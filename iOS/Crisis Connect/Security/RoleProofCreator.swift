//
//  RoleProofCreator.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import Foundation
import CryptoKit

struct RoleProofPayload {
    let devicePublicKey: String
    let certificate: String
    let timestamp: Int64
    let signature: String
    let sessionNonce: String?
    let allowExpiredCertificate: Bool

    func jsonData() throws -> Data {
        var payload: [String: Any] = [
            "devicePublicKey": devicePublicKey,
            "certificate": certificate,
            "timestamp": timestamp,
            "signature": signature,
            "allowExpiredCertificate": allowExpiredCertificate
        ]
        if let sessionNonce {
            payload["sessionNonce"] = sessionNonce
        }
        return try JSONSerialization.data(withJSONObject: payload, options: [])
    }

    func signaturePayloadBytes() -> Data {
        Self.signaturePayload(
            publicKey: devicePublicKey,
            certificate: certificate,
            timestamp: timestamp,
            sessionNonce: sessionNonce,
            allowExpiredCertificate: allowExpiredCertificate
        )
    }

    static func fromJSON(_ data: Data) throws -> RoleProofPayload {
        let object = try JSONSerialization.jsonObject(with: data, options: [])
        guard let json = object as? [String: Any],
              let devicePublicKey = json["devicePublicKey"] as? String,
              let certificate = json["certificate"] as? String,
              let signature = json["signature"] as? String else {
            throw RoleProofDecodingError.invalidPayload
        }

        let timestamp: Int64
        switch json["timestamp"] {
        case let number as NSNumber:
            timestamp = number.int64Value
        case let string as String:
            guard let parsed = Int64(string.trimmingCharacters(in: .whitespacesAndNewlines)) else {
                throw RoleProofDecodingError.invalidPayload
            }
            timestamp = parsed
        default:
            throw RoleProofDecodingError.invalidPayload
        }

        let normalizedSessionNonce = (json["sessionNonce"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return RoleProofPayload(
            devicePublicKey: devicePublicKey,
            certificate: certificate,
            timestamp: timestamp,
            signature: signature,
            sessionNonce: normalizedSessionNonce?.isEmpty == false ? normalizedSessionNonce : nil,
            allowExpiredCertificate: json["allowExpiredCertificate"] as? Bool ?? false
        )
    }

    static func signaturePayload(
        publicKey: String,
        certificate: String,
        timestamp: Int64,
        sessionNonce: String? = nil,
        allowExpiredCertificate: Bool = false
    ) -> Data {
        var canonical = "\(publicKey)|\(certificate)|\(timestamp)"
        if let sessionNonce = sessionNonce?.trimmingCharacters(in: .whitespacesAndNewlines),
           !sessionNonce.isEmpty {
            canonical.append("|\(sessionNonce)")
        }
        if allowExpiredCertificate {
            canonical.append("|true")
        }
        return Data(canonical.utf8)
    }
}

final class RoleProofCreator {
    private let securityRepository: SecurityRepository
    private let deviceStore: DeviceIdentityStore
    private let allowExpiredCertificate: Bool
    private let nowProvider: () -> Int64

    init(
        securityRepository: SecurityRepository = .shared,
        deviceStore: DeviceIdentityStore = .shared,
        allowExpiredCertificate: Bool = true,
        nowProvider: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.securityRepository = securityRepository
        self.deviceStore = deviceStore
        self.allowExpiredCertificate = allowExpiredCertificate
        self.nowProvider = nowProvider
    }

    func createPayload(sessionNonce: String? = nil) async throws -> RoleProofPayload {
        guard let certificate = try await securityRepository.getStoredCertificate(
            allowExpired: allowExpiredCertificate
        ) else {
            throw MissingRoleCertificateError.missing
        }
        let certificateBase64 = certificate.certificateBytes.base64EncodedString()
        let timestamp = nowProvider()
        let trimmedSessionNonce = sessionNonce?.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedSessionNonce = (trimmedSessionNonce?.isEmpty == false) ? trimmedSessionNonce : nil
        let usingOfflineGrace = !certificate.roleCertificate.isValid(at: timestamp)
        let payload = RoleProofPayload(
            devicePublicKey: certificate.publicKeyBase64,
            certificate: certificateBase64,
            timestamp: timestamp,
            signature: "",
            sessionNonce: normalizedSessionNonce,
            allowExpiredCertificate: usingOfflineGrace
        )
        let signature = try deviceStore.signPayload(
            payload.signaturePayloadBytes(),
            with: certificate.privateKey
        )
        let signatureBase64 = signature.base64EncodedString()
        return RoleProofPayload(
            devicePublicKey: certificate.publicKeyBase64,
            certificate: certificateBase64,
            timestamp: timestamp,
            signature: signatureBase64,
            sessionNonce: normalizedSessionNonce,
            allowExpiredCertificate: usingOfflineGrace
        )
    }

    func createEncryptedPacket(
        sessionKey: SymmetricKey,
        maxPacketSize: Int,
        sessionNonce: String? = nil
    ) async throws -> Data {
        let payload = try await createPayload(sessionNonce: sessionNonce)
        let json = try payload.jsonData()
        let encrypted = BleAesGcm.encryptPacket(
            key: sessionKey,
            plaintext: json,
            maxPacketSize: maxPacketSize
        )
        return encrypted
    }
}

enum MissingRoleCertificateError: Error {
    case missing
}
