package com.auralis.crisisconnect.domain.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.os.StatFs
import com.auralis.crisisconnect.data.offline.OfflineDownloadRequest
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.abs
import kotlin.math.max

/**
 * Starts a new offline download after performing guard checks such as storage and network constraints.
 */
class StartDownloadUseCase(
    private val context: Context,
    private val repository: OfflineRegionRepository,
    private val styleUrlProvider: StyleUrlProvider,
) {

    /** Parameters describing the requested offline region. */
    data class Params(
        val name: String,
        val bounds: LatLngBounds,
        val minZoom: Double,
        val maxZoom: Double,
        val pixelRatio: Float,
        val force: Boolean = false,
    )

    /** Possible warnings that might require explicit user confirmation. */
    sealed class DownloadWarning {
        data class LowStorage(val requiredBytes: Long, val availableBytes: Long) : DownloadWarning()
        object MeteredNetwork : DownloadWarning()
        object Roaming : DownloadWarning()
        object PowerSave : DownloadWarning()
    }

    /** Result of attempting to kick off an offline download. */
    sealed class Result {
        data class Success(val entity: OfflineRegionEntity) : Result()
        data class NeedsUserConfirmation(val warnings: List<DownloadWarning>) : Result()
        data class Failure(val throwable: Throwable) : Result()
    }

    /** Executes the use case and returns the resulting outcome. */
    suspend operator fun invoke(params: Params): Result = withContext(Dispatchers.IO) {
        val warnings = buildList {
            evaluateStorage(params)?.let { add(it) }
            if (isMetered()) add(DownloadWarning.MeteredNetwork)
            if (isRoaming()) add(DownloadWarning.Roaming)
            if (isPowerSaveMode()) add(DownloadWarning.PowerSave)
        }
        if (warnings.isNotEmpty() && !params.force) {
            return@withContext Result.NeedsUserConfirmation(warnings)
        }

        val styleUrl = styleUrlProvider.primaryOrFallback()
        val request = OfflineDownloadRequest(
            name = params.name,
            bounds = params.bounds,
            minZoom = params.minZoom,
            maxZoom = params.maxZoom,
            styleUrl = styleUrl,
            pixelRatio = params.pixelRatio,
        )
        runCatching { repository.startDownload(request) }
            .fold(
                onSuccess = { Result.Success(it) },
                onFailure = { Result.Failure(it) }
            )
    }

    private fun evaluateStorage(params: Params): DownloadWarning.LowStorage? {
        val estimator = estimateBytes(params.bounds, params.minZoom, params.maxZoom)
        val statFs = StatFs(context.filesDir.absolutePath)
        val available = statFs.availableBytes
        return if (available < estimator) {
            DownloadWarning.LowStorage(requiredBytes = estimator, availableBytes = available)
        } else {
            null
        }
    }

    private fun estimateBytes(bounds: LatLngBounds, minZoom: Double, maxZoom: Double): Long {
        val latSpan = abs(bounds.latitudeNorth - bounds.latitudeSouth)
        val lonSpan = abs(bounds.longitudeEast - bounds.longitudeWest)
        val areaFactor = max(latSpan * lonSpan, 0.05)
        val zoomFactor = max(maxZoom - minZoom, 1.0)
        val tileEstimate = areaFactor * zoomFactor * 1200
        return (tileEstimate * AVERAGE_TILE_SIZE_BYTES).toLong()
    }

    private fun isMetered(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun isRoaming(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return connectivityManager.activeNetworkInfo?.isRoaming == true
    }

    private fun isPowerSaveMode(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isPowerSaveMode
    }

    private companion object {
        private const val AVERAGE_TILE_SIZE_BYTES = 50_000L
    }
}
