package com.auralis.crisisconnect.service

import android.util.Base64
import java.security.MessageDigest

/**
 * Lightweight payload format for document/file messages over encrypted BLE chat.
 *
 * Packet types:
 * - CC_FILE_INIT|<transferId>|<messageId>|<nameB64Url>|<mimeB64Url>|<originalSize>|<totalBytes>|<totalChunks>|<sha256B64>
 * - CC_FILE_CHUNK|<transferId>|<chunkIndex>|<base64ChunkData>
 * - CC_FILE_DONE|<transferId>
 * - CC_FILE_ABORT|<transferId>|<reason>
 */
object BleFilePayload {
    private const val INIT_PREFIX = "CC_FILE_INIT|"
    private const val CHUNK_PREFIX = "CC_FILE_CHUNK|"
    private const val DONE_PREFIX = "CC_FILE_DONE|"
    private const val ABORT_PREFIX = "CC_FILE_ABORT|"
    private const val MAX_TOTAL_CHUNKS = 4_096
    private const val MAX_CHUNK_BASE64_LENGTH = 16_384
    private const val MAX_ABORT_REASON_LENGTH = 64
    private const val SHA256_BYTES = 32
    private const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE

    const val OUTGOING_CHUNK_SIZE_BYTES: Int = 10_240
    const val MAX_OUTGOING_TOTAL_BYTES: Int = 480 * 1024

    sealed interface Packet {
        data class Init(
            val transferId: String,
            val messageId: String,
            val displayName: String,
            val mimeType: String?,
            val originalSizeBytes: Long,
            val totalBytes: Int,
            val totalChunks: Int,
            val sha256: ByteArray
        ) : Packet

        data class Chunk(
            val transferId: String,
            val chunkIndex: Int,
            val bytes: ByteArray
        ) : Packet

        data class Done(
            val transferId: String
        ) : Packet

        data class Abort(
            val transferId: String,
            val reason: String?
        ) : Packet
    }

    data class IncomingTransfer(
        val transferId: String,
        val messageId: String,
        val displayName: String,
        val mimeType: String?,
        val originalSizeBytes: Long,
        val totalBytes: Int,
        val totalChunks: Int,
        val sha256: ByteArray,
        val createdAtMillis: Long = System.currentTimeMillis(),
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    ) {
        fun addChunk(index: Int, bytes: ByteArray): Boolean {
            if (index !in 0 until totalChunks) return false
            chunks.putIfAbsent(index, bytes)
            return true
        }

        fun isComplete(): Boolean = chunks.size == totalChunks

        fun composeBytes(): ByteArray? {
            if (!isComplete()) return null
            var totalSize = 0
            for (index in 0 until totalChunks) {
                val chunk = chunks[index] ?: return null
                totalSize += chunk.size
            }
            if (totalSize != totalBytes) {
                return null
            }
            val combined = ByteArray(totalSize)
            var offset = 0
            for (index in 0 until totalChunks) {
                val chunk = chunks[index] ?: return null
                System.arraycopy(chunk, 0, combined, offset, chunk.size)
                offset += chunk.size
            }
            return combined
        }
    }

