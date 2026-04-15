package com.auralis.crisisconnect.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

data class ChatMessage(
    val id: Long,
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
    val sentTo: List<String> = emptyList(),
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList(),
    val senderDisplayName: String? = null,
    val senderAddress: String? = null,
    val originVerifiedRole: String? = null,
    val originVerifiedAtMillis: Long? = null
)

private fun messageDao(context: Context) = AppDatabase.getInstance(context).messageDao()

private const val VOICE_MESSAGES_DIR = "voice_messages"
private const val IMAGE_MESSAGES_DIR = "image_messages"
private const val IMAGE_THUMBNAIL_DIR = "image_thumbnails"
private const val VOICE_MIME_OGG = "audio/ogg"
private const val VOICE_MIME_OPUS = "audio/opus"

private fun normalizeOriginalTimestampMillis(
    originalTimestampMillis: Long?,
    receivedTimestampMillis: Long
): Long? {
    val candidate = originalTimestampMillis?.takeIf { it > 0L } ?: return null
    return candidate.takeIf { it <= receivedTimestampMillis }
}

fun voiceMessageFileName(uuid: String, mimeType: String?): String {
    val normalized = mimeType?.trim()?.lowercase()
    val isOggLike = normalized?.startsWith(VOICE_MIME_OGG) == true ||
        normalized?.contains("opus") == true ||
        normalized == VOICE_MIME_OPUS
    val extension = if (isOggLike) "ogg" else "m4a"
    return "$uuid.$extension"
}

fun voiceMessageDirectory(context: Context): File {
    val directory = File(context.filesDir, VOICE_MESSAGES_DIR)
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return directory
}

fun voiceMessageFile(context: Context, fileName: String): File =
    File(voiceMessageDirectory(context), fileName)

fun imageMessageDirectory(context: Context): File {
    val directory = File(context.filesDir, IMAGE_MESSAGES_DIR)
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return directory
}

fun imageThumbnailDirectory(context: Context): File {
    val directory = File(context.filesDir, IMAGE_THUMBNAIL_DIR)
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return directory
}

fun imageMessageFile(context: Context, fileName: String): File =
    File(imageMessageDirectory(context), fileName)

fun imageThumbnailFile(context: Context, fileName: String): File =
    File(imageThumbnailDirectory(context), fileName)

fun observeMessages(context: Context, sessionCode: String): Flow<List<ChatMessage>> =
    messageDao(context).getMessagesForSession(sessionCode).map { list ->
        list.map { it.toMessage() }
    }

fun observeLatestMessages(context: Context): Flow<Map<String, ChatMessage>> =
    messageDao(context).observeLatestMessages().map { list ->
        list.associateBy({ it.sessionCode }, { it.toMessage() })
    }

fun observeUnreadCounts(context: Context): Flow<Map<String, Int>> =
    messageDao(context).observeUnreadCounts().map { entries ->
        entries.associate { it.sessionCode to it.unreadCount }
    }

fun observeMessagesNewestFirst(context: Context): Flow<List<ChatMessage>> =
    messageDao(context).observeMessagesNewestFirst().map { entities ->
        entities.map { it.toMessage() }
    }

fun loadRecentMessages(
    context: Context,
    sessionCode: String,
    limit: Int
): List<ChatMessage> {
    if (limit <= 0) {
        return emptyList()
    }
    return messageDao(context)
        .getRecentMessagesForSession(sessionCode, limit)
        .map { it.toMessage() }
}

suspend fun saveLocalMessage(context: Context, sessionCode: String, uuid: String, text: String) {
    upsertLocalTextMessage(
        context = context,
        sessionCode = sessionCode,
        uuid = uuid,
        text = text,
        deliveryStatus = MessageDeliveryStatus.SENT
    )
}

