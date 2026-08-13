package com.auralis.crisisconnect.screens.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.crisisconnect.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the type-to-confirm gate on account deletion.
 *
 * Account deletion is irreversible and its button lives on a screen a user might hand to a rescuer,
 * so "did the confirm button stay disabled" is the assertion that matters most here — a regression
 * that enables it early would turn a stray tap into a permanent erase, and nothing else in the
 * codebase would notice.
 *
 * Strings are read from resources rather than hard-coded: the confirmation word is localised (SİL /
 * DELETE / LÖSCHEN …), and a test that hard-codes one would pass or fail on device locale.
 */
@RunWith(AndroidJUnit4::class)
class DeleteAccountSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val openLabel get() = context.getString(R.string.profile_delete_account_button)
    private val confirmWord get() = context.getString(R.string.profile_delete_account_confirm_word)
    private val confirmLabel get() = context.getString(R.string.profile_delete_account_dialog_confirm)
    private val cancelLabel get() = context.getString(R.string.cancel)
    private val progressLabel get() = context.getString(R.string.profile_delete_account_progress)

    private var confirmCount = 0

    private fun setUpSection(isDeleting: Boolean = false) {
        confirmCount = 0
        composeRule.setContent {
            DeleteAccountSection(isDeleting = isDeleting, onConfirm = { confirmCount += 1 })
        }
    }

    @Test
    fun confirmStaysDisabledUntilTheExactWordIsTyped() {
        setUpSection()
        composeRule.onNodeWithText(openLabel).performClick()

        // Nothing typed yet.
        composeRule.onNodeWithText(confirmLabel).assertIsNotEnabled()

        // A near miss must not count — this is the whole point of the gate.
        composeRule.onNode(textFieldMatcher()).performTextInput(confirmWord.dropLast(1))
        composeRule.onNodeWithText(confirmLabel).assertIsNotEnabled()

        composeRule.onNode(textFieldMatcher()).performTextClearance()
        composeRule.onNode(textFieldMatcher()).performTextInput(confirmWord)
        composeRule.onNodeWithText(confirmLabel).assertIsEnabled()

        assertEquals("nothing may be deleted before the confirm click", 0, confirmCount)
    }

    @Test
    fun confirmingOnceInvokesDeletionOnce() {
        setUpSection()
        composeRule.onNodeWithText(openLabel).performClick()
        composeRule.onNode(textFieldMatcher()).performTextInput(confirmWord)
        composeRule.onNodeWithText(confirmLabel).performClick()

        assertEquals(1, confirmCount)
    }

    @Test
    fun cancellingDeletesNothing() {
        setUpSection()
        composeRule.onNodeWithText(openLabel).performClick()
        composeRule.onNode(textFieldMatcher()).performTextInput(confirmWord)
        composeRule.onNodeWithText(cancelLabel).performClick()

        assertEquals(0, confirmCount)
    }

    @Test
    fun reopeningStartsFromAnEmptyConfirmation() {
        setUpSection()
        composeRule.onNodeWithText(openLabel).performClick()
        composeRule.onNode(textFieldMatcher()).performTextInput(confirmWord)
        composeRule.onNodeWithText(cancelLabel).performClick()

        // A dismissed dialog must not leave a primed confirmation behind for the next tap.
        composeRule.onNodeWithText(openLabel).performClick()
        composeRule.onNodeWithText(confirmLabel).assertIsNotEnabled()
    }

    @Test
    fun deletionInProgressShowsProgressAndBlocksReentry() {
        setUpSection(isDeleting = true)

        composeRule.onNodeWithText(progressLabel).assertExists()
        composeRule.onNodeWithText(progressLabel).assertIsNotEnabled()
    }

    /** The dialog holds exactly one text field; matching on the node type keeps the test off labels. */
    private fun textFieldMatcher() =
        androidx.compose.ui.test.hasSetTextAction()
}
