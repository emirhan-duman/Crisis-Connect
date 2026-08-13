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
 * Same-agency authority directory. A selected member is routed into the agency-scoped MLS-v2
 * authority thread; legacy citizen-contact creation is intentionally not used here.
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

    private val _openConversation = MutableSharedFlow<AuthorityRosterMember>(extraBufferCapacity = 1)
    val openConversation: SharedFlow<AuthorityRosterMember> = _openConversation

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

    fun open(member: AuthorityRosterMember) {
        if (member.uid.isBlank() || member.agencySlug.isBlank()) {
            _error.tryEmit(R.string.authority_roster_add_failed)
            return
        }
        _openConversation.tryEmit(member)
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
