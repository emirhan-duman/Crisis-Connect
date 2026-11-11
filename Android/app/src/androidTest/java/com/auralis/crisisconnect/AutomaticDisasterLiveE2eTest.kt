package com.auralis.crisisconnect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.connectivity.DisasterTrafficController
import com.auralis.crisisconnect.data.*
import com.auralis.crisisconnect.messaging.*
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in live smoke on disposable named Firebase apps; never signs out the default user.
 * Host fixture is localhost-only and holds revocable App Check credentials outside the repository.
 */
@RunWith(AndroidJUnit4::class)
class AutomaticDisasterLiveE2eTest {
    @Test fun normalConservationAndRecoveryUseLiveEncryptedMessaging(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDisasterE2E") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(false)
        val fixture = withContext(Dispatchers.IO) {
            val connection = URL("http://10.0.2.2:31234/android").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        }
        val run = fixture.getString("runId")
        val apps = (0..1).map { FirebaseApp.initializeApp(context, FirebaseApp.getInstance().options, "disaster-live-$run-$it") }
        apps.forEach { app ->
            context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}", Context.MODE_PRIVATE)
                .edit().putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", fixture.getString("debugToken")).commit()
            FirebaseAppCheck.getInstance(app).installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance(), false)
            withTimeout(30_000) { FirebaseAppCheck.getInstance(app).getAppCheckToken(true).await() }
        }
        val auths = apps.map { FirebaseAuth.getInstance(it) }
        var incomingConversation: String? = null
        val sentIds = mutableListOf<String>()
        try {
            val uids = auths.map { it.signInAnonymously().await().user!!.uid }
            val senderUid = uids[0]; val receiverUid = uids[1]
            val functions = apps.map { FirebaseFunctions.getInstance(it, "us-central1") }
            val stores = apps.map { FirebaseFirestore.getInstance(it) }
            val sender = InternetMessagingClient(functions[0], auths[0], stores[0])
            val receiver = InternetMessagingClient(functions[1], auths[1], stores[1])
            CompactInboxState.start(context)
            withContext(Dispatchers.IO) {
                val connection = URL("http://10.0.2.2:31234/android").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.outputStream.use { it.write(JSONObject().put("uids", org.json.JSONArray(uids)).put("installationId", MessagingInstallation.id()).toString().toByteArray()) }
                check(connection.responseCode == 200); connection.disconnect()
            }
            assertFalse("Dedicated test device starts in normal mode", DisasterTrafficController.saving.value)
            // Old released request shape remains accepted. Remove synthetic tokens before sending.
            val legacyToken = "live-legacy-$run"
            functions[1].getHttpsCallable("registerPushToken").call(mapOf("token" to legacyToken, "platform" to "android")).await()
            receiver.unregisterPushToken(legacyToken)
            val modernToken = "live-modern-$run"
            receiver.registerPushToken(modernToken); receiver.unregisterPushToken(modernToken)
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val contact = Contact(name="Live test receiver",aesKey="",sessionCode="live-$run",peerUid=receiverUid,peerPublicKey=MessagingKeyCodec.encodePublicKey(keys.public))
            val probe = context.getSharedPreferences("crisisconnect_signal_probe",Context.MODE_PRIVATE)
            probe.edit().putLong(receiverUid,System.currentTimeMillis()).commit()
            val transport = InternetChatTransport(context,sender)
            val normalId = "live-android-normal-$run"
            sentIds.add(normalId)
            assertTrue(transport.sendText(normalId,"normal live encrypted",System.currentTimeMillis(),contact))
            val normalDoc = stores[1].collection("messages").document(normalId).get().await()
            assertEquals(senderUid,normalDoc.getString("senderUid"))
            val normalSealed = SealedMessage(normalDoc.getString("ephemeralPubKey")!!,normalDoc.getString("nonce")!!,normalDoc.getString("ciphertext")!!,normalDoc.getString("alg")!!)
            assertEquals("normal live encrypted",E2eEnvelope.open(normalSealed,keys.private,MessagingIdentity(context).keyPair().public,senderUid,receiverUid,normalDoc.getString("conversationId")!!).text)
            receiver.acknowledgeMessage(normalId)
            DisasterTrafficController.setEmergency(true)
            assertTrue("SOS activates automatic conservation",DisasterTrafficController.saving.value)
            receiver.compactInbox(null) // Publish the device-scoped five-minute lease.
            assertFalse(transport.sendTypingSignal(contact))
            assertFalse(transport.sendCallSignal(contact,"{\"type\":\"offer\"}"))
            assertFalse(transport.sendText("live-rejected-$run","x".repeat(1025),System.currentTimeMillis(),contact))
            val conversation = InternetConversation.pairId(senderUid,receiverUid)
            incomingConversation=conversation
            val peerKeys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            saveContact(context,Contact(name="Live test sender $run",aesKey="",sessionCode=conversation,peerUid=senderUid,peerPublicKey=MessagingKeyCodec.encodePublicKey(peerKeys.public)))
            for (template in listOf(0,202)) {
                val id="live-android-inbound-$template-$run~${System.currentTimeMillis()}"
                sentIds.add(id)
                val text=if(template==202) "Live isolated SOS" else "compact live encrypted"
                val sealed=E2eEnvelope.seal(MessageContent(templateCode=template,text=text),MessagingIdentity(context).keyPair().public,peerKeys.private,senderUid,receiverUid,conversation)
                sender.relayMessage(OutgoingEnvelope(id,conversation,receiverUid,if(template==202) "high" else "normal",System.currentTimeMillis(),86_400_000,sealed))
                val page=receiver.compactInbox(null)
                assertTrue("Compact inbox returns original '~' ID",page.messages.any { it["messageId"]==id })
                assertNotNull(receiveInternetMessage(context,conversation,senderUid,receiverUid,id,IncomingCiphertext.V1(sealed),receiver))
                assertEquals(text,AppDatabase.getInstance(context).messageDao().getMessageByUuid(id)?.text)
                assertTrue("Acknowledgement stays queued until batching",CompactInboxState.pending(receiverUid).contains(id))
                assertFalse("Batch ACK deletes the server copy",receiver.compactInbox(null).messages.any { it["messageId"]==id })
            }
            // Sending from the actual transport now takes the callable path and retains its ID.
            val savingId="live-android-saving-$run~${System.currentTimeMillis()}"
            sentIds.add(savingId)
            assertTrue(transport.sendText(savingId,"saving live encrypted",System.currentTimeMillis(),contact))
            assertTrue(receiver.compactInbox(null).messages.any { it["messageId"]==savingId })
            receiver.acknowledgeMessage(savingId); receiver.compactInbox(null)
            DisasterTrafficController.setEmergency(false)
            assertTrue("Recovery grace prevents mode flapping",DisasterTrafficController.saving.value)
            withTimeout(325_000) { while(DisasterTrafficController.saving.value) delay(1_000) }
            assertFalse(DisasterTrafficController.saving.value)
            receiver.compactInbox(null) // Clears the lease and restores ordinary server delivery.
            val recoveredId="live-android-recovered-$run"
            sentIds.add(recoveredId)
            assertTrue(transport.sendText(recoveredId,"recovered normal encrypted",System.currentTimeMillis(),contact))
            assertTrue(receiver.compactInbox(null).messages.any { it["messageId"]==recoveredId })
            receiver.acknowledgeMessage(recoveredId)
            assertFalse(receiver.compactInbox(null).messages.any { it["messageId"]==recoveredId })
            probe.edit().remove(receiverUid).commit()
            android.util.Log.i("DisasterLiveE2E","PASS normal/direct + automatic SOS + compact/relay + local persistence + batched ACK + 300s recovery")
        } finally {
            DisasterTrafficController.setEmergency(false)
            incomingConversation?.let { deleteContact(context,it); clearInternetMessageNotification(context,it) }
            for(auth in auths) runCatching { auth.currentUser?.delete()?.await() }
            apps.forEach { it.delete() }
        }
    }
}
