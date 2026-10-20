import XCTest
@testable import Crisis_Connect

final class DisasterTrafficPolicyTests: XCTestCase {
    func testNetworkFlappingDoesNotRestoreRichPresenceImmediately() {
        var policy = DisasterTrafficPolicy()
        policy.stopEmergency(now: 0)
        XCTAssertEqual(policy.mode(now: 0), .normal)
        policy.updateNetwork(restricted: true, cellular: true, now: 0)
        policy.updateNetwork(restricted: false, cellular: true, now: 1)
        XCTAssertEqual(policy.mode(now: 60.9), .restrictedNetwork)
        XCTAssertEqual(policy.mode(now: 61), .normal)
    }
    func testCellularFailuresAndStableRecovery() {
        var policy = DisasterTrafficPolicy()
        policy.cellular = true
        policy.record(now: 1, succeeded: false, duration: 0)
        XCTAssertEqual(policy.mode(now: 1), .normal)
        policy.record(now: 2, succeeded: false, duration: 0)
        XCTAssertEqual(policy.mode(now: 2), .degradedCellular)
        policy.record(now: 3, succeeded: true, duration: 0.1)
        policy.record(now: 4, succeeded: true, duration: 0.1)
        XCTAssertEqual(policy.mode(now: 4), .degradedCellular)
        policy.record(now: 302, succeeded: true, duration: 0.1)
        XCTAssertEqual(policy.mode(now: 302), .normal)
    }
    func testPermissionErrorsAndWifiFailuresAreNotCongestion() {
        var policy = DisasterTrafficPolicy()
        policy.cellular = true
        for _ in 0..<5 { policy.record(now: 0, succeeded: false, duration: 0, transientFailure: false) }
        XCTAssertEqual(policy.mode(now: 0), .normal)
        policy.cellular = false
        for _ in 0..<5 { policy.record(now: 0, succeeded: false, duration: 0) }
        XCTAssertEqual(policy.mode(now: 0), .normal)
    }
    func testSlowSuccessfulRequestsReduceTraffic() {
        var policy = DisasterTrafficPolicy()
        policy.cellular = true
        for i in 0..<4 { policy.record(now: Double(i), succeeded: true, duration: 8) }
        XCTAssertEqual(policy.mode(now: 4), .degradedCellular)
    }
    func testSOSAndRestrictedNetworkCannotBeClearedByHealthyTraffic() {
        var policy = DisasterTrafficPolicy()
        policy.emergencyActive = true
        XCTAssertEqual(policy.mode(now: 3_600), .sos)
        policy.stopEmergency(now: 3_600)
        XCTAssertEqual(policy.mode(now: 3_899), .sos)
        XCTAssertEqual(policy.mode(now: 3_900), .normal)
        policy.restricted = true
        for _ in 0..<5 { policy.record(now: 4_000, succeeded: true, duration: 0.1) }
        XCTAssertEqual(policy.mode(now: 4_000), .restrictedNetwork)
    }
    func testCompactTrafficUsesByteLimitAndSuppressesOptionalSignals() {
        XCTAssertTrue(DisasterTrafficPolicy.isAttachmentChunk("transfer~123.0"))
        XCTAssertFalse(DisasterTrafficPolicy.isAttachmentChunk("text~123"))
        XCTAssertTrue(DisasterTrafficPolicy.allowsCompact(template: 202, text: String(repeating: "a", count: 1_024)))
        XCTAssertTrue(DisasterTrafficPolicy.allowsCompact(template: 203, text: "location"))
        XCTAssertTrue(DisasterTrafficPolicy.allowsCompact(template: 208, text: "identity"))
        XCTAssertFalse(DisasterTrafficPolicy.allowsCompact(template: 0, text: String(repeating: "ğ", count: 513)))
        for template in [200, 201] {
            XCTAssertFalse(DisasterTrafficPolicy.allowsCompact(template: template, text: "1"))
        }
    }
}
