package com.auralis.crisisconnect.service.voice

import android.util.Log
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

class JitterBuffer(
    private val frameDurationMs: Int = VoipConfig.FRAME_DURATION_MS,
    initialTargetDelayMs: Int = VoipConfig.JITTER_DEFAULT_DELAY_MS,
    private val minDelayMs: Int = VoipConfig.JITTER_MIN_DELAY_MS,
    private val maxDelayMs: Int = VoipConfig.JITTER_MAX_DELAY_MS,
    maxBufferedFrames: Int = (VoipConfig.JITTER_MAX_DELAY_MS / VoipConfig.FRAME_DURATION_MS) + VoipConfig.FRAMES_PER_PACKET
) {

    data class EnqueueResult(
        val depthFrames: Int,
        val gapDetected: Int,
        val outOfOrder: Boolean,
        val duplicate: Boolean,
        val droppedLate: Boolean
    )

    private val lock = Any()
    private val orderedFrames = TreeMap<Int, ByteArray>()
    private val unorderedFrames = ArrayDeque<ByteArray>()
    private val capacityFrames = maxBufferedFrames.coerceAtLeast(4)
    private val reorderWindowFrames = max(2, capacityFrames / 2)
    private var expectedSeq: Int? = null
    private var highestSeq: Int? = null
    private var lastArrivalSeq: Int? = null
    private var hasPolledFrame = false
    private var stabilityScore = 0
    private var lateScore = 0
    @Volatile
    private var targetDelayMs: Int = initialTargetDelayMs.coerceIn(minDelayMs, maxDelayMs)

    fun reset() {
        synchronized(lock) {
            orderedFrames.clear()
            unorderedFrames.clear()
            expectedSeq = null
            highestSeq = null
            lastArrivalSeq = null
            hasPolledFrame = false
            stabilityScore = 0
            lateScore = 0
            targetDelayMs = VoipConfig.JITTER_DEFAULT_DELAY_MS
        }
    }

    fun addFrame(frame: ByteArray, seq: Int): EnqueueResult {
        synchronized(lock) {
            if (seq <= 0) {
                unorderedFrames.addLast(frame)
                trimToCapacity()
                adjustDelay(gapDetected = 0, outOfOrder = false, duplicate = false, droppedLate = false)
                return EnqueueResult(
                    depthFrames = totalBufferedFramesLocked(),
                    gapDetected = 0,
                    outOfOrder = false,
                    duplicate = false,
                    droppedLate = false
                )
            }

            val expected = expectedSeq
            if (hasPolledFrame && expected != null && seq < expected - reorderWindowFrames) {
                adjustDelay(gapDetected = 0, outOfOrder = true, duplicate = false, droppedLate = true)
                return EnqueueResult(
                    depthFrames = totalBufferedFramesLocked(),
                    gapDetected = 0,
                    outOfOrder = true,
                    duplicate = false,
                    droppedLate = true
                )
            }

            if (orderedFrames.containsKey(seq)) {
                adjustDelay(gapDetected = 0, outOfOrder = false, duplicate = true, droppedLate = false)
                return EnqueueResult(
                    depthFrames = totalBufferedFramesLocked(),
                    gapDetected = 0,
                    outOfOrder = false,
                    duplicate = true,
                    droppedLate = false
                )
            }

            val previousArrival = lastArrivalSeq
            val previousHighest = highestSeq
            val outOfOrder = previousArrival != null && seq < previousArrival
            val gapDetected = if (previousHighest != null && seq > previousHighest + 1) {
                seq - previousHighest - 1
            } else {
                0
            }

            orderedFrames[seq] = frame
            if (expectedSeq == null) {
                expectedSeq = seq
            } else if (!hasPolledFrame && expected != null && seq < expected) {
                expectedSeq = seq
            }
            if (highestSeq == null || seq > highestSeq!!) {
                highestSeq = seq
            }
            lastArrivalSeq = seq
            trimToCapacity()
            adjustDelay(gapDetected = gapDetected, outOfOrder = outOfOrder, duplicate = false, droppedLate = false)
            return EnqueueResult(
                depthFrames = totalBufferedFramesLocked(),
                gapDetected = gapDetected,
                outOfOrder = outOfOrder,
                duplicate = false,
                droppedLate = false
            )
        }
    }

    fun pollFrame(): ByteArray? = synchronized(lock) {
        pollOrderedFrameLocked() ?: unorderedFrames.pollFirst()
    }

    fun depthFrames(): Int = synchronized(lock) { totalBufferedFramesLocked() }

    fun depthMs(): Int = depthFrames() * frameDurationMs

    fun targetDelayMs(): Int = targetDelayMs

    fun shouldStartPlayback(): Boolean = synchronized(lock) {
        (totalBufferedFramesLocked() * frameDurationMs) >= targetDelayMs
    }

    fun snapshot(label: String) {
        val snapshot = synchronized(lock) {
            Triple(totalBufferedFramesLocked(), orderedFrames.size, unorderedFrames.size)
        }
        Log.d(
            label,
            "jitter depth=${snapshot.first * frameDurationMs}ms target=$targetDelayMs lateScore=$lateScore stable=$stabilityScore ordered=${snapshot.second} unordered=${snapshot.third}"
        )
    }

    private fun pollOrderedFrameLocked(): ByteArray? {
        if (orderedFrames.isEmpty()) {
            return null
        }
        val expected = expectedSeq
        if (expected == null) {
            val first = orderedFrames.pollFirstEntry() ?: return null
            expectedSeq = first.key + 1
            hasPolledFrame = true
            return first.value
        }

        val exact = orderedFrames.remove(expected)
        if (exact != null) {
            expectedSeq = expected + 1
            hasPolledFrame = true
            return exact
        }

        val depthFrames = totalBufferedFramesLocked()
        val shouldSkipMissing = orderedFrames.size >= reorderWindowFrames ||
            (depthFrames * frameDurationMs) > (targetDelayMs + frameDurationMs * 2)
        if (!shouldSkipMissing) {
            return null
        }

        val fallback = orderedFrames.pollFirstEntry() ?: return null
        expectedSeq = fallback.key + 1
        hasPolledFrame = true
        adjustDelay(gapDetected = 1, outOfOrder = true, duplicate = false, droppedLate = false)
        return fallback.value
    }

    private fun trimToCapacity() {
        while (totalBufferedFramesLocked() > capacityFrames) {
            if (orderedFrames.isNotEmpty()) {
                val removed = orderedFrames.pollFirstEntry()
                if (removed != null) {
                    val expected = expectedSeq
                    if (expected != null && removed.key <= expected) {
                        expectedSeq = removed.key + 1
                    }
                }
            } else if (unorderedFrames.isNotEmpty()) {
                unorderedFrames.removeFirst()
            } else {
                return
            }
        }
    }

    private fun totalBufferedFramesLocked(): Int = orderedFrames.size + unorderedFrames.size

    private fun adjustDelay(
        gapDetected: Int,
        outOfOrder: Boolean,
        duplicate: Boolean,
        droppedLate: Boolean
    ) {
        if (duplicate) {
            return
        }
        val unstable = gapDetected > 0 || outOfOrder || droppedLate
        if (unstable) {
            val penalty = gapDetected.coerceIn(1, 3)
            lateScore += penalty
            stabilityScore = 0
            if (lateScore >= 2) {
                targetDelayMs = min(targetDelayMs + frameDurationMs, maxDelayMs)
                lateScore = 0
            }
            return
        }
        stabilityScore += 1
        lateScore = max(0, lateScore - 1)
        if (stabilityScore >= 18 && targetDelayMs > minDelayMs) {
            targetDelayMs = max(targetDelayMs - frameDurationMs, minDelayMs)
            stabilityScore = 0
        }
    }
}
