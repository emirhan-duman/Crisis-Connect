package com.auralis.crisisconnect.ai

import org.json.JSONArray
import org.json.JSONObject

internal object CrisisSentinelModelOutputValidator {
    fun validate(
        request: CrisisSentinelRequest,
        modelResponse: CrisisSentinelModelResponse,
        allowedCitations: List<CrisisSentinelCitation>,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain = CrisisSentinelQueryDomain.CrisisGuided
    ): CrisisSentinelResponse? {
        val rawText = modelResponse.structuredJson ?: modelResponse.text
        // Shared fallthrough: when the output isn't a usable structured object (e.g. prose followed
        // by a partial/foreign JSON dump), treat it as plain text so the prose is kept and any
        // JSON tail is stripped, instead of discarding the whole answer.
        fun asPlainText() = validatePlainText(
            request = request,
            modelResponse = modelResponse,
            allowedCitations = allowedCitations,
            fallback = fallback,
            queryDomain = queryDomain
        )
        val jsonText = extractJsonObject(rawText) ?: return asPlainText()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return asPlainText()
        val answer = root.optCleanString("answer") ?: return asPlainText()
        if (isCannedModelMetaAnswer(answer, request)) return null
        if (isLowSignalAnswer(answer, request, fallback, queryDomain)) return null
        if (containsUnsafeHazardAdvice(answer, fallback)) return null
        if (containsUnsafeOperationalAuthority(answer, request)) return null
        val completedAnswer = CrisisSentinelSafetyCoverage.completeAnswer(
            answer = answer,
            request = request,
            fallback = fallback,
            queryDomain = queryDomain
        )
        if (containsUnsafeHazardAdvice(completedAnswer, fallback)) return null
        if (missesRequiredHazardSafety(completedAnswer, fallback)) return null
        if (containsUnsafeMentalHealthAdvice(completedAnswer, request, fallback)) return null
        if (missesRequiredMedicalBoundary(completedAnswer, request, fallback)) return null
        if (containsUnsafeOperationalAuthority(completedAnswer, request)) return null

        val citations = parseCitations(root.optJSONArray("citations"), allowedCitations) ?: return null
        val draft = root.optJSONObject("incidentDraft")?.let { parseIncidentDraft(it) } ?: fallback.incidentDraft
        if (fallback.incidentDraft?.type == CrisisSentinelIncidentType.Hazard &&
            draft?.type != CrisisSentinelIncidentType.Hazard
        ) {
            return null
        }

        val followUps = parseStringArray(root.optJSONArray("followUpQuestions"))
            .ifEmpty { fallback.followUpQuestions }
        val modelSafetyNotices = parseStringArray(root.optJSONArray("safetyNotices"))
        val safetyNotices = mergeSafetyNotices(modelSafetyNotices, fallback.safetyNotices)
        if (containsUnsafeHazardAdvice((listOf(completedAnswer) + followUps + modelSafetyNotices).joinToString(" "), fallback)) {
            return null
        }
        if (missesRequiredHazardSafety((listOf(completedAnswer) + modelSafetyNotices).joinToString(" "), fallback)) {
            return null
        }

        val confidence = (modelResponse.confidence ?: fallback.confidence)
            .coerceIn(0.25f, 0.92f)

        return CrisisSentinelResponse(
            answer = completedAnswer,
            mode = request.mode,
            source = CrisisSentinelResponseSource.LocalModel,
            citations = citations.ifEmpty { fallback.citations },
            incidentDraft = draft,
            followUpQuestions = followUps,
            safetyNotices = safetyNotices,
            confidence = confidence
        )
    }

