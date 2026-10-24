import SwiftUI
import Combine
import UIKit
import FirebaseAuth

// The operation belongs to the app, not to a particular scanner or navigation entry.
enum ContactAddInputSource { case qr, manual }

@MainActor
protocol ContactVerificationDriving: AnyObject {
    var progressStream: AnyPublisher<ContactVerificationProgress, Never> { get }
    func verify(payload: ContactQRPayload, localDisplayName: String, timeout: TimeInterval,
                completion: @escaping (ContactVerificationOutcome) -> Void)
    func cancel()
}

extension ContactBroadcastVerifier: ContactVerificationDriving {
    var progressStream: AnyPublisher<ContactVerificationProgress, Never> { $progress.eraseToAnyPublisher() }
}

enum ContactOnboardingOutcome: Equatable {
    case saved(UUID)
    case failure(title: String, message: String)
}

@MainActor
protocol ContactOnboardingBackgroundLease: AnyObject {
    func begin(onExpiration: @escaping () -> Void)
    func end()
}

@MainActor
private final class SystemContactOnboardingBackgroundLease: ContactOnboardingBackgroundLease {
    private var identifier: UIBackgroundTaskIdentifier = .invalid
    func begin(onExpiration: @escaping () -> Void) {
        end()
        identifier = UIApplication.shared.beginBackgroundTask(withName: "contact-qr-pairing") {
            Task { @MainActor in onExpiration() }
        }
    }
    func end() {
        guard identifier != .invalid else { return }
        let current = identifier
        identifier = .invalid
        UIApplication.shared.endBackgroundTask(current)
    }
}

@MainActor
final class ContactOnboardingCoordinator: ObservableObject {
    static let shared = ContactOnboardingCoordinator(
        verifier: ContactBroadcastVerifier(), persistence: LiveContactOnboardingPersistence(),
        backgroundLease: SystemContactOnboardingBackgroundLease(), journal: EncryptedContactPairingJournal(),
        accountIdentity: { Auth.auth().currentUser?.uid ?? "" })

    @Published private(set) var source: ContactAddInputSource?
    @Published private(set) var stage: ContactOnboardingStage = .idle
    @Published private(set) var resolvedName = ""
    @Published private(set) var outcome: ContactOnboardingOutcome?
    private let verifier: ContactVerificationDriving
    private let persistence: ContactOnboardingPersisting
    private let backgroundLease: ContactOnboardingBackgroundLease
    private let finishDelay: Duration
    private var operationID: UUID?
    private var progressSubscription: AnyCancellable?
    private var finishTask: Task<Void, Never>?
    private let journal: ContactPairingJournaling?
    private let accountIdentity: () -> String
    private let now: () -> Date
    private var checkpoint: ContactPairingCheckpoint?
    private var didCheckRecovery = false
    var isRunning: Bool { operationID != nil }

    init(verifier: ContactVerificationDriving, persistence: ContactOnboardingPersisting,
         backgroundLease: ContactOnboardingBackgroundLease, finishDelay: Duration = .milliseconds(750),
         journal: ContactPairingJournaling? = nil, accountIdentity: @escaping () -> String = { "" },
         now: @escaping () -> Date = Date.init) {
        self.verifier = verifier
        self.persistence = persistence
        self.backgroundLease = backgroundLease
        self.finishDelay = finishDelay
        self.journal = journal
        self.accountIdentity = accountIdentity
        self.now = now
        progressSubscription = verifier.progressStream.receive(on: RunLoop.main).sink { [weak self] progress in
            guard let self, self.isRunning else { return }
            switch progress {
            case .idle: break // A transport finish is followed by the actual completion callback.
            case .searching: if self.stage != .saving { self.stage = .searching }
            case .pairing: if self.stage != .saving { self.stage = .pairing }
            }
        }
    }

