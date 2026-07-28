//
//  Spake2P256Tests.swift
//  Crisis ConnectTests
//
//  Pins the iOS SPAKE2 to the official RFC 9382 Appendix B P-256 test vector — the same vector the
//  Android `Spake2P256Test.kt` uses. If this fails, the iOS SPAKE2 diverged from the spec / Android
//  and offline nearby pairing would be cross-platform-incompatible or insecure.
//

import XCTest
@testable import Crisis_Connect

final class Spake2P256Tests: XCTestCase {

    private let w = BigUInt(hex: "2ee57912099d31560b3a44b1184b9b4866e904c49d12ac5042c97dca461b1a5f")
    private let x = BigUInt(hex: "43dd0fd7215bdcb482879fca3220c6a968e66d70b1356cac18bb26c84a78d729")
    private let y = BigUInt(hex: "dcb60106f276b02606d8ef0a328c02e4b629f84f89786af5befb0bc75b6e66be")
    private let idA = Data("server".utf8)
    private let idB = Data("client".utf8)
    private let pA = "04a56fa807caaa53a4d28dbb9853b9815c61a411118a6fe516a8798434751470f9010153ac33d0d5f2047ffdb1a3e42c9b4e6be662766e1eeb4116988ede5f912c"
    private let pB = "0406557e482bd03097ad0cbaa5df82115460d951e3451962f1eaf4367a420676d09857ccbc522686c83d1852abfa8ed6e4a1155cf8f1543ceca528afb591a1e0b7"
    private let kHex = "0412af7e89717850671913e6b469ace67bd90a4df8ce45c2af19010175e37eed69f75897996d539356e2fa6a406d528501f907e04d97515fbe83db277b715d3325"

    private func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
    private func bytes(_ h: String) -> Data {
        var out = Data(); var i = h.startIndex
        while i < h.endIndex { let n = h.index(i, offsetBy: 2); out.append(UInt8(h[i..<n], radix: 16)!); i = n }
        return out
    }

    func testSharesMatchRfcVector() {
        XCTAssertEqual(hex(Spake2P256.shareA(w: w, x: x)), pA)
        XCTAssertEqual(hex(Spake2P256.shareB(w: w, y: y)), pB)
    }

    func testSharedPointMatchesFromBothSides() {
        XCTAssertEqual(hex(Spake2P256.keyA(w: w, x: x, pB: bytes(pB))), kHex)
        XCTAssertEqual(hex(Spake2P256.keyB(w: w, y: y, pA: bytes(pA))), kHex)
    }

    func testTranscriptAndKeysMatchRfcVector() {
        let tt = Spake2P256.transcript(idA: idA, idB: idB, pA: bytes(pA), pB: bytes(pB), k: bytes(kHex), w: w)
        let keys = Spake2P256.deriveKeys(tt)
        XCTAssertEqual(hex(keys.sharedKey + keys.ka), "0e0672dc86f8e45565d338b0540abe6915bdf72e2b35b5c9e5663168e960a91b")
        XCTAssertEqual(hex(keys.sharedKey), "0e0672dc86f8e45565d338b0540abe69")
        XCTAssertEqual(hex(keys.confirmKeyA), "00c12546835755c86d8c0db7851ae86f")
        XCTAssertEqual(hex(keys.confirmKeyB), "a9fa3406c3b781b93d804485430ca27a")
        XCTAssertEqual(hex(Spake2P256.confirm(confirmKey: keys.confirmKeyA, tt: tt)), "58ad4aa88e0b60d5061eb6b5dd93e80d9c4f00d127c65b3b35b1b5281fee38f0")
        XCTAssertEqual(hex(Spake2P256.confirm(confirmKey: keys.confirmKeyB, tt: tt)), "d3e2e547f1ae04f2dbdbf0fc4b79f8ecff2dff314b5d32fe9fcef2fb26dc459b")
    }

    func testEndToEndBothSidesAgree() {
        let secret = Spake2P256.randomScalar()
        let xa = Spake2P256.randomScalar(), yb = Spake2P256.randomScalar()
        let sA = Spake2P256.shareA(w: secret, x: xa)
        let sB = Spake2P256.shareB(w: secret, y: yb)
        XCTAssertEqual(
            hex(Spake2P256.keyA(w: secret, x: xa, pB: sB)),
            hex(Spake2P256.keyB(w: secret, y: yb, pA: sA))
        )
    }
}
