package com.auralis.crisisconnect.service.gattmesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiDirectGroupTest {

    private val keyA = ByteArray(32) { it.toByte() }
    private val keyB = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `network name starts with the required DIRECT prefix`() {
        assertTrue(WifiDirectGroup.networkName(keyA).startsWith("DIRECT-cc-"))
    }

    @Test
    fun `same group key derives identical credentials on every device`() {
        assertEquals(WifiDirectGroup.networkName(keyA), WifiDirectGroup.networkName(keyA.copyOf()))
        assertEquals(WifiDirectGroup.passphrase(keyA), WifiDirectGroup.passphrase(keyA.copyOf()))
    }

    @Test
    fun `different group keys derive different credentials`() {
        assertNotEquals(WifiDirectGroup.networkName(keyA), WifiDirectGroup.networkName(keyB))
        assertNotEquals(WifiDirectGroup.passphrase(keyA), WifiDirectGroup.passphrase(keyB))
    }

    @Test
    fun `passphrase is within WPA2 length bounds`() {
        val passphrase = WifiDirectGroup.passphrase(keyA)
        assertTrue(passphrase.length in 8..63)
    }

    @Test
    fun `lone device never hosts a group`() {
        assertFalse(WifiDirectGroup.shouldHostGroup(selfNodeId = "0001", peerNodeIds = emptySet()))
    }

    @Test
    fun `lowest node id hosts the group`() {
        assertTrue(
            WifiDirectGroup.shouldHostGroup(
                selfNodeId = "0001",
                peerNodeIds = setOf("0002", "00ff")
            )
        )
    }

    @Test
    fun `higher node id joins as client`() {
        assertFalse(
            WifiDirectGroup.shouldHostGroup(
                selfNodeId = "00ff",
                peerNodeIds = setOf("0001", "0002")
            )
        )
    }
}
