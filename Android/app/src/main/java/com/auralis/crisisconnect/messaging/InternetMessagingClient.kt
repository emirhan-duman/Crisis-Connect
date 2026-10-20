package com.auralis.crisisconnect.messaging

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Public identity key resolved for a contact. */
data class PeerIdentityKey(
    val uid: String,
    val publicKeyBase64: String,
    val keyId: String,
    val alg: String,
    val displayName: String = "",
    val photoUrl: String = "",
    val isChild: Boolean = false
)

/** An address-book number that turned out to belong to a discoverable Crisis Connect user. */
data class DiscoveredContact(
    val phone: String,
    val uid: String,
    val publicKeyBase64: String,
    val keyId: String,
    val alg: String,
    val displayName: String = "",
    val photoUrl: String = "",
    val isChild: Boolean = false
)

/**
 * Android client for the messaging Cloud Functions and the message Firestore collection.
 *
 * Two send paths, one E2E envelope:
 *  - Normal mode writes the ciphertext straight to `messages/{id}` via the Firestore SDK
 *    (realtime, offline-queued, no function cold start).
 *  - Crisis mode calls [relayMessage] — a single compact request — so no realtime socket is
 *    held open; the server persists the doc and the FCM trigger delivers it.
 * Both converge on the same `onMessageCreated` push fan-out.
 */
