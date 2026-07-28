package com.auralis.crisisconnect.data.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.offline.OfflineManager
import com.auralis.crisisconnect.util.initializeMapLibreSafely
import com.auralis.crisisconnect.domain.offline.DeleteRegionUseCase
import com.auralis.crisisconnect.domain.offline.ListRegionsUseCase
import com.auralis.crisisconnect.domain.offline.PauseDownloadUseCase
import com.auralis.crisisconnect.domain.offline.ResumeDownloadUseCase
import com.auralis.crisisconnect.domain.offline.StartDownloadUseCase
import com.auralis.crisisconnect.domain.offline.StyleUrlProvider
import com.auralis.crisisconnect.domain.offline.RenameRegionUseCase

/**
 * Lightweight service locator for offline map dependencies.
 */
object OfflineServiceLocator {

    private val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private var database: OfflineDatabase? = null
    private var repository: OfflineRegionRepository? = null
    private var styleUrlProvider: StyleUrlProvider? = null

    /** Provides a singleton [OfflineRegionRepository] instance. */
    fun provideRepository(context: Context): OfflineRegionRepository {
        return repository ?: synchronized(this) {
            repository ?: buildRepository(context.applicationContext).also { repository = it }
        }
    }

    /** Provides the configured [StyleUrlProvider]. */
    fun provideStyleUrlProvider(context: Context): StyleUrlProvider {
        return styleUrlProvider ?: synchronized(this) {
            styleUrlProvider ?: DefaultStyleUrlProvider(context.applicationContext).also {
                styleUrlProvider = it
            }
        }
    }

    /** Provides a [StartDownloadUseCase] instance. */
    fun provideStartDownloadUseCase(context: Context): StartDownloadUseCase {
        val repo = provideRepository(context)
        val provider = provideStyleUrlProvider(context)
        return StartDownloadUseCase(context.applicationContext, repo, provider)
    }

    /** Provides a [PauseDownloadUseCase] instance. */
    fun providePauseUseCase(context: Context) =
        PauseDownloadUseCase(provideRepository(context))

    /** Provides a [ResumeDownloadUseCase] instance. */
    fun provideResumeUseCase(context: Context) =
        ResumeDownloadUseCase(provideRepository(context))

    /** Provides a [DeleteRegionUseCase] instance. */
    fun provideDeleteUseCase(context: Context) =
        DeleteRegionUseCase(provideRepository(context))

    /** Provides a [RenameRegionUseCase] instance. */
    fun provideRenameUseCase(context: Context) =
        RenameRegionUseCase(provideRepository(context))

    /** Provides a [ListRegionsUseCase] instance. */
    fun provideListRegionsUseCase(context: Context) =
        ListRegionsUseCase(provideRepository(context))

    private fun buildRepository(context: Context): OfflineRegionRepository {
        val db = database ?: OfflineDatabase.build(context).also { database = it }
        // MapLibre MUST be initialized before touching OfflineManager, or it throws a
        // MapLibreConfigurationException. This factory runs during composition BEFORE the screen's
        // MapView initializes MapLibre, so if the offline-map tool is the first map opened in the
        // process (common on a fresh launch), OfflineManager.getInstance would crash. Initialize
        // here first (idempotent). If the device can't run MapLibre at all (no GL ES 3.0), surface a
        // clear, catchable failure instead of the opaque config exception.
        if (!initializeMapLibreSafely(context, "OfflineServiceLocator")) {
            throw OfflineMapUnavailableException()
        }
        val manager = OfflineManager.getInstance(context)
        return OfflineRegionRepositoryImpl(db, manager, applicationScope).also {
            applicationScope.launch { it.refresh() }
        }
    }
}

/** Thrown when the offline-map runtime (MapLibre / GL ES 3.0) is unavailable on this device. */
class OfflineMapUnavailableException : IllegalStateException("Offline maps are not available on this device.")
