package com.auralis.crisisconnect.analytics

import android.content.Context
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.auralis.crisisconnect.settingsDataStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import kotlinx.coroutines.flow.first

/**
 * Applies the user's diagnostics preference to every Firebase telemetry SDK.
 *
 * Collection is disabled in AndroidManifest.xml before Application.onCreate. The stored opt-in is
 * then applied here. Keeping the preference and SDK calls in one place prevents a settings toggle
 * from becoming cosmetic while another telemetry path continues to upload.
 */
object TelemetryConsent {
    val DIAGNOSTICS_UPLOAD_ENABLED: Preferences.Key<Boolean> =
        booleanPreferencesKey("advanced_diagnostics_upload_enabled")

    @Volatile
    private var enabled: Boolean = false

    internal fun resolveEnabled(storedPreference: Boolean?): Boolean = storedPreference ?: false

    suspend fun applyStored(context: Context) {
        val stored = context.settingsDataStore.data.first()[DIAGNOSTICS_UPLOAD_ENABLED]
        apply(resolveEnabled(stored))
    }

    @Synchronized
    fun apply(isEnabled: Boolean) {
        enabled = isEnabled
        refreshTrafficPolicy()
    }

    /** Reapplies the stored choice after connectivity changes without granting new consent. */
    @Synchronized
    internal fun refreshTrafficPolicy() {
        val isEnabled = enabled
        val canUpload = isEnabled && !DisasterTrafficController.saving.value
        Analytics.setCollectionEnabled(isEnabled)
        FirebasePerformance.getInstance().setPerformanceCollectionEnabled(canUpload)

        val crashlytics = FirebaseCrashlytics.getInstance()
        // Keep automatic upload off because an opt-out only changes that override next launch.
        // Explicitly send reports from the previous run only while consent remains enabled.
        crashlytics.setCrashlyticsCollectionEnabled(false)
        if (canUpload) {
            crashlytics.sendUnsentReports()
        } else if (!isEnabled) {
            // Do not retain a pre-consent crash for upload after a later opt-in.
            crashlytics.deleteUnsentReports()
        }
    }

    internal fun isCollectionAllowed(): Boolean = enabled && !DisasterTrafficController.saving.value

    fun recordException(throwable: Throwable) {
        if (!isCollectionAllowed()) return
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }
}
