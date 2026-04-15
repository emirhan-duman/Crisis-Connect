package com.auralis.crisisconnect.data.offline

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity that mirrors the lifecycle and metadata of a stored offline region.
 */
@Entity(tableName = "offline_regions")
data class OfflineRegionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "local_id")
    val localId: Long = 0L,
    @ColumnInfo(name = "region_id")
    val regionId: Long? = null,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "bounds_north")
    val boundsNorth: Double,
    @ColumnInfo(name = "bounds_south")
    val boundsSouth: Double,
    @ColumnInfo(name = "bounds_east")
    val boundsEast: Double,
    @ColumnInfo(name = "bounds_west")
    val boundsWest: Double,
    @ColumnInfo(name = "min_zoom")
    val minZoom: Double,
    @ColumnInfo(name = "max_zoom")
    val maxZoom: Double,
    @ColumnInfo(name = "style_url")
    val styleUrl: String,
    @ColumnInfo(name = "created_at")
    val createdAtEpochMillis: Long,
    @ColumnInfo(name = "status")
    val status: OfflineRegionStatus = OfflineRegionStatus.Idle,
    @ColumnInfo(name = "bytes_downloaded")
    val bytesDownloaded: Long = 0L,
    @ColumnInfo(name = "bytes_required")
    val bytesRequired: Long = 0L,
    @ColumnInfo(name = "is_paused")
    val isPaused: Boolean = false,
)

/**
 * Describes the status of an offline region in a user friendly manner.
 */
enum class OfflineRegionStatus {
    Idle,
    Downloading,
    Complete,
    Failed,
    Deleted
}
