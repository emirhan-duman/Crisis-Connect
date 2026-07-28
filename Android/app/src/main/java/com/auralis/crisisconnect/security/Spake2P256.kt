package com.auralis.crisisconnect.security

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SPAKE2 (RFC 9382) over NIST P-256 with SHA-256 / HKDF-SHA256 / HMAC-SHA256.
 *
 * SPAKE2 is a balanced PAKE: two parties who share a low-entropy secret (here a phone number)
 * derive a strong shared key over an untrusted channel such that an active attacker gets EXACTLY
 * ONE password guess per protocol run and learns nothing otherwise (RFC 9382 §7). We use this for
 * offline "add-by-number" over a plain (untrusted) BLE GATT link: the number is never broadcast,
 * and harvesting it would require ~1 billion live handshakes with the victim — infeasible.
 *
 * Only the minimal, well-specified P-256 affine point arithmetic is implemented here (no
 * dependency); the PROTOCOL and key schedule follow RFC 9382 exactly and the whole thing is pinned
 * to the RFC 9382 Appendix B P-256 test vector in Spake2P256Test. Not constant-time — acceptable
 * because the secret `w` is a value the local user already knows and the remote attacker only sees
 * protocol messages, not local timing.
 *
 * Roles: side A uses M, side B uses N (RFC 9382 §3.3). iOS/web ports MUST reproduce the exact byte
 * layouts below (uncompressed points 0x04||X||Y, 8-byte little-endian lengths in TT).
 */
