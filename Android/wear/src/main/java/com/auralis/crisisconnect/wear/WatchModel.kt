package com.auralis.crisisconnect.wear

import android.content.Context
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.crisisconnect.watch.*
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.*
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.json.JSONObject

class WatchModel(private val context: Context, private val simulation: WatchSimulation? = null) : MessageClient.OnMessageReceivedListener {
    var state by mutableStateOf(PhoneWatchState()); private set
    var reachable by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf(""); private set
    var countdown by mutableStateOf<Int?>(null); private set
    var tick by mutableStateOf(0L); private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var poll: Job? = null
    private var arming: Job? = null
    private var sending: Job? = null
    private var connectionFailed = false
    private var pending: WatchCommand? = null
    private var phoneNode: String? = null
    private var receivedElapsed: Long? = null
    private var newestLiveStateTime = 0L
    private var receivedAge = Long.MAX_VALUE
    private var active = false
    private var waitingForPhoneApproval = false
    init {
        if (simulation == null) {
            context.getSharedPreferences("watch_display_v1", Context.MODE_PRIVATE).getString("state", null)?.let { saved ->
                runCatching { PhoneWatchState.decode(JSONObject(saved)) }.getOrNull()?.let { state = it }
            }
        } // Cached state is display-only: reachable/receivedElapsed remain unset.
    }
    val developerLink get() = simulation?.developerLink == true
    val simulating get() = simulation != null
    val turkish get() = simulation?.turkish ?: (Locale.getDefault().language == "tr")
    fun label(en: String, tr: String) = if (turkish) tr else en
    val fresh get() = reachable && receivedElapsed?.let { SystemClock.elapsedRealtime() - it + receivedAge < 30_000 } == true

