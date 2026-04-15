package com.auralis.crisisconnect.screens.Chat

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.util.Base64
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val FILE_MESSAGE_PREFIX = "CC_FILE:"
const val FILE_COMPRESSION_NONE = "none"
const val FILE_COMPRESSION_ZIP = "zip"

private const val DOCUMENT_COMPRESS_THRESHOLD_BYTES = 512 * 1024
private const val DOCUMENT_MAX_SOURCE_BYTES = 25L * 1024L * 1024L
private const val DOCUMENT_MAX_TRANSFER_BYTES = 15L * 1024L * 1024L

data class PreparedDocumentAttachment(
    val displayName: String,
    val mimeType: String?,
    val originalSizeBytes: Long,
    val transferSizeBytes: Int,
    val compression: String,
    val payloadBytes: ByteArray
)

data class SharedFilePayload(
    val displayName: String,
    val originalSizeBytes: Long,
    val transferSizeBytes: Long,
    val compression: String,
    val mimeType: String?
)

fun prepareDocumentAttachment(
    context: Context,
    messageUuid: String,
    uri: Uri
): PreparedDocumentAttachment? {
    val resolver = context.contentResolver
    val displayName = queryDisplayName(resolver, uri)
        ?.let(::sanitizeDocumentName)
        ?.takeIf { it.isNotBlank() }
        ?: "shared_file_${System.currentTimeMillis()}"
    val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() }

    val originalBytes = resolver.openInputStream(uri)
        ?.use { input -> readBytesWithLimit(input, DOCUMENT_MAX_SOURCE_BYTES) }
        ?: return null
    if (originalBytes.isEmpty()) {
        return null
    }

    val originalSize = originalBytes.size.toLong()
    var transferBytes = originalBytes
    var compression = FILE_COMPRESSION_NONE
    if (originalBytes.size >= DOCUMENT_COMPRESS_THRESHOLD_BYTES) {
        val zipped = zipSingleEntry(displayName, originalBytes)
        val shouldUseZip = zipped.isNotEmpty() &&
            zipped.size < originalBytes.size &&
            zipped.size.toLong() <= DOCUMENT_MAX_TRANSFER_BYTES
        if (shouldUseZip) {
            transferBytes = zipped
            compression = FILE_COMPRESSION_ZIP
        }
    }

    if (transferBytes.size.toLong() > DOCUMENT_MAX_TRANSFER_BYTES) {
        return null
    }

    persistSharedDocumentLocalCopy(
        context = context,
        uuid = messageUuid,
        displayName = displayName,
        bytes = originalBytes
    )

    return PreparedDocumentAttachment(
        displayName = displayName,
        mimeType = mimeType,
        originalSizeBytes = originalSize,
        transferSizeBytes = transferBytes.size,
        compression = compression,
        payloadBytes = transferBytes
    )
}

fun buildSharedFileMessage(payload: PreparedDocumentAttachment): String {
    val nameB64 = Base64.encodeToString(
        payload.displayName.toByteArray(Charsets.UTF_8),
        Base64.NO_WRAP or Base64.URL_SAFE
    )
    val mimeB64 = payload.mimeType
        ?.takeIf { it.isNotBlank() }
        ?.let {
            Base64.encodeToString(
                it.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP or Base64.URL_SAFE
            )
        } ?: "-"
    return buildString {
        append(FILE_MESSAGE_PREFIX)
        append(nameB64)
        append(',')
        append(payload.originalSizeBytes)
        append(',')
        append(payload.transferSizeBytes)
        append(',')
        append(payload.compression)
        append(',')
        append(mimeB64)
    }
}

fun parseSharedFilePayload(text: String): SharedFilePayload? {
    val trimmed = text.trim()
    if (!trimmed.startsWith(FILE_MESSAGE_PREFIX)) {
        return null
    }
    val raw = trimmed.removePrefix(FILE_MESSAGE_PREFIX)
    val parts = raw.split(',', limit = 5)
    if (parts.size < 5) {
        return null
    }
    val displayName = runCatching {
        String(Base64.decode(parts[0], Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val originalSize = parts[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val transferSize = parts[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
    val compression = parts[3].takeIf { it.isNotBlank() } ?: FILE_COMPRESSION_NONE
    val mimeType = parts[4]
        .takeIf { it != "-" && it.isNotBlank() }
        ?.let { encoded ->
            runCatching {
                String(Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
            }.getOrNull()
        }
    return SharedFilePayload(
        displayName = displayName,
        originalSizeBytes = originalSize,
        transferSizeBytes = transferSize,
        compression = compression,
        mimeType = mimeType
    )
}

@Composable
fun FileMessageContent(
    payload: SharedFilePayload,
    contentColor: Color,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val previewBackground = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.08f)
    val previewBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val sizeText = rememberFileSizeText(context, payload.originalSizeBytes)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = previewBackground,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, previewBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.chat_file_preview_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    text = payload.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.9f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.chat_file_tap_to_open),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = sizeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.74f)
                    )
                    val compressionLabel = if (payload.compression.equals(FILE_COMPRESSION_ZIP, true)) {
                        stringResource(R.string.chat_file_compressed_badge)
                    } else {
                        null
                    }
                    compressionLabel?.let {
                        Spacer(modifier = Modifier.size(1.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.74f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberFileSizeText(context: Context, sizeBytes: Long): String {
    return Formatter.formatShortFileSize(context, sizeBytes)
}

private fun zipSingleEntry(entryName: String, bytes: ByteArray): ByteArray {
    return ByteArrayOutputStream().use { byteStream ->
        ZipOutputStream(byteStream).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }
        byteStream.toByteArray()
    }
}

private fun readBytesWithLimit(input: InputStream, maxBytes: Long): ByteArray {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read <= 0) break
        total += read.toLong()
        if (total > maxBytes) {
            return ByteArray(0)
        }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (columnIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(columnIndex)
                } else {
                    null
                }
            }
    }.getOrNull()
}

private fun sanitizeDocumentName(rawName: String): String {
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
