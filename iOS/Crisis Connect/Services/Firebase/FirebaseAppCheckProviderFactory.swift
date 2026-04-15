//
//  FirebaseAppCheckProviderFactory.swift
//  Crisis Connect
//
//  Created by Assistant on 11.03.2026.
//

import Foundation
import FirebaseAppCheck
import FirebaseCore

final class CrisisConnectAppCheckProviderFactory: NSObject, AppCheckProviderFactory {
    func createProvider(with app: FirebaseApp) -> AppCheckProvider? {
        if Self.shouldUseDebugProvider {
            NSLog("Using Firebase App Check debug provider.")
            return AppCheckDebugProvider(app: app)
        }

#if targetEnvironment(simulator)
        return AppCheckDebugProvider(app: app)
#else
        if #available(iOS 14.0, *),
           let provider = AppAttestProvider(app: app) {
            return provider
        }
        return DeviceCheckProvider(app: app)
#endif
    }

    private static var shouldUseDebugProvider: Bool {
#if DEBUG
        let token = ProcessInfo.processInfo.environment["FIRAAppCheckDebugToken"]?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return token?.isEmpty == false
#else
        return false
#endif
    }
}
