package com.auralis.crisisconnect.data.offline

import kotlinx.coroutines.flow.Flow
import org.maplibre.android.geometry.LatLngBounds

/**
 * Contract describing offline region data operations.
 */
interface OfflineRegionRepository {

    /** Flow exposing the persisted offline regions. */
    val regionsFlow: Flow<List<OfflineRegionEntity>>

    /** Persists and starts a download for the supplied request. */
    suspend fun startDownload(request: OfflineDownloadRequest): OfflineRegionEntity

    /** Pauses the download identified by [regionId]. */
    suspend fun pause(regionId: Long)

    /** Resumes the download identified by [regionId]. */
    suspend fun resume(regionId: Long)

    /** Cancels and removes the region entirely. */
    suspend fun cancel(regionId: Long)

    /** Renames the persisted region. */
    suspend fun rename(regionId: Long, newName: String)

    /** Requests a refresh from the underlying offline manager. */
    suspend fun refresh()
}

/**
 * Data structure describing an offline download request.
 */
data class OfflineDownloadRequest(
    val name: String,
    val bounds: LatLngBounds,
    val minZoom: Double,
    val maxZoom: Double,
    val styleUrl: String,
    val pixelRatio: Float,
)
