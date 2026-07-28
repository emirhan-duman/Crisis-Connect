package com.auralis.crisisconnect.service

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide single-active-call gate shared by every call transport (RFCOMM, GATT P2P).
 *
 * A transport must acquire the registry before dialing out or ringing an inbound offer and
 * release it on teardown. Inbound offers that fail to acquire are answered with the transport's
 * busy signal, so a phone already in an RFCOMM call politely rejects GATT callers and vice versa.
 */
object ActiveCallRegistry {

    enum class Transport { RFCOMM, GATT_P2P }

    data class Entry(
        val transport: Transport,
        val sessionCode: String,
        val callId: String
    )

    private val active = AtomicReference<Entry?>(null)

    /** True when the slot was free (or already held by this exact call). */
    fun tryAcquire(transport: Transport, sessionCode: String, callId: String): Boolean {
        val entry = Entry(transport, sessionCode, callId)
        while (true) {
            val current = active.get()
            if (current == null) {
                if (active.compareAndSet(null, entry)) {
                    return true
                }
            } else {
                return current == entry
            }
        }
    }

    fun release(transport: Transport, callId: String) {
        val current = active.get() ?: return
        if (current.transport == transport && current.callId == callId) {
            active.compareAndSet(current, null)
        }
    }

    fun current(): Entry? = active.get()
}
