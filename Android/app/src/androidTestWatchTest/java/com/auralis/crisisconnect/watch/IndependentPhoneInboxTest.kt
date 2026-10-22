package com.auralis.crisisconnect.watch

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.wear.independent.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.URL
import java.util.UUID
import java.io.File

class IndependentPhoneInboxTest {
    @Test fun approvedCiphertextProjectsOnceAndSurvivesProcessRestart() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val phase=InstrumentationRegistry.getArguments().getString("phase") ?: "initial"
        val fixture=File(context.filesDir,"independent-phone-fixture.json")
        val endpoint=URL("http://127.0.0.1:8298/gateway") // No listener: ACK is deliberately unavailable.
        var calls=0;var authorized=true
        val call:suspend (String,Map<String,Any>)->Map<String,Any> = {name,_->calls++;when(name){"enrollIndependentWatch"->mapOf("signalDeviceId" to 2);else->mapOf("approved" to true)}}
        val owner="controlledPhoneAndroid"
        val receiver=IndependentPhoneInbox(context,owner,call,endpoint){authorized}
        if(phase=="initial") {
            val senderID=UUID.randomUUID().toString();val uid="controlledWatchAndroid";val sender=IndependentSignalVault(context,senderID)
            val contact=Contact("Kontrollü bağımsız saat","","watch-inbox-test",peerUid=uid,peerPublicKey="controlled-contact-key")
            saveContact(context,contact)
            receiver.enable()
            val descriptor=JSONObject().put("ownerUid",uid).put("deviceID",senderID).put("bundle",sender.publicBundle(3))
            receiver.approve(contact,descriptor)
            sender.pinPeer(owner,2,receiver.service.signal.identityKey())
            val id=UUID.randomUUID().toString();val clear=IndependentPhoneInbox.textContent("Saatten bağımsız kontrollü mesaj")
            val wire=sender.prepare(id,owner,2,receiver.service.signal.publicBundle(2),clear)
            val message=JSONObject().put("id",id).put("senderUid",uid).put("senderDevice",3).put("senderInstallation",senderID)
                .put("ctype",wire.getString("ctype")).put("ciphertext",wire.getString("ciphertext"))
            fixture.writeText(message.toString())
            receiver.receiveApproved(message)
            receiver.receiveApproved(message)
            val row=AppDatabase.getInstance(context).messageDao().getMessageByUuid(id)!!
            assertEquals("Saatten bağımsız kontrollü mesaj",row.text);assertFalse(row.isLocal)
            assertEquals(MessageDeliveryStatus.DELIVERED,row.deliveryStatus)
            val altered=JSONObject(message.toString()).put("senderDevice",4)
            assertTrue(runCatching{receiver.receiveApproved(altered)}.isFailure)
            authorized=false;assertTrue(runCatching{receiver.restoreChats()}.isFailure);authorized=true
        } else {
            assertEquals("restore",phase);assertTrue(fixture.exists());assertTrue(receiver.isEnabled())
            val message=JSONObject(fixture.readText());val id=message.getString("id")
            val dao=AppDatabase.getInstance(context).messageDao();val before=dao.getMessageByUuid(id)!!
            receiver.restoreChats();receiver.receiveApproved(message);receiver.restoreChats()
            val after=dao.getMessageByUuid(id)!!
            assertEquals(before.id,after.id);assertEquals(before.timestampMillis,after.timestampMillis)
            assertEquals(1,dao.getRecentMessagesForSession("watch-inbox-test",100).count{it.messageUuid==id})
            assertEquals(0,calls) // Restoring/receiving never enrolls, sends, or starts SOS.
        }
        File(context.filesDir,"independent-phone-result.json").writeText(JSONObject().put("passed",true).put("phase",phase)
            .put("accountProvider","controlled injection; not Firebase Auth SDK").put("chatDatabase","normal SQLCipher Room")
            .put("sendsDuringRestore",0).toString())
    }
}
