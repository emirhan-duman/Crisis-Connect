//
//  InternetCallSignaling.swift
//  Crisis Connect
//
//  Foundation for internet voice/video calls (item: InternetCallManager port) — the signaling and
//  ICE-credential layers, deliberately free of WebRTC types so they build, test and ship before the
//  WebRTC engine lands:
//   - call signals (SDP/ICE + ring/accept/reject/end JSON) ride the E2E relay as templateCode 200
//     messages, byte-compatible with Android's InternetChatTransport.sendCallSignal
//   - TURN relay credentials are minted per-session by the dashboard's /api/turn-credentials
//     (Firebase ID token auth; the TURN secret never lives in the app), cached ~50 min, with
//     public Google STUN as the always-available fallback
//  The upcoming call engine consumes InternetCallSignalBus + TurnCredentialsProvider.current().
//

import Combine
import FirebaseAuth
import Foundation

// MARK: - ICE server specs (framework-free, mirrors Android's IceServerSpec)

struct IceServerSpec: Equatable {
    let urls: [String]
    var username: String? = nil
    var credential: String? = nil
}

enum TurnCredentials {
    /// Parse the `/api/turn-credentials` JSON body into ICE server specs.
    ///
    /// Body shape: `{ "iceServers": [ { "urls": [..]|"..", "username"?, "credential"? }, .. ] }`.
    /// Tolerates `iceServers` being a single object, `urls` being a string or array, and absent
    /// credentials. Returns an empty list on any malformed/empty input — callers then use STUN-only.
    static func parse(_ json: Data) -> [IceServerSpec] {
        guard let root = try? JSONSerialization.jsonObject(with: json) as? [String: Any] else {
            return []
        }
        let rawServers: [[String: Any]]
        switch root["iceServers"] {
        case let array as [[String: Any]]:
            rawServers = array
        case let single as [String: Any]:
            rawServers = [single]
        default:
            return []
        }
        return rawServers.compactMap { server in
            let urls: [String]
            switch server["urls"] {
            case let url as String:
                urls = url.isEmpty ? [] : [url]
            case let array as [String]:
                urls = array.filter { !$0.isEmpty }
            default:
                urls = []
            }
            guard !urls.isEmpty else { return nil }
            return IceServerSpec(
                urls: urls,
                username: (server["username"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                credential: (server["credential"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            )
        }
    }
}

// MARK: - TURN credential cache

/// Supplies ICE servers for the internet call engine. `current()` never blocks: STUN-only until
/// the first successful refresh. Any authenticated account may mint relay creds — INCLUDING an
/// anonymous (QR-only) user: it still holds a valid Firebase ID token, and requiring a full
/// account would leave QR-added contacts on STUN only, so their calls would fail behind carrier
/// CGNAT exactly when the relay matters most.
final class TurnCredentialsProvider: @unchecked Sendable {
    static let shared = TurnCredentialsProvider()

    private static let cacheTtl: TimeInterval = 50 * 60 // creds minted for 60 min; refresh at 50.
    private static let stunFallback: [IceServerSpec] = [
        IceServerSpec(urls: ["stun:stun.l.google.com:19302"]),
        IceServerSpec(urls: ["stun:stun1.l.google.com:19302"])
    ]

    private let lock = NSLock()
    private var cached: [IceServerSpec] = TurnCredentialsProvider.stunFallback
    private var cachedAt: Date = .distantPast

    private init() {}

    /// Best-known ICE servers right now. Never blocks.
    func current() -> [IceServerSpec] {
        lock.lock(); defer { lock.unlock() }
        return cached
    }

    /// Refresh the cache from the endpoint. Fire-and-forget safe; a no-op while the cache is fresh.
    func refresh(force: Bool = false) async {
        lock.lock()
        let isFresh = cached != Self.stunFallback && Date().timeIntervalSince(cachedAt) < Self.cacheTtl
        lock.unlock()
        if !force && isFresh { return }
        guard let servers = await fetchIceServers(), !servers.isEmpty else { return }
        lock.lock()
        cached = Self.stunFallback + servers
        cachedAt = Date()
        lock.unlock()
    }

    private func fetchIceServers() async -> [IceServerSpec]? {
        guard let base = HierarchyMessagingClient.webBaseURL() else { return nil }
        guard let user = Auth.auth().currentUser else { return nil }
        guard let token = try? await user.getIDToken(), !token.isEmpty else { return nil }

        var request = URLRequest(url: base.appendingPathComponent("api/turn-credentials"))
        request.httpMethod = "GET"
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse,
              (200..<300).contains(http.statusCode) else {
            return nil
        }
        return TurnCredentials.parse(data)
    }
}

// MARK: - Incoming call-signal bus

/// Routes decrypted call signals (templateCode 200) from the internet receiver to the call engine.
/// Until the engine lands the signals are simply acked and dropped — same externally visible
/// behavior as before, but the wire contract is already in place on both ends.
@MainActor
final class InternetCallSignalBus: ObservableObject {
    static let shared = InternetCallSignalBus()

    struct Signal {
        let contact: ContactRecord
        let senderUid: String
        let payload: [String: Any]
        let receivedAt: Date
    }

    /// The future InternetCallManager subscribes here.
    var onSignal: ((Signal) -> Void)?

    private init() {}

    func publish(contact: ContactRecord, senderUid: String, signalJson: String) {
        guard let data = signalJson.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return
        }
        onSignal?(Signal(contact: contact, senderUid: senderUid, payload: payload, receivedAt: Date()))
    }
}
