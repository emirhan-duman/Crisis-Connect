import CoreBluetooth
import Foundation

/// iOS port of Android's `NearbyAutoLink`: best-effort silent bootstrap of an offline Bluetooth
/// link for a contact that was added over the internet (e.g. a hidden authority bridge), using
/// the peer's stored E.164 number as the SPAKE2 password. The peer still confirms once via their
/// Accept/Decline consent — identical to the manual nearby flow.
///
/// Fail-closed: the attempt only counts as linked when the ORIGINAL contact (same pair-id
/// session) gained a Bluetooth key while keeping the server-verified identity we already
/// trusted. An impostor who knows the number lands on a different session and never touches the
/// bridge contact.
final class NearbyAutoLink: NSObject, CBCentralManagerDelegate {
    static let shared = NearbyAutoLink()

    private let queue = DispatchQueue(label: "nearby.autolink")
    private var manager: CBCentralManager?
    private var running = false
    private var windowExpired = false
    private var tried = Set<UUID>()
    private var pending: [CBPeripheral] = []
    private var retained: [CBPeripheral] = []
    private var activePair: NearbyPairingCentral?
    private var phone = ""
    private var expectedUid = ""
    private var expectedKey = ""
    private var sessionCode = ""

    /// How long to keep looking for candidates. A matching peer, once found, can still hold the
    /// handshake open longer while they tap Accept (NearbyPairingCentral's own timeout).
    private static let discoverWindow: TimeInterval = 8

    /// True when [contact] is an internet contact that could still gain an automatic BT link.
    static func isEligible(_ contact: ContactRecord?) -> Bool {
        guard let contact else { return false }
        let phone = contact.peerPhone?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return contact.supportsInternet && !phone.isEmpty && contact.aesKeyBase64.isEmpty
    }

    /// Fire-and-forget: scans briefly for pairing beacons (0xCCA6) and runs the number-keyed
    /// SPAKE2 handshake against each. Only one attempt runs at a time; repeats are ignored.
    func tryEstablish(contact: ContactRecord) {
        guard Self.isEligible(contact) else { return }
        let phone = contact.peerPhone?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let uid = contact.peerUid ?? ""
        let key = contact.peerPublicKey ?? ""
        let session = contact.sessionCode
        queue.async { [self] in
            guard !running else { return }
            running = true
            windowExpired = false
            tried.removeAll()
            pending.removeAll()
            retained.removeAll()
            self.phone = phone
            expectedUid = uid
            expectedKey = key
            sessionCode = session
            manager = CBCentralManager(delegate: self, queue: queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
            queue.asyncAfter(deadline: .now() + Self.discoverWindow) { [weak self] in
                self?.onWindowExpired()
            }
        }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard running, central.state == .poweredOn else {
            if running, central.state != .poweredOn, central.state != .unknown, central.state != .resetting {
                finish()
            }
            return
        }
        central.scanForPeripherals(withServices: [NearbyPairingGatt.serviceUUID], options: nil)
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

    // MARK: - Sequential pairing

    private func startNextPair(_ central: CBCentralManager) {
        guard running, activePair == nil else { return }
        guard !pending.isEmpty else {
            if windowExpired { finish() }
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
                if case .paired = result, self.linkedToExpectedIdentity() {
                    NSLog("NearbyAutoLink: linked bridge session")
                    self.finish()
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

    /// The link only counts when the original session now holds a key AND still belongs to the
    /// same server-verified peer (uid + identity key) we started with.
    private func linkedToExpectedIdentity() -> Bool {
        guard !expectedUid.isEmpty, !expectedKey.isEmpty else { return false }
        let session = sessionCode
        let stored = ContactStore.shared.contacts.first {
            $0.sessionCode.caseInsensitiveCompare(session) == .orderedSame
        }
        guard let stored, !stored.aesKeyBase64.isEmpty else { return false }
        let storedUid = stored.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let storedKey = stored.peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return storedUid == expectedUid && storedKey == expectedKey
    }

    private func onWindowExpired() {
        windowExpired = true
        // An in-flight handshake keeps its full consent timeout; otherwise wrap up now.
        if activePair == nil { finish() }
    }

    private func finish() {
        running = false
        activePair = nil
        pending.removeAll()
        retained.removeAll()
        if let manager, manager.state == .poweredOn {
            manager.stopScan()
        }
        manager?.delegate = self
        manager = nil
    }
}
