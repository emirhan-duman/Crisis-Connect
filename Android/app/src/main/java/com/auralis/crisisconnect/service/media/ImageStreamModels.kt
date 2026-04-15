package com.auralis.crisisconnect.service.media

import kotlin.math.ceil

object ImageStreamConstants {
    // RFCOMM carries newline-delimited JSON frames; large base64 payloads incur
    // substantial latency before the peer can parse and acknowledge a chunk.
    private const val DEFAULT_CHUNK_SIZE = 48 * 1024
    const val CHUNK_SIZE = DEFAULT_CHUNK_SIZE
    const val WINDOW_SIZE = 12
    const val ACK_INTERVAL = 24
    const val MAX_RETRY = 5
    const val CHUNK_TIMEOUT_MS = 15_000L
    const val OUTBOX_DIR = "image_outbox"
    const val INBOX_DIR = "image_inbox"
    const val MAX_TOTAL_BYTES = 12 * 1024 * 1024

    private const val MIN_CHUNK_COUNT = 3
    private const val MAX_CHUNK_COUNT = 120
    private const val MIN_CHUNK_SIZE = 24 * 1024
    private const val MAX_CHUNK_SIZE = 128 * 1024
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
        totalBytes: Int,
        chunkSize: Int,
        chunkCount: Int,
        sha256: ByteArray,
        encrypted: Boolean,
        iv: ByteArray?,
        aad: ByteArray?
    ): Boolean {
        if (totalBytes !in 1..MAX_TOTAL_BYTES) {
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

    private fun expectedChunkCount(totalBytes: Int, chunkSize: Int): Int =
        ((totalBytes + chunkSize - 1) / chunkSize)
}

enum class ImageTransferDirection {
    Upload,
    Download
}

enum class ImageTransferState {
    Initializing,
    Transferring,
    Waiting,
    Verifying,
    Completed,
    Failed
}

data class ImageTransferProgress(
    val sessionCode: String,
    val uuid: String,
    val direction: ImageTransferDirection,
    val totalChunks: Int,
    val confirmedChunks: Int,
    val pendingChunks: Int,
    val inFlightChunks: Int,
    val state: ImageTransferState
) {
    val remainingChunks: Int = (totalChunks - confirmedChunks).coerceAtLeast(0)
    val percentage: Float = if (totalChunks == 0) 0f else confirmedChunks / totalChunks.toFloat()
}

data class ImageStreamMetadata(
    val uuid: String,
    val totalBytes: Int,
    val mime: String,
    val width: Int?,
    val height: Int?,
    val encrypted: Boolean,
    val chunkSize: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val iv: ByteArray?,
    val aad: ByteArray?
)
