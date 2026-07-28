//
//  SignalScannerViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
import CoreBluetooth

struct DiscoveredPeripheral: Identifiable, Equatable {
    let id: UUID
    var name: String
    var rssi: Int
    var lastSeen: Date
}

final class SignalScannerViewModel: NSObject, ObservableObject, CBCentralManagerDelegate {
    @Published var state: CBManagerState = .unknown
    @Published var isScanning: Bool = false
    @Published var devices: [DiscoveredPeripheral] = []

    private var central: CBCentralManager!
    private let queue = DispatchQueue(label: "bt.scanner.queue")

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
    }

    var scanTitleKey: LocalizedStringKey { isScanning ? "Stop Scan" : "Start Scan" }
    var scanStatusKey: LocalizedStringKey { isScanning ? "Scanning..." : "No devices found" }

    var stateKey: LocalizedStringKey {
        switch state {
        case .unknown: return "Unknown"
        case .resetting: return "Resetting"
        case .unsupported: return "Unsupported"
        case .unauthorized: return "Unauthorized"
        case .poweredOff: return "Powered Off"
        case .poweredOn: return "Powered On"
        @unknown default: return "Unknown"
        }
    }

    func start() {
        guard state == .poweredOn else { return }
        if !isScanning {
            central.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
            DispatchQueue.main.async { self.isScanning = true }
        }
    }

    func stop() {
        if isScanning {
            central.stopScan()
            DispatchQueue.main.async { self.isScanning = false }
        }
    }

    // CBCentralManagerDelegate
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        DispatchQueue.main.async { [weak self] in
            self?.state = central.state
            if central.state != .poweredOn { self?.isScanning = false }
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String : Any],
                        rssi RSSI: NSNumber) {
        let id = peripheral.identifier
        let localName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name ?? "Unknown"
        let rssi = RSSI.intValue
        let now = Date()
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            if let idx = self.devices.firstIndex(where: { $0.id == id }) {
                self.devices[idx].rssi = rssi
                if self.devices[idx].name == "Unknown" && localName != "Unknown" {
                    self.devices[idx].name = localName
                }
                self.devices[idx].lastSeen = now
            } else {
                self.devices.append(DiscoveredPeripheral(id: id, name: localName, rssi: rssi, lastSeen: now))
            }
        }
    }

    deinit { stop() }
}
