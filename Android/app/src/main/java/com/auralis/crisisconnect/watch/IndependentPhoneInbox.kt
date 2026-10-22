package com.auralis.crisisconnect.watch

import android.content.Context
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.wear.independent.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/** An explicitly enabled, separately keyed receiver feeding the ordinary encrypted chat database.
 * The phone's existing device-1 Signal identity is never replaced or copied to a watch. */
class IndependentPhoneInbox(private val context: Context, val ownerUid: String,
    private val call: suspend (String, Map<String, Any>) -> Map<String, Any> = { name, data ->
        require(FirebaseAuth.getInstance().currentUser?.let { it.uid == ownerUid && !it.isAnonymous } == true) { "Sign in to your account" }
        @Suppress("UNCHECKED_CAST")
        (FirebaseFunctions.getInstance("europe-west1").getHttpsCallable(name).call(data).await().data as Map<String, Any>)
    }, endpoint: URL = URL("https://europe-west1-crisis-connect-1.cloudfunctions.net/independentWatchGateway")
, private val accountAvailable: () -> Boolean = {
    FirebaseAuth.getInstance().currentUser?.let { it.uid == ownerUid && !it.isAnonymous } == true
}) {
    private fun requireAccount() { check(accountAvailable()) { "Account changed; reopen independent messaging" } }
    private val state = com.auralis.crisisconnect.security.KeystoreBackedPreferences(context,
        "independent_phone_$ownerUid", "independent_phone_state_v1")
    val deviceID: String = synchronized(LOCK) {
        readState("deviceID") ?: UUID.randomUUID().toString().also { putState("deviceID", it) }
    }
    private fun readState(key: String): String? = state.getStringStrict(key, "").takeIf { it.isNotEmpty() }
    private fun putState(key: String, value: String) { check(state.putStringCommitted(key,value)) { "Cannot persist account consent" } }
    val service = IndependentWatchService(context, deviceID, endpoint)

    /** Call only after the user approves enabling this receiver. Construction is passive. */
    suspend fun enable() = withContext(Dispatchers.IO) {
        requireAccount()
        val enrolled = call("enrollIndependentWatch", service.enrollment(ownerUid).toMap() + mapOf("confirmed" to true, "kind" to "phoneInbox"))
        val index = (enrolled["signalDeviceId"] as Number).toInt()
        call("configureIndependentWatchSignal", service.signalApproval(ownerUid,index).toMap() + ("confirmed" to true))
        putState("enabled", "true")
    }
    fun isEnabled() = readState("enabled") == "true"
    suspend fun devices(contact: Contact): List<JSONObject> {
        requireAccount()
        require(WatchContactMessaging.eligible(contact))
        val rows = call("listIndependentContactDevices", mapOf("peerUid" to contact.peerUid))["devices"] as? List<*> ?: emptyList<Any>()
        return rows.map { JSONObject(it as Map<*, *>) }
    }
    /** The screen presents this exact device/fingerprint before invoking approval. */
    suspend fun approve(contact: Contact, descriptor: JSONObject) = withContext(Dispatchers.IO) {
        requireAccount()
        val latest = requireNotNull(getContact(context, contact.sessionCode))
        require(WatchContactMessaging.eligible(latest) && latest.peerUid == descriptor.getString("ownerUid"))
        val bundle=descriptor.getJSONObject("bundle"); val peerID=descriptor.getString("deviceID")
        service.signal.pinPeer(latest.peerUid,bundle.getInt("deviceId"),bundle.getString("identityKey"))
        for (own in listOfNotNull(deviceID,readState("watchID"))) call("approveIndependentWatchContact",mapOf("confirmed" to true,"deviceID" to own,"peerInstallation" to peerID,
            "peerUid" to latest.peerUid,"identityKey" to bundle.getString("identityKey"),"name" to latest.name))
        putState("peer:$peerID", descriptor.toString())
    }
    suspend fun revokeContact(descriptor: JSONObject) {
        requireAccount()
        val peer=descriptor.getString("deviceID")
        for(own in listOfNotNull(deviceID,readState("watchID")))call("revokeIndependentWatchContact",mapOf("deviceID" to own,"peerInstallation" to peer))
        check(state.putStringCommitted("peer:$peer",null))
    }
    private suspend fun watchRequest(data: JSONObject): JSONObject {
        requireAccount()
        val nodes=com.google.android.gms.wearable.Wearable.getNodeClient(context).connectedNodes.await()
        val node=nodes.singleOrNull() ?: error("Bağlı saatte uygulamayı aç.")
        return kotlinx.coroutines.withTimeout(8_000) {
            JSONObject(String(com.google.android.gms.wearable.Wearable.getMessageClient(context).sendRequest(node.id,IndependentWatchSetup.PATH,data.put("ownerUid",ownerUid).toString().toByteArray()).await()))
        }
    }
    suspend fun pairWatch() {
        requireAccount()
        val enrollment=watchRequest(JSONObject().put("stage","enroll"))
        val id=enrollment.getString("deviceID")
        val enrolled=call("enrollIndependentWatch",enrollment.toMap()+("confirmed" to true))
        val proof=watchRequest(JSONObject().put("stage","bind").put("deviceID",id).put("deviceIndex",(enrolled["signalDeviceId"] as Number).toInt()))
        call("configureIndependentWatchSignal",proof.toMap()+("confirmed" to true))
        watchRequest(JSONObject().put("stage","complete").put("deviceID",id))
        putState("watchID",id)
    }
    suspend fun syncOwnWatchHistory() = withContext(Dispatchers.IO) { projectionMutex.withLock {
        requireAccount()
        val watch=readState("watchID") ?: return@withContext
        val outbox=JSONObject();var offset=0
        while(offset<512) {
            val response=watchRequest(JSONObject().put("stage","history").put("offset",offset))
            require(response.getString("ownerUid")==ownerUid && response.getString("deviceID")==watch)
            val batch=response.getJSONObject("outbox");require(batch.length()<=16)
            for(id in batch.keys())outbox.put(id,batch.getJSONObject(id))
            offset+=batch.length();if(batch.length()<16)break
        }
        val states=mutableMapOf<String,String>()
        for(ids in outbox.keys().asSequence().toList().chunked(32)) {
            val receipts=call("readIndependentWatchReceipts",mapOf("deviceID" to watch,"ids" to ids))["receipts"] as? List<*> ?: emptyList<Any>()
            for(item in receipts) {val receipt=JSONObject(item as Map<*,*>);states[receipt.getString("id")]=receipt.getString("outcome")}
        }
        val dao=AppDatabase.getInstance(context).messageDao()
        for(id in outbox.keys()) {
            requireAccount()
            val row=outbox.getJSONObject(id)
            val contact=getContactByPeerUid(context,row.getString("recipientUid"))?.takeIf(WatchContactMessaging::eligible) ?: continue
            val content=JSONObject(IndependentSignalVault.bytes(row.getString("clear")).toString(Charsets.UTF_8))
            require(content.getInt("version")==1 && content.getString("kind")=="watchText")
            val text=content.getString("text");require(text.toByteArray().size<=1500)
            val old=dao.getMessageByUuid(id)
            require(old==null || old.isLocal && old.sessionCode==contact.sessionCode && old.text==text)
            val status=when(states[id] ?: row.getString("state")) {
                "read"->MessageDeliveryStatus.READ;"delivered"->MessageDeliveryStatus.DELIVERED;"accepted"->MessageDeliveryStatus.SENT;else->MessageDeliveryStatus.FAILED
            }
            // watch_reply is excluded from every automatic resend path, including uncertain rows.
            if(old?.deliveryStatus==MessageDeliveryStatus.READ ||
                old?.deliveryStatus==MessageDeliveryStatus.DELIVERED && status!=MessageDeliveryStatus.READ ||
                old?.deliveryStatus==MessageDeliveryStatus.SENT && status==MessageDeliveryStatus.FAILED)continue
            dao.upsertLocalTextMessageAtomic(contact.sessionCode,id,text,old?.timestampMillis?:System.currentTimeMillis(),null,status,0,null,null,
                if(status==MessageDeliveryStatus.FAILED)"watch_uncertain" else null,WatchReplyChat.ROUTE)
        }
    } }
    suspend fun disable() {
        requireAccount()
        // Server revocation first; retain local plaintext history and permanent installation ID.
        call("revokeIndependentWatch",mapOf("deviceID" to deviceID))
        putState("enabled","false")
    }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        requireAccount()
        if (!isEnabled()) return@withContext
        restoreChats()
        runCatching { syncOwnWatchHistory() }
        var cursor=readState("inboxCursor")
        repeat(16) {
            val inbox=service.refresh(cursor).getJSONArray("inbox")
            for (i in 0 until inbox.length()) runCatching { receiveApproved(inbox.getJSONObject(i)) }
            cursor=if(inbox.length()<2)null else inbox.getJSONObject(inbox.length()-1).getString("id")
            putState("inboxCursor",cursor.orEmpty())
            if(cursor==null)return@withContext
        }
    }
    suspend fun receiveApproved(message: JSONObject) {
        requireAccount()
        val descriptor=requireNotNull(readState("peer:${message.getString("senderInstallation")}")?.let(::JSONObject)) { "Unapproved sender" }
        require(descriptor.getString("ownerUid")==message.getString("senderUid") &&
            descriptor.getJSONObject("bundle").getInt("deviceId")==message.getInt("senderDevice"))
        service.receiveMessage(message) { clear -> importMessage(message.getString("id"), message.getString("senderUid"), clear) }
    }
    suspend fun restoreChats() = withContext(Dispatchers.IO) {
        requireAccount()
        val inbox=service.signal.incoming()
        for(id in inbox.keys()) {
            val row=inbox.getJSONObject(id)
            // Old experimental archives have no normal-chat provenance and are not imported.
            if(row.has("senderUid")) runCatching { importMessage(id,row.getString("senderUid"),IndependentSignalVault.bytes(row.getString("clear"))) }
        }
    }
    private suspend fun importMessage(id:String, uid:String, clear:ByteArray) {
        requireAccount()
        val contact=requireNotNull(getContactByPeerUid(context,uid)) { "Contact removed" }
        require(WatchContactMessaging.eligible(contact)) { "Contact identity needs approval" }
        val content=JSONObject(clear.toString(Charsets.UTF_8))
        require(content.getInt("version")==1 && content.getString("kind")=="watchText")
        val text=content.getString("text");require(text.isNotBlank() && text.toByteArray().size<=1500)
        val dao=AppDatabase.getInstance(context).messageDao()
        val old=dao.getMessageByUuid(id)
        require(old==null || (!old.isLocal && old.sessionCode==contact.sessionCode && old.text==text)) { "Message ID conflict" }
        saveRemoteMessage(context,contact.sessionCode,id,text,senderDisplayName=contact.name)
        // A concurrent importer may have won INSERT OR IGNORE; acknowledge only the exact row.
        val committed=requireNotNull(dao.getMessageByUuid(id))
        require(!committed.isLocal && committed.sessionCode==contact.sessionCode && committed.text==text) { "Message ID conflict" }
    }
    companion object {
        private val LOCK=Any()
        private val projectionMutex=kotlinx.coroutines.sync.Mutex()
        fun textContent(text:String):ByteArray = JSONObject().put("version",1).put("kind","watchText").put("text",text).toString().toByteArray()
        private fun JSONObject.toMap():Map<String,Any> = keys().asSequence().associateWith { get(it) }
    }
}
