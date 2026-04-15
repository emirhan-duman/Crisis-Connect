//
//  PlatformRuntime.swift
//  Crisis Connect
//
//  Created by Codex on 07.03.2026.
//

import Foundation
import ARKit
import CoreLocation
import CoreMotion

enum PlatformRuntime {
    static var isRunningTests: Bool {
        let processInfo = ProcessInfo.processInfo
        return processInfo.environment["XCTestConfigurationFilePath"] != nil
            || processInfo.arguments.contains(where: { $0.hasPrefix("UITEST_") })
    }

    nonisolated static var isDesignedForIPadOnMac: Bool {
        if #available(iOS 14.0, *) {
            return ProcessInfo.processInfo.isiOSAppOnMac
        }
        return false
    }

    nonisolated static var supportsRescueRuntime: Bool {
        !isDesignedForIPadOnMac
    }

    nonisolated static var supportsBlePeripheralHosting: Bool {
        supportsRescueRuntime
    }

    static var supportsSensorsDashboard: Bool {
        let motionManager = CMMotionManager()
        return motionManager.isAccelerometerAvailable
            || motionManager.isGyroAvailable
            || motionManager.isMagnetometerAvailable
            || motionManager.isDeviceMotionAvailable
            || CMAltimeter.isRelativeAltitudeAvailable()
    }

    static var supportsCompass: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        true
        #endif
    }

    static var supportsNightVision: Bool {
        ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    }

    static var supportsMetalDetector: Bool {
        CMMotionManager().isMagnetometerAvailable
    }

    static var supportsSignalScanner: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        true
        #endif
    }
}
