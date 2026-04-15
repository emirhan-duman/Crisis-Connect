//
//  ContactStore.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import CryptoKit
import Combine

private func normalizeVerifiedIdentityKey(_ raw: String?) -> String? {
    guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
          !trimmed.isEmpty else {
        return nil
    }
    return trimmed
}

private func verifiedIdentityCandidate(
    verifiedIdentityKey: String?,
    remoteDeviceId: String?
) -> String? {
    normalizeVerifiedIdentityKey(verifiedIdentityKey)
        ?? normalizeVerifiedIdentityKey(remoteDeviceId)
}

private func sanitizedTrustState(
    isVerified: Bool,
    verifiedIdentityKey: String?,
    verifiedAt: Date?
) -> (isVerified: Bool, verifiedIdentityKey: String?, verifiedAt: Date?) {
    let normalizedKey = normalizeVerifiedIdentityKey(verifiedIdentityKey)
    let trusted = isVerified && normalizedKey != nil
    return (
        isVerified: trusted,
        verifiedIdentityKey: trusted ? normalizedKey : nil,
        verifiedAt: trusted ? verifiedAt : nil
    )
}

private func normalizedContactSessionToken(_ raw: String?) -> String? {
    raw?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty?.lowercased()
}

private func normalizedContactIdentityToken(_ raw: String?) -> String? {
    raw?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty?.lowercased()
}

private func normalizedBleShareToken(_ raw: String?) -> String? {
    raw?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty?.uppercased()
}

private func normalizedBleAddressToken(_ raw: String?) -> String? {
    raw?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty?.uppercased()
}

enum ContactTransport: String, Codable {
    case legacyBroadcast = "legacy_broadcast"
    case bleGatt = "ble_gatt"
}

enum ContactRemotePlatform: String, Codable {
    case unknown
    case android
    case ios

    static func normalize(_ rawValue: String?) -> ContactRemotePlatform {
        switch rawValue?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "android":
            return .android
        case "ios", "iphone":
            return .ios
        default:
            return .unknown
        }
    }
}

struct ContactRecord: Identifiable, Codable, Equatable {
    let id: UUID
    var name: String
    var broadcastId: String
    var sessionCode: String
    var isVerified: Bool
    var verifiedIdentityKey: String?
    var verifiedAt: Date?
    var remoteSessionCode: String?
    var aesKeyBase64: String
    var preferredTransport: ContactTransport
    var remotePlatform: ContactRemotePlatform
    var bleShareId: String?
    var lastKnownBleAddress: String?
    var remoteDeviceId: String?
    var createdAt: Date
    var lastUpdated: Date

    init(
        id: UUID,
        name: String,
        broadcastId: String,
        sessionCode: String,
        isVerified: Bool = false,
        verifiedIdentityKey: String? = nil,
        verifiedAt: Date? = nil,
        remoteSessionCode: String? = nil,
        aesKeyBase64: String,
        preferredTransport: ContactTransport,
        remotePlatform: ContactRemotePlatform = .unknown,
        bleShareId: String? = nil,
        lastKnownBleAddress: String? = nil,
        remoteDeviceId: String? = nil,
        createdAt: Date,
        lastUpdated: Date
    ) {
        self.id = id
        self.name = name
        self.broadcastId = broadcastId
        self.sessionCode = sessionCode
        let trustState = sanitizedTrustState(
            isVerified: isVerified,
            verifiedIdentityKey: verifiedIdentityKey,
            verifiedAt: verifiedAt
        )
        self.isVerified = trustState.isVerified
        self.verifiedIdentityKey = trustState.verifiedIdentityKey
        self.verifiedAt = trustState.verifiedAt
        self.remoteSessionCode = remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.aesKeyBase64 = aesKeyBase64
        self.preferredTransport = preferredTransport
        self.remotePlatform = remotePlatform
        self.bleShareId = bleShareId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.lastKnownBleAddress = lastKnownBleAddress?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.remoteDeviceId = remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.createdAt = createdAt
        self.lastUpdated = lastUpdated
    }

