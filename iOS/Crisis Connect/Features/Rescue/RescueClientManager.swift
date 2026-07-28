//
//  RescueClientManager.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import Foundation
import Combine
import Network
import WiFiAware
import CoreBluetooth

struct RescueBroadcast: Identifiable, Equatable {
    let id: UUID
    var broadcastId: String?
    var peripheralId: UUID
    var peripheralName: String
    var status: RescueConnectionStatus
    var rssi: Int?
    var victimBatteryPercent: Int? = nil
    var victimMedical: RescueVictimMedical? = nil
    var lastSeen: Date
    var lastUpdated: Date
}

final class RescueClientManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    static let shared = RescueClientManager()
    private static let bleCentralRestoreIdentifier = "com.auralis.crisisconnect.rescue-client.central"

    @Published private(set) var broadcasts: [RescueBroadcast] = []
    @Published private(set) var isScanning: Bool = false
    @Published private(set) var lastUpdated: Date?
    @Published private(set) var errorMessageKey: String?

    private let queue = DispatchQueue(label: "sos.rescue.wifiaware")
    private let cleanupInterval: TimeInterval = 15
    private let staleInterval: TimeInterval = 20
    private let rescanDelay: TimeInterval = 0.35
    private let broadcastPublishInterval: TimeInterval = 0.25
    private let queueKey = DispatchSpecificKey<Void>()
    private let allowBLEFallback = true
    private let dashboardSyncService = CrisisLinkDashboardSyncService.shared

    private let wifiAwareServiceName = "_ccrescue._tcp"
    private let rescueServiceUUID = CBUUID(string: "0000CC00-0000-1000-8000-00805F9B34FB")
    private let advertisedRescueServiceUUID = CBUUID(string: "CC00")
    private let settingsStore: RescueSettingsStore

    private enum WiFiAwarePeerScope {
        case pairedOnly
        case userSpecified
    }

    @available(iOS 26.0, *)
    private final class WiFiAwareState {
        var sessionByDeviceId: [WAPairedDevice.ID: UUID] = [:]
        var endpointBySessionId: [UUID: WAEndpoint] = [:]
        var connectionBySessionId: [UUID: RescueWiFiAwareConnection] = [:]
        var sessionIds: Set<UUID> = []
        var browser: NetworkBrowser<WASubscriberBrowser>?
        var browserTask: Task<Void, Never>?
        var listener: NetworkListener<TCP>?
        var listenerTask: Task<Void, Never>?
    }

    private var cleanupWork: DispatchWorkItem?
    private var broadcastPublishWork: DispatchWorkItem?
    private var scanningActive = false
    private var scanRequested = false
    private var wifiAwareErrorKey: String?
    private var wifiAwarePeerScope: WiFiAwarePeerScope = .pairedOnly
    private var lastBroadcastPublishTime: TimeInterval = 0

    private var broadcastsBySessionId: [UUID: RescueBroadcast] = [:]
    private var broadcastIdToSessionId: [String: UUID] = [:]
    private var manualScanRequested = false
    private var persistentRuntimeRequested = false

    private var wifiAwareStateStorage: Any?

    @available(iOS 26.0, *)
    private var wifiAwareState: WiFiAwareState {
        if let state = wifiAwareStateStorage as? WiFiAwareState {
            return state
        }
        let state = WiFiAwareState()
        wifiAwareStateStorage = state
        return state
    }

    @available(iOS 26.0, *)
    private var wifiAwareSessionByDeviceId: [WAPairedDevice.ID: UUID] {
        get { wifiAwareState.sessionByDeviceId }
        set { wifiAwareState.sessionByDeviceId = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareEndpointBySessionId: [UUID: WAEndpoint] {
        get { wifiAwareState.endpointBySessionId }
        set { wifiAwareState.endpointBySessionId = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareConnectionBySessionId: [UUID: RescueWiFiAwareConnection] {
        get { wifiAwareState.connectionBySessionId }
        set { wifiAwareState.connectionBySessionId = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareSessionIds: Set<UUID> {
        get { wifiAwareState.sessionIds }
        set { wifiAwareState.sessionIds = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareBrowser: NetworkBrowser<WASubscriberBrowser>? {
        get { wifiAwareState.browser }
        set { wifiAwareState.browser = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareBrowserTask: Task<Void, Never>? {
        get { wifiAwareState.browserTask }
        set { wifiAwareState.browserTask = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareListener: NetworkListener<TCP>? {
        get { wifiAwareState.listener }
        set { wifiAwareState.listener = newValue }
    }

    @available(iOS 26.0, *)
    private var wifiAwareListenerTask: Task<Void, Never>? {
        get { wifiAwareState.listenerTask }
        set { wifiAwareState.listenerTask = newValue }
    }

    private var bleCentral: CBCentralManager?
    private var bleScanActive = false
    private var bleSessionByPeripheralId: [UUID: UUID] = [:]
    private var blePeripheralIdBySessionId: [UUID: UUID] = [:]
    private var blePeripheralBySessionId: [UUID: CBPeripheral] = [:]
    private var bleConnectionBySessionId: [UUID: RescueConnection] = [:]
    private var autoConnectToScannedBroadcasts: Bool
    private var cancellables: Set<AnyCancellable> = []

    init(settingsStore: RescueSettingsStore = .shared) {
        self.settingsStore = settingsStore
        self.autoConnectToScannedBroadcasts = settingsStore.autoConnectToScannedBroadcasts
        self.persistentRuntimeRequested = settingsStore.autoStartScanning ||
            settingsStore.meshAlwaysOn ||
            settingsStore.crisisLinkEnabled
        super.init()
        queue.setSpecific(key: queueKey, value: ())
        applyRuntimeSettingsSnapshot()
        observeSettings()
    }

    func bootstrapRuntime() {
        queue.async { [weak self] in
            guard let self else { return }
            self.applyRuntimeSettingsSnapshot()
            self.reconcileRuntimeState(reset: false)
        }
    }

    func startScanning() {
        queue.async { [weak self] in
            guard let self else { return }
            self.manualScanRequested = true
            self.reconcileRuntimeState(reset: true)
        }
    }

    func refresh() {
        queue.asyncAfter(deadline: .now() + rescanDelay) { [weak self] in
            guard let self else { return }
            let manualRequestWasEnabled = self.manualScanRequested
            self.stopScanningTransport()
            self.manualScanRequested = manualRequestWasEnabled
            if self.manualScanRequested || self.persistentRuntimeRequested {
                self.reconcileRuntimeState(reset: true)
            }
        }
    }

    func stopScanning() {
        queue.async { [weak self] in
            guard let self else { return }
            self.manualScanRequested = false
            self.reconcileRuntimeState(reset: false)
        }
    }

    func connect(to sessionId: UUID) {
        queue.async { [weak self] in
            guard let self else { return }
            if #available(iOS 26.0, *) {
                if let endpoint = self.wifiAwareEndpointBySessionId[sessionId] {
                    self.connectWiFiAwareIfNeeded(sessionId: sessionId, endpoint: endpoint, force: true)
                    return
                }
                if let connection = self.wifiAwareConnectionBySessionId[sessionId] {
                    connection.connect()
                    return
                }
            }
            if self.allowBLEFallback, let central = self.bleCentral {
                if let connection = self.bleConnectionBySessionId[sessionId] {
                    connection.connect(using: central)
                } else if let peripheral = self.blePeripheralBySessionId[sessionId] {
                    self.connectBLEIfNeeded(
                        sessionId: sessionId,
                        peripheral: peripheral,
                        broadcastId: self.broadcastsBySessionId[sessionId]?.broadcastId,
                        force: true
                    )
                }
            }
        }
    }

    func sendMessage(to sessionId: UUID, text: String, transportMessageId: String) -> Bool {
        if #available(iOS 26.0, *) {
            let connection: RescueWiFiAwareConnection? = syncOnQueue {
                wifiAwareConnectionBySessionId[sessionId]
            }
            if let connection {
                let didSend = connection.sendMessage(text, transportMessageId: transportMessageId)
                if didSend {
                    return true
                }
            }
        }
        if allowBLEFallback, let connection = syncOnQueue({ bleConnectionBySessionId[sessionId] }) {
            return connection.sendMessage(text, transportMessageId: transportMessageId)
        }
        return false
    }

    func sendReadReceipt(to sessionId: UUID, transportMessageIds: [String]) -> Bool {
        if #available(iOS 26.0, *) {
            let connection: RescueWiFiAwareConnection? = syncOnQueue {
                wifiAwareConnectionBySessionId[sessionId]
            }
            if let connection {
                let didSend = connection.sendReadReceipt(transportMessageIds: transportMessageIds)
                if didSend {
                    return true
                }
            }
        }
        if allowBLEFallback, let connection = syncOnQueue({ bleConnectionBySessionId[sessionId] }) {
            return connection.sendReadReceipt(transportMessageIds: transportMessageIds)
        }
        return false
    }

    func sendVoiceMessage(
        to sessionId: UUID,
        audioFileName: String,
        mimeType: String,
        durationMillis: Int,
        messageId: String
    ) -> Bool {
        if #available(iOS 26.0, *) {
            let connection: RescueWiFiAwareConnection? = syncOnQueue {
                wifiAwareConnectionBySessionId[sessionId]
            }
            if let connection {
                let didSend = connection.sendVoiceMessage(
                    audioFileName: audioFileName,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: messageId
                )
                if didSend {
                    return true
                }
            }
        }
        if allowBLEFallback, let connection = syncOnQueue({ bleConnectionBySessionId[sessionId] }) {
            return connection.sendVoiceMessage(
                audioFileName: audioFileName,
                mimeType: mimeType,
                durationMillis: durationMillis,
                messageId: messageId
            )
        }
        return false
    }

    func sendImageMessage(
        to sessionId: UUID,
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String
    ) -> Bool {
        if #available(iOS 26.0, *) {
            let connection: RescueWiFiAwareConnection? = syncOnQueue {
                wifiAwareConnectionBySessionId[sessionId]
            }
            if let connection {
                let didSend = connection.sendImageMessage(
                    imageFileName: imageFileName,
                    mimeType: mimeType,
                    width: width,
                    height: height,
                    messageId: messageId
                )
                if didSend {
                    return true
                }
            }
        }
        if allowBLEFallback, let connection = syncOnQueue({ bleConnectionBySessionId[sessionId] }) {
            return connection.sendImageMessage(
                imageFileName: imageFileName,
                mimeType: mimeType,
                width: width,
                height: height,
                messageId: messageId
            )
        }
        return false
    }

    func sendFileMessage(
        to sessionId: UUID,
        data: Data,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        messageId: String
    ) -> Bool {
        if #available(iOS 26.0, *) {
            let connection: RescueWiFiAwareConnection? = syncOnQueue {
                wifiAwareConnectionBySessionId[sessionId]
            }
            if let connection {
                let didSend = connection.sendFileMessage(
                    data: data,
                    displayName: displayName,
                    mimeType: mimeType,
                    originalSizeBytes: originalSizeBytes,
                    messageId: messageId
                )
                if didSend {
                    return true
                }
            }
        }
        if allowBLEFallback, let connection = syncOnQueue({ bleConnectionBySessionId[sessionId] }) {
            return connection.sendFileMessage(
                data: data,
                displayName: displayName,
                mimeType: mimeType,
                originalSizeBytes: originalSizeBytes,
                messageId: messageId
            )
        }
        return false
    }

    func hasReadyLocationRecipients() -> Bool {
        syncOnQueue {
            broadcastsBySessionId.values.contains { $0.status == .ready }
        }
    }

    func broadcastLiveLocation(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        transportMessageId: String
    ) -> [UUID] {
        syncOnQueue {
            let sessionIds = broadcastsBySessionId.keys.sorted { $0.uuidString < $1.uuidString }
            var deliveredSessionIds: [UUID] = []

            for sessionId in sessionIds {
                guard broadcastsBySessionId[sessionId]?.status == .ready else { continue }

                if #available(iOS 26.0, *),
                   let connection = wifiAwareConnectionBySessionId[sessionId],
                   connection.sendLiveLocation(
                        latitude: latitude,
                        longitude: longitude,
                        horizontalAccuracyMeters: horizontalAccuracyMeters,
                        capturedAt: capturedAt,
                        messageId: transportMessageId
                   ) {
                    deliveredSessionIds.append(sessionId)
                    continue
                }

                if allowBLEFallback,
                   let connection = bleConnectionBySessionId[sessionId],
                   connection.sendLiveLocation(
                        latitude: latitude,
                        longitude: longitude,
                        horizontalAccuracyMeters: horizontalAccuracyMeters,
                        capturedAt: capturedAt,
                        messageId: transportMessageId
                   ) {
                    deliveredSessionIds.append(sessionId)
                }
            }

            return deliveredSessionIds
        }
    }

    // MARK: - Private

    private func startScanningTransport(reset: Bool) {
        scanRequested = true
        if reset {
            resetDiscoveryState()
            if #available(iOS 26.0, *) {
                wifiAwarePeerScope = .pairedOnly
            }
        }

        let browserStarted: Bool
        let listenerStarted: Bool
        if #available(iOS 26.0, *) {
            browserStarted = startWiFiAwareBrowsingIfNeeded()
            listenerStarted = startWiFiAwarePublishingIfNeeded()
        } else {
            browserStarted = false
            listenerStarted = false
        }
        if allowBLEFallback {
            startBLEScanningIfNeeded()
        }
        if !browserStarted && !listenerStarted && wifiAwareErrorKey == nil && !allowBLEFallback {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }

        publishErrorState()
        updateScanningState()
    }

    private func observeSettings() {
        Publishers.Merge3(
            settingsStore.$autoStartScanning.map { _ in () },
            settingsStore.$meshAlwaysOn.map { _ in () },
            settingsStore.$crisisLinkEnabled.map { _ in () }
        )
        .merge(with: settingsStore.$autoConnectToScannedBroadcasts.map { _ in () })
        .sink { [weak self] _ in
            self?.queue.async { [weak self] in
                guard let self else { return }
                self.applyRuntimeSettingsSnapshot()
                self.reconcileRuntimeState(reset: false)
            }
        }
        .store(in: &cancellables)
    }

    private func applyRuntimeSettingsSnapshot() {
        let autoConnectEnabled = settingsStore.autoConnectToScannedBroadcasts
        if autoConnectToScannedBroadcasts != autoConnectEnabled {
            autoConnectToScannedBroadcasts = autoConnectEnabled
            applyAutoConnectSetting(autoConnectEnabled)
        }
        persistentRuntimeRequested =
            settingsStore.autoStartScanning ||
            settingsStore.meshAlwaysOn ||
            settingsStore.crisisLinkEnabled
    }

    private func reconcileRuntimeState(reset: Bool) {
        let shouldScan = manualScanRequested || persistentRuntimeRequested
        if shouldScan {
            if !scanRequested {
                startScanningTransport(reset: reset)
                return
            }
            if reset {
                startScanningTransport(reset: true)
                return
            }
            if !scanningActive {
                startScanningTransport(reset: false)
                return
            }
            updateScanningState()
            return
        }

        stopScanningTransport()
    }

    private func stopScanningTransport() {
        scanRequested = false
        if #available(iOS 26.0, *) {
            stopWiFiAwareTransport()
        } else {
            wifiAwareErrorKey = nil
            publishErrorState()
        }
        if allowBLEFallback {
            stopBLETransport()
        }
        scanningActive = false
        cleanupWork?.cancel()
        cleanupWork = nil
        DispatchQueue.main.async {
            self.isScanning = false
        }
    }

    private func applyAutoConnectSetting(_ enabled: Bool) {
        autoConnectToScannedBroadcasts = enabled
        guard enabled else { return }

        if #available(iOS 26.0, *) {
            wifiAwareEndpointBySessionId.forEach { sessionId, endpoint in
                connectWiFiAwareIfNeeded(sessionId: sessionId, endpoint: endpoint)
            }
        }
        blePeripheralBySessionId.forEach { sessionId, peripheral in
            connectBLEIfNeeded(
                sessionId: sessionId,
                peripheral: peripheral,
                broadcastId: broadcastsBySessionId[sessionId]?.broadcastId
            )
        }
    }

    private func publishBroadcasts(force: Bool = false) {
        if DispatchQueue.getSpecific(key: queueKey) == nil {
            queue.async { [weak self] in
                self?.publishBroadcasts(force: force)
            }
            return
        }

        let flush = { [weak self] in
            guard let self else { return }
            self.broadcastPublishWork = nil
            self.lastBroadcastPublishTime = ProcessInfo.processInfo.systemUptime
            let sorted = self.broadcastsBySessionId.values.sorted { $0.lastSeen > $1.lastSeen }
            ResponderSignalStats.shared.update(
                activeCount: sorted.count,
                signalIds: sorted.compactMap { self.normalizedSignalId(from: $0.broadcastId) }
            )
            DispatchQueue.main.async {
                self.broadcasts = sorted
                self.lastUpdated = sorted.isEmpty ? nil : Date()
            }
        }

        broadcastPublishWork?.cancel()

        let elapsed = ProcessInfo.processInfo.systemUptime - lastBroadcastPublishTime
        let delay = force ? 0 : max(0, broadcastPublishInterval - elapsed)
        guard delay > 0 else {
            flush()
            return
        }

        let work = DispatchWorkItem(block: flush)
        broadcastPublishWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func applyStatus(_ status: RescueConnectionStatus, to sessionId: UUID) {
        guard var entry = broadcastsBySessionId[sessionId] else { return }
        entry.status = status
        entry.lastUpdated = Date()
        broadcastsBySessionId[sessionId] = entry
        publishBroadcasts(force: true)
    }

    private func applyPeerName(_ name: String, sessionId: UUID) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        if var entry = broadcastsBySessionId[sessionId] {
            entry.peripheralName = trimmed
            entry.lastUpdated = Date()
            broadcastsBySessionId[sessionId] = entry
            publishBroadcasts()
        }
    }

    private func applyPeerBattery(_ batteryPercent: Int, sessionId: UUID) {
        guard (0...100).contains(batteryPercent) else { return }
        if var entry = broadcastsBySessionId[sessionId] {
            entry.victimBatteryPercent = batteryPercent
            entry.lastUpdated = Date()
            broadcastsBySessionId[sessionId] = entry
            publishBroadcasts()
        }
    }

    private func applyPeerMedical(_ medical: RescueVictimMedical, sessionId: UUID) {
        guard medical.hasContent else { return }
        if var entry = broadcastsBySessionId[sessionId] {
            entry.victimMedical = medical
            entry.lastUpdated = Date()
            broadcastsBySessionId[sessionId] = entry
            publishBroadcasts()
        }
    }

    private func applyBroadcastId(_ broadcastId: String, sessionId: UUID) {
        guard let normalized = normalizeBroadcastId(broadcastId) else { return }
        if let existingSessionId = broadcastIdToSessionId[normalized], existingSessionId != sessionId {
            let canonicalSessionId = preferredSessionId(between: existingSessionId, and: sessionId)
            let duplicateSessionId = canonicalSessionId == existingSessionId ? sessionId : existingSessionId
            mergeSession(duplicateSessionId, into: canonicalSessionId, broadcastId: normalized)
            return
        }

        broadcastIdToSessionId[normalized] = sessionId
        if var entry = broadcastsBySessionId[sessionId] {
            entry.broadcastId = normalized
            entry.lastUpdated = Date()
            broadcastsBySessionId[sessionId] = entry
        }
        publishBroadcasts()
    }

    private func applySignalLocation(_ signalLocation: SOSSignalLocationPayload?, sessionId: UUID) {
        guard let signalId = normalizedSignalId(from: broadcastsBySessionId[sessionId]?.broadcastId) else {
            return
        }
        let currentRSSI = broadcastsBySessionId[sessionId]?.rssi
        // Only forward a real identity name — the scan-time placeholder is not the victim's
        // display name and would pollute the dashboard marker.
        let rawName = broadcastsBySessionId[sessionId]?.peripheralName
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let victimName = (rawName.isEmpty ||
            rawName == NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")) ? nil : rawName
        let victimBatteryPercent = broadcastsBySessionId[sessionId]?.victimBatteryPercent
        Task { @MainActor [dashboardSyncService] in
            let resolvedLocation: SOSSignalLocationPayload?
            if let signalLocation, signalLocation.gps != nil {
                resolvedLocation = signalLocation
            } else {
                resolvedLocation = await SOSSignalLocationCoordinator.shared.requestRelativeEstimate(rssi: currentRSSI)
            }
            // No fix is still a sighting: Android syncs it (firstSeen/lastSeen tell the dashboard a
            // live victim exists); this used to bail here and the panel heard nothing at all.
            let usableLocation = resolvedLocation?.gps != nil ? resolvedLocation : nil
            let reporterLocation = await SOSSignalLocationCoordinator.shared.requestReporterLocationPayload()
            let reporterPayload = reporterLocation.map(Self.dashboardPayload(from:))
            let synced = await dashboardSyncService.syncSignalLocation(
                signalId: signalId,
                signalLocation: usableLocation,
                reporterLocation: reporterPayload,
                victimName: victimName,
                victimBatteryPercent: victimBatteryPercent
            )
            if !synced {
                // Offline rescuer: queue the observation so it ships on reconnect instead of dying
                // when iOS suspends the app — the realistic field case.
                RescueLiveLocationCoordinator.shared.enqueueSignalObservation(
                    signalId: signalId,
                    signalLocation: usableLocation,
                    reporterLocation: reporterPayload
                )
            }
        }
    }

    private func scheduleCleanup() {
        cleanupWork?.cancel()
        guard scanningActive else { return }
        let work = DispatchWorkItem { [weak self] in
            self?.cleanupStale()
        }
        cleanupWork = work
        queue.asyncAfter(deadline: .now() + cleanupInterval, execute: work)
    }

    private func cleanupStale() {
        guard scanningActive else { return }
        let cutoff = Date().addingTimeInterval(-staleInterval)
        let staleIds = broadcastsBySessionId.filter {
            $0.value.lastSeen < cutoff && !shouldRetainStaleSession($0.key)
        }.map { $0.key }

        if !staleIds.isEmpty {
            staleIds.forEach { sessionId in
                broadcastsBySessionId.removeValue(forKey: sessionId)
                if #available(iOS 26.0, *) {
                    if let connection = wifiAwareConnectionBySessionId.removeValue(forKey: sessionId) {
                        connection.disconnect()
                    }
                    wifiAwareEndpointBySessionId.removeValue(forKey: sessionId)
                    wifiAwareSessionIds.remove(sessionId)
                }
                if let connection = bleConnectionBySessionId.removeValue(forKey: sessionId) {
                    connection.disconnect()
                }
                if allowBLEFallback,
                   let peripheralId = blePeripheralIdBySessionId.removeValue(forKey: sessionId) {
                    bleSessionByPeripheralId.removeValue(forKey: peripheralId)
                }
                blePeripheralBySessionId.removeValue(forKey: sessionId)
            }
            publishBroadcasts()
        }
        scheduleCleanup()
    }

    private func shouldRetainStaleSession(_ sessionId: UUID) -> Bool {
        guard let entry = broadcastsBySessionId[sessionId], entry.status == .ready else {
            return false
        }

        if #available(iOS 26.0, *),
           let connection = wifiAwareConnectionBySessionId[sessionId] {
            if connection.canInitiateConnection && wifiAwareEndpointBySessionId[sessionId] == nil {
                return false
            }
            return true
        }

        if let connection = bleConnectionBySessionId[sessionId] {
            return connection.status == .ready
        }

        return false
    }

    private func publishErrorState() {
        DispatchQueue.main.async {
            self.errorMessageKey = self.wifiAwareErrorKey
        }
    }

    private func updateScanningState() {
        let bleActive = allowBLEFallback && (bleScanActive || !bleConnectionBySessionId.isEmpty)
        let active = hasActiveWiFiAwareTransport || bleActive
        scanningActive = active
        DispatchQueue.main.async {
            self.isScanning = active
        }
        if active {
            scheduleCleanup()
        } else {
            cleanupWork?.cancel()
            cleanupWork = nil
        }
    }

    private func resetDiscoveryState() {
        broadcastsBySessionId.removeAll()
        broadcastIdToSessionId.removeAll()
        if #available(iOS 26.0, *) {
            wifiAwareSessionByDeviceId.removeAll()
            wifiAwareEndpointBySessionId.removeAll()
            wifiAwareSessionIds.removeAll()

            wifiAwareConnectionBySessionId.values.forEach { $0.disconnect() }
            wifiAwareConnectionBySessionId.removeAll()
        }

        if allowBLEFallback {
            bleConnectionBySessionId.values.forEach { $0.disconnect() }
            bleConnectionBySessionId.removeAll()
            bleSessionByPeripheralId.removeAll()
            blePeripheralIdBySessionId.removeAll()
            blePeripheralBySessionId.removeAll()
        }

        publishBroadcasts()
    }

    private var hasActiveWiFiAwareTransport: Bool {
        guard #available(iOS 26.0, *) else {
            return false
        }
        return wifiAwareBrowserTask != nil || wifiAwareListenerTask != nil
    }

    @available(iOS 26.0, *)
    private func startWiFiAwareBrowsingIfNeeded() -> Bool {
        if wifiAwareBrowserTask != nil {
            return true
        }

        guard WACapabilities.supportedFeatures.contains(.wifiAware) else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
            return false
        }

        guard let service = WASubscribableService.allServices[wifiAwareServiceName] else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_CONFIGURATION"
            return false
        }

        wifiAwareErrorKey = nil
        let provider = WASubscriberBrowser.wifiAware(
            .connecting(to: subscriberDevicesForCurrentScope(), from: service)
        )
        let browser = NetworkBrowser(for: provider)
        browser.onStateUpdate { [weak self] _, state in
            self?.queue.async { [weak self] in
                self?.handleWiFiAwareBrowserState(state)
            }
        }

        wifiAwareBrowser = browser
        wifiAwareBrowserTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await browser.run { [weak self] endpoints in
                    self?.queue.async { [weak self] in
                        self?.applyWiFiAwareEndpoints(endpoints)
                    }
                }
            } catch is CancellationError {
                // Cancelled by stopScanning.
            } catch {
                self.queue.async { [weak self] in
                    self?.applyWiFiAwareRunError(error)
                }
            }

            self.queue.async { [weak self] in
                guard let self else { return }
                if self.wifiAwareBrowser === browser {
                    self.wifiAwareBrowser = nil
                }
                self.wifiAwareBrowserTask = nil
                self.updateScanningState()
                self.publishErrorState()
            }
        }

        return true
    }

    @available(iOS 26.0, *)
    private func startWiFiAwarePublishingIfNeeded() -> Bool {
        if wifiAwareListenerTask != nil {
            return true
        }

        guard WACapabilities.supportedFeatures.contains(.wifiAware) else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
            return false
        }

        guard let service = WAPublishableService.allServices[wifiAwareServiceName] else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_CONFIGURATION"
            return false
        }

        let provider = WAPublisherListener.wifiAware(
            .connecting(to: service, from: publisherDevicesForCurrentScope())
        )
        let listener: NetworkListener<TCP>
        do {
            listener = try NetworkListener(for: provider) { TCP() }
        } catch {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
            return false
        }

        listener.onStateUpdate { [weak self] _, state in
            self?.queue.async { [weak self] in
                self?.handleWiFiAwareListenerState(state)
            }
        }

        wifiAwareListener = listener
        wifiAwareListenerTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await listener.run { [weak self] incomingConnection in
                    self?.queue.async { [weak self] in
                        self?.attachIncomingConnection(incomingConnection)
                    }
                }
            } catch is CancellationError {
                // Cancelled by stopScanning.
            } catch {
                self.queue.async { [weak self] in
                    self?.applyWiFiAwareRunError(error)
                }
            }

            self.queue.async { [weak self] in
                guard let self else { return }
                if self.wifiAwareListener === listener {
                    self.wifiAwareListener = nil
                }
                self.wifiAwareListenerTask = nil
                self.updateScanningState()
                self.publishErrorState()
            }
        }

        return true
    }

    @available(iOS 26.0, *)
    private func subscriberDevicesForCurrentScope() -> WASubscriberBrowser.Devices {
        switch wifiAwarePeerScope {
        case .pairedOnly:
            return .allPairedDevices
        case .userSpecified:
            return .userSpecifiedDevices
        }
    }

    @available(iOS 26.0, *)
    private func publisherDevicesForCurrentScope() -> WAPublisherListener.Devices {
        switch wifiAwarePeerScope {
        case .pairedOnly:
            return .allPairedDevices
        case .userSpecified:
            return .userSpecifiedDevices
        }
    }

    @available(iOS 26.0, *)
    private func retryUsingUserSpecifiedDevicesIfNeeded() {
        guard wifiAwarePeerScope != .userSpecified else {
            return
        }

        wifiAwarePeerScope = .userSpecified
        stopWiFiAwareTransport()

        guard scanRequested else {
            return
        }

        let browserStarted = startWiFiAwareBrowsingIfNeeded()
        let listenerStarted = startWiFiAwarePublishingIfNeeded()
        if allowBLEFallback {
            startBLEScanningIfNeeded()
        }
        if !browserStarted && !listenerStarted && wifiAwareErrorKey == nil {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }
        updateScanningState()
    }

    @available(iOS 26.0, *)
    private func stopWiFiAwareTransport() {
        wifiAwareBrowserTask?.cancel()
        wifiAwareBrowserTask = nil
        wifiAwareBrowser = nil

        wifiAwareListenerTask?.cancel()
        wifiAwareListenerTask = nil
        wifiAwareListener = nil

        wifiAwareErrorKey = nil
        wifiAwareEndpointBySessionId.removeAll()
        wifiAwareSessionByDeviceId.removeAll()

        wifiAwareConnectionBySessionId.values.forEach { $0.disconnect() }
        wifiAwareConnectionBySessionId.removeAll()

        if !wifiAwareSessionIds.isEmpty {
            let now = Date()
            wifiAwareSessionIds.forEach { sessionId in
                if var entry = broadcastsBySessionId[sessionId] {
                    entry.status = .disconnected
                    entry.lastUpdated = now
                    broadcastsBySessionId[sessionId] = entry
                }
            }
            publishBroadcasts()
            wifiAwareSessionIds.removeAll()
        }

        publishErrorState()
    }

    private func stopBLETransport() {
        if let central = bleCentral, bleScanActive {
            central.stopScan()
        }
        bleScanActive = false

        bleConnectionBySessionId.values.forEach { $0.disconnect() }
        bleConnectionBySessionId.removeAll()
        bleSessionByPeripheralId.removeAll()
        blePeripheralIdBySessionId.removeAll()
        blePeripheralBySessionId.removeAll()
        bleCentral?.delegate = nil
        bleCentral = nil
    }

    @available(iOS 26.0, *)
    private func handleWiFiAwareBrowserState(_ state: NetworkBrowser<WASubscriberBrowser>.State) {
        switch state {
        case .ready:
            if wifiAwareErrorKey == "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES", broadcastsBySessionId.isEmpty {
                break
            }
            wifiAwareErrorKey = nil
        case .waiting(let error), .failed(let error):
            applyWiFiAwareError(error)
        case .setup, .cancelled:
            break
        @unknown default:
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }
        publishErrorState()
    }

    @available(iOS 26.0, *)
    private func handleWiFiAwareListenerState(_ state: NetworkListener<TCP>.State) {
        switch state {
        case .ready:
            if wifiAwareErrorKey == "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES", broadcastsBySessionId.isEmpty {
                break
            }
            wifiAwareErrorKey = nil
        case .waiting(let error), .failed(let error):
            applyWiFiAwareError(error)
        case .setup, .cancelled:
            break
        @unknown default:
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }
        publishErrorState()
    }

    @available(iOS 26.0, *)
    private func applyWiFiAwareRunError(_ error: Error) {
        if let nwError = error as? NWError {
            applyWiFiAwareError(nwError)
        } else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }
        publishErrorState()
    }

    @available(iOS 26.0, *)
    private func applyWiFiAwareError(_ error: NWError) {
        guard let awareError = error.wifiAware else {
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
            return
        }

        switch awareError {
        case .entitlementMissing(_):
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_ENTITLEMENT"
        case .serviceNotDeclared(_):
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_CONFIGURATION"
        case .noPairedDevices(_):
            retryUsingUserSpecifiedDevicesIfNeeded()
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES"
        case .wifiAwareUnsupported(_):
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        default:
            wifiAwareErrorKey = "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE"
        }
    }

    @available(iOS 26.0, *)
    private func applyWiFiAwareEndpoints(_ endpoints: [WAEndpoint]) {
        let now = Date()
        var visibleSessionIds: Set<UUID> = []
        let hadNoPairedError = wifiAwareErrorKey == "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES"

        for endpoint in endpoints {
            let sessionId = resolveWiFiAwareSessionId(for: endpoint.device.id)
            visibleSessionIds.insert(sessionId)
            wifiAwareEndpointBySessionId[sessionId] = endpoint

            let peerName = endpoint.device.name?.trimmingCharacters(in: .whitespacesAndNewlines)
            let name = (peerName?.isEmpty == false) ? peerName! : NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")

            var entry = broadcastsBySessionId[sessionId] ?? RescueBroadcast(
                id: sessionId,
                broadcastId: nil,
                peripheralId: sessionId,
                peripheralName: name,
                status: .connecting,
                rssi: nil,
                lastSeen: now,
                lastUpdated: now
            )
            entry.peripheralId = sessionId
            entry.peripheralName = name
            entry.rssi = nil
            entry.lastSeen = now
            entry.lastUpdated = now
            if !autoConnectToScannedBroadcasts && wifiAwareConnectionBySessionId[sessionId] == nil {
                entry.status = .disconnected
            } else if entry.status == .failed || entry.status == .disconnected {
                entry.status = .connecting
            }
            broadcastsBySessionId[sessionId] = entry

            connectWiFiAwareIfNeeded(sessionId: sessionId, endpoint: endpoint)
        }

        if hadNoPairedError, !visibleSessionIds.isEmpty {
            wifiAwareErrorKey = nil
            publishErrorState()
        }

        let staleDiscoveredSessions = wifiAwareEndpointBySessionId.keys.filter { !visibleSessionIds.contains($0) }
        for sessionId in staleDiscoveredSessions {
            wifiAwareEndpointBySessionId.removeValue(forKey: sessionId)
            if let connection = wifiAwareConnectionBySessionId[sessionId], connection.canInitiateConnection {
                connection.disconnect()
                wifiAwareConnectionBySessionId.removeValue(forKey: sessionId)
            }
            if var entry = broadcastsBySessionId[sessionId] {
                entry.status = .disconnected
                entry.lastUpdated = now
                broadcastsBySessionId[sessionId] = entry
            }
        }

        publishBroadcasts()
    }

    @available(iOS 26.0, *)
    private func attachIncomingConnection(_ incomingConnection: NetworkConnection<TCP>) {
        let sessionId = resolveIncomingSessionId(for: incomingConnection)
        let now = Date()

        if let existing = wifiAwareConnectionBySessionId[sessionId] {
            existing.disconnect()
            wifiAwareConnectionBySessionId.removeValue(forKey: sessionId)
        }

        let name = NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")
        var entry = broadcastsBySessionId[sessionId] ?? RescueBroadcast(
            id: sessionId,
            broadcastId: nil,
            peripheralId: sessionId,
            peripheralName: name,
            status: .connecting,
            rssi: nil,
            lastSeen: now,
            lastUpdated: now
        )
        entry.lastSeen = now
        entry.lastUpdated = now
        broadcastsBySessionId[sessionId] = entry
        wifiAwareSessionIds.insert(sessionId)

        let connection = RescueWiFiAwareConnection(
            sessionId: sessionId,
            endpoint: nil,
            queue: queue
        )
        bindCallbacks(for: connection, sessionId: sessionId)
        wifiAwareConnectionBySessionId[sessionId] = connection
        connection.attachIncoming(incomingConnection)

        publishBroadcasts()
    }

    @available(iOS 26.0, *)
    private func resolveWiFiAwareSessionId(for deviceId: WAPairedDevice.ID) -> UUID {
        if let existing = wifiAwareSessionByDeviceId[deviceId] {
            return existing
        }
        let sessionId = BroadcastSessionId.fromBroadcastId("WIFI-AWARE-\(deviceId)")
        wifiAwareSessionByDeviceId[deviceId] = sessionId
        wifiAwareSessionIds.insert(sessionId)
        return sessionId
    }

    @available(iOS 26.0, *)
    private func resolveIncomingSessionId(for connection: NetworkConnection<TCP>) -> UUID {
        let token = connection.id
        return BroadcastSessionId.fromBroadcastId("WIFI-AWARE-IN-\(token)")
    }

    @available(iOS 26.0, *)
    private func connectWiFiAwareIfNeeded(sessionId: UUID, endpoint: WAEndpoint, force: Bool = false) {
        if let existing = wifiAwareConnectionBySessionId[sessionId] {
            if let status = broadcastsBySessionId[sessionId]?.status,
               status == .failed || status == .disconnected || status == .connecting {
                if !existing.canInitiateConnection {
                    existing.disconnect()
                    wifiAwareConnectionBySessionId.removeValue(forKey: sessionId)
                } else {
                    existing.connect()
                    return
                }
            } else {
                return
            }
        }

        guard force || autoConnectToScannedBroadcasts else {
            return
        }

        let connection = RescueWiFiAwareConnection(
            sessionId: sessionId,
            endpoint: endpoint,
            queue: queue
        )
        bindCallbacks(for: connection, sessionId: sessionId)
        wifiAwareConnectionBySessionId[sessionId] = connection
        connection.connect()
    }

    @available(iOS 26.0, *)
    private func bindCallbacks(for connection: RescueWiFiAwareConnection, sessionId: UUID) {
        connection.onStatusChange = { [weak self] status in
            self?.queue.async { [weak self] in
                guard let self else { return }
                if (status == .failed || status == .disconnected),
                   connection.canInitiateConnection,
                   self.wifiAwareEndpointBySessionId[sessionId] == nil {
                    self.wifiAwareConnectionBySessionId.removeValue(forKey: sessionId)
                }
                self.applyStatus(status, to: sessionId)
            }
        }
        connection.onBroadcastId = { [weak self] broadcastId in
            self?.queue.async { [weak self] in
                self?.applyBroadcastId(broadcastId, sessionId: sessionId)
            }
        }
        connection.onPeerName = { [weak self] peerName in
            self?.queue.async { [weak self] in
                self?.applyPeerName(peerName, sessionId: sessionId)
            }
        }
        connection.onPeerBattery = { [weak self] batteryPercent in
            self?.queue.async { [weak self] in
                self?.applyPeerBattery(batteryPercent, sessionId: sessionId)
            }
        }
        connection.onSignalLocation = { [weak self] signalLocation in
            self?.queue.async { [weak self] in
                self?.applySignalLocation(signalLocation, sessionId: sessionId)
            }
        }
    }

    private func bindCallbacks(for connection: RescueConnection, sessionId: UUID) {
        connection.onStatusChange = { [weak self] status in
            self?.queue.async { [weak self] in
                self?.applyStatus(status, to: sessionId)
            }
        }
        connection.onPeerMedical = { [weak self] medical in
            self?.queue.async { [weak self] in
                self?.applyPeerMedical(medical, sessionId: sessionId)
            }
        }
        connection.onBroadcastId = { [weak self] broadcastId in
            self?.queue.async { [weak self] in
                self?.applyBroadcastId(broadcastId, sessionId: sessionId)
            }
        }
        connection.onPeerName = { [weak self] peerName in
            self?.queue.async { [weak self] in
                self?.applyPeerName(peerName, sessionId: sessionId)
            }
        }
        connection.onPeerBattery = { [weak self] batteryPercent in
            self?.queue.async { [weak self] in
                self?.applyPeerBattery(batteryPercent, sessionId: sessionId)
            }
        }
        connection.onSignalLocation = { [weak self] signalLocation in
            self?.queue.async { [weak self] in
                self?.applySignalLocation(signalLocation, sessionId: sessionId)
            }
        }
    }

    private func startBLEScanningIfNeeded() {
        guard scanRequested else { return }
        ensureBleCentralInitialized()
        guard let central = bleCentral else {
            if !hasActiveWiFiAwareTransport {
                wifiAwareErrorKey = "RESCUE_ERROR_BLUETOOTH_UNAVAILABLE"
            }
            return
        }

        switch central.state {
        case .poweredOn:
            if !bleScanActive {
                central.scanForPeripherals(
                    withServices: [rescueServiceUUID],
                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
                )
                bleScanActive = true
            }
            if wifiAwareErrorKey == "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES" ||
                wifiAwareErrorKey == "RESCUE_ERROR_WIFI_AWARE_UNAVAILABLE" {
                wifiAwareErrorKey = nil
            }
        case .unsupported:
            bleScanActive = false
            if !hasActiveWiFiAwareTransport {
                wifiAwareErrorKey = "RESCUE_ERROR_BLUETOOTH_UNAVAILABLE"
            }
        case .unauthorized:
            bleScanActive = false
            if !hasActiveWiFiAwareTransport {
                wifiAwareErrorKey = "RESCUE_ERROR_BLUETOOTH_UNAUTHORIZED"
            }
        case .poweredOff:
            bleScanActive = false
            if !hasActiveWiFiAwareTransport {
                wifiAwareErrorKey = "RESCUE_ERROR_BLUETOOTH_DISABLED"
            }
        default:
            break
        }
        publishErrorState()
        updateScanningState()
    }

    private func applyBLEDiscovery(_ peripheral: CBPeripheral, advertisementData: [String: Any], rssi: NSNumber) {
        let now = Date()
        let broadcastId = parseBLEBroadcastId(from: advertisementData, peripheral: peripheral)
        let sessionId = resolveBLESessionId(for: peripheral, broadcastId: broadcastId)
        blePeripheralBySessionId[sessionId] = peripheral
        let displayName = ((advertisementData[CBAdvertisementDataLocalNameKey] as? String) ??
                           peripheral.name)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (displayName?.isEmpty == false) ? displayName! : NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")

        var entry = broadcastsBySessionId[sessionId] ?? RescueBroadcast(
            id: sessionId,
            broadcastId: broadcastId,
            peripheralId: peripheral.identifier,
            peripheralName: name,
            status: .connecting,
            rssi: rssi.intValue,
            lastSeen: now,
            lastUpdated: now
        )
        entry.peripheralId = peripheral.identifier
        entry.peripheralName = name
        entry.rssi = rssi.intValue
        entry.lastSeen = now
        entry.lastUpdated = now
        if !autoConnectToScannedBroadcasts && bleConnectionBySessionId[sessionId] == nil {
            entry.status = .disconnected
        } else if entry.status == .failed || entry.status == .disconnected {
            entry.status = .connecting
        }
        if let broadcastId {
            entry.broadcastId = broadcastId
        }
        broadcastsBySessionId[sessionId] = entry
        connectBLEIfNeeded(sessionId: sessionId, peripheral: peripheral, broadcastId: broadcastId)
        publishBroadcasts()
    }

    private func resolveBLESessionId(for peripheral: CBPeripheral, broadcastId: String?) -> UUID {
        let peripheralId = peripheral.identifier
        if let normalized = broadcastId, let existing = broadcastIdToSessionId[normalized] {
            bleSessionByPeripheralId[peripheralId] = existing
            blePeripheralIdBySessionId[existing] = peripheralId
            return existing
        }
        if let existing = bleSessionByPeripheralId[peripheralId] {
            if let normalized = broadcastId {
                broadcastIdToSessionId[normalized] = existing
            }
            return existing
        }
        if let normalized = broadcastId {
            let sessionId = BroadcastSessionId.fromBroadcastId(normalized)
            broadcastIdToSessionId[normalized] = sessionId
            bleSessionByPeripheralId[peripheralId] = sessionId
            blePeripheralIdBySessionId[sessionId] = peripheralId
            return sessionId
        }
        let sessionId = BroadcastSessionId.fromBroadcastId("BLE-\(peripheralId.uuidString)")
        bleSessionByPeripheralId[peripheralId] = sessionId
        blePeripheralIdBySessionId[sessionId] = peripheralId
        return sessionId
    }

    private func connectBLEIfNeeded(
        sessionId: UUID,
        peripheral: CBPeripheral,
        broadcastId: String?,
        force: Bool = false
    ) {
        guard let central = bleCentral else { return }
        if let existing = bleConnectionBySessionId[sessionId] {
            if force || autoConnectToScannedBroadcasts {
                existing.connect(using: central)
            }
            return
        }
        guard force || autoConnectToScannedBroadcasts else { return }
        let connection = RescueConnection(
            peripheral: peripheral,
            sessionId: sessionId,
            broadcastId: broadcastId,
            preSharedKey: nil,
            queue: queue
        )
        bindCallbacks(for: connection, sessionId: sessionId)
        bleConnectionBySessionId[sessionId] = connection
        connection.connect(using: central)
    }

    private func parseBLEBroadcastId(from advertisementData: [String: Any], peripheral: CBPeripheral) -> String? {
        if let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] {
            let payload: Data?

            if let typed = serviceData as? [CBUUID: Data] {
                payload = typed.first(where: { isRescueServiceUUID($0.key) })?.value
            } else if let typed = serviceData as? [String: Data] {
                payload = typed.first(where: { isRescueServiceUUIDString($0.key) })?.value
            } else if let typed = serviceData as? [AnyHashable: Data] {
                payload = typed.first(where: { key, _ in
                    if let uuid = key as? CBUUID {
                        return isRescueServiceUUID(uuid)
                    }
                    if let string = key as? String {
                        return isRescueServiceUUIDString(string)
                    }
                    return false
                })?.value
            } else {
                payload = nil
            }

            if let payload {
                if payload.count == 12 {
                    let hex = payload.map { String(format: "%02x", $0) }.joined()
                    return normalizeBroadcastId("cc-\(hex)")
                }

                if let raw = String(data: payload, encoding: .utf8) {
                    return normalizeBroadcastId(raw)
                }
            }
        }

        let nameCandidates = [
            advertisementData[CBAdvertisementDataLocalNameKey] as? String,
            peripheral.name
        ]
        for candidate in nameCandidates {
            if let normalized = normalizeBroadcastId(candidate) {
                return normalized
            }
        }
        return nil
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        queue.async { [weak self] in
            guard let self else { return }
            if !self.allowBLEFallback || !self.scanRequested {
                return
            }
            self.startBLEScanningIfNeeded()
        }
    }

    private func ensureBleCentralInitialized() {
        guard allowBLEFallback, bleCentral == nil else { return }
        bleCentral = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: Self.bleCentralRestoreIdentifier,
                CBCentralManagerOptionShowPowerAlertKey: false
            ]
        )
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        queue.async { [weak self] in
            guard let self else { return }

            let restoredPeripherals = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? []
            let restoredScanServices = dict[CBCentralManagerRestoredStateScanServicesKey] as? [CBUUID]
            let restoredScanOptions = dict[CBCentralManagerRestoredStateScanOptionsKey] as? [String: Any]
            let restoredWasScanning = restoredScanServices != nil || restoredScanOptions != nil

            self.applyRuntimeSettingsSnapshot()
            self.scanRequested = restoredWasScanning || self.manualScanRequested || self.persistentRuntimeRequested

            guard !restoredPeripherals.isEmpty else {
                self.updateScanningState()
                return
            }

            let now = Date()
            for peripheral in restoredPeripherals {
                let sessionId = self.resolveBLESessionId(for: peripheral, broadcastId: nil)
                self.blePeripheralBySessionId[sessionId] = peripheral

                var entry = self.broadcastsBySessionId[sessionId] ?? RescueBroadcast(
                    id: sessionId,
                    broadcastId: nil,
                    peripheralId: peripheral.identifier,
                    peripheralName: {
                        let trimmedName = peripheral.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                        return trimmedName.isEmpty
                            ? NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")
                            : trimmedName
                    }(),
                    status: .connecting,
                    rssi: nil,
                    lastSeen: now,
                    lastUpdated: now
                )
                entry.peripheralId = peripheral.identifier
                entry.lastSeen = now
                entry.lastUpdated = now
                entry.status = peripheral.state == .connected ? .connected : .connecting
                self.broadcastsBySessionId[sessionId] = entry

                if self.bleConnectionBySessionId[sessionId] == nil {
                    let connection = RescueConnection(
                        peripheral: peripheral,
                        sessionId: sessionId,
                        broadcastId: entry.broadcastId,
                        preSharedKey: nil,
                        queue: self.queue
                    )
                    self.bindCallbacks(for: connection, sessionId: sessionId)
                    self.bleConnectionBySessionId[sessionId] = connection
                }

                if peripheral.state == .connected,
                   let connection = self.bleConnectionBySessionId[sessionId] {
                    connection.didConnect()
                }
            }

            self.publishBroadcasts(force: true)
            if self.scanRequested {
                self.startBLEScanningIfNeeded()
            }
            self.updateScanningState()
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        queue.async { [weak self] in
            // Every advert processed is a scan event for the dashboard's responder telemetry —
            // these counters shipped hardcoded to 0 for the app's whole life.
            ResponderSignalStats.shared.recordScanEvent()
            self?.applyBLEDiscovery(peripheral, advertisementData: advertisementData, rssi: RSSI)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        queue.async { [weak self] in
            guard let self else { return }
            guard let sessionId = self.bleSessionByPeripheralId[peripheral.identifier],
                  let connection = self.bleConnectionBySessionId[sessionId] else { return }
            connection.didConnect()
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        queue.async { [weak self] in
            guard let self else { return }
            guard let sessionId = self.bleSessionByPeripheralId[peripheral.identifier],
                  let connection = self.bleConnectionBySessionId[sessionId] else { return }
            connection.didDisconnect(error: error)
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        queue.async { [weak self] in
            guard let self else { return }
            guard let sessionId = self.bleSessionByPeripheralId[peripheral.identifier],
                  let connection = self.bleConnectionBySessionId[sessionId] else { return }
            connection.didDisconnect(error: error)
        }
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync { block() }
    }

    private func normalizeBroadcastId(_ raw: String?) -> String? {
        guard let raw else { return nil }
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let cleaned = trimmed
            .replacingOccurrences(of: "ccid:", with: "", options: [.caseInsensitive])
            .replacingOccurrences(of: "cc-", with: "", options: [.caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }

        let hex: String
        switch cleaned.count {
        case 16:
            hex = cleaned + "00000000"
        case 24:
            hex = cleaned
        default:
            return nil
        }

        guard hex.allSatisfy({ $0.isHexDigit }) else { return nil }
        return "CC-\(hex.uppercased())"
    }

    private func normalizedSignalId(from broadcastId: String?) -> String? {
        guard let normalized = normalizeBroadcastId(broadcastId) else { return nil }
        return normalized.lowercased()
    }

    private static func dashboardPayload(from gps: SOSSignalLocationPayload.GPSPayload) -> CrisisLinkDashboardSyncService.LocationPayload {
        CrisisLinkDashboardSyncService.LocationPayload(
            latitude: gps.latitude,
            longitude: gps.longitude,
            horizontalAccuracyMeters: gps.horizontalAccuracyMeters,
            capturedAt: gps.capturedAt
        )
    }

    private func isRescueServiceUUID(_ uuid: CBUUID) -> Bool {
        uuid == rescueServiceUUID || uuid == advertisedRescueServiceUUID
    }

    private func isRescueServiceUUIDString(_ value: String) -> Bool {
        let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return normalized == rescueServiceUUID.uuidString.lowercased() ||
            normalized == advertisedRescueServiceUUID.uuidString.lowercased() ||
            normalized == "0000cc00-0000-1000-8000-00805f9b34fb" ||
            normalized == "cc00"
    }

    private func preferredSessionId(between lhs: UUID, and rhs: UUID) -> UUID {
        RescueSessionMergePlanner.preferredSessionId(
            lhs: lhs,
            lhsStatus: broadcastsBySessionId[lhs]?.status ?? .disconnected,
            rhs: rhs,
            rhsStatus: broadcastsBySessionId[rhs]?.status ?? .disconnected
        )
    }

    private func sessionPriority(for sessionId: UUID) -> Int {
        RescueSessionMergePlanner.priority(
            for: broadcastsBySessionId[sessionId]?.status ?? .disconnected
        )
    }

    private func mergeSession(_ sourceSessionId: UUID, into targetSessionId: UUID, broadcastId: String) {
        guard sourceSessionId != targetSessionId else {
            broadcastIdToSessionId[broadcastId] = targetSessionId
            return
        }

        let sourceEntry = broadcastsBySessionId[sourceSessionId]
        let targetEntry = broadcastsBySessionId[targetSessionId]

        let merged = RescueSessionMergePlanner.mergedBroadcast(
            targetSessionId: targetSessionId,
            targetEntry: targetEntry,
            targetStatus: targetEntry?.status ?? .disconnected,
            sourceEntry: sourceEntry,
            sourceStatus: sourceEntry?.status ?? .disconnected,
            broadcastId: broadcastId
        )
        broadcastsBySessionId[targetSessionId] = merged
        broadcastsBySessionId.removeValue(forKey: sourceSessionId)
        broadcastIdToSessionId[broadcastId] = targetSessionId
        var remappedBroadcastIds: [String: UUID] = [:]
        broadcastIdToSessionId.forEach { entry in
            remappedBroadcastIds[entry.key] = (entry.value == sourceSessionId ? targetSessionId : entry.value)
        }
        broadcastIdToSessionId = remappedBroadcastIds

        if #available(iOS 26.0, *) {
            if let sourceConnection = wifiAwareConnectionBySessionId.removeValue(forKey: sourceSessionId) {
                sourceConnection.disconnect()
            }
            if let sourceEndpoint = wifiAwareEndpointBySessionId.removeValue(forKey: sourceSessionId),
               wifiAwareEndpointBySessionId[targetSessionId] == nil {
                wifiAwareEndpointBySessionId[targetSessionId] = sourceEndpoint
            }
            wifiAwareSessionByDeviceId.keys.forEach { deviceId in
                if wifiAwareSessionByDeviceId[deviceId] == sourceSessionId {
                    wifiAwareSessionByDeviceId[deviceId] = targetSessionId
                }
            }
            if wifiAwareSessionIds.remove(sourceSessionId) != nil {
                wifiAwareSessionIds.insert(targetSessionId)
            }
        }

        if let sourceBleConnection = bleConnectionBySessionId.removeValue(forKey: sourceSessionId) {
            sourceBleConnection.disconnect()
        }
        if let sourcePeripheralId = blePeripheralIdBySessionId.removeValue(forKey: sourceSessionId) {
            bleSessionByPeripheralId.removeValue(forKey: sourcePeripheralId)
        }
        if let sourcePeripheral = blePeripheralBySessionId.removeValue(forKey: sourceSessionId),
           blePeripheralBySessionId[targetSessionId] == nil {
            blePeripheralBySessionId[targetSessionId] = sourcePeripheral
        }

        DispatchQueue.main.async {
            SOSChatStore.shared.mergeSession(from: sourceSessionId, into: targetSessionId)
        }
        publishBroadcasts()
    }
}
