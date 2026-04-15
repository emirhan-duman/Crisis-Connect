//
//  OfflineMapBackgroundManager.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import Foundation
import BackgroundTasks

final class OfflineMapBackgroundManager {
    static let shared = OfflineMapBackgroundManager()
    static let taskIdentifier = "com.auralis.crisisconnect.offline-map-download"

    private let regionStore = OfflineRegionStore()
    private let tileStore = OfflineTileStore()
    private var hasRegistered = false

    private init() {}

    func register() {
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
            NSLog("Failed to register offline map background task.")
        }
    }

    func scheduleIfNeeded() {
        guard hasBackgroundDownloadWork else {
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
            return
        }

        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15)

        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            NSLog("Failed to schedule offline map background task: %@", String(describing: error))
        }
    }

    private var hasBackgroundDownloadWork: Bool {
        regionStore.load().contains { region in
            region.status == .downloading &&
                region.tileCount > 0 &&
                (region.downloadedTiles + region.missingTiles) < region.tileCount
        }
    }

    private func handle(_ task: BGProcessingTask) {
        scheduleIfNeeded()

        let worker = Task(priority: .background) { [weak self] in
            await self?.processPendingDownloads() ?? false
        }
        task.expirationHandler = {
            worker.cancel()
        }

        Task {
            let success = await worker.value
            task.setTaskCompleted(success: success)
        }
    }

    private func processPendingDownloads() async -> Bool {
        var processedAny = false

        while !Task.isCancelled, let region = nextDownloadingRegion() {
            processedAny = true
            let initialDownloaded = max(0, min(region.downloadedTiles, max(0, region.tileCount - region.missingTiles)))
            let plan = OfflineMapDownloadPlan(
                region: region,
                initialDownloaded: initialDownloaded,
                resumeOrdinal: region.downloadCursorOrdinal
            )

            let result = await OfflineMapDownloader.run(
                plan: plan,
                tileStore: tileStore,
                session: OfflineMapNetworkSession.backgroundContinuation
            ) { [weak self] progress in
                await self?.applyProgress(progress, toRegionId: region.id)
            }
            await applyResult(result, toRegionId: region.id)
        }

        scheduleIfNeeded()
        return !Task.isCancelled && (processedAny || !hasBackgroundDownloadWork)
    }

    private func nextDownloadingRegion() -> OfflineMapRegion? {
        regionStore.load()
            .sorted { lhs, rhs in
                if lhs.createdAt == rhs.createdAt {
                    return lhs.id.uuidString < rhs.id.uuidString
                }
                return lhs.createdAt < rhs.createdAt
            }
            .first { region in
                region.status == .downloading &&
                    region.tileCount > 0 &&
                    (region.downloadedTiles + region.missingTiles) < region.tileCount
            }
    }

    private func applyProgress(_ progress: OfflineMapDownloadProgress, toRegionId regionId: UUID) async {
        updateRegion(regionId) { region in
            region.downloadedTiles = progress.downloadedTiles
            region.missingTiles = progress.missingTiles
            region.downloadCursorOrdinal = progress.lastCompletedOrdinal
            region.status = .downloading
            region.lastError = nil
        }
    }

    private func applyResult(_ result: OfflineMapDownloadResult, toRegionId regionId: UUID) async {
        updateRegion(regionId) { region in
            switch result {
            case .completed:
                region.status = .complete
                region.lastError = nil
                region.downloadCursorOrdinal = nil
            case .paused:
                // Background execution expiration should resume on the next scheduler window.
                region.status = .downloading
            case .failed(let error):
                region.status = .failed
                region.lastError = error
            }
        }
    }

    private func updateRegion(_ regionId: UUID, _ mutate: (inout OfflineMapRegion) -> Void) {
        var regions = regionStore.load()
        guard let index = regions.firstIndex(where: { $0.id == regionId }) else { return }
        var region = regions[index]
        mutate(&region)
        regions[index] = region
        regionStore.save(regions)
    }
}
