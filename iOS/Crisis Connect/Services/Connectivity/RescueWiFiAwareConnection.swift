//
//  RescueWiFiAwareConnection.swift
//  Crisis Connect
//
//  Created by Assistant on 22.02.2026.
//

import Foundation
import CryptoKit
import Network
import UIKit
import WiFiAware

@available(iOS 26.0, *)
final class RescueWiFiAwareConnection {
    private final class ImmediateSendResult: @unchecked Sendable {
        private let semaphore = DispatchSemaphore(value: 0)
        var success = false

        func finish(success: Bool) {
            self.success = success
            semaphore.signal()
        }

        func wait(timeout: TimeInterval) -> DispatchTimeoutResult {
            semaphore.wait(timeout: .now() + timeout)
        }
    }

    private struct LiveLocationPayload {
        let messageId: String?
        let latitude: Double
        let longitude: Double
        let horizontalAccuracyMeters: Double?
        let capturedAt: Date
    }

    let sessionId: UUID
    var canInitiateConnection: Bool { endpoint != nil }

    var onStatusChange: ((RescueConnectionStatus) -> Void)?
    var onBroadcastId: ((String) -> Void)?
    var onPeerName: ((String) -> Void)?
    var onSignalLocation: ((SOSSignalLocationPayload?) -> Void)?

    private let queue: DispatchQueue
    private let endpoint: WAEndpoint?
    private let roleProofCreator = RoleProofCreator(allowExpiredCertificate: false)
    private let roleProofVerifier = RoleProofVerifier(maxExpiredCertificateGraceMillis: 0)

    private let maxRoleProofPacketBytes = 16_384
    private let maxSecurePacketBytes = 32_767
    private let textMessageTtlMillis: Int64 = 86_400_000
    private let outboundRouteWiFiAware = "wifi_aware_secure"
    private let securePacketReceiver = BleChunkReceiver(maxPacketSize: 32_767)
    private let immediateSendTimeout: TimeInterval = 5
    private let queueKey = DispatchSpecificKey<Void>()

    private var connection: NetworkConnection<TCP>?
    private var receiveTask: Task<Void, Never>?
    private var authTimeoutWork: DispatchWorkItem?

    private var pendingTransportPackets: [Data] = []
    private var isSendingPacket = false

    private var sessionKey: SymmetricKey?
    private var localKey: P256.KeyAgreement.PrivateKey?
    private var localPublicKeyData: Data?
    private var sessionNonce: String?
    private var hasSentRoleProof = false
    private var hasVerifiedPeer = false
    private var hasSentPeerIdentity = false
    private var verifiedPeerRole: SOSChatRole = .unknown
    private var incomingVoiceTransfers: [String: BleVoicePayload.IncomingTransfer] = [:]
    private var incomingImageTransfers: [String: BleImagePayload.IncomingTransfer] = [:]
    private var incomingFileTransfers: [String: BleFilePayload.IncomingTransfer] = [:]

    init(
        sessionId: UUID,
        endpoint: WAEndpoint?,
        queue: DispatchQueue
    ) {
        self.sessionId = sessionId
        self.endpoint = endpoint
        self.queue = queue
        self.queue.setSpecific(key: queueKey, value: ())
    }

    func connect() {
        queue.async { [weak self] in
            guard let self else { return }
            guard self.connection == nil, let endpoint else { return }
            let connection = NetworkConnection(to: endpoint) { TCP() }
            self.bind(connection)
        }
    }

    func attachIncoming(_ connection: NetworkConnection<TCP>) {
        queue.async { [weak self] in
            self?.bind(connection)
        }
    }

    func disconnect() {
        queue.async { [weak self] in
            guard let self else { return }
            self.receiveTask?.cancel()
            self.receiveTask = nil
            self.connection = nil
            self.resetSessionState()
            self.updateStatus(.disconnected)
        }
    }

    func sendMessage(_ text: String, transportMessageId: String) -> Bool {
        syncOnQueue {
            let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { return false }
            let payloadString = BleChatEnvelope.encodeChat(
                messageId: transportMessageId,
                text: trimmed,
                createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
                ttlMillis: textMessageTtlMillis,
                attempt: 1,
                route: outboundRouteWiFiAware
            )
            return sendEncryptedData(Data(payloadString.utf8), requireConfirmedWrite: true)
        }
    }

    func sendMessage(_ text: String) -> Bool {
        sendMessage(text, transportMessageId: "wifi-aware-\(UUID().uuidString.lowercased())")
    }

