package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthorityMlsDurableStateTest {
    @Test
    fun nearbyEnvelopeRoundTripsAndRejectsTampering() {
        val conversationId = "am2_" + "A".repeat(43)
        val message = AuthorityMlsCiphertextMessage(
            messageId = "m_cross_platform",
            senderUid = "authority-user",
            senderDeviceId = "android-device",
            senderCredential = "cc-mls:v1:YXV0aG9yaXR5LXVzZXI:YW5kcm9pZC1kZXZpY2U",
            ciphertext = "AQIDBA",
        )
        val encoded = AuthorityMlsOfflineEnvelopeCodec.encode(conversationId, message)
        val decoded = AuthorityMlsOfflineEnvelopeCodec.decode(encoded)
        assertEquals(conversationId, decoded?.conversationId)
        assertEquals(message, decoded?.message)
        assertEquals(null, AuthorityMlsOfflineEnvelopeCodec.decode(encoded + "!"))
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityMlsOfflineEnvelopeCodec.encode(conversationId, message.copy(senderUid = ""))
        }
    }

    @Test
    fun roundTripsSnapshotCursorAndOutbox() {
        val state = AuthorityMlsDurableState(
            snapshot = byteArrayOf(1, 2, 3, -1),
            nextControlSequence = (1L shl 40) + 17,
            nextApplicationSequence = (1L shl 39) + 9,
            pendingControlEvents = listOf("{\"type\":\"shareKeyPackage\"}", "güvenli"),
            pendingApplicationMessages = listOf(AuthorityMlsPendingApplication("m_abc123", "AQIDBA")),
            pendingReceivedApplications = listOf(AuthorityMlsPendingReceivedApplication(
                "m_received", "cc-mls:v1:dTE:ZDE", byteArrayOf(4, 5, 6),
            )),
            offlineReceipts = listOf(AuthorityMlsOfflineReceipt(
                "m_offline", "cc-mls:v1:dTI:ZDI", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            )),
        )
        val decoded = AuthorityMlsDurableStateCodec.decode(AuthorityMlsDurableStateCodec.encode(state))
        assertArrayEquals(state.snapshot, decoded.snapshot)
        assertEquals(state.nextControlSequence, decoded.nextControlSequence)
        assertEquals(state.nextApplicationSequence, decoded.nextApplicationSequence)
        assertEquals(state.pendingControlEvents, decoded.pendingControlEvents)
        assertEquals(state.pendingApplicationMessages, decoded.pendingApplicationMessages)
        assertEquals(state.pendingReceivedApplications.first().messageId, decoded.pendingReceivedApplications.first().messageId)
        assertArrayEquals(state.pendingReceivedApplications.first().plaintext, decoded.pendingReceivedApplications.first().plaintext)
        assertEquals(state.offlineReceipts, decoded.offlineReceipts)
    }

    @Test
    fun rejectsTruncationTrailingDataAndInvalidSequence() {
        val valid = AuthorityMlsDurableStateCodec.encode(
            AuthorityMlsDurableState(
                byteArrayOf(9), 0, 0, listOf("event"), emptyList(), emptyList(), emptyList(),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityMlsDurableStateCodec.decode(valid.copyOf(valid.size - 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityMlsDurableStateCodec.decode(valid + byteArrayOf(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AuthorityMlsDurableStateCodec.encode(
                AuthorityMlsDurableState(
                    byteArrayOf(1), -1, 0, emptyList(), emptyList(), emptyList(), emptyList(),
                ),
            )
        }
    }
}
