package com.auralis.crisisconnect.ai

internal object CrisisSentinelIncidentExtractor {
    fun extract(text: String): CrisisSentinelIncidentDraft? {
        val normalized = CrisisSentinelText.normalize(text)
        val type = detectType(normalized)
        val casualtyCount = findCount(normalized, casualtyWords)
        val trappedCount = findCount(normalized, trappedWords)
        val hazards = detectMatches(normalized, hazardKeywords)
        val needs = enrichHazardNeeds(hazards, detectMatches(normalized, needKeywords))
        val locationText = detectLocation(text)

        val hasIncidentSignal = type != CrisisSentinelIncidentType.Unknown ||
            casualtyCount != null ||
            trappedCount != null ||
            hazards.isNotEmpty() ||
            needs.isNotEmpty()

        if (!hasIncidentSignal) return null

        val missingFields = buildList {
            if (locationText.isNullOrBlank()) add("konum/adres")
            if (casualtyCount == null && trappedCount == null) add("etkilenen kişi sayısı")
            if (hazards.isEmpty()) add("ek riskler")
            if (needs.isEmpty()) add("ihtiyaç duyulan destek")
        }

        val priority = when {
            trappedCount != null && trappedCount > 0 -> CrisisSentinelPriority.Immediate
            hazards.any { it in immediateHazards } -> CrisisSentinelPriority.Immediate
            normalized.contains("nefes almiyor") || normalized.contains("bilincsiz") -> CrisisSentinelPriority.Immediate
            casualtyCount != null && casualtyCount > 0 -> CrisisSentinelPriority.High
            type != CrisisSentinelIncidentType.Unknown -> CrisisSentinelPriority.High
            else -> CrisisSentinelPriority.Routine
        }

        val signalCount = listOf(
            type != CrisisSentinelIncidentType.Unknown,
            casualtyCount != null,
            trappedCount != null,
            hazards.isNotEmpty(),
            needs.isNotEmpty(),
            !locationText.isNullOrBlank()
        ).count { it }
        val confidence = (0.2f + signalCount * 0.12f).coerceAtMost(0.92f)

        return CrisisSentinelIncidentDraft(
            type = type,
            priority = priority,
            casualtyCount = casualtyCount,
            trappedCount = trappedCount,
            locationText = locationText,
            hazards = hazards,
            needs = needs,
            missingFields = missingFields,
            confidence = confidence
        )
    }

    private fun detectType(normalized: String): CrisisSentinelIncidentType {
        val scores = mapOf(
            CrisisSentinelIncidentType.Trapped to listOf("mahsur", "enkaz alt", "sikisti", "trapped"),
            CrisisSentinelIncidentType.Fire to listOf("yangin", "duman", "alev", "fire", "smoke"),
            CrisisSentinelIncidentType.Flood to listOf("sel", "su baskini", "flood", "taskin"),
            CrisisSentinelIncidentType.Hazard to listOf("gaz", "dogalgaz", "kimyasal", "sizinti", "patlama", "kivilcim", "yakit", "tehlikeli madde", "hazard", "hazmat"),
            CrisisSentinelIncidentType.Earthquake to listOf("deprem", "artci", "enkaz", "coktu", "collapse", "quake"),
            CrisisSentinelIncidentType.Medical to listOf("yarali", "kanama", "bilinc", "nefes", "ambulans", "medical", "injury", "gogus agrisi", "inme", "astim"),
            CrisisSentinelIncidentType.Communication to listOf("sinyal", "iletisim", "telsiz", "radio", "communication"),
            CrisisSentinelIncidentType.Logistics to listOf(
                "lojistik",
                "kaynak ihtiyaci",
                "kaynak talebi",
                "kaynak taslagi",
                "stok",
                "dagitim",
                "bagis",
                "ikmal"
            ),
            CrisisSentinelIncidentType.Shelter to listOf("barinma", "cadir", "toplanma alani", "shelter"),
            CrisisSentinelIncidentType.PublicHealth to listOf("wash", "hijyen", "sanitasyon", "bulasici", "salgin", "enfeksiyon"),
            CrisisSentinelIncidentType.Translation to listOf("ceviri", "tercuman", "dil erisimi", "language access", "translation"),
            CrisisSentinelIncidentType.Strategy to listOf("strateji", "komuta", "eoc", "karar logu", "risk matrisi", "oncelik"),
            CrisisSentinelIncidentType.Evacuation to listOf("tahliye", "evacuation", "guzergah", "toplanma"),
            CrisisSentinelIncidentType.MentalHealth to listOf("psikolojik", "psikososyal", "pfa", "panik", "kaygi", "yas", "intihar", "kendine zarar", "tukenme", "mhpss")
        ).mapValues { (_, keywords) -> keywords.count { containsKeyword(normalized, it) } }

        return scores.maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.key
            ?: CrisisSentinelIncidentType.Unknown
    }

    private fun findCount(normalized: String, words: List<String>): Int? {
        val wordPattern = words.joinToString("|") { Regex.escape(it) }
        val direct = Regex("""\b(\d{1,4})\s*(?:$wordPattern)\b""")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (direct != null) return direct

        return numberWords.firstNotNullOfOrNull { (word, value) ->
            if (words.any { normalized.contains("$word $it") || normalized.contains("$it $word") }) value else null
        }
    }

