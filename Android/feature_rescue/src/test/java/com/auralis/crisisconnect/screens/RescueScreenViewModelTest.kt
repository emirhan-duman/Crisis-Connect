package com.auralis.crisisconnect.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class RescueScreenViewModelTest {

    @Test
    fun `connection updates preserve beacon lastSeen timestamp`() {
        val current = RescueScreenViewModel.SOSBroadcast(
            address = "AA:BB:CC:DD:EE:FF",
            sessionCode = "ble:old",
            broadcastId = "CC-OLD",
            channelId = "DUAL-ND-123456",
            userId = "Victim",
            status = "Hazir",
            deviceName = "Beacon",
            serviceUuid = null,
            rssi = -68,
            lastSeen = 1_000L,
            lastUpdated = 1_100L,
        )

        val updated = RescueScreenViewModel.updateBroadcastFromConnectionState(
            current = current,
            address = current.address,
            sessionCode = "ble:new",
            broadcastId = "CC-NEW",
            status = "Baglanti kesildi",
            deviceName = null,
            userId = null,
            now = 5_000L,
        )

        assertEquals(1_000L, updated.lastSeen)
        assertEquals(5_000L, updated.lastUpdated)
        assertEquals("DUAL-ND-123456", updated.channelId)
        assertEquals("Victim", updated.userId)
        assertEquals("CC-NEW", updated.broadcastId)
        assertEquals("ble:new", updated.sessionCode)
    }

    @Test
    fun `latest scan mode can downgrade stale dual mode`() {
        val resolved = RescueScreenViewModel.reconcileBroadcastModeForScan(
            current = RescueScreenViewModel.BroadcastMode.DUAL,
            incoming = RescueScreenViewModel.BroadcastMode.MESH,
        )

        assertEquals(RescueScreenViewModel.BroadcastMode.MESH, resolved)
    }
}
