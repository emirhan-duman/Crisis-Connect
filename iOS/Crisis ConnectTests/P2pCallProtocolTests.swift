//
//  P2pCallProtocolTests.swift
//  Crisis ConnectTests
//
//  Golden vectors shared with the Android test suite (`P2pCallProtocolTest.kt`).
//  If any of these change, the wire protocol changed and BOTH platforms must be updated.
//

import XCTest
import CryptoKit
@testable import Crisis_Connect

final class P2pCallProtocolTests: XCTestCase {

    private let rootKey = SymmetricKey(data: Data((0..<32).map { UInt8($0) }))
    private let callId = "11111111-2222-3333-4444-555555555555"
    private let frameA = Data((0..<30).map { UInt8(0xA0 + $0) })
    private let frameB = Data((0..<24).map { UInt8(0x50 + $0) })

    private let saltA2B = (0x10...0x1f).map { String(format: "%02x", $0) }.joined()
    private let saltB2A = (0x20...0x2f).map { String(format: "%02x", $0) }.joined()

    private let goldenCallTag = "666ff6cc"
    private let goldenKeyA2B = "a16c95eb0e91a277b7f4f5cb6c7c4c89ac5d5651f696fcd4466e42bd56b095a4"
    private let goldenKeyB2A = "d85b36821e579412dcbf1e919dc4f5fc50e4d0f731912736dd1dc1e41c46fad7"
    private let goldenBundle =
        "001ea0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbd" +
        "0018505152535455565758595a5b5c5d5e5f6061626364656667"
    private let goldenPacket =
        "ca01666ff6cc0000002a78007a4e0275d6f8c230c5e74cd4c30df2e1726a311c" +
        "21cda830f6e454c645632643519b8ebb0b82dc44b6d784d0a91941fe506a0bd0" +
        "fc8a8b274a0bdee3004e433f9639d9b43ea419f6"

    func testCallTagMatchesGoldenVector() {
        XCTAssertEqual(P2pCallProtocol.deriveCallTag(callId: callId).hexString, goldenCallTag)
    }

