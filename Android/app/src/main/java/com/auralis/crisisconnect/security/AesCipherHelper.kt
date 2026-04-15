package com.auralis.crisisconnect.security

import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Shared AES/GCM utilities for both Device A (offline) and Device B (field team).
 *
 * Responsibilities:
 * - Generate strong AES keys (128-bit or 256-bit).
 * - Encode/decode keys in Base64 without introducing UTF-8 corruption.
 * - Encrypt and decrypt payloads using AES/GCM/NoPadding where the resulting packet is
 *   `[IV (12 bytes)] + [ciphertext || authTag]`.
 * - Persist AES keys to Firestore in Base64, automatically upgrading existing documents that
 *   were saved as UTF-8 or Hex strings.
 */
object AesCipherHelper {

    private const val TAG = "AesCipherHelper"
    private const val AES_ALGORITHM = "AES"
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val GCM_IV_LENGTH_BYTES = 12
    private val secureRandom = SecureRandom()

    /**
     * Creates a random AES key using a cryptographically secure RNG.
     *
     * @param keySizeBits Either 128 or 256.
     */
    fun generateAesKey(keySizeBits: Int = 256): ByteArray {
        require(keySizeBits == 128 || keySizeBits == 256) {
            "AES key size must be 128 or 256 bits, was $keySizeBits"
        }
        val keyBytes = ByteArray(keySizeBits / 8)
        secureRandom.nextBytes(keyBytes)
        return keyBytes
    }

    fun encodeKeyToBase64(keyBytes: ByteArray): String {
        require(keyBytes.size == 16 || keyBytes.size == 32) {
            "AES key must be 16 or 32 bytes, was ${keyBytes.size}"
        }
        return Base64.encodeToString(keyBytes, Base64.NO_WRAP)
    }

    fun decodeKeyFromBase64(base64Key: String): ByteArray {
        val sanitized = base64Key.trim().replace("\n", "")
        val decoded = try {
            Base64.decode(sanitized, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Stored AES key is not valid Base64", error)
        }
        require(decoded.size == 16 || decoded.size == 32) {
            "Decoded AES key must be 16 or 32 bytes, was ${decoded.size}"
        }
        return decoded
    }

    /**
     * Encrypts [plainText] using AES/GCM/NoPadding with the given [keyBytes].
     * The returned packet is `[IV (12 bytes)] + [ciphertext || authTag (16 bytes)]`.
     */
    fun encrypt(keyBytes: ByteArray, plainText: ByteArray): ByteArray {
        validateKeyLength(keyBytes)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val keySpec = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, spec)
        val cipherText = cipher.doFinal(plainText)
        return ByteArray(iv.size + cipherText.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(cipherText, 0, this, iv.size, cipherText.size)
        }
    }

    /**
     * Decrypts an AES/GCM packet produced by [encrypt].
     */
    fun decrypt(keyBytes: ByteArray, encryptedPacket: ByteArray): ByteArray {
        validateKeyLength(keyBytes)
        require(encryptedPacket.size >= GCM_IV_LENGTH_BYTES + 16) {
            "Encrypted packet is too short to contain IV and GCM tag"
        }
        val iv = encryptedPacket.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val cipherBytes = encryptedPacket.copyOfRange(GCM_IV_LENGTH_BYTES, encryptedPacket.size)
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        val keySpec: SecretKey = SecretKeySpec(keyBytes, AES_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)
        return cipher.doFinal(cipherBytes)
    }

    /**
     * Prefixes the encrypted packet with a big-endian payload length header. This is required so
     * BLE receivers know how many bytes to buffer before attempting to decrypt.
     */
    fun wrapForTransport(encryptedPacket: ByteArray): ByteArray {
        val length = encryptedPacket.size
        require(length in 1..0xFFFF) {
            "Encrypted packet size must be between 1 and 65535 bytes, was $length"
        }
        val header = byteArrayOf(
            ((length shr 8) and 0xFF).toByte(),
            (length and 0xFF).toByte(),
        )
        return ByteArray(header.size + encryptedPacket.size).apply {
            System.arraycopy(header, 0, this, 0, header.size)
            System.arraycopy(encryptedPacket, 0, this, header.size, encryptedPacket.size)
        }
    }

    /**
     * Removes the transport header (added via [wrapForTransport]) and validates the total size.
     */
    fun unwrapTransportPacket(transportPacket: ByteArray, maxPacketSize: Int): ByteArray {
        require(transportPacket.size >= Short.SIZE_BYTES) {
            "Transport packet missing length header"
        }
        val length = ((transportPacket[0].toInt() and 0xFF) shl 8) or
            (transportPacket[1].toInt() and 0xFF)
        require(length in 1..maxPacketSize) {
            "Transport packet declared invalid length=$length"
        }
        require(transportPacket.size - Short.SIZE_BYTES == length) {
            "Transport payload truncated: expected $length bytes, found ${transportPacket.size - Short.SIZE_BYTES}"
        }
        require(length >= GCM_IV_LENGTH_BYTES + 16) {
            "Encrypted payload too short to contain IV and GCM tag"
        }
        return transportPacket.copyOfRange(Short.SIZE_BYTES, transportPacket.size)
    }