    private enum CodingKeys: String, CodingKey {
        case id
        case name
        case broadcastId
        case sessionCode
        case isVerified
        case verifiedIdentityKey
        case verifiedAt
        case remoteSessionCode
        case aesKeyBase64
        case preferredTransport
        case remotePlatform
        case bleShareId
        case lastKnownBleAddress
        case remoteDeviceId
        case createdAt
        case lastUpdated
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let broadcastId = try container.decodeIfPresent(String.self, forKey: .broadcastId) ?? ""
        let sessionCode = try container.decodeIfPresent(String.self, forKey: .sessionCode)
            ?? broadcastId
        let remoteDeviceId = try container.decodeIfPresent(String.self, forKey: .remoteDeviceId)
        let bleShareId = try container.decodeIfPresent(String.self, forKey: .bleShareId)
        let transport = try container.decodeIfPresent(ContactTransport.self, forKey: .preferredTransport)
            ?? ContactRecord.defaultTransport(
                sessionCode: sessionCode,
                bleShareId: bleShareId,
                remoteDeviceId: remoteDeviceId
            )
        let stableIdentifier = ContactRecord.stableIdentifier(
            sessionCode: sessionCode,
            broadcastId: broadcastId,
            remoteDeviceId: remoteDeviceId,
            bleShareId: bleShareId
        )

        self.init(
            id: try container.decodeIfPresent(UUID.self, forKey: .id) ?? BroadcastSessionId.fromRawIdentifier(stableIdentifier),
            name: try container.decodeIfPresent(String.self, forKey: .name) ?? "Contact",
            broadcastId: broadcastId,
            sessionCode: sessionCode,
            isVerified: try container.decodeIfPresent(Bool.self, forKey: .isVerified) ?? false,
            verifiedIdentityKey: try container.decodeIfPresent(String.self, forKey: .verifiedIdentityKey),
            verifiedAt: try container.decodeIfPresent(Date.self, forKey: .verifiedAt),
            remoteSessionCode: try container.decodeIfPresent(String.self, forKey: .remoteSessionCode),
            aesKeyBase64: try container.decodeIfPresent(String.self, forKey: .aesKeyBase64) ?? "",
            preferredTransport: transport,
            remotePlatform: try container.decodeIfPresent(ContactRemotePlatform.self, forKey: .remotePlatform) ?? .unknown,
            bleShareId: bleShareId,
            lastKnownBleAddress: try container.decodeIfPresent(String.self, forKey: .lastKnownBleAddress),
            remoteDeviceId: remoteDeviceId,
            createdAt: try container.decodeIfPresent(Date.self, forKey: .createdAt) ?? Date(),
            lastUpdated: try container.decodeIfPresent(Date.self, forKey: .lastUpdated) ?? Date()
        )
    }

    private static func defaultTransport(
        sessionCode: String,
        bleShareId: String?,
        remoteDeviceId: String?
    ) -> ContactTransport {
        if sessionCode.lowercased().hasPrefix("ble:") || bleShareId?.isEmpty == false || remoteDeviceId?.isEmpty == false {
            return .bleGatt
        }
        return .legacyBroadcast
    }

    private static func stableIdentifier(
        sessionCode: String,
        broadcastId: String,
        remoteDeviceId: String?,
        bleShareId: String?
    ) -> String {
        remoteDeviceId?.nilIfEmpty
            ?? sessionCode.nilIfEmpty
            ?? bleShareId?.nilIfEmpty
            ?? broadcastId.nilIfEmpty
            ?? UUID().uuidString
    }
}

private struct ContactLookupKeys {
    let sessionTokens: Set<String>
    let identityTokens: Set<String>
    let bleShareId: String?
    let bleAddress: String?

    static func make(
        sessionTokens: [String?],
        identityTokens: [String?],
        bleShareId: String?,
        bleAddress: String?
    ) -> ContactLookupKeys {
        ContactLookupKeys(
            sessionTokens: Set(sessionTokens.compactMap(normalizedContactSessionToken)),
            identityTokens: Set(identityTokens.compactMap(normalizedContactIdentityToken)),
            bleShareId: normalizedBleShareToken(bleShareId),
            bleAddress: normalizedBleAddressToken(bleAddress)
        )
    }

    static func from(record: ContactRecord) -> ContactLookupKeys {
        make(
            sessionTokens: [
                record.broadcastId,
                record.sessionCode,
                record.remoteSessionCode
            ],
            identityTokens: [
                record.verifiedIdentityKey,
                record.remoteDeviceId
            ],
            bleShareId: record.bleShareId,
            bleAddress: record.lastKnownBleAddress
        )
    }

