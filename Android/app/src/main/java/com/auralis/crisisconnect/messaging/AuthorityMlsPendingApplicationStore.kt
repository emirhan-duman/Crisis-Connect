package com.auralis.crisisconnect.messaging

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class AuthorityMlsStagedApplication(
    val messageId: String,
    val senderCredential: String,
    val plaintext: ByteArray,
)

/**
 * Device-local, Keystore-sealed AuthorityChat outbox. Nothing is uploaded to the MLS relay until
 * this exact device joins a verified group; the user can still compose while the peer is offline.
 */
object AuthorityMlsPendingApplicationStore {
    private val lock = Any()
    private val CONVERSATION_ID = Regex("^am2_[A-Za-z0-9_-]{43}$")
    private val MESSAGE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
    private val MAGIC = byteArrayOf(0x43, 0x43, 0x41, 0x4f, 0x42, 0x01)
    private const val MAX_PENDING = 64
    private const val MAX_PLAINTEXT_BYTES = 900_000
    private const val MAX_CREDENTIAL_BYTES = 512

    suspend fun stage(
        context: Context,
        accountUid: String,
        conversationId: String,
        application: AuthorityMlsStagedApplication,
    ) = withContext(Dispatchers.IO) {
        validate(accountUid, conversationId, application)
        synchronized(lock) {
            val ids = loadIndex(context, accountUid, conversationId).toMutableList()
            if (application.messageId !in ids) {
                check(ids.size < MAX_PENDING) { "Authority MLS protected outbox is full." }
                MlsStateVault.saveProtectedData(
                    context,
                    entryContext(accountUid, conversationId, application.messageId),
                    encode(application),
                )
                ids += application.messageId
                saveIndex(context, accountUid, conversationId, ids)
            } else {
                val existing = loadEntry(context, accountUid, conversationId, application.messageId)
                check(existing != null && existing.senderCredential == application.senderCredential &&
                    existing.plaintext.contentEquals(application.plaintext)) {
                    "Authority MLS pending message ID was reused with different plaintext."
                }
            }
        }
    }

    suspend fun load(
        context: Context,
        accountUid: String,
        conversationId: String,
    ): List<AuthorityMlsStagedApplication> = withContext(Dispatchers.IO) {
        validateBinding(accountUid, conversationId)
        synchronized(lock) {
            loadIndex(context, accountUid, conversationId).mapNotNull { messageId ->
                loadEntry(context, accountUid, conversationId, messageId)
            }
        }
    }

    suspend fun remove(
        context: Context,
        accountUid: String,
        conversationId: String,
        messageId: String,
    ) = withContext(Dispatchers.IO) {
        validateBinding(accountUid, conversationId)
        require(MESSAGE_ID.matches(messageId)) { "Authority MLS message ID is invalid." }
        synchronized(lock) {
            val remaining = loadIndex(context, accountUid, conversationId).filterNot { it == messageId }
            saveIndex(context, accountUid, conversationId, remaining)
            MlsStateVault.deleteProtectedData(context, entryContext(accountUid, conversationId, messageId))
        }
    }

