//
//  CrisisSentinelOnlineClient.swift
//  Crisis Connect
//
//  Client for the web dashboard's Crisis Sentinel chat API — iOS port of Android's
//  `CrisisSentinelOnlineClient`: `POST /api/chat/stream` (SSE) and `GET /api/chat/providers`.
//  Field-team/admin only: callers gate on the rescue-certificate role before invoking; the server
//  enforces the same gate from the Firestore user document (role + agency panel) and applies the
//  panel's AI quota.
//
//  The base URL is the dashboard's Cloud Run origin, NOT the hosting domain — Firebase Hosting
//  buffers SSE, so the stream must hit Cloud Run directly (same origin the web client hard-codes).
//

import Foundation
import FirebaseAuth

struct CrisisSentinelOnlineMessage {
    static let senderUser = "user"
    static let senderBot = "bot"
    let sender: String
    let text: String
}

struct CrisisSentinelMapPoint: Equatable, Codable {
    let lat: Double
    let lng: Double
    let label: String
    var details: String? = nil
    var type: String? = nil
}

struct CrisisSentinelOnlineModelInfo: Identifiable, Equatable {
    let id: String
    let label: String
    var contextWindow: Int64? = nil
}

struct CrisisSentinelOnlineProvider: Identifiable, Equatable {
    let id: String
    let label: String
    var defaultModel: String? = nil
    var source: String = ""
    var models: [CrisisSentinelOnlineModelInfo] = []
}

struct CrisisSentinelOnlineModelChoice: Equatable, Codable {
    let providerId: String
    let modelId: String
}

struct CrisisSentinelOnlineStreamResult {
    let text: String
    var modelName: String? = nil
    var cardJson: String? = nil
    var mapPoints: [CrisisSentinelMapPoint] = []
    var unsupportedTools: [String] = []
    var groundingMetadataJson: String? = nil
}

struct CrisisSentinelOnlineError: Error {
    enum Kind {
        /// No signed-in (non-anonymous) Firebase user, or the server rejected the token.
        case unauthorized
        /// The account lacks dashboard access (role/panel) server-side.
        case forbidden
        /// The agency panel's AI quota is exhausted.
        case quota
        /// Transport-level failure (unreachable, reset, timeout between events).
        case network
        /// The server answered but with an unexpected status or payload.
        case server
        /// The panel has no AI provider configured (server ended with needsApiKey).
        case notConfigured
    }

    let kind: Kind
    var httpStatus: Int? = nil
    let message: String
}

final class CrisisSentinelOnlineClient {
    // Same margins as Android: keep requests inside the server's validation limits
    // (50 messages / 20k chars per message).
    private static let maxMessages = 30
    private static let maxMessageChars = 20_000
    private static let toolShowLocationOnMap = "showLocationOnMap"

    /// The dashboard's Cloud Run origin. Override per-build via Info.plist key
    /// `CrisisSentinelOnlineBaseURL` when staging; the default is production.
    static var baseURL: URL? = {
        if let raw = Bundle.main.object(forInfoDictionaryKey: "CrisisSentinelOnlineBaseURL") as? String,
           let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
           url.scheme == "https" {
            return url
        }
        return URL(string: "https://ssrcrisisconnect1-jxxgznalnq-uc.a.run.app")
    }()

