import Foundation
import CryptoKit

struct IndependentWatchJournalFile: IndependentWatchJournalStorage {
    let url: URL
    private let service: String
    init(url: URL, service: String = "crisis.watch.journal.v1") { self.url = url; self.service = service }
    func read() throws -> [IndependentWatchOperation] {
        guard let clear = try readData() else { return [] }
        return try JSONDecoder().decode([IndependentWatchOperation].self, from: clear)
    }
    func readData() throws -> Data? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard ((attributes[.size] as? NSNumber)?.intValue ?? Int.max) <= 16_000_000 else { throw IndependentWatchError.invalid }
        let sealed = try AES.GCM.SealedBox(combined: Data(contentsOf: url))
        return try AES.GCM.open(sealed, using: key(create: false), authenticating: Data(service.utf8))
    }
    func write(_ rows: [IndependentWatchOperation]) throws {
        try writeData(JSONEncoder().encode(rows))
    }
    func writeData(_ clear: Data) throws {
        guard clear.count <= 15_000_000 else { throw IndependentWatchError.invalid }
        let sealed = try AES.GCM.seal(clear, using: key(create: !FileManager.default.fileExists(atPath: url.path)), authenticating: Data(service.utf8))
        guard let data = sealed.combined else { throw IndependentWatchError.invalid }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: url, options: .atomic)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }
        try handle.synchronize()
        var excluded = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try excluded.setResourceValues(values)
    }
    private func key(create: Bool) throws -> SymmetricKey {
        if let bytes = try IndependentWatchKeychain.read(service) { return SymmetricKey(data: bytes) }
        guard create else { throw IndependentWatchError.missingKey }
        let key = SymmetricKey(size: .bits256)
        try IndependentWatchKeychain.insert(key.withUnsafeBytes { Data($0) }, service: service)
        return key
    }
}
