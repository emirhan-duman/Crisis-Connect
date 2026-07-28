package com.auralis.crisisconnect.messaging

import android.util.Base64
import com.auralis.crisisconnect.security.Crypto
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/** The per-agency shared messaging key, fetched from issueAgencyMessagingKey. */
data class AgencyKey(
    val keyId: String,
    val key: ByteArray,
    val agencySlug: String
)

/** A decrypted agency-channel message. */
data class AgencyMessage(
    val id: String,
    val senderUid: String,
    val senderName: String,
    val text: String,
    val atMillis: Long
)

/**
 * AES-256-GCM under the agency's shared key, byte-for-byte compatible with the web dashboard's
 * `lib/messaging/agency.ts`: 12-byte nonce, ciphertext = ct||tag, associated data = the agencySlug.
 * Verified against a web-produced golden vector (AgencyMessageCryptoTest).
 */
object AgencyMessageCrypto {
    fun decrypt(agencyKey: AgencyKey, nonceBase64: String, ciphertextBase64: String): String {
        val plaintext = Crypto.aesGcmDecrypt(
            key = agencyKey.key,
            nonce = Base64.decode(nonceBase64, Base64.NO_WRAP),
            ciphertextAndTag = Base64.decode(ciphertextBase64, Base64.NO_WRAP),
            associatedData = agencyKey.agencySlug.toByteArray(StandardCharsets.UTF_8)
        )
        return String(plaintext, StandardCharsets.UTF_8)
    }

    /** Returns (nonceBase64, ciphertextBase64). */
    fun encrypt(agencyKey: AgencyKey, text: String): Pair<String, String> {
        val nonce = Crypto.randomBytes(12)
        val ciphertext = Crypto.aesGcmEncrypt(
            key = agencyKey.key,
            nonce = nonce,
            plaintext = text.toByteArray(StandardCharsets.UTF_8),
            associatedData = agencyKey.agencySlug.toByteArray(StandardCharsets.UTF_8)
        )
        return Base64.encodeToString(nonce, Base64.NO_WRAP) to
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
    }
}

/**
 * Field-team (Android) side of the dashboard's agency-internal messaging: fetches the agency key,
 * listens to the encrypted channel in Firestore and decrypts it in realtime (the server only ever
 * holds ciphertext), and can post to the same channel. Mirrors the web `lib/messaging/agency.ts`.
 */
class AgencyMessagingClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    suspend fun fetchAgencyKey(agencySlug: String): AgencyKey = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("issueAgencyMessagingKey")
            .call(hashMapOf("agencySlug" to agencySlug))
            .await()
        val data = result.data as? Map<*, *> ?: throw IllegalStateException("No agency key returned.")
        val keyBase64 = data["keyBase64"] as? String ?: throw IllegalStateException("Missing keyBase64.")
        AgencyKey(
            keyId = data["keyId"] as? String ?: "",
            key = Base64.decode(keyBase64, Base64.NO_WRAP),
            agencySlug = agencySlug
        )
    }

    private fun channel(agencySlug: String) =
        firestore.collection("agencyPanels").document(agencySlug).collection("secureMessages")

    /** Realtime subscription: decrypts each stored message and delivers the ordered list. */
    fun listen(agencyKey: AgencyKey, onMessages: (List<AgencyMessage>) -> Unit): ListenerRegistration =
        channel(agencyKey.agencySlug)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(MESSAGE_LIMIT)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                val messages = snapshot.documents.mapNotNull { doc ->
                    val nonce = doc.getString("nonce") ?: return@mapNotNull null
                    val ciphertext = doc.getString("ciphertext") ?: return@mapNotNull null
                    val text = runCatching {
                        AgencyMessageCrypto.decrypt(agencyKey, nonce, ciphertext)
                    }.getOrElse { "⚠️" }
                    AgencyMessage(
                        id = doc.id,
                        senderUid = doc.getString("senderUid").orEmpty(),
                        senderName = doc.getString("senderName").orEmpty(),
                        text = text,
                        atMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                    )
                }
                onMessages(messages)
            }

    /**
     * Latest message in the agency broadcast channel, decrypted on-device — the push notification's
     * real-text preview (the push itself is routing-only; the ciphertext lives in Firestore).
     */
    suspend fun latestMessage(agencySlug: String): AgencyMessage? = withContext(Dispatchers.IO) {
        val doc = channel(agencySlug)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .await()
            .documents
            .firstOrNull() ?: return@withContext null
        val nonce = doc.getString("nonce") ?: return@withContext null
        val ciphertext = doc.getString("ciphertext") ?: return@withContext null
        val key = runCatching { fetchAgencyKey(agencySlug) }.getOrNull() ?: return@withContext null
        val text = runCatching { AgencyMessageCrypto.decrypt(key, nonce, ciphertext) }
            .getOrNull() ?: return@withContext null
        AgencyMessage(
            id = doc.id,
            senderUid = doc.getString("senderUid").orEmpty(),
            senderName = doc.getString("senderName").orEmpty(),
            text = text,
            atMillis = doc.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
        )
    }

    suspend fun send(agencyKey: AgencyKey, senderName: String, text: String) = withContext(Dispatchers.IO) {
        val uid = auth.currentUser?.uid ?: throw IllegalStateException("Not signed in.")
        val (nonce, ciphertext) = AgencyMessageCrypto.encrypt(agencyKey, text)
        channel(agencyKey.agencySlug)
            .add(
                hashMapOf(
                    "senderUid" to uid,
                    "senderName" to senderName,
                    "keyId" to agencyKey.keyId,
                    "nonce" to nonce,
                    "ciphertext" to ciphertext,
                    "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            )
            .await()
        Unit
    }

    companion object {
        private const val REGION = "us-central1"
        private const val MESSAGE_LIMIT = 200L
    }
}
