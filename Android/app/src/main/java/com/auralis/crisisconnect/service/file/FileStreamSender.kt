package com.auralis.crisisconnect.service.file

import android.content.Context
import android.util.Base64
import android.util.Log
import com.auralis.crisisconnect.core.crypto.AesGcm
import com.auralis.crisisconnect.core.crypto.canonicalAad
import com.auralis.crisisconnect.core.io.Chunker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "FileStreamSender"

private data class FileChunkState(
    val index: Int,
    val data: ByteArray,
    var attempts: Int = 0,
    var inFlight: Boolean = false,
    var acknowledged: Boolean = false,
    var lastSendAt: Long = 0L
)

class FileStreamSender(
    private val context: Context,
    private val sessionCode: String,
    private val uuid: String,
    private val displayName: String,
    private val originalSizeBytes: Long,
    private val mime: String?,
    private val compression: String,
    private val originalBytes: ByteArray,
    private val aesKey: ByteArray?,
    private val scope: CoroutineScope,
    private val frameWriter: suspend (JSONObject) -> Boolean,
    private val onCompleted: (Boolean) -> Unit
) {

    private val chunkSize: Int
    private val chunkStates: List<FileChunkState>
    private val totalChunks: Int
    private val tempDir: File
    private val timeoutJob: Job
    private var pumpJob: Job? = null
    private val contentBytes: ByteArray
    private val sha256Digest: ByteArray
    private val ivBytes: ByteArray?
    private val aadBytes: ByteArray?
    private var finished = false

    init {
        val expectedBytes = if (aesKey == null) {
            originalBytes.size
        } else {
            originalBytes.size + 16
        }
        chunkSize = FileStreamConstants.computeChunkSize(expectedBytes)
        val (preparedBytes, iv, aad) = preparePayload(chunkSize)
        contentBytes = preparedBytes
        ivBytes = iv
        aadBytes = aad
        val chunks = Chunker.chunk(contentBytes, chunkSize)
        totalChunks = chunks.size
        chunkStates = chunks.mapIndexed { index, bytes ->
            FileChunkState(index = index, data = bytes)
        }
        sha256Digest = Chunker.sha256(contentBytes)
        tempDir = File(context.cacheDir, FileStreamConstants.OUTBOX_DIR).apply { mkdirs() }
        persistChunks()
        timeoutJob = scope.launch { monitorTimeouts() }
        scope.launch { sendInit() }
    }

    private fun preparePayload(chunkSize: Int): Triple<ByteArray, ByteArray?, ByteArray?> {
        if (aesKey == null) {
            return Triple(originalBytes, null, null)
        }
        val cipherLen = originalBytes.size + 16
        val cipherChunkCount = (cipherLen + chunkSize - 1) / chunkSize
        val aad = canonicalAad(
            uuid = uuid,
            mime = mime ?: "application/octet-stream",
            durationMs = null,
            chunkSize = chunkSize,
            totalBytes = cipherLen,
            chunkCount = cipherChunkCount,
            encrypted = true
        )
        val encrypted = AesGcm.encryptAesGcm(aesKey, originalBytes, aad)
        return Triple(encrypted.cipher, encrypted.iv, aad)
    }

    private suspend fun sendInit() {
        val init = JSONObject().apply {
            put("type", "file:init")
            put("uuid", uuid)
            put("displayName", displayName)
            put("originalSize", originalSizeBytes)
            put("totalBytes", contentBytes.size)
            if (!mime.isNullOrBlank()) put("mime", mime)
            put("compression", compression)
            put("encrypted", aesKey != null)
            put("chunkSize", chunkSize)
            put("chunkCount", totalChunks)
            if (ivBytes != null) put("ivB64", Base64.encodeToString(ivBytes, Base64.NO_WRAP))
            if (aadBytes != null) put("aadB64", Base64.encodeToString(aadBytes, Base64.NO_WRAP))
            put("sha256B64", Base64.encodeToString(sha256Digest, Base64.NO_WRAP))
        }
        if (!frameWriter(init)) {
            failTransfer("Failed to send FILE_INIT")
            return
        }
        pumpJob?.cancel()
        pumpJob = scope.launch { pumpChunks() }
    }

    private suspend fun pumpChunks() {
        while (scope.isActive && !finished) {
            while (chunkStates.count { it.inFlight && !it.acknowledged } < FileStreamConstants.WINDOW_SIZE) {
                val next = chunkStates.firstOrNull { !it.acknowledged && !it.inFlight }
                    ?: break
                sendChunk(next)
            }
            if (chunkStates.all { it.acknowledged }) {
                sendFinish()
                break
            }
            delay(20L)
        }
    }

    private suspend fun sendChunk(state: FileChunkState) {
        if (state.attempts >= FileStreamConstants.MAX_RETRY) {
            failTransfer("Chunk ${state.index} exceeded retries")
            return
        }
        val payload = JSONObject().apply {
            put("type", "file:chunk")
            put("uuid", uuid)
            put("index", state.index)
            put("crc32", Chunker.crc32(state.data))
            put("payloadB64", Base64.encodeToString(state.data, Base64.NO_WRAP))
        }
        val success = frameWriter(payload)
        if (!success) {
            failTransfer("Failed to send chunk ${state.index}")
            return
        }
        state.inFlight = true
        state.attempts += 1
        state.lastSendAt = System.currentTimeMillis()
    }

    private suspend fun sendFinish() {
        if (finished) return
        finished = true
        val finish = JSONObject().apply {
            put("type", "file:finish")
            put("uuid", uuid)
        }
        if (!frameWriter(finish)) {
            failTransfer("Failed to send FILE_FINISH")
        }
    }

    fun handleAck(receivedUpTo: Int, missing: List<Int>) {
        if (receivedUpTo >= 0) {
            for (index in 0..receivedUpTo) {
                chunkStates.getOrNull(index)?.let { markChunkAcked(it) }
            }
        }
        missing.forEach { index ->
            chunkStates.getOrNull(index)?.let { state ->
                if (!state.acknowledged) {
                    state.inFlight = false
                }
            }
        }
    }

    fun handleResume(want: List<Int>) {
        want.forEach { index ->
            chunkStates.getOrNull(index)?.let { state ->
                state.inFlight = false
                state.lastSendAt = 0L
            }
        }
        pumpJob?.cancel()
        pumpJob = scope.launch { pumpChunks() }
    }

    fun handleVerify(success: Boolean) {
        timeoutJob.cancel()
        onCompleted(success)
        cleanup()
    }

    private fun markChunkAcked(state: FileChunkState) {
        if (state.acknowledged) return
        state.acknowledged = true
        state.inFlight = false
    }

    private suspend fun monitorTimeouts() {
        while (scope.isActive && !finished) {
            val now = System.currentTimeMillis()
            chunkStates.filter { it.inFlight && !it.acknowledged }
                .filter { now - it.lastSendAt >= FileStreamConstants.CHUNK_TIMEOUT_MS }
                .forEach { state ->
                    state.inFlight = false
                    scope.launch { sendChunk(state) }
                }
            delay(250L)
        }
    }

    private fun persistChunks() {
        chunkStates.forEach { state ->
            val partFile = File(tempDir, "${uuid}_${state.index}.part")
            if (!partFile.exists()) {
                try {
                    FileOutputStream(partFile).use { output ->
                        output.write(state.data)
                        output.flush()
                    }
                } catch (ioException: IOException) {
                    Log.e(TAG, "Failed to persist chunk ${state.index}", ioException)
                }
            }
        }
    }

    private fun failTransfer(reason: String) {
        Log.e(TAG, "Transfer $uuid failed: $reason")
        onCompleted(false)
        cleanup()
    }

    private fun cleanup() {
        timeoutJob.cancel()
        pumpJob?.cancel()
        chunkStates.forEach { state ->
            val partFile = File(tempDir, "${uuid}_${state.index}.part")
            if (partFile.exists()) partFile.delete()
        }
    }

    fun cancel() {
        if (!finished) {
            failTransfer("cancelled")
        }
    }
}

