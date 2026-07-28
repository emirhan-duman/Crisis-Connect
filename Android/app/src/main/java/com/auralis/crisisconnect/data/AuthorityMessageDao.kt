package com.auralis.crisisconnect.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Projection: one unread cross-panel conversation, keyed by (channel, peer). */
data class AuthorityUnreadKey(val channelId: String, val peerUid: String)

@Dao
interface AuthorityMessageDao {

    /** Write-through from the Firestore listener; upserts so re-delivered docs replace in place. */
    @Upsert
    suspend fun upsertAll(messages: List<AuthorityMessageEntity>)

    /** Offline-first source for one 1:1 thread, oldest → newest. */
    @Query(
        "SELECT * FROM authority_messages WHERE channelId = :channelId AND peerUid = :peerUid " +
            "ORDER BY createdAtMillis ASC",
    )
    fun observeThread(channelId: String, peerUid: String): Flow<List<AuthorityMessageEntity>>

    /** Latest cached message per (channel, peer) — feeds an offline home-list preview. */
    @Query(
        "SELECT * FROM authority_messages AS m WHERE createdAtMillis = (" +
            "SELECT MAX(createdAtMillis) FROM authority_messages " +
            "WHERE channelId = m.channelId AND peerUid = m.peerUid" +
            ") GROUP BY channelId, peerUid",
    )
    fun observeLatestPerPeer(): Flow<List<AuthorityMessageEntity>>

    /** Latest cached message for a single (channel, peer), or null when nothing is cached yet. */
    @Query(
        "SELECT * FROM authority_messages WHERE channelId = :channelId AND peerUid = :peerUid " +
            "ORDER BY createdAtMillis DESC LIMIT 1",
    )
    suspend fun latestFor(channelId: String, peerUid: String): AuthorityMessageEntity?

    // --- Conversation previews (home list, offline-first) ---

    /** Offline-first source for the home list, newest conversation first. */
    @Query("SELECT * FROM authority_conversations ORDER BY lastAtMillis DESC")
    fun observeConversations(): Flow<List<AuthorityConversationEntity>>

    @Upsert
    suspend fun upsertConversations(rows: List<AuthorityConversationEntity>)

    @Query("DELETE FROM authority_conversations")
    suspend fun clearConversations()

    // --- Read cursors + unread (home list badges) ---

    /** Advances a thread's read cursor to [row]'s timestamp when its thread is open. */
    @Upsert
    suspend fun upsertRead(row: AuthorityChannelReadEntity)

    /**
     * The (channel, peer) conversations whose last message is an unread incoming one: it was sent by the
     * peer (senderUid == peerUid) and is newer than the thread's read cursor (0 when never opened).
     */
    @Query(
        "SELECT c.channelId AS channelId, c.peerUid AS peerUid " +
            "FROM authority_conversations c " +
            "LEFT JOIN authority_channel_reads r " +
            "ON c.channelId = r.channelId AND c.peerUid = r.peerUid " +
            "WHERE c.lastSenderUid = c.peerUid " +
            "AND c.lastAtMillis > COALESCE(r.lastReadAtMillis, 0)",
    )
    fun observeUnreadKeys(): Flow<List<AuthorityUnreadKey>>

    /**
     * Swaps the cached conversation list for [rows] in one transaction, so the [observeConversations]
     * Flow emits exactly once (the final state) — no empty flash while refreshing from the roster.
     */
    @Transaction
    suspend fun replaceConversations(rows: List<AuthorityConversationEntity>) {
        clearConversations()
        if (rows.isNotEmpty()) upsertConversations(rows)
    }
}
