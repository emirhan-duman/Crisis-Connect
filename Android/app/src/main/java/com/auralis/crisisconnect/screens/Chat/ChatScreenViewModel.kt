package com.auralis.crisisconnect.screens.Chat

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.analytics.Analytics
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_IOS
import com.auralis.crisisconnect.data.buildStoreForwardMessageUuid
import com.auralis.crisisconnect.data.resolveSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.markMessagesAsRead
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.normalizeRemotePlatform
import com.auralis.crisisconnect.data.acknowledgePeerKeyChange
import com.auralis.crisisconnect.data.observeContact
import com.auralis.crisisconnect.data.observeMessages
import com.auralis.crisisconnect.data.updateContactAesKey
import com.auralis.crisisconnect.data.updateContactName
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.service.BleChatEnvelope
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.SosServerServiceBinding
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import android.net.ConnectivityManager
import android.net.Network
import com.auralis.crisisconnect.messaging.E2eEnvelope
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.call.InternetCallManager
import com.auralis.crisisconnect.nearby.NearbyAutoLink
import com.auralis.crisisconnect.messaging.MessagingIdentity
import com.auralis.crisisconnect.messaging.SafetyNumber
import com.auralis.crisisconnect.messaging.signal.SignalSessionGate
import com.auralis.crisisconnect.messaging.TypingIndicatorBus
import com.auralis.crisisconnect.data.local.ContactLastSeenStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.auralis.crisisconnect.service.p2p.P2pGattChatManager
import com.auralis.crisisconnect.service.p2p.call.P2pCallController
import com.auralis.crisisconnect.service.p2p.P2pGattChatStatus
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.service.CallState
import com.auralis.crisisconnect.service.CallUiState
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import java.util.Locale
import java.util.UUID
import androidx.exifinterface.media.ExifInterface
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.media.DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.prepareImageAttachmentForTransfer
import com.auralis.crisisconnect.data.observeCallEvents
import com.auralis.crisisconnect.isScreenshotDemoModeEnabledSync
import com.auralis.crisisconnect.settingsDataStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.random.Random

enum class ChatConnectionState {
    Idle,
    Connecting,
    Connected,
    Error
}

/**
 * Which physical transport is currently carrying the conversation. Today all chat traffic is
 * Bluetooth (RFCOMM primary, BLE/GATT fallback); internet delivery flips this to [Internet]
 * once the online transport lands. The connection-status badge shows a matching icon.
 */
enum class ChatTransport {
    Bluetooth,
    Internet
}

data class SignalStrengthInfo(
    val rssi: Int,
    val level: Int,
    val lastUpdated: Long
)

sealed class ChatTimelineItem {
    abstract val timestampMillis: Long

    data class Msg(val message: ChatMessage) : ChatTimelineItem() {
        override val timestampMillis: Long = message.timestampMillis
    }

    data class Call(val event: CallEvent) : ChatTimelineItem() {
        override val timestampMillis: Long = event.timestampMillis
    }
}

sealed class EncryptionSetupResult {
    object Success : EncryptionSetupResult()
    object MissingSession : EncryptionSetupResult()
    object InvalidKey : EncryptionSetupResult()
    object HandshakeFailed : EncryptionSetupResult()
}

class ChatScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _timelineItems = MutableStateFlow<List<ChatTimelineItem>>(emptyList())
    val timelineItems: StateFlow<List<ChatTimelineItem>> = _timelineItems.asStateFlow()

    private val _callEvents = MutableStateFlow<List<CallEvent>>(emptyList())

    private val _contactName = MutableStateFlow<String?>(null)
    val contactName: StateFlow<String?> = _contactName.asStateFlow()

    private val _contactPhotoUrl = MutableStateFlow<String?>(null)
    val contactPhotoUrl: StateFlow<String?> = _contactPhotoUrl.asStateFlow()

    private val _safetyNumber = MutableStateFlow<String?>(null)
    val safetyNumber: StateFlow<String?> = _safetyNumber.asStateFlow()

    // True when the shown safety number is the forward-secret (v3 Signal) fingerprint rather than
    // the interim v2 one — the UI shows a "forward secrecy" badge so a v2→v3 number change reads as
    // a security upgrade, not a MITM.
    private val _safetyNumberForwardSecret = MutableStateFlow(false)
    val safetyNumberForwardSecret: StateFlow<Boolean> = _safetyNumberForwardSecret.asStateFlow()

    // True when the peer's published identity key changed from what we last stored (TOFU warning).
    private val _peerKeyChanged = MutableStateFlow(false)
    val peerKeyChanged: StateFlow<Boolean> = _peerKeyChanged.asStateFlow()

    private val _contactAddressState = MutableStateFlow<String?>(null)
    val contactAddressState: StateFlow<String?> = _contactAddressState.asStateFlow()

    private val _connectionState = MutableStateFlow(ChatConnectionState.Idle)
    val connectionState: StateFlow<ChatConnectionState> = _connectionState.asStateFlow()

    private val _transport = MutableStateFlow(ChatTransport.Bluetooth)
    val transport: StateFlow<ChatTransport> = _transport.asStateFlow()

    private val _signalInfo = MutableStateFlow<SignalStrengthInfo?>(null)
    val signalInfo: StateFlow<SignalStrengthInfo?> = _signalInfo.asStateFlow()

    private val _signalPermissionMissing = MutableStateFlow(false)
    val signalPermissionMissing: StateFlow<Boolean> = _signalPermissionMissing.asStateFlow()

    private val _isBleFallbackActive = MutableStateFlow(false)
    val isBleFallbackActive: StateFlow<Boolean> = _isBleFallbackActive.asStateFlow()

    private val _canSendVoiceMessages = MutableStateFlow(false)
    val canSendVoiceMessages: StateFlow<Boolean> = _canSendVoiceMessages.asStateFlow()

    private val _canSendAttachments = MutableStateFlow(false)
    val canSendAttachments: StateFlow<Boolean> = _canSendAttachments.asStateFlow()

    private val _canShareLocation = MutableStateFlow(false)
    val canShareLocation: StateFlow<Boolean> = _canShareLocation.asStateFlow()

    private val _canPlaceCall = MutableStateFlow(false)
    val canPlaceCall: StateFlow<Boolean> = _canPlaceCall.asStateFlow()

    private val _showCallAction = MutableStateFlow(true)
    val showCallAction: StateFlow<Boolean> = _showCallAction.asStateFlow()

    private val _isSessionEncrypted = MutableStateFlow(false)
    val isSessionEncrypted: StateFlow<Boolean> = _isSessionEncrypted.asStateFlow()

    private val _sessionAesKey = MutableStateFlow<String?>(null)
    val sessionAesKey: StateFlow<String?> = _sessionAesKey.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    private val _activeCall = MutableStateFlow<CallUiState?>(null)
    val activeCall: StateFlow<CallUiState?> = _activeCall.asStateFlow()

    /**
     * The active internet (WebRTC) call, if any — a separate lightweight state from the Bluetooth
     * [activeCall] (which is Opus/RFCOMM-specific). Web-compatible: signals ride the E2E relay in the
     * same CallSignal wire the web dashboard uses, so this call can be with an Android or web peer.
     */
    val internetCall: StateFlow<InternetCallManager.CallInfo?> = InternetCallManager.call

    // Call state arrives from two transports; the last snapshot of each is merged so one
    // transport clearing its state never wipes an active call on the other.
    @Volatile
    private var rfcommCallUiState: CallUiState? = null

    @Volatile
    private var gattCallsSnapshot: Map<String, CallUiState> = emptyMap()

    private var sessionCode: String? = null
    private var messageJob: Job? = null
    private var contactJob: Job? = null
    private var sessionMonitorJob: Job? = null
    private var reconnectJob: Job? = null
    private var callStateJob: Job? = null
    private var callHistoryJob: Job? = null
    private var deferredConnectJob: Job? = null
    private var flushPendingTextJob: Job? = null
    private var retryPendingTextJob: Job? = null
    private var retryScheduledAtMillis: Long? = null

    private var service: RfcommForegroundService? = null
    private var isBound = false
    private var pendingConnection = false
    private var connectionInProgress = false
    private var hasConnectedAtLeastOnce = false
    private val pendingOperations = ArrayDeque<(RfcommForegroundService) -> Unit>()
    private val sosServiceBinding = SosServerServiceBinding(context)
    private val p2pGattChatManager = P2pGattChatManager.shared(context)
    private val internetChatTransport = InternetChatTransport(context)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var sosBindingJob: Job? = null
    private var bleClientStateJob: Job? = null
    private var p2pGattStatusJob: Job? = null
    private var signalMonitor: SignalMonitor? = null
    private var isPublicMeshModeEnabled = false
    private var isHighRangeModeEnabled = false
    private var bleClientFallbackArmed = false
    private var temporaryBleFallbackSessionCode: String? = null
    private var temporaryBleFallbackUntilElapsedRealtimeMs: Long? = null
    private var temporaryBleFallbackExpiryJob: Job? = null
    private var contactAddress: String? = null
    private var activeContact: Contact? = null
    // One background attempt per chat session to auto-bootstrap an offline Bluetooth link for an
    // internet-added contact (SPAKE2 using the peer's stored number). Guarded so it doesn't re-run
    // on every contact emission; reset when a new session is initialized.
    private var autoLinkAttemptedForSession: String? = null
    private var autoLinkJob: Job? = null
    private var lastBleFallbackConnectAddress: String? = null
    // ANR guards for BT failure storms: the BLE client re-emits (address,status) many times per
    // second while GATT is flapping, and each pass used to re-kick a connect from the collector —
    // a main-thread feedback loop that starved input dispatch (see 2026-07-03 S21 ANRs).
    private var lastObservedBleClientStateKey: String? = null
    private var lastBleFallbackConnectAttemptAddress: String? = null
    private var lastBleFallbackConnectAttemptAtMs: Long = 0L
    // One-shot guard so a Bluetooth-down episode (with no internet fallback) warns the user once,
    // not on every reconnect attempt. Reset on a successful link or once we switch to the internet.
    private var bluetoothLostWarningShown = false
    // True while the chat is shown as "connected over the internet" AND a Bluetooth attempt is still
    // running in the background. Suppresses the transient "Connecting" a background BT attempt emits
    // so the badge stays "internet connected" until Bluetooth actually links up (then it takes over).
    private var internetActiveConnected = false
    // Hysteresis for the internet→Bluetooth badge handover: a marginal BT link bounces up and down,
    // and flipping the badge on every link-up would ping-pong it (bluetooth → connecting → internet →
    // bluetooth …). While the internet carries the chat, a fresh BT link must stay up for
    // BLUETOOTH_TAKEOVER_STABILITY_MS before it takes the badge; message routing does NOT wait.
    private var bluetoothTakeoverJob: Job? = null
    private val pendingP2pReadReceiptIds = linkedSetOf<String>()
    private var isFlushingPendingP2pReadReceipts = false
    // Read receipts routed over the internet that haven't been accepted by the relay yet; merged
    // into the next attempt (receipts are idempotent on the peer, so re-sends are harmless).
    // Guarded by synchronized(pendingInternetReadReceiptIds): filled on Main, drained on IO.
    private val pendingInternetReadReceiptIds = linkedSetOf<String>()

    /**
     * When true, the ViewModel has been bootstrapped with the scripted
     * screenshot scenario in [applyScreenshotDemoScenario]. Used by
     * [refreshTransportCapabilities] and [updateConnectionState] to avoid
     * clobbering the fake "Connected / everything enabled" state when the
     * real settings DataStore, BLE peers, or service binder emit events.
     */
    private var isInScreenshotDemoMode: Boolean = false

    private var mediaRecorder: MediaRecorder? = null
    private var currentRecordingFile: File? = null
    private var currentRecordingMimeType: String = VOICE_MIME_AAC
    private var recordingStartRealtime: Long = 0L
    private var recordingTimerJob: Job? = null
    private var voiceProgressJob: Job? = null
    private var imageProgressJob: Job? = null
    private var lastOfflineMapShareSentAtMillis: Long = 0L

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val rfcommBinder = binder as? RfcommForegroundService.LocalBinder ?: return
            val boundService = rfcommBinder.getService()
            service = boundService
            isBound = true
            refreshBleFallbackRouteState()
            boundService.ensureListening("onServiceConnected")
            observeBleFallbackClientState(boundService)
            voiceProgressJob?.cancel()
            voiceProgressJob = viewModelScope.launch(exceptionHandler) {
                boundService.voiceTransfers.collect { transfers ->
                    val currentSession = sessionCode
                    val filtered = if (currentSession != null) {
                        transfers.values
                            .filter { it.sessionCode == currentSession }
                            .associateBy { it.uuid }
                    } else {
                        emptyMap()
                    }
                    _voiceTransfers.value = filtered
                }
            }
            imageProgressJob?.cancel()
            imageProgressJob = viewModelScope.launch(exceptionHandler) {
                boundService.imageTransfers.collect { transfers ->
                    val currentSession = sessionCode
                    val filtered = if (currentSession != null) {
                        transfers.values
                            .filter { it.sessionCode == currentSession }
                            .associateBy { it.uuid }
                    } else {
                        emptyMap()
                    }
                    _imageTransfers.value = filtered
                }
            }
            callStateJob?.cancel()
            callStateJob = viewModelScope.launch(exceptionHandler) {
                boundService.calls.collect { calls ->
                    val currentSession = sessionCode
                    rfcommCallUiState = currentSession?.let { calls[it] }
                    recomputeActiveCall()
                }
            }
            observeActiveSessions(boundService)
            flushPendingOperations()
            flushQueuedTextMessages()
            val code = sessionCode
            if (code != null && pendingConnection && !connectionInProgress && !boundService.isSessionActive(code)) {
                requestConnection(code)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cleanupServiceBinding()
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val key = LocalKeyStorage.getOrCreateAesKey(context)
            _sessionAesKey.value = key
        }
        viewModelScope.launch(exceptionHandler) {
            P2pCallController.shared(context).calls.collect { calls ->
                gattCallsSnapshot = calls
                recomputeActiveCall()
            }
        }
        observeSosBinding()
        observeP2pGattStatus()
        viewModelScope.launch(exceptionHandler) {
            context.settingsDataStore.data.collect { prefs ->
                val isMeshEnabled = prefs[PUBLIC_MESH_ENABLED_KEY] ?: false
                if (isPublicMeshModeEnabled != isMeshEnabled) {
                    isPublicMeshModeEnabled = isMeshEnabled
                    if (isMeshEnabled) {
                        stopSignalMonitoring()
                        _signalPermissionMissing.value = false
                    } else {
                        refreshSignalMonitoring()
                    }
                }
                val highRangeEnabled = prefs[HIGH_RANGE_MODE_ENABLED_KEY] ?: false
                if (isHighRangeModeEnabled != highRangeEnabled) {
                    isHighRangeModeEnabled = highRangeEnabled
                    if (highRangeEnabled || isBleFallbackAllowed()) {
                        requestBleFallbackBootstrap()
                    } else {
                        disarmBleClientFallback()
                        sosServiceBinding.unbind()
                        disconnectTrackedBleFallbackConnections()
                    }
                }
                refreshBleFallbackRouteState()
            }
        }
    }

    private fun observeP2pGattStatus() {
        p2pGattStatusJob?.cancel()
        p2pGattStatusJob = viewModelScope.launch(exceptionHandler) {
            p2pGattChatManager.status.collect { status ->
                if (!isPreferredP2pGattContact()) {
                    return@collect
                }
                when (status) {
                    P2pGattChatStatus.Ready -> {
                        // Bluetooth (GATT) linked up — take over from the internet background
                        // (the badge waits out the stability window when the internet is active).
                        hasConnectedAtLeastOnce = true
                        connectionInProgress = false
                        pendingConnection = false
                        onBluetoothLinkUp()
                        flushQueuedTextMessages()
                        flushPendingP2pReadReceipts()
                    }

                    P2pGattChatStatus.Connecting,
                    P2pGattChatStatus.Discovering -> {
                        updateConnectionState(ChatConnectionState.Connecting)
                    }

                    P2pGattChatStatus.Failed -> {
                        connectionInProgress = false
                        pendingConnection = false
                        cancelBluetoothTakeover()
                        if (!internetActiveConnected && switchToInternetIfAvailable()) {
                            // Peer reachable online — the internet carries the chat while GATT retries.
                        } else {
                            updateConnectionState(ChatConnectionState.Error)
                        }
                    }

                    P2pGattChatStatus.Disconnected -> {
                        cancelBluetoothTakeover()
                        if (!internetActiveConnected && switchToInternetIfAvailable()) {
                            // Peer reachable online — no dead end while GATT reconnects.
                        } else if (pendingConnection || connectionInProgress) {
                            updateConnectionState(ChatConnectionState.Connecting)
                        } else {
                            updateConnectionState(ChatConnectionState.Error)
                        }
                    }
                }
            }
        }
    }

    fun initialize(sessionCode: String) {
        if (this.sessionCode == sessionCode) {
            return
        }
        if (isScreenshotDemoModeEnabledSync(context)) {
            applyScreenshotDemoScenario(sessionCode)
            return
        }
        // Reset in case a previous session was in demo mode and the same
        // ViewModel instance is being reused for a real session.
        isInScreenshotDemoMode = false
        registerConnectivityFlushIfNeeded()
        val previousSessionCode = this.sessionCode
        val previousAddress = contactAddress
        if (!previousSessionCode.isNullOrBlank() && previousSessionCode != sessionCode && !previousAddress.isNullOrBlank()) {
            disconnectBleFallbackClient(previousAddress)
        }
        p2pGattChatManager.stopNow()
        autoLinkJob?.cancel()
        autoLinkJob = null
        autoLinkAttemptedForSession = null
        // Transport badge state must not leak across sessions: drop any pending Bluetooth-takeover
        // hold and the previous chat's internet suppression flag before the new one connects.
        cancelBluetoothTakeover()
        internetActiveConnected = false
        activeContact = null
        contactAddress = null
        _contactAddressState.value = null
        flushPendingTextJob?.cancel()
        retryPendingTextJob?.cancel()
        retryPendingTextJob = null
        retryScheduledAtMillis = null
        disarmBleClientFallback()
        hasConnectedAtLeastOnce = false
        pendingP2pReadReceiptIds.clear()
        isFlushingPendingP2pReadReceipts = false
        synchronized(pendingInternetReadReceiptIds) { pendingInternetReadReceiptIds.clear() }
        this.sessionCode = sessionCode
        if (isBleFallbackAllowed(sessionCode)) {
            requestBleFallbackBootstrap()
        }
        callHistoryJob?.cancel()
        callHistoryJob = viewModelScope.launch(exceptionHandler) {
            observeCallEvents(context, sessionCode).collect { events ->
                _callEvents.value = events
            }
        }
        messageJob?.cancel()
        messageJob = viewModelScope.launch(exceptionHandler) {
            observeMessages(context, sessionCode)
                .combine(_callEvents) { list, callEvents ->
                    list to callEvents
                }
                .map { (messages, callEvents) ->
                    withContext(Dispatchers.Default) {
                        buildTimelineSnapshot(messages, callEvents)
                    }
                }
                .collect { snapshot ->
                    _messages.value = snapshot.messages
                    _timelineItems.value = snapshot.timeline
                    val hasQueuedLocalText = snapshot.messages.any { message ->
                        message.sessionCode == sessionCode &&
                            message.isLocal &&
                            message.messageType == MessageType.TEXT &&
                            message.text.isNotBlank() &&
                            message.deliveryStatus == MessageDeliveryStatus.QUEUED
                    }
                    if (hasQueuedLocalText) {
                        if (!isTextTransportReady(sessionCode)) {
                            requestConnection(sessionCode)
                            requestBleFallbackBootstrap()
                        }
                        flushQueuedTextMessages()
                    } else {
                        schedulePendingTextRetry()
                    }
                }
        }
        contactJob?.cancel()
        contactJob = viewModelScope.launch(exceptionHandler) {
            observeContact(context, sessionCode).collect { contact ->
                _contactName.value = contact?.name
                _contactPhotoUrl.value = contact?.peerPhotoUrl?.takeIf { it.isNotBlank() }
                updateSafetyNumber(contact)
                _peerKeyChanged.value = contact?.peerKeyChanged == true
                activeContact = contact
                attachPresenceListener(contact)
                maybeAutoLinkBluetooth(contact, sessionCode)
                p2pGattChatManager.updateContact(contact)
                val storedKey = contact?.aesKey?.takeIf { it.isNotBlank() }
                _isSessionEncrypted.value = storedKey != null
                val normalizedAddressSource = when {
                    normalizePreferredTransport(contact?.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT ->
                        contact?.lastKnownBleAddress?.takeIf { it.isNotBlank() } ?: contact?.address
                    else -> contact?.address
                }
                val normalizedAddress = normalizedAddressSource?.takeIf { it.isNotBlank() }?.let {
                    normalizeMacAddress(it)
                }?.takeIf { it.isNotBlank() }
                contactAddress = normalizedAddress
                _contactAddressState.value = normalizedAddress
                if (contactAddress == null || isPreferredP2pGattContact()) {
                    stopSignalMonitoring()
                    _signalPermissionMissing.value = false
                } else {
                    refreshSignalMonitoring()
                }
                val address = activeBleFallbackAddress()
                val code = this@ChatScreenViewModel.sessionCode
                if (isBleFallbackAllowed(code) && !address.isNullOrBlank() && !code.isNullOrBlank()) {
                    requestBleFallbackBootstrap()
                    sosServiceBinding.service.value?.registerSessionAlias(address, code)
                }
                refreshBleFallbackRouteState()
                val currentCode = this@ChatScreenViewModel.sessionCode
                if (
                    !currentCode.isNullOrBlank() &&
                    currentCode == sessionCode &&
                    !isTextTransportReady(currentCode)
                ) {
                    requestConnection(currentCode)
                }
                if (currentCode == sessionCode) {
                    flushPendingP2pReadReceipts()
                }
            }
        }
        updateConnectionState(ChatConnectionState.Connecting)
        deferredConnectJob?.cancel()
        deferredConnectJob = viewModelScope.launch(exceptionHandler) {
            delay(CONNECTION_BOOTSTRAP_DELAY_MS)
            if (this@ChatScreenViewModel.sessionCode == sessionCode) {
                requestConnection(sessionCode)
            }
        }
    }

    fun saveContactName(newName: String) {
        val code = sessionCode ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            updateContactName(context, code, trimmed)
            _contactName.value = trimmed
        }
    }

    fun performEncryptionSetup(aesKey: String, onResult: (EncryptionSetupResult) -> Unit) {
        val code = sessionCode
        if (code.isNullOrBlank()) {
            mainHandler.post { onResult(EncryptionSetupResult.MissingSession) }
            return
        }
        val trimmedKey = aesKey.trim()
        if (trimmedKey.isEmpty()) {
            mainHandler.post { onResult(EncryptionSetupResult.InvalidKey) }
            return
        }
        if (isPreferredP2pGattContact()) {
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                updateContactAesKey(context, code, trimmedKey)
                activeContact = activeContact?.copy(aesKey = trimmedKey)
                p2pGattChatManager.updateContact(activeContact)
                mainHandler.post { onResult(EncryptionSetupResult.Success) }
            }
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.performHandshake(code, trimmedKey) { success ->
                if (success) {
                    viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                        updateContactAesKey(context, code, trimmedKey)
                        mainHandler.post { onResult(EncryptionSetupResult.Success) }
                    }
                } else {
                    mainHandler.post { onResult(EncryptionSetupResult.HandshakeFailed) }
                }
            }
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun sendMessage(text: String, onResult: (Boolean) -> Unit) {
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            onResult(false)
            return
        }
        val createdAtMillis = System.currentTimeMillis()
        val messageUuid = buildStoreForwardMessageUuid(createdAtMillis = createdAtMillis)
        viewModelScope.launch(exceptionHandler) {
            val queued = queueOutgoingTextMessage(
                sessionCode = code,
                uuid = messageUuid,
                text = trimmed
            )
            if (!queued) {
                _errorMessage.value = context.getString(R.string.chat_send_failed)
                onResult(false)
                return@launch
            }
            onResult(true)
            Analytics.messageSent(kind = "text", transport = _transport.value.name.lowercase())
            if (!isTextTransportReady(code)) {
                requestConnection(code)
                requestBleFallbackBootstrap()
            }
            flushQueuedTextMessages()
        }
    }

    fun shareOfflineMapBundleForLocation(latitude: Double, longitude: Double) {
        val code = sessionCode ?: return
        if (!latitude.isFinite() || !longitude.isFinite()) {
            return
        }
        // The offline-map bundle rides the Bluetooth (RFCOMM) link only — it lets an OFFLINE peer view
        // the shared pin without downloading tiles. Over an internet-only chat there's no BT link to
        // send it on (and the peer has internet to load the map anyway), so skip the futile transfer
        // instead of bouncing off a disconnected service. A peer that's BOTH nearby and online still
        // gets it over Bluetooth.
        if (!isRfcommTransportReady()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastOfflineMapShareSentAtMillis < OFFLINE_MAP_SHARE_COOLDOWN_MS) {
            return
        }
        lastOfflineMapShareSentAtMillis = now
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.sendOfflineMapShareForLocation(code, latitude, longitude) { success ->
                if (!success) {
                    // If sharing did not happen (no eligible map or transfer issue), allow retry sooner.
                    lastOfflineMapShareSentAtMillis = 0L
                }
            }
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun startVoiceRecording() {
        if (_isRecording.value) {
            return
        }
        if (!hasAudioPermission()) {
            onRecordingPermissionDenied()
            return
        }
        cleanupRecording(deleteFile = true)
        var started = false
        preferredRecordingProfiles().forEach { profile ->
            if (started) {
                return@forEach
            }
            val outputFile = try {
                File.createTempFile("voice_", profile.fileExtension, context.cacheDir)
            } catch (_: IOException) {
                null
            } ?: return@forEach
            val recorder = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
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
            _errorMessage.value = context.getString(R.string.chat_voice_recording_failed)
            return
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
    }

    fun stopVoiceRecording() {
        if (!_isRecording.value) {
            return
        }
        val recorder = mediaRecorder
        val file = currentRecordingFile
        if (recorder == null || file == null) {
            cleanupRecording(deleteFile = true)
            return
        }
        val startRealtime = recordingStartRealtime
        try {
            recorder.stop()
        } catch (exception: Exception) {
            runCatching { recorder.reset() }
            recorder.release()
            mediaRecorder = null
            recordingTimerJob?.cancel()
            recordingTimerJob = null
            recordingStartRealtime = 0L
            _isRecording.value = false
            _recordingDuration.value = 0L
            _recordingFilePath.value = null
            deleteFileSilently(file)
            currentRecordingFile = null
            _errorMessage.value = context.getString(R.string.chat_voice_recording_stop_failed)
            return
        }
        runCatching { recorder.reset() }
        recorder.release()
        mediaRecorder = null
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingStartRealtime = 0L
        _isRecording.value = false
        val elapsed = if (startRealtime > 0L) SystemClock.elapsedRealtime() - startRealtime else 0L
        _recordingDuration.value = elapsed
        if (!file.exists() || file.length() <= 0L) {
            deleteFileSilently(file)
            currentRecordingFile = null
            _recordingFilePath.value = null
            _recordingDuration.value = 0L
            _errorMessage.value = context.getString(R.string.chat_voice_file_missing)
            return
        }
        _recordingFilePath.value = file.absolutePath
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

    fun sendRecordedVoice(onResult: (Boolean) -> Unit) {
        if (_isRecording.value) {
            stopVoiceRecording()
        }
        if (!supportsVoiceMessaging()) {
            _errorMessage.value = context.getString(R.string.chat_voice_send_failed)
            onResult(false)
            return
        }
        val path = _recordingFilePath.value
        val file = (currentRecordingFile?.takeIf { it.absolutePath == path } ?: path?.let { File(it) })
        if (file == null || !file.exists() || file.length() == 0L) {
            _errorMessage.value = context.getString(R.string.chat_voice_file_missing)
            cleanupRecording(deleteFile = true)
            onResult(false)
            return
        }
        currentRecordingFile = file
        val mimeType = inferRecordingMimeType(file.absolutePath)
        val duration = _recordingDuration.value.takeIf { it > 0L }
        val uuid = UUID.randomUUID().toString()
        _isSendingVoice.value = true
        Analytics.messageSent(kind = "voice", transport = _transport.value.name.lowercase())

        // Internet transport: when Bluetooth is NOT linked but we hold the peer's identity and the
        // internet is reachable, send the voice clip over the E2E relay (chunked) instead of BT.
        val voiceCode = sessionCode ?: activeContact?.sessionCode
        val internetVoiceContact = activeContact
        if (internetVoiceContact != null &&
            internetVoiceContact.supportsInternet &&
            internetChatTransport.isAvailable() &&
            (voiceCode == null || !isBluetoothLinkReady(voiceCode))
        ) {
            viewModelScope.launch(exceptionHandler) {
                val fileBytes = try {
                    withContext(Dispatchers.IO) {
                        val destination = voiceMessageFile(context, voiceMessageFileName(uuid, mimeType))
                        file.copyTo(destination, overwrite = true)
                        val bytes = destination.readBytes()
                        saveLocalAudioMessage(
                            context,
                            internetVoiceContact.sessionCode,
                            uuid,
                            destination.name,
                            duration,
                            deliveryStatus = MessageDeliveryStatus.SENDING
                        )
                        bytes
                    }
                } catch (_: IOException) {
                    _errorMessage.value = context.getString(R.string.chat_voice_file_missing)
                    cleanupRecording(deleteFile = true)
                    _isSendingVoice.value = false
                    onResult(false)
                    return@launch
                }
                val sent = internetChatTransport.sendAttachment(
                    transferId = uuid,
                    kind = E2eEnvelope.ATTACHMENT_KIND_AUDIO,
                    mime = mimeType ?: "audio/ogg",
                    name = voiceMessageFileName(uuid, mimeType),
                    durationMs = (duration ?: 0L).toInt(),
                    data = fileBytes,
                    createdAtMs = System.currentTimeMillis(),
                    contact = internetVoiceContact
                )
                if (sent) markInternetCarrying()
                handleVoiceSendResult(uuid, sent, onResult)
            }
            return
        }

        if (isPreferredP2pGattContact()) {
            val contact = activeContact
            if (contact == null) {
                _isSendingVoice.value = false
                onResult(false)
                return
            }
            viewModelScope.launch(exceptionHandler) {
                val fileBytes = try {
                    withContext(Dispatchers.IO) {
                        val destination = voiceMessageFile(context, voiceMessageFileName(uuid, mimeType))
                        file.copyTo(destination, overwrite = true)
                        val bytes = destination.readBytes()
                        saveLocalAudioMessage(
                            context,
                            contact.sessionCode,
                            uuid,
                            destination.name,
                            duration,
                            deliveryStatus = MessageDeliveryStatus.SENDING
                        )
                        bytes
                    }
                } catch (_: IOException) {
                    _errorMessage.value = context.getString(R.string.chat_voice_file_missing)
                    cleanupRecording(deleteFile = true)
                    _isSendingVoice.value = false
                    onResult(false)
                    return@launch
                }
                val success = p2pGattChatManager.sendVoiceMessage(
                    contact = contact,
                    messageId = uuid,
                    mimeType = mimeType,
                    durationMillis = duration ?: 0L,
                    bytes = fileBytes
                )
                handleVoiceSendResult(uuid, success, onResult)
            }
            return
        }

        val code = sessionCode ?: run {
            _isSendingVoice.value = false
            onResult(false)
            return
        }
        viewModelScope.launch(exceptionHandler) {
            val fileBytes = try {
                withContext(Dispatchers.IO) {
                    val destination = voiceMessageFile(context, voiceMessageFileName(uuid, mimeType))
                    file.copyTo(destination, overwrite = true)
                    val bytes = destination.readBytes()
                    saveLocalAudioMessage(
                        context,
                        code,
                        uuid,
                        destination.name,
                        duration,
                        deliveryStatus = MessageDeliveryStatus.SENDING
                    )
                    bytes
                }
            } catch (ioException: IOException) {
                _errorMessage.value = context.getString(R.string.chat_voice_file_missing)
                cleanupRecording(deleteFile = true)
                _isSendingVoice.value = false
                onResult(false)
                return@launch
            }
            val operation: (RfcommForegroundService) -> Unit = { srv ->
                srv.sendVoiceStream(code, uuid, mimeType, duration, fileBytes) { success ->
                    handleVoiceSendResult(uuid, success, onResult)
                }
            }
            val currentService = service
            if (currentService == null) {
                pendingOperations.add(operation)
                ensureServiceBound()
            } else {
                operation(currentService)
            }
        }
    }

    fun sendImageAttachment(
        uri: Uri,
        mimeType: String?,
        width: Int?,
        height: Int?,
        onResult: (Boolean) -> Unit
    ) {
        if (!supportsAttachmentTransport()) {
            _errorMessage.value = context.getString(R.string.chat_image_send_failed)
            onResult(false)
            return
        }
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        _isSendingImage.value = true
        Analytics.messageSent(kind = "image", transport = _transport.value.name.lowercase())
        val uuid = UUID.randomUUID().toString()
        viewModelScope.launch(exceptionHandler) {
            val preparation = withContext(Dispatchers.IO) {
                prepareImageAttachment(uuid, uri, mimeType, width, height)
            }
            if (preparation == null) {
                _isSendingImage.value = false
                _errorMessage.value = context.getString(R.string.chat_image_file_missing)
                onResult(false)
                return@launch
            }
            try {
                saveLocalImageMessage(
                    context,
                    code,
                    uuid,
                    preparation.fileName,
                    preparation.thumbnailName,
                    preparation.width,
                    preparation.height,
                    preparation.mimeType,
                    deliveryStatus = MessageDeliveryStatus.SENDING
                )
            } catch (throwable: Exception) {
                _isSendingImage.value = false
                _errorMessage.value = context.getString(R.string.chat_image_file_missing)
                onResult(false)
                return@launch
            }
            // Internet transport: send the image over the E2E relay (chunked) when Bluetooth is not
            // linked but the peer's identity is known and the internet is reachable.
            val imageInternetContact = activeContact
            if (imageInternetContact != null &&
                imageInternetContact.supportsInternet &&
                internetChatTransport.isAvailable() &&
                !isBluetoothLinkReady(code)
            ) {
                val sent = internetChatTransport.sendAttachment(
                    transferId = uuid,
                    kind = E2eEnvelope.ATTACHMENT_KIND_IMAGE,
                    mime = preparation.mimeType,
                    name = preparation.fileName,
                    durationMs = 0,
                    data = preparation.bytes,
                    createdAtMs = System.currentTimeMillis(),
                    contact = imageInternetContact
                )
                if (sent) markInternetCarrying()
                handleImageSendResult(uuid, sent, onResult)
                return@launch
            }
            if (isPreferredP2pGattContact()) {
                val contact = activeContact
                if (contact == null) {
                    _isSendingImage.value = false
                    onResult(false)
                    return@launch
                }
                val preparedWidth = preparation.width
                val preparedHeight = preparation.height
                if (preparedWidth == null || preparedHeight == null || preparedWidth <= 0 || preparedHeight <= 0) {
                    _isSendingImage.value = false
                    _errorMessage.value = context.getString(R.string.chat_image_send_failed)
                    onResult(false)
                    return@launch
                }
                val success = p2pGattChatManager.sendImageMessage(
                    contact = contact,
                    messageId = uuid,
                    mimeType = preparation.mimeType,
                    width = preparedWidth,
                    height = preparedHeight,
                    bytes = preparation.bytes
                )
                handleImageSendResult(uuid, success, onResult)
                return@launch
            }
            val operation: (RfcommForegroundService) -> Unit = { srv ->
                srv.sendImageStream(
                    code,
                    uuid,
                    preparation.mimeType,
                    preparation.width,
                    preparation.height,
                    preparation.bytes
                ) { success ->
                    handleImageSendResult(uuid, success, onResult)
                }
            }
            val currentService = service
            if (currentService == null) {
                pendingOperations.add(operation)
                ensureServiceBound()
            } else {
                operation(currentService)
            }
        }
    }

    fun sendDocumentAttachment(
        uri: Uri,
        onResult: (Boolean) -> Unit
    ) {
        if (!supportsAttachmentTransport()) {
            _errorMessage.value = context.getString(R.string.chat_document_send_failed)
            onResult(false)
            return
        }
        val code = sessionCode ?: run {
            onResult(false)
            return
        }
        if (_isSendingDocument.value) {
            onResult(false)
            return
        }
        _isSendingDocument.value = true
        val uuid = UUID.randomUUID().toString()
        viewModelScope.launch(exceptionHandler) {
            val prepared = withContext(Dispatchers.IO) {
                prepareDocumentAttachment(context, uuid, uri)
            }
            if (prepared == null) {
                _isSendingDocument.value = false
                _errorMessage.value = context.getString(R.string.chat_document_file_invalid)
                onResult(false)
                return@launch
            }
            // Internet transport: no BT link but the peer's identity is known → ship the ORIGINAL
            // bytes chunked over the E2E relay (the wire attachment carries no compression flag, so
            // the uncompressed copy keeps the receiver simple), plus the same CC_FILE metadata text
            // that renders the file bubble.
            val docInternetContact = activeContact
            if (docInternetContact != null &&
                docInternetContact.supportsInternet &&
                internetChatTransport.isAvailable() &&
                !isBluetoothLinkReady(code)
            ) {
                // prepareDocumentAttachment persisted the original bytes locally under this uuid.
                val originalBytes = withContext(Dispatchers.IO) {
                    runCatching {
                        resolveSharedDocumentLocalCopy(context, uuid)?.readBytes()
                    }.getOrNull()
                }
                if (originalBytes == null || originalBytes.isEmpty()) {
                    _isSendingDocument.value = false
                    _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                    onResult(false)
                    return@launch
                }
                val internetPrepared = prepared.copy(
                    transferSizeBytes = originalBytes.size,
                    compression = FILE_COMPRESSION_NONE,
                    payloadBytes = originalBytes
                )
                val sent = internetChatTransport.sendAttachment(
                    transferId = uuid,
                    kind = E2eEnvelope.ATTACHMENT_KIND_FILE,
                    mime = internetPrepared.mimeType ?: "application/octet-stream",
                    name = internetPrepared.displayName,
                    durationMs = 0,
                    data = originalBytes,
                    createdAtMs = System.currentTimeMillis(),
                    contact = docInternetContact
                )
                if (!sent) {
                    _isSendingDocument.value = false
                    _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                    onResult(false)
                    return@launch
                }
                val previewQueued = queueOutgoingTextMessage(
                    sessionCode = code,
                    uuid = uuid,
                    text = buildSharedFileMessage(internetPrepared)
                )
                _isSendingDocument.value = false
                if (!previewQueued) {
                    _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                    onResult(false)
                    return@launch
                }
                markInternetCarrying()
                flushQueuedTextMessages()
                onResult(true)
                return@launch
            }
            if (isPreferredP2pGattContact()) {
                val contact = activeContact
                if (contact == null) {
                    _isSendingDocument.value = false
                    onResult(false)
                    return@launch
                }
                val fileSent = p2pGattChatManager.sendFileMessage(
                    contact = contact,
                    messageId = uuid,
                    displayName = prepared.displayName,
                    mimeType = prepared.mimeType,
                    originalSizeBytes = prepared.originalSizeBytes,
                    bytes = prepared.payloadBytes
                )
                if (!fileSent) {
                    _isSendingDocument.value = false
                    _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                    onResult(false)
                    return@launch
                }
                val previewQueued = queueOutgoingTextMessage(
                    sessionCode = code,
                    uuid = uuid,
                    text = buildSharedFileMessage(prepared)
                )
                _isSendingDocument.value = false
                if (!previewQueued) {
                    _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                    onResult(false)
                    return@launch
                }
                updateConnectionState(ChatConnectionState.Connected)
                flushQueuedTextMessages()
                onResult(true)
                return@launch
            }
            val metadataMessage = buildSharedFileMessage(prepared)
            val operation: (RfcommForegroundService) -> Unit = { srv ->
                srv.sendMessage(code, uuid, metadataMessage) { messageSent ->
                    if (!messageSent) {
                        _isSendingDocument.value = false
                        _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                        onResult(false)
                        return@sendMessage
                    }
                    srv.sendFileStream(
                        sessionCode = code,
                        uuid = uuid,
                        displayName = prepared.displayName,
                        mimeType = prepared.mimeType,
                        originalSizeBytes = prepared.originalSizeBytes,
                        compression = prepared.compression,
                        payload = prepared.payloadBytes
                    ) { success ->
                        _isSendingDocument.value = false
                        if (!success) {
                            updateLocalAttachmentDeliveryStatus(
                                uuid = uuid,
                                status = MessageDeliveryStatus.FAILED,
                                lastError = "DOCUMENT_SEND_FAILED"
                            )
                            _errorMessage.value = context.getString(R.string.chat_document_send_failed)
                            onResult(false)
                            return@sendFileStream
                        }
                        updateConnectionState(ChatConnectionState.Connected)
                        onResult(true)
                    }
                }
            }
            val currentService = service
            if (currentService == null) {
                pendingOperations.add(operation)
                ensureServiceBound()
            } else {
                operation(currentService)
            }
        }
    }

    fun onImageSelectionFailed() {
        _errorMessage.value = context.getString(R.string.chat_image_file_missing)
    }

    // ---- "peer is typing" indicator (internet transport only) ----

    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping.asStateFlow()
    private var typingExpiryJob: Job? = null
    private var lastTypingSignalAt = 0L

    init {
        viewModelScope.launch {
            TypingIndicatorBus.typing.collect { refreshPeerTyping(it) }
        }
        // Feed the local (Bluetooth-side) last-seen record: a live BT link or any incoming message
        // proves the peer was just alive, no server needed.
        viewModelScope.launch {
            connectionState.collect { state ->
                if (state == ChatConnectionState.Connected) {
                    sessionCode?.let { ContactLastSeenStore.record(context, it) }
                    refreshPeerPresence()
                }
            }
        }
        viewModelScope.launch {
            timelineItems.collect { items ->
                val lastRemote = items.lastOrNull { it is ChatTimelineItem.Msg && !it.message.isLocal }
                if (lastRemote != null) {
                    sessionCode?.let {
                        ContactLastSeenStore.record(context, it, lastRemote.timestampMillis)
                    }
                    refreshPeerPresence()
                }
            }
        }
    }

    // ---- peer presence: "çevrimiçi" / son görülme (internet heartbeat + local BT observations) ----

    data class PeerPresence(val online: Boolean = false, val lastSeenMillis: Long? = null)

    private val _peerPresence = MutableStateFlow(PeerPresence())
    val peerPresence: StateFlow<PeerPresence> = _peerPresence.asStateFlow()
    private var presenceRegistration: ListenerRegistration? = null
    private var presenceWatchedUid: String? = null
    private var presenceRefreshJob: Job? = null
    @Volatile private var remotePresenceMillis: Long? = null

    /** Live-watch the peer's presence doc (internet-identified contacts only). */
    private fun attachPresenceListener(contact: Contact?) {
        val uid = contact?.peerUid?.trim()?.takeIf { it.isNotBlank() }
        if (uid == null || presenceWatchedUid == uid) {
            refreshPeerPresence()
            return
        }
        presenceRegistration?.remove()
        presenceWatchedUid = uid
        presenceRegistration = runCatching {
            FirebaseFirestore.getInstance()
                .collection("presence")
                .document(uid)
                .addSnapshotListener { snapshot, _ ->
                    remotePresenceMillis = snapshot?.getLong("lastActiveAt")
                    refreshPeerPresence()
                }
        }.getOrNull()
    }

    private fun refreshPeerPresence() {
        val local = sessionCode?.let { ContactLastSeenStore.get(context, it) } ?: 0L
        val remote = remotePresenceMillis ?: 0L
        val last = maxOf(local, remote).takeIf { it > 0L }
        val now = System.currentTimeMillis()
        // Fresh within the heartbeat window (60s pulse + slack) counts as online.
        val online = last != null && now - last < 90_000L
        _peerPresence.value = PeerPresence(online = online, lastSeenMillis = last)
        presenceRefreshJob?.cancel()
        if (online) {
            // Re-evaluate the moment this stamp would age out of the online window.
            presenceRefreshJob = viewModelScope.launch {
                delay((last!! + 90_000L - now).coerceAtLeast(250L))
                refreshPeerPresence()
            }
        }
    }

    private fun refreshPeerTyping(map: Map<String, Long>) {
        val expiresAt = sessionCode?.let { map[it] }
        val now = System.currentTimeMillis()
        val active = expiresAt != null && expiresAt > now
        _isPeerTyping.value = active
        typingExpiryJob?.cancel()
        if (active) {
            // Re-evaluate when this pulse would expire (a fresh pulse just reschedules us).
            typingExpiryJob = viewModelScope.launch {
                delay((expiresAt!! - now).coerceAtLeast(50L))
                refreshPeerTyping(TypingIndicatorBus.typing.value)
            }
        }
    }

    /** Composer keystrokes → a throttled, sealed "typing" pulse to the peer (internet contacts only). */
    fun onComposerTyping(text: String) {
        if (text.isBlank()) return
        val contact = activeContact ?: return
        if (!contact.supportsInternet) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSignalAt < 4_000L) return // one pulse per 4s while typing keeps writes cheap
        lastTypingSignalAt = now
        if (!internetChatTransport.isAvailable()) return
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            runCatching { internetChatTransport.sendTypingSignal(contact) }
        }
    }

    fun onCameraPermissionDenied() {
        _errorMessage.value = context.getString(R.string.chat_camera_permission_required)
    }

    fun onRecordingPermissionDenied() {
        _errorMessage.value = context.getString(R.string.chat_voice_permission_required)
    }

    fun startCall() {
        if (!supportsCallTransport()) {
            _errorMessage.value = context.getString(R.string.chat_call_failed)
            return
        }
        val code = sessionCode ?: return
        if (!hasAudioPermission()) {
            onRecordingPermissionDenied()
            return
        }
        if (isPreferredP2pGattContact() && p2pGattChatManager.isReady()) {
            Analytics.callStarted("p2p_gatt")
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                val started = P2pCallController.shared(context).startCall(code)
                if (!started) {
                    _errorMessage.value = context.getString(R.string.chat_call_failed)
                }
            }
            return
        }
        // Online internet contact with no live Bluetooth link → place a WebRTC call over the E2E relay
        // (web-compatible signalling). Bluetooth calls above take precedence when a link is available.
        val internetContact = activeContact
        if (internetContact?.supportsInternet == true &&
            internetChatTransport.isAvailable() &&
            !isBluetoothLinkReady(code)
        ) {
            Analytics.callStarted("internet")
            InternetCallManager.startCall(internetContact)
            return
        }
        Analytics.callStarted("bluetooth")
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.startVoipCall(code) { success ->
                if (!success) {
                    attemptGattCallFallback(code)
                }
            }
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    /**
     * RFCOMM-first fallback: when the classic Bluetooth call cannot be set up but this
     * contact's P2P GATT link happens to be ready, retry the call over GATT — mirroring how
     * messaging falls back to the same link.
     */
    private fun attemptGattCallFallback(code: String) {
        if (!p2pGattChatManager.isReady()) {
            _errorMessage.value = context.getString(R.string.chat_call_failed)
            return
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val started = P2pCallController.shared(context).startCall(code)
            if (!started) {
                _errorMessage.value = context.getString(R.string.chat_call_failed)
            }
        }
    }

    fun acceptCall(callId: String) {
        val code = sessionCode ?: return
        if (!hasAudioPermission()) {
            onRecordingPermissionDenied()
            return
        }
        if (isPreferredP2pGattContact()) {
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                if (!P2pCallController.shared(context).acceptCall(code, callId)) {
                    _errorMessage.value = context.getString(R.string.chat_call_failed)
                }
            }
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.acceptIncomingCall(code, callId) { success ->
                if (!success) {
                    _errorMessage.value = context.getString(R.string.chat_call_failed)
                }
            }
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun rejectCall(callId: String) {
        val code = sessionCode ?: return
        if (isPreferredP2pGattContact()) {
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                P2pCallController.shared(context).rejectCall(code, callId)
            }
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.rejectIncomingCall(code, callId)
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun hangupCall(callId: String) {
        val code = sessionCode ?: return
        if (isPreferredP2pGattContact()) {
            viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
                P2pCallController.shared(context).endCall(code)
            }
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.endVoipCall(code, callId)
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    // ---- Internet (WebRTC) call controls — driven by the internet-call overlay, not the Bluetooth
    // call UI. They act on [InternetCallManager] whose state is exposed via [internetCall]. ----

    fun acceptInternetCall() {
        if (!hasAudioPermission()) {
            onRecordingPermissionDenied()
            return
        }
        InternetCallManager.accept()
    }

    fun rejectInternetCall() {
        InternetCallManager.reject()
    }

    fun endInternetCall() {
        InternetCallManager.end()
    }

    fun setInternetCallMuted(muted: Boolean) {
        InternetCallManager.setMuted(muted)
    }

    fun setSpeakerEnabled(enabled: Boolean) {
        setAudioRoute(
            if (enabled) {
                CallAudioRoute.Speaker
            } else {
                CallAudioRoute.Earpiece
            }
        )
    }

    fun setAudioRoute(route: CallAudioRoute) {
        val code = sessionCode ?: return
        val call = _activeCall.value ?: return
        if (route !in call.availableRoutes) {
            return
        }
        _activeCall.value = call.copy(
            speakerEnabled = route == CallAudioRoute.Speaker,
            currentRoute = route
        )
        if (isPreferredP2pGattContact()) {
            P2pCallController.shared(context)
                .setSpeakerEnabled(code, route == CallAudioRoute.Speaker)
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.setCallAudioRoute(code, route)
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun setMicMuted(muted: Boolean) {
        val code = sessionCode ?: return
        _activeCall.value = _activeCall.value?.copy(muted = muted)
        if (isPreferredP2pGattContact()) {
            P2pCallController.shared(context).setMuted(code, muted)
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            srv.setCallMicMuted(code, muted)
        }
        val currentService = service
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
        } else {
            operation(currentService)
        }
    }

    fun onMessagesVisible(messageUuids: Set<String>) {
        if (messageUuids.isEmpty()) {
            return
        }
        viewModelScope.launch(exceptionHandler) {
            markMessagesAsRead(context, messageUuids)
        }
        val code = sessionCode ?: return
        if (shouldAwaitBleContactResolution(code)) {
            rememberPendingP2pReadReceipts(messageUuids)
            requestConnection(code)
            return
        }
        val contact = activeContact
        if (isPreferredP2pGattContact() && contact != null) {
            viewModelScope.launch(exceptionHandler) {
                val sent = p2pGattChatManager.sendReadReceipt(contact, messageUuids)
                if (sent) {
                    forgetPendingP2pReadReceipts(messageUuids)
                } else {
                    rememberPendingP2pReadReceipts(messageUuids)
                    requestConnection(code)
                }
            }
            return
        }
        val currentService = service
        val rfcommReady = currentService?.isSessionActive(code) == true
        if (rfcommReady) {
            messageUuids.forEach { uuid ->
                currentService.acknowledgeMessage(code, uuid)
            }
            return
        }
        // No Bluetooth link but the peer is reachable over the internet → E2E read receipt via the
        // relay (same transport preference as outgoing messages: Bluetooth first, then internet).
        val internetReceiptContact = activeContact
        if (internetReceiptContact != null &&
            internetReceiptContact.supportsInternet &&
            !isBluetoothLinkReady(code) &&
            internetChatTransport.isAvailable()
        ) {
            sendInternetReadReceipts(internetReceiptContact, messageUuids)
            return
        }
        val address = activeBleFallbackAddress()
        if (isBleFallbackAllowed(code) && !address.isNullOrBlank()) {
            val normalizedAddress = normalizeBleAddress(address)
            viewModelScope.launch(exceptionHandler) {
                val sentViaBleFallback = sendReadReceiptViaBleFallback(
                    address = normalizedAddress,
                    sessionCode = code,
                    messageIds = messageUuids
                )
                if (sentViaBleFallback) {
                    return@launch
                }
                requestBleFallbackBootstrap()
                val fallbackService = service
                val operation: (RfcommForegroundService) -> Unit = { srv ->
                    messageUuids.forEach { uuid ->
                        srv.acknowledgeMessage(code, uuid)
                    }
                }
                if (fallbackService == null) {
                    pendingOperations.add(operation)
                    ensureServiceBound()
                } else {
                    operation(fallbackService)
                }
            }
            return
        }
        val operation: (RfcommForegroundService) -> Unit = { srv ->
            messageUuids.forEach { uuid ->
                srv.acknowledgeMessage(code, uuid)
            }
        }
        if (currentService == null) {
            pendingOperations.add(operation)
            ensureServiceBound()
            return
        }
        operation(currentService)
    }

    /**
     * Sends "read" for [messageUuids] (plus any earlier ids the relay hasn't accepted yet) over the
     * internet transport. Failed batches stay pending and are merged into the next visible-message
     * event, so a flaky connection re-tries instead of silently dropping the receipt.
     */
    private fun sendInternetReadReceipts(contact: Contact, messageUuids: Collection<String>) {
        val batch: List<String>
        synchronized(pendingInternetReadReceiptIds) {
            messageUuids.forEach { uuid ->
                uuid.trim().takeIf { it.isNotEmpty() }?.let(pendingInternetReadReceiptIds::add)
            }
            batch = pendingInternetReadReceiptIds.toList()
        }
        if (batch.isEmpty()) {
            return
        }
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            val sent = runCatching {
                internetChatTransport.sendReceipt(
                    contact = contact,
                    templateCode = InternetChatTransport.READ_RECEIPT_TEMPLATE,
                    messageIds = batch
                )
            }.getOrDefault(false)
            if (sent) {
                synchronized(pendingInternetReadReceiptIds) {
                    pendingInternetReadReceiptIds.removeAll(batch.toSet())
                }
            }
        }
    }

    private suspend fun sendReadReceiptViaBleFallback(
        address: String,
        sessionCode: String,
        messageIds: Collection<String>
    ): Boolean {
        if (messageIds.isEmpty()) {
            return true
        }
        val normalizedAddress = normalizeBleAddress(address)
        val normalizedSessionCode = sessionCode.trim()
        val sosService = sosServiceBinding.service.value
        if (sosService != null) {
            sosService.registerSessionAlias(normalizedAddress, normalizedSessionCode)
            val serverSent = runCatching {
                sosService.sendReadReceipt(normalizedAddress, messageIds)
            }.getOrDefault(false)
            if (serverSent) {
                return true
            }
        }
        if (!isBleClientFallbackReady(normalizedAddress)) {
            ensureBleFallbackClientConnection(normalizedAddress)
            return false
        }
        val currentService = service
        if (currentService == null) {
            ensureServiceBound()
            return false
        }
        val clientSent = runCatching {
            currentService.sendBleFallbackReadReceiptAwait(normalizedAddress, messageIds)
        }.getOrDefault(false)
        if (!clientSent) {
            ensureBleFallbackClientConnection(normalizedAddress)
        }
        return clientSent
    }

    private fun normalizePendingP2pReadReceiptIds(messageUuids: Collection<String>): Set<String> {
        if (messageUuids.isEmpty()) {
            return emptySet()
        }
        val normalized = linkedSetOf<String>()
        messageUuids.forEach { rawId ->
            val normalizedId = rawId.trim()
            if (normalizedId.isNotEmpty()) {
                normalized += normalizedId
            }
        }
        return normalized
    }

    private fun rememberPendingP2pReadReceipts(messageUuids: Collection<String>) {
        pendingP2pReadReceiptIds += normalizePendingP2pReadReceiptIds(messageUuids)
    }

    private fun forgetPendingP2pReadReceipts(messageUuids: Collection<String>) {
        val normalizedIds = normalizePendingP2pReadReceiptIds(messageUuids)
        if (normalizedIds.isEmpty()) {
            return
        }
        pendingP2pReadReceiptIds.removeAll(normalizedIds)
    }

    private fun flushPendingP2pReadReceipts() {
        if (isFlushingPendingP2pReadReceipts || pendingP2pReadReceiptIds.isEmpty()) {
            return
        }
        val code = sessionCode ?: return
        val contact = activeContact ?: return
        if (!isPreferredP2pGattContact()) {
            return
        }
        val pendingIds = pendingP2pReadReceiptIds.toList()
        if (pendingIds.isEmpty()) {
            return
        }
        isFlushingPendingP2pReadReceipts = true
        viewModelScope.launch(exceptionHandler) {
            try {
                val sent = p2pGattChatManager.sendReadReceipt(contact, pendingIds)
                if (sent) {
                    pendingP2pReadReceiptIds.removeAll(pendingIds.toSet())
                } else {
                    requestConnection(code)
                }
            } finally {
                isFlushingPendingP2pReadReceipts = false
                if (pendingP2pReadReceiptIds.isNotEmpty()) {
                    flushPendingP2pReadReceipts()
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    private fun flushQueuedTextMessages() {
        val code = sessionCode ?: return
        if (flushPendingTextJob?.isActive == true) {
            return
        }
        flushPendingTextJob = viewModelScope.launch(exceptionHandler) {
            val now = System.currentTimeMillis()
            val queuedMessages = _messages.value
                .asSequence()
                .filter { message ->
                    message.sessionCode == code &&
                        message.isLocal &&
                        message.messageType == MessageType.TEXT &&
                        message.text.isNotBlank() &&
                        message.deliveryStatus == MessageDeliveryStatus.QUEUED &&
                        (message.nextRetryAtMillis == null || message.nextRetryAtMillis <= now)
                }
                .sortedBy { it.timestampMillis }
                .toList()
            if (queuedMessages.isEmpty()) {
                schedulePendingTextRetry()
                return@launch
            }
            // Bluetooth is the preferred transport: when the BT link is up, send over Bluetooth ONLY.
            // Use the internet only when Bluetooth is NOT connected. If NEITHER is available the
            // message stays queued (store-and-forward) and drains when a transport comes back.
            val bluetoothReady = isBluetoothLinkReady(code)
            val internetContact = activeContact
            // Empty here so the Bluetooth-branch "keep SENT if internet delivered it" guards below are
            // safe no-ops (the internet pass returns before reaching them).
            val internetDelivered = emptySet<String>()
            if (internetContact != null &&
                internetContact.supportsInternet &&
                internetChatTransport.isAvailable() &&
                !bluetoothReady
            ) {
                var anyInternetSent = false
                for (message in queuedMessages) {
                    val attempt = (message.retryCount + 1).coerceAtLeast(1)
                    val lastAttemptAt = System.currentTimeMillis()
                    updateOutgoingTextState(
                        uuid = message.messageUuid,
                        status = MessageDeliveryStatus.SENDING,
                        retryCount = message.retryCount,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = lastAttemptAt,
                        lastError = null,
                        outboundRoute = OUTBOUND_ROUTE_INTERNET
                    )
                    val sent = internetChatTransport.sendText(
                        messageId = message.messageUuid,
                        text = message.text,
                        createdAtMs = message.timestampMillis,
                        contact = internetContact
                    )
                    if (sent) {
                        anyInternetSent = true
                        updateOutgoingTextState(
                            uuid = message.messageUuid,
                            status = MessageDeliveryStatus.SENT,
                            retryCount = attempt,
                            nextRetryAtMillis = null,
                            lastAttemptAtMillis = lastAttemptAt,
                            lastError = null,
                            outboundRoute = OUTBOUND_ROUTE_INTERNET
                        )
                    } else {
                        val exhausted = attempt >= MAX_TEXT_SEND_ATTEMPTS
                        val nextRetryAt = if (exhausted) null else computeNextRetryAtMillis(attempt)
                        updateOutgoingTextState(
                            uuid = message.messageUuid,
                            status = if (exhausted) MessageDeliveryStatus.FAILED else MessageDeliveryStatus.QUEUED,
                            retryCount = attempt,
                            nextRetryAtMillis = nextRetryAt,
                            lastAttemptAtMillis = lastAttemptAt,
                            lastError = TEXT_ERROR_SEND_FAILED,
                            outboundRoute = OUTBOUND_ROUTE_INTERNET
                        )
                    }
                }
                if (anyInternetSent) {
                    markInternetCarrying()
                }
                schedulePendingTextRetry()
                return@launch
            }
            // Bluetooth-carried pass; reflect that on the badge — but only when Bluetooth is actually
            // up (this pass is also reached when NEITHER transport is available and messages just
            // stay queued; flipping the badge to Bluetooth there was a flicker source).
            if (bluetoothReady) {
                markBluetoothCarrying()
            }
            if (isPreferredP2pGattContact()) {
                val contact = activeContact
                if (contact == null) {
                    schedulePendingTextRetry()
                    return@launch
                }
                for (message in queuedMessages) {
                    if (!p2pGattChatManager.isReady()) {
                        // GATT not ready — keep internet-delivered messages SENT; only re-queue the rest.
                        if (!internetDelivered.contains(message.messageUuid)) {
                            val retryAt = System.currentTimeMillis() + TEXT_TRANSPORT_WAIT_RETRY_DELAY_MS
                            updateOutgoingTextState(
                                uuid = message.messageUuid,
                                status = MessageDeliveryStatus.QUEUED,
                                retryCount = message.retryCount,
                                nextRetryAtMillis = retryAt,
                                lastAttemptAtMillis = null,
                                lastError = TEXT_ERROR_TRANSPORT_UNAVAILABLE,
                                outboundRoute = OUTBOUND_ROUTE_P2P_BLE_GATT
                            )
                        }
                        requestConnection(code)
                        break
                    }
                    val attempt = (message.retryCount + 1).coerceAtLeast(1)
                    val lastAttemptAt = System.currentTimeMillis()
                    updateOutgoingTextState(
                        uuid = message.messageUuid,
                        status = MessageDeliveryStatus.SENDING,
                        retryCount = message.retryCount,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = lastAttemptAt,
                        lastError = null,
                        outboundRoute = OUTBOUND_ROUTE_P2P_BLE_GATT
                    )
                    val sent = p2pGattChatManager.sendText(message, contact)
                    if (sent) {
                        updateOutgoingTextState(
                            uuid = message.messageUuid,
                            status = MessageDeliveryStatus.SENT,
                            retryCount = attempt,
                            nextRetryAtMillis = null,
                            lastAttemptAtMillis = lastAttemptAt,
                            lastError = null,
                            outboundRoute = OUTBOUND_ROUTE_P2P_BLE_GATT
                        )
                        updateConnectionState(ChatConnectionState.Connected)
                        continue
                    }
                    // Bluetooth (GATT) failed — but if the internet already carried it, keep it SENT.
                    if (internetDelivered.contains(message.messageUuid)) {
                        continue
                    }
                    val exhausted = attempt >= MAX_TEXT_SEND_ATTEMPTS
                    val nextRetryAt = if (exhausted) null else computeNextRetryAtMillis(attempt)
                    updateOutgoingTextState(
                        uuid = message.messageUuid,
                        status = if (exhausted) MessageDeliveryStatus.FAILED else MessageDeliveryStatus.QUEUED,
                        retryCount = attempt,
                        nextRetryAtMillis = nextRetryAt,
                        lastAttemptAtMillis = lastAttemptAt,
                        lastError = TEXT_ERROR_SEND_FAILED,
                        outboundRoute = OUTBOUND_ROUTE_P2P_BLE_GATT
                    )
                    if (!exhausted) {
                        requestConnection(code)
                    }
                }
                schedulePendingTextRetry()
                return@launch
            }
            for (message in queuedMessages) {
                val currentService = service
                val rfcommReady = currentService?.isSessionActive(code) == true
                val address = activeBleFallbackAddress()
                val bleFallbackReady = if (isBleFallbackAllowed(code) && !address.isNullOrBlank()) {
                    isBleFallbackTransportReady(address, registerAlias = true)
                } else {
                    false
                }
                if (!rfcommReady && !bleFallbackReady) {
                    val retryAt = System.currentTimeMillis() + TEXT_TRANSPORT_WAIT_RETRY_DELAY_MS
                    queuedMessages.forEach { queued ->
                        // Keep internet-delivered messages SENT; only re-queue what needs Bluetooth.
                        if (!internetDelivered.contains(queued.messageUuid)) {
                            updateOutgoingTextState(
                                uuid = queued.messageUuid,
                                status = MessageDeliveryStatus.QUEUED,
                                retryCount = queued.retryCount,
                                nextRetryAtMillis = retryAt,
                                lastAttemptAtMillis = null,
                                lastError = TEXT_ERROR_TRANSPORT_UNAVAILABLE,
                                outboundRoute = queued.outboundRoute
                            )
                        }
                    }
                    requestConnection(code)
                    requestBleFallbackBootstrap()
                    break
                }
                val attempt = (message.retryCount + 1).coerceAtLeast(1)
                val lastAttemptAt = System.currentTimeMillis()
                val outboundRoute = if (rfcommReady) {
                    OUTBOUND_ROUTE_RFCOMM
                } else {
                    OUTBOUND_ROUTE_BLE_GATT
                }
                updateOutgoingTextState(
                    uuid = message.messageUuid,
                    status = MessageDeliveryStatus.SENDING,
                    retryCount = message.retryCount,
                    nextRetryAtMillis = null,
                    lastAttemptAtMillis = lastAttemptAt,
                    lastError = null,
                    outboundRoute = outboundRoute
                )
                val sent = if (rfcommReady) {
                    sendTextMessageAwait(
                        service = currentService,
                        sessionCode = code,
                        uuid = message.messageUuid,
                        text = message.text
                    )
                } else {
                    if (address.isNullOrBlank()) {
                        false
                    } else {
                        sendTextMessageViaBleFallback(
                            address = address,
                            sessionCode = code,
                            messageId = message.messageUuid,
                            text = message.text,
                            createdAtMillis = message.timestampMillis,
                            attempt = attempt
                        )
                    }
                }
                if (sent) {
                    updateOutgoingTextState(
                        uuid = message.messageUuid,
                        status = MessageDeliveryStatus.SENT,
                        retryCount = attempt,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = lastAttemptAt,
                        lastError = null,
                        outboundRoute = outboundRoute
                    )
                    updateConnectionState(ChatConnectionState.Connected)
                    refreshBleFallbackRouteState()
                    continue
                }
                // Bluetooth (RFCOMM/BLE fallback) failed — keep it SENT if the internet carried it.
                if (internetDelivered.contains(message.messageUuid)) {
                    continue
                }
                val exhausted = attempt >= MAX_TEXT_SEND_ATTEMPTS
                val nextRetryAt = if (exhausted) null else computeNextRetryAtMillis(attempt)
                updateOutgoingTextState(
                    uuid = message.messageUuid,
                    status = if (exhausted) MessageDeliveryStatus.FAILED else MessageDeliveryStatus.QUEUED,
                    retryCount = attempt,
                    nextRetryAtMillis = nextRetryAt,
                    lastAttemptAtMillis = lastAttemptAt,
                    lastError = TEXT_ERROR_SEND_FAILED,
                    outboundRoute = outboundRoute
                )
                if (!exhausted) {
                    requestConnection(code)
                    requestBleFallbackBootstrap()
                }
            }
            schedulePendingTextRetry()
        }
    }

    /**
     * Store-and-forward promptness: when the OS reports a network became available, immediately
     * retry any queued text messages instead of waiting out the exponential-backoff timer. This
     * is what lets a message queued while offline go out over the internet the moment it returns.
     */
    private fun registerConnectivityFlushIfNeeded() {
        if (networkCallback != null) return
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                viewModelScope.launch(exceptionHandler) {
                    resetQueuedRetryScheduleNow()
                    flushQueuedTextMessages()
                }
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        if (registered) {
            networkCallback = callback
        }
    }

    private fun unregisterConnectivityFlush() {
        val cm = connectivityManager
        val callback = networkCallback
        if (cm != null && callback != null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
    }

    /** Clears the backoff timer on queued messages so the next flush attempts them right away. */
    private suspend fun resetQueuedRetryScheduleNow() {
        val code = sessionCode ?: return
        val queued = _messages.value.filter { message ->
            message.sessionCode == code &&
                message.isLocal &&
                message.messageType == MessageType.TEXT &&
                message.text.isNotBlank() &&
                message.deliveryStatus == MessageDeliveryStatus.QUEUED &&
                message.nextRetryAtMillis != null
        }
        for (message in queued) {
            updateOutgoingTextState(
                uuid = message.messageUuid,
                status = MessageDeliveryStatus.QUEUED,
                retryCount = message.retryCount,
                nextRetryAtMillis = null,
                lastAttemptAtMillis = message.lastAttemptAtMillis,
                lastError = message.lastError,
                outboundRoute = message.outboundRoute
            )
        }
    }

    /**
     * Compute the safety number off the main thread (the v3 fingerprint opens the DB + runs a
     * 5200-iteration hash) and publish it + whether it's the forward-secret (v3) one. Prefers the
     * v3 Signal fingerprint once a session identity exists, else the interim v2 P-256 number.
     */
    private fun updateSafetyNumber(contact: Contact?) {
        if (contact == null || !contact.supportsInternet) {
            _safetyNumber.value = null
            _safetyNumberForwardSecret.value = false
            return
        }
        val myUid = FirebaseAuth.getInstance().currentUser?.uid
        if (myUid == null) {
            _safetyNumber.value = null
            _safetyNumberForwardSecret.value = false
            return
        }
        viewModelScope.launch(exceptionHandler) {
            val result = withContext(Dispatchers.IO) {
                // v3 (forward-secret) fingerprint first; null means no Signal session identity yet.
                runCatching {
                    SignalSessionGate.create(context).safetyNumber(myUid, contact.peerUid)
                }.getOrNull()?.let { return@withContext it to true }
                runCatching {
                    SafetyNumber.compute(
                        localPublicKeyB64 = MessagingIdentity(context).publicKeyBase64(),
                        remotePublicKeyB64 = contact.peerPublicKey,
                        localUid = myUid,
                        remoteUid = contact.peerUid
                    )
                }.getOrNull()?.let { it to false }
            }
            _safetyNumber.value = result?.first
            _safetyNumberForwardSecret.value = result?.second == true
        }
    }

    /** User re-confirmed the peer after a key change — clear the TOFU warning. */
    fun acknowledgePeerKeyChange() {
        val code = sessionCode ?: return
        _peerKeyChanged.value = false
        viewModelScope.launch(exceptionHandler) {
            withContext(Dispatchers.IO) {
                acknowledgePeerKeyChange(context, code)
            }
        }
    }

    private fun schedulePendingTextRetry() {
        val code = sessionCode ?: return
        val now = System.currentTimeMillis()
        val nextRetryAt = _messages.value
            .asSequence()
            .filter { message ->
                message.sessionCode == code &&
                    message.isLocal &&
                    message.messageType == MessageType.TEXT &&
                    message.text.isNotBlank() &&
                    message.deliveryStatus == MessageDeliveryStatus.QUEUED
            }
            .map { message -> message.nextRetryAtMillis ?: now }
            .minOrNull() ?: run {
            retryPendingTextJob?.cancel()
            retryPendingTextJob = null
            retryScheduledAtMillis = null
            return
        }
        val delayMs = (nextRetryAt - now).coerceAtLeast(TEXT_RETRY_MIN_DELAY_MS)
        val scheduledAt = now + delayMs
        val existingAt = retryScheduledAtMillis
        if (
            retryPendingTextJob?.isActive == true &&
            existingAt != null &&
            kotlin.math.abs(existingAt - scheduledAt) <= TEXT_RETRY_SCHEDULE_TOLERANCE_MS
        ) {
            return
        }
        retryPendingTextJob?.cancel()
        retryScheduledAtMillis = scheduledAt
        retryPendingTextJob = viewModelScope.launch(exceptionHandler) {
            delay(delayMs)
            retryScheduledAtMillis = null
            flushQueuedTextMessages()
        }
    }

    private suspend fun updateOutgoingTextState(
        uuid: String,
        status: MessageDeliveryStatus,
        retryCount: Int,
        nextRetryAtMillis: Long?,
        lastAttemptAtMillis: Long?,
        lastError: String?,
        outboundRoute: String?
    ) {
        updateLocalMessageDeliveryState(
            context = context,
            uuid = uuid,
            deliveryStatus = status,
            retryCount = retryCount,
            nextRetryAtMillis = nextRetryAtMillis,
            lastAttemptAtMillis = lastAttemptAtMillis,
            lastError = lastError,
            outboundRoute = outboundRoute
        )
    }

    private suspend fun sendTextMessageAwait(
        service: RfcommForegroundService,
        sessionCode: String,
        uuid: String,
        text: String
    ): Boolean {
        return suspendCancellableCoroutine { continuation ->
            service.sendMessage(sessionCode, uuid, text) { success ->
                if (continuation.isActive) {
                    continuation.resume(success)
                }
            }
        }
    }

    private suspend fun sendTextMessageViaBleFallback(
        address: String,
        sessionCode: String,
        messageId: String,
        text: String,
        createdAtMillis: Long,
        attempt: Int
    ): Boolean {
        val normalizedAddress = normalizeBleAddress(address)
        val session = sessionCode.trim()
        val payload = BleChatEnvelope.encodeChat(
            messageId = messageId,
            text = text,
            createdAtMillis = createdAtMillis,
            ttlMillis = TEXT_MESSAGE_TTL_MS,
            attempt = attempt,
            route = OUTBOUND_ROUTE_BLE_GATT
        )

        val sosService = sosServiceBinding.service.value
        if (sosService != null) {
            sosService.registerSessionAlias(normalizedAddress, session)
            if (sosService.isChatReady(normalizedAddress)) {
                val serverSent = sosService.sendChatMessage(normalizedAddress, payload)
                if (serverSent) {
                    return true
                }
            }
        }

        if (!isBleClientFallbackReady(normalizedAddress)) {
            ensureBleFallbackClientConnection(normalizedAddress)
            return false
        }

        val currentService = service
        if (currentService == null) {
            ensureServiceBound()
            return false
        }
        val clientSent = currentService.sendBleFallbackMessageAwait(normalizedAddress, payload)
        if (!clientSent) {
            ensureBleFallbackClientConnection(normalizedAddress)
        }
        return clientSent
    }

    private fun isBleFallbackTransportReady(
        address: String,
        registerAlias: Boolean
    ): Boolean {
        if (!isBleFallbackAllowed()) {
            return false
        }
        val normalizedAddress = normalizeBleAddress(address)
        val allowBleClientFallback = shouldAttemptBleClientFallbackClient()
        if (!allowBleClientFallback) {
            disconnectBleFallbackClient(normalizedAddress)
        }
        val code = sessionCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val sosService = sosServiceBinding.service.value
        var serverReady = false
        if (sosService != null) {
            if (registerAlias) {
                sosService.registerSessionAlias(normalizedAddress, code)
            }
            serverReady = sosService.isChatReady(normalizedAddress)
        }
        val clientReady = if (allowBleClientFallback) {
            isBleClientFallbackReady(normalizedAddress)
        } else {
            false
        }
        if (allowBleClientFallback && !serverReady && !clientReady) {
            ensureBleFallbackClientConnection(normalizedAddress)
        }
        return serverReady || clientReady
    }

    private fun requestBleFallbackBootstrap() {
        if (isPreferredP2pGattContact() || shouldAwaitBleContactResolution()) {
            _isBleFallbackActive.value = false
            return
        }
        if (!isBleFallbackAllowed()) {
            return
        }
        sosServiceBinding.bind()
        activeBleFallbackAddress()?.let { address ->
            if (shouldAttemptBleClientFallbackClient()) {
                ensureBleFallbackClientConnection(address)
            } else {
                disconnectBleFallbackClient(address)
            }
        }
        refreshBleFallbackRouteState()
    }

    private fun observeSosBinding() {
        sosBindingJob?.cancel()
        sosBindingJob = viewModelScope.launch(exceptionHandler) {
            sosServiceBinding.service.collect {
                val address = activeBleFallbackAddress()
                val code = sessionCode
                if (it != null && !address.isNullOrBlank() && !code.isNullOrBlank()) {
                    it.registerSessionAlias(address, code)
                }
                refreshBleFallbackRouteState()
                flushQueuedTextMessages()
            }
        }
    }

    private fun observeBleFallbackClientState(boundService: RfcommForegroundService) {
        bleClientStateJob?.cancel()
        bleClientStateJob = viewModelScope.launch(exceptionHandler) {
            boundService.bleFallbackConnectionStates.collect { state ->
                // Consecutive duplicates carry no new information; dropping them keeps a GATT
                // failure storm from monopolising the main thread.
                val stateKey = "${normalizeBleAddress(state.address)}|${state.status}"
                if (stateKey == lastObservedBleClientStateKey) {
                    return@collect
                }
                lastObservedBleClientStateKey = stateKey
                if (isPreferredP2pGattContact() || shouldAwaitBleContactResolution()) {
                    refreshBleFallbackRouteState()
                    return@collect
                }
                val activeAddress = activeBleFallbackAddress() ?: return@collect
                val normalizedStateAddress = normalizeBleAddress(state.address)
                if (normalizeBleAddress(activeAddress) != normalizedStateAddress) {
                    return@collect
                }
                val status = state.status
                if (
                    status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Failed ||
                    status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Disconnected
                ) {
                    if (lastBleFallbackConnectAddress == normalizedStateAddress) {
                        lastBleFallbackConnectAddress = null
                    }
                }
                val code = sessionCode
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                val rfcommReady = code?.let(boundService::isSessionActive) == true
                val serverReady = sosServiceBinding.service.value?.let { sosService ->
                    if (code != null) {
                        sosService.registerSessionAlias(normalizedStateAddress, code)
                    }
                    sosService.isChatReady(normalizedStateAddress)
                } ?: false

                when (status) {
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Ready -> {
                        // Bluetooth (BLE fallback) linked up — take over from the internet background
                        // (the badge waits out the stability window when the internet is active).
                        hasConnectedAtLeastOnce = true
                        connectionInProgress = false
                        pendingConnection = false
                        onBluetoothLinkUp()
                        reconnectJob?.cancel()
                        reconnectJob = null
                        flushQueuedTextMessages()
                    }

                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connecting,
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connected,
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Discovering,
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Authenticating,
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Reconnecting -> {
                        if (rfcommReady || serverReady) {
                            hasConnectedAtLeastOnce = true
                            connectionInProgress = false
                            pendingConnection = false
                            updateConnectionState(ChatConnectionState.Connected)
                        } else {
                            updateConnectionState(ChatConnectionState.Connecting)
                        }
                    }

                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Failed,
                    com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Disconnected -> {
                        if (rfcommReady || serverReady) {
                            hasConnectedAtLeastOnce = true
                            connectionInProgress = false
                            pendingConnection = false
                            updateConnectionState(ChatConnectionState.Connected)
                        } else {
                            cancelBluetoothTakeover()
                            if (!internetActiveConnected && switchToInternetIfAvailable()) {
                                // Peer reachable online — the internet carries the chat while BT retries.
                            } else if (connectionInProgress || pendingConnection) {
                                updateConnectionState(ChatConnectionState.Connecting)
                            } else {
                                updateConnectionState(ChatConnectionState.Error)
                            }
                        }
                    }
                }
                refreshBleFallbackRouteState()
            }
        }
    }

    private fun isBleClientFallbackReady(address: String): Boolean {
        if (!shouldAttemptBleClientFallbackClient()) {
            return false
        }
        val normalizedAddress = normalizeBleAddress(address)
        val state = service?.currentBleFallbackState(normalizedAddress)
        return state?.status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Ready
    }

    private fun isBleClientFallbackInProgress(address: String): Boolean {
        if (!shouldAttemptBleClientFallbackClient()) {
            return false
        }
        val normalizedAddress = normalizeBleAddress(address)
        val status = service?.currentBleFallbackState(normalizedAddress)?.status ?: return false
        return status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connecting ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connected ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Discovering ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Authenticating ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Reconnecting
    }

    private fun ensureBleFallbackClientConnection(address: String) {
        if (!shouldAttemptBleClientFallbackClient()) {
            disconnectBleFallbackClient(address)
            return
        }
        val normalizedAddress = normalizeBleAddress(address)
        val currentService = service
        if (currentService == null) {
            ensureServiceBound()
            return
        }
        val status = currentService.currentBleFallbackState(normalizedAddress)?.status
        if (
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Ready ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connecting ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Connected ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Discovering ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Authenticating ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Reconnecting
        ) {
            return
        }
        sessionCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { code ->
                currentService.registerBleFallbackSessionAlias(normalizedAddress, code)
            }
        // Rate-limit VM-initiated connect kicks: BleClientManager already retries with its own
        // backoff, so hammering connectBleFallback from every state emission only feeds the
        // emit → refresh → connect → emit loop that froze the main thread.
        val now = System.currentTimeMillis()
        if (
            normalizedAddress == lastBleFallbackConnectAttemptAddress &&
            now - lastBleFallbackConnectAttemptAtMs < BLE_FALLBACK_CONNECT_COOLDOWN_MS
        ) {
            return
        }
        lastBleFallbackConnectAttemptAddress = normalizedAddress
        lastBleFallbackConnectAttemptAtMs = now
        if (lastBleFallbackConnectAddress != normalizedAddress) {
            lastBleFallbackConnectAddress = normalizedAddress
        }
        currentService.connectBleFallback(normalizedAddress)
    }

    private fun normalizeBleAddress(address: String): String {
        return normalizeMacAddress(address).ifBlank {
            address.trim().uppercase(Locale.US)
        }
    }

    private fun activeBleFallbackAddress(): String? {
        val rawAddress = if (isPreferredP2pGattContact()) {
            contactAddress
        } else {
            activeContact?.lastKnownBleAddress?.takeIf { it.isNotBlank() } ?: contactAddress
        }
        return rawAddress?.takeIf { it.isNotBlank() }?.let(::normalizeBleAddress)
    }

    private fun normalizeSessionCode(raw: String?): String? {
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun isTemporaryBleFallbackActive(code: String? = sessionCode): Boolean {
        val normalizedSessionCode = normalizeSessionCode(code) ?: return false
        val scopedSessionCode = temporaryBleFallbackSessionCode ?: return false
        if (!scopedSessionCode.equals(normalizedSessionCode, ignoreCase = true)) {
            return false
        }
        val expiresAtElapsedRealtime = temporaryBleFallbackUntilElapsedRealtimeMs ?: return false
        if (SystemClock.elapsedRealtime() >= expiresAtElapsedRealtime) {
            clearTemporaryBleFallbackWindow(normalizedSessionCode)
            return false
        }
        return true
    }

    private fun armTemporaryBleFallbackWindow(sessionCode: String) {
        val normalizedSessionCode = normalizeSessionCode(sessionCode) ?: return
        val expiresAtElapsedRealtime = SystemClock.elapsedRealtime() + AUTO_BLE_FALLBACK_WINDOW_MS
        temporaryBleFallbackSessionCode = normalizedSessionCode
        temporaryBleFallbackUntilElapsedRealtimeMs = expiresAtElapsedRealtime
        armBleClientFallback()
        scheduleTemporaryBleFallbackExpiry(normalizedSessionCode, expiresAtElapsedRealtime)
    }

    private fun clearTemporaryBleFallbackWindow(targetSessionCode: String? = sessionCode) {
        val normalizedSessionCode = normalizeSessionCode(targetSessionCode)
        if (
            normalizedSessionCode != null &&
            !temporaryBleFallbackSessionCode.equals(normalizedSessionCode, ignoreCase = true)
        ) {
            return
        }
        temporaryBleFallbackSessionCode = null
        temporaryBleFallbackUntilElapsedRealtimeMs = null
        temporaryBleFallbackExpiryJob?.cancel()
        temporaryBleFallbackExpiryJob = null
        disarmBleClientFallback()
    }

    private fun scheduleTemporaryBleFallbackExpiry(
        sessionCode: String,
        expiresAtElapsedRealtime: Long
    ) {
        temporaryBleFallbackExpiryJob?.cancel()
        temporaryBleFallbackExpiryJob = viewModelScope.launch(exceptionHandler) {
            val delayMs = (expiresAtElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(delayMs)
            if (
                !temporaryBleFallbackSessionCode.equals(sessionCode, ignoreCase = true) ||
                temporaryBleFallbackUntilElapsedRealtimeMs != expiresAtElapsedRealtime
            ) {
                return@launch
            }
            clearTemporaryBleFallbackWindow(sessionCode)
            if (!isHighRangeModeEnabled && this@ChatScreenViewModel.sessionCode == sessionCode) {
                disconnectTrackedBleFallbackConnections()
                sosServiceBinding.unbind()
            }
            refreshBleFallbackRouteState()
            refreshTransportCapabilities()
        }
    }

    private fun shouldAttemptBleClientFallbackClient(): Boolean {
        if (!isBleFallbackAllowed()) {
            return false
        }
        if (!bleClientFallbackArmed) {
            return false
        }
        return !shouldAvoidBleClientFallbackForActiveRfcomm()
    }

    private fun isPreferredP2pGattContact(): Boolean {
        return normalizePreferredTransport(activeContact?.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT
    }

    private fun shouldAwaitBleContactResolution(code: String? = sessionCode): Boolean {
        val normalizedCode = code
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return normalizedCode.startsWith("ble:", ignoreCase = true) && activeContact == null
    }

    private fun shouldAvoidBleClientFallbackForActiveRfcomm(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            return false
        }
        val code = sessionCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return false
        return service?.isSessionActive(code) == true
    }

    private fun disconnectBleFallbackClient(address: String) {
        val normalizedAddress = normalizeBleAddress(address)
        val currentService = service
        if (currentService == null) {
            if (lastBleFallbackConnectAddress == normalizedAddress) {
                lastBleFallbackConnectAddress = null
            }
            return
        }
        val status = currentService.currentBleFallbackState(normalizedAddress)?.status
        if (status == null ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Disconnected ||
            status == com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus.Failed
        ) {
            if (lastBleFallbackConnectAddress == normalizedAddress) {
                lastBleFallbackConnectAddress = null
            }
            return
        }
        currentService.disconnectBleFallback(normalizedAddress)
        if (lastBleFallbackConnectAddress == normalizedAddress) {
            lastBleFallbackConnectAddress = null
        }
    }

    private fun disconnectTrackedBleFallbackConnections() {
        val addresses = linkedSetOf<String>()
        activeBleFallbackAddress()?.let { addresses += it }
        lastBleFallbackConnectAddress?.takeIf { it.isNotBlank() }?.let { addresses += it }
        addresses.forEach { address -> disconnectBleFallbackClient(address) }
        lastBleFallbackConnectAddress = null
    }

    private fun armBleClientFallback() {
        bleClientFallbackArmed = true
    }

    private fun disarmBleClientFallback() {
        bleClientFallbackArmed = false
    }

    private fun isBleFallbackAllowed(code: String? = sessionCode): Boolean {
        return isHighRangeModeEnabled || isTemporaryBleFallbackActive(code)
    }

    private fun isRfcommTransportReady(code: String? = sessionCode): Boolean {
        val normalizedSessionCode = normalizeSessionCode(code) ?: return false
        return service?.isSessionActive(normalizedSessionCode) == true
    }

    /**
     * Voice / attachments / location lane: the ready Bluetooth link first, else the E2E internet
     * transport — the SAME precedence the send paths themselves use (they all carry internet
     * branches). Gating on BT alone left the mic + attach buttons dead in internet-only chats.
     */
    private fun supportsMediaTransport(): Boolean {
        val bluetoothReady = if (isPreferredP2pGattContact()) {
            p2pGattChatManager.isReady()
        } else {
            isRfcommTransportReady()
        }
        if (bluetoothReady) {
            return true
        }
        return activeContact?.supportsInternet == true && internetChatTransport.isAvailable()
    }

    private fun supportsVoiceMessaging(): Boolean = supportsMediaTransport()

    private fun supportsAttachmentTransport(): Boolean = supportsMediaTransport()

    private fun supportsLocationSharing(): Boolean = supportsMediaTransport()

    private fun supportsCallTransport(): Boolean {
        // GATT calls ride the same P2P link as GATT messaging (works cross-platform) — but ONLY while
        // that link is live. With no live GATT link we must NOT stop here (returning its readiness
        // greyed the call button out): fall through so an internet call is still offered, mirroring iOS
        // which falls back to WebRTC when the peer is reachable online.
        if (isPreferredP2pGattContact() && p2pGattChatManager.isReady()) {
            return true
        }
        if (isRfcommTransportReady()) {
            return true
        }
        // No Bluetooth link, but an online internet contact can be called over WebRTC (web-compatible).
        return activeContact?.supportsInternet == true && internetChatTransport.isAvailable()
    }

    private fun shouldShowCallActionForCurrentContact(): Boolean {
        if (activeContact != null) {
            return true
        }
        return sessionCode
            ?.trim()
            ?.startsWith("ble:", ignoreCase = true)
            ?.not()
            ?: true
    }

    private fun refreshTransportCapabilities() {
        if (isInScreenshotDemoMode) {
            // Demo mode has forced every capability to `true`; don't let a
            // real-service check flip them back and dim the top-bar actions.
            return
        }
        _canSendVoiceMessages.value = supportsVoiceMessaging()
        _canSendAttachments.value = supportsAttachmentTransport()
        _canShareLocation.value = supportsLocationSharing()
        _canPlaceCall.value = supportsCallTransport()
        _showCallAction.value = shouldShowCallActionForCurrentContact()
    }

    private fun refreshBleFallbackRouteState() {
        refreshTransportCapabilities()
        if (isPreferredP2pGattContact() || shouldAwaitBleContactResolution()) {
            _isBleFallbackActive.value = false
            return
        }
        val code = sessionCode
        val rfcommReady = if (code != null) {
            service?.isSessionActive(code) == true
        } else {
            false
        }
        val address = activeBleFallbackAddress()
        if (!address.isNullOrBlank() && shouldAvoidBleClientFallbackForActiveRfcomm()) {
            disconnectBleFallbackClient(address)
        }
        val bleReady = if (!address.isNullOrBlank()) {
            isBleFallbackTransportReady(address, registerAlias = true)
        } else {
            false
        }
        _isBleFallbackActive.value = isBleFallbackAllowed(code) && !rfcommReady && bleReady
    }

    /** True when the Bluetooth link (P2P GATT, RFCOMM, or BLE fallback) is up for this session. */
    private fun isBluetoothLinkReady(sessionCode: String, registerAlias: Boolean = true): Boolean {
        if (isPreferredP2pGattContact()) {
            return p2pGattChatManager.isReady()
        }
        if (service?.isSessionActive(sessionCode) == true) {
            return true
        }
        val address = activeBleFallbackAddress() ?: return false
        return isBleFallbackTransportReady(address, registerAlias = registerAlias)
    }

    private fun isTextTransportReady(sessionCode: String): Boolean {
        // Bluetooth first (preferred when the peers are linked); internet only as a fallback.
        if (isBluetoothLinkReady(sessionCode)) {
            return true
        }
        return activeContact?.supportsInternet == true && internetChatTransport.isAvailable()
    }

    private fun computeNextRetryAtMillis(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponent = (safeAttempt - 1).coerceAtMost(TEXT_RETRY_MAX_EXPONENT)
        val baseDelay = (TEXT_RETRY_BASE_DELAY_MS * (1L shl exponent))
            .coerceAtMost(TEXT_RETRY_MAX_DELAY_MS)
        val jitter = (baseDelay * TEXT_RETRY_JITTER_RATIO).toLong()
            .coerceAtLeast(TEXT_RETRY_MIN_JITTER_MS)
        val randomized = baseDelay + Random.nextLong(from = -jitter, until = jitter + 1L)
        return System.currentTimeMillis() + randomized.coerceAtLeast(TEXT_RETRY_MIN_DELAY_MS)
    }

    private fun handleVoiceSendResult(
        uuid: String,
        success: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        _isSendingVoice.value = false
        if (!success) {
            updateLocalAttachmentDeliveryStatus(
                uuid = uuid,
                status = MessageDeliveryStatus.FAILED,
                lastError = "VOICE_SEND_FAILED"
            )
            _errorMessage.value = context.getString(R.string.chat_voice_send_failed)
            onResult(false)
            return
        }
        updateLocalAttachmentDeliveryStatus(
            uuid = uuid,
            status = MessageDeliveryStatus.SENT
        )
        updateConnectionState(ChatConnectionState.Connected)
        cleanupRecording(deleteFile = true)
        onResult(true)
    }

    private fun handleImageSendResult(
        uuid: String,
        success: Boolean,
        onResult: (Boolean) -> Unit
    ) {
        _isSendingImage.value = false
        if (!success) {
            updateLocalAttachmentDeliveryStatus(
                uuid = uuid,
                status = MessageDeliveryStatus.FAILED,
                lastError = "IMAGE_SEND_FAILED"
            )
            _errorMessage.value = context.getString(R.string.chat_image_send_failed)
            onResult(false)
            return
        }
        updateLocalAttachmentDeliveryStatus(
            uuid = uuid,
            status = MessageDeliveryStatus.SENT
        )
        updateConnectionState(ChatConnectionState.Connected)
        onResult(true)
    }

    private suspend fun queueOutgoingTextMessage(
        sessionCode: String,
        uuid: String,
        text: String
    ): Boolean {
        return runCatching {
            upsertLocalTextMessage(
                context = context,
                sessionCode = sessionCode,
                uuid = uuid,
                text = text,
                deliveryStatus = MessageDeliveryStatus.QUEUED,
                retryCount = 0,
                nextRetryAtMillis = null,
                lastAttemptAtMillis = null,
                lastError = null,
                outboundRoute = null
            )
        }.isSuccess
    }

    private fun updateLocalAttachmentDeliveryStatus(
        uuid: String,
        status: MessageDeliveryStatus,
        lastError: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            updateLocalMessageDeliveryState(
                context = context,
                uuid = uuid,
                deliveryStatus = status,
                retryCount = 0,
                nextRetryAtMillis = null,
                lastAttemptAtMillis = System.currentTimeMillis(),
                lastError = lastError,
                outboundRoute = null
            )
        }
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    private fun cleanupRecording(deleteFile: Boolean) {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        recordingStartRealtime = 0L
        _isRecording.value = false
        _isSendingVoice.value = false
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
        if (isPreferredP2pGattContact()) {
            profiles += VoiceRecordingProfile(
                mimeType = VOICE_MIME_AAC,
                fileExtension = ".m4a",
                outputFormat = MediaRecorder.OutputFormat.MPEG_4,
                audioEncoder = MediaRecorder.AudioEncoder.AAC,
                sampleRate = 44_100,
                bitrate = 128_000
            )
            return profiles
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            profiles += VoiceRecordingProfile(
                mimeType = VOICE_MIME_OPUS,
                fileExtension = ".ogg",
                outputFormat = MediaRecorder.OutputFormat.OGG,
                audioEncoder = MediaRecorder.AudioEncoder.OPUS,
                sampleRate = 48_000,
                bitrate = 32_000
            )
        }
        profiles += VoiceRecordingProfile(
            mimeType = VOICE_MIME_AAC,
            fileExtension = ".m4a",
            outputFormat = MediaRecorder.OutputFormat.MPEG_4,
            audioEncoder = MediaRecorder.AudioEncoder.AAC,
            sampleRate = 44_100,
            bitrate = 128_000
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

    private data class ImagePreparationResult(
        val fileName: String,
        val thumbnailName: String?,
        val bytes: ByteArray,
        val width: Int?,
        val height: Int?,
        val mimeType: String
    )

    private fun prepareImageAttachment(
        uuid: String,
        uri: Uri,
        mimeType: String?,
        fallbackWidth: Int?,
        fallbackHeight: Int?
    ): ImagePreparationResult? {
        return prepareImageAttachmentForTransfer(
            context = context,
            uuid = uuid,
            uri = uri,
            mimeType = mimeType,
            fallbackWidth = fallbackWidth,
            fallbackHeight = fallbackHeight,
            profile = DEFAULT_CHAT_IMAGE_TRANSFER_PROFILE
        )?.let { prepared ->
            ImagePreparationResult(
                fileName = prepared.fileName,
                thumbnailName = prepared.thumbnailName,
                bytes = prepared.bytes,
                width = prepared.width,
                height = prepared.height,
                mimeType = prepared.mimeType
            )
        }
    }

    private fun normalizeImageMimeType(mimeType: String?): String {
        val normalized = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            normalized.contains("jpeg") || normalized.contains("jpg") -> IMAGE_MIME_JPEG
            normalized.contains("png") -> "image/png"
            normalized.contains("webp") -> "image/webp"
            normalized.contains("heic") -> "image/heic"
            normalized.contains("heif") -> "image/heif"
            normalized.startsWith("image/") -> normalized
            else -> IMAGE_MIME_JPEG
        }
    }

    private fun shouldOptimizeImageForTransfer(
        fileSizeBytes: Long,
        width: Int,
        height: Int,
        mimeType: String
    ): Boolean {
        val isJpeg = mimeType == IMAGE_MIME_JPEG
        if (fileSizeBytes >= IMAGE_TRANSFER_FORCE_OPTIMIZE_BYTES) {
            return true
        }
        if (width > IMAGE_TRANSFER_MAX_DIMENSION || height > IMAGE_TRANSFER_MAX_DIMENSION) {
            return true
        }
        return !isJpeg && fileSizeBytes >= IMAGE_TRANSFER_NON_JPEG_OPTIMIZE_BYTES
    }

    private fun selectTransferMaxDimension(fileSizeBytes: Long, width: Int, height: Int): Int {
        val largestEdge = maxOf(width, height)
        return when {
            fileSizeBytes >= 8_000_000L || largestEdge >= 4000 -> 1280
            fileSizeBytes >= 3_000_000L || largestEdge >= 2800 -> 1440
            else -> IMAGE_TRANSFER_MAX_DIMENSION
        }
    }

    private fun compressBitmapForTransfer(bitmap: Bitmap): ByteArray {
        var quality = IMAGE_TRANSFER_INITIAL_JPEG_QUALITY
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        while (output.size() > IMAGE_TRANSFER_TARGET_BYTES && quality > IMAGE_TRANSFER_MIN_JPEG_QUALITY) {
            quality = (quality - IMAGE_TRANSFER_QUALITY_STEP).coerceAtLeast(IMAGE_TRANSFER_MIN_JPEG_QUALITY)
            output.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }
        return output.toByteArray()
    }

    private fun resolveOrientedDimensions(file: File, rawWidth: Int, rawHeight: Int): Pair<Int, Int> {
        if (rawWidth <= 0 || rawHeight <= 0) {
            return 0 to 0
        }
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
        }.getOrDefault(ExifInterface.ORIENTATION_UNDEFINED)
        return if (
            orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            rawHeight to rawWidth
        } else {
            rawWidth to rawHeight
        }
    }

    private fun generateThumbnail(source: File, target: File, mimeType: String?): Boolean {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, bounds)
            val reqSize = 512
            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqSize, reqSize)
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize.coerceAtLeast(1) }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return@runCatching false
            val rotated = applyExifRotation(source, bitmap)
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                rotated.compress(compressFormatForMime(mimeType), 85, output)
            }
            if (rotated !== bitmap) {
                bitmap.recycle()
                rotated.recycle()
            } else {
                bitmap.recycle()
            }
            true
        }.getOrElse {
            target.delete()
            false
        }
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun applyExifRotation(source: File, bitmap: Bitmap): Bitmap {
        return try {
            val exif = ExifInterface(source.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateBitmap(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateBitmap(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateBitmap(bitmap, 270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> flipBitmap(bitmap, horizontal = true)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> flipBitmap(bitmap, horizontal = false)
                else -> bitmap
            }
        } catch (_: Exception) {
            bitmap
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun flipBitmap(bitmap: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply { preScale(if (horizontal) -1f else 1f, if (horizontal) 1f else -1f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun compressFormatForMime(mimeType: String?): Bitmap.CompressFormat {
        val normalized = mimeType?.lowercase(Locale.ROOT)
        return when {
            normalized?.contains("png") == true -> Bitmap.CompressFormat.PNG
            normalized?.contains("webp") == true -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
            }
            else -> Bitmap.CompressFormat.JPEG
        }
    }

    private fun buildTimelineSnapshot(
        messages: List<ChatMessage>,
        callEvents: List<CallEvent>
    ): TimelineSnapshot {
        val uniqueMessages = messages.distinctBy(ChatMessage::messageUuid)
        val mergedTimeline = ArrayList<ChatTimelineItem>(uniqueMessages.size + callEvents.size)
        uniqueMessages.forEach { mergedTimeline += ChatTimelineItem.Msg(it) }
        callEvents.forEach { mergedTimeline += ChatTimelineItem.Call(it) }
        mergedTimeline.sortBy { it.timestampMillis }
        return TimelineSnapshot(
            messages = uniqueMessages,
            timeline = mergedTimeline
        )
    }

    private fun ensureServiceBound() {
        if (isPreferredP2pGattContact() || shouldAwaitBleContactResolution()) {
            return
        }
        if (isBound) {
            return
        }
        val intent = Intent(context, RfcommForegroundService::class.java)
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * When we open a chat with a contact that was added by number over the internet but has no
     * offline Bluetooth link yet, try once (in the background) to establish one via SPAKE2 if the
     * peer is nearby — so future messages can ride Bluetooth automatically. Best-effort: the internet
     * transport keeps carrying the chat meanwhile, and the peer confirms the pairing once.
     */
    private fun maybeAutoLinkBluetooth(contact: Contact?, code: String) {
        if (autoLinkAttemptedForSession == code) return
        if (!NearbyAutoLink.isEligible(contact) || contact == null) return
        autoLinkAttemptedForSession = code
        autoLinkJob?.cancel()
        autoLinkJob = viewModelScope.launch(exceptionHandler) {
            val linked = withContext(Dispatchers.IO) {
                runCatching { NearbyAutoLink.tryEstablish(context, contact) }.getOrDefault(false)
            }
            // The paired contact now holds a BT key + address at the same sessionCode (the contact
            // flow also re-emits); kick a connect so Bluetooth takes over while we're still here.
            if (linked && sessionCode == code) {
                requestConnection(code)
            }
        }
    }

    private fun requestConnection(code: String) {
        Log.d(
            TAG,
            "requestConnection code=$code supportsInternet=${activeContact?.supportsInternet} " +
                "peerUidSet=${activeContact?.peerUid?.isNotBlank()} " +
                "internetAvailable=${internetChatTransport.isAvailable()}"
        )
        // Re-evaluate fresh each pass; the internet path below re-sets it when it applies.
        internetActiveConnected = false
        // If we're NOT on Bluetooth yet but the internet is available, connect over the internet
        // IMMEDIATELY (show "internet connected", drain the queue) — no waiting on a Bluetooth link
        // that may hang. Bluetooth still keeps trying in the background below, and takes over the
        // moment it links up (the internet "Connecting" suppression above keeps the badge steady).
        if (!isBluetoothLinkReady(code) &&
            activeContact?.supportsInternet == true &&
            internetChatTransport.isAvailable()
        ) {
            markInternetCarrying()
            flushQueuedTextMessages()
            // fall through to still kick off Bluetooth attempts in the background
        }
        // Bluetooth attempts (they run in the background when the internet is already connected;
        // their transient "Connecting" is suppressed so the badge stays "internet connected").
        if (isPreferredP2pGattContact()) {
            pendingConnection = true
            connectionInProgress = true
            updateConnectionState(ChatConnectionState.Connecting)
            p2pGattChatManager.start()
            return
        }
        if (shouldAwaitBleContactResolution(code)) {
            pendingConnection = true
            connectionInProgress = false
            updateConnectionState(ChatConnectionState.Connecting)
            return
        }
        if (isBleFallbackAllowed(code)) {
            requestBleFallbackBootstrap()
        }
        if (shouldAttemptBleClientFallbackClient()) {
            val address = activeBleFallbackAddress()
            if (!address.isNullOrBlank()) {
                ensureBleFallbackClientConnection(address)
            }
        }
        val currentService = service
        if (currentService == null) {
            pendingConnection = true
            ensureServiceBound()
            return
        }
        requestConnectionInternal(code)
    }

    private fun requestConnectionInternal(code: String) {
        if (connectionInProgress) return
        val srv = service ?: return
        if (srv.isSessionActive(code)) {
            connectionInProgress = false
            pendingConnection = false
            updateConnectionState(ChatConnectionState.Connected)
            refreshBleFallbackRouteState()
            return
        }
        connectionInProgress = true
        pendingConnection = true
        updateConnectionState(ChatConnectionState.Connecting)
        srv.connectToContact(code) { success ->
            handleConnectionResult(success)
        }
    }

    /** The internet is now carrying the chat: badge, BT-noise suppression flag, and warning reset. */
    private fun markInternetCarrying() {
        cancelBluetoothTakeover()
        internetActiveConnected = true
        bluetoothLostWarningShown = false
        _transport.value = ChatTransport.Internet
        updateConnectionState(ChatConnectionState.Connected)
    }

    /** Bluetooth is now carrying the chat: badge flip + drop the internet suppression flag. */
    private fun markBluetoothCarrying() {
        cancelBluetoothTakeover()
        internetActiveConnected = false
        bluetoothLostWarningShown = false
        _transport.value = ChatTransport.Bluetooth
        updateConnectionState(ChatConnectionState.Connected)
    }

    private fun cancelBluetoothTakeover() {
        bluetoothTakeoverJob?.cancel()
        bluetoothTakeoverJob = null
    }

    /**
     * A Bluetooth link just came up. If the internet isn't carrying the chat, take the badge over
     * right away. Otherwise hold the "internet connected" badge until the link stays up for
     * [BLUETOOTH_TAKEOVER_STABILITY_MS] — marginal links bounce, and flipping on every link-up would
     * ping-pong the badge. Only the badge waits: sends route over Bluetooth as soon as it's ready.
     */
    private fun onBluetoothLinkUp() {
        if (!internetActiveConnected) {
            markBluetoothCarrying()
            return
        }
        if (bluetoothTakeoverJob?.isActive == true) {
            return
        }
        bluetoothTakeoverJob = viewModelScope.launch(exceptionHandler) {
            delay(BLUETOOTH_TAKEOVER_STABILITY_MS)
            bluetoothTakeoverJob = null
            val code = sessionCode
            if (code != null && isBluetoothLinkReady(code)) {
                markBluetoothCarrying()
                flushQueuedTextMessages()
            }
        }
    }

    /**
     * The Bluetooth link couldn't be (re)established. If this contact is reachable over the internet
     * and we're online, switch to the internet transport seamlessly (no error, drain the queue).
     * Returns true when it switched; false means the caller should warn the user to enable Bluetooth.
     */
    private fun switchToInternetIfAvailable(): Boolean {
        val code = sessionCode
        if (code != null && isBluetoothLinkReady(code)) {
            // Another Bluetooth route (e.g. the BLE fallback) still carries the chat — keep it.
            markBluetoothCarrying()
            return true
        }
        val contactSupportsInternet = activeContact?.supportsInternet == true
        val internetAvailable = internetChatTransport.isAvailable()
        Log.d(
            TAG,
            "BT link down: contactSupportsInternet=$contactSupportsInternet " +
                "internetAvailable=$internetAvailable peerUid=${activeContact?.peerUid?.isNotBlank()}"
        )
        if (contactSupportsInternet && internetAvailable) {
            markInternetCarrying()
            flushQueuedTextMessages()
            return true
        }
        return false
    }

    private fun handleConnectionResult(success: Boolean) {
        val activeSessionCode = sessionCode
        if (!success) {
            if (switchToInternetIfAvailable()) {
                // Reachable online — stay connected over the internet and keep retrying Bluetooth
                // quietly in the background so we prefer it once it comes back. Never RESTART a
                // running retry loop from its own failure callback — that resets the exponential
                // backoff to its floor and turns it into a tight forever-loop.
                if (reconnectJob?.isActive != true) {
                    activeSessionCode?.let { scheduleAutoReconnect(it) }
                }
                return
            }
            updateConnectionState(ChatConnectionState.Error)
            if (!bluetoothLostWarningShown) {
                // Only claim "no internet" when internet is genuinely the missing piece (contact IS
                // internet-capable but we're offline). For a Bluetooth-only contact, internet status
                // is irrelevant — just prompt to turn Bluetooth on.
                val internetWasTheMissingPiece =
                    activeContact?.supportsInternet == true && !internetChatTransport.isAvailable()
                _errorMessage.value = context.getString(
                    if (internetWasTheMissingPiece) R.string.chat_bluetooth_lost_enable
                    else R.string.chat_bluetooth_lost_simple
                )
                bluetoothLostWarningShown = true
            }
            activeSessionCode?.let(::armTemporaryBleFallbackWindow)
            requestBleFallbackBootstrap()
            // Same backoff guard as above: a failure fired by the retry loop's own attempt must not
            // cancel-and-restart that loop at the minimum delay.
            if (reconnectJob?.isActive != true) {
                activeSessionCode?.let { scheduleAutoReconnect(it) }
            }
        } else {
            // Bluetooth linked up — it takes over from the background (the badge waits out the
            // stability window when the internet is already carrying the chat).
            clearTemporaryBleFallbackWindow(activeSessionCode)
            hasConnectedAtLeastOnce = true
            onBluetoothLinkUp()
            activeBleFallbackAddress()?.let { disconnectBleFallbackClient(it) }
            reconnectJob?.cancel()
            reconnectJob = null
            flushQueuedTextMessages()
        }
        connectionInProgress = false
        pendingConnection = false
        refreshBleFallbackRouteState()
    }

    private fun scheduleAutoReconnect(code: String) {
        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch(exceptionHandler) {
            try {
                var delayMs = 1_500L
                repeat(6) {
                    if (connectionState.value == ChatConnectionState.Connected) {
                        return@launch
                    }
                    delay(delayMs)
                    if (connectionState.value != ChatConnectionState.Connected) {
                        requestConnectionInternal(code)
                    }
                    delayMs = (delayMs * 2).coerceAtMost(30_000L)
                }
            } finally {
                if (reconnectJob === this@launch) {
                    reconnectJob = null
                }
            }
        }
    }

    private fun observeActiveSessions(service: RfcommForegroundService) {
        sessionMonitorJob?.cancel()
        sessionMonitorJob = viewModelScope.launch(exceptionHandler) {
            service.activeSessions.collect { sessions ->
                if (isPreferredP2pGattContact() || shouldAwaitBleContactResolution()) {
                    refreshBleFallbackRouteState()
                    return@collect
                }
                val code = sessionCode
                if (code != null && sessions.contains(code)) {
                    clearTemporaryBleFallbackWindow(code)
                    hasConnectedAtLeastOnce = true
                    activeBleFallbackAddress()?.let { address ->
                        disconnectBleFallbackClient(address)
                    }
                    connectionInProgress = false
                    pendingConnection = false
                    // RFCOMM link is up — Bluetooth takes the badge (held briefly for stability
                    // when the internet is already carrying the chat).
                    onBluetoothLinkUp()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    flushQueuedTextMessages()
                    refreshBleFallbackRouteState()
                    return@collect
                }

                val address = activeBleFallbackAddress()
                val bleReady = if (!address.isNullOrBlank()) {
                    isBleFallbackTransportReady(address, registerAlias = true)
                } else {
                    false
                }
                if (bleReady) {
                    hasConnectedAtLeastOnce = true
                    connectionInProgress = false
                    pendingConnection = false
                    onBluetoothLinkUp()
                    reconnectJob?.cancel()
                    reconnectJob = null
                    flushQueuedTextMessages()
                    refreshBleFallbackRouteState()
                    return@collect
                }

                val bleInProgress = if (!address.isNullOrBlank()) {
                    isBleClientFallbackInProgress(address)
                } else {
                    false
                }

                cancelBluetoothTakeover()
                if (!internetActiveConnected && switchToInternetIfAvailable()) {
                    // BT session dropped but the peer is reachable online — the internet takes the
                    // badge (no dead end) while Bluetooth keeps retrying in the background.
                } else if (connectionInProgress || pendingConnection || bleInProgress) {
                    updateConnectionState(ChatConnectionState.Connecting)
                } else {
                    updateConnectionState(ChatConnectionState.Error)
                }
                refreshBleFallbackRouteState()
            }
        }
    }

    private fun flushPendingOperations() {
        val srv = service ?: return
        while (pendingOperations.isNotEmpty()) {
            val operation = pendingOperations.removeFirst()
            operation(srv)
        }
    }

    private fun updateConnectionState(state: ChatConnectionState) {
        if (isInScreenshotDemoMode) {
            // Keep the scripted "Connected" state pinned; otherwise the real
            // service / BLE observers would flip it to Connecting/Error once
            // they realize there's no peer.
            return
        }
        // The internet is already carrying the chat while Bluetooth connects in the background — don't
        // downgrade to "Connecting" (or "Error") for those background BT attempts; the chat is usable.
        if (internetActiveConnected &&
            (state == ChatConnectionState.Connecting || state == ChatConnectionState.Error)
        ) {
            return
        }
        // Once "connection lost" is showing, background retries must not strobe the badge back to
        // "Connecting" on every silent attempt (each failure would flip it right back ~200ms later).
        // Error only clears on an attempt that actually SUCCEEDS (Connected / a transport mark).
        if (_connectionState.value == ChatConnectionState.Error && state == ChatConnectionState.Connecting) {
            return
        }
        if (_connectionState.value == state) {
            refreshSignalMonitoring()
            refreshBleFallbackRouteState()
            return
        }
        Log.d(
            TAG,
            "Connection state ${_connectionState.value} -> $state " +
                "sessionCode=$sessionCode contactAddress=$contactAddress " +
                "preferredTransport=${activeContact?.preferredTransport}"
        )
        _connectionState.value = state
        refreshSignalMonitoring()
        refreshBleFallbackRouteState()
    }

    private fun recomputeActiveCall() {
        val currentSession = sessionCode
        val gattState = currentSession?.let { gattCallsSnapshot[it] }
        handleCallStateChange(rfcommCallUiState ?: gattState)
    }

    private fun handleCallStateChange(state: CallUiState?) {
        _activeCall.value = state
        val active = when (state?.state) {
            CallState.Connecting, CallState.Ringing, CallState.InCall -> true
            else -> false
        }
        onCallStateChanged(active)
    }

    private fun onCallStateChanged(inCallOrRinging: Boolean) {
        if (inCallOrRinging) {
            stopSignalMonitoring()
            _signalPermissionMissing.value = false
        } else {
            refreshSignalMonitoring()
        }
    }

    private fun hasActiveRfcommCall(): Boolean {
        return when (_activeCall.value?.state) {
            CallState.Connecting, CallState.Ringing, CallState.InCall -> true
            else -> false
        }
    }

    private fun refreshSignalMonitoring() {
        if (isPreferredP2pGattContact()) {
            stopSignalMonitoring()
            _signalPermissionMissing.value = false
            return
        }
        if (isPublicMeshModeEnabled) {
            stopSignalMonitoring()
            _signalPermissionMissing.value = false
            return
        }
        if (hasActiveRfcommCall()) {
            stopSignalMonitoring()
            _signalPermissionMissing.value = false
            return
        }
        if (_connectionState.value == ChatConnectionState.Connected) {
            startSignalMonitoring()
        } else {
            stopSignalMonitoring()
            _signalPermissionMissing.value = false
        }
    }

    private fun startSignalMonitoring() {
        if (isPublicMeshModeEnabled) {
            _signalPermissionMissing.value = false
            return
        }
        if (hasActiveRfcommCall()) {
            _signalPermissionMissing.value = false
            stopSignalMonitoring()
            return
        }
        val address = contactAddress?.takeIf { it.isNotBlank() } ?: return
        if (!hasScanPermission()) {
            _signalPermissionMissing.value = true
            return
        }
        _signalPermissionMissing.value = false
        val monitor = signalMonitor ?: SignalMonitor().also { signalMonitor = it }
        monitor.start(address)
    }

    private fun stopSignalMonitoring() {
        signalMonitor?.stop()
        signalMonitor = null
        _signalInfo.value = null
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothScanGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            bluetoothScanGranted && fineLocationGranted
        } else {
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            fineLocationGranted || coarseLocationGranted
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterConnectivityFlush()
        presenceRegistration?.remove()
        presenceRegistration = null
        presenceRefreshJob?.cancel()
        typingExpiryJob?.cancel()
        messageJob?.cancel()
        contactJob?.cancel()
        sessionMonitorJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = null
        deferredConnectJob?.cancel()
        deferredConnectJob = null
        flushPendingTextJob?.cancel()
        retryPendingTextJob?.cancel()
        retryPendingTextJob = null
        retryScheduledAtMillis = null
        temporaryBleFallbackExpiryJob?.cancel()
        temporaryBleFallbackExpiryJob = null
        sosBindingJob?.cancel()
        sosBindingJob = null
        bleClientStateJob?.cancel()
        bleClientStateJob = null
        p2pGattStatusJob?.cancel()
        p2pGattStatusJob = null
        sosServiceBinding.unbind()
        clearTemporaryBleFallbackWindow()
        disconnectTrackedBleFallbackConnections()
        p2pGattChatManager.detach()
        stopSignalMonitoring()
        _signalPermissionMissing.value = false
        cancelVoiceRecording()
        cleanupServiceBinding()
    }

    private fun cleanupServiceBinding() {
        if (isBound) {
            try {
                context.unbindService(serviceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        isBound = false
        service = null
        sessionMonitorJob?.cancel()
        sessionMonitorJob = null
        voiceProgressJob?.cancel()
        voiceProgressJob = null
        _voiceTransfers.value = emptyMap()
        imageProgressJob?.cancel()
        imageProgressJob = null
        _imageTransfers.value = emptyMap()
        callStateJob?.cancel()
        callStateJob = null
        callHistoryJob?.cancel()
        callHistoryJob = null
        bleClientStateJob?.cancel()
        bleClientStateJob = null
        _activeCall.value = null
        refreshTransportCapabilities()
        refreshBleFallbackRouteState()
    }

    /**
     * Replace the real DB-backed session state with the scripted scenario from
     * [ChatScreenshotDemoScenario]. Only invoked from [initialize] when the
     * debug "Screenshot Demo Mode" flag is on. Cancels every job the normal
     * flow would start so nothing writes back to the real DataStore / DB, and
     * forces the transport-capability state to "connected" so the chat screen
     * renders the same chrome it would during a live session.
     */
    private fun applyScreenshotDemoScenario(sessionCode: String) {
        // Tear down any previously running observers so demo state is the only
        // source of truth for this ViewModel instance.
        messageJob?.cancel()
        contactJob?.cancel()
        sessionMonitorJob?.cancel()
        reconnectJob?.cancel()
        callHistoryJob?.cancel()
        deferredConnectJob?.cancel()
        flushPendingTextJob?.cancel()
        retryPendingTextJob?.cancel()
        sosBindingJob?.cancel()
        bleClientStateJob?.cancel()
        p2pGattStatusJob?.cancel()
        stopSignalMonitoring()

        // Set BEFORE touching any of the StateFlows below. While this flag is
        // true, refreshTransportCapabilities() and updateConnectionState()
        // are no-ops, so the scripted values we write next are sticky even
        // when the settings DataStore or other init-time collectors emit.
        isInScreenshotDemoMode = true

        this.sessionCode = sessionCode
        activeContact = null
        contactAddress = null
        _contactAddressState.value = null

        val demo = ChatScreenshotDemoScenario.buildTimeline(
            context = context,
            sessionCode = sessionCode
        )
        _callEvents.value = demo.callEvents
        _messages.value = demo.messages
        // Interleave messages and call events ordered by timestamp so the
        // missed-call row sits above the subsequent text messages.
        val timeline = buildList {
            demo.messages.forEach { add(ChatTimelineItem.Msg(it)) }
            demo.callEvents.forEach { add(ChatTimelineItem.Call(it)) }
        }.sortedBy { it.timestampMillis }
        _timelineItems.value = timeline
        _contactName.value = ChatScreenshotDemoScenario.demoContactName(context)
        _isSessionEncrypted.value = true
        _isBleFallbackActive.value = false
        _signalPermissionMissing.value = false
        _signalInfo.value = null

        // Present every transport capability as available so bottom-bar
        // actions (voice, attachment, location, call) appear enabled in
        // screenshots.
        _canSendVoiceMessages.value = true
        _canSendAttachments.value = true
        _canShareLocation.value = true
        _canPlaceCall.value = true
        _showCallAction.value = true

        _connectionState.value = ChatConnectionState.Connected
    }

    private data class TimelineSnapshot(
        val messages: List<ChatMessage>,
        val timeline: List<ChatTimelineItem>
    )

    private companion object {
        private const val TAG = "ChatScreenVM"
        private val PUBLIC_MESH_ENABLED_KEY = booleanPreferencesKey("advanced_public_mesh_enabled")
        private val HIGH_RANGE_MODE_ENABLED_KEY = booleanPreferencesKey("advanced_high_range_mode_enabled")
        private const val SCAN_INTERVAL_MS = 12_000L
        private const val CONNECTION_BOOTSTRAP_DELAY_MS = 260L
        private const val BLUETOOTH_TAKEOVER_STABILITY_MS = 2_500L
        private const val VOICE_MIME_AAC = "audio/mp4"
        private const val VOICE_MIME_OPUS = "audio/ogg"
        private const val IMAGE_MIME_JPEG = "image/jpeg"
        private const val IMAGE_TRANSFER_MAX_DIMENSION = 1600
        private const val IMAGE_TRANSFER_TARGET_BYTES = 450_000
        private const val IMAGE_TRANSFER_INITIAL_JPEG_QUALITY = 84
        private const val IMAGE_TRANSFER_MIN_JPEG_QUALITY = 58
        private const val IMAGE_TRANSFER_QUALITY_STEP = 7
        private const val IMAGE_TRANSFER_FORCE_OPTIMIZE_BYTES = 700_000L
        private const val IMAGE_TRANSFER_NON_JPEG_OPTIMIZE_BYTES = 350_000L
        private const val OFFLINE_MAP_SHARE_COOLDOWN_MS = 2 * 60 * 1000L
        private const val MAX_TEXT_SEND_ATTEMPTS = 7
        private const val TEXT_RETRY_BASE_DELAY_MS = 1_500L
        private const val TEXT_RETRY_MIN_DELAY_MS = 250L
        private const val TEXT_RETRY_MAX_DELAY_MS = 60_000L
        private const val TEXT_RETRY_JITTER_RATIO = 0.20
        private const val TEXT_RETRY_MIN_JITTER_MS = 250L
        private const val TEXT_RETRY_MAX_EXPONENT = 6
        private const val TEXT_TRANSPORT_WAIT_RETRY_DELAY_MS = 2_500L
        private const val BLE_FALLBACK_CONNECT_COOLDOWN_MS = 4_000L
        private const val TEXT_RETRY_SCHEDULE_TOLERANCE_MS = 200L
        private const val TEXT_MESSAGE_TTL_MS = 86_400_000L
        private const val AUTO_BLE_FALLBACK_WINDOW_MS = 20 * 60 * 1000L
        private const val OUTBOUND_ROUTE_RFCOMM = "rfcomm"
        private const val OUTBOUND_ROUTE_BLE_GATT = "ble_gatt_fallback"
        private const val OUTBOUND_ROUTE_INTERNET = "internet"
        private const val OUTBOUND_ROUTE_P2P_BLE_GATT = "p2p_ble_gatt"
        private const val TEXT_ERROR_TRANSPORT_UNAVAILABLE = "TRANSPORT_UNAVAILABLE"
        private const val TEXT_ERROR_SEND_FAILED = "SEND_FAILED"
    }

    private inner class SignalMonitor {

        private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
        private val handler = Handler(Looper.getMainLooper())
        private val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        private var registered = false
        private var running = false
        private var targetAddress: String? = null

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (!running || intent?.action != BluetoothDevice.ACTION_FOUND) {
                    return
                }
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val address = targetAddress ?: return
                val normalized = device?.address?.let { normalizeMacAddress(it) } ?: return
                if (normalized != address) {
                    return
                }
                val rssi = intent.getShortExtra(
                    BluetoothDevice.EXTRA_RSSI,
                    Short.MIN_VALUE
                ).toInt()
                if (rssi == Short.MIN_VALUE.toInt()) {
                    return
                }
                _signalInfo.value = SignalStrengthInfo(
                    rssi = rssi,
                    level = mapRssiToLevel(rssi),
                    lastUpdated = System.currentTimeMillis()
                )
            }
        }

        private val scanRunnable = object : Runnable {
            override fun run() {
                if (!running) {
                    return
                }
                if (GattMeshForegroundService.isRuntimeActive()) {
                    stop()
                    _signalPermissionMissing.value = false
                    return
                }
                if (isPublicMeshModeEnabled) {
                    stop()
                    _signalPermissionMissing.value = false
                    return
                }
                if (hasActiveRfcommCall()) {
                    stop()
                    _signalPermissionMissing.value = false
                    return
                }
                val activeSession = sessionCode
                if (activeSession.isNullOrBlank() || !ActiveChatTracker.isSessionActive(activeSession)) {
                    stop()
                    return
                }
                val isConnectionTrackable = _connectionState.value == ChatConnectionState.Connected
                if (!isConnectionTrackable) {
                    stop()
                    return
                }
                if (!hasScanPermission()) {
                    _signalPermissionMissing.value = true
                    stop()
                    return
                }
                val adapter = adapter ?: run {
                    stop()
                    return
                }
                val address = targetAddress
                if (address.isNullOrBlank()) {
                    handler.postDelayed(this, SCAN_INTERVAL_MS)
                    return
                }
                try {
                    if (adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                    }
                    val started = adapter.startDiscovery()
                    if (!started) {
                        _signalPermissionMissing.value = true
                        stop()
                        return
                    }
                } catch (_: SecurityException) {
                    _signalPermissionMissing.value = true
                    stop()
                    return
                }
                handler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }

        fun start(address: String) {
            targetAddress = normalizeMacAddress(address)
            if (adapter == null) {
                return
            }
            if (!registered) {
                registerReceiverSafe(receiver, filter)
                registered = true
            }
            if (!running) {
                running = true
                handler.post(scanRunnable)
            }
        }

        fun stop() {
            if (running) {
                running = false
                handler.removeCallbacks(scanRunnable)
            }
            if (registered) {
                unregisterReceiverSafe(receiver)
                registered = false
            }
            if (hasScanPermission()) {
                try {
                    if (adapter?.isDiscovering == true) {
                        adapter.cancelDiscovery()
                    }
                } catch (_: SecurityException) {
                }
            }
        }

        private fun mapRssiToLevel(rssi: Int): Int = when {
            rssi >= -55 -> 4
            rssi >= -65 -> 3
            rssi >= -75 -> 2
            rssi >= -85 -> 1
            else -> 0
        }
    }

    private fun registerReceiverSafe(receiver: BroadcastReceiver, filter: IntentFilter) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
        }
    }

}