    fun buildPackets(
        transferId: String,
        messageId: String,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Long,
        bytes: ByteArray
    ): List<String> {
        val safeTransferId = transferId.trim()
        val safeMessageId = messageId.trim()
        val safeDisplayName = displayName.trim()
        if (
            safeTransferId.isEmpty() ||
            safeMessageId.isEmpty() ||
            safeDisplayName.isEmpty() ||
            originalSizeBytes <= 0L ||
            bytes.isEmpty() ||
            bytes.size > MAX_OUTGOING_TOTAL_BYTES
        ) {
            return emptyList()
        }
        val chunkSize = OUTGOING_CHUNK_SIZE_BYTES.coerceAtLeast(512)
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            return emptyList()
        }
        val sha256 = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bytes),
            Base64.NO_WRAP
        )
        val encodedName = Base64.encodeToString(safeDisplayName.toByteArray(Charsets.UTF_8), BASE64_FLAGS)
        val encodedMime = mimeType
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { value ->
                Base64.encodeToString(value.toByteArray(Charsets.UTF_8), BASE64_FLAGS)
            } ?: "-"
        val packets = ArrayList<String>(totalChunks + 1)
        packets += buildString {
            append(INIT_PREFIX)
            append(safeTransferId)
            append('|')
            append(safeMessageId)
            append('|')
            append(encodedName)
            append('|')
            append(encodedMime)
            append('|')
            append(originalSizeBytes)
            append('|')
            append(bytes.size)
            append('|')
            append(totalChunks)
            append('|')
            append(sha256)
        }

        var offset = 0
        for (index in 0 until totalChunks) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
            packets += buildString {
                append(CHUNK_PREFIX)
                append(safeTransferId)
                append('|')
                append(index)
                append('|')
                append(encoded)
            }
            offset = end
        }
        return packets
    }

    fun parsePacket(raw: String): Packet? {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith(INIT_PREFIX) -> parseInit(trimmed)
            trimmed.startsWith(CHUNK_PREFIX) -> parseChunk(trimmed)
            trimmed.startsWith(DONE_PREFIX) -> parseDone(trimmed)
            trimmed.startsWith(ABORT_PREFIX) -> parseAbort(trimmed)
            else -> null
        }
    }

    fun buildDonePacket(transferId: String): String {
        val safeId = transferId.trim()
        if (safeId.isEmpty()) return ""
        return "$DONE_PREFIX$safeId"
    }

    fun buildAbortPacket(transferId: String, reason: String?): String {
        val safeId = transferId.trim()
        if (safeId.isEmpty()) return ""
        val safeReason = reason
            ?.trim()
            ?.replace('|', '/')
            ?.take(MAX_ABORT_REASON_LENGTH)
            .orEmpty()
        return if (safeReason.isEmpty()) {
            "$ABORT_PREFIX$safeId"
        } else {
            "$ABORT_PREFIX$safeId|$safeReason"
        }
    }

    private fun parseInit(message: String): Packet.Init? {
        val fields = message.removePrefix(INIT_PREFIX).split('|')
        if (fields.size != 8) return null
        val transferId = fields[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val messageId = fields[1].trim().takeIf { it.isNotEmpty() } ?: return null
        val displayName = runCatching {
            val bytes = Base64.decode(fields[2], BASE64_FLAGS)
            String(bytes, Charsets.UTF_8)
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val mimeType = fields[3]
            .takeIf { it != "-" && it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val bytes = Base64.decode(encoded, BASE64_FLAGS)
                    String(bytes, Charsets.UTF_8)
                }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            }
        val originalSizeBytes = fields[4].toLongOrNull() ?: return null
        val totalBytes = fields[5].toIntOrNull() ?: return null
        val totalChunks = fields[6].toIntOrNull() ?: return null
        if (originalSizeBytes !in 1L..MAX_OUTGOING_TOTAL_BYTES.toLong()) return null
        if (totalBytes !in 1..MAX_OUTGOING_TOTAL_BYTES) return null
        if (totalChunks !in 1..MAX_TOTAL_CHUNKS) return null
        val sha256 = runCatching { Base64.decode(fields[7], Base64.NO_WRAP) }.getOrNull() ?: return null
        if (sha256.size != SHA256_BYTES) return null
        return Packet.Init(
            transferId = transferId,
            messageId = messageId,
            displayName = displayName,
            mimeType = mimeType,
            originalSizeBytes = originalSizeBytes,
            totalBytes = totalBytes,
            totalChunks = totalChunks,
            sha256 = sha256
        )
    }

    private fun parseChunk(message: String): Packet.Chunk? {
        val fields = message.removePrefix(CHUNK_PREFIX).split('|', limit = 3)
        if (fields.size != 3) return null
        val transferId = fields[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val chunkIndex = fields[1].toIntOrNull() ?: return null
        val payload = fields[2].trim()
        if (payload.isEmpty() || payload.length > MAX_CHUNK_BASE64_LENGTH) return null
        val bytes = runCatching { Base64.decode(payload, Base64.NO_WRAP) }.getOrNull() ?: return null
        return Packet.Chunk(
            transferId = transferId,
            chunkIndex = chunkIndex,
            bytes = bytes
        )
    }

    private fun parseDone(message: String): Packet.Done? {
        val transferId = message.removePrefix(DONE_PREFIX).trim().takeIf { it.isNotEmpty() } ?: return null
        return Packet.Done(transferId = transferId)
    }

    private fun parseAbort(message: String): Packet.Abort? {
        val fields = message.removePrefix(ABORT_PREFIX).split('|', limit = 2)
        val transferId = fields.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val reason = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Packet.Abort(
            transferId = transferId,
            reason = reason
        )
    }
}