    func matches(_ other: ContactLookupKeys) -> Bool {
        if !sessionTokens.isDisjoint(with: other.sessionTokens) {
            return true
        }
        if !identityTokens.isDisjoint(with: other.identityTokens) {
            return true
        }
        if let bleShareId, bleShareId == other.bleShareId {
            return true
        }
        if let bleAddress, bleAddress == other.bleAddress {
            return true
        }
        return false
    }
}

final class ContactStore: ObservableObject {
    static let shared = ContactStore()

    @Published private(set) var contacts: [ContactRecord] = []

    private let stateQueue = DispatchQueue(label: "contact.store.state")
    private let persistenceQueue = DispatchQueue(label: "contact.store.persistence")
    private var pendingSave: DispatchWorkItem?
    private let persistenceURL: URL
    private var storedContacts: [ContactRecord] = []

    private init() {
        let baseURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        let directoryURL = baseURL.appendingPathComponent("CrisisConnect", isDirectory: true)
        persistenceURL = directoryURL.appendingPathComponent("contacts.json")
        loadPersisted()
    }

    func contact(for broadcastId: String) -> ContactRecord? {
        let normalized = broadcastId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return stateQueue.sync {
            storedContacts.first { $0.broadcastId.lowercased() == normalized }
        }
    }

    func contact(for sessionId: UUID) -> ContactRecord? {
        stateQueue.sync {
            storedContacts.first { $0.id == sessionId }
        }
    }

    func contactForSessionCode(_ sessionCode: String) -> ContactRecord? {
        let normalized = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return stateQueue.sync {
            storedContacts.first { $0.sessionCode.lowercased() == normalized }
        }
    }

    func contactForRemoteDeviceId(_ remoteDeviceId: String) -> ContactRecord? {
        let normalized = remoteDeviceId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return stateQueue.sync {
            storedContacts.first {
                $0.remoteDeviceId?.caseInsensitiveCompare(normalized) == .orderedSame
            }
        }
    }

    func contactForBleAddress(_ address: String) -> ContactRecord? {
        let normalized = address.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalized.isEmpty else { return nil }
        return stateQueue.sync {
            storedContacts.first {
                $0.lastKnownBleAddress?.caseInsensitiveCompare(normalized) == .orderedSame
            }
        }
    }

    func existingBleContact(
        sessionCode: String,
        remoteSessionCode: String?,
        bleShareId: String?,
        lastKnownBleAddress: String?,
        remoteDeviceId: String?,
        verifiedIdentityKey: String? = nil
    ) -> ContactRecord? {
        let normalizedSession = normalizeSessionCode(sessionCode)
        let incomingKeys = ContactLookupKeys.make(
            sessionTokens: [
                normalizedSession,
                remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ],
            identityTokens: [
                verifiedIdentityCandidate(
                    verifiedIdentityKey: verifiedIdentityKey,
                    remoteDeviceId: remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                ),
                remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ],
            bleShareId: bleShareId,
            bleAddress: lastKnownBleAddress
        )
        return stateQueue.sync {
            let indexes = matchingContactIndexes(in: storedContacts, incoming: incomingKeys)
            guard let index = preferredCanonicalIndex(
                in: storedContacts,
                matchingIndexes: indexes,
                preferredStableId: BroadcastSessionId.fromRawIdentifier(
                    verifiedIdentityCandidate(
                        verifiedIdentityKey: verifiedIdentityKey,
                        remoteDeviceId: remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                    )
                    ?? remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                    ?? remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
                    ?? lastKnownBleAddress?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().nilIfEmpty
                    ?? bleShareId?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().nilIfEmpty
                    ?? normalizedSession
                ),
                preferredVerifiedIdentityKey: verifiedIdentityKey,
                preferredRemoteDeviceId: remoteDeviceId
            ) else {
                return nil
            }
            return storedContacts[index]
        }
    }

    func hasBlePreferredContacts() -> Bool {
        stateQueue.sync {
            storedContacts.contains { $0.preferredTransport == .bleGatt }
        }
    }

