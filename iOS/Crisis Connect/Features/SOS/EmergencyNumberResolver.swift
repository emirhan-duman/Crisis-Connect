//
//  EmergencyNumberResolver.swift
//  Crisis Connect
//
//  Resolves the emergency number for the device's current region — the iOS port of Android's
//  EmergencyNumberResolver (service/sos/EmergencyNumberResolver.kt). The country→number table is
//  kept byte-identical to Android for parity.
//
//  iOS has no permission-free equivalent of Android's aggregated TelephonyManager emergency list,
//  and CoreTelephony's carrier ISO is deprecated and returns empty on iOS 16+. So the signal is
//  the device region (Settings ▸ General ▸ Language & Region), which is the user's own locale —
//  matched against the curated table, defaulting to 112 (accepted on GSM networks nearly
//  everywhere). This mirrors Android's everyday, permission-free path.
//

import Foundation

enum EmergencyNumberResolver {

    /// Emergency number plus the region it was resolved for (nil when no region could be detected).
    struct RegionalEmergencyNumber: Equatable {
        let number: String
        let countryIso: String?
    }

    private static let defaultEmergencyNumber = "112"

    private static let countryEmergencyNumbers: [String: String] = [
        "TR": "112",
        "US": "911", "CA": "911", "MX": "911",
        "GB": "999", "IE": "112",
        "AU": "000", "NZ": "111",
        "IN": "112", "PK": "15",
        "JP": "110", "KR": "112", "CN": "110", "TW": "110",
        "HK": "999", "SG": "999", "MY": "999",
        "PH": "911", "ID": "112", "TH": "191", "VN": "113",
        "BR": "190", "AR": "911", "CL": "133", "CO": "123", "PE": "105",
        "ZA": "10111", "NG": "112", "KE": "999", "EG": "122",
        "SA": "911", "AE": "999", "IL": "100", "IR": "110",
        "RU": "112", "UA": "112"
        // The EU/EEA and most of the remaining world standardize on 112 — the default.
    ]

    static func resolve() -> String {
        resolveWithRegion().number
    }

    static func resolveWithRegion() -> RegionalEmergencyNumber {
        let iso = detectCountryIso()
        let number = iso.flatMap { countryEmergencyNumbers[$0] } ?? defaultEmergencyNumber
        return RegionalEmergencyNumber(number: number, countryIso: iso)
    }

    /// Looks up the emergency number for an explicit ISO code (used by tests + callers that already
    /// know the region). Falls back to 112.
    static func number(forCountryIso iso: String) -> String {
        countryEmergencyNumbers[iso.uppercased()] ?? defaultEmergencyNumber
    }

    private static func detectCountryIso() -> String? {
        let region: String?
        if #available(iOS 16, *) {
            region = Locale.current.region?.identifier
        } else {
            region = Locale.current.regionCode
        }
        let trimmed = region?.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        return (trimmed?.isEmpty == false) ? trimmed : nil
    }
}
