//
//  SOSChatStore.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import Foundation
import Combine
import UIKit

enum SOSChatRole: String, Codable, Sendable {
    case fieldTeam
    case victim
    case unknown
}

enum SOSChatMessageStatus: String, Codable, Sendable {
    case pending
    case sent
    case delivered
    case read
}

enum SOSChatMessageKind: String, Codable, Sendable {
    case text
    case audio
    case image
    case location
    case call
}

enum SOSChatCallDirection: String, Codable, Sendable {
    case incoming
    case outgoing
}

enum SOSChatCallResult: String, Codable, Sendable {
    case answered
    case missed
    case rejected
    case canceled
}

struct SOSChatMessage: Identifiable, Equatable, Codable, Sendable {
    let id: UUID
    let sessionId: UUID
    let kind: SOSChatMessageKind
    let text: String
    let audioRelativePath: String?
    let audioDurationMillis: Int?
    let imageRelativePath: String?
    let imageThumbnailRelativePath: String?
    let imageWidth: Int?
    let imageHeight: Int?
    let imageMimeType: String?
    let callDirection: SOSChatCallDirection?
    let callResult: SOSChatCallResult?
    let callDurationMillis: Int?
    let locationLatitude: Double?
    let locationLongitude: Double?
    let locationHorizontalAccuracyMeters: Double?
    let isLocal: Bool
    let timestamp: Date
    var status: SOSChatMessageStatus
    let transportMessageId: String?

    init(
        id: UUID,
        sessionId: UUID,
        kind: SOSChatMessageKind = .text,
        text: String,
        audioRelativePath: String? = nil,
        audioDurationMillis: Int? = nil,
        imageRelativePath: String? = nil,
        imageThumbnailRelativePath: String? = nil,
        imageWidth: Int? = nil,
        imageHeight: Int? = nil,
        imageMimeType: String? = nil,
        callDirection: SOSChatCallDirection? = nil,
        callResult: SOSChatCallResult? = nil,
        callDurationMillis: Int? = nil,
        locationLatitude: Double? = nil,
        locationLongitude: Double? = nil,
        locationHorizontalAccuracyMeters: Double? = nil,
        isLocal: Bool,
        timestamp: Date,
        status: SOSChatMessageStatus,
        transportMessageId: String?
    ) {
        self.id = id
        self.sessionId = sessionId
        self.kind = kind
        self.text = text
        let normalizedAudioRelativePath = audioRelativePath?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.audioRelativePath = (normalizedAudioRelativePath?.isEmpty == false) ? normalizedAudioRelativePath : nil
        self.audioDurationMillis = audioDurationMillis
        let normalizedImageRelativePath = imageRelativePath?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.imageRelativePath = (normalizedImageRelativePath?.isEmpty == false) ? normalizedImageRelativePath : nil
        let normalizedThumbnailRelativePath = imageThumbnailRelativePath?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.imageThumbnailRelativePath = (normalizedThumbnailRelativePath?.isEmpty == false) ? normalizedThumbnailRelativePath : nil
        self.imageWidth = imageWidth
        self.imageHeight = imageHeight
        let normalizedImageMimeType = imageMimeType?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.imageMimeType = (normalizedImageMimeType?.isEmpty == false) ? normalizedImageMimeType : nil
        self.callDirection = callDirection
        self.callResult = callResult
        self.callDurationMillis = callDurationMillis.map { max(0, $0) }
        self.locationLatitude = locationLatitude
        self.locationLongitude = locationLongitude
        self.locationHorizontalAccuracyMeters = locationHorizontalAccuracyMeters
        self.isLocal = isLocal
        self.timestamp = timestamp
        self.status = status
        let normalizedTransportMessageId = transportMessageId?.trimmingCharacters(in: .whitespacesAndNewlines)
        self.transportMessageId = (normalizedTransportMessageId?.isEmpty == false) ? normalizedTransportMessageId : nil
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case sessionId
        case kind
        case text
        case audioRelativePath
        case audioDurationMillis
        case imageRelativePath
        case imageThumbnailRelativePath
        case imageWidth
        case imageHeight
        case imageMimeType
        case callDirection
        case callResult
        case callDurationMillis
        case locationLatitude
        case locationLongitude
        case locationHorizontalAccuracyMeters
        case isLocal
        case timestamp
        case status
        case transportMessageId
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(UUID.self, forKey: .id)
        sessionId = try container.decode(UUID.self, forKey: .sessionId)
        kind = try container.decodeIfPresent(SOSChatMessageKind.self, forKey: .kind) ?? .text
        text = try container.decodeIfPresent(String.self, forKey: .text) ?? ""
        let decodedAudioRelativePath = (try container.decodeIfPresent(String.self, forKey: .audioRelativePath))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        audioRelativePath = (decodedAudioRelativePath?.isEmpty == false) ? decodedAudioRelativePath : nil
        audioDurationMillis = try container.decodeIfPresent(Int.self, forKey: .audioDurationMillis)
        let decodedImageRelativePath = (try container.decodeIfPresent(String.self, forKey: .imageRelativePath))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        imageRelativePath = (decodedImageRelativePath?.isEmpty == false) ? decodedImageRelativePath : nil
        let decodedThumbnailRelativePath = (try container.decodeIfPresent(String.self, forKey: .imageThumbnailRelativePath))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        imageThumbnailRelativePath = (decodedThumbnailRelativePath?.isEmpty == false) ? decodedThumbnailRelativePath : nil
        imageWidth = try container.decodeIfPresent(Int.self, forKey: .imageWidth)
        imageHeight = try container.decodeIfPresent(Int.self, forKey: .imageHeight)
        let decodedImageMimeType = (try container.decodeIfPresent(String.self, forKey: .imageMimeType))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        imageMimeType = (decodedImageMimeType?.isEmpty == false) ? decodedImageMimeType : nil
        callDirection = try container.decodeIfPresent(SOSChatCallDirection.self, forKey: .callDirection)
        callResult = try container.decodeIfPresent(SOSChatCallResult.self, forKey: .callResult)
        callDurationMillis = try container.decodeIfPresent(Int.self, forKey: .callDurationMillis)
        locationLatitude = try container.decodeIfPresent(Double.self, forKey: .locationLatitude)
        locationLongitude = try container.decodeIfPresent(Double.self, forKey: .locationLongitude)
        locationHorizontalAccuracyMeters = try container.decodeIfPresent(Double.self, forKey: .locationHorizontalAccuracyMeters)
        isLocal = try container.decode(Bool.self, forKey: .isLocal)
        timestamp = try container.decode(Date.self, forKey: .timestamp)
        status = try container.decode(SOSChatMessageStatus.self, forKey: .status)
        let decodedTransportMessageId = (try container.decodeIfPresent(String.self, forKey: .transportMessageId))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        transportMessageId = (decodedTransportMessageId?.isEmpty == false) ? decodedTransportMessageId : nil
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(id, forKey: .id)
        try container.encode(sessionId, forKey: .sessionId)
        try container.encode(kind, forKey: .kind)
        try container.encode(text, forKey: .text)
        try container.encodeIfPresent(audioRelativePath, forKey: .audioRelativePath)
        try container.encodeIfPresent(audioDurationMillis, forKey: .audioDurationMillis)
        try container.encodeIfPresent(imageRelativePath, forKey: .imageRelativePath)
        try container.encodeIfPresent(imageThumbnailRelativePath, forKey: .imageThumbnailRelativePath)
        try container.encodeIfPresent(imageWidth, forKey: .imageWidth)
        try container.encodeIfPresent(imageHeight, forKey: .imageHeight)
        try container.encodeIfPresent(imageMimeType, forKey: .imageMimeType)
        try container.encodeIfPresent(callDirection, forKey: .callDirection)
        try container.encodeIfPresent(callResult, forKey: .callResult)
        try container.encodeIfPresent(callDurationMillis, forKey: .callDurationMillis)
        try container.encodeIfPresent(locationLatitude, forKey: .locationLatitude)
        try container.encodeIfPresent(locationLongitude, forKey: .locationLongitude)
        try container.encodeIfPresent(locationHorizontalAccuracyMeters, forKey: .locationHorizontalAccuracyMeters)
        try container.encode(isLocal, forKey: .isLocal)
        try container.encode(timestamp, forKey: .timestamp)
        try container.encode(status, forKey: .status)
        try container.encodeIfPresent(transportMessageId, forKey: .transportMessageId)
    }
}

