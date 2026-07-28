//
//  CrisisSentinelIncidentExtractor.swift
//  Crisis Connect
//

import Foundation

enum CrisisSentinelIncidentExtractor {
    static func extract(from text: String) -> CrisisSentinelIncidentDraft? {
        let normalized = CrisisSentinelText.normalized(text)
        let type = detectType(normalized)
        let casualtyCount = findCount(normalized, words: casualtyWords)
        let trappedCount = findCount(normalized, words: trappedWords)
        let hazards = detectMatches(normalized, dictionary: hazardKeywords)
        let needs = enrichHazardNeeds(hazards: hazards, detectedNeeds: detectMatches(normalized, dictionary: needKeywords))
        let locationText = detectLocation(text)

        let hasIncidentSignal = type != .unknown ||
            casualtyCount != nil ||
            trappedCount != nil ||
            hazards.isEmpty == false ||
            needs.isEmpty == false

        guard hasIncidentSignal else { return nil }

        var missingFields: [String] = []
        if locationText?.isEmpty ?? true { missingFields.append("konum/adres") }
        if casualtyCount == nil && trappedCount == nil { missingFields.append("etkilenen kişi sayısı") }
        if hazards.isEmpty { missingFields.append("ek riskler") }
        if needs.isEmpty { missingFields.append("ihtiyaç duyulan destek") }

        let priority: CrisisSentinelPriority
        if let trappedCount, trappedCount > 0 {
            priority = .immediate
        } else if hazards.contains(where: { immediateHazards.contains($0) }) {
            priority = .immediate
        } else if normalized.contains("nefes almiyor") || normalized.contains("bilincsiz") {
            priority = .immediate
        } else if let casualtyCount, casualtyCount > 0 {
            priority = .high
        } else if type != .unknown {
            priority = .high
        } else {
            priority = .routine
        }

        let signalCount = [
            type != .unknown,
            casualtyCount != nil,
            trappedCount != nil,
            hazards.isEmpty == false,
            needs.isEmpty == false,
            locationText?.isEmpty == false
        ].filter { $0 }.count
        let confidence = min(0.2 + Float(signalCount) * 0.12, 0.92)

        return CrisisSentinelIncidentDraft(
            type: type,
            priority: priority,
            casualtyCount: casualtyCount,
            trappedCount: trappedCount,
            locationText: locationText,
            hazards: hazards,
            needs: needs,
            missingFields: missingFields,
            confidence: confidence
        )
    }

    private static func detectType(_ normalized: String) -> CrisisSentinelIncidentType {
        let dictionaries: [(CrisisSentinelIncidentType, [String])] = [
            (.trapped, ["mahsur", "enkaz alt", "sikisti", "trapped"]),
            (.fire, ["yangin", "duman", "alev", "fire", "smoke"]),
            (.flood, ["sel", "su baskini", "flood", "taskin"]),
            (.hazard, ["gaz", "dogalgaz", "kimyasal", "sizinti", "patlama", "kivilcim", "yakit", "tehlikeli madde", "hazard", "hazmat"]),
            (.earthquake, ["deprem", "artci", "enkaz", "coktu", "collapse", "quake"]),
            (.medical, ["yarali", "kanama", "bilinc", "nefes", "ambulans", "medical", "injury", "gogus agrisi", "inme", "astim"]),
            (.communication, ["sinyal", "iletisim", "telsiz", "radio", "communication"]),
            (.logistics, [
                "lojistik",
                "kaynak ihtiyaci",
                "kaynak talebi",
                "kaynak taslagi",
                "stok",
                "dagitim",
                "bagis",
                "ikmal"
            ]),
            (.shelter, ["barinma", "cadir", "toplanma alani", "shelter"]),
            (.publicHealth, ["wash", "hijyen", "sanitasyon", "bulasici", "salgin", "enfeksiyon"]),
            (.translation, ["ceviri", "tercuman", "dil erisimi", "language access", "translation"]),
            (.strategy, ["strateji", "komuta", "eoc", "karar logu", "risk matrisi", "oncelik"]),
            (.evacuation, ["tahliye", "evacuation", "guzergah", "toplanma"]),
            (.mentalHealth, ["psikolojik", "psikososyal", "pfa", "panik", "kaygi", "yas", "intihar", "kendine zarar", "tukenme", "mhpss"])
        ]

        return dictionaries
            .map { type, keywords in
                (type, keywords.filter { containsKeyword(normalized, keyword: $0) }.count)
            }
            .max { $0.1 < $1.1 }
            .flatMap { $0.1 > 0 ? $0.0 : nil } ?? .unknown
    }

    private static func findCount(_ normalized: String, words: [String]) -> Int? {
        let pattern = #"\b(\d{1,4})\s*(?:"# + words.map { NSRegularExpression.escapedPattern(for: $0) }.joined(separator: "|") + #")\b"#
        if let match = normalized.firstCapturedGroup(pattern: pattern, group: 1), let count = Int(match) {
            return count
        }

        for (word, value) in numberWords {
            if words.contains(where: { normalized.contains("\(word) \($0)") || normalized.contains("\($0) \(word)") }) {
                return value
            }
        }
        return nil
    }

    private static func detectMatches(_ normalized: String, dictionary: [String: [String]]) -> [String] {
        dictionary.compactMap { label, keywords in
            keywords.contains { containsKeyword(normalized, keyword: $0) } ? label : nil
        }
        .sorted()
    }

