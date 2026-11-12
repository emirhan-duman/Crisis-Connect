package com.auralis.crisisconnect.watch

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import java.util.Locale

data class QuickReplyItem(
    val text: String,
    val isDefault: Boolean
)

class WatchQuickRepliesStore(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("watch_quick_replies_store", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_CUSTOM_REPLIES = "custom_replies"
        const val MAX_REPLY_LENGTH = 50
    }

    fun getDefaultReplies(): List<String> {
        val isTr = Locale.getDefault().language.equals("tr", ignoreCase = true)
        return if (isTr) {
            listOf("İyiyim", "Yaralıyım", "Yardım lazım")
        } else {
            listOf("I am safe", "I am injured", "I need help")
        }
    }

    fun getCustomReplies(): List<String> {
        val raw = prefs.getString(KEY_CUSTOM_REPLIES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun getAllReplies(): List<QuickReplyItem> {
        val defaults = getDefaultReplies().map { QuickReplyItem(it, isDefault = true) }
        val customs = getCustomReplies().map { QuickReplyItem(it, isDefault = false) }
        return defaults + customs
    }

    fun getReplyStrings(): List<String> {
        return getAllReplies().map { it.text }
    }

    fun addCustomReply(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_REPLY_LENGTH) return false
        val current = getCustomReplies().toMutableList()
        val all = getReplyStrings()
        if (all.any { it.equals(trimmed, ignoreCase = true) }) return false
        current.add(trimmed)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        return prefs.edit().putString(KEY_CUSTOM_REPLIES, arr.toString()).commit()
    }

    fun removeReply(text: String): Boolean {
        val current = getCustomReplies().toMutableList()
        val removed = current.removeAll { it.equals(text.trim(), ignoreCase = true) }
        if (!removed) return false
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        return prefs.edit().putString(KEY_CUSTOM_REPLIES, arr.toString()).commit()
    }

    fun resetToDefaults(): Boolean {
        return prefs.edit().remove(KEY_CUSTOM_REPLIES).commit()
    }
}
