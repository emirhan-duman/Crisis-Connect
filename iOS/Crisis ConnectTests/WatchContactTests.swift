import XCTest
@testable import Crisis_Connect

@MainActor final class WatchContactTests: XCTestCase {
    private func contact(_ id: UUID = UUID()) -> ContactRecord {
        ContactRecord(id: id, name: "Test kişi", broadcastId: "test", sessionCode: "test", aesKeyBase64: "",
            preferredTransport: .bleGatt, peerUid: "isolated-test", peerPublicKey: "isolated-key", createdAt: .now, lastUpdated: .now)
    }
    func testColdContactRestorePublishesRosterBeforeReturning() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("contacts.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let saved = contact()
        try LocalEncryptedFileStore.write(JSONEncoder().encode([saved]), to: url)
        let restored = ContactStore(persistenceURL: url)
        await restored.waitUntilLoaded()
        XCTAssertEqual(restored.contacts.map(\.id), [saved.id])
        XCTAssertEqual(restored.contact(for: saved.id)?.name, saved.name)
    }

    func testSavedContactValidationAndOldStateCompatibility() throws {
        XCTAssertFalse(WatchCommand(action: .contactReply, reply: .safe).isValid(at: Date().timeIntervalSince1970))
        XCTAssertTrue(WatchCommand(action: .contactReply, peerID: UUID().uuidString, reply: .safe).isValid(at: Date().timeIntervalSince1970))
        let old = Data(#"{"version":1,"sosActive":false,"bleBroadcasting":false,"updatedAt":1}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(WatchPhoneState.self, from: old).contacts)
        var record = contact(); XCTAssertTrue(WatchContactMessaging.eligible(record))
        record.peerKeyChanged = true; XCTAssertFalse(WatchContactMessaging.eligible(record))
        record.peerKeyChanged = false; record.isAuthorityBridge = true; XCTAssertFalse(WatchContactMessaging.eligible(record))
    }
    func testContactRelayKeepsOriginalIDAndNeverReplaysAfterReopen() async throws {
        await SOSChatStore.shared.waitUntilLoaded()
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("watch.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = SOSRecoveryStore(url: url), record = contact()
        let command = WatchCommand(action: .contactReply, peerID: record.id.uuidString, reply: .safe)
        var effects = 0
        let lookup: (UUID) -> ContactRecord? = { $0 == record.id ? record : nil }
        let relay: (ContactRecord, String, String, Date) async -> Bool = { recipient, id, text, _ in
            effects += 1; XCTAssertEqual(recipient.id, record.id); XCTAssertEqual(id, command.id); XCTAssertFalse(text.isEmpty)
            XCTAssertEqual(store.snapshot.replies.first?.result, "uncertain")
            return true
        }
        let first = await WatchContactMessaging.send(command, store: store, lookup: lookup, relay: relay)
        XCTAssertEqual(first, "accepted")
        let reopened = SOSRecoveryStore(url: url)
        let duplicate = await WatchContactMessaging.send(command, store: reopened, lookup: { _ in nil }, relay: relay)
        XCTAssertEqual(duplicate, "accepted"); XCTAssertEqual(effects, 1)
        SOSChatStore.shared.reconcileWatchReplies(reopened.snapshot.replies)
        XCTAssertEqual(SOSChatStore.shared.messages(for: record.id).filter { $0.transportMessageId == command.id }.count, 1)
        XCTAssertFalse(SOSChatStore.shared.pendingOutgoingTextMessages().contains { $0.transportMessageId == command.id })
        XCTAssertNil(reopened.snapshot.session)
        var wrongRecipient = command; wrongRecipient.peerID = UUID().uuidString
        let changed = await WatchContactMessaging.send(wrongRecipient, store: reopened, lookup: lookup, relay: relay)
        XCTAssertEqual(changed, "stateChanged"); XCTAssertEqual(effects, 1)
    }
    func testRemovedOrChangedContactsCannotSendAndUncertainReservationDoesNotReplay() async throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString).appendingPathComponent("watch.json")
        defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
        let store = SOSRecoveryStore(url: url); var record = contact()
        let command = WatchCommand(action: .contactReply, peerID: record.id.uuidString, reply: .needHelp)
        var effects = 0
        let relay: (ContactRecord, String, String, Date) async -> Bool = { _, _, _, _ in effects += 1; return false }
        let missing = await WatchContactMessaging.send(command, store: store, lookup: { _ in nil }, relay: relay)
        XCTAssertEqual(missing, "noPeer")
        record.peerKeyChanged = true
        let changed = await WatchContactMessaging.send(command, store: store, lookup: { _ in record }, relay: relay)
        XCTAssertEqual(changed, "noPeer"); XCTAssertEqual(effects, 0); XCTAssertTrue(store.snapshot.replies.isEmpty)
        try store.reserve(id: command.id, peer: record.id.uuidString, text: "Yardıma ihtiyacım var")
        let reopened = SOSRecoveryStore(url: url)
        let uncertain = await WatchContactMessaging.send(command, store: reopened, lookup: { _ in record }, relay: relay)
        XCTAssertEqual(uncertain, "uncertain"); XCTAssertEqual(effects, 0)
    }
}
