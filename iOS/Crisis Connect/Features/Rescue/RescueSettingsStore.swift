//
//  RescueSettingsStore.swift
//  Crisis Connect
//
//  Created by Assistant on 12.03.2026.
//

import Foundation
import Combine

final class RescueSettingsStore: ObservableObject {
    static let shared = RescueSettingsStore()

    static let liveLocationIntervalOptionsSeconds = [30, 60, 90, 300]
    static let defaultLiveLocationIntervalSeconds = 60

    @Published var autoStartScanning: Bool {
        didSet {
            userDefaults.set(autoStartScanning, forKey: Keys.autoStartScanning)
        }
    }

    @Published var autoConnectToScannedBroadcasts: Bool {
        didSet {
            userDefaults.set(autoConnectToScannedBroadcasts, forKey: Keys.autoConnectToScannedBroadcasts)
        }
    }

    @Published var showOnlyActiveSignals: Bool {
        didSet {
            userDefaults.set(showOnlyActiveSignals, forKey: Keys.showOnlyActiveSignals)
        }
    }

    @Published var showDeviceIdentifier: Bool {
        didSet {
            userDefaults.set(showDeviceIdentifier, forKey: Keys.showDeviceIdentifier)
        }
    }

    @Published var meshAlwaysOn: Bool {
        didSet {
            userDefaults.set(meshAlwaysOn && canUseMeshAlwaysOn, forKey: Keys.meshAlwaysOn)
        }
    }

    @Published var crisisLinkEnabled: Bool {
        didSet {
            userDefaults.set(crisisLinkEnabled && canUseCrisisLink, forKey: Keys.crisisLinkEnabled)
        }
    }

    @Published var crisisLinkLiveLocationEnabled: Bool {
        didSet {
            userDefaults.set(
                crisisLinkLiveLocationEnabled && canUseCrisisLink,
                forKey: Keys.crisisLinkLiveLocationEnabled
            )
        }
    }

    @Published var crisisLinkLiveLocationIntervalSeconds: Int {
        didSet {
            let sanitized = Self.sanitizedLiveLocationIntervalSeconds(crisisLinkLiveLocationIntervalSeconds)
            if sanitized != crisisLinkLiveLocationIntervalSeconds {
                crisisLinkLiveLocationIntervalSeconds = sanitized
                return
            }
            userDefaults.set(sanitized, forKey: Keys.crisisLinkLiveLocationIntervalSeconds)
        }
    }

    let canUseMeshAlwaysOn = PlatformRuntime.supportsRescueRuntime
    let canUseCrisisLink = PlatformRuntime.supportsRescueRuntime

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        self.autoStartScanning = userDefaults.object(forKey: Keys.autoStartScanning) as? Bool ?? false
        self.autoConnectToScannedBroadcasts = userDefaults.object(forKey: Keys.autoConnectToScannedBroadcasts) as? Bool ?? true
        self.showOnlyActiveSignals = userDefaults.object(forKey: Keys.showOnlyActiveSignals) as? Bool ?? false
        self.showDeviceIdentifier = userDefaults.object(forKey: Keys.showDeviceIdentifier) as? Bool ?? true

        let storedMeshAlwaysOn = userDefaults.object(forKey: Keys.meshAlwaysOn) as? Bool ?? false
        self.meshAlwaysOn = storedMeshAlwaysOn && canUseMeshAlwaysOn

        let storedCrisisLinkEnabled = userDefaults.object(forKey: Keys.crisisLinkEnabled) as? Bool ?? false
        self.crisisLinkEnabled = storedCrisisLinkEnabled && canUseCrisisLink

        let storedLiveLocationEnabled = userDefaults.object(forKey: Keys.crisisLinkLiveLocationEnabled) as? Bool ?? false
        self.crisisLinkLiveLocationEnabled = storedLiveLocationEnabled && canUseCrisisLink

        let storedInterval = userDefaults.integer(forKey: Keys.crisisLinkLiveLocationIntervalSeconds)
        if storedInterval == 0 {
            self.crisisLinkLiveLocationIntervalSeconds = Self.defaultLiveLocationIntervalSeconds
        } else {
            self.crisisLinkLiveLocationIntervalSeconds = Self.sanitizedLiveLocationIntervalSeconds(storedInterval)
        }
    }

    private static func sanitizedLiveLocationIntervalSeconds(_ value: Int) -> Int {
        liveLocationIntervalOptionsSeconds.first(where: { $0 == value }) ?? defaultLiveLocationIntervalSeconds
    }

    private enum Keys {
        static let autoStartScanning = "rescue.autoStartScanning"
        static let autoConnectToScannedBroadcasts = "rescue.autoConnectToScannedBroadcasts"
        static let showOnlyActiveSignals = "rescue.showOnlyActiveSignals"
        static let showDeviceIdentifier = "rescue.showDeviceIdentifier"
        static let meshAlwaysOn = "rescue.meshAlwaysOn"
        static let crisisLinkEnabled = "rescue.crisisLinkEnabled"
        static let crisisLinkLiveLocationEnabled = "rescue.crisisLinkLiveLocationEnabled"
        static let crisisLinkLiveLocationIntervalSeconds = "rescue.crisisLinkLiveLocationIntervalSeconds"
    }
}
