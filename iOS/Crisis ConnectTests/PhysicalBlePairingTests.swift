import XCTest
import CoreBluetooth
import CryptoKit
import UIKit
import AVFoundation
@testable import Crisis_Connect

/// Opt-in physical Android/iOS interop test. The coordinator supplies a fresh synthetic QR
/// payload to both devices. This exercises the production BLE verifier, not camera recognition.
final class PhysicalBlePairingTests: XCTestCase {
    @MainActor func testNativeMicrophoneBaseline() async throws {
        guard ProcessInfo.processInfo.environment["CC_PHYSICAL_BLE_VOICE"] == "1" else { throw XCTSkip("Physical microphone opt-in") }
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.defaultToSpeaker])
        try session.setActive(true)
        let engine = AVAudioEngine()
        let format = engine.inputNode.outputFormat(forBus: 0)
        let received = expectation(description: "Native microphone callback")
        let count = PhysicalFrameCounter()
        engine.inputNode.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            if buffer.frameLength > 0, count.increment() == 3 { received.fulfill() }
        }
        defer { engine.stop(); engine.inputNode.removeTap(onBus: 0); try? session.setActive(false) }
        engine.prepare(); try engine.start()
        await fulfillment(of: [received], timeout: 5)
        print("CCPhysicalMicTest native callbacks=\(count.value)")
    }

    @MainActor func testStandaloneGattMicrophoneProducesOpus() async throws {
        guard ProcessInfo.processInfo.environment["CC_PHYSICAL_BLE_VOICE"] == "1" else { throw XCTSkip("Physical microphone opt-in") }
        let audio = GattCallAudioEngine()
        defer { audio.release() }
        let count = PhysicalFrameCounter()
        let received = expectation(description: "Production GATT microphone Opus frames")
        XCTAssertTrue(audio.prepare()); XCTAssertTrue(audio.startPlayback())
        XCTAssertTrue(audio.startCapture { _ in if count.increment() == 10 { received.fulfill() } })
        await fulfillment(of: [received], timeout: 6)
        print("CCPhysicalMicTest production Opus frames=\(count.value)")
    }
    @MainActor func testAndroidQrCompletesEncryptedHandshakeOverPhysicalBluetooth() async throws {
        #if targetEnvironment(simulator)
        throw XCTSkip("Requires a physical Bluetooth peer")
        #else
        let env = ProcessInfo.processInfo.environment
        guard env["CC_PHYSICAL_BLE"] == "1",
              let key = env["CC_PHYSICAL_BLE_KEY"],
              let code = env["CC_PHYSICAL_BLE_CODE"],
              let share = env["CC_PHYSICAL_BLE_SHARE"],
              let localName = env["CC_PHYSICAL_BLE_PEER_NAME"] else {
            throw XCTSkip("Requires explicit coordination with the Android internal test host")
        }
        XCTAssertEqual(Data(base64Encoded: key)?.count, 32)
        FirebaseRuntime.ensureConfiguredForTests()
        let supplied = ContactQRPayload(code: code, key: key, platform: "android",
            name: "CC Hardware Android", shareId: share)
        let encoded = try XCTUnwrap(supplied.encodedString())
        let payload = try XCTUnwrap(ContactQRPayload.decode(from: encoded))
        XCTAssertTrue(payload.supportsSecureOfflinePairing)
        let verifier = ContactBroadcastVerifier()
        defer { verifier.cancel() }
        let done = expectation(description: "Production BLE verifier received authenticated server_finish")
        var outcome: ContactVerificationOutcome?
        verifier.verify(payload: payload, localDisplayName: localName, timeout: 45) {
            outcome = $0
            done.fulfill()
        }
        await fulfillment(of: [done], timeout: 50)
        switch outcome {
        case .verified(let peer):
            XCTAssertEqual(peer.shareId, share)
            XCTAssertEqual(peer.sessionCode, code)
            XCTAssertEqual(peer.platform, "android")
            XCTAssertEqual(peer.displayName, "CC Hardware Android")
            XCTAssertFalse(peer.remoteDeviceId.isEmpty)
            print("CCPhysicalBleTest PASS: physical Android/iPad encrypted QR handshake")
            if let messageId = env["CC_PHYSICAL_BLE_MESSAGE_ID"] {
                var voiceSessionId: UUID?
                if env["CC_PHYSICAL_BLE_VOICE"] == "1" {
                    let backup = try PhysicalVoiceStoreBackup()
                    addTeardownBlock { @MainActor in
                        ChatPeerVoiceCallCoordinator.shared.endCall()
                        try await Task.sleep(for: .seconds(2))
                        try await ContactStore.shared.flushDurably()
                        try await SOSChatStore.shared.flushToDisk()
                        try backup.restore()
                    }
                    await ContactStore.shared.waitUntilLoaded()
                    await SOSChatStore.shared.waitUntilLoaded()
                    let contact = ContactStore.shared.upsertBleContact(name: "CC Hardware Android", sessionCode: code,
                        aesKeyBase64: key, isVerified: true, verifiedIdentityKey: peer.remoteDeviceId,
                        remoteSessionCode: code, remotePlatform: .android, bleShareId: share,
                        lastKnownBleAddress: peer.lastKnownBleAddress, remoteDeviceId: peer.remoteDeviceId)
                    voiceSessionId = contact.id
                    _ = ChatPeerVoiceCallCoordinator.shared
                }
                let delivered = expectation(description: "Authenticated delivered receipt over physical BLE")
                let probe = PhysicalBleMessageProbe(peripheralId: try XCTUnwrap(UUID(uuidString: peer.lastKnownBleAddress)),
                    key: try XCTUnwrap(Data(base64Encoded: key)), messageId: messageId, sender: localName,
                    voiceSessionId: voiceSessionId) { result in
                    if case .failure(let error) = result { XCTFail("Physical encrypted message: \(error)") }
                    delivered.fulfill()
                }
                probe.start()
                await fulfillment(of: [delivered], timeout: 45)
                if let voiceSessionId {
                    let coordinator = ChatPeerVoiceCallCoordinator.shared
                    let ringingDeadline = Date().addingTimeInterval(45)
                    while coordinator.activeCall?.phase != .ringing && Date() < ringingDeadline {
                        try await Task.sleep(for: .milliseconds(100))
                    }
                    XCTAssertEqual(coordinator.activeCall?.phase, .ringing)
                    coordinator.answerCall(sessionId: voiceSessionId)
                    let activeDeadline = Date().addingTimeInterval(45)
                    while coordinator.activeCall?.phase != .active && Date() < activeDeadline {
                        try await Task.sleep(for: .milliseconds(100))
                    }
                    XCTAssertEqual(coordinator.activeCall?.phase, .active)
                    print("CCPhysicalBleTest VOICE ACTIVE: production iOS call coordinator and microphone")
                    var rx = 0, tx: UInt32 = 0
                    for _ in 0..<50 {
                        let counters = probe.voiceCounters()
                        rx = max(rx, counters.1); tx = max(tx, counters.0)
                        try await Task.sleep(for: .milliseconds(200))
                    }
                    XCTAssertGreaterThanOrEqual(rx, 20, "No authenticated Android microphone frames")
                    XCTAssertGreaterThanOrEqual(tx, 20, "No iPad microphone frames emitted")
                    print("CCPhysicalBleTest \(rx >= 20 && tx >= 20 ? "PASS" : "FAIL") VOICE: authenticated audio frames rx=\(rx) tx=\(tx)")
                    let endDeadline = Date().addingTimeInterval(15)
                    while coordinator.activeCall?.phase == .active && Date() < endDeadline {
                        try await Task.sleep(for: .milliseconds(100))
                    }
                    XCTAssertNotEqual(coordinator.activeCall?.phase, .active, "Peer hangup did not end iOS call")
                }
                probe.stop()
                print("CCPhysicalBleTest message probe completed (see assertions for result)")
            }
        case .failed(let failure):
            XCTFail("Physical BLE verifier failed: \(failure)")
        case nil:
            XCTFail("Physical BLE verifier never completed")
        }
        #endif
    }
}

