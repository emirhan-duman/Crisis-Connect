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
        // Each language is shown by its own native name (autonym) so users can
        // find their language regardless of the UI language, and so the list does
        // not need an N×N matrix of translated language names. Only add an entry
        // once the matching <code>.lproj</code> exists.
        self.options = [
            LanguageOption(id: "system", titleKey: "System Default"),
            LanguageOption(id: "en", titleKey: "English"),
            LanguageOption(id: "tr", titleKey: "Türkçe"),
            LanguageOption(id: "ja", titleKey: "日本語"),
            LanguageOption(id: "ar", titleKey: "العربية"),
            LanguageOption(id: "bn", titleKey: "বাংলা"),
            LanguageOption(id: "de", titleKey: "Deutsch"),
            LanguageOption(id: "es", titleKey: "Español"),
            LanguageOption(id: "fa", titleKey: "فارسی"),
            LanguageOption(id: "fil", titleKey: "Filipino"),
            LanguageOption(id: "fr", titleKey: "Français"),
            LanguageOption(id: "hi", titleKey: "हिन्दी"),
            LanguageOption(id: "id", titleKey: "Bahasa Indonesia"),
            LanguageOption(id: "ku", titleKey: "Kurdî"),
            LanguageOption(id: "pt", titleKey: "Português"),
            LanguageOption(id: "ru", titleKey: "Русский"),
            LanguageOption(id: "uk", titleKey: "Українська"),
            LanguageOption(id: "ur", titleKey: "اردو"),
            LanguageOption(id: "vi", titleKey: "Tiếng Việt"),
            LanguageOption(id: "zh", titleKey: "中文")
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
