package com.auralis.crisisconnect.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.MainActivity
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.MessageType
import com.auralis.crisisconnect.data.ensureContactForDevice
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.saveCallEvent
import com.auralis.crisisconnect.data.saveLocalMessage
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.messaging.MessagingIdentity
import com.auralis.crisisconnect.nearby.NearbyContactAdvertiser
import com.auralis.crisisconnect.nearby.NearbyContactScanner
import com.auralis.crisisconnect.nearby.NearbyDiscoveryPreferences
import com.auralis.crisisconnect.nearby.NearbyPairingServer
import com.auralis.crisisconnect.security.AesCipherHelper
import com.google.firebase.auth.FirebaseAuth
import com.auralis.crisisconnect.security.decryptAesGcm
import com.auralis.crisisconnect.security.encryptAesGcm
import com.auralis.crisisconnect.telecom.RfcommTelecomCoordinator
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.file.FileStreamConstants
import com.auralis.crisisconnect.service.file.FileStreamReceiver
import com.auralis.crisisconnect.service.file.FileStreamSender
import com.auralis.crisisconnect.service.media.ImageStreamReceiver
import com.auralis.crisisconnect.service.media.ImageStreamSender
import com.auralis.crisisconnect.service.media.ImageStreamConstants
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferProgress
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.offline.OfflineMapShareCoordinator
import com.auralis.crisisconnect.service.voice.JitterBuffer
import com.auralis.crisisconnect.service.voice.VoiceStreamReceiver
import com.auralis.crisisconnect.service.voice.VoiceStreamSender
import com.auralis.crisisconnect.service.voice.VoiceStreamConstants
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferProgress
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import com.auralis.crisisconnect.service.voice.VoipConfig
import com.auralis.opuscodec.Opus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import kotlin.math.max

class RfcommForegroundService : Service() {

    private enum class OutboundTrafficClass {
        Default,
        RealtimeAudio
    }

    internal enum class WriteResult {
        Sent,
        Dropped,
        Failed
    }

    private data class OutboundPacket(
        val bytes: ByteArray,
        val trafficClass: OutboundTrafficClass,
        val enqueuedAtMs: Long = SystemClock.elapsedRealtime()
    )

    enum class CallDirection {
        OUTGOING,
        INCOMING
    }

    enum class CallResult {
        ANSWERED,
        MISSED,
        REJECTED,
        CANCELED
    }

    data class CallEvent(
        val id: String,
        val sessionCode: String,
        val timestampMillis: Long,
        val direction: CallDirection,
        val result: CallResult,
        val durationMillis: Long?
    )

    private val binder = LocalBinder()
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sharedCrc32 = CRC32()
    private val notificationManager by lazy { NotificationManagerCompat.from(this) }

    private val adapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val bleFallbackClientManager by lazy {
        BleClientManager(applicationContext, serviceScope)
    }
    private val audioManager: AudioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val callAudioLock = Any()
    private var callAudioSessionRefs = 0
    private var callAudioFocusRequest: AudioFocusRequest? = null
    private var callHasAudioFocus = false
    private var callWakeLock: PowerManager.WakeLock? = null
    private var callPreviousMode = AudioManager.MODE_NORMAL
    private var callPreviousSpeakerphone = false
    private var callPreviousMicMute = false
    private var callPreviousVoiceCallVolume = -1
    private var callVoiceCallVolumeAdjusted = false
    private val ringtoneAudioLock = Any()
    private var ringtoneAudioFocusRequest: AudioFocusRequest? = null
    private var ringtoneHasAudioFocus = false
    private var ringtonePreviousMode = AudioManager.MODE_NORMAL
    private var activeRingingSessionCode: String? = null

