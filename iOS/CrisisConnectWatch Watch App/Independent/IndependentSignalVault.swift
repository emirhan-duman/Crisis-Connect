#if CC_WATCH_INDEPENDENT || os(iOS)
import Foundation
import LibSignalClient

/// Ratchet mutations and outgoing ciphertext / received plaintext commit in one encrypted archive.
/// Restoration has no network side effects. Keys belong to this watch, never the paired phone.
@MainActor final class IndependentSignalVault {
    private let file: IndependentWatchJournalFile
    private var poisoned = false
    private struct Archive: Codable {
        var version = 1
        var identity: Data
        var registration: UInt32
        var records: [String: Data] = [:]
        var outbox: [String: Message] = [:]
        var inbox: [String: Message] = [:]
    }
    struct Message: Codable, Equatable {
        let id: String
        let uid: String
        let device: UInt32
        let ctype: String
        let ciphertext: String
        let clear: Data
        var state: String = "uncertain"
    }
    init(namespace: String) throws {
        guard UUID(uuidString: namespace) != nil else { throw IndependentWatchError.invalid }
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        file = IndependentWatchJournalFile(url: directory.appendingPathComponent("signal-\(namespace).enc"), service: "crisis.watch.signal.\(namespace)")
        if try file.readData() == nil {
            try file.writeData(JSONEncoder().encode(Archive(identity: IdentityKeyPair.generate().serialize(), registration: UInt32.random(in: 1...16383))))
        }
        _ = try load()
    }
    private func load() throws -> Archive {
        guard !poisoned, let bytes = try file.readData() else { throw IndependentWatchError.uncertain }
        let archive = try JSONDecoder().decode(Archive.self, from: bytes)
        guard archive.version == 1, archive.outbox.count <= 512, archive.inbox.count <= 512 else { throw IndependentWatchError.invalid }
        return archive
    }
    private func transaction<T>(_ body: (Store) throws -> T) throws -> T {
        let store = Store(try load())
        let result = try body(store)
        do { try file.writeData(JSONEncoder().encode(store.archive)) }
        catch { poisoned = true; throw error }
        return result
    }
    func identityKey() throws -> String { try IdentityKeyPair(bytes: load().identity).publicKey.serialize().base64EncodedString() }
    /// Invoke only after authenticated phone approval / verified device binding. Never replace a pin implicitly.
    func pinPeer(uid: String, device: UInt32, identity: String) throws {
        try transaction { store in
            let address = try Self.address(uid, device), key = try IdentityKey(bytes: Self.bytes(identity))
            _ = try store.saveIdentity(key, for: address, context: NullContext())
        }
    }
    func publicBundle(device: UInt32) throws -> [String: Any] {
        guard (2...127).contains(device) else { throw IndependentWatchError.invalid }
        return try transaction { store in
            let context = NullContext(), identity = try store.identityKeyPair(context: context)
            if store.archive.records["signed:1"] == nil {
                let ec = PrivateKey.generate(), kem = KEMKeyPair.generate(), now = UInt64(Date().timeIntervalSince1970 * 1000)
                let signed = try SignedPreKeyRecord(id: 1, timestamp: now, privateKey: ec, signature: identity.privateKey.generateSignature(message: ec.publicKey.serialize()))
                let kyber = try KyberPreKeyRecord(id: 1, timestamp: now, keyPair: kem, signature: identity.privateKey.generateSignature(message: kem.publicKey.serialize()))
                try store.storeSignedPreKey(signed, id: 1, context: context)
                try store.storeKyberPreKey(kyber, id: 1, context: context)
            }
            let ec = try store.loadSignedPreKey(id: 1, context: context), kem = try store.loadKyberPreKey(id: 1, context: context)
            return ["registrationId": store.archive.registration, "deviceId": device, "identityKey": identity.publicKey.serialize().base64EncodedString(),
                    "signedPreKey": try ec.publicKey().serialize().base64EncodedString(), "signedSignature": ec.signature.base64EncodedString(),
                    "kyberPreKey": try kem.keyPair().publicKey.serialize().base64EncodedString(), "kyberSignature": kem.signature.base64EncodedString()]
        }
    }
    func prepare(id: String, uid: String, device: UInt32, bundle: [String: Any]?, clear: Data) throws -> Message {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id, (1...2048).contains(clear.count) else { throw IndependentWatchError.invalid }
        return try transaction { store in
            let context = NullContext(), address = try Self.address(uid, device)
            guard store.archive.outbox.count < 512, store.archive.outbox[id] == nil,
                  let pinned = try store.identity(for: address, context: context) else { throw IndependentWatchError.invalid }
            if try store.loadSession(for: address, context: context)?.hasCurrentState != true {
                guard let b = bundle, (b["deviceId"] as? NSNumber)?.uint32Value == device,
                      let registration = (b["registrationId"] as? NSNumber)?.uint32Value else { throw IndependentWatchError.invalid }
                func data(_ key: String) throws -> Data { guard let s = b[key] as? String else { throw IndependentWatchError.invalid }; return try Self.bytes(s) }
                let identity = try IdentityKey(bytes: data("identityKey"))
                guard identity == pinned else { throw IndependentWatchError.invalid }
                let prekey = try PreKeyBundle(registrationId: registration, deviceId: device, signedPrekeyId: 1,
                    signedPrekey: PublicKey(data("signedPreKey")), signedPrekeySignature: data("signedSignature"), identity: identity,
                    kyberPrekeyId: 1, kyberPrekey: KEMPublicKey(data("kyberPreKey")), kyberPrekeySignature: data("kyberSignature"))
                try processPreKeyBundle(prekey, for: address, sessionStore: store, identityStore: store, context: context)
            }
            let ciphertext = try signalEncrypt(message: clear, for: address, sessionStore: store, identityStore: store, context: context)
            let row = Message(id: id, uid: uid, device: device, ctype: ciphertext.messageType == .preKey ? "prekey" : "signal", ciphertext: ciphertext.serialize().base64EncodedString(), clear: clear)
            store.archive.outbox[id] = row
            return row
        }
    }
    func receive(id: String, uid: String, device: UInt32, ctype: String, ciphertext: String) throws -> Data {
        guard UUID(uuidString: id)?.uuidString.lowercased() == id, ciphertext.count <= 24000 else { throw IndependentWatchError.invalid }
        return try transaction { store in
            let context = NullContext(), address = try Self.address(uid, device)
            guard try store.identity(for: address, context: context) != nil else { throw IndependentWatchError.invalid }
            if let old = store.archive.inbox[id] {
                guard old.uid == uid, old.device == device, old.ctype == ctype, old.ciphertext == ciphertext else { throw IndependentWatchError.invalid }
                return old.clear
            }
            guard store.archive.inbox.count < 512 else { throw IndependentWatchError.invalid }
            let bytes = try Self.bytes(ciphertext), clear: Data
            switch ctype {
            case "prekey": clear = try signalDecryptPreKey(message: PreKeySignalMessage(bytes: bytes), from: address, sessionStore: store, identityStore: store, preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: context)
            case "signal": clear = try signalDecrypt(message: SignalMessage(bytes: bytes), from: address, sessionStore: store, identityStore: store, context: context)
            default: throw IndependentWatchError.invalid
            }
            guard (1...2048).contains(clear.count) else { throw IndependentWatchError.invalid }
            store.archive.inbox[id] = Message(id: id, uid: uid, device: device, ctype: ctype, ciphertext: ciphertext, clear: clear, state: "delivered")
            return clear
        }
    }
    func incoming() throws -> [String: Message] { try load().inbox }
    func outgoing() throws -> [String: Message] { try load().outbox }
    func reconcile(id: String, state: String) throws {
        try transaction { store in
            let ranks = ["uncertain", "accepted", "delivered", "read"]
            guard let next = ranks.firstIndex(of: state), next > 0, var row = store.archive.outbox[id], let old = ranks.firstIndex(of: row.state) else { throw IndependentWatchError.invalid }
            if next > old { row.state = state; store.archive.outbox[id] = row }
        }
    }
    nonisolated private static func bytes(_ s: String) throws -> Data { guard let d = Data(base64Encoded: s) else { throw IndependentWatchError.invalid }; return d }
    private static func address(_ uid: String, _ device: UInt32) throws -> ProtocolAddress {
        guard uid.range(of: "^[A-Za-z0-9]{1,128}$", options: .regularExpression) != nil, (1...127).contains(device) else { throw IndependentWatchError.invalid }
        return try ProtocolAddress(name: uid, deviceId: device)
    }
    // LibSignal callbacks mutate only this transaction's private copy. A throw discards every mutation.
    private final class Store: IdentityKeyStore, SessionStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore {
        var archive: Archive
        init(_ archive: Archive) { self.archive = archive }
        func key(_ a: ProtocolAddress) -> String { "\(a.name).\(a.deviceId)" }
        func required(_ key: String) throws -> Data { guard let d = archive.records[key] else { throw IndependentWatchError.invalid }; return d }
        func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair { try IdentityKeyPair(bytes: archive.identity) }
        func localRegistrationId(context: StoreContext) throws -> UInt32 { archive.registration }
        func identity(for a: ProtocolAddress, context: StoreContext) throws -> IdentityKey? { try archive.records["identity:\(key(a))"].map { try IdentityKey(bytes: $0) } }
        func saveIdentity(_ identity: IdentityKey, for a: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
            let k = "identity:\(key(a))"
            guard archive.records[k] == nil || archive.records[k] == identity.serialize() else { throw IndependentWatchError.invalid }
            archive.records[k] = identity.serialize(); return .newOrUnchanged
        }
        func isTrustedIdentity(_ identity: IdentityKey, for a: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool { archive.records["identity:\(key(a))"] == identity.serialize() }
        func loadSession(for a: ProtocolAddress, context: StoreContext) throws -> SessionRecord? { try archive.records["session:\(key(a))"].map { try SessionRecord(bytes: $0) } }
        func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] { try addresses.map { try SessionRecord(bytes: required("session:\(key($0))")) } }
        func storeSession(_ record: SessionRecord, for a: ProtocolAddress, context: StoreContext) throws { archive.records["session:\(key(a))"] = record.serialize() }
        func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord { try PreKeyRecord(bytes: required("pre:\(id)")) }
        func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws { archive.records["pre:\(id)"] = record.serialize() }
        func removePreKey(id: UInt32, context: StoreContext) throws { archive.records.removeValue(forKey: "pre:\(id)") }
        func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord { try SignedPreKeyRecord(bytes: required("signed:\(id)")) }
        func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws { archive.records["signed:\(id)"] = record.serialize() }
        func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord { try KyberPreKeyRecord(bytes: required("kyber:\(id)")) }
        func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws { archive.records["kyber:\(id)"] = record.serialize() }
        func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext) throws {
            let key = "used:\(id):\(signedPreKeyId):\(baseKey.serialize().base64EncodedString())"
            guard archive.records[key] == nil else { throw IndependentWatchError.invalid }; archive.records[key] = Data([1])
        }
    }
}

#endif
