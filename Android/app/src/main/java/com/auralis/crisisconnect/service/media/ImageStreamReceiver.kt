package com.auralis.crisisconnect.service.media

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.core.crypto.AesGcm
import com.auralis.crisisconnect.core.io.Chunker
import com.auralis.crisisconnect.core.io.requireSafeTransferId
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.data.imageMessageDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "ImageStreamReceiver"

class ImageStreamReceiver(
    private val context: Context,
    private val sessionCode: String,
    private val metadata: ImageStreamMetadata,
    private val aesKey: ByteArray?,
    private val scope: CoroutineScope,
    private val frameWriter: suspend (JSONObject) -> Boolean,
    private val onProgress: (ImageTransferProgress) -> Unit,
    private val onCompleted: (Boolean, File?) -> Unit
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
        val baseDir = File(context.cacheDir, ImageStreamConstants.INBOX_DIR).apply { mkdirs() }
        chunkDir = File(baseDir, safeTransferId).apply {
            if (!exists()) mkdirs()
        }
        chunkFiles = Array(metadata.chunkCount) { index -> File(chunkDir, "$index.part") }
        monitorJob = scope.launch { monitorProgress() }
        updateProgress(ImageTransferState.Initializing)
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
            updateProgress(ImageTransferState.Failed)
            return
        }
        val computed = Chunker.crc32(bytes)
        if (computed != crc32) {
            requestResume(listOf(index))
            updateProgress(ImageTransferState.Failed)
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
                updateProgress(ImageTransferState.Failed)
                return
            }
        }
        received[index] = true
        if (index == 0) {
            Log.d(
                TAG,
                "Received first image chunk uuid=${metadata.uuid} session=$sessionCode bytes=${bytes.size}"
            )
        }
        updateProgress(ImageTransferState.Transferring)
        val contiguous = contiguousCount()
        if (contiguous - 1 >= 0 && contiguous - 1 >= lastAckSent + ImageStreamConstants.ACK_INTERVAL - 1) {
            scope.launch { sendAck(force = true) }
        }
    }

    fun handleFinish() {
        if (closed) {
            return
        }
        val missing = missingIndices()
        if (missing.isNotEmpty()) {
            Log.w(TAG, "Image finish requested resume uuid=${metadata.uuid} missing=${missing.size}")
            requestResume(missing)
            updateProgress(ImageTransferState.Waiting)
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
            Log.w(TAG, "Sending image:resume uuid=${metadata.uuid} want=${indices.joinToString(",")}")
            val resume = JSONObject().apply {
                put("type", "image:resume")
                put("uuid", metadata.uuid)
                put("want", indices)
            }
            if (!frameWriter(resume)) {
                Log.w(TAG, "Failed to send image:resume uuid=${metadata.uuid}")
            }
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
            put("type", "image:ack")
            put("uuid", metadata.uuid)
            put("receivedUpTo", contiguous)
            if (force || (missingChanged && currentMissing.isNotEmpty())) {
                put("missing", currentMissing)
            }
        }
        if (frameWriter(ack)) {
            Log.d(
                TAG,
                "Sent image:ack uuid=${metadata.uuid} receivedUpTo=$contiguous " +
                    "missing=${currentMissing.size} force=$force"
            )
            lastAckSent = contiguous
            if (force || missingChanged) {
                lastMissingSent = currentMissingArray
            }
        } else {
            Log.w(TAG, "Failed to send image:ack uuid=${metadata.uuid} receivedUpTo=$contiguous")
        }
    }

    private suspend fun finalizeTransfer() {
        updateProgress(ImageTransferState.Verifying)
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
                updateProgress(ImageTransferState.Failed)
                return
            }
            totalLen += data.size
            md.update(data)
        }
        if (totalLen != metadata.totalBytes) {
            Log.w(
                TAG,
                "Image size mismatch uuid=${metadata.uuid} totalLen=$totalLen expected=${metadata.totalBytes}"
            )
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(ImageTransferState.Failed)
            cleanup()
            return
        }
        val digest = md.digest()
        if (!digest.contentEquals(metadata.sha256)) {
            Log.w(TAG, "Image sha mismatch uuid=${metadata.uuid}")
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(ImageTransferState.Failed)
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
                Log.e(TAG, "Missing AES metadata for encrypted image ${metadata.uuid}")
                sendVerify(false)
                onCompleted(false, null)
                updateProgress(ImageTransferState.Failed)
                cleanup()
                return
            }
            try {
                AesGcm.decryptAesGcm(aesKey, metadata.iv, payload, metadata.aad)
            } catch (throwable: Exception) {
                Log.e(TAG, "AES decrypt failed", throwable)
                sendVerify(false)
                onCompleted(false, null)
                updateProgress(ImageTransferState.Failed)
                cleanup()
                return
            }
        } else {
            payload
        }
        val fileName = ImageFileUtils.fileNameFor(metadata.uuid, metadata.mime)
        val targetDir = imageMessageDirectory(context)
        val targetFile = File(targetDir, fileName)
        try {
            FileOutputStream(targetFile).use { output ->
                output.write(resolvedBytes)
                output.flush()
            }
        } catch (ioException: IOException) {
            Log.e(TAG, "Failed to persist final image", ioException)
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(ImageTransferState.Failed)
            cleanup()
            return
        }
        Log.i(TAG, "Image finalize success uuid=${metadata.uuid} file=${targetFile.name}")
        sendVerify(true)
        onCompleted(true, targetFile)
        cleanup()
    }

    private fun sendVerify(success: Boolean) {
        scope.launch {
            val message = JSONObject().apply {
                put("type", if (success) "image:verify_ok" else "image:verify_fail")
                put("uuid", metadata.uuid)
            }
            if (!frameWriter(message)) {
                Log.w(TAG, "Failed to send image verify result uuid=${metadata.uuid} success=$success")
            }
            if (!success) {
                updateProgress(ImageTransferState.Failed)
            } else {
                updateProgress(ImageTransferState.Completed)
            }
        }
    }

    private suspend fun monitorProgress() {
        while (scope.isActive) {
            sendAck(force = false)
            delay(1_000L)
        }
    }

    private fun updateProgress(state: ImageTransferState) {
        val confirmed = received.count { it }
        val pending = metadata.chunkCount - confirmed
        onProgress(
            ImageTransferProgress(
                sessionCode = sessionCode,
                uuid = metadata.uuid,
                direction = ImageTransferDirection.Download,
                totalChunks = metadata.chunkCount,
                confirmedChunks = confirmed,
                pendingChunks = pending,
                inFlightChunks = 0,
                state = state
            )
        )
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
        updateProgress(ImageTransferState.Failed)
        onCompleted(false, null)
        cleanup()
    }
}
