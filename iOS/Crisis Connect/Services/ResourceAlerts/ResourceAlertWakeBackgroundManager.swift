import BackgroundTasks
import Foundation

final class ResourceAlertWakeBackgroundManager {
    static let shared = ResourceAlertWakeBackgroundManager()
    static let taskIdentifier = "com.auralis.crisisconnect.resource-alert-wake-ack"

    private var hasRegistered = false

    private init() {}

    func register() {
        guard !PlatformRuntime.isRunningTests, !hasRegistered else { return }
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
            NSLog("Failed to register resource-alert ACK background task")
        }
    }

    func scheduleIfNeeded() {
        guard !PlatformRuntime.isRunningTests else { return }
        Task(priority: .utility) {
            guard let next = await ResourceAlertWakeClient.shared.nextPendingAttemptDate() else {
                BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
                return
            }
            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.taskIdentifier)
            let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
            request.requiresNetworkConnectivity = true
            request.requiresExternalPower = false
            request.earliestBeginDate = max(next, Date(timeIntervalSinceNow: 60))
            do {
                try BGTaskScheduler.shared.submit(request)
            } catch {
                NSLog("Failed to schedule resource-alert ACK background task")
            }
        }
    }

    private func handle(_ task: BGProcessingTask) {
        let worker = Task(priority: .background) {
            await ResourceAlertWakeClient.shared.drainPending(maximumItems: 16)
        }
        task.expirationHandler = { worker.cancel() }
        Task {
            let empty = await worker.value
            task.setTaskCompleted(success: empty)
            Self.shared.scheduleIfNeeded()
        }
    }
}
