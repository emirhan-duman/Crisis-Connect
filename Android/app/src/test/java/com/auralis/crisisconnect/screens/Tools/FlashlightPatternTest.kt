package com.auralis.crisisconnect.screens.Tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashlightPatternTest {
    @Test
    fun `sos contains three dots three dashes and three dots`() {
        val onDurations = FlashlightPatterns.sos
            .filter(FlashlightPulse::isOn)
            .map(FlashlightPulse::durationMillis)

        assertEquals(
            listOf(200L, 200L, 200L, 600L, 600L, 600L, 200L, 200L, 200L),
            onDurations
        )
        assertEquals(6_000L, FlashlightPatterns.sos.sumOf(FlashlightPulse::durationMillis))
    }

    @Test
    fun `emergency beacon emits six signals in a two minute cycle`() {
        val pattern = FlashlightPatterns.emergencyBeacon

        assertEquals(6, pattern.count(FlashlightPulse::isOn))
        assertEquals(120_000L, pattern.sumOf(FlashlightPulse::durationMillis))
    }

    @Test
    fun `strobe is capped at three flashes per second`() {
        val pattern = FlashlightPatterns.strobe(flashesPerSecond = 20)

        assertEquals(2, pattern.size)
        assertTrue(pattern.all { it.durationMillis >= 166L })
    }
}
