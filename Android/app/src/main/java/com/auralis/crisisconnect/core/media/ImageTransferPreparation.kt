package com.auralis.crisisconnect.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

internal const val IMAGE_MIME_JPEG = "image/jpeg"

internal data class ImageTransferOptimizationProfile(
    val maxDimension: Int,
    val targetBytes: Int,
    val initialJpegQuality: Int,
    val minJpegQuality: Int,
    val qualityStep: Int,
    val forceOptimizeBytes: Long,
    val nonJpegOptimizeBytes: Long,
    val alwaysOptimize: Boolean = false,
    val useDynamicMaxDimension: Boolean = false,
    val maxOutputBytes: Int? = null
)

internal data class PreparedImageAttachment(
    val fileName: String,
    val thumbnailName: String?,
    val bytes: ByteArray,
    val width: Int?,
    val height: Int?,
    val mimeType: String
)

private data class CompressedBitmapResult(
    val bytes: ByteArray,
    val width: Int,
    val height: Int
)

internal val DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE = ImageTransferOptimizationProfile(
    maxDimension = 1600,
    targetBytes = 450_000,
    initialJpegQuality = 84,
    minJpegQuality = 58,
    qualityStep = 7,
    forceOptimizeBytes = 700_000L,
    nonJpegOptimizeBytes = 350_000L,
    useDynamicMaxDimension = true
)

internal val BLE_IMAGE_TRANSFER_PROFILE = ImageTransferOptimizationProfile(
    maxDimension = 1280,
    targetBytes = 425_000,
    initialJpegQuality = 82,
    minJpegQuality = 50,
    qualityStep = 6,
    forceOptimizeBytes = 0L,
    nonJpegOptimizeBytes = 0L,
    alwaysOptimize = true,
    maxOutputBytes = 512 * 1024
)

internal fun prepareImageAttachmentForTransfer(
    context: Context,
    uuid: String,
    uri: Uri,
    mimeType: String?,
    fallbackWidth: Int?,
    fallbackHeight: Int?,
    profile: ImageTransferOptimizationProfile
): PreparedImageAttachment? {
    val normalizedMime = normalizeImageMimeType(mimeType)
    val originalFile = imageMessageFile(context, ImageFileUtils.fileNameFor(uuid, normalizedMime))
    val compressedFile = imageMessageFile(context, ImageFileUtils.fileNameFor(uuid, IMAGE_MIME_JPEG))
    val thumbnailFile = imageThumbnailFile(context, ImageFileUtils.thumbnailNameFor(uuid, IMAGE_MIME_JPEG))
    val cleanupTargets = linkedSetOf(originalFile, compressedFile, thumbnailFile)

    return runCatching {
        originalFile.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(originalFile, false).use { output ->
                input.copyTo(output)
            }
        } ?: return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(originalFile.absolutePath, bounds)
        val orientedBounds = resolveOrientedDimensions(
            originalFile,
            bounds.outWidth,
            bounds.outHeight
        )
        val fileSizeBytes = originalFile.length()
        val shouldOptimize = shouldOptimizeImageForTransfer(
            profile = profile,
            fileSizeBytes = fileSizeBytes,
            width = orientedBounds.first,
            height = orientedBounds.second,
            mimeType = normalizedMime
        )

        var finalFile = originalFile
        var finalMimeType = normalizedMime
        var resolvedWidth = orientedBounds.first.takeIf { it > 0 } ?: fallbackWidth
        var resolvedHeight = orientedBounds.second.takeIf { it > 0 } ?: fallbackHeight

        if (shouldOptimize) {
            val maxDimension = selectTransferMaxDimension(
                profile = profile,
                fileSizeBytes = fileSizeBytes,
                width = bounds.outWidth,
                height = bounds.outHeight
            )
            val sampleSize = calculateInSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                maxDimension,
                maxDimension
            )
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize.coerceAtLeast(1)
            }
            val decoded = BitmapFactory.decodeFile(originalFile.absolutePath, decodeOptions)
                ?: return null
            val rotated = applyExifRotation(originalFile, decoded)
            val scaled = scaleBitmapToMaxDimension(rotated, maxDimension)
            val compressed = compressBitmapForTransfer(
                bitmap = scaled,
                profile = profile,
                maxOutputBytes = profile.maxOutputBytes
            )
            compressedFile.parentFile?.mkdirs()
            FileOutputStream(compressedFile, false).use { output ->
                output.write(compressed.bytes)
                output.flush()
            }
            if (scaled !== rotated) {
                scaled.recycle()
            }
            if (rotated !== decoded) {
                decoded.recycle()
            }
            rotated.recycle()

            if (compressedFile.absolutePath != originalFile.absolutePath) {
                deleteIfExists(originalFile)
            }

            resolvedWidth = compressed.width.takeIf { it > 0 } ?: fallbackWidth
            resolvedHeight = compressed.height.takeIf { it > 0 } ?: fallbackHeight
            finalFile = compressedFile
            finalMimeType = IMAGE_MIME_JPEG
        }

        val bytes = finalFile.readBytes()
        val maxOutputBytes = profile.maxOutputBytes
        if (maxOutputBytes != null && bytes.size > maxOutputBytes) {
            cleanupTargets.forEach(::deleteIfExists)
            return null
        }

        val thumbnailName = ImageFileUtils.thumbnailNameFor(uuid, finalMimeType)
        val thumbnailTarget = imageThumbnailFile(context, thumbnailName)
        val thumbnailCreated = generateImageThumbnail(
            source = finalFile,
            target = thumbnailTarget,
            mimeType = finalMimeType
        )

        PreparedImageAttachment(
            fileName = finalFile.name,
            thumbnailName = if (thumbnailCreated) thumbnailName else null,
            bytes = bytes,
            width = resolvedWidth,
            height = resolvedHeight,
            mimeType = finalMimeType
        )
    }.getOrElse {
        cleanupTargets.forEach(::deleteIfExists)
        null
    }
}

