package com.auralis.crisisconnect.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Locale

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val sessionCode: String,
    val name: String,
    val aesKey: String,
    val verified: Boolean = false,
    val verifiedIdentityKey: String = "",
    val verifiedAt: Long? = null,
    val address: String = "",
    val remoteSessionCode: String = "",
    val preferredTransport: String = PREFERRED_TRANSPORT_RFCOMM,
    val remotePlatform: String = REMOTE_PLATFORM_UNKNOWN,
    val bleShareId: String = "",
    val lastKnownBleAddress: String = "",
    val remoteDeviceId: String = "",
    // Firebase uid of the internet-messaging peer and their published identity public key
    // (base64 SPKI). Captured from a QR scan or a directory lookup; empty for BT-only contacts.
    val peerUid: String? = null,
    val peerPublicKey: String? = null,
    // The peer's published profile photo URL (Firebase Storage), shown via ContactAvatar.
    val peerPhotoUrl: String? = null,
    // Set when the peer's identity key changed from what we last stored (safety-number changed) —
    // surfaces a "verify again" warning until the user re-confirms.
    val peerKeyChanged: Boolean = false,
    // The peer's E.164 phone number, captured when added by number over the internet. Used as the
    // SPAKE2 password to automatically bootstrap an offline Bluetooth link when the two are nearby.
    // Empty for contacts not added by number.
    val peerPhone: String? = null,
    // Whether the peer's device reported running in child profile mode when the contact was
    // exchanged. Child contacts are never offered/used as SOS emergency contacts.
    val peerIsChild: Boolean = false,
    // Hidden transport-only contact for an authority channel peer (offline Bluetooth bridge).
    val isAuthorityBridge: Boolean = false
)

fun ContactEntity.toContact(): Contact {
    val normalizedVerifiedIdentityKey = normalizeVerifiedIdentityKey(verifiedIdentityKey)
    val trusted = verified && normalizedVerifiedIdentityKey.isNotBlank()
    return Contact(
        name = name,
        aesKey = aesKey,
        sessionCode = sessionCode,
        verified = trusted,
        verifiedIdentityKey = if (trusted) normalizedVerifiedIdentityKey else "",
        verifiedAt = if (trusted) verifiedAt else null,
        address = normalizeMacAddress(address),
        remoteSessionCode = remoteSessionCode.trim(),
        preferredTransport = normalizePreferredTransport(preferredTransport),
        remotePlatform = normalizeRemotePlatform(remotePlatform),
        bleShareId = bleShareId.trim().uppercase(Locale.US),
        lastKnownBleAddress = normalizeMacAddress(lastKnownBleAddress),
        remoteDeviceId = remoteDeviceId.trim(),
        peerUid = peerUid?.trim().orEmpty(),
        peerPublicKey = peerPublicKey?.trim().orEmpty(),
        peerPhotoUrl = peerPhotoUrl?.trim().orEmpty(),
        peerKeyChanged = peerKeyChanged,
        peerPhone = peerPhone?.trim().orEmpty(),
        peerIsChild = peerIsChild,
        isAuthorityBridge = isAuthorityBridge
    )
}

fun Contact.toEntity(): ContactEntity {
    val normalizedVerifiedIdentityKey = normalizeVerifiedIdentityKey(verifiedIdentityKey)
    val trusted = verified && normalizedVerifiedIdentityKey.isNotBlank()
    return ContactEntity(
        sessionCode = sessionCode,
        name = name,
        aesKey = aesKey,
        verified = trusted,
        verifiedIdentityKey = if (trusted) normalizedVerifiedIdentityKey else "",
        verifiedAt = if (trusted) verifiedAt else null,
        address = normalizeMacAddress(address),
        remoteSessionCode = remoteSessionCode.trim(),
        preferredTransport = normalizePreferredTransport(preferredTransport),
        remotePlatform = normalizeRemotePlatform(remotePlatform),
        bleShareId = bleShareId.trim().uppercase(Locale.US),
        lastKnownBleAddress = normalizeMacAddress(lastKnownBleAddress),
        remoteDeviceId = remoteDeviceId.trim(),
        peerUid = peerUid.trim().ifEmpty { null },
        peerPublicKey = peerPublicKey.trim().ifEmpty { null },
        peerPhotoUrl = peerPhotoUrl.trim().ifEmpty { null },
        peerKeyChanged = peerKeyChanged,
        peerPhone = peerPhone.trim().ifEmpty { null },
        peerIsChild = peerIsChild,
        isAuthorityBridge = isAuthorityBridge
    )
}
