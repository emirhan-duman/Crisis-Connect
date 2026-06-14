package com.auralis.crisisconnect.screens

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.BuildConfig
import com.auralis.crisisconnect.getSavedLanguage
import com.auralis.crisisconnect.ThemeOption
import com.auralis.crisisconnect.getSavedThemeOption
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.getScreenshotDemoModeFlow
import com.auralis.crisisconnect.saveLanguage
import com.auralis.crisisconnect.saveThemeOption
import com.auralis.crisisconnect.saveUserName
import com.auralis.crisisconnect.setLocale
import com.auralis.crisisconnect.setScreenshotDemoMode
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = getApplication<Application>()
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    var selectedCode by mutableStateOf("en")
        private set

    var userName by mutableStateOf("")
        private set

    var themeOption by mutableStateOf(ThemeOption.SYSTEM)
        private set

    /** Screenshot Demo Mode is only surfaced in debug builds. */
    val isDeveloperOptionsAvailable: Boolean = BuildConfig.DEBUG

    var screenshotDemoMode by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch(exceptionHandler) {
            getSavedLanguage(appContext).collect { selectedCode = it }
        }
        viewModelScope.launch(exceptionHandler) {
            getSavedUserName(appContext).collect { userName = it }
        }
        viewModelScope.launch(exceptionHandler) {
            getSavedThemeOption(appContext).collect { themeOption = it }
        }
        if (isDeveloperOptionsAvailable) {
            viewModelScope.launch(exceptionHandler) {
                getScreenshotDemoModeFlow(appContext).collect { screenshotDemoMode = it }
            }
        }
    }

    fun updateLanguage(context: Context, code: String) {
        viewModelScope.launch(exceptionHandler) {
            selectedCode = code
            saveLanguage(appContext, code)
            setLocale(context, code)
        }
    }

    fun updateUserName(name: String) {
        viewModelScope.launch(exceptionHandler) {
            userName = name
            saveUserName(appContext, name)
        }
    }

    fun updateTheme(option: ThemeOption, onApplied: () -> Unit = {}) {
        viewModelScope.launch(exceptionHandler) {
            themeOption = option
            saveThemeOption(appContext, option)
            onApplied()
        }
    }

    fun updateScreenshotDemoMode(enabled: Boolean) {
        if (!isDeveloperOptionsAvailable) return
        viewModelScope.launch(exceptionHandler) {
            screenshotDemoMode = enabled
            setScreenshotDemoMode(appContext, enabled)
        }
    }

    companion object {
        private const val TAG = "SettingsViewModel"
    }
}
