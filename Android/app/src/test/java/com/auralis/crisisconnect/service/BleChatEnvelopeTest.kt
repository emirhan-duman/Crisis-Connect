package com.auralis.crisisconnect.service

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BleChatEnvelopeTest {

    @Test
    fun `encode and decode chat roundtrip applies safety normalization`() {
        val encoded = BleChatEnvelope.encodeChat(
            messageId = "  msg-1  ",
            text = "Acil mesaj",
            createdAtMillis = 1000L,
            ttlMillis = 0L,
            attempt = 0,
            route = " FAST Route#1 "
        )

        val decoded = BleChatEnvelope.decodeChat(encoded)

        assertNotNull(decoded)
        decoded ?: return
        assertEquals("msg-1", decoded.messageId)
        assertEquals("Acil mesaj", decoded.text)
        assertEquals(1000L, decoded.createdAtMillis)
        assertEquals(1L, decoded.ttlMillis)
        assertEquals(1, decoded.attempt)
        assertEquals("fast_route_1", decoded.route)
    }

    @Test
    fun `decode chat returns null for malformed packets`() {
        assertNull(BleChatEnvelope.decodeChat("invalid"))
        assertNull(BleChatEnvelope.decodeChat("CCMSG1|READ|x"))
    }

    @Test
    fun `decode chat returns null when base64 payload is invalid`() {
        val raw = "CCMSG1|CHAT|id-1|100|200|1|route|%%%notbase64%%%"
        assertNull(BleChatEnvelope.decodeChat(raw))
    }

    @Test
    fun `isExpired only after ttl deadline`() {
        val payload = BleChatEnvelope.ChatPayload(
            messageId = "m1",
            text = "hello",
            createdAtMillis = 10_000L,
            ttlMillis = 5_000L,
            attempt = 1,
            route = "rfcomm"
        )
        assertFalse(BleChatEnvelope.isExpired(payload, nowMillis = 15_000L))
        assertTrue(BleChatEnvelope.isExpired(payload, nowMillis = 15_001L))
    }

    @Test
    fun `encode delivered ack without id is type only`() {
        val bytes = BleChatEnvelope.encodeDeliveredAck("   ")
        val decoded = BleChatEnvelope.decodeAck(bytes)
        assertNotNull(decoded)
        decoded ?: return
        assertEquals(BleChatEnvelope.AckType.DELIVERED, decoded.type)
        assertTrue(decoded.messageIds.isEmpty())
    }

    @Test
    fun `encode delivered ack trims id`() {
        val bytes = BleChatEnvelope.encodeDeliveredAck("  abc-1  ")
        val decoded = BleChatEnvelope.decodeAck(bytes)
        assertNotNull(decoded)
        decoded ?: return
        assertEquals(BleChatEnvelope.AckType.DELIVERED, decoded.type)
        assertEquals(listOf("abc-1"), decoded.messageIds)
    }

    @Test
    fun `encode read ack deduplicates ids and keeps payload bounded`() {
        val ids = buildList {
            repeat(70) { index ->
                add(" msg-${index % 8} ")
            }
            repeat(80) { index ->
                add("x${index.toString().padStart(3, '0')}")
            }
        }

        val bytes = BleChatEnvelope.encodeReadAck(ids)
        val decoded = BleChatEnvelope.decodeAck(bytes)
        val payloadSize = bytes.toString(StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8).size

        assertNotNull(decoded)
        decoded ?: return
        assertEquals(BleChatEnvelope.AckType.READ, decoded.type)
        assertTrue(decoded.messageIds.isNotEmpty())
        assertEquals(decoded.messageIds.size, decoded.messageIds.distinct().size)
        assertTrue(payloadSize <= 185)
    }

    @Test
    fun `decode ack supports case-insensitive type and normalized ids`() {
        val decoded = BleChatEnvelope.decodeAck("read| a1, a1, a2 ,, ".toByteArray())
        assertNotNull(decoded)
        decoded ?: return
        assertEquals(BleChatEnvelope.AckType.READ, decoded.type)
        assertEquals(listOf("a1", "a2"), decoded.messageIds)
    }

    @Test
    fun `decode ack returns null for unknown type or malformed shape`() {
        assertNull(BleChatEnvelope.decodeAck(ByteArray(0)))
        assertNull(BleChatEnvelope.decodeAck("UNKNOWN|a".toByteArray()))
        assertNull(BleChatEnvelope.decodeAck("READ a,b".toByteArray()))
    }
}
