package com.auralis.crisisconnect.service.mesh

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.AwarePairingConfig
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Handler
import android.util.Base64
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.MeshChatStore
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.RoleCertificate
import com.auralis.crisisconnect.security.RoleProofPayload
import com.auralis.crisisconnect.security.RoleProofVerificationResult
import com.auralis.crisisconnect.security.RoleProofVerifier
import com.auralis.crisisconnect.security.RoleProofCreator
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.NotificationLocalization
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.net.Socket
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject

@TargetApi(Build.VERSION_CODES.O)
class MeshAwareService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val binder = LocalBinder()
    private val stateFlow = MutableStateFlow(MeshAwareServiceState())
    val state: StateFlow<MeshAwareServiceState> = stateFlow.asStateFlow()

    private val securityRepository by lazy { SecurityRepository(this) }
    private val roleProofCreator by lazy {
        RoleProofCreator(
            context = this,
            securityRepository = securityRepository,
            allowExpiredCertificate = false
        )
    }
    private val roleProofVerifier by lazy { RoleProofVerifier() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val eventCounter = AtomicLong(0)
    private val sendMessageIdCounter = AtomicInteger(1)
    private val messageTracker = mutableMapOf<Int, String>()
    private val peerStateByKey = mutableMapOf<String, PeerState>()
    private val unauthorizedAttemptWindow = mutableMapOf<String, Long>()

    private var manager: WifiAwareManager? = null
    private var wifiManager: WifiManager? = null
    private var connectivityManager: ConnectivityManager? = null
    private var awareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private var selfSessionKeyPair: KeyPair? = null
    private var selfSessionPublicKeyBase64: String? = null
    private var selfSessionPublicKeySignatureBase64: String? = null
    private var selfLabel: String = LEGACY_DEFAULT_NODE_LABEL_PREFIX
    private var selfNodeId: String = ""
    private var desiredMeshEnabled: Boolean = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt: Int = 0
    private val seenMessageIds = LinkedHashSet<String>()
    private var awarePairingSupported: Boolean = false
    private val interopLock = Any()
    private val interopConnectionsByKey = mutableMapOf<String, InteropConnection>()
    private val interopCallbacksByKey = mutableMapOf<String, ConnectivityManager.NetworkCallback>()
    private val interopConnectingKeys = mutableSetOf<String>()

    private val publishCallback = object : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            publishSession = session
            Log.d(LOG_TAG, "Publish session started")
            updateActiveState()
        }

        override fun onSessionTerminated() {
            publishSession = null
            handleDiscoverySessionTerminated()
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            handleIncomingMessage(peerHandle, message)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            messageTracker.remove(messageId)
        }

        override fun onMessageSendFailed(messageId: Int) {
            val key = messageTracker.remove(messageId)
            if (key != null) {
                Log.w(LOG_TAG, "Message send failed to peer $key, msgId=$messageId")
            }
        }

        override fun onSessionConfigFailed() {
            Log.w(LOG_TAG, "Publish session config failed")
            stateFlow.update { it.copy(errorMessage = R.string.rescue_mesh_error_start_failed, isEnabled = false, isBusy = false) }
            stopAwareSession()
            scheduleReconnect("publish_config_failed")
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            handlePeerLost(peerHandle)
        }

        override fun onPairingSetupRequestReceived(peerHandle: PeerHandle, requestId: Int) {
            handlePairingSetupRequest(peerHandle, requestId)
        }

        override fun onPairingSetupSucceeded(peerHandle: PeerHandle, alias: String) {
            handlePairingSucceeded(peerHandle, alias)
        }

        override fun onPairingSetupFailed(peerHandle: PeerHandle) {
            handlePairingFailed(peerHandle)
        }

        override fun onPairingVerificationSucceed(peerHandle: PeerHandle, alias: String) {
            handlePairingSucceeded(peerHandle, alias)
        }

        override fun onPairingVerificationFailed(peerHandle: PeerHandle) {
            handlePairingFailed(peerHandle)
        }
    }

    private val subscribeCallback = object : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            subscribeSession = session
            Log.d(LOG_TAG, "Subscribe session started")
            updateActiveState()
        }

        override fun onSessionTerminated() {
            subscribeSession = null
            handleDiscoverySessionTerminated()
        }

        override fun onSessionConfigFailed() {
            Log.w(LOG_TAG, "Subscribe session config failed")
            stateFlow.update { it.copy(errorMessage = R.string.rescue_mesh_error_start_failed, isEnabled = false, isBusy = false) }
            stopAwareSession()
            scheduleReconnect("subscribe_config_failed")
        }

        override fun onServiceDiscovered(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray,
            matchFilter: List<ByteArray>
        ) {
            handleServiceDiscovered(peerHandle, serviceSpecificInfo)
        }

        override fun onServiceDiscoveredWithinRange(
            peerHandle: PeerHandle,
            serviceSpecificInfo: ByteArray,
            matchFilter: List<ByteArray>,
            distanceMm: Int
        ) {
            handleServiceDiscovered(peerHandle, serviceSpecificInfo)
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            handleIncomingMessage(peerHandle, message)
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            messageTracker.remove(messageId)
        }

        override fun onMessageSendFailed(messageId: Int) {
            val key = messageTracker.remove(messageId)
            if (key != null) {
                Log.w(LOG_TAG, "Message send failed to peer $key, msgId=$messageId")
            }
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            handlePeerLost(peerHandle)
        }

        override fun onPairingSetupRequestReceived(peerHandle: PeerHandle, requestId: Int) {
            handlePairingSetupRequest(peerHandle, requestId)
        }

        override fun onPairingSetupSucceeded(peerHandle: PeerHandle, alias: String) {
            handlePairingSucceeded(peerHandle, alias)
        }

        override fun onPairingSetupFailed(peerHandle: PeerHandle) {
            handlePairingFailed(peerHandle)
        }

        override fun onPairingVerificationSucceed(peerHandle: PeerHandle, alias: String) {
            handlePairingSucceeded(peerHandle, alias)
        }

        override fun onPairingVerificationFailed(peerHandle: PeerHandle) {
            handlePairingFailed(peerHandle)
        }
    }

    private val attachCallback = object : AttachCallback() {
        override fun onAttached(session: WifiAwareSession) {
            awareSession = session
            reconnectAttempt = 0
            reconnectJob?.cancel()
            reconnectJob = null
            stateFlow.update { it.copy(isBusy = true, errorMessage = null) }
            startDiscoverySessions(session)
        }

        override fun onAttachFailed() {
            Log.w(LOG_TAG, "Wifi Aware attach failed")
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_unavailable,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            scheduleReconnect("attach_failed")
        }

        override fun onAwareSessionTerminated() {
            Log.w(LOG_TAG, "Wifi Aware session terminated by system")
            stopAwareSession()
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_unavailable,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            scheduleReconnect("aware_session_terminated")
        }
    }

    override fun onCreate() {
        super.onCreate()
        selfNodeId = UUID.randomUUID().toString()
        selfLabel = resolveSelfLabel()
        Log.d(LOG_TAG, "Mesh aware service created nodeId=$selfNodeId")
        startForeground(NOTIFICATION_ID, buildNotification(false))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SET_MESH_ENABLED -> {
                val enabled = intent.getBooleanExtra(EXTRA_MESH_ENABLED, false)
                applyMeshEnabled(enabled)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        desiredMeshEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        stopAwareSession()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun setMeshEnabled(enabled: Boolean) {
        applyMeshEnabled(enabled)
    }

    fun clearPeerAuthEvent() {
        stateFlow.update {
            it.copy(peerAuthEvent = null)
        }
    }

    fun sendGroupMessage(message: String): Boolean {
        val normalized = message.trim()
        if (normalized.isBlank() || normalized.length > MAX_CHAT_MESSAGE_LENGTH_CHARS) {
            return false
        }
        val authorizedPeers = peerStateByKey.values.filter { peer ->
            peer.isAuthorized && peer.sessionKey != null
        }
        if (authorizedPeers.isEmpty() && interopConnectedPeerCount() == 0) {
            return false
        }

        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        var sentAny = false
        authorizedPeers.forEach { peer ->
            if (sendEncryptedChatMessage(peer, messageId, timestamp, normalized)) {
                sentAny = true
            }
        }
        val interopSentAny = sendInteropChatMessage(normalized)
        if (sentAny) {
            rememberMessageId(messageId)
        }
        return sentAny || interopSentAny
    }

    private fun applyMeshEnabled(enable: Boolean) {
        if (enable) {
            desiredMeshEnabled = true
            reconnectJob?.cancel()
            reconnectJob = null
            val currentState = stateFlow.value
            if (currentState.isEnabled || currentState.isBusy) {
                return
            }
            stateFlow.update { it.copy(isBusy = true, errorMessage = null) }
            serviceScope.launch {
                if (!prepareToRunMesh()) {
                    stateFlow.update { it.copy(isBusy = false) }
                    return@launch
                }
                ensureManagerAndAttach()
            }
            return
        }
        desiredMeshEnabled = false
        reconnectAttempt = 0
        reconnectJob?.cancel()
        reconnectJob = null
        stopAwareSession()
        stateFlow.update { it.copy(isEnabled = false, isBusy = false, errorMessage = null) }
        stopSelfSafely()
    }

    private suspend fun prepareToRunMesh(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            stateFlow.update { it.copy(errorMessage = R.string.rescue_mesh_error_unsupported, isEnabled = false, isBusy = false) }
            return false
        }
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            stateFlow.update { it.copy(errorMessage = R.string.rescue_mesh_error_unsupported, isEnabled = false, isBusy = false) }
            return false
        }

        if (!hasMeshPermissions()) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_permission_required,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }

        val localRole = withContext(Dispatchers.IO) {
            securityRepository.getUsableStoredCertificateRole(allowExpired = true)
        }
        if (localRole !in MESH_CONTROL_ROLES) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_unauthorized,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }

        selfSessionKeyPair = null
        selfSessionPublicKeyBase64 = null
        selfSessionPublicKeySignatureBase64 = null

        val identity = runCatching {
            withContext(Dispatchers.IO) { securityRepository.getOrCreateDeviceIdentity() }
        }.getOrNull()
        if (identity == null) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_auth_missing,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }

        val proofReady = runCatching {
            roleProofCreator.createProof(
                sessionNonce = "mesh-bootstrap-$selfNodeId"
            )
        }.isSuccess
        if (!proofReady) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_auth_missing,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }

        val sessionKeyPair = runCatching { Crypto.generateEphemeralEcKeyPair() }.getOrNull()
        if (sessionKeyPair == null) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_auth_invalid,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }
        val sessionPublicKeyBase64 = Base64.encodeToString(sessionKeyPair.public.encoded, Base64.NO_WRAP)
        val sessionSignature = runCatching {
            val payload = buildSessionKeySignaturePayload(selfNodeId, sessionPublicKeyBase64)
            Crypto.signData(identity.private, payload)
        }.getOrNull()
        if (sessionSignature == null) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_auth_invalid,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return false
        }

        selfSessionKeyPair = sessionKeyPair
        selfSessionPublicKeyBase64 = sessionPublicKeyBase64
        selfSessionPublicKeySignatureBase64 = Base64.encodeToString(sessionSignature, Base64.NO_WRAP)
        return true
    }

    private fun ensureManagerAndAttach() {
        if (awareSession != null && publishSession != null && subscribeSession != null) {
            return
        }
        if (wifiManager == null) {
            wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }
        val wifiEnabled = runCatching { wifiManager?.isWifiEnabled }.getOrNull() == true
        if (!wifiEnabled) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_wifi_disabled,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            scheduleReconnect("wifi_disabled")
            return
        }
        if (manager == null) {
            manager = getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
        }
        if (connectivityManager == null) {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        }
        val awareManager = manager ?: run {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_unsupported,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            return
        }
        if (!awareManager.isAvailable) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_unavailable,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            scheduleReconnect("manager_unavailable")
            return
        }

        awarePairingSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { awareManager.characteristics?.isAwarePairingSupported == true }
                .getOrDefault(false)
        } else {
            false
        }
        Log.i(LOG_TAG, "Wi-Fi Aware pairing supported=$awarePairingSupported")

        runCatching {
            awareManager.attach(attachCallback, mainHandler)
        }.onFailure { throwable ->
            Log.e(LOG_TAG, "Unable to attach to Wi-Fi Aware", throwable)
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_start_failed,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            scheduleReconnect("attach_throwable")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscoverySessions(session: WifiAwareSession) {
        if (!hasMeshPermissions()) {
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_permission_required,
                    isEnabled = false,
                    isBusy = false
                )
            }
            stopAwareSession()
            return
        }

        clearDiscoverySessions()
        val serviceInfo = buildServiceSpecificInfo()
        val pairingConfig = createAwarePairingConfigIfSupported()
        runCatching {
            val publishBuilder = PublishConfig.Builder()
                .setServiceName(MESH_SERVICE_NAME)
                .setServiceSpecificInfo(serviceInfo)
                .setTtlSec(MESH_TTL_SECONDS)
            if (pairingConfig != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                publishBuilder.setPairingConfig(pairingConfig)
            }
            session.publish(
                publishBuilder.build(),
                publishCallback,
                mainHandler
            )
        }.onSuccess {
            Log.d(LOG_TAG, "Publish request sent")
        }.onFailure { throwable ->
            Log.e(LOG_TAG, "Unable to start publish session", throwable)
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_start_failed,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            stopAwareSession()
            scheduleReconnect("publish_start_failed")
            return@startDiscoverySessions
        }

        runCatching {
            val subscribeBuilder = SubscribeConfig.Builder()
                .setServiceName(MESH_SERVICE_NAME)
                .setServiceSpecificInfo(serviceInfo)
                .setTtlSec(MESH_TTL_SECONDS)
            if (pairingConfig != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                subscribeBuilder.setPairingConfig(pairingConfig)
            }
            session.subscribe(
                subscribeBuilder.build(),
                subscribeCallback,
                mainHandler
            )
        }.onSuccess {
            Log.d(LOG_TAG, "Subscribe request sent")
        }.onFailure { throwable ->
            Log.e(LOG_TAG, "Unable to start subscribe session", throwable)
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.rescue_mesh_error_start_failed,
                    isEnabled = false,
                    isBusy = false,
                )
            }
            stopAwareSession()
            scheduleReconnect("subscribe_start_failed")
        }
    }

    private fun updateActiveState() {
        val isRunning = publishSession != null || subscribeSession != null
        val connectedPeers = if (isRunning) totalConnectedPeerCount() else 0
        stateFlow.update { previous ->
            if (isRunning) {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
                previous.copy(
                    isEnabled = true,
                    isBusy = false,
                    connectedPeerCount = connectedPeers,
                    errorMessage = null
                )
            } else {
                previous.copy(isEnabled = false, isBusy = false, connectedPeerCount = 0)
            }
        }
    }

    private fun stopAwareSession() {
        publishSession?.close()
        subscribeSession?.close()
        publishSession = null
        subscribeSession = null
        awareSession?.close()
        awareSession = null
        peerStateByKey.clear()
        messageTracker.clear()
        unauthorizedAttemptWindow.clear()
        seenMessageIds.clear()
        clearAllInteropConnections()
        syncConnectedPeerCount()
        updateActiveState()
    }

    private fun handleDiscoverySessionTerminated() {
        if (publishSession == null && subscribeSession == null && stateFlow.value.isEnabled) {
            peerStateByKey.clear()
            messageTracker.clear()
            clearAllInteropConnections()
            stateFlow.update {
                it.copy(
                    isEnabled = false,
                    isBusy = false,
                    connectedPeerCount = 0,
                    errorMessage = R.string.rescue_mesh_error_unavailable,
                )
            }
            scheduleReconnect("discovery_session_terminated")
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!desiredMeshEnabled) {
            return
        }

        reconnectJob?.cancel()
        reconnectAttempt += 1
        val backoffDelayMs = (RECONNECT_BASE_DELAY_MS * reconnectAttempt).coerceAtMost(RECONNECT_MAX_DELAY_MS)
        Log.w(LOG_TAG, "Scheduling mesh reconnect in ${backoffDelayMs}ms, reason=$reason, attempt=$reconnectAttempt")

        reconnectJob = serviceScope.launch {
            stateFlow.update { it.copy(isBusy = true) }
            delay(backoffDelayMs)
            if (!desiredMeshEnabled) {
                return@launch
            }
            if (!prepareToRunMesh()) {
                stateFlow.update { it.copy(isBusy = false) }
                return@launch
            }
            ensureManagerAndAttach()
        }
    }

    private fun clearDiscoverySessions() {
        publishSession?.close()
        subscribeSession?.close()
        publishSession = null
        subscribeSession = null
    }

    private fun hasMeshPermissions(): Boolean {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocation = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearbyWifi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasNearbyWifi) {
            return false
        }
        return hasFineLocation || hasCoarseLocation
    }

    private fun handleServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray?) {
        val key = peerKey(peerHandle)
        val peerInfo = parseDiscoveredPeerInfo(serviceSpecificInfo)
        val peerState = peerStateByKey.getOrPut(key) {
            PeerState(handle = peerHandle, key = key, labelHint = peerInfo.label)
        }
        peerState.handle = peerHandle
        peerState.labelHint = peerState.labelHint ?: peerInfo.label
        peerState.remotePairingCapable = peerInfo.pairingCapable ?: peerState.remotePairingCapable
        val pairingRequiredForPeer = shouldRequireAwarePairing(peerState)
        maybeInitiateAwarePairing(peerState)
        maybeStartInteropConnection(peerState)
        if (peerState.awaitingHello) {
            return
        }
        if (pairingRequiredForPeer && !peerState.pairingEstablished) {
            return
        }
        peerState.awaitingHello = true
        sendHello(peerState)
    }

    private fun handlePeerLost(peerHandle: PeerHandle) {
        val key = peerKey(peerHandle)
        peerStateByKey.remove(key)
        clearInteropForPeer(key)
        syncConnectedPeerCount()
    }

    private fun maybeInitiateAwarePairing(peerState: PeerState) {
        if (!shouldAttemptAwarePairing(peerState)) {
            return
        }
        if (peerState.pairingRequested || peerState.pairingEstablished) {
            return
        }
        val session = publishSession ?: subscribeSession ?: return
        val alias = suggestedAliasForPeer(peerState)
        peerState.pairingRequested = true
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                session.initiatePairingRequest(
                    peerState.handle,
                    alias,
                    android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_PASN_128,
                    null
                )
            }
        }.onFailure { throwable ->
            peerState.pairingRequested = false
            Log.w(LOG_TAG, "Unable to initiate Wi-Fi Aware pairing with peer=${peerState.key}", throwable)
        }
    }

    private fun handlePairingSetupRequest(peerHandle: PeerHandle, requestId: Int) {
        if (!isAwarePairingAvailable()) {
            return
        }
        val key = peerKey(peerHandle)
        val peerState = peerStateByKey.getOrPut(key) {
            PeerState(handle = peerHandle, key = key)
        }
        peerState.handle = peerHandle
        val session = publishSession ?: subscribeSession ?: return
        val alias = suggestedAliasForPeer(peerState)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                session.acceptPairingRequest(
                    requestId,
                    peerHandle,
                    alias,
                    android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_PASN_128,
                    null
                )
            }
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Unable to accept Wi-Fi Aware pairing request for peer=$key", throwable)
        }
    }

    private fun handlePairingSucceeded(peerHandle: PeerHandle, alias: String) {
        val key = peerKey(peerHandle)
        val peerState = peerStateByKey[key] ?: return
        peerState.pairingRequested = false
        peerState.pairingEstablished = true
        val normalizedAlias = alias.trim().ifBlank { null }
        if (normalizedAlias != null) {
            peerState.pairedAlias = normalizedAlias
        }
        Log.d(LOG_TAG, "Wi-Fi Aware pairing succeeded for peer=$key alias=${normalizedAlias ?: "n/a"}")
        maybeStartInteropConnection(peerState)
        if (!peerState.awaitingHello) {
            peerState.awaitingHello = true
            sendHello(peerState)
        }
    }

    private fun handlePairingFailed(peerHandle: PeerHandle) {
        val key = peerKey(peerHandle)
        val peerState = peerStateByKey[key] ?: return
        peerState.pairingRequested = false
        peerState.pairingEstablished = false
        Log.w(LOG_TAG, "Wi-Fi Aware pairing failed for peer=$key")
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun isAwarePairingAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && awarePairingSupported
    }

    private fun shouldAttemptAwarePairing(peerState: PeerState): Boolean {
        if (!isAwarePairingAvailable()) {
            return false
        }
        return peerState.remotePairingCapable == true
    }

    private fun shouldRequireAwarePairing(peerState: PeerState): Boolean {
        return shouldAttemptAwarePairing(peerState)
    }

    private fun createAwarePairingConfigIfSupported(): AwarePairingConfig? {
        if (!isAwarePairingAvailable()) {
            return null
        }
        return runCatching {
            AwarePairingConfig.Builder()
                .setPairingSetupEnabled(true)
                .setPairingVerificationEnabled(true)
                .setPairingCacheEnabled(true)
                .setBootstrappingMethods(AwarePairingConfig.PAIRING_BOOTSTRAPPING_OPPORTUNISTIC)
                .build()
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Unable to build Wi-Fi Aware pairing config", throwable)
        }.getOrNull()
    }

    private fun suggestedAliasForPeer(peerState: PeerState): String {
        val preferred = peerState.labelHint?.trim()?.takeIf { it.isNotBlank() }
        if (preferred != null) {
            return preferred.take(MAX_AWARE_PAIRING_ALIAS_LENGTH)
        }
        return "cc-${peerState.key}".take(MAX_AWARE_PAIRING_ALIAS_LENGTH)
    }

    private fun maybeStartInteropConnection(peerState: PeerState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        if (!hasMeshPermissions()) {
            return
        }
        if (!peerState.pairingEstablished) {
            return
        }

        val session = subscribeSession ?: return
        val cm = connectivityManager ?: return
        val key = peerState.key
        synchronized(interopLock) {
            if (interopConnectionsByKey.containsKey(key) || interopCallbacksByKey.containsKey(key) || interopConnectingKeys.contains(key)) {
                return
            }
        }

        val specifier = runCatching {
            android.net.wifi.aware.WifiAwareNetworkSpecifier.Builder(session, peerState.handle).build()
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "Unable to build Wi-Fi Aware network specifier for peer=$key", throwable)
            return
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    return
                }
                val info = networkCapabilities.transportInfo as? WifiAwareNetworkInfo ?: return
                tryOpenInteropSocket(key, peerState.handle, network, info)
            }

            override fun onLost(network: Network) {
                clearInteropForPeer(key)
            }

            override fun onUnavailable() {
                clearInteropForPeer(key)
            }
        }

        synchronized(interopLock) {
            interopCallbacksByKey[key] = callback
        }

        runCatching {
            cm.requestNetwork(request, callback, INTEROP_NETWORK_REQUEST_TIMEOUT_MS.toInt())
        }.onFailure { throwable ->
            Log.w(LOG_TAG, "Unable to request Wi-Fi Aware datapath for peer=$key", throwable)
            synchronized(interopLock) {
                interopCallbacksByKey.remove(key)
            }
        }
    }

    private fun tryOpenInteropSocket(
        peerKey: String,
        peerHandle: PeerHandle,
        network: Network,
        networkInfo: WifiAwareNetworkInfo,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val remoteAddress = networkInfo.peerIpv6Addr ?: return
        val remotePort = networkInfo.port
        if (remotePort <= 0) {
            return
        }

        synchronized(interopLock) {
            if (interopConnectionsByKey.containsKey(peerKey)) {
                return
            }
            if (!interopConnectingKeys.add(peerKey)) {
                return
            }
        }

        serviceScope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            var writer: BufferedWriter? = null
            var reader: BufferedReader? = null
            try {
                socket = network.socketFactory.createSocket(remoteAddress, remotePort)
                socket.tcpNoDelay = true
                writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), UTF_8))
                reader = BufferedReader(InputStreamReader(socket.getInputStream(), UTF_8))
                val connection = InteropConnection(
                    peerKey = peerKey,
                    peerHandle = peerHandle,
                    socket = socket,
                    writer = writer,
                )

                synchronized(interopLock) {
                    interopConnectionsByKey[peerKey] = connection
                    interopConnectingKeys.remove(peerKey)
                }
                syncConnectedPeerCount()
                sendInteropPeerInfo(connection)
                readInteropSocketLoop(connection, reader)
            } catch (throwable: Throwable) {
                Log.w(LOG_TAG, "Unable to open interop socket for peer=$peerKey", throwable)
            } finally {
                synchronized(interopLock) {
                    interopConnectingKeys.remove(peerKey)
                }
                runCatching { reader?.close() }
                runCatching { writer?.close() }
                runCatching { socket?.close() }
                clearInteropForPeer(peerKey)
            }
        }
    }

    private fun readInteropSocketLoop(connection: InteropConnection, reader: BufferedReader) {
        while (true) {
            val line = runCatching { reader.readLine() }.getOrNull() ?: break
            val payload = line.trim()
            if (payload.isEmpty()) {
                continue
            }
            handleInteropPayload(connection, payload)
        }
    }

    private fun handleInteropPayload(connection: InteropConnection, payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (json.optString("kind")) {
            "peer_info" -> {
                val remoteName = json.optString("name").trim().ifBlank { null }
                if (remoteName != null) {
                    connection.peerName = remoteName
                    peerStateByKey[connection.peerKey]?.labelHint = remoteName
                }
            }

            "chat" -> {
                val text = json.optString("text").trim()
                if (text.isNotEmpty()) {
                    val sender = connection.peerName
                        ?: peerStateByKey[connection.peerKey]?.labelHint
                        ?: unknownDeviceLabel()
                    MeshChatStore.appendRemoteMessage(
                        id = UUID.randomUUID().toString(),
                        text = text,
                        senderLabel = sender,
                        timestampMillis = System.currentTimeMillis()
                    )
                    sendInteropPacket(
                        connection,
                        JSONObject().apply { put("kind", "delivered_receipt") }
                    )
                }
            }

            "read_receipt", "delivered_receipt" -> {
                // Keep wire compatibility with iOS protocol.
            }
        }
    }

    private fun sendInteropPeerInfo(connection: InteropConnection) {
        sendInteropPacket(
            connection,
            JSONObject().apply {
                put("kind", "peer_info")
                put("name", selfLabel)
                put("role", "rescue")
                put("broadcastId", selfNodeId)
            }
        )
    }

    private fun sendInteropChatMessage(text: String): Boolean {
        val message = text.trim()
        if (message.isEmpty()) {
            return false
        }
        val connections = synchronized(interopLock) {
            interopConnectionsByKey.values.toList()
        }
        if (connections.isEmpty()) {
            return false
        }
        var sentAny = false
        connections.forEach { connection ->
            if (
                sendInteropPacket(
                    connection,
                    JSONObject().apply {
                        put("kind", "chat")
                        put("text", message)
                    }
                )
            ) {
                sentAny = true
            }
        }
        return sentAny
    }

    private fun sendInteropPacket(connection: InteropConnection, packet: JSONObject): Boolean {
        val payload = packet.toString()
        val sent = runCatching {
            synchronized(connection.writeLock) {
                connection.writer.write(payload)
                connection.writer.newLine()
                connection.writer.flush()
            }
        }.isSuccess
        if (!sent) {
            clearInteropForPeer(connection.peerKey)
        }
        return sent
    }

    private fun clearInteropForPeer(peerKey: String) {
        val callback: ConnectivityManager.NetworkCallback?
        val connection: InteropConnection?
        synchronized(interopLock) {
            callback = interopCallbacksByKey.remove(peerKey)
            connection = interopConnectionsByKey.remove(peerKey)
            interopConnectingKeys.remove(peerKey)
        }

        if (callback != null) {
            runCatching {
                connectivityManager?.unregisterNetworkCallback(callback)
            }
        }
        if (connection != null) {
            closeInteropConnection(connection)
        }
        syncConnectedPeerCount()
    }

    private fun clearAllInteropConnections() {
        val keys = synchronized(interopLock) {
            (interopCallbacksByKey.keys + interopConnectionsByKey.keys + interopConnectingKeys).toSet()
        }
        keys.forEach { clearInteropForPeer(it) }
    }

    private fun closeInteropConnection(connection: InteropConnection) {
        runCatching { connection.writer.close() }
        runCatching { connection.socket.close() }
    }

    private fun interopConnectedPeerCount(): Int {
        return synchronized(interopLock) { interopConnectionsByKey.size }
    }

    private fun handleIncomingMessage(peerHandle: PeerHandle, message: ByteArray) {
        if (message.isEmpty()) {
            return
        }
        val text = message.toString(UTF_8)
        val parsed = parseMessage(text) ?: run {
            Log.w(LOG_TAG, "Invalid mesh packet from ${peerHandle.hashCode()}")
            emitUnauthorizedAttempt(peerHandle, null)
            return
        }
        val key = peerKey(peerHandle)
        val peerState = peerStateByKey.getOrPut(key) {
            PeerState(handle = peerHandle, key = key)
        }
        peerState.handle = peerHandle
        if (shouldRequireAwarePairing(peerState) && !peerState.pairingEstablished) {
            maybeInitiateAwarePairing(peerState)
            return
        }
        if (parsed.type == MESSAGE_TYPE_CHUNK) {
            handleChunkMessage(peerState, parsed)
            return
        }
        dispatchParsedMessage(peerState, parsed)
    }

    private fun dispatchParsedMessage(peerState: PeerState, parsed: MeshMessage) {
        when (parsed.type) {
            MESSAGE_TYPE_HELLO -> handleHelloMessage(peerState, parsed)
            MESSAGE_TYPE_AUTH_OK -> handleAuthOkMessage(peerState, parsed)
            MESSAGE_TYPE_CHAT -> handleChatMessage(peerState, parsed)
            MESSAGE_TYPE_AUTH_REJECT -> {
                Log.w(LOG_TAG, "Peer ${peerState.key} rejected by remote")
                resetPeerAuthorization(peerState)
                syncConnectedPeerCount()
            }
            MESSAGE_TYPE_ALERT -> {
                parsed.label?.let { emitUnauthorizedAlertEvent(it) }
            }
            else -> {
                Log.w(LOG_TAG, "Unknown mesh packet type=${parsed.type} from ${peerState.key}")
            }
        }
    }

    private fun handleChunkMessage(peerState: PeerState, parsed: MeshMessage) {
        val chunkId = parsed.chunkId?.trim().orEmpty()
        val chunkIndex = parsed.chunkIndex ?: -1
        val chunkCount = parsed.chunkCount ?: -1
        val chunkPayload = parsed.chunkPayload ?: return
        if (
            chunkId.isBlank() ||
            chunkCount <= 0 ||
            chunkCount > MAX_AWARE_CHUNK_COUNT ||
            chunkIndex < 0 ||
            chunkIndex >= chunkCount
        ) {
            return
        }

        val now = System.currentTimeMillis()
        cleanupExpiredChunkAssemblies(peerState, now)
        val assembly = peerState.chunkAssemblies[chunkId]?.takeIf { it.totalChunks == chunkCount }
            ?: ChunkAssembly(totalChunks = chunkCount, createdAtMillis = now).also {
                peerState.chunkAssemblies[chunkId] = it
            }
        assembly.parts[chunkIndex] = chunkPayload
        if (assembly.parts.size < assembly.totalChunks) {
            return
        }

        val merged = StringBuilder()
        for (index in 0 until assembly.totalChunks) {
            val part = assembly.parts[index] ?: run {
                peerState.chunkAssemblies.remove(chunkId)
                return
            }
            merged.append(part)
            if (merged.length > MAX_REASSEMBLED_PAYLOAD_LENGTH_CHARS) {
                peerState.chunkAssemblies.remove(chunkId)
                return
            }
        }
        peerState.chunkAssemblies.remove(chunkId)
        val reassembled = parseMessage(merged.toString()) ?: return
        if (reassembled.type == MESSAGE_TYPE_CHUNK) {
            return
        }
        dispatchParsedMessage(peerState, reassembled)
    }

    private fun cleanupExpiredChunkAssemblies(peerState: PeerState, now: Long = System.currentTimeMillis()) {
        if (peerState.chunkAssemblies.isEmpty()) {
            return
        }
        val staleKeys = peerState.chunkAssemblies.filterValues {
            (now - it.createdAtMillis) > CHUNK_ASSEMBLY_TTL_MS
        }.keys
        staleKeys.forEach { key ->
            peerState.chunkAssemblies.remove(key)
        }
    }

    private fun handleHelloMessage(peerState: PeerState, parsed: MeshMessage) {
        if (parsed.nonce.isBlank() || parsed.nodeId.isBlank()) {
            emitUnauthorizedAttempt(peerState.handle, parsed.label)
            sendReject(peerState)
            resetPeerAuthorization(peerState)
            syncConnectedPeerCount()
            return
        }
        val proofJson = parsed.proof
        val displayLabel = parsed.label ?: resolvePeerLabelFromProof(proofJson)
        if (proofJson == null) {
            emitUnauthorizedAttempt(peerState.handle, displayLabel)
            broadcastUnauthorizedAttempt(displayLabel ?: unknownDeviceLabel())
            sendReject(peerState)
            resetPeerAuthorization(peerState)
            syncConnectedPeerCount()
            return
        }

        val result = proofJson?.let { proof ->
            runCatching {
                roleProofVerifier.verifyProofPayload(
                    proof = RoleProofPayload.fromJson(proof),
                    expectedSessionNonce = parsed.nonce
                )
            }.getOrNull()
        }

        when (result) {
            is RoleProofVerificationResult.Success -> {
                val sessionPublicKey = verifyAndDecodePeerSessionPublicKey(
                    parsed = parsed,
                    proofPayload = result.proof
                )
                if (sessionPublicKey == null) {
                    emitUnauthorizedAttempt(peerState.handle, displayLabel)
                    sendReject(peerState)
                    resetPeerAuthorization(peerState)
                    syncConnectedPeerCount()
                    return
                }
                val sessionKey = derivePeerSessionKey(
                    peerNodeId = parsed.nodeId,
                    peerSessionPublicKey = sessionPublicKey
                )
                if (sessionKey == null) {
                    emitUnauthorizedAttempt(peerState.handle, displayLabel)
                    sendReject(peerState)
                    resetPeerAuthorization(peerState)
                    syncConnectedPeerCount()
                    return
                }

                peerState.isAuthorized = true
                peerState.labelHint = resolvePeerLabelFromPayload(parsed) ?: displayLabel
                peerState.awaitingHello = false
                peerState.expectedAuthNonce = parsed.nonce
                peerState.nodeId = parsed.nodeId
                peerState.sessionKey = sessionKey
                syncConnectedPeerCount()
                sendAuthOk(peerState, parsed.nonce)
            }

            is RoleProofVerificationResult.Failure -> {
                Log.w(LOG_TAG, "Mesh auth rejected from peer ${peerState.key}: ${result.reason}")
                emitUnauthorizedAttempt(peerState.handle, displayLabel)
                broadcastUnauthorizedAttempt(displayLabel ?: unknownDeviceLabel())
                sendReject(peerState)
                resetPeerAuthorization(peerState)
                syncConnectedPeerCount()
            }

            null -> {
                emitUnauthorizedAttempt(peerState.handle, displayLabel)
                broadcastUnauthorizedAttempt(displayLabel ?: unknownDeviceLabel())
                sendReject(peerState)
                resetPeerAuthorization(peerState)
                syncConnectedPeerCount()
            }
        }
    }

    private fun handleAuthOkMessage(peerState: PeerState, parsed: MeshMessage) {
        if (parsed.nonce.isBlank() || parsed.nonce != peerState.expectedAuthNonce || parsed.nodeId.isBlank()) {
            emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
            resetPeerAuthorization(peerState)
            syncConnectedPeerCount()
            return
        }

        val proofJson = parsed.proof
        if (proofJson == null) {
            emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
            resetPeerAuthorization(peerState)
            syncConnectedPeerCount()
            return
        }

        val result = runCatching {
            roleProofVerifier.verifyProofPayload(
                proof = RoleProofPayload.fromJson(proofJson),
                expectedSessionNonce = parsed.nonce
            )
        }.getOrNull()

        when (result) {
            is RoleProofVerificationResult.Success -> {
                val peerSessionPublicKey = verifyAndDecodePeerSessionPublicKey(
                    parsed = parsed,
                    proofPayload = result.proof
                )
                if (peerSessionPublicKey == null) {
                    emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
                    sendReject(peerState)
                    resetPeerAuthorization(peerState)
                    syncConnectedPeerCount()
                    return
                }
                val sessionKey = derivePeerSessionKey(
                    peerNodeId = parsed.nodeId,
                    peerSessionPublicKey = peerSessionPublicKey
                )
                if (sessionKey == null) {
                    emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
                    sendReject(peerState)
                    resetPeerAuthorization(peerState)
                    syncConnectedPeerCount()
                    return
                }

                peerState.isAuthorized = true
                peerState.awaitingHello = false
                peerState.expectedAuthNonce = null
                peerState.nodeId = parsed.nodeId
                peerState.sessionKey = sessionKey
                peerState.labelHint = resolvePeerLabelFromPayload(parsed) ?: resolvePeerLabelFromProof(proofJson)
                syncConnectedPeerCount()
                Log.d(LOG_TAG, "Authenticated peer ${peerState.key} as authorized")
            }

            is RoleProofVerificationResult.Failure -> {
                Log.w(LOG_TAG, "Peer ${peerState.key} rejected due to invalid auth reply: ${result.reason}")
                emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
                broadcastUnauthorizedAttempt(resolvePeerLabelFromPayload(parsed) ?: unknownDeviceLabel())
                sendReject(peerState)
                resetPeerAuthorization(peerState)
                syncConnectedPeerCount()
            }

            null -> {
                emitUnauthorizedAttempt(peerState.handle, resolvePeerLabelFromPayload(parsed))
                resetPeerAuthorization(peerState)
                syncConnectedPeerCount()
            }
        }
    }

    private fun handleChatMessage(peerState: PeerState, parsed: MeshMessage) {
        if (!peerState.isAuthorized) {
            return
        }
        val expectedNodeId = peerState.nodeId
        if (!expectedNodeId.isNullOrBlank() && expectedNodeId != parsed.nodeId) {
            Log.w(LOG_TAG, "Dropping mesh chat due to node mismatch. expected=$expectedNodeId got=${parsed.nodeId}")
            return
        }

        val messageId = parsed.messageId?.trim().orEmpty()
        val timestamp = parsed.timestampMillis ?: return
        val ivBytes = decodeBase64Value(parsed.ivBase64) ?: return
        val cipherBytes = decodeBase64Value(parsed.cipherBase64) ?: return
        val sessionKey = peerState.sessionKey ?: return

        if (
            messageId.isBlank() ||
            messageId.length > MAX_MESH_MESSAGE_ID_LENGTH ||
            ivBytes.size != AES_GCM_NONCE_LENGTH_BYTES ||
            !isTimestampWithinAllowedWindow(timestamp)
        ) {
            return
        }

        val aad = buildChatAad(
            messageId = messageId,
            senderNodeId = parsed.nodeId,
            timestampMillis = timestamp
        )
        val plaintextBytes = runCatching {
            Crypto.aesGcmDecrypt(
                key = sessionKey,
                nonce = ivBytes,
                ciphertextAndTag = cipherBytes,
                associatedData = aad
            )
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "Unable to decrypt mesh chat message from ${peerState.key}", throwable)
            return
        }

        val messageText = plaintextBytes.toString(UTF_8).trim()
        if (messageText.isBlank() || messageText.length > MAX_CHAT_MESSAGE_LENGTH_CHARS) {
            return
        }
        if (!rememberMessageId(messageId)) {
            return
        }
        MeshChatStore.appendRemoteMessage(
            id = messageId,
            text = messageText,
            senderLabel = resolvePeerLabelFromPayload(parsed) ?: peerState.labelHint,
            timestampMillis = timestamp
        )
    }

    private fun resetPeerAuthorization(peerState: PeerState) {
        peerState.isAuthorized = false
        peerState.awaitingHello = false
        peerState.expectedAuthNonce = null
        peerState.nodeId = null
        peerState.sessionKey = null
        peerState.chunkAssemblies.clear()
    }

    private fun rememberMessageId(messageId: String): Boolean {
        synchronized(seenMessageIds) {
            if (!seenMessageIds.add(messageId)) {
                return false
            }
            while (seenMessageIds.size > MAX_TRACKED_MESSAGE_IDS) {
                val oldest = seenMessageIds.firstOrNull() ?: break
                seenMessageIds.remove(oldest)
            }
            return true
        }
    }

    private fun sendEncryptedChatMessage(
        peerState: PeerState,
        messageId: String,
        timestampMillis: Long,
        message: String
    ): Boolean {
        val sessionKey = peerState.sessionKey ?: return false
        val nonce = runCatching { Crypto.randomBytes(AES_GCM_NONCE_LENGTH_BYTES) }.getOrNull() ?: return false
        val aad = buildChatAad(
            messageId = messageId,
            senderNodeId = selfNodeId,
            timestampMillis = timestampMillis
        )
        val cipherBytes = runCatching {
            Crypto.aesGcmEncrypt(
                key = sessionKey,
                nonce = nonce,
                plaintext = message.toByteArray(UTF_8),
                associatedData = aad
            )
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "Unable to encrypt mesh chat message for peer=${peerState.key}", throwable)
            return false
        }

        val payload = JSONObject().apply {
            put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_CHAT)
            put(MESSAGE_FIELD_NODE_ID, selfNodeId)
            put(MESSAGE_FIELD_LABEL, selfLabel)
            put(MESSAGE_FIELD_MESSAGE_ID, messageId)
            put(MESSAGE_FIELD_TIMESTAMP, timestampMillis)
            put(MESSAGE_FIELD_IV, Base64.encodeToString(nonce, Base64.NO_WRAP))
            put(MESSAGE_FIELD_CIPHER, Base64.encodeToString(cipherBytes, Base64.NO_WRAP))
        }
        return sendMessageToPeer(peerState.handle, payload, peerState.key)
    }

    private fun buildChatAad(
        messageId: String,
        senderNodeId: String,
        timestampMillis: Long
    ): ByteArray {
        val canonical = buildString {
            append("mesh-chat")
            append('|')
            append(messageId)
            append('|')
            append(senderNodeId)
            append('|')
            append(timestampMillis)
        }
        return canonical.toByteArray(UTF_8)
    }

    private fun verifyAndDecodePeerSessionPublicKey(
        parsed: MeshMessage,
        proofPayload: RoleProofPayload
    ): PublicKey? {
        val peerNodeId = parsed.nodeId.trim()
        if (peerNodeId.isBlank()) {
            return null
        }
        val peerSessionPublicKeyBase64 = parsed.sessionPublicKeyBase64 ?: return null
        val peerSessionSignatureBase64 = parsed.sessionPublicKeySignatureBase64 ?: return null
        val signerPublicKey = decodeEcPublicKey(proofPayload.devicePublicKey) ?: return null
        val sessionPublicKey = decodeEcPublicKey(peerSessionPublicKeyBase64) ?: return null
        val signatureBytes = decodeBase64Value(peerSessionSignatureBase64) ?: return null
        val signaturePayload = buildSessionKeySignaturePayload(
            nodeId = peerNodeId,
            sessionPublicKeyBase64 = peerSessionPublicKeyBase64
        )
        val verified = runCatching {
            Crypto.verifySignature(
                publicKey = signerPublicKey,
                data = signaturePayload,
                signatureBytes = signatureBytes
            )
        }.getOrDefault(false)
        return if (verified) sessionPublicKey else null
    }

    private fun derivePeerSessionKey(
        peerNodeId: String,
        peerSessionPublicKey: PublicKey
    ): ByteArray? {
        val selfSessionPrivateKey = selfSessionKeyPair?.private ?: return null
        val sortedNodes = listOf(selfNodeId, peerNodeId).sorted()
        val info = "mesh-chat-v1|${sortedNodes[0]}|${sortedNodes[1]}".toByteArray(UTF_8)
        return runCatching {
            Crypto.deriveSessionKey(
                privateKey = selfSessionPrivateKey,
                peerPublicKey = peerSessionPublicKey,
                info = info,
                outputLength = AES_KEY_LENGTH_BYTES
            )
        }.getOrElse { throwable ->
            Log.w(LOG_TAG, "Failed to derive mesh session key for peerNodeId=$peerNodeId", throwable)
            null
        }
    }

    private fun decodeBase64Value(value: String?): ByteArray? {
        val sanitized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            Base64.decode(sanitized, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeEcPublicKey(base64Value: String): PublicKey? {
        val encoded = decodeBase64Value(base64Value) ?: return null
        val spec = X509EncodedKeySpec(encoded)
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }.getOrNull()
    }

    private fun buildSessionKeySignaturePayload(
        nodeId: String,
        sessionPublicKeyBase64: String
    ): ByteArray {
        return "$nodeId|$sessionPublicKeyBase64".toByteArray(UTF_8)
    }

    private fun parseMessage(rawMessage: String): MeshMessage? {
        return runCatching {
            val json = JSONObject(rawMessage)
            MeshMessage(
                type = json.optString(MESSAGE_FIELD_TYPE),
                nodeId = json.optString(MESSAGE_FIELD_NODE_ID),
                nonce = json.optString(MESSAGE_FIELD_NONCE),
                label = json.optString(MESSAGE_FIELD_LABEL).trim().ifBlank { null },
                proof = json.optString(MESSAGE_FIELD_PROOF).trim().ifBlank { null },
                reason = json.optString(MESSAGE_FIELD_REASON).trim().ifBlank { null },
                sessionPublicKeyBase64 = json.optString(MESSAGE_FIELD_SESSION_PUBLIC_KEY).trim().ifBlank { null },
                sessionPublicKeySignatureBase64 = json.optString(MESSAGE_FIELD_SESSION_SIGNATURE).trim().ifBlank { null },
                messageId = json.optString(MESSAGE_FIELD_MESSAGE_ID).trim().ifBlank { null },
                timestampMillis = json.optLong(MESSAGE_FIELD_TIMESTAMP).takeIf { it > 0L },
                ivBase64 = json.optString(MESSAGE_FIELD_IV).trim().ifBlank { null },
                cipherBase64 = json.optString(MESSAGE_FIELD_CIPHER).trim().ifBlank { null },
                chunkId = json.optString(MESSAGE_FIELD_CHUNK_ID).trim().ifBlank { null },
                chunkIndex = if (json.has(MESSAGE_FIELD_CHUNK_INDEX)) {
                    json.optInt(MESSAGE_FIELD_CHUNK_INDEX).takeIf { it >= 0 }
                } else {
                    null
                },
                chunkCount = if (json.has(MESSAGE_FIELD_CHUNK_COUNT)) {
                    json.optInt(MESSAGE_FIELD_CHUNK_COUNT).takeIf { it > 0 }
                } else {
                    null
                },
                chunkPayload = json.optString(MESSAGE_FIELD_CHUNK_PAYLOAD).takeIf {
                    json.has(MESSAGE_FIELD_CHUNK_PAYLOAD)
                },
            )
        }.getOrNull()?.takeIf { it.type.isNotBlank() }
    }

    private fun syncConnectedPeerCount() {
        stateFlow.update { current ->
            current.copy(connectedPeerCount = totalConnectedPeerCount())
        }
    }

    private fun authorizedPeerCount(): Int {
        return peerStateByKey.values.count { it.isAuthorized }
    }

    private fun totalConnectedPeerCount(): Int {
        return authorizedPeerCount() + interopConnectedPeerCount()
    }

    private fun sendHello(peerState: PeerState) {
        val sessionPublicKey = selfSessionPublicKeyBase64 ?: return
        val sessionPublicKeySignature = selfSessionPublicKeySignatureBase64 ?: return
        val nonce = "n-${sendMessageIdCounter.getAndIncrement()}"
        serviceScope.launch {
            val proofJson = runCatching {
                roleProofCreator.createProof(sessionNonce = nonce).toJson().toString()
            }.getOrNull() ?: run {
                sendReject(peerState)
                return@launch
            }
            val message = JSONObject().apply {
                put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_HELLO)
                put(MESSAGE_FIELD_NONCE, nonce)
                put(MESSAGE_FIELD_NODE_ID, selfNodeId)
                put(MESSAGE_FIELD_LABEL, selfLabel)
                put(MESSAGE_FIELD_PROOF, proofJson)
                put(MESSAGE_FIELD_SESSION_PUBLIC_KEY, sessionPublicKey)
                put(MESSAGE_FIELD_SESSION_SIGNATURE, sessionPublicKeySignature)
            }
            sendMessageToPeer(peerState.handle, message, peerState.key)
        }
    }

    private fun sendAuthOk(peerState: PeerState, nonce: String) {
        val sessionPublicKey = selfSessionPublicKeyBase64 ?: return
        val sessionPublicKeySignature = selfSessionPublicKeySignatureBase64 ?: return
        serviceScope.launch {
            val proofJson = runCatching {
                roleProofCreator.createProof(sessionNonce = nonce).toJson().toString()
            }.getOrNull() ?: run {
                sendReject(peerState)
                return@launch
            }
            val message = JSONObject().apply {
                put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_AUTH_OK)
                put(MESSAGE_FIELD_NONCE, nonce)
                put(MESSAGE_FIELD_NODE_ID, selfNodeId)
                put(MESSAGE_FIELD_LABEL, selfLabel)
                put(MESSAGE_FIELD_PROOF, proofJson)
                put(MESSAGE_FIELD_SESSION_PUBLIC_KEY, sessionPublicKey)
                put(MESSAGE_FIELD_SESSION_SIGNATURE, sessionPublicKeySignature)
            }
            sendMessageToPeer(peerState.handle, message, peerState.key)
        }
    }

    private fun sendReject(peerState: PeerState) {
        val message = JSONObject().apply {
            put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_AUTH_REJECT)
            put(MESSAGE_FIELD_NODE_ID, selfNodeId)
            put(MESSAGE_FIELD_REASON, getString(R.string.rescue_mesh_error_unauthorized))
        }
        sendMessageToPeer(peerState.handle, message, peerState.key)
    }

    private fun broadcastUnauthorizedAttempt(peerLabel: String) {
        val message = JSONObject().apply {
            put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_ALERT)
            put(MESSAGE_FIELD_LABEL, peerLabel)
            put(MESSAGE_FIELD_REASON, getString(R.string.rescue_mesh_error_unauthorized))
        }
        peerStateByKey.values
            .filter { it.isAuthorized }
            .forEach { peer -> sendMessageToPeer(peer.handle, message, peer.key) }
    }

    private fun sendMessageToPeer(peerHandle: PeerHandle, payload: JSONObject, peerKey: String): Boolean {
        val payloadText = payload.toString()
        val payloadSize = payloadText.toByteArray(UTF_8).size
        if (payloadSize <= MAX_AWARE_MESSAGE_PAYLOAD_BYTES) {
            return sendRawMessageToPeer(peerHandle, payloadText, peerKey)
        }
        return sendChunkedMessageToPeer(peerHandle, payloadText, peerKey)
    }

    private fun sendChunkedMessageToPeer(peerHandle: PeerHandle, payloadText: String, peerKey: String): Boolean {
        val chunks = payloadText.chunked(MAX_AWARE_CHUNK_PAYLOAD_LENGTH_CHARS)
        if (chunks.isEmpty() || chunks.size > MAX_AWARE_CHUNK_COUNT) {
            Log.w(LOG_TAG, "Unable to chunk mesh payload for peer=$peerKey size=${payloadText.length}")
            return false
        }
        val chunkId = "chunk-${UUID.randomUUID()}"
        for ((index, chunkPayload) in chunks.withIndex()) {
            val chunkMessage = JSONObject().apply {
                put(MESSAGE_FIELD_TYPE, MESSAGE_TYPE_CHUNK)
                put(MESSAGE_FIELD_CHUNK_ID, chunkId)
                put(MESSAGE_FIELD_CHUNK_INDEX, index)
                put(MESSAGE_FIELD_CHUNK_COUNT, chunks.size)
                put(MESSAGE_FIELD_CHUNK_PAYLOAD, chunkPayload)
            }.toString()
            if (!sendRawMessageToPeer(peerHandle, chunkMessage, peerKey)) {
                return false
            }
        }
        return true
    }

    private fun sendRawMessageToPeer(peerHandle: PeerHandle, payloadText: String, peerKey: String): Boolean {
        val messageId = sendMessageIdCounter.getAndIncrement()
        val session = publishSession ?: subscribeSession ?: return false
        runCatching {
            session.sendMessage(peerHandle, messageId, payloadText.toByteArray(UTF_8))
            messageTracker[messageId] = peerKey
        }.onFailure { throwable ->
            messageTracker.remove(messageId)
            Log.w(LOG_TAG, "Unable to send mesh message to $peerKey", throwable)
            return false
        }
        return true
    }

    private fun emitUnauthorizedAttempt(peerHandle: PeerHandle, peerLabel: String?) {
        val knownLabel = peerLabel ?: unknownDeviceLabel()
        emitUnauthorizedAlertEvent(knownLabel)
    }

    private fun emitUnauthorizedAlertEvent(peerLabel: String) {
        val now = System.currentTimeMillis()
        val key = peerLabel.trim().ifBlank { unknownDeviceLabel() }
        val last = unauthorizedAttemptWindow[key] ?: 0L
        if ((now - last) < UNAUTHORIZED_ALERT_THROTTLE_MS) {
            return
        }
        unauthorizedAttemptWindow[key] = now
        stateFlow.update {
            it.copy(
                peerAuthEvent = MeshPeerAuthEvent(
                    eventId = eventCounter.incrementAndGet(),
                    peerLabel = key,
                )
            )
        }
    }

    private fun isTimestampWithinAllowedWindow(timestampMillis: Long): Boolean {
        val now = System.currentTimeMillis()
        if (timestampMillis > now + MAX_FUTURE_CLOCK_SKEW_MS) {
            return false
        }
        if (timestampMillis < now - MAX_CHAT_MESSAGE_AGE_MS) {
            return false
        }
        return true
    }

    private fun stopSelfSafely() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun unknownDeviceLabel(): String = getString(R.string.rescue_unknown_device)

    private fun resolvePeerLabelFromPayload(message: MeshMessage): String? {
        val proofLabel = resolvePeerLabelFromProof(message.proof)
        return message.label ?: proofLabel
    }

    private fun resolvePeerLabelFromProof(proof: String?): String? {
        if (proof == null) return null
        val parsed = runCatching { RoleProofPayload.fromJson(proof) }.getOrNull() ?: return null
        val certBytes = runCatching {
            Base64.decode(parsed.certificate, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        val certificate = runCatching { RoleCertificate.fromStorageBytes(certBytes) }.getOrNull() ?: return null
        return if (certificate.ownerUid.isNotBlank()) {
            certificate.ownerUid
        } else {
            null
        }
    }

    private fun resolveSelfLabel(): String = "${defaultNodeLabelPrefix()}-${
        selfNodeId.take(6).uppercase(Locale.US)
    }"

    private fun defaultNodeLabelPrefix(): String {
        return NotificationLocalization.localizedContext(this)
            .getString(R.string.mesh_service_default_node_label_prefix)
    }

    private fun buildServiceSpecificInfo(): ByteArray {
        val info = JSONObject().apply {
            put(MESH_DISCOVERY_KEY_NODE_ID, selfNodeId)
            put(MESH_DISCOVERY_KEY_LABEL, selfLabel)
            put(MESH_DISCOVERY_KEY_PAIRING_CAPABLE, isAwarePairingAvailable())
        }
        return info.toString().toByteArray(UTF_8)
    }

    private fun parseDiscoveredPeerInfo(serviceSpecificInfo: ByteArray?): DiscoveredPeerInfo {
        if (serviceSpecificInfo == null) return DiscoveredPeerInfo(null, null, null)
        return runCatching {
            val json = JSONObject(String(serviceSpecificInfo, UTF_8))
            DiscoveredPeerInfo(
                nodeId = json.optString(MESH_DISCOVERY_KEY_NODE_ID).trim().takeIf { it.isNotBlank() },
                label = json.optString(MESH_DISCOVERY_KEY_LABEL).trim().takeIf { it.isNotBlank() },
                pairingCapable = if (json.has(MESH_DISCOVERY_KEY_PAIRING_CAPABLE)) {
                    json.optBoolean(MESH_DISCOVERY_KEY_PAIRING_CAPABLE, false)
                } else {
                    null
                },
            )
        }.getOrDefault(DiscoveredPeerInfo(null, null, null))
    }

    private fun peerKey(peerHandle: PeerHandle): String {
        return peerHandle.hashCode().toString()
    }

    private fun buildNotification(isEnabled: Boolean): Notification {
        ensureNotificationChannel(NOTIFICATION_CHANNEL_ID)
        val title = getString(R.string.mesh_service_notification_title)
        val text = if (isEnabled) {
            getString(R.string.mesh_service_notification_text)
        } else {
            getString(R.string.mesh_service_notification_text)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val systemService = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            channelId,
            getString(R.string.mesh_service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mesh_service_channel_description)
        }
        systemService.createNotificationChannel(channel)
    }

    private data class PeerState(
        var handle: PeerHandle,
        val key: String,
        var labelHint: String? = null,
        var remotePairingCapable: Boolean? = null,
        var pairedAlias: String? = null,
        var pairingRequested: Boolean = false,
        var pairingEstablished: Boolean = false,
        var isAuthorized: Boolean = false,
        var awaitingHello: Boolean = false,
        var expectedAuthNonce: String? = null,
        var nodeId: String? = null,
        var sessionKey: ByteArray? = null,
        val chunkAssemblies: MutableMap<String, ChunkAssembly> = mutableMapOf(),
    )

    private data class InteropConnection(
        val peerKey: String,
        val peerHandle: PeerHandle,
        val socket: Socket,
        val writer: BufferedWriter,
        val writeLock: Any = Any(),
        var peerName: String? = null,
    )

    private data class MeshMessage(
        val type: String,
        val nodeId: String,
        val nonce: String,
        val label: String?,
        val proof: String?,
        val reason: String?,
        val sessionPublicKeyBase64: String?,
        val sessionPublicKeySignatureBase64: String?,
        val messageId: String?,
        val timestampMillis: Long?,
        val ivBase64: String?,
        val cipherBase64: String?,
        val chunkId: String?,
        val chunkIndex: Int?,
        val chunkCount: Int?,
        val chunkPayload: String?,
    )

    private data class ChunkAssembly(
        val totalChunks: Int,
        val createdAtMillis: Long,
        val parts: MutableMap<Int, String> = mutableMapOf(),
    )

    private data class DiscoveredPeerInfo(
        val nodeId: String?,
        val label: String?,
        val pairingCapable: Boolean?,
    )

    inner class LocalBinder : Binder() {
        fun getService(): MeshAwareService = this@MeshAwareService
    }

    companion object {
        const val ACTION_SET_MESH_ENABLED = "com.auralis.crisisconnect.action.SET_MESH_ENABLED"
        const val EXTRA_MESH_ENABLED = "extra_mesh_enabled"
        private const val LOG_TAG = "MeshAwareService"
        private const val NOTIFICATION_ID = 2503
        private const val NOTIFICATION_CHANNEL_ID = "mesh_service_channel"
        private const val MESH_SERVICE_NAME = "_ccrescue._tcp"
        private const val MESH_TTL_SECONDS = 30
        private const val MAX_AWARE_PAIRING_ALIAS_LENGTH = 64
        private const val INTEROP_NETWORK_REQUEST_TIMEOUT_MS = 30_000L
        private const val MESSAGE_FIELD_TYPE = "type"
        private const val MESSAGE_FIELD_NONCE = "nonce"
        private const val MESSAGE_FIELD_NODE_ID = "nodeId"
        private const val MESSAGE_FIELD_LABEL = "label"
        private const val MESSAGE_FIELD_PROOF = "proof"
        private const val MESSAGE_FIELD_REASON = "reason"
        private const val MESSAGE_FIELD_SESSION_PUBLIC_KEY = "sessionPub"
        private const val MESSAGE_FIELD_SESSION_SIGNATURE = "sessionSig"
        private const val MESSAGE_FIELD_MESSAGE_ID = "messageId"
        private const val MESSAGE_FIELD_TIMESTAMP = "timestamp"
        private const val MESSAGE_FIELD_IV = "iv"
        private const val MESSAGE_FIELD_CIPHER = "cipher"
        private const val MESSAGE_FIELD_CHUNK_ID = "chunkId"
        private const val MESSAGE_FIELD_CHUNK_INDEX = "chunkIndex"
        private const val MESSAGE_FIELD_CHUNK_COUNT = "chunkCount"
        private const val MESSAGE_FIELD_CHUNK_PAYLOAD = "chunkPayload"
        private const val MESSAGE_TYPE_HELLO = "hello"
        private const val MESSAGE_TYPE_AUTH_OK = "authOk"
        private const val MESSAGE_TYPE_AUTH_REJECT = "authReject"
        private const val MESSAGE_TYPE_ALERT = "unauthorizedAlert"
        private const val MESSAGE_TYPE_CHAT = "chat"
        private const val MESSAGE_TYPE_CHUNK = "chunk"
        private const val MESH_DISCOVERY_KEY_NODE_ID = "nodeId"
        private const val MESH_DISCOVERY_KEY_LABEL = "label"
        private const val MESH_DISCOVERY_KEY_PAIRING_CAPABLE = "pairingCapable"
        private const val LEGACY_DEFAULT_NODE_LABEL_PREFIX = "RescueMesh"
        private const val UNAUTHORIZED_ALERT_THROTTLE_MS = 5 * 60_000L
        private const val RECONNECT_BASE_DELAY_MS = 1_500L
        private const val RECONNECT_MAX_DELAY_MS = 8_000L
        private const val MAX_TRACKED_MESSAGE_IDS = 2048
        private const val MAX_MESH_MESSAGE_ID_LENGTH = 128
        private const val MAX_CHAT_MESSAGE_LENGTH_CHARS = 1024
        private const val MAX_CHAT_MESSAGE_AGE_MS = 24 * 60 * 60 * 1000L
        private const val MAX_FUTURE_CLOCK_SKEW_MS = 2 * 60 * 1000L
        private const val MAX_AWARE_MESSAGE_PAYLOAD_BYTES = 220
        private const val MAX_AWARE_CHUNK_PAYLOAD_LENGTH_CHARS = 96
        private const val MAX_AWARE_CHUNK_COUNT = 512
        private const val MAX_REASSEMBLED_PAYLOAD_LENGTH_CHARS = 128 * 1024
        private const val CHUNK_ASSEMBLY_TTL_MS = 60_000L
        private const val AES_GCM_NONCE_LENGTH_BYTES = 12
        private const val AES_KEY_LENGTH_BYTES = 32
        private val MESH_CONTROL_ROLES = setOf("admin", "fieldteam")
        private val UTF_8: Charset = StandardCharsets.UTF_8

        fun canStart(context: Context): Boolean {
            val appContext = context.applicationContext
            return runCatching {
                runBlocking(Dispatchers.IO) {
                    val role = SecurityRepository(appContext)
                        .getUsableStoredCertificateRole(allowExpired = true)
                        ?.trim()
                        ?.lowercase(Locale.US)
                    role in MESH_CONTROL_ROLES
                }
            }.getOrDefault(false)
        }
    }
}
