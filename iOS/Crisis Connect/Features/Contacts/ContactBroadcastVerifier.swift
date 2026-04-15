//
//  ContactBroadcastVerifier.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import CoreBluetooth
import Combine

struct ContactVerificationResult {
    let shareId: String
    let sessionCode: String
    let displayName: String?
    let avatarBase64: String?
    let platform: String
    let remoteDeviceId: String
    let lastKnownBleAddress: String
}

enum ContactVerificationFailure {
    case notFound
    case bluetoothPoweredOff
    case bluetoothUnauthorized
    case bluetoothUnsupported
}

enum ContactVerificationOutcome {
    case verified(ContactVerificationResult)
    case failed(ContactVerificationFailure)
}

enum ContactVerificationProgress: Equatable {
    case idle
    case searching
    case pairing
}

private struct P2pBootstrapPayload {
    let shareId: String
    let sessionCode: String
    let displayName: String?
    let avatarBase64: String?
    let platform: String
    let protocolVersion: Int
    let serverNonce: String
    let serverDeviceId: String
}

private enum P2pControlStage {
    case serverHello
    case serverFinish
}

final class ContactBroadcastVerifier: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published private(set) var isScanning: Bool = false
    @Published private(set) var bluetoothState: CBManagerState = .unknown
    @Published private(set) var progress: ContactVerificationProgress = .idle

    private let queue = DispatchQueue(label: "contact.p2p.scan")
    private var central: CBCentralManager?
    private var timeoutWork: DispatchWorkItem?
    private var shouldStartWhenReady = false
    private var completion: ((ContactVerificationOutcome) -> Void)?
    private var targetPayload: ContactQRPayload?
    private var keyData: Data?
    private var localDisplayName: String = ""
    private var activePeripheral: CBPeripheral?
    private var controlCharacteristic: CBCharacteristic?
    private var bootstrapPayload: P2pBootstrapPayload?
    private var controlStage: P2pControlStage?
    private var clientNonce: String?
    private var authenticatedServerName: String?
    private var authenticatedServerAvatarBase64: String?

    override init() {
        super.init()
    }

    func verify(
        payload: ContactQRPayload,
        localDisplayName: String,
        timeout: TimeInterval = 12,
        completion: @escaping (ContactVerificationOutcome) -> Void
    ) {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopScanning(disconnect: true)
            self.targetPayload = payload
            self.keyData = P2pBleProtocol.decodeBase64(payload.key)
            self.localDisplayName = localDisplayName.trimmingCharacters(in: .whitespacesAndNewlines)
            self.completion = completion
            self.shouldStartWhenReady = true
            self.controlCharacteristic = nil
            self.bootstrapPayload = nil
            self.controlStage = nil
            self.clientNonce = nil
            self.authenticatedServerName = nil
            self.ensureCentralManager()
            self.setProgress(.searching)

            let work = DispatchWorkItem { [weak self] in
                self?.finish(.failed(.notFound))
            }
            self.timeoutWork = work
            self.queue.asyncAfter(deadline: .now() + timeout, execute: work)
            self.startScanIfPossible()
        }
    }

    func cancel() {
        queue.async { [weak self] in
            guard let self else { return }
            self.stopScanning(disconnect: true)
            self.shouldStartWhenReady = false
            self.completion = nil
            self.targetPayload = nil
            self.keyData = nil
            self.localDisplayName = ""
            self.controlCharacteristic = nil
            self.bootstrapPayload = nil
            self.controlStage = nil
            self.clientNonce = nil
            self.authenticatedServerName = nil
            self.setProgress(.idle)
        }
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        DispatchQueue.main.async { [weak self] in
            self?.bluetoothState = central.state
        }
        switch central.state {
        case .poweredOn:
            startScanIfPossible()
        case .poweredOff:
            finish(.failed(.bluetoothPoweredOff))
        case .unauthorized:
            finish(.failed(.bluetoothUnauthorized))
        case .unsupported:
            finish(.failed(.bluetoothUnsupported))
        default:
            break
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        guard let payload = targetPayload else { return }
        guard activePeripheral == nil else { return }

        if let expectedShareId = payload.shareId?.nilIfEmpty {
            guard
                let advertisedShareId = P2pBleProtocol.advertisedShareId(
                    from: advertisementData,
                    peripheralName: peripheral.name
                ),
                P2pBleProtocol.normalizeShareId(advertisedShareId) == P2pBleProtocol.normalizeShareId(expectedShareId)
            else {
                return
            }
            connectIfNeeded(peripheral)
            return
        }

        connectIfNeeded(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        setProgress(.pairing)
        peripheral.discoverServices([P2pBleProtocol.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if activePeripheral?.identifier == peripheral.identifier {
            activePeripheral = nil
        }
        resumeScanIfNeeded()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if activePeripheral?.identifier == peripheral.identifier {
            activePeripheral = nil
        }
        if completion != nil {
            resumeScanIfNeeded()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            disconnectAndResumeScan()
            return
        }
        guard let service = peripheral.services?.first(where: { $0.uuid == P2pBleProtocol.serviceUUID }) else {
            disconnectAndResumeScan()
            return
        }
        peripheral.discoverCharacteristics(
            [
                P2pBleProtocol.idCharacteristicUUID,
                P2pBleProtocol.bootstrapCharacteristicUUID,
                P2pBleProtocol.controlCharacteristicUUID
            ],
            for: service
        )
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            disconnectAndResumeScan()
            return
        }
        let idCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.idCharacteristicUUID })
        controlCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.controlCharacteristicUUID })
        let bootstrapCharacteristic = service.characteristics?.first(where: { $0.uuid == P2pBleProtocol.bootstrapCharacteristicUUID })
        let firstReadTarget = idCharacteristic ?? bootstrapCharacteristic
        guard let firstReadTarget, controlCharacteristic != nil else {
            disconnectAndResumeScan()
            return
        }
        peripheral.readValue(for: firstReadTarget)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else {
            disconnectAndResumeScan()
            return
        }
        switch characteristic.uuid {
        case P2pBleProtocol.idCharacteristicUUID:
            let identity = String(decoding: value, as: UTF8.self)
                .replacingOccurrences(of: "share:", with: "", options: [.caseInsensitive])
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if let expectedShareId = targetPayload?.shareId?.nilIfEmpty,
               P2pBleProtocol.normalizeShareId(identity) != P2pBleProtocol.normalizeShareId(expectedShareId) {
                disconnectAndResumeScan()
                return
            }
            guard let bootstrap = characteristic.service?.characteristics?.first(where: { $0.uuid == P2pBleProtocol.bootstrapCharacteristicUUID }) else {
                disconnectAndResumeScan()
                return
            }
            peripheral.readValue(for: bootstrap)

        case P2pBleProtocol.bootstrapCharacteristicUUID:
            guard let payload = parseBootstrapPayload(from: value) else {
                disconnectAndResumeScan()
                return
            }
            if payload.protocolVersion != P2pBleProtocol.protocolVersion || payload.serverNonce.isEmpty || payload.serverDeviceId.isEmpty {
                disconnectAndResumeScan()
                return
            }
            if let expectedShareId = targetPayload?.shareId?.nilIfEmpty,
               P2pBleProtocol.normalizeShareId(payload.shareId) != P2pBleProtocol.normalizeShareId(expectedShareId) {
                disconnectAndResumeScan()
                return
            }
            bootstrapPayload = payload
            authenticatedServerName = payload.displayName
            clientNonce = P2pBleProtocol.randomNonceBase64()
            controlStage = .serverHello
            sendClientHello()

        case P2pBleProtocol.controlCharacteristicUUID:
            guard
                let object = try? JSONSerialization.jsonObject(with: value) as? [String: Any],
                let type = (object["type"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            else {
                disconnectAndResumeScan()
                return
            }

            if type == P2pBleProtocol.typeError {
                disconnectAndResumeScan()
                return
            }

            switch controlStage {
            case .serverHello:
                handleServerHello(object)
            case .serverFinish:
                handleServerFinish(object)
            case .none:
                disconnectAndResumeScan()
            }

        default:
            disconnectAndResumeScan()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == P2pBleProtocol.controlCharacteristicUUID else {
            disconnectAndResumeScan()
            return
        }
        guard let controlCharacteristic else {
            disconnectAndResumeScan()
            return
        }
        peripheral.readValue(for: controlCharacteristic)
    }

    private func sendClientHello() {
        guard
            let peripheral = activePeripheral,
            let controlCharacteristic,
            let payload = bootstrapPayload,
            let keyData,
            let clientNonce
        else {
            disconnectAndResumeScan()
            return
        }

        let localSessionCode = SecureLocalStore.shared.getOrCreateP2pSessionCode()
        let localDeviceId = SecureLocalStore.shared.getOrCreateP2pDeviceId()
        let localAvatarBase64 = localAvatarPayload()
        guard let proof = P2pBleProtocol.hmacBase64(
            key: keyData,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeClientHello),
                ("shareId", payload.shareId),
                ("serverSessionCode", payload.sessionCode),
                ("serverDeviceId", payload.serverDeviceId),
                ("serverNonce", payload.serverNonce),
                ("clientSessionCode", localSessionCode),
                ("clientDeviceId", localDeviceId),
                ("clientNonce", clientNonce),
                ("clientName", localDisplayName),
                ("clientPlatform", "ios")
            ])
        ) else {
            disconnectAndResumeScan()
            return
        }

        let frame: [String: Any?] = [
            "type": P2pBleProtocol.typeClientHello,
            "protocolVersion": P2pBleProtocol.protocolVersion,
            "shareId": payload.shareId,
            "clientSessionCode": localSessionCode,
            "clientDeviceId": localDeviceId,
            "clientNonce": clientNonce,
            "clientPlatform": "ios",
            "clientName": localDisplayName,
            "avatarB64": localAvatarBase64,
            "proof": proof
        ]
        let compactFrame = frame.compactMapValues { $0 }
        guard let data = try? JSONSerialization.data(withJSONObject: compactFrame, options: []) else {
            disconnectAndResumeScan()
            return
        }
        peripheral.writeValue(data, for: controlCharacteristic, type: .withResponse)
    }

    private func handleServerHello(_ object: [String: Any]) {
        guard
            let payload = bootstrapPayload,
            let keyData,
            let clientNonce,
            let type = object["type"] as? String,
            type == P2pBleProtocol.typeServerHello
        else {
            disconnectAndResumeScan()
            return
        }

        let remoteShareId = ((object["shareId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let remoteSessionCode = ((object["sessionCode"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let remoteDeviceId = ((object["serverDeviceId"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let remoteNonce = ((object["serverNonce"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let remoteName = ((object["serverName"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let remoteAvatarBase64 = ((object["avatarB64"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let proof = ((object["proof"] as? String) ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        guard
            P2pBleProtocol.normalizeShareId(remoteShareId) == payload.shareId,
            remoteSessionCode == payload.sessionCode,
            remoteDeviceId == payload.serverDeviceId,
            remoteNonce == payload.serverNonce,
            !proof.isEmpty
        else {
            disconnectAndResumeScan()
            return
        }

        let localSessionCode = SecureLocalStore.shared.getOrCreateP2pSessionCode()
        let localDeviceId = SecureLocalStore.shared.getOrCreateP2pDeviceId()
        let expectedProof = P2pBleProtocol.hmacBase64(
            key: keyData,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeServerHello),
                ("shareId", payload.shareId),
                ("serverSessionCode", payload.sessionCode),
                ("serverDeviceId", payload.serverDeviceId),
                ("serverNonce", payload.serverNonce),
                ("clientSessionCode", localSessionCode),
                ("clientDeviceId", localDeviceId),
                ("clientNonce", clientNonce),
                ("clientName", localDisplayName),
                ("clientPlatform", "ios"),
                ("serverName", remoteName ?? payload.displayName ?? ""),
                ("serverPlatform", payload.platform)
            ])
        )
        guard P2pBleProtocol.secureEqualsBase64(expectedProof, proof) else {
            disconnectAndResumeScan()
            return
        }

        authenticatedServerName = remoteName ?? payload.displayName
        authenticatedServerAvatarBase64 = remoteAvatarBase64 ?? payload.avatarBase64
        controlStage = .serverFinish
        guard let finishProof = P2pBleProtocol.hmacBase64(
            key: keyData,
            payload: P2pBleProtocol.buildProofPayload([
                ("type", P2pBleProtocol.typeClientFinish),
                ("shareId", payload.shareId),
                ("serverSessionCode", payload.sessionCode),
                ("serverDeviceId", payload.serverDeviceId),
                ("serverNonce", payload.serverNonce),
                ("clientSessionCode", localSessionCode),
                ("clientDeviceId", localDeviceId),
                ("clientNonce", clientNonce),
                ("clientPlatform", "ios"),
                ("serverHelloProof", proof)
            ])
        ) else {
            disconnectAndResumeScan()
            return
        }
        writeControl([
            "type": P2pBleProtocol.typeClientFinish,
            "proof": finishProof
        ])
    }

    private func handleServerFinish(_ object: [String: Any]) {
        guard
            let type = object["type"] as? String,
            type == P2pBleProtocol.typeServerFinish,
            ((object["status"] as? String) ?? "").lowercased() == "ok",
            let payload = bootstrapPayload,
            let peripheral = activePeripheral
        else {
            disconnectAndResumeScan()
            return
        }

        let result = ContactVerificationResult(
            shareId: payload.shareId,
            sessionCode: payload.sessionCode,
            displayName: authenticatedServerName ?? payload.displayName,
            avatarBase64: authenticatedServerAvatarBase64 ?? payload.avatarBase64,
            platform: payload.platform,
            remoteDeviceId: payload.serverDeviceId,
            lastKnownBleAddress: peripheral.identifier.uuidString.uppercased()
        )
        finish(.verified(result))
    }

    private func writeControl(_ payload: [String: Any]) {
        guard
            let peripheral = activePeripheral,
            let controlCharacteristic,
            let data = try? JSONSerialization.data(withJSONObject: payload, options: [])
        else {
            disconnectAndResumeScan()
            return
        }
        peripheral.writeValue(data, for: controlCharacteristic, type: .withResponse)
    }

    private func parseBootstrapPayload(from data: Data) -> P2pBootstrapPayload? {
        guard
            let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let shareId = (json["shareId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            let sessionCode = (json["sessionCode"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            let platform = (json["platform"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            let protocolVersion = json["protocolVersion"] as? Int,
            let serverNonce = (json["serverNonce"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
            let serverDeviceId = (json["serverDeviceId"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
        else {
            return nil
        }
        let displayName = (json["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let avatarBase64 = (json["avatarB64"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        return P2pBootstrapPayload(
            shareId: P2pBleProtocol.normalizeShareId(shareId),
            sessionCode: sessionCode,
            displayName: displayName,
            avatarBase64: avatarBase64,
            platform: platform,
            protocolVersion: protocolVersion,
            serverNonce: serverNonce,
            serverDeviceId: serverDeviceId
        )
    }

    private func localAvatarPayload() -> String? {
        guard let data = ProfileMetadataStore.loadAvatarThumbnailData(), !data.isEmpty else {
            return nil
        }
        let encoded = data.base64EncodedString().trimmingCharacters(in: .whitespacesAndNewlines)
        return encoded.nilIfEmpty
    }

    private func connectIfNeeded(_ peripheral: CBPeripheral) {
        guard let central else { return }
        activePeripheral = peripheral
        peripheral.delegate = self
        setProgress(.pairing)
        central.connect(peripheral, options: nil)
    }

    private func disconnectAndResumeScan() {
        guard let central, let activePeripheral else {
            resumeScanIfNeeded()
            return
        }
        controlCharacteristic = nil
        bootstrapPayload = nil
        controlStage = nil
        clientNonce = nil
        authenticatedServerName = nil
        authenticatedServerAvatarBase64 = nil
        central.cancelPeripheralConnection(activePeripheral)
    }

    private func resumeScanIfNeeded() {
        guard completion != nil else { return }
        activePeripheral = nil
        controlCharacteristic = nil
        bootstrapPayload = nil
        controlStage = nil
        clientNonce = nil
        authenticatedServerName = nil
        shouldStartWhenReady = true
        setProgress(.searching)
        startScanIfPossible()
    }

    private func startScanIfPossible() {
        guard shouldStartWhenReady else { return }
        ensureCentralManager()
        guard let central, central.state == .poweredOn else { return }
        guard targetPayload != nil, keyData != nil else {
            finish(.failed(.notFound))
            return
        }
        shouldStartWhenReady = false
        setProgress(.searching)
        central.scanForPeripherals(
            withServices: nil,
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
        )
        DispatchQueue.main.async { [weak self] in
            self?.isScanning = true
        }
    }

    private func ensureCentralManager() {
        if central == nil {
            central = CBCentralManager(delegate: self, queue: queue)
        }
    }

    private func stopScanning(disconnect: Bool) {
        timeoutWork?.cancel()
        timeoutWork = nil
        if let central, central.isScanning {
            central.stopScan()
        }
        if disconnect, let central, let activePeripheral {
            central.cancelPeripheralConnection(activePeripheral)
        }
        activePeripheral = nil
        DispatchQueue.main.async { [weak self] in
            self?.isScanning = false
        }
    }

    private func finish(_ outcome: ContactVerificationOutcome) {
        stopScanning(disconnect: true)
        shouldStartWhenReady = false
        let callback = completion
        completion = nil
        targetPayload = nil
        keyData = nil
        localDisplayName = ""
        controlCharacteristic = nil
        bootstrapPayload = nil
        controlStage = nil
        clientNonce = nil
        authenticatedServerName = nil
        setProgress(.idle)
        DispatchQueue.main.async {
            callback?(outcome)
        }
    }

    private func setProgress(_ progress: ContactVerificationProgress) {
        DispatchQueue.main.async { [weak self] in
            self?.progress = progress
        }
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
