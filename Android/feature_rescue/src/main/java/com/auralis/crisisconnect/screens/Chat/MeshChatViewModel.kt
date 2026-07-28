package com.auralis.crisisconnect.screens.Chat

import android.app.Application
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.data.AuthorityMeshChatStore
import com.auralis.crisisconnect.data.MeshChatHistoryRestorer
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.data.markAllRemoteMessagesRead
import com.auralis.crisisconnect.core.chat.previewTextForReplyTarget
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.service.gattmesh.MeshProfiles
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.auralis.crisisconnect.service.gattmesh.GattMeshSendDisposition
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceBinding
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceState
import com.auralis.crisisconnect.service.mesh.AuthorityMeshServiceController
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    /** Telsiz (push-to-talk) session state for the authority mesh. */
    val telsizState: StateFlow<com.auralis.crisisconnect.service.gattmesh.ptt.PttSessionState> =
        meshBinding.pttState

    fun joinTelsiz() = meshBinding.joinTelsiz()
    fun leaveTelsiz() = meshBinding.leaveTelsiz()
    fun pttPressTalk() = meshBinding.pttPressTalk()
    fun pttReleaseTalk() = meshBinding.pttReleaseTalk()

    /** Local user's saved name, used to label the "self" row in the connected-users sheet. */
    val localDisplayName: StateFlow<String> = getSavedUserName(application.applicationContext)
        .map { it.trim() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Verified agency from this device's own certificate (the same kurum peers see for us),
     * shown under our name in the connected-users sheet. Null/empty until a v3 cert is provisioned.
     */
    private val _localAgency = MutableStateFlow<String?>(null)
    val localAgency: StateFlow<String?> = _localAgency.asStateFlow()

    init {
        viewModelScope.launch {
            _localAgency.value = runCatching {
                SecurityRepository(application.applicationContext)
                    .getUsableStoredCertificateAgency()
            }.getOrNull()
        }
    }

    private val _sendFailureEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendFailureEvents: SharedFlow<Int> = _sendFailureEvents

    private val appContext = application.applicationContext
    private var hasRestoredHistory = false
    private val sentReadReceiptIds = LinkedHashSet<String>()

    init {
        // While the chat is open, acknowledge incoming messages as READ so the sender's bubble lifts
        // to "read" (same receipt model as the public gatt mesh chat).
        viewModelScope.launch {
            messages.collect { if (AuthorityMeshChatStore.isChatOpen()) sendReadReceiptsForRemote(it) }
        }
    }

    fun onScreenStarted() {
        // createIfNeeded=true so opening the chat actually STARTS the authority mesh runtime. The chat
        // can be opened directly from the main-home "Yetkili Sohbet" card (not via the rescue toggle),
        // and on a fresh process the authority service isn't auto-started — so binding with
        // createIfNeeded=false used to leave the runtime never started (panel showed 0 events / stuck
        // on "yakındaki cihazlar bekleniyor"). The service still self-gates on the enable pref, which
        // is true whenever this entry/screen is reachable.
        meshBinding.bind(createIfNeeded = true)
        AuthorityMeshChatStore.setChatOpen(true)
        restorePersistedMessagesIfNeeded()
        viewModelScope.launch {
            runCatching { markAllRemoteMessagesRead(appContext, AuthorityMeshChatStore.sessionCode) }
            sendReadReceiptsForRemote(AuthorityMeshChatStore.currentMessages())
        }
    }

    /** Restore persisted history once so the authority chat survives an app/process restart. */
    private fun restorePersistedMessagesIfNeeded() {
        if (hasRestoredHistory) return
        hasRestoredHistory = true
        viewModelScope.launch {
            runCatching { MeshChatHistoryRestorer.restore(appContext, AuthorityMeshChatStore) }
        }
    }

    private val _isEnablingMesh = MutableStateFlow(false)
    val isEnablingMesh: StateFlow<Boolean> = _isEnablingMesh.asStateFlow()

    /** Turn the authority mesh on directly from the chat screen when it's currently off. Mirrors
     *  RescueScreenViewModel.setMeshEnabled: ensure the group key, persist the enable flag, start. */
    fun enableAuthorityMesh() {
        if (_isEnablingMesh.value) return
        viewModelScope.launch {
            _isEnablingMesh.value = true
            val hasKey = runCatching {
                SecurityRepository(appContext).ensureAuthorityMeshGroupKey()
            }.getOrDefault(false)
            if (!hasKey) {
                _isEnablingMesh.value = false
                _sendFailureEvents.tryEmit(R.string.rescue_mesh_error_unauthorized)
                return@launch
            }
            runCatching {
                appContext.settingsDataStore.edit { prefs ->
                    prefs[booleanPreferencesKey(MeshProfiles.AUTHORITY.enabledPrefKey)] = true
                }
            }
            meshBinding.setEnabled(true)
            _isEnablingMesh.value = false
        }
    }

    private fun sendReadReceiptsForRemote(currentMessages: List<MeshChatMessage>) {
        val unacked = currentMessages
            .filter { !it.isLocal && it.id !in sentReadReceiptIds }
            .map { it.id }
        if (unacked.isEmpty()) return
        sentReadReceiptIds.addAll(unacked)
        meshBinding.sendReadReceipt(unacked)
    }

    fun onScreenStopped() {
        AuthorityMeshChatStore.setChatOpen(false)
    }

    fun updateDraft(text: String) {
        _messageDraft.value = text
    }

    fun sendMessage(replyTo: MeshChatMessage? = null) {
        val content = _messageDraft.value.trim()
        if (content.isBlank()) return

        // Store-and-forward: allow sending whenever the mesh is ON, even with no peer connected yet.
        // The service queues the packet (QUEUED) and flushes it once a peer is in range.
        if (!meshState.value.isEnabled) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_not_ready)
            return
        }

        val formattedContent = buildReplyFormattedMessage(content, replyTo)
        val result = meshBinding.sendGroupMessage(formattedContent)
        if (result == null) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
            return
        }

        // Keep the packet ID so delivery/read receipts can correlate with this local bubble.
        AuthorityMeshChatStore.appendLocalMessage(
            text = formattedContent,
            messageId = result.packetId,
            status = when (result.disposition) {
                GattMeshSendDisposition.QUEUED -> MeshMessageStatus.QUEUED
                GattMeshSendDisposition.SENT -> MeshMessageStatus.SENT
            }
        )
        _messageDraft.value = ""
    }

    /** Encodes an inline reply into the message body (same `↪[id] author|preview` wire format the
     *  public gatt mesh chat uses), so peers render the quoted reply via [parseReplyMetadata]. */
    private fun buildReplyFormattedMessage(body: String, replyTo: MeshChatMessage?): String {
        val trimmedBody = body.trim()
        val target = replyTo ?: return trimmedBody
        val targetId = target.id.trim().takeIf { it.matches(MESSAGE_ID_REGEX) } ?: return trimmedBody
        val preview = (previewTextForReplyTarget(target.text) ?: target.text.trim())
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(REPLY_PREVIEW_MAX_LENGTH)
            .ifBlank { appContext.getString(R.string.chat_reply_unknown_placeholder) }
        val author = if (target.isLocal) {
            appContext.getString(R.string.chat_reply_sender_you)
        } else {
            target.senderLabel?.trim().orEmpty()
        }
        val heading = if (author.isBlank()) preview else "$author|$preview"
        return "↪[$targetId] $heading\n$trimmedBody"
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
        private const val REPLY_PREVIEW_MAX_LENGTH = 72
        private val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9-]{8,128}$")

        fun isSecureMeshChatReady(state: GattMeshServiceState): Boolean {
            val totalDevices = state.connectedPeerCount + 1
            return state.isEnabled && totalDevices >= MIN_TOTAL_DEVICES_FOR_CHAT
        }
    }
}
