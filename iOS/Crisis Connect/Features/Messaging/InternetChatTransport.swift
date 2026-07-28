//
//  InternetChatTransport.swift
//  Crisis Connect
//
//  Sends chat messages over the E2E internet transport — the iOS port of Android's
//  InternetChatTransport. Every send funnels through ONE `sealAndRelay`, which picks the crypto
//  layer per peer (FS-6, same rules as Android):
//    v3 (Signal Double Ratchet, forward secret) when a session exists or can be established from
//       the peer's published PQXDH prekeys — wire doc v==2 {ctype, ciphertext};
//    v2 (authenticated ECIES envelope) otherwise — wire doc v==1.
//  Migrating ALL sends keeps the anti-downgrade pin sound: once a v3 session exists, every message
//  to that peer is v3, so a v1 from a v3-peer is unambiguously a downgrade on the receive side.
//
//  The server only ever sees the opaque envelope — it can route but never decrypt.
//

import Foundation
import Network

final class InternetChatTransport: @unchecked Sendable {
    static let shared = InternetChatTransport()

    private let client: InternetMessagingClient
    private let monitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "internet.transport.reachability")
    private let stateLock = NSLock()
    private var isOnline = false

    static let templateFreeText = 0
    // Reserved templateCodes shared with Android — control traffic that rides the sealed channel
    // but must never be filed as a chat message.
    static let callSignalTemplate = 200
    static let typingSignalTemplate = 201
    // SOS emergency alert: text still carries the human-readable message, so clients that ignore
    // the code degrade to plain text.
    static let sosAlertTemplate = 202
    // Live-location update to an earlier SOS alert; text = {"ref": <alert id>, "text": <body>}.
    static let sosLocationUpdateTemplate = 203
    // E2E delivery/read receipts; text = {"ids":[...]} referencing wire message ids.
    static let deliveryReceiptTemplate = 204
    static let readReceiptTemplate = 205
    // Parent/child linking signals (feature not on iOS yet; acked + dropped on receive).
    static let parentRequestTemplate = 206
    static let parentResponseTemplate = 207
    // Reciprocal identity announce after a QR pairing: the SCANNER tells the displayer its own
    // internet identity over the relay so the displayer heals its BLE-only paired contact — the
    // MTU-independent half of bidirectional QR pairing. Payload {"rsc":<scanner sessionCode>,
    // "name":<scanner name>}; senderUid + key on the envelope ARE the identity. Byte-parity w/ Android.
    static let identityAnnounceTemplate = 208
    private static let defaultTtlMs = 24 * 60 * 60 * 1000
    private static let typingSignalTtlMs = 15 * 1000
    private static let callSignalTtlMs = 60 * 1000
    // Max raw bytes per chunk, identical to Android: base64(ciphertext+tag+header) must stay under
    // the backend's 24 KB ciphertext rule cap; ~12 KB raw → ~16 KB base64, comfortably below.
    private static let chunkSize = 12_000

    init(client: InternetMessagingClient = InternetMessagingClient()) {
        self.client = client
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.stateLock.lock()
            self.isOnline = path.status == .satisfied
            self.stateLock.unlock()
        }
        monitor.start(queue: monitorQueue)
    }

    /// True only when signed in AND on a usable connection.
    func isAvailable() -> Bool {
        guard client.currentUid != nil else { return false }
        stateLock.lock(); defer { stateLock.unlock() }
        return isOnline
    }

    // MARK: - The single seal-and-relay funnel (v3 preferred, v2 fallback)

    private struct PeerRoute {
        let myUid: String
        let peerUid: String
        let peerKeyB64: String? // v2 identity key; optional because v3 doesn't need it
        let conversationId: String
    }

    private func route(for contact: ContactRecord) -> PeerRoute? {
        guard let myUid = client.currentUid else {
            MessagingDiagLog.log("route: no signed-in uid — internet send impossible")
            return nil
        }
        guard let peerUid = contact.peerUid?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty else {
            MessagingDiagLog.log("route: contact \(contact.id) has no peerUid — internet send impossible (BT-only contact?)")
            return nil
        }
        // The wire conversationId is the SYMMETRIC pair id of the two uids, so both peers derive
        // the same id without a round trip.
        return PeerRoute(
            myUid: myUid,
            peerUid: peerUid,
            peerKeyB64: contact.peerPublicKey?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty,
            conversationId: InternetConversation.pairId(myUid, peerUid)
        )
    }

    /// Seals `content` with the best available crypto layer and hands it to the relay/direct-write
    /// path. `forceEstablish` false = ephemeral pulses (typing) use an existing v3 session but
    /// never drive establishment; `directWriteOnly` skips the callable fallback.
    private func sealAndRelay(
        route: PeerRoute,
        content: InternetMessageContent,
        messageId: String,
        createdAtMs: Int,
        priority: String,
        ttlMs: Int,
        forceEstablish: Bool = true,
        directWriteOnly: Bool = false,
        // Stamped on the wire doc (not the ciphertext): set only for a call OFFER so the backend
        // fires an iOS PushKit VoIP ring for a force-quit recipient.
        callRing: Bool = false
    ) async throws {
        let envelope: RelayableInternetEnvelope
        if await SignalSessionGate.shared.ensureOutbound(route.peerUid, forceEstablish: forceEstablish) {
            let wire = try SignalSessionGate.shared.encrypt(route.peerUid, content: content)
            envelope = OutgoingSignalInternetEnvelope(
                messageId: messageId,
                conversationId: route.conversationId,
                recipientUid: route.peerUid,
                priority: priority,
                createdAtMs: createdAtMs,
                ttlMs: ttlMs,
                ctype: wire.ctype,
                ciphertextBase64: wire.ciphertextBase64,
                callRing: callRing
            )
        } else {
            guard let peerKeyB64 = route.peerKeyB64 else {
                throw InternetMessagingClientError.malformedResponse
            }
            let recipientPublicKey = try MessagingKeyCodec.decodePublicKey(peerKeyB64)
            let sealed = try InternetE2eEnvelope.seal(
                content: content,
                recipientPublicKey: recipientPublicKey,
                deriveSenderStaticSecret: { peerPub in
                    try MessagingIdentity.shared.deriveSharedSecret(peerPub)
                },
                senderUid: route.myUid,
                recipientUid: route.peerUid,
                conversationId: route.conversationId
            )
            envelope = OutgoingInternetEnvelope(
                messageId: messageId,
                conversationId: route.conversationId,
                recipientUid: route.peerUid,
                priority: priority,
                createdAtMs: createdAtMs,
                ttlMs: ttlMs,
                sealed: sealed,
                callRing: callRing
            )
        }
        // Content-free breadcrumb mirroring Android, so cross-platform tests read the same signals.
        MessagingDiagLog.log(
            "internet send template=\(content.templateCode) via " +
                ((envelope is OutgoingSignalInternetEnvelope) ? "v3" : "v2") +
                " to=\(route.peerUid.prefix(8)) id=\(messageId)"
        )
        if directWriteOnly {
            try await client.writeMessageDoc(envelope)
        } else {
            try await relayEnvelope(envelope)
        }
    }

    /// Direct Firestore write first (fast, no cold start), callable as the fallback with the id
    /// sanitized to its stricter charset — mirrors Android's relayEnvelope.
    private func relayEnvelope(_ envelope: RelayableInternetEnvelope) async throws {
        do {
            try await client.writeMessageDoc(envelope)
        } catch {
            MessagingDiagLog.log("direct write failed (\(error)) — falling back to relayMessage callable")
            _ = try await client.relayMessage(envelope.sanitizedForCallable())
        }
    }

    // MARK: - Public send surface (unchanged signatures)

    /// Seals `text` to `contact` and relays it. Returns true on accepted delivery to the server.
    @discardableResult
    func sendText(
        messageId: String,
        text: String,
        createdAtMs: Int = Int(Date().timeIntervalSince1970 * 1000),
        contact: ContactRecord,
        priority: String = "normal",
        templateCode: Int = InternetChatTransport.templateFreeText
    ) async -> Bool {
        guard let route = route(for: contact) else { return false }
        do {
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(templateCode: templateCode, text: text),
                messageId: messageId,
                createdAtMs: createdAtMs,
                priority: priority,
                ttlMs: Self.defaultTtlMs
            )
            return true
        } catch {
            MessagingDiagLog.log("send FAILED for \(messageId): \(String(describing: error))")
            return false
        }
    }

    /// Seals and relays a binary attachment (voice clip / image / file) to `contact`, chunked to
    /// stay under the relay's ciphertext cap — the exact wire format Android sends and expects.
    @discardableResult
    func sendAttachment(
        transferId: String,
        kind: Int,
        mime: String,
        name: String,
        durationMs: Int,
        data: Data,
        contact: ContactRecord,
        priority: String = "normal"
    ) async -> Bool {
        guard let route = route(for: contact), !data.isEmpty else { return false }
        let createdAtMs = Int(Date().timeIntervalSince1970 * 1000)
        do {
            let totalSize = data.count
            let chunkCount = (totalSize + Self.chunkSize - 1) / Self.chunkSize
            for index in 0..<chunkCount {
                let start = index * Self.chunkSize
                let end = min(start + Self.chunkSize, totalSize)
                try await sealAndRelay(
                    route: route,
                    content: InternetMessageContent(
                        templateCode: 0,
                        text: "",
                        attachment: InternetMessageAttachment(
                            kind: kind,
                            mime: mime,
                            name: name,
                            transferId: transferId,
                            chunkIndex: index,
                            chunkCount: chunkCount,
                            totalSize: totalSize,
                            durationMs: durationMs,
                            bytes: data.subdata(in: start..<end)
                        )
                    ),
                    // Unique per chunk (server dedups on messageId); receiver groups by transferId.
                    messageId: "\(transferId).\(index)",
                    createdAtMs: createdAtMs,
                    priority: priority,
                    ttlMs: Self.defaultTtlMs
                )
            }
            return true
        } catch {
            NSLog("InternetChatTransport: attachment send failed for %@: %@", transferId, String(describing: error))
            return false
        }
    }

    /// Announces our internet identity to a freshly QR-paired [contact] so their side heals its
    /// BLE-only reciprocal contact (see identityAnnounceTemplate). [mySessionCode] is our OWN P2P
    /// session code — the value the peer stored as remoteSessionCode when we paired — so they match
    /// it to the right contact. Durable so a briefly-offline peer still heals.
    @discardableResult
    func sendIdentityAnnounce(contact: ContactRecord, mySessionCode: String, myName: String?) async -> Bool {
        guard let route = route(for: contact) else { return false }
        var payloadObj: [String: Any] = ["rsc": mySessionCode]
        if let myName, !myName.isEmpty { payloadObj["name"] = myName }
        // Carry our OWN v2 messaging public key in the authenticated payload so the peer heals
        // WITHOUT a directory lookup of our uid — the lookup lags right after a fresh install and a
        // v3 announce leaves the receiver's senderKey empty otherwise (audit root cause A).
        if let myKey = try? MessagingIdentity.shared.publicKeyBase64(), !myKey.isEmpty {
            payloadObj["pk"] = myKey
        }
        do {
            let payload = try JSONSerialization.data(withJSONObject: payloadObj)
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(
                    templateCode: Self.identityAnnounceTemplate,
                    text: String(decoding: payload, as: UTF8.self)
                ),
                messageId: UUID().uuidString,
                createdAtMs: Int(Date().timeIntervalSince1970 * 1000),
                priority: "normal",
                ttlMs: Self.defaultTtlMs
            )
            return true
        } catch {
            MessagingDiagLog.log("identity announce send failed: \(String(describing: error))")
            return false
        }
    }

    /// Sends an E2E delivery/read receipt ({"ids":[...]}, templateCode 204/205), byte-compatible
    /// with Android. Idempotent on the receiving side, so re-sending for a duplicate is harmless.
    @discardableResult
    func sendReceipt(contact: ContactRecord, templateCode: Int, messageIds: [String]) async -> Bool {
        guard let route = route(for: contact) else { return false }
        let ids = Array(Set(messageIds.map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }))
        guard !ids.isEmpty else { return true }
        do {
            let payload = try JSONSerialization.data(withJSONObject: ["ids": ids])
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(
                    templateCode: templateCode,
                    text: String(decoding: payload, as: UTF8.self)
                ),
                messageId: UUID().uuidString,
                createdAtMs: Int(Date().timeIntervalSince1970 * 1000),
                priority: "normal",
                ttlMs: Self.defaultTtlMs
            )
            return true
        } catch {
            NSLog("InternetChatTransport: receipt send failed: %@", String(describing: error))
            return false
        }
    }

    /// Asks `contact` to become this (child) device's parent; payload {"action":"request"}.
    @discardableResult
    func sendParentRequest(contact: ContactRecord) async -> Bool {
        await sendParentSignal(contact: contact, templateCode: Self.parentRequestTemplate, action: "request")
    }

    /// Answers a parent request with accept/decline; payload {"action":"accept"|"decline"}.
    @discardableResult
    func sendParentResponse(contact: ContactRecord, accepted: Bool) async -> Bool {
        await sendParentSignal(
            contact: contact,
            templateCode: Self.parentResponseTemplate,
            action: accepted ? "accept" : "decline"
        )
    }

    private func sendParentSignal(contact: ContactRecord, templateCode: Int, action: String) async -> Bool {
        guard let route = route(for: contact) else { return false }
        do {
            let payload = try JSONSerialization.data(withJSONObject: ["action": action])
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(
                    templateCode: templateCode,
                    text: String(decoding: payload, as: UTF8.self)
                ),
                messageId: UUID().uuidString,
                createdAtMs: Int(Date().timeIntervalSince1970 * 1000),
                priority: "normal",
                ttlMs: Self.defaultTtlMs
            )
            return true
        } catch {
            NSLog("InternetChatTransport: parent signal send failed: %@", String(describing: error))
            return false
        }
    }

    /// Seals and relays a WebRTC call signal (SDP offer/answer, ICE candidate, ring/accept/reject/
    /// end JSON) as an ordinary E2E message tagged templateCode 200 — byte-compatible with Android.
    /// High priority + short TTL: call setup is real-time and worthless once stale.
    @discardableResult
    func sendCallSignal(contact: ContactRecord, signalJson: String) async -> Bool {
        guard let route = route(for: contact) else { return false }
        // Only a fresh OFFER wakes a force-quit peer (PushKit VoIP ring). "restart-offer" is
        // mid-call renegotiation (app already running) and must NOT ring.
        let isOffer: Bool = {
            guard let data = signalJson.data(using: .utf8),
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
                return false
            }
            let kind = (obj["type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? (obj["kind"] as? String)
            return kind == "offer"
        }()
        do {
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(templateCode: Self.callSignalTemplate, text: signalJson),
                messageId: UUID().uuidString,
                createdAtMs: Int(Date().timeIntervalSince1970 * 1000),
                priority: "high",
                ttlMs: Self.callSignalTtlMs,
                callRing: isOffer
            )
            return true
        } catch {
            NSLog("InternetChatTransport: call signal send failed: %@", String(describing: error))
            return false
        }
    }

    /// Fire-and-forget "I'm typing" pulse (templateCode 201), byte-compatible with Android. Direct
    /// Firestore write ONLY, normal priority, short TTL — and never establishes a v3 session just
    /// for an ephemeral pulse (forceEstablish false).
    @discardableResult
    func sendTypingSignal(contact: ContactRecord) async -> Bool {
        guard let route = route(for: contact) else { return false }
        do {
            try await sealAndRelay(
                route: route,
                content: InternetMessageContent(templateCode: Self.typingSignalTemplate, text: "1"),
                messageId: UUID().uuidString,
                createdAtMs: Int(Date().timeIntervalSince1970 * 1000),
                priority: "normal",
                ttlMs: Self.typingSignalTtlMs,
                forceEstablish: false,
                directWriteOnly: true
            )
            return true
        } catch {
            return false
        }
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
