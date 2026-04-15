//
//  CrisisLinkBackgroundRefreshManager.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import BackgroundTasks
import Foundation

final class CrisisLinkBackgroundRefreshManager {
    static let shared = CrisisLinkBackgroundRefreshManager()
    static let taskIdentifier = "com.auralis.crisisconnect.crisis-link-refresh"

    private var hasRegistered = false

    private init() {}

    func register() {
        guard !PlatformRuntime.isRunningTests else { return }
        guard !hasRegistered else { return }
        hasRegistered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: nil
        ) { task in
            guard let refreshTask = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            Self.shared.handle(refreshTask)
        }
        if !hasRegistered {
            NSLog("Failed to register Crisis Link background refresh task.")
        }
    }

    func scheduleIfNeeded() {
        guard !PlatformRuntime.isRunningTests else { return }

        Task { @MainActor in
            let coordinator = RescueLiveLocationCoordinator.shared
            guard coordinator.shouldScheduleBackgroundRefresh else {
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
                return
            }

            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
            let request = BGAppRefreshTaskRequest(identifier: Self.taskIdentifier)
            request.earliestBeginDate = Date(timeIntervalSinceNow: coordinator.backgroundRefreshEarliestDelay)

            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                NSLog("Failed to schedule Crisis Link background refresh task: %@", String(describing: error))
            }
        }
    }

    private func handle(_ task: BGAppRefreshTask) {
        scheduleIfNeeded()

        let worker = Task { @MainActor in
            await RescueLiveLocationCoordinator.shared.handleBackgroundRefresh()
        }
        task.expirationHandler = {
            worker.cancel()
        }

        Task {
            let success = await worker.value
            task.setTaskCompleted(success: success)
            Self.shared.scheduleIfNeeded()
        }
    }
}
