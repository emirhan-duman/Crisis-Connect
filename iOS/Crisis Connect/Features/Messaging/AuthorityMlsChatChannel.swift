import Foundation

struct AuthorityMlsChatMessage: Sendable {
    let id: String
    let senderUid: String
    let payload: AuthorityMlsMessagePayload
}

struct AuthorityMlsOfflineEnvelope: Sendable {
    let conversationId: String
    let message: AuthorityMlsCiphertextMessage
}

enum AuthorityMlsOfflineEnvelopeCodec {
    static let prefix = "CC_AMLS2:"
    private static let maxEncodedBytes = 256 * 1024
    private static let conversationPattern = "^am2_[A-Za-z0-9_-]{43}$"
    private static let messagePattern = "^[A-Za-z0-9_-]{1,128}$"
    private static let base64urlPattern = "^[A-Za-z0-9_-]{1,900000}$"

    static func encode(
        conversationId: String,
        message: AuthorityMlsCiphertextMessage
    ) throws -> String {
        guard matches(conversationId, conversationPattern),
              matches(message.messageId, messagePattern),
              matches(message.ciphertext, base64urlPattern), validIdentityFields(message) else {
            throw AuthorityMlsError.invalidContext
        }
        let root: [String: Any] = [
            "v": 2, "c": conversationId, "m": message.messageId,
            "u": message.senderUid, "d": message.senderDeviceId,
            "k": message.senderCredential, "x": message.ciphertext
        ]
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        guard !data.isEmpty, data.count <= maxEncodedBytes else { throw AuthorityMlsError.invalidContext }
        return prefix + base64url(data)
    }

    static func decode(_ value: String) -> AuthorityMlsOfflineEnvelope? {
        guard value.hasPrefix(prefix), value.count <= prefix.count + maxEncodedBytes * 2,
              let data = decodeBase64url(String(value.dropFirst(prefix.count))),
              !data.isEmpty, data.count <= maxEncodedBytes,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              (root["v"] as? NSNumber)?.intValue == 2,
              let conversationId = root["c"] as? String,
              let messageId = root["m"] as? String,
              let senderUid = root["u"] as? String,
              let senderDeviceId = root["d"] as? String,
              let senderCredential = root["k"] as? String,
              let ciphertext = root["x"] as? String,
              matches(conversationId, conversationPattern), matches(messageId, messagePattern),
              matches(ciphertext, base64urlPattern) else { return nil }
        let message = AuthorityMlsCiphertextMessage(
            messageId: messageId, senderUid: senderUid, senderDeviceId: senderDeviceId,
            senderCredential: senderCredential, ciphertext: ciphertext
        )
        guard validIdentityFields(message) else { return nil }
        return AuthorityMlsOfflineEnvelope(
            conversationId: conversationId,
            message: message
        )
    }

    private static func matches(_ value: String, _ pattern: String) -> Bool {
        value.range(of: pattern, options: .regularExpression) != nil
    }

    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    private static func decodeBase64url(_ value: String) -> Data? {
        guard matches(value, base64urlPattern) else { return nil }
        var normalized = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = normalized.count % 4
        if remainder > 0 { normalized += String(repeating: "=", count: 4 - remainder) }
        guard let data = Data(base64Encoded: normalized), base64url(data) == value else { return nil }
        return data
    }

    private static func validIdentityFields(_ message: AuthorityMlsCiphertextMessage) -> Bool {
        !message.senderUid.isEmpty && message.senderUid.count <= 256 &&
            !message.senderDeviceId.isEmpty && message.senderDeviceId.count <= 128 &&
            !message.senderCredential.isEmpty && message.senderCredential.count <= 512
    }
}

