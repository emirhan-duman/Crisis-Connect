import XCTest
@testable import Crisis_Connect

final class CPRAssistTimingTests: XCTestCase {
    func testTargetCadenceIsInsideRecommendedRange() {
        XCTAssertTrue(
            (CPRAssistTiming.minimumRecommendedBPM...CPRAssistTiming.maximumRecommendedBPM)
                .contains(CPRAssistTiming.targetBPM)
        )
        XCTAssertEqual(CPRAssistTiming.beatInterval, 60.0 / 110.0, accuracy: 0.000_001)
    }

    func testCompressionCounterWrapsAfterThirty() {
        XCTAssertEqual(CPRAssistTiming.nextCompression(inSet: 0), 1)
        XCTAssertEqual(CPRAssistTiming.nextCompression(inSet: 29), 30)
        XCTAssertEqual(CPRAssistTiming.nextCompression(inSet: 30), 1)
        XCTAssertTrue(CPRAssistTiming.completedSet(after: 30))
    }

    func testRoundCountdownAndFormatting() {
        XCTAssertEqual(CPRAssistTiming.roundRemaining(elapsed: 0), 120)
        XCTAssertEqual(CPRAssistTiming.roundRemaining(elapsed: 119), 1)
        XCTAssertEqual(CPRAssistTiming.roundRemaining(elapsed: 125), 0)
        XCTAssertEqual(CPRAssistTiming.formatDuration(120), "02:00")
        XCTAssertEqual(CPRAssistTiming.formatDuration(65.9), "01:05")
    }
}
