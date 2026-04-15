package com.auralis.crisisconnect.data

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object GattMeshChatStore {
    const val SESSION_CODE: String = "gattmesh:general"

    private const val MAX_MESSAGES = 500
    private const val MAX_MESSAGE_LENGTH = 1024
    private const val MAX_SENDER_LABEL_LENGTH = 48
    private const val MAX_VERIFIED_ROLE_LENGTH = 24

    private val lock = Any()
    private val _messages = MutableStateFlow<List<MeshChatMessage>>(emptyList())
    private val _unreadCount = MutableStateFlow(0)
    private var isChatOpen = false

    val messages: StateFlow<List<MeshChatMessage>> = _messages.asStateFlow()
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun isChatOpen(): Boolean = synchronized(lock) { isChatOpen }

    fun appendLocalMessage(
        text: String,
        messageId: String? = null,
        status: MeshMessageStatus = MeshMessageStatus.SENT,
        timestampMillis: Long = System.currentTimeMillis()
    ): MeshChatMessage {
        val normalized = text.trim().take(MAX_MESSAGE_LENGTH)
        require(normalized.isNotEmpty()) { "Gatt mesh local message cannot be blank" }
        val normalizedId = messageId
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
        val message = MeshChatMessage(
            id = normalizedId,
            text = normalized,
            senderLabel = null,
            isLocal = true,
            timestampMillis = timestampMillis,
            status = status
        )
        var existing: MeshChatMessage? = null
        _messages.update { current ->
            // Same packet can be surfaced twice (retry/UI race); keep only one bubble per message ID.
            current.firstOrNull { it.id == normalizedId }?.let { duplicate ->
                existing = duplicate
                return@update current
            }
            normalizeMessageList(current + message)
        }
        return existing ?: message
    }

    fun currentMessages(): List<MeshChatMessage> = _messages.value

    fun replaceMessages(messages: List<MeshChatMessage>) {
        if (messages.isEmpty()) {
            _messages.value = emptyList()
            return
        }
        val deduplicated = LinkedHashMap<String, MeshChatMessage>(messages.size)
        messages.forEach { message ->
            val normalizedId = message.id.trim()
            if (normalizedId.isEmpty()) {
                return@forEach
            }
            val normalizedText = message.text.trim().take(MAX_MESSAGE_LENGTH)
            if (normalizedText.isEmpty()) {
                return@forEach
            }
            deduplicated[normalizedId] = message.copy(
                id = normalizedId,
                text = normalizedText,
                senderLabel = normalizeRecipientLabel(message.senderLabel),
                sourceAddress = normalizeStoredSenderAddress(message.sourceAddress),
                originVerifiedRole = normalizeVerifiedRole(message.originVerifiedRole),
                originVerifiedAtMillis = normalizeVerifiedAtMillis(message.originVerifiedAtMillis)
            )
        }
        _messages.value = normalizeMessageList(deduplicated.values.toList())
    }

    fun getQueuedLocalMessages(): List<MeshChatMessage> {
        return _messages.value.filter { message ->
            message.isLocal && message.status == MeshMessageStatus.QUEUED
        }
    }

    fun updateLocalMessageStatus(
        messageId: String,
        status: MeshMessageStatus
    ) {
        val normalizedId = messageId.trim()
        if (normalizedId.isEmpty()) {
            return
        }
        _messages.update { current ->
            current.map { message ->
                if (!message.isLocal || message.id != normalizedId) {
                    return@map message
                }
                val mergedStatus = mergePreferredStatus(
                    currentStatus = message.status,
                    nextStatus = status
                )
                if (mergedStatus == message.status) {
                    message
                } else {
                    message.copy(status = mergedStatus)
                }
            }
        }
    }

    fun appendRemoteMessage(
        id: String,
        text: String,
        senderLabel: String?,
        sourceAddress: String?,
        originVerifiedRole: String? = null,
        originVerifiedAtMillis: Long? = null,
        timestampMillis: Long = System.currentTimeMillis(),
        receivedTimestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return false
        val normalizedText = text.trim().take(MAX_MESSAGE_LENGTH)
        if (normalizedText.isBlank()) return false
        val safeReceivedTimestampMillis = receivedTimestampMillis.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val safeOriginalTimestampMillis = normalizeOriginalTimestampMillis(
            originalTimestampMillis = timestampMillis,
            receivedTimestampMillis = safeReceivedTimestampMillis
        )

        val message = MeshChatMessage(
            id = normalizedId,
            text = normalizedText,
            senderLabel = senderLabel?.trim()?.take(MAX_SENDER_LABEL_LENGTH)?.takeIf { it.isNotEmpty() },
            sourceAddress = normalizeStoredSenderAddress(sourceAddress),
            originVerifiedRole = normalizeVerifiedRole(originVerifiedRole),
            originVerifiedAtMillis = normalizeVerifiedAtMillis(originVerifiedAtMillis),
            isLocal = false,
            timestampMillis = safeOriginalTimestampMillis ?: safeReceivedTimestampMillis,
            receivedTimestampMillis = safeReceivedTimestampMillis
                .takeIf { safeOriginalTimestampMillis != null },
            status = MeshMessageStatus.DELIVERED
        )

        var inserted = false
        _messages.update { current ->
            if (current.any { it.id == normalizedId }) {
                return@update current
            }
            inserted = true
            normalizeMessageList(current + message)
        }

        if (inserted) {
            synchronized(lock) {
                if (!isChatOpen) {
                    _unreadCount.update { count ->
                        if (count == Int.MAX_VALUE) count else count + 1
                    }
                }
            }
        }
        return inserted
    }

    fun updateRemoteMessageMetadata(
        messageId: String,
        senderLabel: String?,
        sourceAddress: String?,
        originVerifiedRole: String? = null,
        originVerifiedAtMillis: Long? = null
    ): Boolean {
        val normalizedId = messageId.trim()
        if (normalizedId.isEmpty()) {
            return false
        }
        val normalizedSenderLabel = normalizeRecipientLabel(senderLabel)
        val normalizedSourceAddress = normalizeStoredSenderAddress(sourceAddress)
        val normalizedVerifiedRole = normalizeVerifiedRole(originVerifiedRole)
        val normalizedVerifiedAtMillis = normalizeVerifiedAtMillis(originVerifiedAtMillis)
        if (
            normalizedSenderLabel == null &&
            normalizedSourceAddress == null &&
            normalizedVerifiedRole == null &&
            normalizedVerifiedAtMillis == null
        ) {
            return false
        }
        var changed = false
        _messages.update { current ->
            current.map { message ->
                if (message.isLocal || message.id != normalizedId) {
                    return@map message
                }
                val nextSenderLabel = message.senderLabel ?: normalizedSenderLabel
                val nextSourceAddress = message.sourceAddress ?: normalizedSourceAddress
                val nextVerifiedRole = message.originVerifiedRole ?: normalizedVerifiedRole
                val nextVerifiedAtMillis = message.originVerifiedAtMillis ?: normalizedVerifiedAtMillis
                if (
                    nextSenderLabel == message.senderLabel &&
                    nextSourceAddress == message.sourceAddress &&
                    nextVerifiedRole == message.originVerifiedRole &&
                    nextVerifiedAtMillis == message.originVerifiedAtMillis
                ) {
                    message
                } else {
                    changed = true
                    message.copy(
                        senderLabel = nextSenderLabel,
                        sourceAddress = nextSourceAddress,
                        originVerifiedRole = nextVerifiedRole,
                        originVerifiedAtMillis = nextVerifiedAtMillis
                    )
                }
            }
        }
        return changed
    }

    fun markDelivered(messageIds: Collection<String>) {
        markDelivered(messageIds = messageIds, recipientLabel = null)
    }

    fun markDelivered(
        messageIds: Collection<String>,
        recipientLabel: String?
    ) {
        if (messageIds.isEmpty()) {
            return
        }
        val targetIds = messageIds.map(String::trim).filter { it.isNotEmpty() }.toSet()
        if (targetIds.isEmpty()) {
            return
        }
        val normalizedRecipient = normalizeRecipientLabel(recipientLabel)
        _messages.update { current ->
            current.map { message ->
                if (message.isLocal && message.id in targetIds && message.status != MeshMessageStatus.READ) {
                    message.copy(
                        status = MeshMessageStatus.DELIVERED,
                        deliveredTo = mergeRecipientList(
                            existing = message.deliveredTo,
                            recipient = normalizedRecipient
                        )
                    )
                } else {
                    if (
                        message.isLocal &&
                        message.id in targetIds &&
                        normalizedRecipient != null
                    ) {
                        message.copy(
                            deliveredTo = mergeRecipientList(
                                existing = message.deliveredTo,
                                recipient = normalizedRecipient
                            )
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    fun markRead(messageIds: Collection<String>) {
        markRead(messageIds = messageIds, recipientLabel = null)
    }

    fun markRead(
        messageIds: Collection<String>,
        recipientLabel: String?
    ) {
        if (messageIds.isEmpty()) {
            return
        }
        val targetIds = messageIds.map(String::trim).filter { it.isNotEmpty() }.toSet()
        if (targetIds.isEmpty()) {
            return
        }
        val normalizedRecipient = normalizeRecipientLabel(recipientLabel)
        _messages.update { current ->
            current.map { message ->
                if (message.isLocal && message.id in targetIds && message.status != MeshMessageStatus.READ) {
                    message.copy(
                        status = MeshMessageStatus.READ,
                        deliveredTo = mergeRecipientList(
                            existing = message.deliveredTo,
                            recipient = normalizedRecipient
                        ),
                        readBy = mergeRecipientList(
                            existing = message.readBy,
                            recipient = normalizedRecipient
                        )
                    )
                } else {
                    if (message.isLocal && message.id in targetIds && normalizedRecipient != null) {
                        message.copy(
                            deliveredTo = mergeRecipientList(
                                existing = message.deliveredTo,
                                recipient = normalizedRecipient
                            ),
                            readBy = mergeRecipientList(
                                existing = message.readBy,
                                recipient = normalizedRecipient
                            )
                        )
                    } else {
                        message
                    }
                }
            }
        }
    }

    fun markSentTo(messageId: String, recipients: Collection<String>) {
        val normalizedId = messageId.trim()
        if (normalizedId.isEmpty()) {
            return
        }
        val normalizedRecipients = normalizeRecipientList(recipients)
        if (normalizedRecipients.isEmpty()) {
            return
        }
        _messages.update { current ->
            current.map { message ->
                if (message.isLocal && message.id == normalizedId) {
                    message.copy(
                        sentTo = mergeRecipientList(
                            existing = message.sentTo,
                            recipients = normalizedRecipients
                        )
                    )
                } else {
                    message
                }
            }
        }
    }

    fun setChatOpen(open: Boolean) {
        synchronized(lock) {
            isChatOpen = open
            if (open) {
                _unreadCount.value = 0
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _messages.value = emptyList()
            _unreadCount.value = 0
            isChatOpen = false
        }
    }

    private fun normalizeRecipientLabel(raw: String?): String? {
        return raw
            ?.trim()
            ?.take(MAX_SENDER_LABEL_LENGTH)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeVerifiedRole(raw: String?): String? {
        return raw
            ?.trim()
            ?.take(MAX_VERIFIED_ROLE_LENGTH)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun normalizeVerifiedAtMillis(raw: Long?): Long? = raw?.takeIf { it > 0L }

    private fun normalizeOriginalTimestampMillis(
        originalTimestampMillis: Long?,
        receivedTimestampMillis: Long
    ): Long? {
        val candidate = originalTimestampMillis?.takeIf { it > 0L } ?: return null
        return candidate.takeIf { it < receivedTimestampMillis }
    }

    private fun normalizeMessageList(messages: List<MeshChatMessage>): List<MeshChatMessage> {
        return messages
            .sortedWith(compareBy<MeshChatMessage>({ it.timestampMillis }, { it.id }))
            .takeLast(MAX_MESSAGES)
    }

    private fun normalizeRecipientList(recipients: Collection<String>): List<String> {
        if (recipients.isEmpty()) {
            return emptyList()
        }
        return recipients
            .asSequence()
            .mapNotNull(::normalizeRecipientLabel)
            .distinct()
            .toList()
    }

    private fun mergeRecipientList(
        existing: List<String>,
        recipient: String?
    ): List<String> {
        if (recipient == null) {
            return existing
        }
        if (recipient in existing) {
            return existing
        }
        return existing + recipient
    }

    private fun mergeRecipientList(
        existing: List<String>,
        recipients: Collection<String>
    ): List<String> {
        if (recipients.isEmpty()) {
            return existing
        }
        val merged = LinkedHashSet<String>(existing.size + recipients.size)
        merged.addAll(existing)
        recipients.forEach { recipient ->
            if (recipient.isNotBlank()) {
                merged += recipient
            }
        }
        return merged.toList()
    }

    private fun mergePreferredStatus(
        currentStatus: MeshMessageStatus,
        nextStatus: MeshMessageStatus
    ): MeshMessageStatus {
        // Receipt-based states are terminal for UI ordering; transient send states must not downgrade them.
        if (currentStatus == MeshMessageStatus.READ) {
            return MeshMessageStatus.READ
        }
        if (currentStatus == MeshMessageStatus.DELIVERED) {
            return when (nextStatus) {
                MeshMessageStatus.QUEUED,
                MeshMessageStatus.SENDING,
                MeshMessageStatus.SENT,
                MeshMessageStatus.FAILED -> MeshMessageStatus.DELIVERED
                else -> nextStatus
            }
        }
        return nextStatus
    }
}
