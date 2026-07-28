package com.auralis.crisisconnect.data.signal

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Synchronous DAO for the Signal-protocol store. libsignal's store interfaces are blocking and are
 * driven from a background thread (the FCM receive path / send path), so these are plain synchronous
 * queries rather than suspend/Flow.
 */
@Dao
interface SignalStoreDao {

    // ---- Identities ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putIdentity(entity: SignalIdentityEntity)

    @Query("SELECT * FROM signal_identities WHERE address = :address LIMIT 1")
    fun getIdentity(address: String): SignalIdentityEntity?

    // ---- Sessions ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSession(entity: SignalSessionEntity)

    @Query("SELECT * FROM signal_sessions WHERE address = :address LIMIT 1")
    fun getSession(address: String): SignalSessionEntity?

    @Query("SELECT * FROM signal_sessions WHERE name = :name")
    fun getSessionsForName(name: String): List<SignalSessionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM signal_sessions WHERE address = :address)")
    fun containsSession(address: String): Boolean

    @Query("DELETE FROM signal_sessions WHERE address = :address")
    fun deleteSession(address: String)

    @Query("DELETE FROM signal_sessions WHERE name = :name")
    fun deleteSessionsForName(name: String)

    // ---- One-time EC prekeys ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putPreKey(entity: SignalPreKeyEntity)

    @Query("SELECT * FROM signal_prekeys WHERE keyId = :keyId LIMIT 1")
    fun getPreKey(keyId: Int): SignalPreKeyEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM signal_prekeys WHERE keyId = :keyId)")
    fun containsPreKey(keyId: Int): Boolean

    @Query("DELETE FROM signal_prekeys WHERE keyId = :keyId")
    fun deletePreKey(keyId: Int)

    // ---- Signed prekeys ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSignedPreKey(entity: SignalSignedPreKeyEntity)

    @Query("SELECT * FROM signal_signed_prekeys WHERE keyId = :keyId LIMIT 1")
    fun getSignedPreKey(keyId: Int): SignalSignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    fun getAllSignedPreKeys(): List<SignalSignedPreKeyEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM signal_signed_prekeys WHERE keyId = :keyId)")
    fun containsSignedPreKey(keyId: Int): Boolean

    @Query("DELETE FROM signal_signed_prekeys WHERE keyId = :keyId")
    fun deleteSignedPreKey(keyId: Int)

    // ---- Kyber prekeys ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putKyberPreKey(entity: SignalKyberPreKeyEntity)

    @Query("SELECT * FROM signal_kyber_prekeys WHERE keyId = :keyId LIMIT 1")
    fun getKyberPreKey(keyId: Int): SignalKyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys")
    fun getAllKyberPreKeys(): List<SignalKyberPreKeyEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM signal_kyber_prekeys WHERE keyId = :keyId)")
    fun containsKyberPreKey(keyId: Int): Boolean

    @Query("UPDATE signal_kyber_prekeys SET used = 1 WHERE keyId = :keyId")
    fun markKyberPreKeyUsed(keyId: Int)

    // ---- Sender keys (group messaging; unused today but required by the interface) ----
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSenderKey(entity: SignalSenderKeyEntity)

    @Query("SELECT * FROM signal_sender_keys WHERE address = :address AND distributionId = :distributionId LIMIT 1")
    fun getSenderKey(address: String, distributionId: String): SignalSenderKeyEntity?

    // ---- Wipe (identity rotation / reinstall) ----
    @Query("DELETE FROM signal_prekeys")
    fun clearPreKeys()

    @Query("DELETE FROM signal_signed_prekeys")
    fun clearSignedPreKeys()

    @Query("DELETE FROM signal_kyber_prekeys")
    fun clearKyberPreKeys()

    @Query("DELETE FROM signal_sessions")
    fun clearSessions()

    @Query("DELETE FROM signal_identities")
    fun clearIdentities()
}
