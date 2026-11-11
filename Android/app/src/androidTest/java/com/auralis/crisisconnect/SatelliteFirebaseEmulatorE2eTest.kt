package com.auralis.crisisconnect

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.data.AppDatabase
import com.auralis.crisisconnect.data.Contact
import com.auralis.crisisconnect.messaging.E2eEnvelope
import com.auralis.crisisconnect.messaging.IncomingCiphertext
import com.auralis.crisisconnect.messaging.InternetConversation
import com.auralis.crisisconnect.messaging.InternetChatTransport
import com.auralis.crisisconnect.messaging.InternetMessagingClient
import com.auralis.crisisconnect.messaging.clearInternetMessageNotification
import com.auralis.crisisconnect.messaging.MessageContent
import com.auralis.crisisconnect.messaging.MessagingIdentity
import com.auralis.crisisconnect.messaging.MessagingKeyCodec
import com.auralis.crisisconnect.messaging.OutgoingEnvelope
import com.auralis.crisisconnect.messaging.SealedMessage
import com.auralis.crisisconnect.messaging.postInternetMessageNotification
import com.auralis.crisisconnect.messaging.receiveInternetMessage
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Full Android SDK smoke through the emulator's UID-bound SATELLITE fallback network.
 * Run only with Firebase Local Emulator Suite and --project demo-crisis-connect; this test never
 * points its Auth, Firestore, or Functions clients at the configured production Firebase project.
 */
