package com.auralis.crisisconnect.service

import android.util.Base64
import java.security.MessageDigest

/**
 * Lightweight payload format for image messages over encrypted BLE chat.
 *
 * Packet types:
 * - CC_IMAGE_INIT|<transferId>|<messageId>|<mimeType>|<width>|<height>|<totalBytes>|<totalChunks>|<sha256B64>
 * - CC_IMAGE_CHUNK|<transferId>|<chunkIndex>|<base64ChunkData>
 * - CC_IMAGE_DONE|<transferId>
 * - CC_IMAGE_ABORT|<transferId>|<reason>
 */
object BleImagePayload {
    private const val INIT_PREFIX = "CC_IMAGE_INIT|"
    private const val CHUNK_PREFIX = "CC_IMAGE_CHUNK|"
    private const val DONE_PREFIX = "CC_IMAGE_DONE|"
    private const val ABORT_PREFIX = "CC_IMAGE_ABORT|"
    private const val MAX_TOTAL_CHUNKS = 4_096
    private const val MAX_CHUNK_BASE64_LENGTH = 16_384
    private const val MAX_ABORT_REASON_LENGTH = 64
    private const val SHA256_BYTES = 32

    const val OUTGOING_CHUNK_SIZE_BYTES: Int = 10_240
    const val MAX_OUTGOING_TOTAL_BYTES: Int = 512 * 1024

    sealed interface Packet {
        data class Init(
            val transferId: String,
            val messageId: String,
            val mimeType: String,
            val width: Int?,
            val height: Int?,
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
        val mimeType: String,
        val width: Int?,
        val height: Int?,
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
        mimeType: String,
        width: Int?,
        height: Int?,
        bytes: ByteArray
    ): List<String> {
        val safeTransferId = transferId.trim()
        val safeMessageId = messageId.trim()
        if (
            safeTransferId.isEmpty() ||
            safeMessageId.isEmpty() ||
            bytes.isEmpty() ||
            bytes.size > MAX_OUTGOING_TOTAL_BYTES
        ) {
            return emptyList()
        }
        val safeMime = mimeType.trim().ifEmpty { "image/jpeg" }
        val chunkSize = OUTGOING_CHUNK_SIZE_BYTES.coerceAtLeast(512)
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            return emptyList()
        }
        val sha256 = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(bytes),
            Base64.NO_WRAP
        )
        val packets = ArrayList<String>(totalChunks + 1)
        packets += buildString {
            append(INIT_PREFIX)
            append(safeTransferId)
            append('|')
            append(safeMessageId)
            append('|')
            append(safeMime)
            append('|')
            append(width ?: 0)
            append('|')
            append(height ?: 0)
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
        val mimeType = fields[2].trim().ifEmpty { "image/jpeg" }
        val width = fields[3].toIntOrNull()?.takeIf { it > 0 }
        val height = fields[4].toIntOrNull()?.takeIf { it > 0 }
        val totalBytes = fields[5].toIntOrNull() ?: return null
        val totalChunks = fields[6].toIntOrNull() ?: return null
        if (totalBytes !in 1..MAX_OUTGOING_TOTAL_BYTES) return null
        if (totalChunks !in 1..MAX_TOTAL_CHUNKS) return null
        val sha256 = runCatching { Base64.decode(fields[7], Base64.NO_WRAP) }.getOrNull() ?: return null
        if (sha256.size != SHA256_BYTES) return null
        return Packet.Init(
            transferId = transferId,
            messageId = messageId,
            mimeType = mimeType,
            width = width,
            height = height,
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
