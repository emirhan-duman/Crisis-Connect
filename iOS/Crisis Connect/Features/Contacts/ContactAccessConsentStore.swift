import Combine
import FirebaseAuth
import FirebaseCore
import Foundation

/// Versioned, account-scoped consent. The file is excluded from device backups.
@MainActor
final class ContactAccessConsentStore: ObservableObject {
    static let shared = ContactAccessConsentStore()
    static let version = 2
    @Published private(set) var revision = 0
    private var tokens: [String: String]
    private let fileURL: URL
    private let ownerID: () -> String

    init(fileURL: URL? = nil, ownerID: @escaping () -> String = {
        guard FirebaseApp.app() != nil else { return "signed-out" }
        return Auth.auth().currentUser?.uid ?? "signed-out"
    }) {
        self.fileURL = fileURL ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ContactConsent/consents.json")
        self.ownerID = ownerID
        if let data = try? Data(contentsOf: self.fileURL),
           let saved = try? JSONDecoder().decode([String: String].self, from: data) {
            tokens = saved
        } else {
            tokens = [:]
        }
    }

    private func key(_ online: Bool) -> String {
        "v\(Self.version):\(ownerID()):\(online ? "online" : "nearby")"
    }
    func token(online: Bool) -> String? {
        let key = key(online)
        return tokens[key].map { "\(key):\($0)" }
    }
    func isGranted(online: Bool) -> Bool { token(online: online) != nil }
    func isCurrent(online: Bool, token: String) -> Bool { self.token(online: online) == token }

    @discardableResult
    func accept(online: Bool) -> Bool {
        var updated = tokens
        updated[key(online)] = UUID().uuidString
        guard save(updated) else { return false }
        tokens = updated
        revision += 1
        return true
    }

    func revoke(online: Bool) {
        var updated = tokens
        updated.removeValue(forKey: key(online))
        // Fail closed in memory even if persistence is unavailable.
        tokens = updated
        if !save(updated) { try? FileManager.default.removeItem(at: fileURL) }
        revision += 1
    }

    private func save(_ updated: [String: String]) -> Bool {
        do {
            var directory = fileURL.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            try JSONEncoder().encode(updated).write(to: fileURL, options: .atomic)
            var file = fileURL
            try file.setResourceValues(values)
            return true
        } catch {
            return false
        }
    }
}
