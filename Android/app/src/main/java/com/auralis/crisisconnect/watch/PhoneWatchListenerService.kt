package com.auralis.crisisconnect.watch

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PhoneWatchListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchPaths.COMMAND) return
        val command = WatchCommand.decode(event.data) ?: return
        // Keep the listener alive until the bounded action and acknowledgement finish.
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(8_000) { PhoneWatchBridge.handle(applicationContext, event.sourceNodeId, command) }
        }
    }
}
