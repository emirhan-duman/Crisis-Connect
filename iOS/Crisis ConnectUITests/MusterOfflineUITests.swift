import XCTest

final class MusterOfflineUITests: XCTestCase {
    @MainActor
    func testOfflineTextAndVoicePersistUntilForegroundReconnect() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["UITEST_MUSTER_OFFLINE_FIXTURE", UUID().uuidString]
        app.launch()
        expect("Fixture offline", at: "status", in: app)

        let note = app.textFields["muster-fixture-note"]
        XCTAssertTrue(note.waitForExistence(timeout: 10))
        note.tap()
        note.typeText("Offline device fixture")
        app.buttons["muster-fixture-submit-text"].tap()
        expect("Pending: 1", at: "pending", in: app)
        app.buttons["muster-fixture-submit-voice"].tap()
        expect("Pending: 2", at: "pending", in: app)
        expect("Stored audio: 1", at: "audio", in: app)
        expect("Received: 0", at: "received", in: app)

        sendToBackground(app)
        app.terminate()
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 10))
        app.launch()
        expect("Fixture offline", at: "status", in: app)
        expect("Pending: 2", at: "pending", in: app)
        expect("Saved text: Offline device fixture", at: "saved-text", in: app)
        expect("Stored audio: 1", at: "audio", in: app)
        expect("Received: 0", at: "received", in: app)

        app.buttons["muster-fixture-reconnect"].tap()
        expect("Fixture reconnect armed", at: "status", in: app)
        sendToBackground(app)
        app.activate()
        expect("Fixture reconnected", at: "status", in: app)
        expect("Pending: 0", at: "pending", in: app)
        expect("Sent: 2", at: "sent", in: app)
        expect("Stored audio: 0", at: "audio", in: app)
        expect("Received: 2", at: "received", in: app)

        app.terminate()
        app.launch()
        expect("Sent: 2", at: "sent", in: app)
        expect("Stored audio: 0", at: "audio", in: app)
        expect("Received: 2", at: "received", in: app)
        app.buttons["muster-fixture-clear"].tap()
        expect("Fixture cleared", at: "status", in: app)
    }

    @MainActor
    private func expect(_ label: String, at identifier: String, in app: XCUIApplication,
                        file: StaticString = #filePath, line: UInt = #line) {
        let element = app.staticTexts.matching(identifier: "muster-fixture-" + identifier)
            .matching(NSPredicate(format: "label == %@", label)).firstMatch
        XCTAssertTrue(element.waitForExistence(timeout: 15), "Expected \(label)", file: file, line: line)
    }

    @MainActor
    private func sendToBackground(_ app: XCUIApplication, file: StaticString = #filePath, line: UInt = #line) {
        XCUIDevice.shared.press(.home)
        let background = XCTNSPredicateExpectation(predicate: NSPredicate { _, _ in
            app.state == .runningBackground || app.state == .runningBackgroundSuspended
        }, object: nil)
        XCTAssertEqual(XCTWaiter.wait(for: [background], timeout: 10), .completed,
                       "App did not enter the background", file: file, line: line)
    }
}
