package com.auralis.crisisconnect.service.gattmesh.ptt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttFloorRulesTest {

    private fun grant(
        floor: PttFloorState,
        localClaimId: Long? = null,
        remoteSpeaker: String? = null,
        remoteClaimId: Long = Long.MIN_VALUE,
        from: String = "peer-a",
        incoming: Long = 0L
    ) = PttFloorRules.shouldGrantRemoteClaim(
        floor = floor,
        localClaimId = localClaimId,
        currentRemoteSpeaker = remoteSpeaker,
        currentRemoteClaimId = remoteClaimId,
        fromAddress = from,
        incomingClaimId = incoming
    )

    @Test
    fun idleAlwaysGrants() {
        assertTrue(grant(PttFloorState.IDLE, incoming = Long.MIN_VALUE))
        assertTrue(grant(PttFloorState.IDLE, incoming = 42L))
    }

    @Test
    fun localKeepsFloorWhenIncomingClaimIsLowerOrEqual() {
        assertFalse(grant(PttFloorState.LOCAL_SPEAKING, localClaimId = 100L, incoming = 100L))
        assertFalse(grant(PttFloorState.LOCAL_SPEAKING, localClaimId = 100L, incoming = 50L))
    }

    @Test
    fun localYieldsWhenIncomingClaimIsHigher() {
        assertTrue(grant(PttFloorState.LOCAL_SPEAKING, localClaimId = 100L, incoming = 101L))
    }

    @Test
    fun remoteSameSpeakerAlwaysRefreshes() {
        assertTrue(
            grant(
                PttFloorState.REMOTE_SPEAKING,
                remoteSpeaker = "peer-a",
                remoteClaimId = 999L,
                from = "peer-a",
                incoming = 1L
            )
        )
    }

    @Test
    fun remoteOtherSpeakerNeedsHigherClaim() {
        assertTrue(
            grant(
                PttFloorState.REMOTE_SPEAKING,
                remoteSpeaker = "peer-a",
                remoteClaimId = 100L,
                from = "peer-b",
                incoming = 101L
            )
        )
        assertFalse(
            grant(
                PttFloorState.REMOTE_SPEAKING,
                remoteSpeaker = "peer-a",
                remoteClaimId = 100L,
                from = "peer-b",
                incoming = 100L
            )
        )
    }

    /**
     * Two devices that press at the same instant must converge on a single speaker: exactly one of
     * them grants the other's claim (the one with the lower claimId yields), so the higher claimId
     * ends up holding the floor on both sides.
     */
    @Test
    fun simultaneousClaimsConvergeOnHigherClaimId() {
        val claimA = 100L
        val claimB = 200L // B wins

        // From A's perspective (A is LOCAL_SPEAKING), B's higher claim arrives → A yields.
        val aYields = grant(PttFloorState.LOCAL_SPEAKING, localClaimId = claimA, from = "B", incoming = claimB)
        // From B's perspective (B is LOCAL_SPEAKING), A's lower claim arrives → B keeps the floor.
        val bYields = grant(PttFloorState.LOCAL_SPEAKING, localClaimId = claimB, from = "A", incoming = claimA)

        assertTrue(aYields)
        assertFalse(bYields)
    }
}
