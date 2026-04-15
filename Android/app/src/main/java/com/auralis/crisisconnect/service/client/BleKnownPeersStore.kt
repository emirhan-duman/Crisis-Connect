package com.auralis.crisisconnect.service.client

import android.content.Context
import java.util.Locale

internal object BleKnownPeersStore {

    fun isKnown(context: Context, address: String): Boolean {
        val normalized = normalizeAddress(address)
        if (normalized.isBlank()) {
            return false
        }
        return knownAddresses(context).contains(normalized)
    }

    fun knownAddresses(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_KNOWN_ADDRESSES, emptySet())
            ?.mapNotNull { address ->
                normalizeAddress(address).takeIf { it.isNotBlank() }
            }
            ?.toSet()
            ?: emptySet()
    }

    fun markKnown(context: Context, address: String) {
        val normalized = normalizeAddress(address)
        if (normalized.isBlank()) {
            return
        }
        val current = prefs(context).getStringSet(KEY_KNOWN_ADDRESSES, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        if (!current.add(normalized)) {
            return
        }
        prefs(context).edit()
            .putStringSet(KEY_KNOWN_ADDRESSES, current)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREF_FILE,
        Context.MODE_PRIVATE
    )

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }

    private const val PREF_FILE = "ble_known_peers"
    private const val KEY_KNOWN_ADDRESSES = "known_addresses"
}
