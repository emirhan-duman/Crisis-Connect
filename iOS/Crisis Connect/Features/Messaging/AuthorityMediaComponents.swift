//
//  AuthorityMediaComponents.swift
//  Crisis Connect
//
//  Media pieces for authority (hierarchy) threads: the voice-note recorder, the encrypted
//  attachment bubble (image / audio / file) and image preparation. Recording is AAC/M4A
//  ("audio/mp4") — the one format every member of the fleet can play (web <audio>, Android
//  MediaPlayer, iOS AVAudioPlayer). Ogg/Opus notes from older Android builds can't be decoded
//  on iOS (no Ogg demuxer in the OS); those render as an "unsupported format" row.
//

import AVFoundation
import Combine
import CryptoKit
import SwiftUI
import UIKit

// MARK: - Image preparation (matches web/Android: ≤2048px, JPEG q0.85)

enum AuthorityMediaPrep {
    private static let maxDimension: CGFloat = 2048
    private static let jpegQuality: CGFloat = 0.85

    static func imageAttachment(from data: Data) -> PendingChannelAttachment? {
        guard let image = UIImage(data: data)?.normalizedOrientationImage() else { return nil }
        let size = image.size
        let largest = max(size.width, size.height)
        let scaled: UIImage
        if largest > maxDimension, largest > 0 {
            let factor = maxDimension / largest
            let target = CGSize(width: size.width * factor, height: size.height * factor)
            let renderer = UIGraphicsImageRenderer(size: target)
            scaled = renderer.image { _ in image.draw(in: CGRect(origin: .zero, size: target)) }
        } else {
            scaled = image
        }
        guard let jpeg = scaled.jpegData(compressionQuality: jpegQuality) else { return nil }
        return PendingChannelAttachment(
            data: jpeg,
            name: "image_\(Int(Date().timeIntervalSince1970)).jpg",
            mime: "image/jpeg",
            width: Int(scaled.size.width.rounded()),
            height: Int(scaled.size.height.rounded())
        )
    }
}

// MARK: - Voice recorder (AAC/M4A)

@MainActor
final class AuthorityVoiceRecorderModel: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var elapsedSeconds = 0

    private var recorder: AVAudioRecorder?
    private var fileURL: URL?
    private var timer: Timer?

    func start() {
        guard !isRecording else { return }
        Task { @MainActor [weak self] in
            guard let self else { return }
            let granted = await Self.requestPermission()
            guard granted else { return }
            self.begin()
        }
    }

    /// Stops and returns the finished voice note, or nil when nothing usable was captured.
    func stop() -> PendingChannelAttachment? {
        guard let recorder, let fileURL else { return nil }
        recorder.stop()
        cleanupTimer()
        isRecording = false
        self.recorder = nil
        let duration = max(1, elapsedSeconds)
        elapsedSeconds = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        guard let data = try? Data(contentsOf: fileURL), !data.isEmpty else {
            try? FileManager.default.removeItem(at: fileURL)
            return nil
        }
        try? FileManager.default.removeItem(at: fileURL)
        self.fileURL = nil
        return PendingChannelAttachment(
            data: data,
            name: "voice_\(Int(Date().timeIntervalSince1970)).m4a",
            mime: "audio/mp4",
            durationSec: duration
        )
    }

    func cancel() {
        recorder?.stop()
        cleanupTimer()
        isRecording = false
        recorder = nil
        elapsedSeconds = 0
        if let fileURL { try? FileManager.default.removeItem(at: fileURL) }
        fileURL = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func begin() {
        let session = AVAudioSession.sharedInstance()
        guard (try? session.setCategory(.playAndRecord, mode: .default, options: [.allowBluetooth])) != nil,
              (try? session.setActive(true)) != nil else {
            return
        }
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("authority_voice_\(Int(Date().timeIntervalSince1970)).m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderBitRateKey: 32_000
        ]
        guard let recorder = try? AVAudioRecorder(url: url, settings: settings),
              recorder.record() else {
            return
        }
        self.recorder = recorder
        fileURL = url
        elapsedSeconds = 0
        isRecording = true
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.isRecording else { return }
                self.elapsedSeconds += 1
            }
        }
    }

    private func cleanupTimer() {
        timer?.invalidate()
        timer = nil
    }

    private nonisolated static func requestPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }
    }
}

// MARK: - Audio playback

@MainActor
final class ChannelAudioPlayerModel: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var isPlaying = false

    private var player: AVAudioPlayer?

    func toggle(data: Data) {
        if isPlaying {
            player?.stop()
            player = nil
            isPlaying = false
            return
        }
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        guard let player = try? AVAudioPlayer(data: data) else { return }
        player.delegate = self
        self.player = player
        isPlaying = player.play()
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor [weak self] in
            self?.isPlaying = false
            self?.player = nil
        }
    }
}

// MARK: - Attachment bubble

/// Renders one encrypted channel attachment: downloads + decrypts (disk-cached) and shows an
/// image thumbnail, an audio play row, or a generic file row. iOS can't demux Ogg — those audio
/// notes get an explicit "unsupported" row instead of a broken player.
struct ChannelAttachmentBubbleView: View {
    let attachment: ChannelAttachment
    let key: SymmetricKey?
    let aad: String?

    @State private var data: Data?
    @State private var failed = false
    @State private var showsFullImage = false
    @StateObject private var audioPlayer = ChannelAudioPlayerModel()

    private var isPlayableAudio: Bool {
        attachment.isAudio && !attachment.mime.contains("ogg") && !attachment.mime.contains("webm")
    }

    var body: some View {
        content
            .task(id: attachment.path) {
                guard data == nil else { return }
                let fetched = await ChannelAttachments.fetchAttachmentBytes(
                    key: key, aad: aad, attachment: attachment
                )
                data = fetched
                failed = fetched == nil
            }
    }

