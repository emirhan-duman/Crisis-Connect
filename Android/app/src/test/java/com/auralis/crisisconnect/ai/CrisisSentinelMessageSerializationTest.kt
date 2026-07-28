package com.auralis.crisisconnect.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Round-trips the online-engine message extras through the local SharedPreferences store. */
@RunWith(RobolectricTestRunner::class)
class CrisisSentinelMessageSerializationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clear()
    }

    @After
    fun tearDown() {
        clear()
    }

    private fun clear() {
        context.getSharedPreferences("crisis_sentinel_conversations", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun assistantExtras_surviveStoreRoundTrip() {
        val store = CrisisSentinelConversationStore(context)
        val conversation = store.create()
        store.appendUserMessage(conversation.id, "İstanbul'da hava nasıl?")
        val cardJson = """{"kind":"weather","weather":{"label":"İstanbul","tempC":23.4}}"""
        store.appendAssistantResponse(
            id = conversation.id,
            response = CrisisSentinelResponse(
                answer = "Hava güzel.",
                mode = CrisisSentinelUserMode.FieldTeam,
                source = CrisisSentinelResponseSource.OnlineModel,
                citations = emptyList(),
                incidentDraft = null,
                followUpQuestions = emptyList(),
                safetyNotices = emptyList(),
                confidence = 1f
            ),
            modelName = "Gemini 3.5 Flash",
            cardJson = cardJson,
            mapPoints = listOf(
                CrisisSentinelMapPoint(lat = 41.0, lng = 29.0, label = "Kadıköy", details = "x")
            ),
            unsupportedToolName = "showSosSignals"
        )

        val reloaded = CrisisSentinelConversationStore(context).load(conversation.id)!!
        val assistant = reloaded.messages.last()
        assertEquals(CrisisSentinelResponseSource.OnlineModel, assistant.source)
        assertEquals("Gemini 3.5 Flash", assistant.modelName)
        assertEquals(cardJson, assistant.cardJson)
        assertEquals(1, assistant.mapPoints.size)
        assertEquals(41.0, assistant.mapPoints[0].lat, 0.0001)
        assertEquals("Kadıköy", assistant.mapPoints[0].label)
        // Transient by design: the notice never survives persistence.
        assertNull(assistant.unsupportedToolName)
    }

    @Test
    fun legacyMessagesWithoutExtras_stillParse() {
        val store = CrisisSentinelConversationStore(context)
        val conversation = store.create()
        store.appendUserMessage(conversation.id, "merhaba")

        val reloaded = CrisisSentinelConversationStore(context).load(conversation.id)!!
        val message = reloaded.messages.last()
        assertNull(message.modelName)
        assertNull(message.cardJson)
        assertTrue(message.mapPoints.isEmpty())
    }

    @Test
    fun rename_updatesTitle() {
        val store = CrisisSentinelConversationStore(context)
        val conversation = store.create()
        store.appendUserMessage(conversation.id, "ilk mesaj")

        store.rename(conversation.id, "Yeni Başlık")

        assertEquals("Yeni Başlık", store.load(conversation.id)!!.title)
        // Blank rename is ignored.
        store.rename(conversation.id, "   ")
        assertEquals("Yeni Başlık", store.load(conversation.id)!!.title)
    }
}
