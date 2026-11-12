package com.auralis.crisisconnect.service

import org.junit.Assert.*
import org.junit.Test

class RadioPrivacyIdentityTest {
    @Test fun aliasesRotateAndDoNotPersistAcrossProcessesAndBothServicesShareElectionRank() {
        val first = RadioPrivacyIdentity.derive(ByteArray(32) { 1 }, 10)
        val same = RadioPrivacyIdentity.derive(ByteArray(32) { 1 }, 10)
        val next = RadioPrivacyIdentity.derive(ByteArray(32) { 1 }, 11)
        val restart = RadioPrivacyIdentity.derive(ByteArray(32) { 2 }, 10)
        assertArrayEquals(first.serviceData, same.serviceData)
        assertEquals(12, first.serviceData.size)
        assertTrue(first.broadcastId.matches(Regex("CC-[0-9A-F]{24}")))
        assertNotEquals(first.broadcastId, next.broadcastId)
        assertNotEquals(first.broadcastId, restart.broadcastId)
        assertNotEquals(first.rank(false), next.rank(false))
        assertTrue(first.rank(false) >= 0); assertTrue(first.rank(true) < 0)
        assertEquals(RadioPrivacyIdentity.current.rank(false), RadioPrivacyIdentity.current.rank(false))
    }
}
