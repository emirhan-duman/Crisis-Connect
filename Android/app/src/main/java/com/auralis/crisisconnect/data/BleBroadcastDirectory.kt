package com.auralis.crisisconnect.data

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BleBroadcastEntry(
    val broadcastId: String,
    val address: String,
    val lastSeen: Long,
)

/**
 * Tracks BLE broadcast IDs for BLE transports so sessions stay stable when device addresses rotate.
 */
object BleBroadcastDirectory {
    private val lock = Any()
    private val _entries = MutableStateFlow<Map<String, BleBroadcastEntry>>(emptyMap())
    val entries: StateFlow<Map<String, BleBroadcastEntry>> = _entries.asStateFlow()

    private val addressToId = mutableMapOf<String, String>()

    fun update(broadcastId: String, address: String, lastSeen: Long) {
        val normalizedId = normalizeId(broadcastId)
        val normalizedAddress = normalizeAddress(address)
        synchronized(lock) {
            val next = _entries.value.toMutableMap()
            next[normalizedId] = BleBroadcastEntry(
                broadcastId = normalizedId,
                address = normalizedAddress,
                lastSeen = lastSeen
            )
            _entries.value = next
            addressToId[normalizedAddress] = normalizedId
        }
    }

    fun resolveBroadcastId(address: String): String? {
        val normalized = normalizeAddress(address)
        synchronized(lock) {
            return addressToId[normalized]
        }
    }

    fun resolveAddress(broadcastId: String): String? {
        val normalizedId = normalizeId(broadcastId)
        return _entries.value[normalizedId]?.address
    }

    fun remove(broadcastId: String) {
        val normalizedId = normalizeId(broadcastId)
        synchronized(lock) {
            val entry = _entries.value[normalizedId]
            val next = _entries.value.toMutableMap()
            next.remove(normalizedId)
            _entries.value = next
            if (entry != null) {
                addressToId.remove(entry.address)
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            _entries.value = emptyMap()
            addressToId.clear()
        }
    }

    private fun normalizeId(value: String): String {
        return value.trim().uppercase(Locale.US)
    }

    private fun normalizeAddress(address: String): String {
        return address.trim().uppercase(Locale.US)
    }
}
