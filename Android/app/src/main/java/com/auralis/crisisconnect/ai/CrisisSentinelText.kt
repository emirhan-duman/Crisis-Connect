package com.auralis.crisisconnect.ai

import java.util.Locale

internal object CrisisSentinelText {
    private val rawStopWords = setOf(
        "acaba", "ama", "and", "bana", "ben", "bir", "bize", "bu", "can", "cok", "da",
        "de", "do", "does", "for", "gibi", "how", "ile", "icin", "için", "is",
        "konum", "mi", "mu", "nasıl", "ne", "nedir", "nokta", "of", "ornek",
        "örnek", "the", "to", "var", "ve", "what", "yardim", "yardım",
        "nasil", "neden", "niye", "nerede", "nerden", "nereye", "kim", "kime",
        "selam", "merhaba", "hello", "hi", "seni", "sana", "bana", "beni",
        "yapmaliyim", "yapmalıyım", "yapabiliriz", "yapabilirim", "kullanabilirim"
    )
    private val stopWords by lazy { rawStopWords.map(::normalize).toSet() }

    fun tokens(text: String): List<String> {
        return normalize(text)
            .split(Regex("[^a-z0-9]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 && it !in stopWords }
            .distinct()
            .toList()
    }

    fun normalize(text: String): String {
        return text
            .trim()
            .lowercase(Locale.ROOT)
            .replace("\u0307", "")
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
    }

    fun isTurkish(locale: Locale): Boolean = locale.language.equals("tr", ignoreCase = true)

    fun responseLocaleFor(
        prompt: String,
        recentMessages: List<String> = emptyList(),
        fallback: Locale = Locale.getDefault()
    ): Locale {
        detectLanguage(prompt)?.let { return Locale.forLanguageTag(it) }
        recentMessages.asReversed().forEach { message ->
            detectLanguage(message)?.let { return Locale.forLanguageTag(it) }
        }
        val fallbackLanguage = fallback.language.takeIf { it.isNotBlank() } ?: "en"
        return Locale.forLanguageTag(fallbackLanguage)
    }

    fun responseLanguageName(locale: Locale): String {
        return when (locale.language.lowercase(Locale.ROOT)) {
            "tr" -> "Turkish"
            "de" -> "German"
            "es" -> "Spanish"
            "fr" -> "French"
            "ar" -> "Arabic"
            else -> "English"
        }
    }

    private fun detectLanguage(text: String): String? {
        val raw = text.trim()
        if (raw.isBlank()) return null
        val normalized = normalize(raw)
        if (normalized in setOf("hi", "hello", "hey", "good morning", "good evening", "good night")) return "en"
        if (normalized in setOf("selam", "selamlar", "slm", "merhaba", "meraba", "mrb", "sa", "naber", "nasilsin")) return "tr"
        if (normalized in setOf("hallo", "moin", "guten tag", "guten morgen", "guten abend")) return "de"
        if (raw.any { it in "çğıİöşüÇĞÖŞÜ" }) return "tr"

        val tokens = tokensIncludingShort(normalized)
        if (tokens.isEmpty()) return null
        // ASCII-fied Turkish ("nasilsin", "yardim edebilir misin") carries no diacritics, so the
        // stopword list alone misses it; characteristic verb/possessive suffixes close that gap.
        val turkishSuffixHits = tokens.count { token ->
            token.length >= 5 &&
                token !in englishLanguageSignals &&
                turkishStrongSuffixes.any { suffix -> token.endsWith(suffix) }
        }
        val turkishScore = tokens.count { it in turkishLanguageSignals } + turkishSuffixHits
        val englishScore = tokens.count { it in englishLanguageSignals }
        val germanScore = tokens.count { it in germanLanguageSignals }
        val best = listOf("tr" to turkishScore, "en" to englishScore, "de" to germanScore)
            .maxByOrNull { it.second }
            ?: return null
        return if (best.second >= 1 && best.second > 0 && listOf(turkishScore, englishScore, germanScore).count { it == best.second } == 1) {
            best.first
        } else {
            null
        }
    }

    private fun tokensIncludingShort(normalized: String): List<String> {
        return normalized
            .split(Regex("[^a-z0-9]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
    }

    private val turkishLanguageSignals = setOf(
        "bir", "bana", "beni", "benim", "sana", "seni", "nasil", "nedir", "ne", "mi", "mu",
        "icin", "yazar", "yaz", "yardim", "eder", "misin", "konusabiliriz", "turkce",
        "ve", "bu", "ben", "sen", "biz", "evet", "hayir", "tamam", "tmm", "lutfen",
        "tesekkur", "tesekkurler", "sagol", "deprem", "yangin", "sel", "enkaz", "nerede",
        "nereye", "neden", "niye", "hangi", "kac", "var", "yok", "cok", "daha", "ama",
        "fakat", "cunku", "kadar", "sonra", "once", "simdi", "bugun", "yarin", "peki",
        "yani", "devam", "anlat", "ozetle", "kisaca", "soyle", "olur", "olmaz", "lazim",
        "gerek", "istiyorum", "yapmaliyim", "yapmam", "edebilir", "yapabilir"
    )
    // Verb/question/possessive endings that are distinctive for Turkish even without diacritics.
    // Short generic endings (-yor, -dim, -tum…) are excluded: they collide with English words
    // like "mayor", "victim", "momentum".
    private val turkishStrongSuffixes = setOf(
        "iyor", "uyor", "yorum", "yorsun", "yoruz", "yorlar",
        "mak", "mek", "misin", "musun", "miyim", "muyum", "miyiz", "muyuz",
        "siniz", "sunuz", "acak", "ecek", "meli", "abilir", "ebilir",
        "lari", "leri", "larin", "lerin", "imiz", "umuz", "iniz", "unuz"
    )
    private val englishLanguageSignals = setOf(
        "what", "whats", "how", "why", "can", "could", "would", "should", "is", "are", "the",
        "a", "an", "write", "explain", "help", "me", "you", "your", "english"
    )
    private val germanLanguageSignals = setOf(
        "was", "wie", "warum", "kann", "kannst", "ist", "sind", "der", "die", "das",
        "schreib", "erklar", "hilfe", "mir", "du", "deutsch"
    )
}
