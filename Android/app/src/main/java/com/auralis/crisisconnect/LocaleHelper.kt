package com.auralis.crisisconnect

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.LocaleList
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.auralis.crisisconnect.widget.AppWidgetLanguageRefresher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

private const val KEY_LANGUAGE = "language"
private val LANGUAGE_KEY = stringPreferencesKey(KEY_LANGUAGE)
private const val LANGUAGE_EN = "en"
private const val LANGUAGE_TR = "tr"
private const val LANGUAGE_JA = "ja"
private const val LANGUAGE_ES = "es"
private const val LANGUAGE_HI = "hi"
private const val LANGUAGE_FR = "fr"
private const val LANGUAGE_AR = "ar"
private const val LANGUAGE_KU = "ku"
private const val LANGUAGE_FA = "fa"
private const val LANGUAGE_ID = "id"
private const val LANGUAGE_BN = "bn"
private const val LANGUAGE_RU = "ru"
private const val LANGUAGE_DE = "de"
private const val LANGUAGE_UR = "ur"
private const val LANGUAGE_ZH = "zh"
private const val LANGUAGE_UK = "uk"
private const val LANGUAGE_PT = "pt"
private const val LANGUAGE_FIL = "fil"
private const val LANGUAGE_VI = "vi"
private const val SYNC_PREFS_NAME = "language_sync_prefs"
private const val SYNC_KEY_LANGUAGE = "language"
private const val SYNC_KEY_EXPLICIT_CHOICE = "explicit_choice"
private const val KEY_UNPIN_MIGRATION_DONE = "auto_locale_unpinned_v1"

fun getSavedLanguageSync(context: Context): String {
    val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    val cachedLanguage = prefs.getString(SYNC_KEY_LANGUAGE, null)
    // Tri-state: true = user picked a language in-app, false = confirmed auto mode, null =
    // legacy install where we can't tell synchronously (the DataStore flow backfills it).
    val explicitChoice: Boolean? = if (prefs.contains(SYNC_KEY_EXPLICIT_CHOICE)) {
        prefs.getBoolean(SYNC_KEY_EXPLICIT_CHOICE, false)
    } else {
        null
    }
    val resolvedLanguage = systemPerAppLanguage(context)
        ?: when (explicitChoice) {
            // Auto mode: re-derive from the device locales every time so a device-language
            // change is picked up on the next launch instead of the first detection sticking.
            false -> deviceBestSupportedLanguage()
            // Explicit choice (or unknown, where the cache may hold one): the cache wins.
            else -> normalizeLanguageCode(cachedLanguage ?: deviceBestSupportedLanguage())
        }
    if (cachedLanguage != resolvedLanguage) {
        prefs.edit().putString(SYNC_KEY_LANGUAGE, resolvedLanguage).apply()
    }
    return resolvedLanguage
}

/**
 * Best supported language derived from the device locale list: the first entry we ship a
 * translation for wins, so a device set to [Korean, Turkish] resolves to Turkish rather than
 * falling straight to English. Uses toLanguageTag(), which maps legacy Java codes (Indonesian
 * "in", Hebrew "iw") back to their modern form before matching.
 */
private fun deviceBestSupportedLanguage(): String {
    val locales = LocaleList.getDefault()
    for (i in 0 until locales.size()) {
        supportedLanguageOrNull(locales.get(i).toLanguageTag())?.let { return it }
    }
    return LANGUAGE_EN
}

/**
 * The per-app language the user picked in system settings (Android 13+), or null when none is
 * set or the platform store is unavailable. When present it wins over our own persistence, so a
 * change made in system settings is honored instead of being overwritten at startup.
 */
private fun systemPerAppLanguage(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val localeManager = context.applicationContext
        .getSystemService(LocaleManager::class.java) ?: return null
    val locales = runCatching { localeManager.applicationLocales }.getOrNull() ?: return null
    if (locales.isEmpty) return null
    return normalizeLanguageCode(locales.get(0).toLanguageTag())
}

