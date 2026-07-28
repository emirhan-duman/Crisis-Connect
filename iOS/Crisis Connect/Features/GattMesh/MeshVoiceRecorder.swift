//
//  MeshVoiceRecorder.swift
//  Crisis Connect
//
//  AAC/M4A voice-note recorder for the authority mesh chat (mirrors Android's MediaRecorder
//  setup: AAC, 16kHz mono, 24kbps, ≤90s, ≤400KB).
//

import AVFoundation
import Combine
import Foundation

@MainActor
final class MeshVoiceRecorder: ObservableObject {

    @Published private(set) var isRecording = false

    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private var startedAt: Date?

    private static let minDurationMillis: Int64 = 700

    /// Requests mic permission and starts recording. Returns whether recording started.
    @discardableResult
    func start() async -> Bool {
        guard !isRecording else { return true }
        let granted: Bool
        if #available(iOS 17.0, *) {
            granted = await AVAudioApplication.requestRecordPermission()
        } else {
            granted = await withCheckedContinuation { continuation in
                AVAudioSession.sharedInstance().requestRecordPermission { allowed in
                    continuation.resume(returning: allowed)
                }
            }
        }
        guard granted else { return false }

        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try session.setActive(true)

            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("mesh_voice_\(Int(Date().timeIntervalSince1970)).m4a")
            let settings: [String: Any] = [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 16_000,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: 24_000
            ]
            let recorder = try AVAudioRecorder(url: url, settings: settings)
            guard recorder.record(forDuration: TimeInterval(GattMeshProtocol.meshVoiceMaxDurationMillis) / 1_000) else {
                return false
            }
            self.recorder = recorder
            self.fileURL = url
            self.startedAt = Date()
            isRecording = true
            return true
        } catch {
            return false
        }
    }

    /// Stops recording and returns the M4A bytes + duration, or nil when too short/failed.
    func stopAndCollect() -> (data: Data, durationMillis: Int64)? {
        guard let recorder else { return nil }
        let durationMillis = Int64((Date().timeIntervalSince(startedAt ?? Date())) * 1_000)
        recorder.stop()
        self.recorder = nil
        isRecording = false
        defer {
            fileURL.map { try? FileManager.default.removeItem(at: $0) }
            fileURL = nil
            startedAt = nil
        }
        guard
            durationMillis >= Self.minDurationMillis,
            let url = fileURL,
            let data = try? Data(contentsOf: url),
            !data.isEmpty,
            data.count <= GattMeshProtocol.meshImageMaxPlainBytes
        else {
            return nil
        }
        return (data, min(durationMillis, GattMeshProtocol.meshVoiceMaxDurationMillis))
    }

    func cancel() {
        recorder?.stop()
        recorder = nil
        isRecording = false
        fileURL.map { try? FileManager.default.removeItem(at: $0) }
        fileURL = nil
        startedAt = nil
    }
}
