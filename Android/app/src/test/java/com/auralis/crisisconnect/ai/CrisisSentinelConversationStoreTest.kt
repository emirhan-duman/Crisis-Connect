package com.auralis.crisisconnect.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CrisisSentinelConversationStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearStore()
    }

    @After
    fun tearDown() {
        clearStore()
    }

    @Test
    fun saveDraft_blankWithoutExistingConversation_doesNotCreateSummary() {
        val store = CrisisSentinelConversationStore(context)

        val draft = store.saveDraft(id = null, text = "")

        assertNull(draft)
        assertTrue(store.summaries().isEmpty())
    }

    @Test
    fun saveDraft_nonBlankWithoutExistingConversation_createsDraftSummary() {
        val store = CrisisSentinelConversationStore(context)

        val draft = requireNotNull(store.saveDraft(id = null, text = "  selam ekip  "))
        val summaries = store.summaries()

        assertEquals("selam ekip", draft.draftText)
        assertEquals(1, summaries.size)
        assertTrue(summaries.first().isDraft)
        assertEquals("selam ekip", summaries.first().lastMessagePreview)
        assertEquals("selam ekip", store.load(draft.id)?.draftText)
    }

    @Test
    fun saveDraft_blankForEmptyDraft_deletesConversation() {
        val store = CrisisSentinelConversationStore(context)
        val draft = requireNotNull(store.saveDraft(id = null, text = "taslak deneme"))

        val cleared = store.saveDraft(id = draft.id, text = "")

        assertNull(cleared)
        assertTrue(store.summaries().isEmpty())
        assertNull(store.load(draft.id))
    }

    @Test
    fun appendUserMessage_clearsDraftState() {
        val store = CrisisSentinelConversationStore(context)
        val draft = requireNotNull(store.saveDraft(id = null, text = "yardim lazim"))

        val conversation = requireNotNull(store.appendUserMessage(draft.id, "yardim lazim"))
        val summary = store.summaries().single()

        assertEquals("", conversation.draftText)
        assertFalse(summary.isDraft)
        assertEquals("yardim lazim", summary.lastMessagePreview)
    }

    @Test
    fun delete_removesConversation() {
        val store = CrisisSentinelConversationStore(context)
        val draft = requireNotNull(store.saveDraft(id = null, text = "sil test"))

        assertTrue(store.delete(draft.id))

        assertTrue(store.summaries().isEmpty())
        assertNull(store.load(draft.id))
        assertFalse(store.delete(draft.id))
    }

    @Test
    fun load_filtersLegacyCannedModelMetaAnswers() {
        val conversationId = "legacy-slm"
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(
                KEY_CONVERSATIONS,
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", conversationId)
                            .put("title", "slm")
                            .put("mode", CrisisSentinelUserMode.Public.name)
                            .put("createdAtMillis", 1L)
                            .put("updatedAtMillis", 2L)
                            .put("draftText", "")
                            .put(
                                "messages",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("id", "user-1")
                                            .put("role", CrisisSentinelChatRole.User.name)
                                            .put("text", "slm")
                                            .put("timestampMillis", 1L)
                                    )
                                    .put(
                                        JSONObject()
                                            .put("id", "assistant-1")
                                            .put("role", CrisisSentinelChatRole.Assistant.name)
                                            .put(
                                                "text",
                                                "Bu çıktı offline on-device asistanlıktır ve Crisis Sentinel Edge'dir."
                                            )
                                            .put("timestampMillis", 2L)
                                            .put("source", CrisisSentinelResponseSource.LocalModel.name)
                                    )
                            )
                    )
                    .toString()
            )
            .commit()

        val store = CrisisSentinelConversationStore(context)
        val summary = store.summaries().single()
        val loaded = requireNotNull(store.load(conversationId))

        assertEquals("slm", summary.lastMessagePreview)
        assertEquals(1, loaded.messages.size)
        assertEquals(CrisisSentinelChatRole.User, loaded.messages.single().role)
        assertFalse(summary.lastMessagePreview.orEmpty().contains("offline on-device"))
    }

    private fun clearStore() {
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "crisis_sentinel_conversations"
        private const val KEY_CONVERSATIONS = "conversations"
    }
}
