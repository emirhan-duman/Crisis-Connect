package com.auralis.crisisconnect.service

import android.util.Base64
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import java.nio.charset.StandardCharsets
import org.json.JSONObject

object BleDirectChatCompat {
    const val CHAT_KIND_IMAGE_INIT = "image_init"
    const val CHAT_KIND_IMAGE_CHUNK = "image_chunk"
    const val CHAT_KIND_IMAGE_DONE = "image_done"
    const val CHAT_KIND_IMAGE_ABORT = "image_abort"
    const val CHAT_KIND_FILE_INIT = "file_init"
    const val CHAT_KIND_FILE_CHUNK = "file_chunk"
    const val CHAT_KIND_FILE_DONE = "file_done"
    const val CHAT_KIND_FILE_ABORT = "file_abort"

    data class Payload(
        val kind: String,
        val messageId: String?,
        val text: String?,
        val senderName: String?,
        val displayName: String?,
        val mimeType: String?,
        val durationMillis: Long?,
        val width: Int?,
        val height: Int?,
        val originalSizeBytes: Long?,
        val totalBytes: Int?,
        val totalChunks: Int?,
        val sha256: ByteArray?,
        val chunkIndex: Int?,
        val chunkData: ByteArray?
    )

    private data class Envelope(
        val fromDeviceId: String,
        val encryptedPacket: ByteArray
    )

    fun unwrapTransportPacket(packet: ByteArray, maxPacketSize: Int): ByteArray? {
        return runCatching {
            require(packet.size >= 2) { "Transport packet missing length header" }
            val length = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
            require(length in 1..maxPacketSize) { "Transport packet declared invalid length=$length" }
            require(packet.size - 2 == length) {
                "Transport payload truncated: expected $length bytes, found ${packet.size - 2}"
            }
            packet.copyOfRange(2, packet.size)
        }.getOrNull()
    }

    fun decodePayloadJson(
        outerMessage: String,
        keyBytes: ByteArray,
        maxEncryptedPacketBytes: Int
    ): String? {
        val envelope = parseEnvelope(outerMessage) ?: return null
        val payloadBytes = decryptPayload(
            keyBytes = keyBytes,
            encryptedPacket = envelope.encryptedPacket,
            maxEncryptedPacketBytes = maxEncryptedPacketBytes
        ) ?: return null
        return payloadBytes.toString(StandardCharsets.UTF_8).trimEnd { it == '\u0000' }
    }

