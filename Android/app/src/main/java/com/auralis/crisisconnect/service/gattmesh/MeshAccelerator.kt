package com.auralis.crisisconnect.service.gattmesh

/**
 * A Wi-Fi-class "fast lane" for authority-mesh media blobs.
 *
 * BLE GATT is always the universal baseline; an accelerator is an *optional* faster transport (Wi-Fi
 * Aware, Wi-Fi Direct, …) that pushes the SAME group-key-encrypted blob over a higher-bandwidth link.
 * Receivers dedupe by blob id, so whichever lane arrives first wins and the others are dropped —
 * which makes every accelerator safe to add: if it never connects, BLE still delivers.
 *
 * Implementations must be self-guarding: [offerBlob] is a no-op when the lane has no open peers, and
 * [start] must quietly bail when the lane is unsupported / unprovisioned. See
 * [AuthorityMeshAwareAccelerator] for the reference implementation.
 */
internal interface MeshAccelerator {
    /** Stable short id for logging and mutual-exclusion checks (e.g. "aware", "wifi-direct"). */
    val laneId: String

    /** Whether this device can run the lane at all (radio feature + API level present). */
    fun isSupported(): Boolean

    /** Brings the lane up (discovery + datapath). Must be cheap/idempotent and fail soft. */
    fun start()

    /** Tears the lane down and releases all radio/socket resources. */
    fun stop()

    /** True once at least one fast-lane peer socket is open. */
    fun hasFastPeers(): Boolean

    /** Fire-and-forget push of one encrypted blob to every open peer on this lane. */
    fun offerBlob(initPacketPayload: ByteArray, cipher: ByteArray)
}
