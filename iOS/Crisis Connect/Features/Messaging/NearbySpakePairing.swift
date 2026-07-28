//
//  NearbySpakePairing.swift
//  Crisis Connect
//
//  iOS port of the Android `NearbySpakePairing.kt` — the transport-agnostic SPAKE2 session for
//  harvest-proof offline "add by phone number". Kept byte-for-byte compatible with Android: the
//  phone number is only the low-entropy SPAKE2 password (never broadcast), and identities are
//  exchanged AES-256-GCM-encrypted under a key derived from the SPAKE2 secret Ke.
//
//  Flow (initiator = searcher who has the target's number; responder = discoverable device, uses
//  its OWN number). Both derive the same w only if they mean the same number:
//    1. initiator → responder : pA
//    2. responder → initiator : pB, cB
//    3. initiator → responder : cA, enc(identityA)
//    4. responder → initiator : status, enc(identityB)
//

import Foundation
import CryptoKit

enum NearbyPairingError: Error {
    case malformed
    case confirmationFailed
    case rejected(Int)
    case notReady
}

struct NearbyIdentity: Equatable {
    let uid: String
    let publicKeyBase64: String
    let displayName: String
}

enum NearbySpakePairing {
    private static let version: UInt8 = 1
    private static let domain = "crisisconnect:nearby:spake2:v1"
    private static let idA = Data("cc-nearby-A".utf8)
    private static let idB = Data("cc-nearby-B".utf8)

    static let statusReject = 0
    static let statusOk = 1
    static let statusPending = 2

    /// Maps a phone number to the SPAKE2 password scalar w. Must match Android exactly.
    static func deriveW(_ e164: String) -> BigUInt {
        let trimmed = e164.trimmingCharacters(in: .whitespacesAndNewlines)
        let hash = Data(SHA512.hash(data: Data("\(domain):w:\(trimmed)".utf8)))
        return BigUInt(bytes: hash).mod(Spake2P256.order)
    }

    /// A standalone REJECT reply (message 4) for when we can't or won't run the handshake.
    static func rejectMessage() -> Data { encodeMsg4(status: statusReject, encIdentity: Data()) }

    /// The status byte of a message-4 reply (for the connector's PENDING polling loop).
    static func responseStatus(_ msg4: Data) -> Int {
        (try? decodeMsg4(msg4).status) ?? -1
    }

    // MARK: - Initiator (searcher)

    final class Initiator {
        private let w: BigUInt
        private let me: NearbyIdentity
        private let x: BigUInt
        private let pA: Data
        private var ke: Data?

        init(w: BigUInt, me: NearbyIdentity) {
            self.w = w
            self.me = me
            self.x = Spake2P256.randomScalar()
            self.pA = Spake2P256.shareA(w: w, x: x)
        }

        func message1() -> Data { encodeMsg1(pA: pA) }

        func onMessage2(_ msg2: Data) throws -> Data {
            let (pB, cB) = try decodeMsg2(msg2)
            let k = Spake2P256.keyA(w: w, x: x, pB: pB)
            let tt = Spake2P256.transcript(idA: idA, idB: idB, pA: pA, pB: pB, k: k, w: w)
            let keys = Spake2P256.deriveKeys(tt)
            guard constantTimeEquals(cB, Spake2P256.confirm(confirmKey: keys.confirmKeyB, tt: tt)) else {
                throw NearbyPairingError.confirmationFailed
            }
            ke = keys.sharedKey
            let cA = Spake2P256.confirm(confirmKey: keys.confirmKeyA, tt: tt)
            return encodeMsg3(cA: cA, encIdentity: try encryptIdentity(keys.sharedKey, me))
        }

        func onMessage4(_ msg4: Data) throws -> NearbyIdentity {
            guard let key = ke else { throw NearbyPairingError.notReady }
            let decoded = try decodeMsg4(msg4)
            guard decoded.status == statusOk else { throw NearbyPairingError.rejected(decoded.status) }
            return try decryptIdentity(key, decoded.encIdentity)
        }

