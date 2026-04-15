package com.auralis.crisisconnect.service.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.core.media.ImageFileUtils
import com.auralis.crisisconnect.core.media.generateImageThumbnail
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.imageMessageFile
import com.auralis.crisisconnect.data.imageThumbnailFile
import com.auralis.crisisconnect.data.markAllLocalMessagesRead
import com.auralis.crisisconnect.data.markLocalMessagesDeliveredWithRecipient
import com.auralis.crisisconnect.data.markLocalMessagesReadWithRecipient
import com.auralis.crisisconnect.data.normalizePreferredTransport
import com.auralis.crisisconnect.data.persistSharedDocumentLocalCopy
import com.auralis.crisisconnect.data.saveRemoteAudioMessage
import com.auralis.crisisconnect.data.saveRemoteImageMessage
import com.auralis.crisisconnect.data.saveRemoteMessage
import com.auralis.crisisconnect.data.MessageDeliveryStatus
import com.auralis.crisisconnect.data.updateContactAesKey
import com.auralis.crisisconnect.data.updateContactBleRuntimeMetadata
import com.auralis.crisisconnect.data.updateLocalMessageDeliveryState
import com.auralis.crisisconnect.data.voiceMessageFile
import com.auralis.crisisconnect.data.voiceMessageFileName
import com.auralis.crisisconnect.data.PREFERRED_TRANSPORT_BLE_GATT
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.security.AesCipherHelper
import com.auralis.crisisconnect.security.BleChunkReceiver
import com.auralis.crisisconnect.security.BleChunkSender
import com.auralis.crisisconnect.service.BleFilePayload
import com.auralis.crisisconnect.service.BleImagePayload
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.auralis.crisisconnect.service.BleMessageNotifier
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private data class P2pBootstrapIdentity(
    val shareId: String?,
    val deviceId: String?,
    val displayName: String?
)

