package com.auralis.crisisconnect.service

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordinates ownership of classic Bluetooth discovery between screens/services.
 * When at least one hold exists, background services should avoid cancelling discovery.
 */
object BluetoothClassicDiscoveryGuard {

    private val activeHolds = AtomicInteger(0)

    fun acquire(): Hold {
        activeHolds.incrementAndGet()
        return Hold()
    }

    fun isHeld(): Boolean = activeHolds.get() > 0

    class Hold internal constructor() {
        private val released = AtomicBoolean(false)

        fun release() {
            if (!released.compareAndSet(false, true)) {
                return
            }
            while (true) {
                val current = activeHolds.get()
                if (current <= 0) {
                    return
                }
                if (activeHolds.compareAndSet(current, current - 1)) {
                    return
                }
            }
        }
    }
}
