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
    private val deviceId = "cc-0123456789abcdef01234567"

    @Test
    fun signingPayload_normalizesFieldTeamAliases() {
        val payload = RoleCertificate.signingPayload(
            publicKeyBase64 = "public-key",
            ownerUid = "user-1",
            role = "field_team",
            deviceId = deviceId,
            issuedAtMillis = 1_000L,
            expiresAtMillis = 2_000L,
            agency = "AFAD"
        )
        val text = String(payload, StandardCharsets.UTF_8)
        assertTrue(text.contains("|fieldteam|"))
        assertTrue(text.endsWith("|AFAD"))
    }

    @Test
    fun signingPayload_appendsSanitizedAgency() {
        val payload = RoleCertificate.signingPayload(
            publicKeyBase64 = "public-key",
            ownerUid = "user-1",
            role = "admin",
            deviceId = deviceId,
            issuedAtMillis = 1_000L,
            expiresAtMillis = 2_000L,
            // Embedded delimiter + control chars + extra whitespace must be stripped/collapsed
            // so the canonical payload stays unambiguous and matches the backend.
            agency = "  AF|AD\tdisaster\n unit  "
        )
        val text = String(payload, StandardCharsets.UTF_8)
        assertTrue(text.endsWith("|AF AD disaster unit"))
    }

    @Test
    fun signingPayload_v2OmitsAgencySegment() {
        val payload = RoleCertificate.signingPayload(
            publicKeyBase64 = "public-key",
            ownerUid = "user-1",
            role = "admin",
            deviceId = deviceId,
            issuedAtMillis = 1_000L,
            expiresAtMillis = 2_000L,
            agency = "AFAD",
            version = 2
        )
        val text = String(payload, StandardCharsets.UTF_8)
        // Legacy v2 canonical ends at expiresAtMillis — no trailing agency.
        assertTrue(text.endsWith("|2000"))
        assertFalse(text.contains("AFAD"))
    }

    @Test
    fun fromCallableResponse_acceptsLegacyV2WithoutAgency() {
        val cert = RoleCertificate.fromCallableResponse(
            mapOf(
                "certificateVersion" to 2,
                "ownerUid" to "user-1",
                "role" to "admin",
                "deviceId" to deviceId,
                "issuedAtMs" to 1_000L,
                "expiresAtMs" to 2_000L,
                "certificate" to "AQID"
            )
        )
        assertEquals(2, cert.version)
        assertEquals("", cert.agency)
    }

    @Test
    fun sanitizeAgency_isIdempotentAndBounded() {
        val once = RoleCertificate.sanitizeAgency("A".repeat(80) + "|extra")
        val twice = RoleCertificate.sanitizeAgency(once)
        assertEquals(once, twice)
        assertTrue(once.length <= RoleCertificate.AGENCY_MAX_LENGTH)
    }

    @Test
    fun storageRoundTrip_preservesAgency() {
        val signature = Base64.encodeToString(
            byteArrayOf(1, 2, 3, 4),
            Base64.NO_WRAP or Base64.NO_PADDING
        )
        val cert = RoleCertificate(
            ownerUid = "user-1",
            role = "admin",
            deviceId = deviceId,
            issuedAtMillis = 100_000L,
            expiresAtMillis = 200_000L,
            signatureBase64 = signature,
            agency = "FEMA"
        )
        val restored = RoleCertificate.fromStorageBytes(cert.toStorageBytes())
        assertEquals("FEMA", restored?.agency)
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
            deviceId = deviceId,
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
            deviceId = deviceId,
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
            deviceId = deviceId,
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