private final class PhysicalFrameCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    var value: Int { lock.lock(); defer { lock.unlock() }; return count }
    func increment() -> Int { lock.lock(); defer { lock.unlock() }; count += 1; return count }
}

/// A test central using production framing/crypto against the production Android server.
/// Does not insert contacts into the iPad's shared store or start its messaging services.
private final class PhysicalBleMessageProbe: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, GattCallLink {
    private let peripheralId: UUID
    private let key: Data
    private let messageId: String
    private let sender: String
    private let completion: (Result<Void, Error>) -> Void
    private let queue = DispatchQueue(label: "cc.hardware.message.probe")
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var input: CBCharacteristic?
    private var chunks: [Data] = []
    private var writing = false
    private let receiver = BleChunkReceiver(maxPacketSize: 8192)
    private var challenge: P2pPrivacyWire.Challenge?
    private var done = false
    private let voiceSessionId: UUID?

    init(peripheralId: UUID, key: Data, messageId: String, sender: String, voiceSessionId: UUID? = nil,
         completion: @escaping (Result<Void, Error>) -> Void) {
        self.peripheralId = peripheralId; self.key = key; self.messageId = messageId
        self.sender = sender; self.completion = completion
        self.voiceSessionId = voiceSessionId
    }

    func start() { central = CBCentralManager(delegate: self, queue: queue) }
    func stop() {
        queue.async { [self] in
            done = true
            if let peripheral { central?.cancelPeripheralConnection(peripheral) }
        }
    }
    private func fail(_ text: String) {
        finish(.failure(NSError(domain: "CCPhysicalBleTest", code: 1,
            userInfo: [NSLocalizedDescriptionKey: text])))
    }
    private func finish(_ result: Result<Void, Error>) {
        guard !done else { return }; done = true; completion(result)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard central.state == .poweredOn else {
            if central.state == .poweredOff || central.state == .unauthorized { fail("Bluetooth unavailable") }
            return
        }
        guard let peer = central.retrievePeripherals(withIdentifiers: [peripheralId]).first else {
            fail("Paired peripheral was not retrievable"); return
        }
        peripheral = peer; peer.delegate = self; central.connect(peer)
    }
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([P2pBleProtocol.serviceUUID])
    }
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        fail("Connection failed: \(String(describing: error))")
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == P2pBleProtocol.serviceUUID }) else {
            fail("P2P service missing"); return
        }
        peripheral.discoverCharacteristics([P2pBleProtocol.messageInCharacteristicUUID,
            P2pBleProtocol.messageOutCharacteristicUUID], for: service)
    }
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        input = service.characteristics?.first { $0.uuid == P2pBleProtocol.messageInCharacteristicUUID }
        guard error == nil, input != nil,
              let output = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.messageOutCharacteristicUUID }) else {
            fail("P2P message characteristics missing"); return
        }
        peripheral.setNotifyValue(true, for: output)
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.isNotifying else { fail("Subscription failed"); return }
        let fresh = P2pPrivacyWire.Challenge(); challenge = fresh
        send(["kind": "privacy_ping", "messageId": fresh.nonce, "senderName": sender])
    }
    private func send(_ payload: [String: Any]) {
        guard let peripheral, input != nil else { fail("No writable route"); return }
        do {
            let plain = try JSONSerialization.data(withJSONObject: payload)
            let sealed = BleAesGcm.encryptPacket(key: SymmetricKey(data: key), plaintext: plain, maxPacketSize: 4096)
            guard !sealed.isEmpty else { fail("Encryption failed"); return }
            let envelope: [String: Any] = ["type": P2pBleProtocol.typeChatEnvelope,
                "fromDeviceId": P2pPrivacyWire.alias(key: key, packet: sealed), "payload": sealed.base64EncodedString()]
            let wire = BleAesGcm.wrapTransportPacket(try JSONSerialization.data(withJSONObject: envelope))
            let size = max(1, peripheral.maximumWriteValueLength(for: .withResponse))
            chunks = stride(from: 0, to: wire.count, by: size).map { wire.subdata(in: $0..<min($0 + size, wire.count)) }
            writeNext()
        } catch { finish(.failure(error)) }
    }
    private func writeNext() {
        guard let peripheral, let input, !chunks.isEmpty else { return }
        writing = true
        peripheral.writeValue(chunks.removeFirst(), for: input, type: .withResponse)
    }
    func setCallHold(_ active: Bool) {}
    func sendCallSignal(_ payload: [String: Any]) -> Bool {
        queue.async { [self] in send(payload) }
        return true
    }
    func sendCallAudioFrame(_ packet: Data) -> Bool {
        queue.sync {
            guard let peripheral, let input, chunks.isEmpty, !writing, peripheral.canSendWriteWithoutResponse else { return false }
            let wrapped = BleAesGcm.wrapTransportPacket(packet)
            guard wrapped.count <= peripheral.maximumWriteValueLength(for: .withoutResponse) else { return false }
            peripheral.writeValue(wrapped, for: input, type: .withoutResponse)
            return true
        }
    }
    /// Read production audio counters on their owning queue; no audio content is retained.
    func voiceCounters() -> (UInt32, Int) {
        let coordinator = ChatPeerVoiceCallCoordinator.shared
        guard let owner = Mirror(reflecting: coordinator).descendant("transportQueue") as? DispatchQueue else { return (0, 0) }
        return owner.sync {
            guard let wrapped = Mirror(reflecting: coordinator).descendant("gattRuntime"),
                  let runtime = Mirror(reflecting: wrapped).children.first?.value as? ChatPeerVoiceGattCallRuntime else { return (0, 0) }
            return (runtime.nextSeq, runtime.rxFramesReceived)
        }
    }
    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        writing = false
        guard error == nil else { fail("Message write rejected: \(String(describing: error))"); return }
        writeNext()
    }
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { fail("Notification failed"); return }
        do {
            guard let packet = try receiver.onChunk(value) else { return }
            let data = try BleAesGcm.unwrapTransportPacket(packet, maxPacketSize: 8192)
            if voiceSessionId != nil, P2pCallProtocol.isCallAudioFrame(data) {
                ChatPeerVoiceCallCoordinator.shared.handleGattCallAudio(data)
                return
            }
            guard let outer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let alias = outer["fromDeviceId"] as? String,
                  let encoded = outer["payload"] as? String, let sealed = Data(base64Encoded: encoded),
                  P2pPrivacyWire.matches(alias, key: key, packet: sealed) else {
                fail("Reply lacked an authenticated private routing alias"); return
            }
            // Android emits a raw GCM box; iOS may emit the length-prefixed representation.
            // Match the production chat reader's order rather than requiring the iOS form.
            let aesKey = SymmetricKey(data: key)
            let plaintext: Data
            if let box = try? AES.GCM.SealedBox(combined: sealed),
               let opened = try? AES.GCM.open(box, using: aesKey) {
                plaintext = opened
            } else {
                plaintext = try BleAesGcm.decryptPacket(key: aesKey, transportPacket: sealed, maxPacketSize: 4096)
            }
            guard let payload = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any] else {
                fail("Reply was not a chat payload"); return
            }
            if let voiceSessionId, P2pCallProtocol.isCallSignalKind(payload["kind"] as? String) {
                ChatPeerVoiceCallCoordinator.shared.handleGattCallSignal(sessionId: voiceSessionId, payload: payload, link: self)
                return
            }
            if payload["kind"] as? String == "privacy_pong" {
                guard challenge?.accept(payload["messageId"] as? String) == true else {
                    fail("Reply did not prove the fresh connection challenge"); return
                }
                send(["kind": "text", "messageId": messageId, "text": "CC Hardware encrypted message", "senderName": sender])
            } else if payload["kind"] as? String == "delivered", payload["messageId"] as? String == messageId {
                guard challenge != nil else { fail("Receipt arrived without authentication"); return }
                print("CCPhysicalBleTest PASS: encrypted physical message and authenticated delivered receipt")
                finish(.success(()))
            } else { fail("Unexpected chat reply") }
        } catch { finish(.failure(error)) }
    }
}

