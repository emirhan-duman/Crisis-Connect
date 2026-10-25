package com.auralis.crisisconnect.watch

import android.content.Context
import com.auralis.crisisconnect.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable watch journal feeds the ordinary encrypted chat DB; this never sends a message. */
object WatchReplyChat {
    const val ROUTE = "watch_reply"
    private val lock = Mutex()
    suspend fun reconcile(context: Context) = lock.withLock { withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).messageDao()
        for (reply in WatchRecoveryStore.get(context).state.value.replies) {
            if (getContact(context, reply.peer) == null) continue
            val previous = dao.getMessageByUuid(reply.id)
            if (previous != null && (!previous.isLocal || previous.sessionCode != reply.peer)) continue
            if (previous?.deliveryStatus in listOf(MessageDeliveryStatus.DELIVERED, MessageDeliveryStatus.READ)) continue
            val status = if (reply.result == "accepted") MessageDeliveryStatus.SENT else MessageDeliveryStatus.FAILED
            dao.upsertLocalTextMessageAtomic(reply.peer, reply.id, reply.text,
                previous?.timestampMillis ?: reply.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(), null,
                status, 0, null, null, if (reply.result == "accepted") null else "watch_" + reply.result, ROUTE)
        }
    } }
    suspend fun send(context: Context, id: String, peer: String, text: String, kind: String = "", handoff: suspend () -> Boolean): String {
        val store = WatchRecoveryStore.get(context)
        store.state.value.replies.firstOrNull { it.id == id }?.let { reconcile(context); return if (it.peer == peer) it.result else "stateChanged" }
        store.reserveReply(id, peer, text, kind)
        reconcile(context) // reserved/uncertain bubble exists before touching transport
        val result = if (handoff()) "accepted" else "noPeer"
        store.completeReply(id, result)
        reconcile(context)
        return result
    }
}