    fun parsePayload(raw: String): Payload? {
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val kind = payload.optString("kind").trim().takeIf { it.isNotBlank() } ?: return null
        return Payload(
            kind = kind,
            messageId = payload.optString("messageId").trim().takeIf { it.isNotBlank() },
            text = payload.optString("text").trim().takeIf { it.isNotBlank() },
            senderName = payload.optString("senderName").trim().takeIf { it.isNotBlank() },
            displayName = payload.optString("displayName").trim().takeIf { it.isNotBlank() },
            mimeType = payload.optString("mimeType").trim().takeIf { it.isNotBlank() },
            durationMillis = payload.optLong("durationMillis").takeIf { payload.has("durationMillis") },
            width = payload.optInt("width").takeIf { payload.has("width") },
            height = payload.optInt("height").takeIf { payload.has("height") },
            originalSizeBytes = payload.optLong("originalSizeBytes")
                .takeIf { payload.has("originalSizeBytes") },
            totalBytes = payload.optInt("totalBytes").takeIf { payload.has("totalBytes") },
            totalChunks = payload.optInt("totalChunks").takeIf { payload.has("totalChunks") },
            sha256 = payload.optString("sha256").trim().takeIf { it.isNotBlank() }?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
            },
            chunkIndex = payload.optInt("chunkIndex").takeIf { payload.has("chunkIndex") },
            chunkData = payload.optString("chunkData").trim().takeIf { it.isNotBlank() }?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
            }
        )
    }

    fun toIncomingVoicePacket(payload: Payload): BleVoicePayload.Packet? {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_VOICE_INIT -> {
                val mimeType = payload.mimeType?.trim().takeIf { !it.isNullOrBlank() } ?: return null
                val totalChunks = payload.totalChunks?.takeIf { it > 0 } ?: return null
                BleVoicePayload.Packet.Init(
                    transferId = messageId,
                    mimeType = mimeType,
                    durationMillis = payload.durationMillis?.coerceAtLeast(0L) ?: 0L,
                    totalChunks = totalChunks
                )
            }

            P2pBleProtocol.CHAT_KIND_VOICE_CHUNK -> {
                val chunkIndex = payload.chunkIndex ?: return null
                val chunkData = payload.chunkData ?: return null
                BleVoicePayload.Packet.Chunk(
                    transferId = messageId,
                    chunkIndex = chunkIndex,
                    bytes = chunkData
                )
            }

            else -> null
        }
    }

    fun toIncomingImagePacket(payload: Payload): BleImagePayload.Packet? {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when (payload.kind) {
            CHAT_KIND_IMAGE_INIT -> {
                val mimeType = payload.mimeType?.trim().takeIf { !it.isNullOrBlank() } ?: return null
                val totalBytes = payload.totalBytes?.takeIf { it > 0 } ?: return null
                val totalChunks = payload.totalChunks?.takeIf { it > 0 } ?: return null
                val sha256 = payload.sha256?.takeIf { it.size == 32 } ?: return null
                BleImagePayload.Packet.Init(
                    transferId = messageId,
                    messageId = messageId,
                    mimeType = mimeType,
                    width = payload.width?.takeIf { it > 0 },
                    height = payload.height?.takeIf { it > 0 },
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = sha256
                )
            }

            CHAT_KIND_IMAGE_CHUNK -> {
                val chunkIndex = payload.chunkIndex ?: return null
                val chunkData = payload.chunkData ?: return null
                BleImagePayload.Packet.Chunk(
                    transferId = messageId,
                    chunkIndex = chunkIndex,
                    bytes = chunkData
                )
            }

            else -> null
        }
    }

    fun toIncomingFilePacket(payload: Payload): BleFilePayload.Packet? {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return null
        return when (payload.kind) {
            CHAT_KIND_FILE_INIT -> {
                val displayName = payload.displayName?.trim().takeIf { !it.isNullOrBlank() } ?: return null
                val totalBytes = payload.totalBytes?.takeIf { it > 0 } ?: return null
                val totalChunks = payload.totalChunks?.takeIf { it > 0 } ?: return null
                val sha256 = payload.sha256?.takeIf { it.size == 32 } ?: return null
                BleFilePayload.Packet.Init(
                    transferId = messageId,
                    messageId = messageId,
                    displayName = displayName,
                    mimeType = payload.mimeType?.trim()?.takeIf { it.isNotEmpty() },
                    originalSizeBytes = payload.originalSizeBytes?.takeIf { it > 0L }
                        ?: totalBytes.toLong(),
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = sha256
                )
            }

            CHAT_KIND_FILE_CHUNK -> {
                val chunkIndex = payload.chunkIndex ?: return null
                val chunkData = payload.chunkData ?: return null
                BleFilePayload.Packet.Chunk(
                    transferId = messageId,
                    chunkIndex = chunkIndex,
                    bytes = chunkData
                )
            }

            else -> null
        }
    }

    private fun parseEnvelope(raw: String): Envelope? {
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = payload.optString("type").trim()
        if (type != P2pBleProtocol.TYPE_CHAT_ENVELOPE) {
            return null
        }
        val fromDeviceId = payload.optString("fromDeviceId").trim().takeIf { it.isNotBlank() } ?: return null
        val encrypted = P2pBleProtocol.decodeBase64(payload.optString("payload")) ?: return null
        return Envelope(
            fromDeviceId = fromDeviceId,
            encryptedPacket = encrypted
        )
    }

    private fun decryptPayload(
        keyBytes: ByteArray,
        encryptedPacket: ByteArray,
        maxEncryptedPacketBytes: Int
    ): ByteArray? {
        runCatching {
            return AesCipherHelper.decrypt(keyBytes, encryptedPacket)
        }
        val wrappedPacket = unwrapTransportPacket(encryptedPacket, maxEncryptedPacketBytes) ?: return null
        return runCatching {
            AesCipherHelper.decrypt(keyBytes, wrappedPacket)
        }.getOrNull()
    }
}
