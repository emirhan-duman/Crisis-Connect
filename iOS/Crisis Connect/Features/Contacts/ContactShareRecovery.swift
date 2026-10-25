import Foundation

struct ContactShareCheckpoint: Codable {
    let shareId: String
    let sessionCode: String
    let displayName: String?
    let aesKeyBase64: String
    let account: String
    var expiresAt: Date

    func canResume(sessionCode: String, account: String, now: Date) -> Bool {
        self.sessionCode == sessionCode && self.account == account &&
            expiresAt > now && expiresAt.timeIntervalSince(now) <= 300
    }
}

/// All access is serialized on the peripheral host queue. Never stores a handshake nonce:
/// reopening advertises the same QR identity but performs fresh challenge/response.
enum ContactShareRecovery {
    private static var url: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CrisisConnect/pending-contact-share.enc")
    }
    static func read() throws -> ContactShareCheckpoint? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(ContactShareCheckpoint.self, from: LocalEncryptedFileStore.read(from: url).data)
    }
    static func write(_ checkpoint: ContactShareCheckpoint) throws {
        try LocalEncryptedFileStore.write(JSONEncoder().encode(checkpoint), to: url)
        var file = url
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        try file.setResourceValues(values)
    }
    static func clear() throws {
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
