//
//  SOSBroadcastManager.swift
//  Crisis Connect
//
//  Created by Assistant on 16.12.2025
//

import Foundation
import CoreBluetooth
import CoreLocation
import CryptoKit
import Combine
import UIKit

struct BleHostedAdvertisementDescriptor {
    let serviceUUIDs: [CBUUID]
    let localName: String?
    let priority: Int
}

protocol BlePeripheralHostProfile: AnyObject, CBPeripheralManagerDelegate {
    var hostProfileIdentifier: String { get }
    var hostPrimaryServiceUUID: CBUUID { get }
    var isPeripheralHostActive: Bool { get }
    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? { get }

    func buildHostedService() -> CBMutableService?
    func peripheralHostWillReset()
}

final class BlePeripheralHostCoordinator: NSObject, CBPeripheralManagerDelegate {
    static let shared = BlePeripheralHostCoordinator()

    let queue = DispatchQueue(label: "ble.peripheral.host.queue")
    private let advertisementRotationInterval: TimeInterval = 3
    // Descriptors at or above this priority belong to time-critical flows (active
    // QR contact share = 100, SOS broadcast = 1000). They are advertised alone and
    // continuously instead of rotating with passive profiles like the public mesh,
    // because a scanner keying off one advertisement misses rotated-out packets.
    private let pinnedAdvertisementPriority = 100
    private let maxForegroundAdvertisementPayloadBytes = 31
    private let recoveryDelay: TimeInterval = 2

    private let queueKey = DispatchSpecificKey<Void>()
    private var peripheralManager: CBPeripheralManager?
    private var profiles: [String: BlePeripheralHostProfile] = [:]
    private var pendingServiceUUIDs: Set<CBUUID> = []
    private var serviceRegistrationFailed = false
    private var advertisedDescriptorIndex = 0
    private var advertisementRotationWork: DispatchWorkItem?
    private var recoveryWork: DispatchWorkItem?

    private override init() {
        super.init()
        queue.setSpecific(key: queueKey, value: ())
    }

    var currentPeripheralManager: CBPeripheralManager? {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            return peripheralManager
        }
        return queue.sync { peripheralManager }
    }

    func register(_ profile: BlePeripheralHostProfile) {
        performOnQueue {
            self.profiles[profile.hostProfileIdentifier] = profile
            guard !self.activeProfilesLocked().isEmpty else { return }
            self.ensurePeripheralManagerLocked()
            self.refreshHostingLocked()
        }
    }

    func setNeedsRefresh() {
        performOnQueue {
            let activeProfiles = self.activeProfilesLocked()
            guard !activeProfiles.isEmpty else {
                self.teardownPeripheralManagerLocked()
                return
            }
            self.ensurePeripheralManagerLocked()
            NSLog(
                "BLE host refresh requested activeProfiles=%@ peripheralState=%@",
                activeProfiles.map(\.hostProfileIdentifier).joined(separator: ","),
                self.debugDescription(for: self.peripheralManager?.state ?? .unknown)
            )
            self.refreshHostingLocked()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        // The system relaunched us with previously published services. No manual reconstruction is
        // needed: the profiles are re-registered during app init and the poweredOn callback that
        // follows this runs refreshHostingLocked(), which rebuilds services + advertising from
        // their live state. Implementing the method is what opts us into receiving the relaunch.
        NSLog("[SOSBroadcast] CBPeripheralManager state restored (%d services)",
              (dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService])?.count ?? 0)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        profiles.values.forEach { profile in
            profile.peripheralManagerDidUpdateState(peripheral)
        }
        // Log every state transition so we can diagnose "BLE silently stopped working"
        // reports — without this, .poweredOff / .unauthorized / .unsupported all silently
        // tear the hosted configuration down with no breadcrumb in the device log.
        switch peripheral.state {
        case .poweredOn:
            NSLog("[SOSBroadcast] CBPeripheralManager state: poweredOn — refreshing hosted services")
            recoveryWork?.cancel()
            recoveryWork = nil
            refreshHostingLocked()
        case .resetting:
            NSLog("[SOSBroadcast] CBPeripheralManager state: resetting — scheduling recovery")
            scheduleRecoveryLocked()
        case .poweredOff:
            NSLog("[SOSBroadcast] CBPeripheralManager state: poweredOff — Bluetooth is OFF, clearing hosted configuration")
            recoveryWork?.cancel()
            recoveryWork = nil
            clearHostedConfigurationLocked()
        case .unauthorized:
            NSLog("[SOSBroadcast] CBPeripheralManager state: unauthorized — user denied Bluetooth permission, clearing hosted configuration")
            recoveryWork?.cancel()
            recoveryWork = nil
            clearHostedConfigurationLocked()
        case .unsupported:
            NSLog("[SOSBroadcast] CBPeripheralManager state: unsupported — device does not support BLE peripheral role")
            recoveryWork?.cancel()
            recoveryWork = nil
            clearHostedConfigurationLocked()
        case .unknown:
            NSLog("[SOSBroadcast] CBPeripheralManager state: unknown — clearing hosted configuration until state resolves")
            recoveryWork?.cancel()
            recoveryWork = nil
            clearHostedConfigurationLocked()
        @unknown default:
            NSLog("[SOSBroadcast] CBPeripheralManager state: unrecognized raw=%d — clearing hosted configuration", peripheral.state.rawValue)
            recoveryWork?.cancel()
            recoveryWork = nil
            clearHostedConfigurationLocked()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let profile = profile(for: service.uuid) {
            profile.peripheralManager?(peripheral, didAdd: service, error: error)
        }
        if error != nil {
            serviceRegistrationFailed = true
        }
        pendingServiceUUIDs.remove(service.uuid)
        guard pendingServiceUUIDs.isEmpty else { return }
        if serviceRegistrationFailed {
            clearHostedConfigurationLocked()
            return
        }
        startAdvertisingLocked(on: peripheral)
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            NSLog("BLE host advertising failed: %@", error.localizedDescription)
        }
        activeProfilesLocked().forEach { profile in
            profile.peripheralManagerDidStartAdvertising?(peripheral, error: error)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard let profile = profile(for: request.characteristic.service?.uuid) else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
            return
        }
        profile.peripheralManager?(peripheral, didReceiveRead: request)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        let groupedRequests = Dictionary(grouping: requests) { request in
            request.characteristic.service?.uuid
        }
        groupedRequests.forEach { serviceUuid, grouped in
            guard let profile = profile(for: serviceUuid) else {
                grouped.forEach { peripheral.respond(to: $0, withResult: .attributeNotFound) }
                return
            }
            profile.peripheralManager?(peripheral, didReceiveWrite: grouped)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        profile(for: characteristic.service?.uuid)?
            .peripheralManager?(peripheral, central: central, didSubscribeTo: characteristic)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        profile(for: characteristic.service?.uuid)?
            .peripheralManager?(peripheral, central: central, didUnsubscribeFrom: characteristic)
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        activeProfilesLocked().forEach { profile in
            profile.peripheralManagerIsReady?(toUpdateSubscribers: peripheral)
        }
    }

    private func performOnQueue(_ block: @escaping () -> Void) {
        if DispatchQueue.getSpecific(key: queueKey) != nil {
            block()
        } else {
            queue.async(execute: block)
        }
    }

    private func ensurePeripheralManagerLocked() {
        guard PlatformRuntime.supportsBlePeripheralHosting else {
            peripheralManager = nil
            return
        }
        if peripheralManager == nil {
            peripheralManager = CBPeripheralManager(
                delegate: self,
                queue: queue,
                options: [
                    // State restoration: when the system jetsams the app while it is hosting (an SOS
                    // beacon, the chat characteristics), iOS can relaunch it in the background and
                    // hand the published services back — WITHOUT this key the beacon simply went
                    // silent on memory pressure until the user manually reopened the app, which a
                    // trapped victim cannot do. (Force-quit is different and unfixable by design:
                    // iOS never restores an app the user explicitly killed.) The rescue central has
                    // used the same pattern all along.
                    CBPeripheralManagerOptionRestoreIdentifierKey:
                        "com.auralis.crisisconnect.peripheral-host",
                    CBPeripheralManagerOptionShowPowerAlertKey: false
                ]
            )
        }
    }

    private func activeProfilesLocked() -> [BlePeripheralHostProfile] {
        profiles.values
            .filter { $0.isPeripheralHostActive }
            .sorted { $0.hostProfileIdentifier < $1.hostProfileIdentifier }
    }

    private func profile(for serviceUuid: CBUUID?) -> BlePeripheralHostProfile? {
        guard let serviceUuid else { return nil }
        return profiles.values.first(where: { $0.hostPrimaryServiceUUID == serviceUuid })
    }

    private func refreshHostingLocked() {
        guard PlatformRuntime.supportsBlePeripheralHosting else {
            teardownPeripheralManagerLocked()
            return
        }
        let activeProfiles = activeProfilesLocked()
        NSLog(
            "BLE host refreshing activeProfiles=%@",
            activeProfiles.map(\.hostProfileIdentifier).joined(separator: ",")
        )
        guard !activeProfiles.isEmpty else {
            teardownPeripheralManagerLocked()
            return
        }

        ensurePeripheralManagerLocked()
        guard let peripheralManager else { return }
        guard peripheralManager.state == .poweredOn else {
            NSLog("BLE host refresh skipped peripheralState=%@", debugDescription(for: peripheralManager.state))
            return
        }

        clearHostedConfigurationLocked()
        pendingServiceUUIDs.removeAll()
        serviceRegistrationFailed = false
        advertisedDescriptorIndex = 0

        activeProfiles.forEach { profile in
            guard let service = profile.buildHostedService() else { return }
            pendingServiceUUIDs.insert(service.uuid)
            peripheralManager.add(service)
        }

        if pendingServiceUUIDs.isEmpty {
            startAdvertisingLocked(on: peripheralManager)
        }
    }

    private func clearHostedConfigurationLocked() {
        pendingServiceUUIDs.removeAll()
        serviceRegistrationFailed = false
        advertisementRotationWork?.cancel()
        advertisementRotationWork = nil
        advertisedDescriptorIndex = 0
        profiles.values.forEach { $0.peripheralHostWillReset() }
        guard let peripheralManager else { return }
        if peripheralManager.isAdvertising {
            peripheralManager.stopAdvertising()
        }
        peripheralManager.removeAllServices()
    }

    private func teardownPeripheralManagerLocked() {
        recoveryWork?.cancel()
        recoveryWork = nil
        clearHostedConfigurationLocked()
        peripheralManager?.delegate = nil
        peripheralManager = nil
    }

    private func scheduleRecoveryLocked() {
        guard recoveryWork == nil else { return }
        NSLog("BLE host peripheral entered resetting; scheduling recovery")
        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.performOnQueue {
                guard let peripheralManager = self.peripheralManager,
                      peripheralManager.state == .resetting else {
                    self.recoveryWork = nil
                    return
                }
                NSLog("BLE host peripheral still resetting; recreating peripheral manager")
                self.clearHostedConfigurationLocked()
                peripheralManager.delegate = nil
                self.peripheralManager = nil
                self.recoveryWork = nil
                self.ensurePeripheralManagerLocked()
            }
        }
        recoveryWork = work
        queue.asyncAfter(deadline: .now() + recoveryDelay, execute: work)
    }

    private func startAdvertisingLocked(on peripheral: CBPeripheralManager) {
        advertisementRotationWork?.cancel()
        advertisementRotationWork = nil

        let sortedDescriptors = activeProfilesLocked()
            .compactMap(\.hostedAdvertisementDescriptor)
            .sorted { lhs, rhs in lhs.priority > rhs.priority }
        let descriptors: [BleHostedAdvertisementDescriptor]
        if let pinned = sortedDescriptors.first, pinned.priority >= pinnedAdvertisementPriority {
            descriptors = [pinned]
        } else {
            descriptors = combinedAdvertisementDescriptorsIfPossible(from: sortedDescriptors)
        }
        guard !descriptors.isEmpty else { return }

        let descriptorIndex = min(advertisedDescriptorIndex, descriptors.count - 1)
        let descriptor = descriptors[descriptorIndex]
        guard let primaryServiceUUIDs = Optional(descriptor.serviceUUIDs),
              !primaryServiceUUIDs.isEmpty else { return }

        var advertisement: [String: Any] = [
            CBAdvertisementDataServiceUUIDsKey: advertisingServiceUUIDs(from: primaryServiceUUIDs)
        ]
        if let localName = descriptor.localName {
            advertisement[CBAdvertisementDataLocalNameKey] = localName
        }
        NSLog(
            "BLE host advertising services=%@ localName=%@",
            primaryServiceUUIDs.map(\.uuidString).joined(separator: ","),
            advertisement[CBAdvertisementDataLocalNameKey] as? String ?? "-"
        )
        if peripheral.isAdvertising {
            peripheral.stopAdvertising()
        }
        peripheral.startAdvertising(advertisement)

        guard descriptors.count > 1 else { return }

        let work = DispatchWorkItem { [weak self, weak peripheral] in
            guard let self, let peripheral else { return }
            self.performOnQueue {
                guard self.peripheralManager === peripheral else { return }
                self.advertisedDescriptorIndex = (descriptorIndex + 1) % descriptors.count
                self.startAdvertisingLocked(on: peripheral)
            }
        }
        advertisementRotationWork = work
        queue.asyncAfter(deadline: .now() + advertisementRotationInterval, execute: work)
    }

    private func advertisingServiceUUIDs(from uuids: [CBUUID]) -> NSArray {
        return NSArray(array: uuids)
    }

    private func combinedAdvertisementDescriptorsIfPossible(
        from descriptors: [BleHostedAdvertisementDescriptor]
    ) -> [BleHostedAdvertisementDescriptor] {
        guard descriptors.count > 1,
              let mergedDescriptor = mergedAdvertisementDescriptor(from: descriptors) else {
            return descriptors
        }
        return [mergedDescriptor]
    }

    private func mergedAdvertisementDescriptor(
        from descriptors: [BleHostedAdvertisementDescriptor]
    ) -> BleHostedAdvertisementDescriptor? {
        let localNames = descriptors.compactMap { descriptor in
            descriptor.localName?.trimmingCharacters(in: .whitespacesAndNewlines)
        }.filter { !$0.isEmpty }
        let distinctLocalNames = Array(Set(localNames))
        guard distinctLocalNames.count <= 1 else { return nil }

        var seenServiceUUIDs = Set<String>()
        let mergedServiceUUIDs = descriptors
            .flatMap(\.serviceUUIDs)
            .filter { uuid in
                seenServiceUUIDs.insert(uuid.uuidString).inserted
            }

        let mergedDescriptor = BleHostedAdvertisementDescriptor(
            serviceUUIDs: mergedServiceUUIDs,
            localName: distinctLocalNames.first,
            priority: descriptors.map(\.priority).max() ?? 0
        )

        guard advertisementPayloadSize(for: mergedDescriptor) <= maxForegroundAdvertisementPayloadBytes else {
            return nil
        }

        return mergedDescriptor
    }

    private func advertisementPayloadSize(for descriptor: BleHostedAdvertisementDescriptor) -> Int {
        var payloadSize = 3 // flags
        let uuidsByByteCount = Dictionary(grouping: descriptor.serviceUUIDs) { $0.data.count }
        payloadSize += uuidsByByteCount.reduce(into: 0) { partialResult, item in
            partialResult += 2 + (item.key * item.value.count)
        }
        if let localName = descriptor.localName, !localName.isEmpty {
            payloadSize += 2 + localName.utf8.count
        }
        return payloadSize
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
}

