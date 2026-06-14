package com.auralis.crisisconnect

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Debug-only developer preferences.
 *
 * Every reader and writer is hard-gated behind [BuildConfig.DEBUG]. In release builds
 * the flag is forced to `false` regardless of what may be in the DataStore, and
 * writes are silently ignored. This guarantees that demo/debug behavior can never
 * leak into a release build even if the pref somehow survives a build-type switch.
 */

private const val KEY_SCREENSHOT_DEMO_MODE = "debug_screenshot_demo_mode"
private val SCREENSHOT_DEMO_MODE_KEY = booleanPreferencesKey(KEY_SCREENSHOT_DEMO_MODE)

private const val SYNC_PREFS_NAME = "debug_sync_prefs"
private const val SYNC_KEY_SCREENSHOT_DEMO_MODE = "screenshot_demo_mode"

/**
 * Fast synchronous read from SharedPreferences, safe on the main thread.
 *
 * Used by [com.auralis.crisisconnect.screens.Chat.ChatScreenViewModel] when
 * bootstrapping a session so it can short-circuit the real message flow without
 * awaiting a DataStore coroutine.
 */
fun isScreenshotDemoModeEnabledSync(context: Context): Boolean {
    if (!BuildConfig.DEBUG) return false
    val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getBoolean(SYNC_KEY_SCREENSHOT_DEMO_MODE, false)
}

private fun cacheScreenshotDemoModeSync(context: Context, enabled: Boolean) {
    context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(SYNC_KEY_SCREENSHOT_DEMO_MODE, enabled)
        .apply()
}

fun getScreenshotDemoModeFlow(context: Context): Flow<Boolean> {
    if (!BuildConfig.DEBUG) return flowOf(false)
    return context.settingsDataStore.data.map { prefs ->
        prefs[SCREENSHOT_DEMO_MODE_KEY] ?: false
    }
}

suspend fun setScreenshotDemoMode(context: Context, enabled: Boolean) {
    if (!BuildConfig.DEBUG) return
    context.settingsDataStore.edit { prefs ->
        prefs[SCREENSHOT_DEMO_MODE_KEY] = enabled
    }
    cacheScreenshotDemoModeSync(context, enabled)
}