    func sendReadReceipt(transportMessageIds: [String]) -> Bool {
        syncOnQueue {
            sendEncryptedData(
                BleChatEnvelope.encodeReadAck(messageIds: transportMessageIds),
                requireConfirmedWrite: true
            )
        }
    }

    func sendReadReceipt() -> Bool {
        sendReadReceipt(transportMessageIds: [])
    }

    func sendImageMessage(
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String
    ) -> Bool {
        syncOnQueue {
            guard let imageData = SOSChatStore.loadImageData(fileName: imageFileName),
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
            var didQueueAny = false
            for packet in packets {
                guard sendEncryptedData(Data(packet.utf8), requireConfirmedWrite: true) else {
                    return false
                }
                didQueueAny = true
            }
            return didQueueAny
        }
    }

    func sendVoiceMessage(
        audioFileName: String,
        mimeType: String,
        durationMillis: Int,
        messageId: String
    ) -> Bool {
        syncOnQueue {
            guard let audioData = SOSChatStore.loadVoiceData(fileName: audioFileName),
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
            var didQueueAny = false
            for packet in packets {
                guard sendEncryptedData(Data(packet.utf8), requireConfirmedWrite: true) else {
                    return false
                }
                didQueueAny = true
            }
            return didQueueAny
        }
    }

    func sendFileMessage(
        data: Data,
        displayName: String,
        mimeType: String?,
        originalSizeBytes: Int,
        messageId: String
    ) -> Bool {
        syncOnQueue {
            guard !data.isEmpty else { return false }
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
            var didQueueAny = false
            for packet in packets {
                guard sendEncryptedData(Data(packet.utf8), requireConfirmedWrite: true) else {
                    return false
                }
                didQueueAny = true
            }
            return didQueueAny
        }
    }

    func sendLiveLocation(
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        messageId: String
    ) -> Bool {
        syncOnQueue {
            var packet: [String: Any] = [
                "kind": "live_location",
                "messageId": messageId,
                "latitude": latitude,
                "longitude": longitude,
                "timestampMillis": Int64(capturedAt.timeIntervalSince1970 * 1000)
            ]
            if let horizontalAccuracyMeters {
                packet["accuracyMeters"] = horizontalAccuracyMeters
            }
            return sendEncryptedJSONObject(packet, requireConfirmedWrite: true)
        }
    }

    private func bind(_ connection: NetworkConnection<TCP>) {
        self.connection = connection
        resetSessionState()
        updateStatus(.connecting)
        connection.onStateUpdate { [weak self] _, state in
            self?.handleConnectionState(state)
        }
        connection.onPathUpdate { [weak self] _, path in
            guard let self else { return }
            Task {
                guard let awarePath = try? await path.wifiAware else { return }
                let peerName = awarePath.endpoint.device.name?
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard let peerName, !peerName.isEmpty else { return }
                self.queue.async { [weak self] in
                    self?.onPeerName?(peerName)
                }
            }
        }
        startReceiveLoopIfNeeded(with: connection)
    }

    private func handleConnectionState(_ state: NetworkChannel<TCP>.State) {
        queue.async { [weak self] in
            guard let self else { return }
            switch state {
            case .setup, .preparing, .waiting:
                self.updateStatus(.connecting)
            case .ready:
                self.updateStatus(.connected)
                DispatchQueue.main.async {
                    SOSChatStore.shared.ensureSession(id: self.sessionId, role: .unknown)
                }
                self.beginAuthenticationIfNeeded()
            case .failed:
                self.failConnection()
            case .cancelled:
                self.receiveTask?.cancel()
                self.receiveTask = nil
                self.connection = nil
                self.resetSessionState()
                self.updateStatus(.disconnected)
            @unknown default:
                self.failConnection()
            }
        }
    }

    private func beginAuthenticationIfNeeded() {
        guard connection != nil else { return }
        guard sessionKey == nil, !hasVerifiedPeer else { return }
        scheduleAuthTimeout()
        updateStatus(.authenticating)
        if endpoint != nil {
            sendLocalHelloIfNeeded()
        }
    }

