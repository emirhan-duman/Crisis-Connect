package com.auralis.crisisconnect.messaging

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Signal-style safety number for an internet chat: a 60-digit fingerprint derived from BOTH peers'
 * long-term identity public keys. Both sides compute the SAME number, so if two people read it out
 * (or scan it as a QR) and it matches, they've confirmed no man-in-the-middle swapped a key during
 * the QR/directory exchange. Any mismatch means the keys differ — do not trust the channel.
 */
object SafetyNumber {
    private const val DOMAIN = "crisisconnect:safetynumber:v1"
    private const val DIGITS_PER_PARTY = 30

    /** Returns the shared safety number formatted as 12 space-separated groups of 5 digits. */
    fun compute(
        localPublicKeyB64: String,
        remotePublicKeyB64: String,
        localUid: String,
        remoteUid: String
    ): String {
        val local = fingerprint(localPublicKeyB64, localUid)
        val remote = fingerprint(remotePublicKeyB64, remoteUid)
        // Order-independent so both peers derive the same string.
        val ordered = listOf(local, remote).sorted()
        return (ordered[0] + ordered[1]).chunked(5).joinToString(" ")
    }

    private fun fingerprint(publicKeyB64: String, uid: String): String {
        val digest = MessageDigest.getInstance("SHA-512")
            .digest("$DOMAIN:$uid:${publicKeyB64.trim()}".toByteArray(StandardCharsets.UTF_8))
        val value = BigInteger(1, digest.copyOf(16)).mod(BigInteger.TEN.pow(DIGITS_PER_PARTY))
        return value.toString().padStart(DIGITS_PER_PARTY, '0')
    }
}