    private fun validatePlainText(
        request: CrisisSentinelRequest,
        modelResponse: CrisisSentinelModelResponse,
        allowedCitations: List<CrisisSentinelCitation>,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ): CrisisSentinelResponse? {
        val answer = cleanPlainText(modelResponse.text) ?: return null
        if (isCannedModelMetaAnswer(answer, request)) return null
        if (isLowSignalAnswer(answer, request, fallback, queryDomain)) return null
        if (containsUnsafeHazardAdvice(answer, fallback)) return null
        if (containsUnsafeOperationalAuthority(answer, request)) return null
        val completedAnswer = CrisisSentinelSafetyCoverage.completeAnswer(
            answer = answer,
            request = request,
            fallback = fallback,
            queryDomain = queryDomain
        )
        if (containsUnsafeHazardAdvice(completedAnswer, fallback)) return null
        if (missesRequiredHazardSafety(completedAnswer, fallback)) return null
        if (containsUnsafeMentalHealthAdvice(completedAnswer, request, fallback)) return null
        if (missesRequiredMedicalBoundary(completedAnswer, request, fallback)) return null
        if (containsUnsafeOperationalAuthority(completedAnswer, request)) return null
        val confidence = (modelResponse.confidence ?: fallback.confidence)
            .coerceIn(0.25f, 0.82f)
        return CrisisSentinelResponse(
            answer = completedAnswer,
            mode = request.mode,
            source = CrisisSentinelResponseSource.LocalModel,
            citations = allowedCitations.ifEmpty { fallback.citations },
            incidentDraft = fallback.incidentDraft,
            followUpQuestions = fallback.followUpQuestions,
            safetyNotices = fallback.safetyNotices,
            confidence = confidence
        )
    }

    private fun cleanPlainText(text: String): String? {
        val clean = stripTrailingJsonBlock(
            text
                .replace(Regex("(?is)<start_of_turn>.*?\\n"), "")
                .replace("<end_of_turn>", "")
                .replace("```json", "")
                .replace("```", "")
                .trim()
        ).trim()
        if (clean.length < 16) return null
        val normalized = CrisisSentinelText.normalize(clean)
        if (looksLikePromptOrSchemaLeak(clean, normalized)) return null
        return clean.take(MAX_PLAIN_TEXT_CHARS)
    }

    /**
     * Models (notably Gemma 3n) sometimes append a JSON dump like `{"response": "..."}` after the
     * plain-text answer even when told to return plain text. Keep only the natural-language prose
     * before that block so the bubble doesn't show raw JSON.
     */
    private fun stripTrailingJsonBlock(text: String): String {
        val braceIndex = text.indexOf('{')
        if (braceIndex <= 0) return text
        val tail = text.substring(braceIndex)
        val looksStructured = JSON_TAIL_MARKERS.any { tail.contains(it, ignoreCase = true) }
        if (!looksStructured) return text
        val before = text.substring(0, braceIndex).trim()
        return if (before.length >= 16) before else text
    }

    private val JSON_TAIL_MARKERS = listOf(
        "\"response\"", "\"answer\"", "\"cevap\"", "\"citations\"",
        "\"incidentDraft\"", "\"safetyNotices\"", "\"followUpQuestions\""
    )

    private fun looksLikePromptOrSchemaLeak(clean: String, normalized: String): Boolean {
        val rawLower = clean.lowercase()
        if (clean.startsWith("{") || clean.startsWith("[")) return true
        return listOf(
            "response schema",
            "context snippets",
            "return only one valid",
            "you are crisis sentinel edge",
            "sen crisis sentinel edge",
            "sistem prompt",
            "system prompt",
            "talimatlarim",
            "talimatlarım",
            "kurallar:",
            "politika metni",
            "incidentdraft",
            "casualtycount",
            "trappedcount",
            "locationtext",
            "followupquestions",
            "safetynotices",
            "articleid",
            "short localized response",
            "earthquake|fire|flood|hazard",
            "\"answer\"",
            "\"citations\"",
            "\"type\"",
            "\"priority\""
        ).any { normalized.contains(it) || rawLower.contains(it) }
    }

    private fun isLowSignalAnswer(
        answer: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ): Boolean {
        val normalized = CrisisSentinelText.normalize(answer)
        val metadataOnly = listOf(
            "konum tr tr",
            "dili turkce",
            "locale",
            "mode public",
            "mode fieldteam",
            "mode coordinator"
        ).any { normalized.contains(it) }
        if (metadataOnly) return true
        if (isUsageQuestion(request.prompt) && !looksLikeUsageAnswer(answer)) return true
        if (queryDomain == CrisisSentinelQueryDomain.General) return false

        val expectedTokens = (
            CrisisSentinelText.tokens(request.prompt) +
                CrisisSentinelText.tokens(fallback.answer) +
                fallback.citations.flatMap { citation ->
                    CrisisSentinelText.tokens("${citation.title} ${citation.snippet}")
                }
            ).toSet()
        if (expectedTokens.isEmpty()) return false
        val answerTokens = CrisisSentinelText.tokens(answer).toSet()
        val overlap = answerTokens.count { it in expectedTokens }
        return overlap < 2
    }

