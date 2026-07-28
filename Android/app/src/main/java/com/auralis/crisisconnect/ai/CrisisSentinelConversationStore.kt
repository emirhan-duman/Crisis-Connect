package com.auralis.crisisconnect.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class CrisisSentinelChatRole {
    User,
    Assistant
}

data class CrisisSentinelChatMessage(
    val id: String,
    val role: CrisisSentinelChatRole,
    val text: String,
    val timestampMillis: Long,
    val source: CrisisSentinelResponseSource? = null,
    val confidence: Double? = null,
    val generationDurationMillis: Long? = null,
    val modelName: String? = null,
    /** The online engine's `_card` payload verbatim (JSON object string). */
    val cardJson: String? = null,
    val mapPoints: List<CrisisSentinelMapPoint> = emptyList(),
    /** Transient UX notice for a tool the app can't render — never persisted. */
    val unsupportedToolName: String? = null
)

data class CrisisSentinelConversation(
    val id: String,
    val title: String,
    val mode: CrisisSentinelUserMode,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<CrisisSentinelChatMessage>,
    val draftText: String = ""
)

data class CrisisSentinelConversationSummary(
    val id: String,
    val title: String,
    val mode: CrisisSentinelUserMode,
    val updatedAtMillis: Long,
    val lastMessagePreview: String?,
    val isDraft: Boolean = false,
    /** True when the conversation lives in Firestore (synced with the web dashboard). */
    val isCloud: Boolean = false
)

class CrisisSentinelConversationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun summaries(): List<CrisisSentinelConversationSummary> {
        return loadAll()
            .filter { it.messages.isNotEmpty() || it.draftText.isNotBlank() }
            .sortedByDescending { it.updatedAtMillis }
            .map { conversation ->
                val draft = conversation.draftText.trim()
                CrisisSentinelConversationSummary(
                    id = conversation.id,
                    title = conversation.title,
                    mode = conversation.mode,
                    updatedAtMillis = conversation.updatedAtMillis,
                    lastMessagePreview = draft.takeIf { it.isNotBlank() }
                        ?: conversation.messages.lastOrNull()?.text,
                    isDraft = draft.isNotBlank()
                )
            }
    }

    fun load(id: String): CrisisSentinelConversation? {
        return loadAll().firstOrNull { it.id == id }
    }

    fun delete(id: String): Boolean {
        val conversations = loadAll()
        val next = conversations.filterNot { it.id == id }
        if (next.size == conversations.size) return false
        saveAll(next)
        return true
    }

    fun create(mode: CrisisSentinelUserMode = defaultMode()): CrisisSentinelConversation {
        val now = System.currentTimeMillis()
        val conversation = CrisisSentinelConversation(
            id = UUID.randomUUID().toString(),
            title = "",
            mode = mode,
            createdAtMillis = now,
            updatedAtMillis = now,
            messages = emptyList(),
            draftText = ""
        )
        saveAll(loadAll() + conversation)
        return conversation
    }

    fun saveDraft(
        id: String?,
        mode: CrisisSentinelUserMode = defaultMode(),
        text: String
    ): CrisisSentinelConversation? {
        val clean = text.trim()
        val conversations = loadAll()
        val existing = id?.let { conversationId ->
            conversations.firstOrNull { it.id == conversationId }
        }

        if (clean.isBlank()) {
            if (existing == null) return null
            if (existing.messages.isEmpty()) {
                saveAll(conversations.filterNot { it.id == existing.id })
                return null
            }
            return mutate(existing.id) { conversation ->
                conversation.copy(draftText = "")
            }
        }

        if (existing != null) {
            return mutate(existing.id) { conversation ->
                val now = System.currentTimeMillis()
                conversation.copy(
                    title = conversation.title.ifBlank { titleFromPrompt(clean) },
                    updatedAtMillis = now,
                    draftText = clean
                )
            }
        }

        val now = System.currentTimeMillis()
        val draft = CrisisSentinelConversation(
            id = UUID.randomUUID().toString(),
            title = titleFromPrompt(clean),
            mode = mode,
            createdAtMillis = now,
            updatedAtMillis = now,
            messages = emptyList(),
            draftText = clean
        )
        saveAll(conversations + draft)
        return draft
    }

    fun appendUserMessage(id: String, text: String): CrisisSentinelConversation? {
        val clean = text.trim()
        if (clean.isBlank()) return load(id)
        return mutate(id) { conversation ->
            val now = System.currentTimeMillis()
            val userMessage = CrisisSentinelChatMessage(
                id = UUID.randomUUID().toString(),
                role = CrisisSentinelChatRole.User,
                text = clean,
                timestampMillis = now
            )
            val nextMessages = conversation.messages + userMessage
            conversation.copy(
                title = conversation.title.ifBlank { titleFromPrompt(clean) },
                updatedAtMillis = now,
                messages = nextMessages,
                draftText = ""
            )
        }
    }

    fun appendAssistantResponse(
        id: String,
        response: CrisisSentinelResponse,
        generationDurationMillis: Long? = null,
        modelName: String? = null,
        cardJson: String? = null,
        mapPoints: List<CrisisSentinelMapPoint> = emptyList(),
        unsupportedToolName: String? = null
    ): CrisisSentinelConversation? {
        return mutate(id) { conversation ->
            val now = System.currentTimeMillis()
            val assistantMessage = CrisisSentinelChatMessage(
                id = UUID.randomUUID().toString(),
                role = CrisisSentinelChatRole.Assistant,
                text = response.answer,
                timestampMillis = now,
                source = response.source,
                confidence = response.confidence.toDouble(),
                generationDurationMillis = generationDurationMillis,
                modelName = modelName,
                cardJson = cardJson,
                mapPoints = mapPoints,
                unsupportedToolName = unsupportedToolName
            )
            conversation.copy(
                updatedAtMillis = now,
                messages = conversation.messages + assistantMessage
            )
        }
    }

    /** Renames a conversation (long-press menu); blank titles are ignored. */
    fun rename(id: String, title: String): CrisisSentinelConversation? {
        val clean = title.trim()
        if (clean.isBlank()) return load(id)
        return mutate(id) { conversation ->
            conversation.copy(
                title = clean.take(TITLE_LIMIT),
                updatedAtMillis = System.currentTimeMillis()
            )
        }
    }

    /** Drops trailing assistant message(s) so the last user prompt can be regenerated in place. */
    fun removeTrailingAssistantMessages(id: String): CrisisSentinelConversation? {
        return mutate(id) { conversation ->
            val trimmed = conversation.messages.toMutableList()
            while (trimmed.isNotEmpty() && trimmed.last().role == CrisisSentinelChatRole.Assistant) {
                trimmed.removeAt(trimmed.lastIndex)
            }
            conversation.copy(
                updatedAtMillis = System.currentTimeMillis(),
                messages = trimmed
            )
        }
    }

    fun defaultMode(): CrisisSentinelUserMode {
        return when (prefs.getString(KEY_DEFAULT_MODE, CrisisSentinelUserMode.Public.name)) {
            CrisisSentinelUserMode.FieldTeam.name -> CrisisSentinelUserMode.FieldTeam
            else -> CrisisSentinelUserMode.Public
        }
    }

    fun saveDefaultMode(mode: CrisisSentinelUserMode) {
        prefs.edit().putString(KEY_DEFAULT_MODE, mode.name).apply()
    }

    /** Last engine the user explicitly picked, or null if they never chose one. */
    fun preferredEngine(): CrisisSentinelEngine? {
        return when (prefs.getString(KEY_PREFERRED_ENGINE, null)) {
            CrisisSentinelEngine.Online.name -> CrisisSentinelEngine.Online
            CrisisSentinelEngine.Edge.name -> CrisisSentinelEngine.Edge
            else -> null
        }
    }

    fun savePreferredEngine(engine: CrisisSentinelEngine) {
        prefs.edit().putString(KEY_PREFERRED_ENGINE, engine.name).apply()
    }

    /** The user's pinned online provider/model; null = server default (Android analog of the
     *  web picker's localStorage entry). */
    fun preferredOnlineModel(): CrisisSentinelOnlineModelChoice? {
        val raw = prefs.getString(KEY_PREFERRED_ONLINE_MODEL, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val providerId = json.optString("providerId").takeIf { it.isNotBlank() }
                ?: return null
            val modelId = json.optString("modelId").takeIf { it.isNotBlank() } ?: return null
            CrisisSentinelOnlineModelChoice(
                providerId = providerId,
                modelId = modelId,
                modelLabel = json.optString("modelLabel").ifBlank { modelId }
            )
        }.getOrNull()
    }

    fun savePreferredOnlineModel(choice: CrisisSentinelOnlineModelChoice?) {
        prefs.edit().apply {
            if (choice == null) {
                remove(KEY_PREFERRED_ONLINE_MODEL)
            } else {
                putString(
                    KEY_PREFERRED_ONLINE_MODEL,
                    JSONObject()
                        .put("providerId", choice.providerId)
                        .put("modelId", choice.modelId)
                        .put("modelLabel", choice.modelLabel)
                        .toString()
                )
            }
        }.apply()
    }

    private fun mutate(
        id: String,
        transform: (CrisisSentinelConversation) -> CrisisSentinelConversation
    ): CrisisSentinelConversation? {
        val conversations = loadAll()
        var updated: CrisisSentinelConversation? = null
        val next = conversations.map { conversation ->
            if (conversation.id == id) {
                transform(conversation).also { updated = it }
            } else {
                conversation
            }
        }
        if (updated != null) {
            saveAll(next)
        }
        return updated
    }

    private fun loadAll(): List<CrisisSentinelConversation> {
        val raw = prefs.getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    parseConversation(array.optJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(conversations: List<CrisisSentinelConversation>) {
        val array = JSONArray()
        conversations.forEach { conversation ->
            array.put(conversation.toJson())
        }
        prefs.edit().putString(KEY_CONVERSATIONS, array.toString()).apply()
    }

    private fun parseConversation(json: JSONObject?): CrisisSentinelConversation? {
        if (json == null) return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val messages = json.optJSONArray("messages") ?: JSONArray()
        val parsedMessages = buildList {
            for (index in 0 until messages.length()) {
                parseMessage(messages.optJSONObject(index))?.let(::add)
            }
        }.filterNot { message ->
            message.role == CrisisSentinelChatRole.Assistant &&
                isLegacyCannedModelMeta(message.text)
        }
        val mode = when (json.optString("mode")) {
            CrisisSentinelUserMode.FieldTeam.name -> CrisisSentinelUserMode.FieldTeam
            CrisisSentinelUserMode.Coordinator.name -> CrisisSentinelUserMode.Coordinator
            else -> CrisisSentinelUserMode.Public
        }
        return CrisisSentinelConversation(
            id = id,
            title = json.optString("title"),
            mode = mode,
            createdAtMillis = json.optLong("createdAtMillis", 0L),
            updatedAtMillis = json.optLong("updatedAtMillis", 0L),
            messages = parsedMessages,
            draftText = json.optString("draftText")
        )
    }

    private fun parseMessage(json: JSONObject?): CrisisSentinelChatMessage? {
        if (json == null) return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val role = when (json.optString("role")) {
            CrisisSentinelChatRole.Assistant.name -> CrisisSentinelChatRole.Assistant
            else -> CrisisSentinelChatRole.User
        }
        val source = when (json.optString("source")) {
            CrisisSentinelResponseSource.LocalModel.name -> CrisisSentinelResponseSource.LocalModel
            CrisisSentinelResponseSource.OfflineRules.name -> CrisisSentinelResponseSource.OfflineRules
            CrisisSentinelResponseSource.OnlineModel.name -> CrisisSentinelResponseSource.OnlineModel
            else -> null
        }
        return CrisisSentinelChatMessage(
            id = id,
            role = role,
            text = json.optString("text"),
            timestampMillis = json.optLong("timestampMillis", 0L),
            source = source,
            confidence = if (json.has("confidence")) json.optDouble("confidence") else null,
            generationDurationMillis = if (json.has("generationDurationMillis")) {
                json.optLong("generationDurationMillis")
            } else {
                null
            },
            modelName = json.optString("modelName").takeIf { it.isNotBlank() },
            cardJson = json.optString("cardJson").takeIf { it.isNotBlank() },
            mapPoints = parseMapPoints(json.optJSONArray("mapPoints"))
        )
    }

    private fun parseMapPoints(array: JSONArray?): List<CrisisSentinelMapPoint> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val lat = point.optDouble("lat", Double.NaN)
                val lng = point.optDouble("lng", Double.NaN)
                if (lat.isNaN() || lng.isNaN()) continue
                add(
                    CrisisSentinelMapPoint(
                        lat = lat,
                        lng = lng,
                        label = point.optString("label"),
                        details = point.optString("details").takeIf { it.isNotBlank() },
                        type = point.optString("type").takeIf { it.isNotBlank() }
                    )
                )
            }
        }
    }

    private fun CrisisSentinelConversation.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("title", title)
            .put("mode", mode.name)
            .put("createdAtMillis", createdAtMillis)
            .put("updatedAtMillis", updatedAtMillis)
            .put("draftText", draftText)
            .put(
                "messages",
                JSONArray().also { array ->
                    messages.forEach { array.put(it.toJson()) }
                }
            )
    }

    private fun CrisisSentinelChatMessage.toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("role", role.name)
            .put("text", text)
            .put("timestampMillis", timestampMillis)
            .apply {
                source?.let { put("source", it.name) }
                confidence?.let { put("confidence", it) }
                generationDurationMillis?.let { put("generationDurationMillis", it) }
                modelName?.let { put("modelName", it) }
                cardJson?.let { put("cardJson", it) }
                if (mapPoints.isNotEmpty()) {
                    put(
                        "mapPoints",
                        JSONArray().also { array ->
                            mapPoints.forEach { point ->
                                array.put(
                                    JSONObject()
                                        .put("lat", point.lat)
                                        .put("lng", point.lng)
                                        .put("label", point.label)
                                        .apply {
                                            point.details?.let { put("details", it) }
                                            point.type?.let { put("type", it) }
                                        }
                                )
                            }
                        }
                    )
                }
                // unsupportedToolName is intentionally NOT serialized (transient notice).
            }
    }

    private fun titleFromPrompt(prompt: String): String {
        val firstLine = prompt
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
        if (firstLine.length <= TITLE_LIMIT) return firstLine
        return "${firstLine.take(TITLE_LIMIT - 1).trimEnd()}..."
    }

    private fun isLegacyCannedModelMeta(text: String): Boolean {
        val normalized = CrisisSentinelText.normalize(text)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return listOf(
            "bu cikti",
            "offline on device",
            "on device asistan",
            "yerel model",
            "local model"
        ).count { normalized.contains(it) } >= 2
    }

    private companion object {
        private const val PREFS_NAME = "crisis_sentinel_conversations"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_DEFAULT_MODE = "default_mode"
        private const val KEY_PREFERRED_ENGINE = "preferred_engine"
        private const val KEY_PREFERRED_ONLINE_MODEL = "preferred_online_model"
        private const val TITLE_LIMIT = 48
    }
}
