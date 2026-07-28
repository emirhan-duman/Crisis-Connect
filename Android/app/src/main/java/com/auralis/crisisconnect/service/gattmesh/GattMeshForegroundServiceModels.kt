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
    val agency: String,
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
    val isReadable: Boolean = true,
    val blobId: String? = null,
    val blobIndex: Int = -1,
    val blobChunkCount: Int = 0,
    val blobCipherBytes: Int = 0,
    val blobDataBase64: String? = null,
    val blobMime: String? = null,
    val blobWidth: Int? = null,
    val blobHeight: Int? = null,
    val blobKind: String? = null,
    val blobDurationMillis: Long? = null
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
    AUTH_PROOF("auth_proof"),
    IMAGE_INIT("image_init"),
    IMAGE_CHUNK("image_chunk"),
    IMAGE_DONE("image_done"),

    // Telsiz (push-to-talk), authority mesh only. All single-hop, fire-and-forget.
    // Control packets (join/leave/floor) carry their payload in [MeshPacket.message]; PTT_AUDIO
    // carries the Opus frame in [MeshPacket.blobDataBase64] with the sequence in [MeshPacket.blobIndex].
    PTT_JOIN("ptt_join"),
    PTT_LEAVE("ptt_leave"),
    PTT_FLOOR_CLAIM("ptt_floor_claim"),
    PTT_FLOOR_RELEASE("ptt_floor_release"),
    PTT_AUDIO("ptt_audio")
}

/**
 * Reassembly state for one inbound image blob. Blob packets are single-hop only (never relayed),
 * so the whole transfer comes from one directly connected peer.
 */
internal class InboundImageBlobState(
    val sourceAddress: String,
    val senderLabel: String,
    val ivBase64: String,
    val keyId: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val chunkCount: Int,
    val cipherBytes: Int,
    val kind: String? = null,
    val durationMillis: Long? = null,
) {
    val chunks: Array<ByteArray?> = arrayOfNulls(chunkCount)
    var receivedCount: Int = 0
    var receivedBytes: Int = 0
    var lastActivityAtMillis: Long = System.currentTimeMillis()
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
