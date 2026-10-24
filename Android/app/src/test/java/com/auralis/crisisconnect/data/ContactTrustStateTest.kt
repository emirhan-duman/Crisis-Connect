package com.auralis.crisisconnect.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactTrustStateTest {

    @Test fun relabelingRetiredKeyAsV2DoesNotReactivateIt() {
        val old = ContactEntity(sessionCode = "legacy", name = "Family", aesKey = "old-secret", offlineKeyVersion = 1)
        val relabeled = old.copy(offlineKeyVersion = 2, verified = true, verifiedIdentityKey = "device:claimed")
        val saved = preserveLegacyOfflineStorage(relabeled, old)
        assertEquals("old-secret", saved.aesKey)
        assertTrue(saved.toContact().offlineRepairRequired)
        assertEquals("", saved.toContact().aesKey)
        assertFalse(saved.toContact().verified)
    }

    @Test fun retainedLegacyKeyNeverReachesTransportFacingContactOrTrust() {
        val row = ContactEntity(sessionCode = "legacy", name = "Family", aesKey = "retained-secret", offlineKeyVersion = 1,
            verified = true, verifiedIdentityKey = "device:legacy", verifiedAt = 123)
        val contact = row.toContact()
        assertEquals("", contact.aesKey)
        assertTrue(contact.offlineRepairRequired)
        assertFalse(contact.verified)
        assertEquals("", contact.verifiedIdentityKey)
        assertEquals("retained-secret", row.aesKey)
    }

    @Test fun metadataSavePreservesQuarantinedBytesButCannotReactivateThem() {
        val row = ContactEntity(sessionCode = "legacy", name = "Family", aesKey = "retained-secret", offlineKeyVersion = 1)
        val updated = row.toContact().copy(name = "Renamed")
        val persisted = preserveLegacyOfflineStorage(updated.toEntity(), row)
        assertEquals("retained-secret", persisted.aesKey)
        assertEquals("Renamed", persisted.name)
        assertEquals(1, persisted.offlineKeyVersion)
        assertEquals("", persisted.toContact().aesKey)
        assertTrue(persisted.toContact().offlineRepairRequired)
    }

    @Test fun freshPairingReplacesQuarantinedKeyAndMakesOnlyNewKeyEligible() {
        val row = ContactEntity(sessionCode = "legacy", name = "Family", aesKey = "old-secret", offlineKeyVersion = 1)
        val fresh = Contact(name = "Family", sessionCode = "legacy", aesKey = "new-pair-secret").toEntity()
        val persisted = preserveLegacyOfflineStorage(fresh, row)
        assertEquals(2, persisted.offlineKeyVersion)
        assertEquals("new-pair-secret", persisted.toContact().aesKey)
        assertFalse(persisted.toContact().offlineRepairRequired)
    }

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
