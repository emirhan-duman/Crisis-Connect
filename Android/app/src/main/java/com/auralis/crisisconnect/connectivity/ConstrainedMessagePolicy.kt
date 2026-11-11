package com.auralis.crisisconnect.connectivity

/** Data-use policy shared by the Android sender and its unit tests. */
internal object ConstrainedMessagePolicy {
    const val MAX_TEXT_BYTES = 1_024

    fun allowsText(templateCode: Int, text: String): Boolean =
        (templateCode == FREE_TEXT_TEMPLATE || templateCode == SOS_ALERT_TEMPLATE) &&
            text.toByteArray(Charsets.UTF_8).size <= MAX_TEXT_BYTES

    fun isConstrained(
        satelliteTransport: Boolean,
        notBandwidthConstrained: Boolean,
        bandwidthCapabilitySupported: Boolean
    ): Boolean = satelliteTransport ||
        (bandwidthCapabilitySupported && !notBandwidthConstrained)

    private const val FREE_TEXT_TEMPLATE = 0
    private const val SOS_ALERT_TEMPLATE = 202
}