    private fun detectMatches(normalized: String, dictionary: Map<String, List<String>>): List<String> {
        return dictionary.mapNotNull { (label, keywords) ->
            label.takeIf { keywords.any { keyword -> containsKeyword(normalized, keyword) } }
        }
    }

    private fun containsKeyword(normalized: String, keyword: String): Boolean {
        val normalizedKeyword = CrisisSentinelText.normalize(keyword)
        if (normalizedKeyword.isBlank()) return false
        if (normalizedKeyword.any { !it.isLetterOrDigit() }) {
            return normalized.contains(normalizedKeyword)
        }
        return Regex("""(?:^|[^a-z0-9])${Regex.escape(normalizedKeyword)}(?:$|[^a-z0-9])""")
            .containsMatchIn(normalized)
    }

    private fun enrichHazardNeeds(hazards: List<String>, detectedNeeds: List<String>): List<String> {
        return buildList {
            addAll(detectedNeeds)
            if ("gaz kokusu" in hazards) {
                add("itfaiye/gaz ekibi")
                add("güvenli tahliye")
            }
            if ("kimyasal/sızıntı riski" in hazards) {
                add("tehlikeli madde desteği")
                add("alan güvenliği")
            }
        }.distinct()
    }

    private fun detectLocation(text: String): String? {
        val coordinate = Regex("""[-+]?\d{1,2}\.\d{3,}\s*,\s*[-+]?\d{1,3}\.\d{3,}""")
            .find(text)
            ?.value
        if (coordinate != null) return coordinate

        val explicit = Regex("""(?i)\b(konum|adres|lokasyon|location)\s*[:\-]\s*([^.;\n]+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
        if (!explicit.isNullOrBlank()) return explicit.take(120)

        val sentence = text
            .split(Regex("[.\n;]"))
            .firstOrNull { raw ->
                val normalized = CrisisSentinelText.normalize(raw)
                locationHints.any { normalized.contains(it) }
            }
            ?.trim()
        return sentence?.takeIf { it.length >= 4 }?.take(120)
    }

    private val casualtyWords = listOf("yarali", "kazazede", "etkilenen", "injured", "victim", "kisi")
    private val trappedWords = listOf("mahsur", "trapped", "enkaz altinda", "sikismis")
    private val locationHints = listOf("mahalle", "sokak", "cadde", "bulvar", "apartman", "okul", "cami", "metro", "bina", "blok")

    private val hazardKeywords = mapOf(
        "gaz kokusu" to listOf("gaz kokusu", "dogalgaz", "gas smell", "gas leak"),
        "kimyasal/sızıntı riski" to listOf("kimyasal", "tehlikeli madde", "sizinti", "keskin koku", "hazmat", "yakit", "bidon"),
        "duman/yangın" to listOf("duman", "yangin", "alev", "smoke", "fire"),
        "çökme riski" to listOf("cokme riski", "cokebilir", "enkaz", "collapse"),
        "elektrik riski" to listOf("elektrik hatti", "elektrik kablo", "kopuk kablo", "akim", "electric line", "power line"),
        "su baskını" to listOf("sel", "su baskini", "taskin", "flood"),
        "halk sağlığı riski" to listOf("bulasici", "salgin", "enfeksiyon", "kirli su", "hijyen"),
        "koruma/mahremiyet riski" to listOf("mahremiyet", "koruma", "cocuk", "engelli", "yasli"),
        "kendine zarar riski" to listOf("kendine zarar", "intihar", "olmek istiyorum", "suicide"),
        "akut stres" to listOf("panik", "kaygi", "aglama", "titreme", "yas", "travma")
    )

    private val immediateHazards = setOf(
        "gaz kokusu",
        "kimyasal/sızıntı riski",
        "duman/yangın",
        "çökme riski",
        "halk sağlığı riski",
        "kendine zarar riski"
    )

    private val needKeywords = mapOf(
        "arama kurtarma" to listOf("arama kurtarma", "enkaz", "mahsur", "rescue"),
        "ambulans/sağlık" to listOf("ambulans", "saglik", "yarali", "kanama", "medical"),
        "tahliye" to listOf("tahliye", "evacuate", "evacuation"),
        "su/gıda" to listOf("icme suyu", "temiz su", "su az", "su ihtiyaci", "gida", "yiyecek", "water supply", "food"),
        "iletişim desteği" to listOf("sinyal", "iletisim", "telsiz", "communication"),
        "lojistik/kaynak" to listOf("lojistik", "kaynak ihtiyaci", "kaynak talebi", "kaynak taslagi", "stok", "ikmal", "dagitim"),
        "barınma" to listOf("barinma", "cadir", "shelter"),
        "WASH/hijyen" to listOf("wash", "hijyen", "sanitasyon", "tuvalet"),
        "dil/tercüman desteği" to listOf("ceviri", "tercuman", "dil erisimi"),
        "komuta/koordinasyon" to listOf("komuta", "eoc", "strateji", "koordinasyon"),
        "psikososyal destek" to listOf("psikolojik", "psikososyal", "pfa", "mhpss", "panik", "kaygi", "yas")
    )

    private val numberWords = mapOf(
        "bir" to 1,
        "iki" to 2,
        "uc" to 3,
        "dort" to 4,
        "bes" to 5,
        "alti" to 6,
        "yedi" to 7,
        "sekiz" to 8,
        "dokuz" to 9,
        "on" to 10
    )
}
