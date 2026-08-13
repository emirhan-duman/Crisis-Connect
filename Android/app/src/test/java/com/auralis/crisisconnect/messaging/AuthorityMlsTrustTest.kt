package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AuthorityMlsTrustTest {
    @Test
    fun approvalNeverCarriesForwardToFirstOrChangedDeviceSet() {
        assertEquals(false, AuthorityMlsTrust.approvalCarriesForward(existingApproved = false, exactDeviceSetMatch = true))
        assertEquals(false, AuthorityMlsTrust.approvalCarriesForward(existingApproved = true, exactDeviceSetMatch = false))
        assertEquals(true, AuthorityMlsTrust.approvalCarriesForward(existingApproved = true, exactDeviceSetMatch = true))
    }

    @Test
    fun deviceSetFingerprintIsOrderIndependentAndChangesOnInjection() {
        val a = AuthorityMlsTrust.deviceCommitment(
            AuthorityMlsDirectoryRecord("u1", "d1", "cc-mls:v1:dTE:ZDE", ByteArray(32) { 1 }, "one"),
        )
        val b = AuthorityMlsTrust.deviceCommitment(
            AuthorityMlsDirectoryRecord("u1", "d2", "cc-mls:v1:dTE:ZDI", ByteArray(32) { 2 }, "two"),
        )
        val first = AuthorityMlsTrust.deviceSetFingerprint(listOf(a, b).sorted())
        assertEquals(first, AuthorityMlsTrust.deviceSetFingerprint(listOf(b, a).sorted()))
        assertNotEquals(first, AuthorityMlsTrust.deviceSetFingerprint(listOf(a)))
        assertEquals(43, first.length)
        assertEquals(16, AuthorityMlsTrust.safetyNumber(listOf(a, b).sorted()).split(Regex("\\s+")).size)
    }
}
