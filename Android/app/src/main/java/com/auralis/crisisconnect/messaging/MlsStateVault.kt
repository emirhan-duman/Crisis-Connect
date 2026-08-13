package com.auralis.crisisconnect.messaging

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The locally wrapped MLS ratchet cannot be resumed without risking sender-generation reuse.
 * Callers may recover only after the relay proves the leaf never published, or by rotating to a
 * fresh per-conversation leaf identity.
 */
class MlsStateRecoveryRequiredException(
    message: String = "MLS ratchet must be securely rejoined.",
    cause: Throwable? = null,
) : SecurityException(message, cause)

/**
 * Device-local persistence for secret OpenMLS ratchet snapshots. The AES key is generated as a
 * non-exportable Android Keystore key (StrongBox when available, TEE fallback) and ciphertext is
 * written atomically under noBackupFilesDir so neither cloud backup nor a partial write can clone or
 * roll the live ratchet back. Callers must serialize load/advance/save as one operation.
 */
object MlsStateVault {
    private const val KEY_ALIAS = "crisis-connect-mls-state-v1"
    private const val PROTECTED_KEY_ALIAS = "crisis-connect-protected-messaging-v1"
    private const val MAX_SNAPSHOT_BYTES = AuthorityMlsDurableStateCodec.MAX_DURABLE_STATE_BYTES
    private const val MAX_PROTECTED_BYTES = 2 * 1024 * 1024
    private val MAGIC = byteArrayOf(0x43, 0x43, 0x4d, 0x4c, 0x53, 0x01)
    private val JOURNAL_MAGIC = byteArrayOf(0x43, 0x43, 0x4d, 0x4c, 0x53, 0x4a, 0x01)
    private val PROTECTED_MAGIC = byteArrayOf(0x43, 0x43, 0x50, 0x4d, 0x53, 0x01)
    private val lock = Any()

