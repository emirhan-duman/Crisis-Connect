//
//  RoleCertificate.swift
//  Crisis Connect
//
//  Created by Assistant on 11.03.2026.
//

import Foundation
import CryptoKit

struct RoleCertificate {
    let ownerUID: String
    let role: String
    let issuedAtMillis: Int64
    let expiresAtMillis: Int64
    let signatureBase64: String
    let version: Int

    static let certificateVersion = 1
    static let defaultMaxClockSkewMillis: Int64 = 60_000
    static let defaultOfflineGraceMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    static let allowedRoles: Set<String> = ["admin", "fieldteam"]

    func signatureBytes() throws -> Data {
        try decodeBase64Data(signatureBase64, fieldName: "signature")
    }

    func toStorageData() throws -> Data {
        let payload: [String: Any] = [
            Self.storageKeyVersion: version,
            Self.storageKeyOwnerUID: ownerUID,
            Self.storageKeyRole: role,
            Self.storageKeyIssuedAt: issuedAtMillis,
            Self.storageKeyExpiresAt: expiresAtMillis,
            Self.storageKeySignature: signatureBase64
        ]
        return try JSONSerialization.data(withJSONObject: payload, options: [])
    }

    func signingPayload(publicKeyBase64: String) throws -> Data {
        try Self.signingPayload(
            publicKeyBase64: publicKeyBase64,
            ownerUID: ownerUID,
            role: role,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis
        )
    }

    func isOwned(by userUID: String) -> Bool {
        let normalizedUserUID = userUID.trimmingCharacters(in: .whitespacesAndNewlines)
        return !normalizedUserUID.isEmpty && ownerUID == normalizedUserUID
    }

    func isValid(
        at nowMillis: Int64,
        maxClockSkewMillis: Int64 = Self.defaultMaxClockSkewMillis
    ) -> Bool {
        guard hasValidShape() else { return false }
        let earliestValidMillis = issuedAtMillis - maxClockSkewMillis
        let latestValidMillis = expiresAtMillis + maxClockSkewMillis
        return (earliestValidMillis...latestValidMillis).contains(nowMillis)
    }

    func isUsable(
        at nowMillis: Int64,
        allowOfflineGrace: Bool = false,
        maxClockSkewMillis: Int64 = Self.defaultMaxClockSkewMillis,
        offlineGraceMillis: Int64 = Self.defaultOfflineGraceMillis
    ) -> Bool {
        guard hasValidShape() else { return false }

        let earliestValidMillis = issuedAtMillis - maxClockSkewMillis
        if nowMillis < earliestValidMillis {
            return false
        }

        let latestValidMillis = expiresAtMillis + maxClockSkewMillis
        if nowMillis <= latestValidMillis {
            return true
        }

        guard allowOfflineGrace else { return false }
        let latestUsableMillis = latestValidMillis + max(0, offlineGraceMillis)
        return nowMillis <= latestUsableMillis
    }

    func hasValidShape() -> Bool {
        guard version == Self.certificateVersion else { return false }
        guard !ownerUID.isEmpty else { return false }
        guard Self.allowedRoles.contains(role) else { return false }
        guard issuedAtMillis > 0, expiresAtMillis > issuedAtMillis else { return false }
        return (try? signatureBytes().isEmpty == false) ?? false
    }

    static func fromStorageBytes(_ bytes: Data) -> RoleCertificate? {
        guard !bytes.isEmpty else { return nil }
        return try? fromJSONObjectData(bytes)
    }

