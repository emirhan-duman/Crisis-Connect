import CryptoKit
import Foundation

struct AuthorityMlsDeviceIdentity: Sendable {
    let credential: String
    let accountUid: String
    let deviceId: String
    let signingPublicKey: Data
}

struct AuthorityMlsStep: Sendable {
    let broadcasts: [String]
    let safetyNumber: String?
    let nextControlSequence: Int64
    let nextApplicationSequence: Int64
    let pendingApplicationMessages: [AuthorityMlsPendingApplication]
    let pendingReceivedApplications: [AuthorityMlsPendingReceivedApplication]
}

struct AuthorityMlsOutbox: Sendable {
    let pendingBroadcasts: [String]
    let nextControlSequence: Int64
}

struct AuthorityMlsApplicationOutbox: Sendable {
    let pendingMessages: [AuthorityMlsPendingApplication]
}

/**
 * Durable, per-conversation MLS state for AuthorityChat. The actor serializes every ratchet mutation
 * and commits its OS-protected snapshot before releasing ciphertext or plaintext. A failed snapshot
 * destroys the older recovery point and closes the native context, preventing generation reuse after
 * restart. Handshake membership is accepted only when every MLS signing key matches the caller's
 * verified device directory.
 */
actor PersistentAuthorityMlsContext {
    let accountUid: String
    let channelId: String
    let deviceId: String
    let credential: String

    private let stateContext: String
    private let backend: MlsWorkerBackend
    private var initialized = false
    private var nextControlSequence: Int64 = 0
    private var nextApplicationSequence: Int64 = 0
    private var pendingControlEvents: [String] = []
    private var pendingApplicationMessages: [AuthorityMlsPendingApplication] = []
    private var pendingReceivedApplications: [AuthorityMlsPendingReceivedApplication] = []
    private var offlineReceipts: [AuthorityMlsOfflineReceipt] = []

    init(accountUid: String, channelId: String, backend: MlsWorkerBackend? = MlsWorker.backend) throws {
        let uid = accountUid.trimmingCharacters(in: .whitespacesAndNewlines)
        let channel = channelId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty, !channel.isEmpty, channel.utf8.count <= 256, let backend else {
            throw backend == nil ? AuthorityMlsError.unavailable : AuthorityMlsError.invalidContext
        }
        let deviceId = SecureLocalStore.shared.getOrCreateRescueDeviceId()
        let credential = try AuthorityMlsCredential.encode(accountUid: uid, deviceId: deviceId)
        let stateContext = "authority-mls:v2:\(uid):\(deviceId):\(channel)"
        guard stateContext.utf8.count <= 512 else { throw AuthorityMlsError.invalidContext }
        self.accountUid = uid
        self.channelId = channel
        self.deviceId = deviceId
        self.credential = credential
        self.stateContext = stateContext
        self.backend = backend
    }

    func restoreOrInitialize(isCreator: Bool) throws -> AuthorityMlsStep {
        if initialized {
            return AuthorityMlsStep(
                broadcasts: pendingControlEvents,
                safetyNumber: currentSafetyNumberIfEstablished(),
                nextControlSequence: nextControlSequence,
                nextApplicationSequence: nextApplicationSequence,
                pendingApplicationMessages: pendingApplicationMessages,
                pendingReceivedApplications: pendingReceivedApplications
            )
        }
        if let encoded = try MlsStateVault.load(context: stateContext) {
            let state = try AuthorityMlsDurableStateCodec.decode(encoded)
            try backend.persistentImportState(context: stateContext, snapshot: state.snapshot)
            initialized = true
            nextControlSequence = state.nextControlSequence
            nextApplicationSequence = state.nextApplicationSequence
            pendingControlEvents = state.pendingControlEvents
            pendingApplicationMessages = state.pendingApplicationMessages
            pendingReceivedApplications = state.pendingReceivedApplications
            offlineReceipts = state.offlineReceipts
            let identity = try localIdentity()
            guard identity.credential == credential else { throw AuthorityMlsError.identityMismatch }
            return AuthorityMlsStep(
                broadcasts: pendingControlEvents,
                safetyNumber: currentSafetyNumberIfEstablished(),
                nextControlSequence: nextControlSequence,
                nextApplicationSequence: nextApplicationSequence,
                pendingApplicationMessages: pendingApplicationMessages,
                pendingReceivedApplications: pendingReceivedApplications
            )
        }
        return try advanceRatchet {
            let response = try (isCreator
                ? backend.persistentNewStateAndCreateGroup(context: stateContext, credential: credential)
                : backend.persistentNewState(context: stateContext, credential: credential))
            initialized = true
            return incorporate(try parseResponse(response))
        }
    }

    func localIdentity() throws -> AuthorityMlsDeviceIdentity {
        guard initialized else { throw AuthorityMlsError.invalidContext }
        let json = try backend.persistentIdentity(context: stateContext)
        return try parseIdentity(root: parseObject(json))
    }

    func alignFreshJoinRelayCursors(
        nextControl: Int64,
        nextApplication: Int64
    ) throws -> AuthorityMlsStep {
        guard initialized,
              let payload = pendingControlEvents.first,
              pendingControlEvents.count == 1,
              let envelope = try? parseObject(payload),
              envelope["type"] as? String == "shareKeyPackage",
              envelope["senderId"] as? String == credential,
              envelope["senderUid"] as? String == accountUid,
              pendingApplicationMessages.isEmpty,
              pendingReceivedApplications.isEmpty,
              nextControl >= nextControlSequence,
              nextApplication >= nextApplicationSequence else {
            throw AuthorityMlsError.invalidContext
        }
        return try advanceRatchet {
            nextControlSequence = nextControl
            nextApplicationSequence = nextApplication
            return AuthorityMlsStep(
                broadcasts: pendingControlEvents,
                safetyNumber: nil,
                nextControlSequence: nextControlSequence,
                nextApplicationSequence: nextApplicationSequence,
                pendingApplicationMessages: pendingApplicationMessages,
                pendingReceivedApplications: pendingReceivedApplications
            )
        }
    }

    /// Returns only a roster whose credentials and signing keys match the approved directory.
    func verifiedRoster(_ directory: [String: Data]) throws -> [AuthorityMlsDeviceIdentity] {
        let identities = try authenticatedRoster()
        for identity in identities {
            guard directory[identity.credential] == identity.signingPublicKey else {
                throw AuthorityMlsError.unverifiedDevice
            }
        }
        return identities
    }

    /// Cryptographically authenticated roster from the already-established local MLS group.
    func authenticatedRoster() throws -> [AuthorityMlsDeviceIdentity] {
        guard initialized else { throw AuthorityMlsError.invalidContext }
        let root = try parseObject(backend.persistentRoster(context: stateContext))
        guard root["error"] == nil,
              let members = root["members"] as? [[String: Any]],
              !members.isEmpty else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return try members.map { try parseIdentity(root: $0) }
    }

    func processHandshake(
        _ payload: String,
        authenticatedSenderUid: String,
        verifiedDirectory: [String: Data],
        sequence: Int64,
        relayApplicationSequence: Int64
    ) throws -> AuthorityMlsStep {
        guard initialized else { throw AuthorityMlsError.invalidContext }
        guard sequence == nextControlSequence, sequence < Self.maxSafeSequence else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        guard relayApplicationSequence >= 0, relayApplicationSequence < Self.maxSafeSequence else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        let envelope = try parseObject(payload)
        guard let senderUid = envelope["senderUid"] as? String,
              let senderCredential = envelope["senderId"] as? String,
              let parsed = AuthorityMlsCredential.decode(senderCredential),
              senderUid == authenticatedSenderUid,
              parsed.accountUid == authenticatedSenderUid,
              let expectedSigningKey = verifiedDirectory[senderCredential] else {
            throw AuthorityMlsError.unverifiedDevice
        }
        guard let message = MlsHandshakeCodec.decode(payload) else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        let addressedElsewhere: Bool
        if case .sendMlsWelcome = message {
            guard let recipient = envelope["recipientId"] as? String, !recipient.isEmpty else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            addressedElsewhere = recipient != credential
        } else {
            addressedElsewhere = false
        }
        let controlType = envelope["type"] as? String
        let applicationBoundary: Int64?
        if controlType == "shareKeyPackage" {
            guard envelope["applicationSequenceBoundary"] == nil else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            applicationBoundary = nil
        } else {
            guard let number = envelope["applicationSequenceBoundary"] as? NSNumber else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            let value = number.int64Value
            guard number.doubleValue == Double(value), value >= 0, value < Self.maxSafeSequence else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            applicationBoundary = value
        }
        return try advanceRatchet {
            if case .sendMlsWelcome = message, !addressedElsewhere {
                guard let boundary = applicationBoundary,
                      currentSafetyNumberIfEstablished() == nil,
                      pendingApplicationMessages.isEmpty,
                      pendingReceivedApplications.isEmpty,
                      boundary >= nextApplicationSequence,
                      boundary <= relayApplicationSequence else {
                    throw AuthorityMlsError.invalidWorkerResponse
                }
                nextApplicationSequence = boundary
            }
            let step: AuthorityMlsStep
            if addressedElsewhere {
                step = AuthorityMlsStep(
                    broadcasts: [],
                    safetyNumber: currentSafetyNumberIfEstablished(),
                    nextControlSequence: nextControlSequence,
                    nextApplicationSequence: nextApplicationSequence,
                    pendingApplicationMessages: pendingApplicationMessages,
                    pendingReceivedApplications: pendingReceivedApplications
                )
            } else {
                let response: String
                switch message {
                case .shareKeyPackage(let keyPkg):
                    response = try backend.persistentAddUser(
                        context: stateContext,
                        keyPkg: keyPkg,
                        expectedCredential: senderCredential,
                        expectedSigningKey: expectedSigningKey
                    )
                case .sendMlsWelcome(_, let welcome, let rtree):
                    response = try backend.persistentJoinGroup(
                        context: stateContext,
                        welcome: welcome,
                        rtree: rtree
                    )
                case .sendMlsMessage(let msg, _):
                    response = try backend.persistentHandleCommit(
                        context: stateContext,
                        msg: msg,
                        senderCredential: senderCredential
                    )
                }
                step = try parseResponse(
                    response,
                    welcomeRecipient: {
                        if case .shareKeyPackage = message { return senderCredential }
                        return nil
                    }(),
                    applicationSequenceBoundary: relayApplicationSequence
                )
            }
            let safetyNumber = currentSafetyNumberIfEstablished()
            if !addressedElsewhere, safetyNumber != nil { try verifyRoster(verifiedDirectory) }
            nextControlSequence = sequence + 1
            return incorporate(AuthorityMlsStep(
                broadcasts: step.broadcasts,
                safetyNumber: safetyNumber ?? step.safetyNumber,
                nextControlSequence: step.nextControlSequence,
                nextApplicationSequence: step.nextApplicationSequence,
                pendingApplicationMessages: step.pendingApplicationMessages,
                pendingReceivedApplications: step.pendingReceivedApplications
            ))
        }
    }

    /// Call only after this exact outbox head is durably present at `sequence` in Firestore.
    func acknowledgePublishedControlEvent(sequence: Int64, payload: String) throws -> AuthorityMlsOutbox {
        guard initialized,
              sequence == nextControlSequence,
              sequence < Self.maxSafeSequence,
              pendingControlEvents.first == payload else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return try advanceRatchet {
            nextControlSequence = sequence + 1
            pendingControlEvents.removeFirst()
            return AuthorityMlsOutbox(
                pendingBroadcasts: pendingControlEvents,
                nextControlSequence: nextControlSequence
            )
        }
    }

    func encryptApplication(_ plaintext: Data) throws -> Data {
        guard initialized else { throw AuthorityMlsError.invalidContext }
        return try advanceRatchet {
            try backend.persistentEncryptApplication(context: stateContext, plaintext: plaintext)
        }
    }

    /// Encrypts once and persists the exact ciphertext before exposing it to Firestore transport.
    func queueApplication(
        _ plaintext: Data,
        messageId: String = randomAuthorityMlsMessageId()
    ) throws -> AuthorityMlsPendingApplication {
        guard initialized,
              messageId.utf8.count <= 128,
              messageId.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
            throw AuthorityMlsError.invalidContext
        }
        return try advanceRatchet {
            let encrypted = try backend.persistentEncryptApplication(context: stateContext, plaintext: plaintext)
            let entry = AuthorityMlsPendingApplication(
                messageId: messageId,
                ciphertext: authorityMlsBase64url(encrypted)
            )
            pendingApplicationMessages.append(entry)
            return entry
        }
    }

    func pendingApplication(messageId: String) -> AuthorityMlsPendingApplication? {
        pendingApplicationMessages.first { $0.messageId == messageId }
    }

    func acknowledgePublishedApplication(
        messageId: String,
        ciphertext: String
    ) throws -> AuthorityMlsApplicationOutbox {
        guard initialized,
              pendingApplicationMessages.first?.messageId == messageId,
              pendingApplicationMessages.first?.ciphertext == ciphertext else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return try advanceRatchet {
            pendingApplicationMessages.removeFirst()
            return AuthorityMlsApplicationOutbox(pendingMessages: pendingApplicationMessages)
        }
    }

    func decryptApplication(_ ciphertext: Data) throws -> Data {
        guard initialized else { throw AuthorityMlsError.invalidContext }
        return try advanceRatchet {
            try backend.persistentDecryptApplication(context: stateContext, ciphertext: ciphertext)
        }
    }

    /// Commits the ordered receive cursor, receiver ratchet and plaintext inbox atomically.
    func processApplicationMessage(
        sequence: Int64,
        messageId: String,
        senderCredential: String,
        ciphertext: String,
        verifiedDirectory: [String: Data]
    ) throws -> [AuthorityMlsPendingReceivedApplication] {
        guard initialized,
              sequence == nextApplicationSequence,
              sequence < Self.maxSafeSequence else { throw AuthorityMlsError.invalidWorkerResponse }
        guard messageId.utf8.count <= 128,
              messageId.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil,
              verifiedDirectory[senderCredential] != nil else { throw AuthorityMlsError.unverifiedDevice }
        return try advanceRatchet {
            if let offline = offlineReceipts.first(where: { $0.messageId == messageId }) {
                let hash = try ciphertextHash(ciphertext)
                guard offline.senderCredential == senderCredential,
                      offline.ciphertextHash == hash else {
                    throw AuthorityMlsError.invalidWorkerResponse
                }
                offlineReceipts.removeAll { $0.messageId == messageId }
                nextApplicationSequence = sequence + 1
                return pendingReceivedApplications
            }
            if senderCredential != credential {
                let plaintext = try backend.persistentDecryptApplication(
                    context: stateContext,
                    ciphertext: try decodeAuthorityMlsBase64url(ciphertext)
                )
                pendingReceivedApplications.append(AuthorityMlsPendingReceivedApplication(
                    messageId: messageId,
                    senderCredential: senderCredential,
                    plaintext: plaintext
                ))
            }
            nextApplicationSequence = sequence + 1
            return pendingReceivedApplications
        }
    }

    /// Opens one same-MLS ciphertext from the authenticated nearby peer without moving cloud order.
    func processOfflineApplicationMessage(
        messageId: String,
        senderCredential: String,
        ciphertext: String,
        authenticatedPeerUid: String
    ) throws -> [AuthorityMlsPendingReceivedApplication] {
        guard initialized,
              messageId.utf8.count <= 128,
              messageId.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil,
              let parsed = AuthorityMlsCredential.decode(senderCredential),
              parsed.accountUid == authenticatedPeerUid,
              senderCredential != credential else { throw AuthorityMlsError.unverifiedDevice }
        let roster = try authenticatedRoster()
        guard roster.contains(where: {
            $0.credential == senderCredential && $0.accountUid == authenticatedPeerUid
        }) else { throw AuthorityMlsError.unverifiedDevice }
        let hash = try ciphertextHash(ciphertext)
        if let existing = offlineReceipts.first(where: { $0.messageId == messageId }) {
            guard existing.senderCredential == senderCredential,
                  existing.ciphertextHash == hash else { throw AuthorityMlsError.invalidWorkerResponse }
            return pendingReceivedApplications
        }
        guard offlineReceipts.count < 256 else { throw AuthorityMlsError.invalidContext }
        return try advanceRatchet {
            let plaintext = try backend.persistentDecryptApplication(
                context: stateContext,
                ciphertext: try decodeAuthorityMlsBase64url(ciphertext)
            )
            pendingReceivedApplications.append(AuthorityMlsPendingReceivedApplication(
                messageId: messageId, senderCredential: senderCredential, plaintext: plaintext
            ))
            offlineReceipts.append(AuthorityMlsOfflineReceipt(
                messageId: messageId, senderCredential: senderCredential, ciphertextHash: hash
            ))
            return pendingReceivedApplications
        }
    }

    /// ACK only after the UI/local encrypted database durably accepted this exact inbox head.
    func acknowledgeDeliveredApplication(messageId: String) throws -> [AuthorityMlsPendingReceivedApplication] {
        guard initialized, pendingReceivedApplications.first?.messageId == messageId else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return try advanceRatchet {
            pendingReceivedApplications.removeFirst()
            return pendingReceivedApplications
        }
    }

    func close() throws {
        if initialized { try backend.persistentClose(context: stateContext) }
        initialized = false
    }

    /// Use only after the user has approved a verified fresh rejoin/rekey.
    func discardForVerifiedRejoin() throws {
        if initialized { try? backend.persistentClose(context: stateContext) }
        initialized = false
        nextControlSequence = 0
        nextApplicationSequence = 0
        pendingControlEvents = []
        pendingApplicationMessages = []
        pendingReceivedApplications = []
        offlineReceipts = []
        try MlsStateVault.delete(context: stateContext)
    }

    private func verifyRoster(_ directory: [String: Data]) throws {
        _ = try verifiedRoster(directory)
    }

    private func parseIdentity(root: [String: Any]) throws -> AuthorityMlsDeviceIdentity {
        guard root["error"] == nil,
              let identityCredential = root["credential"] as? String,
              let parsed = AuthorityMlsCredential.decode(identityCredential),
              let signing = decodeByteField(root["signingPublicKey"]),
              signing.count == 32 else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return AuthorityMlsDeviceIdentity(
            credential: identityCredential,
            accountUid: parsed.accountUid,
            deviceId: parsed.deviceId,
            signingPublicKey: signing
        )
    }

    private func parseResponse(
        _ raw: String,
        welcomeRecipient: String? = nil,
        applicationSequenceBoundary: Int64? = nil
    ) throws -> AuthorityMlsStep {
        var root = try parseObject(raw)
        guard root["error"] == nil else { throw AuthorityMlsError.invalidWorkerResponse }
        var broadcasts: [String] = []
        if let messages = root["broadcast"] as? [[String: Any]] {
            for var message in messages {
                message["senderId"] = credential
                message["senderUid"] = accountUid
                if message["type"] as? String == "sendMlsWelcome" {
                    guard let welcomeRecipient else { throw AuthorityMlsError.invalidWorkerResponse }
                    message["recipientId"] = welcomeRecipient
                }
                if message["type"] as? String == "sendMlsWelcome" ||
                    message["type"] as? String == "sendMlsMessage" {
                    guard let applicationSequenceBoundary else {
                        throw AuthorityMlsError.invalidWorkerResponse
                    }
                    message["applicationSequenceBoundary"] = applicationSequenceBoundary
                }
                guard JSONSerialization.isValidJSONObject(message),
                      let encoded = try? JSONSerialization.data(withJSONObject: message) else {
                    throw AuthorityMlsError.invalidWorkerResponse
                }
                broadcasts.append(String(decoding: encoded, as: UTF8.self))
            }
        }
        let safetyNumber: String?
        if let rawNumber = root["safetyNumber"] as? [NSNumber], !rawNumber.isEmpty {
            safetyNumber = rawNumber.map { String(format: "%03d", $0.intValue & 0xff) }.joined()
        } else {
            safetyNumber = nil
        }
        root.removeAll(keepingCapacity: false)
        return AuthorityMlsStep(
            broadcasts: broadcasts,
            safetyNumber: safetyNumber,
            nextControlSequence: nextControlSequence,
            nextApplicationSequence: nextApplicationSequence,
            pendingApplicationMessages: pendingApplicationMessages,
            pendingReceivedApplications: pendingReceivedApplications
        )
    }

    private func incorporate(_ step: AuthorityMlsStep) -> AuthorityMlsStep {
        pendingControlEvents.append(contentsOf: step.broadcasts)
        return AuthorityMlsStep(
            broadcasts: pendingControlEvents,
            safetyNumber: step.safetyNumber,
            nextControlSequence: nextControlSequence,
            nextApplicationSequence: nextApplicationSequence,
            pendingApplicationMessages: pendingApplicationMessages,
            pendingReceivedApplications: pendingReceivedApplications
        )
    }

    private func currentSafetyNumberIfEstablished() -> String? {
        guard let bytes = try? backend.persistentSafetyNumber(context: stateContext) else { return nil }
        return bytes.isEmpty ? nil : bytes.map { String(format: "%03d", $0) }.joined()
    }

    private func persist() throws {
        let snapshot = try backend.persistentExportState(context: stateContext)
        let state = AuthorityMlsDurableState(
            snapshot: snapshot,
            nextControlSequence: nextControlSequence,
            nextApplicationSequence: nextApplicationSequence,
            pendingControlEvents: pendingControlEvents,
            pendingApplicationMessages: pendingApplicationMessages,
            pendingReceivedApplications: pendingReceivedApplications,
            offlineReceipts: offlineReceipts
        )
        try MlsStateVault.save(try AuthorityMlsDurableStateCodec.encode(state), context: stateContext)
    }

    private func advanceRatchet<T>(_ operation: () throws -> T) throws -> T {
        try MlsStateVault.beginAdvance(context: stateContext)
        do {
            let result = try operation()
            try persist()
            return result
        } catch {
            // The journal predates every possible native mutation. Destroy both recovery points on
            // any ambiguity rather than ever reuse a sender or receiver generation.
            try? MlsStateVault.delete(context: stateContext)
            try? backend.persistentClose(context: stateContext)
            initialized = false
            throw AuthorityMlsError.stateCommitFailed
        }
    }

    private func parseObject(_ value: String) throws -> [String: Any] {
        guard let data = value.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        return object
    }

    private func ciphertextHash(_ ciphertext: String) throws -> String {
        authorityMlsBase64url(Data(SHA256.hash(data: try decodeAuthorityMlsBase64url(ciphertext))))
    }

    private func decodeByteField(_ value: Any?) -> Data? {
        guard let field = value as? [String: Any],
              (field["FLAG_ARRAY_BUFFER"] as? Bool == true || field["FLAG_TYPED_ARRAY"] as? Bool == true),
              let values = field["data"] as? [NSNumber] else { return nil }
        return Data(values.map { UInt8(truncating: $0) })
    }

    private static let maxSafeSequence: Int64 = 9_007_199_254_740_991
}

private func randomAuthorityMlsMessageId() -> String {
    "m_" + authorityMlsBase64url(Data((0..<16).map { _ in UInt8.random(in: .min ... .max) }))
}

private func authorityMlsBase64url(_ data: Data) -> String {
    data.base64EncodedString()
        .replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_")
        .replacingOccurrences(of: "=", with: "")
}

private func decodeAuthorityMlsBase64url(_ value: String) throws -> Data {
    guard !value.isEmpty,
          value.utf8.count <= 900_000,
          value.range(of: "^[A-Za-z0-9_-]+$", options: .regularExpression) != nil else {
        throw AuthorityMlsError.invalidWorkerResponse
    }
    var normalized = value.replacingOccurrences(of: "-", with: "+")
        .replacingOccurrences(of: "_", with: "/")
    let remainder = normalized.count % 4
    if remainder > 0 { normalized += String(repeating: "=", count: 4 - remainder) }
    guard let decoded = Data(base64Encoded: normalized), authorityMlsBase64url(decoded) == value else {
        throw AuthorityMlsError.invalidWorkerResponse
    }
    return decoded
}
