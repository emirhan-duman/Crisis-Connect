import XCTest
import CallKit
import AVFoundation
@testable import Crisis_Connect

final class PhysicalCallKitMicrophoneTests: XCTestCase {
    @MainActor func testPlainMicrophoneInsideCallKit() async throws { try await run(voiceProcessing: false) }
    @MainActor func testVoiceProcessingMicrophoneInsideCallKit() async throws { try await run(voiceProcessing: true) }
    @MainActor func testGattMicrophoneInsideCallKit() async throws { try await run(voiceProcessing: true, productionEngine: true) }
    @MainActor func testNativeDuplexMicrophoneInsideCallKit() async throws { try await run(voiceProcessing: true, playbackGraph: true) }
    @MainActor func testPlainDuplexMicrophoneInsideCallKit() async throws { try await run(voiceProcessing: false, playbackGraph: true) }
    @MainActor private func run(voiceProcessing: Bool, productionEngine: Bool = false, playbackGraph: Bool = false) async throws {
        guard ProcessInfo.processInfo.environment["CC_PHYSICAL_BLE_VOICE"] == "1" else { throw XCTSkip("Physical CallKit opt-in") }
        let done = expectation(description: "Native microphone buffers while CallKit owns audio")
        let probe = NativeCallKitMicrophoneProbe(voiceProcessing: voiceProcessing, productionEngine: productionEngine, playbackGraph: playbackGraph) { error in
            if let error { XCTFail("Native CallKit microphone: \(error)") }; done.fulfill()
        }
        defer { probe.stop() }
        probe.start()
        await fulfillment(of: [done], timeout: 12)
        print("CCPhysicalMicTest CallKit voiceProcessing=\(voiceProcessing) productionEngine=\(productionEngine) buffers=\(probe.count)")
    }
}

private final class NativeCallKitMicrophoneProbe: NSObject, CXProviderDelegate {
    private let provider = SharedCallProvider.shared.provider
    private let controller = CXCallController()
    private let uuid = UUID()
    private lazy var engine = AVAudioEngine()
    private lazy var gattEngine = GattCallAudioEngine()
    private let player = AVAudioPlayerNode()
    private let playbackGraph: Bool
    private let productionEngine: Bool
    private let voiceProcessing: Bool
    private let completion: (Error?) -> Void
    private let lock = NSLock()
    private var buffers = 0
    private var tap = false
    var count: Int { lock.lock(); defer { lock.unlock() }; return buffers }
    init(voiceProcessing: Bool, productionEngine: Bool, playbackGraph: Bool, completion: @escaping (Error?) -> Void) {
        self.voiceProcessing = voiceProcessing; self.productionEngine = productionEngine
        self.playbackGraph = playbackGraph; self.completion = completion
    }
    func start() {
        SharedCallProvider.shared.makeActive(self)
        let update = CXCallUpdate(); update.localizedCallerName = "CC microphone test"
        update.remoteHandle = CXHandle(type: .generic, value: "owned-local-test")
        provider.reportNewIncomingCall(with: uuid, update: update) { [self] error in
            if let error { completion(error); return }
            controller.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { [self] error in
                if let error { completion(error) }
            }
        }
    }
    func providerDidReset(_ provider: CXProvider) {}
    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        do {
            let audio = AVAudioSession.sharedInstance()
            try audio.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
            try audio.setPreferredSampleRate(16_000)
            if productionEngine {
                gattEngine.callKitManagesSession = true
                guard gattEngine.prepare(), gattEngine.startPlayback(), gattEngine.startCapture(onFrame: { [self] _ in
                    lock.lock(); buffers += 1; let ready = buffers == 3; lock.unlock()
                    if ready { completion(nil) }
                }) else { action.fail(); completion(NSError(domain: "GattMic", code: 2)); return }
            }
            action.fulfill()
        } catch { action.fail(); completion(error) }
    }
    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        if productionEngine { gattEngine.refreshInputAfterSessionActivation(); return }
        do {
            if voiceProcessing { try engine.inputNode.setVoiceProcessingEnabled(true) }
            let format = engine.inputNode.outputFormat(forBus: 0)
            if playbackGraph {
                let pcm = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: 16_000, channels: 1, interleaved: false)!
                engine.attach(player); engine.connect(player, to: engine.mainMixerNode, format: pcm)
                engine.connect(engine.mainMixerNode, to: engine.outputNode, format: format)
            }
            guard format.sampleRate > 0, format.channelCount > 0 else {
                completion(NSError(domain: "NativeMic", code: 1)); return
            }
            engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { [self] buffer, _ in
                guard buffer.frameLength > 0 else { return }
                lock.lock(); buffers += 1; let ready = buffers == 3; lock.unlock()
                if ready { completion(nil) }
            }
            tap = true
            engine.prepare(); try engine.start()
            if playbackGraph { player.play() }
        } catch { completion(error) }
    }
    func stop() {
        if productionEngine { gattEngine.release() }
        engine.stop()
        if tap { engine.inputNode.removeTap(onBus: 0) }
        provider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
    }
}
