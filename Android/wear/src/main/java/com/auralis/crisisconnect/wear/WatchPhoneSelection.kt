package com.auralis.crisisconnect.wear

internal data class WatchPhoneCandidate(val id: String, val nearby: Boolean)

/** Candidates must come from FILTER_REACHABLE for our app capability. Cloud routes are valid.
 * An action stays bound to the phone that supplied the displayed state; only status may switch.
 */
internal fun selectWatchPhone(candidates: List<WatchPhoneCandidate>, previousID: String?, requirePrevious: Boolean): WatchPhoneCandidate? {
    candidates.firstOrNull { it.id == previousID }?.let { return it }
    if (requirePrevious) return null
    return candidates.sortedWith(compareByDescending<WatchPhoneCandidate> { it.nearby }.thenBy { it.id }).firstOrNull()
}
