package com.auralis.crisisconnect.service.gattmesh.ptt

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.service.gattmesh.MeshDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Control message kinds exchanged for telsiz session/floor coordination. */
internal enum class PttControlKind { JOIN, LEAVE, FLOOR_CLAIM, FLOOR_RELEASE }

/**
 * Orchestrates the authority-mesh telsiz: session presence, single-speaker floor lock, and the
 * mic→Opus→broadcast / receive→decode→play loops (via [PttAudioEngine]).
 *
 * Floor model (user-approved): strict single speaker. Press-to-talk claims the floor only when it
 * is free; if a peer holds it, the local press is rejected (busy). Simultaneous claims are resolved
 * deterministically by a random `claimId` carried in the FLOOR_CLAIM (higher wins) so both sides
 * converge on the same speaker. Audio frames are fire-and-forget (no ACK); loss is concealed by the
 * jitter buffer.
 *
 * **Multi-hop:** peers and the floor are keyed by a stable per-device **origin id** (carried in every
 * telsiz packet), NOT by the BLE hop address, so a participant reached via a relay is identified
 * correctly. Presence is liveness-based: each joined node re-announces (JOIN heartbeat) periodically
 * and entries not heard from within [PRESENCE_TIMEOUT_MS] are swept — this works uniformly whether a
 * peer is one hop or several hops away. The transport relays packets onward (see the service).
 */
