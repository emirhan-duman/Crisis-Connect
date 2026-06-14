package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.google.android.play.core.integrity.IntegrityTokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Requests Play Integrity tokens bound to a server-issued nonce. The resulting
 * token is decoded server-side via the Play Integrity Decode API.
 */
object PlayIntegrityHelper {

    private const val CRISIS_CONNECT_PROJECT_NUMBER = 865429938209L

    /**
     * Requests a fresh integrity token using the supplied attestation nonce.
     *
     * The nonce should be the same byte sequence supplied to the Android
     * Keystore via `setAttestationChallenge` so the server can correlate the
     * Play Integrity verdict with the key attestation chain.
     */
    suspend fun requestIntegrityToken(
        context: Context,
        nonceBytes: ByteArray,
        cloudProjectNumber: Long = CRISIS_CONNECT_PROJECT_NUMBER,
    ): String {
        require(nonceBytes.size in 16..500) {
            "Integrity nonce must be between 16 and 500 bytes (got ${nonceBytes.size})."
        }
        val nonceBase64Url = Base64.encodeToString(
            nonceBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val manager = IntegrityManagerFactory.create(context.applicationContext)
        val request = IntegrityTokenRequest.builder()
            .setNonce(nonceBase64Url)
            .setCloudProjectNumber(cloudProjectNumber)
            .build()
        val response = manager.requestIntegrityToken(request).await()
        return response.token()
            ?: throw IllegalStateException("Play Integrity returned an empty token.")
    }
}

private suspend fun Task<IntegrityTokenResponse>.await(): IntegrityTokenResponse =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        addOnFailureListener { throwable ->
            if (continuation.isActive) {
                continuation.resumeWithException(throwable)
            }
        }
        addOnCanceledListener {
            if (continuation.isActive) {
                continuation.cancel()
            }
        }
    }
