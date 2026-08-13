import XCTest
@testable import Crisis_Connect

final class BreadcrumbTrailMathTests: XCTestCase {
    func testDistanceAndBearingAcrossShortNorthboundSegment() {
        let start = point(latitude: 41.0000, longitude: 29.0000)
        let north = point(latitude: 41.0010, longitude: 29.0000)

        XCTAssertEqual(BreadcrumbTrailMath.distance(start, north), 111.2, accuracy: 1.0)
        XCTAssertEqual(BreadcrumbTrailMath.bearing(from: start, to: north), 0, accuracy: 0.2)
    }

    func testReturnCursorWalksRecordedPointsInReverse() {
        let points = [
            point(latitude: 41.0000, longitude: 29.0000),
            point(latitude: 41.0005, longitude: 29.0000),
            point(latitude: 41.0010, longitude: 29.0000),
            point(latitude: 41.0015, longitude: 29.0000),
        ]

        XCTAssertEqual(BreadcrumbTrailMath.initialReturnCursor(points: points, destinationIndex: 0), 2)
        XCTAssertEqual(
            BreadcrumbTrailMath.advanceReturnCursor(
                points: points,
                current: points[2],
                cursor: 2,
                destinationIndex: 0
            ),
            1
        )
    }

    func testRemainingDistanceIncludesCurrentLegAndRecordedTrail() {
        let points = [
            point(latitude: 41.0000, longitude: 29.0000),
            point(latitude: 41.0010, longitude: 29.0000),
            point(latitude: 41.0020, longitude: 29.0000),
        ]
        let current = point(latitude: 41.0025, longitude: 29.0000)

        let remaining = BreadcrumbTrailMath.remainingRouteDistance(
            points: points,
            current: current,
            cursor: 1,
            destinationIndex: 0
        )

        XCTAssertEqual(remaining, 278.0, accuracy: 3.0)
    }

    private func point(latitude: Double, longitude: Double) -> BreadcrumbPoint {
        BreadcrumbPoint(
            latitude: latitude,
            longitude: longitude,
            altitudeMeters: nil,
            accuracyMeters: 5,
            timestamp: Date(timeIntervalSince1970: 1_700_000_000)
        )
    }
}
