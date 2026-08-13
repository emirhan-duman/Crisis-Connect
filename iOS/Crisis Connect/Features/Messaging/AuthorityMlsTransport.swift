import CryptoKit
import FirebaseFirestore
import Foundation

enum AuthorityMlsScopeType: String, Sendable, Codable {
    case agency
    case hierarchy
}

struct AuthorityMlsBinding: Sendable {
    let scopeType: AuthorityMlsScopeType
    let channelId: String
    let participants: [String]
}

struct AuthorityMlsDirectoryRecord: Sendable {
    let uid: String
    let deviceId: String
    let credential: String
    let signingPublicKey: Data
    let label: String
}

struct AuthorityMlsDirectoryResult: Sendable {
    let records: [AuthorityMlsDirectoryRecord]
    let rejected: Int
}

struct AuthorityMlsControlEvent: Sendable {
    let id: String
    let senderUid: String
    let senderDeviceId: String
    let senderCredential: String
    let payload: String
    let sequence: Int64
}

struct AuthorityMlsCiphertextMessage: Sendable {
    let messageId: String
    let senderUid: String
    let senderDeviceId: String
    let senderCredential: String
    let ciphertext: String
    let sequence: Int64

    init(
        messageId: String,
        senderUid: String,
        senderDeviceId: String,
        senderCredential: String,
        ciphertext: String,
        sequence: Int64 = -1
    ) {
        self.messageId = messageId
        self.senderUid = senderUid
        self.senderDeviceId = senderDeviceId
        self.senderCredential = senderCredential
        self.ciphertext = ciphertext
        self.sequence = sequence
    }
}

struct AuthorityMlsConversationHandle: Sendable {
    let conversationId: String
    let creatorCredential: String
    let nextControlSequence: Int64
    let nextApplicationSequence: Int64
}

enum AuthorityMlsIdentifiers {
    static func conversationId(_ binding: AuthorityMlsBinding) throws -> String {
        let canonical = try canonicalBinding(binding)
        return "am2_" + hash([
            "cc-authority-mls-conversation:v7",
            canonical.scopeType.rawValue,
            canonical.channelId,
            String(canonical.participants.count)
        ] + canonical.participants)
    }

    static func controlEventId(
        conversationId: String,
        sequence: Int64,
        senderCredential: String,
        payload: String
    ) throws -> String {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(sequence)
        return "c_" + hash([
            "cc-authority-mls-control:v2",
            conversationId,
            String(sequence),
            senderCredential,
            payload
        ])
    }

    static func canonicalBinding(_ binding: AuthorityMlsBinding) throws -> AuthorityMlsBinding {
        let channelId = try validatePart(binding.channelId, label: "channel ID", maxBytes: 256)
        let normalized = try binding.participants.map {
            try validatePart($0, label: "participant UID", maxBytes: 256)
        }
        var seen = Set<Data>()
        let unique = normalized.filter { seen.insert(Data($0.utf8)).inserted }
        let participants = unique.sorted(by: compareUtf8)
        guard !participants.isEmpty,
              participants.count <= 100,
              participants.count == binding.participants.count else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS participant set is invalid.")
        }
        return AuthorityMlsBinding(
            scopeType: binding.scopeType,
            channelId: channelId,
            participants: participants
        )
    }

    private static func hash(_ fields: [String]) -> String {
        var input = Data()
        for field in fields {
            let bytes = Data(field.utf8)
            var length = UInt32(bytes.count).bigEndian
            withUnsafeBytes(of: &length) { input.append(contentsOf: $0) }
            input.append(bytes)
        }
        return base64url(Data(SHA256.hash(data: input)))
    }
}

final class AuthorityMlsControlSubscription: @unchecked Sendable {
    private let registration: ListenerRegistration
    private let task: Task<Void, Never>
    private let finish: @Sendable () -> Void

    fileprivate init(
        registration: ListenerRegistration,
        task: Task<Void, Never>,
        finish: @escaping @Sendable () -> Void
    ) {
        self.registration = registration
        self.task = task
        self.finish = finish
    }

    func cancel() {
        registration.remove()
        finish()
        task.cancel()
    }

    deinit { cancel() }
}

/// Firestore is an untrusted, opaque relay; every identity and event binding is checked locally.
final class AuthorityMlsTransport: @unchecked Sendable {
    private static let root = "authorityMlsV2"
    private static let maxControlPayloadBytes = 256 * 1024
    private let db: Firestore

