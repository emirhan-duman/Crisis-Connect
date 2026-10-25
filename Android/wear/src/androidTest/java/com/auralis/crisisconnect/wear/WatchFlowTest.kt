package com.auralis.crisisconnect.wear

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Runs only against the debug emulator transport. Never starts a real phone SOS. */
class WatchFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun launch(scenario: String) = ActivityScenario.launch<MainActivity>(
        Intent(context, MainActivity::class.java).putExtra("crisis_watch_scenario", scenario)
            .putExtra("crisis_watch_language", "tr").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    private fun waitFor(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun waitEnabled(text: String) = compose.waitUntil(5_000) {
        compose.onAllNodes(hasText(text) and isEnabled() and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun screenshot(name: String) {
        val directory = File(context.getExternalFilesDir(null), "watch-ui-results").apply { mkdirs() }
        assertTrue(device.takeScreenshot(File(directory, "$name.png")))
    }
    private fun assertControlFits(text: String) {
        val bounds = compose.onNodeWithText(text).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("$text must fit without scrolling: $bounds", bounds.top >= 10.dp &&
            bounds.bottom <= (context.resources.configuration.screenHeightDp - 10).dp)
    }

    @Test fun countdownCancelStartAndStopConfirmation() {
        launch("idle").use {
            waitEnabled("SOS başlat")
            assertControlFits("SOS başlat")
            screenshot("sos-inactive")
            compose.onNodeWithText("SOS başlat").performClick()
            assertControlFits("İptal")
            screenshot("countdown")
            compose.onNodeWithText("İptal").performClick()
            Thread.sleep(5_500)
            compose.onNodeWithText("SOS kapalı").assertExists()
            compose.onNodeWithText("SOS başlat").performClick()
            compose.waitUntil(8_000) { compose.onAllNodesWithText("SOS aktif").fetchSemanticsNodes().isNotEmpty() }
            assertControlFits("SOS’u bitir")
            screenshot("sos-active")
            compose.onNodeWithText("SOS’u bitir").performClick()
            compose.onNodeWithText("Aktif SOS bitirilsin mi?").assertIsDisplayed()
            compose.onNodeWithText("İptal").assertIsDisplayed()
            screenshot("stop-confirmation")
            compose.onNodeWithText("İptal").performClick()
            compose.onNodeWithText("SOS aktif").assertExists()
            compose.onNodeWithText("SOS’u bitir").performClick()
            compose.onNodeWithText("SOS’u bitir").performClick()
            waitFor("Telefon SOS’un bittiğini doğruladı.")
            compose.onNodeWithText("SOS kapalı").assertExists()
            screenshot("sos-ended")
        }
    }

    @Test fun allThreeQuickReplies() {
        launch("idle").use {
            waitEnabled("SOS başlat")
            listOf("İyiyim", "Yaralıyım", "Yardıma ihtiyacım var").forEachIndexed { index, reply ->
                compose.onNodeWithText(reply).performScrollTo().assertIsDisplayed().performClick()
                waitFor("Test cevabı: $reply")
                compose.onNodeWithText("Test cevabı: $reply").performScrollTo().assertIsDisplayed()
                screenshot("reply-$index")
            }
        }
    }

    @Test fun disconnectedCannotStartSOS() {
        launch("disconnected").use {
            waitFor("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon bağlı olmalı.")
            compose.onNodeWithText("SOS başlat").assertIsNotEnabled()
            compose.onNodeWithText("SOS durumu bilinmiyor").assertExists()
            screenshot("disconnected")
        }
    }

    @Test fun missingReceiptDoesNotClaimSuccess() {
        launch("unconfirmed").use {
            waitEnabled("SOS başlat")
            // Reply failure exercises the same unconfirmed receipt handler without a countdown delay.
            compose.onNodeWithText("İyiyim").performScrollTo().performClick()
            waitFor("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon bağlı olmalı.")
            compose.onNodeWithText("SOS başlat").assertIsNotEnabled()
            compose.onNodeWithText("Son bilinen: SOS kapalı").assertExists()
            compose.onNodeWithText("Doğrulama alınamadı. Durumu yenile. Telefon uygulaması kurulu ve telefon bağlı olmalı.").performScrollTo()
            screenshot("unconfirmed")
        }
    }

    @Test fun phoneApprovalDoesNotClaimActiveSOS() {
        launch("phoneConfirmation").use {
            waitEnabled("SOS başlat")
            compose.onNodeWithText("SOS başlat").performClick()
            val message = "SOS’u başlatmak için telefondaki bildirime dokun. SOS henüz doğrulanmadı."
            compose.waitUntil(8_000) { compose.onAllNodesWithText(message).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("SOS kapalı").assertExists()
            compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
            screenshot("phone-approval")
        }
    }

    @Test fun backgroundCancelsCountdown() {
        launch("idle").use { scenario ->
            waitEnabled("SOS başlat")
            compose.onNodeWithText("SOS başlat").performClick()
            compose.onNodeWithText("İptal").assertIsDisplayed()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(5_500)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            waitEnabled("SOS başlat")
            compose.onNodeWithText("SOS kapalı").assertExists()
        }
    }
}
