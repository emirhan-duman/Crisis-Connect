package com.auralis.crisisconnect.ai

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class CrisisSentinelModelManifestClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)
) {
    suspend fun fetchLatest(platform: String = "android"): CrisisSentinelModelRelease? =
        withContext(Dispatchers.IO) {
            val result = functions
                .getHttpsCallable(CALLABLE_NAME)
                .call(mapOf("platform" to platform))
                .awaitManifestResult()
            CrisisSentinelModelManifestParser.parse(result.data)
        }

    companion object {
        private const val FUNCTIONS_REGION = "us-central1"
        private const val CALLABLE_NAME = "getCrisisSentinelModelManifest"
    }
}

internal object CrisisSentinelModelManifestParser {
    private val sha256Regex = Regex("^[a-fA-F0-9]{64}$")

    fun parse(raw: Any?): CrisisSentinelModelRelease? {
        val envelope = raw as? Map<*, *> ?: return null
        val available = envelope["available"] as? Boolean ?: false
        if (!available) return null
        val release = envelope["release"] as? Map<*, *>
            ?: throw IllegalStateException("Crisis Sentinel manifest is missing release.")

        val id = release.stringValue("id")
        val displayName = release.stringValue("displayName")
        val fileName = release.stringValue("fileName")
        val downloadUrl = release.optionalStringValue("downloadUrl")
        val explicitStorageLocation = release.storageLocationValue()
        val downloadUrlStorageLocation = downloadUrl?.let { url ->
            require(url.startsWith("https://", ignoreCase = true)) {
                "Crisis Sentinel model download URL must use HTTPS."
            }
            CrisisSentinelModelDownloadUrlPolicy.requireAllowed(url)
            CrisisSentinelModelDownloadUrlPolicy.storageLocation(url)
        }
        val storageLocation = explicitStorageLocation ?: downloadUrlStorageLocation
            ?: throw IllegalStateException(
                "Crisis Sentinel manifest is missing an approved Firebase Storage model path."
            )
        require(downloadUrlStorageLocation == null || downloadUrlStorageLocation == storageLocation) {
            "Crisis Sentinel manifest download URL and storage path must point to the same model."
        }

        val sha256 = release.optionalStringValue("expectedSha256")
        require(sha256 == null || sha256Regex.matches(sha256)) {
            "Crisis Sentinel model checksum must be 64 hex characters."
        }

        return CrisisSentinelModelRelease(
            id = id,
            displayName = displayName,
            fileName = fileName,
            downloadUrl = downloadUrl,
            storageBucket = storageLocation.bucket,
            storagePath = storageLocation.objectPath,
            expectedSha256 = sha256?.lowercase(),
            expectedBytes = release.optionalLongValue("expectedBytes"),
            minFreeBytes = release.optionalLongValue("minFreeBytes")
                ?: CrisisSentinelModelFileStore.defaultRelease.minFreeBytes
        )
    }

    private fun Map<*, *>.stringValue(key: String): String {
        return optionalStringValue(key)
            ?: throw IllegalStateException("Crisis Sentinel manifest is missing '$key'.")
    }

    private fun Map<*, *>.optionalStringValue(key: String): String? {
        return (this[key] as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun Map<*, *>.storageLocationValue(): FirebaseStorageModelLocation? {
        val bucket = optionalStringValue("storageBucket")
        val objectPath = optionalStringValue("storagePath") ?: optionalStringValue("objectPath")
        if (bucket == null && objectPath == null) return null
        require(bucket != null && objectPath != null) {
            "Crisis Sentinel manifest storageBucket and storagePath must be provided together."
        }
        return CrisisSentinelModelDownloadUrlPolicy.locationIfAllowed(bucket, objectPath)
            ?: throw IllegalStateException(
                "Crisis Sentinel manifest storage path must point to the approved Firebase Storage model path."
            )
    }

    private fun Map<*, *>.optionalLongValue(key: String): Long? {
        val raw = this[key] ?: return null
        val value = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> {
                require(raw % 1.0 == 0.0) { "Crisis Sentinel manifest '$key' must be an integer." }
                raw.toLong()
            }
            is Number -> raw.toLong()
            else -> throw IllegalStateException("Crisis Sentinel manifest '$key' must be numeric.")
        }
        require(value > 0L) { "Crisis Sentinel manifest '$key' must be positive." }
        return value
    }
}

internal object CrisisSentinelModelDownloadUrlPolicy {
    private const val FIREBASE_STORAGE_HOST = "firebasestorage.googleapis.com"
    private const val GCS_HOST = "storage.googleapis.com"
    private const val MODEL_OBJECT_PREFIX = "crisis-sentinel/models/"
    private val allowedModelBuckets = setOf(
        "crisis-connect-1.firebasestorage.app",
        "crisis-connect-1.appspot.com"
    )

    fun requireAllowed(rawUrl: String) {
        require(isAllowed(rawUrl)) {
            "Crisis Sentinel model download URL must point to the approved Firebase Storage model path."
        }
    }

    fun isAllowed(rawUrl: String): Boolean {
        return storageLocation(rawUrl) != null
    }

    fun storageLocation(rawUrl: String): FirebaseStorageModelLocation? {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https") return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        return when (host) {
            FIREBASE_STORAGE_HOST -> firebaseStorageLocation(uri)
            GCS_HOST -> gcsStorageLocation(uri)
            else -> null
        }
    }