class InternetMessagingClient(
    functions: FirebaseFunctions? = null,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    firestore: FirebaseFirestore? = null,
    private val regionalCalls: RegionalMessagingCalls? = if (functions == null) RegionalMessagingCalls() else null
) {
    private val firestore = firestore ?: if (functions == null && MessagingDatabaseTarget.HIGH_AVAILABILITY_ENABLED)
        FirebaseFirestore.getInstance(MessagingDatabaseTarget.DATABASE_ID) else FirebaseFirestore.getInstance()
    private val functions = functions ?: FirebaseFunctions.getInstance(REGION)
    val usesRegionalDelivery: Boolean get() = regionalCalls != null
    private suspend fun callRegional(operation: String, payload: Map<String, Any>): Any? {
        val uid = currentUid() ?: error("Not signed in.")
        return checkNotNull(regionalCalls).call(operation, payload + ("requestUid" to uid)) { currentUid() == uid }
    }
    private suspend fun callRouted(operation: String, payload: Map<String, Any> = emptyMap()): Any? =
        if (regionalCalls != null) callRegional(operation, payload)
        else functions.getHttpsCallable(operation).call(payload).await().data
    fun currentUid(): String? = auth.currentUser?.uid
    internal val messageStore: FirebaseFirestore get() = firestore

    suspend fun publishIdentityKey(
        publicKeyBase64: String,
        discoverable: Boolean,
        phone: String? = null,
        username: String? = null,
        displayName: String? = null,
        photoUrl: String? = null,
        isChild: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val payload = hashMapOf<String, Any>(
            "publicKey" to publicKeyBase64,
            "alg" to E2eEnvelope.ALG,
            "discoverable" to discoverable,
            "isChild" to isChild
        )
        if (discoverable) {
            phone?.takeIf { it.isNotBlank() }?.let { payload["phone"] = it }
            username?.takeIf { it.isNotBlank() }?.let { payload["username"] = it }
        }
        displayName?.takeIf { it.isNotBlank() }?.let { payload["displayName"] = it }
        photoUrl?.takeIf { it.isNotBlank() }?.let { payload["photoUrl"] = it }
        callRouted("publishIdentityKey", payload)
        Unit
    }

    suspend fun lookupIdentityKey(
        uid: String? = null,
        phone: String? = null,
        username: String? = null
    ): PeerIdentityKey = withContext(Dispatchers.IO) {
        val payload = hashMapOf<String, Any>()
        uid?.takeIf { it.isNotBlank() }?.let { payload["uid"] = it }
        phone?.takeIf { it.isNotBlank() }?.let { payload["phone"] = it }
        username?.takeIf { it.isNotBlank() }?.let { payload["username"] = it }
        val result = callRouted("lookupIdentityKey", payload)
        val data = result as? Map<*, *>
            ?: throw IllegalStateException("lookupIdentityKey returned no data.")
        PeerIdentityKey(
            uid = data["uid"] as? String ?: throw IllegalStateException("Missing uid."),
            publicKeyBase64 = data["publicKey"] as? String
                ?: throw IllegalStateException("Missing publicKey."),
            keyId = data["keyId"] as? String ?: "",
            alg = data["alg"] as? String ?: "",
            displayName = data["displayName"] as? String ?: "",
            photoUrl = data["photoUrl"] as? String ?: "",
            isChild = data["isChild"] as? Boolean ?: false
        )
    }

    /**
     * Batch contact discovery: hand the server a list of E.164 numbers from the address book and
     * get back only the ones that belong to discoverable Crisis Connect users. The server never
     * stores the queried numbers.
     */
    suspend fun findContacts(phones: List<String>): List<DiscoveredContact> =
        withContext(Dispatchers.IO) {
            if (phones.isEmpty()) return@withContext emptyList()
            val result = callRouted("findContactsOnCrisisConnect", mapOf("phones" to phones))
            val data = result as? Map<*, *> ?: return@withContext emptyList()
            val matches = data["matches"] as? List<*> ?: return@withContext emptyList()
            matches.mapNotNull { raw ->
                val m = raw as? Map<*, *> ?: return@mapNotNull null
                val phone = m["phone"] as? String ?: return@mapNotNull null
                val uid = m["uid"] as? String ?: return@mapNotNull null
                val publicKey = m["publicKey"] as? String ?: return@mapNotNull null
                DiscoveredContact(
                    phone = phone,
                    uid = uid,
                    publicKeyBase64 = publicKey,
                    keyId = m["keyId"] as? String ?: "",
                    alg = m["alg"] as? String ?: "",
                    displayName = m["displayName"] as? String ?: "",
                    photoUrl = m["photoUrl"] as? String ?: "",
                    isChild = m["isChild"] as? Boolean ?: false
                )
            }
        }

    suspend fun registerPushToken(token: String, platform: String = "android") =
        withContext(Dispatchers.IO) {
            callRouted("registerPushToken", mapOf("token" to token, "platform" to platform,
                    "installationId" to MessagingInstallation.id(), "trafficPolicyVersion" to 1))
            Unit
        }

    suspend fun unregisterPushToken(token: String) = withContext(Dispatchers.IO) {
        callRouted("unregisterPushToken", mapOf("token" to token))
        Unit
    }

    /** Content-free, authenticated wake; the backend derives the recipient from the MLS parent. */
    suspend fun requestAuthorityMlsPreparation(conversationId: String) = withContext(Dispatchers.IO) {
        if (ResilientApplicationStore.ENABLED) {
            callRouted("requestAuthorityMlsPreparation", mapOf("conversationId" to conversationId))
            return@withContext
        }
        functions.getHttpsCallable("requestAuthorityMlsPreparation")
            .call(hashMapOf("conversationId" to conversationId))
            .await()
        Unit
    }

    /**
     * Confirms a message was received and stored, so the server deletes its copy (WhatsApp-style).
     * Best-effort: failure just means the server's safety-net purge cleans it up later.
     */
    suspend fun acknowledgeMessage(messageId: String, expectedUid: String? = null) = withContext(Dispatchers.IO) {
        val uid = currentUid() ?: return@withContext
        if (expectedUid != null && uid != expectedUid) return@withContext
        // Persist first in every mode: failed normal-mode ACKs are retried by the sync worker.
        CompactInboxState.enqueue(uid, messageId)
        if (com.auralis.crisisconnect.connectivity.DisasterTrafficController.saving.value) return@withContext
        try {
            kotlinx.coroutines.withTimeout(if (regionalCalls != null) 20_000L else 12_000L) {
                if (regionalCalls != null) callRegional("acknowledgeMessage", mapOf("messageId" to messageId))
                else functions.getHttpsCallable("acknowledgeMessage").call(hashMapOf("messageId" to messageId)).await()
            }
            CompactInboxState.confirm(uid, listOf(messageId))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
        } catch (_: Exception) { /* Already queued; never lose a failed ACK. */ }
        Unit
    }

    data class CompactInboxPage(val messages: List<Map<String, Any>>, val cursor: String?)

    suspend fun compactInbox(cursor: String?, receive: Boolean = true): CompactInboxPage = withContext(Dispatchers.IO) {
        val uid = currentUid() ?: throw IllegalStateException("Not signed in.")
        val ids = CompactInboxState.pending(uid)
        val request = hashMapOf<String, Any>("acknowledgements" to ids)
        request["installationId"] = MessagingInstallation.id()
        request["policyVersion"] = 2
        request["profileRevision"] = CompactInboxState.nextRevision(uid)
        request["receive"] = receive
        request["saving"] = com.auralis.crisisconnect.connectivity.DisasterTrafficController.saving.value
        if (cursor != null) request["cursor"] = cursor
        val result = try {
            kotlinx.coroutines.withTimeout(20_000) {
                if (regionalCalls != null) callRegional("compactInbox", request)
                else functions.getHttpsCallable("compactInbox").call(request).await().data
            }
        } catch (error: Exception) {
            com.auralis.crisisconnect.connectivity.TrafficMeasurements.request(false)
            throw error
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        check(currentUid() == uid) { "Account changed during inbox sync." }
        val data = result as? Map<*, *> ?: error("Malformed compact inbox.")
        com.auralis.crisisconnect.connectivity.TrafficMeasurements.request(true, org.json.JSONObject(data).toString().toByteArray().size.toLong())
        com.auralis.crisisconnect.connectivity.DisasterTrafficController.applyServerPolicy(data["policy"] as? Map<*, *>)
        CompactInboxState.confirm(uid, ids)
        CompactInboxState.setProfile(uid, request["saving"] == true)
        @Suppress("UNCHECKED_CAST")
        CompactInboxPage((data["messages"] as? List<Map<String, Any>>).orEmpty(), data["cursor"] as? String)
    }

    // ---- Signal-protocol (v3) prekeys (FS-1 backend) ----

    /** One published prekey: {keyId, publicKey[, signature]}. Signature is null for one-time EC keys. */
    data class PreKeyUpload(val keyId: Int, val publicKeyBase64: String, val signatureBase64: String?)

    /** Local view of the server's prekey inventory, used to decide whether to top up. */
    data class SignalPreKeyInventory(val published: Boolean, val ecCount: Int, val kyberCount: Int)

    /** A PQXDH bundle fetched for a peer, ready to feed into a libsignal PreKeyBundle (FS-3). */
    data class SignalBundle(
        val registrationId: Int,
        val deviceId: Int,
        val identityKeyBase64: String,
        val signedPreKeyId: Int,
        val signedPreKeyBase64: String,
        val signedPreKeySignatureBase64: String,
        val preKeyId: Int?,
        val preKeyBase64: String?,
        val kyberPreKeyId: Int,
        val kyberPreKeyBase64: String,
        val kyberPreKeySignatureBase64: String,
    )

    private fun PreKeyUpload.toMap(): Map<String, Any> {
        val m = hashMapOf<String, Any>("keyId" to keyId, "publicKey" to publicKeyBase64)
        signatureBase64?.let { m["signature"] = it }
        return m
    }

    suspend fun publishSignalPreKeys(
        registrationId: Int,
        identityKeyBase64: String,
        signedPreKey: PreKeyUpload,
        lastResortKyberPreKey: PreKeyUpload,
        preKeys: List<PreKeyUpload>,
        kyberPreKeys: List<PreKeyUpload>,
    ) = withContext(Dispatchers.IO) {
        val payload = hashMapOf(
            "registrationId" to registrationId,
            "identityKey" to identityKeyBase64,
            "signedPreKey" to signedPreKey.toMap(),
            "lastResortKyberPreKey" to lastResortKyberPreKey.toMap(),
            "preKeys" to preKeys.map { it.toMap() },
            "kyberPreKeys" to kyberPreKeys.map { it.toMap() },
        )
        callRouted("publishSignalPreKeys", payload)
        Unit
    }

    suspend fun checkSignalPreKeys(): SignalPreKeyInventory = withContext(Dispatchers.IO) {
        val result = callRouted("checkSignalPreKeys")
        val data = result as? Map<*, *> ?: emptyMap<Any, Any>()
        SignalPreKeyInventory(
            published = data["published"] as? Boolean ?: false,
            ecCount = (data["ecCount"] as? Number)?.toInt() ?: 0,
            kyberCount = (data["kyberCount"] as? Number)?.toInt() ?: 0,
        )
    }

    /** Returns null when the target has never published Signal prekeys (caller falls back to v2). */
    suspend fun fetchSignalPreKeyBundle(targetUid: String): SignalBundle? = withContext(Dispatchers.IO) {
        val result = runCatching {
            callRouted("fetchSignalPreKeyBundle", mapOf("targetUid" to targetUid))
        }.getOrElse { error ->
            val code = (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code
            if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND) {
                return@withContext null
            }
            throw error
        }
        val data = result as? Map<*, *>
            ?: throw IllegalStateException("fetchSignalPreKeyBundle returned no data.")
        val signed = data["signedPreKey"] as? Map<*, *>
            ?: throw IllegalStateException("Missing signedPreKey.")
        val kyber = data["kyberPreKey"] as? Map<*, *>
            ?: throw IllegalStateException("Missing kyberPreKey.")
        val preKey = data["preKey"] as? Map<*, *>
        SignalBundle(
            registrationId = (data["registrationId"] as? Number)?.toInt()
                ?: throw IllegalStateException("Missing registrationId."),
            deviceId = (data["deviceId"] as? Number)?.toInt() ?: 1,
            identityKeyBase64 = data["identityKey"] as? String
                ?: throw IllegalStateException("Missing identityKey."),
            signedPreKeyId = (signed["keyId"] as? Number)?.toInt()
                ?: throw IllegalStateException("Missing signedPreKey.keyId."),
            signedPreKeyBase64 = signed["publicKey"] as? String
                ?: throw IllegalStateException("Missing signedPreKey.publicKey."),
            signedPreKeySignatureBase64 = signed["signature"] as? String
                ?: throw IllegalStateException("Missing signedPreKey.signature."),
            preKeyId = (preKey?.get("keyId") as? Number)?.toInt(),
            preKeyBase64 = preKey?.get("publicKey") as? String,
            kyberPreKeyId = (kyber["keyId"] as? Number)?.toInt()
                ?: throw IllegalStateException("Missing kyberPreKey.keyId."),
            kyberPreKeyBase64 = kyber["publicKey"] as? String
                ?: throw IllegalStateException("Missing kyberPreKey.publicKey."),
            kyberPreKeySignatureBase64 = kyber["signature"] as? String
                ?: throw IllegalStateException("Missing kyberPreKey.signature."),
        )
    }

    /** Crisis-mode compact uplink. Returns the accepted messageId. */
    suspend fun relayMessage(envelope: RelayableEnvelope): String = withContext(Dispatchers.IO) {
        if (regionalCalls != null) {
            callRegional("relayMessage", envelope.toMap() + ("trafficPolicyVersion" to 2))
            return@withContext envelope.messageId
        }
        functions.getHttpsCallable("relayMessage").apply { setTimeout(20, java.util.concurrent.TimeUnit.SECONDS) }
            .call(envelope.toMap() + ("trafficPolicyVersion" to 2)).await()
        envelope.messageId
    }

    /** Normal-mode direct Firestore write of the same ciphertext envelope. */
    suspend fun writeMessageDoc(envelope: RelayableEnvelope) = withContext(Dispatchers.IO) {
        if (regionalCalls != null) {
            // New clients use the same durable receipt in normal mode too: no late offline
            // Firestore write can recreate a message after regional delivery and ACK.
            callRegional("relayMessage", envelope.toMap() + ("trafficPolicyVersion" to 1))
            return@withContext
        }
        val uid = currentUid() ?: throw IllegalStateException("Not signed in.")
        // expireAt mirrors what the relay stamps server-side; without it a never-acked direct-write
        // doc is invisible to the purgeExpiredMessages sweep and replays on every listener attach.
        val expireAt = com.google.firebase.Timestamp(
            java.util.Date(envelope.createdAtMs + envelope.ttlMs)
        )
        kotlinx.coroutines.withTimeout(12_000) {
            firestore.collection("messages")
                .document(envelope.messageId)
                .set(envelope.toMap() + mapOf("senderUid" to uid, "expireAt" to expireAt))
                .await()
        }
        Unit
    }

    companion object {
        private const val REGION = "us-central1"
    }
}

/** A message envelope the relay/direct-write paths can persist. Both crypto layers implement it. */
interface RelayableEnvelope {
    val messageId: String
    val createdAtMs: Long
    val ttlMs: Long
    fun toMap(): Map<String, Any>

    /**
     * A copy safe for the relayMessage callable, whose id regex rejects '~' (used by the
     * store-and-forward "uuid~createdAt" ids). Direct Firestore writes accept '~'.
     */
    fun sanitizedForCallable(): RelayableEnvelope
}

/**
 * v1 (ECIES) envelope: routing fields + the opaque sealed content. `senderUid` is added server-side
 * (relay) or from the auth session (direct write) so it can never be forged.
 */
data class OutgoingEnvelope(
    override val messageId: String,
    val conversationId: String,
    val recipientUid: String,
    val priority: String, // "normal" | "high"
    override val createdAtMs: Long,
    override val ttlMs: Long,
    val sealed: SealedMessage,
    // Routing-only marker (no plaintext): true on a call OFFER so the backend fires an iOS
    // PushKit VoIP ring for a force-quit recipient. See onMessageCreated.
    val callRing: Boolean = false
) : RelayableEnvelope {
    override fun toMap(): Map<String, Any> = buildMap {
        put("v", 1)
        put("messageId", messageId)
        put("conversationId", conversationId)
        put("recipientUid", recipientUid)
        put("priority", priority)
        put("createdAtMs", createdAtMs)
        put("ttlMs", ttlMs)
        put("alg", sealed.alg)
        put("ephemeralPubKey", sealed.ephemeralPublicKey)
        put("nonce", sealed.nonce)
        put("ciphertext", sealed.ciphertext)
        if (callRing) put("callRing", true)
    }

    override fun sanitizedForCallable(): RelayableEnvelope =
        copy(messageId = messageId.replace('~', ':'))
}

/**
 * v2 (Signal) envelope: same routing fields, but the content is a Double-Ratchet ciphertext tagged
 * with [ctype] ("prekey" for the first/handshake message, "signal" afterwards). No ECDH fields.
 */
data class OutgoingSignalEnvelope(
    override val messageId: String,
    val conversationId: String,
    val recipientUid: String,
    val priority: String,
    override val createdAtMs: Long,
    override val ttlMs: Long,
    val ctype: String,
    val ciphertextBase64: String,
    // See OutgoingEnvelope.callRing.
    val callRing: Boolean = false
) : RelayableEnvelope {
    override fun toMap(): Map<String, Any> = buildMap {
        put("v", 2)
        put("messageId", messageId)
        put("conversationId", conversationId)
        put("recipientUid", recipientUid)
        put("priority", priority)
        put("createdAtMs", createdAtMs)
        put("ttlMs", ttlMs)
        put("ctype", ctype)
        put("ciphertext", ciphertextBase64)
        if (callRing) put("callRing", true)
    }

    override fun sanitizedForCallable(): RelayableEnvelope =
        copy(messageId = messageId.replace('~', ':'))
}
