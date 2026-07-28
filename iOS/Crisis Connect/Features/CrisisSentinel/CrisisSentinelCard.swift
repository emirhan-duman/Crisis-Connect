//
//  CrisisSentinelCard.swift
//  Crisis Connect
//
//  Typed views of the web chat API's `_card` payloads (the 12 field-team kinds) — iOS port of
//  Android's CrisisSentinelCardModels. Parsing is deliberately lenient (every field optional) so a
//  partially populated card renders partially instead of failing. Manager-only / unknown kinds
//  parse to nil and are silently skipped, matching the web's render switch.
//

import Foundation

enum CrisisSentinelCard: Equatable {
    case weather(Weather)
    case quake(Quake)
    case airQuality(AirQuality)
    case hazardEvents(HazardEvents)
    case flood(Flood)
    case alerts(Alerts)
    case quakeImpact(QuakeImpact)
    case route(Route)
    case marine(Marine)
    case satellite(Satellite)
    case facility(Facility)
    case damage(Damage)

    struct Weather: Equatable {
        var label: String?
        var tempC: Double?
        var feelsLikeC: Double?
        var humidity: Double?
        var windKmh: Double?
        var gustKmh: Double?
        var rainMm: Double?
        var precipMm: Double?
    }

    struct Quake: Equatable {
        var region: String?
        var label: String?
        var events: [Event]
        struct Event: Equatable {
            var magnitude: Double?
            var depth: Double?
            var place: String?
            var occurredAt: String?
            var lat: Double?
            var lon: Double?
        }
    }

    struct AirQuality: Equatable {
        var label: String?
        var usAqi: Double?
        var level: String?
        var pm25: Double?
        var pm10: Double?
        var ozone: Double?
        var no2: Double?
        var so2: Double?
        var co: Double?
    }

    struct HazardEvents: Equatable {
        var category: String?
        var label: String?
        var place: String?
        var events: [Event]
        struct Event: Equatable {
            var title: String?
            var category: String?
            var lat: Double?
            var lon: Double?
            var date: String?
        }
    }

    struct Flood: Equatable {
        var label: String?
        var unit: String?
        var todayDischarge: Double?
        var peakDischarge: Double?
        var peakDate: String?
        var rising: Bool?
    }

    struct Alerts: Equatable {
        var title: String?
        var source: String?
        var items: [Item]
        struct Item: Equatable {
            var severity: String?
            var label: String?
            var detail: String?
            var whenText: String?
        }
    }

    struct QuakeImpact: Equatable {
        var events: [Event]
        struct Event: Equatable {
            var mag: Double?
            var place: String?
            var alert: String?
            var mmi: Double?
            var felt: Int64?
            var tsunami: Bool?
            var whenText: String?
        }
    }

    struct Route: Equatable {
        var from: String?
        var to: String?
        var mode: String?
        var distanceKm: Double?
        var durationMin: Double?
        var steps: [Step]
        struct Step: Equatable {
            var text: String?
            var distanceKm: Double?
        }
    }

    struct Marine: Equatable {
        var label: String?
        var waveHeight: Double?
        var severity: String?
        var waveDirection: Double?
        var wavePeriod: Double?
        var windWaveHeight: Double?
        var swellWaveHeight: Double?
        var seaSurfaceTemp: Double?
    }

    struct Satellite: Equatable {
        var label: String?
        var date: String?
        var imageKind: String?
        var widthKm: Double?
        var url: String?
    }

    struct Facility: Equatable {
        var label: String?
        var facilityType: String?
        var facilities: [Entry]
        struct Entry: Equatable {
            var name: String?
            var lat: Double?
            var lon: Double?
            var distanceKm: Double?
        }
    }

    struct Damage: Equatable {
        var rating: String?
        var summary: String?
        var hazards: [String]
    }
}

