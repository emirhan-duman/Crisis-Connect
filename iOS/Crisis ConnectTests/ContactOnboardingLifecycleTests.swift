import XCTest
import Combine
@testable import Crisis_Connect

@MainActor
final class ContactOnboardingLifecycleTests: XCTestCase {
    private final class Driver: ContactVerificationDriving {
        let progress = CurrentValueSubject<ContactVerificationProgress, Never>(.idle)
        var progressStream: AnyPublisher<ContactVerificationProgress, Never> { progress.eraseToAnyPublisher() }
        var completions: [(ContactVerificationOutcome) -> Void] = []
        var cancels = 0
        func verify(payload: ContactQRPayload, localDisplayName: String, timeout: TimeInterval,
                    completion: @escaping (ContactVerificationOutcome) -> Void) { completions.append(completion) }
        func cancel() { cancels += 1 }
    }
    private final class Persistence: ContactOnboardingPersisting {
        let saved = SavedOnboardingContact(id: UUID(), name: "Peer", alreadyRegistered: false)
        var writes = 0
        var flushFails = false
        func verified(_ result: ContactVerificationResult, payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact {
            writes += 1
            return saved
        }
        func internet(_ payload: ContactQRPayload, fallbackName: String) -> SavedOnboardingContact? { nil }
        func existing(id: UUID) -> SavedOnboardingContact? { writes > 0 && id == saved.id ? saved : nil }
        func flush() async throws { if flushFails { throw TestFailure.disk } }
    }
    private enum TestFailure: Error { case disk }
    private final class Journal: ContactPairingJournaling {
        var data: Data?
        var writeFails = false
        func read() throws -> ContactPairingCheckpoint? {
            try data.map { try JSONDecoder().decode(ContactPairingCheckpoint.self, from: $0) }
        }
        func write(_ checkpoint: ContactPairingCheckpoint) throws {
            if writeFails { throw TestFailure.disk }
            data = try JSONEncoder().encode(checkpoint)
        }
        func clear() { data = nil }
    }
    private final class Lease: ContactOnboardingBackgroundLease {
        var expiration: (() -> Void)?
        var active = false
        func begin(onExpiration: @escaping () -> Void) { expiration = onExpiration; active = true }
        func end() { active = false }
    }
    private let payload = ContactQRPayload(code: "peer", key: Data(repeating: 7, count: 32).base64EncodedString(), platform: "ios", name: "Peer")
    private let verified = ContactVerificationResult(shareId: "share", sessionCode: "peer", displayName: "Peer", avatarBase64: nil,
                                                     platform: "ios", remoteDeviceId: "device", lastKnownBleAddress: "address")
    private func start(_ owner: ContactOnboardingCoordinator) {
        owner.start(payload: payload, fallbackName: "Peer", source: .qr, localSessionCode: "me", localDisplayName: "Me")
    }
    private func terminal(_ owner: ContactOnboardingCoordinator, action: () -> Void) async {
        let done = expectation(description: "Operation reaches a terminal outcome")
        var subscription: AnyCancellable? = owner.$outcome.compactMap { $0 }.prefix(1).sink { _ in done.fulfill() }
        action()
        await fulfillment(of: [done], timeout: 2)
        subscription?.cancel()
        subscription = nil
    }

    func testLegacyQrNeverStartsRadioOrReplaysOldVerifiedCheckpoint() async throws {
        let driver = Driver(), store = Persistence(), journal = Journal()
        let legacy = ContactQRPayload(offlineProtocolVersion: 1, code: "peer", key: payload.key, platform: "android")
        let owner = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        owner.start(payload: legacy, fallbackName: "Peer", source: .qr, localSessionCode: "me", localDisplayName: "Me")
        XCTAssertTrue(driver.completions.isEmpty); XCTAssertEqual(store.writes, 0)
        var pending = ContactPairingCheckpoint(payload: legacy, fallbackName: "Peer", manual: false, owner: "me:", createdAt: Date())
        pending.verified = verified
        try journal.write(pending)
        let reopened = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        XCTAssertTrue(driver.completions.isEmpty); XCTAssertEqual(store.writes, 0)
    }

    func testDetachedScreenDoesNotCancelAndReattachedScreenGetsSavedResult() async {
        let driver = Driver(), store = Persistence(), lease = Lease()
        let owner = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: lease, finishDelay: .zero)
        var screen: AnyCancellable? = owner.$stage.sink { _ in }
        start(owner)
        screen?.cancel(); screen = nil // Navigation pop / destroyed UI observer.
        XCTAssertTrue(owner.isRunning)
        XCTAssertTrue(lease.active)
        XCTAssertEqual(driver.cancels, 0)
        await terminal(owner) { driver.completions[0](.verified(verified)) }
        XCTAssertEqual(store.writes, 1)
        XCTAssertFalse(lease.active)
        XCTAssertEqual(owner.consumeOutcome(), .saved(store.saved.id))
        XCTAssertNil(owner.consumeOutcome())
    }

