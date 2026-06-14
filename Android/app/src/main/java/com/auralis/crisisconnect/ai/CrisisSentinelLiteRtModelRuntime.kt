package com.auralis.crisisconnect.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class CrisisSentinelLiteRtModelRuntime(
    context: Context,
    release: CrisisSentinelModelRelease = CrisisSentinelModelFileStore.defaultRelease,
    private val releaseProvider: () -> CrisisSentinelModelRelease = { release },
    private val store: CrisisSentinelModelFileStore = CrisisSentinelModelFileStore(context),
    private val maxTokens: Int = 1024,
    private val crisisTopK: Int = 32,
    private val crisisTopP: Double = 0.90,
    private val crisisTemperature: Double = 0.15,
    private val generalTopK: Int = 64,
    private val generalTopP: Double = 0.95,
    // 0.85 amplified hallucinations on the small on-device release; 0.7 keeps general chat
    // natural while staying coherent (Gemma's recommended 1.0 assumes a fully tuned model).
    private val generalTemperature: Double = 0.7
) : CrisisSentinelModelRuntime, AutoCloseable {
    private val appContext = context.applicationContext
    private var engine: Engine? = null
    private var engineModelPath: String? = null
    private var activeConversation: Conversation? = null

    override val id: String
        get() = "litert-lm:${releaseProvider().id}"

    override val isReady: Boolean
        get() = store.installedModel(releaseProvider()) != null

    override suspend fun generate(request: CrisisSentinelModelRequest): CrisisSentinelModelResponse {
        val installed = store.installedModel(releaseProvider())
            ?: throw IllegalStateException("Crisis Sentinel model is not installed")

        return withContext(Dispatchers.Default) {
            val engine = ensureEngine(installed.file)
            val conversationConfig = ConversationConfig(
                systemInstruction = Contents.of(request.systemInstruction),
                samplerConfig = samplerConfigFor(request.queryDomain)
            )
            val conversation = engine.createConversation(conversationConfig)
            conversation.use {
                registerActiveConversation(it)
                try {
                    val userMessage = renderUserMessage(request)
                    val text = it.sendMessage(Contents.of(Content.Text(userMessage))).textContent()
                    CrisisSentinelModelResponse(
                        text = text,
                        structuredJson = text,
                        confidence = 0.68f
                    )
                } finally {
                    clearActiveConversation(it)
                }
            }
        }
    }

    @Synchronized
    private fun ensureEngine(modelFile: File): Engine {
        val path = modelFile.absolutePath
        val existing = engine
        if (existing != null && engineModelPath == path && existing.isInitialized()) {
            return existing
        }

        engine?.close()
        val cacheDir = File(appContext.cacheDir, "crisis_sentinel_litertlm").apply { mkdirs() }
        val created = createInitializedEngine(
            modelPath = path,
            cachePath = cacheDir.absolutePath
        )
        engine = created
        engineModelPath = path
        return created
    }

    private fun createInitializedEngine(
        modelPath: String,
        cachePath: String
    ): Engine {
        var lastError: Throwable? = null
        val cpuThreads = min(max(Runtime.getRuntime().availableProcessors() - 1, 2), 4)
        val backends = listOf(
            Backend.GPU(),
            Backend.CPU(cpuThreads),
            Backend.NPU()
        )

        for (backend in backends) {
            val engine = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    maxNumTokens = maxTokens,
                    cacheDir = cachePath
                )
            )
            try {
                engine.initialize()
                return engine
            } catch (throwable: Throwable) {
                lastError = throwable
                runCatching { engine.close() }
            }
        }

        throw IllegalStateException("Crisis Sentinel LiteRT-LM engine could not initialize.", lastError)
    }

    @Synchronized
    private fun registerActiveConversation(conversation: Conversation) {
        activeConversation = conversation
    }

    @Synchronized
    private fun clearActiveConversation(conversation: Conversation) {
        if (activeConversation === conversation) {
            activeConversation = null
        }
    }

    @Synchronized
    override fun cancelGeneration() {
        activeConversation?.cancelProcess()
    }

    private fun samplerConfigFor(queryDomain: CrisisSentinelQueryDomain): SamplerConfig {
        return if (queryDomain == CrisisSentinelQueryDomain.General) {
            SamplerConfig(
                topK = generalTopK,
                topP = generalTopP,
                temperature = generalTemperature
            )
        } else {
            SamplerConfig(
                topK = crisisTopK,
                topP = crisisTopP,
                temperature = crisisTemperature,
                seed = 7
            )
        }
    }

    internal fun renderUserMessage(request: CrisisSentinelModelRequest): String {
        if (request.queryDomain == CrisisSentinelQueryDomain.General) {
            return renderGeneralUserMessage(request)
        }
        return buildString {
            appendLine("Kullanıcı rolü: ${request.mode.promptRoleLabel()}")
            appendLine("Cevap dili: ${CrisisSentinelText.responseLanguageName(request.locale)}")
            appendLine()
            appendLine("Kullanıcı sorusu:")
            appendLine(request.userPrompt.ifBlank { request.recentMessages.lastOrNull().orEmpty() })
            if (request.recentMessages.isNotEmpty()) {
                appendLine()
                appendLine("Son konuşma:")
                request.recentMessages.forEach { message ->
                    appendLine("- ${message.take(140)}")
                }
            }
            appendLine()
            appendLine("Yerel kaynak bağlamı:")
            if (request.contextSnippets.isEmpty()) {
                appendLine("- Yerel bağlam yoksa genel, güvenli ve sınırları belirten cevap ver.")
            } else {
                request.contextSnippets.forEach { snippet ->
                    appendLine("- $snippet")
                }
            }
            appendLine()
            appendLine("Cevap formatı: 3-5 kısa cümle. Gerekirse en fazla 2 net soru sor. JSON yazma.")
            appendLine("Kurallar: Kullanıcının dilinde cevap ver; sistem/politika/model metni yazma; ilgisiz önceki cevapları kopyalama.")
        }.trim()
    }

    private fun renderGeneralUserMessage(request: CrisisSentinelModelRequest): String {
        val prompt = request.userPrompt.ifBlank { request.recentMessages.lastOrNull().orEmpty() }.trim()
        if (request.recentMessages.isEmpty()) return prompt

        // Keep the scaffold in the response language so the model doesn't drift into English.
        val isTurkish = CrisisSentinelText.isTurkish(request.locale)
        return buildString {
            appendLine(if (isTurkish) "Önceki konuşma:" else "Recent conversation:")
            request.recentMessages.forEach { message ->
                appendLine(message.take(180))
            }
            appendLine()
            appendLine(if (isTurkish) "Kullanıcı:" else "User:")
            append(prompt)
        }.trim()
    }

    private fun CrisisSentinelUserMode.promptRoleLabel(): String = when (this) {
        CrisisSentinelUserMode.Public -> "genel kullanıcı"
        CrisisSentinelUserMode.FieldTeam -> "saha ekibi"
        CrisisSentinelUserMode.Coordinator -> "yetkili koordinasyon"
    }

    private fun Message.textContent(): String {
        val text = contents.contents
            .filterIsInstance<Content.Text>()
            .joinToString("\n") { it.text }
            .trim()
        return text.ifBlank { toString() }
    }

    @Synchronized
    override fun close() {
        activeConversation?.cancelProcess()
        activeConversation = null
        engine?.close()
        engine = null
        engineModelPath = null
    }
}
