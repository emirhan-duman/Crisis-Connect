package com.auralis.crisisconnect.service

import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process progress bus for BLE voice transfers so BLE chat UI can mirror RFCOMM progress UX.
 */
object BleVoiceTransferProgressStore {

    private val lock = Any()
    private val _progress = MutableStateFlow<Map<String, VoiceTransferProgress>>(emptyMap())
    val progress: StateFlow<Map<String, VoiceTransferProgress>> = _progress.asStateFlow()

    fun update(
        sessionCode: String,
        transferId: String,
        direction: VoiceTransferDirection,
        totalChunks: Int,
        confirmedChunks: Int,
        state: VoiceTransferState
    ) {
        val safeSession = sessionCode.trim()
        val safeTransferId = transferId.trim()
        if (safeSession.isEmpty() || safeTransferId.isEmpty()) {
            return
        }
        val safeTotal = totalChunks.coerceAtLeast(1)
        val safeConfirmed = confirmedChunks.coerceIn(0, safeTotal)
        val pending = (safeTotal - safeConfirmed).coerceAtLeast(0)
        val key = buildKey(safeSession, safeTransferId, direction)
        val item = VoiceTransferProgress(
            sessionCode = safeSession,
            uuid = safeTransferId,
            direction = direction,
            totalChunks = safeTotal,
            confirmedChunks = safeConfirmed,
            pendingChunks = pending,
            inFlightChunks = 0,
            state = state
        )
        synchronized(lock) {
            val updated = _progress.value.toMutableMap()
            updated[key] = item
            _progress.value = updated
        }
    }

    fun remove(
        sessionCode: String,
        transferId: String,
        direction: VoiceTransferDirection
    ) {
        val safeSession = sessionCode.trim()
        val safeTransferId = transferId.trim()
        if (safeSession.isEmpty() || safeTransferId.isEmpty()) {
            return
        }
        val key = buildKey(safeSession, safeTransferId, direction)
        synchronized(lock) {
            val updated = _progress.value.toMutableMap()
            if (updated.remove(key) != null) {
                _progress.value = updated
            }
        }
    }

    fun clearSession(sessionCode: String) {
        val safeSession = sessionCode.trim()
        if (safeSession.isEmpty()) {
            return
        }
        synchronized(lock) {
            val updated = _progress.value
                .filterValues { it.sessionCode != safeSession }
            if (updated.size != _progress.value.size) {
                _progress.value = updated
            }
        }
    }

    private fun buildKey(
        sessionCode: String,
        transferId: String,
        direction: VoiceTransferDirection
    ): String = "$sessionCode|$transferId|${direction.name}"
}
