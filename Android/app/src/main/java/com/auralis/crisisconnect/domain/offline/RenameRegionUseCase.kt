package com.auralis.crisisconnect.domain.offline

import com.auralis.crisisconnect.data.offline.OfflineRegionRepository

/**
 * Renames an existing offline region.
 */
class RenameRegionUseCase(private val repository: OfflineRegionRepository) {

    /** Executes the rename operation. */
    suspend operator fun invoke(regionId: Long, newName: String) {
        repository.rename(regionId, newName)
    }
}