    func updateBleAddress(sessionId: UUID, address: String?) {
        let normalizedAddress = address?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().nilIfEmpty
        var snapshot: [ContactRecord] = []
        var changed = false

        stateQueue.sync {
            var next = storedContacts
            guard let index = next.firstIndex(where: { $0.id == sessionId }) else { return }
            if next[index].lastKnownBleAddress != normalizedAddress {
                next[index].lastKnownBleAddress = normalizedAddress
                next[index].lastUpdated = Date()
                changed = true
            }
            storedContacts = next
            snapshot = next
        }

        if changed {
            publish(snapshot)
        }
    }

    func aesKey(for broadcastId: String) -> SymmetricKey? {
        guard let record = contact(for: broadcastId) else { return nil }
        return aesKey(for: record)
    }

    func aesKey(for record: ContactRecord) -> SymmetricKey? {
        guard let data = Data(base64Encoded: record.aesKeyBase64, options: [.ignoreUnknownCharacters]),
              data.count == 32 else {
            return nil
        }
        return SymmetricKey(data: data)
    }

    func clearAesKey(for record: ContactRecord) {
        let snapshot: [ContactRecord] = stateQueue.sync {
            guard let index = storedContacts.firstIndex(where: { $0.id == record.id }) else {
                return storedContacts
            }
            storedContacts[index].aesKeyBase64 = ""
            return storedContacts
        }
        schedulePersist(snapshot)
        DispatchQueue.main.async { self.objectWillChange.send() }
    }

    @discardableResult
    func upsertContact(name: String, broadcastId: String, aesKeyBase64: String) -> ContactRecord {
        let normalizedBroadcastId = broadcastId.trimmingCharacters(in: .whitespacesAndNewlines)
        let canonicalKey = canonicalAESKey(aesKeyBase64)
        let now = Date()
        let sessionCode = normalizedBroadcastId
        let stableId = BroadcastSessionId.fromRawIdentifier(sessionCode)
        var updatedRecord: ContactRecord!
        var snapshot: [ContactRecord] = []
        var migratedSessionPairs: [(UUID, UUID)] = []

        stateQueue.sync {
            var next = storedContacts
            let incomingKeys = ContactLookupKeys.make(
                sessionTokens: [normalizedBroadcastId, sessionCode],
                identityTokens: [],
                bleShareId: nil,
                bleAddress: nil
            )
            let matchingIndexes = matchingContactIndexes(in: next, incoming: incomingKeys)

            if let canonicalIndex = preferredCanonicalIndex(
                in: next,
                matchingIndexes: matchingIndexes,
                preferredStableId: stableId,
                preferredVerifiedIdentityKey: nil,
                preferredRemoteDeviceId: nil
            ) {
                let duplicateIds = matchingIndexes
                    .filter { $0 != canonicalIndex }
                    .map { next[$0].id }
                let matchingIndexSet = Set(matchingIndexes)
                var mergedRecord = next[canonicalIndex]
                for index in matchingIndexes where index != canonicalIndex {
                    mergedRecord = mergeDuplicateRecord(primary: mergedRecord, secondary: next[index])
                }
                let preservesTrust = sanitizedTrustState(
                    isVerified: mergedRecord.isVerified,
                    verifiedIdentityKey: mergedRecord.verifiedIdentityKey,
                    verifiedAt: mergedRecord.verifiedAt
                ).isVerified
                mergedRecord.name = name
                if !normalizedBroadcastId.isEmpty {
                    mergedRecord.broadcastId = normalizedBroadcastId
                }
                if mergedRecord.preferredTransport != .bleGatt || isPlaceholderSessionCode(mergedRecord.sessionCode) {
                    mergedRecord.sessionCode = sessionCode
                }
                if !canonicalKey.isEmpty {
                    mergedRecord.aesKeyBase64 = canonicalKey
                }
                if mergedRecord.preferredTransport != .bleGatt {
                    mergedRecord.preferredTransport = .legacyBroadcast
                }
                if !preservesTrust {
                    mergedRecord.isVerified = false
                    mergedRecord.verifiedIdentityKey = nil
                    mergedRecord.verifiedAt = nil
                }
                mergedRecord.lastUpdated = now

                next = next.enumerated().compactMap { index, record in
                    if matchingIndexSet.contains(index), index != canonicalIndex {
                        return nil
                    }
                    return record
                }
                if let survivorIndex = next.firstIndex(where: { $0.id == mergedRecord.id }) {
                    next[survivorIndex] = mergedRecord
                } else {
                    next.append(mergedRecord)
                }
                updatedRecord = mergedRecord
                migratedSessionPairs = duplicateIds.map { ($0, mergedRecord.id) }
            } else {
                let record = ContactRecord(
                    id: stableId,
                    name: name,
                    broadcastId: normalizedBroadcastId,
                    sessionCode: sessionCode,
                    aesKeyBase64: canonicalKey,
                    preferredTransport: .legacyBroadcast,
                    createdAt: now,
                    lastUpdated: now
                )
                next.append(record)
                updatedRecord = record
            }
            storedContacts = next
            snapshot = next
        }

        migrateSessionsIfNeeded(migratedSessionPairs)
        publish(snapshot)
        return updatedRecord
    }