    static func fromCallableResponse(_ data: [String: Any]) throws -> RoleCertificate {
        let version = parseIntValue(data["certificateVersion"] ?? data["version"]) ?? certificateVersion
        guard version == certificateVersion else {
            throw RoleCertificateError.unsupportedVersion(version)
        }

        guard let ownerUID = (data["ownerUid"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !ownerUID.isEmpty else {
            throw RoleCertificateError.missingField("ownerUid")
        }

        guard let role = normalizeRole(data["role"] as? String) else {
            throw RoleCertificateError.invalidRole((data["role"] as? String) ?? "")
        }

        guard let issuedAtMillis = parseInt64Value(data["issuedAtMs"] ?? data["issuedAt"] ?? data["iat"]) else {
            throw RoleCertificateError.missingField("issuedAtMs")
        }

        guard let expiresAtMillis = parseInt64Value(data["expiresAtMs"] ?? data["expiresAt"] ?? data["exp"]) else {
            throw RoleCertificateError.missingField("expiresAtMs")
        }

        guard let signature = ((data["certificate"] ?? data["signature"]) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !signature.isEmpty else {
            throw RoleCertificateError.missingField("certificate")
        }

        let certificate = RoleCertificate(
            ownerUID: ownerUID,
            role: role,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis,
            signatureBase64: signature,
            version: version
        )
        guard certificate.hasValidShape() else {
            throw RoleCertificateError.invalidCertificate
        }
        return certificate
    }

    static func signingPayload(
        publicKeyBase64: String,
        ownerUID: String,
        role: String,
        issuedAtMillis: Int64,
        expiresAtMillis: Int64
    ) throws -> Data {
        let normalizedPublicKey = publicKeyBase64.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedOwnerUID = ownerUID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let normalizedRole = normalizeRole(role) else {
            throw RoleCertificateError.invalidRole(role)
        }
        guard !normalizedPublicKey.isEmpty else {
            throw RoleCertificateError.missingField("publicKey")
        }
        guard !normalizedOwnerUID.isEmpty else {
            throw RoleCertificateError.missingField("ownerUid")
        }
        guard issuedAtMillis > 0 else {
            throw RoleCertificateError.invalidCertificate
        }
        guard expiresAtMillis > issuedAtMillis else {
            throw RoleCertificateError.invalidCertificate
        }

        let canonical = "\(normalizedPublicKey)|\(normalizedOwnerUID)|\(normalizedRole)|\(issuedAtMillis)|\(expiresAtMillis)"
        return Data(canonical.utf8)
    }

    static func normalizeRole(_ rawRole: String?) -> String? {
        let normalized = rawRole?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
        guard !normalized.isEmpty else { return nil }
        switch normalized {
        case "admin":
            return "admin"
        case "fieldteam", "field_team", "field-team", "ft":
            return "fieldteam"
        default:
            return nil
        }
    }

    private static func fromJSONObjectData(_ data: Data) throws -> RoleCertificate {
        let object = try JSONSerialization.jsonObject(with: data, options: [])
        guard let json = object as? [String: Any] else {
            throw RoleCertificateError.invalidStorage
        }

        guard let ownerUID = (json[storageKeyOwnerUID] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !ownerUID.isEmpty else {
            throw RoleCertificateError.invalidStorage
        }

        guard let role = normalizeRole(json[storageKeyRole] as? String) else {
            throw RoleCertificateError.invalidStorage
        }

        guard let issuedAtMillis = parseInt64Value(json[storageKeyIssuedAt]),
              let expiresAtMillis = parseInt64Value(json[storageKeyExpiresAt]),
              let signature = (json[storageKeySignature] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              !signature.isEmpty else {
            throw RoleCertificateError.invalidStorage
        }

        let certificate = RoleCertificate(
            ownerUID: ownerUID,
            role: role,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis,
            signatureBase64: signature,
            version: parseIntValue(json[storageKeyVersion]) ?? certificateVersion
        )
        guard certificate.hasValidShape() else {
            throw RoleCertificateError.invalidStorage
        }
        return certificate
    }

    private static func parseInt64Value(_ rawValue: Any?) -> Int64? {
        switch rawValue {
        case let number as NSNumber:
            return number.int64Value
        case let string as String:
            return Int64(string.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }

    private static func parseIntValue(_ rawValue: Any?) -> Int? {
        switch rawValue {
        case let number as NSNumber:
            return number.intValue
        case let string as String:
            return Int(string.trimmingCharacters(in: .whitespacesAndNewlines))
        default:
            return nil
        }
    }

    private static let storageKeyVersion = "v"
    private static let storageKeyOwnerUID = "uid"
    private static let storageKeyRole = "role"
    private static let storageKeyIssuedAt = "iat"
    private static let storageKeyExpiresAt = "exp"
    private static let storageKeySignature = "sig"
}

enum RoleCertificateSignatureVerifier {
    static let defaultMasterPublicKeyBase64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEnwait56qp0FAWjpL7EpGev3aYCO4DKI1Rswibgvqx438LesLoghU/hIDmmJPjKiZfDBUc1Xf0hMgZLKcJoQnEw=="

    static func verify(
        roleCertificate: RoleCertificate,
        publicKeyBase64: String,
        masterPublicKeyBase64: String = defaultMasterPublicKeyBase64
    ) -> Bool {
        do {
            let signature = try P256.Signing.ECDSASignature(derRepresentation: roleCertificate.signatureBytes())
            let masterPublicKeyData = try decodeBase64Data(masterPublicKeyBase64, fieldName: "masterPublicKey")
            let masterPublicKey = try P256.Signing.PublicKey(
                x963Representation: BleKeyDecoder.x963PublicKey(from: masterPublicKeyData)
            )
            let signingPayload = try roleCertificate.signingPayload(publicKeyBase64: publicKeyBase64)
            return masterPublicKey.isValidSignature(signature, for: signingPayload)
        } catch {
            return false
        }
    }
}

enum RoleCertificateError: Error {
    case invalidStorage
    case unsupportedVersion(Int)
    case missingField(String)
    case invalidRole(String)
    case invalidCertificate
}

func decodeBase64Data(_ value: String, fieldName: String) throws -> Data {
    let sanitized = value.trimmingCharacters(in: .whitespacesAndNewlines)
        .replacingOccurrences(of: "\n", with: "")
    guard !sanitized.isEmpty else {
        throw RoleProofDecodingError.invalidBase64(fieldName)
    }

    var padded = sanitized
    let remainder = padded.count % 4
    if remainder != 0 {
        padded = padded.padding(
            toLength: padded.count + (4 - remainder),
            withPad: "=",
            startingAt: 0
        )
    }

    guard let data = Data(base64Encoded: padded, options: [.ignoreUnknownCharacters]),
          !data.isEmpty else {
        throw RoleProofDecodingError.invalidBase64(fieldName)
    }
    return data
}

enum RoleProofDecodingError: Error {
    case invalidBase64(String)
    case invalidPayload
}
