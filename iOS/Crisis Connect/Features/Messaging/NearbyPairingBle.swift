//
//  NearbyPairingBle.swift
//  Crisis Connect
//
//  CoreBluetooth transport for the SPAKE2 nearby pairing (iOS port of the Android
//  NearbyContactAdvertiser + NearbyPairingServer + NearbyPairingClient). The peripheral hosts a
//  generic, number-free "open to add" service (0xCCA6) and answers as the SPAKE2 responder; the
//  central is the SPAKE2 initiator for a targeted search. No phone number is ever advertised.
//
//  ⚠️ UNVERIFIED: CoreBluetooth is a hardware state machine that cannot be validated without a
//  device — this is a faithful port that WILL need on-device iteration (MTU/write/read ordering,
//  background modes, permissions). The SPAKE2 crypto + session it drives are unit-verified.
//

import Combine
import CoreBluetooth
import Foundation

enum NearbyPairingGatt {
    static let serviceUUID = CBUUID(string: "0000CCA6-0000-1000-8000-00805F9B34FB")
    static let requestUUID = CBUUID(string: "0000CCA7-0000-1000-8000-00805F9B34FB")  // central writes
    static let responseUUID = CBUUID(string: "0000CCA8-0000-1000-8000-00805F9B34FB") // central reads
}

// MARK: - Discovery opt-in (mirror of Android's NearbyDiscoveryPreferences)

/// Opt-in switch for nearby discoverability. Off by default — hosting the "open to add" beacon only
/// happens when the user turns it on. Exception, same as Android: once the user has a verified phone
/// number we flip it on as a sensible default, because SPAKE2 pairing only works for people who
/// already have your number anyway. A deliberate toggle choice always wins over that default.
enum NearbyDiscoveryPreferences {
    private static let enabledKey = "nearby.discovery.enabled"
    private static let userSetKey = "nearby.discovery.userSet"

    static var isEnabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    static func setEnabled(_ enabled: Bool) {
        UserDefaults.standard.set(enabled, forKey: enabledKey)
        UserDefaults.standard.set(true, forKey: userSetKey)
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }

    /// Default-on once a phone number exists — never overriding an explicit choice.
    static func enableByDefaultIfUnset() {
        guard !UserDefaults.standard.bool(forKey: userSetKey),
              !UserDefaults.standard.bool(forKey: enabledKey),
              NearbyPairingSupport.ownPhoneE164() != nil else { return }
        UserDefaults.standard.set(true, forKey: enabledKey)
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }
}

// MARK: - Responder (peripheral): generic beacon + SPAKE2 responder + consent

final class NearbyPairingPeripheral: NSObject, ObservableObject, BlePeripheralHostProfile {

    struct PendingApproval: Identifiable, Equatable {
        let id: UUID          // the central's identifier
        let displayName: String
    }

    /// Set when a connector completed the handshake and is awaiting the user's Accept/Decline.
    @Published var pendingApproval: PendingApproval?

    private final class Session {
        let responder: NearbySpakePairing.Responder
        var peer: NearbyIdentity?
        init(_ responder: NearbySpakePairing.Responder) { self.responder = responder }
    }

    /// This class was fully implemented and then never instantiated: no call site ever ran start(),
    /// so iOS never advertised the pairing service at all. An Android (or iOS) peer looking for us to
    /// establish the offline Bluetooth link found nothing — a directory-added iOS contact could never
    /// gain a Bluetooth fallback, which in a blackout is the only transport left. Hosting now rides
    /// the app's shared peripheral coordinator (a second standalone CBPeripheralManager would fight
    /// the SOS/contact hosts for the advertisement) and is registered from the app root.
    static let shared = NearbyPairingPeripheral()

    private var sessions: [UUID: Session] = [:]     // central id -> handshake state
    private var responses: [UUID: Data] = [:]       // central id -> current READ payload
    private let queue = DispatchQueue(label: "nearby.pairing.peripheral")

    private override init() {
        super.init()
        NearbyDiscoveryPreferences.enableByDefaultIfUnset()
        BlePeripheralHostCoordinator.shared.register(self)
    }

    // MARK: BlePeripheralHostProfile

