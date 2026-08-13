package com.auralis.crisisconnect.messaging.call

import com.auralis.crisisconnect.data.Contact
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.util.Date

/**
 * Web-compatible call signalling over an authority channel's Firestore `callSignals` subcollection
 * (`agencyPanels/{slug}/callSignals` or `hierarchyChannels/{id}/callSignals`). This is the transport
 * the web dashboard's CallManager uses (lib/messaging/call-signals.ts): one doc per signal, addressed
 * `to` a uid. Only the bound SFU-v2 ring schema is accepted; legacy SDP/ICE authority signals are
 * rejected on write and read. Reusing it lets an
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

    /** Verified SFU-v2 sink. If absent, authority signals are dropped fail-closed. */
    var onSfuSignal: ((fromUid: String, signal: JSONObject) -> Unit)? = null

    /** The transport override handed to InternetCallManager — writes each signal as a web callSignals doc. */
    val sender = InternetCallManager.SignalSender { contact, signalJson ->
        runCatching {
            val s = JSONObject(signalJson)
            val type = s.optString("type").ifBlank { s.optString("kind") }
            if (type !in VALID_TYPES) return@SignalSender false
            val callId = s.optString("callId")
            if (!UUID_PATTERN.matches(callId) || contact.peerUid.isBlank() || contact.peerUid == myUid ||
                listOf("sdp", "candidate", "sdpMid", "sdpMLineIndex").any(s::has)
            ) return@SignalSender false
            val roomId = s.optString("roomId")
            when (type) {
                "offer" -> if (!UUID_PATTERN.matches(roomId) || s.optInt("sfuVersion", 0) != 2 ||
                    !s.has("video") || s.opt("video") !is Boolean
                ) return@SignalSender false
                "answer" -> if (roomId.isNotBlank() || s.optInt("sfuVersion", 0) != 2) {
                    return@SignalSender false
                }
                else -> if (roomId.isNotBlank() || s.has("video") || s.has("sfuVersion")) {
                    return@SignalSender false
                }
            }
            val doc = hashMapOf<String, Any>(
                "signalVersion" to 2,
                "scopeType" to if (kind == ChannelKind.AGENCY) "agency" else "hierarchy",
                "channelId" to channelId,
                "from" to myUid,
                "to" to contact.peerUid,
                "callId" to callId,
                "type" to type,
                "createdAt" to FieldValue.serverTimestamp(),
                "expireAt" to Date(System.currentTimeMillis() + SIGNAL_RETENTION_MS)
            )
            if (type == "offer") {
                doc["roomId"] = roomId
                doc["video"] = s.getBoolean("video")
                doc["sfuVersion"] = 2
            } else if (type == "answer") {
                doc["sfuVersion"] = 2
            }
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
                val createdAt = change.document.getTimestamp("createdAt") ?: continue
                val expireAt = change.document.getTimestamp("expireAt") ?: continue
                val retentionMs = expireAt.toDate().time - createdAt.toDate().time
                if (retentionMs <= 0 || retentionMs > MAX_SIGNAL_RETENTION_MS) continue
                val ageMs = System.currentTimeMillis() - createdAt.toDate().time
                if (ageMs > MAX_SIGNAL_AGE_MS) continue
                val d = change.document
                if (d.getLong("signalVersion") != 2L ||
                    d.getString("scopeType") != (if (kind == ChannelKind.AGENCY) "agency" else "hierarchy") ||
                    d.getString("channelId") != channelId
                ) continue
                val type = d.getString("type") ?: continue
                if (type !in VALID_TYPES) continue
                val from = d.getString("from").orEmpty().takeIf { it.isNotBlank() && it != myUid } ?: continue
                val callId = d.getString("callId").orEmpty()
                if (!UUID_PATTERN.matches(callId)) continue
                val baseKeys = setOf(
                    "signalVersion", "scopeType", "channelId", "from", "to", "callId", "type",
                    "createdAt", "expireAt"
                )
                val signal = JSONObject().put("type", type).put("callId", callId)
                if (type == "offer") {
                    val roomId = d.getString("roomId").orEmpty()
                    if (!UUID_PATTERN.matches(roomId) || d.getLong("sfuVersion") != 2L ||
                        d.getBoolean("video") == null || d.data.keys != baseKeys + setOf("roomId", "video", "sfuVersion")
                    ) continue
                    signal.put("roomId", roomId)
                    signal.put("video", d.getBoolean("video"))
                    signal.put("sfuVersion", 2)
                } else if (type == "answer") {
                    if (d.getLong("sfuVersion") != 2L || d.data.keys != baseKeys + "sfuVersion") continue
                    signal.put("sfuVersion", 2)
                } else if (d.data.keys != baseKeys) {
                    continue
                }
                // Authority signaling is SFU-v2 only. If the verified SFU receiver is not attached,
                // fail closed instead of falling through to the legacy P2P engine.
                val sfuSink = onSfuSignal
                if (sfuSink != null) {
                    sfuSink(from, signal)
                }
            }
        }
    }

    /** Places an outgoing call over this channel to [peerUid]. */
    fun startCall(peerUid: String) {
        require(peerUid.isNotBlank()) { "Authority call peer is required." }
        throw SecurityException("Legacy authority P2P calls are disabled; use secure SFU v2.")
    }

    /** Sends an SFU control response without exposing the Firestore collection to the call gate. */
    suspend fun sendSfuSignal(peerUid: String, signal: JSONObject): Boolean =
        sender.send(peerContact(peerUid), signal.toString())

    private fun peerContact(peerUid: String): Contact =
        Contact(
            name = peerNameResolver(peerUid),
            aesKey = "",
            sessionCode = "",
            peerUid = peerUid,
            // The SignalSender interface still requires a Contact, but no legacy SDP/ICE is accepted.
            peerPublicKey = "channel"
        )

    companion object {
        private val VALID_TYPES = setOf("offer", "answer", "reject", "busy", "end", "cancel")
        private val UUID_PATTERN = Regex(
            "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
        )

        // Longer than the 35s ring timeout with headroom for clock skew — see listen()'s freshness gate.
        private const val MAX_SIGNAL_AGE_MS = 45_000L
        private const val SIGNAL_RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val MAX_SIGNAL_RETENTION_MS = 25 * 60 * 60 * 1000L

    }
}
