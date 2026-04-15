package com.auralis.crisisconnect.core.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks which chat session is currently visible so background services do not
 * surface duplicate notifications for the open conversation.
 */
object ActiveChatTracker {
    private val _activeSession = MutableStateFlow<String?>(null)
    val activeSession: StateFlow<String?> = _activeSession

    fun setActiveSession(sessionCode: String) {
        _activeSession.value = sessionCode
    }

    fun clearSession(sessionCode: String) {
        if (_activeSession.value == sessionCode) {
            _activeSession.value = null
        }
    }

    fun isSessionActive(sessionCode: String): Boolean = _activeSession.value == sessionCode
}
