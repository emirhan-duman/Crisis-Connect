package com.auralis.crisisconnect.messaging.call.sfu

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Intent
import android.media.projection.MediaProjection
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
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.RtpTransceiver.RtpTransceiverDirection
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class SfuMediaState { IDLE, JOINING, LIVE, FAILED, LEFT }

/** One of a participant's published tracks (name + kind + logical source). */
data class SfuPublishedTrack(val trackName: String, val kind: String, val source: String)

/** Live video tracks of a 1:1 SFU call, for the call overlay (same shape the P2P overlay renders). */
data class SfuVideoStreams(
    val local: VideoTrack? = null,
    val localScreen: VideoTrack? = null,
    val remote: VideoTrack? = null,
    val remoteScreen: VideoTrack? = null,
)

/** A remote room member whose tracks we should pull, with their SFU session id. */
data class SfuRemoteParticipant(
    val uid: String,
    val sessionId: String,
    val tracks: List<SfuPublishedTrack>,
    /** Server time of their last roster write (heartbeat) — null while the write is still pending. */
    val updatedAtMillis: Long? = null,
)

/**
 * Android mirror of the web [SfuCallManager] (lib/messaging/sfu-call.ts): ONE [PeerConnection] to the
 * nearest Cloudflare Realtime edge that PUSHES our mic and PULLS every other room participant's audio.
 * Media never flows peer-to-peer — each client talks only to the SFU. Room coordination (who is in the
 * room + their SFU session id + track names) is injected via [setRoster] (the controller relays it over
 * Firestore), so this class stays transport-agnostic like the web.
 *
 * Faz B scope: **audio-only** (mic push + pull peers' audio). Video/screen layer on later, MLS-E2EE in
 * Faz C. E2EE-free first so the media path can be brought up and quality-checked before crypto.
 */
class SfuCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: SfuApiClient = SfuApiClient(),
    // When true, MLS-E2EE each frame: the local sender gets a native FrameEncryptor and every inbound
    // receiver a FrameDecryptor ([MlsFrameCrypto]). Required for web interop (the dashboard enforces
    // MLS). Safe to leave on before the handshake finishes — the native path emits silence until ready.
    private val e2ee: Boolean = false,
    private val onState: (SfuMediaState) -> Unit = {},
    private val onPublished: (sessionId: String, tracks: List<SfuPublishedTrack>) -> Unit = { _, _ -> },
) {
    var state: SfuMediaState = SfuMediaState.IDLE
        private set

    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var sessionId: String? = null
    private var published: List<SfuPublishedTrack> = emptyList()

    // Local camera (video call / voice→video upgrade) + screen share, each on its own send transceiver.
    private var cameraCapturer: VideoCapturer? = null
    private var cameraHelper: SurfaceTextureHelper? = null
    private var cameraSource: VideoSource? = null
    private var cameraTrack: VideoTrack? = null
    private var cameraTransceiver: RtpTransceiver? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenTransceiver: RtpTransceiver? = null
    var cameraOn = false
        private set
    var sharingScreen = false
        private set

    /** Live local/remote video tracks for the overlay. */
    val videoStreams = MutableStateFlow(SfuVideoStreams())

    private val midToUid = HashMap<String, String>() // inbound transceiver mid -> uid
    private val midSource = HashMap<String, String>() // inbound transceiver mid -> "mic"/"camera"/"screen"
    private val remoteSession = HashMap<String, String>() // uid -> their SFU sessionId
    private val pulled = HashSet<String>() // "sessionId/trackName" already pulled
    private val pullRetries = HashMap<String, Int>() // "sessionId/trackName" -> failed pull attempts
    private var pullingUid: String? = null // uid whose tracks are mid-renegotiation
    private var pullingTracks: List<SfuPublishedTrack> = emptyList() // tracks mid-pull (mid→source map)
    private val negotiation = Mutex() // serialize offer/answer cycles on the single PC
    private var iceGatheringDone: CompletableDeferred<Unit>? = null
    private var muted = false
    private var micHold = false

    private fun set(next: SfuMediaState) {
        state = next
        onState(next)
    }

    /**
     * Join the SFU: capture the mic (+ camera on a VIDEO call), create the session, and publish our
     * tracks. On a voice call the camera hardware never activates; [toggleCamera] upgrades on demand.
     */
    suspend fun join(video: Boolean = false) {
        if (state != SfuMediaState.IDLE) return
        Log.i(TAG, "join() start video=$video")
        set(SfuMediaState.JOINING)
        try {
            val factory = ensureFactory()
            val connection = factory.createPeerConnection(rtcConfig(), pcObserver())
                ?: throw IOException("PeerConnection not created")
            pc = connection

            // Capture the mic and add it sendonly — this client only PUSHES its audio; it PULLS others'.
            val source = factory.createAudioSource(MediaConstraints())
            audioSource = source
            val track = factory.createAudioTrack("sfu_mic", source)
            track.setEnabled(!muted && !micHold)
            localAudioTrack = track
            val sendTransceiver = connection.addTransceiver(
                track,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY),
            )
            // Attach the MLS FrameEncryptor to every sender before publishing, so outgoing frames are
            // E2E-encrypted (empty/silence until the group handshake lands — see [MlsFrameCrypto]).
            attachEncryptor(sendTransceiver, "mic")

            if (video) {
                captureCamera()?.let { camTrack ->
                    val tr = connection.addTransceiver(
                        camTrack,
                        RtpTransceiver.RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY),
                    )
                    cameraTransceiver = tr
                    pinVp9(tr)
                    attachEncryptor(tr, "camera")
                    applyVideoParams(tr, camera = true)
                    cameraOn = true
                    videoStreams.value = videoStreams.value.copy(local = camTrack)
                }
            }

            // 1) Create the SFU session with our first (gathered) offer; Cloudflare answers with a sessionId.
            val offer1 = connection.createOfferAwait()
            connection.setLocalAwait(offer1)
            val session = api.createSession(connection.gatheredLocalSdp())
            val sid = session.optString("sessionId").takeIf { it.isNotBlank() }
                ?: throw IOException("session not created")
            sessionId = sid
            Log.i(TAG, "session created id=$sid")
            connection.setRemoteAwait(session.answerSdp())

            // 2) Publish: name each local track by its id, tied to its transceiver mid, and renegotiate.
            val localTracks = connection.transceivers.mapNotNull { tr ->
                val sender = tr.sender.track() ?: return@mapNotNull null
                val mid = tr.mid ?: return@mapNotNull null
                JSONObject().put("location", "local").put("mid", mid).put("trackName", sender.id())
            }
            val offer2 = connection.createOfferAwait()
            connection.setLocalAwait(offer2)
            val pub = api.publishTracks(sid, connection.gatheredLocalSdp(), localTracks)
            pub.optJSONObject("sessionDescription")?.let { connection.setRemoteAwait(it.getString("sdp"), it.getString("type")) }

            published = connection.transceivers.mapNotNull { tr ->
                val sender = tr.sender.track() ?: return@mapNotNull null
                val kind = sender.kind()
                SfuPublishedTrack(sender.id(), kind, if (kind == "audio") "mic" else "camera")
            }
            set(SfuMediaState.LIVE)
            Log.i(TAG, "LIVE — published ${published.size} track(s)")
            onPublished(sid, published)
            startStatsProbe()
        } catch (e: Exception) {
            Log.w(TAG, "SFU join failed", e)
            set(SfuMediaState.FAILED)
            leave()
        }
    }

    /** Reconcile the room roster: pull each newly-published remote track; drop members who left. */
    suspend fun setRoster(remotes: List<SfuRemoteParticipant>) {
        Log.i(TAG, "setRoster remotes=${remotes.size} state=$state")
        if (state != SfuMediaState.LIVE) return
        val present = remotes.map { it.uid }.toHashSet()
        for (uid in remoteSession.keys.toList()) if (uid !in present) dropRemote(uid)
        for (r in remotes) {
            val fresh = r.tracks.filter { "${r.sessionId}/${it.trackName}" !in pulled }
            if (fresh.isEmpty()) continue
            remoteSession[r.uid] = r.sessionId
            fresh.forEach { pulled.add("${r.sessionId}/${it.trackName}") }
            val ok = runCatching { negotiation.withLock { pullGroup(r.uid, r.sessionId, fresh) } }
                .onFailure { Log.w(TAG, "pull failed for ${r.uid}", it) }
                .getOrDefault(false)
            if (!ok) {
                // The publisher may not have finished publishing yet (Cloudflare answers "track not
                // found"-style per-track errors). Un-mark and retry shortly, bounded per track.
                fresh.forEach { pulled.remove("${r.sessionId}/${it.trackName}") }
                val retriable = fresh.filter { t ->
                    val key = "${r.sessionId}/${t.trackName}"
                    val n = pullRetries.getOrDefault(key, 0)
                    pullRetries[key] = n + 1
                    n + 1 < MAX_PULL_RETRIES
                }
                if (retriable.isNotEmpty()) {
                    Log.i(TAG, "scheduling pull retry for ${r.uid} in ${PULL_RETRY_DELAY_MS}ms")
                    scope.launch {
                        kotlinx.coroutines.delay(PULL_RETRY_DELAY_MS)
                        setRoster(remotes)
                    }
                } else {
                    Log.w(TAG, "giving up pulling ${r.uid} after $MAX_PULL_RETRIES attempts")
                }
            }
        }
    }

    /** Pull [tracks] from a remote session. Returns true only when every track subscribed cleanly. */
    private suspend fun pullGroup(uid: String, remoteSessionId: String, tracks: List<SfuPublishedTrack>): Boolean {
        val connection = pc ?: return false
        val sid = sessionId ?: return false
        val trackObjs = tracks.map {
            JSONObject().put("location", "remote").put("sessionId", remoteSessionId).put("trackName", it.trackName)
        }
        val res = api.pullTracks(sid, trackObjs)
        var allOk = true
        res.optJSONArray("tracks")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val mid = t.optString("mid")
                val err = t.optString("error").ifBlank { t.optString("errorDescription") }
                if (mid.isBlank() || err.isNotBlank()) {
                    allOk = false
                    Log.w(TAG, "pull track NOT subscribed name=${t.optString("trackName")} mid='$mid' error='$err'")
                } else {
                    midToUid[mid] = uid
                    // Which logical source this mid carries (mic/camera/screen), from the roster entry
                    // we asked for — onTrack uses it to route the remote video to the right tile.
                    tracks.firstOrNull { it.trackName == t.optString("trackName") }
                        ?.let { midSource[mid] = it.source }
                }
            }
        }
        val sdp = res.optJSONObject("sessionDescription")
        if (res.optBoolean("requiresImmediateRenegotiation") && sdp?.optString("type") == "offer") {
            pullingUid = uid
            pullingTracks = tracks
            try {
                connection.setRemoteAwait(sdp.getString("sdp"), "offer")
                val answer = connection.createAnswerAwait()
                connection.setLocalAwait(answer)
                api.renegotiate(sid, connection.gatheredLocalSdp())
            } finally {
                pullingUid = null
                pullingTracks = emptyList()
            }
        }
        return allOk
    }

    private fun dropRemote(uid: String) {
        val theirMids = midToUid.filterValues { it == uid }.keys
        theirMids.forEach { midSource.remove(it) }
        midToUid.entries.removeAll { it.value == uid }
        remoteSession.remove(uid)?.let { sid -> pulled.removeAll { it.startsWith("$sid/") } }
        videoStreams.value = videoStreams.value.copy(remote = null, remoteScreen = null)
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        localAudioTrack?.setEnabled(!muted && !micHold)
    }

    /**
     * Pre-warm privacy gate: while the caller joins the SFU DURING the ring (to shave the answer→audio
     * latency), the mic track stays disabled — nothing the callee could pull carries voice until the
     * call is actually answered.
     */
    fun setMicHold(hold: Boolean) {
        micHold = hold
        localAudioTrack?.setEnabled(!muted && !hold)
    }

    /**
     * Camera on/off. Off→on mid-call publishes the camera as a NEW track (voice→video upgrade, one
     * renegotiation, VP9-pinned + MLS transform — same as the web); on→off keeps the transceiver and
     * just stops sending (the roster's cameraOn flag drives the peer's tile back to the avatar).
     */
    suspend fun toggleCamera() {
        if (state != SfuMediaState.LIVE) return
        if (cameraOn) {
            cameraOn = false
            runCatching { cameraTransceiver?.sender?.setTrack(null, false) }
            stopCameraCapture()
            videoStreams.value = videoStreams.value.copy(local = null)
            published = published.filterNot { it.source == "camera" }
            sessionId?.let { onPublished(it, published) }
            return
        }
        val track = captureCamera() ?: return
        cameraOn = true
        val existing = cameraTransceiver
        if (existing != null) {
            // The m-line already exists from an earlier camera session — just resume on it.
            runCatching { existing.sender.setTrack(track, false) }
            applyVideoParams(existing, camera = true)
        } else {
            negotiation.withLock { publishLocalTrack(track, source = "camera") }
        }
        videoStreams.value = videoStreams.value.copy(local = track)
        published = published.filterNot { it.source == "camera" } +
            SfuPublishedTrack(track.id(), "video", "camera")
        sessionId?.let { onPublished(it, published) }
    }

    /** Flip between the front and back lens (no renegotiation — capturer-level switch). */
    fun switchCamera() {
        (cameraCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    /** Begin screen sharing with the MediaProjection consent [projectionData]. Its own track/m-line. */
    suspend fun startScreenShare(projectionData: Intent) {
        if (state != SfuMediaState.LIVE || sharingScreen) return
        val factory = ensureFactory()
        val egl = sharedEgl ?: return
        val capturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() { scope.launch { runCatching { stopScreenShare() } } }
        })
        val helper = SurfaceTextureHelper.create("SfuScreenCapture", egl.eglBaseContext)
        val source = factory.createVideoSource(true) // isScreencast → text-friendly encoding
        capturer.initialize(helper, context, source.capturerObserver)
        val metrics = context.resources.displayMetrics
        runCatching { capturer.startCapture(metrics.widthPixels, metrics.heightPixels, 15) }
        val track = factory.createVideoTrack("sfu_screen", source).apply { setEnabled(true) }
        screenCapturer = capturer
        screenHelper = helper
        screenSource = source
        screenTrack = track
        sharingScreen = true
        val existing = screenTransceiver
        if (existing != null) {
            runCatching { existing.sender.setTrack(track, false) }
            applyVideoParams(existing, camera = false)
        } else {
            negotiation.withLock { publishLocalTrack(track, source = "screen") }
        }
        videoStreams.value = videoStreams.value.copy(localScreen = track)
        published = published.filterNot { it.source == "screen" } +
            SfuPublishedTrack(track.id(), "video", "screen")
        sessionId?.let { onPublished(it, published) }
    }

    suspend fun stopScreenShare() {
        if (!sharingScreen) return
        sharingScreen = false
        runCatching { screenTransceiver?.sender?.setTrack(null, false) }
        stopScreenCapture()
        videoStreams.value = videoStreams.value.copy(localScreen = null)
        published = published.filterNot { it.source == "screen" }
        sessionId?.let { onPublished(it, published) }
    }

    /** Publish a NEW local video track mid-call: new sendonly m-line + one publish renegotiation. */
    private suspend fun publishLocalTrack(track: VideoTrack, source: String) {
        val connection = pc ?: return
        val sid = sessionId ?: return
        val tr = connection.addTransceiver(
            track,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiverDirection.SEND_ONLY),
        )
        if (source == "camera") cameraTransceiver = tr else screenTransceiver = tr
        pinVp9(tr)
        attachEncryptor(tr, source)
        applyVideoParams(tr, camera = source == "camera")
        val offer = connection.createOfferAwait()
        connection.setLocalAwait(offer)
        val body = listOf(
            JSONObject().put("location", "local").put("mid", tr.mid).put("trackName", track.id()),
        )
        val res = api.publishTracks(sid, connection.gatheredLocalSdp(), body)
        res.optJSONObject("sessionDescription")
            ?.let { connection.setRemoteAwait(it.getString("sdp"), it.getString("type")) }
        Log.i(TAG, "published local $source track (mid=${tr.mid})")
    }

    private var statsJob: kotlinx.coroutines.Job? = null

    /** Per-3s inbound-video probe: pinpoints whether remote video is not received / not decoded / fine. */
    private fun startStatsProbe() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (state == SfuMediaState.LIVE) {
                kotlinx.coroutines.delay(3000)
                val connection = pc ?: break
                connection.getStats { report ->
                    report.statsMap.values.forEach { s ->
                        if (s.type == "inbound-rtp" && s.members["kind"] == "video") {
                            val recv = s.members["framesReceived"]
                            val dec = s.members["framesDecoded"]
                            val drop = s.members["framesDropped"]
                            val w = s.members["frameWidth"]
                            val h = s.members["frameHeight"]
                            Log.i(TAG, "IN-VIDEO recv=$recv decoded=$dec dropped=$drop ${w}x$h")
                        }
                        if (s.type == "outbound-rtp" && s.members["kind"] == "video") {
                            Log.i(TAG, "OUT-VIDEO sent=${s.members["framesSent"]} enc=${s.members["framesEncoded"]} ${s.members["frameWidth"]}x${s.members["frameHeight"]}")
                        }
                    }
                }
            }
        }
    }

    /** Front-preferring camera capture; sets up capturer/helper/source and returns the live track. */
    private fun captureCamera(): VideoTrack? {
        val factory = ensureFactory()
        val egl = sharedEgl ?: return null
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val device = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull() ?: return null
        val capturer = enumerator.createCapturer(device, null) ?: return null
        val helper = SurfaceTextureHelper.create("SfuCameraCapture", egl.eglBaseContext)
        val source = factory.createVideoSource(false)
        capturer.initialize(helper, context, source.capturerObserver)
        runCatching { capturer.startCapture(960, 540, 30) }
        val track = factory.createVideoTrack("sfu_camera", source).apply { setEnabled(true) }
        cameraCapturer = capturer
        cameraHelper = helper
        cameraSource = source
        cameraTrack = track
        return track
    }

    private fun stopCameraCapture() {
        runCatching { cameraCapturer?.stopCapture() }
        runCatching { cameraCapturer?.dispose() }
        runCatching { cameraHelper?.dispose() }
        runCatching { cameraSource?.dispose() }
        runCatching { cameraTrack?.dispose() }
        cameraCapturer = null
        cameraHelper = null
        cameraSource = null
        cameraTrack = null
    }

    private fun stopScreenCapture() {
        runCatching { screenCapturer?.stopCapture() }
        runCatching { screenCapturer?.dispose() }
        runCatching { screenHelper?.dispose() }
        runCatching { screenSource?.dispose() }
        runCatching { screenTrack?.dispose() }
        screenCapturer = null
        screenHelper = null
        screenSource = null
        screenTrack = null
    }

    /** Attach the MLS FrameEncryptor to [tr]'s sender when E2EE is on. */
    private fun attachEncryptor(tr: RtpTransceiver, label: String) {
        if (!e2ee) return
        MlsFrameCrypto.newEncryptor()?.let {
            tr.sender.setFrameEncryptor(it)
            Log.i(TAG, "attached MLS FrameEncryptor to $label sender")
        }
    }

    /**
     * Pin VP9 (+rtx) on a video transceiver. The MLS per-frame crypto targets VP9 (whole-frame
     * encryption, no plaintext header — see split_vp9_header in the shared Rust) and the web pins the
     * same, so both sides negotiate identical codecs.
     */
    private fun pinVp9(tr: RtpTransceiver) {
        runCatching {
            val caps = sharedFactory
                ?.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                ?: return
            val vp9 = caps.codecs.filter {
                val name = it.name?.lowercase().orEmpty()
                name == "vp9" || name == "rtx"
            }
            if (vp9.isNotEmpty()) tr.setCodecPreferences(vp9)
        }.onFailure { Log.w(TAG, "VP9 pin failed (using negotiated default)", it) }
    }

    // Camera favours a smooth frame rate; screen favours crisp text. Bitrate caps mirror the web.
    private fun applyVideoParams(tr: RtpTransceiver, camera: Boolean) {
        runCatching {
            val sender = tr.sender
            val params = sender.parameters ?: return
            params.degradationPreference = if (camera) {
                RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            }
            params.encodings.firstOrNull()?.let { enc ->
                enc.maxBitrateBps = if (camera) CAMERA_MAX_BITRATE else SCREEN_MAX_BITRATE
                enc.maxFramerate = if (camera) 30 else 15
            }
            sender.parameters = params
        }
    }

    fun leave() {
        statsJob?.cancel()
        statsJob = null
        runCatching { pc?.close() }
        pc = null
        runCatching { localAudioTrack?.dispose() }
        runCatching { audioSource?.dispose() }
        localAudioTrack = null
        audioSource = null
        stopCameraCapture()
        stopScreenCapture()
        cameraTransceiver = null
        screenTransceiver = null
        cameraOn = false
        sharingScreen = false
        videoStreams.value = SfuVideoStreams()
        midToUid.clear()
        midSource.clear()
        remoteSession.clear()
        pulled.clear()
        pullRetries.clear()
        sessionId = null
        if (state != SfuMediaState.FAILED) set(SfuMediaState.LEFT)
    }

    // ---- WebRTC plumbing ----

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val ice = listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        return PeerConnection.RTCConfiguration(ice).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
    }

    private fun pcObserver() = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) {
                iceGatheringDone?.takeIf { !it.isCompleted }?.complete(Unit)
            }
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            if (newState == PeerConnection.PeerConnectionState.FAILED ||
                newState == PeerConnection.PeerConnectionState.CLOSED
            ) {
                if (state == SfuMediaState.LIVE || state == SfuMediaState.JOINING) {
                    scope.launch { set(SfuMediaState.FAILED) }
                }
            }
        }
        override fun onTrack(transceiver: RtpTransceiver) {
            // Remote audio plays automatically through the default AudioDeviceModule; remote VIDEO is
            // surfaced through [videoStreams] (camera → remote, screen share → remoteScreen).
            val mid = transceiver.mid
            Log.i(TAG, "onTrack mid=$mid kind=${transceiver.receiver.track()?.kind()} pullingUid=$pullingUid")
            mid?.let { m -> pullingUid?.let { uid -> midToUid.putIfAbsent(m, uid) } }
            // Attach the MLS FrameDecryptor to this inbound receiver so peers' E2E-encrypted frames
            // decrypt (silence until the shared group handshake completes — see [MlsFrameCrypto]).
            if (e2ee) MlsFrameCrypto.newDecryptor()?.let {
                transceiver.receiver.setFrameDecryptor(it)
                Log.i(TAG, "attached MLS FrameDecryptor to receiver mid=$mid")
            }
            val track = transceiver.receiver.track()
            if (track is VideoTrack && mid != null) {
                // Source comes from the pull's mid mapping; if the mapping isn't in yet (onTrack can
                // beat the response parse), fall back to the batch being pulled right now.
                val source = midSource[mid]
                    ?: pullingTracks.firstOrNull { it.kind == "video" }?.source
                    ?: "camera"
                track.setEnabled(true)
                videoStreams.value = if (source == "screen") {
                    videoStreams.value.copy(remoteScreen = track)
                } else {
                    videoStreams.value.copy(remote = track)
                }
                Log.i(TAG, "remote video attached source=$source")
            }
        }
        override fun onIceCandidate(candidate: IceCandidate?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
    }

    // Cloudflare Realtime is non-trickle: the offer we POST must already contain our ICE candidates, so
    // wait for gathering to finish (bounded) before reading localDescription.
    private suspend fun PeerConnection.gatheredLocalSdp(): String {
        if (iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
            val gate = CompletableDeferred<Unit>()
            iceGatheringDone = gate
            if (iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                withTimeoutOrNull(ICE_GATHER_TIMEOUT_MS) { gate.await() }
            }
        }
        return localDescription?.description ?: throw IOException("no local description")
    }

    private suspend fun PeerConnection.createOfferAwait(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createOffer(sdpCreateObserver(cont), MediaConstraints())
        }

    private suspend fun PeerConnection.createAnswerAwait(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            createAnswer(sdpCreateObserver(cont), MediaConstraints())
        }

    private suspend fun PeerConnection.setLocalAwait(sdp: SessionDescription) =
        suspendCancellableCoroutine { cont ->
            setLocalDescription(sdpSetObserver(cont), sdp)
        }

    private suspend fun PeerConnection.setRemoteAwait(sdp: String, type: String = "answer") =
        suspendCancellableCoroutine { cont ->
            val desc = SessionDescription(
                if (type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                sdp,
            )
            setRemoteDescription(sdpSetObserver(cont), desc)
        }

    private fun sdpCreateObserver(cont: kotlinx.coroutines.CancellableContinuation<SessionDescription>) =
        object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { if (cont.isActive) cont.resume(sdp) }
            override fun onCreateFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IOException("createSdp: $error"))
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }

    private fun sdpSetObserver(cont: kotlinx.coroutines.CancellableContinuation<Unit>) =
        object : SdpObserver {
            override fun onSetSuccess() { if (cont.isActive) cont.resume(Unit) }
            override fun onSetFailure(error: String?) {
                if (cont.isActive) cont.resumeWithException(IOException("setSdp: $error"))
            }
            override fun onCreateSuccess(sdp: SessionDescription) {}
            override fun onCreateFailure(error: String?) {}
        }

    private fun JSONObject.answerSdp(): String =
        optJSONObject("sessionDescription")?.getString("sdp") ?: throw IOException("no answer sdp")

    companion object {
        private const val TAG = "SfuCallManager"
        private const val ICE_GATHER_TIMEOUT_MS = 4_000L
        private const val MAX_PULL_RETRIES = 5
        private const val PULL_RETRY_DELAY_MS = 2_000L
        private const val CAMERA_MAX_BITRATE = 1_800_000
        private const val SCREEN_MAX_BITRATE = 2_000_000

        /** EGL context for the overlay's SurfaceViewRenderers (same one the capture pipeline uses). */
        fun sharedEglContext(): EglBase.Context? = sharedEgl?.eglBaseContext

        @Volatile
        private var sharedFactory: PeerConnectionFactory? = null
        private var sharedEgl: EglBase? = null

        @Synchronized
        private fun ensureFactoryFor(context: Context): PeerConnectionFactory {
            sharedFactory?.let { return it }
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions(),
            )
            val egl = EglBase.create()
            sharedEgl = egl
            val factory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
                .createPeerConnectionFactory()
            sharedFactory = factory
            return factory
        }
    }

    private fun ensureFactory(): PeerConnectionFactory = ensureFactoryFor(context)
}