    private fun isUsageQuestion(prompt: String): Boolean {
        val normalized = CrisisSentinelText.normalize(prompt)
        if (normalized.isBlank()) return false
        val asksCapability = listOf(
            "seni nasil kullan",
            "nasil kullan",
            "ne yapabilirsin",
            "ne ise yararsin",
            "yardim eder misin",
            "komut",
            "ornek soru",
            "how can i use",
            "what can you do"
        ).any { normalized.contains(it) }
        return asksCapability
    }

    private fun looksLikeUsageAnswer(answer: String): Boolean {
        val normalized = CrisisSentinelText.normalize(answer)
        if (answer.trim().endsWith("?")) return false
        val usageSignals = listOf(
            "kullan",
            "sor",
            "yaz",
            "saha",
            "afet",
            "hazirlik",
            "tahliye",
            "ilk yardim",
            "foto",
            "dosya",
            "dikte",
            "rapor",
            "prompt",
            "ask",
            "provide",
            "field",
            "preparedness",
            "evacuation",
            "report"
        )
        return usageSignals.count { normalized.contains(it) } >= 2
    }

    private fun isCannedModelMetaAnswer(answer: String, request: CrisisSentinelRequest): Boolean {
        val normalized = CrisisSentinelText.normalize(answer)
        val prompt = CrisisSentinelText.normalize(request.prompt)
        val startsAsMeta = listOf(
            "bu cikti",
            "this output"
        ).any { normalized.startsWith(it) }
        if (startsAsMeta) return true

        val modelMetaSignals = listOf(
            "offline on-device",
            "offline on device",
            "on-device asistan",
            "on device assistant",
            "yerel model",
            "local model",
            "crisis sentinel edgedir",
            "crisis sentinel edge dir"
        ).any { normalized.contains(CrisisSentinelText.normalize(it)) }
        if (!modelMetaSignals) return false

        val promptAsksIdentity = listOf(
            "kimsin",
            "nesin",
            "crisis sentinel nedir",
            "crisis sentinel edge nedir",
            "who are you",
            "what are you"
        ).any { prompt.contains(CrisisSentinelText.normalize(it)) }
        if (promptAsksIdentity && looksLikeUsageAnswer(answer)) return false

        return true
    }

    private fun containsUnsafeOperationalAuthority(text: String, request: CrisisSentinelRequest): Boolean {
        val normalized = CrisisSentinelText.normalize(text)
        val alwaysUnsafe = listOf(
            "kesin emir",
            "resmi emir verildi",
            "otomatik sevk edildi",
            "ambulans sevk ettim",
            "komuta yerine gecer",
            "command is replaced"
        ).any { normalized.contains(CrisisSentinelText.normalize(it)) }
        if (alwaysUnsafe) return true

        val looksLikeDispatchOrOrder = listOf(
            "gorev verildi",
            "ekiplere gorev ver",
            "ekipleri yonlendir",
            "sevk edildi",
            "dispatch completed",
            "official order"
        ).any { normalized.contains(CrisisSentinelText.normalize(it)) }
        if (!looksLikeDispatchOrOrder) return false
        if (request.mode != CrisisSentinelUserMode.Coordinator) return true

        val hasDraftBoundary = listOf(
            "taslak",
            "onay",
            "komuta",
            "kurum",
            "draft",
            "approval",
            "command"
        ).any { normalized.contains(CrisisSentinelText.normalize(it)) }
        return !hasDraftBoundary
    }

    private fun parseCitations(
        citationsJson: JSONArray?,
        allowedCitations: List<CrisisSentinelCitation>
    ): List<CrisisSentinelCitation>? {
        if (citationsJson == null || citationsJson.length() == 0) return emptyList()
        val allowedById = allowedCitations.associateBy { it.articleId }
        val parsed = mutableListOf<CrisisSentinelCitation>()
        for (index in 0 until citationsJson.length()) {
            val item = citationsJson.optJSONObject(index) ?: return null
            val articleId = item.optCleanString("articleId") ?: return null
            parsed += allowedById[articleId] ?: return null
        }
        return parsed.distinctBy { it.articleId }
    }

