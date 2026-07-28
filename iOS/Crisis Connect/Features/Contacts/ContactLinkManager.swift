//
//  ContactLinkManager.swift
//  Crisis Connect
//
//  Created by Assistant on 16.01.2026
//

import Foundation
import CoreBluetooth
import Combine
import CryptoKit

final class ContactLinkManager: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published private(set) var status: RescueConnectionStatus = .disconnected
    @Published private(set) var isScanning: Bool = false

    private let queue = DispatchQueue(label: "contact.link.central")
    private let queueKey = DispatchSpecificKey<Void>()
    private let serviceUUID = CBUUID(string: "0000CC00-0000-1000-8000-00805F9B34FB")

    private var central: CBCentralManager?
    private var connection: RescueConnection?
    private var activePeripheral: CBPeripheral?
    private var targetBroadcastId: String?
    private var targetSessionId: UUID?
    private var retryWork: DispatchWorkItem?

    override init() {
        super.init()
        queue.setSpecific(key: queueKey, value: ())
    }

    func start(broadcastId: String, sessionId: UUID) {
        let normalized = broadcastId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        queue.async { [weak self] in
            guard let self else { return }
            self.ensureCentralInitialized()
            self.targetBroadcastId = normalized
            self.targetSessionId = sessionId
            self.beginScan()
        }
    }

    func stop() {
        queue.async { [weak self] in
            guard let self else { return }
            self.retryWork?.cancel()
            self.retryWork = nil
            if self.central?.isScanning == true {
                self.central?.stopScan()
            }
            self.connection?.disconnect()
            self.connection = nil
            self.activePeripheral = nil
            self.targetBroadcastId = nil
            self.targetSessionId = nil
            DispatchQueue.main.async { self.isScanning = false }
        }
    }

    func sendMessage(_ text: String) -> Bool {
        syncOnQueue { connection?.sendMessage(text) ?? false }
    }

    func sendReadReceipt() -> Bool {
        syncOnQueue { connection?.sendReadReceipt() ?? false }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn {
            beginScan()
        } else {
            updateStatus(.disconnected)
        }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String : Any],
        rssi RSSI: NSNumber
    ) {
        guard let targetBroadcastId else { return }
        let normalized = parseBroadcastId(from: advertisementData)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
        if let normalized, normalized == targetBroadcastId {
            connectIfNeeded(peripheral, broadcastId: normalized)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        connection?.didConnect()
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        updateStatus(.failed)
        scheduleRetry()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connection?.didDisconnect(error: error)
        connection = nil
        activePeripheral = nil
        scheduleRetry()
    }

    // MARK: - Private

    private func beginScan() {
        ensureCentralInitialized()
        guard let central else { return }
        guard central.state == .poweredOn else { return }
        guard targetBroadcastId != nil else { return }
        guard connection == nil else { return }
        if !central.isScanning {
            central.scanForPeripherals(
                withServices: nil,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
        DispatchQueue.main.async { [weak self] in
            self?.isScanning = true
        }
    }

    private func connectIfNeeded(_ peripheral: CBPeripheral, broadcastId: String?) {
        guard let central else { return }
        guard connection == nil else { return }
        guard let sessionId = targetSessionId else { return }
        guard let broadcastId else { return }
        guard let key = ContactStore.shared.aesKey(for: broadcastId) else { return }
        let connection = RescueConnection(
            peripheral: peripheral,
            sessionId: sessionId,
            broadcastId: broadcastId,
            preSharedKey: key,
            queue: queue
        )
        connection.onStatusChange = { [weak self] status in
            self?.updateStatus(status)
            if status == .ready {
                self?.stopScanIfNeeded()
            } else if status == .failed || status == .disconnected {
                self?.scheduleRetry()
            }
        }
        connection.onBroadcastId = { [weak self] broadcastId in
            self?.validateBroadcastId(broadcastId)
        }
        self.connection = connection
        self.activePeripheral = peripheral
        central.connect(peripheral, options: nil)
    }

    private func validateBroadcastId(_ broadcastId: String) {
        guard let targetBroadcastId else { return }
        let normalized = broadcastId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard normalized == targetBroadcastId else {
            disconnectAndRetry()
            return
        }
    }

    private func disconnectAndRetry() {
        if let central, let activePeripheral {
            central.cancelPeripheralConnection(activePeripheral)
        }
        connection = nil
        activePeripheral = nil
        scheduleRetry()
    }

    private func stopScanIfNeeded() {
        if central?.isScanning == true {
            central?.stopScan()
        }
        DispatchQueue.main.async { [weak self] in
            self?.isScanning = false
        }
    }

    private func scheduleRetry() {
        retryWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.beginScan()
        }
        retryWork = work
        queue.asyncAfter(deadline: .now() + 1.0, execute: work)
    }

    private func updateStatus(_ status: RescueConnectionStatus) {
        DispatchQueue.main.async { [weak self] in
            self?.status = status
        }
    }

    private func parseBroadcastId(from advertisementData: [String: Any]) -> String? {
        guard let serviceData = advertisementData[CBAdvertisementDataServiceDataKey] else { return nil }
        let targetKey = serviceUUID.uuidString.lowercased()
        let payload: Data?

        if let typed = serviceData as? [CBUUID: Data] {
            payload = typed.first(where: { $0.key.uuidString.lowercased() == targetKey })?.value
        } else if let typed = serviceData as? [String: Data] {
            payload = typed.first(where: { $0.key.lowercased() == targetKey })?.value
        } else if let typed = serviceData as? [AnyHashable: Data] {
            payload = typed.first(where: { key, _ in
                if let uuid = key as? CBUUID {
                    return uuid.uuidString.lowercased() == targetKey
                }
                if let string = key as? String {
                    return string.lowercased() == targetKey
                }
                return false
            })?.value
        } else {
            payload = nil
        }

        guard let payload, let raw = String(data: payload, encoding: .utf8) else { return nil }
        return raw.replacingOccurrences(of: "ccid:", with: "", options: [.caseInsensitive])
    }

    private func syncOnQueue<T>(_ block: () -> T) -> T {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return block()
        }
        return queue.sync { block() }
    }

    private func ensureCentralInitialized() {
        guard central == nil else { return }
        central = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }
}
