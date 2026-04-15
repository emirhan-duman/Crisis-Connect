//
//  Crisis_ConnectUITestsLaunchTests.swift
//  Crisis ConnectUITests
//
//  Created by Emirhan Duman on 25.10.2025.
//

import XCTest

final class Crisis_ConnectUITestsLaunchTests: XCTestCase {

    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    @MainActor
    func testLaunch() throws {
        let app = XCUIApplication()
        app.launchArguments += ["UITEST_DISABLE_SYSTEM_PROMPTS", "UITEST_SET_ONBOARDING_COMPLETE"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Messages"].waitForExistence(timeout: 10))

        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = "Launch Screen"
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
