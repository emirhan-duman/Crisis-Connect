//
//  NotificationsSettingsViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI
import Combine
import UserNotifications
#if canImport(UIKit)
import UIKit
#endif

final class NotificationsSettingsViewModel: ObservableObject {
    @Published private(set) var authorizationStatus: UNAuthorizationStatus = .notDetermined
    @Published private(set) var statusKey: String = "NOTIFICATIONS_STATUS_NOT_DETERMINED"
    @Published private(set) var permissionsFooterKey: String = "NOTIFICATIONS_PERMISSIONS_FOOTER_REQUEST"
    @Published private(set) var isSystemAuthorized: Bool = false

    @Published var appNotificationsEnabled: Bool {
        didSet {
            hasStoredAppNotificationsPreference = true
            userDefaults.set(appNotificationsEnabled, forKey: Keys.appNotificationsEnabled)
        }
    }

    @Published var sosAlertsEnabled: Bool {
        didSet { userDefaults.set(sosAlertsEnabled, forKey: Keys.sosAlertsEnabled) }
    }

    @Published var contactUpdatesEnabled: Bool {
        didSet { userDefaults.set(contactUpdatesEnabled, forKey: Keys.contactUpdatesEnabled) }
    }

    @Published var chatMessagesEnabled: Bool {
        didSet { userDefaults.set(chatMessagesEnabled, forKey: Keys.chatMessagesEnabled) }
    }

    @Published var playSoundEnabled: Bool {
        didSet { userDefaults.set(playSoundEnabled, forKey: Keys.playSoundEnabled) }
    }

    private let userDefaults: UserDefaults
    private var hasStoredAppNotificationsPreference: Bool

    init(userDefaults: UserDefaults = .standard) {
        self.userDefaults = userDefaults
        let storedAppPreference = userDefaults.object(forKey: Keys.appNotificationsEnabled)
        let storedAppEnabled = storedAppPreference as? Bool
        let storedSos = userDefaults.object(forKey: Keys.sosAlertsEnabled) as? Bool
        let storedContacts = userDefaults.object(forKey: Keys.contactUpdatesEnabled) as? Bool
        let storedSound = userDefaults.object(forKey: Keys.playSoundEnabled) as? Bool

        self.hasStoredAppNotificationsPreference = storedAppPreference != nil
        self.appNotificationsEnabled = storedAppEnabled ?? true
        self.sosAlertsEnabled = storedSos ?? true
        self.contactUpdatesEnabled = storedContacts ?? true
        self.chatMessagesEnabled = (userDefaults.object(forKey: Keys.chatMessagesEnabled) as? Bool) ?? true
        self.playSoundEnabled = storedSound ?? true
        refreshAuthorization()
    }

    var canConfigureNotifications: Bool {
        appNotificationsEnabled && isSystemAuthorized
    }

    func refreshAuthorization() {
        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                self?.applyAuthorizationStatus(settings.authorizationStatus)
            }
        }
    }

    func handleAppNotificationsToggle(_ enabled: Bool) {
        if enabled {
            switch authorizationStatus {
            case .notDetermined:
                requestAuthorization()
            case .denied:
                appNotificationsEnabled = false
            default:
                appNotificationsEnabled = true
            }
        } else {
            appNotificationsEnabled = false
        }
    }

    func openSystemSettings() {
#if canImport(UIKit)
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
#endif
    }

    private func requestAuthorization() {
        let options: UNAuthorizationOptions = [.alert, .badge, .sound]
        UNUserNotificationCenter.current().requestAuthorization(options: options) { [weak self] granted, _ in
            DispatchQueue.main.async {
                self?.appNotificationsEnabled = granted
                self?.refreshAuthorization()
            }
        }
    }

    private func applyAuthorizationStatus(_ status: UNAuthorizationStatus) {
        authorizationStatus = status
        switch status {
        case .authorized:
            statusKey = "NOTIFICATIONS_STATUS_AUTHORIZED"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_ALLOWED"
            isSystemAuthorized = true
            syncAppNotificationPreferenceIfNeeded(isAuthorized: true)
        case .provisional:
            statusKey = "NOTIFICATIONS_STATUS_PROVISIONAL"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_ALLOWED"
            isSystemAuthorized = true
            syncAppNotificationPreferenceIfNeeded(isAuthorized: true)
        case .ephemeral:
            statusKey = "NOTIFICATIONS_STATUS_EPHEMERAL"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_ALLOWED"
            isSystemAuthorized = true
            syncAppNotificationPreferenceIfNeeded(isAuthorized: true)
        case .denied:
            statusKey = "NOTIFICATIONS_STATUS_DENIED"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_DENIED"
            isSystemAuthorized = false
            appNotificationsEnabled = false
        case .notDetermined:
            statusKey = "NOTIFICATIONS_STATUS_NOT_DETERMINED"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_REQUEST"
            isSystemAuthorized = false
        @unknown default:
            statusKey = "NOTIFICATIONS_STATUS_UNKNOWN"
            permissionsFooterKey = "NOTIFICATIONS_PERMISSIONS_FOOTER_UNKNOWN"
            isSystemAuthorized = false
        }
    }

    private func syncAppNotificationPreferenceIfNeeded(isAuthorized: Bool) {
        guard !hasStoredAppNotificationsPreference,
              isAuthorized,
              appNotificationsEnabled != isAuthorized else { return }
        appNotificationsEnabled = isAuthorized
    }

    private enum Keys {
        static let appNotificationsEnabled = "notifications.appEnabled"
        static let sosAlertsEnabled = "notifications.sosAlerts"
        static let contactUpdatesEnabled = "notifications.contactUpdates"
        static let chatMessagesEnabled = "notifications.chatMessages"
        static let playSoundEnabled = "notifications.playSound"
    }
}
