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
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(true)
    }

    static func updateCurrentUser(
        uid: String?,
        role: String? = nil,
        certificateReady: Bool? = nil
    ) {
        let crashlytics = Crashlytics.crashlytics()
        let normalizedUid = uid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        crashlytics.setUserID(normalizedUid)

        if let role {
            crashlytics.setCustomValue(role, forKey: "rescue_role")
        }
        if let certificateReady {
            crashlytics.setCustomValue(certificateReady, forKey: "rescue_certificate_ready")
        }
    }
}
