package com.auralis.crisisconnect.service.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class P2pShareAdvertisement {
    SERVICE_DATA,
    LOCAL_NAME,
    SERVICE_UUID,
    CACHED_NAME;

    val hasExplicitShareId: Boolean
        get() = this == SERVICE_DATA || this == LOCAL_NAME
}

private const val MAX_P2P_LOCAL_NAME_LENGTH = 8

internal fun classifyP2pShareAdvertisement(
    expectedShareId: String,
    advertisedShareId: String?,
    advertisedName: String?,
    hasServiceUuid: Boolean
): P2pShareAdvertisement? {
    if (advertisedShareId != null) {
        return P2pShareAdvertisement.SERVICE_DATA.takeIf {
            P2pBleProtocol.normalizeShareId(advertisedShareId) == expectedShareId
        }
    }
    if (advertisedName?.trim().equals(expectedShareId.take(MAX_P2P_LOCAL_NAME_LENGTH), ignoreCase = true)) {
        return P2pShareAdvertisement.LOCAL_NAME
    }
    return P2pShareAdvertisement.SERVICE_UUID.takeIf { hasServiceUuid }
}

internal fun matchesCachedP2pShareName(expectedShareId: String, cachedName: String?): Boolean =
    cachedName?.trim().equals(expectedShareId.take(MAX_P2P_LOCAL_NAME_LENGTH), ignoreCase = true)

internal class P2pShareCandidates<Device>(private val serviceFallbackGraceMs: Long?) {
    data class Candidate<Device>(
        val device: Device,
        val advertisement: P2pShareAdvertisement,
        val rssi: Int,
        val firstSeenMs: Long
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Candidate<Device>>()
    private val attempted = mutableMapOf<String, P2pShareAdvertisement>()
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val firstServiceOnlyCandidate = CompletableDeferred<Unit>()
    private var serviceFallbackAllowed = false
    private var earlyServiceFallbackAttempts = 0
    private var scanFinished = false

    fun offer(address: String, candidate: Candidate<Device>) = synchronized(lock) {
        val previousAttempt = attempted[address]
        if (scanFinished || (previousAttempt != null &&
                (previousAttempt.hasExplicitShareId || !candidate.advertisement.hasExplicitShareId))) {
            return@synchronized
        }
        val current = pending[address]
        val updated = if (current == null) candidate else candidate.copy(firstSeenMs = current.firstSeenMs)
        if (current != null && preference.compare(updated, current) >= 0) return@synchronized
        if (current == null && pending.size >= MAX_PENDING_CANDIDATES) {
            val weakest = pending.entries.maxWithOrNull { a, b -> preference.compare(a.value, b.value) }
                ?: return@synchronized
            if (preference.compare(updated, weakest.value) >= 0) return@synchronized
            pending.remove(weakest.key)
        }
        pending[address] = updated
        if (updated.advertisement == P2pShareAdvertisement.SERVICE_UUID) {
            firstServiceOnlyCandidate.complete(Unit)
        }
        updates.trySend(Unit)
        Unit
    }

    fun discardWeakCandidate(address: String) = synchronized(lock) {
        if (pending[address]?.advertisement?.hasExplicitShareId == false) {
            pending.remove(address)
        }
    }

    fun finishScan() = synchronized(lock) {
        scanFinished = true
        updates.close()
    }

    suspend fun <Result : Any> firstAuthenticated(
        authenticate: suspend (Candidate<Device>) -> Result?
    ): Result? = coroutineScope {
        val fallbackTimer = serviceFallbackGraceMs?.let { graceMs ->
            launch {
                firstServiceOnlyCandidate.await()
                delay(graceMs)
                synchronized(lock) {
                    serviceFallbackAllowed = true
                    updates.trySend(Unit)
                }
            }
        }
        try {
            var candidate = nextCandidate()
            while (candidate != null) {
                authenticate(candidate)?.let { return@coroutineScope it }
                candidate = nextCandidate()
            }
            null
        } finally {
            fallbackTimer?.cancel()
        }
    }

    private suspend fun nextCandidate(): Candidate<Device>? {
        while (true) {
            takeCandidate()?.let { return it }
            if (updates.receiveCatching().isClosed) return takeCandidate()
        }
    }

    private fun takeCandidate(): Candidate<Device>? = synchronized(lock) {
        val next = pending.entries
            .filter { (_, candidate) ->
                when (candidate.advertisement) {
                    P2pShareAdvertisement.SERVICE_DATA, P2pShareAdvertisement.LOCAL_NAME -> true
                    P2pShareAdvertisement.SERVICE_UUID -> scanFinished ||
                        (serviceFallbackAllowed && earlyServiceFallbackAttempts < MAX_EARLY_SERVICE_FALLBACK_ATTEMPTS)
                    P2pShareAdvertisement.CACHED_NAME -> scanFinished
                }
            }
            .minWithOrNull { a, b -> preference.compare(a.value, b.value) }
            ?: return@synchronized null
        val candidate = next.value
        pending.remove(next.key)
        attempted[next.key] = candidate.advertisement
        if (!scanFinished && candidate.advertisement == P2pShareAdvertisement.SERVICE_UUID) {
            earlyServiceFallbackAttempts++
        }
        candidate
    }

    private companion object {
        const val MAX_PENDING_CANDIDATES = 16
        const val MAX_EARLY_SERVICE_FALLBACK_ATTEMPTS = 3
        val preference = compareBy<Candidate<*>> { it.advertisement }.thenByDescending { it.rssi }
    }
}
