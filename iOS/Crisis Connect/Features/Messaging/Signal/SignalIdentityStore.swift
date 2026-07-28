//
//  SignalIdentityStore.swift
//  Crisis Connect
//
//  FS-6: the device's long-term Signal-protocol identity (Curve25519 key pair + registration id)
//  and the persistent protocol store backing libsignal's Double Ratchet. iOS port of Android's
//  `SignalIdentity` + `AndroidSignalProtocolStore`.
//
//  Identity: unlike the P-256 `MessagingIdentity` (Secure Enclave), libsignal needs the raw
//  Curve25519 private key for the ratchet, so the serialized pair lives in the Keychain
//  (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly — messages must decrypt while backgrounded,
//  and the key must never migrate to another device via backup).
//
//  Store: one file per record under Application Support/SignalStore with complete-until-first-auth
//  file protection. Volume is tiny (a handful of sessions + ~100 prekeys), and libsignal reads
//  whole records anyway, so files beat dragging in a database dependency. All calls are blocking —
//  callers stay off the main thread (SignalSessionGate enforces this).
//

import Foundation
import LibSignalClient
import Security

final class SignalIdentity {
    static let shared = SignalIdentity()

    private let service = "com.crisisconnect.messaging.signal"
    private let account = "identity-curve25519-v1"
    private let lock = NSLock()
    private var cached: (pair: IdentityKeyPair, registrationId: UInt32)?

    private init() {}

    func identityKeyPair() throws -> IdentityKeyPair {
        try loadOrCreate().pair
    }

    func registrationId() throws -> UInt32 {
        try loadOrCreate().registrationId
    }

    /// Drops the persisted identity so the next access generates a fresh one. Used only on a
    /// reinstall-reset: iOS keeps this key in the keychain (survives app deletion) while the prekey
    /// store in Application Support does not, so a device that lost its store but kept its identity
    /// serves peers stale, undecryptable prekeys forever (the backend only clears its pool on an
    /// identity change). A fresh identity makes the next publishSignalPreKeys wipe that stale pool.
    func rotate() {
        lock.lock(); defer { lock.unlock() }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        cached = nil
    }

    private func loadOrCreate() throws -> (pair: IdentityKeyPair, registrationId: UInt32) {
        lock.lock(); defer { lock.unlock() }
        if let cached { return cached }

        if let blob = try readKeychain() {
            // Layout: [u16 BE pairLen][pair bytes][u32 BE registrationId]
            guard blob.count > 6 else { throw SignalStoreError.corruptIdentity }
            let pairLen = Int(blob[0]) << 8 | Int(blob[1])
            guard blob.count == 2 + pairLen + 4 else { throw SignalStoreError.corruptIdentity }
            let pair = try IdentityKeyPair(bytes: blob.subdata(in: 2..<(2 + pairLen)))
            let rid = blob.suffix(4).reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
            let value = (pair, rid)
            cached = value
            return value
        }

        let pair = IdentityKeyPair.generate()
        // Same range libsignal uses: a random 14-bit non-zero registration id.
        let rid = UInt32.random(in: 1...0x3FFF)
        let pairBytes = pair.serialize()
        var blob = Data()
        blob.append(UInt8((pairBytes.count >> 8) & 0xFF))
        blob.append(UInt8(pairBytes.count & 0xFF))
        blob.append(pairBytes)
        for shift in stride(from: 24, through: 0, by: -8) {
            blob.append(UInt8((rid >> UInt32(shift)) & 0xFF))
        }
        try writeKeychain(blob)
        let value = (pair, rid)
        cached = value
        return value
    }

    private func readKeychain() throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw SignalStoreError.keychain(status)
        }
        return data
    }

    private func writeKeychain(_ data: Data) throws {
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemAdd(add as CFDictionary, nil)
        guard status == errSecSuccess else { throw SignalStoreError.keychain(status) }
    }
}

enum SignalStoreError: Error {
    case corruptIdentity
    case keychain(OSStatus)
    case missingRecord(String)
}

/// File-backed libsignal protocol store. One file per record; atomic writes; complete-until-
/// first-auth protection. Directory layout under Application Support/SignalStore/:
///   sessions/<uid>.1, identities/<uid>.1, prekeys/<id>, signed-prekeys/<id>, kyber-prekeys/<id>
final class SignalProtocolFileStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore {
    private let root: URL

