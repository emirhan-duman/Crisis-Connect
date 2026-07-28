package com.auralis.crisisconnect.screens

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.data.observeHasBleContacts
import com.auralis.crisisconnect.messaging.PresenceReporter
import com.auralis.crisisconnect.service.gattmesh.GattMeshForegroundService
import com.auralis.crisisconnect.settingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

class AdvancedSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    data class AdvancedSettingsUiState(
        val shareLastSeenEnabled: Boolean = true,
        val publicMeshEnabled: Boolean = false,
        val gattMeshNotificationsEnabled: Boolean = true,
        val highRangeModeEnabled: Boolean = false,
        val highRangeModeForcedByBleContacts: Boolean = false,
        val batterySaverMode: Boolean = true,
        val deliveryRetryEnabled: Boolean = true,
        val diagnosticsUploadEnabled: Boolean = true,
        val experimentalFeaturesEnabled: Boolean = false,
        val isLoaded: Boolean = false,
    )

    private val appContext = getApplication<Application>()
    private val bleContactsPresent = MutableStateFlow(false)

    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(exceptionHandler) {
            observeHasBleContacts(appContext).collect { hasBleContacts ->
                bleContactsPresent.value = hasBleContacts
            }
        }
        viewModelScope.launch(exceptionHandler) {
            combine(
                appContext.settingsDataStore.data,
                bleContactsPresent
            ) { prefs, isBleForced ->
                val highRangeModeEnabled = (prefs[HIGH_RANGE_MODE_ENABLED] ?: false) || isBleForced
                AdvancedSettingsUiState(
                    shareLastSeenEnabled = prefs[PresenceReporter.SHARE_LAST_SEEN_KEY] ?: true,
                    publicMeshEnabled = prefs[PUBLIC_MESH_ENABLED] ?: false,
                    gattMeshNotificationsEnabled = prefs[GATT_MESH_NOTIFICATIONS_ENABLED] ?: true,
                    highRangeModeEnabled = highRangeModeEnabled,
                    highRangeModeForcedByBleContacts = isBleForced,
                    batterySaverMode = prefs[BATTERY_SAVER_MODE] ?: true,
                    deliveryRetryEnabled = prefs[DELIVERY_RETRY_ENABLED] ?: true,
                    diagnosticsUploadEnabled = prefs[DIAGNOSTICS_UPLOAD_ENABLED] ?: true,
                    experimentalFeaturesEnabled = prefs[EXPERIMENTAL_FEATURES_ENABLED] ?: false,
                    isLoaded = true
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun setShareLastSeenEnabled(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[PresenceReporter.SHARE_LAST_SEEN_KEY] = enabled
            }
            // Apply immediately: ON stamps presence now, OFF deletes the doc entirely.
            PresenceReporter.onSharingPreferenceChanged(appContext, enabled)
        }
    }

    fun setBatterySaverMode(enabled: Boolean) = updateBoolean(BATTERY_SAVER_MODE, enabled)

    fun setPublicMeshEnabled(enabled: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[PUBLIC_MESH_ENABLED] = enabled
            }
            syncGattMeshForegroundService(publicMeshOverride = enabled)
        }
    }

    fun setHighRangeModeEnabled(enabled: Boolean) {
        if (!enabled && bleContactsPresent.value) {
            return
        }
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[HIGH_RANGE_MODE_ENABLED] = enabled
            }
        }
    }

    fun setGattMeshNotificationsEnabled(enabled: Boolean) = updateBoolean(
        GATT_MESH_NOTIFICATIONS_ENABLED,
        enabled
    )

    fun setDeliveryRetryEnabled(enabled: Boolean) = updateBoolean(DELIVERY_RETRY_ENABLED, enabled)

    fun setDiagnosticsUploadEnabled(enabled: Boolean) = updateBoolean(DIAGNOSTICS_UPLOAD_ENABLED, enabled)

    fun setExperimentalFeaturesEnabled(enabled: Boolean) = updateBoolean(EXPERIMENTAL_FEATURES_ENABLED, enabled)

    private fun updateBoolean(key: Preferences.Key<Boolean>, value: Boolean) {
        viewModelScope.launch(exceptionHandler) {
            appContext.settingsDataStore.edit { prefs ->
                prefs[key] = value
            }
        }
    }

    private suspend fun syncGattMeshForegroundService(
        publicMeshOverride: Boolean? = null
    ) {
        val prefs = runCatching { appContext.settingsDataStore.data.first() }
            .getOrNull()
        val publicMeshEnabled = publicMeshOverride ?: (prefs?.get(PUBLIC_MESH_ENABLED) ?: false)
        if (publicMeshEnabled) {
            GattMeshForegroundService.start(appContext)
        } else {
            GattMeshForegroundService.stop(appContext)
        }
    }

    private companion object {
        private const val TAG = "AdvancedSettingsVM"
        val PUBLIC_MESH_ENABLED = booleanPreferencesKey("advanced_public_mesh_enabled")
        val GATT_MESH_NOTIFICATIONS_ENABLED = booleanPreferencesKey("advanced_gatt_mesh_notifications_enabled")
        val HIGH_RANGE_MODE_ENABLED = booleanPreferencesKey("advanced_high_range_mode_enabled")
        val BATTERY_SAVER_MODE = booleanPreferencesKey("advanced_battery_saver_mode")
        val DELIVERY_RETRY_ENABLED = booleanPreferencesKey("advanced_delivery_retry_enabled")
        val DIAGNOSTICS_UPLOAD_ENABLED = booleanPreferencesKey("advanced_diagnostics_upload_enabled")
        val EXPERIMENTAL_FEATURES_ENABLED = booleanPreferencesKey("advanced_experimental_features_enabled")
    }
}
