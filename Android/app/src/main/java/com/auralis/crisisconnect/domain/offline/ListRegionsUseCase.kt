package com.auralis.crisisconnect.domain.offline

import com.auralis.crisisconnect.data.offline.OfflineRegionEntity
import com.auralis.crisisconnect.data.offline.OfflineRegionRepository
import kotlinx.coroutines.flow.Flow

/**
 * Exposes a flow of persisted offline regions for UI consumption.
 */
class ListRegionsUseCase(private val repository: OfflineRegionRepository) {

    /** Returns the underlying repository flow. */
    operator fun invoke(): Flow<List<OfflineRegionEntity>> = repository.regionsFlow
}
