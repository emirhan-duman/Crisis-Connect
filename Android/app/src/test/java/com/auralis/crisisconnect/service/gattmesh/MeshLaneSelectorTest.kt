package com.auralis.crisisconnect.service.gattmesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshLaneSelectorTest {

    @Test
    fun `tiny blobs ride BLE alone`() {
        assertFalse(MeshLaneSelector.shouldUseFastLane(0))
        assertFalse(MeshLaneSelector.shouldUseFastLane(1_024))
        assertFalse(MeshLaneSelector.shouldUseFastLane(MeshLaneSelector.MIN_FAST_LANE_BLOB_BYTES - 1))
    }

    @Test
    fun `blobs at or above the threshold also use the Wi-Fi fast lane`() {
        assertTrue(MeshLaneSelector.shouldUseFastLane(MeshLaneSelector.MIN_FAST_LANE_BLOB_BYTES))
        assertTrue(MeshLaneSelector.shouldUseFastLane(400_000))
    }
}