    @discardableResult
    func upsertBleContact(
        name: String,
        sessionCode: String,
        aesKeyBase64: String,
        isVerified: Bool = false,
        verifiedIdentityKey: String? = nil,
        verifiedAt: Date? = nil,
        remoteSessionCode: String?,
        remotePlatform: ContactRemotePlatform,
        bleShareId: String?,
        lastKnownBleAddress: String?,
        remoteDeviceId: String?
    ) -> ContactRecord {
        let normalizedSession = normalizeSessionCode(sessionCode)
        let normalizedRemoteSession = remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedShareId = bleShareId?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().nilIfEmpty
        let normalizedAddress = lastKnownBleAddress?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased().nilIfEmpty
        let normalizedDeviceId = remoteDeviceId?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedVerifiedIdentityKey = verifiedIdentityCandidate(
            verifiedIdentityKey: verifiedIdentityKey,
            remoteDeviceId: normalizedDeviceId
        )
        let incomingTrusted = isVerified && normalizedVerifiedIdentityKey != nil
        let canonicalKey = canonicalAESKey(aesKeyBase64)
        let now = Date()
        let stableKey = normalizedVerifiedIdentityKey
            ?? normalizedDeviceId
            ?? normalizedRemoteSession
            ?? normalizedAddress
            ?? normalizedShareId
            ?? normalizedSession
        let stableId = BroadcastSessionId.fromRawIdentifier(stableKey)
        var updatedRecord: ContactRecord!
        var snapshot: [ContactRecord] = []
        var migratedSessionPairs: [(UUID, UUID)] = []

        stateQueue.sync {
            var next = storedContacts
            let incomingKeys = ContactLookupKeys.make(
                sessionTokens: [normalizedSession, normalizedRemoteSession],
                identityTokens: [normalizedVerifiedIdentityKey, normalizedDeviceId],
                bleShareId: normalizedShareId,
                bleAddress: normalizedAddress
            )
            let matchingIndexes = matchingContactIndexes(in: next, incoming: incomingKeys)

            if let canonicalIndex = preferredCanonicalIndex(
                in: next,
                matchingIndexes: matchingIndexes,
                preferredStableId: stableId,
                preferredVerifiedIdentityKey: normalizedVerifiedIdentityKey,
                preferredRemoteDeviceId: normalizedDeviceId
            ) {
                let duplicateIds = matchingIndexes
                    .filter { $0 != canonicalIndex }
                    .map { next[$0].id }
                let matchingIndexSet = Set(matchingIndexes)
                var mergedRecord = next[canonicalIndex]
                for index in matchingIndexes where index != canonicalIndex {
                    mergedRecord = mergeDuplicateRecord(primary: mergedRecord, secondary: next[index])
                }

                let preservedTrust = sanitizedTrustState(
                    isVerified: mergedRecord.isVerified,
                    verifiedIdentityKey: mergedRecord.verifiedIdentityKey,
                    verifiedAt: mergedRecord.verifiedAt
                )

                mergedRecord.name = name
                mergedRecord.sessionCode = normalizedSession
                mergedRecord.isVerified = incomingTrusted || preservedTrust.isVerified
                mergedRecord.verifiedIdentityKey = incomingTrusted
                    ? normalizedVerifiedIdentityKey
                    : preservedTrust.verifiedIdentityKey
                mergedRecord.verifiedAt = incomingTrusted
                    ? (verifiedAt ?? preservedTrust.verifiedAt ?? now)
                    : preservedTrust.verifiedAt
                mergedRecord.remoteSessionCode = normalizedRemoteSession ?? mergedRecord.remoteSessionCode
                if !canonicalKey.isEmpty {
                    mergedRecord.aesKeyBase64 = canonicalKey
                }
                mergedRecord.preferredTransport = .bleGatt
                mergedRecord.remotePlatform = remotePlatform == .unknown ? mergedRecord.remotePlatform : remotePlatform
                mergedRecord.bleShareId = normalizedShareId ?? mergedRecord.bleShareId
                mergedRecord.lastKnownBleAddress = normalizedAddress ?? mergedRecord.lastKnownBleAddress
                mergedRecord.remoteDeviceId = normalizedDeviceId ?? mergedRecord.remoteDeviceId
                mergedRecord.lastUpdated = now

                next = next.enumerated().compactMap { index, record in
                    if matchingIndexSet.contains(index), index != canonicalIndex {
                        return nil
                    }
                    return record
                }
                if let survivorIndex = next.firstIndex(where: { $0.id == mergedRecord.id }) {
                    next[survivorIndex] = mergedRecord
                } else {
                    next.append(mergedRecord)
                }
                updatedRecord = mergedRecord
                migratedSessionPairs = duplicateIds.map { ($0, mergedRecord.id) }
            } else {
                let record = ContactRecord(
                    id: stableId,
                    name: name,
                    broadcastId: "",
                    sessionCode: normalizedSession,
                    isVerified: incomingTrusted,
                    verifiedIdentityKey: normalizedVerifiedIdentityKey,
                    verifiedAt: incomingTrusted ? (verifiedAt ?? now) : nil,
                    remoteSessionCode: normalizedRemoteSession,
                    aesKeyBase64: canonicalKey,
                    preferredTransport: .bleGatt,
                    remotePlatform: remotePlatform,
                    bleShareId: normalizedShareId,
                    lastKnownBleAddress: normalizedAddress,
                    remoteDeviceId: normalizedDeviceId,
                    createdAt: now,
                    lastUpdated: now
                )
                next.append(record)
                updatedRecord = record
            }
            storedContacts = next
            snapshot = next
        }

        migrateSessionsIfNeeded(migratedSessionPairs)
        publish(snapshot)
        return updatedRecord
    }

