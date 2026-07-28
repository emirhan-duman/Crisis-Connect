//
//  SfuFoundationTests.swift
//  Crisis ConnectTests
//
//  Locks the SFU group-call wire contracts shared with web/Android: the Orange typed-array MLS
//  handshake framing (byte-exact — a mismatch deadlocks the group handshake cross-platform),
//  the safety-number display format, and the /api/realtime proxy error contract.
//

import XCTest
@testable import Crisis_Connect

final class SfuFoundationTests: XCTestCase {

    // MARK: - MLS handshake codec

    func testShareKeyPackageEncodingMatchesOrangeFraming() throws {
        let json = try XCTUnwrap(
            MlsHandshakeCodec.encode(.shareKeyPackage(keyPkg: Data([0, 1, 127, 128, 255])))
        )
        let object = try XCTUnwrap(
            JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any]
        )
        XCTAssertEqual(object["type"] as? String, "shareKeyPackage")
        let field = try XCTUnwrap(object["keyPkg"] as? [String: Any])
        // ArrayBuffer dialect — the one the web wasm worker accepts (typed-array kills it).
        XCTAssertEqual(field["FLAG_ARRAY_BUFFER"] as? Bool, true)
        XCTAssertEqual((field["data"] as? [Int]), [0, 1, 127, 128, 255])
    }

    func testRoundTripAllMessageTypes() throws {
        let messages: [MlsHandshakeCodec.Message] = [
            .shareKeyPackage(keyPkg: Data((0...255).map(UInt8.init))),
            .sendMlsMessage(msg: Data([9, 8, 7]), senderId: "uid-android"),
            .sendMlsWelcome(senderId: "uid-web", welcome: Data([1, 2]), rtree: Data([3, 4, 5]))
        ]
        for message in messages {
            let encoded = try XCTUnwrap(MlsHandshakeCodec.encode(message))
            XCTAssertEqual(MlsHandshakeCodec.decode(encoded), message)
        }
    }

    func testDecodesWebArrayBufferFlaggedField() {
        // The web can frame bytes as an ArrayBuffer instead of a Uint8Array.
        let json = """
        {"type":"shareKeyPackage","keyPkg":{"FLAG_ARRAY_BUFFER":true,"data":[10,20,30]}}
        """
        XCTAssertEqual(
            MlsHandshakeCodec.decode(json),
            .shareKeyPackage(keyPkg: Data([10, 20, 30]))
        )
    }

    func testUnknownAndLocalOnlyTypesDecodeToNil() {
        XCTAssertNil(MlsHandshakeCodec.decode("{\"type\":\"newSafetyNumber\"}"))
        XCTAssertNil(MlsHandshakeCodec.decode("{\"type\":\"workerReady\"}"))
        XCTAssertNil(MlsHandshakeCodec.decode("not json"))
        // A byte field without the Orange flag must be rejected, not misparsed.
        XCTAssertNil(MlsHandshakeCodec.decode("{\"type\":\"shareKeyPackage\",\"keyPkg\":{\"data\":[1]}}"))
    }

    func testSafetyNumberFormatting() {
        // Mirrors Android's padStart(3, '0') concatenation.
        XCTAssertEqual(MlsHandshakeCodec.formatSafetyNumber([1, 23, 255, 0]), "001023255000")
    }

    // MARK: - MlsSession worker-response contract

    func testWorkerResponseParsesBroadcastAndSafetyNumber() throws {
        let parsed = MlsSession.parseWorkerResponse("""
        {"broadcast":[{"type":"shareKeyPackage","keyPkg":{"FLAG_TYPED_ARRAY":true,"data":[7]}}],
         "safetyNumber":[42,7,199]}
        """)
        XCTAssertEqual(parsed.safetyNumber, "042007199")
        XCTAssertEqual(parsed.broadcast.count, 1)
        // The re-framed broadcast payload must still decode as a valid handshake message.
        XCTAssertEqual(
            MlsHandshakeCodec.decode(try XCTUnwrap(parsed.broadcast.first)),
            .shareKeyPackage(keyPkg: Data([7]))
        )
    }

    // MARK: - /api/realtime proxy error contract

    func testValidateAcceptsCleanSuccess() throws {
        let json = try SfuApiClient.validate(statusCode: 200, json: ["sessionId": "abc"])
        XCTAssertEqual(json["sessionId"] as? String, "abc")
    }

    func testValidateRejectsErrorCodeEvenOn200() {
        XCTAssertThrowsError(
            try SfuApiClient.validate(
                statusCode: 200,
                json: ["errorCode": "X", "errorDescription": "bad track"]
            )
        ) { error in
            XCTAssertEqual(error as? SfuApiError, .requestFailed("bad track"))
        }
    }

    func testValidateRejectsNon2xxWithHttpDetail() {
        XCTAssertThrowsError(
            try SfuApiClient.validate(statusCode: 502, json: [:])
        ) { error in
            XCTAssertEqual(error as? SfuApiError, .requestFailed("HTTP 502"))
        }
    }
}
