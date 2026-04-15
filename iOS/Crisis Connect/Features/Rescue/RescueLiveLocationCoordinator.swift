//
//  RescueLiveLocationCoordinator.swift
//  Crisis Connect
//
//  Created by Codex on 18.03.2026.
//

import Foundation
import Combine
import CoreLocation
import FirebaseAuth
import FirebaseFirestore
import UIKit

@MainActor
final class RescueLiveLocationCoordinator: NSObject, CLLocationManagerDelegate {
    static let shared = RescueLiveLocationCoordinator()

    private let rescueManager: RescueClientManager
    private let settingsStore: RescueSettingsStore
    private let dashboardSyncService: CrisisLinkDashboardSyncService
    private let locationManager = CLLocationManager()

    private var cancellables: Set<AnyCancellable> = []
    private var syncTimer: Timer?
    private var syncRetryWorkItem: DispatchWorkItem?
    private var pendingSyncDrainTask: Task<Void, Never>?
    private var hasBootstrapped = false
    private var applicationIsActive = false
    private var pendingImmediateSync = false
    private var currentTimerInterval: TimeInterval?
    private var hasPublishedDashboardLocation = false
    private var isWarmingLocation = false
    private var isMonitoringSignificantLocationChanges = false
    private var requestedAlwaysAuthorization = false
    private var backgroundTaskIdentifier: UIBackgroundTaskIdentifier = .invalid
    private let pendingSyncStore = PendingDashboardSyncStore()
    private var pendingSyncOperations: [PendingDashboardSyncOperation] = []

    init(
        rescueManager: RescueClientManager? = nil,
        settingsStore: RescueSettingsStore? = nil,
        dashboardSyncService: CrisisLinkDashboardSyncService? = nil
    ) {
        self.rescueManager = rescueManager ?? .shared
        self.settingsStore = settingsStore ?? .shared
        self.dashboardSyncService = dashboardSyncService ?? .shared
        super.init()
        locationManager.delegate = self
        pendingSyncOperations = pendingSyncStore.load()
        configureLocationManager()
    }

    func bootstrap() {
        if !hasBootstrapped {
            hasBootstrapped = true
            if Thread.isMainThread {
                applicationIsActive = UIApplication.shared.applicationState == .active
            } else {
                DispatchQueue.main.sync {
                    self.applicationIsActive = UIApplication.shared.applicationState == .active
                }
            }
            observeSettings()
            observeApplicationLifecycle()
        }
        configureLocationManager()
        refreshScheduling(requestImmediateSync: true)
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let authorized = manager.authorizationStatus == .authorizedWhenInUse || manager.authorizationStatus == .authorizedAlways
        refreshScheduling(requestImmediateSync: authorized)
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard liveLocationEnabled else { return }
        guard let location = locations.last else { return }
        if !applicationIsActive {
            beginBackgroundTaskIfNeeded()
        }
        stopLocationWarmupIfNeeded()
        publishLocationUpdate(from: location, allowTransportBroadcast: true)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        guard let locationError = error as? CLError else { return }
        switch locationError.code {
        case .locationUnknown, .network:
            break
        default:
            pendingImmediateSync = false
        }
    }

    func locationManagerDidPauseLocationUpdates(_ manager: CLLocationManager) {
        isWarmingLocation = false
    }

    func locationManagerDidResumeLocationUpdates(_ manager: CLLocationManager) {
        if applicationIsActive {
            isWarmingLocation = true
        }
    }

    private func observeSettings() {
        Publishers.Merge4(
            settingsStore.$crisisLinkEnabled.map { _ in () },
            settingsStore.$crisisLinkLiveLocationEnabled.map { _ in () },
            settingsStore.$crisisLinkLiveLocationIntervalSeconds.map { _ in () },
            NotificationCenter.default.publisher(for: .privacyPreferencesDidChange).map { _ in () }
        )
        .receive(on: RunLoop.main)
        .sink { [weak self] _ in
            self?.refreshScheduling(requestImmediateSync: true)
        }
        .store(in: &cancellables)
    }

