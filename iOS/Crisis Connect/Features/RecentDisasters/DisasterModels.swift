//
//  DisasterModels.swift
//  Crisis Connect
//
//  Ported from the Android "Recent Disasters" tool.
//

import Foundation
import CoreLocation

// ─── Domain model ───────────────────────────────────────────────────────────

/// Which feed the UI is currently showing.
enum DisasterFeed {
    case global
    case local
}

/// Why a load failed. Only set when every network source actually failed.
enum DisasterErrorType {
    case offline
    case timeout
    case noData
}

/// Source identifiers, kept as constants so priority ordering and labels stay
/// in sync across the repository and the UI.
enum DisasterSource {
    static let gdacs = "GDACS"
    static let afad = "AFAD"
    static let usgs = "USGS"
    static let emsc = "EMSC"
    static let kandilli = "Kandilli"
}

/// A single normalized disaster event, regardless of which source it came from.
struct DisasterEvent: Identifiable, Equatable, Codable, Sendable {
    let eventId: Int
    let title: String
    let eventDescription: String
    let htmlDescription: String
    let eventType: String      // EQ, FL, TC, VO, WF, DR
    let alertLevel: String     // Red, Orange, Green
    let country: String
    let fromDate: String
    let toDate: String
    let severityText: String
    let magnitude: Double?     // populated for earthquakes; nil otherwise
    let source: String         // "GDACS", "AFAD", "USGS", "EMSC", "Kandilli"
    let eventTimeMillis: Int64 // parsed epoch (0 if unknown) — used for sorting + relative time
    let latitude: Double
    let longitude: Double

    /// Stable identity for SwiftUI lists (the numeric id alone can collide across sources).
    var id: String { "\(source)-\(eventId)-\(eventTimeMillis)" }

    var hasCoordinates: Bool { latitude != 0.0 || longitude != 0.0 }
}

/// Outcome of a load. `isStale` is true only when we are showing cached data
/// *because all live sources failed* — NOT when the cache was still within its
/// TTL. That distinction keeps the "offline" banner from appearing while online.
struct DisasterFetchResult {
    let events: [DisasterEvent]
    let lastUpdatedMillis: Int64
    let isStale: Bool
    let error: DisasterErrorType?
    let primarySource: String
}

/// The country/region the "local" tab represents. Resolved from where the user
/// actually is (GPS → IP → locale), not from the app language.
struct DisasterRegion: Equatable {
    let countryCode: String   // ISO-3166 alpha-2, e.g. "TR"
    let countryName: String   // localized display name for the tab label
    let minLat: Double
    let maxLat: Double
    let minLon: Double
    let maxLon: Double

    func contains(_ lat: Double, _ lon: Double) -> Bool {
        (lat != 0.0 || lon != 0.0)
            && (minLat...maxLat).contains(lat)
            && (minLon...maxLon).contains(lon)
    }
}

// ─── Shared geo / time utilities ──────────────────────────────────────────────

enum DisasterGeo {
    /// Great-circle distance in kilometres.
    static func haversineKm(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6371.0
        let dLat = (lat2 - lat1) * .pi / 180.0
        let dLon = (lon2 - lon1) * .pi / 180.0
        let a = sin(dLat / 2) * sin(dLat / 2)
            + cos(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0)
            * sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /// Initial bearing in degrees (0–360) from point 1 to point 2.
    static func bearing(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let dLon = (lon2 - lon1) * .pi / 180.0
        let y = sin(dLon) * cos(lat2 * .pi / 180.0)
        let x = cos(lat1 * .pi / 180.0) * sin(lat2 * .pi / 180.0)
            - sin(lat1 * .pi / 180.0) * cos(lat2 * .pi / 180.0) * cos(dLon)
        return (atan2(y, x) * 180.0 / .pi + 360.0).truncatingRemainder(dividingBy: 360.0)
    }
}

/// Best-effort parse of the various date strings the feeds return into an epoch
/// (milliseconds). Tries with and without fractional seconds. Returns 0 when
/// nothing matches.
enum DisasterDate {
    private static let patterns = [
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd"
    ]

    static func epochMillis(_ raw: String, utc: Bool) -> Int64 {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return 0 }
        let cleaned = trimmed.hasSuffix("Z") ? String(trimmed.dropLast()) : trimmed
        let candidates = [cleaned, String(cleaned.prefix(while: { $0 != "." }))]
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = utc ? TimeZone(identifier: "UTC") : TimeZone.current
        for candidate in candidates {
            for pattern in patterns {
                formatter.dateFormat = pattern
                if let date = formatter.date(from: candidate) {
                    return Int64((date.timeIntervalSince1970 * 1000).rounded())
                }
            }
        }
        return 0
    }

    static func isoUtc(_ millis: Int64) -> String {
        guard millis > 0 else { return "" }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        return formatter.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000))
    }
}

// ─── In-app-language-aware localization ───────────────────────────────────────

