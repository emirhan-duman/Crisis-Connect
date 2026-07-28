package com.auralis.crisisconnect.nearby

import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.Spake2P256
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Harvest-proof offline "add by phone number" via SPAKE2 (RFC 9382), replacing the old passive
 * number-beacon. The phone number is used only as the low-entropy SPAKE2 password, so it is NEVER
 * put on the air: an active attacker gets exactly one guess per live handshake and learns nothing
 * on a wrong guess, so recovering a number would need ~1e9 in-person handshakes with the victim.
 *
 * Flow (initiator = the searcher who has the target's number; responder = the discoverable device,
 * who uses its OWN number). Both derive the same w only if they mean the same number:
 *   1. initiator → responder : pA
 *   2. responder → initiator : pB, cB          (responder's key-confirmation MAC)
 *   3. initiator → responder : cA, enc(identityA)   (verifies cB first, then reveals identity)
 *   4. responder → initiator : status, enc(identityB)  (verifies cA first)
 * On success both hold the SPAKE2 key Ke; identities (uid + messaging public key + name) are
 * exchanged AES-256-GCM-encrypted under a key derived from Ke, so eavesdroppers on the plain BLE
 * link learn nothing and the identity is bound to the number-authenticated session (→ TOFU pin).
 *
 * This crypto/session core is transport-agnostic and unit-tested; the GATT plumbing and the
 * user-consent gate live in the service layer. SPAKE2 itself is verified against the RFC 9382
 * test vector (see Spake2P256Test).
 */
object NearbySpakePairing {
    private const val VERSION = 1
    private const val DOMAIN = "crisisconnect:nearby:spake2:v1"

    // GATT service for the SPAKE2 pairing (distinct from the mesh 0xCCA1 and the retired
    // number-beacon 0xCCA2). The connector WRITEs a message to REQUEST and READs the reply from
    // RESPONSE; the 4-message flow reuses these two characteristics across two write/read rounds.
    val SERVICE_UUID: UUID = UUID.fromString("0000cca6-0000-1000-8000-00805f9b34fb")
    val REQUEST_UUID: UUID = UUID.fromString("0000cca7-0000-1000-8000-00805f9b34fb")
    val RESPONSE_UUID: UUID = UUID.fromString("0000cca8-0000-1000-8000-00805f9b34fb")

    // Fixed SPAKE2 identities (RFC 9382 §3.3 idA/idB). Security comes from w (the number); the peer
    // identities are exchanged inside the authenticated channel, so fixed role labels are fine.
    private val ID_A = "cc-nearby-A".toByteArray(StandardCharsets.US_ASCII)
    private val ID_B = "cc-nearby-B".toByteArray(StandardCharsets.US_ASCII)

    const val STATUS_REJECT = 0
    const val STATUS_OK = 1
    const val STATUS_PENDING = 2

    /** Identity exchanged on success: enough to create a real internet-messaging contact + TOFU-pin. */
    data class NearbyIdentity(
        val uid: String,
        val publicKeyBase64: String,
        val displayName: String
    )

    /** Maps a phone number to the SPAKE2 password scalar w. Must be identical across platforms. */
    fun deriveW(e164: String): BigInteger {
        val h = MessageDigest.getInstance("SHA-512")
            .digest("$DOMAIN:w:${e164.trim()}".toByteArray(StandardCharsets.UTF_8))
        return BigInteger(1, h).mod(Spake2P256.ORDER)
    }

    /** The searcher: knows the target's number (→ [w]) and wants to add them. */
    class Initiator(private val w: BigInteger, private val me: NearbyIdentity) {
        private val x = Spake2P256.randomScalar()
        private val pA = Spake2P256.shareA(w, x)
        private var ke: ByteArray? = null

        fun message1(): ByteArray = encodeMsg1(pA)

        /** Verifies the responder's confirmation, then returns message 3 (our confirmation + identity). */
        fun onMessage2(msg2: ByteArray): ByteArray {
            val (pB, cB) = decodeMsg2(msg2)
            val k = Spake2P256.keyA(w, x, pB)
            val tt = Spake2P256.transcript(ID_A, ID_B, pA, pB, k, w)
            val keys = Spake2P256.deriveKeys(tt)
            require(constantTimeEquals(cB, Spake2P256.confirm(keys.confirmKeyB, tt))) {
                "Responder confirmation failed (wrong number / MITM)."
            }
            ke = keys.sharedKey
            val cA = Spake2P256.confirm(keys.confirmKeyA, tt)
            return encodeMsg3(cA, encryptIdentity(keys.sharedKey, me))
        }

        /** Reads the final message and returns the peer's identity (or throws on reject). */
        fun onMessage4(msg4: ByteArray): NearbyIdentity {
            val key = ke ?: error("onMessage2 must run first.")
            val (status, enc) = decodeMsg4(msg4)
            require(status == STATUS_OK) { "Pairing was not accepted (status=$status)." }
            return decryptIdentity(key, enc)
        }

        /** Symmetric key for offline Bluetooth P2P chat with this peer, derived from the SPAKE2 secret. */
        fun contactKey(): ByteArray = deriveContactKey(ke ?: error("Handshake not complete."))
    }

    /** The discoverable device: uses its OWN number as the password. */
    class Responder(private val w: BigInteger, private val me: NearbyIdentity) {
        private val y = Spake2P256.randomScalar()
        private var ke: ByteArray? = null
        private var confirmKeyA: ByteArray? = null
        private var tt: ByteArray? = null

        /** Consumes message 1 and returns message 2 (our share + confirmation). */
        fun onMessage1(msg1: ByteArray): ByteArray {
            val pA = decodeMsg1(msg1)
            val pB = Spake2P256.shareB(w, y)
            val k = Spake2P256.keyB(w, y, pA)
            val transcript = Spake2P256.transcript(ID_A, ID_B, pA, pB, k, w)
            val keys = Spake2P256.deriveKeys(transcript)
            ke = keys.sharedKey
            confirmKeyA = keys.confirmKeyA
            tt = transcript
            return encodeMsg2(pB, Spake2P256.confirm(keys.confirmKeyB, transcript))
        }

        /**
         * Verifies the initiator's confirmation and returns their identity. Does NOT build the reply
         * or add a contact — the service layer decides that after user consent (see [responseMessage]).
         */
        fun onMessage3(msg3: ByteArray): NearbyIdentity {
            val key = ke ?: error("onMessage1 must run first.")
            val (cA, enc) = decodeMsg3(msg3)
            require(constantTimeEquals(cA, Spake2P256.confirm(confirmKeyA!!, tt!!))) {
                "Initiator confirmation failed (wrong number / MITM)."
            }
            return decryptIdentity(key, enc)
        }

        /**
         * Message 4. Pass [status] = [STATUS_OK] with the user's consent to reveal our identity and
         * complete the add; [STATUS_PENDING] while awaiting consent; [STATUS_REJECT] to decline.
         */
        fun responseMessage(status: Int): ByteArray {
            val enc = if (status == STATUS_OK) encryptIdentity(ke!!, me) else ByteArray(0)
            return encodeMsg4(status, enc)
        }

        /** Symmetric key for offline Bluetooth P2P chat with this peer, derived from the SPAKE2 secret. */
        fun contactKey(): ByteArray = deriveContactKey(ke ?: error("Handshake not complete."))
    }

    /** A standalone REJECT reply (message 4) for when we can't or won't run the handshake. */
    fun rejectMessage(): ByteArray = encodeMsg4(STATUS_REJECT, ByteArray(0))

    /** Reads just the status byte of a message-4 reply (for the connector's PENDING polling loop). */
    fun responseStatus(msg4: ByteArray): Int = runCatching { decodeMsg4(msg4).first }.getOrDefault(-1)

    private fun deriveContactKey(ke: ByteArray): ByteArray =
        Crypto.hkdfSha256(ke, info = "$DOMAIN:btkey".toByteArray(StandardCharsets.UTF_8), outputLength = 32)

    // ---- Identity encryption (AES-256-GCM under a key derived from the SPAKE2 secret Ke) ----

    private fun identityKey(ke: ByteArray): ByteArray =
        Crypto.hkdfSha256(ke, info = "$DOMAIN:identity".toByteArray(StandardCharsets.UTF_8), outputLength = 32)

    private fun encryptIdentity(ke: ByteArray, identity: NearbyIdentity): ByteArray {
        val plaintext = ByteArrayOutputStream().apply {
            writeLenPrefixed(this, identity.uid.toByteArray(StandardCharsets.UTF_8), 128)
            writeLenPrefixed(this, identity.publicKeyBase64.toByteArray(StandardCharsets.US_ASCII), 512)
            writeLenPrefixed(this, identity.displayName.toByteArray(StandardCharsets.UTF_8), 128)
        }.toByteArray()
        val nonce = Crypto.randomBytes(12)
        val ct = Crypto.aesGcmEncrypt(key = identityKey(ke), nonce = nonce, plaintext = plaintext, associatedData = null)
        return ByteArrayOutputStream().apply {
            write(nonce)
            write((ct.size shr 8) and 0xFF); write(ct.size and 0xFF)
            write(ct)
        }.toByteArray()
    }

    private fun decryptIdentity(ke: ByteArray, enc: ByteArray): NearbyIdentity {
        val reader = Reader(enc)
        val nonce = reader.bytes(12)
        val ctLen = (reader.u8() shl 8) or reader.u8()
        val ct = reader.bytes(ctLen)
        val plaintext = Crypto.aesGcmDecrypt(key = identityKey(ke), nonce = nonce, ciphertextAndTag = ct, associatedData = null)
        val pr = Reader(plaintext)
        val uid = String(pr.lenPrefixed(128), StandardCharsets.UTF_8)
        val pub = String(pr.lenPrefixed(512), StandardCharsets.US_ASCII)
        val name = String(pr.lenPrefixed(128), StandardCharsets.UTF_8)
        return NearbyIdentity(uid = uid, publicKeyBase64 = pub, displayName = name)
    }

    // ---- Message codecs ----

    private fun encodeMsg1(pA: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { write(VERSION); write(pA) }.toByteArray()

    private fun decodeMsg1(bytes: ByteArray): ByteArray {
        val r = Reader(bytes)
        require(r.u8() == VERSION) { "Unsupported version." }
        return r.bytes(65)
    }

    private fun encodeMsg2(pB: ByteArray, cB: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { write(VERSION); write(pB); write(cB) }.toByteArray()

    private fun decodeMsg2(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val r = Reader(bytes)
        require(r.u8() == VERSION) { "Unsupported version." }
        return r.bytes(65) to r.bytes(32)
    }

    private fun encodeMsg3(cA: ByteArray, encIdentity: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(VERSION); write(cA)
            write((encIdentity.size shr 8) and 0xFF); write(encIdentity.size and 0xFF)
            write(encIdentity)
        }.toByteArray()

    private fun decodeMsg3(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        val r = Reader(bytes)
        require(r.u8() == VERSION) { "Unsupported version." }
        val cA = r.bytes(32)
        val len = (r.u8() shl 8) or r.u8()
        return cA to r.bytes(len)
    }

    private fun encodeMsg4(status: Int, encIdentity: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(VERSION); write(status and 0xFF)
            write((encIdentity.size shr 8) and 0xFF); write(encIdentity.size and 0xFF)
            write(encIdentity)
        }.toByteArray()

    private fun decodeMsg4(bytes: ByteArray): Pair<Int, ByteArray> {
        val r = Reader(bytes)
        require(r.u8() == VERSION) { "Unsupported version." }
        val status = r.u8()
        val len = (r.u8() shl 8) or r.u8()
        return status to r.bytes(len)
    }

    private fun writeLenPrefixed(out: ByteArrayOutputStream, value: ByteArray, maxLen: Int) {
        require(value.size <= maxLen) { "value too long" }
        out.write((value.size shr 8) and 0xFF)
        out.write(value.size and 0xFF)
        out.write(value)
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        fun u8(): Int = data[pos++].toInt() and 0xFF
        fun bytes(n: Int): ByteArray {
            val slice = data.copyOfRange(pos, pos + n); pos += n; return slice
        }
        fun lenPrefixed(maxLen: Int): ByteArray {
            val len = (u8() shl 8) or u8()
            require(len in 0..maxLen) { "length out of range" }
            return bytes(len)
        }
    }
}
