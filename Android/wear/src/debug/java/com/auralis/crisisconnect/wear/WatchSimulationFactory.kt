package com.auralis.crisisconnect.wear

import android.content.Intent
import android.os.Build
import com.auralis.crisisconnect.watch.*
import java.util.UUID
import kotlinx.coroutines.delay

fun createWatchSimulation(intent: Intent): WatchSimulation? {
    val emulator = Build.HARDWARE in setOf("ranchu", "goldfish")
    val scenario = intent.getStringExtra("crisis_watch_scenario")
    if (!emulator || scenario !in setOf("idle", "active", "disconnected", "unconfirmed", "phoneConfirmation")) return null
    return LocalWatchSimulation(requireNotNull(scenario), intent.getStringExtra("crisis_watch_language") == "tr")
}

private class LocalWatchSimulation(private val scenario: String, override val turkish: Boolean) : WatchSimulation {
    private var state = PhoneWatchState(
        sosActive = scenario == "active", bleBroadcasting = scenario == "active",
        emergencyID = if (scenario == "active") UUID.randomUUID().toString() else "",
        peerID = "test-rescue-peer", peerName = if (turkish) "Test kurtarma ekibi" else "Test rescue team",
        lastMessage = if (turkish) "Durumunuz nasıl?" else "How are you?"
    )

    override suspend fun exchange(command: WatchCommand): SimulatedWatchReceipt? {
        delay(500)
        if (scenario == "disconnected" || (scenario == "unconfirmed" && command.action != WatchAction.status)) return null
        if (scenario == "phoneConfirmation" && command.action == WatchAction.startSOS) {
            return SimulatedWatchReceipt("phoneConfirmation", state.copy(updatedAt = System.currentTimeMillis()))
        }
        state = when (command.action) {
            WatchAction.status -> state
            WatchAction.startSOS -> state.copy(sosActive = true, bleBroadcasting = true, emergencyID = UUID.randomUUID().toString())
            WatchAction.stopSOS -> state.copy(sosActive = false, bleBroadcasting = false, emergencyID = "")
            WatchAction.quickReply, WatchAction.contactReply -> state.copy(lastMessage = (if (turkish) "Test cevabı: " else "Test reply: ") +
                (if (turkish) command.reply?.turkish else command.reply?.english).orEmpty())
        }.copy(updatedAt = System.currentTimeMillis())
        return SimulatedWatchReceipt("accepted", state)
    }
}
