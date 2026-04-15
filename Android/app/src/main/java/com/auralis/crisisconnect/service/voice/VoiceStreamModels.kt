package com.auralis.crisisconnect.service.voice

import kotlin.math.ceil

/**
 * Constants for the voice streaming protocol.
 */

object VoiceStreamConstants {
    const val CHUNK_SIZE = 512
    const val WINDOW_SIZE = 8
    const val ACK_INTERVAL = 32
    const val MAX_RETRY = 5
    const val CHUNK_TIMEOUT_MS = 3_000L
    const val OUTBOX_DIR = "voice_outbox"
    const val INBOX_DIR = "voice_inbox"
    const val MAX_TOTAL_BYTES = 80 * 32 * 1024

    private const val MIN_CHUNK_COUNT = 3
    private const val MAX_CHUNK_COUNT = 80
    private const val TARGET_SECONDS_PER_CHUNK = 2.5
    private const val MAX_CHUNK_SIZE = 32 * 1024
    private const val SHA256_LENGTH_BYTES = 32
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val MAX_AAD_LENGTH_BYTES = 1_024

    /**
     * Determine an optimal chunk size for the provided voice payload.
     *
     * When duration metadata is available we attempt to keep the number of
     * chunks roughly proportional to the clip length (≈2.5 seconds per chunk),
     * while ensuring we never fall below [CHUNK_SIZE] or exceed
     * [MAX_CHUNK_SIZE]. If the duration is unavailable we fall back to the
     * default chunk size behaviour.
     */
    fun computeChunkSize(durationMs: Long?, totalBytes: Int): Int {
        if (totalBytes <= 0) {
            return CHUNK_SIZE
        }

        val durationSeconds = durationMs?.coerceAtLeast(0L)?.let { it / 1000.0 }
        val targetChunks = durationSeconds?.let { seconds ->
            if (seconds <= 0.0) {
                MIN_CHUNK_COUNT
            } else {
                ceil(seconds / TARGET_SECONDS_PER_CHUNK).toInt()
            }
        }?.coerceIn(MIN_CHUNK_COUNT, MAX_CHUNK_COUNT)
            ?: ((totalBytes + CHUNK_SIZE - 1) / CHUNK_SIZE)
                .coerceIn(MIN_CHUNK_COUNT, MAX_CHUNK_COUNT)

        val computedSize = ceil(totalBytes / targetChunks.toDouble()).toInt()
        return computedSize.coerceIn(CHUNK_SIZE, MAX_CHUNK_SIZE)
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
        if (chunkSize !in CHUNK_SIZE..MAX_CHUNK_SIZE) {
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
        if (encrypted && iv?.size != GCM_IV_LENGTH_BYTES) {
            return false
        }
        if (aad != null && aad.size > MAX_AAD_LENGTH_BYTES) {
            return false
        }
        return true
    }

    private fun expectedChunkCount(totalBytes: Int, chunkSize: Int): Int =
        ((totalBytes + chunkSize - 1) / chunkSize)
}

/**
 * Represents the direction of a transfer.
 */
enum class VoiceTransferDirection {
    Upload,
    Download
}

/**
 * Lifecycle state of a voice transfer.
 */
enum class VoiceTransferState {
    Initializing,
    Transferring,
    Waiting,
    Verifying,
    Completed,
    Failed
}

/**
 * Progress payload surfaced to the UI.
 */
data class VoiceTransferProgress(
    val sessionCode: String,
    val uuid: String,
    val direction: VoiceTransferDirection,
    val totalChunks: Int,
    val confirmedChunks: Int,
    val pendingChunks: Int,
    val inFlightChunks: Int,
    val state: VoiceTransferState
) {
    val remainingChunks: Int = (totalChunks - confirmedChunks).coerceAtLeast(0)
    val percentage: Float = if (totalChunks == 0) 0f else confirmedChunks / totalChunks.toFloat()
}

/**
 * Metadata describing the remote voice payload.
 */
data class VoiceStreamMetadata(
    val uuid: String,
    val totalBytes: Int,
    val mime: String,
    val durationMs: Long?,
    val encrypted: Boolean,
    val chunkSize: Int,
    val chunkCount: Int,
    val sha256: ByteArray,
    val iv: ByteArray?,
    val aad: ByteArray?
)

/*
 * Voice Protocol quick reference:
 * 1. VOICE_INIT announces transfer metadata and optional AES-GCM parameters.
 * 2. Sender streams VOICE_CHUNK frames (<=chunkSize bytes) honoring a window of 8.
 * 3. Receiver emits VOICE_ACK every 32 chunks (or on demand) and VOICE_RESUME
 *    when gaps are detected after a link drop. Each chunk is CRC32 verified.
 * 4. After all chunks transmit the sender sends VOICE_FINISH. The receiver
 *    persists, SHA-256 verifies, decrypts when needed, and responds with
 *    VOICE_VERIFY_OK or VOICE_VERIFY_FAIL.
 * 5. Both sides delete temporary *.part artifacts once verification succeeds.
 */
