//
//  OfflineMapViewModel.swift
//  Crisis Connect
//
//  Created by Codex on 27.12.2025
//

import SwiftUI
import Foundation
import CoreLocation
import Combine

final class OfflineMapViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published var regions: [OfflineMapRegion] = []
    @Published var selectionBounds: MapBounds?
    @Published var editorName: String = ""
    @Published var minZoom: Int = OfflineMapConfiguration.defaultMinZoom
    @Published var maxZoom: Int = OfflineMapConfiguration.defaultMaxZoom
    @Published var estimatedTileCount: Int = 0
    @Published var estimatedBytes: Int64 = 0
    @Published var activeSheet: OfflineMapSheet?
    @Published var alertState: OfflineMapAlertState?
    @Published var tileRefreshToken = UUID()
    @Published var focusToken = UUID()
    @Published var centerOnUserToken = UUID()
    @Published var isLocationAuthorized = false
    @Published var lastKnownLocation: CLLocation?
    @Published var userZoomMeters: CLLocationDistance = 400
    @Published var toast: OfflineMapToast?
    @Published var allowsNetworkFallback: Bool {
        didSet {
            UserDefaults.standard.set(allowsNetworkFallback, forKey: Keys.allowsNetworkFallback)
        }
    }

    private let locationManager = CLLocationManager()
    private let tileStore = OfflineTileStore()
    private let regionStore = OfflineRegionStore()
    private var downloadTasks: [UUID: Task<Void, Never>] = [:]
    private var pendingRegion: OfflineMapRegion?
    private var focusBounds: MapBounds?
    private var pendingLocationFocus = false
    private var toastWorkItem: DispatchWorkItem?
    private var regionMetadataRefreshTask: Task<Void, Never>?
    private var regionMetadataRefreshGeneration = 0
    private var hasActivated = false

    override init() {
        allowsNetworkFallback = UserDefaults.standard.object(forKey: Keys.allowsNetworkFallback) as? Bool ?? true
        super.init()
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    deinit {
        regionMetadataRefreshTask?.cancel()
        toastWorkItem?.cancel()
    }

    var activeRegion: OfflineMapRegion? {
        regions.first { $0.status == .downloading }
            ?? regions.first { $0.status == .paused }
    }

    func updateVisibleBounds(_ bounds: MapBounds) {
        selectionBounds = bounds
        updateEstimates()
    }

    func activate() {
        guard !hasActivated else { return }
        hasActivated = true
        loadRegions()
        resumePersistedDownloadsIfNeeded()
    }

    func updateMinZoom(_ value: Int) {
        minZoom = min(max(value, OfflineMapConfiguration.downloadMinZoom), OfflineMapConfiguration.downloadMaxZoom)
        if minZoom > maxZoom {
            maxZoom = minZoom
        }
        updateEstimates()
    }

    func updateMaxZoom(_ value: Int) {
        maxZoom = min(max(value, OfflineMapConfiguration.downloadMinZoom), OfflineMapConfiguration.downloadMaxZoom)
        if maxZoom < minZoom {
            minZoom = maxZoom
        }
        updateEstimates()
    }

    func openEditor() {
        activeSheet = .editor
    }

    func openList() {
        activeSheet = .list
    }

    func dismissSheet() {
        activeSheet = nil
    }

    func startDownload(force: Bool = false) {
        guard let bounds = selectionBounds else {
            alertState = OfflineMapAlertState.error(NSLocalizedString("Select a map area first.", comment: ""))
            return
        }
        guard OfflineMapConfiguration.hasMapTilerAPIKey else {
            alertState = OfflineMapAlertState.error(NSLocalizedString("Offline map provider key is not configured.", comment: ""))
            return
        }

        let trimmedName = editorName.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = trimmedName.isEmpty ? defaultRegionName() : trimmedName
        let tileCount = TileCalculator.tileCount(bounds: bounds, minZoom: minZoom, maxZoom: maxZoom)

        guard tileCount > 0 else {
            alertState = OfflineMapAlertState.error(NSLocalizedString("Region is too small for the selected zoom range.", comment: ""))
            return
        }

        let estimatedBytes = Int64(tileCount) * OfflineMapConfiguration.averageTileBytes
        let region = OfflineMapRegion(
            id: UUID(),
            name: name,
            bounds: bounds.normalized,
            minZoom: minZoom,
            maxZoom: maxZoom,
            tileCount: tileCount,
            downloadedTiles: 0,
            missingTiles: 0,
            status: .downloading,
            createdAt: Date(),
            lastError: nil
        )

        if !force && shouldWarn(tileCount: tileCount, estimatedBytes: estimatedBytes) {
            pendingRegion = region
            alertState = OfflineMapAlertState.warning(
                NSLocalizedString("This download may be large and take time. Continue?", comment: "")
            )
            return
        }

        beginDownload(region)
        editorName = ""
        activeSheet = nil
    }

    func confirmWarningDownload() {
        guard let region = pendingRegion else { return }
        pendingRegion = nil
        beginDownload(region)
        editorName = ""
        activeSheet = nil
    }

    func cancelWarningDownload() {
        pendingRegion = nil
    }

    func pauseRegion(_ region: OfflineMapRegion) {
        downloadTasks[region.id]?.cancel()
        downloadTasks[region.id] = nil
        updateRegion(region.id) { item in
            item.status = .paused
        }
        showToast(NSLocalizedString("Download paused", comment: ""), style: .info)
        saveRegions()
    }

    func resumeRegion(_ region: OfflineMapRegion) {
        guard region.status != .downloading else { return }
        showToast(NSLocalizedString("Download resumed", comment: ""), style: .info)
        beginDownload(region, announce: false)
    }

    func deleteRegion(_ region: OfflineMapRegion) {
        downloadTasks[region.id]?.cancel()
        downloadTasks[region.id] = nil
        tileStore.removeRegion(regionId: region.id)
        regions.removeAll { $0.id == region.id }
        tileRefreshToken = UUID()
        saveRegions()
    }

    func renameRegion(_ region: OfflineMapRegion, name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        updateRegion(region.id) { item in
            item.name = trimmed
        }
        saveRegions()
    }

    func focusOnRegion(_ region: OfflineMapRegion) {
        focusBounds = region.bounds
        focusToken = UUID()
        activeSheet = nil
    }

    // External pins (e.g. Crisis Sentinel "show on map"): drop labeled markers and fit them.
    @Published private(set) var externalPins: [OfflineMapPin] = []

    func showExternalPins(_ pins: [OfflineMapPin]) {
        externalPins = pins
        guard !pins.isEmpty else { return }
        if pins.count == 1 {
            let c = pins[0].coordinate
            // A single point → a tight box around it so setVisibleMapRect zooms in.
            focusBounds = MapBounds(north: c.latitude + 0.02, south: c.latitude - 0.02,
                                    east: c.longitude + 0.02, west: c.longitude - 0.02)
        } else {
            let lats = pins.map { $0.coordinate.latitude }
            let lngs = pins.map { $0.coordinate.longitude }
            focusBounds = MapBounds(north: lats.max()!, south: lats.min()!,
                                    east: lngs.max()!, west: lngs.min()!)
        }
        focusToken = UUID()
    }

    func requestUserLocation() {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            pendingLocationFocus = true
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            isLocationAuthorized = true
            pendingLocationFocus = true
            locationManager.requestLocation()
        case .denied, .restricted:
            alertState = OfflineMapAlertState.error(NSLocalizedString("Location permission is disabled. Enable it in Settings.", comment: ""))
        @unknown default:
            alertState = OfflineMapAlertState.error(NSLocalizedString("Unable to access location.", comment: ""))
        }
    }

    func consumeFocusBounds() -> MapBounds? {
        let bounds = focusBounds
        focusBounds = nil
        return bounds
    }

    private func loadRegions() {
        regions = normalizedRegions(regionStore.load())
        updateEstimates()
        refreshPersistedRegionState()
    }

    private func saveRegions() {
        regionStore.save(regions)
        OfflineMapBackgroundManager.shared.scheduleIfNeeded()
    }

    private func updateEstimates() {
        guard let bounds = selectionBounds else {
            estimatedTileCount = 0
            estimatedBytes = 0
            return
        }
        estimatedTileCount = TileCalculator.tileCount(bounds: bounds, minZoom: minZoom, maxZoom: maxZoom)
        estimatedBytes = Int64(estimatedTileCount) * OfflineMapConfiguration.averageTileBytes
    }

    private func beginDownload(_ region: OfflineMapRegion, announce: Bool = true) {
        var region = region
        let initialDownloadedTiles = max(0, min(region.downloadedTiles, max(0, region.tileCount - region.missingTiles)))
        region.downloadedTiles = initialDownloadedTiles
        let processedTiles = initialDownloadedTiles + region.missingTiles
        region.status = processedTiles >= region.tileCount ? .complete : .downloading
        if region.status == .complete {
            region.downloadCursorOrdinal = nil
        }

        if let index = regions.firstIndex(where: { $0.id == region.id }) {
            regions[index] = region
        } else {
            regions.append(region)
        }
        saveRegions()

        guard region.status == .downloading else { return }
        guard downloadTasks[region.id] == nil else { return }
        if announce {
            showToast(NSLocalizedString("Download started", comment: ""), style: .info)
        }

        let plan = OfflineMapDownloadPlan(
            region: region,
            initialDownloaded: initialDownloadedTiles,
            resumeOrdinal: region.downloadCursorOrdinal
        )
        let session = OfflineMapNetworkSession.interactive

        let task = Task(priority: .utility) { [weak self] in
            let store = OfflineTileStore()
            let result = await OfflineMapDownloader.run(
                plan: plan,
                tileStore: store,
                session: session
            ) { progress in
                await MainActor.run {
                    guard self?.downloadTasks[plan.region.id] != nil else { return }
                    self?.updateRegion(plan.region.id) { item in
                        item.downloadedTiles = progress.downloadedTiles
                        item.missingTiles = progress.missingTiles
                        item.downloadCursorOrdinal = progress.lastCompletedOrdinal
                        item.status = .downloading
                        item.lastError = nil
                    }
                    if progress.downloadedTiles % OfflineMapConfiguration.refreshStride == 0 {
                        self?.tileRefreshToken = UUID()
                    }
                }
            }
            await MainActor.run {
                self?.finalizeDownload(regionId: plan.region.id, result: result)
            }
        }
        downloadTasks[region.id] = task
    }

    private func finalizeDownload(regionId: UUID, result: OfflineMapDownloadResult) {
        downloadTasks[regionId] = nil
        guard let index = regions.firstIndex(where: { $0.id == regionId }) else { return }
        switch result {
        case .completed:
            regions[index].status = .complete
            regions[index].lastError = nil
            regions[index].downloadCursorOrdinal = nil
            tileRefreshToken = UUID()
            showToast(String(format: NSLocalizedString("Download complete: %@", comment: ""), regions[index].name), style: .success)
        case .paused:
            regions[index].status = .paused
        case .failed(let error):
            regions[index].status = .failed
            regions[index].lastError = error
            alertState = OfflineMapAlertState.error(String(format: NSLocalizedString("Download failed: %@", comment: ""), "\(error)"))
        }
        saveRegions()
    }

    private func updateRegion(_ regionId: UUID, update: (inout OfflineMapRegion) -> Void) {
        guard let index = regions.firstIndex(where: { $0.id == regionId }) else { return }
        var item = regions[index]
        update(&item)
        regions[index] = item
    }

    private func defaultRegionName() -> String {
        String(format: NSLocalizedString("Offline Region %d", comment: ""), regions.count + 1)
    }

    private func resumePersistedDownloadsIfNeeded() {
        let activeDownloads = regions.filter {
            $0.status == .downloading &&
                ($0.downloadedTiles + $0.missingTiles) < $0.tileCount
        }
        for region in activeDownloads {
            beginDownload(region, announce: false)
        }
    }

    private func shouldWarn(tileCount: Int, estimatedBytes: Int64) -> Bool {
        tileCount >= OfflineMapConfiguration.warningTileLimit
            || estimatedBytes >= OfflineMapConfiguration.warningByteLimit
    }

    private func normalizedRegions(_ persistedRegions: [OfflineMapRegion]) -> [OfflineMapRegion] {
        var normalized = persistedRegions
        for index in normalized.indices {
            let processedTiles = normalized[index].downloadedTiles + normalized[index].missingTiles
            if normalized[index].tileCount > 0 && processedTiles >= normalized[index].tileCount {
                normalized[index].status = .complete
                normalized[index].downloadCursorOrdinal = nil
            }
        }
        return normalized
    }

    private func refreshPersistedRegionState() {
        let snapshot = regions.filter { $0.status != .downloading }
        guard !snapshot.isEmpty else { return }

        regionMetadataRefreshGeneration += 1
        let generation = regionMetadataRefreshGeneration
        regionMetadataRefreshTask?.cancel()
        regionMetadataRefreshTask = Task(priority: .utility) { [weak self] in
            let store = OfflineTileStore()
            var refreshedByID: [UUID: OfflineMapRegion] = [:]
            refreshedByID.reserveCapacity(snapshot.count)

            for region in snapshot {
                if Task.isCancelled { return }

                let countedTiles = store.countTiles(regionId: region.id)
                var refreshedRegion = region
                refreshedRegion.downloadedTiles = countedTiles

                let processedTiles = countedTiles + refreshedRegion.missingTiles
                if refreshedRegion.tileCount > 0 {
                    if processedTiles >= refreshedRegion.tileCount {
                        refreshedRegion.status = .complete
                        refreshedRegion.downloadCursorOrdinal = nil
                    } else if refreshedRegion.status == .complete {
                        refreshedRegion.status = .paused
                    }
                }

                refreshedByID[refreshedRegion.id] = refreshedRegion
            }

            await MainActor.run { [weak self] in
                guard let self else { return }
                guard generation == self.regionMetadataRefreshGeneration else { return }

                var hasChanges = false
                for index in self.regions.indices {
                    let regionId = self.regions[index].id
                    guard self.downloadTasks[regionId] == nil,
                          let refreshedRegion = refreshedByID[regionId],
                          self.regions[index] != refreshedRegion else {
                        continue
                    }

                    self.regions[index] = refreshedRegion
                    hasChanges = true
                }

                if hasChanges {
                    self.saveRegions()
                }
            }
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            isLocationAuthorized = true
            manager.requestLocation()
            if pendingLocationFocus {
                // Wait for a fresh location update before zooming.
            }
        default:
            isLocationAuthorized = false
            pendingLocationFocus = false
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        lastKnownLocation = locations.last
        if pendingLocationFocus {
            centerOnUserToken = UUID()
            pendingLocationFocus = false
        }
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        alertState = OfflineMapAlertState.error(NSLocalizedString("Unable to fetch location.", comment: ""))
    }

    private func showToast(_ message: String, style: OfflineMapToast.Style = .info) {
        toastWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            self?.toast = nil
        }
        toastWorkItem = item
        toast = OfflineMapToast(message: message, style: style)
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.2, execute: item)
    }

    private enum Keys {
        static let allowsNetworkFallback = "offlineMapAllowsNetworkFallback"
    }
}

