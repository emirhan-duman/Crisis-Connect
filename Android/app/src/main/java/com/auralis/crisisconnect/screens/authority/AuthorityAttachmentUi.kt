package com.auralis.crisisconnect.screens.authority

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.ChannelAttachment
import com.auralis.crisisconnect.messaging.PendingChannelAttachment
import com.auralis.crisisconnect.ui.components.AudioMessageCard
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val IMAGE_MAX_DIMENSION = 2048
private const val IMAGE_JPEG_QUALITY = 85
private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

/** Renders one decrypted attachment inside a message bubble: image / voice note / generic file. */
@Composable
fun ChannelAttachmentContent(
    attachment: ChannelAttachment,
    loadBytes: suspend (ChannelAttachment) -> ByteArray?,
    contentColor: Color,
    mine: Boolean,
) {
    when {
        attachment.isImage -> ImageAttachmentContent(attachment, loadBytes)
        attachment.isAudio -> VoiceAttachmentContent(attachment, loadBytes, contentColor, mine)
        else -> FileAttachmentContent(attachment, contentColor)
    }
}

@Composable
private fun ImageAttachmentContent(
    attachment: ChannelAttachment,
    loadBytes: suspend (ChannelAttachment) -> ByteArray?,
) {
    // Bumping retryKey re-runs the load — so a fetch that failed while offline can be retried by tapping
    // once connectivity is back (produceState otherwise keys on att.path only and never retries).
    var retryKey by remember { mutableStateOf(0) }
    val result by produceState<Pair<android.graphics.Bitmap?, Boolean>>(
        initialValue = null to true,
        attachment.path,
        retryKey,
    ) {
        value = null to true
        val bmp = withContext(Dispatchers.IO) {
            loadBytes(attachment)?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
        }
        value = bmp to false
    }
    val (bmp, loading) = result
    val failed = !loading && bmp == null
    val ratio = if (attachment.width != null && attachment.height != null && attachment.height > 0) {
        attachment.width.toFloat() / attachment.height.toFloat()
    } else {
        1f
    }
    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .aspectRatio(ratio.coerceIn(0.5f, 2f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .then(if (failed) Modifier.clickable { retryKey++ } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )
            loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            else -> Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.retry),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun VoiceAttachmentContent(
    attachment: ChannelAttachment,
    loadBytes: suspend (ChannelAttachment) -> ByteArray?,
    contentColor: Color,
    mine: Boolean,
) {
    val context = LocalContext.current
    // Same offline-retry pattern as ImageAttachmentContent: bumping retryKey re-runs a fetch that
    // failed while offline. The decrypted bytes are materialized into a stable cache file so
    // AudioMessageCard (which needs a Uri, and caches waveforms keyed by it) works unchanged.
    var retryKey by remember { mutableStateOf(0) }
    val result by produceState<Pair<File?, Boolean>>(
        initialValue = null to true,
        attachment.path,
        retryKey,
    ) {
        value = null to true
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val f = voicePlaybackFile(context, attachment.path)
                if (f.isFile && f.length() > 0) return@runCatching f
                val bytes = loadBytes(attachment) ?: return@runCatching null
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                f
            }.getOrNull()
        }
        value = file to false
    }
    val (file, loading) = result

    if (file != null) {
        // Exactly ChatScreen's AudioMessageContent: same card, same per-direction color recipe.
        AudioMessageCard(
            uri = Uri.fromFile(file),
            useLiveVisualizer = false,
            modifier = Modifier.fillMaxWidth(),
            initialDurationMillis = attachment.durationSec?.takeIf { it > 0 }?.times(1000L),
            waveBaseColor = contentColor.copy(alpha = 0.3f),
            waveActiveColor = if (mine) {
                MaterialTheme.colorScheme.primary
            } else {
                contentColor.copy(alpha = 0.95f)
            },
            timeTextColor = contentColor.copy(alpha = 0.78f),
            controlContainerColor = contentColor.copy(alpha = if (mine) 0.16f else 0.22f),
            controlContentColor = contentColor,
        )
    } else {
        // ChatScreen's audio-unavailable fallback row; tapping retries the fetch.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (!loading) Modifier.clickable { retryKey++ } else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = contentColor)
            } else {
                Icon(
                    imageVector = Icons.Filled.Mic,
                    contentDescription = null,
                    tint = contentColor.copy(alpha = 0.7f),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.chat_voice_message_label),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                if (!loading) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.chat_voice_unavailable),
                        style = MaterialTheme.typography.labelSmall,
                        color = contentColor.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

/** Stable playback path for a voice blob (decrypted, like the authority_media cache), keyed by Storage path. */
private fun voicePlaybackFile(context: Context, path: String): File {
    val dir = File(context.cacheDir, "authority_voice")
    val hash = MessageDigest.getInstance("SHA-256")
        .digest(path.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return File(dir, "$hash.ogg")
}

@Composable
private fun FileAttachmentContent(
    attachment: ChannelAttachment,
    contentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.widthIn(min = 160.dp, max = 240.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(28.dp),
        )
        Column {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 1,
            )
            if (attachment.size > 0) {
                Text(
                    text = formatBytes(attachment.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f),
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * Turns a picked content Uri into a ready-to-send attachment: images are downscaled to ≤2048px and
 * re-encoded as JPEG (matching the web); other files are read as-is (capped). Returns null on failure
 * or if the file is too large.
 */
suspend fun prepareChannelAttachmentFromUri(context: Context, uri: Uri): PendingChannelAttachment? =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val displayName = queryDisplayName(context, uri) ?: "file"
        val raw = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            ?: return@withContext null
        if (mime.startsWith("image/")) {
            val original = runCatching { BitmapFactory.decodeByteArray(raw, 0, raw.size) }.getOrNull()
                ?: return@withContext null
            val ratio = minOf(1f, IMAGE_MAX_DIMENSION.toFloat() / maxOf(original.width, original.height))
            val width = maxOf(1, (original.width * ratio).toInt())
            val height = maxOf(1, (original.height * ratio).toInt())
            val scaled = android.graphics.Bitmap.createScaledBitmap(original, width, height, true)
            val out = java.io.ByteArrayOutputStream()
            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, out)
            val bytes = out.toByteArray()
            if (bytes.size > MAX_ATTACHMENT_BYTES) return@withContext null
            PendingChannelAttachment(
                bytes = bytes,
                name = displayName.substringBeforeLast('.', displayName) + ".jpg",
                mime = "image/jpeg",
                width = width,
                height = height,
            )
        } else {
            if (raw.size > MAX_ATTACHMENT_BYTES) return@withContext null
            PendingChannelAttachment(bytes = raw, name = displayName, mime = mime)
        }
    }

private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()

/** Wraps recorded Ogg/Opus bytes as a voice-note attachment (mirrors the web's makeVoiceAttachment). */
fun makeVoiceAttachment(bytes: ByteArray, durationSec: Int): PendingChannelAttachment =
    PendingChannelAttachment(
        bytes = bytes,
        name = "voice-${System.currentTimeMillis()}.m4a",
        mime = "audio/mp4",
        durationSec = durationSec.coerceAtLeast(0),
    )