    @discardableResult
    func markVerified(
        sessionId: UUID,
        verifiedIdentityKey: String? = nil,
        verifiedAt: Date = Date()
    ) -> Bool {
        var snapshot: [ContactRecord] = []
        var changed = false
        stateQueue.sync {
            var next = storedContacts
            guard let index = next.firstIndex(where: { $0.id == sessionId }) else {
                return
            }
            let trustKey = verifiedIdentityCandidate(
                verifiedIdentityKey: verifiedIdentityKey,
                remoteDeviceId: next[index].remoteDeviceId
            )
            guard let trustKey else {
                return
            }
            if next[index].isVerified == false ||
                next[index].verifiedIdentityKey != trustKey ||
                next[index].verifiedAt == nil {
                next[index].isVerified = true
                next[index].verifiedIdentityKey = trustKey
                next[index].verifiedAt = verifiedAt
                next[index].lastUpdated = verifiedAt
                storedContacts = next
                snapshot = next
                changed = true
            }
        }
        if changed {
            publish(snapshot)
        }
        return changed
    }

    private func normalizeSessionCode(_ sessionCode: String) -> String {
        let trimmed = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "ble:unknown" }
        if trimmed.lowercased().hasPrefix("ble:") {
            return trimmed
        }
        return "ble:\(trimmed)"
    }

    private func canonicalAESKey(_ base64: String) -> String {
        let trimmed = base64.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let data = Data(base64Encoded: trimmed, options: [.ignoreUnknownCharacters]),
              data.count == 32 else {
            return ""
        }
        return data.base64EncodedString()
    }

    private func matchingContactIndexes(
        in contacts: [ContactRecord],
        incoming: ContactLookupKeys
    ) -> [Int] {
        contacts.indices.filter { index in
            ContactLookupKeys.from(record: contacts[index]).matches(incoming)
        }
    }

    private func preferredCanonicalIndex(
        in contacts: [ContactRecord],
        matchingIndexes: [Int],
        preferredStableId: UUID,
        preferredVerifiedIdentityKey: String?,
        preferredRemoteDeviceId: String?
    ) -> Int? {
        matchingIndexes.sorted { lhs, rhs in
            let lhsRecord = contacts[lhs]
            let rhsRecord = contacts[rhs]
            let lhsScore = canonicalScore(
                for: lhsRecord,
                preferredStableId: preferredStableId,
                preferredVerifiedIdentityKey: preferredVerifiedIdentityKey,
                preferredRemoteDeviceId: preferredRemoteDeviceId
            )
            let rhsScore = canonicalScore(
                for: rhsRecord,
                preferredStableId: preferredStableId,
                preferredVerifiedIdentityKey: preferredVerifiedIdentityKey,
                preferredRemoteDeviceId: preferredRemoteDeviceId
            )
            if lhsScore != rhsScore {
                return lhsScore > rhsScore
            }
            if lhsRecord.createdAt != rhsRecord.createdAt {
                return lhsRecord.createdAt < rhsRecord.createdAt
            }
            return lhsRecord.id.uuidString < rhsRecord.id.uuidString
        }.first
    }

    private func canonicalScore(
        for record: ContactRecord,
        preferredStableId: UUID,
        preferredVerifiedIdentityKey: String?,
        preferredRemoteDeviceId: String?
    ) -> Int {
        var score = 0
        if record.id == preferredStableId {
            score += 200
        }
        if let preferredVerifiedIdentityKey,
           record.verifiedIdentityKey?.caseInsensitiveCompare(preferredVerifiedIdentityKey) == .orderedSame {
            score += 120
        }
        if record.isVerified && record.verifiedIdentityKey != nil {
            score += 80
        }
        if let preferredRemoteDeviceId,
           record.remoteDeviceId?.caseInsensitiveCompare(preferredRemoteDeviceId) == .orderedSame {
            score += 60
        }
        if record.preferredTransport == .bleGatt {
            score += 40
        }
        if record.remoteDeviceId?.nilIfEmpty != nil {
            score += 20
        }
        if record.remoteSessionCode?.nilIfEmpty != nil {
            score += 10
        }
        if record.broadcastId.nilIfEmpty != nil {
            score += 5
        }
        return score
    }

    private func mergeDuplicateRecord(primary: ContactRecord, secondary: ContactRecord) -> ContactRecord {
        var merged = primary

        if shouldAdoptContactName(current: merged.name, incoming: secondary.name) {
            merged.name = secondary.name
        }
        if merged.broadcastId.nilIfEmpty == nil, let secondaryBroadcastId = secondary.broadcastId.nilIfEmpty {
            merged.broadcastId = secondaryBroadcastId
        }
        if isPlaceholderSessionCode(merged.sessionCode), let secondarySessionCode = secondary.sessionCode.nilIfEmpty {
            merged.sessionCode = secondarySessionCode
        }
        if merged.remoteSessionCode?.nilIfEmpty == nil {
            merged.remoteSessionCode = secondary.remoteSessionCode?.nilIfEmpty
        }
        if merged.aesKeyBase64.isEmpty, !secondary.aesKeyBase64.isEmpty {
            merged.aesKeyBase64 = secondary.aesKeyBase64
        }
        if merged.preferredTransport != .bleGatt, secondary.preferredTransport == .bleGatt {
            merged.preferredTransport = .bleGatt
        }
        if merged.remotePlatform == .unknown {
            merged.remotePlatform = secondary.remotePlatform
        }
        if merged.bleShareId?.nilIfEmpty == nil {
            merged.bleShareId = secondary.bleShareId?.nilIfEmpty
        }
        if merged.lastKnownBleAddress?.nilIfEmpty == nil {
            merged.lastKnownBleAddress = secondary.lastKnownBleAddress?.nilIfEmpty
        }
        if merged.remoteDeviceId?.nilIfEmpty == nil {
            merged.remoteDeviceId = secondary.remoteDeviceId?.nilIfEmpty
        }

        let preservedTrust = sanitizedTrustState(
            isVerified: merged.isVerified,
            verifiedIdentityKey: merged.verifiedIdentityKey,
            verifiedAt: merged.verifiedAt
        )
        if !preservedTrust.isVerified {
            let fallbackTrust = sanitizedTrustState(
                isVerified: secondary.isVerified,
                verifiedIdentityKey: secondary.verifiedIdentityKey,
                verifiedAt: secondary.verifiedAt
            )
            merged.isVerified = fallbackTrust.isVerified
            merged.verifiedIdentityKey = fallbackTrust.verifiedIdentityKey
            merged.verifiedAt = fallbackTrust.verifiedAt
        } else {
            merged.isVerified = preservedTrust.isVerified
            merged.verifiedIdentityKey = preservedTrust.verifiedIdentityKey
            merged.verifiedAt = preservedTrust.verifiedAt
        }

        merged.createdAt = min(merged.createdAt, secondary.createdAt)
        merged.lastUpdated = max(merged.lastUpdated, secondary.lastUpdated)
        return merged
    }

    private func shouldAdoptContactName(current: String, incoming: String) -> Bool {
        let normalizedCurrent = current.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedIncoming = incoming.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedIncoming.isEmpty else { return false }
        if normalizedCurrent.isEmpty {
            return true
        }
        return normalizedCurrent.caseInsensitiveCompare("contact") == .orderedSame
    }

    private func isPlaceholderSessionCode(_ sessionCode: String) -> Bool {
        let normalized = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty || normalized.caseInsensitiveCompare("ble:unknown") == .orderedSame
    }

    private func migrateSessionsIfNeeded(_ pairs: [(UUID, UUID)]) {
        let uniquePairs = Array(
            Set(
                pairs.compactMap { source, target in
                    source == target ? nil : "\(source.uuidString.lowercased())|\(target.uuidString.lowercased())"
                }
            )
        )
        guard !uniquePairs.isEmpty else { return }
        DispatchQueue.main.async {
            uniquePairs.forEach { pair in
                let components = pair.split(separator: "|", maxSplits: 1).map(String.init)
                guard components.count == 2,
                      let sourceId = UUID(uuidString: components[0]),
                      let targetId = UUID(uuidString: components[1]) else {
                    return
                }
                SOSChatStore.shared.mergeSession(from: sourceId, into: targetId)
            }
        }
    }

    private func loadPersisted() {
        persistenceQueue.async { [weak self] in
            guard let self else { return }
            let records: [ContactRecord]
            let needsMigration: Bool
            if let payload = try? LocalEncryptedFileStore.read(from: self.persistenceURL) {
                let decodedRecords = (try? JSONDecoder().decode([ContactRecord].self, from: payload.data)) ?? []
                records = decodedRecords.map { record in
                    let trustState = sanitizedTrustState(
                        isVerified: record.isVerified,
                        verifiedIdentityKey: record.verifiedIdentityKey,
                        verifiedAt: record.verifiedAt
                    )
                    var sanitized = record
                    sanitized.isVerified = trustState.isVerified
                    sanitized.verifiedIdentityKey = trustState.verifiedIdentityKey
                    sanitized.verifiedAt = trustState.verifiedAt
                    return sanitized
                }
                needsMigration = !payload.wasEncrypted || decodedRecords != records
            } else {
                records = []
                needsMigration = false
            }

            if needsMigration {
                self.persist(records)
            }

            self.stateQueue.sync {
                self.storedContacts = records
            }
            DispatchQueue.main.async {
                self.contacts = records
            }
        }
    }

    private func publish(_ snapshot: [ContactRecord]) {
        DispatchQueue.main.async { [weak self] in
            self?.contacts = snapshot
        }
        schedulePersist(snapshot)
    }

    private func schedulePersist(_ snapshot: [ContactRecord]) {
        pendingSave?.cancel()
        let work = DispatchWorkItem { [weak self] in
            self?.persist(snapshot)
        }
        pendingSave = work
        persistenceQueue.asyncAfter(deadline: .now() + 0.4, execute: work)
    }

    private func persist(_ snapshot: [ContactRecord]) {
        let directory = persistenceURL.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(snapshot) else { return }
        try? LocalEncryptedFileStore.write(data, to: persistenceURL)
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
