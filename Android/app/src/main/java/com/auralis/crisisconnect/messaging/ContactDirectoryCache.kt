package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Last known result of a contact-directory scan, kept on disk so "Add from contacts" can render
 * instantly (WhatsApp-style) instead of making the user watch a spinner while the address book is
 * read and the directory is queried. The fresh scan then runs silently behind the cached list.
 *
 * Scoped to the uid that produced it: after a different account signs in the cache is ignored (and
 * dropped), so one user never sees another's matches.
 */
object ContactDirectoryCache {

    private const val TAG = "ContactDirectoryCache"
    private const val PREFS = "contact_directory_cache"
    private const val KEY_PAYLOAD = "payload"

    data class Entry(
        val displayName: String,
        val phone: String,
        val uid: String,
        val publicKey: String,
        val photoUrl: String,
        val isChild: Boolean
    )

    data class Cached(val entries: List<Entry>, val scannedAtMs: Long)

    /** Returns the cached scan for [uid], or null when there is none (or it belongs elsewhere). */
    fun read(context: Context, uid: String): Cached? {
        val raw = prefs(context).getString(KEY_PAYLOAD, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            if (root.optString("uid") != uid) return null
            val array = root.optJSONArray("matches") ?: JSONArray()
            val entries = ArrayList<Entry>(array.length())
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val entryUid = o.optString("uid")
                if (entryUid.isBlank()) continue
                entries.add(
                    Entry(
                        displayName = o.optString("name"),
                        phone = o.optString("phone"),
                        uid = entryUid,
                        publicKey = o.optString("key"),
                        photoUrl = o.optString("photo"),
                        isChild = o.optBoolean("child", false)
                    )
                )
            }
            Cached(entries = entries, scannedAtMs = root.optLong("scannedAtMs", 0L))
        }.getOrElse {
            Log.w(TAG, "Unreadable cache, dropping", it)
            clear(context)
            null
        }
    }

    /** Replaces the cache with [entries] as of [scannedAtMs]. Call off the main thread. */
    fun write(context: Context, uid: String, entries: List<Entry>, scannedAtMs: Long) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("name", entry.displayName)
                    .put("phone", entry.phone)
                    .put("uid", entry.uid)
                    .put("key", entry.publicKey)
                    .put("photo", entry.photoUrl)
                    .put("child", entry.isChild)
            )
        }
        val root = JSONObject()
            .put("uid", uid)
            .put("scannedAtMs", scannedAtMs)
            .put("matches", array)
        prefs(context).edit().putString(KEY_PAYLOAD, root.toString()).apply()
    }

    /** Drops the cache (sign-out, or a payload we can no longer parse). */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_PAYLOAD).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
