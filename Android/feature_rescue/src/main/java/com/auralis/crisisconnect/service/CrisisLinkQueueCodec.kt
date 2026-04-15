package com.auralis.crisisconnect.service

import com.auralis.crisisconnect.security.AesCipherHelper

/**
 * Encodes/decodes Crisis Link pending queue payloads with an encrypted on-disk format.
 * Falls back to legacy plaintext decoding for migration compatibility.
 */
internal class CrisisLinkQueueCodec(
    private val keyResolver: () -> ByteArray?,
    private val encryptor: (ByteArray, ByteArray) -> ByteArray = AesCipherHelper::encrypt,
    private val decryptor: (ByteArray, ByteArray) -> ByteArray = AesCipherHelper::decrypt
) {

    data class DecodedPayload(
        val plaintext: ByteArray,
        val isLegacyPlaintext: Boolean
    )

    fun encode(plainBytes: ByteArray): ByteArray? {
        val keyBytes = keyResolver() ?: return null
        val encryptedPacket = runCatching {
            encryptor(keyBytes, plainBytes)
        }.getOrNull() ?: return null
        return ByteArray(MAGIC.size + 1 + encryptedPacket.size).apply {
            System.arraycopy(MAGIC, 0, this, 0, MAGIC.size)
            this[MAGIC.size] = FORMAT_VERSION
            System.arraycopy(encryptedPacket, 0, this, MAGIC.size + 1, encryptedPacket.size)
        }
    }

    fun decode(rawBytes: ByteArray): DecodedPayload? {
        if (rawBytes.isEmpty()) {
            return DecodedPayload(plaintext = ByteArray(0), isLegacyPlaintext = false)
        }
        if (!isEncrypted(rawBytes)) {
            return DecodedPayload(plaintext = rawBytes, isLegacyPlaintext = true)
        }
        if (rawBytes.size <= MAGIC.size + 1) {
            return null
        }
        val versionByte = rawBytes[MAGIC.size]
        if (versionByte != FORMAT_VERSION) {
            return null
        }
        val encryptedPacket = rawBytes.copyOfRange(MAGIC.size + 1, rawBytes.size)
        val keyBytes = keyResolver() ?: return null
        val plainBytes = runCatching {
            decryptor(keyBytes, encryptedPacket)
        }.getOrNull() ?: return null
        return DecodedPayload(plaintext = plainBytes, isLegacyPlaintext = false)
    }

    fun isEncrypted(rawBytes: ByteArray): Boolean {
        if (rawBytes.size <= MAGIC.size) {
            return false
        }
        for (index in MAGIC.indices) {
            if (rawBytes[index] != MAGIC[index]) {
                return false
            }
        }
        return true
    }

    companion object {
        internal const val FORMAT_VERSION: Byte = 1
        internal val MAGIC = byteArrayOf(
            0x43.toByte(),
            0x4C.toByte(),
            0x51.toByte(),
            0x31.toByte()
        )
    }
}
