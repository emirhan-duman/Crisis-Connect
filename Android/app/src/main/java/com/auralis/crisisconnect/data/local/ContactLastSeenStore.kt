package com.auralis.crisisconnect.data.local

import android.content.Context

/**
 * Local, per-conversation "last seen the peer alive" record — the Bluetooth-side half of the
 * last-seen feature. Fed by observations that need no server at all: a live BT link to the peer or
 * any incoming message. The chat header shows max(this, the peer's internet presence doc), so a
 * peer met offline in the field still gets a truthful "son görülme".
 */
object ContactLastSeenStore {
    private const val PREFS = "contact_last_seen"

    fun record(context: Context, sessionCode: String, atMillis: Long = System.currentTimeMillis()) {
        val code = sessionCode.trim()
        if (code.isEmpty() || atMillis <= 0L) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (atMillis > prefs.getLong(code, 0L)) {
            prefs.edit().putLong(code, atMillis).apply()
        }
    }

    fun get(context: Context, sessionCode: String): Long? {
        val code = sessionCode.trim()
        if (code.isEmpty()) return null
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(code, 0L)
            .takeIf { it > 0L }
    }
}