    private fun parseIncidentDraft(json: JSONObject): CrisisSentinelIncidentDraft? {
        val type = parseIncidentType(json.optCleanString("type")) ?: return null
        val priority = parsePriority(json.optCleanString("priority")) ?: return null
        return CrisisSentinelIncidentDraft(
            type = type,
            priority = priority,
            casualtyCount = json.optNullableInt("casualtyCount"),
            trappedCount = json.optNullableInt("trappedCount"),
            locationText = json.optCleanString("locationText"),
            hazards = parseStringArray(json.optJSONArray("hazards")),
            needs = parseStringArray(json.optJSONArray("needs")),
            missingFields = parseStringArray(json.optJSONArray("missingFields")),
            confidence = 0.72f
        )
    }

    private fun parseIncidentType(value: String?): CrisisSentinelIncidentType? {
        return when (value.normalizedKey()) {
            "unknown" -> CrisisSentinelIncidentType.Unknown
            "earthquake" -> CrisisSentinelIncidentType.Earthquake
            "fire" -> CrisisSentinelIncidentType.Fire
            "flood" -> CrisisSentinelIncidentType.Flood
            "hazard" -> CrisisSentinelIncidentType.Hazard
            "medical" -> CrisisSentinelIncidentType.Medical
            "trapped" -> CrisisSentinelIncidentType.Trapped
            "communication" -> CrisisSentinelIncidentType.Communication
            "logistics" -> CrisisSentinelIncidentType.Logistics
            "shelter" -> CrisisSentinelIncidentType.Shelter
            "publichealth" -> CrisisSentinelIncidentType.PublicHealth
            "translation" -> CrisisSentinelIncidentType.Translation
            "strategy" -> CrisisSentinelIncidentType.Strategy
            "evacuation" -> CrisisSentinelIncidentType.Evacuation
            "mentalhealth" -> CrisisSentinelIncidentType.MentalHealth
            else -> null
        }
    }

    private fun parsePriority(value: String?): CrisisSentinelPriority? {
        return when (value.normalizedKey()) {
            "routine" -> CrisisSentinelPriority.Routine
            "high" -> CrisisSentinelPriority.High
            "immediate" -> CrisisSentinelPriority.Immediate
            else -> null
        }
    }

