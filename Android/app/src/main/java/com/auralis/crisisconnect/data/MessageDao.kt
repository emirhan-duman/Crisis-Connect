package com.auralis.crisisconnect.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class UnreadCountProjection(
    val sessionCode: String,
    @ColumnInfo(name = "unreadCount") val unreadCount: Int
)

data class SessionMessageCountProjection(
    val sessionCode: String,
    @ColumnInfo(name = "messageCount") val messageCount: Int
)

data class SessionFirstMessageProjection(
    val sessionCode: String,
    @ColumnInfo(name = "firstMessageAtMillis") val firstMessageAtMillis: Long
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE sessionCode = :sessionCode ORDER BY timestampMillis ASC")
    fun getMessagesForSession(sessionCode: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT * FROM messages WHERE sessionCode = :sessionCode " +
            "ORDER BY timestampMillis DESC LIMIT :limit"
    )
    fun getRecentMessagesForSession(sessionCode: String, limit: Int): List<MessageEntity>

    @Query("UPDATE messages SET isRead = 1 WHERE messageUuid IN (:messageUuids)")
    suspend fun markMessagesRead(messageUuids: List<String>)

    @Query("SELECT * FROM messages WHERE messageUuid = :messageUuid LIMIT 1")
    suspend fun getMessageByUuid(messageUuid: String): MessageEntity?

    /** Removes only an already-consumed internal AuthorityChat MLS transport row. */
    @Query(
        "DELETE FROM messages WHERE sessionCode = :sessionCode AND messageUuid = :messageUuid " +
            "AND isLocal = 0 AND messageType = 'TEXT' AND text LIKE 'CC_AMLS2:%'"
    )
    suspend fun deleteInboundAuthorityMlsTransportRow(sessionCode: String, messageUuid: String): Int

    @Query("UPDATE messages SET sessionCode = :newSessionCode WHERE sessionCode = :oldSessionCode")
    suspend fun migrateSessionCode(oldSessionCode: String, newSessionCode: String)

    @Query(
        "UPDATE messages SET " +
            "sessionCode = :sessionCode, " +
            "text = :text, " +
            "messageType = 'TEXT', " +
            "audioFilePath = NULL, " +
            "audioDurationMillis = NULL, " +
            "imageFilePath = NULL, " +
            "imageThumbnailPath = NULL, " +
            "imageWidth = NULL, " +
            "imageHeight = NULL, " +
            "imageMimeType = NULL, " +
            "isLocal = 1, " +
            "isRead = CASE WHEN :isRead THEN 1 ELSE isRead END, " +
            "deliveryStatus = CASE " +
            "  WHEN :deliveryStatus IN ('QUEUED','SENDING','SENT') " +
            "    AND deliveryStatus IN ('DELIVERED','READ') " +
            "  THEN deliveryStatus ELSE :deliveryStatus END, " +
            "retryCount = :retryCount, " +
            "nextRetryAtMillis = :nextRetryAtMillis, " +
            "lastAttemptAtMillis = :lastAttemptAtMillis, " +
            "lastError = :lastError, " +
            "outboundRoute = :outboundRoute, " +
            "senderDisplayName = NULL, " +
            "senderAddress = NULL, " +
            "originVerifiedRole = NULL, " +
            "originVerifiedAtMillis = NULL " +
            "WHERE isLocal = 1 AND messageUuid = :messageUuid"
    )
    suspend fun updateLocalTextMessageByUuid(
        sessionCode: String,
        messageUuid: String,
        text: String,
        isRead: Boolean,
        deliveryStatus: MessageDeliveryStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ): Int

    @Transaction
    suspend fun upsertLocalTextMessageAtomic(
        sessionCode: String,
        messageUuid: String,
        text: String,
        timestampMillis: Long,
        originalTimestampMillis: Long?,
        deliveryStatus: MessageDeliveryStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ) {
        val isRead = deliveryStatus == MessageDeliveryStatus.READ
        val updated = updateLocalTextMessageByUuid(
            sessionCode = sessionCode,
            messageUuid = messageUuid,
            text = text,
            isRead = isRead,
            deliveryStatus = deliveryStatus,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
        if (updated > 0) {
            return
        }

        val inserted = insertIgnore(
            MessageEntity(
                sessionCode = sessionCode,
                messageUuid = messageUuid,
                text = text,
                messageType = MessageType.TEXT,
                audioFilePath = null,
                audioDurationMillis = null,
                imageFilePath = null,
                imageThumbnailPath = null,
                imageWidth = null,
                imageHeight = null,
                imageMimeType = null,
                isLocal = true,
                isRead = isRead,
                timestampMillis = timestampMillis,
                originalTimestampMillis = originalTimestampMillis,
                deliveryStatus = deliveryStatus,
                retryCount = retryCount,
                nextRetryAtMillis = nextRetryAtMillis,
                lastAttemptAtMillis = lastAttemptAtMillis,
                lastError = lastError,
                outboundRoute = outboundRoute
            )
        )

        if (inserted == -1L) {
            updateLocalTextMessageByUuid(
                sessionCode = sessionCode,
                messageUuid = messageUuid,
                text = text,
                isRead = isRead,
                deliveryStatus = deliveryStatus,
                retryCount = retryCount,
                nextRetryAtMillis = nextRetryAtMillis,
                lastAttemptAtMillis = lastAttemptAtMillis,
                lastError = lastError,
                outboundRoute = outboundRoute
            )
        }
    }

    @Query(
        "UPDATE messages SET " +
            "deliveryStatus = :deliveryStatus, " +
            "retryCount = :retryCount, " +
            "nextRetryAtMillis = :nextRetryAtMillis, " +
            "lastAttemptAtMillis = :lastAttemptAtMillis, " +
            "lastError = :lastError, " +
            "outboundRoute = :outboundRoute " +
            "WHERE messageUuid = :messageUuid " +
            "AND (deliveryStatus IS NULL OR NOT (" +
            "  (:deliveryStatus IN ('QUEUED','SENDING','SENT') AND deliveryStatus IN ('DELIVERED','READ'))" +
            "))"
    )
    suspend fun updateDeliveryState(
        messageUuid: String,
        deliveryStatus: MessageDeliveryStatus?,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    )

    @Query(
        "UPDATE messages SET " +
            "senderDisplayName = COALESCE(:senderDisplayName, senderDisplayName), " +
            "senderAddress = COALESCE(:senderAddress, senderAddress), " +
            "originVerifiedRole = COALESCE(:originVerifiedRole, originVerifiedRole), " +
            "originVerifiedAtMillis = COALESCE(:originVerifiedAtMillis, originVerifiedAtMillis) " +
            "WHERE isLocal = 0 AND messageUuid = :messageUuid"
    )
    suspend fun updateRemoteMessageMetadata(
        messageUuid: String,
        senderDisplayName: String?,
        senderAddress: String?,
        originVerifiedRole: String?,
        originVerifiedAtMillis: Long?
    )

    @Query(
        "UPDATE messages SET deliveryStatus = 'DELIVERED' " +
            "WHERE isLocal = 1 AND messageUuid IN (:messageUuids) " +
            "AND (deliveryStatus IS NULL OR deliveryStatus NOT IN ('READ', 'FAILED'))"
    )
    suspend fun markLocalMessagesDelivered(messageUuids: List<String>)

    @Query(
        "UPDATE messages SET deliveryStatus = 'READ', isRead = 1 " +
            "WHERE isLocal = 1 AND messageUuid IN (:messageUuids) " +
            "AND (deliveryStatus IS NULL OR deliveryStatus != 'FAILED')"
    )
    suspend fun markLocalMessagesRead(messageUuids: List<String>)

    @Query(
        "UPDATE messages SET " +
            "sentToRecipients = :sentToRecipients " +
            "WHERE isLocal = 1 AND messageUuid = :messageUuid"
    )
    suspend fun updateLocalSentToRecipients(
        messageUuid: String,
        sentToRecipients: String
    )

    @Query(
        "UPDATE messages SET " +
            "deliveredToRecipients = :deliveredToRecipients, " +
            "readByRecipients = :readByRecipients " +
            "WHERE isLocal = 1 AND messageUuid = :messageUuid"
    )
    suspend fun updateLocalReceiptTracking(
        messageUuid: String,
        deliveredToRecipients: String,
        readByRecipients: String
    )

    @Query(
        "UPDATE messages SET deliveryStatus = 'DELIVERED' " +
            "WHERE sessionCode = :sessionCode AND isLocal = 1 AND deliveryStatus IN ('SENT', 'SENDING')"
    )
    suspend fun markAllSentMessagesDelivered(sessionCode: String)

    @Query(
        "UPDATE messages SET deliveryStatus = 'READ', isRead = 1 " +
            "WHERE sessionCode = :sessionCode AND isLocal = 1 " +
            "AND (deliveryStatus IS NULL OR deliveryStatus != 'FAILED')"
    )
    suspend fun markAllLocalMessagesRead(sessionCode: String)

    @Query(
        "UPDATE messages SET isRead = 1 " +
            "WHERE sessionCode = :sessionCode AND isLocal = 0 AND isRead = 0"
    )
    suspend fun markAllRemoteMessagesRead(sessionCode: String)

    @Query(
        "SELECT * FROM messages WHERE id IN (SELECT MAX(id) FROM messages GROUP BY sessionCode)"
    )
    fun observeLatestMessages(): Flow<List<MessageEntity>>

    @Query(
        "SELECT sessionCode, COUNT(*) AS unreadCount FROM messages " +
            "WHERE isLocal = 0 AND isRead = 0 GROUP BY sessionCode"
    )
    fun observeUnreadCounts(): Flow<List<UnreadCountProjection>>

    @Query("SELECT * FROM messages ORDER BY timestampMillis DESC")
    fun observeMessagesNewestFirst(): Flow<List<MessageEntity>>

    @Query("SELECT sessionCode, COUNT(*) AS messageCount FROM messages GROUP BY sessionCode")
    suspend fun getSessionMessageCounts(): List<SessionMessageCountProjection>

    @Query(
        "SELECT sessionCode, MIN(timestampMillis) AS firstMessageAtMillis FROM messages " +
            "GROUP BY sessionCode"
    )
    suspend fun getSessionFirstMessageTimes(): List<SessionFirstMessageProjection>

    @Query(
        "UPDATE messages SET text = :text " +
            "WHERE messageUuid = :messageUuid AND messageType = 'SOS_ALERT'"
    )
    suspend fun updateSosAlertText(messageUuid: String, text: String): Int

    @Query("SELECT MIN(timestampMillis) FROM messages WHERE sessionCode = :sessionCode")
    suspend fun getFirstMessageTimeForSession(sessionCode: String): Long?
}
