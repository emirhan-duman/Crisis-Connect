package com.auralis.crisisconnect.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts")
    fun getContacts(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveContact(contact: ContactEntity)

    @Query("SELECT * FROM contacts WHERE sessionCode = :sessionCode LIMIT 1")
    fun getContactBySessionCode(sessionCode: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE remoteSessionCode = :remoteSessionCode LIMIT 1")
    fun getContactByRemoteSessionCode(remoteSessionCode: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE address = :address LIMIT 1")
    fun getContactByAddress(address: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE remoteDeviceId = :remoteDeviceId LIMIT 1")
    fun getContactByRemoteDeviceId(remoteDeviceId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE peerUid = :peerUid LIMIT 1")
    fun getContactByPeerUid(peerUid: String): ContactEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE preferredTransport = 'BLE_GATT')")
    fun hasAnyBleGattContacts(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM contacts WHERE preferredTransport = 'BLE_GATT')")
    fun observeHasAnyBleGattContacts(): Flow<Boolean>

    @Query("SELECT * FROM contacts")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE sessionCode = :sessionCode LIMIT 1")
    fun observeContactBySessionCode(sessionCode: String): Flow<ContactEntity?>

    @Query("UPDATE contacts SET address = :address WHERE sessionCode = :sessionCode")
    fun updateContactAddress(sessionCode: String, address: String)

    @Query("UPDATE contacts SET aesKey = :aesKey WHERE sessionCode = :sessionCode")
    fun updateContactAesKey(sessionCode: String, aesKey: String)

    @Query("UPDATE contacts SET name = :name WHERE sessionCode = :sessionCode")
    fun updateContactName(sessionCode: String, name: String)

    @Query(
        "UPDATE contacts SET " +
            "lastKnownBleAddress = :lastKnownBleAddress, " +
            "name = :name " +
            "WHERE sessionCode = :sessionCode"
    )
    fun updateBleRuntimeMetadata(
        sessionCode: String,
        lastKnownBleAddress: String,
        name: String
    )

    @Query("UPDATE contacts SET address = UPPER(address) WHERE address != ''")
    fun normalizeAddresses()

    @Delete
    fun deleteContact(contact: ContactEntity)

    @Query("DELETE FROM contacts WHERE sessionCode = :sessionCode")
    fun deleteBySessionCode(sessionCode: String)
}
