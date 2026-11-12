package com.auralis.crisisconnect.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Stores and observes pinned message UUIDs per conversation sessionCode.
 * Persisted in SharedPreferences so pins survive process restarts.
 */
object ChatPinStore {
    private const val PREFS_NAME = "chat_pinned_messages"
    private const val KEY_PIN_PREFIX = "pinned_msg_"
    private val memoryFlows = mutableMapOf<String, MutableStateFlow<String?>>()
    private val lock = Any()

    fun getPinnedMessageUuid(context: Context, sessionCode: String): String? {
        val clean = sessionCode.trim()
        if (clean.isEmpty()) return null
        return synchronized(lock) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_PIN_PREFIX + clean, null)
        }
    }

    fun observePinnedMessageUuid(context: Context, sessionCode: String): StateFlow<String?> {
        val clean = sessionCode.trim()
        synchronized(lock) {
            val existing = memoryFlows[clean]
            if (existing != null) {
                return existing.asStateFlow()
            }
            val initial = getPinnedMessageUuid(context, clean)
            val flow = MutableStateFlow(initial)
            memoryFlows[clean] = flow
            return flow.asStateFlow()
        }
    }

    fun pinMessage(context: Context, sessionCode: String, messageUuid: String) {
        val clean = sessionCode.trim()
        if (clean.isEmpty() || messageUuid.isBlank()) return
        synchronized(lock) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PIN_PREFIX + clean, messageUuid)
                .apply()
            val flow = memoryFlows.getOrPut(clean) { MutableStateFlow(messageUuid) }
            flow.value = messageUuid
        }
    }

    fun unpinMessage(context: Context, sessionCode: String) {
        val clean = sessionCode.trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_PIN_PREFIX + clean)
                .apply()
            val flow = memoryFlows.getOrPut(clean) { MutableStateFlow(null) }
            flow.value = null
        }
    }
}
