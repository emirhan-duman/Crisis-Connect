package com.auralis.crisisconnect

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import com.google.firebase.FirebaseOptions
import com.auralis.crisisconnect.data.database.LocalKeyStorage
import com.auralis.crisisconnect.messaging.*
import com.auralis.crisisconnect.security.Crypto
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctions
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Live, disposable two-platform test. It never signs out the default application's user. */
@RunWith(AndroidJUnit4::class)
class ApplicationHighAvailabilityLiveSdkTest {
    @Test fun profilesAndMlsAcrossAndroidAndIos(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveApplicationHa") == "true")
        withTimeout(600_000) {
            suspend fun fixture(body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
                val connection = URL("http://127.0.0.1:31234/android").openConnection() as HttpURLConnection
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                if (body != null) { connection.requestMethod = "POST"; connection.doOutput = true; connection.outputStream.use { it.write(body.toString().toByteArray()) } }
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }.also { connection.disconnect() }
            }
            suspend fun until(condition: suspend () -> Boolean) { while (!condition()) delay(500) }
            val config = fixture()
            val mediaRoute = RegionalRoutePolicy(initialRegion = { RegionalRoutePolicy.EUROPE })
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder(FirebaseApp.getInstance().options).setApplicationId(config.getString("appId")).build(), "application-ha-${config.getString("runId")}")!!
            val check = FirebaseAppCheck.getInstance(app)
            // The dedicated fixture registers this exact debug secret; no production provider changes.
            System.setProperty("firebaseAppCheckDebugSecret", config.getString("debugToken"))
            context.getSharedPreferences("com.google.firebase.appcheck.debug.store.${app.persistenceKey}", 0).edit()
                .putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", config.getString("debugToken")).commit()
            check.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance(), false)
            val auth = FirebaseAuth.getInstance(app)
            val store = FirebaseFirestore.getInstance(app, MessagingDatabaseTarget.DATABASE_ID)
            val legacy = FirebaseFirestore.getInstance(app)
            var session: AuthorityMlsConversationSession? = null
            suspend fun call(operation: String, payload: Map<String, Any>): Map<*, *> {
                val calls = RegionalMessagingCalls(policy = RegionalRoutePolicy(initialRegion = { RegionalRoutePolicy.EUROPE }), highAvailability = true) { region, op, data ->
                    FirebaseFunctions.getInstance(app, region).getHttpsCallable(if (region == RegionalRoutePolicy.EUROPE) "messagingGatewayHaEurope" else "messagingGatewayHaUS")
                        .apply { setTimeout(45, java.util.concurrent.TimeUnit.SECONDS) }
                        .call(mapOf("operation" to op, "payload" to data)).await().data
                }
                return calls.call(operation, payload) as Map<*, *>
            }
            try {
                check.getAppCheckToken(true).await()
                val uid = auth.signInAnonymously().await().user!!.uid
                val device = LocalKeyStorage.getOrCreateRescueDeviceId(context)
                fixture(JSONObject().put("uid", uid).put("deviceId", device))
                until { fixture().optBoolean("ready") }
                val peer = fixture().getJSONObject("peer").getString("uid")
                val rawBinding = mapOf("scopeType" to "agency", "channelId" to config.getString("panelId"), "participants" to listOf(uid, peer))
                assertEquals(false, call("resolveResilientGroup", mapOf("binding" to rawBinding, "deviceId" to device))["resilient"])
                fixture(JSONObject().put("legacyChecked", true))
                until { fixture().optBoolean("legacyReleased") }
                val binding = AuthorityMlsBinding(AuthorityMlsScopeType.AGENCY, config.getString("panelId"), listOf(uid, peer), resilient = true)
                val id = AuthorityMlsIdentifiers.conversationId(binding)
                suspend fun legacyRound(phase: String) {
                    val compatibility = binding.copy(resilient = false, routeResolved = true)
                    assertNotEquals(id, AuthorityMlsIdentifiers.conversationId(compatibility))
                    val old = AuthorityMlsConversationSession.prepare(context, uid, compatibility, "Android compatibility SDK", AuthorityMlsTransport(legacy))
                    session = old
                    fixture(JSONObject().put("${phase}Prepared", true).put("legacyGroupId", AuthorityMlsIdentifiers.conversationId(compatibility)))
                    until { fixture().getJSONObject("peer").optBoolean("${phase}Prepared") }
                    fixture(JSONObject().put("legacyStage", "$phase:directory"))
                    for (trust in old.refreshPreparation().trust) if (!trust.approved) old.approveDeviceSet(trust.uid, trust.fingerprint)
                    val received = CompletableDeferred<ByteArray>()
                    val expected = "iOS $phase ${config.getString("runId")}"
                    fixture(JSONObject().put("legacyStage", "$phase:activate"))
                    old.activate(onApplication = { if (String(it.plaintext) == expected) received.complete(it.plaintext) })
                    fixture(JSONObject().put("legacyStage", "$phase:ready"))
                    until { old.isReadyToSend() }
                    fixture(JSONObject().put("legacyStage", "$phase:send"))
                    old.sendApplication("Android $phase ${config.getString("runId")}".toByteArray())
                    assertEquals(expected, String(received.await()))
                    fixture(JSONObject().put("${phase}Received", true))
                    until { fixture().getJSONObject("peer").optBoolean("${phase}Received") }
                    old.close(); session = null
                }
                legacyRound("legacyBefore")
                store.document("users/$uid").update("username", "HA Android").await()
                until { legacy.document("users/$uid").get(Source.SERVER).await().getString("username") == "HA Android" }
                legacy.document("users/$uid").update("country", "TR").await()
                until { store.document("users/$uid").get(Source.SERVER).await().getString("country") == "TR" }
                val avatar = ByteArrayOutputStream().also { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                val avatarPath = "users/$uid/avatars/${UUID.randomUUID()}.jpg"
                val upload = JSONObject(String(ResilientMediaClient.request(avatarPath, "PUT", avatar, app, mediaRoute)))
                store.document("users/$uid").update("photoURL", upload.getString("downloadUrl")).await()
                until { legacy.document("users/$uid").get(Source.SERVER).await().getString("photoURL") == upload.getString("downloadUrl") }
                val roster = call("listAuthorityRoster", mapOf("agencySlug" to config.getString("panelId")))
                assertTrue((roster["members"] as List<*>).any { (it as Map<*, *>)["uid"] == peer })
                legacy.disableNetwork().await()
                until { fixture().optBoolean("bootstrapOutageArmed") }
                val unavailable = runCatching { call("resolveResilientGroup", mapOf("binding" to rawBinding, "deviceId" to device)) }.exceptionOrNull()
                assertTrue(unavailable is com.google.firebase.functions.FirebaseFunctionsException && unavailable.code == com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE)
                fixture(JSONObject().put("legacyOutageRejected", true))
                until { fixture().getJSONObject("peer").optBoolean("legacyOutageRejected") }
                val route = call("openResilientGroup", mapOf("binding" to rawBinding, "deviceId" to device))
                assertEquals(id, route["conversationId"])
                assertEquals("separate-session", route["compatibility"])
                assertEquals(false, route["legacyDelivery"])
                assertTrue(AuthorityMlsIdentifiers.legacyConversationIds(binding).isEmpty())
                val transport = AuthorityMlsTransport(store)
                session = AuthorityMlsConversationSession.prepare(context, uid, binding, "Android HA SDK", transport)
                fixture(JSONObject().put("prepared", true).put("groupId", id).put("avatarPath", avatarPath))
                until { fixture().optBoolean("allPrepared") }
                val active = session!!
                for (trust in active.refreshPreparation().trust) if (!trust.approved) active.approveDeviceSet(trust.uid, trust.fingerprint)
                val inbox = CompletableDeferred<ByteArray>()
                active.activate(onApplication = { inbox.complete(it.plaintext) })
                until { active.isReadyToSend() }
                val path = "authorityMessageAttachments/$id/$uid/${UUID.randomUUID()}"
                val key = Crypto.randomBytes(32)
                val nonce = Crypto.randomBytes(12)
                val plain = "Android attachment ${config.getString("runId")}".toByteArray()
                val cipher = Crypto.aesGcmEncrypt(key, nonce, plain, path.toByteArray())
                ResilientMediaClient.request(path, "PUT", cipher, app, mediaRoute)
                ResilientMediaClient.request(path, "PUT", cipher, app, mediaRoute) // Immutable retry, no second object.
                val conflict = runCatching { ResilientMediaClient.request(path, "PUT", "conflicting bytes".toByteArray(), app, mediaRoute) }.exceptionOrNull()
                assertTrue(conflict?.message?.contains("409") == true)
                val other = runCatching { ResilientMediaClient.request("authorityMessageAttachments/$id/$peer/${UUID.randomUUID()}", "PUT", cipher, app, mediaRoute) }.exceptionOrNull()
                assertTrue(other?.message?.contains("403") == true)
                val attachment = ChannelAttachment(path, Base64.getEncoder().encodeToString(nonce), Base64.getEncoder().encodeToString(key), "canary.txt", "text/plain", plain.size.toLong())
                val payload = AuthorityMlsMessagePayload(peer, "iOS", "Android", "Android HA ${config.getString("runId")}", System.currentTimeMillis(), listOf(attachment))
                active.sendApplication(AuthorityMlsMessagePayloadCodec.encode(payload))
                val received = AuthorityMlsMessagePayloadCodec.decode(inbox.await())
                assertEquals(uid, received.recipientUid)
                assertEquals("iOS HA ${config.getString("runId")}", received.text)
                val file = received.attachments.single()
                val download = ResilientMediaClient.request(file.path, "GET", app = app, route = mediaRoute)
                val opened = Crypto.aesGcmDecrypt(Base64.getDecoder().decode(file.key), Base64.getDecoder().decode(file.nonce), download, file.path.toByteArray())
                assertEquals("iOS attachment ${config.getString("runId")}", String(opened))
                fixture(JSONObject().put("received", true).put("mediaPath", path))
                until { fixture().optBoolean("allReceived") }
                active.close(); session = null
                legacy.enableNetwork().await()
                legacyRound("legacyAfter")
                FirebaseFunctions.getInstance(app, "us-central1").getHttpsCallable("deleteAccountAndData").call().await()
                fixture(JSONObject().put("status", "passed").put("cleanupComplete", true))
            } catch (error: Throwable) {
                runCatching { fixture(JSONObject().put("status", "failed").put("errorType", error.javaClass.simpleName)) }
                throw error
            } finally {
                runCatching { session?.close() }
                runCatching { auth.currentUser?.delete()?.await() }
                store.terminate().await(); legacy.terminate().await(); app.delete()
            }
        }
    }
}
