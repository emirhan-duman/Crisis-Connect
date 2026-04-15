package com.auralis.crisisconnect.screens.Tools

import androidx.test.core.app.ApplicationProvider
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import com.auralis.crisisconnect.domain.offline.DeleteRegionUseCase
import com.auralis.crisisconnect.domain.offline.ListRegionsUseCase
import com.auralis.crisisconnect.domain.offline.PauseDownloadUseCase
import com.auralis.crisisconnect.domain.offline.RenameRegionUseCase
import com.auralis.crisisconnect.domain.offline.ResumeDownloadUseCase
import com.auralis.crisisconnect.domain.offline.StartDownloadUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLngBounds
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineMapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var startDownloadUseCase: StartDownloadUseCase
    private lateinit var pauseDownloadUseCase: PauseDownloadUseCase
    private lateinit var resumeDownloadUseCase: ResumeDownloadUseCase
    private lateinit var deleteRegionUseCase: DeleteRegionUseCase
    private lateinit var renameRegionUseCase: RenameRegionUseCase
    private lateinit var listRegionsUseCase: ListRegionsUseCase

    @Before
    fun setup() {
        startDownloadUseCase = mockk()
        pauseDownloadUseCase = mockk(relaxed = true)
        resumeDownloadUseCase = mockk(relaxed = true)
        deleteRegionUseCase = mockk(relaxed = true)
        renameRegionUseCase = mockk(relaxed = true)
        listRegionsUseCase = mockk()
        every { listRegionsUseCase.invoke() } returns MutableStateFlow(emptyList())
    }

    @Test
    fun startOfflineDownloadHidesEditorAndShowsSnackbar() = runTest(dispatcher) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val entity = OfflineRegionEntity(
            localId = 1L,
            regionId = 2L,
            name = "Region",
            boundsNorth = 1.0,
            boundsSouth = 0.0,
            boundsEast = 1.0,
            boundsWest = 0.0,
            minZoom = 5.0,
            maxZoom = 10.0,
            styleUrl = "style",
            createdAtEpochMillis = System.currentTimeMillis(),
            status = OfflineRegionStatus.Downloading
        )
        coEvery { startDownloadUseCase.invoke(any()) } returns StartDownloadUseCase.Result.Success(entity)

        val viewModel = OfflineMapViewModel(
            appContext = context,
            startDownloadUseCase = startDownloadUseCase,
            pauseDownloadUseCase = pauseDownloadUseCase,
            resumeDownloadUseCase = resumeDownloadUseCase,
            deleteRegionUseCase = deleteRegionUseCase,
            renameRegionUseCase = renameRegionUseCase,
            listRegionsUseCase = listRegionsUseCase
        )

        viewModel.updatePixelRatio(2f)
        viewModel.updateVisibleBounds(LatLngBounds.from(1.0, 1.0, 0.0, 0.0))
        viewModel.updateSelectionName("Test Region")
        viewModel.setEditorVisibility(true)

        viewModel.startOfflineDownload(force = false)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isEditorVisible)
        assertNotNull(state.snackbarMessage)
        assertEquals(R.string.offline_map_download_started, state.snackbarMessage?.messageRes)
    }
}
