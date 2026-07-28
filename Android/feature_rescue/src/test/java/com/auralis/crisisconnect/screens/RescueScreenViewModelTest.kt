package com.auralis.crisisconnect.screens

import com.auralis.crisisconnect.ai.CrisisSentinelUserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `rescue ai mode follows stored rescue role`() {
        assertEquals(
            CrisisSentinelUserMode.Coordinator,
            RescueScreenViewModel.rescueAiModeForRole("admin")
        )
        assertEquals(
            CrisisSentinelUserMode.FieldTeam,
            RescueScreenViewModel.rescueAiModeForRole("fieldteam")
        )
        assertEquals(
            CrisisSentinelUserMode.Public,
            RescueScreenViewModel.rescueAiModeForRole(null)
        )
    }

    @Test
    fun `rescue ai signal prompt summarizes active signals without mac address`() {
        val old = RescueScreenViewModel.SOSBroadcast(
            address = "AA:BB:CC:DD:EE:FF",
            sessionCode = "ble:old",
            broadcastId = "CC-OLD",
            channelId = "SOS-ND-OLD",
            userId = "Responder Old",
            status = "Hazir",
            deviceName = "Beacon",
            serviceUuid = null,
            rssi = -80,
            lastSeen = 1_000L,
            lastUpdated = 1_000L,
            isSosVictim = true,
            sosState = RescueScreenViewModel.SosState.CLEARED,
            clearedAtMillis = 2_000L,
        )
        val active = old.copy(
            address = "11:22:33:44:55:66",
            sessionCode = "ble:active",
            channelId = "DUAL-ND-ACTIVE",
            userId = "Responder Active",
            status = "Aktif",
            rssi = -61,
            lastSeen = 5_000L,
            sosState = RescueScreenViewModel.SosState.ACTIVE,
            clearedAtMillis = null,
        )

        val prompt = RescueScreenViewModel.buildRescueAiSignalPrompt(
            broadcasts = listOf(old, active),
            activeStatus = "Aktif"
        )

        assertTrue(prompt.contains("1 aktif / 2 toplam"))
        assertTrue(prompt.contains("Responder Active"))
        assertTrue(prompt.contains("DUAL-ND-ACTIVE"))
        assertFalse(prompt.contains("11:22:33:44:55:66"))
        assertFalse(prompt.contains("AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `rescue ai context is bounded to recent five signals`() {
        val broadcasts = (1..7).map { index ->
            RescueScreenViewModel.SOSBroadcast(
                address = "AA:BB:CC:DD:EE:${index.toString().padStart(2, '0')}",
                sessionCode = "ble:$index",
                broadcastId = "CC-$index",
                channelId = "SOS-ND-$index",
                userId = "Responder $index",
                status = "Aktif",
                deviceName = null,
                serviceUuid = null,
                rssi = -60 - index,
                lastSeen = index * 1_000L,
                lastUpdated = index * 1_000L,
            )
        }

        val context = RescueScreenViewModel.buildRescueAiContext(broadcasts).single()

        assertTrue(context.contains("SOS-ND-7"))
        assertTrue(context.contains("SOS-ND-3"))
        assertFalse(context.contains("SOS-ND-2"))
        assertFalse(context.contains("SOS-ND-1"))
    }
}
