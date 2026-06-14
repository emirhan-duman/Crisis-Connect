package com.auralis.crisisconnect.service.gattmesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.crypto.AesGcm
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.AuthorityMeshChatStore
import com.auralis.crisisconnect.data.GattMeshChatStore
import com.auralis.crisisconnect.data.MeshChatStoreCore
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.MeshMessageStatus
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.data.markLocalMessagesDeliveredWithRecipient
import com.auralis.crisisconnect.data.markLocalMessagesReadWithRecipient
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.updateRemoteMessageMetadata
import com.auralis.crisisconnect.data.updateLocalMessageSentToRecipients
import com.auralis.crisisconnect.data.upsertLocalTextMessage
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.saveLocalImageMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveLocalAudioMessage
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.core.media.BLE_IMAGE_TRANSFER_PROFILE
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.generateImageThumbnail
import com.auralis.crisisconnect.core.media.prepareImageAttachmentForTransfer
import android.net.Uri
import com.auralis.crisisconnect.security.BleChunkReceiver
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.MissingRoleCertificateException
import com.auralis.crisisconnect.security.RoleCertificate
import com.auralis.crisisconnect.security.RoleProofCreator
import com.auralis.crisisconnect.security.RoleProofPayload
import com.auralis.crisisconnect.security.RoleProofVerificationResult
import com.auralis.crisisconnect.security.RoleProofVerifier
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.BleRadioPolicy
import com.auralis.crisisconnect.service.BluetoothClassicDiscoveryGuard
import com.auralis.crisisconnect.service.BleMessageNotifier
import com.auralis.crisisconnect.service.GattSOSServerService
import com.auralis.crisisconnect.service.NotificationLocalization
import com.auralis.crisisconnect.service.client.BleConnectQueue
import com.auralis.crisisconnect.service.client.BleKnownPeersStore
import com.auralis.crisisconnect.service.p2p.P2pGattChatManager
import com.auralis.crisisconnect.service.p2p.P2pGattServerService
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.util.writeCharacteristicCompat
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

open class GattMeshForegroundService : Service() {

    /**
     * The mesh network this instance speaks. Defaults to the public mesh; the authority-only mesh
     * subclass overrides this to broadcast on a separate service UUID and encrypt with a separate
     * key. Implemented as a getter (no backing field) so it stays valid while the service and its
     * delegate fields are still being constructed.
     */
    protected open val profile: MeshProfile get() = MeshProfiles.PUBLIC

    /** In-memory chat store for this profile; public and authority chats are kept fully separate. */
    private val chatStore: MeshChatStoreCore
        get() = if (profile.id == MeshProfiles.AUTHORITY.id) AuthorityMeshChatStore else GattMeshChatStore

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow(GattMeshServiceState())
    val state: StateFlow<GattMeshServiceState> = stateFlow.asStateFlow()

    private val lock = Any()
    private val discoveredPeers = mutableMapOf<String, Long>()
    private val firstDiscoveredPeers = mutableMapOf<String, Long>()
    private val discoveredDevices = mutableMapOf<String, BluetoothDevice>()
    private val discoveredPeerInitiatorRanks = mutableMapOf<String, Int>()
    private val serverDevices = mutableMapOf<String, BluetoothDevice>()
    private val serverNotifyEnabled = mutableMapOf<String, Boolean>()
    private val serverConnectedAt = mutableMapOf<String, Long>()
    private val serverPeerMtu = mutableMapOf<String, Int>()
    private val serverPendingPayload = mutableMapOf<String, ByteArray>()
    private val clientPeers = mutableMapOf<String, ClientPeer>()
    private val clientWriteTickets = mutableMapOf<String, ClientWriteTicket>()
    private val serverNotifyTickets = mutableMapOf<String, ServerNotifyTicket>()
    private val clientFailureBackoff = mutableMapOf<String, ClientFailureBackoff>()
    private val lastConnectAttemptMillis = mutableMapOf<String, Long>()
    private val clientSendRecoveryBlockedUntilMillis = mutableMapOf<String, Long>()
    private val p2pSuppressedPeersUntil = mutableMapOf<String, Long>()
    private val inboundRateWindows = mutableMapOf<String, InboundRateWindow>()
    private val seenMessageIds = LinkedHashMap<String, Long>()
    private val peerSenderLabels = mutableMapOf<String, String>()
    private val peerVerificationByAddress = mutableMapOf<String, PeerVerificationState>()
    private val pendingPeerVerificationNonces = mutableMapOf<String, String>()
    private val pendingPeerVerificationRequestedAtMillis = mutableMapOf<String, Long>()
    private val identityAnnouncementSentForPeers = mutableSetOf<String>()
    private val inboundChunkReceivers =
        mutableMapOf<String, MutableMap<InboundTransportChannel, BleChunkReceiver>>()
    private val outboundSendLocks = mutableMapOf<String, Any>()
    private val inboundImageBlobs = mutableMapOf<String, InboundImageBlobState>()
    private var awareAccelerator: AuthorityMeshAwareAccelerator? = null
    private val clientNotificationSettleJobs = mutableMapOf<String, Job>()
    private val clientNotificationBypassPeers = mutableSetOf<String>()
    private val clientNoResponsePreferredPeers = mutableSetOf<String>()
    private val clientWithResponsePreferredUntilElapsedRealtimeMs = mutableMapOf<String, Long>()
    private val clientPacketQuietUntilElapsedRealtimeMs = mutableMapOf<String, Long>()
    private val serverNotifyCallbackBypassPeers = mutableSetOf<String>()
    private val clientNoResponseSettleOverridesMs = mutableMapOf<String, Long>()
    private val securityRecoveryAttemptAtMillis = mutableMapOf<String, Long>()
    private val sharedModeMeshQualifiedPeers = mutableSetOf<String>()
    private val identityAnnouncementPendingForPeers = mutableSetOf<String>()
    private val sharedModeObservedInboundConnectedAt = mutableMapOf<String, Long>()
    private val pendingDeliveryReceiptIds = LinkedHashSet<String>()
    private val pendingOutboundPackets = LinkedHashMap<String, MeshPacket>()
    private val pendingOutboundRetryStates = mutableMapOf<String, PendingOutboundRetryState>()
    private val pendingOutboundQueueFileLock = Any()
    private val pendingOutboundQueuePersistEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val inboundFallbackJobs = mutableMapOf<String, Job>()
    private val clientReconnectJobs = mutableMapOf<String, Job>()
    private var systemConnectedLeAddresses = emptySet<String>()
    private var systemConnectedSnapshotAtMillis = 0L
    private var hasFreshSystemConnectedSnapshot = false

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var scanResumeJob: Job? = null
    private var cleanupJob: Job? = null
    private var startupTimeoutJob: Job? = null
    private var postAddServiceRecoveryJob: Job? = null
    private var localGattServerStartupFallbackJob: Job? = null
    private var pendingOutboundQueuePersistJob: Job? = null
    private var pendingOutboundFlushJob: Job? = null
    private var pendingOutboundRecoveryJob: Job? = null
    private var pendingDeliveryReceiptJob: Job? = null
    private var advertiseRetryJob: Job? = null
    private var legacyLocalServerRecoveryJob: Job? = null
    private var runtimeActive = false
    private var startupInProgress = false
    private var foregroundStarted = false
    private var sosSharedGattServerMode = false
    private var p2pSharedGattServerMode = false
    private var usesExternalGattServer = false
    private var localGattServerStartupCompleted = false
    private var clientOnlyMeshFallbackActive = false
    private var legacyLocalServerRecoveryAttempts = 0
    private var activeLocalServerCallbackGeneration = 0L
    private var classicDiscoveryReceiverRegistered = false
    private var localSenderLabel: String = LEGACY_DEFAULT_SENDER_LABEL
    private var localInitiatorSalt: Int = DEFAULT_INITIATOR_SALT
    private var localInitiatorRank: Int = DEFAULT_INITIATOR_SALT
    private var lastLoggedConnectedCount = -1
    private var lastLoggedReadyCount = -1
    private var lastLoggedDiscoveredCount = -1
    private var lastNotifiedConnectedCount = -1
    private var lastNotifiedReadyCount = -1
    private var lastNotifiedDiscoveredCount = -1
    private var advertiseConflictRetryAttempts = 0
    @Volatile
    private var gattMeshNotificationsEnabled = true
    private val securityRepository by lazy(LazyThreadSafetyMode.NONE) {
        SecurityRepository(applicationContext)
    }
    private val roleProofCreator by lazy(LazyThreadSafetyMode.NONE) {
        RoleProofCreator(applicationContext)
    }
    private val roleProofVerifier by lazy(LazyThreadSafetyMode.NONE) {
        RoleProofVerifier()
    }
    private val messageOriginRoleProofVerifier by lazy(LazyThreadSafetyMode.NONE) {
        RoleProofVerifier(maxClockSkewMillis = MAX_ORIGIN_PROOF_AGE_MS)
    }
    private val meshPayloadKey: ByteArray by lazy(LazyThreadSafetyMode.NONE) {
        profile.derivePayloadKey(this) ?: ByteArray(32) { index -> (index + 1).toByte() }
    }

