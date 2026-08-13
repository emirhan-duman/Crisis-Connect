package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryWakeQueuePersistence : ResourceAlertWakeQueuePersistence {
    var value: String? = null
    override fun load(): String? = value
    override fun save(value: String?): Boolean {
        this.value = value
        return true
    }
}

class ResourceAlertWakeQueueTest {
    private val payload = ResourceAlertWakePayload(
        "afad",
        "attempt-device-1",
        "11111111-1111-4111-8111-111111111111",
    )

    @Test
    fun pendingAckSurvivesRestartAndUsesBoundedBackoff() {
        val persistence = MemoryWakeQueuePersistence()
        val start = 1_800_000_000_000L
        val first = ResourceAlertWakeQueue(persistence)
        val key = first.enqueue(payload, "user-1", start)
        assertEquals(1, first.claim("user-1", start, key)?.attemptCount)

        val restarted = ResourceAlertWakeQueue(persistence)
        assertNull(restarted.claim("user-1", start + 29_999, key))
        assertEquals(2, restarted.claim("user-1", start + 30_000, key)?.attemptCount)
        assertTrue(restarted.complete("user-1", key!!, start + 30_001))
        assertFalse(restarted.hasPending("user-1", start + 30_001))
        assertEquals(
            ResourceAlertWakeQueue.MAXIMUM_BACKOFF_MILLIS,
            ResourceAlertWakeQueue.backoffAfterAttempt(100),
        )
    }

    @Test
    fun dedupeAccountIsolationAndExpiryAreFailClosed() {
        val persistence = MemoryWakeQueuePersistence()
        val queue = ResourceAlertWakeQueue(persistence)
        val start = 1_800_000_000_000L
        queue.enqueue(payload, "user-1", start)
        queue.enqueue(payload, "user-1", start)
        assertTrue(queue.hasPending("user-1", start))
        assertEquals(1, queue.claim("user-1", start)?.attemptCount)
        assertNull(queue.claim("user-1", start))

        val other = ResourceAlertWakePayload(
            "afad",
            "attempt-device-2",
            "22222222-2222-4222-8222-222222222222",
        )
        queue.enqueue(other, "user-2", start)
        assertTrue(queue.hasPending("user-2", start))
        assertFalse(queue.hasPending("user-2", start + ResourceAlertWakeQueue.RETENTION_MILLIS + 1))
    }

    @Test
    fun capacityRejectsNewReceiptWithoutEvictingOldest() {
        val persistence = MemoryWakeQueuePersistence()
        val queue = ResourceAlertWakeQueue(persistence)
        val start = 1_800_000_000_000L
        repeat(ResourceAlertWakeQueue.MAXIMUM_ENTRIES) { index ->
            val item = ResourceAlertWakePayload(
                "afad",
                "attempt-$index",
                index.toString().padStart(32, '0'),
            )
            assertTrue(queue.enqueue(item, "user-1", start + index) != null)
        }
        val overflow = ResourceAlertWakePayload(
            "afad",
            "attempt-overflow",
            "99999999999999999999999999999999",
        )
        assertNull(queue.enqueue(overflow, "user-1", start + 100))
        assertTrue(
            queue.claim(
                "user-1",
                start + 100,
                "attempt-0.00000000000000000000000000000000",
            ) != null
        )
    }
}
