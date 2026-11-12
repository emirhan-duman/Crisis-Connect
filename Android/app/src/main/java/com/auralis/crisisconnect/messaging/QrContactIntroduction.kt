package com.auralis.crisisconnect.messaging

import com.auralis.crisisconnect.data.Contact
import org.json.JSONObject

/** Builds reciprocal contacts only from a proof of the QR capability or an existing pairing. */
internal object QrContactIntroduction {
    fun resolve(
        sender: String,
        owner: String,
        publicKey: String,
        payload: JSONObject?,
        existing: Contact?,
        activeQrKey: String?,
        fallbackName: String,
        knownPeerIsChild: Boolean = false,
        knownPeerPhotoUrl: String = "",
    ): Contact? {
        val code = payload?.optString("rsc")?.trim().orEmpty()
        val proof = payload?.optString("binding").orEmpty()
        if (sender.isBlank() || sender == owner || publicKey.isBlank() || code.isBlank() || code.length > 128) return null
        if (existing != null && (existing.peerUid.isNotBlank() && existing.peerUid != sender ||
                existing.peerPublicKey.isNotBlank() && existing.peerPublicKey != publicKey)) return null
        // A matching short session code alone cannot rebind an older BLE peer.
        // Heal that peer only with its own paired key; the live QR authorizes new rows.
        val proofKey = existing?.aesKey?.takeIf(String::isNotBlank) ?: activeQrKey
        val proven = proofKey != null && ContactIdentityBinding.verify(proof, proofKey, sender, code, publicKey)
        // Replays may arrive after the short-lived QR has closed. A pinned, unchanged
        // identity is already present: acknowledge it without changing the contact.
        if (!proven) return existing?.takeIf { it.peerUid == sender && it.peerPublicKey == publicKey }
        if (existing != null) return existing.copy(
            peerUid = sender,
            peerPublicKey = publicKey,
            remoteSessionCode = existing.remoteSessionCode.ifBlank { code },
            aesKey = existing.aesKey.ifBlank { activeQrKey.orEmpty() }
        )
        return Contact(
            name = payload?.optString("name")?.trim()?.take(120)?.takeIf(String::isNotBlank) ?: fallbackName,
            sessionCode = InternetConversation.pairId(sender, owner),
            aesKey = activeQrKey.orEmpty(),
            offlineKeyVersion = 1,
            verified = false,
            remoteSessionCode = code,
            peerUid = sender,
            peerPublicKey = publicKey,
            peerIsChild = knownPeerIsChild,
            peerPhotoUrl = knownPeerPhotoUrl,
        )
    }
}
