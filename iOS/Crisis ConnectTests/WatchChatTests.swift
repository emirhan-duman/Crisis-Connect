import XCTest
@testable import Crisis_Connect
@MainActor final class WatchChatTests: XCTestCase {
 func testProjectionDeduplicatesAndPreservesReceipt() async throws {
  let store=SOSChatStore.shared
  for _ in 0..<100 { if store.watchProjectionReady { break };try await Task.sleep(for:.milliseconds(20)) }
  XCTAssertTrue(store.watchProjectionReady)
  let peer=UUID();let id=UUID().uuidString
  store.ensureSession(id:peer,displayName:"Test ekibi",role:.fieldTeam,isVerified:true)
  let reserved=SOSRecoveryStore.Reply(id:id,peer:peer.uuidString,text:"İyiyim",result:"uncertain",createdAt:Date(timeIntervalSince1970:1000))
  store.reconcileWatchReplies([reserved]);store.reconcileWatchReplies([reserved])
  XCTAssertEqual(store.messages(for:peer).count,1)
  XCTAssertEqual(store.session(for:peer)?.role,.fieldTeam)
  XCTAssertTrue(store.session(for:peer)?.isVerified == true)
  XCTAssertEqual(store.messages(for:peer).first?.status,.failed)
  var accepted=reserved;accepted.result="accepted";store.reconcileWatchReplies([accepted])
  XCTAssertEqual(store.messages(for:peer).first?.status,.sent)
  XCTAssertEqual(store.messages(for:peer).first?.timestamp,reserved.createdAt)
  store.markDelivered(sessionId:peer,transportMessageIds:[id]);store.reconcileWatchReplies([reserved])
  XCTAssertEqual(store.messages(for:peer).first?.status,.delivered)
  XCTAssertFalse(store.pendingOutgoingTextMessages().contains {$0.transportMessageId==id})
  let encoded=try JSONEncoder().encode(store.messages(for:peer).first!)
  XCTAssertEqual(try JSONDecoder().decode(SOSChatMessage.self,from:encoded).watchReplyResult,"accepted")
 }
}
