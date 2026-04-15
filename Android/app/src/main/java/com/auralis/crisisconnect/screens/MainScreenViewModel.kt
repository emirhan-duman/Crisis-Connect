package com.auralis.crisisconnect.screens

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
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
import com.auralis.crisisconnect.saveUserName
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

private val Context.dataStore by preferencesDataStore(name = "popup_prefs")
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
            val termsAccepted = context.dataStore.data
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
                    _contacts.value = emptyList()
                    _isContactsLoaded.value = true
                    return@launch
                }

            combine(
                contactsFlow,
                BlePeerStore.peers
            ) { saved, peersMap ->
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
                merged.values.toList()
            }
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Unable to observe contacts", error)
                    _contacts.value = emptyList()
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
                    _latestMessages.value = emptyMap()
                    return@launch
                }

            latestMessagesFlow
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Latest messages stream failed", error)
                    emit(emptyMap())
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
                    _unreadCounts.value = emptyMap()
                    return@launch
                }

            unreadCountsFlow
                .distinctUntilChanged()
                .catch { error ->
                    Log.e(TAG, "Unread count stream failed", error)
                    emit(emptyMap())
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
            context.dataStore.edit { prefs ->
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
