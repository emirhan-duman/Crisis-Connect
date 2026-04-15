package com.auralis.crisisconnect.service.p2p

import android.util.Base64
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.util.UUIDGenerator
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.text.Charsets

object P2pBleProtocol {
    private const val SERVICE_ASSIGNED_NUMBER = 0xCD00
    private const val CHAR_ID_ASSIGNED_NUMBER = 0xCD01
    private const val CHAR_BOOTSTRAP_ASSIGNED_NUMBER = 0xCD02
    private const val CHAR_CONTROL_ASSIGNED_NUMBER = 0xCD03
    private const val CHAR_MESSAGE_IN_ASSIGNED_NUMBER = 0xCD04
    private const val CHAR_MESSAGE_OUT_ASSIGNED_NUMBER = 0xCD05
    private const val CCC_DESCRIPTOR_ASSIGNED_NUMBER = 0x2902

    const val PROTOCOL_VERSION = 1

    const val TYPE_CLIENT_HELLO = "client_hello"
    const val TYPE_SERVER_HELLO = "server_hello"
    const val TYPE_CLIENT_FINISH = "client_finish"
    const val TYPE_SERVER_FINISH = "server_finish"
    const val TYPE_ERROR = "error"
    const val TYPE_CHAT_ENVELOPE = "chat_envelope"

    const val CHAT_KIND_TEXT = "text"
    const val CHAT_KIND_READ = "read"
    const val CHAT_KIND_DELIVERED = "delivered"
    const val CHAT_KIND_VOICE_INIT = "voice_init"
    const val CHAT_KIND_VOICE_CHUNK = "voice_chunk"
    const val CHAT_KIND_VOICE_DONE = "voice_done"
    const val CHAT_KIND_VOICE_ABORT = "voice_abort"
    const val CHAT_KIND_IMAGE_INIT = "image_init"
    const val CHAT_KIND_IMAGE_CHUNK = "image_chunk"
    const val CHAT_KIND_IMAGE_DONE = "image_done"
    const val CHAT_KIND_IMAGE_ABORT = "image_abort"
    const val CHAT_KIND_FILE_INIT = "file_init"
    const val CHAT_KIND_FILE_CHUNK = "file_chunk"
    const val CHAT_KIND_FILE_DONE = "file_done"
    const val CHAT_KIND_FILE_ABORT = "file_abort"
    const val CHAT_KIND_DECRYPT_FAIL = "decrypt_fail"

    const val VOICE_CHUNK_SIZE_BYTES = 1_536
    const val VOICE_MAX_TOTAL_BYTES = 524_288
    const val VOICE_MAX_CHUNKS = 512
    const val IMAGE_CHUNK_SIZE_BYTES = 2_048
    const val IMAGE_MAX_TOTAL_BYTES = 524_288
    const val IMAGE_MAX_CHUNKS = 512
    const val FILE_CHUNK_SIZE_BYTES = 2_048
    const val FILE_MAX_TOTAL_BYTES = 524_288
    const val FILE_MAX_CHUNKS = 512

    val SERVICE_UUID: UUID = UUIDGenerator.fromAssignedNumber(SERVICE_ASSIGNED_NUMBER)
    val ID_CHARACTERISTIC_UUID: UUID = UUIDGenerator.fromAssignedNumber(CHAR_ID_ASSIGNED_NUMBER)
    val BOOTSTRAP_CHARACTERISTIC_UUID: UUID =
        UUIDGenerator.fromAssignedNumber(CHAR_BOOTSTRAP_ASSIGNED_NUMBER)
    val CONTROL_CHARACTERISTIC_UUID: UUID =
        UUIDGenerator.fromAssignedNumber(CHAR_CONTROL_ASSIGNED_NUMBER)
    val MESSAGE_IN_CHARACTERISTIC_UUID: UUID =
        UUIDGenerator.fromAssignedNumber(CHAR_MESSAGE_IN_ASSIGNED_NUMBER)
    val MESSAGE_OUT_CHARACTERISTIC_UUID: UUID =
        UUIDGenerator.fromAssignedNumber(CHAR_MESSAGE_OUT_ASSIGNED_NUMBER)
    val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
        UUIDGenerator.fromAssignedNumber(CCC_DESCRIPTOR_ASSIGNED_NUMBER)

    fun normalizeShareId(shareId: String): String {
        return shareId.trim().uppercase(Locale.US)
    }

    fun encodeAdvertisedShareId(shareId: String): ByteArray {
        return normalizeShareId(shareId).toByteArray(Charsets.UTF_8)
    }

    fun buildIdentityValue(shareId: String): ByteArray {
        return "share:${normalizeShareId(shareId)}".toByteArray(Charsets.UTF_8)
    }

    fun buildDeviceIdentityValue(deviceId: String): ByteArray {
        return "device:${deviceId.trim()}".toByteArray(Charsets.UTF_8)
    }

    fun randomNonceBase64(length: Int = 16): String {
        return encodeBase64(Crypto.randomBytes(length))
    }

    fun encodeBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun decodeBase64(value: String?): ByteArray? {
        val sanitized = value?.trim().orEmpty()
        if (sanitized.isEmpty()) {
            return null
        }
        return runCatching { Base64.decode(sanitized, Base64.DEFAULT) }.getOrNull()
    }

    private fun buildProofPayloadInternal(
        parts: Array<out Pair<String, String>>,
        trailingNewline: Boolean
    ): ByteArray {
        return buildString {
            append("p2p-v")
            append(PROTOCOL_VERSION)
            if (parts.isNotEmpty() || trailingNewline) {
                append('\n')
            }
            parts.forEachIndexed { index, (key, value) ->
                append(key)
                append('=')
                append(
                    Base64.encodeToString(
                        value.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP
                    )
                )
                if (trailingNewline || index != parts.lastIndex) {
                    append('\n')
                }
            }
        }.toByteArray(Charsets.UTF_8)
    }

    fun buildProofPayload(vararg parts: Pair<String, String>): ByteArray {
        return buildProofPayloadInternal(parts, trailingNewline = true)
    }

    fun buildCanonicalProofPayload(vararg parts: Pair<String, String>): ByteArray {
        return buildProofPayloadInternal(parts, trailingNewline = false)
    }

    fun hmacBase64(keyBytes: ByteArray, payload: ByteArray): String? {
        return runCatching {
            encodeBase64(Crypto.hmacSha256(keyBytes, payload))
        }.getOrNull()
    }

    fun secureEqualsBase64(expected: String?, actual: String?): Boolean {
        val expectedBytes = decodeBase64(expected) ?: return false
        val actualBytes = decodeBase64(actual) ?: return false
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }

    fun wrapTransportPacket(payload: ByteArray): ByteArray {
        val length = payload.size
        require(length in 1..0xFFFF) {
            "P2P transport payload must be between 1 and 65535 bytes, was $length"
        }
        return byteArrayOf(
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte()
        ) + payload
    }

    fun unwrapTransportPacket(packet: ByteArray, maxPacketSize: Int): ByteArray {
        require(packet.size >= 2) { "P2P transport packet missing length header" }
        val declaredLength = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        require(declaredLength in 1..maxPacketSize) {
            "P2P transport packet declared invalid length=$declaredLength"
        }
        require(packet.size - 2 == declaredLength) {
            "P2P transport payload truncated: expected $declaredLength bytes, found ${packet.size - 2}"
        }
        return packet.copyOfRange(2, packet.size)
    }
}
