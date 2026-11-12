import XCTest
@testable import Crisis_Connect

final class TrafficBudgetTests: XCTestCase {
    func testEssentialTrafficAndExplicitFeedRefreshSurviveConservation() {
        for work in [NetworkWork.message,.receipt,.sos] { XCTAssertTrue(TrafficBudget.permits(work,saving:true)) }
        for work in [NetworkWork.avatar,.mapDownload,.telemetry] { XCTAssertFalse(TrafficBudget.permits(work,saving:true,userInitiated:true)) }
        XCTAssertFalse(TrafficBudget.permits(.feedRefresh,saving:true))
        XCTAssertTrue(TrafficBudget.permits(.feedRefresh,saving:true,userInitiated:true))
    }
    func testConnectionObservationsNeverInferSafety() {
        XCTAssertEqual(TrafficBudget.presence(online:false,emergency:false,saving:true),.offline)
        XCTAssertEqual(TrafficBudget.presence(online:false,emergency:true,saving:true),.sos)
        XCTAssertEqual(TrafficBudget.presence(online:true,emergency:false,saving:true),.limited)
        XCTAssertEqual(TrafficBudget.presence(online:true,emergency:false,saving:false),.normal)
    }
    func testReceiptBatchesDrainLongIdsAndKeepConcurrentAdditions() throws {
        var batches = try ReceiptBatches.enqueue([],ReceiptBatch(peer:"a",template:204,ids:(0...30).map { String(repeating:"a",count:124) + String($0) }))
        var seen = Set<String>()
        while let next = ReceiptBatches.next(batches) {
            let data = try JSONSerialization.data(withJSONObject:["ids":next.ids])
            XCTAssertLessThanOrEqual(data.count,1024)
            XCTAssertLessThanOrEqual(next.ids.count,6)
            seen.formUnion(next.ids)
            batches = ReceiptBatches.confirm(batches,next)
        }
        XCTAssertEqual(seen.count,31)
        batches = try ReceiptBatches.enqueue([],ReceiptBatch(peer:"a",template:204,ids:["old"]))
        let snapshot = try XCTUnwrap(ReceiptBatches.next(batches))
        batches = try ReceiptBatches.enqueue(batches,ReceiptBatch(peer:"a",template:204,ids:["new"]))
        batches = try ReceiptBatches.enqueue(batches,ReceiptBatch(peer:"b",template:205,ids:["old"]))
        let remaining = ReceiptBatches.confirm(batches,snapshot)
        XCTAssertEqual(remaining.first?.ids,["new"])
        XCTAssertEqual(remaining.count,2)
    }
    func testReceiptJournalSurvivesRestartAndIsAccountIsolated() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at:folder) }
        let store = ReceiptJournal(directory:folder)
        try store.enqueue(uid:"account-a",batch:ReceiptBatch(peer:"peer",template:204,ids:["one"]))
        let reopened = ReceiptJournal(directory:folder)
        XCTAssertEqual(try reopened.next(uid:"account-a")?.ids,["one"])
        XCTAssertNil(try reopened.next(uid:"account-b"))
    }
    func testReceiptJournalRejectsInvalidIdentifiers() {
        for id in ["../bad","ğ",String(repeating:"a",count:129)] {
            XCTAssertThrowsError(try ReceiptBatches.enqueue([],ReceiptBatch(peer:"a",template:204,ids:[id])))
        }
    }
}