struct OfflineMapToast: Identifiable {
    enum Style {
        case info
        case success
    }

    let id = UUID()
    let message: String
    let style: Style
}

struct OfflineMapAlertState: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let isWarning: Bool

    static func warning(_ message: String) -> OfflineMapAlertState {
        OfflineMapAlertState(title: NSLocalizedString("Large Download", comment: ""), message: message, isWarning: true)
    }

    static func error(_ message: String) -> OfflineMapAlertState {
        OfflineMapAlertState(title: NSLocalizedString("Offline Maps", comment: ""), message: message, isWarning: false)
    }
}

struct OfflineMapDownloadPlan: Sendable {
    let region: OfflineMapRegion
    let initialDownloaded: Int
    let resumeOrdinal: Int?
}

struct OfflineMapDownloadProgress: Sendable {
    let downloadedTiles: Int
    let missingTiles: Int
    let lastCompletedOrdinal: Int?
}

struct OfflineMapRetryPolicy: Sendable {
    let maxAttempts: Int
    let initialDelaySeconds: TimeInterval
    let maximumDelaySeconds: TimeInterval
    let retryableHTTPStatusCodes: Set<Int>
    let retryableURLErrorCodes: Set<URLError.Code>

    static let standard = OfflineMapRetryPolicy(
        maxAttempts: 3,
        initialDelaySeconds: 0.35,
        maximumDelaySeconds: 2.5,
        retryableHTTPStatusCodes: [408, 425, 429, 500, 502, 503, 504],
        retryableURLErrorCodes: [
            .timedOut,
            .networkConnectionLost,
            .cannotFindHost,
            .cannotConnectToHost,
            .dnsLookupFailed,
            .notConnectedToInternet,
            .internationalRoamingOff,
            .callIsActive
        ]
    )