    func start(payload: ContactQRPayload, fallbackName: String, source: ContactAddInputSource,
               localSessionCode: String, localDisplayName: String) {
        guard stage == .idle, outcome == nil, !isRunning else { return }
        let remoteCode = payload.code.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !remoteCode.isEmpty, let key = Data(base64Encoded: payload.key.trimmingCharacters(in: .whitespacesAndNewlines), options: [.ignoreUnknownCharacters]), key.count == 32 else {
            outcome = .failure(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
            return
        }
        guard remoteCode.caseInsensitiveCompare(localSessionCode) != .orderedSame else {
            outcome = .failure(title: "CONTACT_SCAN_SELF_TITLE", message: "CONTACT_SCAN_SELF_MESSAGE")
            return
        }
        didCheckRecovery = true
        checkpoint = ContactPairingCheckpoint(payload: payload, fallbackName: fallbackName, manual: source == .manual,
            owner: localSessionCode + ":" + accountIdentity(), createdAt: now())
        do { if let checkpoint { try journal?.write(checkpoint) } }
        catch { fail(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE"); return }
        begin(payload: payload, fallbackName: fallbackName, source: source, localDisplayName: localDisplayName)
    }

    /// Called from the foreground app root, independently of the scanner's navigation lifetime.
    func resumePending(localSessionCode: String, localDisplayName: String) async {
        guard !didCheckRecovery, !isRunning, outcome == nil else { return }
        // Do not query/upsert against an empty in-memory store while its disk restore is in flight.
        await persistence.prepareForRecovery()
        guard !didCheckRecovery, !isRunning, outcome == nil else { return }
        do {
            guard var pending = try journal?.read() else { didCheckRecovery = true; return }
            guard pending.canResume(owner: localSessionCode + ":" + accountIdentity(), now: now()) else {
                try journal?.clear(); didCheckRecovery = true; return
            }
            pending.recoveries += 1
            try journal?.write(pending)
            didCheckRecovery = true
            checkpoint = pending
            if let savedID = pending.savedID, let saved = persistence.existing(id: savedID) {
                source = pending.manual ? .manual : .qr
                finish(saved)
            } else {
                begin(payload: pending.payload, fallbackName: pending.fallbackName,
                      source: pending.manual ? .manual : .qr, localDisplayName: localDisplayName,
                      verified: pending.verified)
            }
        } catch {
            // A locked/unavailable key must not destroy the journal; retry on the next foreground.
        }
    }

    private func begin(payload: ContactQRPayload, fallbackName: String, source: ContactAddInputSource,
                       localDisplayName: String, verified: ContactVerificationResult? = nil) {
        let id = UUID()
        operationID = id
        self.source = source
        resolvedName = payload.name?.nilIfEmpty ?? fallbackName
        stage = .searching
        backgroundLease.begin { [weak self] in
            guard let self, self.operationID == id else { return }
            self.verifier.cancel()
            self.fail(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE")
        }
        guard payload.supportsSecureOfflinePairing else {
            complete(.failed(.notFound), payload: payload, id: id)
            return
        }
        if let verified {
            complete(.verified(verified), payload: payload, id: id)
            return
        }
        verifier.verify(payload: payload, localDisplayName: localDisplayName, timeout: 12) { [weak self] result in
            // The live verifier delivers on main; hopping also makes injected drivers safe.
            Task { @MainActor in self?.complete(result, payload: payload, id: id) }
        }
    }

    private func complete(_ result: ContactVerificationOutcome, payload: ContactQRPayload, id: UUID) {
        guard operationID == id, stage != .saving else { return }
        switch result {
        case .verified(let verified):
            stage = .saving
            // Durably retain the authenticated result BEFORE mutating contacts. A kill in either
            // write window replays the same identity upsert, without needing the peer to stay online.
            checkpoint?.verified = verified
            do { if let checkpoint { try journal?.write(checkpoint) } }
            catch { fail(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE"); return }
            saveDurably(persistence.verified(verified, payload: payload, fallbackName: resolvedName), id: id)
        case .failed(let error):
            if let saved = persistence.internet(payload, fallbackName: resolvedName) {
                stage = .saving
                saveDurably(saved, id: id)
                return
            }
            switch error {
            case .notFound: fail(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE")
            case .bluetoothPoweredOff: fail(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_DISABLED_MESSAGE")
            case .bluetoothUnauthorized: fail(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_UNAUTHORIZED_MESSAGE")
            case .bluetoothUnsupported: fail(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_UNSUPPORTED_MESSAGE")
            }
        }
    }

    private func saveDurably(_ saved: SavedOnboardingContact, id: UUID) {
        finishTask = Task { [weak self] in
            guard let self else { return }
            do {
                try await self.persistence.flush()
                guard self.operationID == id, !Task.isCancelled else { return }
                self.checkpoint?.savedID = saved.id
                if let checkpoint = self.checkpoint { try self.journal?.write(checkpoint) }
                self.finish(saved)
            } catch {
                guard self.operationID == id else { return }
                self.fail(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE")
            }
        }
    }

    private func finish(_ saved: SavedOnboardingContact) {
        operationID = nil
        backgroundLease.end()
        resolvedName = saved.name
        stage = saved.alreadyRegistered ? .alreadyRegistered : .completed
        finishTask = Task { [weak self, finishDelay] in
            try? await Task.sleep(for: finishDelay)
            guard !Task.isCancelled else { return }
            self?.outcome = .saved(saved.id)
        }
    }

    private func fail(title: String, message: String) {
        operationID = nil
        backgroundLease.end()
        stage = .idle
        outcome = .failure(title: title, message: message)
    }

    // Only an explicit Cancel stops a running operation. Disappearing views are observers.
    func cancel() {
        didCheckRecovery = true
        try? journal?.clear()
        checkpoint = nil
        operationID = nil
        finishTask?.cancel()
        finishTask = nil
        verifier.cancel()
        backgroundLease.end()
        clearPresentation()
    }

    func consumeOutcome() -> ContactOnboardingOutcome? {
        guard let result = outcome else { return nil }
        try? journal?.clear()
        checkpoint = nil
        clearPresentation()
        return result
    }

    func clearIfFinished() {
        guard !isRunning, finishTask == nil, outcome == nil else { return }
        clearPresentation()
    }

    private func clearPresentation() {
        finishTask?.cancel()
        finishTask = nil
        source = nil
        stage = .idle
        resolvedName = ""
        outcome = nil
    }
}

private extension String {
    var nilIfEmpty: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}

@MainActor
protocol ContactOnboardingPersisting {
    func verified(_ result: ContactVerificationResult, payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact
    func internet(_ payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact?
    func prepareForRecovery() async
    func existing(id: UUID) -> SavedOnboardingContact?
    func flush() async throws
}

extension ContactOnboardingPersisting {
    func prepareForRecovery() async {}
    func existing(id: UUID) -> SavedOnboardingContact? { nil }
    func flush() async throws {}
}

struct SavedOnboardingContact {
    let id: UUID
    let name: String
    let alreadyRegistered: Bool
}

@MainActor
private final class LiveContactOnboardingPersistence: ContactOnboardingPersisting {
    private let contactStore = ContactStore.shared
    private var localSessionCode: String { SecureLocalStore.shared.getOrCreateP2pSessionCode() }
    func prepareForRecovery() async { await contactStore.waitUntilLoaded() }
    func existing(id: UUID) -> SavedOnboardingContact? {
        guard let record = contactStore.contact(for: id) else { return nil }
        return SavedOnboardingContact(id: record.id, name: record.name, alreadyRegistered: true)
    }
    func flush() async throws { try await contactStore.flushDurably() }

    func verified(_ result: ContactVerificationResult, payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact {
        let resolvedName = fallbackName
        let remoteSessionCode = payload.code.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = payload.key.trimmingCharacters(in: .whitespacesAndNewlines)
        let canonicalSessionCode = canonicalBleSessionCode(
            remoteDeviceId: result.remoteDeviceId,
            fallbackSessionCode: result.sessionCode,
            fallbackAddress: result.lastKnownBleAddress
        )
        // Prefer a BLE-identity match; else fall back to a contact already stamped with this
        // peer's uid (added earlier via the directory or auto-created from an incoming
        // message) so re-scanning its QR heals that thread instead of spawning a duplicate.
        // Mirrors Android saveInternetContactFromQr's findStoredContactForQr ?? getContactByPeerUid.
        let existingRecord = contactStore.existingBleContact(
            sessionCode: canonicalSessionCode,
            remoteSessionCode: remoteSessionCode,
            bleShareId: result.shareId,
            lastKnownBleAddress: result.lastKnownBleAddress,
            remoteDeviceId: result.remoteDeviceId,
            verifiedIdentityKey: result.remoteDeviceId
        ) ?? (payload.peerUid?.nilIfEmpty).flatMap { contactStore.contactForPeerUid($0) }
        // Route the upsert into the existing thread's session code so upsertBleContact merges
        // in place (preserving that record's id/thread) instead of inserting a fresh row.
        let upsertSessionCode = existingRecord?.sessionCode ?? canonicalSessionCode
        let record = contactStore.upsertBleContact(
            name: result.displayName ?? resolvedName,
            sessionCode: upsertSessionCode,
            aesKeyBase64: key,
            isVerified: true,
            verifiedIdentityKey: result.remoteDeviceId,
            verifiedAt: Date(),
            remoteSessionCode: remoteSessionCode,
            remotePlatform: ContactRemotePlatform.normalize(
                payload.platform.isEmpty ? result.platform : payload.platform
            ),
            bleShareId: result.shareId,
            lastKnownBleAddress: result.lastKnownBleAddress,
            remoteDeviceId: result.remoteDeviceId,
            peerUid: payload.peerUid,
            peerPublicKey: payload.peerPublicKey,
            analyticsSource: "qr"
        )
        MessagingDiagLog.log(
            "qr add (BLE verified): peerUid=\(payload.peerUid?.prefix(8) ?? "nil") " +
                "peerKey=\(payload.peerPublicKey != nil) supportsInternet=\(record.supportsInternet)"
        )
        announceOurIdentity(to: record)
        SOSChatStore.shared.ensureSession(
            id: record.id,
            displayName: record.name,
            role: .unknown,
            isVerified: record.isVerified
        )
        SOSChatStore.shared.updateIdentity(
            id: record.id,
            displayName: record.name,
            role: .unknown,
            isVerified: record.isVerified,
            avatarBase64: result.avatarBase64
        )

        return SavedOnboardingContact(id: record.id, name: record.name, alreadyRegistered: existingRecord != nil)
    }

    func internet(_ payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact? {
        let peerUid = (payload.peerUid ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let peerKey = (payload.peerPublicKey ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let key = payload.key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerUid.isEmpty, !peerKey.isEmpty, isValidAESKey(key) else { return nil }
        let remoteSessionCode = payload.code.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (payload.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = name.isEmpty ? fallbackName : name

        // Reuse an existing thread if we already have this peer (added via the directory or
        // auto-created from an incoming message) so re-scanning its QR heals that contact instead of
        // forking a second thread. Mirrors Android saveInternetContactFromQr's getContactByPeerUid reuse.
        let existingByPeerUid = contactStore.contactForPeerUid(peerUid)
        // No BLE handshake happened, so there is no verified device id/address — key the contact by
        // the existing thread's session code when we found one, else by its remote session code
        // (always present in a QR), else by the peer uid. peerUid + peerPublicKey make it
        // internet-capable (supportsInternet). Routing through the existing session code makes
        // upsertBleContact merge the QR fields into that record in place rather than insert a duplicate.
        let canonicalSessionCode = existingByPeerUid?.sessionCode
            ?? (remoteSessionCode.isEmpty ? peerUid : remoteSessionCode)
        let record = contactStore.upsertBleContact(
            name: resolvedName,
            sessionCode: canonicalSessionCode,
            aesKeyBase64: key,
            offlineKeyVersion: 1,
            isVerified: false,
            remoteSessionCode: remoteSessionCode.nilIfEmpty,
            remotePlatform: ContactRemotePlatform.normalize(payload.platform),
            bleShareId: payload.shareId,
            lastKnownBleAddress: nil,
            remoteDeviceId: nil,
            peerUid: peerUid,
            peerPublicKey: peerKey,
            analyticsSource: "qr_internet"
        )
        SOSChatStore.shared.ensureSession(
            id: record.id,
            displayName: record.name,
            role: .unknown,
            isVerified: false
        )
        MessagingDiagLog.log("qr add: BLE verify failed but saved internet contact peer=\(peerUid.prefix(8))")
        announceOurIdentity(to: record)
        return SavedOnboardingContact(id: record.id, name: record.name, alreadyRegistered: existingByPeerUid != nil)
    }

    private func announceOurIdentity(to record: ContactRecord) {
        guard record.supportsInternet else { return }
        let mySessionCode = localSessionCode
        let myName = ProfileMetadataStore.preferredDisplayName()
        Task {
            _ = await InternetChatTransport.shared.sendIdentityAnnounce(
                contact: record, mySessionCode: mySessionCode, myName: myName
            )
        }
    }

    private func canonicalBleSessionCode(
        remoteDeviceId: String,
        fallbackSessionCode: String,
        fallbackAddress: String
    ) -> String {
        let raw = remoteDeviceId.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? fallbackSessionCode.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? fallbackAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.lowercased().hasPrefix("ble:") {
            return raw
        }
        return "ble:\(raw)"
    }

    private func isValidAESKey(_ base64: String) -> Bool {
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]) else { return false }
        return data.count == 32
    }
}
