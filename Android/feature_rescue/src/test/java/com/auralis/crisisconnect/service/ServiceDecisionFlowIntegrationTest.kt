package com.auralis.crisisconnect.service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceDecisionFlowIntegrationTest {

    private val engine = CrisisLinkSyncDecisionEngine()

    @Test
    fun `crisis link flow with pending queue on metered picks balanced and delivers immediately`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = true,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = true,
            batteryState = battery(levelPercent = 70, isCharging = false, isPowerSaveMode = false)
        )
        val shouldDeliver = engine.shouldDeliverPayload(
            previousState = null,
            payload = payload(activeSignalIds = emptySet(), totalUniqueSignalCount = 1, totalScanEventCount = 1),
            now = 1_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )
        val nextDelay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = true,
            authorizationBackoff = false,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L),
            syncProfile = profile
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED, profile)
        assertTrue(shouldDeliver)
        assertEquals(45_000L, nextDelay)
    }

    @Test
    fun `crisis link quiet eco flow defers delivery and uses idle cadence`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 10, isCharging = false, isPowerSaveMode = false)
        )
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            totalUniqueSignalCount = 2,
            totalScanEventCount = 10
        )
        val shouldDeliver = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(activeSignalIds = emptySet(), totalUniqueSignalCount = 2, totalScanEventCount = 10),
            now = 341_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )
        val nextDelay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = false,
            authorizationBackoff = false,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L),
            syncProfile = profile
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.ECO, profile)
        assertFalse(shouldDeliver)
        assertEquals(5 * 60_000L, nextDelay)
    }

    @Test
    fun `crisis link pending queue without internet uses network-backoff interval`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        val nextDelay = engine.nextSyncDelayMillis(
            hasActiveSignals = true,
            hasPendingQueue = true,
            authorizationBackoff = false,
            hasValidatedInternet = false,
            liveLocationSettings = liveLocation(enabled = true, intervalMillis = 5_000L),
            syncProfile = profile
        )

        assertEquals(8 * 60_000L, nextDelay)
    }

    private fun battery(
        levelPercent: Int?,
        isCharging: Boolean,
        isPowerSaveMode: Boolean
    ): CrisisLinkSyncDecisionEngine.BatteryState {
        return CrisisLinkSyncDecisionEngine.BatteryState(
            levelPercent = levelPercent,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode
        )
    }

    private fun previous(
        syncedAtMillis: Long,
        activeSignalIds: Set<String>,
        totalUniqueSignalCount: Long = 0,
        totalScanEventCount: Long = 0
    ): CrisisLinkSyncDecisionEngine.LastSuccessfulSyncState {
        return CrisisLinkSyncDecisionEngine.LastSuccessfulSyncState(
            activeSignalIds = activeSignalIds,
            location = null,
            totalUniqueSignalCount = totalUniqueSignalCount,
            totalScanEventCount = totalScanEventCount,
            syncedAtMillis = syncedAtMillis
        )
    }

    private fun payload(
        activeSignalIds: Set<String>,
        totalUniqueSignalCount: Long = 0,
        totalScanEventCount: Long = 0
    ): CrisisLinkSyncDecisionEngine.PayloadSnapshot {
        return CrisisLinkSyncDecisionEngine.PayloadSnapshot(
            activeSignalIds = activeSignalIds,
            location = null,
            totalUniqueSignalCount = totalUniqueSignalCount,
            totalScanEventCount = totalScanEventCount
        )
    }

    private fun liveLocation(
        enabled: Boolean,
        intervalMillis: Long
    ): CrisisLinkSyncDecisionEngine.LiveLocationSettings {
        return CrisisLinkSyncDecisionEngine.LiveLocationSettings(
            enabled = enabled,
            intervalMillis = intervalMillis
        )
    }
}