object Spake2P256 {
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val A_COEF = P.subtract(BigInteger.valueOf(3)) // a = -3 mod p
    private val B_COEF = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)
    /** Curve order n (prime; cofactor 1). Scalars are taken mod this. */
    val ORDER: BigInteger = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
    private val G = Point(
        BigInteger("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296", 16),
        BigInteger("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5", 16)
    )

    // Fixed SPAKE2 points for P-256 (RFC 9382 §4), nothing-up-my-sleeve, no trapdoor.
    private val M = decodePoint(hexToBytes("02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"))
    private val N = decodePoint(hexToBytes("03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"))

    private val secureRandom = SecureRandom()

    /** Affine point; `null` denotes the point at infinity. */
    private class Point(val x: BigInteger, val y: BigInteger)

    // ---- Public SPAKE2 API (points encoded uncompressed: 0x04 || X[32] || Y[32]) ----

    /** Side A's share: pA = w·M + x·G. */
    fun shareA(w: BigInteger, x: BigInteger): ByteArray =
        encode(add(scalarMul(w, M), scalarMul(x, G)))

    /** Side B's share: pB = w·N + y·G. */
    fun shareB(w: BigInteger, y: BigInteger): ByteArray =
        encode(add(scalarMul(w, N), scalarMul(y, G)))

    /** Side A computes the shared point K = x·(pB − w·N). */
    fun keyA(w: BigInteger, x: BigInteger, pB: ByteArray): ByteArray =
        encode(scalarMul(x, sub(decodePoint(pB), scalarMul(w, N))))

    /** Side B computes the shared point K = y·(pA − w·M). */
    fun keyB(w: BigInteger, y: BigInteger, pA: ByteArray): ByteArray =
        encode(scalarMul(y, sub(decodePoint(pA), scalarMul(w, M))))

    /**
     * RFC 9382 §3.3 transcript:
     * TT = len(A)||A || len(B)||B || len(pA)||pA || len(pB)||pB || len(K)||K || len(w)||w,
     * each length an 8-byte little-endian byte count. `w` is encoded as a 32-byte big-endian scalar.
     */
    fun transcript(
        idA: ByteArray,
        idB: ByteArray,
        pA: ByteArray,
        pB: ByteArray,
        k: ByteArray,
        w: BigInteger
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun put(s: ByteArray) {
            out.write(len8(s.size))
            out.write(s)
        }
        put(idA); put(idB); put(pA); put(pB); put(k); put(i2osp(w.mod(ORDER), 32))
        return out.toByteArray()
    }

    class Spake2Keys(
        val sharedKey: ByteArray, // Ke — the session secret both sides feed into their KDF
        val ka: ByteArray,
        val confirmKeyA: ByteArray, // KcA
        val confirmKeyB: ByteArray  // KcB
    )

    /** RFC 9382 §4: Ke||Ka = SHA-256(TT); KcA||KcB = HKDF(salt=nil, Ka, "ConfirmationKeys"). */
    fun deriveKeys(tt: ByteArray): Spake2Keys {
        val hash = sha256(tt)
        val ke = hash.copyOfRange(0, 16)
        val ka = hash.copyOfRange(16, 32)
        val kc = Crypto.hkdfSha256(
            ikm = ka,
            salt = null,
            info = "ConfirmationKeys".toByteArray(Charsets.US_ASCII),
            outputLength = 32
        )
        return Spake2Keys(
            sharedKey = ke,
            ka = ka,
            confirmKeyA = kc.copyOfRange(0, 16),
            confirmKeyB = kc.copyOfRange(16, 32)
        )
    }

    /** Confirmation MAC: HMAC-SHA256(Kc, TT). A sends confirm(KcA, TT); B sends confirm(KcB, TT). */
    fun confirm(confirmKey: ByteArray, tt: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(confirmKey, "HmacSHA256"))
        return mac.doFinal(tt)
    }

    /** A fresh scalar in [1, n) for the per-run ephemeral (x or y). */
    fun randomScalar(): BigInteger {
        while (true) {
            val candidate = BigInteger(256, secureRandom)
            if (candidate >= BigInteger.ONE && candidate < ORDER) return candidate
        }
    }

    // ---- Field / curve arithmetic (affine, mod P) ----

    private fun add(p1: Point?, p2: Point?): Point? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x) {
            if ((p1.y + p2.y).mod(P).signum() == 0) return null // P + (-P) = infinity
            return doublePoint(p1)
        }
        val lambda = (p2.y - p1.y).mod(P)
            .multiply((p2.x - p1.x).modInverse(P)).mod(P)
        val x3 = (lambda * lambda - p1.x - p2.x).mod(P)
        val y3 = (lambda * (p1.x - x3) - p1.y).mod(P)
        return Point(x3, y3)
    }

    private fun doublePoint(p1: Point): Point? {
        if (p1.y.signum() == 0) return null
        val two = BigInteger.valueOf(2)
        val lambda = (BigInteger.valueOf(3) * p1.x * p1.x + A_COEF).mod(P)
            .multiply((two * p1.y).modInverse(P)).mod(P)
        val x3 = (lambda * lambda - two * p1.x).mod(P)
        val y3 = (lambda * (p1.x - x3) - p1.y).mod(P)
        return Point(x3, y3)
    }

    private fun sub(p1: Point?, p2: Point?): Point? =
        add(p1, p2?.let { Point(it.x, (P - it.y).mod(P)) })

    private fun scalarMul(kRaw: BigInteger, point: Point?): Point? {
        if (point == null) return null
        val k = kRaw.mod(ORDER)
        var result: Point? = null
        var addend: Point? = point
        var bits = k
        while (bits.signum() > 0) {
            if (bits.testBit(0)) result = add(result, addend)
            addend = doublePoint(addend!!)
            bits = bits.shiftRight(1)
        }
        return result
    }

    // ---- Encoding ----

    private fun encode(point: Point?): ByteArray {
        requireNotNull(point) { "Cannot encode the point at infinity." }
        val out = ByteArray(65)
        out[0] = 0x04
        i2osp(point.x, 32).copyInto(out, 1)
        i2osp(point.y, 32).copyInto(out, 33)
        return out
    }

    private fun decodePoint(bytes: ByteArray): Point {
        val point = when {
            bytes.size == 65 && bytes[0].toInt() == 0x04 -> {
                Point(os2ip(bytes, 1, 32), os2ip(bytes, 33, 32))
            }
            bytes.size == 33 && (bytes[0].toInt() == 0x02 || bytes[0].toInt() == 0x03) -> {
                val x = os2ip(bytes, 1, 32)
                decompress(x, odd = bytes[0].toInt() == 0x03)
            }
            else -> throw IllegalArgumentException("Unsupported point encoding.")
        }
        require(isOnCurve(point)) { "Point is not on the P-256 curve." }
        return point
    }

    private fun decompress(x: BigInteger, odd: Boolean): Point {
        val rhs = (x.modPow(BigInteger.valueOf(3), P) + A_COEF * x + B_COEF).mod(P)
        // p ≡ 3 (mod 4) ⇒ sqrt(a) = a^((p+1)/4) mod p
        var y = rhs.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        require((y * y).mod(P) == rhs) { "No square root: invalid compressed point." }
        if (y.testBit(0) != odd) y = (P - y).mod(P)
        return Point(x, y)
    }

    private fun isOnCurve(point: Point): Boolean {
        val lhs = (point.y * point.y).mod(P)
        val rhs = (point.x.modPow(BigInteger.valueOf(3), P) + A_COEF * point.x + B_COEF).mod(P)
        return lhs == rhs
    }

    private fun i2osp(value: BigInteger, length: Int): ByteArray {
        val raw = value.toByteArray() // big-endian, may carry a leading 0x00 sign byte or be short
        val out = ByteArray(length)
        if (raw.size <= length) {
            raw.copyInto(out, length - raw.size)
        } else {
            // Drop the leading sign byte(s) that BigInteger may prepend.
            raw.copyInto(out, 0, raw.size - length, raw.size)
        }
        return out
    }

    private fun os2ip(bytes: ByteArray, offset: Int, length: Int): BigInteger =
        BigInteger(1, bytes.copyOfRange(offset, offset + length))

    private fun len8(n: Int): ByteArray {
        val out = ByteArray(8)
        var v = n.toLong()
        for (i in 0 until 8) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) +
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
