import FirebaseAuth
import Foundation

struct ResourceAlertWakePayload: Equatable, Sendable {
    let panelId: String
    let attemptId: String
    let receiptNonce: String

    init(panelId: String, attemptId: String, receiptNonce: String) {
        self.panelId = panelId
        self.attemptId = attemptId
        self.receiptNonce = receiptNonce
    }

    init?(userInfo: [AnyHashable: Any]) {
        func string(_ key: String) -> String? {
            (userInfo[key] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard string("type") == "resource_alert_wake",
              let panelId = string("panelId"),
              let attemptId = string("attemptId"),
              let receiptNonce = string("receiptNonce"),
              Self.matches(panelId, pattern: "[a-z0-9._-]{1,96}"),
              Self.matches(attemptId, pattern: "[A-Za-z0-9:_-]{1,128}"),
              Self.matches(receiptNonce, pattern: "[A-Za-z0-9_-]{16,128}") else {
            return nil
        }
        self.panelId = panelId
        self.attemptId = attemptId
        self.receiptNonce = receiptNonce
    }

    func makeRequest(endpoint: URL, idToken: String) throws -> URLRequest {
        let body: [String: String] = [
            "panelId": panelId,
            "action": "ackWake",
            "attemptId": attemptId,
            "receiptNonce": receiptNonce,
            "source": "native",
        ]
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        request.timeoutInterval = 10
        return request
    }

    private static func matches(_ value: String, pattern: String) -> Bool {
        value.range(of: "^(?:\(pattern))$", options: .regularExpression) != nil
    }
}

enum ResourceAlertWakeHandlingResult: Equatable, Sendable {
    case notResourceWake
    case acknowledged
    case queued
    case failed
}

extension Notification.Name {
    static let resourceAlertInboxRefreshRequested = Notification.Name(
        "crisisconnect.resourceAlertInboxRefreshRequested"
    )
}

final class ResourceAlertWakeClient: @unchecked Sendable {
    static let shared = ResourceAlertWakeClient()

    private let session: URLSession
    private let queue: ResourceAlertWakeQueue

    init(session: URLSession = .shared, queue: ResourceAlertWakeQueue = .shared) {
        self.session = session
        self.queue = queue
    }

    func handleRemotePush(userInfo: [AnyHashable: Any]) async -> ResourceAlertWakeHandlingResult {
        guard let payload = ResourceAlertWakePayload(userInfo: userInfo) else {
            return .notResourceWake
        }
        guard let user = Auth.auth().currentUser,
              !user.isAnonymous else {
            return .failed
        }
        do {
            let key = try await queue.enqueue(payload: payload, recipientUid: user.uid)
            let result = await deliverQueued(key: key, user: user)
            ResourceAlertWakeBackgroundManager.shared.scheduleIfNeeded()
            return result ? .acknowledged : .queued
        } catch {
            NSLog("ResourceAlertWakeClient: could not persist ACK")
            return .failed
        }
    }

    @discardableResult
    func drainPending(maximumItems: Int = 8) async -> Bool {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return false }
        for _ in 0..<max(1, min(maximumItems, 32)) {
            let pending: PendingResourceAlertWake?
            do {
                pending = try await queue.claim(recipientUid: user.uid)
            } catch {
                NSLog("ResourceAlertWakeClient: could not read pending ACK queue")
                return false
            }
            guard let pending else { break }
            _ = await deliver(claimed: pending, user: user)
        }
        return (try? await queue.hasPending(recipientUid: user.uid)) == false
    }

    func nextPendingAttemptDate() async -> Date? {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return nil }
        return try? await queue.nextAttemptDate(recipientUid: user.uid)
    }

    private func deliverQueued(key: String, user: User) async -> Bool {
        guard let claimed = try? await queue.claim(key: key, recipientUid: user.uid) else {
            return false
        }
        return await deliver(claimed: claimed, user: user)
    }

    private func deliver(claimed: PendingResourceAlertWake, user: User) async -> Bool {
        guard let endpoint = Self.endpointURL() else { return false }
        let status: Int
        do {
            let token = try await user.getIDToken()
            var response = try await send(payload: claimed.payload, endpoint: endpoint, idToken: token)
            if response == 401 {
                let refreshed = try await user.getIDToken(forcingRefresh: true)
                response = try await send(payload: claimed.payload, endpoint: endpoint, idToken: refreshed)
            }
            status = response
        } catch {
            NSLog("ResourceAlertWakeClient: ACK transport failed")
            return false
        }
        if (200...299).contains(status) {
            try? await queue.complete(key: claimed.key, recipientUid: user.uid)
            NotificationCenter.default.post(
                name: .resourceAlertInboxRefreshRequested,
                object: nil,
                userInfo: ["panelId": claimed.panelId]
            )
            return true
        }
        if Self.isPermanentFailure(status) {
            // The server has definitively rejected or retired this challenge; retrying it would only
            // leak battery and rate-limit budget. The durable personal inbox remains authoritative.
            try? await queue.complete(key: claimed.key, recipientUid: user.uid)
        }
        NSLog("ResourceAlertWakeClient: ACK rejected status=%d", status)
        return false
    }

    private static func isPermanentFailure(_ status: Int) -> Bool {
        (400...499).contains(status) && ![401, 408, 425, 429].contains(status)
    }

    private func send(payload: ResourceAlertWakePayload, endpoint: URL, idToken: String) async throws -> Int {
        let request = try payload.makeRequest(endpoint: endpoint, idToken: idToken)
        let (_, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { return 503 }
        return http.statusCode
    }

    private static func endpointURL() -> URL? {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let base = raw.isEmpty ? "https://crisisconnect.network" : raw
        guard var components = URLComponents(string: base), components.scheme == "https" else {
            return nil
        }
        components.path = components.path.hasSuffix("/")
            ? components.path + "api/dashboard/resource-alert-inbox"
            : components.path + "/api/dashboard/resource-alert-inbox"
        components.query = nil
        components.fragment = nil
        return components.url
    }
}
