package com.auralis.crisisconnect.messaging

import android.content.Context

/** Requires the exact already-approved AuthorityChat device sets before an authority call may ring. */
object AuthorityMlsCallGate {
    suspend fun isVerified(
        context: Context,
        selfUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        channelId: String,
    ): Boolean = runCatching {
        // Existing call signaling carries no HA-session discriminator. Keep its released
        // trust binding; receiving an old call must never allocate a separate chat session.
        val canonical = AuthorityMlsIdentifiers.canonicalBinding(
            AuthorityMlsBinding(scopeType, channelId, listOf(selfUid, peerUid), routeResolved = true),
        )
        val conversationId = AuthorityMlsIdentifiers.conversationId(canonical)
        val directory = AuthorityMlsTransport().apply { configure(canonical) }.loadDeviceDirectory(conversationId)
        if (directory.rejected != 0) return@runCatching false
        val grouped = directory.records.groupBy { it.uid }
        if (grouped.keys != canonical.participants.toSet()) return@runCatching false
        val trust = AuthorityMlsTrustStore(context.applicationContext)
        canonical.participants.all { uid ->
            trust.verifyExisting(conversationId, uid, grouped[uid].orEmpty()).approved
        }
    }.getOrDefault(false)
}
