package com.auralis.crisisconnect.wear

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.auralis.crisisconnect.watch.*
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real watch buttons/model; host changes only the separate phone process. */
class WatchChatLifecycleTest {
    @get:Rule val ui=createEmptyComposeRule()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val args get()=InstrumentationRegistry.getArguments()
    private val run get()=args.getString("chat_run")!!
    private val commands=java.util.Collections.synchronizedList(mutableListOf<WatchCommand>())
    private val checks=JSONArray()
    private fun phase(name:String) { File(context.filesDir,"watch-chat-phase.json").writeText(JSONObject().put("run",run).put("phase",name).toString()) }
    private fun host(name:String) {
        val file=File(context.filesDir,"chat-$run-$name");val until=System.currentTimeMillis()+60000
        while(!file.exists() && System.currentTimeMillis()<until)Thread.sleep(100)
        assertTrue("Missing host phase $name",file.exists())
    }
    private fun waitText(text:String,timeout:Long=20000)=ui.waitUntil(timeout) {ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    private fun click(text:String) {
        ui.waitUntil(15000) {ui.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        val node=ui.onNode(hasText(text) and hasClickAction());if(runCatching {node.assertIsDisplayed()}.isFailure)node.performScrollTo();node.performClick()
    }
    private fun replies()=synchronized(commands) {commands.filter {it.action==WatchAction.quickReply}}
    private fun check(name:String) {checks.put(name)}
    private fun snap(name:String) {
        ui.onNodeWithText("Crisis Connect").performScrollTo();ui.waitForIdle()
        val dir=File(context.getExternalFilesDir(null),"watch-chat-lifecycle").apply {mkdirs()}
        assertTrue(UiDevice.getInstance(ins).takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun backgroundLostReceiptClosedPhoneAndReconnect() {
        val token=args.getString("dev_link_token")!!
        DevLinkTransport.commandObserver={commands.add(it)}
        try {
            ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("dev_link_token",token)).use { scenario ->
                click("İyiyim");waitText("Cevap telefondaki test alıcısına ulaştı.")
                check("foreground_reply_accepted")
                phase("background_ready");host("background")
                click("Yaralıyım");waitText("İşlem doğrulanamadı. Tekrar denemeden durumu yenile.")
                check("background_reply_remains_uncertain")
                phase("drop_ready");host("drop")
                click("Yardıma ihtiyacım var")
                waitText("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon yakında olmalı.")
                assertEquals(3,replies().size);check("lost_receipt_does_not_claim_success")
                snap("01-receipt-lost")
                click("Durumu yenile");waitText("Telefon yeniden bağlandı. Durum güncellendi.")
                phase("kill_ready");host("dead")
                // Fresh cached state permits one user attempt; the transport must fail safely.
                click("İyiyim")
                waitText("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon yakında olmalı.")
                ui.onNode(hasText("İyiyim") and hasClickAction()).assertIsNotEnabled()
                assertEquals(4,replies().size);check("closed_phone_attempt_unconfirmed_and_controls_disabled")
                snap("02-phone-closed")
                phase("offline_verified");host("reopened")
                waitText("Telefon yeniden bağlandı. Durum güncellendi.",30000)
                Thread.sleep(17000) // Allow another automatic status poll, never a reply retry.
                assertEquals(4,replies().size);check("reconnect_and_status_poll_do_not_replay_replies")
                val now=System.currentTimeMillis()
                val duplicate=replies()[2].copy(issuedAt=now,expiresAt=now+30000)
                assertEquals("noPeer",runBlocking {DevLinkTransport(token).exchange(duplicate)}.result)
                check("explicit_duplicate_keeps_original_failure")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                waitText("Telefon bağlı");Thread.sleep(1500)
                assertEquals(5,replies().size);check("watch_resume_does_not_replay_replies")
                snap("03-reconnected")
                phase("verify_rows");host("verified")
                File(context.filesDir,"watch-chat-lifecycle-results.json").writeText(JSONObject().put("passed",true).put("checks",checks)
                    .put("replyAttempts",JSONArray(replies().map {JSONObject(it.encode().toString(Charsets.UTF_8))})).toString(2))
                phase("finished")
            }
        } finally {DevLinkTransport.commandObserver=null}
    }
}
