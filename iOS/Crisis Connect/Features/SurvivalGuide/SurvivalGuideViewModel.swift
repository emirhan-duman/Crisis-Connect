//
//  SurvivalGuideViewModel.swift
//  Crisis Connect
//
//  Updated by Assistant on 26.02.2026
//

import Foundation
import Combine

struct SurvivalGuideEntry: Identifiable, Hashable {
    let category: SurvivalGuideCategory
    let article: SurvivalGuideArticle

    var id: String { article.id }
}

final class SurvivalGuideViewModel: ObservableObject {
    @Published var selectedCategoryID: String = SurvivalGuideData.allCategoryID
    @Published var searchQuery: String = ""
    @Published var expandedGuideID: String?
    @Published private(set) var checkedChecklistItemIDs: Set<String>

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let stored = defaults.stringArray(forKey: Keys.checkedChecklistItems) ?? []
        self.checkedChecklistItemIDs = Set(stored)
    }

    var categories: [SurvivalGuideCategory] {
        SurvivalGuideData.categories
    }

    var hasActiveFilters: Bool {
        selectedCategoryID != SurvivalGuideData.allCategoryID || searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
    }

    func clearFilters() {
        searchQuery = ""
        selectedCategoryID = SurvivalGuideData.allCategoryID
        expandedGuideID = nil
    }

    func visibleEntries(locale: Locale) -> [SurvivalGuideEntry] {
        let selectedCategories: [SurvivalGuideCategory]
        if selectedCategoryID == SurvivalGuideData.allCategoryID {
            selectedCategories = categories
        } else {
            selectedCategories = categories.filter { $0.id == selectedCategoryID }
        }

        let allEntries = selectedCategories.flatMap { category in
            category.guides.map { article in
                SurvivalGuideEntry(category: category, article: article)
            }
        }

        let queryTokens = searchTokens(locale: locale)
        if queryTokens.isEmpty {
            return allEntries
        }
        return allEntries.filter { matchesQuery(entry: $0, queryTokens: queryTokens, locale: locale) }
    }

    func toggleExpansion(articleID: String) {
        expandedGuideID = expandedGuideID == articleID ? nil : articleID
    }

    func startChecklist(for article: SurvivalGuideArticle) {
        expandedGuideID = article.id
        checkedChecklistItemIDs = checkedChecklistItemIDs.filter { key in
            key.hasPrefix("\(article.id)#") == false
        }
        persistCheckedChecklistItems()
    }

    func toggleChecklist(articleID: String, index: Int) {
        let key = checklistKey(articleID: articleID, index: index)
        if checkedChecklistItemIDs.contains(key) {
            checkedChecklistItemIDs.remove(key)
        } else {
            checkedChecklistItemIDs.insert(key)
        }
        persistCheckedChecklistItems()
    }

    func isChecklistItemChecked(articleID: String, index: Int) -> Bool {
        checkedChecklistItemIDs.contains(checklistKey(articleID: articleID, index: index))
    }

    func checklistProgress(for article: SurvivalGuideArticle) -> (completed: Int, total: Int, ratio: Double) {
        let total = article.checklist.count
        guard total > 0 else {
            return (0, 0, 0)
        }

        let completed = article.checklist.indices.reduce(into: 0) { partialResult, index in
            if isChecklistItemChecked(articleID: article.id, index: index) {
                partialResult += 1
            }
        }

        return (completed, total, Double(completed) / Double(total))
    }

    func readDurationLabel(locale: Locale, minutes: Int) -> String {
        if isJapanese(locale: locale) {
            return "\(minutes) 分"
        }
        if isTurkish(locale: locale) {
            return "\(minutes) dk"
        }
        return "\(minutes) min"
    }

    func emergencyNumber(locale: Locale) -> String {
        let languageCode = localeLanguageCode(locale)
        if languageCode.hasPrefix("tr") {
            return "112"
        }
        if languageCode.hasPrefix("ja") {
            return "119"
        }
        if languageCode.hasPrefix("en") {
            return "911"
        }

        let countryCode = localeRegionCode(locale)?.uppercased() ?? ""
        switch countryCode {
        case "TR": return "112"
        case "US", "CA", "MX": return "911"
        case "GB", "IE": return "999"
        case "AU": return "000"
        case "NZ": return "111"
        case "JP", "KR": return "119"
        case "CN": return "120"
        case "BR": return "190"
        default: return "911"
        }
    }

    func countryLabel(locale: Locale) -> String {
        guard let regionCode = localeRegionCode(locale), regionCode.isEmpty == false else {
            return "N/A"
        }
        return locale.localizedString(forRegionCode: regionCode) ?? regionCode.uppercased()
    }

    private func checklistKey(articleID: String, index: Int) -> String {
        "\(articleID)#\(index)"
    }

    private func persistCheckedChecklistItems() {
        defaults.set(Array(checkedChecklistItemIDs).sorted(), forKey: Keys.checkedChecklistItems)
    }

    private func searchTokens(locale: Locale) -> [String] {
        var seen = Set<String>()
        return searchQuery
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(whereSeparator: { $0.isWhitespace })
            .map { normalizedSearchKey(String($0), locale: locale) }
            .filter { token in
                token.isEmpty == false && seen.insert(token).inserted
            }
    }

    private func normalizedSearchKey(_ text: String, locale: Locale) -> String {
        text
            .lowercased(with: locale)
            .replacingOccurrences(of: "ı", with: "i")
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: locale)
    }

    private func matchesQuery(entry: SurvivalGuideEntry, queryTokens: [String], locale: Locale) -> Bool {
        guard queryTokens.isEmpty == false else { return true }

        var components: [String] = [
            entry.article.id,
            entry.article.id.filter { $0.isLetter || $0.isNumber },
            entry.article.title.resolve(locale: locale),
            entry.article.priority.resolve(locale: locale),
            entry.category.title.resolve(locale: locale),
            entry.category.description.resolve(locale: locale)
        ]

        components.append(contentsOf: entry.article.in30Seconds.map { $0.resolve(locale: locale) })
        components.append(contentsOf: entry.article.stepByStep.map { $0.resolve(locale: locale) })
        components.append(contentsOf: entry.article.dontDo.map { $0.resolve(locale: locale) })
        components.append(contentsOf: entry.article.checklist.map { $0.resolve(locale: locale) })

        let haystack = normalizedSearchKey(components.joined(separator: " "), locale: locale)
        return queryTokens.allSatisfy { haystack.contains($0) }
    }

    private func isTurkish(locale: Locale) -> Bool {
        let languageCode = localeLanguageCode(locale)
        return languageCode.hasPrefix("tr")
    }

    private func isJapanese(locale: Locale) -> Bool {
        let languageCode = localeLanguageCode(locale)
        return languageCode.hasPrefix("ja")
    }

    private func localeLanguageCode(_ locale: Locale) -> String {
        locale.language.languageCode?.identifier.lowercased() ?? locale.identifier.lowercased()
    }

    private func localeRegionCode(_ locale: Locale) -> String? {
        locale.region?.identifier
    }

    private enum Keys {
        static let checkedChecklistItems = "survival_guide_checked_checklist_items"
    }
}
