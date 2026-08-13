//
//  P2pGattChatManager.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import CoreBluetooth
import CryptoKit
import Combine
import UIKit

private enum P2pPendingEvent {
    case text(String, String)
    case read
    case delivered(String)
    case decryptFail(String?)
    case voiceInit(String, String, Int, Int)
    case voiceChunk(String, Int, Data)
    case voiceDone(String)
    case voiceAbort(String)
    case imageInit(String, String, Int, Int, Int, Int, String)
    case imageChunk(String, Int, Data)
    case imageDone(String)
    case imageAbort(String)
    case fileInit(String, String, String?, Int, Int, Int, String)
    case fileChunk(String, Int, Data)
    case fileDone(String)
    case fileAbort(String)
}

private struct P2pDecodedChatPayload {
    let kind: String
    let messageId: String?
    let text: String?
    let senderName: String?
    let displayName: String?
    let mimeType: String?
    let durationMillis: Int?
    let width: Int?
    let height: Int?
    let originalSizeBytes: Int?
    let totalBytes: Int?
    let totalChunks: Int?
    let sha256: String?
    let chunkIndex: Int?
    let chunkData: Data?
}

private struct P2pBootstrapIdentity {
    let shareId: String?
    let deviceId: String?
    let displayName: String?
}

private struct P2pIncomingVoiceTransfer {
    let messageId: String
    let mimeType: String
    let durationMillis: Int
    let totalChunks: Int
    var chunks: [Int: Data] = [:]
    var receivedDone = false

    var isComplete: Bool {
        receivedDone && chunks.count == totalChunks
    }

    func composedData() -> Data? {
        guard isComplete else { return nil }
        var combined = Data()
        for index in 0..<totalChunks {
            guard let chunk = chunks[index] else { return nil }
            combined.append(chunk)
        }
        return combined
    }
}

private struct P2pIncomingImageTransfer {
    let messageId: String
    let mimeType: String
    let width: Int
    let height: Int
    let totalBytes: Int
    let totalChunks: Int
    let sha256: String
    var chunks: [Int: Data] = [:]
    var receivedDone = false

    var isComplete: Bool {
        receivedDone && chunks.count == totalChunks
    }

    func composedData() -> Data? {
        guard isComplete else { return nil }
        var combined = Data()
        for index in 0..<totalChunks {
            guard let chunk = chunks[index] else { return nil }
            combined.append(chunk)
        }
        guard combined.count == totalBytes else { return nil }
        let digest = Data(SHA256.hash(data: combined)).base64EncodedString()
        guard digest == sha256 else { return nil }
        return combined
    }
}

private struct P2pIncomingFileTransfer {
    let messageId: String
    let displayName: String
    let mimeType: String?
    let originalSizeBytes: Int
    let totalBytes: Int
    let totalChunks: Int
    let sha256: String
    var chunks: [Int: Data] = [:]
    var receivedDone = false

    var isComplete: Bool {
        receivedDone && chunks.count == totalChunks
    }

    func composedData() -> Data? {
        guard isComplete else { return nil }
        var combined = Data()
        for index in 0..<totalChunks {
            guard let chunk = chunks[index] else { return nil }
            combined.append(chunk)
        }
        guard combined.count == totalBytes else { return nil }
        let digest = Data(SHA256.hash(data: combined)).base64EncodedString()
        guard digest == sha256 else { return nil }
        return combined
    }
}

private let p2pMaxEncryptedChatPacketBytes = 4096
private let p2pMaxEnvelopePacketBytes = 8192
private let p2pConnectFastDelay: TimeInterval = 0.2
private let p2pConnectSlowDelay: TimeInterval = 0.65
private let p2pTransientRetryStep: TimeInterval = 0.45
private let p2pMaxTransientConnectFailures = 3
private let p2pDisconnectGrace: TimeInterval = 45

