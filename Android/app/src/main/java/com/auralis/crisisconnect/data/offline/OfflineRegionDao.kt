package com.auralis.crisisconnect.data.offline

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access object for maintaining offline region metadata.
 */
@Dao
interface OfflineRegionDao {
    /** Returns a cold flow of all offline regions sorted by creation time descending. */
    @Query("SELECT * FROM offline_regions ORDER BY created_at DESC")
    fun observeRegions(): Flow<List<OfflineRegionEntity>>

    /** Reads a single region by its regionId if available. */
    @Query("SELECT * FROM offline_regions WHERE region_id = :regionId LIMIT 1")
    suspend fun getByRegionId(regionId: Long): OfflineRegionEntity?

    /** Reads a single region by its local identifier. */
    @Query("SELECT * FROM offline_regions WHERE local_id = :localId LIMIT 1")
    suspend fun getByLocalId(localId: Long): OfflineRegionEntity?

    /** Persists the provided entity, replacing any conflicting row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OfflineRegionEntity): Long

    /** Performs a partial update on an existing entity. */
    @Update
    suspend fun update(entity: OfflineRegionEntity)

    /** Deletes the given entity permanently. */
    @Delete
    suspend fun delete(entity: OfflineRegionEntity)

    /** Deletes rows by region id, typically after a remote deletion. */
    @Query("DELETE FROM offline_regions WHERE region_id = :regionId")
    suspend fun deleteByRegionId(regionId: Long)
}
