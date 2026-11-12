package com.auralis.crisisconnect.core.media

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stable history paths, atomic ciphertext writes, and lazy upgrade of pre-encryption media. */
internal class EncryptedMediaFiles(private val root: File, private val key: () -> SecretKey) {
    private val directories = setOf("voice_messages", "image_messages", "image_thumbnails", "contact_avatars")
    private val magic = "CCMEDIA1".toByteArray(Charsets.US_ASCII)

    fun manages(file: File): Boolean {
        fun protectedPath(path: File, base: File): Boolean {
            val relative = path.relativeToOrNull(base) ?: return false
            return relative.path.substringBefore(File.separatorChar) in directories && relative.parent != null
        }
        val canonical = protectedPath(file.canonicalFile, root.canonicalFile)
        if (protectedPath(file.absoluteFile.normalize(), root.absoluteFile.normalize()) && !canonical) {
            throw IOException("Managed media path escapes its storage directory")
        }
        return canonical
    }

    @Synchronized
    fun read(file: File): ByteArray {
        val bytes = file.readBytes()
        if (!manages(file)) return bytes
        if (!bytes.take(magic.size).toByteArray().contentEquals(magic)) {
            // Preserve the original on key/write failure. Never return an unprotected legacy
            // attachment until its durable copy has been upgraded successfully.
            write(file, bytes)
            return bytes
        }
        try {
            require(bytes.size >= magic.size + 12 + 16)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(magic.size, magic.size + 12)))
            cipher.updateAAD(magic)
            return cipher.doFinal(bytes, magic.size + 12, bytes.size - magic.size - 12)
        } catch (error: Exception) {
            throw IOException("Encrypted media unavailable", error)
        }
    }

    @Synchronized
    fun write(file: File, bytes: ByteArray) {
        if (!manages(file)) { file.writeBytes(bytes); return }
        var temporary: File? = null
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            // Let AndroidKeyStore choose a fresh IV; randomized encryption is mandatory.
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(magic)
            val sealed = cipher.doFinal(bytes)
            val parent = file.parentFile ?: throw IOException("Missing media directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create media directory")
            temporary = File.createTempFile(".sealed-", ".tmp", parent)
            FileOutputStream(temporary).use {
                it.write(magic); it.write(cipher.iv); it.write(sealed); it.fd.sync()
            }
            if (!temporary.renameTo(file)) throw IOException("Cannot atomically replace media")
        } catch (error: Exception) {
            throw IOException("Cannot protect media", error)
        } finally { temporary?.delete() }
    }

    fun migrateExisting(): List<File> = directories.flatMap { name ->
        File(root, name).walkTopDown().filter { it.isFile }.mapNotNull { file ->
            runCatching { read(file); null }.getOrElse { file }
        }.toList()
    }
}
