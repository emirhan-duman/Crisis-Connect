package com.auralis.crisisconnect.service.p2p

import com.auralis.crisisconnect.service.p2p.P2pShareAdvertisement.CACHED_NAME
import com.auralis.crisisconnect.service.p2p.P2pShareAdvertisement.LOCAL_NAME
import com.auralis.crisisconnect.service.p2p.P2pShareAdvertisement.SERVICE_DATA
import com.auralis.crisisconnect.service.p2p.P2pShareAdvertisement.SERVICE_UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2pShareCandidatesTest {
    @Test
    fun explicitLocalNameStartsAuthenticationWithoutWaitingForTheScan() = runTest {
        val candidates = P2pShareCandidates<String>(serviceFallbackGraceMs = 750)
        candidates.offer("nearby", candidate("nearby", SERVICE_UUID, rssi = -30))
        val result = async { candidates.firstAuthenticated { it.device } }

        candidates.offer("target", candidate("target", LOCAL_NAME))
        runCurrent()

        assertEquals("target", result.await())
        assertEquals(0L, currentTime)
    }

    @Test
    fun exactServiceDataTakesPriorityDuringTheServiceOnlyGrace() = runTest {
        val candidates = P2pShareCandidates<String>(serviceFallbackGraceMs = 750)
        candidates.offer("nearby", candidate("nearby", SERVICE_UUID, rssi = -30))
        val result = async { candidates.firstAuthenticated { it.device } }

        advanceTimeBy(250)
        assertFalse(result.isCompleted)
        candidates.offer("target", candidate("target", SERVICE_DATA))
        runCurrent()

        assertEquals("target", result.await())
        assertEquals(250L, currentTime)
    }

    @Test
    fun serviceOnlyStartsAfterGraceAndExplicitMatchCanRetryFailedAddress() = runTest {
        val candidates = P2pShareCandidates<String>(serviceFallbackGraceMs = 750)
        val firstAttempt = CompletableDeferred<Unit>()
        val attempts = mutableListOf<P2pShareAdvertisement>()
        val result = async {
            candidates.firstAuthenticated { candidate ->
                attempts += candidate.advertisement
                if (candidate.advertisement == SERVICE_UUID) {
                    firstAttempt.await()
                    null
                } else {
                    candidate.device
                }
            }
        }
        candidates.offer("target", candidate("target", SERVICE_UUID))

        advanceTimeBy(749)
        runCurrent()
        assertEquals(emptyList<P2pShareAdvertisement>(), attempts)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(SERVICE_UUID), attempts)

        candidates.offer("target", candidate("target", SERVICE_UUID))
        candidates.offer("target", candidate("target", LOCAL_NAME))
        runCurrent()
        assertEquals(listOf(SERVICE_UUID), attempts)
        firstAttempt.complete(Unit)

        assertEquals("target", result.await())
        assertEquals(listOf(SERVICE_UUID, LOCAL_NAME), attempts)
    }

    @Test
    fun ordinaryScanDefersServiceAndCachedNameUntilScanEnds() = runTest {
        val candidates = P2pShareCandidates<String>(serviceFallbackGraceMs = null)
        candidates.offer("cached", candidate("cached", CACHED_NAME, rssi = -30))
        candidates.offer("nearby", candidate("nearby", SERVICE_UUID))
        val result = async { candidates.firstAuthenticated { it.device } }

        advanceTimeBy(1_000)
        assertFalse(result.isCompleted)
        candidates.finishScan()

        assertEquals("nearby", result.await())
    }

    @Test
    fun earlyFallbackBudgetLeavesOtherCandidatesAvailableAfterTheScan() = runTest {
        val candidates = P2pShareCandidates<String>(serviceFallbackGraceMs = 750)
        val attempts = mutableListOf<String>()
        repeat(4) { index ->
            val device = "candidate-$index"
            candidates.offer(device, candidate(device, SERVICE_UUID, rssi = -30 - index))
        }
        val result = async {
            candidates.firstAuthenticated {
                attempts += it.device
                it.device.takeIf { device -> device == "candidate-3" }
            }
        }

        advanceTimeBy(750)
        runCurrent()
        assertEquals(listOf("candidate-0", "candidate-1", "candidate-2"), attempts)
        assertFalse(result.isCompleted)
        candidates.finishScan()

        assertEquals("candidate-3", result.await())
    }

    @Test
    fun mismatchedServiceDataCannotBeOverriddenByNameOrServiceUuid() {
        assertNull(classifyP2pShareAdvertisement("ABCDEFGH", "NOTMATCH", "ABCDEFGH", true))
        assertEquals(SERVICE_DATA, classifyP2pShareAdvertisement("ABCDEFGH", " abcdefgh ", null, true))
        assertEquals(LOCAL_NAME, classifyP2pShareAdvertisement("ABCDEFGH", null, " abcdefgh ", true))
        assertEquals(SERVICE_UUID, classifyP2pShareAdvertisement("ABCDEFGH", null, null, true))
    }

    private fun candidate(
        device: String,
        advertisement: P2pShareAdvertisement,
        rssi: Int = -60
    ) = P2pShareCandidates.Candidate(device, advertisement, rssi, firstSeenMs = 0)
}
