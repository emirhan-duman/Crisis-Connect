//
//  SosCloudReporter.swift
//  Crisis Connect
//
//  Victim-side internet uplink for the SOS broadcast — the iOS port of Android's SosCloudReporter.
//
//  The BLE broadcast is the primary, offline-first channel and must never wait on this class. When
//  usable connectivity exists (now or later — store-and-forward), the active SOS is additionally
//  self-reported to the agency dashboard through the `reportSosSignal` callable, refreshed on a
//  battery-aware heartbeat, and finally marked resolved when the victim stops the broadcast. A
//  resolve that cannot be delivered (offline stop) is persisted and retried on the next app
//  foreground. The signalId is the same rescue device id the BLE advert carries, so the dashboard
//  merges the self-report with any rescuer-side sightings of the same victim.
//

import BackgroundTasks
import Combine
import Foundation
import FirebaseAuth
import FirebaseFunctions
import CoreLocation
import Network
import UIKit

final class SosCloudReporter: ObservableObject, @unchecked Sendable {
    static let shared = SosCloudReporter()

    enum State: Equatable {
        case idle
        /// SOS is active but there is no usable network yet; the report is queued locally.
        case waitingForNetwork
        case sending
        case reported(lastReportedAt: Date)
        /// Last attempt over a live network failed; will retry automatically.
        case failed
    }

    @Published private(set) var state: State = .idle

    private static let callableName = "reportSosSignal"
    private static let functionsRegion = "us-central1"
    private static let statusActive = "active"
    private static let statusResolved = "resolved"

    private static let callTimeout: TimeInterval = 20
    private static let heartbeatInterval: TimeInterval = 45
    private static let heartbeatIntervalLowBattery: TimeInterval = 90
    private static let lowBatteryThresholdPercent = 20
    private static let offlinePollInterval: TimeInterval = 10
    private static let failureRetryDelay: TimeInterval = 20
    private static let signInTimeout: TimeInterval = 10