final class SOSBroadcastManager: NSObject, ObservableObject, CBPeripheralManagerDelegate, BlePeripheralHostProfile {
    static let shared = SOSBroadcastManager()

    @Published private(set) var elapsedSeconds: Int = 0
    @Published private(set) var isBroadcasting: Bool = false
    /// Rescuers that completed the authenticated handshake against this beacon. The bookkeeping
    /// (sessionKeys) always existed; nothing ever published it, so the SOS screen showed a static
    /// "ACTIVE" while a victim had no way to know anyone had actually found them.
    @Published private(set) var connectedRescuerCount: Int = 0
    /// The shared BLE peripheral session is up. This does NOT mean the user is in danger: the same
    /// hosted service carries the legacy BLE chat characteristics, so opening a chat brings it up too.
    @Published private(set) var isSessionActive: Bool = false

    /// The user DECLARED an emergency by pressing SOS. Everything that tells the outside world a human
    /// is in danger — the agency dashboard report, the automatic contact alerts, the location
    /// broadcast, and being advertised where rescuers can see it — hangs off THIS, never off
    /// `isSessionActive`. Conflating the two is what made merely opening a chat transmit a real SOS.
    @Published private(set) var isEmergencyDeclared: Bool = false
    @Published private(set) var bluetoothState: CBManagerState = .unknown
    @Published private(set) var lastAuthenticatedFieldTeamSessionId: UUID?

    private var startDate: Date?
    private var timer: Timer?

    private var service: CBMutableService?
    private var idCharacteristic: CBMutableCharacteristic?
    private var statusCharacteristic: CBMutableCharacteristic?
    private var authChallengeCharacteristic: CBMutableCharacteristic?
    private var authResponseCharacteristic: CBMutableCharacteristic?
    private var secureInCharacteristic: CBMutableCharacteristic?
    private var secureAckCharacteristic: CBMutableCharacteristic?
    private var secureChatInCharacteristic: CBMutableCharacteristic?
    private var secureChatOutCharacteristic: CBMutableCharacteristic?
    private var callIoOutCharacteristic: CBMutableCharacteristic?

    private var authResponseValues: [UUID: Data] = [:]
    private var secureAckValues: [UUID: Data] = [:]
    private var secureChatOutValues: [UUID: Data] = [:]
    private var pendingChatOutValues: [UUID: Data] = [:]
    private var queuedSecureChatPayloads: [UUID: [Data]] = [:]
    private var pendingAckValues: [UUID: Data] = [:]
    private var pendingNotifications: [PendingNotificationKey: PendingNotification] = [:]
    private var sessionKeys: [UUID: SymmetricKey] = [:]

    private func publishRescuerCount() {
        let count = sessionKeys.count
        DispatchQueue.main.async { [weak self] in
            guard let self, self.connectedRescuerCount != count else { return }
            self.connectedRescuerCount = count
        }
    }
    private var authSessionNonces: [UUID: String] = [:]
    private var pskCentrals: Set<UUID> = []
    private var secureInReceivers: [UUID: SOSChunkReceiver] = [:]
    private var secureChatReceivers: [UUID: SOSChunkReceiver] = [:]
    private var verifiedCentrals: Set<UUID> = []
    private var subscribedChatCentrals: [UUID: CBCentral] = [:]
    private var subscribedAckCentrals: [UUID: CBCentral] = [:]
    private var knownCentrals: [UUID: CBCentral] = [:]
    private var centralToSessionId: [UUID: UUID] = [:]
    private var sessionToCentralId: [UUID: UUID] = [:]
    private var incomingImageTransfers: [String: BleImagePayload.IncomingTransfer] = [:]
    // Victim-side inbound voice/file reassembly — mirrors the rescuer's RescueConnection maps so
    // both directions carry the full media set (the victim used to silently drop these).
    private var incomingVoiceTransfers: [String: BleVoicePayload.IncomingTransfer] = [:]
    private var incomingFileTransfers: [String: BleFilePayload.IncomingTransfer] = [:]

