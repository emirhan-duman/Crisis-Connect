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
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.MessagingBootstrap
import com.auralis.crisisconnect.security.ChildPinResult
import com.auralis.crisisconnect.security.ChildProfileManager
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

    var childProfileEnabled by mutableStateOf(false)
        private set

    /** Session codes of the CONFIRMED parents (the contact approved the request). */
    var childParents by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Session codes with a parent request sent but not yet answered. */
    var childPendingParents by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Session codes of the children this device is a parent of (accepted their request). */
    var childProfileChildren by mutableStateOf<Set<String>>(emptySet())
        private set

    init {
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.enabledFlow(appContext).collect { childProfileEnabled = it }
        }
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.parentsFlow(appContext).collect { childParents = it }
        }
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.pendingParentsFlow(appContext).collect { childPendingParents = it }
        }
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.childrenFlow(appContext).collect { childProfileChildren = it }
        }
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
            setLocale(context, code, syncToSystem = true)
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

    fun enableChildProfile(pin: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            val enabled = ChildProfileManager.enable(appContext, pin)
            onResult(enabled)
            if (enabled) republishDirectoryEntry()
        }
    }

    fun disableChildProfile(pin: String, onResult: (ChildPinResult) -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            val result = ChildProfileManager.disable(appContext, pin)
            onResult(result)
            if (result is ChildPinResult.Success) republishDirectoryEntry()
        }
    }

    fun verifyChildPin(pin: String, onResult: (ChildPinResult) -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            onResult(ChildProfileManager.verifyPin(appContext, pin))
        }
    }

    /**
     * Sends a parent request to [contact] and tracks it as pending. The contact becomes a
     * confirmed parent only after accepting on their own device (template 207 → confirmParent).
     * On send failure the pending entry is rolled back and [onResult] gets false.
     */
    fun requestChildParent(contact: Contact, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.addPendingParent(appContext, contact.sessionCode)
            val sent = runCatching {
                InternetChatTransport(appContext).sendParentRequest(contact)
            }.getOrDefault(false)
            if (!sent) {
                ChildProfileManager.removePendingParent(appContext, contact.sessionCode)
            }
            onResult(sent)
        }
    }

    /** Removes [sessionCode] from the confirmed parents and any pending request. */
    fun removeChildParent(sessionCode: String) {
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.removeParent(appContext, sessionCode)
            ChildProfileManager.removePendingParent(appContext, sessionCode)
        }
    }

    /** Removes a child from this parent device's "my children" list. */
    fun removeChildProfileChild(sessionCode: String) {
        viewModelScope.launch(exceptionHandler) {
            ChildProfileManager.removeChild(appContext, sessionCode)
        }
    }

    /** Pushes the changed child-profile flag to the messaging directory (best effort). */
    private fun republishDirectoryEntry() {
        viewModelScope.launch(exceptionHandler) {
            runCatching { MessagingBootstrap.republishIdentity(appContext) }
                .onFailure { Log.w(TAG, "Failed to republish directory entry", it) }
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
