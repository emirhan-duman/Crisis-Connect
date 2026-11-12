package com.auralis.crisisconnect.wear.independent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

/** Direct HTTPS client. Construction and restoration never dispatch an action. */
class IndependentWatchService(context: Context, val deviceID: String, private val endpoint: URL) {
    val signal = IndependentSignalVault(context, deviceID)
    private val identity = IndependentWatchIdentity(deviceID)
    private val journal = IndependentWatchJournal(IndependentJournalFile(context, "independent-$deviceID"))
    init {
        val emulatorDebug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && Build.HARDWARE in listOf("ranchu", "goldfish")
        require(endpoint.protocol == "https" && endpoint.host == "europe-west1-crisis-connect-1.cloudfunctions.net" && endpoint.path == "/independentWatchGateway" ||
            emulatorDebug && endpoint.protocol == "http" && endpoint.host == "127.0.0.1")
        require(endpoint.userInfo == null && endpoint.ref == null && endpoint.query == null)
    }
    fun enrollment(ownerUid: String) = identity.enrollment(ownerUid)
    fun history() = journal.snapshot()
    fun signalApproval(ownerUid:String, deviceIndex:Int):JSONObject {
        val proof=JSONObject().put("version",1).put("kind","signal-binding").put("ownerUid",ownerUid).put("deviceID",deviceID)
            .put("expiresAt",System.currentTimeMillis()+300_000).put("bundle",signal.publicBundle(deviceIndex))
        val encoded=IndependentWatchIdentity.b64(proof.toString().toByteArray())
        return JSONObject().put("ownerUid",ownerUid).put("payload",encoded).put("signature",identity.sign(encoded))
    }
    suspend fun startSOS(location: JSONObject, sessionID: String = UUID.randomUUID().toString()): JSONObject = dispatch("startSOS",sessionID,JSONObject(location.toString()).put("sessionID",sessionID))
    suspend fun stopSOS(sessionID: String): JSONObject = dispatch("stopSOS",sessionID,JSONObject().put("sessionID",sessionID))
    suspend fun refresh(inboxAfter: String? = null): JSONObject = withContext(Dispatchers.IO) { LOCK.withLock {
        journal.reload()
        val chunks=journal.snapshot().map{it.id}.chunked(32).ifEmpty{listOf(emptyList())}
        var result=JSONObject()
        val allReceipts=JSONArray()
        for(ids in chunks) {
            result=exchange(payload("status",UUID.randomUUID().toString(),JSONObject().put("commandIDs",JSONArray(ids)).apply { if(inboxAfter!=null)put("inboxAfter",inboxAfter) }))
            require(result.getString("deviceID")==deviceID)
            val receipts=result.getJSONArray("receipts")
            for(index in 0 until receipts.length()) {
                val receipt=receipts.getJSONObject(index)
                val operation=journal.snapshot().singleOrNull{it.id==receipt.getString("id")} ?: continue
                require(receipt.getString("digest")==digest(operation.payload))
                journal.reconcile(operation.id,receipt.getString("outcome"))
                if(operation.action=="shortMessage" && receipt.getString("outcome") in listOf("accepted","delivered","read"))signal.reconcile(operation.id,receipt.getString("outcome"))
                allReceipts.put(receipt)
            }
        }
        result.put("receipts",allReceipts)
    } }
    suspend fun sendMessage(recipientInstallation:String, uid:String, device:Int, bundle:JSONObject?, clear:ByteArray):JSONObject = withContext(Dispatchers.IO) { LOCK.withLock {
        val op=journal.reservePrepared("shortMessage",uid) { id ->
            val wire=signal.prepare(id,uid,device,bundle,clear)
            payload("shortMessage",id,JSONObject().put("messageID",id).put("recipientInstallation",recipientInstallation)
                .put("recipientUid",uid).put("recipientDevice",device).put("ctype",wire.getString("ctype")).put("ciphertext",wire.getString("ciphertext")))
        }
        val result=exchange(op.payload)
        require(result.getString("id")==op.id && result.getString("digest")==digest(op.payload))
        journal.reconcile(op.id,result.getString("outcome"));signal.reconcile(op.id,result.getString("outcome"));result
    } }
    suspend fun receiveMessage(message:JSONObject, persist: suspend (ByteArray) -> Unit = {}):ByteArray = withContext(Dispatchers.IO) { LOCK.withLock {
        val id=message.getString("id")
        val clear=signal.receive(id,message.getString("senderUid"),message.getInt("senderDevice"),message.getString("ctype"),message.getString("ciphertext"))
        persist(clear) // The normal chat must be durable before delivery is acknowledged.
        // Receipt loss must not hide successfully persisted plaintext from the conversation.
        try { exchange(payload("ackMessage",UUID.randomUUID().toString(),JSONObject().put("messageID",id))) } catch (_:Exception) { /* Reconcile on an explicit inbox check; never resend the message. */ }
        clear
    } }
    private suspend fun dispatch(action: String, subject: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) { LOCK.withLock {
        val op=journal.reservePrepared(action,subject){ id -> payload(action,id,body) }
        val result=exchange(op.payload)
        require(result.getString("id")==op.id && result.getString("digest")==digest(op.payload))
        journal.reconcile(op.id,result.getString("outcome"))
        result
    } }
    private fun payload(action:String,id:String,body:JSONObject):String {
        val now=System.currentTimeMillis()
        return IndependentWatchIdentity.b64(JSONObject().put("version",1).put("deviceID",deviceID).put("id",id)
            .put("issuedAt",now).put("expiresAt",now+30_000).put("action",action).put("body",body).toString().toByteArray())
    }
    private fun exchange(payload:String):JSONObject {
        val bytes=JSONObject().put("payload",payload).put("signature",identity.sign(payload)).toString().toByteArray()
        val connection=endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST";connection.instanceFollowRedirects=false
            connection.connectTimeout=8_000;connection.readTimeout=8_000
            connection.setRequestProperty("Content-Type","application/json");connection.setRequestProperty("Cache-Control","no-store")
            connection.doOutput=true;connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use{it.write(bytes)}
            check(connection.responseCode==200){"Watch operation unconfirmed (${connection.responseCode})"}
            val response=connection.inputStream.use{stream->
                val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=65_536);output.write(buffer,0,count)}
                output.toByteArray()
            }
            return JSONObject(response.toString(Charsets.UTF_8)).getJSONObject("result")
        } finally { connection.disconnect() }
    }
    companion object {
        private val LOCK=Mutex()
        fun digest(payload:String)=MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString(""){"%02x".format(it)}
    }
}
