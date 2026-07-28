//
//  GattMeshManager.swift
//  Crisis Connect
//
//  Created by Codex on 29.03.2026.
//

import Foundation
@preconcurrency import CoreBluetooth
import Combine

struct GattMeshConnectedPeer: Identifiable, Equatable {
    let id: String
    let routeKeys: [String]
    let displayName: String
    let transportLabel: String
    let isSendReady: Bool
    let verificationStatus: GattMeshPeerVerificationStatus
    let verifiedRole: String?
    let verifiedAgency: String?
    let verifiedAtMillis: Int64?
}

enum GattMeshPeerVerificationStatus: Equatable {
    case unverified
    case verified
}

struct GattMeshRuntimeSnapshot: Equatable {
    var isEnabled = false
    var isScanning = false
    var connectedPeerCount = 0
    var discoveredPeerCount = 0
    var sendReadyPeerCount = 0
    var connectedPeers: [GattMeshConnectedPeer] = []
    var lastError: String?
}

private struct GattMeshPendingOutboundRetryState: Codable, Equatable {
    let failureCount: Int
    let nextEligibleAtMillis: Int64
}

private struct GattMeshPendingOutboundQueueSnapshot: Codable {
    let version: Int
    let packets: [GattMeshPacket]
    let retryStates: [String: GattMeshPendingOutboundRetryState]
}

private struct GattMeshPeerVerificationState: Equatable {
    let role: String
    let agency: String
    let verifiedAtMillis: Int64
    let peerIdentityKey: String?
}

private struct GattMeshConnectedPeerAccumulator {
    var peer: GattMeshConnectedPeer
    var peerIdentityKey: String?
    var routeIdentityKeys: Set<String>
}

/// Reassembly state for one inbound image blob (single-hop, so one directly connected source).
private final class InboundImageBlob {
    let sourceRouteKey: String
    let senderLabel: String
    let ivBase64: String
    let keyId: String
    let mimeType: String?
    let width: Int?
    let height: Int?
    let chunkCount: Int
    let cipherBytes: Int
    let kind: String?
    let durationMillis: Int64?
    var chunks: [Data?]
    var receivedCount = 0
    var receivedBytes = 0
    var lastActivityAtMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)

    init(
        sourceRouteKey: String,
        senderLabel: String,
        ivBase64: String,
        keyId: String,
        mimeType: String?,
        width: Int?,
        height: Int?,
        chunkCount: Int,
        cipherBytes: Int,
        kind: String?,
        durationMillis: Int64?
    ) {
        self.sourceRouteKey = sourceRouteKey
        self.senderLabel = senderLabel
        self.ivBase64 = ivBase64
        self.keyId = keyId
        self.mimeType = mimeType
        self.width = width
        self.height = height
        self.chunkCount = chunkCount
        self.cipherBytes = cipherBytes
        self.kind = kind
        self.durationMillis = durationMillis
        self.chunks = Array(repeating: nil, count: chunkCount)
    }
}

final class GattMeshManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate, BlePeripheralHostProfile {
    static let shared = GattMeshManager(profile: .publicMesh)
    /// Second, role-gated authority mesh instance (inert until enabled + provisioned with a key).
    static let authority = GattMeshManager(profile: .authority)

    let profile: MeshProfile

    @Published private(set) var snapshot = GattMeshRuntimeSnapshot()

    /// Telsiz (push-to-talk) session state, published for the telsiz UI. Updated on the main queue.
    @Published private(set) var telsizState = PttSessionState()

    private let queue = BlePeripheralHostCoordinator.shared.queue
    private let queueKey = DispatchSpecificKey<Void>()
    private let cleanupInterval: TimeInterval = 12
    private let discoveryStaleInterval: TimeInterval = 20
    private let scanStopSettleBeforeConnectDelay: TimeInterval = 0.3
    private let pendingOutboundPacketMaxAgeMillis: Int64 = 2 * 60 * 1_000
    private let pendingOutboundRetryBaseMillis: Int64 = 1_500
    private let pendingOutboundRetryMaxMillis: Int64 = 15_000
    private let peerVerificationCacheTTLMillis: Int64 = 10 * 60 * 1_000
    private let peerVerificationRetryIntervalMillis: Int64 = 15 * 1_000
    private let meshInitiatorRankManufacturerID: UInt16 = 0x0F0F
    private let sosServiceUUID = CBUUID(string: "0000CC00-0000-1000-8000-00805F9B34FB")
    private let securityRepository = SecurityRepository.shared
    private let deviceIdentityStore = DeviceIdentityStore.shared

