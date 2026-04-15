//
//  LanguageSettingsViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

final class LanguageSettingsViewModel: ObservableObject {
    @Published var selectedLanguage: String {
        didSet {
            settings.appLanguage = selectedLanguage
        }
    }

    let options: [LanguageOption]
    private let settings: AppSettingsViewModel

    init(settings: AppSettingsViewModel) {
        self.settings = settings
        self.selectedLanguage = settings.appLanguage
        self.options = [
            LanguageOption(id: "system", titleKey: "System Default"),
            LanguageOption(id: "en", titleKey: "English"),
            LanguageOption(id: "tr", titleKey: "Turkish")
        ]
    }

    func select(_ option: LanguageOption) {
        selectedLanguage = option.id
    }

    func syncFromSettings() {
        selectedLanguage = settings.appLanguage
    }
}

struct LanguageOption: Identifiable {
    let id: String
    let titleKey: String
}
