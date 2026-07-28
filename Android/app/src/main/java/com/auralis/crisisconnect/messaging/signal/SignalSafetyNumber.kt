package com.auralis.crisisconnect.messaging.signal

import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator
import java.nio.charset.StandardCharsets

/**
 * The forward-secret (v3) safety number: libsignal's numeric fingerprint over the two peers'
 * Curve25519 identity keys. Both sides compute the SAME 60-digit number (the generator is
 * symmetric), so reading it out confirms no key was swapped during the X3DH/PQXDH handshake — the
 * v3 analogue of [com.auralis.crisisconnect.messaging.SafetyNumber] (which fingerprints the v2 P-256
 * keys). When a contact upgrades from v2 to v3 the displayed number changes because the underlying
 * key + algorithm changed — this is a security UPGRADE, not a MITM, and the UI labels it as such.
 */
object SignalSafetyNumber {
    // Signal's standard iteration count + fingerprint version.
    private const val ITERATIONS = 5200
    private const val VERSION = 0

    fun compute(
        localUid: String,
        localIdentityKey: IdentityKey,
        remoteUid: String,
        remoteIdentityKey: IdentityKey
    ): String {
        val fingerprint = NumericFingerprintGenerator(ITERATIONS).createFor(
            VERSION,
            localUid.toByteArray(StandardCharsets.UTF_8),
            localIdentityKey,
            remoteUid.toByteArray(StandardCharsets.UTF_8),
            remoteIdentityKey
        )
        // getDisplayText() is 60 digits; group into 12×5 to match the v2 safety-number layout.
        return fingerprint.displayableFingerprint.displayText.chunked(5).joinToString(" ")
    }
}
