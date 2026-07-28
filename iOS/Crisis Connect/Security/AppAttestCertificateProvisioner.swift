//
//  AppAttestCertificateProvisioner.swift
//  Crisis Connect
//
//  Mirrors the Android CertificateProvisioningFlow. The hardened backend
//  `issueRoleCertificate` callable requires a full attestation handshake; this
//  type performs the iOS half of it:
//
//    1. Register the rescue device (Firestore `rescueDevices/{cc-…}`).
//    2. Request a single-use attestation challenge from the backend.
//    3. Run Apple App Attest, binding the attestation to the SecureEnclave
//       device key via clientDataHash = SHA-256(challenge || devicePublicKey).
//    4. Hand back the payload `issueRoleCertificate` expects.
//

import Foundation
import CryptoKit
import DeviceCheck
import FirebaseFirestore
import FirebaseFunctions

final class AppAttestCertificateProvisioner {
    static let shared = AppAttestCertificateProvisioner()

    private lazy var functions: Functions = {
        FirebaseRuntime.ensureConfigured()
        return Functions.functions(region: "us-central1")
    }()
    private lazy var firestore: Firestore = {
        FirebaseRuntime.ensureConfigured()
        return Firestore.firestore()
    }()
    private let attestService = DCAppAttestService.shared

    private init() {}

    enum ProvisioningError: LocalizedError {
        case appAttestUnsupported
        case challengeMissing
        case invalidChallenge

        var errorDescription: String? {
            switch self {
            case .appAttestUnsupported:
                return "App Attest is not supported on this device."
            case .challengeMissing:
                return "The backend did not return an attestation challenge."
            case .invalidChallenge:
                return "The attestation challenge could not be decoded."
            }
        }
    }

    // MARK: - Device id

    /// Returns the stable `cc-<24 hex>` rescue device id, generating and
    /// persisting one in the Keychain on first use. Matches Android's
    /// `LocalKeyStorage.getOrCreateRescueDeviceId`.
    func getOrCreateDeviceId() -> String {
        if let existing = KeychainStore.loadString(Keys.deviceId),
           Self.isValidDeviceId(existing) {
            return existing
        }
        let generated = Self.generateDeviceId()
        KeychainStore.saveString(generated, key: Keys.deviceId)
        return generated
    }

    // MARK: - Device ownership (rescueDevices/{deviceId}.uid)

    /// The uid that last successfully registered the stored device id, or "" if
    /// unknown. Used to decide whether a permission-denied write means the doc is
    /// owned by a different account. Mirrors LocalKeyStorage.getRescueDeviceOwnerUid.
    private func storedDeviceOwnerUid() -> String {
        KeychainStore.loadString(Keys.deviceOwnerUid)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private func markDeviceOwner(_ uid: String) {
        let normalized = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        if normalized.isEmpty {
            KeychainStore.delete(Keys.deviceOwnerUid)
        } else {
            KeychainStore.saveString(normalized, key: Keys.deviceOwnerUid)
        }
    }

    /// Generates and persists a fresh `cc-<24 hex>` device id (same format as
    /// getOrCreateDeviceId), recording the owner uid. Mirrors
    /// LocalKeyStorage.rotateRescueDeviceId.
    private func rotateDeviceId(ownerUid: String) -> String {
        let generated = Self.generateDeviceId()
        KeychainStore.saveString(generated, key: Keys.deviceId)
        markDeviceOwner(ownerUid)
        return generated
    }

    // MARK: - Full flow

    /// Registers the device, fetches a challenge, runs App Attest and returns
    /// the `issueRoleCertificate` callable payload.
    func makeIssuancePayload(
        deviceId: String,
        publicKeyBase64: String,
        publicKeyDer: Data,
        uid: String
    ) async throws -> [String: Any] {
        // Registration may rotate the device id (see registerRescueDevice) when the
        // rescueDevices/{deviceId} doc is owned by a different account, so everything
        // downstream — challenge, attestation binding, payload — must use the id that
        // was actually written.
        let effectiveDeviceId = try await registerRescueDevice(deviceId: deviceId, uid: uid)
        let challenge = try await requestChallenge(deviceId: effectiveDeviceId)
        let attestation = try await attest(challenge: challenge, publicKeyDer: publicKeyDer)
        return [
            "platform": "ios",
            "deviceId": effectiveDeviceId,
            "publicKey": publicKeyBase64,
            "attestationChallenge": challenge.base64EncodedString(),
            "appAttestKeyId": attestation.keyId,
            "appAttestObject": attestation.object.base64EncodedString(),
            "deviceBrand": "Apple",
            "deviceModel": Self.getDeviceModel()
        ]
    }

    // MARK: - Steps

    /// Registers the rescue device doc and returns the device id that was actually
    /// written. If the first write is rejected with Firestore permission-denied AND
    /// the locally stored owner uid differs from the current uid, the device id is
    /// rotated to a fresh `cc-<hex>` value and registration is retried once — this
    /// recovers rescue provisioning after an account switch on the same device, where
    /// the old id's doc is owned by the previous account. Mirrors Android
    /// RescueDeviceRegistry.registerLocalDevice + firestore.rules ownership check.
    @discardableResult
    private func registerRescueDevice(deviceId: String, uid: String) async throws -> String {
        let normalizedUid = uid.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            try await writeRescueDeviceDoc(deviceId: deviceId, uid: normalizedUid)
            markDeviceOwner(normalizedUid)
            return deviceId
        } catch {
            guard shouldRotateDeviceIdAfterFailure(uid: normalizedUid, error: error) else {
                throw error
            }
        }

        let rotatedDeviceId = rotateDeviceId(ownerUid: normalizedUid)
        try await writeRescueDeviceDoc(deviceId: rotatedDeviceId, uid: normalizedUid)
        markDeviceOwner(normalizedUid)
        return rotatedDeviceId
    }

