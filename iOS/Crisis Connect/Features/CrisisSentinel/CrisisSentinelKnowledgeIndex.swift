//
//  CrisisSentinelKnowledgeIndex.swift
//  Crisis Connect
//

import Foundation

struct CrisisSentinelKnowledgeArticle: Equatable {
    let id: String
    let category: String
    let title: String
    let priority: String
    let in30Seconds: [String]
    let stepByStep: [String]
    let dontDo: [String]
    let checklist: [String]
    let sourceNote: String?

    var searchableText: String {
        [
            title,
            category,
            priority,
            in30Seconds.joined(separator: " "),
            stepByStep.joined(separator: " "),
            dontDo.joined(separator: " "),
            checklist.joined(separator: " "),
            sourceNote ?? ""
        ].joined(separator: " ")
    }
}

struct CrisisSentinelKnowledgeIndex {
    private let articles: [CrisisSentinelKnowledgeArticle]

    init(articles: [CrisisSentinelKnowledgeArticle]) {
        self.articles = articles
    }

    func search(query: String, limit: Int = 3) -> [CrisisSentinelCitation] {
        let queryTokens = CrisisSentinelText.tokens(from: query)
        guard queryTokens.isEmpty == false else { return [] }

        return articles.compactMap { article in
            let score = score(article: article, queryTokens: queryTokens)
            guard score > 0 else { return nil }
            return CrisisSentinelCitation(
                articleId: article.id,
                title: article.title,
                category: article.category,
                snippet: article.in30Seconds.first ?? article.stepByStep.first ?? article.sourceNote ?? "",
                score: score
            )
        }
        .sorted {
            if $0.score == $1.score {
                return $0.articleId < $1.articleId
            }
            return $0.score > $1.score
        }
        .prefix(max(limit, 1))
        .map { $0 }
    }

    func article(id: String) -> CrisisSentinelKnowledgeArticle? {
        articles.first { $0.id == id }
    }

    private func score(article: CrisisSentinelKnowledgeArticle, queryTokens: [String]) -> Int {
        let title = CrisisSentinelText.normalized(article.title)
        let category = CrisisSentinelText.normalized(article.category)
        let body = CrisisSentinelText.normalized(article.searchableText)

        return queryTokens.reduce(into: 0) { partial, token in
            if title.contains(token) {
                partial += 8
            } else if category.contains(token) {
                partial += 5
            } else if body.contains(token) {
                partial += 2
            }
        }
    }

    static func fromSurvivalGuide(locale: Locale = .current) -> CrisisSentinelKnowledgeIndex {
        let guideArticles = SurvivalGuideData.categories.flatMap { category in
            category.guides.map { article in
                CrisisSentinelKnowledgeArticle(
                    id: article.id,
                    category: category.title.resolve(locale: locale),
                    title: article.title.resolve(locale: locale),
                    priority: article.priority.resolve(locale: locale),
                    in30Seconds: article.in30Seconds.map { $0.resolve(locale: locale) },
                    stepByStep: article.stepByStep.map { $0.resolve(locale: locale) },
                    dontDo: article.dontDo.map { $0.resolve(locale: locale) },
                    checklist: article.checklist.map { $0.resolve(locale: locale) },
                    sourceNote: article.sourceNote?.resolve(locale: locale)
                )
            }
        }
        return CrisisSentinelKnowledgeIndex(articles: guideArticles + crisisConnectPlatformArticles(locale: locale))
    }

