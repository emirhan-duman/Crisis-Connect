package com.auralis.crisisconnect.messaging.signal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * FS-4: the v3 safety number is symmetric (both peers derive the same 60-digit fingerprint from
 * their two Curve25519 identity keys) and changes if either identity key changes.
 */
class SignalSafetyNumberTest {

    @Test
    fun bothSidesComputeTheSameNumber() {
        val alice = IdentityKeyPair.generate().publicKey
        val bob = IdentityKeyPair.generate().publicKey

        val fromAlice = SignalSafetyNumber.compute("alice-uid", alice, "bob-uid", bob)
        val fromBob = SignalSafetyNumber.compute("bob-uid", bob, "alice-uid", alice)

        assertEquals(fromAlice, fromBob)
        // 60 digits grouped into 12×5 with single spaces.
        assertEquals(60, fromAlice.filter { it.isDigit() }.length)
        assertEquals(11, fromAlice.count { it == ' ' })
        assertTrue(fromAlice.all { it.isDigit() || it == ' ' })
    }

    @Test
    fun differentKeyProducesDifferentNumber() {
        val alice = IdentityKeyPair.generate().publicKey
        val bob = IdentityKeyPair.generate().publicKey
        val impostor = IdentityKeyPair.generate().publicKey

        val real = SignalSafetyNumber.compute("alice-uid", alice, "bob-uid", bob)
        val swapped = SignalSafetyNumber.compute("alice-uid", alice, "bob-uid", impostor)

        assertNotEquals(real, swapped)
    }
}