    func testDirectionalKeysMatchGoldenVectors() {
        let keyA2B = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let keyB2A = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: false,
            directionSaltHex: saltB2A
        )
        XCTAssertEqual(keyA2B.withUnsafeBytes { Data($0) }.hexString, goldenKeyA2B)
        XCTAssertEqual(keyB2A.withUnsafeBytes { Data($0) }.hexString, goldenKeyB2A)
    }

    func testBundlePackingMatchesGoldenVector() throws {
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA, frameB]))
        XCTAssertEqual(bundle.hexString, goldenBundle)
        let unpacked = try XCTUnwrap(P2pCallProtocol.unpackFrameBundle(bundle))
        XCTAssertEqual(unpacked.count, 2)
        XCTAssertEqual(unpacked[0], frameA)
        XCTAssertEqual(unpacked[1], frameB)
    }

    func testAudioFrameEncodingMatchesGoldenVector() throws {
        let txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let callTag = P2pCallProtocol.deriveCallTag(callId: callId)
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA, frameB]))
        let packet = try XCTUnwrap(
            P2pCallProtocol.encodeAudioFrame(txKey: txKey, callTag: callTag, seq: 42, bundle: bundle)
        )
        XCTAssertEqual(packet.hexString, goldenPacket)
    }

    func testAudioFrameRoundTripsAndRejectsTampering() throws {
        let txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let callTag = P2pCallProtocol.deriveCallTag(callId: callId)
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA, frameB]))
        let packet = try XCTUnwrap(
            P2pCallProtocol.encodeAudioFrame(txKey: txKey, callTag: callTag, seq: 42, bundle: bundle)
        )

        XCTAssertTrue(P2pCallProtocol.isCallAudioFrame(packet))
        let decoded = try XCTUnwrap(
            P2pCallProtocol.decodeAudioFrame(rxKey: txKey, expectedCallTag: callTag, packet: packet)
        )
        XCTAssertEqual(decoded.seq, 42)
        XCTAssertEqual(decoded.bundle, bundle)

        let wrongDirectionKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: false,
            directionSaltHex: saltB2A
        )
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(
                rxKey: wrongDirectionKey,
                expectedCallTag: callTag,
                packet: packet
            )
        )

        let wrongTag = P2pCallProtocol.deriveCallTag(callId: "some-other-call")
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(rxKey: txKey, expectedCallTag: wrongTag, packet: packet)
        )

        var tampered = packet
        tampered[tampered.count - 1] &+= 1
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(rxKey: txKey, expectedCallTag: callTag, packet: tampered)
        )

        let truncated = packet.prefix(P2pCallProtocol.frameHeaderBytes + 4)
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(
                rxKey: txKey,
                expectedCallTag: callTag,
                packet: Data(truncated)
            )
        )
    }

    func testJsonPayloadIsNeverMistakenForAudioFrame() {
        let json = Data("{\"kind\":\"text\"}".utf8)
        XCTAssertFalse(P2pCallProtocol.isCallAudioFrame(json))
    }

    func testSignalCodecRoundTripsOffer() throws {
        let offer = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallOffer,
            callId: callId,
            senderName: "Emirhan",
            timestampMillis: 1_751_462_400_000,
            sampleRateHz: 16_000,
            frameMs: 20,
            framesPerPacket: 2,
            bitrateBps: 12_000
        )
        let encoded = try XCTUnwrap(P2pCallProtocol.encodeSignal(offer))
        XCTAssertEqual(encoded["kind"] as? String, "call_offer")
        XCTAssertEqual(encoded["callId"] as? String, callId)
        XCTAssertEqual((encoded["sampleRate"] as? NSNumber)?.intValue, 16_000)
        XCTAssertEqual((encoded["frameMs"] as? NSNumber)?.intValue, 20)
        XCTAssertEqual((encoded["framesPerPacket"] as? NSNumber)?.intValue, 2)
        XCTAssertEqual((encoded["bitrate"] as? NSNumber)?.intValue, 12_000)
        XCTAssertEqual(P2pCallProtocol.parseSignal(encoded), offer)
    }

    func testSignalCodecRoundTripsEndAndCfgAck() throws {
        let end = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallEnd,
            callId: callId,
            reason: "hangup"
        )
        let encodedEnd = try XCTUnwrap(P2pCallProtocol.encodeSignal(end))
        XCTAssertEqual(P2pCallProtocol.parseSignal(encodedEnd), end)

        let cfgAck = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallCfgAck,
            callId: callId,
            ok: true
        )
        let encodedAck = try XCTUnwrap(P2pCallProtocol.encodeSignal(cfgAck))
        XCTAssertEqual(P2pCallProtocol.parseSignal(encodedAck), cfgAck)
    }

    func testSignalParserRejectsUnknownKindAndMissingCallId() {
        XCTAssertNil(P2pCallProtocol.parseSignal(["kind": "text", "callId": callId]))
        XCTAssertNil(P2pCallProtocol.parseSignal(["kind": "call_offer"]))
        XCTAssertNil(P2pCallProtocol.parseSignal(["kind": "call_offer", "callId": "   "]))
        XCTAssertNil(
            P2pCallProtocol.parseSignal(
                ["kind": "call_offer", "callId": String(repeating: "x", count: 200)]
            )
        )
    }

    func testSignalCodecRoundTripsCfgAndBusy() throws {
        let cfg = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallCfg,
            callId: callId,
            framesPerPacket: 2,
            bitrateBps: 18_000
        )
        let encodedCfg = try XCTUnwrap(P2pCallProtocol.encodeSignal(cfg))
        XCTAssertEqual(P2pCallProtocol.parseSignal(encodedCfg), cfg)

        let busy = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallBusy,
            callId: callId,
            reason: "busy"
        )
        let encodedBusy = try XCTUnwrap(P2pCallProtocol.encodeSignal(busy))
        XCTAssertEqual(P2pCallProtocol.parseSignal(encodedBusy), busy)
    }

    func testEncodeSignalRejectsInvalidInput() {
        XCTAssertNil(P2pCallProtocol.encodeSignal(P2pCallSignal(kind: "text", callId: callId)))
        XCTAssertNil(
            P2pCallProtocol.encodeSignal(
                P2pCallSignal(kind: P2pBleProtocol.chatKindCallOffer, callId: "")
            )
        )
    }

    func testBundleRejectsStructuralViolations() {
        // Too many frames.
        XCTAssertNil(
            P2pCallProtocol.packFrameBundle(
                Array(repeating: frameA, count: P2pCallProtocol.maxBundleFrames + 1)
            )
        )
        // Empty frame.
        XCTAssertNil(P2pCallProtocol.packFrameBundle([Data()]))
        // Truncated bundle: declared length exceeds remaining bytes.
        XCTAssertNil(P2pCallProtocol.unpackFrameBundle(Data([0x00, 0x20, 0x01, 0x02])))
        // Zero-length frame entry.
        XCTAssertNil(P2pCallProtocol.unpackFrameBundle(Data([0x00, 0x00])))
        // Oversized input.
        XCTAssertNil(
            P2pCallProtocol.unpackFrameBundle(Data(count: P2pCallProtocol.maxBundleBytes + 1))
        )
    }

    func testAudioFrameRoundTripsAtMaxSequence() throws {
        let txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let callTag = P2pCallProtocol.deriveCallTag(callId: callId)
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA]))
        let packet = try XCTUnwrap(
            P2pCallProtocol.encodeAudioFrame(
                txKey: txKey,
                callTag: callTag,
                seq: UInt32.max,
                bundle: bundle
            )
        )
        let decoded = try XCTUnwrap(
            P2pCallProtocol.decodeAudioFrame(rxKey: txKey, expectedCallTag: callTag, packet: packet)
        )
        XCTAssertEqual(decoded.seq, UInt32.max)
        XCTAssertEqual(decoded.bundle, bundle)
    }

    func testReplayedFrameWithDifferentSeqFailsAuthentication() throws {
        // Re-tagging a captured frame with a different sequence number must fail: the seq is
        // bound both by the AAD (header) and by the nonce.
        let txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let callTag = P2pCallProtocol.deriveCallTag(callId: callId)
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA]))
        let packet = try XCTUnwrap(
            P2pCallProtocol.encodeAudioFrame(txKey: txKey, callTag: callTag, seq: 7, bundle: bundle)
        )
        var reSequenced = packet
        reSequenced[9] = 8 // header seq 7 -> 8
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(rxKey: txKey, expectedCallTag: callTag, packet: reSequenced)
        )
    }

    func testDifferentCallsProduceUnrelatedKeysAndTags() throws {
        let otherCallId = "99999999-8888-7777-6666-555555555555"
        XCTAssertNotEqual(
            P2pCallProtocol.deriveCallTag(callId: callId),
            P2pCallProtocol.deriveCallTag(callId: otherCallId)
        )
        let txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        let bundle = try XCTUnwrap(P2pCallProtocol.packFrameBundle([frameA]))
        let packet = try XCTUnwrap(
            P2pCallProtocol.encodeAudioFrame(
                txKey: txKey,
                callTag: P2pCallProtocol.deriveCallTag(callId: callId),
                seq: 1,
                bundle: bundle
            )
        )
        let otherRxKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: otherCallId,
            callerToCallee: true,
            directionSaltHex: saltA2B
        )
        XCTAssertNil(
            P2pCallProtocol.decodeAudioFrame(
                rxKey: otherRxKey,
                expectedCallTag: P2pCallProtocol.deriveCallTag(callId: otherCallId),
                packet: packet
            )
        )
    }

    func testSignalJsonSurvivesSerializationRoundTrip() throws {
        let offer = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallOffer,
            callId: callId,
            timestampMillis: 1_751_462_400_000,
            sampleRateHz: 16_000,
            frameMs: 20,
            framesPerPacket: 2,
            bitrateBps: 12_000
        )
        let encoded = try XCTUnwrap(P2pCallProtocol.encodeSignal(offer))
        let data = try JSONSerialization.data(withJSONObject: encoded, options: [])
        let decoded = try XCTUnwrap(
            JSONSerialization.jsonObject(with: data) as? [String: Any]
        )
        XCTAssertEqual(P2pCallProtocol.parseSignal(decoded), offer)
    }

    func testDifferentSaltsProduceDifferentKeysForSameCallId() {
        // The nonce-reuse fix: even if a peer reuses a callId, a fresh per-direction salt makes
        // the derived key (and thus the whole keystream) unique per call instance.
        let keyOne = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: P2pCallProtocol.randomSaltHex()
        )
        let keyTwo = P2pCallProtocol.deriveDirectionalKey(
            contactKey: rootKey,
            callId: callId,
            callerToCallee: true,
            directionSaltHex: P2pCallProtocol.randomSaltHex()
        )
        XCTAssertNotEqual(
            keyOne.withUnsafeBytes { Data($0) },
            keyTwo.withUnsafeBytes { Data($0) }
        )
    }

    func testRandomSaltHexIsSixteenBytes() {
        let salt = P2pCallProtocol.randomSaltHex()
        XCTAssertEqual(salt.count, P2pCallProtocol.saltBytes * 2)
        XCTAssertTrue(salt.allSatisfy { "0123456789abcdef".contains($0) })
    }

    func testSignalCodecRoundTripsSalt() throws {
        let offer = P2pCallSignal(
            kind: P2pBleProtocol.chatKindCallOffer,
            callId: callId,
            saltHex: saltA2B
        )
        let encoded = try XCTUnwrap(P2pCallProtocol.encodeSignal(offer))
        XCTAssertEqual(P2pCallProtocol.parseSignal(encoded)?.saltHex, saltA2B)
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