extension SOSChatMessage {
    var callEventPreviewText: String? {
        guard kind == .call,
              let callDirection,
              let callResult else {
            return nil
        }
        return SOSChatStore.callEventPreviewText(direction: callDirection, result: callResult)
    }

    var inlineReplyMetadata: P2pInlineReplyMetadata? {
        guard kind == .text else { return nil }
        return P2pSharedTransferSupport.parseReplyMetadata(text)
    }

    var visibleTextPreview: String {
        guard kind == .text else { return text }
        return P2pSharedTransferSupport.previewText(for: text)
    }

    var replyPreviewText: String {
        switch kind {
        case .text:
            return P2pSharedTransferSupport.previewTextForReplyTarget(text)
                ?? P2pSharedTransferSupport.previewText(for: text)
        case .audio:
            return SOSChatStore.voicePreviewText()
        case .image:
            return SOSChatStore.imagePreviewText()
        case .location:
            return SOSChatStore.locationPreviewText()
        case .call:
            return callEventPreviewText
                ?? NSLocalizedString("SOS_CHAT_REPLY_UNKNOWN_PLACEHOLDER", comment: "")
        }
    }

    var copyableText: String? {
        let resolvedText: String
        switch kind {
        case .text:
            resolvedText = visibleTextPreview
        case .audio:
            resolvedText = SOSChatStore.voicePreviewText()
        case .image:
            resolvedText = SOSChatStore.imagePreviewText()
        case .location:
            resolvedText = SOSChatStore.locationPreviewText()
        case .call:
            resolvedText = callEventPreviewText
                ?? NSLocalizedString("SOS_CHAT_REPLY_UNKNOWN_PLACEHOLDER", comment: "")
        }
        let trimmed = resolvedText.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

struct SOSChatSession: Identifiable, Equatable, Codable, Sendable {
    let id: UUID
    var displayName: String
    var role: SOSChatRole
    var lastUpdated: Date
    var lastMessage: String?
    var isVerified: Bool
    var unreadCount: Int
    var avatarInitials: String?
    var avatarHue: Double?
    var avatarImageRelativePath: String?

    var showsVerificationBadge: Bool {
        isVerified
    }
}

final class SOSChatStore: ObservableObject {
    static let shared = SOSChatStore()

    nonisolated private static let voiceMessagesDirectoryName = "voice_messages"
    nonisolated private static let imageMessagesDirectoryName = "image_messages"
    nonisolated private static let imageThumbnailsDirectoryName = "image_thumbnails"
    nonisolated private static let sessionAvatarsDirectoryName = "session_avatars"
    nonisolated private static let cachedVoiceMessagesDirectoryURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent(voiceMessagesDirectoryName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: directoryURL.path) {
            try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }
        return directoryURL
    }()
    nonisolated private static let cachedImageMessagesDirectoryURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent(imageMessagesDirectoryName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: directoryURL.path) {
            try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }
        return directoryURL
    }()
    nonisolated private static let cachedImageThumbnailsDirectoryURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent(imageThumbnailsDirectoryName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: directoryURL.path) {
            try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }
        return directoryURL
    }()
    nonisolated private static let cachedSessionAvatarsDirectoryURL: URL = {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL
            .appendingPathComponent("CrisisConnect", isDirectory: true)
            .appendingPathComponent(sessionAvatarsDirectoryName, isDirectory: true)
        if !FileManager.default.fileExists(atPath: directoryURL.path) {
            try? FileManager.default.createDirectory(at: directoryURL, withIntermediateDirectories: true)
        }
        return directoryURL
    }()

    @Published private(set) var sessions: [SOSChatSession] = []
    @Published private(set) var messagesBySession: [UUID: [SOSChatMessage]] = [:]

    private let persistenceQueue = DispatchQueue(label: "sos.chat.persistence")
    private let persistenceURL: URL
    private let sessionUpdateSubject = PassthroughSubject<UUID, Never>()
    private var pendingSave: DispatchWorkItem?

    nonisolated static func voicePreviewText() -> String {
        NSLocalizedString("Voice message", comment: "")
    }

    nonisolated static func imagePreviewText() -> String {
        NSLocalizedString("Photo", comment: "")
    }

    nonisolated static func callEventPreviewText(
        direction: SOSChatCallDirection,
        result: SOSChatCallResult
    ) -> String {
        switch (direction, result) {
        case (.outgoing, .answered):
            return NSLocalizedString("SOS_CHAT_CALL_EVENT_OUTGOING_ANSWERED", comment: "")
        case (.incoming, .answered):
            return NSLocalizedString("SOS_CHAT_CALL_EVENT_INCOMING_ANSWERED", comment: "")
        case (_, .missed):
            return NSLocalizedString("SOS_CHAT_CALL_EVENT_MISSED", comment: "")
        case (_, .rejected):
            return NSLocalizedString("SOS_CHAT_CALL_EVENT_REJECTED", comment: "")
        case (_, .canceled):
            return NSLocalizedString("SOS_CHAT_CALL_EVENT_CANCELED", comment: "")
        }
    }

    nonisolated static func locationPreviewText() -> String {
        NSLocalizedString("Location shared", comment: "")
    }

    nonisolated static func sharedFilePreviewText() -> String {
        NSLocalizedString("Shared file", comment: "")
    }

    nonisolated static func voiceMessageDirectoryURL() -> URL {
        cachedVoiceMessagesDirectoryURL
    }

    nonisolated static func voiceMessageFileURL(fileName: String) -> URL {
        voiceMessageDirectoryURL().appendingPathComponent(fileName, isDirectory: false)
    }

    nonisolated static func imageMessageDirectoryURL() -> URL {
        cachedImageMessagesDirectoryURL
    }

    nonisolated static func imageThumbnailDirectoryURL() -> URL {
        cachedImageThumbnailsDirectoryURL
    }

    nonisolated static func imageMessageFileURL(fileName: String) -> URL {
        imageMessageDirectoryURL().appendingPathComponent(fileName, isDirectory: false)
    }

    nonisolated static func imageThumbnailFileURL(fileName: String) -> URL {
        imageThumbnailDirectoryURL().appendingPathComponent(fileName, isDirectory: false)
    }

    nonisolated static func sessionAvatarDirectoryURL() -> URL {
        cachedSessionAvatarsDirectoryURL
    }

    nonisolated static func sessionAvatarFileURL(fileName: String) -> URL {
        sessionAvatarDirectoryURL().appendingPathComponent(fileName, isDirectory: false)
    }

    nonisolated static func voiceMessageFileName(messageId: String, mimeType: String?) -> String {
        let normalized = mimeType?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let fileExtension: String
        if let normalized, normalized.contains("ogg") || normalized.contains("opus") {
            fileExtension = "ogg"
        } else if let normalized, normalized.contains("aac") || normalized.contains("mp4") || normalized == "audio/mp4" {
            fileExtension = "m4a"
        } else {
            fileExtension = "m4a"
        }
        return "\(messageId).\(fileExtension)"
    }

    nonisolated static func imageMessageFileName(messageId: String, mimeType: String?) -> String {
        let normalized = mimeType?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let fileExtension: String
        if let normalized, normalized.contains("png") {
            fileExtension = "png"
        } else if let normalized, normalized.contains("webp") {
            fileExtension = "webp"
        } else if let normalized, normalized.contains("heic") || normalized.contains("heif") {
            fileExtension = "heic"
        } else {
            fileExtension = "jpg"
        }
        return "\(messageId).\(fileExtension)"
    }

    nonisolated static func imageThumbnailFileName(messageId: String, mimeType: String?) -> String {
        let normalized = mimeType?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let fileExtension: String
        if let normalized, normalized.contains("png") {
            fileExtension = "png"
        } else {
            fileExtension = "jpg"
        }
        return "\(messageId)_thumb.\(fileExtension)"
    }

    nonisolated static func sessionAvatarFileName(sessionId: UUID) -> String {
        "\(sessionId.uuidString.lowercased()).jpg"
    }

    nonisolated static func persistVoiceData(_ data: Data, messageId: String, mimeType: String?) -> String? {
        guard !data.isEmpty else { return nil }
        let fileName = voiceMessageFileName(messageId: messageId, mimeType: mimeType)
        let destination = voiceMessageFileURL(fileName: fileName)
        do {
            try LocalEncryptedFileStore.write(data, to: destination)
            return fileName
        } catch {
            return nil
        }
    }

    nonisolated static func persistVoiceFile(from sourceURL: URL, messageId: String, mimeType: String?) -> String? {
        guard let data = try? Data(contentsOf: sourceURL), !data.isEmpty else { return nil }
        return persistVoiceData(data, messageId: messageId, mimeType: mimeType)
    }

    nonisolated static func persistImageData(_ data: Data, messageId: String, mimeType: String?) -> String? {
        guard !data.isEmpty else { return nil }
        let fileName = imageMessageFileName(messageId: messageId, mimeType: mimeType)
        let destination = imageMessageFileURL(fileName: fileName)
        do {
            try LocalEncryptedFileStore.write(data, to: destination)
            return fileName
        } catch {
            return nil
        }
    }

    nonisolated static func persistImageThumbnailData(_ data: Data, messageId: String, mimeType: String?) -> String? {
        guard !data.isEmpty else { return nil }
        let fileName = imageThumbnailFileName(messageId: messageId, mimeType: mimeType)
        let destination = imageThumbnailFileURL(fileName: fileName)
        do {
            try LocalEncryptedFileStore.write(data, to: destination)
            return fileName
        } catch {
            return nil
        }
    }

    nonisolated static func persistSessionAvatarData(_ data: Data, sessionId: UUID) -> String? {
        guard !data.isEmpty else { return nil }
        let fileName = sessionAvatarFileName(sessionId: sessionId)
        let destination = sessionAvatarFileURL(fileName: fileName)
        do {
            try LocalEncryptedFileStore.write(data, to: destination)
            return fileName
        } catch {
            return nil
        }
    }

    nonisolated static func loadVoiceData(fileName: String) -> Data? {
        let normalized = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        let destination = voiceMessageFileURL(fileName: normalized)
        guard let payload = try? LocalEncryptedFileStore.read(from: destination) else {
            return nil
        }
        if !payload.wasEncrypted {
            try? LocalEncryptedFileStore.write(payload.data, to: destination)
        }
        return payload.data
    }

    nonisolated static func loadImageData(fileName: String, thumbnail: Bool = false) -> Data? {
        let normalized = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        let destination = thumbnail
            ? imageThumbnailFileURL(fileName: normalized)
            : imageMessageFileURL(fileName: normalized)
        guard let payload = try? LocalEncryptedFileStore.read(from: destination) else {
            return nil
        }
        if !payload.wasEncrypted {
            try? LocalEncryptedFileStore.write(payload.data, to: destination)
        }
        return payload.data
    }

    nonisolated static func loadSessionAvatarData(fileName: String) -> Data? {
        let normalized = fileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        let destination = sessionAvatarFileURL(fileName: normalized)
        guard let payload = try? LocalEncryptedFileStore.read(from: destination) else {
            return nil
        }
        if !payload.wasEncrypted {
            try? LocalEncryptedFileStore.write(payload.data, to: destination)
        }
        return payload.data
    }

    private init() {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL.appendingPathComponent("CrisisConnect", isDirectory: true)
        persistenceURL = directoryURL.appendingPathComponent("sos_chat.json")
        loadPersisted()
    }

    func clear() {
        sessions = []
        messagesBySession = [:]
        schedulePersist()
    }

    func updatesPublisher(for sessionId: UUID) -> AnyPublisher<Void, Never> {
        sessionUpdateSubject
            .filter { $0 == sessionId }
            .map { _ in () }
            .eraseToAnyPublisher()
    }

    func ensureSession(
        id: UUID,
        displayName: String? = nil,
        role: SOSChatRole = .unknown,
        isVerified: Bool = false
    ) {
        let normalized = normalizedIdentity(id: id, displayName: displayName, role: role)
        if let index = sessions.firstIndex(where: { $0.id == id }) {
            let oldName = sessions[index].displayName
            let sanitized = normalized.displayName
            sessions[index].role = normalized.role
            sessions[index].isVerified = sessions[index].isVerified || isVerified
            if let name = sanitized {
                sessions[index].displayName = name
            }
            let allowInitialUpdate = shouldRefreshInitials(
                existing: sessions[index].avatarInitials,
                oldName: oldName,
                newName: sanitized,
                role: normalized.role,
                id: id
            )
            ensureAvatar(
                for: index,
                displayName: sessions[index].displayName,
                role: normalized.role,
                allowInitialUpdate: allowInitialUpdate
            )
            notifySessionUpdated(id)
            schedulePersist()
            return
        }

        let name = normalized.displayName ?? defaultDisplayName(for: normalized.role, id: id)
        let initials = AvatarGenerator.initials(from: name)
        let hue = AvatarGenerator.hue(for: id)
        let session = SOSChatSession(
            id: id,
            displayName: name,
            role: normalized.role,
            lastUpdated: Date(),
            lastMessage: nil,
            isVerified: isVerified,
            unreadCount: 0,
            avatarInitials: initials,
            avatarHue: hue,
            avatarImageRelativePath: nil
        )
        sessions.append(session)
        notifySessionUpdated(id)
        schedulePersist()
    }

    func updateIdentity(
        id: UUID,
        displayName: String?,
        role: SOSChatRole,
        isVerified: Bool = false,
        avatarBase64: String? = nil
    ) {
        guard let index = sessions.firstIndex(where: { $0.id == id }) else {
            ensureSession(id: id, displayName: displayName, role: role, isVerified: isVerified)
            if let createdIndex = sessions.firstIndex(where: { $0.id == id }) {
                updateAvatarImage(for: createdIndex, sessionId: id, avatarBase64: avatarBase64)
                notifySessionUpdated(id)
                schedulePersist()
            }
            return
        }
        let normalized = normalizedIdentity(id: id, displayName: displayName, role: role)
        let oldName = sessions[index].displayName
        let sanitized = normalized.displayName
        if let name = sanitized {
            sessions[index].displayName = name
        } else if sessions[index].displayName.isEmpty {
            sessions[index].displayName = defaultDisplayName(for: normalized.role, id: id)
        }
        sessions[index].role = normalized.role
        sessions[index].isVerified = sessions[index].isVerified || isVerified
        let allowInitialUpdate = shouldRefreshInitials(
            existing: sessions[index].avatarInitials,
            oldName: oldName,
            newName: sanitized,
            role: normalized.role,
            id: id
        )
        ensureAvatar(
            for: index,
            displayName: sessions[index].displayName,
            role: normalized.role,
            allowInitialUpdate: allowInitialUpdate
        )
        updateAvatarImage(for: index, sessionId: id, avatarBase64: avatarBase64)
        notifySessionUpdated(id)
        schedulePersist()
    }

    @discardableResult
    func appendRemoteMessage(sessionId: UUID, text: String, transportMessageId: String? = nil) -> Bool {
        ensureSession(id: sessionId, role: .unknown)
        let normalizedTransportId = normalizedTransportMessageId(transportMessageId)
        if let normalizedTransportId,
           messagesBySession[sessionId, default: []].contains(where: {
               !$0.isLocal && $0.transportMessageId == normalizedTransportId
           }) {
            return false
        }
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .text,
            text: text,
            audioRelativePath: nil,
            audioDurationMillis: nil,
            isLocal: false,
            timestamp: Date(),
            status: .delivered,
            transportMessageId: normalizedTransportId
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = P2pSharedTransferSupport.previewText(for: text)
            session.unreadCount += 1
        }
        schedulePersist()
        return true
    }

    @discardableResult
    func appendLocalMessage(
        sessionId: UUID,
        text: String,
        status: SOSChatMessageStatus,
        transportMessageId: String? = nil
    ) -> SOSChatMessage {
        ensureSession(id: sessionId, role: .unknown)
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .text,
            text: text,
            audioRelativePath: nil,
            audioDurationMillis: nil,
            isLocal: true,
            timestamp: Date(),
            status: status,
            transportMessageId: normalizedTransportMessageId(transportMessageId)
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = P2pSharedTransferSupport.previewText(for: text)
        }
        schedulePersist()
        return message
    }

    @discardableResult
    func appendLocalAudioMessage(
        sessionId: UUID,
        audioRelativePath: String,
        durationMillis: Int?,
        status: SOSChatMessageStatus,
        transportMessageId: String? = nil
    ) -> SOSChatMessage {
        ensureSession(id: sessionId, role: .unknown)
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .audio,
            text: Self.voicePreviewText(),
            audioRelativePath: audioRelativePath,
            audioDurationMillis: durationMillis,
            isLocal: true,
            timestamp: Date(),
            status: status,
            transportMessageId: normalizedTransportMessageId(transportMessageId)
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = Self.voicePreviewText()
        }
        schedulePersist()
        return message
    }

    @discardableResult
    func appendLocalImageMessage(
        sessionId: UUID,
        imageRelativePath: String,
        thumbnailRelativePath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String?,
        status: SOSChatMessageStatus,
        transportMessageId: String? = nil
    ) -> SOSChatMessage {
        ensureSession(id: sessionId, role: .unknown)
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .image,
            text: Self.imagePreviewText(),
            audioRelativePath: nil,
            audioDurationMillis: nil,
            imageRelativePath: imageRelativePath,
            imageThumbnailRelativePath: thumbnailRelativePath,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            imageMimeType: imageMimeType,
            isLocal: true,
            timestamp: Date(),
            status: status,
            transportMessageId: normalizedTransportMessageId(transportMessageId)
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = Self.imagePreviewText()
        }
        schedulePersist()
        return message
    }

    @discardableResult
    func appendCallEvent(
        sessionId: UUID,
        direction: SOSChatCallDirection,
        result: SOSChatCallResult,
        durationMillis: Int?
    ) -> SOSChatMessage {
        ensureSession(id: sessionId, role: .unknown)
        let timestamp = Date()
        let previewText = Self.callEventPreviewText(direction: direction, result: result)
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .call,
            text: previewText,
            audioRelativePath: nil,
            audioDurationMillis: nil,
            imageRelativePath: nil,
            imageThumbnailRelativePath: nil,
            imageWidth: nil,
            imageHeight: nil,
            imageMimeType: nil,
            callDirection: direction,
            callResult: result,
            callDurationMillis: durationMillis,
            locationLatitude: nil,
            locationLongitude: nil,
            locationHorizontalAccuracyMeters: nil,
            isLocal: direction == .outgoing,
            timestamp: timestamp,
            status: .delivered,
            transportMessageId: nil
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = timestamp
            session.lastMessage = previewText
            if direction == .incoming, result == .missed {
                session.unreadCount += 1
            }
        }
        schedulePersist()
        return message
    }

    @discardableResult
    func upsertLocalLocationMessage(
        sessionId: UUID,
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        status: SOSChatMessageStatus,
        transportMessageId: String? = nil
    ) -> SOSChatMessage {
        let result = upsertLocationMessage(
            sessionId: sessionId,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: horizontalAccuracyMeters,
            capturedAt: capturedAt,
            isLocal: true,
            status: status,
            transportMessageId: transportMessageId
        )
        return result.message
    }

    @discardableResult
    func appendRemoteAudioMessage(
        sessionId: UUID,
        audioRelativePath: String,
        durationMillis: Int?,
        transportMessageId: String? = nil
    ) -> Bool {
        ensureSession(id: sessionId, role: .unknown)
        let normalizedTransportId = normalizedTransportMessageId(transportMessageId)
        if let normalizedTransportId,
           messagesBySession[sessionId, default: []].contains(where: {
               !$0.isLocal && $0.transportMessageId == normalizedTransportId
           }) {
            return false
        }
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .audio,
            text: Self.voicePreviewText(),
            audioRelativePath: audioRelativePath,
            audioDurationMillis: durationMillis,
            isLocal: false,
            timestamp: Date(),
            status: .delivered,
            transportMessageId: normalizedTransportId
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = Self.voicePreviewText()
            session.unreadCount += 1
        }
        schedulePersist()
        return true
    }

    @discardableResult
    func appendRemoteImageMessage(
        sessionId: UUID,
        imageRelativePath: String,
        thumbnailRelativePath: String?,
        imageWidth: Int?,
        imageHeight: Int?,
        imageMimeType: String?,
        transportMessageId: String? = nil
    ) -> Bool {
        ensureSession(id: sessionId, role: .unknown)
        let normalizedTransportId = normalizedTransportMessageId(transportMessageId)
        if let normalizedTransportId,
           messagesBySession[sessionId, default: []].contains(where: {
               !$0.isLocal && $0.transportMessageId == normalizedTransportId
           }) {
            return false
        }
        let message = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .image,
            text: Self.imagePreviewText(),
            audioRelativePath: nil,
            audioDurationMillis: nil,
            imageRelativePath: imageRelativePath,
            imageThumbnailRelativePath: thumbnailRelativePath,
            imageWidth: imageWidth,
            imageHeight: imageHeight,
            imageMimeType: imageMimeType,
            isLocal: false,
            timestamp: Date(),
            status: .delivered,
            transportMessageId: normalizedTransportId
        )
        var messages = messagesBySession[sessionId, default: []]
        messages.append(message)
        setMessages(messages, for: sessionId)
        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = message.timestamp
            session.lastMessage = Self.imagePreviewText()
            session.unreadCount += 1
        }
        schedulePersist()
        return true
    }

    @discardableResult
    func upsertRemoteLocationMessage(
        sessionId: UUID,
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        transportMessageId: String? = nil
    ) -> Bool {
        let result = upsertLocationMessage(
            sessionId: sessionId,
            latitude: latitude,
            longitude: longitude,
            horizontalAccuracyMeters: horizontalAccuracyMeters,
            capturedAt: capturedAt,
            isLocal: false,
            status: .delivered,
            transportMessageId: transportMessageId
        )
        return result.changed
    }

    func markDelivered(sessionId: UUID, transportMessageIds: [String]? = nil) {
        let ids = normalizedTransportMessageIds(transportMessageIds)
        updateLocalMessages(sessionId: sessionId, transportMessageIds: ids) { status in
            switch status {
            case .pending, .sent:
                return .delivered
            case .delivered, .read:
                return status
            }
        }
        schedulePersist()
    }

    func markRead(sessionId: UUID, transportMessageIds: [String]? = nil) {
        let ids = normalizedTransportMessageIds(transportMessageIds)
        updateLocalMessages(sessionId: sessionId, transportMessageIds: ids) { _ in .read }
        schedulePersist()
    }

    func requeueMessage(sessionId: UUID, transportMessageId: String) {
        let normalized = normalizedTransportMessageId(transportMessageId)
        guard let normalized else { return }
        updateLocalMessages(sessionId: sessionId, transportMessageIds: [normalized]) { _ in .pending }
        schedulePersist()
    }

    func markRemoteRead(sessionId: UUID) {
        updateSession(sessionId: sessionId) { session in
            session.unreadCount = 0
        }
        schedulePersist()
    }

    func messages(for sessionId: UUID) -> [SOSChatMessage] {
        messagesBySession[sessionId, default: []]
    }

    func recentMessages(for sessionId: UUID, limit: Int) -> [SOSChatMessage] {
        let normalizedLimit = max(0, limit)
        guard normalizedLimit > 0 else { return [] }
        return Array(messagesBySession[sessionId, default: []].suffix(normalizedLimit))
    }

    func messageCount(for sessionId: UUID) -> Int {
        messagesBySession[sessionId, default: []].count
    }

    func lastMessage(for sessionId: UUID) -> SOSChatMessage? {
        messagesBySession[sessionId, default: []].last
    }

    func session(for sessionId: UUID) -> SOSChatSession? {
        sessions.first(where: { $0.id == sessionId })
    }

    func transportMessageIdsForRemoteMessages(sessionId: UUID) -> [String] {
        var seen = Set<String>()
        return messagesBySession[sessionId, default: []]
            .compactMap { message in
                guard !message.isLocal,
                      let transportMessageId = normalizedTransportMessageId(message.transportMessageId),
                      !seen.contains(transportMessageId) else {
                    return nil
                }
                seen.insert(transportMessageId)
                return transportMessageId
            }
    }

    func mergeSession(from sourceSessionId: UUID, into targetSessionId: UUID) {
        guard sourceSessionId != targetSessionId else { return }
        let sourceSession = sessions.first(where: { $0.id == sourceSessionId })
        let targetSession = sessions.first(where: { $0.id == targetSessionId })
        guard sourceSession != nil || messagesBySession[sourceSessionId] != nil else { return }

        ensureSession(
            id: targetSessionId,
            displayName: sourceSession?.displayName ?? targetSession?.displayName,
            role: targetSession?.role ?? sourceSession?.role ?? .unknown,
            isVerified: (targetSession?.isVerified ?? false) || (sourceSession?.isVerified ?? false)
        )

        var mergedMessages = messagesBySession[targetSessionId, default: []]
        let existingKeys = Set(mergedMessages.compactMap { messageMergeKey($0) })
        var dedupeKeys = existingKeys
        for message in messagesBySession[sourceSessionId, default: []] {
            let migrated = SOSChatMessage(
                id: message.id,
                sessionId: targetSessionId,
                kind: message.kind,
                text: message.text,
                audioRelativePath: message.audioRelativePath,
                audioDurationMillis: message.audioDurationMillis,
                imageRelativePath: message.imageRelativePath,
                imageThumbnailRelativePath: message.imageThumbnailRelativePath,
                imageWidth: message.imageWidth,
                imageHeight: message.imageHeight,
                imageMimeType: message.imageMimeType,
                locationLatitude: message.locationLatitude,
                locationLongitude: message.locationLongitude,
                locationHorizontalAccuracyMeters: message.locationHorizontalAccuracyMeters,
                isLocal: message.isLocal,
                timestamp: message.timestamp,
                status: message.status,
                transportMessageId: normalizedTransportMessageId(message.transportMessageId)
            )
            if let key = messageMergeKey(migrated) {
                guard !dedupeKeys.contains(key) else { continue }
                dedupeKeys.insert(key)
            }
            mergedMessages.append(migrated)
        }
        mergedMessages.sort { $0.timestamp < $1.timestamp }
        var snapshot = messagesBySession
        snapshot[targetSessionId] = mergedMessages
        snapshot.removeValue(forKey: sourceSessionId)
        messagesBySession = snapshot
        notifySessionUpdated(targetSessionId)
        notifySessionUpdated(sourceSessionId)

        if let sourceIndex = sessions.firstIndex(where: { $0.id == sourceSessionId }) {
            let source = sessions[sourceIndex]
            let normalizedSourceRole = normalizedIdentity(
                id: targetSessionId,
                displayName: source.displayName,
                role: source.role
            ).role
            updateSession(sessionId: targetSessionId) { session in
                if isPlaceholderDisplayName(session.displayName, role: session.role, id: targetSessionId),
                   !source.displayName.isEmpty {
                    session.displayName = source.displayName
                }
                if session.role == .unknown && normalizedSourceRole != .unknown {
                    session.role = normalizedSourceRole
                }
                session.isVerified = session.isVerified || source.isVerified
                if session.avatarImageRelativePath == nil {
                    session.avatarImageRelativePath = source.avatarImageRelativePath
                }
                session.lastUpdated = max(session.lastUpdated, source.lastUpdated)
                if source.lastUpdated >= session.lastUpdated {
                    session.lastMessage = source.lastMessage ?? session.lastMessage
                }
                session.unreadCount = max(session.unreadCount, source.unreadCount)
            }
            sessions.remove(at: sourceIndex)
            notifySessionUpdated(sourceSessionId)
        }

        if let targetIndex = sessions.firstIndex(where: { $0.id == targetSessionId }) {
            let normalized = normalizedIdentity(
                id: targetSessionId,
                displayName: sessions[targetIndex].displayName,
                role: sessions[targetIndex].role
            )
            let oldName = sessions[targetIndex].displayName
            let targetName = normalized.displayName ?? defaultDisplayName(for: normalized.role, id: targetSessionId)
            let targetRole = normalized.role
            sessions[targetIndex].displayName = targetName
            sessions[targetIndex].role = targetRole
            let allowInitialUpdate = shouldRefreshInitials(
                existing: sessions[targetIndex].avatarInitials,
                oldName: oldName,
                newName: targetName,
                role: targetRole,
                id: targetSessionId
            )
            ensureAvatar(
                for: targetIndex,
                displayName: targetName,
                role: targetRole,
                allowInitialUpdate: allowInitialUpdate
            )
            notifySessionUpdated(targetSessionId)
        }
        schedulePersist()
    }

    private func updateLocalMessages(
        sessionId: UUID,
        transportMessageIds: Set<String>,
        transform: (SOSChatMessageStatus) -> SOSChatMessageStatus
    ) {
        guard var messages = messagesBySession[sessionId] else { return }
        var changed = false
        for index in messages.indices {
            guard messages[index].isLocal else { continue }
            if !transportMessageIds.isEmpty {
                let transportMessageId = normalizedTransportMessageId(messages[index].transportMessageId)
                guard let transportMessageId, transportMessageIds.contains(transportMessageId) else {
                    continue
                }
            }
            let updated = transform(messages[index].status)
            if updated != messages[index].status {
                messages[index].status = updated
                changed = true
            }
        }
        if changed {
            setMessages(messages, for: sessionId)
        }
    }

    private func upsertLocationMessage(
        sessionId: UUID,
        latitude: Double,
        longitude: Double,
        horizontalAccuracyMeters: Double?,
        capturedAt: Date,
        isLocal: Bool,
        status: SOSChatMessageStatus,
        transportMessageId: String?
    ) -> (message: SOSChatMessage, changed: Bool) {
        ensureSession(id: sessionId, role: .unknown)
        let normalizedTransportId = normalizedTransportMessageId(transportMessageId)
        let baseMessage = SOSChatMessage(
            id: UUID(),
            sessionId: sessionId,
            kind: .location,
            text: Self.locationPreviewText(),
            locationLatitude: latitude,
            locationLongitude: longitude,
            locationHorizontalAccuracyMeters: horizontalAccuracyMeters,
            isLocal: isLocal,
            timestamp: capturedAt,
            status: status,
            transportMessageId: normalizedTransportId
        )

        var messages = messagesBySession[sessionId, default: []]
        let existingLastUpdated = session(for: sessionId)?.lastUpdated ?? .distantPast
        let shouldPromotePreview = capturedAt >= existingLastUpdated
        var changed = false
        var inserted = true
        var storedMessage = baseMessage

        if let normalizedTransportId,
           let index = messages.firstIndex(where: { $0.transportMessageId == normalizedTransportId }) {
            inserted = false
            let updatedMessage = SOSChatMessage(
                id: messages[index].id,
                sessionId: sessionId,
                kind: .location,
                text: Self.locationPreviewText(),
                locationLatitude: latitude,
                locationLongitude: longitude,
                locationHorizontalAccuracyMeters: horizontalAccuracyMeters,
                isLocal: isLocal,
                timestamp: capturedAt,
                status: status,
                transportMessageId: normalizedTransportId
            )
            storedMessage = updatedMessage
            changed = messages[index] != updatedMessage
            if changed {
                messages[index] = updatedMessage
                setMessages(messages, for: sessionId)
            }
        } else {
            messages.append(baseMessage)
            setMessages(messages, for: sessionId)
            changed = true
        }

        updateSession(sessionId: sessionId) { session in
            session.lastUpdated = max(session.lastUpdated, capturedAt)
            if shouldPromotePreview {
                session.lastMessage = Self.locationPreviewText()
            }
            if inserted && !isLocal {
                session.unreadCount += 1
            }
        }

        if changed || shouldPromotePreview {
            schedulePersist()
        }
        return (storedMessage, changed || shouldPromotePreview)
    }

    private func setMessages(_ messages: [SOSChatMessage], for sessionId: UUID) {
        var snapshot = messagesBySession
        snapshot[sessionId] = messages
        messagesBySession = snapshot
        notifySessionUpdated(sessionId)
    }

    private func updateSession(sessionId: UUID, transform: (inout SOSChatSession) -> Void) {
        guard let index = sessions.firstIndex(where: { $0.id == sessionId }) else { return }
        var session = sessions[index]
        transform(&session)
        sessions[index] = session
        notifySessionUpdated(sessionId)
    }

    private func notifySessionUpdated(_ sessionId: UUID) {
        sessionUpdateSubject.send(sessionId)
    }

    private func sanitizedName(_ name: String?) -> String? {
        let trimmed = name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private func normalizedIdentity(
        id: UUID,
        displayName: String?,
        role: SOSChatRole
    ) -> (displayName: String?, role: SOSChatRole) {
        let sanitized = sanitizedName(displayName)
        guard let contact = ContactStore.shared.contact(for: id),
              contact.preferredTransport == .bleGatt else {
            return (sanitized, role)
        }
        return (sanitizedName(contact.name) ?? sanitized, .unknown)
    }

    private func normalizedTransportMessageId(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? nil : trimmed
    }

    private func normalizedTransportMessageIds(_ values: [String]?) -> Set<String> {
        Set((values ?? []).compactMap { normalizedTransportMessageId($0) })
    }

    private func messageMergeKey(_ message: SOSChatMessage) -> String? {
        guard let transportMessageId = normalizedTransportMessageId(message.transportMessageId) else {
            return nil
        }
        return "\(message.isLocal ? "local" : "remote"):\(transportMessageId)"
    }

    private func isPlaceholderDisplayName(_ name: String, role: SOSChatRole, id: UUID) -> Bool {
        name == defaultDisplayName(for: role, id: id)
    }

    private func defaultDisplayName(for role: SOSChatRole, id: UUID) -> String {
        let baseKey: String
        switch role {
        case .fieldTeam:
            baseKey = "SOS_CHAT_ROLE_FIELD_TEAM"
        case .victim:
            baseKey = "SOS_CHAT_ROLE_VICTIM"
        case .unknown:
            baseKey = "SOS_CHAT_ROLE_UNKNOWN"
        }
        let base = NSLocalizedString(baseKey, comment: "")
        let suffix = id.uuidString.suffix(4)
        return "\(base) \(suffix)"
    }

    private func ensureAvatar(for index: Int, displayName: String, role: SOSChatRole, allowInitialUpdate: Bool) {
        if sessions[index].avatarHue == nil {
            sessions[index].avatarHue = AvatarGenerator.hue(for: sessions[index].id)
        }
        if sessions[index].avatarInitials == nil || allowInitialUpdate {
            let name = displayName.isEmpty ? defaultDisplayName(for: role, id: sessions[index].id) : displayName
            sessions[index].avatarInitials = AvatarGenerator.initials(from: name)
        }
    }

    private func updateAvatarImage(for index: Int, sessionId: UUID, avatarBase64: String?) {
        guard let avatarBase64 = sanitizedName(avatarBase64),
              let avatarData = decodeAvatarPayload(avatarBase64),
              let relativePath = Self.persistSessionAvatarData(avatarData, sessionId: sessionId) else {
            return
        }
        sessions[index].avatarImageRelativePath = relativePath
    }

    private func decodeAvatarPayload(_ payloadBase64: String) -> Data? {
        let trimmed = payloadBase64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let remainder = trimmed.count % 4
        let padded = remainder == 0
            ? trimmed
            : trimmed.padding(toLength: trimmed.count + (4 - remainder), withPad: "=", startingAt: 0)
        return Data(base64Encoded: padded, options: [.ignoreUnknownCharacters])
    }

    private func shouldRefreshInitials(
        existing: String?,
        oldName: String,
        newName: String?,
        role: SOSChatRole,
        id: UUID
    ) -> Bool {
        if existing == nil { return true }
        guard let newName, !newName.isEmpty else { return false }
        let placeholder = defaultDisplayName(for: role, id: id)
        return oldName == placeholder
    }

    private func schedulePersist() {
        pendingSave?.cancel()
        let snapshot = SOSChatSnapshot(
            sessions: sessions,
            messagesBySession: messagesBySession
        )
        let workItem = DispatchWorkItem { [weak self] in
            guard let self else { return }
            self.saveSnapshot(snapshot, to: self.persistenceURL)
        }
        pendingSave = workItem
        persistenceQueue.asyncAfter(deadline: .now() + 0.4, execute: workItem)
    }

    private func loadPersisted() {
        persistenceQueue.async { [weak self] in
            guard let self else { return }
            let url = self.persistenceURL
            guard let payload = try? LocalEncryptedFileStore.read(from: url),
                  let snapshot = try? JSONDecoder().decode(SOSChatSnapshot.self, from: payload.data) else {
                return
            }
            var updatedSessions = snapshot.sessions
            var needsPersist = !payload.wasEncrypted
            for index in updatedSessions.indices {
                let normalized = self.normalizedIdentity(
                    id: updatedSessions[index].id,
                    displayName: updatedSessions[index].displayName,
                    role: updatedSessions[index].role
                )
                let normalizedName = normalized.displayName ?? self.defaultDisplayName(for: normalized.role, id: updatedSessions[index].id)
                if updatedSessions[index].displayName != normalizedName {
                    updatedSessions[index].displayName = normalizedName
                    updatedSessions[index].avatarInitials = AvatarGenerator.initials(from: normalizedName)
                    needsPersist = true
                }
                if updatedSessions[index].role != normalized.role {
                    updatedSessions[index].role = normalized.role
                    needsPersist = true
                }
                if updatedSessions[index].avatarHue == nil {
                    updatedSessions[index].avatarHue = AvatarGenerator.hue(for: updatedSessions[index].id)
                    needsPersist = true
                }
                if updatedSessions[index].avatarInitials == nil {
                    updatedSessions[index].avatarInitials = AvatarGenerator.initials(from: updatedSessions[index].displayName)
                    needsPersist = true
                }
                if let relativePath = updatedSessions[index].avatarImageRelativePath,
                   Self.loadSessionAvatarData(fileName: relativePath) == nil {
                    updatedSessions[index].avatarImageRelativePath = nil
                    needsPersist = true
                }
            }

            DispatchQueue.main.async {
                self.sessions = updatedSessions
                self.messagesBySession = snapshot.messagesBySession
                if needsPersist {
                    self.schedulePersist()
                }
            }
        }
    }

    private func saveSnapshot(_ snapshot: SOSChatSnapshot, to url: URL) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        guard let data = try? encoder.encode(snapshot) else { return }
        let directory = url.deletingLastPathComponent()
        if !FileManager.default.fileExists(atPath: directory.path) {
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        }
        try? LocalEncryptedFileStore.write(data, to: url)
    }
}

private struct SOSChatSnapshot: Codable, Sendable {
    let sessions: [SOSChatSession]
    let messagesBySession: [UUID: [SOSChatMessage]]

    enum CodingKeys: String, CodingKey {
        case sessions
        case messagesBySession
    }

    nonisolated init(sessions: [SOSChatSession], messagesBySession: [UUID: [SOSChatMessage]]) {
        self.sessions = sessions
        self.messagesBySession = messagesBySession
    }

    nonisolated init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sessions = try container.decode([SOSChatSession].self, forKey: .sessions)
        let raw = try container.decode([String: [SOSChatMessage]].self, forKey: .messagesBySession)
        var mapped: [UUID: [SOSChatMessage]] = [:]
        raw.forEach { key, value in
            if let id = UUID(uuidString: key) {
                mapped[id] = value
            }
        }
        messagesBySession = mapped
    }

    nonisolated func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(sessions, forKey: .sessions)
        let raw = messagesBySession.reduce(into: [String: [SOSChatMessage]]()) { acc, entry in
            acc[entry.key.uuidString] = entry.value
        }
        try container.encode(raw, forKey: .messagesBySession)
    }
}
