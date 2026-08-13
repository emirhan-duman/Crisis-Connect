package com.auralis.crisisconnect.messaging

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class AuthorityMlsPendingApplication(
    val messageId: String,
    val ciphertext: String,
)

data class AuthorityMlsPendingReceivedApplication(
    val messageId: String,
    val senderCredential: String,
    val plaintext: ByteArray,
)

/**
 * An application message already authenticated and opened from the offline Bluetooth relay, but
 * not yet observed in the authoritative Firestore application log. Keeping this receipt in the
 * same crash-safe envelope as the OpenMLS snapshot lets the later cloud copy advance the relay
 * cursor without decrypting (and consuming) the same sender generation twice.
 */
data class AuthorityMlsOfflineReceipt(
    val messageId: String,
    val senderCredential: String,
    val ciphertextHash: String,
)

data class AuthorityMlsDurableState(
    val snapshot: ByteArray,
    val nextControlSequence: Long,
    val nextApplicationSequence: Long,
    val pendingControlEvents: List<String>,
    val pendingApplicationMessages: List<AuthorityMlsPendingApplication>,
    val pendingReceivedApplications: List<AuthorityMlsPendingReceivedApplication>,
    val offlineReceipts: List<AuthorityMlsOfflineReceipt>,
)

/** Versioned envelope binding OpenMLS, relay cursor and both crash-safe outboxes. */
object AuthorityMlsDurableStateCodec {
    private val MAGIC_PREFIX = byteArrayOf(0x43, 0x43, 0x4d, 0x4c, 0x53, 0x32, 0x00)
    private const val V1: Byte = 1
    private const val V2: Byte = 2
    private const val V3: Byte = 3
    private const val V4: Byte = 4
    private const val V1_HEADER_BYTES = 24
    private const val V2_HEADER_BYTES = 28
    private const val V3_HEADER_BYTES = 40
    private const val V4_HEADER_BYTES = 44
    private const val MAX_MLS_SNAPSHOT_BYTES = 16 * 1024 * 1024
    private const val MAX_CONTROL_EVENT_BYTES = 256 * 1024
    private const val MAX_PENDING_CONTROL_EVENTS = 32
    private const val MAX_PENDING_CONTROL_BYTES = 4 * 1024 * 1024
    private const val MAX_PENDING_APPLICATION_MESSAGES = 64
    private const val MAX_PENDING_APPLICATION_BYTES = 4 * 1024 * 1024
    private const val MAX_MESSAGE_ID_BYTES = 128
    private const val MAX_CIPHERTEXT_BYTES = 900_000
    private const val MAX_PENDING_RECEIVED_APPLICATIONS = 64
    private const val MAX_PENDING_RECEIVED_BYTES = 4 * 1024 * 1024
    private const val MAX_SENDER_CREDENTIAL_BYTES = 512
    private const val MAX_PLAINTEXT_BYTES = 900_000
    private const val MAX_OFFLINE_RECEIPTS = 256
    private const val MAX_OFFLINE_RECEIPT_BYTES = 256 * 1024
    private const val CIPHERTEXT_HASH_BYTES = 43
    private const val MAX_SAFE_SEQUENCE = 9_007_199_254_740_991L
    const val MAX_DURABLE_STATE_BYTES =
        V4_HEADER_BYTES + MAX_MLS_SNAPSHOT_BYTES + MAX_PENDING_CONTROL_BYTES +
            MAX_PENDING_APPLICATION_BYTES + MAX_PENDING_RECEIVED_BYTES + MAX_OFFLINE_RECEIPT_BYTES

