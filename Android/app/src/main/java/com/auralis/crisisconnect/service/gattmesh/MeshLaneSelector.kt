package com.auralis.crisisconnect.service.gattmesh

/**
 * Pure policy for *whether a given blob should also ride the Wi-Fi fast lanes* (Aware/Direct), on top
 * of the always-on BLE baseline. Kept side-effect-free so the decision is unit-testable.
 *
 * Rationale: BLE GATT delivers small blobs in well under a second, so duplicating a tiny payload onto
 * a Wi-Fi lane only burns a redundant radio write for no real latency win (receivers dedupe anyway).
 * Larger blobs are where the Wi-Fi lane's bandwidth actually pays off. BLE is always used regardless;
 * this only gates the *extra* fast-lane copy.
 *
 * The threshold is deliberately conservative and tunable; a future refinement can fold in measured
 * link quality (BLE RSSI / fast-lane throughput) to pick lanes per-peer rather than per-size.
 */
internal object MeshLaneSelector {

    /** Blobs at or above this size are also pushed onto the Wi-Fi fast lanes. */
    const val MIN_FAST_LANE_BLOB_BYTES = 16 * 1024

    fun shouldUseFastLane(blobSizeBytes: Int): Boolean = blobSizeBytes >= MIN_FAST_LANE_BLOB_BYTES
}
