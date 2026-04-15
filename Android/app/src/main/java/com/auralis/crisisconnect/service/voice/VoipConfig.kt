package com.auralis.crisisconnect.service.voice

object VoipConfig {
    const val SAMPLE_RATE_HZ = 16_000
    const val FRAME_DURATION_MS = 20
    const val FRAMES_PER_PACKET = 1
    const val OPUS_BITRATE_BPS = 18_000
    const val OPUS_COMPLEXITY = 1
    const val OPUS_PACKET_LOSS_PERC = 20

    const val JITTER_MIN_DELAY_MS = 20
    const val JITTER_MAX_DELAY_MS = 220
    const val JITTER_DEFAULT_DELAY_MS = 60
}