func parseCrisisSentinelCard(_ cardJson: String) -> CrisisSentinelCard? {
    guard let data = cardJson.data(using: .utf8),
          let root = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
        return nil
    }
    switch root["kind"] as? String {
    case "weather":
        guard let w = root["weather"] as? [String: Any] else { return nil }
        return .weather(.init(
            label: w.str("label"), tempC: w.dbl("tempC"), feelsLikeC: w.dbl("feelsLikeC"),
            humidity: w.dbl("humidity"), windKmh: w.dbl("windKmh"), gustKmh: w.dbl("gustKmh"),
            rainMm: w.dbl("rainMm"), precipMm: w.dbl("precipMm")
        ))
    case "quake": // flat payload
        return .quake(.init(
            region: root.str("region"), label: root.str("label"),
            events: root.arr("events").map { e in
                .init(magnitude: e.dbl("magnitude"), depth: e.dbl("depth"), place: e.str("place"),
                      occurredAt: e.str("occurredAt"), lat: e.dbl("lat"), lon: e.dbl("lon"))
            }
        ))
    case "airquality":
        guard let a = root["airquality"] as? [String: Any] else { return nil }
        return .airQuality(.init(
            label: a.str("label"), usAqi: a.dbl("usAqi"), level: a.str("level"),
            pm25: a.dbl("pm25"), pm10: a.dbl("pm10"), ozone: a.dbl("ozone"),
            no2: a.dbl("no2"), so2: a.dbl("so2"), co: a.dbl("co")
        ))
    case "hazardEvents":
        guard let h = root["hazard"] as? [String: Any] else { return nil }
        return .hazardEvents(.init(
            category: h.str("category"), label: h.str("label"), place: h.str("place"),
            events: h.arr("events").map { e in
                .init(title: e.str("title"), category: e.str("category"),
                      lat: e.dbl("lat"), lon: e.dbl("lon"), date: e.str("date"))
            }
        ))
    case "flood":
        guard let f = root["flood"] as? [String: Any] else { return nil }
        return .flood(.init(
            label: f.str("label"), unit: f.str("unit"), todayDischarge: f.dbl("todayDischarge"),
            peakDischarge: f.dbl("peakDischarge"), peakDate: f.str("peakDate"),
            rising: f["rising"] as? Bool
        ))
    case "alerts":
        guard let a = root["alerts"] as? [String: Any] else { return nil }
        return .alerts(.init(
            title: a.str("title"), source: a.str("source"),
            items: a.arr("items").map { i in
                .init(severity: i.str("severity"), label: i.str("label"),
                      detail: i.str("detail"), whenText: i.str("when"))
            }
        ))
    case "quakeImpact":
        guard let q = root["quakeImpact"] as? [String: Any] else { return nil }
        return .quakeImpact(.init(
            events: q.arr("events").map { e in
                .init(mag: e.dbl("mag"), place: e.str("place"), alert: e.str("alert"),
                      mmi: e.dbl("mmi"), felt: (e["felt"] as? NSNumber)?.int64Value,
                      tsunami: e["tsunami"] as? Bool, whenText: e.str("when"))
            }
        ))
    case "route":
        guard let r = root["route"] as? [String: Any] else { return nil }
        return .route(.init(
            from: r.str("from"), to: r.str("to"), mode: r.str("mode"),
            distanceKm: r.dbl("distanceKm"), durationMin: r.dbl("durationMin"),
            steps: r.arr("steps").map { s in .init(text: s.str("text"), distanceKm: s.dbl("distanceKm")) }
        ))
    case "marine":
        guard let m = root["marine"] as? [String: Any] else { return nil }
        return .marine(.init(
            label: m.str("label"), waveHeight: m.dbl("waveHeight"), severity: m.str("severity"),
            waveDirection: m.dbl("waveDirection"), wavePeriod: m.dbl("wavePeriod"),
            windWaveHeight: m.dbl("windWaveHeight"), swellWaveHeight: m.dbl("swellWaveHeight"),
            seaSurfaceTemp: m.dbl("seaSurfaceTemp")
        ))
    case "satellite":
        guard let s = root["satellite"] as? [String: Any] else { return nil }
        return .satellite(.init(
            label: s.str("label"), date: s.str("date"), imageKind: s.str("imageKind"),
            widthKm: s.dbl("widthKm"), url: s.str("url")
        ))
    case "facility": // flat payload
        return .facility(.init(
            label: root.str("label"), facilityType: root.str("facilityType"),
            facilities: root.arr("facilities").map { f in
                .init(name: f.str("name"), lat: f.dbl("lat"), lon: f.dbl("lon"), distanceKm: f.dbl("distanceKm"))
            }
        ))
    case "damage": // flat payload
        return .damage(.init(
            rating: root.str("rating"), summary: root.str("summary"),
            hazards: (root["hazards"] as? [Any] ?? []).compactMap {
                ($0 as? String)?.trimmingCharacters(in: .whitespaces).nilIfEmpty
            }
        ))
    default:
        return nil
    }
}

// MARK: - Lenient JSON accessors (parity with Android's optStringOrNull/optDoubleOrNull/mapArray)

private extension [String: Any] {
    func str(_ key: String) -> String? {
        (self[key] as? String)?.trimmingCharacters(in: .whitespaces).nilIfEmpty
    }
    func dbl(_ key: String) -> Double? {
        if let n = self[key] as? NSNumber { return n.doubleValue }
        if let s = self[key] as? String { return Double(s) }
        return nil
    }
    func arr(_ key: String) -> [[String: Any]] {
        (self[key] as? [[String: Any]]) ?? []
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
