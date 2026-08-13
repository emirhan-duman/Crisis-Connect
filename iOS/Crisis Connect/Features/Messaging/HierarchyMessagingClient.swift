//
//  HierarchyMessagingClient.swift
//  Crisis Connect
//
//  iOS client for the dashboard's cross-panel (hierarchy) messaging, mirroring Android's
//  HierarchyMessagingClient and the web `lib/messaging/hierarchy.ts`. Entitlement + the shared
//  per-channel key are decided server-side by the Next.js route `/api/messaging/hierarchy`
//  (called over HTTPS with the Firebase ID token); messages themselves are AES-256-GCM under
//  that key with AAD = channelId, stored as ciphertext under
//  hierarchyChannels/{channelId}/secureMessages — byte-compatible with web and Android.
//

import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import Foundation

/// A manager on the far side of a cross-panel channel (a candidate recipient).
/// Codable so the kurum home rows can be cached to disk for offline starts (Android Room parity).
struct HierarchyPeer: Identifiable, Equatable, Codable {
    var id: String { uid }
    let uid: String
    let name: String
    let role: String?
    let photoUrl: String?
    let agency: String?
    /// E.164 phone (entitled managers only) — the SPAKE2 password for the offline Bluetooth bridge.
    var phone: String? = nil
}

/// A cross-panel channel: my panel ↔ one related panel (parent/child/sibling), with its roster.
struct HierarchyChannel: Identifiable, Equatable, Codable {
    var id: String { channelId }
    let channelId: String
    let peerPanelId: String
    let peerPanelName: String
    let group: String
    let peers: [HierarchyPeer]
}

/// The shared per-channel AES key, issued by /api/messaging/hierarchy after re-verifying entitlement.
struct HierarchyChannelKey {
    let keyId: String
    let key: SymmetricKey
    let channelId: String
}

/// A decrypted cross-panel message, including any decoded attachment descriptors.
struct HierarchyMessage: Identifiable, Equatable {
    let id: String
    let senderUid: String
    let senderName: String
    let recipientUid: String
    let recipientName: String
    let text: String
    let at: Date
    let attachments: [ChannelAttachment]
    /// Original Bluetooth-bridge uuid on docs backfilled from the offline transport (dedup key).
    var clientUuid: String = ""

    var hasAttachment: Bool { !attachments.isEmpty }
}

enum HierarchyMessagingError: Error {
    case notSignedIn
    case baseUrlMissing
    case requestFailed
    case keyMissing
    case legacyWriteDisabled
}

final class HierarchyMessagingClient: @unchecked Sendable {
    private static let apiPath = "api/messaging/hierarchy"
    private static let previewScanLimit = 25

    private let db = Firestore.firestore()

    // MARK: - Web API (channel discovery + keys)

