//
//  SecureLocalStore.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import CryptoKit
import Foundation
import Security

final class SecureLocalStore {
    nonisolated static let shared = SecureLocalStore()

    private let stateQueue = DispatchQueue(label: "secure.local.store.state")
    private var cachedAesKeyBase64: String?
    private var cachedFileEncryptionKeyBase64: String?

    private init() {}

    func getOrCreateAesKeyBase64() -> String {
        if let cached = cachedAesKeyBase64,
           let data = Data(base64Encoded: cached),
           data.count == 32 {
            return cached
        }
        if let existing = KeychainStore.loadString(Keys.aesKey),
           let data = Data(base64Encoded: existing),
           data.count == 32 {
            stateQueue.sync {
                cachedAesKeyBase64 = existing
            }
            return existing
        }

        var keyData = Data(count: 32)
        let status = keyData.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, 32, bytes.baseAddress!)
        }
        if status != errSecSuccess {
            return ""
        }
        let base64 = keyData.base64EncodedString()
        stateQueue.sync {
            cachedAesKeyBase64 = base64
        }
        KeychainStore.saveString(base64, key: Keys.aesKey)
        if let persisted = KeychainStore.loadString(Keys.aesKey),
           let persistedData = Data(base64Encoded: persisted),
           persistedData.count == 32 {
            stateQueue.sync {
                cachedAesKeyBase64 = persisted
            }
            return persisted
        }
        return base64
    }

    func getOrCreateFileEncryptionKeyBase64() -> String {
        let cached = stateQueue.sync { cachedFileEncryptionKeyBase64 }
        if let cached,
           let data = Data(base64Encoded: cached),
           data.count == 32 {
            return cached
        }
        let base64 = LocalEncryptedFileStore.loadOrCreateFileEncryptionKeyBase64()
        stateQueue.sync {
            cachedFileEncryptionKeyBase64 = base64
        }
        return base64
    }

    func saveAesKey(base64: String) {
        let sanitized = base64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = Data(base64Encoded: sanitized, options: [.ignoreUnknownCharacters]),
              data.count == 32 else {
            return
        }
        let canonical = data.base64EncodedString()
        stateQueue.sync {
            cachedAesKeyBase64 = canonical
        }
        KeychainStore.saveString(canonical, key: Keys.aesKey)
    }

    func saveRole(_ role: String) {
        guard let normalized = RescueRoleAccess.normalizedRole(role) else {
            clearRole()
            return
        }
        let previous = loadRole()?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        KeychainStore.saveString(normalized, key: Keys.role)
        if normalized != previous {
            NotificationCenter.default.post(name: .rescueRoleDidChange, object: nil)
        }
    }

    func loadRole() -> String? {
        KeychainStore.loadString(Keys.role)
    }

    func clearRole() {
        let previous = loadRole()?.trimmingCharacters(in: .whitespacesAndNewlines)
        KeychainStore.delete(Keys.role)
        if let previous, !previous.isEmpty {
            NotificationCenter.default.post(name: .rescueRoleDidChange, object: nil)
        }
    }

    func saveUid(_ uid: String) {
        KeychainStore.saveString(uid, key: Keys.uid)
    }

    func loadUid() -> String? {
        KeychainStore.loadString(Keys.uid)
    }

    func clearUid() {
        KeychainStore.delete(Keys.uid)
    }

    func getOrCreateBroadcastId() -> String {
        if let existing = KeychainStore.loadString(Keys.broadcastId) {
            let trimmed = existing.trimmingCharacters(in: .whitespacesAndNewlines)
            if let normalized = normalizeBroadcastId(trimmed) {
                if normalized != trimmed {
                    KeychainStore.saveString(normalized, key: Keys.broadcastId)
                }
                return normalized
            }
        }

        var random = Data(count: 12)
        let status = random.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, bytes.count, bytes.baseAddress!)
        }
        guard status == errSecSuccess else {
            return "unknown"
        }
        let hex = random.map { String(format: "%02x", $0) }.joined()
        let value = "cc-\(hex)"
        KeychainStore.saveString(value, key: Keys.broadcastId)
        return value
    }

    func getOrCreateRescueDeviceId() -> String {
        if let existing = KeychainStore.loadString(Keys.rescueDeviceId) {
            let trimmed = existing.trimmingCharacters(in: .whitespacesAndNewlines)
            if let normalized = normalizeBroadcastId(trimmed) {
                if normalized != trimmed {
                    KeychainStore.saveString(normalized, key: Keys.rescueDeviceId)
                }
                return normalized
            }
        }

        let seeded = getOrCreateBroadcastId()
        KeychainStore.saveString(seeded, key: Keys.rescueDeviceId)
        return seeded
    }

    func broadcastIdServiceData() -> Data? {
        guard let normalized = normalizeBroadcastId(getOrCreateBroadcastId()) else {
            return nil
        }
        let hex = String(normalized.dropFirst(3))
        return decodeHex(hex)
    }

    func getOrCreateP2pDeviceId() -> String {
        if let existing = KeychainStore.loadString(Keys.p2pDeviceId) {
            let trimmed = existing.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                return trimmed
            }
        }

        let hex = randomHex(byteCount: 12) ?? UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
        let value = "p2p-\(hex)"
        KeychainStore.saveString(value, key: Keys.p2pDeviceId)
        return value
    }

    func getOrCreateP2pSessionCode() -> String {
        if let existing = KeychainStore.loadString(Keys.p2pSessionCode) {
            let trimmed = existing.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if !trimmed.isEmpty {
                return trimmed
            }
        }

        let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        let bytes = randomBytes(count: 6) ?? Data([1, 2, 3, 4, 5, 6])
        var value = ""
        value.reserveCapacity(6)
        for byte in bytes.prefix(6) {
            value.append(alphabet[Int(byte) % alphabet.count])
        }
        KeychainStore.saveString(value, key: Keys.p2pSessionCode)
        return value
    }

    func saveP2pSessionCode(_ sessionCode: String) {
        let trimmed = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !trimmed.isEmpty else { return }
        KeychainStore.saveString(trimmed, key: Keys.p2pSessionCode)
    }

    func saveP2pDeviceId(_ deviceId: String) {
        let trimmed = deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        KeychainStore.saveString(trimmed, key: Keys.p2pDeviceId)
    }

    private func randomBytes(count: Int) -> Data? {
        var data = Data(count: count)
        let status = data.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, bytes.count, bytes.baseAddress!)
        }
        guard status == errSecSuccess else { return nil }
        return data
    }

    private func randomHex(byteCount: Int) -> String? {
        randomBytes(count: byteCount)?.map { String(format: "%02x", $0) }.joined()
    }

    private func decodeHex(_ hex: String) -> Data? {
        guard hex.count.isMultiple(of: 2) else { return nil }
        var data = Data()
        data.reserveCapacity(hex.count / 2)

        var index = hex.startIndex
        while index < hex.endIndex {
            let nextIndex = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<nextIndex], radix: 16) else {
                return nil
            }
            data.append(byte)
            index = nextIndex
        }
        return data
    }

    private func normalizeBroadcastId(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return nil }

        let cleaned = trimmed
            .replacingOccurrences(of: "ccid:", with: "")
            .replacingOccurrences(of: "cc-", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleaned.isEmpty else { return nil }

        let hex: String
        switch cleaned.count {
        case 16:
            // Legacy iOS values used 8 random bytes. Pad deterministically to Android's 12-byte format.
            hex = cleaned + "00000000"
        case 24:
            hex = cleaned
        default:
            return nil
        }

        guard hex.allSatisfy({ $0.isHexDigit }) else { return nil }
        return "cc-\(hex)"
    }

    private enum Keys {
        static let aesKey = "local.aesKey"
        static let fileEncryptionKey = "local.fileEncryptionKey"
        static let role = "local.role"
        static let uid = "local.uid"
        static let broadcastId = "local.broadcastId"
        static let rescueDeviceId = "local.rescueDeviceId"
        static let p2pDeviceId = "local.p2pDeviceId"
        static let p2pSessionCode = "local.p2pSessionCode"
    }
}

