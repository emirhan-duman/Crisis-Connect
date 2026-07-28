//
//  SurvivalGuideData.swift
//  Crisis Connect
//
//  Generated from Android GuideMainScreenViewModel.kt for cross-platform parity.
//
//  IMPORTANT: Japanese (ja) translations produced by Claude (AI).
//  Crisis Connect is a safety-critical emergency-coordination app.
//  Mistranslation can cause physical harm.
//  NATIVE SPEAKER REVIEW IS REQUIRED BEFORE PRODUCTION RELEASE.
//  Reviewer checklist: ですます consistency, imperative てください form,
//  preserve emergency-number semantics (119 = fire/ambulance in Japan),
//  verify 要救助者 vs 被害者 usage in life-safety contexts.
//

import Foundation

struct SurvivalGuideLocalizedText: Hashable {
    let tr: String
    let en: String
    let ja: String

    func resolve(locale: Locale) -> String {
        let languageCode = locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
        if languageCode.hasPrefix("ja") { return ja }
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
            title: SurvivalGuideLocalizedText(tr: "Acil Bilgiler", en: "Emergency Info", ja: "緊急情報"),
            description: SurvivalGuideLocalizedText(tr: "112, aile planı, toplanma alanı ve 72 saat çantası", en: "112, family plan, assembly area and 72-hour go-bag", ja: "119番、家族の連絡計画、集合場所、72時間分の避難袋"),
            symbolName: "info.circle.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "G-001",
                    title: SurvivalGuideLocalizedText(tr: "112 ve Acil Arama Kılavuzu", en: "112 Emergency Call Guide", ja: "119番・緊急通報ガイド"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Hayati tehlike varsa 112'yi ara.", en: "Call 112 immediately for life-threatening situations.", ja: "生命に関わる場合はすぐに119番に通報してください。"),
                        SurvivalGuideLocalizedText(tr: "Konumu net ver: il/ilçe/mahalle/sokak/bina no.", en: "Give exact location: city/district/neighborhood/street/building.", ja: "現在地を正確に伝えてください: 都道府県/市区町村/町名/番地/建物名。"),
                        SurvivalGuideLocalizedText(tr: "Olayı tek cümlede özetle: ne oldu, kaç kişi etkilendi.", en: "Summarize in one sentence: what happened and how many are affected.", ja: "状況を一文で伝えてください: 何が起きたか、何人が影響を受けているか。"),
                        SurvivalGuideLocalizedText(tr: "Operatör kapat demeden telefonu kapatma.", en: "Do not hang up until the operator tells you.", ja: "通信指令員が切って良いと言うまで電話を切らないでください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kesin adres + yakın işaret (okul, market, cami, metro).", en: "Share exact address and nearest landmark.", ja: "正確な住所と近くの目印(学校、コンビニ、駅など)を伝えてください。"),
                        SurvivalGuideLocalizedText(tr: "Kim arıyor + geri aranacak telefon numarası.", en: "State caller name and callback number.", ja: "通報者の氏名と折り返し用の電話番号を伝えてください。"),
                        SurvivalGuideLocalizedText(tr: "Olay türü: deprem, yangın, yaralanma, mahsur kalma.", en: "State incident type: quake, fire, injury, trapped person.", ja: "事故の種類を伝えてください: 地震、火災、けが、閉じ込めなど。"),
                        SurvivalGuideLocalizedText(tr: "Kişi sayısı ve durum: bilinç, nefes, kanama.", en: "State victim count and condition: consciousness, breathing, bleeding.", ja: "けが人の人数と状態を伝えてください: 意識、呼吸、出血の有無。"),
                        SurvivalGuideLocalizedText(tr: "Ek riskler: gaz kokusu, duman, çökme riski.", en: "Mention extra risks: gas odor, smoke, collapse risk.", ja: "追加のリスクを伝えてください: ガス臭、煙、倒壊の危険。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Panikle bağırıp adres bilgisini atlama.", en: "Do not skip address details due to panic.", ja: "パニックで叫んで住所情報を省かないでください。"),
                        SurvivalGuideLocalizedText(tr: "“Tam bilmiyorum” deyip hattı belirsiz bırakma.", en: "Do not leave the call vague with “I don't know”.", ja: "「わかりません」だけで通話をあいまいなまま終わらせないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Adres hazır", en: "Address ready", ja: "住所を準備"),
                        SurvivalGuideLocalizedText(tr: "Olay türü hazır", en: "Incident type ready", ja: "事故の種類を準備"),
                        SurvivalGuideLocalizedText(tr: "Yaralı sayısı hazır", en: "Victim count ready", ja: "けが人の人数を準備"),
                        SurvivalGuideLocalizedText(tr: "Ek risk bilgisi hazır", en: "Additional risk info ready", ja: "追加リスク情報を準備"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "112 tek acil numaradır; adres, olay tanımı ve kişi durumu net verilmelidir.", en: "112 is the single emergency number; location, incident and victim status must be clear.", ja: "日本では119番(消防・救急)と110番(警察)が主な緊急番号です。住所、事故の種類、けが人の状態を明確に伝えてください。")
                ),
                SurvivalGuideArticle(
                    id: "G-002",
                    title: SurvivalGuideLocalizedText(tr: "Aile Acil Durum Planı", en: "Family Emergency Plan", ja: "家族の緊急時対応計画"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness", ja: "備え"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "1 şehir dışı irtibat kişisi belirle.", en: "Assign one out-of-city contact.", ja: "遠方に住む連絡係を1人決めてください。"),
                        SurvivalGuideLocalizedText(tr: "2 buluşma noktası seç: mahalle içi + mahalle dışı.", en: "Set two meeting points: local and outside neighborhood.", ja: "集合場所を2か所決めてください: 近所と地区外の2つ。"),
                        SurvivalGuideLocalizedText(tr: "Herkeste acil kartı olsun: kimlik kopyası + numaralar.", en: "Everyone carries an emergency card: ID copy + key numbers.", ja: "全員が緊急カードを携帯してください: 身分証のコピーと連絡先。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Aynı irtibat kişisini tüm aile kullansın.", en: "All family members contact the same person.", ja: "家族全員が同じ連絡係を使ってください。"),
                        SurvivalGuideLocalizedText(tr: "Hat yoğunluğunda SMS kuralını kullan.", en: "Use SMS when voice lines are congested.", ja: "音声回線が混雑しているときは、SMSや災害用伝言板(171)を使ってください。"),
                        SurvivalGuideLocalizedText(tr: "Plan sırası: Ev yoksa nokta-1, olmazsa nokta-2.", en: "Protocol: if home fails use point-1, then point-2.", ja: "優先順位: 自宅が使えなければ集合場所1、それが使えなければ集合場所2。"),
                        SurvivalGuideLocalizedText(tr: "Rol dağıt: çanta, çocuk/yaşlı, evcil hayvan sorumlusu.", en: "Assign roles: bag, children/elderly, pets.", ja: "役割を分担してください: 避難袋、子ども・高齢者、ペットの担当。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Tek buluşma noktasına güvenme.", en: "Do not rely on only one meeting point.", ja: "集合場所を1か所だけに決めないでください。"),
                        SurvivalGuideLocalizedText(tr: "Herkes farklı kişiyi aramasın.", en: "Do not scatter information by calling different people.", ja: "家族がそれぞれ別の人に連絡して情報が分散しないようにしてください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Şehir dışı kişi seçildi", en: "Out-of-city contact selected", ja: "遠方の連絡係を決定"),
                        SurvivalGuideLocalizedText(tr: "2 buluşma noktası kaydedildi", en: "Two meeting points saved", ja: "集合場所2か所を登録"),
                        SurvivalGuideLocalizedText(tr: "Acil kartlar hazırlandı", en: "Emergency cards prepared", ja: "緊急カードを準備"),
                        SurvivalGuideLocalizedText(tr: "Rol dağıtımı yapıldı", en: "Roles assigned", ja: "役割分担を完了"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Aile planlarında şehir dışı irtibat ve SMS odaklı iletişim önerilir.", en: "Family plans recommend one out-of-area contact and SMS-first communication.", ja: "家族計画では、遠方の連絡係を決め、SMSや災害用伝言板を優先した連絡方法が推奨されます。")
                ),
                SurvivalGuideArticle(
                    id: "G-003",
                    title: SurvivalGuideLocalizedText(tr: "Toplanma Alanı Nedir? E-Devlet'ten Nasıl Bulunur?", en: "What Is an Assembly Area? How to Find It via e-Devlet", ja: "指定避難場所とは? 探し方(自治体ハザードマップ)"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness", ja: "備え"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Toplanma alanı afet sonrası ilk güvenli buluşma noktasıdır.", en: "Assembly area is the first safe post-disaster meeting point.", ja: "指定緊急避難場所は、災害発生直後に身の安全を確保する一時的な集合場所です。"),
                        SurvivalGuideLocalizedText(tr: "E-Devlet'te “Toplanma Alanı Sorgulama” ile bulunur.", en: "Find it in e-Devlet with “Assembly Area Query”.", ja: "お住まいの市区町村のハザードマップや防災アプリで確認できます。"),
                        SurvivalGuideLocalizedText(tr: "En yakın alanın adres ve konumunu kaydet.", en: "Save nearest area address and map location.", ja: "最寄りの避難場所の住所と地図上の位置を保存してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "E-Devlet'e giriş yap.", en: "Sign in to e-Devlet.", ja: "自治体の公式サイトまたは防災ポータルにアクセスしてください。"),
                        SurvivalGuideLocalizedText(tr: "Arama kutusuna “Toplanma Alanı Sorgulama” yaz.", en: "Search for “Toplanma Alanı Sorgulama”.", ja: "「指定緊急避難場所」または「ハザードマップ」で検索してください。"),
                        SurvivalGuideLocalizedText(tr: "Haritada en yakını seç ve kaydet.", en: "Pick the nearest on map and save it.", ja: "地図上で最寄りの避難場所を選び、保存してください。"),
                        SurvivalGuideLocalizedText(tr: "Aile ile paylaş, 2 alternatif rota çıkar.", en: "Share with family and define two routes.", ja: "家族と共有し、2つの代替ルートを決めてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Toplanma alanını barınma alanı ile karıştırma.", en: "Do not confuse assembly area with shelter area.", ja: "指定緊急避難場所と指定避難所(宿泊可能な避難所)を混同しないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "En yakın toplanma alanı bulundu", en: "Nearest assembly area found", ja: "最寄りの避難場所を特定"),
                        SurvivalGuideLocalizedText(tr: "Adres + koordinat kaydedildi", en: "Address + coordinates saved", ja: "住所と座標を保存"),
                        SurvivalGuideLocalizedText(tr: "Aile ile paylaşıldı", en: "Shared with family", ja: "家族と共有"),
                        SurvivalGuideLocalizedText(tr: "Alternatif rotalar çizildi", en: "Alternative routes planned", ja: "代替ルートを計画"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Toplanma alanı sorgulaması AFAD/e-Devlet üzerinden resmi olarak sunulur.", en: "Assembly area lookup is officially provided via AFAD/e-Devlet.", ja: "日本では指定緊急避難場所の情報は各自治体が公式に提供し、内閣府や国土地理院のハザードマップポータルでも確認できます。")
                ),
                SurvivalGuideArticle(
                    id: "G-004",
                    title: SurvivalGuideLocalizedText(tr: "Afet & Acil Durum Çantası (72 Saat)", en: "Disaster Go-Bag (72 Hours)", ja: "防災リュック・非常持ち出し袋(72時間分)"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness", ja: "備え"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Hedef: ilk 72 saati kendi başına yönetebilmek.", en: "Goal: self-manage the first 72 hours.", ja: "目標: 最初の72時間を自力で乗り切れる準備をしてください。"),
                        SurvivalGuideLocalizedText(tr: "Omurga: su, gıda, ilk yardım, ışık, iletişim, evrak.", en: "Core: water, food, first aid, light, communication, documents.", ja: "基本: 水、食料、救急セット、明かり、通信手段、重要書類。"),
                        SurvivalGuideLocalizedText(tr: "Çanta kapıya yakın ve erişilebilir yerde olsun.", en: "Keep the bag near the exit and easy to reach.", ja: "防災リュックは玄関付近の取り出しやすい場所に置いてください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Su + bozulmayan gıda + konserve açacağı ekle.", en: "Pack water, non-perishable food, and can opener.", ja: "水、保存食、缶切りを入れてください。"),
                        SurvivalGuideLocalizedText(tr: "Düzenli ilaçlar + reçete + temel ilk yardım seti koy.", en: "Add regular meds, prescriptions, and first aid kit.", ja: "常用薬、処方箋のコピー、基本的な救急セットを入れてください。"),
                        SurvivalGuideLocalizedText(tr: "Fener, pil, powerbank, kablo ve mümkünse radyo koy.", en: "Add flashlight, batteries, powerbank, cable, and radio.", ja: "懐中電灯、電池、モバイルバッテリー、ケーブル、可能であればラジオを入れてください。"),
                        SurvivalGuideLocalizedText(tr: "Kimlik ve kritik belge fotokopileri + bir miktar nakit ekle.", en: "Add ID/critical document copies and cash.", ja: "身分証や重要書類のコピー、小額の現金を入れてください。"),
                        SurvivalGuideLocalizedText(tr: "Bebek/yaşlı/engelli/evcil için özel malzemeyi unutma.", en: "Include special items for babies/elderly/disabled/pets.", ja: "乳幼児、高齢者、障がいのある方、ペット用の特別な用品も忘れないでください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Çantayı evde ulaşılmaz yere kaldırma.", en: "Do not store the bag in an unreachable place.", ja: "防災リュックを手の届かない場所に仕舞わないでください。"),
                        SurvivalGuideLocalizedText(tr: "Son kullanma tarihi kontrolünü atlama.", en: "Do not skip expiry-date checks.", ja: "賞味期限・使用期限の確認を忘れないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Su ve gıda hazır", en: "Water and food packed", ja: "水と食料を準備"),
                        SurvivalGuideLocalizedText(tr: "İlk yardım ve ilaç hazır", en: "First aid and medications packed", ja: "救急セットと薬を準備"),
                        SurvivalGuideLocalizedText(tr: "Işık/iletişim ekipmanı hazır", en: "Light/communication kit packed", ja: "明かりと通信機器を準備"),
                        SurvivalGuideLocalizedText(tr: "Belge ve nakit hazır", en: "Documents and cash packed", ja: "書類と現金を準備"),
                        SurvivalGuideLocalizedText(tr: "Özel ihtiyaç malzemeleri hazır", en: "Special-needs supplies packed", ja: "特別なニーズ用品を準備"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "AFAD dayanıklı gıda ve önemli belge fotokopilerini çantada önerir.", en: "AFAD recommends durable food and copies of critical documents in the go-bag.", ja: "内閣府・消防庁は、保存食と重要書類のコピーを防災リュックに入れることを推奨しています。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "earthquake",
            title: SurvivalGuideLocalizedText(tr: "Deprem", en: "Earthquake", ja: "地震"),
            description: SurvivalGuideLocalizedText(tr: "Deprem öncesi hazırlık, deprem anı ve ilk 30 dakika", en: "Preparedness, immediate response, and first 30 minutes", ja: "地震前の備え、発生時の対応、最初の30分"),
            symbolName: "waveform.path.ecg",
            guides: [
                SurvivalGuideArticle(
                    id: "E-001",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Öncesi Ev Güvenliği (Risk Azaltma)", en: "Home Safety Before Earthquakes", ja: "地震前の家庭内安全対策(リスク低減)"),
                    priority: SurvivalGuideLocalizedText(tr: "Hazırlık", en: "Preparedness", ja: "備え"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Dolap, raf, TV gibi devrilebilir eşyaları sabitle.", en: "Anchor wardrobes, shelves, and TVs.", ja: "タンス、棚、テレビなど倒れやすい家具を固定してください。"),
                        SurvivalGuideLocalizedText(tr: "Ağır eşyaları alt raflara al.", en: "Move heavy items to lower shelves.", ja: "重い物は低い棚に移してください。"),
                        SurvivalGuideLocalizedText(tr: "Kaçış yolunu açık tut ve mini tatbikat yap.", en: "Keep exit path clear and run mini drills.", ja: "避難経路を確保し、短い避難訓練を実施してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yatak başını cam/pencereden uzaklaştır.", en: "Keep bed away from windows/glass.", ja: "ベッドの位置を窓やガラスから離してください。"),
                        SurvivalGuideLocalizedText(tr: "Kapı önü ve koridoru daima boş bırak.", en: "Keep doorways and corridors clear.", ja: "ドア前と廊下は常に物を置かないでください。"),
                        SurvivalGuideLocalizedText(tr: "Gaz, elektrik, su vanalarının yerini öğren.", en: "Know where gas, electric, and water shutoffs are.", ja: "ガスの元栓、ブレーカー、水道の元栓の位置を把握してください。"),
                        SurvivalGuideLocalizedText(tr: "Evde Çök-Kapan-Tutun pratiği yap.", en: "Practice Drop-Cover-Hold at home.", ja: "自宅で『姿勢を低く・身を守る・動かない』の訓練をしてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Ağır çerçeve/ayna gibi objeleri yatak üstüne asma.", en: "Do not hang heavy mirrors/frames above beds.", ja: "ベッドの上に重い鏡や額縁を掛けないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Mobilya sabitleme tamam", en: "Furniture anchoring complete", ja: "家具の固定が完了"),
                        SurvivalGuideLocalizedText(tr: "Kaçış rotası temiz", en: "Exit route clear", ja: "避難経路を確保"),
                        SurvivalGuideLocalizedText(tr: "Vana noktaları öğrenildi", en: "Shutoff points known", ja: "元栓・ブレーカー位置を把握"),
                        SurvivalGuideLocalizedText(tr: "Ev tatbikatı yapıldı", en: "Home drill completed", ja: "自宅で避難訓練を実施"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem öncesinde sabitleme ve deprem anında korunma davranışı kritik görülür.", en: "Anchoring before a quake and protective posture during a quake are critical.", ja: "地震前の家具固定と、発生時の身を守る姿勢(ドロップ・カバー・ホールドオン)が非常に重要です。")
                ),
                SurvivalGuideArticle(
                    id: "E-002",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Anında: Çök-Kapan-Tutun", en: "During an Earthquake: Drop-Cover-Hold", ja: "地震発生時: 姿勢を低く・身を守る・動かない"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "ÇÖK-KAPAN-TUTUN uygula.", en: "Apply DROP-COVER-HOLD.", ja: "『姿勢を低く・身を守る・動かない』を実行してください。"),
                        SurvivalGuideLocalizedText(tr: "Cam/pencere ve düşebilecek eşyalardan uzak dur.", en: "Stay away from windows and falling objects.", ja: "窓、ガラス、落下物から離れてください。"),
                        SurvivalGuideLocalizedText(tr: "Asansör, merdiven, balkon tarafına koşma.", en: "Do not run toward elevator, stairs, or balcony.", ja: "エレベーター、階段、ベランダに走って向かわないでください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Sağlam masa yanında veya altında pozisyon al.", en: "Take position under/next to sturdy furniture.", ja: "丈夫なテーブルの下や横に身を寄せてください。"),
                        SurvivalGuideLocalizedText(tr: "Diz üstüne çök, baş-boynu koruyarak kapan.", en: "Drop to knees and cover head/neck.", ja: "膝をつき、頭と首を手で覆って守ってください。"),
                        SurvivalGuideLocalizedText(tr: "Sabit noktaya tutun ve sarsıntı bitene kadar kal.", en: "Hold on and stay until shaking ends.", ja: "固定物にしっかりつかまり、揺れがおさまるまでその場にとどまってください。"),
                        SurvivalGuideLocalizedText(tr: "Dışarıdaysan bina ve direklerden uzak açık alana geç.", en: "If outside, move away from buildings and poles.", ja: "屋外にいるときは、建物や電柱から離れ、開けた場所へ移動してください。"),
                        SurvivalGuideLocalizedText(tr: "Araç içindeysen güvenli yerde dur, araçta kal.", en: "If in a vehicle, stop safely and stay inside.", ja: "運転中は安全な場所に停車し、車内にとどまってください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Panikle dışarı fırlama.", en: "Do not rush outside in panic.", ja: "パニックで外に飛び出さないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Güvenli nokta bul", en: "Find a safe spot", ja: "安全な場所を確保"),
                        SurvivalGuideLocalizedText(tr: "Baş-boyun koru", en: "Protect head and neck", ja: "頭と首を守る"),
                        SurvivalGuideLocalizedText(tr: "Sarsıntı bitene kadar pozisyonu koru", en: "Hold position until shaking stops", ja: "揺れが終わるまで姿勢を維持"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem anında Çök-Kapan-Tutun ve camdan uzak kalma temel yaklaşımdır.", en: "Drop-Cover-Hold and staying away from glass are core guidance.", ja: "地震発生時は『姿勢を低く・身を守る・動かない』とガラスから離れることが基本です。")
                ),
                SurvivalGuideArticle(
                    id: "E-003",
                    title: SurvivalGuideLocalizedText(tr: "Deprem Sonrası: İlk 30 Dakika", en: "After Earthquake: First 30 Minutes", ja: "地震発生後: 最初の30分"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Önce kendi güvenliğini sağla.", en: "Secure your own safety first.", ja: "まず自分の安全を確保してください。"),
                        SurvivalGuideLocalizedText(tr: "Gaz kokusu varsa gazı kapat, havalandır, binayı terk et.", en: "If gas odor exists, shut gas, ventilate, and evacuate.", ja: "ガス臭がする場合は元栓を閉め、換気し、建物から退避してください。"),
                        SurvivalGuideLocalizedText(tr: "Çantanı al ve toplanma alanına git.", en: "Take go-bag and move to assembly area.", ja: "防災リュックを持って指定避難場所へ向かってください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yaralı kontrolü yap, 112'ye bilgi ver.", en: "Check injuries and call 112.", ja: "けが人の有無を確認し、必要なら119番に通報してください。"),
                        SurvivalGuideLocalizedText(tr: "Duman, çökme, gaz riski var mı değerlendir.", en: "Assess smoke, collapse, gas risks.", ja: "煙、倒壊、ガスのリスクがないか確認してください。"),
                        SurvivalGuideLocalizedText(tr: "Uygunsa elektrik-gaz-su vanalarını kapat.", en: "If safe, shut electricity-gas-water.", ja: "安全が確認できれば、ブレーカー・ガス・水道の元栓を閉めてください。"),
                        SurvivalGuideLocalizedText(tr: "Önceden belirlenen rotadan binayı terk et.", en: "Evacuate via pre-planned route.", ja: "事前に決めた避難ルートで建物から退避してください。"),
                        SurvivalGuideLocalizedText(tr: "Resmi duyuruları takip et, yolları acil araçlara bırak.", en: "Follow official updates and keep roads clear.", ja: "公式発表を確認し、緊急車両のために道路をあけてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Hasarlı binaya geri girme.", en: "Do not re-enter damaged buildings.", ja: "損傷した建物に戻って入らないでください。"),
                        SurvivalGuideLocalizedText(tr: "Gereksiz araç kullanıp trafiği kilitleme.", en: "Do not block roads with unnecessary driving.", ja: "不要な車の運転で道路を塞がないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yaralı kontrolü yapıldı", en: "Injury check completed", ja: "けが人の確認を完了"),
                        SurvivalGuideLocalizedText(tr: "Vana kontrolleri yapıldı", en: "Utility shutoff check completed", ja: "元栓・ブレーカーを確認"),
                        SurvivalGuideLocalizedText(tr: "Çanta alındı", en: "Go-bag taken", ja: "防災リュックを携行"),
                        SurvivalGuideLocalizedText(tr: "Toplanma alanına çıkıldı", en: "Moved to assembly area", ja: "指定避難場所へ移動"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Deprem sonrasında vana kontrolü, bina tahliyesi ve toplanma alanına geçiş önerilir.", en: "Post-quake guidance includes utility shutoff, evacuation, and assembly-area transfer.", ja: "地震後はライフラインの元栓確認、建物からの退避、指定避難場所への移動が推奨されます。")
                ),
                SurvivalGuideArticle(
                    id: "E-004",
                    title: SurvivalGuideLocalizedText(tr: "Artçı Depremler ve Hasarlı Binalar", en: "Aftershocks and Damaged Buildings", ja: "余震と損傷した建物"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Büyük deprem sonrası artçı beklenir.", en: "After major quakes, aftershocks are expected.", ja: "大地震の後は必ず余震があると想定してください。"),
                        SurvivalGuideLocalizedText(tr: "Hasarlı binaya girme.", en: "Do not enter damaged structures.", ja: "損傷した建物に入らないでください。"),
                        SurvivalGuideLocalizedText(tr: "Artçıda yine Çök-Kapan-Tutun.", en: "Use Drop-Cover-Hold during aftershocks.", ja: "余震時も『姿勢を低く・身を守る・動かない』を実行してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Resmi yönlendirme gelmeden eve dönme.", en: "Do not return home before official guidance.", ja: "行政の指示が出るまで帰宅しないでください。"),
                        SurvivalGuideLocalizedText(tr: "Toplanma alanında kal ve duyuruları izle.", en: "Stay at assembly area and monitor updates.", ja: "避難場所にとどまり、情報を確認し続けてください。"),
                        SurvivalGuideLocalizedText(tr: "Artçı başladığında yakındaki güvenli pozisyonu al.", en: "Take nearby protective position when aftershock starts.", ja: "余震が始まったら、すぐ近くで身を守る姿勢をとってください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Bir şey olmaz” deyip çatlak binada bekleme.", en: "Do not stay in cracked buildings assuming safety.", ja: "「大丈夫だろう」と思ってひび割れた建物にとどまらないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Artçı riski hatırlandı", en: "Aftershock risk acknowledged", ja: "余震のリスクを認識"),
                        SurvivalGuideLocalizedText(tr: "Hasarlı bina terk edildi", en: "Damaged building avoided", ja: "損傷した建物から退避"),
                        SurvivalGuideLocalizedText(tr: "Resmi duyurular takipte", en: "Official alerts monitored", ja: "公式発表を継続して確認"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Artçılar devam ederken hasarlı yapılara girilmemesi vurgulanır.", en: "Guidance stresses avoiding damaged buildings while aftershocks continue.", ja: "余震が続いている間は損傷した建造物に立ち入らないことが強調されます。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "fire",
            title: SurvivalGuideLocalizedText(tr: "Yangın", en: "Fire", ja: "火災"),
            description: SurvivalGuideLocalizedText(tr: "Yangında ilk dakikalar, duman tahliyesi ve kıyafet tutuşması", en: "First minutes, smoke evacuation, and clothing ignition", ja: "火災発生直後の行動、煙からの避難、衣服への着火"),
            symbolName: "flame.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "F-001",
                    title: SurvivalGuideLocalizedText(tr: "Evde Yangın: İlk Dakikalar ve Tahliye", en: "Home Fire: First Minutes and Evacuation", ja: "住宅火災: 発生直後と避難"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Sakin ol, 112'yi ara.", en: "Stay calm and call 112.", ja: "落ち着いて119番に通報してください。"),
                        SurvivalGuideLocalizedText(tr: "Güvenliyse elektrik/doğalgazı kapat.", en: "If safe, shut electricity/natural gas.", ja: "安全なら、電気とガスを止めてください。"),
                        SurvivalGuideLocalizedText(tr: "Yangın büyüyorsa hemen tahliye et.", en: "Evacuate immediately if fire is growing.", ja: "火が大きくなっている場合は、すぐに避難してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kapı ve pencereleri açık bırakma.", en: "Do not leave doors/windows open.", ja: "ドアや窓を開けたままにしないでください。"),
                        SurvivalGuideLocalizedText(tr: "Tahliyede asansör kullanma.", en: "Never use elevators during evacuation.", ja: "避難時にエレベーターは絶対に使わないでください。"),
                        SurvivalGuideLocalizedText(tr: "Çıkamıyorsan güvenli odada kalıp görünür ol.", en: "If trapped, stay in safer room and stay visible.", ja: "避難できない場合は安全な部屋にとどまり、外から見える場所で助けを求めてください。"),
                        SurvivalGuideLocalizedText(tr: "Düşük pozisyonda ilerleyerek temiz çıkışa yönel.", en: "Move low and head to nearest safe exit.", ja: "姿勢を低くして、最寄りの安全な出口へ向かってください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Eşya kurtarmaya çalışma.", en: "Do not try to save belongings.", ja: "家財を取りに戻ろうとしないでください。"),
                        SurvivalGuideLocalizedText(tr: "Duman içinde ayakta koşma.", en: "Do not run upright in smoke.", ja: "煙の中で立ったまま走らないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "112 arandı", en: "112 called", ja: "119番に通報"),
                        SurvivalGuideLocalizedText(tr: "Tahliye kararı verildi", en: "Evacuation decision made", ja: "避難の判断を実施"),
                        SurvivalGuideLocalizedText(tr: "Asansör kullanılmadı", en: "Elevator avoided", ja: "エレベーター未使用"),
                        SurvivalGuideLocalizedText(tr: "Güvenli çıkışa ilerleniyor", en: "Moving toward safe exit", ja: "安全な出口へ移動中"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yangın anında 112 araması, asansör kullanmama ve hızlı tahliye öne çıkar.", en: "Calling 112, avoiding elevators, and immediate evacuation are key fire actions.", ja: "119番通報、エレベーターを使わないこと、速やかな避難が火災時の基本行動です。")
                ),
                SurvivalGuideArticle(
                    id: "F-002",
                    title: SurvivalGuideLocalizedText(tr: "Dumanlı Ortamda Tahliye", en: "Evacuation in Smoke", ja: "煙の中での避難"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Duman yukarıda birikir: çömel veya emekle.", en: "Smoke rises: crouch or crawl.", ja: "煙は上にたまるので、しゃがむか四つん這いで進んでください。"),
                        SurvivalGuideLocalizedText(tr: "Ağzı-burnu bezle kapatıp en yakın çıkışa yönel.", en: "Cover nose/mouth and move to nearest exit.", ja: "濡れたタオルや布で口と鼻を覆い、最寄りの出口へ向かってください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kapı kolunu elle kontrol et; aşırı sıcaksa kullanma.", en: "Check door heat; avoid very hot doors.", ja: "ドアノブを手の甲で確認し、非常に熱い場合は開けないでください。"),
                        SurvivalGuideLocalizedText(tr: "Duvar hattını takip ederek yönünü koru.", en: "Follow wall line to maintain orientation.", ja: "壁沿いに進んで方向感覚を保ってください。"),
                        SurvivalGuideLocalizedText(tr: "Çocuk ve yaşlıları önde yönlendir, geriden destekle.", en: "Guide children/elderly first and support from behind.", ja: "子どもや高齢者を先に誘導し、後ろから支えてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Dumanlı alanda dik yürümeye çalışma.", en: "Do not walk upright in heavy smoke.", ja: "濃い煙の中で立って歩こうとしないでください。"),
                        SurvivalGuideLocalizedText(tr: "Saklanma noktalarına girme.", en: "Do not hide in enclosed spots.", ja: "押し入れやクローゼットなど閉じた場所に隠れないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Düşük pozisyon alındı", en: "Low posture assumed", ja: "低い姿勢をとった"),
                        SurvivalGuideLocalizedText(tr: "Ağız-burun kapatıldı", en: "Mouth and nose covered", ja: "口と鼻を覆った"),
                        SurvivalGuideLocalizedText(tr: "En yakın çıkış belirlendi", en: "Nearest exit identified", ja: "最寄りの出口を確認"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Dumanlı ortamlarda eğilerek/emekleyerek ilerleme önerilir.", en: "Crouching/crawling is recommended for smoke-filled environments.", ja: "煙が充満した環境では、かがむか四つん這いで進むことが推奨されます。")
                ),
                SurvivalGuideArticle(
                    id: "F-003",
                    title: SurvivalGuideLocalizedText(tr: "Kıyafetin Tutuşursa: Dur-Yat-Yuvarlan", en: "If Clothing Catches Fire: Stop-Drop-Roll", ja: "衣服に着火したら: 止まる・倒れる・転がる"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 1,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "DUR: Koşma.", en: "STOP: Do not run.", ja: "止まる: 走らないでください。"),
                        SurvivalGuideLocalizedText(tr: "YAT: Yere yat.", en: "DROP: Get on the ground.", ja: "倒れる: 地面に横になってください。"),
                        SurvivalGuideLocalizedText(tr: "YUVARLAN: Alev sönene kadar yuvarlan.", en: "ROLL until flames are out.", ja: "転がる: 炎が消えるまで左右に転がってください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yüzü ellerle koruyarak yuvarlan.", en: "Protect face with hands while rolling.", ja: "両手で顔を覆って保護しながら転がってください。"),
                        SurvivalGuideLocalizedText(tr: "Mümkünse kalın kumaşla alevi boğ.", en: "If available, smother flames with thick fabric.", ja: "可能なら、厚手の布をかけて炎を窒息させてください。"),
                        SurvivalGuideLocalizedText(tr: "Yanık varsa soğut ve sağlık yardımı al.", en: "Cool burns and seek medical help.", ja: "やけどは冷やし、医療機関で治療を受けてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Alev varken koşma veya rüzgara dönme.", en: "Do not run or face wind while burning.", ja: "炎がある状態で走ったり、風の方向を向いたりしないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Duruldu", en: "Stopped moving", ja: "動きを止めた"),
                        SurvivalGuideLocalizedText(tr: "Yere yatıldı", en: "Dropped to ground", ja: "地面に倒れた"),
                        SurvivalGuideLocalizedText(tr: "Yuvarlanıldı", en: "Rolled to extinguish", ja: "転がって消火"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kıyafet tutuşmasında temel kural Dur-Yat-Yuvarlan yaklaşımıdır.", en: "Stop-Drop-Roll is the core approach for clothing fires.", ja: "衣服への着火時は『止まる・倒れる・転がる』が基本対応です。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "flood",
            title: SurvivalGuideLocalizedText(tr: "Sel / Taşkın", en: "Flood", ja: "洪水・氾濫"),
            description: SurvivalGuideLocalizedText(tr: "Sel anında yüksek yere çıkma ve suya girmeme", en: "Move to high ground and stay out of floodwater", ja: "洪水時に高所へ避難し、水に入らないこと"),
            symbolName: "drop.triangle.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "W-001",
                    title: SurvivalGuideLocalizedText(tr: "Sel Sırasında: Yüksek Yere Çık, Suya Girme", en: "During Flood: Go Higher, Stay Out of Water", ja: "洪水時: 高所へ避難し、水に入らない"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Sel bölgesini terk et, yüksek noktaya çık.", en: "Leave flooded area and go to high ground.", ja: "浸水している地域から離れ、高台に避難してください。"),
                        SurvivalGuideLocalizedText(tr: "Elektrik kaynaklarından uzak dur.", en: "Stay away from electrical sources.", ja: "電気設備や電線から離れてください。"),
                        SurvivalGuideLocalizedText(tr: "Araçla su kaplı yola girme.", en: "Do not drive into water-covered roads.", ja: "冠水した道路に車で進入しないでください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Çocukları sel suyundan uzak tut.", en: "Keep children away from floodwater.", ja: "子どもを浸水した水から遠ざけてください。"),
                        SurvivalGuideLocalizedText(tr: "Araç arızalanırsa aracı bırakıp yüksek yere çık.", en: "If car stalls, leave vehicle and move higher.", ja: "車が動かなくなったら、車を離れて高台へ避難してください。"),
                        SurvivalGuideLocalizedText(tr: "Gece sürüşünde su derinliğini varsayma.", en: "At night, never assume water depth.", ja: "夜間の運転では、水深を自己判断しないでください。"),
                        SurvivalGuideLocalizedText(tr: "112'ye konumu net ilet.", en: "Call 112 and provide precise location.", ja: "119番に通報し、現在地を正確に伝えてください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Akan suya yürüyerek veya araçla girme.", en: "Do not enter moving water by foot or vehicle.", ja: "流れている水に徒歩や車で入らないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yüksek güvenli nokta bulundu", en: "High safe point reached", ja: "安全な高台に到達"),
                        SurvivalGuideLocalizedText(tr: "Elektrik kaynaklarından uzaklaşıldı", en: "Moved away from electrical risk", ja: "電気の危険から離れた"),
                        SurvivalGuideLocalizedText(tr: "112 bilgilendirildi", en: "112 notified", ja: "119番に通報済み"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Sel sırasında yüksek bölgeye geçiş ve su kaplı yollardan kaçınma önerilir.", en: "Flood guidance prioritizes high ground and avoiding water-covered roads.", ja: "洪水時は高台への避難と、冠水した道路を避けることが優先されます。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "outage_co",
            title: SurvivalGuideLocalizedText(tr: "Kesintiler & Karbonmonoksit", en: "Outages & Carbon Monoxide", ja: "停電と一酸化炭素"),
            description: SurvivalGuideLocalizedText(tr: "Elektrik kesintisinde CO zehirlenmesini önleme", en: "Prevent carbon monoxide poisoning during outages", ja: "停電時の一酸化炭素中毒を防ぐ"),
            symbolName: "bolt.slash.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "P-001",
                    title: SurvivalGuideLocalizedText(tr: "Elektrik Kesintisi: Karbonmonoksit (CO) Riski", en: "Power Outage: Carbon Monoxide (CO) Risk", ja: "停電時: 一酸化炭素(CO)中毒のリスク"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "CO kokusuz ve görünmez; ölümcül olabilir.", en: "CO is odorless/invisible and can be fatal.", ja: "一酸化炭素は無臭・無色で、命に関わります。"),
                        SurvivalGuideLocalizedText(tr: "Jeneratör/mangal/ocak kapalı alanda kullanılmaz.", en: "Never use fuel-burning devices indoors.", ja: "発電機・炭火・コンロなど燃焼器具を屋内で使わないでください。"),
                        SurvivalGuideLocalizedText(tr: "Belirti varsa temiz havaya çık ve 112 ara.", en: "If symptoms appear, move to fresh air and call 112.", ja: "症状が出たら新鮮な空気の場所へ移動し、119番に通報してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Jeneratörü ev, bodrum, garaj içinde çalıştırma.", en: "Do not run generator in home/basement/garage.", ja: "発電機を家の中、地下、車庫で運転しないでください。"),
                        SurvivalGuideLocalizedText(tr: "Dışarıda çalıştırırken kapı-pencereden uzak tut.", en: "Operate it outdoors away from doors/windows.", ja: "屋外で使う場合は、ドアや窓から離れた場所で運転してください。"),
                        SurvivalGuideLocalizedText(tr: "Baş ağrısı, baş dönmesi, bulantı, halsizlikte alanı terk et.", en: "Leave area if headache, dizziness, nausea, weakness occur.", ja: "頭痛、めまい、吐き気、脱力感が出たら、その場から離れてください。"),
                        SurvivalGuideLocalizedText(tr: "Mümkünse pil yedekli CO alarmı kullan.", en: "Use battery-backed CO alarm when possible.", ja: "可能なら、電池式のCO警報器を設置してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Kapıyı araladım yeter” diye düşünme.", en: "Do not assume cracked doors make indoor use safe.", ja: "「ドアを少し開けたから大丈夫」と思わないでください。"),
                        SurvivalGuideLocalizedText(tr: "Aracı kapalı garajda ısınmak için çalıştırma.", en: "Do not idle car in closed garage for heating.", ja: "閉め切った車庫で暖をとるために車のエンジンをかけないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Yanmalı cihazlar iç mekandan çıkarıldı", en: "Fuel-burning devices kept outside", ja: "燃焼器具を屋外で使用"),
                        SurvivalGuideLocalizedText(tr: "Jeneratör açıklıklardan uzak", en: "Generator away from openings", ja: "発電機を開口部から離した"),
                        SurvivalGuideLocalizedText(tr: "CO belirtileri takipte", en: "CO symptoms monitored", ja: "CO中毒症状を観察"),
                        SurvivalGuideLocalizedText(tr: "CO alarmı kontrol edildi", en: "CO alarm checked", ja: "CO警報器を確認"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "CO riskinde kapalı alan kullanımından kaçınmak ve belirtilerde hızlı tahliye önerilir.", en: "Avoid indoor fuel use and evacuate quickly when CO symptoms appear.", ja: "CO中毒のリスクがある場合、屋内での燃焼器具使用を避け、症状が出たら速やかに退避してください。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "first_aid",
            title: SurvivalGuideLocalizedText(tr: "İlk Yardım", en: "First Aid", ja: "応急手当"),
            description: SurvivalGuideLocalizedText(tr: "KBB yaklaşımı, kanama, yanık, kırık ve tıkanma", en: "Protection-report-rescue, bleeding, burns, fractures, choking", ja: "安全確保・通報・救助、出血、やけど、骨折、気道閉塞"),
            symbolName: "cross.case.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "FA-001",
                    title: SurvivalGuideLocalizedText(tr: "İlk Yardımın Temeli: Koruma-Bildirme-Kurtarma + ABC", en: "First Aid Basics: Protect-Report-Rescue + ABC", ja: "応急手当の基本: 安全確保・通報・救助 + ABC"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "KORU: Önce olay yerini güvenli yap.", en: "PROTECT: Secure the scene first.", ja: "安全確保: まず現場の安全を確保してください。"),
                        SurvivalGuideLocalizedText(tr: "BİLDİR: 112'yi ara.", en: "REPORT: Call 112.", ja: "通報: 119番に通報してください。"),
                        SurvivalGuideLocalizedText(tr: "KURTAR: Bildiğin güvenli müdahaleyi yap.", en: "RESCUE: Apply safe actions you know.", ja: "救助: 自分が安全にできる応急手当を実施してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "A: Hava yolu açık mı kontrol et.", en: "A: Check airway.", ja: "A: 気道が確保されているか確認してください。"),
                        SurvivalGuideLocalizedText(tr: "B: Nefes var mı kontrol et.", en: "B: Check breathing.", ja: "B: 呼吸があるか確認してください。"),
                        SurvivalGuideLocalizedText(tr: "C: Büyük kanama var mı kontrol et.", en: "C: Check major bleeding.", ja: "C: 大きな出血がないか確認してください。"),
                        SurvivalGuideLocalizedText(tr: "Sürekli yeniden değerlendir ve kötüleşmede 112'yi güncelle.", en: "Reassess continuously and update 112 if worsening.", ja: "継続的に状態を再評価し、悪化すれば119番に情報を更新してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Tehlike yoksa yaralıyı gereksiz oynatma.", en: "Do not move casualty unnecessarily.", ja: "差し迫った危険がなければ、傷病者を不必要に動かさないでください。"),
                        SurvivalGuideLocalizedText(tr: "Bilinci kapalı kişiye ağızdan bir şey verme.", en: "Do not give anything by mouth to unconscious person.", ja: "意識のない人に口から何かを与えないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Olay yeri güvenli", en: "Scene is safe", ja: "現場の安全を確認"),
                        SurvivalGuideLocalizedText(tr: "112 haberdar", en: "112 informed", ja: "119番に通報済み"),
                        SurvivalGuideLocalizedText(tr: "ABC kontrolü yapıldı", en: "ABC checked", ja: "ABC(気道・呼吸・循環)を確認"),
                        SurvivalGuideLocalizedText(tr: "Durum izleniyor", en: "Condition monitored", ja: "状態を継続観察"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "KBB yaklaşımı ve temel ilk yardım adımları eğitimli şekilde uygulanmalıdır.", en: "KBB-style first aid actions should be applied with training.", ja: "安全確保・通報・救助と基本的な応急手当は、適切な訓練を受けた上で実施してください。")
                ),
                SurvivalGuideArticle(
                    id: "FA-002",
                    title: SurvivalGuideLocalizedText(tr: "Şiddetli Kanama: Bası, Bandaj, Turnike", en: "Severe Bleeding: Pressure, Bandage, Tourniquet", ja: "重度の出血: 圧迫・包帯・止血帯"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "İlk hedef: kanamayı durdur.", en: "Primary goal: stop bleeding.", ja: "最優先: 出血を止めてください。"),
                        SurvivalGuideLocalizedText(tr: "İlk adım: doğrudan bası.", en: "First step: direct pressure.", ja: "最初の対応: 直接圧迫止血です。"),
                        SurvivalGuideLocalizedText(tr: "Hayati uzuv kanamasında bası yetmezse turnike.", en: "For life-threatening limb bleeding, use tourniquet if pressure fails.", ja: "手足の命に関わる出血で圧迫で止まらないときのみ、止血帯を使用してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Temiz bariyerle yaraya güçlü bası uygula.", en: "Apply strong pressure with clean barrier.", ja: "清潔な布で傷口を強く押さえてください。"),
                        SurvivalGuideLocalizedText(tr: "Bez kaldırmadan üstüne yeni bez ekle.", en: "Add cloth on top without removing soaked layer.", ja: "血を吸った布をはがさず、その上に新しい布を重ねてください。"),
                        SurvivalGuideLocalizedText(tr: "Basınçlı bandajla basıyı sürdür.", en: "Maintain pressure with pressure bandage.", ja: "圧迫包帯で圧迫を維持してください。"),
                        SurvivalGuideLocalizedText(tr: "Durmayan ağır uzuv kanamasında uygun turnike uygula ve zamanı not et.", en: "Use proper tourniquet for uncontrolled severe limb bleeding and note time.", ja: "止まらない重度の四肢出血には適切な止血帯を使用し、装着時刻を記録してください。"),
                        SurvivalGuideLocalizedText(tr: "112'yi ara ve şok belirtilerini izle.", en: "Call 112 and monitor shock signs.", ja: "119番に通報し、ショック症状を観察してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Kanlı bezi kaldırıp pıhtıyı bozma.", en: "Do not remove cloth and break clot.", ja: "血を吸った布をはがして血餅を壊さないでください。"),
                        SurvivalGuideLocalizedText(tr: "Turnikeyi her kanamada kullanma.", en: "Do not use tourniquet for every bleed.", ja: "止血帯をすべての出血に使わないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Doğrudan bası uygulandı", en: "Direct pressure applied", ja: "直接圧迫を実施"),
                        SurvivalGuideLocalizedText(tr: "Bandajla bası sürdürülüyor", en: "Pressure maintained with bandage", ja: "包帯で圧迫を維持"),
                        SurvivalGuideLocalizedText(tr: "Turnike gerekiyorsa doğru uygulandı", en: "Tourniquet used correctly if needed", ja: "必要時は止血帯を正しく使用"),
                        SurvivalGuideLocalizedText(tr: "112 bilgilendirildi", en: "112 informed", ja: "119番に通報済み"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kanamada bası önceliklidir; turnike belirli, ağır durumlarda uygulanır.", en: "Direct pressure is first-line; tourniquet is for specific severe cases.", ja: "出血への第一対応は直接圧迫で、止血帯は特定の重度事例にのみ使用します。")
                ),
                SurvivalGuideArticle(
                    id: "FA-003",
                    title: SurvivalGuideLocalizedText(tr: "Yanıklar: Soğut, Koru, Yanlışları Yapma", en: "Burns: Cool, Protect, Avoid Mistakes", ja: "やけど: 冷やす・保護する・間違った処置をしない"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Yanığı hemen soğut (en az 20 dk).", en: "Cool burn immediately (at least 20 min).", ja: "やけどを直ちに冷やしてください(最低20分)。"),
                        SurvivalGuideLocalizedText(tr: "Takıları çıkar, kabarcık patlatma.", en: "Remove jewelry, do not pop blisters.", ja: "指輪や時計を外し、水ぶくれを破らないでください。"),
                        SurvivalGuideLocalizedText(tr: "Geniş yanıkta 112 ara.", en: "Call 112 for extensive burns.", ja: "広範囲のやけどは119番に通報してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Isı kaynağını kes ve güvenliği sağla.", en: "Stop heat source and secure scene.", ja: "熱源を止め、現場の安全を確保してください。"),
                        SurvivalGuideLocalizedText(tr: "Yanığı serin suyla sürekli soğut.", en: "Cool with running cool water.", ja: "流水で冷たい水をかけ続けてください。"),
                        SurvivalGuideLocalizedText(tr: "Yüzük/saat gibi takıları erken çıkar.", en: "Remove rings/watches early.", ja: "腫れる前に指輪や時計を外してください。"),
                        SurvivalGuideLocalizedText(tr: "Temiz bezle gevşek ört.", en: "Cover loosely with clean dressing.", ja: "清潔な布でゆるく覆ってください。"),
                        SurvivalGuideLocalizedText(tr: "Solunum etkilenmişse acil destek iste.", en: "Seek urgent care if breathing affected.", ja: "呼吸に影響がある場合は、緊急医療を要請してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Diş macunu/yoğurt/yağ sürme.", en: "Do not apply toothpaste/yogurt/oil.", ja: "歯磨き粉、ヨーグルト、油を塗らないでください。"),
                        SurvivalGuideLocalizedText(tr: "Kabarcıkları patlatma.", en: "Do not pop blisters.", ja: "水ぶくれを破らないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Soğutma başlatıldı", en: "Cooling started", ja: "冷却を開始"),
                        SurvivalGuideLocalizedText(tr: "Takılar çıkarıldı", en: "Jewelry removed", ja: "装飾品を除去"),
                        SurvivalGuideLocalizedText(tr: "Temiz örtü uygulandı", en: "Clean cover applied", ja: "清潔な覆いを適用"),
                        SurvivalGuideLocalizedText(tr: "Gerekirse 112 arandı", en: "112 called when needed", ja: "必要時に119番通報"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yanıkta 20 dakika soğutma ve yanlış uygulamalardan kaçınma öne çıkar.", en: "20-minute cooling and avoiding harmful home remedies are emphasized.", ja: "やけどは20分以上の冷却が重要で、有害な民間療法は避けるべきです。")
                ),
                SurvivalGuideArticle(
                    id: "FA-004",
                    title: SurvivalGuideLocalizedText(tr: "Kırık / Çıkık / Burkulma", en: "Fracture / Dislocation / Sprain", ja: "骨折・脱臼・捻挫"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Oynatma, sabitle.", en: "Do not move; immobilize.", ja: "動かさず、そのまま固定してください。"),
                        SurvivalGuideLocalizedText(tr: "Şişlik için soğuk uygula.", en: "Apply cold for swelling.", ja: "腫れには冷却を行ってください。"),
                        SurvivalGuideLocalizedText(tr: "112 veya sağlık birimine başvur.", en: "Call 112 or seek medical care.", ja: "119番に通報するか、医療機関を受診してください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Şüpheli bölgeyi bulunduğu pozisyonda tespit et.", en: "Immobilize in found position.", ja: "疑われる部位を発見時の姿勢のまま固定してください。"),
                        SurvivalGuideLocalizedText(tr: "Ağrı, şekil bozukluğu, uyuşmada aciliyet artır.", en: "Escalate urgency for severe pain/deformity/numbness.", ja: "強い痛み、変形、しびれがある場合は緊急度を上げてください。"),
                        SurvivalGuideLocalizedText(tr: "Soğuk uygulamayı aralıklı sürdür.", en: "Continue intermittent cold packs.", ja: "冷却は間隔をあけて続けてください。"),
                        SurvivalGuideLocalizedText(tr: "Açık kırıkta steril örtü ve hızlı sevk sağla.", en: "For open fracture, sterile cover and rapid transfer.", ja: "開放骨折は滅菌ガーゼで覆い、速やかに医療搬送してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Çıkığı yerine oturtmaya çalışma.", en: "Do not attempt to reduce dislocation.", ja: "脱臼を自分で整復しようとしないでください。"),
                        SurvivalGuideLocalizedText(tr: "Açık kırıkta gereksiz müdahale yapma.", en: "Avoid unnecessary handling in open fractures.", ja: "開放骨折には不必要な処置を加えないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Bölge sabitlendi", en: "Area immobilized", ja: "患部を固定"),
                        SurvivalGuideLocalizedText(tr: "Soğuk uygulama yapıldı", en: "Cold pack applied", ja: "冷却を実施"),
                        SurvivalGuideLocalizedText(tr: "Acil destek planlandı", en: "Emergency support planned", ja: "救急対応を計画"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Kırık/çıkıkta temel yaklaşım hareketi kısıtlamak ve tıbbi yardım istemektir.", en: "Core approach is immobilization and timely medical support.", ja: "骨折・脱臼の基本は、動かさずに固定し、速やかに医療支援を求めることです。")
                ),
                SurvivalGuideArticle(
                    id: "FA-005",
                    title: SurvivalGuideLocalizedText(tr: "Solunum Yolu Tıkanıklığı (Yetişkin/Bebek)", en: "Airway Obstruction (Adult/Infant)", ja: "気道閉塞(成人・乳児)"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil", en: "Urgent", ja: "緊急"),
                    readMinutes: 3,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "Öksürüyorsa öksürmeye teşvik et.", en: "Encourage coughing if effective.", ja: "咳ができる場合は、咳を続けるよう促してください。"),
                        SurvivalGuideLocalizedText(tr: "Tam tıkanmada: 5 sırt vuruşu, ardından 5 karın basısı (yetişkin/çocuk).", en: "For complete obstruction: 5 back blows then 5 abdominal thrusts (adult/child).", ja: "完全閉塞: 背部叩打5回、続けて腹部突き上げ5回(成人・小児)。"),
                        SurvivalGuideLocalizedText(tr: "Bebekte: 5 sırt vuruşu + 5 göğüs basısı.", en: "For infants: 5 back blows + 5 chest thrusts.", ja: "乳児: 背部叩打5回と胸部突き上げ5回を行ってください。"),
                        SurvivalGuideLocalizedText(tr: "Düzelmezse 112 + temel yaşam desteği.", en: "If unresolved, call 112 and start life support.", ja: "改善しない場合は119番に通報し、一次救命処置を始めてください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Yetişkin/çocukta 5+5 döngüsünü sürdür.", en: "Continue 5+5 cycle for adult/child.", ja: "成人・小児では5+5のサイクルを続けてください。"),
                        SurvivalGuideLocalizedText(tr: "Bebekte karın basısı kullanma.", en: "Never use abdominal thrusts on infants.", ja: "乳児に腹部突き上げを絶対に行わないでください。"),
                        SurvivalGuideLocalizedText(tr: "Bilinç kaybında 112 ve bildiğin CPR adımlarına geç.", en: "If unconscious, call 112 and start CPR steps you know.", ja: "意識を失ったら119番に通報し、知っている心肺蘇生の手順を開始してください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "Ağız içinde görmediğin cismi körlemesine çıkarma.", en: "Do not perform blind finger sweeps.", ja: "見えない異物を指でかきだそうとしないでください。"),
                        SurvivalGuideLocalizedText(tr: "Bebekte yetişkin manevrası uygulama.", en: "Do not use adult maneuvers on infants.", ja: "乳児に成人向けの手技を使わないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Tıkanma tipi değerlendirildi", en: "Obstruction severity assessed", ja: "閉塞の重症度を評価"),
                        SurvivalGuideLocalizedText(tr: "Doğru yaş grubuna göre manevra uygulandı", en: "Age-appropriate maneuver applied", ja: "年齢に応じた手技を実施"),
                        SurvivalGuideLocalizedText(tr: "112 hazırda", en: "112 ready/called", ja: "119番を準備・通報"),
                        SurvivalGuideLocalizedText(tr: "Bilinç takibi sürüyor", en: "Consciousness monitoring ongoing", ja: "意識状態を継続観察"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Yetişkin ve bebekte tıkanıklık algoritması farklıdır; kör parmak manevrası yapılmaz.", en: "Adult and infant choking protocols differ; blind finger sweeps are avoided.", ja: "成人と乳児の気道閉塞対応は異なり、見えない異物を指でかきだす行為は避けるべきです。")
                ),
            ]
        ),
        SurvivalGuideCategory(
            id: "psychological_first_aid",
            title: SurvivalGuideLocalizedText(tr: "Psikolojik İlk Yardım", en: "Psychological First Aid", ja: "心のケア(サイコロジカル・ファーストエイド)"),
            description: SurvivalGuideLocalizedText(tr: "Bak-Dinle-Bağla yaklaşımı ile kriz desteği", en: "Crisis support with Look-Listen-Link approach", ja: "『見る・聴く・つなぐ』アプローチによる危機支援"),
            symbolName: "heart.text.square.fill",
            guides: [
                SurvivalGuideArticle(
                    id: "MH-001",
                    title: SurvivalGuideLocalizedText(tr: "Psikolojik İlk Yardım: Bak-Dinle-Bağla", en: "Psychological First Aid: Look-Listen-Link", ja: "心のケア: 見る・聴く・つなぐ"),
                    priority: SurvivalGuideLocalizedText(tr: "Acil / Sonrası", en: "Urgent / Aftercare", ja: "緊急・発災後"),
                    readMinutes: 2,
                    in30Seconds: [
                        SurvivalGuideLocalizedText(tr: "BAK: Güvenlik ve acil ihtiyacı olanları belirle.", en: "LOOK: Identify safety risks and urgent needs.", ja: "見る: 安全上のリスクと緊急の必要性を見極めてください。"),
                        SurvivalGuideLocalizedText(tr: "DİNLE: Sakin, yargısız, kısa cümlelerle dinle.", en: "LISTEN: Stay calm, non-judgmental, brief.", ja: "聴く: 落ち着いて、評価せず、短い言葉で聴いてください。"),
                        SurvivalGuideLocalizedText(tr: "BAĞLA: Doğru desteğe yönlendir.", en: "LINK: Connect people to proper support.", ja: "つなぐ: 適切な支援につないでください。"),
                    ],
                    stepByStep: [
                        SurvivalGuideLocalizedText(tr: "Kendini tanıt ve izinli temas kur.", en: "Introduce yourself and ask consent for contact.", ja: "自己紹介し、接触の同意を得てください。"),
                        SurvivalGuideLocalizedText(tr: "Kişiyi konuşturmaya zorlama.", en: "Do not force them to talk.", ja: "相手に話すことを強要しないでください。"),
                        SurvivalGuideLocalizedText(tr: "Su, battaniye, güvenli alana geçiş gibi basit destek ver.", en: "Provide simple practical support (water, blanket, safer place).", ja: "水、毛布、安全な場所への移動など、具体的な支援を提供してください。"),
                        SurvivalGuideLocalizedText(tr: "Aile iletişimi ve resmi bilgi kanallarına erişimi kolaylaştır.", en: "Help reconnect family communication and official information.", ja: "家族との連絡や公式情報へのアクセスを支援してください。"),
                        SurvivalGuideLocalizedText(tr: "Gerektiğinde profesyonel psikososyal desteğe yönlendir.", en: "Refer to professional psychosocial support when needed.", ja: "必要に応じて、専門の心理社会的支援につないでください。"),
                    ],
                    dontDo: [
                        SurvivalGuideLocalizedText(tr: "“Bir şey olmadı” diyerek duyguyu küçümseme.", en: "Do not minimize feelings with “nothing happened”.", ja: "「大したことじゃないよ」と言って感情を軽視しないでください。"),
                        SurvivalGuideLocalizedText(tr: "İzin almadan dokunma/sarılma.", en: "Do not touch/hug without consent.", ja: "同意なしに触れたり抱きしめたりしないでください。"),
                    ],
                    checklist: [
                        SurvivalGuideLocalizedText(tr: "Güvenlik kontrolü yapıldı", en: "Safety check done", ja: "安全確認を実施"),
                        SurvivalGuideLocalizedText(tr: "Sakin dinleme sağlandı", en: "Calm listening provided", ja: "落ち着いて傾聴した"),
                        SurvivalGuideLocalizedText(tr: "Pratik ihtiyaç karşılandı", en: "Practical needs addressed", ja: "実用的な必要を満たした"),
                        SurvivalGuideLocalizedText(tr: "Uygun desteğe yönlendirme yapıldı", en: "Linked to appropriate support", ja: "適切な支援につないだ"),
                    ],
                    sourceNote: SurvivalGuideLocalizedText(tr: "Bak-Dinle-Bağla modeli afet sonrası psikolojik ilk yardımın temel çerçevesidir.", en: "Look-Listen-Link is a core framework for post-disaster psychological first aid.", ja: "『見る・聴く・つなぐ』は、災害後の心のケアの基本的な枠組みです。")
                ),
            ]
        ),
    ]
}
