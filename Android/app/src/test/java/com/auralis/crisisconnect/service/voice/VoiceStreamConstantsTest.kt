package com.auralis.crisisconnect.service.voice

import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamConstantsTest {

    @Test
    fun `computeChunkSize scales down chunk count for short clips`() {
        val totalBytes = 48_000
        val chunkSize = VoiceStreamConstants.computeChunkSize(durationMs = 5_000L, totalBytes = totalBytes)

        assertTrue(chunkSize >= VoiceStreamConstants.CHUNK_SIZE)
        val chunkCount = ceil(totalBytes / chunkSize.toDouble()).toInt()
        assertEquals(3, chunkCount)
    }

    @Test
    fun `computeChunkSize targets about 20 chunks for long clips`() {
        val totalBytes = 480_000
        val chunkSize = VoiceStreamConstants.computeChunkSize(durationMs = 50_000L, totalBytes = totalBytes)

        val chunkCount = ceil(totalBytes / chunkSize.toDouble()).toInt()
        assertEquals(20, chunkCount)
    }

    @Test
    fun `computeChunkSize falls back to default when duration missing`() {
        val totalBytes = 10_000
        val chunkSize = VoiceStreamConstants.computeChunkSize(durationMs = null, totalBytes = totalBytes)

        assertEquals(VoiceStreamConstants.CHUNK_SIZE, chunkSize)
    }

    @Test
    fun `valid inbound metadata accepts bounded payload`() {
        val valid = VoiceStreamConstants.isValidInboundMetadata(
            totalBytes = 4_096,
            chunkSize = 512,
            chunkCount = 8,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null
        )

        assertTrue(valid)
    }

    @Test
    fun `valid inbound metadata rejects mismatched chunk count`() {
        val valid = VoiceStreamConstants.isValidInboundMetadata(
            totalBytes = 4_096,
            chunkSize = 512,
            chunkCount = 7,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null
        )

        assertFalse(valid)
    }

    @Test
    fun `valid inbound metadata rejects invalid encrypted iv`() {
        val valid = VoiceStreamConstants.isValidInboundMetadata(
            totalBytes = 4_096,
            chunkSize = 512,
            chunkCount = 8,
            sha256 = ByteArray(32),
            encrypted = true,
            iv = ByteArray(8),
            aad = ByteArray(16)
        )

        assertFalse(valid)
    }
}
