package com.auralis.crisisconnect.analytics

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.auralis.crisisconnect.settingsDataStore
import com.google.firebase.crashlytics.FirebaseCrashlytics
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

    fun apply(isEnabled: Boolean) {
        enabled = isEnabled
        Analytics.setCollectionEnabled(isEnabled)

        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(isEnabled)
        if (!isEnabled) {
            // Do not retain a pre-consent crash for upload after a later opt-in.
            crashlytics.deleteUnsentReports()
        }
    }

    fun recordException(throwable: Throwable) {
        if (!enabled) return
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }
}
