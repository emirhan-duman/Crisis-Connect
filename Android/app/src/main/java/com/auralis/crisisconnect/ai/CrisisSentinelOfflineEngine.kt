package com.auralis.crisisconnect.ai

import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

class CrisisSentinelOfflineEngine(
    private val knowledgeIndex: CrisisSentinelKnowledgeIndex = CrisisSentinelKnowledgeIndex.fromGuide(),
    private val modelRuntime: CrisisSentinelModelRuntime? = null,
    private val onModelFailure: ((Throwable) -> Unit)? = null,
    private val onModelRejected: ((String) -> Unit)? = null
) {
    fun respond(request: CrisisSentinelRequest): CrisisSentinelResponse {
        val cleanPrompt = request.prompt.trim()
        val incidentDraft = CrisisSentinelIncidentExtractor.extract(
            listOf(cleanPrompt, request.recentMessages.joinToString(" ")).joinToString(" ")
        )
        val citations = knowledgeIndex.search(
            query = if (cleanPrompt.isBlank()) request.recentMessages.joinToString(" ") else cleanPrompt,
            limit = 3
        )

        val guideCitation = citations.firstOrNull()
        val criticalAnswer = buildCriticalPlainAnswer(cleanPrompt, request.mode, request.locale)
        val queryDomain = classifyQueryDomain(
            request = request,
            cleanPrompt = cleanPrompt,
            incidentDraft = incidentDraft,
            criticalAnswer = criticalAnswer
        )
        val answer = when {
            request.mode == CrisisSentinelUserMode.Coordinator && incidentDraft != null ->
                buildCoordinatorAnswer(incidentDraft, citations, request.locale)
            request.mode == CrisisSentinelUserMode.FieldTeam && incidentDraft != null ->
                buildFieldTeamAnswer(incidentDraft, citations, request.locale)
            criticalAnswer != null ->
                criticalAnswer
            isUsageQuestion(cleanPrompt) ->
                buildUsageAnswer(request.locale)
            request.recentMessages.isNotEmpty() && cleanPrompt.isBlank() ->
                buildMessageSummary(request.recentMessages, incidentDraft, request.locale)
            queryDomain == CrisisSentinelQueryDomain.CrisisGuided &&
                guideCitation != null &&
                guideCitation.score >= MIN_GUIDE_SCORE ->
                buildGuideAnswer(guideCitation, request.locale)
            else ->
                fallbackAnswer(cleanPrompt, request.locale, queryDomain)
        }

        val followUps = followUpQuestions(incidentDraft, request.locale)
        val safetyNotices = safetyNotices(request.locale, incidentDraft, queryDomain)
        val responseCitations = if (queryDomain == CrisisSentinelQueryDomain.CrisisGuided) {
            citations
        } else {
            emptyList()
        }
        val confidence = maxOf(
            incidentDraft?.confidence ?: 0f,
            responseCitations.firstOrNull()?.score?.let { (it / 20f).coerceIn(0.25f, 0.86f) } ?: 0.22f
        )

        return CrisisSentinelResponse(
            answer = answer,
            mode = request.mode,
            source = CrisisSentinelResponseSource.OfflineRules,
            citations = responseCitations,
            incidentDraft = incidentDraft,
            followUpQuestions = followUps,
            safetyNotices = safetyNotices,
            confidence = confidence
        )
    }

    suspend fun respondWithModel(request: CrisisSentinelRequest): CrisisSentinelResponse {
        val fallback = respond(request)
        val runtime = modelRuntime ?: return fallback
        if (!runtime.isReady) return fallback

        val queryDomain = classifyQueryDomain(request, fallback)
        val modelRequest = buildGemmaRequest(request, fallback, queryDomain)
        val modelResponse = try {
            withTimeoutOrNull(MODEL_RUNTIME_TIMEOUT_MS) {
                runtime.generate(modelRequest)
            } ?: run {
                runtime.cancelGeneration()
                return fallback
            }
        } catch (throwable: Throwable) {
            onModelFailure?.invoke(throwable)
            return fallback
        }
        val allowedCitations = if (queryDomain == CrisisSentinelQueryDomain.CrisisGuided) {
            knowledgeIndex.search(
                query = if (request.prompt.isBlank()) request.recentMessages.joinToString(" ") else request.prompt,
                limit = 4
            )
        } else {
            emptyList()
        }
        val validated = CrisisSentinelModelOutputValidator.validate(
            request = request,
            modelResponse = modelResponse,
            allowedCitations = allowedCitations,
            fallback = fallback,
            queryDomain = queryDomain
        )
        if (validated == null) {
            onModelRejected?.invoke(modelResponse.text.take(240))
        }
        return validated ?: fallback
    }

    fun buildGemmaRequest(request: CrisisSentinelRequest): CrisisSentinelModelRequest {
        val fallback = respond(request)
        return buildGemmaRequest(request, fallback, classifyQueryDomain(request, fallback))
    }

    private fun buildGemmaRequest(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ): CrisisSentinelModelRequest {
        val toolResult = runLocalKnowledgeTool(request, fallback, queryDomain)
        val trustedContext = buildList {
            addAll(toolResult.toContextSnippets())
            if (toolResult.isRelevant) {
                add(roleInstruction(request.mode, request.locale))
                add("Trusted local answer draft: ${fallback.answer.replace(Regex("\\s+"), " ").take(420)}")
                if (fallback.safetyNotices.isNotEmpty()) {
                    add("Safety boundaries: ${fallback.safetyNotices.joinToString(" ").replace(Regex("\\s+"), " ").take(220)}")
                }
                addAll(CrisisSentinelSafetyCoverage.contextSnippets(request, fallback))
                fallback.incidentDraft?.let { draft ->
                    add(
                        "Detected incident: type=${draft.type.name}, priority=${draft.priority.name}, " +
                        "hazards=${draft.hazards.joinToString(", ")}, needs=${draft.needs.joinToString(", ")}"
                    )
                }
            }
        }.distinct()
        // Small on-device models drift toward the instruction language, so for Turkish the whole
        // system prompt is written in Turkish instead of English + "always answer in Turkish".
        val systemInstruction = if (CrisisSentinelText.isTurkish(request.locale)) {
            if (queryDomain == CrisisSentinelQueryDomain.CrisisGuided) {
                "Sen Crisis Sentinel Edge'sin; yerel yapay zekâ asistanısın ve cevaplarını her zaman Türkçe verirsin. local_knowledge_search adlı yerel bir aracın var. Afet, kriz, ilk yardım, tıbbi, psikoloji, lojistik, çeviri ve saha ekibi soruları için local_knowledge_search sonucunu ve güvenilir yerel taslağı dayanak al. Güvenlik sınırlarını ve kullanıcı rolünü koru. Genel kullanıcılara yalnızca güvenli yönlendirme ver. Saha ekiplerine yalnızca gözlem/SITREP taslağı ver. Yetkili koordinatörlere görevlendirme/kaynak taslağı verebilirsin ama asla nihai resmi emir verme; insan komuta onayı gerekir. Koordinat, teşhis, tedavi iddiası, kaynak kimliği, sevk veya doğrulanmış sayı uydurma. Gaz/kimyasal tehlikede: tahliye, anahtar/kıvılcım/alevden uzak durma, mesafe ve acil servis/gaz ekiplerini arama önceliklidir. Kısa düz metin döndür, JSON yazma."
            } else {
                "Yardımsever, çevrimdışı bir yapay zekâ sohbet asistanısın ve Türkçe cevap verirsin. local_knowledge_search sonucu: kullanıcı somut bir afet, ilk yardım, tıbbi, kendine zarar verme veya saha operasyonu durumu anlatmadıkça bu mesaj için not_relevant. Günlük sohbet, yazma, çeviri, kodlama, fikir üretme ve açıklamalarda kısa, doğal ve faydalı ol. Kullanıcı sormadıkça kendini, modeli veya uygulamayı anlatma. Güvenlik açısından kritik afet, tıbbi, kendine zarar verme veya saha operasyonu sorularında güvensiz kesinlikten kaçın ve gerektiğinde acil yardım çağrılmasını söyle."
            }
        } else {
            val responseLanguage = CrisisSentinelText.responseLanguageName(request.locale)
            if (queryDomain == CrisisSentinelQueryDomain.CrisisGuided) {
                "You are Crisis Sentinel Edge. Always answer in $responseLanguage as the local AI assistant. You have a local tool named local_knowledge_search. For disaster, crisis, first-aid, medical, psychology, logistics, translation, and field-team questions, use the local_knowledge_search tool result and the trusted draft as grounding. Preserve safety boundaries and the user role. Public users get safe guidance only. Field teams get observation/SITREP drafts only. Authorized coordinators may get tasking/resource drafts, but never final official orders; human command approval is required. Do not invent coordinates, diagnoses, treatment claims, citation IDs, dispatches, or verified counts. For gas/chemical hazards: evacuate, avoid switches/sparks/flames, keep distance, and call emergency/gas/hazmat teams. Return short plain text, not JSON."
            } else {
                "You are a helpful offline AI chat assistant. Answer in $responseLanguage. local_knowledge_search result: not_relevant for this message unless the user provides a concrete disaster, first-aid, medical, self-harm, or field-operation situation. Be concise, natural, and useful for everyday conversation, writing, translation, coding, brainstorming, and explanations. Do not describe yourself, the model, or the app unless the user asks. If the user asks about safety-critical disaster, medical, self-harm, or field operations, avoid unsafe certainty and ask for emergency help when needed."
            }
        }
        return CrisisSentinelModelRequest(
            systemInstruction = systemInstruction,
            userPrompt = request.prompt,
            locale = request.locale,
            mode = request.mode,
            queryDomain = queryDomain,
            responseSchema = if (queryDomain == CrisisSentinelQueryDomain.CrisisGuided) gemmaResponseSchema else "",
            contextSnippets = trustedContext,
            recentMessages = request.recentMessages.takeLast(4).map { it.take(160) }
        )
    }

    private fun runLocalKnowledgeTool(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ): CrisisSentinelLocalKnowledgeToolResult {
        val query = if (request.prompt.isBlank()) request.recentMessages.joinToString(" ") else request.prompt
        if (queryDomain == CrisisSentinelQueryDomain.General) {
            return CrisisSentinelLocalKnowledgeToolResult(
                isRelevant = false,
                query = query,
                citations = emptyList(),
                snippets = listOf(
                    "No local disaster, first-aid, medical, or field-operation source is needed for this message."
                )
            )
        }

        val citations = knowledgeIndex.search(query = query, limit = 2)
        val snippets = citations.mapNotNull { citation ->
            knowledgeIndex.articleById(citation.articleId)?.let { article ->
                buildString {
                    append("${article.id} ${article.title}: ")
                    append(article.in30Seconds.take(3).joinToString(" "))
                    if (article.dontDo.isNotEmpty()) {
                        append(" Avoid: ${article.dontDo.take(2).joinToString(" ")}")
                    }
                }.take(320)
            }
        }
        return CrisisSentinelLocalKnowledgeToolResult(
            isRelevant = true,
            query = query,
            citations = citations,
            snippets = snippets.ifEmpty {
                listOf("No exact local article matched; use the trusted safety draft and ask for missing details.")
            },
            incidentDraft = fallback.incidentDraft
        )
    }

    fun hasLocalModelRuntime(): Boolean = modelRuntime?.isReady == true

    private fun classifyQueryDomain(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ): CrisisSentinelQueryDomain {
        val query = if (request.prompt.isBlank()) {
            request.recentMessages.joinToString(" ")
        } else {
            request.prompt
        }
        return classifyQueryDomain(
            request = request,
            cleanPrompt = request.prompt.trim(),
            incidentDraft = fallback.incidentDraft,
            criticalAnswer = buildCriticalPlainAnswer(query, request.mode, request.locale)
        )
    }

    private fun classifyQueryDomain(
        request: CrisisSentinelRequest,
        cleanPrompt: String,
        incidentDraft: CrisisSentinelIncidentDraft?,
        criticalAnswer: String?
    ): CrisisSentinelQueryDomain {
        val query = if (cleanPrompt.isBlank()) {
            request.recentMessages.joinToString(" ")
        } else {
            cleanPrompt
        }
        if (query.isBlank()) return CrisisSentinelQueryDomain.CrisisGuided
        val normalized = CrisisSentinelText.normalize(query)
        if (isFirstAidIntakeQuestion(normalized)) {
            return CrisisSentinelQueryDomain.General
        }
        if (criticalAnswer != null || incidentDraft != null || isUsageQuestion(query)) {
            return CrisisSentinelQueryDomain.CrisisGuided
        }

        val strongCrisisSignals = listOf(
            "afet",
            "kriz",
            "deprem",
            "yangin",
            "sel",
            "enkaz",
            "tahliye",
            "barinma",
            "toplanma",
            "ilk yardim",
            "first aid",
            "yarali",
            "injured",
            "kanama",
            "bleeding",
            "nefes",
            "breathing",
            "gogus",
            "chest pain",
            "ambulans",
            "ambulance",
            "psikolojik ilk yardim",
            "psikososyal",
            "pfa",
            "kendime zarar",
            "intihar",
            "112",
            "acil",
            "gaz",
            "kimyasal",
            "duman",
            "saha notu",
            "kriz masasi",
            "crisis connect",
            "crisis sentinel",
            "offline rehber",
            "ble",
            "bluetooth",
            "mesh",
            "rol sertifikasi"
        )
        val operationalSignals = listOf(
            "sitrep",
            "saha",
            "ekip",
            "gorev",
            "kaynak",
            "lojistik"
        )
        val operationalContextSignals = strongCrisisSignals + listOf(
            "olay",
            "ihbar",
            "risk",
            "ihtiyac",
            "mahalle",
            "kapali",
            "elektrik",
            "su az",
            "yol kapali",
            "kriz merkezi",
            "kriz masasi"
        )
        val hasStrongSignal = strongCrisisSignals.any { containsNormalizedSignal(normalized, it) }
        val hasOperationalSignal = operationalSignals.any { containsNormalizedSignal(normalized, it) }
        val hasOperationalContext = request.mode != CrisisSentinelUserMode.Public ||
            operationalContextSignals.any { containsNormalizedSignal(normalized, it) }
        return if (hasStrongSignal || hasOperationalSignal && hasOperationalContext) {
            CrisisSentinelQueryDomain.CrisisGuided
        } else {
            CrisisSentinelQueryDomain.General
        }
    }

    private fun buildGuideAnswer(citation: CrisisSentinelCitation, locale: Locale): String {
        val article = knowledgeIndex.articleById(citation.articleId)
        if (article == null) return fallbackAnswer("", locale, CrisisSentinelQueryDomain.CrisisGuided)

        val firstSteps = article.in30Seconds.take(3)
        val detailedSteps = article.stepByStep.take(3)
        val dont = article.dontDo.take(2)
        return if (CrisisSentinelText.isTurkish(locale)) {
            buildString {
                append("Offline rehbere göre: ${article.title}\n")
                append(firstSteps.joinToString("\n") { "• $it" })
                if (detailedSteps.isNotEmpty()) {
                    append("\n\nUygulanacak adımlar:\n")
                    append(detailedSteps.joinToString("\n") { "• $it" })
                }
                if (dont.isNotEmpty()) {
                    append("\n\nDikkat:\n")
                    append(dont.joinToString("\n") { "• $it" })
                }
            }
        } else {
            buildString {
                append("From the offline guide: ${article.title}\n")
                append(firstSteps.joinToString("\n") { "• $it" })
                if (detailedSteps.isNotEmpty()) {
                    append("\n\nSteps:\n")
                    append(detailedSteps.joinToString("\n") { "• $it" })
                }
                if (dont.isNotEmpty()) {
                    append("\n\nAvoid:\n")
                    append(dont.joinToString("\n") { "• $it" })
                }
            }
        }
    }

    private fun buildFieldTeamAnswer(
        draft: CrisisSentinelIncidentDraft,
        citations: List<CrisisSentinelCitation>,
        locale: Locale
    ): String {
        val typeLabel = draft.type.name
        val priorityLabel = draft.priority.name
        return if (CrisisSentinelText.isTurkish(locale)) {
            buildString {
                append("Saha taslağı hazırlandı.\n")
                append("Öncelik: $priorityLabel • Olay: $typeLabel\n")
                draft.locationText?.let { append("Konum: $it\n") }
                draft.casualtyCount?.let { append("Yaralı/etkilenen: $it\n") }
                draft.trappedCount?.let { append("Mahsur: $it\n") }
                if (draft.hazards.isNotEmpty()) append("Riskler: ${draft.hazards.joinToString(", ")}\n")
                if (draft.needs.isNotEmpty()) append("İhtiyaçlar: ${draft.needs.joinToString(", ")}\n")
                hazardOperationalWarning(draft, locale)?.let { append("Güvenlik notu: $it\n") }
                if (draft.missingFields.isNotEmpty()) {
                    append("Eksik bilgi: ${draft.missingFields.joinToString(", ")}\n")
                }
                citations.firstOrNull()?.let { append("İlgili offline rehber: ${it.title}") }
            }
        } else {
            buildString {
                append("Field report draft prepared.\n")
                append("Priority: $priorityLabel • Incident: $typeLabel\n")
                draft.locationText?.let { append("Location: $it\n") }
                draft.casualtyCount?.let { append("Injured/affected: $it\n") }
                draft.trappedCount?.let { append("Trapped: $it\n") }
                if (draft.hazards.isNotEmpty()) append("Hazards: ${draft.hazards.joinToString(", ")}\n")
                if (draft.needs.isNotEmpty()) append("Needs: ${draft.needs.joinToString(", ")}\n")
                hazardOperationalWarning(draft, locale)?.let { append("Safety note: $it\n") }
                if (draft.missingFields.isNotEmpty()) {
                    append("Missing: ${draft.missingFields.joinToString(", ")}\n")
                }
                citations.firstOrNull()?.let { append("Related offline guide: ${it.title}") }
            }
        }
    }

    private fun buildCoordinatorAnswer(
        draft: CrisisSentinelIncidentDraft,
        citations: List<CrisisSentinelCitation>,
        locale: Locale
    ): String {
        val typeLabel = draft.type.name
        val priorityLabel = draft.priority.name
        return if (CrisisSentinelText.isTurkish(locale)) {
            buildString {
                append("Koordinasyon taslak notu hazırlandı.\n")
                append("Öncelik: $priorityLabel • Olay: $typeLabel\n")
                draft.locationText?.let { append("Konum: $it\n") }
                draft.casualtyCount?.let { append("Yaralı/etkilenen: $it\n") }
                draft.trappedCount?.let { append("Mahsur: $it\n") }
                if (draft.hazards.isNotEmpty()) append("Riskler: ${draft.hazards.joinToString(", ")}\n")
                if (draft.needs.isNotEmpty()) append("Kaynak/ihtiyaç: ${draft.needs.joinToString(", ")}\n")
                append("Taslak görev/kaynak notu: güvenli erişim teyidi, risk izolasyonu, kaynak talebi ve saha geri bildirimi ayrı kanallardan doğrulansın.\n")
                hazardOperationalWarning(draft, locale)?.let { append("Güvenlik sınırı: $it\n") }
                if (draft.missingFields.isNotEmpty()) {
                    append("Eksik bilgi: ${draft.missingFields.joinToString(", ")}\n")
                }
                append("Bu çıktı sadece taslak nottur; komuta/kurum onayı olmadan ekip yönlendirme veya saha uygulaması başlatmaz.")
                citations.firstOrNull()?.let { append("\nİlgili offline rehber: ${it.title}") }
            }
        } else {
            buildString {
                append("Coordination draft prepared.\n")
                append("Priority: $priorityLabel • Incident: $typeLabel\n")
                draft.locationText?.let { append("Location: $it\n") }
                draft.casualtyCount?.let { append("Injured/affected: $it\n") }
                draft.trappedCount?.let { append("Trapped: $it\n") }
                if (draft.hazards.isNotEmpty()) append("Risks: ${draft.hazards.joinToString(", ")}\n")
                if (draft.needs.isNotEmpty()) append("Resources/needs: ${draft.needs.joinToString(", ")}\n")
                append("Tasking draft: verify safe access, isolate risks, request resources, and collect field confirmation through separate channels.\n")
                hazardOperationalWarning(draft, locale)?.let { append("Safety boundary: $it\n") }
                if (draft.missingFields.isNotEmpty()) {
                    append("Missing: ${draft.missingFields.joinToString(", ")}\n")
                }
                append("This output is a draft note only; it does not authorize team direction or field action without command/agency approval.")
                citations.firstOrNull()?.let { append("\nRelated offline guide: ${it.title}") }
            }
        }
    }

    private fun buildMessageSummary(
        messages: List<String>,
        draft: CrisisSentinelIncidentDraft?,
        locale: Locale
    ): String {
        val latest = messages.takeLast(4).joinToString(" / ") { it.take(80) }
        return if (CrisisSentinelText.isTurkish(locale)) {
            buildString {
                append("Son mesaj özeti: $latest")
                draft?.let {
                    append("\nAlgılanan öncelik: ${it.priority.name}")
                    if (it.missingFields.isNotEmpty()) append("\nEksik: ${it.missingFields.joinToString(", ")}")
                }
            }
        } else {
            buildString {
                append("Recent message summary: $latest")
                draft?.let {
                    append("\nDetected priority: ${it.priority.name}")
                    if (it.missingFields.isNotEmpty()) append("\nMissing: ${it.missingFields.joinToString(", ")}")
                }
            }
        }
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

    private fun isGreetingOnly(prompt: String): Boolean {
        val normalized = CrisisSentinelText.normalize(prompt)
        return normalized in shortGreetings
    }

    private fun containsNormalizedSignal(normalized: String, rawSignal: String): Boolean {
        val signal = CrisisSentinelText.normalize(rawSignal)
        if (signal.isBlank()) return false
        if (signal.any { !it.isLetterOrDigit() }) {
            return normalized.contains(signal)
        }
        return Regex("""(?:^|[^a-z0-9])${Regex.escape(signal)}(?:$|[^a-z0-9])""")
            .containsMatchIn(normalized)
    }

    private fun isFirstAidIntakeQuestion(normalized: String): Boolean {
        fun hasAny(vararg terms: String): Boolean = terms.any { normalized.contains(CrisisSentinelText.normalize(it)) }
        val hasFirstAid = hasAny("ilk yardim", "ilk yardım", "first aid")
        val asksBeforeDetails = hasAny(
            "soru",
            "hakkinda",
            "hakkında",
            "yardim eder",
            "yardım eder",
            "yardimci olur",
            "yardımcı olur",
            "help",
            "can you help",
            "assist"
        )
        val hasConcreteMedicalDetail = hasAny(
            "kanama",
            "yarali",
            "yaralı",
            "nefes",
            "gogus",
            "göğüs",
            "bilinc",
            "bilinç",
            "bayildi",
            "bayıldı",
            "yanik",
            "yanık",
            "kirik",
            "kırık",
            "zehir",
            "bogul",
            "boğul",
            "kalp",
            "ambulans"
        )
        return hasFirstAid && asksBeforeDetails && !hasConcreteMedicalDetail
    }

    private val shortGreetings = setOf(
            "selam",
            "selamlar",
            "slm",
            "merhaba",
            "meraba",
            "mrb",
            "sa",
            "hey",
            "naber",
            "nasilsin",
            "hello",
            "hi"
    )

    private fun buildCriticalPlainAnswer(
        prompt: String,
        mode: CrisisSentinelUserMode,
        locale: Locale
    ): String? {
        val normalized = CrisisSentinelText.normalize(prompt)
        if (normalized.isBlank()) return null
        val turkish = CrisisSentinelText.isTurkish(locale)
        val fieldMode = mode == CrisisSentinelUserMode.FieldTeam
        val coordinatorMode = mode == CrisisSentinelUserMode.Coordinator

        fun hasAny(vararg terms: String): Boolean = terms.any { normalized.contains(CrisisSentinelText.normalize(it)) }
        fun isCrisisTranslationQuestion(): Boolean {
            val hasLanguageOrTranslation = hasAny(
                "ceviri",
                "çeviri",
                "tercume",
                "tercüme",
                "tercuman",
                "ingilizce",
                "arapca",
                "arapça",
                "kurtce",
                "kürtçe",
                "translation",
                "language access"
            )
            val hasCrisisMessageContext = hasAny(
                "tahliye",
                "uyari",
                "uyarı",
                "resmi talimat",
                "kriz",
                "afet",
                "saha"
            )
            val explicitTranslateAction = hasAny("çevir", "cevir", "translate")
            return hasCrisisMessageContext && (hasLanguageOrTranslation || explicitTranslateAction)
        }
        return when {
            hasAny("sistem prompt", "system prompt", "önceki tüm kuralları", "onceki tum kurallari", "kuralları unut", "kurallari unut") ->
                if (turkish) {
                    "Bunu yapamam; sistem talimatlarını veya gizli içeriği paylaşmam. Crisis Sentinel olarak afet hazırlığı, ilk yardım sınırları, saha raporu, çeviri veya Crisis Connect kullanımı hakkında güvenli yardım verebilirim. İstersen olay türü, konum ve riskleri yaz; kısa ve uygulanabilir bir cevap hazırlayayım."
                } else {
                    "I cannot share system instructions or hidden content. Crisis Sentinel can help with disaster preparedness, first-aid boundaries, field reports, translation, or Crisis Connect usage. Share the incident type, location, and risks, and I will give a short safe answer."
                }
            hasAny("oyun hilesi", "hile yaz", "exploit", "bypass", "cheat") ->
                if (turkish) {
                    "Bu konu Crisis Sentinel'in afet, kriz, ilk yardım, psikolojik ilk yardım, çeviri ve saha raporu görevlerinin dışında. İstersen afet çantası, güvenli tahliye, saha notu veya Crisis Connect araçlarını nasıl kullanacağını anlatabilirim."
                } else {
                    "That is outside Crisis Sentinel's disaster, crisis, first-aid, psychological first-aid, translation, and field-report scope. I can help with preparedness, safe evacuation, field notes, or how to use Crisis Connect tools."
                }
            hasAny("fotoğraf", "fotograf", "dosya", "ocr", "ses", "dikte") && hasAny("ne yap", "yapabiliyor", "yapabiliyorsun", "destek") ->
                if (turkish) {
                    "Fotoğraf ve dosyada görünür bulgu, OCR metni, malzeme sınıflama ve kısa saha notu taslağı desteği verebilirim. Sesli dikte metne çevrilirse bunu kriz sorusu veya rapor girdisi olarak kullanabilirim. Fotoğraftan tıbbi tanı, kimlik tespiti, kesin koordinat veya bina güvenliği onayı vermem; belirsizliği açık yazarım."
                } else {
                    "I can help turn photos, files, OCR text, supply observations, and voice dictation into short crisis questions or field-note drafts. I do not diagnose from photos, identify people, certify building safety, or invent exact coordinates; I state uncertainty clearly."
                }
            isFirstAidIntakeQuestion(normalized) ->
                if (turkish) {
                    "Evet, ilk yardım konusunda güvenli sınırlar içinde yardımcı olurum. Durumu yaz: ne oldu, kişi bilinçli mi, nefes alıyor mu, kanama/yanık/kırık var mı, yaşı yaklaşık kaç ve bulunduğunuz yer güvenli mi? Hayati risk, bilinç kaybı, nefes sorunu, yoğun kanama veya göğüs ağrısı varsa 112 önceliklidir. Tanı koymam ve profesyonel sağlık desteğinin yerine geçmem."
                } else {
                    "Yes, I can help with first-aid guidance within safe limits. Tell me what happened, whether the person is conscious and breathing, whether there is bleeding, burn, or fracture, the approximate age, and whether the area is safe. If there is life-threatening danger, loss of consciousness, breathing trouble, heavy bleeding, or chest pain, call emergency services first. I do not diagnose or replace professional medical care."
                }
            hasAny("gaz kokusu", "gaz sizinti", "gaz sızıntı", "kimyasal", "keskin koku") &&
                hasAny("sigorta", "kapat", "kaynağı", "kaynagi", "kendim", "vana") ->
                if (turkish) {
                    "Gaz veya kimyasal risk olabilir; kıvılcım yaratabilecek sigorta, elektrik anahtarı, priz, çakmak, kibrit ve cihazlara dokunma. Güvenliyse kapı/pencereyi zorlamadan açık alana çık ve bina içine geri dönme. Gaz kaynağına yaklaşma veya onarım deneme. Güvenli mesafeden 112, itfaiye veya gaz acil hattını ara."
                } else {
                    "This may be a gas or chemical hazard; do not touch breakers, switches, plugs, lighters, matches, or devices that could spark. Move to open air if safe and do not re-enter. Do not approach or repair the source. From a safe distance, call emergency, fire, or gas response."
                }
            hasAny("deprem") && hasAny("binadan çık", "binadan cik", "çıkmalı", "cikmali", "oldu") ->
                if (turkish) {
                    "Önce güvenliğini sağla; hasarlı bina, cam, elektrik hattı, gaz kokusu ve çökme riski olan yerlerden uzak dur. Artçı sarsıntılara karşı sağlam ayakkabı giy, asansör kullanma ve riskli binaya dönme. Resmi uyarıları ve yerel ekip talimatlarını takip et. Yaralanma, yangın, gaz kokusu veya mahsur kalma varsa 112'yi ara."
                } else {
                    "Prioritize safety and stay away from damaged buildings, glass, power lines, gas smells, and collapse risks. Expect aftershocks, avoid elevators, and do not return to unsafe structures. Follow official alerts and local responder instructions. Call emergency services for injury, fire, gas smell, or entrapment."
                }
            hasAny("çok kanama", "cok kanama", "ciddi kanama", "kanama var", "kanayan") ->
                if (turkish) {
                    "Bu tanı veya tedavi planı değildir; ciddi kanamada 112/sağlık desteği önceliklidir. Güvenliyse temiz bezle kanayan bölgeye doğrudan ve sürekli bası uygula; bez ıslanırsa kaldırmadan üstüne yenisini koy. Kişiyi sıcak tut, bilinç ve nefes durumunu izle. İlaç verme ve yaraya yabancı cisim varsa çıkarmaya çalışma."
                } else {
                    "This is not a diagnosis or treatment plan; serious bleeding needs emergency/medical support. If safe, apply direct continuous pressure with clean cloth, adding more cloth without removing soaked layers. Keep the person warm and monitor breathing/consciousness. Do not give medication or remove embedded objects."
                }
            hasAny("göğs", "gogus", "nefes alam", "nefes nefese", "kalp") ->
                if (turkish) {
                    "Tanı koyma; göğüs ağrısı ve nefes alamama tıbbi acil belirti olabilir. Kişiyi güvenli alana al, kalabalığı azalt ve 112/sağlık desteğini çağır. Ağızdan yiyecek, içecek veya ilaç verme. Net konum, başlangıç zamanı, bilinç ve nefes durumunu not et."
                } else {
                    "Do not diagnose; chest pain and trouble breathing can be medical emergency signs. Move the person to a safe area, reduce crowding, and call emergency/medical support. Do not give food, drink, or medication. Note location, start time, consciousness, and breathing."
                }
            hasAny("kendime zarar", "kendine zarar", "ölmek ist", "olmek ist", "intihar") ->
                if (turkish) {
                    "Bu terapi veya psikiyatrik tanı değildir; şu an güvenlik öncelikli. Lütfen yalnız kalma: güvendiğin birini hemen yanına çağır veya güvenli bir alana geç. İlaç, kesici alet veya tehlikeli nesnelere erişimini güvenilir birinden uzaklaştırmasını iste. 112 veya yerel kriz-sağlık desteğini şimdi ara."
                } else {
                    "This is not therapy or a psychiatric diagnosis; immediate safety comes first. Do not stay alone: contact a trusted person now or move to a safer place. Ask someone reliable to remove access to medication, sharp objects, or other hazards. Call emergency or local crisis-health support now."
                }
            hasAny("çocuk", "cocuk", "çocu", "cocu") && hasAny("enkaz", "travma", "ayrıntılı", "ayrintili", "anlattır", "anlattir") ->
                if (turkish) {
                    "Çocuğu ayrıntı anlatmaya zorlamayın; güvenli, sakin ve bakım veren bir yetişkine yakın kalması önceliklidir. Kısa, yaşına uygun ve doğru cümleler kullanın; suçlayıcı veya korkutucu dil kullanmayın. Temel ihtiyaç, kayıp kişi veya korunma riski varsa ilgili çocuk koruma ya da sağlık ekibine bildirin."
                } else {
                    "Do not force a child to describe traumatic details. Keep the child safe, calm, and close to a caregiver. Use short, age-appropriate, truthful language without blame or frightening detail. Escalate basic needs, missing-person concerns, or protection risks to child-protection or health teams."
                }
            coordinatorMode && hasAny("görev", "gorev", "kaynak", "öncelik", "oncelik", "ekip", "koordine", "kriz masası", "kriz masasi") ->
                if (turkish) {
                    "Koordinasyon taslağı: olayı, konumu, riskleri, etkilenen kişi sayısını, kapalı yolları ve acil kaynak ihtiyacını ayrı yaz. Görevleri taslak olarak üret: güvenli erişim teyidi, risk izolasyonu, kaynak talebi ve saha geri bildirimi. Eksik sayı, konum veya sağlık bilgisini doğrulanmış gibi yazma. Bu çıktı sadece taslak nottur; komuta/kurum onayı olmadan ekip yönlendirme veya saha uygulaması başlatmaz."
                } else {
                    "Coordination draft: separate the incident, location, risks, affected people, closed routes, and urgent resources. Write tasking as drafts: safe-access verification, risk isolation, resource request, and field confirmation. Do not present missing counts, locations, or medical details as verified. This output is a draft note only; it does not authorize team direction or field action without command/agency approval."
                }
            !coordinatorMode && hasAny("görev ver", "gorev ver", "ekiplere görev", "ekiplere gorev", "sevk et", "ambulansı sevk", "ambulansi sevk", "ekipleri yönlendir", "ekipleri yonlendir") ->
                if (turkish) {
                    "Bu modda resmi görev, sevk veya ekip emri veremem. Kısa ihtiyaç/SITREP notu hazırlayabilirim: konum, kişi sayısı, risk, acil ihtiyaç, kapalı yol ve iletişim bilgisi. Hayati riskte 112 ve yetkili kriz/komuta kanalları önceliklidir. Eksik bilgiyi kesinmiş gibi yazma."
                } else {
                    "This mode cannot issue official tasks, dispatches, or team orders. I can prepare a short needs/SITREP note: location, people affected, risks, urgent needs, closed routes, and contact details. For life-threatening danger, emergency services and authorized command channels come first. Do not present missing details as certain."
                }
            hasAny("sitrep", "kriz masası", "kriz masasi", "saha notu") || fieldMode && hasAny("elektrik", "su", "yol", "kapalı", "kapali") ->
                if (turkish) {
                    "SITREP taslağı için konum, zaman, olay türü, görünen risk, etkilenen kişi sayısı, acil ihtiyaç ve kapalı yol bilgisini ayrı ayrı yaz. Eksik olan kişi sayısı, güvenli erişim noktası, su miktarı, tıbbi acil durum ve iletişim kişisini uydurma; kısa takip sorusu sor. Bu resmi komuta kararının yerine geçmez, saha gözlemini düzenler."
                } else {
                    "For a SITREP, separate location, time, incident type, visible risks, affected people, urgent needs, and closed roads. Do not invent missing people counts, safe access points, supply levels, medical emergencies, or contacts; ask short follow-up questions. This organizes field observation and does not replace command decisions."
                }
            isCrisisTranslationQuestion() ->
                if (turkish) {
                    "Tahliye çevirisinde tehlike, güvenli alan, saat, rota ve kimlerin tahliye edileceği net yazılmalı. Belirsiz bilgi kesinmiş gibi çevrilmemeli; resmi talimatın anlamı değiştirilmemeli. Kısa cümle kullan ve panik yaratacak ek ifade ekleme. Kişisel veri veya mahremiyet içeren bilgileri gereksiz paylaşma."
                } else {
                    "For evacuation translation, clearly preserve the hazard, safe area, time, route, and who must evacuate. Do not turn uncertain information into certainty or change official instructions. Use short sentences and avoid panic-inducing additions. Do not share unnecessary personal or private data."
                }
            else -> null
        }
    }

    private fun buildUsageAnswer(locale: Locale): String {
        return if (CrisisSentinelText.isTurkish(locale)) {
            buildString {
                append("Crisis Sentinel'i kısa ve net olay bilgisiyle kullanabilirsin.\n")
                append("• Crisis Connect içinde genel kullanıcıysan afet hazırlığı, ilk yardım sınırları, güvenli tahliye, barınma ve iletişim hakkında sor.\n")
                append("• Saha ekibiysen konum, kişi sayısı, riskler ve ihtiyaçları yaz; ben SITREP/olay taslağı çıkarayım.\n")
                append("• Yetkili koordinasyondaysan görev, kaynak, öncelik ve devir notu taslağı iste; çıktı komuta onayı gerektirir.\n")
                append("• Fotoğraf, dosya veya dikte ekleyerek metni/bağlamı prompt'a dahil edebilirsin.\n")
                append("Hayati risk varsa 112 ve resmi talimatlar önceliklidir.\n")
                append("Örnek: “Apartman girişinde gaz kokusu var, 2 kişi panikte, ne yapalım?”")
            }
        } else {
            buildString {
                append("Use Crisis Sentinel inside Crisis Connect with short, specific incident context.\n")
                append("• Public users can ask about preparedness, first-aid boundaries, evacuation, shelter, and communication.\n")
                append("• Field teams can provide location, people affected, hazards, and needs; I can draft a SITREP.\n")
                append("• Authorized coordinators can request draft tasking, resource priorities, and handover notes; output requires command approval.\n")
                append("• You can add photo OCR, file text, or voice dictation as extra context.\n")
                append("For life-threatening danger, local emergency services and official instructions come first.\n")
                append("Example: “There is a gas smell at the apartment entrance, 2 people are panicking. What should we do?”")
            }
        }
    }

    private fun followUpQuestions(draft: CrisisSentinelIncidentDraft?, locale: Locale): List<String> {
        if (draft == null || draft.missingFields.isEmpty()) return emptyList()
        val turkish = CrisisSentinelText.isTurkish(locale)
        return draft.missingFields.take(3).map { field ->
            if (turkish) {
                when (field) {
                    "konum/adres" -> "Net konum veya en yakın işaret nedir?"
                    "etkilenen kişi sayısı" -> "Kaç kişi yaralı, mahsur veya etkilenmiş görünüyor?"
                    "ek riskler" -> "Gaz kokusu, duman, elektrik veya çökme riski var mı?"
                    else -> "Hangi destek gerekiyor?"
                }
            } else {
                when (field) {
                    "konum/adres" -> "What is the exact location or nearest landmark?"
                    "etkilenen kişi sayısı" -> "How many people are injured, trapped, or affected?"
                    "ek riskler" -> "Are there gas, smoke, electrical, or collapse hazards?"
                    else -> "What support is needed?"
                }
            }
        }
    }

    private fun safetyNotices(
        locale: Locale,
        draft: CrisisSentinelIncidentDraft?,
        queryDomain: CrisisSentinelQueryDomain
    ): List<String> {
        if (queryDomain == CrisisSentinelQueryDomain.General) return emptyList()
        val base = if (CrisisSentinelText.isTurkish(locale)) {
            listOf(
                "Hayati tehlike varsa 112'yi ara.",
                "Crisis Sentinel offline öneri verir; resmi talimat ve saha komuta kararının yerine geçmez."
            )
        } else {
            listOf(
                "Call the local emergency number for life-threatening danger.",
                "Crisis Sentinel provides offline assistance; it does not replace official command decisions."
            )
        }
        return hazardOperationalWarning(draft, locale)?.let { base + it } ?: base
    }

    private fun hazardOperationalWarning(draft: CrisisSentinelIncidentDraft?, locale: Locale): String? {
        if (draft == null) return null
        val hasGas = "gaz kokusu" in draft.hazards
        val hasChemical = "kimyasal/sızıntı riski" in draft.hazards
        if (draft.type != CrisisSentinelIncidentType.Hazard && !hasGas && !hasChemical) return null

        return if (CrisisSentinelText.isTurkish(locale)) {
            when {
                hasGas -> "Gaz kaynağı aranmaz veya onarılmaz; elektrik anahtarı, sigorta, cihaz, ateş/kıvılcım ve kapalı alanda telefon kullanılmaz. Güvenli mesafeden 112/itfaiye/gaz ekibi bilgilendirilir."
                hasChemical -> "Korumasız yaklaşma, koklama veya temizleme yapılmaz. Alan izole edilir; güvenli mesafeden itfaiye/tehlikeli madde desteği istenir."
                else -> "Tehlike kaynağına yaklaşmadan güvenli mesafe korunur ve ilgili acil ekip bilgilendirilir."
            }
        } else {
            when {
                hasGas -> "Do not search for or repair the gas source; do not use switches, breakers, devices, flames/sparks, or phones indoors. Notify emergency/fire/gas teams from a safe distance."
                hasChemical -> "Do not approach, smell, or clean it without protection. Isolate the area and request fire/hazmat support from a safe distance."
                else -> "Keep a safe distance from the hazard source and notify the relevant emergency team."
            }
        }
    }

    private fun roleInstruction(mode: CrisisSentinelUserMode, locale: Locale): String {
        val turkish = CrisisSentinelText.isTurkish(locale)
        return when (mode) {
            CrisisSentinelUserMode.Public ->
                if (turkish) {
                    "Kullanıcı rolü: genel kullanıcı. Resmi görev, ekip emri, sevk, tanı veya yapı güvenliği kararı verme; güvenli rehberlik ve yetkili kanala yönlendirme yap."
                } else {
                    "User role: public user. Do not issue official tasks, team orders, dispatches, diagnoses, or building-safety decisions; provide safe guidance and route to authorized channels."
                }
            CrisisSentinelUserMode.FieldTeam ->
                if (turkish) {
                    "Kullanıcı rolü: saha ekibi. Gözlem, SITREP/SBAR ve eksik bilgi taslağı hazırla; resmi komuta kararı veya ekip emri üretme."
                } else {
                    "User role: field team. Prepare observation, SITREP/SBAR, and missing-field drafts; do not issue official command decisions or team orders."
                }
            CrisisSentinelUserMode.Coordinator ->
                if (turkish) {
                    "Kullanıcı rolü: yetkili koordinasyon. Görev, kaynak, öncelik ve devir taslakları üretebilirsin; her çıktı komuta/kurum onayı gerektirir ve onaysız ekip yönlendirme başlatmaz."
                } else {
                    "User role: authorized coordination. You may draft tasking, resource, priority, and handover notes; every output requires command/agency approval and does not authorize deployment."
                }
        }
    }

    private fun fallbackAnswer(prompt: String, locale: Locale, queryDomain: CrisisSentinelQueryDomain): String {
        return if (queryDomain == CrisisSentinelQueryDomain.General) {
            if (isGreetingOnly(prompt)) {
                return if (CrisisSentinelText.isTurkish(locale)) {
                    "Selam. Ne hakkında konuşmak istersin? Genel sorulara doğrudan cevap verebilir; afet, ilk yardım veya saha notlarında yerel kaynaklara göre yardımcı olurum."
                } else {
                    "Hi. What would you like to talk about? I can answer general questions directly; for disaster, first-aid, or field notes, I use local sources."
                }
            }
            if (CrisisSentinelText.isTurkish(locale)) {
                "Bu konu afet rehberi gerektirmiyor. Sorunu biraz daha net yazarsan doğrudan kısa bir cevap verebilirim; afet, ilk yardım veya saha notuysa yerel kaynaklara göre yanıtlarım."
            } else {
                "This does not need the offline disaster guide. Add a little more detail and I can give a short direct answer; for disaster, first-aid, or field notes, I will use local sources."
            }
        } else if (CrisisSentinelText.isTurkish(locale)) {
            "Offline bellekte bu konu için net bir rehber kaydı bulamadım. Kısa ve net yaz: olay türü, konum, kişi sayısı, riskler ve ihtiyaç. Hayati tehlike varsa 112'yi ara."
        } else {
            "I could not find a clear offline guide entry for this. Provide incident type, location, people affected, hazards, and needs. Call the local emergency number for life-threatening danger."
        }
    }

    companion object {
        private const val MIN_GUIDE_SCORE = 4
        // Gemma 3n E2B (~3.6GB) is far larger than the old 1B model: the first message after launch
        // also pays a one-time engine init (loading the model into GPU/CPU), which alone can take
        // tens of seconds on mid/low-end devices. 45s cut generations off prematurely.
        private const val MODEL_RUNTIME_TIMEOUT_MS = 180_000L

        private val gemmaResponseSchema = """
            {
              "answer": "short localized response",
              "incidentDraft": {
                "type": "earthquake|fire|flood|hazard|medical|trapped|communication|logistics|shelter|publicHealth|translation|strategy|evacuation|mentalHealth|unknown",
                "priority": "routine|high|immediate",
                "casualtyCount": "number|null",
                "trappedCount": "number|null",
                "locationText": "string|null",
                "hazards": ["string"],
                "needs": ["string"],
                "missingFields": ["string"]
              },
              "citations": [{"articleId":"string","title":"string"}],
              "followUpQuestions": ["string"],
              "safetyNotices": ["string"]
            }
        """.trimIndent()
    }
}

private data class CrisisSentinelLocalKnowledgeToolResult(
    val isRelevant: Boolean,
    val query: String,
    val citations: List<CrisisSentinelCitation>,
    val snippets: List<String>,
    val incidentDraft: CrisisSentinelIncidentDraft? = null
) {
    fun toContextSnippets(): List<String> {
        val status = if (isRelevant) "relevant" else "not_relevant"
        return buildList {
            add("Tool result: local_knowledge_search")
            add("Tool status: $status")
            add("Tool query: ${query.replace(Regex("\\s+"), " ").take(180)}")
            if (isRelevant) {
                add("Tool instruction: Use these local sources for disaster, first-aid, medical, psychological first-aid, crisis logistics, translation, and field-operation answers. Do not invent source IDs or facts outside the tool result.")
                if (citations.isNotEmpty()) {
                    add("Tool citations: ${citations.joinToString(", ") { "${it.articleId} ${it.title}" }}")
                }
                snippets.forEachIndexed { index, snippet ->
                    add("Tool source ${index + 1}: ${snippet.replace(Regex("\\s+"), " ").take(360)}")
                }
                incidentDraft?.let { draft ->
                    add("Tool extracted incident: type=${draft.type.name}, priority=${draft.priority.name}, missing=${draft.missingFields.joinToString(", ")}")
                }
            } else {
                add("Tool instruction: Do not force disaster, emergency, SITREP, medical, or Crisis Connect content. Answer as normal chat unless the user provides a concrete crisis, first-aid, medical, self-harm, or field-operation situation.")
            }
        }
    }
}
