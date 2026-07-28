package com.auralis.crisisconnect.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.auralis.crisisconnect.messaging.ChannelAttachment
import com.auralis.crisisconnect.messaging.HierarchyMessage

/**
 * Offline cache of one authority/cross-panel channel message (byte-compatible with the web dashboard's
 * secure channels). The Firestore listener writes decrypted messages through into this table so the
 * thread — and, later, the home list — render instantly and keep working with no connectivity, like a
 * disaster app should. Blobs stay encrypted in Storage; only the (already-decrypted) descriptor metadata
 * is cached here, so tapping media still fetches on demand.
 *
 * [messageUuid] is the Firestore doc id, so re-delivering the same message just upserts in place.
 * [peerUid] is the other party in this 1:1 exchange (whichever of sender/recipient is not me), which is
 * what threads and the home list key on.
 */
@Entity(
    tableName = "authority_messages",
    indices = [
        Index(value = ["channelId", "peerUid"]),
        Index(value = ["channelId"]),
    ],
)
data class AuthorityMessageEntity(
    @PrimaryKey val messageUuid: String,
    val channelId: String,
    val peerUid: String,
    val senderUid: String,
    val senderName: String,
    val recipientUid: String,
    val recipientName: String,
    val text: String,
    val createdAtMillis: Long,
    val attachmentsJson: String,
    // Original Bluetooth-bridge uuid on backfilled docs — lets the thread dedupe the BT copy.
    val clientUuid: String = "",
)

/** Maps a live [HierarchyMessage] to its cache row, computing the thread peer relative to [myUid]. */
fun HierarchyMessage.toAuthorityEntity(channelId: String, myUid: String): AuthorityMessageEntity =
    AuthorityMessageEntity(
        messageUuid = id,
        channelId = channelId,
        peerUid = if (senderUid == myUid) recipientUid else senderUid,
        senderUid = senderUid,
        senderName = senderName,
        recipientUid = recipientUid,
        recipientName = recipientName,
        text = text,
        createdAtMillis = atMillis,
        attachmentsJson = ChannelAttachment.toJsonArray(attachments),
        clientUuid = clientUuid,
    )

/** Rebuilds the [HierarchyMessage] the UI renders from a cached row. */
fun AuthorityMessageEntity.toHierarchyMessage(): HierarchyMessage =
    HierarchyMessage(
        id = messageUuid,
        senderUid = senderUid,
        senderName = senderName,
        recipientUid = recipientUid,
        recipientName = recipientName,
        text = text,
        atMillis = createdAtMillis,
        attachments = ChannelAttachment.fromJsonArray(attachmentsJson),
        clientUuid = clientUuid,
    )
