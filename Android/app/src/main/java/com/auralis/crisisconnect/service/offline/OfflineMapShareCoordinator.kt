package com.auralis.crisisconnect.service.offline

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.storage.FileSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

/**
 * Builds and imports compact offline map share bundles over chat transport.
 */
object OfflineMapShareCoordinator {

    private const val TAG = "OfflineMapShare"
    private const val OFFLINE_DB_NAME = "mbgl-offline.db"
    private const val OFFLINE_DB_WAL_NAME = "mbgl-offline.db-wal"
    private const val OFFLINE_DB_SHM_NAME = "mbgl-offline.db-shm"
    private const val SHARE_TEMP_DIR = "offline_map_share"
    private const val MAX_SHARE_BYTES = 10 * 1024 * 1024

    suspend fun buildSharePayloadForLocation(
        context: Context,
        latitude: Double,
        longitude: Double,
        maxBytes: Int = MAX_SHARE_BYTES
    ): ByteArray? {
        if (!hasRegionCoveringLocation(context, latitude, longitude)) {
            return null
        }

        // Try compacting before copying to avoid sending stale pages.
        runCatching { packDatabase(context) }

        val cacheRoot = File(FileSource.getResourcesCachePath(context))
        val baseDb = File(cacheRoot, OFFLINE_DB_NAME)
        if (!baseDb.exists() || !baseDb.isFile || baseDb.length() <= 0L) {
            return null
        }

        val filesToZip = buildList {
            add(baseDb)
            val wal = File(cacheRoot, OFFLINE_DB_WAL_NAME)
            if (wal.exists() && wal.isFile && wal.length() > 0L) {
                add(wal)
            }
            val shm = File(cacheRoot, OFFLINE_DB_SHM_NAME)
            if (shm.exists() && shm.isFile && shm.length() > 0L) {
                add(shm)
            }
        }

        val archiveBytes = withContext(Dispatchers.IO) {
            ByteArrayOutputStream().use { byteStream ->
                ZipOutputStream(byteStream).use { zip ->
                    filesToZip.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.name))
                        FileInputStream(file).use { input ->
                            input.copyTo(zip, DEFAULT_BUFFER_SIZE)
                        }
                        zip.closeEntry()
                    }
                }
                byteStream.toByteArray()
            }
        }
        if (archiveBytes.isEmpty() || archiveBytes.size > maxBytes) {
            return null
        }
        return archiveBytes
    }

    suspend fun importShareArchive(
        context: Context,
        archiveFile: File
    ): Boolean {
        if (!archiveFile.exists() || !archiveFile.isFile || archiveFile.length() <= 0L) {
            return false
        }

        val importDir = File(
            File(context.cacheDir, SHARE_TEMP_DIR),
            "inbox_${UUID.randomUUID()}"
        )
        importDir.mkdirs()

        return try {
            withContext(Dispatchers.IO) {
                unzipArchiveSafely(archiveFile, importDir)
            }
            val dbFile = resolveExtractedDatabase(importDir) ?: return false
            val merged = mergeOfflineDatabase(context, dbFile.absolutePath)
            if (merged) {
                runCatching { packDatabase(context) }
            }
            merged
        } catch (throwable: Throwable) {
            Log.w(TAG, "Failed to import offline map share archive", throwable)
            false
        } finally {
            runCatching { importDir.deleteRecursively() }
        }
    }

    private suspend fun hasRegionCoveringLocation(
        context: Context,
        latitude: Double,
        longitude: Double
    ): Boolean {
        val point = LatLng(latitude, longitude)
        val regions = listOfflineRegions(context)
        if (regions.isEmpty()) {
            return false
        }
        return regions.any { region ->
            val bounds = region.definition?.bounds
            bounds != null && point in bounds
        }
    }

    private suspend fun listOfflineRegions(context: Context): Array<OfflineRegion> =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                OfflineManager.getInstance(context)
                    .listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                        override fun onList(offlineRegions: Array<OfflineRegion>?) {
                            continuation.resume(offlineRegions ?: emptyArray())
                        }

                        override fun onError(error: String) {
                            Log.w(TAG, "Failed to list offline regions: $error")
                            continuation.resume(emptyArray())
                        }
                    })
            }
        }

    private suspend fun mergeOfflineDatabase(context: Context, path: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                OfflineManager.getInstance(context)
                    .mergeOfflineRegions(path, object : OfflineManager.MergeOfflineRegionsCallback {
                        override fun onMerge(offlineRegions: Array<OfflineRegion>?) {
                            continuation.resume(true)
                        }

                        override fun onError(error: String) {
                            Log.w(TAG, "Offline map merge failed: $error")
                            continuation.resume(false)
                        }
                    })
            }
        }

    private suspend fun packDatabase(context: Context): Boolean =
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                OfflineManager.getInstance(context)
                    .packDatabase(object : OfflineManager.FileSourceCallback {
                        override fun onSuccess() {
                            continuation.resume(true)
                        }

                        override fun onError(message: String) {
                            Log.w(TAG, "packDatabase failed: $message")
                            continuation.resume(false)
                        }
                    })
            }
        }

    private fun unzipArchiveSafely(source: File, targetDir: File) {
        ZipInputStream(FileInputStream(source)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                val rawName = entry.name.orEmpty()
                val safeName = rawName.substringAfterLast('/').trim()
                if (safeName.isNotEmpty() && !entry.isDirectory) {
                    val outFile = File(targetDir, safeName)
                    FileOutputStream(outFile).use { output ->
                        zipInput.copyTo(output, DEFAULT_BUFFER_SIZE)
                    }
                }
                zipInput.closeEntry()
                entry = zipInput.nextEntry
            }
        }
    }

    private fun resolveExtractedDatabase(importDir: File): File? {
        val exact = File(importDir, OFFLINE_DB_NAME)
        if (exact.exists() && exact.isFile && exact.length() > 0L) {
            return exact
        }
        return importDir.listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                    file.name.lowercase(Locale.ROOT).endsWith(".db") &&
                    file.length() > 0L
            }
    }
}
