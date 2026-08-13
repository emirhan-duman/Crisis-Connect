//
//  SfuCallFoundation.swift
//  Crisis Connect
//
//  Foundation of the SFU (Cloudflare Realtime + MLS-E2EE) group-call port — the pure layers that
//  need no native MLS core and no WebRTC frame-crypto fork, mirroring Android's
//  messaging/call/sfu/* and the web's sfu-call.ts / e2ee-mls.ts:
//
//   - SfuCallConfig: feature gate (OFF until media + MLS land and are device-tested)
//   - MlsHandshakeCodec: byte-exact wire framing for MLS handshake blobs (Cloudflare Orange's
//     typed-array JSON convention) — the one Faz C piece with no native dependency
//   - SfuApiClient: the /api/realtime proxy (sessions/new, tracks/new, renegotiate); the proxy
//     injects the Cloudflare App Secret server-side, mobile authenticates with the Firebase ID token
//
//  The media manager (publish/pull tracks over one RTCPeerConnection) and the frame cryptor
//  attach in later increments — see docs/SFU_GROUP_CALLS.md for the full plan and blockers.
//

import FirebaseAuth
import Foundation

/// Feature gate for the SFU authority-call path. MUST stay false until the native MLS core and
/// the frame-crypto WebRTC build land — flipping it early would replace nothing on iOS (the
/// managers aren't ported yet) but the flag mirrors Android's contract for when they are.
enum SfuCallConfig {
    static let enabled = false
}

/// One track I published to the SFU (mirrors the web/Android roster doc shape).
struct SfuPublishedTrack: Equatable {
    let trackName: String
    let kind: String   // "audio" | "video"
    let source: String // "mic" | "camera" | "screen"
}

/// A remote room member from the Firestore roster.
struct SfuRemoteParticipant: Equatable {
    let uid: String
    let sessionId: String
    let tracks: [SfuPublishedTrack]
    let updatedAt: Date?
}

// MARK: - MLS handshake wire codec

/// Wire codec for MLS handshake messages relayed over Firestore (`sfuRooms/{id}/mlsMessages`),
/// byte-exact with the web (`e2ee-mls.ts` replacer/reviver + Cloudflare Orange's
/// `MessageFromWorker` union) and Android's MlsHandshakeCodec. The SFU + Firestore never see
/// keys or plaintext — these are opaque, already-serialized MLS blobs; this codec only frames
/// them so both platforms parse each other's.
///
/// Byte fields follow Orange: a `Uint8Array` becomes `{ FLAG_TYPED_ARRAY: true, data: [ints] }`
/// and an `ArrayBuffer` becomes `{ FLAG_ARRAY_BUFFER: true, data: [ints] }`.
enum MlsHandshakeCodec {
    private static let flagTypedArray = "FLAG_TYPED_ARRAY"
    private static let flagArrayBuffer = "FLAG_ARRAY_BUFFER"

    enum Message: Equatable {
        case shareKeyPackage(keyPkg: Data)
        case sendMlsMessage(msg: Data, senderId: String)
        case sendMlsWelcome(senderId: String, welcome: Data, rtree: Data)
    }