extension Notification.Name {
    static let rescueRoleDidChange = Notification.Name("rescueRoleDidChange")
}

struct LocalEncryptedFilePayload {
    let data: Data
    let wasEncrypted: Bool
}

enum LocalEncryptedFileStore {
    private nonisolated static let envelopeMagic = Data("CCFILE1".utf8)
    private nonisolated static let fileEncryptionKeyStorageKey = "local.fileEncryptionKey"
    private nonisolated static let writeOptions: Data.WritingOptions = [
        .atomic,
        .completeFileProtectionUntilFirstUserAuthentication
    ]

    nonisolated static func loadOrCreateFileEncryptionKeyBase64() -> String {
        if let existing = KeychainStore.loadString(fileEncryptionKeyStorageKey),
           let data = Data(base64Encoded: existing, options: [.ignoreUnknownCharacters]),
           data.count == 32 {
            return data.base64EncodedString()
        }

        var keyData = Data(count: 32)
        let status = keyData.withUnsafeMutableBytes { bytes in
            SecRandomCopyBytes(kSecRandomDefault, 32, bytes.baseAddress!)
        }
        guard status == errSecSuccess else {
            return ""
        }

        let base64 = keyData.base64EncodedString()
        KeychainStore.saveString(base64, key: fileEncryptionKeyStorageKey)
        if let persisted = KeychainStore.loadString(fileEncryptionKeyStorageKey),
           let data = Data(base64Encoded: persisted, options: [.ignoreUnknownCharacters]),
           data.count == 32 {
            return data.base64EncodedString()
        }
        return base64
    }

    nonisolated static func read(from url: URL) throws -> LocalEncryptedFilePayload {
        let fileData = try Data(contentsOf: url)
        guard fileData.starts(with: envelopeMagic) else {
            return LocalEncryptedFilePayload(data: fileData, wasEncrypted: false)
        }

        let combined = fileData.dropFirst(envelopeMagic.count)
        guard !combined.isEmpty else {
            throw LocalEncryptedFileStoreError.invalidEnvelope
        }
        let sealedBox = try AES.GCM.SealedBox(combined: combined)
        let plaintext = try AES.GCM.open(sealedBox, using: symmetricKey())
        return LocalEncryptedFilePayload(data: plaintext, wasEncrypted: true)
    }

    nonisolated static func write(_ plaintext: Data, to url: URL) throws {
        let directory = url.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var payload = envelopeMagic
        guard let combined = try AES.GCM.seal(plaintext, using: symmetricKey()).combined else {
            throw LocalEncryptedFileStoreError.invalidEnvelope
        }
        payload.append(combined)
        try payload.write(to: url, options: writeOptions)
    }

    private nonisolated static func symmetricKey() throws -> SymmetricKey {
        let base64 = loadOrCreateFileEncryptionKeyBase64()
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]),
              data.count == 32 else {
            throw LocalEncryptedFileStoreError.invalidKey
        }
        return SymmetricKey(data: data)
    }

    enum LocalEncryptedFileStoreError: Error {
        case invalidEnvelope
        case invalidKey
    }
}
