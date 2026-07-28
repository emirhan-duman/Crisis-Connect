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
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.local.ContactLastSeenStore
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.ai.CrisisSentinelLiteRtModelRuntime
import com.auralis.crisisconnect.ai.CrisisSentinelModelFileStore
import com.auralis.crisisconnect.ai.CrisisSentinelModelManifestCache
import com.auralis.crisisconnect.ai.CrisisSentinelModelRelease
import com.auralis.crisisconnect.ai.CrisisSentinelOfflineEngine
import com.auralis.crisisconnect.ai.CrisisSentinelRequest
import com.auralis.crisisconnect.ai.CrisisSentinelResponse
import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import com.auralis.crisisconnect.service.client.BleClientManager
import com.auralis.crisisconnect.service.mesh.MeshPeerAuthEvent
import com.auralis.crisisconnect.service.gattmesh.GattMeshServiceBinding
import com.auralis.crisisconnect.service.gattmesh.MeshProfiles
import com.auralis.crisisconnect.service.mesh.AuthorityMeshServiceController
import com.auralis.crisisconnect.data.BleBroadcastDirectory
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.updateContactAddress
import com.auralis.crisisconnect.security.CertificateProvisioningNotifier
import com.auralis.crisisconnect.security.SecurityRepository
import com.auralis.crisisconnect.service.BleRadioPolicy
import com.auralis.crisisconnect.service.RemoteSosSignals
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

    /**
     * Real SOS lifecycle of a victim entry, independent of BLE link health ([SOSBroadcast.status]):
     * ACTIVE = a declared (12-byte) SOS advert is being seen; ACKNOWLEDGED = this rescuer reached
     * the victim (link connected at least once); CLEARED = the node switched to a chat-host-only
     * advert, i.e. the victim stopped the SOS.
     */
    enum class SosState { ACTIVE, ACKNOWLEDGED, CLEARED }

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
        val isSosVictim: Boolean = false,
        val sosState: SosState = SosState.ACTIVE,
        val clearedAtMillis: Long? = null,
    )

    data class RescueUiState(
        val broadcasts: List<SOSBroadcast> = emptyList(),
        val remoteSignals: List<RemoteSosSignals.RemoteSignal> = emptyList(),
        val isScanning: Boolean = false,
        val errorMessage: Int? = null,
        val canControlMesh: Boolean = false,
        val isMeshEnabled: Boolean = false,
        val isMeshBusy: Boolean = false,
        val meshConnectedPeerCount: Int = 0,
        val meshErrorMessage: Int? = null,
        val peerAuthEvent: MeshPeerAuthEvent? = null,
        val lastUpdated: Long? = null,
        val canUseFieldAi: Boolean = false,
        val fieldAiMode: CrisisSentinelUserMode = CrisisSentinelUserMode.FieldTeam,
        val fieldAiCertificateRole: String? = null,
        val fieldAiPrompt: String = "",
        val fieldAiResponse: CrisisSentinelResponse? = null,
        val isFieldAiGenerating: Boolean = false,
        val isFieldAiModelReady: Boolean = false,
        val fieldAiMessage: Int? = null,
    )

    private val context = application.applicationContext
    // Enablement flag the authority mesh foreground service self-gates on. The rescue toggle is the
    // source of truth, so it must persist this — see setMeshEnabled().
    private val authorityMeshEnabledKey =
        booleanPreferencesKey(MeshProfiles.AUTHORITY.enabledPrefKey)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val crisisServiceUuid: UUID = UUIDGenerator.fromAssignedNumber(SERVICE_ASSIGNED_NUMBER)
    private val gattMeshServiceUuid: UUID = UUID.fromString(GATT_MESH_SERVICE_UUID)
    private val serviceBinding = RescueClientServiceBinding(context)
    private val meshServiceBinding = GattMeshServiceBinding(context, AuthorityMeshServiceController)
    private val securityRepository = SecurityRepository(context)
    private val modelStore = CrisisSentinelModelFileStore(context)
    private val modelManifestCache = CrisisSentinelModelManifestCache(context)
    private var currentAiRelease: CrisisSentinelModelRelease =
        modelManifestCache.load() ?: CrisisSentinelModelFileStore.defaultRelease
    private val fieldAiRuntime = CrisisSentinelLiteRtModelRuntime(
        context = context,
        store = modelStore,
        releaseProvider = { currentAiRelease }
    )
    private val fieldAiEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        CrisisSentinelOfflineEngine(
            modelRuntime = fieldAiRuntime,
            onModelFailure = { throwable ->
                Log.w(TAG, "Rescue field AI local model generation failed", throwable)
            },
            onModelRejected = { preview ->
                Log.w(TAG, "Rescue field AI local model output rejected. preview=${preview.take(160)}")
            }
        )
    }

    private val _uiState = MutableStateFlow(RescueUiState())
    val uiState: StateFlow<RescueUiState> = _uiState.asStateFlow()

    private val discovered = mutableMapOf<String, SOSBroadcast>()
    private val connectionStatuses = mutableMapOf<String, BleClientManager.ConnectionStatus>()

    private var cleanupJob: Job? = null
    private var managerJob: Job? = null
    private var meshStateCollector: Job? = null
    private var fieldAiJob: Job? = null
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
            refreshRoleAuthorization(attemptProvisioning = true)
        }
        viewModelScope.launch(exceptionHandler) {
            // The app auto-provisions the role certificate in the background at sign-in. If it
            // completes while this screen is open, lift the "unauthorized" gate immediately.
            CertificateProvisioningNotifier.events.collect { event ->
                if (event is CertificateProvisioningNotifier.Event.Success && !canControlMesh) {
                    refreshRoleAuthorization(attemptProvisioning = false)
                }
            }
        }
        observeMeshState()
        observeRemoteSignals()
    }

    private fun observeRemoteSignals() {
        viewModelScope.launch(exceptionHandler) {
            RemoteSosSignals.observe(context).collect { signals ->
                _uiState.update { current ->
                    // Signals already visible as a live BLE row stay in the BLE list only.
                    val bleSignalIds = current.broadcasts
                        .mapNotNull { it.broadcastId?.lowercase(java.util.Locale.US) }
                        .toSet()
                    current.copy(
                        remoteSignals = signals.filterNot { signal ->
                            signal.signalId.lowercase(java.util.Locale.US) in bleSignalIds
                        }
                    )
                }
            }
        }
    }

    private suspend fun refreshRoleAuthorization(attemptProvisioning: Boolean) {
        var role = storedRescueRole()
        if (role == null && attemptProvisioning) {
            // No usable role certificate on this device yet — the background auto-provision may
            // still be running or may have failed (offline, first run). Try the full provisioning
            // flow once before declaring the device unauthorized.
            runCatching { securityRepository.warmUpCertificate() }
            role = storedRescueRole()
        }
        val canControl = role in MESH_CONTROL_ROLES
        canControlMesh = canControl
        _uiState.update {
            it.copy(
                canControlMesh = canControl,
                canUseFieldAi = role in RESCUE_AI_ROLES,
                fieldAiMode = rescueAiModeForRole(role),
                fieldAiCertificateRole = role,
                isFieldAiModelReady = fieldAiRuntime.isReady,
                meshErrorMessage = if (canControl &&
                    it.meshErrorMessage == R.string.rescue_mesh_error_unauthorized
                ) {
                    null
                } else {
                    it.meshErrorMessage
                },
            )
        }
        if (canControl) {
            // Fetch the shared authority-mesh group key so the mesh can actually start when the
            // user enables it; without the key the foreground service self-stops immediately.
            runCatching { securityRepository.ensureAuthorityMeshGroupKey() }
            // Avoid auto-creating mesh service (and foreground notification) when toggle is off.
            meshServiceBinding.bind(createIfNeeded = false)
        } else {
            _uiState.update {
                it.copy(meshErrorMessage = R.string.rescue_mesh_error_unauthorized)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        meshServiceBinding.unbind()
        stopScanning()
        fieldAiRuntime.close()
        fieldAiJob?.cancel()
        fieldAiJob = null
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
        viewModelScope.launch(exceptionHandler) {
            if (enabled) {
                // Show progress immediately so the user doesn't think the tap was ignored and keep
                // re-tapping (the old "have to press it 2-3 times" symptom).
                _uiState.update { it.copy(isMeshBusy = true, meshErrorMessage = null) }
                // Make sure the shared group key is present BEFORE starting. The foreground service
                // self-stops at its key-guard when the key is missing, so if the async fetch hasn't
                // landed yet the toggle silently bounces back — which is exactly why it used to take
                // several taps. ensureAuthorityMeshGroupKey() is idempotent and caches the key.
                val hasKey = runCatching { securityRepository.ensureAuthorityMeshGroupKey() }
                    .getOrDefault(false)
                if (!hasKey) {
                    _uiState.update {
                        it.copy(
                            isMeshBusy = false,
                            meshErrorMessage = R.string.rescue_mesh_error_unauthorized,
                        )
                    }
                    return@launch
                }
            }
            // The authority mesh foreground service self-gates on its own settings flag
            // (advanced_authority_mesh_enabled). The rescue toggle is the enablement source of
            // truth, so the flag MUST be persisted before (re)starting the service: otherwise
            // GattMeshCommandDecider sees settingsEnabled=false on START and immediately stops the
            // service. Awaiting the write also avoids a race with the service reading the flag in
            // onStartCommand, and survives process restarts.
            runCatching {
                context.settingsDataStore.edit { prefs ->
                    prefs[authorityMeshEnabledKey] = enabled
                }
            }
            meshServiceBinding.setEnabled(enabled)
            if (!enabled) {
                _uiState.update { it.copy(isMeshBusy = false) }
            }
        }
    }

    fun setAutoConnectToScannedBroadcasts(enabled: Boolean) {
        autoConnectToScannedBroadcasts = enabled
    }

    fun refreshFieldAiAccess() {
        viewModelScope.launch(exceptionHandler) {
            val role = storedRescueRole()
            _uiState.update {
                it.copy(
                    canUseFieldAi = role in RESCUE_AI_ROLES,
                    fieldAiMode = rescueAiModeForRole(role),
                    fieldAiCertificateRole = role,
                    isFieldAiModelReady = fieldAiRuntime.isReady,
                    fieldAiMessage = null,
                )
            }
        }
    }

    fun onFieldAiPromptChanged(prompt: String) {
        _uiState.update {
            it.copy(
                fieldAiPrompt = prompt,
                fieldAiMessage = null,
            )
        }
    }

    fun generateFieldAiFromPrompt() {
        val prompt = _uiState.value.fieldAiPrompt.trim()
        if (prompt.isBlank()) {
            _uiState.update { it.copy(fieldAiMessage = R.string.crisis_sentinel_prompt_empty) }
            return
        }
        generateFieldAi(prompt)
    }

    fun generateFieldAiFromSignals() {
        val state = _uiState.value
        generateFieldAi(
            buildRescueAiSignalPrompt(
                broadcasts = state.broadcasts,
                activeStatus = context.getString(R.string.rescue_status_active),
            )
        )
    }

    fun cancelFieldAiGeneration() {
        fieldAiRuntime.cancelGeneration()
        fieldAiJob?.cancel()
        fieldAiJob = null
        _uiState.update {
            it.copy(
                isFieldAiGenerating = false,
                fieldAiMessage = R.string.crisis_sentinel_generation_cancelled,
                isFieldAiModelReady = fieldAiRuntime.isReady,
            )
        }
    }

    private fun generateFieldAi(prompt: String) {
        val state = _uiState.value
        if (!state.canUseFieldAi) {
            _uiState.update { it.copy(fieldAiMessage = R.string.rescue_ai_requires_role) }
            return
        }
        if (state.isFieldAiGenerating) return

        fieldAiJob?.cancel()
        fieldAiJob = viewModelScope.launch(exceptionHandler) {
            _uiState.update {
                it.copy(
                    isFieldAiGenerating = true,
                    fieldAiMessage = null,
                    isFieldAiModelReady = fieldAiRuntime.isReady,
                )
            }
            val current = _uiState.value
            val signalPrompt = buildRescueAiSignalPrompt(
                broadcasts = current.broadcasts,
                activeStatus = context.getString(R.string.rescue_status_active),
            )
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    fieldAiEngine.respondWithModel(
                        CrisisSentinelRequest(
                            prompt = prompt,
                            mode = current.fieldAiMode,
                            locale = Locale.getDefault(),
                            recentMessages = buildRescueAiContext(current.broadcasts)
                        )
                    )
                }
            }
            val response = result.getOrElse { throwable ->
                Log.e(TAG, "Rescue field AI response failed", throwable)
                _uiState.update {
                    it.copy(
                        isFieldAiGenerating = false,
                        fieldAiMessage = R.string.crisis_sentinel_error_response,
                        isFieldAiModelReady = fieldAiRuntime.isReady,
                    )
                }
                return@launch
            }
            _uiState.update {
                it.copy(
                    fieldAiResponse = response,
                    isFieldAiGenerating = false,
                    isFieldAiModelReady = fieldAiRuntime.isReady,
                    fieldAiPrompt = prompt.takeIf { text -> text != signalPrompt } ?: it.fieldAiPrompt,
                )
            }
        }
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
                        // Keep the "starting…" spinner (set when the toggle was tapped) until the
                        // service actually reports the mesh as enabled, so a slow group-key fetch or
                        // startup doesn't look like an ignored tap.
                        isMeshBusy = current.isMeshBusy && !meshState.isEnabled,
                        meshConnectedPeerCount = meshState.connectedPeerCount,
                        meshErrorMessage = meshState.errorMessage,
                        // peerAuthEvent is Wi-Fi Aware-specific; the GATT authority mesh exposes
                        // verified peers via meshState.connectedPeers instead. Left dormant here.
                    )
                }
            }
        }
    }

    fun consumeMeshPeerAuthEvent(event: MeshPeerAuthEvent?) {
        if (_uiState.value.peerAuthEvent == event) {
            _uiState.update { it.copy(peerAuthEvent = null) }
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
        val withSosState = if (
            state.status == BleClientManager.ConnectionStatus.Connected &&
            updated.isSosVictim &&
            updated.sosState == SosState.ACTIVE
        ) {
            // Someone on this device reached the victim — the signal is being handled.
            updated.copy(sosState = SosState.ACKNOWLEDGED)
        } else {
            updated
        }
        discovered[nodeKey] = withSosState
        if (state.status == BleClientManager.ConnectionStatus.Connected ||
            state.status == BleClientManager.ConnectionStatus.Ready
        ) {
            // Feed the BT-side "son görülme" so the chat header stays truthful offline.
            ContactLastSeenStore.record(context, withSosState.sessionCode, now)
        }
        connectionStatuses[address] = state.status
        publishBroadcasts()
        maybeResumeScanAfterAutoConnect(state.status)
    }

    private fun handleScanResult(result: ScanResult) {
        val record = result.scanRecord ?: return
        val serviceUuids = record.serviceUuids ?: return
        val crisisUuid = serviceUuids.firstOrNull { isCrisisConnectService(it.uuid) }
        // Hosting the SOS GATT server is not the same as declaring an emergency — a device with a
        // legacy BLE chat open hosts it too. Only a declared victim belongs on the rescue screen.
        val hasCrisis = crisisUuid != null && !isChatHostOnly(record, crisisUuid)
        // A chat-host advert (id + 0x00 flag) from a node we track as a victim means the SOS was
        // stopped: the shared GATT server is still up for chat, but the emergency is over.
        val isChatHostAdvert = crisisUuid != null && !hasCrisis
        val hasGattMesh = serviceUuids.any { it.uuid == gattMeshServiceUuid }
        if (!hasCrisis && !hasGattMesh) {
            if (isChatHostAdvert) {
                markVictimSosCleared(result.device.address)
            }
            return
        }
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
            val isSosVictim = (current?.isSosVictim ?: false) || hasCrisis
            val previousSosState = current?.sosState ?: SosState.ACTIVE
            val sosState = when {
                // A fresh declared advert re-activates a previously cleared victim.
                hasCrisis -> if (previousSosState == SosState.CLEARED) SosState.ACTIVE else previousSosState
                isChatHostAdvert && current?.isSosVictim == true &&
                    previousSosState != SosState.CLEARED -> SosState.CLEARED
                else -> previousSosState
            }
            val clearedAtMillis = when {
                sosState != SosState.CLEARED -> null
                previousSosState != SosState.CLEARED -> now
                else -> current?.clearedAtMillis
            }
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
                isSosVictim = isSosVictim,
                sosState = sosState,
                clearedAtMillis = clearedAtMillis,
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

    private fun markVictimSosCleared(rawAddress: String) {
        val normalizedAddress = normalizeAddress(rawAddress)
        val nodeKey = stableNodeIdFor(normalizedAddress)
        viewModelScope.launch(exceptionHandler) {
            val current = discovered[nodeKey] ?: return@launch
            if (!current.isSosVictim) return@launch
            val now = System.currentTimeMillis()
            discovered[nodeKey] = if (current.sosState == SosState.CLEARED) {
                // Still advertising as a chat host nearby — keep the cleared card fresh.
                current.copy(lastSeen = now)
            } else {
                current.copy(
                    sosState = SosState.CLEARED,
                    clearedAtMillis = now,
                    lastSeen = now,
                )
            }
            publishBroadcasts()
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
                val now = System.currentTimeMillis()
                val cutoff = now - STALE_ENTRY_TIMEOUT_MS
                val toRemove = discovered.filter { (_, entry) ->
                    if (entry.sosState == SosState.CLEARED) {
                        // Keep a cleared victim visible briefly so the rescuer sees the SOS ended
                        // instead of the card silently vanishing.
                        now - (entry.clearedAtMillis ?: entry.lastSeen) > CLEARED_RETENTION_MS
                    } else {
                        entry.lastSeen < cutoff
                    }
                }.keys
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

    private suspend fun storedRescueRole(): String? {
        return withContext(Dispatchers.IO) {
            securityRepository.getUsableStoredCertificateRole(allowExpired = true)
        }?.trim()?.lowercase(Locale.US)
    }

    /**
     * True when the advert says "this device is merely hosting the shared GATT server for a chat",
     * not "a human here pressed SOS".
     *
     * The SOS service also hosts the legacy BLE chat characteristics, so its presence alone is not a
     * declaration. Intent rides in the payload LENGTH, which keeps a real victim's advert byte-for-
     * byte what every shipped rescuer already parses: 12 bytes = emergency, 13 = id + flag.
     *
     * Unknown flag values deliberately read as EMERGENCY, not chat-host: if a future build adds a
     * third mode, an old rescuer must over-report rather than silently drop a real victim from the
     * list. Hiding someone who is actually in danger is the one failure worse than a phantom.
     */
    private fun isChatHostOnly(record: android.bluetooth.le.ScanRecord, serviceUuid: android.os.ParcelUuid): Boolean {
        val data = record.getServiceData(serviceUuid) ?: return false
        if (data.size != BROADCAST_ID_BYTE_SIZE + 1) return false
        return data[BROADCAST_ID_BYTE_SIZE] == INTENT_FLAG_CHAT_HOST
    }

    private fun parseBroadcastId(record: android.bluetooth.le.ScanRecord, serviceUuid: android.os.ParcelUuid): String? {
        val data = record.getServiceData(serviceUuid) ?: return null
        if (data.size == BROADCAST_ID_BYTE_SIZE) {
            return "CC-${data.toHexString().uppercase(Locale.US)}"
        }
        // Same id, plus the trailing intent flag. Parsed so a chat host stays resolvable by session
        // code (an in-flight conversation must survive the peer ceasing to declare) even though it is
        // filtered out of the victim list above.
        if (data.size == BROADCAST_ID_BYTE_SIZE + 1) {
            return "CC-${data.copyOf(BROADCAST_ID_BYTE_SIZE).toHexString().uppercase(Locale.US)}"
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

    /**
     * Only 0xCC00 is the SOS service.
     *
     * This used to prefix-match "0000cc", which also swallows 0xCCA1 (the authority mesh) and 0xCCA6
     * (the SPAKE2 nearby-contact pairing advertised by anyone sitting on the add-contact screen) —
     * so people who had not pressed SOS, and were in no danger at all, were listed to rescuers as
     * victims. The 16-bit-derived UUIDs are all built the same way, so an exact comparison is both
     * correct and cheaper than the string work it replaces.
     */
    private fun isCrisisConnectService(uuid: UUID): Boolean = uuid == crisisServiceUuid

    internal enum class BroadcastMode {
        SOS,
        MESH,
        DUAL
    }

    companion object {
        private const val TAG = "RescueScreenVM"
        private const val SERVICE_ASSIGNED_NUMBER = 0xCC00
        /** Mirrors GattSOSServerService.INTENT_FLAG_CHAT_HOST — "server up for chat, not an SOS". */
        private const val INTENT_FLAG_CHAT_HOST: Byte = 0x00
        private const val GATT_MESH_SERVICE_UUID = "6f4b5d5e-2e0a-4f13-9b89-7d9f3f1d1001"
        private const val BROADCAST_ID_BYTE_SIZE = 12
        private const val BROADCAST_ID_HEX_LENGTH = 24
        private const val MIN_NODE_ID_HEX_LENGTH = 8
        private const val MAX_NODE_ID_HEX_LENGTH = 24
        private const val CLEANUP_INTERVAL_MS = 15_000L
        private const val STALE_ENTRY_TIMEOUT_MS = 20_000L
        private const val CLEARED_RETENTION_MS = 120_000L
        private const val AUTO_CONNECT_SCAN_RESUME_TIMEOUT_MS = 12_000L
        private val MESH_CONTROL_ROLES = setOf("admin", "fieldteam")
        private val RESCUE_AI_ROLES = setOf("admin", "fieldteam")

        internal fun rescueAiModeForRole(role: String?): CrisisSentinelUserMode {
            return when (role?.trim()?.lowercase(Locale.US)) {
                "admin" -> CrisisSentinelUserMode.Coordinator
                "fieldteam" -> CrisisSentinelUserMode.FieldTeam
                else -> CrisisSentinelUserMode.Public
            }
        }

        internal fun buildRescueAiSignalPrompt(
            broadcasts: List<SOSBroadcast>,
            activeStatus: String,
        ): String {
            if (broadcasts.isEmpty()) {
                return "Saha ekibiyim. Yakında rescue sinyali görünmüyor; tarama durumu, eksik bilgiler ve güvenli takip adımlarını içeren kısa SITREP kontrol listesi hazırla."
            }
            val activeCount = broadcasts.count { it.isSosVictim && it.sosState != SosState.CLEARED }
            val signalLines = broadcasts
                .sortedByDescending { it.lastSeen }
                .take(5)
                .mapIndexed { index, broadcast ->
                    val rssi = broadcast.rssi?.toString() ?: "bilinmiyor"
                    "${index + 1}) ${broadcast.userId.ifBlank { "Bilinmeyen" }}; " +
                        "durum=${broadcast.status}; kanal=${broadcast.channelId}; " +
                        "sinyal=$rssi; oturum=${broadcast.sessionCode}"
                }
                .joinToString("\n")
            return "Saha ekibiyim. Rescue ekranında $activeCount aktif / ${broadcasts.size} toplam sinyal var. " +
                "Aşağıdaki sinyaller için kısa SITREP taslağı, riskler, eksik bilgiler ve takip soruları hazırla. " +
                "Konum veya yaralı sayısı doğrulanmamışsa uydurma.\n$signalLines"
        }

        internal fun buildRescueAiContext(broadcasts: List<SOSBroadcast>): List<String> {
            if (broadcasts.isEmpty()) {
                return listOf("Rescue scan context: no visible nearby rescue signal.")
            }
            val recent = broadcasts
                .sortedByDescending { it.lastSeen }
                .take(5)
                .joinToString(" | ") { broadcast ->
                    val rssi = broadcast.rssi?.toString() ?: "unknown"
                    "${broadcast.channelId} status=${broadcast.status} rssi=$rssi session=${broadcast.sessionCode}"
                }
            return listOf("Rescue scan context: $recent")
        }

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