    func delaySeconds(forAttempt attempt: Int) -> TimeInterval {
        let exponent = max(0, attempt - 1)
        let rawDelay = initialDelaySeconds * pow(2.0, Double(exponent))
        return min(maximumDelaySeconds, rawDelay)
    }
}

enum OfflineMapTileFetchOutcome {
    case success(Data)
    case missing
    case cancelled
    case fatal(String)
}

enum OfflineMapDownloadResult: Sendable, Equatable {
    case completed
    case paused
    case failed(String)
}

enum OfflineMapDownloader {
    static func run(
        plan: OfflineMapDownloadPlan,
        tileStore: OfflineTileStore,
        session: URLSession? = nil,
        tileTemplate: String? = nil,
        retryPolicy: OfflineMapRetryPolicy? = nil,
        sleep: @escaping @Sendable (UInt64) async -> Void = { delay in
            try? await Task.sleep(nanoseconds: delay)
        },
        onProgress: @escaping @Sendable (OfflineMapDownloadProgress) async -> Void
    ) async -> OfflineMapDownloadResult {
        let session = session ?? OfflineMapNetworkSession.interactive
        let retryPolicy = retryPolicy ?? .standard
        let total = plan.region.tileCount
        guard total > 0 else { return .failed(NSLocalizedString("Tile count is zero.", comment: "")) }
        let persistedMissing = max(
            0,
            min(plan.region.missingTiles, max(0, total - plan.initialDownloaded))
        )
        var downloaded = plan.initialDownloaded
        var lastReportedDownloaded = plan.initialDownloaded
        var missing = persistedMissing
        var lastReportedMissing = persistedMissing
        var lastReportedAt = ProcessInfo.processInfo.systemUptime
        var currentOrdinal = 0
        var lastCompletedOrdinal: Int?
        let normalizedTemplate = tileTemplate?.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedTemplate: String?
        if let normalizedTemplate, !normalizedTemplate.isEmpty {
            resolvedTemplate = normalizedTemplate
        } else {
            resolvedTemplate = nil
        }
        guard resolvedTemplate != nil || OfflineMapConfiguration.mapTilerAPIKey != nil else {
            return .failed(NSLocalizedString("Offline map provider key is not configured.", comment: ""))
        }

        let bounds = plan.region.bounds
        let minZoom = plan.region.minZoom
        let maxZoom = plan.region.maxZoom
        let template = resolvedTemplate ?? OfflineMapConfiguration.tileTemplate

        func reportProgressIfNeeded(force: Bool = false) async {
            guard downloaded != lastReportedDownloaded || missing != lastReportedMissing else { return }

            let now = ProcessInfo.processInfo.systemUptime
            let processedDelta = (downloaded + missing) - (lastReportedDownloaded + lastReportedMissing)
            let shouldReport = force
                || processedDelta >= OfflineMapConfiguration.progressPublishStride
                || now - lastReportedAt >= OfflineMapConfiguration.progressPublishInterval

            guard shouldReport else { return }

            lastReportedDownloaded = downloaded
            lastReportedMissing = missing
            lastReportedAt = now
            await onProgress(
                OfflineMapDownloadProgress(
                    downloadedTiles: downloaded,
                    missingTiles: missing,
                    lastCompletedOrdinal: lastCompletedOrdinal
                )
            )
        }

        for zoom in minZoom...maxZoom {
            let xRanges = TileCalculator.tileXRanges(bounds: bounds, zoom: zoom)
            let yRange = TileCalculator.tileYRange(bounds: bounds, zoom: zoom)
            for xRange in xRanges {
                for x in xRange {
                    for y in yRange {
                        currentOrdinal += 1
                        if let resumeOrdinal = plan.resumeOrdinal,
                           currentOrdinal <= resumeOrdinal {
                            continue
                        }
                        if Task.isCancelled { return .paused }
                        if tileStore.tileExists(regionId: plan.region.id, z: zoom, x: x, y: y) {
                            downloaded += 1
                            lastCompletedOrdinal = currentOrdinal
                            await reportProgressIfNeeded(force: downloaded + missing == total)
                            continue
                        }
                        guard let url = tileURL(template: template, z: zoom, x: x, y: y) else {
                            return .failed(NSLocalizedString("Invalid tile URL.", comment: ""))
                        }

                        switch await fetchTile(
                            url: url,
                            session: session,
                            retryPolicy: retryPolicy,
                            sleep: sleep
                        ) {
                        case .success(let data):
                            do {
                                try tileStore.writeTileData(data, regionId: plan.region.id, z: zoom, x: x, y: y)
                                downloaded += 1
                                lastCompletedOrdinal = currentOrdinal
                                await reportProgressIfNeeded(force: downloaded + missing == total)
                            } catch {
                                if Task.isCancelled { return .paused }
                                return .failed(error.localizedDescription)
                            }
                        case .missing:
                            missing += 1
                            lastCompletedOrdinal = currentOrdinal
                            await reportProgressIfNeeded(force: downloaded + missing == total)
                        case .cancelled:
                            return .paused
                        case .fatal(let message):
                            return .failed(message)
                        }
                    }
                }
            }
        }
        await reportProgressIfNeeded(force: true)
        return .completed
    }

