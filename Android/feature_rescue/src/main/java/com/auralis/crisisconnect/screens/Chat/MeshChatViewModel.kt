package com.auralis.crisisconnect.screens.Chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.MeshChatMessage
import com.auralis.crisisconnect.data.MeshChatStore
import com.auralis.crisisconnect.service.mesh.MeshAwareServiceBinding
import com.auralis.crisisconnect.service.mesh.MeshAwareServiceState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MeshChatViewModel(application: Application) : AndroidViewModel(application) {

    private val meshBinding = MeshAwareServiceBinding(application.applicationContext)

    private val _messageDraft = MutableStateFlow("")
    val messageDraft: StateFlow<String> = _messageDraft.asStateFlow()

    val meshState: StateFlow<MeshAwareServiceState> = meshBinding.state
    val messages: StateFlow<List<MeshChatMessage>> = MeshChatStore.messages

    private val _sendFailureEvents = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val sendFailureEvents: SharedFlow<Int> = _sendFailureEvents

    fun onScreenStarted() {
        meshBinding.bind(createIfNeeded = false)
        MeshChatStore.setChatOpen(true)
    }

    fun onScreenStopped() {
        MeshChatStore.setChatOpen(false)
    }

    fun updateDraft(text: String) {
        _messageDraft.value = text
    }

    fun sendMessage() {
        val content = _messageDraft.value.trim()
        if (content.isBlank()) return

        if (!isSecureMeshChatReady(meshState.value)) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_not_ready)
            return
        }

        val sent = meshBinding.sendGroupMessage(content)
        if (!sent) {
            _sendFailureEvents.tryEmit(R.string.mesh_chat_send_failed)
            return
        }

        MeshChatStore.appendLocalMessage(content)
        _messageDraft.value = ""
    }

    override fun onCleared() {
        super.onCleared()
        MeshChatStore.setChatOpen(false)
        meshBinding.unbind()
    }

    companion object {
        private const val MIN_TOTAL_DEVICES_FOR_CHAT = 2

        fun isSecureMeshChatReady(state: MeshAwareServiceState): Boolean {
            val totalDevices = state.connectedPeerCount + 1
            return state.isEnabled && totalDevices >= MIN_TOTAL_DEVICES_FOR_CHAT
        }
    }
}