/// Fail-closed 1:1 AuthorityChat adapter over the persistent MLS session.
actor AuthorityMlsChatChannel {
    private let selfUid: String
    private let peerUid: String
    private let session: AuthorityMlsConversationSession
    private let messageStore: AuthorityMlsMessageStore
    private var active = false

    var conversationId: String {
        get async { await session.conversationId }
    }

    private init(
        selfUid: String,
        peerUid: String,
        session: AuthorityMlsConversationSession,
        messageStore: AuthorityMlsMessageStore
    ) {
        self.selfUid = selfUid
        self.peerUid = peerUid
        self.session = session
        self.messageStore = messageStore
    }

    static func prepare(
        selfUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        channelId: String,
        deviceLabel: String
    ) async throws -> AuthorityMlsChatChannel {
        let session = try await AuthorityMlsConversationSession.prepare(
            accountUid: selfUid,
            binding: AuthorityMlsBinding(
                scopeType: scopeType,
                channelId: channelId,
                participants: [selfUid, peerUid]
            ),
            deviceLabel: String(deviceLabel.prefix(64))
        )
        return AuthorityMlsChatChannel(
            selfUid: selfUid,
            peerUid: peerUid,
            session: session,
            messageStore: AuthorityMlsMessageStore(conversationId: session.conversationId)
        )
    }

    func refreshPreparation() async throws -> AuthorityMlsPreparation {
        try await session.refreshPreparation()
    }

    func approveDeviceSet(uid: String, expectedFingerprint: String) async throws -> AuthorityMlsPreparation {
        try await session.approveDeviceSet(uid: uid, expectedFingerprint: expectedFingerprint)
    }

    func isReadyToSend() async throws -> Bool {
        try await session.isReadyToSend()
    }

    func activate(
        onMessage: @escaping @Sendable (AuthorityMlsChatMessage) async throws -> Void,
        onSecurityError: @escaping @Sendable (Error) -> Void = { _ in }
    ) async throws {
        guard !active else { return }
        try await ChannelAttachments.ensureAuthorityMlsAttachmentsUploaded(
            pendingAttachmentDescriptors().values.flatMap { $0 }
        )
        try await session.activate(onApplication: { [weak self] application in
            guard let self else { throw AuthorityMlsMessagePayloadError.malformed }
            try await self.messageStore.append(application)
            try await onMessage(self.decode(application))
        }, onSecurityError: onSecurityError)
        active = true
    }

    func loadCachedMessages() async throws -> [AuthorityMlsChatMessage] {
        try await messageStore.load().map(decode)
    }

    /** Reads only the protected local cache; it does not register a device or touch Firestore. */
    static func loadCachedMessages(
        selfUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType,
        channelId: String
    ) async throws -> [AuthorityMlsChatMessage] {
        let conversationId = try AuthorityMlsIdentifiers.conversationId(
            AuthorityMlsBinding(
                scopeType: scopeType,
                channelId: channelId,
                participants: [selfUid, peerUid]
            )
        )
        let applications = try await AuthorityMlsMessageStore(conversationId: conversationId).load()
        return try applications.map {
            try decode($0, selfUid: selfUid, peerUid: peerUid)
        }
    }

    func send(_ payload: AuthorityMlsMessagePayload) async throws -> AuthorityMlsChatMessage {
        let staged = try await stage(payload)
        try await session.publishPendingApplications()
        return staged
    }

    /// Stages locally and advances the MLS sender ratchet once, without requiring cloud access.
    func stage(_ payload: AuthorityMlsMessagePayload) async throws -> AuthorityMlsChatMessage {
        guard active, payload.recipientUid == peerUid else { throw AuthorityMlsMessagePayloadError.malformed }
        let plaintext = try AuthorityMlsMessagePayloadCodec.encode(payload)
        let messageId = "m_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let ciphertext = try await session.queueApplicationForOfflineRelay(plaintext, messageId: messageId)
        let application = AuthorityMlsPendingReceivedApplication(
            messageId: messageId,
            senderCredential: ciphertext.senderCredential,
            plaintext: plaintext
        )
        try await messageStore.append(application)
        return try decode(application)
    }

    func publishPending() async throws {
        try await ChannelAttachments.ensureAuthorityMlsAttachmentsUploaded(
            pendingAttachmentDescriptors().values.flatMap { $0 }
        )
        try await session.publishPendingApplications()
    }

    func offlineEnvelope(messageId: String) async throws -> String {
        let ciphertext = try await session.pendingOfflineCiphertext(messageId: messageId)
        let conversationId = await session.conversationId
        return try AuthorityMlsOfflineEnvelopeCodec.encode(
            conversationId: conversationId,
            message: ciphertext
        )
    }

    func pendingOfflineEnvelopes() async throws -> [String: String] {
        let conversationId = await session.conversationId
        let messages = try await session.pendingOfflineCiphertexts()
        return try Dictionary(uniqueKeysWithValues: messages.map {
            ($0.messageId, try AuthorityMlsOfflineEnvelopeCodec.encode(
                conversationId: conversationId,
                message: $0
            ))
        })
    }

    func pendingAttachmentDescriptors() async throws -> [String: [ChannelAttachment]] {
        let pendingIds = Set(try await session.pendingOfflineCiphertexts().map(\.messageId))
        return Dictionary(uniqueKeysWithValues: try await messageStore.load()
            .filter { pendingIds.contains($0.messageId) }
            .map { application in
                (application.messageId, try AuthorityMlsMessagePayloadCodec.decode(application.plaintext).attachments)
            })
    }

    func acceptOfflineEnvelope(_ encoded: String) async throws {
        let conversationId = await session.conversationId
        guard let envelope = AuthorityMlsOfflineEnvelopeCodec.decode(encoded),
              envelope.conversationId == conversationId,
              envelope.message.senderUid == peerUid else { throw AuthorityMlsError.invalidContext }
        try await session.handleOfflineApplicationMessage(
            envelope.message,
            authenticatedPeerUid: peerUid
        )
    }

    func close() async {
        active = false
        await session.close()
    }

    private func decode(_ application: AuthorityMlsPendingReceivedApplication) throws -> AuthorityMlsChatMessage {
        try Self.decode(application, selfUid: selfUid, peerUid: peerUid)
    }

    private static func decode(
        _ application: AuthorityMlsPendingReceivedApplication,
        selfUid: String,
        peerUid: String
    ) throws -> AuthorityMlsChatMessage {
        guard let sender = AuthorityMlsCredential.decode(application.senderCredential)?.accountUid,
              sender == selfUid || sender == peerUid else {
            throw AuthorityMlsMessagePayloadError.malformed
        }
        let payload = try AuthorityMlsMessagePayloadCodec.decode(application.plaintext)
        let expectedRecipient = sender == selfUid ? peerUid : selfUid
        guard payload.recipientUid == expectedRecipient else { throw AuthorityMlsMessagePayloadError.malformed }
        return AuthorityMlsChatMessage(id: application.messageId, senderUid: sender, payload: payload)
    }
}
