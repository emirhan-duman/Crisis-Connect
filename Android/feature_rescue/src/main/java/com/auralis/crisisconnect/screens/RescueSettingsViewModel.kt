package com.auralis.crisisconnect.screens

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.service.CrisisLinkForegroundService
import com.auralis.crisisconnect.service.mesh.MeshAwareService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

class RescueSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    data class RescueSettingsUiState(
        val autoStartScanning: Boolean = false,
        val autoConnectToScannedBroadcasts: Boolean = true,
        val showOnlyActiveSignals: Boolean = false,
        val meshAlwaysOn: Boolean = false,
        val canUseMeshAlwaysOn: Boolean = false,
        val showDeviceAddress: Boolean = true,
        val showRescueInNavbar: Boolean = false,
        val crisisLinkEnabled: Boolean = false,
        val crisisLinkLiveLocationEnabled: Boolean = false,
        val crisisLinkLiveLocationIntervalSeconds: Int = DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS,
        val canUseCrisisLink: Boolean = false,
        val isLoaded: Boolean = false,
    )

    private val appContext = getApplication<Application>()
    private var lastAppliedCrisisLinkServiceState: Boolean? = null
    private val crisisLinkAccess = MutableStateFlow(false)
    private val meshAlwaysOnAccess = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(RescueSettingsUiState())
    val uiState: StateFlow<RescueSettingsUiState> = _uiState.asStateFlow()

    init {
        refreshCrisisLinkAccess()
        refreshMeshAlwaysOnAccess()
        viewModelScope.launch(exceptionHandler) {
            combine(appContext.settingsDataStore.data, crisisLinkAccess, meshAlwaysOnAccess) { prefs, canUseCrisisLink, canUseMeshAlwaysOn ->
                Triple(prefs, canUseCrisisLink, canUseMeshAlwaysOn)
            }.collect { (prefs, canUseCrisisLink, canUseMeshAlwaysOn) ->
                val requestedCrisisLink = prefs[CRISIS_LINK_ENABLED] ?: false
                val effectiveCrisisLink = requestedCrisisLink && canUseCrisisLink
                val liveLocationEnabled = prefs[CRISIS_LINK_LIVE_LOCATION_ENABLED] ?: false
                val liveLocationIntervalSeconds = sanitizeLiveLocationIntervalSeconds(
                    prefs[CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS]
                        ?: DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS
                )
                val requestedMeshAlwaysOn = prefs[MESH_ALWAYS_ON_ENABLED] ?: false
                val effectiveMeshAlwaysOn = requestedMeshAlwaysOn && canUseMeshAlwaysOn
                _uiState.value = RescueSettingsUiState(
                    autoStartScanning = prefs[AUTO_START_SCANNING] ?: false,
                    autoConnectToScannedBroadcasts = prefs[AUTO_CONNECT_SCANNED_BROADCASTS] ?: true,
                    showOnlyActiveSignals = prefs[SHOW_ONLY_ACTIVE_SIGNALS] ?: false,
                    meshAlwaysOn = effectiveMeshAlwaysOn,
                    canUseMeshAlwaysOn = canUseMeshAlwaysOn,
                    showDeviceAddress = prefs[SHOW_DEVICE_ADDRESS] ?: true,
                    showRescueInNavbar = prefs[SHOW_RESCUE_IN_NAVBAR] ?: false,
                    crisisLinkEnabled = effectiveCrisisLink,
                    crisisLinkLiveLocationEnabled = liveLocationEnabled,
                    crisisLinkLiveLocationIntervalSeconds = liveLocationIntervalSeconds,
                    canUseCrisisLink = canUseCrisisLink,
                    isLoaded = true,
                )
                if (requestedMeshAlwaysOn && !canUseMeshAlwaysOn) {
                    updateBoolean(MESH_ALWAYS_ON_ENABLED, false)
                }
                if (requestedCrisisLink && !canUseCrisisLink) {
                    updateBoolean(CRISIS_LINK_ENABLED, false)
                }
                if (prefs[CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS] != liveLocationIntervalSeconds) {
                    updateInt(CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS, liveLocationIntervalSeconds)
                }
                applyCrisisLinkServiceState(effectiveCrisisLink)
            }
        }
    }

    fun setAutoStartScanning(enabled: Boolean) = updateBoolean(AUTO_START_SCANNING, enabled)

    fun setAutoConnectToScannedBroadcasts(enabled: Boolean) = updateBoolean(AUTO_CONNECT_SCANNED_BROADCASTS, enabled)

    fun setShowOnlyActiveSignals(enabled: Boolean) = updateBoolean(SHOW_ONLY_ACTIVE_SIGNALS, enabled)

    fun setMeshAlwaysOnEnabled(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            if (enabled) {
                val canUse = withContext(Dispatchers.IO) {
                    MeshAwareService.canStart(appContext)
                }
                meshAlwaysOnAccess.value = canUse
                if (!canUse) {
                    updateBoolean(MESH_ALWAYS_ON_ENABLED, false)
                    return@launch
                }
            }
            updateBoolean(MESH_ALWAYS_ON_ENABLED, enabled)
        }
    }

    fun setShowDeviceAddress(enabled: Boolean) = updateBoolean(SHOW_DEVICE_ADDRESS, enabled)

    fun setShowRescueInNavbar(enabled: Boolean) = updateBoolean(SHOW_RESCUE_IN_NAVBAR, enabled)

    fun setCrisisLinkEnabled(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            if (enabled) {
                val canUse = withContext(Dispatchers.IO) {
                    CrisisLinkForegroundService.canStartSuspend(appContext)
                }
                crisisLinkAccess.value = canUse
                if (!canUse) {
                    updateBoolean(CRISIS_LINK_ENABLED, false)
                    applyCrisisLinkServiceState(false)
                    return@launch
                }
            }
            updateBoolean(CRISIS_LINK_ENABLED, enabled)
            applyCrisisLinkServiceState(enabled && crisisLinkAccess.value)
        }
    }

    fun setCrisisLinkLiveLocationEnabled(enabled: Boolean) {
        updateBoolean(CRISIS_LINK_LIVE_LOCATION_ENABLED, enabled)
    }

    fun setCrisisLinkLiveLocationIntervalSeconds(seconds: Int) {
        updateInt(CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS, sanitizeLiveLocationIntervalSeconds(seconds))
    }

    private fun updateBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    private fun updateInt(key: Preferences.Key<Int>, value: Int) {
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    private fun sanitizeLiveLocationIntervalSeconds(value: Int): Int {
        return LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS.firstOrNull { it == value }
            ?: DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS
    }

    private fun applyCrisisLinkServiceState(enabled: Boolean) {
        if (lastAppliedCrisisLinkServiceState == enabled) {
            return
        }
        lastAppliedCrisisLinkServiceState = enabled

        if (enabled) {
            runCatching {
                CrisisLinkForegroundService.start(appContext)
            }
        } else {
            runCatching {
                CrisisLinkForegroundService.stop(appContext)
            }
        }
    }

    private fun refreshCrisisLinkAccess() {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            crisisLinkAccess.value = CrisisLinkForegroundService.canStartSuspend(appContext)
        }
    }

    private fun refreshMeshAlwaysOnAccess() {
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            meshAlwaysOnAccess.value = MeshAwareService.canStart(appContext)
        }
    }

    private companion object {
        private const val TAG = "RescueSettingsVM"
        val AUTO_START_SCANNING = booleanPreferencesKey("rescue_auto_start_scanning")
        val AUTO_CONNECT_SCANNED_BROADCASTS = booleanPreferencesKey("rescue_auto_connect_scanned_broadcasts")
        val SHOW_ONLY_ACTIVE_SIGNALS = booleanPreferencesKey("rescue_show_only_active_signals")
        val MESH_ALWAYS_ON_ENABLED = booleanPreferencesKey("rescue_mesh_always_on_enabled")
        val SHOW_DEVICE_ADDRESS = booleanPreferencesKey("rescue_show_device_address")
        val SHOW_RESCUE_IN_NAVBAR = booleanPreferencesKey("rescue_show_in_navbar")
        val CRISIS_LINK_ENABLED = booleanPreferencesKey("rescue_crisis_link_enabled")
        val CRISIS_LINK_LIVE_LOCATION_ENABLED = booleanPreferencesKey("rescue_crisis_link_live_location_enabled")
        val CRISIS_LINK_LIVE_LOCATION_INTERVAL_SECONDS = intPreferencesKey("rescue_crisis_link_live_location_interval_seconds")
        val LIVE_LOCATION_INTERVAL_OPTIONS_SECONDS = listOf(30, 60, 90, 300)
        const val DEFAULT_LIVE_LOCATION_INTERVAL_SECONDS = 60
    }
}
