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

/** Exchanges commands with the OTHER emulator via the authenticated ADB loopback tunnel. */
class WatchDevLinkTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val token get() = requireNotNull(InstrumentationRegistry.getArguments().getString("dev_link_token"))
    private val results = JSONArray()
    private val output get() = File(context.filesDir, "watch-dev-results.json")
    private fun save() { output.writeText(JSONObject().put("transport", "local-developer-link-not-data-layer").put("checks", results).toString(2)) }
    private fun checkReceipt(name: String, transport: DevLinkTransport, command: WatchCommand, result: String, active: Boolean): PhoneWatchState {
        val receipt = runBlocking { transport.receipt(command) }
        val state = PhoneWatchState.decode(receipt.getJSONObject("state"))
        assertEquals(name, result, receipt.getString("result"))
        assertEquals(name, active, state.sosActive)
        assertFalse("No BLE in controlled tests", state.bleBroadcasting)
        results.put(JSONObject().put("name", name).put("id", command.id).put("receipt", receipt).put("passed", true))
        save()
        return state
    }
    private fun waitFor(text: String) = compose.waitUntil(12_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitEnabled(text: String) = compose.waitUntil(12_000) { compose.onAllNodes(hasText(text) and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
    private fun screenshot(name: String) {
        val dir = File(context.getExternalFilesDir(null), "watch-dev-ui").apply { mkdirs() }
        assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(File(dir, "$name.png")))
    }
    @Test fun roundTripsAndRealWatchControls() {
        val transport = DevLinkTransport(token)
        val initial = checkReceipt("initial_off", transport, WatchCommand(action = WatchAction.status), "accepted", false)
        assertEquals("local-dev-test-recipient", initial.peerID)
        // A mismatching per-run token must never reach the phone command handler.
        assertTrue(runCatching { runBlocking { DevLinkTransport("00000000-0000-0000-0000-000000000000").receipt(WatchCommand(action = WatchAction.startSOS)) } }.isFailure)
        results.put(JSONObject().put("name", "wrong_token_rejected").put("passed", true)); save()
        val start = WatchCommand(action = WatchAction.startSOS)
        val active = checkReceipt("start_on_phone", transport, start, "accepted", true)
        assertTrue(active.emergencyID.isNotBlank())
        val duplicate = checkReceipt("duplicate_start", transport, start, "accepted", true)
        assertEquals(active.emergencyID, duplicate.emergencyID)
        QuickReply.entries.forEach { reply ->
            val command = WatchCommand(action = WatchAction.quickReply, peerID = initial.peerID, reply = reply)
            checkReceipt("reply_${reply.name}", transport, command, "accepted", true)
            if (reply == QuickReply.safe) checkReceipt("duplicate_reply", transport, command, "accepted", true)
        }
        checkReceipt("wrong_recipient", transport, WatchCommand(action = WatchAction.quickReply, peerID = "wrong", reply = QuickReply.safe), "noPeer", true)
        val past = System.currentTimeMillis() - 60_000
        checkReceipt("expired_stop", transport, WatchCommand(action = WatchAction.stopSOS, issuedAt = past, expiresAt = past + 30_000, emergencyID = active.emergencyID), "invalid", true)
        checkReceipt("wrong_emergency", transport, WatchCommand(action = WatchAction.stopSOS, emergencyID = "wrong"), "stateChanged", true)
        val stop = WatchCommand(action = WatchAction.stopSOS, emergencyID = active.emergencyID)
        checkReceipt("stop_on_phone", transport, stop, "accepted", false)
        checkReceipt("duplicate_stop", transport, stop, "accepted", false)

        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("dev_link_token", token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            waitEnabled("SOS başlat")
            screenshot("01-inactive")
            compose.onNodeWithText("SOS başlat").performClick()
            screenshot("02-countdown")
            compose.onNodeWithText("İptal").performClick()
            Thread.sleep(5_500)
            checkReceipt("ui_cancel_keeps_phone_off", transport, WatchCommand(action = WatchAction.status), "accepted", false)
            compose.onNodeWithText("SOS başlat").performClick()
            waitFor("SOS aktif")
            screenshot("03-active")
            checkReceipt("ui_start_reaches_phone", transport, WatchCommand(action = WatchAction.status), "accepted", true)
            compose.onNodeWithText("SOS’u bitir").performClick()
            screenshot("04-stop-confirmation")
            compose.onNodeWithText("İptal").performClick()
            checkReceipt("ui_stop_cancel_keeps_phone_active", transport, WatchCommand(action = WatchAction.status), "accepted", true)
            QuickReply.entries.forEachIndexed { index, reply ->
                waitEnabled(reply.turkish)
                compose.onNode(hasText(reply.turkish) and hasClickAction()).performScrollTo().performClick()
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasText(reply.turkish) and !hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                waitFor("Cevap telefondaki test alıcısına ulaştı.")
                screenshot("05-reply-$index")
                val state = checkReceipt("ui_reply_${reply.name}", transport, WatchCommand(action = WatchAction.status), "accepted", true)
                assertEquals(reply.turkish, state.lastMessage)
            }
            compose.onNodeWithText("SOS’u bitir").performScrollTo().performClick()
            compose.onNodeWithText("SOS’u bitir").performClick()
            waitFor("Telefon SOS’un bittiğini doğruladı.")
            screenshot("06-ended")
            checkReceipt("ui_stop_reaches_phone", transport, WatchCommand(action = WatchAction.status), "accepted", false)
        }
    }

    @Test fun disconnectedTunnelDoesNotClaimSuccess() {
        // Run separately after removing ONLY the watch's test-port reverse mapping.
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).putExtra("dev_link_token", token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use {
            waitFor("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon yakında olmalı.")
            compose.onNodeWithText("SOS başlat").assertIsNotEnabled()
            compose.onNodeWithText("SOS durumu bilinmiyor").assertExists()
            screenshot("07-disconnected")
        }
    }
}
