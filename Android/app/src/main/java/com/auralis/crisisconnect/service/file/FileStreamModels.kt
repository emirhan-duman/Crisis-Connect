package com.auralis.crisisconnect.service.file

import kotlin.math.ceil

object FileStreamConstants {
    private const val DEFAULT_CHUNK_SIZE = 512 * 1024
    const val CHUNK_SIZE = DEFAULT_CHUNK_SIZE
    const val WINDOW_SIZE = 10
    const val ACK_INTERVAL = 20
    const val MAX_RETRY = 5
    const val CHUNK_TIMEOUT_MS = 3_000L
    const val OUTBOX_DIR = "file_outbox"
    const val INBOX_DIR = "file_inbox"
    const val MAX_SOURCE_BYTES = 25L * 1024L * 1024L
    const val MAX_TRANSFER_BYTES = (15 * 1024 * 1024) + 16
    const val COMPRESSION_NONE = "none"
    const val COMPRESSION_ZIP = "zip"

    private const val MIN_CHUNK_COUNT = 4
    private const val MAX_CHUNK_COUNT = 160
    private const val MIN_CHUNK_SIZE = 128 * 1024
    private const val MAX_CHUNK_SIZE = 768 * 1024
    private const val SHA256_LENGTH_BYTES = 32
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val MAX_AAD_LENGTH_BYTES = 1_024

    fun computeChunkSize(totalBytes: Int): Int {
        if (totalBytes <= 0) {
            return DEFAULT_CHUNK_SIZE
        }
        val estimatedChunks = ((totalBytes + DEFAULT_CHUNK_SIZE - 1) / DEFAULT_CHUNK_SIZE)
            .coerceIn(MIN_CHUNK_COUNT, MAX_CHUNK_COUNT)
        val computedSize = ceil(totalBytes / estimatedChunks.toDouble()).toInt()
        return computedSize.coerceIn(MIN_CHUNK_SIZE, MAX_CHUNK_SIZE)
    }

    fun isValidInboundMetadata(
        originalSizeBytes: Long,
        totalBytes: Int,
        chunkSize: Int,
        chunkCount: Int,
        sha256: ByteArray,
        encrypted: Boolean,
        iv: ByteArray?,
        aad: ByteArray?,
        compression: String
    ): Boolean {
        if (originalSizeBytes !in 1..MAX_SOURCE_BYTES) {
            return false
        }
        if (totalBytes !in 1..MAX_TRANSFER_BYTES) {
            return false
        }
        if (chunkSize !in MIN_CHUNK_SIZE..MAX_CHUNK_SIZE) {
            return false
        }
        if (chunkCount !in 1..MAX_CHUNK_COUNT) {
            return false
        }
        if (chunkCount != expectedChunkCount(totalBytes, chunkSize)) {
            return false
        }
        if (sha256.size != SHA256_LENGTH_BYTES) {
            return false
        }
        if (!isSupportedCompression(compression)) {
            return false
        }
        if (encrypted) {
            if (iv?.size != GCM_IV_LENGTH_BYTES) {
                return false
            }
            if (aad == null || aad.isEmpty() || aad.size > MAX_AAD_LENGTH_BYTES) {
                return false
            }
        } else if (aad != null && aad.size > MAX_AAD_LENGTH_BYTES) {
            return false
        }
        return true
    }

    private fun isSupportedCompression(compression: String): Boolean {
        return compression == COMPRESSION_NONE || compression == COMPRESSION_ZIP
    }

    private fun expectedChunkCount(totalBytes: Int, chunkSize: Int): Int =
        ((totalBytes + chunkSize - 1) / chunkSize)
}

data class FileStreamMetadata(
    val uuid: String,
    val displayName: String,
    val originalSizeBytes: Long,
    val totalBytes: Int,
    val mime: String?,
    val compression: String,
    val encrypted: Boolean,
    val chunkSize: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val iv: ByteArray?,
    val aad: ByteArray?
)
