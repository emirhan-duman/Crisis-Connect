package com.auralis.crisisconnect.connectivity
import org.junit.Assert.*
import org.junit.Test

class TrafficBudgetTest {
    @Test fun conservationBlocksDiscretionaryWorkButKeepsEssentialTraffic() {
        for (work in listOf(NetworkWork.MESSAGE,NetworkWork.RECEIPT,NetworkWork.SOS)) assertTrue(TrafficBudget.permits(work,true))
        for (work in listOf(NetworkWork.AVATAR,NetworkWork.MAP_DOWNLOAD,NetworkWork.TELEMETRY)) {
            assertFalse(TrafficBudget.permits(work,true,true))
            assertTrue(TrafficBudget.permits(work,false))
        }
    }
    @Test fun manualFeedRefreshIsExplicitAndAutomaticRefreshWaits() {
        assertFalse(TrafficBudget.permits(NetworkWork.FEED_REFRESH,true))
        assertTrue(TrafficBudget.permits(NetworkWork.FEED_REFRESH,true,true))
    }
    @Test fun offlineAndEmergencyAreDistinctFromRestrictedNetwork() {
        assertEquals(TrafficPresence.OFFLINE,TrafficBudget.presence(false,false,true))
        assertEquals(TrafficPresence.SOS,TrafficBudget.presence(false,true,true))
        assertEquals(TrafficPresence.LIMITED,TrafficBudget.presence(true,false,true))
        assertEquals(TrafficPresence.NORMAL,TrafficBudget.presence(true,false,false))
    }
}
