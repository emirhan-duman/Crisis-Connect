//
//  Spake2P256.swift
//  Crisis Connect
//
//  SPAKE2 (RFC 9382) over NIST P-256 — iOS port of the Android `Spake2P256.kt`, kept byte-for-byte
//  compatible (verified against the RFC 9382 Appendix B P-256 test vector and the Android golden
//  vector). Powers harvest-proof offline "add by phone number" over an untrusted BLE link: the
//  number is only the low-entropy SPAKE2 password, never broadcast, and an active attacker gets
//  exactly one guess per handshake.
//
//  CryptoKit exposes no raw EC point arithmetic and Swift has no stdlib big integer, so this file
//  vendors a minimal fixed-work BigUInt (schoolbook multiply + bit-serial reduction + Fermat
//  inverse) — correct-by-construction and pinned to the RFC vector. It is NOT constant-time; that's
//  acceptable because the secret is a value the local user already knows and the remote attacker
//  only sees protocol messages. Roles: side A uses M, side B uses N (RFC 9382 §3.3).
//

import Foundation
import CryptoKit

enum Spake2P256 {
    // P-256 domain parameters.
    private static let p = BigUInt(hex: "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
    private static let aCoef = BigUInt(hex: "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC") // -3 mod p
    private static let bCoef = BigUInt(hex: "5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
    /// Curve order n (prime, cofactor 1).
    static let order = BigUInt(hex: "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
    private static let g = Point(
        x: BigUInt(hex: "6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296"),
        y: BigUInt(hex: "4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
    )
    // Fixed SPAKE2 points for P-256 (RFC 9382 §4).
    private static let m = decodePoint(hexToBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
    private static let n = decodePoint(hexToBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

    // MARK: - SPAKE2 API (points encoded uncompressed: 0x04 || X[32] || Y[32])

    /// Side A's share: pA = w·M + x·G.
    static func shareA(w: BigUInt, x: BigUInt) -> Data {
        encode(add(scalarMul(w, m), scalarMul(x, g)))
    }

    /// Side B's share: pB = w·N + y·G.
    static func shareB(w: BigUInt, y: BigUInt) -> Data {
        encode(add(scalarMul(w, n), scalarMul(y, g)))
    }

    /// Side A computes the shared point K = x·(pB − w·N).
    static func keyA(w: BigUInt, x: BigUInt, pB: Data) -> Data {
        encode(scalarMul(x, sub(decodePoint(pB), scalarMul(w, n))))
    }

    /// Side B computes the shared point K = y·(pA − w·M).
    static func keyB(w: BigUInt, y: BigUInt, pA: Data) -> Data {
        encode(scalarMul(y, sub(decodePoint(pA), scalarMul(w, m))))
    }

    /// RFC 9382 §3.3 transcript (8-byte little-endian length prefixes; no M/N; w as 32-byte scalar).
    static func transcript(idA: Data, idB: Data, pA: Data, pB: Data, k: Data, w: BigUInt) -> Data {
        var out = Data()
        func put(_ s: Data) { out.append(len8(s.count)); out.append(s) }
        put(idA); put(idB); put(pA); put(pB); put(k); put(w.toBytes(32))
        return out
    }

    struct Keys {
        let sharedKey: Data // Ke
        let ka: Data
        let confirmKeyA: Data // KcA
        let confirmKeyB: Data // KcB
    }

    /// RFC 9382 §4: Ke||Ka = SHA-256(TT); KcA||KcB = HKDF(salt=nil, Ka, "ConfirmationKeys").
    static func deriveKeys(_ tt: Data) -> Keys {
        let hash = Data(SHA256.hash(data: tt))
        let ke = hash.prefix(16)
        let ka = hash.suffix(16)
        let kc = HKDF<SHA256>.deriveKey(
            inputKeyMaterial: SymmetricKey(data: ka),
            salt: Data(repeating: 0, count: 32),
            info: Data("ConfirmationKeys".utf8),
            outputByteCount: 32
        ).withUnsafeBytes { Data($0) }
        return Keys(
            sharedKey: Data(ke),
            ka: Data(ka),
            confirmKeyA: Data(kc.prefix(16)),
            confirmKeyB: Data(kc.suffix(16))
        )
    }

    /// Confirmation MAC: HMAC-SHA256(Kc, TT).
    static func confirm(confirmKey: Data, tt: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: tt, using: SymmetricKey(data: confirmKey)))
    }

    /// A fresh scalar in [1, n).
    static func randomScalar() -> BigUInt {
        while true {
            var bytes = [UInt8](repeating: 0, count: 32)
            _ = SecRandomCopyBytes(kSecRandomDefault, 32, &bytes)
            let candidate = BigUInt(bytes: Data(bytes)).mod(order)
            if !candidate.isZero { return candidate }
        }
    }

    // MARK: - Curve (affine, mod p)

    private struct Point {
        let x: BigUInt
        let y: BigUInt
        let infinity: Bool
        init(x: BigUInt, y: BigUInt) { self.x = x; self.y = y; self.infinity = false }
        init(infinity: Bool) { self.x = .zero; self.y = .zero; self.infinity = infinity }
    }

    private static func add(_ p1: Point, _ p2: Point) -> Point {
        if p1.infinity { return p2 }
        if p2.infinity { return p1 }
        if p1.x == p2.x {
            if modAdd(p1.y, p2.y).isZero { return Point(infinity: true) }
            return doublePoint(p1)
        }
        let lambda = modMul(modSub(p2.y, p1.y), modInverse(modSub(p2.x, p1.x)))
        let x3 = modSub(modSub(modMul(lambda, lambda), p1.x), p2.x)
        let y3 = modSub(modMul(lambda, modSub(p1.x, x3)), p1.y)
        return Point(x: x3, y: y3)
    }

    private static func doublePoint(_ p1: Point) -> Point {
        if p1.y.isZero { return Point(infinity: true) }
        let three = BigUInt(small: 3)
        let two = BigUInt(small: 2)
        let numerator = modAdd(modMul(three, modMul(p1.x, p1.x)), aCoef)
        let lambda = modMul(numerator, modInverse(modMul(two, p1.y)))
        let x3 = modSub(modMul(lambda, lambda), modMul(two, p1.x))
        let y3 = modSub(modMul(lambda, modSub(p1.x, x3)), p1.y)
        return Point(x: x3, y: y3)
    }

    private static func sub(_ p1: Point, _ p2: Point) -> Point {
        if p2.infinity { return p1 }
        return add(p1, Point(x: p2.x, y: modSub(BigUInt.zero, p2.y)))
    }

    private static func scalarMul(_ kRaw: BigUInt, _ point: Point) -> Point {
        if point.infinity { return point }
        let k = kRaw.mod(order)
        var result = Point(infinity: true)
        var addend = point
        var bits = k
        while !bits.isZero {
            if bits.testBit(0) { result = add(result, addend) }
            addend = doublePoint(addend)
            bits = bits.shiftedRight1()
        }
        return result
    }

    // Field helpers (mod p).
    private static func modAdd(_ a: BigUInt, _ b: BigUInt) -> BigUInt {
        let s = a.adding(b)
        return s >= p ? s.subtracting(p) : s
    }
    private static func modSub(_ a: BigUInt, _ b: BigUInt) -> BigUInt {
        a >= b ? a.subtracting(b) : a.adding(p).subtracting(b)
    }
    private static func modMul(_ a: BigUInt, _ b: BigUInt) -> BigUInt {
        a.multiplying(b).mod(p)
    }
    private static func modInverse(_ a: BigUInt) -> BigUInt {
        // p is prime → a^(p-2) mod p (Fermat).
        a.modPow(p.subtracting(BigUInt(small: 2)), p)
    }

    // MARK: - Encoding

    private static func encode(_ point: Point) -> Data {
        precondition(!point.infinity, "Cannot encode the point at infinity.")
        var out = Data([0x04])
        out.append(point.x.toBytes(32))
        out.append(point.y.toBytes(32))
        return out
    }

    private static func decodePoint(_ bytes: Data) -> Point {
        let b = [UInt8](bytes)
        let point: Point
        if b.count == 65 && b[0] == 0x04 {
            point = Point(x: BigUInt(bytes: Data(b[1..<33])), y: BigUInt(bytes: Data(b[33..<65])))
        } else if b.count == 33 && (b[0] == 0x02 || b[0] == 0x03) {
            point = decompress(x: BigUInt(bytes: Data(b[1..<33])), odd: b[0] == 0x03)
        } else {
            fatalError("Unsupported point encoding.")
        }
        precondition(isOnCurve(point), "Point is not on the P-256 curve.")
        return point
    }

    private static func decompress(x: BigUInt, odd: Bool) -> Point {
        let rhs = modAdd(modAdd(modMul(modMul(x, x), x), modMul(aCoef, x)), bCoef)
        // p ≡ 3 (mod 4) ⇒ sqrt(a) = a^((p+1)/4) mod p
        var y = rhs.modPow(p.adding(BigUInt(small: 1)).shiftedRight1().shiftedRight1(), p)
        precondition(modMul(y, y) == rhs, "No square root: invalid compressed point.")
        if y.testBit(0) != odd { y = modSub(BigUInt.zero, y) }
        return Point(x: x, y: y)
    }

    private static func isOnCurve(_ point: Point) -> Bool {
        let lhs = modMul(point.y, point.y)
        let rhs = modAdd(modAdd(modMul(modMul(point.x, point.x), point.x), modMul(aCoef, point.x)), bCoef)
        return lhs == rhs
    }

    private static func len8(_ n: Int) -> Data {
        var v = UInt64(n)
        var out = Data(count: 8)
        for i in 0..<8 { out[i] = UInt8(v & 0xFF); v >>= 8 }
        return out
    }

    private static func hexToBytes(_ hex: String) -> Data {
        var out = Data(capacity: hex.count / 2)
        var idx = hex.startIndex
        while idx < hex.endIndex {
            let next = hex.index(idx, offsetBy: 2)
            out.append(UInt8(hex[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }
}

// MARK: - Minimal big unsigned integer (little-endian UInt32 limbs)

struct BigUInt: Equatable, Comparable {
    private var limbs: [UInt32] // little-endian, normalized (no trailing zero limbs)

    static let zero = BigUInt(limbs: [])

    private init(limbs: [UInt32]) {
        var l = limbs
        while l.count > 0 && l.last == 0 { l.removeLast() }
        self.limbs = l
    }

    init(small: UInt32) { self.init(limbs: small == 0 ? [] : [small]) }

    init(bytes: Data) {
        // Big-endian bytes → little-endian 32-bit limbs.
        var l = [UInt32]()
        var acc: UInt32 = 0
        var shift = 0
        for byte in bytes.reversed() {
            acc |= UInt32(byte) << shift
            shift += 8
            if shift == 32 { l.append(acc); acc = 0; shift = 0 }
        }
        if acc != 0 || shift != 0 { l.append(acc) }
        self.init(limbs: l)
    }

    init(hex: String) { self.init(bytes: BigUInt.hexData(hex)) }

    private static func hexData(_ hex: String) -> Data {
        let padded = hex.count % 2 == 0 ? hex : "0" + hex
        var out = Data(capacity: padded.count / 2)
        var idx = padded.startIndex
        while idx < padded.endIndex {
            let next = padded.index(idx, offsetBy: 2)
            out.append(UInt8(padded[idx..<next], radix: 16)!)
            idx = next
        }
        return out
    }

    var isZero: Bool { limbs.isEmpty }

    func testBit(_ i: Int) -> Bool {
        let limb = i >> 5
        if limb >= limbs.count { return false }
        return (limbs[limb] >> UInt32(i & 31)) & 1 == 1
    }

    private var bitLength: Int {
        guard let top = limbs.last else { return 0 }
        return (limbs.count - 1) * 32 + (32 - top.leadingZeroBitCount)
    }

    static func < (a: BigUInt, b: BigUInt) -> Bool { compare(a, b) < 0 }
    static func == (a: BigUInt, b: BigUInt) -> Bool { a.limbs == b.limbs }

    private static func compare(_ a: BigUInt, _ b: BigUInt) -> Int {
        if a.limbs.count != b.limbs.count { return a.limbs.count < b.limbs.count ? -1 : 1 }
        var i = a.limbs.count - 1
        while i >= 0 {
            if a.limbs[i] != b.limbs[i] { return a.limbs[i] < b.limbs[i] ? -1 : 1 }
            i -= 1
        }
        return 0
    }

    func adding(_ other: BigUInt) -> BigUInt {
        var result = [UInt32]()
        var carry: UInt64 = 0
        let n = Swift.max(limbs.count, other.limbs.count)
        for i in 0..<n {
            let av = i < limbs.count ? UInt64(limbs[i]) : 0
            let bv = i < other.limbs.count ? UInt64(other.limbs[i]) : 0
            let sum = av + bv + carry
            result.append(UInt32(sum & 0xFFFFFFFF))
            carry = sum >> 32
        }
        if carry != 0 { result.append(UInt32(carry)) }
        return BigUInt(limbs: result)
    }

    /// Assumes self >= other.
    func subtracting(_ other: BigUInt) -> BigUInt {
        var result = [UInt32]()
        var borrow: Int64 = 0
        for i in 0..<limbs.count {
            let av = Int64(limbs[i])
            let bv = i < other.limbs.count ? Int64(other.limbs[i]) : 0
            var diff = av - bv - borrow
            if diff < 0 { diff += 0x100000000; borrow = 1 } else { borrow = 0 }
            result.append(UInt32(diff))
        }
        return BigUInt(limbs: result)
    }

    func multiplying(_ other: BigUInt) -> BigUInt {
        if isZero || other.isZero { return .zero }
        var result = [UInt64](repeating: 0, count: limbs.count + other.limbs.count)
        for i in 0..<limbs.count {
            var carry: UInt64 = 0
            let av = UInt64(limbs[i])
            for j in 0..<other.limbs.count {
                let cur = result[i + j] + av * UInt64(other.limbs[j]) + carry
                result[i + j] = cur & 0xFFFFFFFF
                carry = cur >> 32
            }
            result[i + other.limbs.count] += carry
        }
        return BigUInt(limbs: result.map { UInt32($0 & 0xFFFFFFFF) })
    }

    func shiftedRight1() -> BigUInt {
        var result = [UInt32](repeating: 0, count: limbs.count)
        var carry: UInt32 = 0
        var i = limbs.count - 1
        while i >= 0 {
            let cur = limbs[i]
            result[i] = (cur >> 1) | (carry << 31)
            carry = cur & 1
            i -= 1
        }
        return BigUInt(limbs: result)
    }

    private func shiftedLeft1() -> BigUInt {
        var result = [UInt32]()
        var carry: UInt32 = 0
        for limb in limbs {
            result.append((limb << 1) | carry)
            carry = limb >> 31
        }
        if carry != 0 { result.append(carry) }
        return BigUInt(limbs: result)
    }

    private func settingBit0() -> BigUInt {
        var l = limbs
        if l.isEmpty { l = [1] } else { l[0] |= 1 }
        return BigUInt(limbs: l)
    }

    /// Remainder self mod m via bit-serial long division (correct-by-construction, not fast).
    func mod(_ m: BigUInt) -> BigUInt {
        if BigUInt.compare(self, m) < 0 { return self }
        var r = BigUInt.zero
        var i = bitLength - 1
        while i >= 0 {
            r = r.shiftedLeft1()
            if testBit(i) { r = r.settingBit0() }
            if BigUInt.compare(r, m) >= 0 { r = r.subtracting(m) }
            i -= 1
        }
        return r
    }

    /// base^exp mod m (square-and-multiply).
    func modPow(_ exp: BigUInt, _ m: BigUInt) -> BigUInt {
        var result = BigUInt(small: 1).mod(m)
        var base = mod(m)
        var e = exp
        while !e.isZero {
            if e.testBit(0) { result = result.multiplying(base).mod(m) }
            e = e.shiftedRight1()
            if !e.isZero { base = base.multiplying(base).mod(m) }
        }
        return result
    }

    /// Big-endian fixed-length byte encoding.
    func toBytes(_ length: Int) -> Data {
        var out = Data(count: length)
        for i in 0..<limbs.count {
            let limb = limbs[i]
            for b in 0..<4 {
                let pos = length - 1 - (i * 4 + b)
                if pos >= 0 { out[pos] = UInt8((limb >> UInt32(b * 8)) & 0xFF) }
            }
        }
        return out
    }
}