    private static func fetchTile(
        url: URL,
        session: URLSession,
        retryPolicy: OfflineMapRetryPolicy,
        sleep: @escaping @Sendable (UInt64) async -> Void
    ) async -> OfflineMapTileFetchOutcome {
        var attempt = 1
        while attempt <= retryPolicy.maxAttempts {
            if Task.isCancelled {
                return .cancelled
            }

            do {
                let (data, response) = try await session.data(from: url)
                if let http = response as? HTTPURLResponse {
                    switch http.statusCode {
                    case 200...299:
                        if data.isEmpty {
                            return .fatal(NSLocalizedString("Empty tile response.", comment: ""))
                        }
                        return .success(data)
                    case 404:
                        return .missing
                    default:
                        if retryPolicy.retryableHTTPStatusCodes.contains(http.statusCode),
                           attempt < retryPolicy.maxAttempts {
                            let delay = retryPolicy.delaySeconds(forAttempt: attempt)
                            await sleep(UInt64(delay * 1_000_000_000))
                            attempt += 1
                            continue
                        }
                        return .fatal("HTTP \(http.statusCode)")
                    }
                }
                if !data.isEmpty {
                    return .success(data)
                }
                return .fatal(NSLocalizedString("Invalid tile response.", comment: ""))
            } catch {
                if Task.isCancelled {
                    return .cancelled
                }
                if let urlError = error as? URLError,
                   retryPolicy.retryableURLErrorCodes.contains(urlError.code),
                   attempt < retryPolicy.maxAttempts {
                    let delay = retryPolicy.delaySeconds(forAttempt: attempt)
                    await sleep(UInt64(delay * 1_000_000_000))
                    attempt += 1
                    continue
                }
                if attempt < retryPolicy.maxAttempts {
                    let nsError = error as NSError
                    if nsError.domain == NSURLErrorDomain {
                        let code = URLError.Code(rawValue: nsError.code)
                        if retryPolicy.retryableURLErrorCodes.contains(code) {
                            let delay = retryPolicy.delaySeconds(forAttempt: attempt)
                            await sleep(UInt64(delay * 1_000_000_000))
                            attempt += 1
                            continue
                        }
                    }
                }
                return .fatal(error.localizedDescription)
            }
        }
        return .fatal(NSLocalizedString("Tile request failed after retrying.", comment: ""))
    }