    /// Serialize a message to the exact JSON string the web expects on the wire.
    static func encode(_ message: Message) -> String? {
        let object: [String: Any]
        switch message {
        case .shareKeyPackage(let keyPkg):
            object = ["type": "shareKeyPackage", "keyPkg": typedArray(keyPkg)]
        case .sendMlsMessage(let msg, let senderId):
            object = ["type": "sendMlsMessage", "msg": typedArray(msg), "senderId": senderId]
        case .sendMlsWelcome(let senderId, let welcome, let rtree):
            object = [
                "type": "sendMlsWelcome",
                "senderId": senderId,
                "welcome": typedArray(welcome),
                "rtree": typedArray(rtree)
            ]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// Parse a peer's (web or Android) MLS message. Nil for unknown/local-only types
    /// (newSafetyNumber / workerReady are never broadcast).
    static func decode(_ json: String) -> Message? {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        switch object["type"] as? String {
        case "shareKeyPackage":
            guard let keyPkg = bytes(object, "keyPkg") else { return nil }
            return .shareKeyPackage(keyPkg: keyPkg)
        case "sendMlsMessage":
            guard let msg = bytes(object, "msg") else { return nil }
            return .sendMlsMessage(msg: msg, senderId: object["senderId"] as? String ?? "")
        case "sendMlsWelcome":
            guard let welcome = bytes(object, "welcome"),
                  let rtree = bytes(object, "rtree") else { return nil }
            return .sendMlsWelcome(
                senderId: object["senderId"] as? String ?? "",
                welcome: welcome,
                rtree: rtree
            )
        default:
            return nil
        }
    }

    /// Formats the worker's safety-number int array the way web/Android display it:
    /// each element zero-padded to 3 digits, concatenated.
    static func formatSafetyNumber(_ values: [Int]) -> String {
        values.map { String(format: "%03d", $0) }.joined()
    }

    private static func typedArray(_ bytes: Data) -> [String: Any] {
        // ArrayBuffer dialect, NOT typed-array: the web reviver turns FLAG_ARRAY_BUFFER back into
        // the ArrayBuffer its wasm worker requires — the typed-array flag silently killed the web
        // worker on Android-generated messages (see android_ffi.rs). Decode accepts both.
        [flagArrayBuffer: true, "data": bytes.map { Int($0) }]
    }

    private static func bytes(_ parent: [String: Any], _ key: String) -> Data? {
        guard let field = parent[key] as? [String: Any],
              (field[flagTypedArray] as? Bool == true) || (field[flagArrayBuffer] as? Bool == true),
              let raw = field["data"] as? [Any] else {
            return nil
        }
        return Data(raw.compactMap { ($0 as? NSNumber).map { UInt8(truncatingIfNeeded: $0.intValue) } })
    }
}

// MARK: - Cloudflare Realtime proxy client

enum SfuApiError: Error, Equatable {
    case notSignedIn
    case baseUrlMissing
    case requestFailed(String)
}

/// Thin client for the Cloudflare Realtime (Serverless SFU) Connection API, reached through the
/// dashboard's `/api/realtime` proxy (which injects the App Secret server-side). The three ops the
/// proxy allows: `sessions/new`, `sessions/{id}/tracks/new`, `sessions/{id}/renegotiate`. Mirrors
/// the web's api() in sfu-call.ts and Android's SfuApiClient, including the cold-edge retry.
final class SfuApiClient: @unchecked Sendable {
    private let session: URLSession
    private let roomId: String
    private let callId: String

    init(roomId: String, callId: String) {
        self.roomId = roomId
        self.callId = callId
        let config = URLSessionConfiguration.default
        // The Cloudflare proxy can exceed 10s when the edge is cold; give it the same window
        // Android does (25s read) with one retry on timeout.
        config.timeoutIntervalForRequest = 25
        session = URLSession(configuration: config)
    }

    /// Allocate an empty session. The single initial SDP offer is sent by `tracks/new` below.
    func createSession() async throws -> [String: Any] {
        try await execute(path: "sessions/new", method: "POST", body: [:])
    }

    /// POST `sessions/{id}/tracks/new` to PUBLISH local tracks (we offer, the SFU answers).
    func publishTracks(sessionId: String, offerSdp: String, tracks: [[String: Any]]) async throws -> [String: Any] {
        try await execute(path: "sessions/\(sessionId)/tracks/new", method: "POST", body: [
            "sessionDescription": ["type": "offer", "sdp": offerSdp],
            "tracks": tracks
        ])
    }

    /// POST `sessions/{id}/tracks/new` to PULL remote tracks (the SFU offers, we answer).
    func pullTracks(sessionId: String, tracks: [[String: Any]]) async throws -> [String: Any] {
        try await execute(path: "sessions/\(sessionId)/tracks/new", method: "POST", body: [
            "tracks": tracks
        ])
    }

    /// PUT `sessions/{id}/renegotiate` with our answer to the SFU's pull offer.
    func renegotiate(sessionId: String, answerSdp: String) async throws -> [String: Any] {
        try await execute(path: "sessions/\(sessionId)/renegotiate", method: "PUT", body: [
            "sessionDescription": ["type": "answer", "sdp": answerSdp]
        ])
    }

    /// The proxy's error contract, shared with web/Android: non-2xx OR a body carrying
    /// errorCode/errorDescription is a failure. Factored out for tests.
    static func validate(statusCode: Int, json: [String: Any]) throws -> [String: Any] {
        let errorCode = (json["errorCode"] as? String) ?? ""
        guard (200..<300).contains(statusCode), errorCode.isEmpty else {
            let detail = (json["errorDescription"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "HTTP \(statusCode)"
            throw SfuApiError.requestFailed(detail)
        }
        return json
    }

    private func execute(path: String, method: String, body: [String: Any]) async throws -> [String: Any] {
        guard let base = HierarchyMessagingClient.webBaseURL() else { throw SfuApiError.baseUrlMissing }
        guard let user = Auth.auth().currentUser else { throw SfuApiError.notSignedIn }
        let token = try await user.getIDToken()

        var request = URLRequest(url: base.appendingPathComponent("api/realtime/\(path)"))
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(roomId, forHTTPHeaderField: "X-CC-SFU-Room-ID")
        request.setValue(callId, forHTTPHeaderField: "X-CC-SFU-Call-ID")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        var lastTimeout: Error?
        for _ in 0..<2 {
            do {
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
                return try Self.validate(statusCode: status, json: json)
            } catch let error as URLError where error.code == .timedOut {
                lastTimeout = error
            }
        }
        throw lastTimeout ?? SfuApiError.requestFailed("timeout")
    }
}
