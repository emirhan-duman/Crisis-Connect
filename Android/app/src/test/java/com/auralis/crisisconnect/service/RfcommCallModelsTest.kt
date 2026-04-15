package com.auralis.crisisconnect.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RfcommCallModelsTest {

    @Test
    fun `receiver observed age summary reports median and p95`() {
        val stats = NetStats().apply {
            receiverObservedAgeMs += listOf(12, 18, 20, 24, 40)
            receiverObservedAgeClampedSamples = 1
            receiverObservedAgeDiscardedSamples = 2
        }

        val summary = stats.receiverObservedAgeSummary() ?: error("Missing latency summary")

        assertEquals(5, summary.sampleCount)
        assertEquals(12, summary.minMs)
        assertEquals(20, summary.medianMs)
        assertEquals(37, summary.p95Ms)
        assertEquals(40, summary.maxMs)
        assertEquals(22.8, summary.averageMs, 0.001)
        assertEquals(1, summary.clampedSamples)
        assertEquals(2, summary.discardedSamples)
    }

    @Test
    fun `receiver observed age recording clamps small negative skew and discards implausible samples`() {
        val stats = NetStats()

        stats.recordReceiverObservedAgeSample(senderTimestampMs = 1_000L, receivedAtMs = 980L)
        stats.recordReceiverObservedAgeSample(senderTimestampMs = 1_000L, receivedAtMs = 1_055L)
        stats.recordReceiverObservedAgeSample(senderTimestampMs = 1_000L, receivedAtMs = 90_000L)

        assertEquals(listOf(0, 55), stats.receiverObservedAgeMs)
        assertEquals(1, stats.receiverObservedAgeClampedSamples)
        assertEquals(1, stats.receiverObservedAgeDiscardedSamples)

        stats.clearReceiverObservedAgeSamples()

        assertEquals(emptyList<Int>(), stats.receiverObservedAgeMs)
        assertEquals(0, stats.receiverObservedAgeClampedSamples)
        assertEquals(0, stats.receiverObservedAgeDiscardedSamples)
        assertNull(stats.receiverObservedAgeSummary())
    }

    @Test
    fun `clock sync observation computes offset and rtt`() {
        val observation = computeClockSyncObservation(
            requestSentAtMs = 1_000L,
            remoteReceivedAtMs = 1_120L,
            remoteSentAtMs = 1_125L,
            responseReceivedAtMs = 1_085L
        ) ?: error("Missing clock sync observation")

        assertEquals(80.0, observation.remoteClockOffsetMs, 0.001)
        assertEquals(80.0, observation.rttMs, 0.001)
    }

    @Test
    fun `clock adjusted age summary uses best sync offset`() {
        val stats = NetStats()
        stats.recordClockSyncObservation(
            ClockSyncObservation(
                remoteClockOffsetMs = 235_829_280.0,
                rttMs = 40.0
            )
        )
        stats.recordClockAdjustedAgeSample(
            senderMonotonicTimestampMs = 947_345_825L,
            receivedAtMs = 711_516_622L
        )
        stats.recordClockAdjustedAgeSample(
            senderMonotonicTimestampMs = 947_345_845L,
            receivedAtMs = 711_516_660L
        )

        val summary = stats.clockAdjustedAgeSummary() ?: error("Missing adjusted latency summary")
        val syncSummary = stats.clockSyncSummary() ?: error("Missing clock sync summary")

        assertEquals(2, summary.sampleCount)
        assertEquals(77, summary.minMs)
        assertEquals(86, summary.medianMs)
        assertEquals(94, summary.p95Ms)
        assertEquals(95, summary.maxMs)
        assertEquals(86.0, summary.averageMs, 0.001)
        assertEquals(1, syncSummary.sampleCount)
        assertEquals(40, syncSummary.bestRttMs)
        assertEquals(235_829_280, syncSummary.bestRemoteClockOffsetMs)
    }
}
