package com.auralis.crisisconnect.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.json.JSONArray

enum class MessageType {
    TEXT,
    AUDIO,
    IMAGE
}

enum class MessageDeliveryStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ContactEntity::class,
            parentColumns = ["sessionCode"],
            childColumns = ["sessionCode"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionCode"]),
        Index(value = ["messageUuid"], unique = true)
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionCode: String,
    val messageUuid: String,
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
    val isRead: Boolean,
    val timestampMillis: Long,
    val originalTimestampMillis: Long? = null,
    val deliveryStatus: MessageDeliveryStatus? = null,
    val retryCount: Int = 0,
    val nextRetryAtMillis: Long? = null,
    val lastAttemptAtMillis: Long? = null,
    val lastError: String? = null,
    val outboundRoute: String? = null,
    val senderDisplayName: String? = null,
    val senderAddress: String? = null,
    val originVerifiedRole: String? = null,
    val originVerifiedAtMillis: Long? = null,
    val sentToRecipients: String = EMPTY_RECIPIENT_TRACKING_JSON,
    val deliveredToRecipients: String = EMPTY_RECIPIENT_TRACKING_JSON,
    val readByRecipients: String = EMPTY_RECIPIENT_TRACKING_JSON
)

internal const val EMPTY_RECIPIENT_TRACKING_JSON = "[]"
private const val MAX_TRACKED_RECIPIENTS = 128
private const val MAX_TRACKED_RECIPIENT_LENGTH = 48
private const val MAX_VERIFIED_ROLE_LENGTH = 24

internal fun normalizeStoredSenderDisplayName(raw: String?): String? {
    return normalizeTrackedRecipient(raw)
}

internal fun normalizeStoredSenderAddress(raw: String?): String? {
    val normalized = normalizeMacAddress(raw)
    return normalized.takeIf { it.isNotEmpty() }
}

internal fun normalizeStoredVerifiedRole(raw: String?): String? {
    return raw
        ?.trim()
        ?.take(MAX_VERIFIED_ROLE_LENGTH)
        ?.takeIf { it.isNotEmpty() }
}

internal fun normalizeStoredVerifiedAtMillis(raw: Long?): Long? = raw?.takeIf { it > 0L }

internal fun normalizeTrackedRecipient(raw: String?): String? {
    return raw
        ?.trim()
        ?.take(MAX_TRACKED_RECIPIENT_LENGTH)
        ?.takeIf { it.isNotEmpty() }
}

internal fun mergeTrackedRecipients(
    existing: Collection<String>,
    additions: Collection<String>
): List<String> {
    if (existing.isEmpty() && additions.isEmpty()) {
        return emptyList()
    }
    val merged = LinkedHashSet<String>(
        (existing.size + additions.size).coerceAtMost(MAX_TRACKED_RECIPIENTS)
    )
    existing.forEach { value ->
        if (merged.size >= MAX_TRACKED_RECIPIENTS) {
            return@forEach
        }
        normalizeTrackedRecipient(value)?.let { normalized ->
            merged += normalized
        }
    }
    additions.forEach { value ->
        if (merged.size >= MAX_TRACKED_RECIPIENTS) {
            return@forEach
        }
        normalizeTrackedRecipient(value)?.let { normalized ->
            merged += normalized
        }
    }
    return merged.toList()
}

internal fun decodeTrackedRecipients(raw: String?): List<String> {
    if (raw.isNullOrBlank()) {
        return emptyList()
    }
    val values = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    if (values.length() == 0) {
        return emptyList()
    }
    val normalized = LinkedHashSet<String>(values.length().coerceAtMost(MAX_TRACKED_RECIPIENTS))
    for (index in 0 until values.length()) {
        if (normalized.size >= MAX_TRACKED_RECIPIENTS) {
            break
        }
        val entry = values.opt(index) as? String ?: continue
        normalizeTrackedRecipient(entry)?.let { recipient ->
            normalized += recipient
        }
    }
    return normalized.toList()
}

internal fun encodeTrackedRecipients(values: Collection<String>): String {
    val normalized = mergeTrackedRecipients(emptyList(), values)
    if (normalized.isEmpty()) {
        return EMPTY_RECIPIENT_TRACKING_JSON
    }
    return JSONArray(normalized).toString()
}

fun MessageEntity.toMessage(): ChatMessage = ChatMessage(
    id = id,
    sessionCode = sessionCode,
    messageUuid = messageUuid,
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
    isRead = isRead,
    timestampMillis = timestampMillis,
    originalTimestampMillis = originalTimestampMillis,
    deliveryStatus = deliveryStatus,
    retryCount = retryCount,
    nextRetryAtMillis = nextRetryAtMillis,
    lastAttemptAtMillis = lastAttemptAtMillis,
    lastError = lastError,
    outboundRoute = outboundRoute,
    senderDisplayName = normalizeStoredSenderDisplayName(senderDisplayName),
    senderAddress = normalizeStoredSenderAddress(senderAddress),
    originVerifiedRole = normalizeStoredVerifiedRole(originVerifiedRole),
    originVerifiedAtMillis = normalizeStoredVerifiedAtMillis(originVerifiedAtMillis),
    sentTo = decodeTrackedRecipients(sentToRecipients),
    deliveredTo = decodeTrackedRecipients(deliveredToRecipients),
    readBy = decodeTrackedRecipients(readByRecipients)
)

fun ChatMessage.toEntity(): MessageEntity = MessageEntity(
    id = id,
    sessionCode = sessionCode,
    messageUuid = messageUuid,
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
    isRead = isRead,
    timestampMillis = timestampMillis,
    originalTimestampMillis = originalTimestampMillis,
    deliveryStatus = deliveryStatus,
    retryCount = retryCount,
    nextRetryAtMillis = nextRetryAtMillis,
    lastAttemptAtMillis = lastAttemptAtMillis,
    lastError = lastError,
    outboundRoute = outboundRoute,
    senderDisplayName = normalizeStoredSenderDisplayName(senderDisplayName),
    senderAddress = normalizeStoredSenderAddress(senderAddress),
    originVerifiedRole = normalizeStoredVerifiedRole(originVerifiedRole),
    originVerifiedAtMillis = normalizeStoredVerifiedAtMillis(originVerifiedAtMillis),
    sentToRecipients = encodeTrackedRecipients(sentTo),
    deliveredToRecipients = encodeTrackedRecipients(deliveredTo),
    readByRecipients = encodeTrackedRecipients(readBy)
)