    fun storageLocation(release: CrisisSentinelModelRelease): FirebaseStorageModelLocation? {
        val explicitBucket = release.storageBucket?.trim()?.takeIf { it.isNotEmpty() }
        val explicitPath = release.storagePath?.trim()?.takeIf { it.isNotEmpty() }
        if (explicitBucket != null || explicitPath != null) {
            return if (explicitBucket != null && explicitPath != null) {
                locationIfAllowed(explicitBucket, explicitPath)
            } else {
                null
            }
        }
        return release.downloadUrl?.let(::storageLocation)
    }

    private fun firebaseStorageLocation(uri: URI): FirebaseStorageModelLocation? {
        if (!hasAltMedia(uri.rawQuery)) return null
        val rawPath = uri.rawPath ?: return null
        val marker = "/o/"
        val objectMarkerIndex = rawPath.indexOf(marker)
        if (!rawPath.startsWith("/v0/b/") || objectMarkerIndex < 0) return null

        val bucket = decode(rawPath.substringAfter("/v0/b/").substringBefore(marker))
        val objectPath = decode(rawPath.substring(objectMarkerIndex + marker.length))
        return locationIfAllowed(bucket, objectPath)
    }

    private fun gcsStorageLocation(uri: URI): FirebaseStorageModelLocation? {
        val path = decode(uri.rawPath ?: return null).trimStart('/')
        val bucket = path.substringBefore('/', missingDelimiterValue = "")
        val objectPath = path.substringAfter('/', missingDelimiterValue = "")
        return locationIfAllowed(bucket, objectPath)
    }

    fun locationIfAllowed(
        bucket: String,
        objectPath: String
    ): FirebaseStorageModelLocation? {
        return if (bucket in allowedModelBuckets && objectPath.startsWith(MODEL_OBJECT_PREFIX)) {
            FirebaseStorageModelLocation(bucket = bucket, objectPath = objectPath)
        } else {
            null
        }
    }

    private fun hasAltMedia(rawQuery: String?): Boolean {
        return rawQuery
            ?.split('&')
            ?.any { part ->
                val key = part.substringBefore('=')
                val value = part.substringAfter('=', missingDelimiterValue = "")
                key == "alt" && value == "media"
            } == true
    }

    private fun decode(value: String): String {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }
}

internal data class FirebaseStorageModelLocation(
    val bucket: String,
    val objectPath: String
)

class CrisisSentinelModelManifestCache(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(): CrisisSentinelModelRelease? {
        val id = preferences.getString(KEY_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val displayName = preferences.getString(KEY_DISPLAY_NAME, null)?.takeIf { it.isNotBlank() } ?: id
        val fileName = preferences.getString(KEY_FILE_NAME, null)?.takeIf { it.isNotBlank() } ?: return null
        val downloadUrl = preferences.getString(KEY_DOWNLOAD_URL, null)?.takeIf { it.isNotBlank() }
        val storageBucket = preferences.getString(KEY_STORAGE_BUCKET, null)?.takeIf { it.isNotBlank() }
        val storagePath = preferences.getString(KEY_STORAGE_PATH, null)?.takeIf { it.isNotBlank() }
        val sha256 = preferences.getString(KEY_SHA256, null)?.takeIf { it.isNotBlank() }
        val expectedBytes = preferences.getLong(KEY_EXPECTED_BYTES, -1L).takeIf { it > 0L }
        val minFreeBytes = preferences.getLong(
            KEY_MIN_FREE_BYTES,
            CrisisSentinelModelFileStore.defaultRelease.minFreeBytes
        )
        val storageLocation = storageBucket?.let { bucket ->
            storagePath?.let { path ->
                CrisisSentinelModelDownloadUrlPolicy.locationIfAllowed(bucket, path)
            }
        } ?: downloadUrl?.let(CrisisSentinelModelDownloadUrlPolicy::storageLocation)
        return runCatching {
            CrisisSentinelModelRelease(
                id = id,
                displayName = displayName,
                fileName = fileName,
                downloadUrl = downloadUrl,
                storageBucket = storageLocation?.bucket,
                storagePath = storageLocation?.objectPath,
                expectedSha256 = sha256,
                expectedBytes = expectedBytes,
                minFreeBytes = minFreeBytes
            )
        }.getOrNull()
    }

    fun save(release: CrisisSentinelModelRelease) {
        preferences.edit()
            .putString(KEY_ID, release.id)
            .putString(KEY_DISPLAY_NAME, release.displayName)
            .putString(KEY_FILE_NAME, release.fileName)
            .putString(KEY_DOWNLOAD_URL, release.downloadUrl)
            .putString(KEY_STORAGE_BUCKET, release.storageBucket)
            .putString(KEY_STORAGE_PATH, release.storagePath)
            .putString(KEY_SHA256, release.expectedSha256)
            .putLong(KEY_EXPECTED_BYTES, release.expectedBytes ?: -1L)
            .putLong(KEY_MIN_FREE_BYTES, release.minFreeBytes)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "crisis_sentinel_model_manifest"
        private const val KEY_ID = "id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_FILE_NAME = "file_name"
        private const val KEY_DOWNLOAD_URL = "download_url"
        private const val KEY_STORAGE_BUCKET = "storage_bucket"
        private const val KEY_STORAGE_PATH = "storage_path"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_EXPECTED_BYTES = "expected_bytes"
        private const val KEY_MIN_FREE_BYTES = "min_free_bytes"
    }
}

private suspend fun Task<HttpsCallableResult>.awaitManifestResult(): HttpsCallableResult =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val result = task.result
                if (result != null) {
                    continuation.resume(result)
                } else {
                    continuation.resumeWithException(
                        IllegalStateException("Firebase callable returned a null result.")
                    )
                }
            } else {
                continuation.resumeWithException(
                    task.exception ?: IllegalStateException("Firebase callable failed.")
                )
            }
        }
    }
