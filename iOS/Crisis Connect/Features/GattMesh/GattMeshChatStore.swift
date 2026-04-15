//
//  GattMeshChatStore.swift
//  Crisis Connect
//
//  Created by Codex on 29.03.2026.
//

import Foundation
import Combine

enum GattMeshMessageStatus: String, Codable {
    case queued
    case sending
    case sent
    case delivered
    case read
    case failed

    var label: String {
        switch self {
        case .queued:
            return NSLocalizedString("GENERAL_CHAT_STATUS_QUEUED", comment: "")
        case .sending:
            return NSLocalizedString("GENERAL_CHAT_STATUS_SENDING", comment: "")
        case .sent:
            return NSLocalizedString("GENERAL_CHAT_STATUS_SENT", comment: "")
        case .delivered:
            return NSLocalizedString("GENERAL_CHAT_STATUS_DELIVERED", comment: "")
        case .read:
            return NSLocalizedString("GENERAL_CHAT_STATUS_READ", comment: "")
        case .failed:
            return NSLocalizedString("GENERAL_CHAT_STATUS_FAILED", comment: "")
        }
    }
}

struct GattMeshChatMessage: Identifiable, Codable, Equatable {
    let id: String
    var text: String
    var senderLabel: String?
    var sourcePeerId: String?
    var originVerifiedRole: String?
    var originVerifiedAtMillis: Int64?
    var isLocal: Bool
    var timestampMillis: Int64
    var receivedTimestampMillis: Int64?
    var status: GattMeshMessageStatus
    var deliveredTo: [String]
    var readBy: [String]
}

extension GattMeshChatMessage {
    var inlineReplyMetadata: P2pInlineReplyMetadata? {
        P2pSharedTransferSupport.parseReplyMetadata(text)
    }

    var visibleBodyText: String {
        P2pSharedTransferSupport.stripReplyMetadata(text) ?? text
    }

    var replyPreviewText: String {
        P2pSharedTransferSupport.previewTextForReplyTarget(text)
            ?? P2pSharedTransferSupport.previewText(for: text)
    }

    var copyableText: String? {
        let trimmedVisibleBody = visibleBodyText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedVisibleBody.isEmpty {
            return trimmedVisibleBody
        }
        let trimmedPreview = replyPreviewText.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedPreview.isEmpty ? nil : trimmedPreview
    }
}

@MainActor
final class GattMeshChatStore: ObservableObject {
    static let shared = GattMeshChatStore()
    static let sessionId = BroadcastSessionId.fromRawIdentifier("gattmesh:general")

    @Published private(set) var messages: [GattMeshChatMessage] = []
    @Published private(set) var unreadCount = 0

