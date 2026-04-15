package com.auralis.crisisconnect.core.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_ALGORITHM = "AES"
private const val GCM_TAG_LENGTH_BITS = 128
private const val IV_LENGTH_BYTES = 12

/**
 * AES-GCM helper utilities used by the voice streaming micro-protocol.
 *
 * The implementation intentionally avoids higher level wrappers so we can
 * directly control IV generation, additional authenticated data (AAD) and the
 * produced ciphertext bytes. The "cipher" payload returned from
 * [encryptAesGcm] contains both the ciphertext and the trailing authentication
 * tag as emitted by [Cipher.doFinal].
 */
object AesGcm {

    private val secureRandom = SecureRandom()

    /**
     * Encrypt [plaintext] with the provided [keyBytes] and [aad].
     *
     * @param keyBytes 256-bit AES key.
     * @param plaintext Raw bytes to encrypt.
     * @param aad Additional authenticated data that will be bound to the
     * ciphertext integrity but not included in the encrypted payload.
     * @return [Encrypted] payload holding the randomly generated IV and the
     * ciphertext+tag bytes.
     */
    fun encryptAesGcm(
        keyBytes: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): Encrypted {
        require(keyBytes.isNotEmpty()) { "AES key must not be empty" }
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)
        val cipherBytes = cipher.doFinal(plaintext)
        return Encrypted(iv = iv, cipher = cipherBytes)
    }

    /**
     * Decrypt [cipher] that was produced by [encryptAesGcm].
     *
     * @param keyBytes 256-bit AES key used during encryption.
     * @param iv 12-byte IV value used during encryption.
     * @param cipher Ciphertext concatenated with the 16-byte authentication tag.
     * @param aad Additional authenticated data that must match the AAD supplied
     * during encryption.
     * @return Decrypted plaintext bytes.
     */
    fun decryptAesGcm(
        keyBytes: ByteArray,
        iv: ByteArray,
        cipher: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(keyBytes.isNotEmpty()) { "AES key must not be empty" }
        val secretKey = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val cipherInstance = Cipher.getInstance(AES_TRANSFORMATION)
        cipherInstance.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
        cipherInstance.updateAAD(aad)
        return cipherInstance.doFinal(cipher)
    }

    /**
     * Container representing the AES-GCM encryption result.
     */
    data class Encrypted(
        val iv: ByteArray,
        val cipher: ByteArray
    )
}

/**
 * Construct the canonical AAD binding for the voice message metadata.
 */
fun canonicalAad(
    uuid: String,
    mime: String,
    durationMs: Long?,
    chunkSize: Int,
    totalBytes: Int,
    chunkCount: Int,
    encrypted: Boolean
): ByteArray {
    val normalizedDuration = durationMs ?: -1L
    val payload = buildString {
        append("uuid=")
        append(uuid)
        append('|')
        append("mime=")
        append(mime)
        append('|')
        append("duration=")
        append(normalizedDuration)
        append('|')
        append("chunkSize=")
        append(chunkSize)
        append('|')
        append("totalBytes=")
        append(totalBytes)
        append('|')
        append("chunkCount=")
        append(chunkCount)
        append('|')
        append("encrypted=")
        append(encrypted)
    }
    return payload.toByteArray(Charsets.UTF_8)
}
