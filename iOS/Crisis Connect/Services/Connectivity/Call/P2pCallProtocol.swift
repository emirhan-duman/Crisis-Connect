//
//  P2pCallProtocol.swift
//  Crisis Connect
//
//  Wire codec for voice calls carried over the P2P GATT chat link (0xCD00).
//
//  Two packet families share the link with regular chat traffic:
//
//  1. Signaling rides the existing encrypted chat envelope as new `call_*` chat kinds
//     (`P2pBleProtocol.chatKindCallOffer` etc.). Old app versions ignore unknown kinds
//     silently on both platforms, so mixed-version peers are unaffected.
//
//  2. Audio uses a compact binary frame instead of the JSON envelope so that a bundle of
//     Opus frames plus AES-GCM tag fits a single ATT write at the preferred MTU (247):
//
//     [0xCA][version=1][callTag 4B][seq u32 BE][AES-GCM(txKey, bundle, nonce, aad=header)]
//     bundle = ([u16 BE len][opus frame]) * framesPerPacket
//     nonce  = 4 zero bytes ‖ u64 BE seq          (never transmitted)
//     aad    = the 10-byte header
//
//  Each direction encrypts with its own key derived from the contact AES key:
//
//     txKey(caller→callee) = HKDF-SHA256(contactKey, salt: "dcs-p2pcall-v1", info: "a2b|"+callId+"|"+saltHex)
//     txKey(callee→caller) = HKDF-SHA256(contactKey, salt: "dcs-p2pcall-v1", info: "b2a|"+callId+"|"+saltHex)
//
//  The 16-byte saltHex for each direction is a fresh random value chosen by that direction's
//  sender (caller's salt rides the offer, callee's rides the accept), so an honest sender's own
//  audio key is unique per call even if a peer replays a callId — closing an AES-GCM nonce-reuse
//  (two-time-pad) gap.
//
//  The Android implementation (`service/p2p/call/P2pCallProtocol.kt`) must stay bit-compatible;
//  both are pinned by the same golden fixtures in their unit tests.
//

import Foundation
import CryptoKit

struct P2pCallSignal: Equatable {
    let kind: String
    let callId: String
    var senderName: String?
    var timestampMillis: Int64?
    var sampleRateHz: Int?
    var frameMs: Int?
    var framesPerPacket: Int?
    var bitrateBps: Int?
    var reason: String?
    var ok: Bool?
    // Lowercase hex of this sender's fresh 16-byte audio-key salt (offer + accept only).
    var saltHex: String?

    init(
        kind: String,
        callId: String,
        senderName: String? = nil,
        timestampMillis: Int64? = nil,
        sampleRateHz: Int? = nil,
        frameMs: Int? = nil,
        framesPerPacket: Int? = nil,
        bitrateBps: Int? = nil,
        reason: String? = nil,
        ok: Bool? = nil,
        saltHex: String? = nil
    ) {
        self.kind = kind
        self.callId = callId
        self.senderName = senderName
        self.timestampMillis = timestampMillis
        self.sampleRateHz = sampleRateHz
        self.frameMs = frameMs
        self.framesPerPacket = framesPerPacket
        self.bitrateBps = bitrateBps
        self.reason = reason
        self.ok = ok
        self.saltHex = saltHex
    }
}

enum P2pCallProtocol {
    static let frameMagic: UInt8 = 0xCA
    static let frameVersion: UInt8 = 0x01
    static let callTagBytes = 4
    static let frameHeaderBytes = 1 + 1 + callTagBytes + 4
    static let gcmTagBytes = 16
    static let maxBundleFrames = 8
    static let maxBundleBytes = 4_096
    static let saltBytes = 16

    private static let hkdfSalt = Data("dcs-p2pcall-v1".utf8)
    private static let hkdfInfoCallerToCallee = "a2b|"
    private static let hkdfInfoCalleeToCaller = "b2a|"
    private static let maxCallIdLength = 128

    static let signalKinds: Set<String> = [
        P2pBleProtocol.chatKindCallOffer,
        P2pBleProtocol.chatKindCallRing,
        P2pBleProtocol.chatKindCallAccept,
        P2pBleProtocol.chatKindCallReject,
        P2pBleProtocol.chatKindCallBusy,
        P2pBleProtocol.chatKindCallEnd,
        P2pBleProtocol.chatKindCallCfg,
        P2pBleProtocol.chatKindCallCfgAck
    ]

