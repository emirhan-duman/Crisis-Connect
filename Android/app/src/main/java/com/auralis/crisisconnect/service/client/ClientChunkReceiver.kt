package com.auralis.crisisconnect.service.client

import android.util.Log
import com.auralis.crisisconnect.security.BleChunkReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Wraps [BleChunkReceiver] so each connection can reassemble secure chat packets without sharing
 * state across devices. Incoming packets are dispatched to the provided [onPacketReady] callback
 * on the supplied [scope] to keep Bluetooth threads responsive.
 */
class ClientChunkReceiver(
    maxPacketSize: Int,
    private val scope: CoroutineScope,
    private val onPacketReady: suspend (ByteArray) -> Unit,
    private val tag: String = "ClientChunkReceiver",
) {
    private val receiver = BleChunkReceiver(maxPacketSize, tag)

    fun reset() {
        receiver.reset()
    }

    fun onChunk(chunk: ByteArray) {
        val packet = try {
            when (val result = receiver.onChunk(chunk)) {
                BleChunkReceiver.ChunkResult.Incomplete -> null
                is BleChunkReceiver.ChunkResult.Complete -> result.packet
                is BleChunkReceiver.ChunkResult.Rejected -> {
                    Log.w(tag, "Rejecting inbound BLE chunk reason=${result.reason}")
                    receiver.reset()
                    null
                }
            }
        } catch (throwable: Throwable) {
            Log.w(tag, "Dropping malformed BLE chunk", throwable)
            receiver.reset()
            null
        }
        if (packet != null) {
            scope.launch {
                onPacketReady(packet)
            }
        }
    }
}
