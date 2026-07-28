package com.auralis.crisisconnect.nearby

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles the Accept/Decline actions on an incoming nearby-pairing approval notification. */
class NearbyPairingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return
        when (intent.action) {
            ACTION_ACCEPT -> NearbyPairingServer.accept(address)
            ACTION_DECLINE -> NearbyPairingServer.decline(address)
        }
    }

    companion object {
        const val ACTION_ACCEPT = "com.auralis.crisisconnect.nearby.PAIR_ACCEPT"
        const val ACTION_DECLINE = "com.auralis.crisisconnect.nearby.PAIR_DECLINE"
        const val EXTRA_ADDRESS = "address"
    }
}