    private enum Keys {
        static let pendingResolveSignalId = "sos.cloudReport.pendingResolveSignalId"
        static let hasReportedActive = "sos.cloudReport.hasReportedActive"
    }

    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "sos.cloud.reporter.reachability")
    private let stateLock = NSLock()
    private var isOnline = false
    private var heartbeatTask: Task<Void, Never>?

    private init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.stateLock.lock()
            self.isOnline = path.status == .satisfied
            self.stateLock.unlock()
        }
        monitor.start(queue: monitorQueue)
    }

    private var hasUsableInternet: Bool {
        stateLock.lock(); defer { stateLock.unlock() }
        return isOnline
    }

    /// Starts the uplink for a freshly started SOS broadcast. Runs entirely on its own task so a
    /// slow network call can never delay or block BLE startup or teardown.
    func onSosStarted() {
        guard !PlatformRuntime.isRunningTests else { return }
        heartbeatTask?.cancel()
        // A new broadcast supersedes any queued resolve for the same device signal.
        UserDefaults.standard.removeObject(forKey: Keys.pendingResolveSignalId)
        UserDefaults.standard.set(false, forKey: Keys.hasReportedActive)
        // @Published `state` now drives the active-SOS UI, so every mutation hops to the main actor.
        let initialState: State = hasUsableInternet ? .sending : .waitingForNetwork
        Task { @MainActor in self.state = initialState }

        heartbeatTask = Task { [weak self] in
            await MainActor.run { UIDevice.current.isBatteryMonitoringEnabled = true }
            while let self, !Task.isCancelled {
                let sessionActive = await MainActor.run { SOSBroadcastManager.shared.isSessionActive }
                guard sessionActive else { break }

                guard self.hasUsableInternet else {
                    await MainActor.run { self.state = .waitingForNetwork }
                    try? await Task.sleep(nanoseconds: UInt64(Self.offlinePollInterval * 1_000_000_000))
                    continue
                }
                await MainActor.run { self.state = .sending }
                let (location, battery) = await MainActor.run {
                    (
                        SOSSignalLocationCoordinator.shared.currentVictimLocationPayload(),
                        Self.batteryPercent()
                    )
                }
                let reported = await self.sendReport(
                    status: Self.statusActive,
                    location: location,
                    batteryPercent: battery
                )
                if reported {
                    UserDefaults.standard.set(true, forKey: Keys.hasReportedActive)
                    await MainActor.run { self.state = .reported(lastReportedAt: Date()) }
                    let lowBattery = battery.map { $0 < Self.lowBatteryThresholdPercent } ?? false
                    let interval = lowBattery ? Self.heartbeatIntervalLowBattery : Self.heartbeatInterval
                    try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                } else {
                    await MainActor.run { self.state = .failed }
                    try? await Task.sleep(nanoseconds: UInt64(Self.failureRetryDelay * 1_000_000_000))
                }
            }
        }
    }

    /// Marks the broadcast resolved on the dashboard. Best-effort immediately; when offline (or on
    /// failure) the resolve is persisted and retried on the next app foreground. Only signals that
    /// actually reported as active get resolved, so a fully-offline SOS session never writes a
    /// phantom "resolved" marker.
    func onSosStopped() {
        guard !PlatformRuntime.isRunningTests else { return }
        heartbeatTask?.cancel()
        heartbeatTask = nil
        Task { @MainActor in self.state = .idle }
        guard UserDefaults.standard.bool(forKey: Keys.hasReportedActive) else { return }
        let signalId = SecureLocalStore.shared.getOrCreateRescueDeviceId()
        UserDefaults.standard.set(signalId, forKey: Keys.pendingResolveSignalId)
        UserDefaults.standard.set(false, forKey: Keys.hasReportedActive)
        Task { [weak self] in
            guard let self else { return }
            if self.hasUsableInternet,
               await self.sendReport(status: Self.statusResolved, location: nil, batteryPercent: nil) {
                UserDefaults.standard.removeObject(forKey: Keys.pendingResolveSignalId)
            }
            // Otherwise the pending resolve stays persisted; deliverPendingResolve retries it on the
            // next foreground, and a network-gated background task narrows the offline-stop gap.
            SosFollowUpBackgroundManager.shared.scheduleIfNeeded()
        }
    }

    /// True when an offline stop left a "resolved" report still awaiting delivery — the trigger the
    /// background follow-up task checks before scheduling itself.
    static var hasPendingResolve: Bool {
        UserDefaults.standard.string(forKey: Keys.pendingResolveSignalId) != nil
    }

    /// Attempts delivery of a persisted resolve (called on app foreground — the iOS stand-in for
    /// Android's SosReportWorker).
    func deliverPendingResolve() async {
        guard let pending = UserDefaults.standard.string(forKey: Keys.pendingResolveSignalId) else {
            return
        }
        // The victim restarted SOS meanwhile; the active heartbeat owns the signal again.
        let sessionActive = await MainActor.run { SOSBroadcastManager.shared.isSessionActive }
        if sessionActive { return }
        guard hasUsableInternet else { return }
        if await sendReport(
            status: Self.statusResolved,
            location: nil,
            batteryPercent: nil,
            signalIdOverride: pending
        ) {
            UserDefaults.standard.removeObject(forKey: Keys.pendingResolveSignalId)
        }
    }

    // MARK: - Report delivery

    /// Returns true when the report was accepted by the backend. Payload fields mirror Android's
    /// SosCloudReporter exactly (the dashboard consumes one schema for both platforms).
    private func sendReport(
        status: String,
        location: SOSSignalLocationPayload.GPSPayload?,
        batteryPercent: Int?,
        signalIdOverride: String? = nil
    ) async -> Bool {
        if status == Self.statusActive && location == nil {
            NSLog("SosCloudReporter: skipping report; no usable location fix yet")
            return false
        }
        guard await ensureSignedIn() else {
            NSLog("SosCloudReporter: skipping report; no Firebase identity available")
            return false
        }
        let signalId = signalIdOverride ?? SecureLocalStore.shared.getOrCreateRescueDeviceId()
        var payload: [String: Any] = [
            "signalId": signalId,
            "status": status
        ]
        if let location {
            payload["lat"] = location.latitude
            payload["lon"] = location.longitude
            payload["capturedAtMillis"] = location.capturedAtMillis
            payload["provider"] = location.provider
            if let accuracy = location.horizontalAccuracyMeters {
                payload["accuracyMeters"] = accuracy
            }
        }
        if let batteryPercent {
            payload["batteryPercent"] = batteryPercent
        }
        if let countryIso = await resolveCountryIso(location: location) {
            payload["countryIso"] = countryIso
        }

        let call = Task {
            try await Functions.functions(region: Self.functionsRegion)
                .httpsCallable(Self.callableName)
                .callAsync(payload)
        }
        let timeout = Task {
            try? await Task.sleep(nanoseconds: UInt64(Self.callTimeout * 1_000_000_000))
            call.cancel()
        }
        defer { timeout.cancel() }
        do {
            _ = try await call.value
            AppAnalytics.sosCloudReported(status: status)
            return true
        } catch {
            NSLog("SosCloudReporter: report failed status=%@: %@", status, String(describing: error))
            return false
        }
    }

    /// Restores the Firebase session or signs in anonymously, time-bounded.
    private func ensureSignedIn() async -> Bool {
        if Auth.auth().currentUser != nil { return true }
        let signIn = Task { try? await Auth.auth().signInAnonymously() }
        let timeout = Task {
            try? await Task.sleep(nanoseconds: UInt64(Self.signInTimeout * 1_000_000_000))
            signIn.cancel()
        }
        defer { timeout.cancel() }
        return await signIn.value != nil
    }

    /// Best-effort ISO country for agency routing: reverse-geocode the fix, else the device
    /// locale's region. Any failure just omits the hint (the dashboard routes to International).
    private func resolveCountryIso(location: SOSSignalLocationPayload.GPSPayload?) async -> String? {
        if let location {
            let clLocation = CLLocation(latitude: location.latitude, longitude: location.longitude)
            let geocoded = try? await CLGeocoder().reverseGeocodeLocation(clLocation)
            if let iso = geocoded?.first?.isoCountryCode?.trimmingCharacters(in: .whitespacesAndNewlines),
               !iso.isEmpty {
                return iso.uppercased()
            }
        }
        return Locale.current.region?.identifier.uppercased()
    }

    /// Roaming-safe ISO country for the emergency-call card: reverse-geocode the victim's live GPS
    /// fix using the same logic the dashboard report uses. Returns nil when there is no usable fix,
    /// so the caller keeps its device-locale default. Never throws.
    func currentGeocodedCountryIso() async -> String? {
        let location = await MainActor.run {
            SOSSignalLocationCoordinator.shared.currentVictimLocationPayload()
        }
        guard let location else { return nil }
        return await resolveCountryIso(location: location)
    }

    @MainActor
    private static func batteryPercent() -> Int? {
        let level = UIDevice.current.batteryLevel
        guard level >= 0 else { return nil }
        return Int((level * 100).rounded())
    }
}

