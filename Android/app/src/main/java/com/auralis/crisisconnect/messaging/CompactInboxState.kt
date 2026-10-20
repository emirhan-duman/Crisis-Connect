package com.auralis.crisisconnect.messaging

import android.content.Context

/** Recipient acknowledgements survive process death and are isolated by Firebase account. */
internal object CompactInboxState {
    private var context: Context? = null
    @Synchronized fun start(context: Context) {
        this.context = context.applicationContext
        MessagingInstallation.start(context)
    }
    @Synchronized fun enqueue(uid: String, id: String): Boolean {
        val prefs = context?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE) ?: return false
        val ids = prefs.getStringSet(uid, emptySet()).orEmpty().toMutableSet()
        ids.add(id)
        // These are already stored locally; any old unacknowledged server copy expires by TTL.
        return prefs.edit().putStringSet(uid, ids.takeLastBounded(2_048)).commit()
    }
    @Synchronized fun pending(uid: String): List<String> = context
        ?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE)?.getStringSet(uid, emptySet())?.take(100).orEmpty()
    @Synchronized fun nextRevision(uid: String): Long {
        val prefs = context?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE)
            ?: error("Compact inbox is not initialized.")
        val revision = maxOf(System.currentTimeMillis(), prefs.getLong("revision.$uid", 0) + 1)
        check(prefs.edit().putLong("revision.$uid", revision).commit()) { "Cannot persist traffic revision." }
        return revision
    }
    @Synchronized fun setProfile(uid: String, saving: Boolean) {
        context?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE)?.edit()?.putBoolean("profile.$uid", saving)?.commit()
    }
    @Synchronized fun hasProfile(uid: String): Boolean = context
        ?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE)?.getBoolean("profile.$uid", false) == true
    @Synchronized fun confirm(uid: String, acknowledged: List<String>) {
        val prefs = context?.getSharedPreferences("compact_inbox", Context.MODE_PRIVATE) ?: return
        val ids = prefs.getStringSet(uid, emptySet()).orEmpty() - acknowledged.toSet()
        prefs.edit().putStringSet(uid, ids).commit()
    }
    private fun Set<String>.takeLastBounded(count: Int): Set<String> = toList().takeLast(count).toSet()
}
