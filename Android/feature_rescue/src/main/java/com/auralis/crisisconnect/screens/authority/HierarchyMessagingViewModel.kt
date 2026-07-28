package com.auralis.crisisconnect.screens.authority

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.HierarchyChannel
import com.auralis.crisisconnect.messaging.HierarchyChannelKey
import com.auralis.crisisconnect.messaging.HierarchyMessage
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.messaging.HierarchyPeer
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Cross-panel (hierarchy) authority messaging — the second online channel type (parent/child/sibling
 * panels), on top of the per-agency channel. Server-issued per-channel keys ([HierarchyMessagingClient]
 * mirrors the web `lib/messaging/hierarchy.ts`); each channel is shared by two panels' managers, and a
 * message is addressed to ONE peer manager, so the UI is a picker (channel → peer) then a 1:1 thread
 * filtered to that peer within the channel.
 */
class HierarchyMessagingViewModel(application: Application) : AndroidViewModel(application) {

    sealed interface PickerState {
        data object Loading : PickerState
        data class Error(val messageRes: Int) : PickerState
        data class Ready(val channels: List<HierarchyChannel>) : PickerState
    }

    data class ActiveConversation(
        val channelName: String,
        val peer: HierarchyPeer
    )

    private val appContext = application.applicationContext
    private val client = HierarchyMessagingClient()
    val selfUid: String? = FirebaseAuth.getInstance().currentUser?.uid

    private val _picker = MutableStateFlow<PickerState>(PickerState.Loading)
    val picker: StateFlow<PickerState> = _picker.asStateFlow()

    private val _active = MutableStateFlow<ActiveConversation?>(null)
    val active: StateFlow<ActiveConversation?> = _active.asStateFlow()

    private val _messages = MutableStateFlow<List<HierarchyMessage>>(emptyList())
    val messages: StateFlow<List<HierarchyMessage>> = _messages.asStateFlow()

    private val _draft = MutableStateFlow("")
    val draft: StateFlow<String> = _draft.asStateFlow()

    private val _sendError = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendError: SharedFlow<Int> = _sendError

    private var listener: ListenerRegistration? = null
    private var activeKey: HierarchyChannelKey? = null
    private var localName: String = ""

    // Web-compatible call signalling over this channel's Firestore callSignals subcollection.
    private var callSignaling: com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling? = null
    private var callListener: ListenerRegistration? = null

    init {
        loadChannels()
    }

    fun loadChannels() {
        _picker.value = PickerState.Loading
        viewModelScope.launch {
            localName = runCatching { getSavedUserName(appContext).first().trim() }.getOrDefault("")
            val channels = runCatching { client.fetchChannels() }.getOrNull()
            _picker.value = when {
                channels == null -> PickerState.Error(R.string.authority_msg_channel_failed)
                channels.isEmpty() -> PickerState.Error(R.string.hierarchy_msg_none)
                else -> PickerState.Ready(channels)
            }
        }
    }

    fun openConversation(channel: HierarchyChannel, peer: HierarchyPeer) {
        listener?.remove()
        listener = null
        activeKey = null
        callListener?.remove()
        callListener = null
        callSignaling = null
        _messages.value = emptyList()
        _draft.value = ""
        _active.value = ActiveConversation(channelName = channel.peerPanelName, peer = peer)
        val uid = selfUid
        if (uid != null) {
            // Only the OUTGOING sender is needed here; INCOMING channel signals are handled app-wide by
            // AuthorityCallReceiver (a single listener source, so an incoming offer isn't processed twice).
            callSignaling = com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling(
                channelId = channel.channelId,
                myUid = uid,
                kind = com.auralis.crisisconnect.messaging.call.AuthorityCallSignaling.ChannelKind.HIERARCHY,
                peerNameResolver = { u -> if (u == peer.uid) peer.name else u }
            )
        }
        viewModelScope.launch {
            val key = runCatching { client.fetchChannelKey(channel.channelId) }.getOrNull()
            if (key == null) {
                _sendError.tryEmit(R.string.authority_msg_channel_failed)
                _active.value = null
                return@launch
            }
            activeKey = key
            val me = selfUid
            listener = client.listen(key) { msgs ->
                _messages.value = msgs.filter { m ->
                    (m.senderUid == peer.uid && m.recipientUid == me) ||
                        (m.senderUid == me && m.recipientUid == peer.uid)
                }
            }
        }
    }

    /** Places a web-compatible internet call to the selected peer over this channel's callSignals. */
    fun startCall() {
        val peer = _active.value?.peer ?: return
        callSignaling?.startCall(peer.uid)
    }

    /** Leaves the current 1:1 thread and returns to the channel/peer picker. */
    fun closeConversation() {
        listener?.remove()
        listener = null
        activeKey = null
        callListener?.remove()
        callListener = null
        callSignaling = null
        _messages.value = emptyList()
        _draft.value = ""
        _active.value = null
    }

    fun updateDraft(text: String) {
        _draft.value = text
    }

    fun send() {
        val key = activeKey ?: return
        val peer = _active.value?.peer ?: return
        val text = _draft.value.trim()
        if (text.isBlank()) return
        _draft.value = ""
        viewModelScope.launch {
            runCatching { client.send(key, localName, peer.uid, peer.name, text) }
                .onFailure { _sendError.tryEmit(R.string.authority_msg_send_failed) }
        }
    }

    fun appErrorText(messageRes: Int): String = appContext.getString(messageRes)

    override fun onCleared() {
        super.onCleared()
        listener?.remove()
        listener = null
        callListener?.remove()
        callListener = null
    }
}