    private let queue = BlePeripheralHostCoordinator.shared.queue

    private let serviceUUID = CBUUID(string: "0000CC00-0000-1000-8000-00805F9B34FB")
    private let idUUID = CBUUID(string: "0000CC01-0000-1000-8000-00805F9B34FB")
    private let statusUUID = CBUUID(string: "0000CC02-0000-1000-8000-00805F9B34FB")
    private let authChallengeUUID = CBUUID(string: "0000CC10-0000-1000-8000-00805F9B34FB")
    private let authResponseUUID = CBUUID(string: "0000CC11-0000-1000-8000-00805F9B34FB")
    private let secureInUUID = CBUUID(string: "0000CC20-0000-1000-8000-00805F9B34FB")
    private let secureAckUUID = CBUUID(string: "0000CC21-0000-1000-8000-00805F9B34FB")
    private let secureChatInUUID = CBUUID(string: "0000CC30-0000-1000-8000-00805F9B34FB")
    private let secureChatOutUUID = CBUUID(string: "0000CC31-0000-1000-8000-00805F9B34FB")
    private let callIoInUUID = CBUUID(string: "0000CC40-0000-1000-8000-00805F9B34FB")
    private let callIoOutUUID = CBUUID(string: "0000CC41-0000-1000-8000-00805F9B34FB")
    private let statusValue = Data("ACTIVE".utf8)
    private let handshakeAckValue = Data("OK".utf8)
    private let messageAckValue = Data("DELIVERED".utf8)
    private let readAckValue = Data("READ".utf8)
    private let peerInfoAckValue = Data("PEER_INFO_ACK".utf8)
    private let pskChallengeValue = Data("psk:v1".utf8)
    private let pskResponseValue = Data("psk:ok".utf8)
    private let maxRoleProofPacketBytes = 16_384
    private let maxSecureChatPacketBytes = 32_767
    private let textMessageTtlMillis: Int64 = 86_400_000
    private let outboundRouteBleGattFallback = "ble_gatt_fallback"
    private let roleProofVerifier = RoleProofVerifier(maxExpiredCertificateGraceMillis: 0)
    private let signalLocationCoordinator = SOSSignalLocationCoordinator.shared

    private lazy var broadcastId: String = SecureLocalStore.shared.getOrCreateBroadcastId()

    var hostProfileIdentifier: String { "sos" }
    var hostPrimaryServiceUUID: CBUUID { serviceUUID }
    var isPeripheralHostActive: Bool { isSessionActive && PlatformRuntime.supportsBlePeripheralHosting }
    var hostedAdvertisementDescriptor: BleHostedAdvertisementDescriptor? {
        // Hosting is advertised only when an emergency was declared. CoreBluetooth cannot put service
        // DATA in an advertisement — the trick Android uses to mark intent — so on iOS intent is
        // carried by WHETHER the SOS service is advertised at all. A chat session still hosts the
        // service (buildHostedService below is gated on isPeripheralHostActive, not on this), so the
        // characteristics stay live; it simply is not announced to rescue scanners.
        guard isPeripheralHostActive, isEmergencyDeclared else { return nil }
        return BleHostedAdvertisementDescriptor(
            serviceUUIDs: [serviceUUID],
            localName: nil,
            priority: 1_000
        )
    }

    private var peripheralManager: CBPeripheralManager? {
        BlePeripheralHostCoordinator.shared.currentPeripheralManager
    }

    private var idValue: Data {
        Data(broadcastId.utf8)
    }

    private struct PendingNotificationKey: Hashable {
        let centralId: UUID
        let characteristicId: CBUUID
    }

    private struct PendingNotification {
        var payload: Data
        var offset: Int
    }

    private override init() {
        super.init()
        Task { @MainActor in
            self.signalLocationCoordinator.onBroadcastLocationChange = { [weak self] _ in
                self?.queue.async { [weak self] in
                    self?.sendPeerIdentityUpdateToVerifiedCentrals()
                }
            }
        }
        BlePeripheralHostCoordinator.shared.register(self)
    }

    var elapsedText: String {
        let hours = elapsedSeconds / 3600
        let minutes = (elapsedSeconds % 3600) / 60
        let seconds = elapsedSeconds % 60
        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%02d:%02d", minutes, seconds)
    }

