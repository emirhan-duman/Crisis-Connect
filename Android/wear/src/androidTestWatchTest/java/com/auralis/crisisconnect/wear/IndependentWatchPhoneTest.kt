package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.wear.independent.IndependentWatchService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URL
import java.util.UUID

class IndependentWatchPhoneTest {
 @Test fun nativeWatchToNormalPhone() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val args=InstrumentationRegistry.getArguments();val phase=args.getString("phase")?:"prepare"
  val file=File(context.filesDir,"independent-phone-native.json")
  val report=if(phase=="prepare")JSONObject().put("deviceID",UUID.randomUUID().toString())else JSONObject(file.readText())
  val service=IndependentWatchService(context,report.getString("deviceID"),URL("http://127.0.0.1:8291/gateway"))
  if(phase=="prepare"){
   val uid=requireNotNull(args.getString("ownerUid"))
   report.put("enrollment",service.enrollment(uid)).put("binding",service.signalApproval(uid,2))
  }else if(phase=="send"){
   assertEquals(0,service.signal.outgoing().length())
   val contacts=service.refresh().getJSONArray("contacts");assertEquals(1,contacts.length())
   val peer=contacts.getJSONObject(0);val bundle=peer.getJSONObject("bundle")
   service.signal.pinPeer(peer.getString("ownerUid"),bundle.getInt("deviceId"),bundle.getString("identityKey"))
   service.sendMessage(peer.getString("deviceID"),peer.getString("ownerUid"),bundle.getInt("deviceId"),bundle,
    JSONObject().put("version",1).put("kind","watchText").put("text","Gerçek Wear OS cihazından mesaj").toString().toByteArray())
   report.put("messageID",service.signal.outgoing().keys().asSequence().single())
  }else{
   assertEquals("restore",phase);service.refresh()
   val outgoing=service.signal.outgoing();assertEquals(1,outgoing.length())
   assertEquals("delivered",outgoing.getJSONObject(report.getString("messageID")).getString("state"))
   assertEquals(1,service.history().size)
   report.put("deliveredAfterWatchReboot",true)
  }
  report.put("phase",phase).put("passed",true).put("realWatchRuntime",true)
  val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
  try { output.write(report.toString().toByteArray());atomic.finishWrite(output) }
  catch(error:Throwable){atomic.failWrite(output);throw error}
 }
}
