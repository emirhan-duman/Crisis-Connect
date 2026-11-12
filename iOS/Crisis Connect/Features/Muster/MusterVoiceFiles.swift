import Foundation
import CryptoKit
import Security

struct MusterVoiceRecording: Sendable {
    let data: Data
    let durationMillis: Int64
}
struct MusterLocalVoice: Codable, Sendable, Equatable {
    let fileId: String
    let durationMillis: Int64
    let bytes: Int
    let sha256: String
}
protocol MusterVoicePersisting: Sendable {
    func save(uid: String, recording: MusterVoiceRecording) throws -> MusterLocalVoice
    func load(uid: String, voice: MusterLocalVoice) throws -> Data
    func remove(uid: String, voice: MusterLocalVoice) throws
    func pruneOrphans(references: [String: Set<String>]) throws
}

/// Only encrypted attachments live here. Queue metadata is small enough for the existing Keychain journal.
/// A full budget refuses a new recording; it never removes an undelivered recording.
final class MusterVoiceFiles: MusterVoicePersisting, @unchecked Sendable {
    static let maximumBytes = 128 * 1024
    static let maximumDuration: Int64 = 30_000
    private static let keyLock = NSLock()
    private let root: URL
    private let suppliedKey: Data?
    private let keyService: String
    init(root: URL? = nil, key: Data? = nil, keyService: String = "com.auralis.crisisconnect.muster-voice-v1") {
        self.root = root ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("MusterVoice", isDirectory: true)
        suppliedKey = key
        self.keyService = keyService
    }
    static func digest(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private func key() throws -> SymmetricKey {
        if let suppliedKey { guard suppliedKey.count == 32 else { throw MusterError.persistence }; return SymmetricKey(data: suppliedKey) }
        Self.keyLock.lock(); defer { Self.keyLock.unlock() }
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: keyService, kSecAttrAccount: "encryption-key"]
        var result: CFTypeRef?
        var read = query; read[kSecReturnData] = true; read[kSecMatchLimit] = kSecMatchLimitOne
        let status = SecItemCopyMatching(read as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data, data.count == 32 { return SymmetricKey(data: data) }
        guard status == errSecItemNotFound else { throw MusterError.persistence }
        let key = SymmetricKey(size: .bits256), data = key.withUnsafeBytes { Data($0) }
        var add = query; add[kSecValueData] = data; add[kSecAttrAccessible] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw MusterError.persistence }
        return key
    }
    private func directory(uid: String) -> URL { root.appendingPathComponent(Self.digest(Data(uid.utf8)), isDirectory: true) }
    private func url(uid: String, voice: MusterLocalVoice) throws -> URL {
        guard UUID(uuidString: voice.fileId) != nil else { throw MusterError.invalid }
        return directory(uid: uid).appendingPathComponent(voice.fileId + ".sealed")
    }
    func save(uid: String, recording: MusterVoiceRecording) throws -> MusterLocalVoice {
        guard !uid.isEmpty, !recording.data.isEmpty, recording.data.count <= Self.maximumBytes,
              (1...Self.maximumDuration).contains(recording.durationMillis) else { throw MusterError.invalid }
        let directory = directory(uid: uid), manager = FileManager.default
        try manager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
        var protectedRoot = root; var values = URLResourceValues(); values.isExcludedFromBackup = true; try protectedRoot.setResourceValues(values)
        func size(_ path: URL) throws -> Int {
            guard let files = manager.enumerator(at: path, includingPropertiesForKeys: [.fileSizeKey, .isRegularFileKey]) else { return 0 }
            var total = 0
            for case let file as URL in files {
                let value = try file.resourceValues(forKeys: [.fileSizeKey, .isRegularFileKey])
                if value.isRegularFile == true { total += value.fileSize ?? 0 }
            }
            return total
        }
        let voice = MusterLocalVoice(fileId: UUID().uuidString, durationMillis: recording.durationMillis, bytes: recording.data.count, sha256: Self.digest(recording.data))
        let sealed = try AES.GCM.seal(recording.data, using: key(), authenticating: associatedData(uid: uid, voice: voice)).combined!
        guard try size(directory) + sealed.count <= 8 * 1024 * 1024, try size(root) + sealed.count <= 32 * 1024 * 1024 else { throw MusterError.capacity }
        let destination = try url(uid: uid, voice: voice)
        try sealed.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        return voice
    }
    private func associatedData(uid: String, voice: MusterLocalVoice) throws -> Data {
        try JSONSerialization.data(withJSONObject: [uid, voice.fileId], options: [.withoutEscapingSlashes])
    }
    /// Only unreferenced, old sealed files from an interrupted enqueue are reclaimable.
    /// The queue supplies references from every successfully decoded account before any new save.
    func pruneOrphans(references: [String: Set<String>]) throws {
        let manager = FileManager.default
        guard manager.fileExists(atPath: root.path) else { return }
        let scoped = Dictionary(uniqueKeysWithValues: references.map { (Self.digest(Data($0.key.utf8)), $0.value) })
        for directory in try manager.contentsOfDirectory(at: root, includingPropertiesForKeys: [.isDirectoryKey, .isSymbolicLinkKey]) {
            let info = try directory.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
            guard info.isDirectory == true, info.isSymbolicLink != true,
                  directory.lastPathComponent.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil else { continue }
            for file in try manager.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey, .isSymbolicLinkKey]) {
                let id = file.deletingPathExtension().lastPathComponent
                let info = try file.resourceValues(forKeys: [.contentModificationDateKey, .isRegularFileKey, .isSymbolicLinkKey])
                guard file.pathExtension == "sealed", UUID(uuidString: id) != nil, info.isRegularFile == true, info.isSymbolicLink != true,
                      scoped[directory.lastPathComponent]?.contains(id) != true,
                      Date().timeIntervalSince(info.contentModificationDate ?? .distantFuture) > 600 else { continue }
                try manager.removeItem(at: file)
            }
        }
    }
    func load(uid: String, voice: MusterLocalVoice) throws -> Data {
        let url = try url(uid: uid, voice: voice)
        guard (try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0) <= Self.maximumBytes + 64 else { throw MusterError.invalid }
        let data = try AES.GCM.open(AES.GCM.SealedBox(combined: Data(contentsOf: url)), using: key(), authenticating: associatedData(uid: uid, voice: voice))
        guard data.count == voice.bytes, Self.digest(data) == voice.sha256 else { throw MusterError.persistence }
        return data
    }
    func remove(uid: String, voice: MusterLocalVoice) throws {
        let file = try url(uid: uid, voice: voice)
        if FileManager.default.fileExists(atPath: file.path) { try FileManager.default.removeItem(at: file) }
    }
}
