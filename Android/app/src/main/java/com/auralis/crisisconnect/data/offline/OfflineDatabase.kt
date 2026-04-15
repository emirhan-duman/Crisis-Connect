package com.auralis.crisisconnect.data.offline

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database that persists offline region metadata.
 */
@Database(
    entities = [OfflineRegionEntity::class],
    version = 1,
    exportSchema = true
)
abstract class OfflineDatabase : RoomDatabase() {

    /** Exposes the DAO responsible for offline region operations. */
    abstract fun offlineRegionDao(): OfflineRegionDao

    companion object {
        private const val DATABASE_NAME = "offline_regions.db"

        /** Builds a singleton instance of the offline database. */
        fun build(context: Context): OfflineDatabase =
            Room.databaseBuilder(context, OfflineDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