    fun start() {
        if (active) return
        active = true
        if (!simulating) Wearable.getMessageClient(context).addListener(this)
        poll = scope.launch {
            var seconds = 0
            while (isActive) {
                tick = SystemClock.elapsedRealtime()
                if (seconds++ % 15 == 0 && !busy) send(WatchAction.status)
                delay(1000)
            }
        }
    }
    fun stop() {
        active = false; poll?.cancel(); cancelCountdown()
        if (!simulating) Wearable.getMessageClient(context).removeListener(this)
        receivedElapsed = null
        reachable = false
        sending?.cancel()
        sending = null
        pending = null
        busy = false
    }
    fun close() { stop(); scope.cancel() }
    fun cancelCountdown() { arming?.cancel(); countdown = null }
    fun armSOS() {
        if (!fresh || busy || countdown != null) return
        arming = scope.launch {
            for (left in 5 downTo 1) {
                if (!active) { countdown = null; return@launch }
                countdown = left; vibrate(); delay(1000)
            }
            countdown = null
            if (active) send(WatchAction.startSOS)
        }
    }
    fun send(action: WatchAction, reply: QuickReply? = null, contactID: String? = null) {
        if (busy || (action != WatchAction.status && !fresh)) return
        if (action == WatchAction.contactReply && state.contacts.none { it.id == contactID && it.canSend }) {
            notice = label("Contact unavailable. Refresh your phone connection.", "Kişiye erişilemiyor. Telefon bağlantısını yenile.")
            return
        }
        val command = WatchCommand(action = action, emergencyID = state.emergencyID, peerID = if (action == WatchAction.contactReply) contactID.orEmpty() else state.peerID, reply = reply)
        pending = command; busy = true
        if (action != WatchAction.status) notice = label("Waiting for phone…", "Telefon yanıtı bekleniyor…")
        sending = scope.launch {
            try {
                // Bound discovery and send as well as the receipt wait. A stalled Play
                // services task must not leave the watch permanently busy.
                withTimeoutOrNull(10_000) {
                    if (simulation != null) {
                        val receipt = simulation.exchange(command)
                        if (pending?.id == command.id) {
                            if (receipt == null) fail(command.id) else complete(command, receipt.result, receipt.state)
                        }
                        return@withTimeoutOrNull
                    }
                    val nodes = Wearable.getCapabilityClient(context).getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).awaitWatch().nodes
                    val phone = selectWatchPhone(nodes.map { WatchPhoneCandidate(it.id, it.isNearby) },
                        phoneNode, requirePrevious = action != WatchAction.status) ?: error("No reachable phone")
                    phoneNode = phone.id
                    Wearable.getMessageClient(context).sendMessage(phone.id, WatchPaths.COMMAND, command.encode()).awaitWatch()
                    delay(10_000)
                }
                if (pending?.id == command.id) fail(command.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (pending?.id == command.id) { reachable = false; fail(command.id) }
            }
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.sourceNodeId != phoneNode || event.data.size > 16_384) return
        val json = runCatching { JSONObject(event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        scope.launch {
            when (event.path) {
                WatchPaths.STATE -> runCatching { accept(PhoneWatchState.decode(json)) }
                WatchPaths.RECEIPT -> {
                    val command = pending ?: return@launch
                    if (json.optString("id") != command.id) return@launch
                    val next = runCatching { PhoneWatchState.decode(json.getJSONObject("state")) }.getOrNull() ?: return@launch
                    complete(command, json.optString("result"), next)
                }
            }
        }
    }
    private fun complete(command: WatchCommand, result: String, next: PhoneWatchState) {
        accept(next)
        busy = false; pending = null
        if (command.action != WatchAction.status) {
            waitingForPhoneApproval = result == "phoneConfirmation"
            notice = resultText(result, command.action)
            vibrate()
        }
    }
    private fun accept(next: PhoneWatchState) {
        if (next.updatedAt <= 0 || next.updatedAt < newestLiveStateTime || next.updatedAt > System.currentTimeMillis() + 5_000) return
        newestLiveStateTime = next.updatedAt
        state = next; reachable = true
        if (simulation == null) context.getSharedPreferences("watch_display_v1", Context.MODE_PRIVATE).edit().putString("state", next.json().toString()).apply()
        if (connectionFailed && System.currentTimeMillis() - next.updatedAt < 30_000) {
            connectionFailed = false
            notice = label("Phone reconnected. Status updated.", "Telefon yeniden bağlandı. Durum güncellendi.")
        }
        if (waitingForPhoneApproval && next.sosActive) {
            waitingForPhoneApproval = false
            notice = label("Phone confirmed SOS activation.", "Telefon SOS’un başladığını doğruladı.")
        }
        receivedAge = (System.currentTimeMillis() - next.updatedAt).coerceAtLeast(0)
        receivedElapsed = SystemClock.elapsedRealtime()
    }
    private fun fail(id: String) {
        if (pending?.id != id) return
        pending = null; busy = false; receivedElapsed = null; reachable = false
        connectionFailed = true
        notice = label("No confirmation. Refresh to check your phone. Keep the phone app installed and connected.", "Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon bağlı olmalı.")
    }
    private fun resultText(result: String, action: WatchAction): String = when (result) {
        "accepted" -> when (action) {
            WatchAction.startSOS -> label("Phone confirmed SOS activation.", "Telefon SOS’un başladığını doğruladı.")
            WatchAction.stopSOS -> label("Phone confirmed SOS ended.", "Telefon SOS’un bittiğini doğruladı.")
            WatchAction.quickReply -> if (developerLink) label("Reply received by phone test recipient.", "Cevap telefondaki test alıcısına ulaştı.") else label("Reply handed to the rescue connection.", "Cevap kurtarma bağlantısına iletime verildi.")
            WatchAction.contactReply -> label("Message handed to the phone connection. Delivery unconfirmed.", "Mesaj telefon bağlantısına verildi. Teslim henüz doğrulanmadı.")
            WatchAction.status -> ""
        }
        "phoneConfirmation" -> if (state.recoveryRequired) label("Confirm on the phone to resume the saved SOS. Broadcasting has not resumed yet.", "Kayıtlı SOS’a devam etmek için telefonda onay ver. Yayın henüz yeniden başlamadı.") else label("Tap the phone notification to start SOS. SOS is not confirmed yet.", "SOS’u başlatmak için telefondaki bildirime dokun. SOS henüz doğrulanmadı.")
        "openPhone" -> label("Open Crisis Connect on your phone and check permissions.", "Telefonda Crisis Connect’i açıp izinleri kontrol et.")
        "noPeer" -> if (action == WatchAction.contactReply) label("Contact or phone internet unavailable. No automatic resend.", "Kişi veya telefonun interneti erişilemiyor. Otomatik tekrar yok.") else label("No active rescue connection.", "Aktif kurtarma bağlantısı yok.")
        "stateChanged" -> label("SOS changed. Refresh and try again.", "SOS durumu değişti. Yenile ve tekrar dene.")
        else -> label("Action not confirmed. Refresh before retrying.", "İşlem doğrulanamadı. Tekrar denemeden durumu yenile.")
    }
    private fun vibrate() {
        val vibrator = context.getSystemService(Vibrator::class.java)
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
private suspend fun <T> Task<T>.awaitWatch(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
    addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
