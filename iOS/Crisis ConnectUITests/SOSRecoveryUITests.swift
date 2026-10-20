import XCTest

final class SOSRecoveryUITests: XCTestCase {
    @MainActor func testConfirmSameSessionUntilWatchEnds() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchEnvironment["CRISIS_WATCH_E2E"] = "1"
        app.launchArguments = ["-AppleLanguages", "(tr)", "-AppleLocale", "tr_TR"]
        app.launch()
        let resume = app.buttons["resumeSavedSOS"]
        XCTAssertTrue(resume.waitForExistence(timeout: 20))
        let before = XCTAttachment(screenshot: app.screenshot()); before.name = "Kayıtlı SOS"; before.lifetime = .keepAlways; add(before)
        resume.tap()
        XCTAssertTrue(app.staticTexts["SOS aktif"].waitForExistence(timeout: 10))
        let active = XCTAttachment(screenshot: app.screenshot()); active.name = "Aynı SOS aktif"; active.lifetime = .keepAlways; add(active)
        XCTAssertTrue(app.staticTexts["SOS kapalı"].waitForExistence(timeout: 100))
        XCTAssertFalse(app.buttons["resumeSavedSOS"].exists)
        // Keep the phone alive while the watch receives the stop and checks its duplicate receipt.
        Thread.sleep(forTimeInterval: 10)
    }
}
