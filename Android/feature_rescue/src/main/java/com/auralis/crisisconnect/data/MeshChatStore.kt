package com.auralis.crisisconnect.data

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object MeshChatStore {
    private const val MAX_MESSAGES = 500

    private val lock = Any()
    private val _messages = MutableStateFlow<List<MeshChatMessage>>(emptyList())
    private val _unreadCount = MutableStateFlow(0)
    private var isChatOpen = false

    val messages: StateFlow<List<MeshChatMessage>> = _messages.asStateFlow()
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    fun appendLocalMessage(
        text: String,
        timestampMillis: Long = System.currentTimeMillis()
    ): MeshChatMessage {
        val normalized = text.trim()
        require(normalized.isNotEmpty()) { "Mesh local message cannot be blank" }
        val message = MeshChatMessage(
            id = UUID.randomUUID().toString(),
            text = normalized,
            senderLabel = null,
            isLocal = true,
            timestampMillis = timestampMillis,
            status = MeshMessageStatus.SENT
        )
        _messages.update { current ->
            (current + message).takeLast(MAX_MESSAGES)
        }
        return message
    }

    fun appendRemoteMessage(
        id: String,
        text: String,
        senderLabel: String?,
        timestampMillis: Long = System.currentTimeMillis()
    ): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return false
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) return false

        val message = MeshChatMessage(
            id = normalizedId,
            text = normalizedText,
            senderLabel = senderLabel?.trim()?.takeIf { it.isNotEmpty() },
            isLocal = false,
            timestampMillis = timestampMillis,
            status = MeshMessageStatus.DELIVERED
        )

        var inserted = false
        _messages.update { current ->
            if (current.any { it.id == normalizedId }) {
                return@update current
            }
            inserted = true
            (current + message).takeLast(MAX_MESSAGES)
        }

        if (inserted) {
            synchronized(lock) {
                if (!isChatOpen) {
                    _unreadCount.update { it + 1 }
                }
            }
        }
        return inserted
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
}