    var hostProfileIdentifier: String { "nearby-pairing" }
    var hostPrimaryServiceUUID: CBUUID { NearbyPairingGatt.serviceUUID }
    /// Hosting requires the opt-in AND an own phone number — without the number the SPAKE2 responder
    /// rejects every handshake anyway, so advertising would only waste the rotation slot.
    var isPeripheralHostActive: Bool {
        NearbyDiscoveryPreferences.isEnabled && NearbyPairingSupport.ownPhoneE164() != nil
    }

    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? {
        guard isPeripheralHostActive else { return nil }
        // Low priority: a passive "open to add" beacon that rotates with the other passive profiles.
        // The searching central scans for ~12s, several times the 3s rotation interval, so rotation
        // never hides us from it. Number-free by design.
        return BleHostedAdvertisementDescriptor(
            serviceUUIDs: [NearbyPairingGatt.serviceUUID],
            localName: nil,
            priority: 10
        )
    }

    func buildHostedService() -> CBMutableService? {
        guard isPeripheralHostActive else { return nil }
        let service = CBMutableService(type: NearbyPairingGatt.serviceUUID, primary: true)
        let request = CBMutableCharacteristic(
            type: NearbyPairingGatt.requestUUID, properties: [.write], value: nil, permissions: [.writeable]
        )
        let response = CBMutableCharacteristic(
            type: NearbyPairingGatt.responseUUID, properties: [.read], value: nil, permissions: [.readable]
        )
        service.characteristics = [request, response]
        return service
    }

    func peripheralHostWillReset() {
        queue.async {
            self.sessions.removeAll()
            self.responses.removeAll()
        }
        DispatchQueue.main.async { self.pendingApproval = nil }
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        // Lifecycle (service add + advertising) belongs to the shared coordinator now.
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == NearbyPairingGatt.requestUUID, let value = request.value else {
                peripheral.respond(to: request, withResult: .writeNotPermitted)
                continue
            }
            let centralId = request.central.identifier
            queue.sync { self.handleWrite(centralId: centralId, value: value) }
            peripheral.respond(to: request, withResult: .success)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == NearbyPairingGatt.responseUUID,
              let payload = queue.sync(execute: { responses[request.central.identifier] }) else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        guard request.offset <= payload.count else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = payload.subdata(in: request.offset..<payload.count)
        peripheral.respond(to: request, withResult: .success)
    }

    private func handleWrite(centralId: UUID, value: Data) {
        if let existing = sessions[centralId] {
            // Second write = SPAKE2 message 3.
            guard existing.peer == nil else { return }
            do {
                let peer = try existing.responder.onMessage3(value)
                existing.peer = peer
                responses[centralId] = try existing.responder.responseMessage(status: NearbySpakePairing.statusPending)
                DispatchQueue.main.async {
                    self.pendingApproval = PendingApproval(id: centralId, displayName: peer.displayName)
                }
            } catch {
                responses[centralId] = (try? existing.responder.responseMessage(status: NearbySpakePairing.statusReject))
                    ?? NearbySpakePairing.rejectMessage()
            }
            return
        }
        // First write = SPAKE2 message 1.
        guard let ownPhone = NearbyPairingSupport.ownPhoneE164() else {
            responses[centralId] = NearbySpakePairing.rejectMessage()
            return
        }
        let responder = NearbySpakePairing.Responder(
            w: NearbySpakePairing.deriveW(ownPhone), me: NearbyPairingSupport.localIdentity()
        )
        do {
            let msg2 = try responder.onMessage1(value)
            sessions[centralId] = Session(responder)
            responses[centralId] = msg2
        } catch {
            responses[centralId] = NearbySpakePairing.rejectMessage()
        }
    }

    /// User approved: reveal our identity, save the contact.
    func accept(_ id: UUID) {
        queue.async {
            guard let session = self.sessions[id], let peer = session.peer else { return }
            self.responses[id] = (try? session.responder.responseMessage(status: NearbySpakePairing.statusOk))
            if let key = try? session.responder.contactKey() {
                NearbyPairingSupport.savePairedContact(peer: peer, contactKey: key, bluetoothAddress: id.uuidString)
            }
            DispatchQueue.main.async { if self.pendingApproval?.id == id { self.pendingApproval = nil } }
        }
    }

    /// User declined.
    func decline(_ id: UUID) {
        queue.async {
            let session = self.sessions[id]
            self.responses[id] = (try? session?.responder.responseMessage(status: NearbySpakePairing.statusReject))
                ?? NearbySpakePairing.rejectMessage()
            DispatchQueue.main.async { if self.pendingApproval?.id == id { self.pendingApproval = nil } }
        }
    }
}

