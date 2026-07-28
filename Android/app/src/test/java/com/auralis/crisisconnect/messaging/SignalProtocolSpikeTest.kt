package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore

/**
 * FS-0 spike: proves the libsignal dependency works end-to-end on the JVM before any app
 * integration — X3DH/PQXDH session establishment from a prekey bundle, Double Ratchet advance in
 * both directions, and out-of-order delivery. Mirrors exactly the flow the app will use
 * (SessionBuilder.process(bundle) on first send → PreKeySignalMessage → SignalMessage after).
 */
class SignalProtocolSpikeTest {

    private val aliceAddress = SignalProtocolAddress("alice-uid", 1)
    private val bobAddress = SignalProtocolAddress("bob-uid", 1)

    private fun store(): InMemorySignalProtocolStore =
        InMemorySignalProtocolStore(IdentityKeyPair.generate(), (1..16380).random())

    /** Bob publishes a bundle (like our fetchSignalPreKeyBundle callable would return). */
    private fun bundleFor(bob: InMemorySignalProtocolStore): PreKeyBundle {
        val preKeyPair = ECKeyPair.generate()
        val signedPreKeyPair = ECKeyPair.generate()
        val signedPreKeySignature =
            bob.identityKeyPair.privateKey.calculateSignature(signedPreKeyPair.publicKey.serialize())
        val kyberKeyPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        val kyberSignature =
            bob.identityKeyPair.privateKey.calculateSignature(kyberKeyPair.publicKey.serialize())

        bob.storePreKey(31337, PreKeyRecord(31337, preKeyPair))
        bob.storeSignedPreKey(22, SignedPreKeyRecord(22, System.currentTimeMillis(), signedPreKeyPair, signedPreKeySignature))
        bob.storeKyberPreKey(7, KyberPreKeyRecord(7, System.currentTimeMillis(), kyberKeyPair, kyberSignature))

        return PreKeyBundle(
            bob.localRegistrationId,
            1, // deviceId
            31337,
            preKeyPair.publicKey,
            22,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            bob.identityKeyPair.publicKey,
            7,
            kyberKeyPair.publicKey,
            kyberSignature,
        )
    }

    @Test
    fun x3dhSession_establishes_and_ratchets_bothDirections() {
        val alice = store()
        val bob = store()

        SessionBuilder(alice, bobAddress).process(bundleFor(bob))

        val aliceCipher = SessionCipher(alice, bobAddress)
        val bobCipher = SessionCipher(bob, aliceAddress)

        // First message rides as a PreKeySignalMessage (carries the X3DH handshake).
        val first = aliceCipher.encrypt("Enkaz altındayım, 2 kişi mahsur".toByteArray())
        assertEquals(CiphertextMessage.PREKEY_TYPE, first.type)
        val firstPlain = bobCipher.decrypt(PreKeySignalMessage(first.serialize()))
        assertEquals("Enkaz altındayım, 2 kişi mahsur", String(firstPlain))

        // Bob replies — session is established, plain SignalMessage from here on.
        val reply = bobCipher.encrypt("Konumunu gönder, ekip yolda".toByteArray())
        assertEquals(CiphertextMessage.WHISPER_TYPE, reply.type)
        assertEquals("Konumunu gönder, ekip yolda", String(aliceCipher.decrypt(SignalMessage(reply.serialize()))))

        // Ratchet advances across many turns; every ciphertext differs even for equal plaintext.
        val seen = mutableSetOf<String>()
        repeat(25) { i ->
            val fromAlice = aliceCipher.encrypt("ping".toByteArray())
            assertEquals("ping", String(bobCipher.decrypt(SignalMessage(fromAlice.serialize()))))
            val fromBob = bobCipher.encrypt("pong".toByteArray())
            assertEquals("pong", String(aliceCipher.decrypt(SignalMessage(fromBob.serialize()))))
            assertTrue("ciphertext repeated at turn $i", seen.add(fromAlice.serialize().toList().toString()))
        }
    }

    @Test
    fun outOfOrderDelivery_isHandled_bySkippedMessageKeys() {
        val alice = store()
        val bob = store()
        SessionBuilder(alice, bobAddress).process(bundleFor(bob))
        val aliceCipher = SessionCipher(alice, bobAddress)
        val bobCipher = SessionCipher(bob, aliceAddress)

        // Establish AND confirm the session (Alice keeps sending PreKeySignalMessages until she
        // has processed one reply from Bob — only then do her messages become plain SignalMessages).
        bobCipher.decrypt(PreKeySignalMessage(aliceCipher.encrypt("m0".toByteArray()).serialize()))
        aliceCipher.decrypt(SignalMessage(bobCipher.encrypt("ack".toByteArray()).serialize()))

        val m1 = aliceCipher.encrypt("m1".toByteArray()).serialize()
        val m2 = aliceCipher.encrypt("m2".toByteArray()).serialize()
        val m3 = aliceCipher.encrypt("m3".toByteArray()).serialize()

        // Deliver 3, then 1, then 2 — the ratchet's skipped-key store must recover all of them.
        assertEquals("m3", String(bobCipher.decrypt(SignalMessage(m3))))
        assertEquals("m1", String(bobCipher.decrypt(SignalMessage(m1))))
        assertEquals("m2", String(bobCipher.decrypt(SignalMessage(m2))))
    }

    @Test
    fun tamperedCiphertext_failsClosed() {
        val alice = store()
        val bob = store()
        SessionBuilder(alice, bobAddress).process(bundleFor(bob))
        val aliceCipher = SessionCipher(alice, bobAddress)
        val bobCipher = SessionCipher(bob, aliceAddress)
        bobCipher.decrypt(PreKeySignalMessage(aliceCipher.encrypt("m0".toByteArray()).serialize()))
        aliceCipher.decrypt(SignalMessage(bobCipher.encrypt("ack".toByteArray()).serialize()))

        val bytes = aliceCipher.encrypt("gizli".toByteArray()).serialize()
        SignalMessage(bytes) // must parse as a valid (untampered) SignalMessage before we corrupt it
        bytes[bytes.size - 3] = (bytes[bytes.size - 3].toInt() xor 0x41).toByte()
        val survived = runCatching { bobCipher.decrypt(SignalMessage(bytes)) }
        assertFalse("tampered message must not decrypt", survived.isSuccess)
    }
}
