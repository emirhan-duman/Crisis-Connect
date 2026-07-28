//
//  SosActivityAttributes.swift
//  Crisis Connect
//
//  Live Activity contract for an active SOS broadcast. Compiled into both the
//  app target (which starts/ends the activity) and the widget extension
//  (which renders the lock-screen card and Dynamic Island).
//

import Foundation
import ActivityKit

struct SosActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// When the SOS was declared — drives the native chronometer, so the
        /// activity never needs push or periodic updates just to tick.
        var startedAt: Date
    }
}
