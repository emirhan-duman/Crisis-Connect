//
//  CrisisSentinelOnlineStreamTests.swift
//  Crisis ConnectTests
//
//  The SSE event parser must accumulate text deltas, resolve the metadata provider id, surface the
//  done payload (card / map points / unsupported tools / model), and map error events + HTTP codes
//  to the right taxonomy — byte-parity with Android's readStream / finishFromDone / mapStatus.
//

import XCTest
@testable import Crisis_Connect

final class CrisisSentinelOnlineStreamTests: XCTestCase {

    /// Feed an SSE transcript line by line; returns the done result or throws the error event.
    private func run(_ lines: [String]) throws -> CrisisSentinelOnlineStreamResult? {
        var parser = SSEEventParser()
        var lastAccumulated: String?
        for line in lines {
            switch try parser.consume(line: line) {
            case .none: break
            case .delta(let acc): lastAccumulated = acc
            case .done(let result): return result
            }
        }
        // EOF path.
        _ = lastAccumulated
        return parser.partialResult()
    }

    func testAccumulatesDeltasAndFinishesOnDone() throws {
        let lines = [
            "event:metadata", #"data:{"_provider":{"id":"gemini"}}"#, "",
            "event:text_delta", #"data:{"delta":"Merhaba "}"#, "",
            "event:text_delta", #"data:{"delta":"dünya"}"#, "",
            "event:done", #"data:{"data":{"model":"gemini-2.0"}}"#, "",
        ]
        let result = try XCTUnwrap(try run(lines))
        XCTAssertEqual(result.text, "Merhaba dünya")
        XCTAssertEqual(result.modelName, "gemini-2.0")
    }

    func testDonePayloadCarriesCardAndMapPoints() throws {
        let done = #"""
        data:{"data":{"_card":{"kind":"weather","weather":{"tempC":21}},"candidates":[{"content":{"parts":[{"functionCall":{"name":"showLocationOnMap","args":{"points":[{"lat":41.0,"lng":28.9,"label":"AKOM"}]}}}]}}]}}
        """#
        let lines = ["event:text_delta", #"data:{"delta":"See map"}"#, "", "event:done", done, ""]
        let result = try XCTUnwrap(try run(lines))
        XCTAssertEqual(result.text, "See map")
        XCTAssertNotNil(result.cardJson)
        XCTAssertEqual(result.mapPoints.count, 1)
        XCTAssertEqual(result.mapPoints.first?.label, "AKOM")
        XCTAssertTrue(result.unsupportedTools.isEmpty)
    }

    func testUnsupportedToolIsCollected() throws {
        let done = #"""
        data:{"data":{"candidates":[{"content":{"parts":[{"text":"x"},{"functionCall":{"name":"createSitrep","args":{}}}]}}]}}
        """#
        let result = try XCTUnwrap(try run(["event:done", done, ""]))
        XCTAssertEqual(result.unsupportedTools, ["createSitrep"])
    }

    func testNeedsApiKeyMapsToNotConfigured() {
        let lines = ["event:done", #"data:{"data":{"needsApiKey":true}}"#, ""]
        XCTAssertThrowsError(try run(lines)) { error in
            XCTAssertEqual((error as? CrisisSentinelOnlineError)?.kind, .notConfigured)
        }
    }

    func testErrorEventMapsStatusToQuota() {
        let lines = ["event:error", #"data:{"status":429,"error":"quota"}"#, ""]
        XCTAssertThrowsError(try run(lines)) { error in
            let e = error as? CrisisSentinelOnlineError
            XCTAssertEqual(e?.kind, .quota)
            XCTAssertEqual(e?.httpStatus, 429)
        }
    }

    func testEofWithoutDoneKeepsStreamedText() throws {
        // Connection dropped after some deltas but before `done`.
        let result = try run(["event:text_delta", #"data:{"delta":"partial reply"}"#, ""])
        XCTAssertEqual(result?.text, "partial reply")
    }

    func testProvidersParsing() {
        let json = #"""
        {"providers":[{"id":"gemini","label":"Gemini","defaultModel":"g2","models":[
          {"id":"g2","label":"Gemini 2","contextWindow":1000000},{"id":"g1"}]},
          {"id":"broken"}]}
        """#
        let providers = CrisisSentinelOnlineClient.parseProviders(Data(json.utf8))
        XCTAssertEqual(providers.count, 2)
        XCTAssertEqual(providers[0].id, "gemini")
        XCTAssertEqual(providers[0].models.count, 2)
        XCTAssertEqual(providers[0].models[0].contextWindow, 1_000_000)
        XCTAssertEqual(providers[0].models[1].label, "g1") // label falls back to id
        XCTAssertEqual(providers[1].models.count, 0)
    }
}
