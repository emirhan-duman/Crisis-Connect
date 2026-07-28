//
//  P2pIdentityProofTests.swift
//  Crisis ConnectTests
//
//  Locks the byte-exact format of the P2P client-hello identity proof — the HMAC that lets a
//  QR-displaying peer trust the scanner's internet identity so it can fall back to the online
//  transport when Bluetooth is off. It MUST stay identical with Android's
//  P2pBleProtocol.buildClientIdentityProofPayload (which uses buildCanonicalProofPayload with the
//  same ordered parts); any drift here silently breaks cross-platform pairing's internet fallback.
//

import XCTest

@testable import Crisis_Connect

final class P2pIdentityProofTests: XCTestCase {

    private func b64(_ s: String) -> String { Data(s.utf8).base64EncodedString() }

    func testIdentityProofPayloadIsCanonicalAndOrdered() {
        let payload = P2pBleProtocol.buildClientIdentityProofPayload(
            shareId: "ABC123",
            serverNonce: "server-nonce",
            clientNonce: "client-nonce",
            peerUid: "uid-42",
            peerPublicKey: "pubkey-base64=="
        )
        let text = String(decoding: payload, as: UTF8.self)

        // Exact canonical form (no trailing newline) with fields in the agreed order — the same
        // bytes Android's buildCanonicalProofPayload produces for these inputs.
        let expected = [
            "p2p-v\(P2pBleProtocol.protocolVersion)",
            "type=\(b64("client_identity"))",
            "shareId=\(b64("ABC123"))",
            "serverNonce=\(b64("server-nonce"))",
            "clientNonce=\(b64("client-nonce"))",
            "clientPeerUid=\(b64("uid-42"))",
            "clientPeerPublicKey=\(b64("pubkey-base64=="))"
        ].joined(separator: "\n")

        XCTAssertEqual(text, expected)
        XCTAssertFalse(text.hasSuffix("\n"), "canonical form must not have a trailing newline")
    }

    func testIdentityProofHmacIsDeterministicAndKeyed() {
        let key = Data("shared-contact-key".utf8)
        let payload = P2pBleProtocol.buildClientIdentityProofPayload(
            shareId: "S", serverNonce: "sn", clientNonce: "cn", peerUid: "u", peerPublicKey: "k"
        )
        let proofA = P2pBleProtocol.hmacBase64(key: key, payload: payload)
        let proofB = P2pBleProtocol.hmacBase64(key: key, payload: payload)
        XCTAssertNotNil(proofA)
        XCTAssertEqual(proofA, proofB, "same key + payload must yield the same proof")

        // A different key (a MITM without the QR's shared key) can't reproduce the proof.
        let otherProof = P2pBleProtocol.hmacBase64(key: Data("attacker-key".utf8), payload: payload)
        XCTAssertNotEqual(proofA, otherProof)
    }

    func testDifferentIdentityYieldsDifferentProof() {
        let key = Data("k".utf8)
        let a = P2pBleProtocol.hmacBase64(
            key: key,
            payload: P2pBleProtocol.buildClientIdentityProofPayload(
                shareId: "S", serverNonce: "sn", clientNonce: "cn", peerUid: "uidA", peerPublicKey: "K"
            )
        )
        let b = P2pBleProtocol.hmacBase64(
            key: key,
            payload: P2pBleProtocol.buildClientIdentityProofPayload(
                shareId: "S", serverNonce: "sn", clientNonce: "cn", peerUid: "uidB", peerPublicKey: "K"
            )
        )
        XCTAssertNotEqual(a, b, "the proof must bind the peer uid")
    }
}
