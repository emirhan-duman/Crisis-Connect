import XCTest

final class ContactDisclosureUITests: XCTestCase {
    @MainActor
    func testPopupDeclineAcceptRememberAndWithdraw() throws {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments += ["UITEST_DISABLE_SYSTEM_PROMPTS", "UITEST_SET_ONBOARDING_COMPLETE",
                                "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let messages = app.tabBars.buttons["Messages"]
        XCTAssertTrue(messages.waitForExistence(timeout: 15))

        // Reset this simulator's consent through the same controls available to users.
        app.tabBars.buttons["Settings"].tap()
        app.buttons["settings-link-privacy"].tap()
        let withdraw = app.buttons["Withdraw online matching consent"]
        XCTAssertTrue(withdraw.waitForExistence(timeout: 5))
        if withdraw.isEnabled { withdraw.tap() }
        messages.tap()
        app.buttons["New Contact"].tap()
        let addContact = app.buttons["Add from phone contacts"]
        XCTAssertTrue(addContact.waitForExistence(timeout: 5))
        addContact.tap()

        let disclosure = app.alerts["Find your contacts online"]
        XCTAssertTrue(disclosure.waitForExistence(timeout: 5))
        XCTAssertTrue(disclosure.buttons["I agree"].exists)
        XCTAssertTrue(disclosure.buttons["Not now"].exists)
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = "Contact disclosure popup"
        screenshot.lifetime = .keepAlways
        add(screenshot)
        disclosure.buttons["Not now"].tap()
        XCTAssertTrue(addContact.waitForExistence(timeout: 5))
        addContact.tap()
        XCTAssertTrue(disclosure.waitForExistence(timeout: 5))
        disclosure.buttons["I agree"].tap()
        XCTAssertTrue(app.navigationBars["Phone Contacts"].waitForExistence(timeout: 5))
        app.navigationBars["Phone Contacts"].buttons.element(boundBy: 0).tap()
        XCTAssertTrue(addContact.waitForExistence(timeout: 5))
        addContact.tap()
        XCTAssertTrue(app.navigationBars["Phone Contacts"].waitForExistence(timeout: 5))
        XCTAssertFalse(disclosure.exists)
        app.navigationBars["Phone Contacts"].buttons.element(boundBy: 0).tap()
        app.buttons["Close"].tap()
        app.tabBars.buttons["Settings"].tap()
        XCTAssertTrue(withdraw.waitForExistence(timeout: 5))
        XCTAssertTrue(withdraw.isEnabled)
        withdraw.tap()
        messages.tap()
        app.buttons["New Contact"].tap()
        XCTAssertTrue(addContact.waitForExistence(timeout: 5))
        addContact.tap()
        XCTAssertTrue(disclosure.waitForExistence(timeout: 5))
        disclosure.buttons["Not now"].tap()
    }
}
