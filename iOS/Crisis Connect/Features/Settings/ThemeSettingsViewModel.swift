//
//  ThemeSettingsViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine

final class ThemeSettingsViewModel: ObservableObject {
    @Published var selectedTheme: String {
        didSet {
            settings.appTheme = selectedTheme
        }
    }

    let options: [ThemeOption]
    private let settings: AppSettingsViewModel

    init(settings: AppSettingsViewModel) {
        self.settings = settings
        self.selectedTheme = settings.appTheme
        self.options = [
            ThemeOption(id: "system", titleKey: "System Default"),
            ThemeOption(id: "light", titleKey: "Light"),
            ThemeOption(id: "dark", titleKey: "Dark")
        ]
    }

    func select(_ option: ThemeOption) {
        selectedTheme = option.id
    }

    func syncFromSettings() {
        selectedTheme = settings.appTheme
    }
}

struct ThemeOption: Identifiable {
    let id: String
    let titleKey: String
}
