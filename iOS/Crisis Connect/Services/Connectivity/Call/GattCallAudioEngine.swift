import AVFoundation
import Foundation

/// Full-duplex Opus audio engine for voice calls over the P2P GATT link.
///
/// Derived from the telsiz `PttAudioEngine` (16 kHz mono / 20 ms Opus via Apple's built-in
/// `kAudioFormatOpus`, `.voiceChat` session mode for hardware echo cancellation), but capture and
/// playback run at the same time — the mic tap keeps producing frames while the player node
/// schedules incoming audio, exactly like a phone call.
final class GattCallAudioEngine {

    // 20 ms @ 16 kHz = 320 samples/frame. Pinned by the cross-platform wire codec
    // (`P2pCallProtocol`), which matches the Android engine's VoipConfig.
    private static let sampleRate = 16_000.0
    private static let channelCount: AVAudioChannelCount = 1
    private static let framesPerPacket: AVAudioFrameCount = 320
    private static let tapBufferSize: AVAudioFrameCount = 1_024
    private static let maxPacketSize = 256

    private let processingQueue = DispatchQueue(label: "p2p.call.audio", qos: .userInitiated)
    private let engine = AVAudioEngine()
    private let playerNode = AVAudioPlayerNode()
    private let pcmFormat = AVAudioFormat(
        commonFormat: .pcmFormatFloat32,
        sampleRate: GattCallAudioEngine.sampleRate,
        channels: GattCallAudioEngine.channelCount,
        interleaved: false
    )!
    private let opusFormat = AVAudioFormat(settings: [
        AVFormatIDKey: kAudioFormatOpus,
        AVSampleRateKey: GattCallAudioEngine.sampleRate,
        AVNumberOfChannelsKey: GattCallAudioEngine.channelCount
    ])!

    private var inputConverter: AVAudioConverter?
    private var encoder: AVAudioConverter?
    private var decoder: AVAudioConverter?
    private var pendingCaptureSamples: [Float] = []
    private var onFrame: ((Data) -> Void)?
    private var hasInstalledTap = false
    private var engineConfigured = false
    private var sessionActive = false

    /// Opus bitrate; must be set before `prepare()`. NB profile (12 kbps) keeps the two-frame
    /// bundle small enough for a single ATT write in both directions of a full-duplex call.
    var bitrateBps = 12_000

    /// While muted, captured frames are replaced with silence (instead of being dropped) so the
    /// remote jitter pipeline keeps receiving a continuous stream.
    var muted = false

    /// Applies a new Opus encoder bitrate mid-call (receiver-driven adaptation).
    func setBitrate(_ bps: Int) {
        processingQueue.async {
            self.bitrateBps = bps
            self.encoder?.bitRate = bps
        }
    }

    private var emittedFrameCount: Int = 0
    private var captureBufferCount = 0
    private var loggedHardwareBuffer = false

    /// CallKit activates the session in provider(_:didActivate:). Hardware setup waits
    /// for that callback instead of caching the inactive input route.
    var callKitManagesSession = false
    private var callKitSessionActivated = false
    private var voiceProcessingEnabled = false
    private var voiceProcessingConfigured = false
    // Fallback when voice processing can't be enabled: raw AVAudioEngine input has NO telephony
    // AGC (Android records with VOICE_COMMUNICATION, which does) — peers heard a whisper.
    private var captureSoftwareGain: Float = 1.0

    func prepare() -> Bool {
        do {
            try ensureSessionAndEngine(startEngine: false)
            return true
        } catch {
            return false
        }
    }

    func startCapture(onFrame: @escaping (Data) -> Void) -> Bool {
        self.onFrame = onFrame
        do {
            try ensureSessionAndEngine(startEngine: true)
        } catch {
            self.onFrame = nil
            return false
        }
        // CallKit may not have activated the route yet. Keep the capture request so
        // didActivate can install its tap once a real hardware format is available.
        return hasInstalledTap || (callKitManagesSession && !callKitSessionActivated)
    }

    func stopCapture() {
        if hasInstalledTap {
            engine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }
        onFrame = nil
        processingQueue.async { self.pendingCaptureSamples.removeAll(keepingCapacity: false) }
    }

    func startPlayback() -> Bool {
        do {
            try ensureSessionAndEngine(startEngine: false)
        } catch {
            NSLog("GattCallAudio: startPlayback ensureSession FAILED: %@", String(describing: error))
            return false
        }
        // A deferred (CallKit-managed) start leaves the engine stopped until didActivate —
        // playerNode.play() on a stopped engine raises; ensureLive resumes it on activation.
        if engine.isRunning, !playerNode.isPlaying {
            playerNode.play()
        }
        return true
    }