suspend fun upsertLocalTextMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    text: String,
    deliveryStatus: MessageDeliveryStatus,
    retryCount: Int = 0,
    nextRetryAtMillis: Long? = null,
    lastAttemptAtMillis: Long? = null,
    lastError: String? = null,
    outboundRoute: String? = null
) {
    withContext(Dispatchers.IO) {
        messageDao(context).upsertLocalTextMessageAtomic(
            sessionCode = sessionCode,
            messageUuid = uuid,
            text = text,
            timestampMillis = System.currentTimeMillis(),
            originalTimestampMillis = null,
            deliveryStatus = deliveryStatus,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }
}

suspend fun updateLocalMessageDeliveryState(
    context: Context,
    uuid: String,
    deliveryStatus: MessageDeliveryStatus,
    retryCount: Int,
    nextRetryAtMillis: Long?,
    lastAttemptAtMillis: Long?,
    lastError: String?,
    outboundRoute: String?
) {
    withContext(Dispatchers.IO) {
        messageDao(context).updateDeliveryState(
            messageUuid = uuid,
            deliveryStatus = deliveryStatus,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }
}

suspend fun markLocalMessagesDelivered(
    context: Context,
    sessionCode: String,
    messageUuids: Collection<String>
) {
    if (messageUuids.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        messageDao(context).markLocalMessagesDelivered(messageUuids.toList())
    }
}

suspend fun markLocalMessagesDeliveredWithRecipient(
    context: Context,
    sessionCode: String,
    messageUuids: Collection<String>,
    recipientLabel: String?
) {
    if (messageUuids.isEmpty()) {
        return
    }
    val normalizedUuids = normalizeMessageUuids(messageUuids)
    if (normalizedUuids.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        val dao = messageDao(context)
        dao.markLocalMessagesDelivered(normalizedUuids)
        val normalizedRecipient = normalizeTrackedRecipient(recipientLabel) ?: return@withContext
        normalizedUuids.forEach { messageUuid ->
            val existing = dao.getMessageByUuid(messageUuid) ?: return@forEach
            if (!existing.isLocal) {
                return@forEach
            }
            val deliveredTo = decodeTrackedRecipients(existing.deliveredToRecipients)
            val readBy = decodeTrackedRecipients(existing.readByRecipients)
            val mergedDeliveredTo = mergeTrackedRecipients(deliveredTo, listOf(normalizedRecipient))
            if (mergedDeliveredTo == deliveredTo) {
                return@forEach
            }
            dao.updateLocalReceiptTracking(
                messageUuid = messageUuid,
                deliveredToRecipients = encodeTrackedRecipients(mergedDeliveredTo),
                readByRecipients = encodeTrackedRecipients(readBy)
            )
        }
    }
}

suspend fun markLocalMessagesRead(
    context: Context,
    sessionCode: String,
    messageUuids: Collection<String>
) {
    if (messageUuids.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        messageDao(context).markLocalMessagesRead(messageUuids.toList())
    }
}

suspend fun markLocalMessagesReadWithRecipient(
    context: Context,
    sessionCode: String,
    messageUuids: Collection<String>,
    recipientLabel: String?
) {
    if (messageUuids.isEmpty()) {
        return
    }
    val normalizedUuids = normalizeMessageUuids(messageUuids)
    if (normalizedUuids.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        val dao = messageDao(context)
        dao.markLocalMessagesRead(normalizedUuids)
        val normalizedRecipient = normalizeTrackedRecipient(recipientLabel) ?: return@withContext
        normalizedUuids.forEach { messageUuid ->
            val existing = dao.getMessageByUuid(messageUuid) ?: return@forEach
            if (!existing.isLocal) {
                return@forEach
            }
            val deliveredTo = decodeTrackedRecipients(existing.deliveredToRecipients)
            val readBy = decodeTrackedRecipients(existing.readByRecipients)
            val mergedDeliveredTo = mergeTrackedRecipients(deliveredTo, listOf(normalizedRecipient))
            val mergedReadBy = mergeTrackedRecipients(readBy, listOf(normalizedRecipient))
            if (mergedDeliveredTo == deliveredTo && mergedReadBy == readBy) {
                return@forEach
            }
            dao.updateLocalReceiptTracking(
                messageUuid = messageUuid,
                deliveredToRecipients = encodeTrackedRecipients(mergedDeliveredTo),
                readByRecipients = encodeTrackedRecipients(mergedReadBy)
            )
        }
    }
}

suspend fun updateLocalMessageSentToRecipients(
    context: Context,
    uuid: String,
    recipients: Collection<String>
) {
    if (recipients.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        val dao = messageDao(context)
        val existing = dao.getMessageByUuid(uuid) ?: return@withContext
        if (!existing.isLocal) {
            return@withContext
        }
        val sentTo = decodeTrackedRecipients(existing.sentToRecipients)
        val mergedSentTo = mergeTrackedRecipients(sentTo, recipients)
        if (mergedSentTo == sentTo) {
            return@withContext
        }
        dao.updateLocalSentToRecipients(
            messageUuid = uuid,
            sentToRecipients = encodeTrackedRecipients(mergedSentTo)
        )
    }
}

suspend fun markAllSentMessagesDelivered(context: Context, sessionCode: String) {
    withContext(Dispatchers.IO) {
        messageDao(context).markAllSentMessagesDelivered(sessionCode)
    }
}

suspend fun markAllLocalMessagesRead(context: Context, sessionCode: String) {
    withContext(Dispatchers.IO) {
        messageDao(context).markAllLocalMessagesRead(sessionCode)
    }
}

suspend fun markAllRemoteMessagesRead(context: Context, sessionCode: String) {
    withContext(Dispatchers.IO) {
        messageDao(context).markAllRemoteMessagesRead(sessionCode)
    }
}

suspend fun saveRemoteMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    text: String,
    createdAtMillis: Long? = null,
    receivedAtMillis: Long = System.currentTimeMillis(),
    senderDisplayName: String? = null,
    senderAddress: String? = null,
    originVerifiedRole: String? = null,
    originVerifiedAtMillis: Long? = null
): Boolean {
    val safeReceivedAtMillis = receivedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis()
    val entity = MessageEntity(
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = text,
        messageType = MessageType.TEXT,
        audioFilePath = null,
        audioDurationMillis = null,
        imageFilePath = null,
        imageThumbnailPath = null,
        imageWidth = null,
        imageHeight = null,
        imageMimeType = null,
        isLocal = false,
        isRead = false,
        timestampMillis = safeReceivedAtMillis,
        originalTimestampMillis = normalizeOriginalTimestampMillis(
            originalTimestampMillis = createdAtMillis,
            receivedTimestampMillis = safeReceivedAtMillis
        ),
        deliveryStatus = MessageDeliveryStatus.DELIVERED,
        senderDisplayName = normalizeStoredSenderDisplayName(senderDisplayName),
        senderAddress = normalizeStoredSenderAddress(senderAddress),
        originVerifiedRole = normalizeStoredVerifiedRole(originVerifiedRole),
        originVerifiedAtMillis = normalizeStoredVerifiedAtMillis(originVerifiedAtMillis)
    )
    return withContext(Dispatchers.IO) {
        messageDao(context).insertIgnore(entity) != -1L
    }
}

suspend fun updateRemoteMessageMetadata(
    context: Context,
    uuid: String,
    senderDisplayName: String? = null,
    senderAddress: String? = null,
    originVerifiedRole: String? = null,
    originVerifiedAtMillis: Long? = null
) {
    val normalizedName = normalizeStoredSenderDisplayName(senderDisplayName)
    val normalizedAddress = normalizeStoredSenderAddress(senderAddress)
    val normalizedRole = normalizeStoredVerifiedRole(originVerifiedRole)
    val normalizedVerifiedAtMillis = normalizeStoredVerifiedAtMillis(originVerifiedAtMillis)
    if (
        normalizedName == null &&
        normalizedAddress == null &&
        normalizedRole == null &&
        normalizedVerifiedAtMillis == null
    ) {
        return
    }
    withContext(Dispatchers.IO) {
        messageDao(context).updateRemoteMessageMetadata(
            messageUuid = uuid,
            senderDisplayName = normalizedName,
            senderAddress = normalizedAddress,
            originVerifiedRole = normalizedRole,
            originVerifiedAtMillis = normalizedVerifiedAtMillis
        )
    }
}

suspend fun saveLocalAudioMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    fileName: String,
    audioDurationMillis: Long?,
    deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT
) {
    val audioPath = voiceMessageFile(context, fileName).absolutePath
    val entity = MessageEntity(
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = "",
        messageType = MessageType.AUDIO,
        audioFilePath = audioPath,
        audioDurationMillis = audioDurationMillis,
        imageFilePath = null,
        imageThumbnailPath = null,
        imageWidth = null,
        imageHeight = null,
        imageMimeType = null,
        isLocal = true,
        isRead = false,
        timestampMillis = System.currentTimeMillis(),
        deliveryStatus = deliveryStatus
    )
    withContext(Dispatchers.IO) {
        messageDao(context).insert(entity)
    }
}

