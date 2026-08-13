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

/** Retired per-agency shared messaging key shape, kept only for source compatibility. */
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
 * Retired shared-key agency messaging client. Key fetches and writes fail locally; Firestore Rules
 * also deny the old history. AuthorityChat MLS v2 owns active mobile messaging.
 */
class AgencyMessagingClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    /** Retired: server-issued shared keys violate the AuthorityChat MLS-only invariant. */
    suspend fun fetchAgencyKey(@Suppress("UNUSED_PARAMETER") agencySlug: String): AgencyKey {
        throw SecurityException(
            "Legacy shared-key agency messaging is permanently disabled; use AuthorityChat MLS v2."
        )
    }

    private fun channel(agencySlug: String) =
        firestore.collection("agencyPanels").document(agencySlug).collection("secureMessages")

    /**
     * Realtime subscription: decrypts each stored message and delivers the ordered list.
     *
     * DESCENDING + limit, reversed below — NOT ascending. Ascending with a limit pins the window
     * to the OLDEST MESSAGE_LIMIT documents, so once a panel had written that many messages every
     * newer one fell outside the query and was never delivered: the channel froze, silently, and
     * stayed frozen (secureMessages is an immutable log, so nothing ages out to make room).
     * `latestMessage` below already ordered descending for the same reason.
     */
    fun listen(agencyKey: AgencyKey, onMessages: (List<AgencyMessage>) -> Unit): ListenerRegistration =
        channel(agencyKey.agencySlug)
            .orderBy("createdAt", Query.Direction.DESCENDING)
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
                // Newest-first off the wire; callers render oldest-first.
                onMessages(messages.reversed())
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

    suspend fun send(agencyKey: AgencyKey, senderName: String, text: String): Unit = withContext(Dispatchers.IO) {
        @Suppress("UNUSED_VARIABLE")
        val rejected = arrayOf(agencyKey, senderName, text)
        throw SecurityException("Legacy shared-key agency writes are disabled; use Authority MLS v2.")
    }

    companion object {
        private const val REGION = "us-central1"
        private const val MESSAGE_LIMIT = 200L
    }
}