    private fun parseStringArray(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isNotEmpty()) values += value
        }
        return values.distinct()
    }

    private fun mergeSafetyNotices(
        modelSafetyNotices: List<String>,
        fallbackSafetyNotices: List<String>
    ): List<String> {
        return (modelSafetyNotices + fallbackSafetyNotices)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun containsUnsafeHazardAdvice(text: String, fallback: CrisisSentinelResponse): Boolean {
        val draft = fallback.incidentDraft
        val hazardPrompt = draft?.type == CrisisSentinelIncidentType.Hazard ||
            draft?.hazards?.any { it == "gaz kokusu" || it == "kimyasal/sızıntı riski" } == true
        if (!hazardPrompt) return false

        val normalized = CrisisSentinelText.normalize(text)
        val unsafePhrases = listOf(
            "kokuyu takip",
            "koku kaynagini takip",
            "gaz kaynagini bul",
            "gaz kaynagini ara",
            "sigortayi kapat",
            "gaz vanasini kapat",
            "vanayi kapat",
            "kaynak bulmaya calis",
            "kendin onar",
            "kendiniz onarin",
            "maskeyle gir",
            "maske takip gir",
            "iceri gir",
            "follow the smell",
            "find the gas source",
            "repair the gas",
            "wear a mask and enter",
            "go inside"
        )
        return unsafePhrases.any { normalized.contains(it) }
    }

    private fun containsUnsafeMentalHealthAdvice(
        text: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ): Boolean {
        val prompt = CrisisSentinelText.normalize(request.prompt)
        val draft = fallback.incidentDraft
        val selfHarmPrompt = draft?.type == CrisisSentinelIncidentType.MentalHealth ||
            listOf("kendime zarar", "kendine zarar", "olmek ist", "intihar").any { prompt.contains(it) }
        if (!selfHarmPrompt) return false

        val normalized = CrisisSentinelText.normalize(text)
        val unsafePhrases = listOf(
            "kimseye soyleme",
            "gizli tut",
            "aramizda kalsin",
            "planini sakla",
            "doz",
            "sakinlestirici",
            "terapi yaparim",
            "keep it secret",
            "do not tell anyone"
        ).any { normalized.contains(CrisisSentinelText.normalize(it)) }
        if (unsafePhrases) return true

        val tellsToStayAlone = listOf(
            "yalniz kal ",
            "yalniz kal.",
            "yalniz kalman",
            "yalniz kalmani",
            "yalniz birak"
        ).any { normalized.contains(it) } &&
            !listOf("yalniz kalma", "yalniz birakma", "yalniz kalmamali").any { normalized.contains(it) }
        if (tellsToStayAlone) return true

        val hasEmergency = listOf("112", "acil", "kriz", "saglik", "health", "emergency").any { normalized.contains(it) }
        val hasTrustedPerson = listOf("guvenilir", "guvendigin", "trusted", "yanina cagir", "yanina çağır").any {
            normalized.contains(CrisisSentinelText.normalize(it))
        }
        return !(hasEmergency && hasTrustedPerson)
    }

    private fun missesRequiredMedicalBoundary(
        text: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ): Boolean {
        val prompt = CrisisSentinelText.normalize(request.prompt)
        val draft = fallback.incidentDraft
        val medicalPrompt = draft?.type == CrisisSentinelIncidentType.Medical ||
            listOf("kanama", "gogus", "nefes", "kalp", "yarali", "yaralı").any {
                prompt.contains(CrisisSentinelText.normalize(it))
            }
        if (!medicalPrompt) return false

        val normalized = CrisisSentinelText.normalize(text)
        val noDiagnosis = listOf("tani", "teshis", "diagnosis", "not a diagnosis").any { normalized.contains(it) }
        val emergency = listOf("112", "acil", "saglik", "emergency", "medical").any { normalized.contains(it) }
        val unsafeMedication = listOf("mg", "doz", "recete yaz", "ilac verilebilir", "sakinlestirici ver").any {
            normalized.contains(CrisisSentinelText.normalize(it))
        }
        return unsafeMedication || !(noDiagnosis && emergency)
    }

    private fun missesRequiredHazardSafety(text: String, fallback: CrisisSentinelResponse): Boolean {
        val draft = fallback.incidentDraft
        val hasGasOrChemical = draft?.hazards?.any {
            it == "gaz kokusu" || it == "kimyasal/sızıntı riski"
        } == true || fallback.citations.any { it.articleId == "CSE-GAS-001" }
        if (!hasGasOrChemical) return false

        val normalized = CrisisSentinelText.normalize(text)
        val nonsensicalOrRisky = listOf(
            "dumani kapat",
            "kokuyu kapat",
            "gazi kapat",
            "gaz vanasini kapat",
            "havalandir"
        ).any { normalized.contains(it) }
        if (nonsensicalOrRisky) return true

        val hasEvacuation = listOf(
            "tahliye",
            "disari cik",
            "guvenli alana gec",
            "guvenli mesafeye gec",
            "uzaklas",
            "leave",
            "evacuate",
            "safe distance"
        ).any { normalized.contains(it) }
        val warnsSparkSources = listOf(
            "elektrik anahtari",
            "priz",
            "sigorta",
            "kivilcim",
            "ates",
            "cakmak",
            "switch",
            "plug",
            "breaker",
            "spark",
            "flame",
            "lighter"
        ).any { normalized.contains(it) }
        val callsResponder = listOf(
            "112",
            "itfaiye",
            "gaz ekibi",
            "tehlikeli madde",
            "hazmat",
            "emergency",
            "fire",
            "gas team"
        ).any { normalized.contains(it) }
        return !(hasEvacuation && warnsSparkSources && callsResponder)
    }

    private fun extractJsonObject(text: String): String? {
        var start = -1
        var depth = 0
        var inString = false
        var escaping = false

        for (index in text.indices) {
            val char = text[index]
            if (start == -1) {
                if (char == '{') {
                    start = index
                    depth = 1
                }
                continue
            }

            if (escaping) {
                escaping = false
                continue
            }
            if (char == '\\' && inString) {
                escaping = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) continue

            when (char) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private const val MAX_PLAIN_TEXT_CHARS = 1_200

    private fun JSONObject.optCleanString(name: String): String? {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return value.toString().trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        val value = opt(name)
        if (value == null || value == JSONObject.NULL) return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun String?.normalizedKey(): String {
        return this
            ?.trim()
            ?.replace("_", "")
            ?.replace("-", "")
            ?.lowercase()
            .orEmpty()
    }
}