/// Preserve pre-test application records; the test process ends after restoring the bytes.
private final class PhysicalVoiceStoreBackup: @unchecked Sendable {
    private let originals: [(URL, Data?)]
    private let journal: URL
    init() throws {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CrisisConnect")
        journal = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("cc-physical-voice-backup")
        let names = ["contacts.json", "sos_chat.json"]
        // A native audio assertion may terminate the process before XCTest teardown.
        // Recover the pre-test bytes before starting another attempt.
        if let manifest = try? Data(contentsOf: journal.appendingPathComponent("manifest.json")),
           let existing = try? JSONDecoder().decode([String: Bool].self, from: manifest) {
            for name in names {
                let url = root.appendingPathComponent(name)
                if existing[name] == true { try Data(contentsOf: journal.appendingPathComponent(name)).write(to: url, options: .atomic) }
                else { try? FileManager.default.removeItem(at: url) }
            }
            try FileManager.default.removeItem(at: journal)
        }
        // Clean only the exact synthetic fixture left by the first, pre-journal crash.
        let contactsURL = root.appendingPathComponent("contacts.json")
        if let key = ProcessInfo.processInfo.environment["CC_PHYSICAL_BLE_KEY"],
           let data = try? LocalEncryptedFileStore.read(from: contactsURL),
           var rows = try? JSONSerialization.jsonObject(with: data.data) as? [[String: Any]] {
            let stale = rows.filter { $0["aesKeyBase64"] as? String == key && $0["name"] as? String == "CC Hardware Android" }
            if !stale.isEmpty {
                let ids = Set(stale.compactMap { $0["id"] as? String })
                rows.removeAll { ids.contains($0["id"] as? String ?? "") }
                try LocalEncryptedFileStore.write(JSONSerialization.data(withJSONObject: rows), to: contactsURL)
                let chatURL = root.appendingPathComponent("sos_chat.json")
                if let chatData = try? LocalEncryptedFileStore.read(from: chatURL),
                   var chat = try? JSONSerialization.jsonObject(with: chatData.data) as? [String: Any] {
                    if var sessions = chat["sessions"] as? [[String: Any]] {
                        sessions.removeAll { ids.contains($0["id"] as? String ?? "") }; chat["sessions"] = sessions
                    }
                    if var messages = chat["messagesBySession"] as? [String: Any] {
                        for id in ids { messages.removeValue(forKey: id) }; chat["messagesBySession"] = messages
                    } else if let messages = chat["messagesBySession"] as? [Any] {
                        var kept: [Any] = []
                        for i in stride(from: 0, to: messages.count - 1, by: 2) where !ids.contains(messages[i] as? String ?? "") {
                            kept.append(messages[i]); kept.append(messages[i + 1])
                        }
                        chat["messagesBySession"] = kept
                    }
                    try LocalEncryptedFileStore.write(JSONSerialization.data(withJSONObject: chat), to: chatURL)
                }
            }
        }
        originals = try ["contacts.json", "sos_chat.json"].map { name in
            let url = root.appendingPathComponent(name)
            return (url, FileManager.default.fileExists(atPath: url.path) ? try Data(contentsOf: url) : nil)
        }
        try FileManager.default.createDirectory(at: journal, withIntermediateDirectories: true)
        var manifest: [String: Bool] = [:]
        for (url, bytes) in originals {
            manifest[url.lastPathComponent] = bytes != nil
            if let bytes { try bytes.write(to: journal.appendingPathComponent(url.lastPathComponent), options: .atomic) }
        }
        try JSONEncoder().encode(manifest).write(to: journal.appendingPathComponent("manifest.json"), options: .atomic)
    }
    func restore() throws {
        for (url, bytes) in originals {
            if let bytes { try bytes.write(to: url, options: .atomic) }
            else if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        }
        try FileManager.default.removeItem(at: journal)
    }
}
