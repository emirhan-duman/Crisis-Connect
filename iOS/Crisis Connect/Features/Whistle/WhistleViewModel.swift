//
//  WhistleViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 28.12.2025
//

import AVFoundation
import Combine
import SwiftUI

final class WhistleViewModel: ObservableObject {
    enum Mode: String, CaseIterable, Identifiable {
        case continuous
        case pulse
        case sos

        var id: String { rawValue }

        var titleKey: LocalizedStringKey {
            switch self {
            case .continuous:
                return "WHISTLE_MODE_CONTINUOUS"
            case .pulse:
                return "WHISTLE_MODE_PULSE"
            case .sos:
                return "WHISTLE_MODE_SOS"
            }
        }

        var descriptionKey: LocalizedStringKey {
            switch self {
            case .continuous:
                return "WHISTLE_MODE_CONTINUOUS_DETAIL"
            case .pulse:
                return "WHISTLE_MODE_PULSE_DETAIL"
            case .sos:
                return "WHISTLE_MODE_SOS_DETAIL"
            }
        }
    }

    @Published var frequency: Double
    @Published var mode: Mode {
        didSet {
            defaults.set(mode.rawValue, forKey: Keys.mode)
            if isPlaying {
                restartPlayback()
            }
        }
    }
    @Published private(set) var isPlaying: Bool = false
    @Published var showLoudnessWarning: Bool = false

