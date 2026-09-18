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
        setCollectionEnabled(PrivacyPreferences.isShareDiagnosticsEnabled())
    }

    static func setCollectionEnabled(_ enabled: Bool) {
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        if !enabled {
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
