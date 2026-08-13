//
//  MlsStateVault.swift
//  Crisis Connect
//
//  Local-only persistence for OpenMLS traffic secrets. A ThisDeviceOnly Keychain key wraps an
//  authenticated, atomic, backup-excluded state file protected until the device is unlocked.
//

import CryptoKit
import Foundation
import Security

enum MlsStateVaultError: Error {
    case invalidContext
    case invalidSnapshot
    case unfinishedAdvance
    case keychain(OSStatus)
}

enum MlsStateVault {
    private static let legacyService = "com.auralis.crisisconnect.mls-state.v1"
    private static let wrappingService = "com.auralis.crisisconnect.mls-wrapping.v2"
    private static let journalService = "com.auralis.crisisconnect.mls-journal.v2"
    private static let wrappingAccount = "local-aes-gcm-v1"
    private static let maxSnapshotBytes = AuthorityMlsDurableStateCodec.maxDurableStateBytes
    private static let fileMagic = Data([0x43, 0x43, 0x4d, 0x4c, 0x53, 0x46, 0x02])
    private static let journalMagic = Data([0x43, 0x43, 0x4d, 0x4c, 0x53, 0x4a, 0x02])
    private static let protectedDataMagic = Data([0x43, 0x43, 0x50, 0x44, 0x01])
    private static let maxProtectedDataBytes = 24 * 1024 * 1024
    private static let lock = NSLock()

    static func load(context: String) throws -> Data? {
        try validate(context: context)
        lock.lock()
        defer { lock.unlock() }
        if try itemExists(query: journalQuery(context: context)) ||
            itemExists(query: legacyJournalQuery(context: context)) {
            throw MlsStateVaultError.unfinishedAdvance
        }
        let url = try stateURL(context: context)
        if FileManager.default.fileExists(atPath: url.path) {
            let encoded = try Data(contentsOf: url, options: .mappedIfSafe)
            return try open(encoded, context: context)
        }
        // One-time compatibility read. The next successful mutation writes the v2 encrypted file
        // and removes this oversized legacy Keychain value.
        return try loadLegacySnapshot(context: context)
    }

    /// Writes a durable marker before a native MLS operation can advance a sender/receiver ratchet.
    static func beginAdvance(context: String) throws {
        try validate(context: context)
        lock.lock()
        defer { lock.unlock() }
        let query = journalQuery(context: context)
        if try itemExists(query: query) || itemExists(query: legacyJournalQuery(context: context)) {
            throw MlsStateVaultError.unfinishedAdvance
        }
        var insert = query
        insert[kSecValueData as String] = journalMagic
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(insert as CFDictionary, nil)
        guard status == errSecSuccess else { throw MlsStateVaultError.keychain(status) }
    }

    static func save(_ snapshot: Data, context: String) throws {
        try validate(context: context)
        guard !snapshot.isEmpty, snapshot.count <= maxSnapshotBytes else {
            throw MlsStateVaultError.invalidSnapshot
        }
        lock.lock()
        defer { lock.unlock() }
        let encoded = try seal(snapshot, context: context)
        let url = try stateURL(context: context)
        try encoded.write(to: url, options: [.atomic, .completeFileProtection])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedURL = url
        try protectedURL.setResourceValues(values)
        let legacyDelete = SecItemDelete(legacySnapshotQuery(context: context) as CFDictionary)
        guard legacyDelete == errSecSuccess || legacyDelete == errSecItemNotFound else {
            throw MlsStateVaultError.keychain(legacyDelete)
        }
        // State replacement happens first. A crash before journal deletion remains deliberately
        // fail-closed; a successful return means the advanced ratchet and cleared marker are durable.
        let journalDelete = SecItemDelete(journalQuery(context: context) as CFDictionary)
        guard journalDelete == errSecSuccess else { throw MlsStateVaultError.keychain(journalDelete) }
    }

