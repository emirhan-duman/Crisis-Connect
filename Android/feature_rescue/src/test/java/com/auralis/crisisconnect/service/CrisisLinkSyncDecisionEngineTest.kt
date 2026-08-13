package com.auralis.crisisconnect.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisLinkSyncDecisionEngineTest {

    private val engine = CrisisLinkSyncDecisionEngine()

    @Test
    fun `shouldDeliver returns true when there is no previous sync state`() {
        val result = engine.shouldDeliverPayload(
            previousState = null,
            payload = payload(activeSignalIds = setOf("a")),
            now = 10_000L,
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns false when below minimum delivery interval and no change`() {
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 5
        )
        val now = previous.syncedAtMillis + engine.minimumDeliveryIntervalMs(
            CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        ) - 1

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                totalUniqueSignalCount = 1,
                totalScanEventCount = 5
            ),
            now = now,
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertFalse(result)
    }

    @Test
    fun `shouldDeliver returns true when live location interval elapsed`() {
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a")
        )
        val now = 105_000L

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(activeSignalIds = setOf("a")),
            now = now,
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED,
            liveLocationSettings = liveLocation(enabled = true, intervalMillis = 5_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when active signal set changes`() {
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 5
        )
        val now = 100_000L + engine.minimumDeliveryIntervalMs(
            CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("b"),
                totalUniqueSignalCount = 1,
                totalScanEventCount = 5
            ),
            now = now,
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when scan event delta exceeds threshold`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        val threshold = engine.scanEventDeltaSyncThreshold(profile)
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 10
        )
        val now = 100_000L + engine.minimumDeliveryIntervalMs(profile)

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                totalUniqueSignalCount = 1,
                totalScanEventCount = 10 + threshold
            ),
            now = now,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when meaningful location change happens by distance`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            location = location(41.000000, 29.000000, 50f)
        )
        val now = 100_000L + engine.minimumDeliveryIntervalMs(profile)

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                location = location(41.000500, 29.000000, 50f)
            ),
            now = now,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when accuracy improves significantly`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            location = location(41.0, 29.0, 60f)
        )
        val now = 100_000L + engine.minimumDeliveryIntervalMs(profile)

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                location = location(41.0, 29.0, 20f)
            ),
            now = now,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true on active heartbeat interval`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 1
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                totalUniqueSignalCount = 1,
                totalScanEventCount = 1
            ),
            now = 130_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true on idle heartbeat interval`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 1
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                totalUniqueSignalCount = 1,
                totalScanEventCount = 1
            ),
            now = 280_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `determineSyncProfile chooses balanced for metered pending sync while not charging`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = true,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = true,
            batteryState = battery(levelPercent = 70, isCharging = false, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED, profile)
    }

    @Test
    fun `determineSyncProfile chooses realtime for charging active unmetered`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = true,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 80, isCharging = true, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME, profile)
    }

    @Test
    fun `determineSyncProfile chooses eco in power save mode`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 70, isCharging = false, isPowerSaveMode = true)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.ECO, profile)
    }

    @Test
    fun `nextSyncDelay returns pending no-network backoff`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = true,
            hasPendingQueue = true,
            authorizationBackoff = false,
            hasValidatedInternet = false,
            liveLocationSettings = liveLocation(enabled = true, intervalMillis = 10_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        )

        assertEquals(8 * 60_000L, delay)
    }

    @Test
    fun `nextSyncDelay clamps to live location interval when smaller than base delay`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = false,
            authorizationBackoff = false,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = true, intervalMillis = 10_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        )

        assertEquals(10_000L, delay)
    }

    @Test
    fun `determineSyncProfile chooses realtime for pending queue on unmetered network`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = true,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 10, isCharging = false, isPowerSaveMode = true)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME, profile)
    }

    @Test
    fun `determineSyncProfile chooses realtime for charging metered pending queue`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = true,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = true,
            batteryState = battery(levelPercent = 12, isCharging = true, isPowerSaveMode = true)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME, profile)
    }

    @Test
    fun `determineSyncProfile chooses eco when battery is low and not charging`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 20, isCharging = false, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.ECO, profile)
    }

    @Test
    fun `determineSyncProfile chooses balanced when battery low but charging`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = false,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 5, isCharging = true, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED, profile)
    }

    @Test
    fun `determineSyncProfile chooses eco for active signals on metered while not charging`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = true,
            hasValidatedInternet = true,
            isMeteredNetwork = true,
            batteryState = battery(levelPercent = 90, isCharging = false, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.ECO, profile)
    }

    @Test
    fun `determineSyncProfile chooses balanced for active unmetered when not charging`() {
        val profile = engine.determineSyncProfile(
            hasPendingQueue = false,
            hasActiveSignals = true,
            hasValidatedInternet = true,
            isMeteredNetwork = false,
            batteryState = battery(levelPercent = 90, isCharging = false, isPowerSaveMode = false)
        )

        assertEquals(CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED, profile)
    }

    @Test
    fun `shouldDeliver stays false when scan delta below threshold and no other change`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        val threshold = engine.scanEventDeltaSyncThreshold(profile)
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            totalUniqueSignalCount = 2,
            totalScanEventCount = 50
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                totalUniqueSignalCount = 2,
                totalScanEventCount = 50 + threshold - 1
            ),
            now = 120_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 30_000L)
        )

        assertFalse(result)
    }

    @Test
    fun `shouldDeliver stays false for minor location drift before idle heartbeat`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            location = location(41.000000, 29.000000, 20f)
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                location = location(41.000010, 29.000000, 19f)
            ),
            now = 191_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertFalse(result)
    }

    @Test
    fun `shouldDeliver returns true when previous location missing and current exists`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            location = null
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                location = location(41.0, 29.0, 15f)
            ),
            now = 191_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when current location missing and previous exists`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            location = location(41.0, 29.0, 15f)
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                location = null
            ),
            now = 191_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `shouldDeliver returns true when unique signal count changes`() {
        val profile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = emptySet(),
            totalUniqueSignalCount = 5,
            totalScanEventCount = 100
        )

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = emptySet(),
                totalUniqueSignalCount = 6,
                totalScanEventCount = 100
            ),
            now = 191_000L,
            syncProfile = profile,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertTrue(result)
    }

    @Test
    fun `nextSyncDelay uses authorization backoff over active interval`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = true,
            hasPendingQueue = true,
            authorizationBackoff = true,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 5_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.REALTIME
        )

        assertEquals(120_000L, delay)
    }

    @Test
    fun `nextSyncDelay returns active interval for pending queue with validated internet`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = true,
            authorizationBackoff = false,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 5_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        )

        assertEquals(45_000L, delay)
    }

    @Test
    fun `nextSyncDelay returns idle interval when no activity and no queue`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = false,
            authorizationBackoff = false,
            hasValidatedInternet = true,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 5_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.ECO
        )

        assertEquals(5 * 60_000L, delay)
    }

    @Test
    fun `nextSyncDelay does not clamp to live location without validated internet`() {
        val delay = engine.nextSyncDelayMillis(
            hasActiveSignals = false,
            hasPendingQueue = false,
            authorizationBackoff = false,
            hasValidatedInternet = false,
            liveLocationSettings = liveLocation(enabled = true, intervalMillis = 10_000L),
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED
        )

        assertEquals(2 * 60_000L, delay)
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

    private fun location(
        lat: Double,
        lon: Double,
        accuracy: Float?
    ): CrisisLinkSyncDecisionEngine.LocationSnapshot {
        return CrisisLinkSyncDecisionEngine.LocationSnapshot(
            latitude = lat,
            longitude = lon,
            accuracyMeters = accuracy
        )
    }

    @Test
    fun `shouldDeliver returns true for a newly sighted victim even below the minimum interval`() {
        // The throttle exists to stop telemetry churn, not to hold a casualty. Before this, a beacon
        // first heard inside the minimum-delivery window was suppressed for the rest of it -- long
        // enough on ECO for the rescuer to walk out of range and lose the sighting entirely.
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a"),
            totalUniqueSignalCount = 1,
            totalScanEventCount = 5
        )
        val now = previous.syncedAtMillis + 1

        for (profile in CrisisLinkSyncDecisionEngine.SyncProfile.values()) {
            val result = engine.shouldDeliverPayload(
                previousState = previous,
                payload = payload(
                    activeSignalIds = setOf("a", "b"),
                    totalUniqueSignalCount = 2,
                    totalScanEventCount = 5
                ),
                now = now,
                syncProfile = profile,
                liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
            )
            assertTrue("a new victim must not wait on the $profile throttle", result)
        }
    }

    @Test
    fun `shouldDeliver still throttles when a victim only drops out of range`() {
        // Asymmetric on purpose: a beacon flapping at the edge of range must not be able to defeat
        // the rate limiter, so only an ADDED id bypasses it.
        val previous = previous(
            syncedAtMillis = 100_000L,
            activeSignalIds = setOf("a", "b"),
            totalUniqueSignalCount = 2,
            totalScanEventCount = 5
        )
        val now = previous.syncedAtMillis + 1

        val result = engine.shouldDeliverPayload(
            previousState = previous,
            payload = payload(
                activeSignalIds = setOf("a"),
                totalUniqueSignalCount = 2,
                totalScanEventCount = 5
            ),
            now = now,
            syncProfile = CrisisLinkSyncDecisionEngine.SyncProfile.BALANCED,
            liveLocationSettings = liveLocation(enabled = false, intervalMillis = 60_000L)
        )

        assertFalse(result)
    }

    private fun previous(
        syncedAtMillis: Long,
        activeSignalIds: Set<String>,
        location: CrisisLinkSyncDecisionEngine.LocationSnapshot? = null,
        totalUniqueSignalCount: Long = 0,
        totalScanEventCount: Long = 0
    ): CrisisLinkSyncDecisionEngine.LastSuccessfulSyncState {
        return CrisisLinkSyncDecisionEngine.LastSuccessfulSyncState(
            activeSignalIds = activeSignalIds,
            location = location,
            totalUniqueSignalCount = totalUniqueSignalCount,
            totalScanEventCount = totalScanEventCount,
            syncedAtMillis = syncedAtMillis
        )
    }

    private fun payload(
        activeSignalIds: Set<String>,
        location: CrisisLinkSyncDecisionEngine.LocationSnapshot? = null,
        totalUniqueSignalCount: Long = 0,
        totalScanEventCount: Long = 0
    ): CrisisLinkSyncDecisionEngine.PayloadSnapshot {
        return CrisisLinkSyncDecisionEngine.PayloadSnapshot(
            activeSignalIds = activeSignalIds,
            location = location,
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