    init(db: Firestore = Firestore.firestore()) {
        self.db = db
    }

    func ensureConversation(
        _ binding: AuthorityMlsBinding,
        creatorUid: String,
        candidateCreatorCredential: String
    ) async throws -> AuthorityMlsConversationHandle {
        let canonical = try AuthorityMlsIdentifiers.canonicalBinding(binding)
        let uid = try validatePart(creatorUid, label: "creator UID", maxBytes: 256)
        guard canonical.participants.contains(uid) else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS creator is not a participant.")
        }
        guard AuthorityMlsCredential.decode(candidateCreatorCredential)?.accountUid == uid else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS creator credential is not bound to its account.")
        }
        let conversationId = try AuthorityMlsIdentifiers.conversationId(canonical)
        let reference = db.collection(Self.root).document(conversationId)
        let existing = try await reference.getDocument()
        if existing.exists {
            return AuthorityMlsConversationHandle(
                conversationId: conversationId,
                creatorCredential: try assertConversation(existing, expected: canonical),
                nextControlSequence: try exactInt64(existing.get("nextControlSequence")),
                nextApplicationSequence: try exactInt64(existing.get("nextApplicationSequence"))
            )
        }
        let fields: [String: Any] = [
            "version": Int64(2),
            "scopeType": canonical.scopeType.rawValue,
            "channelId": canonical.channelId,
            "participants": canonical.participants,
            "createdBy": uid,
            "creatorCredential": candidateCreatorCredential,
            "createdAt": FieldValue.serverTimestamp(),
            "nextControlSequence": Int64(0),
            "lastControlId": "",
            "nextApplicationSequence": Int64(0),
            "lastMessageId": ""
        ]
        do {
            try await reference.setData(fields)
        } catch {
            let raced = try await reference.getDocument()
            guard raced.exists else { throw error }
            return AuthorityMlsConversationHandle(
                conversationId: conversationId,
                creatorCredential: try assertConversation(raced, expected: canonical),
                nextControlSequence: try exactInt64(raced.get("nextControlSequence")),
                nextApplicationSequence: try exactInt64(raced.get("nextApplicationSequence"))
            )
        }
        return AuthorityMlsConversationHandle(
            conversationId: conversationId,
            creatorCredential: candidateCreatorCredential,
            nextControlSequence: 0,
            nextApplicationSequence: 0
        )
    }

    func registerDevice(conversationId: String, record: AuthorityMlsDirectoryRecord) async throws {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        let normalized = try validateDeviceRecord(record)
        let reference = db.collection(Self.root).document(conversationId)
            .collection("devices").document(normalized.deviceId)
        let existing = try await reference.getDocument()
        if existing.exists {
            try assertDevice(existing, expected: normalized)
            return
        }
        let fields: [String: Any] = [
            "uid": normalized.uid,
            "deviceId": normalized.deviceId,
            "credential": normalized.credential,
            "signingPublicKey": base64url(normalized.signingPublicKey),
            "label": normalized.label,
            "createdAt": FieldValue.serverTimestamp()
        ]
        do {
            try await reference.setData(fields)
        } catch {
            let raced = try await reference.getDocument()
            guard raced.exists else { throw error }
            try assertDevice(raced, expected: normalized)
        }
    }

    func revokeDevice(conversationId: String, deviceId: String) async throws {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        _ = try validateDocumentId(deviceId, label: "device ID")
        try await db.collection(Self.root).document(conversationId)
            .collection("devices").document(deviceId).delete()
    }

    /// Records are structurally valid only; user/admin pin verification remains mandatory.
    func loadDeviceDirectory(conversationId: String) async throws -> AuthorityMlsDirectoryResult {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        let snapshot = try await db.collection(Self.root).document(conversationId)
            .collection("devices").getDocuments()
        var records: [AuthorityMlsDirectoryRecord] = []
        var rejected = 0
        for document in snapshot.documents {
            do {
                let raw = document.data()
                let credential = try requireString(raw["credential"])
                guard let parsed = AuthorityMlsCredential.decode(credential) else {
                    throw AuthorityMlsTransportError.malformedDocument
                }
                let uid = try requireString(raw["uid"])
                let deviceId = try requireString(raw["deviceId"])
                let key = try decodeBase64url(try requireString(raw["signingPublicKey"]))
                guard document.documentID == deviceId,
                      parsed.accountUid == uid,
                      parsed.deviceId == deviceId,
                      key.count == 32 else {
                    throw AuthorityMlsTransportError.malformedDocument
                }
                records.append(AuthorityMlsDirectoryRecord(
                    uid: uid,
                    deviceId: deviceId,
                    credential: credential,
                    signingPublicKey: key,
                    label: String((raw["label"] as? String ?? "").prefix(64))
                ))
            } catch {
                rejected += 1
            }
        }
        records.sort { compareUtf8($0.credential, $1.credential) }
        return AuthorityMlsDirectoryResult(records: records, rejected: rejected)
    }

    /// Atomic, gap-free and idempotent publish for the durable MLS control outbox head.
    func publishControlEvent(
        conversationId: String,
        sequence: Int64,
        senderUid: String,
        senderDeviceId: String,
        senderCredential: String,
        payload: String
    ) async throws -> String {
        guard (1...Self.maxControlPayloadBytes).contains(payload.utf8.count) else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS control payload has an invalid size.")
        }
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(sequence)
        try validateSender(senderUid, senderDeviceId, senderCredential)
        let eventId = try AuthorityMlsIdentifiers.controlEventId(
            conversationId: conversationId,
            sequence: sequence,
            senderCredential: senderCredential,
            payload: payload
        )
        let parent = db.collection(Self.root).document(conversationId)
        let event = parent.collection("control").document(eventId)
        _ = try await db.runTransaction { transaction, errorPointer -> Any? in
            do {
                let parentSnapshot = try transaction.getDocument(parent)
                let eventSnapshot = try transaction.getDocument(event)
                guard parentSnapshot.exists else {
                    throw AuthorityMlsTransportError.malformedDocument
                }
                if eventSnapshot.exists {
                    try assertControl(
                        eventSnapshot,
                        sequence: sequence,
                        senderUid: senderUid,
                        senderDeviceId: senderDeviceId,
                        senderCredential: senderCredential,
                        payload: payload
                    )
                } else {
                    guard try exactInt64(parentSnapshot.get("nextControlSequence")) == sequence else {
                        throw AuthorityMlsTransportError.sequenceViolation
                    }
                    transaction.setData([
                        "senderUid": senderUid,
                        "senderDeviceId": senderDeviceId,
                        "senderCredential": senderCredential,
                        "payload": payload,
                        "sequence": sequence,
                        "createdAt": FieldValue.serverTimestamp()
                    ], forDocument: event)
                    transaction.updateData([
                        "nextControlSequence": sequence + 1,
                        "lastControlId": eventId
                    ], forDocument: parent)
                }
                return true
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return eventId
    }

    func listenControlEvents(
        conversationId: String,
        fromSequence: Int64,
        onEvent: @escaping @Sendable (AuthorityMlsControlEvent) async throws -> Void,
        onError: @escaping @Sendable (Error) -> Void
    ) throws -> AuthorityMlsControlSubscription {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(fromSequence)
        var continuation: AsyncStream<AuthorityMlsControlEvent>.Continuation!
        let stream = AsyncStream<AuthorityMlsControlEvent> { continuation = $0 }
        let streamContinuation = continuation!
        let task = Task {
            for await event in stream {
                guard !Task.isCancelled else { break }
                do { try await onEvent(event) } catch { onError(error) }
            }
        }
        let registration = db.collection(Self.root).document(conversationId).collection("control")
            .whereField("sequence", isGreaterThanOrEqualTo: fromSequence)
            .order(by: "sequence", descending: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onError(error)
                    return
                }
                guard let snapshot else { return }
                for change in snapshot.documentChanges where change.type == .added {
                    do {
                        streamContinuation.yield(try parseControl(
                            conversationId: conversationId,
                            eventId: change.document.documentID,
                            raw: change.document.data()
                        ))
                    } catch {
                        onError(error)
                    }
                }
            }
        return AuthorityMlsControlSubscription(
            registration: registration,
            task: task,
            finish: { streamContinuation.finish() }
        )
    }

    func publishCiphertext(
        conversationId: String,
        message: AuthorityMlsCiphertextMessage
    ) async throws -> Int64 {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        _ = try validateDocumentId(message.messageId, label: "message ID")
        try validateSender(message.senderUid, message.senderDeviceId, message.senderCredential)
        guard !message.ciphertext.isEmpty,
              message.ciphertext.utf8.count <= 900_000,
              message.ciphertext.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS application ciphertext is malformed.")
        }
        let parent = db.collection(Self.root).document(conversationId)
        let reference = parent.collection("messages").document(message.messageId)
        let value = try await db.runTransaction { transaction, errorPointer -> Any? in
            do {
                let parentSnapshot = try transaction.getDocument(parent)
                let existing = try transaction.getDocument(reference)
                guard parentSnapshot.exists else { throw AuthorityMlsTransportError.malformedDocument }
                if existing.exists {
                    guard existing.get("messageId") as? String == message.messageId,
                          try exactInt64(existing.get("contentVersion")) == 2,
                          existing.get("ciphertext") as? String == message.ciphertext,
                          existing.get("senderUid") as? String == message.senderUid,
                          existing.get("senderDeviceId") as? String == message.senderDeviceId,
                          existing.get("senderCredential") as? String == message.senderCredential else {
                        throw AuthorityMlsTransportError.identifierCollision
                    }
                    return try exactInt64(existing.get("sequence"))
                } else {
                    let sequence = try exactInt64(parentSnapshot.get("nextApplicationSequence"))
                    try validateSequence(sequence)
                    transaction.setData([
                        "senderUid": message.senderUid,
                        "senderDeviceId": message.senderDeviceId,
                        "senderCredential": message.senderCredential,
                        "messageId": message.messageId,
                        "contentVersion": Int64(2),
                        "ciphertext": message.ciphertext,
                        "sequence": sequence,
                        "createdAt": FieldValue.serverTimestamp()
                    ], forDocument: reference)
                    transaction.updateData([
                        "nextApplicationSequence": sequence + 1,
                        "lastMessageId": message.messageId
                    ], forDocument: parent)
                    return sequence
                }
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return try exactInt64(value)
    }

    func listenCiphertexts(
        conversationId: String,
        fromSequence: Int64,
        onMessage: @escaping @Sendable (AuthorityMlsCiphertextMessage) async throws -> Void,
        onError: @escaping @Sendable (Error) -> Void
    ) throws -> AuthorityMlsControlSubscription {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(fromSequence)
        var continuation: AsyncStream<AuthorityMlsCiphertextMessage>.Continuation!
        let stream = AsyncStream<AuthorityMlsCiphertextMessage> { continuation = $0 }
        let streamContinuation = continuation!
        let task = Task {
            for await message in stream {
                guard !Task.isCancelled else { break }
                do { try await onMessage(message) } catch { onError(error) }
            }
        }
        let registration = db.collection(Self.root).document(conversationId).collection("messages")
            .whereField("sequence", isGreaterThanOrEqualTo: fromSequence)
            .order(by: "sequence", descending: false)
            .addSnapshotListener { snapshot, error in
                if let error {
                    onError(error)
                    return
                }
                guard let snapshot else { return }
                for change in snapshot.documentChanges where change.type == .added {
                    do {
                        streamContinuation.yield(try parseCiphertext(
                            messageId: change.document.documentID,
                            raw: change.document.data()
                        ))
                    } catch {
                        onError(error)
                    }
                }
            }
        return AuthorityMlsControlSubscription(
            registration: registration,
            task: task,
            finish: { streamContinuation.finish() }
        )
    }

    func loadControlEventsFrom(
        conversationId: String,
        fromSequence: Int64,
        pageSize: Int = 100
    ) async throws -> [AuthorityMlsControlEvent] {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(fromSequence)
        guard (1...250).contains(pageSize) else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS control catch-up page size is invalid.")
        }
        let snapshot = try await db.collection(Self.root).document(conversationId).collection("control")
            .whereField("sequence", isGreaterThanOrEqualTo: fromSequence)
            .order(by: "sequence", descending: false)
            .limit(to: pageSize)
            .getDocuments()
        return try snapshot.documents.map {
            try parseControl(conversationId: conversationId, eventId: $0.documentID, raw: $0.data())
        }
    }

    func loadApplicationSequence(conversationId: String) async throws -> Int64 {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        let snapshot = try await db.collection(Self.root).document(conversationId).getDocument()
        guard snapshot.exists else { throw AuthorityMlsTransportError.malformedDocument }
        let sequence = try exactInt64(snapshot.get("nextApplicationSequence"))
        try validateSequence(sequence)
        return sequence
    }

    func loadCiphertextsBefore(
        conversationId: String,
        fromSequence: Int64,
        beforeSequence: Int64,
        pageSize: Int = 100
    ) async throws -> [AuthorityMlsCiphertextMessage] {
        _ = try validateDocumentId(conversationId, label: "conversation ID")
        try validateSequence(fromSequence)
        try validateSequence(beforeSequence)
        guard (1...250).contains(pageSize) else {
            throw AuthorityMlsTransportError.invalidInput("Authority MLS application catch-up page size is invalid.")
        }
        if fromSequence >= beforeSequence { return [] }
        let snapshot = try await db.collection(Self.root).document(conversationId).collection("messages")
            .whereField("sequence", isGreaterThanOrEqualTo: fromSequence)
            .whereField("sequence", isLessThan: beforeSequence)
            .order(by: "sequence", descending: false)
            .limit(to: pageSize)
            .getDocuments()
        return try snapshot.documents.map {
            try parseCiphertext(messageId: $0.documentID, raw: $0.data())
        }
    }
}

