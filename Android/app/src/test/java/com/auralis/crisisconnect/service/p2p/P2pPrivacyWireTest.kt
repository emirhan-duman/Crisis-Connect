package com.auralis.crisisconnect.service.p2p

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class P2pPrivacyWireTest {
    private val key = ByteArray(32) { it.toByte() }
    @Test fun qrMetadataRequiresCorrectSecretAndDomainAndRejectsLegacyAndTamper() {
        val plain = "{\"serverDeviceId\":\"permanent-123\",\"name\":\"Ayşe\",\"sessionCode\":\"ABCD123\",\"avatarB64\":\"private-photo\"}".toByteArray()
        for (purpose in P2pPrivacyWire.Purpose.entries) {
            val wire = P2pPrivacyWire.seal(plain, key, purpose)!!
            assertArrayEquals(plain, P2pPrivacyWire.open(wire, key, purpose))
            assertFalse(wire.toString(Charsets.UTF_8).contains("permanent-123"))
            assertNull(P2pPrivacyWire.open(wire, ByteArray(32), purpose))
            assertNull(P2pPrivacyWire.open(plain, key, purpose))
            val other = P2pPrivacyWire.Purpose.entries.first { it != purpose }
            assertNull(P2pPrivacyWire.open(wire, key, other))
            for (index in wire.indices) {
                val tampered = wire.copyOf(); tampered[index] = (tampered[index].toInt() xor 1).toByte()
                assertNull(P2pPrivacyWire.open(tampered, key, purpose))
            }
        }
    }
    @Test fun controlFragmentsRoundTripAtDefaultMtuAndCannotGrowWithoutBound() {
        val plain = ByteArray(10_000) { (it % 251).toByte() }
        val sealed = P2pPrivacyWire.seal(plain, key, P2pPrivacyWire.Purpose.CONTROL)!!
        val framed = P2pPrivacyWire.frameControl(sealed)!!
        val receiver = P2pPrivacyWire.ControlReceiver()
        val chunks = framed.asList().chunked(20)
        chunks.dropLast(1).forEach { assertNull(receiver.receive(it.toByteArray())) }
        assertArrayEquals(sealed, receiver.receive(chunks.last().toByteArray()))
        assertArrayEquals(plain, P2pPrivacyWire.open(sealed, key, P2pPrivacyWire.Purpose.CONTROL))
        assertNull(receiver.receive(byteArrayOf(-1, -1)))
        assertNull(receiver.receive(ByteArray(60_003)))
        assertArrayEquals(sealed, receiver.receive(framed))
    }
    @Test fun randomRoutesHideDeviceIdAndBindKeyAndExactCiphertext() {
        val packet = "encrypted chat bytes".toByteArray()
        val alias = P2pPrivacyWire.alias(key, packet)
        assertTrue(P2pPrivacyWire.matches(alias, key, packet))
        assertFalse(P2pPrivacyWire.matches(alias, ByteArray(32), packet))
        assertFalse(P2pPrivacyWire.matches(alias, key, packet + 1.toByte()))
        assertFalse(P2pPrivacyWire.matches("device:permanent", key, packet))
        assertNotEquals(alias, P2pPrivacyWire.alias(key, packet))
        assertFalse(P2pPrivacyWire.matches(alias.uppercase(), key, packet))
    }
    @Test fun readyRequiresFreshSingleUseChallengeNotCapturedOrExpiredResponse() {
        val challenge = P2pPrivacyWire.Challenge(100)
        assertFalse(challenge.accept("old-captured-nonce", 101))
        assertTrue(challenge.accept(challenge.nonce, 102))
        assertFalse(challenge.accept(challenge.nonce, 103))
        val expired = P2pPrivacyWire.Challenge(100)
        assertFalse(expired.accept(expired.nonce, 10_000_000_101))
        assertNotEquals(challenge.nonce, expired.nonce)
    }
    @Test fun acceptsSwiftBootstrapAndRouteVector() {
        val properties = java.util.Properties().apply { P2pPrivacyWireTest::class.java.getResourceAsStream("/security/swift-privacy.properties")!!.use { load(it) } }
        val wire = Base64.getDecoder().decode(properties.getProperty("bootstrap"))
        val plain = Base64.getDecoder().decode(properties.getProperty("plaintext"))
        assertArrayEquals(plain, P2pPrivacyWire.open(wire, key, P2pPrivacyWire.Purpose.BOOTSTRAP))
        assertTrue(P2pPrivacyWire.matches(properties.getProperty("alias"), key, wire))
        val androidWire = P2pPrivacyWire.seal(plain, key, P2pPrivacyWire.Purpose.BOOTSTRAP)!!
        println("ANDROID_PRIVACY_WIRE=" + Base64.getEncoder().encodeToString(androidWire))
        println("ANDROID_PRIVACY_ALIAS=" + P2pPrivacyWire.alias(key, androidWire, ByteArray(16) { it.toByte() }))
    }
}
