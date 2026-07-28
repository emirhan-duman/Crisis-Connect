//
//  SfuAuthorityCallSignaling.swift
//  Crisis Connect
//
//  Web-compatible call signalling over an authority channel's Firestore `callSignals`
//  subcollection (`agencyPanels/{slug}/callSignals` or `hierarchyChannels/{id}/callSignals`) —
//  the transport the web dashboard's ring uses (lib/messaging/call-signals.ts) and Android's
//  AuthorityCallSignaling mirrors. One doc per signal, addressed `to` a uid:
//  `{from,to,callId,type,roomId?,createdAt}`. NOTE the doc schema (matching web + Android
//  byte-for-byte) does NOT carry the offer's `video` flag — the callee always rings as a voice
//  call and sees the caller's camera through the roster/track pull instead; that quirk is shared
//  by all three platforms, so don't "fix" it here alone.
//
//  iOS has no legacy authority P2P engine, so unlike Android this transport routes ONLY SFU
//  signals (an offer carrying a roomId, plus follow-ups keyed by a remembered SFU callId);
//  everything else is dropped.
//

import FirebaseAuth
import FirebaseFirestore
import Foundation

final class SfuAuthorityCallSignaling: @unchecked Sendable {
    enum ChannelKind {
        case agency, hierarchy
    }

    private static let validTypes: Set<String> = ["offer", "answer", "ice", "reject", "busy", "end", "cancel"]
    /// Longer than the 35s ring timeout with headroom for clock skew — see listen()'s freshness gate.
    private static let maxSignalAge: TimeInterval = 45

    // Global (cross-instance) set of SFU callIds — an offer carries a roomId, but the follow-up
    // answer/reject/end don't, and they may be caught by a different instance (the app-wide
    // receiver vs the per-call sender). Keying on callId keeps them all on the SFU path.
    private static let sfuCallIdsLock = NSLock()
    private static var sfuCallIds = Set<String>()

    static func rememberSfuCall(_ callId: String) {
        guard !callId.isEmpty else { return }
        sfuCallIdsLock.lock()
        sfuCallIds.insert(callId)
        sfuCallIdsLock.unlock()
    }

    static func isSfuCall(_ callId: String) -> Bool {
        guard !callId.isEmpty else { return false }
        sfuCallIdsLock.lock()
        defer { sfuCallIdsLock.unlock() }
        return sfuCallIds.contains(callId)
    }

    let channelId: String
    let kind: ChannelKind
    private let myUid: String
    private let collection: CollectionReference

    init(channelId: String, myUid: String, kind: ChannelKind, firestore: Firestore = .firestore()) {
        self.channelId = channelId
        self.myUid = myUid
        self.kind = kind
        switch kind {
        case .agency:
            collection = firestore.collection("agencyPanels").document(channelId).collection("callSignals")
        case .hierarchy:
            collection = firestore.collection("hierarchyChannels").document(channelId).collection("callSignals")
        }
    }

    /// Writes one signal as a web callSignals doc addressed to `toUid`.
    func send(toUid: String, signal: [String: Any]) {
        let type = signal["type"] as? String ?? ""
        guard !type.isEmpty, !toUid.isEmpty else { return }
        let callId = signal["callId"] as? String ?? ""
        let roomId = signal["roomId"] as? String ?? ""
        // Remember our own outgoing SFU call so the peer's answer (which has no roomId) — caught
        // by a DIFFERENT instance (the app-wide receiver) — still routes to SFU.
        if type == "offer" && !roomId.isEmpty { Self.rememberSfuCall(callId) }
        var doc: [String: Any] = [
            "from": myUid,
            "to": toUid,
            "callId": callId,
            "type": type,
            "createdAt": FieldValue.serverTimestamp()
        ]
        if !roomId.isEmpty { doc["roomId"] = roomId }
        collection.addDocument(data: doc)
    }

