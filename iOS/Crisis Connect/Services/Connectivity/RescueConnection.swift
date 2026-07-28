//
//  RescueConnection.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import Foundation
import CoreBluetooth
import CryptoKit
import UIKit

enum RescueConnectionStatus: String {
    case connecting
    case connected
    case discovering
    case authenticating
    case ready
    case disconnected
    case failed
}

final class RescueConnection: NSObject, CBPeripheralDelegate {
    private struct LiveLocationPayload {
        let messageId: String?
        let latitude: Double
        let longitude: Double
        let horizontalAccuracyMeters: Double?
        let capturedAt: Date
    }

    let peripheral: CBPeripheral
    let sessionId: UUID

    var broadcastId: String?
    private(set) var status: RescueConnectionStatus = .disconnected
    var onStatusChange: ((RescueConnectionStatus) -> Void)?
    var onBroadcastId: ((String) -> Void)?
    var onPeerName: ((String) -> Void)?
    var onPeerBattery: ((Int) -> Void)?
    var onPeerMedical: ((RescueVictimMedical) -> Void)?
    var onSignalLocation: ((SOSSignalLocationPayload?) -> Void)?

    private let queue: DispatchQueue
    private let roleProofCreator = RoleProofCreator(allowExpiredCertificate: false)
    private let chunkReceiver = BleChunkReceiver(maxPacketSize: 32_767)

    private var central: CBCentralManager?
    private var sessionKey: SymmetricKey?
    private let preSharedKey: SymmetricKey?
    private var clientKey: P256.KeyAgreement.PrivateKey?
    private var clientPublicKeyData: Data?
    private var awaitingAuthResponse = false
    private var awaitingHandshakeAck = false
    private var authTimeoutWork: DispatchWorkItem?
    private var ackTimeoutWork: DispatchWorkItem?
    private var incomingVoiceTransfers: [String: BleVoicePayload.IncomingTransfer] = [:]
    private var incomingImageTransfers: [String: BleImagePayload.IncomingTransfer] = [:]
    private var incomingFileTransfers: [String: BleFilePayload.IncomingTransfer] = [:]

    private var idCharacteristic: CBCharacteristic?
    private var authChallengeCharacteristic: CBCharacteristic?
    private var authResponseCharacteristic: CBCharacteristic?
    private var secureInCharacteristic: CBCharacteristic?
    private var secureAckCharacteristic: CBCharacteristic?
    private var secureChatInCharacteristic: CBCharacteristic?
    private var secureChatOutCharacteristic: CBCharacteristic?
    private var callIoInCharacteristic: CBCharacteristic?
    private var callIoOutCharacteristic: CBCharacteristic?

    private let serviceUUID = CBUUID(string: "0000CC00-0000-1000-8000-00805F9B34FB")
    private let idUUID = CBUUID(string: "0000CC01-0000-1000-8000-00805F9B34FB")
    private let authChallengeUUID = CBUUID(string: "0000CC10-0000-1000-8000-00805F9B34FB")
    private let authResponseUUID = CBUUID(string: "0000CC11-0000-1000-8000-00805F9B34FB")
    private let secureInUUID = CBUUID(string: "0000CC20-0000-1000-8000-00805F9B34FB")
    private let secureAckUUID = CBUUID(string: "0000CC21-0000-1000-8000-00805F9B34FB")
    private let secureChatInUUID = CBUUID(string: "0000CC30-0000-1000-8000-00805F9B34FB")
    private let secureChatOutUUID = CBUUID(string: "0000CC31-0000-1000-8000-00805F9B34FB")
    private let callIoInUUID = CBUUID(string: "0000CC40-0000-1000-8000-00805F9B34FB")
    private let callIoOutUUID = CBUUID(string: "0000CC41-0000-1000-8000-00805F9B34FB")

    private let handshakeAckValue = Data("OK".utf8)
    private let deliveredAckValue = Data("DELIVERED".utf8)
    private let readAckValue = Data("READ".utf8)
    private let peerInfoAckValue = Data("PEER_INFO_ACK".utf8)
    private let pskChallengeValue = Data("psk:v1".utf8)
    private let pskResponseValue = Data("psk:ok".utf8)

    private let maxRoleProofPacketBytes = 16_384
    private let maxSecurePacketBytes = 32_767
    private let textMessageTtlMillis: Int64 = 86_400_000
    private let outboundRouteBleGattFallback = "ble_gatt_fallback"

    private final class WriteRequest {
        let characteristic: CBCharacteristic
        let chunks: [Data]
        var index: Int = 0
        let completion: (Bool) -> Void

        init(characteristic: CBCharacteristic, chunks: [Data], completion: @escaping (Bool) -> Void) {
            self.characteristic = characteristic
            self.chunks = chunks
            self.completion = completion
        }
    }

    private var writeQueue: [WriteRequest] = []
    private var activeWrite: WriteRequest?

    init(
        peripheral: CBPeripheral,
        sessionId: UUID,
        broadcastId: String?,
        preSharedKey: SymmetricKey?,
        queue: DispatchQueue
    ) {
        self.peripheral = peripheral
        self.sessionId = sessionId
        self.broadcastId = broadcastId
        self.preSharedKey = preSharedKey
        self.queue = queue
        super.init()
    }

    func connect(using central: CBCentralManager) {
        self.central = central
        switch status {
        case .connecting, .connected, .discovering, .authenticating, .ready:
            return
        case .disconnected, .failed:
            break
        }
        updateStatus(.connecting)
        central.connect(peripheral, options: nil)
    }

