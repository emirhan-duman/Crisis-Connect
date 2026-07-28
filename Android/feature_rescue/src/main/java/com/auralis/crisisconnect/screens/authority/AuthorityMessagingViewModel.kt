package com.auralis.crisisconnect.screens.authority

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.AgencyKey
import com.auralis.crisisconnect.messaging.AgencyMessage
import com.auralis.crisisconnect.messaging.AgencyMessagingClient
import com.auralis.crisisconnect.messaging.AuthorityRosterClient
import com.auralis.crisisconnect.messaging.AuthorityRosterMember
import com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling
import com.auralis.crisisconnect.security.SecurityRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Online authority (kurum) messaging — the ONLINE half of the authority comms feature. Talks the
 * same per-agency shared-key channel the web dashboard uses ([AgencyMessagingClient] mirrors
 * `lib/messaging/agency.ts`): fetches the agency key, subscribes to the encrypted Firestore channel
 * and posts to it, so an Android field team and a web authority in the SAME agency see one thread.
 *
 * The device's authority role gates entry (handled by [com.auralis.crisisconnect.feature.rescue.RescueActivity]);
 * this VM only needs the agency slug, resolved from the signed-in user's `users/{uid}.agencySlug`
 * (the value `issueAgencyMessagingKey` verifies), falling back to the verified agency bound into the
 * device's own role certificate.
 */
class AuthorityMessagingViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface State {
        data object Loading : State
        data class Error(val messageRes: Int) : State
        data class Ready(val agencySlug: String) : State
    }

    private val appContext = application.applicationContext
    private val client = AgencyMessagingClient()
    private val rosterClient = AuthorityRosterClient()

    /** Agency members who can be called (from the roster); populated once the channel is ready. */
    private val _callTargets = MutableStateFlow<List<AuthorityRosterMember>>(emptyList())
    val callTargets: StateFlow<List<AuthorityRosterMember>> = _callTargets.asStateFlow()

    private var agencySlug: String? = null

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableStateFlow<List<AgencyMessage>>(emptyList())
    val messages: StateFlow<List<AgencyMessage>> = _messages.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _sendError = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendError: SharedFlow<Int> = _sendError

    /** The signed-in user's own uid, so the screen can align own vs. others' bubbles. */
    val selfUid: String? = FirebaseAuth.getInstance().currentUser?.uid

    private var listener: ListenerRegistration? = null
    private var agencyKey: AgencyKey? = null
    private var localName: String = ""

    init {
        start()
    }

    private fun start() {
        _state.value = State.Loading
        viewModelScope.launch {
            localName = runCatching { getSavedUserName(appContext).first().trim() }.getOrDefault("")
            val slug = resolveAgencySlug()
            if (slug.isNullOrBlank()) {
                _state.value = State.Error(R.string.authority_msg_no_agency)
                return@launch
            }
            val key = runCatching { withContext(Dispatchers.IO) { client.fetchAgencyKey(slug) } }.getOrNull()
            if (key == null) {
                _state.value = State.Error(R.string.authority_msg_channel_failed)
                return@launch
            }
            agencyKey = key
            listener?.remove()
            listener = client.listen(key) { msgs -> _messages.value = msgs }
            agencySlug = slug
            _state.value = State.Ready(slug)
            // Load callable agency members so the user can pick who to call (agency thread has no
            // single peer). Best-effort; an empty list just disables the call action.
            _callTargets.value = runCatching { rosterClient.listRoster(slug) }.getOrDefault(emptyList())
        }
    }

    /** Retry after an error (no agency / channel fetch failed / offline). */
    fun retry() {
        if (_state.value is State.Loading) return
        start()
    }

    private suspend fun resolveAgencySlug(): String? = withContext(Dispatchers.IO) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext null
        // Authoritative source: users/{uid}.agencySlug — the exact value issueAgencyMessagingKey checks
        // membership against. Fall back to the agency bound into the device's own role certificate.
        val fromDoc = runCatching {
            FirebaseFirestore.getInstance().document("users/$uid").get().await().getString("agencySlug")
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        fromDoc ?: runCatching {
            SecurityRepository(appContext).getUsableStoredCertificateAgency()
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Resolves a string resource off the composition (send errors are emitted from a coroutine). */
    fun appErrorText(messageRes: Int): String = appContext.getString(messageRes)

    /** Starts a web-compatible internet call to [member] over the agency channel's callSignals. */
    fun startCallTo(member: AuthorityRosterMember) {
        val slug = agencySlug ?: return
        val uid = selfUid ?: return
        AuthorityCallSignaling(
            channelId = slug,
            myUid = uid,
            kind = AuthorityCallSignaling.ChannelKind.AGENCY,
            peerNameResolver = { member.name }
        ).startCall(member.uid)
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    fun send() {
        val key = agencyKey ?: return
        val text = _draft.value.trim()
        if (text.isBlank()) return
        _draft.value = ""
        viewModelScope.launch {
            runCatching { client.send(key, localName, text) }
                .onFailure { _sendError.tryEmit(R.string.authority_msg_send_failed) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        listener?.remove()
        listener = null
    }
}
