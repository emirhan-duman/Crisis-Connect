package com.auralis.crisisconnect.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class KeystoreBackedPreferences(
    context: Context,
    prefName: String,
    private val keyAlias: String
) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(prefName, Context.MODE_PRIVATE)

    @Volatile
    private var cachedSecretKey: SecretKey? = null

    fun getString(key: String, defaultValue: String? = null): String? {
        val encoded = prefs.getString(key, null) ?: return defaultValue
        val decoded = decode(encoded) ?: run {
            prefs.edit().remove(key).apply()
            return defaultValue
        }
        return decoded
    }

    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val encoded = encode(value) ?: return
        prefs.edit().putString(key, encoded).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int {
        return getString(key, null)?.toIntOrNull() ?: defaultValue
    }

    fun putInt(key: String, value: Int) {
        putString(key, value.toString())
    }

    fun getLong(key: String, defaultValue: Long): Long {
        return getString(key, null)?.toLongOrNull() ?: defaultValue
    }

    fun putLong(key: String, value: Long) {
        putString(key, value.toString())
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun remove(vararg keys: String) {
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }

    private fun encode(value: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, resolveSecretKey())
            val iv = cipher.iv
            val cipherText = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packet = ByteArray(iv.size + cipherText.size)
            System.arraycopy(iv, 0, packet, 0, iv.size)
            System.arraycopy(cipherText, 0, packet, iv.size, cipherText.size)
            Base64.encodeToString(packet, Base64.NO_WRAP)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to encrypt secure preference value for keyAlias=$keyAlias", throwable)
        }.getOrNull()
    }

    private fun decode(encoded: String): String? {
        return runCatching {
            val packet = Base64.decode(encoded, Base64.NO_WRAP or Base64.NO_PADDING)
            require(packet.size > GCM_IV_LENGTH_BYTES) {
                "Encrypted preference payload is too short"
            }
            val iv = packet.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherBytes = packet.copyOfRange(GCM_IV_LENGTH_BYTES, packet.size)
            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, resolveSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to decrypt secure preference value for keyAlias=$keyAlias", throwable)
        }.getOrNull()
    }

    private fun resolveSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        synchronized(this) {
            cachedSecretKey?.let { return it }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(keyAlias, null) as? SecretKey
            if (existing != null) {
                cachedSecretKey = existing
                return existing
            }
            val generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val keySpec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(AES_KEY_SIZE_BITS)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
            generator.init(keySpec)
            val generated = generator.generateKey()
            cachedSecretKey = generated
            return generated
        }
    }

    private companion object {
        private const val TAG = "KeystoreBackedPrefs"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AES_KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}
