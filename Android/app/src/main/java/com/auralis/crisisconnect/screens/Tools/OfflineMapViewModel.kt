package com.auralis.crisisconnect.screens.Tools

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.auralis.crisisconnect.R
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.data.offline.OfflineServiceLocator
import com.auralis.crisisconnect.domain.offline.DeleteRegionUseCase
import com.auralis.crisisconnect.domain.offline.ListRegionsUseCase
import com.auralis.crisisconnect.domain.offline.PauseDownloadUseCase
import com.auralis.crisisconnect.domain.offline.ResumeDownloadUseCase
import com.auralis.crisisconnect.domain.offline.StartDownloadUseCase
import com.auralis.crisisconnect.domain.offline.RenameRegionUseCase
import com.auralis.crisisconnect.service.OfflineDownloadService
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLngBounds
import kotlin.math.abs
import kotlin.math.max

/**
 * ViewModel coordinating UI state and domain logic for the offline map tool.
 */
class OfflineMapViewModel internal constructor(
    private val appContext: Context,
    private val startDownloadUseCase: StartDownloadUseCase,
    private val pauseDownloadUseCase: PauseDownloadUseCase,
    private val resumeDownloadUseCase: ResumeDownloadUseCase,
    private val deleteRegionUseCase: DeleteRegionUseCase,
    private val renameRegionUseCase: RenameRegionUseCase,
    listRegionsUseCase: ListRegionsUseCase,
) : ViewModel() {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine failed", throwable)
        runCatching { FirebaseCrashlytics.getInstance().recordException(throwable) }
    }

    private val _uiState = MutableStateFlow(OfflineMapUiState())
    val uiState: StateFlow<OfflineMapUiState> = _uiState.asStateFlow()

    private var pendingStartParams: StartDownloadUseCase.Params? = null

    init {
        viewModelScope.launch(exceptionHandler) {
            listRegionsUseCase().collectLatest { regions ->
                _uiState.update { state ->
                    pendingStartParams = null
                    state.copy(
                        regions = regions.map { it.toUiModel() }
                    )
                }
            }
        }
    }

    /** Signals that the base map style is loading. */
    fun onStyleLoading() {
        _uiState.update { it.copy(isStyleLoaded = false) }
    }

    /** Signals that the base map style finished loading. */
    fun onStyleLoaded() {
        _uiState.update { it.copy(isStyleLoaded = true) }
    }

    /** Notifies that the fallback style had to be applied. */
    fun onStyleFallbackApplied() {
        postMessage(R.string.offline_map_style_fallback)
    }

    /** Clears a previously shown snackbar message. */
    fun onMessageShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    /** Opens or closes the editor sheet. */
    fun setEditorVisibility(visible: Boolean) {
        _uiState.update { it.copy(isEditorVisible = visible) }
    }

    /** Opens or closes the saved regions list. */
    fun setListVisibility(visible: Boolean) {
        _uiState.update { it.copy(isListVisible = visible) }
    }

    /** Updates the selection bounds based on the currently visible camera. */
    fun updateVisibleBounds(bounds: LatLngBounds?) {
        _uiState.update { state ->
            val editor = state.editorState
            val clamped = editor.copy(bounds = bounds, estimatedBytes = estimateBytes(bounds, editor.minZoom, editor.maxZoom))
            state.copy(editorState = clamped)
        }
    }

    /** Updates the minimum zoom slider value. */
    fun updateMinZoom(value: Float) {
        _uiState.update { state ->
            val minZoom = value.toDouble().coerceAtLeast(MIN_ZOOM)
            val maxZoom = state.editorState.maxZoom.coerceAtLeast(minZoom + 0.1)
            val updatedEditor = state.editorState.copy(
                minZoom = minZoom,
                maxZoom = maxZoom,
                estimatedBytes = estimateBytes(state.editorState.bounds, minZoom, maxZoom)
            )
            state.copy(editorState = updatedEditor)
        }
    }

    /** Updates the maximum zoom slider value. */
    fun updateMaxZoom(value: Float) {
        _uiState.update { state ->
            val maxZoom = value.toDouble().coerceAtMost(MAX_ZOOM)
            val minZoom = state.editorState.minZoom.coerceAtMost(maxZoom - 0.1)
            val updatedEditor = state.editorState.copy(
                minZoom = minZoom,
                maxZoom = maxZoom,
                estimatedBytes = estimateBytes(state.editorState.bounds, minZoom, maxZoom)
            )
            state.copy(editorState = updatedEditor)
        }
    }

    /** Updates the friendly name for the region to be downloaded. */
    fun updateSelectionName(name: String) {
        _uiState.update { it.copy(editorState = it.editorState.copy(name = name)) }
    }

    /** Updates the pixel ratio coming from the current device density. */
    fun updatePixelRatio(pixelRatio: Float) {
        _uiState.update { it.copy(editorState = it.editorState.copy(pixelRatio = pixelRatio)) }
    }

    /** Initiates a download, optionally forcing it past user warnings. */
    fun startOfflineDownload(force: Boolean = false) {
        val state = _uiState.value
        val bounds = state.editorState.bounds ?: run {
            postMessage(R.string.offline_map_missing_bounds)
            return
        }
        val name = state.editorState.name.ifBlank { defaultRegionName() }
        val params = StartDownloadUseCase.Params(
            name = name,
            bounds = bounds,
            minZoom = state.editorState.minZoom,
            maxZoom = state.editorState.maxZoom,
            pixelRatio = state.editorState.pixelRatio,
            force = force,
        )
        pendingStartParams = params
        viewModelScope.launch(exceptionHandler) {
            when (val result = startDownloadUseCase(params)) {
                is StartDownloadUseCase.Result.Success -> {
                    OfflineDownloadService.start(appContext)
                    _uiState.update {
                        it.copy(
                            isEditorVisible = false,
                            snackbarMessage = OfflineMapMessage(R.string.offline_map_download_started),
                            showWarningDialog = false,
                            pendingWarnings = emptyList()
                        )
                    }
                }

                is StartDownloadUseCase.Result.NeedsUserConfirmation -> {
                    _uiState.update {
                        it.copy(
                            pendingWarnings = result.warnings,
                            showWarningDialog = true
                        )
                    }
                }

                is StartDownloadUseCase.Result.Failure -> {
                    postMessage(
                        R.string.offline_map_download_failed,
                        listOf(result.throwable.message ?: appContext.getString(R.string.offline_map_unknown_error))
                    )
                }
            }
        }
    }

    /** Retries the last download request after the user acknowledged warnings. */
    fun confirmWarnings() {
        val params = pendingStartParams ?: return
        startOfflineDownload(force = true)
        _uiState.update { it.copy(showWarningDialog = false) }
    }

    /** Dismisses any warning dialog without retrying. */
    fun dismissWarnings() {
        pendingStartParams = null
        _uiState.update { it.copy(showWarningDialog = false, pendingWarnings = emptyList()) }
    }

    /** Pauses the specified region. */
    fun pauseRegion(regionId: Long) {
        viewModelScope.launch(exceptionHandler) { pauseDownloadUseCase(regionId) }
    }

    /** Resumes the specified region. */
    fun resumeRegion(regionId: Long) {
        viewModelScope.launch(exceptionHandler) { resumeDownloadUseCase(regionId) }
    }

    /** Deletes the specified region. */
    fun deleteRegion(regionId: Long) {
        viewModelScope.launch(exceptionHandler) {
            deleteRegionUseCase(regionId)
            postMessage(R.string.offline_map_region_deleted)
        }
    }

    /** Renames the provided region. */
    fun renameRegion(regionId: Long, newName: String) {
        viewModelScope.launch(exceptionHandler) {
            renameRegionUseCase(regionId, newName)
            postMessage(R.string.offline_map_region_renamed)
        }
    }

    private fun defaultRegionName(): String {
        val count = _uiState.value.regions.size + 1
        return appContext.getString(R.string.offline_map_default_region_name, count)
    }

    private fun estimateBytes(bounds: LatLngBounds?, minZoom: Double, maxZoom: Double): Long {
        if (bounds == null) return 0L
        val northEast = bounds.northEast
        val southWest = bounds.southWest
        val latSpan = abs(northEast.latitude - southWest.latitude)
        val lonSpan = abs(northEast.longitude - southWest.longitude)
        val areaFactor = max(latSpan * lonSpan, 0.05)
        val zoomFactor = max(maxZoom - minZoom, 1.0)
        val tileEstimate = areaFactor * zoomFactor * 1200
        return (tileEstimate * AVERAGE_TILE_SIZE_BYTES).toLong()
    }

    private fun postMessage(@StringRes message: Int, args: List<Any> = emptyList()) {
        _uiState.update { it.copy(snackbarMessage = OfflineMapMessage(message, args)) }
    }

    private fun OfflineRegionEntity.toUiModel(): OfflineRegionUiModel {
        val progress = if (bytesRequired <= 0L) 0f else (bytesDownloaded.toDouble() / bytesRequired.toDouble()).toFloat()
        val etaSeconds = if (bytesRequired > 0L && bytesDownloaded > 0L && status == OfflineRegionStatus.Downloading && !isPaused) {
            val remaining = (bytesRequired - bytesDownloaded).coerceAtLeast(0L)
            (remaining / ESTIMATED_THROUGHPUT_BYTES_PER_SECOND).coerceAtLeast(0L)
        } else {
            null
        }
        return OfflineRegionUiModel(
            localId = localId,
            regionId = regionId,
            name = name,
            bounds = LatLngBounds.from(boundsNorth, boundsEast, boundsSouth, boundsWest),
            status = status,
            bytesDownloaded = bytesDownloaded,
            bytesRequired = bytesRequired,
            isPaused = isPaused,
            createdAtMillis = createdAtEpochMillis,
            etaSeconds = etaSeconds,
            progress = progress.coerceIn(0f, 1f)
        )
    }

    companion object {
        private const val TAG = "OfflineMapVM"
        private const val MIN_ZOOM = 5.0
        private const val MAX_ZOOM = 18.0
        private const val AVERAGE_TILE_SIZE_BYTES = 50_000L
        private const val ESTIMATED_THROUGHPUT_BYTES_PER_SECOND = 250_000L

        /** Factory used to create [OfflineMapViewModel] instances without DI. */
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appContext = context.applicationContext
                val start = OfflineServiceLocator.provideStartDownloadUseCase(appContext)
                val pause = OfflineServiceLocator.providePauseUseCase(appContext)
                val resume = OfflineServiceLocator.provideResumeUseCase(appContext)
                val delete = OfflineServiceLocator.provideDeleteUseCase(appContext)
                val rename = OfflineServiceLocator.provideRenameUseCase(appContext)
                val list = OfflineServiceLocator.provideListRegionsUseCase(appContext)
                @Suppress("UNCHECKED_CAST")
                return OfflineMapViewModel(appContext, start, pause, resume, delete, rename, list) as T
            }
        }
    }
}

