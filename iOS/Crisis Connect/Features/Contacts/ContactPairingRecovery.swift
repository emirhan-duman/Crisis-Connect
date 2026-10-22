import Foundation

/// Contains QR key material: always encrypted locally and excluded from device backups.
struct ContactPairingCheckpoint: Codable {
    let payload: ContactQRPayload
    let fallbackName: String
    let manual: Bool
    let owner: String
    let createdAt: Date
    var recoveries: Int = 0
    var verified: ContactVerificationResult?
    var savedID: UUID?

    func canResume(owner: String, now: Date) -> Bool {
        self.owner == owner && now >= createdAt && now.timeIntervalSince(createdAt) < 300 && recoveries < 3
    }
}

@MainActor
protocol ContactPairingJournaling {
    func read() throws -> ContactPairingCheckpoint?
    func write(_ checkpoint: ContactPairingCheckpoint) throws
    func clear() throws
}

@MainActor
final class EncryptedContactPairingJournal: ContactPairingJournaling {
    private let url: URL
    init(url: URL? = nil) {
        self.url = url ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("CrisisConnect/pending-contact-pairing.enc")
    }
    func read() throws -> ContactPairingCheckpoint? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try JSONDecoder().decode(ContactPairingCheckpoint.self, from: LocalEncryptedFileStore.read(from: url).data)
    }
    func write(_ checkpoint: ContactPairingCheckpoint) throws {
        try LocalEncryptedFileStore.write(JSONEncoder().encode(checkpoint), to: url)
        var file = url
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try file.setResourceValues(values)
    }
    func clear() throws {
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}
