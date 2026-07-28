package com.auralis.crisisconnect.service.gattmesh.ptt

/**
 * Public UI-facing state for the authority-mesh telsiz (push-to-talk).
 *
 * The telsiz is a half-duplex walkie-talkie layered on the authority GATT mesh: one speaker at a
 * time (single-speaker floor lock), Opus audio broadcast single-hop to directly connected peers.
 * These types are public because [com.auralis.crisisconnect.service.gattmesh.GattMeshServiceBinding]
 * exposes them to the rescue feature module UI.
 */
enum class PttFloorState {
    /** Nobody is talking; the floor is free to claim. */
    IDLE,

    /** This device holds the floor and is transmitting. */
    LOCAL_SPEAKING,

    /** A remote peer holds the floor and is transmitting. */
    REMOTE_SPEAKING
}

data class PttParticipant(
    /** Peer route key/address, or [SELF_ADDRESS] for the local device. */
    val address: String,
    val displayName: String,
    val isSelf: Boolean = false,
    val isSpeaking: Boolean = false,
    /** Verified institution (AFAD/FEMA…) the participant belongs to, shown to the right of the name. */
    val agency: String? = null
) {
    companion object {
        const val SELF_ADDRESS = "self"
    }
}

data class PttSessionState(
    /** Whether the local user has joined the telsiz session. */
    val joined: Boolean = false,
    /** Everyone currently joined (including self when [joined]). */
    val participants: List<PttParticipant> = emptyList(),
    val floor: PttFloorState = PttFloorState.IDLE,
    /** Display name of whoever currently holds the floor, or null when idle. */
    val speakerName: String? = null,
    /**
     * Monotonic counter bumped whenever the local user pressed talk but the floor was already
     * taken. The UI observes changes to surface a transient "telsiz meşgul" hint.
     */
    val busyTick: Int = 0,
    /**
     * Monotonic counter bumped when the local transmission was auto-released for hitting the max
     * talk duration. The UI observes changes to surface a transient "süre doldu" hint.
     */
    val maxTalkTick: Int = 0
) {
    val isActive: Boolean get() = joined || participants.isNotEmpty()
    val participantCount: Int get() = participants.size
}
