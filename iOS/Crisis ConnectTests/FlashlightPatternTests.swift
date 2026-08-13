import XCTest
@testable import Crisis_Connect

final class FlashlightPatternTests: XCTestCase {
    func testSOSContainsThreeDotsThreeDashesAndThreeDots() {
        let onDurations = FlashlightPatterns.sos
            .filter(\.isOn)
            .map(\.durationNanoseconds)

        XCTAssertEqual(
            onDurations,
            [
                200_000_000, 200_000_000, 200_000_000,
                600_000_000, 600_000_000, 600_000_000,
                200_000_000, 200_000_000, 200_000_000,
            ]
        )
        XCTAssertEqual(
            FlashlightPatterns.sos.reduce(0) { $0 + $1.durationNanoseconds },
            6_000_000_000
        )
    }

    func testEmergencyBeaconEmitsSixSignalsInTwoMinuteCycle() {
        let pattern = FlashlightPatterns.emergencyBeacon

        XCTAssertEqual(pattern.filter(\.isOn).count, 6)
        XCTAssertEqual(pattern.reduce(0) { $0 + $1.durationNanoseconds }, 120_000_000_000)
    }

    func testStrobeIsCappedAtThreeFlashesPerSecond() {
        let pattern = FlashlightPatterns.strobe(flashesPerSecond: 20)

        XCTAssertEqual(pattern.count, 2)
        XCTAssertTrue(pattern.allSatisfy { $0.durationNanoseconds >= 166_666_667 })
    }
}
