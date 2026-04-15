//
//  PrivacySettingsViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI
import Combine
import FirebaseAuth
import FirebaseFirestore
#if canImport(UIKit)
import UIKit
#endif

final class PrivacySettingsViewModel: ObservableObject {
    let canShareLiveLocation: Bool
    let canShareAnalytics: Bool

    @Published var shareLocationInSOS: Bool {
        didSet {
            PrivacyPreferences.setShareLocationInSOS(
                shareLocationInSOS && canShareLiveLocation,
                userDefaults: userDefaults
            )
        }
    }

    @Published var shareProfileDetails: Bool {
        didSet {
            PrivacyPreferences.setShareProfileDetails(shareProfileDetails, userDefaults: userDefaults)
            PrivacyRemoteSync.scheduleProfileDetailsRefresh(userDefaults: userDefaults)
        }
    }

    @Published var shareAnalytics: Bool {
        didSet {
            PrivacyPreferences.setShareAnalytics(
                shareAnalytics && canShareAnalytics,
                userDefaults: userDefaults
            )
        }
    }

    private let userDefaults: UserDefaults

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        self.canShareLiveLocation = RescueSettingsStore.shared.canUseCrisisLink
        self.canShareAnalytics = false
        self.shareLocationInSOS = canShareLiveLocation
            && PrivacyPreferences.isShareLocationInSOSEnabled(userDefaults: userDefaults)
        self.shareProfileDetails = PrivacyPreferences.isShareProfileDetailsEnabled(userDefaults: userDefaults)
        self.shareAnalytics = canShareAnalytics
            && PrivacyPreferences.isShareAnalyticsEnabled(userDefaults: userDefaults)
    }

    func openSystemSettings() {
#if canImport(UIKit)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
#endif
    }
}

enum PrivacyPreferences {
    static func isShareLocationInSOSEnabled(userDefaults: UserDefaults = .standard) -> Bool {
        userDefaults.object(forKey: Keys.shareLocationInSOS) as? Bool ?? true
    }

    static func setShareLocationInSOS(_ enabled: Bool, userDefaults: UserDefaults = .standard) {
        userDefaults.set(enabled, forKey: Keys.shareLocationInSOS)
        NotificationCenter.default.post(name: .privacyPreferencesDidChange, object: nil)
    }

    static func isShareProfileDetailsEnabled(userDefaults: UserDefaults = .standard) -> Bool {
        userDefaults.object(forKey: Keys.shareProfileDetails) as? Bool ?? true
    }

    static func setShareProfileDetails(_ enabled: Bool, userDefaults: UserDefaults = .standard) {
        userDefaults.set(enabled, forKey: Keys.shareProfileDetails)
        NotificationCenter.default.post(name: .privacyPreferencesDidChange, object: nil)
    }

    static func isShareAnalyticsEnabled(userDefaults: UserDefaults = .standard) -> Bool {
        userDefaults.object(forKey: Keys.shareAnalytics) as? Bool ?? false
    }

    static func setShareAnalytics(_ enabled: Bool, userDefaults: UserDefaults = .standard) {
        userDefaults.set(enabled, forKey: Keys.shareAnalytics)
        NotificationCenter.default.post(name: .privacyPreferencesDidChange, object: nil)
    }

    fileprivate enum Keys {
        static let shareLocationInSOS = "privacy.shareLocationInSOS"
        static let shareProfileDetails = "privacy.shareProfileDetails"
        static let shareAnalytics = "privacy.shareAnalytics"
    }
}

enum PrivacyRemoteSync {
    static func scheduleProfileDetailsRefresh(userDefaults: UserDefaults = .standard) {
        Task {
            await syncProfileDetails(userDefaults: userDefaults)
        }
    }

    static func syncProfileDetails(
        userDefaults: UserDefaults = .standard,
        auth: Auth = Auth.auth(),
        firestore: Firestore = Firestore.firestore()
    ) async {
        guard let user = auth.currentUser, !user.isAnonymous else { return }
        let resolvedUID = user.uid.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !resolvedUID.isEmpty else { return }
        let document = firestore.collection("users").document(resolvedUID)
        let existingSnapshot = try? await document.getDocumentAsync()
        let preservedAgency = (existingSnapshot?.get("agency") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let preservedPanelId = AgencyRouter.resolvePanelId(
            candidates: [
                existingSnapshot?.get("agencySlug") as? String,
                existingSnapshot?.get("agencyKey") as? String,
                existingSnapshot?.get("panelId") as? String,
                existingSnapshot?.get("agencyPanelId") as? String,
                existingSnapshot?.get("panelSlug") as? String,
                existingSnapshot?.get("panelKey") as? String,
            ],
            fallbackAgency: preservedAgency
        )
        let shouldPreserveAgencyContext = (existingSnapshot?.get("verified") as? Bool == true)
            || RescueRoleAccess.isAuthorized(existingSnapshot?.get("role") as? String)

        var payload: [String: Any] = [
            "updatedAt": FieldValue.serverTimestamp()
        ]

        if PrivacyPreferences.isShareProfileDetailsEnabled(userDefaults: userDefaults) {
            let fullName = ProfileMetadataStore.loadFullName(userDefaults: userDefaults)
            let email = user.email?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let country = ProfileMetadataStore.loadCountry(userDefaults: userDefaults)
            let explicitAgency = ProfileMetadataStore.loadAgency(userDefaults: userDefaults)
            let resolvedAgency = ProfileMetadataStore.resolvedAgency(
                email: email,
                country: country,
                explicitAgency: explicitAgency
            )

            payload["username"] = fullName.isEmpty ? FieldValue.delete() : fullName
            payload["email"] = email.isEmpty ? FieldValue.delete() : email
            payload["country"] = country.isEmpty ? FieldValue.delete() : country

            let effectiveAgency = !resolvedAgency.isEmpty ? resolvedAgency : preservedAgency
            if effectiveAgency.isEmpty {
                payload["agency"] = FieldValue.delete()
                payload["agencySlug"] = FieldValue.delete()
                payload["agencyKey"] = FieldValue.delete()
            } else {
                payload["agency"] = effectiveAgency
                let slug = AgencyRouter.resolvePanelId(
                    candidates: [
                        existingSnapshot?.get("agencySlug") as? String,
                        existingSnapshot?.get("agencyKey") as? String,
                        existingSnapshot?.get("panelId") as? String,
                        existingSnapshot?.get("agencyPanelId") as? String,
                        existingSnapshot?.get("panelSlug") as? String,
                        existingSnapshot?.get("panelKey") as? String,
                    ],
                    fallbackAgency: effectiveAgency
                )
                payload["agencySlug"] = slug.isEmpty ? FieldValue.delete() : slug
                payload["agencyKey"] = slug.isEmpty ? FieldValue.delete() : slug
            }
        } else {
            payload["username"] = FieldValue.delete()
            payload["email"] = FieldValue.delete()
            payload["country"] = FieldValue.delete()
            if shouldPreserveAgencyContext {
                payload["agency"] = preservedAgency.isEmpty ? FieldValue.delete() : preservedAgency
                payload["agencySlug"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
                payload["agencyKey"] = preservedPanelId.isEmpty ? FieldValue.delete() : preservedPanelId
            } else {
                payload["agency"] = FieldValue.delete()
                payload["agencySlug"] = FieldValue.delete()
                payload["agencyKey"] = FieldValue.delete()
            }
        }

        _ = try? await document.setDataAsync(payload, merge: true)
    }
}

extension Notification.Name {
    static let privacyPreferencesDidChange = Notification.Name("privacyPreferencesDidChange")
}
