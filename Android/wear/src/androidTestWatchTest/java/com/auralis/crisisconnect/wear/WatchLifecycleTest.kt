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

/** The host changes ONLY the phone's lifecycle; this watch process remains running throughout. */
class WatchLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val run get() = requireNotNull(args.getString("lifecycle_run"))
    private val checks = JSONArray()
    private fun note(name: String, state: PhoneWatchState? = null) {
        checks.put(JSONObject().put("name", name).put("passed", true).put("state", state?.json()))
        File(context.filesDir, "watch-lifecycle-results.json").writeText(JSONObject().put("checks", checks).toString(2))
    }
    private fun phase(name: String) {
        File(context.filesDir, "watch-lifecycle-phase.json").writeText(JSONObject().put("run", run).put("phase", name).toString())
    }
    private fun host(name: String) {
        val signal = File(context.filesDir, "lifecycle-$run-$name")
        val end = System.currentTimeMillis() + 60_000
        while (!signal.exists() && System.currentTimeMillis() < end) Thread.sleep(200)
        assertTrue("Host did not reach $name", signal.exists())
    }
    private fun waitFor(text: String, timeout: Long = 15_000) = compose.waitUntil(timeout) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun enabled(text: String) = compose.waitUntil(15_000) {
        compose.onAllNodes(hasText(text) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun click(text: String) {
        enabled(text)
        val node = compose.onNode(hasText(text) and hasClickAction())
        // Confirmation screens intentionally have no scroll container.
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.performClick()
    }
    private fun snap(name: String) {
        val dir = File(context.getExternalFilesDir(null), "watch-lifecycle-ui").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun phoneBackgroundDeathAndReopen() {
        val token = requireNotNull(args.getString("dev_link_token"))
        val transport = DevLinkTransport(token)
        fun exchange(command: WatchCommand): SimulatedWatchReceipt = runBlocking { transport.exchange(command) }
        fun status() = exchange(WatchCommand(action = WatchAction.status)).state
        phase("starting")
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("dev_link_token", token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
            click("SOS başlat"); waitFor("SOS aktif")
            val foreground = status(); assertTrue(foreground.sosActive)
            note("foreground_start", foreground)
            click("İyiyim"); waitFor("Cevap telefondaki test alıcısına ulaştı.")
            assertEquals("İyiyim", status().lastMessage)
            note("foreground_reply")
            phase("background_ready"); host("background")
            assertEquals(foreground.emergencyID, status().emergencyID)
            note("background_preserves_session")
            click("Yaralıyım")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Yaralıyım") and !hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Yaralıyım", status().lastMessage); note("background_reply")
            click("SOS’u bitir"); click("SOS’u bitir"); waitFor("Telefon SOS’un bittiğini doğruladı.")
            assertFalse(status().sosActive); note("background_stop")
            click("SOS başlat"); waitFor("SOS aktif")
            val active = status(); assertTrue(active.sosActive)
            assertNotEquals(foreground.emergencyID, active.emergencyID)
            note("background_start", active)
            click("Yardıma ihtiyacım var")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Yardıma ihtiyacım var") and !hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Yardıma ihtiyacım var", status().lastMessage); note("background_third_reply")
            val repeat = WatchCommand(action = WatchAction.quickReply, peerID = active.peerID, reply = QuickReply.safe)
            assertEquals("accepted", exchange(repeat).result)
            phase("kill_ready"); host("phone_dead")
            click("Durumu yenile")
            waitFor("Son bilinen: SOS aktif")
            compose.onNodeWithText("SOS’u bitir").assertIsNotEnabled()
            compose.onNode(hasText("İyiyim") and hasClickAction()).assertIsNotEnabled()
            compose.onNodeWithText("Crisis Connect").performScrollTo()
            snap("01-phone-closed-stale")
            note("closed_phone_shows_last_known_and_disables_actions")
            phase("offline_verified"); host("reopened")
            // No refresh click or watch relaunch: normal polling must recover automatically.
            waitFor("Telefon yeniden bağlandı. Durum güncellendi.", 25_000)
            enabled("SOS’u bitir")
            val restored = status()
            assertTrue(restored.sosActive); assertEquals(active.emergencyID, restored.emergencyID)
            assertEquals("İyiyim", restored.lastMessage)
            note("automatic_reconnect_same_emergency_and_last_reply", restored)
            snap("02-reconnected")
            assertEquals("accepted", exchange(repeat).result)
            note("duplicate_reply_after_process_death_acknowledged")
            click("Yaralıyım")
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Yaralıyım") and !hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("Yaralıyım", status().lastMessage)
            note("new_reply_after_reopen")
            // Exercise the watch lifecycle as well: retain state but require a fresh phone receipt.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            enabled("SOS’u bitir"); assertEquals(active.emergencyID, status().emergencyID)
            note("watch_background_resume")
            click("SOS’u bitir"); click("SOS’u bitir"); waitFor("Telefon SOS’un bittiğini doğruladı.")
            assertFalse(status().sosActive); note("explicit_stop")
            phase("stopped_ready"); host("reopened_off")
            val ended = status(); assertFalse(ended.sosActive); assertTrue(ended.emergencyID.isBlank())
            assertEquals("Yaralıyım", ended.lastMessage)
            note("second_reopen_preserves_off_state_and_messages", ended)
            click("Durumu yenile"); enabled("SOS başlat")
            snap("03-final-off")
            phase("finished")
        }
    }
}
