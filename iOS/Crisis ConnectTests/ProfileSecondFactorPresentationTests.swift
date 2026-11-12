import XCTest
import SwiftUI
import UIKit
@testable import Crisis_Connect

/// Isolated presentation checks; never signs in, sends SMS, or changes account state.
final class ProfileSecondFactorPresentationTests: XCTestCase {
    private let authenticator = ProfileSecondFactorOption(id: "authenticator", name: nil, isPhone: false)
    private let sms = ProfileSecondFactorOption(id: "sms", name: nil, isPhone: true)

    @MainActor func testVerificationRequiresSupportedSelectionAndASentSmsChallenge() {
        XCTAssertTrue(form(code: "123456").canVerify)
        XCTAssertFalse(form(code: "12345").canVerify)
        XCTAssertFalse(form(code: "123456", loading: true).canVerify)
        XCTAssertFalse(form(code: "123456", phone: true).canEnterCode)
        XCTAssertFalse(form(code: "123456", phone: true).canVerify)
        XCTAssertTrue(form(code: "123456", phone: true, sent: true).canVerify)
        XCTAssertFalse(form(code: "123456", factors: []).canEnterCode)
        XCTAssertFalse(form(code: "123456", factors: []).canVerify)
        XCTAssertFalse(form(code: "123456", selected: "unknown").canVerify)
    }

    @MainActor func testCodePasteUsesTheExistingSixAsciiDigitContract() {
        XCTAssertEqual(ProfileSecondFactorForm.normalizedCode("12 34-56\n"), "123456")
        XCTAssertEqual(ProfileSecondFactorForm.normalizedCode("1234567"), "123456")
        XCTAssertEqual(ProfileSecondFactorForm.normalizedCode("１２３٤٥٦"), "")
        XCTAssertFalse(form(code: "１２３４５６").canVerify)
        XCTAssertFalse(form(code: "12345a").canVerify)
    }

    @MainActor func testRenderVerificationStatesWithoutAuthenticationSideEffects() throws {
        var effects = 0
        let captureForm = form(onAction: { effects += 1 })
        try capture(captureForm, name: "02-iphone-light", width: 393, height: 680)
        try capture(form(code: "123456"), name: "03-iphone-dark", width: 393, height: 680, scheme: .dark)
        try capture(form(phone: true), name: "04-sms-before-tr", width: 393, height: 680, language: "tr")
        try capture(form(code: "123456", phone: true, sent: true), name: "05-sms-sent-tr-dark", width: 393, height: 680, scheme: .dark, language: "tr")
        try capture(form(), name: "06-ipad-light", width: 820, height: 1000)
        try capture(form(code: "123456", factors: [authenticator]), name: "07-ipad-dark", width: 820, height: 1000, scheme: .dark, language: "tr")
        try capture(form(), name: "08-compact-large-ar", width: 320, height: 570, language: "ar", typeSize: .accessibility3)
        try capture(form(factors: []), name: "09-unsupported", width: 393, height: 680)
        try capture(form(code: "123456", error: "The verification code is invalid. Please try again."), name: "10-error", width: 393, height: 680)
        try capture(form(phone: true, loading: true), name: "11-loading", width: 393, height: 680)
        try capture(form(code: "123456"), name: "12-short-viewport", width: 393, height: 430)
        XCTAssertEqual(effects, 0, "Layout must never trigger verification, SMS, method changes, or cancel")
    }

    @MainActor private func form(code: String = "", phone: Bool = false, sent: Bool = false,
                                 loading: Bool = false, error: String? = nil,
                                 factors: [ProfileSecondFactorOption]? = nil, selected: String? = nil,
                                 onAction: @escaping () -> Void = { XCTFail("Rendering must not perform an authentication action") }) -> ProfileSecondFactorForm {
        ProfileSecondFactorForm(
            factors: factors ?? [authenticator, sms], selectedFactorID: selected ?? (phone ? "sms" : "authenticator"),
            selectedIsPhone: phone, codeSent: sent, isLoading: loading, errorMessage: error, code: .constant(code),
            onSelectFactor: { _ in onAction() }, onSendSms: onAction,
            onVerify: { _ in onAction() }, onCancel: onAction
        )
    }

    @MainActor private func capture(_ form: ProfileSecondFactorForm, name: String, width: CGFloat, height: CGFloat,
                                    scheme: ColorScheme = .light, language: String = "en", typeSize: DynamicTypeSize = .large) throws {
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else {
            XCTFail("A render scene is required"); return
        }
        let previous = scene.windows.first(where: \.isKeyWindow)
        defer { previous?.makeKeyAndVisible() }
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: width, height: height)
        window.overrideUserInterfaceStyle = scheme == .dark ? .dark : .light
        let view = form.environment(\.colorScheme, scheme)
            .environment(\.locale, Locale(identifier: language))
            .environment(\.dynamicTypeSize, typeSize)
        let host = UIHostingController(rootView: view)
        window.rootViewController = host; window.makeKeyAndVisible()
        defer { window.isHidden = true }
        host.view.frame = window.bounds; host.view.setNeedsLayout(); host.view.layoutIfNeeded()
        RunLoop.main.run(until: Date().addingTimeInterval(0.3))
        let image = UIGraphicsImageRenderer(bounds: host.view.bounds).image { _ in
            host.view.drawHierarchy(in: host.view.bounds, afterScreenUpdates: true)
        }
        let attachment = XCTAttachment(image: image)
        attachment.name = "mfa-\(name)"; attachment.lifetime = .keepAlways; add(attachment)
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("cc-mfa-\(name).png")
        try XCTUnwrap(image.pngData()).write(to: url)
        print("MFA_CAPTURE: \(url.path)")
    }
}