    private func observeApplicationLifecycle() {
        NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.applicationIsActive = true
                self.endBackgroundTaskIfNeeded()
                self.refreshScheduling(requestImmediateSync: true)
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.applicationIsActive = false
                self.stopTimer()
                self.beginBackgroundTaskIfNeeded()
                self.refreshScheduling(requestImmediateSync: false)
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.didEnterBackgroundNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.applicationIsActive = false
                self.beginBackgroundTaskIfNeeded()
                self.refreshScheduling(requestImmediateSync: false)
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: UIApplication.willTerminateNotification)
            .receive(on: RunLoop.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.enqueueResponderInactiveSync()
                self.flushPendingSyncOperations()
            }
            .store(in: &cancellables)
    }

    private func refreshScheduling(requestImmediateSync: Bool) {
        if requestImmediateSync {
            pendingImmediateSync = true
        }

        configureLocationManager()

        guard liveLocationEnabled else {
            stopTimer()
            stopLocationWarmupIfNeeded()
            stopMonitoringSignificantLocationChangesIfNeeded()
            enqueueResponderInactiveSync()
            schedulePendingSyncDrain(immediate: true)
            return
        }

        pendingSyncOperations.removeAll { $0.kind == .responderInactive }
        persistPendingSyncOperations()

        let profile = currentRuntimeProfile

        if profile.shouldUseTimer {
            ensureTimer()
        } else {
            stopTimer()
        }

        if profile.shouldWarmLocation {
            startLocationWarmupIfNeeded()
        } else {
            stopLocationWarmupIfNeeded()
        }

        if profile.shouldUseSignificantChangeMonitoring {
            startMonitoringSignificantLocationChangesIfNeeded()
        } else {
            stopMonitoringSignificantLocationChangesIfNeeded()
        }

        if profile.shouldRequestOneShotLocation || pendingImmediateSync {
            requestLocationIfPossible()
        }

        schedulePendingSyncDrain(immediate: pendingImmediateSync)
        pendingImmediateSync = false
    }

    private var liveLocationEnabled: Bool {
        settingsStore.crisisLinkEnabled &&
            settingsStore.crisisLinkLiveLocationEnabled &&
            PrivacyPreferences.isShareLocationInSOSEnabled()
    }

    private var shouldRequestAlwaysAuthorization: Bool {
        Bundle.main.object(forInfoDictionaryKey: "NSLocationAlwaysAndWhenInUseUsageDescription") != nil ||
            Bundle.main.object(forInfoDictionaryKey: "NSLocationAlwaysUsageDescription") != nil
    }

    private var syncInterval: TimeInterval {
        TimeInterval(max(30, settingsStore.crisisLinkLiveLocationIntervalSeconds))
    }

    var shouldScheduleBackgroundRefresh: Bool {
        !pendingSyncOperations.isEmpty
    }

    var backgroundRefreshEarliestDelay: TimeInterval {
        max(5 * 60, min(15 * 60, syncInterval))
    }

    private var localTransportMessageId: String {
        let base = SecureLocalStore.shared.getOrCreateBroadcastId()
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        return "live-location-\(base)"
    }

    private func ensureTimer() {
        let interval = syncInterval
        guard syncTimer == nil || currentTimerInterval != interval else { return }

        stopTimer()
        currentTimerInterval = interval
        let timer = Timer(timeInterval: interval, repeats: true) { [weak self] _ in
            guard let coordinator = self else { return }
            Task { @MainActor in
                coordinator.requestLocationIfPossible()
            }
        }
        syncTimer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    private func stopTimer() {
        syncTimer?.invalidate()
        syncTimer = nil
        currentTimerInterval = nil
    }

    private func requestLocationIfPossible() {
        guard liveLocationEnabled else { return }
        let profile = currentRuntimeProfile

        switch locationManager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            if let fallbackLocation = locationManager.location, isFresh(location: fallbackLocation) {
                publishLocationUpdate(from: fallbackLocation, allowTransportBroadcast: false)
            }
            if profile.shouldWarmLocation && (!hasPublishedDashboardLocation || locationManager.location == nil) {
                startLocationWarmupIfNeeded()
            }
            guard profile.shouldRequestOneShotLocation || pendingImmediateSync else { return }
            pendingImmediateSync = false
            locationManager.requestLocation()
        case .notDetermined:
            if shouldRequestAlwaysAuthorization, !requestedAlwaysAuthorization {
                requestedAlwaysAuthorization = true
                locationManager.requestAlwaysAuthorization()
            } else {
                locationManager.requestWhenInUseAuthorization()
            }
        case .denied, .restricted:
            pendingImmediateSync = false
            stopLocationWarmupIfNeeded()
        @unknown default:
            break
        }
    }

    private func startLocationWarmupIfNeeded() {
        guard !isWarmingLocation else { return }
        guard currentRuntimeProfile.shouldWarmLocation else { return }
        isWarmingLocation = true
        locationManager.startUpdatingLocation()
    }

    private func stopLocationWarmupIfNeeded() {
        guard isWarmingLocation else { return }
        isWarmingLocation = false
        locationManager.stopUpdatingLocation()
    }

    private func startMonitoringSignificantLocationChangesIfNeeded() {
        guard !isMonitoringSignificantLocationChanges else { return }
        guard locationManager.authorizationStatus == .authorizedAlways else { return }
        isMonitoringSignificantLocationChanges = true
        locationManager.startMonitoringSignificantLocationChanges()
    }

    private func stopMonitoringSignificantLocationChangesIfNeeded() {
        guard isMonitoringSignificantLocationChanges else { return }
        isMonitoringSignificantLocationChanges = false
        locationManager.stopMonitoringSignificantLocationChanges()
    }

    private func publishLocationUpdate(from location: CLLocation, allowTransportBroadcast: Bool) {
        guard let dashboardPayload = dashboardPayload(from: location) else { return }
        hasPublishedDashboardLocation = true

        enqueueLiveLocationSync(dashboardPayload)

        guard allowTransportBroadcast else { return }

        let transportMessageId = localTransportMessageId
        let deliveredSessionIds = rescueManager.broadcastLiveLocation(
            latitude: dashboardPayload.latitude,
            longitude: dashboardPayload.longitude,
            horizontalAccuracyMeters: dashboardPayload.horizontalAccuracyMeters,
            capturedAt: dashboardPayload.capturedAt,
            transportMessageId: transportMessageId
        )

        guard !deliveredSessionIds.isEmpty else { return }
        for sessionId in deliveredSessionIds {
            _ = SOSChatStore.shared.upsertLocalLocationMessage(
                sessionId: sessionId,
                latitude: dashboardPayload.latitude,
                longitude: dashboardPayload.longitude,
                horizontalAccuracyMeters: dashboardPayload.horizontalAccuracyMeters,
                capturedAt: dashboardPayload.capturedAt,
                status: .sent,
                transportMessageId: transportMessageId
            )
        }
    }

    private func configureLocationManager() {
        let profile = currentRuntimeProfile
        locationManager.desiredAccuracy = profile.desiredAccuracy
        locationManager.distanceFilter = profile.distanceFilter
        locationManager.activityType = applicationIsActive ? .otherNavigation : .other
        locationManager.pausesLocationUpdatesAutomatically = profile.pausesLocationUpdatesAutomatically
        locationManager.allowsBackgroundLocationUpdates = liveLocationEnabled
        locationManager.showsBackgroundLocationIndicator = liveLocationEnabled && !applicationIsActive

        guard liveLocationEnabled else { return }

        switch locationManager.authorizationStatus {
        case .notDetermined where applicationIsActive:
            if shouldRequestAlwaysAuthorization && !requestedAlwaysAuthorization {
                requestedAlwaysAuthorization = true
                locationManager.requestAlwaysAuthorization()
            } else {
                locationManager.requestWhenInUseAuthorization()
            }
        case .authorizedWhenInUse where applicationIsActive && shouldRequestAlwaysAuthorization && !requestedAlwaysAuthorization:
            requestedAlwaysAuthorization = true
            locationManager.requestAlwaysAuthorization()
        default:
            break
        }
    }

    private var currentRuntimeProfile: RescueLiveLocationRuntimeProfile {
        RescueLiveLocationPolicy.runtimeProfile(
            liveLocationEnabled: liveLocationEnabled,
            applicationIsActive: applicationIsActive,
            authorizationStatus: locationManager.authorizationStatus,
            backgroundRefreshAvailable: backgroundRefreshAvailable
        )
    }

    private var backgroundRefreshAvailable: Bool {
        guard !PlatformRuntime.isRunningTests else { return false }
        return UIApplication.shared.backgroundRefreshStatus == .available
    }

    private func isFresh(location: CLLocation) -> Bool {
        let maxAge = max(90, min(300, syncInterval * 2))
        return abs(location.timestamp.timeIntervalSinceNow) <= maxAge
    }

    private func beginBackgroundTaskIfNeeded() {
        guard backgroundTaskIdentifier == .invalid else { return }
        backgroundTaskIdentifier = UIApplication.shared.beginBackgroundTask(withName: "crisis-link-live-location") { [weak self] in
            self?.endBackgroundTaskIfNeeded()
        }
    }

    private func endBackgroundTaskIfNeeded() {
        guard backgroundTaskIdentifier != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTaskIdentifier)
        backgroundTaskIdentifier = .invalid
    }

    private func enqueueLiveLocationSync(_ payload: CrisisLinkDashboardSyncService.LocationPayload) {
        pendingSyncOperations.removeAll { $0.kind == .liveLocation }
        pendingSyncOperations.append(
            PendingDashboardSyncOperation(
                kind: .liveLocation,
                payload: payload,
                attemptCount: 0,
                nextRetryAt: Date(),
                createdAt: Date(),
                lastError: nil
            )
        )
        persistPendingSyncOperations()
        schedulePendingSyncDrain(immediate: true)
    }

    private func enqueueResponderInactiveSync() {
        guard hasPublishedDashboardLocation else { return }
        pendingSyncOperations.removeAll { $0.kind == .liveLocation }
        pendingSyncOperations.removeAll { $0.kind == .responderInactive }
        pendingSyncOperations.append(
            PendingDashboardSyncOperation(
                kind: .responderInactive,
                payload: nil,
                attemptCount: 0,
                nextRetryAt: Date(),
                createdAt: Date(),
                lastError: nil
            )
        )
        persistPendingSyncOperations()
    }

    private func schedulePendingSyncDrain(immediate: Bool) {
        syncRetryWorkItem?.cancel()
        syncRetryWorkItem = nil

        guard !pendingSyncOperations.isEmpty else {
            endBackgroundTaskIfNeeded()
            return
        }
        if !applicationIsActive {
            beginBackgroundTaskIfNeeded()
        }

        let now = Date()
        let nextDate = pendingSyncOperations
            .map(\.nextRetryAt)
            .min() ?? now
        let delay = immediate ? 0 : max(0, nextDate.timeIntervalSince(now))

        let work = DispatchWorkItem { [weak self] in
            self?.flushPendingSyncOperations()
        }
        syncRetryWorkItem = work
        if delay <= 0 {
            DispatchQueue.main.async(execute: work)
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
        }
    }

    private func flushPendingSyncOperations() {
        guard pendingSyncDrainTask == nil else { return }
        guard !pendingSyncOperations.isEmpty else {
            endBackgroundTaskIfNeeded()
            return
        }
        if !applicationIsActive {
            beginBackgroundTaskIfNeeded()
        }

        pendingSyncDrainTask = Task { [weak self] in
            guard let self else { return }
            await self.performPendingSyncDrain()
        }
    }

    @MainActor
    private func performPendingSyncDrain() async {
        defer {
            pendingSyncDrainTask = nil
            if pendingSyncOperations.isEmpty {
                endBackgroundTaskIfNeeded()
            } else {
                schedulePendingSyncDrain(immediate: false)
            }
        }

        while let operation = nextPendingSyncOperation() {
            if operation.nextRetryAt > Date() {
                break
            }

            let success: Bool
            switch operation.kind {
            case .liveLocation:
                guard let payload = operation.payload else {
                    removePendingSyncOperation(operation.id)
                    continue
                }
                success = await dashboardSyncService.syncLiveLocation(payload: payload)
            case .responderInactive:
                success = await dashboardSyncService.markResponderInactive()
            }

            if success {
                removePendingSyncOperation(operation.id)
            } else {
                reschedulePendingSyncOperation(operation.id, failure: "sync_failed")
                break
            }
        }
    }

    private func nextPendingSyncOperation() -> PendingDashboardSyncOperation? {
        pendingSyncOperations
            .filter { $0.nextRetryAt <= Date() }
            .sorted { lhs, rhs in
                if lhs.nextRetryAt == rhs.nextRetryAt {
                    return lhs.createdAt < rhs.createdAt
                }
                return lhs.nextRetryAt < rhs.nextRetryAt
            }
            .first
    }

    private func removePendingSyncOperation(_ id: UUID) {
        pendingSyncOperations.removeAll { $0.id == id }
        persistPendingSyncOperations()
    }

    private func reschedulePendingSyncOperation(_ id: UUID, failure: String) {
        guard let index = pendingSyncOperations.firstIndex(where: { $0.id == id }) else { return }
        let attempts = pendingSyncOperations[index].attemptCount + 1
        pendingSyncOperations[index].attemptCount = attempts
        pendingSyncOperations[index].lastError = failure
        pendingSyncOperations[index].nextRetryAt = Date().addingTimeInterval(LiveLocationSyncBackoff.delay(forAttempt: attempts))
        persistPendingSyncOperations()
    }

    private func persistPendingSyncOperations() {
        pendingSyncStore.save(pendingSyncOperations)
        CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
    }

    func handleBackgroundRefresh() async -> Bool {
        guard !Task.isCancelled else { return false }
        applicationIsActive = false
        configureLocationManager()
        if let cachedLocation = locationManager.location, isFresh(location: cachedLocation) {
            publishLocationUpdate(from: cachedLocation, allowTransportBroadcast: false)
        }
        if !pendingSyncOperations.isEmpty {
            await performPendingSyncDrain()
        }
        return !Task.isCancelled
    }

    private func dashboardPayload(from location: CLLocation) -> CrisisLinkDashboardSyncService.LocationPayload? {
        let latitude = location.coordinate.latitude
        let longitude = location.coordinate.longitude
        guard latitude.isFinite,
              longitude.isFinite,
              (-90.0...90.0).contains(latitude),
              (-180.0...180.0).contains(longitude) else {
            return nil
        }

        let accuracy = location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil
        let capturedAt = location.timestamp.timeIntervalSince1970 > 0 ? location.timestamp : Date()
        return CrisisLinkDashboardSyncService.LocationPayload(
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: accuracy,
            capturedAt: capturedAt
        )
    }
}
