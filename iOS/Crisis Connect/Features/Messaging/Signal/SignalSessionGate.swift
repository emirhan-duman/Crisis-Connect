//
//  SignalSessionGate.swift
//  Crisis Connect
//
//  FS-6: decides — and drives — whether a message to/from a peer uses the forward-secret v3
//  (Signal Double Ratchet) path or the legacy v2 ECIES envelope, and owns the anti-downgrade pin.
//  iOS port of Android's `SignalSessionGate` + `SignalMessageTransport`; both platforms speak the
//  identical wire (doc v==2, ctype "prekey"|"signal", inner bytes = the shared content codec).
//
//  Downgrade pin: `hasV3Session` is the pin. Once a v3 session exists with a peer every message to
//  them MUST be v3, and a received v1 message from them is refused (stripped-v3 downgrade attempt).
//
//  All calls hit the blocking file store — never call on the main thread.
//

import Foundation
import LibSignalClient

enum SignalTransportError: Error {
    case unknownCtype(String)
    case noSession(String)
}

final class SignalSessionGate {
    static let shared = SignalSessionGate()

    // Every gate call shares one file-backed store; a racing push handler + Firestore listener (or
    // two concurrent sends) would read-modify-write the same session and corrupt the ratchet.
    // Serialize all ratchet-mutating operations process-wide — ops are sub-millisecond and internet
    // messaging is low-throughput, so a single lock is ample.
    private static let cryptoLock = NSRecursiveLock()
    private static let deviceId: UInt32 = 1
    private static let negativeProbeTtl: TimeInterval = 6 * 60 * 60
    private static let probeKeyPrefix = "cc.signal.probe."

    private let store: SignalProtocolFileStore?
    private let client = InternetMessagingClient()

    private init() {
        store = try? SignalProtocolFileStore()
    }

    private func address(_ peerUid: String) -> ProtocolAddress {
        // ProtocolAddress construction cannot fail for a plain uid string.
        try! ProtocolAddress(name: peerUid, deviceId: Self.deviceId)
    }

    func hasV3Session(_ peerUid: String) -> Bool {
        guard let store, !peerUid.isEmpty else { return false }
        Self.cryptoLock.lock(); defer { Self.cryptoLock.unlock() }
        guard let record = try? store.loadSession(for: address(peerUid), context: NullContext()) else { return false }
        return record.hasCurrentState
    }

    /// Ensure we can send v3 to `peerUid`. `forceEstablish` true (durable messages) fetches a
    /// bundle and establishes a session when none exists; false (ephemeral pulses like typing)
    /// only ever uses an already-established session.
    func ensureOutbound(_ peerUid: String, forceEstablish: Bool) async -> Bool {
        guard store != nil, !peerUid.isEmpty else { return false }
        if hasV3Session(peerUid) { return true }
        guard forceEstablish, !isNegativeProbeFresh(peerUid) else { return false }

        let bundle: SignalPreKeyBundleWire?
        do {
            bundle = try await client.fetchSignalPreKeyBundle(targetUid: peerUid)
        } catch {
            MessagingDiagLog.log("prekey bundle fetch failed for \(peerUid.prefix(8)): \(String(describing: error))")
            return false
        }
        guard let bundle else {
            // Peer never published Signal prekeys → v2-only for now; throttle re-probing.
            markNegativeProbe(peerUid)
            return false
        }
        do {
            try establishOutbound(peerUid, wire: bundle)
            return true
        } catch {
            MessagingDiagLog.log("failed to establish v3 session with \(peerUid.prefix(8)): \(String(describing: error))")
            return false
        }
    }

    private func establishOutbound(_ peerUid: String, wire: SignalPreKeyBundleWire) throws {
        guard let store else { return }
        Self.cryptoLock.lock(); defer { Self.cryptoLock.unlock() }
        // Re-check under the lock: a concurrent sender may have established while we fetched the
        // bundle; re-processing it would reset the ratchet and reuse message counters.
        if hasV3Session(peerUid) { return }

        let identity = try IdentityKey(bytes: Self.b64(wire.identityKeyBase64))
        let signedPreKey = try PublicKey(Self.b64(wire.signedPreKeyBase64))
        let kyberKey = try KEMPublicKey(Self.b64(wire.kyberPreKeyBase64))

        let bundle: PreKeyBundle
        if let preKeyId = wire.preKeyId, let preKeyB64 = wire.preKeyBase64 {
            bundle = try PreKeyBundle(
                registrationId: UInt32(wire.registrationId),
                deviceId: UInt32(wire.deviceId),
                prekeyId: UInt32(preKeyId),
                prekey: try PublicKey(Self.b64(preKeyB64)),
                signedPrekeyId: UInt32(wire.signedPreKeyId),
                signedPrekey: signedPreKey,
                signedPrekeySignature: Self.b64(wire.signedPreKeySignatureBase64),
                identity: identity,
                kyberPrekeyId: UInt32(wire.kyberPreKeyId),
                kyberPrekey: kyberKey,
                kyberPrekeySignature: Self.b64(wire.kyberPreKeySignatureBase64)
            )
        } else {
            bundle = try PreKeyBundle(
                registrationId: UInt32(wire.registrationId),
                deviceId: UInt32(wire.deviceId),
                signedPrekeyId: UInt32(wire.signedPreKeyId),
                signedPrekey: signedPreKey,
                signedPrekeySignature: Self.b64(wire.signedPreKeySignatureBase64),
                identity: identity,
                kyberPrekeyId: UInt32(wire.kyberPreKeyId),
                kyberPrekey: kyberKey,
                kyberPrekeySignature: Self.b64(wire.kyberPreKeySignatureBase64)
            )
        }
        try processPreKeyBundle(
            bundle,
            for: address(peerUid),
            sessionStore: store,
            identityStore: store,
            context: NullContext()
        )
    }

