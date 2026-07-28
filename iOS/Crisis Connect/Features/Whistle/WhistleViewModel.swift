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
        case sweep
        case rescue

        var id: String { rawValue }

        /// Sweep and rescue vary the frequency per sample (siren-like), so they use the richer
        /// multi-oscillator synthesis; the other three keep the plain-sine path unchanged.
        var isModulated: Bool { self == .sweep || self == .rescue }

        var titleKey: LocalizedStringKey {
            switch self {
            case .continuous:
                return "WHISTLE_MODE_CONTINUOUS"
            case .pulse:
                return "WHISTLE_MODE_PULSE"
            case .sos:
                return "WHISTLE_MODE_SOS"
            case .sweep:
                return "WHISTLE_MODE_SWEEP"
            case .rescue:
                return "WHISTLE_MODE_RESCUE"
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
            case .sweep:
                return "WHISTLE_MODE_SWEEP_DETAIL"
            case .rescue:
                return "WHISTLE_MODE_RESCUE_DETAIL"
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
        /// The tone's base frequency; nil means "use the slider frequency". Only the rescue bursts
        /// set an explicit per-segment frequency (mirroring Android's WhistleToneGenerator).
        var baseFrequency: Double? = nil
    }

    private func segments(for mode: Mode, frequency: Double) -> [ToneSegment] {
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
        case .sweep:
            // One long tone; the frequency sweeps within it (see modulatedFrequency).
            return [ToneSegment(duration: 1.2, isTone: true)]
        case .rescue:
            // Four rising chirp bursts at spread frequencies, Android's WhistleToneGenerator layout.
            let clamp = { (value: Double) in min(max(value, Limits.minFrequency), Limits.maxFrequency) }
            return [
                ToneSegment(duration: 0.21, isTone: true, baseFrequency: clamp(frequency - 420)),
                ToneSegment(duration: 0.08, isTone: false),
                ToneSegment(duration: 0.21, isTone: true, baseFrequency: frequency),
                ToneSegment(duration: 0.08, isTone: false),
                ToneSegment(duration: 0.21, isTone: true, baseFrequency: clamp(frequency + 280)),
                ToneSegment(duration: 0.12, isTone: false),
                ToneSegment(duration: 0.36, isTone: true, baseFrequency: clamp(frequency + 620)),
                ToneSegment(duration: 0.28, isTone: false)
            ]
        }
    }

    // MARK: - Modulated synthesis (sweep + rescue) — port of Android's WhistleToneGenerator

    private enum Synth {
        static let sweepSpanHz: Double = 1_400
        static let sweepMinHz: Double = 1_800
        static let sweepMaxHz: Double = 4_800
        static let rescueChirpSpanHz: Double = 220
        static let rescueTremoloHz: Double = 17.5
        static let envelopeEdgeMs: Double = 6
    }

    /// The instantaneous tone frequency at a sample within a tone segment: a triangle sweep across
    /// the band for `.sweep`, a rising chirp around the burst's base for `.rescue`.
    private func modulatedFrequency(
        mode: Mode,
        segmentBase: Double,
        sampleInSegment: Int,
        segmentSamples: Int
    ) -> Double {
        switch mode {
        case .sweep:
            let halfSpan = Synth.sweepSpanHz / 2
            let minF = max(segmentBase - halfSpan, Synth.sweepMinHz)
            let maxF = min(segmentBase + halfSpan, Synth.sweepMaxHz)
            guard maxF > minF, segmentSamples > 0 else { return segmentBase }
            let progress = Double(sampleInSegment) / Double(segmentSamples)
            let triangle = progress <= 0.5 ? progress * 2 : (1 - progress) * 2
            return minF + (maxF - minF) * triangle
        case .rescue:
            guard segmentSamples > 1 else { return segmentBase }
            let progress = Double(sampleInSegment) / Double(max(1, segmentSamples - 1))
            let chirp = (progress - 0.5) * 2
            return min(max(segmentBase + chirp * Synth.rescueChirpSpanHz, Synth.sweepMinHz), Synth.sweepMaxHz)
        default:
            return segmentBase
        }
    }

    private func edgeEnvelope(sampleInSegment: Int, segmentSamples: Int, edgeSamples: Int) -> Double {
        guard segmentSamples > edgeSamples * 2, edgeSamples > 0 else { return 1.0 }
        let attack = Double(sampleInSegment) / Double(edgeSamples)
        let release = Double(segmentSamples - sampleInSegment - 1) / Double(edgeSamples)
        return min(1.0, max(0.0, min(attack, release)))
    }

    /// Buffer for the modulated modes: three detuned oscillators (fundamental + two sidebands),
    /// tanh soft-clip drive, a rescue-only tremolo, and 6 ms edge envelopes — Android's synthesis.
    private func makeModulatedBuffer(frequency: Double,
                                     mode: Mode,
                                     format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let sampleRate = format.sampleRate > 0 ? format.sampleRate : 44_100
        let channels = max(1, Int(format.channelCount))
        let segments = segments(for: mode, frequency: frequency)
        let segmentFrames = segments.map { max(1, Int($0.duration * sampleRate)) }
        let totalFrames = segmentFrames.reduce(0, +)
        guard totalFrames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(totalFrames)),
              let channelData = buffer.floatChannelData else {
            return nil
        }
        buffer.frameLength = AVAudioFrameCount(totalFrames)

        let amplitude = 0.8
        let edgeSamples = max(1, Int((Synth.envelopeEdgeMs / 1000) * sampleRate))
        let drive = mode == .rescue ? 1.32 : 1.15
        var mainPhase = 0.0, lowerPhase = 0.0, upperPhase = 0.0
        var writeIndex = 0
        var globalSample = 0

        for (index, segment) in segments.enumerated() {
            let frames = segmentFrames[index]
            let base = segment.baseFrequency ?? frequency
            for local in 0..<frames {
                var sample: Float = 0
                if segment.isTone {
                    let f = modulatedFrequency(mode: mode, segmentBase: base, sampleInSegment: local, segmentSamples: frames)
                    let lowerF = max(f * (mode == .rescue ? 0.86 : 0.90), Synth.sweepMinHz)
                    let upperF = min(f * (mode == .rescue ? 1.12 : 1.08), Synth.sweepMaxHz)
                    mainPhase = advance(mainPhase, frequency: f, sampleRate: sampleRate)
                    lowerPhase = advance(lowerPhase, frequency: lowerF, sampleRate: sampleRate)
                    upperPhase = advance(upperPhase, frequency: upperF, sampleRate: sampleRate)

                    let fundamental = sin(mainPhase)
                    let lowerBand = sin(lowerPhase + 0.18)
                    let upperBand = sin(upperPhase + 0.31)
                    let tremolo = mode == .rescue
                        ? 0.78 + 0.22 * sin(2 * Double.pi * Synth.rescueTremoloHz * Double(globalSample) / sampleRate)
                        : 1.0
                    let mixed = mode == .rescue
                        ? fundamental * 0.56 + lowerBand * 0.20 + upperBand * 0.24
                        : fundamental * 0.70 + lowerBand * 0.18 + upperBand * 0.12
                    let shaped = tanh(mixed * drive)
                    let envelope = edgeEnvelope(sampleInSegment: local, segmentSamples: frames, edgeSamples: edgeSamples)
                    sample = Float(shaped * tremolo * envelope * amplitude)
                }
                for channel in 0..<channels {
                    channelData[channel][writeIndex] = sample
                }
                writeIndex += 1
                globalSample += 1
            }
        }
        return buffer
    }

    private func advance(_ phase: Double, frequency: Double, sampleRate: Double) -> Double {
        var next = phase + 2 * Double.pi * frequency / sampleRate
        if next >= 2 * Double.pi { next -= 2 * Double.pi }
        return next
    }

    private func makePatternBuffer(frequency: Double,
                                   mode: Mode,
                                   format: AVAudioFormat) -> AVAudioPCMBuffer? {
        // Sweep + rescue need per-sample frequency modulation → the richer synthesis path.
        // continuous / pulse / sos keep the original plain-sine path untouched.
        if mode.isModulated {
            return makeModulatedBuffer(frequency: frequency, mode: mode, format: format)
        }

        let sampleRate = format.sampleRate > 0 ? format.sampleRate : 44_100
        let channels = max(1, Int(format.channelCount))

        let segments = segments(for: mode, frequency: frequency)
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

#if DEBUG
    /// Test seam: render a mode's loop buffer without touching the audio engine, so the DSP
    /// (especially the new modulated sweep/rescue paths) can be checked for well-formedness.
    func debugRenderBuffer(mode: Mode, frequency: Double, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        makePatternBuffer(frequency: frequency, mode: mode, format: format)
    }
#endif
}
