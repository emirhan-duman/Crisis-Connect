package com.auralis.crisisconnect.connectivity

/** Local evidence, not a claim that a disaster or cell congestion has been detected. */
internal class DisasterTrafficPolicy {
    enum class Mode { NORMAL, RESTRICTED_NETWORK, SOS, DEGRADED_CELLULAR }
    var restricted = false
    var cellular = false
    var emergencyActive = false
    var emergencyUntil = 0L
    private var unhealthy = 0
    private var healthy = 0
    private var degraded = false
    private var recoveryAfter = 0L
    private var restrictedUntil = 0L

    fun updateNetwork(restricted: Boolean, cellular: Boolean, now: Long) {
        if (this.restricted && !restricted) restrictedUntil = now + 60_000
        this.restricted = restricted
        this.cellular = cellular
    }

    fun mode(now: Long): Mode = when {
        restricted || now < restrictedUntil -> Mode.RESTRICTED_NETWORK
        emergencyActive || now < emergencyUntil -> Mode.SOS
        degraded -> Mode.DEGRADED_CELLULAR
        else -> Mode.NORMAL
    }

    fun record(now: Long, succeeded: Boolean, durationMs: Long, transientFailure: Boolean = true) {
        if (!succeeded && !transientFailure) return // Auth/permissions are not network congestion.
        if (!cellular && !degraded) return
        if (!succeeded || durationMs >= 8_000) {
            healthy = 0
            unhealthy += if (succeeded) 1 else 2
            if (unhealthy >= 4) {
                degraded = true
                recoveryAfter = now + 300_000
            }
        } else {
            unhealthy = 0
            healthy++
            if (healthy >= 2 && now >= recoveryAfter) degraded = false
        }
    }

    fun stopEmergency(now: Long) {
        if (!emergencyActive) return // Ending ordinary Bluetooth hosting is not an SOS.
        emergencyActive = false
        emergencyUntil = now + 300_000 // Avoid rapid mode changes at the end of an SOS.
    }

    companion object {
        const val MAX_TEXT_BYTES = 1_024
        private val attachmentChunkId = Regex("\\.\\d{1,8}$")
        // Both mobile attachment transports use transferId.chunkIndex, including the small tail.
        fun isAttachmentChunk(id: String): Boolean = attachmentChunkId.containsMatchIn(id)
        fun allowsCompact(template: Int, bytes: Int): Boolean =
            template in setOf(0, 202, 203, 204, 205, 208) && bytes <= MAX_TEXT_BYTES
    }
}
