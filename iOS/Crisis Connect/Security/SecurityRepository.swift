//
//  SecurityRepository.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import Foundation
import Security
import FirebaseAuth
import FirebaseFunctions

final class SecurityRepository {
    static let shared = SecurityRepository()

    private lazy var functions: Functions = {
        FirebaseRuntime.ensureConfigured()
        return Functions.functions(region: "us-central1")
    }()
    private lazy var auth: Auth = {
        FirebaseRuntime.ensureConfigured()
        return Auth.auth()
    }()
    private let deviceStore = DeviceIdentityStore.shared
    private let secureStore = SecureLocalStore.shared

    private init() {}

    func getOrFetchCertificate() async throws -> DeviceCertificate {
        let privateKey = try deviceStore.getOrCreatePrivateKey()
        let publicKeyData = try deviceStore.publicKeyDataX509(for: privateKey)
        let publicKeyBase64 = publicKeyData.base64EncodedString()
        let currentUID = auth.currentUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !currentUID.isEmpty else {
            throw SecurityError.authRequired
        }

        if let cached = loadUsableStoredCertificate(
            currentUID: currentUID,
            publicKeyBase64: publicKeyBase64,
            allowExpired: false
        ) {
            return DeviceCertificate(
                privateKey: privateKey,
                publicKeyBase64: publicKeyBase64,
                certificateBytes: cached.bytes,
                roleCertificate: cached.roleCertificate
            )
        }

        do {
            let certificate = try await requestCertificate(
                publicKeyBase64: publicKeyBase64,
                currentUID: currentUID
            )
            let certificateBytes = try certificate.toStorageData()
            persistCertificate(
                publicKeyBase64: publicKeyBase64,
                certificateBytes: certificateBytes,
                ownerUID: certificate.ownerUID
            )
            return DeviceCertificate(
                privateKey: privateKey,
                publicKeyBase64: publicKeyBase64,
                certificateBytes: certificateBytes,
                roleCertificate: certificate
            )
        } catch {
            if let cached = loadUsableStoredCertificate(
                currentUID: currentUID,
                publicKeyBase64: publicKeyBase64,
                allowExpired: false
            ) {
                return DeviceCertificate(
                    privateKey: privateKey,
                    publicKeyBase64: publicKeyBase64,
                    certificateBytes: cached.bytes,
                    roleCertificate: cached.roleCertificate
                )
            }
            throw error
        }
    }

    func getStoredCertificate(allowExpired: Bool = false) async throws -> DeviceCertificate? {
        try getStoredCertificateSync(allowExpired: allowExpired)
    }

    func getStoredCertificateSync(allowExpired: Bool = false) throws -> DeviceCertificate? {
        let currentUID = resolveCurrentUID()
        guard !currentUID.isEmpty else { return nil }

        let privateKey = try deviceStore.getOrCreatePrivateKey()
        let publicKeyData = try deviceStore.publicKeyDataX509(for: privateKey)
        let publicKeyBase64 = publicKeyData.base64EncodedString()
        guard let stored = loadUsableStoredCertificate(
            currentUID: currentUID,
            publicKeyBase64: publicKeyBase64,
            allowExpired: allowExpired
        ) else {
            return nil
        }

        return DeviceCertificate(
            privateKey: privateKey,
            publicKeyBase64: publicKeyBase64,
            certificateBytes: stored.bytes,
            roleCertificate: stored.roleCertificate
        )
    }

    func hasUsableStoredCertificate(allowExpired: Bool = false) async -> Bool {
        (try? await getStoredCertificate(allowExpired: allowExpired)) != nil
    }

    func getUsableStoredCertificateRole(allowExpired: Bool = false) async -> String? {
        try? await getStoredCertificate(allowExpired: allowExpired)?.roleCertificate.role
    }

    func warmUpCertificate() async -> Bool {
        (try? await getOrFetchCertificate()) != nil
    }

    func clearStoredCertificate() {
        KeychainStore.delete(Keys.certificate)
        KeychainStore.delete(Keys.publicKey)
        KeychainStore.delete(Keys.certificateOwnerUID)
    }

    private func requestCertificate(
        publicKeyBase64: String,
        currentUID: String
    ) async throws -> RoleCertificate {
        let payload: [String: Any] = ["publicKey": publicKeyBase64]
        let result = try await functions.httpsCallable("issueRoleCertificate").callAsync(payload)
        guard let data = result.data as? [String: Any] else {
            throw SecurityError.invalidResponse
        }

        let certificate = try RoleCertificate.fromCallableResponse(data)
        guard certificate.isOwned(by: currentUID) else {
            throw SecurityError.ownerMismatch
        }
        guard certificate.isValid(at: nowMillis()) else {
            throw SecurityError.invalidCertificate
        }
        guard RoleCertificateSignatureVerifier.verify(
            roleCertificate: certificate,
            publicKeyBase64: publicKeyBase64
        ) else {
            throw SecurityError.invalidCertificate
        }
        _ = try certificate.signingPayload(publicKeyBase64: publicKeyBase64)
        return certificate
    }

    private func persistCertificate(
        publicKeyBase64: String,
        certificateBytes: Data,
        ownerUID: String
    ) {
        KeychainStore.save(certificateBytes, key: Keys.certificate)
        KeychainStore.saveString(publicKeyBase64, key: Keys.publicKey)
        if !ownerUID.isEmpty {
            KeychainStore.saveString(ownerUID, key: Keys.certificateOwnerUID)
        } else {
            KeychainStore.delete(Keys.certificateOwnerUID)
        }
    }

    private func loadUsableStoredCertificate(
        currentUID: String,
        publicKeyBase64: String,
        allowExpired: Bool
    ) -> StoredCertificate? {
        guard !currentUID.isEmpty else { return nil }
        guard let storedPublicKey = KeychainStore.loadString(Keys.publicKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            storedPublicKey == publicKeyBase64,
            let certificateBytes = KeychainStore.load(Keys.certificate),
            let roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) else {
            return nil
        }

        if let storedOwnerUID = KeychainStore.loadString(Keys.certificateOwnerUID)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !storedOwnerUID.isEmpty,
           storedOwnerUID != currentUID {
            return nil
        }

        guard roleCertificate.isOwned(by: currentUID) else { return nil }
        guard roleCertificate.isUsable(
            at: nowMillis(),
            allowOfflineGrace: allowExpired
        ) else {
            return nil
        }
        guard RoleCertificateSignatureVerifier.verify(
            roleCertificate: roleCertificate,
            publicKeyBase64: publicKeyBase64
        ) else {
            return nil
        }

        return StoredCertificate(bytes: certificateBytes, roleCertificate: roleCertificate)
    }

    private func resolveCurrentUID() -> String {
        let authUID = auth.currentUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !authUID.isEmpty {
            return authUID
        }
        return secureStore.loadUid()?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private func nowMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    private enum Keys {
        static let certificate = "security.device.certificate"
        static let publicKey = "security.device.publicKey"
        static let certificateOwnerUID = "security.device.certificateOwnerUid"
    }

    enum SecurityError: Error {
        case authRequired
        case invalidResponse
        case invalidCertificate
        case ownerMismatch
    }

    private struct StoredCertificate {
        let bytes: Data
        let roleCertificate: RoleCertificate
    }
}

struct DeviceCertificate {
    let privateKey: SecKey
    let publicKeyBase64: String
    let certificateBytes: Data
    let roleCertificate: RoleCertificate
}
