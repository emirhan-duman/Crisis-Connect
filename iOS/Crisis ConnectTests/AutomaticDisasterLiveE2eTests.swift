import XCTest
import CryptoKit
import FirebaseCore
import FirebaseAuth
import FirebaseFunctions
import FirebaseFirestore
import FirebaseAppCheck
@testable import Crisis_Connect

/// Runs only when an explicit localhost fixture supplies temporary, revocable test credentials.
/// Uses named Firebase apps and disposable anonymous users; default account remains untouched.
final class AutomaticDisasterLiveE2eTests: XCTestCase {
    func testLiveNormalConservationAndRecovery() async throws {
        let fixtureURL = URL(string: "http://127.0.0.1:31234/ios")!
        let fixtureData: Data
        do { (fixtureData, _) = try await URLSession.shared.data(from: fixtureURL) }
        catch { throw XCTSkip("Live E2E requires the explicitly started localhost test fixture") }
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: fixtureData) as? [String: String])
        let run = try XCTUnwrap(fixture["runId"])
        let debugToken = try XCTUnwrap(fixture["debugToken"])
        setenv("FIRAAppCheckDebugToken", debugToken, 1)
        AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
        defer { unsetenv("FIRAAppCheckDebugToken"); AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory()) }
        let options = try XCTUnwrap(FirebaseApp.app()?.options)
        for index in 0...1 { FirebaseApp.configure(name: "disaster-live-\(run)-\(index)", options: options) }
        let apps = try (0...1).map { try XCTUnwrap(FirebaseApp.app(name: "disaster-live-\(run)-\($0)")) }
        let auths = apps.map { Auth.auth(app: $0) }
        let traffic = DisasterTrafficController.shared
        do {
            for app in apps { _ = try await XCTUnwrap(AppCheck.appCheck(app: app)).token(forcingRefresh: true) }
            let senderUid = try await auths[0].signInAnonymously().user.uid
            let receiverUid = try await auths[1].signInAnonymously().user.uid
            var report = URLRequest(url: fixtureURL); report.httpMethod = "POST"
            report.httpBody = try JSONSerialization.data(withJSONObject: ["uids": [senderUid, receiverUid], "installationId": MessagingInstallation.id])
            _ = try await URLSession.shared.data(for: report)
            let functions = apps.map { Functions.functions(app: $0, region: "us-central1") }
            let stores = apps.map { Firestore.firestore(app: $0) }
            let sender = InternetMessagingClient(functions: functions[0], auth: auths[0], firestore: stores[0])
            let receiver = InternetMessagingClient(functions: functions[1], auth: auths[1], firestore: stores[1])
            XCTAssertFalse(traffic.isSaving, "Dedicated test device starts in normal mode")
            // Both the old request shape and the new explicit capability are accepted live.
            let legacyToken = "live-legacy-\(run)"
            _ = try await functions[1].httpsCallable("registerPushToken").call(["token": legacyToken, "platform": "ios"])
            try await receiver.unregisterPushToken(legacyToken)
            let modernToken = "live-modern-\(run)"
            try await receiver.registerPushToken(modernToken); try await receiver.unregisterPushToken(modernToken)
            let keys = P256.KeyAgreement.PrivateKey()
            let contact = ContactRecord(id: UUID(), name: "Live test receiver", broadcastId: "live-\(run)", sessionCode: "live-\(run)", aesKeyBase64: "", preferredTransport: .legacyBroadcast, peerUid: receiverUid, peerPublicKey: keys.publicKey.derRepresentation.base64EncodedString(), createdAt: Date(), lastUpdated: Date())
            let probeKey = "cc.signal.probe.\(receiverUid)"
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: probeKey)
            defer { UserDefaults.standard.removeObject(forKey: probeKey) }
            let transport = InternetChatTransport(client: sender)
            // Wait for its ordinary NWPathMonitor to report the simulator's live connection.
            for _ in 0..<30 { if transport.isAvailable() { break }; try await Task.sleep(nanoseconds: 100_000_000) }
            let normalId = "live-ios-normal-\(run)"
            let normalSent = await transport.sendText(messageId: normalId, text: "normal live encrypted", contact: contact)
            XCTAssertTrue(normalSent)
            let normal = try await stores[1].collection("messages").document(normalId).getDocument()
            XCTAssertEqual(normal.get("senderUid") as? String, senderUid)
            let sealed = SealedInternetMessage(ephemeralPublicKey: try XCTUnwrap(normal.get("ephemeralPubKey") as? String), nonce: try XCTUnwrap(normal.get("nonce") as? String), ciphertext: try XCTUnwrap(normal.get("ciphertext") as? String))
            let opened = try InternetE2eEnvelope.open(sealed: sealed, recipientPrivateKey: keys, senderStaticPublicKey: MessagingIdentity.shared.publicKey(), senderUid: senderUid, recipientUid: receiverUid, conversationId: try XCTUnwrap(normal.get("conversationId") as? String))
            XCTAssertEqual(opened.text, "normal live encrypted")
            await receiver.acknowledgeMessage(normalId)
            traffic.setEmergency(true)
            XCTAssertTrue(traffic.isSaving, "SOS automatically enables conservation")
            _ = try await receiver.compactInbox(cursor: nil)
            let typing = await transport.sendTypingSignal(contact: contact)
            XCTAssertFalse(typing)
            let tooLong = await transport.sendText(messageId: "live-rejected-\(run)", text: String(repeating: "x", count: 1025), contact: contact)
            XCTAssertFalse(tooLong)
            let conversation = InternetConversation.pairId(senderUid, receiverUid)
            let peerKeys = P256.KeyAgreement.PrivateKey()
            try await sender.publishIdentityKey(publicKeyBase64: peerKeys.publicKey.derRepresentation.base64EncodedString(), discoverable: false, displayName: "Live test sender \(run)")
            let handler = InternetMessageReceiver(client: receiver)
            for template in [0, 202] {
                let id = "live-ios-inbound-\(template)-\(run)~\(Int(Date().timeIntervalSince1970 * 1000))"
                let text = template == 202 ? "Live isolated SOS" : "compact live encrypted"
                let incoming = try InternetE2eEnvelope.seal(content: InternetMessageContent(templateCode: template, text: text), recipientPublicKey: MessagingIdentity.shared.publicKey(), senderPrivateKey: peerKeys, senderUid: senderUid, recipientUid: receiverUid, conversationId: conversation)
                _ = try await sender.relayMessage(OutgoingInternetEnvelope(messageId: id, conversationId: conversation, recipientUid: receiverUid, priority: template == 202 ? "high" : "normal", createdAtMs: Int(Date().timeIntervalSince1970 * 1000), ttlMs: 86_400_000, sealed: incoming))
                let page = try await receiver.compactInbox(cursor: nil)
                let message = try XCTUnwrap(page.messages.first { $0["messageId"] as? String == id }, "Live compact inbox retains original '~' ID")
                let received = await handler.handle(data: message, myUid: receiverUid)
                XCTAssertTrue(received)
                let inboundContact = try XCTUnwrap(ContactStore.shared.contactForPeerUid(senderUid))
                let persisted = await MainActor.run { SOSChatStore.shared.messages(for: inboundContact.id).first { $0.transportMessageId == id } }
                XCTAssertEqual(persisted?.text, text)
                XCTAssertTrue(CompactInboxState.pending(uid: receiverUid).contains(id))
                let acknowledged = try await receiver.compactInbox(cursor: nil)
                XCTAssertFalse(acknowledged.messages.contains { $0["messageId"] as? String == id })
            }
            let savingId = "live-ios-saving-\(run)~\(Int(Date().timeIntervalSince1970 * 1000))"
            let savingSent = await transport.sendText(messageId: savingId, text: "saving live encrypted", contact: contact)
            XCTAssertTrue(savingSent)
            let savingPage = try await receiver.compactInbox(cursor: nil)
            XCTAssertTrue(savingPage.messages.contains { $0["messageId"] as? String == savingId })
            await receiver.acknowledgeMessage(savingId); _ = try await receiver.compactInbox(cursor: nil)
            traffic.setEmergency(false)
            XCTAssertTrue(traffic.isSaving, "Recovery grace prevents mode flapping")
            let deadline = Date().addingTimeInterval(325)
            while traffic.isSaving, Date() < deadline { try await Task.sleep(nanoseconds: 1_000_000_000) }
            XCTAssertFalse(traffic.isSaving)
            _ = try await receiver.compactInbox(cursor: nil)
            let recoveredId = "live-ios-recovered-\(run)"
            let recoveredSent = await transport.sendText(messageId: recoveredId, text: "recovered normal encrypted", contact: contact)
            XCTAssertTrue(recoveredSent)
            let recovered = try await receiver.compactInbox(cursor: nil)
            XCTAssertTrue(recovered.messages.contains { $0["messageId"] as? String == recoveredId })
            await receiver.acknowledgeMessage(recoveredId)
            let cleared = try await receiver.compactInbox(cursor: nil)
            XCTAssertFalse(cleared.messages.contains { $0["messageId"] as? String == recoveredId })
            print("DisasterLiveE2E COMPLETED normal/direct + automatic SOS + compact/relay + local persistence + batched ACK + 300s recovery")
        } catch {
            for auth in auths { try? await auth.currentUser?.delete() }
            traffic.setEmergency(false)
            throw error
        }
        for auth in auths { try? await auth.currentUser?.delete() }
        for app in apps { await withCheckedContinuation { continuation in app.delete { _ in continuation.resume() } } }
    }
}