fun generateImageThumbnail(
    source: File,
    target: File,
    mimeType: String?
): Boolean {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val reqSize = 512
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqSize, reqSize)
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return@runCatching false
        val rotated = applyExifRotation(source, bitmap)
        target.parentFile?.mkdirs()
        FileOutputStream(target, false).use { output ->
            rotated.compress(compressFormatForMime(mimeType), 85, output)
        }
        if (rotated !== bitmap) {
            bitmap.recycle()
            rotated.recycle()
        } else {
            bitmap.recycle()
        }
        true
    }.getOrElse {
        deleteIfExists(target)
        false
    }
}

internal fun normalizeImageMimeType(mimeType: String?): String {
    val normalized = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        normalized.contains("jpeg") || normalized.contains("jpg") -> IMAGE_MIME_JPEG
        normalized.contains("png") -> "image/png"
        normalized.contains("webp") -> "image/webp"
        normalized.contains("heic") -> "image/heic"
        normalized.contains("heif") -> "image/heif"
        normalized.startsWith("image/") -> normalized
        else -> IMAGE_MIME_JPEG
    }
}

private fun shouldOptimizeImageForTransfer(
    profile: ImageTransferOptimizationProfile,
    fileSizeBytes: Long,
    width: Int,
    height: Int,
    mimeType: String
): Boolean {
    if (profile.alwaysOptimize) {
        return true
    }
    val isJpeg = mimeType == IMAGE_MIME_JPEG
    if (fileSizeBytes >= profile.forceOptimizeBytes) {
        return true
    }
    if (width > profile.maxDimension || height > profile.maxDimension) {
        return true
    }
    return !isJpeg && fileSizeBytes >= profile.nonJpegOptimizeBytes
}

private fun selectTransferMaxDimension(
    profile: ImageTransferOptimizationProfile,
    fileSizeBytes: Long,
    width: Int,
    height: Int
): Int {
    if (!profile.useDynamicMaxDimension) {
        return profile.maxDimension
    }
    val largestEdge = maxOf(width, height)
    return when {
        fileSizeBytes >= 8_000_000L || largestEdge >= 4000 -> 1280
        fileSizeBytes >= 3_000_000L || largestEdge >= 2800 -> 1440
        else -> profile.maxDimension
    }
}

