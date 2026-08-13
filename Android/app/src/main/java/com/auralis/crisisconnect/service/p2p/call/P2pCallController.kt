package com.auralis.crisisconnect.service.p2p.call

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.auralis.crisisconnect.R
import android.telecom.DisconnectCause
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.saveCallEvent
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.service.ActiveCallRegistry
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.NotificationAvatarFormatter
import com.auralis.crisisconnect.service.Profile
import com.auralis.crisisconnect.service.RfcommCallNotificationDelegate
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.telecom.RfcommTelecomCoordinator
import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.service.voice.VoipConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Process-wide state machine for voice calls over the P2P GATT chat link (0xCD00).
 *
 * The controller is transport-agnostic: both GATT roles register a [CallLink] —
 * [com.auralis.crisisconnect.service.p2p.P2pGattServerService] (peripheral, reaches centrals via
 * notifications) and [com.auralis.crisisconnect.service.p2p.P2pGattChatManager] (central, reaches
 * the peer's server via writes). Signaling travels as encrypted `call_*` chat-envelope kinds and
 * audio as binary [P2pCallProtocol] frames; see that file for the wire spec.
 *
 * Threading: all state transitions are funneled through [stateLock]; audio jobs run on the
 * engine's own IO coroutines. Only one GATT call can exist at a time.
 */
internal class P2pCallController private constructor(private val appContext: Context) {

    /** A route capable of carrying call traffic to the peer identified by sessionCode. */
    interface CallLink {
        val linkName: String
        fun isReadyForSession(sessionCode: String): Boolean
        suspend fun sendCallSignal(sessionCode: String, payload: JSONObject): Boolean
        /** Single-ATT-write fast path; false when the link cannot take the packet right now. */
        fun trySendCallAudio(sessionCode: String, packet: ByteArray): Boolean
        /** Keep the underlying connection alive for the duration of a call. */
        fun setCallHold(active: Boolean) {}

        /**
         * Call key for sessions with no paired contact AES key (the rescue link supplies its
         * ephemeral ECDH session key here). Null = the link has no key of its own and the
         * contact key must be used.
         */
        fun callKeyForSession(sessionCode: String): ByteArray? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _calls = MutableStateFlow<Map<String, CallUiState>>(emptyMap())
    val calls: StateFlow<Map<String, CallUiState>> = _calls.asStateFlow()

    @Volatile
    private var serverLink: CallLink? = null

    @Volatile
    private var clientLink: CallLink? = null

    /** Additional transports (rescue link etc.) that can also carry calls. */
    private val extraLinks = java.util.concurrent.CopyOnWriteArrayList<CallLink>()

    private var session: GattCallSession? = null

    private val notificationDelegate by lazy {
        ensureNotificationChannels()
        RfcommCallNotificationDelegate(
            context = appContext,
            serviceClass = P2pGattServerService::class.java,
            callChannelId = CALL_CHANNEL_ID,
            ongoingCallChannelId = CALL_ONGOING_CHANNEL_ID,
            callNotificationBaseId = CALL_NOTIFICATION_BASE_ID,
            actionMute = P2pGattServerService.ACTION_CALL_MUTE,
            actionUnmute = P2pGattServerService.ACTION_CALL_UNMUTE,
            actionSpeaker = P2pGattServerService.ACTION_CALL_SPEAKER,
            actionHangup = P2pGattServerService.ACTION_CALL_HANGUP,
            hasPostNotificationsPermission = ::hasPostNotificationsPermission
        )
    }

    private var ringtone: Ringtone? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false

    fun registerServerLink(link: CallLink) {
        serverLink = link
    }

    fun unregisterServerLink(link: CallLink) {
        if (serverLink === link) serverLink = null
    }

    fun registerClientLink(link: CallLink) {
        clientLink = link
    }

    fun unregisterClientLink(link: CallLink) {
        if (clientLink === link) clientLink = null
    }

    fun registerExtraLink(link: CallLink) {
        if (!extraLinks.contains(link)) extraLinks.add(link)
    }

    fun unregisterExtraLink(link: CallLink) {
        extraLinks.remove(link)
    }

    fun hasActiveCall(): Boolean = synchronized(stateLock) { session != null }

    fun isCallReachable(sessionCode: String): Boolean {
        return resolveLink(sessionCode) != null
    }

    // ---------------------------------------------------------------------------------------
    // UI entry points
    // ---------------------------------------------------------------------------------------

    fun startCall(sessionCode: String): Boolean {
        val contact = getContact(appContext, sessionCode)
        val link = resolveLink(sessionCode) ?: run {
            Log.w(TAG, "No ready P2P link for outgoing call session=$sessionCode")
            return false
        }
        // Paired contacts carry their own AES key; keyless links (rescue) supply the
        // ephemeral session key themselves.
        val keyBytes = P2pBleProtocol.decodeBase64(contact?.aesKey ?: "")
            ?.takeIf { it.isNotEmpty() }
            ?: link.callKeyForSession(sessionCode)
            ?: run {
                Log.w(TAG, "Cannot place GATT call without a call key")
                return false
            }
        val callId = UUID.randomUUID().toString()
        if (!ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, sessionCode, callId)) {
            Log.w(TAG, "Refusing outgoing GATT call: another call is active (${ActiveCallRegistry.current()})")
            return false
        }
        val call = synchronized(stateLock) {
            if (session != null) {
                ActiveCallRegistry.release(ActiveCallRegistry.Transport.GATT_P2P, callId)
                return false
            }
            GattCallSession(
                sessionCode = sessionCode,
                callId = callId,
                contactName = contact?.name?.ifBlank { sessionCode } ?: sessionCode,
                isOutgoing = true,
                contactKey = keyBytes,
                callTag = P2pCallProtocol.deriveCallTag(callId),
                localSaltHex = P2pCallProtocol.randomSaltHex(),
                link = link
            ).also { session = it }
        }
        link.setCallHold(true)
        call.state = CallState.Connecting
        publishState()
        RfcommTelecomCoordinator.initialize(appContext)
        RfcommTelecomCoordinator.onOutgoingCall(
            sessionCode = sessionCode,
            callId = callId,
            displayName = call.contactName,
            serviceClass = P2pGattServerService::class.java
        )
        startOfferLoop(call)
        return true
    }

    fun acceptCall(sessionCode: String, callId: String, acceptedByTelecom: Boolean = false): Boolean {
        val call = synchronized(stateLock) {
            session?.takeIf {
                it.sessionCode == sessionCode && it.callId == callId && !it.isOutgoing &&
                    it.state == CallState.Ringing
            }
        } ?: return false
        stopRingtone()
        // Explicitly drop the incoming-call notification (answer/decline) the moment the user
        // accepts — mirrors RfcommForegroundService.acceptCall. Relying on showOngoingCallUi's
        // same-id re-post left the ring notification pinned through (and after) the call on
        // some OEM/Android-14 CallStyle paths.
        if (hasPostNotificationsPermission()) {
            runCatching {
                NotificationManagerCompat.from(appContext)
                    .cancel(notificationDelegate.callNotificationId(call.sessionCode))
            }
        }
        call.timeoutJob?.cancel()
        call.state = CallState.Connecting
        publishState()
        if (!acceptedByTelecom) {
            // Keep the Telecom-managed call in sync when the user answered from our own UI.
            RfcommTelecomCoordinator.answerIncomingCall(sessionCode, callId) { success ->
                if (!success) {
                    Log.w(TAG, "Telecom answer sync failed for GATT call $sessionCode/$callId")
                }
            }
        }
        scope.launch {
            val accepted = sendSignal(
                call,
                P2pCallProtocol.CallSignal(
                    kind = P2pBleProtocol.CHAT_KIND_CALL_ACCEPT,
                    callId = call.callId,
                    sampleRateHz = call.sampleRate,
                    frameMs = call.frameMs,
                    framesPerPacket = call.framesPerPacket,
                    bitrateBps = call.bitrate,
                    saltHex = call.localSaltHex
                )
            )
            if (!accepted) {
                endCallInternal(call, sendEnd = false, reason = "error")
                return@launch
            }
            beginInCall(call)
        }
        return true
    }

    fun rejectCall(sessionCode: String, callId: String) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode && it.callId == callId }
        } ?: return
        scope.launch {
            sendSignal(
                call,
                P2pCallProtocol.CallSignal(
                    kind = P2pBleProtocol.CHAT_KIND_CALL_REJECT,
                    callId = call.callId,
                    reason = "declined"
                )
            )
            endCallInternal(call, sendEnd = false, reason = "declined")
        }
    }

    fun endCall(sessionCode: String) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode }
        } ?: return
        scope.launch { endCallInternal(call, sendEnd = true, reason = "hangup") }
    }

    fun endActiveCallFromNotification(sessionCode: String, callId: String) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode && it.callId == callId }
        } ?: return
        scope.launch { endCallInternal(call, sendEnd = true, reason = "hangup") }
    }

    fun setMuted(sessionCode: String, muted: Boolean) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode }
        } ?: return
        call.muted = muted
        call.audioEngine?.muted = muted
        publishState()
        refreshOngoingNotification(call)
    }

    fun toggleSpeaker(sessionCode: String) {
        val current = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode }?.speakerEnabled
        } ?: return
        setSpeakerEnabled(sessionCode, !current)
    }

    fun setSpeakerEnabled(sessionCode: String, enabled: Boolean) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode }
        } ?: return
        call.speakerEnabled = enabled
        runCatching { audioManager.isSpeakerphoneOn = enabled }
        // When Telecom manages the call, route the request through it as well so the system
        // in-call UI stays in sync.
        RfcommTelecomCoordinator.requestSpeakerRoute(sessionCode, enabled)
        publishState()
        refreshOngoingNotification(call)
    }

    /** Telecom reported the effective audio route (endpoint) for this call. */
    fun onTelecomAudioRouteChanged(sessionCode: String, route: CallAudioRoute?) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == sessionCode }
        } ?: return
        if (route != null) {
            call.speakerEnabled = route == CallAudioRoute.Speaker
        }
        publishState()
        refreshOngoingNotification(call)
    }

    // ---------------------------------------------------------------------------------------
    // Link entry points (both GATT roles call these with decrypted payloads)
    // ---------------------------------------------------------------------------------------

    fun onInboundCallSignal(contact: Contact, payload: JSONObject, link: CallLink) {
        val signal = P2pCallProtocol.parseSignal(payload) ?: return
        when (signal.kind) {
            P2pBleProtocol.CHAT_KIND_CALL_OFFER -> onOffer(contact, signal, link)
            P2pBleProtocol.CHAT_KIND_CALL_RING -> onRing(contact, signal)
            P2pBleProtocol.CHAT_KIND_CALL_ACCEPT -> onAccept(contact, signal)
            P2pBleProtocol.CHAT_KIND_CALL_REJECT,
            P2pBleProtocol.CHAT_KIND_CALL_BUSY -> onRemoteEnd(contact, signal, missed = false)
            P2pBleProtocol.CHAT_KIND_CALL_END -> onRemoteEnd(contact, signal, missed = false)
            P2pBleProtocol.CHAT_KIND_CALL_CFG -> onCfg(contact, signal)
            P2pBleProtocol.CHAT_KIND_CALL_CFG_ACK -> Unit
        }
    }

    /** Peer asked us to change our TX configuration (receiver-driven adaptation). */
    private fun onCfg(contact: Contact, signal: P2pCallProtocol.CallSignal) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == contact.sessionCode && it.callId == signal.callId }
        } ?: return
        if (call.state != CallState.InCall) return
        signal.framesPerPacket?.let { call.framesPerPacket = it.coerceIn(1, 4) }
        signal.bitrateBps?.let { bitrate ->
            val clamped = bitrate.coerceIn(6_000, 24_000)
            if (clamped != call.bitrate) {
                call.bitrate = clamped
                call.audioEngine?.setBitrate(clamped)
                Log.i(TAG, "GATT call TX config applied: bitrate=$clamped fps=${call.framesPerPacket}")
            }
        }
        publishState()
        scope.launch {
            sendSignal(
                call,
                P2pCallProtocol.CallSignal(
                    kind = P2pBleProtocol.CHAT_KIND_CALL_CFG_ACK,
                    callId = call.callId,
                    ok = true
                )
            )
        }
    }

    fun onInboundCallAudio(packet: ByteArray) {
        val call = synchronized(stateLock) { session } ?: return
        if (call.state != CallState.InCall) return
        val rxKey = call.rxKey ?: return
        val decoded = P2pCallProtocol.decodeAudioFrame(rxKey, call.callTag, packet) ?: return
        val frames = P2pCallProtocol.unpackFrameBundle(decoded.bundle) ?: return
        val packetEnd = decoded.seq + frames.size
        var expected: Long
        while (true) {
            expected = call.rxExpectedSeq.get()
            // GCM authenticates bytes but does not by itself reject a previously valid packet.
            // Real-time voice cannot use late audio, so reject both replays and out-of-order
            // packets before they reach the jitter buffer. This is receiver-only and therefore
            // remains wire-compatible with already-installed clients.
            if (decoded.seq < expected) return
            if (call.rxExpectedSeq.compareAndSet(expected, packetEnd)) break
        }
        call.lastRxAtMillis = System.currentTimeMillis()
        // Loss accounting for receiver-driven adaptation: gaps in the frame sequence are
        // frames the sender emitted but we never got (dropped writes/notifications).
        if (expected in 1 until decoded.seq) {
            call.rxFramesLost.addAndGet(
                (decoded.seq - expected).toInt().coerceAtMost(MAX_COUNTED_GAP_FRAMES)
            )
        }
        call.rxFramesReceived.addAndGet(frames.size)
        val engine = call.audioEngine ?: return
        frames.forEachIndexed { index, frame ->
            engine.submitFrame((decoded.seq + index).toInt(), frame)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Signaling handlers
    // ---------------------------------------------------------------------------------------

    private fun onOffer(contact: Contact, signal: P2pCallProtocol.CallSignal, link: CallLink) {
        // Every valid offer carries the caller's audio-key salt; without it we can't derive a
        // collision-free key, so drop malformed/legacy offers.
        if (signal.saltHex.isNullOrBlank()) {
            return
        }
        val now = System.currentTimeMillis()
        val offerTs = signal.timestampMillis
        if (offerTs != null && now - offerTs > OFFER_FRESHNESS_MS) {
            return
        }
        val keyBytes = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return
        val existing = synchronized(stateLock) { session }
        if (existing != null) {
            when {
                existing.callId == signal.callId -> {
                    // Duplicate offer retry: re-acknowledge the ring.
                    scope.launch {
                        sendSignal(
                            existing,
                            P2pCallProtocol.CallSignal(
                                kind = P2pBleProtocol.CHAT_KIND_CALL_RING,
                                callId = existing.callId
                            )
                        )
                    }
                    return
                }
                existing.sessionCode == contact.sessionCode && existing.isOutgoing &&
                    existing.state == CallState.Connecting -> {
                    // Glare: both sides dialed each other. Lexicographically smaller callId wins;
                    // the loser silently drops its own attempt and answers the winner's offer.
                    if (signal.callId < existing.callId) {
                        scope.launch {
                            endCallInternal(existing, sendEnd = false, reason = "glare", silent = true)
                            onOffer(contact, signal, link)
                        }
                    }
                    return
                }
                else -> {
                    scope.launch {
                        sendBusy(contact, signal.callId, link)
                    }
                    return
                }
            }
        }
        if (!ActiveCallRegistry.tryAcquire(
                ActiveCallRegistry.Transport.GATT_P2P,
                contact.sessionCode,
                signal.callId
            )
        ) {
            // A call is already active on another session or transport (RFCOMM); politely
            // reject instead of ringing a second call.
            scope.launch { sendBusy(contact, signal.callId, link) }
            return
        }
        val call = synchronized(stateLock) {
            if (session != null) {
                ActiveCallRegistry.release(ActiveCallRegistry.Transport.GATT_P2P, signal.callId)
                return
            }
            GattCallSession(
                sessionCode = contact.sessionCode,
                callId = signal.callId,
                contactName = signal.senderName?.takeIf { it.isNotBlank() }
                    ?: contact.name.ifBlank { contact.sessionCode },
                isOutgoing = false,
                contactKey = keyBytes,
                callTag = P2pCallProtocol.deriveCallTag(signal.callId),
                localSaltHex = P2pCallProtocol.randomSaltHex(),
                link = link
            ).also {
                it.remoteSaltHex = signal.saltHex
                session = it
            }
        }
        call.applyOfferedConfig(signal)
        link.setCallHold(true)
        call.state = CallState.Ringing
        publishState()
        P2pGattServerService.applyCallForegroundType(appContext, callActive = true)
        RfcommTelecomCoordinator.initialize(appContext)
        RfcommTelecomCoordinator.onIncomingCall(
            sessionCode = call.sessionCode,
            callId = call.callId,
            displayName = call.contactName,
            serviceClass = P2pGattServerService::class.java
        )
        showIncomingCallUi(call)
        scope.launch {
            sendSignal(
                call,
                P2pCallProtocol.CallSignal(
                    kind = P2pBleProtocol.CHAT_KIND_CALL_RING,
                    callId = call.callId
                )
            )
        }
        call.timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            val stillRinging = synchronized(stateLock) {
                session === call && call.state == CallState.Ringing
            }
            if (stillRinging) {
                sendSignal(
                    call,
                    P2pCallProtocol.CallSignal(
                        kind = P2pBleProtocol.CHAT_KIND_CALL_REJECT,
                        callId = call.callId,
                        reason = "timeout"
                    )
                )
                endCallInternal(call, sendEnd = false, reason = "timeout")
            }
        }
    }

    private fun onRing(contact: Contact, signal: P2pCallProtocol.CallSignal) {
        val call = synchronized(stateLock) {
            session?.takeIf {
                it.sessionCode == contact.sessionCode && it.callId == signal.callId && it.isOutgoing
            }
        } ?: return
        if (call.state != CallState.Connecting) return
        call.ringReceived = true
        call.state = CallState.Ringing
        publishState()
    }

    private fun onAccept(contact: Contact, signal: P2pCallProtocol.CallSignal) {
        val call = synchronized(stateLock) {
            session?.takeIf {
                it.sessionCode == contact.sessionCode && it.callId == signal.callId && it.isOutgoing
            }
        } ?: return
        if (call.state == CallState.InCall) return
        if (signal.saltHex.isNullOrBlank()) {
            // A valid accept carries the callee's audio-key salt; without it we can't derive the
            // inbound key, so the call cannot proceed.
            scope.launch { endCallInternal(call, sendEnd = true, reason = "error") }
            return
        }
        call.remoteSaltHex = signal.saltHex
        call.applyOfferedConfig(signal)
        call.offerJob?.cancel()
        call.timeoutJob?.cancel()
        scope.launch { beginInCall(call) }
    }

    private fun onRemoteEnd(contact: Contact, signal: P2pCallProtocol.CallSignal, missed: Boolean) {
        val call = synchronized(stateLock) {
            session?.takeIf { it.sessionCode == contact.sessionCode && it.callId == signal.callId }
        } ?: return
        scope.launch {
            endCallInternal(call, sendEnd = false, reason = signal.reason ?: "remote")
        }
    }

    private suspend fun sendBusy(contact: Contact, callId: String, link: CallLink) {
        val payload = P2pCallProtocol.encodeSignal(
            P2pCallProtocol.CallSignal(
                kind = P2pBleProtocol.CHAT_KIND_CALL_BUSY,
                callId = callId,
                reason = "busy"
            )
        )
        runCatching { link.sendCallSignal(contact.sessionCode, payload) }
    }

    // ---------------------------------------------------------------------------------------
    // Call lifecycle
    // ---------------------------------------------------------------------------------------

    private fun startOfferLoop(call: GattCallSession) {
        call.offerJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                val current = synchronized(stateLock) { session }
                if (current !== call || call.state == CallState.InCall) {
                    return@launch
                }
                val elapsed = System.currentTimeMillis() - startedAt
                if (!call.ringReceived && elapsed >= OFFER_TIMEOUT_MS) {
                    endCallInternal(call, sendEnd = false, reason = "unreachable")
                    return@launch
                }
                if (elapsed >= RING_TIMEOUT_MS) {
                    sendSignal(
                        call,
                        P2pCallProtocol.CallSignal(
                            kind = P2pBleProtocol.CHAT_KIND_CALL_END,
                            callId = call.callId,
                            reason = "timeout"
                        )
                    )
                    endCallInternal(call, sendEnd = false, reason = "timeout")
                    return@launch
                }
                sendSignal(
                    call,
                    P2pCallProtocol.CallSignal(
                        kind = P2pBleProtocol.CHAT_KIND_CALL_OFFER,
                        callId = call.callId,
                        senderName = localDisplayNameOrNull(),
                        timestampMillis = System.currentTimeMillis(),
                        sampleRateHz = call.sampleRate,
                        frameMs = call.frameMs,
                        framesPerPacket = call.framesPerPacket,
                        bitrateBps = call.bitrate,
                        saltHex = call.localSaltHex
                    )
                )
                delay(OFFER_RETRY_INTERVAL_MS)
            }
        }
    }

    private suspend fun beginInCall(call: GattCallSession) {
        // Both salts are now known (caller had the callee's via accept; callee had the caller's
        // via offer), so derive the collision-free directional keys before any audio flows.
        if (!call.deriveKeys()) {
            endCallInternal(call, sendEnd = true, reason = "error")
            return
        }
        val engine = P2pCallAudioEngine(appContext, scope)
        if (!engine.prepare(call.bitrate)) {
            endCallInternal(call, sendEnd = true, reason = "error")
            return
        }
        synchronized(stateLock) {
            if (session !== call) {
                engine.release()
                return
            }
            call.audioEngine = engine
            call.state = CallState.InCall
            call.connectedAt = System.currentTimeMillis()
        }
        P2pGattServerService.applyCallForegroundType(appContext, callActive = true)
        acquireAudioSession()
        engine.muted = call.muted
        engine.startPlayback()
        val frameBuffer = ArrayList<ByteArray>(call.framesPerPacket)
        val started = engine.startCapture { frame ->
            var packet: ByteArray? = null
            synchronized(frameBuffer) {
                frameBuffer.add(frame)
                if (frameBuffer.size >= call.framesPerPacket) {
                    val bundle = runCatching {
                        P2pCallProtocol.packFrameBundle(frameBuffer.toList())
                    }.getOrNull()
                    frameBuffer.clear()
                    val txKey = call.txKey
                    if (bundle != null && txKey != null) {
                        val seq = call.nextFrameSeq.getAndAdd(call.framesPerPacket.toLong())
                        packet = runCatching {
                            P2pCallProtocol.encodeAudioFrame(txKey, call.callTag, seq, bundle)
                        }.getOrNull()
                    }
                }
            }
            packet?.let { encoded ->
                val link = call.link
                if (link == null || !link.trySendCallAudio(call.sessionCode, encoded)) {
                    call.droppedAudioPackets += 1
                }
            }
        }
        if (!started) {
            endCallInternal(call, sendEnd = true, reason = "error")
            return
        }
        RfcommTelecomCoordinator.onCallActive(call.sessionCode, call.callId)
        call.lastRxAtMillis = System.currentTimeMillis()
        startRxWatchdog(call)
        startAdaptation(call)
        publishState()
        showOngoingCallUi(call)
    }

    /** Ends the call when no authenticated audio arrives for [RX_TIMEOUT_MS] (link lost). */
    private fun startRxWatchdog(call: GattCallSession) {
        call.watchdogJob = scope.launch {
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                val current = synchronized(stateLock) { session }
                if (current !== call || call.state != CallState.InCall) {
                    return@launch
                }
                if (System.currentTimeMillis() - call.lastRxAtMillis > RX_TIMEOUT_MS) {
                    Log.w(TAG, "GATT call RX watchdog fired; ending call")
                    endCallInternal(call, sendEnd = true, reason = "rx_timeout")
                    return@launch
                }
            }
        }
    }

    /**
     * Receiver-driven bitrate adaptation: this side measures its own inbound loss (seq gaps)
     * and asks the sender to change its TX config via `call_cfg`. Escalates to 18 kbps after
     * two clean windows, drops back to 12 kbps on sustained loss.
     */
    private fun startAdaptation(call: GattCallSession) {
        call.adaptationJob = scope.launch {
            var consecutiveGoodWindows = 0
            while (isActive) {
                delay(ADAPTATION_WINDOW_MS)
                val current = synchronized(stateLock) { session }
                if (current !== call || call.state != CallState.InCall) {
                    return@launch
                }
                val received = call.rxFramesReceived.getAndSet(0)
                val lost = call.rxFramesLost.getAndSet(0)
                val total = received + lost
                if (total < MIN_WINDOW_FRAMES) {
                    continue
                }
                val lossPercent = lost * 100 / total
                val now = System.currentTimeMillis()
                val cfgAllowed = now - call.lastCfgSentAt >= CFG_MIN_INTERVAL_MS
                when {
                    lossPercent >= DEGRADE_LOSS_PERCENT -> {
                        consecutiveGoodWindows = 0
                        if (call.requestedRemoteBitrate > BITRATE_FLOOR_BPS && cfgAllowed) {
                            call.requestedRemoteBitrate = BITRATE_FLOOR_BPS
                            call.lastCfgSentAt = now
                            Log.i(TAG, "GATT call adaptation: loss=$lossPercent% -> requesting $BITRATE_FLOOR_BPS bps")
                            sendSignal(
                                call,
                                P2pCallProtocol.CallSignal(
                                    kind = P2pBleProtocol.CHAT_KIND_CALL_CFG,
                                    callId = call.callId,
                                    framesPerPacket = 2,
                                    bitrateBps = BITRATE_FLOOR_BPS
                                )
                            )
                        }
                    }
                    lossPercent <= RECOVER_LOSS_PERCENT -> {
                        consecutiveGoodWindows += 1
                        if (consecutiveGoodWindows >= 2 &&
                            call.requestedRemoteBitrate < BITRATE_CEILING_BPS &&
                            cfgAllowed
                        ) {
                            call.requestedRemoteBitrate = BITRATE_CEILING_BPS
                            call.lastCfgSentAt = now
                            Log.i(TAG, "GATT call adaptation: loss=$lossPercent% -> requesting $BITRATE_CEILING_BPS bps")
                            sendSignal(
                                call,
                                P2pCallProtocol.CallSignal(
                                    kind = P2pBleProtocol.CHAT_KIND_CALL_CFG,
                                    callId = call.callId,
                                    framesPerPacket = 2,
                                    bitrateBps = BITRATE_CEILING_BPS
                                )
                            )
                        }
                    }
                    else -> {
                        consecutiveGoodWindows = 0
                    }
                }
            }
        }
    }

    private suspend fun endCallInternal(
        call: GattCallSession,
        sendEnd: Boolean,
        reason: String,
        silent: Boolean = false
    ) {
        val removed = synchronized(stateLock) {
            if (session === call) {
                session = null
                true
            } else {
                false
            }
        }
        if (!removed) return
        call.offerJob?.cancel()
        call.timeoutJob?.cancel()
        call.watchdogJob?.cancel()
        call.adaptationJob?.cancel()
        if (sendEnd) {
            sendSignal(
                call,
                P2pCallProtocol.CallSignal(
                    kind = P2pBleProtocol.CHAT_KIND_CALL_END,
                    callId = call.callId,
                    reason = reason
                )
            )
        }
        call.audioEngine?.release()
        call.audioEngine = null
        releaseAudioSession()
        stopRingtone()
        runCatching { call.link?.setCallHold(false) }
        if (!silent) {
            notificationDelegate.let { delegate ->
                if (hasPostNotificationsPermission()) {
                    runCatching {
                        NotificationManagerCompat.from(appContext)
                            .cancel(delegate.callNotificationId(call.sessionCode))
                    }
                }
            }
        }
        P2pGattServerService.applyCallForegroundType(appContext, callActive = false)
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.GATT_P2P, call.callId)
        val result = when {
            call.connectedAt != null -> RfcommForegroundService.CallResult.ANSWERED
            call.isOutgoing -> if (reason == "declined" || reason == "busy") {
                RfcommForegroundService.CallResult.REJECTED
            } else {
                RfcommForegroundService.CallResult.CANCELED
            }
            reason == "declined" -> RfcommForegroundService.CallResult.REJECTED
            else -> RfcommForegroundService.CallResult.MISSED
        }
        RfcommTelecomCoordinator.onCallEnded(
            sessionCode = call.sessionCode,
            callId = call.callId,
            cause = DisconnectCause(
                when (result) {
                    RfcommForegroundService.CallResult.ANSWERED ->
                        if (sendEnd) DisconnectCause.LOCAL else DisconnectCause.REMOTE
                    RfcommForegroundService.CallResult.MISSED -> DisconnectCause.MISSED
                    RfcommForegroundService.CallResult.REJECTED -> DisconnectCause.REJECTED
                    RfcommForegroundService.CallResult.CANCELED -> DisconnectCause.LOCAL
                }
            )
        )
        if (!silent) {
            // Missed/rejected/answered rows show up in the chat like RFCOMM calls do.
            runCatching {
                saveCallEvent(
                    appContext,
                    RfcommForegroundService.CallEvent(
                        id = call.callId,
                        sessionCode = call.sessionCode,
                        timestampMillis = System.currentTimeMillis(),
                        direction = if (call.isOutgoing) {
                            RfcommForegroundService.CallDirection.OUTGOING
                        } else {
                            RfcommForegroundService.CallDirection.INCOMING
                        },
                        result = result,
                        durationMillis = call.connectedAt?.let {
                            (System.currentTimeMillis() - it).coerceAtLeast(0L)
                        }
                    ),
                    analyticsTransport = "p2p_gatt"
                )
            }.onFailure { Log.w(TAG, "Failed to persist GATT call event", it) }
        }
        if (call.droppedAudioPackets > 0) {
            Log.w(TAG, "GATT call ended reason=$reason droppedAudioPackets=${call.droppedAudioPackets}")
        } else {
            Log.i(TAG, "GATT call ended reason=$reason")
        }
        call.state = CallState.Ended
        // Surface the Ended state briefly (the session is already detached, so publishState()
        // would clear it immediately), then reset the chat UI.
        _calls.value = mapOf(call.sessionCode to call.toUiState())
        scope.launch {
            delay(ENDED_STATE_LINGER_MS)
            publishState()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    private suspend fun sendSignal(call: GattCallSession, signal: P2pCallProtocol.CallSignal): Boolean {
        val payload = runCatching { P2pCallProtocol.encodeSignal(signal) }.getOrNull() ?: return false
        val link = call.link ?: resolveLink(call.sessionCode)?.also { call.link = it } ?: return false
        val sent = runCatching { link.sendCallSignal(call.sessionCode, payload) }.getOrDefault(false)
        if (!sent) {
            // The preferred link failed; retry once on the other role's link if it is ready.
            val fallback = resolveLink(call.sessionCode, exclude = link) ?: return false
            val fallbackSent = runCatching {
                fallback.sendCallSignal(call.sessionCode, payload)
            }.getOrDefault(false)
            if (fallbackSent) {
                call.link = fallback
            }
            return fallbackSent
        }
        return true
    }

    private fun resolveLink(sessionCode: String, exclude: CallLink? = null): CallLink? {
        val candidates = listOfNotNull(clientLink, serverLink) + extraLinks
        return candidates.firstOrNull { it !== exclude && it.isReadyForSession(sessionCode) }
    }

    private fun publishState() {
        val call = synchronized(stateLock) { session }
        _calls.value = if (call == null || call.state == CallState.Ended) {
            emptyMap()
        } else {
            mapOf(call.sessionCode to call.toUiState())
        }
    }

    private suspend fun localDisplayNameOrNull(): String? {
        return runCatching { getSavedUserName(appContext).first().trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // ---------------------------------------------------------------------------------------
    // Incoming call UI (reuses the RFCOMM notification delegate; it is transport-agnostic)
    // ---------------------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun showIncomingCallUi(call: GattCallSession) {
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentIntent = notificationDelegate.createCallScreenPendingIntent(
            sessionCode = call.sessionCode,
            callId = call.callId,
            flags = pendingIntentFlags,
            requestOffset = 0
        )
        val acceptPending = notificationDelegate.createCallActionPendingIntent(
            action = P2pGattServerService.ACTION_CALL_ACCEPT,
            sessionCode = call.sessionCode,
            callId = call.callId,
            flags = pendingIntentFlags,
            requestOffset = 1
        )
        val rejectPending = notificationDelegate.createCallActionPendingIntent(
            action = P2pGattServerService.ACTION_CALL_REJECT,
            sessionCode = call.sessionCode,
            callId = call.callId,
            flags = pendingIntentFlags,
            requestOffset = 2
        )
        val avatarBitmap = NotificationAvatarFormatter.resolveContactAvatarBitmap(
            context = appContext,
            sessionCode = call.sessionCode,
            contactName = call.contactName
        )
        val caller = Person.Builder()
            .setName(call.contactName)
            .setImportant(true)
            .setIcon(
                avatarBitmap?.let { IconCompat.createWithBitmap(it) }
                    ?: IconCompat.createWithResource(appContext, R.drawable.ic_splash_plain)
            )
            .build()
        val useCallStyle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val notification = notificationDelegate.buildIncomingCallNotification(
            name = call.contactName,
            caller = caller,
            contentIntent = contentIntent,
            acceptPending = acceptPending,
            rejectPending = rejectPending,
            useCallStyle = useCallStyle
        ).build()
        if (hasPostNotificationsPermission()) {
            runCatching {
                NotificationManagerCompat.from(appContext)
                    .notify(notificationDelegate.callNotificationId(call.sessionCode), notification)
            }
        } else {
            Log.w(TAG, "Skipping incoming GATT call notification: POST_NOTIFICATIONS not granted")
        }
        ringtone = notificationDelegate.startRingtone(ringtone)
    }

    @SuppressLint("MissingPermission")
    private fun showOngoingCallUi(call: GattCallSession) {
        stopRingtone()
        if (!hasPostNotificationsPermission()) return
        val notification = buildOngoingNotification(call) ?: return
        runCatching {
            NotificationManagerCompat.from(appContext)
                .notify(notificationDelegate.callNotificationId(call.sessionCode), notification)
        }
    }

    private fun refreshOngoingNotification(call: GattCallSession) {
        if (call.state != CallState.InCall) return
        showOngoingCallUi(call)
    }

    private fun buildOngoingNotification(call: GattCallSession): Notification? {
        return runCatching {
            notificationDelegate.buildOngoingCallNotification(
                sessionCode = call.sessionCode,
                callId = call.callId,
                muted = call.muted,
                speakerEnabled = call.speakerEnabled,
                encrypted = true,
                startedAt = call.startedAt,
                connectedAt = call.connectedAt,
                remoteUserName = call.contactName,
                useCallStyle = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            )
        }.getOrNull()
    }

    private fun stopRingtone() {
        notificationDelegate.stopRingtone(ringtone)
        ringtone = null
    }

    private fun hasPostNotificationsPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CALL_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CALL_CHANNEL_ID,
                    appContext.getString(R.string.chat_call_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = appContext.getString(R.string.chat_call_channel_description)
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                }
            )
        }
        if (manager.getNotificationChannel(CALL_ONGOING_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CALL_ONGOING_CHANNEL_ID,
                    appContext.getString(R.string.chat_call_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = appContext.getString(R.string.chat_call_channel_description)
                    setSound(null, null)
                }
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Audio session (focus + communication mode); mirrors RfcommForegroundService's handling
    // ---------------------------------------------------------------------------------------

    private fun acquireAudioSession() {
        synchronized(stateLock) {
            if (hasAudioFocus) return
            previousAudioMode = audioManager.mode
            previousSpeakerphone = audioManager.isSpeakerphoneOn
            hasAudioFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .build()
                val granted = runCatching {
                    audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }.getOrDefault(false)
                if (granted) {
                    audioFocusRequest = request
                }
                granted
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    audioManager.requestAudioFocus(
                        null,
                        AudioManager.STREAM_VOICE_CALL,
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                    ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                }.getOrDefault(false)
            }
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
            runCatching { audioManager.isMicrophoneMute = false }
        }
    }

    private fun releaseAudioSession() {
        synchronized(stateLock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
            } else if (hasAudioFocus) {
                @Suppress("DEPRECATION")
                runCatching { audioManager.abandonAudioFocus(null) }
            }
            audioFocusRequest = null
            hasAudioFocus = false
            runCatching { audioManager.mode = previousAudioMode }
            runCatching { audioManager.isSpeakerphoneOn = previousSpeakerphone }
        }
    }

    // ---------------------------------------------------------------------------------------

    private class GattCallSession(
        val sessionCode: String,
        val callId: String,
        val contactName: String,
        val isOutgoing: Boolean,
        val contactKey: ByteArray,
        val callTag: ByteArray,
        // This side's fresh salt (rides our offer/accept); protects our outbound audio key.
        val localSaltHex: String,
        @Volatile var link: CallLink?
    ) {
        // Directional AES-GCM keys, derived only once BOTH salts are known (i.e. at activation),
        // so each side's outbound key is unique per call regardless of callId reuse by a peer.
        @Volatile var remoteSaltHex: String? = null
        @Volatile var txKey: ByteArray? = null
        @Volatile var rxKey: ByteArray? = null

        /** Derive both directional keys; requires [remoteSaltHex] to be set. Returns false if not. */
        fun deriveKeys(): Boolean {
            val remoteSalt = remoteSaltHex ?: return false
            // Our TX stream uses OUR salt; the peer's TX stream (our RX) uses the peer's salt.
            txKey = P2pCallProtocol.deriveDirectionalKey(
                contactKey, callId, callerToCallee = isOutgoing, directionSaltHex = localSaltHex
            )
            rxKey = P2pCallProtocol.deriveDirectionalKey(
                contactKey, callId, callerToCallee = !isOutgoing, directionSaltHex = remoteSalt
            )
            return true
        }

        @Volatile var state: CallState = CallState.Idle
        @Volatile var muted: Boolean = false
        @Volatile var speakerEnabled: Boolean = false
        @Volatile var ringReceived: Boolean = false
        val startedAt: Long = System.currentTimeMillis()
        @Volatile var connectedAt: Long? = null
        @Volatile var audioEngine: P2pCallAudioEngine? = null
        @Volatile var offerJob: Job? = null
        @Volatile var timeoutJob: Job? = null
        @Volatile var watchdogJob: Job? = null
        @Volatile var adaptationJob: Job? = null
        @Volatile var droppedAudioPackets: Int = 0
        val nextFrameSeq = AtomicLong(0)

        // RX health tracking (watchdog + receiver-driven adaptation).
        @Volatile var lastRxAtMillis: Long = System.currentTimeMillis()
        val rxExpectedSeq = AtomicLong(0)
        val rxFramesReceived = AtomicInteger(0)
        val rxFramesLost = AtomicInteger(0)
        @Volatile var requestedRemoteBitrate: Int = Profile.NB.defaultBitrate
        @Volatile var lastCfgSentAt: Long = 0L

        // Negotiated audio configuration; NB profile defaults per the GATT budget.
        @Volatile var sampleRate: Int = VoipConfig.SAMPLE_RATE_HZ
        @Volatile var frameMs: Int = VoipConfig.FRAME_DURATION_MS
        @Volatile var framesPerPacket: Int = Profile.NB.defaultFramesPerPacket
        @Volatile var bitrate: Int = Profile.NB.defaultBitrate

        fun applyOfferedConfig(signal: P2pCallProtocol.CallSignal) {
            // The wire codec is pinned to 16 kHz / 20 ms Opus; only fps and bitrate are
            // negotiable, and both are clamped to values every peer can sustain.
            signal.framesPerPacket?.let { framesPerPacket = it.coerceIn(1, 4) }
            signal.bitrateBps?.let { bitrate = it.coerceIn(6_000, 24_000) }
        }

        fun toUiState(): CallUiState = CallUiState(
            sessionCode = sessionCode,
            callId = callId,
            state = state,
            isOutgoing = isOutgoing,
            encrypted = true,
            sampleRate = sampleRate,
            channels = 1,
            frameMs = frameMs,
            bitrate = bitrate,
            startedAt = startedAt,
            connectedAt = connectedAt,
            muted = muted,
            speakerEnabled = speakerEnabled,
            currentRoute = if (speakerEnabled) CallAudioRoute.Speaker else CallAudioRoute.Earpiece,
            availableRoutes = listOf(CallAudioRoute.Earpiece, CallAudioRoute.Speaker),
            profile = Profile.NB,
            jitterCapacity = Profile.NB.jitterCapacity
        )
    }

    companion object {
        private const val TAG = "P2pCallController"

        // Same channel ids as the RFCOMM call path so both transports share the user's
        // per-channel notification preferences. Creation is idempotent on both sides.
        private const val CALL_CHANNEL_ID = "dcs_rfcomm_calls_v4"
        private const val CALL_ONGOING_CHANNEL_ID = "dcs_rfcomm_ongoing_calls_v2"

        // Distinct from RFCOMM's base (15000) so simultaneous transports never collide.
        private const val CALL_NOTIFICATION_BASE_ID = 16000

        private const val OFFER_RETRY_INTERVAL_MS = 2_000L
        private const val OFFER_TIMEOUT_MS = 10_000L
        private const val RING_TIMEOUT_MS = 30_000L
        private const val OFFER_FRESHNESS_MS = 12_000L
        private const val ENDED_STATE_LINGER_MS = 1_500L

        // RX watchdog + receiver-driven bitrate adaptation.
        private const val WATCHDOG_TICK_MS = 1_000L
        private const val RX_TIMEOUT_MS = 5_000L
        private const val ADAPTATION_WINDOW_MS = 5_000L
        private const val CFG_MIN_INTERVAL_MS = 10_000L
        private const val DEGRADE_LOSS_PERCENT = 15
        private const val RECOVER_LOSS_PERCENT = 5
        private const val MIN_WINDOW_FRAMES = 50
        private const val MAX_COUNTED_GAP_FRAMES = 200
        private const val BITRATE_FLOOR_BPS = 12_000
        private const val BITRATE_CEILING_BPS = 18_000

        @Volatile
        private var instance: P2pCallController? = null

        fun shared(context: Context): P2pCallController {
            return instance ?: synchronized(this) {
                instance ?: P2pCallController(context.applicationContext).also { instance = it }
            }
        }
    }
}
