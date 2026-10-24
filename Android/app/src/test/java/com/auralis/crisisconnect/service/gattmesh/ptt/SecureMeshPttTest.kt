package com.auralis.crisisconnect.service.gattmesh.ptt

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class SecureMeshPttTest {
    private val now = 1_700_000_000_000L
    private val key = ByteArray(32) { it.toByte() }
    private fun proof(nonce: String): ByteArray = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec("test-role-authority".toByteArray(), "HmacSHA256"))
    }.doFinal(nonce.toByteArray())
    private fun codec(group: ByteArray = key, clock: () -> Long = { now }) = SecureMeshPtt({ group },
        { proof(it) }, { supplied, nonce ->
            if (MessageDigest.isEqual(supplied, proof(nonce))) SecureMeshPtt.VerifiedRole("AFAD", now + 60_000) else null
        }, clock)

    @Test fun opensSwiftGeneratedFixture() {
        val fixture = java.util.Properties().apply {
            SecureMeshPttTest::class.java.getResourceAsStream("/security/swift-ptt.properties")!!.use { load(it) }
        }
        val receiver = codec()
        assertEquals("AFAD", receiver.decode(Base64.getDecoder().decode(fixture.getProperty("control")))?.agency)
        assertArrayEquals(byteArrayOf(0, 0, 0, 7, 0, 4, 1, 2, 3, 4),
            receiver.decode(Base64.getDecoder().decode(fixture.getProperty("audio")))?.body)
    }

    @Test fun protectsAudioAndControlAndRejectsReplayAndEveryChangedAuthenticatedByte() {
        val alice = codec(); val bob = codec()
        val control = alice.encode(1, "Alice", ByteArray(8))!!
        val body = ByteBuffer.allocate(4).putInt(7).array() + byteArrayOf(0, 4, 1, 2, 3, 4)
        val audio = alice.encode(5, "Alice", body)!!
        assertEquals("AFAD", bob.decode(control)?.agency)
        val opened = bob.decode(audio)!!
        assertArrayEquals(body, opened.body)
        assertNull(bob.decode(audio))
        assertNull(alice.decode(control))
        assertNull(codec().decode(audio))
        assertNull(codec(ByteArray(32)).decode(control))
        assertNull(codec(clock = { now + 11_000 }).decode(control))
        assertTrue("One Opus frame must fit the fast ATT path", audio.size + 3 <= 244)
        assertNull(codec().decode("{\"type\":\"ptt_audio\"}".toByteArray()))
        for (index in audio.indices) {
            if (index == 4) continue // bounded hop is intentionally relay-mutable
            val receiver = codec(); assertNotNull(receiver.decode(control))
            val tampered = audio.copyOf(); tampered[index] = (tampered[index].toInt() xor 1).toByte()
            assertNull("tampered byte $index", receiver.decode(tampered))
        }
        val relay = codec(); assertNotNull(relay.decode(control))
        assertEquals(2, relay.decode(SecureMeshPtt.relayed(audio, 2)!!)?.hop)
        assertNull(SecureMeshPtt.relayed(audio, 5))
        // Synthetic fixture, never a production credential or key.
        println("CPT2_CONTROL=" + Base64.getEncoder().encodeToString(control))
        println("CPT2_AUDIO=" + Base64.getEncoder().encodeToString(audio))
    }
}
