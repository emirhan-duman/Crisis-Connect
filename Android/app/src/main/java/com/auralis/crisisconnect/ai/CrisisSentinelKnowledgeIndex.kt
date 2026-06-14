package com.auralis.crisisconnect.ai

import com.auralis.crisisconnect.screens.Guide.GuideMainScreenViewModel
import java.util.Locale

data class CrisisSentinelKnowledgeArticle(
    val id: String,
    val category: String,
    val title: String,
    val priority: String,
    val in30Seconds: List<String>,
    val stepByStep: List<String>,
    val dontDo: List<String>,
    val checklist: List<String>,
    val sourceNote: String?
) {
    val searchableText: String = buildString {
        append(title).append(' ')
        append(category).append(' ')
        append(priority).append(' ')
        append(in30Seconds.joinToString(" ")).append(' ')
        append(stepByStep.joinToString(" ")).append(' ')
        append(dontDo.joinToString(" ")).append(' ')
        append(checklist.joinToString(" ")).append(' ')
        append(sourceNote.orEmpty())
    }
}

class CrisisSentinelKnowledgeIndex(
    private val articles: List<CrisisSentinelKnowledgeArticle>
) {
    fun search(query: String, limit: Int = 3): List<CrisisSentinelCitation> {
        val queryTokens = CrisisSentinelText.tokens(query)
        if (queryTokens.isEmpty()) return emptyList()

        return articles
            .mapNotNull { article ->
                val score = score(article, queryTokens)
                if (score <= 0) {
                    null
                } else {
                    CrisisSentinelCitation(
                        articleId = article.id,
                        title = article.title,
                        category = article.category,
                        snippet = article.in30Seconds.firstOrNull()
                            ?: article.stepByStep.firstOrNull()
                            ?: article.sourceNote.orEmpty(),
                        score = score
                    )
                }
            }
            .sortedWith(compareByDescending<CrisisSentinelCitation> { it.score }.thenBy { it.articleId })
            .take(limit.coerceAtLeast(1))
    }

    fun articleById(id: String): CrisisSentinelKnowledgeArticle? {
        return articles.firstOrNull { it.id == id }
    }

    private fun score(article: CrisisSentinelKnowledgeArticle, queryTokens: List<String>): Int {
        val title = CrisisSentinelText.normalize(article.title)
        val category = CrisisSentinelText.normalize(article.category)
        val body = CrisisSentinelText.normalize(article.searchableText)

        return queryTokens.sumOf { token ->
            when {
                title.contains(token) -> 8
                category.contains(token) -> 5
                body.contains(token) -> 2
                else -> 0
            }
        }
    }

    companion object {
        fun fromGuide(locale: Locale = Locale.getDefault()): CrisisSentinelKnowledgeIndex {
            val guideArticles = GuideMainScreenViewModel.CATEGORIES.flatMap { category ->
                category.guides.map { article ->
                    CrisisSentinelKnowledgeArticle(
                        id = article.id,
                        category = category.title.resolve(locale),
                        title = article.title.resolve(locale),
                        priority = article.priority.resolve(locale),
                        in30Seconds = article.in30Seconds.map { it.resolve(locale) },
                        stepByStep = article.stepByStep.map { it.resolve(locale) },
                        dontDo = article.dontDo.map { it.resolve(locale) },
                        checklist = article.checklist.map { it.resolve(locale) },
                        sourceNote = article.sourceNote?.resolve(locale)
                    )
                }
            }
            return CrisisSentinelKnowledgeIndex(guideArticles + crisisConnectPlatformArticles(locale))
        }

        private fun crisisConnectPlatformArticles(locale: Locale): List<CrisisSentinelKnowledgeArticle> {
            val tr = CrisisSentinelText.isTurkish(locale)
            fun article(
                id: String,
                titleTr: String,
                titleEn: String,
                secondsTr: List<String>,
                secondsEn: List<String>,
                stepsTr: List<String>,
                stepsEn: List<String>,
                dontTr: List<String>,
                dontEn: List<String>,
                checklistTr: List<String>,
                checklistEn: List<String>
            ): CrisisSentinelKnowledgeArticle {
                return CrisisSentinelKnowledgeArticle(
                    id = id,
                    category = if (tr) "Crisis Connect Platform" else "Crisis Connect Platform",
                    title = if (tr) titleTr else titleEn,
                    priority = if (tr) "Ürün bilgisi" else "Product knowledge",
                    in30Seconds = if (tr) secondsTr else secondsEn,
                    stepByStep = if (tr) stepsTr else stepsEn,
                    dontDo = if (tr) dontTr else dontEn,
                    checklist = if (tr) checklistTr else checklistEn,
                    sourceNote = if (tr) {
                        "Yerel Crisis Connect ürün dokümanı; offline AI ve saha modu cevapları için gömülüdür."
                    } else {
                        "Local Crisis Connect product doctrine embedded for offline AI and field-mode answers."
                    }
                )
            }

            return listOf(
                article(
                    id = "CSE-GAS-001",
                    titleTr = "Gaz Kokusu, Sızıntı ve Kimyasal Risk",
                    titleEn = "Gas Smell, Leak, and Chemical Hazard",
                    secondsTr = listOf(
                        "Gaz kokusu, keskin kimyasal koku veya sızıntı şüphesi varsa içeride kalma; sakin şekilde dışarı çık ve güvenli mesafeye geç.",
                        "Elektrik anahtarı, priz, sigorta, telefon şarjı, çakmak, kibrit veya kıvılcım çıkarabilecek cihaz kullanma.",
                        "Güvenli mesafeden 112'yi, itfaiyeyi veya yerel gaz/tehlikeli madde ekibini ara; bina içine geri dönme."
                    ),
                    secondsEn = listOf(
                        "If you smell gas, detect a sharp chemical odor, or suspect a leak, do not stay inside; leave calmly and move to a safe distance.",
                        "Do not use switches, plugs, breakers, chargers, lighters, matches, or anything that can create sparks.",
                        "From a safe distance, call local emergency services, fire, gas, or hazmat teams; do not re-enter the building."
                    ),
                    stepsTr = listOf(
                        "Kapı ve pencereyi yalnızca güvenliyse aç; bunu yapmak seni geciktiriyorsa doğrudan tahliye et.",
                        "Yakındaki kişileri kısa ve sakin uyar; asansör kullanma, açık alana çık.",
                        "Saha ekibine konum, koku türü, etkilenen kişi sayısı, görünen duman/sızıntı ve bina bilgisi ver."
                    ),
                    stepsEn = listOf(
                        "Open doors or windows only if it is safe and does not delay evacuation.",
                        "Warn nearby people briefly and calmly; avoid elevators and move to open air.",
                        "Report location, odor type, affected people, visible smoke/leak, and building context to responders."
                    ),
                    dontTr = listOf(
                        "Gaz kaynağını aramak, vanayı bulmak için riskli alana geri girmek veya onarım denemek güvenli değildir.",
                        "Maske, ıslak bez veya kısa süre dayanırım düşüncesiyle içeride kalma."
                    ),
                    dontEn = listOf(
                        "Do not go back into the risk area to find the source, locate valves, or attempt repairs.",
                        "Do not stay inside because of a mask, wet cloth, or an assumption that brief exposure is safe."
                    ),
                    checklistTr = listOf("Güvenli tahliye", "Kıvılcım kaynakları yok", "112/gaz/itfaiye bilgilendirildi"),
                    checklistEn = listOf("Safe evacuation", "No spark sources", "Emergency/gas/fire team notified")
                ),
                article(
                    id = "CC-001",
                    titleTr = "Crisis Connect Nedir?",
                    titleEn = "What is Crisis Connect?",
                    secondsTr = listOf(
                        "Crisis Connect internet ve cep şebekesi yokken yakın cihazlar arasında mesaj, sesli not, konum ve acil durum bilgisini paylaşmak için tasarlanmış offline afet iletişim uygulamasıdır.",
                        "Bluetooth Low Energy yakın cihaz keşfi ve düşük bant iletişim için kullanılır; bazı akışlar cihazdan cihaza veya mesh atlamalarıyla genişleyebilir.",
                        "Crisis Connect resmi acil hatların yerine geçmez; hayati riskte 112 ve resmi talimatlar önceliklidir."
                    ),
                    secondsEn = listOf(
                        "Crisis Connect is an offline disaster communication app for sharing messages, voice notes, location, and emergency context between nearby devices when internet or cellular service is unavailable.",
                        "Bluetooth Low Energy is used for nearby discovery and low-bandwidth communication; some flows can extend through device-to-device or mesh hops.",
                        "Crisis Connect does not replace official emergency lines; call local emergency services for life-threatening danger."
                    ),
                    stepsTr = listOf(
                        "Krizden önce güvenilir kişileri QR ile ekle ve izinleri test et.",
                        "Afette kısa metin, konum ve durum bilgisini öncele; büyük medya düşük bağlantıda gecikebilir.",
                        "İnternet geldiğinde backend veya dashboard senkronizasyonu ayrı kanallardan yapılabilir."
                    ),
                    stepsEn = listOf(
                        "Before a crisis, add trusted contacts with QR and test permissions.",
                        "During an incident, prioritize short text, location, and status; large media can be delayed on weak links.",
                        "When internet returns, backend or dashboard sync can run through separate channels."
                    ),
                    dontTr = listOf(
                        "Sınırsız menzil, kesin teslimat veya her cihazda aynı performans sözü verme.",
                        "Uygulamayı 112, AFAD, itfaiye, sağlık veya resmi komuta yerine geçiyor gibi anlatma."
                    ),
                    dontEn = listOf(
                        "Do not promise unlimited range, guaranteed delivery, or identical behavior on every device.",
                        "Do not describe the app as a replacement for emergency services, fire, medical, or official command."
                    ),
                    checklistTr = listOf("Offline amaç açıklandı", "BLE/yakın cihaz sınırı belirtildi", "112/resmi hat sınırı korundu"),
                    checklistEn = listOf("Offline purpose explained", "BLE/nearby-device limit stated", "Emergency-service boundary preserved")
                ),
                article(
                    id = "CC-002",
                    titleTr = "Offline Bağlantı: BLE, GATT, RFCOMM ve Mesh",
                    titleEn = "Offline Connectivity: BLE, GATT, RFCOMM, and Mesh",
                    secondsTr = listOf(
                        "BLE yakın cihazları keşfetmek ve küçük veri parçalarını taşımak için uygundur.",
                        "RFCOMM veya benzeri bağlantılar daha büyük sohbet, dosya, sesli not veya aktarım akışlarında kullanılabilir.",
                        "Mesh her koşulda garanti değildir; cihaz modeli, pil, izinler, beton/metal engeller ve kalabalık ortam performansı etkiler."
                    ),
                    secondsEn = listOf(
                        "BLE is suitable for discovering nearby devices and moving small data chunks.",
                        "RFCOMM or similar links can support larger chat, file, voice-note, or transfer flows.",
                        "Mesh is not guaranteed in every condition; device model, battery, permissions, concrete/metal obstacles, and crowd density affect performance."
                    ),
                    stepsTr = listOf(
                        "Kritik bilgiyi kısa metin ve konum olarak gönder.",
                        "Medya aktarımında parça, makbuz, tekrar deneme ve düşük bant stratejisi kullan.",
                        "Teslim edildi bilgisi yoksa mesajı kesin ulaşmış kabul etme."
                    ),
                    stepsEn = listOf(
                        "Send critical context as short text and location first.",
                        "Use chunking, receipts, retry, and low-bandwidth strategy for media transfer.",
                        "Do not treat a message as delivered without delivery evidence."
                    ),
                    dontTr = listOf("Beton, metal ve enkaz etkisini yok sayma.", "Tek bağlantı yolunu resmi operasyon kanalı gibi kullanma."),
                    dontEn = listOf("Do not ignore concrete, metal, and debris effects.", "Do not treat a single link as the official operation channel."),
                    checklistTr = listOf("Kısa metin öncelendi", "Teslimat durumu ayrıldı", "Medya düşük bant kuralına bağlandı"),
                    checklistEn = listOf("Short text prioritized", "Delivery state separated", "Media tied to low-bandwidth rules")
                ),
                article(
                    id = "CC-003",
                    titleTr = "Herkes İçin Araçlar ve Offline Afet Bilgisi",
                    titleEn = "Public Tools and Offline Disaster Guidance",
                    secondsTr = listOf(
                        "Araçlar bölümü herkesin uygulamayı indirip offline afet bilgisi, ilk yardım hatırlatmaları, hazırlık listeleri ve güvenli davranış önerileri alması için tasarlanır.",
                        "Public mod resmi talimat vermez; kullanıcıya kısa, uygulanabilir ve kaynaklı offline bilgi sunar.",
                        "Crisis Sentinel public modda tıbbi tanı koymaz, bina güvenliği onayı vermez ve kesin konum/kişi sayısı uydurmaz."
                    ),
                    secondsEn = listOf(
                        "The tools area lets any user download the app and access offline disaster guidance, first-aid reminders, preparedness checklists, and safe behavior suggestions.",
                        "Public mode does not issue official instructions; it gives short, practical, sourced offline guidance.",
                        "In public mode, Crisis Sentinel does not diagnose, certify building safety, or invent exact location or victim counts."
                    ),
                    stepsTr = listOf(
                        "Kullanıcı sorusunu offline rehberde ara ve en alakalı makaleye bağla.",
                        "Hayati riskte 112 ve resmi talimatı cevapta açık tut.",
                        "Belirsizlik varsa ek bilgi sor; emin değilmiş gibi davran, uydurma."
                    ),
                    stepsEn = listOf(
                        "Search the offline guide and attach the most relevant article.",
                        "Keep emergency-service and official-instruction boundaries visible for life-threatening risk.",
                        "Ask for missing context when uncertain; do not invent."
                    ),
                    dontTr = listOf("Public kullanıcıya operasyon emri verme.", "Fotoğraftan kesin tanı veya yapı güvenliği kararı verme."),
                    dontEn = listOf("Do not issue operational orders to public users.", "Do not make definitive diagnoses or building-safety decisions from photos."),
                    checklistTr = listOf("Kaynaklı cevap verildi", "Resmi hat sınırı belirtildi", "Eksik bilgi soruldu"),
                    checklistEn = listOf("Sourced answer given", "Emergency-service boundary stated", "Missing context requested")
                ),
                article(
                    id = "CC-004",
                    titleTr = "Saha Ekibi Modu ve Operasyon Taslakları",
                    titleEn = "Field Team Mode and Operation Drafts",
                    secondsTr = listOf(
                        "Saha ekibi modu kısa raporlardan olay türü, öncelik, konum, kişi sayısı, riskler, ihtiyaçlar ve eksik bilgileri çıkarır.",
                        "Çıktı sevk emri değil saha taslağıdır; komuta ve resmi karar yerine geçmez.",
                        "Gaz, kimyasal, elektrik, yangın, çökme ve mahsur kalma gibi riskler ayrı alanlarda tutulmalıdır."
                    ),
                    secondsEn = listOf(
                        "Field team mode extracts incident type, priority, location, victim count, hazards, needs, and missing fields from short reports.",
                        "The output is a field draft, not a dispatch order; it does not replace command or official decisions.",
                        "Gas, chemical, electrical, fire, collapse, and trapped-person hazards must remain explicit."
                    ),
                    stepsTr = listOf(
                        "SITREP taslağında olay türü, öncelik, konum, etkilenen kişi, risk, ihtiyaç ve zaman bilgisini ayır.",
                        "Eksik alanları en fazla üç takip sorusuyla tamamlat.",
                        "Yüksek riskte güvenli mesafe ve ilgili uzman ekibi öncele."
                    ),
                    stepsEn = listOf(
                        "Separate incident type, priority, location, affected people, hazards, needs, and time in the SITREP draft.",
                        "Ask at most three follow-up questions for missing fields.",
                        "For high-risk hazards, prioritize safe distance and specialist response teams."
                    ),
                    dontTr = listOf("Komuta onayı olmadan sevk emri üretme.", "Riskleri tek serbest metne gömüp kaybetme."),
                    dontEn = listOf("Do not generate dispatch orders without command approval.", "Do not bury hazards inside unstructured text."),
                    checklistTr = listOf("SITREP alanları ayrıldı", "Eksikler soruldu", "Komuta sınırı korundu"),
                    checklistEn = listOf("SITREP fields separated", "Missing fields requested", "Command boundary preserved")
                ),
                article(
                    id = "CC-011",
                    titleTr = "Yetkili Koordinasyon Modu",
                    titleEn = "Authorized Coordination Mode",
                    secondsTr = listOf(
                        "Yetkili koordinasyon modu yalnızca doğrulanmış yetkili rol bağlamında görev, kaynak, öncelik ve devir notu taslakları üretir.",
                        "Çıktı sadece taslak nottur; komuta/kurum onayı ve saha teyidi olmadan ekip yönlendirme başlatmaz.",
                        "Eksik konum, kişi sayısı, kaynak miktarı veya tıbbi durumu doğrulanmış gibi yazmaz."
                    ),
                    secondsEn = listOf(
                        "Authorized coordination mode drafts tasking, resources, priorities, and handover notes only in verified authority context.",
                        "Output is a draft note only; it does not authorize deployment without command/agency approval and field confirmation.",
                        "Missing locations, counts, resource amounts, or medical state must not be presented as verified."
                    ),
                    stepsTr = listOf(
                        "Olay, konum, risk, ihtiyaç, kaynak, zaman ve eksik bilgiyi ayrı başlıklarla yaz.",
                        "Görevleri taslak olarak ifade et ve onay gerektirdiğini açık belirt.",
                        "Gaz/kimyasal/sağlık riskinde güvenli mesafe ve uzman ekip eskalasyonunu öncele."
                    ),
                    stepsEn = listOf(
                        "Separate incident, location, risks, needs, resources, time, and missing information.",
                        "Phrase tasks as drafts and state that approval is required.",
                        "For gas, chemical, or medical risk, prioritize safe distance and specialist escalation."
                    ),
                    dontTr = listOf("Çıktıyı onaylanmış karar gibi yazma.", "Belirsiz veriyi doğrulanmış gibi gösterme."),
                    dontEn = listOf("Do not present the output as an approved decision.", "Do not present uncertain data as verified."),
                    checklistTr = listOf("Taslak etiketi var", "Onay sınırı var", "Eksik bilgi ayrıldı"),
                    checklistEn = listOf("Draft label present", "Approval boundary present", "Missing information separated")
                ),
                article(
                    id = "CC-005",
                    titleTr = "Crisis Sentinel Mobil AI Rolü",
                    titleEn = "Crisis Sentinel Mobile AI Role",
                    secondsTr = listOf(
                        "Mobil Crisis Sentinel düşük parametreli, offline çalışmaya uygun ve güvenlik kurallarıyla sınırlandırılmış yardımcıdır.",
                        "RAG, yerel rehber, deterministic olay çıkarımı ve doğrulama katmanı model cevabından önce gelir.",
                        "Model çıktısı bozuk JSON, geçersiz kaynak, uygunsuz tıbbi/operasyon iddiası veya tehlikeli tavsiye içerirse fallback cevap kullanılır."
                    ),
                    secondsEn = listOf(
                        "Mobile Crisis Sentinel is a low-parameter offline assistant constrained by safety rules.",
                        "RAG, local guide data, deterministic incident extraction, and validation come before model output.",
                        "If model output has invalid JSON, bad citations, inappropriate medical/operational claims, or unsafe advice, fallback output is used."
                    ),
                    stepsTr = listOf(
                        "Önce yerel RAG makalelerini getir.",
                        "Modelden JSON şemasına uygun kısa cevap iste.",
                        "Schema, enum, citation ve güvenlik doğrulaması geçmeden cevabı gösterme."
                    ),
                    stepsEn = listOf(
                        "Retrieve local RAG articles first.",
                        "Ask the model for a short response that matches the JSON schema.",
                        "Do not show the model answer until schema, enum, citation, and safety validation pass."
                    ),
                    dontTr = listOf("Ham model metnini doğrudan production'da gösterme.", "Citation uyduran cevabı kabul etme."),
                    dontEn = listOf("Do not show raw model text directly in production.", "Do not accept answers with invented citations."),
                    checklistTr = listOf("RAG kullanıldı", "JSON doğrulandı", "Safety fallback hazır"),
                    checklistEn = listOf("RAG used", "JSON validated", "Safety fallback ready")
                ),
                article(
                    id = "CC-006",
                    titleTr = "Fotoğraf, Dosya, Sesli Not, Dikte ve Sesli Görüşme",
                    titleEn = "Photo, File, Voice Note, Dictation, and Voice Call",
                    secondsTr = listOf(
                        "Crisis Connect bağlantı uygunsa mesaj, konum, fotoğraf, dosya ve sesli not aktarabilir; büyük medya düşük bağlantıda gecikebilir.",
                        "Yakındaki kişiyle sesli görüşme desteklenebilir; bağlantı zayıfsa kısa metin veya sesli not daha güvenilirdir.",
                        "Dikte cihazın işletim sistemi veya app entegrasyonuyla metne çevrilip Crisis Sentinel'e sorulabilir."
                    ),
                    secondsEn = listOf(
                        "When the link allows it, Crisis Connect can transfer messages, location, photos, files, and voice notes; large media may be delayed on weak links.",
                        "Nearby voice calls can be supported; short text or voice notes are more reliable on weak links.",
                        "Dictation can be converted to text by the OS or app integration and then sent to Crisis Sentinel."
                    ),
                    stepsTr = listOf(
                        "Fotoğrafı kanıt değil gözlem desteği olarak ele al: görünür risk, OCR, malzeme türü, konum ipucu ve eksik bilgi ayrılır.",
                        "Kritik olayda medya gelmesini bekleme; kısa metin özeti de gönder.",
                        "Yüz, kimlik, plaka, telefon, adres ve sağlık verisini minimum işle veya maskele."
                    ),
                    stepsEn = listOf(
                        "Treat photos as observation support, not proof: separate visible risk, OCR, material type, location clues, and missing information.",
                        "Do not wait for media before sending a critical text summary.",
                        "Minimize or mask faces, IDs, plates, phone numbers, addresses, and health data."
                    ),
                    dontTr = listOf("Fotoğraftan kesin tanı, kimlik, koordinat veya yapı güvenliği kararı verme.", "Mahrem yaralı/çocuk görüntüsünü gereksiz paylaşma."),
                    dontEn = listOf("Do not infer definitive diagnosis, identity, coordinates, or building safety from a photo.", "Do not unnecessarily share sensitive images of injured people or children."),
                    checklistTr = listOf("Medya türü belirtildi", "Düşük bant alternatifi yazıldı", "Mahremiyet kontrol edildi"),
                    checklistEn = listOf("Media type stated", "Low-bandwidth alternative written", "Privacy checked")
                ),
                article(
                    id = "CC-007",
                    titleTr = "Dashboard ve Yüksek Parametreli AI Ayrımı",
                    titleEn = "Dashboard and High-Parameter AI Split",
                    secondsTr = listOf(
                        "Mobil AI offline ve hızlı karar desteğine odaklanır; dashboard tarafı internet/backend olduğunda daha yüksek parametreli model, çoklu olay özetleme ve operasyon analizi yapabilir.",
                        "Dashboard çıktılarını da resmi komuta yerine koyma; doğrulama, kaynak ve insan onayı gerekir.",
                        "Mobil ile dashboard arasında senkronizasyon olduğunda kaynak, zaman, doğrulama durumu ve cihaz bilgisi korunmalıdır."
                    ),
                    secondsEn = listOf(
                        "Mobile AI focuses on offline quick assistance; the dashboard can use a higher-parameter model for multi-incident summaries and operational analysis when backend access exists.",
                        "Dashboard outputs must not replace official command; verification, sourcing, and human approval are required.",
                        "Sync should preserve source, time, verification state, and device context."
                    ),
                    stepsTr = listOf("Mobilde kısa ve doğrulanabilir cevap üret.", "Dashboard'da çoklu raporları kümele ve belirsizliği işaretle.", "İnsan onayı gerektiren kararları ayrı tut."),
                    stepsEn = listOf("Produce short and verifiable mobile answers.", "Cluster multi-report data on the dashboard and mark uncertainty.", "Separate decisions requiring human approval."),
                    dontTr = listOf("Dashboard analizini kesin gerçek veya resmi emir gibi gösterme.", "Mobil offline cevabı internet gerektiriyor gibi tasarlama."),
                    dontEn = listOf("Do not present dashboard analysis as certain fact or official order.", "Do not design mobile offline answers as internet-dependent."),
                    checklistTr = listOf("Mobil/dashboard rolü ayrıldı", "Senkron veri alanları korundu", "İnsan onayı belirtildi"),
                    checklistEn = listOf("Mobile/dashboard role separated", "Sync fields preserved", "Human approval stated")
                ),
                article(
                    id = "CC-008",
                    titleTr = "Gizlilik, Güvenlik ve Rol Sertifikaları",
                    titleEn = "Privacy, Security, and Role Certificates",
                    secondsTr = listOf(
                        "Crisis Connect local-first çalışmalı; hassas veriler görev amacı, rıza ve minimum veri ilkesiyle işlenmelidir.",
                        "Saha ekibi özellikleri rol sertifikası veya doğrulanmış ekip kimliği ile ayrılmalıdır.",
                        "AI cevapları kimlik, sağlık verisi, fotoğraf ve konum bilgisini gereksiz çoğaltmamalıdır."
                    ),
                    secondsEn = listOf(
                        "Crisis Connect should remain local-first; sensitive data must follow purpose, consent, and data-minimization rules.",
                        "Field-team features should be separated by role certificates or verified responder identity.",
                        "AI answers should not unnecessarily duplicate identity, health, photo, or location data."
                    ),
                    stepsTr = listOf("Yetki gerektiren saha özelliklerini public moddan ayır.", "Hassas görsel ve belgelerde maskeleme/azaltma uygula.", "Kaynak ve erişim durumunu logla."),
                    stepsEn = listOf("Separate authority-sensitive field features from public mode.", "Apply masking/minimization to sensitive images and documents.", "Log source and access state."),
                    dontTr = listOf("Public kullanıcıya ekip yetkisi veriyormuş gibi davranma.", "Gereksiz kimlik veya sağlık verisi üretme."),
                    dontEn = listOf("Do not act as if public users have responder authority.", "Do not generate unnecessary identity or health data."),
                    checklistTr = listOf("Rol sınırı korundu", "Veri minimizasyonu uygulandı", "Mahremiyet uyarısı eklendi"),
                    checklistEn = listOf("Role boundary preserved", "Data minimization applied", "Privacy warning added")
                ),
                article(
                    id = "CC-009",
                    titleTr = "Afet Öncesi Hazırlık ve Uygulama Testi",
                    titleEn = "Preparedness and App Readiness Test",
                    secondsTr = listOf(
                        "Crisis Connect en iyi krizden önce kurulduğunda, izinler verildiğinde ve güvenilir kişiler QR ile eklendiğinde çalışır.",
                        "Pil, Bluetooth, konum izni, bildirimler, offline rehber ve acil kart düzenli kontrol edilmelidir.",
                        "Aile ve ekipler kısa metin, konum, sesli not ve düşük bant senaryosunu tatbikatla denemelidir."
                    ),
                    secondsEn = listOf(
                        "Crisis Connect works best when installed before a crisis, permissions are granted, and trusted contacts are added by QR.",
                        "Battery, Bluetooth, location permission, notifications, offline guide, and emergency card should be checked regularly.",
                        "Families and teams should drill short text, location, voice note, and low-bandwidth scenarios."
                    ),
                    stepsTr = listOf("İzinleri ve pil optimizasyonunu kontrol et.", "QR güvenilir kişi listesini güncelle.", "Offline rehberi ve temel araçları açıp test et."),
                    stepsEn = listOf("Check permissions and battery optimization.", "Update QR trusted-contact list.", "Open and test the offline guide and core tools."),
                    dontTr = listOf("İlk kurulumu afet anına bırakma.", "Tek cihaz veya tek iletişim kanalına güvenme."),
                    dontEn = listOf("Do not leave first setup to the disaster moment.", "Do not rely on one device or one communication channel."),
                    checklistTr = listOf("İzinler hazır", "QR kişiler hazır", "Offline rehber test edildi"),
                    checklistEn = listOf("Permissions ready", "QR contacts ready", "Offline guide tested")
                ),
                article(
                    id = "CC-010",
                    titleTr = "Crisis Connect Sınırları ve Doğru Beklenti",
                    titleEn = "Crisis Connect Limits and Correct Expectations",
                    secondsTr = listOf(
                        "Crisis Connect yedek iletişim ve afet destek aracıdır; resmi acil hat, kurtarma ekibi veya tıbbi hizmetin yerine geçmez.",
                        "Bluetooth menzili çevreye göre değişir; enkaz, beton, metal, kalabalık ve cihaz modeli performansı etkiler.",
                        "AI kesin konum, kişi sayısı, yapı güvenliği, tıbbi tanı veya resmi karar uydurmamalıdır."
                    ),
                    secondsEn = listOf(
                        "Crisis Connect is a backup communication and disaster-support tool; it does not replace emergency lines, rescue teams, or medical service.",
                        "Bluetooth range varies by environment; debris, concrete, metal, crowding, and device model affect performance.",
                        "AI must not invent exact location, person counts, building safety, medical diagnosis, or official decisions."
                    ),
                    stepsTr = listOf("Pil azsa konum ve kısa metin önceliklidir.", "Bağlantı düşükse medya yerine özet gönder.", "Belirsizliği açıkça yaz ve doğrulama iste."),
                    stepsEn = listOf("When battery is low, prioritize location and short text.", "When connectivity is weak, send summaries instead of media.", "State uncertainty clearly and request verification."),
                    dontTr = listOf("Uygulamayı her koşulda çalışacak garanti sistem gibi anlatma.", "AI yanıtı ile resmi talimatı karıştırma."),
                    dontEn = listOf("Do not present the app as guaranteed in every condition.", "Do not confuse AI output with official instructions."),
                    checklistTr = listOf("Resmi hat sınırı belirtildi", "Menzil değişkenliği belirtildi", "AI sınırı korundu"),
                    checklistEn = listOf("Emergency-service boundary stated", "Range variability stated", "AI boundary preserved")
                )
            )
        }
    }
}
