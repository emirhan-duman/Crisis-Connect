package com.auralis.crisisconnect.muster

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** One bounded store-and-forward pass. A receipt, not an HTTP success alone, acknowledges an answer. */
internal suspend fun drainMusterQueue(
    queue: MusterQueue,
    uid: String,
    currentAccount: () -> String?,
    transport: suspend (MusterEvent) -> Pair<Int, JSONObject?>,
    now: () -> Long = System::currentTimeMillis,
    changed: () -> Unit = {},
): Boolean {
    repeat(16) {
        if (currentAccount() != uid) return true
        val event = queue.claim(uid, now()) ?: return queue.entries(uid).none { it.state == "pending" }
        try {
            val (code, data) = transport(event)
            val accepted = acceptsMusterReceipt(code, data, event)
            val permanent = code in 400..499 && code !in listOf(401, 408, 425, 429)
            queue.finish(event, if (accepted) "sent" else if (permanent) "failed" else "pending", now(), if (accepted) "" else "http-$code", if (accepted) data?.optString("eventId") else null)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            queue.finish(event, "pending", now(), "connection")
        }
        changed()
    }
    return queue.entries(uid).none { it.state == "pending" }
}

/** Keep the queue's bounded retry time; do not compound it with WorkManager's growing backoff. */
internal fun nextMusterSendDelay(queue: MusterQueue, uid: String, now: Long): Long? =
    queue.entries(uid).filter { it.state == "pending" }.minOfOrNull { it.nextAt }
        ?.let { (it - now).coerceIn(0L, 900000L) }

/** Ready answers run independently of delayed retries. Repeated scheduling for one retry time coalesces. */
internal fun musterSendWorkName(uid: String, retryAt: Long): String =
    if (retryAt == 0L) "muster-send-ready-$uid" else "muster-send-retry-$uid-$retryAt"
