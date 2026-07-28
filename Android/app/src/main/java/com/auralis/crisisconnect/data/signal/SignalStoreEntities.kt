package com.auralis.crisisconnect.data.signal

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persistence for the Signal-protocol (v3) messaging state. These tables live inside the app's
 * SQLCipher-encrypted [com.auralis.crisisconnect.data.AppDatabase], so the serialized records —
 * which contain private ratchet/prekey material — are encrypted at rest by the database itself.
 *
 * Every `record`/`identityKey` column holds the raw libsignal `serialize()` bytes; the store layer
 * ([com.auralis.crisisconnect.messaging.signal.AndroidSignalProtocolStore]) round-trips them through
 * the matching record constructor. Addresses are the libsignal `SignalProtocolAddress.toString()`
 * form ("<name>.<deviceId>").
 */

@Entity(tableName = "signal_identities")
data class SignalIdentityEntity(
    @PrimaryKey val address: String,
    val identityKey: ByteArray,
)

@Entity(tableName = "signal_sessions")
data class SignalSessionEntity(
    @PrimaryKey val address: String,
    /** name half of the address, so we can query all devices for a peer. */
    val name: String,
    val deviceId: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_prekeys")
data class SignalPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_signed_prekeys")
data class SignalSignedPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray,
)

@Entity(tableName = "signal_kyber_prekeys")
data class SignalKyberPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val record: ByteArray,
    /** libsignal marks one-time Kyber prekeys used after consumption (last-resort keys stay usable). */
    val used: Boolean,
)

@Entity(tableName = "signal_sender_keys", primaryKeys = ["address", "distributionId"])
data class SignalSenderKeyEntity(
    val address: String,
    val distributionId: String,
    val record: ByteArray,
)
