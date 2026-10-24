package com.auralis.crisisconnect.security

import org.junit.Assert.*
import org.junit.Test

class AuthorityMeshKeyLeaseTest {
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
