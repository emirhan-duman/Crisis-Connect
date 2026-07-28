package com.auralis.crisisconnect.service.p2p.call

import com.auralis.crisisconnect.service.p2p.P2pBleProtocol
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden vectors shared with the iOS test suite (`P2pCallProtocolTests.swift`).
 * If any of these change, the wire protocol changed and BOTH platforms must be updated.
 */
class P2pCallProtocolTest {

    private val rootKey = ByteArray(32) { it.toByte() }
    private val callId = "11111111-2222-3333-4444-555555555555"
    private val frameA = ByteArray(30) { (0xA0 + it).toByte() }
    private val frameB = ByteArray(24) { (0x50 + it).toByte() }
    private val saltA2B = (0x10..0x1f).joinToString("") { "%02x".format(it) }
    private val saltB2A = (0x20..0x2f).joinToString("") { "%02x".format(it) }

    private val goldenCallTag = "666ff6cc"
    private val goldenKeyA2B = "a16c95eb0e91a277b7f4f5cb6c7c4c89ac5d5651f696fcd4466e42bd56b095a4"
    private val goldenKeyB2A = "d85b36821e579412dcbf1e919dc4f5fc50e4d0f731912736dd1dc1e41c46fad7"
    private val goldenBundle =
        "001ea0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbd" +
            "0018505152535455565758595a5b5c5d5e5f6061626364656667"
    private val goldenPacket =
        "ca01666ff6cc0000002a78007a4e0275d6f8c230c5e74cd4c30df2e1726a311c" +
            "21cda830f6e454c645632643519b8ebb0b82dc44b6d784d0a91941fe506a0bd0" +
            "fc8a8b274a0bdee3004e433f9639d9b43ea419f6"

    @Test
    fun callTagMatchesGoldenVector() {
        assertEquals(goldenCallTag, P2pCallProtocol.deriveCallTag(callId).toHex())
    }

