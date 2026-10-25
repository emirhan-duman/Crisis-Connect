package com.auralis.crisisconnect.wear.independent

import android.content.Context
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/** Receives account enrollment only over the OS-authenticated paired-app channel. */
class IndependentWatchSetup(private val context: Context) {
    private val file=IndependentJournalFile(context,"independent-setup")
    private fun load()=file.readData()?.let { JSONObject(it.toString(Charsets.UTF_8)) } ?: JSONObject()
    private fun save(state:JSONObject)=file.writeData(state.toString().toByteArray())
    fun service(): IndependentWatchService {
        val state=load();check(state.optBoolean("enabled")) { "Telefondan saati onayla." }
        return client(state.getString("deviceID"))
    }
    private fun client(id:String)=IndependentWatchService(context,id,URL("https://europe-west1-crisis-connect-1.cloudfunctions.net/independentWatchGateway"))
    fun request(input:JSONObject):JSONObject = synchronized(LOCK) {
        val owner=input.getString("ownerUid");require(owner.matches(Regex("[A-Za-z0-9]{1,128}")))
        val state=load();require(!state.has("ownerUid") || state.getString("ownerUid")==owner) { "Watch belongs to another account" }
        val id=state.optString("deviceID").ifEmpty { UUID.randomUUID().toString() }
        val service=client(id)
        when(input.getString("stage")) {
            "enroll" -> {state.put("ownerUid",owner).put("deviceID",id);save(state);service.enrollment(owner)}
            "bind" -> {require(state.getString("deviceID")==input.getString("deviceID"));service.signalApproval(owner,input.getInt("deviceIndex"))}
            "complete" -> {require(state.getString("deviceID")==input.getString("deviceID"));state.put("enabled",true);save(state);JSONObject().put("ready",true)}
            "history" -> {require(state.optBoolean("enabled"));val offset=input.optInt("offset",0);require(offset in 0..512);val all=service.signal.outgoing();val batch=JSONObject()
                all.keys().asSequence().sorted().drop(offset).take(16).forEach { batch.put(it,all.getJSONObject(it)) }
                JSONObject().put("ownerUid",owner).put("deviceID",id).put("outbox",batch)}
            else -> error("Unknown setup operation")
        }
    }
    companion object { const val PATH="/crisis/independent/setup";private val LOCK=Any() }
}
