import Foundation

struct IndependentWatchOperation: Codable, Equatable {
    let id: String
    let action: String
    let subjectID: String
    let payload: String
    var state: String = "uncertain"
}
protocol IndependentWatchJournalStorage {
    func read() throws -> [IndependentWatchOperation]
    func write(_ rows: [IndependentWatchOperation]) throws
}
/// Loading or observing this journal never submits a message or starts SOS.
@MainActor final class IndependentWatchJournal {
    private var poisoned = false
    private let storage: IndependentWatchJournalStorage
    private(set) var entries: [IndependentWatchOperation]
    init(storage: IndependentWatchJournalStorage) throws {
        self.storage = storage
        let rows = try storage.read()
        guard rows.count <= 512, Set(rows.map(\.id)).count == rows.count,
            rows.allSatisfy({ UUID(uuidString: $0.id)?.uuidString.lowercased() == $0.id &&
                Self.actions.contains($0.action) && Self.states.contains($0.state) &&
                ($0.action == "shortMessage" || !["delivered", "read"].contains($0.state)) &&
                !$0.subjectID.isEmpty && $0.subjectID.count <= 128 && $0.payload.utf8.count <= 24_576 }) else { throw IndependentWatchError.invalid }
        entries = rows
    }
    func reload() throws {
        guard !poisoned else { throw IndependentWatchError.uncertain }
        entries = try Self(storage: storage).entries
    }
    func reserve(action: String, subjectID: String, payload: String) throws -> IndependentWatchOperation {
        try reservePrepared(action: action, subjectID: subjectID) { _ in payload }
    }
    func reservePrepared(action: String, subjectID: String, prepare: (String) throws -> String) throws -> IndependentWatchOperation {
        let id = UUID().uuidString.lowercased()
        try reload()
        guard Self.actions.contains(action), !subjectID.isEmpty, subjectID.count <= 128,
            entries.count < 512 else { throw IndependentWatchError.invalid }
        guard !entries.contains(where: { $0.action == action && $0.subjectID == subjectID && $0.state == "uncertain" }) else { throw IndependentWatchError.uncertain }
        if action == "startSOS" {
            guard !entries.contains(where: { start in start.action == "startSOS" && (start.subjectID == subjectID ||
                (start.state != "rejected" && !entries.contains(where: { $0.action == "stopSOS" && $0.subjectID == start.subjectID && $0.state == "accepted" }))) }) else { throw IndependentWatchError.uncertain }
        }
        if action == "stopSOS" {
            guard entries.contains(where: { $0.action == "startSOS" && $0.subjectID == subjectID && $0.state != "rejected" }),
                !entries.contains(where: { $0.action == "stopSOS" && $0.subjectID == subjectID && $0.state == "accepted" }) else { throw IndependentWatchError.invalid }
        }
        let payload = try prepare(id)
        guard payload.utf8.count <= 24_576 else { throw IndependentWatchError.invalid }
        let row = IndependentWatchOperation(id: id, action: action, subjectID: subjectID, payload: payload)
        let next = entries + [row]
        try persist(next)
        entries = next
        return row
    }
    func reconcile(id: String, state: String) throws {
        try reload()
        guard let index = entries.firstIndex(where: { $0.id == id }), Self.states.contains(state), state != "uncertain",
            entries[index].action == "shortMessage" || ["accepted", "rejected"].contains(state) else { throw IndependentWatchError.invalid }
        let old = entries[index].state
        let rank = ["uncertain": 0, "accepted": 1, "delivered": 2, "read": 3]
        if old == "rejected" || (state == "rejected" && old != "uncertain") || (rank[state] ?? 0) < (rank[old] ?? 0) { return }
        var next = entries
        next[index].state = state
        try persist(next)
        entries = next
    }
    private func persist(_ next: [IndependentWatchOperation]) throws {
        do { try storage.write(next) } catch { poisoned = true; throw error }
    }
    private static let actions: Set<String> = ["shortMessage", "startSOS", "stopSOS"]
    private static let states: Set<String> = ["uncertain", "accepted", "rejected", "delivered", "read"]
}
