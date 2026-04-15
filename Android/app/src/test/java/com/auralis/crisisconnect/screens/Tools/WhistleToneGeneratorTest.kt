package com.auralis.crisisconnect.screens.Tools

import kotlin.math.abs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhistleToneGeneratorTest {

    @Test
    fun rescueModeUsesMultipleToneBandsWithinRange() {
        val segments = WhistleToneGenerator.buildSegments(3200f, WhistleMode.RESCUE)
            .filter { it.isTone }
            .mapNotNull { it.baseFrequencyHz }

        assertTrue(segments.size >= 4)
        assertTrue(segments.distinct().size >= 3)
        assertTrue(
            segments.all { frequency ->
                frequency in WhistleToneGenerator.MIN_FREQUENCY_HZ..WhistleToneGenerator.MAX_FREQUENCY_HZ
            }
        )
    }

    @Test
    fun sweepFrequencyStaysInsideConfiguredBounds() {
        val low = WhistleToneGenerator.resolveSweepFrequency(
            centerFrequency = 2200f,
            sampleIndexInSegment = 0,
            segmentSamples = WhistleToneGenerator.SAMPLE_RATE
        )
        val high = WhistleToneGenerator.resolveSweepFrequency(
            centerFrequency = 4200f,
            sampleIndexInSegment = WhistleToneGenerator.SAMPLE_RATE / 2,
            segmentSamples = WhistleToneGenerator.SAMPLE_RATE
        )

        assertTrue(low >= 1800f)
        assertTrue(high <= 4800f)
    }

    @Test
    fun generatedRescueBufferIsAudibleWithoutDigitalClipping() {
        val buffer = WhistleToneGenerator.generate(
            WhistleToneRequest(
                frequencyHz = 3200f,
                intensity = 1.0f,
                mode = WhistleMode.RESCUE
            )
        )

        val peak = buffer.maxOf { sample -> abs(sample.toInt()) }

        assertTrue(buffer.isNotEmpty())
        assertTrue(buffer.any { sample -> sample != 0.toShort() })
        assertTrue(peak < Short.MAX_VALUE)
    }

    @Test
    fun sosPatternContainsSilenceGaps() {
        val buffer = WhistleToneGenerator.generate(
            WhistleToneRequest(
                frequencyHz = 3000f,
                intensity = 0.9f,
                mode = WhistleMode.SOS
            )
        )

        assertFalse(buffer.all { sample -> sample != 0.toShort() })
    }
}