    private static func containsKeyword(_ normalized: String, keyword: String) -> Bool {
        let normalizedKeyword = CrisisSentinelText.normalized(keyword)
        guard normalizedKeyword.isEmpty == false else { return false }

        if normalizedKeyword.rangeOfCharacter(from: CharacterSet.alphanumerics.inverted) != nil {
            return normalized.contains(normalizedKeyword)
        }

        let escaped = NSRegularExpression.escapedPattern(for: normalizedKeyword)
        let pattern = #"(^|[^a-z0-9])"# + escaped + #"($|[^a-z0-9])"#
        return normalized.firstMatch(pattern: pattern) != nil
    }

    private static func enrichHazardNeeds(hazards: [String], detectedNeeds: [String]) -> [String] {
        var needs = detectedNeeds
        if hazards.contains("gaz kokusu") {
            needs.append("itfaiye/gaz ekibi")
            needs.append("güvenli tahliye")
        }
        if hazards.contains("kimyasal/sızıntı riski") {
            needs.append("tehlikeli madde desteği")
            needs.append("alan güvenliği")
        }
        return Array(NSOrderedSet(array: needs)) as? [String] ?? needs
    }

    private static func detectLocation(_ text: String) -> String? {
        if let coordinate = text.firstMatch(pattern: #"[-+]?\d{1,2}\.\d{3,}\s*,\s*[-+]?\d{1,3}\.\d{3,}"#) {
            return coordinate
        }

        if let explicit = text.firstCapturedGroup(pattern: #"(?i)\b(konum|adres|lokasyon|location)\s*[:\-]\s*([^.;\n]+)"#, group: 2) {
            return String(explicit.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
        }

        return text
            .components(separatedBy: CharacterSet(charactersIn: ".;\n"))
            .first { raw in
                let normalized = CrisisSentinelText.normalized(raw)
                return locationHints.contains { normalized.contains($0) }
            }
            .map { String($0.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120)) }
            .flatMap { $0.count >= 4 ? $0 : nil }
    }

    private static let casualtyWords = ["yarali", "kazazede", "etkilenen", "injured", "victim", "kisi"]
    private static let trappedWords = ["mahsur", "trapped", "enkaz altinda", "sikismis"]
    private static let locationHints = ["mahalle", "sokak", "cadde", "bulvar", "apartman", "okul", "cami", "metro", "bina", "blok"]

    private static let hazardKeywords: [String: [String]] = [
        "gaz kokusu": ["gaz kokusu", "dogalgaz", "gas smell", "gas leak"],
        "kimyasal/sızıntı riski": ["kimyasal", "tehlikeli madde", "sizinti", "keskin koku", "hazmat", "yakit", "bidon"],
        "duman/yangın": ["duman", "yangin", "alev", "smoke", "fire"],
        "çökme riski": ["cokme riski", "cokebilir", "enkaz", "collapse"],
        "elektrik riski": ["elektrik hatti", "elektrik kablo", "kopuk kablo", "akim", "electric line", "power line"],
        "su baskını": ["sel", "su baskini", "taskin", "flood"],
        "halk sağlığı riski": ["bulasici", "salgin", "enfeksiyon", "kirli su", "hijyen"],
        "koruma/mahremiyet riski": ["mahremiyet", "koruma", "cocuk", "engelli", "yasli"],
        "kendine zarar riski": ["kendine zarar", "intihar", "olmek istiyorum", "suicide"],
        "akut stres": ["panik", "kaygi", "aglama", "titreme", "yas", "travma"]
    ]

    private static let immediateHazards: Set<String> = [
        "gaz kokusu",
        "kimyasal/sızıntı riski",
        "duman/yangın",
        "çökme riski",
        "halk sağlığı riski",
        "kendine zarar riski"
    ]

    private static let needKeywords: [String: [String]] = [
        "arama kurtarma": ["arama kurtarma", "enkaz", "mahsur", "rescue"],
        "ambulans/sağlık": ["ambulans", "saglik", "yarali", "kanama", "medical"],
        "tahliye": ["tahliye", "evacuate", "evacuation"],
        "su/gıda": ["icme suyu", "temiz su", "su az", "su ihtiyaci", "gida", "yiyecek", "water supply", "food"],
        "iletişim desteği": ["sinyal", "iletisim", "telsiz", "communication"],
        "lojistik/kaynak": ["lojistik", "kaynak ihtiyaci", "kaynak talebi", "kaynak taslagi", "stok", "ikmal", "dagitim"],
        "barınma": ["barinma", "cadir", "shelter"],
        "WASH/hijyen": ["wash", "hijyen", "sanitasyon", "tuvalet"],
        "dil/tercüman desteği": ["ceviri", "tercuman", "dil erisimi"],
        "komuta/koordinasyon": ["komuta", "eoc", "strateji", "koordinasyon"],
        "psikososyal destek": ["psikolojik", "psikososyal", "pfa", "mhpss", "panik", "kaygi", "yas"]
    ]

    private static let numberWords: [String: Int] = [
        "bir": 1,
        "iki": 2,
        "uc": 3,
        "dort": 4,
        "bes": 5,
        "alti": 6,
        "yedi": 7,
        "sekiz": 8,
        "dokuz": 9,
        "on": 10
    ]
}

private extension String {
    func firstMatch(pattern: String) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(startIndex..., in: self)
        guard let match = regex.firstMatch(in: self, range: range),
              let swiftRange = Range(match.range(at: 0), in: self) else {
            return nil
        }
        return String(self[swiftRange])
    }

    func firstCapturedGroup(pattern: String, group: Int) -> String? {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
        let range = NSRange(startIndex..., in: self)
        guard let match = regex.firstMatch(in: self, range: range),
              match.numberOfRanges > group,
              let swiftRange = Range(match.range(at: group), in: self) else {
            return nil
        }
        return String(self[swiftRange])
    }
}
