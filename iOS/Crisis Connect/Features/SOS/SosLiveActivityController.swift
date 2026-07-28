//
//  SosLiveActivityController.swift
//  Crisis Connect
//
//  Starts/ends the lock-screen + Dynamic Island Live Activity for a DECLARED
//  SOS. Strictly best-effort: the user can disable Live Activities, and nothing
//  here may ever block or fail the actual broadcast.
//

import ActivityKit
import Foundation

enum SosLiveActivityController {

    static func sosDeclared(startedAt: Date) {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        // Sweep any stale activity from a crashed/killed run before starting anew.
        endAll()
        let content = ActivityContent(
            state: SosActivityAttributes.ContentState(startedAt: startedAt),
            staleDate: nil
        )
        _ = try? Activity.request(attributes: SosActivityAttributes(), content: content)
    }

    static func sosEnded() {
        endAll()
    }

    private static func endAll() {
        for activity in Activity<SosActivityAttributes>.activities {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
    }
}
