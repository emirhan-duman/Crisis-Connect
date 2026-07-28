import AVFoundation
import Foundation

/// Half-duplex telsiz audio engine (iOS port of Android `PttAudioEngine`).
///
/// Reuses the proven `ChatPeerVoiceCall` audio path — `AVAudioEngine` + `AVAudioConverter` with
/// Apple's built-in Opus codec (`kAudioFormatOpus`), no external library — but as a walkie-talkie:
/// you either capture (mic → Opus frames out) or play (incoming Opus → speaker), never both. The
/// `.voiceChat` session mode supplies hardware echo cancellation; Bluetooth/wired routing is handled
/// automatically by the session. 16 kHz mono / 20 ms frames matches the Android telsiz so the Opus
/// stream is cross-platform.
final class PttAudioEngine {

    // 20 ms @ 16 kHz = 320 samples/frame. Mirrors Android VoipConfig used by the telsiz.
    private static let sampleRate = 16_000.0
    private static let channelCount: AVAudioChannelCount = 1
    private static let framesPerPacket: AVAudioFrameCount = 320
    private static let tapBufferSize: AVAudioFrameCount = 1_024
    private static let maxPacketSize = 256

    private let processingQueue = DispatchQueue(label: "telsiz.ptt.audio", qos: .userInitiated)
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let pcmFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: PttAudioEngine.sampleRate,
        channels: PttAudioEngine.channelCount,
        interleaved: false
    )!
    private let opusFormat = AVAudioFormat(settings: [
        AVFormatIDKey: kAudioFormatOpus,
        AVSampleRateKey: PttAudioEngine.sampleRate,
        AVNumberOfChannelsKey: PttAudioEngine.channelCount
    ])!

    private var inputConverter: AVAudioConverter?
    private var encoder: AVAudioConverter?
    private var decoder: AVAudioConverter?
    private var pendingCaptureSamples: [Float] = []
    private var onFrame: ((Data) -> Void)?
    private var hasInstalledTap = false
    private var engineConfigured = false
    private var sessionActive = false
    private var decodeCount = 0

    // MARK: - Public API (mirrors Android PttAudioEngine)

    /// Bring up the audio session + engine ahead of capture/playback. Returns false if the hardware
    /// is unavailable (mirrors Android `PttAudioEngine.prepare()`).
    func prepare() -> Bool {
        do {
            try ensureSessionAndEngine()
            return true
        } catch {
            return false
        }
    }

    /// Begin capturing the mic; each ~20 ms Opus frame is delivered to `onFrame` on an internal queue.
    func startCapture(onFrame: @escaping (Data) -> Void) -> Bool {
        do {
            try ensureSessionAndEngine()
        } catch {
            return false
        }
        self.onFrame = onFrame
        installTapIfNeeded()
        return true
    }

    func stopCapture() {
        if hasInstalledTap {
            engine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }
        onFrame = nil
        processingQueue.async { self.pendingCaptureSamples.removeAll(keepingCapacity: false) }
    }

    /// Prepare playback for an incoming speaker.
    func startPlayback() -> Bool {
        do {
            try ensureSessionAndEngine()
        } catch {
            NSLog("Telsiz audio: startPlayback ensureSession FAILED: %@", String(describing: error))
            return false
        }
        // Re-assert the loudspeaker each time a remote starts talking: VPIO (`.voiceChat`) frequently
        // flips the route back to the earpiece right after the engine (re)starts, so a one-time
        // override at session setup isn't enough.
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(.speaker)
        if !playerNode.isPlaying {
            playerNode.play()
        }
        NSLog("Telsiz audio: startPlayback ok engineRunning=%d playerPlaying=%d", engine.isRunning ? 1 : 0, playerNode.isPlaying ? 1 : 0)
        return true
    }

    /// Decode an incoming Opus frame and schedule it for playback. `seq` is accepted for API parity
    /// with Android; the player node's queue provides buffering (reordering is a later refinement).
    func submitFrame(seq: Int, opus: Data) {
        processingQueue.async { self.decodeAndSchedule(opus) }
    }

    func stopPlayback() {
        playerNode.stop()
    }

    /// Tear everything down and release the audio session.
    func release() {
        stopCapture()
        playerNode.stop()
        engine.stop()
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(.none)
        try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        sessionActive = false
        engineConfigured = false
    }

    // MARK: - Setup

    private func ensureSessionAndEngine() throws {
        if !sessionActive {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .voiceChat,
                options: [.allowBluetoothHFP, .allowBluetoothA2DP, .defaultToSpeaker]
            )
            try session.setPreferredSampleRate(Self.sampleRate)
            try session.setPreferredIOBufferDuration(0.02)
            try session.setActive(true, options: [])
            // Force the loudspeaker. `.voiceChat` (VPIO) otherwise routes playback to the
            // earpiece, so a hand-held telsiz sounds silent even though frames decode fine —
            // this was the "ses gelmiyor" bug. Mirrors the proven ChatPeerVoiceCall path
            // (`applyOutputRoute` → `overrideOutputAudioPort(.speaker)`).
            try? session.overrideOutputAudioPort(.speaker)
            sessionActive = true
        }

        let inputFormat = engine.inputNode.inputFormat(forBus: 0)
        inputConverter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        if encoder == nil {
            let enc = AVAudioConverter(from: pcmFormat, to: opusFormat)
            // Match the Android telsiz bitrate (18 kbps). Apple's Opus otherwise defaults much higher,
            // so a 2-frame bundle + JSON/base64 wrapper can exceed one BLE ATT write and get dropped by
            // the fast path's `count <= maxLen` guard — i.e. silent audio while control packets still fit.
            enc?.bitRate = 18_000
            encoder = enc
        }
        if decoder == nil { decoder = AVAudioConverter(from: opusFormat, to: pcmFormat) }

        if !engineConfigured {
            engine.attach(playerNode)
            engine.connect(playerNode, to: engine.mainMixerNode, format: pcmFormat)
            engineConfigured = true
        }
        if !engine.isRunning {
            engine.prepare()
            try engine.start()
        }
    }

    private func installTapIfNeeded() {
        guard !hasInstalledTap else { return }
        let inputFormat = engine.inputNode.inputFormat(forBus: 0)
        engine.inputNode.installTap(onBus: 0, bufferSize: Self.tapBufferSize, format: inputFormat) { [weak self] buffer, _ in
            guard let self, let copied = Self.copyBuffer(buffer) else { return }
            self.processingQueue.async { self.processCapturedBuffer(copied) }
        }
        hasInstalledTap = true
    }

    // MARK: - Capture → Opus

    private func processCapturedBuffer(_ buffer: AVAudioPCMBuffer) {
        for converted in convertInputBuffer(buffer) {
            appendSamples(from: converted)
        }
        emitReadyFramesIfNeeded()
    }

    private func convertInputBuffer(_ buffer: AVAudioPCMBuffer) -> [AVAudioPCMBuffer] {
        guard let inputConverter else { return [] }
        var sourceConsumed = false
        var outputs: [AVAudioPCMBuffer] = []
        while true {
            guard let outputBuffer = AVAudioPCMBuffer(
                pcmFormat: pcmFormat,
                frameCapacity: Self.framesPerPacket * 4
            ) else {
                return outputs
            }
            var conversionError: NSError?
            let status = inputConverter.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
                if sourceConsumed {
                    outStatus.pointee = .noDataNow
                    return nil
                }
                sourceConsumed = true
                outStatus.pointee = .haveData
                return buffer
            }
            if conversionError != nil { return outputs }
            switch status {
            case .haveData:
                if outputBuffer.frameLength > 0 { outputs.append(outputBuffer) }
            case .inputRanDry, .endOfStream:
                if outputBuffer.frameLength > 0 { outputs.append(outputBuffer) }
                return outputs
            case .error:
                return outputs
            @unknown default:
                return outputs
            }
        }
    }

    private func appendSamples(from buffer: AVAudioPCMBuffer) {
        guard let samples = buffer.floatChannelData?[0] else { return }
        let frameCount = Int(buffer.frameLength)
        pendingCaptureSamples.append(contentsOf: UnsafeBufferPointer(start: samples, count: frameCount))
    }

    private func emitReadyFramesIfNeeded() {
        let frameCount = Int(Self.framesPerPacket)
        while pendingCaptureSamples.count >= frameCount {
            let frameSamples = Array(pendingCaptureSamples.prefix(frameCount))
            pendingCaptureSamples.removeFirst(frameCount)
            guard let packet = encodeFrame(frameSamples) else { continue }
            onFrame?(packet)
        }
    }

    private func encodeFrame(_ samples: [Float]) -> Data? {
        guard let encoder,
              let pcmBuffer = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: Self.framesPerPacket),
              let destination = pcmBuffer.floatChannelData?[0] else {
            return nil
        }
        pcmBuffer.frameLength = Self.framesPerPacket
        destination.update(from: samples, count: samples.count)

        let outputBuffer = AVAudioCompressedBuffer(
            format: opusFormat,
            packetCapacity: 1,
            maximumPacketSize: Self.maxPacketSize
        )
        var sourceProvided = false
        var conversionError: NSError?
        let status = encoder.convert(to: outputBuffer, error: &conversionError) { _, outStatus in
            if sourceProvided {
                outStatus.pointee = .noDataNow
                return nil
            }
            sourceProvided = true
            outStatus.pointee = .haveData
            return pcmBuffer
        }
        if conversionError != nil { return nil }
        guard status == .haveData, outputBuffer.byteLength > 0 else { return nil }
        return Data(bytes: outputBuffer.data, count: Int(outputBuffer.byteLength))
    }

    // MARK: - Opus → playback

    private func decodeAndSchedule(_ payload: Data) {
        guard !payload.isEmpty else { return }
        guard let decoder else {
            NSLog("Telsiz audio: decode skipped — decoder nil (ensureSession/startPlayback not run)")
            return
        }
        let compressedBuffer = AVAudioCompressedBuffer(
            format: opusFormat,
            packetCapacity: 1,
            maximumPacketSize: max(1, payload.count)
        )
        payload.copyBytes(
            to: compressedBuffer.data.assumingMemoryBound(to: UInt8.self),
            count: payload.count
        )
        compressedBuffer.byteLength = UInt32(payload.count)
        compressedBuffer.packetCount = 1
        compressedBuffer.packetDescriptions?[0] = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: 0,
            mDataByteSize: UInt32(payload.count)
        )

        guard let decodedBuffer = AVAudioPCMBuffer(
            pcmFormat: pcmFormat,
            frameCapacity: Self.framesPerPacket * 4
        ) else {
            return
        }
        var sourceProvided = false
        var conversionError: NSError?
        let status = decoder.convert(to: decodedBuffer, error: &conversionError) { _, outStatus in
            if sourceProvided {
                outStatus.pointee = .noDataNow
                return nil
            }
            sourceProvided = true
            outStatus.pointee = .haveData
            return compressedBuffer
        }
        if let conversionError {
            NSLog("Telsiz audio: decode error %@", conversionError.localizedDescription)
            return
        }
        // Decoding a single Opus packet returns `.inputRanDry` (input exhausted) AFTER it has already
        // emitted the PCM — that is success, not failure. The old `status == .haveData` guard rejected
        // every decoded frame (status is always `.inputRanDry` here) and discarded valid audio, which
        // was the real "iOS telsiz: no sound" bug. Accept any non-error status that produced samples,
        // mirroring the capture path's `.inputRanDry/.endOfStream` handling.
        if status == .error {
            NSLog("Telsiz audio: decode status=.error payload=%d", payload.count)
            return
        }
        guard decodedBuffer.frameLength > 0 else { return }
        playerNode.scheduleBuffer(decodedBuffer, completionHandler: nil)
        if !playerNode.isPlaying {
            playerNode.play()
        }
        decodeCount += 1
        if decodeCount % 50 == 1 {
            NSLog("Telsiz audio: playing frame #%d (%u samples)", decodeCount, decodedBuffer.frameLength)
        }
    }

    // MARK: - Helpers

    private static func copyBuffer(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer? {
        guard let copy = AVAudioPCMBuffer(pcmFormat: buffer.format, frameCapacity: buffer.frameLength) else {
            return nil
        }
        copy.frameLength = buffer.frameLength
        let channelCount = Int(buffer.format.channelCount)
        if let source = buffer.floatChannelData, let destination = copy.floatChannelData {
            for channel in 0..<channelCount {
                destination[channel].update(from: source[channel], count: Int(buffer.frameLength))
            }
        }
        return copy
    }
}