    private static func tileURL(template: String, z: Int, x: Int, y: Int) -> URL? {
        let urlString = template
            .replacingOccurrences(of: "{z}", with: "\(z)")
            .replacingOccurrences(of: "{x}", with: "\(x)")
            .replacingOccurrences(of: "{y}", with: "\(y)")
        return URL(string: urlString)
    }
}

final class OfflineRegionStore {
    private let fileManager = FileManager.default
    private let fileURL: URL
    private let queue = DispatchQueue(label: "offline.region.store")

    init() {
        let base = fileManager.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        fileURL = base.appendingPathComponent("offline_regions.json")
        ensureDirectory(base)
    }

    func load() -> [OfflineMapRegion] {
        queue.sync {
            guard let payload = try? LocalEncryptedFileStore.read(from: fileURL) else { return [] }
            if !payload.wasEncrypted {
                try? LocalEncryptedFileStore.write(payload.data, to: fileURL)
            }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return (try? decoder.decode([OfflineMapRegion].self, from: payload.data)) ?? []
        }
    }

    func save(_ regions: [OfflineMapRegion]) {
        queue.sync {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(regions) else { return }
            try? LocalEncryptedFileStore.write(data, to: fileURL)
        }
    }

    private func ensureDirectory(_ url: URL) {
        guard !fileManager.fileExists(atPath: url.path) else { return }
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ]
        try? fileManager.createDirectory(at: url, withIntermediateDirectories: true, attributes: attributes)
    }
}
