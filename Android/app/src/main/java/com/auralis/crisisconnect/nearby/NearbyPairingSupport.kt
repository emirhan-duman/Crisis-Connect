package com.auralis.crisisconnect.nearby

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.getSavedUserName
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.messaging.MessagingIdentity
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * Glue between the transport-agnostic [NearbySpakePairing] session and the app (Firebase identity,
 * messaging key, contact store). Kept separate so the crypto/session core stays platform-neutral
 * and unit-testable.
 */
object NearbyPairingSupport {

    /** Our own identity to reveal to a peer once the SPAKE2 handshake authenticates the number. */
    suspend fun localIdentity(context: Context): NearbySpakePairing.NearbyIdentity {
        val uid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val publicKey = MessagingIdentity(context).publicKeyBase64()
        val name = runCatching { getSavedUserName(context).first().trim() }.getOrNull().orEmpty()
        return NearbySpakePairing.NearbyIdentity(uid = uid, publicKeyBase64 = publicKey, displayName = name)
    }

    /** Our own verified phone number in E.164, or null if not signed in with a phone. */
    fun ownPhoneE164(): String? =
        FirebaseAuth.getInstance().currentUser?.phoneNumber?.trim()?.takeIf { it.startsWith("+") }

    /**
     * Persists a contact for a peer we just paired with over SPAKE2. The peer's messaging identity
     * key is captured (so internet E2E works immediately) AND a Bluetooth P2P key is derived from
     * the SPAKE2 secret (so offline chat works too). The session code is the deterministic pair id
     * shared with internet messaging, unifying the thread across transports.
     */
    fun savePairedContact(
        context: Context,
        peer: NearbySpakePairing.NearbyIdentity,
        contactKey: ByteArray,
        bluetoothAddress: String?
    ): String {
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val sessionCode = if (myUid.isNotBlank() && peer.uid.isNotBlank()) {
            InternetConversation.pairId(myUid, peer.uid)
        } else {
            "n" + base64Url(sha256(contactKey)).take(16)
        }
        // Pairing upgrades the transport of whatever contact lives at this sessionCode — it must
        // not change the contact's nature. That sentence was only half honoured: the row is rebuilt
        // from scratch, and only isAuthorityBridge survived. Everything else fell back to defaults —
        // including peerIsChild, which is a SAFETY gate (a child peer must never become an SOS
        // emergency-contact candidate; SosEmergencyContactsStore and SosContactNotifier both key on
        // it), and peerPhotoUrl, so the avatar a directory add had fetched vanished on upgrade. The
        // contact DAO REPLACEs on conflict and the repository sticky-merges only peerPhone, so this
        // construction site is the one place these can be preserved.
        val existing = getContact(context, sessionCode)
        saveContact(
            context,
            Contact(
                // A name the user already has (their address book's, or their own edit) beats the
                // peer's self-declared display name.
                name = existing?.name?.ifBlank { null }
                    ?: peer.displayName.ifBlank { bluetoothAddress.orEmpty() },
                aesKey = Base64.encodeToString(contactKey, Base64.NO_WRAP),
                sessionCode = sessionCode,
                address = bluetoothAddress.orEmpty(),
                peerUid = peer.uid,
                peerPublicKey = peer.publicKeyBase64,
                peerPhotoUrl = existing?.peerPhotoUrl.orEmpty(),
                peerIsChild = existing?.peerIsChild == true,
                isAuthorityBridge = existing?.isAuthorityBridge == true
            ),
            analyticsSource = "nearby"
        )
        return sessionCode
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
}
