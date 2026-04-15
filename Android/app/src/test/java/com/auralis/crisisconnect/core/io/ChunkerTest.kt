package com.auralis.crisisconnect.core.io

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkerTest {

    @Test
    fun `chunk splits data correctly`() {
        val data = ByteArray(1300) { it.toByte() }
        val chunks = Chunker.chunk(data, 512)
        assertEquals(3, chunks.size)
        assertEquals(512, chunks[0].size)
        assertEquals(512, chunks[1].size)
        assertEquals(276, chunks[2].size)
        assertArrayEquals(data.copyOfRange(0, 512), chunks[0])
        assertArrayEquals(data.copyOfRange(512, 1024), chunks[1])
        assertArrayEquals(data.copyOfRange(1024, 1300), chunks[2])
    }

    @Test
    fun `crc32 matches repeated calculation`() {
        val payload = "crc test".toByteArray()
        val first = Chunker.crc32(payload)
        val second = Chunker.crc32(payload)
        assertEquals(first, second)
    }

    @Test
    fun `sha256 produces expected digest length`() {
        val payload = ByteArray(32) { it.toByte() }
        val digest = Chunker.sha256(payload)
        assertEquals(32, digest.size)
    }
}
