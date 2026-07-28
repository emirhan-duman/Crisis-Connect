//
//  OggOpusTranscoder.swift
//  Crisis Connect
//
//  Android records internet voice clips as Ogg/Opus (MediaRecorder OutputFormat.OGG +
//  AudioEncoder.OPUS, 48 kHz) and ships them as kind-1 audio attachments. iOS stores the bytes
//  fine, but AVAudioPlayer / AVAudioFile cannot open an Ogg container, so the clip was silently
//  unplayable. Apple's Core Audio DOES ship an Opus packet decoder (`kAudioFormatOpus`, the same
//  one the GATT call engine uses) — only the Ogg framing is unsupported. So: parse the Ogg pages
//  ourselves, reassemble the Opus packets, decode them with AVAudioConverter, and write a normal
//  m4a (AAC) file that every player in the app can open.
//
//  Defensive by design: malformed input never crashes — every parse failure logs to
//  MessagingDiagLog and falls back to the original bytes/URL (the pre-existing behavior).
//

import AVFoundation
import Foundation

enum OggOpusTranscoder {

    /// Opus always decodes at 48 kHz regardless of the OpusHead "input sample rate" field
    /// (RFC 7845 §5.1: that field only records the original capture rate).
    private static let opusDecodeSampleRate = 48_000
    /// Longest legal Opus packet is 120 ms → 5760 frames at 48 kHz.
    private static let maxFramesPerOpusPacket: AVAudioFrameCount = 5_760

    // MARK: - Public API

    /// Returns a URL that AVAudioPlayer/AVAudioFile can open.
    ///
    /// - The original `url` when the file is NOT an Ogg container (magic != "OggS") or when the
    ///   transcode fails for any reason (malformed input included — never throws, never crashes).
    /// - Otherwise a sibling `<name>.<ext>.m4a` next to the original, transcoded on the first
    ///   call and reused (plain file-exists cache) on later ones.
    static func transcodeIfNeeded(at url: URL) -> URL {
        guard fileStartsWithOggMagic(url) else { return url }
        let sibling = url.appendingPathExtension("m4a")
        if FileManager.default.fileExists(atPath: sibling.path) { return sibling }
        guard let oggData = try? Data(contentsOf: url) else { return url }
        guard let temp = transcodeToTemporaryM4a(oggData: oggData, context: url.lastPathComponent) else {
            return url
        }
        do {
            // A concurrent call may have won the race; its output is equivalent.
            if FileManager.default.fileExists(atPath: sibling.path) {
                try? FileManager.default.removeItem(at: temp)
            } else {
                try FileManager.default.moveItem(at: temp, to: sibling)
            }
            return sibling
        } catch {
            MessagingDiagLog.log("ogg-transcode move failed \(url.lastPathComponent): \(error.localizedDescription)")
            try? FileManager.default.removeItem(at: temp)
            return url
        }
    }

    /// Data-based entry point for the encrypted voice store (files on disk are
    /// LocalEncryptedFileStore envelopes, so the on-disk URL never shows the "OggS" magic —
    /// only the decrypted bytes do).
    ///
    /// Returns `data` unchanged when it is not an Ogg container or on any transcode failure;
    /// otherwise returns m4a (AAC) bytes. The transcoded copy is cached — encrypted, like every
    /// other voice file — as a `<cacheKey>.m4a` sibling inside the voice-message store, so the
    /// Ogg parse + decode runs once per clip, not once per play.
    static func playableAudioData(for data: Data, cacheKey: String) -> Data {
        guard startsWithOggMagic(data) else { return data }

        let normalizedKey = cacheKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let cacheURL: URL? = normalizedKey.isEmpty
            ? nil
            : SOSChatStore.voiceMessageFileURL(fileName: normalizedKey + ".m4a")

        if let cacheURL,
           FileManager.default.fileExists(atPath: cacheURL.path),
           let cached = try? LocalEncryptedFileStore.read(from: cacheURL),
           !cached.data.isEmpty {
            return cached.data
        }

        guard let temp = transcodeToTemporaryM4a(oggData: data, context: normalizedKey) else {
            return data
        }
        defer { try? FileManager.default.removeItem(at: temp) }
        guard let m4aData = try? Data(contentsOf: temp), !m4aData.isEmpty else {
            MessagingDiagLog.log("ogg-transcode empty output key=\(normalizedKey)")
            return data
        }
        if let cacheURL {
            // Best effort — a failed cache write only costs a re-transcode next play.
            try? LocalEncryptedFileStore.write(m4aData, to: cacheURL)
        }
        MessagingDiagLog.log("ogg-transcode ok key=\(normalizedKey) in=\(data.count)B out=\(m4aData.count)B")
        return m4aData
    }