    private func writeRescueDeviceDoc(deviceId: String, uid: String) async throws {
        try await firestore.collection("rescueDevices").document(deviceId).setData(
            [
                "deviceId": deviceId,
                "uid": uid,
                "updatedAt": FieldValue.serverTimestamp(),
            ],
            merge: true
        )
    }

    /// Rotate only when the write was denied *and* the stored owner is not the current
    /// uid (matching Android): a denial while we already own the doc won't be fixed by
    /// a new id, so it is rethrown.
    private func shouldRotateDeviceIdAfterFailure(uid: String, error: Error) -> Bool {
        let nsError = error as NSError
        guard nsError.domain == FirestoreErrorDomain,
              nsError.code == FirestoreErrorCode.permissionDenied.rawValue else {
            return false
        }
        return storedDeviceOwnerUid() != uid
    }

    private func requestChallenge(deviceId: String) async throws -> Data {
        let result = try await functions
            .httpsCallable("requestAttestationChallenge")
            .call(["deviceId": deviceId])
        guard let data = result.data as? [String: Any],
              let challengeBase64 = data["challenge"] as? String else {
            throw ProvisioningError.challengeMissing
        }
        guard let bytes = Self.decodeBase64(challengeBase64) else {
            throw ProvisioningError.invalidChallenge
        }
        return bytes
    }

    private struct Attestation {
        let keyId: String
        let object: Data
    }

    private func attest(challenge: Data, publicKeyDer: Data) async throws -> Attestation {
        guard attestService.isSupported else {
            throw ProvisioningError.appAttestUnsupported
        }
        let keyId = try await attestService.generateKey()
        // clientDataHash = SHA-256(challenge || devicePublicKeyDer). The backend
        // recomputes the same composite hash (boundData = the SecureEnclave key)
        // and matches it against the credential cert's nonce extension, so the
        // two MUST stay byte-identical.
        var hasher = SHA256()
        hasher.update(data: challenge)
        hasher.update(data: publicKeyDer)
        let clientDataHash = Data(hasher.finalize())
        let object = try await attestService.attestKey(keyId, clientDataHash: clientDataHash)
        return Attestation(keyId: keyId, object: object)
    }

    // MARK: - Helpers

