package com.auralis.crisisconnect.nearby

import org.junit.Assert.*
import org.junit.Test

class NearbyPairingAdmissionTest {
    @Test fun limitsBothRepeatedAndRotatingAddressesAndReopensAfterWindow() {
        val limiter = NearbyPairingAdmission()
        repeat(3) { assertTrue(limiter.admit("same-peer", 1_000L)) }
        assertFalse(limiter.admit("same-peer", 1_001L))
        repeat(9) { assertTrue(limiter.admit("rotating-$it", 1_001L)) }
        assertFalse(limiter.admit("new-address", 1_002L))
        assertTrue(limiter.admit("same-peer", 61_002L))
    }
}
