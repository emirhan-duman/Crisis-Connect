import XCTest
import Foundation
import FirebaseCore
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import FirebaseAppCheck
@testable import Crisis_Connect

final class ScheduledCrossPlatformLiveE2eTests: XCTestCase {
 func testAndroidAndIosAutomaticallyReceiveAndBatchAcknowledge() async throws {
  let base="http://127.0.0.1:31234/"
  func get(_ platform:String) async throws -> [String:Any] {
   let (data,_)=try await URLSession.shared.data(from:URL(string:base+platform)!)
   return try XCTUnwrap(JSONSerialization.jsonObject(with:data) as? [String:Any])
  }
  func post(_ data:[String:Any]) async throws {
   var request=URLRequest(url:URL(string:base+"ios")!);request.httpMethod="POST";request.httpBody=try JSONSerialization.data(withJSONObject:data)
   _ = try await URLSession.shared.data(for:request)
  }
  let fixture:[String:Any]
  do {fixture=try await get("ios")} catch {throw XCTSkip("Explicit localhost live fixture required")}
  let run=try XCTUnwrap(fixture["crossRunId"] as? String)
  setenv("FIRAAppCheckDebugToken",try XCTUnwrap(fixture["debugToken"] as? String),1)
  AppCheck.setAppCheckProviderFactory(AppCheckDebugProviderFactory())
  defer {unsetenv("FIRAAppCheckDebugToken");AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory())}
  for i in 0...1 {FirebaseApp.configure(name:"scheduled-ios-\(run)-\(i)",options:try XCTUnwrap(FirebaseApp.app()?.options))}
  let apps=try (0...1).map {try XCTUnwrap(FirebaseApp.app(name:"scheduled-ios-\(run)-\($0)"))}
  let auths=apps.map {Auth.auth(app:$0)}
  var handler:InternetMessageReceiver?
  do {
   for app in apps {_ = try await XCTUnwrap(AppCheck.appCheck(app:app)).token(forcingRefresh:true)}
   let senderUid=try await auths[0].signInAnonymously().user.uid
   let receiverUid=try await auths[1].signInAnonymously().user.uid
   try await post(["uids":[senderUid,receiverUid]])
   let regional = fixture["regionalDelivery"] as? Bool == true
   let clients=apps.indices.map { index in
    let calls: RegionalMessagingCalls? = regional ? RegionalMessagingCalls(policy:RegionalRoutePolicy(initialRegion:{RegionalRoutePolicy.europe}),invoke:{region,operation,payload in
     let callable=Functions.functions(app:apps[index],region:region).httpsCallable(region==RegionalRoutePolicy.europe ? "messagingGatewayEurope" : "messagingGatewayUS")
     callable.timeoutInterval=8
     return try await callable.call(["operation":operation,"payload":payload]).data
    }) : nil
    return InternetMessagingClient(functions:Functions.functions(app:apps[index],region:"us-central1"),auth:auths[index],firestore:Firestore.firestore(app:apps[index]),regionalCalls:calls)
   }
   let publicKey=try MessagingIdentity.shared.publicKeyBase64()
   for client in clients {try await client.publishIdentityKey(publicKeyBase64:publicKey,discoverable:false,displayName:"Cross-platform live test")}
   try await post(["uids":[senderUid,receiverUid],"senderUid":senderUid,"receiverUid":receiverUid,"publicKey":publicKey,"crossRunId":run,"installationId":MessagingInstallation.id])
   var peer=try await get("android-peer");let deadline=Date().addingTimeInterval(120)
   while peer["crossRunId"] as? String != run || peer["receiverUid"]==nil {guard Date()<deadline else {throw NSError(domain:"LivePeerTimeout",code:1)};try await Task.sleep(nanoseconds:500_000_000);peer=try await get("android-peer")}
   let peerReceiver=try XCTUnwrap(peer["receiverUid"] as? String)
   let peerKey=try XCTUnwrap(peer["publicKey"] as? String)
   let peerSender=try XCTUnwrap(peer["senderUid"] as? String)
   let outgoing=ContactRecord(id:UUID(),name:"Android live test",broadcastId:"cross-\(run)",sessionCode:"cross-\(run)",aesKeyBase64:"",preferredTransport:.legacyBroadcast,peerUid:peerReceiver,peerPublicKey:peerKey,createdAt:Date(),lastUpdated:Date())
   let probe="cc.signal.probe.\(peerReceiver)";UserDefaults.standard.set(Date().timeIntervalSince1970,forKey:probe)
   defer {UserDefaults.standard.removeObject(forKey:probe)}
   await ContactStore.shared.waitUntilLoaded()
   let incoming=try XCTUnwrap(ContactStore.shared.applyInternetIdentity(conversationSessionCode:InternetConversation.pairId(peerSender,receiverUid),peerUid:peerSender,peerPublicKey:peerKey,displayName:"Android live sender"))
   DisasterTrafficController.shared.setEmergency(true);DisasterTrafficController.shared.setForeground(true)
   let transport=InternetChatTransport(client:clients[0])
   for _ in 0..<30 {if transport.isAvailable(allowUltraConstrained:true){break};try await Task.sleep(nanoseconds:100_000_000)}
   let textSent=await transport.sendText(messageId:"cross-ios-text-\(run)",text:"iOS → Android güvenli",contact:outgoing)
   let sosSent=await transport.sendText(messageId:"cross-ios-sos-\(run)",text:"iOS SOS güvenli",contact:outgoing,priority:"high",templateCode:202)
   XCTAssertTrue(textSent);XCTAssertTrue(sosSent)
   try await post(["ready":true])
   let readyDeadline=Date().addingTimeInterval(120)
   while (try await get("android-peer"))["ready"] as? Bool != true {guard Date()<readyDeadline else {throw NSError(domain:"LivePeerTimeout",code:2)};try await Task.sleep(nanoseconds:500_000_000)}
   let receiverTransport=InternetChatTransport(client:clients[1])
   for _ in 0..<30 {if receiverTransport.isAvailable(allowUltraConstrained:true){break};try await Task.sleep(nanoseconds:100_000_000)}
   let inbox=InternetMessageReceiver(client:clients[1],available:{receiverTransport.isAvailable(allowUltraConstrained:true)})
   handler=inbox;await inbox.start()
   let receiveDeadline=Date().addingTimeInterval(90)
   var messages:[SOSChatMessage]=[]
   while Date()<receiveDeadline {
    messages=await MainActor.run {SOSChatStore.shared.messages(for:incoming.id)}
    if messages.contains(where:{$0.transportMessageId=="cross-android-text-\(run)"}) && messages.contains(where:{$0.transportMessageId=="cross-android-sos-\(run)"}) {break}
    try await Task.sleep(nanoseconds:200_000_000)
   }
   XCTAssertEqual(messages.first {$0.transportMessageId=="cross-android-text-\(run)"}?.text,"Android → iOS güvenli")
   XCTAssertEqual(messages.first {$0.transportMessageId=="cross-android-sos-\(run)"}?.kind,.sosAlert)
   let ackDeadline=Date().addingTimeInterval(90)
   while !CompactInboxState.pending(uid:receiverUid).isEmpty, Date()<ackDeadline {try await Task.sleep(nanoseconds:200_000_000)}
   let remaining=try await Firestore.firestore(app:apps[1]).collection("messages").whereField("recipientUid",isEqualTo:receiverUid).getDocuments()
   XCTAssertTrue(remaining.isEmpty,"Automatic scheduler must receive and ACK without manual inbox or handler calls")
   if regional {try await post(["automaticRegionalE2ePassed":true,"preferredRegion":"europe-west4"])}
   await inbox.stop()
   _ = try await Functions.functions(app:apps[1],region:"us-central1").httpsCallable("compactInbox").call(["installationId":MessagingInstallation.id,"profileRevision":CompactInboxState.nextRevision(uid:receiverUid),"saving":false,"receive":false])
   UserDefaults.standard.set(false,forKey:"messaging.compactProfile.\(receiverUid)")
  } catch {
   await handler?.stop();for auth in auths {try? await auth.currentUser?.delete()}
   DisasterTrafficController.shared.setForeground(false);DisasterTrafficController.shared.setEmergency(false)
   throw error
  }
  await handler?.stop();for auth in auths {try? await auth.currentUser?.delete()}
  for app in apps {await withCheckedContinuation {c in app.delete {_ in c.resume()}}}
  DisasterTrafficController.shared.setForeground(false);DisasterTrafficController.shared.setEmergency(false)
 }
}