    private fun loadIndex(context: Context, accountUid: String, conversationId: String): List<String> {
        val encoded = MlsStateVault.loadProtectedData(context, indexContext(accountUid, conversationId))
            ?: return emptyList()
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded)).toString()
        val array = JSONArray(text)
        require(array.length() <= MAX_PENDING) { "Authority MLS protected outbox index is too large." }
        return buildList {
            for (index in 0 until array.length()) {
                val id = array.get(index) as? String
                    ?: throw SecurityException("Authority MLS protected outbox index is malformed.")
                require(MESSAGE_ID.matches(id) && id !in this) {
                    "Authority MLS protected outbox index is malformed."
                }
                add(id)
            }
        }
    }

    private fun saveIndex(context: Context, accountUid: String, conversationId: String, ids: List<String>) {
        val storageContext = indexContext(accountUid, conversationId)
        if (ids.isEmpty()) {
            MlsStateVault.deleteProtectedData(context, storageContext)
            return
        }
        val array = JSONArray()
        ids.forEach(array::put)
        MlsStateVault.saveProtectedData(
            context,
            storageContext,
            array.toString().toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun loadEntry(
        context: Context,
        accountUid: String,
        conversationId: String,
        messageId: String,
    ): AuthorityMlsStagedApplication? {
        val encoded = MlsStateVault.loadProtectedData(
            context,
            entryContext(accountUid, conversationId, messageId),
        ) ?: return null
        return decode(encoded).also {
            require(it.messageId == messageId) { "Authority MLS protected outbox entry changed identity." }
            validate(accountUid, conversationId, it)
        }
    }

    private fun encode(application: AuthorityMlsStagedApplication): ByteArray {
        val messageId = application.messageId.toByteArray(StandardCharsets.UTF_8)
        val credential = application.senderCredential.toByteArray(StandardCharsets.UTF_8)
        return ByteBuffer.allocate(MAGIC.size + 12 + messageId.size + credential.size + application.plaintext.size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(MAGIC)
                putInt(messageId.size)
                putInt(credential.size)
                putInt(application.plaintext.size)
                put(messageId)
                put(credential)
                put(application.plaintext)
            }
            .array()
    }

    private fun decode(encoded: ByteArray): AuthorityMlsStagedApplication {
        require(encoded.size in (MAGIC.size + 12 + 2)..(MAGIC.size + 12 + 128 + MAX_CREDENTIAL_BYTES + MAX_PLAINTEXT_BYTES)) {
            "Authority MLS protected outbox entry size is invalid."
        }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Authority MLS protected outbox entry version is invalid." }
        val idSize = buffer.int
        val credentialSize = buffer.int
        val plaintextSize = buffer.int
        require(idSize in 1..128 && credentialSize in 1..MAX_CREDENTIAL_BYTES &&
            plaintextSize in 1..MAX_PLAINTEXT_BYTES &&
            buffer.remaining() == idSize + credentialSize + plaintextSize) {
            "Authority MLS protected outbox entry is malformed."
        }
        val idBytes = ByteArray(idSize).also(buffer::get)
        val credentialBytes = ByteArray(credentialSize).also(buffer::get)
        val plaintext = ByteArray(plaintextSize).also(buffer::get)
        return AuthorityMlsStagedApplication(
            strictUtf8(idBytes),
            strictUtf8(credentialBytes),
            plaintext,
        )
    }

    private fun validate(
        accountUid: String,
        conversationId: String,
        application: AuthorityMlsStagedApplication,
    ) {
        validateBinding(accountUid, conversationId)
        require(MESSAGE_ID.matches(application.messageId) &&
            application.plaintext.size in 1..MAX_PLAINTEXT_BYTES) {
            "Authority MLS protected outbox application is invalid."
        }
        val identity = AuthorityMlsCredential.decode(application.senderCredential)
        require(identity?.accountUid == accountUid &&
            application.senderCredential.toByteArray(StandardCharsets.UTF_8).size <= MAX_CREDENTIAL_BYTES) {
            "Authority MLS protected outbox sender is invalid."
        }
        AuthorityMlsMessagePayloadCodec.decode(application.plaintext)
    }

    private fun validateBinding(accountUid: String, conversationId: String) {
        require(accountUid.isNotBlank() && accountUid.toByteArray(StandardCharsets.UTF_8).size <= 256 &&
            CONVERSATION_ID.matches(conversationId)) {
            "Authority MLS protected outbox binding is invalid."
        }
    }

    private fun indexContext(accountUid: String, conversationId: String) =
        "authority-mls-outbox-index:v1:$accountUid:$conversationId"

    private fun entryContext(accountUid: String, conversationId: String, messageId: String) =
        "authority-mls-outbox-entry:v1:$accountUid:$conversationId:$messageId"

    private fun strictUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
