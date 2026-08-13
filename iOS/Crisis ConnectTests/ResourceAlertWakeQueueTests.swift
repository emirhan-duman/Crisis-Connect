import Foundation
import XCTest
@testable import Crisis_Connect

private final class MemoryWakeQueueStore: ResourceAlertWakeQueuePersisting, @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?

    func load() throws -> Data? {
        lock.lock()
        defer { lock.unlock() }
        return data
    }

    func save(_ data: Data?) throws {
        lock.lock()
        defer { lock.unlock() }
        self.data = data
    }
}

final class ResourceAlertWakeQueueTests: XCTestCase {
    private let payload = ResourceAlertWakePayload(
        panelId: "afad",
        attemptId: "attempt-device-1",
        receiptNonce: "11111111-1111-4111-8111-111111111111"
    )

    func testPendingAckSurvivesRestartAndUsesBoundedBackoff() async throws {
        let storage = MemoryWakeQueueStore()
        let first = ResourceAlertWakeQueue(storage: storage)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let key = try await first.enqueue(payload: payload, recipientUid: "user-1", now: start)
        let claimed = try await first.claim(key: key, recipientUid: "user-1", now: start)
        XCTAssertEqual(claimed?.attemptCount, 1)

        let restarted = ResourceAlertWakeQueue(storage: storage)
        let tooEarly = try await restarted.claim(
            key: key,
            recipientUid: "user-1",
            now: start.addingTimeInterval(29)
        )
        XCTAssertNil(tooEarly)
        let retried = try await restarted.claim(
            key: key,
            recipientUid: "user-1",
            now: start.addingTimeInterval(30)
        )
        XCTAssertEqual(retried?.attemptCount, 2)
        try await restarted.complete(key: key, recipientUid: "user-1", now: start.addingTimeInterval(31))
        let hasPendingAfterCompletion = try await restarted.hasPending(
            recipientUid: "user-1",
            now: start.addingTimeInterval(31)
        )
        XCTAssertFalse(hasPendingAfterCompletion)
    }

    func testDedupeAccountIsolationCapacityAndExpiry() async throws {
        let storage = MemoryWakeQueueStore()
        let queue = ResourceAlertWakeQueue(storage: storage)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        _ = try await queue.enqueue(payload: payload, recipientUid: "user-1", now: start)
        _ = try await queue.enqueue(payload: payload, recipientUid: "user-1", now: start)
        let firstClaim = try await queue.claim(recipientUid: "user-1", now: start)
        let duplicateClaim = try await queue.claim(recipientUid: "user-1", now: start)
        XCTAssertNotNil(firstClaim)
        XCTAssertNil(duplicateClaim)

        let secondUserPayload = ResourceAlertWakePayload(
            panelId: "afad",
            attemptId: "attempt-device-2",
            receiptNonce: "22222222-2222-4222-8222-222222222222"
        )
        _ = try await queue.enqueue(payload: secondUserPayload, recipientUid: "user-2", now: start)
        let secondUserPending = try await queue.hasPending(recipientUid: "user-2", now: start)
        let expired = try await queue.hasPending(
            recipientUid: "user-2",
            now: start.addingTimeInterval(ResourceAlertWakeQueue.retention + 1)
        )
        XCTAssertTrue(secondUserPending)
        XCTAssertFalse(expired)
    }

    func testCapacityRejectsNewReceiptWithoutEvictingOldest() async throws {
        let storage = MemoryWakeQueueStore()
        let queue = ResourceAlertWakeQueue(storage: storage)
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        for index in 0..<ResourceAlertWakeQueue.maximumEntries {
            let payload = ResourceAlertWakePayload(
                panelId: "afad",
                attemptId: "attempt-\(index)",
                receiptNonce: String(format: "%032d", index)
            )
            _ = try await queue.enqueue(
                payload: payload,
                recipientUid: "user-1",
                now: start.addingTimeInterval(Double(index))
            )
        }
        let overflow = ResourceAlertWakePayload(
            panelId: "afad",
            attemptId: "attempt-overflow",
            receiptNonce: "99999999999999999999999999999999"
        )
        do {
            _ = try await queue.enqueue(
                payload: overflow,
                recipientUid: "user-1",
                now: start.addingTimeInterval(100)
            )
            XCTFail("Expected capacity error")
        } catch ResourceAlertWakeQueueError.capacity {
            // Expected: preserving earlier acknowledgements is safer than silent eviction.
        }
        let oldest = try await queue.claim(
            key: "attempt-0.00000000000000000000000000000000",
            recipientUid: "user-1",
            now: start.addingTimeInterval(100)
        )
        XCTAssertNotNil(oldest)
    }
}
