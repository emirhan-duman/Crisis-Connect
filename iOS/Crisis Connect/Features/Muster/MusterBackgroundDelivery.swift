import BackgroundTasks
import Foundation

enum MusterBackgroundDelivery {
    static let identifier = "com.auralis.crisisconnect.muster-send"

    @discardableResult
    static func register(drain: @escaping @Sendable () async -> Bool, reschedule: @escaping @Sendable () -> Void) -> Bool {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: nil) { task in
            let work = Task { await drain() }
            task.expirationHandler = { work.cancel() }
            Task {
                task.setTaskCompleted(success: await work.value)
                reschedule()
            }
        }
    }

    static func request(nextAt: TimeInterval, now: Date = Date()) -> BGProcessingTaskRequest {
        let request = BGProcessingTaskRequest(identifier: identifier)
        request.requiresNetworkConnectivity = true
        request.earliestBeginDate = max(Date(timeIntervalSince1970: nextAt), now.addingTimeInterval(30))
        return request
    }

    static func submit(nextAt: TimeInterval) throws {
        cancel()
        try BGTaskScheduler.shared.submit(request(nextAt: nextAt))
    }

    static func cancel() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: identifier)
    }
}
