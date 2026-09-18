import XCTest
@testable import Crisis_Connect

final class TelemetryConsentTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!

    override func setUp() {
        super.setUp()
        suiteName = "TelemetryConsentTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        super.tearDown()
    }

    func testTelemetryDefaultsToDisabled() {
        XCTAssertFalse(PrivacyPreferences.isShareAnalyticsEnabled(userDefaults: defaults))
        XCTAssertFalse(PrivacyPreferences.isShareDiagnosticsEnabled(userDefaults: defaults))
    }

    func testExplicitTelemetryChoicesArePreserved() {
        PrivacyPreferences.setShareAnalytics(true, userDefaults: defaults)
        PrivacyPreferences.setShareDiagnostics(true, userDefaults: defaults)

        XCTAssertTrue(PrivacyPreferences.isShareAnalyticsEnabled(userDefaults: defaults))
        XCTAssertTrue(PrivacyPreferences.isShareDiagnosticsEnabled(userDefaults: defaults))
    }
}
