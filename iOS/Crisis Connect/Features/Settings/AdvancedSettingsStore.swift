//
//  AdvancedSettingsStore.swift
//  Crisis Connect
//
//  Created by Codex on 25.03.2026.
//

import Foundation
import Combine

final class AdvancedSettingsStore: ObservableObject {
    static let shared = AdvancedSettingsStore()

    @Published var publicMeshEnabled: Bool {
        didSet {
            let resolved = publicMeshEnabled && canUsePublicMesh
            if resolved != publicMeshEnabled {
                publicMeshEnabled = resolved
                return
            }
            userDefaults.set(resolved, forKey: Keys.publicMeshEnabled)
        }
    }

    @Published var gattMeshNotificationsEnabled: Bool {
        didSet {
            let resolved = gattMeshNotificationsEnabled && canUsePublicMesh
            if resolved != gattMeshNotificationsEnabled {
                gattMeshNotificationsEnabled = resolved
                return
            }
            userDefaults.set(resolved, forKey: Keys.gattMeshNotificationsEnabled)
        }
    }

    @Published var batterySaverMode: Bool {
        didSet {
            userDefaults.set(batterySaverMode, forKey: Keys.batterySaverMode)
        }
    }

    @Published var deliveryRetryEnabled: Bool {
        didSet {
            userDefaults.set(deliveryRetryEnabled, forKey: Keys.deliveryRetryEnabled)
        }
    }

    @Published var diagnosticsUploadEnabled: Bool {
        didSet {
            userDefaults.set(diagnosticsUploadEnabled, forKey: Keys.diagnosticsUploadEnabled)
        }
    }

    @Published var experimentalFeaturesEnabled: Bool {
        didSet {
            userDefaults.set(experimentalFeaturesEnabled, forKey: Keys.experimentalFeaturesEnabled)
        }
    }

    let canUsePublicMesh = PlatformRuntime.supportsBlePeripheralHosting

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults

        let storedPublicMeshEnabled = userDefaults.object(forKey: Keys.publicMeshEnabled) as? Bool ?? false
        self.publicMeshEnabled = storedPublicMeshEnabled && canUsePublicMesh

        let storedGattMeshNotificationsEnabled = userDefaults.object(forKey: Keys.gattMeshNotificationsEnabled) as? Bool ?? true
        self.gattMeshNotificationsEnabled = storedGattMeshNotificationsEnabled && canUsePublicMesh

        self.batterySaverMode = userDefaults.object(forKey: Keys.batterySaverMode) as? Bool ?? true
        self.deliveryRetryEnabled = userDefaults.object(forKey: Keys.deliveryRetryEnabled) as? Bool ?? true
        self.diagnosticsUploadEnabled = userDefaults.object(forKey: Keys.diagnosticsUploadEnabled) as? Bool ?? true
        self.experimentalFeaturesEnabled = userDefaults.object(forKey: Keys.experimentalFeaturesEnabled) as? Bool ?? false
    }

    private enum Keys {
        static let publicMeshEnabled = "advanced.publicMeshEnabled"
        static let gattMeshNotificationsEnabled = "advanced.gattMeshNotificationsEnabled"
        static let batterySaverMode = "advanced.batterySaverMode"
        static let deliveryRetryEnabled = "advanced.deliveryRetryEnabled"
        static let diagnosticsUploadEnabled = "advanced.diagnosticsUploadEnabled"
        static let experimentalFeaturesEnabled = "advanced.experimentalFeaturesEnabled"
    }
}
