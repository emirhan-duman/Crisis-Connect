//
//  WhistleToneTests.swift
//  Crisis ConnectTests
//
//  Guards the whistle tone synthesis — especially the new modulated sweep/rescue paths ported
//  from Android's WhistleToneGenerator. A malformed buffer (NaN, clipping, zero length) would be
//  a nasty audio artifact in an emergency, so every mode must render finite, in-range samples.
//

import AVFoundation
import XCTest

@testable import Crisis_Connect

@MainActor
final class WhistleToneTests: XCTestCase {

    private func format() -> AVAudioFormat {
        AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
    }

    func testAllFiveModesExist() {
        XCTAssertEqual(WhistleViewModel.Mode.allCases.count, 5)
        XCTAssertTrue(WhistleViewModel.Mode.allCases.contains(.sweep))
        XCTAssertTrue(WhistleViewModel.Mode.allCases.contains(.rescue))
    }

    func testEveryModeRendersWellFormedSamples() {
        let viewModel = WhistleViewModel()
        let fmt = format()
        for mode in WhistleViewModel.Mode.allCases {
            let buffer = viewModel.debugRenderBuffer(mode: mode, frequency: 3_200, format: fmt)
            let unwrapped = try? XCTUnwrap(buffer)
            guard let buffer = unwrapped, let channel = buffer.floatChannelData else {
                XCTFail("mode \(mode.rawValue) produced no buffer")
                continue
            }
            XCTAssertGreaterThan(buffer.frameLength, 0, "mode \(mode.rawValue) is empty")
            var peak: Float = 0
            for frame in 0..<Int(buffer.frameLength) {
                let sample = channel[0][frame]
                XCTAssertTrue(sample.isFinite, "mode \(mode.rawValue) has a non-finite sample")
                peak = max(peak, abs(sample))
            }
            // Never clip past full scale, and a tone mode should actually make sound.
            XCTAssertLessThanOrEqual(peak, 1.0, "mode \(mode.rawValue) clips past full scale")
            XCTAssertGreaterThan(peak, 0.1, "mode \(mode.rawValue) is essentially silent")
        }
    }

    func testModulatedModesFlaggedForRichSynthesis() {
        XCTAssertTrue(WhistleViewModel.Mode.sweep.isModulated)
        XCTAssertTrue(WhistleViewModel.Mode.rescue.isModulated)
        XCTAssertFalse(WhistleViewModel.Mode.continuous.isModulated)
        XCTAssertFalse(WhistleViewModel.Mode.sos.isModulated)
    }
}
