//
//  AgencyMessagingClient.swift
//  Crisis Connect
//
//  Field-team (iOS) side of the dashboard's agency-internal messaging: fetches the agency key,
//  listens to the encrypted channel in Firestore and decrypts it in realtime (server only holds
//  ciphertext), and can post to the same channel. Mirrors the web `lib/messaging/agency.ts` and the
//  Android `AgencyMessagingClient`. The AES-GCM (AgencyMessageCrypto) is golden-vector verified.
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

    /// Fetches the agency's shared key (membership-gated server-side). Throws if not a member.
    func fetchAgencyKey(agencySlug: String) async throws -> AgencyKey {
        let result = try await functions.httpsCallable("issueAgencyMessagingKey").callAsync(["agencySlug": agencySlug])
        guard let data = result.data as? [String: Any],
              let keyBase64 = data["keyBase64"] as? String,
              let agencyKey = AgencyKey.from(
                  keyBase64: keyBase64,
                  keyId: data["keyId"] as? String ?? "",
                  agencySlug: agencySlug
              ) else {
            throw AgencyMessageError.malformed
        }
        return agencyKey
    }

    private func channel(_ agencySlug: String) -> CollectionReference {
        db.collection("agencyPanels").document(agencySlug).collection("secureMessages")
    }

    /// Realtime subscription: decrypts each stored message and delivers the ordered list.
    func listen(agencyKey: AgencyKey, onMessages: @escaping ([AgencyMessage]) -> Void) -> ListenerRegistration {
        channel(agencyKey.agencySlug)
            .order(by: "createdAt")
            .limit(to: 200)
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
        guard let uid = auth.currentUser?.uid else { throw AgencyMessageError.malformed }
        let (nonce, ciphertext) = try AgencyMessageCrypto.encrypt(agencyKey: agencyKey, text: text)
        try await channel(agencyKey.agencySlug).addDocument(data: [
            "senderUid": uid,
            "senderName": senderName,
            "keyId": agencyKey.keyId,
            "nonce": nonce,
            "ciphertext": ciphertext,
            "createdAt": FieldValue.serverTimestamp(),
        ])
    }
}
