package com.auralis.crisisconnect.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.mesh.MeshPeerAuthEvent
import com.auralis.crisisconnect.service.mesh.MeshAwareServiceBinding
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.BleRadioPolicy
import com.auralis.crisisconnect.service.client.RescueClientServiceBinding
import com.auralis.crisisconnect.service.scan.BleScanCoordinator
import com.auralis.crisisconnect.util.UUIDGenerator
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.withContext
import com.google.firebase.crashlytics.FirebaseCrashlytics

class RescueScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    data class SOSBroadcast(
        val address: String,
        val sessionCode: String,
        val broadcastId: String?,
        val channelId: String,
        val userId: String,
        val status: String,
        val deviceName: String?,
        val serviceUuid: UUID?,
        val rssi: Int?,
        val lastSeen: Long,
        val lastUpdated: Long,
    )

    data class RescueUiState(
        val broadcasts: List<SOSBroadcast> = emptyList(),
        val isScanning: Boolean = false,
        val errorMessage: Int? = null,
        val canControlMesh: Boolean = false,
        val isMeshEnabled: Boolean = false,
        val isMeshBusy: Boolean = false,
        val meshConnectedPeerCount: Int = 0,
        val meshErrorMessage: Int? = null,
        val peerAuthEvent: MeshPeerAuthEvent? = null,
        val lastUpdated: Long? = null,
    )

    private val context = application.applicationContext
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val crisisServiceUuid: UUID = UUIDGenerator.fromAssignedNumber(SERVICE_ASSIGNED_NUMBER)
    private val gattMeshServiceUuid: UUID = UUID.fromString(GATT_MESH_SERVICE_UUID)
    private val serviceBinding = RescueClientServiceBinding(context)
    private val meshServiceBinding = MeshAwareServiceBinding(context)
    private val securityRepository = SecurityRepository(context)

    private val _uiState = MutableStateFlow(RescueUiState())
    val uiState: StateFlow<RescueUiState> = _uiState.asStateFlow()

    private val discovered = mutableMapOf<String, SOSBroadcast>()
    private val connectionStatuses = mutableMapOf<String, BleClientManager.ConnectionStatus>()

    private var cleanupJob: Job? = null
    private var managerJob: Job? = null
    private var meshStateCollector: Job? = null
    private var canControlMesh: Boolean = false
    private var autoConnectToScannedBroadcasts: Boolean = true
    @Volatile
    private var resumeScanAfterAutoConnect: Boolean = false
    private var autoConnectScanResumeJob: Job? = null
    private val nodeIdHexLength: Int = BuildConfig.RESCUE_NODE_ID_HEX_LENGTH
        .coerceIn(MIN_NODE_ID_HEX_LENGTH, MAX_NODE_ID_HEX_LENGTH)
    private val scanOwnerId = "rescue-screen-${System.identityHashCode(this)}"

    private val scanListener = object : BleScanCoordinator.Listener {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_scan_failed,
                )
            }
            stopBleScan()
        }
    }

    init {
        observeManager()
        viewModelScope.launch(exceptionHandler) {
            val canControl = hasMeshControlRole()
            canControlMesh = canControl
            _uiState.update { it.copy(canControlMesh = canControl) }
            if (canControl) {
                // Avoid auto-creating mesh service (and foreground notification) when toggle is off.
                meshServiceBinding.bind(createIfNeeded = false)
            } else {
                _uiState.update {
                    it.copy(meshErrorMessage = R.string.rescue_mesh_error_unauthorized)
                }
            }
        }
        observeMeshState()
    }

    override fun onCleared() {
        super.onCleared()
        meshServiceBinding.unbind()
        stopScanning()
        autoConnectScanResumeJob?.cancel()
        autoConnectScanResumeJob = null
        managerJob?.cancel()
        meshStateCollector?.cancel()
        serviceBinding.unbind()
    }

    fun startScanning() {
        if (_uiState.value.isScanning) return
        autoConnectScanResumeJob?.cancel()
        autoConnectScanResumeJob = null
        resumeScanAfterAutoConnect = false
        serviceBinding.bind()
        startBleScan(clearDiscovered = true)
    }

    fun refresh() {
        startScanning()
    }

    fun stopScanning() {
        autoConnectScanResumeJob?.cancel()
        autoConnectScanResumeJob = null
        resumeScanAfterAutoConnect = false
        stopBleScan()
    }

    fun setMeshEnabled(enabled: Boolean) {
        if (!canControlMesh) return
        meshServiceBinding.setMeshEnabled(enabled)
    }

    fun setAutoConnectToScannedBroadcasts(enabled: Boolean) {
        autoConnectToScannedBroadcasts = enabled
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(clearDiscovered: Boolean) {
        val adapter = bluetoothManager?.adapter ?: run {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_bluetooth_unavailable,
                    broadcasts = emptyList(),
                    lastUpdated = System.currentTimeMillis(),
                )
            }
            return
        }

        if (!adapter.isEnabled) {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_bluetooth_disabled,
                )
            }
            return
        }

        if (!hasRequiredPermissions()) {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_permission_required,
                )
            }
            return
        }

        val scanner: BluetoothLeScanner = adapter.bluetoothLeScanner ?: run {
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_bluetooth_unavailable,
                )
            }
            return
        }

        if (clearDiscovered) {
            discovered.clear()
            publishBroadcasts()
        }

        _uiState.update {
            it.copy(isScanning = true, errorMessage = null)
        }

        try {
            val scanMode = BleRadioPolicy.resolve(
                context = context,
                preferPerformance = autoConnectToScannedBroadcasts
            ).scanMode
            Log.i(TAG, "Starting BLE scan with mode=${scanModeLabel(scanMode)}")
            val started = BleScanCoordinator.registerOrUpdate(
                owner = scanOwnerId,
                scanner = scanner,
                mode = scanMode,
                filters = null,
                listener = scanListener
            )
            if (!started) {
                throw IllegalStateException("Unable to start coordinated BLE scan")
            }
            ensureCleanupJob()
        } catch (security: SecurityException) {
            Log.e(TAG, "Missing permission while starting BLE scan", security)
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_permission_required,
                )
            }
            stopBleScan()
        } catch (throwable: Throwable) {
            Log.e(TAG, "Unable to start BLE scan", throwable)
            _uiState.update {
                it.copy(
                    isScanning = false,
                    errorMessage = R.string.rescue_error_scan_failed,
                )
            }
            stopBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        val adapter = bluetoothManager?.adapter
        if (adapter != null && hasScanPermissionOnly()) {
            BleScanCoordinator.unregister(scanOwnerId)
        }
        cleanupJob?.cancel()
        cleanupJob = null
        _uiState.update { it.copy(isScanning = false) }
    }

    private fun observeManager() {
        managerJob?.cancel()
        managerJob = viewModelScope.launch(exceptionHandler) {
            serviceBinding.manager.collectLatest { manager ->
                if (manager == null) return@collectLatest
                manager.connectionStates.collect { state ->
                    handleConnectionState(state)
                }
            }
        }
    }

    private fun observeMeshState() {
        meshStateCollector?.cancel()
        meshStateCollector = viewModelScope.launch(exceptionHandler) {
            meshServiceBinding.state.collectLatest { meshState ->
                _uiState.update { current ->
                    current.copy(
                        isMeshEnabled = meshState.isEnabled,
                        isMeshBusy = meshState.isBusy,
                        meshConnectedPeerCount = meshState.connectedPeerCount,
                        meshErrorMessage = meshState.errorMessage,
                        peerAuthEvent = meshState.peerAuthEvent,
                    )
                }
            }
        }
    }

    fun consumeMeshPeerAuthEvent(event: MeshPeerAuthEvent?) {
        if (_uiState.value.peerAuthEvent == event) {
            _uiState.update { it.copy(peerAuthEvent = null) }
            meshServiceBinding.clearPeerAuthEvent()
        }
    }

    private fun handleConnectionState(state: BleClientManager.ConnectionState) {
        val address = normalizeAddress(state.address)
        val nodeKey = stableNodeIdFor(address)
        val now = System.currentTimeMillis()
        val current = discovered[nodeKey] ?: run {
            maybeResumeScanAfterAutoConnect(state.status)
            return
        }
        val updated = updateBroadcastFromConnectionState(
            current = current,
            address = address,
            sessionCode = BleSessionResolver.sessionCodeForAddress(address),
            broadcastId = BleBroadcastDirectory.resolveBroadcastId(address),
            status = statusLabel(state.status),
            deviceName = state.deviceName,
            userId = state.userId,
            now = now,
        )
        discovered[nodeKey] = updated
        connectionStatuses[address] = state.status
        publishBroadcasts()
        maybeResumeScanAfterAutoConnect(state.status)
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceUuids = record.serviceUuids ?: return
        val crisisUuid = serviceUuids.firstOrNull { isCrisisConnectService(it.uuid) }
        val hasCrisis = crisisUuid != null
        val hasGattMesh = serviceUuids.any { it.uuid == gattMeshServiceUuid }
        if (!hasCrisis && !hasGattMesh) return
        val mode = when {
            hasCrisis && hasGattMesh -> BroadcastMode.DUAL
            hasCrisis -> BroadcastMode.SOS
            else -> BroadcastMode.MESH
        }
        val normalizedAddress = normalizeAddress(result.device.address)
        val now = System.currentTimeMillis()
        val deviceName = readDeviceName(result)
        val broadcastId = if (hasCrisis) parseBroadcastId(record, crisisUuid!!) else null
        if (broadcastId != null) {
            BleBroadcastDirectory.update(broadcastId, normalizedAddress, now)
        }
        val nodeKey = stableNodeIdFor(normalizedAddress)
        val sessionCode = BleSessionResolver.sessionCodeForAddress(normalizedAddress)
        val primaryServiceUuid = when {
            hasCrisis -> crisisUuid!!.uuid
            else -> gattMeshServiceUuid
        }

        viewModelScope.launch(exceptionHandler) {
            val current = discovered[nodeKey]
            val mergedMode = reconcileBroadcastModeForScan(
                current?.channelId?.let { parseModeFromChannelId(it) },
                mode
            )
            val resolvedBroadcastId = broadcastId ?: current?.broadcastId
            val defaultStatus = if (autoConnectToScannedBroadcasts && hasCrisis) {
                BleClientManager.ConnectionStatus.Connecting
            } else {
                // Auto-connect is off, so no connection attempt was made yet.
                BleClientManager.ConnectionStatus.Ready
            }
            val updated = (current ?: SOSBroadcast(
                address = normalizedAddress,
                sessionCode = sessionCode,
                broadcastId = resolvedBroadcastId,
                channelId = buildChannelId(
                    mode = mergedMode,
                    address = normalizedAddress
                ),
                userId = context.getString(R.string.rescue_unknown_user),
                status = statusLabel(defaultStatus),
                deviceName = deviceName,
                serviceUuid = primaryServiceUuid,
                rssi = result.rssi,
                lastSeen = now,
                lastUpdated = now,
            )).copy(
                deviceName = deviceName ?: current?.deviceName,
                serviceUuid = primaryServiceUuid,
                rssi = result.rssi,
                lastSeen = now,
                broadcastId = resolvedBroadcastId,
                channelId = buildChannelId(
                    mode = mergedMode,
                    address = normalizedAddress
                ),
                sessionCode = sessionCode,
            )
            discovered[nodeKey] = updated
            publishBroadcasts()
            if (broadcastId != null) {
                withContext(Dispatchers.IO) {
                    updateContactAddress(context, sessionCode, normalizedAddress)
                }
            }
            if (!autoConnectToScannedBroadcasts || !hasCrisis) return@launch
            val manager = serviceBinding.manager.value ?: return@launch
            val status = connectionStatuses[normalizedAddress]
            if (shouldAutoConnect(status)) {
                connectionStatuses[normalizedAddress] = BleClientManager.ConnectionStatus.Connecting
                pauseScanForAutoConnect()
                manager.connectTo(normalizedAddress)
            }
        }
    }

    private fun pauseScanForAutoConnect() {
        if (!_uiState.value.isScanning) {
            return
        }
        stopBleScan()
        resumeScanAfterAutoConnect = true
        autoConnectScanResumeJob?.cancel()
        autoConnectScanResumeJob = viewModelScope.launch(exceptionHandler) {
            delay(AUTO_CONNECT_SCAN_RESUME_TIMEOUT_MS)
            if (!resumeScanAfterAutoConnect || _uiState.value.isScanning) {
                return@launch
            }
            Log.d(TAG, "Auto-connect scan pause timeout reached; resuming BLE scan")
            resumeScanAfterAutoConnect = false
            startBleScan(clearDiscovered = false)
        }
        Log.d(TAG, "Paused BLE scan during SOS auto-connect attempt")
    }

    private fun maybeResumeScanAfterAutoConnect(status: BleClientManager.ConnectionStatus) {
        if (!resumeScanAfterAutoConnect || _uiState.value.isScanning) {
            return
        }
        when (status) {
            BleClientManager.ConnectionStatus.Ready,
            BleClientManager.ConnectionStatus.Failed -> {
                autoConnectScanResumeJob?.cancel()
                autoConnectScanResumeJob = null
                resumeScanAfterAutoConnect = false
                Log.d(TAG, "Auto-connect settled with status=$status; resuming BLE scan")
                startBleScan(clearDiscovered = false)
            }

            else -> Unit
        }
    }

    private fun publishBroadcasts() {
        val sorted = discovered.values.sortedByDescending { it.lastSeen }
        _uiState.update {
            it.copy(
                broadcasts = sorted,
                lastUpdated = if (sorted.isEmpty()) null else System.currentTimeMillis(),
            )
        }
    }

    private fun ensureCleanupJob() {
        if (cleanupJob?.isActive == true) return
        cleanupJob = viewModelScope.launch(exceptionHandler) {
            while (true) {
                delay(CLEANUP_INTERVAL_MS)
                val cutoff = System.currentTimeMillis() - STALE_ENTRY_TIMEOUT_MS
                val toRemove = discovered.filter { it.value.lastSeen < cutoff }.keys
                if (toRemove.isNotEmpty()) {
                    toRemove.forEach { key ->
                        val entry = discovered.remove(key)
                        entry?.broadcastId?.let { BleBroadcastDirectory.remove(it) }
                        entry?.address?.let { connectionStatuses.remove(it) }
                    }
                    publishBroadcasts()
                }
            }
        }
    }

    private fun statusLabel(status: BleClientManager.ConnectionStatus): String {
        return when (status) {
            BleClientManager.ConnectionStatus.Ready -> context.getString(R.string.ble_status_ready)
            BleClientManager.ConnectionStatus.Connected -> context.getString(R.string.ble_status_connected)
            BleClientManager.ConnectionStatus.Connecting -> context.getString(R.string.ble_status_connecting)
            BleClientManager.ConnectionStatus.Discovering -> context.getString(R.string.ble_status_discovering)
            BleClientManager.ConnectionStatus.Authenticating -> context.getString(R.string.ble_status_authenticating)
            BleClientManager.ConnectionStatus.Reconnecting -> context.getString(R.string.ble_status_reconnecting)
            BleClientManager.ConnectionStatus.Disconnected -> context.getString(R.string.ble_status_disconnected)
            BleClientManager.ConnectionStatus.Failed -> context.getString(R.string.ble_status_failed)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
            val connect = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
            val fineLocation = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            scan && connect && fineLocation
        } else {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun hasScanPermissionOnly(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceName(result: ScanResult): String? {
        if (!hasConnectPermission()) {
            return null
        }
        return runCatching { result.device.name }.getOrNull()
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    private fun scanModeLabel(mode: Int): String {
        return when (mode) {
            ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
            ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
            ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
            ScanSettings.SCAN_MODE_OPPORTUNISTIC -> "OPPORTUNISTIC"
            else -> "UNKNOWN($mode)"
        }
    }

    private fun buildChannelId(
        mode: BroadcastMode,
        address: String
    ): String {
        val base = stableNodeIdFor(address)
        val prefix = when (mode) {
            BroadcastMode.SOS -> "SOS"
            BroadcastMode.MESH -> "MESH"
            BroadcastMode.DUAL -> "DUAL"
        }
        return "$prefix-$base"
    }

    private fun stableNodeIdFor(address: String): String {
        val normalized = normalizeAddress(address)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        val id = digest.toHexString()
            .take(nodeIdHexLength)
            .uppercase(Locale.US)
        return "ND-$id"
    }

    private fun shouldAutoConnect(status: BleClientManager.ConnectionStatus?): Boolean {
        return status == null ||
            status == BleClientManager.ConnectionStatus.Disconnected ||
            status == BleClientManager.ConnectionStatus.Failed
    }

    private fun parseModeFromChannelId(channelId: String): BroadcastMode {
        return when {
            channelId.startsWith("DUAL-", ignoreCase = true) -> BroadcastMode.DUAL
            channelId.startsWith("MESH-", ignoreCase = true) -> BroadcastMode.MESH
            else -> BroadcastMode.SOS
        }
    }

    private suspend fun hasMeshControlRole(): Boolean {
        val cachedRole = withContext(Dispatchers.IO) {
            securityRepository.getUsableStoredCertificateRole(allowExpired = true)
        }
        return cachedRole?.trim()?.lowercase(Locale.US)?.let { it in MESH_CONTROL_ROLES } == true
    }

    private fun parseBroadcastId(record: android.bluetooth.le.ScanRecord, serviceUuid: android.os.ParcelUuid): String? {
        val data = record.getServiceData(serviceUuid) ?: return null
        if (data.size == BROADCAST_ID_BYTE_SIZE) {
            return "CC-${data.toHexString().uppercase(Locale.US)}"
        }
        val raw = runCatching { data.toString(Charsets.UTF_8) }.getOrNull()?.trim() ?: return null
        if (raw.isBlank()) return null
        val cleaned = raw.removePrefix("ccid:").removePrefix("CCID:").trim()
        val normalized = cleaned.lowercase(Locale.US)
        if (!normalized.startsWith("cc-")) {
            return null
        }
        val hex = normalized.removePrefix("cc-")
        if (hex.length != BROADCAST_ID_HEX_LENGTH || hex.any { it !in '0'..'9' && it !in 'a'..'f' }) {
            return null
        }
        return "CC-${hex.uppercase(Locale.US)}"
    }

    private fun ByteArray.toHexString(): String {
        val chars = CharArray(size * 2)
        val digits = "0123456789abcdef".toCharArray()
        var index = 0
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            chars[index++] = digits[value ushr 4]
            chars[index++] = digits[value and 0x0F]
        }
        return String(chars)
    }

    private fun isCrisisConnectService(uuid: UUID): Boolean {
        val normalized = uuid.toString().lowercase(Locale.ROOT)
        return normalized.startsWith("0000cc") && normalized.endsWith("-0000-1000-8000-00805f9b34fb")
    }

    internal enum class BroadcastMode {
        SOS,
        MESH,
        DUAL
    }

    companion object {
        private const val TAG = "RescueScreenVM"
        private const val SERVICE_ASSIGNED_NUMBER = 0xCC00
        private const val GATT_MESH_SERVICE_UUID = "6f4b5d5e-2e0a-4f13-9b89-7d9f3f1d1001"
        private const val BROADCAST_ID_BYTE_SIZE = 12
        private const val BROADCAST_ID_HEX_LENGTH = 24
        private const val MIN_NODE_ID_HEX_LENGTH = 8
        private const val MAX_NODE_ID_HEX_LENGTH = 24
        private const val CLEANUP_INTERVAL_MS = 15_000L
        private const val STALE_ENTRY_TIMEOUT_MS = 20_000L
        private const val AUTO_CONNECT_SCAN_RESUME_TIMEOUT_MS = 12_000L
        private val MESH_CONTROL_ROLES = setOf("admin", "fieldteam")

        internal fun updateBroadcastFromConnectionState(
            current: SOSBroadcast,
            address: String,
            sessionCode: String,
            broadcastId: String?,
            status: String,
            deviceName: String?,
            userId: String?,
            now: Long,
        ): SOSBroadcast {
            // Connection lifecycle updates describe transport health, not beacon freshness.
            return current.copy(
                userId = userId ?: current.userId,
                status = status,
                deviceName = deviceName ?: current.deviceName,
                broadcastId = broadcastId ?: current.broadcastId,
                sessionCode = sessionCode,
                address = address,
                lastUpdated = now,
            )
        }

        internal fun reconcileBroadcastModeForScan(
            current: BroadcastMode?,
            incoming: BroadcastMode
        ): BroadcastMode {
            if (current == null) return incoming
            return if (current == incoming) current else incoming
        }
    }
}
