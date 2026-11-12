package com.auralis.crisisconnect.security

import org.junit.Assert.*
import org.junit.Test

class AuthorityMeshKeyLeaseTest {
    @Test fun nextLeaseIsStoredEarlyButActivatesOnlyAtTheBoundaryAndOverlapIsBounded() {
        val period = AuthorityMeshKeyLease.PERIOD_MS
        val boundary = 11 * period
        val next = AuthorityMeshKeyLease("authority-v2-11", "key", "owner", boundary, 12 * period,
            12 * period + AuthorityMeshKeyLease.OVERLAP_MS)
        assertTrue(next.isCacheable(10 * period + 1, "owner"))
        assertFalse(next.isCurrent(boundary - 1, "owner"))
        assertTrue(next.isCurrent(boundary, "owner"))
        assertFalse(next.isReceivable(boundary - 60_001, "owner"))
        assertTrue(next.isReceivable(boundary - 60_000, "owner"))
        assertTrue(next.isReceivable(12 * period + 59_999, "owner"))
        assertFalse(next.isReceivable(12 * period + 60_000, "owner"))
        assertFalse(next.isCacheable(9 * period, "owner"))
        assertFalse(next.isReceivable(boundary, "other-account"))
        assertFalse(next.copy(receiveUntilMs = 12 * period + 60_001).isCacheable(boundary, "owner"))
        assertFalse(next.copy(notBeforeMs = Long.MAX_VALUE).isCurrent(boundary, "owner"))
        val certCapped = next.copy(expiresAtMs = boundary + 1000, receiveUntilMs = boundary + 1000)
        assertFalse(certCapped.isReceivable(boundary + 1000, "owner"))
    }
    @Test fun expiresAt72HourEpochAndRejectsOtherAccountsOrExtendedLeases() {
        val period = AuthorityMeshKeyLease.PERIOD_MS
        val lease = AuthorityMeshKeyLease("authority-v2-10", "", "owner", 10 * period, 11 * period)
        assertTrue(lease.isCurrent(10 * period, "owner"))
        assertTrue(lease.isCurrent(11 * period - 1, "owner"))
        assertFalse(lease.isCurrent(11 * period, "owner"))
        assertFalse(lease.isCurrent(10 * period - 1, "owner"))
        assertFalse(lease.isCurrent(10 * period, "different-account"))
        assertFalse(lease.copy(expiresAtMs = 12 * period).isCurrent(10 * period, "owner"))
        assertFalse(lease.copy(keyId = "authority-v1").isCurrent(10 * period, "owner"))
    }
}
