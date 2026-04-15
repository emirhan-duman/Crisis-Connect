package com.auralis.crisisconnect.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object BleImageTransferReceiptStore {
    sealed interface Outcome {
        val transferId: String

        data class Success(
            override val transferId: String
        ) : Outcome

        data class Abort(
            override val transferId: String,
            val reason: String?
        ) : Outcome
    }

    private data class StampedOutcome(
        val outcome: Outcome,
        val timestampMillis: Long
    )

    private val lock = Any()
    private val updates = MutableSharedFlow<Outcome>(extraBufferCapacity = 64)
    private val recentByTransferId = linkedMapOf<String, StampedOutcome>()

    suspend fun awaitOutcome(transferId: String, timeoutMs: Long): Outcome? {
        val safeId = transferId.trim()
        if (safeId.isEmpty()) {
            return null
        }
        synchronized(lock) {
            pruneLocked(System.currentTimeMillis())
            val cached = recentByTransferId.remove(safeId)?.outcome
            if (cached != null) {
                return cached
            }
        }
        return withTimeoutOrNull(timeoutMs) {
            updates.first { outcome -> outcome.transferId == safeId }
        }
    }

    fun publishSuccess(transferId: String) {
        publish(Outcome.Success(transferId.trim()))
    }

    fun publishAbort(transferId: String, reason: String?) {
        publish(
            Outcome.Abort(
                transferId = transferId.trim(),
                reason = reason?.trim()?.take(64)
            )
        )
    }

    fun clear(transferId: String) {
        val safeId = transferId.trim()
        if (safeId.isEmpty()) {
            return
        }
        synchronized(lock) {
            recentByTransferId.remove(safeId)
        }
    }

    private fun publish(outcome: Outcome) {
        if (outcome.transferId.isBlank()) {
            return
        }
        val now = System.currentTimeMillis()
        synchronized(lock) {
            pruneLocked(now)
            recentByTransferId[outcome.transferId] = StampedOutcome(outcome, now)
            trimLocked()
        }
        updates.tryEmit(outcome)
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = recentByTransferId.entries.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (nowMillis - item.value.timestampMillis > OUTCOME_RETENTION_MS) {
                iterator.remove()
            }
        }
    }

    private fun trimLocked() {
        while (recentByTransferId.size > MAX_OUTCOME_CACHE_SIZE) {
            val oldestKey = recentByTransferId.keys.firstOrNull() ?: return
            recentByTransferId.remove(oldestKey)
        }
    }

    private const val OUTCOME_RETENTION_MS = 2 * 60 * 1000L
    private const val MAX_OUTCOME_CACHE_SIZE = 128
}
