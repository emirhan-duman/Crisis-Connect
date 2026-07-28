package com.auralis.crisisconnect.service.gattmesh.ptt

/**
 * Pure decision logic for the single-speaker telsiz floor lock, factored out of [PttController] so
 * it can be unit-tested without Android/audio dependencies.
 *
 * Tie-break is by `claimId` (a random Long carried in each FLOOR_CLAIM): the higher claimId wins, so
 * two devices that press at the same instant independently converge on the same speaker. A claim
 * from the peer that already holds the floor always refreshes it.
 */
internal object PttFloorRules {

    /**
     * Whether an incoming remote FLOOR_CLAIM should (re)assign the floor to [fromAddress].
     *
     * @param floor current local floor state
     * @param localClaimId our claimId when we hold the floor (else null)
     * @param currentRemoteSpeaker address of the current remote speaker (when [floor] is REMOTE)
     * @param currentRemoteClaimId claimId of the current remote speaker
     * @param fromAddress address the incoming claim came from
     * @param incomingClaimId claimId carried by the incoming claim
     */
    fun shouldGrantRemoteClaim(
        floor: PttFloorState,
        localClaimId: Long?,
        currentRemoteSpeaker: String?,
        currentRemoteClaimId: Long,
        fromAddress: String,
        incomingClaimId: Long
    ): Boolean = when (floor) {
        PttFloorState.IDLE -> true
        PttFloorState.LOCAL_SPEAKING -> incomingClaimId > (localClaimId ?: Long.MIN_VALUE)
        PttFloorState.REMOTE_SPEAKING ->
            fromAddress == currentRemoteSpeaker || incomingClaimId > currentRemoteClaimId
    }
}