    private static func generateDeviceId() -> String {
        var bytes = [UInt8](repeating: 0, count: 12)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status != errSecSuccess {
            for i in bytes.indices { bytes[i] = UInt8.random(in: 0...255) }
        }
        return "cc-" + bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func isValidDeviceId(_ value: String) -> Bool {
        value.range(of: "^cc-[0-9a-f]{24}$", options: .regularExpression) != nil
    }

    private static func getDeviceModel() -> String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machineMirror = Mirror(reflecting: systemInfo.machine)
        let identifier = machineMirror.children.reduce("") { identifier, element in
            guard let value = element.value as? Int8, value != 0 else { return identifier }
            return identifier + String(UnicodeScalar(UInt8(value)))
        }
        
        switch identifier {
        case "iPod9,1": return "iPod touch (7th generation)"
        case "iPhone8,1": return "iPhone 6s"
        case "iPhone8,2": return "iPhone 6s Plus"
        case "iPhone8,4": return "iPhone SE"
        case "iPhone9,1", "iPhone9,3": return "iPhone 7"
        case "iPhone9,2", "iPhone9,4": return "iPhone 7 Plus"
        case "iPhone10,1", "iPhone10,4": return "iPhone 8"
        case "iPhone10,2", "iPhone10,5": return "iPhone 8 Plus"
        case "iPhone10,3", "iPhone10,6": return "iPhone X"
        case "iPhone11,2": return "iPhone XS"
        case "iPhone11,4", "iPhone11,6": return "iPhone XS Max"
        case "iPhone11,8": return "iPhone XR"
        case "iPhone12,1": return "iPhone 11"
        case "iPhone12,3": return "iPhone 11 Pro"
        case "iPhone12,5": return "iPhone 11 Pro Max"
        case "iPhone12,8": return "iPhone SE (2nd generation)"
        case "iPhone13,1": return "iPhone 12 mini"
        case "iPhone13,2": return "iPhone 12"
        case "iPhone13,3": return "iPhone 12 Pro"
        case "iPhone13,4": return "iPhone 12 Pro Max"
        case "iPhone14,4": return "iPhone 13 mini"
        case "iPhone14,5": return "iPhone 13"
        case "iPhone14,2": return "iPhone 13 Pro"
        case "iPhone14,3": return "iPhone 13 Pro Max"
        case "iPhone14,6": return "iPhone SE (3rd generation)"
        case "iPhone14,7": return "iPhone 14"
        case "iPhone14,8": return "iPhone 14 Plus"
        case "iPhone15,2": return "iPhone 14 Pro"
        case "iPhone15,3": return "iPhone 14 Pro Max"
        case "iPhone15,4": return "iPhone 15"
        case "iPhone15,5": return "iPhone 15 Plus"
        case "iPhone16,1": return "iPhone 15 Pro"
        case "iPhone16,2": return "iPhone 15 Pro Max"
        case "iPhone17,1": return "iPhone 16 Pro"
        case "iPhone17,2": return "iPhone 16 Pro Max"
        case "iPhone17,3": return "iPhone 16"
        case "iPhone17,4": return "iPhone 16 Plus"
        case "iPad11,6", "iPad11,7": return "iPad (8th generation)"
        case "iPad12,1", "iPad12,2": return "iPad (9th generation)"
        case "iPad13,18", "iPad13,19": return "iPad (10th generation)"
        case "iPad13,4", "iPad13,5", "iPad13,6", "iPad13,7": return "iPad Pro (11-inch) (3rd generation)"
        case "iPad13,8", "iPad13,9", "iPad13,10", "iPad13,11": return "iPad Pro (12.9-inch) (5th generation)"
        case "iPad14,3", "iPad14,4": return "iPad Pro (11-inch) (4th generation)"
        case "iPad14,5", "iPad14,6": return "iPad Pro (12.9-inch) (6th generation)"
        case "iPad16,3", "iPad16,4": return "iPad Pro (11-inch) (M4)"
        case "iPad16,5", "iPad16,6": return "iPad Pro (13-inch) (M4)"
        case "x86_64", "arm64": return "Simulator"
        default: return identifier
        }
    }

    private static func decodeBase64(_ raw: String) -> Data? {
        let cleaned = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let data = Data(base64Encoded: cleaned) {
            return data
        }
        // Tolerate URL-safe / unpadded encodings.
        var normalized = cleaned
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 {
            normalized += String(repeating: "=", count: 4 - remainder)
        }
        return Data(base64Encoded: normalized)
    }

    private enum Keys {
        static let deviceId = "security.device.rescueDeviceId"
        static let deviceOwnerUid = "security.device.rescueDeviceOwnerUid"
    }
}
