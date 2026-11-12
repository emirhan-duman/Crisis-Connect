package com.auralis.crisisconnect.nearby

import android.content.Context
import android.util.Base64
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.data.getContact
import com.auralis.crisisconnect.data.getContactByPeerUid
import com.auralis.crisisconnect.data.saveContact
import com.auralis.crisisconnect.getSavedUserName
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
        // Number knowledge cannot bind or replace a Firebase/P256 account identity.
        // Keep the encrypted local connection separate until a trusted QR exchange.
        val sessionCode = localSessionCode(contactKey)
        val existing = getContact(context, sessionCode)
        val pinned = peer.uid.takeIf { it.isNotBlank() }?.let { getContactByPeerUid(context, it) } ?: existing
        require(pinned == null && existing == null) {
            "Phone-number pairing cannot replace an existing contact. Use a fresh QR pairing."
        }
        saveContact(
            context,
            Contact(
                name = peer.displayName.ifBlank { bluetoothAddress.orEmpty() },
                aesKey = Base64.encodeToString(contactKey, Base64.NO_WRAP),
                sessionCode = sessionCode,
                address = bluetoothAddress.orEmpty(),
                peerUid = "",
                peerPublicKey = ""
            ),
            analyticsSource = "nearby"
        )
        return sessionCode
    }

    internal fun localSessionCode(contactKey: ByteArray): String =
        "n" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(contactKey)).take(16)

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)


}
