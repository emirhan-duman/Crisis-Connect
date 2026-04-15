package com.auralis.crisisconnect.screens.Tools

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh

internal data class WhistleToneRequest(
    val frequencyHz: Float,
    val intensity: Float,
    val mode: WhistleMode
)

internal data class WhistleToneSegment(
    val durationMs: Int,
    val isTone: Boolean,
    val baseFrequencyHz: Float? = null
)

internal object WhistleToneGenerator {
    const val SAMPLE_RATE = 44_100
    const val MIN_FREQUENCY_HZ = 2_000f
    const val MAX_FREQUENCY_HZ = 4_500f
    const val MIN_INTENSITY = 0.55f
    const val MAX_INTENSITY = 1.0f

    private const val MAX_SAFE_AMPLITUDE_RATIO = 0.95f
    private const val ENVELOPE_EDGE_MS = 6
    private const val SWEEP_SPAN_HZ = 1_400f
    private const val SWEEP_MIN_HZ = 1_800f
    private const val SWEEP_MAX_HZ = 4_800f
    private const val RESCUE_CHIRP_SPAN_HZ = 220f
    private const val RESCUE_TREMOLO_HZ = 17.5

    fun generate(request: WhistleToneRequest): ShortArray {
        val frequencyHz = request.frequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        val intensity = request.intensity.coerceIn(MIN_INTENSITY, MAX_INTENSITY)
        val segments = buildSegments(frequencyHz, request.mode)
        val totalSamples = segments.sumOf { segment ->
            (segment.durationMs * SAMPLE_RATE) / 1000
        }.coerceAtLeast(SAMPLE_RATE / 2)

        val buffer = ShortArray(totalSamples)
        val amplitude = (Short.MAX_VALUE * MAX_SAFE_AMPLITUDE_RATIO * intensity).toInt()
        val edgeSamples = ((ENVELOPE_EDGE_MS / 1000f) * SAMPLE_RATE)
            .toInt()
            .coerceAtLeast(1)

        var mainPhase = 0.0
        var lowerPhase = 0.0
        var upperPhase = 0.0
        var sampleIndex = 0

        segments.forEach { segment ->
            val segmentSamples = (segment.durationMs * SAMPLE_RATE) / 1000
            repeat(segmentSamples) { localIndex ->
                if (sampleIndex >= buffer.size) return@repeat

                buffer[sampleIndex] = if (segment.isTone) {
                    val currentFrequency = when (request.mode) {
                        WhistleMode.SWEEP -> resolveSweepFrequency(
                            centerFrequency = segment.baseFrequencyHz ?: frequencyHz,
                            sampleIndexInSegment = localIndex,
                            segmentSamples = segmentSamples
                        )

                        WhistleMode.RESCUE -> resolveRescueFrequency(
                            segment = segment,
                            sampleIndexInSegment = localIndex,
                            segmentSamples = segmentSamples
                        )

                        else -> segment.baseFrequencyHz ?: frequencyHz
                    }

                    val lowerFrequency = when (request.mode) {
                        WhistleMode.RESCUE -> (currentFrequency * 0.86f).coerceAtLeast(SWEEP_MIN_HZ)
                        else -> (currentFrequency * 0.90f).coerceAtLeast(SWEEP_MIN_HZ)
                    }
                    val upperFrequency = when (request.mode) {
                        WhistleMode.RESCUE -> (currentFrequency * 1.12f).coerceAtMost(SWEEP_MAX_HZ)
                        else -> (currentFrequency * 1.08f).coerceAtMost(SWEEP_MAX_HZ)
                    }

                    mainPhase = advancePhase(mainPhase, currentFrequency)
                    lowerPhase = advancePhase(lowerPhase, lowerFrequency)
                    upperPhase = advancePhase(upperPhase, upperFrequency)

                    val fundamental = sin(mainPhase)
                    val lowerSideBand = sin(lowerPhase + 0.18)
                    val upperSideBand = sin(upperPhase + 0.31)
                    val tremolo = if (request.mode == WhistleMode.RESCUE) {
                        0.78 + (0.22 * sin((2 * PI * RESCUE_TREMOLO_HZ * sampleIndex) / SAMPLE_RATE))
                    } else {
                        1.0
                    }
                    val mixed = when (request.mode) {
                        WhistleMode.RESCUE -> {
                            (fundamental * 0.56) + (lowerSideBand * 0.20) + (upperSideBand * 0.24)
                        }

                        WhistleMode.SWEEP -> {
                            (fundamental * 0.70) + (lowerSideBand * 0.18) + (upperSideBand * 0.12)
                        }

                        else -> {
                            (fundamental * 0.74) + (lowerSideBand * 0.16) + (upperSideBand * 0.10)
                        }
                    }
                    val shaped = tanh(
                        mixed * if (request.mode == WhistleMode.RESCUE) {
                            1.32
                        } else {
                            1.15
                        }
                    )
                    val envelope = edgeEnvelope(
                        sampleIndexInSegment = localIndex,
                        segmentSamples = segmentSamples,
                        edgeSamples = edgeSamples
                    )
                    (shaped * tremolo * envelope * amplitude)
                        .toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                } else {
                    0
                }

                sampleIndex += 1
            }
        }

        return buffer
    }

