package com.auralis.crisisconnect.messaging

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.messaging.signal.SignalSessionGate
import java.util.UUID
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends chat messages over the E2E internet transport. This is the third drain of the chat
 * store-and-forward queue (alongside RFCOMM and BLE/GATT): given a contact whose identity key
 * we already hold (from a QR scan or a directory lookup), it seals the text and hands the
 * ciphertext to the relay. The conversation's local `sessionCode` doubles as the cross-device
 * `conversationId`, so a chat started over Bluetooth continues seamlessly over the internet.
 */
class InternetChatTransport(
    private val context: Context,
    private val client: InternetMessagingClient = InternetMessagingClient()
) {
    // The forward-secret (v3) session decider. Lazy so BT-only builds never touch libsignal.
    private val gate by lazy { SignalSessionGate.create(context, client) }

    /**
     * Seals [content] to [contact] and hands it to the relay, choosing the forward-secret v3
     * (Signal) transport when a session is (or can be) established with the peer, and falling back
     * to the v1 envelope otherwise. Consistency of this choice IS the anti-downgrade pin: once a v3
     * session exists ([SignalSessionGate.ensureOutbound] returns true) every message to that peer
     * goes v3, so the receiver can safely reject a v2 message from a v3 peer.
     *
     * [forceEstablish] false (ephemeral pulses like typing) uses an existing session but never
     * fetches a bundle to create one. [directWriteOnly] skips the callable fallback (pulses aren't
     * worth a Cloud Function cold start).
     */
    private suspend fun sealAndRelay(
        contact: Contact,
        content: MessageContent,
        messageId: String,
        createdAtMs: Long,
        priority: String,
        ttlMs: Long,
        forceEstablish: Boolean = true,
        directWriteOnly: Boolean = false,
        // Stamped on the wire doc (not the ciphertext) so the backend can fire an iOS PushKit
        // VoIP ring for this message — set only for a call OFFER.
        callRing: Boolean = false
        // The Signal gate hits libsignal's blocking Room store — hop off the caller's dispatcher
        // here so every send entry point (some launch on Main) is safe.
    ) = withContext(Dispatchers.IO) {
        val myUid = client.currentUid() ?: throw IllegalStateException("Not signed in.")
        val peerUid = contact.peerUid.trim()
        val conversationId = InternetConversation.pairId(myUid, peerUid)
        val envelope: RelayableEnvelope =
            if (peerUid.isNotBlank() && gate.ensureOutbound(peerUid, forceEstablish)) {
                val wire = gate.encrypt(peerUid, content)
                OutgoingSignalEnvelope(
                    messageId = messageId,
                    conversationId = conversationId,
                    recipientUid = peerUid,
                    priority = priority,
                    createdAtMs = createdAtMs,
                    ttlMs = ttlMs,
                    ctype = gate.typeLabel(wire.type),
                    ciphertextBase64 = wire.ciphertextBase64,
                    callRing = callRing
                )
            } else {
                val recipientPublicKey = MessagingKeyCodec.decodePublicKey(contact.peerPublicKey)
                val identity = MessagingIdentity(context)
                val sealed = E2eEnvelope.seal(
                    content = content,
                    recipientPublicKey = recipientPublicKey,
                    deriveSenderStaticSecret = { pub -> identity.deriveSharedSecret(pub) },
                    senderUid = myUid,
                    recipientUid = peerUid,
                    conversationId = conversationId
                )
                OutgoingEnvelope(
                    messageId = messageId,
                    conversationId = conversationId,
                    recipientUid = peerUid,
                    priority = priority,
                    createdAtMs = createdAtMs,
                    ttlMs = ttlMs,
                    sealed = sealed,
                    callRing = callRing
                )
            }
        // Content-free breadcrumb (survives release minification via Log.i) so FS-5 can confirm the
        // chosen transport per message without exposing anything sensitive.
        Log.i(
            TAG,
            "internet send template=${content.templateCode} via " +
                if (envelope is OutgoingSignalEnvelope) "v3(${envelope.ctype})" else "v2"
        )
        if (directWriteOnly) client.writeMessageDoc(envelope) else relayEnvelope(envelope)
    }

    /** True when signed in AND there is an internet-capable network. */
    fun isAvailable(): Boolean {
        val uid = client.currentUid()
        if (uid == null) {
            Log.d(TAG, "isAvailable=false: no signed-in uid (anonymous session not ready)")
            return false
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: run {
            Log.d(TAG, "isAvailable=false: no active network")
            return false
        }
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        // VALIDATED is preferred but NOT required: some networks never report it even when
        // connectivity works (captive-portal probe blocked / old devices). We'd rather attempt the
        // relay — which queues and retries on failure — than dead-end the chat.
        Log.d(
            TAG,
            "isAvailable: hasInternet=$hasInternet validated=" +
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        )
        return hasInternet
    }

    /**
     * Seals [text] to [contact] and relays it. Returns true on accepted delivery to the server
     * (the FCM trigger fans it out to the recipient's devices).
     */
    suspend fun sendText(
        messageId: String,
        text: String,
        createdAtMs: Long,
        contact: Contact,
        priority: String = PRIORITY_NORMAL,
        templateCode: Int = TEMPLATE_FREE_TEXT
    ): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        return runCatching {
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = templateCode, text = text),
                messageId = messageId,
                createdAtMs = createdAtMs,
                priority = priority,
                ttlMs = DEFAULT_TTL_MS
            )
            true
        }.getOrElse {
            Log.w(TAG, "Internet send failed for $messageId", it)
            false
        }
    }

    /**
     * Delivers one sealed envelope. Fast path: direct Firestore write — the "normal mode" path the
     * security rules were written for. The relayMessage callable adds a Cloud Function round-trip
     * (cold start = seconds of latency) AND rejects the store-and-forward uuid format
     * ("uuid~createdAt": '~' fails its id regex), which silently broke internet text sends. The
     * callable stays as the fallback, with the id sanitized to its stricter charset.
     */
    private suspend fun relayEnvelope(envelope: RelayableEnvelope) {
        runCatching { client.writeMessageDoc(envelope) }
            .recoverCatching {
                client.relayMessage(envelope.sanitizedForCallable())
                Unit
            }
            .getOrThrow()
    }

    /**
     * Seals and relays a binary attachment (voice clip / image / file) to [contact]. Media larger
     * than [CHUNK_SIZE] is split across several sealed relay messages that share [transferId]; each
     * chunk stays under the 24 KB ciphertext cap. The recipient reassembles by transfer id. Returns
     * true only when every chunk was accepted by the relay.
     */
    suspend fun sendAttachment(
        transferId: String,
        kind: Int,
        mime: String,
        name: String,
        durationMs: Int,
        data: ByteArray,
        createdAtMs: Long,
        contact: Contact,
        priority: String = PRIORITY_NORMAL
    ): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        if (data.isEmpty()) return false
        return runCatching {
            val totalSize = data.size
            val chunkCount = (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE
            for (index in 0 until chunkCount) {
                val start = index * CHUNK_SIZE
                val end = minOf(start + CHUNK_SIZE, totalSize)
                val chunkBytes = data.copyOfRange(start, end)
                sealAndRelay(
                    contact = contact,
                    content = MessageContent(
                        templateCode = 0,
                        text = "",
                        attachment = MessageAttachment(
                            kind = kind,
                            mime = mime,
                            name = name,
                            transferId = transferId,
                            chunkIndex = index,
                            chunkCount = chunkCount,
                            totalSize = totalSize,
                            durationMs = durationMs,
                            bytes = chunkBytes
                        )
                    ),
                    // Unique per chunk (server dedups on messageId); the receiver groups by transferId.
                    messageId = "$transferId.$index",
                    createdAtMs = createdAtMs,
                    priority = priority,
                    ttlMs = DEFAULT_TTL_MS
                )
            }
            true
        }.getOrElse {
            Log.w(TAG, "Internet attachment send failed for $transferId", it)
            false
        }
    }

    /**
     * Seals and relays a WebRTC call signal (SDP offer/answer, ICE candidate, ring/accept/reject/end)
     * as an ordinary E2E message tagged with [CALL_SIGNAL_TEMPLATE]. High priority + short TTL — call
     * setup is real-time and worthless once stale. The receiver routes these to the call manager
     * instead of the chat thread. Returns true on relay acceptance.
     */
    suspend fun sendCallSignal(contact: Contact, signalJson: String): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        // Only a fresh OFFER should wake a force-quit peer (an iOS PushKit VoIP ring). "restart-offer"
        // is mid-call renegotiation — the app is already running — so it must NOT ring.
        val isOffer = runCatching {
            JSONObject(signalJson).let { it.optString("type").ifBlank { it.optString("kind") } } == "offer"
        }.getOrDefault(false)
        return runCatching {
            // The offer signal IS the ring — the direct write inside relayEnvelope keeps it fast
            // (no Cloud Function cold start); the recipient's realtime listener picks the doc up
            // immediately and the onMessageCreated FCM trigger fires on creation either way.
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = CALL_SIGNAL_TEMPLATE, text = signalJson),
                messageId = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                priority = PRIORITY_HIGH,
                ttlMs = CALL_SIGNAL_TTL_MS,
                callRing = isOffer
            )
            true
        }.getOrElse {
            Log.w(TAG, "Call signal send failed", it)
            false
        }
    }

    /**
     * Sends an E2E delivery/read receipt ([DELIVERY_RECEIPT_TEMPLATE] / [READ_RECEIPT_TEMPLATE])
     * referencing the peer's message ids as JSON {"ids":[...]}. Receipts ride the same durable relay
     * path as chat messages (direct write + callable fallback, 24h TTL) so a briefly-offline sender
     * still gets the status bump when it reconnects. Idempotent on the receiving side.
     */
    suspend fun sendReceipt(
        contact: Contact,
        templateCode: Int,
        messageIds: Collection<String>
    ): Boolean {
        val myUid = client.currentUid() ?: return false
        if (!contact.supportsInternet) return false
        val ids = messageIds.mapNotNull { id -> id.trim().takeIf { it.isNotEmpty() } }.distinct()
        if (ids.isEmpty()) return true
        return runCatching {
            val payload = org.json.JSONObject().put("ids", org.json.JSONArray(ids)).toString()
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = templateCode, text = payload),
                messageId = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                priority = PRIORITY_NORMAL,
                ttlMs = DEFAULT_TTL_MS
            )
            true
        }.getOrElse {
            Log.w(TAG, "Receipt send failed", it)
            false
        }
    }

    /**
     * Asks [contact] to become this (child) device's parent ([PARENT_REQUEST_TEMPLATE]). Rides the
     * durable relay path (24h TTL) so a briefly-offline parent still gets it; the receiving side
     * answers via [sendParentResponse] and the contact only becomes a confirmed parent on accept.
     */
    suspend fun sendParentRequest(contact: Contact): Boolean =
        sendParentSignal(contact, PARENT_REQUEST_TEMPLATE, "request")

    /**
     * Announces our internet identity to a freshly QR-paired [contact] so their side heals its
     * BLE-only reciprocal contact (see [IDENTITY_ANNOUNCE_TEMPLATE]). [mySessionCode] is our OWN
     * P2P session code — the value the peer stored as `remoteSessionCode` when we paired — so they
     * can match this to the right contact. Durable 24h TTL so a briefly-offline peer still heals.
     */
    suspend fun sendIdentityAnnounce(contact: Contact, mySessionCode: String, myName: String?): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        return runCatching {
            // Carry our OWN v2 messaging public key in the (authenticated, E2E) payload so the peer
            // heals its contact WITHOUT a directory lookup of our uid — the lookup can lag right after
            // a fresh install, and a v3 announce leaves the receiver's senderKey empty otherwise.
            val myPublicKey = runCatching { MessagingIdentity(context).publicKeyBase64() }.getOrNull()
            val payload = org.json.JSONObject()
                .put("rsc", mySessionCode)
                .apply {
                    myName?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                    myPublicKey?.takeIf { it.isNotBlank() }?.let { put("pk", it) }
                }
                .toString()
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = IDENTITY_ANNOUNCE_TEMPLATE, text = payload),
                messageId = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                priority = PRIORITY_NORMAL,
                ttlMs = DEFAULT_TTL_MS
            )
            true
        }.getOrElse {
            Log.w(TAG, "Identity announce send failed", it)
            false
        }
    }

    /** Answers a parent request ([PARENT_RESPONSE_TEMPLATE]) with accept/decline. */
    suspend fun sendParentResponse(contact: Contact, accepted: Boolean): Boolean =
        sendParentSignal(
            contact,
            PARENT_RESPONSE_TEMPLATE,
            if (accepted) "accept" else "decline"
        )

    private suspend fun sendParentSignal(
        contact: Contact,
        templateCode: Int,
        action: String
    ): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        return runCatching {
            val payload = org.json.JSONObject().put("action", action).toString()
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = templateCode, text = payload),
                messageId = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                priority = PRIORITY_NORMAL,
                ttlMs = DEFAULT_TTL_MS
            )
            true
        }.getOrElse {
            Log.w(TAG, "Parent signal send failed", it)
            false
        }
    }

    /**
     * Fire-and-forget "peer is typing" pulse (templateCode [TYPING_SIGNAL_TEMPLATE]). Sealed E2E like
     * any message, but: direct Firestore write ONLY (an ephemeral pulse isn't worth a Cloud Function
     * fallback), NORMAL priority (must never break the peer's device out of Doze), and a short TTL.
     * The receiver routes it to [TypingIndicatorBus] — never stored, never notified.
     */
    suspend fun sendTypingSignal(contact: Contact): Boolean {
        if (client.currentUid() == null) return false
        if (!contact.supportsInternet) return false
        return runCatching {
            // Ephemeral: use an existing v3 session if there is one, but never fetch a bundle just
            // to send a typing pulse (forceEstablish=false); direct write only, no callable fallback.
            sealAndRelay(
                contact = contact,
                content = MessageContent(templateCode = TYPING_SIGNAL_TEMPLATE, text = "1"),
                messageId = UUID.randomUUID().toString(),
                createdAtMs = System.currentTimeMillis(),
                priority = PRIORITY_NORMAL,
                ttlMs = TYPING_SIGNAL_TTL_MS,
                forceEstablish = false,
                directWriteOnly = true
            )
            true
        }.getOrElse { false }
    }

    companion object {
        private const val TAG = "InternetChatTransport"
        private const val TEMPLATE_FREE_TEXT = 0
        // Reserved templateCode marking a message as a WebRTC call signal (never a chat message).
        const val CALL_SIGNAL_TEMPLATE = 200
        // Reserved templateCode for the ephemeral "peer is typing" pulse (never a chat message).
        const val TYPING_SIGNAL_TEMPLATE = 201
        // Reserved templateCode marking an SOS emergency alert. The text still carries the full
        // human-readable message, so an older client that ignores the code degrades to plain text.
        const val SOS_ALERT_TEMPLATE = 202
        // Reserved templateCode for a live-location update to an earlier SOS alert. The text is
        // JSON {"ref": <alert messageId>, "text": <replacement body>} — the receiver rewrites the
        // referenced SOS bubble in place instead of filing a new message.
        const val SOS_LOCATION_UPDATE_TEMPLATE = 203
        // Reserved templateCodes for E2E delivery/read receipts. The text is JSON {"ids":[...]}
        // referencing the peer's local message uuids; routed to receipt handling on arrival —
        // never stored as a chat message, never notified.
        const val DELIVERY_RECEIPT_TEMPLATE = 204
        const val READ_RECEIPT_TEMPLATE = 205

        /** Child device asks this contact to become its parent; payload {"action":"request"}. */
        const val PARENT_REQUEST_TEMPLATE = 206

        /** The asked contact's answer; payload {"action":"accept"} or {"action":"decline"}. */
        const val PARENT_RESPONSE_TEMPLATE = 207

        /**
         * Reciprocal identity announce after a QR pairing: the SCANNER (who got the displayer's uid
         * from the QR) tells the displayer its OWN internet identity over the relay, so the displayer
         * heals its BLE-only paired contact into an internet-capable one — WITHOUT depending on the
         * MTU-limited BLE client-hello (which drops the identity on a small Android↔iOS MTU). Payload
         * {"rsc":"<scanner's own sessionCode, stored as remoteSessionCode on the displayer's side>",
         * "name":"<scanner name>"}. The senderUid + sender key on the envelope ARE the identity.
         */
        const val IDENTITY_ANNOUNCE_TEMPLATE = 208
        private const val CALL_SIGNAL_TTL_MS = 60L * 1000L
        private const val TYPING_SIGNAL_TTL_MS = 15L * 1000L
        private const val DEFAULT_TTL_MS = 24L * 60L * 60L * 1000L
        // Max raw bytes per chunk. base64(ciphertext+tag+header) must stay under the 24 KB rule cap;
        // ~12 KB raw → ~16 KB base64, comfortably below 24000.
        private const val CHUNK_SIZE = 12_000
        const val PRIORITY_NORMAL = "normal"
        const val PRIORITY_HIGH = "high"
    }
}
