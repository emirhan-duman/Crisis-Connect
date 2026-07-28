//
//  InternetMessagingClient.swift
//  Crisis Connect
//
//  iOS client for the messaging Cloud Functions (Faz 4 port of the Android `InternetMessagingClient`).
//  All callables are App Check + Auth enforced server-side (App Check is installed globally at launch),
//  region us-central1. The server only ever sees the opaque sealed envelope produced by
//  `InternetE2eEnvelope`; it can route but never decrypt.
//

import Foundation
import FirebaseFunctions
import FirebaseAuth
import FirebaseFirestore

/// Public identity key resolved for a contact.
struct PeerIdentityKey {
    let uid: String
    let publicKeyBase64: String
    let keyId: String
    let alg: String
    let displayName: String
    let photoUrl: String
}

/// An address-book number that turned out to belong to a discoverable Crisis Connect user.
struct DiscoveredContact {
    let phone: String
    let uid: String
    let publicKeyBase64: String
    let keyId: String
    let alg: String
    let displayName: String
    let photoUrl: String
    let isChild: Bool
}

/// The routing fields the server needs, plus the opaque sealed content. `senderUid` is added
/// server-side (relay) or from the auth session (direct write) so it can never be forged.
/// A message envelope the relay/direct-write paths can persist. Both crypto layers implement it —
/// mirrors Android's `RelayableEnvelope`.
protocol RelayableInternetEnvelope {
    var messageId: String { get }
    var createdAtMs: Int { get }
    var ttlMs: Int { get }
    func toMap() -> [String: Any]
    /// A copy safe for the relayMessage callable, whose id regex historically rejected '~' (used by
    /// the store-and-forward "uuid~createdAt" ids). Direct Firestore writes accept '~'.
    func sanitizedForCallable() -> RelayableInternetEnvelope
}

/// v1 (ECIES) envelope: routing fields + the opaque sealed content.
struct OutgoingInternetEnvelope: RelayableInternetEnvelope {
    let messageId: String
    let conversationId: String
    let recipientUid: String
    let priority: String // "normal" | "high"
    let createdAtMs: Int
    let ttlMs: Int
    let sealed: SealedInternetMessage
    // Routing-only marker (never plaintext): true on a call OFFER so the backend fires an iOS
    // PushKit VoIP ring for a force-quit recipient.
    var callRing: Bool = false

    func toMap() -> [String: Any] {
        var map: [String: Any] = [
            "v": InternetMessagingClient.schemaVersion,
            "messageId": messageId,
            "conversationId": conversationId,
            "recipientUid": recipientUid,
            "priority": priority,
            "createdAtMs": createdAtMs,
            "ttlMs": ttlMs,
            "alg": sealed.alg,
            "ephemeralPubKey": sealed.ephemeralPublicKey,
            "nonce": sealed.nonce,
            "ciphertext": sealed.ciphertext
        ]
        if callRing { map["callRing"] = true }
        return map
    }

    func sanitizedForCallable() -> RelayableInternetEnvelope {
        OutgoingInternetEnvelope(
            messageId: messageId.replacingOccurrences(of: "~", with: ":"),
            conversationId: conversationId,
            recipientUid: recipientUid,
            priority: priority,
            createdAtMs: createdAtMs,
            ttlMs: ttlMs,
            sealed: sealed,
            callRing: callRing
        )
    }
}

/// v2 (Signal) envelope: same routing fields, content is a Double-Ratchet ciphertext tagged with
/// ctype ("prekey" for the handshake message, "signal" afterwards). No ECDH fields — byte- and
/// field-compatible with Android's `OutgoingSignalEnvelope`.
struct OutgoingSignalInternetEnvelope: RelayableInternetEnvelope {
    let messageId: String
    let conversationId: String
    let recipientUid: String
    let priority: String
    let createdAtMs: Int
    let ttlMs: Int
    let ctype: String
    let ciphertextBase64: String
    // See OutgoingInternetEnvelope.callRing.
    var callRing: Bool = false

    func toMap() -> [String: Any] {
        var map: [String: Any] = [
            "v": 2,
            "messageId": messageId,
            "conversationId": conversationId,
            "recipientUid": recipientUid,
            "priority": priority,
            "createdAtMs": createdAtMs,
            "ttlMs": ttlMs,
            "ctype": ctype,
            "ciphertext": ciphertextBase64
        ]
        if callRing { map["callRing"] = true }
        return map
    }

    func sanitizedForCallable() -> RelayableInternetEnvelope {
        OutgoingSignalInternetEnvelope(
            messageId: messageId.replacingOccurrences(of: "~", with: ":"),
            conversationId: conversationId,
            recipientUid: recipientUid,
            priority: priority,
            createdAtMs: createdAtMs,
            ttlMs: ttlMs,
            ctype: ctype,
            ciphertextBase64: ciphertextBase64,
            callRing: callRing
        )
    }
}

enum InternetMessagingClientError: Error {
    case notSignedIn
    case malformedResponse
}

final class InternetMessagingClient {
    static let schemaVersion = 1
    static let region = "us-central1"
    static let defaultTtlMs = 24 * 60 * 60 * 1000

    private let functions: Functions
    private let auth: Auth
    private let firestore: Firestore

