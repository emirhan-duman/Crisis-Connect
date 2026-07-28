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
    // E2E internet-messaging identity of the peer (Firebase uid + base64 SPKI public key), captured
    // from a QR scan or an incoming message. Mirrors Android's Contact.peerUid/peerPublicKey.
    var peerUid: String?
    var peerPublicKey: String?
    var peerPhotoUrl: String?
    /// True when a directory re-discovery replaced a previously pinned identity key — surfaced as
    /// the safety-number "key changed" warning until the user confirms they verified it.
    var peerKeyChanged: Bool?
    /// True when the directory marks this peer as a child account (never an SOS auto-recipient).
    var peerIsChild: Bool?
    /// The peer's E.164 number - the SPAKE2 password for the offline Bluetooth bridge.
    var peerPhone: String?
    /// Hidden transport-only contact auto-created for an authority (kurum) channel peer;
    /// never shown in contact lists - the conversation lives in the channel thread.
    var isAuthorityBridge: Bool?
    var createdAt: Date
    var lastUpdated: Date

    /// True when this contact can be reached over the E2E internet transport (uid + key both known).
    var supportsInternet: Bool {
        (peerUid?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
            && (peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
    }

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
        peerUid: String? = nil,
        peerPublicKey: String? = nil,
        peerPhotoUrl: String? = nil,
        peerKeyChanged: Bool? = nil,
        peerIsChild: Bool? = nil,
        peerPhone: String? = nil,
        isAuthorityBridge: Bool? = nil,
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
        self.peerUid = peerUid?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.peerPublicKey = peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.peerPhotoUrl = peerPhotoUrl?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        self.peerKeyChanged = peerKeyChanged
        self.peerIsChild = peerIsChild
        self.peerPhone = peerPhone
        self.isAuthorityBridge = isAuthorityBridge
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
        case peerUid
        case peerPublicKey
        case peerPhotoUrl
        case peerKeyChanged
        case peerIsChild
        case peerPhone
        case isAuthorityBridge
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
            peerUid: try container.decodeIfPresent(String.self, forKey: .peerUid),
            peerPublicKey: try container.decodeIfPresent(String.self, forKey: .peerPublicKey),
            peerPhotoUrl: try container.decodeIfPresent(String.self, forKey: .peerPhotoUrl),
            peerKeyChanged: try container.decodeIfPresent(Bool.self, forKey: .peerKeyChanged),
            peerIsChild: try container.decodeIfPresent(Bool.self, forKey: .peerIsChild),
            peerPhone: try container.decodeIfPresent(String.self, forKey: .peerPhone),
            isAuthorityBridge: try container.decodeIfPresent(Bool.self, forKey: .isAuthorityBridge),
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

    /// A stored contact whose remoteSessionCode (or own sessionCode) matches — used to heal a
    /// BLE-only paired contact when the peer announces its internet identity over the relay.
    func contactForRemoteSessionCode(_ remoteSessionCode: String) -> ContactRecord? {
        let normalized = remoteSessionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return stateQueue.sync {
            storedContacts.first {
                $0.remoteSessionCode?.caseInsensitiveCompare(normalized) == .orderedSame
                    || $0.sessionCode.caseInsensitiveCompare(normalized) == .orderedSame
            }
        }
    }

    /// Stamps a peer's internet identity onto the paired BLE contact [id], AND absorbs any separate
    /// internet-only duplicate a preceding plain message may have auto-created for the same uid
    /// (so the user is never left with two threads). The BLE contact is the survivor — it holds the
    /// real BLE session + verification. Only heals a contact whose peerUid is still blank.
    @discardableResult
    func healAndAbsorb(id: UUID, peerUid: String, peerPublicKey: String) -> ContactRecord? {
        let healed = healInternetIdentity(id: id, peerUid: peerUid, peerPublicKey: peerPublicKey)
        guard healed != nil else { return nil }
        let uid = peerUid.trimmingCharacters(in: .whitespacesAndNewlines)
        var survivor: ContactRecord?
        var snapshot: [ContactRecord] = []
        stateQueue.sync {
            var next = storedContacts
            guard let primary = next.firstIndex(where: { $0.id == id }) else { return }
            // Any OTHER contact holding this uid is the auto-created internet-only duplicate; drop it.
            let dupes = next.enumerated().filter {
                $0.offset != primary
                    && $0.element.peerUid?.caseInsensitiveCompare(uid) == .orderedSame
            }.map { $0.element.id }
            if !dupes.isEmpty {
                next.removeAll { dupes.contains($0.id) }
            }
            survivor = next.first { $0.id == id }
            storedContacts = next
            snapshot = next
        }
        if survivor != nil { publish(snapshot) }
        return survivor
    }

    /// Stamps a peer's internet identity onto the contact with [id], but only if it has none yet
    /// (idempotent, never hijacks an established identity). Returns the healed record or nil.
    @discardableResult
    func healInternetIdentity(id: UUID, peerUid: String, peerPublicKey: String) -> ContactRecord? {
        let uid = peerUid.trimmingCharacters(in: .whitespacesAndNewlines)
        let key = peerPublicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !uid.isEmpty, !key.isEmpty else { return nil }
        var healed: ContactRecord?
        var snapshot: [ContactRecord] = []
        stateQueue.sync {
            var next = storedContacts
            guard let index = next.firstIndex(where: { $0.id == id }),
                  (next[index].peerUid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "").isEmpty else {
                return
            }
            next[index].peerUid = uid
            next[index].peerPublicKey = key
            next[index].lastUpdated = Date()
            healed = next[index]
            storedContacts = next
            snapshot = next
        }
        if healed != nil {
            publish(snapshot)
        }
        return healed
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

    /// User-chosen rename. The name a user typed (or their address book supplied) must survive
    /// every automatic update — until this existed there was NO way to fix a typo'd contact name
    /// anywhere in the app, so a bad name at pairing time was permanent.
    func renameContact(id: UUID, to newName: String) {
        let trimmed = newName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let snapshot: [ContactRecord] = stateQueue.sync {
            guard let index = storedContacts.firstIndex(where: { $0.id == id }) else {
                return storedContacts
            }
            storedContacts[index].name = trimmed
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
        remoteDeviceId: String?,
        peerUid: String? = nil,
        peerPublicKey: String? = nil,
        peerPhotoUrl: String? = nil,
        analyticsSource: String? = nil,
        analyticsReceived: Bool = false
    ) -> ContactRecord {
        let normalizedSession = normalizeSessionCode(sessionCode)
        let normalizedRemoteSession = remoteSessionCode?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedPeerUid = peerUid?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedPeerPublicKey = peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedPeerPhotoUrl = peerPhotoUrl?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
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
        var insertedNewRecord = false

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
                mergedRecord.peerUid = normalizedPeerUid ?? mergedRecord.peerUid
                mergedRecord.peerPublicKey = normalizedPeerPublicKey ?? mergedRecord.peerPublicKey
                mergedRecord.peerPhotoUrl = normalizedPeerPhotoUrl ?? mergedRecord.peerPhotoUrl
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
                    peerUid: normalizedPeerUid,
                    peerPublicKey: normalizedPeerPublicKey,
                    peerPhotoUrl: normalizedPeerPhotoUrl,
                    createdAt: now,
                    lastUpdated: now
                )
                next.append(record)
                updatedRecord = record
                insertedNewRecord = true
            }
            storedContacts = next
            snapshot = next
        }
        // Merges/migrations of an existing contact are not adds — only a genuinely new row counts.
        if insertedNewRecord, let analyticsSource, updatedRecord.isAuthorityBridge != true {
            if analyticsReceived {
                AppAnalytics.contactReceived(via: analyticsSource, transport: "ble_gatt")
            } else {
                AppAnalytics.contactAdded(method: analyticsSource, transport: "ble_gatt")
            }
            // A successfully added contact is a calm, positive milestone — the same moment the
            // Android app counts toward its in-app review gating.
            Task { @MainActor in InAppReview.onPositiveEvent() }
        }

        migrateSessionsIfNeeded(migratedSessionPairs)
        publish(snapshot)
        return updatedRecord
    }

    func contactForPeerUid(_ peerUid: String) -> ContactRecord? {
        let normalized = peerUid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty else { return nil }
        return stateQueue.sync {
            storedContacts.first { $0.peerUid?.caseInsensitiveCompare(normalized) == .orderedSame }
        }
    }

    /// Records the E2E internet identity of a peer on the matching contact (found by conversation
    /// session code or by peerUid), creating a minimal internet-only contact when none exists yet.
    /// Used by the QR-add path and by the message receiver (trust-on-first-use). Mirrors the
    /// auto-create in Android's CrisisConnectMessagingService.receiveMessage.
    @discardableResult
    func applyInternetIdentity(
        conversationSessionCode: String,
        peerUid: String,
        peerPublicKey: String,
        displayName: String? = nil,
        peerPhotoUrl: String? = nil,
        peerIsChild: Bool? = nil,
        peerPhone: String? = nil,
        isAuthorityBridge: Bool = false,
        analyticsSource: String? = nil,
        analyticsReceived: Bool = false
    ) -> ContactRecord? {
        let normalizedSession = normalizeSessionCode(conversationSessionCode)
        let normalizedPeerUid = peerUid.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedPeerKey = peerPublicKey.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedPeerUid.isEmpty, !normalizedPeerKey.isEmpty else { return nil }
        let normalizedPhoto = peerPhotoUrl?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let incomingName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let now = Date()

        var result: ContactRecord?
        var snapshot: [ContactRecord] = []
        var insertedNewRecord = false
        stateQueue.sync {
            var next = storedContacts
            let index = next.firstIndex {
                $0.peerUid?.caseInsensitiveCompare(normalizedPeerUid) == .orderedSame
            } ?? next.firstIndex {
                $0.sessionCode.caseInsensitiveCompare(normalizedSession) == .orderedSame
                    || ($0.remoteSessionCode?.caseInsensitiveCompare(normalizedSession) == .orderedSame)
            } ?? next.firstIndex { candidate in
                // Heal a QR/manual/legacy-BLE contact that has NO internet identity yet by matching
                // on name (Android saveInternetContactFromQr parity). Without this the receiver
                // spawned a duplicate "Contact" for the peer's uid, and the user's original thread —
                // the one they type in — stayed supportsInternet=false forever. Only ever adopts a
                // contact whose peerUid is blank, so it can never hijack an established identity.
                guard let incomingName,
                      (candidate.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? "").isEmpty,
                      candidate.isAuthorityBridge != true else { return false }
                return candidate.name.trimmingCharacters(in: .whitespacesAndNewlines)
                    .caseInsensitiveCompare(incomingName) == .orderedSame
            }
            if let index {
                let previousKey = next[index].peerPublicKey?
                    .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                if !previousKey.isEmpty, previousKey != normalizedPeerKey {
                    // A re-discovered key differs from the pinned one: could be a reinstall or a
                    // new phone — or impersonation. Flag it for the safety-number warning.
                    next[index].peerKeyChanged = true
                }
                next[index].peerUid = normalizedPeerUid
                next[index].peerPublicKey = normalizedPeerKey
                if let normalizedPhoto { next[index].peerPhotoUrl = normalizedPhoto }
                if let peerIsChild { next[index].peerIsChild = peerIsChild }
                // Offline-bridge input stays sticky: only backfill a missing number, and a
                // deliberately added contact never becomes a hidden bridge.
                if let peerPhone, (next[index].peerPhone ?? "").isEmpty { next[index].peerPhone = peerPhone }
                if let incomingName, shouldAdoptContactName(current: next[index].name, incoming: incomingName) {
                    next[index].name = incomingName
                }
                next[index].lastUpdated = now
                result = next[index]
            } else {
                let record = ContactRecord(
                    id: BroadcastSessionId.fromRawIdentifier(normalizedPeerUid),
                    name: incomingName ?? "Contact",
                    broadcastId: "",
                    sessionCode: normalizedSession,
                    aesKeyBase64: "",
                    preferredTransport: .legacyBroadcast,
                    remotePlatform: .unknown,
                    peerUid: normalizedPeerUid,
                    peerPublicKey: normalizedPeerKey,
                    peerPhotoUrl: normalizedPhoto,
                    peerIsChild: peerIsChild,
                    peerPhone: peerPhone,
                    isAuthorityBridge: isAuthorityBridge ? true : nil,
                    createdAt: now,
                    lastUpdated: now
                )
                next.append(record)
                result = record
                insertedNewRecord = true
            }
            storedContacts = next
            snapshot = next
        }
        if insertedNewRecord, let analyticsSource, !isAuthorityBridge {
            if analyticsReceived {
                AppAnalytics.contactReceived(via: analyticsSource, transport: "internet")
            } else {
                AppAnalytics.contactAdded(method: analyticsSource, transport: "internet")
            }
            Task { @MainActor in InAppReview.onPositiveEvent() }
        }
        publish(snapshot)
        return result
    }

    /// The user compared the safety number and confirmed the new key — clear the warning.
    func acknowledgePeerKeyChange(contactId: UUID) {
        var snapshot: [ContactRecord] = []
        stateQueue.sync {
            var next = storedContacts
            guard let index = next.firstIndex(where: { $0.id == contactId }) else { return }
            next[index].peerKeyChanged = nil
            next[index].lastUpdated = Date()
            storedContacts = next
            snapshot = next
        }
        guard !snapshot.isEmpty else { return }
        publish(snapshot)
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
        if merged.peerUid?.nilIfEmpty == nil {
            merged.peerUid = secondary.peerUid?.nilIfEmpty
        }
        if merged.peerPublicKey?.nilIfEmpty == nil {
            merged.peerPublicKey = secondary.peerPublicKey?.nilIfEmpty
        }
        if merged.peerPhotoUrl?.nilIfEmpty == nil {
            merged.peerPhotoUrl = secondary.peerPhotoUrl?.nilIfEmpty
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
