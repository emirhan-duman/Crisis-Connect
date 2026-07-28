package com.auralis.crisisconnect.messaging.call.sfu

import com.auralis.crisisconnect.analytics.Analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** Call lifecycle state, mirroring the web CallState union (webrtc-call.ts). */
enum class SfuCallState { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

/** Writes one web-compatible call signal (type/callId/roomId) addressed to [toUid] over callSignals. */
fun interface SfuSignalSender {
    fun send(toUid: String, signal: JSONObject)
}

/**
 * Android mirror of the web [SfuRingManager] (lib/messaging/sfu-ring.ts): ring/signaling for SFU calls.
 * It carries **no media** — the `offer` signal carries a shared ephemeral `roomId`; when the callee
 * accepts, both sides get [onRoom] and join the Cloudflare Realtime SFU room where the actual E2EE media
 * flows (Faz B). Reuses the existing callSignals Firestore transport, so it is byte-compatible with the
 * web dashboard and web↔Android authority calls ring on both sides.
 *
 * Scoped to AUTHORITY (hierarchy + agency) calls only; citizen P2P calls keep [InternetCallManager].
 */
class SfuRingManager(
    private val scope: CoroutineScope,
    private val sender: SfuSignalSender,
    private val onState: (SfuCallState) -> Unit,
    /** Fires once the call is accepted on both sides with the shared room to join ([isCaller] role). */
    private val onRoom: (roomId: String, isCaller: Boolean) -> Unit,
) {
    var callId: String? = null
        private set
    var peerUid: String? = null
        private set
    var peerName: String? = null

    /** Whether this call was placed as a VIDEO call (camera on) — set from startCall (caller) or
     *  the offer signal (callee), so a voice call never lights up either side's camera. */
    var video = false
        private set
    var roomId: String? = null
        private set
    var state: SfuCallState = SfuCallState.IDLE
        private set

    private var ringJob: Job? = null
    private var cleanupJob: Job? = null

    private fun set(next: SfuCallState) {
        android.util.Log.i("SfuRing", "state $state → $next (callId=$callId roomId=$roomId)")
        state = next
        // Leaving a ringing state (answered, connecting, ended…) cancels the no-answer timeout.
        if (next != SfuCallState.OUTGOING && next != SfuCallState.INCOMING) clearRing()
        onState(next)
    }

    private fun startRing() {
        clearRing()
        ringJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            when (state) {
                SfuCallState.OUTGOING -> end() // caller: no answer
                SfuCallState.INCOMING -> reject() // callee: didn't pick up in time
                else -> {}
            }
        }
    }

    private fun clearRing() {
        ringJob?.cancel()
        ringJob = null
    }

    /** Place an outgoing authority call: mint a shared room id and invite the peer. */
    fun startCall(peerUid: String, peerName: String, video: Boolean = false) {
        if (state != SfuCallState.IDLE) return
        Analytics.callStarted("authority_sfu")
        this.peerUid = peerUid
        this.peerName = peerName
        this.video = video
        callId = UUID.randomUUID().toString()
        roomId = UUID.randomUUID().toString()
        set(SfuCallState.OUTGOING)
        startRing()
        sender.send(peerUid, signal("offer").put("roomId", roomId).put("video", video))
    }

    /** Accept the ringing call and join the invited room. */
    fun accept() {
        if (state != SfuCallState.INCOMING) return
        val peer = peerUid ?: return
        val room = roomId ?: return
        if (callId == null) return
        set(SfuCallState.CONNECTING)
        sender.send(peer, signal("answer"))
        onRoom(room, false)
        set(SfuCallState.CONNECTED)
    }

    fun reject() {
        val peer = peerUid
        if (peer != null && callId != null) sender.send(peer, signal("reject"))
        cleanup()
    }

    fun end() {
        val peer = peerUid
        if (peer != null && callId != null) sender.send(peer, signal("end"))
        cleanup()
    }

    /** Routes an inbound call signal (from the callSignals listener) through the ring state machine. */
    fun handleSignal(fromUid: String, signal: JSONObject) {
        when (signal.optString("type")) {
            "offer" -> {
                val sigCallId = signal.optString("callId").takeIf { it.isNotBlank() } ?: return
                // Busy if already on a different call.
                if (state != SfuCallState.IDLE && callId != sigCallId) {
                    sender.send(fromUid, JSONObject().put("type", "busy").put("callId", sigCallId))
                    return
                }
                // Duplicate delivery of the SAME offer (a listener re-attach replays the collection):
                // ignore — re-entering INCOMING while ringing/connected re-rings and can double-join.
                if (callId == sigCallId && state != SfuCallState.IDLE) return
                val room = signal.optString("roomId").takeIf { it.isNotBlank() } ?: return // not an SFU invite
                peerUid = fromUid
                callId = sigCallId
                roomId = room
                video = signal.optBoolean("video", false)
                set(SfuCallState.INCOMING)
                startRing()
            }
            "answer" -> {
                if (signal.optString("callId") != callId) return
                // Only the caller, and only ONCE: a replayed answer must not fire onRoom (join) again.
                if (state != SfuCallState.OUTGOING) return
                val room = roomId ?: return
                set(SfuCallState.CONNECTING)
                onRoom(room, true)
                set(SfuCallState.CONNECTED)
                // Answered elsewhere: the callee may have other ringing sessions (web tabs) — tell them
                // the call is taken so they stop ringing. The answering session ignores this ("cancel"
                // only acts while INCOMING).
                peerUid?.let {
                    sender.send(it, JSONObject().put("type", "cancel").put("callId", callId))
                }
            }
            "cancel" -> {
                // Another session of MINE answered this call — stop ringing here, silently.
                if (signal.optString("callId") == callId && state == SfuCallState.INCOMING) cleanup()
            }
            "reject", "busy" -> {
                // Only while ringing. The callee can have OTHER sessions (web multi-tab / multi-device);
                // a session that never answered ring-times-out ~35s in and sends reject — that must not
                // kill a call the answering session already connected (observed live: answer, then a
                // stale reject at +35s tore the whole call down).
                if (signal.optString("callId") == callId &&
                    (state == SfuCallState.OUTGOING || state == SfuCallState.INCOMING)
                ) {
                    cleanup()
                }
            }
            "end" -> {
                if (signal.optString("callId") == callId) cleanup()
            }
            "ice" -> Unit // SFU calls carry no peer-to-peer ICE
        }
    }

    private fun cleanup() {
        clearRing()
        set(SfuCallState.ENDED)
        // Brief "ended" flash, then back to idle so a fresh call can start.
        cleanupJob?.cancel()
        cleanupJob = scope.launch {
            delay(ENDED_FLASH_MS)
            callId = null
            peerUid = null
            peerName = null
            roomId = null
            video = false
            if (state == SfuCallState.ENDED) set(SfuCallState.IDLE)
        }
    }

    fun dispose() {
        clearRing()
        cleanupJob?.cancel()
    }

    private fun signal(type: String) = JSONObject().put("type", type).put("callId", callId)

    companion object {
        /** A ringing call auto-ends after this long if unanswered (same as the web + P2P paths). */
        private const val RING_TIMEOUT_MS = 35_000L
        private const val ENDED_FLASH_MS = 1_200L
    }
}