    /// This device's own verified institution (kurum) from its stored certificate, shown on the self
    /// row of the connected-users sheet (mirrors Android). Empty/v2 certs yield nil.
    var localAgency: String? {
        // `try?` already flattens the function's `DeviceCertificate?` return to a single optional.
        guard let deviceCertificate = try? securityRepository.getStoredCertificateSync(allowExpired: true) else {
            return nil
        }
        let trimmed = deviceCertificate.roleCertificate.agency.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
    private let roleProofVerifier = RoleProofVerifier()
    private let messageOriginRoleProofVerifier = RoleProofVerifier(
        maxClockSkewMillis: GattMeshProtocol.maxOriginProofAgeMillis,
        maxExpiredCertificateGraceMillis: 0
    )

    private static let persistedPendingQueueVersion = 1
    private static let maxPendingOutboundPackets = 200
    private static let inboundImageBlobTimeoutMillis: Int64 = 45_000
    private static let maxConcurrentInboundImageBlobs = 3
    private static let imageChunkSendSpacingSeconds: TimeInterval = 0.075
    private let centralRestoreIdentifier: String
    private let persistedPendingQueueURL: URL

    private var cleanupWork: DispatchWorkItem?
    private var pendingOutboundFlushWork: DispatchWorkItem?
    private var centralRecoveryWork: DispatchWorkItem?
    private var central: CBCentralManager?
    private var centralState: CBManagerState = .unknown
    private var peripheralState: CBManagerState = .unknown
    private var publicMeshEnabled = false
    private var notificationsEnabled = true
    private var isScanning = false
    private var isChatOpen = false
    private var lastError: String?

    private var hostedMessageInCharacteristic: CBMutableCharacteristic?
    private var hostedMessageOutCharacteristic: CBMutableCharacteristic?

    /// Telsiz controller (push-to-talk); created on first join, torn down on leave/shutdown.
    private var pttController: PttController?
    /// Stable per-process origin id stamped onto every telsiz packet so peers and the floor lock are
    /// keyed independently of the BLE hop address (mirrors Android `localPttOriginId`).
    private lazy var localPttOriginId = String(format: "%08x", UInt32.random(in: UInt32.min...UInt32.max))

    private var discoveredPeers: [UUID: DiscoveredPeer] = [:]
    private var outboundPeers: [UUID: OutboundPeer] = [:]
    private var outboundReceivers: [UUID: BleChunkReceiver] = [:]
    private var inboundPeers: [UUID: InboundPeer] = [:]
    private var inboundReceivers: [UUID: BleChunkReceiver] = [:]
    private var inboundReadPayloads: [UUID: Data] = [:]
    private var inboundNotificationQueues: [UUID: [InboundNotificationRequest]] = [:]
    private var activeInboundNotificationRequests: [UUID: InboundNotificationRequest] = [:]
    private var seenMessageIds: [String: Int64] = [:]
    private var pendingOutboundPackets: [GattMeshPacket] = []
    private var pendingOutboundRetryStates: [String: GattMeshPendingOutboundRetryState] = [:]
    private var peerLabels: [String: String] = [:]
    private var peerVerificationByRouteKey: [String: GattMeshPeerVerificationState] = [:]
    private var pendingPeerVerificationNonces: [String: String] = [:]
    private var pendingPeerVerificationRequestedAtMillis: [String: Int64] = [:]
    private var inboundImageBlobs: [String: InboundImageBlob] = [:]
    private var cancellables: Set<AnyCancellable> = []

    var hostProfileIdentifier: String { self.profile.id == MeshProfile.publicMesh.id ? "gattmesh" : "gattmesh-\(self.profile.id)" }
    var hostPrimaryServiceUUID: CBUUID { self.profile.serviceUUID }
    var isPeripheralHostActive: Bool { publicMeshEnabled && PlatformRuntime.supportsBlePeripheralHosting }
    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? {
        guard isPeripheralHostActive else { return nil }
        return BleHostedAdvertisementDescriptor(
            serviceUUIDs: [self.profile.serviceUUID],
            localName: nil,
            priority: 50
        )
    }

    private var peripheralManager: CBPeripheralManager? {
        BlePeripheralHostCoordinator.shared.currentPeripheralManager
    }

    private init(profile: MeshProfile = .publicMesh) {
        self.profile = profile
        self.centralRestoreIdentifier = "com.auralis.crisisconnect.gattmesh.\(self.profile.id).central"
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        self.persistedPendingQueueURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent("gatt_mesh_pending_outbound_queue_\(self.profile.id).json", isDirectory: false)
        super.init()
        queue.setSpecific(key: queueKey, value: ())
        BlePeripheralHostCoordinator.shared.register(self)
        observeSettings()

        queue.async { [weak self] in
            self?.loadPersistedPendingOutboundQueueLocked()
            self?.scheduleCleanupLocked()
            self?.schedulePendingOutboundFlushLocked()
            self?.reconcileRuntimeLocked(clearRoutes: false)
        }
    }

    func bootstrap() {
        queue.async { [weak self] in
            self?.reconcileRuntimeLocked(clearRoutes: false)
        }
    }

    /// Toggles the authority mesh by writing its persisted preference; the runtime reconcile happens
    /// in `observeSettings` (which also enforces the group-key requirement). No-op for the public
    /// mesh, which follows its own toggle.
    func setMeshEnabled(_ enabled: Bool) {
        guard self.profile.id == MeshProfile.authority.id else { return }
        DispatchQueue.main.async {
            AdvancedSettingsStore.shared.authorityMeshEnabled = enabled
        }
    }

    func setChatOpen(_ isOpen: Bool) {
        queue.async { [weak self] in
            self?.isChatOpen = isOpen
        }

        let isAuthority = self.profile.id == MeshProfile.authority.id
        Task { @MainActor [weak self] in
            let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
            store.setChatOpen(isOpen)
            guard isOpen else { return }
            let unreadIds = store.markAllRemoteMessagesRead()
            guard !unreadIds.isEmpty else { return }
            self?.queue.async { [weak self] in
                self?.sendReceiptLocked(type: .read, messageIds: unreadIds)
            }
        }
    }

    func sendChatMessage(_ text: String) -> GattMeshSendResult? {
        syncOnQueue {
            guard publicMeshEnabled else { return nil }
            guard let basePacket = GattMeshProtocol.makeChatPacket(text: text, profile: profile) else { return nil }
            let packet = authenticatedChatPacketLocked(from: basePacket)
            _ = rememberMessageIdLocked(packet.id, timestampMillis: packet.timestampMillis)

            switch sendOrQueuePacketLocked(packet, queueWhenFailed: true) {
            case .sent:
                return GattMeshSendResult(messageId: packet.id, disposition: .sent)
            case .queued:
                return GattMeshSendResult(messageId: packet.id, disposition: .queued)
            case .failed:
                return nil
            }
        }
    }

    /// Sends a prepared JPEG into the authority mesh as a single-hop encrypted blob
    /// (init → paced chunks → done), mirroring Android's mesh image blob v1.
    /// Returns the blob/message id immediately; the bubble status updates SENDING → SENT/FAILED.
    func sendImageMessage(jpegData: Data, width: Int?, height: Int?) -> String? {
        guard profile.id == MeshProfile.authority.id else { return nil }
        guard !jpegData.isEmpty, jpegData.count <= GattMeshProtocol.meshImageMaxPlainBytes else {
            return nil
        }
        let blobId = UUID().uuidString
        guard let fileName = MeshImageFileStore.save(data: jpegData, blobId: blobId) else {
            return nil
        }
        guard let encrypted = GattMeshProtocol.encryptImageBlob(
            blobId: blobId,
            mimeType: "image/jpeg",
            data: jpegData,
            profile: profile
        ) else {
            return nil
        }

        let chunkSize = GattMeshProtocol.meshImageChunkBytes
        let chunkCount = (encrypted.cipher.count + chunkSize - 1) / chunkSize
        guard (1...GattMeshProtocol.meshImageMaxChunks).contains(chunkCount) else { return nil }

        let sender = GattMeshProtocol.localSenderLabel()
        var packets: [GattMeshPacket] = []
        packets.reserveCapacity(chunkCount + 2)
        packets.append(
            GattMeshProtocol.makeImageInitPacket(
                blobId: blobId,
                chunkCount: chunkCount,
                cipherBytes: encrypted.cipher.count,
                keyId: encrypted.keyId,
                ivBase64: encrypted.ivBase64,
                mimeType: "image/jpeg",
                width: width,
                height: height,
                senderLabel: sender
            )
        )
        var offset = 0
        var index = 0
        while offset < encrypted.cipher.count {
            let end = min(offset + chunkSize, encrypted.cipher.count)
            packets.append(
                GattMeshProtocol.makeImageChunkPacket(
                    blobId: blobId,
                    index: index,
                    dataBase64: encrypted.cipher.subdata(in: offset..<end).base64EncodedString(),
                    senderLabel: sender
                )
            )
            offset = end
            index += 1
        }
        packets.append(GattMeshProtocol.makeImageDonePacket(blobId: blobId, senderLabel: sender))

        Task { @MainActor in
            GattMeshChatStore.authority.appendLocalImageMessage(
                messageId: blobId,
                imageFileName: fileName,
                imageWidth: width,
                imageHeight: height,
                status: .sending
            )
        }
        queue.async { [weak self] in
            guard let self else { return }
            guard self.publicMeshEnabled else {
                self.finishOutboundImageBlob(blobId: blobId, success: false)
                return
            }
            self.dispatchOutboundImagePacketsLocked(blobId: blobId, packets: packets, index: 0)
        }
        return blobId
    }

    /// Sends a recorded voice note (AAC/M4A) into the authority mesh as a single-hop encrypted
    /// blob, reusing the image blob pipeline with kind=voice.
    func sendVoiceMessage(m4aData: Data, durationMillis: Int64) -> String? {
        guard profile.id == MeshProfile.authority.id else { return nil }
        guard !m4aData.isEmpty, m4aData.count <= GattMeshProtocol.meshImageMaxPlainBytes else {
            return nil
        }
        let safeDuration = min(max(durationMillis, 1), GattMeshProtocol.meshVoiceMaxDurationMillis)
        let blobId = UUID().uuidString
        guard let fileName = MeshImageFileStore.save(data: m4aData, blobId: blobId, fileExtension: "m4a") else {
            return nil
        }
        guard let encrypted = GattMeshProtocol.encryptImageBlob(
            blobId: blobId,
            mimeType: GattMeshProtocol.meshVoiceMime,
            data: m4aData,
            profile: profile
        ) else {
            return nil
        }

        let chunkSize = GattMeshProtocol.meshImageChunkBytes
        let chunkCount = (encrypted.cipher.count + chunkSize - 1) / chunkSize
        guard (1...GattMeshProtocol.meshImageMaxChunks).contains(chunkCount) else { return nil }

        let sender = GattMeshProtocol.localSenderLabel()
        var packets: [GattMeshPacket] = []
        packets.reserveCapacity(chunkCount + 2)
        packets.append(
            GattMeshProtocol.makeImageInitPacket(
                blobId: blobId,
                chunkCount: chunkCount,
                cipherBytes: encrypted.cipher.count,
                keyId: encrypted.keyId,
                ivBase64: encrypted.ivBase64,
                mimeType: GattMeshProtocol.meshVoiceMime,
                width: nil,
                height: nil,
                senderLabel: sender,
                kind: GattMeshProtocol.blobKindVoice,
                durationMillis: safeDuration
            )
        )
        var offset = 0
        var index = 0
        while offset < encrypted.cipher.count {
            let end = min(offset + chunkSize, encrypted.cipher.count)
            packets.append(
                GattMeshProtocol.makeImageChunkPacket(
                    blobId: blobId,
                    index: index,
                    dataBase64: encrypted.cipher.subdata(in: offset..<end).base64EncodedString(),
                    senderLabel: sender
                )
            )
            offset = end
            index += 1
        }
        packets.append(GattMeshProtocol.makeImageDonePacket(blobId: blobId, senderLabel: sender))

        Task { @MainActor in
            GattMeshChatStore.authority.appendLocalVoiceMessage(
                messageId: blobId,
                voiceFileName: fileName,
                voiceDurationMillis: safeDuration,
                status: .sending
            )
        }
        queue.async { [weak self] in
            guard let self else { return }
            guard self.publicMeshEnabled else {
                self.finishOutboundImageBlob(blobId: blobId, success: false)
                return
            }
            self.dispatchOutboundImagePacketsLocked(blobId: blobId, packets: packets, index: 0)
        }
        return blobId
    }

    private func dispatchOutboundImagePacketsLocked(blobId: String, packets: [GattMeshPacket], index: Int) {
        guard index < packets.count else {
            finishOutboundImageBlob(blobId: blobId, success: true)
            return
        }
        guard publicMeshEnabled else {
            finishOutboundImageBlob(blobId: blobId, success: false)
            return
        }
        if sendOrQueuePacketLocked(packets[index], queueWhenFailed: false) == .failed {
            finishOutboundImageBlob(blobId: blobId, success: false)
            return
        }
        queue.asyncAfter(deadline: .now() + Self.imageChunkSendSpacingSeconds) { [weak self] in
            self?.dispatchOutboundImagePacketsLocked(blobId: blobId, packets: packets, index: index + 1)
        }
    }

    private func finishOutboundImageBlob(blobId: String, success: Bool) {
        Task { @MainActor in
            GattMeshChatStore.authority.updateLocalMessageStatus(
                blobId,
                status: success ? .sent : .failed
            )
        }
    }

    func peripheralHostWillReset() {
        performOnQueue {
            self.hostedMessageInCharacteristic = nil
            self.hostedMessageOutCharacteristic = nil
            self.peripheralState = .unknown
            self.inboundPeers.removeAll()
            self.inboundReceivers.removeAll()
            self.inboundReadPayloads.removeAll()
            self.inboundNotificationQueues.removeAll()
            self.activeInboundNotificationRequests.removeAll()
            self.peerVerificationByRouteKey.removeAll()
            self.pendingPeerVerificationNonces.removeAll()
            self.pendingPeerVerificationRequestedAtMillis.removeAll()
            self.publishStateLocked()
        }
    }

    func buildHostedService() -> CBMutableService? {
        guard isPeripheralHostActive else { return nil }

        let service = CBMutableService(type: self.profile.serviceUUID, primary: true)
        let messageIn = CBMutableCharacteristic(
            type: self.profile.messageInCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let messageOut = CBMutableCharacteristic(
            type: self.profile.messageOutCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )
        // CoreBluetooth automatically manages the CCCD descriptor for
        // characteristics that include .notify in their properties.
        // Manually adding a CBMutableDescriptor with UUID 0x2902 is
        // unsupported and causes peripheralManager.add(service) to fail,
        // which prevents advertising and breaks scanning via
        // peripheralHostWillReset setting peripheralState to .unknown.
        service.characteristics = [messageIn, messageOut]

        hostedMessageInCharacteristic = messageIn
        hostedMessageOutCharacteristic = messageOut

        return service
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        performOnQueue {
            self.peripheralState = peripheral.state
            NSLog("GattMesh peripheral state=%@", self.debugDescription(for: peripheral.state))
            if !self.publicMeshEnabled {
                self.publishStateLocked()
                return
            }
            if peripheral.state != .poweredOn {
                self.lastError = self.errorMessage(for: peripheral.state)
            } else {
                self.lastError = nil
            }
            if peripheral.state == .poweredOn && self.central == nil {
                self.reconcileRuntimeLocked(clearRoutes: false)
                return
            }
            self.publishStateLocked()
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        performOnQueue {
            self.lastError = error?.localizedDescription
            self.publishStateLocked()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        performOnQueue {
            guard request.characteristic.uuid == self.profile.messageOutCharacteristicUUID else {
                peripheral.respond(to: request, withResult: .attributeNotFound)
                return
            }

            let fullValue = self.inboundReadPayloads[request.central.identifier] ?? Data()
            guard request.offset >= 0, request.offset <= fullValue.count else {
                peripheral.respond(to: request, withResult: .invalidOffset)
                return
            }

            request.value = fullValue.subdata(in: request.offset..<fullValue.count)
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        performOnQueue {
            for request in requests {
                let centralId = request.central.identifier
                let routeKey = self.routeKey(for: request.central)
                self.upsertInboundPeerLocked(for: request.central, routeKey: routeKey)

                guard request.characteristic.uuid == self.profile.messageInCharacteristicUUID,
                      let value = request.value,
                      !value.isEmpty else {
                    peripheral.respond(to: request, withResult: .requestNotSupported)
                    continue
                }

                let receiver = self.inboundReceivers[centralId] ?? BleChunkReceiver(maxPacketSize: GattMeshProtocol.maxPacketBytes)
                self.inboundReceivers[centralId] = receiver

                do {
                    if let transportPacket = try receiver.onChunk(value) {
                        self.handleTransportPacketLocked(
                            transportPacket,
                            sourceRouteKey: routeKey,
                            fallbackDisplayName: self.inboundPeers[centralId]?.name
                        )
                    }
                    peripheral.respond(to: request, withResult: .success)
                } catch {
                    receiver.reset()
                    peripheral.respond(to: request, withResult: .unlikelyError)
                }
            }

            self.publishStateLocked()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        performOnQueue {
            guard characteristic.uuid == self.profile.messageOutCharacteristicUUID else { return }
            let routeKey = self.routeKey(for: central)
            self.upsertInboundPeerLocked(for: central, routeKey: routeKey, notifyEnabled: true)
            self.publishStateLocked()
            self.flushPendingOutboundPacketsLocked()
            self.processInboundNotificationQueueLocked(centralId: central.identifier)
            self.requestPeerVerificationIfNeededLocked(routeKey: routeKey)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        performOnQueue {
            guard characteristic.uuid == self.profile.messageOutCharacteristicUUID else { return }
            self.inboundPeers[central.identifier]?.notifyEnabled = false
            self.activeInboundNotificationRequests.removeValue(forKey: central.identifier)
            self.inboundNotificationQueues.removeValue(forKey: central.identifier)
            self.publishStateLocked()
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        performOnQueue {
            let centralIds = Array(self.activeInboundNotificationRequests.keys)
            centralIds.forEach { self.processInboundNotificationQueueLocked(centralId: $0) }
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        performOnQueue {
            self.central = central
            self.centralState = central.state
            NSLog("GattMesh central state=%@", self.debugDescription(for: central.state))
            switch central.state {
            case .poweredOn:
                self.centralRecoveryWork?.cancel()
                self.centralRecoveryWork = nil
            case .resetting:
                self.scheduleCentralRecoveryLocked()
            default:
                self.centralRecoveryWork?.cancel()
                self.centralRecoveryWork = nil
            }
            if central.state == .poweredOn {
                self.lastError = nil
            } else {
                self.lastError = self.errorMessage(for: central.state)
            }
            self.reconcileRuntimeLocked(clearRoutes: central.state != .poweredOn)
        }
    }

    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String : Any]) {
        performOnQueue {
            self.central = central
            self.centralState = central.state

            let restoredPeripherals = (dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral]) ?? []
            let restoredScanServices = dict[CBCentralManagerRestoredStateScanServicesKey] as? [CBUUID]
            let restoredScanOptions = dict[CBCentralManagerRestoredStateScanOptionsKey] as? [String: Any]
            let restoredWasScanning = restoredScanServices != nil || restoredScanOptions != nil
            let now = Date()

            restoredPeripherals.forEach { peripheral in
                let resolvedName = self.nonEmpty(peripheral.name?.trimmingCharacters(in: .whitespacesAndNewlines))
                    ?? self.peerLabels[self.routeKey(for: peripheral)]
                    ?? self.localized("GENERAL_CHAT_PEER_NAME_FALLBACK")
                let peer = self.outboundPeers[peripheral.identifier] ?? OutboundPeer(
                    peripheral: peripheral,
                    name: resolvedName
                )
                peer.name = resolvedName
                peer.lastSeen = now
                peer.isConnected = peripheral.state == .connected
                peer.isConnecting = peripheral.state == .connecting
                peripheral.delegate = self
                self.outboundPeers[peripheral.identifier] = peer
                self.peerLabels[self.routeKey(for: peripheral)] = self.peerLabels[self.routeKey(for: peripheral)] ?? resolvedName

                if peripheral.state == .connected {
                    peripheral.discoverServices([self.profile.serviceUUID])
                }
            }

            if restoredWasScanning && self.publicMeshEnabled && central.state == .poweredOn {
                self.isScanning = true
            }

            self.reconcileRuntimeLocked(clearRoutes: central.state != .poweredOn)
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        performOnQueue {
            guard self.publicMeshEnabled else { return }
            guard self.matchesMeshAdvertisement(advertisementData) else { return }

            let resolvedName = self.nonEmpty((advertisementData[CBAdvertisementDataLocalNameKey] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines))
                ?? self.nonEmpty(peripheral.name?.trimmingCharacters(in: .whitespacesAndNewlines))
                ?? self.localized("GENERAL_CHAT_PEER_NAME_FALLBACK")
            self.discoveredPeers[peripheral.identifier] = DiscoveredPeer(
                peripheral: peripheral,
                name: resolvedName,
                lastSeen: Date()
            )

            let peer = self.outboundPeers[peripheral.identifier] ?? OutboundPeer(peripheral: peripheral, name: resolvedName)
            peer.name = resolvedName
            peer.lastSeen = Date()
            self.outboundPeers[peripheral.identifier] = peer
            self.peerLabels[self.routeKey(for: peripheral)] = self.peerLabels[self.routeKey(for: peripheral)] ?? resolvedName

            guard !peer.isConnected, !peer.isConnecting else {
                self.publishStateLocked()
                return
            }

            peer.isConnecting = true
            self.publishStateLocked()
            self.stopScanningLocked()
            self.queue.asyncAfter(deadline: .now() + self.scanStopSettleBeforeConnectDelay) { [weak self, weak peripheral] in
                guard let self, let peripheral else { return }
                Task { @MainActor [weak self, weak peripheral] in
                    guard let self, let peripheral else { return }
                    self.assignPeripheralDelegate(peripheral)
                    self.queue.async { [weak self, weak peripheral] in
                        guard let self, let peripheral else { return }
                        guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
                        guard let peer = self.outboundPeers[peripheral.identifier], peer.isConnecting, !peer.isConnected else {
                            self.startScanningLocked()
                            self.publishStateLocked()
                            return
                        }
                        central.connect(peripheral, options: nil)
                        self.startScanningLocked()
                        self.publishStateLocked()
                    }
                }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        performOnQueue {
            let peer = self.outboundPeers[peripheral.identifier] ?? OutboundPeer(
                peripheral: peripheral,
                name: self.nonEmpty(peripheral.name?.trimmingCharacters(in: .whitespacesAndNewlines))
                    ?? self.localized("GENERAL_CHAT_PEER_NAME_FALLBACK")
            )
            peer.isConnecting = false
            peer.isConnected = true
            peer.lastSeen = Date()
            peripheral.delegate = self
            self.outboundPeers[peripheral.identifier] = peer
            peripheral.discoverServices([self.profile.serviceUUID])
            self.publishStateLocked()
        }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        performOnQueue {
            self.lastError = error?.localizedDescription ?? self.lastError
            self.outboundPeers[peripheral.identifier]?.isConnecting = false
            self.outboundPeers[peripheral.identifier]?.isConnected = false
            self.publishStateLocked()

            guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
            self.queue.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                guard let self else { return }
                guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
                guard let peer = self.outboundPeers[peripheral.identifier], !peer.isConnected, !peer.isConnecting else {
                    return
                }
                peer.isConnecting = true
                self.publishStateLocked()
                Task { @MainActor [weak self, weak peripheral] in
                    guard let self, let peripheral else { return }
                    self.assignPeripheralDelegate(peripheral)
                    self.queue.async { [weak self, weak peripheral] in
                        guard let self, let peripheral else { return }
                        guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
                        guard let peer = self.outboundPeers[peripheral.identifier], !peer.isConnected, peer.isConnecting else {
                            return
                        }
                        self.central?.connect(peripheral, options: nil)
                        self.publishStateLocked()
                    }
                }
            }
        }
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        performOnQueue {
            self.outboundPeers[peripheral.identifier]?.isConnecting = false
            self.outboundPeers[peripheral.identifier]?.isConnected = false
            self.outboundPeers[peripheral.identifier]?.notifyEnabled = false
            self.outboundPeers[peripheral.identifier]?.messageIn = nil
            self.outboundPeers[peripheral.identifier]?.messageOut = nil
            self.outboundPeers[peripheral.identifier]?.activeWrite = nil
            self.outboundPeers[peripheral.identifier]?.queuedWrites.removeAll()
            self.outboundReceivers.removeValue(forKey: peripheral.identifier)
            if let error {
                self.lastError = error.localizedDescription
            }
            self.publishStateLocked()

            guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
            self.queue.asyncAfter(deadline: .now() + 0.8) { [weak self] in
                guard let self else { return }
                guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
                guard let peer = self.outboundPeers[peripheral.identifier], !peer.isConnected, !peer.isConnecting else {
                    return
                }
                peer.isConnecting = true
                self.publishStateLocked()
                Task { @MainActor [weak self, weak peripheral] in
                    guard let self, let peripheral else { return }
                    self.assignPeripheralDelegate(peripheral)
                    self.queue.async { [weak self, weak peripheral] in
                        guard let self, let peripheral else { return }
                        guard self.publicMeshEnabled, self.centralState == .poweredOn else { return }
                        guard let peer = self.outboundPeers[peripheral.identifier], !peer.isConnected, peer.isConnecting else {
                            return
                        }
                        self.central?.connect(peripheral, options: nil)
                        self.publishStateLocked()
                    }
                }
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        performOnQueue {
            guard error == nil else {
                self.lastError = error?.localizedDescription
                self.central?.cancelPeripheralConnection(peripheral)
                return
            }
            guard let service = peripheral.services?.first(where: { $0.uuid == self.profile.serviceUUID }) else {
                self.central?.cancelPeripheralConnection(peripheral)
                return
            }
            peripheral.discoverCharacteristics(
                [
                    self.profile.messageInCharacteristicUUID,
                    self.profile.messageOutCharacteristicUUID
                ],
                for: service
            )
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        performOnQueue {
            guard error == nil else {
                self.lastError = error?.localizedDescription
                self.central?.cancelPeripheralConnection(peripheral)
                return
            }
            guard let peer = self.outboundPeers[peripheral.identifier] else { return }

            peer.messageIn = service.characteristics?.first(where: { $0.uuid == self.profile.messageInCharacteristicUUID })
            peer.messageOut = service.characteristics?.first(where: { $0.uuid == self.profile.messageOutCharacteristicUUID })
            peer.lastSeen = Date()

            if let messageOut = peer.messageOut, messageOut.properties.contains(.notify) {
                peripheral.setNotifyValue(true, for: messageOut)
            } else {
                self.publishStateLocked()
                self.flushPendingOutboundPacketsLocked()
                self.requestPeerVerificationIfNeededLocked(routeKey: self.routeKey(for: peripheral))
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        performOnQueue {
            guard characteristic.uuid == self.profile.messageOutCharacteristicUUID else { return }
            guard let peer = self.outboundPeers[peripheral.identifier] else { return }

            peer.notifyEnabled = error == nil && characteristic.isNotifying
            if let error {
                self.lastError = error.localizedDescription
            }

            self.publishStateLocked()
            self.flushPendingOutboundPacketsLocked()
            if peer.notifyEnabled {
                self.requestPeerVerificationIfNeededLocked(routeKey: self.routeKey(for: peripheral))
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        performOnQueue {
            guard characteristic.uuid == self.profile.messageOutCharacteristicUUID else { return }
            guard error == nil, let value = characteristic.value, !value.isEmpty else {
                self.lastError = error?.localizedDescription
                return
            }

            let receiver = self.outboundReceivers[peripheral.identifier] ?? BleChunkReceiver(maxPacketSize: GattMeshProtocol.maxPacketBytes)
            self.outboundReceivers[peripheral.identifier] = receiver

            do {
                if let transportPacket = try receiver.onChunk(value) {
                    self.handleTransportPacketLocked(
                        transportPacket,
                        sourceRouteKey: self.routeKey(for: peripheral),
                        fallbackDisplayName: self.outboundPeers[peripheral.identifier]?.name
                    )
                }
            } catch {
                receiver.reset()
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        performOnQueue {
            guard characteristic.uuid == self.profile.messageInCharacteristicUUID,
                  let peer = self.outboundPeers[peripheral.identifier],
                  let activeWrite = peer.activeWrite else {
                return
            }

            guard error == nil else {
                self.lastError = error?.localizedDescription
                peer.activeWrite = nil
                return
            }

            activeWrite.index += 1
            if activeWrite.index >= activeWrite.chunks.count {
                peer.activeWrite = nil
                if activeWrite.fromQueuedPacket {
                    let isAuthority = self.profile.id == MeshProfile.authority.id
                    Task { @MainActor in
                        let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                        store.updateLocalMessageStatus(activeWrite.packetId, status: .sent)
                    }
                }
            }
            self.processOutboundWriteQueueLocked(peerId: peripheral.identifier)
        }
    }

    private func observeSettings() {
        if self.profile.id == MeshProfile.authority.id {
            // The authority mesh follows its own persisted toggle, gated on the provisioned group key
            // and peripheral-hosting support. It never follows the public mesh toggle.
            AdvancedSettingsStore.shared.$authorityMeshEnabled
                .receive(on: RunLoop.main)
                .sink { [weak self] isEnabled in
                    self?.queue.async { [weak self] in
                        guard let self else { return }
                        let resolved = isEnabled
                            && AuthorityMeshKeyStore.hasGroupKey()
                            && PlatformRuntime.supportsBlePeripheralHosting
                        self.publicMeshEnabled = resolved
                        self.lastError = nil
                        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
                        self.reconcileRuntimeLocked(clearRoutes: !resolved)
                    }
                }
                .store(in: &cancellables)
            return
        }
        AdvancedSettingsStore.shared.$publicMeshEnabled
            .receive(on: RunLoop.main)
            .sink { [weak self] isEnabled in
                self?.queue.async { [weak self] in
                    guard let self else { return }
                    self.publicMeshEnabled = isEnabled && PlatformRuntime.supportsBlePeripheralHosting
                    self.lastError = nil
                    BlePeripheralHostCoordinator.shared.setNeedsRefresh()
                    self.reconcileRuntimeLocked(clearRoutes: !self.publicMeshEnabled)
                }
            }
            .store(in: &cancellables)

        AdvancedSettingsStore.shared.$gattMeshNotificationsEnabled
            .receive(on: RunLoop.main)
            .sink { [weak self] isEnabled in
                self?.queue.async { [weak self] in
                    self?.notificationsEnabled = isEnabled
                }
            }
            .store(in: &cancellables)
    }

    private func reconcileRuntimeLocked(clearRoutes: Bool) {
        if clearRoutes || !publicMeshEnabled {
            stopScanningLocked()
            disconnectAllOutboundPeersLocked()
            if !publicMeshEnabled {
                centralRecoveryWork?.cancel()
                centralRecoveryWork = nil
                central?.delegate = nil
                central = nil
                centralState = .unknown
            }
            if clearRoutes || !publicMeshEnabled {
                clearTransientRoutesLocked()
            }
            publishStateLocked()
            return
        }

        if PlatformRuntime.supportsBlePeripheralHosting {
            if let peripheralManager {
                peripheralState = peripheralManager.state
            } else {
                peripheralState = .unknown
            }
            guard peripheralState == .poweredOn else {
                stopScanningLocked()
                publishStateLocked()
                return
            }
        }

        if central == nil, PlatformRuntime.supportsBlePeripheralHosting {
            central = makeCentralManager()
            centralState = central?.state ?? .unknown
        }

        guard centralState == .poweredOn else {
            stopScanningLocked()
            publishStateLocked()
            return
        }

        startScanningLocked()
        flushPendingOutboundPacketsLocked()
        publishStateLocked()
    }

    private func startScanningLocked() {
        guard !isScanning, let central, central.state == .poweredOn else { return }
        // Older mixed BLE stacks can silently drop hardware service filters.
        // Scan broadly and validate mesh markers in software for parity with Android.
        central.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        isScanning = true
    }

    private func stopScanningLocked() {
        guard isScanning, let central else { return }
        central.stopScan()
        isScanning = false
    }

    private func disconnectAllOutboundPeersLocked() {
        guard let central else { return }
        outboundPeers.values.forEach { peer in
            if peer.isConnected || peer.isConnecting {
                central.cancelPeripheralConnection(peer.peripheral)
            }
        }
    }

    private func clearTransientRoutesLocked() {
        discoveredPeers.removeAll()
        outboundPeers.removeAll()
        outboundReceivers.removeAll()
        inboundPeers.removeAll()
        inboundReceivers.removeAll()
        inboundReadPayloads.removeAll()
        inboundNotificationQueues.removeAll()
        activeInboundNotificationRequests.removeAll()
        peerLabels.removeAll()
        peerVerificationByRouteKey.removeAll()
        pendingPeerVerificationNonces.removeAll()
        pendingPeerVerificationRequestedAtMillis.removeAll()
    }

    private func sendOrQueuePacketLocked(_ packet: GattMeshPacket, queueWhenFailed: Bool) -> DispatchOutcome {
        if relayPacketLocked(packet: packet, excludeRouteKey: nil) {
            return .sent
        }

        guard queueWhenFailed else { return .failed }
        enqueuePendingOutboundPacketLocked(packet)
        return .queued
    }

    private func flushPendingOutboundPacketsLocked() {
        guard !pendingOutboundPackets.isEmpty else {
            persistPendingOutboundQueueLocked()
            return
        }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        var changed = false
        var remaining: [GattMeshPacket] = []
        remaining.reserveCapacity(pendingOutboundPackets.count)

        let orderedPackets = pendingOutboundPackets.sorted { lhs, rhs in
            if lhs.timestampMillis == rhs.timestampMillis {
                return lhs.id < rhs.id
            }
            return lhs.timestampMillis > rhs.timestampMillis
        }

        for packet in orderedPackets {
            if nowMillis - packet.timestampMillis >= pendingOutboundPacketMaxAgeMillis {
                pendingOutboundRetryStates.removeValue(forKey: packet.id)
                changed = true
                if packet.type == .chat {
                    let isAuthority = self.profile.id == MeshProfile.authority.id
                    Task { @MainActor in
                        let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                        store.updateLocalMessageStatus(packet.id, status: .failed)
                    }
                }
                continue
            }

            let retryState = pendingOutboundRetryStates[packet.id]
            if let retryState, retryState.nextEligibleAtMillis > nowMillis {
                remaining.append(packet)
                continue
            }

            if relayPacketLocked(packet: packet, excludeRouteKey: nil) {
                pendingOutboundRetryStates.removeValue(forKey: packet.id)
                changed = true
                if packet.type == .chat {
                    let isAuthority = self.profile.id == MeshProfile.authority.id
                    Task { @MainActor in
                        let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                        store.updateLocalMessageStatus(packet.id, status: .sent)
                    }
                }
            } else {
                let failureCount = (retryState?.failureCount ?? 0) + 1
                pendingOutboundRetryStates[packet.id] = GattMeshPendingOutboundRetryState(
                    failureCount: failureCount,
                    nextEligibleAtMillis: nowMillis + pendingOutboundRetryBackoffMillis(failureCount: failureCount)
                )
                changed = true
                remaining.append(packet)
            }
        }

        if pendingOutboundPackets != remaining {
            changed = true
        }
        pendingOutboundPackets = remaining

        if changed {
            persistPendingOutboundQueueLocked()
        }
        schedulePendingOutboundFlushLocked()
    }

    private func sendReceiptLocked(type: GattMeshReceiptType, messageIds: [String]) {
        guard let receiptPacket = GattMeshProtocol.makeReceiptPacket(type: type, messageIds: messageIds) else {
            return
        }
        _ = rememberMessageIdLocked(receiptPacket.id, timestampMillis: receiptPacket.timestampMillis)
        _ = relayPacketLocked(packet: receiptPacket, excludeRouteKey: nil)
    }

    @discardableResult
    private func relayPacketLocked(packet: GattMeshPacket, excludeRouteKey: String?) -> Bool {
        guard let transportPacket = GattMeshProtocol.transportPacket(for: packet) else {
            return false
        }

        var sentAny = false

        for (peerId, peer) in outboundPeers.sorted(by: { $0.key.uuidString < $1.key.uuidString }) {
            let routeKey = routeKey(for: peer.peripheral)
            guard routeKey != excludeRouteKey else { continue }
            guard peer.isConnected, peer.messageIn != nil else { continue }
            if enqueueOutboundPacketLocked(
                transportPacket,
                packetId: packet.id,
                peerId: peerId,
                fromQueuedPacket: pendingOutboundPackets.contains(where: { $0.id == packet.id })
            ) {
                sentAny = true
            }
        }

        for (centralId, peer) in inboundPeers.sorted(by: { $0.key.uuidString < $1.key.uuidString }) {
            let routeKey = routeKey(for: peer.central)
            guard routeKey != excludeRouteKey else { continue }
            guard peer.notifyEnabled else { continue }
            if enqueueInboundNotificationLocked(
                transportPacket,
                packetId: packet.id,
                centralId: centralId,
                fromQueuedPacket: pendingOutboundPackets.contains(where: { $0.id == packet.id })
            ) {
                sentAny = true
            }
        }

        return sentAny
    }

    private func enqueueOutboundPacketLocked(
        _ transportPacket: Data,
        packetId: String,
        peerId: UUID,
        fromQueuedPacket: Bool
    ) -> Bool {
        guard let peer = outboundPeers[peerId], let messageIn = peer.messageIn, peer.isConnected else {
            return false
        }

        let maxChunkSize = max(20, peer.peripheral.maximumWriteValueLength(for: .withResponse))
        let chunks = split(data: transportPacket, chunkSize: maxChunkSize)
        guard !chunks.isEmpty else { return false }

        peer.queuedWrites.append(
            OutboundWriteRequest(
                packetId: packetId,
                characteristic: messageIn,
                chunks: chunks,
                fromQueuedPacket: fromQueuedPacket
            )
        )
        processOutboundWriteQueueLocked(peerId: peerId)
        return true
    }

    private func processOutboundWriteQueueLocked(peerId: UUID) {
        guard let peer = outboundPeers[peerId], peer.isConnected else { return }

        if peer.activeWrite == nil {
            guard !peer.queuedWrites.isEmpty else { return }
            peer.activeWrite = peer.queuedWrites.removeFirst()
        }

        guard let activeWrite = peer.activeWrite else { return }
        guard activeWrite.index < activeWrite.chunks.count else {
            peer.activeWrite = nil
            processOutboundWriteQueueLocked(peerId: peerId)
            return
        }

        let chunk = activeWrite.chunks[activeWrite.index]
        peer.peripheral.writeValue(chunk, for: activeWrite.characteristic, type: .withResponse)
    }

    private func enqueueInboundNotificationLocked(
        _ transportPacket: Data,
        packetId: String,
        centralId: UUID,
        fromQueuedPacket: Bool
    ) -> Bool {
        guard let peer = inboundPeers[centralId], peer.notifyEnabled else { return false }
        let maxChunkSize = max(20, peer.central.maximumUpdateValueLength)
        let chunks = split(data: transportPacket, chunkSize: maxChunkSize)
        guard !chunks.isEmpty else { return false }

        inboundReadPayloads[centralId] = transportPacket
        inboundNotificationQueues[centralId, default: []].append(
            InboundNotificationRequest(
                packetId: packetId,
                chunks: chunks,
                fromQueuedPacket: fromQueuedPacket
            )
        )
        processInboundNotificationQueueLocked(centralId: centralId)
        return true
    }

    private func processInboundNotificationQueueLocked(centralId: UUID) {
        guard let peripheral = peripheralManager,
              let characteristic = hostedMessageOutCharacteristic,
              let peer = inboundPeers[centralId],
              peer.notifyEnabled else {
            return
        }

        if activeInboundNotificationRequests[centralId] == nil {
            guard var queued = inboundNotificationQueues[centralId], !queued.isEmpty else { return }
            activeInboundNotificationRequests[centralId] = queued.removeFirst()
            inboundNotificationQueues[centralId] = queued.isEmpty ? nil : queued
        }

        guard let activeRequest = activeInboundNotificationRequests[centralId] else { return }

        while activeRequest.index < activeRequest.chunks.count {
            let chunk = activeRequest.chunks[activeRequest.index]
            let updated = peripheral.updateValue(chunk, for: characteristic, onSubscribedCentrals: [peer.central])
            if !updated {
                return
            }
            activeRequest.index += 1
        }

        activeInboundNotificationRequests[centralId] = nil
        if activeRequest.fromQueuedPacket {
            let isAuthority = self.profile.id == MeshProfile.authority.id
            Task { @MainActor in
                let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                store.updateLocalMessageStatus(activeRequest.packetId, status: .sent)
            }
        }
        processInboundNotificationQueueLocked(centralId: centralId)
    }

    private func handleTransportPacketLocked(
        _ transportPacket: Data,
        sourceRouteKey: String,
        fallbackDisplayName: String?
    ) {
        guard
            let payload = GattMeshProtocol.unwrapTransportPacket(transportPacket),
            let packet = GattMeshProtocol.decodePacket(from: payload, profile: profile)
        else {
            return
        }

        peerLabels[sourceRouteKey] = packet.senderLabel
        guard GattMeshProtocol.isTimestampValid(packet.timestampMillis) else {
            return
        }

        guard rememberMessageIdLocked(packet.id, timestampMillis: packet.timestampMillis) else {
            return
        }

        let isAuthority = self.profile.id == MeshProfile.authority.id
        switch packet.type {
        case .chat:
            guard packet.isReadable else {
                relayIfNeededLocked(packet: packet, sourceRouteKey: sourceRouteKey)
                return
            }

            let senderLabel = packet.senderLabel
            let sourcePeerId = sourceRouteKey
            let originVerification = resolveMessageOriginVerificationLocked(
                packet: packet,
                sourceRouteKey: sourceRouteKey
            )
            let shouldNotify = notificationsEnabled && !isChatOpen

            Task { @MainActor in
                let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                let inserted = store.appendRemoteMessage(
                    id: packet.id,
                    text: packet.message,
                    senderLabel: senderLabel,
                    sourcePeerId: sourcePeerId,
                    originVerifiedRole: originVerification?.role,
                    originVerifiedAtMillis: originVerification?.verifiedAtMillis,
                    timestampMillis: packet.timestampMillis
                )
                if !inserted {
                    _ = store.updateRemoteMessageMetadata(
                        id: packet.id,
                        senderLabel: senderLabel,
                        sourcePeerId: sourcePeerId,
                        originVerifiedRole: originVerification?.role,
                        originVerifiedAtMillis: originVerification?.verifiedAtMillis
                    )
                }
                guard inserted, shouldNotify else { return }
                SOSNotificationCenter.notifyIncomingMessage(
                    sessionId: store.sessionId,
                    title: senderLabel,
                    body: packet.message,
                    kind: .generalChat,
                    route: .generalChat
                )
            }

            if isChatOpen {
                sendReceiptLocked(type: .read, messageIds: [packet.id])
            } else {
                sendReceiptLocked(type: .delivered, messageIds: [packet.id])
            }

        case .receipt:
            let receiptIds = packet.receiptMessageIds
            let recipientLabel = nonEmpty(packet.senderLabel.trimmingCharacters(in: .whitespacesAndNewlines))

            Task { @MainActor in
                let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
                switch packet.receiptType {
                case .delivered?:
                    store.markDelivered(messageIds: receiptIds, recipientLabel: recipientLabel)
                case .read?:
                    store.markRead(messageIds: receiptIds, recipientLabel: recipientLabel)
                case nil:
                    break
                }
            }

        case .authChallenge:
            if let nonce = packet.authNonce {
                respondToPeerVerificationChallengeLocked(sourceRouteKey: sourceRouteKey, nonce: nonce)
            }
            return

        case .authProof:
            if let nonce = packet.authNonce,
               let proofJSON = packet.authProofJSON {
                handlePeerVerificationProofLocked(
                    sourceRouteKey: sourceRouteKey,
                    nonce: nonce,
                    compactProofJSON: proofJSON
                )
            }
            return

        case .imageInit:
            handleInboundImageInitLocked(packet, sourceRouteKey: sourceRouteKey)
            return

        case .imageChunk:
            handleInboundImageChunkLocked(packet)
            return

        case .imageDone:
            handleInboundImageDoneLocked(packet)
            return

        case .pttJoin, .pttLeave, .pttFloorClaim, .pttFloorRelease, .pttAudio:
            handlePttPacketLocked(packet, sourceRouteKey: sourceRouteKey)
            return
        }

        relayIfNeededLocked(packet: packet, sourceRouteKey: sourceRouteKey)
    }

    // MARK: - Inbound image blobs (single-hop, authority mesh only)

    private func handleInboundImageInitLocked(_ packet: GattMeshPacket, sourceRouteKey: String) {
        guard profile.id == MeshProfile.authority.id else { return }
        guard
            let blobId = packet.blobId,
            let chunkCount = packet.blobChunkCount,
            let cipherBytes = packet.blobCipherBytes,
            let ivBase64 = packet.ivBase64,
            let keyId = packet.keyId,
            keyId == profile.payloadKeyId
        else {
            return
        }
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        inboundImageBlobs = inboundImageBlobs.filter { _, blob in
            nowMillis - blob.lastActivityAtMillis <= Self.inboundImageBlobTimeoutMillis
        }
        if inboundImageBlobs.count >= Self.maxConcurrentInboundImageBlobs,
           let oldestKey = inboundImageBlobs
               .min(by: { $0.value.lastActivityAtMillis < $1.value.lastActivityAtMillis })?.key {
            inboundImageBlobs.removeValue(forKey: oldestKey)
        }
        inboundImageBlobs[blobId] = InboundImageBlob(
            sourceRouteKey: sourceRouteKey,
            senderLabel: packet.senderLabel,
            ivBase64: ivBase64,
            keyId: keyId,
            mimeType: packet.blobMime,
            width: packet.blobWidth,
            height: packet.blobHeight,
            chunkCount: chunkCount,
            cipherBytes: cipherBytes,
            kind: packet.blobKind,
            durationMillis: packet.blobDurationMillis
        )
    }

    private func handleInboundImageChunkLocked(_ packet: GattMeshPacket) {
        guard
            let blobId = packet.blobId,
            let blobIndex = packet.blobIndex,
            let dataBase64 = packet.blobDataBase64,
            let bytes = Data(base64Encoded: dataBase64, options: [.ignoreUnknownCharacters]),
            let state = inboundImageBlobs[blobId],
            state.chunks.indices.contains(blobIndex)
        else {
            return
        }
        if state.chunks[blobIndex] == nil {
            state.chunks[blobIndex] = bytes
            state.receivedCount += 1
            state.receivedBytes += bytes.count
            if state.receivedBytes > state.cipherBytes {
                inboundImageBlobs.removeValue(forKey: blobId)
                return
            }
        }
        state.lastActivityAtMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        if state.receivedCount == state.chunkCount {
            inboundImageBlobs.removeValue(forKey: blobId)
            assembleInboundImageBlob(blobId: blobId, state: state)
        }
    }

    private func handleInboundImageDoneLocked(_ packet: GattMeshPacket) {
        guard let blobId = packet.blobId, let state = inboundImageBlobs[blobId] else { return }
        if state.receivedCount == state.chunkCount {
            inboundImageBlobs.removeValue(forKey: blobId)
            assembleInboundImageBlob(blobId: blobId, state: state)
        }
    }

    private func assembleInboundImageBlob(blobId: String, state: InboundImageBlob) {
        var cipher = Data(capacity: state.receivedBytes)
        for chunk in state.chunks {
            guard let chunk else { return }
            cipher.append(chunk)
        }
        guard cipher.count == state.cipherBytes else { return }
        guard let plain = GattMeshProtocol.decryptImageBlob(
            blobId: blobId,
            mimeType: state.mimeType,
            keyId: state.keyId,
            ivBase64: state.ivBase64,
            cipher: cipher,
            profile: profile
        ) else {
            return
        }
        let senderLabel = state.senderLabel
        let sourceRouteKey = state.sourceRouteKey
        if state.kind == GattMeshProtocol.blobKindVoice {
            guard let fileName = MeshImageFileStore.save(data: plain, blobId: blobId, fileExtension: "m4a") else {
                return
            }
            let durationMillis = state.durationMillis
            Task { @MainActor in
                GattMeshChatStore.authority.appendRemoteVoiceMessage(
                    messageId: blobId,
                    senderLabel: senderLabel,
                    sourcePeerId: sourceRouteKey,
                    voiceFileName: fileName,
                    voiceDurationMillis: durationMillis
                )
            }
            return
        }
        guard let fileName = MeshImageFileStore.save(data: plain, blobId: blobId) else { return }
        let width = state.width
        let height = state.height
        Task { @MainActor in
            GattMeshChatStore.authority.appendRemoteImageMessage(
                messageId: blobId,
                senderLabel: senderLabel,
                sourcePeerId: sourceRouteKey,
                imageFileName: fileName,
                imageWidth: width,
                imageHeight: height
            )
        }
    }

    private func relayIfNeededLocked(packet: GattMeshPacket, sourceRouteKey: String) {
        guard packet.type == .chat || packet.type == .receipt else { return }
        guard packet.hop < GattMeshProtocol.maxForwardHops else { return }
        let relayedPacket = GattMeshPacket(
            id: packet.id,
            senderLabel: packet.senderLabel,
            timestampMillis: packet.timestampMillis,
            message: packet.message,
            type: packet.type,
            receiptType: packet.receiptType,
            receiptMessageIds: packet.receiptMessageIds,
            authNonce: packet.authNonce,
            authProofJSON: packet.authProofJSON,
            hop: packet.hop + 1,
            protocol: packet.protocol,
            encrypted: packet.encrypted,
            keyId: packet.keyId,
            ivBase64: packet.ivBase64,
            cipherBase64: packet.cipherBase64,
            originProofJSON: packet.originProofJSON,
            originSignatureBase64: packet.originSignatureBase64,
            isReadable: packet.isReadable
        )
        _ = relayPacketLocked(packet: relayedPacket, excludeRouteKey: sourceRouteKey)
    }

    // MARK: - Telsiz (push-to-talk)

    /// Hop limit for relayed telsiz packets (matches Android `MAX_PTT_HOPS`).
    private static let maxPttHops = 3
    /// Throttle counters for telsiz audio TX/RX diagnostics logging.
    private var pttTxLogTick = 0
    private var pttRxLogTick = 0

    /// Join the telsiz session on this mesh (announces presence; starts the audio engine).
    func joinTelsiz() {
        performOnQueue {
            if self.pttController == nil {
                self.pttController = self.makePttControllerLocked()
            }
            self.pttController?.join()
        }
    }

    /// Leave the telsiz session (releases the floor + audio engine; clears presence).
    func leaveTelsiz() {
        performOnQueue { self.pttController?.leave() }
    }

    /// Press-to-talk down: claim the floor and start transmitting (if free).
    func pttPressTalk() {
        performOnQueue { self.pttController?.pressTalk() }
    }

    /// Press-to-talk up: release the floor.
    func pttReleaseTalk() {
        performOnQueue { self.pttController?.releaseTalk() }
    }

    /// Tear down the telsiz controller entirely (e.g. on mesh shutdown).
    func shutdownTelsizLocked() {
        pttController?.shutdown()
        pttController = nil
    }

    private func makePttControllerLocked() -> PttController {
        let controller = PttController(
            profileId: profile.id,
            localDisplayName: { GattMeshProtocol.localSenderLabel() },
            localAgency: { [weak self] in self?.localAgency },
            onSendControl: { [weak self] kind, claimId in
                self?.performOnQueue { self?.sendPttControlLocked(kind: kind, claimId: claimId) }
            },
            onSendAudio: { [weak self] talkTag, baseSeq, frames in
                self?.performOnQueue { self?.sendPttAudioLocked(talkTag: talkTag, baseSeq: baseSeq, frames: frames) }
            },
            onAudioActiveChanged: { [weak self] active in
                self?.performOnQueue { self?.setTelsizAudioActiveLocked(active) }
            }
        )
        controller.onStateChanged = { [weak self] state in
            // Delivered on the main queue by the controller, safe for @Published.
            self?.telsizState = state
        }
        return controller
    }

    private func sendPttControlLocked(kind: PttControlKind, claimId: Int64?) {
        let type: GattMeshPacketType
        switch kind {
        case .join: type = .pttJoin
        case .leave: type = .pttLeave
        case .floorClaim: type = .pttFloorClaim
        case .floorRelease: type = .pttFloorRelease
        }
        let packet = GattMeshProtocol.makePttControlPacket(
            id: "ptt-\(UUID().uuidString)",
            type: type,
            originId: localPttOriginId,
            senderLabel: GattMeshProtocol.localSenderLabel(),
            claimId: claimId.map(String.init) ?? "",
            agency: localAgency
        )
        // Remember our own id so a relayed copy looping back is deduped (not re-relayed).
        _ = rememberMessageIdLocked(packet.id, timestampMillis: packet.timestampMillis)
        _ = sendOrQueuePacketLocked(packet, queueWhenFailed: false)
    }

    private func sendPttAudioLocked(talkTag: String, baseSeq: Int, frames: [Data]) {
        guard !frames.isEmpty else { return }
        // Pack the bundled Opus frames as [u16 length][bytes]… so the receiver can split them back into
        // individual per-frame sequence numbers for the jitter buffer (mirrors Android broadcastPttAudio).
        var payload = Data()
        for frame in frames {
            let len = frame.count
            payload.append(UInt8((len >> 8) & 0xFF))
            payload.append(UInt8(len & 0xFF))
            payload.append(frame)
        }
        // Audio sender label is cosmetic (the speaker is identified by origin id); trim it so a bundled
        // (2-frame) packet still fits in one ATT write.
        let senderLabel = String(GattMeshProtocol.localSenderLabel().prefix(16))
        let packet = GattMeshProtocol.makePttAudioPacket(
            id: "pa" + talkTag + String(format: "%06d", baseSeq),
            originId: localPttOriginId,
            senderLabel: senderLabel.isEmpty ? "telsiz" : senderLabel,
            seq: baseSeq,
            audioBase64: payload.base64EncodedString()
        )
        // Remember our own id so a relayed copy looping back is deduped (not re-played/relayed).
        _ = rememberMessageIdLocked(packet.id, timestampMillis: packet.timestampMillis)
        sendPttFastLocked(packet: packet, excludeRouteKey: nil)
    }

    /// Real-time audio fast path: each frame is written once, best-effort, single chunk — no chunk
    /// queue, ACK waits, retries, or pacing. Frames that don't fit one ATT write are dropped (the
    /// receiver's jitter buffer conceals loss). Unlike `relayPacketLocked` (the reliable chat path).
    private func sendPttFastLocked(packet: GattMeshPacket, excludeRouteKey: String?) {
        guard let transportPacket = GattMeshProtocol.transportPacket(for: packet) else {
            NSLog("Telsiz TX: transportPacket encode FAILED")
            return
        }

        var sent = 0
        var droppedSize = 0
        var droppedFlow = 0
        var minMax = Int.max

        // Outbound links (we are central): WRITE_NO_RESPONSE, honouring CoreBluetooth flow control.
        for (_, peer) in outboundPeers {
            guard peer.isConnected, let messageIn = peer.messageIn else { continue }
            if routeKey(for: peer.peripheral) == excludeRouteKey { continue }
            let maxLen = max(20, peer.peripheral.maximumWriteValueLength(for: .withoutResponse))
            minMax = min(minMax, maxLen)
            guard transportPacket.count <= maxLen else { droppedSize += 1; continue }
            // Write regardless of `canSendWriteWithoutResponse`: gating on it dropped ~2/3 of audio
            // frames during a talk burst (it reports "not ready" between BLE connection events).
            // CoreBluetooth still sends best-effort when there's headroom; the receiver's jitter
            // buffer conceals the rare genuine overflow. Dropping here just made the peer go silent.
            if !peer.peripheral.canSendWriteWithoutResponse { droppedFlow += 1 }
            peer.peripheral.writeValue(transportPacket, for: messageIn, type: .withoutResponse)
            sent += 1
        }

        // Inbound links (we are peripheral): single best-effort notification per subscribed central.
        if let peripheral = peripheralManager, let characteristic = hostedMessageOutCharacteristic {
            for (_, peer) in inboundPeers {
                guard peer.notifyEnabled else { continue }
                if routeKey(for: peer.central) == excludeRouteKey { continue }
                let maxLen = max(20, peer.central.maximumUpdateValueLength)
                minMax = min(minMax, maxLen)
                guard transportPacket.count <= maxLen else { droppedSize += 1; continue }
                if peripheral.updateValue(transportPacket, for: characteristic, onSubscribedCentrals: [peer.central]) {
                    sent += 1
                } else {
                    droppedFlow += 1
                }
            }
        }

        // Always log a dropped-for-size event (the prime "no audio" suspect); otherwise throttle to ~1/s.
        pttTxLogTick += 1
        if packet.type == .pttAudio && (droppedSize > 0 || pttTxLogTick % 25 == 1) {
            NSLog("Telsiz TX audio: pkt=%dB minMaxWrite=%d sent=%d droppedSize=%d droppedFlow=%d",
                  transportPacket.count, minMax == Int.max ? -1 : minMax, sent, droppedSize, droppedFlow)
        }
    }

    private func setTelsizAudioActiveLocked(_ active: Bool) {
        // Free the radio for real-time audio: pause the (duplicate-allowing, radio-heavy) discovery scan
        // while someone is actively transmitting; resume it when the floor goes idle. Mirrors Android's
        // scan hold tied to active audio, not to the whole joined session.
        if active {
            stopScanningLocked()
        } else {
            startScanningLocked()
        }
    }

    private func handlePttPacketLocked(_ packet: GattMeshPacket, sourceRouteKey: String) {
        if let controller = pttController {
            // The origin id (stable, hop-independent) rides in blobId; fall back to the immediate sender.
            let origin = nonEmpty(packet.blobId) ?? sourceRouteKey
            let agency = nonEmpty(packet.blobKind)
            switch packet.type {
            case .pttJoin:
                controller.onPeerControl(origin: origin, senderLabel: packet.senderLabel, agency: agency, kind: .join, claimId: nil)
            case .pttLeave:
                controller.onPeerControl(origin: origin, senderLabel: packet.senderLabel, agency: agency, kind: .leave, claimId: nil)
            case .pttFloorClaim:
                controller.onPeerControl(origin: origin, senderLabel: packet.senderLabel, agency: agency, kind: .floorClaim, claimId: Int64(packet.message))
            case .pttFloorRelease:
                controller.onPeerControl(origin: origin, senderLabel: packet.senderLabel, agency: agency, kind: .floorRelease, claimId: nil)
            case .pttAudio:
                if let bundleBase64 = packet.blobDataBase64, let bundle = Data(base64Encoded: bundleBase64) {
                    // Split the [u16 length][bytes]… bundle back into per-frame seqs (baseSeq, +1, …).
                    let bytes = [UInt8](bundle)
                    var offset = 0
                    var seq = packet.blobIndex ?? 0
                    var frameCount = 0
                    while offset + 2 <= bytes.count {
                        let len = (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
                        offset += 2
                        if len <= 0 || offset + len > bytes.count { break }
                        let frame = bundle.subdata(in: offset..<(offset + len))
                        controller.onPeerAudio(origin: origin, senderLabel: packet.senderLabel, seq: seq, opus: frame)
                        offset += len
                        seq += 1
                        frameCount += 1
                    }
                    pttRxLogTick += 1
                    if pttRxLogTick % 25 == 1 {
                        NSLog("Telsiz RX audio: origin=%@ bundle=%dB frames=%d seq0=%d", origin, bundle.count, frameCount, packet.blobIndex ?? -1)
                    }
                } else {
                    NSLog("Telsiz RX audio: bundle base64 decode FAILED (blobData nil=%d)", packet.blobDataBase64 == nil ? 1 : 0)
                }
            default:
                break
            }
        } else {
            if packet.type == .pttAudio { NSLog("Telsiz RX audio: dropped — pttController is nil (not joined)") }
        }
        // Multi-hop: forward to this node's other peers (never back to the sender), bounded by hops.
        relayPttFastLocked(packet, sourceRouteKey: sourceRouteKey)
    }

    private func relayPttFastLocked(_ packet: GattMeshPacket, sourceRouteKey: String) {
        guard packet.hop < Self.maxPttHops else { return }
        // Forward an identical copy (same id/timestamp for dedup) with the hop counter bumped.
        let relayed = GattMeshPacket(
            id: packet.id,
            senderLabel: packet.senderLabel,
            timestampMillis: packet.timestampMillis,
            message: packet.message,
            type: packet.type,
            receiptType: nil,
            receiptMessageIds: [],
            authNonce: nil,
            authProofJSON: nil,
            hop: packet.hop + 1,
            protocol: packet.protocol,
            encrypted: false,
            keyId: nil,
            ivBase64: nil,
            cipherBase64: nil,
            originProofJSON: nil,
            originSignatureBase64: nil,
            isReadable: true,
            blobId: packet.blobId,
            blobIndex: packet.blobIndex,
            blobDataBase64: packet.blobDataBase64,
            blobKind: packet.blobKind
        )
        sendPttFastLocked(packet: relayed, excludeRouteKey: sourceRouteKey)
    }

    private func enqueuePendingOutboundPacketLocked(_ packet: GattMeshPacket) {
        if let existingIndex = pendingOutboundPackets.firstIndex(where: { $0.id == packet.id }) {
            pendingOutboundPackets[existingIndex] = packet
        } else {
            pendingOutboundPackets.append(packet)
        }

        pendingOutboundPackets = normalizePendingOutboundPackets(pendingOutboundPackets)
        if pendingOutboundPackets.count > Self.maxPendingOutboundPackets {
            pendingOutboundPackets = Array(pendingOutboundPackets.prefix(Self.maxPendingOutboundPackets))
        }

        let validIds = Set(pendingOutboundPackets.map(\.id))
        pendingOutboundRetryStates = pendingOutboundRetryStates.filter { validIds.contains($0.key) }
        persistPendingOutboundQueueLocked()
        schedulePendingOutboundFlushLocked()
    }

    private func schedulePendingOutboundFlushLocked() {
        pendingOutboundFlushWork?.cancel()
        pendingOutboundFlushWork = nil

        guard !pendingOutboundPackets.isEmpty else { return }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let nextEligibleAtMillis: Int64
        if pendingOutboundPackets.contains(where: { pendingOutboundRetryStates[$0.id] == nil }) {
            nextEligibleAtMillis = nowMillis
        } else {
            nextEligibleAtMillis = pendingOutboundPackets.compactMap { packet in
                pendingOutboundRetryStates[packet.id]?.nextEligibleAtMillis
            }.min() ?? nowMillis
        }
        let delayMillis = max(0, nextEligibleAtMillis - nowMillis)

        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.performOnQueue {
                self.pendingOutboundFlushWork = nil
                self.flushPendingOutboundPacketsLocked()
            }
        }
        pendingOutboundFlushWork = work
        queue.asyncAfter(deadline: .now() + .milliseconds(Int(delayMillis)), execute: work)
    }

    private func pendingOutboundRetryBackoffMillis(failureCount: Int) -> Int64 {
        guard failureCount > 1 else { return pendingOutboundRetryBaseMillis }
        let shift = min(failureCount - 1, 6)
        let scaled = pendingOutboundRetryBaseMillis * Int64(1 << shift)
        return min(pendingOutboundRetryMaxMillis, scaled)
    }

    private func normalizePendingOutboundPackets(_ packets: [GattMeshPacket]) -> [GattMeshPacket] {
        let deduplicated = Dictionary(grouping: packets, by: \.id)
            .compactMap { _, values in
                values.max { lhs, rhs in
                    if lhs.timestampMillis == rhs.timestampMillis {
                        return lhs.id < rhs.id
                    }
                    return lhs.timestampMillis < rhs.timestampMillis
                }
            }
            .sorted { lhs, rhs in
                if lhs.timestampMillis == rhs.timestampMillis {
                    return lhs.id < rhs.id
                }
                return lhs.timestampMillis > rhs.timestampMillis
            }
        return deduplicated
    }

    private func persistPendingOutboundQueueLocked() {
        let destination = persistedPendingQueueURL
        guard !pendingOutboundPackets.isEmpty else {
            try? FileManager.default.removeItem(at: destination)
            return
        }

        let snapshot = GattMeshPendingOutboundQueueSnapshot(
            version: Self.persistedPendingQueueVersion,
            packets: pendingOutboundPackets,
            retryStates: pendingOutboundRetryStates
        )
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? LocalEncryptedFileStore.write(data, to: destination)
    }

    private func loadPersistedPendingOutboundQueueLocked() {
        guard let payload = try? LocalEncryptedFileStore.read(from: persistedPendingQueueURL) else {
            pendingOutboundPackets = []
            pendingOutboundRetryStates = [:]
            return
        }

        guard let snapshot = try? JSONDecoder().decode(GattMeshPendingOutboundQueueSnapshot.self, from: payload.data),
              snapshot.version == Self.persistedPendingQueueVersion else {
            pendingOutboundPackets = []
            pendingOutboundRetryStates = [:]
            try? FileManager.default.removeItem(at: persistedPendingQueueURL)
            return
        }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let filteredPackets = normalizePendingOutboundPackets(snapshot.packets.filter { packet in
            GattMeshProtocol.isTimestampValid(packet.timestampMillis, nowMillis: nowMillis) &&
                nowMillis - packet.timestampMillis < pendingOutboundPacketMaxAgeMillis
        })
        pendingOutboundPackets = Array(filteredPackets.prefix(Self.maxPendingOutboundPackets))
        let packetIds = Set(pendingOutboundPackets.map(\.id))
        pendingOutboundRetryStates = snapshot.retryStates.filter { packetIds.contains($0.key) }

        if !payload.wasEncrypted || pendingOutboundPackets.count != snapshot.packets.count || pendingOutboundRetryStates != snapshot.retryStates {
            persistPendingOutboundQueueLocked()
        }
    }

    private func authenticatedChatPacketLocked(from basePacket: GattMeshPacket) -> GattMeshPacket {
        guard basePacket.type == .chat else { return basePacket }
        guard let proofPayload = buildRoleProofPayloadLocked(sessionNonce: basePacket.id, allowExpired: false),
              let proofJSON = GattMeshProtocol.encodeCompactRoleProofPayload(
                proof: proofPayload,
                includeSessionNonce: false
              ) else {
            return basePacket
        }
        guard let deviceCertificate = try? securityRepository.getStoredCertificateSync(allowExpired: false) else {
            return basePacket
        }
        guard let originSignature = try? deviceIdentityStore.signPayload(
            GattMeshProtocol.buildMessageOriginSignaturePayload(packet: basePacket),
            with: deviceCertificate.privateKey
        ).base64EncodedString() else {
            return basePacket
        }
        guard let authenticatedPacket = GattMeshProtocol.withOriginAuthentication(
            packet: basePacket,
            compactProofJSON: proofJSON,
            originSignatureBase64: originSignature
        ), GattMeshProtocol.transportPacket(for: authenticatedPacket) != nil else {
            return basePacket
        }
        return authenticatedPacket
    }

    private func buildRoleProofPayloadLocked(
        sessionNonce: String,
        allowExpired: Bool
    ) -> RoleProofPayload? {
        guard let deviceCertificate = try? securityRepository.getStoredCertificateSync(allowExpired: allowExpired) else {
            return nil
        }

        let timestampMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let allowExpiredCertificate = !deviceCertificate.roleCertificate.isValid(at: timestampMillis)
        if !allowExpired && allowExpiredCertificate {
            return nil
        }

        let proof = RoleProofPayload(
            devicePublicKey: deviceCertificate.publicKeyBase64,
            certificate: deviceCertificate.certificateBytes.base64EncodedString(),
            timestamp: timestampMillis,
            signature: "",
            sessionNonce: sessionNonce,
            allowExpiredCertificate: allowExpiredCertificate
        )
        guard let signature = try? deviceIdentityStore.signPayload(
            proof.signaturePayloadBytes(),
            with: deviceCertificate.privateKey
        ) else {
            return nil
        }

        return RoleProofPayload(
            devicePublicKey: proof.devicePublicKey,
            certificate: proof.certificate,
            timestamp: proof.timestamp,
            signature: signature.base64EncodedString(),
            sessionNonce: proof.sessionNonce,
            allowExpiredCertificate: proof.allowExpiredCertificate
        )
    }

    private func requestPeerVerificationIfNeededLocked(routeKey: String) {
        guard isDirectRouteSendReadyLocked(routeKey: routeKey) else { return }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        if let verification = peerVerificationByRouteKey[routeKey],
           nowMillis - verification.verifiedAtMillis < peerVerificationCacheTTLMillis {
            return
        }
        if let requestedAtMillis = pendingPeerVerificationRequestedAtMillis[routeKey],
           nowMillis - requestedAtMillis < peerVerificationRetryIntervalMillis {
            return
        }

        guard let challenge = GattMeshProtocol.makePeerVerificationChallenge(),
              let nonce = challenge.authNonce,
              sendDirectPacketLocked(packet: challenge, routeKey: routeKey) else {
            return
        }

        pendingPeerVerificationNonces[routeKey] = nonce
        pendingPeerVerificationRequestedAtMillis[routeKey] = nowMillis
    }

    private func respondToPeerVerificationChallengeLocked(sourceRouteKey: String, nonce: String) {
        guard let proofPayload = buildRoleProofPayloadLocked(sessionNonce: nonce, allowExpired: true),
              let proofJSON = GattMeshProtocol.encodeCompactRoleProofPayload(
                proof: proofPayload,
                includeSessionNonce: false
              ),
              let packet = GattMeshProtocol.makePeerVerificationProof(
                nonce: nonce,
                compactProofJSON: proofJSON
              ) else {
            return
        }
        _ = sendDirectPacketLocked(packet: packet, routeKey: sourceRouteKey)
    }

    private func handlePeerVerificationProofLocked(
        sourceRouteKey: String,
        nonce: String,
        compactProofJSON: String
    ) {
        guard pendingPeerVerificationNonces[sourceRouteKey] == nonce else { return }
        pendingPeerVerificationNonces.removeValue(forKey: sourceRouteKey)
        pendingPeerVerificationRequestedAtMillis[sourceRouteKey] = Int64(Date().timeIntervalSince1970 * 1_000)

        guard let proofPayload = GattMeshProtocol.decodeCompactRoleProofPayload(
            raw: compactProofJSON,
            fallbackSessionNonce: nonce
        ), let verifiedProof = try? roleProofVerifier.verifyProofPayload(
            proof: proofPayload,
            expectedSessionNonce: nonce
        ), let verifiedRole = GattMeshProtocol.resolveVerifiedRole(verifiedProof) else {
            let didRemove = peerVerificationByRouteKey.removeValue(forKey: sourceRouteKey) != nil
            if didRemove {
                publishStateLocked()
            }
            return
        }

        let verifiedAtMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let nextState = GattMeshPeerVerificationState(
            role: verifiedRole,
            agency: GattMeshProtocol.resolveVerifiedAgency(verifiedProof),
            verifiedAtMillis: verifiedAtMillis,
            peerIdentityKey: stablePeerIdentityKey(for: proofPayload)
        )
        let didChange = peerVerificationByRouteKey[sourceRouteKey] != nextState
        peerVerificationByRouteKey[sourceRouteKey] = nextState
        if didChange {
            publishStateLocked()
        }
        backfillVerifiedDirectPeerMessagesLocked(
            sourceRouteKey: sourceRouteKey,
            verifiedRole: verifiedRole,
            verifiedAtMillis: verifiedAtMillis
        )
    }

    private func verifyMessageOriginLocked(packet: GattMeshPacket) -> GattMeshPeerVerificationState? {
        guard packet.type == .chat else { return nil }
        guard let proofJSON = nonEmpty(packet.originProofJSON),
              nonEmpty(packet.originSignatureBase64) != nil,
              let proofPayload = GattMeshProtocol.decodeCompactRoleProofPayload(
                raw: proofJSON,
                fallbackSessionNonce: packet.id
              ),
              let verifiedProof = try? messageOriginRoleProofVerifier.verifyProofPayload(
                proof: proofPayload,
                expectedSessionNonce: packet.id
              ),
              let verifiedRole = GattMeshProtocol.resolveVerifiedRole(verifiedProof),
              GattMeshProtocol.verifyOriginSignature(packet: packet, proof: verifiedProof) else {
            return nil
        }

        return GattMeshPeerVerificationState(
            role: verifiedRole,
            agency: GattMeshProtocol.resolveVerifiedAgency(verifiedProof),
            verifiedAtMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            peerIdentityKey: stablePeerIdentityKey(for: proofPayload)
        )
    }

    private func resolveMessageOriginVerificationLocked(
        packet: GattMeshPacket,
        sourceRouteKey: String
    ) -> GattMeshPeerVerificationState? {
        if let verifiedOrigin = verifyMessageOriginLocked(packet: packet) {
            return verifiedOrigin
        }
        let hasEmbeddedOriginProof = nonEmpty(packet.originProofJSON) != nil ||
            nonEmpty(packet.originSignatureBase64) != nil
        if hasEmbeddedOriginProof || packet.hop != 0 {
            return nil
        }
        return peerVerificationByRouteKey[sourceRouteKey]
    }

    private func backfillVerifiedDirectPeerMessagesLocked(
        sourceRouteKey: String,
        verifiedRole: String,
        verifiedAtMillis: Int64
    ) {
        let isAuthority = self.profile.id == MeshProfile.authority.id
        Task { @MainActor in
            let store = isAuthority ? GattMeshChatStore.authority : GattMeshChatStore.shared
            _ = store.backfillOriginVerification(
                for: sourceRouteKey,
                originVerifiedRole: verifiedRole,
                originVerifiedAtMillis: verifiedAtMillis
            )
        }
    }

    @discardableResult
    private func sendDirectPacketLocked(packet: GattMeshPacket, routeKey: String) -> Bool {
        guard let transportPacket = GattMeshProtocol.transportPacket(for: packet) else {
            return false
        }

        if let outboundEntry = outboundPeers.first(where: { self.routeKey(for: $0.value.peripheral) == routeKey }) {
            return enqueueOutboundPacketLocked(
                transportPacket,
                packetId: packet.id,
                peerId: outboundEntry.key,
                fromQueuedPacket: false
            )
        }

        if let inboundEntry = inboundPeers.first(where: { self.routeKey(for: $0.value.central) == routeKey }) {
            return enqueueInboundNotificationLocked(
                transportPacket,
                packetId: packet.id,
                centralId: inboundEntry.key,
                fromQueuedPacket: false
            )
        }

        return false
    }

    private func isDirectRouteSendReadyLocked(routeKey: String) -> Bool {
        if let outboundEntry = outboundPeers.first(where: { self.routeKey(for: $0.value.peripheral) == routeKey }) {
            let peer = outboundEntry.value
            return peer.isConnected && peer.messageIn != nil
        }
        if let inboundEntry = inboundPeers.first(where: { self.routeKey(for: $0.value.central) == routeKey }) {
            return inboundEntry.value.notifyEnabled
        }
        return false
    }

    private func rememberMessageIdLocked(_ messageId: String, timestampMillis: Int64) -> Bool {
        if seenMessageIds[messageId] != nil {
            return false
        }
        seenMessageIds[messageId] = timestampMillis
        if seenMessageIds.count > 4_096 {
            let trimmed = seenMessageIds
                .sorted { $0.value < $1.value }
                .suffix(4_096)
            seenMessageIds = Dictionary(uniqueKeysWithValues: trimmed.map { ($0.key, $0.value) })
        }
        return true
    }

    private func upsertInboundPeerLocked(
        for central: CBCentral,
        routeKey: String,
        notifyEnabled: Bool? = nil
    ) {
        let name = peerLabels[routeKey]
            ?? localized("GENERAL_CHAT_PEER_NAME_FALLBACK")

        if var existing = inboundPeers[central.identifier] {
            existing.name = peerLabels[routeKey] ?? existing.name
            existing.lastSeen = Date()
            if let notifyEnabled {
                existing.notifyEnabled = notifyEnabled
            }
            inboundPeers[central.identifier] = existing
        } else {
            inboundPeers[central.identifier] = InboundPeer(
                central: central,
                name: name,
                notifyEnabled: notifyEnabled ?? false,
                lastSeen: Date()
            )
        }
    }

    @MainActor
    private func assignPeripheralDelegate(_ peripheral: CBPeripheral) {
        peripheral.delegate = self
    }

    private func scheduleCleanupLocked() {
        cleanupWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.performOnQueue {
                self.cleanupStaleStateLocked()
                self.scheduleCleanupLocked()
            }
        }
        cleanupWork = work
        queue.asyncAfter(deadline: .now() + cleanupInterval, execute: work)
    }

    private func cleanupStaleStateLocked() {
        let staleThreshold = Date().addingTimeInterval(-discoveryStaleInterval)
        discoveredPeers = discoveredPeers.filter { key, peer in
            if let outbound = outboundPeers[key], outbound.isConnected || outbound.isConnecting {
                return true
            }
            return peer.lastSeen >= staleThreshold
        }

        inboundPeers = inboundPeers.filter { _, peer in
            peer.lastSeen >= staleThreshold || peer.notifyEnabled
        }

        let nowMillis = Int64(Date().timeIntervalSince1970 * 1_000)
        let timeThreshold = nowMillis - GattMeshProtocol.maxMessageAgeMillis
        seenMessageIds = seenMessageIds.filter { $0.value >= timeThreshold }
        peerVerificationByRouteKey = peerVerificationByRouteKey.filter {
            nowMillis - $0.value.verifiedAtMillis < peerVerificationCacheTTLMillis
        }
        pendingPeerVerificationRequestedAtMillis = pendingPeerVerificationRequestedAtMillis.filter {
            nowMillis - $0.value < peerVerificationRetryIntervalMillis
        }
        let pendingRouteKeys = Set(pendingPeerVerificationRequestedAtMillis.keys)
        pendingPeerVerificationNonces = pendingPeerVerificationNonces.filter { pendingRouteKeys.contains($0.key) }
        publishStateLocked()
    }

    private func publishStateLocked() {
        let runtimeError = currentRuntimeErrorLocked()
        let effectiveLastError: String?
        if let runtimeError {
            effectiveLastError = runtimeError
        } else if let lastError, !isRuntimeStateError(lastError) {
            effectiveLastError = lastError
        } else {
            effectiveLastError = nil
        }
        let connectedPeers = buildConnectedPeersLocked()
        let snapshot = GattMeshRuntimeSnapshot(
            isEnabled: publicMeshEnabled,
            isScanning: isScanning,
            connectedPeerCount: connectedPeers.count,
            discoveredPeerCount: discoveredPeers.count,
            sendReadyPeerCount: connectedPeers.filter(\.isSendReady).count,
            connectedPeers: connectedPeers,
            lastError: effectiveLastError
        )

        DispatchQueue.main.async {
            self.snapshot = snapshot
        }
    }

    private func buildConnectedPeersLocked() -> [GattMeshConnectedPeer] {
        var peers: [GattMeshConnectedPeerAccumulator] = []

        for (_, peer) in outboundPeers.sorted(by: { $0.key.uuidString < $1.key.uuidString }) {
            guard peer.isConnected else { continue }
            let routeKey = routeKey(for: peer.peripheral)
            appendConnectedPeerLocked(
                routeKey: routeKey,
                displayName: peerLabels[routeKey] ?? peer.name,
                isSendReady: peer.messageIn != nil,
                verification: peerVerificationByRouteKey[routeKey],
                into: &peers
            )
        }

        for (_, peer) in inboundPeers.sorted(by: { $0.key.uuidString < $1.key.uuidString }) {
            let routeKey = routeKey(for: peer.central)
            appendConnectedPeerLocked(
                routeKey: routeKey,
                displayName: peerLabels[routeKey] ?? peer.name,
                isSendReady: peer.notifyEnabled,
                verification: peerVerificationByRouteKey[routeKey],
                into: &peers
            )
        }

        return peers.map(\.peer)
    }

    private func appendConnectedPeerLocked(
        routeKey: String,
        displayName: String,
        isSendReady: Bool,
        verification: GattMeshPeerVerificationState?,
        into peers: inout [GattMeshConnectedPeerAccumulator]
    ) {
        let peer = GattMeshConnectedPeer(
            id: routeKey,
            routeKeys: [routeKey],
            displayName: displayName,
            transportLabel: localized("GENERAL_CHAT_PEER_SUBTITLE"),
            isSendReady: isSendReady,
            verificationStatus: verification == nil ? .unverified : .verified,
            verifiedRole: verification?.role,
            verifiedAgency: verification?.agency,
            verifiedAtMillis: verification?.verifiedAtMillis
        )
        let peerIdentityKey = verification?.peerIdentityKey
        let routeIdentityKey = routeIdentityKey(for: routeKey)

        if let index = peers.firstIndex(where: { existing in
            if let peerIdentityKey, existing.peerIdentityKey == peerIdentityKey {
                return true
            }
            if let routeIdentityKey, existing.routeIdentityKeys.contains(routeIdentityKey) {
                return true
            }
            return false
        }) {
            peers[index] = mergeConnectedPeerAccumulator(
                peers[index],
                with: peer,
                peerIdentityKey: peerIdentityKey,
                routeIdentityKey: routeIdentityKey
            )
            return
        }

        peers.append(
            GattMeshConnectedPeerAccumulator(
                peer: peer,
                peerIdentityKey: peerIdentityKey,
                routeIdentityKeys: routeIdentityKey.map { [$0] } ?? []
            )
        )
    }

    private func mergeConnectedPeerAccumulator(
        _ existing: GattMeshConnectedPeerAccumulator,
        with next: GattMeshConnectedPeer,
        peerIdentityKey: String?,
        routeIdentityKey: String?
    ) -> GattMeshConnectedPeerAccumulator {
        let preferredDisplayName = preferredConnectedPeerDisplayName(
            current: existing.peer.displayName,
            candidate: next.displayName
        )
        let preferredVerificationPeer: GattMeshConnectedPeer
        if (next.verifiedAtMillis ?? 0) >= (existing.peer.verifiedAtMillis ?? 0) {
            preferredVerificationPeer = next
        } else {
            preferredVerificationPeer = existing.peer
        }
        let mergedVerifiedAtMillis = max(existing.peer.verifiedAtMillis ?? 0, next.verifiedAtMillis ?? 0)
        var routeIdentityKeys = existing.routeIdentityKeys
        if let routeIdentityKey {
            routeIdentityKeys.insert(routeIdentityKey)
        }
        return GattMeshConnectedPeerAccumulator(
            peer: GattMeshConnectedPeer(
                id: existing.peer.id,
                routeKeys: Array(Set(existing.peer.routeKeys).union(next.routeKeys)).sorted(),
                displayName: preferredDisplayName,
                transportLabel: localized("GENERAL_CHAT_PEER_SUBTITLE"),
                isSendReady: existing.peer.isSendReady || next.isSendReady,
                verificationStatus: existing.peer.verificationStatus == .verified || next.verificationStatus == .verified
                    ? .verified
                    : .unverified,
                verifiedRole: preferredVerificationPeer.verifiedRole,
                verifiedAgency: preferredVerificationPeer.verifiedAgency,
                verifiedAtMillis: mergedVerifiedAtMillis > 0 ? mergedVerifiedAtMillis : nil
            ),
            peerIdentityKey: peerIdentityKey ?? existing.peerIdentityKey,
            routeIdentityKeys: routeIdentityKeys
        )
    }

    private func preferredConnectedPeerDisplayName(current: String, candidate: String) -> String {
        let fallbackName = localized("GENERAL_CHAT_PEER_NAME_FALLBACK")
        let normalizedCurrent = nonEmpty(current.trimmingCharacters(in: .whitespacesAndNewlines)) ?? fallbackName
        let normalizedCandidate = nonEmpty(candidate.trimmingCharacters(in: .whitespacesAndNewlines)) ?? fallbackName
        let currentIsFallback = normalizedCurrent == fallbackName
        let candidateIsFallback = normalizedCandidate == fallbackName
        if currentIsFallback != candidateIsFallback {
            return currentIsFallback ? normalizedCandidate : normalizedCurrent
        }
        return normalizedCandidate.count > normalizedCurrent.count ? normalizedCandidate : normalizedCurrent
    }

    private func routeIdentityKey(for routeKey: String) -> String? {
        guard let separator = routeKey.firstIndex(of: ":") else { return nil }
        let rawValue = routeKey[routeKey.index(after: separator)...]
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return rawValue.isEmpty ? nil : rawValue
    }

    private func stablePeerIdentityKey(for proof: RoleProofPayload) -> String? {
        nonEmpty(proof.devicePublicKey.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private func errorMessage(for state: CBManagerState) -> String {
        switch state {
        case .unsupported:
            return localized("GENERAL_CHAT_ERROR_UNSUPPORTED")
        case .unauthorized:
            return localized("GENERAL_CHAT_ERROR_UNAUTHORIZED")
        case .poweredOff:
            return localized("GENERAL_CHAT_ERROR_POWERED_OFF")
        case .resetting:
            return localized("GENERAL_CHAT_ERROR_RESETTING")
        case .unknown:
            return localized("GENERAL_CHAT_ERROR_UNKNOWN")
        case .poweredOn:
            return ""
        @unknown default:
            return localized("GENERAL_CHAT_ERROR_UNAVAILABLE")
        }
    }

    private func debugDescription(for state: CBManagerState) -> String {
        switch state {
        case .unknown:
            return "unknown"
        case .resetting:
            return "resetting"
        case .unsupported:
            return "unsupported"
        case .unauthorized:
            return "unauthorized"
        case .poweredOff:
            return "poweredOff"
        case .poweredOn:
            return "poweredOn"
        @unknown default:
            return "other(\(state.rawValue))"
        }
    }

    private func currentRuntimeErrorLocked() -> String? {
        if let central {
            centralState = central.state
        }
        if let peripheralManager {
            peripheralState = peripheralManager.state
        } else {
            peripheralState = .unknown
        }

        guard publicMeshEnabled else { return nil }

        if centralState != .poweredOn {
            return errorMessage(for: centralState)
        }

        if PlatformRuntime.supportsBlePeripheralHosting && peripheralState != .poweredOn {
            return errorMessage(for: peripheralState)
        }

        return nil
    }

    private func isRuntimeStateError(_ message: String) -> Bool {
        [
            errorMessage(for: .unsupported),
            errorMessage(for: .unauthorized),
            errorMessage(for: .poweredOff),
            errorMessage(for: .resetting),
            errorMessage(for: .unknown)
        ].contains(message)
    }

    private func matchesMeshAdvertisement(_ advertisementData: [String: Any]) -> Bool {
        let serviceUUIDs = advertisedServiceUUIDs(from: advertisementData)
        if serviceUUIDs.contains(self.profile.serviceUUID) || serviceUUIDs.contains(sosServiceUUID) {
            return true
        }

        guard let manufacturerID = manufacturerIdentifier(from: advertisementData) else {
            return false
        }
        return manufacturerID == meshInitiatorRankManufacturerID
    }

    private func advertisedServiceUUIDs(from advertisementData: [String: Any]) -> Set<CBUUID> {
        var serviceUUIDs = Set((advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? [])
        let overflowServiceUUIDs = (advertisementData[CBAdvertisementDataOverflowServiceUUIDsKey] as? [CBUUID]) ?? []
        serviceUUIDs.formUnion(overflowServiceUUIDs)
        return serviceUUIDs
    }

    private func manufacturerIdentifier(from advertisementData: [String: Any]) -> UInt16? {
        guard let manufacturerData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
              manufacturerData.count >= 2 else {
            return nil
        }
        return UInt16(manufacturerData[0]) | (UInt16(manufacturerData[1]) << 8)
    }

    private func routeKey(for peripheral: CBPeripheral) -> String {
        "peripheral:\(peripheral.identifier.uuidString.lowercased())"
    }

    private func routeKey(for central: CBCentral) -> String {
        "central:\(central.identifier.uuidString.lowercased())"
    }

    private func split(data: Data, chunkSize: Int) -> [Data] {
        guard chunkSize > 0, !data.isEmpty else { return [] }
        var chunks: [Data] = []
        var offset = 0
        while offset < data.count {
            let end = min(offset + chunkSize, data.count)
            chunks.append(data.subdata(in: offset..<end))
            offset = end
        }
        return chunks
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    private func makeCentralManager() -> CBCentralManager {
        CBCentralManager(
            delegate: self,
            queue: queue,
            options: [
                CBCentralManagerOptionRestoreIdentifierKey: centralRestoreIdentifier,
                CBCentralManagerOptionShowPowerAlertKey: false
            ]
        )
    }

    private func scheduleCentralRecoveryLocked() {
        guard centralRecoveryWork == nil else { return }
        NSLog("GattMesh central entered resetting; scheduling recovery")
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.performOnQueue {
                guard self.centralState == .resetting else {
                    self.centralRecoveryWork = nil
                    return
                }
                NSLog("GattMesh central still resetting; recreating central manager")
                self.stopScanningLocked()
                self.disconnectAllOutboundPeersLocked()
                self.clearTransientRoutesLocked()
                self.central?.delegate = nil
                self.central = self.makeCentralManager()
                self.centralState = .unknown
                self.centralRecoveryWork = nil
                self.publishStateLocked()
            }
        }
        centralRecoveryWork = work
        queue.asyncAfter(deadline: .now() + 2, execute: work)
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private func performOnQueue(_ block: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()
        } else {
            queue.async(execute: block)
        }
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync(execute: block)
    }

    private struct DiscoveredPeer {
        let peripheral: CBPeripheral
        var name: String
        var lastSeen: Date
    }

    private struct InboundPeer {
        let central: CBCentral
        var name: String
        var notifyEnabled: Bool
        var lastSeen: Date
    }

    private final class OutboundPeer {
        let peripheral: CBPeripheral
        var name: String
        var messageIn: CBCharacteristic?
        var messageOut: CBCharacteristic?
        var notifyEnabled = false
        var isConnected = false
        var isConnecting = false
        var lastSeen = Date()
        var queuedWrites: [OutboundWriteRequest] = []
        var activeWrite: OutboundWriteRequest?

        init(peripheral: CBPeripheral, name: String) {
            self.peripheral = peripheral
            self.name = name
        }
    }

    private final class OutboundWriteRequest {
        let packetId: String
        let characteristic: CBCharacteristic
        let chunks: [Data]
        let fromQueuedPacket: Bool
        var index = 0

        init(packetId: String, characteristic: CBCharacteristic, chunks: [Data], fromQueuedPacket: Bool) {
            self.packetId = packetId
            self.characteristic = characteristic
            self.chunks = chunks
            self.fromQueuedPacket = fromQueuedPacket
        }
    }

    private final class InboundNotificationRequest {
        let packetId: String
        let chunks: [Data]
        let fromQueuedPacket: Bool
        var index = 0

        init(packetId: String, chunks: [Data], fromQueuedPacket: Bool) {
            self.packetId = packetId
            self.chunks = chunks
            self.fromQueuedPacket = fromQueuedPacket
        }
    }

    private enum DispatchOutcome {
        case sent
        case queued
        case failed
    }
}
