import Combine
import CoreBluetooth
import Foundation
import FirebaseAuth
import WatchConnectivity

@MainActor
final class PhoneWatchBridge: NSObject, WCSessionDelegate {
    static let shared = PhoneWatchBridge()
    private var subscriptions = Set<AnyCancellable>()
    private var observing = false
    private var independentRefresh: Task<Void, Never>?
    private var persistenceReady = false
    private var lastPublishedPayload: Data?
    private let defaults = UserDefaults.standard
    private let ledgerKey = "watch.commands.v1"

    func start() {
        if independentRefresh == nil {
            independentRefresh = Task { @MainActor in
                while !Task.isCancelled {
                    if let user = Auth.auth().currentUser, !user.isAnonymous,
                       let receiver = try? IndependentPhoneInbox(ownerUid: user.uid), receiver.isEnabled {
                        try? await receiver.refresh()
                    }
                    try? await Task.sleep(for: .seconds(30)) // Read/ack only; no automatic sends or SOS.
                }
            }
        }
        guard WCSession.isSupported() else { return }
        WCSession.default.delegate = self
        WCSession.default.activate()
        recordTestConnectivity("start")
        Task { @MainActor in
            await self.restorePersistence()
            self.observeState()
            self.publish()
        }
    }

    /// A cold WatchConnectivity wake can precede the first phone scene and disk restore.
    private func restorePersistence() async {
        guard !persistenceReady else { return }
        #if DEBUG && targetEnvironment(simulator)
        if WatchE2ETestMode.contactRebootEnabled {
            await WatchE2ETestSupport.rebootContactStore.waitUntilLoaded()
        } else if !WatchE2ETestMode.enabled {
            await ContactStore.shared.waitUntilLoaded()
        }
        #else
        await ContactStore.shared.waitUntilLoaded()
        #endif
        await SOSChatStore.shared.waitUntilLoaded()
        persistenceReady = true
    }

    private func recordTestConnectivity(_ event: String, error: Error? = nil) {
        #if DEBUG && targetEnvironment(simulator)
        guard WatchE2ETestMode.enabled else { return }
        let session = WCSession.default
        let row: [String: Any] = ["event": event, "timestamp": Date().timeIntervalSince1970,
            "activationState": session.activationState.rawValue, "paired": session.isPaired,
            "watchInstalled": session.isWatchAppInstalled, "reachable": session.isReachable,
            "error": error?.localizedDescription ?? ""]
        if let data = try? JSONSerialization.data(withJSONObject: row, options: [.prettyPrinted, .sortedKeys]) {
            let path = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("phone-connectivity-diagnostics.json")
            try? data.write(to: path, options: .atomic)
        }
        #endif
    }

    private func observeState() {
        guard !observing else { return }
        observing = true
        SOSBroadcastManager.shared.objectWillChange
            .merge(with: SOSChatStore.shared.objectWillChange)
            .merge(with: SOSRecoveryStore.shared.objectWillChange)
            .merge(with: ContactStore.shared.objectWillChange)
            .debounce(for: .milliseconds(150), scheduler: DispatchQueue.main)
            .sink { [weak self] _ in Task { @MainActor in self?.publish() } }
            .store(in: &subscriptions)
        publish()
    }

