package com.auralis.crisisconnect.service.file

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileStreamConstantsTest {

    @Test
    fun `valid inbound metadata accepts bounded file`() {
        val valid = FileStreamConstants.isValidInboundMetadata(
            originalSizeBytes = 1_048_576L,
            totalBytes = 1_048_576,
            chunkSize = FileStreamConstants.CHUNK_SIZE,
            chunkCount = 2,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null,
            compression = FileStreamConstants.COMPRESSION_NONE
        )

        assertTrue(valid)
    }

    @Test
    fun `valid inbound metadata rejects mismatched chunk count`() {
        val valid = FileStreamConstants.isValidInboundMetadata(
            originalSizeBytes = 1_048_576L,
            totalBytes = 1_048_576,
            chunkSize = FileStreamConstants.CHUNK_SIZE,
            chunkCount = 3,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null,
            compression = FileStreamConstants.COMPRESSION_NONE
        )

        assertFalse(valid)
    }

    @Test
    fun `valid inbound metadata rejects unsupported compression`() {
        val valid = FileStreamConstants.isValidInboundMetadata(
            originalSizeBytes = 1_048_576L,
            totalBytes = 1_048_576,
            chunkSize = FileStreamConstants.CHUNK_SIZE,
            chunkCount = 2,
            sha256 = ByteArray(32),
            encrypted = false,
            iv = null,
            aad = null,
            compression = "tar"
        )

        assertFalse(valid)
    }
}
