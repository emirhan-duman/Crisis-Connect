//
//  InternetMessageReceiver.swift
//  Crisis Connect
//
//  Downlink for E2E internet messages — the iOS port of Android's receive path
//  (CrisisConnectMessagingService). Two entry points feed one shared handler:
//   - a Firestore listener on `messages` where recipientUid == me (app running), and
//   - the silent APNs data push fanned out by the backend's onMessageCreated trigger
//     (app backgrounded/killed), routed here from the AppDelegate.
//  Either way the envelope arrives already encrypted; we decrypt with our identity private key,
//  file it into the chat, post the local notification and acknowledge so the server deletes its
//  copy. The server never holds a key to decrypt — or any plaintext to put in a push.
//

import Foundation
import FirebaseFirestore
import FirebaseAuth
import CryptoKit
import LibSignalClient
import UIKit
import UserNotifications

/// Deterministic conversation id shared by both peers, derived from the two uids order-independently.
/// Byte-for-byte identical to Android's `InternetConversation.pairId` so both sides agree without a
/// round trip. Used to accept a brand-new thread only when its id is canonical for (sender, me).
enum InternetConversation {
    static func pairId(_ uidA: String, _ uidB: String) -> String {
        let sorted = [
            uidA.trimmingCharacters(in: .whitespacesAndNewlines),
            uidB.trimmingCharacters(in: .whitespacesAndNewlines)
        ].sorted()
        let digest = SHA256.hash(data: Data("crisisconnect:conv:v1:\(sorted[0]):\(sorted[1])".utf8))
        let base64url = Data(digest).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "i" + String(base64url.prefix(24))
    }
}

final class InternetMessageReceiver: @unchecked Sendable {
    static let shared = InternetMessageReceiver()

    private let client = InternetMessagingClient()
    private var listener: ListenerRegistration?
    private var listeningUid: String?

    private init() {}

    /// Starts (or re-targets) the listener for the currently signed-in uid. Idempotent.
    func start() {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        if listeningUid == uid, listener != nil { return }
        stop()
        listeningUid = uid
        listener = Firestore.firestore()
            .collection("messages")
            .whereField("recipientUid", isEqualTo: uid)
            .addSnapshotListener { [weak self] snapshot, error in
                guard let self else { return }
                if let error {
                    NSLog("InternetMessageReceiver: listener error: %@", String(describing: error))
                    return
                }
                guard let snapshot else { return }
                for change in snapshot.documentChanges where change.type == .added {
                    let data = change.document.data()
                    Task { _ = await self.handle(data: data, myUid: uid) }
                }
            }
    }

    // Debounces the stale-prekey recovery so a burst of failing messages triggers exactly one
    // identity rotation + republish (each inbound copy would otherwise fire it).
    private static let recoveryLock = NSLock()
    private static var recoveryInFlight = false

    fileprivate static func recoverFromStalePreKeys() async {
        recoveryLock.lock()
        if recoveryInFlight { recoveryLock.unlock(); return }
        recoveryInFlight = true
        recoveryLock.unlock()
        MessagingDiagLog.log("stale-prekey recovery: rotating Signal identity + republishing")
        SignalIdentity.shared.rotate()
        do {
            let store = try SignalProtocolFileStore()
            try await SignalPreKeyManager(store: store).ensurePublished()
            MessagingDiagLog.log("stale-prekey recovery: republished fresh prekeys")
        } catch {
            MessagingDiagLog.log("stale-prekey recovery FAILED: \(String(describing: error))")
        }
        recoveryLock.lock(); recoveryInFlight = false; recoveryLock.unlock()
    }

    func stop() {
        listener?.remove()
        listener = nil
        listeningUid = nil
    }

    /// Entry point for the silent APNs data push (the FCM fan-out from the backend's
    /// onMessageCreated trigger). Same payload shape as the Firestore doc. Returns true when a
    /// new message was filed, so the AppDelegate can report `.newData` to the OS — being honest
    /// here keeps iOS willing to wake us for future pushes.
    ///
    /// A `fetch == "1"` push (envelope over the ~4 KB push budget) carries no inline ciphertext
    /// and is skipped, matching Android: the realtime listener pulls the doc from Firestore the
    /// next time the app runs.
    func handleRemotePush(userInfo: [AnyHashable: Any]) async -> Bool {
        if (userInfo["type"] as? String) == "authority_mls_prepare_v2" {
            return await handleAuthorityMlsPrepareWake(userInfo: userInfo)
        }
        if (userInfo["type"] as? String) == "authority_mls_v2" {
            return await handleAuthorityMlsWake(userInfo: userInfo)
        }
        if (userInfo["type"] as? String) == "channel_chat" {
            // Retired shared-key push. Do not decrypt legacy history into a lock-screen preview or
            // let a stale backend preserve a downgrade notification path after the MLS-v2 cutover.
            NSLog("InternetMessageReceiver: dropped retired channel_chat push")
            return false
        }
        guard (userInfo["type"] as? String) == "chat" else { return false }
        guard let myUid = Auth.auth().currentUser?.uid else { return false }
        var data: [String: Any] = [:]
        for (key, value) in userInfo {
            if let key = key as? String { data[key] = value }
        }
        return await handle(data: data, myUid: myUid)
    }