private enum AuthorityMlsTransportError: Error {
    case invalidInput(String)
    case malformedDocument
    case sequenceViolation
    case identifierCollision
}

private func assertConversation(_ snapshot: DocumentSnapshot, expected: AuthorityMlsBinding) throws -> String {
    guard try exactInt64(snapshot.get("version")) == 2,
          snapshot.get("scopeType") as? String == expected.scopeType.rawValue,
          snapshot.get("channelId") as? String == expected.channelId,
          snapshot.get("participants") as? [String] == expected.participants,
          try exactInt64(snapshot.get("nextControlSequence")) >= 0,
          try exactInt64(snapshot.get("nextApplicationSequence")) >= 0,
          snapshot.get("lastControlId") is String,
          snapshot.get("lastMessageId") is String else {
        throw AuthorityMlsTransportError.identifierCollision
    }
    let createdBy = try requireString(snapshot.get("createdBy"))
    let creatorCredential = try requireString(snapshot.get("creatorCredential"))
    guard expected.participants.contains(createdBy),
          AuthorityMlsCredential.decode(creatorCredential)?.accountUid == createdBy else {
        throw AuthorityMlsTransportError.identifierCollision
    }
    return creatorCredential
}

private func parseCiphertext(messageId: String, raw: [String: Any]) throws -> AuthorityMlsCiphertextMessage {
    _ = try validateDocumentId(messageId, label: "message ID")
    let message = AuthorityMlsCiphertextMessage(
        messageId: try requireString(raw["messageId"]),
        senderUid: try requireString(raw["senderUid"]),
        senderDeviceId: try requireString(raw["senderDeviceId"]),
        senderCredential: try requireString(raw["senderCredential"]),
        ciphertext: try requireString(raw["ciphertext"]),
        sequence: try exactInt64(raw["sequence"])
    )
    guard message.messageId == messageId,
          try exactInt64(raw["contentVersion"]) == 2,
          message.ciphertext.utf8.count <= 900_000,
          message.ciphertext.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
        throw AuthorityMlsTransportError.malformedDocument
    }
    try validateSequence(message.sequence)
    try validateSender(message.senderUid, message.senderDeviceId, message.senderCredential)
    return message
}

