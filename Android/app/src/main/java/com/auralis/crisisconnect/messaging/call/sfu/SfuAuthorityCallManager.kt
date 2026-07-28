package com.auralis.crisisconnect.messaging.call.sfu

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling
import com.auralis.crisisconnect.messaging.call.InternetCallForegroundService
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/** Minimal UI snapshot of the current SFU authority call, for the overlay host + call notification. */
data class SfuUiCall(
    val peerName: String,
    val peerUid: String,
    val incoming: Boolean,
    val state: SfuCallState,
    val muted: Boolean,
    /** Wall-clock millis of the moment the call connected — drives the notification chronometer. */
    val connectedAtMillis: Long? = null,
    /** Whether this was placed/accepted as a VIDEO call (drives the overlay's video controls). */
    val video: Boolean = false,
    val cameraOn: Boolean = false,
    val sharingScreen: Boolean = false,
)

/**
 * Process-wide orchestration for an SFU authority call — the SFU counterpart of [InternetCallManager].
 * Ties the ring/signaling ([SfuRingManager]), the Cloudflare Realtime media engine ([SfuCallManager])
 * and the Firestore roster ([SfuRoomClient]): when the ring reaches a room (offer accepted both sides)
 * it joins the SFU, publishes itself, and pulls every other member.
 *
 * Receive is app-wide: [com.auralis.crisisconnect.messaging.call.AuthorityCallReceiver] routes every SFU
 * signal it sees into [onSfuSignal] (both the incoming offer AND the outgoing call's answer), so this
 * manager never needs its own per-call listener. Gated by [SfuCallConfig]; the citizen P2P path is
 * untouched. Faz B: audio only, E2EE off.
 */
object SfuAuthorityCallManager {
    private const val TAG = "SfuAuthorityCall"
    private const val HEARTBEAT_INTERVAL_MS = 10_000L
    private const val PEER_STALE_MS = 30_000L
    private const val PEER_LOST_END_MS = 30_000L

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var signaling: AuthorityCallSignaling? = null
    private var ring: SfuRingManager? = null
    private var media: SfuCallManager? = null
    private var room: SfuRoomClient? = null
    private var mlsSession: MlsSession? = null
    private var rosterReg: ListenerRegistration? = null

    private var boundChannelId: String = ""
    private var myUid: String = ""
    private var selfName: String = ""
    private var selfPhotoUrl: String? = null
    private var incoming = false
    private var muted = false
    private var connectedAtMillis: Long? = null
    private var published: Pair<String, List<SfuPublishedTrack>>? = null
    private var livenessJob: kotlinx.coroutines.Job? = null
    private var videoCollectJob: kotlinx.coroutines.Job? = null
    private var boundKind: AuthorityCallSignaling.ChannelKind = AuthorityCallSignaling.ChannelKind.HIERARCHY
    private var usedVideo = false
    private var callLogged = false
    @Volatile private var lastPeerAliveMillis = 0L
    @Volatile private var everSawPeer = false

    private val _uiCall = MutableStateFlow<SfuUiCall?>(null)
    val uiCall: StateFlow<SfuUiCall?> = _uiCall.asStateFlow()

