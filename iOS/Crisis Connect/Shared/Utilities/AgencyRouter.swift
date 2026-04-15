//
//  AgencyRouter.swift
//  Crisis Connect
//
//  Created by Assistant on 12.03.2026.
//

import Foundation

enum AgencyRouter {
    private static let countryAgencyMap: [String: String] = [
        "TR": "AFAD",
        "US": "FEMA",
        "IN": "NDMA",
        "JP": "JMA"
    ]

    private static let emailDomainAgencyMap: [String: String] = [
        "afad.gov.tr": "AFAD",
        "fema.dhs.gov": "FEMA",
        "dhs.gov": "FEMA",
        "ndma.gov.in": "NDMA",
        "jma.go.jp": "JMA"
    ]

    static func agencyForCountry(_ countryCode: String) -> String? {
        let normalized = countryCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        guard !normalized.isEmpty else { return nil }
        return countryAgencyMap[normalized] ?? "International"
    }

    static func agencyForEmail(_ email: String) -> String? {
        let domain = email
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .substring(after: "@")
            .lowercased()
        guard !domain.isEmpty else { return nil }
        if let exact = emailDomainAgencyMap[domain] {
            return exact
        }
        return emailDomainAgencyMap.first(where: { domain.hasSuffix(".\($0.key)") })?.value
    }

    static func slugify(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !trimmed.isEmpty else { return "" }
        let sanitized = trimmed.map { character -> Character in
            switch character {
            case "a"..."z", "0"..."9":
                return character
            default:
                return "-"
            }
        }
        return String(sanitized)
            .replacingOccurrences(of: "-{2,}", with: "-", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet(charactersIn: "-"))
    }

    static func normalizedPanelId(_ value: String?) -> String {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !trimmed.isEmpty else { return "" }
        return trimmed.replacingOccurrences(of: "/", with: "-")
    }

    static func resolvePanelId(
        candidates: [String?],
        fallbackAgency: String
    ) -> String {
        for candidate in candidates {
            let normalized = normalizedPanelId(candidate)
            if !normalized.isEmpty {
                return normalized
            }
        }

        return slugify(fallbackAgency)
    }
}

private extension String {
    func substring(after separator: Character) -> String {
        guard let index = firstIndex(of: separator) else { return "" }
        let next = self.index(after: index)
        return String(self[next...]).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
