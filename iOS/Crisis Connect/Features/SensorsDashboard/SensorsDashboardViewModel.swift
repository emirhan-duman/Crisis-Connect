//
//  SensorsDashboardViewModel.swift
//  Crisis Connect
//
//  Created by Assistant on 22.12.2025
//

import SwiftUI
import Combine
import CoreMotion
import CoreLocation
import UIKit

final class SensorsDashboardViewModel: NSObject, ObservableObject, CLLocationManagerDelegate {
    private let motion = CMMotionManager()
    private var altimeter: CMAltimeter? = CMAltimeter()
    private let locationManager = CLLocationManager()
    private var proximityObserver: NSObjectProtocol?

    // Accelerometer (g)
    @Published var accelAvailable: Bool = false
    @Published var accelX: Double = 0
    @Published var accelY: Double = 0
    @Published var accelZ: Double = 0

    // Gyroscope (rad/s)
    @Published var gyroAvailable: Bool = false
    @Published var gyroX: Double = 0
    @Published var gyroY: Double = 0
    @Published var gyroZ: Double = 0

    // Magnetometer (uT)
    @Published var magAvailable: Bool = false
    @Published var magX: Double = 0
    @Published var magY: Double = 0
    @Published var magZ: Double = 0
    @Published var magMagnitude: Double = 0

    // Device Motion (attitude in degrees, magnitudes)
    @Published var deviceMotionAvailable: Bool = false
    @Published var rollDeg: Double = 0
    @Published var pitchDeg: Double = 0
    @Published var yawDeg: Double = 0
    @Published var userAccMag: Double = 0
    @Published var gravityMag: Double = 0

    // Altimeter
    @Published var altimeterAvailable: Bool = false
    @Published var altitudeMeters: Double?
    @Published var pressureKPa: Double?

    // Proximity
    @Published var proximityAvailable: Bool = false
    @Published var isNear: Bool = false

    // Heading
    @Published var headingAvailable: Bool = false
    @Published var headingDegrees: Double?
    @Published var headingAccuracy: Double?
    private var pendingHeadingStart = false

    override init() {
        super.init()
        locationManager.delegate = self
        locationManager.headingFilter = kCLHeadingFilterNone
        locationManager.headingOrientation = .portrait
    }

    func start() {
        startAccelerometer()
        startGyro()
        startMagnetometer()
        startDeviceMotion()
        startAltimeter()
        startHeading()
        startProximity()
    }

    func stop() {
        if motion.isAccelerometerActive { motion.stopAccelerometerUpdates() }
        if motion.isGyroActive { motion.stopGyroUpdates() }
        if motion.isMagnetometerActive { motion.stopMagnetometerUpdates() }
        if motion.isDeviceMotionActive { motion.stopDeviceMotionUpdates() }
        altimeter?.stopRelativeAltitudeUpdates()
        locationManager.stopUpdatingHeading()
        stopProximity()
    }

    func headingDegreesText(from degrees: Double) -> Int {
        Int((degrees.isNaN ? 0 : degrees).rounded())
    }

    func cardinalKey(for degrees: Double) -> String {
        guard degrees.isFinite else { return "" }
        let dirs = ["CARDINAL_N", "CARDINAL_NE", "CARDINAL_E", "CARDINAL_SE", "CARDINAL_S", "CARDINAL_SW", "CARDINAL_W", "CARDINAL_NW", "CARDINAL_N"]
        let idx = Int(((degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) + 22.5) / 45.0)
        return dirs[max(0, min(idx, dirs.count - 1))]
    }