    /// Lists the cross-panel channels this manager may use, each with the peer panel's roster.
    /// A 401/403 (not a manager / not entitled) yields an empty list rather than an error.
    func fetchChannels() async throws -> [HierarchyChannel] {
        guard let body = try await execute(method: "GET", json: nil) else { return [] }
        guard let root = try? JSONSerialization.jsonObject(with: body) as? [String: Any],
              let channels = root["channels"] as? [[String: Any]] else {
            return []
        }
        return channels.compactMap { obj -> HierarchyChannel? in
            let channelId = (obj["channelId"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            guard !channelId.isEmpty else { return nil }
            let peers = (obj["peers"] as? [[String: Any]] ?? []).compactMap { peer -> HierarchyPeer? in
                guard let uid = (peer["uid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !uid.isEmpty else { return nil }
                let name = (peer["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
                return HierarchyPeer(
                    uid: uid,
                    name: (name?.isEmpty == false) ? name! : uid,
                    role: (peer["role"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    photoUrl: (peer["photoUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    agency: (peer["agency"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                    phone: (peer["phone"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                )
            }
            return HierarchyChannel(
                channelId: channelId,
                peerPanelId: obj["peerPanelId"] as? String ?? "",
                peerPanelName: obj["peerPanelName"] as? String ?? "",
                group: obj["group"] as? String ?? "",
                peers: peers
            )
        }
    }

    /// Retired: server-issued shared keys violate the AuthorityChat MLS-only invariant.
    func fetchChannelKey(channelId _: String) async throws -> HierarchyChannelKey {
        throw HierarchyMessagingError.legacyWriteDisabled
    }

    // MARK: - Messages (Firestore ciphertext)

    private func channel(_ channelId: String) -> CollectionReference {
        db.collection("hierarchyChannels").document(channelId).collection("secureMessages")
    }

    /// Realtime subscription to my 1:1 conversation with `peerUid` inside the channel: decrypts
    /// each pair message and delivers the ordered list.
    func listenConversation(
        channelKey: HierarchyChannelKey,
        myUid: String,
        peerUid: String,
        onMessages: @escaping ([HierarchyMessage]) -> Void
    ) -> ListenerRegistration {
        channel(channelKey.channelId)
            .whereField("participants", arrayContains: myUid)
            .order(by: "createdAt")
            .limit(toLast: 200)
            .addSnapshotListener { snapshot, _ in
                guard let documents = snapshot?.documents else { return }
                let messages: [HierarchyMessage] = documents.compactMap { document in
                    let data = document.data()
                    let sender = data["senderUid"] as? String ?? ""
                    let recipient = data["recipientUid"] as? String ?? ""
                    guard (sender == myUid && recipient == peerUid)
                        || (sender == peerUid && recipient == myUid) else {
                        return nil
                    }
                    var text = ""
                    if let nonce = data["nonce"] as? String,
                       let ciphertext = data["ciphertext"] as? String {
                        text = (try? Self.decrypt(
                            channelKey: channelKey, nonceBase64: nonce, ciphertextBase64: ciphertext
                        )) ?? "⚠️"
                    }
                    return HierarchyMessage(
                        id: document.documentID,
                        senderUid: sender,
                        senderName: data["senderName"] as? String ?? "",
                        recipientUid: recipient,
                        recipientName: data["recipientName"] as? String ?? "",
                        text: text,
                        at: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date(),
                        attachments: ChannelAttachments.decodeAttachments(
                            key: channelKey.key,
                            aad: channelKey.channelId,
                            attMeta: data["attMeta"] as? String,
                            attMetaNonce: data["attMetaNonce"] as? String
                        ),
                        clientUuid: data["clientUuid"] as? String ?? ""
                    )
                }
                onMessages(messages)
            }
    }

    /// The newest pair message with `peerUid`, or nil when they've never messaged — nil keeps the
    /// channels list to real conversations, non-nil carries the row preview.
    func latestMessageWith(
        channelId: String,
        peerUid: String,
        channelKey: HierarchyChannelKey?
    ) async -> (text: String, at: Date, senderUid: String, peerName: String?, attachmentKind: String)? {
        guard let myUid = Auth.auth().currentUser?.uid else { return nil }
        let snapshot = try? await channel(channelId)
            .whereField("participants", arrayContains: myUid)
            .order(by: "createdAt", descending: true)
            .limit(to: Self.previewScanLimit)
            .getDocuments()
        let pairDocs = (snapshot?.documents ?? []).filter { d in
            let s = d.get("senderUid") as? String ?? ""
            let r = d.get("recipientUid") as? String ?? ""
            return (s == myUid && r == peerUid) || (s == peerUid && r == myUid)
        }
        guard let doc = pairDocs.first else { return nil }
        // The peer's own client publishes their real display name as `senderName` on every message —
        // the reliable source when the hierarchy roster only knows their login email. Newest inbound.
        let rawPeerName = pairDocs
            .first(where: { ($0.get("senderUid") as? String) == peerUid })?
            .get("senderName") as? String
        let trimmedPeerName = rawPeerName?.trimmingCharacters(in: .whitespacesAndNewlines)
        let peerName = (trimmedPeerName?.isEmpty == false) ? trimmedPeerName : nil
        var text = ""
        if let channelKey,
           let nonce = doc.get("nonce") as? String,
           let ciphertext = doc.get("ciphertext") as? String {
            text = (try? Self.decrypt(
                channelKey: channelKey, nonceBase64: nonce, ciphertextBase64: ciphertext
            )) ?? ""
        }
        // Attachment-only last message → its kind, so the home row can say "Photo"/"Voice
        // message"/"Shared file" instead of a paperclip (Android's lastAttachmentKind).
        var attachmentKind = ""
        if text.isEmpty, let channelKey {
            let attachments = ChannelAttachments.decodeAttachments(
                key: channelKey.key,
                aad: channelKey.channelId,
                attMeta: doc.get("attMeta") as? String,
                attMetaNonce: doc.get("attMetaNonce") as? String
            )
            if let first = attachments.first {
                attachmentKind = first.isImage ? "image" : (first.isAudio ? "audio" : "file")
            }
        }
        return (
            text: text,
            at: (doc.get("createdAt") as? Timestamp)?.dateValue() ?? Date(),
            senderUid: doc.get("senderUid") as? String ?? "",
            peerName: peerName,
            attachmentKind: attachmentKind
        )
    }

    /// Encrypts and posts a message addressed to `recipientUid` within the channel. Attachment
    /// blobs are sealed + uploaded first; their encrypted descriptor rides in the same doc.
    func send(
        channelKey: HierarchyChannelKey,
        senderName: String,
        recipientUid: String,
        recipientName: String,
        text: String,
        attachments: [PendingChannelAttachment] = [],
        clientUuid: String? = nil
    ) async throws {
        _ = (channelKey, senderName, recipientUid, recipientName, text, attachments, clientUuid)
        throw HierarchyMessagingError.legacyWriteDisabled
    }

    // MARK: - Read receipts (✓/✓✓) + typing — byte-compatible with web (lib/messaging/receipts.ts,
    // typing.ts) and Android. Metadata only (uids + timestamps), so no encryption; a member only
    // writes its own doc.

    private func metadataRoot(_ channelId: String, scopeType: AuthorityMlsScopeType) -> DocumentReference {
        if scopeType == .agency {
            return db.collection("agencyPanels").document(channelId)
        }
        return db.collection("hierarchyChannels").document(channelId)
    }

    private func readReceipts(
        _ channelId: String,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) -> CollectionReference {
        metadataRoot(channelId, scopeType: scopeType).collection("readReceipts")
    }

    private func typingCol(
        _ channelId: String,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) -> CollectionReference {
        metadataRoot(channelId, scopeType: scopeType).collection("typing")
    }

    /// One-shot read of MY cursor toward `peerUid` — drives the home-row unread badge (a last
    /// incoming message newer than this cursor means unread).
    func myReadCursorAt(
        channelId: String,
        myUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) async -> Date? {
        let doc = try? await readReceipts(channelId, scopeType: scopeType)
            .document("\(myUid)__\(peerUid)").getDocument()
        if let millis = (doc?.get("at") as? NSNumber)?.doubleValue, millis > 0 {
            return Date(timeIntervalSince1970: millis / 1000)
        }
        if let timestamp = doc?.get("at") as? Timestamp {
            return timestamp.dateValue()
        }
        return nil
    }

    /// Records that I've read `peerUid`'s messages up to `at`. Doc id `me__peer`. Best-effort.
    func writeReadCursor(
        channelId: String,
        myUid: String,
        peerUid: String,
        at: Date,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) {
        guard !myUid.isEmpty, !peerUid.isEmpty else { return }
        readReceipts(channelId, scopeType: scopeType).document("\(myUid)__\(peerUid)").setData([
            "viewerUid": myUid,
            "partnerUid": peerUid,
            "deliveredAt": Int(at.timeIntervalSince1970 * 1000),
            "at": Int(at.timeIntervalSince1970 * 1000),
            "updatedAt": FieldValue.serverTimestamp()
        ], merge: true)
    }

    /// Records that this client decrypted the peer's messages without claiming they were read.
    func writeDeliveredCursor(
        channelId: String,
        myUid: String,
        peerUid: String,
        at: Date,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) {
        guard !myUid.isEmpty, !peerUid.isEmpty else { return }
        readReceipts(channelId, scopeType: scopeType).document("\(myUid)__\(peerUid)").setData([
            "viewerUid": myUid,
            "partnerUid": peerUid,
            "deliveredAt": Int(at.timeIntervalSince1970 * 1000),
            "updatedAt": FieldValue.serverTimestamp()
        ], merge: true)
    }

    /// Subscribes to how far `peerUid` has read MY messages (nil when none) — drives ✓✓.
    func listenReadCursor(
        channelId: String,
        myUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType = .hierarchy,
        onAt: @escaping (Date?) -> Void
    ) -> ListenerRegistration {
        readReceipts(channelId, scopeType: scopeType)
            .document("\(peerUid)__\(myUid)").addSnapshotListener { snapshot, _ in
            if let millis = (snapshot?.get("at") as? NSNumber)?.doubleValue, millis > 0 {
                onAt(Date(timeIntervalSince1970: millis / 1000))
            } else if let timestamp = snapshot?.get("at") as? Timestamp {
                onAt(timestamp.dateValue())
            } else {
                onAt(nil)
            }
        }
    }

    /// Subscribes to how far the peer client has decrypted MY messages.
    func listenDeliveredCursor(
        channelId: String,
        myUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType = .hierarchy,
        onAt: @escaping (Date?) -> Void
    ) -> ListenerRegistration {
        readReceipts(channelId, scopeType: scopeType)
            .document("\(peerUid)__\(myUid)").addSnapshotListener { snapshot, _ in
            if let millis = (snapshot?.get("deliveredAt") as? NSNumber)?.doubleValue, millis > 0 {
                onAt(Date(timeIntervalSince1970: millis / 1000))
            } else if let timestamp = snapshot?.get("deliveredAt") as? Timestamp {
                onAt(timestamp.dateValue())
            } else {
                onAt(nil)
            }
        }
    }

    /// Writes my typing state toward `peerUid`. Doc id = my uid. Best-effort.
    func setTyping(
        channelId: String,
        myUid: String,
        peerUid: String,
        typing: Bool,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) {
        guard !myUid.isEmpty, !peerUid.isEmpty else { return }
        typingCol(channelId, scopeType: scopeType).document(myUid).setData([
            "uid": myUid,
            "to": peerUid,
            "typing": typing,
            "at": FieldValue.serverTimestamp()
        ], merge: true)
    }

    /// Subscribes to whether `peerUid` is currently typing to me.
    func listenTyping(
        channelId: String,
        myUid: String,
        peerUid: String,
        scopeType: AuthorityMlsScopeType = .hierarchy,
        onTyping: @escaping (Bool) -> Void
    ) -> ListenerRegistration {
        typingCol(channelId, scopeType: scopeType).document(peerUid).addSnapshotListener { snapshot, _ in
            let typing = snapshot?.get("typing") as? Bool == true
                && (snapshot?.get("to") as? String ?? "") == myUid
            onTyping(typing)
        }
    }

    // MARK: - Crypto (AES-256-GCM, ct || tag, AAD = channelId — identical to web/Android)

    private static func decrypt(
        channelKey: HierarchyChannelKey,
        nonceBase64: String,
        ciphertextBase64: String
    ) throws -> String {
        guard let nonceData = Data(base64Encoded: nonceBase64),
              let combined = Data(base64Encoded: ciphertextBase64),
              combined.count > 16 else {
            throw HierarchyMessagingError.requestFailed
        }
        let box = try AES.GCM.SealedBox(
            nonce: AES.GCM.Nonce(data: nonceData),
            ciphertext: combined.prefix(combined.count - 16),
            tag: combined.suffix(16)
        )
        let plaintext = try AES.GCM.open(
            box,
            using: channelKey.key,
            authenticating: Data(channelKey.channelId.utf8)
        )
        return String(decoding: plaintext, as: UTF8.self)
    }

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = Data(count: count)
        _ = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, count, $0.baseAddress!) }
        return bytes
    }

    // MARK: - HTTP plumbing

    /// Executes an authorized request against /api/messaging/hierarchy. Returns nil on 401/403
    /// (not entitled — callers treat that as "no channels"), throws on other failures.
    private func execute(method: String, json: [String: Any]?) async throws -> Data? {
        guard let base = Self.webBaseURL() else { throw HierarchyMessagingError.baseUrlMissing }
        guard let user = Auth.auth().currentUser else { throw HierarchyMessagingError.notSignedIn }
        let token = try await user.getIDToken()

        var request = URLRequest(url: base.appendingPathComponent(Self.apiPath))
        request.httpMethod = method
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let json {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONSerialization.data(withJSONObject: json)
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw HierarchyMessagingError.requestFailed }
        if http.statusCode == 401 || http.statusCode == 403 { return nil }
        guard (200..<300).contains(http.statusCode) else { throw HierarchyMessagingError.requestFailed }
        return data
    }

    static func webBaseURL() -> URL? {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String,
              let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
              url.scheme == "https" else {
            return nil
        }
        return url
    }
}