    init(
        functions: Functions = Functions.functions(region: InternetMessagingClient.region),
        auth: Auth = Auth.auth(),
        firestore: Firestore = Firestore.firestore()
    ) {
        self.functions = functions
        self.auth = auth
        self.firestore = firestore
    }

    var currentUid: String? { auth.currentUser?.uid }

    // MARK: - Identity directory

    func publishIdentityKey(
        publicKeyBase64: String,
        discoverable: Bool,
        phone: String? = nil,
        username: String? = nil,
        displayName: String? = nil,
        photoUrl: String? = nil,
        isChild: Bool = false
    ) async throws {
        var payload: [String: Any] = [
            "publicKey": publicKeyBase64,
            "alg": InternetE2eEnvelope.alg,
            "discoverable": discoverable,
            "isChild": isChild
        ]
        if discoverable {
            if let phone, !phone.isEmpty { payload["phone"] = phone }
            if let username, !username.isEmpty { payload["username"] = username }
        }
        if let displayName, !displayName.isEmpty { payload["displayName"] = displayName }
        if let photoUrl, !photoUrl.isEmpty { payload["photoUrl"] = photoUrl }
        _ = try await functions.httpsCallable("publishIdentityKey").callAsync(payload)
    }

    func lookupIdentityKey(uid: String? = nil, phone: String? = nil, username: String? = nil) async throws -> PeerIdentityKey {
        var payload: [String: Any] = [:]
        if let uid, !uid.isEmpty { payload["uid"] = uid }
        if let phone, !phone.isEmpty { payload["phone"] = phone }
        if let username, !username.isEmpty { payload["username"] = username }

        let result = try await functions.httpsCallable("lookupIdentityKey").callAsync(payload)
        guard let data = result.data as? [String: Any],
              let uid = data["uid"] as? String,
              let publicKey = data["publicKey"] as? String else {
            throw InternetMessagingClientError.malformedResponse
        }
        return PeerIdentityKey(
            uid: uid,
            publicKeyBase64: publicKey,
            keyId: data["keyId"] as? String ?? "",
            alg: data["alg"] as? String ?? "",
            displayName: data["displayName"] as? String ?? "",
            photoUrl: data["photoUrl"] as? String ?? ""
        )
    }

    /// Batch contact discovery: hand the server E.164 numbers from the address book and get back only
    /// the ones that belong to discoverable Crisis Connect users. The server never stores the query.
    func findContacts(phones: [String]) async throws -> [DiscoveredContact] {
        guard !phones.isEmpty else { return [] }
        let result = try await functions.httpsCallable("findContactsOnCrisisConnect")
            .callAsync(["phones": phones])
        guard let data = result.data as? [String: Any],
              let matches = data["matches"] as? [Any] else {
            return []
        }
        return matches.compactMap { raw in
            guard let m = raw as? [String: Any],
                  let phone = m["phone"] as? String,
                  let uid = m["uid"] as? String,
                  let publicKey = m["publicKey"] as? String else { return nil }
            return DiscoveredContact(
                phone: phone,
                uid: uid,
                publicKeyBase64: publicKey,
                keyId: m["keyId"] as? String ?? "",
                alg: m["alg"] as? String ?? "",
                displayName: m["displayName"] as? String ?? "",
                photoUrl: m["photoUrl"] as? String ?? "",
                isChild: m["isChild"] as? Bool ?? false
            )
        }
    }

    // MARK: - Push tokens

    func registerPushToken(_ token: String, platform: String = "ios") async throws {
        _ = try await functions.httpsCallable("registerPushToken")
            .callAsync(["token": token, "platform": platform])
    }

    func unregisterPushToken(_ token: String) async throws {
        _ = try await functions.httpsCallable("unregisterPushToken").callAsync(["token": token])
    }

    // MARK: - Send / ack

    /// Crisis-mode compact uplink. Returns the accepted messageId.
    @discardableResult
    func relayMessage(_ envelope: RelayableInternetEnvelope) async throws -> String {
        _ = try await functions.httpsCallable("relayMessage").callAsync(envelope.toMap())
        return envelope.messageId
    }

    /// Normal-mode direct Firestore write of the same ciphertext envelope (realtime path).
    func writeMessageDoc(_ envelope: RelayableInternetEnvelope) async throws {
        guard let uid = currentUid else { throw InternetMessagingClientError.notSignedIn }
        var data = envelope.toMap()
        data["senderUid"] = uid
        // expireAt mirrors what the relay stamps server-side; without it a never-acked direct-write
        // doc is invisible to the purgeExpiredMessages sweep and replays on every listener attach.
        data["expireAt"] = Timestamp(
            date: Date(timeIntervalSince1970: Double(envelope.createdAtMs + envelope.ttlMs) / 1000)
        )
        try await firestore.collection("messages").document(envelope.messageId).setDataAsync(data)
    }

    /// Confirms a message was received and stored so the server deletes its copy (WhatsApp-style).
    /// Best-effort: failure just means the server's safety-net purge cleans it up later.
    func acknowledgeMessage(_ messageId: String) async {
        _ = try? await functions.httpsCallable("acknowledgeMessage").callAsync(["messageId": messageId])
    }
}
