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
    val remoteDeviceId: String = ""
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
        remoteDeviceId = remoteDeviceId.trim()
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
        remoteDeviceId = remoteDeviceId.trim()
    )
}