        func contactKey() throws -> Data {
            guard let key = ke else { throw NearbyPairingError.notReady }
            return deriveContactKey(key)
        }
    }

    // MARK: - Responder (discoverable device, uses its own number)

    final class Responder {
        private let w: BigUInt
        private let me: NearbyIdentity
        private let y: BigUInt
        private var ke: Data?
        private var confirmKeyA: Data?
        private var tt: Data?

        init(w: BigUInt, me: NearbyIdentity) {
            self.w = w
            self.me = me
            self.y = Spake2P256.randomScalar()
        }

        func onMessage1(_ msg1: Data) throws -> Data {
            let pA = try decodeMsg1(msg1)
            let pB = Spake2P256.shareB(w: w, y: y)
            let k = Spake2P256.keyB(w: w, y: y, pA: pA)
            let transcript = Spake2P256.transcript(idA: idA, idB: idB, pA: pA, pB: pB, k: k, w: w)
            let keys = Spake2P256.deriveKeys(transcript)
            ke = keys.sharedKey
            confirmKeyA = keys.confirmKeyA
            tt = transcript
            return encodeMsg2(pB: pB, cB: Spake2P256.confirm(confirmKey: keys.confirmKeyB, tt: transcript))
        }

        func onMessage3(_ msg3: Data) throws -> NearbyIdentity {
            guard let key = ke, let kcA = confirmKeyA, let transcript = tt else {
                throw NearbyPairingError.notReady
            }
            let (cA, enc) = try decodeMsg3(msg3)
            guard constantTimeEquals(cA, Spake2P256.confirm(confirmKey: kcA, tt: transcript)) else {
                throw NearbyPairingError.confirmationFailed
            }
            return try decryptIdentity(key, enc)
        }

        func responseMessage(status: Int) throws -> Data {
            let enc: Data
            if status == statusOk {
                guard let key = ke else { throw NearbyPairingError.notReady }
                enc = try encryptIdentity(key, me)
            } else {
                enc = Data()
            }
            return encodeMsg4(status: status, encIdentity: enc)
        }

        func contactKey() throws -> Data {
            guard let key = ke else { throw NearbyPairingError.notReady }
            return deriveContactKey(key)
        }
    }

    private static func deriveContactKey(_ ke: Data) -> Data {
        hkdf(ke, info: "\(domain):btkey")
    }

    // MARK: - Identity encryption (AES-256-GCM under a key derived from Ke)

    private static func identityKey(_ ke: Data) -> Data { hkdf(ke, info: "\(domain):identity") }

