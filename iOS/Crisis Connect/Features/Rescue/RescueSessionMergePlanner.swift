//
//  RescueSessionMergePlanner.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import Foundation

enum RescueSessionMergePlanner {
    static func preferredSessionId(
        lhs: UUID,
        lhsStatus: RescueConnectionStatus,
        rhs: UUID,
        rhsStatus: RescueConnectionStatus
    ) -> UUID {
        let lhsScore = priority(for: lhsStatus)
        let rhsScore = priority(for: rhsStatus)
        if rhsScore > lhsScore {
            return rhs
        }
        return lhs
    }

    static func priority(for status: RescueConnectionStatus) -> Int {
        switch status {
        case .ready:
            return 6
        case .authenticating:
            return 5
        case .discovering:
            return 4
        case .connected:
            return 3
        case .connecting:
            return 2
        case .disconnected:
            return 1
        case .failed:
            return 0
        }
    }

    static func mergedBroadcast(
        targetSessionId: UUID,
        targetEntry: RescueBroadcast?,
        targetStatus: RescueConnectionStatus,
        sourceEntry: RescueBroadcast?,
        sourceStatus: RescueConnectionStatus,
        broadcastId: String
    ) -> RescueBroadcast {
        var merged = targetEntry ?? sourceEntry ?? RescueBroadcast(
            id: targetSessionId,
            broadcastId: broadcastId,
            peripheralId: sourceEntry?.peripheralId ?? targetSessionId,
            peripheralName: sourceEntry?.peripheralName ?? NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: ""),
            status: sourceEntry?.status ?? .connecting,
            rssi: sourceEntry?.rssi,
            lastSeen: Date(),
            lastUpdated: Date()
        )
        merged.broadcastId = broadcastId

        if let sourceEntry {
            let originalLastSeen = merged.lastSeen
            merged.lastSeen = max(merged.lastSeen, sourceEntry.lastSeen)
            merged.lastUpdated = max(merged.lastUpdated, sourceEntry.lastUpdated)
            if sourceEntry.rssi != nil && (merged.rssi == nil || sourceEntry.lastSeen >= originalLastSeen) {
                merged.rssi = sourceEntry.rssi
            }
            if sourceEntry.victimBatteryPercent != nil &&
                (merged.victimBatteryPercent == nil || sourceEntry.lastSeen >= originalLastSeen) {
                merged.victimBatteryPercent = sourceEntry.victimBatteryPercent
            }
            if merged.peripheralName == NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: ""),
               sourceEntry.peripheralName != NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "") {
                merged.peripheralName = sourceEntry.peripheralName
            }
            if priority(for: sourceStatus) > priority(for: targetStatus) {
                merged.status = sourceEntry.status
                merged.peripheralId = sourceEntry.peripheralId
            }
        }

        return merged
    }
}
