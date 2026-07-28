package com.auralis.crisisconnect.security

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigInteger

/**
 * Pins [Spake2P256] to the official RFC 9382 Appendix B P-256 test vector (A='server', B='client').
 * If any of these fail, the SPAKE2 implementation diverged from the spec and would be incompatible
 * with the iOS/web ports (and possibly insecure) — do not ship until green.
 */
class Spake2P256Test {

    private val w = BigInteger("2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f", 16)
    private val x = BigInteger("43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729", 16)
    private val y = BigInteger("dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be", 16)

    private val idA = "server".toByteArray(Charsets.US_ASCII)
    private val idB = "client".toByteArray(Charsets.US_ASCII)

    private val pA = "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c"
    private val pB = "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7"
    private val kHex = "0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325"

    private val hashTt = "0e0672dc86f8e45565d338b0540abe6915bdf72e2b35b5c9e5663168e960a91b"
    private val ke = "0e0672dc86f8e45565d338b0540abe69"
    private val ka = "15bdf72e2b35b5c9e5663168e960a91b"
    private val kcA = "00c12546835755c86d8c0db7851ae86f"
    private val kcB = "a9fa3406c3b781b93d804485430ca27a"
    private val cA = "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0"
    private val cB = "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b"

    @Test
    fun shares_matchRfcVector() {
        assertEquals(pA, hex(Spake2P256.shareA(w, x)))
        assertEquals(pB, hex(Spake2P256.shareB(w, y)))
    }

    @Test
    fun sharedPoint_matchesFromBothSides() {
        assertEquals(kHex, hex(Spake2P256.keyA(w, x, bytes(pB))))
        assertEquals(kHex, hex(Spake2P256.keyB(w, y, bytes(pA))))
    }

    @Test
    fun transcriptAndKeys_matchRfcVector() {
        val tt = Spake2P256.transcript(idA, idB, bytes(pA), bytes(pB), bytes(kHex), w)
        val keys = Spake2P256.deriveKeys(tt)

        assertEquals(hashTt, hex(keys.sharedKey + keys.ka))
        assertEquals(ke, hex(keys.sharedKey))
        assertEquals(ka, hex(keys.ka))
        assertEquals(kcA, hex(keys.confirmKeyA))
        assertEquals(kcB, hex(keys.confirmKeyB))
        assertEquals(cA, hex(Spake2P256.confirm(keys.confirmKeyA, tt)))
        assertEquals(cB, hex(Spake2P256.confirm(keys.confirmKeyB, tt)))
    }

    @Test
    fun endToEnd_bothSidesAgree() {
        // Independent random run: A and B share the same secret w and derive the same session key.
        val secret = Spake2P256.randomScalar()
        val xa = Spake2P256.randomScalar()
        val yb = Spake2P256.randomScalar()

        val shareA = Spake2P256.shareA(secret, xa)
        val shareB = Spake2P256.shareB(secret, yb)
        val kA = Spake2P256.keyA(secret, xa, shareB)
        val kB = Spake2P256.keyB(secret, yb, shareA)
        assertEquals(hex(kA), hex(kB))

        val ttA = Spake2P256.transcript(idA, idB, shareA, shareB, kA, secret)
        val ttB = Spake2P256.transcript(idA, idB, shareA, shareB, kB, secret)
        val keysA = Spake2P256.deriveKeys(ttA)
        val keysB = Spake2P256.deriveKeys(ttB)
        assertEquals(hex(keysA.sharedKey), hex(keysB.sharedKey))
        // Each side verifies the other's confirmation MAC.
        assertEquals(
            hex(Spake2P256.confirm(keysA.confirmKeyB, ttA)),
            hex(Spake2P256.confirm(keysB.confirmKeyB, ttB))
        )
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) {
            ((Character.digit(hex[it * 2], 16) shl 4) + Character.digit(hex[it * 2 + 1], 16)).toByte()
        }
}
