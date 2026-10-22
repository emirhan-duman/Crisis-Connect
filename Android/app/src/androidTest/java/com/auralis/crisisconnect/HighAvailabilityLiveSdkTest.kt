package com.auralis.crisisconnect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.messaging.*
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live canary; isolated accounts, real native SDKs and production envelope encryption. */
@RunWith(AndroidJUnit4::class)
class HighAvailabilityLiveSdkTest {
 @Test fun encryptedNamedDatabaseAndLegacyCompatibility(): Unit = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("liveHaSdk")=="true")
  android.util.Log.i("HaSdkTest","starting")
  val context=ApplicationProvider.getApplicationContext<Context>()
  suspend fun fixture(body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO) {
   val c=URL("http://127.0.0.1:31234/android").openConnection() as HttpURLConnection
   c.connectTimeout=10_000;c.readTimeout=10_000
   try { if(body!=null){c.requestMethod="POST";c.doOutput=true;c.outputStream.use{it.write(body.toString().toByteArray())}}
    JSONObject(c.inputStream.bufferedReader().use{it.readText()})
   } finally {c.disconnect()}
  }
  val config=fixture();val run=config.getString("runId")
  android.util.Log.i("HaSdkTest","fixture-loaded")
  val options=FirebaseOptions.Builder(FirebaseApp.getInstance().options).setApplicationId(config.getString("appId")).build()
  val apps=(0..1).map { i -> android.util.Log.i("HaSdkTest","initializing-app-$i"); FirebaseApp.initializeApp(context,options,"ha-android-$run-$i").also { app ->
   context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}",Context.MODE_PRIVATE).edit()
    .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET",config.getString("debugToken")).commit()
   FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance(),false)
  }}
  android.util.Log.i("HaSdkTest","apps-initialized")
  val auths=apps.map{FirebaseAuth.getInstance(it)}
  val stores=apps.map{FirebaseFirestore.getInstance(it,MessagingDatabaseTarget.DATABASE_ID)}
  fun client(index:Int,region:String)=InternetMessagingClient(auth=auths[index],firestore=stores[index],
   regionalCalls=RegionalMessagingCalls(RegionalRoutePolicy(initialRegion={region}),highAvailability=true){r,op,payload ->
    FirebaseFunctions.getInstance(apps[index],r).getHttpsCallable(if(r==RegionalRoutePolicy.EUROPE) "messagingGatewayHaEurope" else "messagingGatewayHaUS")
     .apply{setTimeout(45,java.util.concurrent.TimeUnit.SECONDS)}.call(mapOf("operation" to op,"payload" to payload)).await().data
   })
  try {
   android.util.Log.i("HaSdkTest","requesting-app-check")
   for(app in apps) withTimeout(45_000){FirebaseAppCheck.getInstance(app).getAppCheckToken(true).await()}
   android.util.Log.i("HaSdkTest","requesting-auth")
   val uids=auths.map{withTimeout(45_000){it.signInAnonymously().await().user!!.uid}}
   android.util.Log.i("HaSdkTest","waiting-for-canary")
   fixture(JSONObject().put("uids",JSONArray(uids)).put("haUids",JSONArray(uids)).put("haReady",false).put("haStatus","waitingForCanary"))
   withTimeout(300_000){while(!fixture().optBoolean("haReady")) delay(1000)}
   val keys=(0..1).map{KeyPairGenerator.getInstance("EC").apply{initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair()}
   android.util.Log.i("HaSdkTest","canary-ready")
   val sender=client(0,RegionalRoutePolicy.EUROPE);val receiver=client(1,RegionalRoutePolicy.US)
   sender.publishIdentityKey(MessagingKeyCodec.encodePublicKey(keys[0].public),false)
   receiver.publishIdentityKey(MessagingKeyCodec.encodePublicKey(keys[1].public),false)
   assertEquals(MessagingKeyCodec.encodePublicKey(keys[1].public),sender.lookupIdentityKey(uid=uids[1]).publicKeyBase64)
   val conversation=InternetConversation.pairId(uids[0],uids[1]);val id="ha-android-$run"
   val sealed=E2eEnvelope.seal(MessageContent(templateCode=0,text="HA native Android canary"),keys[1].public,keys[0].private,uids[0],uids[1],conversation)
   val envelope=OutgoingEnvelope(id,conversation,uids[1],"normal",System.currentTimeMillis(),300_000,sealed)
   sender.writeMessageDoc(envelope)
   val row=stores[1].collection("messages").document(id).get(Source.SERVER).await()
   assertTrue(row.exists())
   val received=SealedMessage(row.getString("ephemeralPubKey")!!,row.getString("nonce")!!,row.getString("ciphertext")!!,row.getString("alg")!!)
   assertEquals("HA native Android canary",E2eEnvelope.open(received,keys[1].private,keys[0].public,uids[0],uids[1],conversation).text)
   val old=FirebaseFunctions.getInstance(apps[1],"us-central1")
   val inbox=old.getHttpsCallable("compactInbox").call(mapOf("installationId" to "ha-android-test","saving" to false)).await().data as Map<*,*>
   assertTrue((inbox["messages"] as List<*>).any{(it as Map<*,*>)["messageId"]==id})
   old.getHttpsCallable("acknowledgeMessage").call(mapOf("messageId" to id)).await()
   client(0,RegionalRoutePolicy.US).writeMessageDoc(envelope)
   assertFalse(stores[1].collection("messages").whereEqualTo("recipientUid",uids[1]).get(Source.SERVER).await().documents.any{it.id==id})
   fixture(JSONObject().put("haStatus","passed").put("haChecks",JSONArray(listOf("eur3-sdk-read","encrypted-roundtrip","eu-us-same-message","legacy-inbox-ack","no-resurrection"))))
  } catch(e:Throwable){runCatching{fixture(JSONObject().put("haStatus","failed").put("haErrorType",e.javaClass.simpleName))};throw e}
  finally {
   apps.forEachIndexed { i,app ->
    runCatching { FirebaseFunctions.getInstance(app,"us-central1").getHttpsCallable("deleteAccountAndData").call().await() }
    runCatching { auths[i].currentUser?.delete()?.await() };runCatching{stores[i].terminate().await()};app.delete()
   }
  }
 }
}
