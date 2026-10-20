import XCTest
@testable import Crisis_Connect

final class ConstrainedMessagePolicyTests: XCTestCase {
    func testAllowsShortFreeTextAndSOS() {
        XCTAssertTrue(ConstrainedMessagePolicy.allowsText(templateCode: 0, text: "Konumumdayım"))
        XCTAssertTrue(ConstrainedMessagePolicy.allowsText(templateCode: 202, text: "Yardıma ihtiyacım var"))
    }

    func testUsesUtf8ByteCountAtTheLimit() {
        XCTAssertTrue(ConstrainedMessagePolicy.allowsText(templateCode: 0, text: String(repeating: "a", count: 1_024)))
        XCTAssertFalse(ConstrainedMessagePolicy.allowsText(templateCode: 0, text: String(repeating: "a", count: 1_025)))
        XCTAssertFalse(ConstrainedMessagePolicy.allowsText(templateCode: 0, text: String(repeating: "ğ", count: 513)))
    }

    func testDisallowsNonTextControlTemplates() {
        for template in [200, 201, 203, 204, 205, 206, 207, 208] {
            XCTAssertFalse(ConstrainedMessagePolicy.allowsText(templateCode: template, text: "ok"))
        }
    }
}
