package com.auralis.crisisconnect.messaging

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

enum class AuthorityMlsScopeType(val wireName: String) {
    AGENCY("agency"),
    HIERARCHY("hierarchy"),
}

data class AuthorityMlsBinding(
    val scopeType: AuthorityMlsScopeType,
    val channelId: String,
    val participants: List<String>,
)

data class AuthorityMlsDirectoryRecord(
    val uid: String,
    val deviceId: String,
    val credential: String,
    val signingPublicKey: ByteArray,
    val label: String,
)

data class AuthorityMlsDirectoryResult(
    val records: List<AuthorityMlsDirectoryRecord>,
    val rejected: Int,
)

data class AuthorityMlsControlEvent(
    val id: String,
    val senderUid: String,
    val senderDeviceId: String,
    val senderCredential: String,
    val payload: String,
    val sequence: Long,
)

data class AuthorityMlsCiphertextMessage(
    val messageId: String,
    val senderUid: String,
    val senderDeviceId: String,
    val senderCredential: String,
    val ciphertext: String,
    val sequence: Long = -1,
)

data class AuthorityMlsConversationHandle(
    val conversationId: String,
    val creatorCredential: String,
    val nextControlSequence: Long,
    val nextApplicationSequence: Long,
)

class AuthorityMlsPublishedDeviceRecoveryException : SecurityException(
    "Authority MLS published device state cannot be reset.",
)

object AuthorityMlsIdentifiers {
    fun conversationId(binding: AuthorityMlsBinding): String {
        val canonical = canonicalBinding(binding)
        return "am2_" + hash(
            listOf(
                "cc-authority-mls-conversation:v7",
                canonical.scopeType.wireName,
                canonical.channelId,
                canonical.participants.size.toString(),
            ) + canonical.participants,
        )
    }

    fun controlEventId(
        conversationId: String,
        sequence: Long,
        senderCredential: String,
        payload: String,
    ): String {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(sequence)
        return "c_" + hash(
            listOf(
                "cc-authority-mls-control:v2",
                conversationId,
                sequence.toString(),
                senderCredential,
                payload,
            ),
        )
    }

    internal fun canonicalBinding(binding: AuthorityMlsBinding): AuthorityMlsBinding {
        val channelId = validatePart(binding.channelId, "channel ID", 256)
        val normalized = binding.participants.map { validatePart(it, "participant UID", 256) }
        val participants = normalized.distinct().sortedWith(::compareUtf8)
        require(participants.isNotEmpty() && participants.size <= 100 && participants.size == binding.participants.size) {
            "Authority MLS participant set is invalid."
        }
        return AuthorityMlsBinding(binding.scopeType, channelId, participants)
    }

