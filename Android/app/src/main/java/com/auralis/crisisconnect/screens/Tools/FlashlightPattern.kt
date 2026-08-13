package com.auralis.crisisconnect.screens.Tools

internal data class FlashlightPulse(
    val isOn: Boolean,
    val durationMillis: Long
)

/** Pure, testable light patterns shared by the flashlight runtime. */
internal object FlashlightPatterns {
    private const val MORSE_UNIT_MILLIS = 200L

    val sos: List<FlashlightPulse> = buildList {
        fun signal(onUnits: Int, offUnits: Int) {
            add(FlashlightPulse(isOn = true, durationMillis = onUnits * MORSE_UNIT_MILLIS))
            add(FlashlightPulse(isOn = false, durationMillis = offUnits * MORSE_UNIT_MILLIS))
        }

        // SOS is transmitted as one continuous procedural signal: ...---...
        signal(1, 1)
        signal(1, 1)
        signal(1, 1)
        signal(3, 1)
        signal(3, 1)
        signal(3, 1)
        signal(1, 1)
        signal(1, 1)
        signal(1, 7)
    }

    /**
     * International mountain distress signal: six visible signals in one minute, followed by a
     * one-minute pause. Each signal starts ten seconds after the previous one.
     */
    val emergencyBeacon: List<FlashlightPulse> = buildList {
        repeat(6) {
            add(FlashlightPulse(isOn = true, durationMillis = 1_000L))
            add(FlashlightPulse(isOn = false, durationMillis = 9_000L))
        }
        add(FlashlightPulse(isOn = false, durationMillis = 60_000L))
    }

    fun strobe(flashesPerSecond: Int): List<FlashlightPulse> {
        val safeRate = flashesPerSecond.coerceIn(1, 3)
        // Round up so timer quantization can never push the effective rate above 3 Hz.
        val halfPeriodMillis = (500L + safeRate - 1) / safeRate
        return listOf(
            FlashlightPulse(isOn = true, durationMillis = halfPeriodMillis),
            FlashlightPulse(isOn = false, durationMillis = halfPeriodMillis)
        )
    }
}
