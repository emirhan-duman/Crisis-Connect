package com.auralis.crisisconnect
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.messaging.*
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledCrossPlatformLiveE2eTest {
 @Test fun androidAndIosAutomaticallyReceiveAndBatchAcknowledge(): Unit = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("liveDisasterE2E")=="true")
  val context=ApplicationProvider.getApplicationContext<Context>()
  val fixtureHost=InstrumentationRegistry.getArguments().getString("liveFixtureHost") ?: "10.0.2.2"
  suspend fun get(platform:String)=withContext(Dispatchers.IO) {
   val c=URL("http://$fixtureHost:31234/$platform").openConnection() as HttpURLConnection
   c.connectTimeout=10_000;c.readTimeout=10_000
   try { JSONObject(c.inputStream.bufferedReader().use {it.readText()}) } finally {c.disconnect()}
  }
  suspend fun post(data:JSONObject)=withContext(Dispatchers.IO) {
   val c=URL("http://$fixtureHost:31234/android").openConnection() as HttpURLConnection
   c.requestMethod="POST";c.doOutput=true;c.connectTimeout=10_000;c.readTimeout=10_000
   c.outputStream.use {it.write(data.toString().toByteArray())};check(c.responseCode==200);c.disconnect()
  }
  val fixture=get("android");val run=fixture.getString("crossRunId")
  val apps=(0..1).map {FirebaseApp.initializeApp(context,FirebaseApp.getInstance().options,"scheduled-android-$run-$it")}
  apps.forEach {app->
   context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}",Context.MODE_PRIVATE).edit().putString("com.google.firebase.appcheck.debug.DEBUG_SECRET",fixture.getString("debugToken")).commit()
   FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance(),false)
   withTimeout(30_000){FirebaseAppCheck.getInstance(app).getAppCheckToken(true).await()}
  }
  val auths=apps.map {FirebaseAuth.getInstance(it)}
  var inbox:InternetInboxReceiver?=null
  var conversation:String?=null
  try {
   val uids=auths.map {it.signInAnonymously().await().user!!.uid}
   post(JSONObject().put("uids",JSONArray(uids)))
   val regional=fixture.optBoolean("regionalDelivery")
   val routes=JSONArray();var simulatedLostResponse=false
   val clients=apps.indices.map {index->
    val calls=if(regional) RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={RegionalRoutePolicy.EUROPE})) {region,operation,payload->
     val name=if(region==RegionalRoutePolicy.EUROPE) "messagingGatewayEurope" else "messagingGatewayUS"
     val result=FirebaseFunctions.getInstance(apps[index],region).getHttpsCallable(name).apply {setTimeout(8,java.util.concurrent.TimeUnit.SECONDS)}
      .call(mapOf("operation" to operation,"payload" to payload)).await().data
     routes.put(JSONObject().put("region",region).put("operation",operation).put("duplicate",(result as? Map<*,*>)?.get("duplicate")==true))
     post(JSONObject().put("regionalRoutes",routes))
     if(index==0 && region==RegionalRoutePolicy.EUROPE && operation=="relayMessage" && !simulatedLostResponse) {
      simulatedLostResponse=true
      throw com.google.firebase.functions.FirebaseFunctionsException::class.java
       .getDeclaredConstructor(String::class.java,com.google.firebase.functions.FirebaseFunctionsException.Code::class.java,Any::class.java)
       .newInstance("Simulated lost response after actual EU commit",com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,null)
     }
     result
    } else null
    InternetMessagingClient(FirebaseFunctions.getInstance(apps[index],"us-central1"),auths[index],FirebaseFirestore.getInstance(apps[index]),calls)
   }
   if(regional) {
    val payload=mapOf("v" to 2,"messageId" to "regional-interop-$run","conversationId" to "regionalInterop","recipientUid" to uids[1],"priority" to "normal","createdAtMs" to System.currentTimeMillis(),"ttlMs" to 86400000,"ctype" to "signal","ciphertext" to "YWJj","trafficPolicyVersion" to 1)
    suspend fun gateway(region:String,operation:String,data:Map<String,Any>)=FirebaseFunctions.getInstance(apps[0],region)
     .getHttpsCallable(if(region=="europe-west4") "messagingGatewayEurope" else "messagingGatewayUS").call(mapOf("operation" to operation,"payload" to data)).await().data as Map<*,*>
    gateway("europe-west4","relayMessage",payload)
    val old=FirebaseFunctions.getInstance(apps[1],"us-central1")
    val inboxPage=old.getHttpsCallable("compactInbox").call(mapOf("installationId" to "legacy-regional-test","saving" to false)).await().data as Map<*,*>
    assertTrue((inboxPage["messages"] as List<*>).any {(it as Map<*,*>)["messageId"]==payload["messageId"]})
    old.getHttpsCallable("acknowledgeMessage").call(mapOf("messageId" to payload["messageId"])).await()
    assertEquals(true,gateway("us-central1","relayMessage",payload)["duplicate"])
    val remainingInterop=FirebaseFirestore.getInstance(apps[1]).collection("messages").whereEqualTo("recipientUid",uids[1]).get().await()
    assertTrue(remainingInterop.documents.none {it.id==payload["messageId"]})
    val legacyPayload=payload+("messageId" to "legacy-interop-$run")
    FirebaseFunctions.getInstance(apps[0],"us-central1").getHttpsCallable("relayMessage").call(legacyPayload).await()
    val newReceiver=FirebaseFunctions.getInstance(apps[1],"europe-west4").getHttpsCallable("messagingGatewayEurope")
    val page=newReceiver.call(mapOf("operation" to "compactInbox","payload" to mapOf("installationId" to "new-regional-test","saving" to false))).await().data as Map<*,*>
    assertTrue((page["messages"] as List<*>).any {(it as Map<*,*>)["messageId"]==legacyPayload["messageId"]})
    newReceiver.call(mapOf("operation" to "acknowledgeMessage","payload" to mapOf("messageId" to legacyPayload["messageId"]))).await()
    post(JSONObject().put("legacyToRegionalSdkInterop",true).put("regionalToLegacySdkInterop",true).put("retryAfterLegacyAckSuppressed",true))
   }
   val publicKey=MessagingIdentity(context).publicKeyBase64()
   for(client in clients) client.publishIdentityKey(publicKey,false,displayName="Cross-platform live test")
   post(JSONObject().put("uids",JSONArray(uids)).put("senderUid",uids[0]).put("receiverUid",uids[1]).put("publicKey",publicKey).put("crossRunId",run).put("installationId",MessagingInstallation.id()))
   val peer=withTimeout(120_000){var p=get("ios-peer");while(p.optString("crossRunId")!=run || !p.has("receiverUid")){delay(500);p=get("ios-peer")};p}
   val outgoing=Contact(name="iOS live test",aesKey="",sessionCode="cross-$run",peerUid=peer.getString("receiverUid"),peerPublicKey=peer.getString("publicKey"))
   val probe=context.getSharedPreferences("crisisconnect_signal_probe",Context.MODE_PRIVATE)
   probe.edit().putLong(outgoing.peerUid,System.currentTimeMillis()).commit()
   conversation=InternetConversation.pairId(peer.getString("senderUid"),uids[1])
   saveContact(context,Contact(name="iOS live sender",aesKey="",sessionCode=conversation!!,peerUid=peer.getString("senderUid"),peerPublicKey=peer.getString("publicKey")))
   DisasterTrafficController.setEmergency(true);DisasterTrafficController.setForeground(true)
   val transport=InternetChatTransport(context,clients[0])
   assertTrue(transport.sendText("cross-android-text-$run","Android → iOS güvenli",System.currentTimeMillis(),outgoing))
   assertTrue(transport.sendText("cross-android-sos-$run","Android SOS güvenli",System.currentTimeMillis(),outgoing,"high",202))
   post(JSONObject().put("ready",true))
   withTimeout(120_000){while(!get("ios-peer").optBoolean("ready"))delay(500)}
   inbox=InternetInboxReceiver(clients[1]);inbox!!.start(context)
   withTimeout(90_000){
    val dao=AppDatabase.getInstance(context).messageDao()
    while(dao.getMessageByUuid("cross-ios-text-$run")==null || dao.getMessageByUuid("cross-ios-sos-$run")==null)delay(200)
   }
   assertEquals("iOS → Android güvenli",AppDatabase.getInstance(context).messageDao().getMessageByUuid("cross-ios-text-$run")?.text)
   assertEquals(MessageType.SOS_ALERT,AppDatabase.getInstance(context).messageDao().getMessageByUuid("cross-ios-sos-$run")?.messageType)
   withTimeout(90_000){while(CompactInboxState.pending(uids[1]).isNotEmpty())delay(200)}
   val pending=FirebaseFirestore.getInstance(apps[1]).collection("messages").whereEqualTo("recipientUid",uids[1]).get().await()
   assertTrue("Automatic worker must ACK both messages without a manual handler or inbox call",pending.isEmpty)
   if(regional) {
    assertTrue(simulatedLostResponse)
    assertTrue((0 until routes.length()).any {routes.getJSONObject(it).optBoolean("duplicate")})
    post(JSONObject().put("automaticRegionalE2ePassed",true).put("ambiguousEuropeCommitUsFallbackPassed",true))
   }
   inbox!!.stop()
   FirebaseFunctions.getInstance(apps[1],"us-central1").getHttpsCallable("compactInbox").call(mapOf("installationId" to MessagingInstallation.id(),"profileRevision" to CompactInboxState.nextRevision(uids[1]),"saving" to false,"receive" to false)).await()
   CompactInboxState.setProfile(uids[1],false)
   probe.edit().remove(outgoing.peerUid).commit()
  } finally {
   inbox?.stop();DisasterTrafficController.setForeground(false);DisasterTrafficController.setEmergency(false)
   conversation?.let {deleteContact(context,it);clearInternetMessageNotification(context,it)}
   for(auth in auths) runCatching {auth.currentUser?.delete()?.await()}
   apps.forEach {it.delete()}
  }
 }
}
