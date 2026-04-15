package com.auralis.crisisconnect.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrisisLinkQueueCodecTest {

    private val keyBytes = ByteArray(32) { index -> (index + 1).toByte() }

    @Test
    fun `encoded payload round trips as encrypted packet`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val plaintext = """[{"uid":"uid-1","role":"fieldteam"}]""".toByteArray(Charsets.UTF_8)

        val encoded = codec.encode(plaintext)

        assertNotNull(encoded)
        encoded ?: return
        assertTrue(codec.isEncrypted(encoded))
        assertFalse(encoded.contentEquals(plaintext))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("uid-1"))

        val decoded = codec.decode(encoded)
        assertNotNull(decoded)
        decoded ?: return
        assertFalse(decoded.isLegacyPlaintext)
        assertArrayEquals(plaintext, decoded.plaintext)
    }

    @Test
    fun `legacy plaintext payload is still decodable`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val plaintext = """[{"uid":"legacy"}]""".toByteArray(Charsets.UTF_8)

        val decoded = codec.decode(plaintext)

        assertNotNull(decoded)
        decoded ?: return
        assertTrue(decoded.isLegacyPlaintext)
        assertArrayEquals(plaintext, decoded.plaintext)
    }

    @Test
    fun `decode rejects unsupported encrypted version`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val plaintext = """[{"uid":"version"}]""".toByteArray(Charsets.UTF_8)
        val encoded = codec.encode(plaintext)
        assertNotNull(encoded)
        encoded ?: return

        encoded[CrisisLinkQueueCodec.MAGIC.size] = (CrisisLinkQueueCodec.FORMAT_VERSION + 1).toByte()

        val decoded = codec.decode(encoded)
        assertNull(decoded)
    }

    @Test
    fun `decode returns null when encrypted payload key is unavailable`() {
        val codecWithKey = createCodec(keyResolver = { keyBytes })
        val encrypted = codecWithKey.encode("""[{"uid":"key-missing"}]""".toByteArray(Charsets.UTF_8))
        assertNotNull(encrypted)
        encrypted ?: return

        val codecWithoutKey = createCodec(keyResolver = { null })
        assertNull(codecWithoutKey.encode(ByteArray(4) { it.toByte() }))
        assertNull(codecWithoutKey.decode(encrypted))
    }

    @Test
    fun `decode returns empty payload for empty file`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val decoded = codec.decode(ByteArray(0))
        assertNotNull(decoded)
        decoded ?: return
        assertFalse(decoded.isLegacyPlaintext)
        assertTrue(decoded.plaintext.isEmpty())
    }

    @Test
    fun `isEncrypted returns false for malformed magic`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        assertFalse(codec.isEncrypted(payload))
    }

    @Test
    fun `decode returns null for truncated encrypted packet`() {
        val codec = createCodec(keyResolver = { keyBytes })
        val truncated = ByteArray(CrisisLinkQueueCodec.MAGIC.size + 1).apply {
            System.arraycopy(
                CrisisLinkQueueCodec.MAGIC,
                0,
                this,
                0,
                CrisisLinkQueueCodec.MAGIC.size
            )
            this[CrisisLinkQueueCodec.MAGIC.size] = CrisisLinkQueueCodec.FORMAT_VERSION
        }
        assertNull(codec.decode(truncated))
    }

    @Test
    fun `encode returns null when encryptor throws`() {
        val codec = CrisisLinkQueueCodec(
            keyResolver = { keyBytes },
            encryptor = { _, _ -> error("encrypt fail") },
            decryptor = ::xorDecrypt
        )
        assertNull(codec.encode("abc".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `decode returns null when decryptor throws`() {
        val baseCodec = createCodec(keyResolver = { keyBytes })
        val encoded = baseCodec.encode("abc".toByteArray(Charsets.UTF_8))
        assertNotNull(encoded)
        encoded ?: return

        val codec = CrisisLinkQueueCodec(
            keyResolver = { keyBytes },
            encryptor = ::xorEncrypt,
            decryptor = { _, _ -> error("decrypt fail") }
        )
        assertNull(codec.decode(encoded))
    }

    private fun createCodec(keyResolver: () -> ByteArray?): CrisisLinkQueueCodec {
        return CrisisLinkQueueCodec(
            keyResolver = keyResolver,
            encryptor = ::xorEncrypt,
            decryptor = ::xorDecrypt
        )
    }

    private fun xorEncrypt(key: ByteArray, plain: ByteArray): ByteArray {
        return plain.mapIndexed { index, byte ->
            (byte.toInt() xor key[index % key.size].toInt()).toByte()
        }.toByteArray()
    }

    private fun xorDecrypt(key: ByteArray, encrypted: ByteArray): ByteArray {
        return xorEncrypt(key, encrypted)
    }
}
