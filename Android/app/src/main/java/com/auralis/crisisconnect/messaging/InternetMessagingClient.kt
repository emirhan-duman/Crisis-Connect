package com.auralis.crisisconnect.messaging

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
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
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun currentUid(): String? = auth.currentUser?.uid

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
        functions.getHttpsCallable("publishIdentityKey").call(payload).await()
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
        val result = functions.getHttpsCallable("lookupIdentityKey").call(payload).await()
        val data = result.data as? Map<*, *>
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
            val result = functions.getHttpsCallable("findContactsOnCrisisConnect")
                .call(hashMapOf("phones" to phones))
                .await()
            val data = result.data as? Map<*, *> ?: return@withContext emptyList()
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
            functions.getHttpsCallable("registerPushToken")
                .call(hashMapOf("token" to token, "platform" to platform))
                .await()
            Unit
        }

    suspend fun unregisterPushToken(token: String) = withContext(Dispatchers.IO) {
        functions.getHttpsCallable("unregisterPushToken")
            .call(hashMapOf("token" to token))
            .await()
        Unit
    }

    /** Content-free, authenticated wake; the backend derives the recipient from the MLS parent. */
    suspend fun requestAuthorityMlsPreparation(conversationId: String) = withContext(Dispatchers.IO) {
        functions.getHttpsCallable("requestAuthorityMlsPreparation")
            .call(hashMapOf("conversationId" to conversationId))
            .await()
        Unit
    }

    /**
     * Confirms a message was received and stored, so the server deletes its copy (WhatsApp-style).
     * Best-effort: failure just means the server's safety-net purge cleans it up later.
     */
    suspend fun acknowledgeMessage(messageId: String) = withContext(Dispatchers.IO) {
        runCatching {
            functions.getHttpsCallable("acknowledgeMessage")
                .call(hashMapOf("messageId" to messageId))
                .await()
        }
        Unit
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
        functions.getHttpsCallable("publishSignalPreKeys").call(payload).await()
        Unit
    }

    suspend fun checkSignalPreKeys(): SignalPreKeyInventory = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("checkSignalPreKeys").call().await()
        val data = result.data as? Map<*, *> ?: emptyMap<Any, Any>()
        SignalPreKeyInventory(
            published = data["published"] as? Boolean ?: false,
            ecCount = (data["ecCount"] as? Number)?.toInt() ?: 0,
            kyberCount = (data["kyberCount"] as? Number)?.toInt() ?: 0,
        )
    }

    /** Returns null when the target has never published Signal prekeys (caller falls back to v2). */
    suspend fun fetchSignalPreKeyBundle(targetUid: String): SignalBundle? = withContext(Dispatchers.IO) {
        val result = runCatching {
            functions.getHttpsCallable("fetchSignalPreKeyBundle")
                .call(hashMapOf("targetUid" to targetUid))
                .await()
        }.getOrElse { error ->
            val code = (error as? com.google.firebase.functions.FirebaseFunctionsException)?.code
            if (code == com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND) {
                return@withContext null
            }
            throw error
        }
        val data = result.data as? Map<*, *>
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
        functions.getHttpsCallable("relayMessage").call(envelope.toMap()).await()
        envelope.messageId
    }

    /** Normal-mode direct Firestore write of the same ciphertext envelope. */
    suspend fun writeMessageDoc(envelope: RelayableEnvelope) = withContext(Dispatchers.IO) {
        val uid = currentUid() ?: throw IllegalStateException("Not signed in.")
        // expireAt mirrors what the relay stamps server-side; without it a never-acked direct-write
        // doc is invisible to the purgeExpiredMessages sweep and replays on every listener attach.
        val expireAt = com.google.firebase.Timestamp(
            java.util.Date(envelope.createdAtMs + envelope.ttlMs)
        )
        firestore.collection("messages")
            .document(envelope.messageId)
            .set(envelope.toMap() + mapOf("senderUid" to uid, "expireAt" to expireAt))
            .await()
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
