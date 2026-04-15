package com.auralis.crisisconnect.domain.offline

import com.auralis.crisisconnect.data.offline.OfflineRegionRepository

/**
 * Resumes a paused offline download.
 */
class ResumeDownloadUseCase(private val repository: OfflineRegionRepository) {

    /** Invokes the repository resume action. */
    suspend operator fun invoke(regionId: Long) {
        repository.resume(regionId)
    }
}
