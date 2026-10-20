import XCTest
import Foundation
import CryptoKit
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import FirebaseAppCheck
@testable import Crisis_Connect

final class HighAvailabilityLiveSdkTests: XCTestCase {
 func testEncryptedNamedDatabaseAndLegacyCompatibility() async throws {
  let url=URL(string:"http://127.0.0.1:31234/ios")!
  func fixture(_ body:[String:Any]? = nil) async throws -> [String:Any] {
   var request=URLRequest(url:url);request.timeoutInterval=10
   if let body {request.httpMethod="POST";request.httpBody=try JSONSerialization.data(withJSONObject:body)}
   let (data,response)=try await URLSession.shared.data(for:request)
   guard (response as? HTTPURLResponse)?.statusCode==200 else {throw URLError(.badServerResponse)}
   return try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
  }
  let config:[String:Any]
  do {config=try await fixture()} catch {throw XCTSkip("Explicit localhost HA canary fixture required")}
  let run=try XCTUnwrap(config["runId"] as? String)
  let previous=ProcessInfo.processInfo.environment["FIRAAppCheckDebugToken"]
  setenv("FIRAAppCheckDebugToken",try XCTUnwrap(config["debugToken"] as? String),1)
  AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
  defer {if let previous {setenv("FIRAAppCheckDebugToken",previous,1)} else {unsetenv("FIRAAppCheckDebugToken")};AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory())}
  let options=try XCTUnwrap(FirebaseApp.app()?.options)
  for i in 0...1 {FirebaseApp.configure(name:"ha-ios-\(run)-\(i)",options:options)}
  let apps=try (0...1).map{try XCTUnwrap(FirebaseApp.app(name:"ha-ios-\(run)-\($0)"))}
  let auths=apps.map{Auth.auth(app:$0)}
  let stores=apps.map{Firestore.firestore(app:$0,database:MessagingDatabaseTarget.databaseID)}
  func client(_ i:Int,_ region:String)->InternetMessagingClient {
   let calls=RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{region}),highAvailability:true){r,op,payload in
    let callable=Functions.functions(app:apps[i],region:r).httpsCallable(r==RegionalRoutePolicy.europe ? "messagingGatewayHaEurope":"messagingGatewayHaUS")
    callable.timeoutInterval=45
    return try await callable.call(["operation":op,"payload":payload]).data
   }
   return InternetMessagingClient(functions:Functions.functions(app:apps[i],region:"us-central1"),auth:auths[i],firestore:stores[i],regionalCalls:calls)
  }
  func cleanup() async {
   for i in 0...1 {
    _ = try? await Functions.functions(app:apps[i],region:"us-central1").httpsCallable("deleteAccountAndData").call()
    try? await auths[i].currentUser?.delete()
    try? await stores[i].terminate()
    await withCheckedContinuation { continuation in apps[i].delete{_ in continuation.resume()} }
   }
  }
  do {
   for app in apps {_ = try await XCTUnwrap(AppCheck.appCheck(app:app)).token(forcingRefresh:true)}
   let senderUid=try await auths[0].signInAnonymously().user.uid
   let recipientUid=try await auths[1].signInAnonymously().user.uid
   _ = try await fixture(["uids":[senderUid,recipientUid],"haUids":[senderUid,recipientUid],"haReady":false,"haStatus":"waitingForCanary"])
   let deadline=Date().addingTimeInterval(300)
   while try await fixture()["haReady"] as? Bool != true {
    guard Date()<deadline else {throw URLError(.timedOut)}
    try await Task.sleep(nanoseconds:1_000_000_000)
   }
   let senderKey=P256.KeyAgreement.PrivateKey(),recipientKey=P256.KeyAgreement.PrivateKey()
   let sender=client(0,RegionalRoutePolicy.europe),recipient=client(1,RegionalRoutePolicy.us)
   try await sender.publishIdentityKey(publicKeyBase64:senderKey.publicKey.derRepresentation.base64EncodedString(),discoverable:false)
   try await recipient.publishIdentityKey(publicKeyBase64:recipientKey.publicKey.derRepresentation.base64EncodedString(),discoverable:false)
   let peer=try await sender.lookupIdentityKey(uid:recipientUid)
   XCTAssertEqual(peer.publicKeyBase64,recipientKey.publicKey.derRepresentation.base64EncodedString())
   let conversation=InternetConversation.pairId(senderUid,recipientUid),id="ha-ios-\(run)"
   let sealed=try InternetE2eEnvelope.seal(content:InternetMessageContent(templateCode:0,text:"HA native iOS canary"),recipientPublicKey:recipientKey.publicKey,senderPrivateKey:senderKey,senderUid:senderUid,recipientUid:recipientUid,conversationId:conversation)
   let envelope=OutgoingInternetEnvelope(messageId:id,conversationId:conversation,recipientUid:recipientUid,priority:"normal",createdAtMs:Int(Date().timeIntervalSince1970*1000),ttlMs:300_000,sealed:sealed)
   try await sender.writeMessageDoc(envelope)
   let row=try await stores[1].collection("messages").document(id).getDocument(source:.server)
   XCTAssertTrue(row.exists)
   let received=SealedInternetMessage(ephemeralPublicKey:try XCTUnwrap(row.get("ephemeralPubKey") as? String),nonce:try XCTUnwrap(row.get("nonce") as? String),ciphertext:try XCTUnwrap(row.get("ciphertext") as? String))
   let opened=try InternetE2eEnvelope.open(sealed:received,recipientPrivateKey:recipientKey,senderStaticPublicKey:senderKey.publicKey,senderUid:senderUid,recipientUid:recipientUid,conversationId:conversation)
   XCTAssertEqual(opened.text,"HA native iOS canary")
   let legacy=Functions.functions(app:apps[1],region:"us-central1")
   let result=try await legacy.httpsCallable("compactInbox").call(["installationId":"ha-ios-test","saving":false])
   let page=try XCTUnwrap(result.data as? [String:Any])
   XCTAssertTrue((page["messages"] as? [[String:Any]])?.contains{$0["messageId"] as? String == id} == true)
   _ = try await legacy.httpsCallable("acknowledgeMessage").call(["messageId":id])
   try await client(0,RegionalRoutePolicy.us).writeMessageDoc(envelope)
   let after=try await stores[1].collection("messages").whereField("recipientUid",isEqualTo:recipientUid).getDocuments(source:.server)
   XCTAssertFalse(after.documents.contains{$0.documentID==id})
   _ = try await fixture(["haStatus":"passed","haChecks":["eur3-sdk-read","encrypted-roundtrip","eu-us-same-message","legacy-inbox-ack","no-resurrection"]])
  } catch {
   _ = try? await fixture(["haStatus":"failed","haErrorType":String(describing:type(of:error))])
   await cleanup();throw error
  }
  await cleanup()
 }
}
