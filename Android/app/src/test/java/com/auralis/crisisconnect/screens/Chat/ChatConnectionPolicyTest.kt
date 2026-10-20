package com.auralis.crisisconnect.screens.Chat

import org.junit.Assert.*
import org.junit.Test

class ChatConnectionPolicyTest {
    private fun resolve(bt: Boolean = false, internet: Boolean = false, satellite: Boolean = false,
                        pending: ChatConnectionState = ChatConnectionState.Idle) =
        ChatConnectionPolicy.resolve(bt, internet, satellite, pending)

    @Test fun losingEveryRouteClearsTheOldConnectedState() {
        val current = resolve(pending = ChatConnectionState.Connected)
        assertEquals(ChatConnectionState.Error, current.state)
        assertFalse(current.bluetoothReady)
    }

    @Test fun internetReturnRecoversWithoutSendingAMessage() {
        val current = resolve(internet = true, pending = ChatConnectionState.Error)
        assertEquals(ChatConnectionState.Available, current.state)
        assertEquals(ChatTransport.Internet, current.transport)
    }

    @Test fun internetDoesNotProveBluetoothOrPeerPresence() {
        val current = resolve(internet = true, pending = ChatConnectionState.Connected)
        assertEquals(ChatConnectionState.Available, current.state)
        assertFalse(current.bluetoothReady)
        assertNotEquals(ChatConnectionState.Connected, current.state)
    }

    @Test fun failedOrLateInternetResultCannotOverwriteReadyBluetooth() {
        for (event in ChatConnectionState.entries) {
            val current = resolve(bt = true, internet = true, pending = event)
            assertEquals(ChatConnectionState.Connected, current.state)
            assertEquals(ChatTransport.Bluetooth, current.transport)
            assertTrue(current.bluetoothReady)
        }
    }

    @Test fun verifiedBluetoothTakesOverWithoutAPresentationTimer() {
        assertEquals(ChatTransport.Internet, resolve(internet = true).transport)
        assertEquals(ChatTransport.Bluetooth, resolve(bt = true, internet = true).transport)
    }

    @Test fun internetLossDuringHandoverKeepsVerifiedBluetooth() {
        val current = resolve(bt = true, pending = ChatConnectionState.Error)
        assertEquals(ChatConnectionState.Connected, current.state)
        assertTrue(current.bluetoothReady)
    }

    @Test fun bluetoothHandshakeDoesNotOpenBluetoothOnlyFeatures() {
        val current = resolve(pending = ChatConnectionState.Connecting)
        assertEquals(ChatConnectionState.Connecting, current.state)
        assertFalse(current.bluetoothReady)
    }

    @Test fun backgroundBluetoothFailureDoesNotHideAvailableInternet() {
        assertEquals(ChatConnectionState.Available, resolve(internet = true, pending = ChatConnectionState.Error).state)
    }

    @Test fun satelliteAvailabilityNeverBecomesABluetoothConnection() {
        val current = resolve(internet = true, satellite = true)
        assertEquals(ChatConnectionState.Available, current.state)
        assertEquals(ChatTransport.Satellite, current.transport)
        assertFalse(current.bluetoothReady)
    }

    @Test fun staleAvailableHintCannotSurviveNetworkLoss() {
        assertEquals(ChatConnectionState.Error, resolve(pending = ChatConnectionState.Available).state)
    }

    @Test fun normalInternetLocationUpdatesRemainAvailableWithoutClaimingBluetooth() {
        val current = resolve(internet = true)
        assertTrue(current.allowsLocationUpdates(saving = false))
        assertFalse(current.bluetoothReady)
    }

    @Test fun savingAndSatellitePathsDoNotStartAutomaticInternetLocationTracking() {
        assertFalse(resolve(internet = true).allowsLocationUpdates(saving = true))
        assertFalse(resolve(internet = true, satellite = true).allowsLocationUpdates(saving = false))
        assertFalse(resolve().allowsLocationUpdates(saving = false))
        assertTrue(resolve(bt = true, internet = true).allowsLocationUpdates(saving = true))
    }

    @Test fun noContactOrAuthenticationWithNoBluetoothCannotClaimConnected() {
        // The caller supplies internet=false for unsupported contacts or a signed-out account.
        assertEquals(ChatConnectionState.Idle, resolve().state)
        assertEquals(ChatConnectionState.Error, resolve(pending = ChatConnectionState.Connected).state)
    }

    @Test fun allRouteCombinationsMaintainAtomicStatusAndCapabilityInvariants() {
        for (bt in listOf(false, true)) for (internet in listOf(false, true))
            for (satellite in listOf(false, true)) for (pending in ChatConnectionState.entries) {
                val current = resolve(bt, internet, satellite, pending)
                assertEquals(bt, current.bluetoothReady)
                assertEquals(bt, current.state == ChatConnectionState.Connected)
                assertEquals(!bt && internet, current.state == ChatConnectionState.Available)
                if (bt) assertEquals(ChatTransport.Bluetooth, current.transport)
            }
    }
}
