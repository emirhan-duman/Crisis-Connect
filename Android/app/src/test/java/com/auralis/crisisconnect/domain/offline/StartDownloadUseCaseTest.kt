package com.auralis.crisisconnect.domain.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.PowerManager
import android.os.StatFs
import com.auralis.crisisconnect.data.offline.OfflineDownloadRequest
import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionRepository
import com.auralis.crisisconnect.data.offline.OfflineRegionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.maplibre.android.geometry.LatLngBounds

class StartDownloadUseCaseTest {

    private lateinit var repository: FakeRepository
    private lateinit var styleProvider: StyleUrlProvider
    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var networkCapabilities: NetworkCapabilities
    private lateinit var powerManager: PowerManager

    @Before
    fun setup() {
        repository = FakeRepository()
        styleProvider = object : StyleUrlProvider {
            override fun primary(): String = "style"
            override fun fallback(): String = "fallback"
        }
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        networkCapabilities = mockk(relaxed = true)
        powerManager = mockk(relaxed = true)
        every { context.getSystemService(ConnectivityManager::class.java) } returns connectivityManager
        every { context.getSystemService(PowerManager::class.java) } returns powerManager
        every { context.filesDir } returns java.io.File("/tmp")
        mockkConstructor(StatFs::class)
    }

    @Test
    fun returnsWarningWhenStorageLow() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns 1024
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { powerManager.isPowerSaveMode } returns false

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Test",
                bounds = LatLngBounds.from(10.0, 10.0, 9.5, 9.5),
                minZoom = 5.0,
                maxZoom = 10.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.NeedsUserConfirmation)
        val warnings = (result as StartDownloadUseCase.Result.NeedsUserConfirmation).warnings
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.LowStorage })
        assertEquals(0, repository.requests.size)
    }

    @Test
    fun successWhenForcedBypassesWarnings() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        every { connectivityManager.activeNetwork } returns mockk(relaxed = true)
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { powerManager.isPowerSaveMode } returns false

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Ok",
                bounds = LatLngBounds.from(5.0, 5.0, 4.9, 4.9),
                minZoom = 6.0,
                maxZoom = 8.0,
                pixelRatio = 2f,
                force = true
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.Success)
        assertEquals(1, repository.requests.size)
    }

    @Test
    fun returnsMeteredWarningWhenNetworkIsMetered() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns false
        every { powerManager.isPowerSaveMode } returns false
        every { connectivityManager.activeNetworkInfo } returns mockk<NetworkInfo> {
            every { isRoaming } returns false
        }

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Metered",
                bounds = LatLngBounds.from(8.0, 8.0, 7.5, 7.5),
                minZoom = 5.0,
                maxZoom = 9.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.NeedsUserConfirmation)
        val warnings = (result as StartDownloadUseCase.Result.NeedsUserConfirmation).warnings
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.MeteredNetwork })
        assertEquals(0, repository.requests.size)
    }

    @Test
    fun returnsPowerSaveWarningWhenDeviceInPowerSaveMode() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { powerManager.isPowerSaveMode } returns true
        every { connectivityManager.activeNetworkInfo } returns mockk<NetworkInfo> {
            every { isRoaming } returns false
        }

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "PowerSave",
                bounds = LatLngBounds.from(8.0, 8.0, 7.5, 7.5),
                minZoom = 5.0,
                maxZoom = 9.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.NeedsUserConfirmation)
        val warnings = (result as StartDownloadUseCase.Result.NeedsUserConfirmation).warnings
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.PowerSave })
        assertEquals(0, repository.requests.size)
    }

    @Test
    fun returnsRoamingWarningWhenActiveNetworkIsRoaming() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { powerManager.isPowerSaveMode } returns false
        every { connectivityManager.activeNetworkInfo } returns mockk<NetworkInfo> {
            every { isRoaming } returns true
        }

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Roaming",
                bounds = LatLngBounds.from(8.0, 8.0, 7.5, 7.5),
                minZoom = 5.0,
                maxZoom = 9.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.NeedsUserConfirmation)
        val warnings = (result as StartDownloadUseCase.Result.NeedsUserConfirmation).warnings
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.Roaming })
        assertEquals(0, repository.requests.size)
    }

    @Test
    fun returnsCombinedWarningsWhenMultipleRiskConditionsExist() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns 1024
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns false
        every { powerManager.isPowerSaveMode } returns true
        every { connectivityManager.activeNetworkInfo } returns mockk<NetworkInfo> {
            every { isRoaming } returns true
        }

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Combined",
                bounds = LatLngBounds.from(10.0, 10.0, 9.5, 9.5),
                minZoom = 5.0,
                maxZoom = 10.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.NeedsUserConfirmation)
        val warnings = (result as StartDownloadUseCase.Result.NeedsUserConfirmation).warnings
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.LowStorage })
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.MeteredNetwork })
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.Roaming })
        assertTrue(warnings.any { it is StartDownloadUseCase.DownloadWarning.PowerSave })
        assertEquals(0, repository.requests.size)
    }

    @Test
    fun returnsFailureWhenRepositoryThrows() = runTest {
        every { anyConstructed<StatFs>().availableBytes } returns Long.MAX_VALUE
        every { connectivityManager.activeNetwork } returns mockk<Network>()
        every { connectivityManager.getNetworkCapabilities(any()) } returns networkCapabilities
        every { networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        every { powerManager.isPowerSaveMode } returns false
        every { connectivityManager.activeNetworkInfo } returns mockk<NetworkInfo> {
            every { isRoaming } returns false
        }
        repository.throwOnStart = IllegalStateException("boom")

        val useCase = StartDownloadUseCase(context, repository, styleProvider)
        val result = useCase(
            StartDownloadUseCase.Params(
                name = "Failure",
                bounds = LatLngBounds.from(5.0, 5.0, 4.9, 4.9),
                minZoom = 6.0,
                maxZoom = 8.0,
                pixelRatio = 2f
            )
        )

        assertTrue(result is StartDownloadUseCase.Result.Failure)
        assertEquals(0, repository.requests.size)
    }

    private class FakeRepository : OfflineRegionRepository {
        val requests = mutableListOf<OfflineDownloadRequest>()
        var throwOnStart: Throwable? = null
        override val regionsFlow = kotlinx.coroutines.flow.emptyFlow<List<OfflineRegionEntity>>()
        override suspend fun startDownload(request: OfflineDownloadRequest): OfflineRegionEntity {
            throwOnStart?.let { throw it }
            requests += request
            return OfflineRegionEntity(
                name = request.name,
                boundsNorth = request.bounds.latitudeNorth,
                boundsSouth = request.bounds.latitudeSouth,
                boundsEast = request.bounds.longitudeEast,
                boundsWest = request.bounds.longitudeWest,
                minZoom = request.minZoom,
                maxZoom = request.maxZoom,
                styleUrl = request.styleUrl,
                createdAtEpochMillis = System.currentTimeMillis(),
                status = OfflineRegionStatus.Downloading
            )
        }

        override suspend fun pause(regionId: Long) = Unit
        override suspend fun resume(regionId: Long) = Unit
        override suspend fun cancel(regionId: Long) = Unit
        override suspend fun rename(regionId: Long, newName: String) = Unit
        override suspend fun refresh() = Unit
    }
}
