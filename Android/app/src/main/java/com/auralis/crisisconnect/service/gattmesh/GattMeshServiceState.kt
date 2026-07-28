package com.auralis.crisisconnect.service.gattmesh

import androidx.annotation.StringRes

data class GattMeshConnectedPeer(
    val address: String,
    val displayName: String,
    val verificationStatus: GattMeshPeerVerificationStatus = GattMeshPeerVerificationStatus.UNVERIFIED,
    val verifiedRole: String? = null,
    val verifiedAgency: String? = null,
    val verifiedAtMillis: Long? = null,
)

enum class GattMeshPeerVerificationStatus {
    UNVERIFIED,
    VERIFIED
}

enum class GattMeshSendDisposition {
    SENT,
    QUEUED
}

data class GattMeshSendResult(
    val packetId: String,
    val disposition: GattMeshSendDisposition
)

data class GattMeshServiceState(
    val isEnabled: Boolean = false,
    val isScanning: Boolean = false,
    val connectedPeerCount: Int = 0,
    val discoveredPeerCount: Int = 0,
    val sendReadyPeerCount: Int = 0,
    val connectedPeers: List<GattMeshConnectedPeer> = emptyList(),
    @StringRes val errorMessage: Int? = null,
)
