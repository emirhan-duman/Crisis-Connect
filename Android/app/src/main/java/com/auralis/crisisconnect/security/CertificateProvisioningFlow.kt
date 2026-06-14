package com.auralis.crisisconnect.security

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Orchestrates issuance of a device-bound role certificate.
 *
 * 1. Request a single-use attestation challenge from the backend.
 * 2. Generate a fresh hardware-backed key whose attestation extension is
 *    bound to that challenge.
 * 3. Acquire a Play Integrity token that embeds the same nonce.
 * 4. Submit chain + integrity token to the backend, which verifies the
 *    attestation and integrity verdicts before signing the certificate.
 */
class CertificateProvisioningFlow(
    private val context: Context,
    private val functions: FirebaseFunctions,
) {

    sealed class ProvisioningError(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause) {
        class ChallengeRequestFailed(cause: Throwable) :
            ProvisioningError("Could not request attestation challenge.", cause)
        class IntegrityFailed(cause: Throwable) :
            ProvisioningError("Could not acquire Play Integrity token.", cause)
        class AttestationFailed(cause: Throwable) :
            ProvisioningError("Hardware attestation chain generation failed.", cause)
        class IssuanceFailed(cause: Throwable) :
            ProvisioningError("Backend rejected the certificate issuance.", cause)
        class CertificateUnusable(message: String) :
            ProvisioningError(message)
    }

    data class ProvisioningResult(
        val certificate: RoleCertificate,
        val keyMaterial: AttestedKeyStore.AttestedKeyMaterial,
    )

    suspend fun provisionCertificate(deviceId: String): ProvisioningResult = withContext(Dispatchers.IO) {
        require(DEVICE_ID_REGEX.matches(deviceId)) {
            "deviceId must match 'cc-' followed by 24 hex characters."
        }

        val challengeBytes = runCatching {
            requestAttestationChallenge(deviceId)
        }.getOrElse { throw ProvisioningError.ChallengeRequestFailed(it) }

        val keyMaterial = runCatching {
            AttestedKeyStore.generateAttestedSigningKey(
                context = context,
                serverChallenge = challengeBytes,
            )
        }.getOrElse {
            throw ProvisioningError.AttestationFailed(it)
        }

        val integrityToken = runCatching {
            PlayIntegrityHelper.requestIntegrityToken(
                context = context,
                nonceBytes = challengeBytes,
            )
        }.getOrElse { throw ProvisioningError.IntegrityFailed(it) }

        val certificate = runCatching {
            issueRoleCertificate(
                deviceId = deviceId,
                publicKeyBase64 = keyMaterial.publicKeyBase64,
                attestationChainPem = keyMaterial.certificateChainPem,
                integrityToken = integrityToken,
                challengeBase64 = Base64.encodeToString(challengeBytes, Base64.NO_WRAP),
            )
        }.getOrElse { throw ProvisioningError.IssuanceFailed(it) }

        require(certificate.isBoundTo(deviceId)) {
            throw ProvisioningError.CertificateUnusable(
                "Backend certificate is not bound to the requested deviceId."
            )
        }
        require(RoleCertificateSignatureVerifier.verify(certificate, keyMaterial.publicKeyBase64)) {
            throw ProvisioningError.CertificateUnusable(
                "Backend certificate signature failed local verification."
            )
        }
        require(certificate.isValidAt(System.currentTimeMillis())) {
            throw ProvisioningError.CertificateUnusable(
                "Backend certificate is outside its validity window."
            )
        }

        Log.i(
            TAG,
            "Provisioned device-bound certificate role=${certificate.role} deviceId=$deviceId " +
                "expiresAtMs=${certificate.expiresAtMillis} securityHint=${keyMaterial.securityHint}"
        )
        ProvisioningResult(certificate = certificate, keyMaterial = keyMaterial)
    }

    private suspend fun requestAttestationChallenge(deviceId: String): ByteArray {
        val callable = functions.getHttpsCallable("requestAttestationChallenge")
        val result = callable.call(mapOf("deviceId" to deviceId)).awaitResult()
        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected challenge response type.")
        val challenge = data["challenge"] as? String
            ?: throw IllegalStateException("Challenge response missing 'challenge'.")
        return Base64.decode(challenge.trim().replace("\n", ""), Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private suspend fun issueRoleCertificate(
        deviceId: String,
        publicKeyBase64: String,
        attestationChainPem: List<String>,
        integrityToken: String,
        challengeBase64: String,
    ): RoleCertificate {
        val callable = functions.getHttpsCallable("issueRoleCertificate")
        val result = callable.call(
            mapOf(
                "deviceId" to deviceId,
                "publicKey" to publicKeyBase64,
                "attestationChainPem" to attestationChainPem,
                "integrityToken" to integrityToken,
                "attestationChallenge" to challengeBase64,
            )
        ).awaitResult()
        val data = result.data as? Map<*, *>
            ?: throw IllegalStateException("Unexpected issuance response type.")
        return RoleCertificate.fromCallableResponse(data)
    }

    companion object {
        private const val TAG = "CertProvisioning"
        private val DEVICE_ID_REGEX = Regex("^cc-[0-9a-f]{24}$")
    }
}

private suspend fun Task<HttpsCallableResult>.awaitResult(): HttpsCallableResult =
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
                val error = task.exception ?: IllegalStateException("Firebase callable failed.")
                continuation.resumeWithException(error)
            }
        }
    }
