import Foundation

// Keep the phone and watch copies identical. The watch target has no phone SDK dependencies.
nonisolated enum WatchAction: String, Codable, Sendable { case status, startSOS, stopSOS, quickReply, contactReply }
nonisolated enum WatchQuickReply: String, Codable, CaseIterable, Sendable {
    case safe, injured, needHelp
    var english: String {
        switch self { case .safe: return "I am safe"; case .injured: return "I am injured"; case .needHelp: return "I need help" }
    }
    var turkish: String {
        switch self { case .safe: return "İyiyim"; case .injured: return "Yaralıyım"; case .needHelp: return "Yardıma ihtiyacım var" }
    }
}
nonisolated struct WatchCommand: Codable, Sendable {
    var version = 1
    let id: String
    let action: WatchAction
    let issuedAt: Double
    let expiresAt: Double
    var emergencyID: String?
    var peerID: String?
    var reply: WatchQuickReply?

    init(action: WatchAction, emergencyID: String? = nil, peerID: String? = nil, reply: WatchQuickReply? = nil, now: Date = .now) {
        id = UUID().uuidString
        self.action = action
        issuedAt = now.timeIntervalSince1970
        expiresAt = issuedAt + 30
        self.emergencyID = emergencyID
        self.peerID = peerID
        self.reply = reply
    }

    func isValid(at now: Double) -> Bool {
        version == 1 && UUID(uuidString: id) != nil && issuedAt.isFinite && expiresAt.isFinite
            && issuedAt <= now + 5 && expiresAt > now && expiresAt > issuedAt && expiresAt - issuedAt <= 30
            && (![WatchAction.quickReply, .contactReply].contains(action) || (reply != nil && UUID(uuidString: peerID ?? "") != nil))
            && (action != .stopSOS || !(emergencyID ?? "").isEmpty)
    }
}
nonisolated struct WatchSavedContact: Codable, Sendable, Identifiable {
    let id: String
    var name: String
    var lastMessage: String = ""
    var canSend: Bool = false
    var internetCapable: Bool = false
    var keyChanged: Bool = false
    var lastReplyResult: String = ""
}
nonisolated struct WatchPhoneState: Codable, Sendable {
    var version = 1
    var sosActive = false
    var bleBroadcasting = false
    var emergencyID: String?
    var peerID: String?
    var peerName: String?
    var lastMessage: String?
    var updatedAt: Double = 0
    var recoveryRequired: Bool?
    var lastReplyResult: String?
    var contacts: [WatchSavedContact]?
    var contactCount: Int?
}
nonisolated struct WatchReceipt: Codable, Sendable {
    let id: String
    // accepted means the phone executed the action / handed a reply to BLE, never peer delivery.
    let result: String
    let state: WatchPhoneState
}