    /// Encrypt `content` for `peerUid`. Returns the wire ctype label + base64 ciphertext — the
    /// exact doc fields Android writes.
    func encrypt(_ peerUid: String, content: InternetMessageContent) throws -> (ctype: String, ciphertextBase64: String) {
        guard let store else { throw SignalTransportError.noSession(peerUid) }
        Self.cryptoLock.lock(); defer { Self.cryptoLock.unlock() }
        let message = try signalEncrypt(
            message: InternetE2eEnvelope.encodeContent(content),
            for: address(peerUid),
            sessionStore: store,
            identityStore: store,
            context: NullContext()
        )
        let ctype = message.messageType == .preKey ? "prekey" : "signal"
        return (ctype, message.serialize().base64EncodedString())
    }

    /// Decrypt a v3 doc from `peerUid`. Throws on forgery/downgrade/duplicate — callers treat
    /// `SignalError.duplicatedMessage` as the benign "already delivered" signal.
    func decrypt(_ peerUid: String, ctypeLabel: String, ciphertextBase64: String) throws -> InternetMessageContent {
        guard let store else { throw SignalTransportError.noSession(peerUid) }
        guard let ciphertext = Data(base64Encoded: ciphertextBase64) else {
            throw SignalTransportError.unknownCtype("bad base64")
        }
        Self.cryptoLock.lock(); defer { Self.cryptoLock.unlock() }
        let plaintext: Data
        switch ctypeLabel {
        case "prekey":
            plaintext = try signalDecryptPreKey(
                message: try PreKeySignalMessage(bytes: ciphertext),
                from: address(peerUid),
                sessionStore: store,
                identityStore: store,
                preKeyStore: store,
                signedPreKeyStore: store,
                kyberPreKeyStore: store,
                context: NullContext()
            )
        case "signal":
            plaintext = try signalDecrypt(
                message: try SignalMessage(bytes: ciphertext),
                from: address(peerUid),
                sessionStore: store,
                identityStore: store,
                context: NullContext()
            )
        default:
            throw SignalTransportError.unknownCtype(ctypeLabel)
        }
        return try InternetE2eEnvelope.decodeContent(plaintext)
    }

    /// The forward-secret (v3) safety number for `peerUid`, or nil when no v3 identity is held yet
    /// (fall back to the v2 P-256 safety number). Blocking: 5200 fingerprint iterations + file IO.
    func safetyNumber(localUid: String, peerUid: String) -> String? {
        guard let store, !localUid.isEmpty, !peerUid.isEmpty else { return nil }
        Self.cryptoLock.lock(); defer { Self.cryptoLock.unlock() }
        guard let remoteKey = try? store.identity(for: address(peerUid), context: NullContext()),
              let localPair = try? SignalIdentity.shared.identityKeyPair() else { return nil }
        // Same construction as Android's SignalSafetyNumber: Signal's numeric fingerprint over the
        // two Curve25519 identity keys, version 0, 5200 iterations, formatted 12×5 digits.
        guard let fingerprint = try? NumericFingerprintGenerator(iterations: 5200).create(
            version: 0,
            localIdentifier: Data(localUid.utf8),
            localKey: localPair.publicKey,
            remoteIdentifier: Data(peerUid.utf8),
            remoteKey: remoteKey.publicKey
        ) else { return nil }
        let digits = fingerprint.displayable.formatted
        var grouped: [String] = []
        var index = digits.startIndex
        while index < digits.endIndex {
            let end = digits.index(index, offsetBy: 5, limitedBy: digits.endIndex) ?? digits.endIndex
            grouped.append(String(digits[index..<end]))
            index = end
        }
        return grouped.joined(separator: " ")
    }

    // MARK: - Negative-probe throttle (peer without prekeys → don't refetch every message)

    private func isNegativeProbeFresh(_ peerUid: String) -> Bool {
        let at = UserDefaults.standard.double(forKey: Self.probeKeyPrefix + peerUid)
        return at > 0 && Date().timeIntervalSince1970 - at < Self.negativeProbeTtl
    }

    private func markNegativeProbe(_ peerUid: String) {
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.probeKeyPrefix + peerUid)
    }

    /// A peer just proved v3 support → clear any stale negative mark.
    func clearNegativeProbe(_ peerUid: String) {
        UserDefaults.standard.removeObject(forKey: Self.probeKeyPrefix + peerUid)
    }

    private static func b64(_ s: String) throws -> Data {
        guard let d = Data(base64Encoded: s) else { throw SignalTransportError.unknownCtype("bad base64 field") }
        return d
    }
}
