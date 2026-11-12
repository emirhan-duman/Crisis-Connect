import Foundation
import Combine

struct QuickReplyItem: Identifiable, Hashable {
    var id: String { text }
    let text: String
    let isDefault: Bool
}

@MainActor
final class WatchQuickRepliesStore: ObservableObject {
    static let shared = WatchQuickRepliesStore()
    private let key = "watch_custom_quick_replies"

    @Published var customReplies: [String] = []

    init() {
        loadReplies()
    }

    var defaultReplies: [String] {
        let isTr = Locale.current.language.languageCode?.identifier == "tr"
        return isTr ? ["İyiyim", "Yaralıyım", "Yardım lazım"] : ["I am safe", "I am injured", "I need help"]
    }

    var allReplies: [QuickReplyItem] {
        let defaults = defaultReplies.map { QuickReplyItem(text: $0, isDefault: true) }
        let customs = customReplies.map { QuickReplyItem(text: $0, isDefault: false) }
        return defaults + customs
    }

    var allReplyStrings: [String] {
        defaultReplies + customReplies
    }

    func loadReplies() {
        customReplies = UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    func addReply(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 50 else { return false }
        guard !allReplyStrings.contains(where: { $0.caseInsensitiveCompare(trimmed) == .orderedSame }) else { return false }
        customReplies.append(trimmed)
        UserDefaults.standard.set(customReplies, forKey: key)
        return true
    }

    func removeReply(_ text: String) {
        customReplies.removeAll { $0.caseInsensitiveCompare(text.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame }
        UserDefaults.standard.set(customReplies, forKey: key)
    }

    func resetToDefaults() {
        customReplies.removeAll()
        UserDefaults.standard.removeObject(forKey: key)
    }
}
