package com.auralis.crisisconnect.service.mesh

data class MeshAwareServiceState(
    val isEnabled: Boolean = false,
    val isBusy: Boolean = false,
    val connectedPeerCount: Int = 0,
    val errorMessage: Int? = null,
    val peerAuthEvent: MeshPeerAuthEvent? = null,
)

data class MeshPeerAuthEvent(
    val eventId: Long,
    val peerLabel: String,
)
