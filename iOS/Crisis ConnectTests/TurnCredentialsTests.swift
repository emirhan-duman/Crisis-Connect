//
//  TurnCredentialsTests.swift
//  Crisis ConnectTests
//
//  Mirrors Android's TurnCredentialsParseTest: the /api/turn-credentials body must parse across
//  the same tolerant shapes on both platforms, and malformed input degrades to STUN-only.
//

import XCTest
@testable import Crisis_Connect

final class TurnCredentialsTests: XCTestCase {

    func testParsesArrayWithUrlListAndCredentials() {
        let json = """
        {"iceServers":[{"urls":["turn:turn.example.com:3478?transport=udp","turns:turn.example.com:5349"],
        "username":"u1","credential":"c1"}],"ttl":3600}
        """
        let servers = TurnCredentials.parse(Data(json.utf8))
        XCTAssertEqual(servers.count, 1)
        XCTAssertEqual(servers[0].urls.count, 2)
        XCTAssertEqual(servers[0].username, "u1")
        XCTAssertEqual(servers[0].credential, "c1")
    }

    func testToleratesSingleObjectAndStringUrls() {
        let json = """
        {"iceServers":{"urls":"stun:stun.example.com:3478"}}
        """
        let servers = TurnCredentials.parse(Data(json.utf8))
        XCTAssertEqual(servers, [IceServerSpec(urls: ["stun:stun.example.com:3478"])])
    }

    func testEmptyCredentialsBecomeNil() {
        let json = """
        {"iceServers":[{"urls":"turn:t.example.com","username":"","credential":""}]}
        """
        let servers = TurnCredentials.parse(Data(json.utf8))
        XCTAssertEqual(servers.count, 1)
        XCTAssertNil(servers[0].username)
        XCTAssertNil(servers[0].credential)
    }

    func testMalformedInputYieldsEmpty() {
        XCTAssertTrue(TurnCredentials.parse(Data("not json".utf8)).isEmpty)
        XCTAssertTrue(TurnCredentials.parse(Data("{}".utf8)).isEmpty)
        XCTAssertTrue(TurnCredentials.parse(Data("{\"iceServers\":[{\"username\":\"x\"}]}".utf8)).isEmpty)
    }
}
