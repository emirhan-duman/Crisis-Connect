package com.auralis.crisisconnect.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/** App-wide pacing; observes real traffic and OS capabilities, never runs speed-test probes. */
object DisasterTrafficController {
    private val policy = DisasterTrafficPolicy()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private val _presence = MutableStateFlow(TrafficPresence.NORMAL)
    val presence = _presence.asStateFlow()
    private var online = true
    @Volatile var retryAfterMs = 30_000L
        private set
    @Volatile private var serverHoldUntil = 0L
    @Volatile private var recoveryPacingUntil = 0L
    private val sendMutex = Mutex()
    private var nextSendAt = 0L
    private var context: Context? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    @Volatile var foreground = false
        private set

    @Synchronized fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        TrafficMeasurements.start(context)
        val prefs = context.getSharedPreferences("disaster_traffic", Context.MODE_PRIVATE)
        policy.emergencyUntil = SystemClock.elapsedRealtime() +
            (prefs.getLong("emergencyUntil", 0) - System.currentTimeMillis()).coerceIn(0, 1_800_000)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        fun refresh() {
            val caps = manager?.activeNetwork?.let { manager.getNetworkCapabilities(it) }
            synchronized(this) {
                val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                val restricted = SatelliteNetworkStatus.isConstrained(context) ||
                    (cellular && manager?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED)
                policy.updateNetwork(restricted, cellular, SystemClock.elapsedRealtime())
                publish()
            }
        }
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
        }.also { manager?.registerDefaultNetworkCallback(it) }
        refresh()
        androidx.core.content.ContextCompat.registerReceiver(context.applicationContext,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) = refresh()
            }, android.content.IntentFilter(ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { while (isActive) { delay(15_000); synchronized(this@DisasterTrafficController) { publish() } } }
    }

    fun setForeground(active: Boolean) { foreground = active; if (!active) TrafficMeasurements.checkpoint() }
    fun permits(work: NetworkWork, userInitiated: Boolean = false): Boolean =
        TrafficBudget.permits(work, saving.value, userInitiated)
    fun applyServerPolicy(policy: Map<*, *>?) {
        retryAfterMs = (policy?.get("retryAfterMs") as? Number)?.toLong()?.coerceIn(30_000, 300_000) ?: 30_000
    }

    @Synchronized fun setEmergency(active: Boolean) {
        if (!active && !policy.emergencyActive) return
        if (active) policy.emergencyActive = true else policy.stopEmergency(SystemClock.elapsedRealtime())
        context?.getSharedPreferences("disaster_traffic", Context.MODE_PRIVATE)?.edit()
            ?.putLong("emergencyUntil", System.currentTimeMillis() + if (active) 1_800_000 else 300_000)?.apply()
        publish()
    }

    @Synchronized fun record(succeeded: Boolean, durationMs: Long, error: Throwable? = null) {
        val retry = (error as? FirebaseFunctionsException)?.details as? Map<*, *>
        (retry?.get("retryAfterMs") as? Number)?.toLong()?.coerceIn(30_000, 300_000)?.let {
            serverHoldUntil = maxOf(serverHoldUntil, SystemClock.elapsedRealtime() + it)
        }
        val transient = when (error) {
            is FirebaseFunctionsException -> error.code in setOf(FirebaseFunctionsException.Code.UNAVAILABLE,
                FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED)
            is FirebaseFirestoreException -> error.code in setOf(FirebaseFirestoreException.Code.UNAVAILABLE,
                FirebaseFirestoreException.Code.DEADLINE_EXCEEDED, FirebaseFirestoreException.Code.RESOURCE_EXHAUSTED)
            is java.io.IOException -> true
            else -> error == null
        }
        policy.record(SystemClock.elapsedRealtime(), succeeded, durationMs, transient)
        publish()
    }

    private fun publish() {
        val next = policy.mode(SystemClock.elapsedRealtime()) != DisasterTrafficPolicy.Mode.NORMAL
        if (_saving.value && !next) recoveryPacingUntil = SystemClock.elapsedRealtime() + 60_000
        _saving.value = next
        _presence.value = TrafficBudget.presence(online, policy.emergencyActive, _saving.value)
        TrafficMeasurements.transition(_presence.value.name.lowercase())
    }

    fun allowsText(template: Int, text: String): Boolean = !saving.value ||
        DisasterTrafficPolicy.allowsCompact(template, text.toByteArray(Charsets.UTF_8).size)

    /** A single gate across all conversations prevents reconnect bursts. SOS bypasses the text queue. */
    suspend fun awaitSendSlot(emergency: Boolean) {
        if (!emergency) delay((serverHoldUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        if (emergency || (!saving.value && SystemClock.elapsedRealtime() >= recoveryPacingUntil)) return
        sendMutex.withLock {
            delay((nextSendAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            currentCoroutineContext().ensureActive()
            nextSendAt = SystemClock.elapsedRealtime() + 5_000 + Random.nextLong(0, 2_001)
        }
    }
}
