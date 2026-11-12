package com.auralis.crisisconnect.watch

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.service.GattSOSServerService
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecoveryScreenTest {
    @get:Rule val ui = createAndroidComposeRule<RecoveryPreviewActivity>()
    private val context get() = ui.activity
    private val store get() = WatchRecoveryStore.get(context)
    private fun screenshot(name: String) {
        ui.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle()
        val file = File(context.getExternalFilesDir(null), "recovery-ui/$name.png")
        file.parentFile!!.mkdirs()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(file)
    }
    @Test fun pausedAfterColdLaunch() {
        ui.onNodeWithText(context.getString(R.string.sos_recovery_title)).assertExists()
        ui.onNodeWithText("İyiyim").assertExists()
        ui.onNodeWithText("Yaralıyım").assertExists()
        assertEquals(1791792000123L, store.state.value.startedAt)
        assertFalse(GattSOSServerService.isDeclared.value)
        assertFalse(GattSOSServerService.isRunning.value)
        assertFalse(GattSOSServerService.isAdvertising.value)
        assertEquals(2, store.state.value.replies.size)
        screenshot("01-saved-after-cold-launch")
    }
    @Test fun resumeSameSessionAndStopUsingScreen() {
        ui.onNodeWithText(context.getString(R.string.sos_recovery_resume)).performScrollTo().performClick()
        ui.waitUntil(5000) { GattSOSServerService.isDeclared.value }
        assertEquals(1791792000123L, GattSOSServerService.startTimestampMillis.value)
        assertEquals(1791792000123L, store.state.value.startedAt)
        assertFalse(GattSOSServerService.isAdvertising.value)
        ui.onNodeWithContentDescription(context.getString(R.string.sos_screen_slide_label)).assertExists()
        screenshot("02-same-session-active")
        ui.onNodeWithContentDescription(context.getString(R.string.sos_screen_slide_label))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        ui.waitUntil(5000) { !GattSOSServerService.isDeclared.value && store.state.value.startedAt == null }
        ui.onNodeWithText("SOS kapalı").assertExists()
        assertEquals(2, store.state.value.replies.size)
        screenshot("03-explicitly-ended")
    }
    @Test fun resumeOnlyForWatch() {
        ui.onNodeWithText(context.getString(R.string.sos_recovery_resume)).performScrollTo().performClick()
        ui.waitUntil(5000) { GattSOSServerService.isDeclared.value }
        assertEquals(1791792000123L, GattSOSServerService.startTimestampMillis.value)
        ui.onNodeWithContentDescription(context.getString(R.string.sos_screen_slide_label)).assertExists()
        screenshot("05-phone-confirmed-for-watch")
        InstrumentationRegistry.getArguments().getString("recovery_hold_run")?.let { run ->
            File(context.filesDir, "recovery-approved-$run").writeText("approved")
            val release = File(context.filesDir, "recovery-release-$run")
            val end = System.currentTimeMillis() + 60000
            while (!release.exists() && System.currentTimeMillis() < end) Thread.sleep(200)
            assertTrue("Host did not finish the watch stop", release.exists())
        }
    }
    @Test fun remainsOffAfterColdLaunch() {
        ui.onNodeWithText("SOS kapalı").assertExists()
        assertNull(store.state.value.startedAt)
        assertFalse(GattSOSServerService.isDeclared.value)
        assertFalse(GattSOSServerService.isRunning.value)
        assertEquals(listOf("accepted", "uncertain"), store.state.value.replies.map { it.result })
        screenshot("04-off-after-cold-launch")
    }
}
