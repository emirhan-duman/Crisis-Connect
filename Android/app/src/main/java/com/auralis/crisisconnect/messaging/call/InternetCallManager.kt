package com.auralis.crisisconnect.messaging.call

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.telecom.DisconnectCause
import android.util.Log
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.telecom.RfcommTelecomCoordinator
import com.auralis.crisisconnect.service.CallAudioRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID

/**
 * Process-wide state machine for REAL-TIME internet voice calls (WebRTC). This is the internet twin
 * of [com.auralis.crisisconnect.service.p2p.call.P2pCallController] (which carries audio over the P2P
 * GATT link): here the media rides a WebRTC PeerConnection (Opus over SRTP, P2P via ICE/STUN), while
 * only the tiny SDP/ICE signaling travels over our existing E2E relay — sealed end-to-end like a
 * chat message, so the server never sees call contents.
 *
 * Audio-only for now. ICE uses public STUN plus a Cloudflare Realtime global-anycast TURN relay
 * (needed for symmetric-NAT / carrier-CGNAT peers where STUN-only fails) — the relay creds are minted
 * per-session by [TurnCredentialsProvider] and never stored in the app. The signaling is injected via
 * [SignalSender] so this module doesn't depend on the messaging transport (avoids a dependency cycle);
 * the receive side calls [onSignal].
 */
object InternetCallManager {
    private const val TAG = "InternetCall"
    // Video encoder bitrate ceilings (bps). A high cap does NOT speed up a good network — GCC discovers
    // capacity on its own — it only stops runaway oversending from wrecking a constrained/relayed path.
    private const val CAMERA_MAX_BITRATE = 2_500_000 // solid 720p30 conferencing ceiling
    private const val SCREEN_MAX_BITRATE = 3_500_000 // detail/text needs headroom to stay legible
    // When camera + screen stream SIMULTANEOUSLY, the pair is rebalanced so it still fits a
    // constrained / TURN-relayed uplink — the screen (text!) keeps most of the budget.
    private const val CAMERA_SHARED_BITRATE = 1_200_000
    // libwebrtc's Opus default targets ~32 kbps; 64 kbps is near-transparent for speech and cheap.
    private const val AUDIO_MAX_BITRATE = 64_000
    // A transient DISCONNECTED usually self-heals (NAT rebind, brief radio gap) — give it this long
    // before escalating to an ICE restart / teardown instead of killing the call instantly.
    private const val DISCONNECT_GRACE_MS = 12_000L
    private const val MAX_ICE_RESTARTS = 3
    // Trickle-ICE candidates are collected this long and shipped as ONE "ice-batch" relay message
    // (see pendingLocalIce). Long enough to catch a gathering burst, short enough not to slow setup.
    private const val ICE_BATCH_WINDOW_MS = 300L
    // Unanswered calls stop ringing after this long (caller hangs up, callee records a miss).
    private const val RING_TIMEOUT_MS = 45_000L
    private const val STATS_INTERVAL_MS = 2_500L
    // Remote frames stop when the peer turns their camera/screen off. There's no mute/unmute event for
    // a remote track on Android (unlike the web), so a frame watchdog flips the far view back to the
    // avatar after this long without frames — debounced so a brief network stall doesn't blink.
    private const val REMOTE_VIDEO_OFF_DELAY_MS = 2_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    enum class State { IDLE, DIALING, INCOMING, CONNECTING, ACTIVE, ENDED }

    /** Coarse live network quality for the in-call indicator, derived from getStats RTT + loss. */
    enum class CallQuality { GOOD, FAIR, POOR }

    data class CallInfo(
        val callId: String,
        val peerUid: String,
        val peerName: String,
        val incoming: Boolean,
        val state: State,
        val muted: Boolean = false,
        // Video is available only when a video m-line was negotiated (both peers support it). The UI
        // uses these to show camera / screen-share controls — internet calls only, never Bluetooth.
        val canUseVideo: Boolean = false,
        val cameraOn: Boolean = false,
        val sharingScreen: Boolean = false,
        val remoteVideo: Boolean = false,
        // True while the peer is screen-sharing (second video slot receiving frames).
        val remoteScreen: Boolean = false,
        val usingFrontCamera: Boolean = true,
        // Conversation key of the peer — lets the notification/overlay resolve the saved avatar.
        val sessionCode: String = "",
        // Wall-clock time the media path connected; drives the in-call duration/chronometer.
        val connectedAt: Long? = null,
        val speakerOn: Boolean = false,
        // True while the transport dropped and we're inside the recovery grace / ICE restart window.
        val reconnecting: Boolean = false,
        val quality: CallQuality = CallQuality.GOOD
    )

    /** Live local + remote video tracks for the renderer surfaces; null when that side has no video. */
    data class VideoStreams(
        val local: VideoTrack? = null,
        val localScreen: VideoTrack? = null,
        val remote: VideoTrack? = null,
        val remoteScreen: VideoTrack? = null
    )

    /** Sends a JSON call signal to [contact] over the E2E relay. Injected by the messaging layer. */
    fun interface SignalSender {
        suspend fun send(contact: Contact, signalJson: String): Boolean
    }

    private val _call = MutableStateFlow<CallInfo?>(null)
    val call: StateFlow<CallInfo?> = _call.asStateFlow()

    private val _videoStreams = MutableStateFlow(VideoStreams())
    val videoStreams: StateFlow<VideoStreams> = _videoStreams.asStateFlow()

    private var appContext: Context? = null
    private var signalSender: SignalSender? = null
    // Per-call override: authority channel calls signal over a Firestore callSignals subcollection
    // (web-compatible) instead of the default E2E relay. Set for the duration of one call, reset on
    // cleanup, so the same WebRTC engine + overlay serve both relay (citizen 1:1) and channel calls.
    private var callSender: SignalSender? = null
    private var factory: PeerConnectionFactory? = null
    private var eglBase: EglBase? = null

