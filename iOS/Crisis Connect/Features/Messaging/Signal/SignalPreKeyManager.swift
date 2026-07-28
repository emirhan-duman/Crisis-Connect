//
//  SignalPreKeyManager.swift
//  Crisis Connect
//
//  FS-6: generates, locally persists, and publishes the device's Signal-protocol prekeys — the
//  iOS port of Android's `SignalPreKeyManager`, speaking to the same `publishSignalPreKeys` /
//  `checkSignalPreKeys` callables. Every key handed to the backend is stored in the protocol store
//  FIRST so a peer's session opened against it can always be decrypted (the backend copy is
//  public-only).
//

import Foundation
import LibSignalClient

final class SignalPreKeyManager {
    private let store: SignalProtocolFileStore
    private let client: InternetMessagingClient
    private static let defaults = UserDefaults.standard

    private static let keyPreKeyId = "cc.signal.next_prekey_id"
    private static let keySignedId = "cc.signal.next_signed_prekey_id"
    private static let keyKyberId = "cc.signal.next_kyber_prekey_id"
    private static let keyLastResortKyberId = "cc.signal.last_resort_kyber_id"
    private static let maxKeyId: Int = 0xFFFFFF
    private static let targetPool = 100
    private static let lowWatermark = 20
    private static let idLock = NSLock()

    init(store: SignalProtocolFileStore, client: InternetMessagingClient = InternetMessagingClient()) {
        self.store = store
        self.client = client
    }

    /// The published last-resort Kyber prekey id — the one `markKyberPreKeyUsed` must never delete.
    static func lastResortKyberId() -> UInt32 {
        UInt32(defaults.integer(forKey: keyLastResortKyberId))
    }

    /// Publish/rotate meta (identity, signed prekey, last-resort Kyber) and top up the one-time
    /// pools when the server reports them low. Mirrors Android's `ensurePublished`; call from the
    /// messaging bootstrap, off the main thread, best-effort.
    func ensurePublished() async throws {
        // Reinstall-reset recovery: iOS keeps the Signal identity in the keychain (survives app
        // deletion) but this prekey store lives in Application Support (wiped on deletion). A device
        // that lost its store but kept its identity keeps serving peers stale, undecryptable prekeys
        // — every inbound v3 message fails with missingRecord(<kyber id>) forever, because the
        // backend only clears its pool on an identity change. Detect it (local counters are fresh yet
        // the server still reports our prekeys published) and rotate the identity so the publish below
        // wipes the stale server pool. `keySignedId == 0` is only true on a genuinely fresh store —
        // an existing consistent install always has a non-zero counter, so normal users never rotate.
        let localFresh = Self.defaults.integer(forKey: Self.keySignedId) == 0
        if localFresh, let inv = try? await client.checkSignalPreKeys(), inv.published == true {
            NSLog("SignalPreKeyManager: fresh store + server has stale prekeys -> rotating identity")
            SignalIdentity.shared.rotate()
        }

        let identityPair = try SignalIdentity.shared.identityKeyPair()
        let context = NullContext()

        let signed = try generateSignedPreKey(identityPair)
        try store.storeSignedPreKey(signed, id: signed.id, context: context)
        let lastResort = try generateKyberPreKey(identityPair)
        try store.storeKyberPreKey(lastResort, id: lastResort.id, context: context)
        Self.defaults.set(Int(lastResort.id), forKey: Self.keyLastResortKyberId)

        let inventory = try? await client.checkSignalPreKeys()
        let needEc = Self.topUpCount(inventory?.ecCount, published: inventory?.published)
        let needKyber = Self.topUpCount(inventory?.kyberCount, published: inventory?.published)

        var oneTimeEc: [PreKeyRecord] = []
        for _ in 0..<needEc {
            let record = try generateOneTimePreKey()
            try store.storePreKey(record, id: record.id, context: context)
            oneTimeEc.append(record)
        }
        var oneTimeKyber: [KyberPreKeyRecord] = []
        for _ in 0..<needKyber {
            let record = try generateKyberPreKey(identityPair)
            try store.storeKyberPreKey(record, id: record.id, context: context)
            oneTimeKyber.append(record)
        }

        try await client.publishSignalPreKeys(
            registrationId: Int(SignalIdentity.shared.registrationId()),
            identityKeyBase64: identityPair.publicKey.serialize().base64EncodedString(),
            signedPreKey: PreKeyUpload(
                keyId: Int(signed.id),
                publicKeyBase64: try signed.publicKey().serialize().base64EncodedString(),
                signatureBase64: signed.signature.base64EncodedString()
            ),
            lastResortKyberPreKey: try Self.kyberUpload(lastResort),
            preKeys: try oneTimeEc.map { record in
                PreKeyUpload(
                    keyId: Int(record.id),
                    publicKeyBase64: try record.publicKey().serialize().base64EncodedString(),
                    signatureBase64: nil
                )
            },
            kyberPreKeys: try oneTimeKyber.map { try Self.kyberUpload($0) }
        )
    }

    private static func kyberUpload(_ record: KyberPreKeyRecord) throws -> PreKeyUpload {
        PreKeyUpload(
            keyId: Int(record.id),
            publicKeyBase64: try record.keyPair().publicKey.serialize().base64EncodedString(),
            signatureBase64: record.signature.base64EncodedString()
        )
    }

    private static func topUpCount(_ serverCount: Int?, published: Bool?) -> Int {
        let current = (published == true) ? (serverCount ?? 0) : 0
        return current >= lowWatermark ? 0 : max(targetPool - current, 0)
    }

    private func generateSignedPreKey(_ identity: IdentityKeyPair) throws -> SignedPreKeyRecord {
        let keyPair = PrivateKey.generate()
        let signature = identity.privateKey.generateSignature(message: keyPair.publicKey.serialize())
        return try SignedPreKeyRecord(
            id: Self.nextId(Self.keySignedId),
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            privateKey: keyPair,
            signature: signature
        )
    }

    private func generateKyberPreKey(_ identity: IdentityKeyPair) throws -> KyberPreKeyRecord {
        let keyPair = KEMKeyPair.generate()
        let signature = identity.privateKey.generateSignature(message: keyPair.publicKey.serialize())
        return try KyberPreKeyRecord(
            id: Self.nextId(Self.keyKyberId),
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            keyPair: keyPair,
            signature: signature
        )
    }

    private func generateOneTimePreKey() throws -> PreKeyRecord {
        try PreKeyRecord(id: Self.nextId(Self.keyPreKeyId), privateKey: PrivateKey.generate())
    }

    /// Monotonic id in [1, 0xFFFFFF] (libsignal's Medium range), wrapping to 1 — same as Android.
    private static func nextId(_ key: String) -> UInt32 {
        idLock.lock(); defer { idLock.unlock() }
        let next = defaults.integer(forKey: key) + 1
        let wrapped = (1...maxKeyId).contains(next) ? next : 1
        defaults.set(wrapped, forKey: key)
        return UInt32(wrapped)
    }
}

/// Wire shape of one uploaded prekey — matches Android `PreKeyUpload.toMap()`.
struct PreKeyUpload {
    let keyId: Int
    let publicKeyBase64: String
    let signatureBase64: String?

    func toMap() -> [String: Any] {
        var m: [String: Any] = ["keyId": keyId, "publicKey": publicKeyBase64]
        if let signatureBase64 { m["signature"] = signatureBase64 }
        return m
    }
}
