package com.auralis.crisisconnect.service

import com.auralis.crisisconnect.service.voice.VoipConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RfcommCallProtocolDelegateTest {

    private val delegate = RfcommCallProtocolDelegate()

    @Test
    fun `wideband config keeps low latency packetization`() {
        val (profile, cfg) = delegate.sanitizeCfg(
            sampleRate = VoipConfig.SAMPLE_RATE_HZ,
            frameMs = VoipConfig.FRAME_DURATION_MS,
            framesPerPacket = 1,
            bitrate = VoipConfig.OPUS_BITRATE_BPS,
            encrypted = true,
            hasKey = true
        ) ?: error("sanitizeCfg returned null")

        assertEquals(Profile.WB, profile)
        assertEquals(1, cfg.fps)
        assertEquals(VoipConfig.OPUS_BITRATE_BPS, cfg.br)
        assertTrue(cfg.encrypted)
    }

    @Test
    fun `narrowband config clamps bitrate and packet grouping`() {
        val (profile, cfg) = delegate.sanitizeCfg(
            sampleRate = 8_000,
            frameMs = 60,
            framesPerPacket = 5,
            bitrate = 9_000,
            encrypted = true,
            hasKey = false
        ) ?: error("sanitizeCfg returned null")

        assertEquals(Profile.NB, profile)
        assertEquals(VoipConfig.SAMPLE_RATE_HZ, cfg.sr)
        assertEquals(VoipConfig.FRAME_DURATION_MS, cfg.frm)
        assertEquals(2, cfg.fps)
        assertEquals(12_000, cfg.br)
        assertFalse(cfg.encrypted)
    }
}
