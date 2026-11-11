import XCTest
@testable import Crisis_Connect

final class WatchCommandTests: XCTestCase {
    func testExpiredAndFutureCommandsAreRejected() {
        let now = Date(timeIntervalSince1970: 1_000)
        XCTAssertTrue(WatchCommand(action: .startSOS, now: now).isValid(at: 1_001))
        XCTAssertFalse(WatchCommand(action: .startSOS, now: now).isValid(at: 1_030))
        XCTAssertFalse(WatchCommand(action: .startSOS, now: now).isValid(at: 994))
    }
    func testStopNeedsEmergencyAndReplyNeedsRecipient() {
        XCTAssertFalse(WatchCommand(action: .stopSOS).isValid(at: Date().timeIntervalSince1970))
        XCTAssertFalse(WatchCommand(action: .quickReply, reply: .safe).isValid(at: Date().timeIntervalSince1970))
        XCTAssertTrue(WatchCommand(action: .quickReply, peerID: UUID().uuidString, reply: .safe).isValid(at: Date().timeIntervalSince1970))
    }
    func testUnsupportedVersionRejected() {
        var command = WatchCommand(action: .startSOS)
        command.version = 2
        XCTAssertFalse(command.isValid(at: Date().timeIntervalSince1970))
    }
}
