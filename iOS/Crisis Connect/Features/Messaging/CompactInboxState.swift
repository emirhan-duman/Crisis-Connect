import Foundation
import CryptoKit

/// Atomic encrypted ACK journal; UID hashing isolates accounts without exposing IDs in filenames.
final class CompactAckFileStore {
    private let directory: URL
    private let lock = NSLock()
    private var cache: [String: [String]] = [:]
    init(directory: URL) { self.directory = directory }
    private func file(_ uid: String) -> URL {
        let name = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x",$0) }.joined()
        return directory.appendingPathComponent(name + ".acks")
    }
    private func load(_ uid: String) throws -> [String] {
        if let ids = cache[uid] { return ids }
        let url = file(uid)
        let ids = FileManager.default.fileExists(atPath: url.path)
            ? try JSONDecoder().decode([String].self, from: LocalEncryptedFileStore.read(from: url).data) : []
        cache[uid] = ids
        return ids
    }
    private func write(_ uid: String, _ ids: [String]) throws {
        try LocalEncryptedFileStore.write(JSONEncoder().encode(ids), to: file(uid))
        var directory = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try directory.setResourceValues(values)
        cache[uid] = ids
    }
    func migrate(uid: String, ids: [String]) throws {
        lock.lock(); defer { lock.unlock() }
        if !FileManager.default.fileExists(atPath: file(uid).path), !ids.isEmpty {
            try write(uid, Array(Set(ids)).sorted().suffix(2_048).map { $0 })
        }
    }
    func enqueue(uid: String, id: String) throws {
        lock.lock(); defer { lock.unlock() }
        var ids = try load(uid)
        guard !ids.contains(id) else { return }
        ids.append(id)
        try write(uid, Array(ids.suffix(2_048)))
    }
    func pending(uid: String) throws -> [String] {
        lock.lock(); defer { lock.unlock() }
        return Array(try load(uid).prefix(100))
    }
    func confirm(uid: String, ids: [String]) throws {
        lock.lock(); defer { lock.unlock() }
        let confirmed = Set(ids)
        try write(uid, load(uid).filter { !confirmed.contains($0) })
    }
}

enum CompactInboxState {
    private static let lock = NSLock()
    private static let store = CompactAckFileStore(directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MessagingInboxAcks",isDirectory:true))
    private static func key(_ uid: String) -> String { "messaging.compactAcks.\(uid)" }
    private static func migrate(_ uid: String) throws {
        if let ids = UserDefaults.standard.stringArray(forKey: key(uid)) {
            try store.migrate(uid: uid, ids: ids)
            UserDefaults.standard.removeObject(forKey: key(uid))
        }
    }
    @discardableResult static func enqueue(uid: String, id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        do { try migrate(uid); try store.enqueue(uid: uid,id: id); return true }
        catch { MessagingDiagLog.log("ACK journal write unavailable"); return false }
    }
    static func pending(uid: String) -> [String] {
        lock.lock(); defer { lock.unlock() }
        do { try migrate(uid); return try store.pending(uid: uid) }
        catch { MessagingDiagLog.log("ACK journal read unavailable"); return [] }
    }
    static func confirm(uid: String, ids: [String]) {
        lock.lock(); defer { lock.unlock() }
        do { try migrate(uid); try store.confirm(uid: uid,ids: ids) }
        catch { MessagingDiagLog.log("ACK journal confirmation deferred") }
    }
    static func nextRevision(uid: String) -> Int64 {
        lock.lock(); defer { lock.unlock() }
        let key = "messaging.compactRevision.\(uid)"
        let revision = max(Int64(Date().timeIntervalSince1970 * 1000), Int64(UserDefaults.standard.double(forKey: key)) + 1)
        UserDefaults.standard.set(revision, forKey: key)
        return revision
    }
}
