package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.wear.independent.IndependentWatchService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Multi-process fixture phases, driven with the paired phone emulator powered off. */
class IndependentWatchMessageTest {
    @Test fun independentPhoneOff() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val phase=InstrumentationRegistry.getArguments().getString("phase")?:"provision"
        val prefs=context.getSharedPreferences("independent-native-fixture",0)
        val id=if(phase=="provision")UUID.randomUUID().toString().also{check(prefs.edit().putString("device",it).commit())}else requireNotNull(prefs.getString("device",null))
        val service=IndependentWatchService(context,id,URL("http://127.0.0.1:8291/gateway"))
        if(phase=="provision") {
            request("enroll",service.enrollment("watchTestWear").put("ownerUid","watchTestWear"))
            request("publish",service.signalApproval("watchTestWear",2))
            val incident=UUID.randomUUID().toString();check(prefs.edit().putString("session",incident).commit())
            try{service.startSOS(JSONObject().put("lat",41.01).put("lon",28.97).put("accuracyMeters",15).put("capturedAtMillis",System.currentTimeMillis()).put("countryIso","TR").put("fixtureDropResponse",true),incident)}catch(_:Exception){}
            assertEquals("active",service.refresh().getJSONObject("sos").getString("status"));service.stopSOS(incident)
        } else {
            val peer=request("peer/watchTestWear",null);val bundle=peer.getJSONObject("bundle")
            service.signal.pinPeer(peer.getString("ownerUid"),2,bundle.getString("identityKey"))
            val state=service.refresh()
            if(phase=="reply") {
                assertEquals(1,state.getJSONArray("inbox").length())
                val incoming=state.getJSONArray("inbox").getJSONObject(0)
                val altered=JSONObject(incoming.toString())
                val bad=java.util.Base64.getDecoder().decode(altered.getString("ciphertext"));bad[bad.lastIndex]=(bad.last().toInt() xor 1).toByte()
                altered.put("ciphertext",java.util.Base64.getEncoder().encodeToString(bad))
                var rejected=false;try{service.receiveMessage(altered)}catch(_:Exception){rejected=true};assertTrue("Tampered ciphertext must fail",rejected)
                rejected=false;try{service.signal.pinPeer(peer.getString("ownerUid"),2,service.signal.identityKey())}catch(_:Exception){rejected=true};assertTrue("Changed peer key must fail",rejected)
                val clear=service.receiveMessage(incoming);assertEquals("Apple Watch kontrollü mesaj",clear.toString(Charsets.UTF_8))
                assertArrayEquals(clear,service.receiveMessage(incoming)) // persisted plaintext, no second ratchet advance
                try{service.sendMessage(peer.getString("deviceID"),peer.getString("ownerUid"),2,bundle,"Wear OS kontrollü cevap".toByteArray())}catch(_:Exception){}
                service.refresh()
                assertEquals(1,service.signal.outgoing().length())
            } else {
                assertEquals("final",phase);assertEquals(1,service.signal.outgoing().length())
                val received=service.signal.incoming();assertEquals(1,received.length())
                assertEquals("Apple Watch kontrollü mesaj",java.util.Base64.getDecoder().decode(received.getJSONObject(received.keys().next()).getString("clear")).toString(Charsets.UTF_8))
                val outgoing=service.signal.outgoing();assertEquals("delivered",outgoing.getJSONObject(outgoing.keys().next()).getString("state"))
                assertEquals(0,state.getJSONArray("inbox").length())
            }
        }
        val restored=IndependentWatchService(context,id,URL("http://127.0.0.1:8291/gateway"))
        val final=restored.refresh();assertEquals("resolved",final.getJSONObject("sos").getString("status"))
        val report=JSONObject().put("passed",true).put("phase",phase).put("deviceID",id).put("commandIDs",JSONArray(restored.history().map{it.id})).put("states",JSONArray(restored.history().map{it.state})).put("outboxCount",restored.signal.outgoing().length()).put("inboxCount",restored.signal.incoming().length())
        File(context.filesDir,"independent-native-result.json").writeText(report.toString())
    }
    private fun request(path:String,body:JSONObject?):JSONObject {
        val c=URL("http://127.0.0.1:8291/$path").openConnection() as HttpURLConnection
        try{c.connectTimeout=8000;c.readTimeout=8000
            if(body!=null){c.requestMethod="POST";c.setRequestProperty("Content-Type","application/json");c.doOutput=true;c.outputStream.use{it.write(body.toString().toByteArray())}}
            check(c.responseCode==200){"Fixture request failed: $path ${c.responseCode}"}
            return c.inputStream.use{JSONObject(it.readBytes().toString(Charsets.UTF_8))}
        }finally{c.disconnect()}
    }
}
