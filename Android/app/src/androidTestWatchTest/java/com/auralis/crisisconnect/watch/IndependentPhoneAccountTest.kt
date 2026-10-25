package com.auralis.crisisconnect.watch

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.wear.independent.*
import com.google.firebase.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.util.UUID
import java.io.File

class IndependentPhoneAccountTest {
    @Test fun authenticatedAccountConsentAndNormalPhoneDelivery() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: "initial"
        val file=File(context.filesDir,"independent-phone-account.json")
        fun saveReport(value:JSONObject){
            val atomic=android.util.AtomicFile(file);val output=atomic.startWrite()
            try{output.write(value.toString().toByteArray());atomic.finishWrite(output)}catch(error:Throwable){atomic.failWrite(output);throw error}
        }
        val metadata=if(phase!="initial")JSONObject(file.readText()) else JSONObject()
        suspend fun account(label:String):Pair<FirebaseAuth,FirebaseFunctions> {
            val app=FirebaseApp.initializeApp(context,FirebaseOptions.Builder().setApplicationId("1:123456789:android:abcdef123456")
                .setApiKey("AIzaSyEmulatorOnlyNotARealFirebaseApiKey00").setProjectId("demo-crisis-independent-watch").build(),"watch-$label-${UUID.randomUUID()}")!!
            val auth=FirebaseAuth.getInstance(app);auth.useEmulator("127.0.0.1",9097)
            val functions=FirebaseFunctions.getInstance(app,"europe-west1");functions.useEmulator("127.0.0.1",8291)
            val email=if(phase!="initial")metadata.getString("email") else "$label-${UUID.randomUUID()}@example.test"
            if(phase!="initial")auth.signInWithEmailAndPassword(email,"controlled-test-123!").await()
            else auth.createUserWithEmailAndPassword(email,"controlled-test-123!").await()
            if(label=="phone")metadata.put("email",email)
            return auth to functions
        }
        suspend fun invoke(functions:FirebaseFunctions,name:String,data:Map<String,Any>):Map<String,Any> {
            @Suppress("UNCHECKED_CAST")
            return functions.getHttpsCallable(name).call(data).await().data as Map<String,Any>
        }
        val (auth,functions)=account("phone");val owner=auth.currentUser!!.uid
        val endpoint=URL("http://127.0.0.1:8291/gateway")
        val receiver=IndependentPhoneInbox(context,owner,{name,data->invoke(functions,name,data)},endpoint){auth.currentUser?.uid==owner}
        val dao=AppDatabase.getInstance(context).messageDao()
        if(phase=="initial") {
            receiver.enable();val (senderAuth,senderFunctions)=account("watch");val senderUid=senderAuth.currentUser!!.uid
            val sender=IndependentWatchService(context,UUID.randomUUID().toString(),endpoint)
            fun JSONObject.map()=keys().asSequence().associateWith{get(it)}
            val enrolled=invoke(senderFunctions,"enrollIndependentWatch",sender.enrollment(senderUid).map()+("confirmed" to true))
            val index=(enrolled["signalDeviceId"] as Number).toInt()
            invoke(senderFunctions,"configureIndependentWatchSignal",sender.signalApproval(senderUid,index).map()+("confirmed" to true))
            val contact=Contact("Hesap onaylı test saati","","account-watch-$senderUid",peerUid=senderUid,peerPublicKey="controlled-saved-contact")
            saveContact(context,contact)
            val descriptor=receiver.devices(contact).single{it.getString("deviceID")==sender.deviceID}
            receiver.approve(contact,descriptor)
            val recipientBundle=receiver.service.signal.publicBundle(2)
            invoke(senderFunctions,"approveIndependentWatchContact",mapOf("confirmed" to true,"deviceID" to sender.deviceID,
                "peerInstallation" to receiver.deviceID,"peerUid" to owner,"identityKey" to recipientBundle.getString("identityKey")))
            sender.signal.pinPeer(owner,2,recipientBundle.getString("identityKey"))
            runCatching{sender.sendMessage(receiver.deviceID,owner,2,recipientBundle,IndependentPhoneInbox.textContent("Gerçek hesap akışından saat mesajı"))}
            sender.refresh();receiver.refresh();sender.refresh();receiver.refresh()
            val id=sender.signal.outgoing().keys().asSequence().single()
            assertEquals("delivered",sender.signal.outgoing().getJSONObject(id).getString("state"))
            assertEquals("Gerçek hesap akışından saat mesajı",dao.getMessageByUuid(id)!!.text)
            metadata.put("ownerUid",owner).put("messageID",id).put("sessionCode",contact.sessionCode).put("passed",true)
                .put("accountProvider","Firebase Auth SDK / isolated Auth emulator").put("realWatchRuntime",false)
            saveReport(metadata)
        } else if(phase=="native_approve") {
            val uid=metadata.getString("nativePeer")
            val contact=Contact("Gerçek saat test kişisi","","native-watch-$uid",peerUid=uid,peerPublicKey="controlled-saved-contact")
            saveContact(context,contact)
            receiver.approve(contact,receiver.devices(contact).single{it.getString("deviceID")==metadata.getString("nativeDevice")})
            metadata.put("nativeSessionCode",contact.sessionCode);saveReport(metadata)
        } else if(phase=="native_receive" || phase=="native_restore") {
            val id=metadata.getString("nativeMessageID")
            val before=dao.getMessageByUuid(id)
            if(phase=="native_restore")assertNotNull(before)
            receiver.refresh();receiver.restoreChats();receiver.refresh()
            val rows=dao.getRecentMessagesForSession(metadata.getString("nativeSessionCode"),100).filter{it.messageUuid==id}
            assertEquals(1,rows.size);assertEquals("Gerçek Wear OS cihazından mesaj",rows.single().text)
            if(before!=null){assertEquals(before.id,rows.single().id);assertEquals(before.timestampMillis,rows.single().timestampMillis)}
            metadata.put("nativePhase",phase).put("realWatchRuntime",true);saveReport(metadata)
        } else {
            assertEquals(metadata.getString("ownerUid"),owner)
            val id=metadata.getString("messageID");val before=dao.getMessageByUuid(id)!!
            receiver.restoreChats();receiver.refresh();receiver.restoreChats()
            val after=dao.getMessageByUuid(id)!!
            assertEquals(before.id,after.id);assertEquals(before.timestampMillis,after.timestampMillis)
            assertEquals(1,dao.getRecentMessagesForSession(metadata.getString("sessionCode"),100).count{it.messageUuid==id})
            metadata.put("restoredAfterProcessRestart",true);saveReport(metadata)
        }
    }
}
