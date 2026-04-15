//
//  SurvivalGuideData.swift
//  Crisis Connect
//
//  Generated from Android GuideMainScreenViewModel.kt for cross-platform parity.
//

import Foundation

struct SurvivalGuideLocalizedText: Hashable {
    let tr: String
    let en: String

    func resolve(locale: Locale) -> String {
        let languageCode = locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
        return languageCode.hasPrefix("tr") ? tr : en
    }
}

struct SurvivalGuideArticle: Identifiable, Hashable {
    let id: String
    let title: SurvivalGuideLocalizedText
    let priority: SurvivalGuideLocalizedText
    let readMinutes: Int
    let in30Seconds: [SurvivalGuideLocalizedText]
    let stepByStep: [SurvivalGuideLocalizedText]
    let dontDo: [SurvivalGuideLocalizedText]
    let checklist: [SurvivalGuideLocalizedText]
    let sourceNote: SurvivalGuideLocalizedText?
}

struct SurvivalGuideCategory: Identifiable, Hashable {
    let id: String
    let title: SurvivalGuideLocalizedText
    let description: SurvivalGuideLocalizedText
    let symbolName: String
    let guides: [SurvivalGuideArticle]
}

enum SurvivalGuideData {
    static let allCategoryID = "all"
    static let categories: [SurvivalGuideCategory] = [
        SurvivalGuideCategory(
            id: "emergency_info",
            title: SurvivalGuideLocalizedText(tr: "Acil Bilgiler", en: "Emergency Info"),
            description: SurvivalGuideLocalizedText(tr: "112, aile planı, toplanma alanı ve 72 saat çantası", en: "112, family plan, assembly area and 72-hour go-bag"),
            symbolName: "info.circle.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "G-001",
                    title: SurvivalGuideLocalizedText(tr: "112 ve Acil Arama Kılavuzu", en: "112 Emergency Call Guide"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Hayati tehlike varsa 112'yi ara.", en: "Call 112 immediately for life-threatening situations."),
                        SurvivalGuideLocalizedText(tr: "Konumu net ver: il/ilçe/mahalle/sokak/bina no.", en: "Give exact location: city/district/neighborhood/street/building."),
                        SurvivalGuideLocalizedText(tr: "Olayı tek cümlede özetle: ne oldu, kaç kişi etkilendi.", en: "Summarize in one sentence: what happened and how many are affected."),
                        SurvivalGuideLocalizedText(tr: "Operatör kapat demeden telefonu kapatma.", en: "Do not hang up until the operator tells you."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kesin adres + yakın işaret (okul, market, cami, metro).", en: "Share exact address and nearest landmark."),
                        SurvivalGuideLocalizedText(tr: "Kim arıyor + geri aranacak telefon numarası.", en: "State caller name and callback number."),
                        SurvivalGuideLocalizedText(tr: "Olay türü: deprem, yangın, yaralanma, mahsur kalma.", en: "State incident type: quake, fire, injury, trapped person."),
                        SurvivalGuideLocalizedText(tr: "Kişi sayısı ve durum: bilinç, nefes, kanama.", en: "State victim count and condition: consciousness, breathing, bleeding."),
                        SurvivalGuideLocalizedText(tr: "Ek riskler: gaz kokusu, duman, çökme riski.", en: "Mention extra risks: gas odor, smoke, collapse risk."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Panikle bağırıp adres bilgisini atlama.", en: "Do not skip address details due to panic."),
                        SurvivalGuideLocalizedText(tr: "“Tam bilmiyorum” deyip hattı belirsiz bırakma.", en: "Do not leave the call vague with “I don't know”."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Adres hazır", en: "Address ready"),
                        SurvivalGuideLocalizedText(tr: "Olay türü hazır", en: "Incident type ready"),
                        SurvivalGuideLocalizedText(tr: "Yaralı sayısı hazır", en: "Victim count ready"),
                        SurvivalGuideLocalizedText(tr: "Ek risk bilgisi hazır", en: "Additional risk info ready"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "112 tek acil numaradır; adres, olay tanımı ve kişi durumu net verilmelidir.", en: "112 is the single emergency number; location, incident and victim status must be clear.")
                ),
                SurvivalGuideArticle(
                    id: "G-002",
                    title: SurvivalGuideLocalizedText(tr: "Aile Acil Durum Planı", en: "Family Emergency Plan"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "1 şehir dışı irtibat kişisi belirle.", en: "Assign one out-of-city contact."),
                        SurvivalGuideLocalizedText(tr: "2 buluşma noktası seç: mahalle içi + mahalle dışı.", en: "Set two meeting points: local and outside neighborhood."),
                        SurvivalGuideLocalizedText(tr: "Herkeste acil kartı olsun: kimlik kopyası + numaralar.", en: "Everyone carries an emergency card: ID copy + key numbers."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Aynı irtibat kişisini tüm aile kullansın.", en: "All family members contact the same person."),
                        SurvivalGuideLocalizedText(tr: "Hat yoğunluğunda SMS kuralını kullan.", en: "Use SMS when voice lines are congested."),
                        SurvivalGuideLocalizedText(tr: "Plan sırası: Ev yoksa nokta-1, olmazsa nokta-2.", en: "Protocol: if home fails use point-1, then point-2."),
                        SurvivalGuideLocalizedText(tr: "Rol dağıt: çanta, çocuk/yaşlı, evcil hayvan sorumlusu.", en: "Assign roles: bag, children/elderly, pets."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Tek buluşma noktasına güvenme.", en: "Do not rely on only one meeting point."),
                        SurvivalGuideLocalizedText(tr: "Herkes farklı kişiyi aramasın.", en: "Do not scatter information by calling different people."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Şehir dışı kişi seçildi", en: "Out-of-city contact selected"),
                        SurvivalGuideLocalizedText(tr: "2 buluşma noktası kaydedildi", en: "Two meeting points saved"),
                        SurvivalGuideLocalizedText(tr: "Acil kartlar hazırlandı", en: "Emergency cards prepared"),
                        SurvivalGuideLocalizedText(tr: "Rol dağıtımı yapıldı", en: "Roles assigned"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Aile planlarında şehir dışı irtibat ve SMS odaklı iletişim önerilir.", en: "Family plans recommend one out-of-area contact and SMS-first communication.")
                ),
                SurvivalGuideArticle(
                    id: "G-003",
                    title: SurvivalGuideLocalizedText(tr: "Toplanma Alanı Nedir? E-Devlet'ten Nasıl Bulunur?", en: "What Is an Assembly Area? How to Find It via e-Devlet"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Toplanma alanı afet sonrası ilk güvenli buluşma noktasıdır.", en: "Assembly area is the first safe post-disaster meeting point."),
                        SurvivalGuideLocalizedText(tr: "E-Devlet'te “Toplanma Alanı Sorgulama” ile bulunur.", en: "Find it in e-Devlet with “Assembly Area Query”."),
                        SurvivalGuideLocalizedText(tr: "En yakın alanın adres ve konumunu kaydet.", en: "Save nearest area address and map location."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "E-Devlet'e giriş yap.", en: "Sign in to e-Devlet."),
                        SurvivalGuideLocalizedText(tr: "Arama kutusuna “Toplanma Alanı Sorgulama” yaz.", en: "Search for “Toplanma Alanı Sorgulama”."),
                        SurvivalGuideLocalizedText(tr: "Haritada en yakını seç ve kaydet.", en: "Pick the nearest on map and save it."),
                        SurvivalGuideLocalizedText(tr: "Aile ile paylaş, 2 alternatif rota çıkar.", en: "Share with family and define two routes."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Toplanma alanını barınma alanı ile karıştırma.", en: "Do not confuse assembly area with shelter area."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "En yakın toplanma alanı bulundu", en: "Nearest assembly area found"),
                        SurvivalGuideLocalizedText(tr: "Adres + koordinat kaydedildi", en: "Address + coordinates saved"),
                        SurvivalGuideLocalizedText(tr: "Aile ile paylaşıldı", en: "Shared with family"),
                        SurvivalGuideLocalizedText(tr: "Alternatif rotalar çizildi", en: "Alternative routes planned"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Toplanma alanı sorgulaması AFAD/e-Devlet üzerinden resmi olarak sunulur.", en: "Assembly area lookup is officially provided via AFAD/e-Devlet.")
                ),
                SurvivalGuideArticle(
                    id: "G-004",
                    title: SurvivalGuideLocalizedText(tr: "Afet & Acil Durum Çantası (72 Saat)", en: "Disaster Go-Bag (72 Hours)"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Hedef: ilk 72 saati kendi başına yönetebilmek.", en: "Goal: self-manage the first 72 hours."),
                        SurvivalGuideLocalizedText(tr: "Omurga: su, gıda, ilk yardım, ışık, iletişim, evrak.", en: "Core: water, food, first aid, light, communication, documents."),
                        SurvivalGuideLocalizedText(tr: "Çanta kapıya yakın ve erişilebilir yerde olsun.", en: "Keep the bag near the exit and easy to reach."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Su + bozulmayan gıda + konserve açacağı ekle.", en: "Pack water, non-perishable food, and can opener."),
                        SurvivalGuideLocalizedText(tr: "Düzenli ilaçlar + reçete + temel ilk yardım seti koy.", en: "Add regular meds, prescriptions, and first aid kit."),
                        SurvivalGuideLocalizedText(tr: "Fener, pil, powerbank, kablo ve mümkünse radyo koy.", en: "Add flashlight, batteries, powerbank, cable, and radio."),
                        SurvivalGuideLocalizedText(tr: "Kimlik ve kritik belge fotokopileri + bir miktar nakit ekle.", en: "Add ID/critical document copies and cash."),
                        SurvivalGuideLocalizedText(tr: "Bebek/yaşlı/engelli/evcil için özel malzemeyi unutma.", en: "Include special items for babies/elderly/disabled/pets."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Çantayı evde ulaşılmaz yere kaldırma.", en: "Do not store the bag in an unreachable place."),
                        SurvivalGuideLocalizedText(tr: "Son kullanma tarihi kontrolünü atlama.", en: "Do not skip expiry-date checks."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Su ve gıda hazır", en: "Water and food packed"),
                        SurvivalGuideLocalizedText(tr: "İlk yardım ve ilaç hazır", en: "First aid and medications packed"),
                        SurvivalGuideLocalizedText(tr: "Işık/iletişim ekipmanı hazır", en: "Light/communication kit packed"),
                        SurvivalGuideLocalizedText(tr: "Belge ve nakit hazır", en: "Documents and cash packed"),
                        SurvivalGuideLocalizedText(tr: "Özel ihtiyaç malzemeleri hazır", en: "Special-needs supplies packed"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "AFAD dayanıklı gıda ve önemli belge fotokopilerini çantada önerir.", en: "AFAD recommends durable food and copies of critical documents in the go-bag.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "earthquake",
            title: SurvivalGuideLocalizedText(tr: "Deprem", en: "Earthquake"),
            description: SurvivalGuideLocalizedText(tr: "Deprem öncesi hazırlık, deprem anı ve ilk 30 dakika", en: "Preparedness, immediate response, and first 30 minutes"),
            symbolName: "waveform.path.ecg",
            guides: [
                SurvivalGuideArticle(
                    id: "E-001",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Öncesi Ev Güvenliği (Risk Azaltma)", en: "Home Safety Before Earthquakes"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Dolap, raf, TV gibi devrilebilir eşyaları sabitle.", en: "Anchor wardrobes, shelves, and TVs."),
                        SurvivalGuideLocalizedText(tr: "Ağır eşyaları alt raflara al.", en: "Move heavy items to lower shelves."),
                        SurvivalGuideLocalizedText(tr: "Kaçış yolunu açık tut ve mini tatbikat yap.", en: "Keep exit path clear and run mini drills."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yatak başını cam/pencereden uzaklaştır.", en: "Keep bed away from windows/glass."),
                        SurvivalGuideLocalizedText(tr: "Kapı önü ve koridoru daima boş bırak.", en: "Keep doorways and corridors clear."),
                        SurvivalGuideLocalizedText(tr: "Gaz, elektrik, su vanalarının yerini öğren.", en: "Know where gas, electric, and water shutoffs are."),
                        SurvivalGuideLocalizedText(tr: "Evde Çök-Kapan-Tutun pratiği yap.", en: "Practice Drop-Cover-Hold at home."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Ağır çerçeve/ayna gibi objeleri yatak üstüne asma.", en: "Do not hang heavy mirrors/frames above beds."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Mobilya sabitleme tamam", en: "Furniture anchoring complete"),
                        SurvivalGuideLocalizedText(tr: "Kaçış rotası temiz", en: "Exit route clear"),
                        SurvivalGuideLocalizedText(tr: "Vana noktaları öğrenildi", en: "Shutoff points known"),
                        SurvivalGuideLocalizedText(tr: "Ev tatbikatı yapıldı", en: "Home drill completed"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem öncesinde sabitleme ve deprem anında korunma davranışı kritik görülür.", en: "Anchoring before a quake and protective posture during a quake are critical.")
                ),
                SurvivalGuideArticle(
                    id: "E-002",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Anında: Çök-Kapan-Tutun", en: "During an Earthquake: Drop-Cover-Hold"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "ÇÖK-KAPAN-TUTUN uygula.", en: "Apply DROP-COVER-HOLD."),
                        SurvivalGuideLocalizedText(tr: "Cam/pencere ve düşebilecek eşyalardan uzak dur.", en: "Stay away from windows and falling objects."),
                        SurvivalGuideLocalizedText(tr: "Asansör, merdiven, balkon tarafına koşma.", en: "Do not run toward elevator, stairs, or balcony."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Sağlam masa yanında veya altında pozisyon al.", en: "Take position under/next to sturdy furniture."),
                        SurvivalGuideLocalizedText(tr: "Diz üstüne çök, baş-boynu koruyarak kapan.", en: "Drop to knees and cover head/neck."),
                        SurvivalGuideLocalizedText(tr: "Sabit noktaya tutun ve sarsıntı bitene kadar kal.", en: "Hold on and stay until shaking ends."),
                        SurvivalGuideLocalizedText(tr: "Dışarıdaysan bina ve direklerden uzak açık alana geç.", en: "If outside, move away from buildings and poles."),
                        SurvivalGuideLocalizedText(tr: "Araç içindeysen güvenli yerde dur, araçta kal.", en: "If in a vehicle, stop safely and stay inside."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Panikle dışarı fırlama.", en: "Do not rush outside in panic."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Güvenli nokta bul", en: "Find a safe spot"),
                        SurvivalGuideLocalizedText(tr: "Baş-boyun koru", en: "Protect head and neck"),
                        SurvivalGuideLocalizedText(tr: "Sarsıntı bitene kadar pozisyonu koru", en: "Hold position until shaking stops"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem anında Çök-Kapan-Tutun ve camdan uzak kalma temel yaklaşımdır.", en: "Drop-Cover-Hold and staying away from glass are core guidance.")
                ),
                SurvivalGuideArticle(
                    id: "E-003",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Sonrası: İlk 30 Dakika", en: "After Earthquake: First 30 Minutes"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Önce kendi güvenliğini sağla.", en: "Secure your own safety first."),
                        SurvivalGuideLocalizedText(tr: "Gaz kokusu varsa gazı kapat, havalandır, binayı terk et.", en: "If gas odor exists, shut gas, ventilate, and evacuate."),
                        SurvivalGuideLocalizedText(tr: "Çantanı al ve toplanma alanına git.", en: "Take go-bag and move to assembly area."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yaralı kontrolü yap, 112'ye bilgi ver.", en: "Check injuries and call 112."),
                        SurvivalGuideLocalizedText(tr: "Duman, çökme, gaz riski var mı değerlendir.", en: "Assess smoke, collapse, gas risks."),
                        SurvivalGuideLocalizedText(tr: "Uygunsa elektrik-gaz-su vanalarını kapat.", en: "If safe, shut electricity-gas-water."),
                        SurvivalGuideLocalizedText(tr: "Önceden belirlenen rotadan binayı terk et.", en: "Evacuate via pre-planned route."),
                        SurvivalGuideLocalizedText(tr: "Resmi duyuruları takip et, yolları acil araçlara bırak.", en: "Follow official updates and keep roads clear."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Hasarlı binaya geri girme.", en: "Do not re-enter damaged buildings."),
                        SurvivalGuideLocalizedText(tr: "Gereksiz araç kullanıp trafiği kilitleme.", en: "Do not block roads with unnecessary driving."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yaralı kontrolü yapıldı", en: "Injury check completed"),
                        SurvivalGuideLocalizedText(tr: "Vana kontrolleri yapıldı", en: "Utility shutoff check completed"),
                        SurvivalGuideLocalizedText(tr: "Çanta alındı", en: "Go-bag taken"),
                        SurvivalGuideLocalizedText(tr: "Toplanma alanına çıkıldı", en: "Moved to assembly area"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem sonrasında vana kontrolü, bina tahliyesi ve toplanma alanına geçiş önerilir.", en: "Post-quake guidance includes utility shutoff, evacuation, and assembly-area transfer.")
                ),
                SurvivalGuideArticle(
                    id: "E-004",
                    title: SurvivalGuideLocalizedText(tr: "Artçı Depremler ve Hasarlı Binalar", en: "Aftershocks and Damaged Buildings"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Büyük deprem sonrası artçı beklenir.", en: "After major quakes, aftershocks are expected."),
                        SurvivalGuideLocalizedText(tr: "Hasarlı binaya girme.", en: "Do not enter damaged structures."),
                        SurvivalGuideLocalizedText(tr: "Artçıda yine Çök-Kapan-Tutun.", en: "Use Drop-Cover-Hold during aftershocks."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Resmi yönlendirme gelmeden eve dönme.", en: "Do not return home before official guidance."),
                        SurvivalGuideLocalizedText(tr: "Toplanma alanında kal ve duyuruları izle.", en: "Stay at assembly area and monitor updates."),
                        SurvivalGuideLocalizedText(tr: "Artçı başladığında yakındaki güvenli pozisyonu al.", en: "Take nearby protective position when aftershock starts."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Bir şey olmaz” deyip çatlak binada bekleme.", en: "Do not stay in cracked buildings assuming safety."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Artçı riski hatırlandı", en: "Aftershock risk acknowledged"),
                        SurvivalGuideLocalizedText(tr: "Hasarlı bina terk edildi", en: "Damaged building avoided"),
                        SurvivalGuideLocalizedText(tr: "Resmi duyurular takipte", en: "Official alerts monitored"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Artçılar devam ederken hasarlı yapılara girilmemesi vurgulanır.", en: "Guidance stresses avoiding damaged buildings while aftershocks continue.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "fire",
            title: SurvivalGuideLocalizedText(tr: "Yangın", en: "Fire"),
            description: SurvivalGuideLocalizedText(tr: "Yangında ilk dakikalar, duman tahliyesi ve kıyafet tutuşması", en: "First minutes, smoke evacuation, and clothing ignition"),
            symbolName: "flame.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "F-001",
                    title: SurvivalGuideLocalizedText(tr: "Evde Yangın: İlk Dakikalar ve Tahliye", en: "Home Fire: First Minutes and Evacuation"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Sakin ol, 112'yi ara.", en: "Stay calm and call 112."),
                        SurvivalGuideLocalizedText(tr: "Güvenliyse elektrik/doğalgazı kapat.", en: "If safe, shut electricity/natural gas."),
                        SurvivalGuideLocalizedText(tr: "Yangın büyüyorsa hemen tahliye et.", en: "Evacuate immediately if fire is growing."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kapı ve pencereleri açık bırakma.", en: "Do not leave doors/windows open."),
                        SurvivalGuideLocalizedText(tr: "Tahliyede asansör kullanma.", en: "Never use elevators during evacuation."),
                        SurvivalGuideLocalizedText(tr: "Çıkamıyorsan güvenli odada kalıp görünür ol.", en: "If trapped, stay in safer room and stay visible."),
                        SurvivalGuideLocalizedText(tr: "Düşük pozisyonda ilerleyerek temiz çıkışa yönel.", en: "Move low and head to nearest safe exit."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Eşya kurtarmaya çalışma.", en: "Do not try to save belongings."),
                        SurvivalGuideLocalizedText(tr: "Duman içinde ayakta koşma.", en: "Do not run upright in smoke."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "112 arandı", en: "112 called"),
                        SurvivalGuideLocalizedText(tr: "Tahliye kararı verildi", en: "Evacuation decision made"),
                        SurvivalGuideLocalizedText(tr: "Asansör kullanılmadı", en: "Elevator avoided"),
                        SurvivalGuideLocalizedText(tr: "Güvenli çıkışa ilerleniyor", en: "Moving toward safe exit"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yangın anında 112 araması, asansör kullanmama ve hızlı tahliye öne çıkar.", en: "Calling 112, avoiding elevators, and immediate evacuation are key fire actions.")
                ),
                SurvivalGuideArticle(
                    id: "F-002",
                    title: SurvivalGuideLocalizedText(tr: "Dumanlı Ortamda Tahliye", en: "Evacuation in Smoke"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Duman yukarıda birikir: çömel veya emekle.", en: "Smoke rises: crouch or crawl."),
                        SurvivalGuideLocalizedText(tr: "Ağzı-burnu bezle kapatıp en yakın çıkışa yönel.", en: "Cover nose/mouth and move to nearest exit."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kapı kolunu elle kontrol et; aşırı sıcaksa kullanma.", en: "Check door heat; avoid very hot doors."),
                        SurvivalGuideLocalizedText(tr: "Duvar hattını takip ederek yönünü koru.", en: "Follow wall line to maintain orientation."),
                        SurvivalGuideLocalizedText(tr: "Çocuk ve yaşlıları önde yönlendir, geriden destekle.", en: "Guide children/elderly first and support from behind."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Dumanlı alanda dik yürümeye çalışma.", en: "Do not walk upright in heavy smoke."),
                        SurvivalGuideLocalizedText(tr: "Saklanma noktalarına girme.", en: "Do not hide in enclosed spots."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Düşük pozisyon alındı", en: "Low posture assumed"),
                        SurvivalGuideLocalizedText(tr: "Ağız-burun kapatıldı", en: "Mouth and nose covered"),
                        SurvivalGuideLocalizedText(tr: "En yakın çıkış belirlendi", en: "Nearest exit identified"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Dumanlı ortamlarda eğilerek/emekleyerek ilerleme önerilir.", en: "Crouching/crawling is recommended for smoke-filled environments.")
                ),
                SurvivalGuideArticle(
                    id: "F-003",
                    title: SurvivalGuideLocalizedText(tr: "Kıyafetin Tutuşursa: Dur-Yat-Yuvarlan", en: "If Clothing Catches Fire: Stop-Drop-Roll"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "DUR: Koşma.", en: "STOP: Do not run."),
                        SurvivalGuideLocalizedText(tr: "YAT: Yere yat.", en: "DROP: Get on the ground."),
                        SurvivalGuideLocalizedText(tr: "YUVARLAN: Alev sönene kadar yuvarlan.", en: "ROLL until flames are out."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yüzü ellerle koruyarak yuvarlan.", en: "Protect face with hands while rolling."),
                        SurvivalGuideLocalizedText(tr: "Mümkünse kalın kumaşla alevi boğ.", en: "If available, smother flames with thick fabric."),
                        SurvivalGuideLocalizedText(tr: "Yanık varsa soğut ve sağlık yardımı al.", en: "Cool burns and seek medical help."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Alev varken koşma veya rüzgara dönme.", en: "Do not run or face wind while burning."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Duruldu", en: "Stopped moving"),
                        SurvivalGuideLocalizedText(tr: "Yere yatıldı", en: "Dropped to ground"),
                        SurvivalGuideLocalizedText(tr: "Yuvarlanıldı", en: "Rolled to extinguish"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kıyafet tutuşmasında temel kural Dur-Yat-Yuvarlan yaklaşımıdır.", en: "Stop-Drop-Roll is the core approach for clothing fires.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "flood",
            title: SurvivalGuideLocalizedText(tr: "Sel / Taşkın", en: "Flood"),
            description: SurvivalGuideLocalizedText(tr: "Sel anında yüksek yere çıkma ve suya girmeme", en: "Move to high ground and stay out of floodwater"),
            symbolName: "drop.triangle.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "W-001",
                    title: SurvivalGuideLocalizedText(tr: "Sel Sırasında: Yüksek Yere Çık, Suya Girme", en: "During Flood: Go Higher, Stay Out of Water"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Sel bölgesini terk et, yüksek noktaya çık.", en: "Leave flooded area and go to high ground."),
                        SurvivalGuideLocalizedText(tr: "Elektrik kaynaklarından uzak dur.", en: "Stay away from electrical sources."),
                        SurvivalGuideLocalizedText(tr: "Araçla su kaplı yola girme.", en: "Do not drive into water-covered roads."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Çocukları sel suyundan uzak tut.", en: "Keep children away from floodwater."),
                        SurvivalGuideLocalizedText(tr: "Araç arızalanırsa aracı bırakıp yüksek yere çık.", en: "If car stalls, leave vehicle and move higher."),
                        SurvivalGuideLocalizedText(tr: "Gece sürüşünde su derinliğini varsayma.", en: "At night, never assume water depth."),
                        SurvivalGuideLocalizedText(tr: "112'ye konumu net ilet.", en: "Call 112 and provide precise location."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Akan suya yürüyerek veya araçla girme.", en: "Do not enter moving water by foot or vehicle."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yüksek güvenli nokta bulundu", en: "High safe point reached"),
                        SurvivalGuideLocalizedText(tr: "Elektrik kaynaklarından uzaklaşıldı", en: "Moved away from electrical risk"),
                        SurvivalGuideLocalizedText(tr: "112 bilgilendirildi", en: "112 notified"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Sel sırasında yüksek bölgeye geçiş ve su kaplı yollardan kaçınma önerilir.", en: "Flood guidance prioritizes high ground and avoiding water-covered roads.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "outage_co",
            title: SurvivalGuideLocalizedText(tr: "Kesintiler & Karbonmonoksit", en: "Outages & Carbon Monoxide"),
            description: SurvivalGuideLocalizedText(tr: "Elektrik kesintisinde CO zehirlenmesini önleme", en: "Prevent carbon monoxide poisoning during outages"),
            symbolName: "bolt.slash.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "P-001",
                    title: SurvivalGuideLocalizedText(tr: "Elektrik Kesintisi: Karbonmonoksit (CO) Riski", en: "Power Outage: Carbon Monoxide (CO) Risk"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "CO kokusuz ve görünmez; ölümcül olabilir.", en: "CO is odorless/invisible and can be fatal."),
                        SurvivalGuideLocalizedText(tr: "Jeneratör/mangal/ocak kapalı alanda kullanılmaz.", en: "Never use fuel-burning devices indoors."),
                        SurvivalGuideLocalizedText(tr: "Belirti varsa temiz havaya çık ve 112 ara.", en: "If symptoms appear, move to fresh air and call 112."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Jeneratörü ev, bodrum, garaj içinde çalıştırma.", en: "Do not run generator in home/basement/garage."),
                        SurvivalGuideLocalizedText(tr: "Dışarıda çalıştırırken kapı-pencereden uzak tut.", en: "Operate it outdoors away from doors/windows."),
                        SurvivalGuideLocalizedText(tr: "Baş ağrısı, baş dönmesi, bulantı, halsizlikte alanı terk et.", en: "Leave area if headache, dizziness, nausea, weakness occur."),
                        SurvivalGuideLocalizedText(tr: "Mümkünse pil yedekli CO alarmı kullan.", en: "Use battery-backed CO alarm when possible."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Kapıyı araladım yeter” diye düşünme.", en: "Do not assume cracked doors make indoor use safe."),
                        SurvivalGuideLocalizedText(tr: "Aracı kapalı garajda ısınmak için çalıştırma.", en: "Do not idle car in closed garage for heating."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yanmalı cihazlar iç mekandan çıkarıldı", en: "Fuel-burning devices kept outside"),
                        SurvivalGuideLocalizedText(tr: "Jeneratör açıklıklardan uzak", en: "Generator away from openings"),
                        SurvivalGuideLocalizedText(tr: "CO belirtileri takipte", en: "CO symptoms monitored"),
                        SurvivalGuideLocalizedText(tr: "CO alarmı kontrol edildi", en: "CO alarm checked"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "CO riskinde kapalı alan kullanımından kaçınmak ve belirtilerde hızlı tahliye önerilir.", en: "Avoid indoor fuel use and evacuate quickly when CO symptoms appear.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "first_aid",
            title: SurvivalGuideLocalizedText(tr: "İlk Yardım", en: "First Aid"),
            description: SurvivalGuideLocalizedText(tr: "KBB yaklaşımı, kanama, yanık, kırık ve tıkanma", en: "Protection-report-rescue, bleeding, burns, fractures, choking"),
            symbolName: "cross.case.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "FA-001",
                    title: SurvivalGuideLocalizedText(tr: "İlk Yardımın Temeli: Koruma-Bildirme-Kurtarma + ABC", en: "First Aid Basics: Protect-Report-Rescue + ABC"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "KORU: Önce olay yerini güvenli yap.", en: "PROTECT: Secure the scene first."),
                        SurvivalGuideLocalizedText(tr: "BİLDİR: 112'yi ara.", en: "REPORT: Call 112."),
                        SurvivalGuideLocalizedText(tr: "KURTAR: Bildiğin güvenli müdahaleyi yap.", en: "RESCUE: Apply safe actions you know."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "A: Hava yolu açık mı kontrol et.", en: "A: Check airway."),
                        SurvivalGuideLocalizedText(tr: "B: Nefes var mı kontrol et.", en: "B: Check breathing."),
                        SurvivalGuideLocalizedText(tr: "C: Büyük kanama var mı kontrol et.", en: "C: Check major bleeding."),
                        SurvivalGuideLocalizedText(tr: "Sürekli yeniden değerlendir ve kötüleşmede 112'yi güncelle.", en: "Reassess continuously and update 112 if worsening."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Tehlike yoksa yaralıyı gereksiz oynatma.", en: "Do not move casualty unnecessarily."),
                        SurvivalGuideLocalizedText(tr: "Bilinci kapalı kişiye ağızdan bir şey verme.", en: "Do not give anything by mouth to unconscious person."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Olay yeri güvenli", en: "Scene is safe"),
                        SurvivalGuideLocalizedText(tr: "112 haberdar", en: "112 informed"),
                        SurvivalGuideLocalizedText(tr: "ABC kontrolü yapıldı", en: "ABC checked"),
                        SurvivalGuideLocalizedText(tr: "Durum izleniyor", en: "Condition monitored"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "KBB yaklaşımı ve temel ilk yardım adımları eğitimli şekilde uygulanmalıdır.", en: "KBB-style first aid actions should be applied with training.")
                ),
                SurvivalGuideArticle(
                    id: "FA-002",
                    title: SurvivalGuideLocalizedText(tr: "Şiddetli Kanama: Bası, Bandaj, Turnike", en: "Severe Bleeding: Pressure, Bandage, Tourniquet"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "İlk hedef: kanamayı durdur.", en: "Primary goal: stop bleeding."),
                        SurvivalGuideLocalizedText(tr: "İlk adım: doğrudan bası.", en: "First step: direct pressure."),
                        SurvivalGuideLocalizedText(tr: "Hayati uzuv kanamasında bası yetmezse turnike.", en: "For life-threatening limb bleeding, use tourniquet if pressure fails."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Temiz bariyerle yaraya güçlü bası uygula.", en: "Apply strong pressure with clean barrier."),
                        SurvivalGuideLocalizedText(tr: "Bez kaldırmadan üstüne yeni bez ekle.", en: "Add cloth on top without removing soaked layer."),
                        SurvivalGuideLocalizedText(tr: "Basınçlı bandajla basıyı sürdür.", en: "Maintain pressure with pressure bandage."),
                        SurvivalGuideLocalizedText(tr: "Durmayan ağır uzuv kanamasında uygun turnike uygula ve zamanı not et.", en: "Use proper tourniquet for uncontrolled severe limb bleeding and note time."),
                        SurvivalGuideLocalizedText(tr: "112'yi ara ve şok belirtilerini izle.", en: "Call 112 and monitor shock signs."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Kanlı bezi kaldırıp pıhtıyı bozma.", en: "Do not remove cloth and break clot."),
                        SurvivalGuideLocalizedText(tr: "Turnikeyi her kanamada kullanma.", en: "Do not use tourniquet for every bleed."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Doğrudan bası uygulandı", en: "Direct pressure applied"),
                        SurvivalGuideLocalizedText(tr: "Bandajla bası sürdürülüyor", en: "Pressure maintained with bandage"),
                        SurvivalGuideLocalizedText(tr: "Turnike gerekiyorsa doğru uygulandı", en: "Tourniquet used correctly if needed"),
                        SurvivalGuideLocalizedText(tr: "112 bilgilendirildi", en: "112 informed"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kanamada bası önceliklidir; turnike belirli, ağır durumlarda uygulanır.", en: "Direct pressure is first-line; tourniquet is for specific severe cases.")
                ),
                SurvivalGuideArticle(
                    id: "FA-003",
                    title: SurvivalGuideLocalizedText(tr: "Yanıklar: Soğut, Koru, Yanlışları Yapma", en: "Burns: Cool, Protect, Avoid Mistakes"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Yanığı hemen soğut (en az 20 dk).", en: "Cool burn immediately (at least 20 min)."),
                        SurvivalGuideLocalizedText(tr: "Takıları çıkar, kabarcık patlatma.", en: "Remove jewelry, do not pop blisters."),
                        SurvivalGuideLocalizedText(tr: "Geniş yanıkta 112 ara.", en: "Call 112 for extensive burns."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Isı kaynağını kes ve güvenliği sağla.", en: "Stop heat source and secure scene."),
                        SurvivalGuideLocalizedText(tr: "Yanığı serin suyla sürekli soğut.", en: "Cool with running cool water."),
                        SurvivalGuideLocalizedText(tr: "Yüzük/saat gibi takıları erken çıkar.", en: "Remove rings/watches early."),
                        SurvivalGuideLocalizedText(tr: "Temiz bezle gevşek ört.", en: "Cover loosely with clean dressing."),
                        SurvivalGuideLocalizedText(tr: "Solunum etkilenmişse acil destek iste.", en: "Seek urgent care if breathing affected."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Diş macunu/yoğurt/yağ sürme.", en: "Do not apply toothpaste/yogurt/oil."),
                        SurvivalGuideLocalizedText(tr: "Kabarcıkları patlatma.", en: "Do not pop blisters."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Soğutma başlatıldı", en: "Cooling started"),
                        SurvivalGuideLocalizedText(tr: "Takılar çıkarıldı", en: "Jewelry removed"),
                        SurvivalGuideLocalizedText(tr: "Temiz örtü uygulandı", en: "Clean cover applied"),
                        SurvivalGuideLocalizedText(tr: "Gerekirse 112 arandı", en: "112 called when needed"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yanıkta 20 dakika soğutma ve yanlış uygulamalardan kaçınma öne çıkar.", en: "20-minute cooling and avoiding harmful home remedies are emphasized.")
                ),
                SurvivalGuideArticle(
                    id: "FA-004",
                    title: SurvivalGuideLocalizedText(tr: "Kırık / Çıkık / Burkulma", en: "Fracture / Dislocation / Sprain"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Oynatma, sabitle.", en: "Do not move; immobilize."),
                        SurvivalGuideLocalizedText(tr: "Şişlik için soğuk uygula.", en: "Apply cold for swelling."),
                        SurvivalGuideLocalizedText(tr: "112 veya sağlık birimine başvur.", en: "Call 112 or seek medical care."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Şüpheli bölgeyi bulunduğu pozisyonda tespit et.", en: "Immobilize in found position."),
                        SurvivalGuideLocalizedText(tr: "Ağrı, şekil bozukluğu, uyuşmada aciliyet artır.", en: "Escalate urgency for severe pain/deformity/numbness."),
                        SurvivalGuideLocalizedText(tr: "Soğuk uygulamayı aralıklı sürdür.", en: "Continue intermittent cold packs."),
                        SurvivalGuideLocalizedText(tr: "Açık kırıkta steril örtü ve hızlı sevk sağla.", en: "For open fracture, sterile cover and rapid transfer."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Çıkığı yerine oturtmaya çalışma.", en: "Do not attempt to reduce dislocation."),
                        SurvivalGuideLocalizedText(tr: "Açık kırıkta gereksiz müdahale yapma.", en: "Avoid unnecessary handling in open fractures."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Bölge sabitlendi", en: "Area immobilized"),
                        SurvivalGuideLocalizedText(tr: "Soğuk uygulama yapıldı", en: "Cold pack applied"),
                        SurvivalGuideLocalizedText(tr: "Acil destek planlandı", en: "Emergency support planned"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kırık/çıkıkta temel yaklaşım hareketi kısıtlamak ve tıbbi yardım istemektir.", en: "Core approach is immobilization and timely medical support.")
                ),
                SurvivalGuideArticle(
                    id: "FA-005",
                    title: SurvivalGuideLocalizedText(tr: "Solunum Yolu Tıkanıklığı (Yetişkin/Bebek)", en: "Airway Obstruction (Adult/Infant)"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Öksürüyorsa öksürmeye teşvik et.", en: "Encourage coughing if effective."),
                        SurvivalGuideLocalizedText(tr: "Tam tıkanmada: 5 sırt vuruşu, ardından 5 karın basısı (yetişkin/çocuk).", en: "For complete obstruction: 5 back blows then 5 abdominal thrusts (adult/child)."),
                        SurvivalGuideLocalizedText(tr: "Bebekte: 5 sırt vuruşu + 5 göğüs basısı.", en: "For infants: 5 back blows + 5 chest thrusts."),
                        SurvivalGuideLocalizedText(tr: "Düzelmezse 112 + temel yaşam desteği.", en: "If unresolved, call 112 and start life support."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yetişkin/çocukta 5+5 döngüsünü sürdür.", en: "Continue 5+5 cycle for adult/child."),
                        SurvivalGuideLocalizedText(tr: "Bebekte karın basısı kullanma.", en: "Never use abdominal thrusts on infants."),
                        SurvivalGuideLocalizedText(tr: "Bilinç kaybında 112 ve bildiğin CPR adımlarına geç.", en: "If unconscious, call 112 and start CPR steps you know."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Ağız içinde görmediğin cismi körlemesine çıkarma.", en: "Do not perform blind finger sweeps."),
                        SurvivalGuideLocalizedText(tr: "Bebekte yetişkin manevrası uygulama.", en: "Do not use adult maneuvers on infants."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Tıkanma tipi değerlendirildi", en: "Obstruction severity assessed"),
                        SurvivalGuideLocalizedText(tr: "Doğru yaş grubuna göre manevra uygulandı", en: "Age-appropriate maneuver applied"),
                        SurvivalGuideLocalizedText(tr: "112 hazırda", en: "112 ready/called"),
                        SurvivalGuideLocalizedText(tr: "Bilinç takibi sürüyor", en: "Consciousness monitoring ongoing"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yetişkin ve bebekte tıkanıklık algoritması farklıdır; kör parmak manevrası yapılmaz.", en: "Adult and infant choking protocols differ; blind finger sweeps are avoided.")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "psychological_first_aid",
            title: SurvivalGuideLocalizedText(tr: "Psikolojik İlk Yardım", en: "Psychological First Aid"),
            description: SurvivalGuideLocalizedText(tr: "Bak-Dinle-Bağla yaklaşımı ile kriz desteği", en: "Crisis support with Look-Listen-Link approach"),
            symbolName: "heart.text.square.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "MH-001",
                    title: SurvivalGuideLocalizedText(tr: "Psikolojik İlk Yardım: Bak-Dinle-Bağla", en: "Psychological First Aid: Look-Listen-Link"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil / Sonrası", en: "Urgent / Aftercare"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "BAK: Güvenlik ve acil ihtiyacı olanları belirle.", en: "LOOK: Identify safety risks and urgent needs."),
                        SurvivalGuideLocalizedText(tr: "DİNLE: Sakin, yargısız, kısa cümlelerle dinle.", en: "LISTEN: Stay calm, non-judgmental, brief."),
                        SurvivalGuideLocalizedText(tr: "BAĞLA: Doğru desteğe yönlendir.", en: "LINK: Connect people to proper support."),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kendini tanıt ve izinli temas kur.", en: "Introduce yourself and ask consent for contact."),
                        SurvivalGuideLocalizedText(tr: "Kişiyi konuşturmaya zorlama.", en: "Do not force them to talk."),
                        SurvivalGuideLocalizedText(tr: "Su, battaniye, güvenli alana geçiş gibi basit destek ver.", en: "Provide simple practical support (water, blanket, safer place)."),
                        SurvivalGuideLocalizedText(tr: "Aile iletişimi ve resmi bilgi kanallarına erişimi kolaylaştır.", en: "Help reconnect family communication and official information."),
                        SurvivalGuideLocalizedText(tr: "Gerektiğinde profesyonel psikososyal desteğe yönlendir.", en: "Refer to professional psychosocial support when needed."),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Bir şey olmadı” diyerek duyguyu küçümseme.", en: "Do not minimize feelings with “nothing happened”."),
                        SurvivalGuideLocalizedText(tr: "İzin almadan dokunma/sarılma.", en: "Do not touch/hug without consent."),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Güvenlik kontrolü yapıldı", en: "Safety check done"),
                        SurvivalGuideLocalizedText(tr: "Sakin dinleme sağlandı", en: "Calm listening provided"),
                        SurvivalGuideLocalizedText(tr: "Pratik ihtiyaç karşılandı", en: "Practical needs addressed"),
                        SurvivalGuideLocalizedText(tr: "Uygun desteğe yönlendirme yapıldı", en: "Linked to appropriate support"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Bak-Dinle-Bağla modeli afet sonrası psikolojik ilk yardımın temel çerçevesidir.", en: "Look-Listen-Link is a core framework for post-disaster psychological first aid.")
                ),
            ]
        ),
    ]
}
