package com.auralis.crisisconnect.core.media

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

object LocalMedia {
    @Volatile private var store: EncryptedMediaFiles? = null
    private const val ALIAS = "cc.persisted-media.v1"

    @Synchronized fun initialize(context: Context) {
        if (store == null) store = EncryptedMediaFiles(context.applicationContext.filesDir, ::loadKey)
    }
    @Synchronized internal fun initialize(context: Context, keyProvider: () -> SecretKey) {
        store = EncryptedMediaFiles(context.applicationContext.filesDir, keyProvider)
    }
    @Synchronized private fun loadKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(ALIAS)) return keyStore.getKey(ALIAS, null) as SecretKey
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
    private fun files(): EncryptedMediaFiles = checkNotNull(store) { "Local media store not initialized" }
    fun manages(file: File): Boolean = files().manages(file)
    fun read(file: File): ByteArray = files().read(file)
    fun write(file: File, bytes: ByteArray) = files().write(file, bytes)
    fun copy(source: File, target: File) = write(target, read(source))
    @android.annotation.SuppressLint("LogNotTimber") // Platform logging; no Timber runtime dependency.
    fun migrateExisting() {
        files().migrateExisting().forEach { Log.w("LocalMedia", "Media upgrade deferred: ${it.name}") }
    }
    fun file(uri: Uri): File? = if (uri.scheme == "file" || uri.scheme == null) uri.path?.let(::File) else null
    fun dataSource(file: File): MediaDataSource = ByteMediaDataSource(read(file))

    fun decodeSampledImage(file: File, reqWidth: Int, reqHeight: Int): android.graphics.Bitmap? = runCatching {
        require(reqWidth > 0 && reqHeight > 0)
        val bytes = read(file)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqWidth && bounds.outHeight / (sample * 2) >= reqHeight) sample *= 2
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Explicit user export; internal decoding and playback never create a plaintext cache. */
    fun export(context: Context, source: File): File {
        if (!manages(source)) return source
        val directory = File(context.cacheDir, "media_exports").apply { mkdirs() }
        cleanExports(context)
        val target = File(directory, "${java.util.UUID.randomUUID()}-${source.name}")
        target.writeBytes(read(source))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ target.delete() }, 15 * 60_000L)
        return target
    }
    fun cleanExports(context: Context) {
        val cutoff = System.currentTimeMillis() - 15 * 60_000L
        File(context.cacheDir, "media_exports").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }
}

internal class ByteMediaDataSource(private var bytes: ByteArray) : MediaDataSource() {
    override fun getSize(): Long = bytes.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        require(position >= 0 && offset >= 0 && size >= 0 && offset <= buffer.size - size)
        if (size == 0) return 0
        if (position >= bytes.size) return -1
        val length = minOf(size, bytes.size - position.toInt())
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + length)
        return length
    }
    override fun close() { bytes.fill(0); bytes = ByteArray(0) }
}
