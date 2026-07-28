package com.auralis.crisisconnect.messaging.call

import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.Contact
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Web-compatible call signalling over an authority channel's Firestore `callSignals` subcollection
 * (`agencyPanels/{slug}/callSignals` or `hierarchyChannels/{id}/callSignals`). This is the transport
 * the web dashboard's CallManager uses (lib/messaging/call-signals.ts): one doc per signal, addressed
 * `to` a uid, `{from,to,callId,type,sdp?,candidate?,sdpMid?,sdpMLineIndex?}`. Reusing it lets an
 * Android authority call ring on the web (and vice-versa) with no relay/FCM needed — both sides just
 * read/write this membership-gated collection over Firestore realtime.
 *
 * It plugs into the shared [InternetCallManager] WebRTC engine: [sender] is passed to
 * `startCall`/`onChannelSignal` as the per-call transport override.
 */
class AuthorityCallSignaling(
    private val channelId: String,
    private val myUid: String,
    private val kind: ChannelKind,
    firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val peerNameResolver: (String) -> String = { it }
) {
    enum class ChannelKind { AGENCY, HIERARCHY }

    private val collection: CollectionReference = when (kind) {
        ChannelKind.AGENCY -> firestore.collection("agencyPanels").document(channelId).collection("callSignals")
        ChannelKind.HIERARCHY -> firestore.collection("hierarchyChannels").document(channelId).collection("callSignals")
    }

    /**
     * Sink for SFU (roomId) call signals — set by the SFU ring controller (Faz B) to intercept authority
     * calls before the legacy P2P engine. Null (default) means every signal still flows to
     * [InternetCallManager], so this whole SFU path is inert until Faz B wires a controller in.
     */
    var onSfuSignal: ((fromUid: String, signal: JSONObject) -> Unit)? = null

    /** The transport override handed to InternetCallManager — writes each signal as a web callSignals doc. */
    val sender = InternetCallManager.SignalSender { contact, signalJson ->
        runCatching {
            val s = JSONObject(signalJson)
            val type = s.optString("type").ifBlank { s.optString("kind") }
            if (type.isBlank()) return@SignalSender false
            // Remember our own outgoing SFU call so the peer's answer (which has no roomId) — caught by a
            // DIFFERENT AuthorityCallSignaling instance (the app-wide receiver) — still routes to SFU.
            if (type == "offer" && s.optString("roomId").isNotBlank()) rememberSfuCall(s.optString("callId"))
            val doc = hashMapOf<String, Any>(
                "from" to myUid,
                "to" to contact.peerUid,
                "callId" to s.optString("callId"),
                "type" to type,
                "createdAt" to FieldValue.serverTimestamp()
            )
            s.optString("sdp").takeIf { it.isNotBlank() }?.let { doc["sdp"] = it }
            s.optString("candidate").takeIf { it.isNotBlank() }?.let { doc["candidate"] = it }
            // SFU invites carry a shared room id on the offer instead of an SDP (web sfu-ring.ts).
            s.optString("roomId").takeIf { it.isNotBlank() }?.let { doc["roomId"] = it }
            if (s.has("sdpMid") && !s.isNull("sdpMid")) doc["sdpMid"] = s.optString("sdpMid")
            if (s.has("sdpMLineIndex") && !s.isNull("sdpMLineIndex")) doc["sdpMLineIndex"] = s.optInt("sdpMLineIndex")
            collection.add(doc).await()
            true
        }.getOrDefault(false)
    }

    /**
     * Subscribes to incoming signals addressed to me and feeds them to [InternetCallManager]. Dedups by
     * doc id and gates on FRESHNESS rather than skipping the attach-time backlog: a signal can land
     * BEFORE this listener attaches (cold app start + a callee answering within a second — observed
     * live: the answer sat in the initial snapshot, was skipped, and the phone rang until timeout).
     * Anything younger than a ring's lifetime is real; older docs are leftovers from previous calls and
     * are ignored (replaying those caused busy-storms). Returns the registration to remove.
     */
    fun listen(): ListenerRegistration {
        val seen = HashSet<String>()
        return collection.whereEqualTo("to", myUid).addSnapshotListener { snapshot, _ ->
            if (snapshot == null) return@addSnapshotListener
            for (change in snapshot.documentChanges) {
                if (change.type != DocumentChange.Type.ADDED) continue
                val id = change.document.id
                if (!seen.add(id)) continue
                // Null createdAt (pending server time on a latency-compensated echo) counts as fresh.
                val ageMs = change.document.getTimestamp("createdAt")?.toDate()?.time
                    ?.let { System.currentTimeMillis() - it }
                if (ageMs != null && ageMs > MAX_SIGNAL_AGE_MS) continue
                val d = change.document
                val type = d.getString("type") ?: continue
                if (type !in VALID_TYPES) continue
                val from = d.getString("from").orEmpty().takeIf { it.isNotBlank() } ?: continue
                val signal = JSONObject().apply {
                    put("type", type)
                    put("callId", d.getString("callId").orEmpty())
                    d.getString("sdp")?.let { put("sdp", it) }
                    d.getString("candidate")?.let { put("candidate", it) }
                    d.getString("roomId")?.let { put("roomId", it) }
                    d.getString("sdpMid")?.let { put("sdpMid", it) }
                    (d.get("sdpMLineIndex") as? Number)?.let { put("sdpMLineIndex", it.toInt()) }
                }
                // An SFU invite (offer carries a roomId, no SDP) is routed to the SFU ring path; every
                // other signal keeps flowing to the P2P engine. onSfuSignal is null until Faz B wires it.
                val roomId = d.getString("roomId")
                val sfuSink = onSfuSignal
                if (sfuSink != null && (roomId != null || isSfuCall(signal.optString("callId")))) {
                    if (type == "offer" && roomId != null) rememberSfuCall(signal.optString("callId"))
                    sfuSink(from, signal)
                } else {
                    InternetCallManager.onChannelSignal(peerContact(from), from, signal, sender)
                }
            }
        }
    }

    /** Places an outgoing call over this channel to [peerUid]. */
    fun startCall(peerUid: String) {
        Analytics.callStarted("authority_p2p")
        InternetCallManager.startCall(peerContact(peerUid), sender)
    }

    private fun peerContact(peerUid: String): Contact =
        Contact(
            name = peerNameResolver(peerUid),
            aesKey = "",
            sessionCode = "",
            peerUid = peerUid,
            // Non-blank so Contact.supportsInternet is true (the WebRTC engine's precondition); channel
            // calls carry no E2E envelope — the SDP rides the membership-gated callSignals in the clear,
            // exactly like the web.
            peerPublicKey = "channel"
        )

    companion object {
        private val VALID_TYPES = setOf("offer", "answer", "ice", "reject", "busy", "end", "cancel")

        // Longer than the 35s ring timeout with headroom for clock skew — see listen()'s freshness gate.
        private const val MAX_SIGNAL_AGE_MS = 45_000L

        // Global (cross-instance) set of SFU callIds — an offer carries a roomId, but the follow-up
        // answer/reject/end don't, and they may be caught by a different AuthorityCallSignaling instance
        // (the app-wide receiver vs the per-call sender). Keying on callId keeps them all on the SFU path.
        private val sfuCallIds = java.util.Collections.synchronizedSet(HashSet<String>())
        fun rememberSfuCall(callId: String) { if (callId.isNotBlank()) sfuCallIds.add(callId) }
        fun isSfuCall(callId: String): Boolean = callId.isNotBlank() && sfuCallIds.contains(callId)
    }
}
