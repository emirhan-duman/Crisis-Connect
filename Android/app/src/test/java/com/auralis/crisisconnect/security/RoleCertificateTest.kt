package com.auralis.crisisconnect.security

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
class RoleCertificateTest {

    @Test
    fun signingPayload_normalizesFieldTeamAliases() {
        val payload = RoleCertificate.signingPayload(
            publicKeyBase64 = "public-key",
            ownerUid = "user-1",
            role = "field_team",
            issuedAtMillis = 1_000L,
            expiresAtMillis = 2_000L
        )
        val text = String(payload, StandardCharsets.UTF_8)
        assertTrue(text.contains("|fieldteam|"))
    }

    @Test(expected = IllegalStateException::class)
    fun fromCallableResponse_rejectsUnauthorizedRole() {
        RoleCertificate.fromCallableResponse(
            mapOf(
                "ownerUid" to "user-1",
                "role" to "user",
                "issuedAtMs" to 1_000L,
                "expiresAtMs" to 2_000L,
                "certificate" to "AQID"
            )
        )
    }

    @Test
    fun isValidAt_acceptsWithinClockSkewWindow() {
        val signature = Base64.encodeToString(
            byteArrayOf(1, 2, 3, 4),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        val cert = RoleCertificate(
            ownerUid = "user-1",
            role = "admin",
            issuedAtMillis = 100_000L,
            expiresAtMillis = 200_000L,
            signatureBase64 = signature
        )

        assertTrue(cert.hasValidShape())
        assertTrue(cert.isValidAt(99_500L, maxClockSkewMillis = 1_000L))
    }

    @Test
    fun isUsableAt_acceptsWithinOfflineGraceWindow() {
        val signature = Base64.encodeToString(
            byteArrayOf(1, 2, 3, 4),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        val cert = RoleCertificate(
            ownerUid = "user-1",
            role = "admin",
            issuedAtMillis = 100_000L,
            expiresAtMillis = 200_000L,
            signatureBase64 = signature
        )

        assertTrue(
            cert.isUsableAt(
                nowMillis = 200_000L + RoleCertificate.DEFAULT_OFFLINE_GRACE_MILLIS,
                allowOfflineGrace = true,
                maxClockSkewMillis = 0L
            )
        )
    }

    @Test
    fun isUsableAt_rejectsBeyondOfflineGraceWindow() {
        val signature = Base64.encodeToString(
            byteArrayOf(1, 2, 3, 4),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        val cert = RoleCertificate(
            ownerUid = "user-1",
            role = "admin",
            issuedAtMillis = 100_000L,
            expiresAtMillis = 200_000L,
            signatureBase64 = signature
        )

        assertFalse(
            cert.isUsableAt(
                nowMillis = 200_001L + RoleCertificate.DEFAULT_OFFLINE_GRACE_MILLIS,
                allowOfflineGrace = true,
                maxClockSkewMillis = 0L
            )
        )
    }
}