    /// - Parameter declaringEmergency: `true` ONLY from the SOS button. `false` brings the shared BLE
    ///   peripheral up so a legacy chat has a transport and announces nothing to anyone.
    ///
    /// This used to take no parameter, so every caller declared an emergency — including the chat
    /// screen, which starts a session merely to get its transport. That reported a live emergency to
    /// the agency dashboard and auto-messaged the user's contacts with no user action at all. The
    /// emergency block below is unchanged and un-gated internally: a suppressed real SOS is far worse
    /// than an accidental one, so nothing here is made conditional except who may reach it.
    func start(declaringEmergency: Bool) {
        guard timer == nil else {
            // A chat already had the session up and the user has now pressed SOS: escalate in place
            // rather than returning, or the real emergency would be silently swallowed.
            if declaringEmergency, !isEmergencyDeclared {
                declareEmergency()
            }
            return
        }
        isSessionActive = true
        lastAuthenticatedFieldTeamSessionId = nil
        if declaringEmergency {
            declareEmergency()
        }
        startDate = Date()
        updateElapsed()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            self?.updateElapsed()
        }
        timer?.tolerance = 0.2
        guard PlatformRuntime.supportsBlePeripheralHosting else {
            bluetoothState = .unsupported
            isBroadcasting = false
            return
        }
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }

    /// Idempotent: the SOS button can be pressed while a chat session is already hosting.
    private func declareEmergency() {
        guard !isEmergencyDeclared else { return }
        isEmergencyDeclared = true
        AppAnalytics.sosActivated()
        // Lock-screen + Dynamic Island live status (best-effort, never blocks the broadcast).
        SosLiveActivityController.sosDeclared(startedAt: Date())
        Task { @MainActor in
            self.signalLocationCoordinator.beginBroadcasting()
        }
        // Internet uplinks (best-effort; never block the BLE broadcast): the agency dashboard
        // report and the automatic emergency-contact alerts.
        SosCloudReporter.shared.onSosStarted()
        SosContactNotifier.shared.onSosStarted()
        // The session was advertised as chat-only until now; re-announce it so rescuers can see it.
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }

    func stop() {
        isSessionActive = false
        if isEmergencyDeclared {
            SosLiveActivityController.sosEnded()
        }
        isEmergencyDeclared = false
        Task { @MainActor in
            self.signalLocationCoordinator.endBroadcasting()
        }
        SosCloudReporter.shared.onSosStopped()
        SosContactNotifier.shared.onSosStopped()
        timer?.invalidate()
        timer = nil
        startDate = nil
        elapsedSeconds = 0
        lastAuthenticatedFieldTeamSessionId = nil
        resetHostedTransportState()
        isBroadcasting = false
        bluetoothState = .unknown
        BlePeripheralHostCoordinator.shared.setNeedsRefresh()
    }

    private func updateElapsed() {
        guard let startDate else { return }
        elapsedSeconds = max(0, Int(Date().timeIntervalSince(startDate)))
    }

    func peripheralHostWillReset() {
        resetHostedTransportState()
    }

    func buildHostedService() -> CBMutableService? {
        guard isPeripheralHostActive else { return nil }

        let idCharacteristic = CBMutableCharacteristic(
            type: idUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let statusCharacteristic = CBMutableCharacteristic(
            type: statusUUID,
            properties: [.read],
            value: nil,
            permissions: [.readable]
        )
        let authChallengeCharacteristic = CBMutableCharacteristic(
            type: authChallengeUUID,
            properties: [.write],
            value: nil,
            permissions: [.writeable]
        )
        let authResponseCharacteristic = CBMutableCharacteristic(
            type: authResponseUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )
        let secureInCharacteristic = CBMutableCharacteristic(
            type: secureInUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let secureAckCharacteristic = CBMutableCharacteristic(
            type: secureAckUUID,
            properties: [.read, .write, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )
        let secureChatInCharacteristic = CBMutableCharacteristic(
            type: secureChatInUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let secureChatOutCharacteristic = CBMutableCharacteristic(
            type: secureChatOutUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )
        let callIoInCharacteristic = CBMutableCharacteristic(
            type: callIoInUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        let callIoOutCharacteristic = CBMutableCharacteristic(
            type: callIoOutUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        let service = CBMutableService(type: serviceUUID, primary: true)
        service.characteristics = [
            idCharacteristic,
            statusCharacteristic,
            authChallengeCharacteristic,
            authResponseCharacteristic,
            secureInCharacteristic,
            secureAckCharacteristic,
            secureChatInCharacteristic,
            secureChatOutCharacteristic,
            callIoInCharacteristic,
            callIoOutCharacteristic
        ]

        self.service = service
        self.idCharacteristic = idCharacteristic
        self.statusCharacteristic = statusCharacteristic
        self.authChallengeCharacteristic = authChallengeCharacteristic
        self.authResponseCharacteristic = authResponseCharacteristic
        self.secureInCharacteristic = secureInCharacteristic
        self.secureAckCharacteristic = secureAckCharacteristic
        self.secureChatInCharacteristic = secureChatInCharacteristic
        self.secureChatOutCharacteristic = secureChatOutCharacteristic
        self.callIoOutCharacteristic = callIoOutCharacteristic
        return service
    }

    private func resetHostedTransportState() {
        authResponseValues.removeAll()
        secureAckValues.removeAll()
        secureChatOutValues.removeAll()
        pendingChatOutValues.removeAll()
        queuedSecureChatPayloads.removeAll()
        pendingAckValues.removeAll()
        pendingNotifications.removeAll()
        sessionKeys.removeAll()
        publishRescuerCount()
        authSessionNonces.removeAll()
        pskCentrals.removeAll()
        secureInReceivers.removeAll()
        secureChatReceivers.removeAll()
        verifiedCentrals.removeAll()
        subscribedChatCentrals.removeAll()
        subscribedAckCentrals.removeAll()
        knownCentrals.removeAll()
        centralToSessionId.removeAll()
        sessionToCentralId.removeAll()
        incomingImageTransfers.removeAll()
        service = nil
        idCharacteristic = nil
        statusCharacteristic = nil
        authChallengeCharacteristic = nil
        authResponseCharacteristic = nil
        secureInCharacteristic = nil
        secureAckCharacteristic = nil
        secureChatInCharacteristic = nil
        secureChatOutCharacteristic = nil
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        DispatchQueue.main.async { [weak self] in
            self?.bluetoothState = peripheral.state
        }
        guard peripheral.state == .poweredOn else {
            DispatchQueue.main.async { [weak self] in self?.isBroadcasting = false }
            return
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            DispatchQueue.main.async { [weak self] in self?.isBroadcasting = false }
            return
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        DispatchQueue.main.async { [weak self] in
            self?.isBroadcasting = (error == nil) && (self?.isPeripheralHostActive == true)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        let centralId = request.central.identifier
        switch request.characteristic.uuid {
        case idUUID:
            respond(to: request, with: idValue, peripheral: peripheral)
        case statusUUID:
            respond(to: request, with: statusValue, peripheral: peripheral)
        case authResponseUUID:
            let value = authResponseValues[centralId] ?? Data()
            respond(to: request, with: value, peripheral: peripheral)
        case secureAckUUID:
            let value = secureAckValues[centralId] ?? Data()
            respond(to: request, with: value, peripheral: peripheral)
        case secureChatOutUUID:
            let value = secureChatOutValues[centralId]
                ?? queuedSecureChatPayloads[centralId]?.first
                ?? Data()
            respond(to: request, with: value, peripheral: peripheral)
        default:
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            let result = handleWrite(request, peripheral: peripheral)
            peripheral.respond(to: request, withResult: result)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        let centralId = central.identifier
        knownCentrals[centralId] = central
        if characteristic.uuid == secureChatOutUUID {
            subscribedChatCentrals[centralId] = central
            drainSecureChatPayloadQueue(for: centralId, peripheral: peripheral)
        } else if characteristic.uuid == secureAckUUID {
            subscribedAckCentrals[centralId] = central
            if let payload = pendingAckValues[centralId], let secureAckCharacteristic {
                pendingAckValues.removeValue(forKey: centralId)
                queueNotification(payload, for: secureAckCharacteristic, to: central, peripheral: peripheral)
            }
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        let centralId = central.identifier
        let key = PendingNotificationKey(centralId: centralId, characteristicId: characteristic.uuid)
        pendingNotifications.removeValue(forKey: key)
        if characteristic.uuid == secureChatOutUUID {
            subscribedChatCentrals.removeValue(forKey: centralId)
            pendingChatOutValues.removeValue(forKey: centralId)
            queuedSecureChatPayloads.removeValue(forKey: centralId)
            secureChatOutValues.removeValue(forKey: centralId)
            incomingImageTransfers = incomingImageTransfers.filter { key, _ in
                !key.hasPrefix("\(centralId.uuidString)|")
            }
        } else if characteristic.uuid == secureAckUUID {
            subscribedAckCentrals.removeValue(forKey: centralId)
            pendingAckValues.removeValue(forKey: centralId)
        }
        if subscribedChatCentrals[centralId] == nil && subscribedAckCentrals[centralId] == nil {
            resetCentralState(for: centralId)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPendingNotifications(peripheral)
    }

    private func respond(to request: CBATTRequest, with value: Data, peripheral: CBPeripheralManager) {
        if request.offset > value.count {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        request.value = value.subdata(in: request.offset..<value.count)
        peripheral.respond(to: request, withResult: .success)
    }

    private func handleWrite(_ request: CBATTRequest, peripheral: CBPeripheralManager) -> CBATTError.Code {
        knownCentrals[request.central.identifier] = request.central
        switch request.characteristic.uuid {
        case authChallengeUUID:
            return handleAuthChallenge(request.value, central: request.central, peripheral: peripheral)
        case secureInUUID:
            return handleSecureIn(request.value, central: request.central, peripheral: peripheral)
        case callIoInUUID:
            return handleCallIo(request.value, central: request.central)
        case secureChatInUUID:
            return handleSecureChatIn(request.value, central: request.central, peripheral: peripheral)
        case secureAckUUID:
            return handleSecureAck(request.value, central: request.central)
        default:
            return .attributeNotFound
        }
    }

    private func handleAuthChallenge(
        _ value: Data?,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) -> CBATTError.Code {
        guard let value, !value.isEmpty else { return .invalidAttributeValueLength }
        let centralId = central.identifier
        resetCentralState(for: centralId)
        if value == pskChallengeValue {
            guard let key = localPskKey() else { return .unlikelyError }
            sessionKeys[centralId] = key
            publishRescuerCount()
            authSessionNonces.removeValue(forKey: centralId)
            pskCentrals.insert(centralId)
            authResponseValues[centralId] = pskResponseValue
            if let authResponseCharacteristic {
                _ = peripheral.updateValue(pskResponseValue, for: authResponseCharacteristic, onSubscribedCentrals: [central])
            }
            return .success
        }
        do {
            let clientPublicKey = try decodeX509AgreementPublicKey(from: value)
            let serverKey = P256.KeyAgreement.PrivateKey()
            let sharedSecret = try serverKey.sharedSecretFromKeyAgreement(with: clientPublicKey)
            let sessionKey = sharedSecret.hkdfDerivedSymmetricKey(
                using: SHA256.self,
                salt: Data(repeating: 0, count: 32),
                sharedInfo: Data(),
                outputByteCount: 32
            )
            sessionKeys[centralId] = sessionKey
            publishRescuerCount()

            let response = makeX509PublicKeyData(from: serverKey.publicKey)
            authSessionNonces[centralId] = buildSessionNonce(
                clientPublicKey: value,
                serverPublicKey: response
            )
            authResponseValues[centralId] = response
            if let authResponseCharacteristic {
                _ = peripheral.updateValue(response, for: authResponseCharacteristic, onSubscribedCentrals: [central])
            }
            return .success
        } catch {
            sessionKeys.removeValue(forKey: centralId)
            authSessionNonces.removeValue(forKey: centralId)
            authResponseValues.removeValue(forKey: centralId)
            return .unlikelyError
        }
    }

    private func handleSecureIn(
        _ value: Data?,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) -> CBATTError.Code {
        guard let value, !value.isEmpty else { return .invalidAttributeValueLength }
        let centralId = central.identifier
        guard let sessionKey = sessionKeys[centralId] else { return .insufficientAuthentication }
        let receiver = secureInReceivers[centralId] ?? SOSChunkReceiver(maxPacketSize: maxRoleProofPacketBytes)
        secureInReceivers[centralId] = receiver

        do {
            guard let packet = try receiver.onChunk(value) else { return .success }
            if let handshake = try? AesGcmHelper.decryptPacket(
                key: sessionKey,
                transportPacket: packet,
                maxPacketSize: maxRoleProofPacketBytes
            ),
            let payload = ContactHandshakePayload.decode(from: handshake) {
                receiver.reset()
                verifiedCentrals.insert(centralId)
                let sessionId = BroadcastSessionId.fromBroadcastId(payload.broadcastId)
                bindSession(sessionId, to: centralId)
                registerCallTransport(centralId: centralId, sessionId: sessionId)
                setAck(handshakeAckValue, for: central, peripheral: peripheral)
                DispatchQueue.main.async {
                    SOSChatStore.shared.ensureSession(
                        id: sessionId,
                        displayName: payload.name,
                        role: .unknown,
                        isVerified: true
                    )
                }
                sendPeerIdentity(to: central, peripheral: peripheral)
                return .success
            }
            guard let expectedSessionNonce = authSessionNonces[centralId] else {
                throw RoleProofVerificationError.sessionNonceMismatch
            }
            let proof = try roleProofVerifier.verifyPacket(
                key: sessionKey,
                transportPacket: packet,
                maxPacketSize: maxRoleProofPacketBytes,
                expectedSessionNonce: expectedSessionNonce
            )
            receiver.reset()
            verifiedCentrals.insert(centralId)
            let sessionId = stableSessionId(from: proof)
            bindSession(sessionId, to: centralId)
            registerCallTransport(centralId: centralId, sessionId: sessionId)
            setAck(handshakeAckValue, for: central, peripheral: peripheral)
            DispatchQueue.main.async {
                SOSChatStore.shared.ensureSession(id: sessionId, role: .fieldTeam, isVerified: true)
                self.lastAuthenticatedFieldTeamSessionId = sessionId
            }
            sendPeerIdentity(to: central, peripheral: peripheral)
            return .success
        } catch {
            receiver.reset()
            verifiedCentrals.remove(centralId)
            sessionKeys.removeValue(forKey: centralId)
            authSessionNonces.removeValue(forKey: centralId)
            secureAckValues.removeValue(forKey: centralId)
            authResponseValues.removeValue(forKey: centralId)
            NSLog("SOS secure handshake failed for %@: %@", centralId.uuidString, String(describing: error))
            return .unlikelyError
        }
    }

    private func handleSecureChatIn(
        _ value: Data?,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) -> CBATTError.Code {
        guard let value, !value.isEmpty else { return .invalidAttributeValueLength }
        let centralId = central.identifier
        guard verifiedCentrals.contains(centralId) else { return .insufficientAuthentication }
        guard let sessionKey = sessionKeys[centralId] else { return .insufficientAuthentication }
        let sessionId = resolveSessionId(for: centralId)

        let receiver = secureChatReceivers[centralId] ?? SOSChunkReceiver(maxPacketSize: maxSecureChatPacketBytes)
        secureChatReceivers[centralId] = receiver

        do {
            guard let packet = try receiver.onChunk(value) else { return .success }
            let plaintext = try AesGcmHelper.decryptPacket(
                key: sessionKey,
                transportPacket: packet,
                maxPacketSize: maxSecureChatPacketBytes
            )
            receiver.reset()
            let messageText = String(data: plaintext, encoding: .utf8) ?? ""
            let cleanedText = messageText.trimmingCharacters(in: CharacterSet(charactersIn: "\0").union(.whitespacesAndNewlines))
            if let identity = parsePeerInfoPayload(cleanedText) {
                applyPeerIdentity(identity, sessionId: sessionId)
                setAck(peerInfoAckValue, for: central, peripheral: peripheral)
            } else if let imagePacket = BleImagePayload.parsePacket(cleanedText) {
                handleIncomingImagePacket(
                    imagePacket,
                    sessionId: sessionId,
                    central: central,
                    peripheral: peripheral
                )
            } else if let voicePacket = BleVoicePayload.parsePacket(cleanedText) {
                handleIncomingVoicePacket(
                    voicePacket,
                    sessionId: sessionId,
                    central: central,
                    peripheral: peripheral
                )
            } else if let filePacket = BleFilePayload.parsePacket(cleanedText) {
                handleIncomingFilePacket(
                    filePacket,
                    sessionId: sessionId,
                    central: central,
                    peripheral: peripheral
                )
            } else if let liveLocation = Self.parseLiveLocationPayload(cleanedText) {
                DispatchQueue.main.async {
                    _ = SOSChatStore.shared.upsertRemoteLocationMessage(
                        sessionId: sessionId,
                        latitude: liveLocation.latitude,
                        longitude: liveLocation.longitude,
                        horizontalAccuracyMeters: liveLocation.horizontalAccuracyMeters,
                        capturedAt: liveLocation.capturedAt,
                        transportMessageId: liveLocation.messageId
                    )
                }
                if let messageId = liveLocation.messageId {
                    setAck(
                        BleChatEnvelope.encodeDeliveredAck(messageId: messageId),
                        for: central,
                        peripheral: peripheral
                    )
                }
            } else if let envelope = BleChatEnvelope.decodeChat(cleanedText), !BleChatEnvelope.isExpired(envelope) {
                let appended = appendRemoteMessageOnMain(
                    sessionId: sessionId,
                    text: envelope.text,
                    transportMessageId: envelope.messageId
                )
                if appended {
                    DispatchQueue.main.async {
                        let title = SOSChatStore.shared.sessions.first(where: { $0.id == sessionId })?.displayName
                        SOSNotificationCenter.notifyIncomingMessage(
                            sessionId: sessionId,
                            title: title,
                            body: envelope.text,
                            kind: .sosAlert
                        )
                    }
                }
                setAck(
                    BleChatEnvelope.encodeDeliveredAck(messageId: envelope.messageId),
                    for: central,
                    peripheral: peripheral
                )
            } else {
                if !cleanedText.isEmpty {
                    DispatchQueue.main.async {
                        SOSChatStore.shared.appendRemoteMessage(sessionId: sessionId, text: cleanedText)
                        let title = SOSChatStore.shared.sessions.first(where: { $0.id == sessionId })?.displayName
                        SOSNotificationCenter.notifyIncomingMessage(
                            sessionId: sessionId,
                            title: title,
                            body: cleanedText,
                            kind: .sosAlert
                        )
                    }
                }
                setAck(messageAckValue, for: central, peripheral: peripheral)
            }
            return .success
        } catch {
            receiver.reset()
            return .unlikelyError
        }
    }

    private func handleSecureAck(_ value: Data?, central: CBCentral) -> CBATTError.Code {
        guard let value, !value.isEmpty else { return .invalidAttributeValueLength }
        let centralId = central.identifier
        let sessionId = resolveSessionId(for: centralId)
        DispatchQueue.main.async {
            if let ack = BleChatEnvelope.decodeAck(value) {
                switch ack.type {
                case .delivered:
                    SOSChatStore.shared.markDelivered(
                        sessionId: sessionId,
                        transportMessageIds: ack.messageIds.isEmpty ? nil : ack.messageIds
                    )
                case .read:
                    SOSChatStore.shared.markRead(
                        sessionId: sessionId,
                        transportMessageIds: ack.messageIds.isEmpty ? nil : ack.messageIds
                    )
                case .decryptFail:
                    break
                }
            } else if value == self.messageAckValue {
                SOSChatStore.shared.markDelivered(sessionId: sessionId)
            } else if value == self.readAckValue {
                SOSChatStore.shared.markRead(sessionId: sessionId)
            }
        }
        return .success
    }

    // ---------------------------------------------------------------------------------------
    // Voice call over the rescue link (peripheral role): dedicated 0xCC40/0xCC41 pair; the
    // engine owns state/crypto, this class is only the packet pipe. See RescueCallEngine.
    // ---------------------------------------------------------------------------------------

    private func handleCallIo(_ value: Data?, central: CBCentral) -> CBATTError.Code {
        guard let value, !value.isEmpty else { return .invalidAttributeValueLength }
        let centralId = central.identifier
        guard verifiedCentrals.contains(centralId), sessionKeys[centralId] != nil else {
            return .insufficientAuthentication
        }
        let sessionId = resolveSessionId(for: centralId)
        registerCallTransport(centralId: centralId, sessionId: sessionId)
        RescueCallEngine.shared.onInboundPacket(sessionId: sessionId, packet: value)
        return .success
    }

    /// Registers (or refreshes) the engine transport for one verified rescuer. Captures the
    /// session key by value, so re-registration after a re-handshake supplies the fresh key.
    private func registerCallTransport(centralId: UUID, sessionId: UUID) {
        guard let key = sessionKeys[centralId], let central = knownCentrals[centralId] else { return }
        RescueCallEngine.shared.registerTransport(
            RescueCallEngine.Transport(
                sessionId: sessionId,
                sendPacket: { [weak self] packet in
                    guard let self,
                          let characteristic = self.callIoOutCharacteristic,
                          let peripheral = self.peripheralManager else {
                        return false
                    }
                    return peripheral.updateValue(
                        packet,
                        for: characteristic,
                        onSubscribedCentrals: [central]
                    )
                },
                sessionKey: { key },
                peerName: { nil }
            )
        )
    }

    private func resetCentralState(for centralId: UUID) {
        RescueCallEngine.shared.unregisterTransport(sessionId: resolveSessionId(for: centralId))
        authResponseValues.removeValue(forKey: centralId)
        secureAckValues.removeValue(forKey: centralId)
        secureChatOutValues.removeValue(forKey: centralId)
        pendingChatOutValues.removeValue(forKey: centralId)
        queuedSecureChatPayloads.removeValue(forKey: centralId)
        pendingAckValues.removeValue(forKey: centralId)
        sessionKeys.removeValue(forKey: centralId)
        authSessionNonces.removeValue(forKey: centralId)
        pskCentrals.remove(centralId)
        verifiedCentrals.remove(centralId)
        secureInReceivers[centralId]?.reset()
        secureInReceivers.removeValue(forKey: centralId)
        secureChatReceivers[centralId]?.reset()
        secureChatReceivers.removeValue(forKey: centralId)
        clearPendingNotifications(for: centralId)
        incomingImageTransfers = incomingImageTransfers.filter { key, _ in
            !key.hasPrefix("\(centralId.uuidString)|")
        }
        knownCentrals.removeValue(forKey: centralId)
        unbindCentral(centralId)
    }

    private func clearPendingNotifications(for centralId: UUID) {
        pendingNotifications.keys
            .filter { $0.centralId == centralId }
            .forEach { pendingNotifications.removeValue(forKey: $0) }
    }

    private func resolveSessionId(for centralId: UUID) -> UUID {
        centralToSessionId[centralId] ?? centralId
    }

    private func bindSession(_ sessionId: UUID, to centralId: UUID) {
        let previousSessionId = centralToSessionId[centralId]
        if let existingCentral = sessionToCentralId[sessionId], existingCentral != centralId {
            centralToSessionId.removeValue(forKey: existingCentral)
        }
        if let previousSessionId,
           previousSessionId != sessionId,
           sessionToCentralId[previousSessionId] == centralId {
            sessionToCentralId.removeValue(forKey: previousSessionId)
            DispatchQueue.main.async {
                SOSChatStore.shared.mergeSession(from: previousSessionId, into: sessionId)
            }
        }
        centralToSessionId[centralId] = sessionId
        sessionToCentralId[sessionId] = centralId
    }

    private func unbindCentral(_ centralId: UUID) {
        if let sessionId = centralToSessionId.removeValue(forKey: centralId),
           sessionToCentralId[sessionId] == centralId {
            sessionToCentralId.removeValue(forKey: sessionId)
        }
    }

    private func stableSessionId(from proof: RoleProofPayload) -> UUID {
        let keyData = decodeBase64(proof.devicePublicKey) ?? Data(proof.devicePublicKey.utf8)
        let digest = SHA256.hash(data: keyData)
        let bytes = Array(digest)
        let tuple: uuid_t = (
            bytes[0], bytes[1], bytes[2], bytes[3],
            bytes[4], bytes[5], bytes[6], bytes[7],
            bytes[8], bytes[9], bytes[10], bytes[11],
            bytes[12], bytes[13], bytes[14], bytes[15]
        )
        return UUID(uuid: tuple)
    }

    private func decodeBase64(_ value: String) -> Data? {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        var padded = trimmed
        let remainder = padded.count % 4
        if remainder != 0 {
            padded = padded.padding(toLength: padded.count + (4 - remainder), withPad: "=", startingAt: 0)
        }
        return Data(base64Encoded: padded, options: [.ignoreUnknownCharacters])
    }

    private func buildSessionNonce(clientPublicKey: Data, serverPublicKey: Data) -> String {
        var combined = Data()
        combined.append(clientPublicKey)
        combined.append(serverPublicKey)
        let digest = Data(SHA256.hash(data: combined))
        return digest.base64EncodedString()
    }

    private func localPskKey() -> SymmetricKey? {
        let base64 = SecureLocalStore.shared.getOrCreateAesKeyBase64()
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]),
              data.count == 32 else { return nil }
        return SymmetricKey(data: data)
    }

    func sendChatMessage(to sessionId: UUID, text: String, transportMessageId: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return false }
        var queued = false
        queue.sync {
            let centralId = sessionToCentralId[sessionId] ?? sessionId
            guard verifiedCentrals.contains(centralId),
                  let sessionKey = sessionKeys[centralId],
                  let peripheralManager else {
                return
            }
            let payloadText = BleChatEnvelope.encodeChat(
                messageId: transportMessageId,
                text: trimmed,
                createdAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
                ttlMillis: textMessageTtlMillis,
                attempt: 1,
                route: outboundRouteBleGattFallback
            )
            let payload = AesGcmHelper.encryptPacket(
                key: sessionKey,
                plaintext: Data(payloadText.utf8),
                maxPacketSize: maxSecureChatPacketBytes
            )
            guard !payload.isEmpty else { return }
            queued = enqueueSecureChatPayloads([payload], for: centralId, peripheral: peripheralManager)
        }
        return queued
    }

    func sendImageMessage(
        to sessionId: UUID,
        imageFileName: String,
        mimeType: String,
        width: Int,
        height: Int,
        messageId: String
    ) -> Bool {
        var queued = false
        queue.sync {
            let centralId = sessionToCentralId[sessionId] ?? sessionId
            guard verifiedCentrals.contains(centralId),
                  let sessionKey = sessionKeys[centralId],
                  let peripheralManager,
                  let imageData = SOSChatStore.loadImageData(fileName: imageFileName),
                  !imageData.isEmpty else {
                return
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
            guard !packets.isEmpty else { return }
            let encryptedPackets = packets.compactMap { packet -> Data? in
                let encrypted = AesGcmHelper.encryptPacket(
                    key: sessionKey,
                    plaintext: Data(packet.utf8),
                    maxPacketSize: maxSecureChatPacketBytes
                )
                return encrypted.isEmpty ? nil : encrypted
            }
            guard encryptedPackets.count == packets.count else { return }
            queued = enqueueSecureChatPayloads(encryptedPackets, for: centralId, peripheral: peripheralManager)
        }
        return queued
    }

    func sendReadReceipt(to sessionId: UUID, transportMessageIds: [String]) -> Bool {
        var sent = false
        queue.sync {
            let centralId = sessionToCentralId[sessionId] ?? sessionId
            guard verifiedCentrals.contains(centralId),
                  let central = knownCentrals[centralId],
                  let peripheralManager else {
                return
            }
            let payload = BleChatEnvelope.encodeReadAck(messageIds: transportMessageIds)
            setAck(payload, for: central, peripheral: peripheralManager)
            sent = true
        }
        return sent
    }

    private func enqueueSecureChatPayloads(
        _ payloads: [Data],
        for centralId: UUID,
        peripheral: CBPeripheralManager
    ) -> Bool {
        guard !payloads.isEmpty else { return false }
        queuedSecureChatPayloads[centralId, default: []].append(contentsOf: payloads)
        drainSecureChatPayloadQueue(for: centralId, peripheral: peripheral)
        return true
    }

    private func drainSecureChatPayloadQueue(for centralId: UUID, peripheral: CBPeripheralManager) {
        let notificationKey = PendingNotificationKey(centralId: centralId, characteristicId: secureChatOutUUID)
        guard pendingNotifications[notificationKey] == nil,
              let central = subscribedChatCentrals[centralId],
              let secureChatOutCharacteristic else {
            return
        }
        while let payload = queuedSecureChatPayloads[centralId]?.first {
            secureChatOutValues[centralId] = payload
            if let nextOffset = sendChunks(
                payload,
                startingAt: 0,
                to: central,
                characteristic: secureChatOutCharacteristic,
                peripheral: peripheral
            ) {
                pendingNotifications[notificationKey] = PendingNotification(payload: payload, offset: nextOffset)
                return
            }
            queuedSecureChatPayloads[centralId]?.removeFirst()
            if queuedSecureChatPayloads[centralId]?.isEmpty == true {
                queuedSecureChatPayloads.removeValue(forKey: centralId)
            }
        }
        secureChatOutValues.removeValue(forKey: centralId)
    }

    private func handleIncomingImagePacket(
        _ packet: BleImagePayload.Packet,
        sessionId: UUID,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        cleanupStaleIncomingImageTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingImageTransfers[imageTransferKey(centralId: central.identifier, transferId: payload.transferId)] =
                BleImagePayload.IncomingTransfer(
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
            let key = imageTransferKey(centralId: central.identifier, transferId: payload.transferId)
            guard var transfer = incomingImageTransfers[key] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingImageTransfers.removeValue(forKey: key)
                sendImageAbortPacket(
                    transferId: payload.transferId,
                    reason: "invalid_chunk",
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingImageTransfers[key] = transfer
            guard transfer.isComplete,
                  let imageData = transfer.composedData(),
                  imageData.count <= BleImagePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: imageData))
            guard digest == transfer.sha256 else {
                incomingImageTransfers.removeValue(forKey: key)
                sendImageAbortPacket(
                    transferId: payload.transferId,
                    reason: "sha_mismatch",
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingImageTransfers.removeValue(forKey: key)
            guard let imageRelativePath = SOSChatStore.persistImageData(
                imageData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
            ) else {
                sendImageAbortPacket(
                    transferId: payload.transferId,
                    reason: "persist_failed",
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            let thumbnailRelativePath = makeThumbnailRelativePath(
                imageData: imageData,
                messageId: transfer.messageId
            )
            let appended = appendRemoteImageMessageOnMain(
                sessionId: sessionId,
                imageRelativePath: imageRelativePath,
                thumbnailRelativePath: thumbnailRelativePath,
                imageWidth: transfer.width,
                imageHeight: transfer.height,
                imageMimeType: transfer.mimeType,
                transportMessageId: transfer.messageId
            )
            if appended {
                DispatchQueue.main.async {
                    let title = SOSChatStore.shared.sessions.first(where: { $0.id == sessionId })?.displayName
                    SOSNotificationCenter.notifyIncomingMessage(
                        sessionId: sessionId,
                        title: title,
                        body: SOSChatStore.imagePreviewText(),
                        kind: .sosAlert
                    )
                }
            }
            sendImageDonePacket(
                transferId: payload.transferId,
                to: central,
                peripheral: peripheral
            )
        case .done, .abort:
            break
        }
    }

    private func sendPeerIdentity(to central: CBCentral, peripheral: CBPeripheralManager) {
        let name = localDisplayName()
        let isContactPeer = pskCentrals.contains(central.identifier)
        let role = isContactPeer ? "contact" : "victim"
        let payload = buildPeerInfoPayload(
            name: name,
            role: role,
            batteryPercent: currentBatteryPercent(),
            avatarBase64: localAvatarPayload(),
            signalLocation: isContactPeer ? nil : signalLocationCoordinator.currentVictimLocationPayload().map {
                SOSSignalLocationPayload(gps: $0, relativeEstimate: nil)
            },
            // Medical details go to verified rescuers only — contacts chat here too, and they
            // should not silently receive health data.
            medical: isContactPeer ? nil : MedicalInfoStore.load().toPeerInfoJSON()
        )
        guard let sessionKey = sessionKeys[central.identifier] else { return }
        let encrypted = AesGcmHelper.encryptPacket(
            key: sessionKey,
            plaintext: Data(payload.utf8),
            maxPacketSize: maxSecureChatPacketBytes
        )
        guard !encrypted.isEmpty else { return }
        _ = enqueueSecureChatPayloads([encrypted], for: central.identifier, peripheral: peripheral)
    }

    private func sendPeerIdentityUpdateToVerifiedCentrals() {
        guard isSessionActive,
              let peripheral = peripheralManager else {
            return
        }
        verifiedCentrals
            .compactMap { knownCentrals[$0] }
            .forEach { central in
                sendPeerIdentity(to: central, peripheral: peripheral)
            }
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
        return NSLocalizedString("SOS_CHAT_ROLE_VICTIM", comment: "")
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

    private func buildPeerInfoPayload(
        name: String,
        role: String,
        batteryPercent: Int? = nil,
        avatarBase64: String? = nil,
        signalLocation: SOSSignalLocationPayload? = nil,
        medical: [String: Any]? = nil
    ) -> String {
        var payload: [String: Any] = [
            "kind": "peer_info",
            "name": name,
            "role": role
        ]
        if let batteryPercent, (0...100).contains(batteryPercent) {
            payload["batteryPct"] = batteryPercent
        }
        if let medical, !medical.isEmpty {
            payload["medical"] = medical
        }
        if let avatarBase64 = avatarBase64?.trimmingCharacters(in: .whitespacesAndNewlines),
           !avatarBase64.isEmpty {
            payload["avatarB64"] = avatarBase64
        }
        if let signalLocationJSON = signalLocation?.toPeerInfoJSON() {
            payload["signalLocation"] = signalLocationJSON
        }
        if let legacyLocationJSON = signalLocation?.gps?.toJSON() {
            payload["location"] = legacyLocationJSON
        }
        let data = (try? JSONSerialization.data(withJSONObject: payload, options: [])) ?? Data()
        return String(data: data, encoding: .utf8) ?? ""
    }

    private func parsePeerInfoPayload(_ message: String) -> PeerIdentity? {
        guard let data = message.data(using: .utf8) else { return nil }
        guard let object = try? JSONSerialization.jsonObject(with: data, options: []),
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
        return PeerIdentity(
            name: name,
            role: role,
            avatarBase64: avatarBase64,
            signalLocation: SOSSignalLocationPayload.fromPeerInfoJSON(json["signalLocation"])
                ?? SOSSignalLocationPayload.fromLegacyLocationJSON(json["location"])
        )
    }

    private func applyPeerIdentity(_ identity: PeerIdentity, sessionId: UUID) {
        let resolvedRole: SOSChatRole
        switch identity.role.lowercased() {
        case "rescue":
            resolvedRole = .fieldTeam
        case "victim":
            resolvedRole = .victim
        default:
            resolvedRole = .unknown
        }
        DispatchQueue.main.async {
            SOSChatStore.shared.updateIdentity(
                id: sessionId,
                displayName: identity.name,
                role: resolvedRole,
                isVerified: true,
                avatarBase64: identity.avatarBase64
            )
        }
    }

    private func sendImageDonePacket(
        transferId: String,
        to central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        let packet = BleImagePayload.buildDonePacket(transferId)
        guard !packet.isEmpty,
              let sessionKey = sessionKeys[central.identifier] else {
            return
        }
        let encrypted = AesGcmHelper.encryptPacket(
            key: sessionKey,
            plaintext: Data(packet.utf8),
            maxPacketSize: maxSecureChatPacketBytes
        )
        guard !encrypted.isEmpty else { return }
        _ = enqueueSecureChatPayloads([encrypted], for: central.identifier, peripheral: peripheral)
    }

    private func sendImageAbortPacket(
        transferId: String,
        reason: String,
        to central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        let packet = BleImagePayload.buildAbortPacket(transferId, reason: reason)
        guard !packet.isEmpty,
              let sessionKey = sessionKeys[central.identifier] else {
            return
        }
        let encrypted = AesGcmHelper.encryptPacket(
            key: sessionKey,
            plaintext: Data(packet.utf8),
            maxPacketSize: maxSecureChatPacketBytes
        )
        guard !encrypted.isEmpty else { return }
        _ = enqueueSecureChatPayloads([encrypted], for: central.identifier, peripheral: peripheral)
    }

    /// Encrypts + queues one transfer-control packet (done/abort) back to the rescuer.
    private func sendTransferControlPacket(
        _ packetText: String,
        to central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        guard !packetText.isEmpty,
              let sessionKey = sessionKeys[central.identifier] else {
            return
        }
        let encrypted = AesGcmHelper.encryptPacket(
            key: sessionKey,
            plaintext: Data(packetText.utf8),
            maxPacketSize: maxSecureChatPacketBytes
        )
        guard !encrypted.isEmpty else { return }
        _ = enqueueSecureChatPayloads([encrypted], for: central.identifier, peripheral: peripheral)
    }

    private func cleanupStaleMediaTransfers() {
        let now = Date()
        incomingVoiceTransfers = incomingVoiceTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
        incomingFileTransfers = incomingFileTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
    }

    private func handleIncomingVoicePacket(
        _ packet: BleVoicePayload.Packet,
        sessionId: UUID,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        cleanupStaleMediaTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingVoiceTransfers[imageTransferKey(centralId: central.identifier, transferId: payload.transferId)] =
                BleVoicePayload.IncomingTransfer(
                    transferId: payload.transferId,
                    messageId: payload.messageId,
                    mimeType: payload.mimeType,
                    durationMillis: payload.durationMillis,
                    totalBytes: payload.totalBytes,
                    totalChunks: payload.totalChunks,
                    sha256: payload.sha256
                )
        case .chunk(let payload):
            let key = imageTransferKey(centralId: central.identifier, transferId: payload.transferId)
            guard var transfer = incomingVoiceTransfers[key] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingVoiceTransfers.removeValue(forKey: key)
                sendTransferControlPacket(
                    BleVoicePayload.buildAbortPacket(payload.transferId, reason: "invalid_chunk"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingVoiceTransfers[key] = transfer
            guard transfer.isComplete,
                  let audioData = transfer.composedData(),
                  audioData.count <= BleVoicePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: audioData))
            guard digest == transfer.sha256 else {
                incomingVoiceTransfers.removeValue(forKey: key)
                sendTransferControlPacket(
                    BleVoicePayload.buildAbortPacket(payload.transferId, reason: "sha_mismatch"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingVoiceTransfers.removeValue(forKey: key)
            guard let audioRelativePath = SOSChatStore.persistVoiceData(
                audioData,
                messageId: transfer.messageId,
                mimeType: transfer.mimeType
            ) else {
                sendTransferControlPacket(
                    BleVoicePayload.buildAbortPacket(payload.transferId, reason: "persist_failed"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            let durationMillis = transfer.durationMillis
            let transportMessageId = transfer.messageId
            DispatchQueue.main.async {
                let appended = SOSChatStore.shared.appendRemoteAudioMessage(
                    sessionId: sessionId,
                    audioRelativePath: audioRelativePath,
                    durationMillis: durationMillis,
                    transportMessageId: transportMessageId
                )
                if appended {
                    let title = SOSChatStore.shared.sessions.first(where: { $0.id == sessionId })?.displayName
                    SOSNotificationCenter.notifyIncomingMessage(
                        sessionId: sessionId,
                        title: title,
                        body: SOSChatStore.voicePreviewText(),
                        kind: .sosAlert
                    )
                }
            }
            sendTransferControlPacket(
                BleVoicePayload.buildDonePacket(payload.transferId),
                to: central,
                peripheral: peripheral
            )
        case .done:
            break
        case .abort(let payload):
            incomingVoiceTransfers.removeValue(
                forKey: imageTransferKey(centralId: central.identifier, transferId: payload.transferId)
            )
        }
    }

    private func handleIncomingFilePacket(
        _ packet: BleFilePayload.Packet,
        sessionId: UUID,
        central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        cleanupStaleMediaTransfers()
        switch packet {
        case .initPacket(let payload):
            incomingFileTransfers[imageTransferKey(centralId: central.identifier, transferId: payload.transferId)] =
                BleFilePayload.IncomingTransfer(
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
            let key = imageTransferKey(centralId: central.identifier, transferId: payload.transferId)
            guard var transfer = incomingFileTransfers[key] else { return }
            guard transfer.addChunk(index: payload.chunkIndex, bytes: payload.bytes) else {
                incomingFileTransfers.removeValue(forKey: key)
                sendTransferControlPacket(
                    BleFilePayload.buildAbortPacket(payload.transferId, reason: "invalid_chunk"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingFileTransfers[key] = transfer
            guard transfer.isComplete,
                  let fileData = transfer.composedData(),
                  fileData.count <= BleFilePayload.maxOutgoingTotalBytes else {
                return
            }
            let digest = Data(SHA256.hash(data: fileData))
            guard digest == transfer.sha256 else {
                incomingFileTransfers.removeValue(forKey: key)
                sendTransferControlPacket(
                    BleFilePayload.buildAbortPacket(payload.transferId, reason: "sha_mismatch"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            incomingFileTransfers.removeValue(forKey: key)
            if transfer.mimeType == ChannelAttachments.authorityMlsEnvelopeMime {
                guard let encoded = String(data: fileData, encoding: .utf8),
                      AuthorityMlsOfflineEnvelopeCodec.decode(encoded) != nil else {
                    sendTransferControlPacket(
                        BleFilePayload.buildAbortPacket(payload.transferId, reason: "invalid_envelope"),
                        to: central,
                        peripheral: peripheral
                    )
                    return
                }
                _ = appendRemoteMessageOnMain(
                    sessionId: sessionId,
                    text: encoded,
                    transportMessageId: transfer.messageId
                )
                sendTransferControlPacket(
                    BleFilePayload.buildDonePacket(payload.transferId),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            if transfer.mimeType == ChannelAttachments.authorityMlsBlobMime {
                guard ChannelAttachments.cacheAuthorityMlsCiphertext(
                    path: transfer.displayName,
                    cipher: fileData
                ) else {
                    sendTransferControlPacket(
                        BleFilePayload.buildAbortPacket(payload.transferId, reason: "persist_failed"),
                        to: central,
                        peripheral: peripheral
                    )
                    return
                }
                sendTransferControlPacket(
                    BleFilePayload.buildDonePacket(payload.transferId),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            guard P2pSharedTransferSupport.persistSharedDocumentData(
                fileData,
                messageId: transfer.messageId,
                displayName: transfer.displayName
            ) != nil else {
                sendTransferControlPacket(
                    BleFilePayload.buildAbortPacket(payload.transferId, reason: "persist_failed"),
                    to: central,
                    peripheral: peripheral
                )
                return
            }
            sendTransferControlPacket(
                BleFilePayload.buildDonePacket(payload.transferId),
                to: central,
                peripheral: peripheral
            )
        case .done:
            break
        case .abort(let payload):
            incomingFileTransfers.removeValue(
                forKey: imageTransferKey(centralId: central.identifier, transferId: payload.transferId)
            )
        }
    }

    private struct VictimLiveLocationPayload {
        let messageId: String?
        let latitude: Double
        let longitude: Double
        let horizontalAccuracyMeters: Double?
        let capturedAt: Date
    }

    private static func parseLiveLocationPayload(_ message: String) -> VictimLiveLocationPayload? {
        guard let data = message.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data, options: []),
              let json = object as? [String: Any],
              let kind = json["kind"] as? String,
              kind == "live_location" else {
            return nil
        }
        func numeric(_ raw: Any?) -> Double? {
            if let value = raw as? Double { return value.isFinite ? value : nil }
            if let value = raw as? Int { return Double(value) }
            if let value = raw as? NSNumber { return value.doubleValue.isFinite ? value.doubleValue : nil }
            return nil
        }
        guard let latitude = numeric(json["latitude"]),
              let longitude = numeric(json["longitude"]) else {
            return nil
        }
        let messageId = (json["messageId"] as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let timestampMillis = numeric(json["timestampMillis"])
        return VictimLiveLocationPayload(
            messageId: messageId?.isEmpty == false ? messageId : nil,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: numeric(json["accuracyMeters"]),
            capturedAt: timestampMillis.map { Date(timeIntervalSince1970: $0 / 1000) } ?? Date()
        )
    }

    /// Victim → rescuer voice clip over the rescue link, mirroring sendImageMessage.
    func sendVoiceMessage(
        to sessionId: UUID,
        audioFileName: String,
        mimeType: String,
        durationMillis: Int,
        messageId: String
    ) -> Bool {
        var queued = false
        queue.sync {
            let centralId = sessionToCentralId[sessionId] ?? sessionId
            guard verifiedCentrals.contains(centralId),
                  let sessionKey = sessionKeys[centralId],
                  let peripheralManager,
                  let audioData = SOSChatStore.loadVoiceData(fileName: audioFileName),
                  !audioData.isEmpty else {
                return
            }
            let transferId = UUID().uuidString.lowercased()
            let packets = BleVoicePayload.buildPackets(
                transferId: transferId,
                messageId: messageId,
                mimeType: mimeType,
                durationMillis: durationMillis,
                bytes: audioData
            )
            guard !packets.isEmpty else { return }
            let encryptedPackets = packets.compactMap { packet -> Data? in
                let encrypted = AesGcmHelper.encryptPacket(
                    key: sessionKey,
                    plaintext: Data(packet.utf8),
                    maxPacketSize: maxSecureChatPacketBytes
                )
                return encrypted.isEmpty ? nil : encrypted
            }
            guard encryptedPackets.count == packets.count else { return }
            queued = enqueueSecureChatPayloads(encryptedPackets, for: centralId, peripheral: peripheralManager)
        }
        return queued
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
        sessionId: UUID,
        imageRelativePath: String,
        thumbnailRelativePath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String,
        transportMessageId: String
    ) -> Bool {
        let append = {
            SOSChatStore.shared.appendRemoteImageMessage(
                sessionId: sessionId,
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

    private func cleanupStaleIncomingImageTransfers() {
        let now = Date()
        incomingImageTransfers = incomingImageTransfers.filter { _, transfer in
            now.timeIntervalSince(transfer.createdAt) < 90
        }
    }

    private func imageTransferKey(centralId: UUID, transferId: String) -> String {
        "\(centralId.uuidString)|\(transferId)"
    }

    private func queueNotification(
        _ payload: Data,
        for characteristic: CBMutableCharacteristic,
        to central: CBCentral,
        peripheral: CBPeripheralManager
    ) {
        guard !payload.isEmpty else { return }
        let key = PendingNotificationKey(centralId: central.identifier, characteristicId: characteristic.uuid)
        if let nextOffset = sendChunks(
            payload,
            startingAt: 0,
            to: central,
            characteristic: characteristic,
            peripheral: peripheral
        ) {
            pendingNotifications[key] = PendingNotification(payload: payload, offset: nextOffset)
        } else {
            pendingNotifications.removeValue(forKey: key)
        }
    }

    private func flushPendingNotifications(_ peripheral: CBPeripheralManager) {
        guard !pendingNotifications.isEmpty else { return }
        let pending = pendingNotifications
        for (key, entry) in pending {
            guard let central = subscribedCentral(for: key),
                  let characteristic = characteristic(for: key.characteristicId) else {
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
                if key.characteristicId == secureChatOutUUID {
                    if queuedSecureChatPayloads[key.centralId]?.first == entry.payload {
                        queuedSecureChatPayloads[key.centralId]?.removeFirst()
                        if queuedSecureChatPayloads[key.centralId]?.isEmpty == true {
                            queuedSecureChatPayloads.removeValue(forKey: key.centralId)
                        }
                    }
                    drainSecureChatPayloadQueue(for: key.centralId, peripheral: peripheral)
                }
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
        var current = offset
        let maxLength = max(1, central.maximumUpdateValueLength)
        while current < payload.count {
            let end = min(current + maxLength, payload.count)
            let chunk = payload.subdata(in: current..<end)
            if !peripheral.updateValue(chunk, for: characteristic, onSubscribedCentrals: [central]) {
                return current
            }
            current = end
        }
        return nil
    }

    private func subscribedCentral(for key: PendingNotificationKey) -> CBCentral? {
        switch key.characteristicId {
        case secureChatOutUUID:
            return subscribedChatCentrals[key.centralId]
        case secureAckUUID:
            return subscribedAckCentrals[key.centralId]
        default:
            return nil
        }
    }

    private func characteristic(for uuid: CBUUID) -> CBMutableCharacteristic? {
        switch uuid {
        case secureChatOutUUID:
            return secureChatOutCharacteristic
        case secureAckUUID:
            return secureAckCharacteristic
        default:
            return nil
        }
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

    private func setAck(_ payload: Data, for central: CBCentral, peripheral: CBPeripheralManager) {
        let centralId = central.identifier
        secureAckValues[centralId] = payload
        guard let secureAckCharacteristic else { return }
        if subscribedAckCentrals[centralId] != nil {
            queueNotification(payload, for: secureAckCharacteristic, to: central, peripheral: peripheral)
        } else {
            pendingAckValues[centralId] = payload
        }
    }

    private func decodeX509AgreementPublicKey(from data: Data) throws -> P256.KeyAgreement.PublicKey {
        let x963 = try KeyDecoder.x963PublicKey(from: data)
        return try P256.KeyAgreement.PublicKey(x963Representation: x963)
    }

    private func appendRemoteMessageOnMain(
        sessionId: UUID,
        text: String,
        transportMessageId: String?
    ) -> Bool {
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
}

private enum KeyDecoder {
    private static let x509Prefix: [UInt8] = [
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x02, 0x01,
        0x06, 0x08, 0x2A, 0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00
    ]

    static func x963PublicKey(from x509: Data) throws -> Data {
        guard x509.count >= x509Prefix.count + 65 else {
            throw KeyDecoderError.invalidKeyLength
        }
        let prefixData = Data(x509Prefix)
        guard x509.starts(with: prefixData) else {
            throw KeyDecoderError.invalidKeyPrefix
        }
        let x963 = x509.suffix(65)
        guard x963.first == 0x04 else {
            throw KeyDecoderError.invalidKeyPrefix
        }
        return Data(x963)
    }

    enum KeyDecoderError: Error {
        case invalidKeyLength
        case invalidKeyPrefix
    }
}

private enum AesGcmHelper {
    static func decryptPacket(
        key: SymmetricKey,
        transportPacket: Data,
        maxPacketSize: Int
    ) throws -> Data {
        let encrypted = try unwrapTransportPacket(transportPacket, maxPacketSize: maxPacketSize)
        let sealedBox = try AES.GCM.SealedBox(combined: encrypted)
        return try AES.GCM.open(sealedBox, using: key)
    }

    static func encryptPacket(
        key: SymmetricKey,
        plaintext: Data,
        maxPacketSize: Int
    ) -> Data {
        guard let sealedBox = try? AES.GCM.seal(plaintext, using: key),
              let combined = sealedBox.combined,
              combined.count <= maxPacketSize else {
            return Data()
        }
        return wrapTransportPacket(combined)
    }

    static func unwrapTransportPacket(_ transportPacket: Data, maxPacketSize: Int) throws -> Data {
        guard transportPacket.count >= 2 else {
            throw TransportError.missingHeader
        }
        let length = (Int(transportPacket[0]) << 8) | Int(transportPacket[1])
        guard length >= 1 && length <= maxPacketSize else {
            throw TransportError.invalidLength
        }
        guard transportPacket.count - 2 == length else {
            throw TransportError.truncatedPayload
        }
        return transportPacket.subdata(in: 2..<transportPacket.count)
    }

    static func wrapTransportPacket(_ payload: Data) -> Data {
        var packet = Data()
        let length = payload.count
        packet.append(UInt8((length >> 8) & 0xFF))
        packet.append(UInt8(length & 0xFF))
        packet.append(payload)
        return packet
    }

    enum TransportError: Error {
        case missingHeader
        case invalidLength
        case truncatedPayload
    }
}

private struct PeerIdentity {
    let name: String
    let role: String
    let avatarBase64: String?
    let signalLocation: SOSSignalLocationPayload?
}

private final class SOSChunkReceiver {
    private let maxPacketSize: Int
    private var header = Data()
    private var payload = Data()
    private var expectedLength: Int?

    init(maxPacketSize: Int) {
        self.maxPacketSize = maxPacketSize
    }

    func reset() {
        header.removeAll()
        payload.removeAll()
        expectedLength = nil
    }

    func onChunk(_ chunk: Data) throws -> Data? {
        guard !chunk.isEmpty else { return nil }
        var offset = 0

        if expectedLength == nil {
            while header.count < 2 && offset < chunk.count {
                header.append(chunk[offset])
                offset += 1
            }
            if header.count == 2 {
                let length = (Int(header[0]) << 8) | Int(header[1])
                guard length >= 1 && length <= maxPacketSize else {
                    throw ChunkError.invalidLength
                }
                expectedLength = length
            }
        }

        guard let expectedLength else { return nil }
        if offset < chunk.count {
            payload.append(chunk.subdata(in: offset..<chunk.count))
        }
        guard payload.count <= expectedLength else {
            throw ChunkError.payloadOverflow
        }
        if payload.count == expectedLength {
            var packet = Data()
            packet.append(header)
            packet.append(payload)
            reset()
            return packet
        }
        return nil
    }

    enum ChunkError: Error {
        case invalidLength
        case payloadOverflow
    }
}
