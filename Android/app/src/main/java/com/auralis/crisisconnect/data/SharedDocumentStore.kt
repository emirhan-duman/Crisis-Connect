package com.auralis.crisisconnect.data

import android.content.Context
import com.auralis.crisisconnect.core.io.normalizeSafeTransferId
import java.io.File
import java.io.FileOutputStream

private const val SHARED_DOCUMENTS_DIR = "shared_documents"

fun sharedDocumentDirectory(context: Context): File {
    val directory = File(context.filesDir, SHARED_DOCUMENTS_DIR)
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return directory
}

fun persistSharedDocumentLocalCopy(
    context: Context,
    uuid: String,
    displayName: String,
    bytes: ByteArray
): String? {
    val safeUuid = normalizeSafeTransferId(uuid) ?: return null
    if (bytes.isEmpty()) {
        return null
    }
    val safeName = sanitizeSharedDocumentName(displayName)
    val target = File(sharedDocumentDirectory(context), "${safeUuid}_$safeName")
    return runCatching {
        FileOutputStream(target, false).use { output ->
            output.write(bytes)
            output.flush()
        }
        target.absolutePath
    }.getOrNull()
}

fun resolveSharedDocumentLocalCopy(context: Context, uuid: String): File? {
    val safeUuid = normalizeSafeTransferId(uuid) ?: return null
    val directory = sharedDocumentDirectory(context)
    val prefix = "${safeUuid}_"
    return directory.listFiles()
        ?.asSequence()
        ?.filter { it.isFile && it.name.startsWith(prefix) }
        ?.maxByOrNull { it.lastModified() }
}

private fun sanitizeSharedDocumentName(rawName: String): String {
    val cleaned = rawName.trim()
        .replace("\\", "_")
        .replace("/", "_")
        .replace(":", "_")
        .replace("*", "_")
        .replace("?", "_")
        .replace("\"", "_")
        .replace("<", "_")
        .replace(">", "_")
        .replace("|", "_")
        .replace(Regex("\\s+"), " ")
    return cleaned.take(180).ifBlank {
        "shared_file_${System.currentTimeMillis()}"
    }
}