    @ViewBuilder
    private var content: some View {
        if attachment.isImage {
            imageContent
        } else if attachment.isAudio {
            audioContent
        } else {
            fileRow(icon: "doc.fill", title: attachment.name, subtitle: byteCountLabel)
        }
    }

    @ViewBuilder
    private var imageContent: some View {
        if let data, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: 230, maxHeight: 230)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onTapGesture { showsFullImage = true }
                .fullScreenCover(isPresented: $showsFullImage) {
                    ZStack(alignment: .topTrailing) {
                        Color.black.ignoresSafeArea()
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        Button {
                            showsFullImage = false
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 30))
                                .foregroundStyle(.white.opacity(0.85))
                                .padding(16)
                        }
                    }
                }
        } else if failed {
            fileRow(icon: "photo", title: attachment.name, subtitle: NSLocalizedString("AUTHORITY_MEDIA_UNAVAILABLE", comment: ""))
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceElevated)
                .frame(width: 200, height: 140)
                .overlay(ProgressView())
        }
    }

    @ViewBuilder
    private var audioContent: some View {
        if !isPlayableAudio {
            fileRow(
                icon: "waveform.slash",
                title: NSLocalizedString("AUTHORITY_MEDIA_VOICE_NOTE", comment: ""),
                subtitle: NSLocalizedString("AUTHORITY_MEDIA_AUDIO_UNSUPPORTED", comment: "")
            )
        } else if let data {
            HStack(spacing: 10) {
                Button {
                    audioPlayer.toggle(data: data)
                } label: {
                    Image(systemName: audioPlayer.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(Color.appPrimary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Image(systemName: "waveform")
                        .font(.body)
                        .foregroundStyle(Color.appTextSecondary)
                    Text(durationLabel)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .padding(.vertical, 2)
        } else if failed {
            fileRow(
                icon: "waveform",
                title: NSLocalizedString("AUTHORITY_MEDIA_VOICE_NOTE", comment: ""),
                subtitle: NSLocalizedString("AUTHORITY_MEDIA_UNAVAILABLE", comment: "")
            )
        } else {
            HStack(spacing: 8) {
                ProgressView()
                Text("AUTHORITY_MEDIA_VOICE_NOTE")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
    }

    private func fileRow(icon: String, title: String, subtitle: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.system(size: 22))
                .foregroundStyle(Color.appPrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .padding(.vertical, 2)
    }

    private var durationLabel: String {
        let seconds = attachment.durationSec ?? 0
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private var byteCountLabel: String {
        ByteCountFormatter.string(fromByteCount: Int64(attachment.size), countStyle: .file)
    }
}

// MARK: - Bluetooth bridge media

/// Bluetooth-carried media of the hidden bridge contact, rendered from its LOCAL file — no cloud
/// fetch, no channel key needed. Mirrors ChannelAttachmentBubbleView's look so offline media is
/// indistinguishable from cloud attachments in the thread.
struct BridgeMediaBubbleView: View {
    let row: SOSChatMessage

    @State private var data: Data?
    @State private var failed = false
    @State private var showsFullImage = false
    @StateObject private var audioPlayer = ChannelAudioPlayerModel()

    var body: some View {
        content
            .task(id: row.id) {
                guard data == nil else { return }
                let loaded: Data?
                switch row.kind {
                case .audio:
                    loaded = row.audioRelativePath.flatMap { SOSChatStore.loadVoiceData(fileName: $0) }
                case .image:
                    loaded = row.imageRelativePath.flatMap { SOSChatStore.loadImageData(fileName: $0) }
                default:
                    loaded = nil
                }
                data = loaded
                failed = loaded == nil
            }
    }

    @ViewBuilder
    private var content: some View {
        if row.kind == .image {
            imageContent
        } else {
            audioContent
        }
    }

    @ViewBuilder
    private var imageContent: some View {
        if let data, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: 230, maxHeight: 230)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                .onTapGesture { showsFullImage = true }
                .fullScreenCover(isPresented: $showsFullImage) {
                    ZStack(alignment: .topTrailing) {
                        Color.black.ignoresSafeArea()
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: .infinity)
                        Button {
                            showsFullImage = false
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 30))
                                .foregroundStyle(.white.opacity(0.85))
                                .padding(16)
                        }
                    }
                }
        } else if failed {
            Image(systemName: "photo")
                .font(.title2)
                .foregroundStyle(Color.appTextSecondary)
                .frame(width: 200, height: 140)
                .background(
                    RoundedRectangle(cornerRadius: 12, style: .continuous)
                        .fill(Color.appSurfaceElevated)
                )
        } else {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceElevated)
                .frame(width: 200, height: 140)
                .overlay(ProgressView())
        }
    }

    @ViewBuilder
    private var audioContent: some View {
        if let data {
            HStack(spacing: 10) {
                Button {
                    audioPlayer.toggle(data: data)
                } label: {
                    Image(systemName: audioPlayer.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 34))
                        .foregroundStyle(Color.appPrimary)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Image(systemName: "waveform")
                        .font(.body)
                        .foregroundStyle(Color.appTextSecondary)
                    Text(durationLabel)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .padding(.vertical, 2)
        } else if failed {
            HStack(spacing: 8) {
                Image(systemName: "waveform")
                Text("AUTHORITY_MEDIA_UNAVAILABLE")
                    .font(.caption)
            }
            .foregroundStyle(Color.appTextSecondary)
        } else {
            HStack(spacing: 8) {
                ProgressView()
                Text("AUTHORITY_MEDIA_VOICE_NOTE")
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
    }

    private var durationLabel: String {
        let totalSeconds = max(0, (row.audioDurationMillis ?? 0) / 1000)
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
