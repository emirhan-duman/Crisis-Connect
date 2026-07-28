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

    /// Persisted on/off toggle for the role-gated authority mesh (only effective on authority
    /// devices that hold the provisioned group key; the manager enforces that in observeSettings).
    @Published var authorityMeshEnabled: Bool {
        didSet {
            let resolved = authorityMeshEnabled && canUsePublicMesh
            if resolved != authorityMeshEnabled {
                authorityMeshEnabled = resolved
                return
            }
            userDefaults.set(resolved, forKey: Keys.authorityMeshEnabled)
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

    /// Publish my "last seen" presence to contacts (default ON, mirrors Android). Turning it off
    /// also deletes the presence doc so peers see nothing at all — not even a stale stamp. The
    /// choice is ACCOUNT-level: PresenceReporter mirrors it to `presenceSettings/{uid}` so
    /// Android and web follow it, and the Firestore rules enforce it on every presence write.
    @Published var shareLastSeenEnabled: Bool {
        didSet {
            guard shareLastSeenEnabled != oldValue else { return }
            userDefaults.set(shareLastSeenEnabled, forKey: PresenceReporter.shareLastSeenKey)
            guard !adoptingCloudShareLastSeen else { return }
            PresenceReporter.shared.onSharingPreferenceChanged(enabled: shareLastSeenEnabled)
        }
    }

    /// Cloud-wins startup sync (PresenceReporter): adopts the account-level value read from
    /// `presenceSettings/{uid}` without re-publishing it back — the reporter already handles
    /// the presence-doc side. Call on the main thread.
    func adoptCloudShareLastSeen(_ enabled: Bool) {
        guard shareLastSeenEnabled != enabled else { return }
        adoptingCloudShareLastSeen = true
        shareLastSeenEnabled = enabled
        adoptingCloudShareLastSeen = false
    }

    private var adoptingCloudShareLastSeen = false

    let canUsePublicMesh = PlatformRuntime.supportsBlePeripheralHosting

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults

        let storedPublicMeshEnabled = userDefaults.object(forKey: Keys.publicMeshEnabled) as? Bool ?? false
        self.publicMeshEnabled = storedPublicMeshEnabled && canUsePublicMesh

        let storedAuthorityMeshEnabled = userDefaults.object(forKey: Keys.authorityMeshEnabled) as? Bool ?? false
        self.authorityMeshEnabled = storedAuthorityMeshEnabled && canUsePublicMesh

        let storedGattMeshNotificationsEnabled = userDefaults.object(forKey: Keys.gattMeshNotificationsEnabled) as? Bool ?? true
        self.gattMeshNotificationsEnabled = storedGattMeshNotificationsEnabled && canUsePublicMesh

        self.batterySaverMode = userDefaults.object(forKey: Keys.batterySaverMode) as? Bool ?? true
        self.deliveryRetryEnabled = userDefaults.object(forKey: Keys.deliveryRetryEnabled) as? Bool ?? true
        self.diagnosticsUploadEnabled = userDefaults.object(forKey: Keys.diagnosticsUploadEnabled) as? Bool ?? true
        self.experimentalFeaturesEnabled = userDefaults.object(forKey: Keys.experimentalFeaturesEnabled) as? Bool ?? false
        self.shareLastSeenEnabled = userDefaults.object(forKey: PresenceReporter.shareLastSeenKey) as? Bool ?? true
    }

    private enum Keys {
        static let publicMeshEnabled = "advanced.publicMeshEnabled"
        static let authorityMeshEnabled = "advanced.authorityMeshEnabled"
        static let gattMeshNotificationsEnabled = "advanced.gattMeshNotificationsEnabled"
        static let batterySaverMode = "advanced.batterySaverMode"
        static let deliveryRetryEnabled = "advanced.deliveryRetryEnabled"
        static let diagnosticsUploadEnabled = "advanced.diagnosticsUploadEnabled"
        static let experimentalFeaturesEnabled = "advanced.experimentalFeaturesEnabled"
    }
}
