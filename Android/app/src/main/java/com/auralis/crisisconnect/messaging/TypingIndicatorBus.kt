package com.auralis.crisisconnect.messaging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory "peer is typing" pulses, keyed by the chat's session code. The internet transport routes
 * typing signals (templateCode 201) here; the chat screen observes the map and shows the WhatsApp-style
 * indicator while an entry is fresh. Entries self-expire — a peer that stops typing simply stops
 * sending pulses, and the reader treats anything past its expiry as gone. Never persisted.
 */
object TypingIndicatorBus {
    /** How long one received pulse keeps the indicator alive (sender re-pulses every ~4s). */
    private const val TYPING_TTL_MS = 6_000L

    private val _typing = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** sessionCode → epoch-millis the indicator expires. Stale entries may linger until touched. */
    val typing: StateFlow<Map<String, Long>> = _typing.asStateFlow()

    fun signalTyping(sessionCode: String) {
        val code = sessionCode.trim()
        if (code.isEmpty()) return
        val now = System.currentTimeMillis()
        _typing.value = _typing.value.filterValues { it > now } + (code to now + TYPING_TTL_MS)
    }

    /** A real message arrived — the bubble should drop instantly, not wait out the TTL. */
    fun clear(sessionCode: String) {
        val code = sessionCode.trim()
        if (code.isEmpty() || !_typing.value.containsKey(code)) return
        val now = System.currentTimeMillis()
        _typing.value = _typing.value.filterValues { it > now } - code
    }
}
