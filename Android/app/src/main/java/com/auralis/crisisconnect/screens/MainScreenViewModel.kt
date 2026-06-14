package com.auralis.crisisconnect.screens

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.data.BlePeerStore
import com.auralis.crisisconnect.data.BleSessionResolver
import com.auralis.crisisconnect.data.ChatMessage
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.observeContacts
import com.auralis.crisisconnect.data.observeLatestCallEvents
import com.auralis.crisisconnect.data.observeLatestMessages
import com.auralis.crisisconnect.data.observeMessagesNewestFirst
import com.auralis.crisisconnect.data.observeUnreadCounts
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.getScreenshotDemoModeFlow
import com.auralis.crisisconnect.isScreenshotDemoModeEnabledSync
import com.auralis.crisisconnect.onboardingDataStore
import com.auralis.crisisconnect.saveUserName
import com.auralis.crisisconnect.screens.Chat.ChatScreenshotDemoScenario
import com.auralis.crisisconnect.settingsDataStore
import com.auralis.crisisconnect.service.BlePeerIdentityUtils
import com.auralis.crisisconnect.service.RfcommForegroundService.CallEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.collections.LinkedHashMap

private val TERMS_ACCEPTED_KEY = booleanPreferencesKey("terms_v2_accepted")

class MainScreenViewModel(application: Application) : AndroidViewModel(application) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val context = getApplication<Application>()
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts
    private val _isContactsLoaded = MutableStateFlow(false)
    val isContactsLoaded: StateFlow<Boolean> = _isContactsLoaded

    private val _latestMessages = MutableStateFlow<Map<String, ChatMessage>>(emptyMap())
    val latestMessages: StateFlow<Map<String, ChatMessage>> = _latestMessages

    private val _latestCallEvents = MutableStateFlow<Map<String, CallEvent>>(emptyMap())
    val latestCallEvents: StateFlow<Map<String, CallEvent>> = _latestCallEvents

    private val _unreadCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadCounts: StateFlow<Map<String, Int>> = _unreadCounts

    private val _allMessagesNewestFirst = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessagesNewestFirst: StateFlow<List<ChatMessage>> = _allMessagesNewestFirst

    private val _publicMeshEnabled = MutableStateFlow(false)
    val publicMeshEnabled: StateFlow<Boolean> = _publicMeshEnabled

    init {
        viewModelScope.launch(exceptionHandler) {
            val termsAccepted = context.onboardingDataStore.data
                .catch { error ->
                    Log.w(TAG, "Unable to read onboarding preference, showing dialog", error)
                    emit(emptyPreferences())
                }
                .map { prefs -> prefs[TERMS_ACCEPTED_KEY] ?: false }
                .first()

            val savedName = runCatching {
                getSavedUserName(context).first()
            }.getOrElse { error ->
                Log.w(TAG, "Unable to read saved username, showing dialog", error)
                ""
            }.trim()

            val shouldShowDialog = savedName.isBlank() || !termsAccepted

            _showDialog.value = shouldShowDialog
        }
        viewModelScope.launch(exceptionHandler) {
            val contactsFlow = runCatching { observeContacts(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize contacts flow", error)
                    _contacts.value = if (isDemoFlagCurrentlyOn()) {
                        listOf(ChatScreenshotDemoScenario.buildDemoContact())
                    } else {
                        emptyList()
                    }
                    _isContactsLoaded.value = true
                    return@launch
                }

            combine(
                contactsFlow,
                BlePeerStore.peers,
                getScreenshotDemoModeFlow(context)
            ) { saved, peersMap, demoOn ->
                val merged = LinkedHashMap<String, Contact>()
                saved.forEach { contact ->
                    val key = contact.sessionCode.ifBlank { contact.address }
                    merged[key] = contact
                }
                peersMap.values.forEach { contact ->
                    val key = contact.sessionCode.ifBlank { contact.address }
                    val savedContact = merged[key]
                    merged[key] = if (savedContact == null) {
                        contact
                    } else {
                        mergeSavedAndPeerContact(
                            savedContact = savedContact,
                            peerContact = contact
                        )
                    }
                }
                val base = merged.values.toList()
                if (demoOn) {
                    // Prepend the scripted demo contact so it's the first row in
                    // the Messages list. Any real contacts stay visible underneath
                    // so the developer can still tell them apart.
                    val demoContact = ChatScreenshotDemoScenario.buildDemoContact()
                    listOf(demoContact) + base.filterNot {
                        it.sessionCode == demoContact.sessionCode
                    }
                } else {
                    base
                }
            }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Unable to observe contacts", error)
                    _contacts.value = if (isDemoFlagCurrentlyOn()) {
                        listOf(ChatScreenshotDemoScenario.buildDemoContact())
                    } else {
                        emptyList()
                    }
                    _isContactsLoaded.value = true
                }
                .collect { mergedList ->
                    _contacts.value = mergedList
                    _isContactsLoaded.value = true
                }
        }
        viewModelScope.launch(exceptionHandler) {
            val latestMessagesFlow = runCatching { observeLatestMessages(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize latest messages flow", error)
                    _latestMessages.value = demoLatestMessagesOrEmpty()
                    return@launch
                }

            combine(
                latestMessagesFlow,
                getScreenshotDemoModeFlow(context)
            ) { latest, demoOn ->
                if (demoOn) {
                    withDemoLatestMessage(latest)
                } else {
                    latest
                }
            }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Latest messages stream failed", error)
                    emit(demoLatestMessagesOrEmpty())
                }
                .collect { latest ->
                    _latestMessages.value = latest
                }
        }
        viewModelScope.launch(exceptionHandler) {
            val latestCallEventsFlow = runCatching { observeLatestCallEvents(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize latest call events flow", error)
                    _latestCallEvents.value = emptyMap()
                    return@launch
                }

            latestCallEventsFlow
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Latest call events stream failed", error)
                    emit(emptyMap())
                }
                .collect { latest ->
                    _latestCallEvents.value = latest
                }
        }
        viewModelScope.launch(exceptionHandler) {
            val unreadCountsFlow = runCatching { observeUnreadCounts(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize unread count flow", error)
                    _unreadCounts.value = demoUnreadCountsOrEmpty()
                    return@launch
                }

            combine(
                unreadCountsFlow,
                getScreenshotDemoModeFlow(context)
            ) { counts, demoOn ->
                if (demoOn) withDemoUnread(counts) else counts
            }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Unread count stream failed", error)
                    emit(demoUnreadCountsOrEmpty())
                }
                .collect { counts ->
                    _unreadCounts.value = counts
                }
        }
        viewModelScope.launch(exceptionHandler) {
            val messagesFlow = runCatching { observeMessagesNewestFirst(context) }
                .getOrElse { error ->
                    Log.e(TAG, "Unable to initialize message history flow", error)
                    _allMessagesNewestFirst.value = emptyList()
                    return@launch
                }

            messagesFlow
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Message history stream failed", error)
                    emit(emptyList())
                }
                .collect { messages ->
                    _allMessagesNewestFirst.value = messages
                }
        }
        viewModelScope.launch(exceptionHandler) {
            context.settingsDataStore.data
                .map { prefs -> prefs[ADVANCED_PUBLIC_MESH_ENABLED] ?: false }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Unable to observe advanced public mesh preference", error)
                    emit(false)
                }
                .collect { enabled ->
                    _publicMeshEnabled.value = enabled
                }
        }
    }

    /**
     * Cheap synchronous check used only from the `getOrElse` error recovery
     * branches, where we can't re-enter a flow. In the happy path we combine
     * with [getScreenshotDemoModeFlow] instead so the data stays reactive.
     */
    private fun isDemoFlagCurrentlyOn(): Boolean =
        isScreenshotDemoModeEnabledSync(context)

    private fun withDemoLatestMessage(
        real: Map<String, ChatMessage>
    ): Map<String, ChatMessage> {
        val demo = ChatScreenshotDemoScenario.buildDemoLatestMessage(
            context = context,
            nowMillis = System.currentTimeMillis()
        )
        // Use LinkedHashMap so the demo entry stays first in iteration order,
        // matching the way we put it at the top of the contacts list.
        val merged = LinkedHashMap<String, ChatMessage>()
        merged[demo.sessionCode] = demo
        real.forEach { (k, v) ->
            if (k != demo.sessionCode) merged[k] = v
        }
        return merged
    }

    private fun demoLatestMessagesOrEmpty(): Map<String, ChatMessage> =
        if (isDemoFlagCurrentlyOn()) {
            withDemoLatestMessage(emptyMap())
        } else {
            emptyMap()
        }

    private fun withDemoUnread(real: Map<String, Int>): Map<String, Int> {
        // Demo screenshot mode: present the conversation as fully read.
        // Strip any real entry that happens to share the demo session code
        // so neither the contact-row badge nor the Messages bottom-tab
        // badge shows a "1" in screenshots.
        return real.filterKeys { it != ChatScreenshotDemoScenario.DEMO_SESSION_CODE }
    }

    private fun demoUnreadCountsOrEmpty(): Map<String, Int> = emptyMap()

    private fun mergeSavedAndPeerContact(
        savedContact: Contact,
        peerContact: Contact
    ): Contact {
        val sessionCode = savedContact.sessionCode.ifBlank { peerContact.sessionCode }
        val mergedName = if (BleSessionResolver.isBleSession(sessionCode)) {
            BlePeerIdentityUtils.resolveStableBleContactName(
                storedName = savedContact.name,
                peerName = peerContact.name,
                sessionCode = sessionCode,
                addressForFallback = savedContact.address.ifBlank { peerContact.address }
            ) ?: savedContact.name.ifBlank { peerContact.name }
        } else {
            savedContact.name.ifBlank { peerContact.name }
        }
        return savedContact.copy(
            name = mergedName,
            aesKey = savedContact.aesKey.ifBlank { peerContact.aesKey },
            address = savedContact.address.ifBlank { peerContact.address }
        )
    }

    fun acceptDialog(fullName: String) {
        viewModelScope.launch(exceptionHandler) {
            context.onboardingDataStore.edit { prefs ->
                prefs[TERMS_ACCEPTED_KEY] = true
            }
            val trimmed = fullName.trim()
            if (trimmed.isNotEmpty()) {
                saveUserName(context, trimmed)
                syncUserNameWithFirestore(trimmed)
            }
            _showDialog.value = false
        }
    }

    private fun syncUserNameWithFirestore(userName: String) {
        val uid = auth.currentUser?.takeUnless { it.isAnonymous }?.uid
        if (uid.isNullOrBlank()) {
            Log.d(TAG, "Skipping username sync because no authenticated Firebase user is available")
            return
        }

        firestore.collection("users")
            .document(uid)
            .set(mapOf("username" to userName), SetOptions.merge())
            .addOnFailureListener { error ->
                Log.e(TAG, "Failed to sync username", error)
            }
    }

    companion object {
        private const val TAG = "MainScreenVM"
        private val ADVANCED_PUBLIC_MESH_ENABLED =
            booleanPreferencesKey("advanced_public_mesh_enabled")
    }
}
