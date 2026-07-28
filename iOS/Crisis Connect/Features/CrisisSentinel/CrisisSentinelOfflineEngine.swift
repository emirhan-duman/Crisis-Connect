//
//  CrisisSentinelOfflineEngine.swift
//  Crisis Connect
//

import Foundation

final class CrisisSentinelOfflineEngine {
    private static let minimumGuideScore = 4

    private let knowledgeIndex: CrisisSentinelKnowledgeIndex
    private let modelRuntime: CrisisSentinelModelRuntime?
    // Failure diagnostics (Android parity): without these hooks a rejected or crashed generation
    // silently degrades to the rules fallback and nobody can tell WHY the model answer vanished.
    private let onModelFailure: ((Error) -> Void)?
    private let onModelRejected: ((String) -> Void)?

    init(
        knowledgeIndex: CrisisSentinelKnowledgeIndex = .fromSurvivalGuide(),
        modelRuntime: CrisisSentinelModelRuntime? = nil,
        onModelFailure: ((Error) -> Void)? = nil,
        onModelRejected: ((String) -> Void)? = nil
    ) {
        self.knowledgeIndex = knowledgeIndex
        self.modelRuntime = modelRuntime
        self.onModelFailure = onModelFailure
        self.onModelRejected = onModelRejected
    }

    func respond(to request: CrisisSentinelRequest) -> CrisisSentinelResponse {
        let cleanPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let incidentInput = ([cleanPrompt] + request.recentMessages).joined(separator: " ")
        let incidentDraft = CrisisSentinelIncidentExtractor.extract(from: incidentInput)
        let searchQuery = cleanPrompt.isEmpty ? request.recentMessages.joined(separator: " ") : cleanPrompt
        let criticalAnswer = buildCriticalPlainAnswer(prompt: cleanPrompt, mode: request.mode, locale: request.locale)
        let queryDomain = classifyQueryDomain(
            request: request,
            incidentDraft: incidentDraft,
            criticalAnswer: criticalAnswer
        )
        let citations = queryDomain == .crisisGuided ? knowledgeIndex.search(query: searchQuery, limit: 3) : []

        let answer: String
        if request.mode == .coordinator, let incidentDraft {
            answer = buildCoordinatorAnswer(draft: incidentDraft, citations: citations, locale: request.locale)
        } else if request.mode == .fieldTeam, let incidentDraft {
            answer = buildFieldTeamAnswer(draft: incidentDraft, citations: citations, locale: request.locale)
        } else if let criticalAnswer {
            answer = criticalAnswer
        } else if isUsageQuestion(cleanPrompt) {
            answer = buildUsageAnswer(locale: request.locale)
        } else if request.recentMessages.isEmpty == false, cleanPrompt.isEmpty {
            answer = buildMessageSummary(messages: request.recentMessages, draft: incidentDraft, locale: request.locale)
        } else if let firstCitation = citations.first, firstCitation.score >= Self.minimumGuideScore {
            answer = buildGuideAnswer(citation: firstCitation, locale: request.locale)
        } else {
            answer = fallbackAnswer(prompt: cleanPrompt, locale: request.locale, queryDomain: queryDomain)
        }

        let citationConfidence = citations.first.map { min(max(Float($0.score) / 20.0, 0.25), 0.86) } ?? 0.22
        let confidence = max(incidentDraft?.confidence ?? 0, citationConfidence)

        return CrisisSentinelResponse(
            answer: answer,
            mode: request.mode,
            source: .offlineRules,
            citations: citations,
            incidentDraft: incidentDraft,
            followUpQuestions: followUpQuestions(for: incidentDraft, locale: request.locale),
            safetyNotices: safetyNotices(locale: request.locale, draft: incidentDraft, queryDomain: queryDomain),
            confidence: confidence
        )
    }

    func respondWithModel(to request: CrisisSentinelRequest) async -> CrisisSentinelResponse {
        let fallback = respond(to: request)
        guard let modelRuntime, modelRuntime.isReady else { return fallback }

        let modelRequest = buildGemmaRequest(for: request, fallback: fallback)
        let modelResponse: CrisisSentinelModelResponse
        do {
            modelResponse = try await modelRuntime.generate(modelRequest)
        } catch {
            // Surface WHY generation failed (Android parity) — otherwise a crashed/cancelled
            // generation is indistinguishable from a validator rejection in the field.
            onModelFailure?(error)
            return fallback
        }
        let query = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? request.recentMessages.joined(separator: " ")
            : request.prompt
        let allowedCitations = modelRequest.queryDomain == .crisisGuided ? knowledgeIndex.search(query: query, limit: 4) : []
        let validated = CrisisSentinelModelOutputValidator.validate(
            request: request,
            modelResponse: modelResponse,
            allowedCitations: allowedCitations,
            fallback: fallback,
            queryDomain: modelRequest.queryDomain
        )
        if validated == nil {
            // The validator threw the answer away — report a short preview of what was rejected so
            // safety-filter regressions show up in logs instead of looking like a "dumb model".
            onModelRejected?(String(modelResponse.text.prefix(240)))
        }
        return validated ?? fallback
    }

    func buildGemmaRequest(for request: CrisisSentinelRequest) -> CrisisSentinelModelRequest {
        buildGemmaRequest(for: request, fallback: respond(to: request))
    }