    /** Validates a content-free bootstrap wake against the canonical immutable MLS parent. */
    private func handleAuthorityMlsPrepareWake(userInfo: [AnyHashable: Any]) async -> Bool {
        guard let myUid = Auth.auth().currentUser?.uid,
              let conversationId = userInfo["conversationId"] as? String,
              conversationId.range(of: #"^am2_[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil else {
            return false
        }
        do {
            let parent = try await Firestore.firestore()
                .document("authorityMlsV2/\(conversationId)")
                .getDocument()
            guard try exactInt64(parent.get("version")) == 2,
                  let participants = parent.get("participants") as? [String],
                  participants.count == 2,
                  Set(participants).count == 2,
                  participants.contains(myUid),
                  let peerUid = participants.first(where: { $0 != myUid }),
                  let scopeValue = parent.get("scopeType") as? String,
                  let scopeType = AuthorityMlsScopeType(rawValue: scopeValue),
                  let channelId = parent.get("channelId") as? String,
                  !channelId.isEmpty else { return false }
            let canonical = try AuthorityMlsIdentifiers.canonicalBinding(AuthorityMlsBinding(
                scopeType: scopeType,
                channelId: channelId,
                participants: participants
            ))
            guard canonical.participants == participants,
                  try AuthorityMlsIdentifiers.conversationId(canonical) == conversationId else { return false }
            return await AuthorityMlsWakePrewarmer.shared.prepare(
                selfUid: myUid,
                peerUid: peerUid,
                scopeType: scopeType,
                channelId: channelId
            )
        } catch {
            NSLog("Authority MLS prepare wake validation failed: %@", String(describing: error))
            return false
        }
    }

