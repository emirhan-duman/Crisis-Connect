package com.auralis.crisisconnect.security

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
private const val AES_ALGORITHM = "AES"
private const val GCM_TAG_LENGTH_BITS = 128
private const val GCM_IV_LENGTH_BYTES = 12

private val secureRandom = SecureRandom()

fun encryptAesGcm(keyBytes: ByteArray, plainText: ByteArray): Pair<String, String>? {
    return try {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).apply { secureRandom.nextBytes(this) }
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val cipherBytes = cipher.doFinal(plainText)
        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
        ivBase64 to cipherBase64
    } catch (_: Exception) {
        null
    }
}

fun decryptAesGcm(keyBytes: ByteArray, ivBase64: String, cipherBase64: String): ByteArray? {
    val iv = try {
        Base64.decode(ivBase64, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val cipherBytes = try {
        Base64.decode(cipherBase64, Base64.NO_WRAP)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return try {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        cipher.doFinal(cipherBytes)
    } catch (_: Exception) {
        null
    }
}