    private func buildGemmaRequest(
        for request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> CrisisSentinelModelRequest {
        let queryDomain = classifyQueryDomain(request: request, fallback: fallback)
        // Tool prompt framing (Android parity): the retrieval is presented to the model as the
        // result of a local tool named `local_knowledge_search` instead of bare snippets. The small
        // model follows "use this tool result" grounding far more reliably than loose context, and
        // an explicit not_relevant status stops it from forcing disaster content into normal chat.
        let toolResult = runLocalKnowledgeTool(request: request, fallback: fallback, queryDomain: queryDomain)
        var snippets: [String] = toolResult.toContextSnippets()
        if toolResult.isRelevant {
            snippets.append(roleInstruction(mode: request.mode, locale: request.locale))
            snippets.append("Trusted local answer draft: \(fallback.answer.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).prefix(420))")
            if fallback.safetyNotices.isEmpty == false {
                snippets.append("Safety boundaries: \(fallback.safetyNotices.joined(separator: " ").replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).prefix(220))")
            }
            snippets.append(contentsOf: CrisisSentinelSafetyCoverage.contextSnippets(request: request, fallback: fallback))
            if let draft = fallback.incidentDraft {
                snippets.append(
                    "Detected incident: type=\(draft.type.rawValue), priority=\(draft.priority.rawValue), hazards=\(draft.hazards.joined(separator: ", ")), needs=\(draft.needs.joined(separator: ", "))"
                )
            }
        }
        var seenSnippets = Set<String>()
        let distinctSnippets = snippets.filter { seenSnippets.insert($0).inserted }
        // Small on-device models drift toward the instruction language, so for Turkish the whole
        // system prompt is written in Turkish instead of English + "always answer in Turkish".
        let systemInstruction: String
        if CrisisSentinelText.isTurkish(request.locale) {
            systemInstruction = queryDomain == .general
                ? "Yardımsever, çevrimdışı bir yapay zekâ sohbet asistanısın ve Türkçe cevap verirsin. local_knowledge_search sonucu: kullanıcı somut bir afet, ilk yardım, tıbbi, kendine zarar verme veya saha operasyonu durumu anlatmadıkça bu mesaj için not_relevant. Günlük sohbet, yazma, çeviri, kodlama, fikir üretme ve açıklamalarda kısa, doğal ve faydalı ol. Kullanıcı sormadıkça kendini, modeli veya uygulamayı anlatma. Güvenlik açısından kritik afet, tıbbi, kendine zarar verme veya saha operasyonu sorularında güvensiz kesinlikten kaçın ve gerektiğinde acil yardım çağrılmasını söyle."
                : "Sen Crisis Sentinel Edge'sin; yerel yapay zekâ asistanısın ve cevaplarını her zaman Türkçe verirsin. local_knowledge_search adlı yerel bir aracın var. Afet, kriz, ilk yardım, tıbbi, psikoloji, lojistik, çeviri ve saha ekibi soruları için local_knowledge_search sonucunu ve güvenilir yerel taslağı dayanak al. Güvenlik sınırlarını ve kullanıcı rolünü koru. Genel kullanıcılara yalnızca güvenli yönlendirme ver. Saha ekiplerine yalnızca gözlem/SITREP taslağı ver. Yetkili koordinatörlere görevlendirme/kaynak taslağı verebilirsin ama asla nihai resmi emir verme; insan komuta onayı gerekir. Koordinat, teşhis, tedavi iddiası, kaynak kimliği, sevk veya doğrulanmış sayı uydurma. Gaz/kimyasal tehlikede: tahliye, anahtar/kıvılcım/alevden uzak durma, mesafe ve acil servis/gaz ekiplerini arama önceliklidir. Kısa düz metin döndür, JSON yazma."
        } else {
            let responseLanguage = CrisisSentinelText.responseLanguageName(request.locale)
            systemInstruction = queryDomain == .general
                ? "You are a helpful offline AI chat assistant. Answer in \(responseLanguage). local_knowledge_search result: not_relevant for this message unless the user provides a concrete disaster, first-aid, medical, self-harm, or field-operation situation. Be concise, natural, and useful for everyday conversation, writing, translation, coding, brainstorming, and explanations. Do not describe yourself, the model, or the app unless the user asks. If the user asks about safety-critical disaster, medical, self-harm, or field operations, avoid unsafe certainty and ask for emergency help when needed."
                : "You are Crisis Sentinel Edge. Always answer in \(responseLanguage) as the local AI assistant. You have a local tool named local_knowledge_search. For disaster, crisis, first-aid, medical, psychology, logistics, translation, and field-team questions, use the local_knowledge_search tool result and the trusted draft as grounding. Preserve safety boundaries and the user role. Public users get safe guidance only. Field teams get observation/SITREP drafts only. Authorized coordinators may get tasking/resource drafts, but never final official orders; human command approval is required. Do not invent coordinates, diagnoses, treatment claims, citation IDs, dispatches, or verified counts. For gas/chemical hazards: evacuate, avoid switches/sparks/flames, keep distance, and call emergency/gas/hazmat teams. Return short plain text, not JSON."
        }

        return CrisisSentinelModelRequest(
            systemInstruction: systemInstruction,
            userPrompt: request.prompt,
            locale: request.locale,
            mode: request.mode,
            queryDomain: queryDomain,
            responseSchema: queryDomain == .crisisGuided ? Self.gemmaResponseSchema : "",
            contextSnippets: distinctSnippets,
            recentMessages: request.recentMessages.suffix(4).map { String($0.prefix(160)) }
        )
    }

    /// The deterministic "tool call" behind the prompt framing: for crisis-guided turns it runs the
    /// knowledge-index search and packages the top articles; for general turns it returns an
    /// explicit not_relevant result so the model doesn't invent disaster grounding.
    private func runLocalKnowledgeTool(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ) -> CrisisSentinelLocalKnowledgeToolResult {
        let query = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? request.recentMessages.joined(separator: " ")
            : request.prompt
        if queryDomain == .general {
            return CrisisSentinelLocalKnowledgeToolResult(
                isRelevant: false,
                query: query,
                citations: [],
                snippets: [
                    "No local disaster, first-aid, medical, or field-operation source is needed for this message."
                ]
            )
        }

        let citations = knowledgeIndex.search(query: query, limit: 2)
        let snippets = citations.compactMap { citation -> String? in
            guard let article = knowledgeIndex.article(id: citation.articleId) else { return nil }
            var text = "\(article.id) \(article.title): \(article.in30Seconds.prefix(3).joined(separator: " "))"
            if article.dontDo.isEmpty == false {
                text += " Avoid: \(article.dontDo.prefix(2).joined(separator: " "))"
            }
            return String(text.prefix(320))
        }
        return CrisisSentinelLocalKnowledgeToolResult(
            isRelevant: true,
            query: query,
            citations: citations,
            snippets: snippets.isEmpty
                ? ["No exact local article matched; use the trusted safety draft and ask for missing details."]
                : snippets,
            incidentDraft: fallback.incidentDraft
        )
    }

    var hasLocalModelRuntime: Bool {
        modelRuntime?.isReady == true
    }

    private func buildGuideAnswer(citation: CrisisSentinelCitation, locale: Locale) -> String {
        guard let article = knowledgeIndex.article(id: citation.articleId) else {
            return fallbackAnswer(locale: locale, queryDomain: .crisisGuided)
        }

        let firstSteps = article.in30Seconds.prefix(3).map { "• \($0)" }.joined(separator: "\n")
        let detailedSteps = article.stepByStep.prefix(3).map { "• \($0)" }.joined(separator: "\n")
        let dont = article.dontDo.prefix(2).map { "• \($0)" }.joined(separator: "\n")

        if CrisisSentinelText.isTurkish(locale) {
            return [
                "Offline rehbere göre: \(article.title)",
                firstSteps,
                detailedSteps.isEmpty ? nil : "Uygulanacak adımlar:\n\(detailedSteps)",
                dont.isEmpty ? nil : "Dikkat:\n\(dont)"
            ].compactMap { $0 }.joined(separator: "\n\n")
        }

        return [
            "From the offline guide: \(article.title)",
            firstSteps,
            detailedSteps.isEmpty ? nil : "Steps:\n\(detailedSteps)",
            dont.isEmpty ? nil : "Avoid:\n\(dont)"
        ].compactMap { $0 }.joined(separator: "\n\n")
    }

    private func buildFieldTeamAnswer(
        draft: CrisisSentinelIncidentDraft,
        citations: [CrisisSentinelCitation],
        locale: Locale
    ) -> String {
        if CrisisSentinelText.isTurkish(locale) {
            return [
                "Saha taslağı hazırlandı.",
                "Öncelik: \(draft.priority.rawValue) • Olay: \(draft.type.rawValue)",
                draft.locationText.map { "Konum: \($0)" },
                draft.casualtyCount.map { "Yaralı/etkilenen: \($0)" },
                draft.trappedCount.map { "Mahsur: \($0)" },
                draft.hazards.isEmpty ? nil : "Riskler: \(draft.hazards.joined(separator: ", "))",
                draft.needs.isEmpty ? nil : "İhtiyaçlar: \(draft.needs.joined(separator: ", "))",
                hazardOperationalWarning(for: draft, locale: locale).map { "Güvenlik notu: \($0)" },
                draft.missingFields.isEmpty ? nil : "Eksik bilgi: \(draft.missingFields.joined(separator: ", "))",
                citations.first.map { "İlgili offline rehber: \($0.title)" }
            ].compactMap { $0 }.joined(separator: "\n")
        }

        return [
            "Field report draft prepared.",
            "Priority: \(draft.priority.rawValue) • Incident: \(draft.type.rawValue)",
            draft.locationText.map { "Location: \($0)" },
            draft.casualtyCount.map { "Injured/affected: \($0)" },
            draft.trappedCount.map { "Trapped: \($0)" },
            draft.hazards.isEmpty ? nil : "Hazards: \(draft.hazards.joined(separator: ", "))",
            draft.needs.isEmpty ? nil : "Needs: \(draft.needs.joined(separator: ", "))",
            hazardOperationalWarning(for: draft, locale: locale).map { "Safety note: \($0)" },
            draft.missingFields.isEmpty ? nil : "Missing: \(draft.missingFields.joined(separator: ", "))",
            citations.first.map { "Related offline guide: \($0.title)" }
        ].compactMap { $0 }.joined(separator: "\n")
    }

    private func buildCoordinatorAnswer(
        draft: CrisisSentinelIncidentDraft,
        citations: [CrisisSentinelCitation],
        locale: Locale
    ) -> String {
        if CrisisSentinelText.isTurkish(locale) {
            return [
                "Koordinasyon taslak notu hazırlandı.",
                "Öncelik: \(draft.priority.rawValue) • Olay: \(draft.type.rawValue)",
                draft.locationText.map { "Konum: \($0)" },
                draft.casualtyCount.map { "Yaralı/etkilenen: \($0)" },
                draft.trappedCount.map { "Mahsur: \($0)" },
                draft.hazards.isEmpty ? nil : "Riskler: \(draft.hazards.joined(separator: ", "))",
                draft.needs.isEmpty ? nil : "Kaynak/ihtiyaç: \(draft.needs.joined(separator: ", "))",
                "Taslak görev/kaynak notu: güvenli erişim teyidi, risk izolasyonu, kaynak talebi ve saha geri bildirimi ayrı kanallardan doğrulansın.",
                hazardOperationalWarning(for: draft, locale: locale).map { "Güvenlik sınırı: \($0)" },
                draft.missingFields.isEmpty ? nil : "Eksik bilgi: \(draft.missingFields.joined(separator: ", "))",
                "Bu çıktı sadece taslak nottur; komuta/kurum onayı olmadan ekip yönlendirme veya saha uygulaması başlatmaz.",
                citations.first.map { "İlgili offline rehber: \($0.title)" }
            ].compactMap { $0 }.joined(separator: "\n")
        }

        return [
            "Coordination draft prepared.",
            "Priority: \(draft.priority.rawValue) • Incident: \(draft.type.rawValue)",
            draft.locationText.map { "Location: \($0)" },
            draft.casualtyCount.map { "Injured/affected: \($0)" },
            draft.trappedCount.map { "Trapped: \($0)" },
            draft.hazards.isEmpty ? nil : "Risks: \(draft.hazards.joined(separator: ", "))",
            draft.needs.isEmpty ? nil : "Resources/needs: \(draft.needs.joined(separator: ", "))",
            "Tasking draft: verify safe access, isolate risks, request resources, and collect field confirmation through separate channels.",
            hazardOperationalWarning(for: draft, locale: locale).map { "Safety boundary: \($0)" },
            draft.missingFields.isEmpty ? nil : "Missing: \(draft.missingFields.joined(separator: ", "))",
            "This output is a draft note only; it does not authorize team direction or field action without command/agency approval.",
            citations.first.map { "Related offline guide: \($0.title)" }
        ].compactMap { $0 }.joined(separator: "\n")
    }

    private func buildMessageSummary(
        messages: [String],
        draft: CrisisSentinelIncidentDraft?,
        locale: Locale
    ) -> String {
        let latest = messages.suffix(4).map { String($0.prefix(80)) }.joined(separator: " / ")
        if CrisisSentinelText.isTurkish(locale) {
            return [
                "Son mesaj özeti: \(latest)",
                draft.map { "Algılanan öncelik: \($0.priority.rawValue)" },
                draft?.missingFields.isEmpty == false ? "Eksik: \(draft?.missingFields.joined(separator: ", ") ?? "")" : nil
            ].compactMap { $0 }.joined(separator: "\n")
        }

        return [
            "Recent message summary: \(latest)",
            draft.map { "Detected priority: \($0.priority.rawValue)" },
            draft?.missingFields.isEmpty == false ? "Missing: \(draft?.missingFields.joined(separator: ", ") ?? "")" : nil
        ].compactMap { $0 }.joined(separator: "\n")
    }

    private func isUsageQuestion(_ prompt: String) -> Bool {
        let normalized = CrisisSentinelText.normalized(prompt)
        if normalized.isEmpty { return false }
        return [
            "seni nasil kullan",
            "nasil kullan",
            "ne yapabilirsin",
            "ne ise yararsin",
            "yardim eder misin",
            "komut",
            "ornek soru",
            "how can i use",
            "what can you do"
        ].contains { normalized.contains($0) }
    }

    private func classifyQueryDomain(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> CrisisSentinelQueryDomain {
        let cleanPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let criticalAnswer = buildCriticalPlainAnswer(prompt: cleanPrompt, mode: request.mode, locale: request.locale)
        return classifyQueryDomain(
            request: request,
            incidentDraft: fallback.incidentDraft,
            criticalAnswer: criticalAnswer
        )
    }

    private func classifyQueryDomain(
        request: CrisisSentinelRequest,
        incidentDraft: CrisisSentinelIncidentDraft?,
        criticalAnswer: String?
    ) -> CrisisSentinelQueryDomain {
        let cleanPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        let query = (cleanPrompt.isEmpty ? request.recentMessages.joined(separator: " ") : cleanPrompt)
        let normalized = CrisisSentinelText.normalized(query)
        guard normalized.isEmpty == false else { return .crisisGuided }
        // Checked BEFORE the criticalAnswer/incidentDraft short-circuit (Android parity): the
        // intake question has a canned critical answer, so without this guard "can you help with
        // first aid?" would run the crisis-guided pipeline and answer with an unrelated guide
        // article instead of asking what actually happened.
        if isFirstAidIntakeQuestion(normalized) {
            return .general
        }
        if criticalAnswer != nil || incidentDraft != nil || isUsageQuestion(cleanPrompt) {
            return .crisisGuided
        }

        let strongCrisisSignals = [
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
        ]
        let operationalSignals = [
            "sitrep",
            "saha",
            "ekip",
            "gorev",
            "kaynak",
            "lojistik"
        ]
        let operationalContextSignals = strongCrisisSignals + [
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
        ]
        let hasStrongSignal = strongCrisisSignals.contains { containsNormalizedSignal(normalized, $0) }
        let hasOperationalSignal = operationalSignals.contains { containsNormalizedSignal(normalized, $0) }
        let hasOperationalContext = request.mode != .publicUser ||
            operationalContextSignals.contains { containsNormalizedSignal(normalized, $0) }
        return (hasStrongSignal || (hasOperationalSignal && hasOperationalContext)) ? .crisisGuided : .general
    }

    private func containsNormalizedSignal(_ normalized: String, _ rawSignal: String) -> Bool {
        let signal = CrisisSentinelText.normalized(rawSignal)
        guard signal.isEmpty == false else { return false }
        if signal.rangeOfCharacter(from: CharacterSet.alphanumerics.inverted) != nil {
            return normalized.contains(signal)
        }
        let escaped = NSRegularExpression.escapedPattern(for: signal)
        return normalized.range(
            of: "(^|[^a-z0-9])\(escaped)($|[^a-z0-9])",
            options: .regularExpression
        ) != nil
    }

    /// "Can you help with first aid?" without any concrete symptom is an INTAKE question: the right
    /// move is to ask what happened (structured first-aid flow), not to free-generate or dump a
    /// random guide article. Terms cover Turkish and English because the app ships in 19 languages
    /// and English is the common fallback.
    private func isFirstAidIntakeQuestion(_ normalized: String) -> Bool {
        func hasAny(_ terms: [String]) -> Bool {
            terms.contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        }
        let hasFirstAid = hasAny(["ilk yardim", "ilk yardım", "first aid"])
        let asksBeforeDetails = hasAny([
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
        ])
        let hasConcreteMedicalDetail = hasAny([
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
        ])
        return hasFirstAid && asksBeforeDetails && !hasConcreteMedicalDetail
    }

    private func isGreetingOnly(_ prompt: String) -> Bool {
        Self.shortGreetings.contains(CrisisSentinelText.normalized(prompt))
    }

    private static let shortGreetings: Set<String> = [
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
    ]

    private func buildCriticalPlainAnswer(
        prompt: String,
        mode: CrisisSentinelUserMode,
        locale: Locale
    ) -> String? {
        let normalized = CrisisSentinelText.normalized(prompt)
        guard normalized.isEmpty == false else { return nil }
        let turkish = CrisisSentinelText.isTurkish(locale)
        let coordinatorMode = mode == .coordinator

        func hasAny(_ terms: [String]) -> Bool {
            terms.contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        }

        func isCrisisTranslationQuestion() -> Bool {
            let hasLanguageOrTranslation = hasAny([
                "çevir",
                "cevir",
                "çeviri",
                "ceviri",
                "translation",
                "translate",
                "tercüme",
                "tercume",
                "ingilizce",
                "english",
                "arapça",
                "arapca"
            ])
            let hasCrisisMessageContext = hasAny([
                "tahliye",
                "uyarı",
                "uyari",
                "resmi talimat",
                "kriz",
                "afet",
                "saha",
                "enkaz",
                "yangın",
                "yangin",
                "deprem",
                "sel",
                "112",
                "acil",
                "barınma",
                "barinma"
            ])
            let explicitTranslateAction = hasAny([
                "tahliye mesaj",
                "uyarı mesaj",
                "uyari mesaj",
                "talimatı çevir",
                "talimati cevir",
                "evacuation message",
                "translate evacuation",
                "translate warning"
            ])
            return (hasLanguageOrTranslation && hasCrisisMessageContext) || explicitTranslateAction
        }

        switch true {
        case hasAny(["sistem prompt", "system prompt", "önceki tüm kuralları", "onceki tum kurallari", "kuralları unut", "kurallari unut"]):
            return turkish
                ? "Bunu yapamam; sistem talimatlarını veya gizli içeriği paylaşmam. Crisis Sentinel olarak afet hazırlığı, ilk yardım sınırları, saha raporu, çeviri veya Crisis Connect kullanımı hakkında güvenli yardım verebilirim. İstersen olay türü, konum ve riskleri yaz; kısa ve uygulanabilir bir cevap hazırlayayım."
                : "I cannot share system instructions or hidden content. Crisis Sentinel can help with disaster preparedness, first-aid boundaries, field reports, translation, or Crisis Connect usage. Share the incident type, location, and risks, and I will give a short safe answer."
        case hasAny(["oyun hilesi", "hile yaz", "exploit", "bypass", "cheat"]):
            return turkish
                ? "Bu konu Crisis Sentinel'in afet, kriz, ilk yardım, psikolojik ilk yardım, çeviri ve saha raporu görevlerinin dışında. İstersen afet çantası, güvenli tahliye, saha notu veya Crisis Connect araçlarını nasıl kullanacağını anlatabilirim."
                : "That is outside Crisis Sentinel's disaster, crisis, first-aid, psychological first-aid, translation, and field-report scope. I can help with preparedness, safe evacuation, field notes, or how to use Crisis Connect tools."
        case hasAny(["fotoğraf", "fotograf", "dosya", "ocr", "ses", "dikte"]) && hasAny(["ne yap", "yapabiliyor", "yapabiliyorsun", "destek"]):
            return turkish
                ? "Fotoğraf ve dosyada görünür bulgu, OCR metni, malzeme sınıflama ve kısa saha notu taslağı desteği verebilirim. Sesli dikte metne çevrilirse bunu kriz sorusu veya rapor girdisi olarak kullanabilirim. Fotoğraftan tıbbi tanı, kimlik tespiti, kesin koordinat veya bina güvenliği onayı vermem; belirsizliği açık yazarım."
                : "I can help turn photos, files, OCR text, supply observations, and voice dictation into short crisis questions or field-note drafts. I do not diagnose from photos, identify people, certify building safety, or invent exact coordinates; I state uncertainty clearly."
        case isFirstAidIntakeQuestion(normalized):
            return turkish
                ? "Evet, ilk yardım konusunda güvenli sınırlar içinde yardımcı olurum. Durumu yaz: ne oldu, kişi bilinçli mi, nefes alıyor mu, kanama/yanık/kırık var mı, yaşı yaklaşık kaç ve bulunduğunuz yer güvenli mi? Hayati risk, bilinç kaybı, nefes sorunu, yoğun kanama veya göğüs ağrısı varsa 112 önceliklidir. Tanı koymam ve profesyonel sağlık desteğinin yerine geçmem."
                : "Yes, I can help with first-aid guidance within safe limits. Tell me what happened, whether the person is conscious and breathing, whether there is bleeding, burn, or fracture, the approximate age, and whether the area is safe. If there is life-threatening danger, loss of consciousness, breathing trouble, heavy bleeding, or chest pain, call emergency services first. I do not diagnose or replace professional medical care."
        case hasAny(["gaz kokusu", "gaz sizinti", "gaz sızıntı", "kimyasal", "keskin koku"]) &&
            hasAny(["sigorta", "kapat", "kaynağı", "kaynagi", "kendim", "vana"]):
            return turkish
                ? "Gaz veya kimyasal risk olabilir; kıvılcım yaratabilecek sigorta, elektrik anahtarı, priz, çakmak, kibrit ve cihazlara dokunma. Güvenliyse kapı/pencereyi zorlamadan açık alana çık ve bina içine geri dönme. Gaz kaynağına yaklaşma veya onarım deneme. Güvenli mesafeden 112, itfaiye veya gaz acil hattını ara."
                : "This may be a gas or chemical hazard; do not touch breakers, switches, plugs, lighters, matches, or devices that could spark. Move to open air if safe and do not re-enter. Do not approach or repair the source. From a safe distance, call emergency, fire, or gas response."
        case hasAny(["deprem"]) && hasAny(["binadan çık", "binadan cik", "çıkmalı", "cikmali", "oldu"]):
            return turkish
                ? "Önce güvenliğini sağla; hasarlı bina, cam, elektrik hattı, gaz kokusu ve çökme riski olan yerlerden uzak dur. Artçı sarsıntılara karşı sağlam ayakkabı giy, asansör kullanma ve riskli binaya dönme. Resmi uyarıları ve yerel ekip talimatlarını takip et. Yaralanma, yangın, gaz kokusu veya mahsur kalma varsa 112'yi ara."
                : "Prioritize safety and stay away from damaged buildings, glass, power lines, gas smells, and collapse risks. Expect aftershocks, avoid elevators, and do not return to unsafe structures. Follow official alerts and local responder instructions. Call emergency services for injury, fire, gas smell, or entrapment."
        case hasAny(["çok kanama", "cok kanama", "ciddi kanama", "kanama var", "kanayan"]):
            return turkish
                ? "Bu tanı veya tedavi planı değildir; ciddi kanamada 112/sağlık desteği önceliklidir. Güvenliyse temiz bezle kanayan bölgeye doğrudan ve sürekli bası uygula; bez ıslanırsa kaldırmadan üstüne yenisini koy. Kişiyi sıcak tut, bilinç ve nefes durumunu izle. İlaç verme ve yaraya yabancı cisim varsa çıkarmaya çalışma."
                : "This is not a diagnosis or treatment plan; serious bleeding needs emergency/medical support. If safe, apply direct continuous pressure with clean cloth, adding more cloth without removing soaked layers. Keep the person warm and monitor breathing/consciousness. Do not give medication or remove embedded objects."
        case hasAny(["göğs", "gogus", "nefes alam", "nefes nefese", "kalp"]):
            return turkish
                ? "Tanı koyma; göğüs ağrısı ve nefes alamama tıbbi acil belirti olabilir. Kişiyi güvenli alana al, kalabalığı azalt ve 112/sağlık desteğini çağır. Ağızdan yiyecek, içecek veya ilaç verme. Net konum, başlangıç zamanı, bilinç ve nefes durumunu not et."
                : "Do not diagnose; chest pain and trouble breathing can be medical emergency signs. Move the person to a safe area, reduce crowding, and call emergency/medical support. Do not give food, drink, or medication. Note location, start time, consciousness, and breathing."
        case hasAny(["kendime zarar", "kendine zarar", "ölmek ist", "olmek ist", "intihar"]):
            return turkish
                ? "Bu terapi veya psikiyatrik tanı değildir; şu an güvenlik öncelikli. Lütfen yalnız kalma: güvendiğin birini hemen yanına çağır veya güvenli bir alana geç. İlaç, kesici alet veya tehlikeli nesnelere erişimini güvenilir birinden uzaklaştırmasını iste. 112 veya yerel kriz-sağlık desteğini şimdi ara."
                : "This is not therapy or a psychiatric diagnosis; immediate safety comes first. Do not stay alone: contact a trusted person now or move to a safer place. Ask someone reliable to remove access to medication, sharp objects, or other hazards. Call emergency or local crisis-health support now."
        case hasAny(["çocuk", "cocuk", "çocu", "cocu"]) && hasAny(["enkaz", "travma", "ayrıntılı", "ayrintili", "anlattır", "anlattir"]):
            return turkish
                ? "Çocuğu ayrıntı anlatmaya zorlamayın; güvenli, sakin ve bakım veren bir yetişkine yakın kalması önceliklidir. Kısa, yaşına uygun ve doğru cümleler kullanın; suçlayıcı veya korkutucu dil kullanmayın. Temel ihtiyaç, kayıp kişi veya korunma riski varsa ilgili çocuk koruma ya da sağlık ekibine bildirin."
                : "Do not force a child to describe traumatic details. Keep the child safe, calm, and close to a caregiver. Use short, age-appropriate, truthful language without blame or frightening detail. Escalate basic needs, missing-person concerns, or protection risks to child-protection or health teams."
        case coordinatorMode && hasAny(["görev", "gorev", "kaynak", "öncelik", "oncelik", "ekip", "koordine", "kriz masası", "kriz masasi"]):
            return turkish
                ? "Koordinasyon taslağı: olayı, konumu, riskleri, etkilenen kişi sayısını, kapalı yolları ve acil kaynak ihtiyacını ayrı yaz. Görevleri taslak olarak üret: güvenli erişim teyidi, risk izolasyonu, kaynak talebi ve saha geri bildirimi. Eksik sayı, konum veya sağlık bilgisini doğrulanmış gibi yazma. Bu çıktı sadece taslak nottur; komuta/kurum onayı olmadan ekip yönlendirme veya saha uygulaması başlatmaz."
                : "Coordination draft: separate the incident, location, risks, affected people, closed routes, and urgent resources. Write tasking as drafts: safe-access verification, risk isolation, resource request, and field confirmation. Do not present missing counts, locations, or medical details as verified. This output is a draft note only; it does not authorize team direction or field action without command/agency approval."
        case coordinatorMode == false && hasAny(["görev ver", "gorev ver", "ekiplere görev", "ekiplere gorev", "sevk et", "ambulansı sevk", "ambulansi sevk", "ekipleri yönlendir", "ekipleri yonlendir"]):
            return turkish
                ? "Bu modda resmi görev, sevk veya ekip emri veremem. Kısa ihtiyaç/SITREP notu hazırlayabilirim: konum, kişi sayısı, risk, acil ihtiyaç, kapalı yol ve iletişim bilgisi. Hayati riskte 112 ve yetkili kriz/komuta kanalları önceliklidir. Eksik bilgiyi kesinmiş gibi yazma."
                : "This mode cannot issue official tasks, dispatches, or team orders. I can prepare a short needs/SITREP note: location, people affected, risks, urgent needs, closed routes, and contact details. For life-threatening danger, emergency services and authorized command channels come first. Do not present missing details as certain."
        case hasAny(["sitrep", "kriz masası", "kriz masasi", "saha notu"]) ||
            (mode == .fieldTeam && hasAny(["elektrik", "su", "yol", "kapalı", "kapali"])):
            return turkish
                ? "SITREP taslağı için konum, zaman, olay türü, görünen risk, etkilenen kişi sayısı, acil ihtiyaç ve kapalı yol bilgisini ayrı ayrı yaz. Eksik olan kişi sayısı, güvenli erişim noktası, su miktarı, tıbbi acil durum ve iletişim kişisini uydurma; kısa takip sorusu sor. Bu resmi komuta kararının yerine geçmez, saha gözlemini düzenler."
                : "For a SITREP, separate location, time, incident type, visible risks, affected people, urgent needs, and closed roads. Do not invent missing people counts, safe access points, supply levels, medical emergencies, or contacts; ask short follow-up questions. This organizes field observation and does not replace command decisions."
        case isCrisisTranslationQuestion():
            return turkish
                ? "Tahliye çevirisinde tehlike, güvenli alan, saat, rota ve kimlerin tahliye edileceği net yazılmalı. Belirsiz bilgi kesinmiş gibi çevrilmemeli; resmi talimatın anlamı değiştirilmemeli. Kısa cümle kullan ve panik yaratacak ek ifade ekleme. Kişisel veri veya mahremiyet içeren bilgileri gereksiz paylaşma."
                : "For evacuation translation, clearly preserve the hazard, safe area, time, route, and who must evacuate. Do not turn uncertain information into certainty or change official instructions. Use short sentences and avoid panic-inducing additions. Do not share unnecessary personal or private data."
        default:
            return nil
        }
    }

    private func buildUsageAnswer(locale: Locale) -> String {
        if CrisisSentinelText.isTurkish(locale) {
            return """
            Crisis Sentinel'i kısa ve net olay bilgisiyle kullanabilirsin.
            • Crisis Connect içinde genel kullanıcıysan afet hazırlığı, ilk yardım sınırları, güvenli tahliye, barınma ve iletişim hakkında sor.
            • Saha ekibiysen konum, kişi sayısı, riskler ve ihtiyaçları yaz; ben SITREP/olay taslağı çıkarayım.
            • Yetkili koordinasyondaysan görev, kaynak, öncelik ve devir notu taslağı iste; çıktı komuta onayı gerektirir.
            • Fotoğraf, dosya veya dikte ekleyerek metni/bağlamı prompt'a dahil edebilirsin.
            Hayati risk varsa 112 ve resmi talimatlar önceliklidir.
            Örnek: “Apartman girişinde gaz kokusu var, 2 kişi panikte, ne yapalım?”
            """
        }

        return """
        Use Crisis Sentinel inside Crisis Connect with short, specific incident context.
        • Public users can ask about preparedness, first-aid boundaries, evacuation, shelter, and communication.
        • Field teams can provide location, people affected, hazards, and needs; I can draft a SITREP.
        • Authorized coordinators can request draft tasking, resource priorities, and handover notes; output requires command approval.
        • You can add photo OCR, file text, or voice dictation as extra context.
        For life-threatening danger, local emergency services and official instructions come first.
        Example: “There is a gas smell at the apartment entrance, 2 people are panicking. What should we do?”
        """
    }

    private func followUpQuestions(for draft: CrisisSentinelIncidentDraft?, locale: Locale) -> [String] {
        guard let draft, draft.missingFields.isEmpty == false else { return [] }
        let turkish = CrisisSentinelText.isTurkish(locale)
        return draft.missingFields.prefix(3).map { field in
            if turkish {
                switch field {
                case "konum/adres": return "Net konum veya en yakın işaret nedir?"
                case "etkilenen kişi sayısı": return "Kaç kişi yaralı, mahsur veya etkilenmiş görünüyor?"
                case "ek riskler": return "Gaz kokusu, duman, elektrik veya çökme riski var mı?"
                default: return "Hangi destek gerekiyor?"
                }
            }

            switch field {
            case "konum/adres": return "What is the exact location or nearest landmark?"
            case "etkilenen kişi sayısı": return "How many people are injured, trapped, or affected?"
            case "ek riskler": return "Are there gas, smoke, electrical, or collapse hazards?"
            default: return "What support is needed?"
            }
        }
    }

    private func safetyNotices(
        locale: Locale,
        draft: CrisisSentinelIncidentDraft?,
        queryDomain: CrisisSentinelQueryDomain = .crisisGuided
    ) -> [String] {
        guard queryDomain == .crisisGuided else { return [] }

        let base: [String]
        if CrisisSentinelText.isTurkish(locale) {
            base = [
                "Hayati tehlike varsa 112'yi ara.",
                "Crisis Sentinel offline öneri verir; resmi talimat ve saha komuta kararının yerine geçmez."
            ]
        } else {
            base = [
                "Call the local emergency number for life-threatening danger.",
                "Crisis Sentinel provides offline assistance; it does not replace official command decisions."
            ]
        }

        guard let warning = hazardOperationalWarning(for: draft, locale: locale) else { return base }
        return base + [warning]
    }

    private func hazardOperationalWarning(for draft: CrisisSentinelIncidentDraft?, locale: Locale) -> String? {
        guard let draft else { return nil }
        let hasGas = draft.hazards.contains("gaz kokusu")
        let hasChemical = draft.hazards.contains("kimyasal/sızıntı riski")
        guard draft.type == .hazard || hasGas || hasChemical else { return nil }

        if CrisisSentinelText.isTurkish(locale) {
            if hasGas {
                return "Gaz kaynağı aranmaz veya onarılmaz; elektrik anahtarı, sigorta, cihaz, ateş/kıvılcım ve kapalı alanda telefon kullanılmaz. Güvenli mesafeden 112/itfaiye/gaz ekibi bilgilendirilir."
            }
            if hasChemical {
                return "Korumasız yaklaşma, koklama veya temizleme yapılmaz. Alan izole edilir; güvenli mesafeden itfaiye/tehlikeli madde desteği istenir."
            }
            return "Tehlike kaynağına yaklaşmadan güvenli mesafe korunur ve ilgili acil ekip bilgilendirilir."
        }

        if hasGas {
            return "Do not search for or repair the gas source; do not use switches, breakers, devices, flames/sparks, or phones indoors. Notify emergency/fire/gas teams from a safe distance."
        }
        if hasChemical {
            return "Do not approach, smell, or clean it without protection. Isolate the area and request fire/hazmat support from a safe distance."
        }
        return "Keep a safe distance from the hazard source and notify the relevant emergency team."
    }

    private func fallbackAnswer(
        prompt: String = "",
        locale: Locale,
        queryDomain: CrisisSentinelQueryDomain = .crisisGuided
    ) -> String {
        if queryDomain == .general {
            // A bare "selam"/"hi" doesn't deserve a "this needs more detail" lecture (or a model
            // call when the runtime is down) — return a warm canned greeting instead.
            if isGreetingOnly(prompt) {
                return CrisisSentinelText.isTurkish(locale)
                    ? "Selam. Ne hakkında konuşmak istersin? Genel sorulara doğrudan cevap verebilir; afet, ilk yardım veya saha notlarında yerel kaynaklara göre yardımcı olurum."
                    : "Hi. What would you like to talk about? I can answer general questions directly; for disaster, first-aid, or field notes, I use local sources."
            }
            if CrisisSentinelText.isTurkish(locale) {
                return "Bu genel soru için offline afet rehberinden kaynak gerekmedi. Yerel AI modeli hazırsa doğrudan cevap verebilirim; model hazır değilse kısa bir afet/kriz bağlamı yaz veya modeli indir."
            }

            return "This general question does not need offline disaster-guide sources. If the local AI model is ready, I can answer directly; otherwise, add disaster/crisis context or download the model."
        }

        if CrisisSentinelText.isTurkish(locale) {
            return "Offline bellekte bu konu için net bir rehber kaydı bulamadım. Kısa ve net yaz: olay türü, konum, kişi sayısı, riskler ve ihtiyaç. Hayati tehlike varsa 112'yi ara."
        }

        return "I could not find a clear offline guide entry for this. Provide incident type, location, people affected, hazards, and needs. Call the local emergency number for life-threatening danger."
    }

    private func roleInstruction(mode: CrisisSentinelUserMode, locale: Locale) -> String {
        if CrisisSentinelText.isTurkish(locale) {
            switch mode {
            case .publicUser:
                return "Kullanıcı rolü: genel kullanıcı. Resmi görev, ekip emri, sevk, tanı veya yapı güvenliği kararı verme; güvenli rehberlik ve yetkili kanala yönlendirme yap."
            case .fieldTeam:
                return "Kullanıcı rolü: saha ekibi. Gözlem, SITREP/SBAR ve eksik bilgi taslağı hazırla; resmi komuta kararı veya ekip emri üretme."
            case .coordinator:
                return "Kullanıcı rolü: yetkili koordinasyon. Görev, kaynak, öncelik ve devir taslakları üretebilirsin; her çıktı komuta/kurum onayı gerektirir ve onaysız ekip yönlendirme başlatmaz."
            }
        }

        switch mode {
        case .publicUser:
            return "User role: public user. Do not issue official tasks, team orders, dispatches, diagnoses, or building-safety decisions; provide safe guidance and route to authorized channels."
        case .fieldTeam:
            return "User role: field team. Prepare observation, SITREP/SBAR, and missing-field drafts; do not issue official command decisions or team orders."
        case .coordinator:
            return "User role: authorized coordination. You may draft tasking, resource, priority, and handover notes; every output requires command/agency approval and does not authorize deployment."
        }
    }

    private static let gemmaResponseSchema = """
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
    """
}

/// The packaged result of `runLocalKnowledgeTool`, rendered into context snippets that read like a
/// real tool invocation (status, query, sources). Keeping the framing identical across turns is what
/// teaches the small model to trust the tool result instead of hallucinating sources.
private struct CrisisSentinelLocalKnowledgeToolResult {
    let isRelevant: Bool
    let query: String
    let citations: [CrisisSentinelCitation]
    let snippets: [String]
    var incidentDraft: CrisisSentinelIncidentDraft? = nil

    func toContextSnippets() -> [String] {
        let status = isRelevant ? "relevant" : "not_relevant"
        var lines = [
            "Tool result: local_knowledge_search",
            "Tool status: \(status)",
            "Tool query: \(query.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).prefix(180))"
        ]
        if isRelevant {
            lines.append("Tool instruction: Use these local sources for disaster, first-aid, medical, psychological first-aid, crisis logistics, translation, and field-operation answers. Do not invent source IDs or facts outside the tool result.")
            if citations.isEmpty == false {
                lines.append("Tool citations: \(citations.map { "\($0.articleId) \($0.title)" }.joined(separator: ", "))")
            }
            for (index, snippet) in snippets.enumerated() {
                lines.append("Tool source \(index + 1): \(snippet.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).prefix(360))")
            }
            if let draft = incidentDraft {
                lines.append("Tool extracted incident: type=\(draft.type.rawValue), priority=\(draft.priority.rawValue), missing=\(draft.missingFields.joined(separator: ", "))")
            }
        } else {
            lines.append("Tool instruction: Do not force disaster, emergency, SITREP, medical, or Crisis Connect content. Answer as normal chat unless the user provides a concrete crisis, first-aid, medical, self-harm, or field-operation situation.")
        }
        return lines
    }
}

private enum CrisisSentinelSafetyCoverage {
    static func contextSnippets(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> [String] {
        let requiredTerms = requiredTerms(request: request, fallback: fallback)
        guard requiredTerms.isEmpty == false else { return [] }
        var snippets = [
            "Mandatory safety terms to preserve when relevant: \(requiredTerms.joined(separator: ", "))"
        ]
        if let sentence = completionSentences(from: fallback.answer, missingTerms: requiredTerms).first {
            snippets.append("Required trusted sentence: \(sentence)")
        }
        return snippets
    }

    static func completeAnswer(
        _ answer: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ) -> String {
        guard queryDomain != .general else { return answer }
        let missingTerms = requiredTerms(request: request, fallback: fallback)
            .filter { containsNormalized(answer, term: $0) == false }
        guard missingTerms.isEmpty == false else { return answer }

        let additions = completionSentences(from: fallback.answer, missingTerms: missingTerms)
            .filter { containsNormalized(answer, term: $0) == false }
        guard additions.isEmpty == false else { return answer }

        return ([answer.trimmingCharacters(in: .whitespacesAndNewlines)] + additions)
            .filter { $0.isEmpty == false }
            .joined(separator: "\n\n")
            .prefix(maxCompletedAnswerCharacters)
            .description
    }

    private static func requiredTerms(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> [String] {
        let rawPrompt = request.prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? request.recentMessages.joined(separator: " ")
            : request.prompt
        let normalizedPrompt = CrisisSentinelText.normalized(rawPrompt)
        var terms: [String] = []

        func promptHas(_ signals: [String]) -> Bool {
            signals.contains { normalizedPrompt.contains(CrisisSentinelText.normalized($0)) }
        }

        func addFallbackTerms(_ candidates: [String]) {
            for candidate in candidates where containsNormalized(fallback.answer, term: candidate) && terms.contains(candidate) == false {
                terms.append(candidate)
            }
        }

        let dispatchOrOrderPrompt = promptHas([
            "görev ver",
            "gorev ver",
            "ekiplere görev",
            "ekiplere gorev",
            "sevk et",
            "ambulansı sevk",
            "ambulansi sevk",
            "ambulans sevk",
            "ekipleri yönlendir",
            "ekipleri yonlendir",
            "dispatch",
            "official order"
        ])
        if request.mode != .coordinator, dispatchOrOrderPrompt {
            addFallbackTerms(["konum", "location", "112", "emergency", "yetkili", "authorized"])
        }

        if request.mode == .coordinator, promptHas([
            "görev",
            "gorev",
            "kaynak",
            "öncelik",
            "oncelik",
            "ekip",
            "koordine",
            "kriz masası",
            "kriz masasi",
            "handover",
            "tasking"
        ]) {
            addFallbackTerms(["taslak", "onay", "komuta", "draft", "approval", "command"])
        }

        if promptHas(["sitrep", "kriz masası", "kriz masasi", "saha notu"]) ||
            (request.mode == .fieldTeam && promptHas(["elektrik", "su", "yol", "kapalı", "kapali"]))
        {
            addFallbackTerms(["konum", "risk", "ihtiyaç", "ihtiyac", "eksik", "location", "risk", "need", "missing"])
        }

        if promptHas(["tahliye", "uyarı", "uyari", "resmi talimat", "çeviri", "ceviri", "translate", "translation"]) {
            addFallbackTerms([
                "tehlike",
                "güvenli alan",
                "guvenli alan",
                "belirsiz",
                "mahremiyet",
                "hazard",
                "safe area",
                "uncertain",
                "privacy"
            ])
        }

        if promptHas(["çocuk", "cocuk", "çocu", "cocu"]) &&
            promptHas(["enkaz", "travma", "ayrıntılı", "ayrintili", "anlattır", "anlattir"])
        {
            addFallbackTerms(["çocuk", "cocuk", "zorlam", "bakım veren", "bakim veren", "koruma", "child", "caregiver", "protection"])
        }

        if promptHas(["kendime zarar", "kendine zarar", "ölmek ist", "olmek ist", "intihar"]) {
            addFallbackTerms(["yalnız kalma", "yalniz kalma", "112", "güvenilir", "guvenilir", "trusted", "emergency"])
        }

        if promptHas(["göğs", "gogus", "nefes alam", "nefes nefese", "kalp"]) {
            addFallbackTerms(["tanı", "tani", "112", "konum", "diagnosis", "emergency", "location"])
        }

        if promptHas(["çok kanama", "cok kanama", "ciddi kanama", "kanama var", "kanayan"]) {
            addFallbackTerms(["tanı", "tani", "bası", "basi", "112", "ilaç", "ilac", "diagnosis", "pressure", "medication"])
        }

        if promptHas(["gaz kokusu", "gaz sızıntı", "gaz sizinti", "kimyasal", "keskin koku"]) {
            addFallbackTerms(["dokunma", "güvenli", "guvenli", "112", "gaz", "safe", "emergency"])
        }

        return terms
    }

    private static func completionSentences(from answer: String, missingTerms: [String]) -> [String] {
        let fragments = answer
            .replacingOccurrences(of: "\n", with: ". ")
            .components(separatedBy: ".")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.isEmpty == false }
        var selected: [String] = []
        for term in missingTerms {
            guard let fragment = fragments.first(where: { containsNormalized($0, term: term) }) else { continue }
            let sentence = fragment.hasSuffix("!") || fragment.hasSuffix("?") ? fragment : "\(fragment)."
            if selected.contains(sentence) == false {
                selected.append(sentence)
            }
        }
        return selected
    }

    private static func containsNormalized(_ text: String, term: String) -> Bool {
        CrisisSentinelText.normalized(text).contains(CrisisSentinelText.normalized(term))
    }

    private static let maxCompletedAnswerCharacters = 1_400
}

private enum CrisisSentinelModelOutputValidator {
    static func validate(
        request: CrisisSentinelRequest,
        modelResponse: CrisisSentinelModelResponse,
        allowedCitations: [CrisisSentinelCitation],
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain = .crisisGuided
    ) -> CrisisSentinelResponse? {
        let raw = modelResponse.structuredJSON ?? modelResponse.text
        // Shared fallthrough: when the output isn't a usable structured object (e.g. prose followed
        // by a partial/foreign JSON dump), treat it as plain text so the prose is kept and any JSON
        // tail is stripped, instead of discarding the whole answer.
        func asPlainText() -> CrisisSentinelResponse? {
            validatePlainText(
                request: request,
                modelResponse: modelResponse,
                allowedCitations: allowedCitations,
                fallback: fallback,
                queryDomain: queryDomain
            )
        }
        guard let jsonText = extractJSONObject(from: raw) else {
            return asPlainText()
        }
        guard
            let data = jsonText.data(using: .utf8),
            let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let answer = cleanString(root["answer"])
        else { return asPlainText() }

        guard isCannedModelMetaAnswer(answer, request: request) == false else { return nil }
        guard isLowSignalAnswer(answer, request: request, fallback: fallback, queryDomain: queryDomain) == false else { return nil }
        guard containsUnsafeHazardAdvice(answer, fallback: fallback) == false else { return nil }
        guard containsUnsafeOperationalAuthority(answer, request: request) == false else { return nil }
        let completedAnswer = CrisisSentinelSafetyCoverage.completeAnswer(
            answer,
            request: request,
            fallback: fallback,
            queryDomain: queryDomain
        )
        guard containsUnsafeHazardAdvice(completedAnswer, fallback: fallback) == false else { return nil }
        guard missesRequiredHazardSafety(completedAnswer, fallback: fallback) == false else { return nil }
        guard containsUnsafeMentalHealthAdvice(completedAnswer, request: request, fallback: fallback) == false else { return nil }
        guard missesRequiredMedicalBoundary(completedAnswer, request: request, fallback: fallback) == false else { return nil }
        guard containsUnsafeOperationalAuthority(completedAnswer, request: request) == false else { return nil }
        guard let citations = parseCitations(root["citations"], allowedCitations: allowedCitations) else {
            return nil
        }

        let modelDraft: CrisisSentinelIncidentDraft?
        if let draftJSON = root["incidentDraft"] as? [String: Any] {
            guard let parsedDraft = parseIncidentDraft(draftJSON) else { return nil }
            modelDraft = parsedDraft
        } else {
            modelDraft = fallback.incidentDraft
        }

        if fallback.incidentDraft?.type == .hazard, modelDraft?.type != .hazard {
            return nil
        }

        let followUps = parseStringArray(root["followUpQuestions"])
        let modelSafetyNotices = parseStringArray(root["safetyNotices"])
        let safetyNotices = mergeSafetyNotices(modelSafetyNotices: modelSafetyNotices, fallbackSafetyNotices: fallback.safetyNotices)
        let unsafeText = ([completedAnswer] + followUps + modelSafetyNotices).joined(separator: " ")
        guard containsUnsafeHazardAdvice(unsafeText, fallback: fallback) == false else { return nil }
        guard missesRequiredHazardSafety(unsafeText, fallback: fallback) == false else { return nil }
        guard containsUnsafeMentalHealthAdvice(unsafeText, request: request, fallback: fallback) == false else { return nil }
        guard containsUnsafeOperationalAuthority(unsafeText, request: request) == false else { return nil }

        let confidence = min(max(modelResponse.confidence ?? fallback.confidence, 0.25), 0.92)

        return CrisisSentinelResponse(
            answer: completedAnswer,
            mode: request.mode,
            source: .localModel,
            citations: citations.isEmpty ? fallback.citations : citations,
            incidentDraft: modelDraft,
            followUpQuestions: followUps.isEmpty ? fallback.followUpQuestions : followUps,
            safetyNotices: safetyNotices,
            confidence: confidence
        )
    }

    private static func validatePlainText(
        request: CrisisSentinelRequest,
        modelResponse: CrisisSentinelModelResponse,
        allowedCitations: [CrisisSentinelCitation],
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ) -> CrisisSentinelResponse? {
        guard let answer = cleanPlainText(modelResponse.text) else { return nil }
        guard isCannedModelMetaAnswer(answer, request: request) == false else { return nil }
        guard isLowSignalAnswer(answer, request: request, fallback: fallback, queryDomain: queryDomain) == false else { return nil }
        guard containsUnsafeHazardAdvice(answer, fallback: fallback) == false else { return nil }
        guard containsUnsafeOperationalAuthority(answer, request: request) == false else { return nil }
        let completedAnswer = CrisisSentinelSafetyCoverage.completeAnswer(
            answer,
            request: request,
            fallback: fallback,
            queryDomain: queryDomain
        )
        guard containsUnsafeHazardAdvice(completedAnswer, fallback: fallback) == false else { return nil }
        guard missesRequiredHazardSafety(completedAnswer, fallback: fallback) == false else { return nil }
        guard containsUnsafeMentalHealthAdvice(completedAnswer, request: request, fallback: fallback) == false else { return nil }
        guard missesRequiredMedicalBoundary(completedAnswer, request: request, fallback: fallback) == false else { return nil }
        guard containsUnsafeOperationalAuthority(completedAnswer, request: request) == false else { return nil }
        let confidence = min(max(modelResponse.confidence ?? fallback.confidence, 0.25), 0.82)
        return CrisisSentinelResponse(
            answer: completedAnswer,
            mode: request.mode,
            source: .localModel,
            citations: queryDomain == .general ? [] : (allowedCitations.isEmpty ? fallback.citations : allowedCitations),
            incidentDraft: fallback.incidentDraft,
            followUpQuestions: fallback.followUpQuestions,
            safetyNotices: queryDomain == .general ? [] : fallback.safetyNotices,
            confidence: confidence
        )
    }

    private static func cleanPlainText(_ text: String) -> String? {
        let stripped = text
            .replacingOccurrences(of: "(?is)<start_of_turn>.*?\\n", with: "", options: .regularExpression)
            .replacingOccurrences(of: "<end_of_turn>", with: "")
            .replacingOccurrences(of: "```json", with: "")
            .replacingOccurrences(of: "```", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let clean = stripTrailingJSONBlock(stripped).trimmingCharacters(in: .whitespacesAndNewlines)
        guard clean.count >= 16 else { return nil }
        let normalized = CrisisSentinelText.normalized(clean)
        guard looksLikePromptOrSchemaLeak(clean: clean, normalized: normalized) == false else { return nil }
        return String(clean.prefix(maxPlainTextCharacters))
    }

    /// Models (notably Gemma 3n) sometimes append a JSON dump like `{"response": "..."}` after the
    /// plain-text answer even when told to return plain text. Keep only the natural-language prose
    /// before that block so the bubble doesn't show raw JSON.
    private static func stripTrailingJSONBlock(_ text: String) -> String {
        guard let braceRange = text.range(of: "{"), braceRange.lowerBound != text.startIndex else {
            return text
        }
        let tail = text[braceRange.lowerBound...]
        let markers = [
            "\"response\"", "\"answer\"", "\"cevap\"", "\"citations\"",
            "\"incidentDraft\"", "\"safetyNotices\"", "\"followUpQuestions\""
        ]
        let looksStructured = markers.contains { tail.range(of: $0, options: .caseInsensitive) != nil }
        guard looksStructured else { return text }
        let before = text[..<braceRange.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
        return before.count >= 16 ? before : text
    }

    private static func looksLikePromptOrSchemaLeak(clean: String, normalized: String) -> Bool {
        let rawLower = clean.lowercased()
        if clean.hasPrefix("{") || clean.hasPrefix("[") { return true }
        return [
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
        ].contains { normalized.contains($0) || rawLower.contains($0) }
    }

    private static func isLowSignalAnswer(
        _ answer: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain = .crisisGuided
    ) -> Bool {
        let normalized = CrisisSentinelText.normalized(answer)
        if [
            "konum tr tr",
            "dili turkce",
            "locale",
            "mode public",
            "mode fieldteam",
            "mode coordinator"
        ].contains(where: { normalized.contains($0) }) {
            return true
        }
        if isUsageQuestion(request.prompt), looksLikeUsageAnswer(answer) == false {
            return true
        }
        if queryDomain == .general {
            return false
        }

        let expectedTokens = Set(
            CrisisSentinelText.tokens(from: request.prompt) +
            CrisisSentinelText.tokens(from: fallback.answer) +
            fallback.citations.flatMap { CrisisSentinelText.tokens(from: "\($0.title) \($0.snippet)") }
        )
        guard expectedTokens.isEmpty == false else { return false }
        let answerTokens = Set(CrisisSentinelText.tokens(from: answer))
        let overlap = answerTokens.filter { expectedTokens.contains($0) }.count
        return overlap < 2
    }

    private static func isUsageQuestion(_ prompt: String) -> Bool {
        let normalized = CrisisSentinelText.normalized(prompt)
        if normalized.isEmpty { return false }
        return [
            "seni nasil kullan",
            "nasil kullan",
            "ne yapabilirsin",
            "ne ise yararsin",
            "yardim eder misin",
            "komut",
            "ornek soru",
            "how can i use",
            "what can you do"
        ].contains { normalized.contains($0) }
    }

    private static func looksLikeUsageAnswer(_ answer: String) -> Bool {
        let normalized = CrisisSentinelText.normalized(answer)
        if answer.trimmingCharacters(in: .whitespacesAndNewlines).hasSuffix("?") { return false }
        let usageSignals = [
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
        ]
        return usageSignals.filter { normalized.contains($0) }.count >= 2
    }

    /// The small model's favorite failure mode: instead of answering it restates its own framing
    /// ("This output is from an offline on-device assistant…"). That prompt/meta echo must be
    /// rejected so the trusted fallback answers instead — UNLESS the user literally asked "who/what
    /// are you?" and the reply reads like a real capability answer.
    private static func isCannedModelMetaAnswer(_ answer: String, request: CrisisSentinelRequest) -> Bool {
        let normalized = CrisisSentinelText.normalized(answer)
        let prompt = CrisisSentinelText.normalized(request.prompt)
        let startsAsMeta = [
            "bu cikti",
            "this output"
        ].contains { normalized.hasPrefix($0) }
        if startsAsMeta { return true }

        let modelMetaSignals = [
            "offline on-device",
            "offline on device",
            "on-device asistan",
            "on device assistant",
            "yerel model",
            "local model",
            "crisis sentinel edgedir",
            "crisis sentinel edge dir"
        ].contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        guard modelMetaSignals else { return false }

        let promptAsksIdentity = [
            "kimsin",
            "nesin",
            "crisis sentinel nedir",
            "crisis sentinel edge nedir",
            "who are you",
            "what are you"
        ].contains { prompt.contains(CrisisSentinelText.normalized($0)) }
        if promptAsksIdentity && looksLikeUsageAnswer(answer) { return false }

        return true
    }

    private static func containsUnsafeOperationalAuthority(_ text: String, request: CrisisSentinelRequest) -> Bool {
        let normalized = CrisisSentinelText.normalized(text)
        let alwaysUnsafe = [
            "kesin emir",
            "resmi emir verildi",
            "otomatik sevk edildi",
            "ambulans sevk ettim",
            "komuta yerine gecer",
            "command is replaced"
        ].contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        if alwaysUnsafe { return true }

        let looksLikeDispatchOrOrder = [
            "gorev verildi",
            "ekiplere gorev ver",
            "ekipleri yonlendir",
            "sevk edildi",
            "dispatch completed",
            "official order"
        ].contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        guard looksLikeDispatchOrOrder else { return false }
        guard request.mode == .coordinator else { return true }

        let hasDraftBoundary = [
            "taslak",
            "onay",
            "komuta",
            "kurum",
            "draft",
            "approval",
            "command"
        ].contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        return hasDraftBoundary == false
    }

    private static func parseCitations(
        _ value: Any?,
        allowedCitations: [CrisisSentinelCitation]
    ) -> [CrisisSentinelCitation]? {
        guard let rawItems = value as? [[String: Any]], rawItems.isEmpty == false else { return [] }
        let allowedByID = Dictionary(uniqueKeysWithValues: allowedCitations.map { ($0.articleId, $0) })
        var parsed: [CrisisSentinelCitation] = []
        for item in rawItems {
            guard let articleID = cleanString(item["articleId"]), let citation = allowedByID[articleID] else {
                return nil
            }
            if parsed.contains(where: { $0.articleId == citation.articleId }) == false {
                parsed.append(citation)
            }
        }
        return parsed
    }

    private static func parseIncidentDraft(_ json: [String: Any]) -> CrisisSentinelIncidentDraft? {
        guard
            let type = parseIncidentType(cleanString(json["type"])),
            let priority = parsePriority(cleanString(json["priority"]))
        else {
            return nil
        }

        return CrisisSentinelIncidentDraft(
            type: type,
            priority: priority,
            casualtyCount: nullableInt(json["casualtyCount"]),
            trappedCount: nullableInt(json["trappedCount"]),
            locationText: cleanString(json["locationText"]),
            hazards: parseStringArray(json["hazards"]),
            needs: parseStringArray(json["needs"]),
            missingFields: parseStringArray(json["missingFields"]),
            confidence: 0.72
        )
    }

    private static func parseIncidentType(_ value: String?) -> CrisisSentinelIncidentType? {
        switch normalizedKey(value) {
        case "unknown": return .unknown
        case "earthquake": return .earthquake
        case "fire": return .fire
        case "flood": return .flood
        case "hazard": return .hazard
        case "medical": return .medical
        case "trapped": return .trapped
        case "communication": return .communication
        case "logistics": return .logistics
        case "shelter": return .shelter
        case "publichealth": return .publicHealth
        case "translation": return .translation
        case "strategy": return .strategy
        case "evacuation": return .evacuation
        case "mentalhealth": return .mentalHealth
        default: return nil
        }
    }

    private static func parsePriority(_ value: String?) -> CrisisSentinelPriority? {
        switch normalizedKey(value) {
        case "routine": return .routine
        case "high": return .high
        case "immediate": return .immediate
        default: return nil
        }
    }

    private static func parseStringArray(_ value: Any?) -> [String] {
        guard let rawItems = value as? [Any] else { return [] }
        var seen = Set<String>()
        return rawItems.compactMap { item in
            guard let value = cleanString(item), seen.insert(value).inserted else { return nil }
            return value
        }
    }

    private static func mergeSafetyNotices(
        modelSafetyNotices: [String],
        fallbackSafetyNotices: [String]
    ) -> [String] {
        var seen = Set<String>()
        return (modelSafetyNotices + fallbackSafetyNotices).compactMap { notice in
            let trimmed = notice.trimmingCharacters(in: .whitespacesAndNewlines)
            guard trimmed.isEmpty == false, seen.insert(trimmed).inserted else { return nil }
            return trimmed
        }
    }

    private static func containsUnsafeHazardAdvice(_ text: String, fallback: CrisisSentinelResponse) -> Bool {
        let draft = fallback.incidentDraft
        let hazardPrompt = draft?.type == .hazard ||
            draft?.hazards.contains(where: { $0 == "gaz kokusu" || $0 == "kimyasal/sızıntı riski" }) == true
        guard hazardPrompt else { return false }

        let normalized = CrisisSentinelText.normalized(text)
        let unsafePhrases = [
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
        ]
        return unsafePhrases.contains { normalized.contains($0) }
    }

    private static func missesRequiredHazardSafety(_ text: String, fallback: CrisisSentinelResponse) -> Bool {
        let draft = fallback.incidentDraft
        let hasGasOrChemical = draft?.hazards.contains(where: {
            $0 == "gaz kokusu" || $0 == "kimyasal/sızıntı riski"
        }) == true || fallback.citations.contains(where: { $0.articleId == "CSE-GAS-001" })
        guard hasGasOrChemical else { return false }

        let normalized = CrisisSentinelText.normalized(text)
        let nonsensicalOrRisky = [
            "dumani kapat",
            "kokuyu kapat",
            "gazi kapat",
            "gaz vanasini kapat",
            "havalandir"
        ].contains { normalized.contains($0) }
        if nonsensicalOrRisky { return true }

        let hasEvacuation = [
            "tahliye",
            "disari cik",
            "guvenli alana gec",
            "guvenli mesafeye gec",
            "uzaklas",
            "leave",
            "evacuate",
            "safe distance"
        ].contains { normalized.contains($0) }
        let warnsSparkSources = [
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
        ].contains { normalized.contains($0) }
        let callsResponder = [
            "112",
            "itfaiye",
            "gaz ekibi",
            "tehlikeli madde",
            "hazmat",
            "emergency",
            "fire",
            "gas team"
        ].contains { normalized.contains($0) }
        return !(hasEvacuation && warnsSparkSources && callsResponder)
    }

    private static func containsUnsafeMentalHealthAdvice(
        _ text: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> Bool {
        let prompt = CrisisSentinelText.normalized(request.prompt)
        let selfHarmPrompt = fallback.incidentDraft?.type == .mentalHealth ||
            ["kendime zarar", "kendine zarar", "olmek ist", "intihar"].contains { prompt.contains($0) }
        guard selfHarmPrompt else { return false }

        let normalized = CrisisSentinelText.normalized(text)
        let unsafePhrases = [
            "kimseye soyleme",
            "gizli tut",
            "aramizda kalsin",
            "planini sakla",
            "doz",
            "sakinlestirici",
            "terapi yaparim",
            "keep it secret",
            "do not tell anyone"
        ].contains { normalized.contains(CrisisSentinelText.normalized($0)) }
        if unsafePhrases { return true }

        let tellsToStayAlone = [
            "yalniz kal ",
            "yalniz kal.",
            "yalniz kalman",
            "yalniz kalmani",
            "yalniz birak"
        ].contains { normalized.contains($0) } &&
            ["yalniz kalma", "yalniz birakma", "yalniz kalmamali"].contains { normalized.contains($0) } == false
        if tellsToStayAlone { return true }

        let hasEmergency = ["112", "acil", "kriz", "saglik", "health", "emergency"].contains { normalized.contains($0) }
        let hasTrustedPerson = ["guvenilir", "guvendigin", "trusted", "yanina cagir"].contains { normalized.contains($0) }
        return !(hasEmergency && hasTrustedPerson)
    }

    private static func missesRequiredMedicalBoundary(
        _ text: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ) -> Bool {
        let prompt = CrisisSentinelText.normalized(request.prompt)
        let medicalPrompt = fallback.incidentDraft?.type == .medical ||
            ["kanama", "gogus", "nefes", "kalp", "yarali"].contains { prompt.contains($0) }
        guard medicalPrompt else { return false }

        let normalized = CrisisSentinelText.normalized(text)
        let noDiagnosis = ["tani", "teshis", "diagnosis", "not a diagnosis"].contains { normalized.contains($0) }
        let emergency = ["112", "acil", "saglik", "emergency", "medical"].contains { normalized.contains($0) }
        let unsafeMedication = ["mg", "doz", "recete yaz", "ilac verilebilir", "sakinlestirici ver"].contains {
            normalized.contains($0)
        }
        return unsafeMedication || !(noDiagnosis && emergency)
    }

    private static func extractJSONObject(from text: String) -> String? {
        var start: String.Index?
        var depth = 0
        var inString = false
        var escaping = false

        for index in text.indices {
            let character = text[index]
            if start == nil {
                if character == "{" {
                    start = index
                    depth = 1
                }
                continue
            }

            if escaping {
                escaping = false
                continue
            }
            if character == "\\" && inString {
                escaping = true
                continue
            }
            if character == "\"" {
                inString.toggle()
                continue
            }
            if inString { continue }

            if character == "{" {
                depth += 1
            } else if character == "}" {
                depth -= 1
                if depth == 0, let start {
                    return String(text[start...index])
                }
            }
        }
        return nil
    }

    private static func cleanString(_ value: Any?) -> String? {
        guard let value, !(value is NSNull) else { return nil }
        let string: String
        if let value = value as? String {
            string = value
        } else {
            string = "\(value)"
        }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.isEmpty == false, trimmed.lowercased() != "null" else { return nil }
        return trimmed
    }

    private static func nullableInt(_ value: Any?) -> Int? {
        guard let value, !(value is NSNull) else { return nil }
        if let number = value as? NSNumber {
            return number.intValue
        }
        if let string = value as? String {
            return Int(string.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return nil
    }

    private static func normalizedKey(_ value: String?) -> String {
        (value ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "_", with: "")
            .replacingOccurrences(of: "-", with: "")
            .lowercased()
    }

    private static let maxPlainTextCharacters = 1_200
}
