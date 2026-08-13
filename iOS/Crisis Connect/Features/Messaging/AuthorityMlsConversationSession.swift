import Foundation

struct AuthorityMlsPreparation: Sendable {
    let conversationId: String
    let creatorCredential: String
    let localCredential: String
    let safetyNumber: String?
    let trust: [AuthorityMlsTrustAssessment]
    let rejectedDirectoryRecords: Int

    var ready: Bool {
        rejectedDirectoryRecords == 0 && !trust.isEmpty && trust.allSatisfy(\.approved)
    }
}

struct AuthorityMlsVerificationRequiredError: Error, Sendable {
    let preparation: AuthorityMlsPreparation
}

/// Holds one live in-memory ratchet owner per account/conversation. Durable snapshots serialize
/// writes, but two actors restored from different snapshots must never advance the same context.
private actor AuthorityMlsSessionLeaseRegistry {
    static let shared = AuthorityMlsSessionLeaseRegistry()

    private var held = Set<String>()
    private var waiters: [String: [CheckedContinuation<Void, Never>]] = [:]

    func acquire(_ key: String) async {
        if held.insert(key).inserted { return }
        await withCheckedContinuation { continuation in
            waiters[key, default: []].append(continuation)
        }
    }

    func release(_ key: String) {
        if var queued = waiters[key], !queued.isEmpty {
            let next = queued.removeFirst()
            if queued.isEmpty { waiters.removeValue(forKey: key) } else { waiters[key] = queued }
            next.resume()
        } else {
            held.remove(key)
        }
    }
}