    static func delete(context: String) throws {
        try validate(context: context)
        lock.lock()
        defer { lock.unlock() }
        let url = try stateURL(context: context)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        for query in [
            legacySnapshotQuery(context: context),
            legacyJournalQuery(context: context),
            journalQuery(context: context)
        ] {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw MlsStateVaultError.keychain(status)
            }
        }
    }

    /// Device-only encrypted application data, separate from the MLS ratchet journal.
    static func loadProtectedData(context: String) throws -> Data? {
        try validate(context: context)
        lock.lock()
        defer { lock.unlock() }
        let url = try protectedDataURL(context: context)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let encoded = try Data(contentsOf: url, options: .mappedIfSafe)
        guard encoded.count > protectedDataMagic.count + 28,
              encoded.count <= protectedDataMagic.count + maxProtectedDataBytes + 28,
              encoded.starts(with: protectedDataMagic) else { throw MlsStateVaultError.invalidSnapshot }
        do {
            let box = try AES.GCM.SealedBox(combined: encoded.dropFirst(protectedDataMagic.count))
            let opened = try AES.GCM.open(
                box,
                using: try wrappingKey(),
                authenticating: protectedDataAad(context)
            )
            guard !opened.isEmpty, opened.count <= maxProtectedDataBytes else {
                throw MlsStateVaultError.invalidSnapshot
            }
            return opened
        } catch let error as MlsStateVaultError {
            throw error
        } catch {
            throw MlsStateVaultError.invalidSnapshot
        }
    }

    static func saveProtectedData(_ data: Data, context: String) throws {
        try validate(context: context)
        guard !data.isEmpty, data.count <= maxProtectedDataBytes else { throw MlsStateVaultError.invalidSnapshot }
        lock.lock()
        defer { lock.unlock() }
        let box = try AES.GCM.seal(
            data,
            using: try wrappingKey(),
            authenticating: protectedDataAad(context)
        )
        guard let combined = box.combined else { throw MlsStateVaultError.invalidSnapshot }
        let url = try protectedDataURL(context: context)
        try (protectedDataMagic + combined).write(to: url, options: [.atomic, .completeFileProtection])
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var protectedURL = url
        try protectedURL.setResourceValues(values)
    }

    private static func seal(_ snapshot: Data, context: String) throws -> Data {
        let box = try AES.GCM.seal(snapshot, using: try wrappingKey(), authenticating: aad(context))
        guard let combined = box.combined else { throw MlsStateVaultError.invalidSnapshot }
        return fileMagic + combined
    }

    private static func open(_ encoded: Data, context: String) throws -> Data {
        guard encoded.count > fileMagic.count + 12 + 16,
              encoded.count <= fileMagic.count + maxSnapshotBytes + 28,
              encoded.prefix(fileMagic.count) == fileMagic else {
            throw MlsStateVaultError.invalidSnapshot
        }
        do {
            let box = try AES.GCM.SealedBox(combined: encoded.dropFirst(fileMagic.count))
            let snapshot = try AES.GCM.open(box, using: try wrappingKey(), authenticating: aad(context))
            guard !snapshot.isEmpty, snapshot.count <= maxSnapshotBytes else {
                throw MlsStateVaultError.invalidSnapshot
            }
            return snapshot
        } catch let error as MlsStateVaultError {
            throw error
        } catch {
            throw MlsStateVaultError.invalidSnapshot
        }
    }

    private static func wrappingKey() throws -> SymmetricKey {
        let query = wrappingKeyQuery()
        var read = query
        read[kSecReturnData as String] = true
        read[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(read as CFDictionary, &item)
        if status == errSecSuccess {
            guard let data = item as? Data, data.count == 32 else { throw MlsStateVaultError.invalidSnapshot }
            return SymmetricKey(data: data)
        }
        guard status == errSecItemNotFound else { throw MlsStateVaultError.keychain(status) }
        var bytes = Data(count: 32)
        let randomStatus = bytes.withUnsafeMutableBytes {
            SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!)
        }
        guard randomStatus == errSecSuccess else { throw MlsStateVaultError.keychain(randomStatus) }
        var insert = query
        insert[kSecValueData as String] = bytes
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let add = SecItemAdd(insert as CFDictionary, nil)
        guard add == errSecSuccess else { throw MlsStateVaultError.keychain(add) }
        return SymmetricKey(data: bytes)
    }

    private static func stateURL(context: String) throws -> URL {
        let root = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ).appendingPathComponent("MlsStateV2", isDirectory: true)
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete]
        )
        var rootValues = URLResourceValues()
        rootValues.isExcludedFromBackup = true
        var protectedRoot = root
        try protectedRoot.setResourceValues(rootValues)
        let digest = SHA256.hash(data: Data(context.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent("\(digest).bin", isDirectory: false)
    }

    private static func protectedDataURL(context: String) throws -> URL {
        let root = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ).appendingPathComponent("ProtectedMessagingV1", isDirectory: true)
        try FileManager.default.createDirectory(
            at: root,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.complete]
        )
        var rootValues = URLResourceValues()
        rootValues.isExcludedFromBackup = true
        var protectedRoot = root
        try protectedRoot.setResourceValues(rootValues)
        let digest = SHA256.hash(data: Data(context.utf8)).map { String(format: "%02x", $0) }.joined()
        return root.appendingPathComponent("\(digest).bin", isDirectory: false)
    }

    private static func aad(_ context: String) -> Data {
        Data("cc-mls-state-file:v2:\(context)".utf8)
    }

    private static func protectedDataAad(_ context: String) -> Data {
        Data("cc-protected-local-data:v1:\(context)".utf8)
    }

    private static func loadLegacySnapshot(context: String) throws -> Data? {
        var query = legacySnapshotQuery(context: context)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw MlsStateVaultError.keychain(status) }
        guard let data = item as? Data, !data.isEmpty, data.count <= maxSnapshotBytes else {
            throw MlsStateVaultError.invalidSnapshot
        }
        return data
    }

    private static func validate(context: String) throws {
        guard !context.isEmpty, context.utf8.count <= 512 else { throw MlsStateVaultError.invalidContext }
    }

    private static func wrappingKeyQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: wrappingService,
            kSecAttrAccount as String: wrappingAccount,
            kSecAttrSynchronizable as String: false
        ]
    }

    private static func legacySnapshotQuery(context: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: legacyService,
            kSecAttrAccount as String: context,
            kSecAttrSynchronizable as String: false
        ]
    }

    private static func legacyJournalQuery(context: String) -> [String: Any] {
        var query = legacySnapshotQuery(context: context)
        query[kSecAttrAccount as String] = "\(context)#advance"
        return query
    }

    private static func journalQuery(context: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: journalService,
            kSecAttrAccount as String: context,
            kSecAttrSynchronizable as String: false
        ]
    }

    private static func itemExists(query: [String: Any]) throws -> Bool {
        let status = SecItemCopyMatching(query as CFDictionary, nil)
        if status == errSecSuccess { return true }
        if status == errSecItemNotFound { return false }
        throw MlsStateVaultError.keychain(status)
    }
}
