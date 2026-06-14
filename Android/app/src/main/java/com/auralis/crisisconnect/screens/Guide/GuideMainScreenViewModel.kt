package com.auralis.crisisconnect.screens.Guide

import java.util.Locale

class GuideMainScreenViewModel {
    data class LocalizedText(
        val tr: String,
        val en: String = tr,
        val es: String = en
    ) {
        fun resolve(locale: Locale): String {
            return when {
                locale.language.equals("tr", ignoreCase = true) -> tr
                locale.language.equals("es", ignoreCase = true) -> es
                else -> en
            }
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
        private fun lt(tr: String, en: String = tr, es: String = en) = LocalizedText(tr = tr, en = en, es = es)

        private val PRIORITY_URGENT = lt("Acil", "Urgent", es = "Urgente")
        private val PRIORITY_PREP = lt("Hazırlık", "Preparedness", es = "Preparación")
        private val PRIORITY_URGENT_AFTER = lt("Acil / Sonrası", "Urgent / Aftercare", es = "Urgencia / Cuidados posteriores")

        val CATEGORIES = listOf(
            GuideCategory(
                id = "emergency_info",
                title = lt("Acil Bilgiler", "Emergency Info", es = "Información de emergencia"),
                description = lt(
                    "112, aile planı, toplanma alanı ve 72 saat çantası",
                    "911, family plan, assembly area and 72-hour go-bag",
                    es = "112, plan familiar, área de reunión y bolsa de viaje de 72 horas"
                ),
                iconRes = android.R.drawable.ic_dialog_info,
                guides = listOf(
                    GuideArticle(
                        id = "G-001",
                        title = lt("112 ve Acil Arama Kılavuzu", "911 Emergency Call Guide", es = "Guía de llamadas de emergencia al 112"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("Hayati tehlike varsa 112'yi ara.", "Call 911 immediately for life-threatening situations.", es = "Llame al 112 de inmediato en caso de situaciones que pongan en peligro su vida."),
                            lt("Konumu net ver: il/ilçe/mahalle/sokak/bina no.", "Give exact location: city/district/neighborhood/street/building.", es = "Indique la ubicación exacta: ciudad/distrito/barrio/calle/edificio."),
                            lt("Olayı tek cümlede özetle: ne oldu, kaç kişi etkilendi.", "Summarize in one sentence: what happened and how many are affected.", es = "Resume en una frase: qué pasó y cuántos afectados."),
                            lt("Operatör kapat demeden telefonu kapatma.", "Do not hang up until the operator tells you.", es = "No cuelgues hasta que el operador te lo indique.")
                        ),
                        stepByStep = listOf(
                            lt("Kesin adres + yakın işaret (okul, market, cami, metro).", "Share exact address and nearest landmark.", es = "Comparta la dirección exacta y el punto de referencia más cercano."),
                            lt("Kim arıyor + geri aranacak telefon numarası.", "State caller name and callback number.", es = "Indique el nombre de la persona que llama y el número de devolución de llamada."),
                            lt("Olay türü: deprem, yangın, yaralanma, mahsur kalma.", "State incident type: quake, fire, injury, trapped person.", es = "Estado tipo de incidente: terremoto, incendio, heridos, persona atrapada."),
                            lt("Kişi sayısı ve durum: bilinç, nefes, kanama.", "State victim count and condition: consciousness, breathing, bleeding.", es = "Estado del recuento y estado de las víctimas: conciencia, respiración, sangrado."),
                            lt("Ek riskler: gaz kokusu, duman, çökme riski.", "Mention extra risks: gas odor, smoke, collapse risk.", es = "Mencione riesgos adicionales: olor a gas, humo, riesgo de colapso.")
                        ),
                        dontDo = listOf(
                            lt("Panikle bağırıp adres bilgisini atlama.", "Do not skip address details due to panic.", es = "No omita los detalles de la dirección debido al pánico."),
                            lt("“Tam bilmiyorum” deyip hattı belirsiz bırakma.", "Do not leave the call vague with “I don't know”.", es = "No dejes la llamada vaga con un “no sé”.")
                        ),
                        checklist = listOf(
                            lt("Adres hazır", "Address ready", es = "Dirección lista"),
                            lt("Olay türü hazır", "Incident type ready", es = "Tipo de incidente listo"),
                            lt("Yaralı sayısı hazır", "Victim count ready", es = "Listo el recuento de víctimas"),
                            lt("Ek risk bilgisi hazır", "Additional risk info ready", es = "Información de riesgo adicional lista")
                        ),
                        sourceNote = lt(
                            "112 tek acil numaradır; adres, olay tanımı ve kişi durumu net verilmelidir.",
                            "911 is the single emergency number; location, incident and victim status must be clear.",
                            es = "112 es el único número de emergencia; La ubicación, el incidente y el estado de la víctima deben ser claros."
                        )
                    ),
                    GuideArticle(
                        id = "G-002",
                        title = lt("Aile Acil Durum Planı", "Family Emergency Plan", es = "Plan de Emergencia Familiar"),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("1 şehir dışı irtibat kişisi belirle.", "Assign one out-of-city contact.", es = "Asigne un contacto fuera de la ciudad."),
                            lt("2 buluşma noktası seç: mahalle içi + mahalle dışı.", "Set two meeting points: local and outside neighborhood.", es = "Establecer dos puntos de encuentro: local y exterior del barrio."),
                            lt("Herkeste acil kartı olsun: kimlik kopyası + numaralar.", "Everyone carries an emergency card: ID copy + key numbers.", es = "Todo el mundo lleva consigo una tarjeta de emergencia: copia del DNI + números de clave.")
                        ),
                        stepByStep = listOf(
                            lt("Aynı irtibat kişisini tüm aile kullansın.", "All family members contact the same person.", es = "Todos los miembros de la familia contactan a la misma persona."),
                            lt("Hat yoğunluğunda SMS kuralını kullan.", "Use SMS when voice lines are congested.", es = "Utilice SMS cuando las líneas de voz estén congestionadas."),
                            lt("Plan sırası: Ev yoksa nokta-1, olmazsa nokta-2.", "Protocol: if home fails use point-1, then point-2.", es = "Protocolo: si el hogar falla, utilice el punto 1 y luego el punto 2."),
                            lt("Rol dağıt: çanta, çocuk/yaşlı, evcil hayvan sorumlusu.", "Assign roles: bag, children/elderly, pets.", es = "Asigne roles: bolso, niños/ancianos, mascotas.")
                        ),
                        dontDo = listOf(
                            lt("Tek buluşma noktasına güvenme.", "Do not rely on only one meeting point.", es = "No confíes en un solo punto de encuentro."),
                            lt("Herkes farklı kişiyi aramasın.", "Do not scatter information by calling different people.", es = "No disperses información llamando a diferentes personas.")
                        ),
                        checklist = listOf(
                            lt("Şehir dışı kişi seçildi", "Out-of-city contact selected", es = "Contacto fuera de la ciudad seleccionado"),
                            lt("2 buluşma noktası kaydedildi", "Two meeting points saved", es = "Dos puntos de encuentro guardados"),
                            lt("Acil kartlar hazırlandı", "Emergency cards prepared", es = "Tarjetas de emergencia preparadas"),
                            lt("Rol dağıtımı yapıldı", "Roles assigned", es = "Roles asignados")
                        ),
                        sourceNote = lt(
                            "Aile planlarında şehir dışı irtibat ve SMS odaklı iletişim önerilir.",
                            "Family plans recommend one out-of-area contact and SMS-first communication.",
                            es = "Los planes familiares recomiendan un contacto fuera del área y comunicación por SMS primero."
                        )
                    ),
                    GuideArticle(
                        id = "G-003",
                        title = lt(
                            "Toplanma Alanı Nedir? E-Devlet'ten Nasıl Bulunur?",
                            "What Is an Assembly Area? How to Find It via e-Devlet",
                            es = "¿Qué es un área de reunión? Cómo encontrarlo a través de e-Devlet"
                        ),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Toplanma alanı afet sonrası ilk güvenli buluşma noktasıdır.", "Assembly area is the first safe post-disaster meeting point.", es = "El área de reunión es el primer punto de encuentro seguro después de un desastre."),
                            lt("E-Devlet'te “Toplanma Alanı Sorgulama” ile bulunur.", "Find it in e-Devlet with “Assembly Area Query”.", es = "Encuéntrelo en e-Devlet con \"Consulta del área de ensamblaje\"."),
                            lt("En yakın alanın adres ve konumunu kaydet.", "Save nearest area address and map location.", es = "Guarde la dirección del área más cercana y la ubicación en el mapa.")
                        ),
                        stepByStep = listOf(
                            lt("E-Devlet'e giriş yap.", "Sign in to e-Devlet.", es = "Inicie sesión en e-Devlet."),
                            lt("Arama kutusuna “Toplanma Alanı Sorgulama” yaz.", "Search for “Toplanma Alanı Sorgulama”.", es = "Busque “Toplanma Alanı Sorgulama”."),
                            lt("Haritada en yakını seç ve kaydet.", "Pick the nearest on map and save it.", es = "Elija el más cercano en el mapa y guárdelo."),
                            lt("Aile ile paylaş, 2 alternatif rota çıkar.", "Share with family and define two routes.", es = "Comparte en familia y define dos rutas.")
                        ),
                        dontDo = listOf(
                            lt("Toplanma alanını barınma alanı ile karıştırma.", "Do not confuse assembly area with shelter area.", es = "No confundir zona de reunión con zona de refugio.")
                        ),
                        checklist = listOf(
                            lt("En yakın toplanma alanı bulundu", "Nearest assembly area found", es = "Área de reunión más cercana encontrada"),
                            lt("Adres + koordinat kaydedildi", "Address + coordinates saved", es = "Dirección + coordenadas guardadas"),
                            lt("Aile ile paylaşıldı", "Shared with family", es = "Compartido con la familia"),
                            lt("Alternatif rotalar çizildi", "Alternative routes planned", es = "Rutas alternativas previstas")
                        ),
                        sourceNote = lt(
                            "Toplanma alanı sorgulaması AFAD/e-Devlet üzerinden resmi olarak sunulur.",
                            "Assembly area lookup is officially provided via AFAD/e-Devlet.",
                            es = "La búsqueda del área de montaje se proporciona oficialmente a través de AFAD/e-Devlet."
                        )
                    ),
                    GuideArticle(
                        id = "G-004",
                        title = lt(
                            "Afet & Acil Durum Çantası (72 Saat)",
                            "Disaster Go-Bag (72 Hours)",
                            es = "Bolsa de emergencia para desastres (72 horas)"
                        ),
                        priority = PRIORITY_PREP,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Hedef: ilk 72 saati kendi başına yönetebilmek.", "Goal: self-manage the first 72 hours.", es = "Objetivo: autogestionarse las primeras 72 horas."),
                            lt("Omurga: su, gıda, ilk yardım, ışık, iletişim, evrak.", "Core: water, food, first aid, light, communication, documents.", es = "Núcleo: agua, comida, primeros auxilios, luz, comunicación, documentos."),
                            lt("Çanta kapıya yakın ve erişilebilir yerde olsun.", "Keep the bag near the exit and easy to reach.", es = "Mantenga la bolsa cerca de la salida y de fácil acceso.")
                        ),
                        stepByStep = listOf(
                            lt("Su + bozulmayan gıda + konserve açacağı ekle.", "Pack water, non-perishable food, and can opener.", es = "Empacar agua, alimentos no perecederos y abrelatas."),
                            lt("Düzenli ilaçlar + reçete + temel ilk yardım seti koy.", "Add regular meds, prescriptions, and first aid kit.", es = "Agregue medicamentos habituales, recetas y botiquín de primeros auxilios."),
                            lt("Fener, pil, powerbank, kablo ve mümkünse radyo koy.", "Add flashlight, batteries, powerbank, cable, and radio.", es = "Agregue linterna, baterías, powerbank, cable y radio."),
                            lt("Kimlik ve kritik belge fotokopileri + bir miktar nakit ekle.", "Add ID/critical document copies and cash.", es = "Agregue copias de documentos de identificación/documentos críticos y efectivo."),
                            lt("Bebek/yaşlı/engelli/evcil için özel malzemeyi unutma.", "Include special items for babies/elderly/disabled/pets.", es = "Incluya artículos especiales para bebés/ancianos/discapacitados/mascotas.")
                        ),
                        dontDo = listOf(
                            lt("Çantayı evde ulaşılmaz yere kaldırma.", "Do not store the bag in an unreachable place.", es = "No guarde la bolsa en un lugar inalcanzable."),
                            lt("Son kullanma tarihi kontrolünü atlama.", "Do not skip expiry-date checks.", es = "No se salte los controles de fecha de vencimiento.")
                        ),
                        checklist = listOf(
                            lt("Su ve gıda hazır", "Water and food packed", es = "Envasado de agua y comida."),
                            lt("İlk yardım ve ilaç hazır", "First aid and medications packed", es = "Primeros auxilios y medicamentos empacados."),
                            lt("Işık/iletişim ekipmanı hazır", "Light/communication kit packed", es = "Kit de luz/comunicación embalado"),
                            lt("Belge ve nakit hazır", "Documents and cash packed", es = "Documentos y dinero en efectivo empaquetados."),
                            lt("Özel ihtiyaç malzemeleri hazır", "Special-needs supplies packed", es = "Suministros para necesidades especiales empacados")
                        ),
                        sourceNote = lt(
                            "AFAD dayanıklı gıda ve önemli belge fotokopilerini çantada önerir.",
                            "AFAD recommends durable food and copies of critical documents in the go-bag.",
                            es = "AFAD recomienda llevar alimentos duraderos y copias de documentos críticos en la bolsa de viaje."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "earthquake",
                title = lt("Deprem", "Earthquake", es = "Terremoto"),
                description = lt(
                    "Deprem öncesi hazırlık, deprem anı ve ilk 30 dakika",
                    "Preparedness, immediate response, and first 30 minutes",
                    es = "Preparación, respuesta inmediata y primeros 30 minutos"
                ),
                iconRes = android.R.drawable.ic_dialog_alert,
                guides = listOf(
                    GuideArticle(
                        id = "E-001",
                        title = lt("Deprem Öncesi Ev Güvenliği (Risk Azaltma)", "Home Safety Before Earthquakes", es = "Seguridad en el hogar antes de los terremotos"),
                        priority = PRIORITY_PREP,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Dolap, raf, TV gibi devrilebilir eşyaları sabitle.", "Anchor wardrobes, shelves, and TVs.", es = "Anclar armarios, estanterías y televisores."),
                            lt("Ağır eşyaları alt raflara al.", "Move heavy items to lower shelves.", es = "Mueva los artículos pesados ​​a los estantes inferiores."),
                            lt("Kaçış yolunu açık tut ve mini tatbikat yap.", "Keep exit path clear and run mini drills.", es = "Mantenga despejado el camino de salida y realice mini simulacros.")
                        ),
                        stepByStep = listOf(
                            lt("Yatak başını cam/pencereden uzaklaştır.", "Keep bed away from windows/glass.", es = "Mantenga la cama alejada de ventanas/vidrios."),
                            lt("Kapı önü ve koridoru daima boş bırak.", "Keep doorways and corridors clear.", es = "Mantenga despejadas las puertas y pasillos."),
                            lt("Gaz, elektrik, su vanalarının yerini öğren.", "Know where gas, electric, and water shutoffs are.", es = "Sepa dónde están los cortes de gas, electricidad y agua."),
                            lt("Evde Çök-Kapan-Tutun pratiği yap.", "Practice Drop-Cover-Hold at home.", es = "Practique Drop-Cover-Hold en casa.")
                        ),
                        dontDo = listOf(
                            lt("Ağır çerçeve/ayna gibi objeleri yatak üstüne asma.", "Do not hang heavy mirrors/frames above beds.", es = "No cuelgue espejos o marcos pesados ​​encima de las camas.")
                        ),
                        checklist = listOf(
                            lt("Mobilya sabitleme tamam", "Furniture anchoring complete", es = "Anclaje de muebles completo"),
                            lt("Kaçış rotası temiz", "Exit route clear", es = "Ruta de salida despejada"),
                            lt("Vana noktaları öğrenildi", "Shutoff points known", es = "Puntos de corte conocidos"),
                            lt("Ev tatbikatı yapıldı", "Home drill completed", es = "Simulacro en casa completado")
                        ),
                        sourceNote = lt(
                            "Deprem öncesinde sabitleme ve deprem anında korunma davranışı kritik görülür.",
                            "Anchoring before a quake and protective posture during a quake are critical.",
                            es = "Anclarse antes de un terremoto y adoptar una postura protectora durante un terremoto son fundamentales."
                        )
                    ),
                    GuideArticle(
                        id = "E-002",
                        title = lt("Deprem Anında: Çök-Kapan-Tutun", "During an Earthquake: Drop-Cover-Hold", es = "Durante un terremoto: agacharse, cubrirse y mantenerse firme"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("ÇÖK-KAPAN-TUTUN uygula.", "Apply DROP-COVER-HOLD.", es = "Aplique AGÁCHESE-CÚBRASE-AGÁRRESE."),
                            lt("Cam/pencere ve düşebilecek eşyalardan uzak dur.", "Stay away from windows and falling objects.", es = "Manténgase alejado de ventanas y objetos que caigan."),
                            lt("Asansör, merdiven, balkon tarafına koşma.", "Do not run toward elevator, stairs, or balcony.", es = "No corra hacia el ascensor, las escaleras o el balcón.")
                        ),
                        stepByStep = listOf(
                            lt("Sağlam masa yanında veya altında pozisyon al.", "Take position under/next to sturdy furniture.", es = "Colóquese debajo o junto a muebles resistentes."),
                            lt("Diz üstüne çök, baş-boynu koruyarak kapan.", "Drop to knees and cover head/neck.", es = "Arrodíllese y cúbrase la cabeza y el cuello."),
                            lt("Sabit noktaya tutun ve sarsıntı bitene kadar kal.", "Hold on and stay until shaking ends.", es = "Aguanta y quédate hasta que termine de agitar."),
                            lt("Dışarıdaysan bina ve direklerden uzak açık alana geç.", "If outside, move away from buildings and poles.", es = "Si está afuera, aléjese de edificios y postes."),
                            lt("Araç içindeysen güvenli yerde dur, araçta kal.", "If in a vehicle, stop safely and stay inside.", es = "Si está en un vehículo, deténgase de manera segura y quédese adentro.")
                        ),
                        dontDo = listOf(
                            lt("Panikle dışarı fırlama.", "Do not rush outside in panic.", es = "No salga corriendo presa del pánico.")
                        ),
                        checklist = listOf(
                            lt("Güvenli nokta bul", "Find a safe spot", es = "Encuentra un lugar seguro"),
                            lt("Baş-boyun koru", "Protect head and neck", es = "Proteger la cabeza y el cuello"),
                            lt("Sarsıntı bitene kadar pozisyonu koru", "Hold position until shaking stops", es = "Mantenga la posición hasta que cese el temblor.")
                        ),
                        sourceNote = lt(
                            "Deprem anında Çök-Kapan-Tutun ve camdan uzak kalma temel yaklaşımdır.",
                            "Drop-Cover-Hold and staying away from glass are core guidance.",
                            es = "Agacharse, cubrirse, sostenerse y mantenerse alejado del vidrio son pautas fundamentales."
                        )
                    ),
                    GuideArticle(
                        id = "E-003",
                        title = lt("Deprem Sonrası: İlk 30 Dakika", "After Earthquake: First 30 Minutes", es = "Después del terremoto: primeros 30 minutos"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Önce kendi güvenliğini sağla.", "Secure your own safety first.", es = "Asegure su propia seguridad primero."),
                            lt("Gaz kokusu varsa gazı kapat, havalandır, binayı terk et.", "If gas odor exists, shut gas, ventilate, and evacuate.", es = "Si existe olor a gas, cierre el gas, ventile y evacue."),
                            lt("Çantanı al ve toplanma alanına git.", "Take go-bag and move to assembly area.", es = "Tome la bolsa de viaje y muévase al área de reunión.")
                        ),
                        stepByStep = listOf(
                            lt("Yaralı kontrolü yap, 112'ye bilgi ver.", "Check injuries and call 911.", es = "Verifique las lesiones y llame al 112."),
                            lt("Duman, çökme, gaz riski var mı değerlendir.", "Assess smoke, collapse, gas risks.", es = "Evaluar riesgos de humo, colapso, gases."),
                            lt("Uygunsa elektrik-gaz-su vanalarını kapat.", "If safe, shut electricity-gas-water.", es = "Si es seguro, cierre la electricidad, el gas y el agua."),
                            lt("Önceden belirlenen rotadan binayı terk et.", "Evacuate via pre-planned route.", es = "Evacue por una ruta previamente planificada."),
                            lt("Resmi duyuruları takip et, yolları acil araçlara bırak.", "Follow official updates and keep roads clear.", es = "Siga las actualizaciones oficiales y mantenga las carreteras despejadas.")
                        ),
                        dontDo = listOf(
                            lt("Hasarlı binaya geri girme.", "Do not re-enter damaged buildings.", es = "No vuelva a ingresar a los edificios dañados."),
                            lt("Gereksiz araç kullanıp trafiği kilitleme.", "Do not block roads with unnecessary driving.", es = "No bloquee las carreteras con una conducción innecesaria.")
                        ),
                        checklist = listOf(
                            lt("Yaralı kontrolü yapıldı", "Injury check completed", es = "Verificación de lesiones completada"),
                            lt("Vana kontrolleri yapıldı", "Utility shutoff check completed", es = "Verificación de corte de servicios públicos completada"),
                            lt("Çanta alındı", "Go-bag taken", es = "Bolsa de viaje tomada"),
                            lt("Toplanma alanına çıkıldı", "Moved to assembly area", es = "Movido al área de reunión")
                        ),
                        sourceNote = lt(
                            "Deprem sonrasında vana kontrolü, bina tahliyesi ve toplanma alanına geçiş önerilir.",
                            "Post-quake guidance includes utility shutoff, evacuation, and assembly-area transfer.",
                            es = "La orientación posterior al terremoto incluye el corte de servicios públicos, la evacuación y el traslado del área de reunión."
                        )
                    ),
                    GuideArticle(
                        id = "E-004",
                        title = lt("Artçı Depremler ve Hasarlı Binalar", "Aftershocks and Damaged Buildings", es = "Réplicas y edificios dañados"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Büyük deprem sonrası artçı beklenir.", "After major quakes, aftershocks are expected.", es = "Después de grandes terremotos, se esperan réplicas."),
                            lt("Hasarlı binaya girme.", "Do not enter damaged structures.", es = "No entre en estructuras dañadas."),
                            lt("Artçıda yine Çök-Kapan-Tutun.", "Use Drop-Cover-Hold during aftershocks.", es = "Utilice Drop-Cover-Hold durante las réplicas.")
                        ),
                        stepByStep = listOf(
                            lt("Resmi yönlendirme gelmeden eve dönme.", "Do not return home before official guidance.", es = "No regrese a casa antes de las indicaciones oficiales."),
                            lt("Toplanma alanında kal ve duyuruları izle.", "Stay at assembly area and monitor updates.", es = "Permanecer en el área de reunión y monitorear las actualizaciones."),
                            lt("Artçı başladığında yakındaki güvenli pozisyonu al.", "Take nearby protective position when aftershock starts.", es = "Tome una posición protectora cercana cuando comience la réplica.")
                        ),
                        dontDo = listOf(
                            lt("“Bir şey olmaz” deyip çatlak binada bekleme.", "Do not stay in cracked buildings assuming safety.", es = "No permanezca en edificios agrietados por seguridad.")
                        ),
                        checklist = listOf(
                            lt("Artçı riski hatırlandı", "Aftershock risk acknowledged", es = "Se reconoce riesgo de réplica"),
                            lt("Hasarlı bina terk edildi", "Damaged building avoided", es = "Se evitó el edificio dañado"),
                            lt("Resmi duyurular takipte", "Official alerts monitored", es = "Alertas oficiales monitoreadas")
                        ),
                        sourceNote = lt(
                            "Artçılar devam ederken hasarlı yapılara girilmemesi vurgulanır.",
                            "Guidance stresses avoiding damaged buildings while aftershocks continue.",
                            es = "La orientación hace hincapié en evitar los edificios dañados mientras continúan las réplicas."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "fire",
                title = lt("Yangın", "Fire", es = "Fuego"),
                description = lt(
                    "Yangında ilk dakikalar, duman tahliyesi ve kıyafet tutuşması",
                    "First minutes, smoke evacuation, and clothing ignition",
                    es = "Primeros minutos, evacuación de humos y encendido de ropa."
                ),
                iconRes = android.R.drawable.ic_menu_compass,
                guides = listOf(
                    GuideArticle(
                        id = "F-001",
                        title = lt("Evde Yangın: İlk Dakikalar ve Tahliye", "Home Fire: First Minutes and Evacuation", es = "Incendio en el hogar: primeros minutos y evacuación"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Sakin ol, 112'yi ara.", "Stay calm and call 911.", es = "Mantenga la calma y llame al 112."),
                            lt("Güvenliyse elektrik/doğalgazı kapat.", "If safe, shut electricity/natural gas.", es = "Si es seguro, cierre la electricidad y el gas natural."),
                            lt("Yangın büyüyorsa hemen tahliye et.", "Evacuate immediately if fire is growing.", es = "Evacuar inmediatamente si el fuego está creciendo.")
                        ),
                        stepByStep = listOf(
                            lt("Kapı ve pencereleri açık bırakma.", "Do not leave doors/windows open.", es = "No deje puertas/ventanas abiertas."),
                            lt("Tahliyede asansör kullanma.", "Never use elevators during evacuation.", es = "Nunca utilice ascensores durante la evacuación."),
                            lt("Çıkamıyorsan güvenli odada kalıp görünür ol.", "If trapped, stay in safer room and stay visible.", es = "Si está atrapado, quédese en una habitación más segura y permanezca visible."),
                            lt("Düşük pozisyonda ilerleyerek temiz çıkışa yönel.", "Move low and head to nearest safe exit.", es = "Muévase hacia abajo y diríjase a la salida segura más cercana.")
                        ),
                        dontDo = listOf(
                            lt("Eşya kurtarmaya çalışma.", "Do not try to save belongings.", es = "No intentes guardar pertenencias."),
                            lt("Duman içinde ayakta koşma.", "Do not run upright in smoke.", es = "No corras erguido en medio del humo.")
                        ),
                        checklist = listOf(
                            lt("112 arandı", "911 called", es = "112 llamado"),
                            lt("Tahliye kararı verildi", "Evacuation decision made", es = "Decisión de evacuación tomada"),
                            lt("Asansör kullanılmadı", "Elevator avoided", es = "Ascensor evitado"),
                            lt("Güvenli çıkışa ilerleniyor", "Moving toward safe exit", es = "Avanzando hacia una salida segura")
                        ),
                        sourceNote = lt(
                            "Yangın anında 112 araması, asansör kullanmama ve hızlı tahliye öne çıkar.",
                            "Calling 911, avoiding elevators, and immediate evacuation are key fire actions.",
                            es = "Llamar al 112, evitar los ascensores y la evacuación inmediata son acciones clave en caso de incendio."
                        )
                    ),
                    GuideArticle(
                        id = "F-002",
                        title = lt("Dumanlı Ortamda Tahliye", "Evacuation in Smoke", es = "Evacuación en humo"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("Duman yukarıda birikir: çömel veya emekle.", "Smoke rises: crouch or crawl.", es = "El humo sube: agáchese o gatee."),
                            lt("Ağzı-burnu bezle kapatıp en yakın çıkışa yönel.", "Cover nose/mouth and move to nearest exit.", es = "Cubra la nariz/boca y diríjase a la salida más cercana.")
                        ),
                        stepByStep = listOf(
                            lt("Kapı kolunu elle kontrol et; aşırı sıcaksa kullanma.", "Check door heat; avoid very hot doors.", es = "Verifique la temperatura de la puerta; Evite puertas muy calientes."),
                            lt("Duvar hattını takip ederek yönünü koru.", "Follow wall line to maintain orientation.", es = "Siga la línea de la pared para mantener la orientación."),
                            lt("Çocuk ve yaşlıları önde yönlendir, geriden destekle.", "Guide children/elderly first and support from behind.", es = "Guíe a los niños/ancianos primero y apóyelos desde atrás.")
                        ),
                        dontDo = listOf(
                            lt("Dumanlı alanda dik yürümeye çalışma.", "Do not walk upright in heavy smoke.", es = "No camine erguido en medio de humo denso."),
                            lt("Saklanma noktalarına girme.", "Do not hide in enclosed spots.", es = "No te escondas en lugares cerrados.")
                        ),
                        checklist = listOf(
                            lt("Düşük pozisyon alındı", "Low posture assumed", es = "Postura baja asumida"),
                            lt("Ağız-burun kapatıldı", "Mouth and nose covered", es = "Boca y nariz tapadas"),
                            lt("En yakın çıkış belirlendi", "Nearest exit identified", es = "Salida más cercana identificada")
                        ),
                        sourceNote = lt(
                            "Dumanlı ortamlarda eğilerek/emekleyerek ilerleme önerilir.",
                            "Crouching/crawling is recommended for smoke-filled environments.",
                            es = "Se recomienda agacharse o gatear en ambientes llenos de humo."
                        )
                    ),
                    GuideArticle(
                        id = "F-003",
                        title = lt("Kıyafetin Tutuşursa: Dur-Yat-Yuvarlan", "If Clothing Catches Fire: Stop-Drop-Roll", es = "Si la ropa se incendia: detenerse, soltarse y rodar"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 1,
                        in30Seconds = listOf(
                            lt("DUR: Koşma.", "STOP: Do not run.", es = "DETÉNGASE: No corra."),
                            lt("YAT: Yere yat.", "DROP: Get on the ground.", es = "CAÍDA: Tírate al suelo."),
                            lt("YUVARLAN: Alev sönene kadar yuvarlan.", "ROLL until flames are out.", es = "ENROLLAR hasta que se apaguen las llamas.")
                        ),
                        stepByStep = listOf(
                            lt("Yüzü ellerle koruyarak yuvarlan.", "Protect face with hands while rolling.", es = "Proteja la cara con las manos mientras rueda."),
                            lt("Mümkünse kalın kumaşla alevi boğ.", "If available, smother flames with thick fabric.", es = "Si está disponible, sofoque las llamas con una tela gruesa."),
                            lt("Yanık varsa soğut ve sağlık yardımı al.", "Cool burns and seek medical help.", es = "Enfríe las quemaduras y busque ayuda médica.")
                        ),
                        dontDo = listOf(
                            lt("Alev varken koşma veya rüzgara dönme.", "Do not run or face wind while burning.", es = "No corra ni se enfrente al viento mientras quema.")
                        ),
                        checklist = listOf(
                            lt("Duruldu", "Stopped moving", es = "dejó de moverse"),
                            lt("Yere yatıldı", "Dropped to ground", es = "Cayó al suelo"),
                            lt("Yuvarlanıldı", "Rolled to extinguish", es = "Rodado para extinguir")
                        ),
                        sourceNote = lt(
                            "Kıyafet tutuşmasında temel kural Dur-Yat-Yuvarlan yaklaşımıdır.",
                            "Stop-Drop-Roll is the core approach for clothing fires.",
                            es = "Stop-Drop-Roll es el enfoque principal para los incendios de ropa."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "flood",
                title = lt("Sel / Taşkın", "Flood", es = "Inundación"),
                description = lt(
                    "Sel anında yüksek yere çıkma ve suya girmeme",
                    "Move to high ground and stay out of floodwater",
                    es = "Muévase a un terreno elevado y manténgase alejado del agua de la inundación."
                ),
                iconRes = android.R.drawable.ic_menu_directions,
                guides = listOf(
                    GuideArticle(
                        id = "W-001",
                        title = lt("Sel Sırasında: Yüksek Yere Çık, Suya Girme", "During Flood: Go Higher, Stay Out of Water", es = "Durante la inundación: suba más alto, manténgase fuera del agua"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Sel bölgesini terk et, yüksek noktaya çık.", "Leave flooded area and go to high ground.", es = "Salga del área inundada y diríjase a un terreno elevado."),
                            lt("Elektrik kaynaklarından uzak dur.", "Stay away from electrical sources.", es = "Manténgase alejado de fuentes eléctricas."),
                            lt("Araçla su kaplı yola girme.", "Do not drive into water-covered roads.", es = "No conduzca por carreteras cubiertas de agua.")
                        ),
                        stepByStep = listOf(
                            lt("Çocukları sel suyundan uzak tut.", "Keep children away from floodwater.", es = "Mantenga a los niños alejados del agua de las inundaciones."),
                            lt("Araç arızalanırsa aracı bırakıp yüksek yere çık.", "If car stalls, leave vehicle and move higher.", es = "Si el automóvil se detiene, abandone el vehículo y suba más arriba."),
                            lt("Gece sürüşünde su derinliğini varsayma.", "At night, never assume water depth.", es = "Por la noche, nunca asuma la profundidad del agua."),
                            lt("112'ye konumu net ilet.", "Call 911 and provide precise location.", es = "Llame al 112 y proporcione la ubicación precisa.")
                        ),
                        dontDo = listOf(
                            lt("Akan suya yürüyerek veya araçla girme.", "Do not enter moving water by foot or vehicle.", es = "No entre en agua en movimiento a pie o en vehículo.")
                        ),
                        checklist = listOf(
                            lt("Yüksek güvenli nokta bulundu", "High safe point reached", es = "Punto alto de seguridad alcanzado"),
                            lt("Elektrik kaynaklarından uzaklaşıldı", "Moved away from electrical risk", es = "Alejándose del riesgo eléctrico"),
                            lt("112 bilgilendirildi", "911 notified", es = "112 notificado")
                        ),
                        sourceNote = lt(
                            "Sel sırasında yüksek bölgeye geçiş ve su kaplı yollardan kaçınma önerilir.",
                            "Flood guidance prioritizes high ground and avoiding water-covered roads.",
                            es = "La guía de inundaciones prioriza los terrenos elevados y evita las carreteras cubiertas de agua."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "outage_co",
                title = lt("Kesintiler & Karbonmonoksit", "Outages & Carbon Monoxide", es = "Cortes y monóxido de carbono"),
                description = lt(
                    "Elektrik kesintisinde CO zehirlenmesini önleme",
                    "Prevent carbon monoxide poisoning during outages",
                    es = "Prevenir el envenenamiento por monóxido de carbono durante los apagones"
                ),
                iconRes = android.R.drawable.ic_lock_power_off,
                guides = listOf(
                    GuideArticle(
                        id = "P-001",
                        title = lt("Elektrik Kesintisi: Karbonmonoksit (CO) Riski", "Power Outage: Carbon Monoxide (CO) Risk", es = "Corte de energía: riesgo de monóxido de carbono (CO)"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("CO kokusuz ve görünmez; ölümcül olabilir.", "CO is odorless/invisible and can be fatal.", es = "El CO es inodoro/invisible y puede ser mortal."),
                            lt("Jeneratör/mangal/ocak kapalı alanda kullanılmaz.", "Never use fuel-burning devices indoors.", es = "Nunca utilice dispositivos que quemen combustible en interiores."),
                            lt("Belirti varsa temiz havaya çık ve 112 ara.", "If symptoms appear, move to fresh air and call 911.", es = "Si aparecen síntomas, salga al aire libre y llame al 112.")
                        ),
                        stepByStep = listOf(
                            lt("Jeneratörü ev, bodrum, garaj içinde çalıştırma.", "Do not run generator in home/basement/garage.", es = "No haga funcionar el generador en la casa/sótano/garaje."),
                            lt("Dışarıda çalıştırırken kapı-pencereden uzak tut.", "Operate it outdoors away from doors/windows.", es = "Opérelo al aire libre, lejos de puertas/ventanas."),
                            lt("Baş ağrısı, baş dönmesi, bulantı, halsizlikte alanı terk et.", "Leave area if headache, dizziness, nausea, weakness occur.", es = "Salga del área si se presenta dolor de cabeza, mareos, náuseas o debilidad."),
                            lt("Mümkünse pil yedekli CO alarmı kullan.", "Use battery-backed CO alarm when possible.", es = "Utilice una alarma de CO con batería cuando sea posible.")
                        ),
                        dontDo = listOf(
                            lt("“Kapıyı araladım yeter” diye düşünme.", "Do not assume cracked doors make indoor use safe.", es = "No asuma que las puertas agrietadas hacen que el uso en interiores sea seguro."),
                            lt("Aracı kapalı garajda ısınmak için çalıştırma.", "Do not idle car in closed garage for heating.", es = "No deje el vehículo inactivo en un garaje cerrado para calentarlo.")
                        ),
                        checklist = listOf(
                            lt("Yanmalı cihazlar iç mekandan çıkarıldı", "Fuel-burning devices kept outside", es = "Dispositivos que queman combustible mantenidos afuera."),
                            lt("Jeneratör açıklıklardan uzak", "Generator away from openings", es = "Generador alejado de las aberturas"),
                            lt("CO belirtileri takipte", "CO symptoms monitored", es = "Síntomas de CO monitorizados"),
                            lt("CO alarmı kontrol edildi", "CO alarm checked", es = "Alarma de CO comprobada")
                        ),
                        sourceNote = lt(
                            "CO riskinde kapalı alan kullanımından kaçınmak ve belirtilerde hızlı tahliye önerilir.",
                            "Avoid indoor fuel use and evacuate quickly when CO symptoms appear.",
                            es = "Evite el uso de combustible en interiores y evacue rápidamente cuando aparezcan síntomas de CO."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "first_aid",
                title = lt("İlk Yardım", "First Aid", es = "Primeros auxilios"),
                description = lt(
                    "KBB yaklaşımı, kanama, yanık, kırık ve tıkanma",
                    "Protection-report-rescue, bleeding, burns, fractures, choking",
                    es = "Protección-informe-rescate, hemorragias, quemaduras, fracturas, asfixias"
                ),
                iconRes = android.R.drawable.ic_menu_info_details,
                guides = listOf(
                    GuideArticle(
                        id = "FA-001",
                        title = lt(
                            "İlk Yardımın Temeli: Koruma-Bildirme-Kurtarma + ABC",
                            "First Aid Basics: Protect-Report-Rescue + ABC",
                            es = "Conceptos básicos de primeros auxilios: proteger-informar-rescate + ABC"
                        ),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("KORU: Önce olay yerini güvenli yap.", "PROTECT: Secure the scene first.", es = "PROTEGER: Asegure la escena primero."),
                            lt("BİLDİR: 112'yi ara.", "REPORT: Call 911.", es = "INFORME: Llame al 112."),
                            lt("KURTAR: Bildiğin güvenli müdahaleyi yap.", "RESCUE: Apply safe actions you know.", es = "RESCATE: Aplica acciones seguras que conozcas.")
                        ),
                        stepByStep = listOf(
                            lt("A: Hava yolu açık mı kontrol et.", "A: Check airway.", es = "A: Compruebe las vías respiratorias."),
                            lt("B: Nefes var mı kontrol et.", "B: Check breathing.", es = "B: Comprobar la respiración."),
                            lt("C: Büyük kanama var mı kontrol et.", "C: Check major bleeding.", es = "C: Verifique el sangrado mayor."),
                            lt("Sürekli yeniden değerlendir ve kötüleşmede 112'yi güncelle.", "Reassess continuously and update 911 if worsening.", es = "Vuelva a evaluar continuamente y actualice al 112 si empeora.")
                        ),
                        dontDo = listOf(
                            lt("Tehlike yoksa yaralıyı gereksiz oynatma.", "Do not move casualty unnecessarily.", es = "No mueva a la víctima innecesariamente."),
                            lt("Bilinci kapalı kişiye ağızdan bir şey verme.", "Do not give anything by mouth to unconscious person.", es = "No le dé nada por vía oral a una persona inconsciente.")
                        ),
                        checklist = listOf(
                            lt("Olay yeri güvenli", "Scene is safe", es = "La escena es segura"),
                            lt("112 haberdar", "911 informed", es = "112 informado"),
                            lt("ABC kontrolü yapıldı", "ABC checked", es = "ABC comprobado"),
                            lt("Durum izleniyor", "Condition monitored", es = "Condición monitoreada")
                        ),
                        sourceNote = lt(
                            "KBB yaklaşımı ve temel ilk yardım adımları eğitimli şekilde uygulanmalıdır.",
                            "KBB-style first aid actions should be applied with training.",
                            es = "Las acciones de primeros auxilios al estilo KBB deben aplicarse con capacitación."
                        )
                    ),
                    GuideArticle(
                        id = "FA-002",
                        title = lt("Şiddetli Kanama: Bası, Bandaj, Turnike", "Severe Bleeding: Pressure, Bandage, Tourniquet", es = "Sangrado severo: presión, vendaje, torniquete"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("İlk hedef: kanamayı durdur.", "Primary goal: stop bleeding.", es = "Objetivo principal: detener el sangrado."),
                            lt("İlk adım: doğrudan bası.", "First step: direct pressure.", es = "Primer paso: presión directa."),
                            lt("Hayati uzuv kanamasında bası yetmezse turnike.", "For life-threatening limb bleeding, use tourniquet if pressure fails.", es = "En caso de hemorragia de una extremidad que ponga en peligro la vida, utilice un torniquete si falla la presión.")
                        ),
                        stepByStep = listOf(
                            lt("Temiz bariyerle yaraya güçlü bası uygula.", "Apply strong pressure with clean barrier.", es = "Aplique fuerte presión con una barrera limpia."),
                            lt("Bez kaldırmadan üstüne yeni bez ekle.", "Add cloth on top without removing soaked layer.", es = "Agrega un paño encima sin quitar la capa empapada."),
                            lt("Basınçlı bandajla basıyı sürdür.", "Maintain pressure with pressure bandage.", es = "Mantener la presión con vendaje compresivo."),
                            lt("Durmayan ağır uzuv kanamasında uygun turnike uygula ve zamanı not et.", "Use proper tourniquet for uncontrolled severe limb bleeding and note time.", es = "Utilice un torniquete adecuado para hemorragias graves e incontroladas de las extremidades y observe el tiempo."),
                            lt("112'yi ara ve şok belirtilerini izle.", "Call 911 and monitor shock signs.", es = "Llame al 112 y controle las señales de shock.")
                        ),
                        dontDo = listOf(
                            lt("Kanlı bezi kaldırıp pıhtıyı bozma.", "Do not remove cloth and break clot.", es = "No retire la tela ni rompa el coágulo."),
                            lt("Turnikeyi her kanamada kullanma.", "Do not use tourniquet for every bleed.", es = "No utilice torniquete en cada sangrado.")
                        ),
                        checklist = listOf(
                            lt("Doğrudan bası uygulandı", "Direct pressure applied", es = "Presión directa aplicada"),
                            lt("Bandajla bası sürdürülüyor", "Pressure maintained with bandage", es = "Presión mantenida con vendaje."),
                            lt("Turnike gerekiyorsa doğru uygulandı", "Tourniquet used correctly if needed", es = "Torniquete usado correctamente si es necesario"),
                            lt("112 bilgilendirildi", "911 informed", es = "112 informado")
                        ),
                        sourceNote = lt(
                            "Kanamada bası önceliklidir; turnike belirli, ağır durumlarda uygulanır.",
                            "Direct pressure is first-line; tourniquet is for specific severe cases.",
                            es = "La presión directa es de primera línea; El torniquete es para casos graves específicos."
                        )
                    ),
                    GuideArticle(
                        id = "FA-003",
                        title = lt("Yanıklar: Soğut, Koru, Yanlışları Yapma", "Burns: Cool, Protect, Avoid Mistakes", es = "Quemaduras: enfriar, proteger, evitar errores"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Yanığı hemen soğut (en az 20 dk).", "Cool burn immediately (at least 20 min).", es = "Enfríe la quemadura de inmediato (al menos 20 min)."),
                            lt("Takıları çıkar, kabarcık patlatma.", "Remove jewelry, do not pop blisters.", es = "Quítese las joyas, no reviente las ampollas."),
                            lt("Geniş yanıkta 112 ara.", "Call 911 for extensive burns.", es = "Llame al 112 en caso de quemaduras extensas.")
                        ),
                        stepByStep = listOf(
                            lt("Isı kaynağını kes ve güvenliği sağla.", "Stop heat source and secure scene.", es = "Detenga la fuente de calor y asegure la escena."),
                            lt("Yanığı serin suyla sürekli soğut.", "Cool with running cool water.", es = "Enfriar con agua corriente fría."),
                            lt("Yüzük/saat gibi takıları erken çıkar.", "Remove rings/watches early.", es = "Quítese los anillos/relojes con antelación."),
                            lt("Temiz bezle gevşek ört.", "Cover loosely with clean dressing.", es = "Cubra sin apretar con un apósito limpio."),
                            lt("Solunum etkilenmişse acil destek iste.", "Seek urgent care if breathing affected.", es = "Busque atención urgente si la respiración se ve afectada.")
                        ),
                        dontDo = listOf(
                            lt("Diş macunu/yoğurt/yağ sürme.", "Do not apply toothpaste/yogurt/oil.", es = "No aplique pasta de dientes/yogurt/aceite."),
                            lt("Kabarcıkları patlatma.", "Do not pop blisters.", es = "No reviente las ampollas.")
                        ),
                        checklist = listOf(
                            lt("Soğutma başlatıldı", "Cooling started", es = "Enfriamiento iniciado"),
                            lt("Takılar çıkarıldı", "Jewelry removed", es = "Se quitaron las joyas"),
                            lt("Temiz örtü uygulandı", "Clean cover applied", es = "Cubierta limpia aplicada"),
                            lt("Gerekirse 112 arandı", "911 called when needed", es = "112 llamado cuando es necesario")
                        ),
                        sourceNote = lt(
                            "Yanıkta 20 dakika soğutma ve yanlış uygulamalardan kaçınma öne çıkar.",
                            "20-minute cooling and avoiding harmful home remedies are emphasized.",
                            es = "Se enfatiza el enfriamiento de 20 minutos y evitar remedios caseros dañinos."
                        )
                    ),
                    GuideArticle(
                        id = "FA-004",
                        title = lt("Kırık / Çıkık / Burkulma", "Fracture / Dislocation / Sprain", es = "Fractura / Dislocación / Esguince"),
                        priority = PRIORITY_URGENT,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("Oynatma, sabitle.", "Do not move; immobilize.", es = "No se mueva; inmovilizar."),
                            lt("Şişlik için soğuk uygula.", "Apply cold for swelling.", es = "Aplicar frío para la hinchazón."),
                            lt("112 veya sağlık birimine başvur.", "Call 911 or seek medical care.", es = "Llame al 112 o busque atención médica.")
                        ),
                        stepByStep = listOf(
                            lt("Şüpheli bölgeyi bulunduğu pozisyonda tespit et.", "Immobilize in found position.", es = "Inmovilizar en la posición encontrada."),
                            lt("Ağrı, şekil bozukluğu, uyuşmada aciliyet artır.", "Escalate urgency for severe pain/deformity/numbness.", es = "Aumente la urgencia en caso de dolor intenso, deformidad o entumecimiento."),
                            lt("Soğuk uygulamayı aralıklı sürdür.", "Continue intermittent cold packs.", es = "Continúe con las compresas frías intermitentes."),
                            lt("Açık kırıkta steril örtü ve hızlı sevk sağla.", "For open fracture, sterile cover and rapid transfer.", es = "Para fractura abierta, cobertura estéril y transferencia rápida.")
                        ),
                        dontDo = listOf(
                            lt("Çıkığı yerine oturtmaya çalışma.", "Do not attempt to reduce dislocation.", es = "No intente reducir la dislocación."),
                            lt("Açık kırıkta gereksiz müdahale yapma.", "Avoid unnecessary handling in open fractures.", es = "Evite manipulaciones innecesarias en fracturas abiertas.")
                        ),
                        checklist = listOf(
                            lt("Bölge sabitlendi", "Area immobilized", es = "Área inmovilizada"),
                            lt("Soğuk uygulama yapıldı", "Cold pack applied", es = "Paquete frío aplicado"),
                            lt("Acil destek planlandı", "Emergency support planned", es = "Apoyo de emergencia planeado")
                        ),
                        sourceNote = lt(
                            "Kırık/çıkıkta temel yaklaşım hareketi kısıtlamak ve tıbbi yardım istemektir.",
                            "Core approach is immobilization and timely medical support.",
                            es = "El enfoque básico es la inmovilización y la asistencia médica oportuna."
                        )
                    ),
                    GuideArticle(
                        id = "FA-005",
                        title = lt(
                            "Solunum Yolu Tıkanıklığı (Yetişkin/Bebek)",
                            "Airway Obstruction (Adult/Infant)",
                            es = "Obstrucción de las vías respiratorias (adulto/bebé)"
                        ),
                        priority = PRIORITY_URGENT,
                        readMinutes = 3,
                        in30Seconds = listOf(
                            lt("Öksürüyorsa öksürmeye teşvik et.", "Encourage coughing if effective.", es = "Fomente la tos si es eficaz."),
                            lt("Tam tıkanmada: 5 sırt vuruşu, ardından 5 karın basısı (yetişkin/çocuk).", "For complete obstruction: 5 back blows then 5 abdominal thrusts (adult/child).", es = "Para obstrucción completa: 5 golpes en la espalda y luego 5 compresiones abdominales (adulto/niño)."),
                            lt("Bebekte: 5 sırt vuruşu + 5 göğüs basısı.", "For infants: 5 back blows + 5 chest thrusts.", es = "Para bebés: 5 golpes en la espalda + 5 compresiones en el pecho."),
                            lt("Düzelmezse 112 + temel yaşam desteği.", "If unresolved, call 911 and start life support.", es = "Si no se resuelve, llame al 112 e inicie el soporte vital.")
                        ),
                        stepByStep = listOf(
                            lt("Yetişkin/çocukta 5+5 döngüsünü sürdür.", "Continue 5+5 cycle for adult/child.", es = "Continúe el ciclo 5+5 para adulto/niño."),
                            lt("Bebekte karın basısı kullanma.", "Never use abdominal thrusts on infants.", es = "Nunca utilice compresiones abdominales en bebés."),
                            lt("Bilinç kaybında 112 ve bildiğin CPR adımlarına geç.", "If unconscious, call 911 and start CPR steps you know.", es = "Si está inconsciente, llame al 112 y comience los pasos de RCP que conoce.")
                        ),
                        dontDo = listOf(
                            lt("Ağız içinde görmediğin cismi körlemesine çıkarma.", "Do not perform blind finger sweeps.", es = "No realice barridos con los dedos a ciegas."),
                            lt("Bebekte yetişkin manevrası uygulama.", "Do not use adult maneuvers on infants.", es = "No utilice maniobras de adultos en bebés.")
                        ),
                        checklist = listOf(
                            lt("Tıkanma tipi değerlendirildi", "Obstruction severity assessed", es = "Gravedad de la obstrucción evaluada"),
                            lt("Doğru yaş grubuna göre manevra uygulandı", "Age-appropriate maneuver applied", es = "Maniobra apropiada para la edad aplicada"),
                            lt("112 hazırda", "911 ready/called", es = "112 listo/llamado"),
                            lt("Bilinç takibi sürüyor", "Consciousness monitoring ongoing", es = "Monitoreo de la conciencia en curso")
                        ),
                        sourceNote = lt(
                            "Yetişkin ve bebekte tıkanıklık algoritması farklıdır; kör parmak manevrası yapılmaz.",
                            "Adult and infant choking protocols differ; blind finger sweeps are avoided.",
                            es = "Los protocolos de asfixia para adultos y bebés difieren; Se evitan los barridos con los dedos a ciegas."
                        )
                    )
                )
            ),
            GuideCategory(
                id = "psychological_first_aid",
                title = lt("Psikolojik İlk Yardım", "Psychological First Aid", es = "Primeros auxilios psicológicos"),
                description = lt(
                    "Bak-Dinle-Bağla yaklaşımı ile kriz desteği",
                    "Crisis support with Look-Listen-Link approach",
                    es = "Apoyo en crisis con el enfoque Mirar-Escuchar-Vincular"
                ),
                iconRes = android.R.drawable.ic_menu_help,
                guides = listOf(
                    GuideArticle(
                        id = "MH-001",
                        title = lt("Psikolojik İlk Yardım: Bak-Dinle-Bağla", "Psychological First Aid: Look-Listen-Link", es = "Primeros Auxilios Psicológicos: Mira-Escucha-Enlace"),
                        priority = PRIORITY_URGENT_AFTER,
                        readMinutes = 2,
                        in30Seconds = listOf(
                            lt("BAK: Güvenlik ve acil ihtiyacı olanları belirle.", "LOOK: Identify safety risks and urgent needs.", es = "MIRAR: Identificar riesgos de seguridad y necesidades urgentes."),
                            lt("DİNLE: Sakin, yargısız, kısa cümlelerle dinle.", "LISTEN: Stay calm, non-judgmental, brief.", es = "ESCUCHE: Mantenga la calma, no juzgue y sea breve."),
                            lt("BAĞLA: Doğru desteğe yönlendir.", "LINK: Connect people to proper support.", es = "ENLACE: Conecte a las personas con el soporte adecuado.")
                        ),
                        stepByStep = listOf(
                            lt("Kendini tanıt ve izinli temas kur.", "Introduce yourself and ask consent for contact.", es = "Preséntate y pide consentimiento para el contacto."),
                            lt("Kişiyi konuşturmaya zorlama.", "Do not force them to talk.", es = "No los obligues a hablar."),
                            lt("Su, battaniye, güvenli alana geçiş gibi basit destek ver.", "Provide simple practical support (water, blanket, safer place).", es = "Proporcionar apoyo práctico sencillo (agua, manta, lugar más seguro)."),
                            lt("Aile iletişimi ve resmi bilgi kanallarına erişimi kolaylaştır.", "Help reconnect family communication and official information.", es = "Ayuda a reconectar la comunicación familiar y la información oficial."),
                            lt("Gerektiğinde profesyonel psikososyal desteğe yönlendir.", "Refer to professional psychosocial support when needed.", es = "Remitirse a apoyo psicosocial profesional cuando sea necesario.")
                        ),
                        dontDo = listOf(
                            lt("“Bir şey olmadı” diyerek duyguyu küçümseme.", "Do not minimize feelings with “nothing happened”.", es = "No minimices los sentimientos con “no pasó nada”."),
                            lt("İzin almadan dokunma/sarılma.", "Do not touch/hug without consent.", es = "No tocar ni abrazar sin consentimiento.")
                        ),
                        checklist = listOf(
                            lt("Güvenlik kontrolü yapıldı", "Safety check done", es = "Control de seguridad realizado"),
                            lt("Sakin dinleme sağlandı", "Calm listening provided", es = "Escucha tranquila proporcionada"),
                            lt("Pratik ihtiyaç karşılandı", "Practical needs addressed", es = "Necesidades prácticas abordadas"),
                            lt("Uygun desteğe yönlendirme yapıldı", "Linked to appropriate support", es = "Vinculado al soporte adecuado")
                        ),
                        sourceNote = lt(
                            "Bak-Dinle-Bağla modeli afet sonrası psikolojik ilk yardımın temel çerçevesidir.",
                            "Look-Listen-Link is a core framework for post-disaster psychological first aid.",
                            es = "Look-Listen-Link es un marco central para primeros auxilios psicológicos posteriores a un desastre."
                        )
                    )
                )
            )
        )
    }
}