/**
 * One-time recovery for installs that an older build accidentally pinned to a per-app locale.
 *
 * Older builds published the auto-detected language into the platform per-app locale store on
 * every cold start (and, when a startup read failed, published "en"). That override then shadowed
 * the device language forever. Here we undo it exactly once: if the user never made an explicit
 * in-app language choice (no [LANGUAGE_KEY] persisted in DataStore) but a per-app override exists,
 * it was written by that bug — clear it so resolution falls back to the device language again.
 *
 * A user who genuinely picked a language in Android system settings (never in-app) is reset to
 * "System default" this one time; they can re-pick, and afterwards the setting is honored normally.
 * Recovery takes effect on the next launch (the current launch's locale is already applied).
 */
suspend fun clearAutoPinnedSystemLocaleIfNeeded(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val prefs = context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_UNPIN_MIGRATION_DONE, false)) return

    // Throws propagate to the caller's runCatching so the migration retries on a later launch
    // instead of being marked done after a transient read failure.
    val hasExplicitChoice = context.settingsDataStore.data.first()[LANGUAGE_KEY] != null

    if (!hasExplicitChoice) {
        val localeManager = context.applicationContext.getSystemService(LocaleManager::class.java)
        runCatching {
            if (localeManager != null && !localeManager.applicationLocales.isEmpty) {
                localeManager.applicationLocales = LocaleList.getEmptyLocaleList()
            }
        }
        // Drop the poisoned language cache too, so the sync resolution re-derives from the device.
        prefs.edit()
            .remove(SYNC_KEY_LANGUAGE)
            .putBoolean(SYNC_KEY_EXPLICIT_CHOICE, false)
            .putBoolean(KEY_UNPIN_MIGRATION_DONE, true)
            .apply()
    } else {
        prefs.edit()
            .putBoolean(SYNC_KEY_EXPLICIT_CHOICE, true)
            .putBoolean(KEY_UNPIN_MIGRATION_DONE, true)
            .apply()
    }
}

private fun cacheLanguageSync(context: Context, languageCode: String) {
    context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(SYNC_KEY_LANGUAGE, normalizeLanguageCode(languageCode))
        .apply()
}

