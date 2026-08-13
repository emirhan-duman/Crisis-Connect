package com.auralis.crisisconnect.screens.Tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CprAssistTimingTest {
    @Test
    fun targetCadenceIsInsideRecommendedRange() {
        assertTrue(CprAssistTiming.TARGET_BPM in CprAssistTiming.MIN_RECOMMENDED_BPM..CprAssistTiming.MAX_RECOMMENDED_BPM)
        assertEquals(60_000.0 / 110.0, CprAssistTiming.BEAT_INTERVAL_MILLIS, 0.0001)
    }

    @Test
    fun compressionCounterWrapsAfterThirty() {
        assertEquals(1, CprAssistTiming.nextCompressionInSet(0))
        assertEquals(30, CprAssistTiming.nextCompressionInSet(29))
        assertEquals(1, CprAssistTiming.nextCompressionInSet(30))
        assertTrue(CprAssistTiming.completedSetAfterBeat(30))
    }

    @Test
    fun twoMinuteRoundCountdownIsClampedAndFormatted() {
        assertEquals(120_000L, CprAssistTiming.roundRemainingMillis(0L))
        assertEquals(1_000L, CprAssistTiming.roundRemainingMillis(119_000L))
        assertEquals(0L, CprAssistTiming.roundRemainingMillis(125_000L))
        assertEquals("02:00", CprAssistTiming.formatDuration(120_000L))
        assertEquals("01:05", CprAssistTiming.formatDuration(65_999L))
    }
}
