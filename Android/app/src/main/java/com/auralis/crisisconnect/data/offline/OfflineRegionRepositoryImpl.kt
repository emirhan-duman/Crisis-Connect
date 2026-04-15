package com.auralis.crisisconnect.data.offline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import org.maplibre.android.offline.OfflineRegionStatus as MapLibreRegionStatus
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Default implementation of [OfflineRegionRepository] backed by MapLibre's [OfflineManager].
 */
class OfflineRegionRepositoryImpl internal constructor(
    private val database: OfflineDatabase,
    private val offlineManager: OfflineManager,
    private val externalScope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OfflineRegionRepository {

    private val dao = database.offlineRegionDao()

    override val regionsFlow = dao.observeRegions()

    override suspend fun startDownload(request: OfflineDownloadRequest): OfflineRegionEntity =
        withContext(dispatcher) {
            val now = System.currentTimeMillis()
            val entity = OfflineRegionEntity(
                name = request.name,
                boundsNorth = request.bounds.latitudeNorth,
                boundsSouth = request.bounds.latitudeSouth,
                boundsEast = request.bounds.longitudeEast,
                boundsWest = request.bounds.longitudeWest,
                minZoom = request.minZoom,
                maxZoom = request.maxZoom,
                styleUrl = request.styleUrl,
                createdAtEpochMillis = now,
                status = OfflineRegionStatus.Downloading,
                isPaused = false,
            )
            val localId = dao.upsert(entity)
            val stored = entity.copy(localId = localId)
            val definition = OfflineTilePyramidRegionDefinition(
                request.styleUrl,
                request.bounds,
                request.minZoom,
                request.maxZoom,
                request.pixelRatio
            )
            val metadata = JSONObject().apply {
                put(KEY_METADATA_NAME, request.name)
                put(KEY_METADATA_LOCAL_ID, localId)
                put(KEY_METADATA_CREATED_AT, now)
            }.toString().toByteArray()

            val offlineRegion = createOfflineRegion(definition, metadata)
            val tracked = stored.copy(regionId = offlineRegion.id)
            dao.update(tracked)
            observeRegion(offlineRegion, tracked)
            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            tracked
        }

    override suspend fun pause(regionId: Long) {
        withRegion(regionId) { offlineRegion, entity ->
            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
            dao.update(entity.copy(isPaused = true, status = OfflineRegionStatus.Idle))
        }
    }

    override suspend fun resume(regionId: Long) {
        withRegion(regionId) { offlineRegion, entity ->
            offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            dao.update(entity.copy(isPaused = false, status = OfflineRegionStatus.Downloading))
        }
    }

    override suspend fun cancel(regionId: Long) {
        withRegion(regionId) { offlineRegion, entity ->
            suspendCancellableCoroutine { cont ->
                offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() {
                        externalScope.launch {
                            dao.deleteByRegionId(regionId)
                        }
                        cont.resume(Unit)
                    }

                    override fun onError(error: String) {
                        cont.resumeWithException(IllegalStateException(error))
                    }
                })
            }
            dao.update(entity.copy(status = OfflineRegionStatus.Deleted))
        }
    }

    override suspend fun rename(regionId: Long, newName: String) {
        val entity = dao.getByRegionId(regionId) ?: return
        dao.update(entity.copy(name = newName))
    }

    override suspend fun refresh() {
        withContext(dispatcher) {
            val regions = listOfflineRegions()
            regions.forEach { offlineRegion ->
                val metadata = runCatching {
                    JSONObject(String(offlineRegion.metadata ?: ByteArray(0)))
                }.getOrNull()
                val localId = metadata?.optLong(KEY_METADATA_LOCAL_ID)?.takeIf { it > 0 }
                val existing = when {
                    localId != null -> dao.getByLocalId(localId)
                    else -> dao.getByRegionId(offlineRegion.id)
                }
                val definition = offlineRegion.definition as? OfflineTilePyramidRegionDefinition
                val base = (existing ?: OfflineRegionEntity(
                    localId = localId ?: 0L,
                    regionId = offlineRegion.id,
                    name = metadata?.optString(KEY_METADATA_NAME)?.takeIf { it.isNotBlank() }
                        ?: "Region ${offlineRegion.id}",
                    boundsNorth = definition?.bounds?.latitudeNorth ?: 0.0,
                    boundsSouth = definition?.bounds?.latitudeSouth ?: 0.0,
                    boundsEast = definition?.bounds?.longitudeEast ?: 0.0,
                    boundsWest = definition?.bounds?.longitudeWest ?: 0.0,
                    minZoom = definition?.minZoom?.toDouble() ?: 0.0,
                    maxZoom = definition?.maxZoom?.toDouble() ?: 0.0,
                    styleUrl = definition?.styleURL ?: "",
                    createdAtEpochMillis = metadata?.optLong(KEY_METADATA_CREATED_AT)
                        ?: System.currentTimeMillis(),
                ))
                dao.upsert(base.copy(regionId = offlineRegion.id))
                observeRegion(offlineRegion, base.copy(regionId = offlineRegion.id))
            }
        }
    }

    private suspend fun createOfflineRegion(
        definition: OfflineTilePyramidRegionDefinition,
        metadata: ByteArray,
    ): OfflineRegion = suspendCancellableCoroutine { cont ->
        offlineManager.createOfflineRegion(definition, metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    cont.resume(offlineRegion)
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            })
    }

    private suspend fun withRegion(
        regionId: Long,
        block: suspend (OfflineRegion, OfflineRegionEntity) -> Unit,
    ) {
        val entity = dao.getByRegionId(regionId) ?: return
        val offlineRegion = findOfflineRegion(regionId) ?: return
        withContext(dispatcher) { block(offlineRegion, entity) }
    }

    private suspend fun findOfflineRegion(regionId: Long): OfflineRegion? =
        suspendCancellableCoroutine { cont ->
            offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    cont.resume(offlineRegions?.firstOrNull { it.id == regionId })
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            })
        }

    private suspend fun listOfflineRegions(): Array<out OfflineRegion> =
        suspendCancellableCoroutine { cont ->
            offlineManager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    cont.resume(offlineRegions ?: emptyArray())
                }

                override fun onError(error: String) {
                    cont.resumeWithException(IllegalStateException(error))
                }
            })
        }

    private fun observeRegion(offlineRegion: OfflineRegion, template: OfflineRegionEntity) {
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: MapLibreRegionStatus) {
                externalScope.launch {
                    val latest = template.regionId?.let { dao.getByRegionId(it) } ?: template
                    val averageSize = if (status.completedResourceCount > 0) {
                        status.completedResourceSize.toDouble() / status.completedResourceCount.toDouble()
                    } else {
                        0.0
                    }
                    val isComplete = status.isComplete ||
                        (status.requiredResourceCount > 0 &&
                            status.completedResourceCount >= status.requiredResourceCount)
                    val estimatedRequired = when {
                        isComplete -> status.completedResourceSize
                        averageSize > 0 -> (averageSize * status.requiredResourceCount).toLong()
                        latest.bytesRequired > 0 -> latest.bytesRequired
                        else -> status.requiredResourceCount.toLong()
                    }
                    val updated = latest.copy(
                        bytesDownloaded = status.completedResourceSize,
                        bytesRequired = estimatedRequired,
                        status = when {
                            isComplete -> OfflineRegionStatus.Complete
                            latest.isPaused -> OfflineRegionStatus.Idle
                            else -> OfflineRegionStatus.Downloading
                        },
                    )
                    dao.update(updated)
                }
            }

            override fun onError(error: OfflineRegionError) {
                externalScope.launch {
                    val latest = template.regionId?.let { dao.getByRegionId(it) } ?: template
                    dao.update(latest.copy(status = OfflineRegionStatus.Failed))
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                externalScope.launch {
                    val latest = template.regionId?.let { dao.getByRegionId(it) } ?: template
                    dao.update(latest.copy(status = OfflineRegionStatus.Failed, bytesRequired = limit))
                }
            }
        })
    }

    companion object {
        private const val KEY_METADATA_NAME = "name"
        private const val KEY_METADATA_LOCAL_ID = "local_id"
        private const val KEY_METADATA_CREATED_AT = "created_at"
    }
}