    /// Decode an incoming Opus frame and schedule it for playback. `seq` is accepted for API
    /// parity with Android; the player node's queue provides buffering.
    func submitFrame(seq: Int, opus: Data) {
        processingQueue.async { self.decodeAndSchedule(opus) }
    }

    func stopPlayback() {
        playerNode.stop()
    }

    /// Speaker vs earpiece. `.voiceChat` (VPIO) routes to the earpiece by default; the in-call
    /// speaker toggle re-asserts the override just like the MC call pipeline does.
    func setSpeakerEnabled(_ enabled: Bool) {
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(enabled ? .speaker : .none)
    }

    func release() {
        stopCapture()
        playerNode.stop()
        engine.stop()
        let session = AVAudioSession.sharedInstance()
        try? session.overrideOutputAudioPort(.none)
        if !callKitManagesSession {
            try? session.setActive(false, options: [.notifyOthersOnDeactivation])
        }
        sessionActive = false
        callKitSessionActivated = false
        callKitManagesSession = false
        engineConfigured = false
        muted = false
    }

    // MARK: - Setup

    private func ensureSessionAndEngine(startEngine: Bool) throws {
        if !sessionActive {
            let session = AVAudioSession.sharedInstance()
            if !callKitManagesSession {
                try session.setCategory(.playAndRecord, mode: .voiceChat,
                    options: [.allowBluetoothHFP, .allowBluetoothA2DP, .defaultToSpeaker])
                try session.setPreferredSampleRate(Self.sampleRate)
                try session.setPreferredIOBufferDuration(0.02)
                try session.setActive(true, options: [])
            }
            sessionActive = true
        }

        // Even reading inputNode before activation caches a zero-channel hardware
        // route on some devices. Defer all hardware-node access, not only installTap.
        if callKitManagesSession && !callKitSessionActivated { return }

        if !voiceProcessingConfigured {
            voiceProcessingConfigured = true
            do {
                try engine.inputNode.setVoiceProcessingEnabled(true)
                voiceProcessingEnabled = true
                MessagingDiagLog.log("gatt-call voice processing enabled (AGC+AEC)")
            } catch {
                captureSoftwareGain = 8.0
                MessagingDiagLog.log("gatt-call voice processing unavailable (\(error)) — software gain fallback")
            }
        }

        let inputFormat = engine.inputNode.inputFormat(forBus: 0)
        if inputFormat.sampleRate > 0, inputFormat.channelCount > 0 {
            inputConverter = AVAudioConverter(from: inputFormat, to: pcmFormat)
        }
        if encoder == nil {
            let enc = AVAudioConverter(from: pcmFormat, to: opusFormat)
            enc?.bitRate = bitrateBps
            encoder = enc
        }
        if decoder == nil { decoder = AVAudioConverter(from: opusFormat, to: pcmFormat) }

        if !engineConfigured {
            // Capture this before connecting the 16 kHz player. Letting the mixer
            // infer its output from the wire format can stop VPIO microphone callbacks.
            let hardwareFormat = engine.inputNode.outputFormat(forBus: 0)
            guard hardwareFormat.sampleRate > 0, hardwareFormat.channelCount > 0 else {
                throw CaptureRouteError.unavailable
            }
            engine.attach(playerNode)
            engine.connect(playerNode, to: engine.mainMixerNode, format: pcmFormat)
            engine.connect(engine.mainMixerNode, to: engine.outputNode, format: hardwareFormat)
            engineConfigured = true
        }
        if startEngine, !engine.isRunning {
            // A microphone tap must participate in the graph when it starts. Starting
            // only the playback graph first can leave VPIO capture disconnected.
            if onFrame != nil { installTapIfNeeded() }
            engine.prepare()
            try engine.start()
            let audio = AVAudioSession.sharedInstance()
            MessagingDiagLog.log("gatt-call engine running=\(engine.isRunning) category=\(audio.category.rawValue) inputAvailable=\(audio.isInputAvailable) inputs=\(audio.currentRoute.inputs.map { $0.portType.rawValue })")
            if onFrame != nil, !hasInstalledTap, !installTapIfNeeded() { throw CaptureRouteError.unavailable }
        }
    }