suspend fun saveRemoteAudioMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    fileName: String,
    audioDurationMillis: Long?,
) {
    val audioPath = voiceMessageFile(context, fileName).absolutePath
    val entity = MessageEntity(
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = "",
        messageType = MessageType.AUDIO,
        audioFilePath = audioPath,
        audioDurationMillis = audioDurationMillis,
        imageFilePath = null,
        imageThumbnailPath = null,
        imageWidth = null,
        imageHeight = null,
        imageMimeType = null,
        isLocal = false,
        isRead = false,
        timestampMillis = System.currentTimeMillis(),
        deliveryStatus = MessageDeliveryStatus.DELIVERED
    )
    withContext(Dispatchers.IO) {
        messageDao(context).insert(entity)
    }
}

suspend fun saveLocalImageMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    fileName: String,
    thumbnailName: String?,
    width: Int?,
    height: Int?,
    mimeType: String?,
    deliveryStatus: MessageDeliveryStatus = MessageDeliveryStatus.SENT
) {
    val entity = MessageEntity(
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = "",
        messageType = MessageType.IMAGE,
        imageFilePath = imageMessageFile(context, fileName).absolutePath,
        imageThumbnailPath = thumbnailName?.let { imageThumbnailFile(context, it).absolutePath },
        imageWidth = width,
        imageHeight = height,
        imageMimeType = mimeType,
        isLocal = true,
        isRead = false,
        timestampMillis = System.currentTimeMillis(),
        deliveryStatus = deliveryStatus
    )
    withContext(Dispatchers.IO) {
        messageDao(context).insert(entity)
    }
}

