package com.auralis.crisisconnect.service.client

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.BatteryManager
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.core.chat.ActiveChatTracker
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.BleChatStore
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.markAllLocalMessagesRead
import com.auralis.crisisconnect.data.markAllSentMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesDelivered
import com.auralis.crisisconnect.data.markLocalMessagesRead
import com.auralis.crisisconnect.data.normalizeMacAddress
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.data.local.ContactAvatarStorage
import com.auralis.crisisconnect.feature.RescueFeatureManager
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.security.Crypto
import com.auralis.crisisconnect.security.MissingRoleCertificateException
import com.auralis.crisisconnect.security.RoleProofCreator
import com.auralis.crisisconnect.service.BleChatEnvelope
import com.auralis.crisisconnect.service.BleDirectChatCompat
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.BleRadioPolicy
import com.auralis.crisisconnect.service.client.BleClientManager.ConnectionState
import com.auralis.crisisconnect.service.client.BleClientManager.ConnectionStatus
import com.auralis.crisisconnect.util.UUIDGenerator
import com.auralis.crisisconnect.util.writeCharacteristicCompat
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.atan2
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.jvm.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val MAX_SECURE_PACKET_BYTES = 16_384
private const val HANDSHAKE_ACK_TIMEOUT_MS = 8000L
private const val PREFERRED_MTU = 247
private const val PEER_INFO_KIND = "peer_info"
private const val OUTBOUND_ROUTE_BLE_GATT_FALLBACK = "ble_gatt_fallback"

/**
 * Represents a single BLE connection to a Rescue SOS device. Handles the secure handshake,
 * encrypted messaging, and automatic reconnection.
 */
