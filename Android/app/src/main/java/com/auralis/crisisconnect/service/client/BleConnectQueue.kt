package com.auralis.crisisconnect.service.client

import java.util.LinkedHashSet
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide queue that serializes BLE connect launches.
 * This avoids connect storms when multiple features trigger outbound connects at once.
 */
internal object BleConnectQueue {

    private val lock = Mutex()
    private val pending = LinkedHashSet<String>()
    private var activeAddress: String? = null

    suspend fun enqueue(address: String, block: () -> Unit) {
        val normalized = address.trim().uppercase(Locale.US)
        val accepted = lock.withLock {
            if (normalized.isBlank() || normalized == activeAddress || pending.contains(normalized)) {
                false
            } else {
                pending += normalized
                true
            }
        }
        if (!accepted) {
            return
        }

        try {
            awaitTurn(normalized)
            block()
            val settle = CONNECT_SETTLE_BASE_MS + Random.nextLong(CONNECT_SETTLE_JITTER_MS + 1L)
            delay(settle)
        } finally {
            lock.withLock {
                if (activeAddress == normalized) {
                    activeAddress = null
                } else {
                    pending.remove(normalized)
                }
            }
        }
    }

    private suspend fun awaitTurn(address: String) {
        while (true) {
            val hasTurn = lock.withLock {
                if (activeAddress == null && pending.firstOrNull() == address) {
                    pending.remove(address)
                    activeAddress = address
                    true
                } else {
                    false
                }
            }
            if (hasTurn) {
                return
            }
            delay(QUEUE_POLL_MS)
        }
    }

    private const val QUEUE_POLL_MS = 90L
    private const val CONNECT_SETTLE_BASE_MS = 280L
    private const val CONNECT_SETTLE_JITTER_MS = 120L
}