    // Stable across the whole app lifetime (media engines come and go per call): the overlay can
    // subscribe unconditionally from its very first composition and never miss a remote track.
    private val _videoStreams = MutableStateFlow(SfuVideoStreams())
    val videoStreamsFlow: StateFlow<SfuVideoStreams> = _videoStreams.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        // Keep our display name fresh for the room roster (publishSelf).
        scope.launch { runCatching { getSavedUserName(appContext).collect { selfName = it } } }
        // Profile photo for the roster: without it the web call UI renders an empty black circle for us
        // (which reads as an open-but-dark camera). Same source the profile screen uses.
        scope.launch {
            runCatching {
                val auth = com.google.firebase.auth.FirebaseAuth.getInstance()
                val uid = auth.currentUser?.uid ?: return@launch
                val doc = com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .collection("users").document(uid).get().await()
                selfPhotoUrl = doc.getString("photoURL")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: auth.currentUser?.photoUrl?.toString()
            }
        }
    }

    /** Place an outgoing authority SFU call to [peerUid] over [channelId]. */
    fun startOutgoing(
        channelId: String,
        kind: AuthorityCallSignaling.ChannelKind,
        myUid: String,
        peerUid: String,
        peerName: String,
        video: Boolean = false,
    ) {
        if (ring != null && _uiCall.value != null) return // already on a call
        Log.i(TAG, "startOutgoing channel=$channelId peer=$peerUid kind=$kind video=$video")
        incoming = false
        bindSignaling(channelId, kind, myUid)
        usedVideo = video
        ring?.startCall(peerUid, peerName, video)
        // PRE-WARM: the caller knows the room id the moment it rings — join the SFU, publish, and run
        // the MLS claim NOW so the answer only has the pull left (~2s instead of ~7s to two-way audio).
        // The mic track stays DISABLED until the callee actually accepts (privacy), and an unanswered/
        // rejected ring tears the media down via onRingState like before.
        ring?.roomId?.let { joinMedia(it, micHold = true) }
    }

    /**
     * Feed an inbound SFU signal into the ring. Called app-wide by AuthorityCallReceiver for the channel
     * the signal arrived on. A fresh signal while idle (an incoming offer) binds the transport to that
     * channel so our answer goes back over the right callSignals collection.
     */
    fun onSfuSignal(
        channelId: String,
        kind: AuthorityCallSignaling.ChannelKind,
        myUid: String,
        fromUid: String,
        signal: JSONObject,
    ) {
        Log.i(TAG, "onSfuSignal type=${signal.optString("type")} from=$fromUid channel=$channelId ringNull=${ring == null}")
        if (ring == null || _uiCall.value == null) {
            incoming = true
            bindSignaling(channelId, kind, myUid)
        } else if (boundChannelId != channelId) {
            // A signal for a different channel while we're busy — ignore (ring will busy/ignore anyway).
            return
        }
        ring?.handleSignal(fromUid, signal)
    }

    fun accept() {
        ring?.accept()
        emitUi()
    }

    fun reject() = ring?.reject() ?: Unit
    fun end() = ring?.end() ?: Unit

    fun setMuted(muted: Boolean) {
        this.muted = muted
        media?.setMuted(muted)
        // Re-announce on the roster so peers show/hide the mute icon on our tile (web does the same).
        published?.let { (sessionId, tracks) ->
            room?.publishSelf(
                name = selfName,
                photoUrl = selfPhotoUrl,
                cameraOn = media?.cameraOn == true,
                muted = muted,
                sessionId = sessionId,
                tracks = tracks,
            )
        }
        emitUi()
    }


    /** Camera on/off (voice→video upgrade publishes the camera mid-call, like the web). */
    fun toggleCamera() {
        scope.launch {
            runCatching { media?.toggleCamera() }.onFailure { Log.w(TAG, "toggleCamera failed", it) }
            if (media?.cameraOn == true) usedVideo = true
            emitUi()
        }
    }

    fun switchCamera() = media?.switchCamera() ?: Unit

    /** Begin screen share with the MediaProjection consent [projectionData] (from the Activity result). */
    fun startScreenShare(projectionData: android.content.Intent) {
        scope.launch {
            runCatching { media?.startScreenShare(projectionData) }
                .onFailure { Log.w(TAG, "startScreenShare failed", it) }
            emitUi()
        }
    }

    fun stopScreenShare() {
        scope.launch {
            runCatching { media?.stopScreenShare() }.onFailure { Log.w(TAG, "stopScreenShare failed", it) }
            emitUi()
        }
    }

    /** True when this manager currently owns an active/ringing call (so callers can skip the P2P path). */
    fun hasActiveCall(): Boolean = _uiCall.value != null

    // ---- wiring ----

    private fun bindSignaling(channelId: String, kind: AuthorityCallSignaling.ChannelKind, myUid: String) {
        this.myUid = myUid
        this.boundChannelId = channelId
        this.boundKind = kind
        this.usedVideo = false
        this.callLogged = false
        val sig = AuthorityCallSignaling(channelId = channelId, myUid = myUid, kind = kind)
        signaling = sig
        val sender = SfuSignalSender { toUid, signal ->
            scope.launch { runCatching { sig.sender.send(peerContact(toUid), signal.toString()) } }
        }
        ring = SfuRingManager(
            scope = scope,
            sender = sender,
            onState = { st -> onRingState(st) },
            onRoom = { roomId, _ -> onRoom(roomId) },
        )
    }

    private fun onRingState(st: SfuCallState) {
        if (st == SfuCallState.INCOMING) {
            // Ring even when no activity is visible: the FGS renders the CallStyle incoming
            // notification (answer/decline + fullscreen intent) and loops the ringtone.
            runCatching { InternetCallForegroundService.start(appContext) }
        }
        if (st == SfuCallState.ENDED || st == SfuCallState.IDLE) {
            maybeWriteCallLog()
            connectedAtMillis?.let { startedAt ->
                Analytics.callEnded(
                    transport = "authority_sfu",
                    durationSeconds = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L),
                    result = "answered"
                )
            }
            connectedAtMillis = null
            teardownMedia()
            _uiCall.value = if (st == SfuCallState.IDLE) null else _uiCall.value?.copy(state = st)
            if (st == SfuCallState.IDLE) { ring = null; signaling = null }
        } else {
            emitUi(st)
        }
    }

    private fun emitUi(st: SfuCallState = ring?.state ?: SfuCallState.IDLE) {
        val r = ring
        if (st == SfuCallState.CONNECTED && connectedAtMillis == null) {
            connectedAtMillis = System.currentTimeMillis()
            Analytics.callConnected("authority_sfu")
        }
        if (st == SfuCallState.IDLE) connectedAtMillis = null
        _uiCall.value = if (r == null || st == SfuCallState.IDLE) {
            null
        } else {
            SfuUiCall(
                peerName = r.peerName.orEmpty(),
                peerUid = r.peerUid.orEmpty(),
                incoming = incoming,
                state = st,
                muted = muted,
                connectedAtMillis = connectedAtMillis,
                video = r.video,
                cameraOn = media?.cameraOn == true,
                sharingScreen = media?.sharingScreen == true,
            )
        }
    }

    /** Ring accepted on both sides → join the SFU room and start audio. */
    private fun onRoom(roomId: String) {
        Log.i(TAG, "onRoom roomId=$roomId — joining SFU media (preWarmed=${media != null})")
        if (media != null) {
            // Pre-warmed during the outgoing ring — the callee accepted, open the mic and go.
            media?.setMicHold(false)
            return
        }
        joinMedia(roomId, micHold = false)
    }

    private fun joinMedia(roomId: String, micHold: Boolean) {
        val roomClient = SfuRoomClient(roomId, myUid)
        this.room = roomClient
        // E2EE requires BOTH native libs: the MLS handshake worker AND the frame-crypto bridge. The web
        // dashboard enforces MLS, so without them audio can't interop (it stays plaintext → web drops it).
        val e2ee = MlsWorker.available && MlsFrameCrypto.available
        Log.i(TAG, "onRoom e2ee=$e2ee (mlsWorker=${MlsWorker.available} frameCrypto=${MlsFrameCrypto.available})")
        val mediaClient = SfuCallManager(
            context = appContext,
            scope = scope,
            e2ee = e2ee,
            onState = { st -> if (st == SfuMediaState.FAILED) end() },
            onPublished = { sessionId, tracks ->
                published = sessionId to tracks
                roomClient.publishSelf(
                    name = selfName,
                    photoUrl = selfPhotoUrl,
                    cameraOn = media?.cameraOn == true, // media is assigned before join() can publish
                    muted = muted,
                    sessionId = sessionId,
                    tracks = tracks,
                )
                emitUi()
            },
        )
        this.media = mediaClient
        mediaClient.setMicHold(micHold)
        // Mirror this call's video into the manager-stable flow. Cancelled + replaced per call so a
        // finished call's engine (whose StateFlow lives on forever) never leaks a collector.
        videoCollectJob?.cancel()
        videoCollectJob = scope.launch {
            mediaClient.videoStreams.collect { if (media === mediaClient) _videoStreams.value = it }
        }
        runCatching { InternetCallForegroundService.start(appContext) }
        scope.launch {
            runCatching { mediaClient.join(video = ring?.video == true) }
                .onFailure { Log.w(TAG, "SFU join failed", it) }
            // The call may have been torn down while join() was in flight (rejected / ring timeout /
            // hung up) — don't attach a roster listener or watchdog to a dead engine (they'd leak).
            if (media !== mediaClient) return@launch
            emitUi() // surface the initial camera state to the overlay
            rosterReg = roomClient.listenRoster { remotes ->
                val now = System.currentTimeMillis()
                val fresh = remotes.any { r ->
                    r.updatedAtMillis == null || now - r.updatedAtMillis < PEER_STALE_MS
                }
                if (fresh) {
                    lastPeerAliveMillis = now
                    everSawPeer = true
                }
                scope.launch { runCatching { mediaClient.setRoster(remotes) } }
            }
            startLivenessWatchdog(roomClient)
        }
        // MLS-E2EE group (Faz C). Drive the group handshake over Firestore so the native FrameEncryptor/
        // Decryptor (attached in SfuCallManager) have a ready group to encrypt/decrypt against. Only when
        // both native libs are present — otherwise frames stay plaintext and the handshake is pointless.
        if (e2ee) {
            scope.launch {
                val isCreator = runCatching { roomClient.claimMlsCreator() }.getOrDefault(false)
                if (media !== mediaClient) return@launch // torn down mid-claim — don't strand an MlsSession
                Log.i(TAG, "MLS start (creator=$isCreator)")
                mlsSession = MlsSession(myUid, roomClient).also { it.start(isCreator) }
            }
        }
    }

    /**
     * Liveness: heartbeat my roster entry every 10s, and END the call when the peer goes silent — an
     * app kill / crash / dead network on the other side never sends an "end" signal and never deletes
     * its roster doc, which used to leave THIS side sitting in a ghost call forever. Graceful leaves
     * (doc deleted) are caught fast; ungraceful ones when the peer's heartbeat goes stale.
     */
    private fun startLivenessWatchdog(roomClient: SfuRoomClient) {
        lastPeerAliveMillis = System.currentTimeMillis()
        everSawPeer = false
        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEARTBEAT_INTERVAL_MS)
                roomClient.heartbeat()
                val connected = ring?.state == SfuCallState.CONNECTED
                if (connected && everSawPeer &&
                    System.currentTimeMillis() - lastPeerAliveMillis > PEER_LOST_END_MS
                ) {
                    Log.w(TAG, "peer liveness lost (${PEER_LOST_END_MS}ms) — ending the call")
                    end()
                    break
                }
            }
        }
    }

    /**
     * WhatsApp-style call summary in the chat, matching the web exactly: only the CALLER writes it
     * (the shared encrypted message shows in both threads — writing on both sides would duplicate it),
     * wire format `call{"kind","status","durationSec"}` (web secure-channel.ts encodeCallLog; rendered
     * by ChannelBubble→CallEventRow on Android and the dashboard's call bubble on web). Web-originated
     * calls were already logged by the web; without this, Android-originated calls left no history.
     */
    private fun maybeWriteCallLog() {
        if (callLogged || incoming) return
        if (boundKind != AuthorityCallSignaling.ChannelKind.HIERARCHY) return // agency calls have no 1:1 thread
        val channelId = boundChannelId.takeIf { it.isNotBlank() } ?: return
        val peerUid = ring?.peerUid ?: return
        val peerName = ring?.peerName.orEmpty()
        callLogged = true
        val connected = connectedAtMillis
        val durationSec = connected?.let { ((System.currentTimeMillis() - it) / 1000L).coerceAtLeast(0L) } ?: 0L
        val body = "call" + JSONObject()
            .put("kind", if (usedVideo) "video" else "audio")
            .put("status", if (connected != null) "ended" else "missed")
            .put("durationSec", durationSec)
            .toString()
        scope.launch {
            runCatching {
                val client = com.auralis.crisisconnect.messaging.HierarchyMessagingClient()
                val key = client.fetchChannelKey(channelId)
                client.send(
                    channelKey = key,
                    senderName = selfName,
                    recipientUid = peerUid,
                    recipientName = peerName,
                    text = body,
                )
            }.onFailure { Log.w(TAG, "call log write failed", it) }
        }
    }

    private fun teardownMedia() {
        livenessJob?.cancel()
        livenessJob = null
        videoCollectJob?.cancel()
        videoCollectJob = null
        published = null
        _videoStreams.value = SfuVideoStreams()
        rosterReg?.remove()
        rosterReg = null
        mlsSession?.stop()
        mlsSession = null
        room?.leave()
        room = null
        media?.leave()
        media = null
        runCatching { InternetCallForegroundService.stop(appContext) }
    }

    // Non-blank peerPublicKey so Contact.supportsInternet is true (channel calls carry no E2E envelope).
    private fun peerContact(uid: String): Contact =
        Contact(name = "", aesKey = "", sessionCode = "", peerUid = uid, peerPublicKey = "channel")
}
