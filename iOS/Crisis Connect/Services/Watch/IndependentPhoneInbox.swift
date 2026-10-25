import Foundation
import FirebaseAuth
import FirebaseFunctions
import WatchConnectivity

/// Separate, explicitly approved Signal receiver feeding the ordinary chat store.
/// Does not change device-1 identity/session records or send anything while restoring history.
@MainActor final class IndependentPhoneInbox {
    typealias Call = (String, [String: Any]) async throws -> [String: Any]
    let ownerUid: String
    let deviceID: String
    let service: IndependentWatchService
    private let accountAvailable: () -> Bool
    private let call: Call
    private let stateFile: IndependentWatchJournalFile
    private var state: [String: String]

    init(ownerUid: String, endpoint: URL = URL(string: "https://europe-west1-crisis-connect-1.cloudfunctions.net/independentWatchGateway")!, call: Call? = nil, accountAvailable: (() -> Bool)? = nil) throws {
        guard !ownerUid.isEmpty, ownerUid.count <= 128, ownerUid.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) }) else { throw IndependentWatchError.invalid }
        self.ownerUid = ownerUid
        self.accountAvailable = accountAvailable ?? { Auth.auth().currentUser.map { $0.uid == ownerUid && !$0.isAnonymous } == true }
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
        stateFile = IndependentWatchJournalFile(url: directory.appendingPathComponent("independent-phone-\(ownerUid).enc"), service: "crisis.phone.independent.\(ownerUid)")
        state = try stateFile.readData().map { try JSONDecoder().decode([String: String].self, from: $0) } ?? [:]
        deviceID = state["deviceID"] ?? UUID().uuidString.lowercased()
        state["deviceID"] = deviceID
        try stateFile.writeData(JSONEncoder().encode(state))
        service = try IndependentWatchService(deviceID: deviceID, endpoint: endpoint)
        self.call = call ?? { name, data in
            guard let user = Auth.auth().currentUser, user.uid == ownerUid, !user.isAnonymous else { throw IndependentWatchError.invalid }
            let result = try await Functions.functions(region: "europe-west1").httpsCallable(name).call(data)
            guard let object = result.data as? [String: Any] else { throw IndependentWatchError.invalid }
            return object
        }
    }
    private func requireAccount() throws { guard accountAvailable() else { throw IndependentWatchError.invalid } }
    private func reloadState() throws { state = try stateFile.readData().map { try JSONDecoder().decode([String: String].self, from: $0) } ?? [:] }
    var isEnabled: Bool { (try? reloadState()) != nil && state["enabled"] == "true" }
    private func setState(_ key: String, _ value: String?) throws {
        try reloadState(); state[key] = value
        try stateFile.writeData(JSONEncoder().encode(state))
    }
    /// Invoked only by the explicit consent button.
    func enable() async throws {
        try requireAccount()
        var enrollment: [String: Any] = try service.enrollment(ownerUid: ownerUid)
        enrollment["confirmed"] = true; enrollment["kind"] = "phoneInbox"
        let enrolled = try await call("enrollIndependentWatch", enrollment)
        guard let index = (enrolled["signalDeviceId"] as? NSNumber)?.uint32Value else { throw IndependentWatchError.invalid }
        var proof = try service.signalApproval(ownerUid: ownerUid, deviceIndex: index); proof["confirmed"] = true
        _ = try await call("configureIndependentWatchSignal", proof)
        try setState("enabled", "true")
    }
    func devices(contact: ContactRecord) async throws -> [[String: Any]] {
        try requireAccount()
        guard WatchContactMessaging.eligible(contact), let uid = contact.peerUid else { throw IndependentWatchError.invalid }
        return try await call("listIndependentContactDevices", ["peerUid": uid])["devices"] as? [[String: Any]] ?? []
    }
    func approve(contact: ContactRecord, descriptor: [String: Any]) async throws {
        try requireAccount(); try reloadState()
        guard let latest = ContactStore.shared.contact(for: contact.id), WatchContactMessaging.eligible(latest),
              let uid = latest.peerUid, descriptor["ownerUid"] as? String == uid,
              let peerID = descriptor["deviceID"] as? String, let bundle = descriptor["bundle"] as? [String: Any],
              let device = (bundle["deviceId"] as? NSNumber)?.uint32Value, let identity = bundle["identityKey"] as? String else { throw IndependentWatchError.invalid }
        try service.signal.pinPeer(uid: uid, device: device, identity: identity)
        for own in [deviceID, state["watchID"]].compactMap({ $0 }) {
            _ = try await call("approveIndependentWatchContact", ["confirmed": true, "deviceID": own, "peerInstallation": peerID, "peerUid": uid, "identityKey": identity, "name": latest.name])
        }
        try setState("peer:\(peerID)", String(data: try JSONSerialization.data(withJSONObject: descriptor, options: [.sortedKeys]), encoding: .utf8))
    }
    func revokeContact(_ descriptor: [String: Any]) async throws {
        try requireAccount(); try reloadState()
        guard let peer = descriptor["deviceID"] as? String else { throw IndependentWatchError.invalid }
        for own in [deviceID, state["watchID"]].compactMap({ $0 }) {
            _ = try await call("revokeIndependentWatchContact", ["deviceID": own, "peerInstallation": peer])
        }
        try setState("peer:\(peer)", nil)
    }
    private func watchRequest(_ request: [String: Any]) async throws -> [String: Any] {
        try requireAccount()
        guard WCSession.isSupported(), WCSession.default.isReachable else { throw IndependentWatchError.invalid }
        var payload = request; payload["ownerUid"] = ownerUid
        return try await withCheckedThrowingContinuation { continuation in
            WCSession.default.sendMessage(["independentSetup": payload], replyHandler: { response in
                if let result = response["result"] as? [String: Any] { continuation.resume(returning: result) }
                else { continuation.resume(throwing: IndependentWatchError.invalid) }
            }, errorHandler: { continuation.resume(throwing: $0) })
        }
    }
    func pairWatch() async throws {
        var enrollment = try await watchRequest(["stage": "enroll"]); enrollment["confirmed"] = true
        guard let id = enrollment["deviceID"] as? String else { throw IndependentWatchError.invalid }
        let enrolled = try await call("enrollIndependentWatch", enrollment)
        guard let index = enrolled["signalDeviceId"] as? NSNumber else { throw IndependentWatchError.invalid }
        var proof = try await watchRequest(["stage": "bind", "deviceID": id, "deviceIndex": index]); proof["confirmed"] = true
        _ = try await call("configureIndependentWatchSignal", proof)
        _ = try await watchRequest(["stage": "complete", "deviceID": id])
        try setState("watchID", id)
    }
    func syncOwnWatchHistory() async throws {
        try requireAccount(); try reloadState()
        guard let watch = state["watchID"] else { return }
        var messages: [[String: Any]] = [], offset = 0
        repeat {
            let response = try await watchRequest(["stage": "history", "offset": offset])
            guard response["ownerUid"] as? String == ownerUid, response["deviceID"] as? String == watch,
                  let batch = response["messages"] as? [[String: Any]], batch.count <= 16 else { throw IndependentWatchError.invalid }
            messages.append(contentsOf: batch); offset += batch.count
            if batch.count < 16 || offset >= 512 { break }
        } while true
        var receipts: [String: String] = [:]
        let ids = messages.compactMap { $0["id"] as? String }
        for offset in stride(from: 0, to: ids.count, by: 32) {
            let result = try await call("readIndependentWatchReceipts", ["deviceID": watch, "ids": Array(ids.dropFirst(offset).prefix(32))])
            for row in result["receipts"] as? [[String: Any]] ?? [] {
                if let id = row["id"] as? String, let outcome = row["outcome"] as? String { receipts[id] = outcome }
            }
        }
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        for row in messages {
            try requireAccount()
            guard let id = row["id"] as? String, let uid = row["recipientUid"] as? String,
                  let contact = ContactStore.shared.contacts.first(where: { $0.peerUid == uid }), WatchContactMessaging.eligible(contact),
                  let encoded = row["clear"] as? String, let clear = Data(base64Encoded: encoded),
                  let content = try JSONSerialization.jsonObject(with: clear) as? [String: Any], content["version"] as? Int == 1,
                  content["kind"] as? String == "watchText", let text = content["text"] as? String, text.utf8.count <= 1500 else { continue }
            try SOSChatStore.shared.reconcileIndependentWatchSent(contact: contact, id: id, text: text, outcome: receipts[id] ?? row["state"] as? String ?? "uncertain")
        }
        try await SOSChatStore.shared.flushToDisk()
    }
    func disable() async throws {
        try requireAccount()
        _ = try await call("revokeIndependentWatch", ["deviceID": deviceID])
        try setState("enabled", "false")
    }
    func refresh() async throws {
        try requireAccount()
        guard isEnabled else { return }
        try await restoreChats()
        try? await syncOwnWatchHistory()
        var cursor = state["inboxCursor"]
        for _ in 0..<16 {
            let response = try await service.refresh(inboxAfter: cursor)
            let inbox = response["inbox"] as? [[String: Any]] ?? []
            for message in inbox { try? await receiveApproved(message) }
            cursor = inbox.count < 2 ? nil : inbox.last?["id"] as? String
            try setState("inboxCursor", cursor)
            if cursor == nil { break }
        }
    }
    func receiveApproved(_ message: [String: Any]) async throws {
        try requireAccount(); try reloadState()
        guard let id = message["id"] as? String, let uid = message["senderUid"] as? String,
              let sender = message["senderInstallation"] as? String, let saved = state["peer:\(sender)"],
              let bytes = saved.data(using: .utf8), let descriptor = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
              descriptor["ownerUid"] as? String == uid, let bundle = descriptor["bundle"] as? [String: Any],
              (bundle["deviceId"] as? NSNumber) == (message["senderDevice"] as? NSNumber) else { throw IndependentWatchError.invalid }
        _ = try await service.receiveMessage(message) { clear in try await self.importMessage(id: id, uid: uid, clear: clear) }
    }
    func restoreChats() async throws {
        try requireAccount()
        for message in try service.signal.incoming().values { try? await importMessage(id: message.id, uid: message.uid, clear: message.clear) }
    }
    private func importMessage(id: String, uid: String, clear: Data) async throws {
        try requireAccount()
        await ContactStore.shared.waitUntilLoaded()
        guard let contact = ContactStore.shared.contacts.first(where: { $0.peerUid == uid }), WatchContactMessaging.eligible(contact),
              let content = try JSONSerialization.jsonObject(with: clear) as? [String: Any], content["version"] as? Int == 1,
              content["kind"] as? String == "watchText", let text = content["text"] as? String, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              text.utf8.count <= 1500 else { throw IndependentWatchError.invalid }
        let chats = SOSChatStore.shared
        await chats.waitUntilLoaded()
        chats.ensureSession(id: contact.id, displayName: contact.name, role: .unknown, isVerified: contact.isVerified)
        for session in chats.sessions {
            if let old = chats.messages(for: session.id).first(where: { $0.transportMessageId == id }) {
                guard session.id == contact.id, !old.isLocal, old.text == text else { throw IndependentWatchError.invalid }
            }
        }
        _ = chats.appendRemoteMessage(sessionId: contact.id, text: text, transportMessageId: id)
        try await chats.flushToDisk()
    }
    static func textContent(_ text: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["version": 1, "kind": "watchText", "text": text], options: [.sortedKeys])
    }
}