private fun compressBitmapForTransfer(
    bitmap: Bitmap,
    profile: ImageTransferOptimizationProfile,
    maxOutputBytes: Int?
): CompressedBitmapResult {
    var workingBitmap = bitmap
    while (true) {
        val bytes = compressBitmapAtTargetQuality(workingBitmap, profile)
        if (maxOutputBytes == null || bytes.size <= maxOutputBytes) {
            val result = CompressedBitmapResult(
                bytes = bytes,
                width = workingBitmap.width,
                height = workingBitmap.height
            )
            if (workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }
            return result
        }

        val nextWidth = scaledDimensionForBudget(workingBitmap.width, bytes.size, maxOutputBytes)
        val nextHeight = scaledDimensionForBudget(workingBitmap.height, bytes.size, maxOutputBytes)
        if (
            nextWidth >= workingBitmap.width ||
            nextHeight >= workingBitmap.height ||
            nextWidth < 1 ||
            nextHeight < 1
        ) {
            val result = CompressedBitmapResult(
                bytes = bytes,
                width = workingBitmap.width,
                height = workingBitmap.height
            )
            if (workingBitmap !== bitmap) {
                workingBitmap.recycle()
            }
            return result
        }

        val scaledBitmap = Bitmap.createScaledBitmap(
            workingBitmap,
            nextWidth,
            nextHeight,
            true
        )
        if (workingBitmap !== bitmap) {
            workingBitmap.recycle()
        }
        workingBitmap = scaledBitmap
    }
}

private fun compressBitmapAtTargetQuality(
    bitmap: Bitmap,
    profile: ImageTransferOptimizationProfile
): ByteArray {
    var quality = profile.initialJpegQuality
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    while (output.size() > profile.targetBytes && quality > profile.minJpegQuality) {
        quality = (quality - profile.qualityStep).coerceAtLeast(profile.minJpegQuality)
        output.reset()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    }
    return output.toByteArray()
}

private fun scaledDimensionForBudget(
    current: Int,
    currentBytes: Int,
    maxOutputBytes: Int
): Int {
    val budgetRatio = (maxOutputBytes.toDouble() / currentBytes.toDouble()).coerceAtMost(0.98)
    val edgeScale = kotlin.math.sqrt(budgetRatio).coerceIn(0.55, 0.95)
    return (current * edgeScale).toInt().coerceAtLeast(1)
}

private fun resolveOrientedDimensions(file: File, rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
    if (rawWidth <= 0 || rawHeight <= 0) {
        return 0 to 0
    }
    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_UNDEFINED
        )
    }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
    return if (
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
        orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
        orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
        orientation == ExifInterface.ORIENTATION_TRANSVERSE
    ) {
        rawHeight to rawWidth
    } else {
        rawWidth to rawHeight
    }
}

private fun calculateInSampleSize(
    width: Int,
    height: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize.coerceAtLeast(1)
}

private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
    return try {
        val exif = ExifInterface(source.absolutePath)
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
            else -> bitmap
        }
    } catch (_: Exception) {
        bitmap
    }
}

private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(angle) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun scaleBitmapToMaxDimension(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largestEdge = maxOf(bitmap.width, bitmap.height)
    if (largestEdge <= maxDimension || maxDimension <= 0) {
        return bitmap
    }
    val scale = maxDimension.toFloat() / largestEdge.toFloat()
    val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
}

private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
    val matrix = Matrix().apply {
        preScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f)
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun compressFormatForMime(mimeType: String?): Bitmap.CompressFormat {
    val normalized = mimeType?.lowercase(Locale.ROOT)
    return when {
        normalized?.contains("png") == true -> Bitmap.CompressFormat.PNG
        normalized?.contains("webp") == true -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                Bitmap.CompressFormat.WEBP
            }
        }

        else -> Bitmap.CompressFormat.JPEG
    }
}

private fun deleteIfExists(file: File) {
    runCatching {
        if (file.exists()) {
            file.delete()
        }
    }
}
