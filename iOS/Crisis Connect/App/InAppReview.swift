//
//  InAppReview.swift
//  Crisis Connect
//
//  Thin wrapper around StoreKit's in-app review — the iOS port of Android's `InAppReviewManager`
//  with the same gating: call `onPositiveEvent()` from genuinely positive, non-emergency moments
//  (e.g. the user just added a contact). Apple decides whether the card actually appears (it
//  enforces its own quota), so this is strictly best-effort: it never blocks, never interrupts,
//  and never surfaces an error. Deliberately never triggered from SOS/emergency flows.
//

import Foundation
import StoreKit
import UIKit

enum InAppReview {
    private static let keyPositiveEvents = "in_app_review.positive_events"
    private static let keyLastRequest = "in_app_review.last_request_ms"
    private static let keyFirstSeen = "in_app_review.first_seen_ms"

    private static let minPositiveEvents = 2
    private static let minAge: TimeInterval = 1 * 24 * 60 * 60
    private static let requestCooldown: TimeInterval = 90 * 24 * 60 * 60

    /// Records a positive milestone and, when the gating conditions are met, quietly asks StoreKit
    /// for the rating card. Safe to call from anywhere on the main actor.
    @MainActor
    static func onPositiveEvent() {
        let defaults = UserDefaults.standard
        let now = Date().timeIntervalSince1970

        var firstSeen = defaults.double(forKey: keyFirstSeen)
        if firstSeen == 0 {
            firstSeen = now
            defaults.set(now, forKey: keyFirstSeen)
        }
        let events = defaults.integer(forKey: keyPositiveEvents) + 1
        defaults.set(events, forKey: keyPositiveEvents)

        guard events >= minPositiveEvents,
              now - firstSeen >= minAge,
              now - defaults.double(forKey: keyLastRequest) >= requestCooldown else {
            return
        }
        defaults.set(now, forKey: keyLastRequest)

        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else { return }
        AppStore.requestReview(in: scene)
    }
}
