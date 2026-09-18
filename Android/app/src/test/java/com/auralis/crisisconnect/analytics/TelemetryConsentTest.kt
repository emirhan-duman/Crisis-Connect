package com.auralis.crisisconnect.analytics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryConsentTest {
    @Test
    fun missingPreferenceDefaultsToDisabled() {
        assertFalse(TelemetryConsent.resolveEnabled(null))
    }

    @Test
    fun explicitPreferenceIsPreserved() {
        assertTrue(TelemetryConsent.resolveEnabled(true))
        assertFalse(TelemetryConsent.resolveEnabled(false))
    }
}
