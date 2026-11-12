import Foundation

enum MusterLocalization {
    static let languages = ["en", "tr", "ar", "bn", "de", "es", "fa", "fil", "fr", "hi", "id", "ja", "ku", "pt", "ru", "uk", "ur", "vi", "zh"]

    static func language(preference: String, preferredLanguages: [String] = Locale.preferredLanguages) -> String {
        let requested = preference == "system" ? preferredLanguages : [preference]
        return Bundle.preferredLocalizations(from: languages, forPreferences: requested).first ?? "en"
    }

    static var locale: Locale {
        let preference = UserDefaults.standard.string(forKey: "appLanguage") ?? "system"
        return preference == "system" ? .autoupdatingCurrent : Locale(identifier: preference)
    }

    static var isRightToLeft: Bool {
        locale.language.characterDirection == .rightToLeft
    }

    static func text(_ key: String, bundle: Bundle = .main, preference: String? = nil) -> String {
        let selected = language(preference: preference ?? UserDefaults.standard.string(forKey: "appLanguage") ?? "system")
        let fallback = bundle.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:))?
            .localizedString(forKey: key, value: key, table: "Muster") ?? key
        return bundle.path(forResource: selected, ofType: "lproj").flatMap(Bundle.init(path:))?
            .localizedString(forKey: key, value: fallback, table: "Muster") ?? fallback
    }
}
