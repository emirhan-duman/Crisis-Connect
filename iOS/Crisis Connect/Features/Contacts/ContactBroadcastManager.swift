//
//  ContactBroadcastManager.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import CoreBluetooth
import FirebaseAuth
import Combine
import CryptoKit
import UIKit

private struct P2pPublishedSession: Equatable {
    let shareId: String
    let sessionCode: String
    let displayName: String?
    let aesKeyBase64: String
    let serverNonce: String
    let deviceId: String
    let bootstrapPayload: Data
}

private struct P2pPendingHandshake {
    let clientSessionCode: String
    let clientName: String?
    let clientAvatarBase64: String?
    let clientDeviceId: String
    let clientNonce: String
    let clientPlatform: String
    let serverHelloProof: String
    // The scanning peer's internet-messaging identity, carried in the client-hello so THIS (the
    // QR-displaying) side can reach them online too — the reverse of what the QR already gives the
    // scanner. Nil when the peer didn't supply it (older build / not registered). Authenticated by
    // a separate HMAC (clientIdentityProof) so it stays backward-compatible with peers that omit it.
    let clientPeerUid: String?
    let clientPeerPublicKey: String?
}

private struct P2pPendingShareCompletion {
    let session: P2pPublishedSession
    let pending: P2pPendingHandshake
}

private struct P2pIncomingEnvelope {
    let fromDeviceId: String
    let encryptedPacket: Data
}

private struct P2pPendingNotificationKey: Hashable {
    let centralId: UUID
    let characteristicId: CBUUID
}

private struct P2pPendingNotification {
    let payload: Data
    var offset: Int
}

private struct P2pIncomingChatPayload {
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

private let maxEncryptedChatPacketBytes = 4096
private let maxEnvelopePacketBytes = 8192

final class ContactBroadcastManager: NSObject, ObservableObject, CBPeripheralManagerDelegate, BlePeripheralHostProfile {
    static let shared = ContactBroadcastManager()

    @Published private(set) var isAdvertising: Bool = false
    @Published private(set) var bluetoothState: CBManagerState = .unknown
    @Published private(set) var connectedSessionIds: Set<UUID> = []

    private let queue = BlePeripheralHostCoordinator.shared.queue
    private let queueKey = DispatchSpecificKey<Void>()
    private var shareSession: P2pPublishedSession?
    private var deviceResponses: [UUID: Data] = [:]
    private var pendingHandshakes: [UUID: P2pPendingHandshake] = [:]
    private var pendingShareCompletions: [UUID: P2pPendingShareCompletion] = [:]
    private var messageReceivers: [UUID: BleChunkReceiver] = [:]
    private var controlCharacteristic: CBMutableCharacteristic?
    private var messageOutCharacteristic: CBMutableCharacteristic?
    private var subscribedMessageOutCentrals: [UUID: CBCentral] = [:]
    private var pendingMessageOutValues: [UUID: [Data]] = [:]
    private var pendingNotifications: [P2pPendingNotificationKey: P2pPendingNotification] = [:]
    private var centralSessionIds: [UUID: UUID] = [:]
    private var incomingVoiceTransfers: [String: P2pIncomingVoiceTransfer] = [:]
    private var incomingImageTransfers: [String: P2pIncomingImageTransfer] = [:]
    private var incomingFileTransfers: [String: P2pIncomingFileTransfer] = [:]
    private var foregroundSessionId: UUID?
    private var contactSubscription: AnyCancellable?
    private var meshHostingSubscription: AnyCancellable?
    private var hasBlePreferredContacts = ContactStore.shared.hasBlePreferredContacts()

    var hostProfileIdentifier: String { "contact" }
    var hostPrimaryServiceUUID: CBUUID { P2pBleProtocol.serviceUUID }
    var isPeripheralHostActive: Bool { shouldHostAnySession }
    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? {
        guard shouldHostAnySession else { return nil }
        return BleHostedAdvertisementDescriptor(
            serviceUUIDs: [P2pBleProtocol.serviceUUID],
            localName: shareSession?.shareId,
            priority: shareSession == nil ? 10 : 100
        )
    }

    private var peripheralManager: CBPeripheralManager? {
        BlePeripheralHostCoordinator.shared.currentPeripheralManager
    }

    private override init() {
        super.init()
        queue.setSpecific(key: queueKey, value: ())
        BlePeripheralHostCoordinator.shared.register(self)
        contactSubscription = ContactStore.shared.$contacts.sink { [weak self] contacts in
            let hasBlePreferredContacts = contacts.contains { $0.preferredTransport == .bleGatt }
            self?.queue.async {
                guard let self else { return }
                let shouldRefreshHosting = self.peripheralManager == nil ||
                    hasBlePreferredContacts != self.hasBlePreferredContacts
                self.hasBlePreferredContacts = hasBlePreferredContacts
                guard shouldRefreshHosting else { return }
                // Keep the active QR-share GATT host stable until the control handshake
                // completes; persisting the newly paired contact also updates the store.
                if self.shareSession != nil {
                    return
                }
                self.refreshHostingIfNeeded()
            }
        }
        meshHostingSubscription = AdvancedSettingsStore.shared.$publicMeshEnabled
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.queue.async { [weak self] in
                    self?.refreshHostingIfNeeded()
                }
            }
        queue.async { [weak self] in
            self?.refreshHostingIfNeeded()
        }
    }

    func startSharing(
        shareId: String,
        sessionCode: String,
        displayName: String?,
        aesKeyBase64: String
    ) {
        let normalizedShareId = P2pBleProtocol.normalizeShareId(shareId)
        let trimmedSessionCode = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedKey = aesKeyBase64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard
            !normalizedShareId.isEmpty,
            !trimmedSessionCode.isEmpty,
            P2pBleProtocol.decodeBase64(trimmedKey)?.count == 32
        else {
            return
        }

        let serverNonce = P2pBleProtocol.randomNonceBase64()
        let deviceId = localDeviceId()
        let avatarBase64 = localAvatarPayload()
        let bootstrapPayload = Self.makeShareBootstrapPayload(
            shareId: normalizedShareId,
            sessionCode: trimmedSessionCode,
            displayName: displayName,
            serverNonce: serverNonce,
            deviceId: deviceId,
            avatarBase64: avatarBase64
        )
        let nextSession = P2pPublishedSession(
            shareId: normalizedShareId,
            sessionCode: trimmedSessionCode,
            displayName: displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            aesKeyBase64: trimmedKey,
            serverNonce: serverNonce,
            deviceId: deviceId,
            bootstrapPayload: bootstrapPayload
        )

        queue.async { [weak self] in
            guard let self else { return }
            self.shareSession = nextSession
            self.refreshHostingIfNeeded()
        }
    }

    func stopSharing() {
        queue.async { [weak self] in
            guard let self else { return }
            self.shareSession = nil
            self.refreshHostingIfNeeded()
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.shareSession = nil
            self.resetHostedTransportState()
            DispatchQueue.main.async {
                self.isAdvertising = false
            }
            BlePeripheralHostCoordinator.shared.setNeedsRefresh()
        }
    }

