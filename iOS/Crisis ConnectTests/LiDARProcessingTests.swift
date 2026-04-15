//
//  LiDARProcessingTests.swift
//  Crisis ConnectTests
//

import XCTest
@testable import Crisis_Connect
import CoreGraphics

@MainActor
final class LiDARProcessingTests: XCTestCase {
    func testRepresentativeDepthUsesMedianToRejectOutliers() {
        let samples: [Float] = [0.74, 0.76, 0.79, 3.9, 0.78]

        let depth = LiDARProcessing.representativeDepth(from: samples)

        XCTAssertNotNil(depth)
        XCTAssertEqual(depth ?? 0, 0.78 as Float, accuracy: 0.001)
    }

    func testFormattedDistanceUsesExpectedPrecision() {
        XCTAssertEqual(LiDARProcessing.formattedDistance(nil), "--")
        XCTAssertEqual(LiDARProcessing.formattedDistance(2.345), "2.35 m")
        XCTAssertEqual(LiDARProcessing.formattedDistance(12.34), "12.3 m")
    }

    func testAlertStateTransitionsFollowObstacleDistance() {
        XCTAssertEqual(LiDARProcessing.alertState(for: 2.4, isRunning: true, isFrozen: false), .clear)
        XCTAssertEqual(LiDARProcessing.alertState(for: 1.3, isRunning: true, isFrozen: false), .caution)
        XCTAssertEqual(LiDARProcessing.alertState(for: 0.55, isRunning: true, isFrozen: false), .danger)
        XCTAssertEqual(LiDARProcessing.alertState(for: nil, isRunning: true, isFrozen: false), .signalLost)
    }

    func testAlertPresetChangesSensitivity() {
        XCTAssertEqual(
            LiDARProcessing.alertState(for: 1.1, alertPreset: .near, isRunning: true, isFrozen: false),
            .clear
        )
        XCTAssertEqual(
            LiDARProcessing.alertState(for: 1.1, alertPreset: .early, isRunning: true, isFrozen: false),
            .danger
        )
    }

    func testClampMaxDepthKeepsConfiguredBounds() {
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(0.2), 1.0, accuracy: 0.001)
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(4.5), 4.5, accuracy: 0.001)
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(12.0), 8.0, accuracy: 0.001)
    }

    func testGuidancePrefersClearerRightLaneWhenCenterIsBlocked() {
        let snapshot = LiDARLaneSnapshot(left: 0.95, center: 0.82, right: 2.1)

        let guidance = LiDARProcessing.guidance(
            for: snapshot,
            forwardDistance: 0.8,
            signalQuality: .strong,
            isRunning: true,
            isFrozen: false
        )

        XCTAssertEqual(guidance, .moveRight)
    }

    func testGuidanceFallsBackToScanSlowlyWhenSignalIsWeak() {
        let snapshot = LiDARLaneSnapshot(left: 1.8, center: 1.7, right: 1.9)

        let guidance = LiDARProcessing.guidance(
            for: snapshot,
            forwardDistance: 1.6,
            signalQuality: .weak,
            isRunning: true,
            isFrozen: false
        )

        XCTAssertEqual(guidance, .scanSlowly)
    }

    func testRecommendedMaxDepthTracksSceneWithoutBreakingBounds() {
        let samples: [Float] = [0.8, 1.0, 1.2, 1.6, 2.3, 3.7, 4.9, 6.8, 9.4]

        let maxDepth = LiDARProcessing.recommendedMaxDepth(from: samples, focusDistance: 1.4)

        XCTAssertGreaterThanOrEqual(maxDepth, 3.0)
        XCTAssertLessThanOrEqual(maxDepth, 8.0)
    }

    func testViewportTransformScalesNormalizedTranslationIntoViewportSpace() {
        let normalized = CGAffineTransform(a: 1, b: 0, c: 0, d: 1, tx: 0.2, ty: 0.1)

        let transform = LiDARProcessing.viewportTransform(
            from: normalized,
            in: CGSize(width: 1000, height: 500)
        )

        XCTAssertEqual(transform.tx, 200, accuracy: 0.001)
        XCTAssertEqual(transform.ty, 50, accuracy: 0.001)
        XCTAssertEqual(transform.a, 1, accuracy: 0.001)
        XCTAssertEqual(transform.d, 1, accuracy: 0.001)
    }
}
