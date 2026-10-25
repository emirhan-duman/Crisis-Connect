package com.auralis.crisisconnect.connectivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstrainedMessagePolicyTest {
    @Test
    fun allowsOnlyShortFreeTextAndSos() {
        assertTrue(ConstrainedMessagePolicy.allowsText(0, "Konumumdayım"))
        assertTrue(ConstrainedMessagePolicy.allowsText(202, "Yardıma ihtiyacım var"))
        assertFalse(ConstrainedMessagePolicy.allowsText(200, "call"))
        assertFalse(ConstrainedMessagePolicy.allowsText(203, "location"))
    }

    @Test
    fun usesUtf8ByteLimit() {
        assertTrue(ConstrainedMessagePolicy.allowsText(0, "a".repeat(1_024)))
        assertFalse(ConstrainedMessagePolicy.allowsText(0, "a".repeat(1_025)))
        assertFalse(ConstrainedMessagePolicy.allowsText(0, "ğ".repeat(513)))
    }

    @Test
    fun satelliteRemainsConstrainedEvenWhenBandwidthCapabilityIsPresent() {
        assertTrue(ConstrainedMessagePolicy.isConstrained(true, true, true))
        assertTrue(ConstrainedMessagePolicy.isConstrained(false, false, true))
        assertFalse(ConstrainedMessagePolicy.isConstrained(false, true, true))
    }

    @Test
    fun preAndroid16NonSatelliteNetworkIsNotMisclassified() {
        assertFalse(ConstrainedMessagePolicy.isConstrained(false, false, false))
    }
}
