package com.auralis.crisisconnect.domain.offline

import com.auralis.crisisconnect.data.offline.OfflineRegionRepository

/**
 * Permanently deletes an offline region and its associated tiles.
 */
class DeleteRegionUseCase(private val repository: OfflineRegionRepository) {

    /** Executes the deletion logic. */
    suspend operator fun invoke(regionId: Long) {
        repository.cancel(regionId)
    }
}
