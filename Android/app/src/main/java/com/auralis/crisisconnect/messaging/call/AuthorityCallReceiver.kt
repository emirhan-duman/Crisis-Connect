package com.auralis.crisisconnect.messaging.call

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.security.SecurityRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * App-wide receiver for authority channel calls, so an incoming call rings from ANY screen (not only
 * while the relevant conversation is open). For an agency member it subscribes to the agency channel's
 * callSignals plus every cross-panel (hierarchy) channel's callSignals and feeds them to the shared
 * [InternetCallManager] — the same singleton whose state the global overlay renders. This is the SINGLE
 * receive source (conversation screens no longer listen) so an incoming offer is never processed twice.
 *
 * Started once from [com.auralis.crisisconnect.messaging.MessagingBootstrap] after sign-in; a non-agency
 * user (no agencySlug) starts no listeners and stays retry-able.
 */
object AuthorityCallReceiver {
    private const val TAG = "AuthorityCallReceiver"

    private val lock = Any()
    private var started = false
    private val registrations = mutableListOf<ListenerRegistration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        synchronized(lock) {
            if (started) return
            started = true
        }
        val appContext = context.applicationContext
        scope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            val slug = uid?.let { resolveAgencySlug(appContext, it) }
            if (uid == null || slug.isNullOrBlank()) {
                // Not an agency member (e.g. a citizen) — nothing to receive; allow a later retry.
                synchronized(lock) { started = false }
                return@launch
            }
            val genericName = appContext.getString(R.string.internet_call_unknown_peer)
            if (com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) {
                com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.init(appContext)
            }
            val regs = mutableListOf<ListenerRegistration>()
            runCatching {
                AuthorityCallSignaling(
                    channelId = slug,
                    myUid = uid,
                    kind = AuthorityCallSignaling.ChannelKind.AGENCY,
                    peerNameResolver = { genericName }
                ).apply { attachSfuRouting(slug, AuthorityCallSignaling.ChannelKind.AGENCY, uid) }.listen()
            }.getOrNull()?.let { regs.add(it) }

            val channels = runCatching { HierarchyMessagingClient().fetchChannels() }.getOrDefault(emptyList())
            for (channel in channels) {
                runCatching {
                    AuthorityCallSignaling(
                        channelId = channel.channelId,
                        myUid = uid,
                        kind = AuthorityCallSignaling.ChannelKind.HIERARCHY,
                        peerNameResolver = { u -> channel.peers.firstOrNull { it.uid == u }?.name ?: genericName }
                    ).apply { attachSfuRouting(channel.channelId, AuthorityCallSignaling.ChannelKind.HIERARCHY, uid) }.listen()
                }.getOrNull()?.let { regs.add(it) }
            }
            synchronized(lock) { registrations.addAll(regs) }
            Log.d(TAG, "Listening for authority calls on ${regs.size} channel(s)")
        }
    }

    fun stop() {
        synchronized(lock) {
            registrations.forEach { runCatching { it.remove() } }
            registrations.clear()
            started = false
        }
    }

    private suspend fun resolveAgencySlug(context: Context, uid: String): String? {
        val fromDoc = runCatching {
            FirebaseFirestore.getInstance().document("users/$uid").get().await().getString("agencySlug")
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        return fromDoc ?: runCatching {
            SecurityRepository(context).getUsableStoredCertificateAgency()
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }
}

/** When SFU is enabled, route this channel's SFU (roomId) signals to the SFU manager instead of P2P. */
private fun AuthorityCallSignaling.attachSfuRouting(
    channelId: String,
    kind: AuthorityCallSignaling.ChannelKind,
    uid: String,
) {
    if (!com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) return
    onSfuSignal = { from, signal ->
        com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager.onSfuSignal(channelId, kind, uid, from, signal)
    }
}