    /// Subscribes to incoming signals addressed to me and feeds SFU ones to `onSfuSignal`. Dedups
    /// by doc id and gates on FRESHNESS rather than skipping the attach-time backlog: a signal can
    /// land BEFORE this listener attaches (cold app start + a callee answering within a second).
    /// Anything younger than a ring's lifetime is real; older docs are leftovers from previous
    /// calls and are ignored (replaying those caused busy-storms on the other platforms).
    func listen(
        onSfuSignal: @escaping (_ fromUid: String, _ signal: [String: Any]) -> Void
    ) -> ListenerRegistration {
        var seen = Set<String>()
        return collection.whereField("to", isEqualTo: myUid).addSnapshotListener { snapshot, _ in
            guard let snapshot else { return }
            for change in snapshot.documentChanges where change.type == .added {
                let document = change.document
                guard seen.insert(document.documentID).inserted else { continue }
                // Null createdAt (pending server time on a latency-compensated echo) counts as fresh.
                if let createdAt = (document.get("createdAt") as? Timestamp)?.dateValue(),
                   Date().timeIntervalSince(createdAt) > Self.maxSignalAge {
                    continue
                }
                guard let type = document.get("type") as? String, Self.validTypes.contains(type),
                      let from = (document.get("from") as? String)?.nilIfEmpty else { continue }
                let callId = document.get("callId") as? String ?? ""
                let roomId = document.get("roomId") as? String

                var signal: [String: Any] = ["type": type, "callId": callId]
                if let roomId, !roomId.isEmpty { signal["roomId"] = roomId }

                // An SFU invite (offer carries a roomId, no SDP) or a follow-up for a known SFU
                // call routes to the ring; anything else is a legacy P2P signal iOS can't serve.
                if (roomId?.isEmpty == false) || Self.isSfuCall(callId) {
                    if type == "offer", roomId?.isEmpty == false { Self.rememberSfuCall(callId) }
                    onSfuSignal(from, signal)
                } else {
                    NSLog("SfuAuthorityCallSignaling: dropping non-SFU %@ signal (no authority P2P on iOS)", type)
                }
            }
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/// App-wide receiver for authority SFU calls, so an incoming call rings from ANY screen (not only
/// while the relevant conversation is open) — Android's AuthorityCallReceiver. For an agency
/// member it subscribes to the agency channel's callSignals plus every cross-panel (hierarchy)
/// channel's callSignals and feeds them to SfuAuthorityCallManager. A non-agency user (no
/// agencySlug) starts no listeners and stays retry-able.
enum SfuAuthorityCallReceiver {
    private static let lock = NSLock()
    private static var started = false
    private static var registrations: [ListenerRegistration] = []

    static func start() {
        guard SfuCallConfig.enabled else { return }
        lock.lock()
        if started {
            lock.unlock()
            return
        }
        started = true
        lock.unlock()

        Task {
            let user = Auth.auth().currentUser
            guard let uid = user?.uid, user?.isAnonymous == false,
                  let slug = await resolveAgencySlug(uid: uid) else {
                // Not an agency member (e.g. a citizen) — nothing to receive; allow a later retry.
                lock.lock(); started = false; lock.unlock()
                return
            }

            var regs: [ListenerRegistration] = []
            let agency = SfuAuthorityCallSignaling(channelId: slug, myUid: uid, kind: .agency)
            regs.append(agency.listen { from, signal in
                Task { @MainActor in
                    SfuAuthorityCallManager.shared.onSfuSignal(
                        channelId: slug, kind: .agency, myUid: uid, fromUid: from, signal: signal
                    )
                }
            })

            let channels = (try? await HierarchyMessagingClient().fetchChannels()) ?? []
            for channel in channels {
                let signaling = SfuAuthorityCallSignaling(
                    channelId: channel.channelId, myUid: uid, kind: .hierarchy
                )
                let peers = channel.peers
                regs.append(signaling.listen { from, signal in
                    Task { @MainActor in
                        SfuAuthorityCallManager.shared.onSfuSignal(
                            channelId: channel.channelId, kind: .hierarchy, myUid: uid,
                            fromUid: from, signal: signal,
                            peerName: peers.first { $0.uid == from }?.name
                        )
                    }
                })
            }

            lock.lock()
            registrations.append(contentsOf: regs)
            lock.unlock()
            NSLog("SfuAuthorityCallReceiver: listening for authority calls on %d channel(s)", regs.count)
        }
    }

    static func stop() {
        lock.lock()
        registrations.forEach { $0.remove() }
        registrations.removeAll()
        started = false
        lock.unlock()
    }

    /// users/{uid}.agencySlug, falling back to the stored role certificate's agency.
    private static func resolveAgencySlug(uid: String) async -> String? {
        let fromDoc = try? await Firestore.firestore().document("users/\(uid)").getDocument()
            .get("agencySlug") as? String
        if let slug = fromDoc?.trimmingCharacters(in: .whitespacesAndNewlines), !slug.isEmpty {
            return slug
        }
        let fromCertificate = try? SecurityRepository.shared
            .getStoredCertificateSync(allowExpired: true)?.roleCertificate.agency
        let slug = fromCertificate?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (slug?.isEmpty == false) ? slug : nil
    }
}
