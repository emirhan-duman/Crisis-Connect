import AVFoundation
import Combine
import FirebaseAuth
import Foundation

/// AVAudioRecorder.currentTime becomes zero after its duration limit auto-stops it.
/// Prefer the finalized file's duration; otherwise retain the live sample clock or monotonic elapsed time.
func musterRecordingDurationMillis(recorderSeconds: TimeInterval, startedUptime: TimeInterval?, currentUptime: TimeInterval,
                                   encodedSeconds: TimeInterval? = nil) -> Int64? {
    let limit = Double(MusterVoiceFiles.maximumDuration) / 1000
    if let encodedSeconds, encodedSeconds.isFinite, encodedSeconds > limit + 0.5 { return nil }
    let elapsed = startedUptime.map { currentUptime - $0 }
    guard let seconds = [encodedSeconds, recorderSeconds, elapsed].compactMap({ $0 }).first(where: { $0.isFinite && $0 > 0 }) else { return nil }
    // Clamp before conversion so even an anomalous large uptime cannot overflow Int64.
    return max(1, Int64((min(limit, seconds) * 1000).rounded()))
}

/// A foreground, explicit-gesture composer. Drafts are never uploaded or marked safe automatically.
@MainActor final class MusterAudio: ObservableObject {
    static var recordingSettings: [String: Any] { [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: 16_000,
        AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 24_000, AVEncoderAudioQualityKey: AVAudioQuality.medium.rawValue] }
    @Published private(set) var recording: MusterVoiceRecording?
    @Published private(set) var isRecording = false
    @Published private(set) var isPlaying = false
    @Published private(set) var isBusy = false
    @Published private(set) var elapsed = 0
    @Published var error = false
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var temporary: URL?
    private var timer: Task<Void, Never>?
    private var generation = UUID()
    private var startedUptime: TimeInterval?
    private var owner: String?
    private var ownsAudioSession = false
    private var sceneIsActive = false
    private var startAfterActivation = false

    func start(uid: String) async {
        guard sceneIsActive, !isRecording, !isBusy, Auth.auth().currentUser?.uid == uid else { return }
        reset(); let request = generation; owner = uid; isBusy = true
        let granted = await AVAudioApplication.requestRecordPermission()
        guard request == generation, owner == uid, Auth.auth().currentUser?.uid == uid else { return }
        guard granted else { isBusy = false; error = true; return }
        // The first microphone prompt can complete while the scene is still inactive.
        if sceneIsActive { beginRecording(uid: uid) }
        else { startAfterActivation = true }
    }

    func sceneActivityChanged(isActive: Bool) {
        sceneIsActive = isActive
        if isActive, startAfterActivation, let owner {
            beginRecording(uid: owner)
        } else if !isActive {
            if isRecording { stop() }
            stopPlayback()
        }
    }

    func handleInterruption(_ notification: Notification) {
        guard notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt == AVAudioSession.InterruptionType.began.rawValue else { return }
        if isRecording { stop() }
        stopPlayback()
    }

