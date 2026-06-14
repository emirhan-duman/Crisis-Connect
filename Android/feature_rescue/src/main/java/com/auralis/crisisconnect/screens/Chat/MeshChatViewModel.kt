package com.auralis.crisisconnect.screens.Chat

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import java.io.File
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.AuthorityMeshChatStore
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.service.gattmesh.GattMeshSendDisposition
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceBinding
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceState
import com.auralis.crisisconnect.service.mesh.AuthorityMeshServiceController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Field-team ("authority") mesh chat, now backed by the role-gated **GATT** authority mesh instead
 * of Wi-Fi Aware. Reuses the shared GATT mesh transport + the dedicated [AuthorityMeshChatStore],
 * which is kept fully separate from the public mesh chat.
 */
class MeshChatViewModel(application: Application) : AndroidViewModel(application) {

    private val meshBinding = GattMeshServiceBinding(
        application.applicationContext,
        AuthorityMeshServiceController
    )

    private val _messageDraft = MutableStateFlow("")
    val messageDraft: StateFlow<String> = _messageDraft.asStateFlow()

    val meshState: StateFlow<GattMeshServiceState> = meshBinding.state
    val messages: StateFlow<List<MeshChatMessage>> = AuthorityMeshChatStore.messages

    private val _sendFailureEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendFailureEvents: SharedFlow<Int> = _sendFailureEvents

    fun onScreenStarted() {
        meshBinding.bind(createIfNeeded = false)
        AuthorityMeshChatStore.setChatOpen(true)
    }

    fun onScreenStopped() {
        AuthorityMeshChatStore.setChatOpen(false)
    }

    fun updateDraft(text: String) {
        _messageDraft.value = text
    }

    fun sendMessage() {
        val content = _messageDraft.value.trim()
        if (content.isBlank()) return

        if (!isSecureMeshChatReady(meshState.value)) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_not_ready)
            return
        }

        val result = meshBinding.sendGroupMessage(content)
        if (result == null) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
            return
        }

        // Keep the packet ID so delivery/read receipts can correlate with this local bubble.
        AuthorityMeshChatStore.appendLocalMessage(
            text = content,
            messageId = result.packetId,
            status = when (result.disposition) {
                GattMeshSendDisposition.QUEUED -> MeshMessageStatus.QUEUED
                GattMeshSendDisposition.SENT -> MeshMessageStatus.SENT
            }
        )
        _messageDraft.value = ""
    }

    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recordingStartedAtElapsedMs = 0L

    fun startVoiceRecording() {
        if (_isRecordingVoice.value) {
            return
        }
        if (!isSecureMeshChatReady(meshState.value)) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_not_ready)
            return
        }
        val context = getApplication<Application>()
        val outputFile = File(context.cacheDir, "mesh_voice_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        runCatching {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setAudioEncodingBitRate(24_000)
            recorder.setAudioSamplingRate(16_000)
            recorder.setMaxDuration(MAX_VOICE_DURATION_MS)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.prepare()
            recorder.start()
        }.onSuccess {
            mediaRecorder = recorder
            recordingFile = outputFile
            recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
            _isRecordingVoice.value = true
        }.onFailure {
            runCatching { recorder.release() }
            outputFile.delete()
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
        }
    }

    fun stopAndSendVoiceRecording() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        val file = recordingFile
        recordingFile = null
        _isRecordingVoice.value = false
        val durationMillis = SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs
        val stopped = runCatching { recorder.stop() }.isSuccess
        runCatching { recorder.release() }
        if (!stopped || file == null || !file.exists() || durationMillis < MIN_VOICE_DURATION_MS) {
            file?.delete()
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
            return
        }
        if (meshBinding.sendVoiceMessage(file, durationMillis) == null) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
        }
    }

    private fun cancelVoiceRecording() {
        val recorder = mediaRecorder ?: return
        mediaRecorder = null
        _isRecordingVoice.value = false
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        recordingFile?.delete()
        recordingFile = null
    }

    fun sendImage(uri: Uri) {
        if (!isSecureMeshChatReady(meshState.value)) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_not_ready)
            return
        }
        // The service prepares/compresses the image, appends the local bubble itself, and streams
        // the encrypted blob to connected peers; failures surface via the bubble status.
        val blobId = meshBinding.sendImageMessage(uri)
        if (blobId == null) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelVoiceRecording()
        AuthorityMeshChatStore.setChatOpen(false)
        meshBinding.unbind()
    }

    companion object {
        private const val MIN_TOTAL_DEVICES_FOR_CHAT = 2
        private const val MAX_VOICE_DURATION_MS = 90_000
        private const val MIN_VOICE_DURATION_MS = 700L

        fun isSecureMeshChatReady(state: GattMeshServiceState): Boolean {
            val totalDevices = state.connectedPeerCount + 1
            return state.isEnabled && totalDevices >= MIN_TOTAL_DEVICES_FOR_CHAT
        }
    }
}
