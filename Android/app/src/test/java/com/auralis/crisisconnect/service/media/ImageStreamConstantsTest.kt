package com.auralis.crisisconnect.service.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageStreamConstantsTest {

    @Test
    fun `valid inbound metadata accepts bounded image`() {
        val totalBytes = 1_048_576
        val chunkSize = ImageStreamConstants.computeChunkSize(totalBytes)
        val chunkCount = (totalBytes + chunkSize - 1) / chunkSize
        val valid = ImageStreamConstants.isValidInboundMetadata(
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null
        )

        assertTrue(valid)
    }

    @Test
    fun `valid inbound metadata rejects oversized image`() {
        val totalBytes = ImageStreamConstants.MAX_TOTAL_BYTES + 1
        val chunkSize = ImageStreamConstants.CHUNK_SIZE
        val chunkCount = (totalBytes + chunkSize - 1) / chunkSize
        val valid = ImageStreamConstants.isValidInboundMetadata(
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null
        )

        assertFalse(valid)
    }

    @Test
    fun `valid inbound metadata rejects bad encrypted aad`() {
        val totalBytes = 1_048_576
        val chunkSize = ImageStreamConstants.computeChunkSize(totalBytes)
        val chunkCount = (totalBytes + chunkSize - 1) / chunkSize
        val valid = ImageStreamConstants.isValidInboundMetadata(
            totalBytes = totalBytes,
            chunkSize = chunkSize,
            chunkCount = chunkCount,
            sha256 = ByteArray(32),
            encrypted = true,
            iv = ByteArray(12),
            aad = null
        )

        assertFalse(valid)
    }

    @Test
    fun `compute chunk size keeps small image frames below rfcomm-heavy payload size`() {
        val chunkSize = ImageStreamConstants.computeChunkSize(totalBytes = 140_000)

        assertTrue(chunkSize < 64 * 1024)
    }
}
