package com.auralis.crisisconnect.screens.authority

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
        else -> FileAttachmentContent(attachment, loadBytes, contentColor)
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
    // Keep decrypted audio in memory only. ChannelAttachments keeps an encrypted blob cache, so
    // materializing a second plaintext playback file here would defeat encryption at rest.
    var retryKey by remember { mutableStateOf(0) }
    val result by produceState<Pair<ByteArray?, Boolean>>(
        initialValue = null to true,
        attachment.path,
        retryKey,
    ) {
        value = null to true
        value = withContext(Dispatchers.IO) { loadBytes(attachment) } to false
    }
    val (bytes, loading) = result

    if (bytes != null) {
        InMemoryVoicePlayer(
            bytes = bytes,
            durationSec = attachment.durationSec,
            modifier = Modifier.fillMaxWidth(),
            contentColor = contentColor,
            accentColor = if (mine) MaterialTheme.colorScheme.primary else contentColor.copy(alpha = 0.95f),
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

@Composable
private fun InMemoryVoicePlayer(
    bytes: ByteArray,
    durationSec: Int?,
    modifier: Modifier,
    contentColor: Color,
    accentColor: Color,
) {
    var player by remember(bytes) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(bytes) { mutableStateOf(false) }
    var pendingPlay by remember(bytes) { mutableStateOf(false) }
    var isPlaying by remember(bytes) { mutableStateOf(false) }

    fun ensurePlayer(): MediaPlayer {
        player?.let { return it }
        val source = object : MediaDataSource() {
            override fun getSize(): Long = bytes.size.toLong()

            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position < 0 || position >= bytes.size) return -1
                val count = minOf(size, bytes.size - position.toInt())
                bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
                return count
            }

            override fun close() = Unit
        }
        return MediaPlayer().also { created ->
            created.setDataSource(source)
            created.setOnPreparedListener {
                prepared = true
                if (pendingPlay) {
                    pendingPlay = false
                    it.start()
                    isPlaying = true
                }
            }
            created.setOnCompletionListener {
                it.seekTo(0)
                isPlaying = false
            }
            created.setOnErrorListener { _, _, _ ->
                prepared = false
                pendingPlay = false
                isPlaying = false
                true
            }
            created.prepareAsync()
            player = created
        }
    }

    DisposableEffect(bytes) {
        onDispose {
            player?.release()
            player = null
            prepared = false
            pendingPlay = false
            isPlaying = false
            bytes.fill(0)
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        androidx.compose.material3.IconButton(
            onClick = {
                val active = ensurePlayer()
                when {
                    isPlaying -> {
                        active.pause()
                        isPlaying = false
                    }
                    prepared -> {
                        active.start()
                        isPlaying = true
                    }
                    else -> pendingPlay = true
                }
            },
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.chat_voice_pause else R.string.chat_voice_play),
                tint = accentColor,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_voice_message_label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = durationSec?.coerceAtLeast(0)?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–:––",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.78f),
            )
        }
    }
}

@Composable
private fun FileAttachmentContent(
    attachment: ChannelAttachment,
    loadBytes: suspend (ChannelAttachment) -> ByteArray?,
    contentColor: Color,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(attachment.path) { mutableStateOf(false) }
    var preview by remember(attachment.path) { mutableStateOf<SafeAuthorityFilePreview?>(null) }
    val canPreview = remember(attachment.mime, attachment.name) { isSafePreviewCandidate(attachment) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .widthIn(min = 160.dp, max = 240.dp)
            .then(if (canPreview && !loading) Modifier.clickable {
                loading = true
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { loadBytes(attachment) }
                    preview = bytes?.let { buildSafeAuthorityPreview(context, attachment, it) }
                    bytes?.fill(0)
                    loading = false
                }
            } else Modifier),
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
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
        }
    }
    preview?.let { value ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(attachment.name, maxLines = 1) },
            text = {
                when (value) {
                    is SafeAuthorityFilePreview.Text -> Text(
                        text = value.value,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                    is SafeAuthorityFilePreview.Pdf -> Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 560.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        value.pages.forEach { page ->
                            Image(
                                bitmap = page.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

private sealed interface SafeAuthorityFilePreview {
    data class Text(val value: String) : SafeAuthorityFilePreview
    data class Pdf(val pages: List<Bitmap>) : SafeAuthorityFilePreview
}

private val SAFE_TEXT_MIMES = setOf(
    "application/json", "application/ld+json", "application/xml", "application/yaml",
)
private val GENERIC_MIMES = setOf("", "application/octet-stream", "binary/octet-stream")
private val SAFE_TEXT_EXTENSIONS = setOf(
    "txt", "text", "log", "md", "markdown", "json", "xml", "yml", "yaml", "ini", "conf", "cfg",
    "csv", "tsv", "js", "ts", "css", "html", "py", "rb", "go", "rs", "java", "kt", "c", "h",
    "cpp", "hpp", "cs", "php", "sh", "zsh", "sql", "toml", "gradle", "properties",
)

private fun isSafePreviewCandidate(attachment: ChannelAttachment): Boolean {
    val mime = attachment.mime.substringBefore(';').trim().lowercase()
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    return (mime == "application/pdf" && extension == "pdf") || mime.startsWith("text/") ||
        mime in SAFE_TEXT_MIMES || (mime in GENERIC_MIMES && extension in SAFE_TEXT_EXTENSIONS)
}

private suspend fun buildSafeAuthorityPreview(
    context: Context,
    attachment: ChannelAttachment,
    bytes: ByteArray,
): SafeAuthorityFilePreview? = withContext(Dispatchers.IO) {
    if (bytes.size.toLong() != attachment.size) return@withContext null
    val mime = attachment.mime.substringBefore(';').trim().lowercase()
    val extension = attachment.name.substringAfterLast('.', "").lowercase()
    if (mime == "application/pdf" && extension == "pdf") {
        if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals(byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d))) {
            return@withContext null
        }
        val temp = File.createTempFile("authority-preview-", ".pdf", context.cacheDir)
        try {
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            temp.setReadable(true, true)
            temp.setWritable(true, true)
            temp.writeBytes(bytes)
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                temp.delete() // the already-open descriptor survives; no plaintext path remains
                PdfRenderer(descriptor).use { renderer ->
                    val pages = mutableListOf<Bitmap>()
                    var pixelBudget = 24_000_000L
                    for (index in 0 until minOf(renderer.pageCount, 100)) {
                        renderer.openPage(index).use { page ->
                            val width = 1_000
                            val height = (width.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                            val pixels = width.toLong() * height
                            if (pixels > pixelBudget) return@use
                            pixelBudget -= pixels
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages += bitmap
                        }
                        if (pixelBudget <= 0) break
                    }
                    if (pages.isEmpty()) null else SafeAuthorityFilePreview.Pdf(pages)
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            temp.delete()
        }
    } else {
        if (bytes.size > 2 * 1024 * 1024 || bytes.take(4096).any { it == 0.toByte() }) return@withContext null
        val declaredText = mime.startsWith("text/") || mime in SAFE_TEXT_MIMES
        val genericText = mime in GENERIC_MIMES && extension in SAFE_TEXT_EXTENSIONS
        if (!declaredText && !genericText) return@withContext null
        val text = runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrNull() ?: return@withContext null
        SafeAuthorityFilePreview.Text(text)
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
