import Foundation

/// Canonical, cross-platform account/device binding carried inside every MLS BasicCredential.
enum AuthorityMlsCredential {
    private static let prefix = "cc-mls:v1:"

    struct Parsed: Equatable {
        let accountUid: String
        let deviceId: String
    }

    static func encode(accountUid: String, deviceId: String) throws -> String {
        let uid = accountUid.trimmingCharacters(in: .whitespacesAndNewlines)
        let device = deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty, uid.utf8.count <= 192, !device.isEmpty, device.utf8.count <= 192 else {
            throw AuthorityMlsError.invalidCredential
        }
        let encoded = prefix + base64url(Data(uid.utf8)) + ":" + base64url(Data(device.utf8))
        guard encoded.utf8.count <= 512 else { throw AuthorityMlsError.invalidCredential }
        return encoded
    }

    static func decode(_ value: String) -> Parsed? {
        guard value.hasPrefix(prefix), value.utf8.count <= 512 else { return nil }
        let body = String(value.dropFirst(prefix.count))
        let parts = body.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2,
              let uidData = decodeBase64url(String(parts[0])),
              let deviceData = decodeBase64url(String(parts[1])),
              let uid = String(data: uidData, encoding: .utf8),
              let device = String(data: deviceData, encoding: .utf8),
              (try? encode(accountUid: uid, deviceId: device)) == value else { return nil }
        return Parsed(accountUid: uid, deviceId: device)
    }

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func decodeBase64url(_ value: String) -> Data? {
        var normalized = value
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        return Data(base64Encoded: normalized)
    }
}

enum AuthorityMlsError: Error {
    case unavailable
    case invalidCredential
    case invalidContext
    case invalidWorkerResponse
    case unverifiedDevice
    case identityMismatch
    case stateCommitFailed
}
