package com.auralis.crisisconnect.service.gattmesh

import android.util.Log

/**
 * Fronts the set of optional [MeshAccelerator] fast lanes (Wi-Fi Aware, Wi-Fi Direct, …) behind the
 * single surface the mesh service already used for the lone Aware accelerator: [start]/[stop]/
 * [hasFastPeers]/[offerBlob]. BLE GATT remains the universal baseline outside this coordinator.
 *
 * Every supported lane is started; a blob is offered to every lane only when [MeshLaneSelector]
 * judges the fast lanes worthwhile for that payload (small blobs ride BLE alone). Each lane also
 * self-guards when it has no open peers, and receivers dedupe by blob id. Aware↔Direct mutual
 * exclusion lives inside the lanes ([WifiDirectAccelerator] defers to Aware).
 */
internal class MeshAcceleratorCoordinator(
    private val accelerators: List<MeshAccelerator>
) {

    fun start() {
        accelerators.forEach { lane ->
            if (!lane.isSupported()) {
                return@forEach
            }
            runCatching { lane.start() }.onFailure { throwable ->
                Log.w(TAG, "Lane '${lane.laneId}' failed to start", throwable)
            }
        }
    }

    fun stop() {
        accelerators.forEach { lane ->
            runCatching { lane.stop() }.onFailure { throwable ->
                Log.w(TAG, "Lane '${lane.laneId}' failed to stop", throwable)
            }
        }
    }

    fun hasFastPeers(): Boolean = accelerators.any { it.hasFastPeers() }

    /**
     * Pushes one encrypted blob onto the fast lanes when [MeshLaneSelector] deems them worthwhile for
     * this payload size; each lane still no-ops when it has no peers. BLE remains the baseline outside
     * this coordinator, so a skipped fast-lane copy never loses the message.
     */
    fun offerBlob(initPacketPayload: ByteArray, cipher: ByteArray) {
        if (!MeshLaneSelector.shouldUseFastLane(cipher.size)) {
            return
        }
        accelerators.forEach { lane ->
            runCatching { lane.offerBlob(initPacketPayload, cipher) }.onFailure { throwable ->
                Log.w(TAG, "Lane '${lane.laneId}' offerBlob failed", throwable)
            }
        }
    }

    private companion object {
        private const val TAG = "MeshAccelCoordinator"
    }
}