    private let defaults: UserDefaults
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private var buffer: AVAudioPCMBuffer?

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let storedFrequency = defaults.object(forKey: Keys.frequency) as? Double
        let clampedFrequency = min(max(storedFrequency ?? Limits.defaultFrequency,
                                       Limits.minFrequency),
                                   Limits.maxFrequency)
        self.frequency = clampedFrequency
        if let storedMode = defaults.string(forKey: Keys.mode),
           let parsedMode = Mode(rawValue: storedMode) {
            self.mode = parsedMode
        } else {
            self.mode = .continuous
        }

        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: currentOutputFormat())
    }

    var statusTitleKey: LocalizedStringKey {
        isPlaying ? "WHISTLE_STATUS_ACTIVE" : "WHISTLE_STATUS_READY"
    }

    var actionTitleKey: LocalizedStringKey {
        isPlaying ? "WHISTLE_STOP" : "WHISTLE_START"
    }

    var statusSystemImage: String {
        isPlaying ? "speaker.wave.3.fill" : "speaker.slash.fill"
    }

    var frequencyRange: ClosedRange<Double> {
        Limits.minFrequency...Limits.maxFrequency
    }

    var frequencyStep: Double {
        50
    }

    var actionSystemImage: String {
        isPlaying ? "stop.fill" : "play.fill"
    }

    func togglePlayback() {
        isPlaying ? stop() : requestStart()
    }

    func requestStart() {
        guard defaults.bool(forKey: Keys.warningAcknowledged) else {
            showLoudnessWarning = true
            return
        }
        startPlayback()
    }

    func confirmWarningAndStart() {
        defaults.set(true, forKey: Keys.warningAcknowledged)
        showLoudnessWarning = false
        startPlayback()
    }

    func stop() {
        stopPlayback(deactivateSession: true)
    }

    func frequencyEditingChanged(_ isEditing: Bool) {
        guard !isEditing else { return }
        let clamped = min(max(frequency, Limits.minFrequency), Limits.maxFrequency)
        if clamped != frequency {
            frequency = clamped
        }
        defaults.set(frequency, forKey: Keys.frequency)
        if isPlaying {
            restartPlayback()
        }
    }

    private func restartPlayback() {
        startPlayback()
    }

    private func startPlayback() {
        stopPlayback(deactivateSession: false)
        configureSession()
        let format = currentOutputFormat()
        engine.disconnectNodeOutput(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        guard let buffer = makePatternBuffer(frequency: frequency, mode: mode, format: format) else {
            return
        }
        self.buffer = buffer
        player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
        engine.prepare()
        do {
            try engine.start()
        } catch {
            isPlaying = false
            return
        }
        player.play()
        isPlaying = true
    }

    private func stopPlayback(deactivateSession: Bool) {
        player.stop()
        if engine.isRunning {
            engine.stop()
        }
        engine.reset()
        buffer = nil
        isPlaying = false
        if deactivateSession {
            try? AVAudioSession.sharedInstance()
                .setActive(false, options: .notifyOthersOnDeactivation)
        }
    }

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.duckOthers])
            try session.setActive(true)
        } catch {
        }
    }

    private func currentOutputFormat() -> AVAudioFormat {
        let output = engine.mainMixerNode.outputFormat(forBus: 0)
        let sampleRate = output.sampleRate > 0 ? output.sampleRate : 44_100
        let channels = AVAudioChannelCount(max(1, Int(output.channelCount)))
        return AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: channels) ?? output
    }

    private struct ToneSegment {
        let duration: Double
        let isTone: Bool
    }

    private func segments(for mode: Mode) -> [ToneSegment] {
        switch mode {
        case .continuous:
            return [ToneSegment(duration: 1.0, isTone: true)]
        case .pulse:
            return [
                ToneSegment(duration: 0.28, isTone: true),
                ToneSegment(duration: 0.18, isTone: false)
            ]
        case .sos:
            let short = 0.2
            let long = 0.6
            let intraGap = 0.2
            let letterGap = 0.5
            let endGap = 1.0
            var segments: [ToneSegment] = []
            func addSignals(_ durations: [Double]) {
                for (index, duration) in durations.enumerated() {
                    segments.append(ToneSegment(duration: duration, isTone: true))
                    if index < durations.count - 1 {
                        segments.append(ToneSegment(duration: intraGap, isTone: false))
                    }
                }
            }
            addSignals([short, short, short])
            segments.append(ToneSegment(duration: letterGap, isTone: false))
            addSignals([long, long, long])
            segments.append(ToneSegment(duration: letterGap, isTone: false))
            addSignals([short, short, short])
            segments.append(ToneSegment(duration: endGap, isTone: false))
            return segments
        }
    }

    private func makePatternBuffer(frequency: Double,
                                   mode: Mode,
                                   format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let sampleRate = format.sampleRate > 0 ? format.sampleRate : 44_100
        let channels = max(1, Int(format.channelCount))

        let segments = segments(for: mode)
        let segmentFrames = segments.map { max(1, Int($0.duration * sampleRate)) }
        let totalFrames = segmentFrames.reduce(0, +)
        guard totalFrames > 0 else { return nil }

        guard let buffer = AVAudioPCMBuffer(pcmFormat: format,
                                            frameCapacity: AVAudioFrameCount(totalFrames)),
              let channelData = buffer.floatChannelData else {
            return nil
        }

        buffer.frameLength = AVAudioFrameCount(totalFrames)
        let amplitude: Float = 0.8
        let phaseStep = 2.0 * Double.pi * frequency / sampleRate
        var writeIndex = 0

        for (index, segment) in segments.enumerated() {
            let frames = segmentFrames[index]
            if segment.isTone {
                var phase = 0.0
                let fadeSamples = min(frames / 2, Int(sampleRate * 0.005))
                let fadeOutStart = frames - fadeSamples
                for frame in 0..<frames {
                    var amp = amplitude
                    if fadeSamples > 0 {
                        if frame < fadeSamples {
                            amp *= Float(frame) / Float(fadeSamples)
                        } else if frame >= fadeOutStart {
                            amp *= Float(frames - frame) / Float(fadeSamples)
                        }
                    }
                    let sample = Float(sin(phase)) * amp
                    for channel in 0..<channels {
                        channelData[channel][writeIndex] = sample
                    }
                    writeIndex += 1
                    phase += phaseStep
                    if phase >= Double.pi * 2 {
                        phase -= Double.pi * 2
                    }
                }
            } else {
                for _ in 0..<frames {
                    for channel in 0..<channels {
                        channelData[channel][writeIndex] = 0
                    }
                    writeIndex += 1
                }
            }
        }

        return buffer
    }

    private enum Limits {
        static let minFrequency: Double = 2_000
        static let maxFrequency: Double = 4_500
        static let defaultFrequency: Double = 3_200
    }

    private enum Keys {
        static let frequency = "whistle.frequency"
        static let mode = "whistle.mode"
        static let warningAcknowledged = "whistle.warning.acknowledged"
    }

    deinit {
        stopPlayback(deactivateSession: true)
    }
}