    /// Resolves a content-free Authority MLS wake-up against the immutable v2 parent/message docs.
    /// The push handler intentionally does not instantiate another MLS session: that could race the
    /// foreground session's durable ratchet. The verified thread decrypts when the user opens it.
    private func handleAuthorityMlsWake(userInfo: [AnyHashable: Any]) async -> Bool {
        guard let myUid = Auth.auth().currentUser?.uid,
              let conversationId = userInfo["conversationId"] as? String,
              conversationId.range(of: #"^am2_[A-Za-z0-9_-]{43}$"#, options: .regularExpression) != nil,
              let messageId = userInfo["messageId"] as? String,
              messageId.range(of: #"^[A-Za-z0-9_-]{1,128}$"#, options: .regularExpression) != nil else {
            return false
        }
        do {
            let database = Firestore.firestore()
            let parent = try await database.document("authorityMlsV2/\(conversationId)").getDocument()
            guard try exactInt64(parent.get("version")) == 2,
                  let participants = parent.get("participants") as? [String],
                  participants.count == 2,
                  participants.contains(myUid),
                  let peerUid = participants.first(where: { $0 != myUid }),
                  let scopeTypeValue = parent.get("scopeType") as? String,
                  let scopeType = AuthorityMlsScopeType(rawValue: scopeTypeValue),
                  let channelId = parent.get("channelId") as? String,
                  !channelId.isEmpty else { return false }
            let canonical = try AuthorityMlsIdentifiers.canonicalBinding(AuthorityMlsBinding(
                scopeType: scopeType,
                channelId: channelId,
                participants: participants
            ))
            guard canonical.participants == participants,
                  try AuthorityMlsIdentifiers.conversationId(canonical) == conversationId else {
                return false
            }

            let message = try await database
                .document("authorityMlsV2/\(conversationId)/messages/\(messageId)")
                .getDocument()
            guard let senderDeviceId = message.get("senderDeviceId") as? String,
                  senderDeviceId.range(of: #"^[A-Za-z0-9_-]{1,128}$"#, options: .regularExpression) != nil,
                  let senderCredential = message.get("senderCredential") as? String,
                  let parsedCredential = AuthorityMlsCredential.decode(senderCredential),
                  try exactInt64(message.get("sequence")) >= 0,
                  try exactInt64(message.get("sequence")) < 9_007_199_254_740_991,
                  let ciphertext = message.get("ciphertext") as? String,
                  !ciphertext.isEmpty,
                  ciphertext.utf8.count <= 900_000,
                  ciphertext.range(of: #"^[A-Za-z0-9_-]+$"#, options: .regularExpression) != nil,
                  message.get("createdAt") is Timestamp,
                  parsedCredential.accountUid == peerUid,
                  parsedCredential.deviceId == senderDeviceId,
                  try exactInt64(message.get("contentVersion")) == 2,
                  message.get("messageId") as? String == messageId,
                  message.get("senderUid") as? String == peerUid else { return false }

            SOSNotificationCenter.postAuthorityChannelNotification(
                senderUid: peerUid,
                senderName: "",
                channelId: channelId,
                channelKind: scopeType.rawValue,
                body: nil
            )
            return true
        } catch {
            NSLog("InternetMessageReceiver: Authority MLS wake validation failed: %@", String(describing: error))
            return false
        }
    }

    /// Authority CHANNEL message push (agency/hierarchy). The content is E2E — the push itself only
    /// carries the sender name, so we try a BOUNDED decrypt of the latest message for a real preview
    /// (Android parity), falling back to the generic "new encrypted message" body when offline or
    /// slow. Skips our own messages (the server already excludes the sender; belt-and-braces).
    private func handleChannelPush(userInfo: [AnyHashable: Any]) async {
        let senderUid = (userInfo["senderUid"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !senderUid.isEmpty, senderUid != Auth.auth().currentUser?.uid else { return }
        let senderName = (userInfo["senderName"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let channelId = (userInfo["channelId"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let channelKind = (userInfo["channelKind"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)

        var preview: String?
        if channelKind == "hierarchy", !channelId.isEmpty {
            preview = await Self.boundedHierarchyPreview(channelId: channelId, peerUid: senderUid)
        } else if channelKind == "agency", !channelId.isEmpty {
            preview = await Self.boundedAgencyPreview(channelId: channelId)
        }

        // Routed through the notification center: a hierarchy push deep-links into that peer's
        // thread, an agency broadcast opens the channels list (Android parity).
        SOSNotificationCenter.postAuthorityChannelNotification(
            senderUid: senderUid,
            senderName: senderName,
            channelId: channelId.isEmpty ? nil : channelId,
            channelKind: channelKind.isEmpty ? nil : channelKind,
            body: preview
        )
    }

    /// Decrypts the newest message with this peer for the notification body, racing a 6s timeout so
    /// a slow network can't stall the push handler (same bound Android uses). Control payloads and
    /// attachment-only messages become their labels ("Missed call", "Photo", …); nil → generic body.
    private static func boundedHierarchyPreview(channelId: String, peerUid: String) async -> String? {
        await withTaskGroup(of: String?.self) { group in
            group.addTask {
                let client = HierarchyMessagingClient()
                guard let key = try? await client.fetchChannelKey(channelId: channelId),
                      let latest = await client.latestMessageWith(
                          channelId: channelId, peerUid: peerUid, channelKey: key
                      ),
                      latest.senderUid == peerUid else { return nil }
                let text = latest.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty || !latest.attachmentKind.isEmpty else { return nil }
                return await AuthorityChannelsListStore.previewText(
                    text: text, attachmentKind: latest.attachmentKind
                )
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    /// Decrypts the newest broadcast in an agency channel for the notification body, racing the same
    /// 6s timeout the hierarchy path uses so a slow/offline decrypt can't stall the push handler
    /// (Android parity). Control payloads (call/location) become their labels; nil → generic body.
    private static func boundedAgencyPreview(channelId: String) async -> String? {
        await withTaskGroup(of: String?.self) { group in
            group.addTask {
                guard let latest = await AgencyMessagingClient().latestMessage(agencySlug: channelId) else {
                    return nil
                }
                let text = latest.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return nil }
                // Agency broadcasts carry no attachments, so only the text/control-payload path applies.
                return await AuthorityChannelsListStore.previewText(text: text, attachmentKind: "")
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: 6_000_000_000)
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }

    /// Resolves the sender's authenticated identity, decrypts, files the message and acks. Drops the
    /// message (without acking) whenever the sender/conversation binding fails or decryption doesn't
    /// authenticate — the same two anti-impersonation layers as Android:
    ///  1. Conversation binding — refuse to file into a thread that belongs to a different peer, and
    ///     only auto-create a thread when conversationId is the canonical pair id for (sender, me).
    ///  2. Cryptographic sender auth — `open` mixes ECDH(myIdentity, senderIdentity) into the key, so
    ///     a message decrypts only if it was authored with the sender's private key (pinned key when
    ///     we have one, else a directory lookup on first contact = trust-on-first-use).
    @discardableResult
    private func handle(data: [String: Any], myUid: String) async -> Bool {
        let conversationId = (data["conversationId"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let senderUid = (data["senderUid"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = (data["messageId"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !conversationId.isEmpty, !messageId.isEmpty, !senderUid.isEmpty, senderUid != myUid else { return false }

        let ciphertext = data["ciphertext"] as? String ?? ""
        guard !ciphertext.isEmpty else { return false }
        // `v` selects the crypto layer: 2 = forward-secret Signal (ctype), else the v1 ECIES
        // envelope. Firestore delivers a number, the push payload a string — accept both.
        let wireVersion = (data["v"] as? NSNumber)?.intValue ?? Int(data["v"] as? String ?? "") ?? 1
        let isSignal = wireVersion == 2
        let ctypeLabel = data["ctype"] as? String ?? ""
        let sealed = SealedInternetMessage(
            ephemeralPublicKey: data["ephemeralPubKey"] as? String ?? "",
            nonce: data["nonce"] as? String ?? "",
            ciphertext: ciphertext,
            alg: (data["alg"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? InternetE2eEnvelope.alg
        )

        // Find the local thread for this peer: by the conversation id's session code, else by the
        // peer's uid — a local contact is keyed by its own session code (which differs per peer), so
        // an existing thread is found via the internet identity, not the conversationId.
        let existing = ContactStore.shared.contactForSessionCode(conversationId)
            ?? ContactStore.shared.contactForPeerUid(senderUid)
        if let existing,
           let pinnedPeer = existing.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines),
           !pinnedPeer.isEmpty,
           pinnedPeer.caseInsensitiveCompare(senderUid) != .orderedSame {
            NSLog("InternetMessageReceiver: dropping message; conversation belongs to a different peer")
            return false
        }

        // Determine the sender's static identity key: prefer the pinned contact key, else look it
        // up. A v1 message can't be authenticated without it; a v3 message authenticates via the
        // Signal session, so an absent v2 key is not fatal there.
        var lookedUp: PeerIdentityKey?
        var senderKeyB64 = ""
        if let existing,
           let pinnedKey = existing.peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines),
           !pinnedKey.isEmpty {
            senderKeyB64 = pinnedKey
        } else {
            // Layer 1b: for a brand-new thread only accept the canonical pair id for (sender, me),
            // which only the real sender can produce for their own uid.
            if existing == nil, conversationId != InternetConversation.pairId(senderUid, myUid) {
                NSLog("InternetMessageReceiver: dropping message; conversationId not canonical for sender")
                return false
            }
            if let peer = try? await client.lookupIdentityKey(uid: senderUid),
               !peer.publicKeyBase64.isEmpty {
                lookedUp = peer
                senderKeyB64 = peer.publicKeyBase64
            } else if !isSignal {
                NSLog("InternetMessageReceiver: dropping message; no identity key for sender")
                return false
            }
        }

        // Anti-downgrade pin: once a v3 session exists with this peer, a v1 message from them is a
        // stripped-v3 downgrade attempt — drop fail-closed (no ack). Same rule as Android.
        if !isSignal, SignalSessionGate.shared.hasV3Session(senderUid) {
            NSLog("InternetMessageReceiver: dropping v1 message; downgrade from a v3 session with peer")
            return false
        }

        // Claim the id BEFORE touching the ratchet: the push handler and the Firestore listener
        // both deliver each doc, and a v3 message key is one-time — the losing copy must skip
        // decryption entirely.
        guard InternetMessageDedup.shared.claim(messageId) else {
            return false
        }

        // Layer 2: decrypt fail-closed. v3 authenticates via the ratchet; v1 via static-static ECDH.
        let content: InternetMessageContent
        do {
            if isSignal {
                content = try SignalSessionGate.shared.decrypt(
                    senderUid, ctypeLabel: ctypeLabel, ciphertextBase64: ciphertext
                )
                SignalSessionGate.shared.clearNegativeProbe(senderUid)
            } else {
                let senderPublicKey = try MessagingKeyCodec.decodePublicKey(senderKeyB64)
                content = try InternetE2eEnvelope.open(
                    sealed: sealed,
                    deriveSharedSecret: { pub in try MessagingIdentity.shared.deriveSharedSecret(pub) },
                    senderStaticPublicKey: senderPublicKey,
                    senderUid: senderUid,
                    recipientUid: myUid,
                    conversationId: conversationId
                )
            }
        } catch SignalError.duplicatedMessage {
            // libsignal's authoritative "already decrypted" — it WAS delivered. Ack so the lost/
            // failed original ack doesn't leave a server copy replaying on every listener attach.
            await client.acknowledgeMessage(messageId)
            return false
        } catch {
            MessagingDiagLog.log("recv drop: authenticate/decrypt failed: \(String(describing: error))")
            // Stale-prekey self-heal: a missingRecord(<prekey>) means peers hold a PUBLISHED prekey of
            // ours that our store can't back — the classic reinstall case (keychain identity survived,
            // Application Support prekey store did not, and the backend only clears its pool on an
            // identity change). Every inbound v3 message from a fresh session then fails forever. Rotate
            // our Signal identity + republish so the backend wipes the stale pool; the peer re-handshakes
            // against the fresh keys (a changed safety number, handled by TOFU re-pin).
            if case SignalStoreError.missingRecord = error, isSignal {
                await Self.recoverFromStalePreKeys()
            }
            // A genuine failure — release the claim so a real later re-delivery can be retried.
            InternetMessageDedup.shared.release(messageId)
            return false
        }
        NSLog("InternetMessageReceiver: internet recv template=%d via %@", content.templateCode, isSignal ? "v3" : "v2")

        // Reserved control codes ride the same sealed channel but are never chat messages.
        if content.templateCode == InternetChatTransport.typingSignalTemplate {
            // Ephemeral "peer is typing" pulse → in-memory bus only; never stored, never notified.
            if let existing {
                await MainActor.run { TypingIndicatorBus.shared.signalTyping(sessionId: existing.id) }
            }
            await client.acknowledgeMessage(messageId)
            return false
        }
        if content.templateCode == InternetChatTransport.identityAnnounceTemplate {
            // A peer that just QR-paired with us announces its internet identity so we heal our
            // BLE-only paired contact — MTU-independent, unlike the BLE client-hello. Match by the
            // announced session code (our stored remoteSessionCode for them) and stamp their uid+key.
            let obj = content.text.data(using: .utf8)
                .flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
            let rsc = (obj?["rsc"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            // Prefer the key carried IN the (authenticated) payload — it is always present and needs
            // no directory lookup, which lags right after the peer's fresh install (audit root cause A).
            let announcedKey = (obj?["pk"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let effectiveKey = !senderKeyB64.isEmpty ? senderKeyB64 : announcedKey
            let target = rsc.isEmpty ? nil : ContactStore.shared.contactForRemoteSessionCode(rsc)
            MessagingDiagLog.log(
                "announce recv: rsc=\(rsc) key=\(!effectiveKey.isEmpty)(payload=\(!announcedKey.isEmpty)) matched=\(target?.name ?? "NONE")"
            )
            var didHeal = false
            if !effectiveKey.isEmpty, let target {
                // Heal FIRST, ack LAST: a healed contact + absorbed duplicate is the whole point, and
                // a v3 announce can't be re-decrypted on replay, so it must land on first receipt.
                if let healed = ContactStore.shared.healAndAbsorb(
                    id: target.id, peerUid: senderUid, peerPublicKey: effectiveKey
                ) {
                    didHeal = true
                    MessagingDiagLog.log("healed BLE contact '\(healed.name)' with internet identity from announce peer=\(senderUid.prefix(8))")
                }
            }
            await client.acknowledgeMessage(messageId)
            return didHeal
        }
        if content.templateCode == InternetChatTransport.callSignalTemplate {
            // Route to the call-signal bus (the WebRTC engine subscribes there); never filed as a
            // chat message.
            await client.acknowledgeMessage(messageId)
            // Auto-create/pin the caller's contact (trust-on-first-use) when we don't have one —
            // exactly like the plain-message path below. Without this, an incoming call from a peer
            // we never messaged, OR a BLE/QR-paired peer whose internet identity we never captured
            // (e.g. the QR-displaying side), left `existing` nil and the call signal was silently
            // dropped → the phone never rang. Android rings because its live BLE link backfills the
            // peer's uid; iOS has no such backfill, so resolve it here.
            let callContact = existing ?? ContactStore.shared.applyInternetIdentity(
                conversationSessionCode: conversationId,
                peerUid: senderUid,
                peerPublicKey: senderKeyB64,
                displayName: lookedUp?.displayName,
                peerPhotoUrl: lookedUp?.photoUrl,
                analyticsSource: "internet_call",
                analyticsReceived: true
            )
            if let callContact {
                let signalJson = content.text
                await MainActor.run {
                    InternetCallSignalBus.shared.publish(
                        contact: callContact,
                        senderUid: senderUid,
                        signalJson: signalJson
                    )
                }
            }
            return false
        }

        // Delivery/read receipts: apply to our local outgoing bubbles, never file, never notify.
        // The referenced ids are wire ids; a relay-callable fallback replaced '~' with ':' on the
        // sender, so try that variant too (mirrors Android's applyInternetReceipt).
        if content.templateCode == InternetChatTransport.deliveryReceiptTemplate
            || content.templateCode == InternetChatTransport.readReceiptTemplate {
            await client.acknowledgeMessage(messageId)
            guard let existing else { return false }
            let ids = Self.parseReceiptIds(content.text)
            guard !ids.isEmpty else { return false }
            let isRead = content.templateCode == InternetChatTransport.readReceiptTemplate
            await MainActor.run {
                if isRead {
                    SOSChatStore.shared.markRead(sessionId: existing.id, transportMessageIds: ids)
                } else {
                    SOSChatStore.shared.markDelivered(sessionId: existing.id, transportMessageIds: ids)
                }
            }
            return false
        }

        // A child device asks this user to become its parent → file the pending request (surfaced
        // in Settings > Child Profile) + notify; never a chat message.
        if content.templateCode == InternetChatTransport.parentRequestTemplate {
            await client.acknowledgeMessage(messageId)
            guard let existing else { return false }
            await MainActor.run {
                ChildProfileManager.shared.addIncomingRequest(existing.id)
            }
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: existing.id,
                title: existing.name,
                body: NSLocalizedString("CHILD_PARENT_REQUEST_NOTIFICATION", comment: ""),
                kind: .contactUpdate
            )
            return false
        }

        // The asked contact answered our parent request: promote or drop the pending entry.
        // confirmParent's pending check makes a duplicate push+listener delivery a no-op.
        if content.templateCode == InternetChatTransport.parentResponseTemplate {
            await client.acknowledgeMessage(messageId)
            guard let existing else { return false }
            let accepted = (try? JSONSerialization.jsonObject(
                with: Data(content.text.utf8)
            ) as? [String: Any])?["action"] as? String == "accept"
            let hadPending = await MainActor.run { () -> Bool in
                if accepted {
                    return ChildProfileManager.shared.confirmParent(existing.id)
                }
                let wasPending = ChildProfileManager.shared.pendingParents.contains(existing.id)
                if wasPending { ChildProfileManager.shared.removePendingParent(existing.id) }
                return wasPending
            }
            if hadPending {
                SOSNotificationCenter.notifyIncomingMessage(
                    sessionId: existing.id,
                    title: existing.name,
                    body: NSLocalizedString(
                        accepted ? "CHILD_PARENT_ACCEPTED_NOTIFICATION" : "CHILD_PARENT_DECLINED_NOTIFICATION",
                        comment: ""
                    ),
                    kind: .contactUpdate
                )
            }
            return false
        }

        let display = content.text.isEmpty ? "[#\(content.templateCode)]" : content.text

        // Pin / auto-create the contact (trust-on-first-use) and file the message.
        let contact = ContactStore.shared.applyInternetIdentity(
            conversationSessionCode: conversationId,
            peerUid: senderUid,
            peerPublicKey: senderKeyB64,
            displayName: lookedUp?.displayName,
            peerPhotoUrl: lookedUp?.photoUrl,
            analyticsSource: "internet_message",
            analyticsReceived: true
        ) ?? existing
        guard let contact else { return false }

        // Attachment chunk (voice / image / file): ack it so the relay drops its copy, buffer it,
        // and only persist + notify once every chunk of the transfer has arrived and reassembled.
        if let attachment = content.attachment {
            await client.acknowledgeMessage(messageId)
            guard let complete = InternetAttachmentReassembler.shared.offer(attachment) else {
                return false
            }
            let filed = await persistReceivedAttachment(attachment, data: complete, contact: contact)
            if filed {
                // The sender's bubble uuid IS the transferId, so this flips it to "delivered".
                _ = await InternetChatTransport.shared.sendReceipt(
                    contact: contact,
                    templateCode: InternetChatTransport.deliveryReceiptTemplate,
                    messageIds: [attachment.transferId]
                )
            }
            return filed
        }

        // SOS live-location update: rewrite the referenced alert bubble in place — silent. If the
        // original alert never arrived, file the update AS an SOS-styled alert so the contact still
        // ends up with the emergency + latest location (mirrors Android).
        if content.templateCode == InternetChatTransport.sosLocationUpdateTemplate {
            await client.acknowledgeMessage(messageId)
            guard let data = content.text.data(using: .utf8),
                  let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let ref = (payload["ref"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  let newText = (payload["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !ref.isEmpty, !newText.isEmpty else {
                return false
            }
            await MainActor.run {
                SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name, role: .unknown)
                // updateSosAlertText applies both Android guards atomically: it rewrites ONLY the
                // SOS-alert row matching the referenced id (history integrity), and promotes the
                // conversation-list preview only when that alert is still the latest message. Returns
                // false when no such alert exists yet — the original 202 never arrived, so we file the
                // update AS an SOS-styled, map-tappable alert bubble (Android: MessageType.SOS_ALERT),
                // not a plain-text message with no styling and no tappable location.
                let rewritten = SOSChatStore.shared.updateSosAlertText(
                    sessionId: contact.id, transportMessageId: ref, text: newText
                )
                if !rewritten {
                    _ = SOSChatStore.shared.appendRemoteMessage(
                        sessionId: contact.id, text: newText, transportMessageId: ref, kind: .sosAlert
                    )
                }
            }
            return false
        }

        // SOS alerts persist like text but with their own kind, so the chat renders them as a
        // distinct emergency bubble instead of an ordinary message.
        let isSosAlert = content.templateCode == InternetChatTransport.sosAlertTemplate
        let isNewMessage = await MainActor.run {
            SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name, role: .unknown)
            // A real message landing means the peer is done typing — drop the indicator now.
            TypingIndicatorBus.shared.clear(sessionId: contact.id)
            // A shared location arrives as CC_LOC plain text; the BLE paths have always parsed it
            // into the map bubble, but this path filed it as .text — so the peer saw raw
            // "CC_LOC:41.0,29.0,..." instead of a map whenever the location came over the internet.
            if !isSosAlert, let locationPayload = P2pSharedTransferSupport.parseLocationMessage(display) {
                let capturedAt = locationPayload.timestampMillis
                    .map { Date(timeIntervalSince1970: TimeInterval($0) / 1000) }
                    ?? Date()
                return SOSChatStore.shared.upsertRemoteLocationMessage(
                    sessionId: contact.id,
                    latitude: locationPayload.latitude,
                    longitude: locationPayload.longitude,
                    horizontalAccuracyMeters: locationPayload.accuracyMeters,
                    capturedAt: capturedAt,
                    transportMessageId: messageId
                )
            }
            return SOSChatStore.shared.appendRemoteMessage(
                sessionId: contact.id,
                text: display,
                transportMessageId: messageId,
                kind: isSosAlert ? .sosAlert : .text
            )
        }

        // Tell the sender their bubble reached this device (idempotent on their side — re-sending
        // for a duplicate delivery is harmless and covers a first receipt that got lost).
        _ = await InternetChatTransport.shared.sendReceipt(
            contact: contact,
            templateCode: InternetChatTransport.deliveryReceiptTemplate,
            messageIds: [messageId]
        )

        // Local notification with the decrypted preview (the server has no plaintext to push).
        // Duplicates — a message that raced in over both the listener and the push — are already
        // filed, so they neither notify nor count as new. Visible-chat suppression is handled by
        // SOSNotificationCenter itself.
        if isNewMessage {
            SOSNotificationCenter.notifyIncomingMessage(
                sessionId: contact.id,
                title: contact.name,
                body: P2pSharedTransferSupport.previewText(for: display),
                kind: isSosAlert ? .sosAlert : .chatMessage
            )
            // The alert and the content-available wake ride ONE push, so when the app is
            // backgrounded the system shows the generic "encrypted message" banner AND we post the
            // decrypted one — two banners per message, and the generic one never got swept (remote
            // requests carry system identifiers the "message." prefix sweep can't match). Remove
            // the system copy by its payload messageId instead.
            let duplicateMessageId = messageId
            UNUserNotificationCenter.current().getDeliveredNotifications { delivered in
                let dupes = delivered
                    .filter { ($0.request.content.userInfo["messageId"] as? String) == duplicateMessageId }
                    .filter { $0.request.identifier.hasPrefix("message.") == false }
                    .map(\.request.identifier)
                guard !dupes.isEmpty else { return }
                UNUserNotificationCenter.current()
                    .removeDeliveredNotifications(withIdentifiers: dupes)
            }
        }

        // Acknowledge so the server drops its copy (best-effort; a purge cleans up otherwise).
        await client.acknowledgeMessage(messageId)
        return isNewMessage
    }

    /// Receipt ids are wire message ids; when the original send fell back to the relay callable,
    /// its stricter charset replaced '~' with ':' — include that variant so the receipt lands.
    private static func parseReceiptIds(_ text: String) -> [String] {
        guard let data = text.data(using: .utf8),
              let payload = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let rawIds = payload["ids"] as? [Any] else {
            return []
        }
        var ids: [String] = []
        for raw in rawIds {
            guard let id = (raw as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !id.isEmpty else { continue }
            ids.append(id)
            if id.contains(":") {
                ids.append(id.replacingOccurrences(of: ":", with: "~"))
            }
        }
        return ids
    }

    /// Writes a reassembled attachment to the encrypted chat file store and files it as a voice or
    /// image bubble (the transferId doubles as the transport message id, so duplicate deliveries
    /// across the push + listener paths dedupe exactly like text). File/document attachments are
    /// dropped until the document milestone lands on iOS.
    private func persistReceivedAttachment(
        _ attachment: InternetMessageAttachment,
        data: Data,
        contact: ContactRecord
    ) async -> Bool {
        switch attachment.kind {
        case InternetE2eEnvelope.attachmentKindAudio:
            guard let audioRelativePath = SOSChatStore.persistVoiceData(
                data,
                messageId: attachment.transferId,
                mimeType: attachment.mime
            ) else { return false }
            let appended = await MainActor.run {
                SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name, role: .unknown)
                TypingIndicatorBus.shared.clear(sessionId: contact.id)
                return SOSChatStore.shared.appendRemoteAudioMessage(
                    sessionId: contact.id,
                    audioRelativePath: audioRelativePath,
                    durationMillis: attachment.durationMs > 0 ? attachment.durationMs : nil,
                    transportMessageId: attachment.transferId
                )
            }
            if appended {
                SOSNotificationCenter.notifyIncomingMessage(
                    sessionId: contact.id,
                    title: contact.name,
                    body: SOSChatStore.voicePreviewText(),
                    kind: .chatMessage
                )
            }
            return appended

        case InternetE2eEnvelope.attachmentKindImage:
            guard let imageRelativePath = SOSChatStore.persistImageData(
                data,
                messageId: attachment.transferId,
                mimeType: attachment.mime
            ) else { return false }
            let image = UIImage(data: data)
            let thumbnailRelativePath = image?
                .thumbnailJPEGData(side: 512, compressionQuality: 0.78)
                .flatMap {
                    SOSChatStore.persistImageThumbnailData(
                        $0,
                        messageId: attachment.transferId,
                        mimeType: attachment.mime
                    )
                }
            let appended = await MainActor.run {
                SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name, role: .unknown)
                TypingIndicatorBus.shared.clear(sessionId: contact.id)
                return SOSChatStore.shared.appendRemoteImageMessage(
                    sessionId: contact.id,
                    imageRelativePath: imageRelativePath,
                    thumbnailRelativePath: thumbnailRelativePath,
                    imageWidth: image.map { Int($0.size.width.rounded()) },
                    imageHeight: image.map { Int($0.size.height.rounded()) },
                    imageMimeType: attachment.mime,
                    transportMessageId: attachment.transferId
                )
            }
            if appended {
                SOSNotificationCenter.notifyIncomingMessage(
                    sessionId: contact.id,
                    title: contact.name,
                    body: SOSChatStore.imagePreviewText(),
                    kind: .chatMessage
                )
            }
            return appended

        case InternetE2eEnvelope.attachmentKindFile:
            // Store the document like the Bluetooth receiver does: an app-local copy keyed by the
            // transfer id. The bubble itself comes from the paired CC_FILE metadata TEXT message
            // (same uuid) shipped through the normal text path; tap-to-open resolves this copy.
            // No notification here — the CC_FILE message carries the visible bubble.
            let displayName = attachment.name.isEmpty ? attachment.transferId : attachment.name
            let stored = P2pSharedTransferSupport.persistSharedDocumentData(
                data,
                messageId: attachment.transferId,
                displayName: displayName
            )
            return stored != nil

        default:
            NSLog("InternetMessageReceiver: attachment kind=%d not supported yet; dropping", attachment.kind)
            return false
        }
    }
}

/// Buffers attachment chunks (keyed by transfer id) until every chunk of a transfer has arrived,
/// then returns the reassembled bytes. In-memory only — fine for the common case where both peers
/// are online and chunks land within seconds; a mid-transfer restart simply drops the partial
/// transfer. Bounded so a hostile sender can't exhaust memory. Mirrors Android's reassembler,
/// including the completed-transfer LRU that makes the duplicate push+listener delivery a no-op.
final class InternetAttachmentReassembler: @unchecked Sendable {
    static let shared = InternetAttachmentReassembler()

    private static let maxChunks = 4000
    private static let maxTotalSize = 25 * 1024 * 1024 // 25 MB
    private static let completedCapacity = 64
    /// A partial transfer that hasn't seen a new chunk in this long is assumed dead (the sender
    /// backgrounded/crashed/left range) and its buffered chunks are evicted. Without this an
    /// interrupted transfer's chunks would sit in memory until the app restarts.
    private static let pendingTTL: TimeInterval = 5 * 60

    private struct Pending {
        let chunkCount: Int
        let totalSize: Int
        var chunks: [Int: Data] = [:]
        var lastUpdated: Date = Date()
    }

    private let lock = NSLock()
    private var pending: [String: Pending] = [:]
    private var completedTransferIds: [String] = []

    private init() {}

    func offer(_ attachment: InternetMessageAttachment) -> Data? {
        guard (1...Self.maxChunks).contains(attachment.chunkCount),
              (1...Self.maxTotalSize).contains(attachment.totalSize),
              (0..<attachment.chunkCount).contains(attachment.chunkIndex),
              !attachment.transferId.isEmpty else {
            return nil
        }
        lock.lock()
        defer { lock.unlock() }

        // Evict transfers that stalled mid-flight — an interrupted upload never completes, so its
        // buffered chunks would otherwise leak until the app restarts. Swept lazily under the same
        // lock on every offer (no timer needed); the small in-flight set makes this negligible.
        let now = Date()
        if !pending.isEmpty {
            pending = pending.filter { now.timeIntervalSince($0.value.lastUpdated) < Self.pendingTTL }
        }

        if completedTransferIds.contains(attachment.transferId) { return nil }

        var entry = pending[attachment.transferId] ?? Pending(
            chunkCount: attachment.chunkCount,
            totalSize: attachment.totalSize
        )
        guard entry.chunkCount == attachment.chunkCount, entry.totalSize == attachment.totalSize else {
            return nil
        }
        entry.chunks[attachment.chunkIndex] = attachment.bytes
        entry.lastUpdated = now
        pending[attachment.transferId] = entry

        guard entry.chunks.count == entry.chunkCount else { return nil }

        var assembled = Data(capacity: entry.totalSize)
        for index in 0..<entry.chunkCount {
            guard let chunk = entry.chunks[index] else { return nil }
            assembled.append(chunk)
        }
        guard assembled.count == entry.totalSize else {
            pending.removeValue(forKey: attachment.transferId)
            return nil
        }
        pending.removeValue(forKey: attachment.transferId)
        completedTransferIds.append(attachment.transferId)
        if completedTransferIds.count > Self.completedCapacity {
            completedTransferIds.removeFirst(completedTransferIds.count - Self.completedCapacity)
        }
        return assembled
    }
}
