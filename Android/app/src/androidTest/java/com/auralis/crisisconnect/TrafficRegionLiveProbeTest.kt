package com.auralis.crisisconnect
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrafficRegionLiveProbeTest {
 @Test fun compareAuthenticatedCallableRegions(): Unit = runBlocking {
  assumeTrue(InstrumentationRegistry.getArguments().getString("liveRegionProbe")=="true")
  val context=ApplicationProvider.getApplicationContext<Context>()
  suspend fun request(payload:JSONObject?=null)=withContext(Dispatchers.IO) {
   val host=InstrumentationRegistry.getArguments().getString("liveFixtureHost") ?: "10.0.2.2"
   val c=URL("http://$host:31234/android").openConnection() as HttpURLConnection
   c.connectTimeout=10_000;c.readTimeout=10_000
   try {
    if(payload!=null) {c.requestMethod="POST";c.doOutput=true;c.outputStream.use {it.write(payload.toString().toByteArray())}}
    JSONObject(c.inputStream.bufferedReader().use {it.readText()})
   } finally {c.disconnect()}
  }
  val fixture=request();val run=fixture.getString("crossRunId")
  val baseOptions=FirebaseApp.getInstance().options
  val options=FirebaseOptions.Builder(baseOptions)
   .setApplicationId(fixture.optString("appId",baseOptions.applicationId)).build()
  val app=FirebaseApp.initializeApp(context,options,"region-probe-$run")
  context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}",Context.MODE_PRIVATE).edit()
   .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET",fixture.getString("debugToken")).commit()
  FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance(),false)
  val auth=FirebaseAuth.getInstance(app)
  try {
   request(JSONObject().put("probeStatus","running"))
   withTimeout(30_000){FirebaseAppCheck.getInstance(app).getAppCheckToken(true).await()}
   val uid=auth.signInAnonymously().await().user!!.uid
   request(JSONObject().put("uids",JSONArray(listOf(uid))))
   val idToken=auth.currentUser!!.getIdToken(false).await().token!!
   val appCheckToken=FirebaseAppCheck.getInstance(app).getAppCheckToken(false).await().token
   val rejections=JSONArray()
   suspend fun verifyRejected(region:String,name:String,token:String?,check:String?,kind:String)=withContext(Dispatchers.IO) {
    val c=URL("https://$region-crisis-connect-1.cloudfunctions.net/$name").openConnection() as HttpURLConnection
    c.connectTimeout=15_000;c.readTimeout=40_000;c.requestMethod="POST";c.doOutput=true
    c.setRequestProperty("Content-Type","application/json")
    token?.let {c.setRequestProperty("Authorization","Bearer $it")}
    check?.let {c.setRequestProperty("X-Firebase-AppCheck",it)}
    try {
     c.outputStream.use {it.write("{\"data\":{}}".toByteArray())}
     assertEquals("$region must reject $kind",401,c.responseCode)
     val result=JSONObject(c.errorStream.bufferedReader().use {it.readText()})
     assertEquals("UNAUTHENTICATED",result.getJSONObject("error").getString("status"))
     rejections.put(JSONObject().put("region",region).put("case",kind).put("httpStatus",401))
    } finally {c.disconnect()}
   }
   val measurements=JSONArray()
   for((region,name) in listOf("us-central1" to "messagingRegionProbe","europe-west4" to "messagingRegionProbeEurope", "asia-south1" to "messagingRegionProbeMumbai")) {
    val times=JSONArray();val serverTimes=JSONArray()
    repeat(4) {
     val started=android.os.SystemClock.elapsedRealtime()
     val result=FirebaseFunctions.getInstance(app,region).getHttpsCallable(name)
      .apply {setTimeout(40,java.util.concurrent.TimeUnit.SECONDS)}.call().await().data as Map<*,*>
     assertEquals(region,result["region"]);times.put(android.os.SystemClock.elapsedRealtime()-started)
     serverTimes.put((result["serverDurationMs"] as Number).toLong())
    }
    measurements.put(JSONObject().put("region",region).put("requestDurationMs",times).put("serverDurationMs",serverTimes))
    verifyRejected(region,name,idToken,null,"missingAppCheck")
    verifyRejected(region,name,idToken,"invalid-app-check-token","invalidAppCheck")
    verifyRejected(region,name,null,appCheckToken,"missingAuth")
    request(JSONObject().put("regionMeasurements",measurements).put("accessControlChecks",rejections))
   }
   request(JSONObject().put("regionMeasurements",measurements).put("accessControlChecks",rejections).put("probeStatus","passed"))
  } catch(error:Throwable) {
   runCatching {request(JSONObject().put("probeStatus","failed").put("errorType",error.javaClass.simpleName))}
   throw error
  } finally {runCatching {auth.currentUser?.delete()?.await()};app.delete()}
 }
}
