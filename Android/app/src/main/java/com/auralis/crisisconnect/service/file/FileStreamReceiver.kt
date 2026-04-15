package com.auralis.crisisconnect.service.file

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.auralis.crisisconnect.core.crypto.AesGcm
import com.auralis.crisisconnect.core.io.Chunker
import com.auralis.crisisconnect.core.io.requireSafeTransferId
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

private const val TAG = "FileStreamReceiver"

class FileStreamReceiver(
    private val context: Context,
    private val metadata: FileStreamMetadata,
    private val aesKey: ByteArray?,
    private val scope: CoroutineScope,
    private val frameWriter: suspend (JSONObject) -> Boolean,
    private val onCompleted: (Boolean) -> Unit
) {

    private val chunkDir: File
    private val received: BooleanArray = BooleanArray(metadata.chunkCount)
    private val chunkFiles: Array<File>
    private var lastAckSent = -1
    private var lastMissingSent: IntArray = intArrayOf()
    @Volatile
    private var closed = false
    private val monitorJob: Job

    init {
        val safeTransferId = requireSafeTransferId(metadata.uuid)
        val baseDir = File(context.cacheDir, FileStreamConstants.INBOX_DIR).apply { mkdirs() }
        chunkDir = File(baseDir, safeTransferId).apply {
            if (!exists()) mkdirs()
        }
        chunkFiles = Array(metadata.chunkCount) { index -> File(chunkDir, "$index.part") }
        monitorJob = scope.launch { monitorProgress() }
    }

    fun handleChunk(index: Int, crc32: Long, bytes: ByteArray) {
        if (closed) {
            return
        }
        if (index !in chunkFiles.indices) {
            return
        }
        if (bytes.size > metadata.chunkSize) {
            requestResume(listOf(index))
            return
        }
        val computed = Chunker.crc32(bytes)
        if (computed != crc32) {
            requestResume(listOf(index))
            return
        }
        val target = chunkFiles[index]
        if (!target.exists()) {
            try {
                FileOutputStream(target).use { output ->
                    output.write(bytes)
                    output.flush()
                }
            } catch (ioException: IOException) {
                Log.e(TAG, "Failed to persist chunk $index", ioException)
                requestResume(listOf(index))
                return
            }
        }
        received[index] = true
        val contiguous = contiguousCount()
        if (contiguous - 1 >= 0 && contiguous - 1 >= lastAckSent + FileStreamConstants.ACK_INTERVAL - 1) {
            scope.launch { sendAck(force = true) }
        }
    }

    fun handleFinish() {
        if (closed) {
            return
        }
        val missing = missingIndices()
        if (missing.isNotEmpty()) {
            requestResume(missing)
            return
        }
        scope.launch { finalizeTransfer() }
    }

    private fun missingIndices(): List<Int> {
        val out = ArrayList<Int>()
        for (index in received.indices) {
            if (!received[index]) out.add(index)
        }
        return out
    }

    private fun contiguousCount(): Int {
        var count = 0
        for (flag in received) {
            if (!flag) break
            count++
        }
        return count
    }

    private fun requestResume(indices: List<Int>) {
        scope.launch {
            val resume = JSONObject().apply {
                put("type", "file:resume")
                put("uuid", metadata.uuid)
                put("want", indices)
            }
            frameWriter(resume)
        }
    }

    private suspend fun sendAck(force: Boolean) {
        val contiguous = contiguousCount() - 1
        val currentMissing = missingIndices()
        val currentMissingArray = currentMissing.toIntArray()
        val missingChanged = !currentMissingArray.contentEquals(lastMissingSent)
        if (!force && contiguous <= lastAckSent && !missingChanged) {
            return
        }
        val ack = JSONObject().apply {
            put("type", "file:ack")
            put("uuid", metadata.uuid)
            put("receivedUpTo", contiguous)
            if (force || (missingChanged && currentMissing.isNotEmpty())) {
                put("missing", currentMissing)
            }
        }
        if (frameWriter(ack)) {
            lastAckSent = contiguous
            if (force || missingChanged) {
                lastMissingSent = currentMissingArray
            }
        }
    }

    private suspend fun finalizeTransfer() {
        val missing = missingIndices()
        if (missing.isNotEmpty()) {
            requestResume(missing)
            return
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        var totalLen = 0
        for (file in chunkFiles) {
            val data = try {
                file.readBytes()
            } catch (ioException: IOException) {
                Log.e(TAG, "Failed to read chunk ${file.name}", ioException)
                requestResume(listOf(file.name.substringBefore('.').toInt()))
                return
            }
            totalLen += data.size
            md.update(data)
        }
        if (totalLen != metadata.totalBytes) {
            sendVerify(false)
            onCompleted(false)
            cleanup()
            return
        }
        val digest = md.digest()
        if (!digest.contentEquals(metadata.sha256)) {
            sendVerify(false)
            onCompleted(false)
            cleanup()
            return
        }
        val payload = ByteArray(totalLen)
        var offset = 0
        for (file in chunkFiles) {
            val data = file.readBytes()
            System.arraycopy(data, 0, payload, offset, data.size)
            offset += data.size
        }
        val resolvedBytes = if (metadata.encrypted) {
            if (aesKey == null || metadata.iv == null || metadata.aad == null) {
                Log.e(TAG, "Missing AES metadata for encrypted file ${metadata.uuid}")
                sendVerify(false)
                onCompleted(false)
                cleanup()
                return
            }
            try {
                AesGcm.decryptAesGcm(aesKey, metadata.iv, payload, metadata.aad)
            } catch (throwable: Exception) {
                Log.e(TAG, "AES decrypt failed", throwable)
                sendVerify(false)
                onCompleted(false)
                cleanup()
                return
            }
        } else {
            payload
        }

        val extractedBytes = if (metadata.compression.equals(FileStreamConstants.COMPRESSION_ZIP, ignoreCase = true)) {
            unzipSingleEntry(
                bytes = resolvedBytes,
                maxOutputBytes = metadata.originalSizeBytes.coerceAtMost(FileStreamConstants.MAX_SOURCE_BYTES)
            ) ?: run {
                Log.e(TAG, "Unable to unzip payload for ${metadata.uuid}")
                sendVerify(false)
                onCompleted(false)
                cleanup()
                return
            }
        } else {
            resolvedBytes
        }
        if (extractedBytes.size.toLong() != metadata.originalSizeBytes
            || extractedBytes.size.toLong() > FileStreamConstants.MAX_SOURCE_BYTES
        ) {
            Log.e(
                TAG,
                "Rejected extracted file for ${metadata.uuid} (expected=${metadata.originalSizeBytes}, actual=${extractedBytes.size})"
            )
            sendVerify(false)
            onCompleted(false)
            cleanup()
            return
        }

        persistSharedDocumentLocalCopy(
            context = context,
            uuid = metadata.uuid,
            displayName = metadata.displayName,
            bytes = extractedBytes
        )

        val persisted = persistToDownloads(
            context = context,
            displayName = metadata.displayName,
            mimeType = metadata.mime,
            bytes = extractedBytes
        )
        sendVerify(persisted)
        onCompleted(persisted)
        cleanup()
    }

    private fun sendVerify(success: Boolean) {
        scope.launch {
            val message = JSONObject().apply {
                put("type", if (success) "file:verify_ok" else "file:verify_fail")
                put("uuid", metadata.uuid)
            }
            frameWriter(message)
        }
    }

    private suspend fun monitorProgress() {
        while (scope.isActive) {
            sendAck(force = false)
            delay(1_000L)
        }
    }

    private fun cleanup() {
        closed = true
        monitorJob.cancel()
        chunkFiles.forEach { file -> if (file.exists()) file.delete() }
        if (chunkDir.exists()) {
            chunkDir.deleteRecursively()
        }
    }

    fun cancel() {
        onCompleted(false)
        cleanup()
    }
}

private fun unzipSingleEntry(bytes: ByteArray, maxOutputBytes: Long): ByteArray? {
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                val declaredSize = entry.size
                if (declaredSize > maxOutputBytes) {
                    return null
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var totalRead = 0L
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) {
                        break
                    }
                    if (read == 0) {
                        continue
                    }
                    totalRead += read
                    if (totalRead > maxOutputBytes) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                return output.toByteArray()
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }
    return null
}

