import CryptoKit
import Foundation
import Security

enum AuthorityMlsTrustVerdict: String, Codable, Sendable {
    case first
    case match
    case changed
    case missing
}

struct AuthorityMlsTrustAssessment: Sendable {
    let uid: String
    let verdict: AuthorityMlsTrustVerdict
    let approved: Bool
    let fingerprint: String
    let safetyNumber: String
    let deviceCommitments: [String]
}

enum AuthorityMlsTrust {
    static func automaticTrustEligible(deviceCount: Int, commitments: [String]) -> Bool {
        deviceCount > 0 && commitments.count == deviceCount && Set(commitments).count == deviceCount
    }

    static func deviceCommitment(_ record: AuthorityMlsDirectoryRecord) -> String {
        var input = Data()
        appendLengthPrefixed(Data(record.credential.utf8), to: &input)
        appendLengthPrefixed(record.signingPublicKey, to: &input)
        return base64url(Data(SHA256.hash(data: input)))
    }

    static func deviceSetFingerprint(_ commitments: [String]) throws -> String {
        guard !commitments.isEmpty,
              commitments == Array(Set(commitments)).sorted(by: compareUtf8) else {
            throw AuthorityMlsTrustError.invalidDeviceSet
        }
        var input = Data()
        appendLengthPrefixed(Data("cc-authority-mls-device-set:v1".utf8), to: &input)
        for commitment in commitments {
            appendLengthPrefixed(Data(commitment.utf8), to: &input)
        }
        return base64url(Data(SHA256.hash(data: input)))
    }

    static func safetyNumber(_ commitments: [String]) throws -> String {
        let fingerprint = try deviceSetFingerprint(commitments)
        let digest = Data(SHA256.hash(data: Data("cc-authority-mls-safety:v1:\(fingerprint)".utf8)))
        let fields = digest.prefix(16).map { String(format: "%03d", $0) }
        return stride(from: 0, to: fields.count, by: 2)
            .map { fields[$0 ... min($0 + 1, fields.count - 1)].joined(separator: " ") }
            .joined(separator: "  ")
    }

    private static func appendLengthPrefixed(_ field: Data, to output: inout Data) {
        var length = UInt32(field.count).bigEndian
        withUnsafeBytes(of: &length) { output.append(contentsOf: $0) }
        output.append(field)
    }

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func compareUtf8(_ left: String, _ right: String) -> Bool {
        Array(left.utf8).lexicographicallyPrecedes(Array(right.utf8))
    }
}

/// Pins complete device sets from the centrally provisioned authority directory. Valid rotations
/// are accepted automatically; malformed, incomplete and cross-account records still fail closed.
struct AuthorityMlsTrustStore: Sendable {
    // v3 makes the authenticated authority directory the device-set trust source.
    private static let service = "com.crisisconnect.authority-mls-device-pins-v3"

    func assess(
        conversationId: String,
        uid: String,
        devices: [AuthorityMlsDirectoryRecord]
    ) throws -> AuthorityMlsTrustAssessment {
        guard devices.allSatisfy({ $0.uid == uid }) else {
            throw AuthorityMlsTrustError.invalidDeviceSet
        }
        let commitments = devices.map(AuthorityMlsTrust.deviceCommitment).sorted(by: compareUtf8)
        guard AuthorityMlsTrust.automaticTrustEligible(
            deviceCount: devices.count,
            commitments: commitments
        ) else {
            return AuthorityMlsTrustAssessment(
                uid: uid,
                verdict: .missing,
                approved: false,
                fingerprint: "",
                safetyNumber: "",
                deviceCommitments: commitments
            )
        }
        let fingerprint = try AuthorityMlsTrust.deviceSetFingerprint(commitments)
        let safetyNumber = try AuthorityMlsTrust.safetyNumber(commitments)
        let key = recordKey(conversationId: conversationId, uid: uid)
        guard let existing = try load(key) else {
            try save(PinRecord(
                fingerprint: fingerprint,
                deviceCommitments: commitments,
                approved: true,
                verifiedAt: Date().timeIntervalSince1970
            ), key: key)
            return AuthorityMlsTrustAssessment(
                uid: uid,
                verdict: .first,
                approved: true,
                fingerprint: fingerprint,
                safetyNumber: safetyNumber,
                deviceCommitments: commitments
            )
        }
        let matches = existing.fingerprint == fingerprint && existing.deviceCommitments == commitments
        if !matches || !existing.approved {
            try save(PinRecord(
                fingerprint: fingerprint,
                deviceCommitments: commitments,
                approved: true,
                verifiedAt: Date().timeIntervalSince1970
            ), key: key)
        }
        return AuthorityMlsTrustAssessment(
            uid: uid,
            verdict: matches ? .match : .changed,
            approved: true,
            fingerprint: fingerprint,
            safetyNumber: safetyNumber,
            deviceCommitments: commitments
        )
    }

    /// Calls use the same complete authenticated directory as AuthorityChat.
    func verifyExisting(
        conversationId: String,
        uid: String,
        devices: [AuthorityMlsDirectoryRecord]
    ) throws -> AuthorityMlsTrustAssessment {
        try assess(conversationId: conversationId, uid: uid, devices: devices)
    }

    /// Approves only the exact canonical set currently shown to the user.
    func approve(
        conversationId: String,
        uid: String,
        expectedFingerprint: String,
        deviceCommitments: [String]
    ) throws {
        let fingerprint = try AuthorityMlsTrust.deviceSetFingerprint(deviceCommitments)
        guard fingerprint == expectedFingerprint else { throw AuthorityMlsTrustError.invalidDeviceSet }
        let key = recordKey(conversationId: conversationId, uid: uid)
        guard let existing = try load(key),
              existing.fingerprint == fingerprint,
              existing.deviceCommitments == deviceCommitments else {
            throw AuthorityMlsTrustError.storageFailure
        }
        try save(PinRecord(
            fingerprint: fingerprint,
            deviceCommitments: deviceCommitments,
            approved: true,
            verifiedAt: Date().timeIntervalSince1970
        ), key: key)
    }

    private struct PinRecord: Codable {
        let fingerprint: String
        let deviceCommitments: [String]
        let approved: Bool
        let verifiedAt: TimeInterval
    }

    private func recordKey(conversationId: String, uid: String) -> String {
        let digest = SHA256.hash(data: Data("cc-authority-mls-pin:v1:\(conversationId)\u{0}\(uid)".utf8))
        return Data(digest).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private func load(_ key: String) throws -> PinRecord? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: key,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data,
              let record = try? JSONDecoder().decode(PinRecord.self, from: data),
              !record.fingerprint.isEmpty,
              !record.deviceCommitments.isEmpty,
              record.deviceCommitments == record.deviceCommitments.sorted(by: compareUtf8) else {
            throw AuthorityMlsTrustError.storageFailure
        }
        return record
    }

    private func save(_ record: PinRecord, key: String) throws {
        let data = try JSONEncoder().encode(record)
        let lookup: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.service,
            kSecAttrAccount as String: key
        ]
        let update = SecItemUpdate(lookup as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw AuthorityMlsTrustError.storageFailure }
        var insert = lookup
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        insert[kSecValueData as String] = data
        guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else {
            throw AuthorityMlsTrustError.storageFailure
        }
    }

    private func compareUtf8(_ left: String, _ right: String) -> Bool {
        Array(left.utf8).lexicographicallyPrecedes(Array(right.utf8))
    }
}

private enum AuthorityMlsTrustError: Error {
    case invalidDeviceSet
    case storageFailure
}
