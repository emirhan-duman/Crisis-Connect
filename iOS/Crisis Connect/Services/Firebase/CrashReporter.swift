//
//  CrashReporter.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import Foundation
import FirebaseCrashlytics

enum CrashReporter {
    private static let policyObserver = NotificationCenter.default.addObserver(forName:.disasterTrafficChanged,object:nil,queue:.main) { _ in
        applyConsent(PrivacyPreferences.isShareDiagnosticsEnabled())
    }
    static func configure() {
        applyConsent(PrivacyPreferences.isShareDiagnosticsEnabled())
    }

    /// Automatic collection remains disabled so an in-session opt-out cannot upload a later crash.
    /// With consent, reports cached by a previous run are sent explicitly at the next launch.
    static func applyConsent(_ enabled: Bool) {
        _ = policyObserver
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.setCrashlyticsCollectionEnabled(false)
        if enabled && DisasterTrafficController.shared.permits(.telemetry) {
            crashlytics.sendUnsentReports()
        } else if !enabled {
            crashlytics.setUserID("")
            crashlytics.deleteUnsentReports()
        }
    }

    static func updateContext(
        role: String? = nil,
        certificateReady: Bool? = nil
    ) {
        guard PrivacyPreferences.isShareDiagnosticsEnabled(),
              DisasterTrafficController.shared.permits(.telemetry) else { return }
        let crashlytics = Crashlytics.crashlytics()

        if let role {
            crashlytics.setCustomValue(role, forKey: "rescue_role")
        }
        if let certificateReady {
            crashlytics.setCustomValue(certificateReady, forKey: "rescue_certificate_ready")
        }
    }

    /// Record only actionable satellite transport failures. Keep diagnostic context coarse and
    /// content-free; message text, coordinates, and recipient identity are never attached.
    static func recordSatelliteFailure(
        _ error: Error,
        component: String,
        category: String
    ) {
        guard PrivacyPreferences.isShareDiagnosticsEnabled(),
              DisasterTrafficController.shared.permits(.telemetry) else { return }
        let crashlytics = Crashlytics.crashlytics()
        crashlytics.record(
            error: error,
            userInfo: [
                "satellite_component": String(component.prefix(32)),
                "satellite_failure_category": String(category.prefix(32))
            ]
        )
    }
}
