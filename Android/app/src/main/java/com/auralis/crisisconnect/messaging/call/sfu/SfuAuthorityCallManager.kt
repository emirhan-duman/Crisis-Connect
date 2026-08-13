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
    /** MLS group fingerprint; users can compare this out-of-band with the other participant. */
    val safetyNumber: String? = null,
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
    private const val PREFERENCES_NAME = "authority_call_preferences"
    private const val SCREEN_SHARE_QUALITY_KEY = "screen_share_quality"
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
    private var safetyNumber: String? = null
    @Volatile private var lastPeerAliveMillis = 0L
    @Volatile private var everSawPeer = false

    private val _uiCall = MutableStateFlow<SfuUiCall?>(null)
    val uiCall: StateFlow<SfuUiCall?> = _uiCall.asStateFlow()

    // Stable across the whole app lifetime (media engines come and go per call): the overlay can
    // subscribe unconditionally from its very first composition and never miss a remote track.
    private val _videoStreams = MutableStateFlow(SfuVideoStreams())
    val videoStreamsFlow: StateFlow<SfuVideoStreams> = _videoStreams.asStateFlow()
    private val _screenShareQuality = MutableStateFlow(ScreenShareQualityPreset.AUTO)
    val screenShareQuality: StateFlow<ScreenShareQualityPreset> = _screenShareQuality.asStateFlow()

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        _screenShareQuality.value = ScreenShareQualityPreset.fromWireValue(
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(SCREEN_SHARE_QUALITY_KEY, null),
        )
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
        // Wait for the answer before media: only then is v1/v2 negotiation complete. Pre-warming a
        // v2 room would make a mixed-version call invisible to an old peer.
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
        peerName: String? = null,
    ) {
        Log.i(TAG, "onSfuSignal type=${signal.optString("type")} from=$fromUid channel=$channelId ringNull=${ring == null}")
        if ((ring == null || _uiCall.value == null) && signal.optString("type") != "offer") return
        if (ring == null || _uiCall.value == null) {
            incoming = true
            bindSignaling(channelId, kind, myUid)
        } else if (boundChannelId != channelId) {
            // A signal for a different channel while we're busy — ignore (ring will busy/ignore anyway).
            return
        }
        if (!peerName.isNullOrBlank() && ring?.peerName.isNullOrBlank()) ring?.peerName = peerName
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
                onError = { error -> failCoordination(room, error) },
            )
        }
        emitUi()
    }


    /** Camera on/off (voice→video upgrade publishes the camera mid-call, like the web). */
    fun toggleCamera() {
        scope.launch {
            runCatching { media?.toggleCamera() }.onFailure {
                Log.w(TAG, "toggleCamera failed", it)
                end()
            }
            if (media?.cameraOn == true) usedVideo = true
            emitUi()
        }
    }

    fun switchCamera() = media?.switchCamera() ?: Unit

    /** Begin screen share with the MediaProjection consent [projectionData] (from the Activity result). */
    fun startScreenShare(projectionData: android.content.Intent) {
        scope.launch {
            runCatching { media?.startScreenShare(projectionData) }
                .onFailure { Log.w(TAG, "startScreenShare failed", it); end() }
            emitUi()
        }
    }

    fun stopScreenShare() {
        scope.launch {
            runCatching { media?.stopScreenShare() }.onFailure { Log.w(TAG, "stopScreenShare failed", it); end() }
            emitUi()
        }
    }

    fun setScreenShareQuality(preset: ScreenShareQualityPreset) {
        _screenShareQuality.value = preset
        if (::appContext.isInitialized) {
            appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(SCREEN_SHARE_QUALITY_KEY, preset.wireValue)
                .apply()
        }
        media?.setScreenShareQuality(preset)
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
            onRoom = { roomId, _, version -> onRoom(roomId, version) },
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
                safetyNumber = safetyNumber,
            )
        }
    }

    /** Ring accepted on both sides → join the SFU room and start audio. */
    private fun onRoom(roomId: String, version: SfuProtocolVersion) {
        Log.i(TAG, "onRoom roomId=$roomId protocol=${version.wireValue}")
        joinMedia(roomId, micHold = false, version = version)
    }

    private fun joinMedia(roomId: String, micHold: Boolean, version: SfuProtocolVersion) {
        val currentRing = ring ?: return
        val callId = currentRing.callId ?: return
        val peerUid = currentRing.peerUid ?: return
        val binding = runCatching {
            SfuRoomBinding.create(
                scopeType = if (boundKind == AuthorityCallSignaling.ChannelKind.AGENCY) {
                    SfuRoomScopeType.AGENCY
                } else SfuRoomScopeType.HIERARCHY,
                channelId = boundChannelId,
                callId = callId,
                selfUid = myUid,
                peerUid = peerUid,
            )
        }.getOrElse {
            Log.e(TAG, "refusing unbound SFU room", it)
            end()
            return
        }
        val roomClient = SfuRoomClient(roomId, myUid, binding, version)
        this.room = roomClient
        // E2EE requires BOTH native libs: the MLS handshake worker AND the frame-crypto bridge. No
        // unencrypted media is permitted when either component is unavailable.
        val e2ee = MlsWorker.available && MlsFrameCrypto.available
        Log.i(TAG, "onRoom e2ee=$e2ee (mlsWorker=${MlsWorker.available} frameCrypto=${MlsFrameCrypto.available})")
        if (!e2ee) {
            Log.e(TAG, "refusing SFU join because mandatory MLS E2EE is unavailable")
            end()
            return
        }
        val mediaClient = SfuCallManager(
            context = appContext,
            scope = scope,
            roomId = roomId,
            callId = callId,
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
                    onError = { error -> failCoordination(roomClient, error) },
                )
                emitUi()
            },
        )
        this.media = mediaClient
        mediaClient.setScreenShareQuality(_screenShareQuality.value)
        mediaClient.setMicHold(micHold)
        // Mirror this call's video into the manager-stable flow. Cancelled + replaced per call so a
        // finished call's engine (whose StateFlow lives on forever) never leaks a collector.
        videoCollectJob?.cancel()
        videoCollectJob = scope.launch {
            mediaClient.videoStreams.collect { if (media === mediaClient) _videoStreams.value = it }
        }
        runCatching { InternetCallForegroundService.start(appContext) }
        scope.launch {
            val isCreator = runCatching { roomClient.claimMlsCreator() }.getOrElse {
                Log.e(TAG, "authorized MLS room claim failed", it)
                end()
                return@launch
            }
            if (media !== mediaClient) return@launch
            Log.i(TAG, "MLS start (creator=$isCreator)")
            mlsSession = MlsSession(
                myUid = myUid,
                room = roomClient,
                onSafetyNumber = { number ->
                    scope.launch {
                        if (room === roomClient) {
                            safetyNumber = number.chunked(5).joinToString(" ")
                            emitUi()
                        }
                    }
                },
                onFailure = { error -> failCoordination(roomClient, error) },
            ).also { it.start(isCreator) }
            runCatching { mediaClient.join(video = ring?.video == true) }.getOrElse {
                Log.w(TAG, "SFU join failed", it)
                end()
                return@launch
            }
            // The call may have been torn down while join() was in flight (rejected / ring timeout /
            // hung up) — don't attach a roster listener or watchdog to a dead engine (they'd leak).
            if (media !== mediaClient) return@launch
            emitUi() // surface the initial camera state to the overlay
            rosterReg = roomClient.listenRoster(
                onRoster = { remotes ->
                    val now = System.currentTimeMillis()
                    val fresh = remotes.any { r ->
                        r.updatedAtMillis == null || now - r.updatedAtMillis < PEER_STALE_MS
                    }
                    if (fresh) {
                        lastPeerAliveMillis = now
                        everSawPeer = true
                    }
                    scope.launch {
                        runCatching { mediaClient.setRoster(remotes) }
                            .onFailure { failCoordination(roomClient, it) }
                    }
                },
                onError = { error -> failCoordination(roomClient, error) },
            )
            startLivenessWatchdog(roomClient)
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
                roomClient.heartbeat { error -> failCoordination(roomClient, error) }
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

    /** Call history must be written through the verified Authority MLS chat session. */
    private fun maybeWriteCallLog() {
        if (callLogged || incoming) return
        if (boundKind != AuthorityCallSignaling.ChannelKind.HIERARCHY) return // agency calls have no 1:1 thread
        callLogged = true
        Log.i(TAG, "Skipped call-log persistence because no verified MLS chat session was supplied")
    }

    private fun teardownMedia() {
        livenessJob?.cancel()
        livenessJob = null
        videoCollectJob?.cancel()
        videoCollectJob = null
        published = null
        safetyNumber = null
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

    private fun failCoordination(roomClient: SfuRoomClient?, error: Throwable) {
        if (roomClient == null || room !== roomClient) return
        Log.e(TAG, "mandatory SFU/MLS coordination failed", error)
        scope.launch { end() }
    }

    // Non-blank peerPublicKey so Contact.supportsInternet is true (channel calls carry no E2E envelope).
    private fun peerContact(uid: String): Contact =
        Contact(name = "", aesKey = "", sessionCode = "", peerUid = uid, peerPublicKey = "channel")
}
