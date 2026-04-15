package com.auralis.crisisconnect.service.voice

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.core.crypto.AesGcm
import com.auralis.crisisconnect.core.crypto.canonicalAad
import com.auralis.crisisconnect.core.io.Chunker
import com.auralis.crisisconnect.core.io.requireSafeTransferId
import com.auralis.crisisconnect.data.voiceMessageFileName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "VoiceStreamReceiver"

/**
 * Receive-side state machine for the reliable voice streaming protocol.
 */
class VoiceStreamReceiver(
    private val context: Context,
    private val sessionCode: String,
    private val metadata: VoiceStreamMetadata,
    private val aesKey: ByteArray?,
    private val scope: CoroutineScope,
    private val frameWriter: suspend (JSONObject) -> Boolean,
    private val onProgress: (VoiceTransferProgress) -> Unit,
    private val onCompleted: (Boolean, File?) -> Unit
) {

    private val chunkDir: File
    private val received: BooleanArray = BooleanArray(metadata.chunkCount)
    private val chunkFiles: Array<File>
    private var lastAckSent = -1
    private var lastMissingSent: IntArray = intArrayOf()
    private var aborted = false
    private val monitorJob: Job

    init {
        val safeTransferId = requireSafeTransferId(metadata.uuid)
        val baseDir = File(context.cacheDir, VoiceStreamConstants.INBOX_DIR).apply { mkdirs() }
        chunkDir = File(baseDir, safeTransferId).apply {
            if (!exists()) mkdirs()
        }
        chunkFiles = Array(metadata.chunkCount) { index -> File(chunkDir, "$index.part") }
        monitorJob = scope.launch { monitorProgress() }
        updateProgress(VoiceTransferState.Initializing)
        maybeAbortForMissingAesMetadata()
    }

    /**
     * Persist a received VOICE_CHUNK frame.
     */
    fun handleChunk(index: Int, crc32: Long, bytes: ByteArray) {
        if (aborted) {
            return
        }
        if (index !in chunkFiles.indices) {
            return
        }
        if (bytes.size > metadata.chunkSize) {
            requestResume(listOf(index))
            updateProgress(VoiceTransferState.Failed)
            return
        }
        val computed = Chunker.crc32(bytes)
        if (computed != crc32) {
            requestResume(listOf(index))
            updateProgress(VoiceTransferState.Failed)
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
                updateProgress(VoiceTransferState.Failed)
                return
            }
        }
        received[index] = true
        updateProgress(VoiceTransferState.Transferring)
        val contiguous = contiguousCount()
        if (contiguous - 1 >= 0 && contiguous - 1 >= lastAckSent + VoiceStreamConstants.ACK_INTERVAL - 1) {
            sendAck(force = true)
        }
    }

    /**
     * Trigger verification once the sender emits VOICE_FINISH.
     */
    fun handleFinish() {
        if (aborted) {
            return
        }
        val missing = missingIndices()
        if (missing.isNotEmpty()) {
            requestResume(missing)
            updateProgress(VoiceTransferState.Waiting)
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

    private fun maybeAbortForMissingAesMetadata(): Boolean {
        if (!metadata.encrypted || aborted) {
            return aborted
        }
        val keyBytes = aesKey
        val ivBytes = metadata.iv
        val ivLength = ivBytes?.size ?: 0
        val missingKey = keyBytes == null || keyBytes.isEmpty()
        val invalidIv = ivBytes == null || ivBytes.size != 12
        if (missingKey || invalidIv) {
            aborted = true
            Log.e(
                TAG,
                "Missing AES metadata for encrypted voice ${metadata.uuid} (hasKey=${!missingKey}, keyLength=${keyBytes?.size ?: 0}, ivLength=$ivLength)"
            )
            updateProgress(VoiceTransferState.Failed)
            scope.launch {
                sendVerify(false)
                onCompleted(false, null)
            }
            cleanup()
            return true
        }
        return false
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
                put("type", "voice:resume")
                put("uuid", metadata.uuid)
                put("want", indices)
            }
            frameWriter(resume)
        }
    }

    private fun sendAck(force: Boolean = false) {
        scope.launch {
            val contiguous = contiguousCount() - 1
            if (!force && contiguous <= lastAckSent) {
                return@launch
            }
            val currentMissing = missingIndices()
            val currentMissingArray = currentMissing.toIntArray()
            val missingChanged = !currentMissingArray.contentEquals(lastMissingSent)

            val ack = JSONObject().apply {
                put("type", "voice:ack")
                put("uuid", metadata.uuid)
                put("receivedUpTo", contiguous)
                // Only include 'missing' if changed or non-empty or force
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
    }

    private suspend fun finalizeTransfer() {
        updateProgress(VoiceTransferState.Verifying)
        val missing = missingIndices()
        if (missing.isNotEmpty()) {
            requestResume(missing)
            return
        }
        if (maybeAbortForMissingAesMetadata()) {
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
                updateProgress(VoiceTransferState.Failed)
                return
            }
            totalLen += data.size
            md.update(data)
        }
        if (totalLen != metadata.totalBytes) {
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(VoiceTransferState.Failed)
            cleanup()
            return
        }
        val digest = md.digest()
        if (!digest.contentEquals(metadata.sha256)) {
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(VoiceTransferState.Failed)
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
            val resolvedAad = metadata.aad ?: canonicalAad(
                uuid = metadata.uuid,
                mime = metadata.mime,
                durationMs = metadata.durationMs,
                chunkSize = metadata.chunkSize,
                totalBytes = metadata.totalBytes,
                chunkCount = metadata.chunkCount,
                encrypted = metadata.encrypted
            )
            val keyBytes = aesKey!!
            val ivBytes = metadata.iv!!
            try {
                AesGcm.decryptAesGcm(keyBytes, ivBytes, payload, resolvedAad)
            } catch (throwable: Exception) {
                Log.e(TAG, "AES decrypt failed", throwable)
                sendVerify(false)
                onCompleted(false, null)
                updateProgress(VoiceTransferState.Failed)
                cleanup()
                return
            }
        } else {
            payload
        }
        val targetDir = com.auralis.crisisconnect.data.voiceMessageDirectory(context)
        val targetFile = File(targetDir, voiceMessageFileName(metadata.uuid, metadata.mime))
        try {
            FileOutputStream(targetFile).use { output ->
                output.write(resolvedBytes)
                output.flush()
            }
        } catch (ioException: IOException) {
            Log.e(TAG, "Failed to persist final audio", ioException)
            sendVerify(false)
            onCompleted(false, null)
            updateProgress(VoiceTransferState.Failed)
            cleanup()
            return
        }
        sendVerify(true)
        onCompleted(true, targetFile)
        cleanup()
    }

    private fun sendVerify(success: Boolean) {
        scope.launch {
            val message = JSONObject().apply {
                put("type", if (success) "voice:verify_ok" else "voice:verify_fail")
                put("uuid", metadata.uuid)
            }
            frameWriter(message)
            if (!success) {
                updateProgress(VoiceTransferState.Failed)
            } else {
                updateProgress(VoiceTransferState.Completed)
            }
        }
    }

    private suspend fun monitorProgress() {
        while (scope.isActive) {
            sendAck(force = false)
            delay(1_000L)
        }
    }

    private fun updateProgress(state: VoiceTransferState) {
        val confirmed = received.count { it }
        val pending = metadata.chunkCount - confirmed
        onProgress(
            VoiceTransferProgress(
                sessionCode = sessionCode,
                uuid = metadata.uuid,
                direction = VoiceTransferDirection.Download,
                totalChunks = metadata.chunkCount,
                confirmedChunks = confirmed,
                pendingChunks = pending,
                inFlightChunks = 0,
                state = state
            )
        )
    }

    private fun cleanup() {
        aborted = true
        monitorJob.cancel()
        chunkFiles.forEach { file -> if (file.exists()) file.delete() }
        if (chunkDir.exists()) {
            chunkDir.deleteRecursively()
        }
    }

    /**
     * Abort reception and clear temporary state.
     */
    fun cancel() {
        aborted = true
        updateProgress(VoiceTransferState.Failed)
        onCompleted(false, null)
        cleanup()
    }
}
