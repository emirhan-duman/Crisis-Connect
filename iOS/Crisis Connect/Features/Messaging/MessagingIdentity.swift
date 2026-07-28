//
//  MessagingIdentity.swift
//  Crisis Connect
//
//  The device's long-term P-256 identity key for E2E internet messaging (Faz 4 iOS port of the
//  Android `MessagingIdentity`). Kept in the Secure Enclave when available (non-exportable, hardware
//  ECDH), otherwise a software key persisted in the Keychain. Only the public key ever leaves the
//  device; ECDH is performed in place via `deriveSharedSecret`, so the raw private key is never
//  exposed — this is what makes the static-static authentication in `InternetE2eEnvelope` sound.
//

import Foundation
import CryptoKit
import Security

final class MessagingIdentity {
    static let shared = MessagingIdentity()

    private let service = "com.crisisconnect.messaging.identity"
    private let account = "identity-p256-agree-v1"
    private let lock = NSLock()
    private var cached: Stored?

    init() {}

    /// The identity public key as base64 SPKI — the exact wire form Android/web publish and pin.
    func publicKeyBase64() throws -> String {
        MessagingKeyCodec.encodePublicKey(try loadOrCreate().publicKey)
    }

    func publicKey() throws -> P256.KeyAgreement.PublicKey {
        try loadOrCreate().publicKey
    }

    /// Raw ECDH shared secret with `peerPublicKey`, computed inside the enclave/software key without
    /// exposing the private key. Matches Android `MessagingIdentity.deriveSharedSecret` byte-for-byte.
    func deriveSharedSecret(_ peerPublicKey: P256.KeyAgreement.PublicKey) throws -> Data {
        let secret = try loadOrCreate().sharedSecret(with: peerPublicKey)
        return secret.withUnsafeBytes { Data($0) }
    }

    /// True when the identity key is backed by the Secure Enclave (hardware, non-exportable).
    func isHardwareBacked() -> Bool {
        (try? loadOrCreate())?.isHardwareBacked ?? false
    }

    // MARK: - Key material

    private enum Stored {
        case enclave(SecureEnclave.P256.KeyAgreement.PrivateKey)
        case software(P256.KeyAgreement.PrivateKey)

        var publicKey: P256.KeyAgreement.PublicKey {
            switch self {
            case .enclave(let key): return key.publicKey
            case .software(let key): return key.publicKey
            }
        }

        var isHardwareBacked: Bool {
            if case .enclave = self { return true }
            return false
        }

        func sharedSecret(with peer: P256.KeyAgreement.PublicKey) throws -> SharedSecret {
            switch self {
            case .enclave(let key): return try key.sharedSecretFromKeyAgreement(with: peer)
            case .software(let key): return try key.sharedSecretFromKeyAgreement(with: peer)
            }
        }
    }

    private enum StorageKind: UInt8 {
        case enclave = 0x01
        case software = 0x02
    }

    private func loadOrCreate() throws -> Stored {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }

        if let blob = readKeychain(), let restored = decode(blob) {
            cached = restored
            return restored
        }

        let created = try create()
        try persist(created)
        cached = created
        return created
    }

    private func create() throws -> Stored {
        if SecureEnclave.isAvailable {
            if let enclaveKey = try? SecureEnclave.P256.KeyAgreement.PrivateKey() {
                return .enclave(enclaveKey)
            }
        }
        return .software(P256.KeyAgreement.PrivateKey())
    }

    private func decode(_ blob: Data) -> Stored? {
        guard let first = blob.first, let kind = StorageKind(rawValue: first) else { return nil }
        let body = blob.dropFirst()
        switch kind {
        case .enclave:
            guard let key = try? SecureEnclave.P256.KeyAgreement.PrivateKey(dataRepresentation: body) else {
                return nil
            }
            return .enclave(key)
        case .software:
            guard let key = try? P256.KeyAgreement.PrivateKey(rawRepresentation: body) else {
                return nil
            }
            return .software(key)
        }
    }

    private func persist(_ stored: Stored) throws {
        var blob = Data()
        switch stored {
        case .enclave(let key):
            blob.append(StorageKind.enclave.rawValue)
            blob.append(key.dataRepresentation)
        case .software(let key):
            blob.append(StorageKind.software.rawValue)
            blob.append(key.rawRepresentation)
        }
        writeKeychain(blob)
    }

    // MARK: - Keychain (device-only, survives restarts, excluded from backups)

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    private func readKeychain() -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess else { return nil }
        return item as? Data
    }

    private func writeKeychain(_ data: Data) {
        SecItemDelete(baseQuery() as CFDictionary)
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(query as CFDictionary, nil)
    }
}
