package com.auralis.crisisconnect.wear

import com.auralis.crisisconnect.watch.PhoneWatchState
import com.auralis.crisisconnect.watch.WatchCommand

/** Supplied only by non-production emulator factories; production always uses the Data Layer. */
interface WatchSimulation {
    val turkish: Boolean
    val developerLink: Boolean get() = false
    suspend fun exchange(command: WatchCommand): SimulatedWatchReceipt?
}

data class SimulatedWatchReceipt(val result: String, val state: PhoneWatchState)