@RunWith(AndroidJUnit4::class)
class SatelliteFirebaseEmulatorE2eTest {
    @Test
    fun satelliteSdkSendsE2eChatAndReportsThenResolvesSos() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val notificationPermissionWasGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionWasGranted) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivity.activeNetwork
            ?: throw AssertionError("No app-default network is assigned")
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork)
        assertNotNull("No capabilities for app-default network", capabilities)
        assertTrue(
            "Test app must be assigned Android's simulated satellite transport",
            capabilities!!.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)
        )
        val firestoreAddresses = withContext(Dispatchers.IO) {
            activeNetwork.getAllByName("firestore.googleapis.com")
        }
        assertTrue(
            "The app UID could not resolve Firebase's Firestore hostname on satellite",
            firestoreAddresses.isNotEmpty()
        )

        // Disable only the default app's Analytics collection for this test process so startup
        // lifecycle/presence work cannot pollute the real project's Analytics stream.
        val analytics = FirebaseAnalytics.getInstance(context)
        analytics.setAnalyticsCollectionEnabled(false)

        val baseOptions = FirebaseApp.getInstance().options
        val emulatorOptions = FirebaseOptions.Builder()
            .setApplicationId(baseOptions.applicationId!!)
            .setApiKey(baseOptions.apiKey!!)
            .setProjectId(EMULATOR_PROJECT_ID)
            .setGcmSenderId(baseOptions.gcmSenderId ?: "1234567890")
            .build()
        val suffix = UUID.randomUUID().toString().replace("-", "")
        val senderApp = FirebaseApp.initializeApp(context, emulatorOptions, "satellite-sender-$suffix")
        val receiverApp = FirebaseApp.initializeApp(context, emulatorOptions, "satellite-receiver-$suffix")
        var temporaryContactSession: String? = null
        var temporaryMessageId: String? = null
        try {
            val senderAuth = FirebaseAuth.getInstance(senderApp).apply {
                useEmulator(EMULATOR_HOST, AUTH_PORT)
            }
            val receiverAuth = FirebaseAuth.getInstance(receiverApp).apply {
                useEmulator(EMULATOR_HOST, AUTH_PORT)
            }
            val senderUid = senderAuth.signInAnonymously().await().user!!.uid
            val receiverUid = receiverAuth.signInAnonymously().await().user!!.uid
            val firestore = FirebaseFirestore.getInstance(senderApp).apply {
                useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            }
            val receiverFirestore = FirebaseFirestore.getInstance(receiverApp).apply {
                useEmulator(EMULATOR_HOST, FIRESTORE_PORT)
            }
            val functions = FirebaseFunctions.getInstance(senderApp, FUNCTIONS_REGION).apply {
                useEmulator(EMULATOR_HOST, FUNCTIONS_PORT)
            }
            val receiverFunctions = FirebaseFunctions.getInstance(receiverApp, FUNCTIONS_REGION).apply {
                useEmulator(EMULATOR_HOST, FUNCTIONS_PORT)
            }
            val senderClient = InternetMessagingClient(functions, senderAuth, firestore)
            val receiverClient = InternetMessagingClient(receiverFunctions, receiverAuth, receiverFirestore)

            // The receiver's ephemeral test identity lets this test prove the stored payload is
            // genuinely E2E-encrypted to the intended peer and can be opened by that peer.
            val receiverKeyGenerator = KeyPairGenerator.getInstance("EC")
            receiverKeyGenerator.initialize(ECGenParameterSpec("secp256r1"))
            val receiverKeyPair = receiverKeyGenerator.generateKeyPair()
            val contact = Contact(
                name = "Satellite emulator receiver",
                aesKey = "",
                sessionCode = "satellite-emulator",
                peerUid = receiverUid,
                peerPublicKey = MessagingKeyCodec.encodePublicKey(receiverKeyPair.public)
            )
            val messageId = "satellite-test-$suffix"
            val text = "satellite route E2E $suffix"
            val signalProbePrefs = context.getSharedPreferences(
                "crisisconnect_signal_probe",
                Context.MODE_PRIVATE
            )
            // Keep this test on the existing v1 compatibility path; no key bundle is published
            // for the disposable receiver, so an unnecessary callable probe would add noise.
            signalProbePrefs.edit().putLong(receiverUid, System.currentTimeMillis()).commit()
            val senderIdentity = MessagingIdentity(context)
            val receivedByPeer = CompletableDeferred<MessageContent>()
            val receiveListener = receiverFirestore.collection("messages")
                .whereEqualTo("recipientUid", receiverUid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        receivedByPeer.completeExceptionally(error)
                    } else {
                        snapshot?.documents?.firstOrNull { it.id == messageId }?.let { document ->
                            runCatching {
                                E2eEnvelope.open(
                                    sealed = SealedMessage(
                                        ephemeralPublicKey = document.getString("ephemeralPubKey")!!,
                                        nonce = document.getString("nonce")!!,
                                        ciphertext = document.getString("ciphertext")!!,
                                        alg = document.getString("alg")!!
                                    ),
                                    recipientPrivateKey = receiverKeyPair.private,
                                    senderStaticPublicKey = senderIdentity.keyPair().public,
                                    senderUid = senderUid,
                                    recipientUid = receiverUid,
                                    conversationId = document.getString("conversationId")!!
                                )
                            }.onSuccess { receivedByPeer.complete(it) }
                                .onFailure { receivedByPeer.completeExceptionally(it) }
                        }
                    }
                }
            val transport = InternetChatTransport(
                context,
                senderClient
            )
            assertTrue(
                "InternetChatTransport must accept the small message through the satellite route",
                transport.sendText(
                    messageId = messageId,
                    text = text,
                    createdAtMs = System.currentTimeMillis(),
                    contact = contact
                )
            )
            val peerContent = withTimeout(15_000) { receivedByPeer.await() }
            assertEquals("The recipient's Firestore listener must receive and decrypt the message", text, peerContent.text)

            val messageRef = firestore.collection("messages").document(messageId)
            var message = messageRef.get().await()
            repeat(20) {
                if (message.getBoolean("noTokens") == true) return@repeat
                kotlinx.coroutines.delay(250)
                message = messageRef.get().await()
            }
            assertEquals(senderUid, message.getString("senderUid"))
            assertEquals(receiverUid, message.getString("recipientUid"))
            assertEquals(true, message.getBoolean("noTokens"))
            receiveListener.remove()
            signalProbePrefs.edit().remove(receiverUid).commit()

            // Exercise the exact shared receiver used by FCM and the Firestore listener: decrypt,
            // file the plaintext into the local conversation, ack through the local Functions
            // Emulator, and send the delivery receipt through the local Firestore Emulator.
            val incomingConversationId = InternetConversation.pairId(senderUid, receiverUid)
            val incomingMessageId = "satellite-inbound-$suffix"
            val incomingText = "satellite inbound receiver pipeline $suffix"
            val incomingIdentity = MessagingIdentity(context)
            val incomingSenderKeyGenerator = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }
            val incomingSenderIdentity = incomingSenderKeyGenerator.generateKeyPair()
            val incomingContact = Contact(
                name = "Satellite emulator sender",
                aesKey = "",
                sessionCode = incomingConversationId,
                peerUid = senderUid,
                peerPublicKey = MessagingKeyCodec.encodePublicKey(incomingSenderIdentity.public)
            )
            temporaryContactSession = incomingConversationId
            temporaryMessageId = incomingMessageId
            com.auralis.crisisconnect.data.saveContact(context, incomingContact)
            val incomingSealed = E2eEnvelope.seal(
                content = MessageContent(templateCode = 0, text = incomingText),
                recipientPublicKey = incomingIdentity.keyPair().public,
                senderPrivateKey = incomingSenderIdentity.private,
                senderUid = senderUid,
                recipientUid = receiverUid,
                conversationId = incomingConversationId
            )
            senderClient.relayMessage(
                OutgoingEnvelope(
                    messageId = incomingMessageId,
                    conversationId = incomingConversationId,
                    recipientUid = receiverUid,
                    priority = "normal",
                    createdAtMs = System.currentTimeMillis(),
                    ttlMs = 60_000,
                    sealed = incomingSealed
                )
            )
            val received = receiveInternetMessage(
                context = context,
                conversationId = incomingConversationId,
                senderUid = senderUid,
                myUid = receiverUid,
                messageId = incomingMessageId,
                payload = IncomingCiphertext.V1(incomingSealed),
                messagingClient = receiverClient
            )
            assertNotNull("The shared FCM/Firestore receiver must accept the authenticated message", received)
            assertEquals(incomingText, received!!.display)
            assertEquals(incomingConversationId, received.sessionCode)
            val savedIncoming = AppDatabase.getInstance(context).messageDao()
                .getMessageByUuid(incomingMessageId)
            assertNotNull("The received plaintext must be persisted to the local chat", savedIncoming)
            assertEquals(incomingText, savedIncoming!!.text)
            postInternetMessageNotification(
                context = context,
                conversationId = incomingConversationId,
                title = received.senderName,
                body = received.display,
                sessionCode = received.sessionCode,
                // Leave null to avoid reading a photo from the configured production Firestore.
                senderUid = null
            )
            val incomingNotificationId = received.sessionCode.hashCode()
            assertTrue(
                "A decrypted incoming message should post a chat notification when permission is granted",
                NotificationManagerCompat.from(context).activeNotifications.any {
                    it.id == incomingNotificationId
                }
            )
            clearInternetMessageNotification(context, received.sessionCode)

            val signalId = "cc-${suffix.take(24)}"
            val now = System.currentTimeMillis()
            val idToken = senderAuth.currentUser!!.getIdToken(false).await().token!!
            val active = invokeCallable(
                idToken,
                mapOf(
                    "signalId" to signalId,
                    "status" to "active",
                    "lat" to 41.0082,
                    "lon" to 28.9784,
                    "capturedAtMillis" to now,
                    "provider" to "local-emulator-test",
                    "accuracyMeters" to 10,
                    "batteryPercent" to 67,
                    "countryIso" to "TR"
                )
            )
            assertEquals("active", active["status"])
            assertEquals("afad", active["panelId"])

            val resolved = invokeCallable(
                idToken,
                mapOf("signalId" to signalId, "status" to "resolved", "countryIso" to "TR")
            )
            assertEquals("resolved", resolved["status"])
        } finally {
            temporaryMessageId?.let { messageId ->
                AppDatabase.getInstance(context).openHelper.writableDatabase.execSQL(
                    "DELETE FROM messages WHERE messageUuid = ?",
                    arrayOf(messageId)
                )
            }
            temporaryContactSession?.let { sessionCode ->
                AppDatabase.getInstance(context).contactDao().deleteBySessionCode(sessionCode)
            }
            // A listener belongs to the receiver app session and is removed above after delivery.
            senderApp.delete()
            receiverApp.delete()
            analytics.setAnalyticsCollectionEnabled(true)
        }
    }

    private suspend fun invokeCallable(idToken: String, data: Map<String, Any>): Map<*, *> =
        withContext(Dispatchers.IO) {
            val connection = URL(
                "http://$EMULATOR_HOST:$FUNCTIONS_PORT/$EMULATOR_PROJECT_ID/" +
                    "$FUNCTIONS_REGION/reportSosSignal"
            ).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer $idToken")
                // This emulator-only test token is accepted by Functions Emulator's App Check
                // verifier and is sent only to the local Android emulator gateway.
                connection.setRequestProperty("X-Firebase-AppCheck", "local-emulator-test-token")
                connection.outputStream.use { output ->
                    output.write(JSONObject().put("data", JSONObject(data)).toString().toByteArray())
                }
                val statusCode = connection.responseCode
                val stream = if (statusCode < 400) connection.inputStream else connection.errorStream
                val body = stream.bufferedReader().use { it.readText() }
                check(statusCode == 200) { "reportSosSignal emulator returned $statusCode: $body" }
                val response = JSONObject(body)
                val result = response.optJSONObject("result") ?: response.optJSONObject("data")
                    ?: error("Callable returned no result: $body")
                result.keys().asSequence().associateWith { result.get(it) }
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val EMULATOR_PROJECT_ID = "demo-crisis-connect"
        const val EMULATOR_HOST = "10.0.2.2"
        const val AUTH_PORT = 9099
        const val FUNCTIONS_PORT = 5001
        const val FIRESTORE_PORT = 8080
        const val FUNCTIONS_REGION = "us-central1"
    }
}