    private fun hash(fields: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (field in fields) {
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
    }
}

class AuthorityMlsControlSubscription internal constructor(
    private val registration: ListenerRegistration,
    private val queue: Channel<Pair<String, Map<String, Any>>>,
    private val scope: CoroutineScope,
) : Closeable {
    override fun close() {
        registration.remove()
        queue.close()
        scope.cancel()
    }
}

/** Firestore is an untrusted, opaque relay; every identity and event binding is checked locally. */
class AuthorityMlsTransport(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun ensureConversation(
        binding: AuthorityMlsBinding,
        creatorUid: String,
        candidateCreatorCredential: String,
    ): AuthorityMlsConversationHandle {
        val canonical = AuthorityMlsIdentifiers.canonicalBinding(binding)
        val uid = validatePart(creatorUid, "creator UID", 256)
        require(uid in canonical.participants) { "Authority MLS creator is not a participant." }
        val candidate = AuthorityMlsCredential.decode(candidateCreatorCredential)
        require(candidate?.accountUid == uid) {
            "Authority MLS creator credential is not bound to the creator account."
        }
        val conversationId = AuthorityMlsIdentifiers.conversationId(canonical)
        val reference = firestore.collection(ROOT).document(conversationId)
        val existing = reference.get().await()
        if (existing.exists()) {
            val creatorCredential = assertConversation(existing, canonical)
            return AuthorityMlsConversationHandle(
                conversationId,
                creatorCredential,
                existing.getLong("nextControlSequence")!!,
                existing.getLong("nextApplicationSequence")!!,
            )
        }
        val fields = mapOf(
            "version" to 2L,
            "scopeType" to canonical.scopeType.wireName,
            "channelId" to canonical.channelId,
            "participants" to canonical.participants,
            "createdBy" to uid,
            "creatorCredential" to candidateCreatorCredential,
            "createdAt" to FieldValue.serverTimestamp(),
            "nextControlSequence" to 0L,
            "lastControlId" to "",
            "nextApplicationSequence" to 0L,
            "lastMessageId" to "",
        )
        try {
            reference.set(fields).await()
        } catch (error: Throwable) {
            val raced = reference.get().await()
            if (!raced.exists()) throw error
            val creatorCredential = assertConversation(raced, canonical)
            return AuthorityMlsConversationHandle(
                conversationId,
                creatorCredential,
                raced.getLong("nextControlSequence")!!,
                raced.getLong("nextApplicationSequence")!!,
            )
        }
        return AuthorityMlsConversationHandle(conversationId, candidateCreatorCredential, 0L, 0L)
    }

    suspend fun registerDevice(conversationId: String, record: AuthorityMlsDirectoryRecord) {
        validateDocumentId(conversationId, "conversation ID")
        val normalized = validateDeviceRecord(record)
        val reference = firestore.collection(ROOT).document(conversationId)
            .collection("devices").document(normalized.deviceId)
        val existing = reference.get().await()
        if (existing.exists()) {
            assertDevice(existing, normalized)
            return
        }
        val fields = mapOf(
            "uid" to normalized.uid,
            "deviceId" to normalized.deviceId,
            "credential" to normalized.credential,
            "signingPublicKey" to base64url(normalized.signingPublicKey),
            "label" to normalized.label,
            "createdAt" to FieldValue.serverTimestamp(),
        )
        try {
            reference.set(fields).await()
        } catch (error: Throwable) {
            val raced = reference.get().await()
            if (!raced.exists()) throw error
            assertDevice(raced, normalized)
        }
    }

    suspend fun revokeDevice(conversationId: String, deviceId: String) {
        validateDocumentId(conversationId, "conversation ID")
        validateDocumentId(deviceId, "device ID")
        firestore.collection(ROOT).document(conversationId)
            .collection("devices").document(deviceId).delete().await()
    }

    /**
     * Removes only this account's local leaf record after the relay proves that the exact credential
     * has never published a control or application event. Parent cursors are pinned across the proof
     * and delete transaction so a concurrent first publish makes recovery fail closed.
     */
    suspend fun resetUnpublishedDeviceForRejoin(
        conversationId: String,
        deviceId: String,
        ownerUid: String,
        credential: String,
    ) {
        validateDocumentId(conversationId, "conversation ID")
        val normalizedDeviceId = validateDocumentId(deviceId, "device ID")
        val normalizedOwnerUid = validatePart(ownerUid, "device owner UID", 256)
        validateSender(normalizedOwnerUid, normalizedDeviceId, credential)

        val parent = firestore.collection(ROOT).document(conversationId)
        val device = parent.collection("devices").document(normalizedDeviceId)
        val before = parent.get().await()
        check(before.exists()) { "Authority MLS conversation does not exist." }
        val expectedControl = before.getLong("nextControlSequence")
            ?: throw SecurityException("Authority MLS relay control cursor is missing.")
        val expectedApplication = before.getLong("nextApplicationSequence")
            ?: throw SecurityException("Authority MLS relay application cursor is missing.")
        validateSequence(expectedControl)
        validateSequence(expectedApplication)

        val controls = parent.collection("control")
            .whereEqualTo("senderCredential", credential)
            .limit(1)
            .get()
            .await()
        val messages = parent.collection("messages")
            .whereEqualTo("senderCredential", credential)
            .limit(1)
            .get()
            .await()
        if (!controls.isEmpty || !messages.isEmpty) {
            throw AuthorityMlsPublishedDeviceRecoveryException()
        }

        firestore.runTransaction { transaction ->
            val parentSnapshot = transaction.get(parent)
            val deviceSnapshot = transaction.get(device)
            check(parentSnapshot.exists()) { "Authority MLS conversation does not exist." }
            check(
                parentSnapshot.getLong("nextControlSequence") == expectedControl &&
                    parentSnapshot.getLong("nextApplicationSequence") == expectedApplication
            ) { "Authority MLS relay changed while unpublished recovery was verified." }
            if (deviceSnapshot.exists()) {
                check(
                    deviceSnapshot.getString("uid") == normalizedOwnerUid &&
                        deviceSnapshot.getString("deviceId") == normalizedDeviceId &&
                        deviceSnapshot.getString("credential") == credential
                ) { "Authority MLS recovery device binding changed." }
                transaction.delete(device)
            }
            Unit
        }.await()
    }

    /** Records are structurally valid only; user/admin pin verification remains mandatory. */
    suspend fun loadDeviceDirectory(conversationId: String): AuthorityMlsDirectoryResult {
        validateDocumentId(conversationId, "conversation ID")
        val snapshot = firestore.collection(ROOT).document(conversationId).collection("devices").get().await()
        val records = ArrayList<AuthorityMlsDirectoryRecord>()
        var rejected = 0
        for (document in snapshot.documents) {
            try {
                val credential = requireString(document.get("credential"))
                val parsed = AuthorityMlsCredential.decode(credential)
                    ?: throw SecurityException("Authority MLS credential is malformed.")
                val uid = requireString(document.get("uid"))
                val deviceId = requireString(document.get("deviceId"))
                val key = decodeBase64url(requireString(document.get("signingPublicKey")))
                if (document.id != deviceId || parsed.accountUid != uid || parsed.deviceId != deviceId || key.size != 32) {
                    throw SecurityException("Authority MLS device binding is invalid.")
                }
                records += AuthorityMlsDirectoryRecord(
                    uid,
                    deviceId,
                    credential,
                    key,
                    (document.getString("label") ?: "").take(64),
                )
            } catch (_: Throwable) {
                rejected += 1
            }
        }
        records.sortWith { left, right -> compareUtf8(left.credential, right.credential) }
        return AuthorityMlsDirectoryResult(records, rejected)
    }

    /** Atomic, gap-free and idempotent publish for the durable MLS control outbox head. */
    suspend fun publishControlEvent(
        conversationId: String,
        sequence: Long,
        senderUid: String,
        senderDeviceId: String,
        senderCredential: String,
        payload: String,
    ): String {
        val payloadSize = payload.toByteArray(StandardCharsets.UTF_8).size
        require(payloadSize in 1..MAX_CONTROL_PAYLOAD_BYTES) { "Authority MLS control payload has an invalid size." }
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(sequence)
        validateSender(senderUid, senderDeviceId, senderCredential)
        val eventId = AuthorityMlsIdentifiers.controlEventId(
            conversationId,
            sequence,
            senderCredential,
            payload,
        )
        val parent = firestore.collection(ROOT).document(conversationId)
        val event = parent.collection("control").document(eventId)
        firestore.runTransaction { transaction ->
            val parentSnapshot = transaction.get(parent)
            val eventSnapshot = transaction.get(event)
            check(parentSnapshot.exists()) { "Authority MLS conversation does not exist." }
            if (eventSnapshot.exists()) {
                assertControl(eventSnapshot, sequence, senderUid, senderDeviceId, senderCredential, payload)
            } else {
                check(parentSnapshot.getLong("nextControlSequence") == sequence) {
                    "Authority MLS control sequence is stale or has a gap."
                }
                transaction.set(event, mapOf(
                    "senderUid" to senderUid,
                    "senderDeviceId" to senderDeviceId,
                    "senderCredential" to senderCredential,
                    "payload" to payload,
                    "sequence" to sequence,
                    "createdAt" to FieldValue.serverTimestamp(),
                ))
                transaction.update(parent, mapOf(
                    "nextControlSequence" to sequence + 1,
                    "lastControlId" to eventId,
                ))
            }
            true
        }.await()
        return eventId
    }

    /** Crash-recovery probe before a pristine device advances past an occupied relay cursor. */
    suspend fun isControlEventPublished(
        conversationId: String,
        sequence: Long,
        senderUid: String,
        senderDeviceId: String,
        senderCredential: String,
        payload: String,
    ): Boolean {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(sequence)
        validateSender(senderUid, senderDeviceId, senderCredential)
        val eventId = AuthorityMlsIdentifiers.controlEventId(
            conversationId,
            sequence,
            senderCredential,
            payload,
        )
        val snapshot = firestore.collection(ROOT).document(conversationId)
            .collection("control").document(eventId).get().await()
        if (!snapshot.exists()) return false
        val event = parseControl(conversationId, eventId, snapshot.data ?: emptyMap())
        return event.sequence == sequence && event.senderUid == senderUid &&
            event.senderDeviceId == senderDeviceId && event.senderCredential == senderCredential &&
            event.payload == payload
    }

    fun listenControlEvents(
        conversationId: String,
        fromSequence: Long,
        onEvent: suspend (AuthorityMlsControlEvent) -> Unit,
        onError: (Throwable) -> Unit,
    ): AuthorityMlsControlSubscription {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(fromSequence)
        val queue = Channel<Pair<String, Map<String, Any>>>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            for ((eventId, raw) in queue) {
                try {
                    onEvent(parseControl(conversationId, eventId, raw))
                } catch (error: Throwable) {
                    onError(error)
                }
            }
        }
        val registration = firestore.collection(ROOT).document(conversationId).collection("control")
            .orderBy("sequence", Query.Direction.ASCENDING)
            .startAt(fromSequence)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        @Suppress("UNCHECKED_CAST")
                        queue.trySend(change.document.id to (change.document.data as Map<String, Any>))
                    }
                }
            }
        return AuthorityMlsControlSubscription(registration, queue, scope)
    }

    fun listenCiphertexts(
        conversationId: String,
        fromSequence: Long,
        onMessage: suspend (AuthorityMlsCiphertextMessage) -> Unit,
        onError: (Throwable) -> Unit,
    ): AuthorityMlsControlSubscription {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(fromSequence)
        val queue = Channel<Pair<String, Map<String, Any>>>(Channel.UNLIMITED)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            for ((messageId, raw) in queue) {
                try {
                    onMessage(parseCiphertext(messageId, raw))
                } catch (error: Throwable) {
                    onError(error)
                }
            }
        }
        val registration = firestore.collection(ROOT).document(conversationId).collection("messages")
            .orderBy("sequence", Query.Direction.ASCENDING)
            .startAt(fromSequence)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                snapshot?.documentChanges?.forEach { change ->
                    if (change.type == com.google.firebase.firestore.DocumentChange.Type.ADDED) {
                        @Suppress("UNCHECKED_CAST")
                        queue.trySend(change.document.id to (change.document.data as Map<String, Any>))
                    }
                }
            }
        return AuthorityMlsControlSubscription(registration, queue, scope)
    }

    suspend fun loadControlEventsFrom(
        conversationId: String,
        fromSequence: Long,
        pageSize: Long = 100,
    ): List<AuthorityMlsControlEvent> {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(fromSequence)
        require(pageSize in 1..250) { "Authority MLS control catch-up page size is invalid." }
        val snapshot = firestore.collection(ROOT).document(conversationId).collection("control")
            .orderBy("sequence", Query.Direction.ASCENDING)
            .startAt(fromSequence)
            .limit(pageSize)
            .get().await()
        return snapshot.documents.map { parseControl(conversationId, it.id, it.data ?: emptyMap()) }
    }

    suspend fun loadApplicationSequence(conversationId: String): Long {
        validateDocumentId(conversationId, "conversation ID")
        val snapshot = firestore.collection(ROOT).document(conversationId).get().await()
        check(snapshot.exists()) { "Authority MLS conversation does not exist." }
        return snapshot.getLong("nextApplicationSequence")?.also(::validateSequence)
            ?: throw SecurityException("Authority MLS conversation is malformed.")
    }

    suspend fun loadCiphertextsBefore(
        conversationId: String,
        fromSequence: Long,
        beforeSequence: Long,
        pageSize: Long = 100,
    ): List<AuthorityMlsCiphertextMessage> {
        validateDocumentId(conversationId, "conversation ID")
        validateSequence(fromSequence)
        validateSequence(beforeSequence)
        require(pageSize in 1..250) { "Authority MLS application catch-up page size is invalid." }
        if (fromSequence >= beforeSequence) return emptyList()
        val snapshot = firestore.collection(ROOT).document(conversationId).collection("messages")
            .whereGreaterThanOrEqualTo("sequence", fromSequence)
            .whereLessThan("sequence", beforeSequence)
            .orderBy("sequence", Query.Direction.ASCENDING)
            .limit(pageSize)
            .get().await()
        return snapshot.documents.map { parseCiphertext(it.id, it.data ?: emptyMap()) }
    }

    suspend fun publishCiphertext(conversationId: String, message: AuthorityMlsCiphertextMessage): Long {
        validateDocumentId(conversationId, "conversation ID")
        validateDocumentId(message.messageId, "message ID")
        validateSender(message.senderUid, message.senderDeviceId, message.senderCredential)
        require(message.ciphertext.isNotEmpty() && message.ciphertext.length <= 900_000 &&
            BASE64URL.matches(message.ciphertext)) { "Authority MLS application ciphertext is malformed." }
        val parent = firestore.collection(ROOT).document(conversationId)
        val reference = parent.collection("messages").document(message.messageId)
        return firestore.runTransaction { transaction ->
            val parentSnapshot = transaction.get(parent)
            val existing = transaction.get(reference)
            check(parentSnapshot.exists()) { "Authority MLS conversation does not exist." }
            if (existing.exists()) {
                check(existing.getString("messageId") == message.messageId &&
                    existing.getLong("contentVersion") == 2L &&
                    existing.getString("ciphertext") == message.ciphertext &&
                    existing.getString("senderUid") == message.senderUid &&
                    existing.getString("senderDeviceId") == message.senderDeviceId &&
                    existing.getString("senderCredential") == message.senderCredential) {
                    "Authority MLS message ID was already used for different ciphertext."
                }
                existing.getLong("sequence")?.also(::validateSequence)
                    ?: throw SecurityException("Authority MLS message sequence is malformed.")
            } else {
                val sequence = parentSnapshot.getLong("nextApplicationSequence")
                    ?: throw SecurityException("Authority MLS application cursor is malformed.")
                validateSequence(sequence)
                transaction.set(reference, mapOf(
                    "senderUid" to message.senderUid,
                    "senderDeviceId" to message.senderDeviceId,
                    "senderCredential" to message.senderCredential,
                    "messageId" to message.messageId,
                    "contentVersion" to 2L,
                    "ciphertext" to message.ciphertext,
                    "sequence" to sequence,
                    "createdAt" to FieldValue.serverTimestamp(),
                ))
                transaction.update(parent, mapOf(
                    "nextApplicationSequence" to sequence + 1,
                    "lastMessageId" to message.messageId,
                ))
                sequence
            }
        }.await()
    }

    /** True only when this immutable message id is already owned by the expected sender account. */
    suspend fun isCiphertextPublished(
        conversationId: String,
        messageId: String,
        senderUid: String,
    ): Boolean {
        validateDocumentId(conversationId, "conversation ID")
        validateDocumentId(messageId, "message ID")
        val snapshot = firestore.collection(ROOT).document(conversationId)
            .collection("messages").document(messageId).get().await()
        if (!snapshot.exists()) return false
        val parsed = parseCiphertext(snapshot.id, snapshot.data ?: emptyMap())
        val identity = AuthorityMlsCredential.decode(parsed.senderCredential)
        if (identity?.accountUid != senderUid || parsed.senderUid != senderUid) {
            throw SecurityException("Authority MLS pending message ID belongs to another sender account.")
        }
        return true
    }

    private fun parseCiphertext(messageId: String, raw: Map<String, Any>): AuthorityMlsCiphertextMessage {
        validateDocumentId(messageId, "message ID")
        val message = AuthorityMlsCiphertextMessage(
            messageId = requireString(raw["messageId"]),
            senderUid = requireString(raw["senderUid"]),
            senderDeviceId = requireString(raw["senderDeviceId"]),
            senderCredential = requireString(raw["senderCredential"]),
            ciphertext = requireString(raw["ciphertext"]),
            sequence = raw["sequence"] as? Long
                ?: throw SecurityException("Authority MLS application sequence is invalid."),
        )
        if (message.messageId != messageId || raw["contentVersion"] != 2L ||
            message.ciphertext.length > 900_000 || !BASE64URL.matches(message.ciphertext)) {
            throw SecurityException("Authority MLS ciphertext document is malformed.")
        }
        validateSequence(message.sequence)
        validateSender(message.senderUid, message.senderDeviceId, message.senderCredential)
        return message
    }

    private fun parseControl(
        conversationId: String,
        eventId: String,
        raw: Map<String, Any>,
    ): AuthorityMlsControlEvent {
        val event = AuthorityMlsControlEvent(
            id = eventId,
            senderUid = requireString(raw["senderUid"]),
            senderDeviceId = requireString(raw["senderDeviceId"]),
            senderCredential = requireString(raw["senderCredential"]),
            payload = requireString(raw["payload"]),
            sequence = raw["sequence"] as? Long
                ?: throw SecurityException("Authority MLS control sequence is invalid."),
        )
        validateSequence(event.sequence)
        validateSender(event.senderUid, event.senderDeviceId, event.senderCredential)
        val expectedId = AuthorityMlsIdentifiers.controlEventId(
            conversationId,
            event.sequence,
            event.senderCredential,
            event.payload,
        )
        if (eventId != expectedId) throw SecurityException("Authority MLS control event ID is not canonical.")
        return event
    }

    companion object {
        private const val ROOT = "authorityMlsV2"
        private const val MAX_CONTROL_PAYLOAD_BYTES = 256 * 1024
        private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
    }
}

