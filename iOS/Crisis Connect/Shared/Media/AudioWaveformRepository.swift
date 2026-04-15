//
//  AudioWaveformRepository.swift
//  Crisis Connect
//
//  Created by Assistant on 12.03.2026.
//

import AVFoundation
import CoreGraphics
import Foundation

actor AudioWaveformRepository {
    static let shared = AudioWaveformRepository()

    private var cache: [String: [CGFloat]] = [:]

    func cachedWaveform(for fileName: String, barCount: Int) -> [CGFloat]? {
        let key = cacheKey(for: fileName, barCount: barCount)
        return cache[key]
    }

    func waveform(for fileName: String, barCount: Int) async -> [CGFloat] {
        let normalizedCount = max(20, barCount)
        let key = cacheKey(for: fileName, barCount: normalizedCount)
        if let cached = cache[key] {
            return cached
        }

        let extracted = Self.extractWaveform(for: fileName, barCount: normalizedCount)
        cache[key] = extracted
        return extracted
    }

    private func cacheKey(for fileName: String, barCount: Int) -> String {
        "\(fileName)#\(max(20, barCount))"
    }

    nonisolated static func placeholder(barCount: Int) -> [CGFloat] {
        let normalizedCount = max(20, barCount)
        let midpoint = CGFloat(normalizedCount - 1) / 2

        return (0..<normalizedCount).map { index in
            let distance = midpoint == 0 ? 0 : abs(CGFloat(index) - midpoint) / midpoint
            let envelope = 1 - (distance * 0.78)
            let ripple = (sin(CGFloat(index) * 0.82) + 1) * 0.08
            return max(0.14, min(0.72, envelope * 0.42 + ripple))
        }
    }

    private nonisolated static func extractWaveform(for fileName: String, barCount: Int) -> [CGFloat] {
        guard let data = SOSChatStore.loadVoiceData(fileName: fileName), !data.isEmpty else {
            return placeholder(barCount: barCount)
        }

        let pathExtension = URL(fileURLWithPath: fileName).pathExtension
        let fileExtension = pathExtension.isEmpty ? "m4a" : pathExtension
        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-waveform-\(UUID().uuidString.lowercased())")
            .appendingPathExtension(fileExtension)
        do {
            try data.write(to: tempURL, options: [.atomic])
            defer {
                try? FileManager.default.removeItem(at: tempURL)
            }
            return extractWaveform(from: tempURL, barCount: barCount)
        } catch {
            return placeholder(barCount: barCount)
        }
    }

    private nonisolated static func extractWaveform(from url: URL, barCount: Int) -> [CGFloat] {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return placeholder(barCount: barCount)
        }

        do {
            let file = try AVAudioFile(forReading: url)
            let totalFrames = Int(file.length)
            guard totalFrames > 0 else {
                return placeholder(barCount: barCount)
            }

            let format = file.processingFormat
            let chunkFrameCount: AVAudioFrameCount = 4096
            guard let buffer = AVAudioPCMBuffer(
                pcmFormat: format,
                frameCapacity: chunkFrameCount
            ) else {
                return placeholder(barCount: barCount)
            }

            var bucketEnergy = Array(repeating: CGFloat.zero, count: barCount)
            var bucketCounts = Array(repeating: 0, count: barCount)
            var processedFrames = 0

            while processedFrames < totalFrames {
                let remainingFrames = totalFrames - processedFrames
                let framesToRead = min(Int(chunkFrameCount), remainingFrames)
                try file.read(into: buffer, frameCount: AVAudioFrameCount(framesToRead))

                let framesRead = Int(buffer.frameLength)
                if framesRead == 0 {
                    break
                }

                let amplitudes = frameAmplitudes(from: buffer, framesRead: framesRead)
                for frameIndex in 0..<framesRead {
                    let globalFrame = processedFrames + frameIndex
                    let bucketIndex = min(
                        barCount - 1,
                        (globalFrame * barCount) / max(totalFrames, 1)
                    )
                    let amplitude = amplitudes[frameIndex]
                    bucketEnergy[bucketIndex] += amplitude * amplitude
                    bucketCounts[bucketIndex] += 1
                }

                processedFrames += framesRead
            }

            let rawBuckets = zip(bucketEnergy, bucketCounts).map { energy, count in
                guard count > 0 else { return CGFloat.zero }
                return sqrt(energy / CGFloat(count))
            }

            return normalize(rawBuckets, fallbackBarCount: barCount)
        } catch {
            return placeholder(barCount: barCount)
        }
    }

    private nonisolated static func frameAmplitudes(
        from buffer: AVAudioPCMBuffer,
        framesRead: Int
    ) -> [CGFloat] {
        let channelCount = max(Int(buffer.format.channelCount), 1)

        if let channels = buffer.floatChannelData {
            return (0..<framesRead).map { frameIndex in
                var total = Float.zero
                for channelIndex in 0..<channelCount {
                    total += abs(channels[channelIndex][frameIndex])
                }
                return CGFloat(total / Float(channelCount))
            }
        }

        if let channels = buffer.int16ChannelData {
            let divisor = CGFloat(Int16.max)
            return (0..<framesRead).map { frameIndex in
                var total = CGFloat.zero
                for channelIndex in 0..<channelCount {
                    total += CGFloat(abs(Int(channels[channelIndex][frameIndex]))) / divisor
                }
                return total / CGFloat(channelCount)
            }
        }

        if let channels = buffer.int32ChannelData {
            let divisor = CGFloat(Int32.max)
            return (0..<framesRead).map { frameIndex in
                var total = CGFloat.zero
                for channelIndex in 0..<channelCount {
                    total += CGFloat(abs(Int64(channels[channelIndex][frameIndex]))) / divisor
                }
                return total / CGFloat(channelCount)
            }
        }

        return Array(repeating: .zero, count: framesRead)
    }

    private nonisolated static func normalize(
        _ samples: [CGFloat],
        fallbackBarCount: Int
    ) -> [CGFloat] {
        let peak = samples.max() ?? 0
        guard peak > 0.0001 else {
            return placeholder(barCount: fallbackBarCount)
        }

        let normalized = samples.map { pow($0 / peak, 0.58) }
        let smoothed = normalized.indices.map { index -> CGFloat in
            let previous = normalized[max(0, index - 1)]
            let current = normalized[index]
            let next = normalized[min(normalized.count - 1, index + 1)]
            return (previous * 0.22) + (current * 0.56) + (next * 0.22)
        }

        return smoothed.map { sample in
            min(max(sample, 0.06), 1)
        }
    }
}