private func validateDeviceRecord(_ record: AuthorityMlsDirectoryRecord) throws -> AuthorityMlsDirectoryRecord {
    let uid = try validatePart(record.uid, label: "device owner UID", maxBytes: 256)
    let deviceId = try validateDocumentId(record.deviceId, label: "device ID")
    guard let parsed = AuthorityMlsCredential.decode(record.credential),
          parsed.accountUid == uid,
          parsed.deviceId == deviceId,
          record.signingPublicKey.count == 32 else {
        throw AuthorityMlsTransportError.invalidInput("Authority MLS device identity binding is invalid.")
    }
    return AuthorityMlsDirectoryRecord(
        uid: uid,
        deviceId: deviceId,
        credential: record.credential,
        signingPublicKey: record.signingPublicKey,
        label: String(record.label.prefix(64))
    )
}

private func assertDevice(_ snapshot: DocumentSnapshot, expected: AuthorityMlsDirectoryRecord) throws {
    guard snapshot.get("uid") as? String == expected.uid,
          snapshot.get("deviceId") as? String == expected.deviceId,
          snapshot.get("credential") as? String == expected.credential,
          snapshot.get("signingPublicKey") as? String == base64url(expected.signingPublicKey) else {
        throw AuthorityMlsTransportError.identifierCollision
    }
}