private fun persistToDownloads(
    context: Context,
    displayName: String,
    mimeType: String?,
    bytes: ByteArray
): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        persistWithMediaStore(context, displayName, mimeType, bytes)
    } else {
        persistLegacyDownload(context, displayName, mimeType, bytes)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
private fun persistWithMediaStore(
    context: Context,
    displayName: String,
    mimeType: String?,
    bytes: ByteArray
): Boolean {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, sanitizeDownloadName(displayName))
        put(MediaStore.Downloads.MIME_TYPE, mimeType ?: "application/octet-stream")
        put(MediaStore.Downloads.IS_PENDING, 1)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: throw IOException("Unable to open output stream for Downloads URI")
        val completedValues = ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        resolver.update(uri, completedValues, null, null)
        true
    } catch (throwable: Throwable) {
        Log.e(TAG, "Failed to persist document to MediaStore Downloads", throwable)
        runCatching { resolver.delete(uri, null, null) }
        false
    }
}

@Suppress("DEPRECATION")
private fun persistLegacyDownload(
    context: Context,
    displayName: String,
    mimeType: String?,
    bytes: ByteArray
): Boolean {
    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        ?.apply { mkdirs() }
    val safeName = sanitizeDownloadName(displayName)
    val targetFile = createUniqueFile(downloadsDir, safeName)
        ?: return false
    return try {
        FileOutputStream(targetFile, false).use { output ->
            output.write(bytes)
            output.flush()
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(targetFile.absolutePath),
            arrayOf(mimeType ?: "application/octet-stream"),
            null
        )
        true
    } catch (throwable: Throwable) {
        Log.e(TAG, "Failed to persist legacy Downloads file", throwable)
        false
    }
}

private fun createUniqueFile(directory: File?, fileName: String): File? {
    if (directory == null) return null
    directory.mkdirs()
    val name = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
    var candidate = File(directory, fileName)
    var index = 1
    while (candidate.exists()) {
        val nextName = if (extension.isBlank()) {
            "$name ($index)"
        } else {
            "$name ($index).$extension"
        }
        candidate = File(directory, nextName)
        index++
    }
    return candidate
}

private fun sanitizeDownloadName(rawName: String): String {
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
    if (cleaned.isBlank()) {
        return "shared_file_${System.currentTimeMillis()}"
    }
    return cleaned.take(180)
}
