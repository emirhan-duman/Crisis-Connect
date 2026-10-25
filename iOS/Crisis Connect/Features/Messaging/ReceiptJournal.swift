import Foundation
import CryptoKit

struct ReceiptBatch: Codable, Equatable {
    let peer: String
    let template: Int
    var ids: [String]
}
enum ReceiptBatches {
    static func enqueue(_ existing: [ReceiptBatch], _ batch: ReceiptBatch) throws -> [ReceiptBatch] {
        guard [204,205].contains(batch.template), !batch.peer.isEmpty,
              batch.ids.allSatisfy({ !$0.isEmpty && $0.utf8.count <= 128 && $0.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "._:~-".contains($0)) }) }) else {
            throw CocoaError(.fileWriteInvalidFileName)
        }
        var result = existing
        if let index = result.firstIndex(where: { $0.peer == batch.peer && $0.template == batch.template }) {
            var seen = Set(result[index].ids)
            result[index].ids += batch.ids.filter { seen.insert($0).inserted }
        } else {
            var seen = Set<String>()
            result.append(ReceiptBatch(peer: batch.peer,template: batch.template,ids: batch.ids.filter { seen.insert($0).inserted }))
        }
        guard result.reduce(0, { $0 + $1.ids.count }) <= 4096 else { throw CocoaError(.fileWriteOutOfSpace) }
        return result
    }
    static func next(_ existing: [ReceiptBatch]) -> ReceiptBatch? {
        guard var batch = existing.first else { return nil }
        batch.ids = Array(batch.ids.prefix(6)); return batch
    }
    static func confirm(_ existing: [ReceiptBatch], _ sent: ReceiptBatch) -> [ReceiptBatch] {
        let confirmed = Set(sent.ids)
        return existing.map { batch in
            var next = batch
            if batch.peer == sent.peer && batch.template == sent.template { next.ids.removeAll { confirmed.contains($0) } }
            return next
        }.filter { !$0.ids.isEmpty }
    }
}

/// Account-isolated encrypted atomic file, retained through failures and application restarts.
final class ReceiptJournal: @unchecked Sendable {
    static let shared = ReceiptJournal(directory: FileManager.default.urls(for: .applicationSupportDirectory,in: .userDomainMask)[0]
        .appendingPathComponent("MessagingReceipts",isDirectory:true))
    private let directory: URL
    private let lock = NSLock()
    init(directory: URL) { self.directory = directory }
    private func file(_ uid: String) -> URL {
        let key = SHA256.hash(data: Data(uid.utf8)).map { String(format:"%02x",$0) }.joined()
        return directory.appendingPathComponent(key + ".receipts")
    }
    private func read(_ uid: String) throws -> [ReceiptBatch] {
        guard FileManager.default.fileExists(atPath:file(uid).path) else { return [] }
        return try JSONDecoder().decode([ReceiptBatch].self,from:LocalEncryptedFileStore.read(from:file(uid)).data)
    }
    private func write(_ uid: String, _ batches: [ReceiptBatch]) throws {
        try LocalEncryptedFileStore.write(JSONEncoder().encode(batches),to:file(uid))
        var folder = directory
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try folder.setResourceValues(values)
    }
    func enqueue(uid: String, batch: ReceiptBatch) throws {
        lock.lock(); defer { lock.unlock() }; try write(uid,ReceiptBatches.enqueue(read(uid),batch))
    }
    func next(uid: String) throws -> ReceiptBatch? {
        lock.lock(); defer { lock.unlock() }; return ReceiptBatches.next(try read(uid))
    }
    func confirm(uid: String, batch: ReceiptBatch) throws {
        lock.lock(); defer { lock.unlock() }; try write(uid,ReceiptBatches.confirm(read(uid),batch))
    }
}
