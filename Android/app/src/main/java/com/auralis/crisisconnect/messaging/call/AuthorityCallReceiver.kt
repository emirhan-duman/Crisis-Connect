package com.auralis.crisisconnect.messaging.call

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.messaging.HierarchyMessagingClient
import com.auralis.crisisconnect.messaging.AuthorityMlsCallGate
import com.auralis.crisisconnect.messaging.AuthorityMlsScopeType
import com.auralis.crisisconnect.security.SecurityRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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
    private var generation = 0L
    private val registrations = mutableListOf<ListenerRegistration>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        val startGeneration = synchronized(lock) {
            if (started) return
            started = true
            generation += 1L
            generation
        }
        val appContext = context.applicationContext
        scope.launch {
            val uid = FirebaseAuth.getInstance().currentUser?.uid
            val slug = uid?.let { resolveAgencySlug(appContext, it) }
            if (uid == null || slug.isNullOrBlank()) {
                // Not an agency member (e.g. a citizen) — nothing to receive; allow a later retry.
                synchronized(lock) {
                    if (generation == startGeneration) started = false
                }
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
                ).apply { attachSfuRouting(appContext, slug, AuthorityCallSignaling.ChannelKind.AGENCY, uid) }.listen()
            }.getOrNull()?.let { regs.add(it) }

            val channels = runCatching { HierarchyMessagingClient().fetchChannels() }.getOrDefault(emptyList())
            for (channel in channels) {
                runCatching {
                    AuthorityCallSignaling(
                        channelId = channel.channelId,
                        myUid = uid,
                        kind = AuthorityCallSignaling.ChannelKind.HIERARCHY,
                        peerNameResolver = { u -> channel.peers.firstOrNull { it.uid == u }?.name ?: genericName }
                    ).apply {
                        attachSfuRouting(appContext, channel.channelId, AuthorityCallSignaling.ChannelKind.HIERARCHY, uid)
                    }.listen()
                }.getOrNull()?.let { regs.add(it) }
            }
            val retained = synchronized(lock) {
                if (started && generation == startGeneration) {
                    registrations.addAll(regs)
                    true
                } else {
                    false
                }
            }
            if (retained) {
                Log.d(TAG, "Listening for authority calls on ${regs.size} channel(s)")
            } else {
                regs.forEach { runCatching { it.remove() } }
            }
        }
    }

    fun stop() {
        val stale = synchronized(lock) {
            val result = registrations.toList()
            registrations.clear()
            started = false
            generation += 1L
            result
        }
        stale.forEach { runCatching { it.remove() } }
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
    context: Context,
    channelId: String,
    kind: AuthorityCallSignaling.ChannelKind,
    uid: String,
) {
    if (!com.auralis.crisisconnect.messaging.call.sfu.SfuCallConfig.ENABLED) return
    onSfuSignal = { from, signal ->
        if (signal.optString("type") != "offer") {
            com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager
                .onSfuSignal(channelId, kind, uid, from, signal)
        } else {
            authorityCallGateScope.launch {
                val verified = AuthorityMlsCallGate.isVerified(
                    context = context,
                    selfUid = uid,
                    peerUid = from,
                    scopeType = if (kind == AuthorityCallSignaling.ChannelKind.HIERARCHY) {
                        AuthorityMlsScopeType.HIERARCHY
                    } else {
                        AuthorityMlsScopeType.AGENCY
                    },
                    channelId = channelId,
                )
                if (!verified) {
                    sendSfuSignal(
                        from,
                        org.json.JSONObject()
                            .put("type", "reject")
                            .put("callId", signal.optString("callId")),
                    )
                    return@launch
                }
                withContext(Dispatchers.Main.immediate) {
                    com.auralis.crisisconnect.messaging.call.sfu.SfuAuthorityCallManager
                        .onSfuSignal(channelId, kind, uid, from, signal)
                }
            }
        }
    }
}

private val authorityCallGateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
