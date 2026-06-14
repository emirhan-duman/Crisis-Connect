package com.auralis.crisisconnect.ai

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class CrisisSentinelModelDownloadAccess(
    val releaseId: String,
    val downloadUrl: String,
    val expiresAtMs: Long
)

class CrisisSentinelModelDownloadAccessClient(
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION)
) {
    suspend fun fetchDownloadUrl(
        release: CrisisSentinelModelRelease,
        platform: String = "android"
    ): CrisisSentinelModelDownloadAccess = withContext(Dispatchers.IO) {
        val result = functions
            .getHttpsCallable(CALLABLE_NAME)
            .call(
                mapOf(
                    "platform" to platform,
                    "releaseId" to release.id
                )
            )
            .awaitDownloadAccessResult()
        CrisisSentinelModelDownloadAccessParser.parse(result.data, expectedReleaseId = release.id)
            ?: throw IllegalStateException("Crisis Sentinel model download is not available.")
    }

    companion object {
        private const val FUNCTIONS_REGION = "us-central1"
        private const val CALLABLE_NAME = "getCrisisSentinelModelDownloadUrl"
    }
}

internal object CrisisSentinelModelDownloadAccessParser {
    fun parse(raw: Any?, expectedReleaseId: String? = null): CrisisSentinelModelDownloadAccess? {
        val envelope = raw as? Map<*, *> ?: return null
        val available = envelope["available"] as? Boolean ?: false
        if (!available) return null

        val releaseId = envelope.stringValue("releaseId")
        require(expectedReleaseId == null || releaseId == expectedReleaseId) {
            "Crisis Sentinel signed download URL does not match the requested release."
        }

        val downloadUrl = envelope.stringValue("downloadUrl")
        require(downloadUrl.startsWith("https://", ignoreCase = true)) {
            "Crisis Sentinel signed download URL must use HTTPS."
        }
        CrisisSentinelModelDownloadUrlPolicy.requireAllowed(downloadUrl)

        val expiresAtMs = envelope.longValue("expiresAtMs")
        require(expiresAtMs > 0L) { "Crisis Sentinel signed download URL expiry must be positive." }

        return CrisisSentinelModelDownloadAccess(
            releaseId = releaseId,
            downloadUrl = downloadUrl,
            expiresAtMs = expiresAtMs
        )
    }

    private fun Map<*, *>.stringValue(key: String): String {
        return (this[key] as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalStateException("Crisis Sentinel download access is missing '$key'.")
    }

    private fun Map<*, *>.longValue(key: String): Long {
        val raw = this[key] ?: throw IllegalStateException(
            "Crisis Sentinel download access is missing '$key'."
        )
        val value = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> {
                require(raw % 1.0 == 0.0) { "Crisis Sentinel download access '$key' must be an integer." }
                raw.toLong()
            }
            is Number -> raw.toLong()
            else -> throw IllegalStateException("Crisis Sentinel download access '$key' must be numeric.")
        }
        return value
    }
}

private suspend fun Task<HttpsCallableResult>.awaitDownloadAccessResult(): HttpsCallableResult =
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