    func disconnect() {
        central?.cancelPeripheralConnection(peripheral)
    }

    func didConnect() {
        peripheral.delegate = self
        updateStatus(.connected)
        peripheral.discoverServices([serviceUUID])
    }

    func didDisconnect(error: Error?) {
        let shouldPreserveFailure = status == .failed || error != nil
        resetSession()
        updateStatus(shouldPreserveFailure ? .failed : .disconnected)
    }

    func sendMessage(_ text: String, transportMessageId: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return false
        }
        let payloadString = BleChatEnvelope.encodeChat(
            messageId: transportMessageId,
            text: trimmed,
            createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
            ttlMillis: textMessageTtlMillis,
            attempt: 1,
            route: outboundRouteBleGattFallback
        )
        let payload = Data(payloadString.utf8)
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: payload,
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return false }
        enqueueWrite(encrypted, to: characteristic) { _ in }
        return true
    }

    func sendMessage(_ text: String) -> Bool {
        sendMessage(text, transportMessageId: "ios-\(UUID().uuidString.lowercased())")
    }

    func sendImageMessage(
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String
    ) -> Bool {
        guard let key = sessionKey,
              let characteristic = secureChatInCharacteristic,
              let imageData = SOSChatStore.loadImageData(fileName: imageFileName),
              !imageData.isEmpty else {
            return false
        }
        let transferId = UUID().uuidString.lowercased()
        let packets = BleImagePayload.buildPackets(
            transferId: transferId,
            messageId: messageId,
            mimeType: mimeType,
            width: width,
            height: height,
            bytes: imageData
        )
        guard !packets.isEmpty else { return false }
        let encryptedPackets = packets.compactMap { packet -> Data? in
            let plaintext = Data(packet.utf8)
            let encrypted = BleAesGcm.encryptPacket(
                key: key,
                plaintext: plaintext,
                maxPacketSize: maxSecurePacketBytes
            )
            return encrypted.isEmpty ? nil : encrypted
        }
        guard encryptedPackets.count == packets.count else { return false }
        encryptedPackets.forEach { packet in
            enqueueWrite(packet, to: characteristic) { _ in }
        }
        return true
    }

    func sendVoiceMessage(
        audioFileName: String,
        mimeType: String,
        durationMillis: Int,
        messageId: String
    ) -> Bool {
        guard let key = sessionKey,
              let characteristic = secureChatInCharacteristic,
              let audioData = SOSChatStore.loadVoiceData(fileName: audioFileName),
              !audioData.isEmpty else {
            return false
        }
        let transferId = UUID().uuidString.lowercased()
        let packets = BleVoicePayload.buildPackets(
            transferId: transferId,
            messageId: messageId,
            mimeType: mimeType,
            durationMillis: durationMillis,
            bytes: audioData
        )
        guard !packets.isEmpty else { return false }
        let encryptedPackets = packets.compactMap { packet -> Data? in
            let plaintext = Data(packet.utf8)
            let encrypted = BleAesGcm.encryptPacket(
                key: key,
                plaintext: plaintext,
                maxPacketSize: maxSecurePacketBytes
            )
            return encrypted.isEmpty ? nil : encrypted
        }
        guard encryptedPackets.count == packets.count else { return false }
        encryptedPackets.forEach { packet in
            enqueueWrite(packet, to: characteristic) { _ in }
        }
        return true
    }

    func sendFileMessage(
        data: Data,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        messageId: String
    ) -> Bool {
        guard let key = sessionKey,
              let characteristic = secureChatInCharacteristic,
              !data.isEmpty else {
            return false
        }
        let transferId = UUID().uuidString.lowercased()
        let packets = BleFilePayload.buildPackets(
            transferId: transferId,
            messageId: messageId,
            displayName: displayName,
            mimeType: mimeType,
            originalSizeBytes: max(1, originalSizeBytes),
            bytes: data
        )
        guard !packets.isEmpty else { return false }
        let encryptedPackets = packets.compactMap { packet -> Data? in
            let plaintext = Data(packet.utf8)
            let encrypted = BleAesGcm.encryptPacket(
                key: key,
                plaintext: plaintext,
                maxPacketSize: maxSecurePacketBytes
            )
            return encrypted.isEmpty ? nil : encrypted
        }
        guard encryptedPackets.count == packets.count else { return false }
        encryptedPackets.forEach { packet in
            enqueueWrite(packet, to: characteristic) { _ in }
        }
        return true
    }

    func sendReadReceipt(transportMessageIds: [String]) -> Bool {
        guard let characteristic = secureAckCharacteristic else { return false }
        let payload = BleChatEnvelope.encodeReadAck(messageIds: transportMessageIds)
        enqueueWrite(payload, to: characteristic) { _ in }
        return true
    }

    func sendReadReceipt() -> Bool {
        sendReadReceipt(transportMessageIds: [])
    }

    func sendLiveLocation(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        messageId: String
    ) -> Bool {
        guard let key = sessionKey,
              let secureChatInCharacteristic else {
            return false
        }

        var payload: [String: Any] = [
            "kind": "live_location",
            "messageId": messageId,
            "latitude": latitude,
            "longitude": longitude,
            "timestampMillis": Int64(capturedAt.timeIntervalSince1970 * 1000)
        ]
        if let horizontalAccuracyMeters {
            payload["accuracyMeters"] = horizontalAccuracyMeters
        }
        guard let plaintext = try? JSONSerialization.data(withJSONObject: payload, options: []) else {
            return false
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: plaintext,
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return false }
        enqueueWrite(encrypted, to: secureChatInCharacteristic) { _ in }
        return true
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            updateStatus(.failed)
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == serviceUUID }) else {
            updateStatus(.failed)
            return
        }
        updateStatus(.discovering)
        let characteristicUUIDs = [
            idUUID, authChallengeUUID, authResponseUUID, secureInUUID,
            secureAckUUID, secureChatInUUID, secureChatOutUUID,
            callIoInUUID, callIoOutUUID
        ]
        peripheral.discoverCharacteristics(characteristicUUIDs, for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            updateStatus(.failed)
            return
        }
        guard let characteristics = service.characteristics else {
            updateStatus(.failed)
            return
        }
        for characteristic in characteristics {
            switch characteristic.uuid {
            case idUUID:
                idCharacteristic = characteristic
            case authChallengeUUID:
                authChallengeCharacteristic = characteristic
            case authResponseUUID:
                authResponseCharacteristic = characteristic
            case secureInUUID:
                secureInCharacteristic = characteristic
            case secureAckUUID:
                secureAckCharacteristic = characteristic
            case secureChatInUUID:
                secureChatInCharacteristic = characteristic
            case secureChatOutUUID:
                secureChatOutCharacteristic = characteristic
            case callIoInUUID:
                // Optional call-IO pair — only newer victims expose it; absence just disables
                // live voice calls, never the connection.
                callIoInCharacteristic = characteristic
            case callIoOutUUID:
                callIoOutCharacteristic = characteristic
            default:
                break
            }
        }

        guard idCharacteristic != nil,
              authChallengeCharacteristic != nil,
              authResponseCharacteristic != nil,
              secureInCharacteristic != nil,
              secureAckCharacteristic != nil,
              secureChatInCharacteristic != nil,
              secureChatOutCharacteristic != nil else {
            updateStatus(.failed)
            return
        }

        if let authResponseCharacteristic {
            peripheral.setNotifyValue(true, for: authResponseCharacteristic)
        }
        if let secureAckCharacteristic {
            peripheral.setNotifyValue(true, for: secureAckCharacteristic)
        }
        if let secureChatOutCharacteristic {
            peripheral.setNotifyValue(true, for: secureChatOutCharacteristic)
        }
        if let callIoOutCharacteristic {
            peripheral.setNotifyValue(true, for: callIoOutCharacteristic)
        }

        if let idCharacteristic {
            peripheral.readValue(for: idCharacteristic)
        }

        beginHandshake()
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        switch characteristic.uuid {
        case idUUID:
            handleBroadcastId(value)
        case authResponseUUID:
            handleAuthResponse(value)
        case secureAckUUID:
            handleAck(value)
        case secureChatOutUUID:
            handleChatChunk(value)
        case callIoOutUUID:
            RescueCallEngine.shared.onInboundPacket(sessionId: sessionId, packet: value)
        default:
            break
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        queue.async { [weak self] in
            self?.handleWriteResult(success: error == nil)
        }
    }

    private func beginHandshake() {
        guard let authChallengeCharacteristic else {
            updateStatus(.failed)
            return
        }
        updateStatus(.authenticating)
        if let preSharedKey {
            sessionKey = preSharedKey
            enqueueWrite(pskChallengeValue, to: authChallengeCharacteristic) { [weak self] success in
                guard let self else { return }
                if !success {
                    self.updateStatus(.failed)
                    return
                }
                self.awaitingAuthResponse = true
                self.scheduleAuthTimeout()
                if let authResponseCharacteristic = self.authResponseCharacteristic {
                    self.peripheral.readValue(for: authResponseCharacteristic)
                }
            }
            return
        }
        clientKey = P256.KeyAgreement.PrivateKey()
        guard let clientKey else {
            updateStatus(.failed)
            return
        }
        let publicData = makeX509PublicKeyData(from: clientKey.publicKey)
        clientPublicKeyData = publicData
        enqueueWrite(publicData, to: authChallengeCharacteristic) { [weak self] success in
            guard let self else { return }
            if !success {
                self.updateStatus(.failed)
                return
            }
            self.awaitingAuthResponse = true
            self.scheduleAuthTimeout()
            if let authResponseCharacteristic = self.authResponseCharacteristic {
                self.peripheral.readValue(for: authResponseCharacteristic)
            }
        }
    }

    private func handleAuthResponse(_ value: Data) {
        guard awaitingAuthResponse else { return }
        awaitingAuthResponse = false
        authTimeoutWork?.cancel()
        if preSharedKey != nil {
            if value == pskResponseValue {
                sendPskHello()
            } else {
                updateStatus(.failed)
            }
            return
        }
        guard let clientKey else { return }
        do {
            let x963 = try BleKeyDecoder.x963PublicKey(from: value)
            let serverKey = try P256.KeyAgreement.PublicKey(x963Representation: x963)
            let sharedSecret = try clientKey.sharedSecretFromKeyAgreement(with: serverKey)
            let derived = sharedSecret.hkdfDerivedSymmetricKey(
                using: SHA256.self,
                salt: Data(repeating: 0, count: 32),
                sharedInfo: Data(),
                outputByteCount: 32
            )
            sessionKey = derived
            let sessionNonce = clientPublicKeyData.map {
                buildSessionNonce(clientPublicKey: $0, serverPublicKey: value)
            }
            sendRoleProof(sessionNonce: sessionNonce)
        } catch {
            updateStatus(.failed)
        }
    }

    private func sendRoleProof(sessionNonce: String?) {
        guard let key = sessionKey,
              let secureInCharacteristic else {
            updateStatus(.failed)
            return
        }
        Task { [weak self] in
            guard let self else { return }
            do {
                let packet = try await roleProofCreator.createEncryptedPacket(
                    sessionKey: key,
                    maxPacketSize: maxRoleProofPacketBytes,
                    sessionNonce: sessionNonce
                )
                guard !packet.isEmpty else {
                    self.updateStatus(.failed)
                    return
                }
                self.enqueueWrite(packet, to: secureInCharacteristic) { success in
                    if !success {
                        self.updateStatus(.failed)
                        return
                    }
                    self.awaitingHandshakeAck = true
                    self.scheduleAckTimeout()
                }
            } catch {
                self.updateStatus(.failed)
            }
        }
    }

    private func sendPskHello() {
        guard let key = sessionKey,
              let secureInCharacteristic else {
            updateStatus(.failed)
            return
        }
        let name = localDisplayName()
        let payload = ContactHandshakePayload.make(
            name: name,
            broadcastId: SecureLocalStore.shared.getOrCreateBroadcastId()
        )
        guard let data = try? JSONEncoder().encode(payload) else {
            updateStatus(.failed)
            return
        }
        let packet = BleAesGcm.encryptPacket(
            key: key,
            plaintext: data,
            maxPacketSize: maxRoleProofPacketBytes
        )
        guard !packet.isEmpty else {
            updateStatus(.failed)
            return
        }
        enqueueWrite(packet, to: secureInCharacteristic) { [weak self] success in
            if !success {
                self?.updateStatus(.failed)
                return
            }
            self?.awaitingHandshakeAck = true
            self?.scheduleAckTimeout()
        }
    }

    /// Registers this connection as the call transport for its chat session (idempotent).
    private func registerCallTransport() {
        guard let key = sessionKey, callIoInCharacteristic != nil else { return }
        RescueCallEngine.shared.registerTransport(
            RescueCallEngine.Transport(
                sessionId: sessionId,
                sendPacket: { [weak self] packet in
                    guard let self, let characteristic = self.callIoInCharacteristic else {
                        return false
                    }
                    // Best-effort single write; the offer loop retries signaling and the jitter
                    // buffer conceals a lost audio packet (telsiz lesson: don't gate on
                    // canSendWriteWithoutResponse, it flaps between connection events).
                    self.peripheral.writeValue(
                        packet,
                        for: characteristic,
                        type: .withoutResponse
                    )
                    return true
                },
                sessionKey: { key },
                // Peer name resolution stays in the UI layer (SOSChatStore is main-bound and this
                // closure runs on BLE/audio threads); the overlay shows the session name itself.
                peerName: { nil }
            )
        )
    }

    private func handleAck(_ value: Data) {
        if awaitingHandshakeAck, value == handshakeAckValue {
            awaitingHandshakeAck = false
            ackTimeoutWork?.cancel()
            updateStatus(.ready)
            registerCallTransport()
            let resolvedRole: SOSChatRole = preSharedKey == nil ? .victim : .unknown
            DispatchQueue.main.async {
                let stableIdentity = ContactStore.shared.contact(for: self.sessionId)?.verifiedIdentityKey
                    ?? ContactStore.shared.contact(for: self.sessionId)?.remoteDeviceId
                ContactStore.shared.markVerified(
                    sessionId: self.sessionId,
                    verifiedIdentityKey: stableIdentity
                )
                SOSChatStore.shared.ensureSession(id: self.sessionId, role: resolvedRole, isVerified: true)
            }
            sendPeerIdentity()
            return
        }
        if value == peerInfoAckValue {
            return
        }
        if let ack = BleChatEnvelope.decodeAck(value) {
            DispatchQueue.main.async {
                switch ack.type {
                case .delivered:
                    SOSChatStore.shared.markDelivered(
                        sessionId: self.sessionId,
                        transportMessageIds: ack.messageIds.isEmpty ? nil : ack.messageIds
                    )
                case .read:
                    SOSChatStore.shared.markRead(
                        sessionId: self.sessionId,
                        transportMessageIds: ack.messageIds.isEmpty ? nil : ack.messageIds
                    )
                case .decryptFail:
                    break
                }
            }
            return
        }
        if value == deliveredAckValue {
            DispatchQueue.main.async {
                SOSChatStore.shared.markDelivered(sessionId: self.sessionId)
            }
        } else if value == readAckValue {
            DispatchQueue.main.async {
                SOSChatStore.shared.markRead(sessionId: self.sessionId)
            }
        }
    }

    private func handleChatChunk(_ value: Data) {
        guard let key = sessionKey else { return }
        do {
            if let packet = try chunkReceiver.onChunk(value) {
                let plaintext = try BleAesGcm.decryptPacket(
                    key: key,
                    transportPacket: packet,
                    maxPacketSize: maxSecurePacketBytes
                )
                let text = String(data: plaintext, encoding: .utf8) ?? ""
                let cleaned = text.trimmingCharacters(in: CharacterSet(charactersIn: "\0").union(.whitespacesAndNewlines))
                if let identity = parsePeerInfoPayload(cleaned) {
                    applyPeerIdentity(identity)
                    onSignalLocation?(identity.signalLocation)
                    sendAck(peerInfoAckValue)
                } else if let voicePacket = BleVoicePayload.parsePacket(cleaned) {
                    handleVoicePacket(voicePacket)
                } else if let imagePacket = BleImagePayload.parsePacket(cleaned) {
                    handleImagePacket(imagePacket)
                } else if let filePacket = BleFilePayload.parsePacket(cleaned) {
                    handleFilePacket(filePacket)
                } else if let liveLocation = parseLiveLocationPayload(cleaned) {
                    let appended = upsertRemoteLocationMessageOnMain(
                        latitude: liveLocation.latitude,
                        longitude: liveLocation.longitude,
                        horizontalAccuracyMeters: liveLocation.horizontalAccuracyMeters,
                        capturedAt: liveLocation.capturedAt,
                        transportMessageId: liveLocation.messageId
                    )
                    sendAck(BleChatEnvelope.encodeDeliveredAck(messageId: liveLocation.messageId))
                    if !appended {
                        return
                    }
                } else if let envelope = BleChatEnvelope.decodeChat(cleaned), !BleChatEnvelope.isExpired(envelope) {
                    let appended = appendRemoteMessageOnMain(
                        text: envelope.text,
                        transportMessageId: envelope.messageId
                    )
                    sendAck(BleChatEnvelope.encodeDeliveredAck(messageId: envelope.messageId))
                    if !appended {
                        return
                    }
                } else if !cleaned.isEmpty {
                    DispatchQueue.main.async {
                        SOSChatStore.shared.appendRemoteMessage(sessionId: self.sessionId, text: cleaned)
                    }
                    sendAck(deliveredAckValue)
                }
            }
        } catch {
            chunkReceiver.reset()
        }
    }

    private func handleImagePacket(_ packet: BleImagePayload.Packet) {
        cleanupStaleImageTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingImageTransfers[payload.transferId] = BleImagePayload.IncomingTransfer(
                transferId: payload.transferId,
                messageId: payload.messageId,
                mimeType: payload.mimeType,
                width: payload.width,
                height: payload.height,
                totalBytes: payload.totalBytes,
                totalChunks: payload.totalChunks,
                sha256: payload.sha256
            )
        case .chunk(let payload):
            guard var transfer = incomingImageTransfers[payload.transferId] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingImageTransfers.removeValue(forKey: payload.transferId)
                sendImageAbortPacket(transferId: payload.transferId, reason: "invalid_chunk")
                return
            }
            incomingImageTransfers[payload.transferId] = transfer
            guard transfer.isComplete,
                  let imageData = transfer.composedData(),
                  imageData.count <= BleImagePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: imageData))
            guard digest == transfer.sha256 else {
                incomingImageTransfers.removeValue(forKey: payload.transferId)
                sendImageAbortPacket(transferId: payload.transferId, reason: "sha_mismatch")
                return
            }
            incomingImageTransfers.removeValue(forKey: payload.transferId)
            guard let imageRelativePath = SOSChatStore.persistImageData(
                imageData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
            ) else {
                sendImageAbortPacket(transferId: payload.transferId, reason: "persist_failed")
                return
            }
            let thumbnailRelativePath = makeThumbnailRelativePath(
                imageData: imageData,
                messageId: transfer.messageId
            )
            _ = appendRemoteImageMessageOnMain(
                imageRelativePath: imageRelativePath,
                thumbnailRelativePath: thumbnailRelativePath,
                imageWidth: transfer.width,
                imageHeight: transfer.height,
                imageMimeType: transfer.mimeType,
                transportMessageId: transfer.messageId
            )
            sendImageDonePacket(transferId: payload.transferId)
        case .done, .abort:
            break
        }
    }

    private func handleVoicePacket(_ packet: BleVoicePayload.Packet) {
        cleanupStaleVoiceTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingVoiceTransfers[payload.transferId] = BleVoicePayload.IncomingTransfer(
                transferId: payload.transferId,
                messageId: payload.messageId,
                mimeType: payload.mimeType,
                durationMillis: payload.durationMillis,
                totalBytes: payload.totalBytes,
                totalChunks: payload.totalChunks,
                sha256: payload.sha256
            )
        case .chunk(let payload):
            guard var transfer = incomingVoiceTransfers[payload.transferId] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingVoiceTransfers.removeValue(forKey: payload.transferId)
                sendVoiceAbortPacket(transferId: payload.transferId, reason: "invalid_chunk")
                return
            }
            incomingVoiceTransfers[payload.transferId] = transfer
            guard transfer.isComplete,
                  let audioData = transfer.composedData(),
                  audioData.count <= BleVoicePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: audioData))
            guard digest == transfer.sha256 else {
                incomingVoiceTransfers.removeValue(forKey: payload.transferId)
                sendVoiceAbortPacket(transferId: payload.transferId, reason: "sha_mismatch")
                return
            }
            incomingVoiceTransfers.removeValue(forKey: payload.transferId)
            guard let audioRelativePath = SOSChatStore.persistVoiceData(
                audioData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
            ) else {
                sendVoiceAbortPacket(transferId: payload.transferId, reason: "persist_failed")
                return
            }
            _ = appendRemoteAudioMessageOnMain(
                audioRelativePath: audioRelativePath,
                durationMillis: transfer.durationMillis,
                transportMessageId: transfer.messageId
            )
            let notificationTitle = SOSChatStore.shared.session(for: sessionId)?.displayName
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: sessionId,
                title: notificationTitle,
                body: SOSChatStore.voicePreviewText(),
                kind: .sosAlert
            )
            sendVoiceDonePacket(transferId: payload.transferId)
        case .done:
            break
        case .abort(let payload):
            incomingVoiceTransfers.removeValue(forKey: payload.transferId)
        }
    }

    private func handleFilePacket(_ packet: BleFilePayload.Packet) {
        cleanupStaleFileTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingFileTransfers[payload.transferId] = BleFilePayload.IncomingTransfer(
                transferId: payload.transferId,
                messageId: payload.messageId,
                displayName: payload.displayName,
                mimeType: payload.mimeType,
                originalSizeBytes: payload.originalSizeBytes,
                totalBytes: payload.totalBytes,
                totalChunks: payload.totalChunks,
                sha256: payload.sha256
            )
        case .chunk(let payload):
            guard var transfer = incomingFileTransfers[payload.transferId] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingFileTransfers.removeValue(forKey: payload.transferId)
                sendFileAbortPacket(transferId: payload.transferId, reason: "invalid_chunk")
                return
            }
            incomingFileTransfers[payload.transferId] = transfer
            guard transfer.isComplete,
                  let fileData = transfer.composedData(),
                  fileData.count <= BleFilePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: fileData))
            guard digest == transfer.sha256 else {
                incomingFileTransfers.removeValue(forKey: payload.transferId)
                sendFileAbortPacket(transferId: payload.transferId, reason: "sha_mismatch")
                return
            }
            incomingFileTransfers.removeValue(forKey: payload.transferId)
            guard P2pSharedTransferSupport.persistSharedDocumentData(
                fileData,
                messageId: transfer.messageId,
                displayName: transfer.displayName
            ) != nil else {
                sendFileAbortPacket(transferId: payload.transferId, reason: "persist_failed")
                return
            }
            sendFileDonePacket(transferId: payload.transferId)
        case .done:
            break
        case .abort(let payload):
            incomingFileTransfers.removeValue(forKey: payload.transferId)
        }
    }

    private func handleBroadcastId(_ value: Data) {
        guard let raw = String(data: value, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
              !raw.isEmpty else { return }
        let normalized = raw.replacingOccurrences(of: "ccid:", with: "", options: [.caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return }
        if broadcastId != normalized {
            broadcastId = normalized
            onBroadcastId?(normalized)
        }
    }

    private func sendPeerIdentity() {
        guard let key = sessionKey,
              let secureChatInCharacteristic else { return }
        let name = localDisplayName()
        let role = preSharedKey == nil ? "rescue" : "contact"
        var payload: [String: Any] = [
            "kind": "peer_info",
            "name": name,
            "role": role
        ]
        if let batteryPercent = currentBatteryPercent(), (0...100).contains(batteryPercent) {
            payload["batteryPct"] = batteryPercent
        }
        if let avatarBase64 = localAvatarPayload() {
            payload["avatarB64"] = avatarBase64
        }
        let data = (try? JSONSerialization.data(withJSONObject: payload, options: [])) ?? Data()
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: data,
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: secureChatInCharacteristic) { _ in }
    }

    private func parsePeerInfoPayload(_ message: String) -> PeerIdentity? {
        guard let data = message.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let json = object as? [String: Any],
              let kind = json["kind"] as? String,
              kind == "peer_info" else { return nil }
        let name = (json["name"] as? String) ?? ""
        let role = (json["role"] as? String) ?? ""
        let rawAvatar = (json["avatarB64"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let avatarBase64: String?
        if let rawAvatar, !rawAvatar.isEmpty, rawAvatar.count <= 8_192 {
            avatarBase64 = rawAvatar
        } else {
            avatarBase64 = nil
        }
        // Victims report batteryPct in peer_info; dropping it here starved both the rescuer UI
        // and the dashboard sync of the one triage datum the protocol already carries.
        let batteryPercent = (numericValue(from: json["batteryPct"])).flatMap { value -> Int? in
            let rounded = Int(value.rounded())
            return (0...100).contains(rounded) ? rounded : nil
        }
        let medical = (json["medical"] as? [String: Any]).flatMap { medicalJson -> RescueVictimMedical? in
            func field(_ key: String, max: Int) -> String? {
                guard let raw = medicalJson[key] as? String else { return nil }
                let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                return trimmed.isEmpty ? nil : String(trimmed.prefix(max))
            }
            let parsed = RescueVictimMedical(
                bloodType: field("blood", max: 8),
                allergies: field("allergies", max: 200),
                medication: field("meds", max: 200),
                notes: field("notes", max: 200)
            )
            return parsed.hasContent ? parsed : nil
        }
        return PeerIdentity(
            name: name,
            role: role,
            avatarBase64: avatarBase64,
            batteryPercent: batteryPercent,
            medical: medical,
            signalLocation: SOSSignalLocationPayload.fromPeerInfoJSON(json["signalLocation"])
                ?? SOSSignalLocationPayload.fromLegacyLocationJSON(json["location"])
        )
    }

    private func parseLiveLocationPayload(_ message: String) -> LiveLocationPayload? {
        guard let data = message.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let json = object as? [String: Any],
              let kind = json["kind"] as? String,
              kind == "live_location",
              let latitude = numericValue(from: json["latitude"]),
              let longitude = numericValue(from: json["longitude"]) else {
            return nil
        }

        let messageId = (json["messageId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let horizontalAccuracyMeters = numericValue(from: json["accuracyMeters"])
        let timestampMillis = numericValue(from: json["timestampMillis"])
        let capturedAt = timestampMillis.map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()

        return LiveLocationPayload(
            messageId: messageId?.isEmpty == false ? messageId : nil,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: horizontalAccuracyMeters,
            capturedAt: capturedAt
        )
    }

    private func applyPeerIdentity(_ identity: PeerIdentity) {
        let peerName = identity.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !peerName.isEmpty {
            onPeerName?(peerName)
        }
        if let batteryPercent = identity.batteryPercent {
            onPeerBattery?(batteryPercent)
        }
        if let medical = identity.medical {
            onPeerMedical?(medical)
        }
        let resolvedRole: SOSChatRole
        switch identity.role.lowercased() {
        case "victim":
            resolvedRole = .victim
        case "rescue":
            resolvedRole = .fieldTeam
        default:
            resolvedRole = .unknown
        }
        DispatchQueue.main.async {
            SOSChatStore.shared.updateIdentity(
                id: self.sessionId,
                displayName: identity.name,
                role: resolvedRole,
                isVerified: true,
                avatarBase64: identity.avatarBase64
            )
        }
    }

    private func sendAck(_ payload: Data) {
        guard let characteristic = secureAckCharacteristic else { return }
        enqueueWrite(payload, to: characteristic) { _ in }
    }

    private func appendRemoteMessageOnMain(text: String, transportMessageId: String?) -> Bool {
        if Thread.isMainThread {
            return SOSChatStore.shared.appendRemoteMessage(
                sessionId: sessionId,
                text: text,
                transportMessageId: transportMessageId
            )
        }
        return DispatchQueue.main.sync {
            SOSChatStore.shared.appendRemoteMessage(
                sessionId: sessionId,
                text: text,
                transportMessageId: transportMessageId
            )
        }
    }

    private func upsertRemoteLocationMessageOnMain(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        transportMessageId: String?
    ) -> Bool {
        if Thread.isMainThread {
            return SOSChatStore.shared.upsertRemoteLocationMessage(
                sessionId: sessionId,
                latitude: latitude,
                longitude: longitude,
                horizontalAccuracyMeters: horizontalAccuracyMeters,
                capturedAt: capturedAt,
                transportMessageId: transportMessageId
            )
        }
        return DispatchQueue.main.sync {
            SOSChatStore.shared.upsertRemoteLocationMessage(
                sessionId: sessionId,
                latitude: latitude,
                longitude: longitude,
                horizontalAccuracyMeters: horizontalAccuracyMeters,
                capturedAt: capturedAt,
                transportMessageId: transportMessageId
            )
        }
    }

    private func appendRemoteAudioMessageOnMain(
        audioRelativePath: String,
        durationMillis: Int?,
        transportMessageId: String
    ) -> Bool {
        let append = {
            SOSChatStore.shared.appendRemoteAudioMessage(
                sessionId: self.sessionId,
                audioRelativePath: audioRelativePath,
                durationMillis: durationMillis,
                transportMessageId: transportMessageId
            )
        }
        if Thread.isMainThread {
            return append()
        }
        return DispatchQueue.main.sync(execute: append)
    }

    private func localDisplayName() -> String {
        let stored = ProfileMetadataStore.loadFullName().trimmingCharacters(in: .whitespacesAndNewlines)
        if !stored.isEmpty {
            return stored
        }
        let deviceName = UIDevice.current.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !deviceName.isEmpty {
            return deviceName
        }
        return NSLocalizedString("SOS_CHAT_ROLE_FIELD_TEAM", comment: "")
    }

    private func currentBatteryPercent() -> Int? {
        let device = UIDevice.current
        let wasMonitoring = device.isBatteryMonitoringEnabled
        if !wasMonitoring {
            device.isBatteryMonitoringEnabled = true
        }
        let level = device.batteryLevel
        if !wasMonitoring {
            device.isBatteryMonitoringEnabled = false
        }
        guard level >= 0 else { return nil }
        return min(100, max(0, Int((level * 100).rounded())))
    }

    private func localAvatarPayload() -> String? {
        guard let data = ProfileMetadataStore.loadAvatarThumbnailData(),
              !data.isEmpty else {
            return nil
        }
        let encoded = data.base64EncodedString()
        return encoded.count <= 8_192 ? encoded : nil
    }

    private func numericValue(from raw: Any?) -> Double? {
        switch raw {
        case let value as NSNumber:
            return value.doubleValue
        case let value as Double:
            return value
        case let value as Int:
            return Double(value)
        case let value as Int64:
            return Double(value)
        default:
            return nil
        }
    }

    private func sendImageDonePacket(transferId: String) {
        let payload = BleImagePayload.buildDonePacket(transferId)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
    }

    private func sendImageAbortPacket(transferId: String, reason: String) {
        let payload = BleImagePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
    }

    private func sendVoiceDonePacket(transferId: String) {
        let payload = BleVoicePayload.buildDonePacket(transferId)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
    }

    private func sendVoiceAbortPacket(transferId: String, reason: String) {
        let payload = BleVoicePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
    }

    private func sendFileDonePacket(transferId: String) {
        let payload = BleFilePayload.buildDonePacket(transferId)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
    }

    private func sendFileAbortPacket(transferId: String, reason: String) {
        let payload = BleFilePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty,
              let key = sessionKey,
              let characteristic = secureChatInCharacteristic else {
            return
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: key,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return }
        enqueueWrite(encrypted, to: characteristic) { _ in }
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

    private func appendRemoteImageMessageOnMain(
        imageRelativePath: String,
        thumbnailRelativePath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String,
        transportMessageId: String
    ) -> Bool {
        let append = {
            SOSChatStore.shared.appendRemoteImageMessage(
                sessionId: self.sessionId,
                imageRelativePath: imageRelativePath,
                thumbnailRelativePath: thumbnailRelativePath,
                imageWidth: imageWidth,
                imageHeight: imageHeight,
                imageMimeType: imageMimeType,
                transportMessageId: transportMessageId
            )
        }
        if Thread.isMainThread {
            return append()
        }
        return DispatchQueue.main.sync(execute: append)
    }

    private func cleanupStaleImageTransfers() {
        let now = Date()
        incomingImageTransfers = incomingImageTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
    }

    private func cleanupStaleVoiceTransfers() {
        let now = Date()
        incomingVoiceTransfers = incomingVoiceTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
    }

    private func cleanupStaleFileTransfers() {
        let now = Date()
        incomingFileTransfers = incomingFileTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
    }

    private func scheduleAuthTimeout() {
        authTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.awaitingAuthResponse else { return }
            self.failAndDisconnect()
        }
        authTimeoutWork = work
        queue.asyncAfter(deadline: .now() + 8, execute: work)
    }

    private func scheduleAckTimeout() {
        ackTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.awaitingHandshakeAck else { return }
            self.failAndDisconnect()
        }
        ackTimeoutWork = work
        queue.asyncAfter(deadline: .now() + 8, execute: work)
    }

    private func enqueueWrite(_ data: Data, to characteristic: CBCharacteristic, completion: @escaping (Bool) -> Void) {
        let maxLength = max(1, peripheral.maximumWriteValueLength(for: .withResponse))
        var chunks: [Data] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + maxLength, data.count)
            chunks.append(data.subdata(in: offset..<end))
            offset = end
        }
        queue.async { [weak self] in
            guard let self else { return }
            let request = WriteRequest(characteristic: characteristic, chunks: chunks, completion: completion)
            self.writeQueue.append(request)
            if self.activeWrite == nil {
                self.startNextWrite()
            }
        }
    }

    private func startNextWrite() {
        guard activeWrite == nil else { return }
        activeWrite = writeQueue.isEmpty ? nil : writeQueue.removeFirst()
        writeNextChunk()
    }

    private func writeNextChunk() {
        guard let activeWrite else { return }
        guard activeWrite.index < activeWrite.chunks.count else {
            let completion = activeWrite.completion
            self.activeWrite = nil
            completion(true)
            startNextWrite()
            return
        }
        let chunk = activeWrite.chunks[activeWrite.index]
        peripheral.writeValue(chunk, for: activeWrite.characteristic, type: .withResponse)
    }

    private func handleWriteResult(success: Bool) {
        guard let activeWrite else { return }
        if !success {
            let completion = activeWrite.completion
            self.activeWrite = nil
            completion(false)
            startNextWrite()
            return
        }
        activeWrite.index += 1
        writeNextChunk()
    }

    private func updateStatus(_ status: RescueConnectionStatus) {
        self.status = status
        if status == .disconnected || status == .failed {
            RescueCallEngine.shared.unregisterTransport(sessionId: sessionId)
        }
        DispatchQueue.main.async {
            self.onStatusChange?(status)
        }
    }

    private func failAndDisconnect() {
        updateStatus(.failed)
        if let central {
            central.cancelPeripheralConnection(peripheral)
        } else {
            resetSession()
        }
    }

    private func resetSession() {
        sessionKey = nil
        clientKey = nil
        clientPublicKeyData = nil
        awaitingAuthResponse = false
        awaitingHandshakeAck = false
        authTimeoutWork?.cancel()
        ackTimeoutWork?.cancel()
        chunkReceiver.reset()
        incomingImageTransfers.removeAll()
        writeQueue.removeAll()
        activeWrite = nil
    }

    private func buildSessionNonce(clientPublicKey: Data, serverPublicKey: Data) -> String {
        var combined = Data()
        combined.append(clientPublicKey)
        combined.append(serverPublicKey)
        let digest = Data(SHA256.hash(data: combined))
        return digest.base64EncodedString()
    }

    private func makeX509PublicKeyData(from publicKey: P256.KeyAgreement.PublicKey) -> Data {
        let raw = publicKey.x963Representation
        let prefix: [UInt8] = [
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
        ]
        var data = Data(prefix)
        data.append(raw)
        return data
    }
}

private struct PeerIdentity {
    let name: String
    let role: String
    let avatarBase64: String?
    let batteryPercent: Int?
    let medical: RescueVictimMedical?
    let signalLocation: SOSSignalLocationPayload?
}

/// Optional emergency medical details a victim shares over the encrypted rescue link.
struct RescueVictimMedical: Equatable {
    let bloodType: String?
    let allergies: String?
    let medication: String?
    let notes: String?

    var hasContent: Bool {
        [bloodType, allergies, medication, notes].contains { !($0 ?? "").isEmpty }
    }
}
