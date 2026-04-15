package com.auralis.crisisconnect.service

import org.junit.Assert.assertEquals
import org.junit.Test

class RfcommCallAudioPipelineDelegateTest {

    @Test
    fun resolveCallConnectedAt_keepsExistingTimestampDuringReinit() {
        assertEquals(
            1_234_567L,
            resolveCallConnectedAt(existingConnectedAt = 1_234_567L, nowMillis = 9_999_999L)
        )
    }

    @Test
    fun resolveCallConnectedAt_usesCurrentTimestampForFirstConnect() {
        assertEquals(
            9_999_999L,
            resolveCallConnectedAt(existingConnectedAt = null, nowMillis = 9_999_999L)
        )
    }
}
