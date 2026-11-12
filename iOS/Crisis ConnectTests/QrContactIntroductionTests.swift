import XCTest
import CryptoKit
@testable import Crisis_Connect

@MainActor
final class QrContactIntroductionTests: XCTestCase {
    private let bytes = Data((1...32).map { UInt8($0) })
    private var qrKey: String { bytes.base64EncodedString() }
    private var payload: [String: Any] {
        ["rsc": "SCAN01", "name": "Scanner", "pk": "scanner-key",
         "binding": ContactIdentityBinding.sign(key: SymmetricKey(data: bytes), uid: "scanner",
             sessionCode: "SCAN01", publicKey: "scanner-key")]
    }
    private func accept(_ payload: [String: Any]? = nil, existing: ContactRecord? = nil,
                        activeKey: String? = nil, closed: Bool = false,
                        sender: String = "scanner", publicKey: String = "scanner-key") -> ContactRecord? {
        QrContactIntroduction.resolve(sender: sender, owner: "display-owner", publicKey: publicKey,
            payload: payload ?? self.payload, existing: existing, activeQrKeyBase64: closed ? nil : (activeKey ?? qrKey),
            fallbackName: "Unknown")
    }
    private func ble(key: String? = nil) -> ContactRecord {
        ContactRecord(id: UUID(), name: "Custom name", broadcastId: "", sessionCode: "ble:peer",
            isVerified: true, verifiedIdentityKey: "peer-device", verifiedAt: Date(), remoteSessionCode: "SCAN01",
            aesKeyBase64: key ?? qrKey, preferredTransport: .bleGatt, createdAt: Date(), lastUpdated: Date())
    }

    func testAndroidAndIosUseTheSameIdentityProofBytes() {
        let key = SymmetricKey(data: Data((0..<32).map { UInt8($0) }))
        XCTAssertEqual(ContactIdentityBinding.sign(key: key, uid: "uid-ä", sessionCode: "ABC123", publicKey: "public-key"),
            "FbhJmHAiLEAITG2CBkZTI3SW8B+fyk7diKAaJipi9pw=")
    }
    func testDisplayerCreatesAnInternetContactWithoutAnyMessageOrPreviousRecord() throws {
        let record = try XCTUnwrap(accept())
        XCTAssertEqual(record.name, "Scanner")
        XCTAssertEqual(record.peerUid, "scanner")
        XCTAssertEqual(record.remoteSessionCode, "SCAN01")
        XCTAssertEqual(record.sessionCode, InternetConversation.pairId("scanner", "display-owner"))
        XCTAssertTrue(record.supportsInternet)
        XCTAssertFalse(record.isVerified)
        XCTAssertEqual(record.offlineKeyVersion, 1)
        XCTAssertTrue(record.aesKeyBase64.isEmpty)
    }
    func testUnknownSenderNeedsTheActiveQrCapability() {
        var bad = payload; bad["binding"] = ""
        XCTAssertNil(accept(bad))
        XCTAssertNil(accept(closed: true))
        XCTAssertNil(accept(activeKey: Data(repeating: 0, count: 32).base64EncodedString()))
    }
    func testProofRejectsIdentityAndSessionSubstitution() {
        XCTAssertNil(accept(sender: "attacker"))
        XCTAssertNil(accept(publicKey: "attacker-key"))
        var bad = payload; bad["rsc"] = "OTHER1"
        XCTAssertNil(accept(bad))
    }
    func testExistingOfflineIdentityAndCustomNameArePreserved() throws {
        let previous = ble()
        let record = try XCTUnwrap(accept(existing: previous, closed: true))
        XCTAssertEqual(record.id, previous.id)
        XCTAssertEqual(record.name, "Custom name")
        XCTAssertEqual(record.verifiedIdentityKey, previous.verifiedIdentityKey)
        XCTAssertEqual(record.aesKeyBase64, previous.aesKeyBase64)
        XCTAssertTrue(record.isVerified)
        XCTAssertEqual(record.peerUid, "scanner")
    }
    func testReplayAfterQrClosesReusesThePinnedRecord() throws {
        let original = try XCTUnwrap(accept())
        XCTAssertEqual(accept(existing: original, closed: true), original)
    }
    func testQrCannotReplaceAnExistingAccountOrPublicKey() throws {
        var previous = try XCTUnwrap(accept()); previous.peerUid = "another-person"
        XCTAssertNil(accept(existing: previous))
        previous.peerUid = "scanner"; previous.peerPublicKey = "another-key"
        XCTAssertNil(accept(existing: previous))
    }
    func testLiveQrCannotRebindAnUnrelatedOlderBleContactBySessionCode() {
        XCTAssertNil(accept(existing: ble(key: Data(repeating: 0, count: 32).base64EncodedString())))
    }
    func testSelfIntroductionIsRejected() { XCTAssertNil(accept(sender: "display-owner")) }
    func testMissingNameUsesFallback() {
        var unnamed = payload; unnamed["name"] = " "
        XCTAssertEqual(accept(unnamed)?.name, "Unknown")
    }
    func testChildStatusComesFromTheAuthenticatedDirectory() throws {
        let record = QrContactIntroduction.resolve(sender: "scanner", owner: "display-owner", publicKey: "scanner-key",
            payload: payload, existing: nil, activeQrKeyBase64: qrKey, fallbackName: "Unknown", knownPeerIsChild: true)
        XCTAssertEqual(try XCTUnwrap(record).peerIsChild, true)
    }

    func testReciprocalContactSurvivesStoreRecreationWithoutSendingAMessage() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("contacts.enc")
        let store = ContactStore(persistenceURL: url)
        await store.waitUntilLoaded()
        let first = try XCTUnwrap(store.acceptQrIntroduction(sender: "scanner", owner: "display-owner",
            publicKey: "scanner-key", payload: payload, activeQrKeyBase64: qrKey, fallbackName: "Unknown"))
        try await store.flushDurably()
        let restored = ContactStore(persistenceURL: url)
        await restored.waitUntilLoaded()
        let replay = try XCTUnwrap(restored.acceptQrIntroduction(sender: "scanner", owner: "display-owner",
            publicKey: "scanner-key", payload: payload, activeQrKeyBase64: nil, fallbackName: "Unknown"))
        try await restored.flushDurably()
        XCTAssertEqual(first.id, replay.id)
        let records = try JSONDecoder().decode([ContactRecord].self, from: LocalEncryptedFileStore.read(from: url).data)
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.peerUid, "scanner")
    }
    func testInvalidIntroductionDoesNotCreateAPersistedRow() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("contacts.enc")
        let store = ContactStore(persistenceURL: url)
        await store.waitUntilLoaded()
        XCTAssertNil(store.acceptQrIntroduction(sender: "attacker", owner: "display-owner", publicKey: "scanner-key",
            payload: payload, activeQrKeyBase64: qrKey, fallbackName: "Unknown"))
        try await store.flushDurably()
        let records = try JSONDecoder().decode([ContactRecord].self, from: LocalEncryptedFileStore.read(from: url).data)
        XCTAssertTrue(records.isEmpty)
    }
}
