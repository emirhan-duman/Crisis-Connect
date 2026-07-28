package com.auralis.crisisconnect.nearby

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.auralis.crisisconnect.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Opt-in switch for nearby contact discovery. Off by default — advertising your (rotating,
 * truncated) phone token so nearby contacts can find you only happens when the user turns it on.
 *
 * Exception: once the user has added their phone number (e.g. in the welcome flow) we flip it on
 * as a sensible default via [enableByDefaultIfUnset], because discovery only works for people who
 * already have your number. A deliberate choice via the toggle ([setEnabled]) always wins and is
 * never overridden by that default.
 */
object NearbyDiscoveryPreferences {
    private val ENABLED_KEY = booleanPreferencesKey("nearby_discovery_enabled")
    // Marks that the user made an explicit choice with the discovery toggle, so a later
    // "default on when phone added" never clobbers a deliberate opt-out.
    private val USER_SET_KEY = booleanPreferencesKey("nearby_discovery_user_set")

    fun isEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { it[ENABLED_KEY] ?: false }

    suspend fun isEnabledNow(context: Context): Boolean = isEnabled(context).first()

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.settingsDataStore.edit {
            it[ENABLED_KEY] = enabled
            it[USER_SET_KEY] = true
        }
    }

    /**
     * Turns discovery on as a default now that the user has a phone number, unless they've already
     * made an explicit choice (or it's already on). Returns true only if it actually flipped it on,
     * so the caller can bring the service up.
     */
    suspend fun enableByDefaultIfUnset(context: Context): Boolean {
        var flippedOn = false
        context.settingsDataStore.edit { prefs ->
            val userSet = prefs[USER_SET_KEY] ?: false
            val current = prefs[ENABLED_KEY] ?: false
            if (!userSet && !current) {
                prefs[ENABLED_KEY] = true
                flippedOn = true
            }
        }
        return flippedOn
    }
}
