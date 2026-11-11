import Foundation

actor AuthorityMlsMessageStore {
    private struct Record: Codable {
        let messageId: String
        let senderCredential: String
        let plaintextBase64: String
        let storedAtMillis: Int64
    }

    private static let maxMessages = 200
    private static let maxPlaintextBytes = 900_000
    private let context: String

    init(conversationId: String) {
        context = "authority-chat-history:v2:\(conversationId)"
    }

    func load() throws -> [AuthorityMlsPendingReceivedApplication] {
        guard let encoded = try MlsStateVault.loadProtectedData(context: context) else { return [] }
        let records = try JSONDecoder().decode([Record].self, from: encoded)
        guard records.count <= Self.maxMessages else { throw AuthorityMlsMessagePayloadError.malformed }
        return try records.map { record in
            guard record.messageId.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
                  !record.senderCredential.isEmpty, record.senderCredential.utf8.count <= 512,
                  let plaintext = Data(base64Encoded: record.plaintextBase64),
                  !plaintext.isEmpty, plaintext.count <= Self.maxPlaintextBytes else {
                throw AuthorityMlsMessagePayloadError.malformed
            }
            return AuthorityMlsPendingReceivedApplication(
                messageId: record.messageId,
                senderCredential: record.senderCredential,
                plaintext: plaintext
            )
        }
    }

    func append(_ application: AuthorityMlsPendingReceivedApplication) throws {
        guard application.messageId.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
              !application.senderCredential.isEmpty, application.senderCredential.utf8.count <= 512,
              !application.plaintext.isEmpty, application.plaintext.count <= Self.maxPlaintextBytes else {
            throw AuthorityMlsMessagePayloadError.malformed
        }
        var records: [Record] = if let encoded = try MlsStateVault.loadProtectedData(context: context) {
            try JSONDecoder().decode([Record].self, from: encoded)
        } else {
            []
        }
        records.removeAll { $0.messageId == application.messageId }
        records.append(Record(
            messageId: application.messageId,
            senderCredential: application.senderCredential,
            plaintextBase64: application.plaintext.base64EncodedString(),
            storedAtMillis: Int64(Date().timeIntervalSince1970 * 1000)
        ))
        records.sort { $0.storedAtMillis < $1.storedAtMillis }
        if records.count > Self.maxMessages { records.removeFirst(records.count - Self.maxMessages) }
        try MlsStateVault.saveProtectedData(try JSONEncoder().encode(records), context: context)
    }

    /// Bulk, one-commit import used by protocol rollovers. Message ids are content-stable.
    func merge(_ applications: [AuthorityMlsPendingReceivedApplication]) throws {
        guard !applications.isEmpty else { return }
        var records: [Record] = if let encoded = try MlsStateVault.loadProtectedData(context: context) {
            try JSONDecoder().decode([Record].self, from: encoded)
        } else {
            []
        }
        var storedAt = max(
            Int64(Date().timeIntervalSince1970 * 1000),
            (records.map(\.storedAtMillis).max() ?? 0) + 1
        )
        for application in applications {
            guard application.messageId.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
                  !application.senderCredential.isEmpty, application.senderCredential.utf8.count <= 512,
                  !application.plaintext.isEmpty, application.plaintext.count <= Self.maxPlaintextBytes else {
                throw AuthorityMlsMessagePayloadError.malformed
            }
            if let existing = records.first(where: { $0.messageId == application.messageId }) {
                guard let existingPlaintext = Data(base64Encoded: existing.plaintextBase64),
                      existingPlaintext == application.plaintext else {
                    throw AuthorityMlsMessagePayloadError.malformed
                }
            }
            records.removeAll { $0.messageId == application.messageId }
            records.append(Record(
                messageId: application.messageId,
                senderCredential: application.senderCredential,
                plaintextBase64: application.plaintext.base64EncodedString(),
                storedAtMillis: storedAt
            ))
            storedAt += 1
        }
        records.sort { $0.storedAtMillis < $1.storedAtMillis }
        if records.count > Self.maxMessages { records.removeFirst(records.count - Self.maxMessages) }
        try MlsStateVault.saveProtectedData(try JSONEncoder().encode(records), context: context)
    }
}
