//
//  CrisisSentinelConversationStore.swift
//  Crisis Connect
//

import Foundation

enum CrisisSentinelChatRole: String, Codable, Equatable {
    case user
    case assistant
}

struct CrisisSentinelChatMessage: Identifiable, Codable, Equatable {
    let id: String
    let role: CrisisSentinelChatRole
    let text: String
    let timestamp: Date
    let source: CrisisSentinelResponseSource?
    let confidence: Float?
    var generationDurationMillis: Int64? = nil
    // Online-engine extras (S3/S4): the tool card + map points + provider model that came back
    // with a cloud reply. Optional with defaults so older persisted messages decode unchanged.
    var modelName: String? = nil
    var cardJson: String? = nil
    var mapPoints: [CrisisSentinelMapPoint] = []
}

/// Cloud conversations carry a `cloud:` id prefix so the view model can route list/open/send/
/// rename/delete to Firestore vs the local store — iOS parity with Android's CrisisSentinelConversationIds.
enum CrisisSentinelConversationIds {
    private static let cloudPrefix = "cloud:"

    static func cloud(_ docId: String) -> String { cloudPrefix + docId }
    static func isCloud(_ id: String) -> Bool { id.hasPrefix(cloudPrefix) }
    static func cloudDocId(_ id: String) -> String { String(id.dropFirst(cloudPrefix.count)) }
}

struct CrisisSentinelConversation: Identifiable, Codable, Equatable {
    let id: String
    var title: String
    var mode: CrisisSentinelUserMode
    let createdAt: Date
    var updatedAt: Date
    var messages: [CrisisSentinelChatMessage]
    var draftText: String?
}

struct CrisisSentinelConversationSummary: Identifiable, Equatable {
    let id: String
    let title: String
    let mode: CrisisSentinelUserMode
    let updatedAt: Date
    let lastMessagePreview: String?
    let isDraft: Bool
    /// True when this conversation lives in the shared cloud (Firestore) rather than on-device.
    var isCloud: Bool = false
}

final class CrisisSentinelConversationStore {
    private let defaults: UserDefaults
    private let conversationsKey = "crisis_sentinel_conversations"
    private let defaultModeKey = "crisis_sentinel_default_mode"
    private let titleLimit = 48

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func summaries() -> [CrisisSentinelConversationSummary] {
        loadAll()
            .filter { $0.isVisibleInList }
            .sorted { $0.updatedAt > $1.updatedAt }
            .map { conversation in
                let cleanDraft = conversation.cleanDraftText
                let isDraft = conversation.messages.isEmpty && cleanDraft != nil
                return CrisisSentinelConversationSummary(
                    id: conversation.id,
                    title: conversation.displayTitle(titleLimit: titleLimit),
                    mode: conversation.mode,
                    updatedAt: conversation.updatedAt,
                    lastMessagePreview: conversation.messages.last?.text ?? cleanDraft,
                    isDraft: isDraft
                )
            }
    }

    func load(id: String) -> CrisisSentinelConversation? {
        loadAll().first { $0.id == id }
    }

    func create(mode: CrisisSentinelUserMode? = nil) -> CrisisSentinelConversation {
        let now = Date()
        let conversation = CrisisSentinelConversation(
            id: UUID().uuidString,
            title: "",
            mode: mode ?? defaultMode(),
            createdAt: now,
            updatedAt: now,
            messages: [],
            draftText: nil
        )
        saveAll(loadAll() + [conversation])
        return conversation
    }

    func saveDraft(id: String?, text: String, mode: CrisisSentinelUserMode? = nil) -> CrisisSentinelConversation? {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let id, load(id: id) != nil {
            guard clean.isEmpty == false else {
                return clearDraft(id: id)
            }
            return mutate(id: id) { conversation in
                let now = Date()
                conversation.draftText = clean
                if conversation.messages.isEmpty {
                    conversation.title = title(from: clean)
                }
                conversation.updatedAt = now
            }
        }

        guard clean.isEmpty == false else { return nil }
        let now = Date()
        let conversation = CrisisSentinelConversation(
            id: UUID().uuidString,
            title: title(from: clean),
            mode: mode ?? defaultMode(),
            createdAt: now,
            updatedAt: now,
            messages: [],
            draftText: clean
        )
        saveAll(loadAll() + [conversation])
        return conversation
    }

