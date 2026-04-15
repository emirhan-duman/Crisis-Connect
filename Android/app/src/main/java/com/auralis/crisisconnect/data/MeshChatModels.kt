package com.auralis.crisisconnect.data

data class MeshChatMessage(
    val id: String,
    val text: String,
    val senderLabel: String?,
    val sourceAddress: String? = null,
    val originVerifiedRole: String? = null,
    val originVerifiedAtMillis: Long? = null,
    val isLocal: Boolean,
    val timestampMillis: Long,
    val receivedTimestampMillis: Long? = null,
    val status: MeshMessageStatus = MeshMessageStatus.SENT,
    val sentTo: List<String> = emptyList(),
    val deliveredTo: List<String> = emptyList(),
    val readBy: List<String> = emptyList()
)

enum class MeshMessageStatus {
    QUEUED,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}
