package com.auralis.crisisconnect.core.media

import android.webkit.MimeTypeMap
import java.util.Locale

object ImageFileUtils {

    fun extensionForMime(mimeType: String?): String {
        val normalized = mimeType?.lowercase(Locale.ROOT) ?: return "jpg"
        return when {
            normalized.endsWith("jpeg") || normalized.endsWith("jpg") -> "jpg"
            normalized.endsWith("png") -> "png"
            normalized.endsWith("webp") -> "webp"
            normalized.endsWith("heic") -> "heic"
            normalized.endsWith("heif") -> "heif"
            else -> MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized) ?: "jpg"
        }
    }

    fun fileNameFor(uuid: String, mimeType: String?): String =
        "$uuid.${extensionForMime(mimeType)}"

    fun thumbnailNameFor(uuid: String, mimeType: String?): String =
        "$uuid-thumb.${preferredThumbnailExtension(mimeType)}"

    private fun preferredThumbnailExtension(mimeType: String?): String {
        val normalized = mimeType?.lowercase(Locale.ROOT) ?: return "jpg"
        return when {
            normalized.endsWith("png") -> "png"
            normalized.endsWith("webp") -> "webp"
            else -> "jpg"
        }
    }
}
