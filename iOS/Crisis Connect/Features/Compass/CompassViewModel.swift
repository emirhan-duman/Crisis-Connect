//
//  CompassViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
import CoreLocation
import CoreMotion
import UIKit

final class CompassViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let motionManager = CMMotionManager()
    private var orientationObserver: NSObjectProtocol?
    @Published var heading: CLHeading?
    @Published var isAvailable: Bool = false
    @Published var motionAvailable: Bool = false
    @Published var deviceFlatness: Double = 1

    var angle: Double {
        let value = (heading?.trueHeading ?? -1) >= 0 ? (heading?.trueHeading ?? 0) : (heading?.magneticHeading ?? 0)
        if value < 0 || value > 360 { return 0 }
        return value
    }

    var headingDegrees: Int {
        Int(angle.rounded())
    }

    var directionKey: String {
        let dirs = ["CARDINAL_N", "CARDINAL_NE", "CARDINAL_E", "CARDINAL_SE", "CARDINAL_S", "CARDINAL_SW", "CARDINAL_W", "CARDINAL_NW", "CARDINAL_N"]
        let idx = Int((angle + 22.5) / 45.0)
        return dirs[max(0, min(idx, dirs.count - 1))]
    }

    var headingAccuracy: Double? {
        guard let acc = heading?.headingAccuracy, acc >= 0 else { return nil }
        return acc
    }

    var shouldShowHoldFlatWarning: Bool {
        motionAvailable && deviceFlatness < 0.72
    }

    override init() {
        super.init()
        manager.delegate = self
        manager.headingFilter = kCLHeadingFilterNone
        updateHeadingOrientation()
    }

    func start() {
        guard CLLocationManager.headingAvailable() else {
            isAvailable = false
            return
        }

        isAvailable = true
        startOrientationTracking()
        startMotionUpdates()
        manager.startUpdatingHeading()
    }

    func stop() {
        manager.stopUpdatingHeading()
        if motionManager.isDeviceMotionActive {
            motionManager.stopDeviceMotionUpdates()
        }
        stopOrientationTracking()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        DispatchQueue.main.async { [weak self] in
            self?.heading = newHeading
        }
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        // Request calibration when accuracy is poor (> 20 deg is a common heuristic)
        let acc = headingAccuracy ?? 180
        return acc.isFinite && acc > 20
    }

    deinit {
        stop()
    }

    private func startMotionUpdates() {
        guard motionManager.isDeviceMotionAvailable else {
            motionAvailable = false
            return
        }

        motionAvailable = true
        motionManager.deviceMotionUpdateInterval = 0.2
        motionManager.startDeviceMotionUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let gravity = data?.gravity else { return }
            let flatness = min(max(abs(gravity.z), 0), 1)

            DispatchQueue.main.async {
                self.deviceFlatness = flatness
            }
        }
    }

    private func startOrientationTracking() {
        guard orientationObserver == nil else { return }

        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        updateHeadingOrientation()
        orientationObserver = NotificationCenter.default.addObserver(
            forName: UIDevice.orientationDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.updateHeadingOrientation()
        }
    }

    private func stopOrientationTracking() {
        if let orientationObserver {
            NotificationCenter.default.removeObserver(orientationObserver)
            self.orientationObserver = nil
        }

        UIDevice.current.endGeneratingDeviceOrientationNotifications()
    }

    private func updateHeadingOrientation() {
        switch UIDevice.current.orientation {
        case .portrait:
            manager.headingOrientation = .portrait
        case .portraitUpsideDown:
            manager.headingOrientation = .portraitUpsideDown
        case .landscapeLeft:
            manager.headingOrientation = .landscapeLeft
        case .landscapeRight:
            manager.headingOrientation = .landscapeRight
        case .faceUp:
            manager.headingOrientation = .faceUp
        case .faceDown:
            manager.headingOrientation = .faceDown
        default:
            manager.headingOrientation = .portrait
        }
    }
}
