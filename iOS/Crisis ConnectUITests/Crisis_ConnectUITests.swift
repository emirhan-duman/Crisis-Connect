//
//  Crisis_ConnectUITests.swift
//  Crisis ConnectUITests
//
//  Created by Emirhan Duman on 25.10.2025.
//

import XCTest

final class Crisis_ConnectUITests: XCTestCase {

    private func makeApp(with arguments: [String] = []) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments += ["UITEST_DISABLE_SYSTEM_PROMPTS"] + arguments
        return app
    }

    private func enableSwitchIfNeeded(_ toggle: XCUIElement) {
        guard toggle.waitForExistence(timeout: 5) else {
            XCTFail("Expected toggle \(toggle) to exist.")
            return
        }
        if let value = toggle.value as? String, value == "0" {
            toggle.tap()
        }
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testOnboardingFlowCompletesAndShowsMainTabs() throws {
        let app = makeApp(with: ["UITEST_SET_ONBOARDING_INCOMPLETE"])
        app.launch()

        let nameField = app.textFields["onboarding-name-field"]
        XCTAssertTrue(nameField.waitForExistence(timeout: 5))
        nameField.tap()
        nameField.typeText("UI Test User")

        enableSwitchIfNeeded(app.switches["onboarding-terms-toggle"])
        enableSwitchIfNeeded(app.switches["onboarding-privacy-toggle"])

        let nextButton = app.buttons["onboarding-next-button"]
        XCTAssertTrue(nextButton.isEnabled)
        nextButton.tap()

        let finishButton = app.buttons["onboarding-finish-button"]
        XCTAssertTrue(finishButton.waitForExistence(timeout: 5))
        finishButton.tap()

        XCTAssertTrue(app.tabBars.buttons["Messages"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.tabBars.buttons["Tools"].exists)
        XCTAssertTrue(app.tabBars.buttons["Survival Guide"].exists)
        XCTAssertTrue(app.tabBars.buttons["Settings"].exists)
    }

    @MainActor
    func testCoreScreensAreReachableFromMainTabs() throws {
        let app = makeApp(with: ["UITEST_SET_ONBOARDING_COMPLETE"])
        app.launch()

        let toolsTab = app.tabBars.buttons.matching(identifier: "Tools").firstMatch
        XCTAssertTrue(toolsTab.waitForExistence(timeout: 5))
        toolsTab.tap()

        let offlineMapLink = app.buttons["tool-link-offline-map"]
        XCTAssertTrue(offlineMapLink.waitForExistence(timeout: 10))
        offlineMapLink.tap()
        XCTAssertTrue(app.navigationBars["Offline Map"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let settingsTab = app.tabBars.buttons.matching(identifier: "Settings").firstMatch
        XCTAssertTrue(settingsTab.waitForExistence(timeout: 5))
        settingsTab.tap()

        let privacyLink = app.buttons["settings-link-privacy"]
        XCTAssertTrue(privacyLink.waitForExistence(timeout: 10))
        privacyLink.tap()
        XCTAssertTrue(app.navigationBars["Privacy"].waitForExistence(timeout: 5))
        app.navigationBars.buttons.element(boundBy: 0).tap()

        let guideTab = app.tabBars.buttons.matching(identifier: "Survival Guide").firstMatch
        XCTAssertTrue(guideTab.waitForExistence(timeout: 5))
        guideTab.tap()
        XCTAssertTrue(app.navigationBars["Survival Guide"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testCrisisSentinelNavigation() throws {
        let app = makeApp(with: ["UITEST_SET_ONBOARDING_COMPLETE", "UITEST_MOCK_CRISIS_SENTINEL_READY"])
        app.launch()

        let sentinelCard = app.buttons["messages-crisis-sentinel-entry"]
        XCTAssertTrue(sentinelCard.waitForExistence(timeout: 10))
        sentinelCard.tap()

        XCTAssertTrue(app.scrollViews["crisis-sentinel-home-screen"].waitForExistence(timeout: 5))
    }

    @MainActor
    func testLaunchPerformance() throws {
        measure(metrics: [XCTApplicationLaunchMetric()]) {
            let app = makeApp(with: ["UITEST_SET_ONBOARDING_COMPLETE"])
            app.launch()
        }
    }
}
