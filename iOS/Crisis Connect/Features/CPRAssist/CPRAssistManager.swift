import AVFoundation
import Combine
import Foundation
import UIKit

@MainActor
final class CPRAssistManager: ObservableObject {
    @Published var mode: CPRAssistMode = .handsOnly
    @Published private(set) var phase: CPRAssistPhase = .ready
    @Published private(set) var pauseReason: CPRPauseReason?
    @Published private(set) var compressionInSet = 0
    @Published private(set) var totalCompressions = 0
    @Published private(set) var completedSets = 0
    @Published private(set) var elapsed: TimeInterval = 0
    @Published private(set) var roundElapsed: TimeInterval = 0
    @Published private(set) var completedRounds = 0
    @Published private(set) var breathRemaining: TimeInterval = 0
    @Published private(set) var beatSequence = 0
    @Published var soundEnabled = true
    @Published var voiceEnabled = true {
        didSet {
            if !voiceEnabled { audio.stopSpeaking() }
        }
    }
    @Published var hapticsEnabled = false
    @Published var isAEDGuidePresented = false
    @Published private(set) var aedStep: CPRAEDStep = .powerOn

    private let audio = CPRAssistAudioController()
    private var tickerTask: Task<Void, Never>?
    private var beatAccumulator: TimeInterval = 0
    private var lastTickUptime: TimeInterval = 0
    private let haptic = UIImpactFeedbackGenerator(style: .rigid)

    var isSessionRunning: Bool {
        phase == .compressions || phase == .breaths
    }

    var isPaused: Bool { pauseReason != nil }

    var roundRemaining: TimeInterval {
        CPRAssistTiming.roundRemaining(elapsed: roundElapsed)
    }

    func startSession() {
        tickerTask?.cancel()
        beatAccumulator = 0
        lastTickUptime = ProcessInfo.processInfo.systemUptime
        phase = .compressions
        pauseReason = nil
        compressionInSet = 0
        totalCompressions = 0
        completedSets = 0
        elapsed = 0
        roundElapsed = 0
        completedRounds = 0
        breathRemaining = 0
        beatSequence = 0
        isAEDGuidePresented = false
        aedStep = .powerOn
        audio.activate()
        speak("CPR_VOICE_START")
        emitCompressionBeat()
        startTicker()
    }

    func togglePause() {
        guard isSessionRunning, pauseReason != .aedAnalysis else { return }
        if pauseReason == .manual {
            pauseReason = nil
            lastTickUptime = ProcessInfo.processInfo.systemUptime
            speak("CPR_VOICE_RESUME")
        } else {
            pauseReason = .manual
            audio.stopSpeaking()
        }
    }

    func resumeCompressionsEarly() {
        guard phase == .breaths else { return }
        beatAccumulator = 0
        phase = .compressions
        breathRemaining = 0
        compressionInSet = 0
        speak("CPR_VOICE_RESUME")
    }

    func endSession() {
        tickerTask?.cancel()
        tickerTask = nil
        audio.stop()
        phase = .ended
        pauseReason = nil
        isAEDGuidePresented = false
    }

    func resetSession() {
        phase = .ready
        pauseReason = nil
        compressionInSet = 0
        totalCompressions = 0
        completedSets = 0
        elapsed = 0
        roundElapsed = 0
        completedRounds = 0
        breathRemaining = 0
        beatSequence = 0
        isAEDGuidePresented = false
        aedStep = .powerOn
    }

    func openAEDGuide() {
        guard isSessionRunning else { return }
        aedStep = .powerOn
        isAEDGuidePresented = true
        speak("CPR_VOICE_AED_ARRIVED")
    }

    func closeAEDGuideBeforeAnalysis() {
        guard aedStep == .powerOn || aedStep == .attachPads else { return }
        isAEDGuidePresented = false
    }

    func advanceAEDGuide() {
        switch aedStep {
        case .powerOn:
            aedStep = .attachPads
        case .attachPads:
            aedStep = .analyze
            pauseReason = .aedAnalysis
            speak("CPR_VOICE_CLEAR_ANALYSIS")
        case .analyze:
            aedStep = .shockDecision
        case .shockDecision, .resumeCPR:
            break
        }
    }

    func recordAEDDecision() {
        guard aedStep == .shockDecision else { return }
        beatAccumulator = 0
        lastTickUptime = ProcessInfo.processInfo.systemUptime
        aedStep = .resumeCPR
        pauseReason = nil
        phase = .compressions
        compressionInSet = 0
        breathRemaining = 0
        roundElapsed = 0
        speak("CPR_VOICE_RESUME_AFTER_AED")
        emitCompressionBeat()
    }

