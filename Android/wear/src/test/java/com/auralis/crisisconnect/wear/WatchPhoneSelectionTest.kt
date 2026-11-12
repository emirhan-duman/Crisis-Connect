package com.auralis.crisisconnect.wear

import org.junit.Assert.*
import org.junit.Test

class WatchPhoneSelectionTest {
    @Test fun cloudConnectedPhoneRemainsUsable() {
        val remote = WatchPhoneCandidate("paired", false)
        assertEquals(remote, selectWatchPhone(listOf(remote), null, false))
        assertEquals(remote, selectWatchPhone(listOf(remote), "paired", true))
    }
    @Test fun actionCannotMoveToAnotherPhoneAfterDisconnect() {
        val other = WatchPhoneCandidate("other", true)
        assertNull(selectWatchPhone(listOf(other), "paired", true))
        assertEquals(other, selectWatchPhone(listOf(other), "paired", false))
    }
    @Test fun existingPhoneWinsOverNewNearbyPhone() {
        val paired = WatchPhoneCandidate("paired", false)
        val nearby = WatchPhoneCandidate("nearby", true)
        assertEquals(paired, selectWatchPhone(listOf(nearby, paired), "paired", false))
        assertEquals(nearby, selectWatchPhone(listOf(paired, nearby), null, false))
        assertNull(selectWatchPhone(emptyList(), "paired", false))
    }
}