suspend fun saveRemoteImageMessage(
    context: Context,
    sessionCode: String,
    uuid: String,
    fileName: String,
    thumbnailName: String?,
    width: Int?,
    height: Int?,
    mimeType: String?
) {
    val entity = MessageEntity(
        sessionCode = sessionCode,
        messageUuid = uuid,
        text = "",
        messageType = MessageType.IMAGE,
        imageFilePath = imageMessageFile(context, fileName).absolutePath,
        imageThumbnailPath = thumbnailName?.let { imageThumbnailFile(context, it).absolutePath },
        imageWidth = width,
        imageHeight = height,
        imageMimeType = mimeType,
        isLocal = false,
        isRead = false,
        timestampMillis = System.currentTimeMillis(),
        deliveryStatus = MessageDeliveryStatus.DELIVERED
    )
    withContext(Dispatchers.IO) {
        messageDao(context).insert(entity)
    }
}

suspend fun markMessagesAsRead(context: Context, messageUuids: Collection<String>) {
    if (messageUuids.isEmpty()) {
        return
    }
    withContext(Dispatchers.IO) {
        messageDao(context).markMessagesRead(messageUuids.toList())
    }
}

private fun normalizeMessageUuids(messageUuids: Collection<String>): List<String> {
    return messageUuids
        .asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}
