//
//  CrisisSentinelText.swift
//  Crisis Connect
//

import Foundation

enum CrisisSentinelText {
    private static let stopWords: Set<String> = [
        "acaba", "ama", "and", "bana", "ben", "bir", "bize", "bu", "can", "cok", "da",
        "de", "do", "does", "for", "gibi", "how", "ile", "icin", "için", "is",
        "konum", "mi", "mu", "nasil", "nasıl", "ne", "nedir", "nokta", "of",
        "ornek", "örnek", "the", "to", "var", "ve", "what", "yardim", "yardım"
    ]

    static func tokens(from text: String) -> [String] {
        let normalizedText = normalized(text)
        let parts = normalizedText.components(separatedBy: CharacterSet.alphanumerics.inverted)
        var seen = Set<String>()
        return parts.compactMap { raw in
            let token = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard token.count >= 3, stopWords.contains(token) == false, seen.insert(token).inserted else {
                return nil
            }
            return token
        }
    }

    static func normalized(_ text: String) -> String {
        text
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .replacingOccurrences(of: "\u{0307}", with: "")
            .replacingOccurrences(of: "ı", with: "i")
            .replacingOccurrences(of: "ğ", with: "g")
            .replacingOccurrences(of: "ü", with: "u")
            .replacingOccurrences(of: "ş", with: "s")
            .replacingOccurrences(of: "ö", with: "o")
            .replacingOccurrences(of: "ç", with: "c")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: Locale(identifier: "tr_TR"))
    }

    static func isTurkish(_ locale: Locale) -> Bool {
        let code = locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
        return code.hasPrefix("tr")
    }

    static func responseLocale(
        for prompt: String,
        recentMessages: [String] = [],
        fallback: Locale = .current
    ) -> Locale {
        if let language = detectLanguage(prompt) {
            return Locale(identifier: language)
        }
        for message in recentMessages.reversed() {
            if let language = detectLanguage(message) {
                return Locale(identifier: language)
            }
        }
        let fallbackCode = fallback.language.languageCode?.identifier ?? fallback.identifier
        return Locale(identifier: fallbackCode.isEmpty ? "en" : fallbackCode)
    }

    static func responseLanguageName(_ locale: Locale) -> String {
        let code = locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
        if code.hasPrefix("tr") { return "Turkish" }
        if code.hasPrefix("de") { return "German" }
        if code.hasPrefix("es") { return "Spanish" }
        if code.hasPrefix("fr") { return "French" }
        if code.hasPrefix("ar") { return "Arabic" }
        return "English"
    }

    private static func detectLanguage(_ text: String) -> String? {
        let raw = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard raw.isEmpty == false else { return nil }
        let normalizedText = normalized(raw)
        if ["hi", "hello", "hey", "good morning", "good evening", "good night"].contains(normalizedText) {
            return "en"
        }
        if ["selam", "selamlar", "slm", "merhaba", "meraba", "mrb", "sa", "naber", "nasilsin"].contains(normalizedText) {
            return "tr"
        }
        if ["hallo", "moin", "guten tag", "guten morgen", "guten abend"].contains(normalizedText) {
            return "de"
        }
        if raw.range(of: #"[çğıİöşüÇĞÖŞÜ]"#, options: .regularExpression) != nil {
            return "tr"
        }

        let words = normalizedText
            .components(separatedBy: CharacterSet.alphanumerics.inverted)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { $0.count >= 2 }
        guard words.isEmpty == false else { return nil }
        // ASCII-fied Turkish ("nasilsin", "yardim edebilir misin") carries no diacritics, so the
        // stopword list alone misses it; characteristic verb/possessive suffixes close that gap.
        let turkishSuffixHits = words.filter { word in
            word.count >= 5 &&
                englishLanguageSignals.contains(word) == false &&
                turkishStrongSuffixes.contains(where: { word.hasSuffix($0) })
        }.count
        let turkishScore = words.filter { turkishLanguageSignals.contains($0) }.count + turkishSuffixHits
        let englishScore = words.filter { englishLanguageSignals.contains($0) }.count
        let germanScore = words.filter { germanLanguageSignals.contains($0) }.count
        let scores = [("tr", turkishScore), ("en", englishScore), ("de", germanScore)]
        guard let best = scores.max(by: { $0.1 < $1.1 }), best.1 > 0 else { return nil }
        return scores.filter { $0.1 == best.1 }.count == 1 ? best.0 : nil
    }

    private static let turkishLanguageSignals: Set<String> = [
        "bir", "bana", "beni", "benim", "sana", "seni", "nasil", "nedir", "ne", "mi", "mu",
        "icin", "yazar", "yaz", "yardim", "eder", "misin", "konusabiliriz", "turkce",
        "ve", "bu", "ben", "sen", "biz", "evet", "hayir", "tamam", "tmm", "lutfen",
        "tesekkur", "tesekkurler", "sagol", "deprem", "yangin", "sel", "enkaz", "nerede",
        "nereye", "neden", "niye", "hangi", "kac", "var", "yok", "cok", "daha", "ama",
        "fakat", "cunku", "kadar", "sonra", "once", "simdi", "bugun", "yarin", "peki",
        "yani", "devam", "anlat", "ozetle", "kisaca", "soyle", "olur", "olmaz", "lazim",
        "gerek", "istiyorum", "yapmaliyim", "yapmam", "edebilir", "yapabilir"
    ]
    // Verb/question/possessive endings that are distinctive for Turkish even without diacritics.
    // Short generic endings (-yor, -dim, -tum…) are excluded: they collide with English words
    // like "mayor", "victim", "momentum".
    private static let turkishStrongSuffixes: Set<String> = [
        "iyor", "uyor", "yorum", "yorsun", "yoruz", "yorlar",
        "mak", "mek", "misin", "musun", "miyim", "muyum", "miyiz", "muyuz",
        "siniz", "sunuz", "acak", "ecek", "meli", "abilir", "ebilir",
        "lari", "leri", "larin", "lerin", "imiz", "umuz", "iniz", "unuz"
    ]
    private static let englishLanguageSignals: Set<String> = [
        "what", "whats", "how", "why", "can", "could", "would", "should", "is", "are", "the",
        "a", "an", "write", "explain", "help", "me", "you", "your", "english"
    ]
    private static let germanLanguageSignals: Set<String> = [
        "was", "wie", "warum", "kann", "kannst", "ist", "sind", "der", "die", "das",
        "schreib", "erklar", "hilfe", "mir", "du", "deutsch"
    ]
}
