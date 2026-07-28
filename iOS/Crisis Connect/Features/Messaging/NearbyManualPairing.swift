//
//  NearbyManualPairing.swift
//  Crisis Connect
//
//  User-driven "find this person nearby" search (iOS port of Android's NearbyContactsTab engine):
//  the user picks someone from their address book, and we scan for "open to add" beacons (0xCCA6),
//  running the number-keyed SPAKE2 handshake against each. Only the person whose verified number
//  matches can complete it — a stranger's device answers, fails the key exchange, and learns
//  nothing, not even which number we were looking for.
//
//  This differs from NearbyAutoLink (the silent chat-open bootstrap) in three ways: it is driven
//  by an explicit user action, it targets someone who need not be a contact yet (the initiator
//  side of NearbyPairingCentral saves the contact itself on success), and it reports its progress
//  to a UI instead of finishing silently.
//

import Combine
import CoreBluetooth
import Foundation

@MainActor
final class NearbyManualPairing: NSObject, ObservableObject {

    enum Status: Equatable {
        case idle
        case searching
        case paired(displayName: String)
        /// Window closed with no completed handshake — nobody matching was in range (or they
        /// declined; deliberately indistinguishable, matching Android's notMatched semantics).
        case notFound
        case failed
    }

    @Published private(set) var status: Status = .idle

    /// Matches Android's manual search: long enough to walk across a room and for the peer to
    /// notice and answer the consent prompt; the in-flight handshake keeps its own longer timeout.
    private static let searchWindow: TimeInterval = 30

    private let engine = Engine()

    /// Starts (or restarts) a search for the owner of `phone`. Progress arrives on `status`.
    func search(displayName: String, phone: String) {
        let trimmed = phone.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        status = .searching
        engine.start(phone: trimmed, window: Self.searchWindow) { [weak self] outcome in
            Task { @MainActor [weak self] in
                guard let self, self.status == .searching else { return }
                switch outcome {
                case .paired: self.status = .paired(displayName: displayName)
                case .notFound: self.status = .notFound
                case .failed: self.status = .failed
                }
            }
        }
    }

    func cancel() {
        engine.stop()
        status = .idle
    }

    // MARK: - Scan loop (background queue; the same shape as NearbyAutoLink's)

    private final class Engine: NSObject, CBCentralManagerDelegate {
        enum Outcome { case paired, notFound, failed }

        private let queue = DispatchQueue(label: "nearby.manual.pairing")
        private var manager: CBCentralManager?
        private var running = false
        private var windowExpired = false
        private var sawBluetoothFailure = false
        private var tried = Set<UUID>()
        private var pending: [CBPeripheral] = []
        private var retained: [CBPeripheral] = []
        private var activePair: NearbyPairingCentral?
        private var phone = ""
        private var completion: ((Outcome) -> Void)?

        func start(phone: String, window: TimeInterval, completion: @escaping (Outcome) -> Void) {
            queue.async { [self] in
                stopLocked()
                running = true
                windowExpired = false
                sawBluetoothFailure = false
                tried.removeAll()
                self.phone = phone
                self.completion = completion
                manager = CBCentralManager(
                    delegate: self, queue: queue,
                    options: [CBCentralManagerOptionShowPowerAlertKey: false]
                )
                queue.asyncAfter(deadline: .now() + window) { [weak self] in
                    guard let self, self.running else { return }
                    self.windowExpired = true
                    // An in-flight handshake keeps its own consent timeout; otherwise wrap up.
                    if self.activePair == nil { self.finish(.notFound) }
                }
            }
        }

        func stop() {
            queue.async { self.stopLocked() }
        }

        private func stopLocked() {
            running = false
            completion = nil
            activePair = nil
            pending.removeAll()
            retained.removeAll()
            manager?.stopScan()
            manager = nil
        }

        private func finish(_ outcome: Outcome) {
            guard running else { return }
            let done = completion
            stopLocked()
            done?(outcome)
        }

