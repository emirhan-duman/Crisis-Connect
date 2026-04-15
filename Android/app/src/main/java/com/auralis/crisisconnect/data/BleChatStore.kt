package com.auralis.crisisconnect.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * In-memory store for BLE chat transcripts. Now paired with Room persistence so we can rehydrate
 * sessions after process death.
 */
object BleChatStore {
    private val lock = Any()
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<List<BleChatMessage>>>()

    private fun normalizeOriginalTimestampMillis(
        originalTimestampMillis: Long?,
        receivedTimestampMillis: Long
    ): Long? {
        val candidate = originalTimestampMillis?.takeIf { it > 0L } ?: return null
        return candidate.takeIf { it <= receivedTimestampMillis }
    }

    fun observeMessages(sessionCode: String): StateFlow<List<BleChatMessage>> =
        obtainSessionFlow(canonicalSessionCode(sessionCode)).asStateFlow()

    fun ensureSession(sessionCode: String) {
        obtainSessionFlow(canonicalSessionCode(sessionCode))
    }

    fun replaceMessages(sessionCode: String, messages: List<BleChatMessage>) {
        obtainSessionFlow(canonicalSessionCode(sessionCode)).value = messages
    }

    fun appendLocalMessage(
        sessionCode: String,
        text: String,
        status: BleMessageStatus = BleMessageStatus.SENT,
        messageId: String? = null,
        retryCount: Int = 0,
        nextRetryAtMillis: Long? = null,
        lastError: String? = null,
        outboundRoute: String? = null
    ): String {
        return appendMessage(
            canonicalSessionCode(sessionCode),
            text,
            isLocal = true,
            statusOverride = status,
            messageId = messageId,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }

    fun appendRemoteMessage(
        sessionCode: String,
        text: String,
        messageId: String? = null,
        originalTimestampMillis: Long? = null,
        receivedAtMillis: Long = System.currentTimeMillis()
    ): String {
        return appendMessage(
            sessionCode = canonicalSessionCode(sessionCode),
            text = text,
            isLocal = false,
            messageId = messageId,
            timestampMillis = receivedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            originalTimestampMillis = originalTimestampMillis
        )
    }

    fun appendLocalAudioMessage(
        sessionCode: String,
        audioFilePath: String,
        audioDurationMillis: Long?,
        status: BleMessageStatus = BleMessageStatus.SENT,
        messageId: String? = null
    ): String {
        return appendMessage(
            sessionCode = canonicalSessionCode(sessionCode),
            text = "",
            isLocal = true,
            statusOverride = status,
            messageId = messageId,
            messageType = MessageType.AUDIO,
            audioFilePath = audioFilePath,
            audioDurationMillis = audioDurationMillis
        )
    }

    fun appendLocalImageMessage(
        sessionCode: String,
        imageFilePath: String,
        imageThumbnailPath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String?,
        status: BleMessageStatus = BleMessageStatus.SENT,
        messageId: String? = null
    ): String {
        return appendMessage(
            sessionCode = canonicalSessionCode(sessionCode),
            text = "",
            isLocal = true,
            statusOverride = status,
            messageId = messageId,
            messageType = MessageType.IMAGE,
            imageFilePath = imageFilePath,
            imageThumbnailPath = imageThumbnailPath,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            imageMimeType = imageMimeType
        )
    }

    fun appendRemoteImageMessage(
        sessionCode: String,
        imageFilePath: String,
        imageThumbnailPath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String?,
        messageId: String? = null,
        originalTimestampMillis: Long? = null,
        receivedAtMillis: Long = System.currentTimeMillis()
    ): String {
        return appendMessage(
            sessionCode = canonicalSessionCode(sessionCode),
            text = "",
            isLocal = false,
            messageId = messageId,
            messageType = MessageType.IMAGE,
            imageFilePath = imageFilePath,
            imageThumbnailPath = imageThumbnailPath,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            imageMimeType = imageMimeType,
            timestampMillis = receivedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            originalTimestampMillis = originalTimestampMillis
        )
    }

    fun appendRemoteAudioMessage(
        sessionCode: String,
        audioFilePath: String,
        audioDurationMillis: Long?,
        messageId: String? = null
    ): String {
        return appendMessage(
            sessionCode = canonicalSessionCode(sessionCode),
            text = "",
            isLocal = false,
            messageId = messageId,
            messageType = MessageType.AUDIO,
            audioFilePath = audioFilePath,
            audioDurationMillis = audioDurationMillis
        )
    }

    private fun appendMessage(
        sessionCode: String,
        text: String,
        isLocal: Boolean,
        statusOverride: BleMessageStatus? = null,
        messageId: String? = null,
        messageType: MessageType = MessageType.TEXT,
        audioFilePath: String? = null,
        audioDurationMillis: Long? = null,
        imageFilePath: String? = null,
        imageThumbnailPath: String? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
        imageMimeType: String? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        originalTimestampMillis: Long? = null,
        retryCount: Int = 0,
        nextRetryAtMillis: Long? = null,
        lastError: String? = null,
        outboundRoute: String? = null
    ): String {
        val flow = obtainSessionFlow(sessionCode)
        val message = BleChatMessage(
            id = messageId ?: UUID.randomUUID().toString(),
            sessionCode = sessionCode,
            text = text,
            messageType = messageType,
            audioFilePath = audioFilePath,
            audioDurationMillis = audioDurationMillis,
            imageFilePath = imageFilePath,
            imageThumbnailPath = imageThumbnailPath,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            imageMimeType = imageMimeType,
            isLocal = isLocal,
            timestampMillis = timestampMillis,
            originalTimestampMillis = normalizeOriginalTimestampMillis(
                originalTimestampMillis = originalTimestampMillis,
                receivedTimestampMillis = timestampMillis
            ),
            status = statusOverride ?: if (isLocal) BleMessageStatus.SENT else BleMessageStatus.DELIVERED,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
        flow.update { current ->
            if (current.any { it.id == message.id }) current else current + message
        }
        return message.id
    }

    fun getQueuedLocalMessages(sessionCode: String, nowMillis: Long = System.currentTimeMillis()): List<BleChatMessage> {
        return obtainSessionFlow(canonicalSessionCode(sessionCode)).value
            .filter { message ->
                message.isLocal &&
                    message.status == BleMessageStatus.QUEUED &&
                    (message.nextRetryAtMillis == null || message.nextRetryAtMillis <= nowMillis)
            }
            .sortedBy { it.timestampMillis }
    }

    fun updateLocalMessageState(
        sessionCode: String,
        messageId: String,
        status: BleMessageStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ) {
        val flow = obtainSessionFlow(canonicalSessionCode(sessionCode))
        flow.update { current ->
            current.map { message ->
                if (message.id == messageId && message.isLocal) {
                    message.copy(
                        status = status,
                        retryCount = retryCount,
                        nextRetryAtMillis = nextRetryAtMillis,
                        lastError = lastError,
                        outboundRoute = outboundRoute
                    )
                } else {
                    message
                }
            }
        }
    }

    fun markDelivered(sessionCode: String, messageIds: Collection<String>) {
        if (messageIds.isEmpty()) {
            return
        }
        val targetIds = messageIds.toSet()
        val flow = obtainSessionFlow(canonicalSessionCode(sessionCode))
        flow.update { current ->
            current.map { message ->
                if (message.isLocal && message.id in targetIds && message.status != BleMessageStatus.READ) {
                    message.copy(status = BleMessageStatus.DELIVERED)
                } else {
                    message
                }
            }
        }
    }

    fun markDeliveredForAllSent(sessionCode: String) {
        val flow = obtainSessionFlow(canonicalSessionCode(sessionCode))
        flow.update { current ->
            current.map { message ->
                if (
                    message.isLocal &&
                    (message.status == BleMessageStatus.SENT || message.status == BleMessageStatus.SENDING)
                ) {
                    message.copy(status = BleMessageStatus.DELIVERED)
                } else {
                    message
                }
            }
        }
    }

    fun markRead(sessionCode: String, messageIds: Collection<String>) {
        if (messageIds.isEmpty()) {
            return
        }
        val targetIds = messageIds.toSet()
        val flow = obtainSessionFlow(canonicalSessionCode(sessionCode))
        flow.update { current ->
            current.map { message ->
                if (message.isLocal && message.id in targetIds && message.status != BleMessageStatus.READ) {
                    message.copy(status = BleMessageStatus.READ)
                } else {
                    message
                }
            }
        }
    }

    fun markReadAllLocal(sessionCode: String) {
        val flow = obtainSessionFlow(canonicalSessionCode(sessionCode))
        flow.update { current ->
            current.map { message ->
                if (message.isLocal && message.status != BleMessageStatus.READ) {
                    message.copy(status = BleMessageStatus.READ)
                } else {
                    message
                }
            }
        }
    }

    fun clearSession(sessionCode: String) {
        val flow = synchronized(lock) { sessionFlows.remove(sessionCode) }
        flow?.update { emptyList() }
    }

    fun clear() {
        val flows = synchronized(lock) {
            val current = sessionFlows.values.toList()
            sessionFlows.clear()
            current
        }
        flows.forEach { flow -> flow.update { emptyList() } }
    }

    fun currentMessages(sessionCode: String): List<BleChatMessage> {
        return obtainSessionFlow(canonicalSessionCode(sessionCode)).value
    }

    private fun obtainSessionFlow(sessionCode: String): MutableStateFlow<List<BleChatMessage>> =
        synchronized(lock) {
            sessionFlows.getOrPut(sessionCode) { MutableStateFlow(emptyList()) }
        }

    private fun canonicalSessionCode(sessionCode: String): String {
        return BleSessionResolver.normalizeSessionCode(sessionCode) ?: sessionCode
    }
}

fun ChatMessage.toBleChatMessage(statusOverride: BleMessageStatus? = null): BleChatMessage {
    val mappedStatus = statusOverride ?: if (isLocal) {
        when (deliveryStatus) {
            MessageDeliveryStatus.QUEUED -> BleMessageStatus.QUEUED
            MessageDeliveryStatus.SENDING -> BleMessageStatus.SENDING
            MessageDeliveryStatus.SENT -> BleMessageStatus.SENT
            MessageDeliveryStatus.DELIVERED -> BleMessageStatus.DELIVERED
            MessageDeliveryStatus.READ -> BleMessageStatus.READ
            MessageDeliveryStatus.FAILED -> BleMessageStatus.FAILED
            null -> if (isRead) BleMessageStatus.READ else BleMessageStatus.SENT
        }
    } else {
        BleMessageStatus.DELIVERED
    }
    return BleChatMessage(
        id = messageUuid,
        sessionCode = sessionCode,
        text = text,
        messageType = messageType,
        audioFilePath = audioFilePath,
        audioDurationMillis = audioDurationMillis,
        imageFilePath = imageFilePath,
        imageThumbnailPath = imageThumbnailPath,
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        imageMimeType = imageMimeType,
        isLocal = isLocal,
        timestampMillis = timestampMillis,
        originalTimestampMillis = originalTimestampMillis,
        status = mappedStatus,
        retryCount = retryCount,
        nextRetryAtMillis = nextRetryAtMillis,
        lastError = lastError,
        outboundRoute = outboundRoute
    )
}

data class BleChatMessage(
    val id: String,
    val sessionCode: String,
    val text: String,
    val messageType: MessageType = MessageType.TEXT,
    val audioFilePath: String? = null,
    val audioDurationMillis: Long? = null,
    val imageFilePath: String? = null,
    val imageThumbnailPath: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val imageMimeType: String? = null,
    val isLocal: Boolean,
    val timestampMillis: Long,
    val originalTimestampMillis: Long? = null,
    val status: BleMessageStatus = BleMessageStatus.SENT,
    val retryCount: Int = 0,
    val nextRetryAtMillis: Long? = null,
    val lastError: String? = null,
    val outboundRoute: String? = null
)

enum class BleMessageStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