/** UI state consumed by the offline map screen. */
data class OfflineMapUiState(
    val regions: List<OfflineRegionUiModel> = emptyList(),
    val isStyleLoaded: Boolean = false,
    val isEditorVisible: Boolean = false,
    val isListVisible: Boolean = false,
    val editorState: OfflineRegionEditorState = OfflineRegionEditorState(),
    val snackbarMessage: OfflineMapMessage? = null,
    val pendingWarnings: List<StartDownloadUseCase.DownloadWarning> = emptyList(),
    val showWarningDialog: Boolean = false,
)

/** Captures the state of the editor sheet used to create offline regions. */
data class OfflineRegionEditorState(
    val name: String = "",
    val minZoom: Double = 10.0,
    val maxZoom: Double = 12.0,
    val bounds: LatLngBounds? = null,
    val pixelRatio: Float = 1f,
    val estimatedBytes: Long = 0L,
)

/** Message wrapper for snackbar interactions. */
data class OfflineMapMessage(
    @StringRes val messageRes: Int,
    val formatArgs: List<Any> = emptyList(),
)

/** Model consumed by the UI to render an offline region entry. */
data class OfflineRegionUiModel(
    val localId: Long,
    val regionId: Long?,
    val name: String,
    val bounds: LatLngBounds,
    val status: OfflineRegionStatus,
    val bytesDownloaded: Long,
    val bytesRequired: Long,
    val isPaused: Boolean,
    val createdAtMillis: Long,
    val etaSeconds: Long?,
    val progress: Float,
)
