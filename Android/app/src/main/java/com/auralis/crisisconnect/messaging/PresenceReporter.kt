package com.auralis.crisisconnect.messaging

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.auralis.crisisconnect.settingsDataStore
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Publishes this user's "last seen" presence: while the app is foreground, `presence/{uid}` gets a
 * fresh `lastActiveAt` heartbeat every minute, plus one final stamp when the app leaves the
 * foreground — that last stamp IS the peer-visible "son görülme" moment. Readers treat a stamp
 * fresher than ~90s as "online". Best-effort everywhere: offline or missing rules just means peers
 * see stale/no presence, never an error. Driven by [com.auralis.crisisconnect.CrisisConnectApp]'s
 * activity-lifecycle counting (no lifecycle-process dependency).
 *
 * Privacy: gated by [SHARE_LAST_SEEN_KEY] (Advanced Settings, default ON) — and since the toggle is
 * ACCOUNT-level, mirrored to `presenceSettings/{uid}` which (a) the other platforms sync from and
 * (b) the Firestore rules enforce on every presence write, so a stale client can't leak presence.
 * The local DataStore key is a per-device cache of that account doc; on startup the cloud value
 * wins ([syncAccountPreferenceOnce]). Turning sharing off stops the heartbeat AND deletes the
 * presence doc, so peers see nothing at all — not even a stale stamp.
 */
object PresenceReporter {
    /** Advanced-settings preference: publish my presence at all. Shared with the settings UI. */
    val SHARE_LAST_SEEN_KEY = booleanPreferencesKey("advanced_share_last_seen_enabled")

    private const val HEARTBEAT_MS = 60_000L
    private const val SETTINGS_COLLECTION = "presenceSettings"
    private const val SETTINGS_FIELD = "shareLastSeen"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null
    private var appContext: Context? = null

    /** One successful account-doc read per process; an explicit user toggle also settles it. */
    @Volatile
    private var accountPrefSynced = false

    fun onAppForeground(context: Context) {
        appContext = context.applicationContext
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                syncAccountPreferenceOnce()
                publish()
                delay(HEARTBEAT_MS)
            }
        }
    }

    fun onAppBackground(context: Context) {
        appContext = context.applicationContext
        heartbeatJob?.cancel()
        heartbeatJob = null
        scope.launch { publish() }
    }

    /** Settings toggle changed: ON → stamp immediately; OFF → wipe the doc so nothing is visible.
     *  Either way the choice is published account-wide so web (and future iOS) follow it too. */
    fun onSharingPreferenceChanged(context: Context, enabled: Boolean) {
        appContext = context.applicationContext
        // The user's explicit choice beats any not-yet-run cloud sync this process.
        accountPrefSynced = true
        scope.launch {
            val uid = currentUid()
            if (uid != null) publishAccountPreference(uid, enabled)
            if (enabled) {
                publish()
            } else {
                deletePresence()
            }
        }
    }

    /**
     * Cloud-wins startup sync of the account-level toggle. Runs before the first heartbeat and
     * retries on later beats until one read succeeds (offline start must not lock in a stale
     * local value). A device that was switched off before the account doc existed pushes its
     * OFF up so the other platforms adopt it.
     */
    private suspend fun syncAccountPreferenceOnce() {
        if (accountPrefSynced) return
        val context = appContext ?: return
        val uid = currentUid() ?: return
        val snapshot = runCatching {
            FirebaseFirestore.getInstance()
                .collection(SETTINGS_COLLECTION)
                .document(uid)
                .get()
                .await()
        }.getOrNull() ?: return
        accountPrefSynced = true
        val cloud = runCatching { snapshot.getBoolean(SETTINGS_FIELD) }.getOrNull()
        val local = isSharingEnabled()
        when {
            cloud != null && cloud != local -> {
                runCatching {
                    context.settingsDataStore.edit { prefs -> prefs[SHARE_LAST_SEEN_KEY] = cloud }
                }
                if (!cloud) deletePresence()
            }
            cloud == null && !local -> publishAccountPreference(uid, false)
        }
    }

    private suspend fun publishAccountPreference(uid: String, enabled: Boolean) {
        runCatching {
            FirebaseFirestore.getInstance()
                .collection(SETTINGS_COLLECTION)
                .document(uid)
                .set(
                    mapOf(
                        SETTINGS_FIELD to enabled,
                        "updatedAt" to System.currentTimeMillis(),
                    )
                )
                .await()
        }
    }

    private fun currentUid(): String? =
        runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()

    private suspend fun isSharingEnabled(): Boolean {
        val context = appContext ?: return true
        return runCatching {
            context.settingsDataStore.data.first()[SHARE_LAST_SEEN_KEY] ?: true
        }.getOrDefault(true)
    }

    private suspend fun publish() {
        if (!isSharingEnabled()) return
        val uid = currentUid() ?: return
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("presence")
                .document(uid)
                .set(mapOf("lastActiveAt" to System.currentTimeMillis()))
                .await()
        }
    }

    private suspend fun deletePresence() {
        val uid = currentUid() ?: return
        runCatching {
            FirebaseFirestore.getInstance()
                .collection("presence")
                .document(uid)
                .delete()
                .await()
        }
    }
}