    @discardableResult
    private func installTapIfNeeded() -> Bool {
        guard !hasInstalledTap else { return true }
        guard !callKitManagesSession || callKitSessionActivated else { return false }
        // Build the converter from the format the tap will actually deliver — querying it before
        // engine.start() (or before CallKit activates the session) can yield a stale/0Hz format
        // whose converter swallows every captured buffer into silence.
        let inputFormat = engine.inputNode.outputFormat(forBus: 0)
        guard inputFormat.sampleRate > 0, inputFormat.channelCount > 0,
              let converter = AVAudioConverter(from: inputFormat, to: pcmFormat) else {
            MessagingDiagLog.log("gatt-call capture tap deferred until a live input route is available")
            return false
        }
        inputConverter = converter
        MessagingDiagLog.log(
            "gatt-call tap installed format=\(Int(inputFormat.sampleRate))Hz/\(inputFormat.channelCount)ch converter=\(inputConverter != nil)"
        )
        engine.inputNode.installTap(onBus: 0, bufferSize: Self.tapBufferSize, format: inputFormat) { [weak self] buffer, _ in
            guard let self else { return }
            if !self.loggedHardwareBuffer {
                self.loggedHardwareBuffer = true
                MessagingDiagLog.log("gatt-call hardware callback frames=\(buffer.frameLength) capacity=\(buffer.frameCapacity) float=\(buffer.floatChannelData != nil)")
            }
            guard let copied = Self.copyBuffer(buffer) else { return }
            self.processingQueue.async {
                self.captureBufferCount += 1
                if self.captureBufferCount == 1 {
                    MessagingDiagLog.log("gatt-call first microphone buffer frames=\(copied.frameLength) rate=\(Int(copied.format.sampleRate))")
                }
                self.processCapturedBuffer(copied)
            }
        }
        hasInstalledTap = true
        return true
    }

    /// CallKit activated the audio session (its manual-audio handoff): (re)start the engine on
    /// the now-live route, reinstall the capture tap, and resume playback. Also recovers a start
    /// that was deferred because activation hadn't happened yet.
    func refreshInputAfterSessionActivation() {
        callKitSessionActivated = true
        // didActivate can arrive before startCapture. Remember it; setup will resume there.
        guard sessionActive else { return }
        if hasInstalledTap {
            engine.inputNode.removeTap(onBus: 0)
            hasInstalledTap = false
        }
        do { try ensureSessionAndEngine(startEngine: true) }
        catch { MessagingDiagLog.log("gatt-call activated audio route could not start: \(error)"); return }
        if onFrame != nil {
            installTapIfNeeded()
        }
        if engineConfigured, engine.isRunning, !playerNode.isPlaying {
            playerNode.play()
        }
    }

    // MARK: - Capture → Opus

    private enum CaptureRouteError: Error { case unavailable }

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
        if captureSoftwareGain != 1.0 {
            for index in 0..<frameCount {
                pendingCaptureSamples.append(max(-1.0, min(1.0, samples[index] * captureSoftwareGain)))
            }
        } else {
            pendingCaptureSamples.append(contentsOf: UnsafeBufferPointer(start: samples, count: frameCount))
        }
    }

    private func emitReadyFramesIfNeeded() {
        let frameCount = Int(Self.framesPerPacket)
        while pendingCaptureSamples.count >= frameCount {
            var frameSamples = Array(pendingCaptureSamples.prefix(frameCount))
            pendingCaptureSamples.removeFirst(frameCount)
            emittedFrameCount &+= 1
            if emittedFrameCount == 5 || emittedFrameCount % 250 == 0 {
                let peak = frameSamples.reduce(Float(0)) { max($0, abs($1)) }
                MessagingDiagLog.log(
                    "gatt-call capture frame#\(emittedFrameCount) peak=\(String(format: "%.4f", peak)) muted=\(muted)"
                )
            }
            if muted {
                frameSamples = [Float](repeating: 0, count: frameCount)
            }
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
        guard !payload.isEmpty, let decoder else { return }
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
        if conversionError != nil { return }
        // Decoding a single Opus packet returns `.inputRanDry` after emitting the PCM — that is
        // success, not failure (see the telsiz engine for the war story behind this check).
        if status == .error { return }
        guard decodedBuffer.frameLength > 0 else { return }
        // Engine may still be waiting for CallKit's didActivate (deferred start) — scheduling is
        // fine, but play() on a stopped engine raises. ensureLive resumes playback on activation.
        playerNode.scheduleBuffer(decodedBuffer, completionHandler: nil)
        if engine.isRunning, !playerNode.isPlaying {
            playerNode.play()
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