final class P2pGattChatManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    private static let registryQueue = DispatchQueue(label: "contact.p2p.chat.registry")
    private static var sharedManagers: [UUID: P2pGattChatManager] = [:]

    /// Registry peek for callers that only want a link that ALREADY exists (the send-retry
    /// queue's Bluetooth drain): shared(sessionId:) creates a dormant manager as a side effect,
    /// which would slowly grow the registry with an entry per queued conversation.
    static func sharedIfExists(sessionId: UUID) -> P2pGattChatManager? {
        registryQueue.sync { sharedManagers[sessionId] }
    }

    static func shared(sessionId: UUID) -> P2pGattChatManager {
        if let existing = registryQueue.sync(execute: { sharedManagers[sessionId] }) {
            return existing
        }
        let manager = P2pGattChatManager(sessionId: sessionId)
        registryQueue.sync { sharedManagers[sessionId] = manager }
        return manager
    }

    private static func removeShared(sessionId: UUID, instance: P2pGattChatManager) {
        registryQueue.async {
            guard let current = sharedManagers[sessionId], current === instance else { return }
            sharedManagers.removeValue(forKey: sessionId)
        }
    }

    @Published private(set) var status: RescueConnectionStatus = .disconnected
    @Published private(set) var isScanning: Bool = false

    private let sessionId: UUID
    private let queue: DispatchQueue
    private let queueKey = DispatchSpecificKey<Void>()
    private let outputReceiver = BleChunkReceiver(maxPacketSize: p2pMaxEnvelopePacketBytes)

    private var central: CBCentralManager?
    private var activePeripheral: CBPeripheral?
    private var idCharacteristic: CBCharacteristic?
    private var bootstrapCharacteristic: CBCharacteristic?
    private var messageInCharacteristic: CBCharacteristic?
    private var messageOutCharacteristic: CBCharacteristic?
    private var pendingEvents: [P2pPendingEvent] = []
    private var writeQueue: [WriteRequest] = []
    private var activeWrite: WriteRequest?
    private var reconnectWork: DispatchWorkItem?
    private var connectWork: DispatchWorkItem?
    private var disconnectWork: DispatchWorkItem?
    private var shouldStayConnected = false
    private var validatedIdentity = false
    private var authenticatedName: String?
    private var transientConnectFailures: [UUID: Int] = [:]
    private var incomingVoiceTransfers: [String: P2pIncomingVoiceTransfer] = [:]
    private var incomingImageTransfers: [String: P2pIncomingImageTransfer] = [:]
    private var incomingFileTransfers: [String: P2pIncomingFileTransfer] = [:]
    private var callHoldActive = false

    private final class WriteRequest {
        let characteristic: CBCharacteristic
        let chunks: [Data]
        var index: Int = 0

        init(characteristic: CBCharacteristic, chunks: [Data]) {
            self.characteristic = characteristic
            self.chunks = chunks
        }
    }

    private init(sessionId: UUID) {
        self.sessionId = sessionId
        self.queue = DispatchQueue(label: "contact.p2p.chat.\(sessionId.uuidString)")
        super.init()
        queue.setSpecific(key: queueKey, value: ())
    }

    func start() {
        queue.async { [weak self] in
            guard let self else { return }
            self.ensureCentralInitialized()
            self.disconnectWork?.cancel()
            self.disconnectWork = nil
            self.shouldStayConnected = true
            self.beginScanIfPossible()
            if self.isReadyForWrites {
                self.flushPendingEvents()
            }
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.scheduleDelayedStop()
        }
    }

    func sendMessage(_ text: String, messageId: String? = nil) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return syncOnQueue {
            ensureCentralInitialized()
            pendingEvents.append(.text(trimmed, messageId?.nilIfEmpty ?? UUID().uuidString))
            let ready = isReadyForWrites
            beginScanIfPossible()
            if ready {
                flushPendingEvents()
            }
            return ready
        }
    }

    func sendFileMessage(
        data: Data,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        messageId: String
    ) -> Bool {
        guard !data.isEmpty, data.count <= P2pBleProtocol.fileMaxTotalBytes else { return false }
        let trimmedId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty, !trimmedName.isEmpty, originalSizeBytes > 0 else { return false }
        let totalChunks = max(1, Int(ceil(Double(data.count) / Double(P2pBleProtocol.fileChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.fileMaxChunks else { return false }
        let digest = Data(SHA256.hash(data: data)).base64EncodedString()

        return syncOnQueue {
            ensureCentralInitialized()
            pendingEvents.append(
                .fileInit(
                    trimmedId,
                    trimmedName,
                    mimeType?.nilIfEmpty,
                    originalSizeBytes,
                    data.count,
                    totalChunks,
                    digest
                )
            )
            var offset = 0
            for index in 0..<totalChunks {
                let end = min(offset + P2pBleProtocol.fileChunkSizeBytes, data.count)
                pendingEvents.append(.fileChunk(trimmedId, index, data.subdata(in: offset..<end)))
                offset = end
            }
            pendingEvents.append(.fileDone(trimmedId))
            let ready = isReadyForWrites
            beginScanIfPossible()
            if ready {
                flushPendingEvents()
            }
            return ready
        }
    }

    func sendReadReceipt() -> Bool {
        syncOnQueue {
            ensureCentralInitialized()
            pendingEvents.append(.read)
            let ready = isReadyForWrites
            beginScanIfPossible()
            if ready {
                flushPendingEvents()
            }
            return ready
        }
    }

    func isReady() -> Bool {
        syncOnQueue { isReadyForWrites }
    }

    // MARK: - Voice call link (signaling + binary audio over the same characteristics)

    /// Keeps the connection alive for the duration of a voice call: cancels any delayed stop and
    /// prevents new ones until released. Released via `setCallHold(false)`, which resumes the
    /// regular lifecycle (grace period, then stop unless the chat screen re-attached).
    func setCallHold(_ active: Bool) {
        queue.async { [weak self] in
            guard let self else { return }
            self.callHoldActive = active
            if active {
                self.disconnectWork?.cancel()
                self.disconnectWork = nil
                self.shouldStayConnected = true
                self.beginScanIfPossible()
            } else {
                self.scheduleDelayedStop()
            }
        }
    }

    /// Sends an encrypted call-signaling payload over the regular acknowledged write queue.
    func sendCallSignal(_ payload: [String: Any]) -> Bool {
        syncOnQueue {
            ensureCentralInitialized()
            guard isReadyForWrites, let characteristic = messageInCharacteristic else {
                beginScanIfPossible()
                return false
            }
            guard let packet = makeCallEnvelope(payload) else { return false }
            enqueueWrite(packet, to: characteristic)
            return true
        }
    }

    /// Single-ATT-write audio fast path (write-without-response); false when the link cannot
    /// take the packet right now so the caller can count the drop.
    func sendCallAudioFrame(_ packet: Data) -> Bool {
        syncOnQueue {
            guard isReadyForWrites,
                  let peripheral = activePeripheral,
                  let characteristic = messageInCharacteristic else {
                return false
            }
            let wrapped = BleAesGcm.wrapTransportPacket(packet)
            // Android builds up to 1.1.7 declare MESSAGE_IN with PROPERTY_WRITE only — CoreBluetooth
            // (unlike Android's lenient stack) refuses .withoutResponse writes to it, which silently
            // dropped EVERY audio frame of an iOS→Android call. Fall back to acknowledged writes for
            // those peers; newer Android hosts advertise writeWithoutResponse and get the fast path.
            let writeType: CBCharacteristicWriteType =
                characteristic.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse
            let maxLength = peripheral.maximumWriteValueLength(for: writeType)
            guard wrapped.count <= maxLength else { return false }
            if writeType == .withoutResponse {
                guard peripheral.canSendWriteWithoutResponse else { return false }
            }
            peripheral.writeValue(wrapped, for: characteristic, type: writeType)
            return true
        }
    }

    private func makeCallEnvelope(_ innerPayload: [String: Any]) -> Data? {
        guard let contact = currentContact,
              let key = ContactStore.shared.aesKey(for: contact) else {
            return nil
        }
        guard let innerData = try? JSONSerialization.data(withJSONObject: innerPayload, options: []),
              let encryptedPacket = BleAesGcm.encryptPacket(
                key: key,
                plaintext: innerData,
                maxPacketSize: p2pMaxEncryptedChatPacketBytes
              ).nilIfEmptyData else {
            return nil
        }
        let outerPayload: [String: Any] = [
            "type": P2pBleProtocol.typeChatEnvelope,
            "fromDeviceId": localDeviceId(),
            "payload": encryptedPacket.base64EncodedString()
        ]
        guard let outerData = try? JSONSerialization.data(withJSONObject: outerPayload, options: []),
              outerData.count <= p2pMaxEnvelopePacketBytes else {
            return nil
        }
        return BleAesGcm.wrapTransportPacket(outerData)
    }

    func sendVoiceMessage(audioFileName: String, mimeType: String, durationMillis: Int, messageId: String) -> Bool {
        guard let data = SOSChatStore.loadVoiceData(fileName: audioFileName), !data.isEmpty else { return false }
        guard data.count <= P2pBleProtocol.voiceMaxTotalBytes else { return false }
        let totalChunks = max(1, Int(ceil(Double(data.count) / Double(P2pBleProtocol.voiceChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.voiceMaxChunks else { return false }
        let trimmedId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty else { return false }

        return syncOnQueue {
            ensureCentralInitialized()
            pendingEvents.append(.voiceInit(trimmedId, mimeType, max(0, durationMillis), totalChunks))
            var offset = 0
            for index in 0..<totalChunks {
                let end = min(offset + P2pBleProtocol.voiceChunkSizeBytes, data.count)
                pendingEvents.append(.voiceChunk(trimmedId, index, data.subdata(in: offset..<end)))
                offset = end
            }
            pendingEvents.append(.voiceDone(trimmedId))
            let ready = isReadyForWrites
            beginScanIfPossible()
            if ready {
                flushPendingEvents()
            }
            return ready
        }
    }

    func sendImageMessage(
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String
    ) -> Bool {
        guard let data = SOSChatStore.loadImageData(fileName: imageFileName), !data.isEmpty else { return false }
        guard data.count <= P2pBleProtocol.imageMaxTotalBytes else { return false }
        let totalChunks = max(1, Int(ceil(Double(data.count) / Double(P2pBleProtocol.imageChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.imageMaxChunks else { return false }
        let trimmedId = messageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedId.isEmpty else { return false }
        let digest = Data(SHA256.hash(data: data)).base64EncodedString()

        return syncOnQueue {
            ensureCentralInitialized()
            pendingEvents.append(
                .imageInit(
                    trimmedId,
                    mimeType,
                    max(1, width),
                    max(1, height),
                    data.count,
                    totalChunks,
                    digest
                )
            )
            var offset = 0
            for index in 0..<totalChunks {
                let end = min(offset + P2pBleProtocol.imageChunkSizeBytes, data.count)
                pendingEvents.append(.imageChunk(trimmedId, index, data.subdata(in: offset..<end)))
                offset = end
            }
            pendingEvents.append(.imageDone(trimmedId))
            let ready = isReadyForWrites
            beginScanIfPossible()
            if ready {
                flushPendingEvents()
            }
            return ready
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            beginScanIfPossible()
        } else {
            // Bluetooth went away: the peripheral/characteristics are dead objects now. Without a
            // full reset, isReadyForWrites stayed TRUE and message/call sends kept routing into
            // the void ("offer delivered" to nobody) instead of falling back to the internet.
            resetConnectionState(clearPeripheral: true)
            updateStatus(.disconnected)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        guard shouldStayConnected else { return }
        guard let contact = currentContact, contact.preferredTransport == .bleGatt else { return }
        guard activePeripheral == nil else { return }

        let expectedAddress = contact.lastKnownBleAddress?.nilIfEmpty?.uppercased()
        let expectedShareId = contact.bleShareId?.nilIfEmpty
        let expectedDeviceId = contact.remoteDeviceId?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfEmpty
        let advertisesP2pService = P2pBleProtocol.advertisesService(
            P2pBleProtocol.serviceUUID,
            in: advertisementData
        )
        let candidateAddress = peripheral.identifier.uuidString.uppercased()
        if let expectedAddress {
            if candidateAddress == expectedAddress {
                connect(peripheral)
                return
            }
        }

        if let expectedShareId {
            if let advertisedShareId = P2pBleProtocol.advertisedShareId(from: advertisementData) {
                guard P2pBleProtocol.normalizeShareId(advertisedShareId) == P2pBleProtocol.normalizeShareId(expectedShareId) else {
                    return
                }
                connect(peripheral)
                return
            }
            if let peripheralNameShareId = P2pBleProtocol.advertisedShareId(from: [:], peripheralName: peripheral.name),
               P2pBleProtocol.normalizeShareId(peripheralNameShareId) == P2pBleProtocol.normalizeShareId(expectedShareId) {
                connect(peripheral)
                return
            }
            if expectedDeviceId != nil, advertisesP2pService {
                connect(peripheral)
                return
            }
            return
        }

        if expectedDeviceId != nil {
            if advertisesP2pService {
                connect(peripheral)
            }
            return
        }

        if expectedAddress != nil {
            return
        }

        // With an unfiltered scan the discovery callback sees every nearby device,
        // so the last-resort path must still require the P2P service advertisement.
        if advertisesP2pService {
            connect(peripheral)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        transientConnectFailures.removeValue(forKey: peripheral.identifier)
        stopScanIfNeeded()
        updateStatus(.discovering)
        peripheral.discoverServices([P2pBleProtocol.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        handleConnectFailure(for: peripheral, error: error)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        resetConnectionState(clearPeripheral: true)
        if shouldStayConnected {
            scheduleReconnect()
        } else {
            updateStatus(.disconnected)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            disconnectAndRetry()
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == P2pBleProtocol.serviceUUID }) else {
            disconnectAndRetry()
            return
        }
        peripheral.discoverCharacteristics(
            [
                P2pBleProtocol.idCharacteristicUUID,
                P2pBleProtocol.bootstrapCharacteristicUUID,
                P2pBleProtocol.messageInCharacteristicUUID,
                P2pBleProtocol.messageOutCharacteristicUUID
            ],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            disconnectAndRetry()
            return
        }
        idCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.idCharacteristicUUID })
        bootstrapCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.bootstrapCharacteristicUUID })
        messageInCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.messageInCharacteristicUUID })
        messageOutCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.messageOutCharacteristicUUID })

        guard idCharacteristic != nil,
              messageInCharacteristic != nil,
              messageOutCharacteristic != nil else {
            disconnectAndRetry()
            return
        }

        guard let idCharacteristic else {
            disconnectAndRetry()
            return
        }
        peripheral.readValue(for: idCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else {
            disconnectAndRetry()
            return
        }

        switch characteristic.uuid {
        case P2pBleProtocol.idCharacteristicUUID:
            handleIdentityValue(value, on: peripheral)

        case P2pBleProtocol.bootstrapCharacteristicUUID:
            handleBootstrapValue(value, on: peripheral)

        case P2pBleProtocol.messageOutCharacteristicUUID:
            handleMessageOutChunk(value)

        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == P2pBleProtocol.messageOutCharacteristicUUID, characteristic.isNotifying else {
            disconnectAndRetry()
            return
        }
        validatedIdentity = true
        updateStatus(.ready)
        ContactStore.shared.updateBleAddress(sessionId: sessionId, address: peripheral.identifier.uuidString.uppercased())
        flushPendingEvents()
        // The link just came up: hand any queued texts for this conversation to it. Android drains
        // its queue on exactly this transition; without the kick a pending message sat unsent next
        // to a live Bluetooth link until the user happened to foreground the app.
        Task { @MainActor in InternetSendRetryQueue.shared.kick(reason: "bt-ready") }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        queue.async { [weak self] in
            self?.handleWriteResult(success: error == nil)
        }
    }

    private var currentContact: ContactRecord? {
        ContactStore.shared.contact(for: sessionId)
    }

    private func verifiedIdentityKey(envelopeDeviceId: String? = nil) -> String? {
        envelopeDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? currentContact?.verifiedIdentityKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? currentContact?.remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private var isReadyForWrites: Bool {
        activePeripheral != nil && validatedIdentity && messageInCharacteristic != nil
    }

    private func ensureCentralInitialized() {
        guard central == nil else { return }
        central = CBCentralManager(
            delegate: self,
            queue: queue,
            options: [
                // Restoration is per-session (the identifier must be unique per manager). Delivery
                // only happens when a manager with the same id is re-created after relaunch — i.e.
                // when the user reopens this chat — so this mainly protects an in-flight connection
                // across a background kill rather than resurrecting every chat at launch.
                CBCentralManagerOptionRestoreIdentifierKey:
                    "com.auralis.crisisconnect.p2p-chat.\(sessionId.uuidString)",
                CBCentralManagerOptionShowPowerAlertKey: false
            ]
        )
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        queue.async { [weak self] in
            guard let self else { return }
            let restored = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? []
            // Re-adopt the link the system kept alive for us; the normal connect callbacks then
            // resume the identity handshake exactly as after a fresh connect.
            if let peripheral = restored.first {
                peripheral.delegate = self
                self.activePeripheral = peripheral
                if peripheral.state == .connected {
                    peripheral.discoverServices([P2pBleProtocol.serviceUUID])
                }
            }
        }
    }

    private func beginScanIfPossible() {
        ensureCentralInitialized()
        guard let central else { return }
        guard shouldStayConnected else { return }
        guard central.state == .poweredOn else { return }
        guard currentContact?.preferredTransport == .bleGatt else { return }
        guard activePeripheral == nil else { return }
        if !central.isScanning {
            // Scan unfiltered: when the Android peer co-advertises its mesh profile,
            // the P2P service UUID may only appear in the scan response, which a
            // CoreBluetooth service-filtered scan never matches. Candidates are
            // validated in didDiscover (address / shareId / deviceId + service).
            central.scanForPeripherals(
                withServices: nil,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
            DispatchQueue.main.async { [weak self] in
                self?.isScanning = true
            }
        }
        updateStatus(.connecting)
    }

    private func stopScanIfNeeded() {
        guard let central else {
            DispatchQueue.main.async { [weak self] in
                self?.isScanning = false
            }
            return
        }
        if central.isScanning {
            central.stopScan()
        }
        DispatchQueue.main.async { [weak self] in
            self?.isScanning = false
        }
    }

    private func connect(_ peripheral: CBPeripheral) {
        ensureCentralInitialized()
        reconnectWork?.cancel()
        reconnectWork = nil
        connectWork?.cancel()
        connectWork = nil
        activePeripheral = peripheral
        validatedIdentity = false
        authenticatedName = nil
        outputReceiver.reset()
        peripheral.delegate = self
        stopScanIfNeeded()
        updateStatus(.connecting)
        let attempt = transientConnectFailures[peripheral.identifier] ?? 0
        let delay = preferredConnectDelay(failureCount: attempt)
        let work = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self, let peripheral else { return }
            guard self.shouldStayConnected, self.activePeripheral?.identifier == peripheral.identifier else { return }
            self.central?.connect(peripheral, options: nil)
        }
        connectWork = work
        queue.asyncAfter(deadline: .now() + delay, execute: work)
    }

    private func handleIdentityValue(_ value: Data, on peripheral: CBPeripheral) {
        let identity = String(decoding: value, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
        let lowercased = identity.lowercased()
        if lowercased.hasPrefix("device:") {
            let remoteDeviceId = String(identity.dropFirst("device:".count)).trimmingCharacters(in: .whitespacesAndNewlines)
            guard identityMatches(deviceId: remoteDeviceId, shareId: nil) else {
                disconnectAndRetry()
                return
            }
            enableMessageNotifications(on: peripheral)
            return
        }

        if lowercased.hasPrefix("share:") {
            if let bootstrapCharacteristic {
                peripheral.readValue(for: bootstrapCharacteristic)
            } else {
                disconnectAndRetry()
            }
            return
        }

        if let bootstrapCharacteristic {
            peripheral.readValue(for: bootstrapCharacteristic)
        } else {
            disconnectAndRetry()
        }
    }

    private func handleBootstrapValue(_ value: Data, on peripheral: CBPeripheral) {
        guard let identity = parseBootstrapIdentity(from: value) else {
            disconnectAndRetry()
            return
        }
        guard identityMatches(deviceId: identity.deviceId, shareId: identity.shareId) else {
            disconnectAndRetry()
            return
        }
        authenticatedName = identity.displayName
        enableMessageNotifications(on: peripheral)
    }

    private func enableMessageNotifications(on peripheral: CBPeripheral) {
        guard let messageOutCharacteristic else {
            disconnectAndRetry()
            return
        }
        if messageOutCharacteristic.isNotifying {
            validatedIdentity = true
            updateStatus(.ready)
            flushPendingEvents()
            return
        }
        peripheral.setNotifyValue(true, for: messageOutCharacteristic)
    }

    private func identityMatches(deviceId: String?, shareId: String?) -> Bool {
        let expectedDeviceId = currentContact?.remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let expectedShareId = currentContact?.bleShareId?.trimmingCharacters(in: .whitespacesAndNewlines)

        if let expectedDeviceId, !expectedDeviceId.isEmpty {
            guard let deviceId, deviceId.caseInsensitiveCompare(expectedDeviceId) == .orderedSame else {
                return false
            }
        }
        if let expectedShareId, !expectedShareId.isEmpty, let shareId, !shareId.isEmpty {
            if P2pBleProtocol.normalizeShareId(shareId) != P2pBleProtocol.normalizeShareId(expectedShareId) {
                return false
            }
        }
        return true
    }

    private func parseBootstrapIdentity(from data: Data) -> P2pBootstrapIdentity? {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let shareId = (object["shareId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let deviceId = (object["serverDeviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let displayName = (object["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        return P2pBootstrapIdentity(shareId: shareId, deviceId: deviceId, displayName: displayName)
    }

    private func flushPendingEvents() {
        guard isReadyForWrites, let messageInCharacteristic else { return }
        while !pendingEvents.isEmpty {
            let event = pendingEvents.removeFirst()
            guard let data = buildTransportPacket(for: event) else { continue }
            enqueueWrite(data, to: messageInCharacteristic)
        }
    }

    private func buildTransportPacket(for event: P2pPendingEvent) -> Data? {
        guard let contact = currentContact,
              let key = ContactStore.shared.aesKey(for: contact) else {
            return nil
        }

        let innerPayload: [String: Any?]
        switch event {
        case let .text(text, messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindText,
                "messageId": messageId,
                "text": text,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case .read:
            innerPayload = [
                "kind": P2pBleProtocol.chatKindRead,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .delivered(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindDelivered,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .decryptFail(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindDecryptFail,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .voiceInit(messageId, mimeType, durationMillis, totalChunks):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindVoiceInit,
                "messageId": messageId,
                "mimeType": mimeType,
                "durationMillis": durationMillis,
                "totalChunks": totalChunks,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .voiceChunk(messageId, chunkIndex, data):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindVoiceChunk,
                "messageId": messageId,
                "chunkIndex": chunkIndex,
                "chunkData": data.base64EncodedString(),
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .voiceDone(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindVoiceDone,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .voiceAbort(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindVoiceAbort,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .imageInit(messageId, mimeType, width, height, totalBytes, totalChunks, sha256):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindImageInit,
                "messageId": messageId,
                "mimeType": mimeType,
                "width": width,
                "height": height,
                "totalBytes": totalBytes,
                "totalChunks": totalChunks,
                "sha256": sha256,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .imageChunk(messageId, chunkIndex, data):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindImageChunk,
                "messageId": messageId,
                "chunkIndex": chunkIndex,
                "chunkData": data.base64EncodedString(),
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .imageDone(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindImageDone,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .imageAbort(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindImageAbort,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .fileInit(messageId, displayName, mimeType, originalSizeBytes, totalBytes, totalChunks, sha256):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindFileInit,
                "messageId": messageId,
                "displayName": displayName,
                "mimeType": mimeType,
                "originalSizeBytes": originalSizeBytes,
                "totalBytes": totalBytes,
                "totalChunks": totalChunks,
                "sha256": sha256,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .fileChunk(messageId, chunkIndex, data):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindFileChunk,
                "messageId": messageId,
                "chunkIndex": chunkIndex,
                "chunkData": data.base64EncodedString(),
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .fileDone(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindFileDone,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        case let .fileAbort(messageId):
            innerPayload = [
                "kind": P2pBleProtocol.chatKindFileAbort,
                "messageId": messageId,
                "senderName": localDisplayName().nilIfEmpty
            ]
        }

        let innerCompact = innerPayload.compactMapValues { $0 }
        guard let innerData = try? JSONSerialization.data(withJSONObject: innerCompact, options: []),
              let encryptedPacket = BleAesGcm.encryptPacket(
                key: key,
                plaintext: innerData,
                maxPacketSize: p2pMaxEncryptedChatPacketBytes
            ).nilIfEmptyData else {
            return nil
        }

        let outerPayload: [String: Any] = [
            "type": P2pBleProtocol.typeChatEnvelope,
            "fromDeviceId": localDeviceId(),
            "payload": encryptedPacket.base64EncodedString()
        ]
        guard let outerData = try? JSONSerialization.data(withJSONObject: outerPayload, options: []),
              outerData.count <= p2pMaxEnvelopePacketBytes else {
            return nil
        }
        return BleAesGcm.wrapTransportPacket(outerData)
    }

    private func enqueueWrite(_ data: Data, to characteristic: CBCharacteristic) {
        guard let peripheral = activePeripheral else { return }
        let maxLength = max(1, peripheral.maximumWriteValueLength(for: .withResponse))
        var chunks: [Data] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + maxLength, data.count)
            chunks.append(data.subdata(in: offset..<end))
            offset = end
        }
        let request = WriteRequest(characteristic: characteristic, chunks: chunks)
        writeQueue.append(request)
        if activeWrite == nil {
            startNextWrite()
        }
    }

    private func startNextWrite() {
        guard activeWrite == nil else { return }
        activeWrite = writeQueue.isEmpty ? nil : writeQueue.removeFirst()
        writeNextChunk()
    }

    private func writeNextChunk() {
        guard let activeWrite, let peripheral = activePeripheral else { return }
        guard activeWrite.index < activeWrite.chunks.count else {
            self.activeWrite = nil
            startNextWrite()
            return
        }
        let chunk = activeWrite.chunks[activeWrite.index]
        peripheral.writeValue(chunk, for: activeWrite.characteristic, type: .withResponse)
    }

    private func handleWriteResult(success: Bool) {
        guard let activeWrite else { return }
        if !success {
            self.activeWrite = nil
            scheduleReconnect()
            return
        }
        activeWrite.index += 1
        writeNextChunk()
    }

    private func handleMessageOutChunk(_ value: Data) {
        do {
            guard let packet = try outputReceiver.onChunk(value) else { return }
            let envelopeData = try BleAesGcm.unwrapTransportPacket(packet, maxPacketSize: p2pMaxEnvelopePacketBytes)
            if P2pCallProtocol.isCallAudioFrame(envelopeData) {
                // Binary voice-call audio fast path; must never reach the chat envelope parser.
                ChatPeerVoiceCallCoordinator.shared.handleGattCallAudio(envelopeData)
                return
            }
            guard let envelope = parseEnvelope(from: envelopeData),
                  let contact = currentContact,
                  let key = ContactStore.shared.aesKey(for: contact) else {
                return
            }
            guard let payloadData = decryptChatPayload(
                    envelope.encryptedPacket,
                    key: key
                  ),
                  let payload = parsePayload(from: payloadData) else {
                NSLog("[P2pGattChatManager] Failed to decrypt P2P chat payload — sending DECRYPT_FAIL back")
                pendingEvents.append(.decryptFail(nil))
                if isReadyForWrites { flushPendingEvents() }
                return
            }

            if let expectedRemoteDeviceId = contact.remoteDeviceId?.nilIfEmpty,
               envelope.fromDeviceId.caseInsensitiveCompare(expectedRemoteDeviceId) != .orderedSame {
                return
            }

            if P2pCallProtocol.isCallSignalKind(payload.kind) {
                if let rawPayload = (try? JSONSerialization.jsonObject(with: payloadData)) as? [String: Any] {
                    ChatPeerVoiceCallCoordinator.shared.handleGattCallSignal(
                        sessionId: sessionId,
                        payload: rawPayload,
                        link: self
                    )
                }
                return
            }

            let notificationTitle = payload.senderName?.nilIfEmpty
                ?? authenticatedName?.nilIfEmpty
                ?? contact.name
            DispatchQueue.main.async {
                ContactStore.shared.markVerified(
                    sessionId: self.sessionId,
                    verifiedIdentityKey: self.verifiedIdentityKey(envelopeDeviceId: envelope.fromDeviceId)
                )
                if let senderName = payload.senderName?.nilIfEmpty {
                    SOSChatStore.shared.updateIdentity(
                        id: self.sessionId,
                        displayName: senderName,
                        role: .unknown,
                        isVerified: true
                    )
                } else if let authenticatedName = self.authenticatedName?.nilIfEmpty {
                    SOSChatStore.shared.updateIdentity(
                        id: self.sessionId,
                        displayName: authenticatedName,
                        role: .unknown,
                        isVerified: true
                    )
                }
                switch payload.kind {
                case P2pBleProtocol.chatKindDecryptFail:
                    let messageId = payload.messageId?.nilIfEmpty
                    NSLog("[P2pGattChatManager] Remote peer failed to decrypt message \(messageId ?? "(unknown)") — clearing AES key for plaintext fallback")
                    if let contact = self.currentContact {
                        ContactStore.shared.clearAesKey(for: contact)
                        if let messageId {
                            SOSChatStore.shared.requeueMessage(
                                sessionId: self.sessionId,
                                transportMessageId: messageId
                            )
                        }
                    }
                case P2pBleProtocol.chatKindDelivered:
                    SOSChatStore.shared.markDelivered(
                        sessionId: self.sessionId,
                        transportMessageIds: payload.messageId.map { [$0] }
                    )
                case P2pBleProtocol.chatKindRead:
                    SOSChatStore.shared.markRead(
                        sessionId: self.sessionId,
                        transportMessageIds: payload.messageId.map { [$0] }
                    )
                case P2pBleProtocol.chatKindText:
                    if let text = payload.text?.nilIfEmpty {
                        if let locationPayload = P2pSharedTransferSupport.parseLocationMessage(text) {
                            let capturedAt = locationPayload.timestampMillis
                                .map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                                ?? Date()
                            _ = SOSChatStore.shared.upsertRemoteLocationMessage(
                                sessionId: self.sessionId,
                                latitude: locationPayload.latitude,
                                longitude: locationPayload.longitude,
                                horizontalAccuracyMeters: locationPayload.accuracyMeters,
                                capturedAt: capturedAt,
                                transportMessageId: payload.messageId
                            )
                        } else {
                            SOSChatStore.shared.appendRemoteMessage(
                                sessionId: self.sessionId,
                                text: text,
                                transportMessageId: payload.messageId
                            )
                        }
                        SOSNotificationCenter.notifyIncomingMessage(
                            sessionId: self.sessionId,
                            title: notificationTitle,
                            body: P2pSharedTransferSupport.previewText(for: text),
                            kind: .chatMessage
                        )
                    }
                case P2pBleProtocol.chatKindVoiceInit,
                     P2pBleProtocol.chatKindVoiceChunk,
                     P2pBleProtocol.chatKindVoiceDone,
                     P2pBleProtocol.chatKindVoiceAbort:
                    self.handleIncomingVoicePayload(payload)
                case P2pBleProtocol.chatKindImageInit,
                     P2pBleProtocol.chatKindImageChunk,
                     P2pBleProtocol.chatKindImageDone,
                     P2pBleProtocol.chatKindImageAbort:
                    self.handleIncomingImagePayload(payload)
                case P2pBleProtocol.chatKindFileInit,
                     P2pBleProtocol.chatKindFileChunk,
                     P2pBleProtocol.chatKindFileDone,
                     P2pBleProtocol.chatKindFileAbort:
                    self.handleIncomingFilePayload(payload)
                default:
                    break
                }
            }
        } catch {
            outputReceiver.reset()
        }
    }

    private func parseEnvelope(from data: Data) -> (fromDeviceId: String, encryptedPacket: Data)? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = (object["type"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            type == P2pBleProtocol.typeChatEnvelope,
            let fromDeviceId = (object["fromDeviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            let payloadBase64 = (object["payload"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            let encryptedPacket = Data(base64Encoded: payloadBase64, options: [.ignoreUnknownCharacters])
        else {
            return nil
        }
        return (fromDeviceId, encryptedPacket)
    }

    private func parsePayload(from data: Data) -> P2pDecodedChatPayload? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let kind = (object["kind"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        else {
            return nil
        }
        return P2pDecodedChatPayload(
            kind: kind,
            messageId: (object["messageId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            text: (object["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            senderName: (object["senderName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            displayName: (object["displayName"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            mimeType: (object["mimeType"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            durationMillis: object["durationMillis"] as? Int,
            width: object["width"] as? Int,
            height: object["height"] as? Int,
            originalSizeBytes: object["originalSizeBytes"] as? Int,
            totalBytes: object["totalBytes"] as? Int,
            totalChunks: object["totalChunks"] as? Int,
            sha256: (object["sha256"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            chunkIndex: object["chunkIndex"] as? Int,
            chunkData: ((object["chunkData"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines))
                .flatMap { Data(base64Encoded: $0, options: [.ignoreUnknownCharacters]) }
        )
    }

    private func handleIncomingVoicePayload(_ payload: P2pDecodedChatPayload) {
        guard let messageId = payload.messageId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            return
        }

        switch payload.kind {
        case P2pBleProtocol.chatKindVoiceInit:
            guard
                let mimeType = payload.mimeType?.nilIfEmpty,
                let totalChunks = payload.totalChunks,
                let durationMillis = payload.durationMillis,
                totalChunks > 0,
                totalChunks <= P2pBleProtocol.voiceMaxChunks
            else {
                return
            }
            incomingVoiceTransfers[messageId] = P2pIncomingVoiceTransfer(
                messageId: messageId,
                mimeType: mimeType,
                durationMillis: durationMillis,
                totalChunks: totalChunks
            )
        case P2pBleProtocol.chatKindVoiceChunk:
            guard
                var transfer = incomingVoiceTransfers[messageId],
                let chunkIndex = payload.chunkIndex,
                let chunkData = payload.chunkData,
                chunkIndex >= 0,
                chunkIndex < transfer.totalChunks
            else {
                return
            }
            transfer.chunks[chunkIndex] = chunkData
            incomingVoiceTransfers[messageId] = transfer
            completeIncomingVoiceTransferIfReady(messageId: messageId, senderName: payload.senderName)
        case P2pBleProtocol.chatKindVoiceDone:
            guard var transfer = incomingVoiceTransfers[messageId] else { return }
            transfer.receivedDone = true
            incomingVoiceTransfers[messageId] = transfer
            completeIncomingVoiceTransferIfReady(messageId: messageId, senderName: payload.senderName)
        case P2pBleProtocol.chatKindVoiceAbort:
            incomingVoiceTransfers.removeValue(forKey: messageId)
        default:
            break
        }
    }

    private func handleIncomingFilePayload(_ payload: P2pDecodedChatPayload) {
        guard let messageId = payload.messageId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            return
        }

        switch payload.kind {
        case P2pBleProtocol.chatKindFileInit:
            guard
                let displayName = payload.displayName?.nilIfEmpty,
                let originalSizeBytes = payload.originalSizeBytes,
                let totalBytes = payload.totalBytes,
                let totalChunks = payload.totalChunks,
                let sha256 = payload.sha256?.nilIfEmpty,
                originalSizeBytes > 0,
                totalBytes > 0,
                totalBytes <= P2pBleProtocol.fileMaxTotalBytes,
                totalChunks > 0,
                totalChunks <= P2pBleProtocol.fileMaxChunks
            else {
                return
            }
            incomingFileTransfers[messageId] = P2pIncomingFileTransfer(
                messageId: messageId,
                displayName: displayName,
                mimeType: payload.mimeType?.nilIfEmpty,
                originalSizeBytes: originalSizeBytes,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256
            )
        case P2pBleProtocol.chatKindFileChunk:
            guard
                var transfer = incomingFileTransfers[messageId],
                let chunkIndex = payload.chunkIndex,
                let chunkData = payload.chunkData,
                chunkIndex >= 0,
                chunkIndex < transfer.totalChunks
            else {
                return
            }
            transfer.chunks[chunkIndex] = chunkData
            incomingFileTransfers[messageId] = transfer
            completeIncomingFileTransferIfReady(messageId: messageId)
        case P2pBleProtocol.chatKindFileDone:
            guard var transfer = incomingFileTransfers[messageId] else { return }
            transfer.receivedDone = true
            incomingFileTransfers[messageId] = transfer
            completeIncomingFileTransferIfReady(messageId: messageId)
        case P2pBleProtocol.chatKindFileAbort:
            incomingFileTransfers.removeValue(forKey: messageId)
        default:
            break
        }
    }

    private func completeIncomingVoiceTransferIfReady(messageId: String, senderName: String?) {
        guard let transfer = incomingVoiceTransfers[messageId],
              let audioData = transfer.composedData(),
              let relativePath = SOSChatStore.persistVoiceData(
                audioData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
              )
        else {
            return
        }
        incomingVoiceTransfers.removeValue(forKey: messageId)
        pendingEvents.append(.delivered(messageId))
        if isReadyForWrites {
            flushPendingEvents()
        } else {
            beginScanIfPossible()
        }
        DispatchQueue.main.async {
            ContactStore.shared.markVerified(
                sessionId: self.sessionId,
                verifiedIdentityKey: self.verifiedIdentityKey()
            )
            if let senderName = senderName?.nilIfEmpty {
                SOSChatStore.shared.updateIdentity(
                    id: self.sessionId,
                    displayName: senderName,
                    role: .unknown,
                    isVerified: true
                )
            }
            _ = SOSChatStore.shared.appendRemoteAudioMessage(
                sessionId: self.sessionId,
                audioRelativePath: relativePath,
                durationMillis: transfer.durationMillis,
                transportMessageId: messageId
            )
            let notificationTitle = senderName?.nilIfEmpty
                ?? self.authenticatedName?.nilIfEmpty
                ?? self.currentContact?.name
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: self.sessionId,
                title: notificationTitle,
                body: NSLocalizedString("Voice message", comment: ""),
                kind: .chatMessage
            )
        }
    }

    private func handleIncomingImagePayload(_ payload: P2pDecodedChatPayload) {
        guard let messageId = payload.messageId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            return
        }

        switch payload.kind {
        case P2pBleProtocol.chatKindImageInit:
            guard
                let mimeType = payload.mimeType?.nilIfEmpty,
                let width = payload.width,
                let height = payload.height,
                let totalBytes = payload.totalBytes,
                let totalChunks = payload.totalChunks,
                let sha256 = payload.sha256?.nilIfEmpty,
                width > 0,
                height > 0,
                totalBytes > 0,
                totalBytes <= P2pBleProtocol.imageMaxTotalBytes,
                totalChunks > 0,
                totalChunks <= P2pBleProtocol.imageMaxChunks
            else {
                return
            }
            incomingImageTransfers[messageId] = P2pIncomingImageTransfer(
                messageId: messageId,
                mimeType: mimeType,
                width: width,
                height: height,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256
            )
        case P2pBleProtocol.chatKindImageChunk:
            guard
                var transfer = incomingImageTransfers[messageId],
                let chunkIndex = payload.chunkIndex,
                let chunkData = payload.chunkData,
                chunkIndex >= 0,
                chunkIndex < transfer.totalChunks
            else {
                return
            }
            transfer.chunks[chunkIndex] = chunkData
            incomingImageTransfers[messageId] = transfer
            completeIncomingImageTransferIfReady(messageId: messageId, senderName: payload.senderName)
        case P2pBleProtocol.chatKindImageDone:
            guard var transfer = incomingImageTransfers[messageId] else { return }
            transfer.receivedDone = true
            incomingImageTransfers[messageId] = transfer
            completeIncomingImageTransferIfReady(messageId: messageId, senderName: payload.senderName)
        case P2pBleProtocol.chatKindImageAbort:
            incomingImageTransfers.removeValue(forKey: messageId)
        default:
            break
        }
    }

    private func completeIncomingImageTransferIfReady(messageId: String, senderName: String?) {
        guard let transfer = incomingImageTransfers[messageId],
              let imageData = transfer.composedData(),
              let imageRelativePath = SOSChatStore.persistImageData(
                imageData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
              )
        else {
            return
        }
        incomingImageTransfers.removeValue(forKey: messageId)
        let thumbnailRelativePath = makeThumbnailRelativePath(
            imageData: imageData,
            messageId: transfer.messageId
        )
        pendingEvents.append(.delivered(messageId))
        if isReadyForWrites {
            flushPendingEvents()
        } else {
            beginScanIfPossible()
        }
        DispatchQueue.main.async {
            ContactStore.shared.markVerified(
                sessionId: self.sessionId,
                verifiedIdentityKey: self.verifiedIdentityKey()
            )
            if let senderName = senderName?.nilIfEmpty {
                SOSChatStore.shared.updateIdentity(
                    id: self.sessionId,
                    displayName: senderName,
                    role: .unknown,
                    isVerified: true
                )
            }
            _ = SOSChatStore.shared.appendRemoteImageMessage(
                sessionId: self.sessionId,
                imageRelativePath: imageRelativePath,
                thumbnailRelativePath: thumbnailRelativePath,
                imageWidth: transfer.width,
                imageHeight: transfer.height,
                imageMimeType: transfer.mimeType,
                transportMessageId: messageId
            )
            let notificationTitle = senderName?.nilIfEmpty
                ?? self.authenticatedName?.nilIfEmpty
                ?? self.currentContact?.name
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: self.sessionId,
                title: notificationTitle,
                body: SOSChatStore.imagePreviewText(),
                kind: .chatMessage
            )
        }
    }

    private func completeIncomingFileTransferIfReady(messageId: String) {
        guard let transfer = incomingFileTransfers[messageId],
              let fileData = transfer.composedData() else { return }
        if transfer.mimeType == ChannelAttachments.authorityMlsEnvelopeMime {
            guard let encoded = String(data: fileData, encoding: .utf8),
                  AuthorityMlsOfflineEnvelopeCodec.decode(encoded) != nil else { return }
            _ = SOSChatStore.shared.appendRemoteMessage(
                sessionId: sessionId,
                text: encoded,
                transportMessageId: transfer.messageId
            )
            incomingFileTransfers.removeValue(forKey: messageId)
            return
        }
        if transfer.mimeType == ChannelAttachments.authorityMlsBlobMime {
            guard ChannelAttachments.cacheAuthorityMlsCiphertext(
                path: transfer.displayName,
                cipher: fileData
            ) else { return }
            incomingFileTransfers.removeValue(forKey: messageId)
            return
        }
        guard P2pSharedTransferSupport.persistSharedDocumentData(
                fileData,
                messageId: transfer.messageId,
                displayName: transfer.displayName
              ) != nil else { return }
        incomingFileTransfers.removeValue(forKey: messageId)
    }

    private func makeThumbnailRelativePath(imageData: Data, messageId: String) -> String? {
        guard let image = UIImage(data: imageData)?.normalizedOrientationImage(),
              let thumbnailData = image.thumbnailJPEGData(side: 512, compressionQuality: 0.78) else {
            return nil
        }
        return SOSChatStore.persistImageThumbnailData(
            thumbnailData,
            messageId: messageId,
            mimeType: ChatImageTransfer.jpegMimeType
        )
    }

    private func decryptChatPayload(_ encryptedPacket: Data, key: SymmetricKey) -> Data? {
        if let sealedBox = try? AES.GCM.SealedBox(combined: encryptedPacket),
           let plaintext = try? AES.GCM.open(sealedBox, using: key) {
            return plaintext
        }
        return try? BleAesGcm.decryptPacket(
            key: key,
            transportPacket: encryptedPacket,
            maxPacketSize: p2pMaxEncryptedChatPacketBytes
        )
    }

    private func disconnectAndRetry() {
        outputReceiver.reset()
        if let peripheral = activePeripheral, let central {
            central.cancelPeripheralConnection(peripheral)
        } else {
            scheduleReconnect()
        }
    }

    private func scheduleReconnect() {
        guard shouldStayConnected else {
            updateStatus(.disconnected)
            return
        }
        reconnectWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.beginScanIfPossible()
        }
        reconnectWork = work
        resetConnectionState(clearPeripheral: true)
        updateStatus(.disconnected)
        queue.asyncAfter(deadline: .now() + 1.0, execute: work)
    }

    private func handleConnectFailure(for peripheral: CBPeripheral, error: Error?) {
        let attempt = (transientConnectFailures[peripheral.identifier] ?? 0) + 1
        transientConnectFailures[peripheral.identifier] = attempt
        resetConnectionState(clearPeripheral: true)
        if attempt <= p2pMaxTransientConnectFailures {
            let retryDelay = preferredConnectDelay(failureCount: attempt)
            updateStatus(.disconnected)
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.shouldStayConnected else { return }
                self.connect(peripheral)
            }
            reconnectWork?.cancel()
            reconnectWork = work
            queue.asyncAfter(deadline: .now() + retryDelay, execute: work)
            return
        }
        transientConnectFailures.removeValue(forKey: peripheral.identifier)
        scheduleReconnect()
    }

    private func preferredConnectDelay(failureCount: Int) -> TimeInterval {
        let localId = localDeviceId().trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let remoteId = currentContact?.remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let base = if let localId, let remoteId, localId.caseInsensitiveCompare(remoteId) != .orderedDescending {
            p2pConnectFastDelay
        } else {
            p2pConnectSlowDelay
        }
        return base + (Double(max(0, failureCount)) * p2pTransientRetryStep)
    }

    private func resetConnectionState(clearPeripheral: Bool) {
        stopScanIfNeeded()
        connectWork?.cancel()
        connectWork = nil
        idCharacteristic = nil
        bootstrapCharacteristic = nil
        messageInCharacteristic = nil
        messageOutCharacteristic = nil
        validatedIdentity = false
        authenticatedName = nil
        outputReceiver.reset()
        writeQueue.removeAll()
        activeWrite = nil
        if clearPeripheral {
            activePeripheral = nil
        }
    }

    private func scheduleDelayedStop() {
        disconnectWork?.cancel()
        disconnectWork = nil
        if callHoldActive {
            // An active voice call owns the link; the call coordinator releases the hold.
            return
        }
        let hasActiveWork = shouldStayConnected ||
            activePeripheral != nil ||
            (central?.isScanning ?? false) ||
            connectWork != nil ||
            reconnectWork != nil ||
            !pendingEvents.isEmpty ||
            !writeQueue.isEmpty ||
            activeWrite != nil
        guard hasActiveWork else {
            performStopNow(removeFromRegistry: true)
            return
        }
        let work = DispatchWorkItem { [weak self] in
            self?.performStopNow(removeFromRegistry: true)
        }
        disconnectWork = work
        queue.asyncAfter(deadline: .now() + p2pDisconnectGrace, execute: work)
    }

    private func performStopNow(removeFromRegistry: Bool) {
        shouldStayConnected = false
        disconnectWork?.cancel()
        disconnectWork = nil
        reconnectWork?.cancel()
        reconnectWork = nil
        connectWork?.cancel()
        connectWork = nil
        pendingEvents.removeAll()
        writeQueue.removeAll()
        activeWrite = nil
        outputReceiver.reset()
        incomingVoiceTransfers.removeAll()
        incomingImageTransfers.removeAll()
        incomingFileTransfers.removeAll()
        transientConnectFailures.removeAll()
        stopScanIfNeeded()
        if let peripheral = activePeripheral, let central {
            central.cancelPeripheralConnection(peripheral)
        }
        resetConnectionState(clearPeripheral: true)
        updateStatus(.disconnected)
        if removeFromRegistry {
            Self.removeShared(sessionId: sessionId, instance: self)
        }
    }

    private func updateStatus(_ next: RescueConnectionStatus) {
        DispatchQueue.main.async {
            self.status = next
        }
    }

    private func localDeviceId() -> String {
        SecureLocalStore.shared.getOrCreateP2pDeviceId()
    }

    private func localDisplayName() -> String {
        ProfileMetadataStore.preferredDisplayName() ?? "Crisis Connect"
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync { block() }
    }
}

private extension Data {
    var nilIfEmptyData: Data? {
        isEmpty ? nil : self
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
