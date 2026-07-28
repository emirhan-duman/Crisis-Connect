package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.core.media.BLE_IMAGE_TRANSFER_PROFILE
import com.auralis.crisisconnect.core.media.prepareImageAttachmentForTransfer
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.BleChatMessage
import com.auralis.crisisconnect.data.BleChatStore
import com.auralis.crisisconnect.data.BleMessageStatus
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.local.ContactLastSeenStore
import com.auralis.crisisconnect.data.markMessagesAsRead
import com.auralis.crisisconnect.data.observeMessages
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.data.toBleChatMessage
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.service.BleChatEnvelope
import com.auralis.crisisconnect.service.BleFilePayload
import com.auralis.crisisconnect.service.BleFileTransferReceiptStore
import com.auralis.crisisconnect.service.BleImagePayload
import com.auralis.crisisconnect.service.BleImageTransferProgressStore
import com.auralis.crisisconnect.service.BleImageTransferReceiptStore
import com.auralis.crisisconnect.service.BleVoicePayload
import com.auralis.crisisconnect.service.BleVoiceTransferProgressStore
import com.auralis.crisisconnect.service.BleVoiceTransferReceiptStore
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.GattSOSServerService
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.p2p.call.P2pCallController
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.client.RescueClientServiceBinding
import com.auralis.crisisconnect.service.SosServerServiceBinding
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import java.io.File
import java.io.IOException
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import com.google.firebase.crashlytics.FirebaseCrashlytics

