//
//  RescueLiveLocationPolicy.swift
//  Crisis Connect
//
//  Created by Codex on 20.03.2026.
//

import CoreLocation
import Foundation

struct RescueLiveLocationRuntimeProfile: Equatable {
    let desiredAccuracy: CLLocationAccuracy
    let distanceFilter: CLLocationDistance
    let pausesLocationUpdatesAutomatically: Bool
    let shouldUseTimer: Bool
    let shouldUseSignificantChangeMonitoring: Bool
    let shouldWarmLocation: Bool
    let shouldRequestOneShotLocation: Bool
}

enum RescueLiveLocationPolicy {
    static func runtimeProfile(
        liveLocationEnabled: Bool,
        applicationIsActive: Bool,
        authorizationStatus: CLAuthorizationStatus,
        backgroundRefreshAvailable: Bool
    ) -> RescueLiveLocationRuntimeProfile {
        guard liveLocationEnabled else {
            return RescueLiveLocationRuntimeProfile(
                desiredAccuracy: kCLLocationAccuracyHundredMeters,
                distanceFilter: 100,
                pausesLocationUpdatesAutomatically: true,
                shouldUseTimer: false,
                shouldUseSignificantChangeMonitoring: false,
                shouldWarmLocation: false,
                shouldRequestOneShotLocation: false
            )
        }

        let hasAlwaysAuthorization = authorizationStatus == .authorizedAlways
        if applicationIsActive {
            return RescueLiveLocationRuntimeProfile(
                desiredAccuracy: kCLLocationAccuracyNearestTenMeters,
                distanceFilter: kCLDistanceFilterNone,
                pausesLocationUpdatesAutomatically: false,
                shouldUseTimer: true,
                shouldUseSignificantChangeMonitoring: false,
                shouldWarmLocation: true,
                shouldRequestOneShotLocation: true
            )
        }

        let shouldUseSignificantChangeMonitoring = hasAlwaysAuthorization && backgroundRefreshAvailable
        return RescueLiveLocationRuntimeProfile(
            desiredAccuracy: kCLLocationAccuracyHundredMeters,
            distanceFilter: 100,
            pausesLocationUpdatesAutomatically: true,
            shouldUseTimer: false,
            shouldUseSignificantChangeMonitoring: shouldUseSignificantChangeMonitoring,
            shouldWarmLocation: false,
            shouldRequestOneShotLocation: hasAlwaysAuthorization && !shouldUseSignificantChangeMonitoring
        )
    }
}
