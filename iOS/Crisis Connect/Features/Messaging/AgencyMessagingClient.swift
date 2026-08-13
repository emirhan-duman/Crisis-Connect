//
//  AgencyMessagingClient.swift
//  Crisis Connect
//
//  Retired shared-key agency messaging client. Key fetches and writes fail locally; Firestore Rules
//  also deny the old history. AuthorityChat MLS v2 owns active mobile messaging.
//

import Foundation
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions

struct AgencyMessage: Identifiable {
    let id: String
    let senderUid: String
    let senderName: String
    let text: String
    let at: Date
}

final class AgencyMessagingClient {
    private let functions: Functions
    private let db: Firestore
    private let auth: Auth

    init(
        functions: Functions = Functions.functions(region: "us-central1"),
        db: Firestore = Firestore.firestore(),
        auth: Auth = Auth.auth()
    ) {
        self.functions = functions
        self.db = db
        self.auth = auth
    }

    /// Retired: server-issued shared keys violate the AuthorityChat MLS-only invariant.
    func fetchAgencyKey(agencySlug _: String) async throws -> AgencyKey {
        throw AgencyMessageError.legacyWriteDisabled
    }

    private func channel(_ agencySlug: String) -> CollectionReference {
        db.collection("agencyPanels").document(agencySlug).collection("secureMessages")
    }

    /// Realtime subscription: decrypts each stored message and delivers the ordered list.
    ///
    /// `limit(toLast:)`, NOT `limit(to:)`. With an ascending order, `limit(to:)` pins the window to
    /// the OLDEST 200 documents, so once a panel had written that many messages every newer one fell
    /// outside the query and was never delivered: the channel froze, silently, and stayed frozen
    /// (secureMessages is an immutable log, so nothing ages out to make room). `limit(toLast:)` keeps
    /// the window on the newest 200 and still hands them back oldest-first — the same form
    /// HierarchyMessagingClient.listenConversation already uses.
    func listen(agencyKey: AgencyKey, onMessages: @escaping ([AgencyMessage]) -> Void) -> ListenerRegistration {
        channel(agencyKey.agencySlug)
            .order(by: "createdAt")
            .limit(toLast: 200)
            .addSnapshotListener { snapshot, _ in
                guard let documents = snapshot?.documents else { return }
                let messages: [AgencyMessage] = documents.compactMap { document in
                    let data = document.data()
                    guard let nonce = data["nonce"] as? String, let ciphertext = data["ciphertext"] as? String else {
                        return nil
                    }
                    let text = (try? AgencyMessageCrypto.decrypt(
                        agencyKey: agencyKey, nonceBase64: nonce, ciphertextBase64: ciphertext
                    )) ?? "⚠️"
                    return AgencyMessage(
                        id: document.documentID,
                        senderUid: data["senderUid"] as? String ?? "",
                        senderName: data["senderName"] as? String ?? "",
                        text: text,
                        at: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date()
                    )
                }
                onMessages(messages)
            }
    }

    /// Newest message in the agency broadcast channel, decrypted on-device — the push notification's
    /// real-text preview (the push itself is routing-only; the ciphertext lives in Firestore). Fetches
    /// the shared key itself, so a caller holding only the slug (the push handler) needs nothing else.
    /// Mirrors Android's `latestMessage`; nil when the channel is empty or the key/decrypt is unavailable.
    func latestMessage(agencySlug: String) async -> AgencyMessage? {
        let snapshot = try? await channel(agencySlug)
            .order(by: "createdAt", descending: true)
            .limit(to: 1)
            .getDocuments()
        guard let document = snapshot?.documents.first else { return nil }
        let data = document.data()
        guard let nonce = data["nonce"] as? String, let ciphertext = data["ciphertext"] as? String,
              let agencyKey = try? await fetchAgencyKey(agencySlug: agencySlug),
              let text = try? AgencyMessageCrypto.decrypt(
                  agencyKey: agencyKey, nonceBase64: nonce, ciphertextBase64: ciphertext
              ) else {
            return nil
        }
        return AgencyMessage(
            id: document.documentID,
            senderUid: data["senderUid"] as? String ?? "",
            senderName: data["senderName"] as? String ?? "",
            text: text,
            at: (data["createdAt"] as? Timestamp)?.dateValue() ?? Date()
        )
    }

    /// Encrypts and posts a message to the agency channel.
    func send(agencyKey: AgencyKey, senderName: String, text: String) async throws {
        _ = (agencyKey, senderName, text)
        throw AgencyMessageError.legacyWriteDisabled
    }
}