// MARK: - Initiator (central): targeted SPAKE2 search

final class NearbyPairingCentral: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate {

    enum Result {
        case paired(sessionCode: String, displayName: String)
        case notMatched   // wrong person / declined — nothing revealed
        case failed       // transport error
    }

    private enum Phase { case awaitMsg2, awaitMsg4 }

    private var manager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var initiator: NearbySpakePairing.Initiator?
    private var phase: Phase = .awaitMsg2
    private var requestChar: CBCharacteristic?
    private var responseChar: CBCharacteristic?
    private var completion: ((Result) -> Void)?
    private let queue = DispatchQueue(label: "nearby.pairing.central")
    private var timeoutItem: DispatchWorkItem?

    private static let timeout: TimeInterval = 65
    private static let pollInterval: TimeInterval = 2.5

    /// Connect to `target` (a peripheral discovered advertising 0xCCA6) and run SPAKE2 with the number
    /// the user is looking for. Completes with the outcome.
    func pair(central: CBCentralManager, target: CBPeripheral, candidatePhone: String, completion: @escaping (Result) -> Void) {
        self.manager = central
        self.peripheral = target
        self.completion = completion
        self.phase = .awaitMsg2
        self.initiator = NearbySpakePairing.Initiator(
            w: NearbySpakePairing.deriveW(candidatePhone), me: NearbyPairingSupport.localIdentity()
        )
        target.delegate = self
        central.connect(target, options: nil)
        let item = DispatchWorkItem { [weak self] in self?.finish(.failed) }
        timeoutItem = item
        queue.asyncAfter(deadline: .now() + Self.timeout, execute: item)
    }

    private func finish(_ result: Result) {
        timeoutItem?.cancel(); timeoutItem = nil
        if let p = peripheral { manager?.cancelPeripheralConnection(p) }
        let done = completion
        completion = nil
        done?(result)
    }

    func centralManagerDidUpdateState(_ central: CBCentralManager) {}

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([NearbyPairingGatt.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish(.failed)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        if completion != nil { finish(.failed) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == NearbyPairingGatt.serviceUUID }) else {
            finish(.failed); return
        }
        peripheral.discoverCharacteristics([NearbyPairingGatt.requestUUID, NearbyPairingGatt.responseUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        requestChar = service.characteristics?.first { $0.uuid == NearbyPairingGatt.requestUUID }
        responseChar = service.characteristics?.first { $0.uuid == NearbyPairingGatt.responseUUID }
        guard let req = requestChar, let initiator = initiator else { finish(.failed); return }
        peripheral.writeValue(initiator.message1(), for: req, type: .withResponse)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == NearbyPairingGatt.requestUUID, error == nil, let resp = responseChar else {
            finish(.failed); return
        }
        peripheral.readValue(for: resp)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard characteristic.uuid == NearbyPairingGatt.responseUUID, error == nil,
              let value = characteristic.value, let initiator = initiator, let req = requestChar else {
            finish(.failed); return
        }
        switch phase {
        case .awaitMsg2:
            guard let msg3 = try? initiator.onMessage2(value) else {
                finish(.notMatched); return // responder confirmation failed → not who we're looking for
            }
            phase = .awaitMsg4
            peripheral.writeValue(msg3, for: req, type: .withResponse)
        case .awaitMsg4:
            switch NearbySpakePairing.responseStatus(value) {
            case NearbySpakePairing.statusPending:
                queue.asyncAfter(deadline: .now() + Self.pollInterval) { [weak self, weak peripheral, weak characteristic] in
                    guard let peripheral, let characteristic else { return }
                    if self?.completion != nil { peripheral.readValue(for: characteristic) }
                }
            case NearbySpakePairing.statusOk:
                guard let peer = try? initiator.onMessage4(value), let key = try? initiator.contactKey() else {
                    finish(.failed); return
                }
                let sessionCode = NearbyPairingSupport.savePairedContact(
                    peer: peer, contactKey: key, bluetoothAddress: peripheral.identifier.uuidString
                )
                finish(.paired(sessionCode: sessionCode, displayName: peer.displayName))
            default:
                finish(.notMatched) // REJECT
            }
        }
    }
}
