import XCTest
@testable import Crisis_Connect

@MainActor
final class ContactDiscoveryConsentTests: XCTestCase {
    private func store(owner: String = "alice", fileURL: URL? = nil) -> ContactAccessConsentStore {
        let file = fileURL ?? FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("consent.json")
        addTeardownBlock { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
        return ContactAccessConsentStore(fileURL: file, ownerID: { owner })
    }

    func testOpeningAndRefreshingWithoutConsentDoesNotStartDiscovery() async {
        let model = AddFromContactsViewModel(consent: store())
        await model.load()
        await model.load()
        guard case .idle = model.state else { return XCTFail("Discovery ran without consent") }
        XCTAssertFalse(model.hasDiscoveryConsent)
        XCTAssertTrue(model.deviceContacts.isEmpty)
    }

    func testNearbyConsentCannotAuthorizeInternetUpload() async {
        let model = AddFromContactsViewModel(consent: store())
        model.acceptLocalDisclosure()
        await model.load()
        XCTAssertTrue(model.hasLocalConsent)
        XCTAssertFalse(model.hasDiscoveryConsent)
        guard case .idle = model.state else { return XCTFail("Local consent started discovery") }
    }

    func testNewScreenRemembersConsentAndRevokeUpdatesExistingModel() async {
        let consent = store()
        let previous = AddFromContactsViewModel(consent: consent)
        previous.acceptDiscoveryDisclosure()
        XCTAssertTrue(previous.hasDiscoveryConsent)
        XCTAssertFalse(previous.hasLocalConsent)
        XCTAssertTrue(AddFromContactsViewModel(consent: consent).hasDiscoveryConsent)
        consent.revoke(online: true)
        XCTAssertFalse(previous.hasDiscoveryConsent)
        await previous.load()
        guard case .idle = previous.state else { return XCTFail("Revocation did not block lookup") }
    }

    func testPersistenceAndAccountIsolation() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("consent.json")
        let first = store(fileURL: file)
        XCTAssertTrue(first.accept(online: true))
        XCTAssertTrue(store(fileURL: file).isGranted(online: true))
        XCTAssertFalse(store(owner: "bob", fileURL: file).isGranted(online: true))
        XCTAssertFalse(store(fileURL: file).isGranted(online: false))
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        first.revoke(online: true)
        XCTAssertFalse(store(fileURL: file).isGranted(online: true))
    }

    func testRevokeThenAcceptInvalidatesTheEarlierBatchToken() {
        let consent = store()
        consent.accept(online: true)
        let token = consent.token(online: true)!
        XCTAssertTrue(consent.isCurrent(online: true, token: token))
        consent.revoke(online: true)
        XCTAssertFalse(consent.isCurrent(online: true, token: token))
        consent.accept(online: true)
        XCTAssertFalse(consent.isCurrent(online: true, token: token))
    }

    func testOldDisclosureVersionCannotAuthorizeNewUse() throws {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString).appendingPathComponent("consent.json")
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(["v1:alice:online": "old-consent"]).write(to: file)
        XCTAssertFalse(store(fileURL: file).isGranted(online: true))
    }

    func testNearbyReadWithoutConsentDoesNotAccessContacts() async {
        let model = AddFromContactsViewModel(consent: store())
        await model.loadNearbyContacts()
        XCTAssertTrue(model.deviceContacts.isEmpty)
        guard case .idle = model.state else { return XCTFail("Contact access ran without consent") }
    }
}
