import Foundation
import Security
import CryptoKit

func acceptsMusterReceipt(status: Int, data: Data, session: String, event: MusterEvent? = nil) -> Bool {
    struct Receipt: Decodable { let ok: Bool; let id: String; let eventId: String }
    guard (200..<300).contains(status), let receipt = try? JSONDecoder().decode(Receipt.self, from: data) else { return false }
    guard receipt.ok && receipt.id == session && receipt.eventId.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil else { return false }
    if let event {
        guard let identity = try? JSONSerialization.data(withJSONObject: [event.uid, event.operationId], options: [.withoutEscapingSlashes]) else { return false }
        return receipt.eventId == String(MusterVoiceFiles.digest(identity).prefix(32))
    }
    return true
}

struct MusterSession: Codable, Identifiable, Sendable {
    let id: String
    let kind: String
    let title: String
    let instructions: String
    let status: String
    var deadlineAtMillis: Int64?
    var wakePlaceholder: Bool? = nil
    var responseConfig: MusterResponseConfig? = nil
    var displayTitle: String { wakePlaceholder == true ? musterText("title") : title }
    var displayInstructions: String { wakePlaceholder == true ? musterText("connect") : instructions }
}
struct MusterResponseOption: Codable, Identifiable, Sendable, Equatable {
    let id: String
    let label: String
    let meaning: String
    var displayLabel: String { label.isEmpty ? musterText(meaning) : label }
}
struct MusterResponseConfig: Codable, Sendable, Equatable {
    let version: Int
    let question: String
    let options: [MusterResponseOption]
    let allowText: Bool
    let allowVoice: Bool
    func meaning(optionId: String?, note: String, hasVoice: Bool) throws -> String {
        guard version == 1, options.count <= 8, note.utf16.count <= 2000,
              allowText || note.isEmpty, allowVoice || !hasVoice else { throw MusterError.invalid }
        if let optionId {
            guard let option = options.first(where: { $0.id == optionId }), ["safe", "help", "at_muster", "message"].contains(option.meaning) else { throw MusterError.invalid }
            return option.meaning
        }
        guard !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || hasVoice else { throw MusterError.invalid }
        return "message"
    }
}
struct MusterVoiceMetadata: Codable, Sendable, Equatable {
    let eventId: String
    let mimeType: String
    let durationMillis: Int64
    let bytes: Int
    let sha256: String
}
struct MusterOwnResponse: Codable, Sendable {
    var answer: String?; var helpOpen: Bool?; var late: Bool?; var answeredAtMillis: Int64?
    var optionId: String? = nil; var note: String? = nil; var voice: MusterVoiceMetadata? = nil; var helpVoice: MusterVoiceMetadata? = nil
}
struct MusterAssignment: Codable, Sendable { let session: MusterSession; var response: MusterOwnResponse? }
struct CachedMusterAssignment: Codable, Identifiable, Sendable {
    let panelId: String
    let assignment: MusterAssignment
    var id: String { panelId + ":" + assignment.session.id }
}
struct MusterEvent: Codable, Identifiable, Sendable, Equatable {
    let uid: String
    let panelId: String
    let sessionId: String
    let operationId: String
    let answer: String
    let note: String
    let answeredAtMillis: Int64
    var state = "pending"
    var attempts = 0
    var nextAt: TimeInterval = 0
    var error = ""
    var schemaVersion: Int? = nil
    var optionId: String? = nil
    var voice: MusterLocalVoice? = nil
    var id: String { operationId }
    var serverEventId: String {
        guard let identity = try? JSONSerialization.data(withJSONObject: [uid, operationId], options: [.withoutEscapingSlashes]) else { return "" }
        return String(MusterVoiceFiles.digest(identity).prefix(32))
    }
    func body(voiceData: Data? = nil) throws -> Data {
        var value: [String: Any] = ["schemaVersion": schemaVersion ?? 1, "panelId": panelId, "sessionId": sessionId,
            "operationId": operationId, "answer": answer, "note": note, "answeredAtMillis": answeredAtMillis]
        if let optionId { value["optionId"] = optionId }
        if let voice {
            guard let voiceData, voiceData.count == voice.bytes, MusterVoiceFiles.digest(voiceData) == voice.sha256 else { throw MusterError.persistence }
            value["voice"] = ["mimeType": "audio/mp4", "durationMillis": voice.durationMillis, "data": voiceData.base64EncodedString()]
        }
        // Recreated/offline events produce the same upload bytes, including nested audio metadata.
        return try JSONSerialization.data(withJSONObject: value, options: [.sortedKeys, .withoutEscapingSlashes])
    }
}
protocol MusterPersisting: Sendable { func load() throws -> Data?; func save(_ data: Data) throws }
enum MusterError: Error { case invalid, capacity, persistence, rejected, accountChanged }
final class MusterKeychainStore: MusterPersisting, @unchecked Sendable {
    private let service: String
    init(service: String = "com.auralis.crisisconnect.personnel-muster-v1") {
        self.service = service
    }
    func load() throws -> Data? {
        var result: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: "accounts", kSecReturnData: true, kSecMatchLimit: kSecMatchLimitOne] as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw MusterError.persistence }
        return data
    }
    func save(_ data: Data) throws {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: "accounts"]
        let updated = SecItemUpdate(query as CFDictionary, [kSecValueData: data] as CFDictionary)
        if updated == errSecSuccess { return }
        guard updated == errSecItemNotFound else { throw MusterError.persistence }
        var item = query; item[kSecValueData] = data; item[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(item as CFDictionary, nil) == errSecSuccess else { throw MusterError.persistence }
    }
}