private func assertControl(
    _ snapshot: DocumentSnapshot,
    sequence: Int64,
    senderUid: String,
    senderDeviceId: String,
    senderCredential: String,
    payload: String
) throws {
    guard try exactInt64(snapshot.get("sequence")) == sequence,
          snapshot.get("senderUid") as? String == senderUid,
          snapshot.get("senderDeviceId") as? String == senderDeviceId,
          snapshot.get("senderCredential") as? String == senderCredential,
          snapshot.get("payload") as? String == payload else {
        throw AuthorityMlsTransportError.identifierCollision
    }
}

private func parseControl(
    conversationId: String,
    eventId: String,
    raw: [String: Any]
) throws -> AuthorityMlsControlEvent {
    let event = AuthorityMlsControlEvent(
        id: eventId,
        senderUid: try requireString(raw["senderUid"]),
        senderDeviceId: try requireString(raw["senderDeviceId"]),
        senderCredential: try requireString(raw["senderCredential"]),
        payload: try requireString(raw["payload"]),
        sequence: try exactInt64(raw["sequence"])
    )
    try validateSequence(event.sequence)
    try validateSender(event.senderUid, event.senderDeviceId, event.senderCredential)
    guard try AuthorityMlsIdentifiers.controlEventId(
        conversationId: conversationId,
        sequence: event.sequence,
        senderCredential: event.senderCredential,
        payload: event.payload
    ) == eventId else {
        throw AuthorityMlsTransportError.identifierCollision
    }
    return event
}

