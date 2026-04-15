//
//  DeviceIdentityStore.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import Foundation
import Security

final class DeviceIdentityStore {
    static let shared = DeviceIdentityStore()

    private let tag = "com.crisisconnect.device.identity".data(using: .utf8)!
    private let keySize = 256

    private init() {}

    func loadPrivateKey() -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let item else { return nil }
        return unsafeBitCast(item, to: SecKey.self)
    }

    func getOrCreatePrivateKey() throws -> SecKey {
        if let existing = loadPrivateKey() {
            return existing
        }
        if let secureKey = try? generatePrivateKey(useSecureEnclave: true) {
            return secureKey
        }
        return try generatePrivateKey(useSecureEnclave: false)
    }

    func publicKeyDataX509(for privateKey: SecKey) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw DeviceIdentityError.invalidKey
        }
        var error: Unmanaged<CFError>?
        guard let x963 = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            throw DeviceIdentityError.exportFailed
        }
        guard x963.count == 65, x963.first == 0x04 else {
            throw DeviceIdentityError.invalidKey
        }
        return wrapX509PublicKey(x963)
    }

    func signPayload(_ payload: Data, with privateKey: SecKey) throws -> Data {
        let algorithm = SecKeyAlgorithm.ecdsaSignatureMessageX962SHA256
        guard SecKeyIsAlgorithmSupported(privateKey, .sign, algorithm) else {
            throw DeviceIdentityError.signatureUnsupported
        }
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(
            privateKey,
            algorithm,
            payload as CFData,
            &error
        ) as Data? else {
            throw DeviceIdentityError.signatureFailed
        }
        return signature
    }

    private func generatePrivateKey(useSecureEnclave: Bool) throws -> SecKey {
        var attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: keySize,
            kSecPrivateKeyAttrs as String: privateKeyAttributes()
        ]

        if useSecureEnclave {
            attributes[kSecAttrTokenID as String] = kSecAttrTokenIDSecureEnclave
        }

        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw DeviceIdentityError.creationFailed
        }
        return privateKey
    }

    private func privateKeyAttributes() -> [String: Any] {
        var attributes: [String: Any] = [
            kSecAttrIsPermanent as String: true,
            kSecAttrApplicationTag as String: tag
        ]
        if let access = SecAccessControlCreateWithFlags(
            nil,
            kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            [.privateKeyUsage],
            nil
        ) {
            attributes[kSecAttrAccessControl as String] = access
        } else {
            attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        }
        return attributes
    }

    private func wrapX509PublicKey(_ x963: Data) -> Data {
        let prefix: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        ]
        var data = Data(prefix)
        data.append(x963)
        return data
    }

    enum DeviceIdentityError: Error {
        case creationFailed
        case exportFailed
        case invalidKey
        case signatureUnsupported
        case signatureFailed
    }
}
