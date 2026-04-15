package com.auralis.crisisconnect.data.local

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import java.io.ByteArrayOutputStream

object ProfileImageStorage {
    private const val TAG = "ProfileImageStorage"
    private const val PREF_NAME = "profile_image_prefs_v2"
    private const val LEGACY_PREF_NAME = "profile_image_prefs"
    private const val KEYSET_PREF_NAME = "__androidx_security_crypto_encrypted_prefs__"
    private const val MASTER_KEY_ALIAS = "cc_profile_image_prefs_master_v2"
    private const val MIGRATION_MARKER_KEY = "__migration_complete_v2"
    private const val KEY_IMAGE = "profile_image_base64"
    private const val MAX_PROFILE_IMAGE_DIMENSION_PX = 512
    private const val PROFILE_IMAGE_JPEG_QUALITY = 82

    @Volatile
    private var migrationChecked = false

    private fun prefs(context: Context): KeystoreBackedPreferences {
        val securePrefs = KeystoreBackedPreferences(
            context = context.applicationContext,
            prefName = PREF_NAME,
            keyAlias = MASTER_KEY_ALIAS
        )
        ensureMigrated(context.applicationContext, securePrefs)
        return securePrefs
    }

    private fun ensureMigrated(
        context: Context,
        securePrefs: KeystoreBackedPreferences
    ) {
        if (migrationChecked) {
            return
        }
        synchronized(this) {
            if (migrationChecked) {
                return
            }
            if (securePrefs.getString(MIGRATION_MARKER_KEY, null) == "1") {
                migrationChecked = true
                return
            }
            migrateLegacyPrefs(context, securePrefs)
            securePrefs.putString(MIGRATION_MARKER_KEY, "1")
            migrationChecked = true
        }
    }

    private fun migrateLegacyPrefs(
        context: Context,
        securePrefs: KeystoreBackedPreferences
    ) {
        val legacyPrefs = openLegacyEncryptedPrefs(context) ?: return
        runCatching {
            legacyPrefs.getString(KEY_IMAGE, null)?.let { imageBase64 ->
                securePrefs.putString(KEY_IMAGE, imageBase64)
            }
            context.deleteSharedPreferences(LEGACY_PREF_NAME)
            context.deleteSharedPreferences(KEYSET_PREF_NAME)
            Log.i(TAG, "Migrated legacy profile image storage to keystore-backed prefs")
        }.onFailure { throwable ->
            Log.w(TAG, "Legacy profile image migration failed", throwable)
        }
    }

    @Suppress("DEPRECATION")
    private fun openLegacyEncryptedPrefs(context: Context): SharedPreferences? {
        return runCatching {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context,
                LEGACY_PREF_NAME,
                masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure { throwable ->
            Log.d(TAG, "No readable legacy encrypted profile storage found", throwable)
        }.getOrNull()
    }

    fun saveProfileImage(context: Context, bitmap: Bitmap) {
        val normalizedBitmap = normalizeBitmapForStorage(bitmap)
        val outputStream = ByteArrayOutputStream()
        normalizedBitmap.compress(
            Bitmap.CompressFormat.JPEG,
            PROFILE_IMAGE_JPEG_QUALITY,
            outputStream
        )
        val bytes = outputStream.toByteArray()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        prefs(context).putString(KEY_IMAGE, encoded)
    }

    fun loadProfileImage(context: Context): Bitmap? {
        val base64 = prefs(context).getString(KEY_IMAGE, null) ?: return null
        val bytes = runCatching {
            Base64.decode(base64, Base64.NO_WRAP)
        }.getOrNull() ?: return null
        return decodeBitmapForStorage(bytes)
    }

    fun decodeProfileImage(context: Context, uri: Uri): Bitmap? {
        val imageDecoderBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeProfileImageWithImageDecoder(context, uri)
        } else {
            null
        }
        if (imageDecoderBitmap != null) {
            return imageDecoderBitmap
        }

        val bitmapFactoryBitmap = decodeProfileImageWithBitmapFactory(context, uri)
        if (bitmapFactoryBitmap == null) {
            val resolver = context.applicationContext.contentResolver
            Log.w(
                TAG,
                "Unable to decode selected profile image: uri=$uri mimeType=${resolver.getType(uri)}"
            )
        }
        return bitmapFactoryBitmap
    }

    fun getProfileImageBase64(context: Context): String? {
        return prefs(context).getString(KEY_IMAGE, null)
    }

    fun clearProfileImage(context: Context) {
        prefs(context).remove(KEY_IMAGE)
    }

    internal fun decodeBitmapForStorage(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) {
            return null
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                reqWidth = MAX_PROFILE_IMAGE_DIMENSION_PX,
                reqHeight = MAX_PROFILE_IMAGE_DIMENSION_PX
            )
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val normalized = normalizeBitmapForStorage(decoded)
        if (normalized !== decoded) {
            decoded.recycle()
        }
        return normalized
    }

    internal fun normalizeBitmapForStorage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val targetSize = scaledSize(width, height)
        if (targetSize.first == width && targetSize.second == height) {
            return bitmap
        }
        return Bitmap.createScaledBitmap(bitmap, targetSize.first, targetSize.second, true)
    }

    private fun applyExifRotation(
        context: Context,
        uri: Uri,
        bitmap: Bitmap
    ): Bitmap {
        val orientation = runCatching {
            context.applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
            else -> bitmap
        }
    }

    private fun decodeProfileImageWithBitmapFactory(
        context: Context,
        uri: Uri
    ): Bitmap? {
        val resolver = context.applicationContext.contentResolver
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: return null
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }

            val sampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                reqWidth = MAX_PROFILE_IMAGE_DIMENSION_PX,
                reqHeight = MAX_PROFILE_IMAGE_DIMENSION_PX
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
            val decoded = resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            val oriented = applyExifRotation(context, uri, decoded)
            if (oriented === decoded) {
                val normalized = normalizeBitmapForStorage(decoded)
                if (normalized !== decoded) {
                    decoded.recycle()
                }
                normalized
            } else {
                val normalized = normalizeBitmapForStorage(oriented)
                decoded.recycle()
                if (normalized !== oriented) {
                    oriented.recycle()
                }
                normalized
            }
        }.onFailure { throwable ->
            Log.w(TAG, "BitmapFactory failed for selected profile image: uri=$uri", throwable)
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeProfileImageWithImageDecoder(
        context: Context,
        uri: Uri
    ): Bitmap? {
        return runCatching {
            val source = ImageDecoder.createSource(context.applicationContext.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size ?: Size(
                    MAX_PROFILE_IMAGE_DIMENSION_PX,
                    MAX_PROFILE_IMAGE_DIMENSION_PX
                )
                val targetSize = scaledSize(size.width, size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                decoder.setTargetSize(targetSize.first, targetSize.second)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "ImageDecoder failed for selected profile image: uri=$uri", throwable)
        }.getOrNull()?.let { bitmap ->
            val normalized = normalizeBitmapForStorage(bitmap)
            if (normalized !== bitmap) {
                bitmap.recycle()
            }
            normalized
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            preScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f)
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun scaledSize(width: Int, height: Int): Pair<Int, Int> {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val largestEdge = maxOf(safeWidth, safeHeight)
        if (largestEdge <= MAX_PROFILE_IMAGE_DIMENSION_PX) {
            return safeWidth to safeHeight
        }
        val scale = MAX_PROFILE_IMAGE_DIMENSION_PX.toFloat() / largestEdge.toFloat()
        val targetWidth = (safeWidth * scale).toInt().coerceAtLeast(1)
        val targetHeight = (safeHeight * scale).toInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }
}
