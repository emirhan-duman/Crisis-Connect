package com.auralis.crisisconnect.service.gattmesh

internal sealed interface TransportReassemblyResult {
    data object Incomplete : TransportReassemblyResult
    data class Complete(val payload: ByteArray) : TransportReassemblyResult
    data class Rejected(val reason: String) : TransportReassemblyResult
}

internal data class ClientFailureBackoff(
    val attempt: Int,
    val status: Int,
    val lastFailureAtMillis: Long,
    val nextAllowedConnectAtMillis: Long
)

internal data class InboundRateWindow(
    val windowStartMillis: Long,
    val count: Int,
)

internal data class PeerVerificationState(
    val role: String,
    val verifiedAtMillis: Long
)

internal data class EncryptedMeshPayload(
    val keyId: String,
    val ivBase64: String,
    val cipherBase64: String
)

internal data class PendingOutboundRetryState(
    val failureCount: Int,
    val nextEligibleAtMillis: Long
)

internal data class MeshPacket(
    val id: String,
    val senderLabel: String,
    val timestampMillis: Long,
    val message: String,
    val type: MeshPacketType,
    val receiptType: ReceiptType = ReceiptType.DELIVERED,
    val receiptMessageIds: List<String> = emptyList(),
    val hop: Int,
    val protocol: String,
    val encrypted: Boolean = false,
    val keyId: String? = null,
    val encryptedIvBase64: String? = null,
    val encryptedCipherBase64: String? = null,
    val authNonce: String? = null,
    val authProofJson: String? = null,
    val originProofJson: String? = null,
    val originSignatureBase64: String? = null,
    val isReadable: Boolean = true
)

internal data class MessageOriginAuth(
    val proofJson: String,
    val signatureBase64: String
)

internal data class MessageOriginVerification(
    val role: String,
    val verifiedAtMillis: Long
)

internal enum class MeshPacketType(val wireValue: String) {
    CHAT("chat"),
    RECEIPT("receipt"),
    AUTH_CHALLENGE("auth_challenge"),
    AUTH_PROOF("auth_proof")
}

internal enum class ReceiptType(val wireValue: String) {
    DELIVERED("delivered"),
    READ("read")
}

internal enum class DispatchOutcome {
    SENT,
    QUEUED,
    FAILED
}

internal data class SendRouteSnapshot(
    val connected: Int,
    val ready: Int,
    val discovered: Int
)

internal data class PublishStateSnapshot(
    val connectedCount: Int,
    val discoveredCount: Int,
    val sendReadyCount: Int,
    val connectedPeers: List<GattMeshConnectedPeer>
)
