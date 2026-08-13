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

enum SfuRoomScopeType: String {
    case agency, hierarchy
}

enum SfuRoomAuthorizationError: Error {
    case invalidBinding
    case roomBindingMismatch
}

/// Immutable authorization record for one accepted call. The room UUID is only a routing key;
/// Firestore rules and every client bind it to this exact scope, call id, and participant pair.
struct SfuRoomBinding: Equatable {
    let scopeType: SfuRoomScopeType
    let channelId: String
    let callId: String
    let participants: [String]

    init(
        scopeType: SfuRoomScopeType,
        channelId: String,
        callId: String,
        selfUid: String,
        peerUid: String
    ) throws {
        let channel = channelId.trimmingCharacters(in: .whitespacesAndNewlines)
        let call = callId.trimmingCharacters(in: .whitespacesAndNewlines)
        let me = selfUid.trimmingCharacters(in: .whitespacesAndNewlines)
        let peer = peerUid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !channel.isEmpty, channel.count <= 256,
              UUID(uuidString: call) != nil,
              !me.isEmpty, me.count <= 128,
              !peer.isEmpty, peer.count <= 128,
              me != peer else {
            throw SfuRoomAuthorizationError.invalidBinding
        }
        self.scopeType = scopeType
        self.channelId = channel
        self.callId = call
        self.participants = [me, peer].sorted()
    }

    var documentFields: [String: Any] {
        [
            "version": 2,
            "scopeType": scopeType.rawValue,
            "channelId": channelId,
            "callId": callId,
            "participants": participants
        ]
    }

    func matches(_ snapshot: DocumentSnapshot) -> Bool {
        guard (snapshot.get("version") as? NSNumber)?.intValue == 2,
              snapshot.get("scopeType") as? String == scopeType.rawValue,
              snapshot.get("channelId") as? String == channelId,
              snapshot.get("callId") as? String == callId,
              snapshot.get("participants") as? [String] == participants,
              let creator = snapshot.get("mlsCreator") as? String,
              participants.contains(creator) else {
            return false
        }
        return true
    }
}

final class SfuRoomClient: @unchecked Sendable {
    private let roomId: String
    private let selfUid: String
    private let binding: SfuRoomBinding
    private let protocolVersion: SfuProtocolVersion
    private let db: Firestore

    var enforcesBoundIdentity: Bool { true }

    // 12h: comfortably longer than any real authority call, refreshed by heartbeat while live.
    private static let roomTtl: TimeInterval = 12 * 60 * 60

    init(
        roomId: String,
        selfUid: String,
        binding: SfuRoomBinding,
        protocolVersion: SfuProtocolVersion = .secure,
        firestore: Firestore = .firestore()
    ) {
        self.roomId = roomId
        self.selfUid = selfUid
        self.binding = binding
        self.protocolVersion = protocolVersion
        self.db = firestore
    }

    private var participants: CollectionReference {
        db.collection(roomCollection).document(roomId).collection("participants")
    }

    private var mls: CollectionReference {
        db.collection(roomCollection).document(roomId).collection("mlsMessages")
    }

    private var roomCollection: String {
        "sfuRoomsV2"
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
        tracks: [SfuPublishedTrack],
        onError: @escaping (Error) -> Void = { _ in }
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
        ], completion: { error in
            if let error { onError(error) }
        })
    }

    /// Remove myself from the room roster on hang-up (best-effort).
    func leave() {
        participants.document(selfUid).delete()
    }

    /// Liveness heartbeat: refresh my roster entry so peers can tell a live member from a
    /// crashed one (an ungraceful exit never deletes the doc — only staleness reveals it).
    func heartbeat(onError: @escaping (Error) -> Void = { _ in }) {
        participants.document(selfUid).updateData([
            "updatedAt": FieldValue.serverTimestamp(),
            "expireAt": expireAt()
        ], completion: { error in
            if let error { onError(error) }
        })
    }

    /// Atomically claim the MLS group-creator role on the room doc. Exactly one member gets
    /// `true` (race-free); everyone else joins the group the creator makes.
    func claimMlsCreator() async throws -> Bool {
        let reference = db.collection(roomCollection).document(roomId)
        let selfUid = selfUid
        let binding = binding
        let protocolVersion = protocolVersion
        let expire = expireAt()
        guard protocolVersion == .secure, binding.participants.contains(selfUid) else {
            throw SfuRoomAuthorizationError.invalidBinding
        }
        let result = try await db.runTransaction { transaction, errorPointer -> Any? in
            do {
                let snapshot = try transaction.getDocument(reference)
                if snapshot.exists {
                    if !binding.matches(snapshot) {
                        errorPointer?.pointee = SfuRoomAuthorizationError.roomBindingMismatch as NSError
                        return nil
                    }
                    if let creator = snapshot.get("mlsCreator") as? String, !creator.isEmpty {
                        return creator == selfUid
                    }
                    errorPointer?.pointee = SfuRoomAuthorizationError.roomBindingMismatch as NSError
                    return nil
                }
                var fields = binding.documentFields
                fields["mlsCreator"] = selfUid
                fields["createdAt"] = FieldValue.serverTimestamp()
                fields["expireAt"] = expire
                transaction.setData(fields, forDocument: reference)
                return true
            } catch {
                errorPointer?.pointee = error as NSError
                return nil
            }
        }
        return result as? Bool ?? false
    }

    /// Subscribe to the room roster; delivers everyone except me.
    func listenRoster(
        onRoster: @escaping ([SfuRemoteParticipant]) -> Void,
        onError: @escaping (Error) -> Void = { _ in }
    ) -> ListenerRegistration {
        participants.addSnapshotListener { [selfUid] snapshot, error in
            if let error { onError(error); return }
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
    func publishMlsMessage(_ payload: String, onError: @escaping (Error) -> Void = { _ in }) {
        guard !payload.isEmpty, payload.utf8.count <= 65_536 else {
            onError(SfuRoomAuthorizationError.invalidBinding)
            return
        }
        mls.addDocument(data: [
            "from": selfUid,
            "payload": payload,
            "createdAt": FieldValue.serverTimestamp(),
            "expireAt": expireAt()
        ], completion: { error in
            if let error { onError(error) }
        })
    }

    /// Subscribe to peers' MLS messages, INCLUDING the backlog present at attach — the peer can
    /// publish its KeyPackage before our listener attaches (the creator-claim transaction takes a
    /// round-trip first) and dropping it would deadlock the handshake. Batches are delivered in
    /// createdAt order (handshake messages are order-sensitive).
    func listenMlsMessages(
        onMessage: @escaping (_ payload: String, _ fromUid: String) -> Void,
        onError: @escaping (Error) -> Void = { _ in }
    ) -> ListenerRegistration {
        var seen = Set<String>()
        return mls.addSnapshotListener { [selfUid] snapshot, error in
            if let error { onError(error); return }
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
                    if let payload = document.get("payload") as? String,
                       let fromUid = document.get("from") as? String {
                        onMessage(payload, fromUid)
                    }
                }
        }
    }
}
