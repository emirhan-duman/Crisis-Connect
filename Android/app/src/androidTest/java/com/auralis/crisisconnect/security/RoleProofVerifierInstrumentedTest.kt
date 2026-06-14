package com.auralis.crisisconnect.security

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoleProofVerifierInstrumentedTest {

    @Test
    fun expiredCertificateWithinOfflineGrace_doesNotFailDueToExpiry() {
        val nowMillis = 1_700_000_000_000L
        val proof = buildProof(
            nowMillis = nowMillis,
            expiresAtMillis = nowMillis - 2L * 60L * 1000L,
            allowExpiredCertificate = false
        )
        val verifier = RoleProofVerifier(timeProvider = { nowMillis })

        val result = verifier.verifyProofPayload(proof, expectedSessionNonce = TEST_NONCE)

        val failure = result as? RoleProofVerificationResult.Failure
        assertEquals("Certificate validation failed", failure?.reason)
    }

    @Test
    fun expiredCertificateBeyondOfflineGrace_isRejectedEvenIfClientRequestsGrace() {
        val nowMillis = 1_700_000_000_000L
        val proof = buildProof(
            nowMillis = nowMillis,
            expiresAtMillis = nowMillis -
                RoleCertificate.DEFAULT_OFFLINE_GRACE_MILLIS -
                (2L * RoleCertificate.DEFAULT_MAX_CLOCK_SKEW_MILLIS),
            allowExpiredCertificate = true
        )
        val verifier = RoleProofVerifier(timeProvider = { nowMillis })

        val result = verifier.verifyProofPayload(proof, expectedSessionNonce = TEST_NONCE)

        val failure = result as? RoleProofVerificationResult.Failure
        assertEquals("Certificate expired beyond offline grace window", failure?.reason)
    }

    private fun buildProof(
        nowMillis: Long,
        expiresAtMillis: Long,
        allowExpiredCertificate: Boolean
    ): RoleProofPayload {
        val deviceKeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        val roleCertificate = RoleCertificate(
            deviceId = TEST_DEVICE_ID,
            ownerUid = "user-1",
            role = "admin",
            issuedAtMillis = expiresAtMillis - (24L * 60L * 60L * 1000L),
            expiresAtMillis = expiresAtMillis,
            signatureBase64 = encodeBase64(byteArrayOf(1, 2, 3, 4))
        )

        return RoleProofPayload(
            devicePublicKey = encodeBase64(deviceKeyPair.public.encoded),
            certificate = encodeBase64(roleCertificate.toStorageBytes()),
            timestamp = nowMillis,
            signature = encodeBase64(byteArrayOf(9, 8, 7, 6)),
            sessionNonce = TEST_NONCE,
            allowExpiredCertificate = allowExpiredCertificate
        )
    }

    private fun encodeBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private companion object {
        private const val TEST_DEVICE_ID = "cc-0123456789abcdef01234567"
        private const val TEST_NONCE = "nonce-1"
    }
}
