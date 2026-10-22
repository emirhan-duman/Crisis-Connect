package com.auralis.crisisconnect.screens.Chat

enum class ChatConnectionState { Idle, Connecting, Available, Connected, Error }

enum class ChatTransport { Bluetooth, Internet, Satellite }

/** One atomic UI value. Internet availability never asserts a live peer/server connection. */
data class ChatConnectionStatus(
    val state: ChatConnectionState = ChatConnectionState.Idle,
    val transport: ChatTransport = ChatTransport.Bluetooth,
    val bluetoothReady: Boolean = false,
) {
    fun allowsLocationUpdates(saving: Boolean): Boolean = bluetoothReady ||
        (!saving && state == ChatConnectionState.Available && transport == ChatTransport.Internet)
}

object ChatConnectionPolicy {
    fun resolve(
        bluetoothReady: Boolean,
        internetAvailable: Boolean,
        satellite: Boolean,
        pending: ChatConnectionState,
    ): ChatConnectionStatus = when {
        bluetoothReady -> ChatConnectionStatus(ChatConnectionState.Connected, ChatTransport.Bluetooth, true)
        internetAvailable -> ChatConnectionStatus(ChatConnectionState.Available,
            if (satellite) ChatTransport.Satellite else ChatTransport.Internet)
        pending == ChatConnectionState.Connected || pending == ChatConnectionState.Available ->
            ChatConnectionStatus(ChatConnectionState.Error)
        else -> ChatConnectionStatus(pending)
    }
}
