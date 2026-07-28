package com.auralis.crisisconnect.data

import androidx.room.Entity

/**
 * Per-thread read cursor for authority/cross-panel conversations: the newest message timestamp the user
 * has already seen in a given (channel, peer) thread. The home list treats a conversation as unread when
 * its last (incoming) message is newer than this cursor. Kept in its own table so refreshing the
 * conversation-preview cache never wipes the user's read state.
 */
@Entity(tableName = "authority_channel_reads", primaryKeys = ["channelId", "peerUid"])
data class AuthorityChannelReadEntity(
    val channelId: String,
    val peerUid: String,
    val lastReadAtMillis: Long,
)
