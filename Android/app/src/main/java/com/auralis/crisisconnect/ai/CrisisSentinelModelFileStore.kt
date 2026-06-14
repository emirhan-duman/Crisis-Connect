package com.auralis.crisisconnect.ai

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

data class CrisisSentinelModelRelease(
    val id: String,
    val displayName: String,
    val fileName: String,
    val downloadUrl: String? = null,
    val storageBucket: String? = null,
    val storagePath: String? = null,
    val expectedSha256: String? = null,
    val expectedBytes: Long? = null,
    val minFreeBytes: Long = 2_500_000_000L
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "Model id contains unsupported characters" }
        require(fileName.matches(Regex("[A-Za-z0-9._-]+"))) { "Model file name contains unsupported characters" }
        require(expectedBytes == null || expectedBytes > 0L) { "expectedBytes must be positive" }
        require(minFreeBytes >= 0L) { "minFreeBytes must be non-negative" }
    }
}

data class CrisisSentinelInstalledModel(
    val release: CrisisSentinelModelRelease,
    val file: File,
    val bytes: Long,
    val sha256: String?
)

enum class CrisisSentinelModelAvailability {
    Missing,
    Ready,
    Corrupt,
    InsufficientStorage
}

data class CrisisSentinelModelStatus(
    val release: CrisisSentinelModelRelease,
    val availability: CrisisSentinelModelAvailability,
    val file: File,
    val bytes: Long = 0L,
    val sha256: String? = null,
    val reason: String? = null,
    val freeBytes: Long? = null,
    val requiredFreeBytes: Long = release.minFreeBytes
) {
    val isReady: Boolean = availability == CrisisSentinelModelAvailability.Ready
}

class CrisisSentinelModelFileStore(context: Context) {
    private val appContext = context.applicationContext
    private val modelDirectory: File = File(modelRoot(appContext), MODEL_DIR).apply { mkdirs() }

    fun modelFile(release: CrisisSentinelModelRelease = defaultRelease): File {
        return File(modelDirectory, release.fileName)
    }

    fun tempFile(release: CrisisSentinelModelRelease = defaultRelease): File {
        return File(modelDirectory, "${release.fileName}.download")
    }

    fun status(
        release: CrisisSentinelModelRelease = defaultRelease,
        verifyChecksum: Boolean = true
    ): CrisisSentinelModelStatus {
        val file = modelFile(release)
        val freeBytes = modelDirectory.usableSpace
        val requiredFreeBytes = maxOf(release.minFreeBytes, release.expectedBytes ?: 0L)
        if (!file.exists()) {
            if (freeBytes < requiredFreeBytes) {
                return CrisisSentinelModelStatus(
                    release = release,
                    availability = CrisisSentinelModelAvailability.InsufficientStorage,
                    file = file,
                    reason = "Free storage is below required model space",
                    freeBytes = freeBytes,
                    requiredFreeBytes = requiredFreeBytes
                )
            }
            return CrisisSentinelModelStatus(
                release = release,
                availability = CrisisSentinelModelAvailability.Missing,
                file = file,
                freeBytes = freeBytes,
                requiredFreeBytes = requiredFreeBytes
            )
        }

        val bytes = file.length()
        if (release.expectedBytes != null && bytes != release.expectedBytes) {
            return CrisisSentinelModelStatus(
                release = release,
                availability = CrisisSentinelModelAvailability.Corrupt,
                file = file,
                bytes = bytes,
                reason = "Model size does not match manifest",
                freeBytes = freeBytes,
                requiredFreeBytes = requiredFreeBytes
            )
        }

        if (!verifyChecksum) {
            return CrisisSentinelModelStatus(
                release = release,
                availability = CrisisSentinelModelAvailability.Ready,
                file = file,
                bytes = bytes,
                freeBytes = freeBytes,
                requiredFreeBytes = requiredFreeBytes
            )
        }

        val sha256 = release.expectedSha256?.let { sha256(file) }
        if (release.expectedSha256 != null && !sha256.equals(release.expectedSha256, ignoreCase = true)) {
            return CrisisSentinelModelStatus(
                release = release,
                availability = CrisisSentinelModelAvailability.Corrupt,
                file = file,
                bytes = bytes,
                sha256 = sha256,
                reason = "Model checksum does not match manifest",
                freeBytes = freeBytes,
                requiredFreeBytes = requiredFreeBytes
            )
        }

        return CrisisSentinelModelStatus(
            release = release,
            availability = CrisisSentinelModelAvailability.Ready,
            file = file,
            bytes = bytes,
            sha256 = sha256,
            freeBytes = freeBytes,
            requiredFreeBytes = requiredFreeBytes
        )
    }

    fun installedModel(release: CrisisSentinelModelRelease = defaultRelease): CrisisSentinelInstalledModel? {
        val status = status(release)
        if (!status.isReady) return null
        return CrisisSentinelInstalledModel(
            release = release,
            file = status.file,
            bytes = status.bytes,
            sha256 = status.sha256
        )
    }

    fun commitDownloadedModel(tempFile: File, release: CrisisSentinelModelRelease = defaultRelease): CrisisSentinelModelStatus {
        require(tempFile.exists()) { "Temporary model file does not exist" }
        modelDirectory.mkdirs()
        val target = modelFile(release)
        if (target.exists() && !target.delete()) {
            return CrisisSentinelModelStatus(
                release = release,
                availability = CrisisSentinelModelAvailability.Corrupt,
                file = target,
                bytes = target.length(),
                reason = "Could not replace existing model"
            )
        }
        if (!tempFile.renameTo(target)) {
            tempFile.copyTo(target, overwrite = true)
            tempFile.delete()
        }
        return status(release)
    }

    fun deleteModel(release: CrisisSentinelModelRelease = defaultRelease): Boolean {
        val model = modelFile(release)
        val temp = tempFile(release)
        val modelDeleted = !model.exists() || model.delete()
        val tempDeleted = !temp.exists() || temp.delete()
        return modelDeleted && tempDeleted
    }

    companion object {
        private const val MODEL_DIR = "crisis_sentinel_models"

        val defaultRelease = CrisisSentinelModelRelease(
            id = "crisis-sentinel-edge-mobile-q4",
            displayName = "Crisis Sentinel Edge Mobile Q4",
            fileName = "crisis-sentinel-edge-mobile-q4.task",
            downloadUrl = null,
            storageBucket = null,
            storagePath = null,
            expectedSha256 = null,
            expectedBytes = null
        )

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
        }

        private fun modelRoot(context: Context): File {
            return try {
                context.noBackupFilesDir
            } catch (_: Throwable) {
                File(context.filesDir, "no_backup")
            }.apply { mkdirs() }
        }
    }
}
