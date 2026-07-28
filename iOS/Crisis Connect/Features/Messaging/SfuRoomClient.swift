//
//  SfuRoomClient.swift
//  Crisis Connect
//
//  SFU room roster over Firestore — the coordination plane the SFU itself doesn't provide.
//  Mirrors the web `sfu-room.ts` and Android's SfuRoomClient: `sfuRooms/{roomId}/participants/{uid}`
//  holds each member's SFU sessionId + published track names so everyone can pull everyone else.
//  Carries NO media and NO plaintext keys — MLS handshake bytes ride the sibling `mlsMessages`
//  subcollection as opaque blobs. Every write carries `expireAt` so a Firestore TTL policy sweeps
//  orphans (a crashed peer's roster doc, the handshake blobs); live calls keep extending it.
//

import FirebaseFirestore
import Foundation

final class SfuRoomClient: @unchecked Sendable {
    private let roomId: String
    private let selfUid: String
    private let db: Firestore

    // 12h: comfortably longer than any real authority call, refreshed by heartbeat while live.
    private static let roomTtl: TimeInterval = 12 * 60 * 60

    init(roomId: String, selfUid: String, firestore: Firestore = .firestore()) {
        self.roomId = roomId
        self.selfUid = selfUid
        self.db = firestore
    }

    private var participants: CollectionReference {
        db.collection("sfuRooms").document(roomId).collection("participants")
    }

    private var mls: CollectionReference {
        db.collection("sfuRooms").document(roomId).collection("mlsMessages")
    }

    private func expireAt() -> Timestamp {
        Timestamp(date: Date().addingTimeInterval(Self.roomTtl))
    }

    /// Announce (or update) my SFU identity so other members can pull my tracks.
    func publishSelf(
        name: String,
        photoUrl: String?,
        cameraOn: Bool,
        muted: Bool,
        sessionId: String,
        tracks: [SfuPublishedTrack]
    ) {
        participants.document(selfUid).setData([
            "uid": selfUid,
            "name": name,
            "photoUrl": photoUrl as Any,
            "cameraOn": cameraOn,
            "muted": muted,
            "sessionId": sessionId,
            "tracks": tracks.map {
                ["trackName": $0.trackName, "kind": $0.kind, "source": $0.source]
            },
            "updatedAt": FieldValue.serverTimestamp(),
            "expireAt": expireAt()
        ])
    }

    /// Remove myself from the room roster on hang-up (best-effort).
    func leave() {
        participants.document(selfUid).delete()
    }

    /// Liveness heartbeat: refresh my roster entry so peers can tell a live member from a
    /// crashed one (an ungraceful exit never deletes the doc — only staleness reveals it).
    func heartbeat() {
        participants.document(selfUid).updateData([
            "updatedAt": FieldValue.serverTimestamp(),
            "expireAt": expireAt()
        ])
    }

    /// Atomically claim the MLS group-creator role on the room doc. Exactly one member gets
    /// `true` (race-free); everyone else joins the group the creator makes.
    func claimMlsCreator() async throws -> Bool {
        let reference = db.collection("sfuRooms").document(roomId)
        let selfUid = selfUid
        let expire = expireAt()
        let result = try await db.runTransaction { transaction, _ -> Any? in
            let snapshot = try? transaction.getDocument(reference)
            if let creator = snapshot?.get("mlsCreator") as? String {
                return creator == selfUid
            }
            transaction.setData([
                "mlsCreator": selfUid,
                "createdAt": FieldValue.serverTimestamp(),
                "expireAt": expire
            ], forDocument: reference, merge: true)
            return true
        }
        return result as? Bool ?? false
    }

    /// Subscribe to the room roster; delivers everyone except me.
    func listenRoster(onRoster: @escaping ([SfuRemoteParticipant]) -> Void) -> ListenerRegistration {
        participants.addSnapshotListener { [selfUid] snapshot, _ in
            guard let snapshot else { return }
            let remotes: [SfuRemoteParticipant] = snapshot.documents.compactMap { document in
                guard document.documentID != selfUid,
                      let sessionId = document.get("sessionId") as? String,
                      let rawTracks = document.get("tracks") as? [[String: Any]] else {
                    return nil
                }
                let tracks: [SfuPublishedTrack] = rawTracks.compactMap { raw in
                    guard let trackName = raw["trackName"] as? String else { return nil }
                    return SfuPublishedTrack(
                        trackName: trackName,
                        kind: raw["kind"] as? String ?? "audio",
                        source: raw["source"] as? String ?? "mic"
                    )
                }
                return SfuRemoteParticipant(
                    uid: document.documentID,
                    sessionId: sessionId,
                    tracks: tracks,
                    updatedAt: (document.get("updatedAt") as? Timestamp)?.dateValue()
                )
            }
            onRoster(remotes)
        }
    }

    // MARK: - MLS handshake relay (opaque blobs; Firestore never sees keys/plaintext)

    /// Broadcast one opaque MLS handshake message (codec JSON) to the room.
    func publishMlsMessage(_ payload: String) {
        mls.addDocument(data: [
            "from": selfUid,
            "payload": payload,
            "createdAt": FieldValue.serverTimestamp(),
            "expireAt": expireAt()
        ])
    }

    /// Subscribe to peers' MLS messages, INCLUDING the backlog present at attach — the peer can
    /// publish its KeyPackage before our listener attaches (the creator-claim transaction takes a
    /// round-trip first) and dropping it would deadlock the handshake. Batches are delivered in
    /// createdAt order (handshake messages are order-sensitive).
    func listenMlsMessages(onMessage: @escaping (String) -> Void) -> ListenerRegistration {
        var seen = Set<String>()
        return mls.addSnapshotListener { [selfUid] snapshot, _ in
            guard let snapshot else { return }
            snapshot.documentChanges
                .filter { $0.type == .added && seen.insert($0.document.documentID).inserted }
                .map(\.document)
                .filter { ($0.get("from") as? String) != selfUid }
                .sorted {
                    let lhs = ($0.get("createdAt") as? Timestamp)?.dateValue() ?? .distantFuture
                    let rhs = ($1.get("createdAt") as? Timestamp)?.dateValue() ?? .distantFuture
                    return lhs < rhs
                }
                .forEach { document in
                    if let payload = document.get("payload") as? String {
                        onMessage(payload)
                    }
                }
        }
    }
}
