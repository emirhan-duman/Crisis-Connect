package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityMlsJoinTest {
    private val joiningCredential = "credential-new"

    @Test
    fun recognizesOnlyTheAuthenticatedLocalKeyPackage() {
        val payload = payload(
            type = "shareKeyPackage",
            senderId = joiningCredential,
            senderUid = "account-a",
        )
        assertTrue(isAuthorityMlsLocalKeyPackage(payload, joiningCredential, "account-a"))
        assertFalse(isAuthorityMlsLocalKeyPackage(payload, "credential-other", "account-a"))
    }

    @Test
    fun skipsPreMembershipEpochsAndAcceptsOnlyTheAddressedWelcome() {
        val senderCredential = "sponsor"
        val senderUid = "account-b"
        assertEquals(
            AuthorityMlsPreJoinControlDisposition.SKIP,
            classifyAuthorityMlsPreJoinControl(
                payload("sendMlsMessage", senderCredential, senderUid),
                senderCredential,
                senderUid,
                joiningCredential,
            ),
        )
        assertEquals(
            AuthorityMlsPreJoinControlDisposition.SKIP,
            classifyAuthorityMlsPreJoinControl(
                payload("sendMlsWelcome", senderCredential, senderUid, "another-device", 0),
                senderCredential,
                senderUid,
                joiningCredential,
            ),
        )
        assertEquals(
            AuthorityMlsPreJoinControlDisposition.WELCOME,
            classifyAuthorityMlsPreJoinControl(
                payload("sendMlsWelcome", senderCredential, senderUid, joiningCredential, 0),
                senderCredential,
                senderUid,
                joiningCredential,
            ),
        )
        assertEquals(
            AuthorityMlsPreJoinControlDisposition.INVALID,
            classifyAuthorityMlsPreJoinControl(
                payload("sendMlsWelcome", "forged", senderUid, joiningCredential, 0),
                senderCredential,
                senderUid,
                joiningCredential,
            ),
        )
    }

    @Test
    fun prefersTheWokenPeerAccountAsSponsor() {
        val owners = mapOf(
            "credential-new" to "joining-account",
            "credential-z" to "joining-account",
            "credential-a" to "peer-account",
            "credential-m" to "peer-account",
        )
        assertEquals(
            "credential-a",
            authorityMlsJoinSponsor(
                listOf("credential-new", "credential-z", "credential-a", "credential-m"),
                owners,
                "joining-account",
                "credential-new",
            ),
        )
    }

    @Test
    fun fallsBackToSameAccountAndRejectsMalformedRosters() {
        val owners = mapOf(
            "credential-new" to "joining-account",
            "credential-z" to "joining-account",
        )
        assertEquals(
            "credential-z",
            authorityMlsJoinSponsor(
                listOf("credential-new", "credential-z"),
                owners,
                "joining-account",
                "credential-new",
            ),
        )
        assertNull(
            authorityMlsJoinSponsor(
                listOf("duplicate", "duplicate"),
                mapOf("duplicate" to "peer"),
                "joining",
                "joining-device",
            ),
        )
        assertNull(authorityMlsJoinSponsor(emptyList(), owners, "joining-account", "credential-new"))
        assertNull(authorityMlsJoinSponsor(listOf("unknown"), owners, "joining-account", "credential-new"))
    }

    @Test
    fun requiresExactApplicationBoundaries() {
        assertEquals(
            AuthorityMlsControlOrdering("shareKeyPackage", null),
            authorityMlsControlOrdering(payload("shareKeyPackage", "leaf", "account-a")),
        )
        assertEquals(
            AuthorityMlsControlOrdering("sendMlsWelcome", 7),
            authorityMlsControlOrdering(payload("sendMlsWelcome", "leaf", "account-a", "recipient", 7)),
        )
        assertNull(authorityMlsControlOrdering(payload("sendMlsWelcome", "leaf", "account-a", "recipient")))
        assertNull(authorityMlsControlOrdering(payload("sendMlsMessage", "leaf", "account-a", boundary = -1)))
    }

    private fun payload(
        type: String,
        senderId: String,
        senderUid: String,
        recipientId: String? = null,
        boundary: Long? = null,
    ): String = buildString {
        append("{\"type\":\"").append(type)
        append("\",\"senderId\":\"").append(senderId)
        append("\",\"senderUid\":\"").append(senderUid).append('"')
        if (recipientId != null) append(",\"recipientId\":\"").append(recipientId).append('"')
        if (boundary != null) append(",\"applicationSequenceBoundary\":").append(boundary)
        append('}')
    }
}
