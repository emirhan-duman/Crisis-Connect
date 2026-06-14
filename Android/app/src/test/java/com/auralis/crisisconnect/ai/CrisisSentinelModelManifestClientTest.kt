package com.auralis.crisisconnect.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CrisisSentinelModelManifestClientTest {
    @Test
    fun parserBuildsReleaseFromCallableEnvelope() {
        val release = CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "gemma-3-1b-crisis-sentinel-mobile-q4",
                    "displayName" to "Crisis Sentinel Mobile",
                    "fileName" to "crisis-sentinel.task",
                    "downloadUrl" to ALLOWED_DOWNLOAD_URL,
                    "expectedSha256" to "A".repeat(64),
                    "expectedBytes" to 1024L,
                    "minFreeBytes" to 2048L
                )
            )
        )

        assertNotNull(release)
        val parsed = release!!
        assertEquals("gemma-3-1b-crisis-sentinel-mobile-q4", parsed.id)
        assertEquals("Crisis Sentinel Mobile", parsed.displayName)
        assertEquals("crisis-sentinel.task", parsed.fileName)
        assertEquals(ALLOWED_DOWNLOAD_URL, parsed.downloadUrl)
        assertEquals("crisis-connect-1.firebasestorage.app", parsed.storageBucket)
        assertEquals("crisis-sentinel/models/mobile/test/crisis-sentinel.task", parsed.storagePath)
        assertEquals("a".repeat(64), parsed.expectedSha256)
        assertEquals(1024L, parsed.expectedBytes)
        assertEquals(2048L, parsed.minFreeBytes)
    }

    @Test
    fun parserBuildsReleaseFromStorageFieldsWithoutDownloadUrl() {
        val release = CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "crisis-sentinel-mobile-v2",
                    "displayName" to "Crisis Sentinel Mobile",
                    "fileName" to "crisis-sentinel.litertlm",
                    "storageBucket" to "crisis-connect-1.firebasestorage.app",
                    "storagePath" to "crisis-sentinel/models/mobile/v2/crisis-sentinel.litertlm",
                    "expectedSha256" to "b".repeat(64),
                    "expectedBytes" to 2048L
                )
            )
        )

        assertNotNull(release)
        val parsed = release!!
        assertNull(parsed.downloadUrl)
        assertEquals("crisis-connect-1.firebasestorage.app", parsed.storageBucket)
        assertEquals("crisis-sentinel/models/mobile/v2/crisis-sentinel.litertlm", parsed.storagePath)
        assertEquals("b".repeat(64), parsed.expectedSha256)
        assertEquals(2048L, parsed.expectedBytes)
    }

    @Test
    fun parserReturnsNullWhenNoReleaseAvailable() {
        assertNull(
            CrisisSentinelModelManifestParser.parse(
                mapOf(
                    "available" to false,
                    "reason" to "not configured"
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsNonHttpsDownloadUrls() {
        CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "release",
                    "displayName" to "Release",
                    "fileName" to "model.task",
                    "downloadUrl" to "http://example.com/model.task"
                )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun parserRejectsHttpsUrlsOutsideApprovedStoragePath() {
        CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "release",
                    "displayName" to "Release",
                    "fileName" to "model.task",
                    "downloadUrl" to "https://example.com/model.task"
                )
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun parserRejectsMissingDownloadAndStoragePath() {
        CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "release",
                    "displayName" to "Release",
                    "fileName" to "model.task"
                )
            )
        )
    }

    @Test(expected = IllegalStateException::class)
    fun parserRejectsStoragePathOutsideApprovedModelPrefix() {
        CrisisSentinelModelManifestParser.parse(
            mapOf(
                "available" to true,
                "release" to mapOf(
                    "id" to "release",
                    "displayName" to "Release",
                    "fileName" to "model.task",
                    "storageBucket" to "crisis-connect-1.firebasestorage.app",
                    "storagePath" to "other/models/model.task"
                )
            )
        )
    }

    @Test
    fun policyExtractsFirebaseStorageLocationForSdkDownload() {
        val location = CrisisSentinelModelDownloadUrlPolicy.storageLocation(ALLOWED_DOWNLOAD_URL)

        assertNotNull(location)
        assertEquals("crisis-connect-1.firebasestorage.app", location?.bucket)
        assertEquals(
            "crisis-sentinel/models/mobile/test/crisis-sentinel.task",
            location?.objectPath
        )
    }

    @Test
    fun downloadAccessParserAcceptsSignedModelUrl() {
        val access = CrisisSentinelModelDownloadAccessParser.parse(
            mapOf(
                "available" to true,
                "releaseId" to "crisis-sentinel-mobile-v2",
                "downloadUrl" to ALLOWED_SIGNED_DOWNLOAD_URL,
                "expiresAtMs" to 4_102_444_800_000L
            ),
            expectedReleaseId = "crisis-sentinel-mobile-v2"
        )

        assertNotNull(access)
        assertEquals("crisis-sentinel-mobile-v2", access?.releaseId)
        assertEquals(ALLOWED_SIGNED_DOWNLOAD_URL, access?.downloadUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun downloadAccessParserRejectsSignedUrlOutsideModelPath() {
        CrisisSentinelModelDownloadAccessParser.parse(
            mapOf(
                "available" to true,
                "releaseId" to "crisis-sentinel-mobile-v2",
                "downloadUrl" to "https://storage.googleapis.com/crisis-connect-1.firebasestorage.app/other/model.task?x=1",
                "expiresAtMs" to 4_102_444_800_000L
            ),
            expectedReleaseId = "crisis-sentinel-mobile-v2"
        )
    }

    private companion object {
        const val ALLOWED_DOWNLOAD_URL =
            "https://firebasestorage.googleapis.com/v0/b/crisis-connect-1.firebasestorage.app/o/crisis-sentinel%2Fmodels%2Fmobile%2Ftest%2Fcrisis-sentinel.task?alt=media&token=test"
        const val ALLOWED_SIGNED_DOWNLOAD_URL =
            "https://storage.googleapis.com/crisis-connect-1.firebasestorage.app/crisis-sentinel/models/mobile/test/crisis-sentinel.task?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Signature=test"
    }
}
