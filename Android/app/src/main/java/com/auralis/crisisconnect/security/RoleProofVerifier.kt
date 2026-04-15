package com.auralis.crisisconnect.security

import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import kotlin.math.abs

/**
 * Device-side helper that verifies the encrypted role proof received via BLE.
 */
class RoleProofVerifier(
    private val maxPacketSize: Int = 1024,
    private val maxClockSkewMillis: Long = 60_000L,
    private val maxExpiredCertificateGraceMillis: Long = 0L,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val tag: String = "RoleProofVerifier"
) {

    init {
        require(maxExpiredCertificateGraceMillis >= 0L) {
            "maxExpiredCertificateGraceMillis must be non-negative"
        }
    }

    fun verifyPacket(
        keyBytes: ByteArray,
        transportPacket: ByteArray,
        expectedSessionNonce: String? = null
    ): RoleProofVerificationResult {
        return runCatching {
            val encrypted = AesCipherHelper.unwrapTransportPacket(transportPacket, maxPacketSize)
            val plaintext = AesCipherHelper.decrypt(keyBytes, encrypted)
            val jsonString = plaintext.toString(StandardCharsets.UTF_8)
            val proof = RoleProofPayload.fromJson(jsonString)
            verifyProofPayload(proof, expectedSessionNonce = expectedSessionNonce)
        }.getOrElse { throwable ->
            Log.e(tag, "Failed to verify role proof", throwable)
            RoleProofVerificationResult.Failure(throwable.message ?: "Unknown failure", throwable)
        }
    }

    fun verifyProofPayload(
        proof: RoleProofPayload,
        expectedSessionNonce: String? = null
    ): RoleProofVerificationResult {
        return runCatching {
            validateProof(proof, expectedSessionNonce = expectedSessionNonce)
            RoleProofVerificationResult.Success(proof)
        }.getOrElse { throwable ->
            Log.e(tag, "Role proof validation failed", throwable)
            RoleProofVerificationResult.Failure(throwable.message ?: "Unknown failure", throwable)
        }
    }

    private fun validateProof(
        proof: RoleProofPayload,
        expectedSessionNonce: String? = null
    ) {
        val normalizedExpectedNonce = expectedSessionNonce?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedExpectedNonce != null) {
            require(proof.sessionNonce == normalizedExpectedNonce) {
                "Role proof session nonce mismatch"
            }
        }

        val now = timeProvider()
        val skew = abs(now - proof.timestamp)
        require(skew <= maxClockSkewMillis) {
            "Role proof timestamp skew $skew ms exceeds $maxClockSkewMillis"
        }

        val certificateBytes = decodeBase64(proof.certificate, "certificate")
        val roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes)
            ?: throw IllegalArgumentException("Certificate format is invalid")
        require(roleCertificate.role in RoleCertificate.ALLOWED_ROLES) {
            "Certificate role '${roleCertificate.role}' is not authorized"
        }

        val earliestValidMillis = roleCertificate.issuedAtMillis - maxClockSkewMillis
        require(now >= earliestValidMillis) {
            "Certificate is not yet valid"
        }
        // Enforce offline grace locally; do not trust a client-supplied flag to expand it.
        val latestAllowedWithGrace =
            roleCertificate.expiresAtMillis + maxClockSkewMillis + maxExpiredCertificateGraceMillis
        require(now <= latestAllowedWithGrace) {
            "Certificate expired beyond offline grace window"
        }

        val devicePublicKey = decodeEcPublicKey(proof.devicePublicKey)
        val signatureBytes = decodeBase64(proof.signature, "signature")

        val certificateValid = RoleCertificateSignatureVerifier.verify(
            roleCertificate = roleCertificate,
            publicKeyBase64 = proof.devicePublicKey
        )
        require(certificateValid) { "Certificate validation failed" }

        val signatureValid = Crypto.verifySignature(
            devicePublicKey,
            proof.signaturePayloadBytes(),
            signatureBytes
        )
        require(signatureValid) { "Payload signature is invalid" }
    }

    private fun decodeEcPublicKey(base64: String): PublicKey {
        val decoded = decodeBase64(base64, "devicePublicKey")
        val spec = X509EncodedKeySpec(decoded)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun decodeBase64(value: String, fieldName: String): ByteArray {
        require(value.isNotBlank()) { "$fieldName is required" }
        val decoded = try {
            Base64.decode(value.trim().replace("\n", ""), Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$fieldName is not valid Base64", error)
        }
        require(decoded.isNotEmpty()) { "$fieldName must not be empty" }
        return decoded
    }
}

sealed class RoleProofVerificationResult {
    data class Success(val proof: RoleProofPayload) : RoleProofVerificationResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : RoleProofVerificationResult()
}