    private val classicDiscoveryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!runtimeActive) return
            if (intent?.action == BluetoothAdapter.ACTION_DISCOVERY_STARTED) {
                stopClassicDiscoveryIfRunning(reason = "external-discovery-started")
            }
        }
    }

    private val meshScanListener = object : BleScanCoordinator.Listener {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Gatt mesh scan failed code=$errorCode")
            stateFlow.update {
                it.copy(
                    errorMessage = R.string.gatt_mesh_error_scan_failed,
                    isScanning = false
                )
            }
            publishState()
        }
    }

    private fun createServerCallback(callbackGeneration: Long): BluetoothGattServerCallback {
        return object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (service.uuid != profile.serviceUuid) {
                    return
                }
                if (!shouldHandleLocalServerCallback(callbackGeneration, requireStartupPending = true)) {
                    Log.d(
                        TAG,
                        "Ignoring stale local gatt onServiceAdded generation=$callbackGeneration current=$activeLocalServerCallbackGeneration"
                    )
                    return
                }
                startupTimeoutJob?.cancel()
                postAddServiceRecoveryJob?.cancel()
                postAddServiceRecoveryJob = null
                localGattServerStartupFallbackJob?.cancel()
                localGattServerStartupFallbackJob = null
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    publishErrorAndStop(R.string.gatt_mesh_error_server_start_failed)
                    return
                }
                completeLocalGattServerStartup(reason = "service-added")
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    return
                }
                handleServerConnectionStateChanged(device, newState)
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    return
                }
                handleServerMtuChanged(device, mtu)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    if (responseNeeded) {
                        sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, ByteArray(0))
                    }
                    return
                }
                val status = handleServerCharacteristicWrite(
                    device = device,
                    characteristic = characteristic,
                    preparedWrite = preparedWrite,
                    offset = offset,
                    value = value
                )

                if (responseNeeded) {
                    sendGattResponse(device, requestId, status, 0, ByteArray(0))
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, ByteArray(0))
                    return
                }
                val (status, value) = handleServerCharacteristicRead(
                    device = device,
                    characteristic = characteristic,
                    offset = offset
                )
                sendGattResponse(device, requestId, status, offset, value)
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, ByteArray(0))
                    return
                }
                val (status, value) = handleServerDescriptorRead(
                    device = device,
                    descriptor = descriptor,
                    offset = offset
                )
                sendGattResponse(device, requestId, status, offset, value)
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray
            ) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    if (responseNeeded) {
                        sendGattResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, ByteArray(0))
                    }
                    return
                }
                val status = handleServerDescriptorWrite(
                    device = device,
                    descriptor = descriptor,
                    preparedWrite = preparedWrite,
                    offset = offset,
                    value = value
                )
                if (responseNeeded) {
                    sendGattResponse(device, requestId, status, 0, ByteArray(0))
                }
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                if (!shouldHandleLocalServerCallback(callbackGeneration)) {
                    return
                }
                handleServerNotificationSent(device, status)
            }
        }
    }

    private val sharedSosGattDelegate = object : GattSOSServerService.SharedGattDelegate {
        override val serviceUuid: UUID = profile.serviceUuid

        override fun createService(): BluetoothGattService = createMeshGattService()

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handleServerConnectionStateChanged(device, newState)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handleServerMtuChanged(device, mtu)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ): GattSOSServerService.SharedGattReadResult? {
            val (status, value) = handleServerCharacteristicRead(device, characteristic, offset)
            return GattSOSServerService.SharedGattReadResult(
                status = status,
                value = value
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ): Int {
            return handleServerCharacteristicWrite(
                device = device,
                characteristic = characteristic,
                preparedWrite = preparedWrite,
                offset = offset,
                value = value
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ): GattSOSServerService.SharedGattReadResult? {
            val (status, value) = handleServerDescriptorRead(device, descriptor, offset)
            return GattSOSServerService.SharedGattReadResult(
                status = status,
                value = value
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ): Int {
            return handleServerDescriptorWrite(
                device = device,
                descriptor = descriptor,
                preparedWrite = preparedWrite,
                offset = offset,
                value = value
            )
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handleServerNotificationSent(device, status)
        }
    }

    private val sharedP2pGattDelegate = object : P2pGattServerService.SharedGattDelegate {
        override val serviceUuid: UUID = profile.serviceUuid

        override fun createService(): BluetoothGattService = createMeshGattService()

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handleServerConnectionStateChanged(device, newState)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handleServerMtuChanged(device, mtu)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ): P2pGattServerService.SharedGattReadResult? {
            val (status, value) = handleServerCharacteristicRead(device, characteristic, offset)
            return P2pGattServerService.SharedGattReadResult(
                status = status,
                value = value
            )
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ): Int {
            return handleServerCharacteristicWrite(
                device = device,
                characteristic = characteristic,
                preparedWrite = preparedWrite,
                offset = offset,
                value = value
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ): P2pGattServerService.SharedGattReadResult? {
            val (status, value) = handleServerDescriptorRead(device, descriptor, offset)
            return P2pGattServerService.SharedGattReadResult(
                status = status,
                value = value
            )
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ): Int {
            return handleServerDescriptorWrite(
                device = device,
                descriptor = descriptor,
                preparedWrite = preparedWrite,
                offset = offset,
                value = value
            )
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handleServerNotificationSent(device, status)
        }
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "state-change")) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                recordClientConnectionFailure(address, status)
                Log.w(TAG, "[$address] Client connection state failed status=$status newState=$newState")
                closeClientPeer(address, expectedGatt = gatt)
                scheduleReconnectAfterClientFailure(address = address, device = gatt.device)
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) {
                        clientReconnectJobs.remove(address)?.cancel()
                        clientFailureBackoff.remove(address)
                        securityRecoveryAttemptAtMillis.remove(address)
                        clientPeers[address]?.takeIf { it.gatt === gatt }?.connected = true
                    }
                    if (!hasBluetoothConnectPermission()) {
                        closeClientPeer(address, expectedGatt = gatt)
                        return
                    }
                    val radioDecision = BleRadioPolicy.resolve(
                        context = applicationContext,
                        preferPerformance = true,
                        hasActiveTransfer = pendingOutboundPackets.isNotEmpty(),
                        connectedPeerCount = stateFlow.value.connectedPeerCount
                    )
                    try {
                        gatt.requestConnectionPriority(radioDecision.connectionPriority)
                    } catch (securityException: SecurityException) {
                        Log.w(TAG, "[$address] requestConnectionPriority blocked by permission", securityException)
                        closeClientPeer(address, expectedGatt = gatt)
                        return
                    }
                    val discoveryStarted = try {
                        gatt.discoverServices()
                    } catch (securityException: SecurityException) {
                        Log.w(TAG, "[$address] discoverServices blocked by permission", securityException)
                        closeClientPeer(address, expectedGatt = gatt)
                        return
                    }
                    if (!discoveryStarted) {
                        Log.w(TAG, "[$address] discoverServices could not be started")
                        closeClientPeer(address, expectedGatt = gatt)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeClientPeer(address, expectedGatt = gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "mtu-changed")) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "[$address] Client MTU update failed status=$status requested=$mtu")
                return
            }
            val resolvedMtu = mtu.coerceAtLeast(DEFAULT_ATT_MTU)
            synchronized(lock) {
                clientPeers[address]?.takeIf { it.gatt === gatt }?.mtu = resolvedMtu
            }
            Log.d(TAG, "[$address] Client MTU updated mtu=$resolvedMtu")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "services-discovered")) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (scheduleServiceDiscoveryRetry(address, gatt, reason = "status=$status")) {
                    return
                }
                closeClientPeer(address, expectedGatt = gatt)
                return
            }

            val discoveredServices = runCatching { gatt.services.map { it.uuid.toString() } }
                .getOrDefault(emptyList())
            val meshService = gatt.getService(profile.serviceUuid)
            if (meshService == null) {
                if (attemptClientGattCacheRefresh(address, gatt, discoveredServices)) {
                    return
                }
                if (scheduleServiceDiscoveryRetry(address, gatt, reason = "missing-service")) {
                    return
                }
                closeClientPeer(address, expectedGatt = gatt)
                return
            }

            val incoming = meshService.getCharacteristic(profile.messageInUuid)
            val outgoing = meshService.getCharacteristic(profile.messageOutUuid)
            val incomingPermissions = incoming?.permissions ?: 0
            val outgoingPermissions = outgoing?.permissions ?: 0
            val bondState = getDeviceBondStateSafely(gatt.device)
            Log.d(
                TAG,
                "[$address] Mesh service discovered inProps=${incoming?.properties} inPerm=$incomingPermissions " +
                    "outProps=${outgoing?.properties} outPerm=$outgoingPermissions bondState=$bondState"
            )
            if (incoming != null && remoteIncomingWriteLikelyRequiresBond(incomingPermissions, bondState)) {
                val recovered = maybeRecoverFromSecurityFailure(
                    address = address,
                    device = gatt.device,
                    reason = "incoming-permission-requires-bond"
                )
                if (recovered) {
                    return
                }
            }
            if (!isIncomingWriteCapable(incoming)) {
                if (scheduleServiceDiscoveryRetry(address, gatt, reason = "incoming-not-writeable")) {
                    return
                }
                closeClientPeer(address, expectedGatt = gatt)
                return
            }
            val bypassClientNotifications = synchronized(lock) {
                address in clientNotificationBypassPeers
            }
            val outgoingForNotifications = outgoing?.takeIf {
                supportsOutgoingNotifications(it)
            }
            val attemptsClientNotifications = outgoingForNotifications != null &&
                !bypassClientNotifications
            val requiresNotificationSettle = attemptsClientNotifications

            synchronized(lock) {
                val current = clientPeers[address]?.takeIf { it.gatt === gatt }
                if (current != null) {
                    current.messageIn = incoming
                    current.messageOut = outgoingForNotifications.takeIf { attemptsClientNotifications }
                    current.serviceDiscoveryRetries = 0
                    current.cacheRefreshAttempted = false
                    current.ready = !requiresNotificationSettle
                }
            }
            if (outgoingForNotifications != null) {
                if (bypassClientNotifications) {
                    Log.w(
                        TAG,
                        "[$address] Write-only fallback is active; skipping client notifications to keep command queue free"
                    )
                    cancelClientNotificationSettle(address)
                    publishState()
                    requestLocalSenderAnnouncement(address)
                    requestPeerVerification(address)
                    return
                }
                val notificationConfigured = enableClientNotifications(gatt, outgoingForNotifications)
                if (!notificationConfigured) {
                    val failureReason = if (bypassClientNotifications) {
                        "write-only fallback remains active"
                    } else {
                        "write-only mode remains active"
                    }
                    Log.w(TAG, "[$address] Failed to configure notifications; $failureReason")
                    cancelClientNotificationSettle(address)
                    if (requiresNotificationSettle) {
                        promoteClientReady(address = address, gatt = gatt, reason = "notify-setup-failed")
                    }
                } else {
                    if (requiresNotificationSettle) {
                        scheduleClientNotificationSettleFallback(address = address, gatt = gatt)
                    }
                }
            } else if (outgoing != null) {
                Log.w(TAG, "[$address] Outgoing characteristic missing notify/CCCD; continuing with write-only mode")
                cancelClientNotificationSettle(address)
            }
            publishState()
            requestLocalSenderAnnouncement(address)
            requestPeerVerification(address)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != profile.messageOutUuid) {
                return
            }
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "notify-changed")) {
                return
            }
            Log.d(
                TAG,
                "[$address] Client notification callback bytes=${value.size} api=33+"
            )
            handleIncomingPacket(
                payload = value,
                sourceAddress = address,
                transportChannel = InboundTransportChannel.CLIENT_NOTIFICATION
            )
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != profile.messageInUuid) {
                return
            }
            val address = normalizeAddress(gatt.device.address)
            val ticket = synchronized(lock) {
                clientWriteTickets[address]?.takeIf { it.gatt === gatt }
            }
            if (ticket == null && !shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "char-write")) {
                return
            }
            if (ticket == null) {
                Log.d(TAG, "[$address] Ignoring client write callback without active ticket")
                return
            }
            ticket.status = status
            ticket.latch.countDown()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                maybeRecoverFromSecurityStatus(
                    address = address,
                    device = gatt.device,
                    status = status,
                    phase = "characteristic-write"
                )
                Log.w(TAG, "[$address] Client characteristic write callback failed status=$status")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (
                descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
                descriptor.characteristic.uuid != profile.messageOutUuid
            ) {
                return
            }
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "descriptor-write")) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val remoteRequiresBondForWrite = synchronized(lock) {
                    val incomingPermissions = clientPeers[address]?.messageIn?.permissions ?: 0
                    remoteIncomingWriteLikelyRequiresBond(
                        permissions = incomingPermissions,
                        bondState = getDeviceBondStateSafely(gatt.device)
                    )
                }
                val recovered = remoteRequiresBondForWrite && maybeRecoverFromSecurityStatus(
                    address = address,
                    device = gatt.device,
                    status = status,
                    phase = "descriptor-write"
                )
                if (recovered) {
                    cancelClientNotificationSettle(address)
                    synchronized(lock) {
                        clientPeers[address]?.takeIf { it.gatt === gatt }?.ready = false
                    }
                    return
                }
                Log.w(
                    TAG,
                    "[$address] Client notification descriptor write failed status=$status; using write-only readiness"
                )
            } else {
                Log.d(TAG, "[$address] Client notification descriptor configured")
            }
            cancelClientNotificationSettle(address)
            resetInboundChunkReceiver(address, InboundTransportChannel.CLIENT_NOTIFICATION)
            promoteClientReady(address = address, gatt = gatt, reason = "descriptor-write")
        }

        @Deprecated(
            "Android 13 and above uses onCharacteristicChanged(gatt, characteristic, value). " +
                "Kept for pre-Android 13 compatibility.",
            level = DeprecationLevel.WARNING
        )
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            }
            if (characteristic.uuid != profile.messageOutUuid) {
                return
            }
            val address = normalizeAddress(gatt.device.address)
            if (!shouldHandleClientGattCallback(address = address, gatt = gatt, callback = "notify-changed-legacy")) {
                return
            }
            val value = characteristic.value ?: return
            Log.d(
                TAG,
                "[$address] Client notification callback bytes=${value.size} legacy"
            )
            handleIncomingPacket(
                payload = value,
                sourceAddress = address,
                transportChannel = InboundTransportChannel.CLIENT_NOTIFICATION
            )
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): GattMeshForegroundService = this@GattMeshForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        MeshServiceRegistry.register(profile.id, this)
        ensureForegroundStarted()
        serviceScope.launch {
            if (!isGattMeshRuntimeEnabledInSettings()) {
                stopSelfSafely()
                return@launch
            }
            pendingOutboundQueuePersistJob = serviceScope.launch {
                pendingOutboundQueuePersistEvents.collect {
                    persistPendingOutboundQueueSnapshot()
                }
            }
            ensureGattMeshGeneralContact()
            observeGattMeshNotificationSettings()
            localSenderLabel = resolveLocalSenderLabel()
            localInitiatorSalt = resolveLocalInitiatorSalt()
            localInitiatorRank = deriveLocalInitiatorRank(localInitiatorSalt)
            startMeshRuntime()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val command = when (intent?.action) {
            ACTION_STOP -> GattMeshRuntimeCommand.STOP
            ACTION_RECONCILE_SOS_MODE -> GattMeshRuntimeCommand.RECONCILE
            else -> GattMeshRuntimeCommand.START
        }
        if (command == GattMeshRuntimeCommand.STOP) {
            stopSelfSafely()
            return START_NOT_STICKY
        }
        // Guard against FGS ANR when service gets started through command-only paths
        // (e.g. reconcile) and onCreate returned early due settings/state races.
        ensureForegroundStarted()
        serviceScope.launch {
            val decision = GattMeshCommandDecider.decide(
                command = command,
                settingsEnabled = isGattMeshRuntimeEnabledInSettings(),
                runtimeActive = runtimeActive
            )
            when (decision) {
                GattMeshRuntimeDecision.STOP_SERVICE -> stopSelfSafely()
                GattMeshRuntimeDecision.START_RUNTIME -> {
                    if (isUsingDefaultSenderLabel(localSenderLabel)) {
                        localSenderLabel = resolveLocalSenderLabel()
                    }
                    if (!runtimeActive) {
                        startMeshRuntime()
                    }
                }

                GattMeshRuntimeDecision.RECONCILE_RUNTIME -> reconcileRuntimeWithSosMode()
                GattMeshRuntimeDecision.NO_OP -> Unit
            }
        }
        return START_STICKY
    }

    private fun reconcileRuntimeWithSosMode() {
        val shouldUseSharedSosServer = GattSOSServerService.isRunning.value
        if (!runtimeActive) {
            startMeshRuntime()
            return
        }
        if (shouldUseSharedSosServer == sosSharedGattServerMode) {
            return
        }
        Log.i(
            TAG,
            "Reconfiguring mesh runtime for SOS state change sharedMode=$sosSharedGattServerMode targetShared=$shouldUseSharedSosServer"
        )
        stopMeshRuntime(clearError = true)
        startMeshRuntime()
    }

    override fun onBind(intent: Intent?): IBinder {
        requestRuntimeSelfHeal(reason = "bind")
        return binder
    }

    override fun onDestroy() {
        // Persist once more on teardown so queued packets survive process/service restarts.
        persistPendingOutboundQueueSnapshot()
        pendingOutboundQueuePersistJob?.cancel()
        pendingOutboundQueuePersistJob = null
        stopMeshRuntime(clearError = true)
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        MeshServiceRegistry.unregister(profile.id, this)
        super.onDestroy()
    }

    private fun ensureForegroundStarted() {
        if (foregroundStarted) {
            return
        }
        ensureNotificationChannel()
        startForeground(
            profile.notificationId,
            GattMeshNotificationFactory.build(
                context = this,
                connectedCount = stateFlow.value.connectedPeerCount,
                notificationChannelId = profile.notificationChannelId,
                requestCode = GATT_MESH_NOTIFICATION_REQUEST_CODE,
                sessionCode = chatStore.sessionCode
            )
        )
        foregroundStarted = true
    }

    fun sendGroupMessage(
        message: String,
        messageId: String? = null
    ): GattMeshSendResult? {
        if (!runtimeActive) {
            requestRuntimeSelfHeal(reason = "send-group-message")
        }
        val text = sanitizeMessage(message)
        if (text.isEmpty()) {
            return null
        }
        val packetId = messageId
            ?.trim()
            ?.takeIf { it.matches(MESSAGE_ID_REGEX) }
            ?: UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val encryptedPayload = encryptMeshChatPayload(
            messageId = packetId,
            senderLabel = localSenderLabel,
            timestampMillis = timestamp,
            message = text
        ) ?: return null
        val packet = MeshPacket(
            id = packetId,
            senderLabel = localSenderLabel,
            timestampMillis = timestamp,
            message = text,
            type = MeshPacketType.CHAT,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V1,
            encrypted = true,
            keyId = encryptedPayload.keyId,
            encryptedIvBase64 = encryptedPayload.ivBase64,
            encryptedCipherBase64 = encryptedPayload.cipherBase64
        )
        val authenticatedPacket = runCatching {
            buildOriginAuthenticatedPacket(packet)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to attach gatt mesh origin proof to message id=$packetId", throwable)
        }.getOrNull() ?: packet
        val authenticatedPacketSize = encodePacket(authenticatedPacket).size
        val sendPacket = when {
            authenticatedPacketSize > MAX_PACKET_BYTES -> {
                if (authenticatedPacket !== packet) {
                    Log.w(
                        TAG,
                        "Falling back to unsigned gatt mesh packet id=$packetId because origin proof exceeds packet budget"
                    )
                }
                packet
            }

            authenticatedPacket !== packet &&
                shouldPreferCompactUnsignedChatPacket(authenticatedPacketSize) -> {
                Log.w(
                    TAG,
                    "Falling back to unsigned gatt mesh packet id=$packetId because signed payload bytes=$authenticatedPacketSize is too large for the current route mix"
                )
                packet
            }

            else -> authenticatedPacket
        }
        // UI local message must keep this packet ID so delivery/read receipts can match it later.
        // Do not regenerate IDs on retries; store-and-forward relies on ID stability.
        return when (sendOrQueuePacket(sendPacket, queueWhenFailed = true)) {
            DispatchOutcome.SENT -> GattMeshSendResult(
                packetId = sendPacket.id,
                disposition = GattMeshSendDisposition.SENT
            )

            DispatchOutcome.QUEUED -> GattMeshSendResult(
                packetId = sendPacket.id,
                disposition = GattMeshSendDisposition.QUEUED
            )

            DispatchOutcome.FAILED -> null
        }
    }

    fun sendReadReceipt(messageIds: Collection<String>): Boolean {
        if (!runtimeActive) {
            requestRuntimeSelfHeal(reason = "send-read-receipt")
        }
        if (!canAttemptReadReceiptTraffic()) {
            Log.d(TAG, "Skipping gatt mesh read receipts because no stable receipt route is available")
            return false
        }
        return sendReceipt(ReceiptType.READ, messageIds)
    }

    private fun sendDeliveryReceipt(messageIds: Collection<String>): Boolean {
        return sendReceipt(ReceiptType.DELIVERED, messageIds)
    }

    private fun canAttemptReadReceiptTraffic(): Boolean {
        return synchronized(lock) {
            if (!runtimeActive || pendingOutboundPackets.isNotEmpty()) {
                return@synchronized false
            }
            val candidateAddresses = linkedSetOf<String>().apply {
                addAll(serverDevices.keys)
                addAll(clientPeers.keys)
            }
            candidateAddresses.any(::hasStableControlTrafficRouteLocked)
        }
    }

    private fun sendReceipt(type: ReceiptType, messageIds: Collection<String>): Boolean {
        val normalizedIds = normalizeMessageIds(messageIds)
        if (normalizedIds.isEmpty()) {
            return false
        }
        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = RECEIPT_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.RECEIPT,
            receiptType = type,
            receiptMessageIds = normalizedIds,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V2
        )
        val sent = sendOrQueuePacket(packet, queueWhenFailed = false) == DispatchOutcome.SENT
        if (sent) {
            Log.d(
                TAG,
                "Sent receipt type=${type.name} ids=${normalizedIds.take(3)} count=${normalizedIds.size}"
            )
        }
        return sent
    }

    private fun requestPeerVerification(address: String) {
        if (!runtimeActive || address.isBlank()) {
            return
        }
        val nonce = synchronized(lock) {
            if (address in clientNotificationBypassPeers) {
                return@synchronized null
            }
            val now = System.currentTimeMillis()
            val existingVerification = peerVerificationByAddress[address]
            if (
                existingVerification != null &&
                now - existingVerification.verifiedAtMillis < PEER_VERIFICATION_CACHE_TTL_MS
            ) {
                return@synchronized null
            }
            // Verification is only useful when the peer can return the proof over a route that can
            // actually carry large control traffic. On legacy client-write links the small challenge
            // succeeds, but the proof reply corrupts transport reassembly and causes retry storms.
            if (!hasLargeControlTrafficRouteLocked(address)) {
                return@synchronized null
            }
            val lastRequestedAt = pendingPeerVerificationRequestedAtMillis[address] ?: 0L
            if (
                pendingPeerVerificationNonces.containsKey(address) &&
                now - lastRequestedAt < PEER_VERIFICATION_RETRY_INTERVAL_MS
            ) {
                return@synchronized null
            }
            pendingPeerVerificationNonces.remove(address)
            if (now - lastRequestedAt < PEER_VERIFICATION_RETRY_INTERVAL_MS) {
                return@synchronized null
            }
            val nextNonce = UUID.randomUUID().toString()
            pendingPeerVerificationNonces[address] = nextNonce
            pendingPeerVerificationRequestedAtMillis[address] = now
            nextNonce
        } ?: return

        serviceScope.launch {
            if (!awaitControlTrafficWindow(address)) {
                synchronized(lock) {
                    if (pendingPeerVerificationNonces[address] == nonce) {
                        pendingPeerVerificationNonces.remove(address)
                    }
                }
                return@launch
            }
            val sent = sendPeerVerificationChallenge(address = address, nonce = nonce)
            if (!sent) {
                synchronized(lock) {
                    if (pendingPeerVerificationNonces[address] == nonce) {
                        pendingPeerVerificationNonces.remove(address)
                    }
                }
            }
        }
    }

    private fun sendPeerVerificationChallenge(
        address: String,
        nonce: String
    ): Boolean {
        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = AUTH_CHALLENGE_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.AUTH_CHALLENGE,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V4,
            authNonce = nonce
        )
        return sendDirectPacket(packet = packet, address = address)
    }

    private fun respondToPeerVerificationChallenge(
        sourceAddress: String,
        nonce: String
    ) {
        serviceScope.launch {
            val proofPayload = try {
                roleProofCreator.createProof(sessionNonce = nonce)
            } catch (_: MissingRoleCertificateException) {
                return@launch
            } catch (throwable: Throwable) {
                Log.w(TAG, "[$sourceAddress] Unable to build gatt mesh role proof", throwable)
                return@launch
            }
            val proofJson = encodeCompactRoleProofPayload(
                proof = proofPayload,
                includeSessionNonce = false
            )
            if (proofJson.isBlank() || proofJson.length > MAX_AUTH_PROOF_JSON_LENGTH) {
                return@launch
            }
            val packet = MeshPacket(
                id = UUID.randomUUID().toString(),
                senderLabel = localSenderLabel,
                timestampMillis = System.currentTimeMillis(),
                message = AUTH_PROOF_PLACEHOLDER_MESSAGE,
                type = MeshPacketType.AUTH_PROOF,
                hop = 0,
                protocol = PACKET_PROTOCOL_VALUE_V4,
                authNonce = nonce,
                authProofJson = proofJson
            )
            val hasImmediateProofRoute = synchronized(lock) {
                hasLargeControlTrafficRouteLocked(sourceAddress)
            }
            if (hasImmediateProofRoute && sendDirectPacket(packet = packet, address = sourceAddress)) {
                return@launch
            }
            if (!awaitPeerVerificationProofWindow(sourceAddress)) {
                Log.d(
                    TAG,
                    "[$sourceAddress] Skipping auth proof reply until a stable proof route becomes available"
                )
                return@launch
            }
            sendDirectPacket(packet = packet, address = sourceAddress)
        }
    }

    private fun handlePeerVerificationProof(
        sourceAddress: String,
        nonce: String,
        proofJson: String
    ) {
        val expectedNonce = synchronized(lock) {
            pendingPeerVerificationNonces[sourceAddress]
        }
        if (expectedNonce.isNullOrBlank() || expectedNonce != nonce) {
            return
        }

        val proofPayload = decodeCompactRoleProofPayload(
            raw = proofJson,
            fallbackSessionNonce = nonce
        ) ?: run {
            Log.d(TAG, "[$sourceAddress] Dropped invalid compact gatt mesh role proof payload")
            return
        }

        val verificationResult = runCatching {
            roleProofVerifier.verifyProofPayload(
                proof = proofPayload,
                expectedSessionNonce = expectedNonce
            )
        }.getOrElse { throwable ->
            RoleProofVerificationResult.Failure(
                reason = throwable.message ?: "verification failed",
                cause = throwable
            )
        }

        when (verificationResult) {
            is RoleProofVerificationResult.Success -> {
                val verifiedRole = resolveVerifiedRole(verificationResult.proof)
                if (verifiedRole == null) {
                    synchronized(lock) {
                        pendingPeerVerificationNonces.remove(sourceAddress)
                        pendingPeerVerificationRequestedAtMillis[sourceAddress] = 0L
                        peerVerificationByAddress.remove(sourceAddress)
                    }
                    publishState()
                    return
                }
                val verifiedAtMillis = System.currentTimeMillis()
                val changed = synchronized(lock) {
                    pendingPeerVerificationNonces.remove(sourceAddress)
                    val previous = peerVerificationByAddress[sourceAddress]
                    val next = PeerVerificationState(
                        role = verifiedRole,
                        verifiedAtMillis = verifiedAtMillis
                    )
                    if (previous == next) {
                        false
                    } else {
                        peerVerificationByAddress[sourceAddress] = next
                        true
                    }
                }
                if (changed) {
                    publishState()
                }
                backfillVerifiedDirectPeerMessages(
                    sourceAddress = sourceAddress,
                    verifiedRole = verifiedRole,
                    verifiedAtMillis = verifiedAtMillis
                )
            }

            is RoleProofVerificationResult.Failure -> {
                Log.w(
                    TAG,
                    "[$sourceAddress] Gatt mesh peer verification rejected: ${verificationResult.reason}",
                    verificationResult.cause
                )
                val changed = synchronized(lock) {
                    pendingPeerVerificationNonces.remove(sourceAddress)
                    pendingPeerVerificationRequestedAtMillis[sourceAddress] = 0L
                    peerVerificationByAddress.remove(sourceAddress) != null
                }
                if (changed) {
                    publishState()
                }
            }
        }
    }

    private fun resolveVerifiedRole(proof: RoleProofPayload): String? {
        val certificateBytes = runCatching {
            Base64.decode(proof.certificate, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull() ?: return null
        return RoleCertificate.fromStorageBytes(certificateBytes)?.role
    }

    private fun updateRemoteChatMessageMetadata(
        packet: MeshPacket,
        sourceAddress: String,
        originVerification: MessageOriginVerification?
    ) {
        chatStore.updateRemoteMessageMetadata(
            messageId = packet.id,
            senderLabel = packet.senderLabel,
            sourceAddress = sourceAddress,
            originVerifiedRole = originVerification?.role,
            originVerifiedAtMillis = originVerification?.verifiedAtMillis
        )
        serviceScope.launch {
            runCatching {
                updateRemoteMessageMetadata(
                    context = applicationContext,
                    uuid = packet.id,
                    senderDisplayName = packet.senderLabel,
                    senderAddress = sourceAddress,
                    originVerifiedRole = originVerification?.role,
                    originVerifiedAtMillis = originVerification?.verifiedAtMillis
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to update gatt mesh remote metadata id=${packet.id}", throwable)
            }
        }
    }

    private fun backfillVerifiedDirectPeerMessages(
        sourceAddress: String,
        verifiedRole: String,
        verifiedAtMillis: Long
    ) {
        val normalizedSourceAddress = sourceAddress.trim()
        if (normalizedSourceAddress.isEmpty()) {
            return
        }
        val directPeerLabels = synchronized(lock) {
            linkedSetOf<String>().apply {
                add(peerSenderLabels[normalizedSourceAddress].orEmpty())
                add(resolveConnectedPeerDisplayNameLocked(normalizedSourceAddress))
            }.mapNotNull { label ->
                label.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.lowercase(Locale.US)
            }.toSet()
        }
        if (directPeerLabels.isEmpty()) {
            return
        }
        val targetMessages = chatStore.currentMessages().filter { message ->
            !message.isLocal &&
                message.originVerifiedAtMillis == null &&
                message.sourceAddress?.equals(normalizedSourceAddress, ignoreCase = true) == true &&
                message.senderLabel
                    ?.trim()
                    ?.lowercase(Locale.US) in directPeerLabels
        }
        if (targetMessages.isEmpty()) {
            return
        }
        targetMessages.forEach { message ->
            chatStore.updateRemoteMessageMetadata(
                messageId = message.id,
                senderLabel = null,
                sourceAddress = normalizedSourceAddress,
                originVerifiedRole = verifiedRole,
                originVerifiedAtMillis = verifiedAtMillis
            )
        }
        serviceScope.launch {
            targetMessages.forEach { message ->
                runCatching {
                    updateRemoteMessageMetadata(
                        context = applicationContext,
                        uuid = message.id,
                        senderAddress = normalizedSourceAddress,
                        originVerifiedRole = verifiedRole,
                        originVerifiedAtMillis = verifiedAtMillis
                    )
                }.onFailure { throwable ->
                    Log.w(
                        TAG,
                        "Unable to backfill gatt mesh verified snapshot id=${message.id}",
                        throwable
                    )
                }
            }
        }
    }

    private fun buildOriginAuthenticatedPacket(packet: MeshPacket): MeshPacket {
        if (packet.type != MeshPacketType.CHAT) {
            return packet
        }
        val originAuth = runBlocking(Dispatchers.IO) {
            createMessageOriginAuth(packet)
        } ?: return packet
        return packet.copy(
            originProofJson = originAuth.proofJson,
            originSignatureBase64 = originAuth.signatureBase64
        )
    }

    private fun shouldPreferCompactUnsignedChatPacket(packetSizeBytes: Int): Boolean {
        if (packetSizeBytes <= FRAGILE_ROUTE_MAX_AUTHENTICATED_CHAT_PACKET_BYTES) {
            return false
        }
        return synchronized(lock) {
            val candidateAddresses = linkedSetOf<String>().apply {
                addAll(discoveredDevices.keys)
                addAll(serverDevices.keys)
                addAll(clientPeers.keys)
            }
            candidateAddresses.isNotEmpty() &&
                candidateAddresses.any { address ->
                    !hasLargeControlTrafficRouteLocked(address)
                }
        }
    }

    private fun adaptChatPacketForCurrentRouteBudget(packet: MeshPacket): MeshPacket {
        if (
            packet.type != MeshPacketType.CHAT ||
            packet.originProofJson.isNullOrBlank() ||
            packet.originSignatureBase64.isNullOrBlank()
        ) {
            return packet
        }
        val encodedSize = encodePacket(packet).size
        if (!shouldPreferCompactUnsignedChatPacket(encodedSize)) {
            return packet
        }
        Log.w(
            TAG,
            "Downgrading authenticated gatt mesh chat packet id=${packet.id} bytes=$encodedSize to compact unsigned form for fragile route budget"
        )
        return packet.copy(
            originProofJson = null,
            originSignatureBase64 = null
        )
    }

    private fun adaptChatPacketForAddressRoute(
        packet: MeshPacket,
        address: String
    ): MeshPacket {
        val globallyAdapted = adaptChatPacketForCurrentRouteBudget(packet)
        if (
            globallyAdapted.type != MeshPacketType.CHAT ||
            globallyAdapted.hop != 0 ||
            globallyAdapted.originProofJson.isNullOrBlank() ||
            globallyAdapted.originSignatureBase64.isNullOrBlank()
        ) {
            return globallyAdapted
        }
        val shouldPreferDirectVerificationSnapshot = synchronized(lock) {
            val hasServerNotifyRoute = serverNotifyEnabled[address] == true
            val hasReadyClientRoute = clientPeers[address]?.let { peer ->
                peer.ready && peer.connected && peer.gatt != null && peer.messageIn != null
            } == true
            hasReadyClientRoute &&
                !hasServerNotifyRoute &&
                shouldUseConservativeClientPacing(address)
        }
        if (!shouldPreferDirectVerificationSnapshot) {
            return globallyAdapted
        }
        val encodedSize = encodePacket(globallyAdapted).size
        Log.w(
            TAG,
            "[$address] Downgrading direct authenticated gatt mesh chat packet id=${packet.id} bytes=$encodedSize to direct-verification form for legacy client-write route"
        )
        return globallyAdapted.copy(
            originProofJson = null,
            originSignatureBase64 = null
        )
    }

    private suspend fun createMessageOriginAuth(packet: MeshPacket): MessageOriginAuth? {
        if (packet.type != MeshPacketType.CHAT) {
            return null
        }
        val now = System.currentTimeMillis()
        val deviceIdentity = securityRepository.getOrCreateDeviceIdentity()
        val certificateBytes = securityRepository.getStoredCertificate(allowExpired = false) ?: return null
        val roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) ?: return null
        if (!roleCertificate.isUsableAt(nowMillis = now, allowOfflineGrace = false)) {
            return null
        }

        val devicePublicKeyBase64 = Base64.encodeToString(deviceIdentity.public.encoded, Base64.NO_WRAP)
        val certificateBase64 = Base64.encodeToString(certificateBytes, Base64.NO_WRAP)
        val proofSignature = Crypto.signData(
            privateKey = deviceIdentity.private,
            data = RoleProofPayload.signaturePayload(
                publicKey = devicePublicKeyBase64,
                certificate = certificateBase64,
                timestamp = now,
                sessionNonce = packet.id,
                allowExpiredCertificate = false
            )
        )
        val proof = RoleProofPayload(
            devicePublicKey = devicePublicKeyBase64,
            certificate = certificateBase64,
            timestamp = now,
            signature = Base64.encodeToString(proofSignature, Base64.NO_WRAP),
            sessionNonce = packet.id,
            allowExpiredCertificate = false
        )
        val proofJson = encodeCompactRoleProofPayload(
            proof = proof,
            includeSessionNonce = false
        )
        if (proofJson.length > MAX_ORIGIN_PROOF_JSON_LENGTH) {
            return null
        }

        val originSignatureBase64 = Base64.encodeToString(
            Crypto.signData(
                privateKey = deviceIdentity.private,
                data = buildMessageOriginSignaturePayload(packet)
            ),
            Base64.NO_WRAP
        )
        if (originSignatureBase64.length > MAX_ORIGIN_SIGNATURE_LENGTH) {
            return null
        }
        return MessageOriginAuth(
            proofJson = proofJson,
            signatureBase64 = originSignatureBase64
        )
    }

    private fun verifyMessageOrigin(packet: MeshPacket): MessageOriginVerification? {
        if (packet.type != MeshPacketType.CHAT) {
            return null
        }
        val proofJson = packet.originProofJson?.trim().orEmpty()
        val signatureBase64 = packet.originSignatureBase64?.trim().orEmpty()
        if (proofJson.isEmpty() || signatureBase64.isEmpty()) {
            return null
        }
        val proofPayload = decodeCompactRoleProofPayload(
            raw = proofJson,
            fallbackSessionNonce = packet.id
        ) ?: return null
        val verificationResult = runCatching {
            messageOriginRoleProofVerifier.verifyProofPayload(
                proof = proofPayload,
                expectedSessionNonce = packet.id
            )
        }.getOrElse { throwable ->
            RoleProofVerificationResult.Failure(
                reason = throwable.message ?: "origin proof verification failed",
                cause = throwable
            )
        }
        if (verificationResult !is RoleProofVerificationResult.Success) {
            if (verificationResult is RoleProofVerificationResult.Failure) {
                Log.d(TAG, "Dropped gatt mesh origin badge for packet id=${packet.id}: ${verificationResult.reason}")
            }
            return null
        }

        val verifiedRole = resolveVerifiedRole(verificationResult.proof) ?: return null
        val signerPublicKey = decodeEcPublicKeyBase64(verificationResult.proof.devicePublicKey) ?: return null
        val signatureBytes = decodeBase64Value(signatureBase64) ?: return null
        val signatureValid = runCatching {
            Crypto.verifySignature(
                publicKey = signerPublicKey,
                data = buildMessageOriginSignaturePayload(packet),
                signatureBytes = signatureBytes
            )
        }.getOrDefault(false)
        if (!signatureValid) {
            Log.d(TAG, "Dropped gatt mesh origin badge for packet id=${packet.id}: signature mismatch")
            return null
        }

        return MessageOriginVerification(
            role = verifiedRole,
            verifiedAtMillis = System.currentTimeMillis()
        )
    }

    private fun encodeCompactRoleProofPayload(
        proof: RoleProofPayload,
        includeSessionNonce: Boolean
    ): String {
        return JSONObject().apply {
            put(COMPACT_ROLE_PROOF_PUBLIC_KEY_FIELD, proof.devicePublicKey)
            put(COMPACT_ROLE_PROOF_CERTIFICATE_FIELD, proof.certificate)
            put(COMPACT_ROLE_PROOF_TIMESTAMP_FIELD, proof.timestamp)
            put(COMPACT_ROLE_PROOF_SIGNATURE_FIELD, proof.signature)
            if (includeSessionNonce) {
                proof.sessionNonce?.trim()?.takeIf { it.isNotEmpty() }?.let { nonce ->
                    put(COMPACT_ROLE_PROOF_NONCE_FIELD, nonce)
                }
            }
            if (proof.allowExpiredCertificate) {
                put(COMPACT_ROLE_PROOF_ALLOW_EXPIRED_FIELD, true)
            }
        }.toString()
    }

    private fun decodeCompactRoleProofPayload(
        raw: String,
        fallbackSessionNonce: String? = null
    ): RoleProofPayload? {
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            return null
        }
        return runCatching {
            val json = JSONObject(normalized)
            val publicKey = json.optString(COMPACT_ROLE_PROOF_PUBLIC_KEY_FIELD)
                .trim()
                .ifBlank {
                    json.optString(ROLE_PROOF_PUBLIC_KEY_FIELD).trim()
                }
            val certificate = json.optString(COMPACT_ROLE_PROOF_CERTIFICATE_FIELD)
                .trim()
                .ifBlank {
                    json.optString(ROLE_PROOF_CERTIFICATE_FIELD).trim()
                }
            val timestamp = when {
                json.has(COMPACT_ROLE_PROOF_TIMESTAMP_FIELD) -> json.optLong(COMPACT_ROLE_PROOF_TIMESTAMP_FIELD)
                else -> json.optLong(ROLE_PROOF_TIMESTAMP_FIELD)
            }
            val signature = json.optString(COMPACT_ROLE_PROOF_SIGNATURE_FIELD)
                .trim()
                .ifBlank {
                    json.optString(ROLE_PROOF_SIGNATURE_FIELD).trim()
                }
            val nonce = json.optString(COMPACT_ROLE_PROOF_NONCE_FIELD)
                .trim()
                .ifBlank {
                    json.optString(ROLE_PROOF_NONCE_FIELD).trim()
                }
                .ifBlank {
                    fallbackSessionNonce?.trim().orEmpty()
                }
                .ifBlank { null }
            val allowExpiredCertificate = when {
                json.has(COMPACT_ROLE_PROOF_ALLOW_EXPIRED_FIELD) -> json.optBoolean(COMPACT_ROLE_PROOF_ALLOW_EXPIRED_FIELD)
                else -> json.optBoolean(ROLE_PROOF_ALLOW_EXPIRED_FIELD, false)
            }
            if (publicKey.isBlank() || certificate.isBlank() || timestamp <= 0L || signature.isBlank()) {
                return@runCatching null
            }
            RoleProofPayload(
                devicePublicKey = publicKey,
                certificate = certificate,
                timestamp = timestamp,
                signature = signature,
                sessionNonce = nonce,
                allowExpiredCertificate = allowExpiredCertificate
            )
        }.getOrNull()
    }

    private fun resolveMessageOriginVerification(
        packet: MeshPacket,
        sourceAddress: String
    ): MessageOriginVerification? {
        val verifiedOrigin = verifyMessageOrigin(packet)
        if (verifiedOrigin != null) {
            return verifiedOrigin
        }
        val hasEmbeddedOriginProof = !packet.originProofJson.isNullOrBlank() ||
            !packet.originSignatureBase64.isNullOrBlank()
        if (hasEmbeddedOriginProof || packet.hop != 0) {
            return null
        }
        val directPeerVerification = synchronized(lock) {
            peerVerificationByAddress[sourceAddress]
        } ?: return null
        return MessageOriginVerification(
            role = directPeerVerification.role,
            verifiedAtMillis = directPeerVerification.verifiedAtMillis
        )
    }

    private fun resolveReceiptTrackingRecipientLabel(
        packet: MeshPacket,
        sourceAddress: String
    ): String? {
        if (packet.hop == 0) {
            val directPeerLabel = resolveConnectedPeerDisplayName(sourceAddress)
                .trim()
                .takeIf { it.isNotEmpty() }
            if (directPeerLabel != null) {
                return directPeerLabel
            }
        }
        return packet.senderLabel
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun buildMessageOriginSignaturePayload(packet: MeshPacket): ByteArray {
        val canonical = buildString(512) {
            append("gattmesh-origin-chat")
            append('|')
            append(packet.id)
            append('|')
            append(packet.timestampMillis)
            append('|')
            append(packet.senderLabel)
            append('|')
            append(packet.protocol)
            append('|')
            append(packet.type.wireValue)
            append('|')
            append(if (packet.encrypted) 1 else 0)
            append('|')
            append(packet.keyId ?: "")
            append('|')
            if (packet.encrypted) {
                append(packet.encryptedIvBase64 ?: "")
                append('|')
                append(packet.encryptedCipherBase64 ?: "")
            } else {
                append(packet.message)
            }
        }
        return canonical.toByteArray(UTF_8)
    }

    private fun decodeBase64Value(value: String): ByteArray? {
        return runCatching {
            Base64.decode(value.trim(), Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeEcPublicKeyBase64(base64Value: String): PublicKey? {
        val encoded = decodeBase64Value(base64Value) ?: return null
        val spec = X509EncodedKeySpec(encoded)
        return runCatching {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }.getOrNull()
    }

    private fun maybeAnnounceLocalSenderLabel(address: String) {
        if (!runtimeActive || address.isBlank()) {
            return
        }
        val shouldAttempt = synchronized(lock) {
            if (identityAnnouncementSentForPeers.contains(address)) {
                return@synchronized false
            }
            hasStableControlTrafficRouteLocked(address)
        }
        if (!shouldAttempt) {
            return
        }
        val announced = sendReceipt(
            type = ReceiptType.DELIVERED,
            messageIds = listOf(IDENTITY_ANNOUNCEMENT_RECEIPT_ID)
        )
        if (!announced) {
            return
        }
        synchronized(lock) {
            identityAnnouncementSentForPeers += address
        }
    }

    private fun requestLocalSenderAnnouncement(address: String) {
        if (!runtimeActive || address.isBlank()) {
            return
        }
        val shouldQueue = synchronized(lock) {
            if (identityAnnouncementSentForPeers.contains(address)) {
                return@synchronized false
            }
            identityAnnouncementPendingForPeers.add(address)
        }
        if (!shouldQueue) {
            return
        }
        serviceScope.launch {
            try {
                if (!awaitControlTrafficWindow(address)) {
                    return@launch
                }
                maybeAnnounceLocalSenderLabel(address)
            } finally {
                synchronized(lock) {
                    identityAnnouncementPendingForPeers.remove(address)
                }
            }
        }
    }

    private suspend fun awaitControlTrafficWindow(address: String): Boolean {
        if (!runtimeActive) {
            return false
        }
        repeat(CONTROL_TRAFFIC_WINDOW_RETRY_COUNT) { attempt ->
            val delayMillis = when {
                attempt == 0 -> controlTrafficInitialDelayMillis()
                else -> controlTrafficRetryDelayMillis()
            }
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            val readyForControlTraffic = synchronized(lock) {
                if (!runtimeActive) {
                    return@synchronized false
                }
                hasStableControlTrafficRouteLocked(address) && pendingOutboundPackets.isEmpty()
            }
            if (readyForControlTraffic) {
                return true
            }
        }
        return synchronized(lock) {
            runtimeActive &&
                hasStableControlTrafficRouteLocked(address) &&
                pendingOutboundPackets.isEmpty()
        }
    }

    private suspend fun awaitLargeControlTrafficWindow(address: String): Boolean {
        if (!runtimeActive) {
            return false
        }
        repeat(CONTROL_TRAFFIC_WINDOW_RETRY_COUNT) { attempt ->
            val delayMillis = when {
                attempt == 0 -> controlTrafficInitialDelayMillis()
                else -> controlTrafficRetryDelayMillis()
            }
            if (delayMillis > 0L) {
                delay(delayMillis)
            }
            val readyForLargeControlTraffic = synchronized(lock) {
                if (!runtimeActive) {
                    return@synchronized false
                }
                val hasLargeControlRoute = hasLargeControlTrafficRouteLocked(address)
                if (!hasLargeControlRoute) {
                    return@synchronized false
                }
                pendingOutboundPackets.isEmpty() || serverNotifyEnabled[address] == true
            }
            if (readyForLargeControlTraffic) {
                return true
            }
        }
        return synchronized(lock) {
            if (!runtimeActive) {
                return@synchronized false
            }
            val hasLargeControlRoute = hasLargeControlTrafficRouteLocked(address)
            hasLargeControlRoute &&
                (pendingOutboundPackets.isEmpty() || serverNotifyEnabled[address] == true)
        }
    }

    private suspend fun awaitPeerVerificationProofWindow(address: String): Boolean {
        return awaitLargeControlTrafficWindow(address)
    }

    // Must be called with lock held.
    private fun hasStableControlTrafficRouteLocked(address: String): Boolean {
        if (serverNotifyEnabled[address] == true) {
            return true
        }
        return clientPeers[address]?.let { peer ->
            peer.ready &&
                peer.connected &&
                peer.gatt != null &&
                peer.messageIn != null &&
                peer.messageOut != null &&
                address !in clientNotificationBypassPeers
        } == true
    }

    // Must be called with lock held.
    private fun hasLargeControlTrafficRouteLocked(address: String): Boolean {
        if (serverNotifyEnabled[address] == true) {
            return true
        }
        return clientPeers[address]?.let { peer ->
            peer.ready &&
                peer.connected &&
                peer.gatt != null &&
                peer.messageIn != null &&
                peer.messageOut != null &&
                address !in clientNotificationBypassPeers &&
                !shouldUseConservativeClientPacing(address)
        } == true
    }

    private fun sendOrQueuePacket(
        packet: MeshPacket,
        queueWhenFailed: Boolean
    ): DispatchOutcome {
        val dispatchPacket = adaptChatPacketForCurrentRouteBudget(packet)
        // Immediate relay first; if no route is ready, queue only chat packets for later flush.
        val sentAddresses = LinkedHashSet<String>()
        val sentAny = relayPacket(
            packet = dispatchPacket,
            excludeAddress = null,
            onAddressSent = { address -> sentAddresses += address }
        )
        if (sentAny) {
            if (dispatchPacket.type == MeshPacketType.CHAT) {
                chatStore.updateLocalMessageStatus(
                    dispatchPacket.id,
                    MeshMessageStatus.SENT
                )
                recordLocalChatDispatch(dispatchPacket.id, sentAddresses)
                persistLocalChatPacketState(
                    packet = dispatchPacket,
                    status = MessageDeliveryStatus.SENT,
                    outboundAddresses = sentAddresses
                )
            }
            rememberMessageId(dispatchPacket.id, dispatchPacket.timestampMillis)
            var removedQueuedPacket = false
            synchronized(lock) {
                removedQueuedPacket = pendingOutboundPackets.remove(dispatchPacket.id) != null
                pendingOutboundRetryStates.remove(dispatchPacket.id)
            }
            if (removedQueuedPacket) {
                requestPersistPendingOutboundQueue()
            }
            return DispatchOutcome.SENT
        }
        if (!queueWhenFailed) {
            return DispatchOutcome.FAILED
        }
        enqueuePendingOutboundPacket(dispatchPacket)
        if (dispatchPacket.type == MeshPacketType.CHAT) {
            chatStore.updateLocalMessageStatus(
                dispatchPacket.id,
                MeshMessageStatus.QUEUED
            )
            persistLocalChatPacketState(
                packet = dispatchPacket,
                status = MessageDeliveryStatus.QUEUED,
                outboundAddresses = emptyList()
            )
        }
        // If packet was queued, proactively try to open outbound links for inbound-only peers.
        // This shortens one-way periods where a node can receive but cannot send yet.
        triggerOutboundFallbackForNonReadyInboundPeers()
        // If there is no active route but we still discover peers, force a user-driven reconnect attempt.
        triggerOutboundConnectForQueuedPayload()
        val snapshot = synchronized(lock) {
            val serverSet = connectedInboundMeshAddressesLocked()
            val clientConnectedSet = clientPeers.values
                .filter { it.connected && it.gatt != null }
                .map { it.address }
                .toSet()
            val clientReadySet = clientPeers.values
                .filter { it.ready && it.connected && it.gatt != null && it.messageIn != null }
                .map { it.address }
                .toSet()
            val serverReadySet = serverSet.filter { serverNotifyEnabled[it] == true }.toSet()
            SendRouteSnapshot(
                connected = (serverSet + clientConnectedSet).size,
                ready = (clientReadySet + serverReadySet).size,
                discovered = discoveredPeers.size
            )
        }
        Log.w(
            TAG,
            "Queued outbound packet id=${dispatchPacket.id} connected=${snapshot.connected} ready=${snapshot.ready} discovered=${snapshot.discovered}"
        )
        if (snapshot.ready > 0) {
            requestFlushPendingOutboundPackets()
        }
        return DispatchOutcome.QUEUED
    }

    @SuppressLint("MissingPermission")
    private fun triggerOutboundFallbackForNonReadyInboundPeers() {
        if (!runtimeActive || !hasBluetoothConnectPermission()) {
            return
        }
        val now = System.currentTimeMillis()
        val candidates = synchronized(lock) {
            val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
            inboundMeshServerAddressesLocked().mapNotNull { address ->
                val device = serverDevices[address] ?: return@mapNotNull null
                val hasOutbound = hasUsableOrPendingOutboundClientRouteLocked(
                    address = address,
                    now = now,
                    systemSnapshot = systemSnapshot,
                    queuedRecovery = true
                )
                val inboundNotifyEnabled = serverNotifyEnabled[address] == true
                val inboundConnectedAt = serverConnectedAt[address] ?: now
                val inboundGraceElapsed = now - inboundConnectedAt >= INBOUND_NOTIFY_GRACE_MS
                if (hasOutbound || inboundNotifyEnabled || !inboundGraceElapsed) {
                    null
                } else {
                    address to device
                }
            }
        }
        if (candidates.isEmpty()) {
            return
        }
        candidates.forEach { (address, device) ->
            if (!shouldInitiateConnection(address)) {
                return@forEach
            }
            connectToPeer(
                device = device,
                address = address,
                allowFailureBackoffBypass = true
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun triggerOutboundConnectForQueuedPayload() {
        if (!runtimeActive || !hasBluetoothConnectPermission()) {
            return
        }
        val now = System.currentTimeMillis()
        val knownPeerAddresses = BleKnownPeersStore.knownAddresses(applicationContext)
        val candidateAddresses = synchronized(lock) {
            if (pendingOutboundPackets.isEmpty()) {
                return@synchronized emptyList<String>()
            }
            val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
            val inboundMeshServerAddresses = inboundMeshServerAddressesLocked()
            buildSet {
                addAll(discoveredDevices.keys)
                addAll(serverDevices.keys)
                addAll(sharedModeObservedInboundConnectedAt.keys)
                addAll(clientPeers.keys)
                if (systemSnapshot != null) {
                    addAll(systemSnapshot)
                }
                addAll(knownPeerAddresses)
            }.mapNotNull { address ->
                val hasOutbound = hasUsableOrPendingOutboundClientRouteLocked(
                    address = address,
                    now = now,
                    systemSnapshot = systemSnapshot,
                    queuedRecovery = true
                )
                val inboundReady = address in inboundMeshServerAddresses &&
                    serverNotifyEnabled[address] == true
                if (hasOutbound || inboundReady) {
                    null
                } else {
                    address
                }
            }.sorted()
        }
        if (candidateAddresses.isEmpty()) {
            return
        }
        candidateAddresses.forEach { address ->
            val device = resolveQueuedOutboundRecoveryDevice(address) ?: return@forEach
            val queuedConnectMode = synchronized(lock) {
                val backoff = clientFailureBackoff[address]
                when {
                    backoff == null -> 1
                    now >= backoff.nextAllowedConnectAtMillis -> 0
                    clientReconnectJobs[address]?.isActive == true -> -1
                    else -> -1
                }
            }
            if (queuedConnectMode < 0) {
                return@forEach
            }
            val allowFailureBackoffBypass = queuedConnectMode > 0
            if (!shouldInitiateConnection(address, queuedPayloadHandoverAllowed = true)) {
                return@forEach
            }
            connectToPeer(
                device = device,
                address = address,
                // Fresh user traffic can bypass the first conservative backoff, but once a
                // reconnect job is already scheduled we stop forcing parallel attempts.
                allowFailureBackoffBypass = allowFailureBackoffBypass
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveQueuedOutboundRecoveryDevice(address: String): BluetoothDevice? {
        val normalizedAddress = normalizeAddress(address)
        synchronized(lock) {
            discoveredDevices[normalizedAddress]?.let { return it }
            serverDevices[normalizedAddress]?.let { return it }
            clientPeers[normalizedAddress]?.gatt?.device?.let { return it }
        }
        val adapter = bluetoothAdapter ?: return null
        if (!BluetoothAdapter.checkBluetoothAddress(normalizedAddress)) {
            return null
        }
        return runCatching {
            adapter.getRemoteDevice(normalizedAddress)
        }.getOrNull()
    }

    private fun enqueuePendingOutboundPacket(packet: MeshPacket) {
        synchronized(lock) {
            pendingOutboundPackets[packet.id] = packet
            pendingOutboundRetryStates.remove(packet.id)
            while (pendingOutboundPackets.size > MAX_PENDING_OUTBOUND_PACKETS) {
                val oldestKey = pendingOutboundPackets.keys.firstOrNull() ?: break
                pendingOutboundPackets.remove(oldestKey)
                pendingOutboundRetryStates.remove(oldestKey)
            }
        }
        requestPersistPendingOutboundQueue()
        requestPendingOutboundRecovery(reason = "queued-packet")
    }

    private fun requestFlushPendingOutboundPackets(maxPackets: Int = MAX_PENDING_FLUSH_BATCH) {
        if (!runtimeActive) {
            return
        }
        if (pendingOutboundFlushJob?.isActive == true) {
            return
        }
        pendingOutboundFlushJob = serviceScope.launch {
            while (runtimeActive) {
                val removedAny = flushPendingOutboundPackets(maxPackets)
                val shouldContinue = synchronized(lock) {
                    removedAny && pendingOutboundPackets.isNotEmpty()
                }
                if (!shouldContinue) {
                    break
                }
            }
        }
    }

    private fun flushPendingOutboundPackets(maxPackets: Int = MAX_PENDING_FLUSH_BATCH): Boolean {
        if (!runtimeActive) {
            return false
        }
        val now = System.currentTimeMillis()
        val candidates = synchronized(lock) {
            pendingOutboundPackets.values
                .toList()
                .filter { packet ->
                    val nextEligibleAtMillis =
                        pendingOutboundRetryStates[packet.id]?.nextEligibleAtMillis ?: 0L
                    nextEligibleAtMillis <= now
                }
                // Prioritize the newest user traffic first so stale restored backlog cannot
                // monopolize a fresh link and delay the latest message for minutes.
                .sortedWith(compareByDescending<MeshPacket> { it.timestampMillis }.thenBy { it.id })
                .take(maxPackets)
        }
        if (candidates.isEmpty()) {
            return false
        }
        var removedAny = false
        for (packet in candidates) {
            val dispatchPacket = adaptChatPacketForCurrentRouteBudget(packet)
            if (dispatchPacket !== packet) {
                synchronized(lock) {
                    pendingOutboundPackets[packet.id] = dispatchPacket
                }
                requestPersistPendingOutboundQueue()
            }
            val sentAddresses = LinkedHashSet<String>()
            val sent = relayPacket(
                packet = dispatchPacket,
                excludeAddress = null,
                onAddressSent = { address -> sentAddresses += address }
            )
            if (sent) {
                if (dispatchPacket.type == MeshPacketType.CHAT) {
                    chatStore.updateLocalMessageStatus(
                        dispatchPacket.id,
                        MeshMessageStatus.SENT
                    )
                    recordLocalChatDispatch(dispatchPacket.id, sentAddresses)
                    persistLocalChatPacketState(
                        packet = dispatchPacket,
                        status = MessageDeliveryStatus.SENT,
                        outboundAddresses = sentAddresses
                    )
                }
                rememberMessageId(dispatchPacket.id, dispatchPacket.timestampMillis)
                synchronized(lock) {
                    removedAny = pendingOutboundPackets.remove(dispatchPacket.id) != null || removedAny
                    pendingOutboundRetryStates.remove(dispatchPacket.id)
                }
            } else {
                val evictedStalePacket = handlePendingOutboundFlushFailure(dispatchPacket)
                removedAny = evictedStalePacket || removedAny
                if (!evictedStalePacket) {
                    // A healthy route would normally drain multiple packets in one pass.
                    // If the latest packet already failed, stop here so the recovery loop can
                    // reconnect instead of burning seconds on the rest of the backlog.
                    break
                }
            }
        }
        if (removedAny) {
            requestPersistPendingOutboundQueue()
        }
        return removedAny
    }

    private fun handlePendingOutboundFlushFailure(packet: MeshPacket): Boolean {
        if (packet.type != MeshPacketType.CHAT) {
            return false
        }
        val now = System.currentTimeMillis()
        val packetAgeMillis = now - packet.timestampMillis
        if (packetAgeMillis >= MAX_PENDING_OUTBOUND_PACKET_AGE_MS) {
            val removed = synchronized(lock) {
                val removedPacket = pendingOutboundPackets.remove(packet.id) != null
                pendingOutboundRetryStates.remove(packet.id)
                removedPacket
            }
            if (removed) {
                Log.w(
                    TAG,
                    "Dropping stale pending outbound packet id=${packet.id} ageMs=$packetAgeMillis"
                )
                chatStore.updateLocalMessageStatus(
                    packet.id,
                    MeshMessageStatus.FAILED
                )
                persistLocalChatPacketState(
                    packet = packet,
                    status = MessageDeliveryStatus.FAILED,
                    outboundAddresses = emptyList()
                )
            }
            return removed
        }
        var failureCount = 0
        var backoffMillis = 0L
        synchronized(lock) {
            val queued = pendingOutboundPackets.remove(packet.id) ?: return false
            pendingOutboundPackets[packet.id] = queued
            val previousState = pendingOutboundRetryStates[packet.id]
            failureCount = (previousState?.failureCount ?: 0) + 1
            backoffMillis = pendingOutboundRetryBackoffMillis(failureCount)
            pendingOutboundRetryStates[packet.id] = PendingOutboundRetryState(
                failureCount = failureCount,
                nextEligibleAtMillis = now + backoffMillis
            )
        }
        Log.d(
            TAG,
            "Deferring pending outbound packet id=${packet.id} failureCount=$failureCount backoffMs=$backoffMillis"
        )
        return false
    }

    private fun requestPendingOutboundRecovery(reason: String) {
        if (!runtimeActive) {
            return
        }
        val hasPending = synchronized(lock) {
            pendingOutboundPackets.isNotEmpty()
        }
        if (!hasPending || pendingOutboundRecoveryJob?.isActive == true) {
            return
        }
        pendingOutboundRecoveryJob = serviceScope.launch {
            Log.d(TAG, "Pending outbound recovery started reason=$reason")
            while (runtimeActive) {
                val stillPending = synchronized(lock) {
                    pendingOutboundPackets.isNotEmpty()
                }
                if (!stillPending) {
                    break
                }
                triggerOutboundFallbackForNonReadyInboundPeers()
                triggerOutboundConnectForQueuedPayload()
                requestFlushPendingOutboundPackets()
                delay(PENDING_OUTBOUND_RECOVERY_INTERVAL_MS)
            }
            Log.d(TAG, "Pending outbound recovery stopped")
        }
    }

    private fun requestPersistPendingOutboundQueue() {
        if (!pendingOutboundQueuePersistEvents.tryEmit(Unit)) {
            serviceScope.launch {
                pendingOutboundQueuePersistEvents.emit(Unit)
            }
        }
    }

    private fun resetPendingOutboundRetryBackoff(reason: String) {
        val clearedCount = synchronized(lock) {
            val count = pendingOutboundRetryStates.size
            if (count > 0) {
                pendingOutboundRetryStates.clear()
            }
            count
        }
        if (clearedCount > 0) {
            Log.d(TAG, "Reset pending outbound retry backoff count=$clearedCount reason=$reason")
        }
    }

    private fun queueDeliveryReceipt(messageId: String) {
        val normalizedMessageId = messageId.trim()
        if (normalizedMessageId.isEmpty()) {
            return
        }
        val shouldSchedule = synchronized(lock) {
            pendingDeliveryReceiptIds += normalizedMessageId
            pendingDeliveryReceiptJob?.isActive != true
        }
        if (!shouldSchedule) {
            return
        }
        pendingDeliveryReceiptJob = serviceScope.launch {
            try {
                delay(DELIVERY_RECEIPT_COALESCE_DELAY_MS)
                while (runtimeActive) {
                    val batch = synchronized(lock) {
                        pendingDeliveryReceiptIds
                            .take(MAX_RECEIPT_MESSAGE_IDS)
                            .also { ids -> pendingDeliveryReceiptIds.removeAll(ids.toSet()) }
                    }
                    if (batch.isEmpty()) {
                        break
                    }
                    sendDeliveryReceipt(batch)
                    val hasMorePending = synchronized(lock) {
                        pendingDeliveryReceiptIds.isNotEmpty()
                    }
                    if (!hasMorePending) {
                        break
                    }
                    delay(DELIVERY_RECEIPT_COALESCE_DELAY_MS)
                }
            } finally {
                synchronized(lock) {
                    pendingDeliveryReceiptJob = null
                }
            }
        }
    }

    private fun queueRelayPacket(packet: MeshPacket, excludeAddress: String?) {
        if (!runtimeActive) {
            return
        }
        serviceScope.launch {
            relayPacket(packet = packet, excludeAddress = excludeAddress)
        }
    }

    private fun persistPendingOutboundQueueSnapshot() {
        val snapshot = synchronized(lock) {
            pendingOutboundPackets.values.toList()
        }
        val targetFile = pendingOutboundQueueFile()
        runCatching {
            synchronized(pendingOutboundQueueFileLock) {
                if (snapshot.isEmpty()) {
                    if (targetFile.exists()) {
                        targetFile.delete()
                    }
                    return@synchronized
                }
                val payload = JSONObject().apply {
                    put(PERSISTED_QUEUE_FIELD_VERSION, PERSISTED_QUEUE_VERSION)
                    put(
                        PERSISTED_QUEUE_FIELD_PACKETS,
                        JSONArray().apply {
                            snapshot.forEach { packet ->
                                put(packetToJson(packet))
                            }
                        }
                    )
                }.toString()
                writeTextAtomically(targetFile, payload)
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to persist pending outbound queue", throwable)
        }
    }

    private fun restorePendingOutboundQueueFromDisk() {
        val hasInMemoryPending = synchronized(lock) {
            pendingOutboundPackets.isNotEmpty()
        }
        if (hasInMemoryPending) {
            // Runtime restart in same process: keep fresher in-memory queue, just sync it to disk.
            requestPersistPendingOutboundQueue()
            return
        }

        val restored = runCatching {
            val targetFile = pendingOutboundQueueFile()
            synchronized(pendingOutboundQueueFileLock) {
                if (!targetFile.exists()) {
                    return@synchronized LinkedHashMap<String, MeshPacket>()
                }
                val raw = targetFile.readText(UTF_8).trim()
                if (raw.isEmpty()) {
                    return@synchronized LinkedHashMap<String, MeshPacket>()
                }
                val root = JSONObject(raw)
                val packetArray = root.optJSONArray(PERSISTED_QUEUE_FIELD_PACKETS)
                    ?: return@synchronized LinkedHashMap<String, MeshPacket>()
                val loaded = LinkedHashMap<String, MeshPacket>()
                for (index in 0 until packetArray.length()) {
                    val packetJson = packetArray.optJSONObject(index) ?: continue
                    val packet = decodePacket(packetJson.toString().toByteArray(UTF_8)) ?: continue
                    if (packet.type != MeshPacketType.CHAT) {
                        continue
                    }
                    if (!isPacketTimestampValid(packet.timestampMillis)) {
                        continue
                    }
                    if (System.currentTimeMillis() - packet.timestampMillis >= MAX_PENDING_OUTBOUND_PACKET_AGE_MS) {
                        continue
                    }
                    loaded[packet.id] = packet
                    if (loaded.size >= MAX_PENDING_OUTBOUND_PACKETS) {
                        break
                    }
                }
                loaded
            }
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to restore pending outbound queue", throwable)
        }.getOrDefault(LinkedHashMap())

        synchronized(lock) {
            pendingOutboundPackets.clear()
            pendingOutboundRetryStates.clear()
            pendingOutboundPackets.putAll(restored)
        }

        if (restored.isNotEmpty()) {
            Log.d(TAG, "Restored pending outbound queue size=${restored.size}")
        }
        // Rewrite snapshot to normalize/deduplicate and drop any expired entries from disk.
        requestPersistPendingOutboundQueue()
    }

    private fun pendingOutboundQueueFile(): File {
        return File(filesDir, PERSISTED_QUEUE_FILE_NAME)
    }

    private fun writeTextAtomically(targetFile: File, payload: String) {
        val parent = targetFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent ?: filesDir, "${targetFile.name}.tmp")
        tempFile.writeText(payload, UTF_8)
        if (tempFile.renameTo(targetFile)) {
            return
        }
        if (targetFile.exists()) {
            targetFile.delete()
        }
        check(tempFile.renameTo(targetFile)) {
            "Unable to atomically replace ${targetFile.absolutePath}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMeshRuntime() {
        val shouldStart = synchronized(lock) {
            if (runtimeActive || startupInProgress) {
                false
            } else {
                startupInProgress = true
                true
            }
        }
        if (!shouldStart) {
            return
        }
        try {
            restorePendingOutboundQueueFromDisk()

            if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
                publishErrorAndStop(R.string.gatt_mesh_error_bluetooth_unavailable)
                return
            }
            if (!hasRequiredBluetoothPermissions()) {
                publishErrorAndStop(R.string.gatt_mesh_error_permission_required)
                return
            }

            val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = manager?.adapter
            if (manager == null || adapter == null) {
                publishErrorAndStop(R.string.gatt_mesh_error_bluetooth_unavailable)
                return
            }
            if (!adapter.isEnabled) {
                publishErrorAndStop(R.string.gatt_mesh_error_bluetooth_disabled)
                return
            }
            if (profile.derivePayloadKey(this) == null) {
                // Authority mesh stays off until the device is provisioned with the shared group
                // key. Civilian/unprovisioned devices never hold it, so they never broadcast or
                // join the authority network. The public profile always returns a key here.
                Log.w(TAG, "[${profile.id}] mesh payload key unavailable; not starting runtime")
                stopSelfSafely()
                return
            }

            bluetoothManager = manager
            bluetoothAdapter = adapter
            scanner = adapter.bluetoothLeScanner
            advertiser = adapter.bluetoothLeAdvertiser

            runtimeActive = true
            MeshServiceRegistry.setRuntimeActive(profile.id, true)
            registerClassicDiscoveryReceiver()
            if (profile.admission is AdmissionPolicy.RequireVerifiedRole) {
                if (awareAccelerator == null) {
                    awareAccelerator = AuthorityMeshAwareAccelerator(
                        context = this,
                        groupKeyProvider = { profile.derivePayloadKey(this) },
                        onBlobReceived = ::handleAcceleratorBlob
                    )
                }
                awareAccelerator?.start()
            }
            val existingP2pSharedServer = P2pGattServerService.sharedGattServerOrNull()
            if (existingP2pSharedServer != null && attachToP2pGattServer()) {
                p2pSharedGattServerMode = true
                sosSharedGattServerMode = false
                clientOnlyMeshFallbackActive = false
                legacyLocalServerRecoveryAttempts = 0
                Log.i(TAG, "P2P host is active; GATT mesh reusing P2P server")
                stateFlow.update {
                    it.copy(isEnabled = true, errorMessage = null)
                }
                startScanLoop()
                if (!runtimeActive) {
                    return
                }
                startCleanupLoop()
                requestPendingOutboundRecovery(reason = "runtime-start")
                publishState()
                return
            }
            if (GattSOSServerService.isRunning.value) {
                if (!attachToSosGattServer()) {
                    publishErrorAndStop(R.string.gatt_mesh_error_server_start_failed)
                    return
                }
                sosSharedGattServerMode = true
                p2pSharedGattServerMode = false
                clientOnlyMeshFallbackActive = false
                legacyLocalServerRecoveryAttempts = 0
                Log.i(TAG, "SOS is active; GATT mesh reusing SOS server")
                stateFlow.update {
                    it.copy(isEnabled = true, errorMessage = null)
                }
                startScanLoop()
                if (!runtimeActive) {
                    return
                }
                startCleanupLoop()
                requestPendingOutboundRecovery(reason = "runtime-start")
                publishState()
                return
            }
            sosSharedGattServerMode = false
            p2pSharedGattServerMode = false
            usesExternalGattServer = false
            localGattServerStartupCompleted = false
            clientOnlyMeshFallbackActive = false
            legacyLocalServerRecoveryAttempts = 0
            activeLocalServerCallbackGeneration += 1
            if (!startGattServer()) {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                    startClientOnlyMeshFallback(reason = "local-server-start-failed")
                }
                return
            }
            Log.d(TAG, "Local gatt server registration returned sdk=${Build.VERSION.SDK_INT}")
            val startupCallbackGeneration = synchronized(lock) { activeLocalServerCallbackGeneration }
            postAddServiceRecoveryJob?.cancel()
            postAddServiceRecoveryJob = serviceScope.launch {
                delay(POST_ADD_SERVICE_STARTUP_RECOVERY_DELAY_MS)
                val shouldRecover = synchronized(lock) {
                    runtimeActive &&
                        gattServer != null &&
                        !usesExternalGattServer &&
                        !localGattServerStartupCompleted &&
                        activeLocalServerCallbackGeneration == startupCallbackGeneration &&
                        !BleScanCoordinator.isActive(profile.scanCoordinatorOwner)
                }
                if (shouldRecover) {
                    Log.w(
                        TAG,
                        "Local gatt startup still pending after addService return; forcing post-add-service recovery"
                    )
                    completeLocalGattServerStartup(reason = "post-add-service-recovery")
                }
            }
            localGattServerStartupFallbackJob?.cancel()
            localGattServerStartupFallbackJob = serviceScope.launch {
                delay(LOCAL_GATT_SERVER_STARTUP_FALLBACK_DELAY_MS)
                val shouldFallback = synchronized(lock) {
                    runtimeActive &&
                        gattServer != null &&
                        !usesExternalGattServer &&
                        !localGattServerStartupCompleted &&
                        activeLocalServerCallbackGeneration == startupCallbackGeneration
                }
                if (shouldFallback) {
                    Log.w(TAG, "Gatt server startup callback did not arrive; finalizing startup via fallback")
                    completeLocalGattServerStartup(reason = "service-added-fallback")
                }
            }
            startupTimeoutJob?.cancel()
            startupTimeoutJob = serviceScope.launch {
                delay(SERVICE_READY_TIMEOUT_MS)
                val shouldForceRecovery = synchronized(lock) {
                    runtimeActive &&
                        gattServer != null &&
                        !usesExternalGattServer &&
                        !localGattServerStartupCompleted &&
                        !BleScanCoordinator.isActive(profile.scanCoordinatorOwner)
                }
                if (shouldForceRecovery) {
                    Log.w(
                        TAG,
                        "Gatt mesh startup timed out before scan became active; forcing local startup recovery"
                    )
                    completeLocalGattServerStartup(reason = "startup-timeout-recovery")
                    delay(STARTUP_TIMEOUT_RECOVERY_GRACE_MS)
                }
                val shouldFail = synchronized(lock) {
                    !BleScanCoordinator.isActive(profile.scanCoordinatorOwner) &&
                        gattServer != null &&
                        runtimeActive
                }
                if (shouldFail) {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                        Log.w(
                            TAG,
                            "Legacy gatt mesh startup timed out waiting for active scan; retrying local startup recovery before failing"
                        )
                        completeLocalGattServerStartup(reason = "legacy-startup-timeout-retry")
                        return@launch
                    }
                    publishErrorAndStop(R.string.gatt_mesh_error_server_start_failed)
                }
            }
            stateFlow.update {
                it.copy(isEnabled = true, errorMessage = null)
            }
            requestPendingOutboundRecovery(reason = "runtime-start")
            publishState()
        } finally {
            synchronized(lock) {
                startupInProgress = false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopMeshRuntime(clearError: Boolean) {
        val sharedServerDisconnectCandidates = LinkedHashSet<String>()
        BleScanCoordinator.unregister(profile.scanCoordinatorOwner)

        runCatching {
            advertiser?.stopAdvertising(advertiseCallback)
        }

        unregisterClassicDiscoveryReceiver()

        val fallbackJobsToCancel = synchronized(lock) {
            // In shared SOS-server mode, delegate unregistration alone may keep transport links alive.
            // Keep a snapshot so we can force remote disconnect and clear stale "connected peers".
            sharedServerDisconnectCandidates += clientPeers.keys
            sharedServerDisconnectCandidates += serverDevices.keys
            sharedServerDisconnectCandidates += sharedModeMeshQualifiedPeers
            clientPeers.values.forEach { peer ->
                disconnectGattQuietly(peer.gatt)
            }
            clientNotificationSettleJobs.values.forEach { job -> job.cancel() }
            clientNotificationSettleJobs.clear()
            clientReconnectJobs.values.forEach { job -> job.cancel() }
            clientReconnectJobs.clear()
            pendingOutboundRecoveryJob?.cancel()
            pendingOutboundRecoveryJob = null
            pendingDeliveryReceiptJob?.cancel()
            pendingDeliveryReceiptJob = null
            clientNotificationBypassPeers.clear()
            clientNoResponsePreferredPeers.clear()
            clientWithResponsePreferredUntilElapsedRealtimeMs.clear()
            clientPacketQuietUntilElapsedRealtimeMs.clear()
            serverNotifyCallbackBypassPeers.clear()
            clientNoResponseSettleOverridesMs.clear()
            securityRecoveryAttemptAtMillis.clear()
            clientWriteTickets.values.forEach { ticket ->
                ticket.status = BluetoothGatt.GATT_FAILURE
                ticket.latch.countDown()
            }
            clientWriteTickets.clear()
            serverNotifyTickets.values.forEach { ticket ->
                ticket.status = BluetoothGatt.GATT_FAILURE
                ticket.latch.countDown()
            }
            serverNotifyTickets.clear()
            clientPeers.clear()
            clientFailureBackoff.clear()
            lastConnectAttemptMillis.clear()
            p2pSuppressedPeersUntil.clear()
            serverDevices.clear()
            serverNotifyEnabled.clear()
            serverConnectedAt.clear()
            serverPeerMtu.clear()
            serverPendingPayload.clear()
            discoveredPeers.clear()
            firstDiscoveredPeers.clear()
            discoveredDevices.clear()
            discoveredPeerInitiatorRanks.clear()
            inboundRateWindows.clear()
            seenMessageIds.clear()
            peerSenderLabels.clear()
            peerVerificationByAddress.clear()
            pendingPeerVerificationNonces.clear()
            pendingPeerVerificationRequestedAtMillis.clear()
            identityAnnouncementSentForPeers.clear()
            identityAnnouncementPendingForPeers.clear()
            pendingDeliveryReceiptIds.clear()
            inboundChunkReceivers.values.forEach { receivers ->
                receivers.values.forEach { receiver -> receiver.reset() }
            }
            inboundChunkReceivers.clear()
            outboundSendLocks.clear()
            sharedModeMeshQualifiedPeers.clear()
            sharedModeObservedInboundConnectedAt.clear()
            systemConnectedLeAddresses = emptySet()
            systemConnectedSnapshotAtMillis = 0L
            hasFreshSystemConnectedSnapshot = false
            val jobs = inboundFallbackJobs.values.toList()
            inboundFallbackJobs.clear()
            jobs
        }
        fallbackJobsToCancel.forEach { job -> job.cancel() }

        cleanupJob?.cancel()
        cleanupJob = null
        scanResumeJob?.cancel()
        scanResumeJob = null
        startupTimeoutJob?.cancel()
        startupTimeoutJob = null
        postAddServiceRecoveryJob?.cancel()
        postAddServiceRecoveryJob = null
        localGattServerStartupFallbackJob?.cancel()
        localGattServerStartupFallbackJob = null
        legacyLocalServerRecoveryJob?.cancel()
        legacyLocalServerRecoveryJob = null
        pendingOutboundFlushJob?.cancel()
        pendingOutboundFlushJob = null
        advertiseRetryJob?.cancel()
        advertiseRetryJob = null
        advertiseConflictRetryAttempts = 0

        if (sosSharedGattServerMode) {
            if (sharedServerDisconnectCandidates.isNotEmpty()) {
                GattSOSServerService.disconnectSharedClients(sharedServerDisconnectCandidates)
            }
            GattSOSServerService.unregisterSharedGattDelegate(profile.serviceUuid)
        }
        if (p2pSharedGattServerMode) {
            if (sharedServerDisconnectCandidates.isNotEmpty()) {
                P2pGattServerService.disconnectSharedClients(sharedServerDisconnectCandidates)
            }
            P2pGattServerService.unregisterSharedGattDelegate(profile.serviceUuid)
            P2pGattServerService.releaseSharedHost(applicationContext)
        }
        if (usesExternalGattServer) {
            gattServer = null
        } else {
            runCatching { gattServer?.close() }
            gattServer = null
        }
        awareAccelerator?.stop()
        runtimeActive = false
        MeshServiceRegistry.setRuntimeActive(profile.id, false)
        sosSharedGattServerMode = false
        p2pSharedGattServerMode = false
        usesExternalGattServer = false
        localGattServerStartupCompleted = false
        clientOnlyMeshFallbackActive = false
        legacyLocalServerRecoveryAttempts = 0
        activeLocalServerCallbackGeneration += 1

        stateFlow.update {
            it.copy(
                isEnabled = false,
                isScanning = false,
                connectedPeerCount = 0,
                discoveredPeerCount = 0,
                sendReadyPeerCount = 0,
                connectedPeers = emptyList(),
                errorMessage = if (clearError) null else it.errorMessage,
            )
        }
        lastNotifiedConnectedCount = -1
        lastNotifiedReadyCount = -1
        lastNotifiedDiscoveredCount = -1
        lastLoggedConnectedCount = -1
        lastLoggedReadyCount = -1
        lastLoggedDiscoveredCount = -1
    }

    private fun attachToSosGattServer(): Boolean {
        val registered = GattSOSServerService.registerSharedGattDelegate(sharedSosGattDelegate)
        if (!registered) {
            Log.w(TAG, "Unable to register mesh profile on SOS GATT server")
            return false
        }
        val sharedServer = GattSOSServerService.sharedGattServerOrNull()
        if (sharedServer == null) {
            Log.w(TAG, "SOS GATT server reference unavailable after shared profile registration")
            GattSOSServerService.unregisterSharedGattDelegate(profile.serviceUuid)
            return false
        }
        gattServer = sharedServer
        usesExternalGattServer = true
        activeLocalServerCallbackGeneration += 1
        postAddServiceRecoveryJob?.cancel()
        postAddServiceRecoveryJob = null
        localGattServerStartupFallbackJob?.cancel()
        localGattServerStartupFallbackJob = null
        legacyLocalServerRecoveryJob?.cancel()
        legacyLocalServerRecoveryJob = null
        legacyLocalServerRecoveryAttempts = 0
        return true
    }

    private fun attachToP2pGattServer(): Boolean {
        var delegateRegistered = false
        repeat(SHARED_GATT_ATTACH_MAX_ATTEMPTS) { attempt ->
            if (!delegateRegistered) {
                delegateRegistered = P2pGattServerService.registerSharedGattDelegate(sharedP2pGattDelegate)
            }
            val sharedServer = P2pGattServerService.sharedGattServerOrNull()
            if (delegateRegistered && sharedServer != null) {
                P2pGattServerService.acquireSharedHost(applicationContext)
                gattServer = sharedServer
                usesExternalGattServer = true
                activeLocalServerCallbackGeneration += 1
                postAddServiceRecoveryJob?.cancel()
                postAddServiceRecoveryJob = null
                localGattServerStartupFallbackJob?.cancel()
                localGattServerStartupFallbackJob = null
                legacyLocalServerRecoveryJob?.cancel()
                legacyLocalServerRecoveryJob = null
                legacyLocalServerRecoveryAttempts = 0
                return true
            }
            if (attempt < SHARED_GATT_ATTACH_MAX_ATTEMPTS - 1) {
                Thread.sleep(SHARED_GATT_ATTACH_RETRY_DELAY_MS)
            }
        }
        if (delegateRegistered) {
            P2pGattServerService.unregisterSharedGattDelegate(profile.serviceUuid)
        }
        return false
    }

    private fun shouldHandleLocalServerCallback(
        callbackGeneration: Long,
        requireStartupPending: Boolean = false
    ): Boolean {
        return synchronized(lock) {
            if (callbackGeneration != activeLocalServerCallbackGeneration) {
                return@synchronized false
            }
            if (!runtimeActive || usesExternalGattServer || gattServer == null) {
                return@synchronized false
            }
            if (requireStartupPending && localGattServerStartupCompleted) {
                return@synchronized false
            }
            true
        }
    }

    private fun createMeshGattService(): BluetoothGattService {
        val meshService = BluetoothGattService(
            profile.serviceUuid,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )

        val inCharacteristic = BluetoothGattCharacteristic(
            profile.messageInUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            // Transport link can stay unpaired; payload is protected at app layer (AES-GCM).
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )

        val outCharacteristic = BluetoothGattCharacteristic(
            profile.messageOutUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        outCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )

        meshService.addCharacteristic(inCharacteristic)
        meshService.addCharacteristic(outCharacteristic)
        return meshService
    }

    private fun handleServerConnectionStateChanged(device: BluetoothDevice, newState: Int) {
        val address = normalizeAddress(device.address)
        var shouldPublish = false
        var shouldScheduleFallback = false
        synchronized(lock) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val sharedModeRequiresQualification = usesExternalGattServer &&
                    !sharedModeMeshQualifiedPeers.contains(address)
                if (!sharedModeRequiresQualification) {
                    val wasConnected = serverDevices.containsKey(address)
                    serverDevices[address] = device
                    if (!wasConnected) {
                        shouldPublish = true
                        shouldScheduleFallback = true
                        serverNotifyEnabled[address] = false
                    }
                    serverConnectedAt.putIfAbsent(address, System.currentTimeMillis())
                    serverPeerMtu.putIfAbsent(address, DEFAULT_ATT_MTU)
                    sharedModeObservedInboundConnectedAt.remove(address)
                } else {
                    sharedModeObservedInboundConnectedAt[address] = System.currentTimeMillis()
                    shouldScheduleFallback = true
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                sharedModeMeshQualifiedPeers.remove(address)
                sharedModeObservedInboundConnectedAt.remove(address)
                clearServerPeerLocked(address)
                shouldPublish = true
            }
        }
        if (shouldScheduleFallback) {
            // Some peers connect inbound but never enable CCCD; fallback outbound connect after grace.
            scheduleInboundFallbackConnect(device, address)
        }
        if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            cancelInboundFallbackConnect(address)
        }
        if (shouldPublish) {
            publishState()
        }
    }

    private fun handleServerMtuChanged(device: BluetoothDevice, mtu: Int) {
        val address = normalizeAddress(device.address)
        val resolvedMtu = mtu.coerceAtLeast(DEFAULT_ATT_MTU)
        synchronized(lock) {
            if (serverDevices.containsKey(address)) {
                serverPeerMtu[address] = resolvedMtu
            }
        }
        Log.d(TAG, "[$address] Server MTU updated mtu=$resolvedMtu")
    }

    private fun handleServerNotificationSent(device: BluetoothDevice, status: Int) {
        val address = normalizeAddress(device.address)
        val ticket = synchronized(lock) {
            serverNotifyTickets[address]
        }
        if (ticket != null) {
            ticket.status = status
            ticket.latch.countDown()
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "[$address] Server notification callback failed status=$status")
        }
    }

    private fun handleServerCharacteristicWrite(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        offset: Int,
        value: ByteArray?
    ): Int {
        if (characteristic.uuid == profile.messageInUuid) {
            qualifySharedModeMeshPeer(device)
        }
        return when {
            characteristic.uuid != profile.messageInUuid -> BluetoothGatt.GATT_FAILURE
            preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            else -> {
                val payload = value ?: ByteArray(0)
                if (payload.isEmpty()) {
                    BluetoothGatt.GATT_FAILURE
                } else {
                    val sourceAddress = normalizeAddress(device.address)
                    val accepted = handleIncomingPacket(
                        payload = payload,
                        sourceAddress = sourceAddress,
                        transportChannel = InboundTransportChannel.SERVER_WRITE
                    )
                    if (accepted) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE
                }
            }
        }
    }

    private fun handleServerCharacteristicRead(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        offset: Int
    ): Pair<Int, ByteArray> {
        if (characteristic.uuid == profile.messageOutUuid) {
            qualifySharedModeMeshPeer(device)
        }
        val address = normalizeAddress(device.address)
        val fullValue = if (characteristic.uuid == profile.messageOutUuid) {
            synchronized(lock) {
                serverPendingPayload[address] ?: ByteArray(0)
            }
        } else {
            ByteArray(0)
        }
        return when {
            characteristic.uuid != profile.messageOutUuid -> BluetoothGatt.GATT_FAILURE to ByteArray(0)
            offset < 0 || offset > fullValue.size -> BluetoothGatt.GATT_INVALID_OFFSET to ByteArray(0)
            else -> BluetoothGatt.GATT_SUCCESS to fullValue.copyOfRange(offset, fullValue.size)
        }
    }

    private fun handleServerDescriptorRead(
        device: BluetoothDevice,
        descriptor: BluetoothGattDescriptor,
        offset: Int
    ): Pair<Int, ByteArray> {
        if (
            descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID ||
            descriptor.characteristic.uuid != profile.messageOutUuid
        ) {
            return BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED to ByteArray(0)
        }
        qualifySharedModeMeshPeer(device)
        val address = normalizeAddress(device.address)
        val value = synchronized(lock) {
            if (serverNotifyEnabled[address] == true) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
        }
        if (offset < 0 || offset > value.size) {
            return BluetoothGatt.GATT_INVALID_OFFSET to ByteArray(0)
        }
        return BluetoothGatt.GATT_SUCCESS to value.copyOfRange(offset, value.size)
    }

    private fun handleServerDescriptorWrite(
        device: BluetoothDevice,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        offset: Int,
        value: ByteArray
    ): Int {
        val address = normalizeAddress(device.address)
        return when {
            preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            !(
                descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic.uuid == profile.messageOutUuid
                ) -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED

            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
                value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                qualifySharedModeMeshPeer(device)
                synchronized(lock) {
                    serverNotifyEnabled[address] = true
                    serverPeerMtu.putIfAbsent(address, DEFAULT_ATT_MTU)
                }
                Log.d(TAG, "[$address] Peer enabled notifications for mesh out characteristic")
                resetInboundChunkReceiver(address, InboundTransportChannel.SERVER_WRITE)
                // CCCD is active now; keeping fallback connect can reintroduce 133/147 races.
                cancelInboundFallbackConnect(address)
                resetPendingOutboundRetryBackoff(reason = "server-notify-ready:$address")
                publishState()
                requestFlushPendingOutboundPackets()
                requestLocalSenderAnnouncement(address)
                requestPeerVerification(address)
                BluetoothGatt.GATT_SUCCESS
            }

            value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                qualifySharedModeMeshPeer(device)
                synchronized(lock) {
                    serverNotifyEnabled[address] = false
                }
                Log.d(TAG, "[$address] Peer disabled notifications for mesh out characteristic")
                publishState()
                BluetoothGatt.GATT_SUCCESS
            }

            else -> BluetoothGatt.GATT_FAILURE
        }
    }

    private fun qualifySharedModeMeshPeer(device: BluetoothDevice) {
        val address = normalizeAddress(device.address)
        var shouldPublish = false
        var shouldScheduleFallback = false
        synchronized(lock) {
            if (usesExternalGattServer) {
                sharedModeMeshQualifiedPeers += address
            }
            val wasConnected = serverDevices.containsKey(address)
            serverDevices[address] = device
            serverConnectedAt.putIfAbsent(address, System.currentTimeMillis())
            serverPeerMtu.putIfAbsent(address, DEFAULT_ATT_MTU)
            if (!wasConnected) {
                serverNotifyEnabled[address] = false
                shouldPublish = true
                shouldScheduleFallback = true
            }
        }
        if (shouldScheduleFallback) {
            scheduleInboundFallbackConnect(device, address)
        }
        if (shouldPublish) {
            publishState()
        }
    }

    private fun publishErrorAndStop(messageRes: Int) {
        Log.w(TAG, "Stopping gatt mesh runtime errorMessageRes=$messageRes")
        stateFlow.update {
            it.copy(
                isEnabled = false,
                isScanning = false,
                connectedPeers = emptyList(),
                errorMessage = messageRes,
            )
        }
        stopMeshRuntime(clearError = false)
        stopSelfSafely()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer(): Boolean {
        val manager = bluetoothManager ?: return false
        if (!hasBluetoothConnectPermission()) {
            publishErrorAndStop(R.string.gatt_mesh_error_permission_required)
            return false
        }

        var server: BluetoothGattServer? = null
        val callbackGeneration = synchronized(lock) {
            activeLocalServerCallbackGeneration += 1
            activeLocalServerCallbackGeneration
        }
        val serverCallback = createServerCallback(callbackGeneration)
        val openAttempts = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            1
        } else {
            GATT_SERVER_OPEN_MAX_ATTEMPTS
        }
        for (attempt in 0 until openAttempts) {
            server = runCatching {
                manager.openGattServer(this, serverCallback)
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "Gatt server open attempt=${attempt + 1} failed before registration completed",
                    throwable
                )
            }.getOrNull()
            if (server != null) {
                if (attempt > 0) {
                    Log.w(TAG, "Gatt server open recovered on retry attempt=${attempt + 1}")
                }
                break
            }
            if (attempt < openAttempts - 1) {
                Log.w(TAG, "Gatt server open returned null; retrying attempt=${attempt + 1}")
                SystemClock.sleep(GATT_SERVER_OPEN_RETRY_DELAY_MS)
            }
        }
        if (server == null) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                Log.w(TAG, "Legacy device could not open local gatt server; continuing in client-only mode")
                scheduleLegacyLocalServerRecovery(reason = "open-null")
            } else {
                publishErrorAndStop(R.string.gatt_mesh_error_server_start_failed)
            }
            return false
        }
        synchronized(lock) {
            gattServer = server
            usesExternalGattServer = false
        }
        Log.d(TAG, "Opened local gatt server; registering mesh service")

        var addServiceSucceeded = false
        val addServiceAttempts = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            1
        } else {
            GATT_SERVER_ADD_SERVICE_MAX_ATTEMPTS
        }
        for (attempt in 0 until addServiceAttempts) {
            val meshService = createMeshGattService()
            addServiceSucceeded = runCatching { server.addService(meshService) }.getOrDefault(false)
            if (addServiceSucceeded) {
                if (attempt > 0) {
                    Log.w(TAG, "Gatt server addService recovered on retry attempt=${attempt + 1}")
                }
                break
            }
            if (attempt < addServiceAttempts - 1) {
                Log.w(TAG, "Gatt server addService returned false; retrying attempt=${attempt + 1}")
                SystemClock.sleep(GATT_SERVER_ADD_SERVICE_RETRY_DELAY_MS)
            }
        }
        if (!addServiceSucceeded) {
            synchronized(lock) {
                if (gattServer === server) {
                    gattServer = null
                }
            }
            runCatching { server.close() }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                Log.w(TAG, "Legacy device could not register mesh service; continuing in client-only mode")
                scheduleLegacyLocalServerRecovery(reason = "add-service-failed")
            } else {
                publishErrorAndStop(R.string.gatt_mesh_error_server_start_failed)
            }
            return false
        }

        synchronized(lock) {
            legacyLocalServerRecoveryAttempts = 0
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            Log.w(TAG, "Legacy BLE stack detected inside startGattServer; finalizing local startup inline")
            completeLocalGattServerStartup(reason = "legacy-add-service-inline")
        }

        return true
    }

    private fun completeLocalGattServerStartup(reason: String) {
        val shouldStart = synchronized(lock) {
            if (!runtimeActive || usesExternalGattServer || gattServer == null || localGattServerStartupCompleted) {
                return@synchronized false
            }
            localGattServerStartupCompleted = true
            clientOnlyMeshFallbackActive = false
            legacyLocalServerRecoveryAttempts = 0
            true
        }
        if (!shouldStart) {
            val stateSnapshot = synchronized(lock) {
                "runtimeActive=$runtimeActive usesExternalGattServer=$usesExternalGattServer " +
                    "hasGattServer=${gattServer != null} startupCompleted=$localGattServerStartupCompleted"
            }
            Log.d(TAG, "Skipping local gatt startup completion reason=$reason $stateSnapshot")
            return
        }
        postAddServiceRecoveryJob?.cancel()
        postAddServiceRecoveryJob = null
        localGattServerStartupFallbackJob?.cancel()
        localGattServerStartupFallbackJob = null
        if (!startAdvertising()) {
            synchronized(lock) {
                localGattServerStartupCompleted = false
            }
            return
        }
        Log.d(TAG, "Completed local gatt server startup reason=$reason")
        startScanLoop()
        if (!runtimeActive) {
            return
        }
        startCleanupLoop()
        stateFlow.update { it.copy(isEnabled = true, errorMessage = null) }
        publishState()
    }

    private fun startClientOnlyMeshFallback(reason: String) {
        val shouldStart = synchronized(lock) {
            if (!runtimeActive || usesExternalGattServer || clientOnlyMeshFallbackActive) {
                return@synchronized false
            }
            clientOnlyMeshFallbackActive = true
            true
        }
        if (!shouldStart) {
            return
        }
        Log.w(TAG, "Starting client-only gatt mesh fallback reason=$reason")
        startScanLoop()
        if (!runtimeActive) {
            return
        }
        startCleanupLoop()
        stateFlow.update { it.copy(isEnabled = true, errorMessage = null) }
        requestPendingOutboundRecovery(reason = "client-only-fallback")
        publishState()
    }

    private fun scheduleLegacyLocalServerRecovery(reason: String) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            return
        }
        val attempt = synchronized(lock) {
            if (
                !runtimeActive ||
                usesExternalGattServer ||
                gattServer != null ||
                legacyLocalServerRecoveryJob?.isActive == true
            ) {
                return
            }
            legacyLocalServerRecoveryAttempts += 1
            legacyLocalServerRecoveryAttempts
        }
        val delayMillis = legacyLocalServerRecoveryDelayMillis(attempt)
        Log.w(
            TAG,
            "Scheduling legacy local gatt server recovery attempt=$attempt delayMs=$delayMillis reason=$reason"
        )
        val job = serviceScope.launch {
            delay(delayMillis)
            val shouldRetry = synchronized(lock) {
                val isActiveRecoveryJob = legacyLocalServerRecoveryJob === coroutineContext[Job]
                if (isActiveRecoveryJob) {
                    legacyLocalServerRecoveryJob = null
                }
                isActiveRecoveryJob &&
                    runtimeActive &&
                    !usesExternalGattServer &&
                    gattServer == null
            }
            if (!shouldRetry) {
                return@launch
            }
            Log.w(
                TAG,
                "Retrying legacy local gatt server bootstrap attempt=$attempt reason=$reason"
            )
            val started = startGattServer()
            if (started) {
                requestPendingOutboundRecovery(reason = "legacy-local-server-recovery")
            } else {
                startClientOnlyMeshFallback(reason = "legacy-local-server-retry-failed")
            }
        }
        synchronized(lock) {
            if (
                runtimeActive &&
                !usesExternalGattServer &&
                gattServer == null &&
                legacyLocalServerRecoveryJob == null
            ) {
                legacyLocalServerRecoveryJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun legacyLocalServerRecoveryDelayMillis(attempt: Int): Long {
        val boundedAttempt = attempt.coerceAtLeast(1)
        val backoffStep = (boundedAttempt - 1).coerceAtMost(3)
        return LEGACY_GATT_SERVER_RECOVERY_BASE_DELAY_MS +
            (backoffStep * LEGACY_GATT_SERVER_RECOVERY_BACKOFF_STEP_MS)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising(): Boolean {
        val localAdvertiser = advertiser ?: run {
            publishErrorAndStop(R.string.gatt_mesh_error_bluetooth_unavailable)
            return false
        }
        if (!hasBluetoothAdvertisePermission()) {
            publishErrorAndStop(R.string.gatt_mesh_error_permission_required)
            return false
        }

        val radioDecision = BleRadioPolicy.resolve(
            context = applicationContext,
            preferPerformance = true,
            hasActiveTransfer = pendingOutboundPackets.isNotEmpty(),
            connectedPeerCount = stateFlow.value.connectedPeerCount
        )
        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setAdvertiseMode(radioDecision.advertiseMode)
            .setTxPowerLevel(radioDecision.advertiseTxPower)
            .setTimeout(0)
            .build()

        val advertiseData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(profile.serviceUuid))
            // Keep initiator metadata in the primary advertising payload so tie-breaker information
            // survives tight payload budgets on older scanners.
            .addManufacturerData(
                INITIATOR_RANK_MANUFACTURER_ID,
                encodeInitiatorRank(localInitiatorRank)
            )
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(profile.serviceUuid))
            .build()

        return runCatching {
            localAdvertiser.startAdvertising(settings, advertiseData, scanResponse, advertiseCallback)
            true
        }.onFailure {
            publishErrorAndStop(R.string.gatt_mesh_error_advertise_failed)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun startScanLoop() {
        val localScanner = scanner ?: run {
            publishErrorAndStop(R.string.gatt_mesh_error_bluetooth_unavailable)
            return
        }
        if (!hasBluetoothScanPermission()) {
            publishErrorAndStop(R.string.gatt_mesh_error_permission_required)
            return
        }
        stopClassicDiscoveryIfRunning(reason = "scan-start")

        BleScanCoordinator.setPaused(profile.scanCoordinatorOwner, paused = false)
        scanResumeJob?.cancel()
        scanResumeJob = null

        val radioDecision = BleRadioPolicy.resolve(
            context = applicationContext,
            preferPerformance = true,
            hasActiveTransfer = pendingOutboundPackets.isNotEmpty(),
            connectedPeerCount = stateFlow.value.connectedPeerCount
        )
        // Legacy Samsung/Broadcom stacks frequently report zero available hardware filter slots or
        // silently block filtered scans. GattMesh already validates service/manufacturer markers in
        // software, so prefer an unfiltered scan here to keep peer discovery symmetric.
        val started = BleScanCoordinator.registerOrUpdate(
            owner = profile.scanCoordinatorOwner,
            scanner = localScanner,
            mode = radioDecision.scanMode,
            filters = null,
            listener = meshScanListener
        )
        if (!started) {
            publishErrorAndStop(R.string.gatt_mesh_error_scan_failed)
            return
        }
        publishState()
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val serviceUuids = result.scanRecord?.serviceUuids
        val hasMeshService = serviceUuids?.any { it.uuid == profile.serviceUuid } == true
        val hasSosService = serviceUuids?.any { it.uuid == GattSOSServerService.CRISIS_SERVICE_UUID } == true
        val address = normalizeAddress(device.address)
        val localAddress = getLocalAdapterAddressOrNull()
        if (address.isBlank() || address == localAddress) {
            return
        }

        val now = System.currentTimeMillis()
        val peerInitiatorRank = decodePeerInitiatorRank(result)
        val hasMeshMarker = hasMeshService || hasSosService || peerInitiatorRank != null
        if (!hasMeshMarker) {
            return
        }
        if (peerInitiatorRank != null && peerInitiatorRank == localInitiatorRank) {
            // Defensive self-filter for devices where adapter MAC is masked and
            // scanner still reports local advertisements as remote peers.
            Log.d(TAG, "Ignoring self mesh advertisement candidate address=$address")
            return
        }
        var allowFailureBackoffBypass = false
        synchronized(lock) {
            discoveredPeers[address] = now
            if (firstDiscoveredPeers[address] == null) {
                firstDiscoveredPeers[address] = now
            }
            discoveredDevices[address] = device
            if (peerInitiatorRank != null) {
                discoveredPeerInitiatorRanks[address] = peerInitiatorRank
            }
            allowFailureBackoffBypass = shouldBypassFailureBackoffForAddressLocked(
                address = address,
                now = now
            )
        }
        val hasQueuedOutbound = synchronized(lock) {
            pendingOutboundPackets.isNotEmpty()
        }
        if (shouldInitiateConnection(address, queuedPayloadHandoverAllowed = hasQueuedOutbound)) {
            // connectToPeer may pause scan and briefly sleep; keep scan callback thread responsive.
            serviceScope.launch {
                connectToPeer(
                    device = device,
                    address = address,
                    allowFailureBackoffBypass = allowFailureBackoffBypass
                )
            }
        }
        publishState()
    }

    // Must be called with lock held.
    private fun shouldBypassFailureBackoffForAddressLocked(address: String, now: Long): Boolean {
        val hasInbound = serverDevices.containsKey(address) ||
            sharedModeObservedInboundConnectedAt.containsKey(address)
        if (!hasInbound) {
            return false
        }
        if (serverNotifyEnabled[address] == true) {
            return false
        }
        val inboundAt = serverConnectedAt[address]
            ?: sharedModeObservedInboundConnectedAt[address]
            ?: return false
        return now - inboundAt >= INBOUND_NOTIFY_GRACE_MS
    }

    private fun shouldInitiateConnection(
        peerAddress: String,
        queuedPayloadHandoverAllowed: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        val localAddress = getLocalAdapterAddressOrNull()
        val hasValidLocalAddress = !localAddress.isNullOrBlank() &&
            localAddress != INVALID_MAC_ADDRESS &&
            localAddress != peerAddress
        return synchronized(lock) {
            if (isClientSendRecoveryBlockedLocked(peerAddress, now)) {
                return@synchronized false
            }
            val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
            var hasInboundConnection = serverDevices.containsKey(peerAddress) ||
                sharedModeObservedInboundConnectedAt.containsKey(peerAddress)
            if (hasInboundConnection && systemSnapshot != null && peerAddress !in systemSnapshot) {
                hasInboundConnection = false
            }
            val inboundNotifyEnabled = serverNotifyEnabled[peerAddress] == true
            val inboundConnectedAt = serverConnectedAt[peerAddress]
                ?: sharedModeObservedInboundConnectedAt[peerAddress]
                ?: now
            val oldestPendingOutboundTimestamp = if (queuedPayloadHandoverAllowed) {
                pendingOutboundPackets.values.minOfOrNull(MeshPacket::timestampMillis)
            } else {
                null
            }
            val firstSeenAt = firstDiscoveredPeers[peerAddress] ?: now
            val peerInitiatorRank = discoveredPeerInitiatorRanks[peerAddress]
            val preferredInitiator = when {
                peerInitiatorRank != null -> Integer.compareUnsigned(
                    localInitiatorRank,
                    peerInitiatorRank
                ) < 0

                hasValidLocalAddress -> localAddress < peerAddress
                else -> false
            }
            val allowInboundFallbackConnect = hasInboundConnection &&
                !inboundNotifyEnabled &&
                now - inboundConnectedAt >= INBOUND_NOTIFY_GRACE_MS
            val hasTieBreaker = peerInitiatorRank != null || hasValidLocalAddress
            if (hasInboundConnection && !allowInboundFallbackConnect) {
                return@synchronized false
            }
            val hasOutboundConnection = hasActiveOutboundClientLinkLocked(
                address = peerAddress,
                now = now,
                systemSnapshot = systemSnapshot
            )
            if (hasOutboundConnection) {
                return@synchronized false
            }
            val pendingOutboundCount = countPendingOutboundConnectsLocked(
                now = now,
                excludingAddress = peerAddress
            )
            // Mesh handshake stays stable only when outbound attempts are serialized.
            if (pendingOutboundCount >= MAX_PENDING_OUTBOUND_CONNECTS && !allowInboundFallbackConnect) {
                return@synchronized false
            }
            val outboundLinkCount = countActiveOutboundClientLinksLocked(excludingAddress = peerAddress)
            if (outboundLinkCount >= MAX_OUTBOUND_CLIENT_LINKS && !allowInboundFallbackConnect) {
                return@synchronized false
            }
            val allowQueuedPayloadInitiatorHandover =
                queuedPayloadHandoverAllowed &&
                    oldestPendingOutboundTimestamp != null &&
                    !hasInboundConnection &&
                    !hasOutboundConnection &&
                    now - oldestPendingOutboundTimestamp >= QUEUED_OUTBOUND_INITIATOR_HANDOVER_DELAY_MS
            if (hasTieBreaker) {
                if (!allowInboundFallbackConnect) {
                    // Normal case: deterministic initiator prevents dual outbound storms.
                    return@synchronized preferredInitiator || allowQueuedPayloadInitiatorHandover
                }
                // If inbound link stays non-ready for too long, hand over initiator role.
                val fallbackElapsed = now - firstSeenAt
                val preferPrimaryInitiator = fallbackElapsed < INBOUND_INITIATOR_HANDOVER_DELAY_MS
                return@synchronized if (preferPrimaryInitiator) {
                    preferredInitiator
                } else {
                    !preferredInitiator
                }
            }
            if (allowInboundFallbackConnect) {
                return@synchronized true
            }
            val baseDelay = INVALID_LOCAL_ADDRESS_CONNECT_DELAY_MS
            val jitter = computeFallbackJitterMillis(peerAddress)
            now - firstSeenAt >= baseDelay + jitter
        }
    }

    private fun computeFallbackJitterMillis(peerAddress: String): Long {
        val mixed = peerAddress.hashCode() xor localInitiatorSalt
        return (mixed.toLong() and 0x3FFL)
    }

    private fun deriveLocalInitiatorRank(seed: Int): Int {
        val baseRank = seed xor (seed ushr 16) xor 0x6F4B5D5E
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // Legacy stacks are noticeably less reliable as outbound GATT clients for multi-chunk mesh
            // traffic. Bias initiator election so newer peers own the client route when possible.
            baseRank or LEGACY_INITIATOR_RANK_PENALTY_MASK
        } else {
            baseRank and INITIATOR_RANK_VALUE_MASK
        }
    }

    private fun encodeInitiatorRank(rank: Int): ByteArray {
        return byteArrayOf(
            (rank ushr 24).toByte(),
            (rank ushr 16).toByte(),
            (rank ushr 8).toByte(),
            rank.toByte()
        )
    }

    private fun decodePeerInitiatorRank(result: ScanResult): Int? {
        val manufacturerData = result.scanRecord
            ?.getManufacturerSpecificData(INITIATOR_RANK_MANUFACTURER_ID)
            ?: return null
        if (manufacturerData.size < INITIATOR_RANK_BYTES) {
            return null
        }
        return ((manufacturerData[0].toInt() and 0xFF) shl 24) or
            ((manufacturerData[1].toInt() and 0xFF) shl 16) or
            ((manufacturerData[2].toInt() and 0xFF) shl 8) or
            (manufacturerData[3].toInt() and 0xFF)
    }

    // Must be called with lock held.
    private fun clearServerPeerLocked(address: String) {
        serverDevices.remove(address)
        serverNotifyEnabled.remove(address)
        serverConnectedAt.remove(address)
        serverPeerMtu.remove(address)
        serverPendingPayload.remove(address)
        serverNotifyTickets.remove(address)?.let { ticket ->
            ticket.status = BluetoothGatt.GATT_FAILURE
            ticket.latch.countDown()
        }
        sharedModeMeshQualifiedPeers.remove(address)
        sharedModeObservedInboundConnectedAt.remove(address)
        pendingPeerVerificationNonces.remove(address)
        pendingPeerVerificationRequestedAtMillis.remove(address)
        serverNotifyCallbackBypassPeers.remove(address)
        resetInboundChunkReceiverLocked(address, InboundTransportChannel.SERVER_WRITE)
    }

    private fun shouldSuppressForP2p(address: String): Boolean {
        val now = System.currentTimeMillis()
        if (P2pGattChatManager.ownsAddress(address)) {
            synchronized(lock) {
                p2pSuppressedPeersUntil[address] = now + P2P_CHAT_PEER_SUPPRESSION_MS
            }
            return true
        }
        return synchronized(lock) { isP2pSuppressedLocked(address, now) }
    }

    private fun isP2pSuppressedLocked(address: String, now: Long): Boolean {
        val suppressedUntil = p2pSuppressedPeersUntil[address] ?: return false
        if (now >= suppressedUntil) {
            p2pSuppressedPeersUntil.remove(address)
            return false
        }
        return true
    }

    private fun deprioritizePeerForP2pInternal(address: String, durationMs: Long) {
        val normalizedAddress = normalizeAddress(address)
        if (normalizedAddress.isBlank()) {
            return
        }
        val suppressUntil = System.currentTimeMillis() + durationMs
        var gattToClose: BluetoothGatt? = null
        synchronized(lock) {
            p2pSuppressedPeersUntil[normalizedAddress] = suppressUntil
            lastConnectAttemptMillis[normalizedAddress] = System.currentTimeMillis()
            gattToClose = clientPeers.remove(normalizedAddress)?.gatt
            sharedModeObservedInboundConnectedAt.remove(normalizedAddress)
        }
        cancelInboundFallbackConnect(normalizedAddress)
        gattToClose?.let { staleGatt ->
            disconnectGattQuietly(staleGatt)
            Log.d(TAG, "[$normalizedAddress] Released mesh outbound route because P2P chat owns peer")
        }
        publishState()
    }

    // Must be called with lock held.
    private fun inboundMeshServerAddressesLocked(): Set<String> {
        if (!usesExternalGattServer) {
            return serverDevices.keys.toSet()
        }
        return serverDevices.keys
            .filter { address ->
                address in sharedModeMeshQualifiedPeers ||
                    serverNotifyEnabled[address] == true
            }
            .toSet()
    }

    // Physical inbound links can exist before a shared-host peer qualifies for mesh traffic.
    private fun connectedInboundMeshAddressesLocked(): Set<String> {
        if (!usesExternalGattServer) {
            return serverDevices.keys.toSet()
        }
        return (serverDevices.keys + sharedModeObservedInboundConnectedAt.keys).toSet()
    }

    // Must be called with lock held.
    private fun freshSystemConnectedSnapshotLocked(now: Long): Set<String>? {
        if (!hasFreshSystemConnectedSnapshot) {
            return null
        }
        if (now - systemConnectedSnapshotAtMillis > SYSTEM_CONNECTED_SNAPSHOT_MAX_AGE_MS) {
            return null
        }
        return systemConnectedLeAddresses
    }

    @SuppressLint("MissingPermission")
    private fun captureSystemConnectedSnapshot(now: Long) {
        if (!runtimeActive || !hasBluetoothConnectPermission()) {
            synchronized(lock) {
                hasFreshSystemConnectedSnapshot = false
                systemConnectedLeAddresses = emptySet()
                systemConnectedSnapshotAtMillis = 0L
            }
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            // Android 8 BLE stacks frequently report empty GATT/GATT_SERVER snapshots during
            // active links, which makes cleanup tear down valid connections in a loop.
            synchronized(lock) {
                hasFreshSystemConnectedSnapshot = false
                systemConnectedLeAddresses = emptySet()
                systemConnectedSnapshotAtMillis = 0L
            }
            return
        }
        val manager = bluetoothManager ?: run {
            synchronized(lock) {
                hasFreshSystemConnectedSnapshot = false
                systemConnectedLeAddresses = emptySet()
                systemConnectedSnapshotAtMillis = 0L
            }
            return
        }
        val snapshot = run {
            val addresses = linkedSetOf<String>()
            var hasAnySnapshot = false
            runCatching { manager.getConnectedDevices(BluetoothProfile.GATT) }
                .getOrNull()
                ?.also { devices ->
                    hasAnySnapshot = true
                    devices.forEach { device ->
                        addresses += normalizeAddress(device.address)
                    }
                }
            runCatching { manager.getConnectedDevices(BluetoothProfile.GATT_SERVER) }
                .getOrNull()
                ?.also { devices ->
                    hasAnySnapshot = true
                    devices.forEach { device ->
                        addresses += normalizeAddress(device.address)
                    }
                }
            if (hasAnySnapshot) addresses.toSet() else null
        }
        synchronized(lock) {
            if (snapshot == null) {
                hasFreshSystemConnectedSnapshot = false
                return@synchronized
            }
            val hasInternalLinks = serverDevices.isNotEmpty() || clientPeers.values.any { peer ->
                peer.gatt != null
            }
            if (snapshot.isEmpty() && hasInternalLinks) {
                // Treat all-empty snapshots as stale while we still track links locally.
                // On some vendor stacks this transiently appears even though callbacks are healthy.
                hasFreshSystemConnectedSnapshot = false
                return@synchronized
            }
            hasFreshSystemConnectedSnapshot = true
            systemConnectedLeAddresses = snapshot
            systemConnectedSnapshotAtMillis = now
        }
    }

    // Must be called with lock held.
    private fun countActiveOutboundClientLinksLocked(excludingAddress: String? = null): Int {
        return clientPeers.values.count { peer ->
            peer.address != excludingAddress && peer.gatt != null
        }
    }

    private fun outboundConnectPendingGraceMillis(hasQueuedPayload: Boolean = false): Long {
        return when {
            hasQueuedPayload && Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 ->
                QUEUED_OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS

            hasQueuedPayload -> QUEUED_OUTBOUND_CONNECT_PENDING_GRACE_MS
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 -> OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS
            else -> OUTBOUND_CONNECT_PENDING_GRACE_MS
        }
    }

    // Must be called with lock held.
    private fun hasFreshPeerPresenceLocked(
        address: String,
        systemSnapshot: Set<String>?
    ): Boolean {
        return address in discoveredDevices ||
            address in serverDevices ||
            address in sharedModeObservedInboundConnectedAt ||
            systemSnapshot?.contains(address) == true
    }

    // Must be called with lock held.
    private fun outboundConnectPendingGraceMillisLocked(
        address: String,
        systemSnapshot: Set<String>?,
        hasQueuedPayload: Boolean = false
    ): Long {
        val baseGraceMillis = outboundConnectPendingGraceMillis(hasQueuedPayload = hasQueuedPayload)
        if (!hasFreshPeerPresenceLocked(address = address, systemSnapshot = systemSnapshot)) {
            return baseGraceMillis
        }
        val rediscoveredGraceMillis = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            REDISCOVERED_OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS
        } else {
            REDISCOVERED_OUTBOUND_CONNECT_PENDING_GRACE_MS
        }
        return minOf(baseGraceMillis, rediscoveredGraceMillis)
    }

    // Must be called with lock held.
    private fun hasActiveOutboundClientLinkLocked(
        address: String,
        now: Long,
        systemSnapshot: Set<String>?
    ): Boolean {
        val peer = clientPeers[address] ?: return false
        val hasGatt = peer.gatt != null
        if (!hasGatt) {
            return false
        }
        if (peer.connected) {
            return systemSnapshot == null || address in systemSnapshot
        }
        val attemptedAt = lastConnectAttemptMillis[address] ?: return true
        return now - attemptedAt < outboundConnectPendingGraceMillisLocked(
            address = address,
            systemSnapshot = systemSnapshot,
            hasQueuedPayload = pendingOutboundPackets.isNotEmpty()
        )
    }

    // Must be called with lock held.
    private fun hasUsableOrPendingOutboundClientRouteLocked(
        address: String,
        now: Long,
        systemSnapshot: Set<String>?,
        queuedRecovery: Boolean = false
    ): Boolean {
        val peer = clientPeers[address] ?: return false
        if (peer.gatt == null) {
            return false
        }
        val attemptedAt = lastConnectAttemptMillis[address] ?: now
        if (!peer.connected) {
            return now - attemptedAt < outboundConnectPendingGraceMillisLocked(
                address = address,
                systemSnapshot = systemSnapshot,
                hasQueuedPayload = queuedRecovery
            )
        }
        if (systemSnapshot != null && address !in systemSnapshot) {
            return false
        }
        if (peer.ready) {
            return true
        }
        val nonReadyGraceMillis = if (queuedRecovery) {
            QUEUED_OUTBOUND_STALE_CLIENT_CONNECT_MS
        } else {
            STALE_CLIENT_CONNECT_MS
        }
        return now - attemptedAt < nonReadyGraceMillis
    }

    // Must be called with lock held.
    private fun hasPendingOutboundConnectLocked(now: Long): Boolean {
        val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
        val hasQueuedPayload = pendingOutboundPackets.isNotEmpty()
        return clientPeers.values.any { peer ->
            val hasGatt = peer.gatt != null
            if (!hasGatt || peer.connected) {
                false
            } else {
                val attemptedAt = lastConnectAttemptMillis[peer.address] ?: now
                now - attemptedAt < outboundConnectPendingGraceMillisLocked(
                    address = peer.address,
                    systemSnapshot = systemSnapshot,
                    hasQueuedPayload = hasQueuedPayload
                )
            }
        }
    }

    // Must be called with lock held.
    private fun countPendingOutboundConnectsLocked(
        now: Long,
        excludingAddress: String? = null
    ): Int {
        val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
        val hasQueuedPayload = pendingOutboundPackets.isNotEmpty()
        return clientPeers.values.count { peer ->
            if (peer.address == excludingAddress) {
                return@count false
            }
            val hasGatt = peer.gatt != null
            if (!hasGatt || peer.connected) {
                return@count false
            }
            val attemptedAt = lastConnectAttemptMillis[peer.address] ?: now
            now - attemptedAt < outboundConnectPendingGraceMillisLocked(
                address = peer.address,
                systemSnapshot = systemSnapshot,
                hasQueuedPayload = hasQueuedPayload
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(
        device: BluetoothDevice,
        address: String,
        allowFailureBackoffBypass: Boolean = false
    ) {
        if (!hasBluetoothConnectPermission()) {
            return
        }
        if (shouldSuppressForP2p(address)) {
            Log.d(TAG, "[$address] Skipping mesh outbound connect because P2P chat owns peer")
            return
        }
        stopClassicDiscoveryIfRunning(reason = "connect")
        val now = System.currentTimeMillis()
        captureSystemConnectedSnapshot(now)
        var staleGattToClose: BluetoothGatt? = null
        var shouldPublishStateAfterRouteReset = false
        val shouldConnect = synchronized(lock) {
            if (isClientSendRecoveryBlockedLocked(address, now)) {
                return@synchronized false
            }
            if (isP2pSuppressedLocked(address, now)) {
                return@synchronized false
            }
            val lastAttempt = lastConnectAttemptMillis[address] ?: 0L
            if (now - lastAttempt < CONNECT_RETRY_COOLDOWN_MS) {
                return@synchronized false
            }
            val systemSnapshot = freshSystemConnectedSnapshotLocked(now)
            var inboundConnected = serverDevices.containsKey(address) ||
                sharedModeObservedInboundConnectedAt.containsKey(address)
            if (inboundConnected && systemSnapshot != null && address !in systemSnapshot) {
                inboundConnected = false
            }
            val inboundNotifyEnabled = serverNotifyEnabled[address] == true
            val inboundConnectedAt = serverConnectedAt[address] ?:
                sharedModeObservedInboundConnectedAt[address] ?:
                now
            val allowInboundFallbackConnect = inboundConnected &&
                !inboundNotifyEnabled &&
                now - inboundConnectedAt >= INBOUND_NOTIFY_GRACE_MS
            val hasQueuedOutbound = pendingOutboundPackets.isNotEmpty()
            val allowQueuedPayloadConnect = hasQueuedOutbound && allowFailureBackoffBypass
            // Avoid parallel bidirectional handshakes unless inbound link stayed non-ready long enough.
            if (inboundConnected && !allowInboundFallbackConnect && !allowQueuedPayloadConnect) {
                return@synchronized false
            }
            val failureBackoff = clientFailureBackoff[address]
            if (failureBackoff != null && now < failureBackoff.nextAllowedConnectAtMillis) {
                val canBypassFailureBackoff = allowFailureBackoffBypass &&
                    (allowInboundFallbackConnect || hasQueuedOutbound)
                if (!canBypassFailureBackoff) {
                    return@synchronized false
                }
                val remainingMs = (failureBackoff.nextAllowedConnectAtMillis - now).coerceAtLeast(0L)
                val bypassReason = if (allowInboundFallbackConnect) {
                    "inbound-fallback"
                } else {
                    "queued-outbound"
                }
                Log.w(
                    TAG,
                    "[$address] Bypassing reconnect backoff reason=$bypassReason remainingMs=$remainingMs"
                )
            }
            val pendingOutboundCount = countPendingOutboundConnectsLocked(
                now = now,
                excludingAddress = address
            )
            if (
                pendingOutboundCount >= MAX_PENDING_OUTBOUND_CONNECTS &&
                !allowInboundFallbackConnect &&
                !allowQueuedPayloadConnect
            ) {
                return@synchronized false
            }
            val outboundLinkCount = countActiveOutboundClientLinksLocked(excludingAddress = address)
            if (
                outboundLinkCount >= MAX_OUTBOUND_CLIENT_LINKS &&
                !allowInboundFallbackConnect &&
                !allowQueuedPayloadConnect
            ) {
                return@synchronized false
            }
            val existing = clientPeers[address]
            if (existing != null) {
                val existingGatt = existing.gatt
                if (existingGatt != null) {
                    val pendingGraceMillis = outboundConnectPendingGraceMillisLocked(
                        address = address,
                        systemSnapshot = systemSnapshot,
                        hasQueuedPayload = allowQueuedPayloadConnect
                    )
                    if (!existing.connected && now - lastAttempt < pendingGraceMillis) {
                        return@synchronized false
                    }
                    if (!existing.connected) {
                        Log.w(
                            TAG,
                            "[$address] Pending outbound connect timed out after ${now - lastAttempt}ms; recreating GATT"
                        )
                    }
                    val existingLooksConnectedAtSystem = existing.connected &&
                        (systemSnapshot == null || address in systemSnapshot)
                    val staleClientConnectMillis = if (allowQueuedPayloadConnect) {
                        QUEUED_OUTBOUND_STALE_CLIENT_CONNECT_MS
                    } else {
                        STALE_CLIENT_CONNECT_MS
                    }
                    if (existingLooksConnectedAtSystem && now - lastAttempt < staleClientConnectMillis) {
                        return@synchronized false
                    }
                    staleGattToClose = existingGatt
                    existing.gatt = null
                    existing.messageIn = null
                    existing.messageOut = null
                    existing.mtu = DEFAULT_ATT_MTU
                    existing.connected = false
                    existing.ready = false
                    shouldPublishStateAfterRouteReset = true
                }
                lastConnectAttemptMillis[address] = now
                return@synchronized true
            }
            clientPeers[address] = ClientPeer(address = address)
            lastConnectAttemptMillis[address] = now
            true
        }
        staleGattToClose?.let { staleGatt ->
            disconnectGattQuietly(staleGatt)
        }
        if (shouldPublishStateAfterRouteReset) {
            publishState()
        }
        if (!shouldConnect) {
            return
        }

        serviceScope.launch {
            BleConnectQueue.enqueue(address) {
                val backoffAttempt = synchronized(lock) {
                    clientFailureBackoff[address]?.attempt ?: 0
                }
                val knownPeer = BleKnownPeersStore.isKnown(applicationContext, address)
                val useAutoConnect = synchronized(lock) {
                    val hasPendingUserTraffic = pendingOutboundPackets.isNotEmpty()
                    val systemSnapshot = freshSystemConnectedSnapshotLocked(System.currentTimeMillis())
                    val hasFreshPresence = hasFreshPeerPresenceLocked(
                        address = address,
                        systemSnapshot = systemSnapshot
                    )
                    val shouldUseAutoConnectRecovery = knownPeer &&
                        backoffAttempt >= AUTO_CONNECT_RECOVERY_FAILURE_THRESHOLD &&
                        !allowFailureBackoffBypass &&
                        !hasPendingUserTraffic &&
                        !hasFreshPresence &&
                        Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1
                    shouldUseAutoConnectRecovery || (
                        knownPeer &&
                        backoffAttempt > 0 &&
                        !allowFailureBackoffBypass &&
                        !hasPendingUserTraffic &&
                        !hasFreshPresence &&
                        Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1
                    )
                }
                if (useAutoConnect) {
                    if (hasBluetoothScanPermission() && !BleScanCoordinator.isActive(profile.scanCoordinatorOwner)) {
                        startScanLoop()
                    }
                    Log.d(TAG, "[$address] Keeping BLE scan active for autoConnect recovery")
                } else {
                    // Scanning and direct connectGatt at the same moment is unstable on some stacks
                    // (frequent 133/147). Keep the scan running for autoConnect recovery because the
                    // platform may rely on passive discovery before finishing the background connect.
                    val scanPaused = pauseLeScanForConnect(address)
                    if (scanPaused) {
                        // Some stacks need a brief settle window after scan stop before connectGatt.
                        SystemClock.sleep(SCAN_STOP_SETTLE_BEFORE_CONNECT_MS)
                    }
                }
                Log.d(
                    TAG,
                    "[$address] Outbound connect attempt autoConnect=$useAutoConnect knownPeer=$knownPeer " +
                        "backoffAttempt=$backoffAttempt fallbackBypass=$allowFailureBackoffBypass"
                )
                val gatt = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        device.connectGatt(
                            this@GattMeshForegroundService,
                            useAutoConnect,
                            clientCallback,
                            BluetoothDevice.TRANSPORT_LE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        device.connectGatt(this@GattMeshForegroundService, useAutoConnect, clientCallback)
                    }
                }.getOrNull()

                if (gatt == null) {
                    synchronized(lock) { clientPeers.remove(address) }
                    publishState()
                    return@enqueue
                }

                synchronized(lock) {
                    clientPeers[address]?.apply {
                        this.gatt = gatt
                        this.connected = false
                        this.cacheRefreshAttempted = false
                    }
                }
                publishState()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pauseLeScanForConnect(address: String): Boolean {
        if (!hasBluetoothScanPermission()) {
            return false
        }
        if (!BleScanCoordinator.isActive(profile.scanCoordinatorOwner)) {
            return false
        }
        val paused = BleScanCoordinator.setPaused(profile.scanCoordinatorOwner, paused = true)
        if (!paused) {
            return false
        }
        Log.d(TAG, "[$address] Paused BLE scan during outbound connect attempt")
        publishState()
        scheduleLeScanResume()
        return true
    }

    private fun scheduleLeScanResume() {
        scanResumeJob?.cancel()
        scanResumeJob = serviceScope.launch {
            while (runtimeActive) {
                delay(SCAN_RESUME_CHECK_INTERVAL_MS)
                if (!runtimeActive || BleScanCoordinator.isActive(profile.scanCoordinatorOwner) || !hasBluetoothScanPermission()) {
                    return@launch
                }
                val shouldKeepPaused = synchronized(lock) {
                    hasPendingOutboundConnectLocked(System.currentTimeMillis())
                }
                if (shouldKeepPaused) {
                    continue
                }
                startScanLoop()
                return@launch
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleInboundFallbackConnect(device: BluetoothDevice, address: String) {
        cancelInboundFallbackConnect(address)
        val job = serviceScope.launch {
            // Let peer finish service discovery + CCCD before forcing an outbound fallback.
            delay(INBOUND_NOTIFY_GRACE_MS)
            if (!runtimeActive || !hasBluetoothConnectPermission()) {
                return@launch
            }
            if (usesExternalGattServer && GattSOSServerService.ownsActiveSosPeer(address)) {
                synchronized(lock) {
                    sharedModeObservedInboundConnectedAt.remove(address)
                }
                Log.d(TAG, "[$address] Skipping mesh fallback connect because SOS session owns peer")
                return@launch
            }
            if (shouldSuppressForP2p(address)) {
                Log.d(TAG, "[$address] Skipping mesh fallback connect because P2P chat owns peer")
                return@launch
            }
            val shouldFallbackConnect = synchronized(lock) {
                (serverDevices.containsKey(address) || sharedModeObservedInboundConnectedAt.containsKey(address)) &&
                    serverNotifyEnabled[address] != true &&
                    !isP2pSuppressedLocked(address, System.currentTimeMillis()) &&
                    clientPeers[address]?.gatt == null
            }
            if (!shouldFallbackConnect) {
                return@launch
            }
            if (!shouldInitiateConnection(address)) {
                Log.d(
                    TAG,
                    "[$address] Skipping inbound fallback connect; peer currently owns initiator role"
                )
                return@launch
            }
            Log.d(TAG, "[$address] Inbound link has no CCCD; trying outbound fallback connect")
            connectToPeer(
                device = device,
                address = address,
                allowFailureBackoffBypass = true
            )
        }
        synchronized(lock) {
            inboundFallbackJobs[address] = job
        }
    }

    private fun cancelInboundFallbackConnect(address: String) {
        val job = synchronized(lock) { inboundFallbackJobs.remove(address) }
        job?.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun stopClassicDiscoveryIfRunning(reason: String): Boolean {
        val adapter = bluetoothAdapter ?: return false
        if (!hasBluetoothScanPermission()) {
            return false
        }
        if (BluetoothClassicDiscoveryGuard.isHeld()) {
            Log.d(TAG, "Skipping classic discovery cancellation due to external guard ($reason)")
            return false
        }
        return runCatching {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
                Log.d(TAG, "Cancelled classic discovery while running GATT mesh ($reason)")
                true
            } else {
                false
            }
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun scheduleServiceDiscoveryRetry(
        address: String,
        gatt: BluetoothGatt,
        reason: String
    ): Boolean {
        if (!runtimeActive || !hasBluetoothConnectPermission()) {
            return false
        }
        val nextRetry = synchronized(lock) {
            val peer = clientPeers[address] ?: return@synchronized null
            if (peer.gatt !== gatt) {
                return@synchronized null
            }
            if (peer.serviceDiscoveryRetries >= MAX_SERVICE_DISCOVERY_RETRIES) {
                return@synchronized null
            }
            peer.serviceDiscoveryRetries += 1
            peer.ready = false
            peer.messageIn = null
            peer.messageOut = null
            peer.serviceDiscoveryRetries
        } ?: return false

        Log.w(
            TAG,
            "[$address] Service discovery incomplete ($reason), retry $nextRetry/$MAX_SERVICE_DISCOVERY_RETRIES"
        )

        serviceScope.launch {
            delay(SERVICE_DISCOVERY_RETRY_DELAY_MS)
            val canRetry = synchronized(lock) {
                clientPeers[address]?.gatt === gatt
            }
            if (!runtimeActive || !canRetry || !hasBluetoothConnectPermission()) {
                return@launch
            }
            val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
            if (!started) {
                Log.w(TAG, "[$address] discoverServices retry could not be started")
                closeClientPeer(address, expectedGatt = gatt)
            }
        }
        publishState()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun attemptClientGattCacheRefresh(
        address: String,
        gatt: BluetoothGatt,
        discoveredServices: List<String>
    ): Boolean {
        if (!runtimeActive || !hasBluetoothConnectPermission()) {
            return false
        }
        val shouldRefresh = synchronized(lock) {
            val peer = clientPeers[address] ?: return@synchronized false
            if (peer.gatt !== gatt) {
                return@synchronized false
            }
            if (peer.cacheRefreshAttempted) {
                return@synchronized false
            }
            peer.cacheRefreshAttempted = true
            peer.serviceDiscoveryRetries = 0
            peer.ready = false
            peer.messageIn = null
            peer.messageOut = null
            true
        }
        if (!shouldRefresh) {
            return false
        }

        val discoveredSummary = discoveredServices.joinToString(separator = ", ")
            .ifBlank { "none" }
        Log.w(
            TAG,
            "[$address] Mesh service missing; refreshing GATT cache. discovered=[$discoveredSummary]"
        )
        refreshGattCache(gatt)
        serviceScope.launch {
            delay(SERVICE_DISCOVERY_RETRY_DELAY_MS * 2)
            val canRetry = synchronized(lock) {
                clientPeers[address]?.gatt === gatt
            }
            if (!runtimeActive || !canRetry || !hasBluetoothConnectPermission()) {
                return@launch
            }
            val started = runCatching { gatt.discoverServices() }.getOrDefault(false)
            if (!started) {
                Log.w(TAG, "[$address] discoverServices after cache refresh could not be started")
                closeClientPeer(address, expectedGatt = gatt)
            }
        }
        publishState()
        return true
    }

    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val method = gatt.javaClass.getMethod("refresh")
            method.isAccessible = true
            method.invoke(gatt)
            Log.d(TAG, "[${normalizeAddress(gatt.device.address)}] GATT cache refresh invoked")
        }.onFailure { error ->
            Log.w(TAG, "[${normalizeAddress(gatt.device.address)}] Unable to refresh GATT cache", error)
        }
    }

    private fun recordClientConnectionFailure(address: String, status: Int) {
        val now = System.currentTimeMillis()
        val (attempt, nextRetryAt, jitterMs) = synchronized(lock) {
            val previous = clientFailureBackoff[address]
            val nextAttempt = if (
                previous == null ||
                    now - previous.lastFailureAtMillis > CLIENT_FAILURE_RESET_WINDOW_MS
            ) {
                1
            } else {
                (previous.attempt + 1).coerceAtMost(MAX_CLIENT_FAILURE_BACKOFF_STEP)
            }
            val baseDelay = when (status) {
                147 -> CLIENT_FAILURE_BASE_DELAY_STATUS_147_MS
                else -> CLIENT_FAILURE_BASE_DELAY_GENERIC_MS
            }
            // Per-peer deterministic jitter prevents both sides retrying in lockstep collisions.
            val jitter = computeClientFailureJitterMillis(
                address = address,
                status = status,
                attempt = nextAttempt
            )
            val computedDelay = (baseDelay * (1L shl (nextAttempt - 1)) + jitter)
                .coerceAtMost(CLIENT_FAILURE_MAX_DELAY_MS)
            val nextAllowedAt = now + computedDelay
            clientFailureBackoff[address] = ClientFailureBackoff(
                attempt = nextAttempt,
                status = status,
                lastFailureAtMillis = now,
                nextAllowedConnectAtMillis = nextAllowedAt
            )
            Triple(nextAttempt, nextAllowedAt, jitter)
        }
        Log.w(
            TAG,
            "[$address] Client reconnect backoff applied status=$status attempt=$attempt " +
                "jitterMs=$jitterMs retryInMs=${(nextRetryAt - now).coerceAtLeast(0L)}"
        )
    }

    private fun computeClientFailureJitterMillis(
        address: String,
        status: Int,
        attempt: Int
    ): Long {
        val maxJitter = when (status) {
            147 -> CLIENT_FAILURE_STATUS_147_JITTER_MS
            133 -> CLIENT_FAILURE_STATUS_133_JITTER_MS
            else -> CLIENT_FAILURE_GENERIC_JITTER_MS
        }
        if (maxJitter <= 0L) {
            return 0L
        }
        val mixed = address.hashCode() xor localInitiatorSalt xor (status shl 5) xor
            (attempt * 1103515245)
        return Math.floorMod(mixed, (maxJitter + 1).toInt()).toLong()
    }

    private fun registerClassicDiscoveryReceiver() {
        if (classicDiscoveryReceiverRegistered) {
            return
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
        val receiverRegistered = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(classicDiscoveryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(classicDiscoveryReceiver, filter)
            }
            true
        }.getOrDefault(false)
        classicDiscoveryReceiverRegistered = receiverRegistered
    }

    private fun unregisterClassicDiscoveryReceiver() {
        if (!classicDiscoveryReceiverRegistered) {
            return
        }
        runCatching {
            unregisterReceiver(classicDiscoveryReceiver)
        }
        classicDiscoveryReceiverRegistered = false
    }

    private fun shouldHandleClientGattCallback(
        address: String,
        gatt: BluetoothGatt,
        callback: String
    ): Boolean {
        val isCurrent = synchronized(lock) {
            clientPeers[address]?.gatt === gatt
        }
        if (!isCurrent) {
            Log.d(TAG, "[$address] Ignoring stale client callback=$callback")
        }
        return isCurrent
    }

    @SuppressLint("MissingPermission")
    private fun closeClientPeer(address: String, expectedGatt: BluetoothGatt? = null) {
        val (peer, pendingTicket, settleJob) = synchronized(lock) {
            val currentPeer = clientPeers[address]
            if (expectedGatt != null && currentPeer?.gatt !== expectedGatt) {
                return
            }
            val removableTicket = clientWriteTickets[address]?.takeIf { ticket ->
                expectedGatt == null || ticket.gatt === expectedGatt
            }
            if (removableTicket != null) {
                clientWriteTickets.remove(address)
            }
            resetInboundChunkReceiverLocked(address, InboundTransportChannel.CLIENT_NOTIFICATION)
            pendingPeerVerificationNonces.remove(address)
            pendingPeerVerificationRequestedAtMillis.remove(address)
            identityAnnouncementPendingForPeers.remove(address)
            securityRecoveryAttemptAtMillis.remove(address)
            resetClientWritePreferencesLocked(address = address, clearWriteOnlyBypass = false)
            clientPacketQuietUntilElapsedRealtimeMs.remove(address)
            Triple(
                clientPeers.remove(address),
                removableTicket,
                clientNotificationSettleJobs.remove(address)
            )
        }
        settleJob?.cancel()
        pendingTicket?.let { ticket ->
            ticket.status = BluetoothGatt.GATT_FAILURE
            ticket.latch.countDown()
        }
        if (peer == null) {
            return
        }
        disconnectGattQuietly(peer.gatt)
        publishState()
        scheduleLeScanResume()
    }

    // Learned write-mode hints belong to one link lifetime; reconnects should start clean
    // unless a peer explicitly remains in write-only mode.
    private fun resetClientWritePreferencesLocked(
        address: String,
        clearWriteOnlyBypass: Boolean
    ) {
        clientNoResponsePreferredPeers.remove(address)
        clientWithResponsePreferredUntilElapsedRealtimeMs.remove(address)
        clientNoResponseSettleOverridesMs.remove(address)
        if (clearWriteOnlyBypass) {
            clientNotificationBypassPeers.remove(address)
        }
    }

    private fun scheduleReconnectAfterClientFailure(
        address: String,
        device: BluetoothDevice
    ) {
        if (!runtimeActive) {
            return
        }
        val now = System.currentTimeMillis()
        val (reconnectDevice, delayMillis) = synchronized(lock) {
            val retryAt = clientFailureBackoff[address]?.nextAllowedConnectAtMillis ?: return
            val candidate = discoveredDevices[address] ?: serverDevices[address] ?: device
            clientReconnectJobs.remove(address)?.cancel()
            candidate to (retryAt - now).coerceAtLeast(0L)
        }
        lateinit var reconnectJob: Job
        reconnectJob = serviceScope.launch {
            try {
                if (delayMillis > 0L) {
                    delay(delayMillis)
                }
                if (!runtimeActive || !hasBluetoothConnectPermission()) {
                    return@launch
                }
                val hasQueuedOutbound = synchronized(lock) {
                    pendingOutboundPackets.isNotEmpty()
                }
                if (!shouldInitiateConnection(address, queuedPayloadHandoverAllowed = hasQueuedOutbound)) {
                    return@launch
                }
                connectToPeer(device = reconnectDevice, address = address)
            } finally {
                synchronized(lock) {
                    clientReconnectJobs.remove(address, reconnectJob)
                }
            }
        }
        synchronized(lock) {
            clientReconnectJobs[address] = reconnectJob
        }
    }

    private fun recoverFromFailedClientSend(
        address: String,
        gatt: BluetoothGatt,
        reason: String
    ) {
        val shouldReset = synchronized(lock) {
            clientPeers[address]?.gatt === gatt
        }
        if (!shouldReset) {
            return
        }
        if (synchronized(lock) { address in clientNotificationBypassPeers || address in clientNoResponsePreferredPeers }) {
            escalateClientNoResponseSettle(address = address, reason = reason)
        }
        val reconnectDelayMillis = synchronized(lock) {
            val blockedUntil = System.currentTimeMillis() + clientSendRecoveryCooldownMillis()
            clientSendRecoveryBlockedUntilMillis[address] = blockedUntil
            (blockedUntil - System.currentTimeMillis()).coerceAtLeast(CLIENT_SEND_FAILURE_RECONNECT_DELAY_MS)
        }
        Log.w(TAG, "[$address] Resetting client route after failed send reason=$reason")
        closeClientPeer(address, expectedGatt = gatt)
        val reconnectDevice = synchronized(lock) {
            discoveredDevices[address] ?: serverDevices[address]
        } ?: gatt.device
        serviceScope.launch {
            delay(reconnectDelayMillis)
            if (!runtimeActive || !hasBluetoothConnectPermission()) {
                return@launch
            }
            connectToPeer(
                device = reconnectDevice,
                address = address,
                allowFailureBackoffBypass = true
            )
        }
    }

    private fun escalateClientNoResponseSettle(address: String, reason: String) {
        val updated = synchronized(lock) {
            val current = clientNoResponseSettleOverridesMs[address] ?: INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS
            val next = when {
                current < 900L -> 900L
                else -> (current + WRITE_ONLY_NO_RESPONSE_SETTLE_STEP_MS)
                    .coerceAtMost(MAX_WRITE_ONLY_NO_RESPONSE_SETTLE_MS)
            }
            clientNoResponseSettleOverridesMs[address] = next
            next
        }
        Log.w(TAG, "[$address] Increasing write-only no-response settle to ${updated}ms reason=$reason")
    }

    private fun isIncomingWriteCapable(incoming: BluetoothGattCharacteristic?): Boolean {
        if (incoming == null) return false
        return incoming.properties and (
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
            ) != 0
    }

    private fun supportsOutgoingNotifications(outgoing: BluetoothGattCharacteristic): Boolean {
        val outgoingNotifiable = outgoing.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val hasCcc = outgoing.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) != null
        return outgoingNotifiable && hasCcc
    }

    @SuppressLint("MissingPermission")
    private fun enableClientNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val localEnabled = runCatching {
            gatt.setCharacteristicNotification(characteristic, true)
        }.getOrDefault(false)
        if (!localEnabled) {
            return false
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                gatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            }.getOrDefault(false)
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                run {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }.getOrDefault(false)
        }
    }

    private fun handleIncomingPacket(
        payload: ByteArray,
        sourceAddress: String,
        transportChannel: InboundTransportChannel
    ): Boolean {
        if (payload.isEmpty() || payload.size > MAX_TRANSPORT_PACKET_BYTES) {
            Log.w(
                TAG,
                "[$sourceAddress] Dropped inbound payload bytes=${payload.size} channel=${transportChannel.logTag} reason=invalid-size"
            )
            return false
        }
        if (!consumeInboundBudget(sourceAddress)) {
            Log.w(
                TAG,
                "[$sourceAddress] Dropped inbound payload bytes=${payload.size} channel=${transportChannel.logTag} reason=rate-limit"
            )
            return false
        }

        // Backward-compatible fast-path for single-packet peers (legacy behavior).
        decodePacket(payload)?.let { packet ->
            Log.d(
                TAG,
                "[$sourceAddress] Decoded direct packet id=${packet.id} channel=${transportChannel.logTag} bytes=${payload.size}"
            )
            resetInboundChunkReceiver(sourceAddress, transportChannel)
            return processDecodedPacket(packet, sourceAddress)
        }

        when (
            val reassembly = reassembleTransportPayload(
                sourceAddress = sourceAddress,
                transportChannel = transportChannel,
                chunk = payload
            )
        ) {
            TransportReassemblyResult.Incomplete -> {
                Log.d(
                    TAG,
                    "[$sourceAddress] Inbound chunk awaiting reassembly channel=${transportChannel.logTag} bytes=${payload.size}"
                )
                return true
            }

            is TransportReassemblyResult.Rejected -> {
                Log.w(
                    TAG,
                    "[$sourceAddress] Rejected inbound transport chunk channel=${transportChannel.logTag} reason=${reassembly.reason}"
                )
                return false
            }

            is TransportReassemblyResult.Complete -> {
                val reassembledPayload = reassembly.payload
                val packet = decodePacket(reassembledPayload) ?: run {
                    Log.w(
                        TAG,
                        "[$sourceAddress] Failed to decode reassembled payload channel=${transportChannel.logTag} bytes=${reassembledPayload.size}"
                    )
                    return false
                }
                Log.d(
                    TAG,
                    "[$sourceAddress] Decoded reassembled packet id=${packet.id} channel=${transportChannel.logTag} bytes=${reassembledPayload.size}"
                )
                return processDecodedPacket(packet, sourceAddress)
            }
        }
    }

    private fun processDecodedPacket(packet: MeshPacket, sourceAddress: String): Boolean {
        if (!isPacketTimestampValid(packet.timestampMillis)) {
            Log.w(
                TAG,
                "[$sourceAddress] Dropped packet id=${packet.id} reason=invalid-timestamp packetTs=${packet.timestampMillis} now=${System.currentTimeMillis()}"
            )
            return false
        }
        val originVerification = if (packet.type == MeshPacketType.CHAT) {
            resolveMessageOriginVerification(
                packet = packet,
                sourceAddress = sourceAddress
            )
        } else {
            null
        }
        if (!rememberMessageId(packet.id, packet.timestampMillis)) {
            if (packet.type == MeshPacketType.CHAT) {
                updateRemoteChatMessageMetadata(
                    packet = packet,
                    sourceAddress = sourceAddress,
                    originVerification = originVerification
                )
            }
            Log.d(TAG, "[$sourceAddress] Dropped duplicate packet id=${packet.id}")
            return false
        }
        val senderLabelChanged = if (packet.hop == 0) {
            synchronized(lock) {
                val previous = peerSenderLabels[sourceAddress]
                if (previous == packet.senderLabel) {
                    false
                } else {
                    peerSenderLabels[sourceAddress] = packet.senderLabel
                    true
                }
            }
        } else {
            false
        }

        when (packet.type) {
            MeshPacketType.CHAT -> {
                if (!packet.isReadable) {
                    Log.w(
                        TAG,
                        "[$sourceAddress] Forwarding encrypted chat packet id=${packet.id} without local decrypt"
                    )
                } else {
                    val receivedAtMillis = System.currentTimeMillis()
                    val inserted = chatStore.appendRemoteMessage(
                        id = packet.id,
                        text = packet.message,
                        senderLabel = packet.senderLabel,
                        sourceAddress = sourceAddress,
                        originVerifiedRole = originVerification?.role,
                        originVerifiedAtMillis = originVerification?.verifiedAtMillis,
                        timestampMillis = packet.timestampMillis,
                        receivedTimestampMillis = receivedAtMillis,
                    )
                    if (inserted) {
                        maybeNotifyIncomingGattMeshMessage(packet)
                    } else {
                        updateRemoteChatMessageMetadata(
                            packet = packet,
                            sourceAddress = sourceAddress,
                            originVerification = originVerification
                        )
                    }
                    persistRemoteChatPacket(
                        packet = packet,
                        sourceAddress = sourceAddress,
                        receivedAtMillis = receivedAtMillis,
                        originVerification = originVerification
                    )
                    if (!chatStore.isChatOpen()) {
                        queueDeliveryReceipt(packet.id)
                    }
                    Log.d(TAG, "[$sourceAddress] Accepted chat packet id=${packet.id} hop=${packet.hop}")
                }
            }

            MeshPacketType.RECEIPT -> {
                val receiptIds = packet.receiptMessageIds
                if (receiptIds.isNotEmpty()) {
                    val receiptRecipientLabel = resolveReceiptTrackingRecipientLabel(
                        packet = packet,
                        sourceAddress = sourceAddress
                    )
                    when (packet.receiptType) {
                        ReceiptType.DELIVERED -> chatStore.markDelivered(
                            messageIds = receiptIds,
                            recipientLabel = receiptRecipientLabel
                        )

                        ReceiptType.READ -> chatStore.markRead(
                            messageIds = receiptIds,
                            recipientLabel = receiptRecipientLabel
                        )
                    }
                    persistReceiptUpdate(
                        type = packet.receiptType,
                        messageIds = receiptIds,
                        recipientLabel = receiptRecipientLabel
                    )
                }
                Log.d(
                    TAG,
                    "[$sourceAddress] Accepted receipt packet id=${packet.id} type=${packet.receiptType.name} " +
                        "receiptIds=${receiptIds.take(3)} count=${receiptIds.size} hop=${packet.hop}"
                )
            }

            MeshPacketType.AUTH_CHALLENGE -> {
                packet.authNonce?.let { nonce ->
                    respondToPeerVerificationChallenge(
                        sourceAddress = sourceAddress,
                        nonce = nonce
                    )
                }
                Log.d(
                    TAG,
                    "[$sourceAddress] Accepted auth challenge packet id=${packet.id} hop=${packet.hop}"
                )
            }

            MeshPacketType.AUTH_PROOF -> {
                val nonce = packet.authNonce
                val proofJson = packet.authProofJson
                if (!nonce.isNullOrBlank() && !proofJson.isNullOrBlank()) {
                    handlePeerVerificationProof(
                        sourceAddress = sourceAddress,
                        nonce = nonce,
                        proofJson = proofJson
                    )
                }
                Log.d(
                    TAG,
                    "[$sourceAddress] Accepted auth proof packet id=${packet.id} hop=${packet.hop}"
                )
            }

            MeshPacketType.IMAGE_INIT -> handleInboundImageInit(packet, sourceAddress)

            MeshPacketType.IMAGE_CHUNK -> handleInboundImageChunk(packet, sourceAddress)

            MeshPacketType.IMAGE_DONE -> handleInboundImageDone(packet, sourceAddress)
        }
        if (senderLabelChanged) {
            publishState()
        }

        if (
            packet.type == MeshPacketType.AUTH_CHALLENGE ||
            packet.type == MeshPacketType.AUTH_PROOF ||
            // Image blobs are deliberately single-hop: relaying hundreds of chunk packets through
            // store-and-forward would flood the mesh.
            packet.type == MeshPacketType.IMAGE_INIT ||
            packet.type == MeshPacketType.IMAGE_CHUNK ||
            packet.type == MeshPacketType.IMAGE_DONE ||
            packet.hop >= MAX_FORWARD_HOPS
        ) {
            return true
        }
        queueRelayPacket(packet.copy(hop = packet.hop + 1), excludeAddress = sourceAddress)
        return true
    }

    private fun observeGattMeshNotificationSettings() {
        serviceScope.launch {
            applicationContext.settingsDataStore.data.collect { prefs ->
                gattMeshNotificationsEnabled = prefs[GATT_MESH_NOTIFICATIONS_ENABLED_KEY] ?: true
            }
        }
    }

    private fun maybeNotifyIncomingGattMeshMessage(packet: MeshPacket) {
        if (!gattMeshNotificationsEnabled) {
            return
        }
        val sender = packet.senderLabel
            .trim()
            .take(MAX_SENDER_LABEL_LENGTH)
            .ifEmpty { getString(R.string.rescue_unknown_user) }
        val normalizedMessage = GattMeshTextSanitizer.sanitize(
            raw = packet.message,
            maxLength = MAX_CHAT_MESSAGE_LENGTH_CHARS,
            collapseWhitespace = true
        )
        val notificationBody = if (normalizedMessage.isEmpty()) {
            sender
        } else {
            "$sender: $normalizedMessage"
        }
        BleMessageNotifier.notifyIncoming(
            context = applicationContext,
            sessionCode = chatStore.sessionCode,
            contactName = getString(R.string.mesh_chat_general_title),
            body = notificationBody
        )
    }

    private fun persistRemoteChatPacket(
        packet: MeshPacket,
        sourceAddress: String,
        receivedAtMillis: Long,
        originVerification: MessageOriginVerification?
    ) {
        serviceScope.launch {
            runCatching {
                ensureGattMeshGeneralContact()
                val inserted = saveRemoteMessage(
                    context = applicationContext,
                    sessionCode = chatStore.sessionCode,
                    uuid = packet.id,
                    text = packet.message,
                    createdAtMillis = packet.timestampMillis,
                    receivedAtMillis = receivedAtMillis,
                    senderDisplayName = packet.senderLabel,
                    senderAddress = sourceAddress,
                    originVerifiedRole = originVerification?.role,
                    originVerifiedAtMillis = originVerification?.verifiedAtMillis
                )
                if (!inserted) {
                    updateRemoteMessageMetadata(
                        context = applicationContext,
                        uuid = packet.id,
                        senderDisplayName = packet.senderLabel,
                        senderAddress = sourceAddress,
                        originVerifiedRole = originVerification?.role,
                        originVerifiedAtMillis = originVerification?.verifiedAtMillis
                    )
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to persist incoming gatt mesh message id=${packet.id}", throwable)
            }
        }
    }

    private fun persistLocalChatPacketState(
        packet: MeshPacket,
        status: MessageDeliveryStatus,
        outboundAddresses: Collection<String>
    ) {
        serviceScope.launch {
            runCatching {
                ensureGattMeshGeneralContact()
                upsertLocalTextMessage(
                    context = applicationContext,
                    sessionCode = chatStore.sessionCode,
                    uuid = packet.id,
                    text = packet.message,
                    deliveryStatus = status,
                    outboundRoute = outboundAddresses
                        .map(String::trim)
                        .filter { address -> address.isNotEmpty() }
                        .distinct()
                        .joinToString(",")
                        .takeIf { route -> route.isNotEmpty() }
                )
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "Unable to persist local gatt mesh message id=${packet.id} status=$status",
                    throwable
                )
            }
        }
    }

    private fun persistSentToUpdate(
        messageId: String,
        recipients: Collection<String>
    ) {
        if (recipients.isEmpty()) {
            return
        }
        serviceScope.launch {
            runCatching {
                ensureGattMeshGeneralContact()
                updateLocalMessageSentToRecipients(
                    context = applicationContext,
                    uuid = messageId,
                    recipients = recipients
                )
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "Unable to persist gatt mesh sent-to tracking id=$messageId recipients=${recipients.size}",
                    throwable
                )
            }
        }
    }

    private fun persistReceiptUpdate(
        type: ReceiptType,
        messageIds: Collection<String>,
        recipientLabel: String?
    ) {
        if (messageIds.isEmpty()) {
            return
        }
        serviceScope.launch {
            runCatching {
                ensureGattMeshGeneralContact()
                when (type) {
                    ReceiptType.DELIVERED -> {
                        markLocalMessagesDeliveredWithRecipient(
                            context = applicationContext,
                            sessionCode = chatStore.sessionCode,
                            messageUuids = messageIds,
                            recipientLabel = recipientLabel
                        )
                    }

                    ReceiptType.READ -> {
                        markLocalMessagesReadWithRecipient(
                            context = applicationContext,
                            sessionCode = chatStore.sessionCode,
                            messageUuids = messageIds,
                            recipientLabel = recipientLabel
                        )
                    }
                }
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "Unable to persist gatt mesh receipt type=${type.name} ids=${messageIds.size}",
                    throwable
                )
            }
        }
    }

    private fun ensureGattMeshGeneralContact() {
        if (getContact(applicationContext, chatStore.sessionCode) != null) {
            return
        }
        saveContact(
            applicationContext,
            Contact(
                name = getString(R.string.mesh_chat_general_title),
                aesKey = "",
                sessionCode = chatStore.sessionCode,
                address = ""
            )
        )
    }

    private fun reassembleTransportPayload(
        sourceAddress: String,
        transportChannel: InboundTransportChannel,
        chunk: ByteArray
    ): TransportReassemblyResult {
        val receiver = synchronized(lock) {
            inboundChunkReceivers.getOrPut(sourceAddress) {
                mutableMapOf()
            }.getOrPut(transportChannel) {
                BleChunkReceiver(
                    maxPacketSize = MAX_TRANSPORT_PACKET_BYTES,
                    tag = "GattMeshChunk[$sourceAddress/${transportChannel.logTag}]"
                )
            }
        }
        val chunkResult = runCatching {
            receiver.onChunk(chunk)
        }.onFailure { throwable ->
            Log.w(TAG, "[$sourceAddress] Chunk reassembly failed, resetting receiver", throwable)
            receiver.reset()
        }.getOrElse {
            return TransportReassemblyResult.Rejected("receiver-exception")
        }
        val transportPacket = when (chunkResult) {
            BleChunkReceiver.ChunkResult.Incomplete -> return TransportReassemblyResult.Incomplete
            is BleChunkReceiver.ChunkResult.Rejected -> {
                return TransportReassemblyResult.Rejected(chunkResult.reason)
            }
            is BleChunkReceiver.ChunkResult.Complete -> chunkResult.packet
        }

        return unwrapTransportPayload(transportPacket)?.let(TransportReassemblyResult::Complete)
            ?: TransportReassemblyResult.Rejected(
                "invalid-envelope:bytes=${transportPacket.size} channel=${transportChannel.logTag}"
            )
    }

    private fun unwrapTransportPayload(transportPacket: ByteArray): ByteArray? {
        if (transportPacket.size >= TRANSPORT_HEADER_BYTES) {
            val declaredLength = ((transportPacket[0].toInt() and 0xFF) shl 8) or
                (transportPacket[1].toInt() and 0xFF)
            if (
                declaredLength in 1..MAX_PACKET_BYTES &&
                transportPacket.size - TRANSPORT_HEADER_BYTES == declaredLength
            ) {
                return transportPacket.copyOfRange(TRANSPORT_HEADER_BYTES, transportPacket.size)
            }
        }
        return transportPacket.takeIf { it.isNotEmpty() && it.size <= MAX_PACKET_BYTES }
    }

    private fun resetInboundChunkReceiver(
        sourceAddress: String,
        transportChannel: InboundTransportChannel
    ) {
        synchronized(lock) {
            resetInboundChunkReceiverLocked(sourceAddress, transportChannel)
        }
    }

    private fun resetInboundChunkReceiverLocked(
        sourceAddress: String,
        transportChannel: InboundTransportChannel
    ) {
        val receivers = inboundChunkReceivers[sourceAddress] ?: return
        receivers.remove(transportChannel)?.reset()
        if (receivers.isEmpty()) {
            inboundChunkReceivers.remove(sourceAddress)
        }
    }

    private fun resetInboundChunkReceiversLocked(sourceAddress: String) {
        inboundChunkReceivers.remove(sourceAddress)?.values?.forEach { receiver ->
            receiver.reset()
        }
    }

    private fun hasInboundPartialTransportLocked(
        sourceAddress: String,
        transportChannel: InboundTransportChannel
    ): Boolean {
        return inboundChunkReceivers[sourceAddress]
            ?.get(transportChannel)
            ?.hasRecentPartialData() == true
    }

    private fun relayPacket(
        packet: MeshPacket,
        excludeAddress: String?,
        onAddressSent: ((String) -> Unit)? = null
    ): Boolean {
        val readyClientAddresses = synchronized(lock) {
            clientPeers.values.filter { it.ready && it.connected && it.gatt != null && it.messageIn != null }
                .map { it.address }
        }
        val serverAddresses = synchronized(lock) { serverDevices.keys.toList() }

        val targets = (readyClientAddresses + serverAddresses).distinct()
        var sentAny = false
        targets.forEach { address ->
            if (address == excludeAddress) {
                return@forEach
            }
            val addressPacket = adaptChatPacketForAddressRoute(packet, address)
            val encoded = encodePacket(addressPacket)
            if (encoded.size > MAX_PACKET_BYTES) {
                Log.w(TAG, "[$address] Relay packet too large after route adaptation id=${packet.id} bytes=${encoded.size}")
                return@forEach
            }
            val sent = withAddressSendLock(address) {
                val (hasServerNotifyRoute, hasReadyClientRoute) = synchronized(lock) {
                    val serverRoute = serverNotifyEnabled[address] == true
                    val clientRoute = clientPeers[address]?.let { peer ->
                        peer.ready && peer.connected && peer.gatt != null && peer.messageIn != null
                    } == true
                    serverRoute to clientRoute
                }

                var attemptedClientRoute = false
                var attemptedServerRoute = false
                var clientRouteSent = false
                var serverRouteSent = false

                fun tryClientRoute(requireReady: Boolean): Boolean {
                    attemptedClientRoute = true
                    clientRouteSent = sendToClientPeer(
                        address = address,
                        payload = encoded,
                        requireReady = requireReady
                    )
                    return clientRouteSent
                }

                fun tryServerRoute(): Boolean {
                    attemptedServerRoute = true
                    serverRouteSent = sendToServerClient(address, encoded)
                    return serverRouteSent
                }

                val sentViaRoute = when {
                    hasServerNotifyRoute && hasReadyClientRoute -> {
                        // Prefer a single stable path. Dual-send amplifies retries and can
                        // destabilize mixed-vendor peers even after one route already succeeded.
                        tryServerRoute() || tryClientRoute(requireReady = true)
                    }

                    hasReadyClientRoute -> {
                        // Client route is preferred, but if that write fails try server route immediately.
                        tryClientRoute(requireReady = true) || tryServerRoute()
                    }

                    hasServerNotifyRoute -> {
                        // If notify is already active, do not probe the non-ready client path unless
                        // notify actually failed. Probing after success was reintroducing send storms.
                        tryServerRoute() || tryClientRoute(requireReady = false)
                    }

                    else -> {
                        // Last resort: if no route is marked ready, still attempt direct client write if possible.
                        tryClientRoute(requireReady = false) || tryServerRoute()
                    }
                }

                Log.d(
                    TAG,
                    "[$address] Relay decision packet=${packet.id} " +
                        "clientReady=$hasReadyClientRoute serverNotify=$hasServerNotifyRoute " +
                        "triedClient=$attemptedClientRoute clientSent=$clientRouteSent " +
                        "triedServer=$attemptedServerRoute serverSent=$serverRouteSent"
                )
                sentViaRoute
            }
            if (!sent) {
                Log.w(TAG, "[$address] Relay failed for packet id=${packet.id}")
            }
            if (sent) {
                sentAny = true
                onAddressSent?.invoke(address)
            }
        }
        return sentAny
    }

    private fun sendDirectPacket(
        packet: MeshPacket,
        address: String
    ): Boolean {
        val adaptedPacket = adaptChatPacketForAddressRoute(packet, address)
        val encoded = encodePacket(adaptedPacket)
        if (encoded.size > MAX_PACKET_BYTES) {
            return false
        }
        return withAddressSendLock(address) {
            val (hasServerNotifyRoute, hasReadyClientRoute) = synchronized(lock) {
                val serverRoute = serverNotifyEnabled[address] == true
                val clientRoute = clientPeers[address]?.let { peer ->
                    peer.ready && peer.connected && peer.gatt != null && peer.messageIn != null
                } == true
                serverRoute to clientRoute
            }

            when {
                hasServerNotifyRoute && hasReadyClientRoute -> {
                    val serverSent = sendToServerClient(address, encoded)
                    serverSent || sendToClientPeer(
                        address = address,
                        payload = encoded,
                        requireReady = true
                    )
                }

                hasReadyClientRoute -> {
                    sendToClientPeer(
                        address = address,
                        payload = encoded,
                        requireReady = true
                    ) || sendToServerClient(address, encoded)
                }

                hasServerNotifyRoute -> {
                    sendToServerClient(address, encoded) || sendToClientPeer(
                        address = address,
                        payload = encoded,
                        requireReady = false
                    )
                }

                else -> {
                    sendToClientPeer(
                        address = address,
                        payload = encoded,
                        requireReady = false
                    ) || sendToServerClient(address, encoded)
                }
            }
        }
    }

    private fun recordLocalChatDispatch(
        messageId: String,
        sentAddresses: Collection<String>
    ) {
        if (sentAddresses.isEmpty()) {
            return
        }
        val recipientLabels = sentAddresses
            .asSequence()
            .map(::resolveConnectedPeerDisplayName)
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (recipientLabels.isEmpty()) {
            return
        }
        chatStore.markSentTo(messageId, recipientLabels)
        persistSentToUpdate(messageId = messageId, recipients = recipientLabels)
    }

    private fun <T> withAddressSendLock(
        address: String,
        block: () -> T
    ): T {
        val lockRef = synchronized(lock) {
            outboundSendLocks.getOrPut(address) { Any() }
        }
        return synchronized(lockRef) {
            block()
        }
    }

    // Some stacks keep the GATT command queue blocked until the CCCD write completes.
    // Avoid probing MESSAGE_IN early on those connections.
    private fun canUseClientRouteLocked(peer: ClientPeer, requireReady: Boolean): Boolean {
        if (peer.gatt == null || !peer.connected || peer.messageIn == null) {
            return false
        }
        if (peer.ready) {
            return true
        }
        if (requireReady) {
            return false
        }
        val notificationSetupInFlight = peer.messageOut != null &&
            clientNotificationSettleJobs.containsKey(peer.address)
        return !notificationSetupInFlight
    }

    @SuppressLint("MissingPermission")
    private fun sendToClientPeer(
        address: String,
        payload: ByteArray,
        requireReady: Boolean = true
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        val peerSnapshot = synchronized(lock) {
            clientPeers[address]
                ?.takeIf { peer -> canUseClientRouteLocked(peer, requireReady) }
                ?.copy()
        } ?: return false

        val gatt = peerSnapshot.gatt ?: return false
        val characteristic = peerSnapshot.messageIn ?: return false
        val maxPayload = maxPayloadForMtu(peerSnapshot.mtu)
        val safeChunkPayload = maxPayload.coerceAtMost(MAX_CLIENT_CHUNK_BYTES)
        val (_, chunks) = prepareOutboundChunks(payload = payload, maxChunkPayload = safeChunkPayload)
            ?: return false
        val isMultiChunk = chunks.size > 1
        val supportsWriteNoResponse = characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val supportsWrite = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        if (!peerSnapshot.ready && isMultiChunk) {
            Log.d(
                TAG,
                "[$address] Skipping non-ready client route for multi-chunk payload bytes=${payload.size} chunks=${chunks.size}"
            )
            return false
        }
        val packetQuietDelayMillis = clientPacketQuietDelayMillis(
            address = address,
            isMultiChunk = isMultiChunk
        )
        if (packetQuietDelayMillis > 0L) {
            SystemClock.sleep(packetQuietDelayMillis)
        }
        val conservativePacing = shouldUseConservativeClientPacing(address)
        val allowWriteNoResponseFallback = synchronized(lock) {
            !isMultiChunk ||
                !supportsWrite ||
                (
                    !conservativePacing &&
                        (
                            address in clientNoResponsePreferredPeers ||
                                address in clientNotificationBypassPeers
                            )
                    )
        }

        chunks.forEachIndexed { index, chunk ->
            val chunkSent = writeClientChunkWithRetry(
                address = address,
                gatt = gatt,
                characteristic = characteristic,
                chunk = chunk,
                supportsWrite = supportsWrite,
                supportsWriteNoResponse = supportsWriteNoResponse,
                allowWriteNoResponseFallback = allowWriteNoResponseFallback
            )
            if (!chunkSent) {
                Log.w(
                    TAG,
                    "[$address] Client chunk send failed chunk=${index + 1}/${chunks.size} size=${chunk.size}"
                )
                recoverFromFailedClientSend(
                    address = address,
                    gatt = gatt,
                    reason = "chunk=${index + 1}/${chunks.size}"
                )
                return false
            }
            if (index < chunks.lastIndex) {
                SystemClock.sleep(clientInterChunkDelayMillis(address))
            }
        }
        rememberSuccessfulClientPacketSend(address = address, isMultiChunk = isMultiChunk)
        return true
    }

    @SuppressLint("MissingPermission")
    private fun sendToServerClient(address: String, payload: ByteArray): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        val now = System.currentTimeMillis()
        captureSystemConnectedSnapshot(now)
        val staleInboundRoute = synchronized(lock) {
            val snapshot = freshSystemConnectedSnapshotLocked(now)
            snapshot != null && address !in snapshot
        }
        if (staleInboundRoute) {
            synchronized(lock) {
                clearServerPeerLocked(address)
            }
            cancelInboundFallbackConnect(address)
            publishState()
            Log.w(TAG, "[$address] Dropped stale inbound notify route before server send")
            return false
        }
        val mtu = synchronized(lock) { serverPeerMtu[address] ?: DEFAULT_ATT_MTU }
        val maxPayload = maxPayloadForMtu(mtu)
        val safeChunkPayload = maxPayload.coerceAtMost(MAX_SERVER_NOTIFY_CHUNK_BYTES)
        val (pendingPayload, chunks) = prepareOutboundChunks(
            payload = payload,
            maxChunkPayload = safeChunkPayload
        )
            ?: run {
                Log.d(
                    TAG,
                    "[$address] Server path payload preparation failed bytes=${payload.size} max=$safeChunkPayload mtu=$mtu"
                )
                return false
        }
        val notifyPreference = synchronized(lock) {
            serverNotifyEnabled[address]
        }
        if (notifyPreference != true) {
            return false
        }

        val server = gattServer ?: return false
        val device = synchronized(lock) { serverDevices[address] } ?: return false
        val characteristic = server
            .getService(profile.serviceUuid)
            ?.getCharacteristic(profile.messageOutUuid)
            ?: return false

        synchronized(lock) {
            serverPendingPayload[address] = pendingPayload
        }

        val notified = chunks.withIndex().all { (index, chunk) ->
            val sent = notifyServerChunkWithRetry(
                server = server,
                device = device,
                characteristic = characteristic,
                chunk = chunk
            )
            if (!sent) {
                Log.w(
                    TAG,
                    "[$address] Server notify chunk failed chunk=${index + 1}/${chunks.size} size=${chunk.size}"
                )
                return@all false
            }
            if (index < chunks.lastIndex) {
                SystemClock.sleep(INTER_CHUNK_DELAY_MS)
            }
            true
        }
        if (!notified) {
            val shouldScheduleFallback = synchronized(lock) {
                val notifyWasEnabled = serverNotifyEnabled[address] == true
                if (notifyWasEnabled) {
                    serverNotifyEnabled[address] = false
                }
                notifyWasEnabled
            }
            if (shouldScheduleFallback) {
                Log.w(
                    TAG,
                    "[$address] Server notify path failed; marking CCCD route unavailable and scheduling fallback connect"
                )
                scheduleInboundFallbackConnect(device, address)
                publishState()
            }
        }
        if (notified && notifyPreference != true) {
            synchronized(lock) {
                serverNotifyEnabled[address] = true
            }
        }
        return notified
    }

    private fun prepareOutboundChunks(
        payload: ByteArray,
        maxChunkPayload: Int
    ): Pair<ByteArray, List<ByteArray>>? {
        if (payload.isEmpty() || maxChunkPayload <= 0) {
            return null
        }

        // Keep public mesh on the legacy length-prefixed envelope for cross-platform parity.
        // The current iOS mesh runtime still reassembles only the 2-byte header format and
        // rejects framed chunks with ATT status 0x0E ("unlikely error").
        val transportPayload = GattMeshTransportFramer.buildLegacyTransportPayload(
            payload = payload,
            maxPacketBytes = MAX_PACKET_BYTES,
            transportHeaderBytes = TRANSPORT_HEADER_BYTES,
            maxTransportPacketBytes = MAX_TRANSPORT_PACKET_BYTES
        ) ?: return null
        val chunks = GattMeshTransportFramer.splitIntoChunks(transportPayload, maxChunkPayload)
        if (chunks.isEmpty()) {
            return null
        }
        return transportPayload to chunks
    }

    @SuppressLint("MissingPermission")
    private fun writeClientChunkWithRetry(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        supportsWrite: Boolean,
        supportsWriteNoResponse: Boolean,
        allowWriteNoResponseFallback: Boolean
    ): Boolean {
        var lastNoResponseStatus: Int? = null
        var lastWithResponseStatus: Int? = null
        repeat(CHUNK_WRITE_MAX_ATTEMPTS) { attempt ->
            val preferNoResponseFirst = synchronized(lock) {
                val now = SystemClock.elapsedRealtime()
                val prefersNoResponse = address in clientNoResponsePreferredPeers
                val prefersWithResponse = supportsWrite &&
                    (clientWithResponsePreferredUntilElapsedRealtimeMs[address] ?: 0L) > now
                val preferNoResponse = allowWriteNoResponseFallback && supportsWriteNoResponse && (
                    (!prefersWithResponse && prefersNoResponse) ||
                        !supportsWrite
                )
                preferNoResponse
            }
            fun tryNoResponse(): ClientWriteAttemptResult {
                return writeClientChunkNoResponse(address, gatt, characteristic, chunk).also { result ->
                    when (result) {
                        ClientWriteAttemptResult.SUCCESS -> Unit
                        ClientWriteAttemptResult.BUSY -> {
                            lastNoResponseStatus = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                        }
                        ClientWriteAttemptResult.TIMED_OUT -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                lastNoResponseStatus = BluetoothGatt.GATT_FAILURE
                            }
                        }
                        ClientWriteAttemptResult.FAILED -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                lastNoResponseStatus = BluetoothGatt.GATT_FAILURE
                            }
                        }
                    }
                }
            }
            fun tryWithResponse(): ClientWriteAttemptResult {
                return writeClientChunkAwaitAck(
                    address = address,
                    gatt = gatt,
                    characteristic = characteristic,
                    chunk = chunk,
                    allowNoResponsePromotion = allowWriteNoResponseFallback && supportsWriteNoResponse
                ).also { result ->
                    when (result) {
                        ClientWriteAttemptResult.SUCCESS -> Unit
                        ClientWriteAttemptResult.BUSY -> {
                            lastWithResponseStatus = BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
                        }
                        ClientWriteAttemptResult.TIMED_OUT -> {
                            lastWithResponseStatus = BluetoothGatt.GATT_FAILURE
                        }
                        ClientWriteAttemptResult.FAILED -> {
                            lastWithResponseStatus = BluetoothGatt.GATT_FAILURE
                        }
                    }
                }
            }

            if (preferNoResponseFirst && supportsWriteNoResponse) {
                when (val result = tryNoResponse()) {
                    ClientWriteAttemptResult.SUCCESS -> return true
                    ClientWriteAttemptResult.BUSY -> {
                        if (supportsWrite) {
                            rememberWithResponsePreference(address = address, reason = "no-response-busy")
                            SystemClock.sleep(clientBusyRetryDelayMillis(attempt))
                            when (val fallbackResult = tryWithResponse()) {
                                ClientWriteAttemptResult.SUCCESS -> {
                                    rememberWithResponsePreference(address = address, reason = "with-response-success")
                                    return true
                                }
                                ClientWriteAttemptResult.BUSY -> {
                                    SystemClock.sleep(clientBusyRetryDelayMillis(attempt))
                                    return@repeat
                                }

                                ClientWriteAttemptResult.TIMED_OUT -> {
                                    clearWithResponsePreference(address)
                                    return false
                                }

                                ClientWriteAttemptResult.FAILED -> {
                                    clearWithResponsePreference(address)
                                }
                            }
                        } else {
                            SystemClock.sleep(clientBusyRetryDelayMillis(attempt))
                            return@repeat
                        }
                    }
                    ClientWriteAttemptResult.TIMED_OUT -> Unit
                    ClientWriteAttemptResult.FAILED -> Unit
                }
            }

            if (supportsWrite) {
                when (val result = tryWithResponse()) {
                    ClientWriteAttemptResult.SUCCESS -> {
                        rememberWithResponsePreference(address = address, reason = "with-response-success")
                        return true
                    }
                    ClientWriteAttemptResult.BUSY -> {
                        SystemClock.sleep(clientBusyRetryDelayMillis(attempt))
                        return@repeat
                    }
                    ClientWriteAttemptResult.TIMED_OUT -> {
                        clearWithResponsePreference(address)
                        return false
                    }
                    ClientWriteAttemptResult.FAILED -> {
                        clearWithResponsePreference(address)
                    }
                }
            }

            if (!preferNoResponseFirst && allowWriteNoResponseFallback && supportsWriteNoResponse) {
                when (val result = tryNoResponse()) {
                    ClientWriteAttemptResult.SUCCESS -> return true
                    ClientWriteAttemptResult.BUSY -> {
                        SystemClock.sleep(clientBusyRetryDelayMillis(attempt))
                        return@repeat
                    }
                    ClientWriteAttemptResult.TIMED_OUT -> Unit
                    ClientWriteAttemptResult.FAILED -> Unit
                }
            }
            if (attempt < CHUNK_WRITE_MAX_ATTEMPTS - 1) {
                SystemClock.sleep((CHUNK_WRITE_RETRY_BASE_DELAY_MS * (attempt + 1)).coerceAtLeast(1L))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.w(
                TAG,
                "Client chunk write failed. lastNoRespStatus=$lastNoResponseStatus lastRespStatus=$lastWithResponseStatus"
            )
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun writeClientChunkNoResponse(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): ClientWriteAttemptResult {
        val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gattWriteChunkApi33(
                gatt = gatt,
                characteristic = characteristic,
                chunk = chunk,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            when {
                status == BluetoothStatusCodes.SUCCESS -> true
                isClientWriteBusyStatus(status) -> return ClientWriteAttemptResult.BUSY
                else -> return ClientWriteAttemptResult.FAILED
            }
        } else {
            val writeStarted = gatt.writeCharacteristicCompat(
                characteristic = characteristic,
                value = chunk,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
            if (!writeStarted) {
                return if (synchronized(lock) {
                        address in clientNoResponsePreferredPeers || address in clientNotificationBypassPeers
                    }
                ) {
                    ClientWriteAttemptResult.BUSY
                } else {
                    ClientWriteAttemptResult.FAILED
                }
            }
            true
        }
        if (!writeStarted) {
            return ClientWriteAttemptResult.FAILED
        }
        // WRITE_NO_RESPONSE has no completion callback; keep a guard to reduce stack busy races.
        SystemClock.sleep(clientNoResponseSettleMillis(address))
        return ClientWriteAttemptResult.SUCCESS
    }

    @SuppressLint("MissingPermission")
    private fun writeClientChunkAwaitAck(
        address: String,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        allowNoResponsePromotion: Boolean
    ): ClientWriteAttemptResult {
        val ticket = ClientWriteTicket(gatt = gatt)
        synchronized(lock) {
            clientWriteTickets[address]?.let { previous ->
                previous.status = BluetoothGatt.GATT_FAILURE
                previous.latch.countDown()
            }
            clientWriteTickets[address] = ticket
        }
        val writeStarted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gattWriteChunkApi33(
                gatt = gatt,
                characteristic = characteristic,
                chunk = chunk,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            when {
                status == BluetoothStatusCodes.SUCCESS -> true
                isClientWriteBusyStatus(status) -> {
                    synchronized(lock) {
                        clientWriteTickets.remove(address, ticket)
                    }
                    return ClientWriteAttemptResult.BUSY
                }
                else -> false
            }
        } else {
            gatt.writeCharacteristicCompat(
                characteristic = characteristic,
                value = chunk,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        }
        if (!writeStarted) {
            synchronized(lock) {
                clientWriteTickets.remove(address, ticket)
            }
            return ClientWriteAttemptResult.FAILED
        }
        val ackTimeoutMillis = clientWriteAckTimeoutMillis(address)
        val completed = ticket.latch.await(ackTimeoutMillis, TimeUnit.MILLISECONDS)
        synchronized(lock) {
            clientWriteTickets.remove(address, ticket)
        }
        if (!completed) {
            if (allowNoResponsePromotion) {
                synchronized(lock) {
                    clientNoResponsePreferredPeers += address
                }
                Log.w(
                    TAG,
                    "[$address] Client write callback timed out after ${ackTimeoutMillis}ms; preferring WRITE_NO_RESPONSE fallback"
                )
            } else {
                Log.w(
                    TAG,
                    "[$address] Client write callback timed out after ${ackTimeoutMillis}ms"
                )
            }
            Log.w(TAG, "[$address] Timed out waiting for client write callback")
            return ClientWriteAttemptResult.TIMED_OUT
        }
        if (ticket.status != BluetoothGatt.GATT_SUCCESS) {
            return ClientWriteAttemptResult.FAILED
        }
        // Small post-ack guard reduces "prior command is not finished" bursts on some stacks.
        val postAckSettleMillis = clientWritePostAckSettleMillis(address)
        if (postAckSettleMillis > 0L) {
            SystemClock.sleep(postAckSettleMillis)
        }
        return ClientWriteAttemptResult.SUCCESS
    }

    private fun isClientWriteBusyStatus(status: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false
        }
        return status == BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
    }

    private fun clientBusyRetryDelayMillis(attempt: Int): Long {
        return (CLIENT_WRITE_BUSY_RETRY_BASE_DELAY_MS * (attempt + 1)).coerceAtLeast(1L)
    }

    private fun pendingOutboundRetryBackoffMillis(failureCount: Int): Long {
        if (failureCount <= 0) {
            return 0L
        }
        val exponent = (failureCount - 1).coerceAtMost(4)
        val multiplier = 1L shl exponent
        return (PENDING_OUTBOUND_RETRY_BACKOFF_BASE_MS * multiplier)
            .coerceAtMost(PENDING_OUTBOUND_RETRY_BACKOFF_MAX_MS)
    }

    private fun clientNoResponseSettleMillis(address: String): Long {
        val (prefersExtendedSettle, overrideMs) = synchronized(lock) {
            val prefers = address in clientNoResponsePreferredPeers || address in clientNotificationBypassPeers
            prefers to clientNoResponseSettleOverridesMs[address]
        }
        return when {
            overrideMs != null -> overrideMs
            prefersExtendedSettle -> CLIENT_NO_RESPONSE_PREFERRED_SETTLE_MS
            else -> CLIENT_NO_RESPONSE_SETTLE_MS
        }
    }

    private fun clientPacketQuietDelayMillis(
        address: String,
        isMultiChunk: Boolean
    ): Long {
        val now = SystemClock.elapsedRealtime()
        return synchronized(lock) {
            val quietUntil = clientPacketQuietUntilElapsedRealtimeMs[address] ?: 0L
            (quietUntil - now).coerceAtLeast(0L)
        }
    }

    private fun rememberSuccessfulClientPacketSend(
        address: String,
        isMultiChunk: Boolean
    ) {
        if (!isMultiChunk) {
            return
        }
        val quietUntil = SystemClock.elapsedRealtime() + clientPacketSettleMillis(address)
        synchronized(lock) {
            clientPacketQuietUntilElapsedRealtimeMs[address] = quietUntil
        }
    }

    private fun rememberClientRouteWarmup(address: String) {
        val quietUntil = SystemClock.elapsedRealtime() + clientRouteWarmupMillis(address)
        synchronized(lock) {
            val current = clientPacketQuietUntilElapsedRealtimeMs[address] ?: 0L
            if (quietUntil > current) {
                clientPacketQuietUntilElapsedRealtimeMs[address] = quietUntil
            }
        }
    }

    private fun shouldUseConservativeClientPacing(address: String): Boolean {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            return true
        }
        val mtu = synchronized(lock) {
            clientPeers[address]?.mtu ?: DEFAULT_ATT_MTU
        }
        return mtu <= DEFAULT_ATT_MTU
    }

    private fun clientWriteAckTimeoutMillis(address: String): Long {
        val (writeOnlyBypass, prefersNoResponse) = synchronized(lock) {
            val bypass = address in clientNotificationBypassPeers
            val prefers = address in clientNoResponsePreferredPeers
            bypass to prefers
        }
        val defaultAckTimeoutMillis = if (shouldUseConservativeClientPacing(address)) {
            LEGACY_CLIENT_WRITE_ACK_TIMEOUT_MS
        } else {
            CLIENT_WRITE_ACK_TIMEOUT_MS
        }
        return when {
            writeOnlyBypass -> WRITE_ONLY_CLIENT_WRITE_ACK_TIMEOUT_MS
            prefersNoResponse -> defaultAckTimeoutMillis * 2
            else -> defaultAckTimeoutMillis
        }
    }

    private fun clientWritePostAckSettleMillis(address: String): Long {
        return if (shouldUseConservativeClientPacing(address)) {
            LEGACY_CLIENT_WRITE_POST_ACK_SETTLE_MS
        } else {
            CLIENT_WRITE_POST_ACK_SETTLE_MS
        }
    }

    private fun rememberWithResponsePreference(address: String, reason: String) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            return
        }
        val preferUntil = SystemClock.elapsedRealtime() + clientWithResponsePreferenceMillis(address)
        synchronized(lock) {
            clientWithResponsePreferredUntilElapsedRealtimeMs[address] = preferUntil
        }
        Log.d(TAG, "[$address] Temporarily preferring WRITE_DEFAULT reason=$reason")
    }

    private fun clearWithResponsePreference(address: String) {
        synchronized(lock) {
            clientWithResponsePreferredUntilElapsedRealtimeMs.remove(address)
        }
    }

    private fun clientInterChunkDelayMillis(address: String): Long {
        return if (shouldUseConservativeClientPacing(address)) {
            LEGACY_CLIENT_INTER_CHUNK_DELAY_MS
        } else {
            INTER_CHUNK_DELAY_MS
        }
    }

    private fun clientWithResponsePreferenceMillis(address: String): Long {
        return if (shouldUseConservativeClientPacing(address)) {
            0L
        } else {
            CLIENT_WITH_RESPONSE_PREFERRED_MS
        }
    }

    private fun clientRouteWarmupMillis(address: String): Long {
        return if (shouldUseConservativeClientPacing(address)) {
            LEGACY_CLIENT_ROUTE_WARMUP_MS
        } else {
            CLIENT_ROUTE_WARMUP_MS
        }
    }

    private fun clientPacketSettleMillis(address: String): Long {
        return if (shouldUseConservativeClientPacing(address)) {
            LEGACY_CLIENT_PACKET_SETTLE_MS
        } else {
            CLIENT_PACKET_SETTLE_MS
        }
    }

    private fun clientSendRecoveryCooldownMillis(): Long {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            LEGACY_CLIENT_SEND_RECOVERY_COOLDOWN_MS
        } else {
            CLIENT_SEND_RECOVERY_COOLDOWN_MS
        }
    }

    private fun controlTrafficInitialDelayMillis(): Long {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            LEGACY_CONTROL_TRAFFIC_INITIAL_DELAY_MS
        } else {
            CONTROL_TRAFFIC_INITIAL_DELAY_MS
        }
    }

    private fun controlTrafficRetryDelayMillis(): Long {
        return if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
            LEGACY_CONTROL_TRAFFIC_RETRY_DELAY_MS
        } else {
            CONTROL_TRAFFIC_RETRY_DELAY_MS
        }
    }

    // Must be called with lock held.
    private fun isClientSendRecoveryBlockedLocked(address: String, now: Long): Boolean {
        val blockedUntil = clientSendRecoveryBlockedUntilMillis[address] ?: return false
        if (now >= blockedUntil) {
            clientSendRecoveryBlockedUntilMillis.remove(address)
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun gattWriteChunkApi33(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        writeType: Int
    ): Int {
        return runCatching {
            gatt.writeCharacteristic(characteristic, chunk, writeType)
        }.getOrElse { throwable ->
            Log.w(TAG, "Gatt write threw while writing chunk", throwable)
            BluetoothStatusCodes.ERROR_UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyServerChunkWithRetry(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        val address = normalizeAddress(device.address)
        repeat(CHUNK_WRITE_MAX_ATTEMPTS) { attempt ->
            val ticket = ServerNotifyTicket()
            synchronized(lock) {
                serverNotifyTickets[address]?.let { previous ->
                    previous.status = BluetoothGatt.GATT_FAILURE
                    previous.latch.countDown()
                }
                serverNotifyTickets[address] = ticket
            }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runCatching {
                    server.notifyCharacteristicChanged(device, characteristic, false, chunk) ==
                        BluetoothStatusCodes.SUCCESS
                }.getOrDefault(false)
            } else {
                @Suppress("DEPRECATION")
                runCatching {
                    characteristic.value = chunk
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }.getOrDefault(false)
            }
            if (!started) {
                synchronized(lock) {
                    serverNotifyTickets.remove(address, ticket)
                }
            } else {
                val bypassCallbackWait = synchronized(lock) {
                    address in serverNotifyCallbackBypassPeers
                }
                if (bypassCallbackWait) {
                    synchronized(lock) {
                        serverNotifyTickets.remove(address, ticket)
                    }
                    if (SERVER_NOTIFY_CALLBACK_BYPASS_SETTLE_MS > 0L) {
                        SystemClock.sleep(SERVER_NOTIFY_CALLBACK_BYPASS_SETTLE_MS)
                    }
                    return true
                }
                val completed = ticket.latch.await(SERVER_NOTIFY_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                synchronized(lock) {
                    serverNotifyTickets.remove(address, ticket)
                }
                if (completed && ticket.status == BluetoothGatt.GATT_SUCCESS) {
                    if (SERVER_NOTIFY_POST_ACK_SETTLE_MS > 0L) {
                        SystemClock.sleep(SERVER_NOTIFY_POST_ACK_SETTLE_MS)
                    }
                    return true
                }
                if (!completed) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        Log.w(
                            TAG,
                            "[$address] Timed out waiting for server notification callback on legacy stack; " +
                                "keeping callback gating to avoid notification chunk corruption"
                        )
                        return false
                    }
                    synchronized(lock) {
                        serverNotifyCallbackBypassPeers += address
                    }
                    Log.w(
                        TAG,
                        "[$address] Timed out waiting for server notification callback; " +
                            "future chunks will bypass callback wait"
                    )
                    if (SERVER_NOTIFY_CALLBACK_BYPASS_SETTLE_MS > 0L) {
                        SystemClock.sleep(SERVER_NOTIFY_CALLBACK_BYPASS_SETTLE_MS)
                    }
                    return true
                }
            }
            if (attempt < CHUNK_WRITE_MAX_ATTEMPTS - 1) {
                SystemClock.sleep((CHUNK_WRITE_RETRY_BASE_DELAY_MS * (attempt + 1)).coerceAtLeast(1L))
            }
        }
        return false
    }

    /**
     * Sends an image into the mesh as a single-hop encrypted blob (init → chunks → done) to all
     * currently connected peers. Authority-mesh only in v1: the blob is encrypted once with the
     * shared group key, so only provisioned authorities can decrypt it.
     *
     * Returns the message/blob id immediately; progress lands in the chat store
     * (SENDING → SENT/FAILED).
     */
    fun sendImageMessage(uri: Uri): String? {
        if (profile.admission !is AdmissionPolicy.RequireVerifiedRole) {
            return null
        }
        if (!runtimeActive) {
            requestRuntimeSelfHeal(reason = "send-image-message")
        }
        val blobId = UUID.randomUUID().toString()
        serviceScope.launch {
            transferOutboundImageBlob(blobId = blobId, uri = uri)
        }
        return blobId
    }

    private suspend fun transferOutboundImageBlob(blobId: String, uri: Uri) {
        val prepared = runCatching {
            prepareImageAttachmentForTransfer(
                context = applicationContext,
                uuid = blobId,
                uri = uri,
                mimeType = null,
                fallbackWidth = null,
                fallbackHeight = null,
                profile = MESH_IMAGE_TRANSFER_PROFILE
            )
        }.getOrNull()
        if (prepared == null || prepared.bytes.isEmpty() || prepared.bytes.size > MESH_IMAGE_MAX_PLAIN_BYTES) {
            Log.w(TAG, "Unable to prepare mesh image blob=$blobId")
            return
        }
        chatStore.appendLocalImageMessage(
            messageId = blobId,
            imageFileName = prepared.fileName,
            imageThumbnailName = prepared.thumbnailName,
            imageWidth = prepared.width,
            imageHeight = prepared.height,
            status = MeshMessageStatus.SENDING
        )

        val encrypted = runCatching {
            AesGcm.encryptAesGcm(
                keyBytes = meshPayloadKey,
                plaintext = prepared.bytes,
                aad = buildImageBlobAad(blobId = blobId, mimeType = prepared.mimeType)
            )
        }.getOrNull()
        if (encrypted == null) {
            finalizeOutboundImageBlob(blobId, prepared, success = false)
            return
        }
        val cipher = encrypted.cipher
        val chunkCount = (cipher.size + MESH_IMAGE_CHUNK_BYTES - 1) / MESH_IMAGE_CHUNK_BYTES
        if (chunkCount !in 1..MESH_IMAGE_MAX_CHUNKS) {
            finalizeOutboundImageBlob(blobId, prepared, success = false)
            return
        }

        val initPacket = MeshPacket(
            id = blobId,
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = IMAGE_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.IMAGE_INIT,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V5,
            encrypted = true,
            keyId = profile.payloadKeyId,
            encryptedIvBase64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
            blobId = blobId,
            blobChunkCount = chunkCount,
            blobCipherBytes = cipher.size,
            blobMime = prepared.mimeType,
            blobWidth = prepared.width,
            blobHeight = prepared.height
        )
        // Fast lane: also push the whole blob over Wi-Fi Aware when peers support it; receivers
        // dedupe by blob id, so the BLE copy below stays the universal baseline.
        awareAccelerator?.offerBlob(encodePacket(initPacket), cipher)
        if (sendOrQueuePacket(initPacket, queueWhenFailed = false) == DispatchOutcome.FAILED) {
            finalizeOutboundImageBlob(blobId, prepared, success = false)
            return
        }

        var index = 0
        while (index < chunkCount) {
            delay(MESH_IMAGE_CHUNK_SEND_SPACING_MS)
            val start = index * MESH_IMAGE_CHUNK_BYTES
            val length = minOf(MESH_IMAGE_CHUNK_BYTES, cipher.size - start)
            val chunkPacket = MeshPacket(
                id = "$blobId-c$index",
                senderLabel = localSenderLabel,
                timestampMillis = System.currentTimeMillis(),
                message = IMAGE_PLACEHOLDER_MESSAGE,
                type = MeshPacketType.IMAGE_CHUNK,
                hop = 0,
                protocol = PACKET_PROTOCOL_VALUE_V5,
                blobId = blobId,
                blobIndex = index,
                blobDataBase64 = Base64.encodeToString(cipher, start, length, Base64.NO_WRAP)
            )
            if (sendOrQueuePacket(chunkPacket, queueWhenFailed = false) == DispatchOutcome.FAILED) {
                finalizeOutboundImageBlob(blobId, prepared, success = false)
                return
            }
            index++
        }

        delay(MESH_IMAGE_CHUNK_SEND_SPACING_MS)
        val donePacket = MeshPacket(
            id = "$blobId-done",
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = IMAGE_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.IMAGE_DONE,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V5,
            blobId = blobId
        )
        val outcome = sendOrQueuePacket(donePacket, queueWhenFailed = false)
        finalizeOutboundImageBlob(blobId, prepared, success = outcome != DispatchOutcome.FAILED)
    }

    private suspend fun finalizeOutboundImageBlob(
        blobId: String,
        prepared: com.auralis.crisisconnect.core.media.PreparedImageAttachment,
        success: Boolean
    ) {
        chatStore.updateLocalMessageStatus(
            messageId = blobId,
            status = if (success) MeshMessageStatus.SENT else MeshMessageStatus.FAILED
        )
        if (success) {
            runCatching {
                ensureGattMeshGeneralContact()
                saveLocalImageMessage(
                    context = applicationContext,
                    sessionCode = chatStore.sessionCode,
                    uuid = blobId,
                    fileName = prepared.fileName,
                    thumbnailName = prepared.thumbnailName,
                    width = prepared.width,
                    height = prepared.height,
                    mimeType = prepared.mimeType
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to persist local mesh image blob=$blobId", throwable)
            }
        }
    }

    /**
     * Sends a recorded voice note into the mesh as a single-hop encrypted blob, reusing the image
     * blob pipeline with kind=voice. Authority mesh only; AAC/M4A capped at 400KB / 90s.
     */
    fun sendVoiceMessage(audioFile: java.io.File, durationMillis: Long): String? {
        if (profile.admission !is AdmissionPolicy.RequireVerifiedRole) {
            return null
        }
        if (!runtimeActive) {
            requestRuntimeSelfHeal(reason = "send-voice-message")
        }
        val blobId = UUID.randomUUID().toString()
        serviceScope.launch {
            transferOutboundVoiceBlob(blobId = blobId, audioFile = audioFile, durationMillis = durationMillis)
        }
        return blobId
    }

    private suspend fun transferOutboundVoiceBlob(
        blobId: String,
        audioFile: java.io.File,
        durationMillis: Long
    ) {
        val plain = runCatching { audioFile.readBytes() }.getOrNull()
        if (plain == null || plain.isEmpty() || plain.size > MESH_IMAGE_MAX_PLAIN_BYTES) {
            Log.w(TAG, "Unable to prepare mesh voice blob=$blobId bytes=${plain?.size}")
            return
        }
        val safeDuration = durationMillis.coerceIn(1L, MESH_VOICE_MAX_DURATION_MS)
        val voiceFileName = "$blobId.m4a"
        runCatching {
            val target = voiceMessageFile(applicationContext, voiceFileName)
            target.parentFile?.mkdirs()
            target.writeBytes(plain)
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to store local mesh voice blob=$blobId", throwable)
            return
        }
        chatStore.appendLocalVoiceMessage(
            messageId = blobId,
            voiceFileName = voiceFileName,
            voiceDurationMillis = safeDuration,
            status = MeshMessageStatus.SENDING
        )

        val encrypted = runCatching {
            AesGcm.encryptAesGcm(
                keyBytes = meshPayloadKey,
                plaintext = plain,
                aad = buildImageBlobAad(blobId = blobId, mimeType = MESH_VOICE_MIME)
            )
        }.getOrNull()
        if (encrypted == null) {
            finalizeOutboundVoiceBlob(blobId, voiceFileName, safeDuration, success = false)
            return
        }
        val cipher = encrypted.cipher
        val chunkCount = (cipher.size + MESH_IMAGE_CHUNK_BYTES - 1) / MESH_IMAGE_CHUNK_BYTES
        if (chunkCount !in 1..MESH_IMAGE_MAX_CHUNKS) {
            finalizeOutboundVoiceBlob(blobId, voiceFileName, safeDuration, success = false)
            return
        }

        val initPacket = MeshPacket(
            id = blobId,
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = IMAGE_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.IMAGE_INIT,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V5,
            encrypted = true,
            keyId = profile.payloadKeyId,
            encryptedIvBase64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP),
            blobId = blobId,
            blobChunkCount = chunkCount,
            blobCipherBytes = cipher.size,
            blobMime = MESH_VOICE_MIME,
            blobKind = BLOB_KIND_VOICE,
            blobDurationMillis = safeDuration
        )
        // Fast lane: also push the whole blob over Wi-Fi Aware when peers support it; receivers
        // dedupe by blob id, so the BLE copy below stays the universal baseline.
        awareAccelerator?.offerBlob(encodePacket(initPacket), cipher)
        if (sendOrQueuePacket(initPacket, queueWhenFailed = false) == DispatchOutcome.FAILED) {
            finalizeOutboundVoiceBlob(blobId, voiceFileName, safeDuration, success = false)
            return
        }

        var index = 0
        while (index < chunkCount) {
            delay(MESH_IMAGE_CHUNK_SEND_SPACING_MS)
            val start = index * MESH_IMAGE_CHUNK_BYTES
            val length = minOf(MESH_IMAGE_CHUNK_BYTES, cipher.size - start)
            val chunkPacket = MeshPacket(
                id = "$blobId-c$index",
                senderLabel = localSenderLabel,
                timestampMillis = System.currentTimeMillis(),
                message = IMAGE_PLACEHOLDER_MESSAGE,
                type = MeshPacketType.IMAGE_CHUNK,
                hop = 0,
                protocol = PACKET_PROTOCOL_VALUE_V5,
                blobId = blobId,
                blobIndex = index,
                blobDataBase64 = Base64.encodeToString(cipher, start, length, Base64.NO_WRAP)
            )
            if (sendOrQueuePacket(chunkPacket, queueWhenFailed = false) == DispatchOutcome.FAILED) {
                finalizeOutboundVoiceBlob(blobId, voiceFileName, safeDuration, success = false)
                return
            }
            index++
        }

        delay(MESH_IMAGE_CHUNK_SEND_SPACING_MS)
        val donePacket = MeshPacket(
            id = "$blobId-done",
            senderLabel = localSenderLabel,
            timestampMillis = System.currentTimeMillis(),
            message = IMAGE_PLACEHOLDER_MESSAGE,
            type = MeshPacketType.IMAGE_DONE,
            hop = 0,
            protocol = PACKET_PROTOCOL_VALUE_V5,
            blobId = blobId
        )
        val outcome = sendOrQueuePacket(donePacket, queueWhenFailed = false)
        finalizeOutboundVoiceBlob(blobId, voiceFileName, safeDuration, success = outcome != DispatchOutcome.FAILED)
    }

    private suspend fun finalizeOutboundVoiceBlob(
        blobId: String,
        voiceFileName: String,
        durationMillis: Long,
        success: Boolean
    ) {
        chatStore.updateLocalMessageStatus(
            messageId = blobId,
            status = if (success) MeshMessageStatus.SENT else MeshMessageStatus.FAILED
        )
        if (success) {
            runCatching {
                ensureGattMeshGeneralContact()
                saveLocalAudioMessage(
                    context = applicationContext,
                    sessionCode = chatStore.sessionCode,
                    uuid = blobId,
                    fileName = voiceFileName,
                    audioDurationMillis = durationMillis
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to persist local mesh voice blob=$blobId", throwable)
            }
        }
    }

    private fun handleInboundImageInit(packet: MeshPacket, sourceAddress: String) {
        if (profile.admission !is AdmissionPolicy.RequireVerifiedRole) {
            return
        }
        val blobId = packet.blobId ?: return
        val ivBase64 = packet.encryptedIvBase64 ?: return
        if (packet.keyId != profile.payloadKeyId) {
            Log.d(TAG, "[$sourceAddress] Dropped image blob with foreign keyId=${packet.keyId}")
            return
        }
        synchronized(lock) {
            if (inboundImageBlobs.size >= MESH_IMAGE_MAX_CONCURRENT_INBOUND) {
                val oldest = inboundImageBlobs.minByOrNull { it.value.lastActivityAtMillis }?.key
                if (oldest != null) {
                    inboundImageBlobs.remove(oldest)
                }
            }
            inboundImageBlobs[blobId] = InboundImageBlobState(
                sourceAddress = sourceAddress,
                senderLabel = packet.senderLabel,
                ivBase64 = ivBase64,
                keyId = packet.keyId ?: profile.payloadKeyId,
                mimeType = packet.blobMime,
                width = packet.blobWidth,
                height = packet.blobHeight,
                chunkCount = packet.blobChunkCount,
                cipherBytes = packet.blobCipherBytes,
                kind = packet.blobKind,
                durationMillis = packet.blobDurationMillis
            )
        }
        Log.d(
            TAG,
            "[$sourceAddress] Accepted image blob init id=$blobId chunks=${packet.blobChunkCount} " +
                "cipherBytes=${packet.blobCipherBytes}"
        )
    }

    private fun handleInboundImageChunk(packet: MeshPacket, sourceAddress: String) {
        val blobId = packet.blobId ?: return
        val dataBase64 = packet.blobDataBase64 ?: return
        val bytes = runCatching { Base64.decode(dataBase64, Base64.NO_WRAP) }.getOrNull() ?: return
        val completedState = synchronized(lock) {
            val state = inboundImageBlobs[blobId] ?: return
            if (packet.blobIndex !in state.chunks.indices) {
                return
            }
            if (state.chunks[packet.blobIndex] == null) {
                state.chunks[packet.blobIndex] = bytes
                state.receivedCount++
                state.receivedBytes += bytes.size
                if (state.receivedBytes > state.cipherBytes) {
                    inboundImageBlobs.remove(blobId)
                    return
                }
            }
            state.lastActivityAtMillis = System.currentTimeMillis()
            if (state.receivedCount == state.chunkCount) {
                inboundImageBlobs.remove(blobId)
                state
            } else {
                null
            }
        }
        if (completedState != null) {
            assembleInboundImageBlob(blobId, completedState)
        }
    }

    private fun handleInboundImageDone(packet: MeshPacket, sourceAddress: String) {
        val blobId = packet.blobId ?: return
        val completedState = synchronized(lock) {
            val state = inboundImageBlobs[blobId] ?: return
            if (state.receivedCount == state.chunkCount) {
                inboundImageBlobs.remove(blobId)
                state
            } else {
                // Chunks still in flight (writes can land out of order); the assembly completes
                // from handleInboundImageChunk, and the cleanup loop reaps stalled transfers.
                null
            }
        }
        if (completedState != null) {
            assembleInboundImageBlob(blobId, completedState)
        }
    }

    private fun handleAcceleratorBlob(initPacketPayload: ByteArray, cipher: ByteArray) {
        val packet = decodePacket(initPacketPayload) ?: return
        if (packet.type != MeshPacketType.IMAGE_INIT) {
            return
        }
        val blobId = packet.blobId ?: return
        val ivBase64 = packet.encryptedIvBase64 ?: return
        if (packet.keyId != profile.payloadKeyId) {
            return
        }
        if (cipher.size != packet.blobCipherBytes) {
            return
        }
        // Same dedup as the BLE lane: whichever lane lands first claims the blob id; the late
        // BLE INIT is then dropped as a duplicate packet.
        if (!rememberMessageId(packet.id, packet.timestampMillis)) {
            return
        }
        val state = InboundImageBlobState(
            sourceAddress = WIFI_AWARE_SOURCE_TAG,
            senderLabel = packet.senderLabel,
            ivBase64 = ivBase64,
            keyId = packet.keyId ?: profile.payloadKeyId,
            mimeType = packet.blobMime,
            width = packet.blobWidth,
            height = packet.blobHeight,
            chunkCount = packet.blobChunkCount,
            cipherBytes = packet.blobCipherBytes,
            kind = packet.blobKind,
            durationMillis = packet.blobDurationMillis
        )
        Log.i(TAG, "Accelerator delivered blob=$blobId bytes=${cipher.size}")
        assembleInboundImageBlob(blobId = blobId, state = state, preassembledCipher = cipher)
    }

    private fun assembleInboundImageBlob(
        blobId: String,
        state: InboundImageBlobState,
        preassembledCipher: ByteArray? = null
    ) {
        serviceScope.launch {
            val cipher: ByteArray
            if (preassembledCipher != null) {
                if (preassembledCipher.size != state.cipherBytes) {
                    return@launch
                }
                cipher = preassembledCipher
            } else {
                val assembled = ByteArray(state.receivedBytes)
                var offset = 0
                for (chunk in state.chunks) {
                    if (chunk == null) {
                        return@launch
                    }
                    chunk.copyInto(assembled, offset)
                    offset += chunk.size
                }
                if (offset != state.cipherBytes) {
                    Log.w(TAG, "Mesh image blob=$blobId size mismatch got=$offset expected=${state.cipherBytes}")
                    return@launch
                }
                cipher = assembled
            }
            val iv = runCatching { Base64.decode(state.ivBase64, Base64.NO_WRAP) }.getOrNull()
                ?: return@launch
            val plain = runCatching {
                AesGcm.decryptAesGcm(
                    keyBytes = meshPayloadKey,
                    iv = iv,
                    cipher = cipher,
                    aad = buildImageBlobAad(blobId = blobId, mimeType = state.mimeType)
                )
            }.getOrNull()
            if (plain == null || plain.isEmpty() || plain.size > MESH_IMAGE_MAX_PLAIN_BYTES) {
                Log.w(TAG, "Unable to decrypt mesh image blob=$blobId")
                return@launch
            }
            if (state.kind == BLOB_KIND_VOICE) {
                val voiceFileName = "$blobId.m4a"
                runCatching {
                    val voiceFile = voiceMessageFile(applicationContext, voiceFileName)
                    voiceFile.parentFile?.mkdirs()
                    voiceFile.writeBytes(plain)
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to store mesh voice blob=$blobId", throwable)
                    return@launch
                }
                runCatching {
                    ensureGattMeshGeneralContact()
                    saveRemoteAudioMessage(
                        context = applicationContext,
                        sessionCode = chatStore.sessionCode,
                        uuid = blobId,
                        fileName = voiceFileName,
                        audioDurationMillis = state.durationMillis
                    )
                }.onFailure { throwable ->
                    Log.w(TAG, "Unable to persist remote mesh voice blob=$blobId", throwable)
                }
                chatStore.appendRemoteVoiceMessage(
                    messageId = blobId,
                    senderLabel = state.senderLabel,
                    sourceAddress = state.sourceAddress,
                    voiceFileName = voiceFileName,
                    voiceDurationMillis = state.durationMillis
                )
                Log.i(TAG, "Mesh voice blob=$blobId assembled bytes=${plain.size} from=${state.sourceAddress}")
                return@launch
            }
            val mime = state.mimeType ?: "image/jpeg"
            val fileName = ImageFileUtils.fileNameFor(blobId, mime)
            val thumbnailName = ImageFileUtils.thumbnailNameFor(blobId, mime)
            val imageFile = imageMessageFile(applicationContext, fileName)
            runCatching {
                imageFile.parentFile?.mkdirs()
                imageFile.writeBytes(plain)
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to store mesh image blob=$blobId", throwable)
                return@launch
            }
            val thumbnailOk = generateImageThumbnail(
                source = imageFile,
                target = imageThumbnailFile(applicationContext, thumbnailName),
                mimeType = mime
            )
            runCatching {
                ensureGattMeshGeneralContact()
                saveRemoteImageMessage(
                    context = applicationContext,
                    sessionCode = chatStore.sessionCode,
                    uuid = blobId,
                    fileName = fileName,
                    thumbnailName = thumbnailName.takeIf { thumbnailOk },
                    width = state.width,
                    height = state.height,
                    mimeType = mime
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to persist remote mesh image blob=$blobId", throwable)
            }
            chatStore.appendRemoteImageMessage(
                messageId = blobId,
                senderLabel = state.senderLabel,
                sourceAddress = state.sourceAddress,
                imageFileName = fileName,
                imageThumbnailName = thumbnailName.takeIf { thumbnailOk },
                imageWidth = state.width,
                imageHeight = state.height
            )
            Log.i(TAG, "Mesh image blob=$blobId assembled bytes=${plain.size} from=${state.sourceAddress}")
        }
    }

    private fun buildImageBlobAad(blobId: String, mimeType: String?): ByteArray {
        return "blob|$blobId|${mimeType.orEmpty()}".toByteArray(UTF_8)
    }

    private fun decodePacket(payload: ByteArray): MeshPacket? {
        val text = payload.toString(UTF_8).trim()
        if (text.isEmpty() || text.length > MAX_PACKET_TEXT_LENGTH) {
            return null
        }

        return runCatching {
            val json = JSONObject(text)
            val id = json.optString(PACKET_FIELD_ID).trim()
            val sender = sanitizeSenderLabel(json.optString(PACKET_FIELD_SENDER))
            val timestamp = json.optLong(PACKET_FIELD_TIMESTAMP)
            val hop = json.optInt(PACKET_FIELD_HOP, 0)
            val protocol = json.optString(PACKET_FIELD_PROTOCOL).trim()
            val packetType = parsePacketType(json.optString(PACKET_FIELD_TYPE).trim(), protocol)
            val originProofJson = json.optString(PACKET_FIELD_ORIGIN_PROOF).trim()
                .takeIf { it.isNotEmpty() && it.length <= MAX_ORIGIN_PROOF_JSON_LENGTH }
            val originSignatureBase64 = json.optString(PACKET_FIELD_ORIGIN_SIGNATURE).trim()
                .takeIf { it.isNotEmpty() && it.length <= MAX_ORIGIN_SIGNATURE_LENGTH }
            val originAuthentication = if (
                !originProofJson.isNullOrBlank() &&
                !originSignatureBase64.isNullOrBlank()
            ) {
                originProofJson to originSignatureBase64
            } else {
                null
            }

            if (id.isEmpty() || id.length > MAX_MESSAGE_ID_LENGTH || !MESSAGE_ID_REGEX.matches(id)) {
                return null
            }
            if (
                protocol != PACKET_PROTOCOL_VALUE_V1 &&
                protocol != PACKET_PROTOCOL_VALUE_V2 &&
                protocol != PACKET_PROTOCOL_VALUE_V3 &&
                protocol != PACKET_PROTOCOL_VALUE_V4 &&
                protocol != PACKET_PROTOCOL_VALUE_V5
            ) {
                return null
            }
            if (sender.isEmpty() || sender.length > MAX_SENDER_LABEL_LENGTH) {
                return null
            }
            if (hop < 0 || hop > MAX_FORWARD_HOPS) {
                return null
            }
            when (packetType) {
                MeshPacketType.CHAT -> {
                    val encrypted = json.optBoolean(PACKET_FIELD_ENCRYPTED, false) ||
                        protocol == PACKET_PROTOCOL_VALUE_V3
                    if (encrypted) {
                        val keyId = json.optString(PACKET_FIELD_KEY_ID).trim()
                            .ifEmpty { profile.payloadKeyId }
                        val ivBase64 = json.optString(PACKET_FIELD_IV).trim()
                        val cipherBase64 = json.optString(PACKET_FIELD_CIPHER).trim()
                        if (
                            ivBase64.isEmpty() ||
                            cipherBase64.isEmpty() ||
                            ivBase64.length > MAX_ENCRYPTED_FIELD_LENGTH ||
                            cipherBase64.length > MAX_ENCRYPTED_FIELD_LENGTH
                        ) {
                            return null
                        }
                        val decryptedMessage = decryptMeshChatPayload(
                            messageId = id,
                            senderLabel = sender,
                            timestampMillis = timestamp,
                            keyId = keyId,
                            ivBase64 = ivBase64,
                            cipherBase64 = cipherBase64
                        )
                        MeshPacket(
                            id = id,
                            senderLabel = sender,
                            timestampMillis = timestamp,
                            message = decryptedMessage ?: ENCRYPTED_CHAT_PLACEHOLDER_MESSAGE,
                            type = MeshPacketType.CHAT,
                            hop = hop,
                            protocol = protocol,
                            encrypted = true,
                            keyId = keyId,
                            encryptedIvBase64 = ivBase64,
                            encryptedCipherBase64 = cipherBase64,
                            originProofJson = originAuthentication?.first,
                            originSignatureBase64 = originAuthentication?.second,
                            isReadable = decryptedMessage != null
                        )
                    } else {
                        val message = sanitizeMessage(json.optString(PACKET_FIELD_MESSAGE))
                        if (message.isEmpty() || message.length > MAX_CHAT_MESSAGE_LENGTH_CHARS) {
                            return null
                        }
                        MeshPacket(
                            id = id,
                            senderLabel = sender,
                            timestampMillis = timestamp,
                            message = message,
                            type = MeshPacketType.CHAT,
                            hop = hop,
                            protocol = protocol,
                            originProofJson = originAuthentication?.first,
                            originSignatureBase64 = originAuthentication?.second
                        )
                    }
                }

                MeshPacketType.RECEIPT -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V2) {
                        return null
                    }
                    val receiptType = parseReceiptType(
                        json.optString(PACKET_FIELD_RECEIPT_TYPE).trim()
                    ) ?: return null
                    val receiptIds = normalizeMessageIds(
                        json.optJSONArray(PACKET_FIELD_RECEIPT_IDS).toMessageIdList()
                    )
                    if (receiptIds.isEmpty()) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = RECEIPT_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.RECEIPT,
                        receiptType = receiptType,
                        receiptMessageIds = receiptIds,
                        hop = hop,
                        protocol = protocol
                    )
                }

                MeshPacketType.AUTH_CHALLENGE -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V4) {
                        return null
                    }
                    val nonce = json.optString(PACKET_FIELD_AUTH_NONCE).trim()
                    if (nonce.isEmpty() || nonce.length > MAX_AUTH_NONCE_LENGTH) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = AUTH_CHALLENGE_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.AUTH_CHALLENGE,
                        hop = hop,
                        protocol = protocol,
                        authNonce = nonce
                    )
                }

                MeshPacketType.AUTH_PROOF -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V4) {
                        return null
                    }
                    val nonce = json.optString(PACKET_FIELD_AUTH_NONCE).trim()
                    val proofJson = json.optString(PACKET_FIELD_AUTH_PROOF).trim()
                    if (
                        nonce.isEmpty() ||
                        nonce.length > MAX_AUTH_NONCE_LENGTH ||
                        proofJson.isEmpty() ||
                        proofJson.length > MAX_AUTH_PROOF_JSON_LENGTH
                    ) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = AUTH_PROOF_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.AUTH_PROOF,
                        hop = hop,
                        protocol = protocol,
                        authNonce = nonce,
                        authProofJson = proofJson
                    )
                }

                MeshPacketType.IMAGE_INIT -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V5) {
                        return null
                    }
                    val blobId = json.optString(PACKET_FIELD_BLOB_ID).trim()
                    val chunkCount = json.optInt(PACKET_FIELD_BLOB_COUNT, 0)
                    val cipherBytes = json.optInt(PACKET_FIELD_BLOB_BYTES, 0)
                    val ivBase64 = json.optString(PACKET_FIELD_IV).trim()
                    val keyId = json.optString(PACKET_FIELD_KEY_ID).trim()
                        .ifEmpty { profile.payloadKeyId }
                    if (blobId.isEmpty() || blobId.length > MAX_MESSAGE_ID_LENGTH ||
                        !MESSAGE_ID_REGEX.matches(blobId)
                    ) {
                        return null
                    }
                    if (chunkCount !in 1..MESH_IMAGE_MAX_CHUNKS) {
                        return null
                    }
                    if (cipherBytes !in 1..(MESH_IMAGE_MAX_PLAIN_BYTES + 64)) {
                        return null
                    }
                    if (ivBase64.isEmpty() || ivBase64.length > MAX_ENCRYPTED_FIELD_LENGTH) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = IMAGE_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.IMAGE_INIT,
                        hop = hop,
                        protocol = protocol,
                        encrypted = true,
                        keyId = keyId,
                        encryptedIvBase64 = ivBase64,
                        blobId = blobId,
                        blobChunkCount = chunkCount,
                        blobCipherBytes = cipherBytes,
                        blobMime = json.optString(PACKET_FIELD_BLOB_MIME).trim()
                            .take(64).takeIf { it.isNotEmpty() },
                        blobWidth = json.optInt(PACKET_FIELD_BLOB_WIDTH, 0).takeIf { it > 0 },
                        blobHeight = json.optInt(PACKET_FIELD_BLOB_HEIGHT, 0).takeIf { it > 0 },
                        blobKind = json.optString(PACKET_FIELD_BLOB_KIND).trim()
                            .take(16).takeIf { it.isNotEmpty() },
                        blobDurationMillis = json.optLong(PACKET_FIELD_BLOB_DURATION, 0L)
                            .takeIf { it in 1..MESH_VOICE_MAX_DURATION_MS }
                    )
                }

                MeshPacketType.IMAGE_CHUNK -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V5) {
                        return null
                    }
                    val blobId = json.optString(PACKET_FIELD_BLOB_ID).trim()
                    val blobIndex = json.optInt(PACKET_FIELD_BLOB_INDEX, -1)
                    val dataBase64 = json.optString(PACKET_FIELD_BLOB_DATA).trim()
                    if (blobId.isEmpty() || !MESSAGE_ID_REGEX.matches(blobId)) {
                        return null
                    }
                    if (blobIndex !in 0 until MESH_IMAGE_MAX_CHUNKS) {
                        return null
                    }
                    if (dataBase64.isEmpty() || dataBase64.length > MAX_ENCRYPTED_FIELD_LENGTH) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = IMAGE_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.IMAGE_CHUNK,
                        hop = hop,
                        protocol = protocol,
                        blobId = blobId,
                        blobIndex = blobIndex,
                        blobDataBase64 = dataBase64
                    )
                }

                MeshPacketType.IMAGE_DONE -> {
                    if (protocol != PACKET_PROTOCOL_VALUE_V5) {
                        return null
                    }
                    val blobId = json.optString(PACKET_FIELD_BLOB_ID).trim()
                    if (blobId.isEmpty() || !MESSAGE_ID_REGEX.matches(blobId)) {
                        return null
                    }
                    MeshPacket(
                        id = id,
                        senderLabel = sender,
                        timestampMillis = timestamp,
                        message = IMAGE_PLACEHOLDER_MESSAGE,
                        type = MeshPacketType.IMAGE_DONE,
                        hop = hop,
                        protocol = protocol,
                        blobId = blobId
                    )
                }
            }
        }.getOrNull()
    }

    private fun encodePacket(packet: MeshPacket): ByteArray {
        return packetToJson(packet).toString().toByteArray(UTF_8)
    }

    private fun packetToJson(packet: MeshPacket): JSONObject {
        return JSONObject().apply {
            put(PACKET_FIELD_ID, packet.id)
            put(PACKET_FIELD_SENDER, packet.senderLabel)
            put(PACKET_FIELD_TIMESTAMP, packet.timestampMillis)
            put(PACKET_FIELD_TYPE, packet.type.wireValue)
            put(PACKET_FIELD_HOP, packet.hop)
            put(PACKET_FIELD_PROTOCOL, packet.protocol)
            when (packet.type) {
                MeshPacketType.CHAT -> {
                    if (
                        packet.encrypted &&
                        !packet.encryptedIvBase64.isNullOrBlank() &&
                        !packet.encryptedCipherBase64.isNullOrBlank()
                    ) {
                        put(PACKET_FIELD_ENCRYPTED, true)
                        put(PACKET_FIELD_KEY_ID, packet.keyId ?: profile.payloadKeyId)
                        put(PACKET_FIELD_IV, packet.encryptedIvBase64)
                        put(PACKET_FIELD_CIPHER, packet.encryptedCipherBase64)
                        put(PACKET_FIELD_MESSAGE, ENCRYPTED_CHAT_PLACEHOLDER_MESSAGE)
                    } else {
                        put(PACKET_FIELD_MESSAGE, packet.message)
                    }
                    packet.originProofJson?.takeIf { it.isNotBlank() }?.let { proofJson ->
                        put(PACKET_FIELD_ORIGIN_PROOF, proofJson)
                    }
                    packet.originSignatureBase64?.takeIf { it.isNotBlank() }?.let { signature ->
                        put(PACKET_FIELD_ORIGIN_SIGNATURE, signature)
                    }
                }

                MeshPacketType.RECEIPT -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_RECEIPT_TYPE, packet.receiptType.wireValue)
                    put(
                        PACKET_FIELD_RECEIPT_IDS,
                        JSONArray(packet.receiptMessageIds)
                    )
                }

                MeshPacketType.AUTH_CHALLENGE -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_AUTH_NONCE, packet.authNonce)
                }

                MeshPacketType.AUTH_PROOF -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_AUTH_NONCE, packet.authNonce)
                    put(PACKET_FIELD_AUTH_PROOF, packet.authProofJson)
                }

                MeshPacketType.IMAGE_INIT -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_BLOB_ID, packet.blobId)
                    put(PACKET_FIELD_BLOB_COUNT, packet.blobChunkCount)
                    put(PACKET_FIELD_BLOB_BYTES, packet.blobCipherBytes)
                    put(PACKET_FIELD_KEY_ID, packet.keyId ?: profile.payloadKeyId)
                    put(PACKET_FIELD_IV, packet.encryptedIvBase64)
                    packet.blobMime?.takeIf { it.isNotBlank() }?.let { put(PACKET_FIELD_BLOB_MIME, it) }
                    packet.blobWidth?.let { put(PACKET_FIELD_BLOB_WIDTH, it) }
                    packet.blobHeight?.let { put(PACKET_FIELD_BLOB_HEIGHT, it) }
                    packet.blobKind?.takeIf { it.isNotBlank() }?.let { put(PACKET_FIELD_BLOB_KIND, it) }
                    packet.blobDurationMillis?.takeIf { it > 0L }?.let { put(PACKET_FIELD_BLOB_DURATION, it) }
                }

                MeshPacketType.IMAGE_CHUNK -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_BLOB_ID, packet.blobId)
                    put(PACKET_FIELD_BLOB_INDEX, packet.blobIndex)
                    put(PACKET_FIELD_BLOB_DATA, packet.blobDataBase64)
                }

                MeshPacketType.IMAGE_DONE -> {
                    put(PACKET_FIELD_MESSAGE, packet.message)
                    put(PACKET_FIELD_BLOB_ID, packet.blobId)
                }
            }
        }
    }

    private fun encryptMeshChatPayload(
        messageId: String,
        senderLabel: String,
        timestampMillis: Long,
        message: String
    ): EncryptedMeshPayload? {
        val aad = buildMeshChatAad(
            messageId = messageId,
            senderLabel = senderLabel,
            timestampMillis = timestampMillis,
            keyId = profile.payloadKeyId
        )
        val encrypted = runCatching {
            AesGcm.encryptAesGcm(
                keyBytes = meshPayloadKey,
                plaintext = message.toByteArray(UTF_8),
                aad = aad
            )
        }.getOrNull() ?: return null
        val ivBase64 = Base64.encodeToString(encrypted.iv, Base64.NO_WRAP)
        val cipherBase64 = Base64.encodeToString(encrypted.cipher, Base64.NO_WRAP)
        if (ivBase64.length > MAX_ENCRYPTED_FIELD_LENGTH || cipherBase64.length > MAX_ENCRYPTED_FIELD_LENGTH) {
            return null
        }
        return EncryptedMeshPayload(
            keyId = profile.payloadKeyId,
            ivBase64 = ivBase64,
            cipherBase64 = cipherBase64
        )
    }

    private fun decryptMeshChatPayload(
        messageId: String,
        senderLabel: String,
        timestampMillis: Long,
        keyId: String,
        ivBase64: String,
        cipherBase64: String
    ): String? {
        if (keyId != profile.payloadKeyId) {
            return null
        }
        val ivBytes = runCatching {
            Base64.decode(ivBase64, Base64.NO_WRAP)
        }.getOrNull() ?: return null
        if (ivBytes.size != MESH_GCM_IV_BYTES) {
            return null
        }
        val cipherBytes = runCatching {
            Base64.decode(cipherBase64, Base64.NO_WRAP)
        }.getOrNull() ?: return null
        if (cipherBytes.isEmpty()) {
            return null
        }
        val aad = buildMeshChatAad(
            messageId = messageId,
            senderLabel = senderLabel,
            timestampMillis = timestampMillis,
            keyId = keyId
        )
        val plaintext = runCatching {
            AesGcm.decryptAesGcm(
                keyBytes = meshPayloadKey,
                iv = ivBytes,
                cipher = cipherBytes,
                aad = aad
            )
        }.getOrNull() ?: return null
        val resolved = sanitizeMessage(plaintext.toString(UTF_8))
        if (resolved.isEmpty() || resolved.length > MAX_CHAT_MESSAGE_LENGTH_CHARS) {
            return null
        }
        return resolved
    }

    private fun buildMeshChatAad(
        messageId: String,
        senderLabel: String,
        timestampMillis: Long,
        keyId: String
    ): ByteArray {
        val payload = buildString(192) {
            append("id=")
            append(messageId)
            append('|')
            append("sender=")
            append(senderLabel)
            append('|')
            append("ts=")
            append(timestampMillis)
            append('|')
            append("kid=")
            append(keyId)
        }
        return payload.toByteArray(UTF_8)
    }


    private fun parsePacketType(rawType: String, protocol: String): MeshPacketType {
        return when {
            rawType.equals(MeshPacketType.RECEIPT.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V2 -> MeshPacketType.RECEIPT

            rawType.equals(MeshPacketType.AUTH_CHALLENGE.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V4 -> MeshPacketType.AUTH_CHALLENGE

            rawType.equals(MeshPacketType.AUTH_PROOF.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V4 -> MeshPacketType.AUTH_PROOF

            rawType.equals(MeshPacketType.IMAGE_INIT.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V5 -> MeshPacketType.IMAGE_INIT

            rawType.equals(MeshPacketType.IMAGE_CHUNK.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V5 -> MeshPacketType.IMAGE_CHUNK

            rawType.equals(MeshPacketType.IMAGE_DONE.wireValue, ignoreCase = true) &&
                protocol == PACKET_PROTOCOL_VALUE_V5 -> MeshPacketType.IMAGE_DONE

            else -> MeshPacketType.CHAT
        }
    }

    private fun parseReceiptType(rawType: String): ReceiptType? {
        if (rawType.isBlank()) {
            return null
        }
        return ReceiptType.entries.firstOrNull {
            it.wireValue.equals(rawType, ignoreCase = true)
        }
    }

    private fun normalizeMessageIds(messageIds: Collection<String>): List<String> {
        if (messageIds.isEmpty()) {
            return emptyList()
        }
        val normalized = LinkedHashSet<String>(messageIds.size)
        messageIds.forEach { rawId ->
            if (normalized.size >= MAX_RECEIPT_MESSAGE_IDS) {
                return@forEach
            }
            val id = rawId.trim()
            if (
                id.isNotEmpty() &&
                id.length <= MAX_MESSAGE_ID_LENGTH &&
                MESSAGE_ID_REGEX.matches(id)
            ) {
                normalized += id
            }
        }
        return normalized.toList()
    }

    private fun scheduleClientNotificationSettleFallback(address: String, gatt: BluetoothGatt) {
        val job = serviceScope.launch {
            delay(CLIENT_NOTIFICATION_ENABLE_TIMEOUT_MS)
            val currentJob = coroutineContext[Job]
            val settleDecision = synchronized(lock) {
                if (currentJob == null || clientNotificationSettleJobs[address] !== currentJob) {
                    return@synchronized NotificationSettleDecision.NONE
                }
                val current = clientPeers[address] ?: run {
                    clientNotificationSettleJobs.remove(address)
                    return@synchronized NotificationSettleDecision.NONE
                }
                if (current.gatt !== gatt || current.ready || current.messageOut == null) {
                    clientNotificationSettleJobs.remove(address)
                    return@synchronized NotificationSettleDecision.NONE
                }
                if (hasInboundPartialTransportLocked(address, InboundTransportChannel.CLIENT_NOTIFICATION)) {
                    clientNotificationSettleJobs.remove(address)
                    return@synchronized NotificationSettleDecision.RETRY
                }
                clientNotificationSettleJobs.remove(address)
                if (
                    current.connected &&
                    isIncomingWriteCapable(current.messageIn) &&
                    !shouldUseConservativeClientPacing(address)
                ) {
                    NotificationSettleDecision.PROMOTE_WRITE_ONLY
                } else {
                    NotificationSettleDecision.RECONNECT_WRITE_ONLY
                }
            }
            when (settleDecision) {
                NotificationSettleDecision.NONE -> Unit
                NotificationSettleDecision.RETRY -> {
                    Log.d(TAG, "[$address] Delaying write-only fallback because inbound notify packet is still in flight")
                    scheduleClientNotificationSettleFallback(address = address, gatt = gatt)
                }
                NotificationSettleDecision.PROMOTE_WRITE_ONLY -> {
                    promoteClientPeerInWriteOnlyMode(address = address, gatt = gatt)
                }
                NotificationSettleDecision.RECONNECT_WRITE_ONLY -> {
                    reconnectClientPeerInWriteOnlyMode(address = address, gatt = gatt)
                }
            }
        }
        synchronized(lock) {
            clientNotificationSettleJobs.remove(address)?.cancel()
            clientNotificationSettleJobs[address] = job
        }
    }

    private fun cancelClientNotificationSettle(address: String) {
        synchronized(lock) {
            clientNotificationSettleJobs.remove(address)
        }?.cancel()
    }

    private fun reconnectClientPeerInWriteOnlyMode(address: String, gatt: BluetoothGatt) {
        synchronized(lock) {
            clientNotificationBypassPeers += address
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                clientNoResponsePreferredPeers.remove(address)
            }
            val current = clientNoResponseSettleOverridesMs[address] ?: 0L
            if (current < INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS) {
                clientNoResponseSettleOverridesMs[address] = INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS
            }
        }
        Log.w(TAG, "[$address] Reconnecting client route in write-only mode after notification settle timeout")
        closeClientPeer(address)
        val reconnectDevice = synchronized(lock) {
            discoveredDevices[address] ?: serverDevices[address]
        } ?: gatt.device
        serviceScope.launch {
            delay(CLIENT_NOTIFICATION_TIMEOUT_RECONNECT_DELAY_MS)
            if (!runtimeActive || !hasBluetoothConnectPermission()) {
                return@launch
            }
            connectToPeer(
                device = reconnectDevice,
                address = address,
                allowFailureBackoffBypass = true
            )
        }
    }

    private fun promoteClientPeerInWriteOnlyMode(address: String, gatt: BluetoothGatt) {
        val promotedInPlace = synchronized(lock) {
            val current = clientPeers[address] ?: return@synchronized false
            if (current.gatt !== gatt || !current.connected || !isIncomingWriteCapable(current.messageIn)) {
                return@synchronized false
            }
            clientNotificationBypassPeers += address
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1) {
                clientNoResponsePreferredPeers.remove(address)
            }
            val settleOverride = clientNoResponseSettleOverridesMs[address] ?: 0L
            if (settleOverride < INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS) {
                clientNoResponseSettleOverridesMs[address] = INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS
            }
            true
        }
        if (!promotedInPlace) {
            reconnectClientPeerInWriteOnlyMode(address = address, gatt = gatt)
            return
        }
        Log.w(TAG, "[$address] Promoting current client route to write-only mode after notification settle timeout")
        promoteClientReady(address = address, gatt = gatt, reason = "notify-timeout-write-only")
    }

    private fun promoteClientReady(address: String, gatt: BluetoothGatt, reason: String) {
        val becameReady = synchronized(lock) {
            val current = clientPeers[address] ?: return@synchronized false
            if (current.gatt !== gatt) {
                return@synchronized false
            }
            if (current.ready) {
                return@synchronized false
            }
            val fullNotifyRouteReady = reason == "descriptor-write"
            resetClientWritePreferencesLocked(
                address = address,
                clearWriteOnlyBypass = fullNotifyRouteReady
            )
            current.ready = true
            clientSendRecoveryBlockedUntilMillis.remove(address)
            true
        }
        if (!becameReady) {
            return
        }
        BleKnownPeersStore.markKnown(applicationContext, address)
        val needsRouteWarmup = synchronized(lock) {
            address in clientNotificationBypassPeers
        }
        if (needsRouteWarmup) {
            rememberClientRouteWarmup(address)
        }
        Log.d(TAG, "[$address] Client route ready reason=$reason")
        resetPendingOutboundRetryBackoff(reason = "client-ready:$address")
        publishState()
        requestFlushPendingOutboundPackets()
        requestLocalSenderAnnouncement(address)
        requestPeerVerification(address)
    }

    private fun remoteIncomingWriteLikelyRequiresBond(
        permissions: Int,
        bondState: Int
    ): Boolean {
        val requiresEncryptedWrite = permissions and (
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED or
                BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM
            ) != 0
        val allowsPlainWrite = permissions and BluetoothGattCharacteristic.PERMISSION_WRITE != 0
        if (!requiresEncryptedWrite || allowsPlainWrite) {
            return false
        }
        return bondState != BluetoothDevice.BOND_BONDED
    }

    private fun maybeRecoverFromSecurityStatus(
        address: String,
        device: BluetoothDevice,
        status: Int,
        phase: String
    ): Boolean {
        if (!isSecurityFailureStatus(status)) {
            return false
        }
        return maybeRecoverFromSecurityFailure(
            address = address,
            device = device,
            reason = "$phase-status-$status"
        )
    }

    private fun maybeRecoverFromSecurityFailure(
        address: String,
        device: BluetoothDevice,
        reason: String
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        val now = System.currentTimeMillis()
        val canAttempt = synchronized(lock) {
            val lastAttemptAt = securityRecoveryAttemptAtMillis[address] ?: 0L
            if (now - lastAttemptAt < SECURITY_RECOVERY_RETRY_WINDOW_MS) {
                false
            } else {
                securityRecoveryAttemptAtMillis[address] = now
                true
            }
        }
        if (!canAttempt) {
            return false
        }
        val bondState = getDeviceBondStateSafely(device)
        val bondRequested = if (bondState != BluetoothDevice.BOND_BONDED) {
            tryCreateBondSafely(device)
        } else {
            false
        }
        Log.w(
            TAG,
            "[$address] Security recovery reason=$reason bondState=$bondState createBond=$bondRequested"
        )
        serviceScope.launch {
            delay(SECURITY_RECOVERY_RECONNECT_DELAY_MS)
            closeClientPeer(address)
            connectToPeer(
                device = device,
                address = address,
                allowFailureBackoffBypass = true
            )
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun getDeviceBondStateSafely(device: BluetoothDevice): Int {
        if (!hasBluetoothConnectPermission()) {
            return BluetoothDevice.BOND_NONE
        }
        return runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
    }

    @SuppressLint("MissingPermission")
    private fun tryCreateBondSafely(device: BluetoothDevice): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        return runCatching { device.createBond() }.getOrDefault(false)
    }

    private fun isSecurityFailureStatus(status: Int): Boolean {
        return status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
            status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ||
            status == GATT_STATUS_INSUFFICIENT_AUTHORIZATION ||
            status == GATT_STATUS_INSUFFICIENT_KEY_SIZE
    }

    private fun JSONArray?.toMessageIdList(): List<String> {
        if (this == null || length() <= 0) {
            return emptyList()
        }
        return buildList(length()) {
            for (index in 0 until length()) {
                val value = optString(index).trim()
                if (value.isNotEmpty()) {
                    add(value)
                }
            }
        }
    }

    private fun consumeInboundBudget(address: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val existing = inboundRateWindows[address]
            if (existing == null || now - existing.windowStartMillis >= INBOUND_RATE_WINDOW_MS) {
                inboundRateWindows[address] = InboundRateWindow(windowStartMillis = now, count = 1)
                return true
            }
            if (existing.count >= INBOUND_RATE_MAX_PACKETS) {
                return false
            }
            inboundRateWindows[address] = existing.copy(count = existing.count + 1)
            return true
        }
    }

    private fun rememberMessageId(id: String, timestampMillis: Long): Boolean {
        synchronized(lock) {
            if (seenMessageIds.containsKey(id)) {
                return false
            }
            seenMessageIds[id] = timestampMillis
            while (seenMessageIds.size > MAX_TRACKED_MESSAGE_IDS) {
                val firstKey = seenMessageIds.keys.firstOrNull() ?: break
                seenMessageIds.remove(firstKey)
            }
            return true
        }
    }

    private fun isPacketTimestampValid(timestampMillis: Long): Boolean {
        if (timestampMillis <= 0L) {
            return false
        }
        val now = System.currentTimeMillis()
        if (timestampMillis > now + MAX_FUTURE_CLOCK_SKEW_MS) {
            return false
        }
        if (timestampMillis < now - MAX_MESSAGE_AGE_MS) {
            return false
        }
        return true
    }

    private fun startCleanupLoop() {
        cleanupJob?.cancel()
        cleanupJob = serviceScope.launch {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                stopClassicDiscoveryIfRunning(reason = "cleanup")
                val now = System.currentTimeMillis()
                val retryCandidates = LinkedHashMap<String, BluetoothDevice>()
                val fallbackBypassCandidates = LinkedHashSet<String>()
                val staleServerAddresses = LinkedHashSet<String>()
                val staleClientAddresses = LinkedHashSet<String>()
                captureSystemConnectedSnapshot(now)
                synchronized(lock) {
                    discoveredPeers.entries.removeIf { (_, seenAt) ->
                        now - seenAt > STALE_DISCOVERY_WINDOW_MS
                    }
                    val staleAddresses = discoveredPeers.keys.toSet()
                    discoveredDevices.entries.removeIf { (address, _) -> address !in staleAddresses }
                    firstDiscoveredPeers.entries.removeIf { (address, _) -> address !in staleAddresses }
                    discoveredPeerInitiatorRanks.entries.removeIf { (address, _) -> address !in staleAddresses }
                    lastConnectAttemptMillis.entries.removeIf { (address, lastAttempt) ->
                        val isActiveAddress = address in staleAddresses ||
                            address in serverDevices ||
                            address in sharedModeObservedInboundConnectedAt ||
                            address in clientPeers
                        !isActiveAddress || now - lastAttempt > STALE_DISCOVERY_WINDOW_MS
                    }
                    clientFailureBackoff.entries.removeIf { (address, state) ->
                        val isActiveAddress = address in staleAddresses ||
                            address in serverDevices ||
                            address in sharedModeObservedInboundConnectedAt ||
                            address in clientPeers
                        !isActiveAddress ||
                            now - state.lastFailureAtMillis > CLIENT_FAILURE_RETENTION_MS
                    }
                    inboundImageBlobs.entries.removeIf { (_, blob) ->
                        now - blob.lastActivityAtMillis > MESH_IMAGE_INBOUND_TIMEOUT_MS
                    }
                    inboundRateWindows.entries.removeIf { (_, state) ->
                        now - state.windowStartMillis > INBOUND_RATE_WINDOW_MS
                    }
                    inboundChunkReceivers.entries.removeIf { (address, receivers) ->
                        val shouldRemove = address !in staleAddresses
                        if (shouldRemove) {
                            receivers.values.forEach { receiver -> receiver.reset() }
                        }
                        shouldRemove
                    }
                    seenMessageIds.entries.removeIf { (_, timestamp) ->
                        now - timestamp > MAX_MESSAGE_AGE_MS
                    }
                    val activePeerAddresses = buildSet {
                        addAll(staleAddresses)
                        addAll(serverDevices.keys)
                        addAll(sharedModeObservedInboundConnectedAt.keys)
                        addAll(clientPeers.keys)
                    }
                    outboundSendLocks.entries.removeIf { (address, _) -> address !in activePeerAddresses }
                    peerSenderLabels.entries.removeIf { (address, _) -> address !in activePeerAddresses }

                    val connectedSnapshot = freshSystemConnectedSnapshotLocked(now)
                    if (connectedSnapshot != null) {
                        serverDevices.keys
                            .filter { address -> address !in connectedSnapshot }
                            .forEach { address ->
                                clearServerPeerLocked(address)
                                staleServerAddresses += address
                            }
                        clientPeers.values
                            .forEach { peer ->
                                val hasGatt = peer.gatt != null
                                if (!hasGatt || peer.address in connectedSnapshot) {
                                    return@forEach
                                }
                                if (!peer.connected) {
                                    val attemptedAt = lastConnectAttemptMillis[peer.address] ?: 0L
                                    if (
                                        now - attemptedAt < outboundConnectPendingGraceMillis(
                                            hasQueuedPayload = pendingOutboundPackets.isNotEmpty()
                                        )
                                    ) {
                                        return@forEach
                                    }
                                }
                                staleClientAddresses += peer.address
                            }
                    }

                    discoveredDevices.forEach { (address, device) ->
                        val hasOutbound = hasUsableOrPendingOutboundClientRouteLocked(
                            address = address,
                            now = now,
                            systemSnapshot = connectedSnapshot,
                            queuedRecovery = pendingOutboundPackets.isNotEmpty()
                        )
                        val hasInboundReady = serverDevices.containsKey(address) &&
                            serverNotifyEnabled[address] == true
                        val hasQueuedOutbound = pendingOutboundPackets.isNotEmpty()
                        if (
                            !hasOutbound &&
                            !hasInboundReady &&
                            shouldInitiateConnection(
                                address,
                                queuedPayloadHandoverAllowed = hasQueuedOutbound
                            )
                        ) {
                            retryCandidates[address] = device
                            if (shouldBypassFailureBackoffForAddressLocked(address, now)) {
                                fallbackBypassCandidates += address
                            }
                        }
                    }

                    // Some phones keep an inbound GATT server link but never complete CCCD/notify.
                    // Recheck these peers periodically so chat send paths do not get stuck at ready=0.
                    val inboundFallbackAddresses = linkedSetOf<String>().apply {
                        addAll(serverDevices.keys)
                        addAll(sharedModeObservedInboundConnectedAt.keys)
                    }
                    inboundFallbackAddresses.forEach { address ->
                        val device = discoveredDevices[address] ?: serverDevices[address] ?: return@forEach
                        val hasOutbound = hasUsableOrPendingOutboundClientRouteLocked(
                            address = address,
                            now = now,
                            systemSnapshot = connectedSnapshot,
                            queuedRecovery = pendingOutboundPackets.isNotEmpty()
                        )
                        val inboundNotifyEnabled = serverNotifyEnabled[address] == true
                        val hasQueuedOutbound = pendingOutboundPackets.isNotEmpty()
                        if (
                            !hasOutbound &&
                            !inboundNotifyEnabled &&
                            shouldInitiateConnection(
                                address,
                                queuedPayloadHandoverAllowed = hasQueuedOutbound
                            )
                        ) {
                            retryCandidates[address] = device
                            fallbackBypassCandidates += address
                        }
                    }
                }

                staleServerAddresses.forEach { address ->
                    cancelInboundFallbackConnect(address)
                    Log.w(TAG, "[$address] Cleared stale inbound link from system connection snapshot")
                }
                staleClientAddresses.forEach { address ->
                    Log.w(TAG, "[$address] Cleared stale outbound link from system connection snapshot")
                    closeClientPeer(address)
                }
                retryCandidates.forEach { (address, device) ->
                    connectToPeer(
                        device = device,
                        address = address,
                        allowFailureBackoffBypass = address in fallbackBypassCandidates
                    )
                }
                requestFlushPendingOutboundPackets()
                publishState()
            }
        }
    }

    private fun publishState() {
        val snapshot = synchronized(lock) {
            val serverSet = connectedInboundMeshAddressesLocked()
            val readyServerSet = inboundMeshServerAddressesLocked()
            val clientSet = clientPeers.values
                .filter { it.connected && it.gatt != null }
                .map { it.address }
                .toSet()
            val clientReadySet = clientPeers.values
                .filter { it.ready && it.connected && it.gatt != null && it.messageIn != null }
                .map { it.address }
                .toSet()
            val serverReadySet = readyServerSet
                .filter { address -> serverNotifyEnabled[address] == true }
                .toSet()
            val connectedAddresses = (serverSet + clientSet).toSortedSet()
            val peers = connectedAddresses.map { address ->
                val verification = peerVerificationByAddress[address]
                GattMeshConnectedPeer(
                    address = address,
                    displayName = resolveConnectedPeerDisplayNameLocked(address),
                    verificationStatus = if (verification != null) {
                        GattMeshPeerVerificationStatus.VERIFIED
                    } else {
                        GattMeshPeerVerificationStatus.UNVERIFIED
                    },
                    verifiedRole = verification?.role,
                    verifiedAtMillis = verification?.verifiedAtMillis
                )
            }
            PublishStateSnapshot(
                connectedCount = connectedAddresses.size,
                discoveredCount = discoveredPeers.size,
                sendReadyCount = (clientReadySet + serverReadySet).size,
                connectedPeers = peers
            )
        }
        val connectedCount = snapshot.connectedCount
        val discoveredCount = snapshot.discoveredCount
        val sendReadyCount = snapshot.sendReadyCount

        if (sendReadyCount > 0) {
            requestFlushPendingOutboundPackets()
        }

        if (
            connectedCount != lastLoggedConnectedCount ||
            sendReadyCount != lastLoggedReadyCount ||
            discoveredCount != lastLoggedDiscoveredCount
        ) {
            lastLoggedConnectedCount = connectedCount
            lastLoggedReadyCount = sendReadyCount
            lastLoggedDiscoveredCount = discoveredCount
            Log.d(
                TAG,
                "State updated: connected=$connectedCount ready=$sendReadyCount discovered=$discoveredCount active=$runtimeActive"
            )
        }

        stateFlow.update {
            it.copy(
                isEnabled = runtimeActive,
                isScanning = BleScanCoordinator.isActive(profile.scanCoordinatorOwner),
                connectedPeerCount = connectedCount,
                discoveredPeerCount = discoveredCount,
                sendReadyPeerCount = sendReadyCount,
                connectedPeers = snapshot.connectedPeers,
            )
        }
        if (
            connectedCount != lastNotifiedConnectedCount ||
            sendReadyCount != lastNotifiedReadyCount ||
            discoveredCount != lastNotifiedDiscoveredCount
        ) {
            lastNotifiedConnectedCount = connectedCount
            lastNotifiedReadyCount = sendReadyCount
            lastNotifiedDiscoveredCount = discoveredCount
            updateForegroundNotification(
                connectedCount = connectedCount,
                sendReadyCount = sendReadyCount,
                discoveredCount = discoveredCount
            )
        }
    }

    private fun resolveConnectedPeerDisplayNameLocked(address: String): String {
        val senderLabel = peerSenderLabels[address]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (senderLabel != null) {
            return sanitizeSenderLabel(senderLabel)
        }
        val bluetoothName = resolvePeerBluetoothNameLocked(address)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::sanitizeSenderLabel)
        if (!bluetoothName.isNullOrEmpty()) {
            return bluetoothName
        }
        return getString(R.string.rescue_unknown_user)
    }

    private fun resolveConnectedPeerDisplayName(address: String): String {
        return synchronized(lock) {
            resolveConnectedPeerDisplayNameLocked(address)
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolvePeerBluetoothNameLocked(address: String): String? {
        if (!hasBluetoothConnectPermission()) {
            return null
        }
        val candidateNames = buildList {
            add(serverDevices[address]?.name)
            add(clientPeers[address]?.gatt?.device?.name)
            add(discoveredDevices[address]?.name)
        }
        return candidateNames
            .asSequence()
            .mapNotNull { name ->
                runCatching { name?.trim() }.getOrNull()
            }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun sendGattResponse(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray
    ) {
        if (!hasBluetoothConnectPermission()) {
            return
        }
        try {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        } catch (securityException: SecurityException) {
            Log.w(TAG, "sendResponse blocked by permission for ${normalizeAddress(device.address)}", securityException)
        }
    }

    private suspend fun resolveLocalSenderLabel(): String {
        val saved = runCatching {
            getSavedUserName(applicationContext).first().trim()
        }.getOrDefault("")
        return sanitizeSenderLabel(saved)
            .takeIf { it.isNotBlank() }
            ?: defaultSenderLabel()
    }

    private fun defaultSenderLabel(): String {
        return NotificationLocalization.localizedContext(this)
            .getString(R.string.gatt_mesh_default_sender_label)
    }

    private fun isUsingDefaultSenderLabel(label: String): Boolean {
        return label == LEGACY_DEFAULT_SENDER_LABEL || label == defaultSenderLabel()
    }

    private suspend fun isGattMeshRuntimeEnabledInSettings(): Boolean {
        val publicMeshKey = booleanPreferencesKey(profile.enabledPrefKey)
        return runCatching {
            val prefs = applicationContext.settingsDataStore.data.first()
            prefs[publicMeshKey] ?: false
        }.getOrDefault(false)
    }

    private fun requestRuntimeSelfHeal(reason: String) {
        if (runtimeActive) {
            return
        }
        serviceScope.launch {
            if (runtimeActive) {
                return@launch
            }
            if (!isGattMeshRuntimeEnabledInSettings()) {
                return@launch
            }
            Log.w(TAG, "Self-healing gatt mesh runtime reason=$reason")
            startMeshRuntime()
        }
    }

    @SuppressLint("HardwareIds")
    private fun resolveLocalInitiatorSalt(): Int {
        val macSalt = getLocalAdapterAddressOrNull()
            ?.takeIf { it.isNotBlank() && it != INVALID_MAC_ADDRESS }
            ?.hashCode()
        if (macSalt != null) {
            return macSalt
        }
        val androidIdSalt = runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.hashCode()
        return androidIdSalt ?: packageName.hashCode()
    }

    private fun sanitizeSenderLabel(raw: String): String {
        return GattMeshTextSanitizer.sanitize(
            raw = raw,
            maxLength = MAX_SENDER_LABEL_LENGTH,
            collapseWhitespace = true
        )
    }

    @SuppressLint("HardwareIds")
    private fun getLocalAdapterAddressOrNull(): String? {
        if (!hasBluetoothConnectPermission()) {
            return null
        }
        val address = try {
            bluetoothAdapter?.address
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Reading local adapter address blocked by permission", securityException)
            null
        }
        return address?.let(::normalizeAddress)
    }

    private fun sanitizeMessage(raw: String): String {
        return GattMeshTextSanitizer.sanitize(
            raw = raw,
            maxLength = MAX_CHAT_MESSAGE_LENGTH_CHARS,
            collapseWhitespace = false
        )
    }

    private fun maxPayloadForMtu(mtu: Int): Int {
        return (mtu.coerceAtLeast(DEFAULT_ATT_MTU) - ATT_WRITE_OVERHEAD_BYTES).coerceAtLeast(1)
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    private fun hasRequiredBluetoothPermissions(): Boolean {
        return hasBluetoothScanPermission() &&
            hasBluetoothConnectPermission() &&
            hasBluetoothAdvertisePermission()
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val bluetoothScan = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val fineLocation = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            bluetoothScan && fineLocation
        } else {
            val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGattQuietly(gatt: BluetoothGatt?) {
        if (gatt == null || !hasBluetoothConnectPermission()) {
            return
        }
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun stopSelfSafely() {
        stopMeshRuntime(clearError = false)
        stopSelf()
    }

    private fun updateForegroundNotification(
        connectedCount: Int,
        sendReadyCount: Int,
        discoveredCount: Int
    ) {
        if (!runtimeActive) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            profile.notificationId,
            GattMeshNotificationFactory.build(
                context = this,
                connectedCount = connectedCount,
                notificationChannelId = profile.notificationChannelId,
                requestCode = GATT_MESH_NOTIFICATION_REQUEST_CODE,
                sessionCode = chatStore.sessionCode
            )
        )
    }

    private fun ensureNotificationChannel() {
        GattMeshNotificationFactory.ensureChannel(
            context = this,
            notificationChannelId = profile.notificationChannelId
        )
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertiseRetryJob?.cancel()
            advertiseRetryJob = null
            advertiseConflictRetryAttempts = 0
            Log.d(TAG, "Gatt mesh advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            if (
                errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED &&
                runtimeActive &&
                advertiseConflictRetryAttempts < MAX_ADVERTISE_CONFLICT_RETRIES
            ) {
                advertiseConflictRetryAttempts += 1
                val retryAttempt = advertiseConflictRetryAttempts
                Log.w(
                    TAG,
                    "Gatt mesh advertising already started elsewhere; retrying attempt=$retryAttempt"
                )
                advertiseRetryJob?.cancel()
                advertiseRetryJob = serviceScope.launch {
                    delay(ADVERTISE_CONFLICT_RETRY_DELAY_MS)
                    if (!runtimeActive) {
                        return@launch
                    }
                    startAdvertising()
                }
                return
            }
            Log.w(TAG, "Gatt mesh advertising failed code=$errorCode")
            publishErrorAndStop(R.string.gatt_mesh_error_advertise_failed)
        }
    }

    private data class ClientPeer(
        val address: String,
        var gatt: BluetoothGatt? = null,
        var messageIn: BluetoothGattCharacteristic? = null,
        var messageOut: BluetoothGattCharacteristic? = null,
        var mtu: Int = DEFAULT_ATT_MTU,
        var connected: Boolean = false,
        var serviceDiscoveryRetries: Int = 0,
        var cacheRefreshAttempted: Boolean = false,
        var ready: Boolean = false,
    )

    private class ClientWriteTicket(
        val gatt: BluetoothGatt,
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var status: Int = BluetoothGatt.GATT_FAILURE
    )

    private class ServerNotifyTicket(
        val latch: CountDownLatch = CountDownLatch(1),
        @Volatile var status: Int = BluetoothGatt.GATT_FAILURE
    )

    private enum class ClientWriteAttemptResult {
        SUCCESS,
        BUSY,
        TIMED_OUT,
        FAILED
    }

    private enum class NotificationSettleDecision {
        NONE,
        RETRY,
        PROMOTE_WRITE_ONLY,
        RECONNECT_WRITE_ONLY
    }

    private enum class InboundTransportChannel(val logTag: String) {
        CLIENT_NOTIFICATION("client"),
        SERVER_WRITE("server")
    }

    companion object {
        private const val TAG = "GattMeshFgs"
        private const val ACTION_START = "com.auralis.crisisconnect.action.gattmesh.START"
        private const val ACTION_STOP = "com.auralis.crisisconnect.action.gattmesh.STOP"
        private const val ACTION_RECONCILE_SOS_MODE = "com.auralis.crisisconnect.action.gattmesh.RECONCILE_SOS_MODE"

        private const val GATT_MESH_NOTIFICATION_REQUEST_CODE = 3056

        private const val PACKET_FIELD_ID = "id"
        private const val PACKET_FIELD_SENDER = "sender"
        private const val PACKET_FIELD_TIMESTAMP = "timestamp"
        private const val PACKET_FIELD_MESSAGE = "message"
        private const val PACKET_FIELD_TYPE = "type"
        private const val PACKET_FIELD_RECEIPT_TYPE = "receiptType"
        private const val PACKET_FIELD_RECEIPT_IDS = "receiptIds"
        private const val PACKET_FIELD_AUTH_NONCE = "authNonce"
        private const val PACKET_FIELD_AUTH_PROOF = "authProof"
        private const val PACKET_FIELD_ORIGIN_PROOF = "originProof"
        private const val PACKET_FIELD_ORIGIN_SIGNATURE = "originSignature"
        private const val PACKET_FIELD_HOP = "hop"
        private const val PACKET_FIELD_PROTOCOL = "protocol"
        private const val PACKET_FIELD_ENCRYPTED = "encrypted"
        private const val PACKET_FIELD_KEY_ID = "kid"
        private const val PACKET_FIELD_IV = "iv"
        private const val PACKET_FIELD_CIPHER = "cipher"
        private const val PERSISTED_QUEUE_FIELD_VERSION = "version"
        private const val PERSISTED_QUEUE_FIELD_PACKETS = "packets"

        private const val MAX_CHAT_MESSAGE_LENGTH_CHARS = 1024
        private const val MAX_ENCRYPTED_FIELD_LENGTH = 4096
        private const val MAX_PACKET_BYTES = 4096
        private const val TRANSPORT_HEADER_BYTES = 2
        private const val MAX_TRANSPORT_PACKET_BYTES = MAX_PACKET_BYTES + TRANSPORT_HEADER_BYTES
        private const val FRAGILE_ROUTE_MAX_AUTHENTICATED_CHAT_PACKET_BYTES = 1_024
        private const val INITIATOR_RANK_BYTES = 4
        // Development-only manufacturer id used to carry mesh initiator rank in advertising payload.
        private const val INITIATOR_RANK_MANUFACTURER_ID = 0x0F0F
        private const val MAX_PACKET_TEXT_LENGTH = 4096
        private const val MAX_AUTH_NONCE_LENGTH = 128
        private const val MAX_AUTH_PROOF_JSON_LENGTH = 1800
        private const val MAX_ORIGIN_PROOF_JSON_LENGTH = 2200
        private const val MAX_ORIGIN_SIGNATURE_LENGTH = 256
        private const val DEFAULT_ATT_MTU = 23
        private const val DESIRED_MTU = 517
        private const val ATT_WRITE_OVERHEAD_BYTES = 3
        private const val MAX_TRACKED_MESSAGE_IDS = 4096
        private const val MAX_MESSAGE_ID_LENGTH = 128
        private const val MAX_SENDER_LABEL_LENGTH = 48
        private const val MAX_RECEIPT_MESSAGE_IDS = 40
        private const val MAX_FORWARD_HOPS = 4
        private const val INITIATOR_RANK_VALUE_MASK = 0x7FFFFFFF
        private const val LEGACY_INITIATOR_RANK_PENALTY_MASK = 0x80000000.toInt()
        private const val MAX_MESSAGE_AGE_MS = 24 * 60 * 60 * 1000L
        private const val MAX_FUTURE_CLOCK_SKEW_MS = 2 * 60 * 1000L
        private const val MAX_ORIGIN_PROOF_AGE_MS = 10 * 60 * 1000L
        // Keep restore age aligned with the UI restore policy. Older queued packets are already
        // shown as failed on restore and should not keep destabilizing newly recovered links.
        private const val MAX_PENDING_OUTBOUND_PACKET_AGE_MS = 2 * 60 * 1000L
        private const val CLEANUP_INTERVAL_MS = 5_000L
        private const val PENDING_OUTBOUND_RECOVERY_INTERVAL_MS = 1_000L
        private const val PENDING_OUTBOUND_RETRY_BACKOFF_BASE_MS = 1_500L
        private const val PENDING_OUTBOUND_RETRY_BACKOFF_MAX_MS = 15_000L
        private const val STALE_DISCOVERY_WINDOW_MS = 30_000L
        private const val SYSTEM_CONNECTED_SNAPSHOT_MAX_AGE_MS = 15_000L
        private const val INBOUND_RATE_WINDOW_MS = 10_000L
        private const val INBOUND_RATE_MAX_PACKETS = 180
        private const val CONNECT_RETRY_COOLDOWN_MS = 4_000L
        private const val OUTBOUND_CONNECT_PENDING_GRACE_MS = 20_000L
        private const val OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS = 45_000L
        private const val QUEUED_OUTBOUND_CONNECT_PENDING_GRACE_MS = 6_000L
        private const val QUEUED_OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS = 10_000L
        private const val REDISCOVERED_OUTBOUND_CONNECT_PENDING_GRACE_MS = 6_000L
        private const val REDISCOVERED_OUTBOUND_CONNECT_PENDING_GRACE_ANDROID_O_MS = 8_000L
        private const val INBOUND_NOTIFY_GRACE_MS = 8_000L
        private const val INBOUND_INITIATOR_HANDOVER_DELAY_MS = 25_000L
        private const val QUEUED_OUTBOUND_INITIATOR_HANDOVER_DELAY_MS = 4_000L
        private const val INVALID_LOCAL_ADDRESS_CONNECT_DELAY_MS = 5_000L
        private const val STALE_CLIENT_CONNECT_MS = 12_000L
        private const val QUEUED_OUTBOUND_STALE_CLIENT_CONNECT_MS = 10_000L
        private const val CLIENT_SEND_FAILURE_RECONNECT_DELAY_MS = 350L
        private const val CLIENT_NOTIFICATION_TIMEOUT_RECONNECT_DELAY_MS = 200L
        private const val SERVICE_DISCOVERY_RETRY_DELAY_MS = 700L
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 3
        private const val AUTO_CONNECT_RECOVERY_FAILURE_THRESHOLD = 2
        private const val GATT_SERVER_OPEN_MAX_ATTEMPTS = 3
        private const val GATT_SERVER_OPEN_RETRY_DELAY_MS = 200L
        private const val GATT_SERVER_ADD_SERVICE_MAX_ATTEMPTS = 4
        private const val GATT_SERVER_ADD_SERVICE_RETRY_DELAY_MS = 120L
        private const val LEGACY_GATT_SERVER_RECOVERY_BASE_DELAY_MS = 12_500L
        private const val LEGACY_GATT_SERVER_RECOVERY_BACKOFF_STEP_MS = 5_000L
        private const val POST_ADD_SERVICE_STARTUP_RECOVERY_DELAY_MS = 350L
        private const val LOCAL_GATT_SERVER_STARTUP_FALLBACK_DELAY_MS = 1_500L
        private const val SERVICE_READY_TIMEOUT_MS = 10_000L
        private const val STARTUP_TIMEOUT_RECOVERY_GRACE_MS = 1_000L
        private const val SHARED_GATT_ATTACH_RETRY_DELAY_MS = 150L
        private const val SHARED_GATT_ATTACH_MAX_ATTEMPTS = 8
        private const val CLIENT_FAILURE_BASE_DELAY_GENERIC_MS = 3_000L
        private const val CLIENT_FAILURE_BASE_DELAY_STATUS_147_MS = 8_000L
        private const val CLIENT_FAILURE_MAX_DELAY_MS = 60_000L
        // Keep jitter non-zero so peers do not reconnect in synchronized loops.
        private const val CLIENT_FAILURE_GENERIC_JITTER_MS = 900L
        private const val CLIENT_FAILURE_STATUS_133_JITTER_MS = 2_500L
        private const val CLIENT_FAILURE_STATUS_147_JITTER_MS = 4_000L
        private const val CLIENT_FAILURE_RESET_WINDOW_MS = 30_000L
        private const val CLIENT_FAILURE_RETENTION_MS = 120_000L
        private const val MAX_CLIENT_FAILURE_BACKOFF_STEP = 4
        private const val SCAN_STOP_SETTLE_BEFORE_CONNECT_MS = 300L
        private const val SCAN_RESUME_CHECK_INTERVAL_MS = 750L
        private const val CHUNK_WRITE_MAX_ATTEMPTS = 12
        private const val CHUNK_WRITE_RETRY_BASE_DELAY_MS = 30L
        private const val CLIENT_WRITE_BUSY_RETRY_BASE_DELAY_MS = 75L
        private const val CLIENT_NOTIFICATION_ENABLE_TIMEOUT_MS = 2_500L
        private const val CLIENT_WRITE_ACK_TIMEOUT_MS = 1_500L
        private const val LEGACY_CLIENT_WRITE_ACK_TIMEOUT_MS = 3_500L
        private const val WRITE_ONLY_CLIENT_WRITE_ACK_TIMEOUT_MS = 5_000L
        private const val CLIENT_WRITE_POST_ACK_SETTLE_MS = 8L
        private const val LEGACY_CLIENT_WRITE_POST_ACK_SETTLE_MS = 18L
        private const val CLIENT_NO_RESPONSE_SETTLE_MS = 12L
        private const val CLIENT_NO_RESPONSE_PREFERRED_SETTLE_MS = 350L
        private const val CLIENT_WITH_RESPONSE_PREFERRED_MS = 12_000L
        private const val CLIENT_ROUTE_WARMUP_MS = 2_500L
        private const val LEGACY_CLIENT_ROUTE_WARMUP_MS = 5_000L
        private const val CLIENT_PACKET_SETTLE_MS = 180L
        private const val LEGACY_CLIENT_PACKET_SETTLE_MS = 420L
        private const val INITIAL_WRITE_ONLY_NO_RESPONSE_SETTLE_MS = 900L
        private const val WRITE_ONLY_NO_RESPONSE_SETTLE_STEP_MS = 250L
        private const val MAX_WRITE_ONLY_NO_RESPONSE_SETTLE_MS = 2_000L
        private const val SERVER_NOTIFY_ACK_TIMEOUT_MS = 1_500L
        private const val SERVER_NOTIFY_CALLBACK_BYPASS_SETTLE_MS = 60L
        private const val SERVER_NOTIFY_POST_ACK_SETTLE_MS = 8L
        private const val P2P_CHAT_PEER_SUPPRESSION_MS = 45_000L
        private const val SECURITY_RECOVERY_RECONNECT_DELAY_MS = 1_200L
        private const val SECURITY_RECOVERY_RETRY_WINDOW_MS = 15_000L
        private const val ADVERTISE_CONFLICT_RETRY_DELAY_MS = 1_200L
        private const val MAX_ADVERTISE_CONFLICT_RETRIES = 3
        private const val INTER_CHUNK_DELAY_MS = 6L
        private const val LEGACY_CLIENT_INTER_CHUNK_DELAY_MS = 18L
        private const val CLIENT_SEND_RECOVERY_COOLDOWN_MS = 2_500L
        private const val LEGACY_CLIENT_SEND_RECOVERY_COOLDOWN_MS = 5_000L
        private const val CONTROL_TRAFFIC_INITIAL_DELAY_MS = 350L
        private const val CONTROL_TRAFFIC_RETRY_DELAY_MS = 650L
        private const val LEGACY_CONTROL_TRAFFIC_INITIAL_DELAY_MS = 1_800L
        private const val LEGACY_CONTROL_TRAFFIC_RETRY_DELAY_MS = 1_200L
        private const val CONTROL_TRAFFIC_WINDOW_RETRY_COUNT = 5
        private const val DELIVERY_RECEIPT_COALESCE_DELAY_MS = 320L
        private const val PEER_VERIFICATION_RETRY_INTERVAL_MS = 15_000L
        private const val PEER_VERIFICATION_CACHE_TTL_MS = 10 * 60 * 1000L
        private const val MAX_CLIENT_CHUNK_BYTES = 244
        // CoreBluetooth centrals frequently expose ~182 bytes of notification payload budget
        // even when the ATT MTU callback reports a higher value on Android's server side.
        private const val MAX_SERVER_NOTIFY_CHUNK_BYTES = 180
        private const val MAX_PENDING_OUTBOUND_PACKETS = 200
        private const val MAX_PENDING_FLUSH_BATCH = 6
        // Do not raise casually: parallel pending connects often collapse into 147/133 on mixed vendor stacks.
        private const val MAX_PENDING_OUTBOUND_CONNECTS = 1
        // Conservative cap for centrally-initiated links; mesh grows via relaying instead of full-mesh links.
        private const val MAX_OUTBOUND_CLIENT_LINKS = 4

        private const val LEGACY_DEFAULT_SENDER_LABEL = "Yakindaki Kullanici"
        private const val IDENTITY_ANNOUNCEMENT_RECEIPT_ID = "gattmeshidentity00000000000000000000"
        private const val PACKET_PROTOCOL_VALUE_V1 = "dcs-gattmesh-v1"
        private const val PACKET_PROTOCOL_VALUE_V2 = "dcs-gattmesh-v2"
        private const val PACKET_PROTOCOL_VALUE_V3 = "dcs-gattmesh-v3"
        private const val PACKET_PROTOCOL_VALUE_V4 = "dcs-gattmesh-v4"
        private const val PACKET_PROTOCOL_VALUE_V5 = "dcs-gattmesh-v5"
        private const val RECEIPT_PLACEHOLDER_MESSAGE = "mesh-receipt"
        private const val AUTH_CHALLENGE_PLACEHOLDER_MESSAGE = "c"
        private const val AUTH_PROOF_PLACEHOLDER_MESSAGE = "p"
        private const val ROLE_PROOF_PUBLIC_KEY_FIELD = "devicePublicKey"
        private const val ROLE_PROOF_CERTIFICATE_FIELD = "certificate"
        private const val ROLE_PROOF_TIMESTAMP_FIELD = "timestamp"
        private const val ROLE_PROOF_SIGNATURE_FIELD = "signature"
        private const val ROLE_PROOF_NONCE_FIELD = "sessionNonce"
        private const val ROLE_PROOF_ALLOW_EXPIRED_FIELD = "allowExpiredCertificate"
        private const val COMPACT_ROLE_PROOF_PUBLIC_KEY_FIELD = "pk"
        private const val COMPACT_ROLE_PROOF_CERTIFICATE_FIELD = "c"
        private const val COMPACT_ROLE_PROOF_TIMESTAMP_FIELD = "ts"
        private const val COMPACT_ROLE_PROOF_SIGNATURE_FIELD = "s"
        private const val COMPACT_ROLE_PROOF_NONCE_FIELD = "n"
        private const val COMPACT_ROLE_PROOF_ALLOW_EXPIRED_FIELD = "g"
        private const val ENCRYPTED_CHAT_PLACEHOLDER_MESSAGE = "mesh-secure"
        private const val IMAGE_PLACEHOLDER_MESSAGE = "mesh-image"
        private const val PACKET_FIELD_BLOB_ID = "blobId"
        private const val PACKET_FIELD_BLOB_INDEX = "blobIdx"
        private const val PACKET_FIELD_BLOB_COUNT = "blobCnt"
        private const val PACKET_FIELD_BLOB_BYTES = "blobLen"
        private const val PACKET_FIELD_BLOB_DATA = "blobData"
        private const val PACKET_FIELD_BLOB_MIME = "blobMime"
        private const val PACKET_FIELD_BLOB_WIDTH = "blobW"
        private const val PACKET_FIELD_BLOB_HEIGHT = "blobH"
        // Image blobs ride the mesh as single-hop packets; size/pace limits keep one transfer
        // under the per-source inbound rate window (180 packets / 10s).
        private const val MESH_IMAGE_MAX_PLAIN_BYTES = 400_000
        private const val MESH_IMAGE_CHUNK_BYTES = 2_800
        private const val MESH_IMAGE_MAX_CHUNKS = 160
        private const val MESH_IMAGE_CHUNK_SEND_SPACING_MS = 75L
        private const val MESH_IMAGE_INBOUND_TIMEOUT_MS = 45_000L
        private const val MESH_IMAGE_MAX_CONCURRENT_INBOUND = 3
        private const val PACKET_FIELD_BLOB_KIND = "blobKind"
        private const val PACKET_FIELD_BLOB_DURATION = "blobDur"
        private const val BLOB_KIND_IMAGE = "image"
        private const val BLOB_KIND_VOICE = "voice"
        private const val MESH_VOICE_MIME = "audio/mp4"
        private const val MESH_VOICE_MAX_DURATION_MS = 90_000L
        private const val WIFI_AWARE_SOURCE_TAG = "wifi-aware"
        private val MESH_IMAGE_TRANSFER_PROFILE = BLE_IMAGE_TRANSFER_PROFILE.copy(
            targetBytes = 360_000,
            maxOutputBytes = MESH_IMAGE_MAX_PLAIN_BYTES
        )
        private const val MESH_GCM_IV_BYTES = 12
        private const val PERSISTED_QUEUE_VERSION = 1
        private const val PERSISTED_QUEUE_FILE_NAME = "gatt_mesh_pending_outbound_queue.json"
        private const val GATT_MESH_NOTIFICATIONS_ENABLED_PREF = "advanced_gatt_mesh_notifications_enabled"
        private const val INVALID_MAC_ADDRESS = "02:00:00:00:00:00"
        private const val DEFAULT_INITIATOR_SALT = 0x6F4B5D5E
        private val GATT_MESH_NOTIFICATIONS_ENABLED_KEY =
            booleanPreferencesKey(GATT_MESH_NOTIFICATIONS_ENABLED_PREF)

        private val MESSAGE_ID_REGEX = Regex("^[a-zA-Z0-9-]{8,128}$")
        private val UTF_8: Charset = StandardCharsets.UTF_8

        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        // Stack-level GATT status seen when remote attribute requires authorization/security.
        private const val GATT_STATUS_INSUFFICIENT_AUTHORIZATION = 8
        private const val GATT_STATUS_INSUFFICIENT_KEY_SIZE = 12

        val isRunning: StateFlow<Boolean> = MeshServiceRegistry.runningState(MeshProfiles.PUBLIC.id)

        internal fun meshServiceUuidForAdvertising(): UUID = MeshProfiles.PUBLIC.serviceUuid

        fun currentStateSnapshot(): GattMeshServiceState? =
            MeshServiceRegistry.instance(MeshProfiles.PUBLIC.id)?.state?.value

        fun deprioritizePeerForP2p(address: String, durationMs: Long = P2P_CHAT_PEER_SUPPRESSION_MS) {
            MeshServiceRegistry.instance(MeshProfiles.PUBLIC.id)
                ?.deprioritizePeerForP2pInternal(address, durationMs)
        }

        @SuppressLint("HardwareIds")
        internal fun meshManufacturerDataForAdvertising(
            context: Context,
            adapter: BluetoothAdapter?
        ): ByteArray {
            val macSalt = runCatching { adapter?.address }
                .getOrNull()
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() && it != INVALID_MAC_ADDRESS }
                ?.hashCode()
            val seed = macSalt ?: runCatching {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            }.getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.hashCode()
                ?: context.packageName.hashCode()
            val rank = seed xor (seed ushr 16) xor DEFAULT_INITIATOR_SALT
            return byteArrayOf(
                (rank ushr 24).toByte(),
                (rank ushr 16).toByte(),
                (rank ushr 8).toByte(),
                rank.toByte()
            )
        }

        fun start(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GattMeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, GattMeshForegroundService::class.java))
        }

        fun requestSosModeReconcile(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GattMeshForegroundService::class.java).apply {
                action = ACTION_RECONCILE_SOS_MODE
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun isRuntimeActive(): Boolean = MeshServiceRegistry.isRuntimeActive(MeshProfiles.PUBLIC.id)
    }
}
