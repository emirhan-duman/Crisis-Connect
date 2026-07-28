//
//  CrisisSentinelSpeechDictation.swift
//  Crisis Connect
//

import AVFoundation
import Foundation
import Speech

@MainActor
final class CrisisSentinelSpeechDictation {
    private let audioEngine = AVAudioEngine()
    private let recognizer = SFSpeechRecognizer(locale: .current)
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var hasInstalledTap = false
    private(set) var latestTranscript = ""

    var isRunning: Bool {
        audioEngine.isRunning
    }

    func requestAuthorization() async -> Bool {
        let speechAllowed = await withCheckedContinuation { continuation in
            SFSpeechRecognizer.requestAuthorization { status in
                continuation.resume(returning: status == .authorized)
            }
        }
        guard speechAllowed else { return false }

        return await withCheckedContinuation { continuation in
            AVAudioApplication.requestRecordPermission { allowed in
                continuation.resume(returning: allowed)
            }
        }
    }

    func start(onTranscript: @escaping @MainActor (String, Bool) -> Void) throws {
        stop()
        guard let recognizer, recognizer.isAvailable else {
            throw DictationError.unavailable
        }

        let inputNode = audioEngine.inputNode
        let recognitionRequest = SFSpeechAudioBufferRecognitionRequest()
        recognitionRequest.shouldReportPartialResults = true
        request = recognitionRequest
        latestTranscript = ""

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.record, mode: .measurement, options: [.duckOthers])
        try session.setActive(true, options: .notifyOthersOnDeactivation)

        task = recognizer.recognitionTask(with: recognitionRequest) { [weak self] result, error in
            guard let self else { return }
            Task { @MainActor in
                if let result {
                    self.latestTranscript = result.bestTranscription.formattedString
                    onTranscript(self.latestTranscript, result.isFinal)
                }
                if error != nil || result?.isFinal == true {
                    self.stop()
                }
            }
        }

        let format = inputNode.outputFormat(forBus: 0)
        inputNode.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            recognitionRequest.append(buffer)
        }
        hasInstalledTap = true
        audioEngine.prepare()
        try audioEngine.start()
    }

    func stop() {
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        if hasInstalledTap {
            audioEngine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }
        request?.endAudio()
        request = nil
        task?.cancel()
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    enum DictationError: Error {
        case unavailable
    }
}