/// Store-and-forward tail of an SOS session — the iOS stand-in for Android's SosReportWorker
/// (WorkManager, NetworkType.CONNECTED). When the victim stops the broadcast offline, the dashboard
/// "resolved" report and the contacts' all-clear are persisted; this network-gated background task
/// delivers whatever is left even if the app is never reopened. iOS background execution is
/// best-effort, so the app-foreground hooks remain the primary retry path — this only narrows the
/// gap. A no-op whenever nothing is pending. Registration mirrors CertificateRenewalBackgroundManager.
final class SosFollowUpBackgroundManager {
    static let shared = SosFollowUpBackgroundManager()
    static let taskIdentifier = "com.auralis.crisisconnect.sos-followup"

    private static let retryDelay: TimeInterval = 60

    private var hasRegistered = false

    private init() {}

    func register() {
        guard !PlatformRuntime.isRunningTests else { return }
        guard !hasRegistered else { return }
        hasRegistered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Self.shared.handle(processingTask)
        }
        if !hasRegistered {
            NSLog("Failed to register SOS follow-up background task.")
        }
    }

    /// Arms the network-gated task when an offline stop left a resolve or all-clear undelivered;
    /// cancels the request when nothing is pending. Safe to call repeatedly (cancel-then-submit).
    func scheduleIfNeeded() {
        guard !PlatformRuntime.isRunningTests else { return }
        guard SosCloudReporter.hasPendingResolve || SosContactNotifier.hasPendingAllClear else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
            return
        }

        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.retryDelay)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("Failed to schedule SOS follow-up background task: %@", String(describing: error))
        }
    }

    private func handle(_ task: BGProcessingTask) {
        let worker = Task(priority: .background) {
            await SosCloudReporter.shared.deliverPendingResolve()
            _ = await SosContactNotifier.shared.deliverPendingAllClear()
        }
        task.expirationHandler = {
            worker.cancel()
        }

        Task {
            await worker.value
            task.setTaskCompleted(success: true)
            // Anything still undelivered (e.g. the network dropped mid-run) reschedules itself.
            Self.shared.scheduleIfNeeded()
        }
    }
}
