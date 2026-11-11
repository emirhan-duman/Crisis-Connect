package com.auralis.crisisconnect.connectivity

import org.junit.Assert.*
import org.junit.Test

class DisasterTrafficPolicyTest {
    @Test fun normalConnectionKeepsRichPresence() {
        val policy = DisasterTrafficPolicy()
        policy.stopEmergency(0)
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(0))
    }
    @Test fun networkFlappingDoesNotRepeatedlyRestoreRichPresence() {
        val policy = DisasterTrafficPolicy()
        policy.updateNetwork(true, true, 0)
        policy.updateNetwork(false, true, 1_000)
        assertEquals(DisasterTrafficPolicy.Mode.RESTRICTED_NETWORK, policy.mode(60_999))
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(61_000))
    }
    @Test fun cellularFailuresEnterSavingAndRecoverOnlyAfterHoldAndHealthyTraffic() {
        val policy = DisasterTrafficPolicy().apply { cellular = true }
        policy.record(1_000, false, 0)
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(1_000))
        policy.record(2_000, false, 0)
        assertEquals(DisasterTrafficPolicy.Mode.DEGRADED_CELLULAR, policy.mode(2_000))
        policy.record(3_000, true, 100)
        policy.record(4_000, true, 100)
        assertEquals(DisasterTrafficPolicy.Mode.DEGRADED_CELLULAR, policy.mode(4_000))
        policy.record(302_000, true, 100)
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(302_000))
    }
    @Test fun permissionErrorsAndWifiFailuresDoNotClaimCellularCongestion() {
        val policy = DisasterTrafficPolicy().apply { cellular = true }
        repeat(5) { policy.record(0, false, 0, transientFailure = false) }
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(0))
        policy.cellular = false
        repeat(5) { policy.record(0, false, 0) }
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(0))
    }
    @Test fun slowSuccessfulCellularRequestsAlsoReduceTraffic() {
        val policy = DisasterTrafficPolicy().apply { cellular = true }
        repeat(4) { policy.record(it.toLong(), true, 8_000) }
        assertEquals(DisasterTrafficPolicy.Mode.DEGRADED_CELLULAR, policy.mode(4))
    }
    @Test fun sosDoesNotRecoverWhileEmergencyIsActive() {
        val policy = DisasterTrafficPolicy().apply { emergencyActive = true }
        assertEquals(DisasterTrafficPolicy.Mode.SOS, policy.mode(3_600_000))
        policy.stopEmergency(3_600_000)
        assertEquals(DisasterTrafficPolicy.Mode.SOS, policy.mode(3_899_999))
        assertEquals(DisasterTrafficPolicy.Mode.NORMAL, policy.mode(3_900_000))
    }
    @Test fun restrictedNetworkWinsOverHealthyRequests() {
        val policy = DisasterTrafficPolicy().apply { restricted = true; cellular = true }
        repeat(5) { policy.record(600_000, true, 100) }
        assertEquals(DisasterTrafficPolicy.Mode.RESTRICTED_NETWORK, policy.mode(600_000))
    }
    @Test fun compactPolicyKeepsUrgentTrafficAndUsesUtf8Bytes() {
        assertTrue(DisasterTrafficPolicy.isAttachmentChunk("transfer~123.0"))
        assertFalse(DisasterTrafficPolicy.isAttachmentChunk("text~123"))
        assertTrue(DisasterTrafficPolicy.allowsCompact(202, 1_024))
        assertTrue(DisasterTrafficPolicy.allowsCompact(203, 500))
        assertTrue(DisasterTrafficPolicy.allowsCompact(208, 500))
        assertFalse(DisasterTrafficPolicy.allowsCompact(0, "ğ".repeat(513).toByteArray().size))
        assertTrue(DisasterTrafficPolicy.allowsCompact(204, 1_024))
        assertTrue(DisasterTrafficPolicy.allowsCompact(205, 1_024))
        for (template in listOf(200, 201)) assertFalse(DisasterTrafficPolicy.allowsCompact(template, 1))
    }
}
