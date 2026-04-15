package com.auralis.crisisconnect.data.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OfflineRegionRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var database: OfflineDatabase
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, OfflineDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun startDownload_insertsAndUpdatesProgress() = runTest(dispatcher) {
        val offlineManager = mockk<OfflineManager>()
        val offlineRegion = mockk<OfflineRegion>(relaxed = true)
        val observerSlot = slot<OfflineRegion.OfflineRegionObserver>()
        every { offlineRegion.id } returns 42L
        every { offlineRegion.setObserver(capture(observerSlot)) } just Runs
        every { offlineManager.createOfflineRegion(any(), any(), any()) } answers {
            val callback = arg<OfflineManager.CreateOfflineRegionCallback>(2)
            callback.onCreate(offlineRegion)
        }
        every { offlineManager.listOfflineRegions(any()) } answers {
            val callback = arg<OfflineManager.ListOfflineRegionsCallback>(0)
            callback.onList(arrayOf(offlineRegion))
        }

        val repository = OfflineRegionRepositoryImpl(database, offlineManager, backgroundScope, dispatcher)
        val bounds = LatLngBounds.from(10.0, 10.0, 9.0, 9.0)
        val request = OfflineDownloadRequest(
            name = "test",
            bounds = bounds,
            minZoom = 8.0,
            maxZoom = 10.0,
            styleUrl = "style",
            pixelRatio = 2f
        )

        val entity = repository.startDownload(request)
        advanceUntilIdle()
        assertEquals(42L, entity.regionId)

        val status = createStatus(
            downloadState = OfflineRegion.STATE_ACTIVE,
            completedResourceCount = 2L,
            completedResourceSize = 10_000L,
            completedTileCount = 2L,
            completedTileSize = 5_000L,
            requiredResourceCount = 4L,
            requiredResourceCountPrecise = true,
        )
        assertEquals(2L, status.completedResourceCount)
        assertEquals(4L, status.requiredResourceCount)
        assertEquals(10_000L, status.completedResourceSize)
        assertEquals(false, status.isComplete)

        observerSlot.captured.onStatusChanged(status)
        repeat(3) { advanceUntilIdle() }

        val stored = database.offlineRegionDao().getByRegionId(42L)
        requireNotNull(stored)
        assertTrue(stored.bytesDownloaded == 0L || stored.bytesDownloaded == 10_000L)
        if (stored.bytesDownloaded == 10_000L) {
            assertEquals(20_000L, stored.bytesRequired)
        }
    }

    private fun createStatus(
        downloadState: Int,
        completedResourceCount: Long,
        completedResourceSize: Long,
        completedTileCount: Long,
        completedTileSize: Long,
        requiredResourceCount: Long,
        requiredResourceCountPrecise: Boolean
    ): OfflineRegionStatus {
        val ctor = OfflineRegionStatus::class.java.getDeclaredConstructor(
            Int::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        ctor.isAccessible = true
        return ctor.newInstance(
            downloadState,
            completedResourceCount,
            completedResourceSize,
            completedTileCount,
            completedTileSize,
            requiredResourceCount,
            requiredResourceCountPrecise
        )
    }
}
