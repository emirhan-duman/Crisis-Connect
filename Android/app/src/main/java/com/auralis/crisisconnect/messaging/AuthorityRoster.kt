package com.auralis.crisisconnect.messaging

import android.content.Context
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.saveContact
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** A fellow authority in the caller's agency, with the E.164 phone the offline link needs. */
data class AuthorityRosterMember(
    val uid: String,
    val name: String,
    val role: String,
    val phone: String,
    val photoUrl: String
) {
    /** Only members whose number we received can be reached offline by the number-keyed SPAKE2 link. */
    val canLinkOffline: Boolean get() = phone.isNotBlank()
}

/**
 * Client for the same-agency authority roster (`listAuthorityRoster`) + turning a roster member into
 * a 1:1 contact. Adding a member looks up their published identity key (so the chat works over the
 * internet 1:1 relay AND satisfies [com.auralis.crisisconnect.nearby.NearbyAutoLink]'s eligibility)
 * and stores their number as `peerPhone`, so opening the chat while offline + nearby auto-bootstraps
 * a Bluetooth link by number — the offline half of authority comms.
 */
class AuthorityRosterClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(REGION),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val identityClient: InternetMessagingClient = InternetMessagingClient()
) {
    suspend fun listRoster(agencySlug: String): List<AuthorityRosterMember> = withContext(Dispatchers.IO) {
        val result = functions.getHttpsCallable("listAuthorityRoster")
            .call(hashMapOf("agencySlug" to agencySlug))
            .await()
        val data = result.data as? Map<*, *> ?: return@withContext emptyList()
        val members = data["members"] as? List<*> ?: return@withContext emptyList()
        members.mapNotNull { row ->
            val m = row as? Map<*, *> ?: return@mapNotNull null
            val uid = (m["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AuthorityRosterMember(
                uid = uid,
                name = (m["name"] as? String)?.takeIf { it.isNotBlank() } ?: uid,
                role = m["role"] as? String ?: "",
                phone = m["phone"] as? String ?: "",
                photoUrl = m["photoUrl"] as? String ?: ""
            )
        }
    }

    /**
     * Adds [member] as a 1:1 contact (create-or-refresh) and returns its sessionCode to open, or null
     * if we can't resolve the caller or the peer's identity key (e.g. a web-only authority with no app
     * on this device — nothing to reach offline anyway). Mirrors the citizen add-by-number contact.
     */
    suspend fun addContact(context: Context, member: AuthorityRosterMember): String? = withContext(Dispatchers.IO) {
        val myUid = auth.currentUser?.uid ?: return@withContext null
        val identity = runCatching { identityClient.lookupIdentityKey(uid = member.uid) }.getOrNull()
        val publicKey = identity?.publicKeyBase64?.takeIf { it.isNotBlank() } ?: return@withContext null
        val appContext = context.applicationContext
        val existing = getContacts(appContext).firstOrNull { it.peerUid == member.uid }
        if (existing != null) {
            val keyChanged = existing.peerPublicKey.isNotBlank() && existing.peerPublicKey != publicKey
            saveContact(
                appContext,
                existing.copy(
                    peerPublicKey = publicKey,
                    peerPhotoUrl = member.photoUrl.ifBlank { identity.photoUrl }.ifBlank { existing.peerPhotoUrl },
                    name = existing.name.ifBlank { member.name },
                    peerKeyChanged = existing.peerKeyChanged || keyChanged,
                    // Keep the number so an offline Bluetooth link can be bootstrapped later.
                    peerPhone = member.phone.ifBlank { existing.peerPhone }
                )
            )
            existing.sessionCode
        } else {
            val sessionCode = InternetConversation.pairId(myUid, member.uid)
            saveContact(
                appContext,
                Contact(
                    name = member.name,
                    aesKey = "",
                    sessionCode = sessionCode,
                    peerUid = member.uid,
                    peerPublicKey = publicKey,
                    peerPhotoUrl = member.photoUrl.ifBlank { identity.photoUrl },
                    peerPhone = member.phone
                ),
                analyticsSource = "authority_roster"
            )
            sessionCode
        }
    }

    companion object {
        private const val REGION = "us-central1"
    }
}