    private var secureAcceptThread: AcceptThread? = null
    private val connectThreads = ConcurrentHashMap<String, ConnectThread>()
    private val connectCallbackLock = Any()
    private val pendingConnectCallbacks = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private val connectedThreads = ConcurrentHashMap<String, ConnectedThread>()
    private val voiceSenders = ConcurrentHashMap<String, VoiceStreamSender>()
    private val voiceReceivers = ConcurrentHashMap<String, VoiceStreamReceiver>()
    private val imageSenders = ConcurrentHashMap<String, ImageStreamSender>()
    private val imageReceivers = ConcurrentHashMap<String, ImageStreamReceiver>()
    private val fileSenders = ConcurrentHashMap<String, FileStreamSender>()
    private val fileReceivers = ConcurrentHashMap<String, FileStreamReceiver>()
    private val mapShareSenders = ConcurrentHashMap<String, ImageStreamSender>()
    private val mapShareReceivers = ConcurrentHashMap<String, ImageStreamReceiver>()
    private val listeningRetryScheduled = AtomicBoolean(false)
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val connectExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "RfcommConnect").apply { isDaemon = true }
    }
    private val handshakeDelegate by lazy {
        RfcommHandshakeDelegate(
            appContext = applicationContext,
            serviceScope = serviceScope,
            handler = handler,
            hasConnectedSession = { sessionCode -> connectedThreads[sessionCode] != null },
            connectToContact = { sessionCode, preferredAddress, callback ->
                connectToContact(sessionCode, preferredAddress, callback)
            },
            writeToSession = { sessionCode, payload ->
                connectedThreads[sessionCode]?.write(payload) ?: false
            },
            onSessionActive = { sessionCode -> updateActiveSessions(sessionCode, connected = true) },
            currentBluetoothSessionName = ::currentBluetoothSessionName
        )
    }
    private val messageNotificationDelegate by lazy {
        RfcommMessageNotificationDelegate(
            context = this,
            serviceClass = RfcommForegroundService::class.java,
            replyAction = ACTION_REPLY_MESSAGE,
            extraSessionCodeKey = EXTRA_SESSION_CODE,
            hasPostNotificationsPermission = ::hasPostNotificationsPermission,
            notifyNotification = ::notifyNotificationManager,
            sendMessage = ::sendMessage
        )
    }
    private val callNotificationDelegate by lazy {
        RfcommCallNotificationDelegate(
            context = this,
            serviceClass = RfcommForegroundService::class.java,
            callChannelId = CALL_CHANNEL_ID,
            ongoingCallChannelId = CALL_ONGOING_CHANNEL_ID,
            callNotificationBaseId = CALL_NOTIFICATION_BASE_ID,
            actionMute = ACTION_MUTE,
            actionUnmute = ACTION_UNMUTE,
            actionSpeaker = ACTION_SPEAKER,
            actionHangup = ACTION_HANGUP,
            hasPostNotificationsPermission = ::hasPostNotificationsPermission
        )
    }
    private val incomingPayloadDelegate by lazy {
        RfcommIncomingPayloadDelegate(
            appContext = applicationContext,
            serviceScope = serviceScope,
            isSessionConnected = { sessionCode -> connectedThreads[sessionCode] != null },
            writeToSession = { sessionCode, payload ->
                connectedThreads[sessionCode]?.write(payload) ?: false
            },
            onSessionActive = { sessionCode -> updateActiveSessions(sessionCode, connected = true) },
            onHandleHandshakeRequest = ::handleHandshakeRequest,
            onHandleHandshakeAck = ::handleHandshakeAck,
            onHandleContactInfo = ::handleContactInfo,
            onHandleCallFrame = ::handleCallFrame,
            maybeNotifyIncomingMessage = ::maybeNotifyIncomingMessage,
            voiceKey = ::voiceKey,
            imageKey = ::imageKey,
            fileKey = ::fileKey,
            mapShareKey = ::mapShareKey,
            voiceSenders = voiceSenders,
            voiceReceivers = voiceReceivers,
            imageSenders = imageSenders,
            imageReceivers = imageReceivers,
            fileSenders = fileSenders,
            fileReceivers = fileReceivers,
            mapShareSenders = mapShareSenders,
            mapShareReceivers = mapShareReceivers,
            updateVoiceProgress = ::updateVoiceProgress,
            clearVoiceProgress = ::clearVoiceProgress,
            updateImageProgress = ::updateImageProgress,
            clearImageProgress = ::clearImageProgress,
            offlineMapShareMime = OFFLINE_MAP_SHARE_MIME
        )
    }

    private val callSessions = ConcurrentHashMap<String, CallSession>()
    private val callFrameDelegate by lazy {
        RfcommCallFrameDelegate(
            appContext = applicationContext,
            connectedThreads = connectedThreads,
            callSessions = callSessions,
            teardownCall = { session, reason, notifyPeer, resultOverride ->
                teardownCall(session, reason, notifyPeer, resultOverride)
            },
            sanitizeCfg = ::sanitizeCfg,
            trackIncomingCallStart = { sessionCode, callId, startedAt ->
                trackCallStart(sessionCode, callId, CallDirection.INCOMING, startedAt)
            },
            sendRing = { thread, callId -> sendJsonLine(thread, buildRing(callId)) },
            sendReject = { thread, callId, reason ->
                sendJsonLine(thread, buildReject(callId, reason))
            },
            scheduleCallTimeout = ::scheduleCallTimeout,
            updateCallState = ::updateCallState,
            showIncomingCallNotification = ::showIncomingCallNotification,
            stopRingtone = ::stopRingtone,
            cancelCallNotification = ::cancelCallNotification,
            prepareAudioPipelines = ::prepareAudioPipelines,
            endVoipCall = ::endVoipCall,
            clearCallState = ::clearCallState,
            sendCallCfgAck = ::sendCallCfgAck,
            sendClockSyncResponse = ::sendCallClockSyncResponse,
            applyAudioConfig = ::applyAudioConfig,
            enqueueSilenceFrames = ::enqueueSilenceFrames,
            decodeIncomingCallPayload = ::decodeIncomingCallPayload,
            opusFrameSize = ::opusFrameSize,
            processIncomingOpusFrame = ::processIncomingOpusFrame
        )
    }
    private val callRuntimes = ConcurrentHashMap<String, CallRuntime>()
    private val callStyleForegroundSession = AtomicReference<String?>(null)
    private val callProtocolDelegate = RfcommCallProtocolDelegate()
    private val callAudioPipelineDelegate by lazy {
        RfcommCallAudioPipelineDelegate(
            opusSampleRate = ::opusSampleRate,
            opusChannels = ::opusChannels,
            opusFrameSize = ::opusFrameSize,
            releaseAudioEffects = ::releaseAudioEffects,
            resolveOpusApplication = ::resolveOpusApplication,
            setEncoderBitrate = ::setEncoderBitrate,
            enableOpusControls = ::enableOpusControls,
            ensureCallAudioSession = ::ensureCallAudioSession,
            createAudioRecorder = ::createAudioRecorder,
            createAudioTrack = ::createAudioTrack,
            releaseCallAudioSession = ::releaseCallAudioSession,
            updatePlayerOutputRoute = ::updatePlayerOutputRoute,
            setPlayerGain = ::setPlayerGain,
            enableAudioEffects = ::enableAudioEffects,
            startSenderJob = ::startSenderJob,
            startPlayerJob = ::startPlayerJob,
            startAdaptationJob = ::startAdaptationJob,
            startClockSyncJob = ::startClockSyncJob,
            markCallAnswered = ::markCallAnswered,
            publishCallState = ::publishCallState
        )
    }

    private val _calls = MutableStateFlow<Map<String, CallUiState>>(emptyMap())
    val calls: StateFlow<Map<String, CallUiState>> = _calls.asStateFlow()
    private val callStateDelegate by lazy {
        RfcommCallStateDelegate(
            callsState = _calls,
            sendBroadcastIntent = ::sendBroadcast,
            actionCallStateChanged = ACTION_CALL_STATE_CHANGED,
            extraSessionCodeKey = EXTRA_SESSION_CODE,
            extraCallIdKey = EXTRA_CALL_ID,
            extraCallMutedKey = EXTRA_CALL_MUTED,
            extraCallSpeakerKey = EXTRA_CALL_SPEAKER,
            extraCallFlagKey = EXTRA_CALL_FLAG,
            handler = handler,
            callSetupTimeoutMs = CALL_SETUP_TIMEOUT_MS,
            onCallTimeout = { sessionCode, callId ->
                endVoipCall(sessionCode, callId, reason = "timeout")
            },
            onStateInCall = ::startOngoingCallNotification,
            onStateEnded = ::stopOngoingCallNotification
        )
    }
    private val callUiDelegate by lazy {
        RfcommCallUiDelegate(
            context = this,
            serviceClass = RfcommForegroundService::class.java,
            handler = handler,
            callStyleForegroundSession = callStyleForegroundSession,
            callEventChannelId = CALL_EVENT_CHANNEL_ID,
            actionAcceptCall = ACTION_ACCEPT_CALL,
            actionRejectCall = ACTION_REJECT_CALL,
            extraSessionCodeKey = EXTRA_SESSION_CODE,
            extraCallIdKey = EXTRA_CALL_ID,
            extraCallFlagKey = EXTRA_CALL_FLAG,
            callAcceptedFlag = CALL_ACCEPTED_FLAG,
            hasPostNotificationsPermission = ::hasPostNotificationsPermission,
            canUseForegroundCallStyle = ::canUseForegroundCallStyle,
            updateCallStyleForeground = ::updateCallStyleForeground,
            callNotificationId = ::callNotificationId,
            callScreenPendingIntent = ::callScreenPendingIntent,
            buildIncomingCallNotification = { name, caller, contentIntent, acceptPending, rejectPending, useCallStyle ->
                buildIncomingCallNotification(
                    name = name,
                    caller = caller,
                    contentIntent = contentIntent,
                    acceptPending = acceptPending,
                    rejectPending = rejectPending,
                    useCallStyle = useCallStyle
                ).build()
            },
            buildOngoingCallNotification = { session, useCallStyle ->
                buildOngoingCallNotification(session, useCallStyle)
            },
            resolveCallerName = ::resolveCallerName,
            formatElapsed = ::formatElapsed,
            startRingtone = ::startRingtone,
            notifyNotification = ::notifyNotificationManager,
            cancelNotification = { id -> NotificationManagerCompat.from(this).cancel(id) },
            restoreBaseNotification = { startForegroundRobust(buildNotification()) }
        )
    }

    private val _callEvents = MutableSharedFlow<CallEvent>(replay = 0, extraBufferCapacity = 64)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()
    private val callRuntimeDelegate by lazy {
        RfcommCallRuntimeDelegate(
            callRuntimes = callRuntimes,
            emitCallEvent = { event ->
                serviceScope.launch {
                    runCatching {
                        saveCallEvent(applicationContext, event, analyticsTransport = "bluetooth")
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to persist call event", error)
                    }
                    _callEvents.emit(event)
                }
            }
        )
    }

    private val _activeSessions = MutableStateFlow<Set<String>>(emptySet())
    val activeSessions: StateFlow<Set<String>> = _activeSessions.asStateFlow()

    private val _voiceTransfers = MutableStateFlow<Map<String, VoiceTransferProgress>>(emptyMap())
    val voiceTransfers: StateFlow<Map<String, VoiceTransferProgress>> = _voiceTransfers.asStateFlow()

    private val _imageTransfers = MutableStateFlow<Map<String, ImageTransferProgress>>(emptyMap())
    val imageTransfers: StateFlow<Map<String, ImageTransferProgress>> = _imageTransfers.asStateFlow()
    private var bleFallbackMessageJob: Job? = null

    private val foregroundStarted = AtomicBoolean(false)
    private val foregroundHasPhoneCallType = AtomicBoolean(false)

    // Nearby contact discovery (generic beacon + SPAKE2 pairing responder + device scan) is hosted
    // here instead of a dedicated foreground service so the app shows a single persistent
    // notification: any active foreground service lifts the background-BLE restrictions for the
    // whole process, so it doesn't need its own. Tracks the opt-in preference while the service
    // lives.
    @Volatile
    private var nearbyDiscoveryEnabled = false

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.i(TAG, "Bluetooth turned ON, ensuring listener")
                    ensureListening("bluetooth state ON")
                    // The BLE advertiser/scanner die silently with the adapter; a plain (idempotent)
                    // start() would see stale state and no-op, so force a stop+start cycle.
                    restartNearbyDiscoveryIfEnabled()
                }
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                    Log.w(TAG, "Bluetooth turning OFF/OFF, stopping accept thread")
                    stopAcceptThread()
                }
            }
        }
    }

    private fun observeNearbyDiscoveryPreference() {
        serviceScope.launch {
            NearbyDiscoveryPreferences.isEnabled(applicationContext)
                .distinctUntilChanged()
                .collect { enabled ->
                    nearbyDiscoveryEnabled = enabled
                    if (enabled) {
                        Log.i(TAG, "Nearby discovery opted in, starting components")
                        startNearbyDiscoveryComponents()
                    } else {
                        Log.i(TAG, "Nearby discovery opted out, stopping components")
                        stopNearbyDiscoveryComponents()
                    }
                }
        }
    }

    private fun startNearbyDiscoveryComponents() {
        NearbyContactAdvertiser.start(applicationContext)
        NearbyPairingServer.start(applicationContext)
        NearbyContactScanner.start(applicationContext)
    }

    private fun stopNearbyDiscoveryComponents() {
        NearbyContactAdvertiser.stop()
        NearbyPairingServer.stop()
        NearbyContactScanner.stop()
    }

    private fun restartNearbyDiscoveryIfEnabled() {
        if (!nearbyDiscoveryEnabled) return
        stopNearbyDiscoveryComponents()
        startNearbyDiscoveryComponents()
    }

    override fun onCreate() {
        super.onCreate()
        cleanupStaleIncomingTransferCaches()
        RfcommTelecomCoordinator.initialize(applicationContext)
        ensureCallNotificationChannel()
        ensureCallEventNotificationChannel()
        registerReceiver(
            bluetoothStateReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
        val initialNotification = buildNotification()
        startForegroundRobust(initialNotification)
        observeBleFallbackIncomingMessages()
        if (foregroundStarted.get()) {
            ensureListening("service onCreate")
            // Background BLE work needs an active foreground service, so only host nearby
            // discovery once the foreground start actually succeeded.
            observeNearbyDiscoveryPreference()
        } else {
            Log.w(TAG, "Foreground start failed in onCreate, stopping service to avoid crash")
        }
    }

    private fun cleanupStaleIncomingTransferCaches() {
        cleanupIncomingTransferCacheDir(VoiceStreamConstants.INBOX_DIR)
        cleanupIncomingTransferCacheDir(ImageStreamConstants.INBOX_DIR)
        cleanupIncomingTransferCacheDir(FileStreamConstants.INBOX_DIR)
    }

    private fun cleanupIncomingTransferCacheDir(directoryName: String) {
        val cacheDirectory = File(cacheDir, directoryName)
        val leftoverCount = cacheDirectory.listFiles()?.size ?: 0
        if (leftoverCount == 0) {
            return
        }
        runCatching {
            cacheDirectory.deleteRecursively()
        }.onSuccess {
            Log.i(TAG, "Cleared $leftoverCount stale incoming transfer cache entries from $directoryName")
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to clear stale incoming transfer cache for $directoryName", throwable)
        }
    }

    private fun startForegroundRobust(notification: Notification) {
        val wantPhoneCallType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && supportsPhoneCallForegroundType()
        val primaryTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseForegroundServiceTypes(wantPhoneCallType)
        } else {
            0
        }
        val fallbackTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            baseForegroundServiceTypes(includePhoneCallType = false)
        } else {
            0
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, primaryTypes)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted.set(true)
            foregroundHasPhoneCallType.set(wantPhoneCallType)
            Log.i(TAG, "Foreground started (phoneCallType=$wantPhoneCallType, mic=${hasRecordAudioPermission()})")
            return
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Primary startForeground rejected (phoneCallType=$wantPhoneCallType), retrying fallback", securityException)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && primaryTypes != fallbackTypes) {
                try {
                    startForeground(NOTIFICATION_ID, notification, fallbackTypes)
                    foregroundStarted.set(true)
                    foregroundHasPhoneCallType.set(false)
                    Log.i(TAG, "Foreground started with fallback types (mic=${hasRecordAudioPermission()})")
                    return
                } catch (fallbackException: Exception) {
                    Log.w(TAG, "Fallback startForeground failed", fallbackException)
                }
            }
        } catch (startException: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && startException is ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "Foreground start not allowed, stopping service", startException)
            } else {
                Log.w(TAG, "Foreground start failed", startException)
            }
        }

        foregroundStarted.set(false)
        foregroundHasPhoneCallType.set(false)
        stopSelf()
    }

    private fun baseForegroundServiceTypes(includePhoneCallType: Boolean): Int {
        var baseTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (hasRecordAudioPermission()) {
            baseTypes = baseTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (includePhoneCallType) {
            baseTypes = baseTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        return baseTypes
    }

    private fun supportsPhoneCallForegroundType(): Boolean {
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return false
        }

        val hasManageOwnCalls = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.MANAGE_OWN_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasManageOwnCalls) {
            return true
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false
        }

        val roleManager = getSystemService(RoleManager::class.java)
        val hasDialerRole = roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true &&
                roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        return hasDialerRole
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACCEPT_CALL -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                val fromNotification = intent.getStringExtra(EXTRA_CALL_FLAG) == CALL_ACCEPTED_FLAG
                val acceptedByTelecom =
                    intent.getStringExtra(EXTRA_CALL_FLAG) == CALL_ANSWERED_BY_TELECOM_FLAG
                if (!sessionCode.isNullOrBlank() && !callId.isNullOrBlank()) {
                    cancelCallNotification(sessionCode)
                    acceptIncomingCall(
                        sessionCode = sessionCode,
                        callId = callId,
                        acceptedByTelecom = acceptedByTelecom
                    ) {}
                    if (fromNotification && !isDeviceLockedForIncomingUi()) {
                        launchCallScreen(sessionCode, callId)
                    }
                }
            }

            ACTION_REJECT_CALL -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!sessionCode.isNullOrBlank() && !callId.isNullOrBlank()) {
                    cancelCallNotification(sessionCode)
                    rejectIncomingCall(sessionCode, callId)
                    broadcastCallEvent(sessionCode, callId, CALL_REJECTED_FLAG)
                }
            }

            ACTION_END_CALL -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!sessionCode.isNullOrBlank() && !callId.isNullOrBlank()) {
                    endVoipCall(sessionCode, callId)
                }
            }

            ACTION_MUTE -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                sessionCode?.let { code ->
                    callSessions[code]?.takeIf { it.callId == callId }?.let { session ->
                        setCallMicMuted(code, true)
                        broadcastCallState(session)
                    }
                }
            }

            ACTION_UNMUTE -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                sessionCode?.let { code ->
                    callSessions[code]?.takeIf { it.callId == callId }?.let { session ->
                        setCallMicMuted(code, false)
                        broadcastCallState(session)
                    }
                }
            }

            ACTION_SPEAKER -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                sessionCode?.let { code ->
                    callSessions[code]?.takeIf { it.callId == callId }?.let { session ->
                        val target = !session.speakerEnabled.value
                        setCallSpeakerEnabled(code, target)
                        broadcastCallState(session)
                    }
                }
            }

            ACTION_SYNC_CALL_AUDIO_ROUTE -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                val route = intent.getStringExtra(EXTRA_CALL_AUDIO_ROUTE)
                    ?.let(::decodeCallAudioRoute)
                val availableRoutes = intent.getStringArrayListExtra(EXTRA_CALL_AVAILABLE_AUDIO_ROUTES)
                    ?.mapNotNull(::decodeCallAudioRoute)
                sessionCode?.let { code ->
                    callSessions[code]?.takeIf { it.callId == callId }?.let {
                        syncCallAudioState(
                            sessionCode = code,
                            route = route,
                            availableRoutes = availableRoutes
                        )
                    }
                }
            }

            ACTION_HANGUP -> {
                val sessionCode = intent.getStringExtra(EXTRA_SESSION_CODE)
                val callId = intent.getStringExtra(EXTRA_CALL_ID)
                if (!sessionCode.isNullOrBlank() && !callId.isNullOrBlank()) {
                    endVoipCall(sessionCode, callId)
                    stopSelf()
                }
            }

            ACTION_REPLY_MESSAGE -> {
                handleDirectReplyIntent(intent)
            }
        }
        ensureListening("onStartCommand")
        // Every app open restarts the service via startForegroundService; the idempotent start()s
        // give components that bailed earlier (Bluetooth off, phone number not set yet) a retry.
        if (nearbyDiscoveryEnabled) {
            startNearbyDiscoveryComponents()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        handler.postDelayed({
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, RfcommForegroundService::class.java)
                )
            } catch (_: Exception) {
            }
        }, 750L)
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        bleFallbackMessageJob?.cancel()
        bleFallbackMessageJob = null
        // Only tear down the shared nearby singletons when this service started them, so an
        // independent short-lived user (e.g. NearbyAutoLink's scan) is never killed from here.
        if (nearbyDiscoveryEnabled) {
            stopNearbyDiscoveryComponents()
        }
        stopAll()
        serviceScope.cancel()
    }

    fun connectToContact(sessionCode: String, callback: (Boolean) -> Unit) {
        connectToContact(sessionCode, preferredAddress = null, callback = callback)
    }

    fun connectToContact(
        sessionCode: String,
        preferredAddress: String?,
        callback: (Boolean) -> Unit
    ) {
        if (!hasBluetoothPermission()) {
            handler.post { callback(false) }
            return
        }
        connectedThreads[sessionCode]?.let {
            handler.post { callback(true) }
            return
        }
        val adapter = this@RfcommForegroundService.adapter ?: run {
            handler.post { callback(false) }
            return
        }
        if (!isClassicBluetoothOn()) {
            Log.w(
                TAG,
                "connectToContact[$sessionCode]: Bluetooth classic is not ON (state=${currentBluetoothStateLabel()})"
            )
            ensureListeningWithRetry()
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val shouldStartConnection = enqueueConnectCallback(sessionCode, callback)
            if (!shouldStartConnection) {
                Log.d(TAG, "connectToContact[$sessionCode]: connect already in progress, callback queued")
                return@launch
            }
            if (!isClassicBluetoothOn()) {
                Log.w(
                    TAG,
                    "connectToContact[$sessionCode]: Bluetooth classic turned off while connecting (state=${currentBluetoothStateLabel()})"
                )
                dispatchQueuedConnectCallbacks(sessionCode, false)
                ensureListeningWithRetry()
                return@launch
            }
            connectThreads[sessionCode]?.let { existingThread ->
                if (existingThread.isAlive) {
                    Log.d(TAG, "connectToContact[$sessionCode]: existing connect thread is alive, waiting")
                    return@launch
                }
            }
            val contact = getContact(applicationContext, sessionCode) ?: run {
                dispatchQueuedConnectCallbacks(sessionCode, false)
                return@launch
            }
            val storedAddress = normalizeMacAddress(contact.address)
            val fallbackAddress = normalizeMacAddress(contact.lastKnownBleAddress)
            val resolvedTarget = resolveConnectTargetDevice(
                adapter = adapter,
                sessionCode = sessionCode,
                preferredAddress = preferredAddress,
                storedAddress = storedAddress,
                fallbackAddress = fallbackAddress,
                contactName = contact.name
            ) ?: run {
                Log.w(
                    TAG,
                    "connectToContact[$sessionCode]: unable to resolve target device " +
                        "(preferredAddress=${normalizeMacAddress(preferredAddress)}, storedAddress=$storedAddress, " +
                        "fallbackAddress=$fallbackAddress, contactName=${contact.name})"
                )
                dispatchQueuedConnectCallbacks(sessionCode, false)
                return@launch
            }
            val device = resolvedTarget.device
            if (resolvedTarget.address != storedAddress) {
                runCatching {
                    updateContactAddress(applicationContext, sessionCode, resolvedTarget.address)
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "connectToContact[$sessionCode]: failed to update contact address $storedAddress -> ${resolvedTarget.address}",
                        throwable
                    )
                }
            }
            Log.i(
                TAG,
                "connectToContact[$sessionCode]: using ${resolvedTarget.address} (reason=${resolvedTarget.reason})"
            )
            connectedThreads[sessionCode]?.let {
                dispatchQueuedConnectCallbacks(sessionCode, true)
                return@launch
            }
            val thread = ConnectThread(device, sessionCode) { success ->
                dispatchQueuedConnectCallbacks(sessionCode, success)
            }
            val previousThread = connectThreads.put(sessionCode, thread)
            if (previousThread?.isAlive == true) {
                previousThread.cancel()
            }
            runCatching {
                thread.start()
            }.onFailure { throwable ->
                Log.e(TAG, "connectToContact[$sessionCode]: failed to start ConnectThread", throwable)
                connectThreads.remove(sessionCode, thread)
                dispatchQueuedConnectCallbacks(sessionCode, false)
            }
        }
    }

    private fun enqueueConnectCallback(sessionCode: String, callback: (Boolean) -> Unit): Boolean {
        synchronized(connectCallbackLock) {
            val callbacks = pendingConnectCallbacks.getOrPut(sessionCode) { mutableListOf() }
            callbacks += callback
            return callbacks.size == 1
        }
    }

    private fun dispatchQueuedConnectCallbacks(sessionCode: String, success: Boolean) {
        val callbacks = synchronized(connectCallbackLock) {
            pendingConnectCallbacks.remove(sessionCode)?.toList().orEmpty()
        }
        callbacks.forEach { queuedCallback ->
            handler.post { queuedCallback(success) }
        }
    }

    private fun decodeContactAesKey(encodedKey: String?): ByteArray? {
        val sanitized = encodedKey?.trim().orEmpty()
        if (sanitized.isEmpty()) {
            return null
        }
        return runCatching { AesCipherHelper.decodeKeyFromBase64(sanitized) }
            .onFailure { throwable ->
                Log.w(TAG, "Ignoring invalid contact AES key", throwable)
            }
            .getOrNull()
    }

    fun sendMessage(sessionCode: String, uuid: String, text: String, callback: (Boolean) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            handler.post { callback(false) }
            return
        }
        val normalized = trimmed.replace("\r\n", "\n").replace('\r', '\n')
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val aesKeyBytes = decodeContactAesKey(getContact(applicationContext, sessionCode)?.aesKey)

            val encryptedPacket = aesKeyBytes?.let { key ->
                runCatching { AesCipherHelper.encrypt(key, normalized.toByteArray(Charsets.UTF_8)) }
                    .getOrNull()
            }

            val success = if (encryptedPacket != null && encryptedPacket.size > 12) {
                val iv = encryptedPacket.copyOfRange(0, 12)
                val cipherBytes = encryptedPacket.copyOfRange(12, encryptedPacket.size)
                val payload = buildString {
                    append("SEC_MSG|")
                    append(uuid)
                    append('|')
                    append(Base64.encodeToString(iv, Base64.NO_WRAP))
                    append('|')
                    append(Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
                    append('\n')
                }
                thread.write(payload.toByteArray(Charsets.UTF_8))
            } else {
                val encoded = normalized.replace("\n", "\\n")
                val payload = buildString {
                    append("MSG|")
                    append(uuid)
                    append('|')
                    append(encoded)
                    append('\n')
                }
                thread.write(payload.toByteArray(Charsets.UTF_8))
            }

            if (success) {
                saveLocalMessage(applicationContext, sessionCode, uuid, normalized)
            }
            handler.post { callback(success) }
        }
    }

    fun startVoipCall(
        sessionCode: String,
        preferredSampleRate: Int = VoipConfig.SAMPLE_RATE_HZ,
        channels: Int = 1,
        frameMs: Int = VoipConfig.FRAME_DURATION_MS,
        bitrate: Int = VoipConfig.OPUS_BITRATE_BPS,
        preferEncrypted: Boolean = true,
        callback: (Boolean) -> Unit
    ) {
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val existing = callSessions.remove(sessionCode)
            if (existing != null) {
                teardownCall(existing, "hangup", notifyPeer = false)
            }
            val contact = getContact(applicationContext, sessionCode)
            val aesKeyBytes = decodeContactAesKey(contact?.aesKey)
            val remoteDisplayName = contact?.name
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() && !BlePeerIdentityUtils.looksLikeBleIdentifier(it, sessionCode)
                }
            val encrypted = preferEncrypted && aesKeyBytes != null
            val initialProfile = if (bitrate < VoipConfig.OPUS_BITRATE_BPS || preferredSampleRate < VoipConfig.SAMPLE_RATE_HZ) {
                Profile.NB
            } else {
                Profile.WB
            }
            val initialCfg = initialProfile.defaultCfg(encrypted).copy(
                ch = 1,
                frm = VoipConfig.FRAME_DURATION_MS,
                fps = initialProfile.defaultFramesPerPacket,
                br = when (initialProfile) {
                    Profile.WB -> bitrate.coerceIn(16_000, VoipConfig.OPUS_BITRATE_BPS)
                    Profile.NB -> bitrate.coerceIn(12_000, 16_000)
                }
            )
            if (initialCfg.frameSamples <= 0) {
                handler.post { callback(false) }
                return@launch
            }
            val callId = UUID.randomUUID().toString()
            if (!ActiveCallRegistry.tryAcquire(
                    ActiveCallRegistry.Transport.RFCOMM,
                    sessionCode,
                    callId
                )
            ) {
                Log.w(TAG, "Refusing outgoing RFCOMM call: another call is active (${ActiveCallRegistry.current()})")
                handler.post { callback(false) }
                return@launch
            }
            val session = CallSession(
                sessionCode = sessionCode,
                callId = callId,
                aesKey = aesKeyBytes,
                isOutgoing = true
            ).also {
                it.profile = initialProfile
                it.cfg = initialCfg
                it.remoteDisplayName = remoteDisplayName
                it.jitterBuffer = JitterBuffer(
                    initialTargetDelayMs = VoipConfig.JITTER_DEFAULT_DELAY_MS,
                    maxBufferedFrames = initialProfile.jitterCapacity
                )
            }
            callSessions[sessionCode] = session
            trackCallStart(sessionCode, callId, CallDirection.OUTGOING, session.startedAt)
            RfcommTelecomCoordinator.onOutgoingCall(
                sessionCode = sessionCode,
                callId = callId,
                displayName = remoteDisplayName ?: sessionCode
            )
            updateCallState(session, CallState.Connecting)
            val offer = buildOffer(callId, session.cfg)
            val sent = sendJsonLine(thread, offer)
            if (!sent) {
                callSessions.remove(sessionCode)
                teardownCall(session, "error", notifyPeer = false)
                handler.post { callback(false) }
                return@launch
            }
            scheduleCallTimeout(session)
            handler.post { callback(true) }
        }
    }

    fun acceptIncomingCall(
        sessionCode: String,
        callId: String,
        acceptedByTelecom: Boolean = false,
        callback: (Boolean) -> Unit
    ) {
        fun proceed() {
            serviceScope.launch {
                val session = callSessions[sessionCode]
                val thread = connectedThreads[sessionCode]
                if (session == null || session.callId != callId || thread == null) {
                    handler.post { callback(false) }
                    return@launch
                }
                stopRingtone(session)
                cancelCallNotification(sessionCode)
                val accepted = sendJsonLine(thread, buildAccept(callId))
                if (!accepted) {
                    handler.post { callback(false) }
                    teardownCall(session, "error", notifyPeer = false)
                    callSessions.remove(sessionCode)
                    return@launch
                }
                val audioReady = prepareAudioPipelines(session, thread)
                if (!audioReady) {
                    handler.post { callback(false) }
                    endVoipCall(sessionCode, callId, reason = "error")
                    return@launch
                }
                updateCallState(session, CallState.InCall)
                handler.post { callback(true) }
            }
        }

        if (acceptedByTelecom) {
            proceed()
            return
        }

        RfcommTelecomCoordinator.answerIncomingCall(sessionCode, callId) { success ->
            if (!success) {
                handler.post { callback(false) }
                return@answerIncomingCall
            }
            proceed()
        }
    }

    fun rejectIncomingCall(sessionCode: String, callId: String, reason: String = "declined") {
        val session = callSessions[sessionCode]?.takeIf { it.callId == callId } ?: return
        val thread = connectedThreads[sessionCode]
        stopRingtone(session)
        cancelCallNotification(sessionCode)
        if (thread != null) {
            sendJsonLine(thread, buildReject(callId, reason))
        }
        teardownCall(session, reason, notifyPeer = false, resultOverride = CallResult.REJECTED)
        callSessions.remove(sessionCode)
        clearCallState(sessionCode)
    }

    fun endVoipCall(sessionCode: String, callId: String, reason: String = "hangup") {
        val session = callSessions[sessionCode] ?: return
        if (session.callId != callId) {
            return
        }
        val thread = connectedThreads[sessionCode]
        if (thread != null) {
            sendJsonLine(thread, buildEnd(callId, reason))
        }
        teardownCall(session, reason, notifyPeer = false)
        callSessions.remove(sessionCode)
        clearCallState(sessionCode)
    }

    fun setCallMicMuted(sessionCode: String, muted: Boolean) {
        val session = callSessions[sessionCode] ?: return
        session.muted.value = muted
        publishCallState(session)
        if (session.state.value == CallState.InCall) {
            postOngoingCallNotification(session)
        }
    }

    fun setCallSpeakerEnabled(
        sessionCode: String,
        enabled: Boolean,
        syncTelecomRoute: Boolean = true
    ) {
        val session = callSessions[sessionCode] ?: return
        val targetRoute = if (enabled) {
            CallAudioRoute.Speaker
        } else {
            CallAudioRoute.Earpiece
        }
        if (session.speakerEnabled.value == enabled && session.currentRoute == targetRoute) {
            return
        }
        session.availableRoutes = orderedCallAudioRoutes(session.availableRoutes + targetRoute)
        session.speakerEnabled.value = enabled
        session.currentRoute = targetRoute
        updatePlayerOutputRoute(session.player, targetRoute)
        publishCallState(session)
        if (syncTelecomRoute) {
            RfcommTelecomCoordinator.requestSpeakerRoute(sessionCode, enabled)
        }
        if (session.state.value == CallState.InCall) {
            postOngoingCallNotification(session)
        }
    }

    fun setCallAudioRoute(
        sessionCode: String,
        route: CallAudioRoute,
        syncTelecomRoute: Boolean = true
    ) {
        val session = callSessions[sessionCode] ?: return
        val selectableRoutes = orderedCallAudioRoutes(session.availableRoutes + session.currentRoute)
        if (route !in selectableRoutes) {
            return
        }
        val normalizedRoutes = orderedCallAudioRoutes(selectableRoutes + route)
        if (
            session.currentRoute == route &&
            session.speakerEnabled.value == (route == CallAudioRoute.Speaker) &&
            session.availableRoutes == normalizedRoutes
        ) {
            return
        }
        session.availableRoutes = normalizedRoutes
        session.currentRoute = route
        session.speakerEnabled.value = route == CallAudioRoute.Speaker
        updatePlayerOutputRoute(session.player, route)
        publishCallState(session)
        if (syncTelecomRoute) {
            RfcommTelecomCoordinator.requestAudioRoute(sessionCode, route)
        }
        if (session.state.value == CallState.InCall) {
            postOngoingCallNotification(session)
        }
    }

    private fun syncCallAudioState(
        sessionCode: String,
        route: CallAudioRoute?,
        availableRoutes: List<CallAudioRoute>?
    ) {
        val session = callSessions[sessionCode] ?: return
        val normalizedRoutes = availableRoutes
            ?.takeIf { it.isNotEmpty() }
            ?.let { orderedCallAudioRoutes(it + (route ?: session.currentRoute)) }
            ?: session.availableRoutes
        val resolvedRoute = route ?: session.currentRoute
        val speakerEnabled = resolvedRoute == CallAudioRoute.Speaker
        if (
            session.currentRoute == resolvedRoute &&
            session.speakerEnabled.value == speakerEnabled &&
            session.availableRoutes == normalizedRoutes
        ) {
            return
        }
        session.availableRoutes = normalizedRoutes
        session.currentRoute = resolvedRoute
        session.speakerEnabled.value = speakerEnabled
        updatePlayerOutputRoute(session.player, resolvedRoute)
        publishCallState(session)
        if (session.state.value == CallState.InCall) {
            postOngoingCallNotification(session)
        }
    }

    private fun updatePlayerOutputRoute(player: AudioTrack?, route: CallAudioRoute) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = route == CallAudioRoute.Speaker
        if (player != null) {
            setPlayerGain(player)
        }
        val targetType = when (route) {
            CallAudioRoute.Speaker -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            CallAudioRoute.Earpiece -> AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            else -> null
        }
        val device = findOutputAudioDevice(targetType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            }
            return
        }
        if (player == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }
        // Older Android builds, especially Oreo-era devices, can mis-route or attenuate
        // earpiece playback when preferredDevice is forced. Let communication policy choose
        // earpiece and only pin the loudspeaker explicitly.
        runCatching {
            player.preferredDevice = if (route == CallAudioRoute.Speaker) device else null
        }
    }

    private fun findOutputAudioDevice(type: Int?): AudioDeviceInfo? {
        if (type == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        return audioManager
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == type }
    }

    private fun setPlayerGain(player: AudioTrack) {
        val maxGain = runCatching { AudioTrack.getMaxVolume() }
            .getOrDefault(1.0f)
            .coerceAtLeast(1.0f)
        runCatching {
            player.setStereoVolume(maxGain, maxGain)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching {
                player.setVolume(maxGain)
            }
        }
    }

    fun sendVoiceStream(
        sessionCode: String,
        uuid: String,
        mimeType: String,
        durationMs: Long?,
        payload: ByteArray,
        callback: (Boolean) -> Unit
    ) {
        if (payload.isEmpty()) {
            handler.post { callback(false) }
            return
        }
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val contact = getContact(applicationContext, sessionCode)
            val keyBytes = decodeContactAesKey(contact?.aesKey)
            val key = voiceKey(sessionCode, uuid)
            val sender = VoiceStreamSender(
                context = applicationContext,
                sessionCode = sessionCode,
                uuid = uuid,
                mime = mimeType,
                durationMs = durationMs,
                originalBytes = payload,
                aesKey = keyBytes,
                scope = serviceScope,
                frameWriter = { json ->
                    val payloadBytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
                    thread.write(payloadBytes)
                },
                onProgress = { progress -> updateVoiceProgress(key, progress) },
                onCompleted = { success ->
                    voiceSenders.remove(key)
                    if (success) {
                        clearVoiceProgress(key)
                    }
                    handler.post { callback(success) }
                }
            )
            voiceSenders[key] = sender
            updateVoiceProgress(key, VoiceTransferProgress(
                sessionCode = sessionCode,
                uuid = uuid,
                direction = VoiceTransferDirection.Upload,
                totalChunks = 0,
                confirmedChunks = 0,
                pendingChunks = 0,
                inFlightChunks = 0,
                state = VoiceTransferState.Initializing
            ))
        }
    }

    fun sendImageStream(
        sessionCode: String,
        uuid: String,
        mimeType: String,
        width: Int?,
        height: Int?,
        payload: ByteArray,
        callback: (Boolean) -> Unit
    ) {
        if (payload.isEmpty()) {
            handler.post { callback(false) }
            return
        }
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val contact = getContact(applicationContext, sessionCode)
            val keyBytes = decodeContactAesKey(contact?.aesKey)
            val key = imageKey(sessionCode, uuid)
            val sender = ImageStreamSender(
                context = applicationContext,
                sessionCode = sessionCode,
                uuid = uuid,
                mime = mimeType,
                width = width,
                height = height,
                originalBytes = payload,
                aesKey = keyBytes,
                scope = serviceScope,
                frameWriter = { json ->
                    val payloadBytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
                    thread.write(payloadBytes)
                },
                onProgress = { progress -> updateImageProgress(key, progress) },
                onCompleted = { success ->
                    imageSenders.remove(key)
                    if (success) {
                        clearImageProgress(key)
                    }
                    handler.post { callback(success) }
                }
            )
            imageSenders[key] = sender
            updateImageProgress(
                key,
                ImageTransferProgress(
                    sessionCode = sessionCode,
                    uuid = uuid,
                    direction = ImageTransferDirection.Upload,
                    totalChunks = 0,
                    confirmedChunks = 0,
                    pendingChunks = 0,
                    inFlightChunks = 0,
                    state = ImageTransferState.Initializing
                )
            )
        }
    }

    fun sendFileStream(
        sessionCode: String,
        uuid: String,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Long,
        compression: String,
        payload: ByteArray,
        callback: (Boolean) -> Unit
    ) {
        if (payload.isEmpty()) {
            handler.post { callback(false) }
            return
        }
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val contact = getContact(applicationContext, sessionCode)
            val keyBytes = decodeContactAesKey(contact?.aesKey)
            val key = fileKey(sessionCode, uuid)
            val sender = FileStreamSender(
                context = applicationContext,
                sessionCode = sessionCode,
                uuid = uuid,
                displayName = displayName,
                originalSizeBytes = originalSizeBytes,
                mime = mimeType,
                compression = compression,
                originalBytes = payload,
                aesKey = keyBytes,
                scope = serviceScope,
                frameWriter = { json ->
                    val payloadBytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
                    thread.write(payloadBytes)
                },
                onCompleted = { success ->
                    fileSenders.remove(key)
                    handler.post { callback(success) }
                }
            )
            fileSenders[key] = sender
        }
    }

    fun sendOfflineMapShareForLocation(
        sessionCode: String,
        latitude: Double,
        longitude: Double,
        callback: (Boolean) -> Unit
    ) {
        val thread = connectedThreads[sessionCode]
        if (thread == null) {
            handler.post { callback(false) }
            return
        }
        serviceScope.launch {
            val payload = OfflineMapShareCoordinator.buildSharePayloadForLocation(
                context = applicationContext,
                latitude = latitude,
                longitude = longitude
            )
            if (payload == null) {
                handler.post { callback(false) }
                return@launch
            }
            val contact = getContact(applicationContext, sessionCode)
            val keyBytes = decodeContactAesKey(contact?.aesKey)
            val uuid = UUID.randomUUID().toString()
            val key = mapShareKey(sessionCode, uuid)
            val sender = ImageStreamSender(
                context = applicationContext,
                sessionCode = sessionCode,
                uuid = uuid,
                mime = OFFLINE_MAP_SHARE_MIME,
                width = null,
                height = null,
                originalBytes = payload,
                aesKey = keyBytes,
                scope = serviceScope,
                frameWriter = { json ->
                    val payloadBytes = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
                    thread.write(payloadBytes)
                },
                onProgress = { /* silent background map share */ },
                onCompleted = { success ->
                    mapShareSenders.remove(key)
                    handler.post { callback(success) }
                }
            )
            mapShareSenders[key] = sender
        }
    }

    fun performHandshake(
        sessionCode: String,
        aesKeyBase64: String,
        callback: (Boolean) -> Unit
    ) {
        handshakeDelegate.performHandshake(sessionCode, aesKeyBase64, callback = callback)
    }

    fun performHandshake(
        sessionCode: String,
        aesKeyBase64: String,
        preferredAddress: String?,
        callback: (Boolean) -> Unit
    ) {
        handshakeDelegate.performHandshake(
            sessionCode = sessionCode,
            aesKeyBase64 = aesKeyBase64,
            preferredAddress = preferredAddress,
            callback = callback
        )
    }

    private fun handleHandshakeRequest(sessionCode: String, payload: String) {
        handshakeDelegate.handleHandshakeRequest(sessionCode, payload)
    }

    private fun handleHandshakeAck(sessionCode: String, payload: String) {
        handshakeDelegate.handleHandshakeAck(sessionCode, payload)
    }

    private fun handleContactInfo(sessionCode: String, payload: String) {
        handshakeDelegate.handleContactInfo(sessionCode, payload)
    }

    fun acknowledgeMessage(sessionCode: String, messageUuid: String) {
        if (messageUuid.isBlank()) {
            return
        }
        val thread = connectedThreads[sessionCode] ?: return
        val payload = "ACK|$messageUuid\n"
        thread.write(payload.toByteArray(Charsets.UTF_8))
    }

    private fun sendPeerName(sessionCode: String) {
        serviceScope.launch {
            val localName = runCatching { getSavedUserName(applicationContext).first().trim() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val thread = connectedThreads[sessionCode] ?: return@launch
            val payload = "PEER_NAME|$localName\n"
            thread.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    /**
     * Announces our internet identity (Firebase uid + long-term public key) over the Bluetooth link
     * so the peer can stamp it onto their contact and reach us over the internet when Bluetooth
     * drops — no QR re-scan needed. Sent on every connection, so it self-heals a uid that changed
     * (e.g. after a data wipe). The key is base64 (no '|'/newline), so '|' is a safe delimiter.
     */
    private fun sendPeerIdentity(sessionCode: String) {
        serviceScope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@launch
            val publicKey = runCatching { MessagingIdentity(applicationContext).publicKeyBase64() }
                .getOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: return@launch
            val thread = connectedThreads[sessionCode] ?: return@launch
            val payload = "PEER_IDENTITY|$uid|$publicKey\n"
            thread.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    fun isSessionActive(sessionCode: String): Boolean =
        activeSessions.value.contains(sessionCode)

    val bleFallbackConnectionStates: Flow<BleClientManager.ConnectionState>
        get() = bleFallbackClientManager.connectionStates

    fun registerBleFallbackSessionAlias(address: String, sessionCode: String) {
        bleFallbackClientManager.registerSessionAlias(address, sessionCode)
    }

    fun currentBleFallbackState(address: String): BleClientManager.ConnectionState? {
        return bleFallbackClientManager.currentState(address)
    }

    fun connectBleFallback(address: String) {
        bleFallbackClientManager.connectTo(address)
    }

    fun disconnectBleFallback(address: String) {
        bleFallbackClientManager.disconnect(address)
    }

    suspend fun sendBleFallbackMessageAwait(address: String, text: String): Boolean {
        return bleFallbackClientManager.sendMessageAwait(address, text)
    }

    suspend fun sendBleFallbackReadReceiptAwait(address: String, messageIds: Collection<String>): Boolean {
        return bleFallbackClientManager.sendReadReceiptAwait(address, messageIds)
    }

    fun shutdownBleFallback() {
        bleFallbackClientManager.shutdown()
    }

    private fun observeBleFallbackIncomingMessages() {
        if (bleFallbackMessageJob?.isActive == true) {
            return
        }
        bleFallbackMessageJob = serviceScope.launch {
            bleFallbackClientManager.incomingMessages.collect { incoming ->
                val normalizedAddress = normalizeMacAddress(incoming.address).ifBlank {
                    incoming.address.trim().uppercase(Locale.US)
                }
                if (normalizedAddress.isBlank()) {
                    return@collect
                }
                val sessionCode = resolveIncomingBleFallbackSessionCode(
                    normalizedAddress = normalizedAddress,
                    route = incoming.route
                )
                val text = incoming.message.dropLastWhile { it.code == 0 }
                if (text.isBlank()) {
                    return@collect
                }
                val messageId = incoming.messageId
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: UUID.randomUUID().toString()
                val contact = upsertBleFallbackContactForNotification(
                    sessionCode = sessionCode,
                    normalizedAddress = normalizedAddress
                )
                val inserted = runCatching {
                    saveRemoteMessage(
                        context = applicationContext,
                        sessionCode = sessionCode,
                        uuid = messageId,
                        text = text,
                        createdAtMillis = incoming.createdAtMillis,
                        receivedAtMillis = incoming.timestampMs,
                        senderDisplayName = contact.name,
                        senderAddress = normalizedAddress
                    )
                }.getOrElse { throwable ->
                    Log.w(TAG, "[$normalizedAddress] Failed to persist BLE fallback message", throwable)
                    false
                }
                if (inserted) {
                    maybeNotifyIncomingMessage(sessionCode, MessageType.TEXT, text)
                }
            }
        }
    }

    private fun upsertBleFallbackContactForNotification(
        sessionCode: String,
        normalizedAddress: String
    ): Contact {
        val existing = getContact(applicationContext, sessionCode)
        val resolvedName = existing?.name?.trim().takeUnless { it.isNullOrEmpty() }
            ?: getString(R.string.rescue_unknown_user)
        val contact = existing?.copy(
            name = resolvedName,
            address = normalizedAddress
        ) ?: Contact(
            name = resolvedName,
            aesKey = "",
            sessionCode = sessionCode,
            address = normalizedAddress
        )
        if (existing == null || existing != contact) {
            saveContact(applicationContext, contact)
        }
        return contact
    }

    private fun resolveIncomingBleFallbackSessionCode(
        normalizedAddress: String,
        route: String?
    ): String {
        return if (route.equals(OUTBOUND_ROUTE_BLE_GATT_FALLBACK, ignoreCase = true)) {
            resolveBleFallbackSessionCodeForAddress(normalizedAddress)
        } else {
            BleSessionResolver.sessionCodeForAddress(normalizedAddress)
        }
    }

    private fun resolveBleFallbackSessionCodeForAddress(normalizedAddress: String): String {
        val contacts = runCatching { getContacts(applicationContext) }
            .getOrNull()
            .orEmpty()
        val matched = contacts
            .asSequence()
            .filter { contact ->
                normalizeMacAddress(contact.address) == normalizedAddress
            }
            .sortedBy { contact ->
                if (contact.sessionCode.startsWith("ble:", ignoreCase = true)) 1 else 0
            }
            .mapNotNull { contact ->
                contact.sessionCode.trim().takeIf { it.isNotBlank() }
            }
            .firstOrNull()
        if (!matched.isNullOrBlank()) {
            return matched
        }
        val activeSession = ActiveChatTracker.activeSession.value
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.startsWith("ble:", ignoreCase = true) }
        if (activeSession != null) {
            val activeContact = contacts.firstOrNull { contact ->
                contact.sessionCode.equals(activeSession, ignoreCase = true)
            }
            if (activeContact != null) {
                runCatching {
                    updateContactAddress(applicationContext, activeSession, normalizedAddress)
                }
                return activeSession
            }
        }
        val nonBleWithoutAddress = contacts
            .asSequence()
            .filter { contact -> !contact.sessionCode.startsWith("ble:", ignoreCase = true) }
            .filter { contact -> normalizeMacAddress(contact.address).isEmpty() }
            .map { contact -> contact.sessionCode.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (nonBleWithoutAddress.size == 1) {
            val resolved = nonBleWithoutAddress.first()
            runCatching {
                updateContactAddress(applicationContext, resolved, normalizedAddress)
            }
            return resolved
        }
        return BleSessionResolver.sessionCodeForAddress(normalizedAddress)
    }

    fun ensureListening(reason: String? = null) {
        val secureAlive = secureAcceptThread?.isAlive == true
        if (secureAlive) {
            return
        }
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "ensureListening: missing BLUETOOTH_CONNECT (reason=$reason)")
            ensureListeningWithRetry()
            return
        }
        if (!isClassicBluetoothOn()) {
            Log.w(
                TAG,
                "ensureListening: Bluetooth classic is not ON (state=${currentBluetoothStateLabel()}, reason=$reason)"
            )
            ensureListeningWithRetry()
            return
        }
        Log.i(TAG, "ensureListening: starting server (reason=$reason)")
        startServer()
    }

    fun ensureListeningWithRetry() {
        if (hasBluetoothPermission() && isClassicBluetoothOn()) {
            listeningRetryScheduled.set(false)
            ensureListening("retry")
        } else {
            scheduleListeningRetry("retry", PERMISSION_RETRY_DELAY_MS)
        }
    }

    private fun scheduleListeningRetry(reason: String, delayMs: Long) {
        if (listeningRetryScheduled.compareAndSet(false, true)) {
            handler.postDelayed({
                listeningRetryScheduled.set(false)
                ensureListening(reason)
            }, delayMs)
        }
    }

    private fun startServer() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "startServer: missing BLUETOOTH_CONNECT permission")
            return
        }
        if (!isClassicBluetoothOn()) {
            Log.w(TAG, "startServer: Bluetooth classic is not ON (state=${currentBluetoothStateLabel()})")
            ensureListeningWithRetry()
            return
        }
        startAcceptThread(AcceptMode.SECURE)
    }

    private fun startAcceptThread(mode: AcceptMode) {
        val current = secureAcceptThread
        if (current?.isAlive == true) {
            return
        }
        val thread = AcceptThread(mode)
        secureAcceptThread = thread
        thread.start()
    }

    private fun stopAcceptThread() {
        secureAcceptThread?.cancel()
        secureAcceptThread = null
    }

    @Synchronized
    private fun manageConnectedSocket(socket: BluetoothSocket, sessionCode: String) {
        val pending = connectThreads.remove(sessionCode)
        // Eğer manageConnectedSocket() çağrısını o anda çalışmakta olan ConnectThread yapıyorsa,
        // cancel çağrısı aynı soketi kapatır. Bunu engelle:
        if (pending != null && pending !== Thread.currentThread()) {
            pending.cancel()   // sadece farklı bir thread ise iptal et
        }

        val remoteAddress = try {
            socket.remoteDevice?.address
        } catch (_: SecurityException) {
            null
        }
        val normalizedAddress = remoteAddress?.let { normalizeMacAddress(it) }?.takeIf { it.isNotBlank() }
        if (normalizedAddress != null) {
            serviceScope.launch {
                updateContactAddress(applicationContext, sessionCode, normalizedAddress)
            }
        }

        cancelReconnect(sessionCode)
        connectedThreads.remove(sessionCode)?.cancel()
        val connectedThread = ConnectedThread(sessionCode, socket)
        connectedThreads[sessionCode] = connectedThread
        updateActiveSessions(sessionCode, connected = true)
        connectedThread.start()
        sendPeerName(sessionCode)
        sendPeerIdentity(sessionCode)
    }

    private fun connectionFailed(sessionCode: String) {
        connectThreads.remove(sessionCode)
        updateActiveSessions(sessionCode, connected = false)
        ensureListening("connectionFailed")
    }

    private fun connectionLost(sessionCode: String, origin: ConnectedThread? = null) {
        Log.w(TAG, "RFCOMM connection lost for $sessionCode")
        val existing = connectedThreads.remove(sessionCode)
        if (existing != null && existing !== origin) {
            existing.cancel()
        }
        handshakeDelegate.failPendingForSession(sessionCode)
        val prefix = "$sessionCode|"
        voiceSenders.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        voiceReceivers.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        imageSenders.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        imageReceivers.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        fileSenders.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        fileReceivers.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        mapShareSenders.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        mapShareReceivers.entries.toList().filter { it.key.startsWith(prefix) }.forEach { entry ->
            entry.value.cancel()
        }
        callSessions.remove(sessionCode)?.let { session ->
            teardownCall(session, "error", notifyPeer = false)
            clearCallState(sessionCode)
            broadcastCallEvent(session.sessionCode, session.callId, CALL_ENDED_FLAG)
            stopSelf()
        }
        updateActiveSessions(sessionCode, connected = false)
        ensureListening("connectionLost")
        scheduleReconnect(sessionCode)
    }

    private fun scheduleReconnect(sessionCode: String) {
        reconnectJobs.remove(sessionCode)?.cancel()
        val job = serviceScope.launch {
            val delays = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
            for (attempt in delays.indices) {
                delay(delays[attempt])
                if (connectedThreads.containsKey(sessionCode)) {
                    Log.i(TAG, "Reconnect[$sessionCode]: already connected, stopping retry")
                    return@launch
                }
                if (!isClassicBluetoothOn()) {
                    Log.w(TAG, "Reconnect[$sessionCode]: BT off, stopping retry at attempt ${attempt + 1}")
                    return@launch
                }
                val contact = getContact(applicationContext, sessionCode)
                if (contact == null) {
                    Log.w(TAG, "Reconnect[$sessionCode]: no contact found, stopping retry")
                    return@launch
                }
                Log.i(TAG, "Reconnect[$sessionCode]: attempt ${attempt + 1}/${delays.size} after ${delays[attempt]}ms")
                val connected = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                    connectToContact(sessionCode) { success ->
                        if (cont.isActive) cont.resume(success, null)
                    }
                }
                if (connected) {
                    Log.i(TAG, "Reconnect[$sessionCode]: reconnected at attempt ${attempt + 1}")
                    return@launch
                }
            }
            Log.w(TAG, "Reconnect[$sessionCode]: all ${delays.size} attempts exhausted")
        }
        reconnectJobs[sessionCode] = job
    }

    private fun cancelReconnect(sessionCode: String) {
        reconnectJobs.remove(sessionCode)?.cancel()
    }

    fun stopAll() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        connectExecutor.shutdownNow()
        stopAcceptThread()
        connectThreads.values.forEach { it.cancel() }
        connectThreads.clear()
        synchronized(connectCallbackLock) {
            pendingConnectCallbacks.clear()
        }
        connectedThreads.values.forEach { it.cancel() }
        connectedThreads.clear()
        voiceSenders.values.forEach { it.cancel() }
        voiceSenders.clear()
        voiceReceivers.values.forEach { it.cancel() }
        voiceReceivers.clear()
        imageSenders.values.forEach { it.cancel() }
        imageSenders.clear()
        imageReceivers.values.forEach { it.cancel() }
        imageReceivers.clear()
        fileSenders.values.forEach { it.cancel() }
        fileSenders.clear()
        fileReceivers.values.forEach { it.cancel() }
        fileReceivers.clear()
        mapShareSenders.values.forEach { it.cancel() }
        mapShareSenders.clear()
        mapShareReceivers.values.forEach { it.cancel() }
        mapShareReceivers.clear()
        handshakeDelegate.failAllPending()
        callSessions.values.forEach { session -> teardownCall(session, "error", notifyPeer = false) }
        callSessions.clear()
        callRuntimes.clear()
        _calls.value = emptyMap()
        _voiceTransfers.value = emptyMap()
        _imageTransfers.value = emptyMap()
        _activeSessions.value = emptySet()
        bleFallbackClientManager.shutdown()
    }

    private fun resolveSessionCode(device: BluetoothDevice?): String? {
        if (device == null || !hasBluetoothPermission()) {
            return null
        }
        val isBonded = try {
            device.bondState == BluetoothDevice.BOND_BONDED
        } catch (_: SecurityException) {
            false
        }
        return ensureContactForDevice(
            applicationContext,
            device.address,
            getDeviceName(device),
            isBonded
        )
    }

    private fun updateActiveSessions(sessionCode: String, connected: Boolean) {
        _activeSessions.update { current ->
            if (connected) current + sessionCode else current - sessionCode
        }
    }

    private fun trackCallStart(
        sessionCode: String,
        callId: String,
        direction: CallDirection,
        startedAt: Long = System.currentTimeMillis()
    ) {
        callRuntimeDelegate.trackCallStart(sessionCode, callId, direction, startedAt)
    }

    private fun markCallAnswered(sessionCode: String, callId: String, connectedAt: Long) {
        callRuntimeDelegate.markCallAnswered(sessionCode, callId, connectedAt)
    }

    private fun finalizeCallRuntime(
        sessionCode: String,
        callId: String,
        resultOverride: CallResult? = null
    ): CallNotificationSummary? {
        return callRuntimeDelegate.finalizeCallRuntime(sessionCode, callId, resultOverride)
    }

    private fun voiceKey(sessionCode: String, uuid: String): String = "$sessionCode|$uuid"

    private fun updateVoiceProgress(key: String, progress: VoiceTransferProgress) {
        _voiceTransfers.update { it + (key to progress) }
    }

    private fun clearVoiceProgress(key: String) {
        _voiceTransfers.update { it - key }
    }

    private fun imageKey(sessionCode: String, uuid: String): String = "$sessionCode|$uuid"
    private fun fileKey(sessionCode: String, uuid: String): String = "$sessionCode|$uuid"
    private fun mapShareKey(sessionCode: String, uuid: String): String = "$sessionCode|$uuid"

    private fun updateImageProgress(key: String, progress: ImageTransferProgress) {
        _imageTransfers.update { it + (key to progress) }
    }

    private fun clearImageProgress(key: String) {
        _imageTransfers.update { it - key }
    }

    private fun sendJsonLine(thread: ConnectedThread, json: JSONObject): Boolean {
        val payload = json.toString() + "\n"
        return thread.write(payload.toByteArray(Charsets.UTF_8))
    }

    private fun updateCallState(session: CallSession, state: CallState) {
        callStateDelegate.updateCallState(session, state)
        if (state == CallState.InCall) {
            RfcommTelecomCoordinator.onCallActive(session.sessionCode, session.callId)
        }
    }

    private fun publishCallState(session: CallSession) {
        callStateDelegate.publishCallState(session)
    }

    private fun clearCallState(sessionCode: String) {
        callStateDelegate.clearCallState(sessionCode)
    }

    private fun callNotificationId(sessionCode: String): Int {
        return callNotificationDelegate.callNotificationId(sessionCode)
    }

    private fun startRingtone(session: CallSession) {
        synchronized(ringtoneAudioLock) {
            stopOtherRingtonesLocked(exceptSessionCode = session.sessionCode)
            if (!hasAnyActiveRingtoneLocked()) {
                ringtonePreviousMode = audioManager.mode
                requestRingtoneAudioFocusLocked()
                runCatching { audioManager.mode = AudioManager.MODE_RINGTONE }
            }
            session.ringtone = callNotificationDelegate.startRingtone(session.ringtone)
            if (session.ringtone?.isPlaying == true) {
                activeRingingSessionCode = session.sessionCode
            } else if (!hasAnyActiveRingtoneLocked()) {
                restoreRingtoneAudioStateLocked()
            }
        }
    }

    private fun stopRingtone(session: CallSession) {
        synchronized(ringtoneAudioLock) {
            callNotificationDelegate.stopRingtone(session.ringtone)
            session.ringtone = null
            if (activeRingingSessionCode == session.sessionCode) {
                activeRingingSessionCode = null
            }
            if (!hasAnyActiveRingtoneLocked()) {
                restoreRingtoneAudioStateLocked()
            }
        }
    }

    private fun showIncomingCallNotification(session: CallSession) {
        RfcommTelecomCoordinator.onIncomingCall(
            sessionCode = session.sessionCode,
            callId = session.callId,
            displayName = resolveCallerName(session)
        )
        val canUseFullScreenIntent = canRequestFullScreenIntent()
        Log.i(
            TAG,
            "Posting incoming call notification for ${session.sessionCode}/${session.callId} " +
                "with canUseFullScreenIntent=$canUseFullScreenIntent screenInteractive=${isScreenInteractiveForIncomingUi()}"
        )
        callUiDelegate.showIncomingCallNotification(session)
        // Keep the direct-launch fallback narrow. On newer Android versions, the system may still
        // show its own lockscreen/HUN fallback for the attached FSI even when direct starts are restricted.
        if (!canUseFullScreenIntent && !isScreenInteractiveForIncomingUi()) {
            launchCallScreen(session.sessionCode, session.callId)
        }
    }

    private fun canRequestFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { notificationManager.canUseFullScreenIntent() }
            .getOrDefault(false)
    }

    private fun buildIncomingCallNotification(
        name: String,
        caller: Person,
        contentIntent: PendingIntent,
        acceptPending: PendingIntent,
        rejectPending: PendingIntent,
        useCallStyle: Boolean
    ): NotificationCompat.Builder {
        return callNotificationDelegate.buildIncomingCallNotification(
            name = name,
            caller = caller,
            contentIntent = contentIntent,
            acceptPending = acceptPending,
            rejectPending = rejectPending,
            useCallStyle = useCallStyle
        )
    }

    private fun buildOngoingCallNotification(
        session: CallSession,
        useCallStyle: Boolean
    ): Notification {
        ensureOngoingCallNotificationChannel()
        return callNotificationDelegate.buildOngoingCallNotification(
            sessionCode = session.sessionCode,
            callId = session.callId,
            muted = session.muted.value,
            speakerEnabled = session.speakerEnabled.value,
            encrypted = session.cfg.encrypted,
            startedAt = session.startedAt,
            connectedAt = session.connectedAt,
            remoteUserName = resolveCallerName(session),
            useCallStyle = useCallStyle
        )
    }

    private fun canUseForegroundCallStyle(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false
        }
        return foregroundStarted.get() &&
                foregroundHasPhoneCallType.get() &&
                supportsPhoneCallForegroundType()
    }

    private fun updateCallStyleForeground(sessionCode: String, notification: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    baseForegroundServiceTypes(includePhoneCallType = true)
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted.set(true)
            foregroundHasPhoneCallType.set(true)
            callStyleForegroundSession.set(sessionCode)
            return true
        } catch (throwable: Throwable) {
            if (hasPostNotificationsPermission()) {
                notifyNotificationManager(callNotificationId(sessionCode), notification)
            } else {
                Log.w(TAG, "CallStyle foreground update failed and fallback notification is not permitted", throwable)
            }
            return false
        }
    }

    private fun callScreenPendingIntent(
        sessionCode: String,
        callId: String,
        flags: Int,
        requestOffset: Int
    ): PendingIntent {
        return callNotificationDelegate.createCallScreenPendingIntent(
            sessionCode = sessionCode,
            callId = callId,
            flags = flags,
            requestOffset = requestOffset
        )
    }

    private fun formatElapsed(elapsedSeconds: Long): String {
        return callNotificationDelegate.formatElapsed(elapsedSeconds)
    }

    private fun resolveCallerName(session: CallSession): String {
        val sessionCode = session.sessionCode
        session.remoteDisplayName
            ?.trim()
            ?.takeIf {
                it.isNotBlank() && !BlePeerIdentityUtils.looksLikeBleIdentifier(it, sessionCode)
            }
            ?.let { return it }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return sessionCode
        }

        val contactName = getContact(applicationContext, sessionCode)
            ?.name
            ?.trim()
            ?.takeIf {
                it.isNotBlank() && !BlePeerIdentityUtils.looksLikeBleIdentifier(it, sessionCode)
            }
        if (contactName != null) {
            session.remoteDisplayName = contactName
            return contactName
        }
        return sessionCode
    }

    private fun broadcastCallState(session: CallSession) {
        callStateDelegate.broadcastCallState(session)
    }

    private fun broadcastCallEvent(sessionCode: String, callId: String, flag: String) {
        callStateDelegate.broadcastCallEvent(sessionCode, callId, flag)
    }

    private fun launchCallScreen(sessionCode: String, callId: String) {
        val launchIntent = MainActivity.createTrustedLaunchIntent(this).apply {
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_SESSION, sessionCode)
            putExtra(MainActivity.EXTRA_NAVIGATE_TO_CALL, callId)
            putExtra(MainActivity.EXTRA_WAKE_FOR_INCOMING_CALL, true)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching {
            startActivity(launchIntent)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to launch incoming call screen directly", throwable)
        }
    }

    private fun isScreenInteractiveForIncomingUi(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java) ?: return true
        return powerManager.isInteractive
    }

    private fun isDeviceLockedForIncomingUi(): Boolean {
        val keyguardManager = getSystemService(KeyguardManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            keyguardManager.isDeviceLocked
        } else {
            @Suppress("DEPRECATION")
            keyguardManager.isKeyguardLocked
        }
    }

    private fun notifyCallEnded(session: CallSession, summary: CallNotificationSummary) {
        callUiDelegate.notifyCallEnded(
            session = session,
            direction = summary.direction,
            result = summary.result,
            durationMillis = summary.durationMillis
        )
    }

    private fun restoreServiceNotificationIfIdle() {
        val hasActiveCall = callSessions.values.any {
            val state = it.state.value
            state == CallState.Ringing || state == CallState.Connecting || state == CallState.InCall
        }
        if (!hasActiveCall) {
            startForegroundRobust(buildNotification())
        }
    }

    private fun clearCallStyleForeground(sessionCode: String) {
        callUiDelegate.clearCallStyleForeground(sessionCode)
    }

    private fun cancelCallNotification(sessionCode: String) {
        callUiDelegate.cancelCallNotification(sessionCode)
    }

    private fun cancelPostedCallNotification(sessionCode: String) {
        callUiDelegate.cancelPostedCallNotification(sessionCode)
    }

    private fun startOngoingCallNotification(session: CallSession) {
        callUiDelegate.startOngoingCallNotification(session)
    }

    private fun postOngoingCallNotification(session: CallSession) {
        callUiDelegate.postOngoingCallNotification(session)
    }

    private fun stopOngoingCallNotification(session: CallSession) {
        callUiDelegate.stopOngoingCallNotification(session)
    }

    private fun hasPostNotificationsPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleCallTimeout(session: CallSession) {
        callStateDelegate.scheduleCallTimeout(session)
    }

    private fun cancelCallTimeout(session: CallSession) {
        callStateDelegate.cancelCallTimeout(session)
    }

    private fun prepareAudioPipelines(session: CallSession, thread: ConnectedThread): Boolean {
        return callAudioPipelineDelegate.prepareAudioPipelines(session, thread)
    }

    private fun resolveOpusApplication(): Int = callProtocolDelegate.resolveOpusApplication()

    private fun setEncoderBitrate(opus: Opus, bitrate: Int) {
        callProtocolDelegate.setEncoderBitrate(opus, bitrate)
    }

    private fun enableOpusControls(opus: Opus, bitrate: Int) {
        callProtocolDelegate.enableOpusControls(opus, bitrate)
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecorder(cfg: AudioCfg): AudioRecord? {
        if (!hasRecordAudioPermission()) {
            return null
        }
        val channelMask = if (cfg.ch == 1) {
            AudioFormat.CHANNEL_IN_MONO
        } else {
            AudioFormat.CHANNEL_IN_STEREO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            cfg.sr,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            return null
        }
        val target = max(minBuffer + cfg.frameBytes, cfg.frameBytes * 2)
        val bufferSize = target.coerceIn(minBuffer, minBuffer * 2)
        val recorder = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val builder = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(channelMask)
                            .setSampleRate(cfg.sr)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                builder.build()
            } else {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    cfg.sr,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
        } catch (_: Exception) {
            null
        }
        if (recorder == null) {
            return null
        }
        return if (recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder
        } else {
            runCatching { recorder.release() }
            null
        }
    }

    private fun createAudioTrack(cfg: AudioCfg): AudioTrack? {
        val channelMask = if (cfg.ch == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }
        val minBuffer = AudioTrack.getMinBufferSize(
            cfg.sr,
            channelMask,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            return null
        }
        val target = max(minBuffer + cfg.frameBytes, cfg.frameBytes * 2)
        val bufferSize = target.coerceIn(minBuffer, minBuffer * 3)
        val track = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setLegacyStreamType(AudioManager.STREAM_VOICE_CALL)
                    .build()
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(audioAttributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(cfg.sr)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                // Avoid forcing low-latency mode on Oreo where some devices mis-handle call routing.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                }
                builder.build()
            } else {
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    cfg.sr,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
        } catch (_: Exception) {
            null
        }
        if (track == null) {
            return null
        }
        return if (track.state == AudioTrack.STATE_INITIALIZED) {
            track
        } else {
            runCatching { track.release() }
            null
        }
    }

    private fun startSenderJob(session: CallSession, thread: ConnectedThread): Job {
        val cfg = session.cfg
        val opus = session.opus ?: return serviceScope.launch { }
        val recorder = session.recorder ?: return serviceScope.launch { }
        val frameConst = session.frameSizeConst ?: opusFrameSize(cfg.frameSamples) ?: return serviceScope.launch { }
        val useEncryption = cfg.encrypted && session.aesKey != null
        val payloads = Array(cfg.fps) { "" }
        val ivs = Array<String?>(cfg.fps) { null }
        val payloadLengths = IntArray(cfg.fps)
        val frameCrcs = LongArray(cfg.fps)
        val pcmBuffer = ByteArray(cfg.frameBytes)
        return serviceScope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (isActive && session.state.value != CallState.Ended) {
                var produced = 0
                val packetMuted = session.muted.value
                while (produced < cfg.fps && isActive && session.state.value != CallState.Ended) {
                    val readBytes = if (!packetMuted) {
                        val read = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            recorder.read(pcmBuffer, 0, pcmBuffer.size, AudioRecord.READ_BLOCKING)
                        } else {
                            recorder.read(pcmBuffer, 0, pcmBuffer.size)
                        }
                        if (read < pcmBuffer.size) {
                            pcmBuffer.fill(0, read.coerceAtLeast(0), pcmBuffer.size)
                        }
                        read
                    } else {
                        pcmBuffer.fill(0)
                        pcmBuffer.size
                    }
                    if (readBytes <= 0) {
                        continue
                    }
                    if (!packetMuted) {
                        val opusBytes = opus.encode(pcmBuffer, frameConst)
                        payloadLengths[produced] = opusBytes.size
                        frameCrcs[produced] = crc32(session.outboundCrc, opusBytes)
                        if (useEncryption) {
                            val encrypted = encryptAesGcm(session.aesKey!!, opusBytes) ?: continue
                            payloads[produced] = encrypted.second
                            ivs[produced] = encrypted.first
                        } else {
                            payloads[produced] = Base64.encodeToString(opusBytes, Base64.NO_WRAP)
                            ivs[produced] = null
                        }
                    }
                    produced += 1
                }
                if (produced == 0) {
                    continue
                }
                val firstSeq = session.seq.getAndAdd(produced) + 1
                val packetTimestamp = System.currentTimeMillis()
                val packetMonotonicTimestamp = SystemClock.elapsedRealtime()
                val voiceLatencyDiagnosticsEnabled = BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED
                val builder = session.outboundBuilder
                builder.setLength(0)
                if (produced == 1 && cfg.fps == 1) {
                    builder.append("{\"type\":\"call:audio\",\"id\":\"")
                    builder.append(session.callId)
                    builder.append("\",\"seq\":")
                    builder.append(firstSeq)
                    builder.append(",\"timestamp\":")
                    builder.append(packetTimestamp)
                    if (voiceLatencyDiagnosticsEnabled) {
                        builder.append(",\"monoTs\":")
                        builder.append(packetMonotonicTimestamp)
                    }
                    builder.append(",\"codec\":\"opus\"")
                    if (packetMuted) {
                        builder.append(",\"silent\":true")
                    } else {
                        builder.append(",\"payload_len\":")
                        builder.append(payloadLengths[0])
                        builder.append(",\"crc\":")
                        builder.append(frameCrcs[0])
                        builder.append(",\"sz\":")
                        builder.append(payloadLengths[0])
                        builder.append(",\"b64\":\"")
                        builder.append(payloads[0])
                        builder.append('\"')
                        val iv = ivs[0]
                        if (!iv.isNullOrEmpty()) {
                            builder.append(",\"ivB64\":\"")
                            builder.append(iv)
                            builder.append('\"')
                        }
                    }
                    builder.append("}\n")
                } else {
                    builder.append("{\"type\":\"call:audiox\",\"id\":\"")
                    builder.append(session.callId)
                    builder.append("\",\"seq\":")
                    builder.append(firstSeq)
                    builder.append(",\"timestamp\":")
                    builder.append(packetTimestamp)
                    if (voiceLatencyDiagnosticsEnabled) {
                        builder.append(",\"monoTs\":")
                        builder.append(packetMonotonicTimestamp)
                    }
                    builder.append(",\"codec\":\"opus\"")
                    builder.append(",\"n\":")
                    builder.append(produced)
                    if (packetMuted) {
                        builder.append(",\"silent\":true")
                    } else {
                        builder.append(",\"b64\":[")
                        for (i in 0 until produced) {
                            if (i > 0) builder.append(',')
                            builder.append('\"')
                            builder.append(payloads[i])
                            builder.append('\"')
                        }
                        builder.append(']')
                        builder.append(",\"payload_len\":[")
                        for (i in 0 until produced) {
                            if (i > 0) builder.append(',')
                            builder.append(payloadLengths[i])
                        }
                        builder.append(']')
                        builder.append(",\"crc\":[")
                        for (i in 0 until produced) {
                            if (i > 0) builder.append(',')
                            builder.append(frameCrcs[i])
                        }
                        builder.append(']')
                        builder.append(",\"sz\":[")
                        for (i in 0 until produced) {
                            if (i > 0) builder.append(',')
                            builder.append(payloadLengths[i])
                        }
                        builder.append(']')
                        if (useEncryption) {
                            builder.append(",\"iv\":[")
                            for (i in 0 until produced) {
                                if (i > 0) builder.append(',')
                                builder.append('\"')
                                builder.append(ivs[i])
                                builder.append('\"')
                            }
                            builder.append(']')
                        }
                    }
                    builder.append("}\n")
                }
                val line = builder.toString().toByteArray(Charsets.UTF_8)
                val writeStart = SystemClock.elapsedRealtime()
                when (thread.writeRealtime(line)) {
                    WriteResult.Sent -> {
                        val writeDuration = SystemClock.elapsedRealtime() - writeStart
                        updateWriteStats(session, writeDuration.toDouble())
                    }

                    WriteResult.Dropped -> {
                        session.netStats.transportDrops += 1
                    }

                    WriteResult.Failed -> {
                        Log.w(TAG, "Audio write failed for ${session.sessionCode}/${session.callId}; ending call")
                        endVoipCall(session.sessionCode, session.callId, reason = "error")
                        return@launch
                    }
                }
            }
        }
    }

    private fun startPlayerJob(session: CallSession): Job {
        val cfg = session.cfg
        val player = session.player ?: return serviceScope.launch { }
        val silence = ByteArray(cfg.frameBytes)
        val jitter = session.jitterBuffer
        return serviceScope.launch(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var lastLogTs = SystemClock.elapsedRealtime()
            while (isActive && session.state.value != CallState.Ended) {
                if (!jitter.shouldStartPlayback()) {
                    delay(cfg.frm.toLong())
                    continue
                }
                val frame = jitter.pollFrame()
                val data = frame ?: run {
                    val concealed = session.lastPlayoutFrame
                    if (concealed != null) {
                        session.netStats.concealedFrames += 1
                        concealed
                    } else {
                        session.netStats.playoutUnderruns += 1
                        silence
                    }
                }
                player.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
                if (frame != null) {
                    session.lastPlayoutFrame = frame
                }
                val depth = jitter.depthFrames()
                session.netStats.avgQueueDepth = session.netStats.avgQueueDepth * 0.8 + depth * 0.2
                val now = SystemClock.elapsedRealtime()
                if (now - lastLogTs > 2_000L) {
                    Log.d(
                        TAG,
                        "playback jitter depth=${jitter.depthMs()}ms target=${jitter.targetDelayMs()}ms underruns=${session.netStats.playoutUnderruns} loss=${session.netStats.lostFrames} ooo=${session.netStats.outOfOrderFrames} crcDrops=${session.netStats.invalidCrcFrames}"
                    )
                    lastLogTs = now
                }
            }
        }
    }

    private fun startAdaptationJob(session: CallSession, thread: ConnectedThread?): Job {
        return serviceScope.launch(Dispatchers.IO) {
            while (isActive && session.state.value != CallState.Ended) {
                val inCall = session.state.value == CallState.InCall
                delay(if (inCall) 1_000L else 5_000L)
                if (!inCall || session.awaitingAck) {
                    continue
                }
                val stats = session.netStats
                val now = SystemClock.elapsedRealtime()
                val cooldownActive = now - stats.lastAdaptTs < 6_000L
                val currentThread = connectedThreads[session.sessionCode] ?: thread
                if (session.profile == Profile.WB) {
                    val severeDegrade =
                        stats.transportDrops > 0 ||
                            stats.playoutUnderruns >= 4 ||
                            stats.lostFrames >= 4 ||
                            stats.invalidCrcFrames > 0
                    val degrade =
                        stats.movingAvgWriteMs > 20.0 ||
                            stats.playoutUnderruns >= 2 ||
                            stats.avgQueueDepth < 2.0 ||
                            stats.lostFrames >= 2 ||
                            stats.outOfOrderFrames >= 2 ||
                            stats.lateDrops > 0 ||
                            stats.transportDrops > 0 ||
                            stats.invalidCrcFrames > 0
                    stats.degradeScore = when {
                        severeDegrade -> stats.degradeScore + 2
                        degrade -> stats.degradeScore + 1
                        else -> 0
                    }
                    if (!cooldownActive && stats.degradeScore >= 2 && currentThread != null) {
                        requestProfileChange(session, currentThread, Profile.NB)
                        stats.degradeScore = 0
                        stats.lastAdaptTs = now
                    }
                } else {
                    val recover =
                        stats.movingAvgWriteMs < 8.0 &&
                            stats.transportDrops == 0 &&
                            stats.playoutUnderruns == 0 &&
                            stats.lostFrames == 0 &&
                            stats.outOfOrderFrames == 0 &&
                            stats.avgQueueDepth >= 3.0
                    stats.recoverScore = if (recover) stats.recoverScore + 1 else 0
                    if (!cooldownActive && stats.recoverScore >= 20 && currentThread != null) {
                        requestProfileChange(session, currentThread, Profile.WB)
                        stats.recoverScore = 0
                        stats.lastAdaptTs = now
                    }
                }
                stats.transportDrops = 0
                stats.playoutUnderruns = 0
                stats.lostFrames = 0
                stats.outOfOrderFrames = 0
                stats.duplicateFrames = 0
                stats.lateDrops = 0
                stats.invalidPayloadFrames = 0
                stats.invalidCrcFrames = 0
            }
        }
    }

    private fun startClockSyncJob(session: CallSession, thread: ConnectedThread?): Job {
        if (!BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED) {
            session.pendingClockSyncProbes.clear()
            return Job().apply { complete() }
        }
        return serviceScope.launch(Dispatchers.IO) {
            while (isActive && session.state.value != CallState.Ended) {
                if (session.state.value != CallState.InCall) {
                    delay(1_000L)
                    continue
                }
                val currentThread = connectedThreads[session.sessionCode] ?: thread
                if (currentThread != null) {
                    pruneExpiredClockSyncProbes(session)
                    val probeId = UUID.randomUUID().toString()
                    val sentAtMs = SystemClock.elapsedRealtime()
                    session.pendingClockSyncProbes[probeId] = sentAtMs
                    val sent = sendCallClockSyncRequest(currentThread, session.callId, probeId, sentAtMs)
                    if (sent) {
                        Log.i(
                            TAG,
                            "Clock sync request sent ${session.sessionCode}/${session.callId} " +
                                "probeId=$probeId monoSendMs=$sentAtMs"
                        )
                    } else {
                        session.pendingClockSyncProbes.remove(probeId)
                        Log.w(
                            TAG,
                            "Clock sync request failed ${session.sessionCode}/${session.callId} " +
                                "probeId=$probeId monoSendMs=$sentAtMs"
                        )
                    }
                }
                delay(CALL_CLOCK_SYNC_INTERVAL_MS)
            }
        }
    }

    private fun pruneExpiredClockSyncProbes(
        session: CallSession,
        nowMs: Long = SystemClock.elapsedRealtime()
    ) {
        session.pendingClockSyncProbes.entries.removeIf { (_, sentAtMs) ->
            nowMs - sentAtMs > CALL_CLOCK_SYNC_EXPIRY_MS
        }
    }

    private fun updateWriteStats(session: CallSession, durationMs: Double) {
        val stats = session.netStats
        stats.movingAvgWriteMs = if (stats.movingAvgWriteMs == 0.0) {
            durationMs
        } else {
            (stats.movingAvgWriteMs * 0.8) + (durationMs * 0.2)
        }
    }

    private fun crc32(bytes: ByteArray): Long {
        return crc32(sharedCrc32, bytes)
    }

    private fun crc32(crc: CRC32, bytes: ByteArray): Long {
        crc.reset()
        crc.update(bytes)
        return crc.value
    }

    private fun decodeIncomingCallPayload(
        session: CallSession,
        payloadB64: String,
        ivB64: String?,
        payloadLen: Int,
        crc: Long
    ): ByteArray? {
        val encodedBytes = if (session.cfg.encrypted) {
            val key = session.aesKey ?: run {
                session.netStats.invalidPayloadFrames += 1
                return null
            }
            val iv = ivB64?.takeIf { it.isNotBlank() } ?: run {
                session.netStats.invalidPayloadFrames += 1
                return null
            }
            decryptAesGcm(key, iv, payloadB64)
        } else {
            runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
        } ?: run {
            session.netStats.invalidPayloadFrames += 1
            return null
        }

        if (payloadLen > 0 && encodedBytes.size != payloadLen) {
            session.netStats.invalidPayloadFrames += 1
            return null
        }
        if (crc >= 0L && crc32(encodedBytes) != crc) {
            session.netStats.invalidCrcFrames += 1
            return null
        }
        return encodedBytes
    }

    private fun decodePlcFrame(opus: Opus, frameConst: Int): ByteArray? {
        return runCatching { opus.decode(ByteArray(0), frameConst, 0) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun processIncomingOpusFrame(
        session: CallSession,
        opus: Opus,
        frameConst: Int,
        encodedBytes: ByteArray,
        seq: Int
    ) {
        val lastSeq = session.lastInboundSeq
        if (seq > 0 && lastSeq > 0 && seq > lastSeq + 1) {
            val missingCount = seq - lastSeq - 1
            var recoveredWithFec = 0
            if (missingCount > 0) {
                val fecBytes = runCatching { opus.decode(encodedBytes, frameConst, 1) }.getOrNull()
                if (fecBytes != null && enqueueDecodedFrame(session, fecBytes, lastSeq + 1)) {
                    recoveredWithFec = 1
                    session.netStats.concealedFrames += 1
                }
            }
            var concealSeq = lastSeq + 1 + recoveredWithFec
            while (concealSeq < seq) {
                val concealFrame =
                    decodePlcFrame(opus, frameConst) ?: session.lastPlayoutFrame ?: ByteArray(session.cfg.frameBytes)
                if (enqueueDecodedFrame(session, concealFrame, concealSeq)) {
                    session.netStats.concealedFrames += 1
                }
                concealSeq += 1
            }
        }

        val pcmBytes = runCatching { opus.decode(encodedBytes, frameConst, 0) }
            .onFailure { Log.e(TAG, "Failed to decode opus frame", it) }
            .getOrNull()
        if (pcmBytes == null) {
            Log.e(TAG, "Decoded opus frame was null")
            return
        }
        if (!enqueueDecodedFrame(session, pcmBytes, seq)) {
            Log.e(TAG, "Dropping decoded frame after enqueue failure")
        }
        if (seq > session.lastInboundSeq) {
            session.lastInboundSeq = seq
        }
    }

    private fun enqueueSilenceFrames(session: CallSession, firstSeq: Int, count: Int) {
        if (count <= 0) {
            return
        }
        repeat(count) { index ->
            val seq = if (firstSeq > 0) firstSeq + index else -1
            if (!enqueueDecodedFrame(session, ByteArray(session.cfg.frameBytes), seq)) {
                Log.e(TAG, "Dropping synthesized silence frame after enqueue failure")
            }
            if (seq > session.lastInboundSeq) {
                session.lastInboundSeq = seq
            }
        }
    }

    private fun enqueueDecodedFrame(session: CallSession, pcmBytes: ByteArray, seq: Int): Boolean {
        val jitter = session.jitterBuffer
        return runCatching {
            val enqueue = jitter.addFrame(pcmBytes, seq)
            if (!enqueue.duplicate && !enqueue.droppedLate) {
                session.netStats.framesReceived += 1
            }
            if (enqueue.gapDetected > 0) {
                session.netStats.lostFrames += enqueue.gapDetected
            }
            if (enqueue.outOfOrder) {
                session.netStats.outOfOrderFrames += 1
            }
            if (enqueue.duplicate) {
                session.netStats.duplicateFrames += 1
            }
            if (enqueue.droppedLate) {
                session.netStats.lateDrops += 1
            }
            session.netStats.avgQueueDepth =
                session.netStats.avgQueueDepth * 0.8 + enqueue.depthFrames * 0.2
            true
        }.getOrElse {
            Log.e(TAG, "Failed to enqueue frame in jitter buffer", it)
            session.jitterBuffer = JitterBuffer(maxBufferedFrames = session.profile.jitterCapacity)
            false
        }
    }

    private fun sanitizeCfg(
        sampleRate: Int,
        frameMs: Int,
        framesPerPacket: Int,
        bitrate: Int,
        encrypted: Boolean,
        hasKey: Boolean
    ): Pair<Profile, AudioCfg>? {
        return callProtocolDelegate.sanitizeCfg(
            sampleRate = sampleRate,
            frameMs = frameMs,
            framesPerPacket = framesPerPacket,
            bitrate = bitrate,
            encrypted = encrypted,
            hasKey = hasKey
        )
    }

    private fun requestProfileChange(session: CallSession, thread: ConnectedThread, target: Profile) {
        if (session.profile == target) {
            return
        }
        if (session.awaitingAck) {
            return
        }
        val cfg = target.defaultCfg(session.cfg.encrypted).copy(frm = session.cfg.frm)
        session.pendingProfile = target
        session.pendingCfg = cfg
        session.awaitingAck = true
        session.netStats.lastAdaptTs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "Requesting call profile change ${session.profile} -> $target for ${session.sessionCode}/${session.callId} " +
                "(writeAvg=${"%.1f".format(Locale.US, session.netStats.movingAvgWriteMs)}ms, " +
                "drops=${session.netStats.transportDrops}, underruns=${session.netStats.playoutUnderruns}, " +
                "loss=${session.netStats.lostFrames}, ooo=${session.netStats.outOfOrderFrames})"
        )
        if (!sendCallCfg(thread, session.callId, cfg)) {
            session.pendingProfile = null
            session.pendingCfg = null
            session.awaitingAck = false
        }
    }

    private fun applyAudioConfig(
        session: CallSession,
        thread: ConnectedThread,
        profile: Profile,
        cfg: AudioCfg
    ): Boolean {
        Log.i(
            TAG,
            "Applying call profile $profile for ${session.sessionCode}/${session.callId} " +
                "(bitrate=${cfg.br}, fps=${cfg.fps}, frameMs=${cfg.frm})"
        )
        session.profile = profile
        session.cfg = cfg
        return prepareAudioPipelines(session, thread)
    }

    private fun enableAudioEffects(recorder: AudioRecord): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(3)
        fun attach(effect: AudioEffect?) {
            if (effect == null) {
                return
            }
            runCatching { effect.enabled = true }
            effects.add(effect)
        }
        if (AcousticEchoCanceler.isAvailable()) {
            attach(runCatching { AcousticEchoCanceler.create(recorder.audioSessionId) }.getOrNull())
        }
        if (NoiseSuppressor.isAvailable()) {
            attach(runCatching { NoiseSuppressor.create(recorder.audioSessionId) }.getOrNull())
        }
        if (AutomaticGainControl.isAvailable()) {
            attach(runCatching { AutomaticGainControl.create(recorder.audioSessionId) }.getOrNull())
        }
        return effects
    }

    private fun ensureCallAudioSession(session: CallSession): Boolean {
        if (session.audioSessionAcquired) {
            return false
        }
        synchronized(callAudioLock) {
            if (session.audioSessionAcquired) {
                return false
            }
            if (callAudioSessionRefs == 0) {
                callPreviousMode = audioManager.mode
                callPreviousSpeakerphone = audioManager.isSpeakerphoneOn
                callPreviousMicMute = audioManager.isMicrophoneMute
                requestCallAudioFocusLocked()
                runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                runCatching { audioManager.isMicrophoneMute = false }
                boostVoiceCallVolumeLocked()
                acquireCallWakeLockLocked()
            }
            callAudioSessionRefs += 1
            session.audioSessionAcquired = true
            return true
        }
    }

    private fun releaseCallAudioSession(session: CallSession) {
        if (!session.audioSessionAcquired) {
            return
        }
        synchronized(callAudioLock) {
            if (!session.audioSessionAcquired) {
                return
            }
            session.audioSessionAcquired = false
            if (callAudioSessionRefs > 0) {
                callAudioSessionRefs -= 1
            }
            if (callAudioSessionRefs > 0) {
                return
            }
            restoreVoiceCallVolumeLocked()
            abandonCallAudioFocusLocked()
            releaseCallWakeLockLocked()
            runCatching { audioManager.mode = callPreviousMode }
            runCatching { audioManager.isSpeakerphoneOn = callPreviousSpeakerphone }
            runCatching { audioManager.isMicrophoneMute = callPreviousMicMute }
        }
    }

    private fun requestCallAudioFocusLocked() {
        if (callHasAudioFocus) {
            return
        }
        callHasAudioFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val granted = runCatching {
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
            if (granted) {
                callAudioFocusRequest = request
            }
            granted
        } else {
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
        }
    }

    private fun abandonCallAudioFocusLocked() {
        if (!callHasAudioFocus) {
            callAudioFocusRequest = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callAudioFocusRequest?.let {
                runCatching { audioManager.abandonAudioFocusRequest(it) }
            }
            callAudioFocusRequest = null
        } else {
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        callHasAudioFocus = false
    }

    private fun acquireCallWakeLockLocked() {
        if (callWakeLock?.isHeld == true) {
            return
        }
        val powerManager = getSystemService(PowerManager::class.java) ?: return
        callWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "CrisisConnect:VoipCall"
        ).apply { acquire(WAKELOCK_TIMEOUT_MS) }
    }

    private fun releaseCallWakeLockLocked() {
        callWakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        callWakeLock = null
    }

    private fun requestRingtoneAudioFocusLocked() {
        if (ringtoneHasAudioFocus) {
            return
        }
        ringtoneHasAudioFocus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { }
                .build()
            val granted = runCatching {
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
            if (granted) {
                ringtoneAudioFocusRequest = request
            }
            granted
        } else {
            runCatching {
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_RING,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }.getOrDefault(false)
        }
    }

    private fun abandonRingtoneAudioFocusLocked() {
        if (!ringtoneHasAudioFocus) {
            ringtoneAudioFocusRequest = null
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ringtoneAudioFocusRequest?.let {
                runCatching { audioManager.abandonAudioFocusRequest(it) }
            }
            ringtoneAudioFocusRequest = null
        } else {
            runCatching { audioManager.abandonAudioFocus(null) }
        }
        ringtoneHasAudioFocus = false
    }

    private fun stopOtherRingtonesLocked(exceptSessionCode: String) {
        callSessions.values.forEach { otherSession ->
            if (otherSession.sessionCode == exceptSessionCode) {
                return@forEach
            }
            callNotificationDelegate.stopRingtone(otherSession.ringtone)
            otherSession.ringtone = null
        }
        if (activeRingingSessionCode != exceptSessionCode) {
            activeRingingSessionCode = null
        }
    }

    private fun hasAnyActiveRingtoneLocked(): Boolean {
        return callSessions.values.any { it.ringtone?.isPlaying == true }
    }

    private fun restoreRingtoneAudioStateLocked() {
        abandonRingtoneAudioFocusLocked()
        runCatching { audioManager.mode = ringtonePreviousMode }
    }

    private fun boostVoiceCallVolumeLocked() {
        if (callVoiceCallVolumeAdjusted) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && audioManager.isVolumeFixed) {
            return
        }
        val stream = AudioManager.STREAM_VOICE_CALL
        val maxVolume = runCatching { audioManager.getStreamMaxVolume(stream) }.getOrDefault(0)
        if (maxVolume <= 0) {
            return
        }
        val currentVolume = runCatching { audioManager.getStreamVolume(stream) }.getOrDefault(maxVolume)
        if (currentVolume >= maxVolume) {
            return
        }
        callPreviousVoiceCallVolume = currentVolume
        callVoiceCallVolumeAdjusted = true
        runCatching { audioManager.setStreamVolume(stream, maxVolume, 0) }
    }

    private fun restoreVoiceCallVolumeLocked() {
        if (!callVoiceCallVolumeAdjusted) {
            return
        }
        if (callPreviousVoiceCallVolume >= 0) {
            runCatching {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL,
                    callPreviousVoiceCallVolume,
                    0
                )
            }
        }
        callPreviousVoiceCallVolume = -1
        callVoiceCallVolumeAdjusted = false
    }

    private fun releaseOpusSafe(opus: Opus?) {
        if (opus == null) return
        // Try specific encoder/decoder release first (independent resources)
        for (methodName in arrayOf("encoderRelease", "decoderRelease")) {
            runCatching {
                opus.javaClass.getMethod(methodName).invoke(opus)
            }
        }
        // Try general release — break on first success to avoid double-free
        for (methodName in arrayOf("release", "close", "destroy")) {
            val success = runCatching {
                opus.javaClass.getMethod(methodName).invoke(opus)
                true
            }.getOrDefault(false)
            if (success) break
        }
    }

    private fun releaseAudioEffects(session: CallSession) {
        val toRelease = session.audioEffects
        session.audioEffects = emptyList()
        toRelease.forEach { effect ->
            runCatching { effect.enabled = false }
            runCatching { effect.release() }
        }
    }

    private fun teardownCall(
        session: CallSession,
        reason: String,
        notifyPeer: Boolean,
        resultOverride: CallResult? = null
    ) {
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.RFCOMM, session.callId)
        val fallbackDirection = if (session.isOutgoing) {
            CallDirection.OUTGOING
        } else {
            CallDirection.INCOMING
        }
        val fallbackResult = resultOverride ?: when {
            session.connectedAt != null -> CallResult.ANSWERED
            fallbackDirection == CallDirection.INCOMING -> CallResult.MISSED
            else -> CallResult.CANCELED
        }
        val fallbackDurationMillis = if (fallbackResult == CallResult.ANSWERED) {
            val connectedAt = session.connectedAt ?: session.startedAt
            (System.currentTimeMillis() - connectedAt).coerceAtLeast(0L)
        } else {
            null
        }
        cancelCallTimeout(session)
        stopRingtone(session)
        cancelCallNotification(session.sessionCode)
        session.senderJob?.cancel()
        session.playerJob?.cancel()
        session.adaptationJob?.cancel()
        session.syncJob?.cancel()
        session.senderJob = null
        session.playerJob = null
        session.adaptationJob = null
        session.syncJob = null
        releaseOpusSafe(session.opus)
        session.opus = null
        session.frameSizeConst = null
        session.lastInboundSeq = -1
        session.lastPlayoutFrame = null
        releaseAudioEffects(session)
        runCatching { session.recorder?.stop() }
        runCatching { session.recorder?.release() }
        session.recorder = null
        runCatching { session.player?.stop() }
        runCatching { session.player?.release() }
        session.player = null
        releaseCallAudioSession(session)
        session.jitterBuffer.reset()
        session.connectedAt = null
        session.pendingClockSyncProbes.clear()
        session.state.value = CallState.Ended
        publishCallState(session)
        stopOngoingCallNotification(session)
        if (notifyPeer) {
            connectedThreads[session.sessionCode]?.let { thread ->
                sendJsonLine(thread, buildEnd(session.callId, reason))
            }
        }
        val summary = finalizeCallRuntime(session.sessionCode, session.callId, resultOverride)
            ?: CallNotificationSummary(
                direction = fallbackDirection,
                result = fallbackResult,
                durationMillis = fallbackDurationMillis
            )
        Log.i(
            TAG,
            "Tearing down call ${session.sessionCode}/${session.callId} " +
                "reason=$reason result=${summary.result} durationMs=${summary.durationMillis}"
        )
        if (BuildConfig.VOICE_LATENCY_DIAGNOSTICS_ENABLED) {
            val observedAgeSummary = session.netStats.receiverObservedAgeSummary()
            if (observedAgeSummary != null) {
                Log.i(
                    TAG,
                    "Call latency summary ${session.sessionCode}/${session.callId} " +
                        "kind=receiverObservedPacketAge " +
                        "samples=${observedAgeSummary.sampleCount} " +
                        "minMs=${observedAgeSummary.minMs} " +
                        "medianMs=${observedAgeSummary.medianMs} " +
                        "p95Ms=${observedAgeSummary.p95Ms} " +
                        "maxMs=${observedAgeSummary.maxMs} " +
                        "avgMs=${"%.1f".format(Locale.US, observedAgeSummary.averageMs)} " +
                        "clamped=${observedAgeSummary.clampedSamples} " +
                        "discarded=${observedAgeSummary.discardedSamples}"
                )
            } else if (
                session.netStats.receiverObservedAgeClampedSamples > 0 ||
                session.netStats.receiverObservedAgeDiscardedSamples > 0
            ) {
                Log.i(
                    TAG,
                    "Call latency summary ${session.sessionCode}/${session.callId} " +
                        "kind=receiverObservedPacketAge samples=0 " +
                        "clamped=${session.netStats.receiverObservedAgeClampedSamples} " +
                        "discarded=${session.netStats.receiverObservedAgeDiscardedSamples}"
                )
            }
            val clockAdjustedSummary = session.netStats.clockAdjustedAgeSummary()
            val clockSyncSummary = session.netStats.clockSyncSummary()
            if (clockAdjustedSummary != null) {
                Log.i(
                    TAG,
                    "Call latency summary ${session.sessionCode}/${session.callId} " +
                        "kind=clockAdjustedPacketAge " +
                        "samples=${clockAdjustedSummary.sampleCount} " +
                        "minMs=${clockAdjustedSummary.minMs} " +
                        "medianMs=${clockAdjustedSummary.medianMs} " +
                        "p95Ms=${clockAdjustedSummary.p95Ms} " +
                        "maxMs=${clockAdjustedSummary.maxMs} " +
                        "avgMs=${"%.1f".format(Locale.US, clockAdjustedSummary.averageMs)} " +
                        "clamped=${clockAdjustedSummary.clampedSamples} " +
                        "discarded=${clockAdjustedSummary.discardedSamples}" +
                        (clockSyncSummary?.let {
                            " syncSamples=${it.sampleCount} " +
                                "syncBestRttMs=${it.bestRttMs} " +
                                "syncMedianRttMs=${it.medianRttMs} " +
                                "syncP95RttMs=${it.p95RttMs} " +
                                "syncBestRemoteOffsetMs=${it.bestRemoteClockOffsetMs} " +
                                "syncMedianRemoteOffsetMs=${it.medianRemoteClockOffsetMs} " +
                                "syncDiscarded=${it.discardedSamples}"
                        } ?: "")
                )
            } else if (clockSyncSummary != null) {
                Log.i(
                    TAG,
                    "Call clock sync summary ${session.sessionCode}/${session.callId} " +
                        "samples=${clockSyncSummary.sampleCount} " +
                        "bestRttMs=${clockSyncSummary.bestRttMs} " +
                        "medianRttMs=${clockSyncSummary.medianRttMs} " +
                        "p95RttMs=${clockSyncSummary.p95RttMs} " +
                        "bestRemoteOffsetMs=${clockSyncSummary.bestRemoteClockOffsetMs} " +
                        "medianRemoteOffsetMs=${clockSyncSummary.medianRemoteClockOffsetMs} " +
                        "discarded=${clockSyncSummary.discardedSamples}"
                )
            } else if (session.netStats.clockSyncDiscardedSamples > 0) {
                Log.i(
                    TAG,
                    "Call clock sync summary ${session.sessionCode}/${session.callId} " +
                        "samples=0 discarded=${session.netStats.clockSyncDiscardedSamples}"
                )
            }
        }
        RfcommTelecomCoordinator.onCallEnded(
            sessionCode = session.sessionCode,
            callId = session.callId,
            cause = telecomDisconnectCause(summary, reason)
        )
        notifyCallEnded(session, summary)
        restoreServiceNotificationIfIdle()
    }

    private fun telecomDisconnectCause(
        summary: CallNotificationSummary,
        reason: String
    ): DisconnectCause {
        val code = when (summary.result) {
            CallResult.MISSED -> DisconnectCause.MISSED
            CallResult.REJECTED -> DisconnectCause.REJECTED
            CallResult.CANCELED -> {
                if (reason.equals("remote", ignoreCase = true)) {
                    DisconnectCause.REMOTE
                } else {
                    DisconnectCause.LOCAL
                }
            }
            CallResult.ANSWERED -> {
                if (reason.equals("remote", ignoreCase = true)) {
                    DisconnectCause.REMOTE
                } else {
                    DisconnectCause.LOCAL
                }
            }
        }
        return DisconnectCause(code)
    }

    private fun opusSampleRate(sampleRate: Int): Int? = callProtocolDelegate.opusSampleRate(sampleRate)

    private fun opusChannels(channels: Int): Int? = callProtocolDelegate.opusChannels(channels)

    private fun opusFrameSize(samples: Int): Int? = callProtocolDelegate.opusFrameSize(samples)

    private fun buildOffer(
        callId: String,
        cfg: AudioCfg
    ): JSONObject = callProtocolDelegate.buildOffer(callId, cfg)

    private fun buildRing(callId: String): JSONObject = callProtocolDelegate.buildRing(callId)

    private fun buildAccept(callId: String): JSONObject = callProtocolDelegate.buildAccept(callId)

    private fun buildReject(callId: String, reason: String): JSONObject =
        callProtocolDelegate.buildReject(callId, reason)

    private fun buildEnd(callId: String, reason: String): JSONObject =
        callProtocolDelegate.buildEnd(callId, reason)

    private fun sendCallCfg(thread: ConnectedThread, callId: String, cfg: AudioCfg): Boolean {
        return callProtocolDelegate.sendCallCfg(callId, cfg) { json ->
            sendJsonLine(thread, json)
        }
    }

    private fun sendCallCfgAck(thread: ConnectedThread, callId: String, ok: Boolean): Boolean {
        return callProtocolDelegate.sendCallCfgAck(callId, ok) { json ->
            sendJsonLine(thread, json)
        }
    }

    private fun sendCallClockSyncRequest(
        thread: ConnectedThread,
        callId: String,
        probeId: String,
        monotonicSendMs: Long
    ): Boolean {
        return callProtocolDelegate.sendCallClockSyncRequest(callId, probeId, monotonicSendMs) { json ->
            sendJsonLine(thread, json)
        }
    }

    private fun sendCallClockSyncResponse(
        thread: ConnectedThread,
        callId: String,
        probeId: String,
        requesterMonotonicSendMs: Long,
        responderMonotonicReceiveMs: Long,
        responderMonotonicSendMs: Long
    ): Boolean {
        return callProtocolDelegate.sendCallClockSyncResponse(
            callId = callId,
            probeId = probeId,
            requesterMonotonicSendMs = requesterMonotonicSendMs,
            responderMonotonicReceiveMs = responderMonotonicReceiveMs,
            responderMonotonicSendMs = responderMonotonicSendMs
        ) { json ->
            sendJsonLine(thread, json)
        }
    }

    private fun ensureCallNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        LEGACY_CALL_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        val existing = manager.getNotificationChannel(CALL_CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CALL_CHANNEL_ID,
            getString(R.string.chat_call_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.chat_call_channel_description)
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // Incoming call audio is handled by the app-level looping ringtone so the notification
            // stays visually urgent without contributing a second overlapping sound source.
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureCallEventNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val existing = manager.getNotificationChannel(CALL_EVENT_CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CALL_EVENT_CHANNEL_ID,
            getString(R.string.chat_call_event_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.chat_call_event_channel_description)
            enableVibration(false)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun ensureOngoingCallNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        LEGACY_ONGOING_CALL_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        val existing = manager.getNotificationChannel(CALL_ONGOING_CHANNEL_ID)
        if (existing != null) {
            return
        }
        val channel = NotificationChannel(
            CALL_ONGOING_CHANNEL_ID,
            getString(R.string.chat_call_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.chat_call_channel_description)
            setSound(null, null)
            enableVibration(false)
            vibrationPattern = longArrayOf(0L)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasBluetoothScanPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isClassicBluetoothOn(): Boolean {
        val bluetoothAdapter = adapter ?: return false
        return runCatching { bluetoothAdapter.state == BluetoothAdapter.STATE_ON }
            .getOrDefault(false)
    }

    private fun currentBluetoothStateLabel(): String {
        val state = runCatching { adapter?.state }.getOrNull()
        return when (state) {
            null -> "UNKNOWN"
            BluetoothAdapter.STATE_OFF -> "OFF"
            BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
            BluetoothAdapter.STATE_ON -> "ON"
            BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
            else -> "STATE_$state"
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun currentBluetoothSessionName(): String? {
        if (!hasBluetoothPermission()) {
            return null
        }
        return runCatching {
            BluetoothAdapter.getDefaultAdapter()?.name?.takeIf { !it.isNullOrBlank() }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun notifyNotificationManager(id: Int, notification: Notification) {
        if (!hasPostNotificationsPermission()) {
            return
        }
        runCatching {
            notificationManager.notify(id, notification)
        }.onFailure { throwable ->
            if (throwable is SecurityException) {
                Log.w(TAG, "Notification permission denied for id=$id", throwable)
            }
        }
    }

    private fun getDeviceName(device: BluetoothDevice): String? = try {
        device.name
    } catch (_: SecurityException) {
        null
    }

    private data class ConnectTarget(
        val device: BluetoothDevice,
        val address: String,
        val reason: String
    )

    @SuppressLint("MissingPermission")
    private fun resolveConnectTargetDevice(
        adapter: BluetoothAdapter,
        sessionCode: String,
        preferredAddress: String?,
        storedAddress: String,
        fallbackAddress: String?,
        contactName: String?
    ): ConnectTarget? {
        val normalizedPreferred = normalizeMacAddress(preferredAddress)
        val normalizedStored = normalizeMacAddress(storedAddress)
        val normalizedFallback = normalizeMacAddress(fallbackAddress)
        val normalizedSessionAsAddress = normalizeMacAddress(sessionCode)
        val normalizedLocalAddress = runCatching { normalizeMacAddress(adapter.address) }.getOrDefault("")
        val bondedDevices = runCatching { adapter.bondedDevices?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filterNotNull()

        fun deviceAddress(device: BluetoothDevice): String {
            return normalizeMacAddress(runCatching { device.address }.getOrDefault(""))
        }

        fun deviceName(device: BluetoothDevice): String {
            val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { device.alias }.getOrNull()
            } else {
                null
            }
            return alias?.trim().orEmpty()
                .ifBlank { runCatching { device.name }.getOrNull()?.trim().orEmpty() }
        }

        fun firstBondedByAddress(targetAddress: String): BluetoothDevice? {
            if (targetAddress.isBlank()) {
                return null
            }
            return bondedDevices.firstOrNull { candidate ->
                deviceAddress(candidate).equals(targetAddress, ignoreCase = true)
            }
        }

        fun firstBondedByName(targetName: String): BluetoothDevice? {
            if (targetName.isBlank()) {
                return null
            }
            return bondedDevices.firstOrNull { candidate ->
                val candidateName = deviceName(candidate)
                candidateName.isNotBlank() && candidateName.equals(targetName, ignoreCase = true)
            }
        }

        fun firstBondedNameContains(token: String): BluetoothDevice? {
            if (token.isBlank()) {
                return null
            }
            return bondedDevices.firstOrNull { candidate ->
                val candidateName = deviceName(candidate)
                candidateName.isNotBlank() && candidateName.contains(token, ignoreCase = true)
            }
        }

        val trimmedSession = sessionCode.trim()
        val trimmedContactName = contactName?.trim().orEmpty()
        val candidates = LinkedHashMap<String, ConnectTarget>()

        fun addCandidate(device: BluetoothDevice?, reason: String) {
            if (device == null) {
                return
            }
            val address = deviceAddress(device)
            if (address.isBlank()) {
                return
            }
            candidates.putIfAbsent(address, ConnectTarget(device = device, address = address, reason = reason))
        }

        addCandidate(firstBondedByAddress(normalizedPreferred), "preferred bonded address")
        if (normalizedPreferred.isNotBlank()) {
            val preferredDevice = runCatching { adapter.getRemoteDevice(normalizedPreferred) }.getOrNull()
            addCandidate(preferredDevice, "preferred contact address")
        }
        addCandidate(firstBondedByAddress(normalizedStored), "stored bonded address")
        addCandidate(firstBondedByAddress(normalizedFallback), "stored BLE fallback bonded address")
        addCandidate(firstBondedByAddress(normalizedSessionAsAddress), "session code matches bonded address")
        addCandidate(firstBondedByName(trimmedSession), "session code matches bonded device name")
        addCandidate(firstBondedByName(trimmedContactName), "contact name matches bonded device name")
        addCandidate(firstBondedNameContains(trimmedSession), "session code contained in bonded device name")
        addCandidate(firstBondedNameContains(trimmedContactName), "contact name contained in bonded device name")

        if (normalizedStored.isNotBlank()) {
            val remoteDevice = runCatching { adapter.getRemoteDevice(normalizedStored) }.getOrNull()
            if (remoteDevice != null) {
                addCandidate(remoteDevice, "stored contact address")
            }
        }
        if (normalizedFallback.isNotBlank()) {
            val remoteDevice = runCatching { adapter.getRemoteDevice(normalizedFallback) }.getOrNull()
            if (remoteDevice != null) {
                addCandidate(remoteDevice, "stored BLE fallback address")
            }
        }

        val nonLocal = candidates.values.firstOrNull { target ->
            !target.address.equals(normalizedLocalAddress, ignoreCase = true)
        }
        return nonLocal ?: candidates.values.firstOrNull()
    }

    private fun buildNotification(): Notification {
        createChannel()
        val localizedContext = NotificationLocalization.localizedContext(this)
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localizedContext.getString(R.string.chat_service_notification_title))
            .setContentText(localizedContext.getString(R.string.chat_service_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val localizedContext = NotificationLocalization.localizedContext(this)
            // Nearby discovery used to run in its own foreground service with this channel; it is
            // hosted here now, so drop the stale channel from upgraded installs.
            NotificationManagerCompat.from(this)
                .deleteNotificationChannel(LEGACY_NEARBY_DISCOVERY_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                localizedContext.getString(R.string.rfcomm_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = localizedContext.getString(R.string.rfcomm_service_channel_description)
            }
            NotificationManagerCompat.from(this).createNotificationChannel(channel)
        }
    }

    private fun maybeNotifyIncomingMessage(
        sessionCode: String,
        messageType: MessageType,
        body: String?
    ) {
        messageNotificationDelegate.maybeNotifyIncomingMessage(sessionCode, messageType, body)
    }

    private fun handleDirectReplyIntent(intent: Intent) {
        messageNotificationDelegate.handleDirectReplyIntent(intent)
    }

    private fun closeSocketSilently(socket: BluetoothSocket?) {
        if (socket == null) return
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun resolveRfcommChunkSize(socket: BluetoothSocket): Int {
        val reported = runCatching {
            val method = socket.javaClass.getMethod("getMaxTransmitPacketSize")
            (method.invoke(socket) as? Int) ?: 0
        }.getOrDefault(0)
        return reported
            .takeIf { it in RFCOMM_MIN_TX_CHUNK_BYTES..RFCOMM_MAX_TX_CHUNK_BYTES }
            ?: RFCOMM_DEFAULT_TX_CHUNK_BYTES
    }

    private fun closeServerSocketSilently(socket: BluetoothServerSocket?) {
        if (socket == null) return
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private inner class AcceptThread(val mode: AcceptMode) : Thread("AcceptThread-${mode.name}") {
        @Volatile
        private var serverSocket: BluetoothServerSocket? = null

        @SuppressLint("MissingPermission")
        override fun run() {
            val adapter = this@RfcommForegroundService.adapter
            if (adapter == null) {
                Log.w(TAG, "AcceptThread[${mode.label}]: Bluetooth adapter unavailable, stopping listener")
                clearAcceptThreadReference(this)
                return
            }
            if (!hasBluetoothPermission()) {
                Log.w(TAG, "AcceptThread[${mode.label}]: Missing BLUETOOTH_CONNECT permission")
                clearAcceptThreadReference(this)
                return
            }
            val localServer = try {
                adapter.listenUsingRfcommWithServiceRecord(
                    SERVICE_NAME_SECURE, SERVICE_UUID_SECURE
                )
            } catch (securityException: SecurityException) {
                Log.w(
                    TAG,
                    "AcceptThread[${mode.label}]: Server socket open denied; will retry",
                    securityException
                )
                scheduleListeningRetry("server socket denied", ACTIVE_USER_RETRY_DELAY_MS)
                null
            } catch (ioException: IOException) {
                Log.e(TAG, "AcceptThread[${mode.label}]: Failed to open server socket", ioException)
                null
            }

            if (localServer == null) {
                clearAcceptThreadReference(this)
                return
            }
            serverSocket = localServer
            Log.i(TAG, "AcceptThread[${mode.label}]: Listening socket opened")

            try {
                while (!isInterrupted) {
                    Log.i(TAG, "AcceptThread[${mode.label}]: Server is waiting for a new connection...")
                    val socket = try {
                        localServer.accept()
                    } catch (ioException: IOException) {
                        Log.d(TAG, "AcceptThread[${mode.label}]: accept() failed or socket closed", ioException)
                        break
                    } catch (securityException: SecurityException) {
                        // "Not allowed for non-active user": thrown when the device switches to another
                        // Android user mid-accept. Break and let the listener machinery reschedule with
                        // backoff (the listen() path already throttles via ACTIVE_USER_RETRY_DELAY_MS).
                        Log.w(TAG, "AcceptThread[${mode.label}]: accept() denied (non-active user?)", securityException)
                        break
                    }

                    if (socket != null) {
                        Log.i(TAG, "AcceptThread[${mode.label}]: Connection accepted from ${socket.remoteDevice?.address}")
                        serviceScope.launch {
                            val sessionCode = resolveSessionCode(socket.remoteDevice)
                            if (sessionCode != null) {
                                manageConnectedSocket(socket, sessionCode)
                            } else {
                                Log.w(TAG, "AcceptThread[${mode.label}]: Unable to resolve session for ${socket.remoteDevice?.address}, closing socket")
                                closeSocketSilently(socket)
                            }
                        }
                    }
                }
            } finally {
                closeServerSocketSilently(serverSocket)
                serverSocket = null
                clearAcceptThreadReference(this)
                if (!isInterrupted) {
                    handler.post { ensureListening("accept thread terminated") }
                }
            }
        }

        fun cancel() {
            closeServerSocketSilently(serverSocket)
            serverSocket = null
            interrupt()
        }
    }

    private inner class ConnectThread(
        private val device: BluetoothDevice,
        private val sessionCode: String,
        private val callback: (Boolean) -> Unit
    ) : Thread("ConnectThread-$sessionCode") {

        private var socket: BluetoothSocket? = null

        override fun run() {
            try {
                maybeRefreshUuidCacheIfNeeded()
                val connectedSocket =
                    // 1) Custom secure
                    connectWithUuid("SECURE_CUSTOM", SERVICE_UUID_SECURE)
                        // 2) Default SPP secure
                        ?: connectWithUuid("SECURE_SPP", SPP_UUID)
                        // 3) Reflection channel 1 secure (OEM quirks)
                        ?: connectWithReflection(
                            "REFLECTION_CH1_SECURE",
                            channel = RFCOMM_REFLECTION_CHANNEL
                        )

                if (connectedSocket != null) {
                    manageConnectedSocket(connectedSocket, sessionCode)
                    handler.post { callback(true) }
                    return
                }

                logPairingRecoveryHintIfNeeded()
                connectionFailed(sessionCode)
                handler.post { callback(false) }
            } catch (securityException: SecurityException) {
                Log.e(TAG, "ConnectThread: Missing permission while connecting", securityException)
                closeSocketSilently(socket)
                connectionFailed(sessionCode)
                handler.post { callback(false) }
            } finally {
                connectThreads.remove(sessionCode, this)
            }
        }

        fun cancel() {
            closeSocketSilently(socket)
            interrupt()
        }

        @SuppressLint("MissingPermission")
        private fun connectWithUuid(
            label: String,
            uuid: java.util.UUID
        ): BluetoothSocket? {
            if (isInterrupted) {
                return null
            }
            if (!isClassicBluetoothOn()) {
                Log.w(
                    TAG,
                    "ConnectThread[$label]: skipping connect because Bluetooth classic is not ON (state=${currentBluetoothStateLabel()})"
                )
                return null
            }
            return try {
                val candidate = device.createRfcommSocketToServiceRecord(uuid)

                socket = candidate
                cancelDiscoveryBeforeConnect(label)
                Log.d(
                    TAG,
                    "ConnectThread[$label]: Attempting secure connect to ${device.address} (uuid=$uuid)"
                )
                connectSocketWithTimeout(candidate, label)
                Log.i(TAG, "ConnectThread[$label]: Connected to ${device.address}")
                // successful connect — handoff to ConnectedThread will happen
                socket = null
                candidate
            } catch (ioException: IOException) {
                Log.w(TAG, "ConnectThread[$label]: Connection failed", ioException)
                closeSocketSilently(socket)
                socket = null
                null
            }
        }

        @SuppressLint("MissingPermission")
        private fun maybeRefreshUuidCacheIfNeeded() {
            if (isInterrupted || !hasBluetoothPermission()) {
                return
            }
            val cachedUuids = runCatching {
                device.uuids
                    ?.mapNotNull { parcel ->
                        parcel.uuid.toString().lowercase(Locale.US)
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())

            val unresolvedCache = cachedUuids.isEmpty() || cachedUuids.all { it == RFCOMM_ZERO_UUID }
            if (!unresolvedCache) {
                return
            }

            val requested = runCatching { device.fetchUuidsWithSdp() }.getOrDefault(false)
            Log.w(
                TAG,
                "ConnectThread[$sessionCode]: remote UUID cache ${if (cachedUuids.isEmpty()) "empty" else "all-zero"}; fetchUuidsWithSdp requested=$requested"
            )
            if (requested) {
                try {
                    sleep(RFCOMM_SDP_REFRESH_WAIT_MS)
                } catch (_: InterruptedException) {
                    interrupt()
                }
            }
        }

        @SuppressLint("MissingPermission")
        private fun logPairingRecoveryHintIfNeeded() {
            if (!hasBluetoothPermission()) {
                return
            }
            val cachedUuids = runCatching {
                device.uuids
                    ?.mapNotNull { parcel ->
                        parcel.uuid.toString().lowercase(Locale.US)
                    }
                    .orEmpty()
            }.getOrDefault(emptyList())
            val unresolvedCache = cachedUuids.isEmpty() || cachedUuids.all { it == RFCOMM_ZERO_UUID }
            if (!unresolvedCache) {
                return
            }
            Log.e(
                TAG,
                "ConnectThread[$sessionCode]: RFCOMM attempts exhausted with unresolved UUID cache. Recommend unpair/pair on both devices."
            )
        }

        private fun cancelDiscoveryBeforeConnect(label: String) {
            val bluetoothAdapter = this@RfcommForegroundService.adapter ?: return
            if (!hasBluetoothPermission() || !hasBluetoothScanPermission()) {
                Log.w(
                    TAG,
                    "ConnectThread[$label]: missing Bluetooth permission for discovery control"
                )
                return
            }
            try {
                bluetoothAdapter.cancelDiscovery()
            } catch (securityException: SecurityException) {
                Log.w(TAG, "ConnectThread[$label]: cancelDiscovery denied", securityException)
                return
            }
            var waitSteps = 0
            while (!isInterrupted && waitSteps < RFCOMM_DISCOVERY_STOP_MAX_WAIT_STEPS) {
                val discovering = try {
                    bluetoothAdapter.isDiscovering
                } catch (securityException: SecurityException) {
                    Log.w(TAG, "ConnectThread[$label]: isDiscovering denied", securityException)
                    false
                }
                if (!discovering) {
                    break
                }
                try {
                    sleep(RFCOMM_DISCOVERY_STOP_WAIT_STEP_MS)
                } catch (_: InterruptedException) {
                    interrupt()
                    return
                }
                waitSteps += 1
            }
            if (waitSteps > 0) {
                Log.d(
                    TAG,
                    "ConnectThread[$label]: waited ${waitSteps * RFCOMM_DISCOVERY_STOP_WAIT_STEP_MS}ms for discovery to stop"
                )
            }
        }

        @Throws(IOException::class)
        private fun connectSocketWithTimeout(targetSocket: BluetoothSocket, label: String) {
            if (!hasBluetoothPermission()) {
                throw IOException("Missing BLUETOOTH_CONNECT permission ($label)")
            }
            val future = connectExecutor.submit {
                try {
                    targetSocket.connect()
                } catch (securityException: SecurityException) {
                    throw IOException(
                        "Missing BLUETOOTH_CONNECT permission during RFCOMM connect ($label)",
                        securityException
                    )
                }
            }
            try {
                future.get(RFCOMM_CONNECT_ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (timeoutException: TimeoutException) {
                Log.w(
                    TAG,
                    "ConnectThread[$label]: Connect attempt timed out after ${RFCOMM_CONNECT_ATTEMPT_TIMEOUT_MS}ms, closing socket"
                )
                closeSocketSilently(targetSocket)
                future.cancel(true)
                throw IOException("RFCOMM connect timeout ($label)", timeoutException)
            } catch (executionException: ExecutionException) {
                val cause = executionException.cause
                if (cause is IOException) throw cause
                throw IOException("RFCOMM connect failed ($label)", cause ?: executionException)
            } catch (interruptedException: InterruptedException) {
                Thread.currentThread().interrupt()
                closeSocketSilently(targetSocket)
                throw IOException("RFCOMM connect interrupted ($label)", interruptedException)
            }
        }

        @SuppressLint("MissingPermission")
        private fun connectWithReflection(
            label: String,
            channel: Int
        ): BluetoothSocket? {
            if (isInterrupted) {
                return null
            }
            if (!isClassicBluetoothOn()) {
                Log.w(
                    TAG,
                    "ConnectThread[$label]: skipping reflection connect because Bluetooth classic is not ON (state=${currentBluetoothStateLabel()})"
                )
                return null
            }
            fun tryMethod(name: String): BluetoothSocket? {
                return try {
                    if (isInterrupted) {
                        return null
                    }
                    if (!isClassicBluetoothOn()) {
                        Log.w(
                            TAG,
                            "ConnectThread[$label]: Bluetooth classic turned off before reflection $name (state=${currentBluetoothStateLabel()})"
                        )
                        return null
                    }
                    val m = device.javaClass.getMethod(name, Int::class.javaPrimitiveType)
                    val s = m.invoke(device, channel) as BluetoothSocket
                    socket = s
                    cancelDiscoveryBeforeConnect(label)
                    Log.d(TAG, "ConnectThread[$label]: Attempting reflection $name on channel $channel")
                    connectSocketWithTimeout(s, label)
                    Log.i(TAG, "ConnectThread[$label]: Reflection connect OK on channel $channel via $name")
                    // successful connect — handoff to ConnectedThread will happen
                    socket = null
                    s
                } catch (t: Throwable) {
                    Log.w(TAG, "ConnectThread[$label]: Reflection $name failed", t)
                    closeSocketSilently(socket)
                    socket = null
                    null
                }
            }

            return tryMethod("createRfcommSocket")
        }
    }

    internal inner class ConnectedThread(
        private val sessionCode: String,
        private val socket: BluetoothSocket
    ) : Thread("ConnectedThread-$sessionCode") {

        private val inputStream: InputStream? = try {
            socket.inputStream
        } catch (_: IOException) {
            null
        }
        private val outputStream: OutputStream? = try {
            socket.outputStream
        } catch (_: IOException) {
            null
        }
        private val cancelled = AtomicBoolean(false)
        private val lastActivityMs = AtomicReference(SystemClock.elapsedRealtime())
        private var heartbeatJob: Job? = null
        private val outboundQueue = ArrayBlockingQueue<OutboundPacket>(RFCOMM_WRITE_QUEUE_CAPACITY)
        private val queuedBytes = AtomicInteger(0)
        private val queueBytesLock = java.util.concurrent.locks.ReentrantLock()
        private val queueBytesAvailable = queueBytesLock.newCondition()
        private val writerRunning = AtomicBoolean(false)
        private val writerThread = AtomicReference<Thread?>()
        private val txChunkSize = resolveRfcommChunkSize(socket)

        override fun run() {
            val reader = inputStream ?: run {
                connectionLost(sessionCode, this)
                cancel()
                return
            }

            if (cancelled.get()) {
                return
            }
            startWriterLoop()

            heartbeatJob = serviceScope.launch(Dispatchers.IO) {
                while (isActive && !cancelled.get()) {
                    val activeCall = callSessions[sessionCode]
                    val inCall = activeCall?.state?.value == CallState.InCall
                    val idleMs = SystemClock.elapsedRealtime() - lastActivityMs.get()
                    val intervalMs = when {
                        inCall -> HEARTBEAT_ACTIVE_CALL_MS
                        activeCall != null -> HEARTBEAT_ACTIVE_MS
                        idleMs < HEARTBEAT_IDLE_THRESHOLD_MS -> HEARTBEAT_ACTIVE_MS
                        idleMs < HEARTBEAT_DEEP_IDLE_THRESHOLD_MS -> HEARTBEAT_IDLE_MS
                        else -> HEARTBEAT_DEEP_IDLE_MS
                    }
                    delay(intervalMs)
                    if (!inCall) {
                        write(HEARTBEAT_BYTES)
                    }
                }
            }
            val buffer = ByteArray(RFCOMM_READ_BUFFER_BYTES)
            val builder = StringBuilder()
            try {
                while (!cancelled.get()) {
                    val bytes = reader.read(buffer)
                    if (bytes == -1) {
                        break
                    }
                    if (bytes > 0) {
                        lastActivityMs.set(SystemClock.elapsedRealtime())
                        builder.append(String(buffer, 0, bytes, Charsets.UTF_8))
                        if (builder.indexOf("\n") == -1 && builder.length > RFCOMM_MAX_LINE_LENGTH) {
                            Log.w(TAG, "ConnectedThread[$sessionCode]: line exceeds ${RFCOMM_MAX_LINE_LENGTH} bytes without newline, discarding")
                            builder.setLength(0)
                        }
                        var newline = builder.indexOf("\n")
                        while (newline != -1) {
                            val message = builder.substring(0, newline).trim()
                            if (message.isNotEmpty()) {
                                handleIncomingPayload(sessionCode, message)
                            }
                            builder.delete(0, newline + 1)
                            newline = builder.indexOf("\n")
                        }
                    }
                }
            } catch (ioException: IOException) {
                android.util.Log.e(TAG, "ConnectedThread: Connection lost", ioException)
            } finally {
                heartbeatJob?.cancel()
                heartbeatJob = null
                stopWriterLoop()
                connectionLost(sessionCode, this)
                cancel()
            }
        }

        fun write(bytes: ByteArray): Boolean {
            lastActivityMs.set(SystemClock.elapsedRealtime())
            return enqueuePacket(bytes, OutboundTrafficClass.Default) == WriteResult.Sent
        }

        internal fun writeRealtime(bytes: ByteArray): WriteResult {
            return enqueuePacket(bytes, OutboundTrafficClass.RealtimeAudio)
        }

        private fun enqueuePacket(
            bytes: ByteArray,
            trafficClass: OutboundTrafficClass
        ): WriteResult {
            if (bytes.isEmpty()) {
                return WriteResult.Sent
            }
            if (cancelled.get()) {
                return WriteResult.Failed
            }
            if (outputStream == null) {
                return WriteResult.Failed
            }
            val copy = bytes.copyOf()
            val maxQueuedBytes = when (trafficClass) {
                OutboundTrafficClass.Default -> RFCOMM_WRITE_QUEUE_MAX_BYTES
                OutboundTrafficClass.RealtimeAudio -> RFCOMM_REALTIME_QUEUE_MAX_BYTES
            }
            val timeoutMs = when (trafficClass) {
                OutboundTrafficClass.Default -> RFCOMM_WRITE_BACKPRESSURE_TIMEOUT_MS
                OutboundTrafficClass.RealtimeAudio -> RFCOMM_REALTIME_BACKPRESSURE_TIMEOUT_MS
            }
            val reserved = reserveQueuedBytes(
                byteCount = copy.size,
                maxQueuedBytes = maxQueuedBytes,
                timeoutMs = timeoutMs
            )
            if (!reserved) {
                if (trafficClass == OutboundTrafficClass.Default) {
                    Log.w(
                        TAG,
                        "ConnectedThread: Backpressure timeout for $sessionCode " +
                            "(packet=${copy.size} max=$maxQueuedBytes)"
                    )
                    return WriteResult.Failed
                }
                return WriteResult.Dropped
            }
            val packet = OutboundPacket(copy, trafficClass)
            return try {
                val enqueued = outboundQueue.offer(packet, timeoutMs, TimeUnit.MILLISECONDS)
                if (!enqueued) {
                    queuedBytes.addAndGet(-copy.size)
                }
                when {
                    enqueued -> WriteResult.Sent
                    trafficClass == OutboundTrafficClass.RealtimeAudio -> WriteResult.Dropped
                    else -> WriteResult.Failed
                }
            } catch (interruptedException: InterruptedException) {
                queuedBytes.addAndGet(-copy.size)
                Thread.currentThread().interrupt()
                WriteResult.Failed
            }
        }

        private fun reserveQueuedBytes(
            byteCount: Int,
            maxQueuedBytes: Int,
            timeoutMs: Long
        ): Boolean {
            if (byteCount > maxQueuedBytes) {
                return false
            }
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            queueBytesLock.lock()
            try {
                while (!cancelled.get()) {
                    val currentQueued = queuedBytes.get()
                    if (currentQueued + byteCount <= maxQueuedBytes) {
                        if (queuedBytes.compareAndSet(currentQueued, currentQueued + byteCount)) {
                            return true
                        }
                        continue
                    }
                    if (remainingNanos <= 0L) {
                        return false
                    }
                    remainingNanos = queueBytesAvailable.awaitNanos(remainingNanos)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                queueBytesLock.unlock()
            }
            return false
        }

        fun cancel() {
            if (cancelled.getAndSet(true)) {
                return
            }
            heartbeatJob?.cancel()
            heartbeatJob = null
            stopWriterLoop()
            closeSocketSilently(socket)
            interrupt()
        }

        private fun startWriterLoop() {
            if (writerRunning.getAndSet(true)) {
                return
            }
            val writer = outputStream
            if (writer == null) {
                writerRunning.set(false)
                return
            }
            val connectedThreadRef = this
            val thread = Thread({
                try {
                    while (!cancelled.get() && writerRunning.get()) {
                        val packet = outboundQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        queuedBytes.addAndGet(-packet.bytes.size)
                        runCatching {
                            queueBytesLock.lock()
                            try { queueBytesAvailable.signalAll() } finally { queueBytesLock.unlock() }
                        }
                        if (
                            packet.trafficClass == OutboundTrafficClass.RealtimeAudio &&
                            SystemClock.elapsedRealtime() - packet.enqueuedAtMs > RFCOMM_REALTIME_PACKET_EXPIRY_MS
                        ) {
                            continue
                        }
                        var offset = 0
                        synchronized(writer) {
                            while (offset < packet.bytes.size) {
                                val chunkSize = minOf(txChunkSize, packet.bytes.size - offset)
                                writer.write(packet.bytes, offset, chunkSize)
                                offset += chunkSize
                            }
                            writer.flush()
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (ioException: IOException) {
                    android.util.Log.e(TAG, "ConnectedThread: Writer loop failed", ioException)
                    connectionLost(sessionCode, connectedThreadRef)
                    connectedThreadRef.cancel()
                } finally {
                    writerRunning.set(false)
                }
            }, "ConnectedWriter-$sessionCode").apply {
                isDaemon = true
                start()
            }
            writerThread.set(thread)
        }

        private fun stopWriterLoop() {
            if (!writerRunning.getAndSet(false)) {
                return
            }
            writerThread.getAndSet(null)?.interrupt()
            outboundQueue.clear()
            queuedBytes.set(0)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RfcommForegroundService = this@RfcommForegroundService
    }

    private fun handleIncomingPayload(sessionCode: String, payload: String) {
        incomingPayloadDelegate.handleIncomingPayload(sessionCode, payload)
    }

    private fun saveLegacyMessage(sessionCode: String, text: String) {
        incomingPayloadDelegate.saveLegacyMessage(sessionCode, text)
    }

    private fun handleVoiceFrame(sessionCode: String, json: JSONObject): Boolean {
        return incomingPayloadDelegate.handleVoiceFrame(sessionCode, json)
    }

    private fun handleFileFrame(sessionCode: String, json: JSONObject): Boolean {
        return incomingPayloadDelegate.handleFileFrame(sessionCode, json)
    }

    private fun handleCallFrame(sessionCode: String, json: JSONObject): Boolean {
        return callFrameDelegate.handleCallFrame(sessionCode, json)
    }

    private fun handleImageFrame(sessionCode: String, json: JSONObject): Boolean {
        return incomingPayloadDelegate.handleImageFrame(sessionCode, json)
    }

    companion object {
        private const val TAG = "RfcommService"
        private const val CHANNEL_ID = "dcs_rfcomm_channel"
        private const val NOTIFICATION_ID = 14001
        // Keep versioned to avoid stale user/device channel settings from older builds.
        private const val CALL_CHANNEL_ID = "dcs_rfcomm_calls_v4"
        private const val CALL_EVENT_CHANNEL_ID = "dcs_rfcomm_call_events"
        private const val CALL_ONGOING_CHANNEL_ID = "dcs_rfcomm_ongoing_calls_v2"
        private const val CALL_NOTIFICATION_BASE_ID = 15000
        private val LEGACY_CALL_CHANNEL_IDS = listOf(
            "dcs_rfcomm_calls",
            "dcs_rfcomm_calls_v2",
            "dcs_rfcomm_calls_v3"
        )
        private val LEGACY_ONGOING_CALL_CHANNEL_IDS = listOf("dcs_rfcomm_ongoing_calls")
        // Channel of the retired standalone NearbyDiscoveryService, deleted on channel setup.
        private const val LEGACY_NEARBY_DISCOVERY_CHANNEL_ID = "nearby_discovery"
        private const val OFFLINE_MAP_SHARE_MIME =
            "application/vnd.auralis.offline-map-share+zip"
        private const val OUTBOUND_ROUTE_BLE_GATT_FALLBACK = "ble_gatt_fallback"

        const val ACTION_ACCEPT_CALL = "com.auralis.crisisconnect.action.ACCEPT_CALL"
        const val ACTION_REJECT_CALL = "com.auralis.crisisconnect.action.REJECT_CALL"
        const val ACTION_END_CALL = "com.auralis.crisisconnect.action.END_CALL"
        const val ACTION_MUTE = "com.auralis.crisisconnect.action.MUTE"
        const val ACTION_UNMUTE = "com.auralis.crisisconnect.action.UNMUTE"
        const val ACTION_SPEAKER = "com.auralis.crisisconnect.action.SPEAKER"
        const val ACTION_SYNC_CALL_AUDIO_ROUTE = "com.auralis.crisisconnect.action.SYNC_CALL_AUDIO_ROUTE"
        const val ACTION_HANGUP = "com.auralis.crisisconnect.action.HANGUP"
        const val ACTION_REPLY_MESSAGE = "com.auralis.crisisconnect.action.REPLY_MESSAGE"
        const val EXTRA_SESSION_CODE = "extra_session_code"
        const val EXTRA_CALL_ID = "extra_call_id"
        const val EXTRA_CALL_FLAG = "extra_call_flag"
        const val EXTRA_CALL_MUTED = "extra_call_muted"
        const val EXTRA_CALL_SPEAKER = "extra_call_speaker"
        const val EXTRA_CALL_AUDIO_ROUTE = "extra_call_audio_route"
        const val EXTRA_CALL_AVAILABLE_AUDIO_ROUTES = "extra_call_available_audio_routes"
        const val ACTION_CALL_STATE_CHANGED = "com.auralis.crisisconnect.action.CALL_STATE_CHANGED"
        const val CALL_ACCEPTED_FLAG = "CALL_ACCEPTED"
        const val CALL_ANSWERED_BY_TELECOM_FLAG = "CALL_ANSWERED_BY_TELECOM"
        const val CALL_REJECTED_FLAG = "CALL_REJECTED"
        const val CALL_ENDED_FLAG = "CALL_ENDED"

        private const val SERVICE_NAME_SECURE = "DCS Chat (Secure)"

        // Custom UUIDs (BluetoothChat-style)
        val SERVICE_UUID_SECURE: java.util.UUID =
            java.util.UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66")

        // Default SPP UUID (generic modules / broad compatibility)
        val SPP_UUID: java.util.UUID =
            java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val PERMISSION_RETRY_DELAY_MS = 1_000L
        private const val ACTIVE_USER_RETRY_DELAY_MS = 5_000L
        private const val CALL_SETUP_TIMEOUT_MS = 30_000L
        private const val RFCOMM_MIN_TX_CHUNK_BYTES = 128
        private const val RFCOMM_DEFAULT_TX_CHUNK_BYTES = 672
        private const val RFCOMM_MAX_TX_CHUNK_BYTES = 1_024
        private const val RFCOMM_WRITE_QUEUE_CAPACITY = 96
        private const val RFCOMM_WRITE_QUEUE_MAX_BYTES = 2 * 1024 * 1024
        private const val RFCOMM_WRITE_BACKPRESSURE_TIMEOUT_MS = 10_000L
        private const val RFCOMM_REALTIME_QUEUE_MAX_BYTES = 24 * 1024
        private const val RFCOMM_REALTIME_BACKPRESSURE_TIMEOUT_MS = 20L
        private const val RFCOMM_REALTIME_PACKET_EXPIRY_MS = 200L
        private const val CALL_CLOCK_SYNC_INTERVAL_MS = 5_000L
        private const val CALL_CLOCK_SYNC_EXPIRY_MS = 15_000L
        private const val RFCOMM_REFLECTION_CHANNEL = 1
        private const val RFCOMM_CONNECT_ATTEMPT_TIMEOUT_MS = 12_000L
        private const val RFCOMM_DISCOVERY_STOP_WAIT_STEP_MS = 100L
        private const val RFCOMM_DISCOVERY_STOP_MAX_WAIT_STEPS = 10
        private const val RFCOMM_SDP_REFRESH_WAIT_MS = 1_000L
        private const val RFCOMM_ZERO_UUID = "00000000-0000-0000-0000-000000000000"
        private const val RFCOMM_READ_BUFFER_BYTES = 4096
        private const val HEARTBEAT_ACTIVE_CALL_MS = 15_000L
        private const val HEARTBEAT_ACTIVE_MS = 20_000L
        private const val HEARTBEAT_IDLE_MS = 45_000L
        private const val HEARTBEAT_DEEP_IDLE_MS = 90_000L
        private const val HEARTBEAT_IDLE_THRESHOLD_MS = 120_000L
        private const val HEARTBEAT_DEEP_IDLE_THRESHOLD_MS = 300_000L
        private const val WAKELOCK_TIMEOUT_MS = 4L * 60 * 60 * 1000 // 4 hours safety ceiling
        private val HEARTBEAT_BYTES = "__hb__\n".toByteArray(Charsets.UTF_8)
        private const val RFCOMM_MAX_LINE_LENGTH = 2 * 1024 * 1024 // 2MB

        private val AcceptMode.label: String
            get() = when (this) {
                AcceptMode.SECURE -> "SECURE"
            }

        private fun decodeCallAudioRoute(name: String): CallAudioRoute? {
            return runCatching { CallAudioRoute.valueOf(name) }.getOrNull()
        }
    }

    private enum class AcceptMode {
        SECURE
    }

    private fun clearAcceptThreadReference(thread: AcceptThread) {
        if (thread.mode == AcceptMode.SECURE && secureAcceptThread === thread) {
            secureAcceptThread = null
        }
    }

    /*
     * Test plan (manual):
     * 1. Verify Device A can initiate a call to Device B without AES keys: observe offer, ring,
     *    accept, bi-directional audio, and clean hang-up from either side.
     * 2. Repeat with AES keys provisioned for both contacts and confirm `encrypted=true` in offer
     *    and IV/cipher data on each `call:audio` frame while maintaining audio quality.
     * 3. Reject an incoming call from Device B and ensure the caller transitions to `Ended` with
     *    `call:reject` reason propagated and resources released on both devices.
     * 4. Simulate Bluetooth link loss mid-call (e.g., disable Bluetooth) and confirm
     *    `call:end` is emitted with reason `error`, audio pipelines stop, and UI reflects the
     *    disconnect gracefully.
     */
}