private fun assertConversation(snapshot: DocumentSnapshot, expected: AuthorityMlsBinding): String {
    val participants = snapshot.get("participants") as? List<*>
    check(snapshot.getLong("version") == 2L &&
        snapshot.getString("scopeType") == expected.scopeType.wireName &&
        snapshot.getString("channelId") == expected.channelId &&
        participants == expected.participants &&
        snapshot.getLong("nextControlSequence")?.also(::validateSequence) != null &&
        snapshot.getLong("nextApplicationSequence")?.also(::validateSequence) != null &&
        snapshot.getString("lastControlId") != null && snapshot.getString("lastMessageId") != null) {
        "Authority MLS conversation binding changed."
    }
    val createdBy = requireString(snapshot.get("createdBy"))
    val creatorCredential = requireString(snapshot.get("creatorCredential"))
    val parsed = AuthorityMlsCredential.decode(creatorCredential)
    check(createdBy in expected.participants && parsed?.accountUid == createdBy) {
        "Authority MLS creator binding is invalid."
    }
    return creatorCredential
}

private fun validateDeviceRecord(record: AuthorityMlsDirectoryRecord): AuthorityMlsDirectoryRecord {
    val uid = validatePart(record.uid, "device owner UID", 256)
    val deviceId = validateDocumentId(record.deviceId, "device ID")
    val parsed = AuthorityMlsCredential.decode(record.credential)
    require(parsed?.accountUid == uid && parsed.deviceId == deviceId && record.signingPublicKey.size == 32) {
        "Authority MLS device identity binding is invalid."
    }
    return record.copy(uid = uid, deviceId = deviceId, signingPublicKey = record.signingPublicKey.copyOf(), label = record.label.take(64))
}