    private static func encryptIdentity(_ ke: Data, _ identity: NearbyIdentity) throws -> Data {
        var plaintext = Data()
        appendLenPrefixed(&plaintext, Data(identity.uid.utf8))
        appendLenPrefixed(&plaintext, Data(identity.publicKeyBase64.utf8))
        appendLenPrefixed(&plaintext, Data(identity.displayName.utf8))

        var nonceBytes = Data(count: 12)
        _ = nonceBytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 12, $0.baseAddress!) }
        let box = try AES.GCM.seal(
            plaintext,
            using: SymmetricKey(data: identityKey(ke)),
            nonce: try AES.GCM.Nonce(data: nonceBytes)
        )
        let ct = box.ciphertext + box.tag
        var out = Data()
        out.append(nonceBytes)
        out.append(len2(ct.count))
        out.append(ct)
        return out
    }

    private static func decryptIdentity(_ ke: Data, _ enc: Data) throws -> NearbyIdentity {
        var r = Reader(enc)
        let nonce = try r.bytes(12)
        let ctLen = try r.len2()
        let ctTag = try r.bytes(ctLen)
        guard ctTag.count > 16 else { throw NearbyPairingError.malformed }
        let box = try AES.GCM.SealedBox(
            nonce: try AES.GCM.Nonce(data: nonce),
            ciphertext: ctTag.prefix(ctTag.count - 16),
            tag: ctTag.suffix(16)
        )
        let plaintext = try AES.GCM.open(box, using: SymmetricKey(data: identityKey(ke)))
        var pr = Reader(plaintext)
        let uid = String(decoding: try pr.lenPrefixed(), as: UTF8.self)
        let pub = String(decoding: try pr.lenPrefixed(), as: UTF8.self)
        let name = String(decoding: try pr.lenPrefixed(), as: UTF8.self)
        return NearbyIdentity(uid: uid, publicKeyBase64: pub, displayName: name)
    }

    // MARK: - Message codecs (byte-identical to Android)

    private static func encodeMsg1(pA: Data) -> Data { Data([version]) + pA }
    private static func decodeMsg1(_ bytes: Data) throws -> Data {
        var r = Reader(bytes)
        guard try r.u8() == Int(version) else { throw NearbyPairingError.malformed }
        return try r.bytes(65)
    }

    private static func encodeMsg2(pB: Data, cB: Data) -> Data { Data([version]) + pB + cB }
    private static func decodeMsg2(_ bytes: Data) throws -> (Data, Data) {
        var r = Reader(bytes)
        guard try r.u8() == Int(version) else { throw NearbyPairingError.malformed }
        return (try r.bytes(65), try r.bytes(32))
    }

    private static func encodeMsg3(cA: Data, encIdentity: Data) -> Data {
        Data([version]) + cA + len2(encIdentity.count) + encIdentity
    }
    private static func decodeMsg3(_ bytes: Data) throws -> (Data, Data) {
        var r = Reader(bytes)
        guard try r.u8() == Int(version) else { throw NearbyPairingError.malformed }
        let cA = try r.bytes(32)
        let len = try r.len2()
        return (cA, try r.bytes(len))
    }

    private static func encodeMsg4(status: Int, encIdentity: Data) -> Data {
        Data([version, UInt8(status & 0xFF)]) + len2(encIdentity.count) + encIdentity
    }
    private static func decodeMsg4(_ bytes: Data) throws -> (status: Int, encIdentity: Data) {
        var r = Reader(bytes)
        guard try r.u8() == Int(version) else { throw NearbyPairingError.malformed }
        let status = try r.u8()
        let len = try r.len2()
        return (status, try r.bytes(len))
    }

    // MARK: - Helpers

    private static func hkdf(_ ke: Data, info: String) -> Data {
        HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ke),
            salt: Data(repeating: 0, count: 32),
            info: Data(info.utf8),
            outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
    }

    private static func len2(_ n: Int) -> Data { Data([UInt8((n >> 8) & 0xFF), UInt8(n & 0xFF)]) }

    private static func appendLenPrefixed(_ out: inout Data, _ value: Data) {
        out.append(len2(value.count))
        out.append(value)
    }

    private static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        guard a.count == b.count else { return false }
        var diff: UInt8 = 0
        let ab = [UInt8](a), bb = [UInt8](b)
        for i in 0..<ab.count { diff |= ab[i] ^ bb[i] }
        return diff == 0
    }

    private struct Reader {
        private let data: [UInt8]
        private var pos = 0
        init(_ d: Data) { self.data = [UInt8](d) }
        mutating func u8() throws -> Int {
            guard pos < data.count else { throw NearbyPairingError.malformed }
            defer { pos += 1 }
            return Int(data[pos])
        }
        mutating func bytes(_ n: Int) throws -> Data {
            guard pos + n <= data.count, n >= 0 else { throw NearbyPairingError.malformed }
            defer { pos += n }
            return Data(data[pos..<pos + n])
        }
        mutating func len2() throws -> Int { (try u8() << 8) | (try u8()) }
        mutating func lenPrefixed() throws -> Data { try bytes(try len2()) }
    }
}
