package com.auralis.crisisconnect.nearby

/** Bound password guesses globally as well as per address, since BLE addresses can rotate. */
class NearbyPairingAdmission {
    private val attempts = ArrayDeque<Pair<String, Long>>()
    @Synchronized fun admit(address: String, now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first().second >= 60_000L) attempts.removeFirst()
        if (attempts.size >= 12 || attempts.count { it.first == address } >= 3) return false
        attempts.addLast(address to now)
        return true
    }
}
