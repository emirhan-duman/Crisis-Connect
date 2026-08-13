package com.auralis.crisisconnect.screens.Tools

import kotlin.math.max

enum class CprAssistMode {
    HANDS_ONLY,
    THIRTY_TO_TWO
}

enum class CprAssistPhase {
    READY,
    COMPRESSIONS,
    BREATHS,
    ENDED
}

enum class CprPauseReason {
    MANUAL,
    AED_ANALYSIS
}

enum class CprAedStep {
    POWER_ON,
    ATTACH_PADS,
    ANALYZE,
    SHOCK_DECISION,
    RESUME_CPR
}

data class CprAssistUiState(
    val mode: CprAssistMode = CprAssistMode.HANDS_ONLY,
    val phase: CprAssistPhase = CprAssistPhase.READY,
    val pauseReason: CprPauseReason? = null,
    val compressionInSet: Int = 0,
    val totalCompressions: Int = 0,
    val completedSets: Int = 0,
    val elapsedMillis: Long = 0L,
    val roundElapsedMillis: Long = 0L,
    val completedRounds: Int = 0,
    val breathRemainingMillis: Long = 0L,
    val beatSequence: Long = 0L,
    val soundEnabled: Boolean = true,
    val voiceEnabled: Boolean = true,
    val hapticsEnabled: Boolean = false,
    val isAedGuideOpen: Boolean = false,
    val aedStep: CprAedStep = CprAedStep.POWER_ON,
    val speechAvailable: Boolean = true
) {
    val isSessionRunning: Boolean
        get() = phase == CprAssistPhase.COMPRESSIONS || phase == CprAssistPhase.BREATHS

    val isPaused: Boolean
        get() = pauseReason != null

    val roundRemainingMillis: Long
        get() = max(0L, CprAssistTiming.ROUND_DURATION_MILLIS - roundElapsedMillis)
}

object CprAssistTiming {
    const val TARGET_BPM = 110
    const val MIN_RECOMMENDED_BPM = 100
    const val MAX_RECOMMENDED_BPM = 120
    const val COMPRESSIONS_PER_SET = 30
    const val BREATH_PAUSE_MILLIS = 6_000L
    const val ROUND_DURATION_MILLIS = 120_000L
    const val TICK_RESOLUTION_MILLIS = 20L
    const val BEAT_INTERVAL_MILLIS = 60_000.0 / TARGET_BPM.toDouble()

    fun nextCompressionInSet(current: Int): Int {
        if (current !in 1 until COMPRESSIONS_PER_SET) return 1
        return current + 1
    }

    fun completedSetAfterBeat(current: Int): Boolean = current == COMPRESSIONS_PER_SET

    fun roundRemainingMillis(roundElapsedMillis: Long): Long =
        (ROUND_DURATION_MILLIS - roundElapsedMillis).coerceIn(0L, ROUND_DURATION_MILLIS)

    fun formatDuration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) / 1_000L).toInt()
        return "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
    }
}
