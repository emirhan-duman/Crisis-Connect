package com.auralis.crisisconnect.messaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorityMlsReadinessTest {
    private val directory = listOf(
        record("sender", "sender-active"),
        record("sender", "sender-stale"),
        record("recipient", "recipient-active"),
    )

    @Test
    fun staleRegisteredDeviceDoesNotDeadlockAnEstablishedRoster() {
        assertTrue(
            isAuthorityMlsRosterReady(
                participants = listOf("sender", "recipient"),
                directory = directory,
                rosterCredentials = listOf("sender-active", "recipient-active"),
                localCredential = "sender-active",
            ),
        )
    }

    @Test
    fun everyParticipantAndTheExactLocalLeafMustBeRepresented() {
        assertFalse(
            isAuthorityMlsRosterReady(
                listOf("sender", "recipient"),
                directory,
                listOf("sender-active"),
                "sender-active",
            ),
        )
        assertFalse(
            isAuthorityMlsRosterReady(
                listOf("sender", "recipient"),
                directory,
                listOf("sender-stale", "recipient-active"),
                "sender-active",
            ),
        )
    }

    @Test
    fun unverifiedAndDuplicateRosterLeavesFailClosed() {
        assertFalse(
            isAuthorityMlsRosterReady(
                listOf("sender", "recipient"),
                directory,
                listOf("sender-active", "recipient-active", "attacker"),
                "sender-active",
            ),
        )
        assertFalse(
            isAuthorityMlsRosterReady(
                listOf("sender", "recipient"),
                directory,
                listOf("sender-active", "recipient-active", "recipient-active"),
                "sender-active",
            ),
        )
    }

    private fun record(uid: String, credential: String) = AuthorityMlsDirectoryRecord(
        uid = uid,
        deviceId = credential,
        credential = credential,
        signingPublicKey = ByteArray(32),
        label = "",
    )
}
