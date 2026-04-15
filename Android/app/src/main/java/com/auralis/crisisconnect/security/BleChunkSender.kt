package com.auralis.crisisconnect.security

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Handles BLE write chunking. Callers can stay on legacy 20-byte chunks or opt into larger
 * MTU-sized payloads; retries are automatically attempted with minimal pacing between writes.
 */
class BleChunkSender(
    private val chunkWriter: ChunkWriter,
    private val maxChunkSize: Int = DEFAULT_BLE_CHUNK_SIZE,
    private val interChunkDelayMs: Long = 8L,
    private val maxRetries: Int = 3,
    private val retryBackoffBaseMs: Long = 12L,
    private val tag: String = "BleChunkSender"
) {

    init {
        require(maxChunkSize in 1..MAX_ALLOWED_BLE_CHUNK_SIZE) {
            "maxChunkSize must be between 1 and $MAX_ALLOWED_BLE_CHUNK_SIZE bytes"
        }
    }

    suspend fun sendPacket(packet: ByteArray) {
        require(packet.isNotEmpty()) { "Cannot send an empty packet" }
        val chunkSize = maxChunkSize.coerceAtMost(MAX_ALLOWED_BLE_CHUNK_SIZE)
        var offset = 0
        var chunkIndex = 0
        while (offset < packet.size) {
            val end = (offset + chunkSize).coerceAtMost(packet.size)
            val chunk = packet.copyOfRange(offset, end)
            writeChunkWithRetry(chunkIndex, chunk)
            offset = end
            chunkIndex++
            if (offset < packet.size) {
                delay(interChunkDelayMs)
            }
        }
    }

    private suspend fun writeChunkWithRetry(index: Int, chunk: ByteArray) {
        var attempt = 0
        while (attempt <= maxRetries) {
            attempt++
            val success = chunkWriter.write(chunk)
            if (success) {
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, "Chunk #$index (${chunk.size} bytes) written successfully on attempt $attempt")
                }
                return
            }
            if (attempt > maxRetries) {
                break
            }
            val backoff = retryBackoffBaseMs * attempt
            Log.w(tag, "Retrying chunk #$index (attempt $attempt) after ${backoff}ms")
            delay(backoff)
        }
        throw IllegalStateException("Failed to write BLE chunk #$index after ${maxRetries + 1} attempts")
    }

    fun interface ChunkWriter {
        suspend fun write(chunk: ByteArray): Boolean
    }

    companion object {
        private const val DEFAULT_BLE_CHUNK_SIZE = 20
        private const val MAX_ALLOWED_BLE_CHUNK_SIZE = 512

        /**
         * Convenience factory that creates a [BleChunkSender] from a [BluetoothGatt] and
         * [BluetoothGattCharacteristic]. The caller must supply an awaiter that suspends until
         * `onCharacteristicWrite` signals success.
         */
        fun fromGatt(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            awaiter: suspend () -> Boolean,
            maxChunkSize: Int = DEFAULT_BLE_CHUNK_SIZE,
            interChunkDelayMs: Long = 8L,
            maxRetries: Int = 3
        ): BleChunkSender {
            val writer = ChunkWriter { chunk ->
                characteristic.value = chunk
                val writeStarted = try {
                    gatt.writeCharacteristic(characteristic)
                } catch (exception: SecurityException) {
                    Log.w(
                        "BleChunkSender",
                        "BLE write failed due to missing Bluetooth permission",
                        exception
                    )
                    false
                }
                if (!writeStarted) {
                    return@ChunkWriter false
                }
                awaiter()
            }
            return BleChunkSender(
                chunkWriter = writer,
                maxChunkSize = maxChunkSize,
                interChunkDelayMs = interChunkDelayMs,
                maxRetries = maxRetries
            )
        }
    }
}
