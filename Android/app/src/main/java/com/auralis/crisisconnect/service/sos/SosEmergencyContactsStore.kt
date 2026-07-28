package com.auralis.crisisconnect.service.sos

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getFirstMessageTimeForSession
import com.auralis.crisisconnect.security.ChildProfileManager
import com.auralis.crisisconnect.settingsDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The user's hand-picked SOS emergency contacts (conversation session codes), stored in the app
 * settings DataStore. When this set is empty the notifier falls back to its automatic blended
 * ranking (most-messaged + oldest conversations).
 */
object SosEmergencyContactsStore {

    const val MAX_CONTACTS = 5
    private val KEY = stringSetPreferencesKey("sos_emergency_contact_sessions")
    private val PROMPTED_KEY = stringSetPreferencesKey("sos_ec_prompted_sessions")

    /** How long after its first message a conversation still counts as freshly established. */
    private const val NEW_CONVERSATION_WINDOW_MS = 72L * 60L * 60L * 1000L

    fun observe(context: Context): Flow<Set<String>> =
        context.settingsDataStore.data.map { it[KEY] ?: emptySet() }

    suspend fun load(context: Context): Set<String> =
        context.settingsDataStore.data.first()[KEY] ?: emptySet()

    suspend fun save(context: Context, sessionCodes: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY] = sessionCodes.take(MAX_CONTACTS).toSet()
        }
    }

    /**
     * Whether the chat screen should offer the one-time "save as emergency contact?" prompt for
     * this conversation: internet-capable contact, never asked before, not already chosen, list
     * not full — and the conversation must be freshly established (no messages yet, or the first
     * message is younger than [NEW_CONVERSATION_WINDOW_MS]), so existing threads are never
     * spammed retroactively. Old threads get silently marked so they are never re-evaluated.
     */
    suspend fun shouldPromptFor(context: Context, sessionCode: String): Boolean {
        // On a child device the emergency contacts ARE the confirmed parents — never ask.
        if (ChildProfileManager.isEnabled(context)) return false
        val contact = getContact(context, sessionCode) ?: return false
        if (!contact.supportsInternet) return false
        // Never offer a child-profile contact as an emergency contact.
        if (contact.peerIsChild) return false
        val prefs = context.settingsDataStore.data.first()
        val prompted = prefs[PROMPTED_KEY] ?: emptySet()
        if (sessionCode in prompted) return false
        val chosen = prefs[KEY] ?: emptySet()
        if (sessionCode in chosen) return false
        if (chosen.size >= MAX_CONTACTS) return false
        val firstMessageAt = runCatching {
            getFirstMessageTimeForSession(context, sessionCode)
        }.getOrNull()
        val isFreshConversation = firstMessageAt == null ||
            System.currentTimeMillis() - firstMessageAt <= NEW_CONVERSATION_WINDOW_MS
        if (!isFreshConversation) {
            markPrompted(context, sessionCode)
            return false
        }
        return true
    }

    suspend fun markPrompted(context: Context, sessionCode: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[PROMPTED_KEY] = (prefs[PROMPTED_KEY] ?: emptySet()) + sessionCode
        }
    }

    /** Adds the contact to the emergency list (respecting the cap) and marks the prompt answered. */
    suspend fun addEmergencyContact(context: Context, sessionCode: String) {
        context.settingsDataStore.edit { prefs ->
            val chosen = prefs[KEY] ?: emptySet()
            if (chosen.size < MAX_CONTACTS) {
                prefs[KEY] = chosen + sessionCode
            }
            prefs[PROMPTED_KEY] = (prefs[PROMPTED_KEY] ?: emptySet()) + sessionCode
        }
    }
}
