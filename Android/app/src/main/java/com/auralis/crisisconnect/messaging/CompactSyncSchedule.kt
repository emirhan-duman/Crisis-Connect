package com.auralis.crisisconnect.messaging

/** Monotonic, shared request budget across foreground resumes; no network probes. */
internal class CompactSyncSchedule {
    private var lastAttempt = -30_000L
    private var nextAttempt = 0L
    private var failures = 0
    fun reserve(now: Long): Boolean {
        if (now < nextAttempt || now - lastAttempt < 30_000) return false
        lastAttempt = now
        nextAttempt = now + 30_000
        return true
    }
    fun succeeded(now: Long, moreWork: Boolean, jitter: Long) {
        failures = 0
        nextAttempt = maxOf(lastAttempt + 30_000, now + if (moreWork) 30_000 else 120_000 + jitter.coerceIn(0,60_000))
    }
    fun failed(now: Long, jitter: Long) {
        failures = (failures + 1).coerceAtMost(5)
        val base = (30_000L shl (failures - 1)).coerceAtMost(240_000)
        nextAttempt = now + (base + jitter.coerceIn(0,base / 4)).coerceAtMost(300_000)
    }
    fun deferUntil(now: Long, delayMs: Long) {
        nextAttempt = maxOf(nextAttempt, now + delayMs.coerceIn(30_000, 300_000))
    }
}
