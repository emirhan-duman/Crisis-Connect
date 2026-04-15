package com.auralis.crisisconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTrustStateTest {

    @Test
    fun `mergeVerifiedTrust promotes verified contact when remote device id is present`() {
        val merged = mergeVerifiedTrust(
            incoming = Contact(
                name = "Peer",
                aesKey = "key",
                sessionCode = "ble:peer",
                verified = true,
                remoteDeviceId = "DEVICE-123"
            ),
            existing = null
        )

        assertTrue(merged.verified)
        assertEquals("device:device-123", merged.verifiedIdentityKey)
        assertTrue(merged.verifiedAt != null)
    }

    @Test
    fun `mergeVerifiedTrust preserves trust only when incoming identity matches`() {
        val existing = Contact(
            name = "Peer",
            aesKey = "key",
            sessionCode = "ble:peer",
            verified = true,
            verifiedIdentityKey = "device:device-123",
            verifiedAt = 42L,
            remoteDeviceId = "DEVICE-123"
        )

        val merged = mergeVerifiedTrust(
            incoming = Contact(
                name = "Peer Updated",
                aesKey = "key",
                sessionCode = "ble:peer",
                remoteDeviceId = "device-123"
            ),
            existing = existing
        )

        assertTrue(merged.verified)
        assertEquals("device:device-123", merged.verifiedIdentityKey)
        assertEquals(42L, merged.verifiedAt)
    }

    @Test
    fun `mergeVerifiedTrust drops trust when incoming identity is absent`() {
        val existing = Contact(
            name = "Peer",
            aesKey = "key",
            sessionCode = "ble:peer",
            verified = true,
            verifiedIdentityKey = "device:device-123",
            verifiedAt = 42L,
            remoteDeviceId = "DEVICE-123"
        )

        val merged = mergeVerifiedTrust(
            incoming = Contact(
                name = "Peer Updated",
                aesKey = "key",
                sessionCode = "ble:peer"
            ),
            existing = existing
        )

        assertFalse(merged.verified)
        assertEquals("", merged.verifiedIdentityKey)
        assertEquals(null, merged.verifiedAt)
    }

    @Test
    fun `normalizeVerifiedIdentityKey canonicalizes remote device ids`() {
        assertEquals("device:abc-123", normalizeVerifiedIdentityKey(" ABC-123 "))
        assertEquals("device:xyz", normalizeVerifiedIdentityKey("device:XYZ"))
        assertEquals("", normalizeVerifiedIdentityKey(""))
    }
}
