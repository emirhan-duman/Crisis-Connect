package com.auralis.crisisconnect.screens.Chat

import android.content.ContentUris
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.auralis.crisisconnect.data.resolveSharedDocumentLocalCopy

fun openSharedFileFromMessage(
    context: Context,
    messageUuid: String,
    payload: SharedFilePayload
): Boolean {
    val localUri = resolveSharedDocumentLocalCopy(context, messageUuid)
        ?.takeIf { it.exists() && it.isFile }
        ?.let { file ->
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            }.getOrNull()
        }
    val targetUri = localUri ?: findInDownloads(context, payload.displayName) ?: return false
    return launchViewIntent(
        context = context,
        uri = targetUri,
        mimeType = payload.mimeType
    )
}

private fun findInDownloads(context: Context, displayName: String): Uri? {
    if (displayName.isBlank() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return null
    }
    val resolver = context.contentResolver
    val projection = arrayOf(MediaStore.Downloads._ID)
    val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
    val selectionArgs = arrayOf(displayName)
    val sortOrder = "${MediaStore.Downloads.DATE_ADDED} DESC"
    return runCatching {
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(idColumn)
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
            } else {
                null
            }
        }
    }.getOrNull()
}

private fun launchViewIntent(
    context: Context,
    uri: Uri,
    mimeType: String?
): Boolean {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType ?: "*/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: Throwable) {
        false
    }
}
