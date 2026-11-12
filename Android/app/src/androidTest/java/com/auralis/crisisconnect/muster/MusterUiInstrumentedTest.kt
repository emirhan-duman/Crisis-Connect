package com.auralis.crisisconnect.muster

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.R
import com.auralis.crisisconnect.ui.theme.DisasterCommunicationSystemTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Isolated UI fixtures only: no authentication, production documents or notification sends. */
class MusterUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun card() = MusterCardModel("factory", "session", "live", "Personel yoklaması", "Lütfen güvenlik durumunu bildir.", false, "", null, "", false, 100, 0)
    private fun capture(name: String) {
        compose.waitForIdle()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(context.getExternalFilesDir(null), "$name.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }; screenshot.recycle()
    }
    @Test fun homeCardOpensTheExactRequest() {
        val row = card(); var destination: Pair<String?, String?>? = null
        compose.setContent { DisasterCommunicationSystemTheme(dynamicColor = false) { Surface { Column {
            MusterHomeSection(MusterInboxState("test", listOf(row))) { panel, session -> destination = panel to session }
        } } } }
        compose.onNodeWithText(context.getString(R.string.muster_ui_awaiting)).assertIsDisplayed()
        capture("muster-home")
        compose.onNodeWithText(context.getString(R.string.muster_ui_awaiting)).performClick()
        compose.runOnIdle { assertEquals("factory" to "session", destination) }
    }
    @Test fun cachedResponseWorksWhileRefreshIsInProgressAndShowsPendingReceipt() {
        val row = card(); var sent: Triple<String, String, String>? = null
        compose.setContent {
            var inbox by remember { mutableStateOf(MusterInboxState("test", listOf(row))) }
            DisasterCommunicationSystemTheme(dynamicColor = false) { MusterWorkspace(inbox, refreshing = true, saving = false, refreshError = true, saveError = false,
                initialPanel = row.panel, initialSession = row.session, onBack = {}, onRefresh = {}, onAnswer = { target, answer, note ->
                    sent = Triple(target.key, answer, note)
                    inbox = inbox.copy(cards = listOf(target.copy(replyState = "pending", answer = answer, note = note)))
                }) }
        }
        compose.onNodeWithText(context.getString(R.string.muster_safe)).performScrollTo().assertIsEnabled()
        capture("muster-response")
        compose.onNodeWithText(context.getString(R.string.muster_safe)).performClick()
        compose.onNodeWithText(context.getString(R.string.muster_queued)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.muster_sent)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(Triple(row.key, "safe", ""), sent) }
        capture("muster-queued")
    }
    @Test fun closedRequestNeverShowsAnswerControlsAndLockScreenContainsNoAssignmentDetails() {
        val row = card().copy(closed = true)
        var locked by mutableStateOf(false)
        compose.setContent { DisasterCommunicationSystemTheme(dynamicColor = false) {
            if (locked) MusterLockedScreen("live", {}, {})
            else MusterWorkspace(MusterInboxState("test", listOf(row)), false, false, false, false,
                row.panel, row.session, {}, {}, { _, _, _ -> fail("Closed request cannot answer") })
        } }
        compose.onNodeWithText(context.getString(R.string.muster_closed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.muster_safe)).assertDoesNotExist()
        compose.runOnIdle { locked = true }
        compose.onNodeWithText(context.getString(R.string.muster_unlock)).assertIsDisplayed()
        compose.onNodeWithText(row.instructions).assertDoesNotExist()
        capture("muster-lock-screen")
    }
    @Test fun largeTextKeepsUnlockActionReachable() {
        compose.setContent { DisasterCommunicationSystemTheme(dynamicColor = false) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                MusterLockedScreen("live", {}, {})
            }
        } }
        compose.onNodeWithText(context.getString(R.string.muster_unlock)).performScrollTo().assertIsDisplayed()
        capture("muster-lock-large-text")
    }
}