    private func sendLocalHelloIfNeeded() {
        guard connection != nil else { return }
        guard localKey == nil, localPublicKeyData == nil, sessionNonce == nil else { return }
        let localKey = P256.KeyAgreement.PrivateKey()
        let publicKeyData = makeX509PublicKeyData(from: localKey.publicKey)
        let sessionNonce = UUID().uuidString.lowercased()
        self.localKey = localKey
        self.localPublicKeyData = publicKeyData
        self.sessionNonce = sessionNonce
        let payload: [String: Any] = [
            "kind": "auth_hello",
            "protocol": "cc_rescue_secure_v1",
            "publicKey": publicKeyData.base64EncodedString(),
            "sessionNonce": sessionNonce
        ]
        guard sendPlaintextJSONObject(payload) else {
            failConnection()
            return
        }
    }

    private func startReceiveLoopIfNeeded(with connection: NetworkConnection<TCP>) {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            guard let self else { return }
            do {
                while !Task.isCancelled {
                    let message = try await connection.receive(atLeast: 1, atMost: 4096)
                    let chunk = Data(message.content)
                    if chunk.isEmpty {
                        continue
                    }
                    self.queue.async { [weak self] in
                        self?.consume(chunk)
                    }
                }
            } catch is CancellationError {
                return
            } catch {
                self.queue.async { [weak self] in
                    self?.failConnection()
                }
            }
        }
    }

    private func consume(_ chunk: Data) {
        do {
            if let packet = try securePacketReceiver.onChunk(chunk) {
                handleTransportPacket(packet)
            }
        } catch {
            securePacketReceiver.reset()
            failConnection()
        }
    }

    private func handleTransportPacket(_ packet: Data) {
        if handlePlaintextPacket(packet) {
            return
        }

        guard let sessionKey, let sessionNonce else {
            failConnection()
            return
        }

        if !hasVerifiedPeer {
            do {
                let proof = try roleProofVerifier.verifyPacket(
                    key: sessionKey,
                    transportPacket: packet,
                    maxPacketSize: maxRoleProofPacketBytes,
                    expectedSessionNonce: sessionNonce
                )
                handleVerifiedRoleProof(proof)
            } catch {
                failConnection()
            }
            return
        }

        do {
            let plaintext = try BleAesGcm.decryptPacket(
                key: sessionKey,
                transportPacket: packet,
                maxPacketSize: maxSecurePacketBytes
            )
            handleSecurePayload(plaintext)
        } catch {
            failConnection()
        }
    }

    private func handlePlaintextPacket(_ packet: Data) -> Bool {
        guard let payload = try? BleAesGcm.unwrapTransportPacket(packet, maxPacketSize: maxSecurePacketBytes),
              let object = try? JSONSerialization.jsonObject(with: payload, options: []),
              let json = object as? [String: Any],
              let kind = json["kind"] as? String else {
            return false
        }

        switch kind {
        case "auth_hello":
            handleAuthHello(json)
            return true
        case "auth_hello_ack":
            handleAuthHelloAck(json)
            return true
        default:
            return false
        }
    }

    private func handleAuthHello(_ json: [String: Any]) {
        guard sessionKey == nil, !hasVerifiedPeer else {
            failConnection()
            return
        }
        guard let remotePublicKeyBase64 = normalizedString(json["publicKey"] as? String),
              let incomingNonce = normalizedString(json["sessionNonce"] as? String) else {
            failConnection()
            return
        }
        let localKey = localKey ?? {
            let key = P256.KeyAgreement.PrivateKey()
            self.localKey = key
            return key
        }()
        guard let derivedSessionKey = derivedSessionKey(
            using: localKey,
            remotePublicKeyBase64: remotePublicKeyBase64
        ) else {
            failConnection()
            return
        }

        if let existingNonce = sessionNonce, existingNonce != incomingNonce {
            failConnection()
            return
        }
        sessionNonce = incomingNonce
        if localPublicKeyData == nil {
            localPublicKeyData = makeX509PublicKeyData(from: localKey.publicKey)
        }
        sessionKey = derivedSessionKey

        let payload: [String: Any] = [
            "kind": "auth_hello_ack",
            "protocol": "cc_rescue_secure_v1",
            "publicKey": localPublicKeyData?.base64EncodedString() ?? "",
            "sessionNonce": incomingNonce
        ]
        guard sendPlaintextJSONObject(payload) else {
            failConnection()
            return
        }
        sendRoleProofIfNeeded()
    }

    private func handleAuthHelloAck(_ json: [String: Any]) {
        guard sessionKey == nil, !hasVerifiedPeer else {
            return
        }
        guard let localKey,
              let expectedNonce = sessionNonce,
              let returnedNonce = normalizedString(json["sessionNonce"] as? String),
              expectedNonce == returnedNonce,
              let remotePublicKeyBase64 = normalizedString(json["publicKey"] as? String),
              let derivedSessionKey = derivedSessionKey(
                  using: localKey,
                  remotePublicKeyBase64: remotePublicKeyBase64
              ) else {
            failConnection()
            return
        }
        sessionKey = derivedSessionKey
        sendRoleProofIfNeeded()
    }

    private func sendRoleProofIfNeeded() {
        guard !hasSentRoleProof,
              let sessionKey,
              let sessionNonce else {
            return
        }
        hasSentRoleProof = true
        Task { [weak self] in
            guard let self else { return }
            do {
                let packet = try await self.roleProofCreator.createEncryptedPacket(
                    sessionKey: sessionKey,
                    maxPacketSize: self.maxRoleProofPacketBytes,
                    sessionNonce: sessionNonce
                )
                guard !packet.isEmpty else {
                    self.queue.async { [weak self] in
                        self?.failConnection()
                    }
                    return
                }
                self.queue.async { [weak self] in
                    guard let self else { return }
                    guard self.enqueueTransportPacket(packet) else {
                        self.failConnection()
                        return
                    }
                }
            } catch {
                self.queue.async { [weak self] in
                    self?.failConnection()
                }
            }
        }
    }

    private func handleVerifiedRoleProof(_ proof: RoleProofPayload) {
        guard let resolvedRole = verifiedRole(from: proof) else {
            failConnection()
            return
        }
        authTimeoutWork?.cancel()
        hasVerifiedPeer = true
        verifiedPeerRole = resolvedRole
        DispatchQueue.main.async {
            SOSChatStore.shared.ensureSession(
                id: self.sessionId,
                role: resolvedRole,
                isVerified: true
            )
        }
        updateStatus(.ready)
        sendPeerIdentityIfNeeded()
    }

    private func handleSecurePayload(_ plaintext: Data) {
        if let ack = BleChatEnvelope.decodeAck(plaintext) {
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

        let messageText = String(data: plaintext, encoding: .utf8) ?? ""
        let cleanedText = messageText.trimmingCharacters(in: CharacterSet(charactersIn: "\0").union(.whitespacesAndNewlines))
        guard !cleanedText.isEmpty else { return }

        if let peerIdentity = parsePeerInfoPayload(cleanedText) {
            applyPeerInfo(peerIdentity)
            onSignalLocation?(peerIdentity.signalLocation)
            return
        }
        if let voicePacket = BleVoicePayload.parsePacket(cleanedText) {
            handleVoicePacket(voicePacket)
            return
        }
        if let imagePacket = BleImagePayload.parsePacket(cleanedText) {
            handleImagePacket(imagePacket)
            return
        }
        if let filePacket = BleFilePayload.parsePacket(cleanedText) {
            handleFilePacket(filePacket)
            return
        }
        if let liveLocation = parseLiveLocationPayload(cleanedText) {
            let appended = upsertRemoteLocationMessageOnMain(
                latitude: liveLocation.latitude,
                longitude: liveLocation.longitude,
                horizontalAccuracyMeters: liveLocation.horizontalAccuracyMeters,
                capturedAt: liveLocation.capturedAt,
                transportMessageId: liveLocation.messageId
            )
            _ = sendEncryptedData(BleChatEnvelope.encodeDeliveredAck(messageId: liveLocation.messageId))
            if !appended {
                return
            }
            return
        }
        if let envelope = BleChatEnvelope.decodeChat(cleanedText), !BleChatEnvelope.isExpired(envelope) {
            let appended = appendRemoteMessageOnMain(
                text: envelope.text,
                transportMessageId: envelope.messageId
            )
            _ = sendEncryptedData(BleChatEnvelope.encodeDeliveredAck(messageId: envelope.messageId))
            if !appended {
                return
            }
            return
        }

        let appended = appendRemoteMessageOnMain(text: cleanedText, transportMessageId: nil)
        if appended {
            _ = sendEncryptedData(BleChatEnvelope.encodeDeliveredAck(messageId: nil))
        }
    }

    private func sendPeerIdentityIfNeeded() {
        guard hasVerifiedPeer, !hasSentPeerIdentity else { return }
        hasSentPeerIdentity = true

        var payload: [String: Any] = [
            "kind": "peer_info",
            "name": localDisplayName(),
            "role": "rescue",
            "broadcastId": SecureLocalStore.shared.getOrCreateBroadcastId()
        ]
        if let batteryPercent = currentBatteryPercent(), (0...100).contains(batteryPercent) {
            payload["batteryPct"] = batteryPercent
        }
        if let avatarBase64 = localAvatarPayload() {
            payload["avatarB64"] = avatarBase64
        }

        guard sendEncryptedJSONObject(payload) else {
            hasSentPeerIdentity = false
            return
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

    private func sendImageDonePacket(transferId: String) {
        let payload = BleImagePayload.buildDonePacket(transferId)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
    }

    private func sendImageAbortPacket(transferId: String, reason: String) {
        let payload = BleImagePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
    }

    private func sendVoiceDonePacket(transferId: String) {
        let payload = BleVoicePayload.buildDonePacket(transferId)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
    }

    private func sendVoiceAbortPacket(transferId: String, reason: String) {
        let payload = BleVoicePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
    }

    private func sendFileDonePacket(transferId: String) {
        let payload = BleFilePayload.buildDonePacket(transferId)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
    }

    private func sendFileAbortPacket(transferId: String, reason: String) {
        let payload = BleFilePayload.buildAbortPacket(transferId, reason: reason)
        guard !payload.isEmpty else { return }
        _ = sendEncryptedData(Data(payload.utf8))
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

    private func applyPeerInfo(_ identity: WiFiAwarePeerIdentity) {
        let peerName = identity.name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !peerName.isEmpty {
            onPeerName?(peerName)
        }

        DispatchQueue.main.async {
            SOSChatStore.shared.updateIdentity(
                id: self.sessionId,
                displayName: identity.name,
                role: self.hasVerifiedPeer ? self.verifiedPeerRole : .unknown,
                isVerified: self.hasVerifiedPeer,
                avatarBase64: identity.avatarBase64
            )
        }

        if let rawBroadcastId = identity.broadcastId,
           let normalized = normalizeBroadcastId(rawBroadcastId) {
            onBroadcastId?(normalized)
        }
    }

    private func parsePeerInfoPayload(_ message: String) -> WiFiAwarePeerIdentity? {
        guard let data = message.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let json = object as? [String: Any],
              let kind = json["kind"] as? String,
              kind == "peer_info" else {
            return nil
        }
        let name = (json["name"] as? String) ?? ""
        let rawAvatar = (json["avatarB64"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let avatarBase64: String?
        if let rawAvatar, !rawAvatar.isEmpty, rawAvatar.count <= 8_192 {
            avatarBase64 = rawAvatar
        } else {
            avatarBase64 = nil
        }
        return WiFiAwarePeerIdentity(
            name: name,
            avatarBase64: avatarBase64,
            broadcastId: json["broadcastId"] as? String,
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

        let messageId = normalizedString(json["messageId"] as? String)
        let horizontalAccuracyMeters = numericValue(from: json["accuracyMeters"])
        let timestampMillis = numericValue(from: json["timestampMillis"])
        let capturedAt = timestampMillis.map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()

        return LiveLocationPayload(
            messageId: messageId,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: horizontalAccuracyMeters,
            capturedAt: capturedAt
        )
    }

    private func sendPlaintextJSONObject(_ packet: [String: Any]) -> Bool {
        guard let payload = try? JSONSerialization.data(withJSONObject: packet, options: []) else {
            return false
        }
        return enqueueTransportPacket(BleAesGcm.wrapTransportPacket(payload))
    }

    private func sendEncryptedJSONObject(_ packet: [String: Any], requireConfirmedWrite: Bool = false) -> Bool {
        guard let payload = try? JSONSerialization.data(withJSONObject: packet, options: []) else {
            return false
        }
        return sendEncryptedData(payload, requireConfirmedWrite: requireConfirmedWrite)
    }

    private func sendEncryptedData(_ payload: Data, requireConfirmedWrite: Bool = false) -> Bool {
        guard let sessionKey,
              hasVerifiedPeer else {
            return false
        }
        let encrypted = BleAesGcm.encryptPacket(
            key: sessionKey,
            plaintext: payload,
            maxPacketSize: maxSecurePacketBytes
        )
        guard !encrypted.isEmpty else { return false }
        if requireConfirmedWrite {
            return sendTransportPacketImmediately(encrypted)
        }
        return enqueueTransportPacket(encrypted)
    }

    private func sendTransportPacketImmediately(_ packet: Data) -> Bool {
        guard let connection,
              !isSendingPacket,
              pendingTransportPackets.isEmpty else {
            return false
        }

        isSendingPacket = true
        let result = ImmediateSendResult()
        let sendTask = Task {
            do {
                try await connection.send(packet, endOfStream: false)
                result.finish(success: true)
            } catch is CancellationError {
                result.finish(success: false)
            } catch {
                result.finish(success: false)
            }
        }

        let waitResult = result.wait(timeout: immediateSendTimeout)
        if waitResult != .success {
            sendTask.cancel()
        }

        isSendingPacket = false
        guard waitResult == .success, result.success else {
            failConnection()
            return false
        }
        return true
    }

    private func enqueueTransportPacket(_ packet: Data) -> Bool {
        guard connection != nil else { return false }
        pendingTransportPackets.append(packet)
        flushPendingTransportPackets()
        return true
    }

    private func flushPendingTransportPackets() {
        guard !isSendingPacket else { return }
        guard let connection, !pendingTransportPackets.isEmpty else { return }
        let packet = pendingTransportPackets.removeFirst()
        isSendingPacket = true
        Task { [weak self] in
            do {
                try await connection.send(packet, endOfStream: false)
                self?.queue.async { [weak self] in
                    guard let self else { return }
                    self.isSendingPacket = false
                    self.flushPendingTransportPackets()
                }
            } catch is CancellationError {
                self?.queue.async { [weak self] in
                    self?.isSendingPacket = false
                }
            } catch {
                self?.queue.async { [weak self] in
                    guard let self else { return }
                    self.isSendingPacket = false
                    self.failConnection()
                }
            }
        }
    }

    private func scheduleAuthTimeout() {
        authTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, !self.hasVerifiedPeer else { return }
            self.failConnection()
        }
        authTimeoutWork = work
        queue.asyncAfter(deadline: .now() + 8, execute: work)
    }

    private func failConnection() {
        receiveTask?.cancel()
        receiveTask = nil
        connection = nil
        resetSessionState()
        updateStatus(.failed)
    }

    private func updateStatus(_ status: RescueConnectionStatus) {
        DispatchQueue.main.async {
            self.onStatusChange?(status)
        }
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync(execute: block)
    }

    private func resetSessionState() {
        authTimeoutWork?.cancel()
        authTimeoutWork = nil
        securePacketReceiver.reset()
        pendingTransportPackets.removeAll()
        isSendingPacket = false
        sessionKey = nil
        localKey = nil
        localPublicKeyData = nil
        sessionNonce = nil
        hasSentRoleProof = false
        hasVerifiedPeer = false
        hasSentPeerIdentity = false
        verifiedPeerRole = .unknown
        incomingImageTransfers.removeAll()
    }

    private func verifiedRole(from proof: RoleProofPayload) -> SOSChatRole? {
        guard let certificateBytes = try? decodeBase64Data(proof.certificate, fieldName: "certificate"),
              let roleCertificate = RoleCertificate.fromStorageBytes(certificateBytes) else {
            return nil
        }
        switch roleCertificate.role {
        case "admin", "fieldteam":
            return .fieldTeam
        default:
            return nil
        }
    }

    private func derivedSessionKey(
        using localKey: P256.KeyAgreement.PrivateKey,
        remotePublicKeyBase64: String
    ) -> SymmetricKey? {
        guard let publicKeyBytes = try? decodeBase64Data(remotePublicKeyBase64, fieldName: "publicKey"),
              let x963 = try? BleKeyDecoder.x963PublicKey(from: publicKeyBytes),
              let remoteKey = try? P256.KeyAgreement.PublicKey(x963Representation: x963),
              let sharedSecret = try? localKey.sharedSecretFromKeyAgreement(with: remoteKey) else {
            return nil
        }
        return sharedSecret.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: Data(repeating: 0, count: 32),
            sharedInfo: Data(),
            outputByteCount: 32
        )
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

    private func normalizeBroadcastId(_ raw: String) -> String? {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let cleaned = trimmed.replacingOccurrences(of: "ccid:", with: "", options: [.caseInsensitive])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        return cleaned.uppercased()
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

    private func normalizedString(_ value: String?) -> String? {
        let normalized = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized?.isEmpty == false ? normalized : nil
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
}

private struct WiFiAwarePeerIdentity {
    let name: String
    let avatarBase64: String?
    let broadcastId: String?
    let signalLocation: SOSSignalLocationPayload?
}
