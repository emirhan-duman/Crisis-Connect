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
import FirebaseCore

final class SecurityRepository {
    static let shared = SecurityRepository()

    private var functions: Functions? {
        FirebaseRuntime.ensureConfigured()
        return FirebaseApp.app() != nil ? Functions.functions(region: "us-central1") : nil
    }
    private var auth: Auth? {
        FirebaseRuntime.ensureConfigured()
        return FirebaseApp.app() != nil ? Auth.auth() : nil
    }
    private let deviceStore = DeviceIdentityStore.shared
    private let secureStore = SecureLocalStore.shared

    private init() {}

    func getOrFetchCertificate() async throws -> DeviceCertificate {
        let privateKey = try deviceStore.getOrCreatePrivateKey()
        let publicKeyData = try deviceStore.publicKeyDataX509(for: privateKey)
        let publicKeyBase64 = publicKeyData.base64EncodedString()
        let currentUID = auth?.currentUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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
                publicKeyData: publicKeyData,
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

    /// Proactive renewal, mirroring Android's CertificateRenewalWorker: once less than 24h of
    /// validity remains, silently re-provision. A failed renewal keeps the still-valid cached
    /// cert (the cache is only replaced after a successful issuance), so offline devices keep
    /// working until actual expiry. allowExpired matches Android: a just-lapsed certificate
    /// (still inside the offline grace window) is renewed rather than stranded.
    func renewCertificateIfExpiringSoon() async {
        let renewalLeadMillis: Int64 = 24 * 60 * 60 * 1000
        guard let stored = try? getStoredCertificateSync(allowExpired: true) else { return }
        let remainingMillis = stored.roleCertificate.expiresAtMillis - nowMillis()
        guard remainingMillis < renewalLeadMillis else { return }
        do {
            let privateKey = try deviceStore.getOrCreatePrivateKey()
            let publicKeyData = try deviceStore.publicKeyDataX509(for: privateKey)
            let publicKeyBase64 = publicKeyData.base64EncodedString()
            let currentUID = auth?.currentUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !currentUID.isEmpty else { return }
            let certificate = try await requestCertificate(
                publicKeyBase64: publicKeyBase64,
                publicKeyData: publicKeyData,
                currentUID: currentUID
            )
            persistCertificate(
                publicKeyBase64: publicKeyBase64,
                certificateBytes: try certificate.toStorageData(),
                ownerUID: certificate.ownerUID
            )
            NSLog("SecurityRepository: role certificate renewed ahead of expiry")
        } catch {
            NSLog("SecurityRepository: certificate renewal failed (kept cached): %@", String(describing: error))
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
        // Server-side revocation check first (mirrors Android warmUpCertificate): a
        // dashboard-revoked cert is wiped before the cache is consulted, so it can no
        // longer be short-circuited to by getOrFetchCertificate.
        await revalidateAgainstServer()
        return (try? await getOrFetchCertificate()) != nil
    }

    /// Confirms the cached certificate is still active server-side by calling the
    /// already-deployed `validateCertificate` callable (the same one Android calls).
    /// If the server reports `revoked` or `missing`, wipes local state — the stored
    /// certificate AND the attested device key — so the next `getOrFetchCertificate`
    /// re-provisions from scratch. Network-tolerant: a transient failure returns nil
    /// and never wipes the cert; only an explicit revoked/missing status does.
    /// Mirrors Android SecurityRepository.revalidateAgainstServer.
    @discardableResult
    func revalidateAgainstServer() async -> String? {
        let currentUID = resolveCurrentUID()
        guard !currentUID.isEmpty else { return nil }
        // Nothing to revalidate (or wipe) unless a certificate is actually stored.
        guard KeychainStore.load(Keys.certificate) != nil else { return nil }
        guard let functions else { return nil }

        let data: [String: Any]
        do {
            let result = try await functions.httpsCallable("validateCertificate").call([:])
            guard let parsed = result.data as? [String: Any] else { return nil }
            data = parsed
        } catch {
            NSLog("SecurityRepository: certificate revalidation call failed (kept cached): %@", String(describing: error))
            return nil
        }

        guard let rawStatus = data["status"] as? String else { return nil }
        let status = rawStatus.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        switch status {
        case "revoked", "missing":
            let reason = (data["revokedReason"] as? String) ?? status
            NSLog("SecurityRepository: server reported certificate status=%@ reason=%@; wiping", status, reason)
            wipeCertificate(reason: "server-status=\(status)")
        case "active":
            break
        default:
            NSLog("SecurityRepository: server reported certificate status=%@", status)
        }
        return status
    }

    func clearStoredCertificate() {
        KeychainStore.delete(Keys.certificate)
        KeychainStore.delete(Keys.publicKey)
        KeychainStore.delete(Keys.certificateOwnerUID)
    }

    /// Wipes both the cached certificate and the attested device key so the next
    /// `getOrFetchCertificate` starts a fresh provisioning flow with a brand-new key.
    /// Mirrors Android SecurityRepository.wipeCertificate.
    private func wipeCertificate(reason: String) {
        NSLog("SecurityRepository: wiping device-bound certificate: %@", reason)
        clearStoredCertificate()
        deleteAttestedDeviceKey()
    }

    /// Deletes the SecureEnclave EC device key created by DeviceIdentityStore so a
    /// fresh key (and therefore a fresh public key) is generated on the next
    /// provisioning attempt. The application tag MUST stay in sync with the
    /// `tag` DeviceIdentityStore uses to create the key.
    private func deleteAttestedDeviceKey() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: Data("com.crisisconnect.device.identity".utf8),
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom
        ]
        SecItemDelete(query as CFDictionary)
    }

    private func requestCertificate(
        publicKeyBase64: String,
        publicKeyData: Data,
        currentUID: String
    ) async throws -> RoleCertificate {
        // Full App Attest handshake (register device -> challenge -> attest),
        // mirroring Android's CertificateProvisioningFlow. The backend rejects
        // a bare {publicKey} payload with invalid-argument.
        let provisioner = AppAttestCertificateProvisioner.shared
        let deviceId = provisioner.getOrCreateDeviceId()
        let payload = try await provisioner.makeIssuancePayload(
            deviceId: deviceId,
            publicKeyBase64: publicKeyBase64,
            publicKeyDer: publicKeyData,
            uid: currentUID
        )
        // Registration may have rotated the device id (account switch on the same
        // device), so bind the check to the id that was actually issued, not the
        // pre-fetched one.
        let effectiveDeviceId = (payload["deviceId"] as? String) ?? deviceId
        guard let functions else {
            throw SecurityError.invalidResponse
        }
        let result = try await functions.httpsCallable("issueRoleCertificate").call(payload)
        guard let data = result.data as? [String: Any] else {
            throw SecurityError.invalidResponse
        }

        let certificate = try RoleCertificate.fromCallableResponse(data)
        guard certificate.isOwned(by: currentUID) else {
            throw SecurityError.ownerMismatch
        }
        guard certificate.isBound(to: effectiveDeviceId) else {
            throw SecurityError.invalidCertificate
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
        let authUID = auth?.currentUser?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
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
