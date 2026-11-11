package com.auralis.crisisconnect

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in: only fresh cc-onprem-acceptance-* users in a disposable emulator. */
@RunWith(AndroidJUnit4::class)
class OnpremCentralPushAcceptanceTest {
    @Test
    fun receiveCentralAuthorityWake() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixtureFile = File(context.filesDir, "onprem-acceptance.json")
        val fixture = JSONObject(fixtureFile.readText())
        require(fixture.getString("uid").startsWith("cc-onprem-acceptance-"))
        require(fixture.getString("confirm") == "TEMPORARY_TEST_ACCOUNT_ONLY")
        val auth = FirebaseAuth.getInstance()
        when (InstrumentationRegistry.getArguments().getString("acceptancePhase")) {
            "prepare", "receive" -> {
                val result = Tasks.await(auth.signInWithEmailAndPassword(
                    fixture.getString("email"), fixture.getString("password")), 60, TimeUnit.SECONDS)
                require(result.user?.uid == fixture.getString("uid"))
                val token = Tasks.await(FirebaseMessaging.getInstance().token, 90, TimeUnit.SECONDS)
                File(context.filesDir, "onprem-push-token.json").writeText(JSONObject().put("token", token).toString())
                context.getSystemService(NotificationManager::class.java).cancelAll()
                if (InstrumentationRegistry.getArguments().getString("acceptancePhase") == "receive") {
                    File(context.filesDir, "onprem-push-ready").writeText("ready")
                    val manager = context.getSystemService(NotificationManager::class.java)
                    val deadline = System.currentTimeMillis() + 120_000
                    while (manager.activeNotifications.none { it.id == fixture.getString("conversationId").hashCode() } &&
                        System.currentTimeMillis() < deadline) Thread.sleep(1000)
                    assertTrue("Real central FCM wake did not create the Authority notification",
                        manager.activeNotifications.any { it.id == fixture.getString("conversationId").hashCode() })
                }
            }
            "verify" -> {
                require(auth.currentUser?.uid == fixture.getString("uid"))
                val manager = context.getSystemService(NotificationManager::class.java)
                val deadline = System.currentTimeMillis() + 90_000
                while (manager.activeNotifications.none { it.id == fixture.getString("conversationId").hashCode() } &&
                    System.currentTimeMillis() < deadline) Thread.sleep(1000)
                assertTrue("Real central FCM wake did not create the Authority notification",
                    manager.activeNotifications.any { it.id == fixture.getString("conversationId").hashCode() })
            }
            "cleanup" -> {
                Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 30, TimeUnit.SECONDS)
                auth.signOut()
                context.getSystemService(NotificationManager::class.java).cancelAll()
                File(context.filesDir, "onprem-push-token.json").delete()
                File(context.filesDir, "onprem-push-ready").delete()
                fixtureFile.delete()
            }
            else -> error("Explicit acceptancePhase required")
        }
    }
}