    init() throws {
        let base = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        )
        root = base.appendingPathComponent("SignalStore", isDirectory: true)
        for dir in ["sessions", "identities", "prekeys", "signed-prekeys", "kyber-prekeys"] {
            try FileManager.default.createDirectory(
                at: root.appendingPathComponent(dir, isDirectory: true),
                withIntermediateDirectories: true,
                attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
            )
        }
    }

    // File names embed the address; uids are Firebase uids (URL-safe) and ids are integers, but
    // percent-encode defensively so a hostile "uid" can never escape the directory.
    private func url(_ dir: String, _ name: String) -> URL {
        let safe = name.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? name
        return root.appendingPathComponent(dir, isDirectory: true).appendingPathComponent(safe)
    }

    private func read(_ dir: String, _ name: String) -> Data? {
        try? Data(contentsOf: url(dir, name))
    }

    private func write(_ dir: String, _ name: String, _ data: Data) throws {
        try data.write(to: url(dir, name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    private func addressKey(_ address: ProtocolAddress) -> String {
        "\(address.name).\(address.deviceId)"
    }

    // MARK: - IdentityKeyStore

    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair {
        try SignalIdentity.shared.identityKeyPair()
    }

    func localRegistrationId(context: StoreContext) throws -> UInt32 {
        try SignalIdentity.shared.registrationId()
    }

    func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        let key = addressKey(address)
        let previous = read("identities", key)
        let next = identity.serialize()
        try write("identities", key, next)
        if previous == nil || previous == next {
            return .newOrUnchanged
        }
        // Content-free breadcrumb: the peer's safety number just changed (reinstall/reset).
        NSLog("SignalIdentityStore: identity re-pinned for %@ (peer reinstalled or reset)", key)
        return .replacedExisting
    }

    func isTrustedIdentity(
        _ identity: IdentityKey,
        for address: ProtocolAddress,
        direction: Direction,
        context: StoreContext
    ) throws -> Bool {
        // TOFU with non-blocking re-pin, same policy as Android (2026-07-14): a CHANGED key means
        // the peer reinstalled/reset. Accept it — libsignal follows up with saveIdentity, which
        // re-pins and logs. Refusing dropped the peer's re-handshake (UntrustedIdentityError)
        // forever: an unrecoverable two-way deadlock, since neither side has a recovery path once
        // the old session is stale. The change stays visible as a changed safety number in the UI.
        if let stored = read("identities", addressKey(address)), stored != identity.serialize() {
            NSLog("SignalIdentityStore: identity CHANGED for %@ — accepting for re-pin", addressKey(address))
        }
        return true
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        guard let data = read("identities", addressKey(address)) else { return nil }
        return try IdentityKey(bytes: data)
    }

    // MARK: - PreKeyStore

    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let data = read("prekeys", String(id)) else {
            throw SignalStoreError.missingRecord("prekey \(id)")
        }
        return try PreKeyRecord(bytes: data)
    }

    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws {
        try write("prekeys", String(id), record.serialize())
    }

    func removePreKey(id: UInt32, context: StoreContext) throws {
        try? FileManager.default.removeItem(at: url("prekeys", String(id)))
    }

    // MARK: - SignedPreKeyStore

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let data = read("signed-prekeys", String(id)) else {
            throw SignalStoreError.missingRecord("signed prekey \(id)")
        }
        return try SignedPreKeyRecord(bytes: data)
    }

    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try write("signed-prekeys", String(id), record.serialize())
    }

    // MARK: - KyberPreKeyStore

    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let data = read("kyber-prekeys", String(id)) else {
            throw SignalStoreError.missingRecord("kyber prekey \(id)")
        }
        return try KyberPreKeyRecord(bytes: data)
    }

    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws {
        try write("kyber-prekeys", String(id), record.serialize())
    }

    func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext) throws {
        // One-time Kyber prekeys are deleted on use; the published LAST-RESORT key must survive.
        // Android keeps the same split via its DAO; here the manager stores the last-resort id.
        if id != SignalPreKeyManager.lastResortKyberId() {
            try? FileManager.default.removeItem(at: url("kyber-prekeys", String(id)))
        }
    }

    // MARK: - SessionStore

    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        guard let data = read("sessions", addressKey(address)) else { return nil }
        return try SessionRecord(bytes: data)
    }

    func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        try addresses.compactMap { try loadSession(for: $0, context: context) }
    }

    func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        try write("sessions", addressKey(address), record.serialize())
    }
}