/// Dynamic strings built outside the SwiftUI `Text` layer (in the repository /
/// view model) must honour the in-app language override, which `NSLocalizedString`
/// does not. This helper resolves the chosen `.lproj` bundle from the same
/// `appLanguage` preference the rest of the app uses.
enum RDLocalized {
    private static var languageCode: String? {
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "system"
        return lang == "system" ? nil : lang
    }

    static var locale: Locale {
        if let languageCode { return Locale(identifier: languageCode) }
        return Locale.current
    }

    private static var bundle: Bundle {
        if let languageCode,
           let path = Bundle.main.path(forResource: languageCode, ofType: "lproj"),
           let localized = Bundle(path: path) {
            return localized
        }
        return .main
    }

    static func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: nil, table: nil)
    }

    static func format(_ key: String, _ args: CVarArg...) -> String {
        String(format: string(key), locale: locale, arguments: args)
    }
}

// ─── Country bounding boxes ───────────────────────────────────────────────────

/// Approximate ISO-3166 alpha-2 → bounding box `[minLat, maxLat, minLon, maxLon]`.
/// Covers the most populous / seismically active countries; anything missing
/// falls back to a radius around the detected coordinates.
enum CountryBounds {
    static func bbox(_ iso: String) -> [Double]? { table[iso.uppercased()] }

    private static let table: [String: [Double]] = [
        "TR": [35.8, 42.2, 25.6, 44.9],
        "JP": [24.0, 46.0, 122.0, 146.0],
        "IN": [6.0, 37.1, 68.1, 97.4],
        "US": [24.4, 49.4, -125.0, -66.9],
        "GB": [49.9, 60.9, -8.6, 1.8],
        "DE": [47.2, 55.1, 5.8, 15.0],
        "FR": [41.3, 51.1, -5.1, 9.6],
        "ES": [36.0, 43.8, -9.3, 3.3],
        "IT": [36.6, 47.1, 6.6, 18.5],
        "GR": [34.8, 41.8, 19.3, 28.2],
        "PT": [36.9, 42.2, -9.5, -6.2],
        "NL": [50.7, 53.6, 3.3, 7.2],
        "CH": [45.8, 47.8, 5.9, 10.5],
        "AT": [46.3, 49.0, 9.5, 17.2],
        "PL": [49.0, 54.9, 14.1, 24.2],
        "RO": [43.6, 48.3, 20.2, 29.7],
        "RU": [41.2, 77.0, 19.6, 180.0],
        "UA": [44.4, 52.4, 22.1, 40.2],
        "IR": [25.0, 39.8, 44.0, 63.3],
        "IQ": [29.1, 37.4, 38.8, 48.6],
        "SY": [32.3, 37.3, 35.7, 42.4],
        "IL": [29.5, 33.3, 34.3, 35.9],
        "SA": [16.3, 32.2, 34.5, 55.7],
        "AE": [22.6, 26.1, 51.6, 56.4],
        "EG": [22.0, 31.7, 25.0, 35.0],
        "PK": [23.7, 37.1, 60.9, 77.8],
        "AF": [29.4, 38.5, 60.5, 74.9],
        "CN": [18.2, 53.6, 73.5, 134.8],
        "ID": [-11.0, 6.1, 95.0, 141.0],
        "PH": [5.0, 19.6, 117.2, 126.6],
        "NP": [26.3, 30.4, 80.1, 88.2],
        "TH": [5.6, 20.5, 97.3, 105.6],
        "VN": [8.6, 23.4, 102.1, 109.5],
        "KR": [33.2, 38.6, 126.1, 129.6],
        "TW": [21.9, 25.3, 120.0, 122.0],
        "MX": [14.5, 32.7, -118.4, -86.7],
        "BR": [-33.8, 5.3, -73.9, -34.8],
        "AR": [-55.1, -21.8, -73.6, -53.6],
        "CL": [-55.9, -17.5, -75.6, -66.4],
        "PE": [-18.4, 0.0, -81.4, -68.7],
        "CO": [-4.2, 12.5, -79.0, -66.9],
        "EC": [-5.0, 1.4, -81.0, -75.2],
        "CA": [41.7, 70.0, -141.0, -52.6],
        "AU": [-43.6, -10.7, 113.3, 153.6],
        "NZ": [-47.3, -34.4, 166.4, 178.6],
        "ZA": [-34.8, -22.1, 16.5, 32.9],
        "NG": [4.3, 13.9, 2.7, 14.7],
        "KE": [-4.7, 5.0, 33.9, 41.9],
        "ET": [3.4, 14.9, 33.0, 48.0],
        "MA": [27.7, 35.9, -13.2, -1.0],
        "DZ": [19.0, 37.1, -8.7, 12.0],
        "GE": [41.0, 43.6, 40.0, 46.7],
        "AZ": [38.4, 41.9, 44.8, 50.4],
        "AM": [38.8, 41.3, 43.4, 46.6]
    ]
}
