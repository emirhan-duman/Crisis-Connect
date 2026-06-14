package com.auralis.crisisconnect.ai

internal object CrisisSentinelSafetyCoverage {
    fun contextSnippets(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ): List<String> {
        val requiredTerms = requiredTerms(request, fallback)
        if (requiredTerms.isEmpty()) return emptyList()
        return buildList {
            add("Mandatory safety terms to preserve when relevant: ${requiredTerms.joinToString(", ")}")
            completionSentences(fallback.answer, requiredTerms)
                .firstOrNull()
                ?.let { add("Required trusted sentence: $it") }
        }
    }

    fun completeAnswer(
        answer: String,
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse,
        queryDomain: CrisisSentinelQueryDomain
    ): String {
        if (queryDomain == CrisisSentinelQueryDomain.General) return answer
        val missingTerms = requiredTerms(request, fallback)
            .filterNot { answer.containsNormalized(it) }
        if (missingTerms.isEmpty()) return answer

        val additions = completionSentences(fallback.answer, missingTerms)
            .filterNot { answer.containsNormalized(it) }
        if (additions.isEmpty()) return answer

        return (listOf(answer.trim()) + additions)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .replace(Regex("\\s+\\n"), "\n")
            .take(MAX_COMPLETED_ANSWER_CHARS)
    }

    private fun requiredTerms(
        request: CrisisSentinelRequest,
        fallback: CrisisSentinelResponse
    ): List<String> {
        val normalizedPrompt = CrisisSentinelText.normalize(
            if (request.prompt.isBlank()) request.recentMessages.joinToString(" ") else request.prompt
        )
        val terms = linkedSetOf<String>()

        fun promptHas(vararg signals: String): Boolean {
            return signals.any { normalizedPrompt.contains(CrisisSentinelText.normalize(it)) }
        }

        fun addFallbackTerms(vararg candidates: String) {
            candidates
                .filter { fallback.answer.containsNormalized(it) }
                .forEach { terms += it }
        }

        val dispatchOrOrderPrompt = promptHas(
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
        )
        if (request.mode != CrisisSentinelUserMode.Coordinator && dispatchOrOrderPrompt) {
            addFallbackTerms("konum", "location", "112", "emergency", "yetkili", "authorized")
        }

        if (request.mode == CrisisSentinelUserMode.Coordinator && promptHas(
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
            )
        ) {
            addFallbackTerms("taslak", "onay", "komuta", "draft", "approval", "command")
        }

        if (promptHas("sitrep", "kriz masası", "kriz masasi", "saha notu") ||
            request.mode == CrisisSentinelUserMode.FieldTeam &&
            promptHas("elektrik", "su", "yol", "kapalı", "kapali")
        ) {
            addFallbackTerms("konum", "risk", "ihtiyaç", "ihtiyac", "eksik", "location", "risk", "need", "missing")
        }

        if (promptHas("tahliye", "uyarı", "uyari", "resmi talimat", "çeviri", "ceviri", "translate", "translation")) {
            addFallbackTerms(
                "tehlike",
                "güvenli alan",
                "guvenli alan",
                "belirsiz",
                "mahremiyet",
                "hazard",
                "safe area",
                "uncertain",
                "privacy"
            )
        }

        if (promptHas("çocuk", "cocuk", "çocu", "cocu") &&
            promptHas("enkaz", "travma", "ayrıntılı", "ayrintili", "anlattır", "anlattir")
        ) {
            addFallbackTerms("çocuk", "cocuk", "zorlam", "bakım veren", "bakim veren", "koruma", "child", "caregiver", "protection")
        }

        if (promptHas("kendime zarar", "kendine zarar", "ölmek ist", "olmek ist", "intihar")) {
            addFallbackTerms("yalnız kalma", "yalniz kalma", "112", "güvenilir", "guvenilir", "trusted", "emergency")
        }

        if (promptHas("göğs", "gogus", "nefes alam", "nefes nefese", "kalp")) {
            addFallbackTerms("tanı", "tani", "112", "konum", "diagnosis", "emergency", "location")
        }

        if (promptHas("çok kanama", "cok kanama", "ciddi kanama", "kanama var", "kanayan")) {
            addFallbackTerms("tanı", "tani", "bası", "basi", "112", "ilaç", "ilac", "diagnosis", "pressure", "medication")
        }

        if (promptHas("gaz kokusu", "gaz sızıntı", "gaz sizinti", "kimyasal", "keskin koku")) {
            addFallbackTerms("dokunma", "güvenli", "guvenli", "112", "gaz", "safe", "emergency")
        }

        return terms.toList()
    }

    private fun completionSentences(answer: String, missingTerms: List<String>): List<String> {
        val fragments = answer
            .replace("\n", ". ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim().trimEnd('.') }
            .filter { it.isNotBlank() }
        val selected = linkedSetOf<String>()
        for (term in missingTerms) {
            val fragment = fragments.firstOrNull { it.containsNormalized(term) }
            if (fragment != null) {
                selected += if (fragment.endsWith("!") || fragment.endsWith("?")) fragment else "$fragment."
            }
        }
        return selected.toList()
    }

    private fun String.containsNormalized(term: String): Boolean {
        return CrisisSentinelText.normalize(this).contains(CrisisSentinelText.normalize(term))
    }

    private const val MAX_COMPLETED_ANSWER_CHARS = 1_400
}