    private let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 75 // watchdog between SSE frames (server pings early)
        config.timeoutIntervalForResource = 300
        return URLSession(configuration: config)
    }()

    /// Sends the conversation and streams the assistant reply. `onDelta` receives the FULL
    /// accumulated text after each server delta (hop to the main actor before touching UI).
    /// `modelChoice` pins a provider/model; nil lets the server pick its default.
    func streamChat(
        messages: [CrisisSentinelOnlineMessage],
        modelChoice: CrisisSentinelOnlineModelChoice? = nil,
        onDelta: @escaping (String) -> Void
    ) async throws -> CrisisSentinelOnlineStreamResult {
        try await withIdToken { token in
            try await self.executeStream(token: token, messages: messages, modelChoice: modelChoice, onDelta: onDelta)
        }
    }

    /// Providers/models the signed-in user's panel can use. Server omits unconfigured ones.
    func fetchProviders() async throws -> [CrisisSentinelOnlineProvider] {
        try await withIdToken { token in
            try await self.executeFetchProviders(token: token)
        }
    }

    // MARK: - Auth

    /// Runs `block` with a Firebase ID token; on an Unauthorized failure retries exactly once with
    /// a force-refreshed token (covers the cached-token revocation window).
    private func withIdToken<T>(_ block: (String) async throws -> T) async throws -> T {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else {
            throw CrisisSentinelOnlineError(kind: .unauthorized, message: "No signed-in Firebase user")
        }
        let token = try await fetchIdToken(user: user, forceRefresh: false)
        do {
            return try await block(token)
        } catch let error as CrisisSentinelOnlineError where error.kind == .unauthorized {
            let fresh = try await fetchIdToken(user: user, forceRefresh: true)
            return try await block(fresh)
        }
    }

    private func fetchIdToken(user: User, forceRefresh: Bool) async throws -> String {
        let token = try? await user.getIDTokenResult(forcingRefresh: forceRefresh).token
        guard let token = token?.trimmingCharacters(in: .whitespacesAndNewlines), !token.isEmpty else {
            throw CrisisSentinelOnlineError(kind: .unauthorized, message: "Could not obtain Firebase ID token")
        }
        return token
    }

    private func endpoint(_ path: String) throws -> URL {
        guard let base = Self.baseURL else {
            throw CrisisSentinelOnlineError(kind: .notConfigured, message: "Online chat base URL is not configured")
        }
        return base.appendingPathComponent(path)
    }

    // MARK: - Providers

    private func executeFetchProviders(token: String) async throws -> [CrisisSentinelOnlineProvider] {
        var request = URLRequest(url: try endpoint("api/chat/providers"))
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")

        let (data, response): (Data, URLResponse)
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw CrisisSentinelOnlineError(kind: .network, message: error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            throw Self.mapStatus(status, detail: Self.errorDetail(from: data))
        }
        return Self.parseProviders(data)
    }

    // MARK: - Stream

    private func executeStream(
        token: String,
        messages: [CrisisSentinelOnlineMessage],
        modelChoice: CrisisSentinelOnlineModelChoice?,
        onDelta: @escaping (String) -> Void
    ) async throws -> CrisisSentinelOnlineStreamResult {
        var body: [String: Any] = [
            "messages": messages.suffix(Self.maxMessages).map {
                ["sender": $0.sender, "text": String($0.text.prefix(Self.maxMessageChars))]
            },
            "context": [String: Any](),
        ]
        if let modelChoice {
            body["providerId"] = modelChoice.providerId
            body["model"] = modelChoice.modelId
        }

        var request = URLRequest(url: try endpoint("api/chat/stream"))
        request.httpMethod = "POST"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        let (bytes, response): (URLSession.AsyncBytes, URLResponse)
        do {
            (bytes, response) = try await session.bytes(for: request)
        } catch {
            throw CrisisSentinelOnlineError(kind: .network, message: error.localizedDescription)
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            var errorBody = Data()
            for try await byte in bytes.prefix(64 * 1024) { errorBody.append(byte) }
            throw Self.mapStatus(status, detail: Self.errorDetail(from: errorBody))
        }

        // SSE state machine (pure, so it's unit-testable) — identical event handling to Android.
        var parser = SSEEventParser()
        do {
            for try await line in bytes.lines {
                switch try parser.consume(line: line) {
                case .none:
                    break
                case .delta(let accumulated):
                    onDelta(accumulated)
                case .done(let result):
                    return result
                }
            }
        } catch let error as CrisisSentinelOnlineError {
            throw error
        } catch {
            // Mid-stream transport failure: keep a mostly-complete reply rather than discarding it.
            if let partial = parser.partialResult() { return partial }
            throw CrisisSentinelOnlineError(kind: .network, message: error.localizedDescription)
        }
        // EOF without a `done` event.
        if let partial = parser.partialResult() { return partial }
        throw CrisisSentinelOnlineError(kind: .network, message: "Stream ended before any reply")
    }

    // MARK: - Payload parsing (byte-parity with Android)

    static func finishFromDone(
        data: String,
        streamedText: String,
        providerIdFromMetadata: String?
    ) throws -> CrisisSentinelOnlineStreamResult {
        let payload = json(data)?["data"] as? [String: Any]
        if payload?["needsApiKey"] as? Bool == true {
            throw CrisisSentinelOnlineError(kind: .notConfigured, message: "Panel has no AI provider configured")
        }

        let candidate = (payload?["candidates"] as? [[String: Any]])?.first
        let parts = (candidate?["content"] as? [String: Any])?["parts"] as? [[String: Any]] ?? []

        var partText = ""
        var mapPoints: [CrisisSentinelMapPoint] = []
        var unsupportedTools: [String] = []
        for part in parts {
            if let text = part["text"] as? String, !text.trimmingCharacters(in: .whitespaces).isEmpty {
                partText += text
            }
            guard let functionCall = part["functionCall"] as? [String: Any] else { continue }
            let toolName = functionCall["name"] as? String ?? ""
            if toolName == toolShowLocationOnMap {
                mapPoints += parseMapPoints(functionCall["args"] as? [String: Any])
            } else if !toolName.isEmpty {
                unsupportedTools.append(toolName)
            }
        }

        let text = streamedText.isEmpty
            ? partText.trimmingCharacters(in: .whitespacesAndNewlines)
            : streamedText
        var cardJson: String?
        if let card = payload?["_card"] as? [String: Any],
           let cardData = try? JSONSerialization.data(withJSONObject: card) {
            cardJson = String(data: cardData, encoding: .utf8)
        }
        if text.isEmpty && cardJson == nil && mapPoints.isEmpty && unsupportedTools.isEmpty {
            throw CrisisSentinelOnlineError(kind: .server, message: "Stream finished without reply content")
        }

        var groundingJson: String?
        if let grounding = candidate?["groundingMetadata"] as? [String: Any],
           let groundingData = try? JSONSerialization.data(withJSONObject: grounding) {
            groundingJson = String(data: groundingData, encoding: .utf8)
        }
        let model = (payload?["model"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        return CrisisSentinelOnlineStreamResult(
            text: text,
            modelName: model ?? providerIdFromMetadata,
            cardJson: cardJson,
            mapPoints: mapPoints,
            unsupportedTools: unsupportedTools,
            groundingMetadataJson: groundingJson
        )
    }

    private static func parseMapPoints(_ args: [String: Any]?) -> [CrisisSentinelMapPoint] {
        guard let points = args?["points"] as? [[String: Any]] else { return [] }
        return points.compactMap { point in
            guard let lat = (point["lat"] as? NSNumber)?.doubleValue,
                  let lng = (point["lng"] as? NSNumber)?.doubleValue else { return nil }
            return CrisisSentinelMapPoint(
                lat: lat,
                lng: lng,
                label: point["label"] as? String ?? "",
                details: (point["details"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                type: (point["type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            )
        }
    }

    static func parseProviders(_ data: Data) -> [CrisisSentinelOnlineProvider] {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let providers = root["providers"] as? [[String: Any]] else {
            return []
        }
        return providers.compactMap { provider in
            guard let id = (provider["id"] as? String), !id.isEmpty else { return nil }
            let models = (provider["models"] as? [[String: Any]] ?? []).compactMap { model -> CrisisSentinelOnlineModelInfo? in
                guard let modelId = model["id"] as? String, !modelId.isEmpty else { return nil }
                let label = (model["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? modelId
                let context = (model["contextWindow"] as? NSNumber)?.int64Value
                return CrisisSentinelOnlineModelInfo(
                    id: modelId, label: label, contextWindow: (context ?? 0) > 0 ? context : nil
                )
            }
            return CrisisSentinelOnlineProvider(
                id: id,
                label: (provider["label"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? id,
                defaultModel: (provider["defaultModel"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                source: provider["source"] as? String ?? "",
                models: models
            )
        }
    }

    static func json(_ raw: String) -> [String: Any]? {
        guard let data = raw.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    private static func errorDetail(from data: Data) -> String {
        let detail = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["error"] as? String
        return detail?.isEmpty == false ? detail! : ""
    }

    static func mapStatus(_ status: Int, detail: String) -> CrisisSentinelOnlineError {
        let kind: CrisisSentinelOnlineError.Kind
        switch status {
        case 401: kind = .unauthorized
        case 403: kind = .forbidden
        case 429: kind = .quota
        default: kind = .server
        }
        return CrisisSentinelOnlineError(
            kind: kind,
            httpStatus: status,
            message: detail.isEmpty ? "HTTP \(status)" : detail
        )
    }
}

/// Pure SSE event state machine — feed it lines (from bytes.lines or a test), and it accumulates
/// text deltas, resolves the metadata provider id, and finalizes on the `done` event (or throws on
/// `error`). Extracted from the stream loop so the parsing is unit-testable without a network.
struct SSEEventParser {
    enum Outcome {
        case none
        /// The full accumulated reply text after a delta.
        case delta(String)
        case done(CrisisSentinelOnlineStreamResult)
    }

    private var accumulated = ""
    private var providerIdFromMetadata: String?
    private var eventName = ""
    private var dataLines: [String] = []

    mutating func consume(line: String) throws -> Outcome {
        if line.isEmpty {
            let data = dataLines.joined(separator: "\n")
            dataLines.removeAll()
            let name = eventName
            eventName = ""
            switch name {
            case "metadata":
                if let json = CrisisSentinelOnlineClient.json(data),
                   let provider = json["_provider"] as? [String: Any],
                   let id = (provider["id"] as? String)?.trimmingCharacters(in: .whitespaces),
                   !id.isEmpty {
                    providerIdFromMetadata = id
                }
                return .none
            case "text_delta":
                if let delta = CrisisSentinelOnlineClient.json(data)?["delta"] as? String, !delta.isEmpty {
                    accumulated += delta
                    return .delta(accumulated)
                }
                return .none
            case "done":
                return .done(try CrisisSentinelOnlineClient.finishFromDone(
                    data: data,
                    streamedText: accumulated.trimmingCharacters(in: .whitespacesAndNewlines),
                    providerIdFromMetadata: providerIdFromMetadata
                ))
            case "error":
                let json = CrisisSentinelOnlineClient.json(data)
                let status = (json?["status"] as? NSNumber)?.intValue ?? 0
                let message = (json?["error"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Stream error"
                throw CrisisSentinelOnlineClient.mapStatus(status, detail: message)
            default:
                return .none // "status" / "tool_call" / "tool_result" are informational.
            }
        } else if line.hasPrefix("event:") {
            eventName = String(line.dropFirst("event:".count)).trimmingCharacters(in: .whitespaces)
        } else if line.hasPrefix("data:") {
            dataLines.append(String(line.dropFirst("data:".count)).trimmingCharacters(in: .whitespaces))
        }
        return .none
    }

    /// On EOF or a mid-stream transport failure, keep a mostly-complete reply rather than
    /// discarding it; nil when nothing streamed.
    func partialResult() -> CrisisSentinelOnlineStreamResult? {
        let text = accumulated.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : CrisisSentinelOnlineStreamResult(text: text)
    }
}