private fun assertDevice(snapshot: DocumentSnapshot, expected: AuthorityMlsDirectoryRecord) {
    check(snapshot.getString("uid") == expected.uid &&
        snapshot.getString("deviceId") == expected.deviceId &&
        snapshot.getString("credential") == expected.credential &&
        snapshot.getString("signingPublicKey") == base64url(expected.signingPublicKey)) {
        "Authority MLS device record changed."
    }
}

private fun assertControl(
    snapshot: DocumentSnapshot,
    sequence: Long,
    senderUid: String,
    senderDeviceId: String,
    senderCredential: String,
    payload: String,
) {
    check(snapshot.getLong("sequence") == sequence &&
        snapshot.getString("senderUid") == senderUid &&
        snapshot.getString("senderDeviceId") == senderDeviceId &&
        snapshot.getString("senderCredential") == senderCredential &&
        snapshot.getString("payload") == payload) {
        "Authority MLS control event ID was already used for different data."
    }
}

private fun validateSender(senderUid: String, senderDeviceId: String, senderCredential: String) {
    val uid = validatePart(senderUid, "sender UID", 256)
    val deviceId = validateDocumentId(senderDeviceId, "sender device ID")
    val parsed = AuthorityMlsCredential.decode(senderCredential)
    require(parsed?.accountUid == uid && parsed.deviceId == deviceId) {
        "Authority MLS sender credential is not bound to its account and device."
    }
}

