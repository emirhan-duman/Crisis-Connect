//
//  CrashReporter.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import Foundation
import FirebaseCrashlytics

enum CrashReporter {
    static func configure() {
        applyConsent(PrivacyPreferences.isShareDiagnosticsEnabled())
    }

    /// Automatic collection remains disabled so an in-session opt-out cannot upload a later crash.
    /// With consent, reports cached by a previous run are sent explicitly at the next launch.
    static func applyConsent(_ enabled: Bool) {
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(false)
        if enabled {
            crashlytics.sendUnsentReports()
        } else {
            crashlytics.setUserID("")
            crashlytics.deleteUnsentReports()
        }
    }

    static func updateContext(
        role: String? = nil,
        certificateReady: Bool? = nil
    ) {
        guard PrivacyPreferences.isShareDiagnosticsEnabled() else { return }
        let crashlytics = Crashlytics.crashlytics()

        if let role {
            crashlytics.setCustomValue(role, forKey: "rescue_role")
        }
        if let certificateReady {
            crashlytics.setCustomValue(certificateReady, forKey: "rescue_certificate_ready")
        }
    }
}
