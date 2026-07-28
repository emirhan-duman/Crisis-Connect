import FirebaseAnalytics

/// Mirror of the Android analytics layer (`analytics/Analytics.kt`) — event names and parameters
/// MUST stay byte-identical across platforms so both apps aggregate in the one Firebase property.
///
/// Privacy contract (the app is open source; keep it auditable): events never carry identifiers —
/// no uid, session code, name, phone number or location. Only coarse categories (transport,
/// method, result) and durations in seconds.
enum AppAnalytics {

    /// SOS mode switched on (BLE broadcast started).
    static func sosActivated() { log("sos_activated") }

    /// SOS self-report accepted by the `reportSosSignal` callable.
    static func sosCloudReported(status: String) {
        log("sos_cloud_reported", ["status": String(status.prefix(36))])
    }

    /// A user-authored chat message left the composer.
    static func messageSent(kind: String, transport: String, chat: String = "direct") {
        log("message_sent", [
            "kind": kind,
            "transport": String(transport.prefix(36)),
            "chat": chat
        ])
    }

    /// Outgoing voice call placed.
    static func callStarted(transport: String) {
        log("call_started", ["transport": transport])
    }

    /// Call media actually connected (fires on both legs, once per call).
    static func callConnected(transport: String) {
        log("call_connected", ["transport": transport])
    }

    /// Call finished. `result` answered / missed / rejected / canceled; duration 0 unless answered.
    static func callEnded(transport: String, durationSeconds: Int, result: String) {
        log("call_ended", [
            "transport": transport,
            "duration_seconds": durationSeconds,
            "result": String(result.prefix(36))
        ])
    }

    /// Crisis Sentinel prompt submitted.
    static func sentinelPrompt(engine: String) {
        log("sentinel_prompt", ["engine": String(engine.prefix(36))])
    }

    /// A brand-new contact was created by a deliberate user action.
    static func contactAdded(method: String, transport: String) {
        log("contact_added", [
            "method": String(method.prefix(36)),
            "transport": String(transport.prefix(36))
        ])
    }

    /// A contact was auto-created because a PEER added this user (passive side of an add).
    static func contactReceived(via: String, transport: String) {
        log("contact_received", [
            "via": String(via.prefix(36)),
            "transport": String(transport.prefix(36))
        ])
    }

    /// Push-to-talk transmit started (the local floor was claimed on the telsiz/PTT link).
    static func pttTransmit() { log("ptt_transmit") }

    /// Navigation moved to a screen. `route` is a route/screen pattern only — never argument
    /// values, ids or other user data (privacy contract). Uses Firebase's standard screen_view.
    static func screenView(_ route: String) {
        log(AnalyticsEventScreenView, [AnalyticsParameterScreenName: String(route.prefix(100))])
    }

    /// Applies the user's stored consent to the SDK. Called at launch (after Firebase configures)
    /// and from the privacy toggle — without both, the switch is theater over unconditional logging.
    static func applyStoredConsent() {
        setCollectionEnabled(PrivacyPreferences.isShareAnalyticsEnabled())
    }

    static func setCollectionEnabled(_ enabled: Bool) {
        Analytics.setAnalyticsCollectionEnabled(enabled)
    }

    private static func log(_ name: String, _ parameters: [String: Any]? = nil) {
        Analytics.logEvent(name, parameters: parameters)
    }
}

import SwiftUI

/// Per-screen screen_view logging for PUSHED screens. The tab switcher logs the four roots, but a
/// session spent in chats/settings/rescue looked like the user never left the messages tab —
/// Android's navigation observer logs every destination.
private struct AnalyticsScreenModifier: ViewModifier {
    let route: String

    func body(content: Content) -> some View {
        content.onAppear { AppAnalytics.screenView(route) }
    }
}

extension View {
    func analyticsScreen(_ route: String) -> some View {
        modifier(AnalyticsScreenModifier(route: route))
    }
}
