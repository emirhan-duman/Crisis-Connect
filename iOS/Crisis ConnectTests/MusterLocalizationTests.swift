import Foundation
import XCTest
import SwiftUI
import UIKit
@testable import Crisis_Connect

final class MusterLocalizationTests: XCTestCase {
    @MainActor func testLocalizedResponseLayoutInArabicAndFrench() throws {
        let previousLanguage = UserDefaults.standard.object(forKey: "appLanguage")
        defer { if let previousLanguage { UserDefaults.standard.set(previousLanguage, forKey: "appLanguage") } else { UserDefaults.standard.removeObject(forKey: "appLanguage") } }
        let scene = try XCTUnwrap(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
        let previousWindow = scene.windows.first(where: \.isKeyWindow)
        defer { previousWindow?.makeKeyAndVisible() }
        for language in ["ar", "fr"] {
            UserDefaults.standard.set(language, forKey: "appLanguage")
            let row = CachedMusterAssignment(panelId: "fixture", assignment: MusterAssignment(session: MusterSession(id: "fixture", kind: "live",
                title: musterText("title"), instructions: musterText("ui_inbox_detail"), status: "active")))
            let model = MusterViewModel(); model.uid = "fixture"; model.assignments = [row]
            let dark = language == "fr"
            let content = ScrollView { MusterAssignmentRow(row: row, model: model).padding(20) }
                .environment(\.locale, Locale(identifier: language))
                .environment(\.layoutDirection, language == "ar" ? .rightToLeft : .leftToRight)
                .environment(\.colorScheme, dark ? .dark : .light)
            let window = UIWindow(windowScene: scene); window.frame = CGRect(x: 0, y: 0, width: 393, height: 1100)
            window.overrideUserInterfaceStyle = dark ? .dark : .light
            let host = UIHostingController(rootView: content); window.rootViewController = host; window.makeKeyAndVisible()
            host.view.frame = window.bounds; host.view.layoutIfNeeded()
            RunLoop.main.run(until: Date().addingTimeInterval(0.25))
            let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true) }
            let attachment = XCTAttachment(image: image); attachment.name = "muster-\(language)-\(dark ? "dark" : "light")"; attachment.lifetime = .keepAlways; add(attachment)
            window.isHidden = true
        }
    }

    func testRegionalAndSystemLanguageSelection() {
        XCTAssertEqual(MusterLocalization.language(preference: "fr-CA"), "fr")
        XCTAssertEqual(MusterLocalization.language(preference: "ar-SA"), "ar")
        XCTAssertEqual(MusterLocalization.language(preference: "zh-Hans-CN"), "zh")
        XCTAssertEqual(MusterLocalization.language(preference: "system", preferredLanguages: ["uk-UA", "en"]), "uk")
        XCTAssertEqual(MusterLocalization.language(preference: "unknown-language"), "en")
    }

    func testEverySupportedLanguagePackagesTheCompleteTableAndPreservesFormats() throws {
        let bundle = Bundle.main
        func table(_ language: String) throws -> [String: String] {
            let path = try XCTUnwrap(bundle.path(forResource: "Muster", ofType: "strings", inDirectory: nil, forLocalization: language), language)
            let data = try Data(contentsOf: URL(fileURLWithPath: path))
            return try XCTUnwrap(PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String], language)
        }
        let english = try table("en")
        let expression = try NSRegularExpression(pattern: #"%(?:\d+\$)?[d@]"#)
        func formats(_ text: String) -> [String] {
            expression.matches(in: text, range: NSRange(text.startIndex..., in: text)).map { String(text[Range($0.range, in: text)!]) }.sorted()
        }
        for language in MusterLocalization.languages {
            let localized = try table(language)
            XCTAssertEqual(Set(localized.keys), Set(english.keys), language)
            for (key, source) in english {
                let value = try XCTUnwrap(localized[key], "\(language).\(key)")
                XCTAssertFalse(value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, "\(language).\(key)")
                if language != "en" { XCTAssertNil(value.range(of: #"\bclosed\b|CCX\d+XCC"#, options: .regularExpression), "\(language).\(key)") }
                XCTAssertEqual(formats(source), formats(value), "\(language).\(key)")
                XCTAssertEqual(MusterLocalization.text(key, bundle: bundle, preference: language), value)
            }
            if language != "en" {
                XCTAssertNotEqual(localized["title"], english["title"], language)
                XCTAssertNotEqual(localized["help"], english["help"], language)
                XCTAssertNotEqual(localized["sent"], english["sent"], language)
            }
        }
    }
}
