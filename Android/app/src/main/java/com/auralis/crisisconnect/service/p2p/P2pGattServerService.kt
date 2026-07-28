package com.auralis.crisisconnect.service.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.generateImageThumbnail
import com.auralis.crisisconnect.data.Contact
import com.google.firebase.auth.FirebaseAuth
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_RFCOMM
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_IOS
import com.auralis.crisisconnect.data.REMOTE_PLATFORM_UNKNOWN
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContactByAddress
import com.auralis.crisisconnect.data.getContactByRemoteDeviceId
import com.auralis.crisisconnect.data.hasAnyBleGattContacts
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.markAllLocalMessagesRead
import com.auralis.crisisconnect.data.markLocalMessagesDeliveredWithRecipient
import com.auralis.crisisconnect.data.markLocalMessagesReadWithRecipient
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.normalizeRemotePlatform
import com.auralis.crisisconnect.data.normalizeVerifiedIdentityKey
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.saveBleContactAndMigrateLegacySession
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.updateContactAesKey
import com.auralis.crisisconnect.data.updateContactBleRuntimeMetadata
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.security.BleChunkReceiver
import com.auralis.crisisconnect.service.p2p.call.P2pCallController
import com.auralis.crisisconnect.service.p2p.call.P2pCallProtocol
import com.auralis.crisisconnect.service.BleFilePayload
import com.auralis.crisisconnect.service.BleImagePayload
import com.auralis.crisisconnect.service.BleMessageNotifier
import com.auralis.crisisconnect.service.CallAudioRoute
import com.auralis.crisisconnect.service.RfcommForegroundService
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.text.Charsets

class P2pGattServerService : Service() {

    private data class PendingHandshake(
        val clientSessionCode: String,
        val clientName: String?,
        val clientAvatarBase64: String?,
        val clientDeviceId: String,
        val clientNonce: String,
        val clientPlatform: String,
        val serverHelloProof: String,
        // The scanner's internet identity from the (separately authenticated) client-hello, so this
        // displaying side can fall back to the online transport when Bluetooth is off. Null when the
        // peer didn't supply it or its proof failed — those stay BLE-only.
        val clientPeerUid: String? = null,
        val clientPeerPublicKey: String? = null
    )

    private data class ParsedEnvelope(
        val fromDeviceId: String,
        val encryptedPacket: ByteArray
    )

    private data class ParsedChatPayload(
        val kind: String,
        val messageId: String?,
        val messageIds: List<String>,
        val text: String?,
        val senderName: String?,
        val displayName: String?,
        val mimeType: String?,
        val durationMillis: Long?,
        val width: Int?,
        val height: Int?,
        val originalSizeBytes: Long?,
        val totalBytes: Int?,
        val totalChunks: Int?,
        val sha256: ByteArray?,
        val chunkIndex: Int?,
        val chunkBytes: ByteArray?
    )

