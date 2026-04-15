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
class BleImagePayloadTest {

    @Test
    fun `buildPackets returns empty for invalid input`() {
        assertTrue(
            BleImagePayload.buildPackets(
                transferId = "",
                messageId = "m1",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                bytes = byteArrayOf(1)
            ).isEmpty()
        )
        assertTrue(
            BleImagePayload.buildPackets(
                transferId = "t1",
                messageId = "",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                bytes = byteArrayOf(1)
            ).isEmpty()
        )
        assertTrue(
            BleImagePayload.buildPackets(
                transferId = "t1",
                messageId = "m1",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                bytes = ByteArray(0)
            ).isEmpty()
        )
        assertTrue(
            BleImagePayload.buildPackets(
                transferId = "t1",
                messageId = "m1",
                mimeType = "image/jpeg",
                width = 10,
                height = 10,
                bytes = ByteArray(BleImagePayload.MAX_OUTGOING_TOTAL_BYTES + 1)
            ).isEmpty()
        )
    }

    @Test
    fun `buildPackets emits init plus chunks and preserves metadata`() {
        val original = ByteArray(BleImagePayload.OUTGOING_CHUNK_SIZE_BYTES + 513) { index ->
            (index % 251).toByte()
        }

        val packets = BleImagePayload.buildPackets(
            transferId = "transfer-1",
            messageId = "message-1",
            mimeType = " image/jpeg ",
            width = 1280,
            height = 720,
            bytes = original
        )

        assertEquals(3, packets.size)
        val init = BleImagePayload.parsePacket(packets.first())
        assertTrue(init is BleImagePayload.Packet.Init)
        init as BleImagePayload.Packet.Init
        assertEquals("transfer-1", init.transferId)
        assertEquals("message-1", init.messageId)
        assertEquals("image/jpeg", init.mimeType)
        assertEquals(1280, init.width)
        assertEquals(720, init.height)
        assertEquals(original.size, init.totalBytes)
        assertEquals(2, init.totalChunks)
        assertEquals(32, init.sha256.size)

        val transfer = BleImagePayload.IncomingTransfer(
            transferId = init.transferId,
            messageId = init.messageId,
            mimeType = init.mimeType,
            width = init.width,
            height = init.height,
            totalBytes = init.totalBytes,
            totalChunks = init.totalChunks,
            sha256 = init.sha256
        )
        packets.drop(1).forEach { raw ->
            val chunk = BleImagePayload.parsePacket(raw)
            assertTrue(chunk is BleImagePayload.Packet.Chunk)
            chunk as BleImagePayload.Packet.Chunk
            assertTrue(transfer.addChunk(chunk.chunkIndex, chunk.bytes))
        }

        assertTrue(transfer.isComplete())
        assertArrayEquals(original, transfer.composeBytes())
    }

    @Test
    fun `parse init rejects malformed sha and bounds`() {
        assertNull(BleImagePayload.parsePacket("CC_IMAGE_INIT|t|m|image/jpeg|1|1|0|1|abcd"))
        assertNull(BleImagePayload.parsePacket("CC_IMAGE_INIT|t|m|image/jpeg|1|1|10|0|abcd"))
    }

    @Test
    fun `parse chunk rejects malformed content`() {
        assertNull(BleImagePayload.parsePacket("CC_IMAGE_CHUNK|id|0|"))
        assertNull(BleImagePayload.parsePacket("CC_IMAGE_CHUNK|id|x|YWJj"))
        assertNull(
            BleImagePayload.parsePacket(
                "CC_IMAGE_CHUNK|id|0|${"a".repeat(16_385)}"
            )
        )
    }

    @Test
    fun `done and abort round trip`() {
        val done = BleImagePayload.parsePacket(BleImagePayload.buildDonePacket(" tx-1 "))
        assertTrue(done is BleImagePayload.Packet.Done)
        done as BleImagePayload.Packet.Done
        assertEquals("tx-1", done.transferId)

        val abort = BleImagePayload.parsePacket(
            BleImagePayload.buildAbortPacket(" tx-2 ", "a".repeat(70) + "|bad")
        )
        assertTrue(abort is BleImagePayload.Packet.Abort)
        abort as BleImagePayload.Packet.Abort
        assertEquals("tx-2", abort.transferId)
        assertNotNull(abort.reason)
        assertTrue(abort.reason!!.length <= 64)
        assertTrue(!abort.reason!!.contains('|'))
    }
}