        func centralManagerDidUpdateState(_ central: CBCentralManager) {
            guard running else { return }
            switch central.state {
            case .poweredOn:
                central.scanForPeripherals(withServices: [NearbyPairingGatt.serviceUUID], options: nil)
            case .unknown, .resetting:
                break
            default:
                // Bluetooth off/unauthorized is a FAILURE, not "nobody nearby" — the user can fix
                // it, so the UI must say so instead of blaming the peer's absence.
                sawBluetoothFailure = true
                finish(.failed)
            }
        }

        func centralManager(
            _ central: CBCentralManager,
            didDiscover peripheral: CBPeripheral,
            advertisementData: [String: Any],
            rssi RSSI: NSNumber
        ) {
            guard running, !tried.contains(peripheral.identifier) else { return }
            tried.insert(peripheral.identifier)
            retained.append(peripheral)
            pending.append(peripheral)
            startNextPair(central)
        }

        private func startNextPair(_ central: CBCentralManager) {
            guard running, activePair == nil else { return }
            guard !pending.isEmpty else {
                if windowExpired { finish(.notFound) }
                return
            }
            let target = pending.removeFirst()
            central.stopScan()
            let pair = NearbyPairingCentral()
            activePair = pair
            // The pairing session drives the central's connect callbacks itself.
            central.delegate = pair
            pair.pair(central: central, target: target, candidatePhone: phone) { [weak self] result in
                guard let self else { return }
                self.queue.async {
                    self.activePair = nil
                    if case .paired = result {
                        self.finish(.paired)
                        return
                    }
                    guard self.running, let central = self.manager else { return }
                    central.delegate = self
                    if central.state == .poweredOn, !self.windowExpired {
                        central.scanForPeripherals(
                            withServices: [NearbyPairingGatt.serviceUUID], options: nil
                        )
                    }
                    self.startNextPair(central)
                }
            }
        }
    }
}


// MARK: - Passive nearby counter (Android's NearbyContactScanner equivalent)

/// Counts "open to add" beacons (0xCCA6) while the Nearby tab is visible, and surfaces the
/// Bluetooth radio/permission state so the tab can show the right recovery action instead of a
/// silent empty list. Purely passive: scan only, never connects.
@MainActor
final class NearbyDeviceScanner: NSObject, ObservableObject, CBCentralManagerDelegate {

    enum Radio { case unknown, ready, poweredOff, unauthorized, unsupported }

    @Published private(set) var deviceCount = 0
    @Published private(set) var radio: Radio = .unknown

    /// A beacon not re-seen for this long drops out of the count (peers walk away; BLE
    /// advertisements have no goodbye).
    private static let staleAfter: TimeInterval = 15

    private var manager: CBCentralManager?
    private var lastSeen: [UUID: Date] = [:]
    private var pruneTimer: Timer?

    func start() {
        guard manager == nil else { return }
        manager = CBCentralManager(
            delegate: self, queue: .main,
            options: [CBCentralManagerOptionShowPowerAlertKey: false]
        )
        pruneTimer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.prune() }
        }
    }

    func stop() {
        pruneTimer?.invalidate()
        pruneTimer = nil
        manager?.stopScan()
        manager = nil
        lastSeen.removeAll()
        deviceCount = 0
        radio = .unknown
    }

    private func prune() {
        let cutoff = Date().addingTimeInterval(-Self.staleAfter)
        lastSeen = lastSeen.filter { $0.value > cutoff }
        deviceCount = lastSeen.count
    }

    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch central.state {
            case .poweredOn:
                self.radio = .ready
                central.scanForPeripherals(
                    withServices: [NearbyPairingGatt.serviceUUID],
                    // Duplicates keep lastSeen fresh, so leaving peers age out of the count.
                    options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
                )
            case .poweredOff: self.radio = .poweredOff
            case .unauthorized: self.radio = .unauthorized
            case .unsupported: self.radio = .unsupported
            default: self.radio = .unknown
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let id = peripheral.identifier
        Task { @MainActor [weak self] in
            guard let self else { return }
            self.lastSeen[id] = Date()
            self.deviceCount = self.lastSeen.count
        }
    }
}