    /**
     * Persists the AES key for [deviceId] to Firestore ensuring Base64 encoding.
     */
    suspend fun storeAesKeyBase64(
        firestore: FirebaseFirestore,
        deviceId: String,
        keyBytes: ByteArray,
        collectionPath: String = "devices"
    ): String = withContext(Dispatchers.IO) {
        validateKeyLength(keyBytes)
        val normalizedId = deviceId.trim()
        require(normalizedId.isNotEmpty()) { "deviceId is required" }

        val base64Key = encodeKeyToBase64(keyBytes)
        val document = firestore.collection(collectionPath).document(normalizedId)
        val payload = mapOf(
            "aesKey" to base64Key,
            "aesKeyEncoding" to "base64",
            "updatedAt" to FieldValue.serverTimestamp()
        )
        document.set(payload, SetOptions.merge()).awaitResult()
        Log.d(TAG, "Stored AES key for $normalizedId as Base64")
        base64Key
    }

    /**
     * Loads the AES key for [deviceId], upgrading legacy UTF-8 or hex encodings to Base64.
     */
    suspend fun loadAesKeyBase64(
        firestore: FirebaseFirestore,
        deviceId: String,
        collectionPath: String = "devices"
    ): ByteArray = withContext(Dispatchers.IO) {
        val normalizedId = deviceId.trim()
        require(normalizedId.isNotEmpty()) { "deviceId is required" }

        val document = firestore.collection(collectionPath).document(normalizedId)
        val snapshot = document.get().awaitResult()
        if (!snapshot.exists()) {
            throw IllegalStateException("No AES key registered for device $normalizedId")
        }
        val storedValue = snapshot.getString("aesKey")
            ?: throw IllegalStateException("Document for $normalizedId missing aesKey field")

        val (base64Key, rawBytes) = normalizeKeyEncoding(storedValue, document)
        Log.d(TAG, "Loaded AES key for $normalizedId with size=${rawBytes.size}")
        rawBytes
    }

    private suspend fun normalizeKeyEncoding(
        storedValue: String,
        document: DocumentReference,
        fieldName: String = "aesKey"
    ): Pair<String, ByteArray> {
        val sanitized = storedValue.trim()
        val withoutWhitespace = sanitized.replace("\n", "")

        decodeBase64OrNull(withoutWhitespace)?.let { decoded ->
            validateKeyLength(decoded)
            val canonical = encodeKeyToBase64(decoded)
            if (canonical != withoutWhitespace) {
                Log.d(TAG, "Rewriting AES key to canonical Base64 encoding")
                updateStoredKey(document, fieldName, canonical)
            }
            return canonical to decoded
        }

        if (HEX_REGEX.matches(withoutWhitespace) && withoutWhitespace.length % 2 == 0) {
            val decoded = hexToBytes(withoutWhitespace)
            validateKeyLength(decoded)
            val canonical = encodeKeyToBase64(decoded)
            Log.w(TAG, "Converted AES key from hex to Base64 for document=${document.id}")
            updateStoredKey(document, fieldName, canonical)
            return canonical to decoded
        }

        val utf8Bytes = sanitized.toByteArray(Charsets.UTF_8)
        validateKeyLength(utf8Bytes)
        val canonical = encodeKeyToBase64(utf8Bytes)
        Log.w(TAG, "Converted AES key from UTF-8 to Base64 for document=${document.id}")
        updateStoredKey(document, fieldName, canonical)
        return canonical to utf8Bytes
    }

    private suspend fun updateStoredKey(
        document: DocumentReference,
        fieldName: String,
        base64Key: String
    ) {
        runCatching {
            document.update(
                mapOf(
                    fieldName to base64Key,
                    "aesKeyEncoding" to "base64",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).awaitResult()
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to rewrite AES key encoding", throwable)
            throw throwable
        }
    }

    private fun decodeBase64OrNull(value: String): ByteArray? = runCatching {
        Base64.decode(value, Base64.NO_WRAP or Base64.NO_PADDING)
    }.getOrNull()

    private fun validateKeyLength(keyBytes: ByteArray) {
        require(keyBytes.size == 16 || keyBytes.size == 32) {
            "AES key must be 16 or 32 bytes, was ${keyBytes.size}"
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val output = ByteArrayOutputStream(hex.length / 2)
        var index = 0
        while (index < hex.length) {
            val byte = hex.substring(index, index + 2).toInt(16)
            output.write(byte)
            index += 2
        }
        return output.toByteArray()
    }

    private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val result = task.result
                @Suppress("UNCHECKED_CAST")
                continuation.resume((result ?: Unit) as T)
            } else {
                val error = task.exception ?: IllegalStateException("Firebase task failed")
                continuation.resumeWithException(error)
            }
        }

        continuation.invokeOnCancellation {
            // Task API lacks cancellation; nothing additional to do.
        }
    }

    private val HEX_REGEX = Regex("^[0-9a-fA-F]+$")
}
