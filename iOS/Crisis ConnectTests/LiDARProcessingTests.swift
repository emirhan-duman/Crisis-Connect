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

    func testObstacleEscalationIsImmediate() {
        let transition = LiDARProcessing.stabilizedAlertState(
            current: .clear,
            proposed: .danger,
            distanceMeters: 0.52,
            alertPreset: .balanced,
            saferFrameStreak: 0
        )

        XCTAssertEqual(transition.state, .danger)
        XCTAssertEqual(transition.saferFrameStreak, 0)
    }

    func testDangerNeedsThreeSaferFramesBeforeClearing() {
        let first = LiDARProcessing.stabilizedAlertState(
            current: .danger,
            proposed: .clear,
            distanceMeters: 2.0,
            alertPreset: .balanced,
            saferFrameStreak: 0
        )
        let second = LiDARProcessing.stabilizedAlertState(
            current: first.state,
            proposed: .clear,
            distanceMeters: 2.0,
            alertPreset: .balanced,
            saferFrameStreak: first.saferFrameStreak
        )
        let third = LiDARProcessing.stabilizedAlertState(
            current: second.state,
            proposed: .clear,
            distanceMeters: 2.0,
            alertPreset: .balanced,
            saferFrameStreak: second.saferFrameStreak
        )

        XCTAssertEqual(first.state, .danger)
        XCTAssertEqual(second.state, .danger)
        XCTAssertEqual(third.state, .clear)
    }

    func testDangerDoesNotReleaseInsideHysteresisBand() {
        let transition = LiDARProcessing.stabilizedAlertState(
            current: .danger,
            proposed: .caution,
            distanceMeters: 0.9,
            alertPreset: .balanced,
            saferFrameStreak: 2
        )

        XCTAssertEqual(transition.state, .danger)
        XCTAssertEqual(transition.saferFrameStreak, 0)
    }

    func testClampMaxDepthKeepsConfiguredBounds() {
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(0.2), 1.0, accuracy: 0.001)
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(4.5), 4.5, accuracy: 0.001)
        XCTAssertEqual(LiDARProcessing.clampMaxDepth(12.0), 5.0, accuracy: 0.001)
    }

    func testSignalQualityUsesMeasuredCoverage() {
        XCTAssertEqual(LiDARProcessing.signalQuality(for: 0.12), .weak)
        XCTAssertEqual(LiDARProcessing.signalQuality(for: 0.3), .medium)
        XCTAssertEqual(LiDARProcessing.signalQuality(for: 0.62), .strong)
    }

    func testRecommendedMaxDepthTracksSceneWithoutBreakingBounds() {
        let samples: [Float] = [0.8, 1.0, 1.2, 1.6, 2.3, 3.7, 4.9, 6.8, 9.4]

        let maxDepth = LiDARProcessing.recommendedMaxDepth(from: samples, focusDistance: 1.4)

        XCTAssertGreaterThanOrEqual(maxDepth, 3.0)
        XCTAssertLessThanOrEqual(maxDepth, 5.0)
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
