//
//  SfuRingManagerTests.swift
//  Crisis ConnectTests
//
//  Locks the SFU ring state machine — the signaling contract shared byte-for-byte with the web
//  (sfu-ring.ts) and Android (SfuRingManager). A drift here means authority calls ring on one
//  platform but not another, so these assertions guard the cross-platform handshake.
//

import XCTest

@testable import Crisis_Connect

@MainActor
final class SfuRingManagerTests: XCTestCase {

    /// Captures every signal the ring asks to send, plus the onRoom/onState callbacks.
    @MainActor
    private final class Harness {
        var sent: [(toUid: String, signal: [String: Any])] = []
        var rooms: [(roomId: String, isCaller: Bool)] = []
        var states: [SfuCallState] = []

        func makeRing() -> SfuRingManager {
            SfuRingManager(
                sender: { [weak self] toUid, signal in self?.sent.append((toUid, signal)) },
                onState: { [weak self] state in self?.states.append(state) },
                onRoom: { [weak self] roomId, isCaller in self?.rooms.append((roomId, isCaller)) }
            )
        }
    }

    func testStartCallEmitsOfferWithRoomAndGoesOutgoing() {
        let harness = Harness()
        let ring = harness.makeRing()

        ring.startCall(peerUid: "peer-1", peerName: "Peer One", video: true)

        XCTAssertEqual(ring.state, .outgoing)
        XCTAssertNotNil(ring.callId)
        XCTAssertNotNil(ring.roomId)
        XCTAssertTrue(ring.video)

        let offer = try? XCTUnwrap(harness.sent.first)
        XCTAssertEqual(offer?.toUid, "peer-1")
        XCTAssertEqual(offer?.signal["type"] as? String, "offer")
        XCTAssertEqual(offer?.signal["roomId"] as? String, ring.roomId)
        XCTAssertEqual(offer?.signal["video"] as? Bool, true)
        XCTAssertEqual(offer?.signal["callId"] as? String, ring.callId)
    }

    func testCallerAnswerJoinsRoomAsCallerExactlyOnce() {
        let harness = Harness()
        let ring = harness.makeRing()
        ring.startCall(peerUid: "peer-1", peerName: "Peer One")
        let callId = try? XCTUnwrap(ring.callId)
        let roomId = try? XCTUnwrap(ring.roomId)

        ring.handleSignal(fromUid: "peer-1", signal: ["type": "answer", "callId": callId as Any])

        XCTAssertEqual(ring.state, .connected)
        XCTAssertEqual(harness.rooms.count, 1)
        XCTAssertEqual(harness.rooms.first?.roomId, roomId)
        XCTAssertEqual(harness.rooms.first?.isCaller, true)
        // A replayed answer must not join twice (Firestore can redeliver on a listener re-attach).
        ring.handleSignal(fromUid: "peer-1", signal: ["type": "answer", "callId": callId as Any])
        XCTAssertEqual(harness.rooms.count, 1)
    }

    func testIncomingOfferThenAcceptJoinsAsCalleeAndSendsAnswer() {
        let harness = Harness()
        let ring = harness.makeRing()

        ring.handleSignal(fromUid: "peer-2", signal: [
            "type": "offer", "callId": "call-xyz", "roomId": "room-xyz", "video": false
        ])
        XCTAssertEqual(ring.state, .incoming)
        XCTAssertEqual(ring.roomId, "room-xyz")
        XCTAssertEqual(ring.peerUid, "peer-2")

        ring.accept()
        XCTAssertEqual(ring.state, .connected)
        XCTAssertEqual(harness.rooms.first?.roomId, "room-xyz")
        XCTAssertEqual(harness.rooms.first?.isCaller, false)
        XCTAssertTrue(harness.sent.contains { $0.signal["type"] as? String == "answer" })
    }

    func testOfferWhileBusyOnDifferentCallSendsBusy() {
        let harness = Harness()
        let ring = harness.makeRing()
        ring.startCall(peerUid: "peer-1", peerName: "Peer One")

        ring.handleSignal(fromUid: "peer-3", signal: [
            "type": "offer", "callId": "other-call", "roomId": "other-room"
        ])

        let busy = harness.sent.first { $0.signal["type"] as? String == "busy" }
        XCTAssertEqual(busy?.toUid, "peer-3")
        XCTAssertEqual(busy?.signal["callId"] as? String, "other-call")
        // Our own call is untouched.
        XCTAssertEqual(ring.state, .outgoing)
    }

    func testOfferWithoutRoomIdIsIgnored() {
        let harness = Harness()
        let ring = harness.makeRing()

        ring.handleSignal(fromUid: "peer-2", signal: ["type": "offer", "callId": "call-noroom"])

        XCTAssertEqual(ring.state, .idle)
        XCTAssertTrue(harness.sent.isEmpty)
    }

    func testEndSendsEndSignalAndFlashesEnded() {
        let harness = Harness()
        let ring = harness.makeRing()
        ring.startCall(peerUid: "peer-1", peerName: "Peer One")
        harness.sent.removeAll()

        ring.end()

        XCTAssertTrue(harness.sent.contains { $0.signal["type"] as? String == "end" })
        XCTAssertEqual(ring.state, .ended)
    }

    func testStaleRejectAfterConnectDoesNotTearDown() {
        let harness = Harness()
        let ring = harness.makeRing()
        ring.startCall(peerUid: "peer-1", peerName: "Peer One")
        let callId = try? XCTUnwrap(ring.callId)
        ring.handleSignal(fromUid: "peer-1", signal: ["type": "answer", "callId": callId as Any])
        XCTAssertEqual(ring.state, .connected)

        // A never-answered sibling session (web multi-tab) ring-times-out and sends reject ~35s
        // in; it must NOT tear down the call the answering session already connected.
        ring.handleSignal(fromUid: "peer-1", signal: ["type": "reject", "callId": callId as Any])
        XCTAssertEqual(ring.state, .connected)
    }
}