    func clearDraft(id: String) -> CrisisSentinelConversation? {
        var conversations = loadAll()
        guard let index = conversations.firstIndex(where: { $0.id == id }) else { return nil }
        if conversations[index].messages.isEmpty {
            conversations.remove(at: index)
            saveAll(conversations)
            return nil
        }
        conversations[index].draftText = nil
        conversations[index].updatedAt = Date()
        let updated = conversations[index]
        saveAll(conversations)
        return updated
    }

    func appendUserMessage(id: String, text: String) -> CrisisSentinelConversation? {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.isEmpty == false else { return load(id: id) }
        return mutate(id: id) { conversation in
            let now = Date()
            let message = CrisisSentinelChatMessage(
                id: UUID().uuidString,
                role: .user,
                text: clean,
                timestamp: now,
                source: nil,
                confidence: nil
            )
            conversation.messages.append(message)
            if conversation.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                conversation.title = title(from: clean)
            }
            conversation.draftText = nil
            conversation.updatedAt = now
        }
    }

    func appendAssistantResponse(
        id: String,
        response: CrisisSentinelResponse,
        generationDurationMillis: Int64? = nil
    ) -> CrisisSentinelConversation? {
        mutate(id: id) { conversation in
            let now = Date()
            let message = CrisisSentinelChatMessage(
                id: UUID().uuidString,
                role: .assistant,
                text: response.answer,
                timestamp: now,
                source: response.source,
                confidence: response.confidence,
                generationDurationMillis: generationDurationMillis
            )
            conversation.messages.append(message)
            conversation.updatedAt = now
        }
    }

    /// Drops trailing assistant message(s) so the last user prompt can be regenerated in place.
    func removeTrailingAssistantMessages(id: String) -> CrisisSentinelConversation? {
        mutate(id: id) { conversation in
            while conversation.messages.last?.role == .assistant {
                conversation.messages.removeLast()
            }
            conversation.updatedAt = Date()
        }
    }

    func defaultMode() -> CrisisSentinelUserMode {
        guard let rawValue = defaults.string(forKey: defaultModeKey),
              let mode = CrisisSentinelUserMode(rawValue: rawValue) else {
            return .publicUser
        }
        return mode
    }

    func saveDefaultMode(_ mode: CrisisSentinelUserMode) {
        defaults.set(mode.rawValue, forKey: defaultModeKey)
    }

    @discardableResult
    func deleteConversation(id: String) -> Bool {
        var conversations = loadAll()
        guard let index = conversations.firstIndex(where: { $0.id == id }) else { return false }
        conversations.remove(at: index)
        saveAll(conversations)
        return true
    }

    private func mutate(
        id: String,
        transform: (inout CrisisSentinelConversation) -> Void
    ) -> CrisisSentinelConversation? {
        var conversations = loadAll()
        guard let index = conversations.firstIndex(where: { $0.id == id }) else { return nil }
        transform(&conversations[index])
        let updated = conversations[index]
        saveAll(conversations)
        return updated
    }

    private func loadAll() -> [CrisisSentinelConversation] {
        guard let data = defaults.data(forKey: conversationsKey) else { return [] }
        return (try? JSONDecoder().decode([CrisisSentinelConversation].self, from: data)) ?? []
    }

    private func saveAll(_ conversations: [CrisisSentinelConversation]) {
        guard let data = try? JSONEncoder().encode(conversations) else { return }
        defaults.set(data, forKey: conversationsKey)
    }

    private func title(from prompt: String) -> String {
        let firstLine = prompt
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { $0.isEmpty == false } ?? ""
        guard firstLine.count > titleLimit else { return firstLine }
        return "\(String(firstLine.prefix(titleLimit - 1)).trimmingCharacters(in: .whitespacesAndNewlines))..."
    }
}

private extension CrisisSentinelConversation {
    var cleanDraftText: String? {
        guard let draft = draftText?.trimmingCharacters(in: .whitespacesAndNewlines),
              draft.isEmpty == false else {
            return nil
        }
        return draft
    }

    var isVisibleInList: Bool {
        messages.isEmpty == false || cleanDraftText != nil
    }

    func displayTitle(titleLimit: Int) -> String {
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard cleanTitle.isEmpty else { return cleanTitle }
        guard let draft = cleanDraftText else { return "" }
        let firstLine = draft
            .split(separator: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { $0.isEmpty == false } ?? ""
        guard firstLine.count > titleLimit else { return firstLine }
        return "\(String(firstLine.prefix(titleLimit - 1)).trimmingCharacters(in: .whitespacesAndNewlines))..."
    }
}