    internal fun buildSegments(
        centerFrequency: Float,
        mode: WhistleMode
    ): List<WhistleToneSegment> {
        val clampedCenter = centerFrequency.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        return when (mode) {
            WhistleMode.CONTINUOUS -> listOf(
                WhistleToneSegment(durationMs = 1000, isTone = true, baseFrequencyHz = clampedCenter)
            )

            WhistleMode.PULSE -> listOf(
                WhistleToneSegment(durationMs = 240, isTone = true, baseFrequencyHz = clampedCenter),
                WhistleToneSegment(durationMs = 110, isTone = false),
                WhistleToneSegment(durationMs = 240, isTone = true, baseFrequencyHz = clampedCenter),
                WhistleToneSegment(durationMs = 260, isTone = false)
            )

            WhistleMode.SOS -> {
                val short = 180
                val long = short * 3
                val gap = 180
                listOf(
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap * 2, false),
                    WhistleToneSegment(long, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(long, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(long, true, clampedCenter),
                    WhistleToneSegment(gap * 2, false),
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap, false),
                    WhistleToneSegment(short, true, clampedCenter),
                    WhistleToneSegment(gap * 4, false)
                )
            }

            WhistleMode.SWEEP -> listOf(
                WhistleToneSegment(durationMs = 1200, isTone = true, baseFrequencyHz = clampedCenter)
            )

            WhistleMode.RESCUE -> {
                val burstA = (clampedCenter - 420f).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
                val burstB = clampedCenter
                val burstC = (clampedCenter + 280f).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
                val burstD = (clampedCenter + 620f).coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
                listOf(
                    WhistleToneSegment(durationMs = 210, isTone = true, baseFrequencyHz = burstA),
                    WhistleToneSegment(durationMs = 80, isTone = false),
                    WhistleToneSegment(durationMs = 210, isTone = true, baseFrequencyHz = burstB),
                    WhistleToneSegment(durationMs = 80, isTone = false),
                    WhistleToneSegment(durationMs = 210, isTone = true, baseFrequencyHz = burstC),
                    WhistleToneSegment(durationMs = 120, isTone = false),
                    WhistleToneSegment(durationMs = 360, isTone = true, baseFrequencyHz = burstD),
                    WhistleToneSegment(durationMs = 280, isTone = false)
                )
            }
        }
    }

    internal fun resolveSweepFrequency(
        centerFrequency: Float,
        sampleIndexInSegment: Int,
        segmentSamples: Int
    ): Float {
        val halfSpan = SWEEP_SPAN_HZ / 2f
        val minFrequency = (centerFrequency - halfSpan).coerceAtLeast(SWEEP_MIN_HZ)
        val maxFrequency = (centerFrequency + halfSpan).coerceAtMost(SWEEP_MAX_HZ)
        if (maxFrequency <= minFrequency) {
            return centerFrequency
        }

        val progress = sampleIndexInSegment.toFloat() / segmentSamples.coerceAtLeast(1)
        val triangle = if (progress <= 0.5f) {
            progress * 2f
        } else {
            (1f - progress) * 2f
        }
        return minFrequency + (maxFrequency - minFrequency) * triangle
    }

    private fun resolveRescueFrequency(
        segment: WhistleToneSegment,
        sampleIndexInSegment: Int,
        segmentSamples: Int
    ): Float {
        val baseFrequency = segment.baseFrequencyHz ?: 3200f
        if (segmentSamples <= 1) {
            return baseFrequency
        }
        val progress = sampleIndexInSegment.toFloat() / (segmentSamples - 1).coerceAtLeast(1)
        val chirp = (progress - 0.5f) * 2f
        return (baseFrequency + (chirp * RESCUE_CHIRP_SPAN_HZ)).coerceIn(SWEEP_MIN_HZ, SWEEP_MAX_HZ)
    }

    private fun edgeEnvelope(
        sampleIndexInSegment: Int,
        segmentSamples: Int,
        edgeSamples: Int
    ): Double {
        if (segmentSamples <= edgeSamples * 2) {
            return 1.0
        }
        val attack = sampleIndexInSegment.toDouble() / edgeSamples.toDouble()
        val release = (segmentSamples - sampleIndexInSegment - 1).toDouble() / edgeSamples.toDouble()
        return min(1.0, min(attack, release).coerceAtLeast(0.0))
    }

    private fun advancePhase(currentPhase: Double, frequencyHz: Float): Double {
        var nextPhase = currentPhase + ((2 * PI * frequencyHz) / SAMPLE_RATE)
        if (nextPhase > 2 * PI) {
            nextPhase %= (2 * PI)
        }
        return nextPhase
    }
}
