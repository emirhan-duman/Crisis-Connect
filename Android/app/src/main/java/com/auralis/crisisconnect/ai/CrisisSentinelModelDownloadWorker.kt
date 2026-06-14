package com.auralis.crisisconnect.ai

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class CrisisSentinelModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val release = releaseFromInput() ?: return@withContext Result.failure()
        val storageLocation = CrisisSentinelModelDownloadUrlPolicy.storageLocation(release)
        if (storageLocation == null) {
            return@withContext Result.failure()
        }

        val store = CrisisSentinelModelFileStore(applicationContext)
        val storageStatus = store.status(release = release, verifyChecksum = false)
        if (storageStatus.isReady) {
            val verifiedStatus = store.status(release = release, verifyChecksum = true)
            if (verifiedStatus.isReady) {
                return@withContext Result.success(
                    Data.Builder()
                        .putString(KEY_MODEL_PATH, verifiedStatus.file.absolutePath)
                        .putLong(KEY_BYTES_TOTAL, verifiedStatus.bytes)
                        .build()
                )
            }
            verifiedStatus.file.takeIf { it.exists() }?.delete()
        }
        if (storageStatus.availability == CrisisSentinelModelAvailability.InsufficientStorage) {
            return@withContext Result.failure(
                Data.Builder()
                    .putString(
                        KEY_FAILURE_REASON,
                        storageStatus.reason ?: "Insufficient storage for model download"
                    )
                    .build()
            )
        }
        val tempFile = store.tempFile(release)
        tempFile.parentFile?.mkdirs()
        val notification = CrisisSentinelModelDownloadNotification(applicationContext)
        setForeground(
            notification.foregroundInfo(
                workerId = id,
                release = release,
                bytesDownloaded = resumableBytes(tempFile, release.expectedBytes),
                bytesTotal = release.expectedBytes,
                phase = CrisisSentinelDownloadNotificationPhase.Preparing
            )
        )

        try {
            val access = CrisisSentinelModelDownloadAccessClient().fetchDownloadUrl(release)
            val downloadedBytes = downloadSignedUrl(
                downloadUrl = access.downloadUrl,
                destination = tempFile,
                expectedBytes = release.expectedBytes,
                release = release,
                notification = notification
            )
            setForeground(
                notification.foregroundInfo(
                    workerId = id,
                    release = release,
                    bytesDownloaded = downloadedBytes,
                    bytesTotal = release.expectedBytes,
                    phase = CrisisSentinelDownloadNotificationPhase.Verifying
                )
            )

            val committed = store.commitDownloadedModel(tempFile, release)
            if (committed.isReady) {
                Result.success(
                    Data.Builder()
                        .putString(KEY_MODEL_PATH, committed.file.absolutePath)
                        .putLong(KEY_BYTES_TOTAL, committed.bytes)
                        .build()
                )
            } else {
                tempFile.delete()
                committed.file.takeIf { it.exists() }?.delete()
                Result.failure(
                    Data.Builder()
                        .putString(KEY_FAILURE_REASON, committed.reason ?: "Model validation failed")
                        .build()
                )
            }
        } catch (cancellation: CancellationException) {
            tempFile.delete()
            throw cancellation
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Throwable) {
            tempFile.delete()
            Result.retry()
        }
    }

    private suspend fun downloadSignedUrl(
        downloadUrl: String,
        destination: File,
        expectedBytes: Long?,
        release: CrisisSentinelModelRelease,
        notification: CrisisSentinelModelDownloadNotification
    ): Long {
        var resumeBytes = resumableBytes(destination, expectedBytes)
        val connection = openDownloadConnection(downloadUrl, resumeBytes)
        try {
            val responseCode = connection.responseCode
            if (resumeBytes > 0L && responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                if (expectedBytes != null && resumeBytes == expectedBytes) {
                    publishProgress(resumeBytes, expectedBytes, release, notification)
                    return resumeBytes
                }
                destination.delete()
                resumeBytes = 0L
                return downloadSignedUrl(
                    downloadUrl = downloadUrl,
                    destination = destination,
                    expectedBytes = expectedBytes,
                    release = release,
                    notification = notification
                )
            }
            if (responseCode !in 200..299) {
                throw IOException("Signed model download failed with HTTP $responseCode.")
            }
            val shouldAppend = resumeBytes > 0L && responseCode == HTTP_PARTIAL_CONTENT
            if (resumeBytes > 0L && !shouldAppend) {
                destination.delete()
                resumeBytes = 0L
            }

            val totalBytes = expectedBytes
                ?: contentRangeTotal(connection.getHeaderField(HEADER_CONTENT_RANGE))
                ?: connection.contentLengthLong.takeIf { it > 0L }?.let { length ->
                    if (shouldAppend) resumeBytes + length else length
                }
            var downloadedBytes = resumeBytes
            var lastProgressBytes = resumeBytes
            var lastProgressAtMillis = 0L
            val progressByteInterval = progressByteInterval(totalBytes)
            publishProgress(downloadedBytes, totalBytes, release, notification)
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(destination, shouldAppend).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        throwIfStopped()
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val now = SystemClock.elapsedRealtime()
                        if (shouldPublishProgress(
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                lastProgressBytes = lastProgressBytes,
                                lastProgressAtMillis = lastProgressAtMillis,
                                nowMillis = now,
                                progressByteInterval = progressByteInterval
                            )
                        ) {
                            publishProgress(downloadedBytes, totalBytes, release, notification)
                            lastProgressBytes = downloadedBytes
                            lastProgressAtMillis = now
                        }
                    }
                }
            }
            if (expectedBytes != null && downloadedBytes != expectedBytes) {
                throw IOException(
                    "Incomplete model download: expected $expectedBytes bytes, received $downloadedBytes."
                )
            }
            publishProgress(downloadedBytes, totalBytes, release, notification)
            return downloadedBytes
        } finally {
            connection.disconnect()
        }
    }

    private fun openDownloadConnection(downloadUrl: String, resumeBytes: Long): HttpURLConnection {
        return (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            if (resumeBytes > 0L) {
                setRequestProperty(HEADER_RANGE, "bytes=$resumeBytes-")
            }
        }
    }

    private suspend fun publishProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
        release: CrisisSentinelModelRelease,
        notification: CrisisSentinelModelDownloadNotification
    ) {
        throwIfStopped()
        val progressData = Data.Builder()
            .putLong(KEY_BYTES_DOWNLOADED, downloadedBytes)
            .putLong(KEY_BYTES_TOTAL, totalBytes ?: -1L)
            .build()
        setProgress(progressData)
        setForeground(
            notification.foregroundInfo(
                workerId = id,
                release = release,
                bytesDownloaded = downloadedBytes,
                bytesTotal = totalBytes,
                phase = CrisisSentinelDownloadNotificationPhase.Downloading
            )
        )
    }

    private fun throwIfStopped() {
        if (isStopped) {
            throw CancellationException("Crisis Sentinel model download was stopped.")
        }
    }

    private fun shouldPublishProgress(
        downloadedBytes: Long,
        totalBytes: Long?,
        lastProgressBytes: Long,
        lastProgressAtMillis: Long,
        nowMillis: Long,
        progressByteInterval: Long
    ): Boolean {
        if (downloadedBytes <= 0L) return false
        if (totalBytes != null && downloadedBytes >= totalBytes) return true
        return downloadedBytes - lastProgressBytes >= progressByteInterval ||
            nowMillis - lastProgressAtMillis >= MIN_PROGRESS_INTERVAL_MS
    }

    private fun progressByteInterval(totalBytes: Long?): Long {
        val percentageInterval = totalBytes?.let { it / MAX_PROGRESS_UPDATES } ?: 0L
        return maxOf(MIN_PROGRESS_BYTES, percentageInterval)
    }

    private fun resumableBytes(destination: File, expectedBytes: Long?): Long {
        if (!destination.exists()) return 0L
        val length = destination.length()
        if (length <= 0L || (expectedBytes != null && length > expectedBytes)) {
            destination.delete()
            return 0L
        }
        return length
    }

    private fun contentRangeTotal(header: String?): Long? {
        return header
            ?.substringAfter('/', missingDelimiterValue = "")
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
    }

    private fun releaseFromInput(): CrisisSentinelModelRelease? {
        val id = inputData.getString(KEY_RELEASE_ID)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val displayName = inputData.getString(KEY_DISPLAY_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: id
        val fileName = inputData.getString(KEY_FILE_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)?.trim()?.takeIf { it.isNotEmpty() }
        val storageBucket = inputData.getString(KEY_STORAGE_BUCKET)?.trim()?.takeIf { it.isNotEmpty() }
        val storagePath = inputData.getString(KEY_STORAGE_PATH)?.trim()?.takeIf { it.isNotEmpty() }
        val sha256 = inputData.getString(KEY_SHA256)?.trim()?.takeIf { it.isNotEmpty() }
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, -1L).takeIf { it > 0L }
        val minFreeBytes = inputData.getLong(KEY_MIN_FREE_BYTES, CrisisSentinelModelFileStore.defaultRelease.minFreeBytes)
        return runCatching {
            CrisisSentinelModelRelease(
                id = id,
                displayName = displayName,
                fileName = fileName,
                downloadUrl = downloadUrl,
                storageBucket = storageBucket,
                storagePath = storagePath,
                expectedSha256 = sha256,
                expectedBytes = expectedBytes,
                minFreeBytes = minFreeBytes
            )
        }.getOrNull()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "CrisisSentinelModelDownload"
        const val KEY_RELEASE_ID = "release_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_STORAGE_BUCKET = "storage_bucket"
        const val KEY_STORAGE_PATH = "storage_path"
        const val KEY_SHA256 = "sha256"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val KEY_MIN_FREE_BYTES = "min_free_bytes"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_BYTES_TOTAL = "bytes_total"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_FAILURE_REASON = "failure_reason"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val MIN_PROGRESS_BYTES = 4L * 1024L * 1024L
        private const val MIN_PROGRESS_INTERVAL_MS = 500L
        private const val MAX_PROGRESS_UPDATES = 200L
        private const val HEADER_RANGE = "Range"
        private const val HEADER_CONTENT_RANGE = "Content-Range"
        private const val HTTP_PARTIAL_CONTENT = 206
        private const val HTTP_RANGE_NOT_SATISFIABLE = 416

        fun enqueue(context: Context, release: CrisisSentinelModelRelease): Boolean {
            if (CrisisSentinelModelDownloadUrlPolicy.storageLocation(release) == null) {
                return false
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<CrisisSentinelModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(release.toInputData())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            return true
        }

        private fun CrisisSentinelModelRelease.toInputData(): Data {
            val builder = Data.Builder()
                .putString(KEY_RELEASE_ID, id)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_FILE_NAME, fileName)
                .putLong(KEY_MIN_FREE_BYTES, minFreeBytes)
            downloadUrl?.let { builder.putString(KEY_DOWNLOAD_URL, it) }
            storageBucket?.let { builder.putString(KEY_STORAGE_BUCKET, it) }
            storagePath?.let { builder.putString(KEY_STORAGE_PATH, it) }
            expectedSha256?.let { builder.putString(KEY_SHA256, it) }
            expectedBytes?.let { builder.putLong(KEY_EXPECTED_BYTES, it) }
            return builder.build()
        }
    }
}
