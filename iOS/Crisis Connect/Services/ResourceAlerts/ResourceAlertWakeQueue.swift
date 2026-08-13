import Foundation
import Security

struct PendingResourceAlertWake: Codable, Equatable, Sendable {
    let recipientUid: String
    let panelId: String
    let attemptId: String
    let receiptNonce: String
    let enqueuedAt: TimeInterval
    var attemptCount: Int
    var nextAttemptAt: TimeInterval

    var key: String { "\(attemptId).\(receiptNonce)" }

    var payload: ResourceAlertWakePayload {
        ResourceAlertWakePayload(
            panelId: panelId,
            attemptId: attemptId,
            receiptNonce: receiptNonce
        )
    }
}

protocol ResourceAlertWakeQueuePersisting: Sendable {
    func load() throws -> Data?
    func save(_ data: Data?) throws
}

enum ResourceAlertWakeQueueError: Error {
    case keychain(OSStatus)
    case capacity
}

/// Device-only Keychain storage keeps the receipt nonce encrypted, available to silent pushes after
/// first unlock, excluded from backup/sync, and durable across process termination.
final class ResourceAlertWakeKeychainStore: ResourceAlertWakeQueuePersisting, @unchecked Sendable {
    private let service = "com.auralis.crisisconnect.resource-alert-wake"
    private let account = "pending-acks-v1"

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrSynchronizable as String: false,
        ]
    }

    func load() throws -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = item as? Data else {
            throw ResourceAlertWakeQueueError.keychain(status)
        }
        return data
    }

    func save(_ data: Data?) throws {
        let query = baseQuery()
        guard let data else {
            let status = SecItemDelete(query as CFDictionary)
            guard status == errSecSuccess || status == errSecItemNotFound else {
                throw ResourceAlertWakeQueueError.keychain(status)
            }
            return
        }
        let update = SecItemUpdate(
            query as CFDictionary,
            [kSecValueData as String: data] as CFDictionary
        )
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else {
            throw ResourceAlertWakeQueueError.keychain(update)
        }
        var insert = query
        insert[kSecValueData as String] = data
        insert[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let add = SecItemAdd(insert as CFDictionary, nil)
        guard add == errSecSuccess else { throw ResourceAlertWakeQueueError.keychain(add) }
    }
}

actor ResourceAlertWakeQueue {
    static let shared = ResourceAlertWakeQueue(storage: ResourceAlertWakeKeychainStore())
    static let maximumEntries = 32
    static let retention: TimeInterval = 72 * 60 * 60
    static let maximumBackoff: TimeInterval = 6 * 60 * 60

    private let storage: ResourceAlertWakeQueuePersisting
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(storage: ResourceAlertWakeQueuePersisting) {
        self.storage = storage
    }

    func enqueue(
        payload: ResourceAlertWakePayload,
        recipientUid: String,
        now: Date = Date()
    ) throws -> String {
        let nowValue = now.timeIntervalSince1970
        // Enqueue is the account transition boundary: a newly authenticated account replaces any
        // previous account's device-only challenges, which must never be replayed cross-account.
        var entries = try normalizedEntries(now: nowValue, recipientUid: recipientUid)
        let candidate = PendingResourceAlertWake(
            recipientUid: recipientUid,
            panelId: payload.panelId,
            attemptId: payload.attemptId,
            receiptNonce: payload.receiptNonce,
            enqueuedAt: nowValue,
            attemptCount: 0,
            nextAttemptAt: nowValue
        )
        if !entries.contains(where: { $0.key == candidate.key }) {
            guard entries.count < Self.maximumEntries else {
                throw ResourceAlertWakeQueueError.capacity
            }
            entries.append(candidate)
        }
        entries.sort { $0.enqueuedAt < $1.enqueuedAt }
        try persist(entries)
        return candidate.key
    }

    func claim(
        key: String? = nil,
        recipientUid: String,
        now: Date = Date()
    ) throws -> PendingResourceAlertWake? {
        let nowValue = now.timeIntervalSince1970
        var entries = try normalizedEntries(now: nowValue, recipientUid: recipientUid)
        let eligible = entries.indices.filter { index in
            entries[index].recipientUid == recipientUid
                && entries[index].nextAttemptAt <= nowValue
                && (key == nil || entries[index].key == key)
        }
        guard let index = eligible.min(by: { entries[$0].nextAttemptAt < entries[$1].nextAttemptAt }) else {
            try persist(entries)
            return nil
        }
        var claimed = entries[index]
        claimed.attemptCount += 1
        claimed.nextAttemptAt = nowValue + Self.backoff(afterAttempt: claimed.attemptCount)
        entries[index] = claimed
        try persist(entries)
        return claimed
    }

    func complete(key: String, recipientUid: String, now: Date = Date()) throws {
        var entries = try normalizedEntries(now: now.timeIntervalSince1970, recipientUid: recipientUid)
        entries.removeAll { $0.recipientUid == recipientUid && $0.key == key }
        try persist(entries)
    }

    func hasPending(recipientUid: String, now: Date = Date()) throws -> Bool {
        let entries = try normalizedEntries(now: now.timeIntervalSince1970, recipientUid: recipientUid)
        try persist(entries)
        return entries.contains { $0.recipientUid == recipientUid }
    }

    func nextAttemptDate(recipientUid: String, now: Date = Date()) throws -> Date? {
        let entries = try normalizedEntries(now: now.timeIntervalSince1970, recipientUid: recipientUid)
        try persist(entries)
        return entries
            .filter { $0.recipientUid == recipientUid }
            .map(\.nextAttemptAt)
            .min()
            .map(Date.init(timeIntervalSince1970:))
    }

    static func backoff(afterAttempt attempt: Int) -> TimeInterval {
        let exponent = min(max(attempt - 1, 0), 10)
        return min(30 * pow(2, Double(exponent)), maximumBackoff)
    }

    private func normalizedEntries(now: TimeInterval, recipientUid: String) throws -> [PendingResourceAlertWake] {
        guard let data = try storage.load() else { return [] }
        let decoded = (try? decoder.decode([PendingResourceAlertWake].self, from: data)) ?? []
        return decoded.filter { entry in
            entry.recipientUid == recipientUid
                && now - entry.enqueuedAt <= Self.retention
                && entry.enqueuedAt <= now + 5 * 60
                && entry.attemptCount >= 0
                && entry.attemptCount <= 10_000
        }
    }

    private func persist(_ entries: [PendingResourceAlertWake]) throws {
        try storage.save(entries.isEmpty ? nil : encoder.encode(entries))
    }
}
