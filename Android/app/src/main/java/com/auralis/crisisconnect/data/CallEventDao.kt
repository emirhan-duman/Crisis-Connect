package com.auralis.crisisconnect.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CallEventEntity)

    @Query("SELECT * FROM call_events WHERE sessionCode = :sessionCode ORDER BY timestampMillis ASC")
    fun observeForSession(sessionCode: String): Flow<List<CallEventEntity>>

    @Query(
        "SELECT * FROM call_events AS outer_events " +
            "WHERE outer_events.id = (" +
            "SELECT inner_events.id FROM call_events AS inner_events " +
            "WHERE inner_events.sessionCode = outer_events.sessionCode " +
            "ORDER BY inner_events.timestampMillis DESC, inner_events.id DESC LIMIT 1" +
            ")"
    )
    fun observeLatestEvents(): Flow<List<CallEventEntity>>

    @Query("UPDATE call_events SET sessionCode = :newSessionCode WHERE sessionCode = :oldSessionCode")
    suspend fun migrateSessionCode(oldSessionCode: String, newSessionCode: String)

    @Query(
        "DELETE FROM call_events WHERE sessionCode = :sessionCode AND id NOT IN " +
            "(SELECT id FROM call_events WHERE sessionCode = :sessionCode " +
            "ORDER BY timestampMillis DESC LIMIT :limit)"
    )
    suspend fun trim(sessionCode: String, limit: Int)
}