internal class PttController(
    context: Context,
    private val scope: CoroutineScope,
    private val profileId: String,
    private val localDisplayName: () -> String,
    private val localAgency: () -> String? = { null },
    private val resolvePeerName: (String) -> String,
    private val onSendControl: (PttControlKind, claimId: Long?) -> Unit,
    // Sends one BLE packet carrying [frames] consecutive Opus frames starting at [baseSeq], tagged
    // with [talkTag] (a per-transmission id) so receivers can dedup relayed copies. Bundling 2 frames
    // per packet halves the BLE packet rate (50→25/s), giving the link headroom and cutting dropouts.
    private val onSendAudio: (talkTag: String, baseSeq: Int, frames: List<ByteArray>) -> Unit,
    // Fired when the floor crosses the idle boundary: true while someone is actively speaking
    // (local or remote), false when idle. The service uses this to free the BLE radio (pause
    // discovery scanning) only during actual audio, not for the whole joined session.
    private val onAudioActiveChanged: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val engine = PttAudioEngine(appContext, scope)
    private val lock = Any()

    private val _state = MutableStateFlow(PttSessionState())
    val state: StateFlow<PttSessionState> = _state.asStateFlow()

    // Presence: origin id -> display name, plus last-heard timestamps for liveness sweeping.
    private val peers = linkedMapOf<String, String>()
    private val peerLastSeen = HashMap<String, Long>()
    // origin id -> verified institution (kurum), shown to the right of the name in the participant list.
    private val peerAgency = HashMap<String, String>()

    private var floor = PttFloorState.IDLE
    private var localClaimId: Long? = null
    private var localTalkStartedAt = 0L
    private var localSeq = 0
    // Per-transmission tag stamped into outgoing audio packet ids so relays can dedup. New on press.
    @Volatile private var localTalkTag = ""
    private var remoteSpeaker: String? = null
    private var remoteClaimId: Long = Long.MIN_VALUE
    private var remoteLastAudioAt = 0L
    private var audioActive = false

    private var watchdogJob: Job? = null
    private var savedAudioMode: Int? = null
    private var savedSpeakerphone: Boolean? = null
    private var startedBluetoothSco = false

    // Decouples mic capture from the (blocking) GATT writes: the capture loop only enqueues frames,
    // a single consumer drains them. Bounded + DROP_OLDEST so a slow link never backs up memory or
    // adds latency — dropped frames are concealed by the receiver's jitter buffer.
    private var audioSendChannel: Channel<Pair<Int, ByteArray>>? = null
    private var audioSendJob: Job? = null

    fun join() {
        synchronized(lock) {
            if (_state.value.joined) return
            if (!engine.prepare()) {
                MeshDiagnostics.log(profileId, "ptt join failed: audio engine unavailable")
                return
            }
            acquireAudioRoute()
            startAudioSenderLocked()
            _state.value = _state.value.copy(joined = true)
            publishLocked()
            startWatchdogLocked()
        }
        onSendControl(PttControlKind.JOIN, null)
        MeshDiagnostics.log(profileId, "ptt joined")
    }

    fun leave() {
        val wasSpeaking: Boolean
        synchronized(lock) {
            if (!_state.value.joined) return
            wasSpeaking = floor == PttFloorState.LOCAL_SPEAKING
            engine.stopCapture()
            engine.stopPlayback()
            engine.release()
            floor = PttFloorState.IDLE
            localClaimId = null
            remoteSpeaker = null
            peers.clear()
            peerLastSeen.clear()
            peerAgency.clear()
            releaseAudioRoute()
            stopAudioSenderLocked()
            watchdogJob?.cancel()
            watchdogJob = null
            _state.value = PttSessionState()
            if (audioActive) {
                audioActive = false
                scope.launch { onAudioActiveChanged(false) }
            }
        }
        if (wasSpeaking) onSendControl(PttControlKind.FLOOR_RELEASE, null)
        onSendControl(PttControlKind.LEAVE, null)
        MeshDiagnostics.log(profileId, "ptt left")
    }

    /** Press-to-talk down. Claims the floor only when free. */
    fun pressTalk() {
        var claimId: Long? = null
        synchronized(lock) {
            if (!_state.value.joined) return
            when (floor) {
                PttFloorState.LOCAL_SPEAKING -> return
                PttFloorState.REMOTE_SPEAKING -> {
                    _state.value = _state.value.copy(busyTick = _state.value.busyTick + 1)
                    MeshDiagnostics.log(profileId, "ptt press rejected: floor busy (${remoteSpeaker?.let { peers[it] }})")
                    return
                }
                PttFloorState.IDLE -> {
                    val id = Random.nextLong()
                    localTalkTag = Random.nextInt(0x10000).toString(16).padStart(4, '0')
                    if (!engine.startCapture { opus -> broadcastAudioFrame(opus) }) {
                        MeshDiagnostics.log(profileId, "ptt capture failed (mic permission/route)")
                        return
                    }
                    localClaimId = id
                    localSeq = 0
                    localTalkStartedAt = System.currentTimeMillis()
                    floor = PttFloorState.LOCAL_SPEAKING
                    claimId = id
                    publishLocked()
                }
            }
        }
        claimId?.let {
            onSendControl(PttControlKind.FLOOR_CLAIM, it)
            MeshDiagnostics.log(profileId, "ptt floor claimed (local)")
            Analytics.pttTransmit()
        }
    }

    /** Press-to-talk up. Releases the floor if we held it. */
    fun releaseTalk() {
        var release = false
        synchronized(lock) {
            if (floor != PttFloorState.LOCAL_SPEAKING) return
            engine.stopCapture()
            floor = PttFloorState.IDLE
            localClaimId = null
            release = true
            publishLocked()
        }
        if (release) {
            onSendControl(PttControlKind.FLOOR_RELEASE, null)
            MeshDiagnostics.log(profileId, "ptt floor released (local)")
        }
    }

    /**
     * Handle a telsiz control packet. [origin] is the sender's stable origin id (hop-independent),
     * [senderLabel] their display name.
     */
    fun onPeerControl(origin: String, senderLabel: String, agency: String?, kind: PttControlKind, claimId: Long?) {
        synchronized(lock) {
            if (!_state.value.joined) return
            val now = System.currentTimeMillis()
            agency?.takeIf { it.isNotBlank() }?.let { peerAgency[origin] = it }
            when (kind) {
                PttControlKind.JOIN -> {
                    // Only republish when a genuinely new participant appears; heartbeats from known
                    // peers just refresh liveness (avoids needless UI churn every few seconds).
                    val isNew = peers.put(origin, senderLabel.ifBlank { peers[origin] ?: "—" }) == null
                    peerLastSeen[origin] = now
                    if (isNew) publishLocked()
                }
                PttControlKind.LEAVE -> {
                    peers.remove(origin)
                    peerLastSeen.remove(origin)
                    peerAgency.remove(origin)
                    if (remoteSpeaker == origin) clearRemoteFloorLocked()
                    publishLocked()
                }
                PttControlKind.FLOOR_CLAIM -> {
                    peers[origin] = senderLabel.ifBlank { peers[origin] ?: "—" }
                    peerLastSeen[origin] = now
                    val incoming = claimId ?: 0L
                    val grant = PttFloorRules.shouldGrantRemoteClaim(
                        floor = floor,
                        localClaimId = localClaimId,
                        currentRemoteSpeaker = remoteSpeaker,
                        currentRemoteClaimId = remoteClaimId,
                        fromAddress = origin,
                        incomingClaimId = incoming
                    )
                    if (grant) {
                        if (floor == PttFloorState.LOCAL_SPEAKING) {
                            // Lost the tie-break: yield the floor to the peer.
                            engine.stopCapture()
                            localClaimId = null
                            MeshDiagnostics.log(profileId, "ptt yielded floor to ${peers[origin]}")
                        }
                        beginRemoteFloorLocked(origin, incoming)
                    } else {
                        publishLocked()
                    }
                }
                PttControlKind.FLOOR_RELEASE -> {
                    if (remoteSpeaker == origin) clearRemoteFloorLocked()
                }
            }
        }
    }

    /** Handle a telsiz audio frame from [origin] (stable origin id), labelled [senderLabel]. */
    fun onPeerAudio(origin: String, senderLabel: String, seq: Int, opusBytes: ByteArray) {
        synchronized(lock) {
            if (!_state.value.joined) return
            peerLastSeen[origin] = System.currentTimeMillis()
            if (origin !in peers) peers[origin] = senderLabel.ifBlank { "—" }
            when (floor) {
                PttFloorState.LOCAL_SPEAKING -> return
                PttFloorState.REMOTE_SPEAKING -> {
                    if (origin != remoteSpeaker) return
                    remoteLastAudioAt = System.currentTimeMillis()
                }
                PttFloorState.IDLE -> {
                    // Recover from a brief transport stall that tripped the stale-floor watchdog, or
                    // adopt a speaker whose FLOOR_CLAIM we never received (joined mid-talk / lossy
                    // multi-hop): resuming audio (re-)adopts the speaker so it keeps playing.
                    beginRemoteFloorLocked(
                        origin,
                        remoteClaimId.takeIf { it != Long.MIN_VALUE } ?: 0L
                    )
                }
            }
        }
        engine.submitFrame(seq, opusBytes)
    }

    /** No-op: presence is liveness/timeout-based for multi-hop (a peer need not be directly connected). */
    fun onPeerDisconnected(address: String) { /* presence handled by the heartbeat sweep */ }

    /** No-op for multi-hop: direct-connection state no longer defines telsiz membership. */
    fun reconcileConnectedPeers(connectedAddresses: Set<String>) { /* presence handled by the heartbeat sweep */ }

    fun shutdown() {
        synchronized(lock) {
            engine.release()
            stopAudioSenderLocked()
            watchdogJob?.cancel()
            watchdogJob = null
            releaseAudioRoute()
            _state.value = PttSessionState()
            if (audioActive) {
                audioActive = false
                scope.launch { onAudioActiveChanged(false) }
            }
        }
    }

    // --- internal helpers (lock held) ---

    private fun beginRemoteFloorLocked(origin: String, claimId: Long) {
        val switching = remoteSpeaker != origin || floor != PttFloorState.REMOTE_SPEAKING
        remoteSpeaker = origin
        remoteClaimId = claimId
        remoteLastAudioAt = System.currentTimeMillis()
        floor = PttFloorState.REMOTE_SPEAKING
        if (switching) {
            engine.stopPlayback()
            engine.startPlayback()
        }
        publishLocked()
    }

    private fun clearRemoteFloorLocked() {
        engine.stopPlayback()
        remoteSpeaker = null
        remoteClaimId = Long.MIN_VALUE
        floor = PttFloorState.IDLE
        publishLocked()
    }

    private fun broadcastAudioFrame(opus: ByteArray) {
        val seq = synchronized(lock) {
            if (floor != PttFloorState.LOCAL_SPEAKING) return
            localSeq++
        }
        // Non-blocking enqueue from the capture thread; the sender coroutine does the GATT writes.
        audioSendChannel?.trySend(seq to opus)
    }

    private fun startAudioSenderLocked() {
        audioSendJob?.cancel()
        val channel = Channel<Pair<Int, ByteArray>>(
            capacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        audioSendChannel = channel
        audioSendJob = scope.launch {
            val bundle = ArrayList<ByteArray>(FRAMES_PER_PACKET)
            var baseSeq = 0
            while (isActive) {
                // Wait for the next frame, but flush a partial bundle if capture pauses/stops so the
                // tail frame of a transmission is never held back waiting for a partner.
                val received = withTimeoutOrNull(BUNDLE_FLUSH_TIMEOUT_MS) {
                    channel.receiveCatching().getOrNull()
                }
                if (received == null) {
                    if (bundle.isNotEmpty()) {
                        onSendAudio(localTalkTag, baseSeq, bundle.toList())
                        bundle.clear()
                    }
                    if (channel.isClosedForReceive) break else continue
                }
                val (seq, opus) = received
                if (bundle.isEmpty()) baseSeq = seq
                bundle.add(opus)
                if (bundle.size >= FRAMES_PER_PACKET) {
                    onSendAudio(localTalkTag, baseSeq, bundle.toList())
                    bundle.clear()
                }
            }
        }
    }

    private fun stopAudioSenderLocked() {
        audioSendJob?.cancel()
        audioSendJob = null
        audioSendChannel?.close()
        audioSendChannel = null
    }

    private fun startWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var sinceHeartbeatMs = 0L
            while (isActive) {
                delay(WATCHDOG_TICK_MS)
                sinceHeartbeatMs += WATCHDOG_TICK_MS
                var autoReleased = false
                var sendHeartbeat = false
                synchronized(lock) {
                    if (!_state.value.joined) return@synchronized
                    val now = System.currentTimeMillis()
                    if (floor == PttFloorState.REMOTE_SPEAKING &&
                        now - remoteLastAudioAt > REMOTE_STALE_MS
                    ) {
                        clearRemoteFloorLocked()
                        MeshDiagnostics.log(profileId, "ptt remote floor timed out")
                    }
                    if (floor == PttFloorState.LOCAL_SPEAKING &&
                        now - localTalkStartedAt > MAX_TALK_MS
                    ) {
                        engine.stopCapture()
                        floor = PttFloorState.IDLE
                        localClaimId = null
                        _state.value = _state.value.copy(maxTalkTick = _state.value.maxTalkTick + 1)
                        publishLocked()
                        autoReleased = true
                        MeshDiagnostics.log(profileId, "ptt local talk auto-released (max duration)")
                    }
                    // Liveness sweep: drop participants we haven't heard from within the timeout.
                    val stale = peerLastSeen.filterValues { now - it > PRESENCE_TIMEOUT_MS }.keys.toList()
                    if (stale.isNotEmpty()) {
                        stale.forEach { peers.remove(it); peerLastSeen.remove(it); peerAgency.remove(it) }
                        if (remoteSpeaker != null && remoteSpeaker !in peers) {
                            clearRemoteFloorLocked()
                        } else {
                            publishLocked()
                        }
                    }
                    if (sinceHeartbeatMs >= HEARTBEAT_MS) {
                        sinceHeartbeatMs = 0L
                        sendHeartbeat = true
                    }
                }
                if (autoReleased) onSendControl(PttControlKind.FLOOR_RELEASE, null)
                // Re-announce presence so multi-hop peers keep us alive during silent stretches.
                if (sendHeartbeat) onSendControl(PttControlKind.JOIN, null)
            }
        }
    }

    private fun publishLocked() {
        val joined = _state.value.joined
        val list = buildList {
            if (joined) {
                add(
                    PttParticipant(
                        address = PttParticipant.SELF_ADDRESS,
                        displayName = localDisplayName().ifBlank { "—" },
                        isSelf = true,
                        isSpeaking = floor == PttFloorState.LOCAL_SPEAKING,
                        agency = localAgency()?.takeIf { it.isNotBlank() }
                    )
                )
            }
            peers.forEach { (origin, name) ->
                add(
                    PttParticipant(
                        address = origin,
                        displayName = name.ifBlank { "—" },
                        isSpeaking = floor == PttFloorState.REMOTE_SPEAKING && origin == remoteSpeaker,
                        agency = peerAgency[origin]
                    )
                )
            }
        }
        val speakerName = when (floor) {
            PttFloorState.LOCAL_SPEAKING -> localDisplayName()
            PttFloorState.REMOTE_SPEAKING -> remoteSpeaker?.let { peers[it] ?: "—" }
            PttFloorState.IDLE -> null
        }
        _state.value = _state.value.copy(
            participants = list,
            floor = floor,
            speakerName = speakerName,
            joined = joined
        )
        // Signal active-audio transitions (idle <-> speaking) so the radio is freed only during
        // actual transmissions, not for the entire joined session (which would block peer discovery).
        val nowActive = joined && floor != PttFloorState.IDLE
        if (nowActive != audioActive) {
            audioActive = nowActive
            scope.launch { onAudioActiveChanged(nowActive) }
        }
    }

    private fun acquireAudioRoute() {
        val manager = audioManager ?: return
        if (savedAudioMode == null) savedAudioMode = manager.mode
        if (savedSpeakerphone == null) savedSpeakerphone = manager.isSpeakerphoneOn
        runCatching {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            // Route to a headset when present; only fall back to the loudspeaker when bare.
            val outputs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
            } else {
                emptyList()
            }
            val hasWired = outputs.any {
                it == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val hasBtSco = outputs.any { it == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            when {
                hasBtSco -> {
                    // Bluetooth headset: SCO carries both the mic and earpiece for two-way telsiz.
                    runCatching {
                        @Suppress("DEPRECATION")
                        manager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        manager.isBluetoothScoOn = true
                        startedBluetoothSco = true
                    }
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = false
                }
                hasWired -> {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = false
                }
                else -> {
                    @Suppress("DEPRECATION")
                    manager.isSpeakerphoneOn = true
                }
            }
        }
    }

    private fun releaseAudioRoute() {
        val manager = audioManager ?: return
        runCatching {
            if (startedBluetoothSco) {
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.isBluetoothScoOn = false
                    @Suppress("DEPRECATION")
                    manager.stopBluetoothSco()
                }
                startedBluetoothSco = false
            }
            savedSpeakerphone?.let {
                @Suppress("DEPRECATION")
                manager.isSpeakerphoneOn = it
            }
            savedAudioMode?.let { manager.mode = it }
        }
        savedSpeakerphone = null
        savedAudioMode = null
    }

    private companion object {
        private const val TAG = "PttController"
        private const val REMOTE_STALE_MS = 2_000L
        private const val MAX_TALK_MS = 30_000L
        private const val WATCHDOG_TICK_MS = 500L
        // Re-announce presence every few seconds; drop peers not heard from within the timeout.
        private const val HEARTBEAT_MS = 3_000L
        private const val PRESENCE_TIMEOUT_MS = 10_000L
        // Opus frames bundled into one BLE packet (halves the on-air packet rate).
        private const val FRAMES_PER_PACKET = 2
        // Flush a partial bundle if no new frame arrives within this window (end of a transmission).
        private const val BUNDLE_FLUSH_TIMEOUT_MS = 30L
    }
}