class ClientConnection(
    private val context: Context,
    private val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val messageSink: MutableSharedFlow<IncomingBleMessage>,
    private val stateSink: MutableSharedFlow<ConnectionState>,
    private val onRemoved: (String) -> Unit,
) {
    private val address = device.address
    private val connectionJob = SupervisorJob()
    private val connectionScope = CoroutineScope(scope.coroutineContext + connectionJob + Dispatchers.IO)

    private val handshakeInProgress = AtomicBoolean(false)
    private val serviceInitializationInProgress = AtomicBoolean(false)

    private val operationMutex = Mutex()
    private val connectLifecycleMutex = Mutex()
    private val connectLaunchInProgress = AtomicBoolean(false)
    private var pendingReadUuid: UUID? = null
    private var pendingRead = CompletableDeferred<ByteArray?>()
    private var pendingWriteUuid: UUID? = null
    private var pendingWrite = CompletableDeferred<Boolean>()
    private var pendingDescriptorUuid: UUID? = null
    private var pendingDescriptor = CompletableDeferred<Boolean>()
    private var pendingRemoteRssi = CompletableDeferred<Int?>()
    private var handshakeAck = CompletableDeferred<Boolean>()

    private var gatt: BluetoothGatt? = null
    private var connectTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var closedByClient = false
    private var reconnectAttemptCount: Int = 0
    @Volatile
    private var lastStatus: ConnectionStatus = ConnectionStatus.Disconnected
    @Volatile
    private var negotiatedMtu: Int = DEFAULT_MTU
    @Volatile
    private var forceGattCacheRefreshOnNextConnect: Boolean = false

    private var sessionKey: ByteArray? = null
    private var userId: String? = null
    private var localUserName: String? = null
    private var peerBatteryPercent: Int? = null
    @Volatile
    private var preferredSessionCode: String? = null
    @Volatile
    private var lastRemoteRssi: Int? = null
    @Volatile
    private var lastRemoteRssiAtMillis: Long = 0L

    private val crisisService: UUID = UUIDGenerator.fromAssignedNumber(SERVICE_ASSIGNED_NUMBER)
    private val idCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_ID_ASSIGNED_NUMBER)
    private val authChallengeCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_AUTH_CHALLENGE_NUMBER)
    private val authResponseCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_AUTH_RESPONSE_NUMBER)
    private val secureInCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_IN_NUMBER)
    private val secureAckCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_ACK_NUMBER)
    private val secureChatInCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_CHAT_IN_NUMBER)
    private val secureChatOutCharacteristicUuid: UUID = UUIDGenerator.fromAssignedNumber(CHAR_SECURE_CHAT_OUT_NUMBER)

    private var idCharacteristic: BluetoothGattCharacteristic? = null
    private var authChallengeCharacteristic: BluetoothGattCharacteristic? = null
    private var authResponseCharacteristic: BluetoothGattCharacteristic? = null
    private var secureInCharacteristic: BluetoothGattCharacteristic? = null
    private var secureAckCharacteristic: BluetoothGattCharacteristic? = null
    private var secureChatInCharacteristic: BluetoothGattCharacteristic? = null
    private var secureChatOutCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile
    private var secureAckNotificationsEnabled: Boolean = false
    @Volatile
    private var secureChatOutNotificationsEnabled: Boolean = false

    private val chatReceiver = ClientChunkReceiver(
        maxPacketSize = MAX_SECURE_PACKET_BYTES,
        scope = connectionScope,
        onPacketReady = { packet -> handleIncomingChatPacket(packet) },
        tag = "ChatReceiver-$address",
    )

    @Volatile
    private var isDiscoveringServices = false
    @Volatile
    private var servicesInitialized = false
    @Volatile
    private var lastServiceDiscoveryRequestAtMs = 0L

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "[$address] Connection state change error status=$status newState=$newState")
            }
            cancelConnectTimeout()
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emitState(ConnectionStatus.Connected)
                    reconnectAttemptCount = 0
                    negotiatedMtu = DEFAULT_MTU
                    lastServiceDiscoveryRequestAtMs = 0L
                    requestConnectionPriorityForPhase(
                        gatt = gatt,
                        preferPerformance = true,
                        reason = "connected"
                    )
                    servicesInitialized = false
                    serviceInitializationInProgress.set(false)
                    secureAckNotificationsEnabled = false
                    secureChatOutNotificationsEnabled = false
                    val delayMs = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N) 600L else 0L
                    connectionScope.launch {
                        if (forceGattCacheRefreshOnNextConnect) {
                            forceGattCacheRefreshOnNextConnect = false
                            refreshGattCache(gatt)
                            delay(FORCED_CACHE_REFRESH_SETTLE_MS)
                        }
                        if (delayMs > 0) delay(delayMs)
                        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
                            val mtuRequested = gatt.requestMtu(PREFERRED_MTU)
                            if (!mtuRequested) {
                                startServiceDiscovery(gatt, force = true)
                            }
                        } else {
                            startServiceDiscovery(gatt, force = true)
                        }
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "[$address] Disconnected status=$status")
                    cleanupPendingOperations(status)
                    emitState(ConnectionStatus.Disconnected, reason = if (status == BluetoothGatt.GATT_SUCCESS) null else "GATT_$status")
                    handleReconnect()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                negotiatedMtu = mtu
                Log.d(TAG, "[$address] MTU changed to $mtu")
            } else {
                Log.w(TAG, "[$address] Failed to change MTU status=$status")
            }
            if (status == BluetoothGatt.GATT_SUCCESS || Build.VERSION.SDK_INT > Build.VERSION_CODES.N) {
                startServiceDiscovery(gatt, force = true)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            isDiscoveringServices = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "[$address] Service discovery failed status=$status")
                emitState(ConnectionStatus.Failed, reason = "DISCOVERY_$status")
                scheduleReconnect()
                return
            }
            if (servicesInitialized) {
                return
            }
            if (!serviceInitializationInProgress.compareAndSet(false, true)) {
                Log.d(TAG, "[$address] Service initialization already in progress; skipping duplicate callback")
                return
            }
            emitState(ConnectionStatus.Discovering)
            connectionScope.launch {
                runCatching { initializeServices(gatt) }
                    .onSuccess {
                        servicesInitialized = true
                    }
                    .onFailure { throwable ->
                        Log.e(TAG, "[$address] Failed to initialize services", throwable)
                        emitState(ConnectionStatus.Failed, reason = throwable.message)
                        if (throwable is MissingRoleCertificateException) {
                            Log.w(
                                TAG,
                                "[$address] Missing cached rescue certificate. Reconnect suspended until online verification."
                            )
                            closedByClient = true
                            closeGatt()
                        } else {
                            scheduleReconnect()
                        }
                    }
                serviceInitializationInProgress.set(false)
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            val deferred = synchronized(this@ClientConnection) {
                if (characteristic.uuid == pendingReadUuid) {
                    pendingReadUuid = null
                    val pending = pendingRead
                    pendingRead = CompletableDeferred()
                    pending
                } else {
                    null
                }
            }
            deferred?.let {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    it.complete(characteristic.value)
                } else {
                    it.complete(null)
                }
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (characteristic.uuid) {
                    secureAckCharacteristicUuid -> processAck(characteristic.value ?: ByteArray(0))
                    secureChatOutCharacteristicUuid -> characteristic.value?.let { chatReceiver.onChunk(it) }
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            when (characteristic.uuid) {
                secureAckCharacteristicUuid -> processAck(characteristic.value ?: ByteArray(0))
                secureChatOutCharacteristicUuid -> characteristic.value?.let { chatReceiver.onChunk(it) }
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            val deferred = synchronized(this@ClientConnection) {
                if (characteristic.uuid == pendingWriteUuid) {
                    pendingWriteUuid = null
                    val pending = pendingWrite
                    pendingWrite = CompletableDeferred()
                    pending
                } else {
                    null
                }
            }
            deferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "[$address] onCharacteristicWrite failed uuid=${characteristic.uuid} status=$status"
                )
                if (
                    characteristic.uuid == authChallengeCharacteristicUuid &&
                    status == BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                ) {
                    forceGattCacheRefreshOnNextConnect = true
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            val deferred = synchronized(this@ClientConnection) {
                if (descriptor.uuid == pendingDescriptorUuid) {
                    pendingDescriptorUuid = null
                    val pending = pendingDescriptor
                    pendingDescriptor = CompletableDeferred()
                    pending
                } else {
                    null
                }
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "[$address] onDescriptorWrite failed uuid=${descriptor.uuid} " +
                        "characteristic=${descriptor.characteristic.uuid} status=$status"
                )
                if (
                    descriptor.characteristic.uuid == secureAckCharacteristicUuid ||
                    descriptor.characteristic.uuid == secureChatOutCharacteristicUuid
                ) {
                    forceGattCacheRefreshOnNextConnect = true
                }
            }
            deferred?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                lastRemoteRssi = rssi
                lastRemoteRssiAtMillis = System.currentTimeMillis()
            }
            val deferred = synchronized(this@ClientConnection) {
                val pending = pendingRemoteRssi
                pendingRemoteRssi = CompletableDeferred()
                pending
            }
            deferred.complete(if (status == BluetoothGatt.GATT_SUCCESS) rssi else null)
        }
    }

    fun connect() {
        closedByClient = false
        if (!connectLaunchInProgress.compareAndSet(false, true)) {
            return
        }
        if (hasActiveOrPendingConnection()) {
            connectLaunchInProgress.set(false)
            return
        }
        reconnectJob?.cancel()
        connectionScope.launch {
            try {
                emitState(ConnectionStatus.Connecting)
                startGattConnection()
            } finally {
                connectLaunchInProgress.set(false)
            }
        }
    }

    fun registerSessionAlias(sessionCode: String) {
        val normalized = BleSessionResolver.normalizeSessionCode(sessionCode)
            ?: sessionCode.trim()
        if (normalized.isBlank()) {
            return
        }
        preferredSessionCode = normalized
    }

    fun disconnect(manual: Boolean) {
        closedByClient = closedByClient || manual
        reconnectJob?.cancel()
        cancelConnectTimeout()
        connectionScope.launch {
            closeGatt()
            emitState(if (manual) ConnectionStatus.Disconnected else ConnectionStatus.Failed)
            onRemoved(address)
            connectionScope.cancel()
        }
    }

    suspend fun sendMessage(text: String) {
        sendMessage(text, awaitWriteCompletion = true)
    }

    suspend fun sendMessageWithoutAck(text: String) {
        sendMessage(text, awaitWriteCompletion = false)
    }

    private suspend fun sendMessage(text: String, awaitWriteCompletion: Boolean) {
        val key = sessionKey ?: throw IllegalStateException("Handshake not complete for $address")
        val gatt = gatt ?: throw IllegalStateException("Not connected to $address")
        val chatCharacteristic = secureChatInCharacteristic
            ?: throw IllegalStateException("Chat characteristic missing for $address")
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val encrypted = AesCipherHelper.encrypt(key, payload)
        val transportPacket = AesCipherHelper.wrapForTransport(encrypted)
        operationMutex.withLock {
            sendChunked(
                gatt = gatt,
                characteristic = chatCharacteristic,
                packet = transportPacket,
                awaitWriteCompletion = awaitWriteCompletion
            )
        }
    }

    suspend fun sendReadReceipt(messageIds: Collection<String>) {
        val gatt = gatt ?: throw IllegalStateException("Not connected to $address")
        val ackCharacteristic = secureAckCharacteristic
            ?: throw IllegalStateException("Ack characteristic missing for $address")
        val payload = BleChatEnvelope.encodeReadAck(messageIds)
        if (!writeCharacteristic(gatt, ackCharacteristic, payload)) {
            throw IllegalStateException("Failed to send read receipt to $address")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun startGattConnection() {
        connectLifecycleMutex.withLock {
            cancelConnectTimeout()
            closeGatt()
            peerBatteryPercent = null
            lastRemoteRssi = null
            lastRemoteRssiAtMillis = 0L
            negotiatedMtu = DEFAULT_MTU
            servicesInitialized = false
            serviceInitializationInProgress.set(false)
            isDiscoveringServices = false
            lastServiceDiscoveryRequestAtMs = 0L
            val knownPeer = BleKnownPeersStore.isKnown(context, address)
            val useAutoConnect = knownPeer && reconnectAttemptCount > 0
            val newGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, useAutoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, useAutoConnect, gattCallback)
            }
            gatt = newGatt ?: throw IllegalStateException("connectGatt returned null for $address")
            Log.d(TAG, "[$address] connectGatt started autoConnect=$useAutoConnect knownPeer=$knownPeer retry=$reconnectAttemptCount")
            emitState(ConnectionStatus.Connecting)
            scheduleConnectTimeout(newGatt)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun closeGatt() {
        val existing = gatt ?: return
        try {
            existing.disconnect()
        } catch (ignored: SecurityException) {
            // Ignore, connection already lost.
        }
        delay(150L)
        existing.close()
        gatt = null
        sessionKey = null
        peerBatteryPercent = null
        lastRemoteRssi = null
        lastRemoteRssiAtMillis = 0L
        servicesInitialized = false
        serviceInitializationInProgress.set(false)
        isDiscoveringServices = false
        lastServiceDiscoveryRequestAtMs = 0L
        secureAckNotificationsEnabled = false
        secureChatOutNotificationsEnabled = false
    }

    private suspend fun initializeServices(gatt: BluetoothGatt) {
        val service = discoverServiceWithRetry(gatt, crisisService)
            ?: run {
                val discoveredServices = runCatching {
                    gatt.services.joinToString(separator = ",") { it.uuid.toString() }
                }.getOrDefault("")
                throw IllegalStateException(
                    "Service $crisisService not found on $address discovered=[$discoveredServices]"
                )
            }
        idCharacteristic = service.getCharacteristic(idCharacteristicUuid)
        authChallengeCharacteristic = service.getCharacteristic(authChallengeCharacteristicUuid)
        authResponseCharacteristic = service.getCharacteristic(authResponseCharacteristicUuid)
        secureInCharacteristic = service.getCharacteristic(secureInCharacteristicUuid)
        secureAckCharacteristic = service.getCharacteristic(secureAckCharacteristicUuid)
        secureChatInCharacteristic = service.getCharacteristic(secureChatInCharacteristicUuid)
        secureChatOutCharacteristic = service.getCharacteristic(secureChatOutCharacteristicUuid)
        if (idCharacteristic == null || authChallengeCharacteristic == null || authResponseCharacteristic == null ||
            secureInCharacteristic == null || secureAckCharacteristic == null || secureChatInCharacteristic == null ||
            secureChatOutCharacteristic == null
        ) {
            throw IllegalStateException("Missing required characteristics on $address")
        }
        secureAckNotificationsEnabled = configureNotificationsIfSupported(
            gatt = gatt,
            characteristic = secureAckCharacteristic!!,
        )
        secureChatOutNotificationsEnabled = configureNotificationsIfSupported(
            gatt = gatt,
            characteristic = secureChatOutCharacteristic!!,
        )
        if (!secureAckNotificationsEnabled) {
            Log.w(
                TAG,
                "[$address] Secure ACK notification setup unavailable; enabling READ polling fallback"
            )
        }
        if (!secureChatOutNotificationsEnabled) {
            Log.w(
                TAG,
                "[$address] Secure chat-out notifications unavailable; inbound chat updates may be delayed"
            )
        }
        emitState(ConnectionStatus.Authenticating)
        performHandshake(gatt)
    }

    private suspend fun discoverServiceWithRetry(
        gatt: BluetoothGatt,
        uuid: UUID,
    ): BluetoothGattService? {
        val startTime = System.currentTimeMillis()
        var lastRescanAt = Long.MIN_VALUE
        var cacheRefreshed = false
        var hasObservedDiscoveredServices = false

        while (System.currentTimeMillis() - startTime < SERVICE_DISCOVERY_TIMEOUT_MS) {
            gatt.getService(uuid)?.let { return it }

            val elapsedMs = System.currentTimeMillis() - startTime
            val discoveredSnapshot = runCatching { gatt.services }.getOrDefault(emptyList())
            if (discoveredSnapshot.isNotEmpty()) {
                hasObservedDiscoveredServices = true
            }

            val refreshThresholdMs = if (hasObservedDiscoveredServices) {
                SERVICE_DISCOVERY_INITIAL_REFRESH_MS
            } else {
                SERVICE_DISCOVERY_EMPTY_REFRESH_MS
            }
            if (!cacheRefreshed && elapsedMs > refreshThresholdMs) {
                cacheRefreshed = true
                refreshGattCache(gatt)
                startServiceDiscovery(gatt, force = true)
            }

            // Force the platform to materialize the internal cache so getService can succeed
            gatt.services

            val now = System.currentTimeMillis()
            if (now - lastRescanAt >= SERVICE_DISCOVERY_RESCAN_INTERVAL_MS) {
                lastRescanAt = now
                startServiceDiscovery(gatt)
            }

            delay(SERVICE_DISCOVERY_POLL_INTERVAL_MS)
        }

        return gatt.getService(uuid)
    }

    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val method = gatt.javaClass.getMethod("refresh")
            method.isAccessible = true
            method.invoke(gatt)
            Log.d(TAG, "[$address] GATT cache refreshed")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startServiceDiscovery(gatt: BluetoothGatt, delayMs: Long = 0L, force: Boolean = false) {
        connectionScope.launch {
            if (delayMs > 0) delay(delayMs)
            val now = System.currentTimeMillis()
            if (now - lastServiceDiscoveryRequestAtMs < SERVICE_DISCOVERY_MIN_REQUEST_INTERVAL_MS) {
                return@launch
            }
            if (!force && isDiscoveringServices) {
                return@launch
            }
            lastServiceDiscoveryRequestAtMs = now
            isDiscoveringServices = true
            val started = gatt.discoverServices()
            if (!started) {
                isDiscoveringServices = false
                Log.w(TAG, "[$address] discoverServices() returned false")
            } else {
                Log.d(TAG, "[$address] discoverServices() requested")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun configureNotificationsIfSupported(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): Boolean {
        val props = characteristic.properties
        val supportsNotify = props and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        if (!supportsNotify) {
            return false
        }
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.w(TAG, "[$address] Failed to enable notifications for ${characteristic.uuid}")
            return false
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
            ?: run {
                Log.w(TAG, "[$address] Missing CCCD for ${characteristic.uuid}")
                return false
            }
        val value = if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        val success = writeDescriptor(gatt, descriptor, value)
        if (!success) {
            Log.w(TAG, "[$address] Descriptor write failed for ${characteristic.uuid}")
            return false
        }
        return true
    }

    private suspend fun performHandshake(gatt: BluetoothGatt) {
        if (!handshakeInProgress.compareAndSet(false, true)) {
            return
        }
        try {
            synchronized(this) {
                handshakeAck = CompletableDeferred()
            }
            emitState(ConnectionStatus.Authenticating, userId = userId)

            val myKeys = Crypto.generateEphemeralEcKeyPair()
            val challengeCharacteristic = authChallengeCharacteristic
                ?: throw IllegalStateException("Missing challenge characteristic")
            Log.d(
                TAG,
                "[$address] Challenge characteristic props=0x${challengeCharacteristic.properties.toString(16)}"
            )
            val challengeWritten = writeAuthChallenge(gatt, challengeCharacteristic, myKeys.public.encoded)
            if (!challengeWritten) {
                throw IllegalStateException("Failed to write client public key for $address")
            }
            val responseBytes = readCharacteristicWithRetry(gatt, authResponseCharacteristic!!)
                ?: throw IllegalStateException("Failed to read server public key for $address")
            val serverPublicKey = decodeEcPublicKey(responseBytes)
            val derivedSessionKey = Crypto.deriveSessionKey(myKeys.private, serverPublicKey)
            sessionKey = derivedSessionKey
            val sessionNonce = buildSessionNonce(
                clientPublicKey = myKeys.public.encoded,
                serverPublicKey = responseBytes
            )

            val securePacket = RoleProofCreator(
                context = context,
                allowExpiredCertificate = false,
            ).createEncryptedPacket(
                keyBytes = derivedSessionKey,
                sessionNonce = sessionNonce
            )
            operationMutex.withLock {
                sendChunked(
                    gatt = gatt,
                    characteristic = secureInCharacteristic!!,
                    packet = securePacket,
                    awaitWriteCompletion = true,
                )
            }
            val ackReceived = awaitHandshakeAck(gatt)
            if (!ackReceived) {
                throw IllegalStateException("Handshake ACK timeout for $address")
            }
            runCatching {
                sendPeerIdentity()
            }.onFailure { throwable ->
                Log.w(TAG, "[$address] Failed to send peer identity", throwable)
            }
            BleKnownPeersStore.markKnown(context, address)
            requestConnectionPriorityForPhase(
                gatt = gatt,
                preferPerformance = false,
                reason = "ready"
            )
            emitState(ConnectionStatus.Ready, userId = userId)
            connectionScope.launch {
                fetchPeerIdBestEffort(gatt)
            }
        } finally {
            handshakeInProgress.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeAuthChallenge(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
    ): Boolean {
        val props = characteristic.properties
        val supportsWrite = props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val supportsNoResponse =
            props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0

        if (supportsWrite) {
            if (writeCharacteristic(gatt, characteristic, payload)) {
                return true
            }
        }

        val fallbackSent = operationMutex.withLock {
            val sent = gatt.writeCharacteristicCompat(
                characteristic = characteristic,
                value = payload,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            )
            if (sent) {
                delay(HANDSHAKE_CHALLENGE_NO_RESPONSE_SETTLE_MS)
                Log.w(TAG, "[$address] Challenge write fallback used WRITE_NO_RESPONSE")
            } else {
                Log.w(
                    TAG,
                    "[$address] Challenge WRITE_NO_RESPONSE fallback rejected " +
                        "(supportsWrite=$supportsWrite supportsNoResponse=$supportsNoResponse)"
                )
            }
            sent
        }
        return fallbackSent
    }

    private suspend fun readCharacteristicWithRetry(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        attempts: Int = HANDSHAKE_READ_ATTEMPTS,
        retryDelayMs: Long = HANDSHAKE_READ_RETRY_DELAY_MS,
    ): ByteArray? {
        repeat(attempts) { index ->
            val value = readCharacteristic(gatt, characteristic)
            if (value != null && value.isNotEmpty()) {
                return value
            }
            if (index < attempts - 1) {
                delay(retryDelayMs)
            }
        }
        return null
    }

    private suspend fun fetchPeerIdBestEffort(gatt: BluetoothGatt) {
        val idChar = idCharacteristic ?: return
        val idBytes = readCharacteristicWithRetry(
            gatt = gatt,
            characteristic = idChar,
            attempts = 2,
            retryDelayMs = 180L,
        ) ?: return
        val resolvedUserId = idBytes.toString(StandardCharsets.UTF_8)
            .trim()
            .takeUnless { it.isBlank() }
            ?: return
        userId = resolvedUserId
        val broadcastId = parseBroadcastId(resolvedUserId)
        if (broadcastId != null) {
            BleBroadcastDirectory.update(broadcastId, address, System.currentTimeMillis())
            BlePeerIdentityUtils.SignalLocationRegistry.bindSignalId(address, broadcastId)
            RescueFeatureManager(context).notifySignalLocationUpdated()
            val sessionCode = resolveSessionCodeForAddress(address.uppercase(Locale.US))
            withContext(Dispatchers.IO) {
                updateContactAddress(context, sessionCode, address)
            }
        }
        emitState(lastStatus, userId = userId, peerBatteryPercent = peerBatteryPercent)
    }

    private suspend fun awaitHandshakeAck(gatt: BluetoothGatt): Boolean {
        val ackCharacteristic = secureAckCharacteristic ?: return false
        val deferred = synchronized(this) {
            handshakeAck
        }
        val supportsRead = ackCharacteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        if (supportsRead) {
            val value = readCharacteristic(gatt, ackCharacteristic)
            if (value != null && processAck(value)) {
                return true
            }
            if (!secureAckNotificationsEnabled) {
                val readFallbackAck: Boolean = withContext(Dispatchers.IO) {
                    val deadline = SystemClock.elapsedRealtime() + HANDSHAKE_ACK_TIMEOUT_MS
                    while (SystemClock.elapsedRealtime() < deadline) {
                        delay(HANDSHAKE_ACK_READ_POLL_INTERVAL_MS)
                        val polled = readCharacteristic(gatt, ackCharacteristic)
                        if (polled != null && processAck(polled)) {
                            return@withContext true
                        }
                    }
                    false
                }
                if (readFallbackAck) {
                    return true
                }
            }
        }
        if (!secureAckNotificationsEnabled) {
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                withTimeoutOrNull(HANDSHAKE_ACK_TIMEOUT_MS) {
                    deferred.await()
                } ?: false
            } finally {
                synchronized(this@ClientConnection) {
                    handshakeAck = CompletableDeferred()
                }
            }
        }
    }

    private fun processAck(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return false
        }
        if (bytes.contentEquals(HANDSHAKE_ACK_PAYLOAD)) {
            handshakeAck.complete(true)
            return true
        }
        val sessionCode = resolveSessionCodeForAddress(address.uppercase(Locale.US))
        val ack = BleChatEnvelope.decodeAck(bytes)
        if (ack == null) {
            val text = String(bytes, StandardCharsets.UTF_8)
            Log.d(TAG, "[$address] Received ACK payload '$text'")
            if (text == "PEER_INFO_ACK" && lastStatus != ConnectionStatus.Ready) {
                emitState(ConnectionStatus.Ready, userId = userId, peerBatteryPercent = peerBatteryPercent)
            }
            return true
        }
        when (ack.type) {
            BleChatEnvelope.AckType.DELIVERED -> {
                if (ack.messageIds.isEmpty()) {
                    BleChatStore.markDeliveredForAllSent(sessionCode)
                    scope.launch(Dispatchers.IO) {
                        markAllSentMessagesDelivered(context, sessionCode)
                    }
                } else {
                    BleChatStore.markDelivered(sessionCode, ack.messageIds)
                    scope.launch(Dispatchers.IO) {
                        markLocalMessagesDelivered(context, sessionCode, ack.messageIds)
                    }
                }
            }

            BleChatEnvelope.AckType.READ -> {
                if (ack.messageIds.isEmpty()) {
                    BleChatStore.markReadAllLocal(sessionCode)
                    scope.launch(Dispatchers.IO) {
                        markAllLocalMessagesRead(context, sessionCode)
                    }
                } else {
                    BleChatStore.markRead(sessionCode, ack.messageIds)
                    scope.launch(Dispatchers.IO) {
                        markLocalMessagesRead(context, sessionCode, ack.messageIds)
                    }
                }
            }

            BleChatEnvelope.AckType.DECRYPT_FAIL -> { /* Not applicable to rescuer client connection */ }
        }
        return true
    }

    private suspend fun handleIncomingChatPacket(packet: ByteArray) {
        val key = sessionKey ?: return
        val transportPayload = BleDirectChatCompat.unwrapTransportPacket(
            packet = packet,
            maxPacketSize = MAX_SECURE_PACKET_BYTES
        ) ?: run {
            Log.w(TAG, "[$address] Dropping malformed secure chat packet: invalid transport header")
            chatReceiver.reset()
            return
        }
        val message = runCatching {
            val plaintext = AesCipherHelper.decrypt(key, transportPayload)
            plaintext.toString(StandardCharsets.UTF_8).trimEnd { it == '\u0000' }
        }.getOrElse { throwable ->
            BleDirectChatCompat.decodePayloadJson(
                outerMessage = transportPayload.toString(StandardCharsets.UTF_8),
                keyBytes = key,
                maxEncryptedPacketBytes = MAX_SECURE_PACKET_BYTES
            ) ?: run {
                Log.w(TAG, "[$address] Dropping malformed secure chat packet", throwable)
                chatReceiver.reset()
                return
            }
        }
        parsePeerInfoPayload(message)?.let { identity ->
            handlePeerIdentity(identity)
            return
        }
        val now = System.currentTimeMillis()
        val envelope = BleChatEnvelope.decodeChat(message)
        if (envelope != null) {
            val normalizedAddress = address.uppercase(Locale.US)
            preferredSessionCode = if (
                envelope.route.equals(OUTBOUND_ROUTE_BLE_GATT_FALLBACK, ignoreCase = true)
            ) {
                resolveFallbackSessionCodeForAddress(normalizedAddress)
            } else {
                BleSessionResolver.sessionCodeForAddress(normalizedAddress)
            }
            if (!BleChatEnvelope.isExpired(envelope, now)) {
                messageSink.emit(
                    IncomingBleMessage(
                        address = address,
                        userId = userId,
                        message = envelope.text,
                        messageId = envelope.messageId,
                        createdAtMillis = envelope.createdAtMillis,
                        ttlMillis = envelope.ttlMillis,
                        route = envelope.route,
                        timestampMs = now,
                    ),
                )
            } else {
                Log.w(TAG, "[$address] Dropping expired chat envelope id=${envelope.messageId}")
            }
            sendDeliveredReceipt(envelope.messageId)
            return
        }
        messageSink.emit(
            IncomingBleMessage(
                address = address,
                userId = userId,
                message = message,
                timestampMs = now,
            ),
        )
    }

    private fun sendDeliveredReceipt(messageId: String?) {
        val ackId = messageId?.trim().takeUnless { it.isNullOrEmpty() } ?: return
        connectionScope.launch {
            val currentGatt = gatt ?: return@launch
            val ackCharacteristic = secureAckCharacteristic ?: return@launch
            val payload = BleChatEnvelope.encodeDeliveredAck(ackId)
            runCatching {
                operationMutex.withLock {
                    writeCharacteristic(currentGatt, ackCharacteristic, payload)
                }
            }.onFailure { throwable ->
                Log.w(TAG, "[$address] Failed to send delivered ACK for $ackId", throwable)
            }
        }
    }

    private suspend fun sendPeerIdentity() {
        val name = resolveLocalName()
        val role = BlePeerIdentityUtils.roleValue(
            LocalKeyStorage.getSavedRole(context)
        )
        val avatarPayload = withContext(Dispatchers.IO) {
            ContactAvatarStorage.localProfileAvatarPayload(context)
        }
        val payload = BlePeerIdentityUtils.buildPeerInfoPayload(
            name = name,
            role = role,
            batteryPercent = currentBatteryPercent(),
            avatarBase64 = avatarPayload
        )
        sendMessage(payload)
    }

    private suspend fun handlePeerIdentity(identity: BlePeerIdentityUtils.PeerIdentity) {
        val normalizedAddress = address.uppercase(Locale.US)
        val sessionCode = resolveSessionCodeForAddress(normalizedAddress)
        val claimedRoleValue = BlePeerIdentityUtils.normalizePeerRoleValue(identity.role)
        if (claimedRoleValue == BlePeerIdentityUtils.ROLE_RESCUE) {
            Log.d(TAG, "[$address] Ignoring unverified rescue role claim from peer_info")
        }
        val displayName = BlePeerIdentityUtils.buildUnverifiedPeerDisplayName(
            rawName = identity.name,
            sessionCode = sessionCode,
            context = context
        )
        peerBatteryPercent = identity.batteryPercent
        BlePeerIdentityUtils.SignalLocationRegistry.updateVictimIdentity(
            address = normalizedAddress,
            displayName = displayName,
            batteryPercent = identity.batteryPercent
        )
        BleChatStore.ensureSession(sessionCode)
        withContext(Dispatchers.IO) {
            val contact = Contact(
                name = displayName,
                aesKey = "",
                sessionCode = sessionCode,
                address = normalizedAddress
            )
            saveContact(context, contact)
            identity.avatarBase64?.let { avatarPayload ->
                ContactAvatarStorage.saveRemoteAvatarPayload(
                    context = context,
                    sessionCode = sessionCode,
                    payloadBase64 = avatarPayload
                )
            }
        }
        identity.location?.let { location ->
            BlePeerIdentityUtils.SignalLocationRegistry.updateVictimLocation(
                address = normalizedAddress,
                location = location
            )
        } ?: connectionScope.launch {
            estimateAndStoreRelativeVictimLocation(normalizedAddress)
        }
        RescueFeatureManager(context).notifySignalLocationUpdated()
        emitState(lastStatus, userId = userId, peerBatteryPercent = peerBatteryPercent)
    }

    private suspend fun estimateAndStoreRelativeVictimLocation(normalizedAddress: String) {
        val currentGatt = gatt ?: return
        val originLocation = resolveCurrentLocationSnapshot() ?: return
        val headingDegrees = readCurrentHeadingDegrees(originLocation)
        val remoteRssi = sampleRemoteRssi(currentGatt) ?: cachedRemoteRssi()
        val resolvedRssi = remoteRssi ?: -82
        val distanceMeters = estimateDistanceFromRssiMeters(resolvedRssi)
        val projected = if (headingDegrees != null) {
            offsetLatLng(
                originLatitude = originLocation.latitude,
                originLongitude = originLocation.longitude,
                bearingDegrees = headingDegrees.toDouble(),
                distanceMeters = distanceMeters
            )
        } else {
            ProjectedCoordinate(
                latitude = originLocation.latitude,
                longitude = originLocation.longitude
            )
        }
        val sampledAtMillis = System.currentTimeMillis()
        BlePeerIdentityUtils.SignalLocationRegistry.updateRelativeEstimate(
            address = normalizedAddress,
            estimatedVictimLocation = BlePeerIdentityUtils.PeerLocationSnapshot(
                latitude = projected.latitude,
                longitude = projected.longitude,
                accuracyMeters = estimateProjectedAccuracyMeters(
                    originAccuracyMeters = originLocation.accuracyMeters,
                    distanceMeters = distanceMeters,
                    rssi = resolvedRssi
                ),
                capturedAtMillis = sampledAtMillis,
                source = if (headingDegrees != null) {
                    "ble_relative_estimate"
                } else {
                    "ble_anchor_estimate"
                }
            ),
            relativeEstimate = headingDegrees?.let {
                BlePeerIdentityUtils.RelativeVictimEstimate(
                    bearingDegrees = it.toDouble(),
                    distanceMeters = distanceMeters,
                    confidence = estimateRelativeConfidence(
                        originAccuracyMeters = originLocation.accuracyMeters,
                        rssi = resolvedRssi
                    ),
                    originLatitude = originLocation.latitude,
                    originLongitude = originLocation.longitude,
                    originAccuracyMeters = originLocation.accuracyMeters,
                    originCapturedAtMillis = originLocation.capturedAtMillis,
                    headingSource = "rotation_vector",
                    rssi = remoteRssi,
                    sampledAtMillis = sampledAtMillis
                )
            }
        )
        RescueFeatureManager(context).notifySignalLocationUpdated()
    }

    private fun cachedRemoteRssi(): Int? {
        val rssi = lastRemoteRssi ?: return null
        val ageMillis = System.currentTimeMillis() - lastRemoteRssiAtMillis
        return rssi.takeIf { ageMillis in 0..REMOTE_RSSI_MAX_AGE_MS }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sampleRemoteRssi(gatt: BluetoothGatt): Int? {
        return operationMutex.withLock {
            if (!hasConnectPermission()) {
                return@withLock cachedRemoteRssi()
            }
            val deferred = CompletableDeferred<Int?>()
            synchronized(this) {
                pendingRemoteRssi = deferred
            }
            val started = runCatching { gatt.readRemoteRssi() }.getOrDefault(false)
            if (!started) {
                synchronized(this) {
                    if (pendingRemoteRssi === deferred) {
                        pendingRemoteRssi = CompletableDeferred()
                    }
                }
                return@withLock cachedRemoteRssi()
            }
            val callbackResult = withTimeoutOrNull(REMOTE_RSSI_TIMEOUT_MS) {
                deferred.await()
            }
            if (callbackResult != null) {
                return@withLock callbackResult
            }
            synchronized(this) {
                if (pendingRemoteRssi === deferred) {
                    pendingRemoteRssi = CompletableDeferred()
                }
            }
            Log.w(TAG, "[$address] readRemoteRssi callback timeout")
            cachedRemoteRssi()
        }
    }

    private suspend fun resolveCurrentLocationSnapshot(): LocalLocationSnapshot? {
        val now = System.currentTimeMillis()
        val bestLastKnown = readBestLocationSnapshot(now)
        if (bestLastKnown != null &&
            (now - bestLastKnown.capturedAtMillis).coerceAtLeast(0L) <= RELATIVE_LOCATION_MAX_LAST_KNOWN_AGE_MS
        ) {
            return bestLastKnown
        }
        return requestFreshLocationSnapshot(now) ?: bestLastKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestFreshLocationSnapshot(now: Long): LocalLocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
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
                RELATIVE_LOCATION_GPS_FIX_TIMEOUT_MS
            } else {
                RELATIVE_LOCATION_NETWORK_FIX_TIMEOUT_MS
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
            toLocalLocationSnapshot(
                location = location,
                now = now,
                source = location.provider ?: "gps"
            )
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
                            ContextCompat.getMainExecutor(context)
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
    private fun readBestLocationSnapshot(now: Long): LocalLocationSnapshot? {
        if (!hasPreciseLocationPermission()) {
            return null
        }
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return null
        val candidates = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        ).mapNotNull { provider ->
            runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { isPlausibleLocation(it) }

        val best = candidates.minByOrNull { location -> locationScore(now, location) } ?: return null
        return toLocalLocationSnapshot(
            location = best,
            now = now,
            source = "last_known_${best.provider ?: "unknown"}"
        )
    }

    private fun toLocalLocationSnapshot(
        location: Location,
        now: Long,
        source: String
    ): LocalLocationSnapshot {
        return LocalLocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = location.accuracy.takeIf { it.isFinite() && it > 0f },
            capturedAtMillis = location.time.takeIf { it > 0L } ?: now,
            source = source
        )
    }

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
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

    private suspend fun readCurrentHeadingDegrees(referenceLocation: LocalLocationSnapshot?): Float? {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: return null
        val declinationDegrees = referenceLocation?.let { location ->
            runCatching {
                GeomagneticField(
                    location.latitude.toFloat(),
                    location.longitude.toFloat(),
                    0f,
                    location.capturedAtMillis
                ).declination
            }.getOrNull()
        } ?: 0f
        return withTimeoutOrNull(HEADING_SAMPLE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Float?> { continuation ->
                val rotationMatrix = FloatArray(9)
                val orientationAngles = FloatArray(3)
                var lastAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type != Sensor.TYPE_ROTATION_VECTOR) {
                            return
                        }
                        if (lastAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                            return
                        }
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        val magneticHeading = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                        val trueNorthHeading = normalizeHeadingDegrees(magneticHeading + declinationDegrees)
                        sensorManager.unregisterListener(this)
                        if (continuation.isActive) {
                            continuation.resume(trueNorthHeading)
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                            lastAccuracy = accuracy
                        }
                    }
                }
                val registered = sensorManager.registerListener(
                    listener,
                    rotationVectorSensor,
                    SensorManager.SENSOR_DELAY_GAME,
                    Handler(Looper.getMainLooper())
                )
                if (!registered) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    sensorManager.unregisterListener(listener)
                }
            }
        }
    }

    private fun normalizeHeadingDegrees(value: Float): Float {
        val normalized = value % 360f
        return if (normalized < 0f) normalized + 360f else normalized
    }

    private fun offsetLatLng(
        originLatitude: Double,
        originLongitude: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ): ProjectedCoordinate {
        if (!distanceMeters.isFinite() || distanceMeters <= 0.0) {
            return ProjectedCoordinate(originLatitude, originLongitude)
        }
        val earthRadiusMeters = 6_378_137.0
        val distanceRatio = distanceMeters / earthRadiusMeters
        val bearingRadians = Math.toRadians(bearingDegrees)
        val latitudeRadians = Math.toRadians(originLatitude)
        val longitudeRadians = Math.toRadians(originLongitude)

        val nextLatitudeRadians = asin(
            (sin(latitudeRadians) * cos(distanceRatio)) +
                (cos(latitudeRadians) * sin(distanceRatio) * cos(bearingRadians))
        )
        val nextLongitudeRadians = longitudeRadians + atan2(
            sin(bearingRadians) * sin(distanceRatio) * cos(latitudeRadians),
            cos(distanceRatio) - (sin(latitudeRadians) * sin(nextLatitudeRadians))
        )
        return ProjectedCoordinate(
            latitude = Math.toDegrees(nextLatitudeRadians),
            longitude = Math.toDegrees(nextLongitudeRadians)
        )
    }

    private fun estimateDistanceFromRssiMeters(rssi: Int): Double = when {
        rssi >= -50 -> 1.0
        rssi >= -55 -> 1.8
        rssi >= -60 -> 2.8
        rssi >= -67 -> 4.5
        rssi >= -75 -> 8.5
        rssi >= -85 -> 15.0
        else -> 28.0
    }

    private fun estimateProjectedAccuracyMeters(
        originAccuracyMeters: Float?,
        distanceMeters: Double,
        rssi: Int
    ): Float {
        val originComponent = originAccuracyMeters ?: 20f
        val rssiComponent = when {
            rssi >= -60 -> 3f
            rssi >= -70 -> 6f
            rssi >= -80 -> 10f
            else -> 18f
        }
        return (originComponent + rssiComponent + (distanceMeters * 0.35).toFloat())
            .coerceAtLeast(originComponent)
    }

    private fun estimateRelativeConfidence(
        originAccuracyMeters: Float?,
        rssi: Int
    ): Float {
        var confidence = when {
            rssi >= -60 -> 0.72f
            rssi >= -70 -> 0.56f
            rssi >= -80 -> 0.40f
            else -> 0.25f
        }
        originAccuracyMeters?.let { accuracy ->
            confidence += when {
                accuracy <= 10f -> 0.16f
                accuracy <= 25f -> 0.08f
                else -> -0.05f
            }
        }
        return confidence.coerceIn(0.1f, 0.9f)
    }

    private fun resolveSessionCodeForAddress(addressUpper: String): String {
        val normalizedAddress = normalizeMacAddress(addressUpper).ifBlank {
            addressUpper.trim().uppercase(Locale.US)
        }
        val cached = preferredSessionCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (cached != null) {
            return cached
        }
        val fallback = BleSessionResolver.sessionCodeForAddress(normalizedAddress)
        preferredSessionCode = fallback
        return fallback
    }

    private fun resolveFallbackSessionCodeForAddress(normalizedAddress: String): String {
        val contacts = runCatching { getContacts(context) }
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
                    updateContactAddress(context, activeSession, normalizedAddress)
                }
                return activeSession
            }
        }
        return BleSessionResolver.sessionCodeForAddress(normalizedAddress)
    }

    private suspend fun resolveLocalName(): String {
        localUserName?.let { cached ->
            if (cached.isNotBlank()) return cached
        }
        val name = runCatching { getSavedUserName(context).first() }
            .getOrDefault("")
            .trim()
        if (name.isNotEmpty()) {
            localUserName = name
        }
        return name.ifBlank {
            val roleValue = BlePeerIdentityUtils.roleValue(LocalKeyStorage.getSavedRole(context))
            BlePeerIdentityUtils.roleLabel(roleValue, context)
        }
    }

    private fun currentBatteryPercent(): Int? {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        return ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
    }

    private fun parsePeerInfoPayload(message: String): BlePeerIdentityUtils.PeerIdentity? {
        return BlePeerIdentityUtils.parsePeerInfoPayload(message)
    }

    private fun parseBroadcastId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        val normalized = trimmed.removePrefix("ccid:").removePrefix("CCID:").trim()
        if (!normalized.startsWith("cc-", ignoreCase = true)) {
            return null
        }
        return normalized.uppercase(Locale.US)
    }

    private fun decodeEcPublicKey(encoded: ByteArray): PublicKey {
        require(encoded.isNotEmpty()) { "Server public key missing" }
        val spec = X509EncodedKeySpec(encoded)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun buildSessionNonce(clientPublicKey: ByteArray, serverPublicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(clientPublicKey + serverPublicKey)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private suspend fun sendChunked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        packet: ByteArray,
        awaitWriteCompletion: Boolean
    ) {
        val maxChunkSize = currentGattWriteChunkSize(characteristic)
        var offset = 0
        var chunkIndex = 0
        while (offset < packet.size) {
            val end = (offset + maxChunkSize).coerceAtMost(packet.size)
            val chunk = packet.copyOfRange(offset, end)
            val success = if (awaitWriteCompletion) {
                writeChunkWithRetry(
                    gatt = gatt,
                    characteristic = characteristic,
                    chunk = chunk
                )
            } else {
                writeChunk(
                    gatt = gatt,
                    characteristic = characteristic,
                    chunk = chunk,
                    awaitWriteCompletion = false
                )
            }
            if (!success) {
                throw IllegalStateException("Failed to write chunk $chunkIndex for $address")
            }
            offset = end
            chunkIndex++
        }
    }

    private fun currentGattWriteChunkSize(characteristic: BluetoothGattCharacteristic): Int {
        // Keep secure handshake writes conservative for broader Android BLE stack compatibility.
        if (characteristic.uuid == secureInCharacteristicUuid) {
            return HANDSHAKE_CHUNK_SIZE
        }
        return (negotiatedMtu - ATT_WRITE_OVERHEAD_BYTES)
            .coerceIn(LEGACY_CHUNK_SIZE, MAX_CHUNK_SIZE)
    }

    private suspend fun writeChunkWithRetry(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray
    ): Boolean {
        var attempt = 1
        while (attempt <= MAX_CHUNK_WRITE_ATTEMPTS) {
            val success = writeChunk(
                gatt = gatt,
                characteristic = characteristic,
                chunk = chunk,
                awaitWriteCompletion = true
            )
            if (success) {
                return true
            }
            if (attempt < MAX_CHUNK_WRITE_ATTEMPTS) {
                val backoffMs = CHUNK_WRITE_RETRY_BASE_DELAY_MS * attempt
                Log.w(
                    TAG,
                    "[$address] Retrying chunk write uuid=${characteristic.uuid} attempt=${attempt + 1}/$MAX_CHUNK_WRITE_ATTEMPTS"
                )
                delay(backoffMs)
            }
            attempt++
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeChunk(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        chunk: ByteArray,
        awaitWriteCompletion: Boolean
    ): Boolean {
        val supportsNoResponseWrite =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        val writeType = if (!awaitWriteCompletion && supportsNoResponseWrite) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        if (!awaitWriteCompletion) {
            return gatt.writeCharacteristicCompat(
                characteristic = characteristic,
                value = chunk,
                writeType = writeType,
            )
        }

        val deferred = CompletableDeferred<Boolean>()
        synchronized(this) {
            pendingWriteUuid = characteristic.uuid
            pendingWrite = deferred
        }
        if (!gatt.writeCharacteristicCompat(
                characteristic = characteristic,
                value = chunk,
                writeType = writeType,
            )
        ) {
            synchronized(this) {
                pendingWriteUuid = null
                pendingWrite = CompletableDeferred()
            }
            return false
        }
        val callbackResult = withTimeoutOrNull(GATT_CALLBACK_TIMEOUT_MS) {
            deferred.await()
        }
        if (callbackResult != null) {
            return callbackResult
        }
        synchronized(this) {
            if (pendingWriteUuid == characteristic.uuid) {
                pendingWriteUuid = null
                pendingWrite = CompletableDeferred()
            }
        }
        Log.w(TAG, "[$address] writeChunk callback timeout uuid=${characteristic.uuid}")
        return false
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean {
        return operationMutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            synchronized(this) {
                pendingWriteUuid = characteristic.uuid
                pendingWrite = deferred
            }
            if (!gatt.writeCharacteristicCompat(
                    characteristic = characteristic,
                    value = value,
                    writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            ) {
                synchronized(this) {
                    pendingWriteUuid = null
                    pendingWrite = CompletableDeferred()
                }
                return@withLock false
            }
            val callbackResult = withTimeoutOrNull(GATT_CALLBACK_TIMEOUT_MS) {
                deferred.await()
            }
            if (callbackResult != null) {
                return@withLock callbackResult
            }
            synchronized(this) {
                if (pendingWriteUuid == characteristic.uuid) {
                    pendingWriteUuid = null
                    pendingWrite = CompletableDeferred()
                }
            }
            Log.w(TAG, "[$address] writeCharacteristic callback timeout uuid=${characteristic.uuid}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun readCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): ByteArray? {
        return operationMutex.withLock {
            val deferred = CompletableDeferred<ByteArray?>()
            synchronized(this) {
                pendingReadUuid = characteristic.uuid
                pendingRead = deferred
            }
            if (!gatt.readCharacteristic(characteristic)) {
                synchronized(this) {
                    pendingReadUuid = null
                    pendingRead = CompletableDeferred()
                }
                return@withLock null
            }
            val callbackResult = withTimeoutOrNull(GATT_CALLBACK_TIMEOUT_MS) {
                deferred.await()
            }
            if (callbackResult != null) {
                return@withLock callbackResult
            }
            synchronized(this) {
                if (pendingReadUuid == characteristic.uuid) {
                    pendingReadUuid = null
                    pendingRead = CompletableDeferred()
                }
            }
            Log.w(TAG, "[$address] readCharacteristic callback timeout uuid=${characteristic.uuid}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptor(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return operationMutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            synchronized(this) {
                pendingDescriptorUuid = descriptor.uuid
                pendingDescriptor = deferred
            }
            descriptor.value = value
            if (!gatt.writeDescriptor(descriptor)) {
                synchronized(this) {
                    pendingDescriptorUuid = null
                    pendingDescriptor = CompletableDeferred()
                }
                return@withLock false
            }
            val callbackResult = withTimeoutOrNull(GATT_CALLBACK_TIMEOUT_MS) {
                deferred.await()
            }
            if (callbackResult != null) {
                return@withLock callbackResult
            }
            synchronized(this) {
                if (pendingDescriptorUuid == descriptor.uuid) {
                    pendingDescriptorUuid = null
                    pendingDescriptor = CompletableDeferred()
                }
            }
            Log.w(TAG, "[$address] writeDescriptor callback timeout characteristic=${descriptor.characteristic.uuid}")
            false
        }
    }

    private fun emitState(
        status: ConnectionStatus,
        userId: String? = this.userId,
        peerBatteryPercent: Int? = this.peerBatteryPercent,
        reason: String? = null,
    ) {
        lastStatus = status
        Log.d(
            TAG,
            "[$address] emitState status=$status reason=$reason userId=$userId peerBattery=$peerBatteryPercent"
        )
        scope.launch {
            val safeDeviceName = resolveDeviceNameSafely()
            stateSink.emit(
                ConnectionState(
                    address = address,
                    status = status,
                    userId = userId,
                    deviceName = safeDeviceName,
                    peerBatteryPercent = peerBatteryPercent,
                    reason = reason,
                ),
            )
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveDeviceNameSafely(): String? {
        if (!hasConnectPermission()) {
            return null
        }
        return runCatching { device.name }.getOrNull()
    }

    private fun hasActiveOrPendingConnection(): Boolean {
        if (reconnectJob?.isActive == true) {
            return true
        }
        if (handshakeInProgress.get() || serviceInitializationInProgress.get()) {
            return true
        }
        if (gatt == null) {
            return false
        }
        return lastStatus in ACTIVE_CONNECTION_STATES
    }

    private fun handleReconnect() {
        if (closedByClient) {
            onRemoved(address)
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (closedByClient) {
            return
        }
        reconnectAttemptCount += 1
        val attempt = reconnectAttemptCount
        val delayMs = computeReconnectDelayMs(attempt)
        reconnectJob?.cancel()
        reconnectJob = connectionScope.launch {
            emitState(ConnectionStatus.Reconnecting, reason = "RETRY_$attempt")
            delay(delayMs)
            runCatching { startGattConnection() }
                .onFailure { throwable ->
                    if (throwable is CancellationException) {
                        return@onFailure
                    }
                    Log.e(TAG, "[$address] Failed to reconnect attempt=$attempt delayMs=$delayMs", throwable)
                    emitState(ConnectionStatus.Failed, reason = throwable.message)
                    scheduleReconnect()
                }
        }
    }

    private fun scheduleConnectTimeout(targetGatt: BluetoothGatt) {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = connectionScope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (closedByClient || !isCurrentGatt(targetGatt) || lastStatus != ConnectionStatus.Connecting) {
                return@launch
            }
            connectTimeoutJob = null
            Log.w(TAG, "[$address] connectGatt timed out after ${CONNECT_TIMEOUT_MS}ms")
            cleanupPendingOperations(BluetoothGatt.GATT_FAILURE)
            emitState(ConnectionStatus.Failed, reason = "CONNECT_TIMEOUT")
            closeGatt()
            scheduleReconnect()
        }
    }

    private fun cancelConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = null
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionPriorityForPhase(
        gatt: BluetoothGatt,
        preferPerformance: Boolean,
        reason: String
    ) {
        val decision = BleRadioPolicy.resolve(
            context = context,
            preferPerformance = preferPerformance
        )
        runCatching {
            gatt.requestConnectionPriority(decision.connectionPriority)
        }.onFailure { exception ->
            if (exception is SecurityException) {
                Log.w(
                    TAG,
                    "[$address] Unable to request BLE connection priority for phase=$reason",
                    exception
                )
            }
        }
    }

    private fun computeReconnectDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(1)
        val exponent = (safeAttempt - 1).coerceAtMost(RECONNECT_MAX_EXPONENT)
        val base = (RECONNECT_BASE_DELAY_MS * (1L shl exponent))
            .coerceAtMost(RECONNECT_BASE_CAP_MS)
        val jitter = (base * RECONNECT_JITTER_RATIO).toLong()
            .coerceAtLeast(RECONNECT_MIN_JITTER_MS)
        val randomized = base + Random.nextLong(from = -jitter, until = jitter + 1L)
        val batteryCap = reconnectCapForBattery()
        return randomized
            .coerceAtLeast(RECONNECT_MIN_DELAY_MS)
            .coerceAtMost(batteryCap)
    }

    private fun reconnectCapForBattery(): Long {
        val batteryPercent = currentBatteryPercent()
        val isPowerSaveMode = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isPowerSaveMode == true
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        return when {
            isCharging -> RECONNECT_CAP_CHARGING_MS
            isPowerSaveMode || (batteryPercent != null && batteryPercent <= 15) -> RECONNECT_CAP_VERY_LOW_BATTERY_MS
            batteryPercent != null && batteryPercent <= 30 -> RECONNECT_CAP_LOW_BATTERY_MS
            else -> RECONNECT_CAP_NORMAL_MS
        }
    }

    private fun cleanupPendingOperations(status: Int) {
        synchronized(this) {
            isDiscoveringServices = false
            servicesInitialized = false
            serviceInitializationInProgress.set(false)
            lastServiceDiscoveryRequestAtMs = 0L
            pendingRead.complete(null)
            pendingRead = CompletableDeferred()
            pendingReadUuid = null
            pendingWrite.complete(false)
            pendingWrite = CompletableDeferred()
            pendingWriteUuid = null
            pendingDescriptor.complete(false)
            pendingDescriptor = CompletableDeferred()
            pendingDescriptorUuid = null
            pendingRemoteRssi.complete(null)
            pendingRemoteRssi = CompletableDeferred()
            handshakeAck.complete(status == BluetoothGatt.GATT_SUCCESS)
            handshakeAck = CompletableDeferred()
        }
    }

    private fun isCurrentGatt(callbackGatt: BluetoothGatt): Boolean {
        return gatt === callbackGatt
    }

    private data class LocalLocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val capturedAtMillis: Long,
        val source: String
    )

    private data class ProjectedCoordinate(
        val latitude: Double,
        val longitude: Double
    )

    companion object {
        private const val TAG = "ClientConnection"
        private const val SERVICE_ASSIGNED_NUMBER = 0xCC00
        private const val CHAR_ID_ASSIGNED_NUMBER = 0xCC01
        private const val CHAR_STATUS_ASSIGNED_NUMBER = 0xCC02
        private const val CHAR_AUTH_CHALLENGE_NUMBER = 0xCC10
        private const val CHAR_AUTH_RESPONSE_NUMBER = 0xCC11
        private const val CHAR_SECURE_IN_NUMBER = 0xCC20
        private const val CHAR_SECURE_ACK_NUMBER = 0xCC21
        private const val CHAR_SECURE_CHAT_IN_NUMBER = 0xCC30
        private const val CHAR_SECURE_CHAT_OUT_NUMBER = 0xCC31
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val HANDSHAKE_ACK_PAYLOAD = "OK".toByteArray(StandardCharsets.UTF_8)
        private const val DEFAULT_MTU = 23
        private const val ATT_WRITE_OVERHEAD_BYTES = 3
        private const val LEGACY_CHUNK_SIZE = 20
        private const val HANDSHAKE_CHUNK_SIZE = 20
        private const val MAX_CHUNK_SIZE = 244
        private const val MAX_CHUNK_WRITE_ATTEMPTS = 3
        private const val CHUNK_WRITE_RETRY_BASE_DELAY_MS = 40L
        private const val GATT_CALLBACK_TIMEOUT_MS = 3500L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val RECONNECT_BASE_DELAY_MS = 1_800L
        private const val RECONNECT_BASE_CAP_MS = 60_000L
        private const val RECONNECT_MIN_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_EXPONENT = 6
        private const val RECONNECT_MIN_JITTER_MS = 250L
        private const val RECONNECT_JITTER_RATIO = 0.22
        private const val RECONNECT_CAP_NORMAL_MS = 60_000L
        private const val RECONNECT_CAP_LOW_BATTERY_MS = 90_000L
        private const val RECONNECT_CAP_VERY_LOW_BATTERY_MS = 150_000L
        private const val RECONNECT_CAP_CHARGING_MS = 45_000L
        private const val HANDSHAKE_READ_ATTEMPTS = 3
        private const val HANDSHAKE_READ_RETRY_DELAY_MS = 240L
        private const val HANDSHAKE_CHALLENGE_NO_RESPONSE_SETTLE_MS = 220L
        private const val FORCED_CACHE_REFRESH_SETTLE_MS = 260L
        private const val HANDSHAKE_ACK_READ_POLL_INTERVAL_MS = 220L
        private const val REMOTE_RSSI_TIMEOUT_MS = 1800L
        private const val REMOTE_RSSI_MAX_AGE_MS = 15_000L
        private const val RELATIVE_LOCATION_MAX_LAST_KNOWN_AGE_MS = 30_000L
        private const val RELATIVE_LOCATION_NETWORK_FIX_TIMEOUT_MS = 2_500L
        private const val RELATIVE_LOCATION_GPS_FIX_TIMEOUT_MS = 5_000L
        private const val HEADING_SAMPLE_TIMEOUT_MS = 1_500L
        private const val SERVICE_DISCOVERY_TIMEOUT_MS = 8000L
        private const val SERVICE_DISCOVERY_POLL_INTERVAL_MS = 200L
        private const val SERVICE_DISCOVERY_RESCAN_INTERVAL_MS = 1000L
        private const val SERVICE_DISCOVERY_INITIAL_REFRESH_MS = 1500L
        private const val SERVICE_DISCOVERY_EMPTY_REFRESH_MS = 3500L
        private const val SERVICE_DISCOVERY_MIN_REQUEST_INTERVAL_MS = 350L
        private val ACTIVE_CONNECTION_STATES = setOf(
            ConnectionStatus.Connecting,
            ConnectionStatus.Connected,
            ConnectionStatus.Discovering,
            ConnectionStatus.Authenticating,
            ConnectionStatus.Ready,
            ConnectionStatus.Reconnecting
        )
    }
}
