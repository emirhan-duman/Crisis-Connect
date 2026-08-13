package com.auralis.crisisconnect.screens.Tools

import android.app.Application
import android.content.Context
import android.location.LocationManager
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.service.BreadcrumbTrailService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BreadcrumbTrailUiState(
    val session: BreadcrumbTrailSession? = null,
    val currentLocation: BreadcrumbPoint? = null,
    val distanceToStartMeters: Double? = null,
    val distanceToSafeMeters: Double? = null,
    val routeDistanceMeters: Double = 0.0,
    val remainingRouteMeters: Double? = null,
    val nextBreadcrumbMeters: Double? = null,
    val targetBearingDegrees: Double? = null,
    val returnProgress: Float = 0f,
    val offlineRegionName: String? = null,
    val completedOfflineRegionCount: Int = 0,
    @StringRes val errorMessageRes: Int? = null,
)

class BreadcrumbTrailViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val _uiState = MutableStateFlow(BreadcrumbTrailUiState())
    val uiState: StateFlow<BreadcrumbTrailUiState> = _uiState.asStateFlow()
    private var runtime = BreadcrumbRuntimeState()
    private var readyRegions = emptyList<OfflineRegionEntity>()

    init {
        BreadcrumbTrailRepository.initialize(context)
        viewModelScope.launch {
            BreadcrumbTrailRepository.state.collect { value ->
                runtime = value
                publish()
            }
        }
        runCatching { OfflineServiceLocator.provideListRegionsUseCase(context) }
            .onSuccess { listRegions ->
                viewModelScope.launch {
                    listRegions()
                        .catch { emit(emptyList()) }
                        .collect { regions ->
                            readyRegions = regions.filter { it.status == OfflineRegionStatus.Complete }
                            publish()
                        }
                }
            }
    }

    fun startNewTrail() {
        if (!isLocationEnabled()) {
            postError(R.string.breadcrumb_error_location_disabled)
            return
        }
        BreadcrumbTrailRepository.startNew()
        startService()
    }

    fun resumeRecording() {
        if (!isLocationEnabled()) {
            postError(R.string.breadcrumb_error_location_disabled)
            return
        }
        BreadcrumbTrailRepository.resumeRecording()
        startService()
    }

    fun pause() {
        BreadcrumbTrailRepository.pause()
        BreadcrumbTrailService.stop(context)
    }

    fun clearTrail() {
        BreadcrumbTrailService.stop(context)
        BreadcrumbTrailRepository.clear()
    }

    fun markSafeLocation() {
        BreadcrumbTrailRepository.markCurrentAsSafe()
    }

    fun startReturn(target: BreadcrumbReturnTarget) {
        val session = runtime.session
        if (session == null || session.points.size < 2) {
            postError(R.string.breadcrumb_error_not_enough_points)
            return
        }
        if (!isLocationEnabled()) {
            postError(R.string.breadcrumb_error_location_disabled)
            return
        }
        BreadcrumbTrailRepository.startReturn(target)
        startService()
    }

    fun reportPermissionDenied() = postError(R.string.breadcrumb_error_permission)

    fun clearError() {
        _uiState.update { it.copy(errorMessageRes = null) }
    }

    fun trailForMap(maxPoints: Int = 250): List<BreadcrumbPoint> {
        val points = runtime.session?.points.orEmpty()
        if (points.size <= maxPoints) return points
        val stride = ((points.size - 1).toDouble() / (maxPoints - 1)).coerceAtLeast(1.0)
        return buildList {
            var index = 0.0
            while (index < points.lastIndex) {
                add(points[index.toInt().coerceIn(0, points.lastIndex)])
                index += stride
            }
            if (lastOrNull() != points.last()) add(points.last())
        }
    }

    private fun startService() {
        runCatching { BreadcrumbTrailService.start(context) }
            .onFailure { postError(R.string.breadcrumb_error_service) }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun publish() {
        val session = runtime.session
        val current = runtime.currentLocation ?: session?.points?.lastOrNull()
        val points = session?.points.orEmpty()
        val start = points.firstOrNull()
        val safe = session?.let { points.getOrNull(it.safePointIndex) }
        val destinationIndex = session?.let {
            when (it.returnTarget) {
                BreadcrumbReturnTarget.START -> 0
                BreadcrumbReturnTarget.LAST_SAFE -> it.safePointIndex.coerceIn(0, points.lastIndex.coerceAtLeast(0))
            }
        } ?: 0
        val cursor = session?.returnCursor
        val next = cursor?.let(points::getOrNull)
        val containingRegion = current?.let { location ->
            readyRegions.firstOrNull { it.contains(location) }
        } ?: start?.let { location -> readyRegions.firstOrNull { it.contains(location) } }

        val progress = if (session?.mode == BreadcrumbTrailMode.RETURNING && cursor != null) {
            val denominator = (points.lastIndex - destinationIndex).coerceAtLeast(1)
            ((points.lastIndex - cursor).toFloat() / denominator).coerceIn(0f, 1f)
        } else if (session?.mode == BreadcrumbTrailMode.ARRIVED) {
            1f
        } else {
            0f
        }

        _uiState.update { previous ->
            previous.copy(
                session = session,
                currentLocation = current,
                distanceToStartMeters = if (current != null && start != null) {
                    BreadcrumbTrailMath.distanceMeters(current, start)
                } else null,
                distanceToSafeMeters = if (current != null && safe != null) {
                    BreadcrumbTrailMath.distanceMeters(current, safe)
                } else null,
                routeDistanceMeters = BreadcrumbTrailMath.routeDistance(points),
                remainingRouteMeters = if (current != null && cursor != null && points.isNotEmpty()) {
                    BreadcrumbTrailMath.remainingRouteDistance(points, current, cursor, destinationIndex)
                } else null,
                nextBreadcrumbMeters = if (current != null && next != null) {
                    BreadcrumbTrailMath.distanceMeters(current, next)
                } else null,
                targetBearingDegrees = if (current != null && next != null) {
                    BreadcrumbTrailMath.bearingDegrees(current, next)
                } else null,
                returnProgress = progress,
                offlineRegionName = containingRegion?.name,
                completedOfflineRegionCount = readyRegions.size,
            )
        }
    }

    private fun postError(@StringRes message: Int) {
        _uiState.update { it.copy(errorMessageRes = message) }
    }

    private fun OfflineRegionEntity.contains(point: BreadcrumbPoint): Boolean {
        if (point.latitude !in boundsSouth..boundsNorth) return false
        return if (boundsWest <= boundsEast) {
            point.longitude in boundsWest..boundsEast
        } else {
            point.longitude >= boundsWest || point.longitude <= boundsEast
        }
    }
}