private fun validateSequence(sequence: Long) {
    require(sequence >= 0 && sequence < 9_007_199_254_740_991L) {
        "Authority MLS control sequence is invalid."
    }
}

private fun validateDocumentId(value: String, label: String): String {
    val normalized = validatePart(value, label, 128)
    require('/' !in normalized) { "Authority MLS $label is invalid." }
    return normalized
}

private fun validatePart(value: String, label: String, maxBytes: Int): String {
    val normalized = value.trim()
    require(normalized.isNotEmpty() && normalized.toByteArray(StandardCharsets.UTF_8).size <= maxBytes &&
        normalized.none { it.code in 0..31 || it.code == 127 }) { "Authority MLS $label is invalid." }
    return normalized
}

private fun requireString(value: Any?): String =
    (value as? String)?.takeIf { it.isNotEmpty() }
        ?: throw SecurityException("Authority MLS document is malformed.")

private fun base64url(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)

private fun decodeBase64url(value: String): ByteArray {
    require(value.isNotEmpty() && Regex("^[A-Za-z0-9_-]+$").matches(value)) { "Invalid base64url." }
    val decoded = Base64.getUrlDecoder().decode(value)
    require(base64url(decoded) == value) { "Non-canonical base64url." }
    return decoded
}

private fun compareUtf8(left: String, right: String): Int {
    val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
    val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
    val shared = minOf(leftBytes.size, rightBytes.size)
    for (index in 0 until shared) {
        val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return leftBytes.size - rightBytes.size
}