    private static func crisisConnectPlatformArticles(locale: Locale) -> [CrisisSentinelKnowledgeArticle] {
        let tr = CrisisSentinelText.isTurkish(locale)
        func article(
            id: String,
            titleTr: String,
            titleEn: String,
            secondsTr: [String],
            secondsEn: [String],
            stepsTr: [String],
            stepsEn: [String],
            dontTr: [String],
            dontEn: [String],
            checklistTr: [String],
            checklistEn: [String]
        ) -> CrisisSentinelKnowledgeArticle {
            CrisisSentinelKnowledgeArticle(
                id: id,
                category: "Crisis Connect Platform",
                title: tr ? titleTr : titleEn,
                priority: tr ? "Ürün bilgisi" : "Product knowledge",
                in30Seconds: tr ? secondsTr : secondsEn,
                stepByStep: tr ? stepsTr : stepsEn,
                dontDo: tr ? dontTr : dontEn,
                checklist: tr ? checklistTr : checklistEn,
                sourceNote: tr
                    ? "Yerel Crisis Connect ürün dokümanı; offline AI ve saha modu cevapları için gömülüdür."
                    : "Local Crisis Connect product doctrine embedded for offline AI and field-mode answers."
            )
        }

        return [
            article(
                id: "CSE-GAS-001",
                titleTr: "Gaz Kokusu, Sızıntı ve Kimyasal Risk",
                titleEn: "Gas Smell, Leak, and Chemical Hazard",
                secondsTr: [
                    "Gaz kokusu, keskin kimyasal koku veya sızıntı şüphesi varsa içeride kalma; sakin şekilde dışarı çık ve güvenli mesafeye geç.",
                    "Elektrik anahtarı, priz, sigorta, telefon şarjı, çakmak, kibrit veya kıvılcım çıkarabilecek cihaz kullanma.",
                    "Güvenli mesafeden 112'yi, itfaiyeyi veya yerel gaz/tehlikeli madde ekibini ara; bina içine geri dönme."
                ],
                secondsEn: [
                    "If you smell gas, detect a sharp chemical odor, or suspect a leak, do not stay inside; leave calmly and move to a safe distance.",
                    "Do not use switches, plugs, breakers, chargers, lighters, matches, or anything that can create sparks.",
                    "From a safe distance, call local emergency services, fire, gas, or hazmat teams; do not re-enter the building."
                ],
                stepsTr: [
                    "Kapı ve pencereyi yalnızca güvenliyse aç; bunu yapmak seni geciktiriyorsa doğrudan tahliye et.",
                    "Yakındaki kişileri kısa ve sakin uyar; asansör kullanma, açık alana çık.",
                    "Saha ekibine konum, koku türü, etkilenen kişi sayısı, görünen duman/sızıntı ve bina bilgisi ver."
                ],
                stepsEn: [
                    "Open doors or windows only if it is safe and does not delay evacuation.",
                    "Warn nearby people briefly and calmly; avoid elevators and move to open air.",
                    "Report location, odor type, affected people, visible smoke/leak, and building context to responders."
                ],
                dontTr: [
                    "Gaz kaynağını aramak, vanayı bulmak için riskli alana geri girmek veya onarım denemek güvenli değildir.",
                    "Maske, ıslak bez veya kısa süre dayanırım düşüncesiyle içeride kalma."
                ],
                dontEn: [
                    "Do not go back into the risk area to find the source, locate valves, or attempt repairs.",
                    "Do not stay inside because of a mask, wet cloth, or an assumption that brief exposure is safe."
                ],
                checklistTr: ["Güvenli tahliye", "Kıvılcım kaynakları yok", "112/gaz/itfaiye bilgilendirildi"],
                checklistEn: ["Safe evacuation", "No spark sources", "Emergency/gas/fire team notified"]
            ),
            article(
                id: "CC-001",
                titleTr: "Crisis Connect Nedir?",
                titleEn: "What is Crisis Connect?",
                secondsTr: [
                    "Crisis Connect internet ve cep şebekesi yokken yakın cihazlar arasında mesaj, sesli not, konum ve acil durum bilgisini paylaşmak için tasarlanmış offline afet iletişim uygulamasıdır.",
                    "Bluetooth Low Energy yakın cihaz keşfi ve düşük bant iletişim için kullanılır; bazı akışlar cihazdan cihaza veya mesh atlamalarıyla genişleyebilir.",
                    "Crisis Connect resmi acil hatların yerine geçmez; hayati riskte 112 ve resmi talimatlar önceliklidir."
                ],
                secondsEn: [
                    "Crisis Connect is an offline disaster communication app for sharing messages, voice notes, location, and emergency context between nearby devices when internet or cellular service is unavailable.",
                    "Bluetooth Low Energy is used for nearby discovery and low-bandwidth communication; some flows can extend through device-to-device or mesh hops.",
                    "Crisis Connect does not replace official emergency lines; call local emergency services for life-threatening danger."
                ],
                stepsTr: [
                    "Krizden önce güvenilir kişileri QR ile ekle ve izinleri test et.",
                    "Afette kısa metin, konum ve durum bilgisini öncele; büyük medya düşük bağlantıda gecikebilir.",
                    "İnternet geldiğinde backend veya dashboard senkronizasyonu ayrı kanallardan yapılabilir."
                ],
                stepsEn: [
                    "Before a crisis, add trusted contacts with QR and test permissions.",
                    "During an incident, prioritize short text, location, and status; large media can be delayed on weak links.",
                    "When internet returns, backend or dashboard sync can run through separate channels."
                ],
                dontTr: [
                    "Sınırsız menzil, kesin teslimat veya her cihazda aynı performans sözü verme.",
                    "Uygulamayı 112, AFAD, itfaiye, sağlık veya resmi komuta yerine geçiyor gibi anlatma."
                ],
                dontEn: [
                    "Do not promise unlimited range, guaranteed delivery, or identical behavior on every device.",
                    "Do not describe the app as a replacement for emergency services, fire, medical, or official command."
                ],
                checklistTr: ["Offline amaç açıklandı", "BLE/yakın cihaz sınırı belirtildi", "112/resmi hat sınırı korundu"],
                checklistEn: ["Offline purpose explained", "BLE/nearby-device limit stated", "Emergency-service boundary preserved"]
            ),
            article(
                id: "CC-002",
                titleTr: "Offline Bağlantı: BLE, GATT, RFCOMM ve Mesh",
                titleEn: "Offline Connectivity: BLE, GATT, RFCOMM, and Mesh",
                secondsTr: [
                    "BLE yakın cihazları keşfetmek ve küçük veri parçalarını taşımak için uygundur.",
                    "RFCOMM veya benzeri bağlantılar daha büyük sohbet, dosya, sesli not veya aktarım akışlarında kullanılabilir.",
                    "Mesh her koşulda garanti değildir; cihaz modeli, pil, izinler, beton/metal engeller ve kalabalık ortam performansı etkiler."
                ],
                secondsEn: [
                    "BLE is suitable for discovering nearby devices and moving small data chunks.",
                    "RFCOMM or similar links can support larger chat, file, voice-note, or transfer flows.",
                    "Mesh is not guaranteed in every condition; device model, battery, permissions, concrete/metal obstacles, and crowd density affect performance."
                ],
                stepsTr: [
                    "Kritik bilgiyi kısa metin ve konum olarak gönder.",
                    "Medya aktarımında parça, makbuz, tekrar deneme ve düşük bant stratejisi kullan.",
                    "Teslim edildi bilgisi yoksa mesajı kesin ulaşmış kabul etme."
                ],
                stepsEn: [
                    "Send critical context as short text and location first.",
                    "Use chunking, receipts, retry, and low-bandwidth strategy for media transfer.",
                    "Do not treat a message as delivered without delivery evidence."
                ],
                dontTr: ["Beton, metal ve enkaz etkisini yok sayma.", "Tek bağlantı yolunu resmi operasyon kanalı gibi kullanma."],
                dontEn: ["Do not ignore concrete, metal, and debris effects.", "Do not treat a single link as the official operation channel."],
                checklistTr: ["Kısa metin öncelendi", "Teslimat durumu ayrıldı", "Medya düşük bant kuralına bağlandı"],
                checklistEn: ["Short text prioritized", "Delivery state separated", "Media tied to low-bandwidth rules"]
            ),
            article(
                id: "CC-003",
                titleTr: "Herkes İçin Araçlar ve Offline Afet Bilgisi",
                titleEn: "Public Tools and Offline Disaster Guidance",
                secondsTr: [
                    "Araçlar bölümü herkesin uygulamayı indirip offline afet bilgisi, ilk yardım hatırlatmaları, hazırlık listeleri ve güvenli davranış önerileri alması için tasarlanır.",
                    "Public mod resmi talimat vermez; kullanıcıya kısa, uygulanabilir ve kaynaklı offline bilgi sunar.",
                    "Crisis Sentinel public modda tıbbi tanı koymaz, bina güvenliği onayı vermez ve kesin konum/kişi sayısı uydurmaz."
                ],
                secondsEn: [
                    "The tools area lets any user download the app and access offline disaster guidance, first-aid reminders, preparedness checklists, and safe behavior suggestions.",
                    "Public mode does not issue official instructions; it gives short, practical, sourced offline guidance.",
                    "In public mode, Crisis Sentinel does not diagnose, certify building safety, or invent exact location or victim counts."
                ],
                stepsTr: [
                    "Kullanıcı sorusunu offline rehberde ara ve en alakalı makaleye bağla.",
                    "Hayati riskte 112 ve resmi talimatı cevapta açık tut.",
                    "Belirsizlik varsa ek bilgi sor; emin değilmiş gibi davran, uydurma."
                ],
                stepsEn: [
                    "Search the offline guide and attach the most relevant article.",
                    "Keep emergency-service and official-instruction boundaries visible for life-threatening risk.",
                    "Ask for missing context when uncertain; do not invent."
                ],
                dontTr: ["Public kullanıcıya operasyon emri verme.", "Fotoğraftan kesin tanı veya yapı güvenliği kararı verme."],
                dontEn: ["Do not issue operational orders to public users.", "Do not make definitive diagnoses or building-safety decisions from photos."],
                checklistTr: ["Kaynaklı cevap verildi", "Resmi hat sınırı belirtildi", "Eksik bilgi soruldu"],
                checklistEn: ["Sourced answer given", "Emergency-service boundary stated", "Missing context requested"]
            ),
            article(
                id: "CC-004",
                titleTr: "Saha Ekibi Modu ve Operasyon Taslakları",
                titleEn: "Field Team Mode and Operation Drafts",
                secondsTr: [
                    "Saha ekibi modu kısa raporlardan olay türü, öncelik, konum, kişi sayısı, riskler, ihtiyaçlar ve eksik bilgileri çıkarır.",
                    "Çıktı sevk emri değil saha taslağıdır; komuta ve resmi karar yerine geçmez.",
                    "Gaz, kimyasal, elektrik, yangın, çökme ve mahsur kalma gibi riskler ayrı alanlarda tutulmalıdır."
                ],
                secondsEn: [
                    "Field team mode extracts incident type, priority, location, victim count, hazards, needs, and missing fields from short reports.",
                    "The output is a field draft, not a dispatch order; it does not replace command or official decisions.",
                    "Gas, chemical, electrical, fire, collapse, and trapped-person hazards must remain explicit."
                ],
                stepsTr: [
                    "SITREP taslağında olay türü, öncelik, konum, etkilenen kişi, risk, ihtiyaç ve zaman bilgisini ayır.",
                    "Eksik alanları en fazla üç takip sorusuyla tamamlat.",
                    "Yüksek riskte güvenli mesafe ve ilgili uzman ekibi öncele."
                ],
                stepsEn: [
                    "Separate incident type, priority, location, affected people, hazards, needs, and time in the SITREP draft.",
                    "Ask at most three follow-up questions for missing fields.",
                    "For high-risk hazards, prioritize safe distance and specialist response teams."
                ],
                dontTr: ["Komuta onayı olmadan sevk emri üretme.", "Riskleri tek serbest metne gömüp kaybetme."],
                dontEn: ["Do not generate dispatch orders without command approval.", "Do not bury hazards inside unstructured text."],
                checklistTr: ["SITREP alanları ayrıldı", "Eksikler soruldu", "Komuta sınırı korundu"],
                checklistEn: ["SITREP fields separated", "Missing fields requested", "Command boundary preserved"]
            ),
            article(
                id: "CC-011",
                titleTr: "Yetkili Koordinasyon Modu",
                titleEn: "Authorized Coordination Mode",
                secondsTr: [
                    "Yetkili koordinasyon modu yalnızca doğrulanmış yetkili rol bağlamında görev, kaynak, öncelik ve devir notu taslakları üretir.",
                    "Çıktı sadece taslak nottur; komuta/kurum onayı ve saha teyidi olmadan ekip yönlendirme başlatmaz.",
                    "Eksik konum, kişi sayısı, kaynak miktarı veya tıbbi durumu doğrulanmış gibi yazmaz."
                ],
                secondsEn: [
                    "Authorized coordination mode drafts tasking, resources, priorities, and handover notes only in verified authority context.",
                    "Output is a draft note only; it does not authorize deployment without command/agency approval and field confirmation.",
                    "Missing locations, counts, resource amounts, or medical state must not be presented as verified."
                ],
                stepsTr: [
                    "Olay, konum, risk, ihtiyaç, kaynak, zaman ve eksik bilgiyi ayrı başlıklarla yaz.",
                    "Görevleri taslak olarak ifade et ve onay gerektirdiğini açık belirt.",
                    "Gaz/kimyasal/sağlık riskinde güvenli mesafe ve uzman ekip eskalasyonunu öncele."
                ],
                stepsEn: [
                    "Separate incident, location, risks, needs, resources, time, and missing information.",
                    "Phrase tasks as drafts and state that approval is required.",
                    "For gas, chemical, or medical risk, prioritize safe distance and specialist escalation."
                ],
                dontTr: ["Çıktıyı onaylanmış karar gibi yazma.", "Belirsiz veriyi doğrulanmış gibi gösterme."],
                dontEn: ["Do not present the output as an approved decision.", "Do not present uncertain data as verified."],
                checklistTr: ["Taslak etiketi var", "Onay sınırı var", "Eksik bilgi ayrıldı"],
                checklistEn: ["Draft label present", "Approval boundary present", "Missing information separated"]
            ),
            article(
                id: "CC-005",
                titleTr: "Crisis Sentinel Mobil AI Rolü",
                titleEn: "Crisis Sentinel Mobile AI Role",
                secondsTr: [
                    "Mobil Crisis Sentinel düşük parametreli, offline çalışmaya uygun ve güvenlik kurallarıyla sınırlandırılmış yardımcıdır.",
                    "RAG, yerel rehber, deterministic olay çıkarımı ve doğrulama katmanı model cevabından önce gelir.",
                    "Model çıktısı bozuk JSON, geçersiz kaynak, uygunsuz tıbbi/operasyon iddiası veya tehlikeli tavsiye içerirse fallback cevap kullanılır."
                ],
                secondsEn: [
                    "Mobile Crisis Sentinel is a low-parameter offline assistant constrained by safety rules.",
                    "RAG, local guide data, deterministic incident extraction, and validation come before model output.",
                    "If model output has invalid JSON, bad citations, inappropriate medical/operational claims, or unsafe advice, fallback output is used."
                ],
                stepsTr: [
                    "Önce yerel RAG makalelerini getir.",
                    "Modelden JSON şemasına uygun kısa cevap iste.",
                    "Schema, enum, citation ve güvenlik doğrulaması geçmeden cevabı gösterme."
                ],
                stepsEn: [
                    "Retrieve local RAG articles first.",
                    "Ask the model for a short response that matches the JSON schema.",
                    "Do not show the model answer until schema, enum, citation, and safety validation pass."
                ],
                dontTr: ["Ham model metnini doğrudan production'da gösterme.", "Citation uyduran cevabı kabul etme."],
                dontEn: ["Do not show raw model text directly in production.", "Do not accept answers with invented citations."],
                checklistTr: ["RAG kullanıldı", "JSON doğrulandı", "Safety fallback hazır"],
                checklistEn: ["RAG used", "JSON validated", "Safety fallback ready"]
            ),
            article(
                id: "CC-006",
                titleTr: "Fotoğraf, Dosya, Sesli Not, Dikte ve Sesli Görüşme",
                titleEn: "Photo, File, Voice Note, Dictation, and Voice Call",
                secondsTr: [
                    "Crisis Connect bağlantı uygunsa mesaj, konum, fotoğraf, dosya ve sesli not aktarabilir; büyük medya düşük bağlantıda gecikebilir.",
                    "Yakındaki kişiyle sesli görüşme desteklenebilir; bağlantı zayıfsa kısa metin veya sesli not daha güvenilirdir.",
                    "Dikte cihazın işletim sistemi veya app entegrasyonuyla metne çevrilip Crisis Sentinel'e sorulabilir."
                ],
                secondsEn: [
                    "When the link allows it, Crisis Connect can transfer messages, location, photos, files, and voice notes; large media may be delayed on weak links.",
                    "Nearby voice calls can be supported; short text or voice notes are more reliable on weak links.",
                    "Dictation can be converted to text by the OS or app integration and then sent to Crisis Sentinel."
                ],
                stepsTr: [
                    "Fotoğrafı kanıt değil gözlem desteği olarak ele al: görünür risk, OCR, malzeme türü, konum ipucu ve eksik bilgi ayrılır.",
                    "Kritik olayda medya gelmesini bekleme; kısa metin özeti de gönder.",
                    "Yüz, kimlik, plaka, telefon, adres ve sağlık verisini minimum işle veya maskele."
                ],
                stepsEn: [
                    "Treat photos as observation support, not proof: separate visible risk, OCR, material type, location clues, and missing information.",
                    "Do not wait for media before sending a critical text summary.",
                    "Minimize or mask faces, IDs, plates, phone numbers, addresses, and health data."
                ],
                dontTr: ["Fotoğraftan kesin tanı, kimlik, koordinat veya yapı güvenliği kararı verme.", "Mahrem yaralı/çocuk görüntüsünü gereksiz paylaşma."],
                dontEn: ["Do not infer definitive diagnosis, identity, coordinates, or building safety from a photo.", "Do not unnecessarily share sensitive images of injured people or children."],
                checklistTr: ["Medya türü belirtildi", "Düşük bant alternatifi yazıldı", "Mahremiyet kontrol edildi"],
                checklistEn: ["Media type stated", "Low-bandwidth alternative written", "Privacy checked"]
            ),
            article(
                id: "CC-007",
                titleTr: "Dashboard ve Yüksek Parametreli AI Ayrımı",
                titleEn: "Dashboard and High-Parameter AI Split",
                secondsTr: [
                    "Mobil AI offline ve hızlı karar desteğine odaklanır; dashboard tarafı internet/backend olduğunda daha yüksek parametreli model, çoklu olay özetleme ve operasyon analizi yapabilir.",
                    "Dashboard çıktılarını da resmi komuta yerine koyma; doğrulama, kaynak ve insan onayı gerekir.",
                    "Mobil ile dashboard arasında senkronizasyon olduğunda kaynak, zaman, doğrulama durumu ve cihaz bilgisi korunmalıdır."
                ],
                secondsEn: [
                    "Mobile AI focuses on offline quick assistance; the dashboard can use a higher-parameter model for multi-incident summaries and operational analysis when backend access exists.",
                    "Dashboard outputs must not replace official command; verification, sourcing, and human approval are required.",
                    "Sync should preserve source, time, verification state, and device context."
                ],
                stepsTr: ["Mobilde kısa ve doğrulanabilir cevap üret.", "Dashboard'da çoklu raporları kümele ve belirsizliği işaretle.", "İnsan onayı gerektiren kararları ayrı tut."],
                stepsEn: ["Produce short and verifiable mobile answers.", "Cluster multi-report data on the dashboard and mark uncertainty.", "Separate decisions requiring human approval."],
                dontTr: ["Dashboard analizini kesin gerçek veya resmi emir gibi gösterme.", "Mobil offline cevabı internet gerektiriyor gibi tasarlama."],
                dontEn: ["Do not present dashboard analysis as certain fact or official order.", "Do not design mobile offline answers as internet-dependent."],
                checklistTr: ["Mobil/dashboard rolü ayrıldı", "Senkron veri alanları korundu", "İnsan onayı belirtildi"],
                checklistEn: ["Mobile/dashboard role separated", "Sync fields preserved", "Human approval stated"]
            ),
            article(
                id: "CC-008",
                titleTr: "Gizlilik, Güvenlik ve Rol Sertifikaları",
                titleEn: "Privacy, Security, and Role Certificates",
                secondsTr: [
                    "Crisis Connect local-first çalışmalı; hassas veriler görev amacı, rıza ve minimum veri ilkesiyle işlenmelidir.",
                    "Saha ekibi özellikleri rol sertifikası veya doğrulanmış ekip kimliği ile ayrılmalıdır.",
                    "AI cevapları kimlik, sağlık verisi, fotoğraf ve konum bilgisini gereksiz çoğaltmamalıdır."
                ],
                secondsEn: [
                    "Crisis Connect should remain local-first; sensitive data must follow purpose, consent, and data-minimization rules.",
                    "Field-team features should be separated by role certificates or verified responder identity.",
                    "AI answers should not unnecessarily duplicate identity, health, photo, or location data."
                ],
                stepsTr: ["Yetki gerektiren saha özelliklerini public moddan ayır.", "Hassas görsel ve belgelerde maskeleme/azaltma uygula.", "Kaynak ve erişim durumunu logla."],
                stepsEn: ["Separate authority-sensitive field features from public mode.", "Apply masking/minimization to sensitive images and documents.", "Log source and access state."],
                dontTr: ["Public kullanıcıya ekip yetkisi veriyormuş gibi davranma.", "Gereksiz kimlik veya sağlık verisi üretme."],
                dontEn: ["Do not act as if public users have responder authority.", "Do not generate unnecessary identity or health data."],
                checklistTr: ["Rol sınırı korundu", "Veri minimizasyonu uygulandı", "Mahremiyet uyarısı eklendi"],
                checklistEn: ["Role boundary preserved", "Data minimization applied", "Privacy warning added"]
            ),
            article(
                id: "CC-009",
                titleTr: "Afet Öncesi Hazırlık ve Uygulama Testi",
                titleEn: "Preparedness and App Readiness Test",
                secondsTr: [
                    "Crisis Connect en iyi krizden önce kurulduğunda, izinler verildiğinde ve güvenilir kişiler QR ile eklendiğinde çalışır.",
                    "Pil, Bluetooth, konum izni, bildirimler, offline rehber ve acil kart düzenli kontrol edilmelidir.",
                    "Aile ve ekipler kısa metin, konum, sesli not ve düşük bant senaryosunu tatbikatla denemelidir."
                ],
                secondsEn: [
                    "Crisis Connect works best when installed before a crisis, permissions are granted, and trusted contacts are added by QR.",
                    "Battery, Bluetooth, location permission, notifications, offline guide, and emergency card should be checked regularly.",
                    "Families and teams should drill short text, location, voice note, and low-bandwidth scenarios."
                ],
                stepsTr: ["İzinleri ve pil optimizasyonunu kontrol et.", "QR güvenilir kişi listesini güncelle.", "Offline rehberi ve temel araçları açıp test et."],
                stepsEn: ["Check permissions and battery optimization.", "Update QR trusted-contact list.", "Open and test the offline guide and core tools."],
                dontTr: ["İlk kurulumu afet anına bırakma.", "Tek cihaz veya tek iletişim kanalına güvenme."],
                dontEn: ["Do not leave first setup to the disaster moment.", "Do not rely on one device or one communication channel."],
                checklistTr: ["İzinler hazır", "QR kişiler hazır", "Offline rehber test edildi"],
                checklistEn: ["Permissions ready", "QR contacts ready", "Offline guide tested"]
            ),
            article(
                id: "CC-010",
                titleTr: "Crisis Connect Sınırları ve Doğru Beklenti",
                titleEn: "Crisis Connect Limits and Correct Expectations",
                secondsTr: [
                    "Crisis Connect yedek iletişim ve afet destek aracıdır; resmi acil hat, kurtarma ekibi veya tıbbi hizmetin yerine geçmez.",
                    "Bluetooth menzili çevreye göre değişir; enkaz, beton, metal, kalabalık ve cihaz modeli performansı etkiler.",
                    "AI kesin konum, kişi sayısı, yapı güvenliği, tıbbi tanı veya resmi karar uydurmamalıdır."
                ],
                secondsEn: [
                    "Crisis Connect is a backup communication and disaster-support tool; it does not replace emergency lines, rescue teams, or medical service.",
                    "Bluetooth range varies by environment; debris, concrete, metal, crowding, and device model affect performance.",
                    "AI must not invent exact location, person counts, building safety, medical diagnosis, or official decisions."
                ],
                stepsTr: ["Pil azsa konum ve kısa metin önceliklidir.", "Bağlantı düşükse medya yerine özet gönder.", "Belirsizliği açıkça yaz ve doğrulama iste."],
                stepsEn: ["When battery is low, prioritize location and short text.", "When connectivity is weak, send summaries instead of media.", "State uncertainty clearly and request verification."],
                dontTr: ["Uygulamayı her koşulda çalışacak garanti sistem gibi anlatma.", "AI yanıtı ile resmi talimatı karıştırma."],
                dontEn: ["Do not present the app as guaranteed in every condition.", "Do not confuse AI output with official instructions."],
                checklistTr: ["Resmi hat sınırı belirtildi", "Menzil değişkenliği belirtildi", "AI sınırı korundu"],
                checklistEn: ["Emergency-service boundary stated", "Range variability stated", "AI boundary preserved"]
            )
        ]
    }
}