    private data class IncomingVoiceTransfer(
        val messageId: String,
        val mimeType: String,
        val durationMillis: Long,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var receivedDone: Boolean = false
    ) {
        fun isComplete(): Boolean = receivedDone && chunks.size == totalChunks

        fun composeBytes(): ByteArray? {
            if (!isComplete()) return null
            var totalSize = 0
            for (index in 0 until totalChunks) {
                val chunk = chunks[index] ?: return null
                totalSize += chunk.size
            }
            val combined = ByteArray(totalSize)
            var offset = 0
            for (index in 0 until totalChunks) {
                val chunk = chunks[index] ?: return null
                System.arraycopy(chunk, 0, combined, offset, chunk.size)
                offset += chunk.size
            }
            return combined
        }
    }

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var shareSession: PublishedSession? = null
    private var hasBleContacts = false
    private var messageOutCharacteristic: BluetoothGattCharacteristic? = null
    private var startCommandJob: Job? = null
    private var hostedShareSession: PublishedSession? = null
    private var isGattHostingActive = false
    private var sharedHostEnabled = false
    private var awaitingPrimaryServiceRegistration = false
    private val sharedGattDelegates = mutableMapOf<UUID, SharedGattDelegate>()
    private val sharedGattDelegatesLock = Any()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val deviceResponses = ConcurrentHashMap<String, ByteArray>()
    private val pendingHandshakes = ConcurrentHashMap<String, PendingHandshake>()
    private val messageReceivers = ConcurrentHashMap<String, BleChunkReceiver>()
    private val subscribedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val centralMtuByAddress = ConcurrentHashMap<String, Int>()
    private val incomingVoiceTransfers = ConcurrentHashMap<String, IncomingVoiceTransfer>()
    private val incomingImageTransfers = ConcurrentHashMap<String, BleImagePayload.IncomingTransfer>()
    private val incomingFileTransfers = ConcurrentHashMap<String, BleFilePayload.IncomingTransfer>()
    private val notifyMutex = Mutex()
    private val publicationMutex = Mutex()

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "P2P advertising start failed: $errorCode")
            isGattHostingActive = false
            hostedShareSession = null
            publishRunningState(running = false, session = shareSession)
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            super.onServiceAdded(status, service)
            if (service?.uuid != P2pBleProtocol.SERVICE_UUID || !awaitingPrimaryServiceRegistration) {
                return
            }
            awaitingPrimaryServiceRegistration = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "P2P primary GATT service registration failed: status=$status")
                stopAdvertising()
                closeGattServer()
                clearAllControlState()
                publishRunningState(running = false, session = shareSession)
                stopSelf()
                return
            }
            if (!registerPendingSharedGattServices()) {
                Log.e(TAG, "Failed to add shared GATT services after primary P2P service registration")
                stopAdvertising()
                closeGattServer()
                clearAllControlState()
                publishRunningState(running = false, session = shareSession)
                stopSelf()
                return
            }
            if (!startAdvertisingForCurrentProfiles()) {
                stopSelf()
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            if (
                device != null &&
                newState == BluetoothProfile.STATE_CONNECTED &&
                P2pGattChatManager.ownsAddress(device.address)
            ) {
                Log.d(
                    TAG,
                    "Rejecting reciprocal P2P server connection for ${device.address} while client role is active"
                )
                val hasConnectPermission =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                        ContextCompat.checkSelfPermission(
                            this@P2pGattServerService,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                if (hasConnectPermission) {
                    runCatching { gattServer?.cancelConnection(device) }
                        .onFailure { throwable ->
                            Log.w(
                                TAG,
                                "Failed to cancel reciprocal P2P server connection for ${device.address}",
                                throwable
                            )
                        }
                } else {
                    Log.w(
                        TAG,
                        "Skipping reciprocal P2P server disconnect for ${device.address} because BLUETOOTH_CONNECT is unavailable"
                    )
                }
                clearDeviceState(device)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                clearDeviceState(device)
            }
            if (device != null) {
                dispatchSharedConnectionStateChange(device, status, newState)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            if (device != null) {
                deviceKey(device)?.let { key ->
                    centralMtuByAddress[key] = mtu.coerceAtLeast(DEFAULT_ATT_MTU)
                }
                dispatchSharedMtuChange(device, mtu)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            if (device != null) {
                dispatchSharedNotificationSent(device, status)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val targetCharacteristic = characteristic ?: return
            val sharedRead = handleSharedCharacteristicRead(
                device = targetDevice,
                offset = offset,
                characteristic = targetCharacteristic
            )
            if (sharedRead != null) {
                sendResponseSafely(server, targetDevice, requestId, sharedRead.status, offset, sharedRead.value)
                return
            }
            val responseBytes = when (characteristic?.uuid) {
                P2pBleProtocol.ID_CHARACTERISTIC_UUID -> currentIdentityValue()
                P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID -> currentBootstrapPayload()
                P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID -> deviceKey(device)?.let(deviceResponses::get)
                else -> null
            } ?: ByteArray(0)

            if (offset > responseBytes.size) {
                sendResponseSafely(server, targetDevice, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }

            val chunk = if (offset == 0) responseBytes else responseBytes.copyOfRange(offset, responseBytes.size)
            sendResponseSafely(server, targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, chunk)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val targetCharacteristic = characteristic ?: return
            val sharedStatus = handleSharedCharacteristicWrite(
                device = targetDevice,
                characteristic = targetCharacteristic,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                offset = offset,
                value = value
            )
            if (sharedStatus != null) {
                if (responseNeeded) {
                    sendResponseSafely(server, targetDevice, requestId, sharedStatus, offset, ByteArray(0))
                }
                return
            }
            val status = when {
                preparedWrite || offset != 0 -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                characteristic?.uuid == P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID -> {
                    handleControlWrite(device, value ?: ByteArray(0))
                }
                characteristic?.uuid == P2pBleProtocol.MESSAGE_IN_CHARACTERISTIC_UUID -> {
                    handleMessageChunk(device, value ?: ByteArray(0))
                }
                else -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) {
                sendResponseSafely(server, targetDevice, requestId, status, offset, ByteArray(0))
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val targetDescriptor = descriptor ?: return
            val sharedRead = handleSharedDescriptorRead(
                device = targetDevice,
                offset = offset,
                descriptor = targetDescriptor
            )
            if (sharedRead != null) {
                sendResponseSafely(server, targetDevice, requestId, sharedRead.status, offset, sharedRead.value)
                return
            }
            if (descriptor?.uuid != P2pBleProtocol.CLIENT_CONFIG_DESCRIPTOR_UUID) {
                sendResponseSafely(server, targetDevice, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val enabled = deviceKey(device)?.let(subscribedCentrals::containsKey) == true
            val value = if (enabled) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            sendResponseSafely(server, targetDevice, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val server = gattServer ?: return
            val targetDevice = device ?: return
            val targetDescriptor = descriptor ?: return
            val sharedStatus = handleSharedDescriptorWrite(
                device = targetDevice,
                descriptor = targetDescriptor,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                offset = offset,
                value = value ?: ByteArray(0)
            )
            if (sharedStatus != null) {
                if (responseNeeded) {
                    sendResponseSafely(server, targetDevice, requestId, sharedStatus, offset, ByteArray(0))
                }
                return
            }
            val status = if (
                descriptor?.uuid == P2pBleProtocol.CLIENT_CONFIG_DESCRIPTOR_UUID &&
                !preparedWrite &&
                offset == 0
            ) {
                handleDescriptorWrite(device, value ?: ByteArray(0))
            } else {
                BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            }
            if (responseNeeded) {
                sendResponseSafely(server, targetDevice, requestId, status, offset, ByteArray(0))
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        try {
            startForegroundWithTypes(callActive = false)
        } catch (startException: Exception) {
            // Foreground promotion is rejected when started from the background on Android 12+
            // (ForegroundServiceStartNotAllowedException). Stop instead of crashing.
            Log.w(TAG, "P2P startForeground failed; stopping service", startException)
            stopSelf()
        }
        P2pCallController.shared(applicationContext).registerServerLink(callLink)
    }

    /**
     * The manifest declares microphone|phoneCall so voice calls can run under this service, but
     * those types must NOT be part of a background start on Android 14+ (mic-while-in-use rule).
     * Normal hosting therefore starts with connectedDevice only; the call controller upgrades the
     * type from a user-visible context when a call becomes active and downgrades afterwards.
     */
    private fun startForegroundWithTypes(callActive: Boolean) {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (callActive) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, types)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshForegroundTypeForCall(callActive: Boolean) {
        runCatching { startForegroundWithTypes(callActive) }
            .onFailure { throwable ->
                Log.w(TAG, "Unable to refresh P2P foreground type callActive=$callActive", throwable)
            }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_START_SHARE -> {
                shareSession = intent.toPublishedSession()
            }
            ACTION_STOP_SHARE -> {
                shareSession = null
            }
            ACTION_ENSURE_HOST, null -> {
                Unit
            }
            ACTION_ACQUIRE_SHARED_HOST -> {
                sharedHostEnabled = true
            }
            ACTION_RELEASE_SHARED_HOST -> {
                sharedHostEnabled = false
            }
            ACTION_CALL_ACCEPT,
            ACTION_CALL_REJECT,
            ACTION_CALL_HANGUP,
            ACTION_CALL_MUTE,
            ACTION_CALL_UNMUTE,
            ACTION_CALL_SPEAKER,
            // Telecom (system in-call UI) dispatches these generic call actions to whichever
            // service owns the call's transport; for GATT calls that is this service.
            RfcommForegroundService.ACTION_ACCEPT_CALL,
            RfcommForegroundService.ACTION_REJECT_CALL,
            RfcommForegroundService.ACTION_END_CALL,
            RfcommForegroundService.ACTION_MUTE,
            RfcommForegroundService.ACTION_UNMUTE,
            RfcommForegroundService.ACTION_SYNC_CALL_AUDIO_ROUTE -> {
                intent?.let { handleCallAction(action, it) }
                return START_STICKY
            }
            else -> Unit
        }
        startCommandJob?.cancel()
        startCommandJob = serviceScope.launch {
            publicationMutex.withLock {
                hasBleContacts = hasAnyBleGattContacts(applicationContext)
                val shouldHost = sharedHostEnabled || shareSession != null || hasBleContacts
                if (!shouldHost) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    publishRunningState(running = false, session = null)
                    return@withLock
                }
                if (!hasBluetoothConnectPermission() || !hasBluetoothAdvertisePermission()) {
                    Log.w(TAG, "Missing Bluetooth permission(s); unable to host P2P GATT service")
                    publishRunningState(running = false, session = shareSession)
                    stopSelf()
                    return@withLock
                }
                val hostingCurrentConfiguration = isHostingCurrentConfiguration()
                val shouldRestartPublication = when (action) {
                    ACTION_START_SHARE,
                    ACTION_STOP_SHARE -> true
                    ACTION_ENSURE_HOST,
                    ACTION_ACQUIRE_SHARED_HOST,
                    ACTION_RELEASE_SHARED_HOST,
                    null -> !hostingCurrentConfiguration
                    else -> !hostingCurrentConfiguration
                }
                if (!shouldRestartPublication) {
                    return@withLock
                }
                if (!restartGattPublication()) {
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        P2pCallController.shared(applicationContext).unregisterServerLink(callLink)
        callAudioSenderJob?.cancel()
        stopAdvertising()
        closeGattServer()
        clearAllControlState()
        publishRunningState(running = false, session = null)
        shareSession = null
        sharedHostEnabled = false
        hasBleContacts = false
        startCommandJob?.cancel()
        serviceScope.cancel()
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.clear()
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun restartGattPublication(): Boolean {
        stopAdvertising()
        closeGattServer()
        clearAllControlState()
        isGattHostingActive = false
        hostedShareSession = null
        awaitingPrimaryServiceRegistration = false

        val manager = bluetoothManager ?: run {
            Log.e(TAG, "BluetoothManager unavailable")
            return false
        }
        val server = manager.openGattServer(this, gattCallback) ?: run {
            Log.e(TAG, "Failed to open P2P GATT server")
            return false
        }

        val service = BluetoothGattService(
            P2pBleProtocol.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val idCharacteristic = BluetoothGattCharacteristic(
            P2pBleProtocol.ID_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val bootstrapCharacteristic = BluetoothGattCharacteristic(
            P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        val controlCharacteristic = BluetoothGattCharacteristic(
            P2pBleProtocol.CONTROL_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val messageInCharacteristic = BluetoothGattCharacteristic(
            P2pBleProtocol.MESSAGE_IN_CHARACTERISTIC_UUID,
            // WRITE_NO_RESPONSE is required for the voice-call audio fast path: iOS centrals
            // (CoreBluetooth enforces property/type matching, unlike Android's lenient stack)
            // refuse .withoutResponse writes unless the characteristic advertises it — which
            // silently dropped every audio frame of an iOS→Android GATT call.
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val messageOut = BluetoothGattCharacteristic(
            P2pBleProtocol.MESSAGE_OUT_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        messageOut.addDescriptor(
            BluetoothGattDescriptor(
                P2pBleProtocol.CLIENT_CONFIG_DESCRIPTOR_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(idCharacteristic)
        service.addCharacteristic(bootstrapCharacteristic)
        service.addCharacteristic(controlCharacteristic)
        service.addCharacteristic(messageInCharacteristic)
        service.addCharacteristic(messageOut)

        gattServer = server
        messageOutCharacteristic = messageOut
        awaitingPrimaryServiceRegistration = true
        if (!server.addService(service)) {
            Log.e(TAG, "Failed to add P2P GATT service")
            awaitingPrimaryServiceRegistration = false
            server.close()
            gattServer = null
            messageOutCharacteristic = null
            return false
        }
        Log.d(TAG, "Waiting for primary P2P GATT service registration before advertising")
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertisingForCurrentProfiles(): Boolean {
        val localAdvertiser = advertiser ?: run {
            Log.e(TAG, "Bluetooth LE advertiser unavailable")
            return false
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        // The P2P service UUID must stay in the primary advertisement packet: iOS
        // centrals scan with a CoreBluetooth service filter that only matches the
        // primary packet, so a CD00 placed in the scan response is never discovered.
        // CD00 uses the SIG base UUID and encodes as a 2-byte 16-bit entry, which
        // fits next to the 128-bit mesh UUID; the 4-byte mesh initiator rank moves
        // to the scan response (Android merges both packets into one ScanRecord).
        val advertiseDataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(P2pBleProtocol.SERVICE_UUID))
        val scanResponseBuilder = AdvertiseData.Builder()
        if (shouldAdvertiseMeshProfile()) {
            advertiseDataBuilder.addServiceUuid(
                ParcelUuid(GattMeshForegroundService.meshServiceUuidForAdvertising())
            )
            scanResponseBuilder.addManufacturerData(
                MESH_INITIATOR_RANK_MANUFACTURER_ID,
                GattMeshForegroundService.meshManufacturerDataForAdvertising(
                    context = applicationContext,
                    adapter = bluetoothAdapter
                )
            )
        }
        if (shareSession != null) {
            val session = shareSession ?: return false
            scanResponseBuilder.addServiceData(
                ParcelUuid(P2pBleProtocol.SERVICE_UUID),
                P2pBleProtocol.encodeAdvertisedShareId(session.shareId)
            )
        }
        return runCatching {
            localAdvertiser.startAdvertising(
                settings,
                advertiseDataBuilder.build(),
                scanResponseBuilder.build(),
                advertiseCallback
            )
            hostedShareSession = shareSession
            isGattHostingActive = true
            publishRunningState(running = shareSession != null, session = shareSession)
            true
        }.getOrElse { throwable ->
            Log.e(TAG, "Failed to start P2P BLE advertising", throwable)
            hostedShareSession = null
            isGattHostingActive = false
            publishRunningState(running = false, session = shareSession)
            false
        }
    }

    private fun shouldAdvertiseMeshProfile(): Boolean {
        return synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.containsKey(GattMeshForegroundService.meshServiceUuidForAdvertising())
        }
    }

    private fun currentIdentityValue(): ByteArray {
        val activeShare = shareSession
        return if (activeShare != null) {
            P2pBleProtocol.buildIdentityValue(activeShare.shareId)
        } else {
            P2pBleProtocol.buildDeviceIdentityValue(LocalKeyStorage.getOrCreateP2pDeviceId(this))
        }
    }

    private fun currentBootstrapPayload(): ByteArray {
        val activeShare = shareSession
        return if (activeShare != null) {
            activeShare.bootstrapPayload.toByteArray(Charsets.UTF_8)
        } else {
            JSONObject().apply {
                put("mode", "host")
                put("platform", "android")
                put("protocolVersion", P2pBleProtocol.PROTOCOL_VERSION)
                put("serverDeviceId", LocalKeyStorage.getOrCreateP2pDeviceId(this@P2pGattServerService))
                localDisplayName()?.let { put("name", it) }
                localAvatarPayload()?.let { put("avatarB64", it) }
            }.toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun handleControlWrite(device: BluetoothDevice?, value: ByteArray): Int {
        val activeShare = shareSession
        if (activeShare == null) {
            deviceKey(device)?.let { key ->
                setErrorResponse(key, "share_inactive", "QR share session is not active")
            }
            return BluetoothGatt.GATT_SUCCESS
        }
        val key = deviceKey(device) ?: return BluetoothGatt.GATT_FAILURE
        val payload = value.toString(Charsets.UTF_8).trim()
        val frame = runCatching { JSONObject(payload) }.getOrNull() ?: run {
            setErrorResponse(key, "invalid_json", "Malformed control payload")
            return BluetoothGatt.GATT_SUCCESS
        }
        return when (frame.optString("type").trim()) {
            P2pBleProtocol.TYPE_CLIENT_HELLO -> {
                handleClientHello(key, activeShare, frame)
                BluetoothGatt.GATT_SUCCESS
            }
            P2pBleProtocol.TYPE_CLIENT_FINISH -> {
                handleClientFinish(device, key, activeShare, frame)
                BluetoothGatt.GATT_SUCCESS
            }
            else -> {
                setErrorResponse(key, "unsupported_type", "Unsupported control frame")
                BluetoothGatt.GATT_SUCCESS
            }
        }
    }

    private fun buildProofPayloadForClientPlatform(
        clientPlatform: String,
        vararg parts: Pair<String, String>
    ): ByteArray {
        return if (clientPlatform.equals("ios", ignoreCase = true)) {
            P2pBleProtocol.buildCanonicalProofPayload(*parts)
        } else {
            P2pBleProtocol.buildProofPayload(*parts)
        }
    }

    private fun proofMatchesClientPlatform(
        keyBytes: ByteArray,
        actualProof: String,
        clientPlatform: String,
        vararg parts: Pair<String, String>
    ): Boolean {
        val preferred = P2pBleProtocol.hmacBase64(
            keyBytes,
            buildProofPayloadForClientPlatform(clientPlatform, *parts)
        )
        if (P2pBleProtocol.secureEqualsBase64(preferred, actualProof)) {
            return true
        }
        val fallback = if (clientPlatform.equals("ios", ignoreCase = true)) {
            P2pBleProtocol.hmacBase64(keyBytes, P2pBleProtocol.buildProofPayload(*parts))
        } else {
            P2pBleProtocol.hmacBase64(keyBytes, P2pBleProtocol.buildCanonicalProofPayload(*parts))
        }
        return P2pBleProtocol.secureEqualsBase64(fallback, actualProof)
    }

    private fun handleClientHello(
        deviceKey: String,
        session: PublishedSession,
        frame: JSONObject
    ) {
        val keyBytes = P2pBleProtocol.decodeBase64(session.aesKeyBase64)
        if (keyBytes == null || keyBytes.isEmpty()) {
            setErrorResponse(deviceKey, "invalid_key", "Server key unavailable")
            return
        }
        val shareId = frame.optString("shareId").trim().uppercase(Locale.US)
        val clientSessionCode = frame.optString("clientSessionCode").trim()
        val clientName = frame.optString("clientName").trim().takeIf { it.isNotBlank() }
        val clientAvatarBase64 = frame.optString("avatarB64").trim().takeIf { it.isNotBlank() }
        val clientDeviceId = frame.optString("clientDeviceId").trim()
        val clientNonce = frame.optString("clientNonce").trim()
        val clientPlatform = frame.optString("clientPlatform").trim().lowercase(Locale.US)
            .ifBlank { REMOTE_PLATFORM_UNKNOWN.lowercase(Locale.US) }
        val proof = frame.optString("proof").trim()
        if (
            shareId != session.shareId ||
            clientSessionCode.isBlank() ||
            clientDeviceId.isBlank() ||
            clientNonce.isBlank() ||
            proof.isBlank()
        ) {
            setErrorResponse(deviceKey, "invalid_hello", "Missing client hello fields")
            return
        }
        if (!proofMatchesClientPlatform(
                keyBytes = keyBytes,
                actualProof = proof,
                clientPlatform = clientPlatform,
                "type" to P2pBleProtocol.TYPE_CLIENT_HELLO,
                "shareId" to session.shareId,
                "serverSessionCode" to session.sessionCode,
                "serverDeviceId" to session.deviceId,
                "serverNonce" to session.serverNonce,
                "clientSessionCode" to clientSessionCode,
                "clientDeviceId" to clientDeviceId,
                "clientNonce" to clientNonce,
                "clientName" to (clientName ?: ""),
                "clientPlatform" to clientPlatform
            )
        ) {
            setErrorResponse(deviceKey, "auth_failed", "Client proof mismatch")
            return
        }
        val serverHelloProof = P2pBleProtocol.hmacBase64(
            keyBytes,
            buildProofPayloadForClientPlatform(
                clientPlatform,
                "type" to P2pBleProtocol.TYPE_SERVER_HELLO,
                "shareId" to session.shareId,
                "serverSessionCode" to session.sessionCode,
                "serverDeviceId" to session.deviceId,
                "serverNonce" to session.serverNonce,
                "clientSessionCode" to clientSessionCode,
                "clientDeviceId" to clientDeviceId,
                "clientNonce" to clientNonce,
                "clientName" to (clientName ?: ""),
                "clientPlatform" to clientPlatform,
                "serverName" to (session.displayName ?: ""),
                "serverPlatform" to "android"
            )
        )
        if (serverHelloProof.isNullOrBlank()) {
            setErrorResponse(deviceKey, "server_error", "Failed to create server proof")
            return
        }
        // Optional: the scanner's internet identity, accepted ONLY when its separate HMAC verifies
        // against the shared key + this session's nonces. A missing/bad proof just drops the
        // identity (pairing still succeeds over BLE), keeping older peers compatible and a BLE MITM
        // unable to inject a forged identity. Canonical proof form → matches iOS byte-for-byte.
        var clientPeerUid: String? = null
        var clientPeerPublicKey: String? = null
        val offeredPeerUid = frame.optString("clientPeerUid").trim()
        val offeredPeerKey = frame.optString("clientPeerPublicKey").trim()
        val offeredIdentityProof = frame.optString("clientIdentityProof").trim()
        if (offeredPeerUid.isNotBlank() && offeredPeerKey.isNotBlank() && offeredIdentityProof.isNotBlank()) {
            val expectedIdentityProof = P2pBleProtocol.hmacBase64(
                keyBytes,
                P2pBleProtocol.buildClientIdentityProofPayload(
                    shareId = session.shareId,
                    serverNonce = session.serverNonce,
                    clientNonce = clientNonce,
                    peerUid = offeredPeerUid,
                    peerPublicKey = offeredPeerKey
                )
            )
            if (P2pBleProtocol.secureEqualsBase64(expectedIdentityProof, offeredIdentityProof)) {
                clientPeerUid = offeredPeerUid
                clientPeerPublicKey = offeredPeerKey
            } else {
                Log.w(TAG, "Client identity proof mismatch; ignoring internet identity")
            }
        }
        pendingHandshakes[deviceKey] = PendingHandshake(
            clientSessionCode = clientSessionCode,
            clientName = clientName,
            clientAvatarBase64 = clientAvatarBase64,
            clientDeviceId = clientDeviceId,
            clientNonce = clientNonce,
            clientPlatform = clientPlatform,
            serverHelloProof = serverHelloProof,
            clientPeerUid = clientPeerUid,
            clientPeerPublicKey = clientPeerPublicKey
        )
        val localAvatarBase64 = localAvatarPayload()
        setDeviceResponse(
            deviceKey,
            JSONObject().apply {
                put("type", P2pBleProtocol.TYPE_SERVER_HELLO)
                put("protocolVersion", P2pBleProtocol.PROTOCOL_VERSION)
                put("shareId", session.shareId)
                put("sessionCode", session.sessionCode)
                put("serverDeviceId", session.deviceId)
                put("serverNonce", session.serverNonce)
                put("platform", "android")
                if (!session.displayName.isNullOrBlank()) {
                    put("serverName", session.displayName)
                }
                if (!localAvatarBase64.isNullOrBlank()) {
                    put("avatarB64", localAvatarBase64)
                }
                put("proof", serverHelloProof)
            }.toString()
        )
    }

    private fun handleClientFinish(
        device: BluetoothDevice?,
        deviceKey: String,
        session: PublishedSession,
        frame: JSONObject
    ) {
        val keyBytes = P2pBleProtocol.decodeBase64(session.aesKeyBase64)
        val pending = pendingHandshakes[deviceKey]
        if (keyBytes == null || keyBytes.isEmpty() || pending == null) {
            setErrorResponse(deviceKey, "missing_state", "No pending handshake for device")
            return
        }
        val proof = frame.optString("proof").trim()
        if (proof.isBlank()) {
            setErrorResponse(deviceKey, "invalid_finish", "Missing finish proof")
            return
        }
        if (!proofMatchesClientPlatform(
                keyBytes = keyBytes,
                actualProof = proof,
                clientPlatform = pending.clientPlatform,
                "type" to P2pBleProtocol.TYPE_CLIENT_FINISH,
                "shareId" to session.shareId,
                "serverSessionCode" to session.sessionCode,
                "serverDeviceId" to session.deviceId,
                "serverNonce" to session.serverNonce,
                "clientSessionCode" to pending.clientSessionCode,
                "clientDeviceId" to pending.clientDeviceId,
                "clientNonce" to pending.clientNonce,
                "clientPlatform" to pending.clientPlatform,
                "serverHelloProof" to pending.serverHelloProof
            )
        ) {
            setErrorResponse(deviceKey, "auth_failed", "Client finish proof mismatch")
            return
        }
        pendingHandshakes.remove(deviceKey)
        setDeviceResponse(
            deviceKey,
            JSONObject().apply {
                put("type", P2pBleProtocol.TYPE_SERVER_FINISH)
                put("status", "ok")
                put("shareId", session.shareId)
                put("sessionCode", session.sessionCode)
                put("serverDeviceId", session.deviceId)
            }.toString()
        )
        if (device != null) {
            serviceScope.launch {
                persistAuthenticatedPeer(session, device, pending)
            }
        }
    }

    private fun handleDescriptorWrite(device: BluetoothDevice?, value: ByteArray): Int {
        val key = deviceKey(device) ?: return BluetoothGatt.GATT_FAILURE
        return when {
            value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) -> {
                if (device != null) {
                    subscribedCentrals[key] = device
                }
                BluetoothGatt.GATT_SUCCESS
            }
            value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                subscribedCentrals.remove(key)
                BluetoothGatt.GATT_SUCCESS
            }
            else -> BluetoothGatt.GATT_FAILURE
        }
    }

    private fun handleMessageChunk(device: BluetoothDevice?, value: ByteArray): Int {
        val key = deviceKey(device) ?: return BluetoothGatt.GATT_FAILURE
        val receiver = messageReceivers.getOrPut(key) {
            BleChunkReceiver(maxPacketSize = MAX_TRANSPORT_PACKET_BYTES, tag = TAG)
        }
        return runCatching {
            val packet = when (val chunkResult = receiver.onChunk(value)) {
                BleChunkReceiver.ChunkResult.Incomplete -> return BluetoothGatt.GATT_SUCCESS
                is BleChunkReceiver.ChunkResult.Complete -> chunkResult.packet
                is BleChunkReceiver.ChunkResult.Rejected -> {
                    Log.w(TAG, "Rejecting inbound P2P chat chunk reason=${chunkResult.reason}")
                    messageReceivers.remove(key)
                    return BluetoothGatt.GATT_FAILURE
                }
            }
            messageReceivers.remove(key)
            val envelopeBytes = P2pBleProtocol.unwrapTransportPacket(packet, MAX_TRANSPORT_PACKET_BYTES)
            if (P2pCallProtocol.isCallAudioFrame(envelopeBytes)) {
                // Binary voice-call audio fast path; must never reach the chat envelope parser.
                P2pCallController.shared(applicationContext).onInboundCallAudio(envelopeBytes)
                return BluetoothGatt.GATT_SUCCESS
            }
            val envelope = parseEnvelope(envelopeBytes) ?: return BluetoothGatt.GATT_FAILURE
            serviceScope.launch {
                handleIncomingEnvelope(device, envelope)
            }
            BluetoothGatt.GATT_SUCCESS
        }.getOrElse { throwable ->
            Log.w(TAG, "Failed to handle inbound P2P chat chunk", throwable)
            messageReceivers.remove(key)
            BluetoothGatt.GATT_FAILURE
        }
    }

    private suspend fun handleIncomingEnvelope(device: BluetoothDevice?, envelope: ParsedEnvelope) {
        val contact = resolveContactForEnvelope(device, envelope) ?: return
        val keyBytes = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return
        val payloadBytes = decryptChatPayload(
            keyBytes = keyBytes,
            encryptedPacket = envelope.encryptedPacket
        ) ?: run {
            Log.w(TAG, "Failed to decrypt inbound P2P chat payload — sending DECRYPT_FAIL back")
            sendChatEvent(
                kind = P2pBleProtocol.CHAT_KIND_DECRYPT_FAIL,
                messageId = null,
                device = device,
                contact = contact
            )
            return
        }
        val payload = parsePayload(payloadBytes) ?: return
        val address = device?.address?.trim().orEmpty()
        val displayName = payload.senderName?.trim().takeIf { !it.isNullOrBlank() } ?: contact.name
        if (address.isNotBlank()) {
            updateContactBleRuntimeMetadata(
                context = applicationContext,
                sessionCode = contact.sessionCode,
                lastKnownBleAddress = address,
                name = displayName
            )
        }
        if (P2pCallProtocol.isCallSignalKind(payload.kind)) {
            val rawPayload = runCatching {
                JSONObject(payloadBytes.toString(StandardCharsets.UTF_8))
            }.getOrNull() ?: return
            rememberCallRoute(contact.sessionCode, device)
            P2pCallController.shared(applicationContext)
                .onInboundCallSignal(contact, rawPayload, callLink)
            return
        }
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_TEXT -> {
                val messageText = payload.text?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: UUID.randomUUID().toString()
                saveRemoteMessage(
                    context = applicationContext,
                    sessionCode = contact.sessionCode,
                    uuid = messageId,
                    text = messageText,
                    senderDisplayName = displayName,
                    senderAddress = address
                )
                BleMessageNotifier.notifyIncoming(
                    context = applicationContext,
                    sessionCode = contact.sessionCode,
                    contactName = displayName,
                    body = messageText
                )
                sendChatEvent(
                    kind = P2pBleProtocol.CHAT_KIND_DELIVERED,
                    messageId = messageId,
                    device = device,
                    contact = contact
                )
            }
            P2pBleProtocol.CHAT_KIND_READ -> {
                val readMessageIds = LinkedHashSet<String>().apply {
                    payload.messageId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                    payload.messageIds.forEach(::add)
                }
                if (readMessageIds.isEmpty()) {
                    markAllLocalMessagesRead(applicationContext, contact.sessionCode)
                } else {
                    markLocalMessagesReadWithRecipient(
                        context = applicationContext,
                        sessionCode = contact.sessionCode,
                        messageUuids = readMessageIds,
                        recipientLabel = displayName
                    )
                }
            }
            P2pBleProtocol.CHAT_KIND_DELIVERED -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
                markLocalMessagesDeliveredWithRecipient(
                    context = applicationContext,
                    sessionCode = contact.sessionCode,
                    messageUuids = listOf(messageId),
                    recipientLabel = displayName
                )
            }
            P2pBleProtocol.CHAT_KIND_DECRYPT_FAIL -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                Log.w(TAG, "Remote peer failed to decrypt message ${messageId ?: "(unknown)"} — clearing AES key for plaintext fallback")
                updateContactAesKey(applicationContext, contact.sessionCode, "")
                if (messageId != null) {
                    updateLocalMessageDeliveryState(
                        context = applicationContext,
                        uuid = messageId,
                        deliveryStatus = MessageDeliveryStatus.QUEUED,
                        retryCount = 0,
                        nextRetryAtMillis = null,
                        lastAttemptAtMillis = null,
                        lastError = null,
                        outboundRoute = null
                    )
                }
            }
            P2pBleProtocol.CHAT_KIND_VOICE_INIT,
            P2pBleProtocol.CHAT_KIND_VOICE_CHUNK,
            P2pBleProtocol.CHAT_KIND_VOICE_DONE,
            P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                handleIncomingVoicePayload(device, contact, payload, displayName)
            }
            P2pBleProtocol.CHAT_KIND_IMAGE_INIT,
            P2pBleProtocol.CHAT_KIND_IMAGE_CHUNK,
            P2pBleProtocol.CHAT_KIND_IMAGE_DONE,
            P2pBleProtocol.CHAT_KIND_IMAGE_ABORT -> {
                handleIncomingImagePayload(device, contact, payload, displayName)
            }
            P2pBleProtocol.CHAT_KIND_FILE_INIT,
            P2pBleProtocol.CHAT_KIND_FILE_CHUNK,
            P2pBleProtocol.CHAT_KIND_FILE_DONE,
            P2pBleProtocol.CHAT_KIND_FILE_ABORT -> {
                handleIncomingFilePayload(contact, payload, displayName)
            }
        }
    }

    private suspend fun handleIncomingVoicePayload(
        device: BluetoothDevice?,
        contact: Contact,
        payload: ParsedChatPayload,
        displayName: String
    ) {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_VOICE_INIT -> {
                val mimeType = payload.mimeType?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val totalChunks = payload.totalChunks?.takeIf { it in 1..P2pBleProtocol.VOICE_MAX_CHUNKS } ?: return
                incomingVoiceTransfers[messageId] = IncomingVoiceTransfer(
                    messageId = messageId,
                    mimeType = mimeType,
                    durationMillis = payload.durationMillis?.coerceAtLeast(0L) ?: 0L,
                    totalChunks = totalChunks
                )
            }

            P2pBleProtocol.CHAT_KIND_VOICE_CHUNK -> {
                val transfer = incomingVoiceTransfers[messageId] ?: return
                val chunkIndex = payload.chunkIndex ?: return
                val chunkBytes = payload.chunkBytes ?: return
                if (chunkIndex !in 0 until transfer.totalChunks) {
                    return
                }
                transfer.chunks[chunkIndex] = chunkBytes
                completeIncomingVoiceTransferIfReady(device, contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_VOICE_DONE -> {
                val transfer = incomingVoiceTransfers[messageId] ?: return
                transfer.receivedDone = true
                completeIncomingVoiceTransferIfReady(device, contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                incomingVoiceTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingVoiceTransferIfReady(
        device: BluetoothDevice?,
        contact: Contact,
        transfer: IncomingVoiceTransfer,
        displayName: String
    ) {
        if (!transfer.isComplete()) {
            return
        }
        val fileBytes = transfer.composeBytes() ?: return
        if (fileBytes.size > P2pBleProtocol.VOICE_MAX_TOTAL_BYTES) {
            incomingVoiceTransfers.remove(transfer.messageId)
            return
        }
        val fileName = voiceMessageFileName(transfer.messageId, transfer.mimeType)
        withContext(Dispatchers.IO) {
            val destination = voiceMessageFile(applicationContext, fileName)
            destination.writeBytes(fileBytes)
            saveRemoteAudioMessage(
                context = applicationContext,
                sessionCode = contact.sessionCode,
                uuid = transfer.messageId,
                fileName = fileName,
                audioDurationMillis = transfer.durationMillis.takeIf { it > 0L }
            )
        }
        incomingVoiceTransfers.remove(transfer.messageId)
        BleMessageNotifier.notifyIncoming(
            context = applicationContext,
            sessionCode = contact.sessionCode,
            contactName = displayName,
            body = applicationContext.getString(R.string.notification_voice_message_body)
        )
        sendChatEvent(
            kind = P2pBleProtocol.CHAT_KIND_DELIVERED,
            messageId = transfer.messageId,
            device = device,
            contact = contact
        )
        if (device?.address?.isNullOrBlank() == false) {
            updateContactBleRuntimeMetadata(
                context = applicationContext,
                sessionCode = contact.sessionCode,
                lastKnownBleAddress = device.address,
                name = displayName
            )
        }
    }

    private suspend fun handleIncomingImagePayload(
        device: BluetoothDevice?,
        contact: Contact,
        payload: ParsedChatPayload,
        displayName: String
    ) {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_IMAGE_INIT -> {
                val mimeType = payload.mimeType?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val width = payload.width?.takeIf { it > 0 } ?: return
                val height = payload.height?.takeIf { it > 0 } ?: return
                val totalBytes = payload.totalBytes?.takeIf { it in 1..P2pBleProtocol.IMAGE_MAX_TOTAL_BYTES } ?: return
                val totalChunks = payload.totalChunks?.takeIf { it in 1..P2pBleProtocol.IMAGE_MAX_CHUNKS } ?: return
                val sha256 = payload.sha256?.takeIf { it.size == 32 } ?: return
                incomingImageTransfers[messageId] = BleImagePayload.IncomingTransfer(
                    transferId = messageId,
                    messageId = messageId,
                    mimeType = mimeType,
                    width = width,
                    height = height,
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = sha256
                )
            }

            P2pBleProtocol.CHAT_KIND_IMAGE_CHUNK -> {
                val transfer = incomingImageTransfers[messageId] ?: return
                val chunkIndex = payload.chunkIndex ?: return
                val chunkBytes = payload.chunkBytes ?: return
                if (!transfer.addChunk(chunkIndex, chunkBytes)) {
                    return
                }
                completeIncomingImageTransferIfReady(device, contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_IMAGE_DONE -> {
                val transfer = incomingImageTransfers[messageId] ?: return
                completeIncomingImageTransferIfReady(device, contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_IMAGE_ABORT -> {
                incomingImageTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingImageTransferIfReady(
        device: BluetoothDevice?,
        contact: Contact,
        transfer: BleImagePayload.IncomingTransfer,
        displayName: String
    ) {
        val imageBytes = transfer.composeBytes() ?: return
        val messageId = transfer.messageId.ifBlank { UUID.randomUUID().toString() }
        val fileName = ImageFileUtils.fileNameFor(messageId, transfer.mimeType)
        val thumbnailName = ImageFileUtils.thumbnailNameFor(messageId, transfer.mimeType)
        withContext(Dispatchers.IO) {
            val destination = imageMessageFile(applicationContext, fileName)
            val thumbnail = imageThumbnailFile(applicationContext, thumbnailName)
            destination.parentFile?.mkdirs()
            destination.writeBytes(imageBytes)
            val thumbnailCreated = generateImageThumbnail(
                source = destination,
                target = thumbnail,
                mimeType = transfer.mimeType
            )
            saveRemoteImageMessage(
                context = applicationContext,
                sessionCode = contact.sessionCode,
                uuid = messageId,
                fileName = destination.name,
                thumbnailName = if (thumbnailCreated) thumbnail.name else null,
                width = transfer.width,
                height = transfer.height,
                mimeType = transfer.mimeType
            )
        }
        incomingImageTransfers.remove(transfer.transferId)
        BleMessageNotifier.notifyIncoming(
            context = applicationContext,
            sessionCode = contact.sessionCode,
            contactName = displayName,
            body = applicationContext.getString(R.string.notification_photo_message_body)
        )
        sendChatEvent(
            kind = P2pBleProtocol.CHAT_KIND_DELIVERED,
            messageId = messageId,
            device = device,
            contact = contact
        )
    }

    private suspend fun handleIncomingFilePayload(
        contact: Contact,
        payload: ParsedChatPayload,
        displayName: String
    ) {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_FILE_INIT -> {
                val fileDisplayName = payload.displayName?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val totalBytes = payload.totalBytes?.takeIf { it in 1..P2pBleProtocol.FILE_MAX_TOTAL_BYTES } ?: return
                val totalChunks = payload.totalChunks?.takeIf { it in 1..P2pBleProtocol.FILE_MAX_CHUNKS } ?: return
                val originalSizeBytes = payload.originalSizeBytes?.takeIf { it > 0L } ?: totalBytes.toLong()
                val sha256 = payload.sha256?.takeIf { it.size == 32 } ?: return
                incomingFileTransfers[messageId] = BleFilePayload.IncomingTransfer(
                    transferId = messageId,
                    messageId = messageId,
                    displayName = fileDisplayName,
                    mimeType = payload.mimeType?.trim()?.takeIf { it.isNotEmpty() },
                    originalSizeBytes = originalSizeBytes,
                    totalBytes = totalBytes,
                    totalChunks = totalChunks,
                    sha256 = sha256
                )
            }

            P2pBleProtocol.CHAT_KIND_FILE_CHUNK -> {
                val transfer = incomingFileTransfers[messageId] ?: return
                val chunkIndex = payload.chunkIndex ?: return
                val chunkBytes = payload.chunkBytes ?: return
                if (!transfer.addChunk(chunkIndex, chunkBytes)) {
                    return
                }
                completeIncomingFileTransferIfReady(contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_FILE_DONE -> {
                val transfer = incomingFileTransfers[messageId] ?: return
                completeIncomingFileTransferIfReady(contact, transfer, displayName)
            }

            P2pBleProtocol.CHAT_KIND_FILE_ABORT -> {
                incomingFileTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingFileTransferIfReady(
        contact: Contact,
        transfer: BleFilePayload.IncomingTransfer,
        displayName: String
    ) {
        val fileBytes = transfer.composeBytes() ?: return
        persistSharedDocumentLocalCopy(
            context = applicationContext,
            uuid = transfer.messageId,
            displayName = transfer.displayName,
            bytes = fileBytes
        ) ?: return
        incomingFileTransfers.remove(transfer.transferId)
        if (contact.lastKnownBleAddress.isNotBlank()) {
            updateContactBleRuntimeMetadata(
                context = applicationContext,
                sessionCode = contact.sessionCode,
                lastKnownBleAddress = contact.lastKnownBleAddress,
                name = displayName
            )
        }
    }

    private fun resolveContactForEnvelope(device: BluetoothDevice?, envelope: ParsedEnvelope): Contact? {
        val byDeviceId = getContactByRemoteDeviceId(applicationContext, envelope.fromDeviceId)
        if (byDeviceId != null) {
            return byDeviceId
        }
        val byAddress = device?.address?.let { getContactByAddress(applicationContext, it) }
        if (byAddress != null && byAddress.remoteDeviceId.isBlank()) {
            return byAddress
        }
        return null
    }

    private fun parseEnvelope(value: ByteArray): ParsedEnvelope? {
        val payload = runCatching { JSONObject(value.toString(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val type = payload.optString("type").trim()
        if (type != P2pBleProtocol.TYPE_CHAT_ENVELOPE) {
            return null
        }
        val fromDeviceId = payload.optString("fromDeviceId").trim().takeIf { it.isNotBlank() } ?: return null
        val encryptedPacket = P2pBleProtocol.decodeBase64(payload.optString("payload")) ?: return null
        return ParsedEnvelope(fromDeviceId = fromDeviceId, encryptedPacket = encryptedPacket)
    }

    private fun parsePayload(value: ByteArray): ParsedChatPayload? {
        val payload = runCatching { JSONObject(value.toString(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val kind = payload.optString("kind").trim().takeIf { it.isNotBlank() } ?: return null
        return ParsedChatPayload(
            kind = kind,
            messageId = payload.optString("messageId").trim().takeIf { it.isNotBlank() },
            messageIds = payload.optJSONArray("messageIds")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optString(index)
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?.let(::add)
                    }
                }
            } ?: emptyList(),
            text = payload.optString("text").trim().takeIf { it.isNotBlank() },
            senderName = payload.optString("senderName").trim().takeIf { it.isNotBlank() },
            displayName = payload.optString("displayName").trim().takeIf { it.isNotBlank() },
            mimeType = payload.optString("mimeType").trim().takeIf { it.isNotBlank() },
            durationMillis = payload.optLong("durationMillis").takeIf { payload.has("durationMillis") },
            width = payload.optInt("width").takeIf { payload.has("width") },
            height = payload.optInt("height").takeIf { payload.has("height") },
            originalSizeBytes = payload.optLong("originalSizeBytes").takeIf { payload.has("originalSizeBytes") },
            totalBytes = payload.optInt("totalBytes").takeIf { payload.has("totalBytes") },
            totalChunks = payload.optInt("totalChunks").takeIf { payload.has("totalChunks") },
            sha256 = payload.optString("sha256").trim().takeIf { it.isNotBlank() }?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
            },
            chunkIndex = payload.optInt("chunkIndex").takeIf { payload.has("chunkIndex") },
            chunkBytes = payload.optString("chunkData").trim().takeIf { it.isNotBlank() }?.let {
                runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
            }
        )
    }

    private fun decryptChatPayload(keyBytes: ByteArray, encryptedPacket: ByteArray): ByteArray? {
        runCatching {
            return AesCipherHelper.decrypt(keyBytes, encryptedPacket)
        }
        val wrappedPacket = runCatching {
            AesCipherHelper.unwrapTransportPacket(encryptedPacket, MAX_ENCRYPTED_CHAT_PACKET_BYTES)
        }.getOrNull() ?: return null
        return runCatching {
            AesCipherHelper.decrypt(keyBytes, wrappedPacket)
        }.getOrNull()
    }

    private suspend fun persistAuthenticatedPeer(
        session: PublishedSession,
        device: BluetoothDevice,
        pending: PendingHandshake
    ) {
        val address = device.address?.trim().orEmpty()
        if (address.isBlank()) {
            return
        }
        val remotePlatform = normalizeRemotePlatform(pending.clientPlatform)
        val classicSessionCode = pending.clientSessionCode.trim()

        // Self-identity guard. Two different failures, two different answers:
        //  * same DEVICE (our own device id or session code came back at us) is a loopback — there is
        //    no peer, so anything we insert is a contact that is really us. Hard reject.
        //  * same ACCOUNT on a genuinely different device (one responder carrying a phone AND a
        //    tablet) is legitimate, so keep the Bluetooth link — but drop the internet identity,
        //    because a contact whose peerUid is our own uid makes every internet send route back into
        //    our own inbox. An SOS addressed to yourself helps nobody.
        // Every comparison is non-blank gated: a signed-out device supplies no uid, and "" == "" would
        // otherwise reject every offline pairing — the exact case this transport exists for.
        val localDeviceId = session.deviceId.trim()
        val incomingDeviceId = pending.clientDeviceId?.trim().orEmpty()
        if ((incomingDeviceId.isNotBlank() && incomingDeviceId.equals(localDeviceId, ignoreCase = true)) ||
            (classicSessionCode.isNotBlank() &&
                classicSessionCode.equals(session.sessionCode.trim(), ignoreCase = true))
        ) {
            Log.w(TAG, "Rejecting pairing: the peer identity is this device")
            return
        }
        val localUid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }
            .getOrNull()?.trim().orEmpty()
        val incomingUid = pending.clientPeerUid?.trim().orEmpty()
        val sameAccount = localUid.isNotBlank() && incomingUid.isNotBlank() && incomingUid == localUid

        val useBlePrimary = shouldPersistBlePrimaryTransport(
            remotePlatform = remotePlatform,
            sessionCode = classicSessionCode
        )
        val peerSessionCode = bleSessionCodeForPeer(
            deviceId = pending.clientDeviceId,
            fallbackSessionCode = pending.clientSessionCode,
            fallbackAddress = address
        )
        runCatching {
            val savedContact = saveBleContactAndMigrateLegacySession(
                context = applicationContext,
                contact = Contact(
                    name = pending.clientName?.takeIf { it.isNotBlank() } ?: pending.clientSessionCode,
                    aesKey = session.aesKeyBase64,
                    sessionCode = if (useBlePrimary) {
                        peerSessionCode
                    } else {
                        classicSessionCode.ifBlank { peerSessionCode }
                    },
                    verified = true,
                    verifiedIdentityKey = normalizeVerifiedIdentityKey(pending.clientDeviceId),
                    verifiedAt = System.currentTimeMillis(),
                    address = if (useBlePrimary) address else "",
                    remoteSessionCode = classicSessionCode.ifBlank { pending.clientSessionCode },
                    preferredTransport = if (useBlePrimary) {
                        PREFERRED_TRANSPORT_BLE_GATT
                    } else {
                        PREFERRED_TRANSPORT_RFCOMM
                    },
                    remotePlatform = remotePlatform,
                    lastKnownBleAddress = address,
                    remoteDeviceId = pending.clientDeviceId,
                    // Reciprocal internet identity from the authenticated client-hello, so this
                    // (QR-displaying) side can fall back to the online transport when Bluetooth is
                    // off — the fix for QR pairs being stranded on BLE. Blank for peers that didn't
                    // supply it; they stay BLE-only as before.
                    peerUid = if (sameAccount) "" else pending.clientPeerUid.orEmpty(),
                    peerPublicKey = if (sameAccount) "" else pending.clientPeerPublicKey.orEmpty()
                ),
                migrateFromSessionCode = if (useBlePrimary) {
                    pending.clientSessionCode
                } else {
                    peerSessionCode.takeIf { !it.equals(classicSessionCode, ignoreCase = true) }
                        ?: pending.clientSessionCode
                },
                analyticsSource = "ble_gatt_peer",
                analyticsReceived = true
            )
            pending.clientAvatarBase64?.takeIf { it.isNotBlank() }?.let { avatarPayload ->
                ContactAvatarStorage.saveRemoteAvatarPayload(
                    context = applicationContext,
                    sessionCode = savedContact.sessionCode,
                    payloadBase64 = avatarPayload
                )
            }
        }.onFailure { throwable ->
            Log.e(TAG, "Failed to persist authenticated P2P peer", throwable)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendChatEvent(
        kind: String,
        messageId: String?,
        device: BluetoothDevice?,
        contact: Contact
    ) {
        val targetDevice = device ?: return
        val characteristic = messageOutCharacteristic ?: return
        val server = gattServer ?: return
        val key = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return
        if (deviceKey(targetDevice)?.let(subscribedCentrals::containsKey) != true) {
            return
        }
        val senderName = localDisplayName()
        val innerBytes = JSONObject().apply {
            put("kind", kind)
            if (!messageId.isNullOrBlank()) {
                put("messageId", messageId)
            }
            if (!senderName.isNullOrBlank()) {
                put("senderName", senderName)
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val encrypted = runCatching { AesCipherHelper.encrypt(key, innerBytes) }.getOrNull() ?: return
        val outerBytes = JSONObject().apply {
            put("type", P2pBleProtocol.TYPE_CHAT_ENVELOPE)
            put("fromDeviceId", LocalKeyStorage.getOrCreateP2pDeviceId(this@P2pGattServerService))
            put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val packet = runCatching {
            P2pBleProtocol.wrapTransportPacket(outerBytes)
        }.getOrNull() ?: return
        notifyMutex.withLock {
            val maxChunkBytes = notifyChunkSizeForDevice(targetDevice)
            packet.asIterable()
            var offset = 0
            while (offset < packet.size) {
                val end = (offset + maxChunkBytes).coerceAtMost(packet.size)
                val chunk = packet.copyOfRange(offset, end)
                characteristic.value = chunk
                val notified = runCatching {
                    server.notifyCharacteristicChanged(targetDevice, characteristic, false)
                }.getOrDefault(false)
                if (!notified) {
                    return
                }
                offset = end
                if (offset < packet.size) {
                    kotlinx.coroutines.delay(NOTIFY_CHUNK_DELAY_MS)
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // Voice call link (peripheral role): signaling as encrypted chat envelopes, audio as
    // binary P2pCallProtocol frames over single unacked notifications.
    // -------------------------------------------------------------------------------------

    @Volatile
    private var cachedCallRoute: Pair<String, BluetoothDevice>? = null

    private val callAudioQueue = Channel<Pair<BluetoothDevice, ByteArray>>(
        capacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    @Volatile
    private var callAudioSenderJob: Job? = null

    private val callLink = object : P2pCallController.CallLink {
        override val linkName: String = "p2p-server"

        override fun isReadyForSession(sessionCode: String): Boolean {
            return findSubscribedDeviceForSession(sessionCode) != null
        }

        override suspend fun sendCallSignal(sessionCode: String, payload: JSONObject): Boolean {
            val contact = getContact(applicationContext, sessionCode) ?: return false
            val device = findSubscribedDeviceForSession(sessionCode) ?: return false
            rememberCallRoute(sessionCode, device)
            return sendCallEnvelope(contact, device, payload)
        }

        override fun trySendCallAudio(sessionCode: String, packet: ByteArray): Boolean {
            val cached = cachedCallRoute
            val device = if (cached?.first == sessionCode &&
                deviceKey(cached.second)?.let(subscribedCentrals::containsKey) == true
            ) {
                cached.second
            } else {
                findSubscribedDeviceForSession(sessionCode)?.also {
                    rememberCallRoute(sessionCode, it)
                } ?: return false
            }
            val wrapped = runCatching { P2pBleProtocol.wrapTransportPacket(packet) }.getOrNull()
                ?: return false
            if (wrapped.size > notifyChunkSizeForDevice(device)) {
                return false
            }
            ensureCallAudioSender()
            return callAudioQueue.trySend(device to wrapped).isSuccess
        }
    }

    private fun rememberCallRoute(sessionCode: String, device: BluetoothDevice?) {
        if (device != null) {
            cachedCallRoute = sessionCode to device
        }
    }

    /**
     * Resolves the subscribed central for a contact. The contact's lastKnownBleAddress is kept
     * fresh by every inbound envelope; when it is stale and exactly one central is subscribed,
     * that central is the only possible peer on this 1:1 link.
     */
    private fun findSubscribedDeviceForSession(sessionCode: String): BluetoothDevice? {
        cachedCallRoute?.let { (cachedSession, cachedDevice) ->
            if (cachedSession == sessionCode &&
                deviceKey(cachedDevice)?.let(subscribedCentrals::containsKey) == true
            ) {
                return cachedDevice
            }
        }
        val contact = runCatching { getContact(applicationContext, sessionCode) }.getOrNull()
        val address = contact?.lastKnownBleAddress?.trim()?.uppercase(Locale.US).orEmpty()
        if (address.isNotBlank()) {
            subscribedCentrals[address]?.let { return it }
        }
        return subscribedCentrals.values.singleOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun ensureCallAudioSender() {
        if (callAudioSenderJob?.isActive == true) return
        synchronized(this) {
            if (callAudioSenderJob?.isActive == true) return
            callAudioSenderJob = serviceScope.launch {
                for ((device, wrapped) in callAudioQueue) {
                    notifyMutex.withLock {
                        if (!hasBluetoothConnectPermission()) return@withLock
                        val characteristic = messageOutCharacteristic ?: return@withLock
                        val server = gattServer ?: return@withLock
                        characteristic.value = wrapped
                        runCatching {
                            server.notifyCharacteristicChanged(device, characteristic, false)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendCallEnvelope(
        contact: Contact,
        device: BluetoothDevice,
        innerPayload: JSONObject
    ): Boolean {
        val characteristic = messageOutCharacteristic ?: return false
        val server = gattServer ?: return false
        val key = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return false
        if (deviceKey(device)?.let(subscribedCentrals::containsKey) != true) {
            return false
        }
        val innerBytes = innerPayload.toString().toByteArray(StandardCharsets.UTF_8)
        val encrypted = runCatching { AesCipherHelper.encrypt(key, innerBytes) }.getOrNull()
            ?: return false
        val outerBytes = JSONObject().apply {
            put("type", P2pBleProtocol.TYPE_CHAT_ENVELOPE)
            put("fromDeviceId", LocalKeyStorage.getOrCreateP2pDeviceId(this@P2pGattServerService))
            put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val packet = runCatching { P2pBleProtocol.wrapTransportPacket(outerBytes) }.getOrNull()
            ?: return false
        notifyMutex.withLock {
            val maxChunkBytes = notifyChunkSizeForDevice(device)
            var offset = 0
            while (offset < packet.size) {
                val end = (offset + maxChunkBytes).coerceAtMost(packet.size)
                val chunk = packet.copyOfRange(offset, end)
                characteristic.value = chunk
                val notified = runCatching {
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }.getOrDefault(false)
                if (!notified) {
                    return false
                }
                offset = end
                if (offset < packet.size) {
                    kotlinx.coroutines.delay(NOTIFY_CHUNK_DELAY_MS)
                }
            }
        }
        return true
    }

    private fun handleCallAction(action: String, intent: Intent) {
        val sessionCode = intent.getStringExtra(RfcommForegroundService.EXTRA_SESSION_CODE)
            ?.takeIf { it.isNotBlank() } ?: return
        val callId = intent.getStringExtra(RfcommForegroundService.EXTRA_CALL_ID).orEmpty()
        val controller = P2pCallController.shared(applicationContext)
        when (action) {
            ACTION_CALL_ACCEPT -> controller.acceptCall(sessionCode, callId)
            ACTION_CALL_REJECT -> controller.rejectCall(sessionCode, callId)
            ACTION_CALL_HANGUP -> controller.endActiveCallFromNotification(sessionCode, callId)
            ACTION_CALL_MUTE -> controller.setMuted(sessionCode, muted = true)
            ACTION_CALL_UNMUTE -> controller.setMuted(sessionCode, muted = false)
            ACTION_CALL_SPEAKER -> controller.toggleSpeaker(sessionCode)
            RfcommForegroundService.ACTION_ACCEPT_CALL -> {
                val acceptedByTelecom = intent.getStringExtra(RfcommForegroundService.EXTRA_CALL_FLAG) ==
                    RfcommForegroundService.CALL_ANSWERED_BY_TELECOM_FLAG
                controller.acceptCall(sessionCode, callId, acceptedByTelecom = acceptedByTelecom)
            }
            RfcommForegroundService.ACTION_REJECT_CALL -> controller.rejectCall(sessionCode, callId)
            RfcommForegroundService.ACTION_END_CALL ->
                controller.endActiveCallFromNotification(sessionCode, callId)
            RfcommForegroundService.ACTION_MUTE -> controller.setMuted(sessionCode, muted = true)
            RfcommForegroundService.ACTION_UNMUTE -> controller.setMuted(sessionCode, muted = false)
            RfcommForegroundService.ACTION_SYNC_CALL_AUDIO_ROUTE -> {
                val route = intent.getStringExtra(RfcommForegroundService.EXTRA_CALL_AUDIO_ROUTE)
                    ?.let { name -> runCatching { CallAudioRoute.valueOf(name) }.getOrNull() }
                controller.onTelecomAudioRouteChanged(sessionCode, route)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            .onFailure { throwable ->
                if (throwable is SecurityException) {
                    Log.w(TAG, "Unable to stop P2P advertiser due to missing permission", throwable)
                }
            }
        isGattHostingActive = false
    }

    @SuppressLint("MissingPermission")
    private fun closeGattServer() {
        runCatching { gattServer?.close() }
            .onFailure { throwable ->
                if (throwable is SecurityException) {
                    Log.w(TAG, "Unable to close P2P GATT server due to missing permission", throwable)
                }
            }
        awaitingPrimaryServiceRegistration = false
        gattServer = null
        messageOutCharacteristic = null
        hostedShareSession = null
    }

    private fun sharedGattDelegatesSnapshot(): List<SharedGattDelegate> {
        return synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.values.toList()
        }
    }

    private fun sharedGattDelegateForCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): SharedGattDelegate? {
        val serviceUuid = characteristic.service?.uuid ?: return null
        return synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates[serviceUuid]
        }
    }

    private fun sharedGattDelegateForDescriptor(
        descriptor: BluetoothGattDescriptor
    ): SharedGattDelegate? {
        val serviceUuid = descriptor.characteristic.service?.uuid ?: return null
        return synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates[serviceUuid]
        }
    }

    private fun dispatchSharedConnectionStateChange(
        device: BluetoothDevice,
        status: Int,
        newState: Int
    ) {
        sharedGattDelegatesSnapshot().forEach { delegate ->
            runCatching {
                delegate.onConnectionStateChange(device, status, newState)
            }.onFailure { throwable ->
                Log.w(TAG, "Shared P2P delegate connection callback failed", throwable)
            }
        }
    }

    private fun dispatchSharedMtuChange(device: BluetoothDevice, mtu: Int) {
        sharedGattDelegatesSnapshot().forEach { delegate ->
            runCatching {
                delegate.onMtuChanged(device, mtu)
            }.onFailure { throwable ->
                Log.w(TAG, "Shared P2P delegate MTU callback failed", throwable)
            }
        }
    }

    private fun dispatchSharedNotificationSent(device: BluetoothDevice, status: Int) {
        sharedGattDelegatesSnapshot().forEach { delegate ->
            runCatching {
                delegate.onNotificationSent(device, status)
            }.onFailure { throwable ->
                Log.w(TAG, "Shared P2P delegate notify callback failed", throwable)
            }
        }
    }

    private fun handleSharedCharacteristicRead(
        device: BluetoothDevice,
        offset: Int,
        characteristic: BluetoothGattCharacteristic
    ): SharedGattReadResult? {
        val delegate = sharedGattDelegateForCharacteristic(characteristic) ?: return null
        return runCatching {
            delegate.onCharacteristicReadRequest(
                device = device,
                offset = offset,
                characteristic = characteristic
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Shared P2P delegate characteristic read failed", throwable)
        }.getOrNull()
    }

    private fun handleSharedCharacteristicWrite(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?
    ): Int? {
        val delegate = sharedGattDelegateForCharacteristic(characteristic) ?: return null
        return runCatching {
            delegate.onCharacteristicWriteRequest(
                device = device,
                characteristic = characteristic,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                offset = offset,
                value = value
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Shared P2P delegate characteristic write failed", throwable)
        }.getOrNull()
    }

    private fun handleSharedDescriptorRead(
        device: BluetoothDevice,
        offset: Int,
        descriptor: BluetoothGattDescriptor
    ): SharedGattReadResult? {
        val delegate = sharedGattDelegateForDescriptor(descriptor) ?: return null
        return runCatching {
            delegate.onDescriptorReadRequest(
                device = device,
                offset = offset,
                descriptor = descriptor
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Shared P2P delegate descriptor read failed", throwable)
        }.getOrNull()
    }

    private fun handleSharedDescriptorWrite(
        device: BluetoothDevice,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray
    ): Int? {
        val delegate = sharedGattDelegateForDescriptor(descriptor) ?: return null
        return runCatching {
            delegate.onDescriptorWriteRequest(
                device = device,
                descriptor = descriptor,
                preparedWrite = preparedWrite,
                responseNeeded = responseNeeded,
                offset = offset,
                value = value
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Shared P2P delegate descriptor write failed", throwable)
        }.getOrNull()
    }

    private fun registerPendingSharedGattServices(): Boolean {
        val delegates = sharedGattDelegatesSnapshot()
        if (delegates.isEmpty()) {
            return true
        }
        delegates.forEach { delegate ->
            if (!addSharedGattDelegateService(delegate)) {
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun addSharedGattDelegateService(delegate: SharedGattDelegate): Boolean {
        val server = gattServer ?: return true
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot add shared P2P GATT service without BLUETOOTH_CONNECT permission")
            return false
        }
        if (server.getService(delegate.serviceUuid) != null) {
            return true
        }
        val service = runCatching { delegate.createService() }
            .onFailure { throwable ->
                Log.w(TAG, "Shared P2P delegate service creation failed", throwable)
            }
            .getOrNull() ?: return false
        return runCatching { server.addService(service) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed to add shared P2P GATT service ${delegate.serviceUuid}", throwable)
            }
            .getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun removeSharedGattDelegateService(serviceUuid: UUID) {
        val server = gattServer ?: return
        if (!hasBluetoothConnectPermission()) {
            return
        }
        val service = server.getService(serviceUuid) ?: return
        runCatching { server.removeService(service) }
            .onFailure { throwable ->
                Log.w(TAG, "Failed to remove shared P2P GATT service $serviceUuid", throwable)
            }
    }

    private fun registerSharedGattDelegateInternal(delegate: SharedGattDelegate): Boolean {
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates[delegate.serviceUuid] = delegate
        }
        val added = if (isGattHostingActive && gattServer != null) {
            restartGattPublication()
        } else {
            addSharedGattDelegateService(delegate)
        }
        if (!added) {
            synchronized(sharedGattDelegatesLock) {
                val current = sharedGattDelegates[delegate.serviceUuid]
                if (current === delegate) {
                    sharedGattDelegates.remove(delegate.serviceUuid)
                }
            }
            return false
        }
        if (!isGattHostingActive || gattServer == null) {
            refreshAdvertisingForCurrentProfiles()
        }
        return true
    }

    private fun unregisterSharedGattDelegateInternal(serviceUuid: UUID) {
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.remove(serviceUuid)
        }
        if (isGattHostingActive && gattServer != null) {
            if (!restartGattPublication()) {
                stopSelf()
            }
            return
        }
        removeSharedGattDelegateService(serviceUuid)
        refreshAdvertisingForCurrentProfiles()
    }

    private fun refreshAdvertisingForCurrentProfiles() {
        if (!isGattHostingActive || gattServer == null) {
            return
        }
        stopAdvertising()
        if (!startAdvertisingForCurrentProfiles()) {
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectSharedClientsInternal(addresses: Collection<String>) {
        if (addresses.isEmpty()) {
            return
        }
        val server = gattServer ?: return
        val adapter = bluetoothAdapter ?: return
        if (!hasBluetoothConnectPermission()) {
            return
        }
        addresses.asSequence()
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()
            .forEach { address ->
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return@forEach
                runCatching { server.cancelConnection(device) }
                    .onFailure { throwable ->
                        Log.w(TAG, "Failed to cancel shared P2P GATT connection for $address", throwable)
                    }
            }
    }

    private fun currentGattServer(): BluetoothGattServer? = gattServer?.takeIf { isGattHostingActive }

    private fun isHostingCurrentConfiguration(): Boolean {
        if (!isGattHostingActive || gattServer == null) {
            return false
        }
        return hostedShareSession == shareSession
    }

    private fun createNotification(): Notification {
        ensureNotificationChannel()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.p2p_share_notification_title))
            .setContentText(getString(R.string.p2p_share_notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.p2p_share_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasBluetoothAdvertisePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendResponseSafely(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        return runCatching {
            server.sendResponse(device, requestId, status, offset, value)
        }.getOrDefault(false)
    }

    private fun Intent.toPublishedSession(): PublishedSession? {
        val shareId = getStringExtra(EXTRA_SHARE_ID)
            ?.let(P2pBleProtocol::normalizeShareId)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val sessionCode = getStringExtra(EXTRA_SESSION_CODE)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val aesKeyBase64 = getStringExtra(EXTRA_AES_KEY_BASE64)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val decodedKey = P2pBleProtocol.decodeBase64(aesKeyBase64)
        if (decodedKey == null || decodedKey.isEmpty()) {
            return null
        }
        val displayName = getStringExtra(EXTRA_DISPLAY_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        val avatarBase64 = localAvatarPayload()
        val serverNonce = P2pBleProtocol.randomNonceBase64()
        val deviceId = LocalKeyStorage.getOrCreateP2pDeviceId(this@P2pGattServerService)
        val bootstrapPayload = JSONObject().apply {
            put("shareId", shareId)
            put("sessionCode", sessionCode)
            put("platform", "android")
            put("protocolVersion", P2pBleProtocol.PROTOCOL_VERSION)
            put("serverNonce", serverNonce)
            put("serverDeviceId", deviceId)
            if (!displayName.isNullOrBlank()) {
                put("name", displayName)
            }
            if (!avatarBase64.isNullOrBlank()) {
                put("avatarB64", avatarBase64)
            }
        }.toString()
        return PublishedSession(
            shareId = shareId,
            sessionCode = sessionCode,
            displayName = displayName,
            bootstrapPayload = bootstrapPayload,
            aesKeyBase64 = aesKeyBase64,
            serverNonce = serverNonce,
            deviceId = deviceId
        )
    }

    private fun bleSessionCodeForPeer(
        deviceId: String,
        fallbackSessionCode: String,
        fallbackAddress: String
    ): String {
        val raw = deviceId.trim()
            .ifBlank { fallbackSessionCode.trim() }
            .ifBlank { fallbackAddress.trim() }
        return "ble:${raw.uppercase(Locale.US)}"
    }

    private fun shouldPersistBlePrimaryTransport(
        remotePlatform: String,
        sessionCode: String
    ): Boolean {
        return remotePlatform == REMOTE_PLATFORM_IOS ||
            sessionCode.trim().startsWith("ble:", ignoreCase = true)
    }

    private fun clearAllControlState() {
        deviceResponses.clear()
        pendingHandshakes.clear()
        messageReceivers.clear()
        subscribedCentrals.clear()
        centralMtuByAddress.clear()
    }

    private fun clearDeviceState(device: BluetoothDevice?) {
        val key = deviceKey(device) ?: return
        deviceResponses.remove(key)
        pendingHandshakes.remove(key)
        messageReceivers.remove(key)
        subscribedCentrals.remove(key)
        centralMtuByAddress.remove(key)
    }

    private fun setDeviceResponse(deviceKey: String, payload: String) {
        deviceResponses[deviceKey] = payload.toByteArray(Charsets.UTF_8)
    }

    private fun setErrorResponse(deviceKey: String, code: String, message: String) {
        setDeviceResponse(
            deviceKey,
            JSONObject().apply {
                put("type", P2pBleProtocol.TYPE_ERROR)
                put("code", code)
                put("message", message)
            }.toString()
        )
    }

    private fun deviceKey(device: BluetoothDevice?): String? {
        return device?.address?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
    }

    private fun localDisplayName(): String? {
        return runCatching {
            runBlocking {
                getSavedUserName(this@P2pGattServerService).first().trim()
            }.takeIf { it.isNotBlank() }
        }.getOrNull() ?: getString(R.string.app_name)
    }

    private fun localAvatarPayload(): String? {
        return ContactAvatarStorage.localProfileAvatarPayload(applicationContext)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun notifyChunkSizeForDevice(device: BluetoothDevice): Int {
        val mtu = deviceKey(device)?.let(centralMtuByAddress::get) ?: DEFAULT_ATT_MTU
        return (mtu - ATT_NOTIFY_OVERHEAD_BYTES)
            .coerceIn(LEGACY_NOTIFY_CHUNK_BYTES, MAX_NOTIFY_CHUNK_BYTES)
    }

    interface SharedGattDelegate {
        val serviceUuid: UUID

        fun createService(): BluetoothGattService

        fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = Unit

        fun onMtuChanged(device: BluetoothDevice, mtu: Int) = Unit

        fun onNotificationSent(device: BluetoothDevice, status: Int) = Unit

        fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ): SharedGattReadResult? = null

        fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ): Int? = null

        fun onDescriptorReadRequest(
            device: BluetoothDevice,
            offset: Int,
            descriptor: BluetoothGattDescriptor
        ): SharedGattReadResult? = null

        fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ): Int? = null
    }

    data class SharedGattReadResult(
        val status: Int,
        val value: ByteArray = ByteArray(0)
    )

    companion object {
        private const val TAG = "P2pGattServer"
        private const val NOTIFICATION_CHANNEL_ID = "p2p_share_channel"
        private const val NOTIFICATION_ID = 1007
        private const val EXTRA_SHARE_ID = "extra_share_id"
        private const val EXTRA_SESSION_CODE = "extra_session_code"
        private const val EXTRA_DISPLAY_NAME = "extra_display_name"
        private const val EXTRA_AES_KEY_BASE64 = "extra_aes_key_base64"
        private const val ACTION_START_SHARE = "com.auralis.crisisconnect.action.P2P_START_SHARE"
        private const val ACTION_STOP_SHARE = "com.auralis.crisisconnect.action.P2P_STOP_SHARE"
        private const val ACTION_ENSURE_HOST = "com.auralis.crisisconnect.action.P2P_ENSURE_HOST"
        private const val ACTION_ACQUIRE_SHARED_HOST =
            "com.auralis.crisisconnect.action.P2P_ACQUIRE_SHARED_HOST"
        private const val ACTION_RELEASE_SHARED_HOST =
            "com.auralis.crisisconnect.action.P2P_RELEASE_SHARED_HOST"
        const val ACTION_CALL_ACCEPT = "com.auralis.crisisconnect.action.P2P_CALL_ACCEPT"
        const val ACTION_CALL_REJECT = "com.auralis.crisisconnect.action.P2P_CALL_REJECT"
        const val ACTION_CALL_HANGUP = "com.auralis.crisisconnect.action.P2P_CALL_HANGUP"
        const val ACTION_CALL_MUTE = "com.auralis.crisisconnect.action.P2P_CALL_MUTE"
        const val ACTION_CALL_UNMUTE = "com.auralis.crisisconnect.action.P2P_CALL_UNMUTE"
        const val ACTION_CALL_SPEAKER = "com.auralis.crisisconnect.action.P2P_CALL_SPEAKER"

        /**
         * Upgrades/downgrades the foreground-service type around an active voice call. Must be
         * triggered from a user-visible context (chat screen or the call notification) so the
         * Android 14+ mic-while-in-use rule is satisfied.
         */
        fun applyCallForegroundType(context: Context, callActive: Boolean) {
            activeInstance?.refreshForegroundTypeForCall(callActive)
        }
        private const val MESH_INITIATOR_RANK_MANUFACTURER_ID = 0x0F0F
        private const val MAX_TRANSPORT_PACKET_BYTES = 8_192
        private const val MAX_ENCRYPTED_CHAT_PACKET_BYTES = 4_096
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_NOTIFY_OVERHEAD_BYTES = 3
        private const val LEGACY_NOTIFY_CHUNK_BYTES = 20
        private const val MAX_NOTIFY_CHUNK_BYTES = 244
        private const val NOTIFY_CHUNK_DELAY_MS = 8L

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _publishedSession = MutableStateFlow<PublishedSession?>(null)
        val publishedSession: StateFlow<PublishedSession?> = _publishedSession.asStateFlow()
        @Volatile
        private var activeInstance: P2pGattServerService? = null

        fun acquireSharedHost(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, P2pGattServerService::class.java).apply {
                action = ACTION_ACQUIRE_SHARED_HOST
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun releaseSharedHost(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, P2pGattServerService::class.java).apply {
                action = ACTION_RELEASE_SHARED_HOST
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun registerSharedGattDelegate(delegate: SharedGattDelegate): Boolean {
            return activeInstance?.registerSharedGattDelegateInternal(delegate) ?: false
        }

        fun unregisterSharedGattDelegate(serviceUuid: UUID) {
            activeInstance?.unregisterSharedGattDelegateInternal(serviceUuid)
        }

        fun sharedGattServerOrNull(): BluetoothGattServer? {
            return activeInstance?.currentGattServer()
        }

        fun disconnectSharedClients(addresses: Collection<String>) {
            activeInstance?.disconnectSharedClientsInternal(addresses)
        }

        fun startPublishing(
            context: Context,
            shareId: String,
            sessionCode: String,
            displayName: String?,
            aesKeyBase64: String
        ) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, P2pGattServerService::class.java).apply {
                action = ACTION_START_SHARE
                putExtra(EXTRA_SHARE_ID, shareId)
                putExtra(EXTRA_SESSION_CODE, sessionCode)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_AES_KEY_BASE64, aesKeyBase64)
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stopPublishing(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, P2pGattServerService::class.java).apply {
                action = ACTION_STOP_SHARE
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun ensureHosting(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, P2pGattServerService::class.java).apply {
                action = ACTION_ENSURE_HOST
            }
            ContextCompat.startForegroundService(appContext, intent)
        }

        private fun publishRunningState(running: Boolean, session: PublishedSession?) {
            _isRunning.value = running
            _publishedSession.value = if (running) session else null
        }
    }
}

data class PublishedSession(
    val shareId: String,
    val sessionCode: String,
    val displayName: String?,
    val bootstrapPayload: String,
    val aesKeyBase64: String,
    val serverNonce: String,
    val deviceId: String
)
