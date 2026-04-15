package com.auralis.crisisconnect.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Stores per-contact avatar thumbnails and provides compact payload encoding for transport.
 *
 * Avatars are intentionally kept small to reduce BLE/RFCOMM overhead and decoding cost.
 */
object ContactAvatarStorage {
    private const val TAG = "ContactAvatarStorage"
    private const val AVATAR_DIR = "contact_avatars"
    private const val MAX_TRANSPORT_BYTES = 3_072
    private const val MAX_ACCEPTED_INPUT_BYTES = 24_576
    private const val MAX_ACCEPTED_BASE64_LENGTH = 32_768
    private const val TARGET_DIMENSION_PX = 192
    private const val MIN_DIMENSION_PX = 48

    private val jpegQualities = intArrayOf(82, 72, 62, 52, 44, 36, 30, 24)
    private val avatarVersions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val versionCounter = AtomicLong(1L)

    fun observeAvatarVersion(sessionCode: String): Flow<Long> {
        val key = normalizeSessionKey(sessionCode)
        if (key.isBlank()) {
            return flowOf(0L)
        }
        return avatarVersions
            .map { versions -> versions[key] ?: 0L }
            .distinctUntilChanged()
    }

    fun loadContactAvatar(context: Context, sessionCode: String): Bitmap? {
        val key = normalizeSessionKey(sessionCode)
        if (key.isBlank()) {
            return null
        }
        val file = avatarFile(context, key)
        if (!file.exists() || file.length() <= 0L) {
            return null
        }
        return runCatching {
            BitmapFactory.decodeFile(file.absolutePath)
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to decode avatar for session=$sessionCode", throwable)
            null
        }
    }

    fun localProfileAvatarPayload(context: Context): String? {
        val bitmap = runCatching { ProfileImageStorage.loadProfileImage(context) }
            .getOrElse { throwable ->
                Log.w(TAG, "Failed to load local profile image", throwable)
                null
            } ?: return null
        val bytes = compressBitmapForTransport(bitmap) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun saveRemoteAvatarPayload(context: Context, sessionCode: String, payloadBase64: String?): Boolean {
        val key = normalizeSessionKey(sessionCode)
        val payload = payloadBase64?.trim().orEmpty()
        if (key.isBlank() || payload.isEmpty()) {
            return false
        }
        if (payload.length > MAX_ACCEPTED_BASE64_LENGTH) {
            return false
        }
        val decoded = runCatching {
            Base64.decode(payload, Base64.NO_WRAP)
        }.getOrElse { throwable ->
            Log.w(TAG, "Invalid avatar payload for session=$sessionCode", throwable)
            null
        } ?: return false
        if (decoded.isEmpty() || decoded.size > MAX_ACCEPTED_INPUT_BYTES) {
            return false
        }

        val decodedBitmap = runCatching {
            BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to decode avatar bytes for session=$sessionCode", throwable)
            null
        } ?: return false

        val compressed = compressBitmapForTransport(decodedBitmap) ?: return false
        val file = avatarFile(context, key)
        val writeSuccess = writeAtomically(file, compressed)
        if (writeSuccess) {
            bumpVersion(key)
        }
        return writeSuccess
    }

    fun clearContactAvatar(context: Context, sessionCode: String) {
        val key = normalizeSessionKey(sessionCode)
        if (key.isBlank()) {
            return
        }
        val file = avatarFile(context, key)
        if (file.exists()) {
            runCatching { file.delete() }
        }
        bumpVersion(key)
    }

    fun migrateSession(context: Context, fromSessionCode: String, toSessionCode: String) {
        val fromKey = normalizeSessionKey(fromSessionCode)
        val toKey = normalizeSessionKey(toSessionCode)
        if (fromKey.isBlank() || toKey.isBlank() || fromKey == toKey) {
            return
        }
        val fromFile = avatarFile(context, fromKey)
        if (!fromFile.exists() || fromFile.length() <= 0L) {
            return
        }
        val toFile = avatarFile(context, toKey)
        val moved = runCatching {
            toFile.parentFile?.mkdirs()
            fromFile.copyTo(toFile, overwrite = true)
            true
        }.getOrDefault(false)
        if (!moved) {
            return
        }
        runCatching { fromFile.delete() }
        bumpVersion(fromKey)
        bumpVersion(toKey)
    }

    private fun compressBitmapForTransport(bitmap: Bitmap): ByteArray? {
        var targetDimension = TARGET_DIMENSION_PX
        repeat(6) {
            val scaled = scaleDown(bitmap, targetDimension)
            for (quality in jpegQualities) {
                val bytes = compressJpeg(scaled, quality) ?: continue
                if (bytes.size <= MAX_TRANSPORT_BYTES) {
                    return bytes
                }
            }
            if (targetDimension <= MIN_DIMENSION_PX) {
                return null
            }
            targetDimension = (targetDimension * 0.82f).roundToInt().coerceAtLeast(MIN_DIMENSION_PX)
        }
        return null
    }

    private fun compressJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return runCatching {
            val stream = java.io.ByteArrayOutputStream()
            val success = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            if (success) stream.toByteArray() else null
        }.getOrNull()
    }

    private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val longest = maxOf(width, height)
        if (longest <= maxDimension) {
            return bitmap
        }
        val ratio = maxDimension.toFloat() / longest.toFloat()
        val targetWidth = (width * ratio).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }

    private fun avatarFile(context: Context, sessionKey: String): File {
        val dir = File(context.filesDir, AVATAR_DIR)
        if (!dir.exists()) {
            runCatching { dir.mkdirs() }
        }
        val fileName = "${sha256(sessionKey)}.jpg"
        return File(dir, fileName)
    }

    private fun writeAtomically(target: File, bytes: ByteArray): Boolean {
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) {
            return false
        }
        val temp = File(parent, "${target.name}.tmp")
        return runCatching {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "Failed to replace existing avatar file: ${target.absolutePath}")
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            true
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed writing avatar file ${target.absolutePath}", throwable)
            runCatching { temp.delete() }
            false
        }
    }

    private fun normalizeSessionKey(sessionCode: String): String {
        return sessionCode.trim().lowercase(Locale.US)
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val builder = StringBuilder(digest.size * 2)
        digest.forEach { byte ->
            builder.append(((byte.toInt() shr 4) and 0xF).toString(16))
            builder.append((byte.toInt() and 0xF).toString(16))
        }
        return builder.toString()
    }

    private fun bumpVersion(sessionKey: String) {
        val next = versionCounter.incrementAndGet()
        avatarVersions.update { current ->
            current + (sessionKey to next)
        }
    }
}
