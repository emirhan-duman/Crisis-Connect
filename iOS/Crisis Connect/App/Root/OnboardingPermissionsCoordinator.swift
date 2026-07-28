//
//  OnboardingPermissionsCoordinator.swift
//  Crisis Connect
//
//  Coordinates Location / Bluetooth / Notifications permission prompts during onboarding.
//

import Foundation
import Combine
import CoreLocation
import CoreBluetooth
import UserNotifications

@MainActor
final class OnboardingPermissionsCoordinator: NSObject, ObservableObject {
    enum Status: Equatable {
        case notDetermined
        case granted
        case denied
        case restricted

        var isResolved: Bool { self != .notDetermined }
    }

    @Published private(set) var locationStatus: Status = .notDetermined
    @Published private(set) var bluetoothStatus: Status = .notDetermined
    @Published private(set) var notificationsStatus: Status = .notDetermined
    /// Authorization granted but the Bluetooth radio itself is OFF — every offline feature is
    /// dead until the user flips it back on (Control Center / Settings). iOS can't do it for
    /// them, so the UI must say it out loud instead of showing a green check.
    @Published private(set) var bluetoothPoweredOff = false

    private lazy var locationManager: CLLocationManager = {
        let manager = CLLocationManager()
        manager.delegate = self
        return manager
    }()

    private var bluetoothManager: CBCentralManager?

    override init() {
        super.init()
        refreshStatuses()
    }

    func refreshStatuses() {
        locationStatus = Self.mapLocation(locationManager.authorizationStatus)
        bluetoothStatus = Self.mapBluetooth(CBManager.authorization)
        // Authorization alone says nothing about the radio. Once authorized, keep a manager
        // alive so centralManagerDidUpdateState reports the power state too (no prompt fires
        // when authorization is already resolved).
        if CBManager.authorization == .allowedAlways, bluetoothManager == nil {
            bluetoothManager = CBCentralManager(
                delegate: self,
                queue: .main,
                options: [CBCentralManagerOptionShowPowerAlertKey: false]
            )
        }
        Task { [weak self] in
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            await MainActor.run {
                self?.notificationsStatus = Self.mapNotifications(settings.authorizationStatus)
            }
        }
    }

    /// Request When-In-Use location authorization. No-op if already resolved.
    func requestLocation() {
        guard locationStatus == .notDetermined else { return }
        locationManager.requestWhenInUseAuthorization()
    }

    /// Request Bluetooth authorization by instantiating a CBCentralManager with a delegate.
    /// No-op if already resolved.
    func requestBluetooth() {
        guard bluetoothStatus == .notDetermined else { return }
        bluetoothManager = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionShowPowerAlertKey: false]
        )
    }

    /// Request Notifications authorization for alert/sound/badge. No-op if already resolved.
    func requestNotifications() async {
        guard notificationsStatus == .notDetermined else { return }
        do {
            let granted = try await UNUserNotificationCenter.current()
                .requestAuthorization(options: [.alert, .sound, .badge])
            notificationsStatus = granted ? .granted : .denied
        } catch {
            notificationsStatus = .denied
        }
    }

    // MARK: - Mapping

    nonisolated private static func mapLocation(_ status: CLAuthorizationStatus) -> Status {
        switch status {
        case .notDetermined: return .notDetermined
        case .authorizedAlways, .authorizedWhenInUse: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        @unknown default: return .notDetermined
        }
    }

    nonisolated private static func mapBluetooth(_ status: CBManagerAuthorization) -> Status {
        switch status {
        case .notDetermined: return .notDetermined
        case .allowedAlways: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        @unknown default: return .notDetermined
        }
    }

    nonisolated private static func mapNotifications(_ status: UNAuthorizationStatus) -> Status {
        switch status {
        case .notDetermined: return .notDetermined
        case .authorized, .provisional, .ephemeral: return .granted
        case .denied: return .denied
        @unknown default: return .notDetermined
        }
    }
}

extension OnboardingPermissionsCoordinator: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = Self.mapLocation(manager.authorizationStatus)
        Task { @MainActor [weak self] in
            self?.locationStatus = status
        }
    }
}

extension OnboardingPermissionsCoordinator: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        let status = Self.mapBluetooth(CBManager.authorization)
        let poweredOff = central.state == .poweredOff
        Task { @MainActor [weak self] in
            self?.bluetoothStatus = status
            self?.bluetoothPoweredOff = poweredOff
        }
    }
}