    func testDuplicateStartsAndDuplicateCompletionsCannotWriteTwice() async {
        let driver = Driver(), store = Persistence(), lease = Lease()
        let owner = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: lease, finishDelay: .zero)
        start(owner); start(owner)
        XCTAssertEqual(driver.completions.count, 1)
        await terminal(owner) {
            driver.completions[0](.verified(verified)); driver.completions[0](.verified(verified))
        }
        XCTAssertEqual(store.writes, 1)
    }

    func testExplicitCancelRejectsLateCompletionFromOldAttempt() async {
        let driver = Driver(), store = Persistence(), lease = Lease()
        let owner = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: lease, finishDelay: .zero)
        start(owner); owner.cancel(); start(owner)
        await terminal(owner) {
            driver.completions[0](.verified(verified))
            driver.completions[1](.failed(.notFound))
        }
        XCTAssertEqual(store.writes, 0)
        XCTAssertEqual(driver.cancels, 1)
        XCTAssertEqual(owner.consumeOutcome(), .failure(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE"))
    }

    func testBackgroundExpirationEndsLeaseAndCannotSaveFromLateCallback() async {
        let driver = Driver(), store = Persistence(), lease = Lease()
        let owner = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: lease, finishDelay: .zero)
        start(owner)
        await terminal(owner) { lease.expiration?() }
        driver.completions[0](.verified(verified))
        await Task.yield()
        XCTAssertFalse(owner.isRunning)
        XCTAssertFalse(lease.active)
        XCTAssertEqual(driver.cancels, 1)
        XCTAssertEqual(store.writes, 0)
    }

    func testRecreatedOwnerResumesPendingIntentOnceWithoutRescanning() async throws {
        let journal = Journal(), store = Persistence()
        var first: ContactOnboardingCoordinator? = ContactOnboardingCoordinator(verifier: Driver(), persistence: store,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        start(first!)
        XCTAssertNotNil(try journal.read())
        first = nil // A new app process has no coordinator, Activity, or old Bluetooth connection.
        let driver = Driver()
        let reopened = ContactOnboardingCoordinator(verifier: driver, persistence: store,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        XCTAssertEqual(driver.completions.count, 1)
        XCTAssertEqual(try journal.read()?.recoveries, 1)
        await terminal(reopened) { driver.completions[0](.verified(verified)) }
        XCTAssertEqual(store.writes, 1)
        XCTAssertEqual(reopened.consumeOutcome(), .saved(store.saved.id))
        XCTAssertNil(try journal.read())
    }

    func testKillAfterContactCommitRestoresReceiptWithoutWritingAgain() async throws {
        let journal = Journal(), store = Persistence(), driver = Driver()
        var first: ContactOnboardingCoordinator? = ContactOnboardingCoordinator(verifier: driver, persistence: store,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        start(first!)
        await terminal(first!) { driver.completions[0](.verified(verified)) }
        first = nil // Success was not yet consumed by the screen.
        let nextDriver = Driver()
        let reopened = ContactOnboardingCoordinator(verifier: nextDriver, persistence: store,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        let done = expectation(description: "Restored durable receipt")
        let subscription = reopened.$outcome.compactMap { $0 }.prefix(1).sink { _ in done.fulfill() }
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        await fulfillment(of: [done], timeout: 2)
        subscription.cancel()
        XCTAssertEqual(store.writes, 1)
        XCTAssertEqual(nextDriver.completions.count, 0)
        XCTAssertEqual(reopened.consumeOutcome(), .saved(store.saved.id))
    }

    func testVerifiedCheckpointReplaysAfterContactDiskWriteWasInterrupted() async throws {
        let journal = Journal(), driver = Driver(), firstStore = Persistence()
        firstStore.flushFails = true
        var first: ContactOnboardingCoordinator? = ContactOnboardingCoordinator(verifier: driver, persistence: firstStore,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        start(first!)
        await terminal(first!) { driver.completions[0](.verified(verified)) }
        XCTAssertNotNil(try journal.read()?.verified)
        XCTAssertNil(try journal.read()?.savedID)
        first = nil
        let nextDriver = Driver(), diskStore = Persistence()
        let reopened = ContactOnboardingCoordinator(verifier: nextDriver, persistence: diskStore,
            backgroundLease: Lease(), finishDelay: .zero, journal: journal)
        let done = expectation(description: "Authenticated result replays without Bluetooth")
        let subscription = reopened.$outcome.compactMap { $0 }.prefix(1).sink { _ in done.fulfill() }
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        await fulfillment(of: [done], timeout: 2)
        subscription.cancel()
        XCTAssertEqual(nextDriver.completions.count, 0)
        XCTAssertEqual(diskStore.writes, 1)
    }

    func testCancelAndDifferentAccountCannotResumePendingPairing() async throws {
        let journal = Journal(), store = Persistence()
        let first = ContactOnboardingCoordinator(verifier: Driver(), persistence: store,
            backgroundLease: Lease(), journal: journal, accountIdentity: { "account-a" })
        start(first)
        let driver = Driver()
        let reopened = ContactOnboardingCoordinator(verifier: driver, persistence: store,
            backgroundLease: Lease(), journal: journal, accountIdentity: { "account-b" })
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        XCTAssertNil(try journal.read())
        XCTAssertTrue(driver.completions.isEmpty)
        first.cancel()
        XCTAssertNil(try journal.read())
    }

    func testExpiredOrRepeatedRecoveryIsBoundedAndDiskFailureCannotStartTransport() async throws {
        let journal = Journal(), driver = Driver(), store = Persistence()
        journal.writeFails = true
        let first = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: Lease(), journal: journal)
        start(first)
        XCTAssertTrue(driver.completions.isEmpty)
        journal.writeFails = false
        let time = Date()
        var checkpoint = ContactPairingCheckpoint(payload: payload, fallbackName: "Peer", manual: false,
            owner: "me:", createdAt: time)
        XCTAssertFalse(checkpoint.canResume(owner: "me:", now: time.addingTimeInterval(301)))
        XCTAssertFalse(checkpoint.canResume(owner: "me:", now: time.addingTimeInterval(-1)))
        checkpoint.recoveries = 3
        XCTAssertFalse(checkpoint.canResume(owner: "me:", now: time))
    }

    func testEncryptedJournalSurvivesRecreationAndIsExcludedFromBackup() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".enc")
        defer { try? FileManager.default.removeItem(at: url) }
        let checkpoint = ContactPairingCheckpoint(payload: payload, fallbackName: "Peer", manual: false,
            owner: "me:", createdAt: Date())
        try EncryptedContactPairingJournal(url: url).write(checkpoint)
        let encrypted = try Data(contentsOf: url)
        XCTAssertNil(encrypted.range(of: Data(payload.key.utf8)))
        XCTAssertEqual(try EncryptedContactPairingJournal(url: url).read()?.payload.key, payload.key)
        XCTAssertEqual(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        try EncryptedContactPairingJournal(url: url).clear()
        XCTAssertNil(try EncryptedContactPairingJournal(url: url).read())
    }

    func testReplayingVerifiedIdentityAcrossStoreRecreationKeepsOneContactAndThread() async throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let url = folder.appendingPathComponent("contacts.enc")
        var first: ContactStore? = ContactStore(persistenceURL: url)
        await first!.waitUntilLoaded()
        func upsert(_ store: ContactStore, share: String) -> ContactRecord {
            store.upsertBleContact(name: "Peer", sessionCode: "ble:recovery-peer", aesKeyBase64: payload.key,
                isVerified: true, verifiedIdentityKey: "recovery-peer", verifiedAt: Date(),
                remoteSessionCode: "remote-code", remotePlatform: .ios, bleShareId: share,
                lastKnownBleAddress: "original-address", remoteDeviceId: "recovery-peer")
        }
        let original = upsert(first!, share: "old-share")
        try await first!.flushDurably()
        first = nil
        var reopened: ContactStore? = ContactStore(persistenceURL: url)
        await reopened!.waitUntilLoaded()
        let replayed = upsert(reopened!, share: "new-share")
        try await reopened!.flushDurably()
        reopened = nil
        let final = ContactStore(persistenceURL: url)
        await final.waitUntilLoaded()
        XCTAssertEqual(replayed.id, original.id)
        XCTAssertEqual(replayed.sessionCode, original.sessionCode)
        XCTAssertEqual(final.contact(for: original.id)?.bleShareId, "NEW-SHARE")
        let decoded = try JSONDecoder().decode([ContactRecord].self, from: LocalEncryptedFileStore.read(from: url).data)
        XCTAssertEqual(decoded.count, 1)
    }

    func testExplicitCancelIsNotRecoveredByANewOwner() async throws {
        let journal = Journal(), driver = Driver(), store = Persistence()
        let first = ContactOnboardingCoordinator(verifier: Driver(), persistence: store, backgroundLease: Lease(), journal: journal)
        start(first); first.cancel()
        let reopened = ContactOnboardingCoordinator(verifier: driver, persistence: store, backgroundLease: Lease(), journal: journal)
        await reopened.resumePending(localSessionCode: "me", localDisplayName: "Me")
        XCTAssertTrue(driver.completions.isEmpty)
        XCTAssertNil(try journal.read())
    }

    func testShareResumeRequiresSameDeviceAccountAndShortDeadline() {
        let now = Date()
        let checkpoint = ContactShareCheckpoint(shareId: "share", sessionCode: "me", displayName: "Me",
            aesKeyBase64: payload.key, account: "a", expiresAt: now.addingTimeInterval(30))
        XCTAssertTrue(checkpoint.canResume(sessionCode: "me", account: "a", now: now))
        XCTAssertFalse(checkpoint.canResume(sessionCode: "me", account: "b", now: now))
        XCTAssertFalse(checkpoint.canResume(sessionCode: "other", account: "a", now: now))
        XCTAssertFalse(checkpoint.canResume(sessionCode: "me", account: "a", now: now.addingTimeInterval(31)))
    }
}
