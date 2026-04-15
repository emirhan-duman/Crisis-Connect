package com.auralis.crisisconnect.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Yalnız SOS cihazında kullanılır. Handshake doğrulanan bağlı saha cihazlarını tutar.
 * Ephemeral: Uygulama kapanınca sıfırlanır.
 */
object BlePeerStore {
    private val _peers = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val peers: StateFlow<Map<String, Contact>> = _peers.asStateFlow()

    @Synchronized
    fun upsert(contact: Contact) {
        val cur = _peers.value.toMutableMap()
        cur[contact.address] = contact
        _peers.value = cur
    }

    @Synchronized
    fun remove(address: String) {
        val cur = _peers.value.toMutableMap()
        cur.remove(address)
        _peers.value = cur
    }

    fun clear() {
        _peers.value = emptyMap()
    }
}
