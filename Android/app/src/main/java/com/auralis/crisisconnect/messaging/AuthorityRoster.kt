package com.auralis.crisisconnect.messaging

import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Minimal same-agency identity returned by the membership-gated AuthorityChat directory. */
data class AuthorityRosterMember(
    val uid: String,
    val name: String,
    val role: String,
    val phone: String,
    val photoUrl: String,
    /** Canonical panel id returned by the server after resolving the authenticated caller. */
    val agencySlug: String,
) {
}

/**
 * Client for the same-agency AuthorityChat roster (`listAuthorityRoster`). Roster rows are routed by
 * the picker only into agency-scoped MLS-v2 threads; this client intentionally has no legacy
 * citizen-contact creation path.
 */
class AuthorityRosterClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
) {
    suspend fun listRoster(agencySlug: String): List<AuthorityRosterMember> = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("listAuthorityRoster")
            .call(hashMapOf("agencySlug" to agencySlug))
            .await()
        val data = result.data as? Map<*, *>
            ?: error("Authority roster response is malformed")
        val members = data["members"] as? List<*>
            ?: error("Authority roster response is malformed")
        require(members.size <= MAX_ROSTER_MEMBERS) { "Authority roster response is too large" }
        members.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val uid = (m["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AuthorityRosterMember(
                uid = uid,
                name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: uid,
                role = m["role"] as? String ?: "",
                phone = m["phone"] as? String ?: "",
                photoUrl = m["photoUrl"] as? String ?: "",
                agencySlug = (m["agencySlug"] as? String)?.trim().orEmpty(),
            )
        }.filter { it.agencySlug.isNotBlank() }
    }

    companion object {
        private const val REGION = "us-central1"
        private const val MAX_ROSTER_MEMBERS = 2_000
    }
}
