package com.auralis.crisisconnect.service

import android.annotation.SuppressLint
import android.Manifest
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
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.BatteryManager
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.generateImageThumbnail
import com.auralis.crisisconnect.data.BleChatStore
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.markAllLocalMessagesRead
import com.auralis.crisisconnect.data.markAllSentMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesRead
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.service.BleMessageNotifier
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.auralis.crisisconnect.service.media.ImageTransferDirection
import com.auralis.crisisconnect.service.media.ImageTransferState
import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import com.auralis.crisisconnect.service.voice.VoiceTransferDirection
import com.auralis.crisisconnect.service.voice.VoiceTransferState
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.security.BleChunkReceiver
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.RoleProofVerificationResult
import com.auralis.crisisconnect.security.RoleProofVerifier
import com.auralis.crisisconnect.util.UUIDGenerator
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlin.collections.set
import kotlin.coroutines.resume
import kotlin.text.Charsets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class GattSOSServerService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val sharedGattDelegates = mutableMapOf<UUID, SharedGattDelegate>()
    private val sharedGattDelegatesLock = Any()

    private val sessionKeys = mutableMapOf<String, ByteArray>()
    private val serverPublicKeys = mutableMapOf<String, ByteArray>()
    private val authSessionNonceByAddress = mutableMapOf<String, String>()
    private val ackStates = mutableMapOf<String, ByteArray>()
    private val chatOutPayloads = mutableMapOf<String, ByteArray>()
    private val clientConfigValues = mutableMapOf<Pair<String, UUID>, ByteArray>()
    private val verifiedClients = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val sessionCodesByAddress = mutableMapOf<String, String>()
    private val connectedGattDevices =
        Collections.synchronizedMap(mutableMapOf<String, BluetoothDevice>())
    private val secureInReceivers = Collections.synchronizedMap(mutableMapOf<String, BleChunkReceiver>())
    private val secureChatReceivers = Collections.synchronizedMap(mutableMapOf<String, BleChunkReceiver>())
    private val incomingVoiceTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleVoicePayload.IncomingTransfer>())
    private val incomingImageTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleImagePayload.IncomingTransfer>())
    private val incomingFileTransfers =
        Collections.synchronizedMap(mutableMapOf<String, BleFilePayload.IncomingTransfer>())
    private val mtuByAddress = Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val roleProofVerifier = RoleProofVerifier(
        maxPacketSize = MAX_ROLE_PROOF_PACKET_BYTES
    )
    private var localUserName: String = ""
    private val rescueBroadcastId: String by lazy {
        LocalKeyStorage.getOrCreateRescueDeviceId(applicationContext)
    }
    private val rescueBroadcastServiceData: ByteArray by lazy {
        LocalKeyStorage.getRescueDeviceIdBytes(applicationContext)
    }

    private val crisisServiceUuid: UUID = CRISIS_SERVICE_UUID
    private val idCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_ID_ASSIGNED_NUMBER)
    private val statusCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_STATUS_ASSIGNED_NUMBER)
    private val authChallengeCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_AUTH_CHALLENGE_NUMBER)
    private val authResponseCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_AUTH_RESPONSE_NUMBER)
    private val secureInCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_IN_NUMBER)
    private val secureAckCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_ACK_NUMBER)
    private val secureChatInCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_CHAT_IN_NUMBER)
    private val secureChatOutCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_CHAT_OUT_NUMBER)

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        clearStartupFailureMessage()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        serviceScope.launch {
            localUserName = runCatching { getSavedUserName(applicationContext).first() }
                .getOrDefault("")
                .trim()
        }

        startForeground(NOTIFICATION_ID, createNotification())
        if (!startGattServer()) {
            return
        }
        if (!startAdvertising()) {
            return
        }
        _isRunning.value = true
        _startTimestampMillis.value = System.currentTimeMillis()
        runCatching {
            GattMeshForegroundService.requestSosModeReconcile(applicationContext)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to request GATT mesh SOS-mode reconcile after SOS start", throwable)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        _isRunning.value = false
        _startTimestampMillis.value = null
        runCatching {
            GattMeshForegroundService.requestSosModeReconcile(applicationContext)
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to request GATT mesh SOS-mode reconcile after SOS stop", throwable)
        }
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
            .onFailure { throwable ->
                if (throwable is SecurityException) {
                    Log.w(TAG, "Failed to stop BLE advertising due to missing permission", throwable)
                }
            }
        runCatching { gattServer?.close() }
            .onFailure { throwable ->
                if (throwable is SecurityException) {
                    Log.w(TAG, "Failed to close GATT server due to missing permission", throwable)
                }
            }
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.clear()
        }
        if (activeInstance === this) {
            activeInstance = null
        }
        stopForeground(true)
        sessionCodesByAddress.values.toSet().forEach { session ->
            BleVoiceTransferProgressStore.clearSession(session)
            BleImageTransferProgressStore.clearSession(session)
        }
        connectedGattDevices.clear()
        BlePeerStore.clear()
        BleChatStore.clear()
        verifiedClients.clear()
        sessionCodesByAddress.clear()
        authSessionNonceByAddress.clear()
        mtuByAddress.clear()
        chatOutPayloads.clear()
        secureInReceivers.clear()
        incomingVoiceTransfers.clear()
        incomingImageTransfers.clear()
        incomingFileTransfers.clear()
        serviceScope.cancel()
        Log.d(TAG, "SOS broadcast stopped.")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @SuppressLint("MissingPermission")
    private fun startGattServer(): Boolean {
        val manager = bluetoothManager ?: run {
            Log.e(TAG, "BluetoothManager not available")
            publishStartupFailure(getString(R.string.rescue_error_bluetooth_unavailable))
            stopSelf()
            return false
        }

        val adapter = bluetoothAdapter ?: run {
            Log.e(TAG, "BluetoothAdapter not available")
            publishStartupFailure(getString(R.string.rescue_error_bluetooth_unavailable))
            stopSelf()
            return false
        }

        if (!adapter.isEnabled) {
            Log.e(TAG, "BluetoothAdapter is disabled")
            publishStartupFailure(getString(R.string.rescue_error_bluetooth_disabled))
            stopSelf()
            return false
        }
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Missing BLUETOOTH_CONNECT permission. Unable to start GATT server.")
            publishStartupFailure(getString(R.string.rescue_error_permission_required))
            stopSelf()
            return false
        }

        val callback = object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                Log.d(TAG, "Device ${device.address} connection state changed: $newState")
                val normalizedAddress = device.address.uppercase(Locale.US)
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    connectedGattDevices[normalizedAddress] = device
                    mtuByAddress[normalizedAddress] = DEFAULT_MTU
                    secureInReceivers[normalizedAddress] = BleChunkReceiver(
                        MAX_ROLE_PROOF_PACKET_BYTES,
                        tag = "SecureInReceiver-$normalizedAddress",
                    )
                    secureChatReceivers[normalizedAddress] = BleChunkReceiver(
                        MAX_SECURE_CHAT_PACKET_BYTES,
                        tag = "SecureChatReceiver-$normalizedAddress",
                    )
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val sessionCode = sessionCodesByAddress[normalizedAddress]
                    sessionKeys.remove(normalizedAddress)
                    serverPublicKeys.remove(normalizedAddress)
                    authSessionNonceByAddress.remove(normalizedAddress)
                    ackStates.remove(normalizedAddress)
                    chatOutPayloads.remove(normalizedAddress)
                    verifiedClients.remove(normalizedAddress)
                    sessionCodesByAddress.remove(normalizedAddress)
                    clientConfigValues.entries.removeIf { it.key.first == normalizedAddress }
                    mtuByAddress.remove(normalizedAddress)
                    BlePeerStore.remove(normalizedAddress)
                    clearVoiceTransfersForAddress(normalizedAddress)
                    clearImageTransfersForAddress(normalizedAddress)
                    clearFileTransfersForAddress(normalizedAddress)
                    if (!sessionCode.isNullOrBlank()) {
                        BleVoiceTransferProgressStore.clearSession(sessionCode)
                        BleImageTransferProgressStore.clearSession(sessionCode)
                    }
                    secureInReceivers.remove(normalizedAddress)?.let { receiver ->
                        runCatching { receiver.ensureNoPartialData() }
                            .onFailure { throwable ->
                                Log.w(TAG, "[$normalizedAddress] Incomplete secure payload on disconnect", throwable)
                            }
                    }
                    secureChatReceivers.remove(normalizedAddress)?.let { receiver ->
                        runCatching { receiver.ensureNoPartialData() }
                            .onFailure { throwable ->
                                Log.w(TAG, "[$normalizedAddress] Incomplete chat payload on disconnect", throwable)
                            }
                    }
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectedGattDevices.remove(normalizedAddress)
                }
                dispatchSharedConnectionStateChange(device, status, newState)
            }

            override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
                val normalizedAddress = device.address.uppercase(Locale.US)
                mtuByAddress[normalizedAddress] = mtu
                Log.d(TAG, "[$normalizedAddress] Server MTU changed to $mtu")
                dispatchSharedMtuChange(device, mtu)
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) {
                dispatchSharedNotificationSent(device, status)
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic
            ) {
                val sharedResponse = handleSharedCharacteristicRead(
                    device = device,
                    offset = offset,
                    characteristic = characteristic
                )
                if (sharedResponse != null) {
                    sendGattResponse(
                        device = device,
                        requestId = requestId,
                        status = sharedResponse.status,
                        offset = offset,
                        value = sharedResponse.value
                    )
                    return
                }
                val normalizedAddress = device.address.uppercase(Locale.US)
                val response = when (characteristic.uuid) {
                    idCharacteristicUuid -> {
                        buildBroadcastIdPayload().toByteArray(Charsets.UTF_8)
                    }

                    statusCharacteristicUuid -> DEFAULT_STATUS.toByteArray(Charsets.UTF_8)

                    authResponseCharacteristicUuid -> serverPublicKeys[normalizedAddress] ?: ByteArray(0)

                    secureAckCharacteristicUuid -> ackStates[normalizedAddress] ?: ByteArray(0)

                    secureChatOutCharacteristicUuid -> chatOutPayloads[normalizedAddress] ?: ByteArray(0)

                    else -> ByteArray(0)
                }
                sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
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
                val status = when (characteristic.uuid) {
                    authChallengeCharacteristicUuid -> handleAuthChallengeWrite(device, value)
                    secureInCharacteristicUuid -> handleSecureInWrite(device, value)
                    secureChatInCharacteristicUuid -> handleSecureChatInWrite(device, value)
                    secureAckCharacteristicUuid -> handleAckWrite(device, value)
                    else -> handleSharedCharacteristicWrite(
                        device = device,
                        characteristic = characteristic,
                        preparedWrite = preparedWrite,
                        responseNeeded = responseNeeded,
                        offset = offset,
                        value = value
                    ) ?: BluetoothGatt.GATT_FAILURE
                }

                if (responseNeeded) {
                    sendGattResponse(device, requestId, status, offset, ByteArray(0))
                }
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                descriptor: BluetoothGattDescriptor
            ) {
                val isSosCccd = descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic.service?.uuid == crisisServiceUuid
                if (!isSosCccd) {
                    val sharedResponse = handleSharedDescriptorRead(
                        device = device,
                        offset = offset,
                        descriptor = descriptor
                    )
                    if (sharedResponse != null) {
                        sendGattResponse(
                            device = device,
                            requestId = requestId,
                            status = sharedResponse.status,
                            offset = offset,
                            value = sharedResponse.value
                        )
                        return
                    }
                }
                val normalizedAddress = device.address.uppercase(Locale.US)
                val value = if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                    clientConfigValues[normalizedAddress to descriptor.characteristic.uuid]
                        ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                } else {
                    descriptor.value ?: BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                }
                sendGattResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
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
                val isSosCccd = descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID &&
                    descriptor.characteristic.service?.uuid == crisisServiceUuid
                val status = if (isSosCccd) {
                    val normalizedAddress = device.address.uppercase(Locale.US)
                    clientConfigValues[normalizedAddress to descriptor.characteristic.uuid] = value.clone()
                    BluetoothGatt.GATT_SUCCESS
                } else {
                    handleSharedDescriptorWrite(
                        device = device,
                        descriptor = descriptor,
                        preparedWrite = preparedWrite,
                        responseNeeded = responseNeeded,
                        offset = offset,
                        value = value
                    ) ?: BluetoothGatt.GATT_FAILURE
                }
                if (responseNeeded) {
                    sendGattResponse(device, requestId, status, offset, ByteArray(0))
                }
            }
        }

        gattServer = manager.openGattServer(this, callback) ?: run {
            Log.e(TAG, "Unable to open GATT server")
            publishStartupFailure(getString(R.string.sos_error_gatt_server_not_started))
            stopSelf()
            return false
        }

        val service = BluetoothGattService(crisisServiceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        val idCharacteristic = BluetoothGattCharacteristic(
            idCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val statusCharacteristic = BluetoothGattCharacteristic(
            statusCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val authChallengeCharacteristic = BluetoothGattCharacteristic(
            authChallengeCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val authResponseCharacteristic = BluetoothGattCharacteristic(
            authResponseCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val secureInCharacteristic = BluetoothGattCharacteristic(
            secureInCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val secureAckCharacteristic = BluetoothGattCharacteristic(
            secureAckCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val secureChatInCharacteristic = BluetoothGattCharacteristic(
            secureChatInCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val secureChatOutCharacteristic = BluetoothGattCharacteristic(
            secureChatOutCharacteristicUuid,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        authResponseCharacteristic.addDescriptor(createClientConfigDescriptor())
        secureAckCharacteristic.addDescriptor(createClientConfigDescriptor())
        secureChatOutCharacteristic.addDescriptor(createClientConfigDescriptor())

        service.addCharacteristic(idCharacteristic)
        service.addCharacteristic(statusCharacteristic)
        service.addCharacteristic(authChallengeCharacteristic)
        service.addCharacteristic(authResponseCharacteristic)
        service.addCharacteristic(secureInCharacteristic)
        service.addCharacteristic(secureAckCharacteristic)
        service.addCharacteristic(secureChatInCharacteristic)
        service.addCharacteristic(secureChatOutCharacteristic)

        if (!runCatching { gattServer!!.addService(service) }
                .onFailure { throwable ->
                    Log.e(TAG, "Failed to add SOS service to GATT server", throwable)
                }
                .getOrDefault(false)
        ) {
            Log.e(TAG, "Failed to add SOS service to GATT server")
            publishStartupFailure(getString(R.string.sos_error_gatt_server_not_started))
            stopSelf()
            return false
        }
        if (!registerPendingSharedGattServices()) {
            Log.e(TAG, "Failed to add shared GATT services to SOS server")
            publishStartupFailure(getString(R.string.sos_error_gatt_server_not_started))
            stopSelf()
            return false
        }

        Log.d(TAG, "GATT Server started with Crisis Connect service.")
        return true
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
                Log.w(TAG, "Shared GATT delegate connection callback failed", throwable)
            }
        }
    }

    private fun dispatchSharedMtuChange(
        device: BluetoothDevice,
        mtu: Int
    ) {
        sharedGattDelegatesSnapshot().forEach { delegate ->
            runCatching {
                delegate.onMtuChanged(device, mtu)
            }.onFailure { throwable ->
                Log.w(TAG, "Shared GATT delegate MTU callback failed", throwable)
            }
        }
    }

    private fun dispatchSharedNotificationSent(
        device: BluetoothDevice,
        status: Int
    ) {
        sharedGattDelegatesSnapshot().forEach { delegate ->
            runCatching {
                delegate.onNotificationSent(device, status)
            }.onFailure { throwable ->
                Log.w(TAG, "Shared GATT delegate notify callback failed", throwable)
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
            Log.w(TAG, "Shared GATT delegate characteristic read failed", throwable)
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
            Log.w(TAG, "Shared GATT delegate characteristic write failed", throwable)
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
            Log.w(TAG, "Shared GATT delegate descriptor read failed", throwable)
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
            Log.w(TAG, "Shared GATT delegate descriptor write failed", throwable)
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
            Log.w(TAG, "Cannot add shared GATT service without BLUETOOTH_CONNECT permission")
            return false
        }
        if (server.getService(delegate.serviceUuid) != null) {
            return true
        }
        val service = runCatching {
            delegate.createService()
        }.onFailure { throwable ->
            Log.w(TAG, "Shared GATT delegate service creation failed", throwable)
        }.getOrNull() ?: return false
        return runCatching {
            server.addService(service)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to add shared GATT service ${delegate.serviceUuid}", throwable)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun removeSharedGattDelegateService(serviceUuid: UUID) {
        val server = gattServer ?: return
        if (!hasBluetoothConnectPermission()) {
            return
        }
        val service = server.getService(serviceUuid) ?: return
        runCatching {
            server.removeService(service)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to remove shared GATT service $serviceUuid", throwable)
        }
    }

    private fun registerSharedGattDelegateInternal(delegate: SharedGattDelegate): Boolean {
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates[delegate.serviceUuid] = delegate
        }
        val added = addSharedGattDelegateService(delegate)
        if (!added) {
            synchronized(sharedGattDelegatesLock) {
                val current = sharedGattDelegates[delegate.serviceUuid]
                if (current === delegate) {
                    sharedGattDelegates.remove(delegate.serviceUuid)
                }
            }
        }
        return added
    }

    private fun unregisterSharedGattDelegateInternal(serviceUuid: UUID) {
        synchronized(sharedGattDelegatesLock) {
            sharedGattDelegates.remove(serviceUuid)
        }
        removeSharedGattDelegateService(serviceUuid)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectSharedClientsInternal(addresses: Collection<String>) {
        if (addresses.isEmpty()) {
            return
        }
        val server = gattServer ?: return
        val adapter = bluetoothAdapter ?: return
        if (!hasBluetoothConnectPermission()) {
            Log.w(TAG, "Cannot disconnect shared GATT clients without BLUETOOTH_CONNECT permission")
            return
        }
        val normalizedAddresses = addresses
            .asSequence()
            .map { it.trim().uppercase(Locale.US) }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        normalizedAddresses.forEach { address ->
            val device = runCatching {
                adapter.getRemoteDevice(address)
            }.getOrNull() ?: return@forEach
            runCatching {
                server.cancelConnection(device)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to cancel shared GATT connection for $address", throwable)
            }
        }
        if (normalizedAddresses.isNotEmpty()) {
            Log.d(
                TAG,
                "Requested shared GATT disconnect for ${normalizedAddresses.size} client(s): " +
                    normalizedAddresses.joinToString(",")
            )
        }
    }

    private fun currentGattServer(): BluetoothGattServer? = gattServer

    private fun startAdvertising(): Boolean {
        val advertiser = advertiser ?: run {
            Log.e(TAG, "Bluetooth LE Advertiser not available")
            publishStartupFailure(getString(R.string.rescue_error_bluetooth_unavailable))
            stopSelf()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val permissionState = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
            if (permissionState != PackageManager.PERMISSION_GRANTED) {
                Log.w(
                    TAG,
                    "Missing BLUETOOTH_ADVERTISE permission. Unable to start SOS advertising."
                )
                publishStartupFailure(getString(R.string.rescue_error_permission_required))
                stopSelf()
                return false
            }
        }

        val radioDecision = BleRadioPolicy.resolve(
            context = applicationContext,
            preferPerformance = true,
            hasActiveTransfer = sessionKeys.isNotEmpty(),
            connectedPeerCount = verifiedClients.size
        )
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(radioDecision.advertiseMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(radioDecision.advertiseTxPower)
            .build()

        val initiatorRank = resolveMeshInitiatorRankForAdvertising()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(crisisServiceUuid))
            .addManufacturerData(
                INITIATOR_RANK_MANUFACTURER_ID,
                encodeMeshInitiatorRank(initiatorRank)
            )
            .build()
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(ParcelUuid(crisisServiceUuid), rescueBroadcastServiceData)
            .build()

        try {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
            Log.d(TAG, "SOS Advertising started.")
        } catch (securityException: SecurityException) {
            Log.e(TAG, "Failed to start SOS advertising due to security exception", securityException)
            publishStartupFailure(
                getString(R.string.rescue_error_permission_required),
                securityException
            )
            stopSelf()
            return false
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start SOS advertising", exception)
            publishStartupFailure(
                getString(R.string.sos_error_advertise_failed),
                exception
            )
            stopSelf()
            return false
        }
        return true
    }

    @SuppressLint("HardwareIds")
    private fun resolveMeshInitiatorRankForAdvertising(): Int {
        val macSalt = if (hasBluetoothConnectPermission()) {
            runCatching { bluetoothAdapter?.address }
                .getOrNull()
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() && it != INVALID_MAC_ADDRESS }
                ?.hashCode()
        } else {
            null
        }
        val seed = macSalt ?: runCatching {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.hashCode()
            ?: packageName.hashCode()
        return seed xor (seed ushr 16) xor DEFAULT_INITIATOR_SALT
    }

    private fun encodeMeshInitiatorRank(rank: Int): ByteArray {
        return byteArrayOf(
            (rank ushr 24).toByte(),
            (rank ushr 16).toByte(),
            (rank ushr 8).toByte(),
            rank.toByte()
        )
    }

    private fun buildBroadcastIdPayload(): String {
        return "ccid:$rescueBroadcastId"
    }

    private fun createClientConfigDescriptor(): BluetoothGattDescriptor {
        return BluetoothGattDescriptor(
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        ).apply {
            value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
    }

    private fun handleAuthChallengeWrite(device: BluetoothDevice, value: ByteArray?): Int {
        if (value == null || value.isEmpty()) {
            Log.w(TAG, "[${device.address.uppercase(Locale.US)}] Empty auth challenge write")
            return BluetoothGatt.GATT_FAILURE
        }
        val normalizedAddress = device.address.uppercase(Locale.US)
        Log.d(TAG, "[$normalizedAddress] Auth challenge write received bytes=${value.size}")
        ackStates.remove(normalizedAddress)
        secureInReceivers[normalizedAddress]?.reset()
        secureChatReceivers[normalizedAddress]?.reset()
        return try {
            val clientPublicKey = decodeEcPublicKey(value)
            val serverKeys = Crypto.generateEphemeralEcKeyPair()
            val sessionKey = Crypto.deriveSessionKey(serverKeys.private, clientPublicKey)
            sessionKeys[normalizedAddress] = sessionKey
            val serverPublicKeyBytes = serverKeys.public.encoded
            serverPublicKeys[normalizedAddress] = serverPublicKeyBytes
            authSessionNonceByAddress[normalizedAddress] =
                buildSessionNonce(value, serverPublicKeyBytes)
            gattServer?.getService(crisisServiceUuid)?.getCharacteristic(authResponseCharacteristicUuid)?.let { characteristic ->
                characteristic.value = serverPublicKeyBytes
                notifyIfEnabled(device, characteristic)
            }
            Log.d(TAG, "[$normalizedAddress] Auth challenge accepted and response prepared")
            BluetoothGatt.GATT_SUCCESS
        } catch (exception: GeneralSecurityException) {
            Log.e(TAG, "Failed to derive session key", exception)
            sessionKeys.remove(normalizedAddress)
            serverPublicKeys.remove(normalizedAddress)
            authSessionNonceByAddress.remove(normalizedAddress)
            Log.w(TAG, "[$normalizedAddress] Auth challenge rejected due to key derivation failure")
            BluetoothGatt.GATT_FAILURE
        }
    }

    private fun handleSecureInWrite(device: BluetoothDevice, value: ByteArray?): Int {
        if (value == null || value.isEmpty()) {
            return BluetoothGatt.GATT_FAILURE
        }
        val normalizedAddress = device.address.uppercase(Locale.US)
        val sessionKey = sessionKeys[normalizedAddress] ?: return BluetoothGatt.GATT_FAILURE
        val expectedSessionNonce = authSessionNonceByAddress[normalizedAddress]
            ?: return BluetoothGatt.GATT_FAILURE
        val receiver = secureInReceivers.getOrPut(normalizedAddress) {
            BleChunkReceiver(MAX_ROLE_PROOF_PACKET_BYTES)
        }
        return try {
            val packet = when (val chunkResult = receiver.onChunk(value)) {
                BleChunkReceiver.ChunkResult.Incomplete -> return BluetoothGatt.GATT_SUCCESS
                is BleChunkReceiver.ChunkResult.Complete -> chunkResult.packet
                is BleChunkReceiver.ChunkResult.Rejected -> {
                    Log.w(TAG, "[$normalizedAddress] Rejecting secure handshake chunk reason=${chunkResult.reason}")
                    receiver.reset()
                    return BluetoothGatt.GATT_FAILURE
                }
            }
            when (
                val result = roleProofVerifier.verifyPacket(
                    keyBytes = sessionKey,
                    transportPacket = packet,
                    expectedSessionNonce = expectedSessionNonce
                )
            ) {
                is RoleProofVerificationResult.Success -> {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        val suffix = result.proof.devicePublicKey.takeLast(16)
                        Log.d(TAG, "[$normalizedAddress] Role proof accepted pubKeySuffix=$suffix")
                    }
                    receiver.reset()
                    authSessionNonceByAddress.remove(normalizedAddress)
                    verifiedClients.add(normalizedAddress)
                    val sessionCode = ensurePeerPresence(normalizedAddress)
                    sessionCodesByAddress[normalizedAddress] = sessionCode
                    sendAck(device, HANDSHAKE_ACK_PAYLOAD)
                    serviceScope.launch {
                        sendLocalIdentity(normalizedAddress)
                    }
                    BluetoothGatt.GATT_SUCCESS
                }
                is RoleProofVerificationResult.Failure -> {
                    Log.w(
                        TAG,
                        "[$normalizedAddress] Role proof rejected: ${result.reason}",
                        result.cause
                    )
                    receiver.reset()
                    verifiedClients.remove(normalizedAddress)
                    sessionKeys.remove(normalizedAddress)
                    serverPublicKeys.remove(normalizedAddress)
                    authSessionNonceByAddress.remove(normalizedAddress)
                    sessionCodesByAddress.remove(normalizedAddress)
                    ackStates.remove(normalizedAddress)
                    BluetoothGatt.GATT_FAILURE
                }
            }
        } catch (throwable: Throwable) {
            Log.e(TAG, "[$normalizedAddress] Failed processing secure payload", throwable)
            receiver.reset()
            sessionKeys.remove(normalizedAddress)
            serverPublicKeys.remove(normalizedAddress)
            authSessionNonceByAddress.remove(normalizedAddress)
            BluetoothGatt.GATT_FAILURE
        }
    }

    private fun handleSecureChatInWrite(device: BluetoothDevice, value: ByteArray?): Int {
        if (value == null || value.isEmpty()) {
            return BluetoothGatt.GATT_FAILURE
        }
        val normalizedAddress = device.address.uppercase(Locale.US)
        if (!verifiedClients.contains(normalizedAddress)) {
            Log.w(TAG, "Received secure chat before handshake from ${device.address}")
            secureChatReceivers[normalizedAddress]?.reset()
            return BluetoothGatt.GATT_FAILURE
        }

        val receiver = secureChatReceivers.getOrPut(normalizedAddress) {
            BleChunkReceiver(MAX_SECURE_CHAT_PACKET_BYTES, tag = "SecureChatReceiver-$normalizedAddress")
        }

        val packet = try {
            when (val chunkResult = receiver.onChunk(value)) {
                BleChunkReceiver.ChunkResult.Incomplete -> return BluetoothGatt.GATT_SUCCESS
                is BleChunkReceiver.ChunkResult.Complete -> chunkResult.packet
                is BleChunkReceiver.ChunkResult.Rejected -> {
                    Log.w(TAG, "[$normalizedAddress] Rejecting secure chat chunk reason=${chunkResult.reason}")
                    receiver.reset()
                    return BluetoothGatt.GATT_FAILURE
                }
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "[$normalizedAddress] Dropping malformed chat chunk", throwable)
            receiver.reset()
            return BluetoothGatt.GATT_FAILURE
        }

        return try {
            val sessionKey = sessionKeys[normalizedAddress] ?: return BluetoothGatt.GATT_FAILURE
            val transportPayload = BleDirectChatCompat.unwrapTransportPacket(
                packet = packet,
                maxPacketSize = MAX_SECURE_CHAT_PACKET_BYTES
            ) ?: return BluetoothGatt.GATT_FAILURE
            val message = runCatching {
                val plaintext = AesCipherHelper.decrypt(sessionKey, transportPayload)
                plaintext.toString(Charsets.UTF_8)
            }.getOrElse {
                BleDirectChatCompat.decodePayloadJson(
                    outerMessage = transportPayload.toString(Charsets.UTF_8),
                    keyBytes = sessionKey,
                    maxEncryptedPacketBytes = MAX_SECURE_CHAT_PACKET_BYTES
                ) ?: throw it
            }
            val status = handleSecureChatMessage(device, normalizedAddress, message)
            receiver.reset()
            status
        } catch (throwable: Throwable) {
            Log.e(TAG, "[$normalizedAddress] Failed to process secure chat payload", throwable)
            receiver.reset()
            BluetoothGatt.GATT_FAILURE
        }
    }

    private fun handleSecureChatMessage(
        device: BluetoothDevice,
        normalizedAddress: String,
        message: String
    ): Int {
        if (!verifiedClients.contains(normalizedAddress)) {
            Log.w(TAG, "Received secure message before handshake from ${device.address}")
            return BluetoothGatt.GATT_FAILURE
        }

        val text = message.trimEnd { it == '\u0000' }
        BlePeerIdentityUtils.parsePeerInfoPayload(text)?.let { identity ->
            applyPeerIdentity(normalizedAddress, identity)
            sendAck(device, PEER_INFO_ACK_PAYLOAD)
            return BluetoothGatt.GATT_SUCCESS
        }
        BleDirectChatCompat.parsePayload(text)?.let { payload ->
            return handleCompatChatPayload(
                device = device,
                normalizedAddress = normalizedAddress,
                payload = payload
            )
        }
        BleImagePayload.parsePacket(text)?.let { packet ->
            val sessionCode = ensurePeerPresence(
                normalizedAddress = normalizedAddress,
                preferredSessionCode = resolveSessionCodeForAddress(normalizedAddress)
            )
            sessionCodesByAddress[normalizedAddress] = sessionCode
            when (packet) {
                is BleImagePayload.Packet.Done -> {
                    BleImageTransferReceiptStore.publishSuccess(packet.transferId)
                }

                is BleImagePayload.Packet.Abort -> {
                    BleImageTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                }

                is BleImagePayload.Packet.Init,
                is BleImagePayload.Packet.Chunk -> {
                    handleIncomingImagePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
            }
            return BluetoothGatt.GATT_SUCCESS
        }
        BleFilePayload.parsePacket(text)?.let { packet ->
            val sessionCode = ensurePeerPresence(
                normalizedAddress = normalizedAddress,
                preferredSessionCode = resolveSessionCodeForAddress(normalizedAddress)
            )
            sessionCodesByAddress[normalizedAddress] = sessionCode
            when (packet) {
                is BleFilePayload.Packet.Done -> {
                    BleFileTransferReceiptStore.publishSuccess(packet.transferId)
                }

                is BleFilePayload.Packet.Abort -> {
                    BleFileTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                }

                is BleFilePayload.Packet.Init,
                is BleFilePayload.Packet.Chunk -> {
                    handleIncomingFilePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
            }
            return BluetoothGatt.GATT_SUCCESS
        }
        BleVoicePayload.parsePacket(text)?.let { packet ->
            val sessionCode = ensurePeerPresence(
                normalizedAddress = normalizedAddress,
                preferredSessionCode = resolveSessionCodeForAddress(normalizedAddress)
            )
            sessionCodesByAddress[normalizedAddress] = sessionCode
            when (packet) {
                is BleVoicePayload.Packet.Done -> {
                    BleVoiceTransferReceiptStore.publishSuccess(packet.transferId)
                }

                is BleVoicePayload.Packet.Abort -> {
                    BleVoiceTransferReceiptStore.publishAbort(packet.transferId, packet.reason)
                }

                is BleVoicePayload.Packet.Init,
                is BleVoicePayload.Packet.Chunk -> {
                    handleIncomingVoicePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
            }
            return BluetoothGatt.GATT_SUCCESS
        }
        val envelope = BleChatEnvelope.decodeChat(text)
        if (envelope != null) {
            val sessionCode = ensurePeerPresence(
                normalizedAddress = normalizedAddress,
                preferredSessionCode = resolveSessionCodeForIncomingEnvelope(
                    normalizedAddress = normalizedAddress,
                    route = envelope.route
                )
            )
            sessionCodesByAddress[normalizedAddress] = sessionCode
            val receivedAtMillis = System.currentTimeMillis()
            if (!BleChatEnvelope.isExpired(envelope, nowMillis = receivedAtMillis)) {
                BleChatStore.appendRemoteMessage(
                    sessionCode = sessionCode,
                    text = envelope.text,
                    messageId = envelope.messageId,
                    originalTimestampMillis = envelope.createdAtMillis,
                    receivedAtMillis = receivedAtMillis
                )
                serviceScope.launch(Dispatchers.IO) {
                    ensureContactExistsForSession(
                        sessionCode = sessionCode,
                        normalizedAddress = normalizedAddress
                    )
                    val inserted = runCatching {
                        saveRemoteMessage(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            uuid = envelope.messageId,
                            text = envelope.text,
                            createdAtMillis = envelope.createdAtMillis,
                            receivedAtMillis = receivedAtMillis
                        )
                    }.getOrElse { throwable ->
                        Log.w(TAG, "[$normalizedAddress] Failed to persist remote chat message", throwable)
                        false
                    }
                    if (inserted) {
                        val contactName = resolveIncomingContactName(normalizedAddress, sessionCode)
                        BleMessageNotifier.notifyIncoming(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            contactName = contactName,
                            body = envelope.text,
                            timestamp = receivedAtMillis
                        )
                    }
                }
            } else {
                Log.w(TAG, "[$normalizedAddress] Dropping expired chat envelope id=${envelope.messageId}")
            }
            sendAck(device, BleChatEnvelope.encodeDeliveredAck(envelope.messageId))
            return BluetoothGatt.GATT_SUCCESS
        }
        val sessionCode = ensurePeerPresence(normalizedAddress)
        sessionCodesByAddress[normalizedAddress] = sessionCode
        if (text.isNotEmpty()) {
            val messageId = UUID.randomUUID().toString()
            BleChatStore.appendRemoteMessage(sessionCode, text, messageId = messageId)
            serviceScope.launch(Dispatchers.IO) {
                ensureContactExistsForSession(
                    sessionCode = sessionCode,
                    normalizedAddress = normalizedAddress
                )
                runCatching {
                    saveRemoteMessage(applicationContext, sessionCode, messageId, text)
                }.onFailure { throwable ->
                    Log.w(TAG, "[$normalizedAddress] Failed to persist legacy remote chat message", throwable)
                }
            }
            val contactName = resolveIncomingContactName(normalizedAddress, sessionCode)
            BleMessageNotifier.notifyIncoming(
                context = applicationContext,
                sessionCode = sessionCode,
                contactName = contactName,
                body = text
            )
        }
        sendAck(device, BleChatEnvelope.encodeDeliveredAck(null))
        return BluetoothGatt.GATT_SUCCESS
    }

    private fun handleCompatChatPayload(
        device: BluetoothDevice,
        normalizedAddress: String,
        payload: BleDirectChatCompat.Payload
    ): Int {
        val sessionCode = ensurePeerPresence(
            normalizedAddress = normalizedAddress,
            preferredSessionCode = resolveSessionCodeForAddress(normalizedAddress),
            preferredName = payload.senderName
        )
        sessionCodesByAddress[normalizedAddress] = sessionCode
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_DELIVERED -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                if (messageId == null) {
                    BleChatStore.markDeliveredForAllSent(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllSentMessagesDelivered(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markDelivered(sessionCode, listOf(messageId))
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesDelivered(applicationContext, sessionCode, listOf(messageId))
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            P2pBleProtocol.CHAT_KIND_READ -> {
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                if (messageId == null) {
                    BleChatStore.markReadAllLocal(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllLocalMessagesRead(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markRead(sessionCode, listOf(messageId))
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesRead(applicationContext, sessionCode, listOf(messageId))
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            P2pBleProtocol.CHAT_KIND_TEXT -> {
                val body = payload.text?.trim().takeIf { !it.isNullOrBlank() } ?: return BluetoothGatt.GATT_SUCCESS
                val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                    ?: UUID.randomUUID().toString()
                val receivedAtMillis = System.currentTimeMillis()
                BleChatStore.appendRemoteMessage(
                    sessionCode = sessionCode,
                    text = body,
                    messageId = messageId,
                    receivedAtMillis = receivedAtMillis
                )
                serviceScope.launch(Dispatchers.IO) {
                    ensureContactExistsForSession(
                        sessionCode = sessionCode,
                        normalizedAddress = normalizedAddress
                    )
                    val inserted = runCatching {
                        saveRemoteMessage(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            uuid = messageId,
                            text = body,
                            receivedAtMillis = receivedAtMillis,
                            senderDisplayName = payload.senderName,
                            senderAddress = normalizedAddress
                        )
                    }.getOrElse { throwable ->
                        Log.w(TAG, "[$normalizedAddress] Failed to persist compat chat message", throwable)
                        false
                    }
                    if (inserted) {
                        val contactName = resolveIncomingContactName(normalizedAddress, sessionCode)
                        BleMessageNotifier.notifyIncoming(
                            context = applicationContext,
                            sessionCode = sessionCode,
                            contactName = contactName,
                            body = body,
                            timestamp = receivedAtMillis
                        )
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingVoiceTransfers) {
                        incomingVoiceTransfers.remove(voiceTransferKey(normalizedAddress, transferId))
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            BleDirectChatCompat.CHAT_KIND_IMAGE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingImageTransfers) {
                        incomingImageTransfers.remove(imageTransferKey(normalizedAddress, transferId))
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            BleDirectChatCompat.CHAT_KIND_FILE_ABORT -> {
                payload.messageId?.let { transferId ->
                    synchronized(incomingFileTransfers) {
                        incomingFileTransfers.remove(fileTransferKey(normalizedAddress, transferId))
                    }
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            P2pBleProtocol.CHAT_KIND_VOICE_INIT,
            P2pBleProtocol.CHAT_KIND_VOICE_CHUNK,
            P2pBleProtocol.CHAT_KIND_VOICE_DONE -> {
                BleDirectChatCompat.toIncomingVoicePacket(payload)?.let { packet ->
                    handleIncomingVoicePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            BleDirectChatCompat.CHAT_KIND_IMAGE_INIT,
            BleDirectChatCompat.CHAT_KIND_IMAGE_CHUNK,
            BleDirectChatCompat.CHAT_KIND_IMAGE_DONE -> {
                BleDirectChatCompat.toIncomingImagePacket(payload)?.let { packet ->
                    handleIncomingImagePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
                return BluetoothGatt.GATT_SUCCESS
            }

            BleDirectChatCompat.CHAT_KIND_FILE_INIT,
            BleDirectChatCompat.CHAT_KIND_FILE_CHUNK,
            BleDirectChatCompat.CHAT_KIND_FILE_DONE -> {
                BleDirectChatCompat.toIncomingFilePacket(payload)?.let { packet ->
                    handleIncomingFilePacket(
                        normalizedAddress = normalizedAddress,
                        sessionCode = sessionCode,
                        packet = packet
                    )
                }
                return BluetoothGatt.GATT_SUCCESS
            }
        }
        sendAck(device, BleChatEnvelope.encodeDeliveredAck(null))
        return BluetoothGatt.GATT_SUCCESS
    }

    private fun handleIncomingVoicePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleVoicePayload.Packet
    ) {
        cleanupStaleIncomingVoiceTransfers()
        when (packet) {
            is BleVoicePayload.Packet.Init -> {
                val key = voiceTransferKey(normalizedAddress, packet.transferId)
                incomingVoiceTransfers[key] = BleVoicePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    mimeType = packet.mimeType,
                    durationMillis = packet.durationMillis,
                    totalChunks = packet.totalChunks
                )
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = packet.transferId,
                    totalChunks = packet.totalChunks,
                    confirmedChunks = 0,
                    state = VoiceTransferState.Initializing
                )
            }

            is BleVoicePayload.Packet.Chunk -> {
                val key = voiceTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingVoiceTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    incomingVoiceTransfers.remove(key)
                    return
                }
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = VoiceTransferState.Transferring
                )
                if (!transfer.isComplete()) {
                    return
                }
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Verifying
                )
                val fileBytes = transfer.composeBytes()
                incomingVoiceTransfers.remove(key)
                if (fileBytes == null) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (fileBytes.isEmpty()) {
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "empty_payload")
                    return
                }
                if (fileBytes.size > BleVoicePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    Log.w(TAG, "[$normalizedAddress] Dropping oversized voice packet size=${fileBytes.size}")
                    publishIncomingVoiceProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = VoiceTransferState.Failed
                    )
                    clearIncomingVoiceProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                persistRemoteVoiceMessage(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = fileBytes
                )
            }

            is BleVoicePayload.Packet.Done,
            is BleVoicePayload.Packet.Abort -> Unit
        }
    }

    private fun handleIncomingFilePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleFilePayload.Packet
    ) {
        cleanupStaleIncomingFileTransfers()
        when (packet) {
            is BleFilePayload.Packet.Init -> {
                val key = fileTransferKey(normalizedAddress, packet.transferId)
                incomingFileTransfers[key] = BleFilePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    messageId = packet.messageId,
                    displayName = packet.displayName,
                    mimeType = packet.mimeType,
                    originalSizeBytes = packet.originalSizeBytes,
                    totalBytes = packet.totalBytes,
                    totalChunks = packet.totalChunks,
                    sha256 = packet.sha256
                )
            }

            is BleFilePayload.Packet.Chunk -> {
                val key = fileTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingFileTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    incomingFileTransfers.remove(key)
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    return
                }
                if (!transfer.isComplete()) {
                    return
                }
                val fileBytes = transfer.composeBytes()
                incomingFileTransfers.remove(key)
                if (fileBytes == null || fileBytes.isEmpty()) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (fileBytes.size > BleFilePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(fileBytes)
                if (!digest.contentEquals(transfer.sha256)) {
                    sendFileAbortPacket(normalizedAddress, transfer.transferId, "sha_mismatch")
                    return
                }
                persistRemoteFileTransfer(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = fileBytes
                )
            }

            is BleFilePayload.Packet.Done,
            is BleFilePayload.Packet.Abort -> Unit
        }
    }

    private fun persistRemoteVoiceMessage(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleVoicePayload.IncomingTransfer,
        bytes: ByteArray
    ) {
        val messageId = UUID.randomUUID().toString()
        val fileName = voiceMessageFileName(messageId, transfer.mimeType)
        val duration = transfer.durationMillis.takeIf { it > 0L }
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                ensureContactExistsForSession(
                    sessionCode = sessionCode,
                    normalizedAddress = normalizedAddress
                )
                val destination = voiceMessageFile(applicationContext, fileName)
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
                saveRemoteAudioMessage(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    uuid = messageId,
                    fileName = destination.name,
                    audioDurationMillis = duration
                )
                BleChatStore.appendRemoteAudioMessage(
                    sessionCode = sessionCode,
                    audioFilePath = destination.absolutePath,
                    audioDurationMillis = duration,
                    messageId = messageId
                )
                val contactName = resolveIncomingContactName(normalizedAddress, sessionCode)
                BleMessageNotifier.notifyIncoming(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    contactName = contactName,
                    body = getString(R.string.notification_voice_message_body)
                )
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Completed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    delayMs = VOICE_COMPLETED_BADGE_MS
                )
                sendVoiceDonePacket(normalizedAddress, transfer.transferId)
            }.onFailure { throwable ->
                Log.w(TAG, "[$normalizedAddress] Failed to persist incoming voice packet", throwable)
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = VoiceTransferState.Failed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendVoiceAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun sendVoiceDonePacket(normalizedAddress: String, transferId: String) {
        val packet = BleVoicePayload.buildDonePacket(transferId)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun sendVoiceAbortPacket(normalizedAddress: String, transferId: String, reason: String) {
        val packet = BleVoicePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun persistRemoteFileTransfer(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleFilePayload.IncomingTransfer,
        bytes: ByteArray
    ) {
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                ensureContactExistsForSession(
                    sessionCode = sessionCode,
                    normalizedAddress = normalizedAddress
                )
                val persisted = persistSharedDocumentLocalCopy(
                    context = applicationContext,
                    uuid = transfer.messageId,
                    displayName = transfer.displayName,
                    bytes = bytes
                )
                check(!persisted.isNullOrBlank()) { "persist_failed" }
                sendFileDonePacket(normalizedAddress, transfer.transferId)
            }.onFailure { throwable ->
                Log.w(TAG, "[$normalizedAddress] Failed to persist incoming file packet", throwable)
                sendFileAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun sendFileDonePacket(normalizedAddress: String, transferId: String) {
        val packet = BleFilePayload.buildDonePacket(transferId)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun sendFileAbortPacket(normalizedAddress: String, transferId: String, reason: String) {
        val packet = BleFilePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun voiceTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun fileTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun publishIncomingVoiceProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: VoiceTransferState
    ) {
        BleVoiceTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = VoiceTransferDirection.Download,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun clearIncomingVoiceProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long = VOICE_FAILED_BADGE_MS
    ) {
        serviceScope.launch {
            delay(delayMs)
            BleVoiceTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = VoiceTransferDirection.Download
            )
        }
    }

    private fun clearVoiceTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        val sessionCode = sessionCodesByAddress[normalizedAddress] ?: "ble:$normalizedAddress"
        val removedTransferIds = mutableListOf<String>()
        synchronized(incomingVoiceTransfers) {
            val iterator = incomingVoiceTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    removedTransferIds += entry.value.transferId
                    iterator.remove()
                }
            }
        }
        removedTransferIds.forEach { transferId ->
            BleVoiceTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = VoiceTransferDirection.Download
            )
        }
    }

    private fun cleanupStaleIncomingVoiceTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleVoicePayload.IncomingTransfer>>()
        synchronized(incomingVoiceTransfers) {
            val iterator = incomingVoiceTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > VOICE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                val sessionCode = sessionCodesByAddress[address] ?: "ble:$address"
                publishIncomingVoiceProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = VoiceTransferState.Failed
                )
                clearIncomingVoiceProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendVoiceAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun clearFileTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        synchronized(incomingFileTransfers) {
            val iterator = incomingFileTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    iterator.remove()
                }
            }
        }
    }

    private fun cleanupStaleIncomingFileTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleFilePayload.IncomingTransfer>>()
        synchronized(incomingFileTransfers) {
            val iterator = incomingFileTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > FILE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                sendFileAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun handleIncomingImagePacket(
        normalizedAddress: String,
        sessionCode: String,
        packet: BleImagePayload.Packet
    ) {
        cleanupStaleIncomingImageTransfers()
        when (packet) {
            is BleImagePayload.Packet.Init -> {
                val key = imageTransferKey(normalizedAddress, packet.transferId)
                incomingImageTransfers[key] = BleImagePayload.IncomingTransfer(
                    transferId = packet.transferId,
                    messageId = packet.messageId,
                    mimeType = packet.mimeType,
                    width = packet.width,
                    height = packet.height,
                    totalBytes = packet.totalBytes,
                    totalChunks = packet.totalChunks,
                    sha256 = packet.sha256
                )
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = packet.transferId,
                    totalChunks = packet.totalChunks,
                    confirmedChunks = 0,
                    state = ImageTransferState.Initializing
                )
            }

            is BleImagePayload.Packet.Chunk -> {
                val key = imageTransferKey(normalizedAddress, packet.transferId)
                val transfer = incomingImageTransfers[key] ?: return
                val added = transfer.addChunk(packet.chunkIndex, packet.bytes)
                if (!added) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "invalid_chunk")
                    incomingImageTransfers.remove(key)
                    return
                }
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = ImageTransferState.Transferring
                )
                if (!transfer.isComplete()) {
                    return
                }
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Verifying
                )
                val fileBytes = transfer.composeBytes()
                incomingImageTransfers.remove(key)
                if (fileBytes == null || fileBytes.isEmpty()) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.chunks.size,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "compose_failed")
                    return
                }
                if (fileBytes.size > BleImagePayload.MAX_OUTGOING_TOTAL_BYTES) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "payload_too_large")
                    return
                }
                val digest = MessageDigest.getInstance("SHA-256").digest(fileBytes)
                if (!digest.contentEquals(transfer.sha256)) {
                    publishIncomingImageProgress(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId,
                        totalChunks = transfer.totalChunks,
                        confirmedChunks = transfer.totalChunks,
                        state = ImageTransferState.Failed
                    )
                    clearIncomingImageProgressLater(
                        sessionCode = sessionCode,
                        transferId = transfer.transferId
                    )
                    sendImageAbortPacket(normalizedAddress, transfer.transferId, "sha_mismatch")
                    return
                }
                persistRemoteImageMessage(
                    normalizedAddress = normalizedAddress,
                    sessionCode = sessionCode,
                    transfer = transfer,
                    bytes = fileBytes
                )
            }

            is BleImagePayload.Packet.Done,
            is BleImagePayload.Packet.Abort -> Unit
        }
    }

    private fun persistRemoteImageMessage(
        normalizedAddress: String,
        sessionCode: String,
        transfer: BleImagePayload.IncomingTransfer,
        bytes: ByteArray
    ) {
        val messageId = transfer.messageId.ifBlank { UUID.randomUUID().toString() }
        val fileName = ImageFileUtils.fileNameFor(messageId, transfer.mimeType)
        val thumbnailName = ImageFileUtils.thumbnailNameFor(messageId, transfer.mimeType)
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                ensureContactExistsForSession(
                    sessionCode = sessionCode,
                    normalizedAddress = normalizedAddress
                )
                val destination = imageMessageFile(applicationContext, fileName)
                val thumbnail = imageThumbnailFile(applicationContext, thumbnailName)
                destination.parentFile?.mkdirs()
                destination.writeBytes(bytes)
                val thumbnailCreated = generateImageThumbnail(
                    source = destination,
                    target = thumbnail,
                    mimeType = transfer.mimeType
                )
                saveRemoteImageMessage(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    uuid = messageId,
                    fileName = destination.name,
                    thumbnailName = if (thumbnailCreated) thumbnail.name else null,
                    width = transfer.width,
                    height = transfer.height,
                    mimeType = transfer.mimeType
                )
                BleChatStore.appendRemoteImageMessage(
                    sessionCode = sessionCode,
                    imageFilePath = destination.absolutePath,
                    imageThumbnailPath = if (thumbnailCreated) thumbnail.absolutePath else null,
                    imageWidth = transfer.width,
                    imageHeight = transfer.height,
                    imageMimeType = transfer.mimeType,
                    messageId = messageId
                )
                val contactName = resolveIncomingContactName(normalizedAddress, sessionCode)
                BleMessageNotifier.notifyIncoming(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    contactName = contactName,
                    body = getString(R.string.notification_photo_message_body)
                )
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Completed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    delayMs = IMAGE_COMPLETED_BADGE_MS
                )
                sendImageDonePacket(normalizedAddress, transfer.transferId)
            }.onFailure { throwable ->
                Log.w(TAG, "[$normalizedAddress] Failed to persist incoming image packet", throwable)
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.totalChunks,
                    state = ImageTransferState.Failed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendImageAbortPacket(normalizedAddress, transfer.transferId, "persist_failed")
            }
        }
    }

    private fun sendImageDonePacket(normalizedAddress: String, transferId: String) {
        val packet = BleImagePayload.buildDonePacket(transferId)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun sendImageAbortPacket(normalizedAddress: String, transferId: String, reason: String) {
        val packet = BleImagePayload.buildAbortPacket(transferId, reason)
        if (packet.isBlank()) return
        notifyChat(normalizedAddress, packet)
    }

    private fun imageTransferKey(normalizedAddress: String, transferId: String): String {
        return "$normalizedAddress|$transferId"
    }

    private fun publishIncomingImageProgress(
        sessionCode: String,
        transferId: String,
        totalChunks: Int,
        confirmedChunks: Int,
        state: ImageTransferState
    ) {
        BleImageTransferProgressStore.update(
            sessionCode = sessionCode,
            transferId = transferId,
            direction = ImageTransferDirection.Download,
            totalChunks = totalChunks,
            confirmedChunks = confirmedChunks,
            state = state
        )
    }

    private fun clearIncomingImageProgressLater(
        sessionCode: String,
        transferId: String,
        delayMs: Long = IMAGE_FAILED_BADGE_MS
    ) {
        serviceScope.launch {
            delay(delayMs)
            BleImageTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = ImageTransferDirection.Download
            )
        }
    }

    private fun clearImageTransfersForAddress(normalizedAddress: String) {
        val prefix = "$normalizedAddress|"
        val sessionCode = sessionCodesByAddress[normalizedAddress] ?: "ble:$normalizedAddress"
        val removedTransferIds = mutableListOf<String>()
        synchronized(incomingImageTransfers) {
            val iterator = incomingImageTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.startsWith(prefix)) {
                    removedTransferIds += entry.value.transferId
                    iterator.remove()
                }
            }
        }
        removedTransferIds.forEach { transferId ->
            BleImageTransferProgressStore.remove(
                sessionCode = sessionCode,
                transferId = transferId,
                direction = ImageTransferDirection.Download
            )
        }
    }

    private fun cleanupStaleIncomingImageTransfers() {
        val now = System.currentTimeMillis()
        val stale = mutableListOf<Pair<String, BleImagePayload.IncomingTransfer>>()
        synchronized(incomingImageTransfers) {
            val iterator = incomingImageTransfers.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value.createdAtMillis > IMAGE_TRANSFER_TTL_MS) {
                    stale += entry.key to entry.value
                    iterator.remove()
                }
            }
        }
        stale.forEach { (key, transfer) ->
            val address = key.substringBefore('|', missingDelimiterValue = "")
            if (address.isNotBlank()) {
                val sessionCode = sessionCodesByAddress[address] ?: "ble:$address"
                publishIncomingImageProgress(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId,
                    totalChunks = transfer.totalChunks,
                    confirmedChunks = transfer.chunks.size,
                    state = ImageTransferState.Failed
                )
                clearIncomingImageProgressLater(
                    sessionCode = sessionCode,
                    transferId = transfer.transferId
                )
                sendImageAbortPacket(address, transfer.transferId, "transfer_timeout")
            }
        }
    }

    private fun decodeEcPublicKey(encoded: ByteArray): PublicKey {
        require(encoded.isNotEmpty()) { "Client public key missing" }
        val spec = X509EncodedKeySpec(encoded)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun buildSessionNonce(clientPublicKey: ByteArray, serverPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clientPublicKey + serverPublicKey)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private fun handleAckWrite(device: BluetoothDevice, value: ByteArray?): Int {
        val normalizedAddress = device.address.uppercase(Locale.US)
        if (value == null || value.isEmpty()) {
            return BluetoothGatt.GATT_FAILURE
        }
        val sessionCode = sessionCodesByAddress[normalizedAddress]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BleSessionResolver.sessionCodeForAddress(normalizedAddress)
        val ack = BleChatEnvelope.decodeAck(value)
        if (ack == null) {
            return BluetoothGatt.GATT_SUCCESS
        }
        when (ack.type) {
            BleChatEnvelope.AckType.DELIVERED -> {
                if (ack.messageIds.isEmpty()) {
                    BleChatStore.markDeliveredForAllSent(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllSentMessagesDelivered(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markDelivered(sessionCode, ack.messageIds)
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesDelivered(applicationContext, sessionCode, ack.messageIds)
                    }
                }
            }

            BleChatEnvelope.AckType.READ -> {
                if (ack.messageIds.isEmpty()) {
                    BleChatStore.markReadAllLocal(sessionCode)
                    serviceScope.launch(Dispatchers.IO) {
                        markAllLocalMessagesRead(applicationContext, sessionCode)
                    }
                } else {
                    BleChatStore.markRead(sessionCode, ack.messageIds)
                    serviceScope.launch(Dispatchers.IO) {
                        markLocalMessagesRead(applicationContext, sessionCode, ack.messageIds)
                    }
                }
            }

            BleChatEnvelope.AckType.DECRYPT_FAIL -> { /* Not applicable to rescuer SOS GATT */ }
        }
        return BluetoothGatt.GATT_SUCCESS
    }

    private fun ensurePeerPresence(
        normalizedAddress: String,
        preferredSessionCode: String? = null,
        preferredName: String? = null,
        roleValue: String = BlePeerIdentityUtils.ROLE_VICTIM
    ): String {
        val sessionCode = preferredSessionCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: resolveSessionCodeForAddress(normalizedAddress)
        sessionCodesByAddress[normalizedAddress] = sessionCode
        val existing = BlePeerStore.peers.value[normalizedAddress]
        val resolvedName = resolvePeerName(
            preferredName = preferredName,
            currentName = existing?.name,
            roleValue = roleValue,
            normalizedAddress = normalizedAddress
        )
        val resolved = existing?.let { current ->
            val updated = current.copy(
                name = resolvedName,
                sessionCode = sessionCode,
                verified = current.verified || verifiedClients.contains(normalizedAddress),
                address = normalizedAddress
            )
            if (updated != current) {
                BlePeerStore.upsert(updated)
            }
            updated
        } ?: Contact(
            name = resolvedName,
            aesKey = "",
            sessionCode = sessionCode,
            verified = verifiedClients.contains(normalizedAddress),
            address = normalizedAddress
        ).also { contact ->
            BlePeerStore.upsert(contact)
        }

        BleChatStore.ensureSession(sessionCode)

        serviceScope.launch {
            val saved = getContact(applicationContext, sessionCode)
            val persistedName = resolvePeerName(
                preferredName = preferredName,
                currentName = saved?.name,
                roleValue = roleValue,
                normalizedAddress = normalizedAddress
            )
            val stored = saved?.copy(
                name = persistedName,
                verified = saved.verified || verifiedClients.contains(normalizedAddress),
                address = normalizedAddress
            ) ?: resolved.copy(
                name = persistedName,
                verified = resolved.verified || verifiedClients.contains(normalizedAddress)
            )
            if (saved == null || saved != stored) {
                saveContact(applicationContext, stored)
            }
            if (stored != resolved) {
                BlePeerStore.upsert(stored)
            }
        }

        return sessionCode
    }

    private fun resolveSessionCodeForIncomingEnvelope(
        normalizedAddress: String,
        route: String
    ): String {
        return if (route.equals(OUTBOUND_ROUTE_BLE_GATT_FALLBACK, ignoreCase = true)) {
            resolveFallbackSessionCodeForAddress(normalizedAddress)
        } else {
            BleSessionResolver.sessionCodeForAddress(normalizedAddress)
        }
    }

    private fun resolveFallbackSessionCodeForAddress(normalizedAddress: String): String {
        val mapped = sessionCodesByAddress[normalizedAddress]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (mapped != null && !mapped.startsWith("ble:", ignoreCase = true)) {
            return mapped
        }
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
        return mapped ?: BleSessionResolver.sessionCodeForAddress(normalizedAddress)
    }

    private fun resolveSessionCodeForAddress(normalizedAddress: String): String {
        val mapped = sessionCodesByAddress[normalizedAddress]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (mapped != null) {
            return mapped
        }
        return BleSessionResolver.sessionCodeForAddress(normalizedAddress)
    }

    private fun resolvePeerName(
        preferredName: String?,
        currentName: String?,
        roleValue: String,
        normalizedAddress: String
    ): String {
        val sessionCode = "ble:$normalizedAddress"
        val safeRoleValue = roleValue.ifBlank { BlePeerIdentityUtils.ROLE_VICTIM }
        val preferred = preferredName?.trim().orEmpty()
        if (preferred.isNotEmpty()) {
            return BlePeerIdentityUtils.buildLabeledName(
                rawName = preferred,
                roleValue = safeRoleValue,
                sessionCode = sessionCode,
                context = applicationContext
            )
        }

        val current = currentName?.trim().orEmpty()
        if (current.isNotEmpty() && !BlePeerIdentityUtils.looksLikeBleIdentifier(current, sessionCode)) {
            return current
        }

        return BlePeerIdentityUtils.defaultRoleName(safeRoleValue, applicationContext)
    }

    private fun applyPeerIdentity(normalizedAddress: String, identity: BlePeerIdentityUtils.PeerIdentity) {
        val roleValue = BlePeerIdentityUtils.displayRoleValue(
            claimedRoleValue = identity.role,
            trustClaimedRole = true
        )
        val displayName = BlePeerIdentityUtils.buildLabeledName(
            rawName = identity.name,
            roleValue = roleValue,
            sessionCode = "ble:$normalizedAddress",
            context = applicationContext
        )
        val sessionCode = ensurePeerPresence(
            normalizedAddress = normalizedAddress,
            preferredSessionCode = resolveSessionCodeForAddress(normalizedAddress),
            preferredName = displayName,
            roleValue = roleValue
        )
        sessionCodesByAddress[normalizedAddress] = sessionCode
        identity.avatarBase64?.let { avatarPayload ->
            serviceScope.launch {
                ContactAvatarStorage.saveRemoteAvatarPayload(
                    context = applicationContext,
                    sessionCode = sessionCode,
                    payloadBase64 = avatarPayload
                )
            }
        }
    }

    private fun resolveIncomingContactName(
        normalizedAddress: String,
        sessionCode: String,
        fallbackRoleValue: String = BlePeerIdentityUtils.ROLE_VICTIM
    ): String {
        val contactName = BlePeerStore.peers.value[normalizedAddress]?.name?.trim().orEmpty()
        return if (contactName.isNotBlank() && !BlePeerIdentityUtils.looksLikeBleIdentifier(contactName, sessionCode)) {
            contactName
        } else {
            BlePeerIdentityUtils.defaultRoleName(fallbackRoleValue, applicationContext)
        }
    }

    private suspend fun ensureContactExistsForSession(
        sessionCode: String,
        normalizedAddress: String
    ) {
        val existing = getContact(applicationContext, sessionCode)
        val desiredName = resolveIncomingContactName(normalizedAddress, sessionCode)
        val normalizedExistingAddress = existing?.address?.let(::normalizeMacAddress)
        val contact = if (existing != null) {
            val stableName = existing.name
                .trim()
                .takeIf { it.isNotEmpty() && !BlePeerIdentityUtils.looksLikeBleIdentifier(it, sessionCode) }
                ?: desiredName
            existing.copy(
                name = stableName,
                verified = existing.verified || verifiedClients.contains(normalizedAddress),
                address = normalizedAddress
            )
        } else {
            Contact(
                name = desiredName,
                aesKey = "",
                sessionCode = sessionCode,
                verified = verifiedClients.contains(normalizedAddress),
                address = normalizedAddress
            )
        }
        if (
            existing == null ||
            existing != contact ||
            normalizedExistingAddress != normalizedAddress
        ) {
            saveContact(applicationContext, contact)
        }
        BlePeerStore.upsert(contact)
    }

    private suspend fun sendLocalIdentity(normalizedAddress: String) {
        val batteryPct = currentBatteryPercent()
        // In SOS mode, the broadcaster must be presented as a victim regardless of account privileges.
        val roleValue = BlePeerIdentityUtils.ROLE_VICTIM
        val localName = localUserName.ifBlank {
            BlePeerIdentityUtils.roleLabel(roleValue, applicationContext)
        }
        val avatarPayload = ContactAvatarStorage.localProfileAvatarPayload(applicationContext)
        val victimLocation = resolveVictimLocationSnapshot()
        val payload = BlePeerIdentityUtils.buildPeerInfoPayload(
            name = localName,
            role = roleValue,
            batteryPercent = batteryPct,
            avatarBase64 = avatarPayload,
            location = victimLocation
        )
        val sent = notifyChat(normalizedAddress, payload)
        if (!sent) {
            Log.w(TAG, "[$normalizedAddress] Failed to send peer identity packet")
        }
    }

    private fun currentBatteryPercent(): Int? {
        val sticky = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    private suspend fun resolveVictimLocationSnapshot(): BlePeerIdentityUtils.PeerLocationSnapshot? {
        val now = System.currentTimeMillis()
        val bestLastKnown = readBestVictimLocationSnapshot(now)
        if (bestLastKnown != null &&
            (now - bestLastKnown.capturedAtMillis).coerceAtLeast(0L) <= SOS_LOCATION_MAX_LAST_KNOWN_AGE_MS
        ) {
            return bestLastKnown
        }
        return requestFreshVictimLocationSnapshot(now) ?: bestLastKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshVictimLocationSnapshot(
        now: Long
    ): BlePeerIdentityUtils.PeerLocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val providers = buildList {
            val gpsEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false)
            val networkEnabled = runCatching {
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
            if (gpsEnabled) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (networkEnabled) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }
        if (providers.isEmpty()) {
            return null
        }

        var bestFix: Location? = null
        providers.forEach { provider ->
            val timeoutMs = if (provider == LocationManager.GPS_PROVIDER) {
                SOS_LOCATION_GPS_FIX_TIMEOUT_MS
            } else {
                SOS_LOCATION_NETWORK_FIX_TIMEOUT_MS
            }
            val candidate = requestSingleLocationFix(
                locationManager = locationManager,
                provider = provider,
                timeoutMs = timeoutMs
            )
            if (candidate == null || !isPlausibleLocation(candidate)) {
                return@forEach
            }
            val currentBest = bestFix
            if (currentBest == null || locationScore(now, candidate) < locationScore(now, currentBest)) {
                bestFix = candidate
            }
        }
        return bestFix?.let { location ->
            toPeerLocationSnapshot(location = location, now = now, source = location.provider ?: "gps")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleLocationFix(
        locationManager: LocationManager,
        provider: String,
        timeoutMs: Long
    ): Location? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        return withTimeoutOrNull(timeoutMs) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellationSignal = CancellationSignal()
                    continuation.invokeOnCancellation {
                        cancellationSignal.cancel()
                    }
                    runCatching {
                        locationManager.getCurrentLocation(
                            provider,
                            cancellationSignal,
                            ContextCompat.getMainExecutor(this@GattSOSServerService)
                        ) { location ->
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }
                    }.onFailure {
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            } else {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            runCatching {
                                locationManager.removeUpdates(this)
                            }
                            if (continuation.isActive) {
                                continuation.resume(location)
                            }
                        }

                        override fun onProviderDisabled(provider: String) {
                            runCatching {
                                locationManager.removeUpdates(this)
                            }
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?
                        ) = Unit
                    }
                    continuation.invokeOnCancellation {
                        runCatching {
                            locationManager.removeUpdates(listener)
                        }
                    }
                    runCatching {
                        locationManager.requestLocationUpdates(
                            provider,
                            0L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }.onFailure {
                        runCatching {
                            locationManager.removeUpdates(listener)
                        }
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun readBestVictimLocationSnapshot(
        now: Long
    ): BlePeerIdentityUtils.PeerLocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { isPlausibleLocation(it) }

        val best = candidates.minByOrNull { location -> locationScore(now, location) } ?: return null
        val source = "last_known_${best.provider ?: "unknown"}"
        return toPeerLocationSnapshot(location = best, now = now, source = source)
    }

    private fun toPeerLocationSnapshot(
        location: Location,
        now: Long,
        source: String
    ): BlePeerIdentityUtils.PeerLocationSnapshot {
        return BlePeerIdentityUtils.PeerLocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { it.isFinite() && it > 0f },
            capturedAtMillis = location.time.takeIf { it > 0L } ?: now,
            source = source
        )
    }

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPlausibleLocation(location: Location): Boolean {
        return location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0
    }

    private fun locationScore(now: Long, location: Location): Double {
        val locationTime = location.time.takeIf { it > 0L } ?: now
        val ageMillis = (now - locationTime).coerceAtLeast(0L).toDouble()
        val accuracyPenalty = location.accuracy
            .takeIf { it.isFinite() && it > 0f }
            ?.toDouble()
            ?: 250.0
        return ageMillis + (accuracyPenalty * 120.0)
    }

    private fun sendAck(device: BluetoothDevice, payload: ByteArray) {
        val normalizedAddress = device.address.uppercase(Locale.US)
        ackStates[normalizedAddress] = payload
        gattServer?.getService(crisisServiceUuid)?.getCharacteristic(secureAckCharacteristicUuid)?.let { characteristic ->
            characteristic.value = payload
            notifyIfEnabled(device, characteristic)
        }
    }

    fun sendReadReceipt(addressUpper: String, messageIds: Collection<String>): Boolean {
        val normalizedAddress = addressUpper.uppercase(Locale.US)
        val targetDevice = findConnectedGattDevice(normalizedAddress) ?: return false
        sendAck(targetDevice, BleChatEnvelope.encodeReadAck(messageIds))
        return true
    }

    fun registerSessionAlias(addressUpper: String, sessionCode: String) {
        val normalizedAddress = addressUpper.trim().uppercase(Locale.US)
        val normalizedSessionCode = sessionCode.trim()
        if (normalizedAddress.isBlank() || normalizedSessionCode.isBlank()) {
            return
        }
        sessionCodesByAddress[normalizedAddress] = normalizedSessionCode
    }

    fun isChatReady(addressUpper: String): Boolean {
        val normalizedAddress = addressUpper.trim().uppercase(Locale.US)
        if (normalizedAddress.isBlank()) {
            return false
        }
        if (!verifiedClients.contains(normalizedAddress)) {
            return false
        }
        if (!sessionKeys.containsKey(normalizedAddress)) {
            return false
        }
        val service = gattServer?.getService(crisisServiceUuid) ?: return false
        val characteristic = service.getCharacteristic(secureChatOutCharacteristicUuid) ?: return false
        val targetDevice = findConnectedGattDevice(normalizedAddress) ?: return false
        return isNotificationEnabled(targetDevice.address.uppercase(Locale.US), characteristic.uuid)
    }

    private fun notifyChat(addressUpper: String, text: String): Boolean {
        val normalizedAddress = addressUpper.uppercase(Locale.US)
        val sessionKey = sessionKeys[normalizedAddress] ?: return false
        val payload = text.toByteArray(Charsets.UTF_8)
        val encrypted = AesCipherHelper.encrypt(sessionKey, payload)
        val transportPacket = AesCipherHelper.wrapForTransport(encrypted)
        chatOutPayloads[normalizedAddress] = transportPacket
        val service = gattServer?.getService(crisisServiceUuid) ?: return false
        val characteristic = service.getCharacteristic(secureChatOutCharacteristicUuid) ?: return false
        val targetDevice = findConnectedGattDevice(normalizedAddress) ?: return false
        characteristic.value = transportPacket
        return notifyIfEnabled(targetDevice, characteristic)
    }

    fun sendChatMessage(addressUpper: String, text: String): Boolean {
        val normalizedAddress = addressUpper.uppercase(Locale.US)
        if (text.isBlank()) {
            return false
        }
        if (!verifiedClients.contains(normalizedAddress)) {
            return false
        }
        return notifyChat(normalizedAddress, text)
    }

    inner class LocalBinder : Binder() {
        fun getService(): GattSOSServerService = this@GattSOSServerService
    }

    @SuppressLint("MissingPermission")
    private fun findConnectedGattDevice(normalizedAddress: String): BluetoothDevice? {
        if (!hasBluetoothConnectPermission()) {
            return null
        }
        connectedGattDevices[normalizedAddress]?.let { cached ->
            return cached
        }
        val manager = bluetoothManager ?: return null
        val connectedDevices = linkedSetOf<BluetoothDevice>()
        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to query connected GATT devices", throwable)
        }.getOrDefault(emptyList()).forEach { device ->
            connectedDevices += device
        }
        runCatching {
            manager.getConnectedDevices(BluetoothProfile.GATT_SERVER)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to query connected GATT server devices", throwable)
        }.getOrDefault(emptyList()).forEach { device ->
            connectedDevices += device
        }
        return connectedDevices.firstOrNull { device ->
            device.address.uppercase(Locale.US) == normalizedAddress
        }?.also { device ->
            connectedGattDevices[normalizedAddress] = device
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyIfEnabled(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasBluetoothConnectPermission()) {
            return false
        }
        val payload = characteristic.value ?: return false
        val normalizedAddress = device.address.uppercase(Locale.US)
        if (!isNotificationEnabled(normalizedAddress, characteristic.uuid)) {
            return false
        }
        val chunkSize = notificationChunkSizeFor(normalizedAddress)
        val server = gattServer ?: return false
        var offset = 0
        var chunkIndex = 0
        while (offset < payload.size) {
            val end = (offset + chunkSize).coerceAtMost(payload.size)
            val chunk = if (offset == 0 && end == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, end)
            }
            characteristic.value = chunk
            val notified = runCatching {
                server.notifyCharacteristicChanged(device, characteristic, false)
            }.onFailure { throwable ->
                Log.w(
                    TAG,
                    "[$normalizedAddress] Failed to notify ${characteristic.uuid} chunk=$chunkIndex size=${chunk.size}",
                    throwable
                )
            }.getOrDefault(false)
            if (!notified) {
                Log.w(
                    TAG,
                    "[$normalizedAddress] Failed to notify ${characteristic.uuid} chunk=$chunkIndex size=${chunk.size}"
                )
                if (payload.size > chunkSize) {
                    characteristic.value = payload
                }
                return false
            }
            offset = end
            chunkIndex++
        }
        if (payload.size > chunkSize) {
            characteristic.value = payload
        }
        return true
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true
        }
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
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
        runCatching {
            gattServer?.sendResponse(device, requestId, status, offset, value)
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to send GATT response", throwable)
        }
    }

    private fun notificationChunkSizeFor(normalizedAddress: String): Int {
        val mtu = mtuByAddress[normalizedAddress] ?: DEFAULT_MTU
        return (mtu - ATT_NOTIFY_OVERHEAD_BYTES)
            .coerceIn(LEGACY_NOTIFY_CHUNK_SIZE, MAX_NOTIFICATION_CHUNK_SIZE)
    }

    private fun isNotificationEnabled(addressUpper: String, characteristicUuid: UUID): Boolean {
        val value = clientConfigValues[addressUpper to characteristicUuid] ?: return false
        return value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ||
            value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    private fun createNotification(): Notification {
        val channelId = NOTIFICATION_CHANNEL_ID
        ensureNotificationChannel(channelId)

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.sos_broadcast_notification_title))
            .setContentText(getString(R.string.sos_broadcast_notification_description))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureNotificationChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelId,
            getString(R.string.sos_broadcast_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.sos_broadcast_channel_description)
        }
        try {
            manager.createNotificationChannel(channel)
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Unable to register SOS notification channel", securityException)
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising success.")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error code: $errorCode")
            publishStartupFailure(resolveAdvertisingFailureMessage(errorCode))
            stopSelf()
        }
    }

    private fun resolveAdvertisingFailureMessage(errorCode: Int): String {
        return when (errorCode) {
            AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> getString(
                R.string.sos_error_advertise_already_started
            )

            AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> getString(
                R.string.sos_error_advertise_data_too_large
            )

            AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> getString(
                R.string.sos_error_advertise_unsupported
            )

            AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> getString(
                R.string.sos_error_advertise_failed
            )

            AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> getString(
                R.string.sos_error_advertise_limit_reached
            )

            else -> getString(R.string.sos_error_advertise_unknown_code, errorCode)
        }
    }

    private fun publishStartupFailure(message: String, throwable: Throwable? = null) {
        _startFailureMessage.value = message
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.w(TAG, message)
        }
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
        private const val TAG = "SOS_GATT"
        private const val SERVICE_ASSIGNED_NUMBER = 0xCC00
        private const val CHAR_ID_ASSIGNED_NUMBER = 0xCC01
        private const val CHAR_STATUS_ASSIGNED_NUMBER = 0xCC02
        private const val CHAR_AUTH_CHALLENGE_NUMBER = 0xCC10
        private const val CHAR_AUTH_RESPONSE_NUMBER = 0xCC11
        private const val CHAR_SECURE_IN_NUMBER = 0xCC20
        private const val CHAR_SECURE_ACK_NUMBER = 0xCC21
        private const val CHAR_SECURE_CHAT_IN_NUMBER = 0xCC30
        private const val CHAR_SECURE_CHAT_OUT_NUMBER = 0xCC31
        private const val NOTIFICATION_CHANNEL_ID = "sos_broadcast_channel"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_STATUS = "ACTIVE"
        private const val INITIATOR_RANK_MANUFACTURER_ID = 0x0F0F
        private const val DEFAULT_INITIATOR_SALT = 0x6F4B5D5E
        private const val INVALID_MAC_ADDRESS = "02:00:00:00:00:00"
        private const val MAX_ROLE_PROOF_PACKET_BYTES = 16_384
        private const val MAX_SECURE_CHAT_PACKET_BYTES = 32_767
        private const val DEFAULT_MTU = 23
        private const val ATT_NOTIFY_OVERHEAD_BYTES = 3
        private const val LEGACY_NOTIFY_CHUNK_SIZE = 20
        private const val MAX_NOTIFICATION_CHUNK_SIZE = 244
        private const val VOICE_TRANSFER_TTL_MS = 90_000L
        private const val VOICE_COMPLETED_BADGE_MS = 900L
        private const val VOICE_FAILED_BADGE_MS = 2_200L
        private const val IMAGE_TRANSFER_TTL_MS = 90_000L
        private const val IMAGE_COMPLETED_BADGE_MS = 1_200L
        private const val IMAGE_FAILED_BADGE_MS = 2_600L
        private const val FILE_TRANSFER_TTL_MS = 90_000L
        private const val ROLE_RESCUE = "rescue"
        private const val ROLE_VICTIM = "victim"
        private const val OUTBOUND_ROUTE_BLE_GATT_FALLBACK = "ble_gatt_fallback"
        private const val PEER_INFO_KIND = "peer_info"
        private val HANDSHAKE_ACK_PAYLOAD = "OK".toByteArray(Charsets.UTF_8)
        private const val SOS_LOCATION_MAX_LAST_KNOWN_AGE_MS = 30_000L
        private const val SOS_LOCATION_NETWORK_FIX_TIMEOUT_MS = 3_000L
        private const val SOS_LOCATION_GPS_FIX_TIMEOUT_MS = 6_000L
        private val PEER_INFO_ACK_PAYLOAD = "PEER_INFO_ACK".toByteArray(Charsets.UTF_8)
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val CRISIS_SERVICE_UUID: UUID = UUIDGenerator.fromAssignedNumber(SERVICE_ASSIGNED_NUMBER)

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _startTimestampMillis = MutableStateFlow<Long?>(null)
        val startTimestampMillis: StateFlow<Long?> = _startTimestampMillis

        private val _startFailureMessage = MutableStateFlow<String?>(null)
        val startFailureMessage: StateFlow<String?> = _startFailureMessage.asStateFlow()
        @Volatile
        private var activeInstance: GattSOSServerService? = null

        fun clearStartupFailureMessage() {
            _startFailureMessage.value = null
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

        fun startBroadcast(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, GattSOSServerService::class.java)
            ContextCompat.startForegroundService(appContext, intent)
        }

        fun stopBroadcast(context: Context) {
            _isRunning.value = false
            _startTimestampMillis.value = null
            val appContext = context.applicationContext
            val intent = Intent(appContext, GattSOSServerService::class.java)
            appContext.stopService(intent)
        }
    }
}