/** All accounts are retained separately; switching identities never relabels or deletes pending answers. */
actor MusterQueue {
    static let shared = MusterQueue(storage: MusterKeychainStore())
    private struct Account: Codable { var events: [MusterEvent] = []; var assignments: [CachedMusterAssignment] = [] }
    private let storage: any MusterPersisting
    private let voiceFiles: any MusterVoicePersisting
    init(storage: any MusterPersisting, voiceFiles: any MusterVoicePersisting = MusterVoiceFiles()) {
        self.storage = storage; self.voiceFiles = voiceFiles
    }
    private func accounts() throws -> [String: Account] {
        guard let data = try storage.load() else { return [:] }
        return try JSONDecoder().decode([String: Account].self, from: data)
    }
    func events(uid: String) throws -> [MusterEvent] { try accounts()[uid]?.events ?? [] }
    func assignments(uid: String) throws -> [CachedMusterAssignment] { try accounts()[uid]?.assignments ?? [] }
    private func update(uid: String, _ mutate: (inout Account) throws -> Void) throws {
        var all = try accounts(); var account = all[uid] ?? Account()
        try mutate(&account); all[uid] = account
        try storage.save(JSONEncoder().encode(all))
    }
    func cache(uid: String, panel: String, received: [MusterAssignment]) throws {
        try update(uid: uid) { account in
            for item in received {
                let cached = CachedMusterAssignment(panelId: panel, assignment: item)
                account.assignments.removeAll { $0.id == cached.id }; account.assignments.append(cached)
            }
            account.assignments = Array(account.assignments.suffix(100))
        }
    }
    func cacheWake(uid: String, panel: String, session: String, kind: String = "live") throws {
        guard ["live", "drill"].contains(kind) else { throw MusterError.invalid }
        if try assignments(uid: uid).contains(where: { $0.panelId == panel && $0.assignment.session.id == session }) { return }
        try cache(uid: uid, panel: panel, received: [MusterAssignment(session: MusterSession(id: session, kind: kind, title: musterText("title"), instructions: musterText("connect"), status: "active", wakePlaceholder: true))])
    }
    @discardableResult func enqueue(uid: String, panel: String, session: String, answer: String, note: String = "", now: Date = Date(), operationId: String = UUID().uuidString) throws -> MusterEvent {
        guard ["safe", "help", "at_muster"].contains(answer), note.utf16.count <= 500,
              panel.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
              session.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil else { throw MusterError.invalid }
        let event = MusterEvent(uid: uid, panelId: panel, sessionId: session, operationId: operationId, answer: answer, note: note, answeredAtMillis: Int64(now.timeIntervalSince1970 * 1000))
        if let previous = try events(uid: uid).first(where: { $0.id == event.id }) {
            guard previous.panelId == panel, previous.sessionId == session, previous.answer == answer, previous.note == note else { throw MusterError.invalid }
            return previous
        }
        try update(uid: uid) { account in
            account.events.removeAll { $0.state == "sent" && now.timeIntervalSince1970 - Double($0.answeredAtMillis) / 1000 > 7 * 86400 }
            guard account.events.count < 256 else { throw MusterError.capacity }
            account.events.append(event)
        }
        return event
    }
    @discardableResult func enqueueConfigured(uid: String, panel: String, session: String, config: MusterResponseConfig,
        optionId: String?, note: String = "", recording: MusterVoiceRecording? = nil, now: Date = Date(), operationId: String = UUID().uuidString) throws -> MusterEvent {
        let answer = try config.meaning(optionId: optionId, note: note, hasVoice: recording != nil)
        guard panel.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
              session.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil else { throw MusterError.invalid }
        if let previous = try events(uid: uid).first(where: { $0.id == operationId }) {
            guard previous.panelId == panel, previous.sessionId == session, previous.answer == answer,
                  previous.optionId == optionId, previous.note == note, previous.schemaVersion == 2,
                  previous.voice?.sha256 == recording.map({ MusterVoiceFiles.digest($0.data) }),
                  previous.voice?.durationMillis == recording?.durationMillis else { throw MusterError.invalid }
            return previous
        }
        guard try events(uid: uid).filter({ $0.state != "sent" || now.timeIntervalSince1970 - Double($0.answeredAtMillis) / 1000 <= 7 * 86400 }).count < 256 else { throw MusterError.capacity }
        if recording != nil {
            let journal = try accounts()
            let references = journal.mapValues { Set($0.events.compactMap { $0.voice?.fileId }) }
            try voiceFiles.pruneOrphans(references: references)
        }
        let voice = try recording.map { try voiceFiles.save(uid: uid, recording: $0) }
        let event = MusterEvent(uid: uid, panelId: panel, sessionId: session, operationId: operationId, answer: answer, note: note,
            answeredAtMillis: Int64(now.timeIntervalSince1970 * 1000), schemaVersion: 2, optionId: optionId, voice: voice)
        do {
            try update(uid: uid) { account in
                account.events.removeAll { $0.state == "sent" && now.timeIntervalSince1970 - Double($0.answeredAtMillis) / 1000 > 7 * 86400 }
                guard account.events.count < 256 else { throw MusterError.capacity }
                account.events.append(event)
            }
        } catch { if let voice { try? voiceFiles.remove(uid: uid, voice: voice) }; throw error }
        return event
    }
    func body(_ event: MusterEvent) throws -> Data {
        try event.body(voiceData: event.voice.map { try voiceFiles.load(uid: event.uid, voice: $0) })
    }
    func voiceData(_ event: MusterEvent) throws -> Data? {
        try event.voice.map { try voiceFiles.load(uid: event.uid, voice: $0) }
    }
    /// Only an authenticated, typed receipt matching this event may release its durable attachment.
    func acknowledge(_ event: MusterEvent, status: Int, receipt: Data) throws {
        guard acceptsMusterReceipt(status: status, data: receipt, session: event.sessionId, event: event) else { throw MusterError.rejected }
        try finish(event, state: "sent")
        if let voice = event.voice { try voiceFiles.remove(uid: event.uid, voice: voice) }
    }
    func releaseConnectionBackoff(uid: String, now: Date = Date()) throws {
        var all = try accounts()
        guard var account = all[uid] else { return }
        let retryAt = now.timeIntervalSince1970
        let delayed = account.events.indices.filter { index in
            let event = account.events[index]
            return event.state == "pending" && event.error == "connection" && event.nextAt > retryAt
        }
        guard !delayed.isEmpty else { return }
        for index in delayed { account.events[index].nextAt = retryAt }
        all[uid] = account
        try storage.save(JSONEncoder().encode(all))
    }
    func claim(uid: String, now: Date = Date()) throws -> MusterEvent? {
        var claimed: MusterEvent?
        try update(uid: uid) { account in
            guard let index = account.events.firstIndex(where: { $0.state == "pending" && $0.nextAt <= now.timeIntervalSince1970 }) else { return }
            account.events[index].attempts += 1
            account.events[index].nextAt = now.timeIntervalSince1970 + 60
            // A leased attempt has no completed error that a reconnect can release.
            account.events[index].error = ""
            claimed = account.events[index]
        }
        return claimed
    }
    func finish(_ event: MusterEvent, state: String, error: String = "", now: Date = Date()) throws {
        try update(uid: event.uid) { account in
            guard let index = account.events.firstIndex(where: { $0.id == event.id }) else { throw MusterError.invalid }
            account.events[index].state = state; account.events[index].error = error
            account.events[index].nextAt = now.timeIntervalSince1970 + min(900, 30 * pow(2, Double(min(event.attempts, 5))))
        }
    }
}
func musterText(_ key: String) -> String {
    MusterLocalization.text(key)
}
