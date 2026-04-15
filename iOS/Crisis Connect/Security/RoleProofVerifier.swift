//
//  RoleProofVerifier.swift
//  Crisis Connect
//
//  Created by Assistant on 11.03.2026.
//

import Foundation
import CryptoKit

struct RoleProofVerifier {
    let maxClockSkewMillis: Int64
    let maxExpiredCertificateGraceMillis: Int64
    let timeProvider: () -> Int64

    init(
        maxClockSkewMillis: Int64 = 60_000,
        maxExpiredCertificateGraceMillis: Int64 = 7 * 24 * 60 * 60 * 1000,
        timeProvider: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.maxClockSkewMillis = maxClockSkewMillis
        self.maxExpiredCertificateGraceMillis = max(0, maxExpiredCertificateGraceMillis)
        self.timeProvider = timeProvider
    }

    func verifyPacket(
        key: SymmetricKey,
        transportPacket: Data,
        maxPacketSize: Int,
        expectedSessionNonce: String? = nil
    ) throws -> RoleProofPayload {
        let plaintext = try BleAesGcm.decryptPacket(
            key: key,
            transportPacket: transportPacket,
            maxPacketSize: maxPacketSize
        )
        let proof = try RoleProofPayload.fromJSON(plaintext)
        return try verifyProofPayload(
            proof: proof,
            expectedSessionNonce: expectedSessionNonce
        )
    }

    func verifyProofPayload(
        proof: RoleProofPayload,
        expectedSessionNonce: String? = nil
    ) throws -> RoleProofPayload {
        try validate(proof, expectedSessionNonce: expectedSessionNonce)
        return proof
    }

    private func validate(
        _ proof: RoleProofPayload,
        expectedSessionNonce: String?
    ) throws {
        let normalizedExpectedNonce = expectedSessionNonce?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let normalizedExpectedNonce, !normalizedExpectedNonce.isEmpty {
            guard proof.sessionNonce == normalizedExpectedNonce else {
                throw RoleProofVerificationError.sessionNonceMismatch
            }
        }

        let now = timeProvider()
        let skew = abs(now - proof.timestamp)
        guard skew <= maxClockSkewMillis else {
            throw RoleProofVerificationError.clockSkew
        }

        let certificateBytes = try decodeBase64Data(proof.certificate, fieldName: "certificate")
        guard let roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) else {
            throw RoleProofVerificationError.invalidCertificate
        }
        guard roleCertificate.isUsable(
            at: now,
            allowOfflineGrace: true,
            maxClockSkewMillis: maxClockSkewMillis,
            offlineGraceMillis: maxExpiredCertificateGraceMillis
        ) else {
            throw RoleProofVerificationError.expiredCertificate
        }
        guard RoleCertificateSignatureVerifier.verify(
            roleCertificate: roleCertificate,
            publicKeyBase64: proof.devicePublicKey
        ) else {
            throw RoleProofVerificationError.invalidCertificate
        }

        let devicePublicKeyData = try decodeBase64Data(proof.devicePublicKey, fieldName: "devicePublicKey")
        let devicePublicKey = try P256.Signing.PublicKey(
            x963Representation: BleKeyDecoder.x963PublicKey(from: devicePublicKeyData)
        )
        let signature = try P256.Signing.ECDSASignature(
            derRepresentation: decodeBase64Data(proof.signature, fieldName: "signature")
        )
        guard devicePublicKey.isValidSignature(signature, for: proof.signaturePayloadBytes()) else {
            throw RoleProofVerificationError.invalidSignature
        }
    }
}

enum RoleProofVerificationError: Error {
    case sessionNonceMismatch
    case clockSkew
    case invalidCertificate
    case expiredCertificate
    case invalidSignature
}