    fun load(context: Context, stateContext: String): ByteArray? = synchronized(lock) {
        validateContext(stateContext)
        if (journalFile(context, stateContext).exists()) {
            throw MlsStateRecoveryRequiredException(
                "MLS ratchet has an unfinished advance and must be securely rejoined.",
            )
        }
        val file = AtomicFile(stateFile(context, stateContext))
        if (!file.baseFile.exists()) return@synchronized null
        val encoded = file.readFully()
        require(encoded.size in (MAGIC.size + 1 + 12 + 16)..(MAX_SNAPSHOT_BYTES + 128)) {
            "Wrapped MLS state is malformed."
        }
        val buffer = ByteBuffer.wrap(encoded)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC)) { "Wrapped MLS state version is invalid." }
        val nonceLength = buffer.get().toInt() and 0xff
        require(nonceLength == 12 && buffer.remaining() > nonceLength + 16) {
            "Wrapped MLS state nonce is invalid."
        }
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad(stateContext))
        cipher.doFinal(ciphertext).also {
            require(it.size in 1..MAX_SNAPSHOT_BYTES) { "MLS state snapshot has an invalid size." }
        }
    }

    /** Commits a write-ahead marker before any in-memory MLS ratchet mutation. */
    fun beginAdvance(context: Context, stateContext: String) = synchronized(lock) {
        validateContext(stateContext)
        val file = AtomicFile(journalFile(context, stateContext))
        check(!file.baseFile.exists()) {
            "MLS ratchet already has an unfinished advance."
        }
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(JOURNAL_MAGIC)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    fun save(context: Context, stateContext: String, snapshot: ByteArray) = synchronized(lock) {
        validateContext(stateContext)
        require(snapshot.size in 1..MAX_SNAPSHOT_BYTES) { "MLS state snapshot has an invalid size." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        cipher.updateAAD(aad(stateContext))
        val ciphertext = cipher.doFinal(snapshot)
        val encoded = ByteArrayOutputStream(MAGIC.size + 1 + cipher.iv.size + ciphertext.size).use { out ->
            out.write(MAGIC)
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(ciphertext)
            out.toByteArray()
        }
        val file = AtomicFile(stateFile(context, stateContext))
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encoded)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
        // Snapshot replacement happens first. A crash before this deletion remains fail-closed;
        // a successful return means both the new ratchet and cleared journal are durable.
        AtomicFile(journalFile(context, stateContext)).delete()
    }

    fun delete(context: Context, stateContext: String) = synchronized(lock) {
        validateContext(stateContext)
        AtomicFile(stateFile(context, stateContext)).delete()
        AtomicFile(journalFile(context, stateContext)).delete()
    }

    /** Generic atomic secret storage for messaging pins/history, under a separate Keystore key. */
    fun loadProtectedData(context: Context, storageContext: String): ByteArray? = synchronized(lock) {
        validateContext(storageContext)
        val file = AtomicFile(protectedFile(context, storageContext))
        if (!file.baseFile.exists()) return@synchronized null
        val encoded = file.readFully()
        require(encoded.size in (PROTECTED_MAGIC.size + 1 + 12 + 16)..(MAX_PROTECTED_BYTES + 128)) {
            "Wrapped protected messaging data is malformed."
        }
        val buffer = ByteBuffer.wrap(encoded)
        val magic = ByteArray(PROTECTED_MAGIC.size).also(buffer::get)
        require(magic.contentEquals(PROTECTED_MAGIC)) { "Protected messaging data version is invalid." }
        val nonceLength = buffer.get().toInt() and 0xff
        require(nonceLength == 12 && buffer.remaining() > nonceLength + 16) {
            "Protected messaging data nonce is invalid."
        }
        val nonce = ByteArray(nonceLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(PROTECTED_KEY_ALIAS), GCMParameterSpec(128, nonce))
        cipher.updateAAD(protectedAad(storageContext))
        cipher.doFinal(ciphertext).also {
            require(it.size in 1..MAX_PROTECTED_BYTES) { "Protected messaging data has an invalid size." }
        }
    }

    fun saveProtectedData(context: Context, storageContext: String, plaintext: ByteArray) = synchronized(lock) {
        validateContext(storageContext)
        require(plaintext.size in 1..MAX_PROTECTED_BYTES) { "Protected messaging data has an invalid size." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(PROTECTED_KEY_ALIAS))
        cipher.updateAAD(protectedAad(storageContext))
        val ciphertext = cipher.doFinal(plaintext)
        val encoded = ByteArrayOutputStream(PROTECTED_MAGIC.size + 1 + cipher.iv.size + ciphertext.size).use { out ->
            out.write(PROTECTED_MAGIC)
            out.write(cipher.iv.size)
            out.write(cipher.iv)
            out.write(ciphertext)
            out.toByteArray()
        }
        val file = AtomicFile(protectedFile(context, storageContext))
        file.baseFile.parentFile?.mkdirs()
        val stream = file.startWrite()
        try {
            stream.write(encoded)
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    fun deleteProtectedData(context: Context, storageContext: String) = synchronized(lock) {
        validateContext(storageContext)
        AtomicFile(protectedFile(context, storageContext)).delete()
    }

    private fun wrappingKey(alias: String = KEY_ALIAS): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { key ->
            check(key.encoded == null) { "MLS wrapping key must remain non-exportable." }
            return key
        }
        return generateKey(alias, strongBox = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    }

    private fun generateKey(alias: String, strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return try {
            generator.init(builder.build())
            generator.generateKey()
        } catch (error: ProviderException) {
            if (!strongBox) throw error
            generateKey(alias, strongBox = false)
        }.also { check(it.encoded == null) { "MLS wrapping key must remain non-exportable." } }
    }

    private fun stateFile(context: Context, stateContext: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(stateContext.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.noBackupFilesDir, "mls-state-v1"), "$name.bin")
    }

    private fun journalFile(context: Context, stateContext: String): File {
        val state = stateFile(context, stateContext)
        return File(state.parentFile, "${state.name}.advance")
    }

    private fun protectedFile(context: Context, storageContext: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(storageContext.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.noBackupFilesDir, "protected-messaging-v1"), "$name.bin")
    }

    private fun aad(stateContext: String): ByteArray =
        "cc-mls-state:v1:$stateContext".toByteArray(Charsets.UTF_8)

    private fun protectedAad(storageContext: String): ByteArray =
        "cc-protected-messaging:v1:$storageContext".toByteArray(Charsets.UTF_8)

    private fun validateContext(stateContext: String) {
        require(stateContext.isNotBlank() && stateContext.toByteArray(Charsets.UTF_8).size <= 512) {
            "MLS state context is invalid."
        }
    }
}