    private static let maxMessages = 500
    private static let maxMessageLength = 1_024
    private static let maxVerifiedRoleLength = 24
    private static let persistenceURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent("gatt_mesh_chat.json", isDirectory: false)
    }()

    private var isChatOpen = false
    private let persistenceQueue = DispatchQueue(label: "gattmesh.chat.persistence", qos: .utility)
    private var pendingPersistWorkItem: DispatchWorkItem?

    private init() {
        loadPersistedState()
    }

    func setChatOpen(_ isOpen: Bool) {
        isChatOpen = isOpen
        if isOpen {
            unreadCount = 0
            persistState()
        }
    }

    @discardableResult
    func appendLocalMessage(
        text: String,
        messageId: String,
        status: GattMeshMessageStatus
    ) -> GattMeshChatMessage {
        let resolvedText = sanitizeMessage(text)
        let resolvedId = GattMeshProtocol.normalizeMessageId(messageId) ?? UUID().uuidString

        if let existing = messages.first(where: { $0.id == resolvedId }) {
            return existing
        }

        let message = GattMeshChatMessage(
            id: resolvedId,
            text: resolvedText,
            senderLabel: nil,
            sourcePeerId: nil,
            originVerifiedRole: nil,
            originVerifiedAtMillis: nil,
            isLocal: true,
            timestampMillis: Int64(Date().timeIntervalSince1970 * 1_000),
            receivedTimestampMillis: nil,
            status: status,
            deliveredTo: [],
            readBy: []
        )
        messages = normalizeMessages(messages + [message])
        persistState()
        return message
    }

    func updateLocalMessageStatus(_ messageId: String, status: GattMeshMessageStatus) {
        guard let resolvedId = GattMeshProtocol.normalizeMessageId(messageId) else { return }
        var didChange = false
        messages = normalizeMessages(messages.map { message in
            guard message.isLocal, message.id == resolvedId else { return message }
            let mergedStatus = mergeStatus(current: message.status, next: status)
            guard mergedStatus != message.status else { return message }
            didChange = true
            var updated = message
            updated.status = mergedStatus
            return updated
        })
        if didChange {
            persistState()
        }
    }

    @discardableResult
    func appendRemoteMessage(
        id: String,
        text: String,
        senderLabel: String?,
        sourcePeerId: String?,
        originVerifiedRole: String? = nil,
        originVerifiedAtMillis: Int64? = nil,
        timestampMillis: Int64,
        receivedTimestampMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1_000)
    ) -> Bool {
        guard let resolvedId = GattMeshProtocol.normalizeMessageId(id) else { return false }
        let resolvedText = sanitizeMessage(text)
        guard !resolvedText.isEmpty else { return false }
        guard messages.contains(where: { $0.id == resolvedId }) == false else { return false }

        let message = GattMeshChatMessage(
            id: resolvedId,
            text: resolvedText,
            senderLabel: senderLabel
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .nilIfEmpty,
            sourcePeerId: sourcePeerId
                .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                .nilIfEmpty,
            originVerifiedRole: sanitizeVerifiedRole(originVerifiedRole),
            originVerifiedAtMillis: sanitizeVerifiedAtMillis(originVerifiedAtMillis),
            isLocal: false,
            timestampMillis: timestampMillis > 0 ? timestampMillis : receivedTimestampMillis,
            receivedTimestampMillis: receivedTimestampMillis,
            status: isChatOpen ? .read : .delivered,
            deliveredTo: [],
            readBy: []
        )

        messages = normalizeMessages(messages + [message])
        if !isChatOpen {
            unreadCount = min(Int.max, unreadCount + 1)
        }
        persistState()
        return true
    }

    func updateRemoteMessageMetadata(
        id: String,
        senderLabel: String?,
        sourcePeerId: String?,
        originVerifiedRole: String? = nil,
        originVerifiedAtMillis: Int64? = nil
    ) -> Bool {
        guard let resolvedId = GattMeshProtocol.normalizeMessageId(id) else { return false }

        let normalizedSenderLabel = senderLabel
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .nilIfEmpty
        let normalizedSourcePeerId = sourcePeerId
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .nilIfEmpty
        let normalizedVerifiedRole = sanitizeVerifiedRole(originVerifiedRole)
        let normalizedVerifiedAtMillis = sanitizeVerifiedAtMillis(originVerifiedAtMillis)

        var didChange = false
        messages = normalizeMessages(messages.map { message in
            guard !message.isLocal, message.id == resolvedId else { return message }

            let nextSenderLabel = message.senderLabel ?? normalizedSenderLabel
            let nextSourcePeerId = message.sourcePeerId ?? normalizedSourcePeerId
            let nextVerifiedRole = message.originVerifiedRole ?? normalizedVerifiedRole
            let nextVerifiedAtMillis = message.originVerifiedAtMillis ?? normalizedVerifiedAtMillis

            guard nextSenderLabel != message.senderLabel ||
                    nextSourcePeerId != message.sourcePeerId ||
                    nextVerifiedRole != message.originVerifiedRole ||
                    nextVerifiedAtMillis != message.originVerifiedAtMillis else {
                return message
            }

            didChange = true
            var updated = message
            updated.senderLabel = nextSenderLabel
            updated.sourcePeerId = nextSourcePeerId
            updated.originVerifiedRole = nextVerifiedRole
            updated.originVerifiedAtMillis = nextVerifiedAtMillis
            return updated
        })

        if didChange {
            persistState()
        }
        return didChange
    }

    func backfillOriginVerification(
        for sourcePeerId: String,
        originVerifiedRole: String,
        originVerifiedAtMillis: Int64
    ) -> Bool {
        let normalizedSourcePeerId = sourcePeerId
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedVerifiedRole = sanitizeVerifiedRole(originVerifiedRole)
        let normalizedVerifiedAtMillis = sanitizeVerifiedAtMillis(originVerifiedAtMillis)
        guard !normalizedSourcePeerId.isEmpty,
              let normalizedVerifiedRole,
              let normalizedVerifiedAtMillis else {
            return false
        }

        var didChange = false
        messages = normalizeMessages(messages.map { message in
            guard !message.isLocal,
                  message.sourcePeerId == normalizedSourcePeerId,
                  message.originVerifiedAtMillis == nil else {
                return message
            }

            didChange = true
            var updated = message
            updated.originVerifiedRole = normalizedVerifiedRole
            updated.originVerifiedAtMillis = normalizedVerifiedAtMillis
            return updated
        })

        if didChange {
            persistState()
        }
        return didChange
    }

    func markDelivered(messageIds: [String], recipientLabel: String?) {
        applyReceipt(type: .delivered, messageIds: messageIds, recipientLabel: recipientLabel)
    }

    func markRead(messageIds: [String], recipientLabel: String?) {
        applyReceipt(type: .read, messageIds: messageIds, recipientLabel: recipientLabel)
    }

    func unreadRemoteMessageIDs() -> [String] {
        messages.compactMap { message in
            guard !message.isLocal, message.status != .read else { return nil }
            return message.id
        }
    }

    func markAllRemoteMessagesRead() -> [String] {
        let targetIds = Set(unreadRemoteMessageIDs())
        guard !targetIds.isEmpty else { return [] }

        messages = normalizeMessages(messages.map { message in
            guard !message.isLocal, targetIds.contains(message.id) else { return message }
            var updated = message
            updated.status = .read
            return updated
        })
        unreadCount = 0
        persistState()
        return Array(targetIds)
    }

    private func applyReceipt(
        type: GattMeshReceiptType,
        messageIds: [String],
        recipientLabel: String?
    ) {
        let resolvedIds = Set(GattMeshProtocol.normalizeMessageIds(messageIds))
        guard !resolvedIds.isEmpty else { return }

        let normalizedRecipient = recipientLabel
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .nilIfEmpty
        var didChange = false

        messages = normalizeMessages(messages.map { message in
            guard message.isLocal, resolvedIds.contains(message.id) else { return message }

            var updated = message
            switch type {
            case .delivered:
                let nextStatus = mergeStatus(current: updated.status, next: .delivered)
                if nextStatus != updated.status {
                    updated.status = nextStatus
                    didChange = true
                }
                let nextDeliveredTo = mergeRecipientList(existing: updated.deliveredTo, recipient: normalizedRecipient)
                if nextDeliveredTo != updated.deliveredTo {
                    updated.deliveredTo = nextDeliveredTo
                    didChange = true
                }

            case .read:
                let nextStatus = mergeStatus(current: updated.status, next: .read)
                if nextStatus != updated.status {
                    updated.status = nextStatus
                    didChange = true
                }
                let nextReadBy = mergeRecipientList(existing: updated.readBy, recipient: normalizedRecipient)
                if nextReadBy != updated.readBy {
                    updated.readBy = nextReadBy
                    didChange = true
                }
            }

            return updated
        })

        if didChange {
            persistState()
        }
    }

    private func normalizeMessages(_ input: [GattMeshChatMessage]) -> [GattMeshChatMessage] {
        let deduplicated = Dictionary(grouping: input, by: \.id)
            .compactMap { _, values in values.last }
            .sorted { lhs, rhs in
                if lhs.timestampMillis == rhs.timestampMillis {
                    return lhs.id < rhs.id
                }
                return lhs.timestampMillis < rhs.timestampMillis
            }
        return Array(deduplicated.suffix(Self.maxMessages))
    }

    private func mergeStatus(
        current: GattMeshMessageStatus,
        next: GattMeshMessageStatus
    ) -> GattMeshMessageStatus {
        if current == .read || next == .read {
            return .read
        }
        if current == .delivered && next == .sent {
            return current
        }
        if current == .sent && next == .failed {
            return current
        }
        if current == .delivered && next == .failed {
            return current
        }
        if current == .queued && next == .failed {
            return .failed
        }
        if current == .sending && next == .failed {
            return .failed
        }

        let rank: [GattMeshMessageStatus: Int] = [
            .queued: 0,
            .sending: 1,
            .sent: 2,
            .delivered: 3,
            .read: 4,
            .failed: -1
        ]
        return (rank[next] ?? 0) >= (rank[current] ?? 0) ? next : current
    }

    private func mergeRecipientList(existing: [String], recipient: String?) -> [String] {
        guard let recipient else { return existing }
        guard existing.contains(recipient) == false else { return existing }
        return existing + [recipient]
    }

    private func sanitizeMessage(_ rawValue: String) -> String {
        let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(Self.maxMessageLength))
    }

    private func sanitizeVerifiedRole(_ rawValue: String?) -> String? {
        let trimmed = rawValue?.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let trimmed, !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(Self.maxVerifiedRoleLength))
    }

    private func sanitizeVerifiedAtMillis(_ rawValue: Int64?) -> Int64? {
        guard let rawValue, rawValue > 0 else { return nil }
        return rawValue
    }

    private func loadPersistedState() {
        guard let payload = try? LocalEncryptedFileStore.read(from: Self.persistenceURL) else {
            return
        }
        guard let snapshot = try? JSONDecoder().decode(Snapshot.self, from: payload.data) else {
            return
        }
        messages = normalizeMessages(snapshot.messages)
        unreadCount = max(0, snapshot.unreadCount)
    }

    private func persistState() {
        let snapshot = Snapshot(
            messages: messages,
            unreadCount: isChatOpen ? 0 : unreadCount
        )
        pendingPersistWorkItem?.cancel()
        let workItem = DispatchWorkItem {
            guard let data = try? JSONEncoder().encode(snapshot) else { return }
            try? LocalEncryptedFileStore.write(data, to: Self.persistenceURL)
        }
        pendingPersistWorkItem = workItem
        persistenceQueue.asyncAfter(deadline: .now() + 0.2, execute: workItem)
    }

    private struct Snapshot: Codable {
        let messages: [GattMeshChatMessage]
        let unreadCount: Int
    }
}

private extension Optional where Wrapped == String {
    var nilIfEmpty: String? {
        guard let value = self?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty else {
            return nil
        }
        return value
    }
}
