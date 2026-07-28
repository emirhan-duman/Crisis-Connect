package com.auralis.crisisconnect.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads persisted chat history for a mesh conversation and merges it into the in-memory
 * [MeshChatStoreCore], so the chat shows past messages after an app/process restart.
 *
 * Profile-agnostic (keyed by [MeshChatStoreCore.sessionCode]); used by the authority mesh chat to
 * match the public gatt mesh chat's restore behaviour. Mirrors the mapping/merge logic in
 * `GattMeshViewModel.restorePersistedMessagesIfNeeded`.
 */
object MeshChatHistoryRestorer {
    private const val RESTORE_LIMIT = 200

    suspend fun restore(context: Context, store: MeshChatStoreCore) {
        val appContext = context.applicationContext
        val persisted = withContext(Dispatchers.IO) {
            loadRecentMessages(appContext, store.sessionCode, RESTORE_LIMIT)
        }.map { it.toMeshChatMessage() }

        val runtime = store.currentMessages()
        val mergedById = LinkedHashMap<String, MeshChatMessage>(persisted.size + runtime.size)
        persisted.forEach { mergedById[it.id] = it }
        runtime.forEach { runtimeMessage ->
            val existing = mergedById[runtimeMessage.id]
            mergedById[runtimeMessage.id] =
                if (existing == null) runtimeMessage else mergeRestored(existing, runtimeMessage)
        }
        val merged = mergedById.values.sortedWith(compareBy({ it.timestampMillis }, { it.id }))
        store.replaceMessages(merged)
    }

    private fun ChatMessage.toMeshChatMessage(): MeshChatMessage {
        val display = resolveDisplayTimestamp(timestampMillis, originalTimestampMillis)
        return MeshChatMessage(
            id = messageUuid,
            text = text,
            senderLabel = senderDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: senderAddress?.trim()?.takeIf { it.isNotEmpty() },
            sourceAddress = senderAddress?.trim()?.takeIf { it.isNotEmpty() },
            originVerifiedRole = originVerifiedRole?.trim()?.takeIf { it.isNotEmpty() },
            originVerifiedAtMillis = originVerifiedAtMillis?.takeIf { it > 0L },
            isLocal = isLocal,
            timestampMillis = display,
            receivedTimestampMillis = if (isLocal) {
                null
            } else {
                originalTimestampMillis?.takeIf { it in 1 until timestampMillis }?.let { timestampMillis }
            },
            status = mapStatus(deliveryStatus, isRead, isLocal),
            sentTo = sentTo,
            deliveredTo = deliveredTo,
            readBy = readBy
        )
    }

    private fun resolveDisplayTimestamp(timestampMillis: Long, originalTimestampMillis: Long?): Long {
        val original = originalTimestampMillis?.takeIf { it > 0L && it < timestampMillis }
        return original ?: timestampMillis
    }

    private fun mapStatus(
        deliveryStatus: MessageDeliveryStatus?,
        isRead: Boolean,
        isLocal: Boolean
    ): MeshMessageStatus {
        if (!isLocal) {
            return if (isRead) MeshMessageStatus.READ else MeshMessageStatus.DELIVERED
        }
        if (isRead || deliveryStatus == MessageDeliveryStatus.READ) {
            return MeshMessageStatus.READ
        }
        return when (deliveryStatus) {
            MessageDeliveryStatus.QUEUED -> MeshMessageStatus.QUEUED
            MessageDeliveryStatus.SENDING -> MeshMessageStatus.SENDING
            MessageDeliveryStatus.SENT -> MeshMessageStatus.SENT
            MessageDeliveryStatus.DELIVERED -> MeshMessageStatus.DELIVERED
            MessageDeliveryStatus.READ -> MeshMessageStatus.READ
            MessageDeliveryStatus.FAILED -> MeshMessageStatus.FAILED
            null -> MeshMessageStatus.SENT
        }
    }

    private fun mergeRestored(persisted: MeshChatMessage, runtime: MeshChatMessage): MeshChatMessage {
        if (persisted.isLocal != runtime.isLocal) return runtime
        return runtime.copy(
            text = runtime.text.ifBlank { persisted.text },
            senderLabel = runtime.senderLabel?.takeIf { it.isNotBlank() } ?: persisted.senderLabel,
            sourceAddress = runtime.sourceAddress?.takeIf { it.isNotBlank() } ?: persisted.sourceAddress,
            originVerifiedRole = runtime.originVerifiedRole?.takeIf { it.isNotBlank() }
                ?: persisted.originVerifiedRole,
            originVerifiedAtMillis = runtime.originVerifiedAtMillis?.takeIf { it > 0L }
                ?: persisted.originVerifiedAtMillis,
            timestampMillis = runtime.timestampMillis.takeIf { it > 0L } ?: persisted.timestampMillis,
            receivedTimestampMillis = runtime.receivedTimestampMillis ?: persisted.receivedTimestampMillis,
            status = preferredStatus(persisted.status, runtime.status),
            sentTo = (persisted.sentTo + runtime.sentTo).distinct(),
            deliveredTo = (persisted.deliveredTo + runtime.deliveredTo).distinct(),
            readBy = (persisted.readBy + runtime.readBy).distinct()
        )
    }

    private fun preferredStatus(persisted: MeshMessageStatus, runtime: MeshMessageStatus): MeshMessageStatus =
        if (statusRank(runtime) >= statusRank(persisted)) runtime else persisted

    private fun statusRank(status: MeshMessageStatus): Int = when (status) {
        MeshMessageStatus.FAILED -> 0
        MeshMessageStatus.QUEUED -> 1
        MeshMessageStatus.SENDING -> 2
        MeshMessageStatus.SENT -> 3
        MeshMessageStatus.DELIVERED -> 4
        MeshMessageStatus.READ -> 5
    }
}
