package com.auralis.crisisconnect.service.voice

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
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "VoiceStreamSender"

private data class ChunkState(
    val index: Int,
    val data: ByteArray,
    var attempts: Int = 0,
    var inFlight: Boolean = false,
    var acknowledged: Boolean = false,
    var lastSendAt: Long = 0L
)

/**
 * Send-side state machine for the reliable voice streaming protocol.
 */
class VoiceStreamSender(
    private val context: Context,
    private val sessionCode: String,
    private val uuid: String,
    private val mime: String,
    private val durationMs: Long?,
    private val originalBytes: ByteArray,
    private val aesKey: ByteArray?,
    private val scope: CoroutineScope,
    private val frameWriter: suspend (JSONObject) -> Boolean,
    private val onProgress: (VoiceTransferProgress) -> Unit,
    private val onCompleted: (Boolean) -> Unit
) {

    private val chunkSize: Int
    private val chunkStates: List<ChunkState>
    private val totalChunks: Int
    private val tempDir: File
    private val progress = ConcurrentHashMap<Int, ChunkState>()
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
        chunkSize = VoiceStreamConstants.computeChunkSize(durationMs, expectedBytes)
        val (preparedBytes, iv, aad) = preparePayload(chunkSize)
        contentBytes = preparedBytes
        ivBytes = iv
        aadBytes = aad
        val chunks = Chunker.chunk(contentBytes, chunkSize)
        totalChunks = chunks.size
        chunkStates = chunks.mapIndexed { index, bytes ->
            ChunkState(index = index, data = bytes).also { progress[index] = it }
        }
        sha256Digest = Chunker.sha256(contentBytes)
        tempDir = File(context.cacheDir, VoiceStreamConstants.OUTBOX_DIR).apply { mkdirs() }
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
            mime = mime,
            durationMs = durationMs,
            chunkSize = chunkSize,
            totalBytes = cipherLen,
            chunkCount = cipherChunkCount,
            encrypted = true
        )
        val encrypted = AesGcm.encryptAesGcm(aesKey, originalBytes, aad)
        // Sanity: providers should produce exactly plaintext+16 (no padding). We rely on this;
        // if not, keep going with produced bytes.
        return Triple(encrypted.cipher, encrypted.iv, aad)
    }

    private suspend fun sendInit() {
        updateProgress(VoiceTransferState.Initializing)
        val init = JSONObject().apply {
            put("type", "voice:init")
            put("uuid", uuid)
            put("totalBytes", contentBytes.size)
            put("mime", mime)
            if (durationMs != null) put("durationMs", durationMs) else put("durationMs", JSONObject.NULL)
            put("encrypted", aesKey != null)
            put("chunkSize", chunkSize)
            put("chunkCount", totalChunks)
            if (ivBytes != null) put("ivB64", Base64.encodeToString(ivBytes, Base64.NO_WRAP))
            if (aadBytes != null) put("aadB64", Base64.encodeToString(aadBytes, Base64.NO_WRAP))
            put("sha256B64", Base64.encodeToString(sha256Digest, Base64.NO_WRAP))
        }
        if (!frameWriter(init)) {
            failTransfer("Failed to send VOICE_INIT")
            return
        }
        pumpJob?.cancel()
        pumpJob = scope.launch { pumpChunks() }
    }

    private suspend fun pumpChunks() {
        while (scope.isActive && !finished) {
            val inFlight = chunkStates.count { it.inFlight && !it.acknowledged }
            if (inFlight < VoiceStreamConstants.WINDOW_SIZE) {
                val next = chunkStates.firstOrNull { !it.acknowledged && !it.inFlight }
                if (next != null) {
                    sendChunk(next)
                }
            }
            if (chunkStates.all { it.acknowledged }) {
                sendFinish()
                break
            }
            delay(50L)
        }
    }

    private suspend fun sendChunk(state: ChunkState) {
        if (state.attempts >= VoiceStreamConstants.MAX_RETRY) {
            failTransfer("Chunk ${state.index} exceeded retries")
            return
        }
        val payload = JSONObject().apply {
            put("type", "voice:chunk")
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
        updateProgress(VoiceTransferState.Transferring)
    }

    private suspend fun sendFinish() {
        if (finished) return
        finished = true
        updateProgress(VoiceTransferState.Verifying)
        val finish = JSONObject().apply {
            put("type", "voice:finish")
            put("uuid", uuid)
        }
        if (!frameWriter(finish)) {
            failTransfer("Failed to send VOICE_FINISH")
        }
    }

    /**
     * Process a VOICE_ACK frame from the receiver.
     */
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

    /**
     * React to a VOICE_RESUME frame by resending requested chunks.
     */
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

    /**
     * Handle VOICE_VERIFY_OK / VOICE_VERIFY_FAIL from the peer.
     */
    fun handleVerify(success: Boolean) {
        timeoutJob.cancel()
        if (success) {
            updateProgress(VoiceTransferState.Completed)
        } else {
            updateProgress(VoiceTransferState.Failed)
        }
        onCompleted(success)
        cleanup()
    }

    private fun markChunkAcked(state: ChunkState) {
        if (state.acknowledged) return
        state.acknowledged = true
        state.inFlight = false
        updateProgress(
            if (chunkStates.all { it.acknowledged }) VoiceTransferState.Verifying
            else VoiceTransferState.Transferring
        )
    }

    private suspend fun monitorTimeouts() {
        while (scope.isActive && !finished) {
            val now = System.currentTimeMillis()
            chunkStates.filter { it.inFlight && !it.acknowledged }
                .filter { now - it.lastSendAt >= VoiceStreamConstants.CHUNK_TIMEOUT_MS }
                .forEach { state ->
                    state.inFlight = false
                    scope.launch { sendChunk(state) }
                }
            delay(250L)
        }
    }

    private fun updateProgress(state: VoiceTransferState) {
        val confirmed = chunkStates.count { it.acknowledged }
        val inflight = chunkStates.count { it.inFlight && !it.acknowledged }
        val pending = totalChunks - confirmed - inflight
        onProgress(
            VoiceTransferProgress(
                sessionCode = sessionCode,
                uuid = uuid,
                direction = VoiceTransferDirection.Upload,
                totalChunks = totalChunks,
                confirmedChunks = confirmed,
                pendingChunks = pending,
                inFlightChunks = inflight,
                state = state
            )
        )
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
        val meta = JSONObject().apply {
            put("uuid", uuid)
            put("sessionCode", sessionCode)
            put("mime", mime)
            put("durationMs", durationMs ?: JSONObject.NULL)
            put("encrypted", aesKey != null)
            put("chunkSize", chunkSize)
            put("chunkCount", totalChunks)
        }
        File(tempDir, "${uuid}.json").writeText(meta.toString())
    }

    private fun failTransfer(reason: String) {
        Log.e(TAG, "Transfer $uuid failed: $reason")
        updateProgress(VoiceTransferState.Failed)
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
        val meta = File(tempDir, "${uuid}.json")
        if (meta.exists()) meta.delete()
    }

    /**
     * Abort the transfer, marking it as failed and cleaning up resources.
     */
    fun cancel() {
        if (!finished) {
            failTransfer("cancelled")
        }
    }
}