/// One serialized, crash-safe iOS AuthorityChat MLS v2 conversation.
actor AuthorityMlsConversationSession {
    private let accountUid: String
    private let participants: [String]
    let conversationId: String
    let creatorCredential: String
    private let crypto: PersistentAuthorityMlsContext
    private let transport: AuthorityMlsTransport
    private let trustStore: AuthorityMlsTrustStore
    private let localIdentity: AuthorityMlsDeviceIdentity
    private var cursor: Int64
    private var controlOutbox: [String]
    private var applicationOutbox: [AuthorityMlsPendingApplication]
    private var nextApplicationSequence: Int64
    private var receivedInbox: [AuthorityMlsPendingReceivedApplication]
    private var safetyNumber: String?
    private var subscription: AuthorityMlsControlSubscription?
    private var applicationSubscription: AuthorityMlsControlSubscription?
    private var errorHandler: @Sendable (Error) -> Void = { _ in }
    private var applicationHandler: (@Sendable (AuthorityMlsPendingReceivedApplication) async throws -> Void)?
    private var awaitingWelcome: Bool
    private var closed = false
    private let sessionLeaseKey: String

    private init(
        accountUid: String,
        participants: [String],
        conversationId: String,
        creatorCredential: String,
        crypto: PersistentAuthorityMlsContext,
        transport: AuthorityMlsTransport,
        trustStore: AuthorityMlsTrustStore,
        localIdentity: AuthorityMlsDeviceIdentity,
        initial: AuthorityMlsStep,
        sessionLeaseKey: String
    ) {
        self.accountUid = accountUid
        self.participants = participants
        self.conversationId = conversationId
        self.creatorCredential = creatorCredential
        self.crypto = crypto
        self.transport = transport
        self.trustStore = trustStore
        self.localIdentity = localIdentity
        self.sessionLeaseKey = sessionLeaseKey
        cursor = initial.nextControlSequence
        controlOutbox = initial.broadcasts
        applicationOutbox = initial.pendingApplicationMessages
        nextApplicationSequence = initial.nextApplicationSequence
        receivedInbox = initial.pendingReceivedApplications
        safetyNumber = initial.safetyNumber
        awaitingWelcome = creatorCredential != localIdentity.credential && initial.safetyNumber == nil
    }

    static func prepare(
        accountUid: String,
        binding: AuthorityMlsBinding,
        deviceLabel: String,
        transport: AuthorityMlsTransport = AuthorityMlsTransport()
    ) async throws -> AuthorityMlsConversationSession {
        let canonical = try AuthorityMlsIdentifiers.canonicalBinding(binding)
        let conversationId = try AuthorityMlsIdentifiers.conversationId(canonical)
        let leaseKey = "\(accountUid)\u{0}\(conversationId)"
        await AuthorityMlsSessionLeaseRegistry.shared.acquire(leaseKey)
        do {
            try Task.checkCancellation()
            let crypto = try PersistentAuthorityMlsContext(accountUid: accountUid, channelId: conversationId)
            let handle = try await transport.ensureConversation(
                canonical,
                creatorUid: accountUid,
                candidateCreatorCredential: crypto.credential
            )
            guard handle.conversationId == conversationId else { throw AuthorityMlsError.identityMismatch }
            var initial = try await crypto.restoreOrInitialize(isCreator: handle.creatorCredential == crypto.credential)
            if handle.creatorCredential != crypto.credential,
               initial.safetyNumber == nil,
               initial.nextControlSequence < handle.nextControlSequence ||
                initial.nextApplicationSequence < handle.nextApplicationSequence {
                initial = try await crypto.alignFreshJoinRelayCursors(
                    nextControl: handle.nextControlSequence,
                    nextApplication: handle.nextApplicationSequence
                )
            }
            let identity = try await crypto.localIdentity()
            try await transport.registerDevice(
                conversationId: conversationId,
                record: AuthorityMlsDirectoryRecord(
                    uid: accountUid,
                    deviceId: identity.deviceId,
                    credential: identity.credential,
                    signingPublicKey: identity.signingPublicKey,
                    label: String(deviceLabel.prefix(64))
                )
            )
            return AuthorityMlsConversationSession(
                accountUid: accountUid,
                participants: canonical.participants,
                conversationId: conversationId,
                creatorCredential: handle.creatorCredential,
                crypto: crypto,
                transport: transport,
                trustStore: AuthorityMlsTrustStore(),
                localIdentity: identity,
                initial: initial,
                sessionLeaseKey: leaseKey
            )
        } catch {
            await AuthorityMlsSessionLeaseRegistry.shared.release(leaseKey)
            throw error
        }
    }

    func refreshPreparation() async throws -> AuthorityMlsPreparation {
        try ensureOpen()
        return try await preparation()
    }

    func approveDeviceSet(uid: String, expectedFingerprint: String) async throws -> AuthorityMlsPreparation {
        try ensureOpen()
        guard participants.contains(uid) else { throw AuthorityMlsError.unverifiedDevice }
        let directory = try await transport.loadDeviceDirectory(conversationId: conversationId)
        let assessment = try trustStore.assess(
            conversationId: conversationId,
            uid: uid,
            devices: directory.records.filter { $0.uid == uid }
        )
        guard assessment.fingerprint == expectedFingerprint,
              !assessment.deviceCommitments.isEmpty else { throw AuthorityMlsError.unverifiedDevice }
        try trustStore.approve(
            conversationId: conversationId,
            uid: uid,
            expectedFingerprint: expectedFingerprint,
            deviceCommitments: assessment.deviceCommitments
        )
        return try await preparation(directory)
    }

    /// Directory trust alone is not enough: wait for every authenticated device to join the MLS roster.
    func isReadyToSend() async throws -> Bool {
        try ensureOpen()
        let directory = try await verifiedDirectory()
        guard let roster = try? await crypto.verifiedRoster(directory) else { return false }
        return Set(roster.map(\.credential)) == Set(directory.keys)
    }

    func activate(
        onApplication: @escaping @Sendable (AuthorityMlsPendingReceivedApplication) async throws -> Void,
        onSecurityError: @escaping @Sendable (Error) -> Void = { _ in }
    ) async throws {
        try ensureOpen()
        if subscription != nil { return }
        errorHandler = onSecurityError
        applicationHandler = onApplication
        _ = try await verifiedDirectory()
        try await deliverInbox(onApplication)
        subscription = try transport.listenControlEvents(
            conversationId: conversationId,
            fromSequence: cursor,
            onEvent: { [weak self] event in try await self?.handleControlEvent(event) },
            onError: onSecurityError
        )
        try startApplicationListener()
        await flushControl()
        try await flushApplications(requireCompleteRoster: true)
    }

    /// Queues, persists and idempotently publishes one already-serialized AuthorityChat payload.
    func sendApplication(_ plaintext: Data, messageId: String? = nil) async throws -> String {
        try ensureOpen()
        guard subscription != nil, applicationSubscription != nil else { throw AuthorityMlsError.invalidContext }
        let directory = try await verifiedDirectory()
        let roster = try await crypto.verifiedRoster(directory)
        guard Set(roster.map(\.credential)) == Set(directory.keys) else {
            throw AuthorityMlsError.unverifiedDevice
        }
        let entry = if let messageId {
            try await crypto.queueApplication(plaintext, messageId: messageId)
        } else {
            try await crypto.queueApplication(plaintext)
        }
        applicationOutbox.append(entry)
        try await flushApplications(requireCompleteRoster: false)
        return entry.messageId
    }

    /// Queues once without cloud access and exposes only the immutable MLS ciphertext to Bluetooth.
    func queueApplicationForOfflineRelay(
        _ plaintext: Data,
        messageId: String
    ) async throws -> AuthorityMlsCiphertextMessage {
        try ensureOpen()
        guard applicationHandler != nil else { throw AuthorityMlsError.invalidContext }
        let roster = try await crypto.authenticatedRoster()
        guard Set(roster.map(\.accountUid)) == Set(participants),
              roster.contains(where: { $0.credential == localIdentity.credential }) else {
            throw AuthorityMlsError.unverifiedDevice
        }
        let entry: AuthorityMlsPendingApplication
        if let existing = applicationOutbox.first(where: { $0.messageId == messageId }) {
            entry = existing
        } else {
            entry = try await crypto.queueApplication(plaintext, messageId: messageId)
            applicationOutbox.append(entry)
        }
        return AuthorityMlsCiphertextMessage(
            messageId: entry.messageId,
            senderUid: accountUid,
            senderDeviceId: localIdentity.deviceId,
            senderCredential: localIdentity.credential,
            ciphertext: entry.ciphertext
        )
    }

    func pendingOfflineCiphertext(messageId: String) throws -> AuthorityMlsCiphertextMessage {
        try ensureOpen()
        guard let entry = applicationOutbox.first(where: { $0.messageId == messageId }) else {
            throw AuthorityMlsError.invalidContext
        }
        return AuthorityMlsCiphertextMessage(
            messageId: entry.messageId,
            senderUid: accountUid,
            senderDeviceId: localIdentity.deviceId,
            senderCredential: localIdentity.credential,
            ciphertext: entry.ciphertext
        )
    }

    func pendingOfflineCiphertexts() throws -> [AuthorityMlsCiphertextMessage] {
        try ensureOpen()
        return applicationOutbox.map {
            AuthorityMlsCiphertextMessage(
                messageId: $0.messageId,
                senderUid: accountUid,
                senderDeviceId: localIdentity.deviceId,
                senderCredential: localIdentity.credential,
                ciphertext: $0.ciphertext
            )
        }
    }

    func publishPendingApplications() async throws {
        try ensureOpen()
        try await flushApplications(requireCompleteRoster: false)
    }

    /// Accepts one same-MLS packet from the P2P-authenticated nearby account, without cloud order.
    func handleOfflineApplicationMessage(
        _ message: AuthorityMlsCiphertextMessage,
        authenticatedPeerUid: String
    ) async throws {
        try ensureOpen()
        guard !awaitingWelcome,
              let parsed = AuthorityMlsCredential.decode(message.senderCredential),
              message.senderUid == authenticatedPeerUid,
              parsed.accountUid == authenticatedPeerUid,
              parsed.deviceId == message.senderDeviceId,
              let handler = applicationHandler else { throw AuthorityMlsError.unverifiedDevice }
        receivedInbox = try await crypto.processOfflineApplicationMessage(
            messageId: message.messageId,
            senderCredential: message.senderCredential,
            ciphertext: message.ciphertext,
            authenticatedPeerUid: authenticatedPeerUid
        )
        try await deliverInbox(handler)
    }

    func close() async {
        if closed { return }
        closed = true
        subscription?.cancel()
        applicationSubscription?.cancel()
        subscription = nil
        applicationSubscription = nil
        try? await crypto.close()
        await AuthorityMlsSessionLeaseRegistry.shared.release(sessionLeaseKey)
    }

    private func handleControlEvent(_ event: AuthorityMlsControlEvent) async throws {
        try ensureOpen()
        if event.sequence < cursor { return }
        guard event.sequence == cursor else { throw AuthorityMlsError.invalidWorkerResponse }
        if event.senderCredential == localIdentity.credential {
            guard controlOutbox.first == event.payload else { throw AuthorityMlsError.invalidWorkerResponse }
            let outbox = try await crypto.acknowledgePublishedControlEvent(
                sequence: event.sequence,
                payload: event.payload
            )
            cursor = outbox.nextControlSequence
            controlOutbox = outbox.pendingBroadcasts
        } else {
            guard let ordering = authorityMlsControlOrdering(event.payload) else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            if !awaitingWelcome, ordering.type == "sendMlsMessage" {
                guard let boundary = ordering.applicationSequenceBoundary else {
                    throw AuthorityMlsError.invalidWorkerResponse
                }
                try await catchUpApplications(before: boundary)
            }
            let relayApplicationSequence = try await transport.loadApplicationSequence(
                conversationId: conversationId
            )
            let step = try await crypto.processHandshake(
                event.payload,
                authenticatedSenderUid: event.senderUid,
                verifiedDirectory: try await verifiedDirectory(),
                sequence: event.sequence,
                relayApplicationSequence: relayApplicationSequence
            )
            cursor = step.nextControlSequence
            controlOutbox = step.broadcasts
            applicationOutbox = step.pendingApplicationMessages
            safetyNumber = step.safetyNumber ?? safetyNumber
            guard step.nextApplicationSequence >= nextApplicationSequence,
                  step.nextApplicationSequence <= relayApplicationSequence else {
                throw AuthorityMlsError.invalidWorkerResponse
            }
            nextApplicationSequence = step.nextApplicationSequence
            if awaitingWelcome, ordering.type == "sendMlsWelcome" {
                guard step.safetyNumber != nil else { throw AuthorityMlsError.invalidWorkerResponse }
                awaitingWelcome = false
                try startApplicationListener()
            }
        }
        await flushControl()
    }

    private func handleApplicationMessage(
        _ message: AuthorityMlsCiphertextMessage,
        onApplication: @escaping @Sendable (AuthorityMlsPendingReceivedApplication) async throws -> Void,
        synchronizeControl: Bool = true
    ) async throws {
        try ensureOpen()
        if synchronizeControl { try await synchronizeControlsBeforeApplication() }
        if message.sequence < nextApplicationSequence { return }
        guard message.sequence == nextApplicationSequence else { throw AuthorityMlsError.invalidWorkerResponse }
        guard !awaitingWelcome else { throw AuthorityMlsError.invalidWorkerResponse }
        receivedInbox = try await crypto.processApplicationMessage(
            sequence: message.sequence,
            messageId: message.messageId,
            senderCredential: message.senderCredential,
            ciphertext: message.ciphertext,
            verifiedDirectory: try await verifiedDirectory()
        )
        nextApplicationSequence = message.sequence + 1
        try await deliverInbox(onApplication)
    }

    private func synchronizeControlsBeforeApplication() async throws {
        while true {
            let page = try await transport.loadControlEventsFrom(
                conversationId: conversationId,
                fromSequence: cursor
            )
            if page.isEmpty { return }
            let before = cursor
            for event in page { try await handleControlEvent(event) }
            guard cursor > before else { throw AuthorityMlsError.invalidWorkerResponse }
            if page.count < 100 { return }
        }
    }

    private func deliverInbox(
        _ onApplication: @escaping @Sendable (AuthorityMlsPendingReceivedApplication) async throws -> Void
    ) async throws {
        while let head = receivedInbox.first {
            try await onApplication(head)
            receivedInbox = try await crypto.acknowledgeDeliveredApplication(messageId: head.messageId)
        }
    }

    private func startApplicationListener() throws {
        guard !awaitingWelcome, applicationSubscription == nil, let applicationHandler else { return }
        applicationSubscription = try transport.listenCiphertexts(
            conversationId: conversationId,
            fromSequence: nextApplicationSequence,
            onMessage: { [weak self] message in
                try await self?.handleApplicationMessage(message, onApplication: applicationHandler)
            },
            onError: errorHandler
        )
    }

    private func catchUpApplications(before boundary: Int64) async throws {
        guard boundary >= nextApplicationSequence, let applicationHandler else {
            throw AuthorityMlsError.invalidWorkerResponse
        }
        while nextApplicationSequence < boundary {
            let page = try await transport.loadCiphertextsBefore(
                conversationId: conversationId,
                fromSequence: nextApplicationSequence,
                beforeSequence: boundary
            )
            guard !page.isEmpty else { throw AuthorityMlsError.invalidWorkerResponse }
            for message in page {
                try await handleApplicationMessage(
                    message,
                    onApplication: applicationHandler,
                    synchronizeControl: false
                )
            }
        }
    }

    private func flushControl() async {
        while let payload = controlOutbox.first {
            do {
                _ = try await transport.publishControlEvent(
                    conversationId: conversationId,
                    sequence: cursor,
                    senderUid: accountUid,
                    senderDeviceId: localIdentity.deviceId,
                    senderCredential: localIdentity.credential,
                    payload: payload
                )
                let outbox = try await crypto.acknowledgePublishedControlEvent(
                    sequence: cursor,
                    payload: payload
                )
                cursor = outbox.nextControlSequence
                controlOutbox = outbox.pendingBroadcasts
            } catch {
                errorHandler(error)
                return
            }
        }
    }

    private func flushApplications(requireCompleteRoster: Bool) async throws {
        if applicationOutbox.isEmpty { return }
        if requireCompleteRoster {
            let directory = try await verifiedDirectory()
            guard let roster = try? await crypto.verifiedRoster(directory),
                  Set(roster.map(\.credential)) == Set(directory.keys) else { return }
        }
        while let head = applicationOutbox.first {
            _ = try await transport.publishCiphertext(
                conversationId: conversationId,
                message: AuthorityMlsCiphertextMessage(
                    messageId: head.messageId,
                    senderUid: accountUid,
                    senderDeviceId: localIdentity.deviceId,
                    senderCredential: localIdentity.credential,
                    ciphertext: head.ciphertext
                )
            )
            applicationOutbox = try await crypto.acknowledgePublishedApplication(
                messageId: head.messageId,
                ciphertext: head.ciphertext
            ).pendingMessages
        }
    }

    private func preparation(_ loaded: AuthorityMlsDirectoryResult? = nil) async throws -> AuthorityMlsPreparation {
        let directory: AuthorityMlsDirectoryResult
        if let loaded {
            directory = loaded
        } else {
            directory = try await transport.loadDeviceDirectory(conversationId: conversationId)
        }
        let grouped = Dictionary(grouping: directory.records, by: \.uid)
        let assessments = try participants.map { uid in
            try trustStore.assess(
                conversationId: conversationId,
                uid: uid,
                devices: grouped[uid] ?? []
            )
        }
        return AuthorityMlsPreparation(
            conversationId: conversationId,
            creatorCredential: creatorCredential,
            localCredential: localIdentity.credential,
            safetyNumber: safetyNumber,
            trust: assessments,
            rejectedDirectoryRecords: directory.rejected
        )
    }

    private func verifiedDirectory() async throws -> [String: Data] {
        let directory = try await transport.loadDeviceDirectory(conversationId: conversationId)
        let state = try await preparation(directory)
        guard state.ready else { throw AuthorityMlsVerificationRequiredError(preparation: state) }
        return Dictionary(uniqueKeysWithValues: directory.records.map { ($0.credential, $0.signingPublicKey) })
    }

    private func ensureOpen() throws {
        if closed { throw AuthorityMlsError.invalidContext }
    }
}

private struct AuthorityMlsControlOrdering {
    let type: String
    let applicationSequenceBoundary: Int64?
}

private func authorityMlsControlOrdering(_ payload: String) -> AuthorityMlsControlOrdering? {
    guard let data = payload.data(using: .utf8),
          let envelope = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
          let type = envelope["type"] as? String else { return nil }
    if type == "shareKeyPackage" {
        return envelope["applicationSequenceBoundary"] == nil
            ? AuthorityMlsControlOrdering(type: type, applicationSequenceBoundary: nil)
            : nil
    }
    guard type == "sendMlsWelcome" || type == "sendMlsMessage",
          let number = envelope["applicationSequenceBoundary"] as? NSNumber else { return nil }
    let boundary = number.int64Value
    guard number.doubleValue == Double(boundary), boundary >= 0,
          boundary < 9_007_199_254_740_991 else { return nil }
    return AuthorityMlsControlOrdering(type: type, applicationSequenceBoundary: boundary)
}
