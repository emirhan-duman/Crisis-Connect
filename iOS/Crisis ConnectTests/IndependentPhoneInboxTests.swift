import XCTest
@testable import Crisis_Connect

@MainActor final class IndependentPhoneInboxTests: XCTestCase {
    func testApprovedWatchCiphertextCommitsToNormalChatOnce() async throws {
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        let suffix = UUID().uuidString.replacingOccurrences(of: "-", with: "")
        let uid = "controlledWatchApple" + suffix, owner = "controlledPhoneApple" + suffix
        let base = ContactStore.shared.upsertContact(name: "Kontrollü bağımsız saat", broadcastId: "independent-watch-test-" + suffix, aesKeyBase64: "")
        let contact = try XCTUnwrap(ContactStore.shared.applyInternetIdentity(conversationSessionCode: base.sessionCode, peerUid: uid, peerPublicKey: "controlled-contact-key", displayName: base.name))
        try await ContactStore.shared.flushDurably()
        var calls = 0
        let receiver = try IndependentPhoneInbox(ownerUid: owner, endpoint: URL(string: "http://127.0.0.1:8298/gateway")!, call: { name, _ in
            calls += 1
            return name == "enrollIndependentWatch" ? ["signalDeviceId": 2] : ["approved": true]
        }, accountAvailable: { true })
        try await receiver.enable()
        let senderID = UUID().uuidString.lowercased(), sender = try IndependentSignalVault(namespace: senderID)
        let descriptor: [String: Any] = ["ownerUid": uid, "deviceID": senderID, "bundle": try sender.publicBundle(device: 3)]
        try await receiver.approve(contact: contact, descriptor: descriptor)
        try sender.pinPeer(uid: owner, device: 2, identity: receiver.service.signal.identityKey())
        let id = UUID().uuidString.lowercased(), text = "Saatten bağımsız kontrollü mesaj"
        let wire = try sender.prepare(id: id, uid: owner, device: 2, bundle: receiver.service.signal.publicBundle(device: 2), clear: IndependentPhoneInbox.textContent(text))
        let message: [String: Any] = ["id": id, "senderUid": uid, "senderDevice": 3, "senderInstallation": senderID, "ctype": wire.ctype, "ciphertext": wire.ciphertext]
        try await receiver.receiveApproved(message) // ACK unavailable; normal chat still commits.
        try await receiver.receiveApproved(message)
        let first = try XCTUnwrap(SOSChatStore.shared.messages(for: contact.id).first { $0.transportMessageId == id })
        XCTAssertEqual(first.text, text); XCTAssertFalse(first.isLocal)
        let reopened = try IndependentPhoneInbox(ownerUid: owner, endpoint: URL(string: "http://127.0.0.1:8298/gateway")!, call: { _, _ in XCTFail("Restore must not enroll or send"); return [:] }, accountAvailable: { true })
        try await reopened.restoreChats(); try await reopened.receiveApproved(message)
        let rows = SOSChatStore.shared.messages(for: contact.id).filter { $0.transportMessageId == id }
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows.first?.id, first.id); XCTAssertEqual(rows.first?.timestamp, first.timestamp)
        var wrong = message; wrong["senderDevice"] = 4
        do { try await reopened.receiveApproved(wrong); XCTFail("Changed device accepted") } catch {}
        let signedOut = try IndependentPhoneInbox(ownerUid: owner, accountAvailable: { false })
        do { try await signedOut.restoreChats(); XCTFail("Signed-out history accepted") } catch {}
        let sentID = UUID().uuidString.lowercased()
        for outcome in ["uncertain", "accepted", "delivered", "accepted", "read", "uncertain"] {
            try SOSChatStore.shared.reconcileIndependentWatchSent(contact: contact, id: sentID, text: "Kendi saatimden", outcome: outcome)
        }
        try await SOSChatStore.shared.flushToDisk()
        let sent = SOSChatStore.shared.messages(for: contact.id).filter { $0.transportMessageId == sentID }
        XCTAssertEqual(sent.count, 1); XCTAssertEqual(sent.first?.status, .read)
        XCTAssertFalse(SOSChatStore.shared.pendingOutgoingTextMessages().contains { $0.transportMessageId == sentID })
        XCTAssertEqual(calls, 3)
        let report: [String: Any] = ["passed": true, "messageID": id, "contactID": contact.id.uuidString, "sentID": sentID, "accountProvider": "controlled injection; not Firebase Auth SDK", "ownerUid": owner]
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        try JSONSerialization.data(withJSONObject: report).write(to: documents.appendingPathComponent("independent-phone-result.json"), options: .atomic)
    }
    func testRestoreAfterAppRestart() async throws {
        let documents = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let report = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: documents.appendingPathComponent("independent-phone-result.json"))) as? [String: Any])
        await ContactStore.shared.waitUntilLoaded(); await SOSChatStore.shared.waitUntilLoaded()
        let contactID = try XCTUnwrap(UUID(uuidString: report["contactID"] as! String)), id = report["messageID"] as! String
        let before = try XCTUnwrap(SOSChatStore.shared.messages(for: contactID).first { $0.transportMessageId == id })
        let reopened = try IndependentPhoneInbox(ownerUid: report["ownerUid"] as! String, call: { _, _ in XCTFail("Restore must not send"); return [:] }, accountAvailable: { true })
        try await reopened.restoreChats()
        let rows = SOSChatStore.shared.messages(for: contactID).filter { $0.transportMessageId == id }
        XCTAssertEqual(rows.count, 1); XCTAssertEqual(rows.first?.id, before.id)
        XCTAssertEqual(rows.first?.timestamp, before.timestamp)
        XCTAssertEqual(SOSChatStore.shared.messages(for: contactID).first { $0.transportMessageId == report["sentID"] as? String }?.status, .read)
    }
}