private fun markExplicitChoiceSync(context: Context, explicit: Boolean) {
    context.getSharedPreferences(SYNC_PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(SYNC_KEY_EXPLICIT_CHOICE, explicit)
        .apply()
}

fun getSavedLanguage(context: Context): Flow<String> {
    return context.settingsDataStore.data.map { prefs ->
        val storedChoice = prefs[LANGUAGE_KEY]
        // Backfill the sync-prefs marker so the synchronous startup path knows whether the
        // persisted language is a real user choice or just an auto-detection cache.
        markExplicitChoiceSync(context, storedChoice != null)
        val resolvedLanguage = systemPerAppLanguage(context)
            ?: storedChoice?.let(::normalizeLanguageCode)
            ?: deviceBestSupportedLanguage()
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
    markExplicitChoiceSync(context, true)
}

/** The concrete [Locale] the app uses for a (possibly unnormalized) language code. */
fun localeForLanguageCode(languageCode: String): Locale = when (normalizeLanguageCode(languageCode)) {
    LANGUAGE_TR -> Locale("tr", "TR")
    LANGUAGE_JA -> Locale("ja", "JP")
    LANGUAGE_ES -> Locale("es", "ES")
    LANGUAGE_HI -> Locale("hi", "IN")
    LANGUAGE_FR -> Locale("fr", "FR")
    LANGUAGE_AR -> Locale("ar")
    LANGUAGE_KU -> Locale("ku")
    LANGUAGE_FA -> Locale("fa")
    LANGUAGE_ID -> Locale("id")
    LANGUAGE_BN -> Locale("bn")
    LANGUAGE_RU -> Locale("ru")
    LANGUAGE_DE -> Locale("de", "DE")
    LANGUAGE_UR -> Locale("ur")
    LANGUAGE_ZH -> Locale("zh", "CN")
    LANGUAGE_UK -> Locale("uk")
    LANGUAGE_PT -> Locale("pt")
    LANGUAGE_FIL -> Locale("fil")
    LANGUAGE_VI -> Locale("vi")
    else -> Locale("en", "US")
}

/**
 * A context whose resources resolve in [languageCode], independent of when the process-wide
 * locale was last applied. Use this when building localized strings inside long-lived holders
 * (ViewModels) that react to a language-change flow — the application context they captured may
 * not have been re-configured yet at the moment the flow emits.
 */
fun localizedContext(context: Context, languageCode: String): Context {
    val locale = localeForLanguageCode(languageCode)
    val config = android.content.res.Configuration(context.resources.configuration)
    config.setLocales(LocaleList(locale))
    config.setLayoutDirection(locale)
    return context.createConfigurationContext(config)
}

fun setLocale(
    context: Context,
    languageCode: String,
    shouldRecreate: Boolean = true,
    syncToSystem: Boolean = false,
) {
    val locale = localeForLanguageCode(languageCode)
    Locale.setDefault(locale)

    // Mirror into the platform per-app locale store only for an explicit in-app choice.
    // Startup auto-detection must never write there: doing so pins a per-app override that then
    // shadows the device language on every later launch (and can trap the app in whatever it wrote).
    val systemHandlesRecreate = syncToSystem && publishToSystemPerAppLocale(context, locale)

    updateResources(context.applicationContext, locale)
    updateResources(context, locale)
    val activity = context.findActivity()
    if (activity != null) {
        updateResources(activity, locale)
    }

    if (shouldRecreate && !systemHandlesRecreate) {
        activity?.recreate()
    }

    // Pinned home-screen widgets render from app resources at compose time; with
    // updatePeriodMillis=0 nothing re-renders them after a language change, so do it here.
    AppWidgetLanguageRefresher.refreshAll(context)
}

/**
 * Mirrors the chosen language into the platform per-app locale store (Android 13+) so system
 * settings shows it. Returns true when the platform accepted a change — it then recreates
 * activities itself, so the caller must skip its own recreate to avoid doing it twice.
 */
private fun publishToSystemPerAppLocale(context: Context, locale: Locale): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    val localeManager = context.applicationContext
        .getSystemService(LocaleManager::class.java) ?: return false
    return runCatching {
        val desired = LocaleList(locale)
        if (localeManager.applicationLocales != desired) {
            localeManager.applicationLocales = desired
            true
        } else {
            false
        }
    }.getOrDefault(false)
}

private fun normalizeLanguageCode(languageCode: String): String {
    return supportedLanguageOrNull(languageCode) ?: LANGUAGE_EN
}

/** The supported language a code/tag maps to, or null when we ship no translation for it. */
private fun supportedLanguageOrNull(languageCode: String): String? {
    val normalized = languageCode.trim().replace('_', '-').lowercase(Locale.US)
    // Legacy/alternate codes older devices still report: Java used "in" for Indonesian until
    // Android 15, and some devices expose Filipino as Tagalog ("tl").
    val aliased = when {
        normalized == "in" || normalized.startsWith("in-") -> LANGUAGE_ID
        normalized == "tl" || normalized.startsWith("tl-") -> LANGUAGE_FIL
        else -> normalized
    }
    return when {
        aliased.startsWith(LANGUAGE_EN) -> LANGUAGE_EN
        aliased.startsWith(LANGUAGE_TR) -> LANGUAGE_TR
        aliased.startsWith(LANGUAGE_JA) -> LANGUAGE_JA
        aliased.startsWith(LANGUAGE_ES) -> LANGUAGE_ES
        aliased.startsWith(LANGUAGE_HI) -> LANGUAGE_HI
        aliased.startsWith(LANGUAGE_FR) -> LANGUAGE_FR
        aliased.startsWith(LANGUAGE_AR) -> LANGUAGE_AR
        aliased.startsWith(LANGUAGE_KU) -> LANGUAGE_KU
        aliased.startsWith(LANGUAGE_FA) -> LANGUAGE_FA
        aliased.startsWith(LANGUAGE_ID) -> LANGUAGE_ID
        aliased.startsWith(LANGUAGE_BN) -> LANGUAGE_BN
        aliased.startsWith(LANGUAGE_RU) -> LANGUAGE_RU
        aliased.startsWith(LANGUAGE_DE) -> LANGUAGE_DE
        aliased.startsWith(LANGUAGE_UR) -> LANGUAGE_UR
        aliased.startsWith(LANGUAGE_ZH) -> LANGUAGE_ZH
        aliased.startsWith(LANGUAGE_UK) -> LANGUAGE_UK
        aliased.startsWith(LANGUAGE_FIL) -> LANGUAGE_FIL
        aliased.startsWith(LANGUAGE_PT) -> LANGUAGE_PT
        aliased.startsWith(LANGUAGE_VI) -> LANGUAGE_VI
        else -> null
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
