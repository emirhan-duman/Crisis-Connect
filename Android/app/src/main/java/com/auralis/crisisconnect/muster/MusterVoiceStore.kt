package com.auralis.crisisconnect.muster

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, account-bound audio files. No audio is copied into the preference journal. */
internal class MusterEncryptedVoiceStore(private val root: File, private val secret: () -> SecretKey,
    private val maxAccountBytes: Long = 4L * 1024 * 1024, private val maxTotalBytes: Long = 32L * 1024 * 1024) : MusterVoiceStorage {
    private fun directory(uid: String) = File(root, musterHash(uid.toByteArray())).apply { check(mkdirs() || isDirectory) }
    private fun file(uid: String, ref: MusterVoiceRef): File {
        require(Regex("^[a-f0-9]{64}$").matches(ref.file))
        return File(directory(uid), ref.file)
    }
    private fun aad(uid: String, filename: String) = "muster-voice-v1:${musterHash(uid.toByteArray())}:$filename".toByteArray()
    @Synchronized override fun put(uid: String, operationId: String, draft: MusterVoiceDraft): MusterVoiceRef {
        val ref = MusterVoiceRef(musterHash(operationId.toByteArray()), draft.durationMillis, draft.bytes.size, draft.sha256)
        val destination = file(uid, ref)
        if (destination.exists()) { require(read(uid, ref).contentEquals(draft.bytes)); return ref }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secret()); updateAAD(aad(uid, ref.file)) }
        val packet = cipher.iv + cipher.doFinal(draft.bytes)
        val directory = destination.parentFile!!
        val used = directory.listFiles()?.sumOf { it.length() } ?: 0L
        val totalUsed = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // Refuse new captures at either budget; another account's pending audio is never evicted.
        check(used + packet.size <= maxAccountBytes && totalUsed + packet.size <= maxTotalBytes) { "muster-voice-storage-full" }
        val temporary = File.createTempFile("pending-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { it.write(packet); it.flush(); it.fd.sync() }
            check(temporary.renameTo(destination)) { "muster-voice-persistence-failed" }
        } finally { temporary.delete() }
        return ref
    }
    @Synchronized override fun read(uid: String, ref: MusterVoiceRef): ByteArray {
        val source = file(uid, ref)
        require(source.length() in 29..(MUSTER_VOICE_MAX_BYTES + 28).toLong())
        val packet = source.readBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, packet.copyOfRange(0, 12))); updateAAD(aad(uid, ref.file))
        }
        return cipher.doFinal(packet, 12, packet.size - 12).also {
            require(it.size == ref.bytes && musterHash(it) == ref.sha256) { "muster-voice-corrupt" }
        }
    }
    @Synchronized override fun reconcile(uid: String, referencedFiles: Set<String>) {
        // An interrupted pre-journal write is not a response. Age protects any in-flight writer.
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory(uid).listFiles()?.filter { it.name !in referencedFiles && it.lastModified() < cutoff }?.forEach { it.delete() }
    }
    @Synchronized override fun delete(uid: String, ref: MusterVoiceRef) { val target = file(uid, ref); check(!target.exists() || target.delete()) }
}

internal fun musterVoiceStorage(context: Context): MusterVoiceStorage {
    val alias = "personnel_muster_voice_key_v1"
    return MusterEncryptedVoiceStore(File(context.noBackupFilesDir, "muster-voice-v1"), secret = {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) checkNotNull(keyStore.getKey(alias, null) as? SecretKey)
        else KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    })
}