    static func isCallSignalKind(_ kind: String?) -> Bool {
        guard let kind else { return false }
        return signalKinds.contains(kind)
    }

    /// Builds the inner chat-envelope JSON payload for a call signal.
    static func encodeSignal(_ signal: P2pCallSignal) -> [String: Any]? {
        guard isCallSignalKind(signal.kind),
              !signal.callId.isEmpty,
              signal.callId.count <= maxCallIdLength else {
            return nil
        }
        var payload: [String: Any] = [
            "kind": signal.kind,
            "callId": signal.callId
        ]
        if let senderName = signal.senderName, !senderName.isEmpty {
            payload["senderName"] = senderName
        }
        if let timestampMillis = signal.timestampMillis {
            payload["ts"] = timestampMillis
        }
        if let sampleRateHz = signal.sampleRateHz {
            payload["sampleRate"] = sampleRateHz
        }
        if let frameMs = signal.frameMs {
            payload["frameMs"] = frameMs
        }
        if let framesPerPacket = signal.framesPerPacket {
            payload["framesPerPacket"] = framesPerPacket
        }
        if let bitrateBps = signal.bitrateBps {
            payload["bitrate"] = bitrateBps
        }
        if let reason = signal.reason, !reason.isEmpty {
            payload["reason"] = reason
        }
        if let ok = signal.ok {
            payload["ok"] = ok
        }
        if let saltHex = signal.saltHex, !saltHex.isEmpty {
            payload["salt"] = saltHex
        }
        return payload
    }

