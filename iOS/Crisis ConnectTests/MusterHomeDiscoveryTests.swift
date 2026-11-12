import Foundation
import XCTest
@testable import Crisis_Connect

final class MusterHomeDiscoveryTests: XCTestCase {
    func testFreshAccountIsAdmittedWithoutCachedAssignmentsAndRepeatedForegroundIsThrottled() throws {
        var gate = MusterHomeDiscoveryGate()
        let first = try XCTUnwrap(gate.begin(uid: "fresh-member", uptime: 100))
        gate.finish(uid: "fresh-member", ticket: first)
        XCTAssertNil(gate.begin(uid: "fresh-member", uptime: 101))
        XCTAssertNil(gate.begin(uid: "fresh-member", uptime: 159.999))
        XCTAssertNotNil(gate.begin(uid: "fresh-member", uptime: 160))
    }

    func testConcurrentAppearanceAndForegroundCannotDuplicateAnInFlightDiscovery() throws {
        var gate = MusterHomeDiscoveryGate()
        let first = try XCTUnwrap(gate.begin(uid: "member", uptime: 10))
        XCTAssertNil(gate.begin(uid: "member", uptime: 10))
        XCTAssertNil(gate.begin(uid: "member", uptime: 500), "A slow request remains exclusive even after the cooldown")
        gate.finish(uid: "member", ticket: first)
        XCTAssertNotNil(gate.begin(uid: "member", uptime: 500))
    }

    func testFailedDiscoveryRetainsCooldownAndAccountsHaveIndependentAdmission() throws {
        var gate = MusterHomeDiscoveryGate()
        let alice = try XCTUnwrap(gate.begin(uid: "alice", uptime: 100))
        let bob = try XCTUnwrap(gate.begin(uid: "bob", uptime: 101))
        // Completion is the same after success or a caught nonmember/offline failure.
        gate.finish(uid: "alice", ticket: alice)
        XCTAssertNil(gate.begin(uid: "alice", uptime: 110))
        XCTAssertNil(gate.begin(uid: "bob", uptime: 500), "Finishing Alice cannot unlock Bob's pending request")
        gate.finish(uid: "bob", ticket: bob)
        XCTAssertNotNil(gate.begin(uid: "bob", uptime: 500))
        XCTAssertNotNil(gate.begin(uid: "alice", uptime: 500))
    }

    func testStaleCompletionCannotUnlockANewerAttemptForTheSameAccount() throws {
        var gate = MusterHomeDiscoveryGate()
        let old = try XCTUnwrap(gate.begin(uid: "member", uptime: 100))
        gate.finish(uid: "member", ticket: old)
        let current = try XCTUnwrap(gate.begin(uid: "member", uptime: 160))
        gate.finish(uid: "member", ticket: old)
        XCTAssertNil(gate.begin(uid: "member", uptime: 500))
        gate.finish(uid: "member", ticket: current)
        XCTAssertNotNil(gate.begin(uid: "member", uptime: 500))
    }

    func testAccountHistoryIsBoundedWithoutEvictingAnInFlightRequest() throws {
        var gate = MusterHomeDiscoveryGate()
        let active = try XCTUnwrap(gate.begin(uid: "active", uptime: 0))
        for index in 0..<100 {
            let uid = "finished-\(index)"
            let ticket = try XCTUnwrap(gate.begin(uid: uid, uptime: Double(index + 1)))
            gate.finish(uid: uid, ticket: ticket)
            XCTAssertLessThanOrEqual(gate.retainedAccountCount, MusterHomeDiscoveryGate.maximumAccounts)
        }
        XCTAssertNil(gate.begin(uid: "active", uptime: 1000))
        gate.finish(uid: "active", ticket: active)
        XCTAssertNotNil(gate.begin(uid: "active", uptime: 1000))
    }

    func testSaturatedInFlightStateRefusesNewWorkUntilARequestFinishes() throws {
        var gate = MusterHomeDiscoveryGate(), tickets: [UUID] = []
        for index in 0..<MusterHomeDiscoveryGate.maximumAccounts {
            tickets.append(try XCTUnwrap(gate.begin(uid: "active-\(index)", uptime: Double(index))))
        }
        XCTAssertNil(gate.begin(uid: "extra", uptime: 1000))
        gate.finish(uid: "active-0", ticket: tickets[0])
        XCTAssertNotNil(gate.begin(uid: "extra", uptime: 1000))
        XCTAssertEqual(gate.retainedAccountCount, MusterHomeDiscoveryGate.maximumAccounts)
        XCTAssertNil(gate.begin(uid: "active-1", uptime: 1000))
    }

    func testInvalidOrBackwardMonotonicTimeCannotBypassTheCooldown() throws {
        var gate = MusterHomeDiscoveryGate()
        XCTAssertNil(gate.begin(uid: "", uptime: 100))
        XCTAssertNil(gate.begin(uid: "member", uptime: .nan))
        XCTAssertNil(gate.begin(uid: "member", uptime: .infinity))
        XCTAssertNil(gate.begin(uid: "member", uptime: -1))
        let ticket = try XCTUnwrap(gate.begin(uid: "member", uptime: 100))
        gate.finish(uid: "member", ticket: ticket)
        XCTAssertNil(gate.begin(uid: "member", uptime: 99))
        XCTAssertNil(gate.begin(uid: "member", uptime: 159))
        XCTAssertNotNil(gate.begin(uid: "member", uptime: 160))
    }
}
