package com.auralis.crisisconnect.wear

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.wear.independent.IndependentWatchService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class IndependentWatchSOSTest {
    @Test fun directSOSWithPhoneOffLostReceiptAndReopen() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val id=UUID.randomUUID().toString();val incident=UUID.randomUUID().toString()
        val url=URL("http://127.0.0.1:8291/gateway")
        val service=IndependentWatchService(context,id,url)
        val enrollment=service.enrollment("watchTestWear").put("ownerUid","watchTestWear")
        val connection=URL("http://127.0.0.1:8291/enroll").openConnection() as HttpURLConnection
        try{connection.requestMethod="POST";connection.connectTimeout=8000;connection.readTimeout=8000;connection.setRequestProperty("Content-Type","application/json");connection.doOutput=true
            connection.outputStream.use{it.write(enrollment.toString().toByteArray())};assertEquals(200,connection.responseCode);connection.inputStream.close()
        }finally{connection.disconnect()}
        val location=JSONObject().put("lat",41.01).put("lon",28.97).put("accuracyMeters",15).put("capturedAtMillis",System.currentTimeMillis()).put("countryIso","TR").put("fixtureDropResponse",true)
        try{service.startSOS(location,incident)}catch(_:Exception){}
        assertEquals(1,service.history().size)
        val restored=IndependentWatchService(context,id,url)
        val active=restored.refresh();assertEquals("active",active.getJSONObject("sos").getString("status"))
        assertEquals(incident,active.getJSONObject("sos").getString("sessionID"));assertEquals("accepted",restored.history().single().state)
        restored.stopSOS(incident)
        val reopened=IndependentWatchService(context,id,url)
        val final=reopened.refresh();assertEquals("resolved",final.getJSONObject("sos").getString("status"))
        assertEquals(2,reopened.history().size);assertTrue(reopened.history().all{it.state=="accepted"})
        assertEquals(restored.history().map{it.id},reopened.history().map{it.id})
        val report=JSONObject().put("passed",true).put("deviceID",id).put("sessionID",incident).put("status",final.getJSONObject("sos")).put("commandIDs",org.json.JSONArray(reopened.history().map{it.id}))
        File(context.filesDir,"independent-sos-result.json").writeText(report.toString())
    }
}
