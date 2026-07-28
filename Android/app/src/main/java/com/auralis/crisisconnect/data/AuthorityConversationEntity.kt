package com.auralis.crisisconnect.data

import androidx.room.Entity
import com.auralis.crisisconnect.screens.authority.ChannelConversation

/**
 * Denormalized last-message preview for one authority/cross-panel conversation — the "conversations"
 * table that backs the home list, kept separate from the per-message [AuthorityMessageEntity] history.
 * Refreshed from the roster whenever online and read back offline, so the chat list survives with no
 * connectivity (peer name + agency tag + last preview all come from here).
 */
@Entity(tableName = "authority_conversations", primaryKeys = ["channelId", "peerUid"])
data class AuthorityConversationEntity(
    val channelId: String,
    val peerUid: String,
    val peerName: String,
    val peerPanelName: String,
    val group: String,
    val lastText: String,
    val lastAtMillis: Long,
    val lastSenderUid: String,
    val lastAttachmentKind: String,
    val peerRole: String,
)

fun ChannelConversation.toAuthorityConversationEntity(): AuthorityConversationEntity =
    AuthorityConversationEntity(
        channelId = channelId,
        peerUid = peerUid,
        peerName = peerName,
        peerPanelName = peerPanelName,
        group = group,
        lastText = lastText,
        lastAtMillis = lastAtMillis,
        lastSenderUid = lastSenderUid,
        lastAttachmentKind = lastAttachmentKind,
        peerRole = peerRole,
    )

fun AuthorityConversationEntity.toChannelConversation(): ChannelConversation =
    ChannelConversation(
        channelId = channelId,
        peerUid = peerUid,
        peerName = peerName,
        peerPanelName = peerPanelName,
        group = group,
        lastText = lastText,
        lastAtMillis = lastAtMillis,
        lastSenderUid = lastSenderUid,
        lastAttachmentKind = lastAttachmentKind,
        peerRole = peerRole,
    )
