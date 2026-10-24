package com.auralis.crisisconnect.data

import androidx.room.ColumnInfo
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
    val isAuthorityBridge: Boolean = false,
    @ColumnInfo(defaultValue = "1") val offlineKeyVersion: Int = 2
)

fun ContactEntity.toContact(): Contact {
    val normalizedVerifiedIdentityKey = normalizeVerifiedIdentityKey(verifiedIdentityKey)
    val trusted = offlineKeyVersion >= 2 && verified && normalizedVerifiedIdentityKey.isNotBlank()
    return Contact(
        name = name,
        aesKey = if (offlineKeyVersion >= 2) aesKey else "",
        offlineKeyVersion = offlineKeyVersion,
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
    val trusted = offlineKeyVersion >= 2 && verified && normalizedVerifiedIdentityKey.isNotBlank()
    return ContactEntity(
        sessionCode = sessionCode,
        name = name,
        aesKey = if (offlineKeyVersion >= 2) aesKey else "",
        offlineKeyVersion = offlineKeyVersion,
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

/** Preserve retired bytes only inside storage. They never enter transport-facing Contact.aesKey. */
internal fun preserveLegacyOfflineStorage(incoming: ContactEntity, existing: ContactEntity?): ContactEntity {
    if (existing?.offlineKeyVersion == 1 && existing.aesKey.isNotBlank() && incoming.aesKey == existing.aesKey) {
        return incoming.copy(offlineKeyVersion = 1, verified = false, verifiedIdentityKey = "", verifiedAt = null)
    }
    if (incoming.offlineKeyVersion < 2 && existing != null && existing.offlineKeyVersion >= 2 && existing.aesKey.isNotBlank()) return existing
    if (existing == null || existing.offlineKeyVersion >= 2 ||
        (incoming.offlineKeyVersion >= 2 && incoming.aesKey.isNotBlank())) return incoming
    return incoming.copy(aesKey = existing.aesKey, offlineKeyVersion = 1,
        verified = false, verifiedIdentityKey = "", verifiedAt = null)
}
