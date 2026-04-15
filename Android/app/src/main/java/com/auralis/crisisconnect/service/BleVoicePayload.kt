package com.auralis.crisisconnect.service

import android.util.Base64

/**
 * Lightweight payload format for voice messages over encrypted BLE chat.
 *
 * Packet types:
 * - CC_VOICE_INIT|<transferId>|<mimeType>|<durationMs>|<totalChunks>
 * - CC_VOICE_CHUNK|<transferId>|<chunkIndex>|<base64ChunkData>
 */
object BleVoicePayload {
    private const val INIT_PREFIX = "CC_VOICE_INIT|"
    private const val CHUNK_PREFIX = "CC_VOICE_CHUNK|"
    private const val DONE_PREFIX = "CC_VOICE_DONE|"
    private const val ABORT_PREFIX = "CC_VOICE_ABORT|"
    private const val MAX_TOTAL_CHUNKS = 2_048
    private const val MAX_CHUNK_BASE64_LENGTH = 16_000
    private const val MAX_ABORT_REASON_LENGTH = 64

    // Kept below secure chat packet limits after Base64 expansion + metadata.
    const val OUTGOING_CHUNK_SIZE_BYTES: Int = 12_000
    const val MAX_OUTGOING_TOTAL_BYTES: Int = 1_048_576

    sealed interface Packet {
        data class Init(
            val transferId: String,
            val mimeType: String,
            val durationMillis: Long,
            val totalChunks: Int
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
        val mimeType: String,
        val durationMillis: Long,
        val totalChunks: Int,
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
        mimeType: String,
        durationMillis: Long,
        bytes: ByteArray
    ): List<String> {
        if (transferId.isBlank() || bytes.isEmpty() || bytes.size > MAX_OUTGOING_TOTAL_BYTES) {
            return emptyList()
        }
        val safeMime = mimeType.trim().ifEmpty { "audio/mp4" }
        val chunkSize = OUTGOING_CHUNK_SIZE_BYTES.coerceAtLeast(256)
        val totalChunks = ((bytes.size + chunkSize - 1) / chunkSize).coerceAtLeast(1)
        if (totalChunks > MAX_TOTAL_CHUNKS) {
            return emptyList()
        }

        val packets = ArrayList<String>(totalChunks + 1)
        packets += buildString {
            append(INIT_PREFIX)
            append(transferId.trim())
            append('|')
            append(safeMime)
            append('|')
            append(durationMillis.coerceAtLeast(0L))
            append('|')
            append(totalChunks)
        }

        var offset = 0
        for (index in 0 until totalChunks) {
            val end = (offset + chunkSize).coerceAtMost(bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
            packets += buildString {
                append(CHUNK_PREFIX)
                append(transferId.trim())
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
        val body = message.removePrefix(INIT_PREFIX)
        val fields = body.split('|')
        if (fields.size != 4) return null
        val transferId = fields[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val mimeType = fields[1].trim().ifEmpty { "audio/mp4" }
        val durationMillis = fields[2].toLongOrNull()?.coerceAtLeast(0L) ?: return null
        val totalChunks = fields[3].toIntOrNull() ?: return null
        if (totalChunks !in 1..MAX_TOTAL_CHUNKS) return null
        return Packet.Init(
            transferId = transferId,
            mimeType = mimeType,
            durationMillis = durationMillis,
            totalChunks = totalChunks
        )
    }

    private fun parseChunk(message: String): Packet.Chunk? {
        val body = message.removePrefix(CHUNK_PREFIX)
        val fields = body.split('|', limit = 3)
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
        val body = message.removePrefix(DONE_PREFIX).trim()
        if (body.isEmpty()) return null
        return Packet.Done(transferId = body)
    }

    private fun parseAbort(message: String): Packet.Abort? {
        val body = message.removePrefix(ABORT_PREFIX)
        val fields = body.split('|', limit = 2)
        if (fields.isEmpty()) return null
        val transferId = fields[0].trim().takeIf { it.isNotEmpty() } ?: return null
        val reason = fields.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return Packet.Abort(
            transferId = transferId,
            reason = reason
        )
    }
}