private func validateSender(_ senderUid: String, _ senderDeviceId: String, _ senderCredential: String) throws {
    let uid = try validatePart(senderUid, label: "sender UID", maxBytes: 256)
    let deviceId = try validateDocumentId(senderDeviceId, label: "sender device ID")
    guard let parsed = AuthorityMlsCredential.decode(senderCredential),
          parsed.accountUid == uid,
          parsed.deviceId == deviceId else {
        throw AuthorityMlsTransportError.invalidInput("Authority MLS sender identity binding is invalid.")
    }
}

private func validateSequence(_ sequence: Int64) throws {
    guard sequence >= 0, sequence < 9_007_199_254_740_991 else {
        throw AuthorityMlsTransportError.invalidInput("Authority MLS control sequence is invalid.")
    }
}

private func validateDocumentId(_ value: String, label: String) throws -> String {
    let normalized = try validatePart(value, label: label, maxBytes: 128)
    guard !normalized.contains("/") else {
        throw AuthorityMlsTransportError.invalidInput("Authority MLS \(label) is invalid.")
    }
    return normalized
}

private func validatePart(_ value: String, label: String, maxBytes: Int) throws -> String {
    let normalized = value.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !normalized.isEmpty,
          normalized.utf8.count <= maxBytes,
          !normalized.unicodeScalars.contains(where: { $0.value <= 31 || $0.value == 127 }) else {
        throw AuthorityMlsTransportError.invalidInput("Authority MLS \(label) is invalid.")
    }
    return normalized
}

private func requireString(_ value: Any?) throws -> String {
    guard let value = value as? String, !value.isEmpty else {
        throw AuthorityMlsTransportError.malformedDocument
    }
    return value
}

func exactInt64(_ value: Any?) throws -> Int64 {
    guard let number = value as? NSNumber else { throw AuthorityMlsTransportError.malformedDocument }
    let integer = number.int64Value
    guard number.doubleValue == Double(integer) else { throw AuthorityMlsTransportError.malformedDocument }
    return integer
}

private func base64url(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

private func decodeBase64url(_ value: String) throws -> Data {
    guard !value.isEmpty,
          value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
        throw AuthorityMlsTransportError.malformedDocument
    }
    var normalized = value.replacingOccurrences(of: "-", with: "+")
        .replacingOccurrences(of: "_", with: "/")
    let remainder = normalized.count % 4
    if remainder > 0 { normalized += String(repeating: "=", count: 4 - remainder) }
    guard let decoded = Data(base64Encoded: normalized), base64url(decoded) == value else {
        throw AuthorityMlsTransportError.malformedDocument
    }
    return decoded
}

private func compareUtf8(_ left: String, _ right: String) -> Bool {
    Array(left.utf8).lexicographicallyPrecedes(Array(right.utf8))
}
