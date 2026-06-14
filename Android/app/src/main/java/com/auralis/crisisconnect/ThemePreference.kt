package com.auralis.crisisconnect

import android.content.Context
import android.content.res.Configuration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeOption(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String?): ThemeOption = when (value) {
            DARK.value -> DARK
            LIGHT.value -> LIGHT
            else -> SYSTEM
        }
    }
}

private const val KEY_THEME = "theme_option"
private val THEME_KEY = stringPreferencesKey(KEY_THEME)

private const val SYNC_PREFS_NAME = "theme_sync_prefs"
private const val SYNC_KEY_THEME = "theme_option"

/** Fast synchronous read from SharedPreferences – safe to call on the main thread. */
fun getSavedThemeOptionSync(context: Context): ThemeOption {
    val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    prefs.getString(SYNC_KEY_THEME, null)?.let(ThemeOption::fromValue)?.let { return it }

    // First launch: seed the app with its own light/dark palette based on the
    // device's current appearance, instead of keeping the theme in follow-system mode.
    val initialOption = getCurrentDeviceThemeOption(context)
    prefs.edit().putString(SYNC_KEY_THEME, initialOption.value).apply()
    return initialOption
}

private fun cacheThemeOptionSync(context: Context, option: ThemeOption) {
    context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(SYNC_KEY_THEME, option.value).apply()
}

fun getSavedThemeOption(context: Context): Flow<ThemeOption> {
    return context.settingsDataStore.data.map { prefs ->
        prefs[THEME_KEY]
            ?.let(ThemeOption::fromValue)
            ?: getSavedThemeOptionSync(context)
    }
}

suspend fun saveThemeOption(context: Context, option: ThemeOption) {
    context.settingsDataStore.edit { prefs ->
        prefs[THEME_KEY] = option.value
    }
    cacheThemeOptionSync(context, option)
}

private fun getCurrentDeviceThemeOption(context: Context): ThemeOption {
    val currentNightMode =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    return if (currentNightMode == Configuration.UI_MODE_NIGHT_YES) {
        ThemeOption.DARK
    } else {
        ThemeOption.LIGHT
    }
}
