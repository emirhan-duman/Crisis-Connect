package com.auralis.crisisconnect.watch

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.AtomicFile
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.service.GattSOSServerService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class DevLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        check(WatchTestHooks.enabled) { "watchTest can run only on an Android emulator" }
        DevLinkFixture.initialize(this)
        WatchTestHooks.install(if (getSharedPreferences("watch_dev_link", MODE_PRIVATE).getBoolean("recovery", false)) RecoveryPreviewDelegate(this) else DevLinkFixture)
        // No normal app bootstrap, cloud, analytics, contacts, or BLE initialization.
    }
}

/** Durable fixture storage is limited to the isolated test APK's private directory. */
object DevLinkFixture : WatchTestHooks.Delegate {
    const val PEER = "local-dev-test-recipient"
    private val messages = mutableListOf<JSONObject>()
    private val events = mutableListOf<JSONObject>()
    private lateinit var report: AtomicFile
    private var savedEmergency: Long? = null
    @Volatile var connection = "Bağlantı başlatılıyor"
    @Synchronized fun initialize(context: Context) {
        report = AtomicFile(File(context.filesDir, "watch-dev-phone.json"))
        if (!report.baseFile.exists()) return
        val data = JSONObject(report.openRead().bufferedReader().use { it.readText() })
        savedEmergency = data.optLong("savedEmergencyStartedAt").takeIf { it > 0 }
        data.optJSONArray("messages")?.let { array -> for (i in 0 until array.length()) messages.add(array.getJSONObject(i)) }
        data.optJSONArray("events")?.let { array -> for (i in 0 until array.length()) events.add(array.getJSONObject(i)) }
    }
    @Synchronized fun reset() {
        check(!GattSOSServerService.isDeclared.value)
        savedEmergency = null; messages.clear(); events.clear(); persist()
    }
    @Synchronized override fun restoredEmergencyTimestamp() = savedEmergency
    @Synchronized override fun sosStateChanged(startedAt: Long?) { savedEmergency = startedAt; persist() }
    @Synchronized override fun state(actual: PhoneWatchState) = actual.copy(
        peerID = PEER, peerName = "Telefondaki test alıcısı",
        lastMessage = messages.lastOrNull()?.optString("text") ?: "Üç kısa cevap bekleniyor"
    )
    @Synchronized override fun reply(command: WatchCommand): String {
        if (command.peerID != PEER) return "noPeer"
        val reply = command.reply ?: return "invalid"
        // Persist before acknowledging. A crash between delivery and ledger commit cannot duplicate it.
        if (messages.none { it.getString("id") == command.id }) {
            messages.add(JSONObject().put("id", command.id).put("reply", reply.name).put("text", reply.turkish))
            persist()
        }
        return "accepted"
    }
    @Synchronized override fun record(command: WatchCommand, receipt: JSONObject) {
        events.add(JSONObject().put("command", JSONObject(command.encode().toString(Charsets.UTF_8)))
            .put("receipt", JSONObject(receipt.toString())))
        while (events.size > 1000) events.removeAt(0)
        persist()
    }
    private fun persist() {
        val output = JSONObject().put("mode", "local-developer-transport-not-data-layer")
            .put("savedEmergencyStartedAt", savedEmergency ?: 0L)
            .put("messages", JSONArray(messages)).put("events", JSONArray(events)).put("state", actualState().json())
        val stream = report.startWrite()
        try { stream.write(output.toString(2).toByteArray()); report.finishWrite(stream) }
        catch (error: Throwable) { report.failWrite(stream); throw error }
    }
    fun actualState() = PhoneWatchState(
        GattSOSServerService.isDeclared.value, GattSOSServerService.isAdvertising.value,
        GattSOSServerService.startTimestampMillis.value?.toString().orEmpty(), updatedAt = System.currentTimeMillis()
    )
    @Synchronized fun screen(): String = buildString {
        append("CRISIS CONNECT\nYEREL SAAT TESTİ\n\n")
        append(if (GattSOSServerService.isDeclared.value) "SOS AKTİF" else if (savedEmergency != null) "SOS GERİ YÜKLENİYOR" else "SOS KAPALI")
        append("\n$connection\nBLE yayını: ${GattSOSServerService.isAdvertising.value}\n")
        append("\nGerçek kişilere bildirim gönderilmez.\nOturum ve cevaplar cihazda saklanır.\n\n")
        append("Telefonda alınan kısa cevaplar (${messages.size}):\n")
        messages.takeLast(8).forEach { append("• ${it.getString("text")}\n") }
        append("\nAlınan komut: ${events.size}\n")
        events.lastOrNull()?.let { append("Son işlem: ${it.getJSONObject("command").getString("action")}\nSonuç: ${it.getJSONObject("receipt").getString("result")}") }
    }
}

