import Combine
import FirebaseAuth
import Foundation
import Network
import SwiftUI
import UserNotifications

struct MusterDestination: Identifiable { let panel: String; let session: String; var id: String { panel + ":" + session } }
@MainActor final class MusterPresentation: ObservableObject {
    static let shared = MusterPresentation()
    @Published var destination: MusterDestination?
}
enum MusterNotificationCenter {
    static func payload(_ info: [AnyHashable: Any]) -> (uid: String, panel: String, session: String, kind: String)? {
        guard info["type"] as? String == "personnel_muster", let kind = info["kind"] as? String, ["live", "drill"].contains(kind),
              let uid = info["recipientUid"] as? String, let current = Auth.auth().currentUser, !current.isAnonymous, current.uid == uid,
              let panel = info["panelId"] as? String, panel.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
              let session = info["sessionId"] as? String, session.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil else { return nil }
        return (uid, panel, session, kind)
    }
    static var category: UNNotificationCategory {
        UNNotificationCategory(identifier: "MUSTER_CHECK", actions: ["safe", "help", "at_muster"].map { answer in
            UNNotificationAction(identifier: "MUSTER_" + answer, title: musterText(answer), options: [.authenticationRequired])
        }, intentIdentifiers: [])
    }
    static var configuredCategory: UNNotificationCategory {
        UNNotificationCategory(identifier: "MUSTER_CONFIGURED", actions: [], intentIdentifiers: [])
    }
    static func refreshLocalizedActions() {
        let center = UNUserNotificationCenter.current()
        center.getNotificationCategories { categories in
            var updated = categories.filter { !["MUSTER_CHECK", "MUSTER_CONFIGURED"].contains($0.identifier) }
            updated.insert(category)
            updated.insert(configuredCategory)
            center.setNotificationCategories(updated)
        }
    }
    static func handle(_ response: UNNotificationResponse, completion: @escaping () -> Void) -> Bool {
        let info = response.notification.request.content.userInfo
        guard info["type"] as? String == "personnel_muster" else { return false }
        guard let payload = payload(info) else { completion(); return true }
        Task {
            if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
                await MainActor.run { MusterPresentation.shared.destination = MusterDestination(panel: payload.panel, session: payload.session) }
            } else if response.actionIdentifier.hasPrefix("MUSTER_") {
                let answer = String(response.actionIdentifier.dropFirst(7))
                do {
                    if info["responseConfigVersion"] as? String == "1" {
                        await MainActor.run { MusterPresentation.shared.destination = MusterDestination(panel: payload.panel, session: payload.session) }
                        completion(); return
                    }
                    try await MusterClient.shared.enqueue(uid: payload.uid, panel: payload.panel, session: payload.session, answer: answer)
                }
                catch { await MainActor.run { MusterPresentation.shared.destination = MusterDestination(panel: payload.panel, session: payload.session) } }
            }
            completion()
        }
        return true
    }
}

final class MusterBackgroundManager: @unchecked Sendable {
    static let shared = MusterBackgroundManager()
    private var monitor: NWPathMonitor?
    // Read and written only by the monitor's serial callback queue.
    private var lastPathStatus: NWPath.Status?
    private var authHandle: AuthStateDidChangeListenerHandle?
    func register() {
        guard !PlatformRuntime.isRunningTests, monitor == nil else { return }
        MusterBackgroundDelivery.register(drain: { await MusterClient.shared.drain() },
                                          reschedule: { Self.shared.scheduleIfNeeded() })
        authHandle = Auth.auth().addStateDidChangeListener { _, _ in
            NotificationCenter.default.post(name: .musterStateChanged, object: nil)
            Task { _ = await MusterClient.shared.drain(); Self.shared.scheduleIfNeeded() }
        }
        let path = NWPathMonitor(); monitor = path
        path.pathUpdateHandler = { [weak self] state in
            guard let self else { return }
            let reconnected = self.lastPathStatus == .unsatisfied && state.status == .satisfied
            self.lastPathStatus = state.status
            guard state.status == .satisfied else { return }
            Task {
                _ = await MusterClient.shared.drain(reconnected: reconnected)
                self.scheduleIfNeeded()
            }
        }
        path.start(queue: DispatchQueue(label: "personnel-muster-connectivity"))
    }
    func scheduleIfNeeded() {
        guard !PlatformRuntime.isRunningTests else { return }
        Task {
            guard let uid = Auth.auth().currentUser?.uid,
                  let entries = try? await MusterQueue.shared.events(uid: uid), let next = entries.filter({ $0.state == "pending" }).map(\.nextAt).min() else { return }
            try? MusterBackgroundDelivery.submit(nextAt: next)
        }
    }
}
