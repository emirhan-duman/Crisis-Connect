package com.auralis.crisisconnect.messaging

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * The inner plaintext serialization of a [MessageContent] — the bytes that get encrypted. It is the
 * SAME on both messaging crypto layers so everything downstream (template codes 200-207, attachment
 * chunking, receipts, call signals) works identically no matter which transport carried it:
 *   - v2 envelope ([E2eEnvelope], authenticated ECIES) — the interim construction.
 *   - v3 Signal protocol ([com.auralis.crisisconnect.messaging.signal] Double Ratchet) — the FS layer.
 *
 * Layout is a 1-byte version tag then either text or an attachment chunk. Cross-platform (iOS/web)
 * must reproduce these bytes exactly.
 */
object MessageContentCodec {
    const val PAYLOAD_VERSION_TEXT = 1
    const val PAYLOAD_VERSION_ATTACHMENT = 2

    fun encode(content: MessageContent): ByteArray {
        val att = content.attachment
        if (att == null) {
            val textBytes = content.text.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArray(2 + textBytes.size)
            out[0] = PAYLOAD_VERSION_TEXT.toByte()
            out[1] = (content.templateCode and 0xFF).toByte()
            System.arraycopy(textBytes, 0, out, 2, textBytes.size)
            return out
        }
        // v2 attachment layout (big-endian): [2][kind][chunkIndex i32][chunkCount i32][totalSize i32]
        // [durationMs i32][mimeLen u16][mime][nameLen u16][name][tidLen u16][transferId][chunk bytes]
        val mimeBytes = att.mime.toByteArray(StandardCharsets.UTF_8)
        val nameBytes = att.name.toByteArray(StandardCharsets.UTF_8)
        val tidBytes = att.transferId.toByteArray(StandardCharsets.UTF_8)
        val headerSize = 18 + 2 + mimeBytes.size + 2 + nameBytes.size + 2 + tidBytes.size
        val buf = ByteBuffer.allocate(headerSize + att.bytes.size)
        buf.put(PAYLOAD_VERSION_ATTACHMENT.toByte())
        buf.put((att.kind and 0xFF).toByte())
        buf.putInt(att.chunkIndex)
        buf.putInt(att.chunkCount)
        buf.putInt(att.totalSize)
        buf.putInt(att.durationMs)
        buf.putShort(mimeBytes.size.toShort()); buf.put(mimeBytes)
        buf.putShort(nameBytes.size.toShort()); buf.put(nameBytes)
        buf.putShort(tidBytes.size.toShort()); buf.put(tidBytes)
        buf.put(att.bytes)
        return buf.array()
    }

    fun decode(bytes: ByteArray): MessageContent {
        require(bytes.size >= 2) { "Payload too short." }
        return when (bytes[0].toInt()) {
            PAYLOAD_VERSION_TEXT -> {
                val templateCode = bytes[1].toInt() and 0xFF
                val text = String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_8)
                MessageContent(templateCode = templateCode, text = text)
            }
            PAYLOAD_VERSION_ATTACHMENT -> {
                val buf = ByteBuffer.wrap(bytes)
                buf.get() // version
                val kind = buf.get().toInt() and 0xFF
                val chunkIndex = buf.int
                val chunkCount = buf.int
                val totalSize = buf.int
                val durationMs = buf.int
                val mime = readLenPrefixedString(buf)
                val name = readLenPrefixedString(buf)
                val transferId = readLenPrefixedString(buf)
                val chunk = ByteArray(buf.remaining())
                buf.get(chunk)
                MessageContent(
                    templateCode = 0,
                    text = "",
                    attachment = MessageAttachment(
                        kind = kind,
                        mime = mime,
                        name = name,
                        transferId = transferId,
                        chunkIndex = chunkIndex,
                        chunkCount = chunkCount,
                        totalSize = totalSize,
                        durationMs = durationMs,
                        bytes = chunk
                    )
                )
            }
            else -> throw IllegalArgumentException("Unsupported payload version.")
        }
    }

    private fun readLenPrefixedString(buf: ByteBuffer): String {
        val len = buf.short.toInt() and 0xFFFF
        val b = ByteArray(len)
        buf.get(b)
        return String(b, StandardCharsets.UTF_8)
    }
}