    private val lock = Any()
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    // Video (camera + screen share). The video m-line is pre-negotiated (SEND_RECV) like the web, so a
    // camera/screen track is swapped onto `videoSender` at runtime via setTrack() — no renegotiation.
    // Camera and screen keep independent capturer→source→track sets so the camera can stay live under a
    // screen share and resume when it stops (mirrors lib/messaging/webrtc-call.ts).
    private var videoSender: RtpSender? = null
    // Second pre-negotiated video m-line so camera + screen can stream SIMULTANEOUSLY. Null when the
    // peer is an older single-m-line build — then screen share falls back to the legacy behavior of
    // swapping the camera track out for the screen on the one shared sender.
    private var screenSender: RtpSender? = null
    // Receiver id of the screen m-line, captured ONCE alongside the senders. libwebrtc's Java
    // PeerConnection.getTransceivers() DISPOSES every wrapper it handed out before (including the
    // ones addTransceiver returned, whose senders we cache above) — so it must never be re-queried
    // mid-call or videoSender/screenSender die ("RtpSender has been disposed" on the camera toggle).
    private var screenReceiverId: String? = null
    private var audioSender: RtpSender? = null
    private var cameraCapturer: VideoCapturer? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var remoteCameraTrack: VideoTrack? = null
    private var remoteScreenTrack: VideoTrack? = null
    private var remoteCameraSink: VideoSink? = null
    private var remoteScreenSink: VideoSink? = null
    private var remoteVideoWatchdog: Job? = null
    @Volatile private var lastRemoteCameraFrameAt = 0L
    @Volatile private var lastRemoteScreenFrameAt = 0L
    private var cameraOn = false
    private var sharingScreen = false
    private var usingFrontCamera = true
    @Volatile private var remoteCameraOn = false
    @Volatile private var remoteScreenOn = false
    private var contact: Contact? = null
    private var callId: String? = null
    private var isCaller: Boolean = false
    // Phone-call audio routing (parity with the Bluetooth call stack): MODE_IN_COMMUNICATION routes
    // voice to the earpiece with proper AEC, and is restored to the pre-call values on teardown.
    private var speakerOn = false
    private var previousAudioMode: Int? = null
    private var previousSpeakerphone: Boolean? = null
    private var audioModeConfigured = false
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothScoStarted = false
    // Resilience: transient-drop grace, caller-driven ICE restarts, network-change watcher, ring
    // timeout and the in-call stats monitor. All cancelled/unregistered in cleanup().
    private var disconnectGraceJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var statsJob: Job? = null
    private var restartAttempts = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNetworkHandle: Long? = null
    // Cumulative inbound counters from the previous stats sample (for interval loss fractions).
    private var loggedVideoDecode = false
    private var prevPacketsLost = 0L
    private var prevPacketsReceived = 0L
    // ICE candidates that arrive before the remote description is set are buffered and applied after.
    private val pendingRemoteCandidates = ArrayList<IceCandidate>()
    private var remoteDescriptionSet = false
    // Outbound trickle-ICE batching: one relay message per candidate melts weak devices — every
    // candidate costs a Signal-ratchet seal + a Firestore write here and a serialized decrypt + Room
    // write on the peer (a J2 Core took ~14s to chew through one call's 73 signals). Candidates are
    // collected for ICE_BATCH_WINDOW_MS and shipped as one "ice-batch" signal instead. Guarded by
    // its own monitor (NOT `lock`: onIceCandidate runs on the WebRTC signaling thread, see the
    // deadlock note in onConnectionChange).
    private val pendingLocalIce = ArrayList<JSONObject>()
    private var iceFlushJob: Job? = null

    /** One-time wiring: application context + the outbound signaling sender. */
    fun init(context: Context, sender: SignalSender) {
        appContext = context.applicationContext
        signalSender = sender
        // Register with the system Telecom stack so internet calls show in the system call UI /
        // lock-screen ringer (self-managed calls via androidx.core.telecom). Best-effort; the app
        // overlay is the fallback UI on devices/versions where Telecom is unavailable.
        runCatching { RfcommTelecomCoordinator.initialize(context.applicationContext) }
        // Warm the Cloudflare TURN creds now so the first call already has a relay (mobile/CGNAT
        // peers can't connect on STUN alone). Best-effort; failures leave STUN-only in place.
        scope.launch { runCatching { TurnCredentialsProvider.refresh() } }
    }

    fun hasActiveCall(): Boolean = synchronized(lock) { peerConnection != null }

    // ---- outbound / control ----