private data class P2pDecodedChatPayload(
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

private data class P2pIncomingVoiceTransfer(
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

enum class P2pGattChatStatus {
    Disconnected,
    Connecting,
    Discovering,
    Ready,
    Failed
}

private const val P2P_CHAT_SCAN_TIMEOUT_MS = 12_000L
private const val P2P_CHAT_PACKET_MAX_BYTES = 8_192
private const val P2P_CHAT_ENCRYPTED_MAX_BYTES = 4_096
private const val P2P_CHAT_RECONNECT_DELAY_MS = 1_000L
private const val P2P_CHAT_DISCONNECT_GRACE_MS = 45_000L
private const val P2P_CHAT_CONNECT_FAST_DELAY_MS = 200L
private const val P2P_CHAT_CONNECT_SLOW_DELAY_MS = 650L
private const val P2P_CHAT_TRANSIENT_RETRY_STEP_MS = 450L
private const val P2P_CHAT_MAX_TRANSIENT_CONNECT_FAILURES = 3
private const val P2P_CHAT_SCAN_OWNER = "p2p-gatt-chat-manager"
private const val MAX_P2P_LOCAL_NAME_LENGTH = 8
private const val P2P_CHAT_DEFAULT_ATT_MTU = 23
private const val P2P_CHAT_PREFERRED_MTU = 247
private const val P2P_CHAT_ATT_WRITE_OVERHEAD_BYTES = 3
private const val P2P_CHAT_LEGACY_CHUNK_SIZE = 20
private const val P2P_CHAT_MAX_CHUNK_SIZE = 512
private const val P2P_CHAT_PHASE_TIMEOUT_MS = 8_000L
private const val P2P_CHAT_SERVICE_DISCOVERY_RETRY_DELAY_MS = 450L

class P2pGattChatManager(
    context: Context
) {
    companion object {
        private const val TAG = "P2pGattChatMgr"

        @Volatile
        private var sharedInstance: P2pGattChatManager? = null

        fun shared(context: Context): P2pGattChatManager {
            return sharedInstance ?: synchronized(this) {
                sharedInstance ?: P2pGattChatManager(context.applicationContext).also {
                    sharedInstance = it
                }
            }
        }

        fun ownsAddress(address: String?): Boolean {
            return sharedInstance?.ownsAddressInternal(address) == true
        }
    }

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val outputReceiver = BleChunkReceiver(maxPacketSize = P2P_CHAT_PACKET_MAX_BYTES)

    private val _status = MutableStateFlow(P2pGattChatStatus.Disconnected)
    val status: StateFlow<P2pGattChatStatus> = _status.asStateFlow()

    private val _connectedSessions = MutableStateFlow<Set<String>>(emptySet())
    val connectedSessions: StateFlow<Set<String>> = _connectedSessions.asStateFlow()

    @Volatile
    private var currentContact: Contact? = null

    @Volatile
    private var shouldStayConnected = false

    @Volatile
    private var activeGatt: BluetoothGatt? = null

    @Volatile
    private var activeDevice: BluetoothDevice? = null

    @Volatile
    private var idCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var bootstrapCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var messageInCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var messageOutCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var negotiatedMtu: Int = P2P_CHAT_DEFAULT_ATT_MTU

    @Volatile
    private var validatedIdentity = false

    @Volatile
    private var authenticatedName: String? = null

    @Volatile
    private var pendingWrite: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingDescriptorWrite: CompletableDeferred<Boolean>? = null

    @Volatile
    private var pendingWriteResult: Boolean? = null

    @Volatile
    private var pendingDescriptorWriteResult: Boolean? = null

    private var reconnectJob: Job? = null
    private var connectJob: Job? = null
    private var scanTimeoutJob: Job? = null
    private var delayedStopJob: Job? = null
    private var phaseTimeoutJob: Job? = null
    private var isScanning = false
    @Volatile
    private var pendingConnectAddress: String? = null
    @Volatile
    private var serviceDiscoveryStarted = false
    @Volatile
    private var refreshedGattCacheAfterMissingService = false
    private val rejectedAddresses = linkedSetOf<String>()
    private val transientConnectFailures = ConcurrentHashMap<String, Int>()
    private val incomingVoiceTransfers = ConcurrentHashMap<String, P2pIncomingVoiceTransfer>()
    private val incomingImageTransfers = ConcurrentHashMap<String, BleImagePayload.IncomingTransfer>()
    private val incomingFileTransfers = ConcurrentHashMap<String, BleFilePayload.IncomingTransfer>()

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private fun ownsAddressInternal(address: String?): Boolean {
        if (!shouldStayConnected) {
            return false
        }
        val normalized = normalizeAddress(address)
        if (normalized.isBlank()) {
            return false
        }
        return normalized == normalizeAddress(pendingConnectAddress) ||
            normalized == normalizeAddress(activeDevice?.address) ||
            normalized == normalizeAddress(activeGatt?.device?.address)
    }

    fun updateContact(contact: Contact?) {
        currentContact = contact
        if (contact == null || normalizePreferredTransport(contact.preferredTransport) != PREFERRED_TRANSPORT_BLE_GATT) {
            stopNow()
        } else {
            refreshConnectedSessions()
        }
    }

    fun start() {
        delayedStopJob?.cancel()
        delayedStopJob = null
        shouldStayConnected = true
        rejectedAddresses.clear()
        transientConnectFailures.clear()
        beginScanIfPossible()
    }

    fun stop() {
        stopNow()
    }

    fun stopNow() {
        shouldStayConnected = false
        delayedStopJob?.cancel()
        delayedStopJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        clearPhaseTimeout()
        pendingConnectAddress = null
        stopScanIfNeeded()
        disconnectGatt(clearDevice = true)
        outputReceiver.reset()
        validatedIdentity = false
        authenticatedName = null
        pendingWrite?.cancel()
        pendingWrite = null
        pendingDescriptorWrite?.cancel()
        pendingDescriptorWrite = null
        pendingWriteResult = null
        pendingDescriptorWriteResult = null
        transientConnectFailures.clear()
        incomingVoiceTransfers.clear()
        incomingImageTransfers.clear()
        incomingFileTransfers.clear()
        setStatus(P2pGattChatStatus.Disconnected)
    }

    fun detach() {
        delayedStopJob?.cancel()
        delayedStopJob = null
        if (!shouldStayConnected || (activeGatt == null && !isScanning && pendingConnectAddress.isNullOrBlank())) {
            stopNow()
            return
        }
        delayedStopJob = scope.launch {
            delay(P2P_CHAT_DISCONNECT_GRACE_MS)
            stopNow()
        }
    }

    fun isReady(): Boolean {
        return _status.value == P2pGattChatStatus.Ready &&
            activeGatt != null &&
            messageInCharacteristic != null &&
            validatedIdentity
    }

    suspend fun sendText(
        message: ChatMessage,
        contact: Contact
    ): Boolean {
        if (!shouldUseBleGatt(contact)) {
            return false
        }
        val messageText = message.text.trim()
        if (messageText.isEmpty()) {
            return false
        }
        val packet = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_TEXT,
            messageId = message.messageUuid,
            text = messageText,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        return writePacket(packet)
    }

    suspend fun sendReadReceipt(
        contact: Contact,
        messageIds: Collection<String>
    ): Boolean {
        if (!shouldUseBleGatt(contact) || messageIds.isEmpty()) {
            return false
        }
        val normalizedIds = LinkedHashSet<String>()
        messageIds.forEach { rawId ->
            val normalizedId = rawId.trim()
            if (normalizedId.isNotEmpty()) {
                normalizedIds += normalizedId
            }
        }
        if (normalizedIds.isEmpty()) {
            return false
        }
        normalizedIds.forEach { messageId ->
            val packet = buildTransportPacket(
                contact = contact,
                kind = P2pBleProtocol.CHAT_KIND_READ,
                messageId = messageId,
                text = null,
                displayName = null,
                mimeType = null,
                durationMillis = null,
                width = null,
                height = null,
                originalSizeBytes = null,
                totalBytes = null,
                totalChunks = null,
                sha256 = null,
                chunkIndex = null,
                chunkBytes = null
            ) ?: return false
            if (!writePacket(packet)) {
                return false
            }
        }
        return true
    }

    suspend fun sendVoiceMessage(
        contact: Contact,
        messageId: String,
        mimeType: String,
        durationMillis: Long,
        bytes: ByteArray
    ): Boolean {
        if (!shouldUseBleGatt(contact) || bytes.isEmpty() || bytes.size > P2pBleProtocol.VOICE_MAX_TOTAL_BYTES) {
            return false
        }
        val safeMessageId = messageId.trim()
        if (safeMessageId.isEmpty()) {
            return false
        }
        val totalChunks = ((bytes.size + P2pBleProtocol.VOICE_CHUNK_SIZE_BYTES - 1) /
            P2pBleProtocol.VOICE_CHUNK_SIZE_BYTES).coerceAtLeast(1)
        if (totalChunks > P2pBleProtocol.VOICE_MAX_CHUNKS) {
            return false
        }

        val initPacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_VOICE_INIT,
            messageId = safeMessageId,
            text = null,
            displayName = null,
            mimeType = mimeType,
            durationMillis = durationMillis,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = totalChunks,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        if (!writePacket(initPacket)) {
            return false
        }

        var offset = 0
        for (index in 0 until totalChunks) {
            val end = (offset + P2pBleProtocol.VOICE_CHUNK_SIZE_BYTES).coerceAtMost(bytes.size)
            val chunkPacket = buildTransportPacket(
                contact = contact,
                kind = P2pBleProtocol.CHAT_KIND_VOICE_CHUNK,
                messageId = safeMessageId,
                text = null,
                displayName = null,
                mimeType = null,
                durationMillis = null,
                width = null,
                height = null,
                originalSizeBytes = null,
                totalBytes = null,
                totalChunks = null,
                sha256 = null,
                chunkIndex = index,
                chunkBytes = bytes.copyOfRange(offset, end)
            ) ?: return false
            if (!writePacket(chunkPacket)) {
                return false
            }
            offset = end
        }

        val donePacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_VOICE_DONE,
            messageId = safeMessageId,
            text = null,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        return writePacket(donePacket)
    }

    suspend fun sendImageMessage(
        contact: Contact,
        messageId: String,
        mimeType: String,
        width: Int,
        height: Int,
        bytes: ByteArray
    ): Boolean {
        if (
            !shouldUseBleGatt(contact) ||
            bytes.isEmpty() ||
            bytes.size > P2pBleProtocol.IMAGE_MAX_TOTAL_BYTES
        ) {
            return false
        }
        val safeMessageId = messageId.trim()
        if (safeMessageId.isEmpty() || width <= 0 || height <= 0) {
            return false
        }
        val totalChunks = ((bytes.size + P2pBleProtocol.IMAGE_CHUNK_SIZE_BYTES - 1) /
            P2pBleProtocol.IMAGE_CHUNK_SIZE_BYTES).coerceAtLeast(1)
        if (totalChunks > P2pBleProtocol.IMAGE_MAX_CHUNKS) {
            return false
        }
        val digest = base64Sha256(bytes) ?: return false

        val initPacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_IMAGE_INIT,
            messageId = safeMessageId,
            text = null,
            displayName = null,
            mimeType = mimeType,
            durationMillis = null,
            width = width,
            height = height,
            originalSizeBytes = null,
            totalBytes = bytes.size,
            totalChunks = totalChunks,
            sha256 = digest,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        if (!writePacket(initPacket)) {
            return false
        }

        var offset = 0
        for (index in 0 until totalChunks) {
            val end = (offset + P2pBleProtocol.IMAGE_CHUNK_SIZE_BYTES).coerceAtMost(bytes.size)
            val chunkPacket = buildTransportPacket(
                contact = contact,
                kind = P2pBleProtocol.CHAT_KIND_IMAGE_CHUNK,
                messageId = safeMessageId,
                text = null,
                displayName = null,
                mimeType = null,
                durationMillis = null,
                width = null,
                height = null,
                originalSizeBytes = null,
                totalBytes = null,
                totalChunks = null,
                sha256 = null,
                chunkIndex = index,
                chunkBytes = bytes.copyOfRange(offset, end)
            ) ?: return false
            if (!writePacket(chunkPacket)) {
                return false
            }
            offset = end
        }

        val donePacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_IMAGE_DONE,
            messageId = safeMessageId,
            text = null,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        return writePacket(donePacket)
    }

    suspend fun sendFileMessage(
        contact: Contact,
        messageId: String,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Long,
        bytes: ByteArray
    ): Boolean {
        if (
            !shouldUseBleGatt(contact) ||
            bytes.isEmpty() ||
            bytes.size > P2pBleProtocol.FILE_MAX_TOTAL_BYTES
        ) {
            return false
        }
        val safeMessageId = messageId.trim()
        val safeDisplayName = displayName.trim()
        if (safeMessageId.isEmpty() || safeDisplayName.isEmpty() || originalSizeBytes <= 0L) {
            return false
        }
        val totalChunks = ((bytes.size + P2pBleProtocol.FILE_CHUNK_SIZE_BYTES - 1) /
            P2pBleProtocol.FILE_CHUNK_SIZE_BYTES).coerceAtLeast(1)
        if (totalChunks > P2pBleProtocol.FILE_MAX_CHUNKS) {
            return false
        }
        val digest = base64Sha256(bytes) ?: return false

        val initPacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_FILE_INIT,
            messageId = safeMessageId,
            text = null,
            displayName = safeDisplayName,
            mimeType = mimeType,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = originalSizeBytes,
            totalBytes = bytes.size,
            totalChunks = totalChunks,
            sha256 = digest,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        if (!writePacket(initPacket)) {
            return false
        }

        var offset = 0
        for (index in 0 until totalChunks) {
            val end = (offset + P2pBleProtocol.FILE_CHUNK_SIZE_BYTES).coerceAtMost(bytes.size)
            val chunkPacket = buildTransportPacket(
                contact = contact,
                kind = P2pBleProtocol.CHAT_KIND_FILE_CHUNK,
                messageId = safeMessageId,
                text = null,
                displayName = null,
                mimeType = null,
                durationMillis = null,
                width = null,
                height = null,
                originalSizeBytes = null,
                totalBytes = null,
                totalChunks = null,
                sha256 = null,
                chunkIndex = index,
                chunkBytes = bytes.copyOfRange(offset, end)
            ) ?: return false
            if (!writePacket(chunkPacket)) {
                return false
            }
            offset = end
        }

        val donePacket = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_FILE_DONE,
            messageId = safeMessageId,
            text = null,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return false
        return writePacket(donePacket)
    }

    private fun shouldUseBleGatt(contact: Contact?): Boolean {
        return contact != null &&
            normalizePreferredTransport(contact.preferredTransport) == PREFERRED_TRANSPORT_BLE_GATT &&
            contact.aesKey.isNotBlank()
    }

    private fun beginScanIfPossible() {
        val contact = currentContact
        if (!shouldStayConnected || !shouldUseBleGatt(contact)) {
            return
        }
        if (!hasBleScanPermission() || !hasBleConnectPermission()) {
            setStatus(P2pGattChatStatus.Failed)
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            setStatus(P2pGattChatStatus.Failed)
            return
        }
        if (activeGatt != null || isScanning || !pendingConnectAddress.isNullOrBlank()) {
            return
        }
        val bluetoothScanner = scanner ?: run {
            setStatus(P2pGattChatStatus.Failed)
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        isScanning = true
        setStatus(P2pGattChatStatus.Connecting)
        val started = runCatching {
            // iOS advertisements can miss hardware-level service UUID filtering on some stacks.
            // Scan unfiltered here and validate candidates in-process.
            BleScanCoordinator.registerOrUpdate(
                owner = P2P_CHAT_SCAN_OWNER,
                scanner = bluetoothScanner,
                mode = settings.scanMode,
                filters = null,
                listener = scanListener
            )
        }.getOrElse { throwable ->
            Log.w(TAG, "Unable to start coordinated P2P GATT scan", throwable)
            false
        }
        if (!started) {
            isScanning = false
            setStatus(P2pGattChatStatus.Failed)
            scheduleReconnect()
            return
        }
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(P2P_CHAT_SCAN_TIMEOUT_MS)
            if (isScanning && activeGatt == null) {
                stopScanIfNeeded()
                setStatus(P2pGattChatStatus.Failed)
                scheduleReconnect()
            }
        }
    }

    private fun stopScanIfNeeded() {
        if (!isScanning) {
            return
        }
        isScanning = false
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        BleScanCoordinator.unregister(P2P_CHAT_SCAN_OWNER)
    }

    @SuppressLint("MissingPermission")
    private fun connect(
        device: BluetoothDevice,
        delayMs: Long = preferredConnectDelayMs(
            failureCount = transientConnectFailures[normalizeAddress(device.address)] ?: 0
        )
    ) {
        val address = normalizeAddress(device.address)
        if (address.isBlank()) {
            setStatus(P2pGattChatStatus.Failed)
            scheduleReconnect()
            return
        }
        if (!hasBleConnectPermission()) {
            setStatus(P2pGattChatStatus.Failed)
            return
        }
        if (activeGatt != null && normalizeAddress(activeDevice?.address) == address) {
            Log.d(TAG, "Ignoring duplicate P2P chat connect for active device $address")
            return
        }
        if (pendingConnectAddress == address) {
            Log.d(TAG, "Ignoring duplicate pending P2P chat connect for $address")
            return
        }
        GattMeshForegroundService.deprioritizePeerForP2p(address)
        stopScanIfNeeded()
        reconnectJob?.cancel()
        reconnectJob = null
        connectJob?.cancel()
        connectJob = null
        disconnectGatt(clearDevice = true)
        activeDevice = device
        pendingConnectAddress = address
        validatedIdentity = false
        authenticatedName = null
        outputReceiver.reset()
        rejectedAddresses.remove(address)
        serviceDiscoveryStarted = false
        refreshedGattCacheAfterMissingService = false
        clearPhaseTimeout()
        setStatus(P2pGattChatStatus.Connecting)
        connectJob = scope.launch {
            if (delayMs > 0L) {
                Log.d(TAG, "Delaying P2P chat connect to $address by ${delayMs}ms")
                delay(delayMs)
            }
            if (!shouldStayConnected || activeGatt != null || normalizeAddress(activeDevice?.address) != address) {
                if (pendingConnectAddress == address && activeGatt == null) {
                    pendingConnectAddress = null
                }
                return@launch
            }
            activeGatt = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                } else {
                    device.connectGatt(appContext, false, gattCallback)
                }
            }.getOrElse { throwable ->
                Log.w(TAG, "Failed to connect GATT", throwable)
                null
            }
            if (activeGatt == null) {
                if (pendingConnectAddress == address) {
                    pendingConnectAddress = null
                }
                handleGattConnectionFailure(
                    status = BluetoothGatt.GATT_FAILURE,
                    newState = BluetoothProfile.STATE_DISCONNECTED,
                    device = device
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(clearDevice: Boolean) {
        clearPhaseTimeout()
        pendingWrite?.cancel()
        pendingWrite = null
        pendingDescriptorWrite?.cancel()
        pendingDescriptorWrite = null
        pendingWriteResult = null
        pendingDescriptorWriteResult = null
        runCatching { activeGatt?.disconnect() }
        runCatching { activeGatt?.close() }
        activeGatt = null
        idCharacteristic = null
        bootstrapCharacteristic = null
        messageInCharacteristic = null
        messageOutCharacteristic = null
        negotiatedMtu = P2P_CHAT_DEFAULT_ATT_MTU
        validatedIdentity = false
        authenticatedName = null
        outputReceiver.reset()
        incomingVoiceTransfers.clear()
        incomingImageTransfers.clear()
        incomingFileTransfers.clear()
        pendingConnectAddress = null
        serviceDiscoveryStarted = false
        refreshedGattCacheAfterMissingService = false
        if (clearDevice) {
            activeDevice = null
        }
    }

    private fun scheduleReconnect(
        retryDevice: BluetoothDevice? = null,
        delayMs: Long = P2P_CHAT_RECONNECT_DELAY_MS
    ) {
        if (!shouldStayConnected) {
            setStatus(P2pGattChatStatus.Disconnected)
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayMs)
            disconnectGatt(clearDevice = true)
            if (!shouldStayConnected) {
                setStatus(P2pGattChatStatus.Disconnected)
                return@launch
            }
            if (retryDevice != null) {
                connect(retryDevice, delayMs = 0L)
            } else {
                beginScanIfPossible()
            }
        }
    }

    private fun setStatus(next: P2pGattChatStatus) {
        if (_status.value != next) {
            _status.value = next
        }
        refreshConnectedSessions()
    }

    private fun refreshConnectedSessions() {
        val sessionCode = currentContact
            ?.sessionCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        _connectedSessions.value = if (_status.value == P2pGattChatStatus.Ready && sessionCode != null) {
            setOf(sessionCode)
        } else {
            emptySet()
        }
    }

    private suspend fun buildTransportPacket(
        contact: Contact,
        kind: String,
        messageId: String?,
        text: String?,
        displayName: String?,
        mimeType: String?,
        durationMillis: Long?,
        width: Int?,
        height: Int?,
        originalSizeBytes: Long?,
        totalBytes: Int?,
        totalChunks: Int?,
        sha256: String?,
        chunkIndex: Int?,
        chunkBytes: ByteArray?
    ): ByteArray? {
        val keyBytes = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return null
        val senderName = runCatching { getSavedUserName(appContext).first().trim() }.getOrDefault("")
        val inner = JSONObject().apply {
            put("kind", kind)
            if (!messageId.isNullOrBlank()) {
                put("messageId", messageId)
            }
            if (!text.isNullOrBlank()) {
                put("text", text)
            }
            if (!displayName.isNullOrBlank()) {
                put("displayName", displayName)
            }
            if (!mimeType.isNullOrBlank()) {
                put("mimeType", mimeType)
            }
            if (durationMillis != null) {
                put("durationMillis", durationMillis.coerceAtLeast(0L))
            }
            if (width != null) {
                put("width", width)
            }
            if (height != null) {
                put("height", height)
            }
            if (originalSizeBytes != null) {
                put("originalSizeBytes", originalSizeBytes)
            }
            if (totalBytes != null) {
                put("totalBytes", totalBytes)
            }
            if (totalChunks != null) {
                put("totalChunks", totalChunks)
            }
            if (!sha256.isNullOrBlank()) {
                put("sha256", sha256)
            }
            if (chunkIndex != null) {
                put("chunkIndex", chunkIndex)
            }
            if (chunkBytes != null && chunkBytes.isNotEmpty()) {
                put("chunkData", Base64.encodeToString(chunkBytes, Base64.NO_WRAP))
            }
            if (senderName.isNotBlank()) {
                put("senderName", senderName)
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        val encrypted = runCatching { AesCipherHelper.encrypt(keyBytes, inner) }.getOrNull() ?: return null
        val outer = JSONObject().apply {
            put("type", P2pBleProtocol.TYPE_CHAT_ENVELOPE)
            put("fromDeviceId", LocalKeyStorage.getOrCreateP2pDeviceId(appContext))
            put("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }.toString().toByteArray(StandardCharsets.UTF_8)
        return runCatching { P2pBleProtocol.wrapTransportPacket(outer) }.getOrNull()
    }

    private suspend fun writePacket(packet: ByteArray): Boolean {
        val contact = currentContact
        if (!shouldUseBleGatt(contact)) {
            return false
        }
        if (!isReady()) {
            beginScanIfPossible()
            return false
        }
        val gatt = activeGatt ?: return false
        val characteristic = messageInCharacteristic ?: return false
        return writeMutex.withLock {
            val sender = BleChunkSender.fromGatt(
                gatt = gatt,
                characteristic = characteristic,
                awaiter = { awaitCharacteristicWrite() },
                maxChunkSize = currentGattWriteChunkSize(),
                interChunkDelayMs = 0L
            )
            runCatching {
                sender.sendPacket(packet)
            }.onFailure { throwable ->
                Log.w(TAG, "Failed to write P2P chat packet", throwable)
                scheduleReconnect()
            }.isSuccess
        }
    }

    private suspend fun awaitCharacteristicWrite(): Boolean {
        pendingWriteResult?.let { result ->
            pendingWriteResult = null
            return result
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingWrite = deferred
        pendingWriteResult?.let { result ->
            if (pendingWrite === deferred) {
                pendingWrite = null
            }
            pendingWriteResult = null
            return result
        }
        val result = withTimeoutOrNull(8_000L) { deferred.await() } ?: false
        if (pendingWrite === deferred) {
            pendingWrite = null
        }
        return result
    }

    private suspend fun awaitDescriptorWrite(): Boolean {
        pendingDescriptorWriteResult?.let { result ->
            pendingDescriptorWriteResult = null
            return result
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingDescriptorWrite = deferred
        pendingDescriptorWriteResult?.let { result ->
            if (pendingDescriptorWrite === deferred) {
                pendingDescriptorWrite = null
            }
            pendingDescriptorWriteResult = null
            return result
        }
        val result = withTimeoutOrNull(8_000L) { deferred.await() } ?: false
        if (pendingDescriptorWrite === deferred) {
            pendingDescriptorWrite = null
        }
        return result
    }

    private fun currentGattWriteChunkSize(): Int {
        return (negotiatedMtu - P2P_CHAT_ATT_WRITE_OVERHEAD_BYTES)
            .coerceIn(P2P_CHAT_LEGACY_CHUNK_SIZE, P2P_CHAT_MAX_CHUNK_SIZE)
    }

    private fun handleIdentityValue(gatt: BluetoothGatt, value: ByteArray) {
        clearPhaseTimeout()
        val identity = value.toString(StandardCharsets.UTF_8).trim()
        val lower = identity.lowercase(Locale.US)
        val address = normalizeAddress(gatt.device.address)
        when {
            lower.startsWith("device:") -> {
                val remoteDeviceId = identity.removePrefix("device:").trim()
                if (!identityMatches(deviceId = remoteDeviceId, shareId = null)) {
                    activeDevice?.address?.let { rejectedAddresses += normalizeAddress(it) }
                    scheduleReconnect()
                    return
                }
                enableMessageNotifications(gatt)
            }

            lower.startsWith("share:") -> {
                val shareId = identity.removePrefix("share:").trim()
                if (!identityMatches(deviceId = null, shareId = shareId)) {
                    activeDevice?.address?.let { rejectedAddresses += normalizeAddress(it) }
                    scheduleReconnect()
                    return
                }
                val bootstrap = bootstrapCharacteristic
                if (bootstrap == null) {
                    scheduleReconnect()
                    return
                }
                setStatus(P2pGattChatStatus.Discovering)
                if (!readCharacteristicSafely(gatt, bootstrap)) {
                    Log.w(TAG, "Unable to start P2P chat bootstrap read on $address")
                    scheduleReconnect()
                    return
                }
                armPhaseTimeout(phase = "bootstrap read", address = address)
            }

            else -> {
                val bootstrap = bootstrapCharacteristic
                if (bootstrap == null) {
                    scheduleReconnect()
                    return
                }
                setStatus(P2pGattChatStatus.Discovering)
                if (!readCharacteristicSafely(gatt, bootstrap)) {
                    Log.w(TAG, "Unable to start P2P chat bootstrap fallback read on $address")
                    scheduleReconnect()
                    return
                }
                armPhaseTimeout(phase = "bootstrap read", address = address)
            }
        }
    }

    private fun handleBootstrapValue(gatt: BluetoothGatt, value: ByteArray) {
        clearPhaseTimeout()
        val identity = parseBootstrapIdentity(value) ?: run {
            scheduleReconnect()
            return
        }
        if (!identityMatches(deviceId = identity.deviceId, shareId = identity.shareId)) {
            activeDevice?.address?.let { rejectedAddresses += normalizeAddress(it) }
            scheduleReconnect()
            return
        }
        authenticatedName = identity.displayName
        scope.launch {
            persistRuntimeMetadata(identity.displayName)
        }
        enableMessageNotifications(gatt)
    }

    private fun enableMessageNotifications(gatt: BluetoothGatt) {
        val address = normalizeAddress(gatt.device.address)
        val characteristic = messageOutCharacteristic ?: run {
            Log.w(TAG, "P2P chat message-out characteristic missing on $address")
            scheduleReconnect()
            return
        }
        val descriptor = characteristic.getDescriptor(P2pBleProtocol.CLIENT_CONFIG_DESCRIPTOR_UUID) ?: run {
            Log.w(TAG, "P2P chat CCC descriptor missing on $address")
            scheduleReconnect()
            return
        }
        val notifyEnabled = setCharacteristicNotificationSafely(
            gatt = gatt,
            characteristic = characteristic,
            enabled = true
        )
        if (!notifyEnabled) {
            Log.w(TAG, "Unable to enable local P2P chat notifications on $address")
            scheduleReconnect()
            return
        }
        scope.launch {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = writeDescriptorSafely(gatt, descriptor)
            if (!started) {
                Log.w(TAG, "Unable to start P2P chat descriptor write on $address")
                scheduleReconnect()
                return@launch
            }
            armPhaseTimeout(phase = "notification subscription", address = address)
            val success = awaitDescriptorWrite()
            if (!success) {
                Log.w(TAG, "P2P chat descriptor write timed out on $address")
                scheduleReconnect()
                return@launch
            }
            clearPhaseTimeout()
            validatedIdentity = true
            setStatus(P2pGattChatStatus.Ready)
            persistRuntimeMetadata(authenticatedName)
        }
    }

    private fun parseBootstrapIdentity(value: ByteArray): P2pBootstrapIdentity? {
        val payload = runCatching { JSONObject(value.toString(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        return P2pBootstrapIdentity(
            shareId = payload.optString("shareId").trim().takeIf { it.isNotBlank() },
            deviceId = payload.optString("serverDeviceId").trim().takeIf { it.isNotBlank() },
            displayName = payload.optString("name").trim().takeIf { it.isNotBlank() }
        )
    }

    private fun identityMatches(deviceId: String?, shareId: String?): Boolean {
        val contact = currentContact ?: return false
        val expectedDeviceId = contact.remoteDeviceId.trim().takeIf { it.isNotBlank() }
        val expectedShareId = contact.bleShareId.trim().takeIf { it.isNotBlank() }
        if (expectedDeviceId != null) {
            if (deviceId.isNullOrBlank() || !expectedDeviceId.equals(deviceId, ignoreCase = true)) {
                return false
            }
        }
        if (expectedShareId != null && !shareId.isNullOrBlank()) {
            if (P2pBleProtocol.normalizeShareId(expectedShareId) != P2pBleProtocol.normalizeShareId(shareId)) {
                return false
            }
        }
        return true
    }

    private suspend fun persistRuntimeMetadata(displayName: String?) {
        val contact = currentContact ?: return
        val address = activeDevice?.address?.trim().orEmpty()
        val resolvedName = displayName?.trim().takeIf { !it.isNullOrBlank() } ?: contact.name
        if (address.isBlank()) {
            return
        }
        updateContactBleRuntimeMetadata(
            context = appContext,
            sessionCode = contact.sessionCode,
            lastKnownBleAddress = address,
            name = resolvedName
        )
    }

    private fun handleMessageNotification(value: ByteArray) {
        val packet = runCatching {
            when (val result = outputReceiver.onChunk(value)) {
                BleChunkReceiver.ChunkResult.Incomplete -> null
                is BleChunkReceiver.ChunkResult.Complete -> result.packet
                is BleChunkReceiver.ChunkResult.Rejected -> {
                    Log.w(TAG, "Rejecting P2P notification chunk reason=${result.reason}")
                    outputReceiver.reset()
                    null
                }
            }
        }.getOrElse { throwable ->
            Log.w(TAG, "Invalid P2P notification chunk", throwable)
            outputReceiver.reset()
            return
        } ?: return
        val envelopeBytes = runCatching {
            P2pBleProtocol.unwrapTransportPacket(packet, P2P_CHAT_PACKET_MAX_BYTES)
        }.getOrElse { throwable ->
            Log.w(TAG, "Invalid P2P envelope packet", throwable)
            outputReceiver.reset()
            return
        }
        val envelope = parseEnvelope(envelopeBytes) ?: return
        val contact = currentContact ?: return
        if (contact.remoteDeviceId.isNotBlank() &&
            !contact.remoteDeviceId.equals(envelope.fromDeviceId, ignoreCase = true)
        ) {
            return
        }
        val keyBytes = P2pBleProtocol.decodeBase64(contact.aesKey)?.takeIf { it.isNotEmpty() } ?: return
        val payloadBytes = decryptChatPayload(
            keyBytes = keyBytes,
            encryptedPacket = envelope.encryptedPacket
        ) ?: run {
            Log.w(TAG, "Failed to decrypt P2P chat payload — sending DECRYPT_FAIL back")
            scope.launch { sendDecryptFailAck(contact, messageId = null) }
            return
        }
        val payload = parsePayload(payloadBytes) ?: return
        scope.launch {
            persistRuntimeMetadata(payload.senderName ?: authenticatedName)
            when (payload.kind) {
                P2pBleProtocol.CHAT_KIND_DECRYPT_FAIL -> {
                    val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                    Log.w(TAG, "Remote peer failed to decrypt message ${messageId ?: "(unknown)"} — clearing AES key for plaintext fallback")
                    updateContactAesKey(appContext, contact.sessionCode, "")
                    if (messageId != null) {
                        updateLocalMessageDeliveryState(
                            context = appContext,
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

                P2pBleProtocol.CHAT_KIND_DELIVERED -> {
                    val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return@launch
                    markLocalMessagesDeliveredWithRecipient(
                        context = appContext,
                        sessionCode = contact.sessionCode,
                        messageUuids = listOf(messageId),
                        recipientLabel = payload.senderName ?: contact.name
                    )
                }

                P2pBleProtocol.CHAT_KIND_READ -> {
                    val readMessageIds = LinkedHashSet<String>().apply {
                        payload.messageId?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
                        payload.messageIds.forEach(::add)
                    }
                    if (readMessageIds.isEmpty()) {
                        markAllLocalMessagesRead(appContext, contact.sessionCode)
                    } else {
                        markLocalMessagesReadWithRecipient(
                            context = appContext,
                            sessionCode = contact.sessionCode,
                            messageUuids = readMessageIds,
                            recipientLabel = payload.senderName ?: authenticatedName ?: contact.name
                        )
                    }
                }

                P2pBleProtocol.CHAT_KIND_TEXT -> {
                    val text = payload.text?.trim().takeIf { !it.isNullOrBlank() } ?: return@launch
                    val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() }
                        ?: UUID.randomUUID().toString()
                    saveRemoteMessage(
                        context = appContext,
                        sessionCode = contact.sessionCode,
                        uuid = messageId,
                        text = text,
                        senderDisplayName = payload.senderName ?: authenticatedName,
                        senderAddress = activeDevice?.address
                    )
                    BleMessageNotifier.notifyIncoming(
                        context = appContext,
                        sessionCode = contact.sessionCode,
                        contactName = payload.senderName ?: authenticatedName ?: contact.name,
                        body = text
                    )
                    sendDeliveredReceipt(contact, messageId)
                }
                P2pBleProtocol.CHAT_KIND_VOICE_INIT,
                P2pBleProtocol.CHAT_KIND_VOICE_CHUNK,
                P2pBleProtocol.CHAT_KIND_VOICE_DONE,
                P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                    handleIncomingVoicePayload(contact, payload)
                }
                P2pBleProtocol.CHAT_KIND_IMAGE_INIT,
                P2pBleProtocol.CHAT_KIND_IMAGE_CHUNK,
                P2pBleProtocol.CHAT_KIND_IMAGE_DONE,
                P2pBleProtocol.CHAT_KIND_IMAGE_ABORT -> {
                    handleIncomingImagePayload(contact, payload)
                }
                P2pBleProtocol.CHAT_KIND_FILE_INIT,
                P2pBleProtocol.CHAT_KIND_FILE_CHUNK,
                P2pBleProtocol.CHAT_KIND_FILE_DONE,
                P2pBleProtocol.CHAT_KIND_FILE_ABORT -> {
                    handleIncomingFilePayload(contact, payload)
                }
            }
        }
    }

    private suspend fun handleIncomingVoicePayload(
        contact: Contact,
        payload: P2pDecodedChatPayload
    ) {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_VOICE_INIT -> {
                val mimeType = payload.mimeType?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val totalChunks = payload.totalChunks?.takeIf { it in 1..P2pBleProtocol.VOICE_MAX_CHUNKS } ?: return
                val duration = payload.durationMillis?.coerceAtLeast(0L) ?: 0L
                incomingVoiceTransfers[messageId] = P2pIncomingVoiceTransfer(
                    messageId = messageId,
                    mimeType = mimeType,
                    durationMillis = duration,
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
                completeIncomingVoiceTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_VOICE_DONE -> {
                val transfer = incomingVoiceTransfers[messageId] ?: return
                transfer.receivedDone = true
                completeIncomingVoiceTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_VOICE_ABORT -> {
                incomingVoiceTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingVoiceTransferIfReady(
        contact: Contact,
        transfer: P2pIncomingVoiceTransfer,
        senderName: String?
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
            val destination = voiceMessageFile(appContext, fileName)
            destination.writeBytes(fileBytes)
            saveRemoteAudioMessage(
                context = appContext,
                sessionCode = contact.sessionCode,
                uuid = transfer.messageId,
                fileName = fileName,
                audioDurationMillis = transfer.durationMillis.takeIf { it > 0L }
            )
        }
        incomingVoiceTransfers.remove(transfer.messageId)
        BleMessageNotifier.notifyIncoming(
            context = appContext,
            sessionCode = contact.sessionCode,
            contactName = senderName ?: authenticatedName ?: contact.name,
            body = appContext.getString(R.string.notification_voice_message_body)
        )
        sendDeliveredReceipt(contact, transfer.messageId)
        persistRuntimeMetadata(senderName ?: authenticatedName)
    }

    private suspend fun handleIncomingImagePayload(
        contact: Contact,
        payload: P2pDecodedChatPayload
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
                completeIncomingImageTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_IMAGE_DONE -> {
                val transfer = incomingImageTransfers[messageId] ?: return
                completeIncomingImageTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_IMAGE_ABORT -> {
                incomingImageTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingImageTransferIfReady(
        contact: Contact,
        transfer: BleImagePayload.IncomingTransfer,
        senderName: String?
    ) {
        val imageBytes = transfer.composeBytes() ?: return
        val messageId = transfer.messageId.ifBlank { UUID.randomUUID().toString() }
        val fileName = ImageFileUtils.fileNameFor(messageId, transfer.mimeType)
        val thumbnailName = ImageFileUtils.thumbnailNameFor(messageId, transfer.mimeType)
        withContext(Dispatchers.IO) {
            val destination = imageMessageFile(appContext, fileName)
            val thumbnail = imageThumbnailFile(appContext, thumbnailName)
            destination.parentFile?.mkdirs()
            destination.writeBytes(imageBytes)
            val thumbnailCreated = generateImageThumbnail(
                source = destination,
                target = thumbnail,
                mimeType = transfer.mimeType
            )
            saveRemoteImageMessage(
                context = appContext,
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
            context = appContext,
            sessionCode = contact.sessionCode,
            contactName = senderName ?: authenticatedName ?: contact.name,
            body = appContext.getString(R.string.notification_photo_message_body)
        )
        sendDeliveredReceipt(contact, messageId)
        persistRuntimeMetadata(senderName ?: authenticatedName)
    }

    private suspend fun handleIncomingFilePayload(
        contact: Contact,
        payload: P2pDecodedChatPayload
    ) {
        val messageId = payload.messageId?.trim().takeIf { !it.isNullOrBlank() } ?: return
        when (payload.kind) {
            P2pBleProtocol.CHAT_KIND_FILE_INIT -> {
                val displayName = payload.displayName?.trim().takeIf { !it.isNullOrBlank() } ?: return
                val totalBytes = payload.totalBytes?.takeIf { it in 1..P2pBleProtocol.FILE_MAX_TOTAL_BYTES } ?: return
                val totalChunks = payload.totalChunks?.takeIf { it in 1..P2pBleProtocol.FILE_MAX_CHUNKS } ?: return
                val originalSizeBytes = payload.originalSizeBytes?.takeIf { it > 0L } ?: totalBytes.toLong()
                val sha256 = payload.sha256?.takeIf { it.size == 32 } ?: return
                incomingFileTransfers[messageId] = BleFilePayload.IncomingTransfer(
                    transferId = messageId,
                    messageId = messageId,
                    displayName = displayName,
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
                completeIncomingFileTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_FILE_DONE -> {
                val transfer = incomingFileTransfers[messageId] ?: return
                completeIncomingFileTransferIfReady(contact, transfer, payload.senderName)
            }

            P2pBleProtocol.CHAT_KIND_FILE_ABORT -> {
                incomingFileTransfers.remove(messageId)
            }
        }
    }

    private suspend fun completeIncomingFileTransferIfReady(
        contact: Contact,
        transfer: BleFilePayload.IncomingTransfer,
        senderName: String?
    ) {
        val fileBytes = transfer.composeBytes() ?: return
        persistSharedDocumentLocalCopy(
            context = appContext,
            uuid = transfer.messageId,
            displayName = transfer.displayName,
            bytes = fileBytes
        ) ?: return
        incomingFileTransfers.remove(transfer.transferId)
        persistRuntimeMetadata(senderName ?: authenticatedName ?: contact.name)
    }

    private suspend fun sendDeliveredReceipt(contact: Contact, messageId: String) {
        val packet = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_DELIVERED,
            messageId = messageId,
            text = null,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return
        writePacket(packet)
    }

    private suspend fun sendDecryptFailAck(contact: Contact, messageId: String?) {
        val packet = buildTransportPacket(
            contact = contact,
            kind = P2pBleProtocol.CHAT_KIND_DECRYPT_FAIL,
            messageId = messageId,
            text = null,
            displayName = null,
            mimeType = null,
            durationMillis = null,
            width = null,
            height = null,
            originalSizeBytes = null,
            totalBytes = null,
            totalChunks = null,
            sha256 = null,
            chunkIndex = null,
            chunkBytes = null
        ) ?: return
        writePacket(packet)
    }

    private fun parseEnvelope(value: ByteArray): ParsedEnvelope? {
        val payload = runCatching { JSONObject(value.toString(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val type = payload.optString("type").trim()
        if (type != P2pBleProtocol.TYPE_CHAT_ENVELOPE) {
            return null
        }
        val fromDeviceId = payload.optString("fromDeviceId").trim().takeIf { it.isNotBlank() } ?: return null
        val encrypted = P2pBleProtocol.decodeBase64(payload.optString("payload")) ?: return null
        return ParsedEnvelope(
            fromDeviceId = fromDeviceId,
            encryptedPacket = encrypted
        )
    }

    private fun parsePayload(value: ByteArray): P2pDecodedChatPayload? {
        val payload = runCatching { JSONObject(value.toString(StandardCharsets.UTF_8)) }.getOrNull() ?: return null
        val kind = payload.optString("kind").trim().takeIf { it.isNotBlank() } ?: return null
        return P2pDecodedChatPayload(
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

    private fun base64Sha256(bytes: ByteArray): String? {
        return runCatching {
            Base64.encodeToString(
                MessageDigest.getInstance("SHA-256").digest(bytes),
                Base64.NO_WRAP
            )
        }.getOrNull()
    }

    private fun decryptChatPayload(keyBytes: ByteArray, encryptedPacket: ByteArray): ByteArray? {
        runCatching {
            return AesCipherHelper.decrypt(keyBytes, encryptedPacket)
        }
        val wrappedPacket = runCatching {
            AesCipherHelper.unwrapTransportPacket(encryptedPacket, P2P_CHAT_ENCRYPTED_MAX_BYTES)
        }.getOrNull() ?: return null
        return runCatching {
            AesCipherHelper.decrypt(keyBytes, wrappedPacket)
        }.getOrNull()
    }

    private fun hasBleScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            val fineLocationGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            fineLocationGranted || coarseLocationGranted
        }
    }

    private fun hasBleConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun remoteDeviceName(result: ScanResult): String? {
        if (!hasBleConnectPermission()) {
            return null
        }
        return runCatching {
            result.device?.name
                ?.trim()
                ?.uppercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun readCharacteristicSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching { gatt.readCharacteristic(characteristic) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun setCharacteristicNotificationSafely(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        enabled: Boolean
    ): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching {
            gatt.setCharacteristicNotification(characteristic, enabled)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorSafely(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching { gatt.writeDescriptor(descriptor) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionPrioritySafely(gatt: BluetoothGatt): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching {
            gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun requestPreferredMtuSafely(gatt: BluetoothGatt): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching { gatt.requestMtu(P2P_CHAT_PREFERRED_MTU) }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun discoverServicesSafely(gatt: BluetoothGatt): Boolean {
        if (!hasBleConnectPermission()) {
            return false
        }
        return runCatching { gatt.discoverServices() }.getOrDefault(false)
    }

    private fun clearPhaseTimeout() {
        phaseTimeoutJob?.cancel()
        phaseTimeoutJob = null
    }

    private fun armPhaseTimeout(
        phase: String,
        address: String,
        timeoutMs: Long = P2P_CHAT_PHASE_TIMEOUT_MS
    ) {
        clearPhaseTimeout()
        if (address.isBlank()) {
            return
        }
        phaseTimeoutJob = scope.launch {
            delay(timeoutMs)
            if (!shouldStayConnected) {
                return@launch
            }
            val activeAddress = normalizeAddress(activeGatt?.device?.address)
            val targetAddress = normalizeAddress(activeDevice?.address)
            if (activeAddress != address && targetAddress != address) {
                return@launch
            }
            Log.w(TAG, "P2P chat $phase timed out on $address")
            scheduleReconnect()
        }
    }

    private fun characteristicDebug(characteristic: BluetoothGattCharacteristic?): String {
        if (characteristic == null) {
            return "missing"
        }
        return "uuid=${characteristic.uuid} props=0x${characteristic.properties.toString(16)}"
    }

    @SuppressLint("MissingPermission")
    private fun refreshGattCache(gatt: BluetoothGatt, address: String) {
        runCatching {
            val method = gatt.javaClass.getMethod("refresh")
            method.isAccessible = true
            method.invoke(gatt)
            Log.d(TAG, "P2P chat GATT cache refresh invoked for $address")
        }.onFailure { error ->
            Log.w(TAG, "Unable to refresh P2P chat GATT cache for $address", error)
        }
    }

    private fun beginServiceDiscovery(gatt: BluetoothGatt, reason: String) {
        if (activeGatt !== gatt) {
            return
        }
        if (serviceDiscoveryStarted) {
            Log.d(
                TAG,
                "Ignoring duplicate P2P chat service discovery request for ${normalizeAddress(gatt.device.address)} reason=$reason"
            )
            return
        }
        serviceDiscoveryStarted = true
        val address = normalizeAddress(gatt.device.address)
        Log.d(TAG, "Starting P2P chat service discovery for $address reason=$reason")
        if (!discoverServicesSafely(gatt)) {
            serviceDiscoveryStarted = false
            Log.w(TAG, "Unable to start P2P chat service discovery for $address")
            scheduleReconnect()
            return
        }
        armPhaseTimeout(phase = "service discovery", address = address)
    }

    private fun isCurrentGatt(gatt: BluetoothGatt): Boolean {
        return activeGatt === gatt
    }

    private fun normalizeAddress(address: String?): String {
        return address?.trim()?.uppercase(Locale.US).orEmpty()
    }

    private fun preferredConnectDelayMs(failureCount: Int): Long {
        val localDeviceId = runCatching {
            LocalKeyStorage.getOrCreateP2pDeviceId(appContext).trim()
        }.getOrNull().takeIf { !it.isNullOrBlank() }
        val remoteDeviceId = currentContact?.remoteDeviceId?.trim().takeIf { !it.isNullOrBlank() }
        val baseDelay = if (
            localDeviceId != null &&
            remoteDeviceId != null &&
            localDeviceId.compareTo(remoteDeviceId, ignoreCase = true) <= 0
        ) {
            P2P_CHAT_CONNECT_FAST_DELAY_MS
        } else {
            P2P_CHAT_CONNECT_SLOW_DELAY_MS
        }
        return baseDelay + (failureCount.coerceAtLeast(0) * P2P_CHAT_TRANSIENT_RETRY_STEP_MS)
    }

    private fun handleGattConnectionFailure(
        status: Int,
        newState: Int,
        device: BluetoothDevice? = activeDevice
    ) {
        val targetDevice = device
        val address = normalizeAddress(targetDevice?.address)
        connectJob?.cancel()
        connectJob = null
        disconnectGatt(clearDevice = false)
        if (pendingConnectAddress == address) {
            pendingConnectAddress = null
        }
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (address.isNotBlank()) {
                transientConnectFailures.remove(address)
            }
            Log.d(TAG, "P2P GATT disconnected on $address; rescanning")
            scheduleReconnect()
            return
        }
        if (address.isBlank() || targetDevice == null) {
            scheduleReconnect()
            return
        }
        val isTransient = status == 133 || status == 8 || status == 19 || status == 62
        if (isTransient) {
            val attempt = (transientConnectFailures[address] ?: 0) + 1
            transientConnectFailures[address] = attempt
            if (attempt <= P2P_CHAT_MAX_TRANSIENT_CONNECT_FAILURES) {
                val retryDelay = preferredConnectDelayMs(attempt)
                Log.w(
                    TAG,
                    "Transient P2P GATT failure status=$status state=$newState on $address retry=$attempt/$P2P_CHAT_MAX_TRANSIENT_CONNECT_FAILURES delay=${retryDelay}ms"
                )
                scheduleReconnect(retryDevice = targetDevice, delayMs = retryDelay)
                return
            }
        }
        transientConnectFailures.remove(address)
        rejectedAddresses += address
        Log.w(TAG, "Rejecting P2P chat candidate $address after status=$status state=$newState")
        scheduleReconnect()
    }

    fun release() {
        detach()
    }

    private val scanListener = object : BleScanCoordinator.Listener {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = normalizeAddress(device.address)
            if (address.isBlank() || address in rejectedAddresses || activeGatt != null) {
                return
            }
            val contact = currentContact ?: return
            val expectedAddress = normalizeAddress(contact.lastKnownBleAddress)
            if (expectedAddress.isNotBlank() && expectedAddress == address) {
                Log.d(TAG, "Connecting P2P chat via cached BLE address $address")
                connect(device)
                return
            }
            val expectedShareId = contact.bleShareId.trim().takeIf { it.isNotBlank() }
            val advertisedShareId = result.scanRecord
                ?.serviceData
                ?.get(ParcelUuid(P2pBleProtocol.SERVICE_UUID))
                ?.toString(StandardCharsets.UTF_8)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
            if (expectedShareId != null && advertisedShareId != null) {
                if (P2pBleProtocol.normalizeShareId(expectedShareId) == P2pBleProtocol.normalizeShareId(advertisedShareId)) {
                    Log.d(TAG, "Connecting P2P chat via advertised shareId on $address")
                    connect(device)
                    return
                }
            }
            if (expectedShareId != null) {
                if (advertisesP2pService(result) || advertisesExpectedShareName(result, expectedShareId)) {
                    Log.d(TAG, "Connecting P2P chat via fallback advertisement match on $address")
                    connect(device)
                }
                return
            }
            if (expectedAddress.isBlank() && advertisesP2pService(result)) {
                Log.d(TAG, "Connecting P2P chat via generic P2P service on $address")
                connect(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "P2P chat scan failed: $errorCode")
            stopScanIfNeeded()
            setStatus(P2pGattChatStatus.Failed)
            scheduleReconnect()
        }
    }

    private fun advertisesP2pService(result: ScanResult): Boolean {
        val advertisedUuids = result.scanRecord?.serviceUuids ?: return false
        return advertisedUuids.any { advertised ->
            advertised.uuid == P2pBleProtocol.SERVICE_UUID
        }
    }

    private fun advertisesExpectedShareName(result: ScanResult, expectedShareId: String): Boolean {
        val expectedName = P2pBleProtocol.normalizeShareId(expectedShareId)
            .take(MAX_P2P_LOCAL_NAME_LENGTH)
        val advertisedName = result.scanRecord?.deviceName
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.isNotBlank() }
            ?: remoteDeviceName(result)
            ?: return false
        return advertisedName == expectedName
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (!isCurrentGatt(gatt)) {
                Log.d(
                    TAG,
                    "Ignoring stale P2P chat connection callback for ${normalizeAddress(gatt.device.address)} status=$status state=$newState"
                )
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                clearPhaseTimeout()
                handleGattConnectionFailure(status = status, newState = newState, device = gatt.device)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                val address = normalizeAddress(gatt.device.address)
                if (pendingConnectAddress == address) {
                    pendingConnectAddress = null
                }
                transientConnectFailures.remove(address)
                serviceDiscoveryStarted = false
                refreshedGattCacheAfterMissingService = false
                setStatus(P2pGattChatStatus.Discovering)
                requestConnectionPrioritySafely(gatt)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val mtuRequested = requestPreferredMtuSafely(gatt)
                    if (mtuRequested) {
                        armPhaseTimeout(phase = "MTU negotiation", address = address)
                    } else {
                        Log.d(TAG, "P2P chat MTU request unavailable for $address; continuing with service discovery")
                        beginServiceDiscovery(gatt, reason = "mtu unavailable")
                    }
                } else {
                    beginServiceDiscovery(gatt, reason = "legacy stack")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            val address = normalizeAddress(gatt.device.address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "P2P chat MTU request failed for $address status=$status")
                beginServiceDiscovery(gatt, reason = "mtu failed")
                return
            }
            clearPhaseTimeout()
            negotiatedMtu = mtu.coerceAtLeast(P2P_CHAT_DEFAULT_ATT_MTU)
            Log.d(
                TAG,
                "P2P chat MTU updated to $negotiatedMtu for $address"
            )
            beginServiceDiscovery(gatt, reason = "mtu negotiated")
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            clearPhaseTimeout()
            serviceDiscoveryStarted = false
            val address = normalizeAddress(gatt.device.address)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "P2P chat service discovery failed for $address status=$status")
                scheduleReconnect()
                return
            }
            val discoveredServices = runCatching { gatt.services }.getOrDefault(emptyList())
            val service = discoveredServices.firstOrNull { it.uuid == P2pBleProtocol.SERVICE_UUID }
                ?: gatt.getService(P2pBleProtocol.SERVICE_UUID)
                ?: run {
                    val discoveredSummary = discoveredServices
                        .joinToString(separator = ", ") { it.uuid.toString() }
                        .ifBlank { "none" }
                    if (!refreshedGattCacheAfterMissingService) {
                        refreshedGattCacheAfterMissingService = true
                        Log.w(
                            TAG,
                            "P2P chat service missing on $address; refreshing GATT cache. discovered=[$discoveredSummary]"
                        )
                        refreshGattCache(gatt, address)
                        scope.launch {
                            delay(P2P_CHAT_SERVICE_DISCOVERY_RETRY_DELAY_MS)
                            if (activeGatt === gatt && shouldStayConnected) {
                                beginServiceDiscovery(gatt, reason = "cache refresh")
                            }
                        }
                        return
                    }
                    Log.w(TAG, "P2P chat service still missing on $address after cache refresh")
                    scheduleReconnect()
                    return
                }
            idCharacteristic = service.getCharacteristic(P2pBleProtocol.ID_CHARACTERISTIC_UUID)
            bootstrapCharacteristic = service.getCharacteristic(P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID)
            messageInCharacteristic = service.getCharacteristic(P2pBleProtocol.MESSAGE_IN_CHARACTERISTIC_UUID)
            messageOutCharacteristic = service.getCharacteristic(P2pBleProtocol.MESSAGE_OUT_CHARACTERISTIC_UUID)
            val id = idCharacteristic
            val messageIn = messageInCharacteristic
            val messageOut = messageOutCharacteristic
            if (id == null || messageIn == null || messageOut == null) {
                Log.w(
                    TAG,
                    "P2P chat characteristics missing on $address: id=${characteristicDebug(id)} messageIn=${characteristicDebug(messageIn)} messageOut=${characteristicDebug(messageOut)} bootstrap=${characteristicDebug(bootstrapCharacteristic)}"
                )
                scheduleReconnect()
                return
            }
            Log.d(
                TAG,
                "P2P chat characteristics on $address: id=${characteristicDebug(id)} bootstrap=${characteristicDebug(bootstrapCharacteristic)} messageIn=${characteristicDebug(messageIn)} messageOut=${characteristicDebug(messageOut)}"
            )
            setStatus(P2pGattChatStatus.Discovering)
            if (!readCharacteristicSafely(gatt, id)) {
                Log.w(TAG, "Unable to start P2P chat identity read on $address")
                scheduleReconnect()
                return
            }
            armPhaseTimeout(phase = "identity read", address = address)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    TAG,
                    "P2P chat characteristic read failed on ${normalizeAddress(gatt.device.address)} uuid=${characteristic.uuid} status=$status"
                )
                scheduleReconnect()
                return
            }
            when (characteristic.uuid) {
                P2pBleProtocol.ID_CHARACTERISTIC_UUID -> handleIdentityValue(gatt, value)
                P2pBleProtocol.BOOTSTRAP_CHARACTERISTIC_UUID -> handleBootstrapValue(gatt, value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            }
            onCharacteristicRead(gatt, characteristic, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            if (characteristic.uuid == P2pBleProtocol.MESSAGE_OUT_CHARACTERISTIC_UUID) {
                handleMessageNotification(value)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            }
            onCharacteristicChanged(gatt, characteristic, characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            val result = status == BluetoothGatt.GATT_SUCCESS
            val pending = pendingWrite
            if (pending != null) {
                pending.complete(result)
                pendingWrite = null
            } else {
                pendingWriteResult = result
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (!isCurrentGatt(gatt)) {
                return
            }
            clearPhaseTimeout()
            val result = status == BluetoothGatt.GATT_SUCCESS
            val pending = pendingDescriptorWrite
            if (pending != null) {
                pending.complete(result)
                pendingDescriptorWrite = null
            } else {
                pendingDescriptorWriteResult = result
            }
        }
    }

    private data class ParsedEnvelope(
        val fromDeviceId: String,
        val encryptedPacket: ByteArray
    )
}
