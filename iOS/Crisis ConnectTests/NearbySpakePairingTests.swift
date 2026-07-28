//
//  NearbySpakePairingTests.swift
//  Crisis ConnectTests
//
//  Exercises the SPAKE2 pairing session end-to-end (no GATT), mirroring the Android
//  NearbySpakePairingTest: matching numbers complete and exchange identities + a shared offline key;
//  a mismatched number fails at key confirmation and reveals nothing (the harvesting defense).
//

import XCTest
@testable import Crisis_Connect

final class NearbySpakePairingTests: XCTestCase {

    private let alice = NearbyIdentity(uid: "uidAlice", publicKeyBase64: "ALICEPUBb64", displayName: "Alice")
    private let bob = NearbyIdentity(uid: "uidBob", publicKeyBase64: "BOBPUBb64", displayName: "Bob")

    func testMatchingNumberCompletesAndExchangesIdentities() throws {
        let w = NearbySpakePairing.deriveW("+905551112233")
        let initiator = NearbySpakePairing.Initiator(w: w, me: bob)   // searcher
        let responder = NearbySpakePairing.Responder(w: w, me: alice) // discoverable (Alice)

        let msg1 = initiator.message1()
        let msg2 = try responder.onMessage1(msg1)
        let msg3 = try initiator.onMessage2(msg2)
        let peerSeenByResponder = try responder.onMessage3(msg3)
        let msg4 = try responder.responseMessage(status: NearbySpakePairing.statusOk)
        let peerSeenByInitiator = try initiator.onMessage4(msg4)

        XCTAssertEqual(peerSeenByInitiator, alice)
        XCTAssertEqual(peerSeenByResponder, bob)
        XCTAssertEqual(try initiator.contactKey(), try responder.contactKey())
    }

    func testMismatchedNumberFailsAtConfirmation() throws {
        let initiator = NearbySpakePairing.Initiator(w: NearbySpakePairing.deriveW("+905551112233"), me: bob)
        let responder = NearbySpakePairing.Responder(w: NearbySpakePairing.deriveW("+905559998877"), me: alice)
        let msg2 = try responder.onMessage1(initiator.message1())
        XCTAssertThrowsError(try initiator.onMessage2(msg2))
    }

    func testPendingResponseCarriesNoIdentity() throws {
        let w = NearbySpakePairing.deriveW("+905551112233")
        let initiator = NearbySpakePairing.Initiator(w: w, me: bob)
        let responder = NearbySpakePairing.Responder(w: w, me: alice)
        let msg2 = try responder.onMessage1(initiator.message1())
        let msg3 = try initiator.onMessage2(msg2)
        _ = try responder.onMessage3(msg3)
        let pending = try responder.responseMessage(status: NearbySpakePairing.statusPending)
        XCTAssertThrowsError(try initiator.onMessage4(pending))
    }
}
