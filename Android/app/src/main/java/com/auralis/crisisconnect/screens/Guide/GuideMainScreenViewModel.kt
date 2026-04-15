package com.auralis.crisisconnect.screens.Guide

import java.util.Locale

class GuideMainScreenViewModel {
    data class LocalizedText(
        val tr: String,
        val en: String = tr
    ) {
        fun resolve(locale: Locale): String {
            return if (locale.language.equals("tr", ignoreCase = true)) tr else en
        }
    }

    data class GuideArticle(
        val id: String,
        val title: LocalizedText,
        val priority: LocalizedText,
        val readMinutes: Int,
        val in30Seconds: List<LocalizedText>,
        val stepByStep: List<LocalizedText>,
        val dontDo: List<LocalizedText>,
        val checklist: List<LocalizedText>,
        val sourceNote: LocalizedText? = null
    )

    data class GuideCategory(
        val id: String,
        val title: LocalizedText,
        val description: LocalizedText,
        val iconRes: Int,
        val guides: List<GuideArticle>
    )

    companion object {
        private fun lt(tr: String, en: String = tr) = LocalizedText(tr = tr, en = en)

        private val PRIORITY_URGENT = lt("Acil", "Urgent")
        private val PRIORITY_PREP = lt("Hazırlık", "Preparedness")
        private val PRIORITY_URGENT_AFTER = lt("Acil / Sonrası", "Urgent / Aftercare")

        val CATEGORIES = listOf(
            GuideCategory(
                id = "emergency_info",
                title = lt("Acil Bilgiler", "Emergency Info"),
                description = lt(
                    "112, aile planı, toplanma alanı ve 72 saat çantası",
                    "911, family plan, assembly area and 72-hour go-bag"
                ),
                iconRes = android.R.drawable.ic_dialog_info,
                guides = listOf(
                    GuideArticle(
                        id = "G-001",
                        title = lt("112 ve Acil Arama Kılavuzu", "911 Emergency Call Guide"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("Hayati tehlike varsa 112'yi ara.", "Call 911 immediately for life-threatening situations."),
                            lt("Konumu net ver: il/ilçe/mahalle/sokak/bina no.", "Give exact location: city/district/neighborhood/street/building."),
                            lt("Olayı tek cümlede özetle: ne oldu, kaç kişi etkilendi.", "Summarize in one sentence: what happened and how many are affected."),
                            lt("Operatör kapat demeden telefonu kapatma.", "Do not hang up until the operator tells you.")
                        ),
                        stepByStep = listOf(
                            lt("Kesin adres + yakın işaret (okul, market, cami, metro).", "Share exact address and nearest landmark."),
                            lt("Kim arıyor + geri aranacak telefon numarası.", "State caller name and callback number."),
                            lt("Olay türü: deprem, yangın, yaralanma, mahsur kalma.", "State incident type: quake, fire, injury, trapped person."),
                            lt("Kişi sayısı ve durum: bilinç, nefes, kanama.", "State victim count and condition: consciousness, breathing, bleeding."),
                            lt("Ek riskler: gaz kokusu, duman, çökme riski.", "Mention extra risks: gas odor, smoke, collapse risk.")
                        ),
                        dontDo = listOf(
                            lt("Panikle bağırıp adres bilgisini atlama.", "Do not skip address details due to panic."),
                            lt("“Tam bilmiyorum” deyip hattı belirsiz bırakma.", "Do not leave the call vague with “I don't know”.")
                        ),
                        checklist = listOf(
                            lt("Adres hazır", "Address ready"),
                            lt("Olay türü hazır", "Incident type ready"),
                            lt("Yaralı sayısı hazır", "Victim count ready"),
                            lt("Ek risk bilgisi hazır", "Additional risk info ready")
                        ),
                        sourceNote = lt(
                            "112 tek acil numaradır; adres, olay tanımı ve kişi durumu net verilmelidir.",
                            "911 is the single emergency number; location, incident and victim status must be clear."
                        )
                    ),
                    GuideArticle(
                        id = "G-002",
                        title = lt("Aile Acil Durum Planı", "Family Emergency Plan"),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("1 şehir dışı irtibat kişisi belirle.", "Assign one out-of-city contact."),
                            lt("2 buluşma noktası seç: mahalle içi + mahalle dışı.", "Set two meeting points: local and outside neighborhood."),
                            lt("Herkeste acil kartı olsun: kimlik kopyası + numaralar.", "Everyone carries an emergency card: ID copy + key numbers.")
                        ),
                        stepByStep = listOf(
                            lt("Aynı irtibat kişisini tüm aile kullansın.", "All family members contact the same person."),
                            lt("Hat yoğunluğunda SMS kuralını kullan.", "Use SMS when voice lines are congested."),
                            lt("Plan sırası: Ev yoksa nokta-1, olmazsa nokta-2.", "Protocol: if home fails use point-1, then point-2."),
                            lt("Rol dağıt: çanta, çocuk/yaşlı, evcil hayvan sorumlusu.", "Assign roles: bag, children/elderly, pets.")
                        ),
                        dontDo = listOf(
                            lt("Tek buluşma noktasına güvenme.", "Do not rely on only one meeting point."),
                            lt("Herkes farklı kişiyi aramasın.", "Do not scatter information by calling different people.")
                        ),
                        checklist = listOf(
                            lt("Şehir dışı kişi seçildi", "Out-of-city contact selected"),
                            lt("2 buluşma noktası kaydedildi", "Two meeting points saved"),
                            lt("Acil kartlar hazırlandı", "Emergency cards prepared"),
                            lt("Rol dağıtımı yapıldı", "Roles assigned")
                        ),
                        sourceNote = lt(
                            "Aile planlarında şehir dışı irtibat ve SMS odaklı iletişim önerilir.",
                            "Family plans recommend one out-of-area contact and SMS-first communication."
                        )
                    ),
                    GuideArticle(
                        id = "G-003",
                        title = lt(
                            "Toplanma Alanı Nedir? E-Devlet'ten Nasıl Bulunur?",
                            "What Is an Assembly Area? How to Find It via e-Devlet"
                        ),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Toplanma alanı afet sonrası ilk güvenli buluşma noktasıdır.", "Assembly area is the first safe post-disaster meeting point."),
                            lt("E-Devlet'te “Toplanma Alanı Sorgulama” ile bulunur.", "Find it in e-Devlet with “Assembly Area Query”."),
                            lt("En yakın alanın adres ve konumunu kaydet.", "Save nearest area address and map location.")
                        ),
                        stepByStep = listOf(
                            lt("E-Devlet'e giriş yap.", "Sign in to e-Devlet."),
                            lt("Arama kutusuna “Toplanma Alanı Sorgulama” yaz.", "Search for “Toplanma Alanı Sorgulama”."),
                            lt("Haritada en yakını seç ve kaydet.", "Pick the nearest on map and save it."),
                            lt("Aile ile paylaş, 2 alternatif rota çıkar.", "Share with family and define two routes.")
                        ),
                        dontDo = listOf(
                            lt("Toplanma alanını barınma alanı ile karıştırma.", "Do not confuse assembly area with shelter area.")
                        ),
                        checklist = listOf(
                            lt("En yakın toplanma alanı bulundu", "Nearest assembly area found"),
                            lt("Adres + koordinat kaydedildi", "Address + coordinates saved"),
                            lt("Aile ile paylaşıldı", "Shared with family"),
                            lt("Alternatif rotalar çizildi", "Alternative routes planned")
                        ),
                        sourceNote = lt(
                            "Toplanma alanı sorgulaması AFAD/e-Devlet üzerinden resmi olarak sunulur.",
                            "Assembly area lookup is officially provided via AFAD/e-Devlet."
                        )
                    ),
                    GuideArticle(
                        id = "G-004",
                        title = lt(
                            "Afet & Acil Durum Çantası (72 Saat)",
                            "Disaster Go-Bag (72 Hours)"
                        ),
                        priority = PRIORITY_PREP,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Hedef: ilk 72 saati kendi başına yönetebilmek.", "Goal: self-manage the first 72 hours."),
                            lt("Omurga: su, gıda, ilk yardım, ışık, iletişim, evrak.", "Core: water, food, first aid, light, communication, documents."),
                            lt("Çanta kapıya yakın ve erişilebilir yerde olsun.", "Keep the bag near the exit and easy to reach.")
                        ),
                        stepByStep = listOf(
                            lt("Su + bozulmayan gıda + konserve açacağı ekle.", "Pack water, non-perishable food, and can opener."),
                            lt("Düzenli ilaçlar + reçete + temel ilk yardım seti koy.", "Add regular meds, prescriptions, and first aid kit."),
                            lt("Fener, pil, powerbank, kablo ve mümkünse radyo koy.", "Add flashlight, batteries, powerbank, cable, and radio."),
                            lt("Kimlik ve kritik belge fotokopileri + bir miktar nakit ekle.", "Add ID/critical document copies and cash."),
                            lt("Bebek/yaşlı/engelli/evcil için özel malzemeyi unutma.", "Include special items for babies/elderly/disabled/pets.")
                        ),
                        dontDo = listOf(
                            lt("Çantayı evde ulaşılmaz yere kaldırma.", "Do not store the bag in an unreachable place."),
                            lt("Son kullanma tarihi kontrolünü atlama.", "Do not skip expiry-date checks.")
                        ),
                        checklist = listOf(
                            lt("Su ve gıda hazır", "Water and food packed"),
                            lt("İlk yardım ve ilaç hazır", "First aid and medications packed"),
                            lt("Işık/iletişim ekipmanı hazır", "Light/communication kit packed"),
                            lt("Belge ve nakit hazır", "Documents and cash packed"),
                            lt("Özel ihtiyaç malzemeleri hazır", "Special-needs supplies packed")
                        ),
                        sourceNote = lt(
                            "AFAD dayanıklı gıda ve önemli belge fotokopilerini çantada önerir.",
                            "AFAD recommends durable food and copies of critical documents in the go-bag."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "earthquake",
                title = lt("Deprem", "Earthquake"),
                description = lt(
                    "Deprem öncesi hazırlık, deprem anı ve ilk 30 dakika",
                    "Preparedness, immediate response, and first 30 minutes"
                ),
                iconRes = android.R.drawable.ic_dialog_alert,
                guides = listOf(
                    GuideArticle(
                        id = "E-001",
                        title = lt("Deprem Öncesi Ev Güvenliği (Risk Azaltma)", "Home Safety Before Earthquakes"),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Dolap, raf, TV gibi devrilebilir eşyaları sabitle.", "Anchor wardrobes, shelves, and TVs."),
                            lt("Ağır eşyaları alt raflara al.", "Move heavy items to lower shelves."),
                            lt("Kaçış yolunu açık tut ve mini tatbikat yap.", "Keep exit path clear and run mini drills.")
                        ),
                        stepByStep = listOf(
                            lt("Yatak başını cam/pencereden uzaklaştır.", "Keep bed away from windows/glass."),
                            lt("Kapı önü ve koridoru daima boş bırak.", "Keep doorways and corridors clear."),
                            lt("Gaz, elektrik, su vanalarının yerini öğren.", "Know where gas, electric, and water shutoffs are."),
                            lt("Evde Çök-Kapan-Tutun pratiği yap.", "Practice Drop-Cover-Hold at home.")
                        ),
                        dontDo = listOf(
                            lt("Ağır çerçeve/ayna gibi objeleri yatak üstüne asma.", "Do not hang heavy mirrors/frames above beds.")
                        ),
                        checklist = listOf(
                            lt("Mobilya sabitleme tamam", "Furniture anchoring complete"),
                            lt("Kaçış rotası temiz", "Exit route clear"),
                            lt("Vana noktaları öğrenildi", "Shutoff points known"),
                            lt("Ev tatbikatı yapıldı", "Home drill completed")
                        ),
                        sourceNote = lt(
                            "Deprem öncesinde sabitleme ve deprem anında korunma davranışı kritik görülür.",
                            "Anchoring before a quake and protective posture during a quake are critical."
                        )
                    ),
                    GuideArticle(
                        id = "E-002",
                        title = lt("Deprem Anında: Çök-Kapan-Tutun", "During an Earthquake: Drop-Cover-Hold"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("ÇÖK-KAPAN-TUTUN uygula.", "Apply DROP-COVER-HOLD."),
                            lt("Cam/pencere ve düşebilecek eşyalardan uzak dur.", "Stay away from windows and falling objects."),
                            lt("Asansör, merdiven, balkon tarafına koşma.", "Do not run toward elevator, stairs, or balcony.")
                        ),
                        stepByStep = listOf(
                            lt("Sağlam masa yanında veya altında pozisyon al.", "Take position under/next to sturdy furniture."),
                            lt("Diz üstüne çök, baş-boynu koruyarak kapan.", "Drop to knees and cover head/neck."),
                            lt("Sabit noktaya tutun ve sarsıntı bitene kadar kal.", "Hold on and stay until shaking ends."),
                            lt("Dışarıdaysan bina ve direklerden uzak açık alana geç.", "If outside, move away from buildings and poles."),
                            lt("Araç içindeysen güvenli yerde dur, araçta kal.", "If in a vehicle, stop safely and stay inside.")
                        ),
                        dontDo = listOf(
                            lt("Panikle dışarı fırlama.", "Do not rush outside in panic.")
                        ),
                        checklist = listOf(
                            lt("Güvenli nokta bul", "Find a safe spot"),
                            lt("Baş-boyun koru", "Protect head and neck"),
                            lt("Sarsıntı bitene kadar pozisyonu koru", "Hold position until shaking stops")
                        ),
                        sourceNote = lt(
                            "Deprem anında Çök-Kapan-Tutun ve camdan uzak kalma temel yaklaşımdır.",
                            "Drop-Cover-Hold and staying away from glass are core guidance."
                        )
                    ),
                    GuideArticle(
                        id = "E-003",
                        title = lt("Deprem Sonrası: İlk 30 Dakika", "After Earthquake: First 30 Minutes"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Önce kendi güvenliğini sağla.", "Secure your own safety first."),
                            lt("Gaz kokusu varsa gazı kapat, havalandır, binayı terk et.", "If gas odor exists, shut gas, ventilate, and evacuate."),
                            lt("Çantanı al ve toplanma alanına git.", "Take go-bag and move to assembly area.")
                        ),
                        stepByStep = listOf(
                            lt("Yaralı kontrolü yap, 112'ye bilgi ver.", "Check injuries and call 911."),
                            lt("Duman, çökme, gaz riski var mı değerlendir.", "Assess smoke, collapse, gas risks."),
                            lt("Uygunsa elektrik-gaz-su vanalarını kapat.", "If safe, shut electricity-gas-water."),
                            lt("Önceden belirlenen rotadan binayı terk et.", "Evacuate via pre-planned route."),
                            lt("Resmi duyuruları takip et, yolları acil araçlara bırak.", "Follow official updates and keep roads clear.")
                        ),
                        dontDo = listOf(
                            lt("Hasarlı binaya geri girme.", "Do not re-enter damaged buildings."),
                            lt("Gereksiz araç kullanıp trafiği kilitleme.", "Do not block roads with unnecessary driving.")
                        ),
                        checklist = listOf(
                            lt("Yaralı kontrolü yapıldı", "Injury check completed"),
                            lt("Vana kontrolleri yapıldı", "Utility shutoff check completed"),
                            lt("Çanta alındı", "Go-bag taken"),
                            lt("Toplanma alanına çıkıldı", "Moved to assembly area")
                        ),
                        sourceNote = lt(
                            "Deprem sonrasında vana kontrolü, bina tahliyesi ve toplanma alanına geçiş önerilir.",
                            "Post-quake guidance includes utility shutoff, evacuation, and assembly-area transfer."
                        )
                    ),
                    GuideArticle(
                        id = "E-004",
                        title = lt("Artçı Depremler ve Hasarlı Binalar", "Aftershocks and Damaged Buildings"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Büyük deprem sonrası artçı beklenir.", "After major quakes, aftershocks are expected."),
                            lt("Hasarlı binaya girme.", "Do not enter damaged structures."),
                            lt("Artçıda yine Çök-Kapan-Tutun.", "Use Drop-Cover-Hold during aftershocks.")
                        ),
                        stepByStep = listOf(
                            lt("Resmi yönlendirme gelmeden eve dönme.", "Do not return home before official guidance."),
                            lt("Toplanma alanında kal ve duyuruları izle.", "Stay at assembly area and monitor updates."),
                            lt("Artçı başladığında yakındaki güvenli pozisyonu al.", "Take nearby protective position when aftershock starts.")
                        ),
                        dontDo = listOf(
                            lt("“Bir şey olmaz” deyip çatlak binada bekleme.", "Do not stay in cracked buildings assuming safety.")
                        ),
                        checklist = listOf(
                            lt("Artçı riski hatırlandı", "Aftershock risk acknowledged"),
                            lt("Hasarlı bina terk edildi", "Damaged building avoided"),
                            lt("Resmi duyurular takipte", "Official alerts monitored")
                        ),
                        sourceNote = lt(
                            "Artçılar devam ederken hasarlı yapılara girilmemesi vurgulanır.",
                            "Guidance stresses avoiding damaged buildings while aftershocks continue."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "fire",
                title = lt("Yangın", "Fire"),
                description = lt(
                    "Yangında ilk dakikalar, duman tahliyesi ve kıyafet tutuşması",
                    "First minutes, smoke evacuation, and clothing ignition"
                ),
                iconRes = android.R.drawable.ic_menu_compass,
                guides = listOf(
                    GuideArticle(
                        id = "F-001",
                        title = lt("Evde Yangın: İlk Dakikalar ve Tahliye", "Home Fire: First Minutes and Evacuation"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Sakin ol, 112'yi ara.", "Stay calm and call 911."),
                            lt("Güvenliyse elektrik/doğalgazı kapat.", "If safe, shut electricity/natural gas."),
                            lt("Yangın büyüyorsa hemen tahliye et.", "Evacuate immediately if fire is growing.")
                        ),
                        stepByStep = listOf(
                            lt("Kapı ve pencereleri açık bırakma.", "Do not leave doors/windows open."),
                            lt("Tahliyede asansör kullanma.", "Never use elevators during evacuation."),
                            lt("Çıkamıyorsan güvenli odada kalıp görünür ol.", "If trapped, stay in safer room and stay visible."),
                            lt("Düşük pozisyonda ilerleyerek temiz çıkışa yönel.", "Move low and head to nearest safe exit.")
                        ),
                        dontDo = listOf(
                            lt("Eşya kurtarmaya çalışma.", "Do not try to save belongings."),
                            lt("Duman içinde ayakta koşma.", "Do not run upright in smoke.")
                        ),
                        checklist = listOf(
                            lt("112 arandı", "911 called"),
                            lt("Tahliye kararı verildi", "Evacuation decision made"),
                            lt("Asansör kullanılmadı", "Elevator avoided"),
                            lt("Güvenli çıkışa ilerleniyor", "Moving toward safe exit")
                        ),
                        sourceNote = lt(
                            "Yangın anında 112 araması, asansör kullanmama ve hızlı tahliye öne çıkar.",
                            "Calling 911, avoiding elevators, and immediate evacuation are key fire actions."
                        )
                    ),
                    GuideArticle(
                        id = "F-002",
                        title = lt("Dumanlı Ortamda Tahliye", "Evacuation in Smoke"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("Duman yukarıda birikir: çömel veya emekle.", "Smoke rises: crouch or crawl."),
                            lt("Ağzı-burnu bezle kapatıp en yakın çıkışa yönel.", "Cover nose/mouth and move to nearest exit.")
                        ),
                        stepByStep = listOf(
                            lt("Kapı kolunu elle kontrol et; aşırı sıcaksa kullanma.", "Check door heat; avoid very hot doors."),
                            lt("Duvar hattını takip ederek yönünü koru.", "Follow wall line to maintain orientation."),
                            lt("Çocuk ve yaşlıları önde yönlendir, geriden destekle.", "Guide children/elderly first and support from behind.")
                        ),
                        dontDo = listOf(
                            lt("Dumanlı alanda dik yürümeye çalışma.", "Do not walk upright in heavy smoke."),
                            lt("Saklanma noktalarına girme.", "Do not hide in enclosed spots.")
                        ),
                        checklist = listOf(
                            lt("Düşük pozisyon alındı", "Low posture assumed"),
                            lt("Ağız-burun kapatıldı", "Mouth and nose covered"),
                            lt("En yakın çıkış belirlendi", "Nearest exit identified")
                        ),
                        sourceNote = lt(
                            "Dumanlı ortamlarda eğilerek/emekleyerek ilerleme önerilir.",
                            "Crouching/crawling is recommended for smoke-filled environments."
                        )
                    ),
                    GuideArticle(
                        id = "F-003",
                        title = lt("Kıyafetin Tutuşursa: Dur-Yat-Yuvarlan", "If Clothing Catches Fire: Stop-Drop-Roll"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("DUR: Koşma.", "STOP: Do not run."),
                            lt("YAT: Yere yat.", "DROP: Get on the ground."),
                            lt("YUVARLAN: Alev sönene kadar yuvarlan.", "ROLL until flames are out.")
                        ),
                        stepByStep = listOf(
                            lt("Yüzü ellerle koruyarak yuvarlan.", "Protect face with hands while rolling."),
                            lt("Mümkünse kalın kumaşla alevi boğ.", "If available, smother flames with thick fabric."),
                            lt("Yanık varsa soğut ve sağlık yardımı al.", "Cool burns and seek medical help.")
                        ),
                        dontDo = listOf(
                            lt("Alev varken koşma veya rüzgara dönme.", "Do not run or face wind while burning.")
                        ),
                        checklist = listOf(
                            lt("Duruldu", "Stopped moving"),
                            lt("Yere yatıldı", "Dropped to ground"),
                            lt("Yuvarlanıldı", "Rolled to extinguish")
                        ),
                        sourceNote = lt(
                            "Kıyafet tutuşmasında temel kural Dur-Yat-Yuvarlan yaklaşımıdır.",
                            "Stop-Drop-Roll is the core approach for clothing fires."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "flood",
                title = lt("Sel / Taşkın", "Flood"),
                description = lt(
                    "Sel anında yüksek yere çıkma ve suya girmeme",
                    "Move to high ground and stay out of floodwater"
                ),
                iconRes = android.R.drawable.ic_menu_directions,
                guides = listOf(
                    GuideArticle(
                        id = "W-001",
                        title = lt("Sel Sırasında: Yüksek Yere Çık, Suya Girme", "During Flood: Go Higher, Stay Out of Water"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Sel bölgesini terk et, yüksek noktaya çık.", "Leave flooded area and go to high ground."),
                            lt("Elektrik kaynaklarından uzak dur.", "Stay away from electrical sources."),
                            lt("Araçla su kaplı yola girme.", "Do not drive into water-covered roads.")
                        ),
                        stepByStep = listOf(
                            lt("Çocukları sel suyundan uzak tut.", "Keep children away from floodwater."),
                            lt("Araç arızalanırsa aracı bırakıp yüksek yere çık.", "If car stalls, leave vehicle and move higher."),
                            lt("Gece sürüşünde su derinliğini varsayma.", "At night, never assume water depth."),
                            lt("112'ye konumu net ilet.", "Call 911 and provide precise location.")
                        ),
                        dontDo = listOf(
                            lt("Akan suya yürüyerek veya araçla girme.", "Do not enter moving water by foot or vehicle.")
                        ),
                        checklist = listOf(
                            lt("Yüksek güvenli nokta bulundu", "High safe point reached"),
                            lt("Elektrik kaynaklarından uzaklaşıldı", "Moved away from electrical risk"),
                            lt("112 bilgilendirildi", "911 notified")
                        ),
                        sourceNote = lt(
                            "Sel sırasında yüksek bölgeye geçiş ve su kaplı yollardan kaçınma önerilir.",
                            "Flood guidance prioritizes high ground and avoiding water-covered roads."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "outage_co",
                title = lt("Kesintiler & Karbonmonoksit", "Outages & Carbon Monoxide"),
                description = lt(
                    "Elektrik kesintisinde CO zehirlenmesini önleme",
                    "Prevent carbon monoxide poisoning during outages"
                ),
                iconRes = android.R.drawable.ic_lock_power_off,
                guides = listOf(
                    GuideArticle(
                        id = "P-001",
                        title = lt("Elektrik Kesintisi: Karbonmonoksit (CO) Riski", "Power Outage: Carbon Monoxide (CO) Risk"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("CO kokusuz ve görünmez; ölümcül olabilir.", "CO is odorless/invisible and can be fatal."),
                            lt("Jeneratör/mangal/ocak kapalı alanda kullanılmaz.", "Never use fuel-burning devices indoors."),
                            lt("Belirti varsa temiz havaya çık ve 112 ara.", "If symptoms appear, move to fresh air and call 911.")
                        ),
                        stepByStep = listOf(
                            lt("Jeneratörü ev, bodrum, garaj içinde çalıştırma.", "Do not run generator in home/basement/garage."),
                            lt("Dışarıda çalıştırırken kapı-pencereden uzak tut.", "Operate it outdoors away from doors/windows."),
                            lt("Baş ağrısı, baş dönmesi, bulantı, halsizlikte alanı terk et.", "Leave area if headache, dizziness, nausea, weakness occur."),
                            lt("Mümkünse pil yedekli CO alarmı kullan.", "Use battery-backed CO alarm when possible.")
                        ),
                        dontDo = listOf(
                            lt("“Kapıyı araladım yeter” diye düşünme.", "Do not assume cracked doors make indoor use safe."),
                            lt("Aracı kapalı garajda ısınmak için çalıştırma.", "Do not idle car in closed garage for heating.")
                        ),
                        checklist = listOf(
                            lt("Yanmalı cihazlar iç mekandan çıkarıldı", "Fuel-burning devices kept outside"),
                            lt("Jeneratör açıklıklardan uzak", "Generator away from openings"),
                            lt("CO belirtileri takipte", "CO symptoms monitored"),
                            lt("CO alarmı kontrol edildi", "CO alarm checked")
                        ),
                        sourceNote = lt(
                            "CO riskinde kapalı alan kullanımından kaçınmak ve belirtilerde hızlı tahliye önerilir.",
                            "Avoid indoor fuel use and evacuate quickly when CO symptoms appear."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "first_aid",
                title = lt("İlk Yardım", "First Aid"),
                description = lt(
                    "KBB yaklaşımı, kanama, yanık, kırık ve tıkanma",
                    "Protection-report-rescue, bleeding, burns, fractures, choking"
                ),
                iconRes = android.R.drawable.ic_menu_info_details,
                guides = listOf(
                    GuideArticle(
                        id = "FA-001",
                        title = lt(
                            "İlk Yardımın Temeli: Koruma-Bildirme-Kurtarma + ABC",
                            "First Aid Basics: Protect-Report-Rescue + ABC"
                        ),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("KORU: Önce olay yerini güvenli yap.", "PROTECT: Secure the scene first."),
                            lt("BİLDİR: 112'yi ara.", "REPORT: Call 911."),
                            lt("KURTAR: Bildiğin güvenli müdahaleyi yap.", "RESCUE: Apply safe actions you know.")
                        ),
                        stepByStep = listOf(
                            lt("A: Hava yolu açık mı kontrol et.", "A: Check airway."),
                            lt("B: Nefes var mı kontrol et.", "B: Check breathing."),
                            lt("C: Büyük kanama var mı kontrol et.", "C: Check major bleeding."),
                            lt("Sürekli yeniden değerlendir ve kötüleşmede 112'yi güncelle.", "Reassess continuously and update 911 if worsening.")
                        ),
                        dontDo = listOf(
                            lt("Tehlike yoksa yaralıyı gereksiz oynatma.", "Do not move casualty unnecessarily."),
                            lt("Bilinci kapalı kişiye ağızdan bir şey verme.", "Do not give anything by mouth to unconscious person.")
                        ),
                        checklist = listOf(
                            lt("Olay yeri güvenli", "Scene is safe"),
                            lt("112 haberdar", "911 informed"),
                            lt("ABC kontrolü yapıldı", "ABC checked"),
                            lt("Durum izleniyor", "Condition monitored")
                        ),
                        sourceNote = lt(
                            "KBB yaklaşımı ve temel ilk yardım adımları eğitimli şekilde uygulanmalıdır.",
                            "KBB-style first aid actions should be applied with training."
                        )
                    ),
                    GuideArticle(
                        id = "FA-002",
                        title = lt("Şiddetli Kanama: Bası, Bandaj, Turnike", "Severe Bleeding: Pressure, Bandage, Tourniquet"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("İlk hedef: kanamayı durdur.", "Primary goal: stop bleeding."),
                            lt("İlk adım: doğrudan bası.", "First step: direct pressure."),
                            lt("Hayati uzuv kanamasında bası yetmezse turnike.", "For life-threatening limb bleeding, use tourniquet if pressure fails.")
                        ),
                        stepByStep = listOf(
                            lt("Temiz bariyerle yaraya güçlü bası uygula.", "Apply strong pressure with clean barrier."),
                            lt("Bez kaldırmadan üstüne yeni bez ekle.", "Add cloth on top without removing soaked layer."),
                            lt("Basınçlı bandajla basıyı sürdür.", "Maintain pressure with pressure bandage."),
                            lt("Durmayan ağır uzuv kanamasında uygun turnike uygula ve zamanı not et.", "Use proper tourniquet for uncontrolled severe limb bleeding and note time."),
                            lt("112'yi ara ve şok belirtilerini izle.", "Call 911 and monitor shock signs.")
                        ),
                        dontDo = listOf(
                            lt("Kanlı bezi kaldırıp pıhtıyı bozma.", "Do not remove cloth and break clot."),
                            lt("Turnikeyi her kanamada kullanma.", "Do not use tourniquet for every bleed.")
                        ),
                        checklist = listOf(
                            lt("Doğrudan bası uygulandı", "Direct pressure applied"),
                            lt("Bandajla bası sürdürülüyor", "Pressure maintained with bandage"),
                            lt("Turnike gerekiyorsa doğru uygulandı", "Tourniquet used correctly if needed"),
                            lt("112 bilgilendirildi", "911 informed")
                        ),
                        sourceNote = lt(
                            "Kanamada bası önceliklidir; turnike belirli, ağır durumlarda uygulanır.",
                            "Direct pressure is first-line; tourniquet is for specific severe cases."
                        )
                    ),
                    GuideArticle(
                        id = "FA-003",
                        title = lt("Yanıklar: Soğut, Koru, Yanlışları Yapma", "Burns: Cool, Protect, Avoid Mistakes"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Yanığı hemen soğut (en az 20 dk).", "Cool burn immediately (at least 20 min)."),
                            lt("Takıları çıkar, kabarcık patlatma.", "Remove jewelry, do not pop blisters."),
                            lt("Geniş yanıkta 112 ara.", "Call 911 for extensive burns.")
                        ),
                        stepByStep = listOf(
                            lt("Isı kaynağını kes ve güvenliği sağla.", "Stop heat source and secure scene."),
                            lt("Yanığı serin suyla sürekli soğut.", "Cool with running cool water."),
                            lt("Yüzük/saat gibi takıları erken çıkar.", "Remove rings/watches early."),
                            lt("Temiz bezle gevşek ört.", "Cover loosely with clean dressing."),
                            lt("Solunum etkilenmişse acil destek iste.", "Seek urgent care if breathing affected.")
                        ),
                        dontDo = listOf(
                            lt("Diş macunu/yoğurt/yağ sürme.", "Do not apply toothpaste/yogurt/oil."),
                            lt("Kabarcıkları patlatma.", "Do not pop blisters.")
                        ),
                        checklist = listOf(
                            lt("Soğutma başlatıldı", "Cooling started"),
                            lt("Takılar çıkarıldı", "Jewelry removed"),
                            lt("Temiz örtü uygulandı", "Clean cover applied"),
                            lt("Gerekirse 112 arandı", "911 called when needed")
                        ),
                        sourceNote = lt(
                            "Yanıkta 20 dakika soğutma ve yanlış uygulamalardan kaçınma öne çıkar.",
                            "20-minute cooling and avoiding harmful home remedies are emphasized."
                        )
                    ),
                    GuideArticle(
                        id = "FA-004",
                        title = lt("Kırık / Çıkık / Burkulma", "Fracture / Dislocation / Sprain"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Oynatma, sabitle.", "Do not move; immobilize."),
                            lt("Şişlik için soğuk uygula.", "Apply cold for swelling."),
                            lt("112 veya sağlık birimine başvur.", "Call 911 or seek medical care.")
                        ),
                        stepByStep = listOf(
                            lt("Şüpheli bölgeyi bulunduğu pozisyonda tespit et.", "Immobilize in found position."),
                            lt("Ağrı, şekil bozukluğu, uyuşmada aciliyet artır.", "Escalate urgency for severe pain/deformity/numbness."),
                            lt("Soğuk uygulamayı aralıklı sürdür.", "Continue intermittent cold packs."),
                            lt("Açık kırıkta steril örtü ve hızlı sevk sağla.", "For open fracture, sterile cover and rapid transfer.")
                        ),
                        dontDo = listOf(
                            lt("Çıkığı yerine oturtmaya çalışma.", "Do not attempt to reduce dislocation."),
                            lt("Açık kırıkta gereksiz müdahale yapma.", "Avoid unnecessary handling in open fractures.")
                        ),
                        checklist = listOf(
                            lt("Bölge sabitlendi", "Area immobilized"),
                            lt("Soğuk uygulama yapıldı", "Cold pack applied"),
                            lt("Acil destek planlandı", "Emergency support planned")
                        ),
                        sourceNote = lt(
                            "Kırık/çıkıkta temel yaklaşım hareketi kısıtlamak ve tıbbi yardım istemektir.",
                            "Core approach is immobilization and timely medical support."
                        )
                    ),
                    GuideArticle(
                        id = "FA-005",
                        title = lt(
                            "Solunum Yolu Tıkanıklığı (Yetişkin/Bebek)",
                            "Airway Obstruction (Adult/Infant)"
                        ),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Öksürüyorsa öksürmeye teşvik et.", "Encourage coughing if effective."),
                            lt("Tam tıkanmada: 5 sırt vuruşu, ardından 5 karın basısı (yetişkin/çocuk).", "For complete obstruction: 5 back blows then 5 abdominal thrusts (adult/child)."),
                            lt("Bebekte: 5 sırt vuruşu + 5 göğüs basısı.", "For infants: 5 back blows + 5 chest thrusts."),
                            lt("Düzelmezse 112 + temel yaşam desteği.", "If unresolved, call 911 and start life support.")
                        ),
                        stepByStep = listOf(
                            lt("Yetişkin/çocukta 5+5 döngüsünü sürdür.", "Continue 5+5 cycle for adult/child."),
                            lt("Bebekte karın basısı kullanma.", "Never use abdominal thrusts on infants."),
                            lt("Bilinç kaybında 112 ve bildiğin CPR adımlarına geç.", "If unconscious, call 911 and start CPR steps you know.")
                        ),
                        dontDo = listOf(
                            lt("Ağız içinde görmediğin cismi körlemesine çıkarma.", "Do not perform blind finger sweeps."),
                            lt("Bebekte yetişkin manevrası uygulama.", "Do not use adult maneuvers on infants.")
                        ),
                        checklist = listOf(
                            lt("Tıkanma tipi değerlendirildi", "Obstruction severity assessed"),
                            lt("Doğru yaş grubuna göre manevra uygulandı", "Age-appropriate maneuver applied"),
                            lt("112 hazırda", "911 ready/called"),
                            lt("Bilinç takibi sürüyor", "Consciousness monitoring ongoing")
                        ),
                        sourceNote = lt(
                            "Yetişkin ve bebekte tıkanıklık algoritması farklıdır; kör parmak manevrası yapılmaz.",
                            "Adult and infant choking protocols differ; blind finger sweeps are avoided."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "psychological_first_aid",
                title = lt("Psikolojik İlk Yardım", "Psychological First Aid"),
                description = lt(
                    "Bak-Dinle-Bağla yaklaşımı ile kriz desteği",
                    "Crisis support with Look-Listen-Link approach"
                ),
                iconRes = android.R.drawable.ic_menu_help,
                guides = listOf(
                    GuideArticle(
                        id = "MH-001",
                        title = lt("Psikolojik İlk Yardım: Bak-Dinle-Bağla", "Psychological First Aid: Look-Listen-Link"),
                        priority = PRIORITY_URGENT_AFTER,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("BAK: Güvenlik ve acil ihtiyacı olanları belirle.", "LOOK: Identify safety risks and urgent needs."),
                            lt("DİNLE: Sakin, yargısız, kısa cümlelerle dinle.", "LISTEN: Stay calm, non-judgmental, brief."),
                            lt("BAĞLA: Doğru desteğe yönlendir.", "LINK: Connect people to proper support.")
                        ),
                        stepByStep = listOf(
                            lt("Kendini tanıt ve izinli temas kur.", "Introduce yourself and ask consent for contact."),
                            lt("Kişiyi konuşturmaya zorlama.", "Do not force them to talk."),
                            lt("Su, battaniye, güvenli alana geçiş gibi basit destek ver.", "Provide simple practical support (water, blanket, safer place)."),
                            lt("Aile iletişimi ve resmi bilgi kanallarına erişimi kolaylaştır.", "Help reconnect family communication and official information."),
                            lt("Gerektiğinde profesyonel psikososyal desteğe yönlendir.", "Refer to professional psychosocial support when needed.")
                        ),
                        dontDo = listOf(
                            lt("“Bir şey olmadı” diyerek duyguyu küçümseme.", "Do not minimize feelings with “nothing happened”."),
                            lt("İzin almadan dokunma/sarılma.", "Do not touch/hug without consent.")
                        ),
                        checklist = listOf(
                            lt("Güvenlik kontrolü yapıldı", "Safety check done"),
                            lt("Sakin dinleme sağlandı", "Calm listening provided"),
                            lt("Pratik ihtiyaç karşılandı", "Practical needs addressed"),
                            lt("Uygun desteğe yönlendirme yapıldı", "Linked to appropriate support")
                        ),
                        sourceNote = lt(
                            "Bak-Dinle-Bağla modeli afet sonrası psikolojik ilk yardımın temel çerçevesidir.",
                            "Look-Listen-Link is a core framework for post-disaster psychological first aid."
                        )
                    )
                )
            )
        )
    }
}
