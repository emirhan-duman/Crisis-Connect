package com.auralis.crisisconnect.service.gattmesh

import java.security.MessageDigest

/**
 * Pure, side-effect-free derivations for the Wi-Fi Direct fast lane — extracted from
 * [WifiDirectAccelerator] so the group-credential math and the group-owner election are unit-testable
 * without a radio.
 *
 * The SSID + passphrase are derived deterministically from the authority group key, so every
 * provisioned authority device computes the *same* credentials with no out-of-band exchange (same
 * trust model as the Wi-Fi Aware PSK). Civilian/unprovisioned devices lack the group key and so can
 * neither derive the credentials nor join the group.
 */
internal object WifiDirectGroup {

    /** Wi-Fi Direct network names must start with "DIRECT-"; the suffix derives from the group key. */
    fun networkName(groupKey: ByteArray): String = "DIRECT-cc-" + deriveHex(groupKey, SSID_SALT).take(8)

    /** WPA2 passphrase (8–63 chars) derived from the group key. */
    fun passphrase(groupKey: ByteArray): String = deriveHex(groupKey, PSK_SALT).take(16)

    /**
     * Deterministic group-owner election: the device with the lexicographically lowest node id among
     * itself and all discovered peers hosts the group. Returns false until at least one peer is known
     * (a lone device never forms a group).
     */
    fun shouldHostGroup(selfNodeId: String, peerNodeIds: Set<String>): Boolean {
        if (peerNodeIds.isEmpty()) {
            return false
        }
        return (peerNodeIds + selfNodeId).minOrNull() == selfNodeId
    }

    private fun deriveHex(groupKey: ByteArray, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(groupKey + salt.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it) }

    private const val SSID_SALT = "cc-authmesh-direct-ssid-v1"
    private const val PSK_SALT = "cc-authmesh-direct-psk-v1"
}