    // MARK: - Magic checks

    private static let oggCapturePattern: [UInt8] = [0x4F, 0x67, 0x67, 0x53] // "OggS"

    private static func startsWithOggMagic(_ data: Data) -> Bool {
        guard data.count >= 4 else { return false }
        return data.prefix(4).elementsEqual(oggCapturePattern)
    }

    private static func fileStartsWithOggMagic(_ url: URL) -> Bool {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return false }
        defer { try? handle.close() }
        guard let head = try? handle.read(upToCount: 4) else { return false }
        return startsWithOggMagic(head)
    }

    // MARK: - Core transcode (Ogg parse → Opus decode → AAC m4a)

    /// Full pipeline into a temporary .m4a in the caller's temp directory. Returns nil (after
    /// logging) on any failure; the caller owns moving/removing the returned file.
    private static func transcodeToTemporaryM4a(oggData: Data, context: String) -> URL? {
        guard let stream = parseOggOpusStream(oggData) else {
            MessagingDiagLog.log("ogg-transcode parse failed key=\(context) size=\(oggData.count)B")
            return nil
        }
        guard !stream.audioPackets.isEmpty else {
            MessagingDiagLog.log("ogg-transcode no audio packets key=\(context)")
            return nil
        }

        // Decode format: Apple's Opus decoder (same construction as GattCallAudioEngine, but at
        // the container's native 48 kHz instead of the call codec's 16 kHz).
        guard let opusFormat = AVAudioFormat(settings: [
            AVFormatIDKey: kAudioFormatOpus,
            AVSampleRateKey: opusDecodeSampleRate,
            AVNumberOfChannelsKey: stream.channelCount
        ]), let pcmFormat = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(opusDecodeSampleRate),
            channels: AVAudioChannelCount(stream.channelCount),
            interleaved: false
        ), let converter = AVAudioConverter(from: opusFormat, to: pcmFormat) else {
            MessagingDiagLog.log("ogg-transcode decoder unavailable key=\(context) ch=\(stream.channelCount)")
            return nil
        }

        let tempURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("cc-oggopus-\(UUID().uuidString.lowercased())")
            .appendingPathExtension("m4a")

        // Optional-var so the AVAudioFile can be released (finalizing the m4a) before the caller
        // moves or reads the file — there is no public close() before iOS 18.
        var outputFile: AVAudioFile?
        do {
            outputFile = try AVAudioFile(forWriting: tempURL, settings: [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: opusDecodeSampleRate,
                AVNumberOfChannelsKey: stream.channelCount,
                AVEncoderBitRateKey: stream.channelCount > 1 ? 128_000 : 64_000
            ])
        } catch {
            MessagingDiagLog.log("ogg-transcode m4a create failed key=\(context): \(error.localizedDescription)")
            return nil
        }

        // RFC 7845 trimming: drop the encoder's priming samples (pre-skip) from the head, and cap
        // the total at final-granule − pre-skip when the file carries a sane granule position.
        var remainingPreSkip = stream.preSkipFrames
        var framesBudget: UInt64? = stream.finalGranulePosition.flatMap { granule in
            granule > UInt64(stream.preSkipFrames) ? granule - UInt64(stream.preSkipFrames) : nil
        }
        var totalFramesWritten: UInt64 = 0
        var decodeFailures = 0

        for packet in stream.audioPackets {
            guard !packet.isEmpty else { continue }
            if let budget = framesBudget, budget == 0 { break }
            guard let decoded = decodeOpusPacket(packet, converter: converter, opusFormat: opusFormat, pcmFormat: pcmFormat) else {
                decodeFailures += 1
                continue // one bad packet must not kill the clip
            }

            var frameCount = Int(decoded.frameLength)
            var startFrame = 0
            if remainingPreSkip > 0 {
                let drop = min(remainingPreSkip, frameCount)
                startFrame = drop
                frameCount -= drop
                remainingPreSkip -= drop
            }
            if let budget = framesBudget, UInt64(frameCount) > budget {
                frameCount = Int(budget)
            }
            guard frameCount > 0 else { continue }

            let writable: AVAudioPCMBuffer
            if startFrame == 0, frameCount == Int(decoded.frameLength) {
                writable = decoded
            } else if let trimmed = trimmedCopy(of: decoded, from: startFrame, frames: frameCount, format: pcmFormat) {
                writable = trimmed
            } else {
                continue
            }

            do {
                try outputFile?.write(from: writable)
            } catch {
                MessagingDiagLog.log("ogg-transcode m4a write failed key=\(context): \(error.localizedDescription)")
                outputFile = nil
                try? FileManager.default.removeItem(at: tempURL)
                return nil
            }
            totalFramesWritten &+= UInt64(frameCount)
            if framesBudget != nil { framesBudget! -= UInt64(frameCount) }
        }

        outputFile = nil // finalize the container

        guard totalFramesWritten > 0 else {
            MessagingDiagLog.log(
                "ogg-transcode produced no audio key=\(context) packets=\(stream.audioPackets.count) failures=\(decodeFailures)"
            )
            try? FileManager.default.removeItem(at: tempURL)
            return nil
        }
        if decodeFailures > 0 {
            MessagingDiagLog.log("ogg-transcode skipped \(decodeFailures) undecodable packet(s) key=\(context)")
        }
        return tempURL
    }

    /// Single Opus packet → PCM. Copied from GattCallAudioEngine.decodeAndSchedule: an
    /// AVAudioCompressedBuffer holding exactly one packet (with its packet description filled in),
    /// pushed through the converter with a one-shot input block.
    private static func decodeOpusPacket(
        _ packet: [UInt8],
        converter: AVAudioConverter,
        opusFormat: AVAudioFormat,
        pcmFormat: AVAudioFormat
    ) -> AVAudioPCMBuffer? {
        let compressedBuffer = AVAudioCompressedBuffer(
            format: opusFormat,
            packetCapacity: 1,
            maximumPacketSize: max(1, packet.count)
        )
        packet.withUnsafeBufferPointer { source in
            guard let base = source.baseAddress else { return }
            compressedBuffer.data.copyMemory(from: base, byteCount: packet.count)
        }
        compressedBuffer.byteLength = UInt32(packet.count)
        compressedBuffer.packetCount = 1
        compressedBuffer.packetDescriptions?[0] = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: 0,
            mDataByteSize: UInt32(packet.count)
        )

        guard let decodedBuffer = AVAudioPCMBuffer(
            pcmFormat: pcmFormat,
            frameCapacity: maxFramesPerOpusPacket
        ) else {
            return nil
        }
        var sourceProvided = false
        var conversionError: NSError?
        let status = converter.convert(to: decodedBuffer, error: &conversionError) { _, outStatus in
            if sourceProvided {
                outStatus.pointee = .noDataNow
                return nil
            }
            sourceProvided = true
            outStatus.pointee = .haveData
            return compressedBuffer
        }
        if conversionError != nil { return nil }
        // Decoding a single Opus packet returns `.inputRanDry` after emitting the PCM — that is
        // success, not failure (same caveat as GattCallAudioEngine / the telsiz engine).
        if status == .error { return nil }
        guard decodedBuffer.frameLength > 0 else { return nil }
        return decodedBuffer
    }

    private static func trimmedCopy(
        of buffer: AVAudioPCMBuffer,
        from startFrame: Int,
        frames: Int,
        format: AVAudioFormat
    ) -> AVAudioPCMBuffer? {
        guard frames > 0,
              startFrame + frames <= Int(buffer.frameLength),
              let copy = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let source = buffer.floatChannelData,
              let destination = copy.floatChannelData else {
            return nil
        }
        copy.frameLength = AVAudioFrameCount(frames)
        for channel in 0..<Int(format.channelCount) {
            destination[channel].update(from: source[channel] + startFrame, count: frames)
        }
        return copy
    }

    // MARK: - Ogg container parsing (RFC 3533) + Opus mapping (RFC 7845)

    private struct ParsedOpusStream {
        let channelCount: Int
        let preSkipFrames: Int
        /// Granule position of the last page that completed a packet — total 48 kHz samples
        /// including pre-skip. nil when the file never carried one.
        let finalGranulePosition: UInt64?
        let audioPackets: [[UInt8]]
    }

    private static func parseOggOpusStream(_ data: Data) -> ParsedOpusStream? {
        let bytes = [UInt8](data)
        guard bytes.count >= 27 else { return nil }

        func le64(_ index: Int) -> UInt64 {
            var value: UInt64 = 0
            for shift in 0..<8 {
                value |= UInt64(bytes[index + shift]) << (8 * shift)
            }
            return value
        }
        func le32(_ index: Int) -> UInt32 {
            var value: UInt32 = 0
            for shift in 0..<4 {
                value |= UInt32(bytes[index + shift]) << (8 * shift)
            }
            return value
        }
        func matchesASCII(_ index: Int, _ text: String) -> Bool {
            let ascii = [UInt8](text.utf8)
            guard index + ascii.count <= bytes.count else { return false }
            for (position, byte) in ascii.enumerated() where bytes[index + position] != byte {
                return false
            }
            return true
        }
        /// Scan forward for the next "OggS" capture pattern (resync after damage).
        func nextCapturePattern(from start: Int) -> Int? {
            guard start >= 0 else { return nil }
            var index = start
            while index + 4 <= bytes.count {
                if bytes[index] == 0x4F, bytes[index + 1] == 0x67,
                   bytes[index + 2] == 0x67, bytes[index + 3] == 0x53 {
                    return index
                }
                index += 1
            }
            return nil
        }

        var offset = 0
        var adoptedSerial: UInt32?
        var packets: [[UInt8]] = []
        var pendingPacket: [UInt8] = []
        var pendingHasStart = false
        var finalGranule: UInt64?
        var sawEndOfStream = false

        while offset + 27 <= bytes.count, !sawEndOfStream {
            guard matchesASCII(offset, "OggS") else {
                guard let resync = nextCapturePattern(from: offset + 1) else { break }
                offset = resync
                continue
            }
            let version = bytes[offset + 4]
            guard version == 0 else { break } // only Ogg bitstream version 0 exists
            let headerType = bytes[offset + 5]
            let granulePosition = le64(offset + 6)
            let pageSerial = le32(offset + 14)
            // seq (u32le @ +18) and crc (u32le @ +22) are parsed positionally but not enforced —
            // resync + per-packet decode failures already cover damaged input.
            let segmentCount = Int(bytes[offset + 26])
            let lacingStart = offset + 27
            guard lacingStart + segmentCount <= bytes.count else { break }
            var payloadSize = 0
            for segment in 0..<segmentCount {
                payloadSize += Int(bytes[lacingStart + segment])
            }
            let payloadStart = lacingStart + segmentCount
            guard payloadStart + payloadSize <= bytes.count else { break }
            let nextPageOffset = payloadStart + payloadSize

            // Lock onto the Opus logical stream: the page whose payload opens with "OpusHead".
            // (Android's muxer writes a single stream; anything else is skipped.)
            if adoptedSerial == nil {
                guard matchesASCII(payloadStart, "OpusHead") else {
                    offset = nextPageOffset
                    continue
                }
                adoptedSerial = pageSerial
            }
            guard pageSerial == adoptedSerial else {
                offset = nextPageOffset
                continue
            }

            let isContinuation = (headerType & 0x01) != 0
            if !isContinuation {
                // A fresh page while a packet was still open: the fragment is unrecoverable.
                pendingPacket.removeAll(keepingCapacity: true)
                pendingHasStart = true
            }
            // (isContinuation with pendingHasStart == false: we never saw the packet's start —
            // the segments up to the first boundary complete a fragment that gets dropped below.)

            var cursor = payloadStart
            for segment in 0..<segmentCount {
                let segmentLength = Int(bytes[lacingStart + segment])
                if segmentLength > 0 {
                    pendingPacket.append(contentsOf: bytes[cursor..<(cursor + segmentLength)])
                    cursor += segmentLength
                }
                if segmentLength < 255 {
                    // Packet boundary (a 255 lacing value means "continues in the next
                    // segment/page" — when the page ENDS on 255, pendingPacket/pendingHasStart
                    // simply carry over into the next page's continuation).
                    if pendingHasStart, !pendingPacket.isEmpty {
                        packets.append(pendingPacket)
                    }
                    pendingPacket.removeAll(keepingCapacity: true)
                    pendingHasStart = true
                }
            }

            // All-ones granule (-1) means "no packet completes on this page" — don't record it.
            if granulePosition != UInt64.max {
                finalGranule = granulePosition
            }
            if (headerType & 0x04) != 0 {
                sawEndOfStream = true
            }
            offset = nextPageOffset
        }

        // First packet must be OpusHead (RFC 7845 §5.1):
        //   "OpusHead"(8) version(u8 @8) channels(u8 @9) preSkip(u16le @10)
        //   inputSampleRate(u32le @12, informational only) gain(s16le @16) mappingFamily(u8 @18)
        guard let head = packets.first, head.count >= 19 else { return nil }
        guard head[0] == 0x4F, head[1] == 0x70, head[2] == 0x75, head[3] == 0x73,
              head[4] == 0x48, head[5] == 0x65, head[6] == 0x61, head[7] == 0x64 else { // "OpusHead"
            return nil
        }
        let channelCount = Int(head[9])
        // Apple's kAudioFormatOpus decoder handles mono/stereo (mapping family 0). Multichannel
        // mappings never come out of Android's voice recorder; bail cleanly if one shows up.
        guard channelCount == 1 || channelCount == 2 else {
            MessagingDiagLog.log("ogg-transcode unsupported channel count \(channelCount)")
            return nil
        }
        let preSkip = Int(head[10]) | (Int(head[11]) << 8)

        // Second packet is OpusTags — metadata, skipped. Tolerate files that omit it.
        var audioPackets = Array(packets.dropFirst())
        if let tags = audioPackets.first, tags.count >= 8,
           tags[0] == 0x4F, tags[1] == 0x70, tags[2] == 0x75, tags[3] == 0x73,
           tags[4] == 0x54, tags[5] == 0x61, tags[6] == 0x67, tags[7] == 0x73 { // "OpusTags"
            audioPackets.removeFirst()
        }

        return ParsedOpusStream(
            channelCount: channelCount,
            preSkipFrames: max(0, preSkip),
            finalGranulePosition: finalGranule,
            audioPackets: audioPackets
        )
    }
}