    func resumeAfterAED() {
        guard aedStep == .resumeCPR else { return }
        isAEDGuidePresented = false
        aedStep = .powerOn
    }

    private func startTicker() {
        tickerTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: CPRAssistTiming.tickResolution)
                guard !Task.isCancelled, let self else { return }
                self.updateClock()
            }
        }
    }

    private func updateClock() {
        let now = ProcessInfo.processInfo.systemUptime
        let delta = min(max(now - lastTickUptime, 0), 0.25)
        lastTickUptime = now
        guard isSessionRunning, !isPaused else { return }

        elapsed += delta
        roundElapsed += delta
        if roundElapsed >= CPRAssistTiming.roundDuration {
            roundElapsed.formTruncatingRemainder(dividingBy: CPRAssistTiming.roundDuration)
            completedRounds += 1
            speak("CPR_VOICE_TWO_MINUTES")
        }

        if phase == .breaths {
            breathRemaining = max(0, breathRemaining - delta)
            if breathRemaining <= 0 {
                resumeCompressionsEarly()
            }
            return
        }

        beatAccumulator += delta
        while beatAccumulator >= CPRAssistTiming.beatInterval {
            beatAccumulator -= CPRAssistTiming.beatInterval
            emitCompressionBeat()
            if phase != .compressions { break }
        }
    }

    private func emitCompressionBeat() {
        guard phase == .compressions, !isPaused else { return }
        let next = CPRAssistTiming.nextCompression(inSet: compressionInSet)
        compressionInSet = next
        totalCompressions += 1
        beatSequence += 1
        if CPRAssistTiming.completedSet(after: next) {
            completedSets += 1
        }

        if soundEnabled { audio.playBeat() }
        if hapticsEnabled {
            haptic.prepare()
            haptic.impactOccurred(intensity: 0.65)
        }

        if next == CPRAssistTiming.compressionsPerSet, mode == .thirtyToTwo {
            beatAccumulator = 0
            phase = .breaths
            breathRemaining = CPRAssistTiming.breathPause
            speak("CPR_VOICE_TWO_BREATHS")
        }
    }

    private func speak(_ key: String) {
        guard voiceEnabled else { return }
        audio.speak(CPRText.value(key))
    }

    deinit {
        tickerTask?.cancel()
    }
}

@MainActor
private final class CPRAssistAudioController {
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let speech = AVSpeechSynthesizer()
    private var beatBuffer: AVAudioPCMBuffer?
    private var isPrepared = false

    func activate() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: [.duckOthers])
        try? session.setActive(true)
        prepareIfNeeded()
        if !engine.isRunning { try? engine.start() }
    }

    func playBeat() {
        prepareIfNeeded()
        guard let beatBuffer else { return }
        if !engine.isRunning { try? engine.start() }
        player.scheduleBuffer(beatBuffer, at: nil, options: .interrupts)
        if !player.isPlaying { player.play() }
    }

    func speak(_ text: String) {
        guard !text.isEmpty else { return }
        speech.stopSpeaking(at: .immediate)
        let utterance = AVSpeechUtterance(string: text)
        let language = Bundle.main.preferredLocalizations.first ?? Locale.current.identifier
        utterance.voice = AVSpeechSynthesisVoice(language: language)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate * 0.92
        utterance.volume = 1
        speech.speak(utterance)
    }

    func stopSpeaking() {
        speech.stopSpeaking(at: .immediate)
    }

    func stop() {
        speech.stopSpeaking(at: .immediate)
        player.stop()
        engine.pause()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func prepareIfNeeded() {
        guard !isPrepared else { return }
        let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 1)!
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        beatBuffer = Self.makeBeatBuffer(format: format)
        engine.prepare()
        isPrepared = true
    }

    private static func makeBeatBuffer(format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let duration = 0.075
        let frames = AVAudioFrameCount(format.sampleRate * duration)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let samples = buffer.floatChannelData?[0]
        else { return nil }
        buffer.frameLength = frames
        for frame in 0..<Int(frames) {
            let time = Double(frame) / format.sampleRate
            let envelope = max(0, 1 - time / duration)
            samples[frame] = Float(sin(2 * Double.pi * 880 * time) * envelope * 0.72)
        }
        return buffer
    }
}

enum CPRText {
    static func value(_ key: String) -> String {
        NSLocalizedString(key, tableName: "CPR", bundle: .main, value: key, comment: "")
    }
}