    private func beginRecording(uid: String) {
        startAfterActivation = false
        isBusy = false
        guard Auth.auth().currentUser?.uid == uid else { reset(); return }
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try audio.setActive(true); ownsAudioSession = true
            let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MusterDrafts", isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.complete])
            let existing = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey])
            var budget = 0
            for file in existing {
                let values = try file.resourceValues(forKeys: [.contentModificationDateKey, .fileSizeKey])
                // A recorder cannot remain active beyond 30 seconds. Old plaintext drafts are abandoned captures.
                if Date().timeIntervalSince(values.contentModificationDate ?? .distantPast) > 60 { try FileManager.default.removeItem(at: file) }
                else { budget += values.fileSize ?? 0 }
            }
            guard budget < 1024 * 1024 else { throw MusterError.capacity }
            let file = directory.appendingPathComponent(UUID().uuidString + ".m4a"); temporary = file
            let capture = try AVAudioRecorder(url: file, settings: Self.recordingSettings)
            recorder = capture; startedUptime = ProcessInfo.processInfo.systemUptime
            guard capture.record(forDuration: 29.8) else { throw MusterError.persistence }
            try FileManager.default.setAttributes([.protectionKey: FileProtectionType.complete], ofItemAtPath: file.path)
            isRecording = true
            timer = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(200))
                    guard !Task.isCancelled, let self, self.isRecording else { return }
                    guard Auth.auth().currentUser?.uid == uid else { self.reset(); return }
                    self.elapsed = min(30, max(0, Int(ProcessInfo.processInfo.systemUptime - (self.startedUptime ?? ProcessInfo.processInfo.systemUptime))))
                    if self.recorder?.isRecording != true || self.elapsed >= 30 { self.stop(); return }
                }
            }
        } catch { reset(); self.error = true }
    }
    func stop() {
        guard let recorder else { return }
        let recorderSeconds = recorder.currentTime, startedUptime = self.startedUptime, stoppedUptime = ProcessInfo.processInfo.systemUptime
        recorder.stop(); self.recorder = nil; self.startedUptime = nil
        timer?.cancel(); timer = nil; isRecording = false
        defer { clearTemporary(); deactivate() }
        guard owner == Auth.auth().currentUser?.uid, let temporary,
              let bytes = try? temporary.resourceValues(forKeys: [.fileSizeKey]).fileSize,
              (1...MusterVoiceFiles.maximumBytes).contains(bytes),
              let data = try? Data(contentsOf: temporary) else { error = true; return }
        let finalized = try? AVAudioFile(forReading: temporary)
        let encodedSeconds = finalized.map { Double($0.length) / $0.fileFormat.sampleRate }
        guard let duration = musterRecordingDurationMillis(recorderSeconds: recorderSeconds, startedUptime: startedUptime,
                                                          currentUptime: stoppedUptime, encodedSeconds: encodedSeconds) else { error = true; return }
        recording = MusterVoiceRecording(data: data, durationMillis: duration)
    }
    func preview() {
        guard let recording else { return }
        play(recording.data, uid: owner)
    }
    func play(_ data: Data, uid: String?) {
        guard let uid, Auth.auth().currentUser?.uid == uid, data.count <= MusterVoiceFiles.maximumBytes else { return }
        if isPlaying { stopPlayback(); return }
        do {
            owner = uid
            let audio = AVAudioSession.sharedInstance(); try audio.setCategory(.playback, mode: .spokenAudio); try audio.setActive(true); ownsAudioSession = true
            let player = try AVAudioPlayer(data: data)
            guard player.duration > 0, player.duration <= 30.5, player.play() else { throw MusterError.invalid }
            self.player = player; isPlaying = true
            timer = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(200))
                    guard !Task.isCancelled, let self else { return }
                    if Auth.auth().currentUser?.uid != uid { self.reset(); return }
                    if self.player?.isPlaying != true { self.stopPlayback(); return }
                }
            }
        } catch { deactivate(); self.error = true }
    }
    func suspend() {
        generation = UUID(); isBusy = false; startAfterActivation = false
        if isRecording { stop() }; stopPlayback()
    }
    func stopPlayback() {
        guard player != nil || isPlaying else { return }
        timer?.cancel(); timer = nil; player?.stop(); player = nil; isPlaying = false; deactivate()
    }
    func reset() {
        generation = UUID(); timer?.cancel(); timer = nil; recorder?.stop(); recorder = nil; player?.stop(); player = nil
        clearTemporary(); startedUptime = nil; recording = nil; isRecording = false; isPlaying = false; isBusy = false; startAfterActivation = false; elapsed = 0; owner = nil; error = false; deactivate()
    }
    private func clearTemporary() { if let temporary { try? FileManager.default.removeItem(at: temporary) }; temporary = nil }
    private func deactivate() {
        guard ownsAudioSession else { return }; ownsAudioSession = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}
