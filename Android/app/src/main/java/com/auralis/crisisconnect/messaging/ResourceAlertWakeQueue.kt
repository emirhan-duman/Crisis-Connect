package com.auralis.crisisconnect.messaging

import android.content.Context
import com.auralis.crisisconnect.security.KeystoreBackedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow

internal data class PendingResourceAlertWake(
    val recipientUid: String,
    val panelId: String,
    val attemptId: String,
    val receiptNonce: String,
    val enqueuedAt: Long,
    val attemptCount: Int,
    val nextAttemptAt: Long,
) {
    val key: String get() = "$attemptId.$receiptNonce"
    val payload: ResourceAlertWakePayload
        get() = ResourceAlertWakePayload(panelId, attemptId, receiptNonce)
}

internal interface ResourceAlertWakeQueuePersistence {
    fun load(): String?
    fun save(value: String?): Boolean
}

private class KeystoreResourceAlertWakePersistence(context: Context) : ResourceAlertWakeQueuePersistence {
    private val preferences = KeystoreBackedPreferences(
        context.applicationContext,
        PREF_NAME,
        KEY_ALIAS,
    )

    override fun load(): String? = preferences.getString(QUEUE_KEY, null)
    override fun save(value: String?): Boolean = preferences.putStringCommitted(QUEUE_KEY, value)

    private companion object {
        const val PREF_NAME = "resource_alert_wake_queue_v1"
        const val KEY_ALIAS = "resource_alert_wake_queue_key_v1"
        const val QUEUE_KEY = "pending_ack_receipts"
    }
}

internal class ResourceAlertWakeQueue(
    private val persistence: ResourceAlertWakeQueuePersistence,
) {
    @Synchronized
    fun enqueue(payload: ResourceAlertWakePayload, recipientUid: String, now: Long): String? {
        // Enqueue is the account transition boundary. A new account replaces old receipt
        // challenges so a later worker can never replay them with the wrong identity.
        val entries = normalized(now, recipientUid).toMutableList()
        val candidate = PendingResourceAlertWake(
            recipientUid = recipientUid,
            panelId = payload.panelId,
            attemptId = payload.attemptId,
            receiptNonce = payload.receiptNonce,
            enqueuedAt = now,
            attemptCount = 0,
            nextAttemptAt = now,
        )
        if (entries.none { it.key == candidate.key }) {
            if (entries.size >= MAXIMUM_ENTRIES) return null
            entries += candidate
        }
        return candidate.key.takeIf { persist(entries.sortedBy { it.enqueuedAt }) }
    }

    @Synchronized
    fun claim(recipientUid: String, now: Long, key: String? = null): PendingResourceAlertWake? {
        val entries = normalized(now, recipientUid).toMutableList()
        val index = entries.indices
            .filter {
                entries[it].recipientUid == recipientUid && entries[it].nextAttemptAt <= now &&
                    (key == null || entries[it].key == key)
            }
            .minByOrNull { entries[it].nextAttemptAt }
            ?: run {
                persist(entries)
                return null
            }
        val current = entries[index]
        val claimed = current.copy(
            attemptCount = current.attemptCount + 1,
            nextAttemptAt = now + backoffAfterAttempt(current.attemptCount + 1),
        )
        entries[index] = claimed
        return claimed.takeIf { persist(entries) }
    }

    @Synchronized
    fun complete(recipientUid: String, key: String, now: Long): Boolean {
        val remaining = normalized(now, recipientUid).filterNot { it.key == key }
        return persist(remaining)
    }

    @Synchronized
    fun hasPending(recipientUid: String, now: Long): Boolean {
        val entries = normalized(now, recipientUid)
        persist(entries)
        return entries.any { it.recipientUid == recipientUid }
    }

    @Synchronized
    fun nextAttemptAt(recipientUid: String, now: Long): Long? {
        val entries = normalized(now, recipientUid)
        persist(entries)
        return entries.filter { it.recipientUid == recipientUid }.minOfOrNull { it.nextAttemptAt }
    }

    private fun normalized(now: Long, recipientUid: String): List<PendingResourceAlertWake> {
        val raw = persistence.load() ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val entries = ArrayList<PendingResourceAlertWake>(min(array.length(), MAXIMUM_ENTRIES))
        for (index in 0 until min(array.length(), MAXIMUM_ENTRIES * 2)) {
            val value = array.optJSONObject(index) ?: continue
            val entry = decode(value) ?: continue
            if (entry.recipientUid == recipientUid && entry.enqueuedAt <= now + CLOCK_SKEW_MILLIS &&
                now - entry.enqueuedAt <= RETENTION_MILLIS && entry.attemptCount in 0..10_000
            ) entries += entry
        }
        return entries.distinctBy { it.key }.sortedBy { it.enqueuedAt }.takeLast(MAXIMUM_ENTRIES)
    }

    private fun persist(entries: List<PendingResourceAlertWake>): Boolean {
        if (entries.isEmpty()) return persistence.save(null)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("recipientUid", entry.recipientUid)
                    .put("panelId", entry.panelId)
                    .put("attemptId", entry.attemptId)
                    .put("receiptNonce", entry.receiptNonce)
                    .put("enqueuedAt", entry.enqueuedAt)
                    .put("attemptCount", entry.attemptCount)
                    .put("nextAttemptAt", entry.nextAttemptAt)
            )
        }
        return persistence.save(array.toString())
    }

    private fun decode(value: JSONObject): PendingResourceAlertWake? {
        val payload = parseResourceAlertWake(
            mapOf(
                "type" to "resource_alert_wake",
                "panelId" to value.optString("panelId"),
                "attemptId" to value.optString("attemptId"),
                "receiptNonce" to value.optString("receiptNonce"),
            )
        ) ?: return null
        val uid = value.optString("recipientUid").takeIf {
            it.length in 1..128 && it.none(Char::isISOControl)
        } ?: return null
        val enqueuedAt = value.optLong("enqueuedAt", -1L).takeIf { it >= 0 } ?: return null
        val attemptCount = value.optInt("attemptCount", -1).takeIf { it >= 0 } ?: return null
        val nextAttemptAt = value.optLong("nextAttemptAt", -1L).takeIf { it >= 0 } ?: return null
        return PendingResourceAlertWake(
            uid,
            payload.panelId,
            payload.attemptId,
            payload.receiptNonce,
            enqueuedAt,
            attemptCount,
            nextAttemptAt,
        )
    }

    companion object {
        const val MAXIMUM_ENTRIES = 32
        const val RETENTION_MILLIS = 72L * 60L * 60L * 1000L
        const val MAXIMUM_BACKOFF_MILLIS = 6L * 60L * 60L * 1000L
        private const val CLOCK_SKEW_MILLIS = 5L * 60L * 1000L
        @Volatile private var shared: ResourceAlertWakeQueue? = null

        fun shared(context: Context): ResourceAlertWakeQueue = shared ?: synchronized(this) {
            shared ?: ResourceAlertWakeQueue(
                KeystoreResourceAlertWakePersistence(context.applicationContext)
            ).also { shared = it }
        }

        fun backoffAfterAttempt(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 10)
            return min((30_000.0 * 2.0.pow(exponent)).toLong(), MAXIMUM_BACKOFF_MILLIS)
        }
    }
}
