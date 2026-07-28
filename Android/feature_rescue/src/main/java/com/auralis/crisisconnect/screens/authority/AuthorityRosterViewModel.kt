package com.auralis.crisisconnect.screens.authority

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.AuthorityRosterClient
import com.auralis.crisisconnect.messaging.AuthorityRosterMember
import com.auralis.crisisconnect.security.SecurityRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Same-agency authority directory: lists fellow authorities (via `listAuthorityRoster`) and turns a
 * tapped member into a 1:1 contact ([AuthorityRosterClient.addContact]) so its chat works over the
 * internet now and can fall back to an offline number-keyed Bluetooth link (NearbyAutoLink) when the
 * two are nearby without connectivity. This is the offline half of the authority comms feature.
 */
class AuthorityRosterViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Loading : State
        data class Error(val messageRes: Int) : State
        data class Ready(val members: List<AuthorityRosterMember>) : State
    }

    private val appContext = application.applicationContext
    private val client = AuthorityRosterClient()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The uid currently being added (its row shows progress + is disabled), or null. */
    private val _addingUid = MutableStateFlow<String?>(null)
    val addingUid: StateFlow<String?> = _addingUid.asStateFlow()

    private val _openConversation = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openConversation: SharedFlow<String> = _openConversation

    private val _error = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val error: SharedFlow<Int> = _error

    init {
        load()
    }

    fun load() {
        _state.value = State.Loading
        viewModelScope.launch {
            val slug = resolveAgencySlug()
            if (slug.isNullOrBlank()) {
                _state.value = State.Error(R.string.authority_msg_no_agency)
                return@launch
            }
            val members = runCatching { client.listRoster(slug) }.getOrNull()
            _state.value = when {
                members == null -> State.Error(R.string.authority_msg_channel_failed)
                members.isEmpty() -> State.Error(R.string.authority_roster_empty)
                else -> State.Ready(members)
            }
        }
    }

    fun addAndOpen(member: AuthorityRosterMember) {
        if (_addingUid.value != null) return
        _addingUid.value = member.uid
        viewModelScope.launch {
            val sessionCode = runCatching { client.addContact(appContext, member) }.getOrNull()
            _addingUid.value = null
            if (sessionCode.isNullOrBlank()) {
                _error.tryEmit(R.string.authority_roster_add_failed)
            } else {
                _openConversation.tryEmit(sessionCode)
            }
        }
    }

    private suspend fun resolveAgencySlug(): String? = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext null
        val fromDoc = runCatching {
            FirebaseFirestore.getInstance().document("users/$uid").get().await().getString("agencySlug")
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        fromDoc ?: runCatching {
            SecurityRepository(appContext).getUsableStoredCertificateAgency()
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    fun appErrorText(messageRes: Int): String = appContext.getString(messageRes)
}