    /** Places an outgoing call to [target]. Requires RECORD_AUDIO permission (checked by the caller UI). */
    /**
     * Places an outgoing call to [target]. [sender] overrides the default relay transport for this
     * call (e.g. an authority channel's Firestore callSignals); pass null for the citizen relay.
     */
    fun startCall(target: Contact, sender: SignalSender? = null) {
        synchronized(lock) {
            if (peerConnection != null) {
                Log.w(TAG, "Refusing outgoing call: another call is active")
                return
            }
            if (!target.supportsInternet) {
                Log.w(TAG, "Cannot place internet call: contact has no internet identity")
                return
            }
            callSender = sender
            // Keep TURN creds fresh across long-running app sessions (helps a retry if this call's
            // cached creds have expired); the peer connection below uses whatever is cached now.
            scope.launch { runCatching { TurnCredentialsProvider.refresh() } }
            val id = UUID.randomUUID().toString()
            contact = target
            callId = id
            isCaller = true
            remoteDescriptionSet = false
            pendingRemoteCandidates.clear()
            _call.value = CallInfo(
                id, target.peerUid, target.name,
                incoming = false, state = State.DIALING, sessionCode = target.sessionCode
            )
            val pc = createPeerConnection() ?: run { cleanup(State.ENDED); return }
            peerConnection = pc
            addLocalAudio(pc)
            addVideoTransceivers(pc)
            configureCallAudio()
            scheduleRingTimeout(id)
            registerNetworkWatcher()
            appContext?.let { InternetCallForegroundService.start(it) }
            runCatching {
                RfcommTelecomCoordinator.onOutgoingCall(id, id, target.name, InternetCallForegroundService::class.java)
            }
            pc.createOffer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver(), sdp)
                    sendSignal(jsonOf("offer", id).put("sdp", sdp.description))
                }
            }, audioConstraints())
        }
    }

    /** Answers the current incoming call. */
    fun accept() {
        synchronized(lock) {
            val pc = peerConnection ?: return
            val id = callId ?: return
            if (isCaller) return
            ringTimeoutJob?.cancel()
            _call.value = _call.value?.copy(state = State.CONNECTING)
            configureCallAudio()
            appContext?.let { InternetCallForegroundService.start(it) }
            callId?.let { id -> runCatching { RfcommTelecomCoordinator.answerIncomingCall(id, id) {} } }
            pc.createAnswer(object : SimpleSdpObserver() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(SimpleSdpObserver(), sdp)
                    sendSignal(jsonOf("answer", id).put("sdp", sdp.description))
                }
            }, audioConstraints())
        }
    }

    /** Rejects the current incoming call. */
    fun reject() {
        val id: String?
        synchronized(lock) {
            id = callId ?: return
            sendSignal(jsonOf("reject", id))
            _call.value = _call.value?.copy(state = State.ENDED)
        }
        scheduleTeardown(id)
    }

    /** Ends the active/outgoing call. */
    fun end() {
        val id: String?
        synchronized(lock) {
            id = callId
            if (id != null) sendSignal(jsonOf("end", id))
            _call.value = _call.value?.copy(state = State.ENDED)
        }
        scheduleTeardown(id)
    }

    /**
     * Runs [cleanup] on a background thread, but only if the call it was scheduled for is still the
     * current one (guards a stale teardown from wiping a newer call's state).
     *
     * Why async: [PeerConnection.close] blocks until the WebRTC signaling thread drains its observer
     * callbacks, and our [PeerConnection.Observer.onConnectionChange] used to take [lock]
     * synchronously on that same thread. Running close() while holding [lock] on the UI thread
     * deadlocked the two (main ⇄ signaling), freezing the app into an "executing service" ANR the
     * moment the user hung up. Teardown therefore never runs on the main thread, and observer
     * callbacks only ever *schedule* it.
     */
    private fun scheduleTeardown(expectedCallId: String?) {
        scope.launch(Dispatchers.IO) {
            synchronized(lock) {
                if (callId == expectedCallId) cleanup(State.ENDED)
            }
        }
    }

    fun setMuted(muted: Boolean) {
        synchronized(lock) {
            localAudioTrack?.setEnabled(!muted)
            _call.value = _call.value?.copy(muted = muted)
        }
    }

    /** Routes call audio to the loudspeaker (true) or back to the headset/earpiece (false). */
    fun setSpeaker(on: Boolean) {
        synchronized(lock) {
            speakerOn = on
            if (audioModeConfigured) applyAudioRouting()
            _call.value = _call.value?.copy(speakerOn = on)
        }
    }

    /**
     * Phone-call audio routing, mirroring the Bluetooth call stack: MODE_IN_COMMUNICATION gives the
     * earpiece + hardware echo cancellation instead of the media speaker. Previous values are saved
     * and restored on teardown. Called with [lock] held.
     */
    private fun configureCallAudio() {
        if (audioModeConfigured) return
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousAudioMode = manager.mode
        previousSpeakerphone = manager.isSpeakerphoneOn
        runCatching { manager.mode = AudioManager.MODE_IN_COMMUNICATION }
        audioModeConfigured = true
        applyAudioRouting()
        registerAudioDeviceWatcher(manager)
    }

    private fun restoreCallAudio() {
        if (!audioModeConfigured) return
        audioModeConfigured = false
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioDeviceCallback?.let { runCatching { manager.unregisterAudioDeviceCallback(it) } }
        audioDeviceCallback = null
        if (bluetoothScoStarted) {
            bluetoothScoStarted = false
            runCatching { manager.stopBluetoothSco() }
            runCatching { manager.isBluetoothScoOn = false }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.clearCommunicationDevice() }
        }
        previousAudioMode?.let { runCatching { manager.mode = it } }
        previousSpeakerphone?.let { runCatching { manager.isSpeakerphoneOn = it } }
        previousAudioMode = null
        previousSpeakerphone = null
    }

    /**
     * Headset-aware routing. Priority: loudspeaker toggle > Bluetooth headset > wired/USB headset >
     * earpiece. Android 12+ uses setCommunicationDevice; older builds need the SCO link started by
     * hand or a Bluetooth headset never carries call audio.
     */
    /** Headset beats the earpiece when one is connected — same precedence as the Bluetooth stack. */
    private fun preferredNonSpeakerRoute(): CallAudioRoute {
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return CallAudioRoute.Earpiece
        val outputs = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
            .getOrDefault(emptyArray())
        return when {
            outputs.any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            } -> CallAudioRoute.Bluetooth
            outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            } -> CallAudioRoute.WiredHeadset
            else -> CallAudioRoute.Earpiece
        }
    }

    private fun applyAudioRouting() {
        // These calls register as SELF-MANAGED Telecom calls, and Telecom owns the route for those —
        // so tell Telecom first, or our AudioManager request is just a suggestion it can override.
        // Nothing here is conditional on Telecom working: requestAudioRoute returns immediately when
        // the call isn't managed (unsupported OS, addCall failure), and the AudioManager pass below
        // still runs. A dead speaker button in a disaster app is worse than a mis-routed one.
        callId?.let { id ->
            runCatching {
                RfcommTelecomCoordinator.requestAudioRoute(
                    id,
                    if (speakerOn) CallAudioRoute.Speaker else preferredNonSpeakerRoute()
                )
            }
        }
        val manager = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val devices = manager.availableCommunicationDevices
                val target = if (speakerOn) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                    } ?: devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                            it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                // The earpiece is SELECTED now, never merely left to a default. The old code fell
                // through to clearCommunicationDevice() with the comment "framework default:
                // earpiece" — but clearing only WITHDRAWS our own request and hands the route back to
                // whoever owns it, which for a self-managed call is Telecom. On One UI that came up on
                // the LOUDSPEAKER, so calls opened on speaker and the button read as inverted: the
                // first press was really the first time anything asserted a route at all.
                if (target != null) manager.setCommunicationDevice(target)
            }
            return
        }
        val hasBluetoothHeadset = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        }.getOrDefault(false)
        if (!speakerOn && hasBluetoothHeadset) {
            if (!bluetoothScoStarted) {
                bluetoothScoStarted = true
                runCatching { manager.startBluetoothSco() }
                runCatching { manager.isBluetoothScoOn = true }
            }
            runCatching { manager.isSpeakerphoneOn = false }
        } else {
            if (bluetoothScoStarted) {
                bluetoothScoStarted = false
                runCatching { manager.stopBluetoothSco() }
                runCatching { manager.isBluetoothScoOn = false }
            }
            // Wired headsets need no explicit routing: in-communication policy prefers them over the
            // earpiece automatically once the speakerphone is off.
            runCatching { manager.isSpeakerphoneOn = speakerOn }
        }
    }

    /** Re-route live when a headset (dis)connects mid-call — plugging one in also cancels speaker. */
    private fun registerAudioDeviceWatcher(manager: AudioManager) {
        if (audioDeviceCallback != null) return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                if (addedDevices.none { it.isCallHeadset() }) return
                synchronized(lock) {
                    if (!audioModeConfigured) return
                    speakerOn = false
                    applyAudioRouting()
                    _call.value = _call.value?.copy(speakerOn = false)
                }
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (removedDevices.none { it.isCallHeadset() }) return
                synchronized(lock) {
                    if (audioModeConfigured) applyAudioRouting()
                }
            }
        }
        runCatching { manager.registerAudioDeviceCallback(callback, null) }
            .onSuccess { audioDeviceCallback = callback }
    }

    private fun AudioDeviceInfo.isCallHeadset(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    /** Video just started (local camera or remote feed) → default to the loudspeaker, like a video call. */
    private fun autoSpeakerForVideo() {
        scope.launch {
            synchronized(lock) {
                if (peerConnection == null || speakerOn) return@launch
            }
            setSpeaker(true)
        }
    }

    // ---- inbound signaling (called by the receive path) ----

    /**
     * Handles an inbound signal that arrived over an authority channel's Firestore callSignals
     * (web-compatible). Adopts [sender] as this call's transport so our answer/ICE return over the same
     * channel, then routes it through the normal state machine. Won't hijack an active relay call.
     */
    fun onChannelSignal(from: Contact, senderUid: String, signal: JSONObject, sender: SignalSender) {
        synchronized(lock) {
            if (peerConnection == null || callSender != null) {
                callSender = sender
            }
        }
        onSignal(from, senderUid, signal)
    }

    /** Routes a decrypted call signal from [from] to the state machine. */
    fun onSignal(from: Contact, senderUid: String, signal: JSONObject) {
        // Accept the web's "type" field first (its canonical CallSignal), falling back to the legacy
        // Android "kind" — so signals interoperate in both directions.
        val kind = signal.optString("type").takeIf { it.isNotBlank() }
            ?: signal.optString("kind").takeIf { it.isNotBlank() } ?: return
        val id = signal.optString("callId").takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            when (kind) {
                "offer" -> onOffer(from, senderUid, id, signal)
                "answer" -> onAnswer(id, signal)
                "ice" -> onRemoteIce(id, signal)
                "ice-batch" -> onRemoteIceBatch(id, signal)
                // ICE-restart renegotiation mid-call (network handover recovery). Distinct kinds so
                // older peers / the web simply ignore them instead of misreading a fresh call.
                "restart-offer" -> onRestartOffer(id, signal)
                "restart-answer" -> onAnswer(id, signal)
                "restart-request" -> if (id == callId && isCaller) attemptIceRestart()
                "reject", "busy", "end" -> if (id == callId) {
                    _call.value = _call.value?.copy(state = State.ENDED)
                    scheduleTeardown(id)
                }
            }
        }
    }

    private fun onOffer(from: Contact, senderUid: String, id: String, signal: JSONObject) {
        if (peerConnection != null) {
            // Already on a call → politely decline.
            sendSignalTo(from, jsonOf("busy", id))
            return
        }
        val sdp = signal.optString("sdp").takeIf { it.isNotBlank() } ?: return
        // Keep TURN creds fresh (see startCall) — the callee needs a relay just as much as the caller.
        scope.launch { runCatching { TurnCredentialsProvider.refresh() } }
        contact = from
        callId = id
        isCaller = false
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        _call.value = CallInfo(
            id, senderUid, from.name,
            incoming = true, state = State.INCOMING, sessionCode = from.sessionCode
        )
        runCatching {
            RfcommTelecomCoordinator.onIncomingCall(id, id, from.name, InternetCallForegroundService::class.java)
        }
        // Ring even when no activity is showing: the foreground service posts the incoming-call
        // notification (full-screen intent + ringtone) and keeps ringing in the background.
        appContext?.let { InternetCallForegroundService.start(it) }
        val pc = createPeerConnection() ?: run { cleanup(State.ENDED); return }
        peerConnection = pc
        addLocalAudio(pc)
        scheduleRingTimeout(id)
        registerNetworkWatcher()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                // The remote offer's video m-lines auto-created transceivers; adopt their senders so
                // we can send camera/screen too (null slots when the peer is older / audio-only).
                grabVideoSendersFromRemote()
                drainPendingCandidates()
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun onAnswer(id: String, signal: JSONObject) {
        if (id != callId) return
        val pc = peerConnection ?: return
        val sdp = signal.optString("sdp").takeIf { it.isNotBlank() } ?: return
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet = true
                drainPendingCandidates()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    private fun onRemoteIce(id: String, signal: JSONObject) {
        if (id != callId) return
        queueRemoteCandidate(
            IceCandidate(
                signal.optString("sdpMid"),
                signal.optInt("sdpMLineIndex"),
                signal.optString("candidate")
            )
        )
    }

    /** A batched trickle-ICE signal: {"candidates":[{candidate,sdpMid,sdpMLineIndex},...]}. */
    private fun onRemoteIceBatch(id: String, signal: JSONObject) {
        if (id != callId) return
        val candidates = signal.optJSONArray("candidates") ?: return
        for (i in 0 until candidates.length()) {
            val c = candidates.optJSONObject(i) ?: continue
            queueRemoteCandidate(
                IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.optString("candidate"))
            )
        }
    }

    private fun queueRemoteCandidate(candidate: IceCandidate) {
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingRemoteCandidates.add(candidate)
        }
    }

    /** Ships the collected local candidates: one legacy "ice" signal when there is a single one
     *  (wire-compatible with older peers), else a single "ice-batch". */
    private fun flushLocalIce() {
        val id = callId
        val batch: List<JSONObject>
        synchronized(pendingLocalIce) {
            batch = pendingLocalIce.toList()
            pendingLocalIce.clear()
        }
        if (id == null || batch.isEmpty()) return
        if (batch.size == 1) {
            val c = batch[0]
            sendSignal(
                jsonOf("ice", id)
                    .put("candidate", c.optString("candidate"))
                    .put("sdpMid", c.optString("sdpMid"))
                    .put("sdpMLineIndex", c.optInt("sdpMLineIndex"))
            )
        } else {
            sendSignal(jsonOf("ice-batch", id).put("candidates", JSONArray(batch)))
        }
    }

    private fun drainPendingCandidates() {
        val pc = peerConnection ?: return
        pendingRemoteCandidates.forEach { pc.addIceCandidate(it) }
        pendingRemoteCandidates.clear()
    }

    // ---- resilience: transient-drop grace, ICE restarts, network watcher, ring timeout, stats ----

    private fun onTransportDisconnected() {
        val id = callId ?: return
        _call.value = _call.value?.copy(reconnecting = true)
        disconnectGraceJob?.cancel()
        disconnectGraceJob = scope.launch {
            delay(DISCONNECT_GRACE_MS)
            if (callId != id) return@launch
            val state = runCatching { peerConnection?.connectionState() }.getOrNull() ?: return@launch
            if (state != PeerConnection.PeerConnectionState.CONNECTED) escalateRecovery()
        }
    }

    private fun onTransportFailed() {
        _call.value = _call.value?.copy(reconnecting = true)
        scope.launch { escalateRecovery() }
    }

    /** Try to save the call with a caller-driven ICE restart; give up (teardown) when out of tries. */
    private fun escalateRecovery() {
        val id = callId ?: return
        if (restartAttempts >= MAX_ICE_RESTARTS) {
            _call.value = _call.value?.copy(state = State.ENDED)
            scheduleTeardown(id)
            return
        }
        if (isCaller) {
            attemptIceRestart()
        } else {
            // Only the offerer can cleanly restart ICE — ask it to. Peers that predate the restart
            // protocol ignore this silently, and the next verdict window tears the call down.
            restartAttempts++
            sendSignal(jsonOf("restart-request", id))
            scheduleRestartVerdict(id)
        }
    }

    /** Caller side: ICE restart — a new-ufrag offer over the same signaling. Media keeps flowing on
     *  the old path (if any) until the new one connects, then shifts over. */
    private fun attemptIceRestart() {
        val id: String?
        val pc: PeerConnection?
        synchronized(lock) {
            id = callId
            pc = peerConnection
        }
        if (id == null || pc == null) return
        restartAttempts++
        _call.value = _call.value?.copy(reconnecting = true)
        runCatching { pc.restartIce() }
        val constraints = audioConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                sendSignal(jsonOf("restart-offer", id).put("sdp", sdp.description))
            }
        }, constraints)
        scheduleRestartVerdict(id)
    }

    /** After a restart attempt, verify it actually reconnected; if not, try again or give up. */
    private fun scheduleRestartVerdict(id: String) {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = scope.launch {
            delay(DISCONNECT_GRACE_MS)
            if (callId != id) return@launch
            val state = runCatching { peerConnection?.connectionState() }.getOrNull() ?: return@launch
            if (state != PeerConnection.PeerConnectionState.CONNECTED) escalateRecovery()
        }
    }

    /** Callee side of an ICE restart: apply the new offer, answer over the same signaling. */
    private fun onRestartOffer(id: String, signal: JSONObject) {
        if (id != callId) return
        val pc = peerConnection ?: return
        val sdp = signal.optString("sdp").takeIf { it.isNotBlank() } ?: return
        _call.value = _call.value?.copy(reconnecting = true)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(SimpleSdpObserver(), answer)
                        sendSignal(jsonOf("restart-answer", id).put("sdp", answer.description))
                    }
                }, audioConstraints())
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    /** Proactive ICE restart on default-network handover (Wi-Fi ↔ cellular) — the classic mid-call
     *  killer. Registered while a call exists, unregistered in cleanup. */
    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val manager =
            appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val handle = network.networkHandle
                val previous = currentNetworkHandle
                currentNetworkHandle = handle
                if (previous == null || previous == handle) return // first fire = baseline
                scope.launch {
                    val id = callId ?: return@launch
                    if (isCaller) attemptIceRestart() else sendSignal(jsonOf("restart-request", id))
                }
            }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkWatcher() {
        val manager = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        networkCallback?.let { cb -> runCatching { manager?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        currentNetworkHandle = null
    }

    /** Unanswered ring guard: the caller gives up (sends end), the callee just stops ringing. */
    private fun scheduleRingTimeout(id: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (callId != id) return@launch
            when (_call.value?.state) {
                State.DIALING -> end()
                State.INCOMING -> {
                    _call.value = _call.value?.copy(state = State.ENDED)
                    scheduleTeardown(id)
                }
                else -> Unit
            }
        }
    }

    /** Poll getStats during the call and fold RTT + interval loss into the coarse quality signal. */
    private fun startStatsMonitor() {
        statsJob?.cancel()
        loggedVideoDecode = false
        prevPacketsLost = 0L
        prevPacketsReceived = 0L
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val pc = peerConnection ?: break
                runCatching {
                    pc.getStats { report ->
                        var rttMs = 0.0
                        var lost = 0L
                        var received = 0L
                        report.statsMap.values.forEach { s ->
                            when (s.type) {
                                "remote-inbound-rtp" -> {
                                    val rtt = (s.members["roundTripTime"] as? Number)?.toDouble() ?: 0.0
                                    if (rtt * 1000 > rttMs) rttMs = rtt * 1000
                                }
                                "inbound-rtp" -> {
                                    lost += (s.members["packetsLost"] as? Number)?.toLong() ?: 0L
                                    received += (s.members["packetsReceived"] as? Number)?.toLong() ?: 0L
                                    // One-shot probe of what we actually RECEIVE. Pair it with the
                                    // peer's outbound log: the same WxH on both ends means the sender
                                    // encoded small (a capture/encoder fault); a drop between them
                                    // means the network or our decoder. decoderImplementation also
                                    // names the codec, which is how we confirm H264 was negotiated.
                                    if (!loggedVideoDecode && s.members["kind"] == "video") {
                                        val w = (s.members["frameWidth"] as? Number)?.toInt() ?: 0
                                        val h = (s.members["frameHeight"] as? Number)?.toInt() ?: 0
                                        if (w > 0) {
                                            loggedVideoDecode = true
                                            Log.i(
                                                TAG,
                                                "IN-VIDEO ${w}x$h fps=${s.members["framesPerSecond"]}" +
                                                    " decoded=${s.members["framesDecoded"]}" +
                                                    " dropped=${s.members["framesDropped"]}" +
                                                    " dec=${s.members["decoderImplementation"]}"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        val deltaLost = (lost - prevPacketsLost).coerceAtLeast(0L)
                        val deltaReceived = (received - prevPacketsReceived).coerceAtLeast(0L)
                        prevPacketsLost = lost
                        prevPacketsReceived = received
                        val total = deltaLost + deltaReceived
                        val lossFraction = if (total > 0L) deltaLost.toDouble() / total else 0.0
                        val quality = when {
                            lossFraction > 0.08 || rttMs > 500 -> CallQuality.POOR
                            lossFraction > 0.03 || rttMs > 250 -> CallQuality.FAIR
                            else -> CallQuality.GOOD
                        }
                        _call.value = _call.value?.let {
                            if (it.state == State.ACTIVE && it.quality != quality) {
                                it.copy(quality = quality)
                            } else it
                        }
                    }
                }
            }
        }
    }

    // ---- WebRTC plumbing ----

    private fun ensureFactory(): PeerConnectionFactory? {
        factory?.let { return it }
        val ctx = appContext ?: return null
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions()
        )
        val egl = EglBase.create()
        eglBase = egl
        val created = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun createPeerConnection(): PeerConnection? {
        val f = ensureFactory() ?: return null
        // Uses whatever creds are cached now (warmed at init); STUN-only if the warm-up hasn't landed.
        val config = PeerConnection.RTCConfiguration(TurnCredentialsProvider.current()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        return f.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                if (callId == null) return
                synchronized(pendingLocalIce) {
                    pendingLocalIce.add(
                        JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    )
                    if (iceFlushJob?.isActive != true) {
                        iceFlushJob = scope.launch {
                            delay(ICE_BATCH_WINDOW_MS)
                            flushLocalIce()
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                // NOTE: this runs on the WebRTC signaling thread. It must NOT block on `lock`:
                // PeerConnection.close() (inside cleanup) waits for this very thread, so a synchronous
                // synchronized() here deadlocks against a concurrent hangup — only ever *schedule*.
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        disconnectGraceJob?.cancel()
                        ringTimeoutJob?.cancel()
                        restartAttempts = 0
                        _call.value = _call.value?.let {
                            if (it.connectedAt == null) {
                                Analytics.callConnected(if (callSender != null) "authority_p2p" else "internet")
                            }
                            it.copy(
                                state = State.ACTIVE,
                                connectedAt = it.connectedAt ?: System.currentTimeMillis(),
                                reconnecting = false
                            )
                        }
                        startStatsMonitor()
                        callId?.let { id -> runCatching { RfcommTelecomCoordinator.onCallActive(id, id) } }
                    }
                    // Transient by design: NAT rebind / brief radio gap. Flag "reconnecting", give it
                    // a grace window, then escalate to an ICE restart instead of dropping the call.
                    PeerConnection.PeerConnectionState.DISCONNECTED -> onTransportDisconnected()
                    PeerConnection.PeerConnectionState.FAILED -> onTransportFailed()
                    PeerConnection.PeerConnectionState.CLOSED -> scheduleTeardown(callId)
                    else -> Unit
                }
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {
                when (val track = receiver.track()) {
                    // Remote audio plays automatically through the default AudioDeviceModule; enable it.
                    is AudioTrack -> track.setEnabled(true)
                    is VideoTrack -> attachRemoteVideo(track, screenSlot = isScreenSlotReceiver(receiver))
                }
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: org.webrtc.MediaStream) {}
            override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
            override fun onDataChannel(dc: org.webrtc.DataChannel) {}
            override fun onRenegotiationNeeded() {}
        })
    }

    private fun addLocalAudio(pc: PeerConnection) {
        val f = factory ?: return
        // Voice-processing chain: echo cancellation, noise suppression, gain control, highpass.
        // Most builds default these on, but ask explicitly so no vendor build ships without them.
        val constraints = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = f.createAudioSource(constraints)
        val track = f.createAudioTrack("audio0", source)
        track.setEnabled(true)
        val sender = pc.addTrack(track, listOf("stream0"))
        audioSender = sender
        applyAudioParams(sender)
        audioSource = source
        localAudioTrack = track
    }

    /** Lift the Opus target off libwebrtc's ~32 kbps default — a straight speech-quality win. */
    private fun applyAudioParams(sender: RtpSender?) {
        val s = sender ?: return
        runCatching {
            val params = s.parameters ?: return
            params.encodings.firstOrNull()?.maxBitrateBps = AUDIO_MAX_BITRATE
            s.parameters = params
        }
    }

    private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        // Video m-lines are pre-negotiated via transceivers, so we accept remote video too.
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
    }

    // ---- video: camera + screen share on TWO pre-negotiated m-lines (slot 0 = camera, slot 1 =
    // screen) so both can stream simultaneously. Track-swap only, never renegotiation — and when the
    // peer only offers one video m-line (older build / web), screen share falls back to swapping the
    // camera out on the shared sender exactly like before. ----

    /** Caller side: add the pre-negotiated SEND_RECV video m-lines to the offer. */
    private fun addVideoTransceivers(pc: PeerConnection) {
        val camera = runCatching {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
        }.getOrNull()
        val screen = runCatching {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
        }.getOrNull()
        videoSender = camera?.sender
        screenSender = screen?.sender
        screenReceiverId = runCatching { screen?.receiver?.id() }.getOrNull()
        emitMediaState()
    }

    /** Callee side: adopt the senders of the video transceivers the remote offer created. Slot order
     *  follows the m-line order (camera first, screen second). Either may be null for older peers —
     *  no screen slot just disables simultaneous share, no video slot disables video entirely. */
    private fun grabVideoSendersFromRemote() {
        val pc = peerConnection ?: return
        val videoTransceivers = pc.transceivers.filter {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
        }
        videoTransceivers.getOrNull(0)?.let {
            runCatching { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV }
            videoSender = it.sender
        }
        videoTransceivers.getOrNull(1)?.let {
            runCatching { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV }
            screenSender = it.sender
            screenReceiverId = runCatching { it.receiver?.id() }.getOrNull()
        }
        emitMediaState()
    }

    /** Toggle the front camera on/off. No-op when the call has no negotiated video m-line. */
    fun toggleCamera() {
        synchronized(lock) {
            if (peerConnection == null || videoSender == null) return
            if (cameraOn) disableCamera() else enableCamera()
        }
    }

    private fun enableCamera() {
        if (cameraOn) return
        val f = factory ?: return
        val egl = eglBase ?: return
        val ctx = appContext ?: return
        val capturer = createCameraCapturer(ctx) ?: return
        val helper = SurfaceTextureHelper.create("CameraCapture", egl.eglBaseContext)
        val source = f.createVideoSource(false)
        capturer.initialize(helper, ctx, source.capturerObserver)
        runCatching { capturer.startCapture(1280, 720, 30) }
        val track = f.createVideoTrack("camera0", source).apply { setEnabled(true) }
        cameraCapturer = capturer
        cameraHelper = helper
        cameraSource = source
        cameraTrack = track
        cameraOn = true
        usingFrontCamera = true // createCameraCapturer prefers the front lens
        if (screenSender != null || !sharingScreen) {
            // Dual m-line (or no active share): the camera streams on its own slot, alongside any
            // ongoing screen share. setTrack throws if the sender wrapper was disposed (call torn
            // down while the camera permission dialog was up) — revert instead of crashing.
            val attached = runCatching {
                videoSender?.setTrack(track, false)
                applyVideoParams(videoSender, camera = true)
            }.isSuccess
            if (!attached) {
                Log.w(TAG, "enableCamera: video sender unusable, reverting")
                cameraOn = false
                stopCameraCapture()
                emitMediaState()
                return
            }
            publishLocalCamera(track)
        }
        // Legacy single-slot peer mid-share: keep sending the screen; camera resumes when it stops.
        emitMediaState()
        autoSpeakerForVideo()
    }

    private fun disableCamera() {
        if (!cameraOn) return
        cameraOn = false
        if (screenSender != null || !sharingScreen) {
            runCatching { videoSender?.setTrack(null, false) }
            publishLocalCamera(null)
        }
        stopCameraCapture()
        emitMediaState()
    }

    /** Begin screen sharing with the MediaProjection consent [projectionData] from the Activity result. */
    fun startScreenShare(projectionData: Intent) {
        synchronized(lock) {
            if (peerConnection == null || videoSender == null || sharingScreen) return
            val f = factory ?: return
            val egl = eglBase ?: return
            val ctx = appContext ?: return
            val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
                // The system "Stop sharing" affordance ends the projection — revert cleanly.
                override fun onStop() { scope.launch { stopScreenShare() } }
            })
            val helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext)
            val source = f.createVideoSource(true) // isScreencast → screen-optimized encoding
            capturer.initialize(helper, ctx, source.capturerObserver)
            val metrics = ctx.resources.displayMetrics
            runCatching { capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15) }
            val track = f.createVideoTrack("screen0", source).apply { setEnabled(true) }
            screenCapturer = capturer
            screenHelper = helper
            screenSource = source
            screenTrack = track
            sharingScreen = true
            val dedicated = screenSender
            val attached = runCatching {
                if (dedicated != null) {
                    // Dual m-line: screen streams on its own slot; the camera keeps going on slot 0.
                    dedicated.setTrack(track, false)
                    applyVideoParams(dedicated, camera = false)
                    if (cameraOn) applyVideoParams(videoSender, camera = true) // rebalance shared budget
                    publishLocalScreen(track)
                } else {
                    // Legacy single-slot peer: the screen takes over the shared sender (camera pauses).
                    videoSender?.setTrack(track, false)
                    applyVideoParams(videoSender, camera = false)
                    publishLocalCamera(null)
                    publishLocalScreen(track)
                }
            }.isSuccess
            if (!attached) {
                Log.w(TAG, "startScreenShare: video sender unusable, reverting")
                sharingScreen = false
                stopScreenCapture()
                publishLocalScreen(null)
            }
            emitMediaState()
        }
    }

    /** Flip between the front and back camera while the camera is on. */
    fun switchCamera() {
        synchronized(lock) {
            val cap = cameraCapturer as? CameraVideoCapturer ?: return
            cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    usingFrontCamera = isFrontCamera
                    emitMediaState()
                }
                override fun onCameraSwitchError(error: String?) {}
            })
        }
    }

    fun stopScreenShare() {
        synchronized(lock) {
            if (!sharingScreen) return
            sharingScreen = false
            val dedicated = screenSender
            if (dedicated != null) {
                // Dual m-line: just stop the screen slot; the camera never stopped.
                runCatching { dedicated.setTrack(null, false) }
                publishLocalScreen(null)
                if (cameraOn) applyVideoParams(videoSender, camera = true) // camera gets budget back
            } else {
                // Legacy single-slot peer: fall back to the camera if it's still enabled.
                val revert = if (cameraOn) cameraTrack else null
                runCatching { videoSender?.setTrack(revert, false) }
                if (revert != null) applyVideoParams(videoSender, camera = true)
                publishLocalScreen(null)
                publishLocalCamera(revert)
            }
            stopScreenCapture()
            emitMediaState()
        }
    }

    private fun createCameraCapturer(context: Context): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let { return enumerator.createCapturer(it, null) }
        return names.firstOrNull()?.let { enumerator.createCapturer(it, null) }
    }

    // Mirror the web's contentHint/degradationPreference: camera favours a smooth frame rate (drop
    // resolution first), screen favours crisp resolution/text (drop frame rate first). The bitrate
    // ceiling stops runaway oversending on a constrained / TURN-relayed path; when camera + screen
    // stream at the same time, the camera drops to its shared budget.
    private fun applyVideoParams(sender: RtpSender?, camera: Boolean) {
        val s = sender ?: return
        runCatching {
            val params = s.parameters ?: return
            params.degradationPreference = if (camera) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
            val bothStreaming = cameraOn && sharingScreen && screenSender != null
            params.encodings.firstOrNull()?.let { enc ->
                enc.maxBitrateBps = when {
                    !camera -> SCREEN_MAX_BITRATE
                    bothStreaming -> CAMERA_SHARED_BITRATE
                    else -> CAMERA_MAX_BITRATE
                }
                enc.maxFramerate = 30
            }
            s.parameters = params
        }
    }

    /** True when [receiver] belongs to the second (screen) video m-line. Compares against the id
     *  cached at negotiation time — re-querying pc.transceivers here would DISPOSE the cached
     *  videoSender/screenSender wrappers (see the note on [screenReceiverId]). */
    private fun isScreenSlotReceiver(receiver: RtpReceiver): Boolean {
        val screenId = screenReceiverId ?: return false
        return runCatching { receiver.id() == screenId }.getOrDefault(false)
    }

    private fun attachRemoteVideo(track: VideoTrack, screenSlot: Boolean) {
        track.setEnabled(true)
        // Light the remote surface up only while real frames are arriving: the first frame flips it on,
        // and a watchdog flips it back once frames stop (peer turned that stream off).
        val sink = VideoSink {
            if (screenSlot) {
                lastRemoteScreenFrameAt = System.currentTimeMillis()
                if (!remoteScreenOn) {
                    remoteScreenOn = true
                    emitMediaState()
                    autoSpeakerForVideo()
                }
            } else {
                lastRemoteCameraFrameAt = System.currentTimeMillis()
                if (!remoteCameraOn) {
                    remoteCameraOn = true
                    emitMediaState()
                    autoSpeakerForVideo()
                }
            }
        }
        if (screenSlot) {
            remoteScreenTrack = track
            remoteScreenSink = sink
            track.addSink(sink)
            _videoStreams.value = _videoStreams.value.copy(remoteScreen = track)
        } else {
            remoteCameraTrack = track
            remoteCameraSink = sink
            track.addSink(sink)
            _videoStreams.value = _videoStreams.value.copy(remote = track)
        }
        startRemoteVideoWatchdog()
    }

    // Poll for frame starvation: the far side turning a stream off shows up as frames simply stopping,
    // so if a slot has been "on" but frameless for REMOTE_VIDEO_OFF_DELAY_MS, drop it back off.
    private fun startRemoteVideoWatchdog() {
        remoteVideoWatchdog?.cancel()
        remoteVideoWatchdog = scope.launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                var changed = false
                if (remoteCameraOn && now - lastRemoteCameraFrameAt > REMOTE_VIDEO_OFF_DELAY_MS) {
                    remoteCameraOn = false
                    changed = true
                }
                if (remoteScreenOn && now - lastRemoteScreenFrameAt > REMOTE_VIDEO_OFF_DELAY_MS) {
                    remoteScreenOn = false
                    changed = true
                }
                if (changed) emitMediaState()
            }
        }
    }

    private fun publishLocalCamera(track: VideoTrack?) {
        _videoStreams.value = _videoStreams.value.copy(local = track)
    }

    private fun publishLocalScreen(track: VideoTrack?) {
        _videoStreams.value = _videoStreams.value.copy(localScreen = track)
    }

    private fun emitMediaState() {
        _call.value = _call.value?.copy(
            canUseVideo = videoSender != null,
            cameraOn = cameraOn,
            sharingScreen = sharingScreen,
            remoteVideo = remoteCameraOn,
            remoteScreen = remoteScreenOn,
            usingFrontCamera = usingFrontCamera
        )
    }

    /** Shared EGL context for the renderer surfaces. Null before the first call sets up the factory. */
    fun eglBaseContext(): EglBase.Context? = eglBase?.eglBaseContext

    private fun stopCameraCapture() {
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { cameraTrack?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraHelper?.dispose() }
        cameraCapturer = null
        cameraTrack = null
        cameraSource = null
        cameraHelper = null
    }

    private fun stopScreenCapture() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenTrack?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenHelper?.dispose() }
        screenCapturer = null
        screenTrack = null
        screenSource = null
        screenHelper = null
    }

    private fun stopVideo() {
        remoteVideoWatchdog?.cancel()
        remoteVideoWatchdog = null
        runCatching { remoteCameraSink?.let { remoteCameraTrack?.removeSink(it) } }
        runCatching { remoteScreenSink?.let { remoteScreenTrack?.removeSink(it) } }
        remoteCameraSink = null
        remoteScreenSink = null
        remoteCameraTrack = null
        remoteScreenTrack = null
        lastRemoteCameraFrameAt = 0L
        lastRemoteScreenFrameAt = 0L
        stopScreenCapture()
        stopCameraCapture()
        videoSender = null
        screenSender = null
        screenReceiverId = null
        audioSender = null
        cameraOn = false
        sharingScreen = false
        usingFrontCamera = true
        remoteCameraOn = false
        remoteScreenOn = false
        _videoStreams.value = VideoStreams()
    }

    private fun jsonOf(kind: String, id: String): JSONObject =
        // "type" is the web CallSignal field name (lib/messaging/webrtc-call.ts:
        // { type, callId, sdp?, candidate?, sdpMid?, sdpMLineIndex? }); "kind" is kept for
        // backward-compat with older Android builds. Both carry the same value, so a web peer sees a
        // valid signal and a legacy Android peer still finds "kind".
        JSONObject().put("type", kind).put("kind", kind).put("callId", id)

    private fun sendSignal(signal: JSONObject) {
        val target = contact ?: return
        sendSignalTo(target, signal)
    }

    private fun sendSignalTo(target: Contact, signal: JSONObject) {
        val sender = callSender ?: signalSender ?: return
        scope.launch { runCatching { sender.send(target, signal.toString()) } }
    }

    private fun cleanup(finalState: State) {
        // Duration metric must read connectedAt/callSender before this teardown clears them; a
        // second cleanup sees _call.value == null and logs nothing.
        _call.value?.connectedAt?.let { connectedAt ->
            Analytics.callEnded(
                transport = if (callSender != null) "authority_p2p" else "internet",
                durationSeconds = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0L),
                result = "answered"
            )
        }
        disconnectGraceJob?.cancel()
        disconnectGraceJob = null
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        iceFlushJob?.cancel()
        iceFlushJob = null
        synchronized(pendingLocalIce) { pendingLocalIce.clear() }
        statsJob?.cancel()
        statsJob = null
        restartAttempts = 0
        unregisterNetworkWatcher()
        appContext?.let { InternetCallForegroundService.stop(it) }
        callId?.let { id ->
            runCatching { RfcommTelecomCoordinator.onCallEnded(id, id, DisconnectCause(DisconnectCause.LOCAL)) }
        }
        callSender = null
        _call.value = _call.value?.copy(state = finalState)
        runCatching { localAudioTrack?.setEnabled(false) }
        stopVideo()
        runCatching { peerConnection?.close() }
        runCatching { audioSource?.dispose() }
        restoreCallAudio()
        speakerOn = false
        peerConnection = null
        localAudioTrack = null
        audioSource = null
        contact = null
        callId = null
        isCaller = false
        remoteDescriptionSet = false
        pendingRemoteCandidates.clear()
        _call.value = null
    }
}

/** No-op [SdpObserver] with overridable success hooks — keeps the call sites terse. */
private open class SimpleSdpObserver : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {
        Log.w("InternetCall", "SDP create failed: $error")
    }
    override fun onSetFailure(error: String?) {
        Log.w("InternetCall", "SDP set failed: $error")
    }
}