    func sendMessage(_ text: String, transportMessageId: String, sessionId: UUID) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        return syncOnQueue {
            sendChatPayload(
                kind: P2pBleProtocol.chatKindText,
                messageId: transportMessageId,
                text: trimmed,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: nil,
                chunkData: nil,
                sessionId: sessionId
            )
        }
    }

    func sendReadReceipt(sessionId: UUID) -> Bool {
        syncOnQueue {
            sendChatPayload(
                kind: P2pBleProtocol.chatKindRead,
                messageId: nil,
                text: nil,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: nil,
                chunkData: nil,
                sessionId: sessionId
            )
        }
    }

    func isSessionConnected(_ sessionId: UUID) -> Bool {
        syncOnQueue {
            connectedCentral(for: sessionId) != nil
        }
    }

    func sendVoiceMessage(
        audioFileName: String,
        mimeType: String,
        durationMillis: Int,
        messageId: String,
        sessionId: UUID
    ) -> Bool {
        guard
            let manager = peripheralManager,
            let messageOutCharacteristic,
            let record = ContactStore.shared.contact(for: sessionId),
            let central = connectedCentral(for: sessionId),
            let fileData = SOSChatStore.loadVoiceData(fileName: audioFileName),
            !fileData.isEmpty,
            fileData.count <= P2pBleProtocol.voiceMaxTotalBytes
        else {
            return false
        }

        let totalChunks = max(1, Int(ceil(Double(fileData.count) / Double(P2pBleProtocol.voiceChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.voiceMaxChunks else { return false }

        guard let initPayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindVoiceInit,
            messageId: messageId,
            text: nil,
            displayName: nil,
            mimeType: mimeType,
            durationMillis: max(0, durationMillis),
            width: nil,
            height: nil,
            originalSizeBytes: nil,
            totalBytes: nil,
            totalChunks: totalChunks,
            sha256: nil,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(initPayload, for: messageOutCharacteristic, to: central, peripheral: manager)

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + P2pBleProtocol.voiceChunkSizeBytes, fileData.count)
            guard let chunkPayload = buildChatTransportPayload(
                kind: P2pBleProtocol.chatKindVoiceChunk,
                messageId: messageId,
                text: nil,
                displayName: nil,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                originalSizeBytes: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: index,
                chunkData: fileData.subdata(in: offset..<end),
                record: record
            ) else {
                return false
            }
            queueNotification(chunkPayload, for: messageOutCharacteristic, to: central, peripheral: manager)
            offset = end
        }

        guard let donePayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindVoiceDone,
            messageId: messageId,
            text: nil,
            displayName: nil,
            mimeType: nil,
            durationMillis: nil,
            width: nil,
            height: nil,
            originalSizeBytes: nil,
            totalBytes: nil,
            totalChunks: nil,
            sha256: nil,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(donePayload, for: messageOutCharacteristic, to: central, peripheral: manager)
        return true
    }

    func sendImageMessage(
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String,
        sessionId: UUID
    ) -> Bool {
        guard
            let manager = peripheralManager,
            let messageOutCharacteristic,
            let record = ContactStore.shared.contact(for: sessionId),
            let central = connectedCentral(for: sessionId),
            let fileData = SOSChatStore.loadImageData(fileName: imageFileName),
            !fileData.isEmpty,
            fileData.count <= P2pBleProtocol.imageMaxTotalBytes
        else {
            return false
        }

        let totalChunks = max(1, Int(ceil(Double(fileData.count) / Double(P2pBleProtocol.imageChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.imageMaxChunks else { return false }
        let digest = Data(SHA256.hash(data: fileData)).base64EncodedString()

        guard let initPayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindImageInit,
            messageId: messageId,
            text: nil,
            displayName: nil,
            mimeType: mimeType,
            durationMillis: nil,
            width: max(1, width),
            height: max(1, height),
            originalSizeBytes: nil,
            totalBytes: fileData.count,
            totalChunks: totalChunks,
            sha256: digest,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(initPayload, for: messageOutCharacteristic, to: central, peripheral: manager)

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + P2pBleProtocol.imageChunkSizeBytes, fileData.count)
            guard let chunkPayload = buildChatTransportPayload(
                kind: P2pBleProtocol.chatKindImageChunk,
                messageId: messageId,
                text: nil,
                displayName: nil,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                originalSizeBytes: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: index,
                chunkData: fileData.subdata(in: offset..<end),
                record: record
            ) else {
                return false
            }
            queueNotification(chunkPayload, for: messageOutCharacteristic, to: central, peripheral: manager)
            offset = end
        }

        guard let donePayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindImageDone,
            messageId: messageId,
            text: nil,
            displayName: nil,
            mimeType: nil,
            durationMillis: nil,
            width: nil,
            height: nil,
            originalSizeBytes: nil,
            totalBytes: nil,
            totalChunks: nil,
            sha256: nil,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(donePayload, for: messageOutCharacteristic, to: central, peripheral: manager)
        return true
    }

    func sendFileMessage(
        data: Data,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        messageId: String,
        sessionId: UUID
    ) -> Bool {
        guard
            let manager = peripheralManager,
            let messageOutCharacteristic,
            let record = ContactStore.shared.contact(for: sessionId),
            let central = connectedCentral(for: sessionId),
            !data.isEmpty,
            data.count <= P2pBleProtocol.fileMaxTotalBytes
        else {
            return false
        }

        let totalChunks = max(1, Int(ceil(Double(data.count) / Double(P2pBleProtocol.fileChunkSizeBytes))))
        guard totalChunks <= P2pBleProtocol.fileMaxChunks else { return false }
        let digest = Data(SHA256.hash(data: data)).base64EncodedString()
        let trimmedName = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty, originalSizeBytes > 0 else { return false }

        guard let initPayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindFileInit,
            messageId: messageId,
            text: nil,
            displayName: trimmedName,
            mimeType: mimeType,
            durationMillis: nil,
            width: nil,
            height: nil,
            originalSizeBytes: originalSizeBytes,
            totalBytes: data.count,
            totalChunks: totalChunks,
            sha256: digest,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(initPayload, for: messageOutCharacteristic, to: central, peripheral: manager)

        var offset = 0
        for index in 0..<totalChunks {
            let end = min(offset + P2pBleProtocol.fileChunkSizeBytes, data.count)
            guard let chunkPayload = buildChatTransportPayload(
                kind: P2pBleProtocol.chatKindFileChunk,
                messageId: messageId,
                text: nil,
                displayName: nil,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                originalSizeBytes: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: index,
                chunkData: data.subdata(in: offset..<end),
                record: record
            ) else {
                return false
            }
            queueNotification(chunkPayload, for: messageOutCharacteristic, to: central, peripheral: manager)
            offset = end
        }

        guard let donePayload = buildChatTransportPayload(
            kind: P2pBleProtocol.chatKindFileDone,
            messageId: messageId,
            text: nil,
            displayName: nil,
            mimeType: nil,
            durationMillis: nil,
            width: nil,
            height: nil,
            originalSizeBytes: nil,
            totalBytes: nil,
            totalChunks: nil,
            sha256: nil,
            chunkIndex: nil,
            chunkData: nil,
            record: record
        ) else {
            return false
        }
        queueNotification(donePayload, for: messageOutCharacteristic, to: central, peripheral: manager)
        return true
    }

    func setForegroundSession(_ sessionId: UUID?) {
        queue.async { [weak self] in
            guard let self else { return }
            self.foregroundSessionId = sessionId
            guard let sessionId else { return }
            if let subscribedCentral = self.subscribedMessageOutCentrals.values.first,
               self.subscribedMessageOutCentrals.count == 1 {
                self.bindCentral(subscribedCentral.identifier, to: sessionId)
            } else {
                self.publishConnectedSessions()
            }
        }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        DispatchQueue.main.async { [weak self] in
            self?.bluetoothState = peripheral.state
        }
        guard peripheral.state == .poweredOn else {
            // Bluetooth is gone — every subscribed central is dead. Without this flush,
            // isSessionConnected stayed true and message/call routing kept picking the dead
            // peripheral link instead of falling back to the internet transport.
            centralSessionIds.removeAll()
            subscribedMessageOutCentrals.removeAll()
            publishConnectedSessions()
            DispatchQueue.main.async { [weak self] in
                self?.isAdvertising = false
            }
            return
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        DispatchQueue.main.async { [weak self] in
            self?.isAdvertising = (error == nil) && (self?.isPeripheralHostActive == true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            DispatchQueue.main.async { [weak self] in
                self?.isAdvertising = false
            }
            return
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        let responseData: Data
        switch request.characteristic.uuid {
        case P2pBleProtocol.idCharacteristicUUID:
            responseData = currentIdentityValue()
        case P2pBleProtocol.bootstrapCharacteristicUUID:
            responseData = currentBootstrapPayload()
        case P2pBleProtocol.controlCharacteristicUUID:
            responseData = deviceResponses[request.central.identifier] ?? Data()
        default:
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }

        if request.offset > responseData.count {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = responseData.subdata(in: request.offset..<responseData.count)
        peripheral.respond(to: request, withResult: .success)

        guard
            request.characteristic.uuid == P2pBleProtocol.controlCharacteristicUUID,
            request.offset == 0
        else {
            return
        }
        finalizeShareCompletionIfNeeded(for: request.central.identifier, deliveredPayload: responseData)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.offset == 0, let value = request.value else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                continue
            }

            let status: CBATTError.Code
            switch request.characteristic.uuid {
            case P2pBleProtocol.controlCharacteristicUUID:
                ensureTrackedCentral(request.central)
                status = handleControlWrite(value, from: request.central)
            case P2pBleProtocol.messageInCharacteristicUUID:
                ensureTrackedCentral(request.central)
                status = handleMessageChunk(value, from: request.central)
            default:
                status = .requestNotSupported
            }
            peripheral.respond(to: request, withResult: status)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == P2pBleProtocol.messageOutCharacteristicUUID else { return }
        subscribedMessageOutCentrals[central.identifier] = central
        if let record = ContactStore.shared.contactForBleAddress(central.identifier.uuidString.uppercased()) {
            bindCentral(central.identifier, to: record)
        } else if let foregroundSessionId {
            bindCentral(central.identifier, to: foregroundSessionId)
        } else {
            publishConnectedSessions()
        }
        flushQueuedMessageOutValues(for: central.identifier, peripheral: peripheral)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == P2pBleProtocol.messageOutCharacteristicUUID else { return }
        let key = P2pPendingNotificationKey(
            centralId: central.identifier,
            characteristicId: P2pBleProtocol.messageOutCharacteristicUUID
        )
        subscribedMessageOutCentrals.removeValue(forKey: central.identifier)
        pendingMessageOutValues.removeValue(forKey: central.identifier)
        pendingNotifications.removeValue(forKey: key)
        centralSessionIds.removeValue(forKey: central.identifier)
        publishConnectedSessions()
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPendingNotifications(peripheral)
    }

    private func refreshHostingIfNeeded() {
        guard PlatformRuntime.supportsBlePeripheralHosting else {
            resetHostedTransportState()
            DispatchQueue.main.async { [weak self] in
                self?.isAdvertising = false
                self?.bluetoothState = .unsupported
            }
            BlePeripheralHostCoordinator.shared.setNeedsRefresh()
            return
        }

        let shouldHost = shouldHostAnySession
        guard shouldHost else {
            resetHostedTransportState()
            DispatchQueue.main.async { [weak self] in
                self?.isAdvertising = false
            }
            BlePeripheralHostCoordinator.shared.setNeedsRefresh()
            return
        }
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }

    private var shouldHostAnySession: Bool {
        guard PlatformRuntime.supportsBlePeripheralHosting else { return false }
        // BlePeripheralHostCoordinator can host multiple services at once and will either merge
        // their advertisement payloads or rotate them when the payload budget is tight. Keeping
        // the contact GATT service active while public mesh is enabled is required so direct P2P
        // contacts can still discover 0xCD00 and complete the QR-paired handshake.
        return hasActiveHostedSession || hasBlePreferredContacts
    }

    private var hasActiveHostedSession: Bool {
        shareSession != nil || foregroundSessionId != nil || !centralSessionIds.isEmpty
    }

    private func resetHostedTransportState() {
        deviceResponses.removeAll()
        pendingHandshakes.removeAll()
        pendingShareCompletions.removeAll()
        messageReceivers.removeAll()
        controlCharacteristic = nil
        messageOutCharacteristic = nil
        subscribedMessageOutCentrals.removeAll()
        pendingMessageOutValues.removeAll()
        pendingNotifications.removeAll()
        centralSessionIds.removeAll()
        incomingVoiceTransfers.removeAll()
        incomingImageTransfers.removeAll()
        incomingFileTransfers.removeAll()
        publishConnectedSessions()
    }

    func peripheralHostWillReset() {
        resetHostedTransportState()
    }

    func buildHostedService() -> CBMutableService? {
        guard shouldHostAnySession else { return nil }
        deviceResponses.removeAll()
        pendingHandshakes.removeAll()
        pendingShareCompletions.removeAll()
        messageReceivers.removeAll()
        controlCharacteristic = nil
        messageOutCharacteristic = nil
        subscribedMessageOutCentrals.removeAll()
        pendingMessageOutValues.removeAll()
        pendingNotifications.removeAll()
        centralSessionIds.removeAll()
        incomingVoiceTransfers.removeAll()
        incomingImageTransfers.removeAll()
        incomingFileTransfers.removeAll()
        publishConnectedSessions()

        let idCharacteristic = CBMutableCharacteristic(
            type: P2pBleProtocol.idCharacteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let bootstrapCharacteristic = CBMutableCharacteristic(
            type: P2pBleProtocol.bootstrapCharacteristicUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let controlCharacteristic = CBMutableCharacteristic(
            type: P2pBleProtocol.controlCharacteristicUUID,
            properties: [.read, .write],
            value: nil,
            permissions: [.readable, .writeable]
        )
        let messageInCharacteristic = CBMutableCharacteristic(
            type: P2pBleProtocol.messageInCharacteristicUUID,
            // writeWithoutResponse is the voice-call audio fast path — without it the ATT server
            // rejects a central's Write Commands and call audio never arrives (mirror of the
            // Android host's PROPERTY_WRITE_NO_RESPONSE fix).
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let messageOutCharacteristic = CBMutableCharacteristic(
            type: P2pBleProtocol.messageOutCharacteristicUUID,
            properties: [.notify],
            value: nil,
            permissions: []
        )
        let service = CBMutableService(type: P2pBleProtocol.serviceUUID, primary: true)
        service.characteristics = [
            idCharacteristic,
            bootstrapCharacteristic,
            controlCharacteristic,
            messageInCharacteristic,
            messageOutCharacteristic
        ]
        self.controlCharacteristic = controlCharacteristic
        self.messageOutCharacteristic = messageOutCharacteristic
        return service
    }

    private func currentIdentityValue() -> Data {
        if let shareSession {
            return P2pBleProtocol.buildIdentityValue(shareId: shareSession.shareId)
        }
        return P2pBleProtocol.buildDeviceIdentityValue(deviceId: localDeviceId())
    }

    private func currentBootstrapPayload() -> Data {
        if let shareSession {
            return shareSession.bootstrapPayload
        }
        return Self.makeHostBootstrapPayload(
            deviceId: localDeviceId(),
            displayName: localDisplayName(),
            avatarBase64: localAvatarPayload()
        )
    }

    private func handleControlWrite(_ value: Data, from central: CBCentral) -> CBATTError.Code {
        guard let shareSession else {
            setErrorResponse(for: central.identifier, code: "share_inactive", message: "QR share session is not active")
            return .success
        }
        guard let frame = try? JSONSerialization.jsonObject(with: value) as? [String: Any],
              let type = (frame["type"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) else {
            setErrorResponse(for: central.identifier, code: "invalid_json", message: "Malformed control payload")
            return .success
        }

        switch type {
        case P2pBleProtocol.typeClientHello:
            handleClientHello(frame, central: central, session: shareSession)
            return .success
        case P2pBleProtocol.typeClientFinish:
            handleClientFinish(frame, central: central, session: shareSession)
            return .success
        default:
            setErrorResponse(for: central.identifier, code: "unsupported_type", message: "Unsupported control frame")
            return .success
        }
    }

    private func handleClientHello(
        _ frame: [String: Any],
        central: CBCentral,
        session: P2pPublishedSession
    ) {
        guard let key = P2pBleProtocol.decodeBase64(session.aesKeyBase64) else {
            setErrorResponse(for: central.identifier, code: "invalid_key", message: "Server key unavailable")
            return
        }

        let shareId = ((frame["shareId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let clientSessionCode = ((frame["clientSessionCode"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let clientName = ((frame["clientName"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let clientAvatarBase64 = ((frame["avatarB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let clientDeviceId = ((frame["clientDeviceId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let clientNonce = ((frame["clientNonce"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let clientPlatform = ((frame["clientPlatform"] as? String) ?? "unknown").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let proof = ((frame["proof"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        guard
            shareId == session.shareId,
            !clientSessionCode.isEmpty,
            !clientDeviceId.isEmpty,
            !clientNonce.isEmpty,
            !proof.isEmpty
        else {
            setErrorResponse(for: central.identifier, code: "invalid_hello", message: "Missing client hello fields")
            return
        }

        let expectedProof = P2pBleProtocol.hmacBase64(
            key: key,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeClientHello),
                ("shareId", session.shareId),
                ("serverSessionCode", session.sessionCode),
                ("serverDeviceId", session.deviceId),
                ("serverNonce", session.serverNonce),
                ("clientSessionCode", clientSessionCode),
                ("clientDeviceId", clientDeviceId),
                ("clientNonce", clientNonce),
                ("clientName", clientName ?? ""),
                ("clientPlatform", clientPlatform)
            ])
        )
        guard P2pBleProtocol.secureEqualsBase64(expectedProof, proof) else {
            setErrorResponse(for: central.identifier, code: "auth_failed", message: "Client proof mismatch")
            return
        }

        // Optional: the scanner's internet identity, so we (the displayer) can reach them online.
        // Accepted ONLY when its separate HMAC verifies against the shared key + this session's
        // nonces; a missing or bad proof simply drops the identity (pairing still succeeds over BLE),
        // keeping older peers compatible and a BLE MITM unable to inject a forged identity.
        var clientPeerUid: String?
        var clientPeerPublicKey: String?
        let offeredPeerUid = ((frame["clientPeerUid"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let offeredPeerKey = ((frame["clientPeerPublicKey"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let identityProof = ((frame["clientIdentityProof"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if !offeredPeerUid.isEmpty, !offeredPeerKey.isEmpty, !identityProof.isEmpty {
            let expectedIdentityProof = P2pBleProtocol.hmacBase64(
                key: key,
                payload: P2pBleProtocol.buildClientIdentityProofPayload(
                    shareId: session.shareId,
                    serverNonce: session.serverNonce,
                    clientNonce: clientNonce,
                    peerUid: offeredPeerUid,
                    peerPublicKey: offeredPeerKey
                )
            )
            if P2pBleProtocol.secureEqualsBase64(expectedIdentityProof, identityProof) {
                clientPeerUid = offeredPeerUid
                clientPeerPublicKey = offeredPeerKey
                MessagingDiagLog.log("pairing(host): scanner identity ACCEPTED peer=\(offeredPeerUid.prefix(8)) — reciprocal contact will be internet-capable")
            } else {
                NSLog("ContactBroadcastManager: client identity proof mismatch; ignoring internet identity")
                MessagingDiagLog.log("pairing(host): scanner identity PROOF MISMATCH — reciprocal contact stays BLE-only")
            }
        } else {
            MessagingDiagLog.log("pairing(host): scanner sent NO internet identity (offeredUid=\(!offeredPeerUid.isEmpty) key=\(!offeredPeerKey.isEmpty) proof=\(!identityProof.isEmpty)) — reciprocal contact stays BLE-only")
        }

        guard let serverHelloProof = P2pBleProtocol.hmacBase64(
            key: key,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeServerHello),
                ("shareId", session.shareId),
                ("serverSessionCode", session.sessionCode),
                ("serverDeviceId", session.deviceId),
                ("serverNonce", session.serverNonce),
                ("clientSessionCode", clientSessionCode),
                ("clientDeviceId", clientDeviceId),
                ("clientNonce", clientNonce),
                ("clientName", clientName ?? ""),
                ("clientPlatform", clientPlatform),
                ("serverName", session.displayName ?? ""),
                ("serverPlatform", "ios")
            ])
        ) else {
            setErrorResponse(for: central.identifier, code: "server_error", message: "Failed to create server proof")
            return
        }

        pendingHandshakes[central.identifier] = P2pPendingHandshake(
            clientSessionCode: clientSessionCode,
            clientName: clientName,
            clientAvatarBase64: clientAvatarBase64,
            clientDeviceId: clientDeviceId,
            clientNonce: clientNonce,
            clientPlatform: clientPlatform,
            serverHelloProof: serverHelloProof,
            clientPeerUid: clientPeerUid,
            clientPeerPublicKey: clientPeerPublicKey
        )

        setDeviceResponse(
            for: central.identifier,
            payload: [
                "type": P2pBleProtocol.typeServerHello,
                "protocolVersion": P2pBleProtocol.protocolVersion,
                "shareId": session.shareId,
                "sessionCode": session.sessionCode,
                "serverDeviceId": session.deviceId,
                "serverNonce": session.serverNonce,
                "platform": "ios",
                "serverName": session.displayName as Any,
                "avatarB64": localAvatarPayload() as Any,
                "proof": serverHelloProof
            ]
        )
    }

    private func handleClientFinish(
        _ frame: [String: Any],
        central: CBCentral,
        session: P2pPublishedSession
    ) {
        guard
            let key = P2pBleProtocol.decodeBase64(session.aesKeyBase64),
            let pending = pendingHandshakes[central.identifier]
        else {
            setErrorResponse(for: central.identifier, code: "missing_state", message: "No pending handshake for device")
            return
        }

        let proof = ((frame["proof"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !proof.isEmpty else {
            setErrorResponse(for: central.identifier, code: "invalid_finish", message: "Missing finish proof")
            return
        }

        let expectedProof = P2pBleProtocol.hmacBase64(
            key: key,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeClientFinish),
                ("shareId", session.shareId),
                ("serverSessionCode", session.sessionCode),
                ("serverDeviceId", session.deviceId),
                ("serverNonce", session.serverNonce),
                ("clientSessionCode", pending.clientSessionCode),
                ("clientDeviceId", pending.clientDeviceId),
                ("clientNonce", pending.clientNonce),
                ("clientPlatform", pending.clientPlatform),
                ("serverHelloProof", pending.serverHelloProof)
            ])
        )
        guard P2pBleProtocol.secureEqualsBase64(expectedProof, proof) else {
            setErrorResponse(for: central.identifier, code: "auth_failed", message: "Client finish proof mismatch")
            return
        }

        pendingHandshakes.removeValue(forKey: central.identifier)
        setDeviceResponse(
            for: central.identifier,
            payload: [
                "type": P2pBleProtocol.typeServerFinish,
                "status": "ok",
                "shareId": session.shareId,
                "sessionCode": session.sessionCode,
                "serverDeviceId": session.deviceId
            ]
        )
        pendingShareCompletions[central.identifier] = P2pPendingShareCompletion(
            session: session,
            pending: pending
        )
    }

    private func handleMessageChunk(_ value: Data, from central: CBCentral) -> CBATTError.Code {
        let identifier = central.identifier
        let receiver = messageReceivers[identifier] ?? BleChunkReceiver(maxPacketSize: maxEnvelopePacketBytes)
        messageReceivers[identifier] = receiver

        do {
            guard let packet = try receiver.onChunk(value) else {
                return .success
            }
            messageReceivers.removeValue(forKey: identifier)
            let envelopeData = try BleAesGcm.unwrapTransportPacket(packet, maxPacketSize: maxEnvelopePacketBytes)
            if P2pCallProtocol.isCallAudioFrame(envelopeData) {
                // Binary voice-call audio fast path (peer is the central and wrote it to us);
                // must never reach the chat envelope parser.
                ChatPeerVoiceCallCoordinator.shared.handleGattCallAudio(envelopeData)
                return .success
            }
            guard let envelope = parseIncomingEnvelope(from: envelopeData) else {
                return .success
            }
            handleIncomingEnvelope(envelope, from: central)
            return .success
        } catch {
            messageReceivers.removeValue(forKey: identifier)
            return .unlikelyError
        }
    }

    private func handleIncomingEnvelope(_ envelope: P2pIncomingEnvelope, from central: CBCentral) {
        guard let record = ContactStore.shared.contactForRemoteDeviceId(envelope.fromDeviceId),
              let key = ContactStore.shared.aesKey(for: record) else {
            return
        }

        guard let plaintext = decryptChatPayload(
            envelope.encryptedPacket,
            key: key
        ),
        let payload = parseIncomingChatPayload(from: plaintext) else {
            return
        }

        let address = central.identifier.uuidString.uppercased()
        ContactStore.shared.updateBleAddress(sessionId: record.id, address: address)
        bindCentral(central.identifier, to: record)

        // Voice-call signaling from a central peer. Route it BEFORE the chat-kind switch —
        // these kinds used to fall into `default: break`, silently eating every incoming
        // call offer whenever the peer had dialed the link (they rang out "unreachable").
        // Replies must ride this peripheral link: the central-side manager has no connection.
        if P2pCallProtocol.isCallSignalKind(payload.kind) {
            if let rawPayload = (try? JSONSerialization.jsonObject(with: plaintext)) as? [String: Any] {
                ChatPeerVoiceCallCoordinator.shared.handleGattCallSignal(
                    sessionId: record.id,
                    payload: rawPayload,
                    link: callLink(for: record.id)
                )
            }
            return
        }

        switch payload.kind {
        case P2pBleProtocol.chatKindText:
            let trimmedText = payload.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            guard !trimmedText.isEmpty else { return }
            let notificationTitle = payload.senderName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                ?? record.name
            DispatchQueue.main.async {
                ContactStore.shared.markVerified(
                    sessionId: record.id,
                    verifiedIdentityKey: self.contactVerifiedIdentityKey(
                        for: record,
                        envelopeDeviceId: envelope.fromDeviceId
                    )
                )
                SOSChatStore.shared.ensureSession(
                    id: record.id,
                    displayName: record.name,
                    role: .unknown,
                    isVerified: true
                )
                if let senderName = payload.senderName?.trimmingCharacters(in: .whitespacesAndNewlines), !senderName.isEmpty {
                    SOSChatStore.shared.updateIdentity(
                        id: record.id,
                        displayName: senderName,
                        role: .unknown,
                        isVerified: true
                    )
                }
                if let locationPayload = P2pSharedTransferSupport.parseLocationMessage(trimmedText) {
                    let capturedAt = locationPayload.timestampMillis
                        .map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                        ?? Date()
                    _ = SOSChatStore.shared.upsertRemoteLocationMessage(
                        sessionId: record.id,
                        latitude: locationPayload.latitude,
                        longitude: locationPayload.longitude,
                        horizontalAccuracyMeters: locationPayload.accuracyMeters,
                        capturedAt: capturedAt,
                        transportMessageId: payload.messageId
                    )
                } else {
                    SOSChatStore.shared.appendRemoteMessage(
                        sessionId: record.id,
                        text: trimmedText,
                        transportMessageId: payload.messageId
                    )
                }
                SOSNotificationCenter.notifyIncomingMessage(
                    sessionId: record.id,
                    title: notificationTitle,
                    body: P2pSharedTransferSupport.previewText(for: trimmedText),
                    kind: .chatMessage
                )
            }
            sendChatEvent(
                kind: P2pBleProtocol.chatKindDelivered,
                messageId: payload.messageId,
                to: central,
                record: record
            )

        case P2pBleProtocol.chatKindRead:
            DispatchQueue.main.async {
                SOSChatStore.shared.markRead(
                    sessionId: record.id,
                    transportMessageIds: payload.messageId.map { [$0] }
                )
            }

        case P2pBleProtocol.chatKindVoiceInit,
             P2pBleProtocol.chatKindVoiceChunk,
             P2pBleProtocol.chatKindVoiceDone,
             P2pBleProtocol.chatKindVoiceAbort:
            handleIncomingVoicePayload(payload, from: central, record: record)

        case P2pBleProtocol.chatKindImageInit,
             P2pBleProtocol.chatKindImageChunk,
             P2pBleProtocol.chatKindImageDone,
             P2pBleProtocol.chatKindImageAbort:
            handleIncomingImagePayload(payload, from: central, record: record)

        case P2pBleProtocol.chatKindFileInit,
             P2pBleProtocol.chatKindFileChunk,
             P2pBleProtocol.chatKindFileDone,
             P2pBleProtocol.chatKindFileAbort:
            handleIncomingFilePayload(payload)

        default:
            break
        }
    }

    private func sendChatEvent(
        kind: String,
        messageId: String?,
        to central: CBCentral,
        record: ContactRecord
    ) {
        guard let manager = peripheralManager,
              let messageOutCharacteristic,
              let transport = buildChatTransportPayload(
                kind: kind,
                messageId: messageId,
                text: nil,
                displayName: nil,
                mimeType: nil,
                durationMillis: nil,
                width: nil,
                height: nil,
                originalSizeBytes: nil,
                totalBytes: nil,
                totalChunks: nil,
                sha256: nil,
                chunkIndex: nil,
                chunkData: nil,
                record: record
              ) else {
            return
        }
        queueNotification(transport, for: messageOutCharacteristic, to: central, peripheral: manager)
    }

    private func sendChatPayload(
        kind: String,
        messageId: String?,
        text: String?,
        displayName: String? = nil,
        mimeType: String?,
        durationMillis: Int?,
        width: Int?,
        height: Int?,
        originalSizeBytes: Int? = nil,
        totalBytes: Int?,
        totalChunks: Int?,
        sha256: String?,
        chunkIndex: Int?,
        chunkData: Data?,
        sessionId: UUID
    ) -> Bool {
        guard let manager = peripheralManager,
              let messageOutCharacteristic,
              let record = ContactStore.shared.contact(for: sessionId),
              let central = connectedCentral(for: sessionId),
              let transport = buildChatTransportPayload(
                kind: kind,
                messageId: messageId,
                text: text,
                displayName: displayName,
                mimeType: mimeType,
                durationMillis: durationMillis,
                width: width,
                height: height,
                originalSizeBytes: originalSizeBytes,
                totalBytes: totalBytes,
                totalChunks: totalChunks,
                sha256: sha256,
                chunkIndex: chunkIndex,
                chunkData: chunkData,
                record: record
              ) else {
            return false
        }
        queueNotification(transport, for: messageOutCharacteristic, to: central, peripheral: manager)
        return true
    }

    private func buildChatTransportPayload(
        kind: String,
        messageId: String?,
        text: String?,
        displayName: String?,
        mimeType: String?,
        durationMillis: Int?,
        width: Int?,
        height: Int?,
        originalSizeBytes: Int?,
        totalBytes: Int?,
        totalChunks: Int?,
        sha256: String?,
        chunkIndex: Int?,
        chunkData: Data?,
        record: ContactRecord
    ) -> Data? {
        guard let key = ContactStore.shared.aesKey(for: record) else {
            return nil
        }

        let innerPayload: [String: Any?] = [
            "kind": kind,
            "messageId": messageId,
            "text": text,
            "displayName": displayName,
            "mimeType": mimeType,
            "durationMillis": durationMillis,
            "width": width,
            "height": height,
            "originalSizeBytes": originalSizeBytes,
            "totalBytes": totalBytes,
            "totalChunks": totalChunks,
            "sha256": sha256,
            "chunkIndex": chunkIndex,
            "chunkData": chunkData?.base64EncodedString(),
            "senderName": localDisplayName().nilIfEmpty
        ]
        let innerCompact = innerPayload.compactMapValues { $0 }
        guard let innerData = try? JSONSerialization.data(withJSONObject: innerCompact, options: []),
              let encryptedPacket = BleAesGcm.encryptPacket(
                key: key,
                plaintext: innerData,
                maxPacketSize: maxEncryptedChatPacketBytes
              ).nilIfEmptyData else {
            return nil
        }

        let outerPayload: [String: Any] = [
            "type": P2pBleProtocol.typeChatEnvelope,
            "fromDeviceId": localDeviceId(),
            "payload": encryptedPacket.base64EncodedString()
        ]
        guard let outerData = try? JSONSerialization.data(withJSONObject: outerPayload, options: []),
              outerData.count <= maxEnvelopePacketBytes else {
            return nil
        }

        return BleAesGcm.wrapTransportPacket(outerData)
    }

    // MARK: - Voice-call link (peripheral role)

    // The peer dialed our hosted chat service, so call replies (ring/accept/end) and outbound
    // audio must ride MESSAGE_OUT notifications to that central — the central-side chat manager
    // has no connection in this topology. One adapter per session keeps `GattCallLink` identity
    // stable across a call.
    private var peripheralCallLinks: [UUID: P2pPeripheralGattCallLink] = [:]

    /// The `GattCallLink` riding this hosted (peripheral-role) session — outgoing calls must
    /// use it when the peer dialed us, instead of tearing the link down with a central dial.
    func callLink(for sessionId: UUID) -> GattCallLink {
        if let existing = peripheralCallLinks[sessionId] { return existing }
        let link = P2pPeripheralGattCallLink(sessionId: sessionId)
        peripheralCallLinks[sessionId] = link
        return link
    }

    fileprivate func sendCallSignal(sessionId: UUID, payload: [String: Any]) -> Bool {
        syncOnQueue {
            guard let peripheral = peripheralManager,
                  let characteristic = messageOutCharacteristic,
                  let central = connectedCentral(for: sessionId),
                  let record = ContactStore.shared.contact(for: sessionId),
                  let packet = makeCallEnvelope(payload, record: record) else {
                return false
            }
            queueNotification(packet, for: characteristic, to: central, peripheral: peripheral)
            return true
        }
    }

    /// Single-notification audio fast path; false when the link cannot take the packet right
    /// now so the caller can count the drop (mirrors the central manager's write path).
    fileprivate func sendCallAudioFrame(sessionId: UUID, packet: Data) -> Bool {
        syncOnQueue {
            guard let peripheral = peripheralManager,
                  let characteristic = messageOutCharacteristic,
                  let central = connectedCentral(for: sessionId) else {
                return false
            }
            // Never interleave into a partially-notified chunked packet — that would corrupt
            // the central's reassembly stream. Drop the frame instead; audio tolerates loss.
            let key = P2pPendingNotificationKey(
                centralId: central.identifier,
                characteristicId: characteristic.uuid
            )
            guard pendingNotifications[key] == nil else { return false }
            let wrapped = BleAesGcm.wrapTransportPacket(packet)
            guard wrapped.count <= max(1, central.maximumUpdateValueLength) else { return false }
            return peripheral.updateValue(wrapped, for: characteristic, onSubscribedCentrals: [central])
        }
    }

    fileprivate func setCallHold(sessionId: UUID, active: Bool) {
        // The central manager's hold pins ITS dialed connection open; in the peripheral role
        // the peer owns the connection and our hosted service stays up while the app runs,
        // so there is nothing to pin. Kept for GattCallLink parity.
        _ = (sessionId, active)
    }

    private func makeCallEnvelope(_ innerPayload: [String: Any], record: ContactRecord) -> Data? {
        guard let key = ContactStore.shared.aesKey(for: record),
              let innerData = try? JSONSerialization.data(withJSONObject: innerPayload, options: []),
              let encryptedPacket = BleAesGcm.encryptPacket(
                key: key,
                plaintext: innerData,
                maxPacketSize: maxEncryptedChatPacketBytes
              ).nilIfEmptyData else {
            return nil
        }
        let outerPayload: [String: Any] = [
            "type": P2pBleProtocol.typeChatEnvelope,
            "fromDeviceId": localDeviceId(),
            "payload": encryptedPacket.base64EncodedString()
        ]
        guard let outerData = try? JSONSerialization.data(withJSONObject: outerPayload, options: []),
              outerData.count <= maxEnvelopePacketBytes else {
            return nil
        }
        return BleAesGcm.wrapTransportPacket(outerData)
    }

    private func queueNotification(
        _ payload: Data,
        for characteristic: CBMutableCharacteristic,
        to central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        guard !payload.isEmpty else { return }
        let centralId = central.identifier
        let key = P2pPendingNotificationKey(centralId: centralId, characteristicId: characteristic.uuid)

        guard subscribedMessageOutCentrals[centralId] != nil else {
            pendingMessageOutValues[centralId, default: []].append(payload)
            return
        }
        guard pendingNotifications[key] == nil else {
            pendingMessageOutValues[centralId, default: []].append(payload)
            return
        }

        if let nextOffset = sendChunks(
            payload,
            startingAt: 0,
            to: central,
            characteristic: characteristic,
            peripheral: peripheral
        ) {
            pendingNotifications[key] = P2pPendingNotification(payload: payload, offset: nextOffset)
        } else {
            pendingNotifications.removeValue(forKey: key)
            flushQueuedMessageOutValues(for: centralId, peripheral: peripheral)
        }
    }

    private func flushQueuedMessageOutValues(for centralId: UUID, peripheral: CBPeripheralManager) {
        guard let characteristic = messageOutCharacteristic,
              let central = subscribedMessageOutCentrals[centralId] else {
            return
        }
        let key = P2pPendingNotificationKey(centralId: centralId, characteristicId: characteristic.uuid)
        guard pendingNotifications[key] == nil else { return }

        while pendingNotifications[key] == nil {
            guard var queued = pendingMessageOutValues[centralId], let nextPayload = queued.first else {
                pendingMessageOutValues.removeValue(forKey: centralId)
                break
            }
            queued.removeFirst()
            if queued.isEmpty {
                pendingMessageOutValues.removeValue(forKey: centralId)
            } else {
                pendingMessageOutValues[centralId] = queued
            }

            if let nextOffset = sendChunks(
                nextPayload,
                startingAt: 0,
                to: central,
                characteristic: characteristic,
                peripheral: peripheral
            ) {
                pendingNotifications[key] = P2pPendingNotification(payload: nextPayload, offset: nextOffset)
                break
            }
        }
    }

    private func flushPendingNotifications(_ peripheral: CBPeripheralManager) {
        guard let characteristic = messageOutCharacteristic, !pendingNotifications.isEmpty else { return }
        let pending = pendingNotifications
        for (key, entry) in pending {
            guard key.characteristicId == characteristic.uuid,
                  let central = subscribedMessageOutCentrals[key.centralId] else {
                pendingNotifications.removeValue(forKey: key)
                continue
            }
            if let nextOffset = sendChunks(
                entry.payload,
                startingAt: entry.offset,
                to: central,
                characteristic: characteristic,
                peripheral: peripheral
            ) {
                pendingNotifications[key]?.offset = nextOffset
            } else {
                pendingNotifications.removeValue(forKey: key)
                flushQueuedMessageOutValues(for: key.centralId, peripheral: peripheral)
            }
        }
    }

    private func sendChunks(
        _ payload: Data,
        startingAt offset: Int,
        to central: CBCentral,
        characteristic: CBMutableCharacteristic,
        peripheral: CBPeripheralManager
    ) -> Int? {
        var currentOffset = offset
        let maxLength = max(1, central.maximumUpdateValueLength)
        while currentOffset < payload.count {
            let end = min(currentOffset + maxLength, payload.count)
            let chunk = payload.subdata(in: currentOffset..<end)
            if !peripheral.updateValue(chunk, for: characteristic, onSubscribedCentrals: [central]) {
                return currentOffset
            }
            currentOffset = end
        }
        return nil
    }

    private func parseIncomingEnvelope(from data: Data) -> P2pIncomingEnvelope? {
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
        return P2pIncomingEnvelope(fromDeviceId: fromDeviceId, encryptedPacket: encryptedPacket)
    }

    private func parseIncomingChatPayload(from data: Data) -> P2pIncomingChatPayload? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let kind = (object["kind"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        else {
            return nil
        }
        return P2pIncomingChatPayload(
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

    private func decryptChatPayload(_ encryptedPacket: Data, key: SymmetricKey) -> Data? {
        if let sealedBox = try? AES.GCM.SealedBox(combined: encryptedPacket),
           let plaintext = try? AES.GCM.open(sealedBox, using: key) {
            return plaintext
        }
        return try? BleAesGcm.decryptPacket(
            key: key,
            transportPacket: encryptedPacket,
            maxPacketSize: maxEncryptedChatPacketBytes
        )
    }

    private func handleIncomingVoicePayload(
        _ payload: P2pIncomingChatPayload,
        from central: CBCentral,
        record: ContactRecord
    ) {
        guard let messageId = payload.messageId?.nilIfEmpty else { return }

        switch payload.kind {
        case P2pBleProtocol.chatKindVoiceInit:
            guard
                let mimeType = payload.mimeType?.nilIfEmpty,
                let durationMillis = payload.durationMillis,
                let totalChunks = payload.totalChunks,
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
            completeIncomingVoiceTransferIfReady(
                messageId: messageId,
                from: central,
                record: record,
                senderName: payload.senderName
            )
        case P2pBleProtocol.chatKindVoiceDone:
            guard var transfer = incomingVoiceTransfers[messageId] else { return }
            transfer.receivedDone = true
            incomingVoiceTransfers[messageId] = transfer
            completeIncomingVoiceTransferIfReady(
                messageId: messageId,
                from: central,
                record: record,
                senderName: payload.senderName
            )
        case P2pBleProtocol.chatKindVoiceAbort:
            incomingVoiceTransfers.removeValue(forKey: messageId)
        default:
            break
        }
    }

    private func completeIncomingVoiceTransferIfReady(
        messageId: String,
        from central: CBCentral,
        record: ContactRecord,
        senderName: String?
    ) {
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
        DispatchQueue.main.async {
            ContactStore.shared.markVerified(
                sessionId: record.id,
                verifiedIdentityKey: self.contactVerifiedIdentityKey(for: record)
            )
            if let senderName = senderName?.nilIfEmpty {
                SOSChatStore.shared.updateIdentity(
                    id: record.id,
                    displayName: senderName,
                    role: .unknown,
                    isVerified: true
                )
            }
            _ = SOSChatStore.shared.appendRemoteAudioMessage(
                sessionId: record.id,
                audioRelativePath: relativePath,
                durationMillis: transfer.durationMillis,
                transportMessageId: messageId
            )
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: record.id,
                title: senderName?.nilIfEmpty ?? record.name,
                body: NSLocalizedString("Voice message", comment: ""),
                kind: .chatMessage
            )
        }
        sendChatEvent(
            kind: P2pBleProtocol.chatKindDelivered,
            messageId: messageId,
            to: central,
            record: record
        )
    }

    private func handleIncomingImagePayload(
        _ payload: P2pIncomingChatPayload,
        from central: CBCentral,
        record: ContactRecord
    ) {
        guard let messageId = payload.messageId?.nilIfEmpty else { return }

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
            completeIncomingImageTransferIfReady(
                messageId: messageId,
                from: central,
                record: record,
                senderName: payload.senderName
            )
        case P2pBleProtocol.chatKindImageDone:
            guard var transfer = incomingImageTransfers[messageId] else { return }
            transfer.receivedDone = true
            incomingImageTransfers[messageId] = transfer
            completeIncomingImageTransferIfReady(
                messageId: messageId,
                from: central,
                record: record,
                senderName: payload.senderName
            )
        case P2pBleProtocol.chatKindImageAbort:
            incomingImageTransfers.removeValue(forKey: messageId)
        default:
            break
        }
    }

    private func completeIncomingImageTransferIfReady(
        messageId: String,
        from central: CBCentral,
        record: ContactRecord,
        senderName: String?
    ) {
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
        DispatchQueue.main.async {
            ContactStore.shared.markVerified(
                sessionId: record.id,
                verifiedIdentityKey: self.contactVerifiedIdentityKey(for: record)
            )
            if let senderName = senderName?.nilIfEmpty {
                SOSChatStore.shared.updateIdentity(
                    id: record.id,
                    displayName: senderName,
                    role: .unknown,
                    isVerified: true
                )
            }
            _ = SOSChatStore.shared.appendRemoteImageMessage(
                sessionId: record.id,
                imageRelativePath: imageRelativePath,
                thumbnailRelativePath: thumbnailRelativePath,
                imageWidth: transfer.width,
                imageHeight: transfer.height,
                imageMimeType: transfer.mimeType,
                transportMessageId: messageId
            )
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: record.id,
                title: senderName?.nilIfEmpty ?? record.name,
                body: SOSChatStore.imagePreviewText(),
                kind: .chatMessage
            )
        }
        sendChatEvent(
            kind: P2pBleProtocol.chatKindDelivered,
            messageId: messageId,
            to: central,
            record: record
        )
    }

    private func handleIncomingFilePayload(_ payload: P2pIncomingChatPayload) {
        guard let messageId = payload.messageId?.nilIfEmpty else { return }

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

    private func completeIncomingFileTransferIfReady(messageId: String) {
        guard let transfer = incomingFileTransfers[messageId],
              let fileData = transfer.composedData(),
              P2pSharedTransferSupport.persistSharedDocumentData(
                fileData,
                messageId: transfer.messageId,
                displayName: transfer.displayName
              ) != nil else {
            return
        }
        incomingFileTransfers.removeValue(forKey: messageId)
    }

    private func contactVerifiedIdentityKey(
        for record: ContactRecord,
        envelopeDeviceId: String? = nil
    ) -> String? {
        envelopeDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? record.verifiedIdentityKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? record.remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
    }

    private func persistAuthenticatedPeer(
        session: P2pPublishedSession,
        centralId: UUID,
        pending: P2pPendingHandshake
    ) {
        let address = centralId.uuidString.uppercased()

        // Self-identity guard — the mirror of the Android host-side one. Two failures, two answers:
        //  * same DEVICE (our own device id or session code echoed back) is a loopback; there is no
        //    peer, so anything we insert is a contact that is really us. Hard reject.
        //  * same ACCOUNT on a genuinely different device (one responder with a phone AND a tablet)
        //    is legitimate, so keep the Bluetooth link — but drop the internet identity, because a
        //    contact whose peerUid is our own uid routes every internet send back into our own inbox.
        //    An SOS addressed to yourself helps nobody.
        // Non-blank gated throughout: a signed-out device supplies no uid, and "" == "" would reject
        // every offline pairing — the exact case this transport exists for.
        let localDeviceId = session.deviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        let incomingDeviceId = (pending.clientDeviceId ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let localSessionCode = session.sessionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        let incomingSessionCode = pending.clientSessionCode
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if (!incomingDeviceId.isEmpty
                && incomingDeviceId.caseInsensitiveCompare(localDeviceId) == .orderedSame)
            || (!incomingSessionCode.isEmpty
                && incomingSessionCode.caseInsensitiveCompare(localSessionCode) == .orderedSame) {
            MessagingDiagLog.log("pairing rejected: the peer identity is this device")
            return
        }
        let localUid = (Auth.auth().currentUser?.uid ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let incomingUid = (pending.clientPeerUid ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let sameAccount = !localUid.isEmpty && !incomingUid.isEmpty && localUid == incomingUid

        let peerSessionCode = canonicalBleSessionCode(
            deviceId: pending.clientDeviceId,
            fallbackSessionCode: pending.clientSessionCode,
            fallbackAddress: address
        )
        let record = ContactStore.shared.upsertBleContact(
            name: pending.clientName ?? pending.clientSessionCode,
            sessionCode: peerSessionCode,
            aesKeyBase64: session.aesKeyBase64,
            isVerified: true,
            verifiedIdentityKey: pending.clientDeviceId,
            verifiedAt: Date(),
            remoteSessionCode: pending.clientSessionCode,
            remotePlatform: ContactRemotePlatform.normalize(pending.clientPlatform),
            bleShareId: nil,
            lastKnownBleAddress: address,
            remoteDeviceId: pending.clientDeviceId,
            // Reciprocal internet identity from the authenticated client-hello, so this side can
            // fall back to the E2E internet transport when Bluetooth is off (the whole point of the
            // fix). Nil for peers that didn't supply it — those stay BLE-only as before.
            peerUid: sameAccount ? nil : pending.clientPeerUid,
            peerPublicKey: sameAccount ? nil : pending.clientPeerPublicKey,
            analyticsSource: "ble_gatt_peer",
            analyticsReceived: true
        )
        bindCentral(centralId, to: record)
        DispatchQueue.main.async {
            SOSChatStore.shared.ensureSession(
                id: record.id,
                displayName: record.name,
                role: .unknown,
                isVerified: true
            )
            SOSChatStore.shared.updateIdentity(
                id: record.id,
                displayName: record.name,
                role: .unknown,
                isVerified: true,
                avatarBase64: pending.clientAvatarBase64
            )
            NotificationCenter.default.post(
                name: .p2pShareDidAddContact,
                object: nil,
                userInfo: [
                    "contactId": record.id
                ]
            )
        }
    }

    private func finalizeShareCompletionIfNeeded(for centralId: UUID, deliveredPayload: Data) {
        guard isServerFinishPayload(deliveredPayload),
              let completion = pendingShareCompletions.removeValue(forKey: centralId) else {
            return
        }

        // Keep the QR-share GATT service alive briefly after returning the final
        // ATT read response so the central can finish processing `server_finish`.
        queue.asyncAfter(deadline: .now() + .milliseconds(250)) { [weak self] in
            self?.persistAuthenticatedPeer(
                session: completion.session,
                centralId: centralId,
                pending: completion.pending
            )
        }
    }

    private func isServerFinishPayload(_ data: Data) -> Bool {
        guard
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = (object["type"] as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
                .nilIfEmpty
        else {
            return false
        }
        return type == P2pBleProtocol.typeServerFinish
    }

    private func canonicalBleSessionCode(
        deviceId: String,
        fallbackSessionCode: String,
        fallbackAddress: String
    ) -> String {
        let raw = deviceId.nilIfEmpty ?? fallbackSessionCode.nilIfEmpty ?? fallbackAddress
        if raw.lowercased().hasPrefix("ble:") {
            return raw
        }
        return "ble:\(raw)"
    }

    private func setDeviceResponse(for identifier: UUID, payload: [String: Any?]) {
        let compact = payload.compactMapValues { $0 }
        guard let data = try? JSONSerialization.data(withJSONObject: compact, options: []) else { return }
        deviceResponses[identifier] = data
    }

    private func setErrorResponse(for identifier: UUID, code: String, message: String) {
        setDeviceResponse(
            for: identifier,
            payload: [
                "type": P2pBleProtocol.typeError,
                "code": code,
                "message": message
            ]
        )
    }

    private func connectedCentral(for sessionId: UUID) -> CBCentral? {
        for (centralId, mappedSessionId) in centralSessionIds where mappedSessionId == sessionId {
            if let central = subscribedMessageOutCentrals[centralId] {
                return central
            }
        }
        if foregroundSessionId == sessionId,
           subscribedMessageOutCentrals.count == 1,
           let central = subscribedMessageOutCentrals.values.first {
            bindCentral(central.identifier, to: sessionId)
            return central
        }
        return nil
    }

    private func bindCentral(_ centralId: UUID, to record: ContactRecord) {
        bindCentral(centralId, to: record.id)
    }

    private func bindCentral(_ centralId: UUID, to sessionId: UUID) {
        centralSessionIds[centralId] = sessionId
        publishConnectedSessions()
    }

    private func ensureTrackedCentral(_ central: CBCentral) {
        let centralId = central.identifier
        guard subscribedMessageOutCentrals[centralId] != nil else { return }
        if centralSessionIds[centralId] != nil {
            publishConnectedSessions()
        }
        if let peripheralManager {
            flushQueuedMessageOutValues(for: centralId, peripheral: peripheralManager)
        }
    }

    private func publishConnectedSessions() {
        let next = Set(centralSessionIds.compactMap { centralId, sessionId in
            subscribedMessageOutCentrals[centralId] == nil ? nil : sessionId
        })
        DispatchQueue.main.async { [weak self] in
            self?.connectedSessionIds = next
        }
    }

    private func localDeviceId() -> String {
        SecureLocalStore.shared.getOrCreateP2pDeviceId()
    }

    private func localDisplayName() -> String {
        ProfileMetadataStore.preferredDisplayName() ?? "Crisis Connect"
    }

    private func localAvatarPayload() -> String? {
        guard let data = ProfileMetadataStore.loadAvatarThumbnailData(), !data.isEmpty else {
            return nil
        }
        let encoded = data.base64EncodedString().trimmingCharacters(in: .whitespacesAndNewlines)
        return encoded.nilIfEmpty
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync { block() }
    }

    private static func makeShareBootstrapPayload(
        shareId: String,
        sessionCode: String,
        displayName: String?,
        serverNonce: String,
        deviceId: String,
        avatarBase64: String?
    ) -> Data {
        let payload: [String: Any?] = [
            "shareId": shareId,
            "sessionCode": sessionCode,
            "platform": "ios",
            "protocolVersion": P2pBleProtocol.protocolVersion,
            "serverNonce": serverNonce,
            "serverDeviceId": deviceId,
            "name": displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            "avatarB64": avatarBase64?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        ]
        let compact = payload.compactMapValues { $0 }
        return (try? JSONSerialization.data(withJSONObject: compact, options: [])) ?? Data()
    }

    private static func makeHostBootstrapPayload(
        deviceId: String,
        displayName: String,
        avatarBase64: String?
    ) -> Data {
        let payload: [String: Any?] = [
            "mode": "host",
            "platform": "ios",
            "protocolVersion": P2pBleProtocol.protocolVersion,
            "serverDeviceId": deviceId,
            "name": displayName.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            "avatarB64": avatarBase64?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        ]
        let compact = payload.compactMapValues { $0 }
        return (try? JSONSerialization.data(withJSONObject: compact, options: [])) ?? Data()
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

extension Notification.Name {
    static let p2pShareDidAddContact = Notification.Name("p2pShareDidAddContact")
}

/// `GattCallLink` over the hosted (peripheral-role) chat service: replies and audio go out as
/// MESSAGE_OUT notifications to the central bound to this session. Counterpart of
/// `P2pGattChatManager`'s conformance for links this device dialed itself.
final class P2pPeripheralGattCallLink: GattCallLink {
    private let sessionId: UUID

    fileprivate init(sessionId: UUID) {
        self.sessionId = sessionId
    }

    @discardableResult
    func sendCallSignal(_ payload: [String: Any]) -> Bool {
        ContactBroadcastManager.shared.sendCallSignal(sessionId: sessionId, payload: payload)
    }

    @discardableResult
    func sendCallAudioFrame(_ packet: Data) -> Bool {
        ContactBroadcastManager.shared.sendCallAudioFrame(sessionId: sessionId, packet: packet)
    }

    func setCallHold(_ active: Bool) {
        ContactBroadcastManager.shared.setCallHold(sessionId: sessionId, active: active)
    }
}