/** The listener survives Activity destruction/backgrounding, but never claims to survive force-stop. */
class DevLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var listening: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        check(WatchTestHooks.enabled)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("watch_dev_link", "Yerel saat testi", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, DevLinkActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(18765, Notification.Builder(this, "watch_dev_link")
            .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Crisis Connect — Yerel test")
            .setContentText("Saat test bağlantısı açık. Dış SOS bildirimi kapalı.")
            .setOngoing(true).setContentIntent(open).build())
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (listening?.isActive == true) return START_NOT_STICKY
        val token = getSharedPreferences("watch_dev_link", MODE_PRIVATE).getString("token", null)
        if (token == null) { stopSelf(); return START_NOT_STICKY }
        listening = scope.launch {
            try {
                // Restore only this isolated emulator test session, with the original emergency ID.
                if (WatchTestHooks.delegate?.productionRecovery() != true && DevLinkFixture.restoredEmergencyTimestamp() != null) {
                    withContext(Dispatchers.Main) {
                        startService(Intent(this@DevLinkService, GattSOSServerService::class.java)
                            .putExtra(GattSOSServerService.EXTRA_USER_DECLARED, true))
                    }
                    withTimeout(3_000) { GattSOSServerService.isDeclared.first { it } }
                }
                ServerSocket().use { listener ->
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 18765), 4)
                    server = listener
                    DevLinkFixture.connection = "Yerel bağlantı hazır"
                    while (isActive) {
                        val socket = runCatching { listener.accept() }.getOrNull() ?: break
                        socket.use {
                            it.soTimeout = 4_000
                            runCatching {
                                val payload = DataInputStream(it.getInputStream()).readUTF()
                                require(payload.length <= 8192)
                                val request = JSONObject(payload)
                                require(request.optString("token") == token)
                                val command = requireNotNull(WatchCommand.decode(request.getJSONObject("command").toString().toByteArray()))
                                val receipt = PhoneWatchBridge.handleDeveloperCommand(applicationContext, command)
                                val drop=File(filesDir,"watch-chat-drop-receipt")
                                if(command.action==WatchAction.quickReply && drop.exists()) drop.delete()
                                else DataOutputStream(it.getOutputStream()).writeUTF(receipt.toString())
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { DevLinkFixture.connection = "Bağlantı açılamadı: ${error.javaClass.simpleName}" }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        server?.close(); scope.cancel()
        DevLinkFixture.connection = "Yerel bağlantı kapalı"
        // Stopping the transport is not an explicit SOS stop and must not erase the saved session.
        super.onDestroy()
    }
}

class DevLinkActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var screen: TextView
    private val refresh = object : Runnable {
        override fun run() { screen.text = DevLinkFixture.screen(); handler.postDelayed(this, 250) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(WatchTestHooks.enabled)
        val prefs = getSharedPreferences("watch_dev_link", MODE_PRIVATE)
        val token = intent.getStringExtra("dev_link_token") ?: prefs.getString("token", null)
        if (token == null) {
            setContentView(TextView(this).apply { text = "Yerel testi scripts/open-watch-dev-link.py ile aç." })
            return
        }
        require(UUID.fromString(token).toString() == token)
        check(prefs.edit().putString("token", token).putBoolean("recovery", false).commit())
        WatchTestHooks.install(DevLinkFixture)
        // Only explicit test-run setup resets synthetic data; ordinary reopening always preserves it.
        if (intent.getBooleanExtra("dev_link_reset", false) && savedInstanceState == null) {
            GattSOSServerService.stopBroadcast(this)
            DevLinkFixture.reset()
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = TextView(this).apply { textSize = 19f; setPadding(32, 64, 32, 32) }
        setContentView(screen)
        ContextCompat.startForegroundService(this, Intent(this, DevLinkService::class.java))
    }
    override fun onStart() { super.onStart(); if (::screen.isInitialized) handler.post(refresh) }
    override fun onStop() { handler.removeCallbacks(refresh); super.onStop() }
}
