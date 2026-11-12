package com.auralis.crisisconnect.service

import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class SecureRfcommIdentityTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val publicKey = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().public.encoded)
    private val local = SecureRfcommIdentity.Identity("local-user", Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair().public.encoded))
    private fun open(socket: SecureRfcommIdentity, pairKey: String, response: String) = socket.open(pairKey, response, local)
    private fun answer(socket: SecureRfcommIdentity) = requireNotNull(SecureRfcommIdentity().answer(key, socket.challenge(), "alice", publicKey))

    @Test fun firstIdentityRequiresPairKeyAndCurrentSocketChallenge() {
        val socket = SecureRfcommIdentity()
        assertEquals(SecureRfcommIdentity.Identity("alice", publicKey), open(socket, key, answer(socket)))
    }
    @Test fun unsignedLegacyIdentityCannotEnrichContact() {
        assertNull(open(SecureRfcommIdentity(), key, "PEER_IDENTITY|attacker|$publicKey"))
    }
    @Test fun replyFromPriorSocketCannotBeReplayed() {
        assertNull(open(SecureRfcommIdentity(), key, answer(SecureRfcommIdentity())))
    }
    @Test fun responseIsConsumedOnlyOnce() {
        val socket = SecureRfcommIdentity(); val response = answer(socket)
        assertNotNull(open(socket, key, response)); assertNull(open(socket, key, response))
    }
    @Test fun wrongKeyAndModifiedIdentityFailWithoutConsumingLegitimateResponse() {
        val socket = SecureRfcommIdentity(); val response = answer(socket)
        assertNull(open(socket, Base64.getEncoder().encodeToString(ByteArray(32) { 8 }), response))
        val alteredBody = Base64.getDecoder().decode(response.substringAfter("V2:").trim())
            .toString(Charsets.UTF_8).replace("|alice|", "|mallory|")
        val altered = "PEER_IDENTITY|V2:" + Base64.getEncoder().encodeToString(alteredBody.toByteArray())
        assertNull(open(socket, key, altered))
        assertNotNull(open(socket, key, response))
    }
    @Test fun missingKeysMalformedKeysAndDelimiterInjectionFailClosed() {
        val socket = SecureRfcommIdentity()
        assertNull(SecureRfcommIdentity().answer("", socket.challenge(), "alice", publicKey))
        assertNull(SecureRfcommIdentity().answer(key, socket.challenge(), "alice|mallory", publicKey))
        assertNull(SecureRfcommIdentity().answer(key, "ID_CHALLENGE|bad", "alice", publicKey))
        assertNull(SecureRfcommIdentity().answer(key, socket.challenge(), "alice", "not-a-public-key"))
    }
    @Test fun reflectedChallengeCannotObtainProofAndOtherLocalSocketCannotPinOwnIdentity() {
        val socket = SecureRfcommIdentity()
        assertNull(socket.answer(key, socket.challenge(), local.uid, local.publicKey))
        val otherLocalSocket = SecureRfcommIdentity()
        val reflected = requireNotNull(otherLocalSocket.answer(key, socket.challenge(), local.uid, local.publicKey))
        assertNull(socket.open(key, reflected, local))
        assertNotNull(open(socket, key, answer(socket)))
    }
    @Test fun bothLegitimateDirectionsAuthenticateWithDistinctSocketNonces() {
        val alice = SecureRfcommIdentity(); val bob = SecureRfcommIdentity()
        val aliceIdentity = SecureRfcommIdentity.Identity("alice", publicKey)
        val aToB = requireNotNull(alice.answer(key, bob.challenge(), aliceIdentity.uid, aliceIdentity.publicKey))
        val bToA = requireNotNull(bob.answer(key, alice.challenge(), local.uid, local.publicKey))
        assertEquals(aliceIdentity, bob.open(key, aToB, local))
        assertEquals(local, alice.open(key, bToA, aliceIdentity))
    }
    @Test fun legacyPeersIgnoreBothEnvelopeKindsWithoutBindingOrChatBubbles() {
        val socket = SecureRfcommIdentity()
        for (frame in listOf(socket.challenge(), answer(socket))) {
            assertTrue(frame.startsWith("PEER_IDENTITY|"))
            assertEquals(2, frame.trim().split('|', limit = 3).size)
        }
        assertEquals(SecureRfcommIdentity.Kind.CHALLENGE, SecureRfcommIdentity.kind(socket.challenge()))
        assertEquals(SecureRfcommIdentity.Kind.PROOF, SecureRfcommIdentity.kind(answer(socket)))
    }

}
