package com.auralis.crisisconnect.nearby

import com.auralis.crisisconnect.data.Contact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gate that decides whether a chat-open should try to auto-bootstrap an offline Bluetooth link.
 * These pin the exact conditions so the feature only fires for a fresh internet-added contact and
 * never re-fires once a Bluetooth key/address is in place.
 */
class NearbyAutoLinkEligibilityTest {

    private fun internetContact(
        peerPhone: String = "+905551112233",
        aesKey: String = "",
        address: String = "",
        peerUid: String = "uid-123",
        peerPublicKey: String = "pk-abc"
    ) = Contact(
        name = "Peer",
        aesKey = aesKey,
        sessionCode = "pair-1",
        address = address,
        peerUid = peerUid,
        peerPublicKey = peerPublicKey,
        peerPhone = peerPhone
    )

    @Test
    fun `eligible for a fresh internet contact with a stored number`() {
        assertTrue(NearbyAutoLink.isEligible(internetContact()))
    }

    @Test
    fun `not eligible when the contact is null`() {
        assertFalse(NearbyAutoLink.isEligible(null))
    }

    @Test
    fun `not eligible without a stored number`() {
        assertFalse(NearbyAutoLink.isEligible(internetContact(peerPhone = "")))
    }

    @Test
    fun `not eligible once a Bluetooth key already exists`() {
        assertFalse(NearbyAutoLink.isEligible(internetContact(aesKey = "sharedkey")))
    }

    @Test
    fun `not eligible once a Bluetooth address already exists`() {
        assertFalse(NearbyAutoLink.isEligible(internetContact(address = "AA:BB:CC:DD:EE:FF")))
    }

    @Test
    fun `not eligible when the contact is not internet-capable`() {
        // supportsInternet requires BOTH a peer uid and a peer public key.
        assertFalse(NearbyAutoLink.isEligible(internetContact(peerUid = "")))
        assertFalse(NearbyAutoLink.isEligible(internetContact(peerPublicKey = "")))
    }

    // --- Identity guard: an auto-link is only accepted if it stayed the same authenticated peer ---

    @Test
    fun `identity guard accepts the same peer after linking`() {
        val expected = internetContact(peerUid = "uid-1", peerPublicKey = "pk-1")
        val stored = expected.copy(aesKey = "btkey", address = "AA:BB:CC:DD:EE:FF")
        assertTrue(NearbyAutoLink.isSameAuthenticatedIdentity(expected, stored))
    }

    @Test
    fun `identity guard rejects a swapped uid (impersonation)`() {
        val expected = internetContact(peerUid = "uid-1", peerPublicKey = "pk-1")
        val stored = expected.copy(peerUid = "attacker-uid", aesKey = "btkey", address = "AA:BB:CC:DD:EE:FF")
        assertFalse(NearbyAutoLink.isSameAuthenticatedIdentity(expected, stored))
    }

    @Test
    fun `identity guard rejects a swapped public key`() {
        val expected = internetContact(peerUid = "uid-1", peerPublicKey = "pk-1")
        val stored = expected.copy(peerPublicKey = "attacker-key", aesKey = "btkey")
        assertFalse(NearbyAutoLink.isSameAuthenticatedIdentity(expected, stored))
    }

    @Test
    fun `identity guard rejects a missing stored contact`() {
        assertFalse(NearbyAutoLink.isSameAuthenticatedIdentity(internetContact(), null))
    }
}
