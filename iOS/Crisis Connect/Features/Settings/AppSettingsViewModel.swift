//
//  AppSettingsViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
#if canImport(UIKit)
import UIKit
#endif

final class AppSettingsViewModel: ObservableObject {
    @Published var appLanguage: String {
        didSet {
            userDefaults.set(appLanguage, forKey: Keys.appLanguage)
        }
    }

    @Published var appTheme: String {
        didSet {
            userDefaults.set(appTheme, forKey: Keys.appTheme)
        }
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        self.appLanguage = userDefaults.string(forKey: Keys.appLanguage) ?? "system"
        if let storedTheme = userDefaults.string(forKey: Keys.appTheme) {
            self.appTheme = storedTheme
        } else {
            let defaultTheme = Self.deviceDefaultTheme()
            self.appTheme = defaultTheme
            userDefaults.set(defaultTheme, forKey: Keys.appTheme)
        }
    }

    var resolvedLocale: Locale {
        appLanguage == "system" ? .autoupdatingCurrent : Locale(identifier: appLanguage)
    }

    var languageNameKey: String {
        switch appLanguage {
        case "en": return "English"
        case "tr": return "Türkçe"
        case "ja": return "日本語"
        case "ar": return "العربية"
        case "bn": return "বাংলা"
        case "de": return "Deutsch"
        case "es": return "Español"
        case "fa": return "فارسی"
        case "fil": return "Filipino"
        case "fr": return "Français"
        case "hi": return "हिन्दी"
        case "id": return "Bahasa Indonesia"
        case "ku": return "Kurdî"
        case "pt": return "Português"
        case "ru": return "Русский"
        case "uk": return "Українська"
        case "ur": return "اردو"
        case "vi": return "Tiếng Việt"
        case "zh": return "中文"
        default: return "System Default"
        }
    }

    var resolvedColorScheme: ColorScheme? {
        switch appTheme {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

    var themeNameKey: String {
        switch appTheme {
        case "light": return "Light"
        case "dark": return "Dark"
        default: return "System Default"
        }
    }

    private enum Keys {
        static let appLanguage = "appLanguage"
        static let appTheme = "appTheme"
    }

    private static func deviceDefaultTheme() -> String {
#if canImport(UIKit)
        switch UITraitCollection.current.userInterfaceStyle {
        case .dark:
            return "dark"
        case .light:
            return "light"
        default:
            return "system"
        }
#else
        return "system"
#endif
    }
}