    private func snapshot() -> WatchPhoneState {
        let manager = SOSBroadcastManager.shared
        let peer = manager.lastAuthenticatedFieldTeamSessionId.flatMap { SOSChatStore.shared.session(for: $0) }
        let verifiedPeer = peer.flatMap { $0.isVerified && $0.role == .fieldTeam ? $0 : nil }
        let lastSaved = SOSRecoveryStore.shared.snapshot.replies.last { $0.kind != "contact" && (verifiedPeer == nil || $0.peer == verifiedPeer?.id.uuidString) }
        #if DEBUG && targetEnvironment(simulator)
        if WatchE2ETestMode.enabled {
            return WatchPhoneState(sosActive: manager.isEmergencyDeclared, bleBroadcasting: false,
                emergencyID: manager.emergencySessionID ?? SOSRecoveryStore.shared.snapshot.session?.id, peerID: WatchE2ETestSupport.peerID,
                peerName: "Test alıcısı · dış iletim yok", lastMessage: WatchE2ETestSupport.shared.replies.last ?? "Durumunuz nasıl?",
                updatedAt: Date().timeIntervalSince1970, recoveryRequired: manager.recoveryRequired,
                lastReplyResult: lastSaved?.result,
                contacts: WatchE2ETestMode.contactsEnabled ? WatchE2ETestSupport.contacts : nil,
                contactCount: WatchE2ETestMode.contactsEnabled ? WatchE2ETestSupport.contactIDs.count : nil)
        }
        #endif
        let visibleContacts = ContactStore.shared.contacts.filter { $0.isAuthorityBridge != true }.sorted {
            $0.name == $1.name ? $0.id.uuidString < $1.id.uuidString : $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending
        }
        let internetAvailable = InternetChatTransport.shared.isAvailable(allowUltraConstrained: true)
        var contactBytes = 0
        let contacts = visibleContacts.prefix(20).compactMap { contact -> WatchSavedContact? in
            let saved = SOSRecoveryStore.shared.snapshot.replies.last { $0.peer == contact.id.uuidString }
            let confirmed = saved.flatMap { reply in SOSChatStore.shared.messages(for: contact.id).last { $0.isLocal && $0.transportMessageId == reply.id }?.status }
            let row = WatchSavedContact(id: contact.id.uuidString, name: String(contact.name.prefix(60)),
                lastMessage: String((SOSChatStore.shared.session(for: contact.id)?.lastMessage ?? "").prefix(100)),
                canSend: WatchContactMessaging.eligible(contact) && internetAvailable, internetCapable: contact.supportsInternet,
                keyChanged: contact.peerKeyChanged == true,
                lastReplyResult: confirmed == .read ? "read" : confirmed == .delivered ? "delivered" : saved?.result ?? "")
            contactBytes += (try? JSONEncoder().encode(row).count) ?? 10_000
            return contactBytes <= 10_000 ? row : nil
        }
        return WatchPhoneState(sosActive: manager.isEmergencyDeclared, bleBroadcasting: manager.isBroadcasting,
            emergencyID: manager.emergencySessionID ?? SOSRecoveryStore.shared.snapshot.session?.id, peerID: verifiedPeer?.id.uuidString, peerName: verifiedPeer?.displayName,
            lastMessage: verifiedPeer?.lastMessage.map { String($0.prefix(180)) } ?? lastSaved?.text,
            updatedAt: Date().timeIntervalSince1970, recoveryRequired: manager.recoveryRequired,
            lastReplyResult: lastSaved?.result, contacts: contacts, contactCount: visibleContacts.count)
    }

    private func publish() {
        guard persistenceReady, WCSession.default.activationState == .activated, WCSession.default.isWatchAppInstalled else { return }
        let current = snapshot()
        var comparable = current
        comparable.updatedAt = 0
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        guard let payload = try? encoder.encode(comparable), payload != lastPublishedPayload,
              let data = try? encoder.encode(current) else { return }
        do {
            try WCSession.default.updateApplicationContext(["watchState": data])
            lastPublishedPayload = payload
        } catch { /* A foreground status request can recover when the watch reconnects. */ }
    }

    private func handle(_ data: Data) async -> Data? {
        guard data.count <= 4096, let command = try? JSONDecoder().decode(WatchCommand.self, from: data) else { return nil }
        await restorePersistence()
        observeState()
        var result = "invalid"
        if command.isValid(at: Date().timeIntervalSince1970) {
            if command.action == .status { result = "accepted" }
            else {
                var ledger = defaults.dictionary(forKey: ledgerKey) as? [String: String] ?? [:]
                if let previous = ledger[command.id] { result = previous }
                else {
                    // Reserve before effects. A crash can leave an uncertain result, but cannot replay SOS.
                    let now = Date().timeIntervalSince1970
                    var expirations = defaults.dictionary(forKey: ledgerKey + ".expiry") as? [String: Double] ?? [:]
                    ledger = ledger.filter { (expirations[$0.key] ?? 0) > now }
                    expirations = expirations.filter { $0.value > now }
                    expirations[command.id] = command.expiresAt
                    defaults.set(expirations, forKey: ledgerKey + ".expiry")
                    ledger[command.id] = "uncertain"
                    defaults.set(ledger, forKey: ledgerKey)
                    result = await execute(command)
                    // Another command may have completed while an internet handoff was suspended.
                    ledger = defaults.dictionary(forKey: ledgerKey) as? [String: String] ?? [:]
                    ledger[command.id] = result
                    defaults.set(ledger, forKey: ledgerKey)
                }
            }
        }
        let receipt = WatchReceipt(id: command.id, result: result, state: snapshot())
        #if DEBUG && targetEnvironment(simulator)
        if WatchE2ETestMode.enabled { WatchE2ETestSupport.shared.record(command, result: result, state: receipt.state) }
        #endif
        publish()
        return try? JSONEncoder().encode(receipt)
    }

