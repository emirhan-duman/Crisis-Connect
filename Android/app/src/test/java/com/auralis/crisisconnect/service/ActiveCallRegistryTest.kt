package com.auralis.crisisconnect.service

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveCallRegistryTest {

    @After
    fun releaseSlot() {
        // The registry is a process-wide singleton; leave it clean for the next test.
        ActiveCallRegistry.current()?.let {
            ActiveCallRegistry.release(it.transport, it.callId)
        }
    }

    @Test
    fun acquiresFreeSlot() {
        assertTrue(
            ActiveCallRegistry.tryAcquire(
                ActiveCallRegistry.Transport.RFCOMM,
                sessionCode = "AAAA",
                callId = "call-1"
            )
        )
        assertEquals(
            ActiveCallRegistry.Entry(ActiveCallRegistry.Transport.RFCOMM, "AAAA", "call-1"),
            ActiveCallRegistry.current()
        )
    }

    @Test
    fun reacquiringTheSameCallIsIdempotent() {
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, "AAAA", "call-1"))
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, "AAAA", "call-1"))
    }

    @Test
    fun otherTransportIsBusyWhileHeld() {
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.RFCOMM, "AAAA", "call-1"))
        assertFalse(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, "BBBB", "call-2"))
        assertFalse(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.RFCOMM, "AAAA", "call-3"))
    }

    @Test
    fun releaseRequiresMatchingTransportAndCallId() {
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, "AAAA", "call-1"))
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.RFCOMM, "call-1")
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.GATT_P2P, "other-call")
        assertEquals("call-1", ActiveCallRegistry.current()?.callId)
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.GATT_P2P, "call-1")
        assertNull(ActiveCallRegistry.current())
    }

    @Test
    fun slotIsReusableAfterRelease() {
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.RFCOMM, "AAAA", "call-1"))
        ActiveCallRegistry.release(ActiveCallRegistry.Transport.RFCOMM, "call-1")
        assertTrue(ActiveCallRegistry.tryAcquire(ActiveCallRegistry.Transport.GATT_P2P, "BBBB", "call-2"))
    }
}