class BleChatViewModel(application: Application) : AndroidViewModel(application) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val serviceBinding = RescueClientServiceBinding(application.applicationContext)
    private val sosServiceBinding = SosServerServiceBinding(application.applicationContext)
    private var attemptedAutoSosStart = false

    private val _messages = MutableStateFlow<List<BleChatMessage>>(emptyList())
    val messages: StateFlow<List<BleChatMessage>> = _messages.asStateFlow()

    private val _contactName = MutableStateFlow<String?>(null)
    val contactName: StateFlow<String?> = _contactName.asStateFlow()
    private val _isContactVerified = MutableStateFlow(false)
    val isContactVerified: StateFlow<Boolean> = _isContactVerified.asStateFlow()

    private val _messageDraft = MutableStateFlow("")
    val messageDraft: StateFlow<String> = _messageDraft.asStateFlow()

    private val _connectionState = MutableStateFlow<BleClientManager.ConnectionState?>(null)
    val connectionState: StateFlow<BleClientManager.ConnectionState?> = _connectionState.asStateFlow()

    // BT-side "son görülme": fed by live link transitions and incoming message timestamps, so the
    // header can show when the peer was last provably alive even with no internet presence doc.
    private val _peerLastSeenMillis = MutableStateFlow<Long?>(null)
    val peerLastSeenMillis: StateFlow<Long?> = _peerLastSeenMillis.asStateFlow()

    // Live voice call over the rescue link, scoped to this conversation.
    private val _rescueCall = MutableStateFlow<CallUiState?>(null)
    val rescueCall: StateFlow<CallUiState?> = _rescueCall.asStateFlow()

    private val _serverReady = MutableStateFlow(false)
    val serverReady: StateFlow<Boolean> = _serverReady.asStateFlow()

    private val _isRescueUser = MutableStateFlow(false)
    val isRescueUser: StateFlow<Boolean> = _isRescueUser.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingFilePath = MutableStateFlow<String?>(null)
    val recordingFilePath: StateFlow<String?> = _recordingFilePath.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0L)
    val recordingDuration: StateFlow<Long> = _recordingDuration.asStateFlow()

    private val _isSendingVoice = MutableStateFlow(false)
    val isSendingVoice: StateFlow<Boolean> = _isSendingVoice.asStateFlow()

    private val _voiceTransfers = MutableStateFlow<Map<String, VoiceTransferProgress>>(emptyMap())
    val voiceTransfers: StateFlow<Map<String, VoiceTransferProgress>> = _voiceTransfers.asStateFlow()

    private val _isSendingImage = MutableStateFlow(false)
    val isSendingImage: StateFlow<Boolean> = _isSendingImage.asStateFlow()

    private val _isSendingDocument = MutableStateFlow(false)
    val isSendingDocument: StateFlow<Boolean> = _isSendingDocument.asStateFlow()

    private val _imageTransfers = MutableStateFlow<Map<String, ImageTransferProgress>>(emptyMap())
    val imageTransfers: StateFlow<Map<String, ImageTransferProgress>> = _imageTransfers.asStateFlow()

    private var sessionCode: String? = null
    private var sessionAddress: String? = null
    private var messageJob: Job? = null
    private var contactJob: Job? = null
    private var managerJob: Job? = null
    private var sosLifecycleJob: Job? = null
    private var sosBindingJob: Job? = null
    private var directoryJob: Job? = null
    private var pendingConnectAddress: String? = null
    private var lastRequestedAddress: String? = null
    private var lastReadReceiptRealtime: Long = 0L
    private val readReceiptLock = Any()
    private val pendingReadReceiptIds = LinkedHashSet<String>()
    private var readReceiptRetryJob: Job? = null
    private var readReceiptRetryAtRealtime: Long? = null
    private var flushPendingJob: Job? = null
    private var persistenceJob: Job? = null
    private var retryPendingJob: Job? = null
    private var retryScheduledAtMillis: Long? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingMimeType: String = VOICE_MIME_AAC
    private var recordingStartRealtime: Long = 0L
    private var recordingTimerJob: Job? = null
    private var voiceTransferJob: Job? = null
    private var imageTransferJob: Job? = null

    init {
        resolveLocalRole()
        attachManager()
        observeSosLifecycle()
        observeSosBinding()
        observeVoiceTransferProgress()
        observeImageTransferProgress()
        observeRescueCalls()
    }

    private fun observeRescueCalls() {
        viewModelScope.launch(exceptionHandler) {
            P2pCallController.shared(getApplication<Application>().applicationContext)
                .calls
                .collectLatest { calls ->
                    val code = sessionCode
                    _rescueCall.value = if (code.isNullOrBlank()) {
                        null
                    } else {
                        calls.values.firstOrNull { it.sessionCode.equals(code, ignoreCase = true) }
                    }
                }
        }
    }

    fun startRescueCall() {
        val code = sessionCode ?: return
        val appContext = getApplication<Application>().applicationContext
        // startCall reads the contact row via Room — must never run on the main thread
        // (tapping the call button used to crash with assertNotMainThread here).
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val started = P2pCallController.shared(appContext).startCall(code)
            if (!started) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.ble_call_unavailable),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun acceptRescueCall(callId: String) {
        val code = sessionCode ?: return
        P2pCallController.shared(getApplication<Application>().applicationContext)
            .acceptCall(code, callId)
    }

    fun rejectRescueCall(callId: String) {
        val code = sessionCode ?: return
        P2pCallController.shared(getApplication<Application>().applicationContext)
            .rejectCall(code, callId)
    }

    fun endRescueCall() {
        val code = sessionCode ?: return
        P2pCallController.shared(getApplication<Application>().applicationContext).endCall(code)
    }

    fun setRescueCallMuted(muted: Boolean) {
        val code = sessionCode ?: return
        P2pCallController.shared(getApplication<Application>().applicationContext)
            .setMuted(code, muted)
    }

    fun toggleRescueCallSpeaker() {
        val code = sessionCode ?: return
        P2pCallController.shared(getApplication<Application>().applicationContext)
            .toggleSpeaker(code)
    }

    fun initialize(sessionCode: String) {
        val normalizedCode = BleSessionResolver.normalizeSessionCode(sessionCode) ?: return
        if (this.sessionCode == normalizedCode) {
            return
        }
        this.sessionCode = normalizedCode
        _peerLastSeenMillis.value = ContactLastSeenStore.get(
            getApplication<Application>().applicationContext,
            normalizedCode
        )
        _voiceTransfers.value = emptyMap()
        _imageTransfers.value = emptyMap()
        sessionAddress = BleSessionResolver.addressForSessionCode(normalizedCode)
        _serverReady.value = false
        synchronized(readReceiptLock) {
            pendingReadReceiptIds.clear()
        }
        readReceiptRetryJob?.cancel()
        readReceiptRetryJob = null
        readReceiptRetryAtRealtime = null
        registerSessionAliases()
        updateServerReady()
        ensureChatHostRunning()
        sessionAddress?.let { pendingConnectAddress = it }
        lastRequestedAddress = null
        BleChatStore.ensureSession(normalizedCode)
        observeMessages(normalizedCode)
        observeContactInfo(normalizedCode)
        observePersistedMessages(normalizedCode)
        observeBroadcastDirectory()
        attemptConnection()
    }

    private fun observeMessages(sessionCode: String) {
        messageJob?.cancel()
        messageJob = viewModelScope.launch(exceptionHandler) {
            BleChatStore.observeMessages(sessionCode).collectLatest { list ->
                _messages.value = list
                list.asSequence()
                    .filter { !it.isLocal }
                    .maxOfOrNull { it.timestampMillis }
                    ?.let { newestRemote ->
                        recordPeerAlive(minOf(newestRemote, System.currentTimeMillis()))
                    }
            }
        }
    }

    private fun observePersistedMessages(sessionCode: String) {
        persistenceJob?.cancel()
        val ctx = getApplication<Application>().applicationContext
        persistenceJob = viewModelScope.launch(exceptionHandler) {
            observeMessages(ctx, sessionCode).collectLatest { persisted ->
                val current = BleChatStore.observeMessages(sessionCode).value.associateBy { it.id }
                val mapped = persisted.map { message ->
                    val existing = current[message.messageUuid]
                    val normalizedStatus = when {
                        existing?.isLocal == true -> existing.status
                        message.isLocal -> mapDeliveryStatus(message.deliveryStatus, message.isRead)
                        else -> BleMessageStatus.DELIVERED
                    }
                    message.toBleChatMessage(statusOverride = normalizedStatus).copy(
                        originalTimestampMillis = message.originalTimestampMillis
                            ?: existing?.originalTimestampMillis
                    )
                }
                BleChatStore.replaceMessages(sessionCode, mapped)
                val hasQueuedLocalText = mapped.any { message ->
                    message.isLocal &&
                        message.messageType == MessageType.TEXT &&
                        message.status == BleMessageStatus.QUEUED
                }
                if (hasQueuedLocalText) {
                    sessionAddress?.let { pendingConnectAddress = it }
                    attemptConnection()
                    flushPendingMessages()
                } else {
                    schedulePendingRetry()
                }
            }
        }
    }

    private fun observeContactInfo(sessionCode: String) {
        contactJob?.cancel()
        contactJob = viewModelScope.launch(exceptionHandler) {
            val ctx = getApplication<Application>().applicationContext
            combine(
                BlePeerStore.peers,
                com.auralis.crisisconnect.data.observeContact(ctx, sessionCode)
            ) { peers, storedContact ->
                peers to storedContact
            }.collectLatest { (peers, storedContact) ->
                _isContactVerified.value = storedContact?.verified == true
                val activeAddress = sessionAddress ?: storedContact?.address
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { normalizeAddress(it) }
                    ?.also { recovered ->
                        updateSessionAddress(recovered)
                    }
                    ?: return@collectLatest
                val preferredName = BlePeerIdentityUtils.resolveStableBleContactName(
                    storedName = storedContact?.name,
                    peerName = peers[activeAddress]?.name,
                    sessionCode = sessionCode,
                    addressForFallback = activeAddress
                )
                _contactName.value = BlePeerIdentityUtils.buildBleCounterpartyDisplayName(
                    preferredName = preferredName,
                    addressForFallback = activeAddress,
                    isCurrentUserRescue = _isRescueUser.value,
                    context = ctx
                )
                updateServerReady(peers)
            }
        }
    }

    private fun attachManager() {
        managerJob?.cancel()
        managerJob = viewModelScope.launch(exceptionHandler) {
            serviceBinding.manager.collectLatest { manager ->
                if (manager == null) {
                    _connectionState.value = null
                    lastRequestedAddress = null
                    pendingConnectAddress = sessionAddress
                    return@collectLatest
                }
                val currentAddress = sessionAddress
                if (!currentAddress.isNullOrBlank()) {
                    manager.currentState(currentAddress)?.let { cached ->
                        handleConnectionState(cached)
                    }
                }
                registerSessionAliases(manager = manager)
                attemptConnection()
                manager.connectionStates.collect { state ->
                    handleConnectionState(state)
                }
            }
        }
    }

    private fun observeSosLifecycle() {
        sosLifecycleJob?.cancel()
        sosLifecycleJob = viewModelScope.launch(exceptionHandler) {
            GattSOSServerService.isRunning.collectLatest { running ->
                if (running) {
                    sosServiceBinding.bind()
                    if (_isRescueUser.value) {
                        serviceBinding.bind()
                        attemptConnection()
                    } else {
                        serviceBinding.unbind()
                        _connectionState.value = null
                        lastRequestedAddress = null
                        pendingConnectAddress = sessionAddress
                    }
                } else {
                    sosServiceBinding.unbind()
                    _serverReady.value = false
                    if (_isRescueUser.value) {
                        serviceBinding.bind()
                    } else {
                        serviceBinding.unbind()
                        _connectionState.value = null
                    }
                }
            }
        }
    }

    private fun observeSosBinding() {
        sosBindingJob?.cancel()
        sosBindingJob = viewModelScope.launch(exceptionHandler) {
            sosServiceBinding.service.collectLatest { service ->
                registerSessionAliases(server = service)
                updateServerReady()
            }
        }
    }

    private fun observeVoiceTransferProgress() {
        voiceTransferJob?.cancel()
        voiceTransferJob = viewModelScope.launch(exceptionHandler) {
            BleVoiceTransferProgressStore.progress.collectLatest { transfers ->
                val activeSession = sessionCode
                _voiceTransfers.value = if (activeSession.isNullOrBlank()) {
                    emptyMap()
                } else {
                    transfers.values
                        .filter { transfer -> transfer.sessionCode == activeSession }
                        .associateBy { transfer -> "${transfer.direction.name}:${transfer.uuid}" }
                }
            }
        }
    }

    private fun observeImageTransferProgress() {
        imageTransferJob?.cancel()
        imageTransferJob = viewModelScope.launch(exceptionHandler) {
            BleImageTransferProgressStore.progress.collectLatest { transfers ->
                val activeSession = sessionCode
                _imageTransfers.value = if (activeSession.isNullOrBlank()) {
                    emptyMap()
                } else {
                    transfers.values
                        .filter { transfer -> transfer.sessionCode == activeSession }
                        .associateBy { transfer -> "${transfer.direction.name}:${transfer.uuid}" }
                }
            }
        }
    }

    private fun handleConnectionState(state: BleClientManager.ConnectionState) {
        val currentAddress = sessionAddress ?: return
        if (!addressesMatch(state.address, currentAddress)) {
            return
        }
        Log.d(
            TAG,
            "Connection state update address=${normalizeAddress(state.address)} " +
                "status=${state.status} reason=${state.reason} serverReady=${_serverReady.value}"
        )
        _connectionState.value = state
        if (state.status == BleClientManager.ConnectionStatus.Ready ||
            state.status == BleClientManager.ConnectionStatus.Connected
        ) {
            recordPeerAlive()
        }
        if (state.status == BleClientManager.ConnectionStatus.Ready) {
            flushPendingMessages()
            flushPendingReadReceipts()
        }
    }

    private fun recordPeerAlive(atMillis: Long = System.currentTimeMillis()) {
        val code = sessionCode ?: return
        ContactLastSeenStore.record(getApplication<Application>().applicationContext, code, atMillis)
        if (atMillis > (_peerLastSeenMillis.value ?: 0L)) {
            _peerLastSeenMillis.value = atMillis
        }
    }

    fun updateDraft(text: String) {
        _messageDraft.value = text
    }

    fun onImageSelectionFailed() {
        Toast.makeText(
            getApplication<Application>(),
            getApplication<Application>().getString(R.string.chat_image_file_missing),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun onCameraPermissionDenied() {
        Toast.makeText(
            getApplication<Application>(),
            getApplication<Application>().getString(R.string.chat_camera_permission_required),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun sendMessage(onSent: () -> Unit = {}) {
        val content = _messageDraft.value.trim()
        if (queueOutgoingTextMessage(content = content, messageId = null, clearDraft = true)) {
            onSent()
        }
    }

    fun sendMessage(text: String, onResult: (Boolean) -> Unit) {
        onResult(
            queueOutgoingTextMessage(
                content = text,
                messageId = null,
                clearDraft = false
            )
        )
    }

    private fun queueOutgoingTextMessage(
        content: String,
        messageId: String?,
        clearDraft: Boolean
    ): Boolean {
        val code = sessionCode ?: return false
        val address = sessionAddress ?: return false
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return false
        }
        val resolvedMessageId = messageId ?: UUID.randomUUID().toString()
        BleChatStore.appendLocalMessage(
            sessionCode = code,
            text = trimmed,
            status = BleMessageStatus.QUEUED,
            messageId = resolvedMessageId
        )
        if (clearDraft) {
            _messageDraft.value = ""
        }
        viewModelScope.launch(exceptionHandler) {
            persistOutgoingTextState(
                sessionCode = code,
                messageId = resolvedMessageId,
                text = trimmed,
                status = BleMessageStatus.QUEUED,
                retryCount = 0,
                nextRetryAtMillis = null,
                lastAttemptAtMillis = null,
                lastError = null,
                outboundRoute = null
            )
            pendingConnectAddress = address
            attemptConnection()
            flushPendingMessages()
        }
        Analytics.messageSent(kind = "text", transport = "ble_gatt")
        return true
    }

    fun startVoiceRecording(): Boolean {
        if (_isRecording.value) {
            return true
        }
        if (!hasAudioPermission()) {
            return false
        }
        cleanupRecording(deleteFile = true)
        var started = false
        preferredRecordingProfiles().forEach { profile ->
            if (started) {
                return@forEach
            }
            val outputFile = try {
                File.createTempFile("ble_voice_", profile.fileExtension, getApplication<Application>().cacheDir)
            } catch (_: IOException) {
                null
            } ?: return@forEach
            val recorder = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(getApplication<Application>())
                } else {
                    MediaRecorder()
                }
            } catch (_: Throwable) {
                deleteFileSilently(outputFile)
                return@forEach
            }
            val prepared = runCatching {
                recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(profile.outputFormat)
                recorder.setAudioEncoder(profile.audioEncoder)
                recorder.setAudioEncodingBitRate(profile.bitrate)
                recorder.setAudioSamplingRate(profile.sampleRate)
                recorder.setOutputFile(outputFile.absolutePath)
                recorder.prepare()
                recorder.start()
            }.isSuccess
            if (!prepared) {
                runCatching { recorder.reset() }
                recorder.release()
                deleteFileSilently(outputFile)
                return@forEach
            }
            mediaRecorder = recorder
            currentRecordingFile = outputFile
            currentRecordingMimeType = profile.mimeType
            started = true
        }
        if (!started) {
            return false
        }
        recordingStartRealtime = SystemClock.elapsedRealtime()
        _recordingDuration.value = 0L
        _recordingFilePath.value = null
        _isRecording.value = true
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch(exceptionHandler) {
            while (isActive && _isRecording.value) {
                val duration = SystemClock.elapsedRealtime() - recordingStartRealtime
                _recordingDuration.value = duration
                delay(200L)
            }
        }
        return true
    }

    fun stopVoiceRecording(): Boolean {
        if (!_isRecording.value) {
            return _recordingFilePath.value?.isNotBlank() == true
        }
        val recorder = mediaRecorder
        val file = currentRecordingFile
        if (recorder == null || file == null) {
            cleanupRecording(deleteFile = true)
            return false
        }
        val startRealtime = recordingStartRealtime
        try {
            recorder.stop()
        } catch (_: Exception) {
            runCatching { recorder.reset() }
            recorder.release()
            mediaRecorder = null
            cleanupRecording(deleteFile = true)
            return false
        }
        runCatching { recorder.reset() }
        recorder.release()
        mediaRecorder = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingStartRealtime = 0L
        _isRecording.value = false
        val elapsed = if (startRealtime > 0L) {
            SystemClock.elapsedRealtime() - startRealtime
        } else {
            0L
        }
        _recordingDuration.value = elapsed
        if (!file.exists() || file.length() <= 0L) {
            deleteFileSilently(file)
            currentRecordingFile = null
            _recordingFilePath.value = null
            _recordingDuration.value = 0L
            return false
        }
        _recordingFilePath.value = file.absolutePath
        return true
    }

    fun cancelVoiceRecording() {
        val recorder = mediaRecorder
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            recorder.release()
            mediaRecorder = null
        }
        cleanupRecording(deleteFile = true)
    }

    fun sendRecordedVoice(onResult: (Boolean) -> Unit = {}) {
        if (_isSendingVoice.value) {
            onResult(false)
            return
        }
        if (_isRecording.value && !stopVoiceRecording()) {
            onResult(false)
            return
        }
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        val address = sessionAddress ?: run {
            onResult(false)
            return
        }
        val path = _recordingFilePath.value
        val file = (currentRecordingFile?.takeIf { it.absolutePath == path } ?: path?.let { File(it) })
        if (file == null || !file.exists() || file.length() <= 0L) {
            cleanupRecording(deleteFile = true)
            onResult(false)
            return
        }
        if (file.length() > BleVoicePayload.MAX_OUTGOING_TOTAL_BYTES) {
            cleanupRecording(deleteFile = true)
            onResult(false)
            return
        }
        val duration = _recordingDuration.value.takeIf { it > 0L }
        val mimeType = inferRecordingMimeType(file.absolutePath)
        _isSendingVoice.value = true

        viewModelScope.launch(exceptionHandler) {
            if (!canSendSecurePacket()) {
                pendingConnectAddress = address
                attemptConnection()
            }
            val rawBytes = withContext(Dispatchers.IO) {
                runCatching { file.readBytes() }.getOrNull()
            }
            if (rawBytes == null || rawBytes.isEmpty()) {
                cleanupRecording(deleteFile = true)
                _isSendingVoice.value = false
                onResult(false)
                return@launch
            }
            val transferId = UUID.randomUUID().toString()
            BleVoiceTransferReceiptStore.clear(transferId)
            val packets = BleVoicePayload.buildPackets(
                transferId = transferId,
                mimeType = mimeType,
                durationMillis = duration ?: 0L,
                bytes = rawBytes
            )
            if (packets.isEmpty()) {
                cleanupRecording(deleteFile = true)
                _isSendingVoice.value = false
                onResult(false)
                return@launch
            }
            val totalChunks = (packets.size - 1).coerceAtLeast(1)
            val maxAttempts = MAX_VOICE_SEND_ATTEMPTS
            Log.d(
                TAG,
                "Voice transfer queued id=$transferId chunks=$totalChunks"
            )
            publishOutgoingVoiceProgress(
                sessionCode = code,
                transferId = transferId,
                totalChunks = totalChunks,
                confirmedChunks = 0,
                state = VoiceTransferState.Initializing
            )

            var completed = false
            var abortedByPeer = false
            var queuedChunks = 0
            for (attempt in 1..maxAttempts) {
                val isServerPathSend = _serverReady.value && sosServiceBinding.service.value != null
                val outcomeTimeoutMs = computeVoiceOutcomeTimeoutMs(
                    totalChunks = totalChunks,
                    isServerPath = isServerPathSend
                )
                val sent = sendVoicePackets(address, packets) { sentChunkCount ->
                    queuedChunks = sentChunkCount.coerceAtMost(totalChunks)
                    publishOutgoingVoiceProgress(
                        sessionCode = code,
                        transferId = transferId,
                        totalChunks = totalChunks,
                        confirmedChunks = queuedChunks,
                        state = VoiceTransferState.Transferring
                    )
                }
                if (!sent) {
                    pendingConnectAddress = address
                    attemptConnection()
                    if (attempt < maxAttempts) {
                        delay(VOICE_RETRY_DELAY_MS)
                    }
                    continue
                }
                publishOutgoingVoiceProgress(
                    sessionCode = code,
                    transferId = transferId,
                    totalChunks = totalChunks,
                    confirmedChunks = queuedChunks,
                    state = VoiceTransferState.Waiting
                )
                val outcome = BleVoiceTransferReceiptStore.awaitOutcome(
                    transferId = transferId,
                    timeoutMs = outcomeTimeoutMs
                )
                Log.d(
                    TAG,
                    "Voice transfer outcome id=$transferId attempt=$attempt/$maxAttempts outcome=${outcome?.javaClass?.simpleName ?: "timeout"} queuedChunks=$queuedChunks"
                )
                when (outcome) {
                    is BleVoiceTransferReceiptStore.Outcome.Success -> {
                        completed = true
                    }

                    is BleVoiceTransferReceiptStore.Outcome.Abort -> {
                        abortedByPeer = true
                    }

                    null -> {
                        if (attempt < maxAttempts) {
                            delay(VOICE_RETRY_DELAY_MS)
                        }
                    }
                }
                if (completed || abortedByPeer) {
                    break
                }
            }
            BleVoiceTransferReceiptStore.clear(transferId)
            if (!completed) {
                sendVoiceAbortPacket(address, transferId, "timeout")
                publishOutgoingVoiceProgress(
                    sessionCode = code,
                    transferId = transferId,
                    totalChunks = totalChunks,
                    confirmedChunks = queuedChunks,
                    state = VoiceTransferState.Failed
                )
                clearOutgoingVoiceProgressLater(code, transferId, VOICE_FAILED_BADGE_MS)
                _isSendingVoice.value = false
                if (!abortedByPeer) {
                    pendingConnectAddress = address
                    attemptConnection()
                }
                cleanupRecording(deleteFile = false)
                onResult(false)
                return@launch
            }

            val messageId = UUID.randomUUID().toString()
            val fileName = voiceMessageFileName(messageId, mimeType)
            val destinationPath = withContext(Dispatchers.IO) {
                runCatching {
                    val destination = voiceMessageFile(getApplication<Application>(), fileName)
                    destination.parentFile?.mkdirs()
                    file.copyTo(destination, overwrite = true)
                    saveLocalAudioMessage(
                        getApplication<Application>(),
                        code,
                        messageId,
                        destination.name,
                        duration
                    )
                    destination.absolutePath
                }.getOrNull()
            }
            if (destinationPath.isNullOrBlank()) {
                publishOutgoingVoiceProgress(
                    sessionCode = code,
                    transferId = transferId,
                    totalChunks = totalChunks,
                    confirmedChunks = totalChunks,
                    state = VoiceTransferState.Failed
                )
                clearOutgoingVoiceProgressLater(code, transferId, VOICE_FAILED_BADGE_MS)
                _isSendingVoice.value = false
                onResult(false)
                return@launch
            }

            BleChatStore.appendLocalAudioMessage(
                sessionCode = code,
                audioFilePath = destinationPath,
                audioDurationMillis = duration,
                messageId = messageId
            )
            publishOutgoingVoiceProgress(
                sessionCode = code,
                transferId = transferId,
                totalChunks = totalChunks,
                confirmedChunks = totalChunks,
                state = VoiceTransferState.Completed
            )
            clearOutgoingVoiceProgressLater(code, transferId, VOICE_COMPLETED_BADGE_MS)
            cleanupRecording(deleteFile = true)
            _isSendingVoice.value = false
            onResult(true)
        }
    }

    fun sendImageAttachment(
        uri: Uri,
        mimeType: String?,
        width: Int?,
        height: Int?,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (_isSendingImage.value) {
            onResult(false)
            return
        }
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        val address = sessionAddress ?: run {
            onResult(false)
            return
        }
        _isSendingImage.value = true
        viewModelScope.launch(exceptionHandler) {
            if (!canSendSecurePacket()) {
                pendingConnectAddress = address
                attemptConnection()
            }

            val messageId = UUID.randomUUID().toString()
            val prepared = withContext(Dispatchers.IO) {
                prepareImageAttachmentForTransfer(
                    context = getApplication<Application>().applicationContext,
                    uuid = messageId,
                    uri = uri,
                    mimeType = mimeType,
                    fallbackWidth = width,
                    fallbackHeight = height,
                    profile = BLE_IMAGE_TRANSFER_PROFILE
                )
            }
            if (prepared == null || prepared.bytes.isEmpty()) {
                _isSendingImage.value = false
                onResult(false)
                return@launch
            }

            val appContext = getApplication<Application>().applicationContext
            val imagePath = imageMessageFile(appContext, prepared.fileName).absolutePath
            val thumbnailPath = prepared.thumbnailName?.let { imageThumbnailFile(appContext, it).absolutePath }
            val persisted = runCatching {
                saveLocalImageMessage(
                    context = appContext,
                    sessionCode = code,
                    uuid = messageId,
                    fileName = prepared.fileName,
                    thumbnailName = prepared.thumbnailName,
                    width = prepared.width,
                    height = prepared.height,
                    mimeType = prepared.mimeType,
                    deliveryStatus = MessageDeliveryStatus.SENDING
                )
            }.isSuccess
            if (!persisted) {
                _isSendingImage.value = false
                onResult(false)
                return@launch
            }

            BleChatStore.appendLocalImageMessage(
                sessionCode = code,
                imageFilePath = imagePath,
                imageThumbnailPath = thumbnailPath,
                imageWidth = prepared.width,
                imageHeight = prepared.height,
                imageMimeType = prepared.mimeType,
                status = BleMessageStatus.SENDING,
                messageId = messageId
            )

            val packets = BleImagePayload.buildPackets(
                transferId = messageId,
                messageId = messageId,
                mimeType = prepared.mimeType,
                width = prepared.width,
                height = prepared.height,
                bytes = prepared.bytes
            )
            BleImageTransferReceiptStore.clear(messageId)
            if (packets.isEmpty()) {
                markLocalImageDeliveryState(
                    sessionCode = code,
                    messageId = messageId,
                    status = BleMessageStatus.FAILED,
                    lastError = IMAGE_ERROR_PAYLOAD_TOO_LARGE
                )
                _isSendingImage.value = false
                onResult(false)
                return@launch
            }

            val totalChunks = (packets.size - 1).coerceAtLeast(1)
            publishOutgoingImageProgress(
                sessionCode = code,
                transferId = messageId,
                totalChunks = totalChunks,
                confirmedChunks = 0,
                state = ImageTransferState.Initializing
            )

            var completed = false
            var abortedByPeer = false
            var queuedChunks = 0
            var lastOutboundRoute: String? = null
            for (attempt in 1..MAX_IMAGE_SEND_ATTEMPTS) {
                val isServerPathSend = _serverReady.value && sosServiceBinding.service.value != null
                lastOutboundRoute = if (isServerPathSend) {
                    TextRoute.Server.transportName
                } else {
                    TextRoute.Client.transportName
                }
                val sent = sendImagePackets(address, packets) { sentChunkCount ->
                    queuedChunks = sentChunkCount.coerceAtMost(totalChunks)
                    publishOutgoingImageProgress(
                        sessionCode = code,
                        transferId = messageId,
                        totalChunks = totalChunks,
                        confirmedChunks = queuedChunks,
                        state = ImageTransferState.Transferring
                    )
                }
                if (!sent) {
                    pendingConnectAddress = address
                    attemptConnection()
                    if (attempt < MAX_IMAGE_SEND_ATTEMPTS) {
                        delay(IMAGE_RETRY_DELAY_MS)
                    }
                    continue
                }
                publishOutgoingImageProgress(
                    sessionCode = code,
                    transferId = messageId,
                    totalChunks = totalChunks,
                    confirmedChunks = queuedChunks,
                    state = ImageTransferState.Waiting
                )
                when (
                    BleImageTransferReceiptStore.awaitOutcome(
                        transferId = messageId,
                        timeoutMs = computeImageOutcomeTimeoutMs(
                            totalChunks = totalChunks,
                            isServerPath = isServerPathSend
                        )
                    )
                ) {
                    is BleImageTransferReceiptStore.Outcome.Success -> completed = true
                    is BleImageTransferReceiptStore.Outcome.Abort -> abortedByPeer = true
                    null -> if (attempt < MAX_IMAGE_SEND_ATTEMPTS) delay(IMAGE_RETRY_DELAY_MS)
                }
                if (completed || abortedByPeer) {
                    break
                }
            }
            BleImageTransferReceiptStore.clear(messageId)

            if (!completed) {
                if (!abortedByPeer) {
                    sendImageAbortPacket(address, messageId, "timeout")
                    pendingConnectAddress = address
                    attemptConnection()
                }
                publishOutgoingImageProgress(
                    sessionCode = code,
                    transferId = messageId,
                    totalChunks = totalChunks,
                    confirmedChunks = queuedChunks,
                    state = ImageTransferState.Failed
                )
                clearOutgoingImageProgressLater(code, messageId, IMAGE_FAILED_BADGE_MS)
                markLocalImageDeliveryState(
                    sessionCode = code,
                    messageId = messageId,
                    status = BleMessageStatus.FAILED,
                    lastError = IMAGE_ERROR_SEND_FAILED,
                    outboundRoute = lastOutboundRoute
                )
                _isSendingImage.value = false
                onResult(false)
                return@launch
            }

            publishOutgoingImageProgress(
                sessionCode = code,
                transferId = messageId,
                totalChunks = totalChunks,
                confirmedChunks = totalChunks,
                state = ImageTransferState.Completed
            )
            clearOutgoingImageProgressLater(code, messageId, IMAGE_COMPLETED_BADGE_MS)
            markLocalImageDeliveryState(
                sessionCode = code,
                messageId = messageId,
                status = BleMessageStatus.SENT,
                lastError = null,
                outboundRoute = lastOutboundRoute
            )
            _isSendingImage.value = false
            onResult(true)
        }
    }

    fun sendDocumentAttachment(
        uri: Uri,
        onResult: (Boolean) -> Unit = {}
    ) {
        if (_isSendingDocument.value) {
            onResult(false)
            return
        }
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        val address = sessionAddress ?: run {
            onResult(false)
            return
        }
        _isSendingDocument.value = true
        viewModelScope.launch(exceptionHandler) {
            if (!canSendSecurePacket()) {
                pendingConnectAddress = address
                attemptConnection()
            }

            val messageId = UUID.randomUUID().toString()
            val prepared = withContext(Dispatchers.IO) {
                prepareDocumentAttachment(
                    context = getApplication<Application>().applicationContext,
                    messageUuid = messageId,
                    uri = uri
                )
            }
            if (
                prepared == null ||
                prepared.payloadBytes.isEmpty() ||
                prepared.transferSizeBytes > BleFilePayload.MAX_OUTGOING_TOTAL_BYTES ||
                !prepared.compression.equals(FILE_COMPRESSION_NONE, ignoreCase = true)
            ) {
                _isSendingDocument.value = false
                onResult(false)
                return@launch
            }

            val packets = BleFilePayload.buildPackets(
                transferId = messageId,
                messageId = messageId,
                displayName = prepared.displayName,
                mimeType = prepared.mimeType,
                originalSizeBytes = prepared.originalSizeBytes,
                bytes = prepared.payloadBytes
            )
            BleFileTransferReceiptStore.clear(messageId)
            if (packets.isEmpty()) {
                _isSendingDocument.value = false
                onResult(false)
                return@launch
            }

            var completed = false
            var abortedByPeer = false
            for (attempt in 1..MAX_FILE_SEND_ATTEMPTS) {
                val sent = sendFilePackets(address, packets)
                if (!sent) {
                    pendingConnectAddress = address
                    attemptConnection()
                    if (attempt < MAX_FILE_SEND_ATTEMPTS) {
                        delay(FILE_RETRY_DELAY_MS)
                    }
                    continue
                }
                when (
                    BleFileTransferReceiptStore.awaitOutcome(
                        transferId = messageId,
                        timeoutMs = FILE_ACK_TIMEOUT_MS
                    )
                ) {
                    is BleFileTransferReceiptStore.Outcome.Success -> completed = true
                    is BleFileTransferReceiptStore.Outcome.Abort -> abortedByPeer = true
                    null -> if (attempt < MAX_FILE_SEND_ATTEMPTS) delay(FILE_RETRY_DELAY_MS)
                }
                if (completed || abortedByPeer) {
                    break
                }
            }
            BleFileTransferReceiptStore.clear(messageId)

            if (!completed) {
                if (!abortedByPeer) {
                    sendFileAbortPacket(address, messageId, "timeout")
                    pendingConnectAddress = address
                    attemptConnection()
                }
                _isSendingDocument.value = false
                onResult(false)
                return@launch
            }

            val metadataMessage = buildSharedFileMessage(prepared)
            val queued = queueOutgoingTextMessage(
                content = metadataMessage,
                messageId = messageId,
                clearDraft = false
            )
            _isSendingDocument.value = false
            onResult(queued)
        }
    }

    fun acknowledgeRemoteMessages() {
        val remoteMessages = _messages.value
            .asSequence()
            .filter { message -> !message.isLocal && message.status != BleMessageStatus.READ }
            .filter { message -> message.id.isNotBlank() }
            .sortedByDescending { message -> message.timestampMillis }
            .toList()
        if (remoteMessages.isEmpty()) {
            return
        }
        val messageIds = remoteMessages.map { it.id }
        // Keep UI unread counters responsive even when transport is temporarily unavailable.
        markRemoteMessagesAsRead(messageIds)
        queuePendingReadReceiptIds(messageIds)
        flushPendingReadReceipts()
    }

    private fun queuePendingReadReceiptIds(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) {
            return
        }
        synchronized(readReceiptLock) {
            messageIds.forEach { rawId ->
                val id = rawId.trim()
                if (id.isNotEmpty()) {
                    pendingReadReceiptIds += id
                }
            }
            while (pendingReadReceiptIds.size > MAX_PENDING_READ_RECEIPTS) {
                val oldest = pendingReadReceiptIds.firstOrNull() ?: break
                pendingReadReceiptIds.remove(oldest)
            }
        }
    }

    private fun flushPendingReadReceipts() {
        val address = sessionAddress ?: return
        val normalizedAddress = normalizeAddress(address)
        val pendingSnapshot = synchronized(readReceiptLock) {
            pendingReadReceiptIds.toList()
        }
        if (pendingSnapshot.isEmpty()) {
            return
        }
        val nowRealtime = SystemClock.elapsedRealtime()
        val elapsedSinceLastSend = nowRealtime - lastReadReceiptRealtime
        if (elapsedSinceLastSend < READ_RECEIPT_MIN_SEND_INTERVAL_MS) {
            scheduleReadReceiptRetry(
                delayMs = READ_RECEIPT_MIN_SEND_INTERVAL_MS - elapsedSinceLastSend
            )
            return
        }

        val batchIds = resolveReadReceiptBatchIds(pendingSnapshot)
        if (batchIds.isEmpty()) {
            return
        }

        val service = sosServiceBinding.service.value
        val manager = serviceBinding.manager.value
        val serverPathReady = currentServerPathReady(normalizedAddress)
        val clientPathReady =
            _connectionState.value?.status == BleClientManager.ConnectionStatus.Ready &&
                manager != null
        if (!serverPathReady && !clientPathReady) {
            ensureChatHostRunning()
            pendingConnectAddress = address
            attemptConnection()
            scheduleReadReceiptRetry(delayMs = READ_RECEIPT_RETRY_DELAY_MS)
            return
        }

        viewModelScope.launch(exceptionHandler) {
            var sent = false
            if (serverPathReady) {
                sent = service?.sendReadReceipt(normalizedAddress, batchIds) == true
            }
            if (!sent && clientPathReady) {
                sent = manager?.sendReadReceiptAwait(normalizedAddress, batchIds) == true
            }
            if (!sent) {
                ensureChatHostRunning()
                pendingConnectAddress = normalizedAddress
                attemptConnection()
                scheduleReadReceiptRetry(delayMs = READ_RECEIPT_RETRY_DELAY_MS)
                return@launch
            }
            lastReadReceiptRealtime = SystemClock.elapsedRealtime()
            synchronized(readReceiptLock) {
                pendingReadReceiptIds.removeAll(batchIds.toSet())
            }
            markRemoteMessagesAsRead(batchIds)
            val hasMorePending = synchronized(readReceiptLock) {
                pendingReadReceiptIds.isNotEmpty()
            }
            if (hasMorePending) {
                scheduleReadReceiptRetry(delayMs = READ_RECEIPT_MIN_SEND_INTERVAL_MS)
            }
        }
    }

    private fun resolveReadReceiptBatchIds(candidateIds: List<String>): List<String> {
        if (candidateIds.isEmpty()) {
            return emptyList()
        }
        val normalized = candidateIds
            .asSequence()
            .map { id -> id.trim() }
            .filter { id -> id.isNotEmpty() }
            .distinct()
            .take(MAX_PENDING_READ_RECEIPTS)
            .toList()
        if (normalized.isEmpty()) {
            return emptyList()
        }
        val encoded = BleChatEnvelope.encodeReadAck(normalized)
        val decoded = BleChatEnvelope.decodeAck(encoded)
        if (decoded?.type == BleChatEnvelope.AckType.READ && decoded.messageIds.isNotEmpty()) {
            return decoded.messageIds
        }
        return listOf(normalized.first())
    }

    private fun scheduleReadReceiptRetry(delayMs: Long) {
        val safeDelay = delayMs.coerceAtLeast(READ_RECEIPT_RETRY_MIN_DELAY_MS)
        val scheduledAt = SystemClock.elapsedRealtime() + safeDelay
        val existingAt = readReceiptRetryAtRealtime
        if (
            readReceiptRetryJob?.isActive == true &&
            existingAt != null &&
            kotlin.math.abs(existingAt - scheduledAt) <= READ_RECEIPT_RETRY_COALESCE_WINDOW_MS
        ) {
            return
        }
        readReceiptRetryJob?.cancel()
        readReceiptRetryAtRealtime = scheduledAt
        readReceiptRetryJob = viewModelScope.launch(exceptionHandler) {
            delay(safeDelay)
            readReceiptRetryAtRealtime = null
            flushPendingReadReceipts()
        }
    }

    private fun markRemoteMessagesAsRead(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) {
            return
        }
        val ctx = getApplication<Application>().applicationContext
        val ids = messageIds
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        if (ids.isEmpty()) {
            return
        }
        viewModelScope.launch(exceptionHandler) {
            markMessagesAsRead(ctx, ids)
        }
    }

    private fun attemptConnection() {
        val address = pendingConnectAddress ?: return
        val manager = serviceBinding.manager.value ?: return
        registerSessionAliases(manager = manager)
        manager.currentState(address)?.let { cached ->
            handleConnectionState(cached)
            if (cached.status == BleClientManager.ConnectionStatus.Ready) {
                pendingConnectAddress = null
                return
            }
        }
        if (lastRequestedAddress != address) {
            manager.connectTo(address)
            lastRequestedAddress = address
        }
        pendingConnectAddress = null
    }

    /**
     * Brings up the shared GATT server so a legacy BLE chat has a transport — WITHOUT declaring an
     * emergency.
     *
     * The old name and the old string ("SOS started to enable messaging") record what this was always
     * meant to be: transport bring-up. But the service conflated hosting with declaring, so opening a
     * chat reported a live emergency to the agency dashboard and auto-messaged the user's contacts.
     * Starting without EXTRA_USER_DECLARED hosts the server and tells nobody anything; only the SOS
     * button declares. The toast is gone with the declaration it used to announce — saying "SOS
     * started" when no SOS is being sent would be worse than silence.
     *
     * The rescue-role gate is kept exactly as it was, purely to avoid changing behaviour that nobody
     * reported as broken.
     */
    private fun ensureChatHostRunning() {
        if (attemptedAutoSosStart) {
            return
        }
        val ctx = getApplication<Application>().applicationContext
        val role = loadSavedRole(ctx)
        if (role != null && role in RESCUE_ROLES) {
            return
        }
        if (GattSOSServerService.isRunning.value) {
            return
        }
        attemptedAutoSosStart = true
        runCatching {
            val intent = Intent(ctx, GattSOSServerService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to start the BLE chat GATT host", throwable)
        }
    }

    private fun resolveLocalRole() {
        val ctx = getApplication<Application>().applicationContext
        val role = loadSavedRole(ctx)
        _isRescueUser.value = role != null && role in RESCUE_ROLES
    }

    private fun loadSavedRole(ctx: Context): String? {
        return LocalKeyStorage.getSavedRole(ctx)
            ?.lowercase(Locale.US)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun addressesMatch(lhs: String, rhs: String): Boolean {
        return normalizeAddress(lhs) == normalizeAddress(rhs)
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    private fun observeBroadcastDirectory() {
        directoryJob?.cancel()
        directoryJob = viewModelScope.launch(exceptionHandler) {
            BleBroadcastDirectory.entries.collectLatest {
                val code = sessionCode ?: return@collectLatest
                val resolved = BleSessionResolver.addressForSessionCode(code) ?: return@collectLatest
                updateSessionAddress(resolved)
            }
        }
    }

    private fun updateSessionAddress(address: String) {
        val normalized = normalizeAddress(address)
        if (sessionAddress == normalized) {
            return
        }
        sessionAddress = normalized
        pendingConnectAddress = normalized
        lastRequestedAddress = null
        updateServerReady()
        registerSessionAliases()
        val code = sessionCode
        if (code != null) {
            val ctx = getApplication<Application>().applicationContext
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                updateContactAddress(ctx, code, normalized)
            }
        }
        attemptConnection()
    }

    private fun registerSessionAliases(
        manager: BleClientManager? = serviceBinding.manager.value,
        server: GattSOSServerService? = sosServiceBinding.service.value
    ) {
        server?.registerSessionAlias(
            addressUpper = sessionAddress?.trim().orEmpty(),
            sessionCode = sessionCode?.trim().orEmpty()
        )
        registerClientSessionAlias(manager)
    }

    private fun registerClientSessionAlias(manager: BleClientManager?) {
        val targetManager = manager ?: return
        val code = sessionCode?.trim().orEmpty()
        val address = sessionAddress?.trim().orEmpty()
        if (code.isBlank() || address.isBlank()) {
            return
        }
        targetManager.registerSessionAlias(address, code)
    }

    private fun updateServerReady(peers: Map<String, Contact>? = null) {
        val address = sessionAddress ?: run {
            _serverReady.value = false
            return
        }
        val normalizedAddress = normalizeAddress(address)
        val snapshot = peers ?: BlePeerStore.peers.value
        val hasPeer = snapshot.containsKey(normalizedAddress)
        val serverPathReady = currentServerPathReady(normalizedAddress)
        if (_serverReady.value != serverPathReady) {
            Log.d(
                TAG,
                "Server path ready changed address=$normalizedAddress ready=$serverPathReady hasPeer=$hasPeer"
            )
        }
        _serverReady.value = serverPathReady
        if (_serverReady.value) {
            flushPendingMessages()
            flushPendingReadReceipts()
        }
    }

    private fun currentServerPathReady(address: String): Boolean {
        val normalizedAddress = normalizeAddress(address)
        val service = sosServiceBinding.service.value ?: return false
        return service.isChatReady(normalizedAddress)
    }

    private fun flushPendingMessages() {
        val code = sessionCode ?: return
        val address = sessionAddress ?: return
        if (flushPendingJob?.isActive == true) {
            return
        }
        flushPendingJob = viewModelScope.launch(exceptionHandler) {
            val now = System.currentTimeMillis()
            val queuedMessages = BleChatStore.getQueuedLocalMessages(code, nowMillis = now)
            if (queuedMessages.isEmpty()) {
                schedulePendingRetry()
                return@launch
            }
            for (message in queuedMessages) {
                if (message.messageType != MessageType.TEXT || message.text.isBlank()) {
                    continue
                }
                val route = resolveTextRoute()
                if (route == null) {
                    val retryAt = System.currentTimeMillis() + TEXT_TRANSPORT_WAIT_RETRY_DELAY_MS
                    queuedMessages.forEach { queued ->
                        updateOutgoingTextState(
                            sessionCode = code,
                            messageId = queued.id,
                            text = queued.text,
                            status = BleMessageStatus.QUEUED,
                            retryCount = queued.retryCount,
                            nextRetryAtMillis = retryAt,
                            lastAttemptAtMillis = null,
                            lastError = "TRANSPORT_UNAVAILABLE",
                            outboundRoute = queued.outboundRoute
                        )
                    }
                    ensureChatHostRunning()
                    pendingConnectAddress = address
                    attemptConnection()
                    break
                }
                val attempt = (message.retryCount + 1).coerceAtLeast(1)
                val lastAttemptAt = System.currentTimeMillis()
                updateOutgoingTextState(
                    sessionCode = code,
                    messageId = message.id,
                    text = message.text,
                    status = BleMessageStatus.SENDING,
                    retryCount = message.retryCount,
                    nextRetryAtMillis = null,
                    lastAttemptAtMillis = lastAttemptAt,
                    lastError = null,
                    outboundRoute = route.transportName
                )
                val payload = BleChatEnvelope.encodeChat(
                    messageId = message.id,
                    text = message.text,
                    createdAtMillis = message.timestampMillis,
                    ttlMillis = TEXT_MESSAGE_TTL_MS,
                    attempt = attempt,
                    route = route.transportName
                )
                val sent = when (route) {
                    TextRoute.Server -> {
                        val service = sosServiceBinding.service.value
                        _serverReady.value && service != null && service.sendChatMessage(address, payload)
                    }

                    TextRoute.Client -> {
                        val manager = serviceBinding.manager.value
                        val ready =
                            _connectionState.value?.status == BleClientManager.ConnectionStatus.Ready &&
                                manager != null
                        ready && runCatching { manager!!.sendMessageAwait(address, payload) }.getOrElse { false }
                    }
                }
                if (sent) {
                    updateOutgoingTextState(
                        sessionCode = code,
                        messageId = message.id,
                        text = message.text,
                        status = BleMessageStatus.SENT,
                        retryCount = attempt,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = lastAttemptAt,
                        lastError = null,
                        outboundRoute = route.transportName
                    )
                    continue
                }
                val exhausted = attempt >= MAX_TEXT_SEND_ATTEMPTS
                val nextRetryAt = if (exhausted) {
                    null
                } else {
                    computeNextRetryAtMillis(attempt)
                }
                val failedStatus = if (exhausted) BleMessageStatus.FAILED else BleMessageStatus.QUEUED
                updateOutgoingTextState(
                    sessionCode = code,
                    messageId = message.id,
                    text = message.text,
                    status = failedStatus,
                    retryCount = attempt,
                    nextRetryAtMillis = nextRetryAt,
                    lastAttemptAtMillis = lastAttemptAt,
                    lastError = "SEND_FAILED",
                    outboundRoute = route.transportName
                )
                if (!exhausted) {
                    pendingConnectAddress = address
                    attemptConnection()
                }
            }
            schedulePendingRetry()
        }
    }

    private fun schedulePendingRetry() {
        val code = sessionCode ?: return
        val now = System.currentTimeMillis()
        val nextRetryAt = BleChatStore.currentMessages(code)
            .asSequence()
            .filter { message ->
                message.isLocal &&
                    message.messageType == MessageType.TEXT &&
                    message.text.isNotBlank() &&
                    message.status == BleMessageStatus.QUEUED
            }
            .map { it.nextRetryAtMillis ?: now }
            .minOrNull() ?: run {
            retryPendingJob?.cancel()
            retryPendingJob = null
            retryScheduledAtMillis = null
            return
        }
        val delayMs = (nextRetryAt - now).coerceAtLeast(TEXT_RETRY_MIN_DELAY_MS)
        val scheduledAt = now + delayMs
        val existingAt = retryScheduledAtMillis
        if (retryPendingJob?.isActive == true && existingAt != null && kotlin.math.abs(existingAt - scheduledAt) <= 200L) {
            return
        }
        retryPendingJob?.cancel()
        retryScheduledAtMillis = scheduledAt
        retryPendingJob = viewModelScope.launch(exceptionHandler) {
            delay(delayMs)
            retryScheduledAtMillis = null
            flushPendingMessages()
        }
    }

    private suspend fun updateOutgoingTextState(
        sessionCode: String,
        messageId: String,
        text: String,
        status: BleMessageStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ) {
        BleChatStore.updateLocalMessageState(
            sessionCode = sessionCode,
            messageId = messageId,
            status = status,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
        updateLocalMessageDeliveryState(
            context = getApplication<Application>().applicationContext,
            uuid = messageId,
            deliveryStatus = mapToDeliveryStatus(status),
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }

    private fun resolveTextRoute(): TextRoute? {
        val serviceReady = _serverReady.value && sosServiceBinding.service.value != null
        if (serviceReady) {
            return TextRoute.Server
        }
        val managerReady =
            _connectionState.value?.status == BleClientManager.ConnectionStatus.Ready &&
                serviceBinding.manager.value != null
        if (managerReady) {
            return TextRoute.Client
        }
        return null
    }

    private fun computeNextRetryAtMillis(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponent = (safeAttempt - 1).coerceAtMost(6)
        val baseDelay = (TEXT_RETRY_BASE_DELAY_MS * (1L shl exponent)).coerceAtMost(TEXT_RETRY_MAX_DELAY_MS)
        val jitter = (baseDelay * TEXT_RETRY_JITTER_RATIO).toLong().coerceAtLeast(250L)
        val randomized = baseDelay + Random.nextLong(from = -jitter, until = jitter + 1L)
        return System.currentTimeMillis() + randomized.coerceAtLeast(TEXT_RETRY_MIN_DELAY_MS)
    }

    private fun mapDeliveryStatus(status: MessageDeliveryStatus?, isRead: Boolean): BleMessageStatus {
        if (isRead) {
            return BleMessageStatus.READ
        }
        return when (status) {
            MessageDeliveryStatus.QUEUED -> BleMessageStatus.QUEUED
            MessageDeliveryStatus.SENDING -> BleMessageStatus.SENDING
            MessageDeliveryStatus.SENT -> BleMessageStatus.SENT
            MessageDeliveryStatus.DELIVERED -> BleMessageStatus.DELIVERED
            MessageDeliveryStatus.READ -> BleMessageStatus.READ
            MessageDeliveryStatus.FAILED -> BleMessageStatus.FAILED
            null -> BleMessageStatus.SENT
        }
    }

    private fun mapToDeliveryStatus(status: BleMessageStatus): MessageDeliveryStatus {
        return when (status) {
            BleMessageStatus.QUEUED -> MessageDeliveryStatus.QUEUED
            BleMessageStatus.SENDING -> MessageDeliveryStatus.SENDING
            BleMessageStatus.SENT -> MessageDeliveryStatus.SENT
            BleMessageStatus.DELIVERED -> MessageDeliveryStatus.DELIVERED
            BleMessageStatus.READ -> MessageDeliveryStatus.READ
            BleMessageStatus.FAILED -> MessageDeliveryStatus.FAILED
        }
    }

    private suspend fun persistOutgoingTextState(
        sessionCode: String,
        messageId: String,
        text: String,
        status: BleMessageStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ) {
        upsertLocalTextMessage(
            context = getApplication<Application>().applicationContext,
            sessionCode = sessionCode,
            uuid = messageId,
            text = text,
            deliveryStatus = mapToDeliveryStatus(status),
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }

    private suspend fun sendVoicePackets(
        address: String,
        packets: List<String>,
        onChunkQueued: (Int) -> Unit
    ): Boolean {
        val totalPayloadChunks = if (packets.isNotEmpty()) {
            (packets.size - 1).coerceAtLeast(1)
        } else {
            1
        }
        val server = sosServiceBinding.service.value
        var sentChunks = 0
        if (packets.isEmpty()) {
            return true
        }
        if (_serverReady.value && server != null) {
            packets.forEachIndexed { index, packet ->
                val sent = server.sendChatMessage(address, packet)
                if (!sent) {
                    return false
                }
                sentChunks = if (index == 0) {
                    0
                } else {
                    index
                }.coerceAtMost(totalPayloadChunks)
                onChunkQueued(sentChunks)
            }
            return true
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            packets.forEachIndexed { index, packet ->
                val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
                if (!sent) {
                    return false
                }
                sentChunks = if (index == 0) {
                    0
                } else {
                    index
                }.coerceAtMost(totalPayloadChunks)
                onChunkQueued(sentChunks)
            }
            return true
        }
        return false
    }

    private suspend fun sendImagePackets(
        address: String,
        packets: List<String>,
        onChunkQueued: (Int) -> Unit
    ): Boolean {
        val totalPayloadChunks = if (packets.isNotEmpty()) {
            (packets.size - 1).coerceAtLeast(1)
        } else {
            1
        }
        val server = sosServiceBinding.service.value
        if (packets.isEmpty()) {
            return true
        }
        if (_serverReady.value && server != null) {
            packets.forEachIndexed { index, packet ->
                if (!server.sendChatMessage(address, packet)) {
                    return false
                }
                onChunkQueued(if (index == 0) 0 else index.coerceAtMost(totalPayloadChunks))
            }
            return true
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            packets.forEachIndexed { index, packet ->
                val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
                if (!sent) {
                    return false
                }
                onChunkQueued(if (index == 0) 0 else index.coerceAtMost(totalPayloadChunks))
            }
            return true
        }
        return false
    }

    private suspend fun sendFilePackets(
        address: String,
        packets: List<String>
    ): Boolean {
        val server = sosServiceBinding.service.value
        if (packets.isEmpty()) {
            return true
        }
        if (_serverReady.value && server != null) {
            packets.forEach { packet ->
                if (!server.sendChatMessage(address, packet)) {
                    return false
                }
            }
            return true
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            packets.forEach { packet ->
                val sent = runCatching { manager.sendMessageAwait(address, packet) }.getOrElse { false }
                if (!sent) {
                    return false
                }
            }
            return true
        }
        return false
    }

    private fun sendVoiceAbortPacket(address: String, transferId: String, reason: String) {
        val abortPacket = BleVoicePayload.buildAbortPacket(transferId, reason)
        if (abortPacket.isBlank()) {
            return
        }
        val server = sosServiceBinding.service.value
        if (_serverReady.value && server != null) {
            server.sendChatMessage(address, abortPacket)
            return
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    manager.sendMessageAwait(address, abortPacket)
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Failed to send voice abort packet transferId=$transferId reason=$reason",
                        throwable
                    )
                }
            }
        }
    }

    private fun sendImageAbortPacket(address: String, transferId: String, reason: String) {
        val abortPacket = BleImagePayload.buildAbortPacket(transferId, reason)
        if (abortPacket.isBlank()) {
            return
        }
        val server = sosServiceBinding.service.value
        if (_serverReady.value && server != null) {
            server.sendChatMessage(address, abortPacket)
            return
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    manager.sendMessageAwait(address, abortPacket)
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Failed to send image abort packet transferId=$transferId reason=$reason",
                        throwable
                    )
                }
            }
        }
    }

    private fun sendFileAbortPacket(address: String, transferId: String, reason: String) {
        val abortPacket = BleFilePayload.buildAbortPacket(transferId, reason)
        if (abortPacket.isBlank()) {
            return
        }
        val server = sosServiceBinding.service.value
        if (_serverReady.value && server != null) {
            server.sendChatMessage(address, abortPacket)
            return
        }
        val manager = serviceBinding.manager.value
        if (_connectionState.value?.status == BleClientManager.ConnectionStatus.Ready && manager != null) {
            viewModelScope.launch(exceptionHandler) {
                runCatching {
                    manager.sendMessageAwait(address, abortPacket)
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Failed to send file abort packet transferId=$transferId reason=$reason",
                        throwable
                    )
                }
            }
        }
    }

    private fun publishOutgoingVoiceProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: VoiceTransferState
    ) {
        BleVoiceTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = VoiceTransferDirection.Upload,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun publishOutgoingImageProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: ImageTransferState
    ) {
        BleImageTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = ImageTransferDirection.Upload,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun clearOutgoingVoiceProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long
    ) {
        viewModelScope.launch(exceptionHandler) {
            delay(delayMs)
            BleVoiceTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = VoiceTransferDirection.Upload
            )
        }
    }

    private fun clearOutgoingImageProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long
    ) {
        viewModelScope.launch(exceptionHandler) {
            delay(delayMs)
            BleImageTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = ImageTransferDirection.Upload
            )
        }
    }

    private fun computeVoiceOutcomeTimeoutMs(
        totalChunks: Int,
        isServerPath: Boolean
    ): Long {
        if (isServerPath) {
            return VOICE_ACK_TIMEOUT_SERVER_MS
        }
        val dynamic = VOICE_ACK_TIMEOUT_CLIENT_BASE_MS +
            (totalChunks.toLong() * VOICE_ACK_TIMEOUT_CLIENT_PER_CHUNK_MS)
        return dynamic.coerceIn(
            VOICE_ACK_TIMEOUT_CLIENT_BASE_MS,
            VOICE_ACK_TIMEOUT_CLIENT_MAX_MS
        )
    }

    private fun computeImageOutcomeTimeoutMs(
        totalChunks: Int,
        isServerPath: Boolean
    ): Long {
        if (isServerPath) {
            return IMAGE_ACK_TIMEOUT_SERVER_MS
        }
        val dynamic = IMAGE_ACK_TIMEOUT_CLIENT_BASE_MS +
            (totalChunks.toLong() * IMAGE_ACK_TIMEOUT_CLIENT_PER_CHUNK_MS)
        return dynamic.coerceIn(
            IMAGE_ACK_TIMEOUT_CLIENT_BASE_MS,
            IMAGE_ACK_TIMEOUT_CLIENT_MAX_MS
        )
    }

    private fun markLocalImageDeliveryState(
        sessionCode: String,
        messageId: String,
        status: BleMessageStatus,
        lastError: String?,
        outboundRoute: String? = null
    ) {
        BleChatStore.updateLocalMessageState(
            sessionCode = sessionCode,
            messageId = messageId,
            status = status,
            retryCount = 0,
            nextRetryAtMillis = null,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
        viewModelScope.launch(exceptionHandler) {
            updateLocalMessageDeliveryState(
                context = getApplication<Application>().applicationContext,
                uuid = messageId,
                deliveryStatus = mapToDeliveryStatus(status),
                retryCount = 0,
                nextRetryAtMillis = null,
                lastAttemptAtMillis = System.currentTimeMillis(),
                lastError = lastError,
                outboundRoute = outboundRoute
            )
        }
    }

    private fun canSendSecurePacket(): Boolean {
        val serverReady = _serverReady.value && sosServiceBinding.service.value != null
        if (serverReady) {
            return true
        }
        val manager = serviceBinding.manager.value
        return manager != null && _connectionState.value?.status == BleClientManager.ConnectionStatus.Ready
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication<Application>(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun cleanupRecording(deleteFile: Boolean) {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingStartRealtime = 0L
        _isRecording.value = false
        mediaRecorder = null
        if (deleteFile) {
            deleteFileSilently(currentRecordingFile)
            currentRecordingFile = null
            currentRecordingMimeType = VOICE_MIME_AAC
            _recordingFilePath.value = null
            _recordingDuration.value = 0L
        }
    }

    private fun deleteFileSilently(file: File?) {
        if (file == null) {
            return
        }
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private data class VoiceRecordingProfile(
        val mimeType: String,
        val fileExtension: String,
        val outputFormat: Int,
        val audioEncoder: Int,
        val sampleRate: Int,
        val bitrate: Int
    )

    private fun preferredRecordingProfiles(): List<VoiceRecordingProfile> {
        val profiles = mutableListOf<VoiceRecordingProfile>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            profiles += VoiceRecordingProfile(
                mimeType = VOICE_MIME_OPUS,
                fileExtension = ".ogg",
                outputFormat = MediaRecorder.OutputFormat.OGG,
                audioEncoder = MediaRecorder.AudioEncoder.OPUS,
                sampleRate = 24_000,
                bitrate = 24_000
            )
        }
        profiles += VoiceRecordingProfile(
            mimeType = VOICE_MIME_AAC,
            fileExtension = ".m4a",
            outputFormat = MediaRecorder.OutputFormat.MPEG_4,
            audioEncoder = MediaRecorder.AudioEncoder.AAC,
            sampleRate = 24_000,
            bitrate = 64_000
        )
        return profiles
    }

    private fun inferRecordingMimeType(path: String?): String {
        val extension = path?.substringAfterLast('.', "")?.lowercase(Locale.US)
        return when (extension) {
            "ogg", "opus" -> VOICE_MIME_OPUS
            "m4a", "mp4", "aac" -> VOICE_MIME_AAC
            else -> currentRecordingMimeType
        }
    }

    override fun onCleared() {
        cancelVoiceRecording()
        super.onCleared()
        messageJob?.cancel()
        contactJob?.cancel()
        managerJob?.cancel()
        sosLifecycleJob?.cancel()
        sosBindingJob?.cancel()
        directoryJob?.cancel()
        voiceTransferJob?.cancel()
        imageTransferJob?.cancel()
        flushPendingJob?.cancel()
        persistenceJob?.cancel()
        retryPendingJob?.cancel()
        readReceiptRetryJob?.cancel()
        synchronized(readReceiptLock) {
            pendingReadReceiptIds.clear()
        }
        sessionCode?.let { code ->
            BleVoiceTransferProgressStore.clearSession(code)
            BleImageTransferProgressStore.clearSession(code)
        }
        serviceBinding.unbind()
        sosServiceBinding.unbind()
    }

    private enum class TextRoute(val transportName: String) {
        Server("server"),
        Client("client")
    }

    companion object {
        private val RESCUE_ROLES = setOf("admin", "fieldteam")
        private const val TAG = "BleChatVM"
        private const val MAX_TEXT_SEND_ATTEMPTS = 7
        private const val TEXT_RETRY_BASE_DELAY_MS = 1_500L
        private const val TEXT_RETRY_MIN_DELAY_MS = 250L
        private const val TEXT_RETRY_MAX_DELAY_MS = 60_000L
        private const val TEXT_RETRY_JITTER_RATIO = 0.20
        private const val TEXT_TRANSPORT_WAIT_RETRY_DELAY_MS = 2_500L
        private const val TEXT_MESSAGE_TTL_MS = 86_400_000L
        private const val MAX_PENDING_READ_RECEIPTS = 256
        private const val READ_RECEIPT_MIN_SEND_INTERVAL_MS = 1_000L
        private const val READ_RECEIPT_RETRY_DELAY_MS = 900L
        private const val READ_RECEIPT_RETRY_MIN_DELAY_MS = 200L
        private const val READ_RECEIPT_RETRY_COALESCE_WINDOW_MS = 120L
        private const val VOICE_MIME_AAC = "audio/mp4"
        private const val VOICE_MIME_OPUS = "audio/ogg"
        private const val MAX_VOICE_SEND_ATTEMPTS = 3
        private const val VOICE_RETRY_DELAY_MS = 600L
        private const val VOICE_ACK_TIMEOUT_SERVER_MS = 12_000L
        private const val VOICE_ACK_TIMEOUT_CLIENT_BASE_MS = 8_000L
        private const val VOICE_ACK_TIMEOUT_CLIENT_PER_CHUNK_MS = 1_250L
        private const val VOICE_ACK_TIMEOUT_CLIENT_MAX_MS = 240_000L
        private const val VOICE_COMPLETED_BADGE_MS = 900L
        private const val VOICE_FAILED_BADGE_MS = 2_200L
        private const val MAX_IMAGE_SEND_ATTEMPTS = 3
        private const val IMAGE_RETRY_DELAY_MS = 900L
        private const val IMAGE_ACK_TIMEOUT_SERVER_MS = 18_000L
        private const val IMAGE_ACK_TIMEOUT_CLIENT_BASE_MS = 12_000L
        private const val IMAGE_ACK_TIMEOUT_CLIENT_PER_CHUNK_MS = 1_500L
        private const val IMAGE_ACK_TIMEOUT_CLIENT_MAX_MS = 90_000L
        private const val IMAGE_COMPLETED_BADGE_MS = 1_200L
        private const val IMAGE_FAILED_BADGE_MS = 2_600L
        private const val IMAGE_ERROR_PAYLOAD_TOO_LARGE = "IMAGE_PAYLOAD_TOO_LARGE"
        private const val IMAGE_ERROR_SEND_FAILED = "IMAGE_SEND_FAILED"
        private const val MAX_FILE_SEND_ATTEMPTS = 3
        private const val FILE_RETRY_DELAY_MS = 900L
        private const val FILE_ACK_TIMEOUT_MS = 45_000L
    }
}
