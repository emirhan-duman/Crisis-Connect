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
        val canonical = AuthorityMlsIdentifiers.canonicalBinding(
            AuthorityMlsBinding(scopeType, channelId, listOf(selfUid, peerUid)),
        )
        val conversationId = AuthorityMlsIdentifiers.conversationId(canonical)
        val directory = AuthorityMlsTransport().loadDeviceDirectory(conversationId)
        if (directory.rejected != 0) return@runCatching false
        val grouped = directory.records.groupBy { it.uid }
        if (grouped.keys != canonical.participants.toSet()) return@runCatching false
        val trust = AuthorityMlsTrustStore(context.applicationContext)
        canonical.participants.all { uid ->
            trust.verifyExisting(conversationId, uid, grouped[uid].orEmpty()).approved
        }
    }.getOrDefault(false)
}
