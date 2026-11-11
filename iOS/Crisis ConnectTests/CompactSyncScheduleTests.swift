import XCTest
@testable import Crisis_Connect
final class CompactSyncScheduleTests: XCTestCase {
 func testForegroundResumeCannotBurst() { var s=CompactSyncSchedule();XCTAssertTrue(s.reserve(now:0));XCTAssertFalse(s.reserve(now:29.999));XCTAssertTrue(s.reserve(now:30)) }
 func testPaginationAndPendingAcknowledgementsContinueWithinBudget() { var s=CompactSyncSchedule();_ = s.reserve(now:0);s.succeeded(now:0.5,moreWork:true,jitter:60);XCTAssertFalse(s.reserve(now:30.499));XCTAssertTrue(s.reserve(now:30.5)) }
 func testIdlePollUsesJitterInsteadOfBusyLoop() { var s=CompactSyncSchedule();_ = s.reserve(now:0);s.succeeded(now:1,moreWork:false,jitter:60);XCTAssertFalse(s.reserve(now:180.999));XCTAssertTrue(s.reserve(now:181)) }
 func testFailureBackoffIsBoundedAndSuccessResetsIt() { var s=CompactSyncSchedule();var now:Double=0;for _ in 0..<8 { XCTAssertTrue(s.reserve(now:now));s.failed(now:now,jitter:10000);XCTAssertFalse(s.reserve(now:now+29.999));now += 300 };s.succeeded(now:now,moreWork:true,jitter:0);XCTAssertTrue(s.reserve(now:now+30));s.failed(now:now+30,jitter:0);XCTAssertTrue(s.reserve(now:now+60)) }
 func testLongSuspensionAllowsOneRequestThenEnforcesBudgetAgain() { var s=CompactSyncSchedule();_ = s.reserve(now:0);s.succeeded(now:0,moreWork:false,jitter:0);XCTAssertTrue(s.reserve(now:3600));XCTAssertFalse(s.reserve(now:3600.001)) }
}
final class CompactAckFileStoreTests: XCTestCase {
 func testRestartPreservesAcknowledgementsAndIsolatesAccounts() throws {
  let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer { try? FileManager.default.removeItem(at:root) }
  let first=CompactAckFileStore(directory:root);try first.enqueue(uid:"a",id:"one");try first.enqueue(uid:"b",id:"two")
  let restarted=CompactAckFileStore(directory:root);XCTAssertEqual(try restarted.pending(uid:"a"),["one"]);XCTAssertEqual(try restarted.pending(uid:"b"),["two"])
  try restarted.confirm(uid:"a",ids:["one"]);XCTAssertEqual(try CompactAckFileStore(directory:root).pending(uid:"a"),[])
 }
 func testConfirmingSnapshotPreservesNewAcknowledgements() throws {
  let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer { try? FileManager.default.removeItem(at:root) }
  let store=CompactAckFileStore(directory:root);try store.enqueue(uid:"a",id:"one");let snapshot=try store.pending(uid:"a");try store.enqueue(uid:"a",id:"two");try store.confirm(uid:"a",ids:snapshot);XCTAssertEqual(try store.pending(uid:"a"),["two"])
 }
 func testBatchesDrainBeyondOneHundredWithoutDuplicateIds() throws {
  let root=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer { try? FileManager.default.removeItem(at:root) }
  let store=CompactAckFileStore(directory:root);try store.migrate(uid:"a",ids:(0..<205).map {"id-\($0)"});try store.enqueue(uid:"a",id:"id-0")
  XCTAssertEqual(try store.pending(uid:"a").count,100);try store.confirm(uid:"a",ids:store.pending(uid:"a"));XCTAssertEqual(try store.pending(uid:"a").count,100);try store.confirm(uid:"a",ids:store.pending(uid:"a"));XCTAssertEqual(try store.pending(uid:"a").count,5)
 }
}
