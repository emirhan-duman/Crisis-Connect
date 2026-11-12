import CoreGraphics
import XCTest
@testable import Crisis_Connect

final class DuoResizingTests: XCTestCase {
    func testResizeDuringSOSCancellationRequiresFreshGesture() {
        let wide = CGRect(x: 0, y: 0, width: 700, height: 62)
        let narrow = CGRect(x: 0, y: 0, width: 300, height: 62)
        // A drag which was incomplete on the inner screen must not complete after folding.
        XCTAssertFalse(SOSSlideCancellationPolicy.canComplete(offset: 240, travel: 240, startingRegion: wide, currentRegion: narrow))
        XCTAssertTrue(SOSSlideCancellationPolicy.canComplete(offset: 240, travel: 240, startingRegion: narrow, currentRegion: narrow))
        XCTAssertFalse(SOSSlideCancellationPolicy.canComplete(offset: 0, travel: 0, startingRegion: narrow, currentRegion: narrow))
        // Returning to the original size during the same touch must not re-arm the old drag.
        XCTAssertFalse(SOSSlideCancellationPolicy.canComplete(offset: 600, travel: 640, startingRegion: wide, currentRegion: wide, wasResized: true))
    }

    func testCriticalControlsAvoidVerticalHingeAndActiveCamera() {
        let bounds = CGRect(x: 0, y: 0, width: 1000, height: 700)
        let hinge = CGRect(x: 490, y: 0, width: 20, height: 700)
        let camera = CGRect(x: 720, y: 0, width: 60, height: 40)
        let region = AdaptivePresentationRegion.frame(in: bounds, avoiding: [hinge, camera])
        XCTAssertTrue(bounds.contains(region))
        XCTAssertGreaterThan(region.width * region.height, 0)
        XCTAssertFalse(region.intersects(hinge))
        XCTAssertFalse(region.intersects(camera))
    }

    func testCriticalControlsUseLowerRegionInTabletopPose() {
        let bounds = CGRect(x: 0, y: 0, width: 700, height: 1000)
        let hinge = CGRect(x: 0, y: 490, width: 700, height: 20)
        let region = AdaptivePresentationRegion.frame(in: bounds, avoiding: [hinge])
        XCTAssertEqual(region, CGRect(x: 0, y: 510, width: 700, height: 490))
        XCTAssertEqual(AdaptivePresentationRegion.frame(in: bounds, avoiding: []), bounds)
    }

    func testChatControlsFitThroughContinuousNarrowToWideResize() {
        // Include transient zero-size layout and widths smaller than a normal iPhone.
        for width in stride(from: CGFloat(0), through: 1600, by: 7) {
            let layout = ChatLayoutMetrics(availableWidth: width)
            XCTAssertLessThanOrEqual(layout.columnWidth + layout.outerHorizontalPadding * 2, width)
            XCTAssertLessThanOrEqual(layout.bubbleMaxWidth + layout.messageEdgeSpacer, layout.transcriptWidth + 0.001)
            XCTAssertGreaterThanOrEqual(layout.audioBubbleMinWidth, 0)
            XCTAssertLessThanOrEqual(layout.audioBubbleMinWidth, layout.audioBubbleMaxWidth)
            XCTAssertLessThanOrEqual(layout.audioBubbleMaxWidth, layout.bubbleMaxWidth)
            XCTAssertLessThanOrEqual(layout.loadMoreButtonWidth, layout.transcriptWidth)
        }
    }

    func testWideChatKeepsReadableColumnAndReturnsToNarrowWidth() {
        let wide = ChatLayoutMetrics(availableWidth: 1400)
        XCTAssertTrue(wide.isWideLayout)
        XCTAssertLessThanOrEqual(wide.columnWidth, 860)
        XCTAssertLessThanOrEqual(wide.bubbleMaxWidth, 540)
        let narrow = ChatLayoutMetrics(availableWidth: 280)
        XCTAssertFalse(narrow.isWideLayout)
        XCTAssertEqual(narrow.viewportWidth, 280)
        XCTAssertEqual(narrow.columnWidth, 280)
    }

    func testScreenShareScaleFollowsActualCaptureDimensionsAcrossResize() {
        for preset in ScreenShareQualityPreset.allCases {
            for size in [CGSize(width: 320, height: 240), CGSize(width: 3840, height: 2160),
                         CGSize(width: 2160, height: 3840), CGSize(width: 4000, height: 3000)] {
                let scale = preset.resolutionScale(for: size)
                XCTAssertGreaterThanOrEqual(scale, 1)
                let encoded = CGSize(width: size.width / scale, height: size.height / scale)
                XCTAssertLessThanOrEqual(max(encoded.width, encoded.height), CGFloat(preset.maxWidth) + 0.001)
                XCTAssertLessThanOrEqual(min(encoded.width, encoded.height), CGFloat(preset.maxHeight) + 0.001)
                XCTAssertEqual(scale, preset.resolutionScale(for: CGSize(width: size.height, height: size.width)))
            }
        }
        XCTAssertEqual(ScreenShareQualityPreset.auto.resolutionScale(for: .zero), 1)
    }

    @MainActor
    func testScannerPreviewFitsNarrowAndShallowContainers() {
        for size in [CGSize(width: 280, height: 300), CGSize(width: 390, height: 420),
                     CGSize(width: 900, height: 600), CGSize(width: 1200, height: 900)] {
            for compact in [true, false] {
                let height = ContactQRScannerScreen.viewportHeight(availableSize: size, compact: compact)
                XCTAssertGreaterThan(height, 0)
                XCTAssertLessThanOrEqual(height, min(size.width, 600) - 40)
                XCTAssertLessThanOrEqual(height, 520)
            }
        }
    }
}
