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
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchRecoveryTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = ins.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val run get() = requireNotNull(args.getString("recovery_run"))
    private val checks = mutableListOf<String>()
    private fun phase(name: String) {
        File(context.filesDir,"watch-recovery-phase.json").writeText(JSONObject().put("run",run).put("phase",name).toString())
    }
    private fun host(name: String) {
        val f=File(context.filesDir,"recovery-$run-$name")
        val until=System.currentTimeMillis()+60000
        while(!f.exists() && System.currentTimeMillis()<until) Thread.sleep(200)
        assertTrue("Host missing: $name", f.exists())
    }
    private fun waitText(text: String, timeout: Long=20000) = ui.waitUntil(timeout) { ui.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun click(text: String) {
        ui.waitUntil(15000) { ui.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        val node=ui.onNode(hasText(text) and hasClickAction())
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.performClick()
    }
    private fun snap(name: String) {
        ui.onNodeWithText("Crisis Connect").performScrollTo(); ui.waitForIdle()
        val device=UiDevice.getInstance(ins); device.waitForIdle()
        val dir=File(context.getExternalFilesDir(null),"watch-recovery-ui").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun reconnectResumeSameSessionAndEnd() {
        val token=requireNotNull(args.getString("dev_link_token"))
        val transport=DevLinkTransport(token)
        fun status()=runBlocking { transport.exchange(WatchCommand(action=WatchAction.status)).state }
        ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).putExtra("dev_link_token",token)).use {
            waitText("SOS kayıtlı · duraklatıldı")
            val saved=status(); assertTrue(saved.recoveryRequired); assertFalse(saved.sosActive)
            assertEquals("1791792000123",saved.emergencyID); assertEquals("Yaralıyım",saved.lastMessage)
            checks.add("saved_session_and_reply_visible")
            snap("01-saved-session")
            phase("kill_ready");host("dead")
            click("Durumu yenile");waitText("Son bilinen: SOS duraklatıldı")
            ui.onNodeWithText("Telefonda devam et").assertIsNotEnabled()
            checks.add("closed_phone_is_stale_and_actions_disabled")
            phase("offline");host("reopened")
            waitText("Telefon yeniden bağlandı. Durum güncellendi.",30000)
            waitText("SOS kayıtlı · duraklatıldı")
            assertEquals(saved.emergencyID,status().emergencyID); assertFalse(status().sosActive)
            checks.add("automatic_reconnect_keeps_paused_session")
            snap("02-reconnected-paused")
            click("Telefonda devam et")
            waitText("Kayıtlı SOS’a devam etmek için telefonda onay ver. Yayın henüz yeniden başlamadı.")
            assertTrue(status().recoveryRequired); assertFalse(status().sosActive)
            checks.add("watch_request_does_not_resume_without_phone_confirmation")
            phase("approve");host("approved")
            waitText("Telefon SOS’un başladığını doğruladı.",30000)
            val active=status();assertTrue(active.sosActive);assertFalse(active.recoveryRequired)
            assertEquals(saved.emergencyID,active.emergencyID)
            checks.add("phone_confirmation_resumes_same_session")
            snap("03-same-session-active")
            click("SOS’u bitir");click("SOS’u bitir")
            waitText("Telefon SOS’un bittiğini doğruladı.")
            assertFalse(status().sosActive);assertFalse(status().recoveryRequired)
            checks.add("watch_stop_clears_phone_session")
            phase("ended");host("reopened_off")
            click("Durumu yenile");waitText("SOS kapalı")
            val off=status();assertFalse(off.sosActive);assertFalse(off.recoveryRequired);assertEquals("",off.emergencyID)
            assertEquals("Yaralıyım",off.lastMessage)
            checks.add("phone_relaunch_stays_off_and_keeps_replies")
            snap("04-off-after-phone-relaunch")
            File(context.filesDir,"watch-recovery-results.json").writeText(JSONObject().put("passed",true).put("checks",org.json.JSONArray(checks)).toString(2))
            phase("finished")
        }
    }
}