    /// Fresh random per-direction audio-key salt as lowercase hex.
    static func randomSaltHex() -> String {
        var bytes = [UInt8](repeating: 0, count: saltBytes)
        _ = SecRandomCopyBytes(kSecRandomDefault, saltBytes, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Parses a call signal from a decrypted inner chat payload; nil if not a valid signal.
    static func parseSignal(_ payload: [String: Any]) -> P2pCallSignal? {
        guard let kind = payload["kind"] as? String, isCallSignalKind(kind) else {
            return nil
        }
        guard let rawCallId = payload["callId"] as? String else { return nil }
        let callId = rawCallId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !callId.isEmpty, callId.count <= maxCallIdLength else { return nil }
        return P2pCallSignal(
            kind: kind,
            callId: callId,
            senderName: (payload["senderName"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            timestampMillis: (payload["ts"] as? NSNumber)?.int64Value,
            sampleRateHz: (payload["sampleRate"] as? NSNumber)?.intValue,
            frameMs: (payload["frameMs"] as? NSNumber)?.intValue,
            framesPerPacket: (payload["framesPerPacket"] as? NSNumber)?.intValue,
            bitrateBps: (payload["bitrate"] as? NSNumber)?.intValue,
            reason: (payload["reason"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            ok: boolValue(payload["ok"]),
            saltHex: (payload["salt"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        )
    }

    /// First four bytes of SHA-256 over the callId; public routing tag inside audio frames.
    static func deriveCallTag(callId: String) -> Data {
        let digest = SHA256.hash(data: Data(callId.utf8))
        return Data(digest.prefix(callTagBytes))
    }

    /// HKDF-SHA256 (RFC 5869) over the per-contact AES key. The caller of the call encrypts
    /// with the `a2b` key, the callee with `b2a`.
    ///
    /// `directionSaltHex` is the fresh random salt chosen by that direction's sender; folding it
    /// into the info string makes the derived key unique per call-instance even if a peer reuses
    /// a `callId`, preventing AES-GCM nonce reuse.
    static func deriveDirectionalKey(
        contactKey: SymmetricKey,
        callId: String,
        callerToCallee: Bool,
        directionSaltHex: String
    ) -> SymmetricKey {
        let prefix = callerToCallee ? hkdfInfoCallerToCallee : hkdfInfoCalleeToCaller
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: contactKey,
            salt: hkdfSalt,
            info: Data((prefix + callId + "|" + directionSaltHex).utf8),
            outputByteCount: 32
        )
    }

    /// Distinguishes binary call-audio frames from JSON chat envelopes after transport unwrap.
    /// JSON always starts with '{' (0x7B), so matching on the magic byte alone is unambiguous
    /// and keeps future frame versions out of the chat parser as well.
    static func isCallAudioFrame(_ payload: Data) -> Bool {
        payload.first == frameMagic
    }

    /// Concatenates Opus frames as ([u16 BE length][frame])*.
    static func packFrameBundle(_ frames: [Data]) -> Data? {
        guard !frames.isEmpty, frames.count <= maxBundleFrames else { return nil }
        var bundle = Data()
        for frame in frames {
            guard !frame.isEmpty, frame.count <= 0xFFFF else { return nil }
            bundle.append(UInt8((frame.count >> 8) & 0xFF))
            bundle.append(UInt8(frame.count & 0xFF))
            bundle.append(frame)
        }
        guard bundle.count <= maxBundleBytes else { return nil }
        return bundle
    }

    /// Reverses `packFrameBundle`; nil on any structural violation.
    static func unpackFrameBundle(_ bundle: Data) -> [Data]? {
        guard !bundle.isEmpty, bundle.count <= maxBundleBytes else { return nil }
        let bytes = [UInt8](bundle)
        var frames: [Data] = []
        var offset = 0
        while offset < bytes.count {
            guard offset + 2 <= bytes.count, frames.count < maxBundleFrames else { return nil }
            let length = (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
            offset += 2
            guard length > 0, offset + length <= bytes.count else { return nil }
            frames.append(Data(bytes[offset..<(offset + length)]))
            offset += length
        }
        return frames.isEmpty ? nil : frames
    }

    static func encodeAudioFrame(
        txKey: SymmetricKey,
        callTag: Data,
        seq: UInt32,
        bundle: Data
    ) -> Data? {
        guard callTag.count == callTagBytes,
              !bundle.isEmpty,
              bundle.count <= maxBundleBytes else {
            return nil
        }
        var header = Data([frameMagic, frameVersion])
        header.append(callTag)
        header.append(bigEndianBytes(of: seq))
        guard let nonce = try? AES.GCM.Nonce(data: nonceData(for: seq)),
              let sealed = try? AES.GCM.seal(
                bundle,
                using: txKey,
                nonce: nonce,
                authenticating: header
              ) else {
            return nil
        }
        return header + sealed.ciphertext + sealed.tag
    }

    /// Authenticated decode of a binary audio frame. Returns nil on any mismatch — wrong call,
    /// wrong direction key, truncation, or tampering — so callers can drop packets silently.
    static func decodeAudioFrame(
        rxKey: SymmetricKey,
        expectedCallTag: Data,
        packet: Data
    ) -> (seq: UInt32, bundle: Data)? {
        guard expectedCallTag.count == callTagBytes,
              packet.count >= frameHeaderBytes + gcmTagBytes + 1 else {
            return nil
        }
        let bytes = [UInt8](packet)
        guard bytes[0] == frameMagic, bytes[1] == frameVersion else { return nil }
        guard Data(bytes[2..<(2 + callTagBytes)]) == expectedCallTag else { return nil }
        let seqOffset = 2 + callTagBytes
        let seq = (UInt32(bytes[seqOffset]) << 24)
            | (UInt32(bytes[seqOffset + 1]) << 16)
            | (UInt32(bytes[seqOffset + 2]) << 8)
            | UInt32(bytes[seqOffset + 3])
        let header = Data(bytes[0..<frameHeaderBytes])
        let cipherText = Data(bytes[frameHeaderBytes..<(bytes.count - gcmTagBytes)])
        let tag = Data(bytes[(bytes.count - gcmTagBytes)...])
        guard let nonce = try? AES.GCM.Nonce(data: nonceData(for: seq)),
              let sealed = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: cipherText, tag: tag),
              let bundle = try? AES.GCM.open(sealed, using: rxKey, authenticating: header) else {
            return nil
        }
        return (seq: seq, bundle: bundle)
    }

    private static func nonceData(for seq: UInt32) -> Data {
        var nonce = Data(count: 4)
        nonce.append(bigEndianBytes(of: UInt64(seq)))
        return nonce
    }

    private static func bigEndianBytes<T: FixedWidthInteger>(of value: T) -> Data {
        var bigEndian = value.bigEndian
        return withUnsafeBytes(of: &bigEndian) { Data($0) }
    }

    private static func boolValue(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber else { return nil }
        return number.boolValue
    }
}
