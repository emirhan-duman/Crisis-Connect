import XCTest
@testable import Crisis_Connect

@MainActor final class SOSRecoveryTests: XCTestCase {
    private func location() -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("state.json") }
    func testReopenKeepsIdentityAndRequiresConfirmation() throws {
        let url = location(); let store = SOSRecoveryStore(url: url)
        let initial = try store.begin(now: Date(timeIntervalSince1970: 1000))
        let reopened = SOSRecoveryStore(url: url)
        XCTAssertEqual(reopened.snapshot.session, initial)
        XCTAssertThrowsError(try reopened.begin())
        XCTAssertEqual(try reopened.begin(resuming: initial.id), initial)
    }
    func testEndSurvivesReopenAndStaleResumeFails() throws {
        let url = location(); let store = SOSRecoveryStore(url: url)
        let initial = try store.begin(); try store.reserve(id: "one", peer: "test", text: "İyiyim"); try store.finish()
        let reopened = SOSRecoveryStore(url: url)
        XCTAssertNil(reopened.snapshot.session); XCTAssertEqual(reopened.snapshot.replies.count, 1)
        XCTAssertThrowsError(try reopened.begin(resuming: initial.id))
        XCTAssertNotEqual(try reopened.begin().id, initial.id)
    }
    func testUncertainReplySurvivesAndDoesNotDuplicate() throws {
        let url = location(); let store = SOSRecoveryStore(url: url)
        try store.reserve(id: "same", peer: "test", text: "Yaralıyım")
        let reopened = SOSRecoveryStore(url: url); try reopened.reserve(id: "same", peer: "other", text: "changed")
        XCTAssertEqual(reopened.snapshot.replies.count, 1)
        XCTAssertEqual(reopened.snapshot.replies.first?.result, "uncertain")
        XCTAssertEqual(reopened.snapshot.replies.first?.text, "Yaralıyım")
    }
    func testCorruptHistoryCannotStartOrErase() throws {
        let url = location(); try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("broken".utf8).write(to: url)
        let store = SOSRecoveryStore(url: url); XCTAssertTrue(store.unavailable)
        XCTAssertThrowsError(try store.begin()); XCTAssertThrowsError(try store.finish())
    }
    func testOldWatchStateDecodesWithoutRecoveryFields() throws {
        let data = Data(#"{"version":1,"sosActive":false,"bleBroadcasting":false,"updatedAt":1}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(WatchPhoneState.self, from: data).recoveryRequired)
    }
}
