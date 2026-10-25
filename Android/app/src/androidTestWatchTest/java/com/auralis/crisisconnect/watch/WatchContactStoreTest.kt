package com.auralis.crisisconnect.watch

import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class WatchContactStoreTest {
    @Test fun journalProjectsOnceAndRejectsChangedRemovedRecipients() = runBlocking(Dispatchers.IO) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val peer="contact-test-"+UUID.randomUUID();val store=WatchRecoveryStore.get(context)
        val contact=Contact("Test kişi","",peer,peerUid="isolated",peerPublicKey="test")
        val command=WatchCommand(action=WatchAction.contactReply,peerID=peer,reply=QuickReply.safe)
        val dao=AppDatabase.getInstance(context).messageDao()
        var effects=0
        val relay:suspend (Contact,String,String,Long)->Boolean={recipient,id,text,_ ->
            effects++;assertEquals(peer,recipient.sessionCode);assertEquals(command.id,id);assertTrue(text.isNotBlank())
            assertEquals("uncertain",store.state.value.replies.first {it.id==id}.result);true
        }
        try {
            saveContact(context,contact)
            assertEquals("accepted",WatchContactMessaging.send(context,command,relay))
            val before=dao.getMessageByUuid(command.id)!!
            AppDatabase.getInstance(context).contactDao().deleteBySessionCode(peer)
            assertEquals("accepted",WatchContactMessaging.send(context,command,relay));assertEquals(1,effects)
            saveContact(context,contact);WatchReplyChat.reconcile(context)
            assertEquals(before.messageUuid,dao.getMessageByUuid(command.id)!!.messageUuid)
            assertEquals(before.timestampMillis,dao.getMessageByUuid(command.id)!!.timestampMillis)
            dao.markLocalMessagesDelivered(listOf(command.id));WatchReplyChat.reconcile(context)
            assertEquals(MessageDeliveryStatus.DELIVERED,dao.getMessageByUuid(command.id)!!.deliveryStatus)
            saveContact(context,contact.copy(peerKeyChanged=true))
            assertEquals("noPeer",WatchContactMessaging.send(context,command.copy(id=UUID.randomUUID().toString()),relay));assertEquals(1,effects)
            val reserved=command.copy(id=UUID.randomUUID().toString())
            store.reserveReply(reserved.id,peer,"İyiyim")
            assertEquals("uncertain",WatchContactMessaging.send(context,reserved,relay));assertEquals(1,effects)
            assertEquals(MessageDeliveryStatus.FAILED,dao.getMessageByUuid(reserved.id)!!.deliveryStatus)
            dao.markLocalMessagesDelivered(listOf(reserved.id));WatchReplyChat.reconcile(context)
            assertEquals(MessageDeliveryStatus.DELIVERED,dao.getMessageByUuid(reserved.id)!!.deliveryStatus)
            dao.markLocalMessagesRead(listOf(reserved.id));WatchReplyChat.reconcile(context)
            assertEquals(MessageDeliveryStatus.READ,dao.getMessageByUuid(reserved.id)!!.deliveryStatus)
            assertEquals("stateChanged",WatchContactMessaging.send(context,command.copy(peerID="wrong-recipient"),relay))
            assertNull(store.state.value.startedAt)
            val old=JSONObject().put("version",1).put("sosActive",false).put("bleBroadcasting",false).put("updatedAt",1)
            assertTrue(PhoneWatchState.decode(old).contacts.isEmpty())
            val huge=PhoneWatchState(contacts=List(20){WatchSavedContact("test-$it","\n".repeat(60),"\n".repeat(100),true)},contactCount=20)
            assertTrue(huge.json().toString().toByteArray().size<=14_000)
        } finally {store.removeRepliesForPeer(peer);AppDatabase.getInstance(context).contactDao().deleteBySessionCode(peer)}
    }
}
