import Foundation
import FirebaseAuth

struct AuthorityNearbyCallRoute: Codable, Equatable {
    let channel: HierarchyChannel
    let peer: HierarchyPeer
    let scopeType: AuthorityMlsScopeType
}

/// A hidden bridge contact is transport-only, so opening it directly would expose a fake citizen
/// thread after answering a nearby call. Keep the last real Authority thread route for that bridge.
enum AuthorityNearbyCallRouteRegistry {
    private static let keyPrefix = "authority.nearby-call-route."

    static func register(
        sessionId: UUID,
        channel: HierarchyChannel,
        peer: HierarchyPeer,
        scopeType: AuthorityMlsScopeType
    ) {
        guard !channel.channelId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !peer.uid.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let data = try? JSONEncoder().encode(
                AuthorityNearbyCallRoute(
                    channel: HierarchyChannel(
                        channelId: channel.channelId,
                        peerPanelId: channel.peerPanelId,
                        peerPanelName: channel.peerPanelName,
                        group: channel.group,
                        peers: [peer]
                    ),
                    peer: peer,
                    scopeType: scopeType
                )
              ) else { return }
        UserDefaults.standard.set(data, forKey: keyPrefix + sessionId.uuidString.lowercased())
    }

    static func route(for sessionId: UUID) -> AuthorityNearbyCallRoute? {
        guard let data = UserDefaults.standard.data(
            forKey: keyPrefix + sessionId.uuidString.lowercased()
        ) else { return nil }
        return try? JSONDecoder().decode(AuthorityNearbyCallRoute.self, from: data)
    }
}

/// Mirrors Android's `AuthorityBridgeContacts`: keeps a hidden 1:1 "bridge" contact for every
/// cross-panel authority (kurum) channel peer whose E.164 number the backend released
/// (`HierarchyPeer.phone`, entitled managers only).
///
/// The bridge contact is what lets the kurum channel chat work offline: it holds the peer's
/// number (the SPAKE2 password) and published identity key at the deterministic 1:1 session code
/// (`InternetConversation.pairId`), so a nearby handshake can silently upgrade it with a
/// Bluetooth link. Bridge contacts are flagged `isAuthorityBridge` and never surface in contact
/// lists — the conversation itself is the channel thread.
enum AuthorityBridgeContacts {

    /// Upserts bridge contacts for [channels]' peers. Peers the user already added deliberately
    /// only get a missing number backfilled and are never flagged as bridges. Peers without a
    /// published identity key have no app device — nothing to reach offline — and are skipped.
    static func sync(channels: [HierarchyChannel]) async {
        guard let myUid = Auth.auth().currentUser?.uid, !channels.isEmpty else { return }
        let client = InternetMessagingClient()
        var existingByUid: [String: ContactRecord] = [:]
        for contact in ContactStore.shared.contacts {
            let uid = contact.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            if !uid.isEmpty, existingByUid[uid] == nil {
                existingByUid[uid] = contact
            }
        }
        var created = 0
        for channel in channels {
            for peer in channel.peers {
                let phone = peer.phone?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                guard !phone.isEmpty, !peer.uid.isEmpty, peer.uid != myUid else { continue }
                if let existing = existingByUid[peer.uid] {
                    let existingKey = existing.peerPublicKey?
                        .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                    if (existing.peerPhone ?? "").isEmpty, !existingKey.isEmpty {
                        _ = ContactStore.shared.applyInternetIdentity(
                            conversationSessionCode: InternetConversation.pairId(myUid, peer.uid),
                            peerUid: peer.uid,
                            peerPublicKey: existingKey,
                            peerPhone: phone
                        )
                    }
                    if existing.isAuthorityBridge == true {
                        AuthorityNearbyCallRouteRegistry.register(
                            sessionId: existing.id,
                            channel: channel,
                            peer: peer,
                            scopeType: .hierarchy
                        )
                    }
                    continue
                }
                guard let identity = try? await client.lookupIdentityKey(uid: peer.uid),
                      !identity.publicKeyBase64.isEmpty else { continue }
                let bridge = ContactStore.shared.applyInternetIdentity(
                    conversationSessionCode: InternetConversation.pairId(myUid, peer.uid),
                    peerUid: peer.uid,
                    peerPublicKey: identity.publicKeyBase64,
                    displayName: peer.name,
                    peerPhotoUrl: peer.photoUrl,
                    peerPhone: phone,
                    isAuthorityBridge: true
                )
                if let bridge {
                    AuthorityNearbyCallRouteRegistry.register(
                        sessionId: bridge.id,
                        channel: channel,
                        peer: peer,
                        scopeType: .hierarchy
                    )
                }
                created += 1
            }
        }
        NSLog("AuthorityBridge: sync channels=%d created=%d", channels.count, created)
    }
}