    private func execute(_ command: WatchCommand) async -> String {
        let manager = SOSBroadcastManager.shared
        switch command.action {
        case .contactReply:
            #if DEBUG && targetEnvironment(simulator)
            if WatchE2ETestMode.enabled {
                guard WatchE2ETestMode.contactsEnabled else { return "noPeer" }
                return await WatchContactMessaging.send(command, lookup: { WatchE2ETestSupport.contact($0) }, relay: { contact, id, _, _ in
                    WatchE2ETestSupport.shared.recordContactEffect(id: id, peer: contact.id.uuidString)
                    return command.reply != .needHelp
                })
            }
            #endif
            return await WatchContactMessaging.send(command)
        case .status: return "accepted"
        case .startSOS:
            if manager.recoveryRequired {
                guard command.emergencyID == SOSRecoveryStore.shared.snapshot.session?.id else { return "stateChanged" }
                return "phoneConfirmation"
            }
            if !manager.isEmergencyDeclared && command.emergencyID != nil { return "stateChanged" }
            #if DEBUG && targetEnvironment(simulator)
            if WatchE2ETestMode.enabled {
                manager.start(declaringEmergency: true)
                return manager.isEmergencyDeclared ? "accepted" : "failed"
            }
            #endif
            guard PlatformRuntime.supportsBlePeripheralHosting,
                  CBManager.authorization == .allowedAlways else { return "openPhone" }
            guard manager.bluetoothState != .poweredOff && manager.bluetoothState != .unsupported
                    && manager.bluetoothState != .unauthorized else { return "bluetoothUnavailable" }
            manager.start(declaringEmergency: true)
            return manager.isEmergencyDeclared ? "accepted" : "failed"
        case .stopSOS:
            let current = snapshot()
            guard current.sosActive || current.recoveryRequired == true, current.emergencyID == command.emergencyID else { return "stateChanged" }
            manager.stop()
            return !manager.isEmergencyDeclared && !manager.recoveryRequired ? "accepted" : "uncertain"
        case .quickReply:
            guard let reply = command.reply, let peerID = command.peerID,
                  let sessionID = UUID(uuidString: peerID), snapshot().peerID == peerID else { return "noPeer" }
            let text = Locale.current.language.languageCode?.identifier == "tr" ? reply.turkish : reply.english
            if let previous = SOSRecoveryStore.shared.snapshot.replies.first(where: { $0.id == command.id }) { SOSChatStore.shared.reconcileWatchReplies(); return previous.result }
            #if DEBUG && targetEnvironment(simulator)
            if WatchE2ETestMode.enabled {
                do {
                    try SOSRecoveryStore.shared.reserve(id: command.id, peer: peerID, text: text)
                    let fixtureOutcome = WatchE2ETestMode.chatEnabled ? (reply == .injured ? "uncertain" : reply == .needHelp ? "noPeer" : "accepted") : "accepted"
                    WatchE2ETestSupport.shared.recordEffect(command,result:fixtureOutcome)
                    if fixtureOutcome != "uncertain" { try SOSRecoveryStore.shared.complete(id: command.id, result: fixtureOutcome) }
                    SOSChatStore.shared.reconcileWatchReplies()
                    WatchE2ETestSupport.shared.receive(text)
                    return fixtureOutcome
                } catch { return "failed" }
            }
            #endif
            do { try SOSRecoveryStore.shared.reserve(id: command.id, peer: peerID, text: text) } catch { return "failed" }
            SOSChatStore.shared.reconcileWatchReplies()
            guard manager.sendChatMessage(to: sessionID, text: text, transportMessageId: command.id) else {
                try? SOSRecoveryStore.shared.complete(id: command.id, result: "noPeer")
                SOSChatStore.shared.reconcileWatchReplies()
                return "noPeer"
            }
            do { try SOSRecoveryStore.shared.complete(id: command.id, result: "accepted") } catch { return "uncertain" }
            SOSChatStore.shared.reconcileWatchReplies()
            return "accepted"
        }
    }

    nonisolated func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {
        Task { @MainActor in
            self.recordTestConnectivity("activation", error: error)
            if activationState == .activated, session.isWatchAppInstalled { self.observeState() }
        }
    }
    nonisolated func sessionWatchStateDidChange(_ session: WCSession) {
        Task { @MainActor in
            self.recordTestConnectivity("watchStateChanged")
            if session.isWatchAppInstalled { self.observeState(); self.publish() }
        }
    }
    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        Task { @MainActor in self.recordTestConnectivity("reachabilityChanged") }
    }
    nonisolated func session(_ session: WCSession, didReceiveMessage message: [String: Any], replyHandler: @escaping ([String: Any]) -> Void) {
        guard let data = message["watchCommand"] as? Data else { replyHandler([:]); return }
        Task { @MainActor in
            let receipt=await self.handle(data)
            #if DEBUG && targetEnvironment(simulator)
            let drop=FileManager.default.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("watch-chat-drop-receipt")
            if WatchE2ETestMode.chatLifecycleEnabled,
               (try? JSONDecoder().decode(WatchCommand.self,from:data))?.action == .quickReply,
               FileManager.default.fileExists(atPath:drop.path) {
                try? FileManager.default.removeItem(at:drop)
                replyHandler([:]);return // Deliberately lose the valid receipt after durable handling.
            }
            #endif
            replyHandler(receipt.map {["watchReceipt":$0]} ?? [:])
        }
    }
    nonisolated func sessionDidBecomeInactive(_ session: WCSession) {}
    nonisolated func sessionDidDeactivate(_ session: WCSession) { session.activate() }
}
