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
    let deviceId: String
    let issuedAtMillis: Int64
    let expiresAtMillis: Int64
    let signatureBase64: String
    let agency: String
    let version: Int

    // Version we issue/expect for new certificates. v2 binds the deviceId, v3
    // additionally binds the issuing user's agency (e.g. AFAD/FEMA).
    static let certificateVersion = 3
    // Versions this client still accepts/verifies. v2 (no agency) is kept so peers
    // that have not re-provisioned keep working through the rollout; they simply
    // show no verified agency. The agency is only appended to the v3 canonical.
    static let supportedVersions: Set<Int> = [2, 3]
    static let agencyCertificateVersion = 3
    static let agencyMaxLength = 64
    static let defaultMaxClockSkewMillis: Int64 = 60_000
    static let defaultOfflineGraceMillis: Int64 = 7 * 24 * 60 * 60 * 1000
    static let allowedRoles: Set<String> = ["admin", "fieldteam"]
    static let deviceIdRegex = try! NSRegularExpression(pattern: "^cc-[0-9a-f]{24}$")

    func signatureBytes() throws -> Data {
        try decodeBase64Data(signatureBase64, fieldName: "signature")
    }

    func toStorageData() throws -> Data {
        let payload: [String: Any] = [
            Self.storageKeyVersion: version,
            Self.storageKeyOwnerUID: ownerUID,
            Self.storageKeyRole: role,
            Self.storageKeyDeviceId: deviceId,
            Self.storageKeyIssuedAt: issuedAtMillis,
            Self.storageKeyExpiresAt: expiresAtMillis,
            Self.storageKeyAgency: agency,
            Self.storageKeySignature: signatureBase64
        ]
        return try JSONSerialization.data(withJSONObject: payload, options: [])
    }

    func signingPayload(publicKeyBase64: String) throws -> Data {
        try Self.signingPayload(
            publicKeyBase64: publicKeyBase64,
            ownerUID: ownerUID,
            role: role,
            deviceId: deviceId,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis,
            agency: agency,
            version: version
        )
    }

    func isBound(to deviceIdToCheck: String) -> Bool {
        let normalized = deviceIdToCheck.trimmingCharacters(in: .whitespacesAndNewlines)
        return !normalized.isEmpty && deviceId == normalized
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
        guard Self.supportedVersions.contains(version) else { return false }
        guard !ownerUID.isEmpty else { return false }
        guard Self.allowedRoles.contains(role) else { return false }
        guard Self.isValidDeviceId(deviceId) else { return false }
        guard issuedAtMillis > 0, expiresAtMillis > issuedAtMillis else { return false }
        return (try? signatureBytes().isEmpty == false) ?? false
    }

    static func isValidDeviceId(_ value: String) -> Bool {
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        return deviceIdRegex.firstMatch(in: value, options: [], range: range) != nil
    }

    static func fromStorageBytes(_ bytes: Data) -> RoleCertificate? {
        guard !bytes.isEmpty else { return nil }
        return try? fromJSONObjectData(bytes)
    }

    static func fromCallableResponse(_ data: [String: Any]) throws -> RoleCertificate {
        let version = parseIntValue(data["certificateVersion"] ?? data["version"]) ?? certificateVersion
        guard supportedVersions.contains(version) else {
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

        guard let deviceId = (data["deviceId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            isValidDeviceId(deviceId) else {
            throw RoleCertificateError.missingField("deviceId")
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

        // Agency is only bound (signed) from v3 onward; ignore it on legacy v2.
        let agency = version >= agencyCertificateVersion
            ? sanitizeAgency(data["agency"] as? String)
            : ""

        let certificate = RoleCertificate(
            ownerUID: ownerUID,
            role: role,
            deviceId: deviceId,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis,
            signatureBase64: signature,
            agency: agency,
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
        deviceId: String,
        issuedAtMillis: Int64,
        expiresAtMillis: Int64,
        agency: String,
        version: Int = certificateVersion
    ) throws -> Data {
        let normalizedPublicKey = publicKeyBase64.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedOwnerUID = ownerUID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let normalizedRole = normalizeRole(role) else {
            throw RoleCertificateError.invalidRole(role)
        }
        let normalizedDeviceId = deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedAgency = sanitizeAgency(agency)
        guard !normalizedPublicKey.isEmpty else {
            throw RoleCertificateError.missingField("publicKey")
        }
        guard !normalizedOwnerUID.isEmpty else {
            throw RoleCertificateError.missingField("ownerUid")
        }
        guard isValidDeviceId(normalizedDeviceId) else {
            throw RoleCertificateError.missingField("deviceId")
        }
        guard issuedAtMillis > 0 else {
            throw RoleCertificateError.invalidCertificate
        }
        guard expiresAtMillis > issuedAtMillis else {
            throw RoleCertificateError.invalidCertificate
        }

        var canonical = "\(normalizedPublicKey)|\(normalizedOwnerUID)|\(normalizedRole)|\(normalizedDeviceId)|\(issuedAtMillis)|\(expiresAtMillis)"
        // The agency segment exists only in the v3 canonical. Legacy v2
        // certificates were signed without it, so their canonical must end at
        // expiresAtMillis to verify against the original signature.
        if version >= agencyCertificateVersion {
            canonical.append("|\(normalizedAgency)")
        }
        return Data(canonical.utf8)
    }

    /// Normalises the agency display label so the client rebuilds an identical
    /// canonical signing payload to the backend (`sanitizeAgency` in
    /// functions/src/certificates/issuance.ts): strips control characters and the
    /// '|' delimiter, collapses whitespace, trims, and bounds the length.
    static func sanitizeAgency(_ raw: String?) -> String {
        guard let raw, !raw.isEmpty else { return "" }
        var scalars = String.UnicodeScalarView()
        for scalar in raw.unicodeScalars {
            if scalar.value < 0x20 || scalar.value == 0x7F || scalar == "|" {
                scalars.append(" ")
            } else {
                scalars.append(scalar)
            }
        }
        let collapsed = String(scalars)
            .components(separatedBy: .whitespaces)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        guard collapsed.count > agencyMaxLength else { return collapsed }
        return String(collapsed.prefix(agencyMaxLength))
            .trimmingCharacters(in: .whitespaces)
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

        guard let deviceId = (json[storageKeyDeviceId] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            isValidDeviceId(deviceId) else {
            throw RoleCertificateError.invalidStorage
        }

        guard let issuedAtMillis = parseInt64Value(json[storageKeyIssuedAt]),
              let expiresAtMillis = parseInt64Value(json[storageKeyExpiresAt]),
              let signature = (json[storageKeySignature] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
              !signature.isEmpty else {
            throw RoleCertificateError.invalidStorage
        }

        let storedVersion = parseIntValue(json[storageKeyVersion]) ?? certificateVersion
        // Agency is only bound (signed) from v3 onward; ignore it on legacy v2.
        let agency = storedVersion >= agencyCertificateVersion
            ? sanitizeAgency(json[storageKeyAgency] as? String)
            : ""

        let certificate = RoleCertificate(
            ownerUID: ownerUID,
            role: role,
            deviceId: deviceId,
            issuedAtMillis: issuedAtMillis,
            expiresAtMillis: expiresAtMillis,
            signatureBase64: signature,
            agency: agency,
            version: storedVersion
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
    private static let storageKeyDeviceId = "did"
    private static let storageKeyIssuedAt = "iat"
    private static let storageKeyExpiresAt = "exp"
    private static let storageKeyAgency = "agcy"
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