    fun encode(state: AuthorityMlsDurableState): ByteArray {
        require(state.snapshot.size in 1..MAX_MLS_SNAPSHOT_BYTES) { "Authority MLS snapshot size is invalid." }
        require(state.nextControlSequence in 0..MAX_SAFE_SEQUENCE) { "Authority MLS control sequence is invalid." }
        require(state.nextApplicationSequence in 0..MAX_SAFE_SEQUENCE) {
            "Authority MLS application sequence is invalid."
        }
        require(state.pendingControlEvents.size <= MAX_PENDING_CONTROL_EVENTS &&
            state.pendingApplicationMessages.size <= MAX_PENDING_APPLICATION_MESSAGES) {
            "Authority MLS durable outbox is full."
        }
        var controlBytes = 0
        val events = state.pendingControlEvents.map { event ->
            event.toByteArray(StandardCharsets.UTF_8).also { encoded ->
                require(encoded.size in 1..MAX_CONTROL_EVENT_BYTES) { "Authority MLS control event size is invalid." }
                controlBytes += 4 + encoded.size
                require(controlBytes <= MAX_PENDING_CONTROL_BYTES) { "Authority MLS control outbox is too large." }
            }
        }
        var applicationBytes = 0
        val messages = state.pendingApplicationMessages.map { message ->
            val messageId = message.messageId.toByteArray(StandardCharsets.UTF_8)
            val ciphertext = message.ciphertext.toByteArray(StandardCharsets.UTF_8)
            require(messageId.size in 1..MAX_MESSAGE_ID_BYTES && BASE64URL.matches(message.messageId) &&
                ciphertext.size in 1..MAX_CIPHERTEXT_BYTES && BASE64URL.matches(message.ciphertext)) {
                "Authority MLS application outbox entry is malformed."
            }
            applicationBytes += 8 + messageId.size + ciphertext.size
            require(applicationBytes <= MAX_PENDING_APPLICATION_BYTES) {
                "Authority MLS application outbox is too large."
            }
            messageId to ciphertext
        }
        require(state.pendingReceivedApplications.size <= MAX_PENDING_RECEIVED_APPLICATIONS) {
            "Authority MLS durable inbox is full."
        }
        var receivedBytes = 0
        val received = state.pendingReceivedApplications.map { message ->
            val messageId = message.messageId.toByteArray(StandardCharsets.UTF_8)
            val sender = message.senderCredential.toByteArray(StandardCharsets.UTF_8)
            val plaintext = message.plaintext.copyOf()
            require(messageId.size in 1..MAX_MESSAGE_ID_BYTES && BASE64URL.matches(message.messageId) &&
                sender.size in 1..MAX_SENDER_CREDENTIAL_BYTES && plaintext.size in 1..MAX_PLAINTEXT_BYTES) {
                "Authority MLS application inbox entry is malformed."
            }
            receivedBytes += 12 + messageId.size + sender.size + plaintext.size
            require(receivedBytes <= MAX_PENDING_RECEIVED_BYTES) { "Authority MLS application inbox is too large." }
            Triple(messageId, sender, plaintext)
        }
        require(state.offlineReceipts.size <= MAX_OFFLINE_RECEIPTS) {
            "Authority MLS offline receipt ledger is full."
        }
        var receiptBytes = 0
        val receipts = state.offlineReceipts.map { receipt ->
            val messageId = receipt.messageId.toByteArray(StandardCharsets.UTF_8)
            val sender = receipt.senderCredential.toByteArray(StandardCharsets.UTF_8)
            val hash = receipt.ciphertextHash.toByteArray(StandardCharsets.UTF_8)
            require(messageId.size in 1..MAX_MESSAGE_ID_BYTES && BASE64URL.matches(receipt.messageId) &&
                sender.size in 1..MAX_SENDER_CREDENTIAL_BYTES && hash.size == CIPHERTEXT_HASH_BYTES &&
                BASE64URL.matches(receipt.ciphertextHash)) {
                "Authority MLS offline receipt is malformed."
            }
            receiptBytes += 12 + messageId.size + sender.size + hash.size
            require(receiptBytes <= MAX_OFFLINE_RECEIPT_BYTES) {
                "Authority MLS offline receipt ledger is too large."
            }
            Triple(messageId, sender, hash)
        }
        val total = V4_HEADER_BYTES + state.snapshot.size + controlBytes + applicationBytes +
            receivedBytes + receiptBytes
        require(total <= MAX_DURABLE_STATE_BYTES) { "Authority MLS durable state is too large." }
        return ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN).apply {
            put(MAGIC_PREFIX)
            put(V4)
            putLong(state.nextControlSequence)
            putLong(state.nextApplicationSequence)
            putInt(state.snapshot.size)
            putInt(events.size)
            putInt(messages.size)
            putInt(received.size)
            putInt(receipts.size)
            put(state.snapshot)
            events.forEach { event -> putInt(event.size); put(event) }
            messages.forEach { (messageId, ciphertext) ->
                putInt(messageId.size)
                putInt(ciphertext.size)
                put(messageId)
                put(ciphertext)
            }
            received.forEach { (messageId, sender, plaintext) ->
                putInt(messageId.size)
                putInt(sender.size)
                putInt(plaintext.size)
                put(messageId)
                put(sender)
                put(plaintext)
            }
            receipts.forEach { (messageId, sender, hash) ->
                putInt(messageId.size)
                putInt(sender.size)
                putInt(hash.size)
                put(messageId)
                put(sender)
                put(hash)
            }
        }.array()
    }

    fun decode(encoded: ByteArray): AuthorityMlsDurableState {
        require(encoded.size in V1_HEADER_BYTES..MAX_DURABLE_STATE_BYTES) {
            "Authority MLS durable state size is invalid."
        }
        val buffer = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN)
        val magic = ByteArray(MAGIC_PREFIX.size).also(buffer::get)
        val version = buffer.get()
        require(magic.contentEquals(MAGIC_PREFIX) &&
            (version == V1 || version == V2 || version == V3 || version == V4)) {
            "Authority MLS durable state version is invalid."
        }
        val headerBytes = when (version) {
            V1 -> V1_HEADER_BYTES
            V2 -> V2_HEADER_BYTES
            V3 -> V3_HEADER_BYTES
            else -> V4_HEADER_BYTES
        }
        require(encoded.size >= headerBytes) { "Authority MLS durable state is truncated." }
        val sequence = buffer.long
        val nextApplicationSequence = if (version == V3 || version == V4) buffer.long else 0L
        val snapshotLength = buffer.int
        val eventCount = buffer.int
        val applicationCount = if (version == V1) 0 else buffer.int
        val receivedCount = if (version == V3 || version == V4) buffer.int else 0
        val receiptCount = if (version == V4) buffer.int else 0
        require(sequence in 0..MAX_SAFE_SEQUENCE && snapshotLength in 1..MAX_MLS_SNAPSHOT_BYTES &&
            nextApplicationSequence in 0..MAX_SAFE_SEQUENCE &&
            eventCount in 0..MAX_PENDING_CONTROL_EVENTS &&
            applicationCount in 0..MAX_PENDING_APPLICATION_MESSAGES &&
            receivedCount in 0..MAX_PENDING_RECEIVED_APPLICATIONS &&
            receiptCount in 0..MAX_OFFLINE_RECEIPTS && snapshotLength <= buffer.remaining()) {
            "Authority MLS durable state is malformed."
        }
        val snapshot = ByteArray(snapshotLength).also(buffer::get)
        var controlBytes = 0
        val events = buildList {
            repeat(eventCount) {
                require(buffer.remaining() >= 4) { "Authority MLS control outbox is truncated." }
                val length = buffer.int
                controlBytes += 4 + length.coerceAtLeast(0)
                require(length in 1..MAX_CONTROL_EVENT_BYTES && controlBytes <= MAX_PENDING_CONTROL_BYTES &&
                    length <= buffer.remaining()) { "Authority MLS control outbox is malformed." }
                add(decodeUtf8(ByteArray(length).also(buffer::get)))
            }
        }
        var applicationBytes = 0
        val messages = buildList {
            repeat(applicationCount) {
                require(buffer.remaining() >= 8) { "Authority MLS application outbox is truncated." }
                val messageIdLength = buffer.int
                val ciphertextLength = buffer.int
                applicationBytes += 8 + messageIdLength.coerceAtLeast(0) + ciphertextLength.coerceAtLeast(0)
                require(messageIdLength in 1..MAX_MESSAGE_ID_BYTES && ciphertextLength in 1..MAX_CIPHERTEXT_BYTES &&
                    applicationBytes <= MAX_PENDING_APPLICATION_BYTES &&
                    messageIdLength + ciphertextLength <= buffer.remaining()) {
                    "Authority MLS application outbox is malformed."
                }
                val messageId = decodeUtf8(ByteArray(messageIdLength).also(buffer::get))
                val ciphertext = decodeUtf8(ByteArray(ciphertextLength).also(buffer::get))
                require(BASE64URL.matches(messageId) && BASE64URL.matches(ciphertext)) {
                    "Authority MLS application outbox is malformed."
                }
                add(AuthorityMlsPendingApplication(messageId, ciphertext))
            }
        }
        var receivedBytes = 0
        val received = buildList {
            repeat(receivedCount) {
                require(buffer.remaining() >= 12) { "Authority MLS application inbox is truncated." }
                val messageIdLength = buffer.int
                val senderLength = buffer.int
                val plaintextLength = buffer.int
                receivedBytes += 12 + messageIdLength.coerceAtLeast(0) + senderLength.coerceAtLeast(0) +
                    plaintextLength.coerceAtLeast(0)
                require(messageIdLength in 1..MAX_MESSAGE_ID_BYTES &&
                    senderLength in 1..MAX_SENDER_CREDENTIAL_BYTES && plaintextLength in 1..MAX_PLAINTEXT_BYTES &&
                    receivedBytes <= MAX_PENDING_RECEIVED_BYTES &&
                    messageIdLength + senderLength + plaintextLength <= buffer.remaining()) {
                    "Authority MLS application inbox is malformed."
                }
                val messageId = decodeUtf8(ByteArray(messageIdLength).also(buffer::get))
                val sender = decodeUtf8(ByteArray(senderLength).also(buffer::get))
                val plaintext = ByteArray(plaintextLength).also(buffer::get)
                require(BASE64URL.matches(messageId)) { "Authority MLS application inbox is malformed." }
                add(AuthorityMlsPendingReceivedApplication(messageId, sender, plaintext))
            }
        }
        var receiptBytes = 0
        val receipts = buildList {
            repeat(receiptCount) {
                require(buffer.remaining() >= 12) { "Authority MLS offline receipt ledger is truncated." }
                val messageIdLength = buffer.int
                val senderLength = buffer.int
                val hashLength = buffer.int
                receiptBytes += 12 + messageIdLength.coerceAtLeast(0) + senderLength.coerceAtLeast(0) +
                    hashLength.coerceAtLeast(0)
                require(messageIdLength in 1..MAX_MESSAGE_ID_BYTES &&
                    senderLength in 1..MAX_SENDER_CREDENTIAL_BYTES && hashLength == CIPHERTEXT_HASH_BYTES &&
                    receiptBytes <= MAX_OFFLINE_RECEIPT_BYTES &&
                    messageIdLength + senderLength + hashLength <= buffer.remaining()) {
                    "Authority MLS offline receipt ledger is malformed."
                }
                val messageId = decodeUtf8(ByteArray(messageIdLength).also(buffer::get))
                val sender = decodeUtf8(ByteArray(senderLength).also(buffer::get))
                val hash = decodeUtf8(ByteArray(hashLength).also(buffer::get))
                require(BASE64URL.matches(messageId) && BASE64URL.matches(hash)) {
                    "Authority MLS offline receipt ledger is malformed."
                }
                add(AuthorityMlsOfflineReceipt(messageId, sender, hash))
            }
        }
        require(!buffer.hasRemaining()) { "Authority MLS durable state has trailing data." }
        return AuthorityMlsDurableState(
            snapshot, sequence, nextApplicationSequence, events, messages, received, receipts,
        )
    }

    private fun decodeUtf8(value: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(value)).toString()

    private val BASE64URL = Regex("^[A-Za-z0-9_-]+$")
}