    @Test
    fun directionalKeysMatchGoldenVectors() {
        assertEquals(
            goldenKeyA2B,
            P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B).toHex()
        )
        assertEquals(
            goldenKeyB2A,
            P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = false, directionSaltHex = saltB2A).toHex()
        )
    }

    @Test
    fun bundlePackingMatchesGoldenVector() {
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA, frameB))
        assertEquals(goldenBundle, bundle.toHex())
        val unpacked = P2pCallProtocol.unpackFrameBundle(bundle)!!
        assertEquals(2, unpacked.size)
        assertArrayEquals(frameA, unpacked[0])
        assertArrayEquals(frameB, unpacked[1])
    }

    @Test
    fun audioFrameEncodingMatchesGoldenVector() {
        val txKey = P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B)
        val callTag = P2pCallProtocol.deriveCallTag(callId)
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA, frameB))
        val packet = P2pCallProtocol.encodeAudioFrame(txKey, callTag, seq = 42L, bundle = bundle)
        assertEquals(goldenPacket, packet.toHex())
    }

    @Test
    fun audioFrameRoundTripsAndRejectsTampering() {
        val txKey = P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B)
        val callTag = P2pCallProtocol.deriveCallTag(callId)
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA, frameB))
        val packet = P2pCallProtocol.encodeAudioFrame(txKey, callTag, seq = 42L, bundle = bundle)

        assertTrue(P2pCallProtocol.isCallAudioFrame(packet))
        val decoded = P2pCallProtocol.decodeAudioFrame(txKey, callTag, packet)!!
        assertEquals(42L, decoded.seq)
        assertArrayEquals(bundle, decoded.bundle)

        val wrongDirectionKey =
            P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = false, directionSaltHex = saltB2A)
        assertNull(P2pCallProtocol.decodeAudioFrame(wrongDirectionKey, callTag, packet))

        val wrongTag = P2pCallProtocol.deriveCallTag("some-other-call")
        assertNull(P2pCallProtocol.decodeAudioFrame(txKey, wrongTag, packet))

        val tampered = packet.copyOf().also { it[it.lastIndex] = (it[it.lastIndex] + 1).toByte() }
        assertNull(P2pCallProtocol.decodeAudioFrame(txKey, callTag, tampered))

        val truncated = packet.copyOfRange(0, P2pCallProtocol.FRAME_HEADER_BYTES + 4)
        assertNull(P2pCallProtocol.decodeAudioFrame(txKey, callTag, truncated))
    }

    @Test
    fun jsonPayloadIsNeverMistakenForAudioFrame() {
        val json = "{\"kind\":\"text\"}".toByteArray(Charsets.UTF_8)
        assertFalse(P2pCallProtocol.isCallAudioFrame(json))
        assertNull(
            P2pCallProtocol.decodeAudioFrame(
                P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B),
                P2pCallProtocol.deriveCallTag(callId),
                json
            )
        )
    }

    @Test
    fun signalCodecRoundTripsOffer() {
        val offer = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_OFFER,
            callId = callId,
            senderName = "Emirhan",
            timestampMillis = 1_751_462_400_000L,
            sampleRateHz = 16_000,
            frameMs = 20,
            framesPerPacket = 2,
            bitrateBps = 12_000
        )
        val encoded = P2pCallProtocol.encodeSignal(offer)
        assertEquals("call_offer", encoded.getString("kind"))
        assertEquals(callId, encoded.getString("callId"))
        assertEquals(16_000, encoded.getInt("sampleRate"))
        assertEquals(20, encoded.getInt("frameMs"))
        assertEquals(2, encoded.getInt("framesPerPacket"))
        assertEquals(12_000, encoded.getInt("bitrate"))
        assertEquals(offer, P2pCallProtocol.parseSignal(encoded))
    }

    @Test
    fun signalCodecRoundTripsEndAndCfgAck() {
        val end = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_END,
            callId = callId,
            reason = "hangup"
        )
        assertEquals(end, P2pCallProtocol.parseSignal(P2pCallProtocol.encodeSignal(end)))

        val cfgAck = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_CFG_ACK,
            callId = callId,
            ok = true
        )
        assertEquals(cfgAck, P2pCallProtocol.parseSignal(P2pCallProtocol.encodeSignal(cfgAck)))
    }

    @Test
    fun signalParserRejectsUnknownKindAndMissingCallId() {
        assertNull(P2pCallProtocol.parseSignal(JSONObject().put("kind", "text")))
        assertNull(
            P2pCallProtocol.parseSignal(JSONObject().put("kind", "call_offer"))
        )
        assertNull(
            P2pCallProtocol.parseSignal(
                JSONObject().put("kind", "call_offer").put("callId", "   ")
            )
        )
        assertNull(
            P2pCallProtocol.parseSignal(
                JSONObject().put("kind", "call_offer").put("callId", "x".repeat(200))
            )
        )
    }

    @Test
    fun signalCodecRoundTripsCfg() {
        val cfg = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_CFG,
            callId = callId,
            framesPerPacket = 2,
            bitrateBps = 18_000
        )
        assertEquals(cfg, P2pCallProtocol.parseSignal(P2pCallProtocol.encodeSignal(cfg)))

        val busy = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_BUSY,
            callId = callId,
            reason = "busy"
        )
        assertEquals(busy, P2pCallProtocol.parseSignal(P2pCallProtocol.encodeSignal(busy)))
    }

    @Test
    fun encodeSignalRejectsInvalidInput() {
        assertTrue(
            runCatching {
                P2pCallProtocol.encodeSignal(
                    P2pCallProtocol.CallSignal(kind = "text", callId = callId)
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                P2pCallProtocol.encodeSignal(
                    P2pCallProtocol.CallSignal(
                        kind = P2pBleProtocol.CHAT_KIND_CALL_OFFER,
                        callId = "  "
                    )
                )
            }.isFailure
        )
    }

    @Test
    fun bundleRejectsStructuralViolations() {
        // Too many frames.
        assertTrue(
            runCatching {
                P2pCallProtocol.packFrameBundle(List(P2pCallProtocol.MAX_BUNDLE_FRAMES + 1) { frameA })
            }.isFailure
        )
        // Empty frame.
        assertTrue(
            runCatching { P2pCallProtocol.packFrameBundle(listOf(ByteArray(0))) }.isFailure
        )
        // Truncated bundle: declared length exceeds remaining bytes.
        val truncated = byteArrayOf(0x00, 0x20, 0x01, 0x02)
        assertNull(P2pCallProtocol.unpackFrameBundle(truncated))
        // Zero-length frame entry.
        assertNull(P2pCallProtocol.unpackFrameBundle(byteArrayOf(0x00, 0x00)))
        // Oversized input.
        assertNull(P2pCallProtocol.unpackFrameBundle(ByteArray(P2pCallProtocol.MAX_BUNDLE_BYTES + 1)))
    }

    @Test
    fun audioFrameRoundTripsAtMaxSequence() {
        val txKey = P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B)
        val callTag = P2pCallProtocol.deriveCallTag(callId)
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA))
        val packet = P2pCallProtocol.encodeAudioFrame(
            txKey,
            callTag,
            seq = P2pCallProtocol.MAX_SEQ,
            bundle = bundle
        )
        val decoded = P2pCallProtocol.decodeAudioFrame(txKey, callTag, packet)!!
        assertEquals(P2pCallProtocol.MAX_SEQ, decoded.seq)
        assertArrayEquals(bundle, decoded.bundle)
    }

    @Test
    fun replayedFrameWithDifferentSeqFailsAuthentication() {
        // Re-tagging a captured frame with a different sequence number must fail: the seq is
        // bound both by the AAD (header) and by the nonce.
        val txKey = P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B)
        val callTag = P2pCallProtocol.deriveCallTag(callId)
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA))
        val packet = P2pCallProtocol.encodeAudioFrame(txKey, callTag, seq = 7L, bundle = bundle)
        val reSequenced = packet.copyOf().also { it[9] = 8 } // header seq 7 -> 8
        assertNull(P2pCallProtocol.decodeAudioFrame(txKey, callTag, reSequenced))
    }

    @Test
    fun differentCallsProduceUnrelatedKeysAndTags() {
        val otherCallId = "99999999-8888-7777-6666-555555555555"
        assertFalse(
            P2pCallProtocol.deriveCallTag(callId).contentEquals(
                P2pCallProtocol.deriveCallTag(otherCallId)
            )
        )
        assertFalse(
            P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B).contentEquals(
                P2pCallProtocol.deriveDirectionalKey(rootKey, otherCallId, callerToCallee = true, directionSaltHex = saltA2B)
            )
        )
        // A frame from one call never decodes in another call even under the same contact key.
        val txKey = P2pCallProtocol.deriveDirectionalKey(rootKey, callId, callerToCallee = true, directionSaltHex = saltA2B)
        val bundle = P2pCallProtocol.packFrameBundle(listOf(frameA))
        val packet = P2pCallProtocol.encodeAudioFrame(
            txKey,
            P2pCallProtocol.deriveCallTag(callId),
            seq = 1L,
            bundle = bundle
        )
        val otherRxKey = P2pCallProtocol.deriveDirectionalKey(rootKey, otherCallId, callerToCallee = true, directionSaltHex = saltA2B)
        assertNull(
            P2pCallProtocol.decodeAudioFrame(
                otherRxKey,
                P2pCallProtocol.deriveCallTag(otherCallId),
                packet
            )
        )
    }

    @Test
    fun differentSaltsProduceDifferentKeysForSameCallId() {
        // The nonce-reuse fix: even if a peer reuses a callId, a fresh per-direction salt makes
        // the derived key (and thus the whole keystream) unique per call instance.
        val keyOne = P2pCallProtocol.deriveDirectionalKey(
            rootKey, callId, callerToCallee = true, directionSaltHex = P2pCallProtocol.randomSaltHex()
        )
        val keyTwo = P2pCallProtocol.deriveDirectionalKey(
            rootKey, callId, callerToCallee = true, directionSaltHex = P2pCallProtocol.randomSaltHex()
        )
        assertFalse(keyOne.contentEquals(keyTwo))
    }

    @Test
    fun randomSaltHexIsSixteenBytes() {
        val salt = P2pCallProtocol.randomSaltHex()
        assertEquals(P2pCallProtocol.SALT_BYTES * 2, salt.length)
        assertTrue(salt.all { it in "0123456789abcdef" })
    }

    @Test
    fun signalCodecRoundTripsSalt() {
        val offer = P2pCallProtocol.CallSignal(
            kind = P2pBleProtocol.CHAT_KIND_CALL_OFFER,
            callId = callId,
            saltHex = saltA2B
        )
        val decoded = P2pCallProtocol.parseSignal(P2pCallProtocol.encodeSignal(offer))
        assertEquals(saltA2B, decoded?.saltHex)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
