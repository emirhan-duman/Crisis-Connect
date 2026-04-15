package com.auralis.crisisconnect.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BleVoicePayloadTest {

    @Test
    fun `buildPackets returns empty for invalid input`() {
        assertTrue(BleVoicePayload.buildPackets("", "audio/mp4", 1000L, byteArrayOf(1)).isEmpty())
        assertTrue(BleVoicePayload.buildPackets("t1", "audio/mp4", 1000L, ByteArray(0)).isEmpty())
        assertTrue(
            BleVoicePayload.buildPackets(
                "t1",
                "audio/mp4",
                1000L,
                ByteArray(BleVoicePayload.MAX_OUTGOING_TOTAL_BYTES + 1)
            ).isEmpty()
        )
    }

    @Test
    fun `buildPackets emits init plus chunk packets and can be reconstructed`() {
        val original = ByteArray(BleVoicePayload.OUTGOING_CHUNK_SIZE_BYTES + 321) { index ->
            (index % 251).toByte()
        }

        val packets = BleVoicePayload.buildPackets(
            transferId = "transfer-1",
            mimeType = " audio/aac ",
            durationMillis = 1_250L,
            bytes = original
        )

        assertEquals(3, packets.size)
        val init = BleVoicePayload.parsePacket(packets.first())
        assertTrue(init is BleVoicePayload.Packet.Init)
        init as BleVoicePayload.Packet.Init
        assertEquals("transfer-1", init.transferId)
        assertEquals("audio/aac", init.mimeType)
        assertEquals(1_250L, init.durationMillis)
        assertEquals(2, init.totalChunks)

        val transfer = BleVoicePayload.IncomingTransfer(
            transferId = init.transferId,
            mimeType = init.mimeType,
            durationMillis = init.durationMillis,
            totalChunks = init.totalChunks
        )
        packets.drop(1).forEach { raw ->
            val chunk = BleVoicePayload.parsePacket(raw)
            assertTrue(chunk is BleVoicePayload.Packet.Chunk)
            chunk as BleVoicePayload.Packet.Chunk
            assertTrue(transfer.addChunk(chunk.chunkIndex, chunk.bytes))
        }

        assertTrue(transfer.isComplete())
        assertArrayEquals(original, transfer.composeBytes())
    }

    @Test
    fun `parsePacket returns null for unknown prefix`() {
        assertNull(BleVoicePayload.parsePacket("UNKNOWN|payload"))
    }

    @Test
    fun `parse init validates chunk count boundaries`() {
        assertNull(BleVoicePayload.parsePacket("CC_VOICE_INIT|id|audio/mp4|100|0"))
        assertNull(BleVoicePayload.parsePacket("CC_VOICE_INIT|id|audio/mp4|100|2049"))
    }

    @Test
    fun `parse chunk rejects malformed content`() {
        assertNull(BleVoicePayload.parsePacket("CC_VOICE_CHUNK|id|0|"))
        assertNull(BleVoicePayload.parsePacket("CC_VOICE_CHUNK|id|x|YWJj"))
        assertNull(
            BleVoicePayload.parsePacket(
                "CC_VOICE_CHUNK|id|0|${"a".repeat(16_001)}"
            )
        )
    }

    @Test
    fun `buildDonePacket trims id and parse done resolves transfer id`() {
        assertEquals("", BleVoicePayload.buildDonePacket("   "))
        val packet = BleVoicePayload.buildDonePacket(" done-1 ")
        val parsed = BleVoicePayload.parsePacket(packet)
        assertTrue(parsed is BleVoicePayload.Packet.Done)
        parsed as BleVoicePayload.Packet.Done
        assertEquals("done-1", parsed.transferId)
    }

    @Test
    fun `buildAbortPacket sanitizes and truncates reason`() {
        val reason = "a".repeat(70) + "|bad"
        val packet = BleVoicePayload.buildAbortPacket(" tx-9 ", reason)
        val parsed = BleVoicePayload.parsePacket(packet)
        assertTrue(parsed is BleVoicePayload.Packet.Abort)
        parsed as BleVoicePayload.Packet.Abort
        assertEquals("tx-9", parsed.transferId)
        assertNotNull(parsed.reason)
        parsed.reason ?: return
        assertTrue(parsed.reason.length <= 64)
        assertTrue(!parsed.reason.contains('|'))
    }

    @Test
    fun `buildAbortPacket omits empty reason`() {
        val packet = BleVoicePayload.buildAbortPacket("tx-10", "   ")
        val parsed = BleVoicePayload.parsePacket(packet)
        assertTrue(parsed is BleVoicePayload.Packet.Abort)
        parsed as BleVoicePayload.Packet.Abort
        assertEquals("tx-10", parsed.transferId)
        assertNull(parsed.reason)
    }

    @Test
    fun `incoming transfer addChunk enforces index bounds and accepts duplicates safely`() {
        val transfer = BleVoicePayload.IncomingTransfer(
            transferId = "t-1",
            mimeType = "audio/mp4",
            durationMillis = 0L,
            totalChunks = 2
        )

        assertTrue(transfer.addChunk(0, byteArrayOf(1, 2)))
        assertTrue(transfer.addChunk(0, byteArrayOf(9, 9)))
        assertTrue(!transfer.addChunk(-1, byteArrayOf(1)))
        assertTrue(!transfer.addChunk(2, byteArrayOf(1)))
        assertTrue(!transfer.isComplete())

        assertTrue(transfer.addChunk(1, byteArrayOf(3)))
        assertTrue(transfer.isComplete())
        assertArrayEquals(byteArrayOf(1, 2, 3), transfer.composeBytes())
    }

    @Test
    fun `incoming transfer composeBytes returns null while incomplete`() {
        val transfer = BleVoicePayload.IncomingTransfer(
            transferId = "t-2",
            mimeType = "audio/mp4",
            durationMillis = 0L,
            totalChunks = 2
        )
        transfer.addChunk(0, byteArrayOf(1))
        assertNull(transfer.composeBytes())
    }
}
