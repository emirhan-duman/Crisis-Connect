//
//  SfuAuthorityCallSignaling.swift
//  Crisis Connect
//
//  Web-compatible call signalling over an authority channel's Firestore `callSignals`
//  subcollection (`agencyPanels/{slug}/callSignals` or `hierarchyChannels/{id}/callSignals`) —
//  the transport the web dashboard's ring uses (lib/messaging/call-signals.ts) and Android's
//  AuthorityCallSignaling mirrors. One doc per signal, addressed `to` a uid:
//  The schema is byte-compatible across web/Android/iOS and explicitly bound to SFU-v2, scope and
//  channel. Unknown fields and legacy SDP/ICE documents are rejected.
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

    private static let validTypes: Set<String> = ["offer", "answer", "reject", "busy", "end", "cancel"]
    private static let uuidPattern = try! NSRegularExpression(
        pattern: "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
    )
    /// Longer than the 35s ring timeout with headroom for clock skew — see listen()'s freshness gate.
    private static let maxSignalAge: TimeInterval = 45
    private static let signalRetention: TimeInterval = 24 * 60 * 60
    private static let maxSignalRetention: TimeInterval = 25 * 60 * 60

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
        guard Self.validTypes.contains(type), !toUid.isEmpty, toUid != myUid else { return }
        let callId = signal["callId"] as? String ?? ""
        guard Self.isUUID(callId), signal["sdp"] == nil, signal["candidate"] == nil,
              signal["sdpMid"] == nil, signal["sdpMLineIndex"] == nil else { return }
        let roomId = signal["roomId"] as? String ?? ""
        switch type {
        case "offer":
            guard Self.isUUID(roomId), signal["video"] is Bool,
                  Self.integer(signal["sfuVersion"]) == 2 else { return }
        case "answer":
            guard roomId.isEmpty, signal["video"] == nil,
                  Self.integer(signal["sfuVersion"]) == 2 else { return }
        default:
            guard roomId.isEmpty, signal["video"] == nil, signal["sfuVersion"] == nil else { return }
        }
        var doc: [String: Any] = [
            "signalVersion": 2,
            "scopeType": kind == .agency ? "agency" : "hierarchy",
            "channelId": channelId,
            "from": myUid,
            "to": toUid,
            "callId": callId,
            "type": type,
            "createdAt": FieldValue.serverTimestamp(),
            "expireAt": Timestamp(date: Date().addingTimeInterval(Self.signalRetention))
        ]
        if type == "offer" {
            doc["roomId"] = roomId
            doc["video"] = signal["video"] as? Bool ?? false
            doc["sfuVersion"] = 2
        } else if type == "answer" {
            doc["sfuVersion"] = 2
        }
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
        return collection.whereField("to", isEqualTo: myUid).addSnapshotListener { [weak self] snapshot, _ in
            guard let self, let snapshot else { return }
            for change in snapshot.documentChanges where change.type == .added {
                let document = change.document
                guard seen.insert(document.documentID).inserted else { continue }
                guard let createdAt = (document.get("createdAt") as? Timestamp)?.dateValue(),
                      let expireAt = (document.get("expireAt") as? Timestamp)?.dateValue(),
                      expireAt > createdAt,
                      expireAt.timeIntervalSince(createdAt) <= Self.maxSignalRetention,
                      Date().timeIntervalSince(createdAt) <= Self.maxSignalAge,
                      Self.integer(document.get("signalVersion")) == 2,
                      document.get("scopeType") as? String == (self.kind == .agency ? "agency" : "hierarchy"),
                      document.get("channelId") as? String == self.channelId else { continue }
                guard let type = document.get("type") as? String, Self.validTypes.contains(type),
                      let from = (document.get("from") as? String)?.nilIfEmpty, from != self.myUid,
                      document.get("to") as? String == self.myUid else { continue }
                let callId = document.get("callId") as? String ?? ""
                guard Self.isUUID(callId) else { continue }
                let data = document.data()
                let baseKeys: Set<String> = [
                    "signalVersion", "scopeType", "channelId", "from", "to", "callId", "type",
                    "createdAt", "expireAt"
                ]
                var signal: [String: Any] = ["type": type, "callId": callId]
                if type == "offer" {
                    guard let roomId = document.get("roomId") as? String, Self.isUUID(roomId),
                          let video = document.get("video") as? Bool,
                          Self.integer(document.get("sfuVersion")) == 2,
                          Set(data.keys) == baseKeys.union(["roomId", "video", "sfuVersion"]) else { continue }
                    signal["roomId"] = roomId
                    signal["video"] = video
                    signal["sfuVersion"] = 2
                } else if type == "answer" {
                    guard Self.integer(document.get("sfuVersion")) == 2,
                          Set(data.keys) == baseKeys.union(["sfuVersion"]) else { continue }
                    signal["sfuVersion"] = 2
                } else {
                    guard Set(data.keys) == baseKeys else { continue }
                }
                onSfuSignal(from, signal)
            }
        }
    }

    private static func isUUID(_ value: String) -> Bool {
        uuidPattern.firstMatch(in: value, range: NSRange(value.startIndex..., in: value)) != nil
    }

    private static func integer(_ value: Any?) -> Int? {
        if let value = value as? Int { return value }
        return (value as? NSNumber)?.intValue
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
    private static var generation = 0
    private static var registrations: [ListenerRegistration] = []

    static func start() {
        guard SfuCallConfig.enabled else { return }
        guard let startGeneration = claimStart() else { return }

        Task {
            let user = Auth.auth().currentUser
            guard let uid = user?.uid, user?.isAnonymous == false,
                  let slug = await resolveAgencySlug(uid: uid) else {
                // Not an agency member (e.g. a citizen) — nothing to receive; allow a later retry.
                resetStarted(generation: startGeneration)
                return
            }

            var regs: [ListenerRegistration] = []
            let agency = SfuAuthorityCallSignaling(channelId: slug, myUid: uid, kind: .agency)
            regs.append(agency.listen { from, signal in
                Task {
                    if signal["type"] as? String == "offer" {
                        let verified = await AuthorityMlsCallGate.isVerified(
                            selfUid: uid, peerUid: from, scopeType: .agency, channelId: slug
                        )
                        guard verified else {
                            agency.send(toUid: from, signal: [
                                "type": "reject", "callId": signal["callId"] as? String ?? ""
                            ])
                            return
                        }
                    }
                    await MainActor.run {
                    SfuAuthorityCallManager.shared.onSfuSignal(
                        channelId: slug, kind: .agency, myUid: uid, fromUid: from, signal: signal
                    )
                    }
                }
            })

            let channels = (try? await HierarchyMessagingClient().fetchChannels()) ?? []
            for channel in channels {
                let signaling = SfuAuthorityCallSignaling(
                    channelId: channel.channelId, myUid: uid, kind: .hierarchy
                )
                let peers = channel.peers
                regs.append(signaling.listen { from, signal in
                    Task {
                        if signal["type"] as? String == "offer" {
                            let verified = await AuthorityMlsCallGate.isVerified(
                                selfUid: uid, peerUid: from, scopeType: .hierarchy,
                                channelId: channel.channelId
                            )
                            guard verified else {
                                signaling.send(toUid: from, signal: [
                                    "type": "reject", "callId": signal["callId"] as? String ?? ""
                                ])
                                return
                            }
                        }
                        await MainActor.run {
                            SfuAuthorityCallManager.shared.onSfuSignal(
                                channelId: channel.channelId, kind: .hierarchy, myUid: uid,
                                fromUid: from, signal: signal,
                                peerName: peers.first { $0.uid == from }?.name
                            )
                        }
                    }
                })
            }

            if retainRegistrations(regs, generation: startGeneration) {
                NSLog("SfuAuthorityCallReceiver: listening for authority calls on %d channel(s)", regs.count)
            } else {
                regs.forEach { $0.remove() }
            }
        }
    }

    /// Resolves a content-free PushKit wake to its immutable signal document. Nothing from the push
    /// is accepted as a call offer until the exact SFU-v2 schema and the MLS device set both verify.
    static func handleVoipWake(
        scopeType: String,
        channelId: String,
        signalId: String,
        expectedCallId: String,
        expectedSenderUid: String
    ) async {
        let uuidPattern = "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-4[0-9A-Fa-f]{3}-[89AaBb][0-9A-Fa-f]{3}-[0-9A-Fa-f]{12}$"
        guard SfuCallConfig.enabled,
              let myUid = Auth.auth().currentUser?.uid,
              scopeType == "agency" || scopeType == "hierarchy",
              (1...256).contains(channelId.count), !channelId.contains("/"),
              signalId.range(of: "^[A-Za-z0-9_-]{1,128}$", options: .regularExpression) != nil,
              expectedCallId.range(of: uuidPattern, options: .regularExpression) != nil,
              !expectedSenderUid.isEmpty, expectedSenderUid.count <= 128 else {
            await MainActor.run {
                SfuAuthorityCallManager.shared.cancelVoipWake(callId: expectedCallId)
            }
            return
        }
        let kind: SfuAuthorityCallSignaling.ChannelKind = scopeType == "agency" ? .agency : .hierarchy
        let collection = scopeType == "agency" ? "agencyPanels" : "hierarchyChannels"
        let reference = Firestore.firestore()
            .collection(collection).document(channelId)
            .collection("callSignals").document(signalId)
        guard let document = try? await reference.getDocument(), document.exists,
              let createdAt = document.get("createdAt") as? Timestamp,
              let expireAt = document.get("expireAt") as? Timestamp else {
            await MainActor.run {
                SfuAuthorityCallManager.shared.cancelVoipWake(callId: expectedCallId)
            }
            return
        }
        let data = document.data() ?? [:]
        let expectedKeys: Set<String> = [
            "signalVersion", "scopeType", "channelId", "from", "to", "callId", "type",
            "createdAt", "expireAt", "roomId", "video", "sfuVersion"
        ]
        let now = Date()
        let fromUid = document.get("from") as? String ?? ""
        let callId = document.get("callId") as? String ?? ""
        let roomId = document.get("roomId") as? String ?? ""
        let video = document.get("video") as? Bool
        let age = now.timeIntervalSince(createdAt.dateValue())
        let retention = expireAt.dateValue().timeIntervalSince(createdAt.dateValue())
        guard Set(data.keys) == expectedKeys,
              (document.get("signalVersion") as? NSNumber)?.intValue == 2,
              document.get("scopeType") as? String == scopeType,
              document.get("channelId") as? String == channelId,
              document.get("type") as? String == "offer",
              (document.get("sfuVersion") as? NSNumber)?.intValue == 2,
              video != nil,
              document.get("to") as? String == myUid,
              fromUid == expectedSenderUid, fromUid != myUid,
              callId.caseInsensitiveCompare(expectedCallId) == .orderedSame,
              callId.range(of: uuidPattern, options: .regularExpression) != nil,
              roomId.range(of: uuidPattern, options: .regularExpression) != nil,
              age >= -5, age <= 45, retention > 0, retention <= 25 * 60 * 60,
              expireAt.dateValue() > now else {
            await MainActor.run {
                SfuAuthorityCallManager.shared.cancelVoipWake(callId: expectedCallId)
            }
            return
        }
        let verified = await AuthorityMlsCallGate.isVerified(
            selfUid: myUid,
            peerUid: fromUid,
            scopeType: kind == .agency ? .agency : .hierarchy,
            channelId: channelId
        )
        let signaling = SfuAuthorityCallSignaling(channelId: channelId, myUid: myUid, kind: kind)
        guard verified else {
            signaling.send(toUid: fromUid, signal: ["type": "reject", "callId": callId])
            await MainActor.run {
                SfuAuthorityCallManager.shared.cancelVoipWake(callId: expectedCallId)
            }
            return
        }
        let profile = try? await Firestore.firestore().collection("users").document(fromUid).getDocument()
        let peerName = ["displayName", "name", "fullName"]
            .compactMap { (profile?.get($0) as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }
            .first { !$0.isEmpty }
        let verifiedVideo = video ?? false
        await MainActor.run {
            SfuAuthorityCallManager.shared.onSfuSignal(
                channelId: channelId,
                kind: kind,
                myUid: myUid,
                fromUid: fromUid,
                signal: [
                    "type": "offer", "callId": callId, "roomId": roomId,
                    "video": verifiedVideo, "sfuVersion": 2
                ],
                peerName: peerName
            )
        }
    }

    static func stop() {
        lock.lock()
        let stale = registrations
        registrations.removeAll()
        started = false
        generation += 1
        lock.unlock()
        stale.forEach { $0.remove() }
    }

    private static func claimStart() -> Int? {
        lock.lock()
        defer { lock.unlock() }
        guard !started else { return nil }
        started = true
        generation += 1
        return generation
    }

    private static func resetStarted(generation expected: Int) {
        lock.lock()
        if generation == expected { started = false }
        lock.unlock()
    }

    private static func retainRegistrations(_ values: [ListenerRegistration], generation expected: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard started, generation == expected else { return false }
        registrations.append(contentsOf: values)
        return true
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
