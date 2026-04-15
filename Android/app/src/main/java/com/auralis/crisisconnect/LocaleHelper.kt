package com.auralis.crisisconnect

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Locale

private const val KEY_LANGUAGE = "language"
private val LANGUAGE_KEY = stringPreferencesKey(KEY_LANGUAGE)
private const val LANGUAGE_EN = "en"
private const val LANGUAGE_TR = "tr"
private const val SYNC_PREFS_NAME = "language_sync_prefs"
private const val SYNC_KEY_LANGUAGE = "language"

fun getSavedLanguageSync(context: Context): String {
    val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    val cachedLanguage = prefs.getString(SYNC_KEY_LANGUAGE, null)
    val resolvedLanguage = normalizeLanguageCode(cachedLanguage ?: Locale.getDefault().language)
    if (cachedLanguage != resolvedLanguage) {
        prefs.edit().putString(SYNC_KEY_LANGUAGE, resolvedLanguage).apply()
    }
    return resolvedLanguage
}

private fun cacheLanguageSync(context: Context, languageCode: String) {
    context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(SYNC_KEY_LANGUAGE, normalizeLanguageCode(languageCode))
        .apply()
}

fun getSavedLanguage(context: Context): Flow<String> {
    return context.settingsDataStore.data.map { prefs ->
        val resolvedLanguage = normalizeLanguageCode(
            prefs[LANGUAGE_KEY] ?: getSavedLanguageSync(context)
        )
        cacheLanguageSync(context, resolvedLanguage)
        resolvedLanguage
    }
}

suspend fun saveLanguage(context: Context, languageCode: String) {
    val normalizedLanguage = normalizeLanguageCode(languageCode)
    context.settingsDataStore.edit { prefs ->
        prefs[LANGUAGE_KEY] = normalizedLanguage
    }
    cacheLanguageSync(context, normalizedLanguage)
}

fun setLocale(context: Context, languageCode: String, shouldRecreate: Boolean = true) {
    val normalizedCode = normalizeLanguageCode(languageCode)
    val locale = when (normalizedCode) {
        LANGUAGE_TR -> Locale("tr", "TR")
        else -> Locale("en", "US")
    }
    Locale.setDefault(locale)

    updateResources(context.applicationContext, locale)
    updateResources(context, locale)
    val activity = context.findActivity()
    if (activity != null) {
        updateResources(activity, locale)
    }

    if (shouldRecreate) {
        activity?.recreate()
    }
}

private fun normalizeLanguageCode(languageCode: String): String {
    val normalized = languageCode.trim().replace('_', '-').lowercase(Locale.US)
    return when {
        normalized.startsWith(LANGUAGE_TR) -> LANGUAGE_TR
        else -> LANGUAGE_EN
    }
}

private fun updateResources(context: Context, locale: Locale) {
    val resources = context.resources
    val config = resources.configuration

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        resources.updateConfiguration(config, resources.displayMetrics)
    } else {
        @Suppress("DEPRECATION")
        resources.updateConfiguration(
            config.apply {
                setLocale(locale)
                setLayoutDirection(locale)
            },
            resources.displayMetrics
        )
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> {
        if (baseContext === this) null else baseContext.findActivity()
    }
    else -> null
}
