package com.auralis.crisisconnect.service

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class CrisisLinkSyncDecisionEngine(
    private val config: TimingConfig = TimingConfig.DEFAULT
) {

    data class BatteryState(
        val levelPercent: Int?,
        val isCharging: Boolean,
        val isPowerSaveMode: Boolean
    )

    data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?
    )

    data class LastSuccessfulSyncState(
        val activeSignalIds: Set<String>,
        val location: LocationSnapshot?,
        val totalUniqueSignalCount: Long,
        val totalScanEventCount: Long,
        val syncedAtMillis: Long
    )

    data class PayloadSnapshot(
        val activeSignalIds: Set<String>,
        val location: LocationSnapshot?,
        val totalUniqueSignalCount: Long,
        val totalScanEventCount: Long
    )

    data class LiveLocationSettings(
        val enabled: Boolean,
        val intervalMillis: Long
    )

    enum class SyncProfile {
        REALTIME,
        BALANCED,
        ECO
    }

    data class TimingConfig(
        val activeSyncIntervalRealtimeMs: Long,
        val activeSyncIntervalBalancedMs: Long,
        val activeSyncIntervalEcoMs: Long,
        val idleSyncIntervalRealtimeMs: Long,
        val idleSyncIntervalBalancedMs: Long,
        val idleSyncIntervalEcoMs: Long,
        val authFailureRetryIntervalMs: Long,
        val noValidatedNetworkPendingSyncIntervalMs: Long,
        val activeSignalHeartbeatRealtimeMs: Long,
        val activeSignalHeartbeatBalancedMs: Long,
        val activeSignalHeartbeatEcoMs: Long,
        val idleHeartbeatRealtimeMs: Long,
        val idleHeartbeatBalancedMs: Long,
        val idleHeartbeatEcoMs: Long,
        val minimumDeliveryIntervalRealtimeMs: Long,
        val minimumDeliveryIntervalBalancedMs: Long,
        val minimumDeliveryIntervalEcoMs: Long,
        val scanEventDeltaThresholdRealtime: Long,
        val scanEventDeltaThresholdBalanced: Long,
        val scanEventDeltaThresholdEco: Long,
        val lowBatteryLevelPercent: Int,
        val locationDistanceDeltaMeters: Float,
        val locationAccuracyImprovementMeters: Float
    ) {
        companion object {
            val DEFAULT = TimingConfig(
                activeSyncIntervalRealtimeMs = 15_000L,
                activeSyncIntervalBalancedMs = 45_000L,
                activeSyncIntervalEcoMs = 2 * 60_000L,
                idleSyncIntervalRealtimeMs = 60_000L,
                idleSyncIntervalBalancedMs = 2 * 60_000L,
                idleSyncIntervalEcoMs = 5 * 60_000L,
                authFailureRetryIntervalMs = 120_000L,
                noValidatedNetworkPendingSyncIntervalMs = 8 * 60_000L,
                activeSignalHeartbeatRealtimeMs = 30_000L,
                activeSignalHeartbeatBalancedMs = 90_000L,
                activeSignalHeartbeatEcoMs = 3 * 60_000L,
                idleHeartbeatRealtimeMs = 3 * 60_000L,
                idleHeartbeatBalancedMs = 5 * 60_000L,
                idleHeartbeatEcoMs = 10 * 60_000L,
                minimumDeliveryIntervalRealtimeMs = 15_000L,
                minimumDeliveryIntervalBalancedMs = 90_000L,
                minimumDeliveryIntervalEcoMs = 4 * 60_000L,
                scanEventDeltaThresholdRealtime = 25L,
                scanEventDeltaThresholdBalanced = 120L,
                scanEventDeltaThresholdEco = 300L,
                lowBatteryLevelPercent = 20,
                locationDistanceDeltaMeters = 25f,
                locationAccuracyImprovementMeters = 20f
            )
        }
    }

    fun determineSyncProfile(
        hasPendingQueue: Boolean,
        hasActiveSignals: Boolean,
        hasValidatedInternet: Boolean,
        isMeteredNetwork: Boolean,
        batteryState: BatteryState
    ): SyncProfile {
        if (hasPendingQueue && hasValidatedInternet) {
            return if (isMeteredNetwork && !batteryState.isCharging) {
                SyncProfile.BALANCED
            } else {
                SyncProfile.REALTIME
            }
        }
        val lowBattery = (batteryState.levelPercent ?: 100) <= config.lowBatteryLevelPercent &&
            !batteryState.isCharging
        if (batteryState.isPowerSaveMode || lowBattery) {
            return SyncProfile.ECO
        }
        if (hasActiveSignals && isMeteredNetwork && !batteryState.isCharging) {
            return SyncProfile.ECO
        }
        return if (hasActiveSignals && batteryState.isCharging && !isMeteredNetwork) {
            SyncProfile.REALTIME
        } else {
            SyncProfile.BALANCED
        }
    }

    fun shouldDeliverPayload(
        previousState: LastSuccessfulSyncState?,
        payload: PayloadSnapshot,
        now: Long,
        syncProfile: SyncProfile,
        liveLocationSettings: LiveLocationSettings
    ): Boolean {
        val previous = previousState ?: return true
        val elapsedSinceLastDelivery = now - previous.syncedAtMillis
        if (liveLocationSettings.enabled &&
            elapsedSinceLastDelivery >= liveLocationSettings.intervalMillis
        ) {
            return true
        }
        if (elapsedSinceLastDelivery < minimumDeliveryIntervalMs(syncProfile)) {
            return false
        }
        if (payload.activeSignalIds != previous.activeSignalIds) {
            return true
        }
        if (payload.totalUniqueSignalCount != previous.totalUniqueSignalCount) {
            return true
        }
        if ((payload.totalScanEventCount - previous.totalScanEventCount) >= scanEventDeltaSyncThreshold(syncProfile)) {
            return true
        }
        if (hasMeaningfulLocationChange(previous.location, payload.location)) {
            return true
        }
        if (payload.activeSignalIds.isNotEmpty() &&
            elapsedSinceLastDelivery >= activeSignalHeartbeatIntervalMs(syncProfile)
        ) {
            return true
        }
        return elapsedSinceLastDelivery >= idleHeartbeatIntervalMs(syncProfile)
    }

    fun nextSyncDelayMillis(
        hasActiveSignals: Boolean,
        hasPendingQueue: Boolean,
        authorizationBackoff: Boolean,
        hasValidatedInternet: Boolean,
        liveLocationSettings: LiveLocationSettings,
        syncProfile: SyncProfile
    ): Long {
        if (hasPendingQueue && !hasValidatedInternet) {
            return config.noValidatedNetworkPendingSyncIntervalMs
        }
        val baseDelay = when {
            authorizationBackoff -> config.authFailureRetryIntervalMs
            hasPendingQueue -> activeSyncIntervalMs(syncProfile)
            hasActiveSignals -> activeSyncIntervalMs(syncProfile)
            else -> idleSyncIntervalMs(syncProfile)
        }
        if (!liveLocationSettings.enabled || !hasValidatedInternet) {
            return baseDelay
        }
        return minOf(baseDelay, liveLocationSettings.intervalMillis)
    }

    fun minimumDeliveryIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.minimumDeliveryIntervalRealtimeMs
            SyncProfile.BALANCED -> config.minimumDeliveryIntervalBalancedMs
            SyncProfile.ECO -> config.minimumDeliveryIntervalEcoMs
        }
    }

    fun scanEventDeltaSyncThreshold(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.scanEventDeltaThresholdRealtime
            SyncProfile.BALANCED -> config.scanEventDeltaThresholdBalanced
            SyncProfile.ECO -> config.scanEventDeltaThresholdEco
        }
    }

    private fun activeSyncIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.activeSyncIntervalRealtimeMs
            SyncProfile.BALANCED -> config.activeSyncIntervalBalancedMs
            SyncProfile.ECO -> config.activeSyncIntervalEcoMs
        }
    }

    private fun idleSyncIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.idleSyncIntervalRealtimeMs
            SyncProfile.BALANCED -> config.idleSyncIntervalBalancedMs
            SyncProfile.ECO -> config.idleSyncIntervalEcoMs
        }
    }

    private fun activeSignalHeartbeatIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.activeSignalHeartbeatRealtimeMs
            SyncProfile.BALANCED -> config.activeSignalHeartbeatBalancedMs
            SyncProfile.ECO -> config.activeSignalHeartbeatEcoMs
        }
    }

    private fun idleHeartbeatIntervalMs(profile: SyncProfile): Long {
        return when (profile) {
            SyncProfile.REALTIME -> config.idleHeartbeatRealtimeMs
            SyncProfile.BALANCED -> config.idleHeartbeatBalancedMs
            SyncProfile.ECO -> config.idleHeartbeatEcoMs
        }
    }

    private fun hasMeaningfulLocationChange(
        previous: LocationSnapshot?,
        current: LocationSnapshot?
    ): Boolean {
        if (previous == null && current == null) {
            return false
        }
        if (previous == null || current == null) {
            return true
        }
        val distanceMeters = haversineDistanceMeters(previous, current)
        if (distanceMeters >= config.locationDistanceDeltaMeters) {
            return true
        }
        val previousAccuracy = previous.accuracyMeters
        val currentAccuracy = current.accuracyMeters
        if (previousAccuracy != null && currentAccuracy != null) {
            val improvement = previousAccuracy - currentAccuracy
            if (improvement >= config.locationAccuracyImprovementMeters) {
                return true
            }
        }
        return false
    }

    private fun haversineDistanceMeters(a: LocationSnapshot, b: LocationSnapshot): Float {
        val lat1 = Math.toRadians(a.latitude)
        val lon1 = Math.toRadians(a.longitude)
        val lat2 = Math.toRadians(b.latitude)
        val lon2 = Math.toRadians(b.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1

        val sinLat = sin(dLat / 2.0)
        val sinLon = sin(dLon / 2.0)
        val chord = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
        val angle = 2.0 * atan2(sqrt(chord), sqrt(1.0 - chord))
        val distanceMeters = EARTH_RADIUS_METERS * angle
        return distanceMeters.toFloat()
    }

    private companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
