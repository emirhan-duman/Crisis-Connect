package com.auralis.crisisconnect.data

import java.util.Locale

object BleSessionResolver {
    private const val BLE_PREFIX = "ble:"

    fun normalizeSessionCode(sessionCode: String): String? {
        if (!sessionCode.startsWith(BLE_PREFIX, ignoreCase = true)) {
            return null
        }
        val raw = sessionCode.removePrefix(BLE_PREFIX).trim()
        if (raw.isBlank()) {
            return null
        }
        return if (raw.contains(":")) {
            "$BLE_PREFIX${normalizeAddress(raw)}"
        } else {
            "$BLE_PREFIX${raw.uppercase(Locale.US)}"
        }
    }

    fun sessionCodeForAddress(address: String): String {
        val normalized = normalizeAddress(address)
        val broadcastId = BleBroadcastDirectory.resolveBroadcastId(normalized)
        return if (broadcastId != null) {
            "$BLE_PREFIX$broadcastId"
        } else {
            "$BLE_PREFIX$normalized"
        }
    }

    fun addressForSessionCode(sessionCode: String): String? {
        val normalized = normalizeSessionCode(sessionCode) ?: return null
        val raw = normalized.removePrefix(BLE_PREFIX)
        return if (raw.contains(":")) {
            normalizeAddress(raw)
        } else {
            BleBroadcastDirectory.resolveAddress(raw)
        }
    }

    fun isBleSession(sessionCode: String): Boolean {
        return sessionCode.startsWith(BLE_PREFIX, ignoreCase = true)
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }
}
