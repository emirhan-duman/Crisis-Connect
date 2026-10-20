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
import UIKit
import Darwin

enum PlatformRuntime {
    static func prepareMacMetalCache() {
        guard isDesignedForIPadOnMac, !isRunningTests,
              ProcessInfo.processInfo.operatingSystemVersion.majorVersion >= 27,
              let bundleIdentifier = Bundle.main.bundleIdentifier,
              let cachesURL = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
        else { return }

        let length = confstr(_CS_DARWIN_USER_CACHE_DIR, nil, 0)
        guard length > 1 else { return }
        var buffer = [CChar](repeating: 0, count: length)
        guard confstr(_CS_DARWIN_USER_CACHE_DIR, &buffer, length) == length else { return }
        let darwinCacheURL = URL(fileURLWithPath: String(cString: buffer), isDirectory: true)
        let metalCacheURL = darwinCacheURL
            .appendingPathComponent(bundleIdentifier, isDirectory: true)
            .appendingPathComponent("com.apple.metal", isDirectory: true)
        let containedCacheURL = cachesURL.appendingPathComponent("MacMetalShaderCache", isDirectory: true)
        // Leave runtimes that already place the cache inside the app container alone.
        let homePath = URL(fileURLWithPath: NSHomeDirectory()).standardizedFileURL.path + "/"
        guard !metalCacheURL.standardizedFileURL.path.hasPrefix(homePath) else { return }
        do {
            try MacMetalCacheCompatibility.containCache(at: metalCacheURL, in: containedCacheURL)
            NSLog("Mac Metal shader cache prepared inside application caches.")
        } catch {
            // Cache repair must never prevent launching. Preserve the old cache on failure.
            NSLog("Mac Metal shader cache preparation failed: %@", String(describing: error))
        }
    }

    enum SystemSettingsPane {
        case privacy
        case notifications
    }

    @MainActor
    static func openSystemSettings(_ pane: SystemSettingsPane = .privacy) {
        let urlString: String
        if isDesignedForIPadOnMac {
            // app-settings: opens Touch Alternatives in the iOS-on-Mac runtime.
            // Permission controls live in the Mac's System Settings instead.
            switch pane {
            case .privacy:
                urlString = "x-apple.systempreferences:com.apple.settings.PrivacySecurity.extension"
            case .notifications:
                urlString = "x-apple.systempreferences:com.apple.Notifications-Settings.extension"
            }
        } else {
            urlString = UIApplication.openSettingsURLString
        }
        guard let url = URL(string: urlString) else { return }
        UIApplication.shared.open(url)
    }

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
        // Core Motion capability queries can crash inside the iOS-on-Mac runtime.
        // Skip manager creation entirely on Macs, which don't expose these sensors.
        guard !isDesignedForIPadOnMac else { return false }
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
        !isDesignedForIPadOnMac
        #endif
    }

    static var supportsNightVision: Bool {
        ARWorldTrackingConfiguration.supportsFrameSemantics(.sceneDepth)
    }

    static var supportsMetalDetector: Bool {
        guard !isDesignedForIPadOnMac else { return false }
        return CMMotionManager().isMagnetometerAvailable
    }

    static var supportsSignalScanner: Bool {
        #if targetEnvironment(simulator)
        false
        #else
        true
        #endif
    }
}

/// macOS 27's iOS-on-Mac Metal runtime opens archiveUsage.db outside the app container.
/// RunningBoard kills suspended apps holding that shared lock (0xDEAD10CC). Keep only
/// this app's Metal cache in its permitted caches directory, using ordinary file APIs.
/// The alias must be installed before any SwiftUI/Core Image/Metal initialization.
enum MacMetalCacheCompatibility {
    static func containCache(at source: URL, in destination: URL, fileManager: FileManager = .default) throws {
        let destination = destination.standardizedFileURL
        if source.resolvingSymlinksInPath().standardizedFileURL == destination.resolvingSymlinksInPath().standardizedFileURL {
            // The OS may purge caches while leaving the alias itself in place.
            try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
            return
        }
        if let linkPath = try? fileManager.destinationOfSymbolicLink(atPath: source.path) {
            // resolvingSymlinksInPath() does not resolve a dangling link after a cache purge.
            let linkedURL = URL(fileURLWithPath: linkPath, relativeTo: source.deletingLastPathComponent())
                .absoluteURL.standardizedFileURL
            if linkedURL == destination {
                try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
                return
            }
            // Reinstalling can replace the container. Repair only a dangling alias to our
            // own named cache; never retarget an existing directory or an unrelated alias.
            guard linkedURL.lastPathComponent == destination.lastPathComponent,
                  !fileManager.fileExists(atPath: linkedURL.path) else {
                throw CocoaError(.fileWriteFileExists)
            }
            try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
            try fileManager.removeItem(at: source) // Removes the dangling alias, not its target.
            do {
                try fileManager.createSymbolicLink(at: source, withDestinationURL: destination)
            } catch {
                try fileManager.createSymbolicLink(atPath: source.path, withDestinationPath: linkPath)
                throw error
            }
            return
        }

        try fileManager.createDirectory(at: source.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fileManager.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)

        let previousCache: URL?
        if fileManager.fileExists(atPath: source.path) {
            // Preserve existing shader data. A partial prior migration gets a separate backup.
            let saved = fileManager.fileExists(atPath: destination.path)
                ? destination.deletingLastPathComponent().appendingPathComponent("MacMetalShaderCache-previous-\(UUID().uuidString)")
                : destination
            try fileManager.moveItem(at: source, to: saved)
            previousCache = saved
        } else {
            previousCache = nil
        }

        do {
            try fileManager.createDirectory(at: destination, withIntermediateDirectories: true)
            try fileManager.createSymbolicLink(at: source, withDestinationURL: destination)
        } catch {
            if let previousCache {
                try fileManager.moveItem(at: previousCache, to: source)
            }
            throw error
        }
    }
}
