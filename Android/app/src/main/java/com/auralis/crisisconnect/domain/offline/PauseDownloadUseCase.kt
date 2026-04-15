package com.auralis.crisisconnect.domain.offline

import com.auralis.crisisconnect.data.offline.OfflineRegionRepository

/**
 * Pauses an ongoing offline download if present.
 */
class PauseDownloadUseCase(private val repository: OfflineRegionRepository) {

    /** Invokes the repository pause action. */
    suspend operator fun invoke(regionId: Long) {
        repository.pause(regionId)
    }
}