    private func startAccelerometer() {
        guard motion.isAccelerometerAvailable else { accelAvailable = false; return }
        accelAvailable = true
        motion.accelerometerUpdateInterval = 0.1
        motion.startAccelerometerUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let a = data?.acceleration else { return }
            DispatchQueue.main.async {
                self.accelX = a.x
                self.accelY = a.y
                self.accelZ = a.z
            }
        }
    }

    private func startGyro() {
        guard motion.isGyroAvailable else { gyroAvailable = false; return }
        gyroAvailable = true
        motion.gyroUpdateInterval = 0.1
        motion.startGyroUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let g = data?.rotationRate else { return }
            DispatchQueue.main.async {
                self.gyroX = g.x
                self.gyroY = g.y
                self.gyroZ = g.z
            }
        }
    }

    private func startMagnetometer() {
        guard motion.isMagnetometerAvailable else { magAvailable = false; return }
        magAvailable = true
        motion.magnetometerUpdateInterval = 0.2
        motion.startMagnetometerUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let m = data?.magneticField else { return }
            let magnitude = sqrt(m.x * m.x + m.y * m.y + m.z * m.z)
            DispatchQueue.main.async {
                self.magX = m.x
                self.magY = m.y
                self.magZ = m.z
                self.magMagnitude = magnitude
            }
        }
    }

    private func startDeviceMotion() {
        guard motion.isDeviceMotionAvailable else { deviceMotionAvailable = false; return }
        deviceMotionAvailable = true
        motion.deviceMotionUpdateInterval = 0.1
        motion.startDeviceMotionUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let dm = data else { return }
            let roll = dm.attitude.roll * 180 / .pi
            let pitch = dm.attitude.pitch * 180 / .pi
            let yaw = dm.attitude.yaw * 180 / .pi
            let u = dm.userAcceleration
            let g = dm.gravity
            let uMag = sqrt(u.x*u.x + u.y*u.y + u.z*u.z)
            let gMag = sqrt(g.x*g.x + g.y*g.y + g.z*g.z)
            DispatchQueue.main.async {
                self.rollDeg = roll
                self.pitchDeg = pitch
                self.yawDeg = yaw
                self.userAccMag = uMag
                self.gravityMag = gMag
            }
        }
    }

    private func startAltimeter() {
        guard CMAltimeter.isRelativeAltitudeAvailable() else { altimeterAvailable = false; return }
        altimeterAvailable = true
        altimeter?.startRelativeAltitudeUpdates(to: OperationQueue()) { [weak self] data, _ in
            guard let self, let d = data else { return }
            let alt = d.relativeAltitude.doubleValue // meters
            let pressure = d.pressure.doubleValue // kPa
            DispatchQueue.main.async {
                self.altitudeMeters = alt
                self.pressureKPa = pressure
            }
        }
    }

    private func startHeading() {
        guard CLLocationManager.headingAvailable() else { headingAvailable = false; return }
        headingAvailable = true
        switch locationManager.authorizationStatus {
        case .notDetermined:
            pendingHeadingStart = true
            locationManager.requestWhenInUseAuthorization()
        case .authorizedAlways, .authorizedWhenInUse:
            locationManager.startUpdatingHeading()
        default:
            break
        }
    }

    private func startProximity() {
        UIDevice.current.isProximityMonitoringEnabled = true
        proximityAvailable = UIDevice.current.isProximityMonitoringEnabled
        isNear = UIDevice.current.proximityState
        proximityObserver = NotificationCenter.default.addObserver(forName: UIDevice.proximityStateDidChangeNotification, object: nil, queue: .main) { [weak self] _ in
            self?.isNear = UIDevice.current.proximityState
        }
    }

    private func stopProximity() {
        if UIDevice.current.isProximityMonitoringEnabled {
            UIDevice.current.isProximityMonitoringEnabled = false
        }
        if let token = proximityObserver {
            NotificationCenter.default.removeObserver(token)
            proximityObserver = nil
        }
        proximityAvailable = false
        isNear = false
    }

    // MARK: - CLLocationManagerDelegate
    func locationManager(_ manager: CLLocationManager, didUpdateHeading newHeading: CLHeading) {
        DispatchQueue.main.async { [weak self] in
            self?.headingDegrees = (newHeading.trueHeading >= 0 ? newHeading.trueHeading : newHeading.magneticHeading)
            self?.headingAccuracy = newHeading.headingAccuracy >= 0 ? newHeading.headingAccuracy : nil
        }
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard pendingHeadingStart else { return }
        switch manager.authorizationStatus {
        case .authorizedAlways, .authorizedWhenInUse:
            pendingHeadingStart = false
            manager.startUpdatingHeading()
        case .denied, .restricted:
            pendingHeadingStart = false
        default:
            break
        }
    }

    func locationManagerShouldDisplayHeadingCalibration(_ manager: CLLocationManager) -> Bool {
        let acc = headingAccuracy ?? 180
        return acc.isFinite && acc > 20
    }

    deinit {
        stop()
    }
}
