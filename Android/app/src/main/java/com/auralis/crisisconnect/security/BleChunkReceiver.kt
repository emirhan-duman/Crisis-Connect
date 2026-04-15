package com.auralis.crisisconnect.security

import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Reassembles BLE characteristic notifications/writes into full packets.
 *
 * Legacy mode accepts the historical 2-byte length envelope.
 * Framed mode accepts chunk-level headers with a magic prefix, stream id, sequence number,
 * payload length and CRC, which makes reconnect/resync much more robust against stale chunks.
 */
class BleChunkReceiver(
    private val maxPacketSize: Int,
    private val tag: String = "BleChunkReceiver",
    private val partialResetTimeoutMs: Long = DEFAULT_PARTIAL_RESET_TIMEOUT_MS
) {
    sealed interface ChunkResult {
        data object Incomplete : ChunkResult
        data class Complete(val packet: ByteArray) : ChunkResult
        data class Rejected(val reason: String) : ChunkResult
    }

    private val legacyHeaderBuffer = ByteArrayOutputStream(Short.SIZE_BYTES)
    private val legacyPayloadBuffer = ByteArrayOutputStream()
    private var legacyExpectedLength: Int? = null

    private val framedPayloadBuffer = ByteArrayOutputStream()
    private var framedStreamId: Int? = null
    private var framedExpectedSequence: Int = 0
    private var framedExpectedLength: Int? = null
    private var framedExpectedCrc32: Long? = null
    private var lastChunkAtElapsedRealtimeMs: Long = 0L

    fun reset() {
        resetLegacyState()
        resetFramedState()
        lastChunkAtElapsedRealtimeMs = 0L
    }

    fun hasPartialData(): Boolean {
        return hasLegacyPartialData() || hasFramedPartialData()
    }

    fun hasRecentPartialData(): Boolean {
        if (!hasPartialData()) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (
            partialResetTimeoutMs > 0L &&
            lastChunkAtElapsedRealtimeMs > 0L &&
            now - lastChunkAtElapsedRealtimeMs > partialResetTimeoutMs
        ) {
            Log.w(tag, "Discarding stale partial BLE packet during readiness check after ${now - lastChunkAtElapsedRealtimeMs}ms")
            reset()
            return false
        }
        return true
    }

    fun onChunk(chunk: ByteArray): ChunkResult {
        if (chunk.isEmpty()) {
            Log.w(tag, "Ignoring empty BLE chunk")
            return ChunkResult.Rejected("empty-chunk")
        }
        val now = SystemClock.elapsedRealtime()
        val hasPartialPacket = hasPartialData()
        if (
            hasPartialPacket &&
            partialResetTimeoutMs > 0L &&
            lastChunkAtElapsedRealtimeMs > 0L &&
            now - lastChunkAtElapsedRealtimeMs > partialResetTimeoutMs
        ) {
            Log.w(tag, "Discarding stale partial BLE packet after ${now - lastChunkAtElapsedRealtimeMs}ms")
            reset()
        }
        lastChunkAtElapsedRealtimeMs = now

        if (isFramedChunk(chunk)) {
            return onFramedChunk(chunk)
        }
        if (hasFramedPartialData()) {
            Log.w(tag, "Discarding non-framed BLE chunk while framed transfer is active")
            reset()
            return ChunkResult.Rejected("unexpected-legacy-chunk-during-framed-transfer")
        }
        return onLegacyChunk(chunk)
    }

    private fun onLegacyChunk(chunk: ByteArray): ChunkResult {
        var offset = 0
        if (legacyExpectedLength == null) {
            while (legacyHeaderBuffer.size() < Short.SIZE_BYTES && offset < chunk.size) {
                legacyHeaderBuffer.write(chunk[offset].toInt())
                offset++
            }
            if (legacyHeaderBuffer.size() == Short.SIZE_BYTES) {
                val headerBytes = legacyHeaderBuffer.toByteArray()
                val declaredLength = ((headerBytes[0].toInt() and 0xFF) shl 8) or
                    (headerBytes[1].toInt() and 0xFF)
                if (declaredLength !in 1..maxPacketSize) {
                    Log.w(tag, "Discarding invalid BLE transport header length=$declaredLength")
                    reset()
                    return ChunkResult.Rejected("invalid-header:length=$declaredLength")
                }
                legacyExpectedLength = declaredLength
                if (Log.isLoggable(tag, Log.DEBUG)) {
                    Log.d(tag, "Expecting legacy $declaredLength byte packet")
                }
            }
        }

        val targetLength = legacyExpectedLength ?: return ChunkResult.Incomplete
        if (offset < chunk.size) {
            legacyPayloadBuffer.write(chunk, offset, chunk.size - offset)
        }
        val receivedBytes = legacyPayloadBuffer.size()
        if (receivedBytes > targetLength) {
            Log.w(tag, "Discarding BLE packet overflow received=$receivedBytes declared=$targetLength")
            reset()
            return ChunkResult.Rejected("overflow:received=$receivedBytes declared=$targetLength")
        }
        return if (receivedBytes == targetLength) {
            val payload = legacyPayloadBuffer.toByteArray()
            val header = legacyHeaderBuffer.toByteArray()
            val packet = ByteArray(header.size + payload.size)
            System.arraycopy(header, 0, packet, 0, header.size)
            System.arraycopy(payload, 0, packet, header.size, payload.size)
            reset()
            ChunkResult.Complete(packet)
        } else {
            ChunkResult.Incomplete
        }
    }

    fun ensureNoPartialData() {
        if (hasPartialData()) {
            throw IllegalStateException(
                "BLE transfer ended with incomplete packet: " +
                    "legacyExpected=$legacyExpectedLength legacyReceived=${legacyPayloadBuffer.size()} " +
                    "framedStream=$framedStreamId framedExpected=$framedExpectedLength framedReceived=${framedPayloadBuffer.size()}"
            )
        }
    }

    private fun onFramedChunk(chunk: ByteArray): ChunkResult {
        val control = chunk[2].toInt() and 0xFF
        val flags = control and FRAME_FLAGS_MASK
        val isFirstChunk = flags and FRAME_FLAG_FIRST != 0
        val streamId = ((chunk[3].toInt() and 0xFF) shl 8) or (chunk[4].toInt() and 0xFF)
        val sequence = ((chunk[5].toInt() and 0xFF) shl 8) or (chunk[6].toInt() and 0xFF)
        if (streamId == 0) {
            reset()
            return ChunkResult.Rejected("invalid-framed-stream-id")
        }

        val payloadOffset: Int
        if (isFirstChunk) {
            if (chunk.size < FRAME_FIRST_CHUNK_HEADER_BYTES) {
                reset()
                return ChunkResult.Rejected("short-framed-first-chunk")
            }
            if (sequence != 0) {
                reset()
                return ChunkResult.Rejected("invalid-first-sequence:$sequence")
            }
            val declaredLength = ((chunk[7].toInt() and 0xFF) shl 8) or (chunk[8].toInt() and 0xFF)
            if (declaredLength !in 1..maxPacketSize) {
                reset()
                return ChunkResult.Rejected("invalid-framed-length:$declaredLength")
            }
            resetLegacyState()
            resetFramedState()
            framedStreamId = streamId
            framedExpectedSequence = 1
            framedExpectedLength = declaredLength
            framedExpectedCrc32 = ((chunk[9].toLong() and 0xFFL) shl 24) or
                ((chunk[10].toLong() and 0xFFL) shl 16) or
                ((chunk[11].toLong() and 0xFFL) shl 8) or
                (chunk[12].toLong() and 0xFFL)
            payloadOffset = FRAME_FIRST_CHUNK_HEADER_BYTES
        } else {
            val activeStreamId = framedStreamId
                ?: return ChunkResult.Rejected("unexpected-framed-continuation")
            if (streamId != activeStreamId) {
                reset()
                return ChunkResult.Rejected("framed-stream-mismatch:expected=$activeStreamId actual=$streamId")
            }
            if (sequence != framedExpectedSequence) {
                val expected = framedExpectedSequence
                reset()
                return ChunkResult.Rejected("framed-sequence-mismatch:expected=$expected actual=$sequence")
            }
            payloadOffset = FRAME_CONTINUATION_CHUNK_HEADER_BYTES
        }

        if (payloadOffset < chunk.size) {
            framedPayloadBuffer.write(chunk, payloadOffset, chunk.size - payloadOffset)
        }
        val targetLength = framedExpectedLength ?: return ChunkResult.Incomplete
        val receivedBytes = framedPayloadBuffer.size()
        if (receivedBytes > targetLength) {
            reset()
            return ChunkResult.Rejected("framed-overflow:received=$receivedBytes declared=$targetLength")
        }
        if (receivedBytes == targetLength) {
            val payload = framedPayloadBuffer.toByteArray()
            val crc32 = CRC32().apply { update(payload) }.value
            val expectedCrc32 = framedExpectedCrc32
            if (expectedCrc32 == null || crc32 != expectedCrc32) {
                reset()
                return ChunkResult.Rejected("framed-crc-mismatch")
            }
            reset()
            return ChunkResult.Complete(payload)
        }
        if (!isFirstChunk) {
            framedExpectedSequence += 1
        }
        return ChunkResult.Incomplete
    }

    private fun isFramedChunk(chunk: ByteArray): Boolean {
        if (chunk.size < FRAME_CONTINUATION_CHUNK_HEADER_BYTES) {
            return false
        }
        if (chunk[0] != FRAME_MAGIC_HIGH || chunk[1] != FRAME_MAGIC_LOW) {
            return false
        }
        val control = chunk[2].toInt() and 0xFF
        val version = (control and FRAME_VERSION_MASK) ushr FRAME_VERSION_SHIFT
        if (version != FRAME_VERSION) {
            return false
        }
        val flags = control and FRAME_FLAGS_MASK
        return if (flags and FRAME_FLAG_FIRST != 0) {
            chunk.size >= FRAME_FIRST_CHUNK_HEADER_BYTES
        } else {
            chunk.size >= FRAME_CONTINUATION_CHUNK_HEADER_BYTES
        }
    }

    private fun hasLegacyPartialData(): Boolean {
        return legacyExpectedLength != null || legacyPayloadBuffer.size() > 0 || legacyHeaderBuffer.size() > 0
    }

    private fun hasFramedPartialData(): Boolean {
        return framedStreamId != null || framedExpectedLength != null || framedPayloadBuffer.size() > 0
    }

    private fun resetLegacyState() {
        legacyHeaderBuffer.reset()
        legacyPayloadBuffer.reset()
        legacyExpectedLength = null
    }

    private fun resetFramedState() {
        framedPayloadBuffer.reset()
        framedStreamId = null
        framedExpectedSequence = 0
        framedExpectedLength = null
        framedExpectedCrc32 = null
    }

    companion object {
        private const val DEFAULT_PARTIAL_RESET_TIMEOUT_MS = 2_000L
        private const val FRAME_VERSION = 1
        private const val FRAME_VERSION_SHIFT = 4
        private const val FRAME_VERSION_MASK = 0xF0
        private const val FRAME_FLAGS_MASK = 0x0F
        private const val FRAME_FLAG_FIRST = 0x01
        private const val FRAME_CONTINUATION_CHUNK_HEADER_BYTES = 7
        private const val FRAME_FIRST_CHUNK_HEADER_BYTES = 13
        private const val FRAME_MAGIC_HIGH: Byte = 0x44
        private const val FRAME_MAGIC_LOW: Byte = 0x43
    }
}
