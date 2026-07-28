//
//  CertificateRenewalBackgroundManager.swift
//  Crisis Connect
//
//  Background renewal of the device-bound rescue role certificate, mirroring
//  Android's CertificateRenewalWorker. Certificates live for 72h; this task is
//  scheduled with a network constraint on a ~6h cadence and silently
//  re-provisions once less than 24h of validity remains — so an authorized
//  device that sees the internet at least once every couple of days never lets
//  its certificate lapse, even if the app is only ever in the background.
//
//  Deliberately quiet: no banners or notifications. Having a stored
//  certificate is the natural role gate (only admin/fieldteam devices ever
//  obtain one), so for everyone else scheduleIfNeeded() is a cheap no-op that
//  never reaches the network.
//

import BackgroundTasks
import Foundation

final class CertificateRenewalBackgroundManager {
    static let shared = CertificateRenewalBackgroundManager()
    static let taskIdentifier = "com.auralis.crisisconnect.cert-renewal"

    /// Android runs its periodic check every 6h — 3-4 renewal opportunities
    /// inside the 24h lead window while staying battery-trivial. iOS treats
    /// this as the earliest begin date; BGTaskScheduler decides the rest.
    private static let renewalCheckInterval: TimeInterval = 6 * 60 * 60

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
            NSLog("Failed to register certificate renewal background task.")
        }
    }

    func scheduleIfNeeded() {
        guard !PlatformRuntime.isRunningTests else { return }

        Task(priority: .utility) {
            // allowExpired keeps a just-lapsed certificate (still inside the
            // offline grace window) on the renewal path instead of stranding it.
            let hasCertificate = (try? SecurityRepository.shared
                .getStoredCertificateSync(allowExpired: true)) != nil
            guard hasCertificate else {
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
                return
            }

            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
            let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
            request.requiresNetworkConnectivity = true
            request.requiresExternalPower = false
            request.earliestBeginDate = Date(timeIntervalSinceNow: Self.renewalCheckInterval)

            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                NSLog("Failed to schedule certificate renewal background task: %@", String(describing: error))
            }
        }
    }

    private func handle(_ task: BGProcessingTask) {
        scheduleIfNeeded()

        let worker = Task(priority: .background) {
            await SecurityRepository.shared.renewCertificateIfExpiringSoon()
        }
        task.expirationHandler = {
            worker.cancel()
        }

        Task {
            await worker.value
            // A failed renewal keeps the still-valid cached certificate; the
            // next scheduled run (and the foreground hook) are the retry path.
            task.setTaskCompleted(success: true)
            Self.shared.scheduleIfNeeded()
        }
    }
}
