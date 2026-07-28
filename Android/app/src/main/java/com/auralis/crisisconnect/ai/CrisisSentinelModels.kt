package com.auralis.crisisconnect.ai

import java.util.Locale

enum class CrisisSentinelUserMode {
    Public,
    FieldTeam,
    Coordinator
}

enum class CrisisSentinelResponseSource {
    OfflineRules,
    LocalModel,
    OnlineModel
}

/**
 * Which inference backend answers a chat turn. [Online] is the field-team-only cloud engine
 * (web dashboard's Crisis Sentinel); [Edge] is the on-device LiteRT-LM model.
 */
enum class CrisisSentinelEngine {
    Online,
    Edge
}

enum class CrisisSentinelQueryDomain {
    CrisisGuided,
    General
}

enum class CrisisSentinelIncidentType {
    Unknown,
    Earthquake,
    Fire,
    Flood,
    Hazard,
    Medical,
    Trapped,
    Communication,
    Logistics,
    Shelter,
    PublicHealth,
    Translation,
    Strategy,
    Evacuation,
    MentalHealth
}

enum class CrisisSentinelPriority {
    Routine,
    High,
    Immediate
}

data class CrisisSentinelRequest(
    val prompt: String,
    val mode: CrisisSentinelUserMode = CrisisSentinelUserMode.Public,
    val locale: Locale = Locale.getDefault(),
    val recentMessages: List<String> = emptyList()
)

data class CrisisSentinelResponse(
    val answer: String,
    val mode: CrisisSentinelUserMode,
    val source: CrisisSentinelResponseSource,
    val citations: List<CrisisSentinelCitation>,
    val incidentDraft: CrisisSentinelIncidentDraft?,
    val followUpQuestions: List<String>,
    val safetyNotices: List<String>,
    val confidence: Float
)

data class CrisisSentinelCitation(
    val articleId: String,
    val title: String,
    val category: String,
    val snippet: String,
    val score: Int
)

data class CrisisSentinelIncidentDraft(
    val type: CrisisSentinelIncidentType,
    val priority: CrisisSentinelPriority,
    val casualtyCount: Int?,
    val trappedCount: Int?,
    val locationText: String?,
    val hazards: List<String>,
    val needs: List<String>,
    val missingFields: List<String>,
    val confidence: Float
)

data class CrisisSentinelModelRequest(
    val systemInstruction: String,
    val userPrompt: String,
    val locale: Locale,
    val mode: CrisisSentinelUserMode,
    val queryDomain: CrisisSentinelQueryDomain,
    val responseSchema: String,
    val contextSnippets: List<String>,
    val recentMessages: List<String> = emptyList()
)

data class CrisisSentinelModelResponse(
    val text: String,
    val structuredJson: String? = null,
    val confidence: Float? = null
)

interface CrisisSentinelModelRuntime {
    val id: String
    val isReady: Boolean

    suspend fun generate(request: CrisisSentinelModelRequest): CrisisSentinelModelResponse

    fun cancelGeneration() = Unit
}
