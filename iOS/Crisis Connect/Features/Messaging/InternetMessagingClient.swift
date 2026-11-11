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
import FirebaseCore

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
    case constrainedNetworkDisallowed
}

final class InternetMessagingClient {
    static let schemaVersion = 1
    static let region = "us-central1"
    static let defaultTtlMs = 24 * 60 * 60 * 1000

    private let functions: Functions
    private let auth: Auth
    private let firestore: Firestore
    private let regionalCalls: RegionalMessagingCalls?

    init(
        functions: Functions? = nil,
        auth: Auth = Auth.auth(),
        firestore: Firestore? = nil,
        regionalCalls: RegionalMessagingCalls? = nil
    ) {
        self.functions = functions ?? Functions.functions(region: InternetMessagingClient.region)
        self.auth = auth
        self.firestore = firestore ?? (functions == nil && MessagingDatabaseTarget.highAvailabilityEnabled
            ? Firestore.firestore(app: FirebaseApp.app()!, database: MessagingDatabaseTarget.databaseID)
            : Firestore.firestore())
        self.regionalCalls = regionalCalls ?? (functions == nil ? RegionalMessagingCalls() : nil)
    }

    var currentUid: String? { auth.currentUser?.uid }
    var usesRegionalDelivery: Bool { regionalCalls != nil }
    private func callRegional(_ operation: String, _ payload: [String: Any]) async throws -> Any {
        guard let uid = currentUid, let regionalCalls else { throw InternetMessagingClientError.notSignedIn }
        var request=payload;request["requestUid"]=uid
        return try await regionalCalls.call(operation, request, accountCurrent: { self.currentUid == uid })
    }
    func callRouted(_ operation: String, _ payload: [String: Any] = [:]) async throws -> Any {
        if regionalCalls != nil { return try await callRegional(operation, payload) }
        return try await functions.httpsCallable(operation).callAsync(payload).data
    }
    var messageStore: Firestore { firestore }

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
        _ = try await callRouted("publishIdentityKey", payload)
    }

    func lookupIdentityKey(uid: String? = nil, phone: String? = nil, username: String? = nil) async throws -> PeerIdentityKey {
        var payload: [String: Any] = [:]
        if let uid, !uid.isEmpty { payload["uid"] = uid }
        if let phone, !phone.isEmpty { payload["phone"] = phone }
        if let username, !username.isEmpty { payload["username"] = username }

        let result = try await callRouted("lookupIdentityKey", payload)
        guard let data = result as? [String: Any],
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
        let result = try await callRouted("findContactsOnCrisisConnect", ["phones": phones])
        guard let data = result as? [String: Any],
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
        _ = try await callRouted("registerPushToken", ["token": token, "platform": platform,
                "installationId": MessagingInstallation.id, "trafficPolicyVersion": 1])
    }

    func unregisterPushToken(_ token: String) async throws {
        _ = try await callRouted("unregisterPushToken", ["token": token])
    }

    /// Content-free wake; the callable authorizes the caller and derives the peer from the MLS parent.
    func requestAuthorityMlsPreparation(conversationId: String) async throws {
        if ResilientApplicationStore.enabled {
            _ = try await callRouted("requestAuthorityMlsPreparation", ["conversationId": conversationId])
            return
        }
        _ = try await functions.httpsCallable("requestAuthorityMlsPreparation")
            .callAsync(["conversationId": conversationId])
    }

    // MARK: - Send / ack

    /// Crisis-mode compact uplink. Returns the accepted messageId.
    @discardableResult
    func relayMessage(_ envelope: RelayableInternetEnvelope) async throws -> String {
        if let regionalCalls {
            var payload = envelope.toMap(); payload["trafficPolicyVersion"] = 2
            _ = try await callRegional("relayMessage", payload)
            return envelope.messageId
        }
        let callable = functions.httpsCallable("relayMessage")
        callable.timeoutInterval = 20
        var payload = envelope.toMap(); payload["trafficPolicyVersion"] = 2
        _ = try await callable.callAsync(payload)
        return envelope.messageId
    }

    /// Normal-mode direct Firestore write of the same ciphertext envelope (realtime path).
    func writeMessageDoc(_ envelope: RelayableInternetEnvelope) async throws {
        if let regionalCalls {
            var payload = envelope.toMap(); payload["trafficPolicyVersion"] = 1
            _ = try await callRegional("relayMessage", payload)
            return
        }
        guard let uid = currentUid else { throw InternetMessagingClientError.notSignedIn }
        var data = envelope.toMap()
        data["senderUid"] = uid
        // expireAt mirrors what the relay stamps server-side; without it a never-acked direct-write
        // doc is invisible to the purgeExpiredMessages sweep and replays on every listener attach.
        data["expireAt"] = Timestamp(
            date: Date(timeIntervalSince1970: Double(envelope.createdAtMs + envelope.ttlMs) / 1000)
        )
        // Firestore queues offline writes indefinitely. Bound the wait; the relay uses the SAME
        // ID/envelope, so a late SDK completion cannot create a second message or push.
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let completion = MessageWriteCompletion(continuation)
            firestore.collection("messages").document(envelope.messageId).setData(data) { error in
                completion.finish(error: error)
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + 12) {
                completion.finish(error: URLError(.timedOut))
            }
        }
    }

    /// Confirms a message was received and stored so the server deletes its copy (WhatsApp-style).
    /// Best-effort: failure just means the server's safety-net purge cleans it up later.
    func acknowledgeMessage(_ messageId: String, expectedUid: String? = nil) async {
        guard let uid = currentUid else { return }
        if let expectedUid, uid != expectedUid { return }
        CompactInboxState.enqueue(uid: uid,id: messageId)
        guard !DisasterTrafficController.shared.isSaving else { return }
        do {
            let callable = functions.httpsCallable("acknowledgeMessage")
            callable.timeoutInterval = 12
            if regionalCalls != nil { _ = try await callRegional("acknowledgeMessage", ["messageId": messageId]) }
            else { _ = try await callable.callAsync(["messageId": messageId]) }
            CompactInboxState.confirm(uid: uid,ids: [messageId])
        } catch { /* The persisted journal is retried by the inbox worker. */ }
    }

    func compactInbox(cursor: String?, receive: Bool = true) async throws -> (messages: [[String: Any]], cursor: String?) {
        guard let uid = currentUid else { throw InternetMessagingClientError.notSignedIn }
        let ids = CompactInboxState.pending(uid: uid)
        var payload: [String: Any] = ["acknowledgements": ids]
        payload["installationId"] = MessagingInstallation.id
        payload["policyVersion"] = 2
        payload["profileRevision"] = CompactInboxState.nextRevision(uid: uid)
        payload["receive"] = receive
        payload["saving"] = DisasterTrafficController.shared.isSaving
        if let cursor { payload["cursor"] = cursor }
        let callable = functions.httpsCallable("compactInbox")
        callable.timeoutInterval = 20
        let result: Any
        do {
            if regionalCalls != nil { result = try await callRegional("compactInbox", payload) }
            else { result = try await callable.callAsync(payload).data }
        }
        catch { TrafficMeasurements.shared.request(succeeded:false); throw error }
        try Task.checkCancellation()
        guard currentUid == uid, let data = result as? [String: Any],
              let messages = data["messages"] as? [[String: Any]] else {
            throw InternetMessagingClientError.malformedResponse
        }
        CompactInboxState.confirm(uid: uid, ids: ids)
        TrafficMeasurements.shared.request(succeeded:true,responseJsonBytes:(try? JSONSerialization.data(withJSONObject:data).count) ?? 0)
        DisasterTrafficController.shared.applyServerPolicy(data["policy"] as? [String: Any])
        UserDefaults.standard.set(payload["saving"] as? Bool ?? false, forKey: "messaging.compactProfile.\(uid)")
        return (messages, data["cursor"] as? String)
    }
}

private final class MessageWriteCompletion: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    init(_ continuation: CheckedContinuation<Void, Error>) { self.continuation = continuation }
    func finish(error: Error?) {
        lock.lock()
        let pending = continuation
        continuation = nil
        lock.unlock()
        if let error { pending?.resume(throwing: error) } else { pending?.resume() }
    }
}
