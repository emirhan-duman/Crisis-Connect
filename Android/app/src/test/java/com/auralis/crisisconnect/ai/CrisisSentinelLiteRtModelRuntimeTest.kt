package com.auralis.crisisconnect.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CrisisSentinelLiteRtModelRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun renderUserMessage_generalChatKeepsRawPrompt() {
        val runtime = CrisisSentinelLiteRtModelRuntime(context)
        val message = runtime.renderUserMessage(
            CrisisSentinelModelRequest(
                systemInstruction = "general",
                userPrompt = "Python'da listeyi nasıl ters çeviririm?",
                locale = Locale("tr"),
                mode = CrisisSentinelUserMode.Public,
                queryDomain = CrisisSentinelQueryDomain.General,
                responseSchema = "",
                contextSnippets = emptyList()
            )
        )

        assertEquals("Python'da listeyi nasıl ters çeviririm?", message)
        assertFalse(message.contains("Sorgu türü"))
        assertFalse(message.contains("Yerel kaynak bağlamı"))
        assertFalse(message.contains("Crisis Connect"))
    }

    @Test
    fun renderUserMessage_crisisGuidedIncludesLocalContext() {
        val runtime = CrisisSentinelLiteRtModelRuntime(context)
        val message = runtime.renderUserMessage(
            CrisisSentinelModelRequest(
                systemInstruction = "crisis",
                userPrompt = "Apartmanda gaz kokusu var ne yapmalıyız?",
                locale = Locale("tr"),
                mode = CrisisSentinelUserMode.Public,
                queryDomain = CrisisSentinelQueryDomain.CrisisGuided,
                responseSchema = "",
                contextSnippets = listOf("CSE-GAS-001: Elektrik anahtarı kullanma, kıvılcım çıkarma.")
            )
        )

        assertTrue(message.contains("Kullanıcı rolü"))
        assertTrue(message.contains("Yerel kaynak bağlamı"))
        assertTrue(message.contains("CSE-GAS-001"))
        assertTrue(message.contains("Elektrik anahtarı"))
    }
}
