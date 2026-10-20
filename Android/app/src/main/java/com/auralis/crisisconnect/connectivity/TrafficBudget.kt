package com.auralis.crisisconnect.connectivity

/** These are local connection observations, never a declaration that a person is safe. */
enum class TrafficPresence { NORMAL, LIMITED, OFFLINE, SOS }
enum class NetworkWork { MESSAGE, RECEIPT, SOS, AVATAR, MAP_DOWNLOAD, FEED_REFRESH, TELEMETRY }

object TrafficBudget {
    fun permits(work: NetworkWork, saving: Boolean, userInitiated: Boolean = false): Boolean =
        when (work) {
            NetworkWork.MESSAGE, NetworkWork.RECEIPT, NetworkWork.SOS -> true
            NetworkWork.FEED_REFRESH -> !saving || userInitiated
            else -> !saving
        }
    fun presence(online: Boolean, emergency: Boolean, saving: Boolean): TrafficPresence = when {
        emergency -> TrafficPresence.SOS
        !online -> TrafficPresence.OFFLINE
        saving -> TrafficPresence.LIMITED
        else -> TrafficPresence.NORMAL
    }
}
