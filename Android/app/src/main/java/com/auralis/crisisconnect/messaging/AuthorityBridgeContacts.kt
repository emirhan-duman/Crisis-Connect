package com.auralis.crisisconnect.messaging

import android.content.Context
import android.util.Log
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContacts
import com.auralis.crisisconnect.data.saveContact
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a hidden 1:1 "bridge" contact for every cross-panel authority (kurum) channel peer whose
 * E.164 number the backend released ([HierarchyPeer.phone], entitled managers only).
 *
 * The bridge contact is what lets the kurum channel chat work offline: it satisfies
 * [com.auralis.crisisconnect.nearby.NearbyAutoLink]'s eligibility (peerPhone + identity key, no BT
 * link yet), so being nearby bootstraps a number-keyed SPAKE2 Bluetooth link at a deterministic
 * sessionCode ([InternetConversation.pairId]) — the same citizen pipeline that already carries
 * messages, attachments and calls over Bluetooth. Bridge contacts are flagged
 * [Contact.isAuthorityBridge] and never surface in the home list or pickers.
 *
 * A peer the user already added deliberately (same uid) is left as-is — only its missing
 * offline-link inputs (number, name) are backfilled, and the bridge flag is never set on it.
 */
object AuthorityBridgeContacts {
    private const val TAG = "AuthorityBridge"

    suspend fun syncFromChannels(
        context: Context,
        channels: List<HierarchyChannel>
    ) = withContext(Dispatchers.IO) {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext
        if (channels.isEmpty()) return@withContext
        val appContext = context.applicationContext
        val identityClient = InternetMessagingClient()
        val contactsByUid = getContacts(appContext)
            .filter { it.peerUid.isNotBlank() }
            .associateBy { it.peerUid }
        var created = 0
        var peersSeen = 0
        var peersWithPhone = 0
        var missingIdentity = 0
        for (channel in channels) {
            for (peer in channel.peers) {
                if (peer.uid.isBlank() || peer.uid == myUid) continue
                peersSeen++
                val phone = peer.phone?.trim().orEmpty()
                if (phone.isEmpty()) continue
                peersWithPhone++
                val existing = contactsByUid[peer.uid]
                if (existing != null) {
                    if (existing.peerPhone.isBlank() || existing.name.isBlank()) {
                        saveContact(
                            appContext,
                            existing.copy(
                                peerPhone = existing.peerPhone.ifBlank { phone },
                                name = existing.name.ifBlank { peer.name }
                            )
                        )
                    }
                    continue
                }
                // The E2E layer needs the peer's published identity key. No key means no app
                // device registered under that account — nothing to reach offline anyway.
                val publicKey = runCatching { identityClient.lookupIdentityKey(uid = peer.uid) }
                    .getOrNull()?.publicKeyBase64?.takeIf { it.isNotBlank() }
                    ?: run {
                        missingIdentity++
                        return@run null
                    }
                    ?: continue
                saveContact(
                    appContext,
                    Contact(
                        name = peer.name,
                        aesKey = "",
                        sessionCode = InternetConversation.pairId(myUid, peer.uid),
                        peerUid = peer.uid,
                        peerPublicKey = publicKey,
                        peerPhotoUrl = peer.photoUrl.orEmpty(),
                        peerPhone = phone,
                        isAuthorityBridge = true
                    )
                )
                created++
            }
        }
        // Always log the outcome — "0 created" with the reason visible (no phone released vs no
        // identity key) is exactly what field debugging of the offline bridge needs.
        Log.i(
            TAG,
            "sync: channels=${channels.size} peers=$peersSeen withPhone=$peersWithPhone " +
                "created=$created missingIdentityKey=$missingIdentity"
        )
    }
}
