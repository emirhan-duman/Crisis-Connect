//
//  CrisisConnectWatchApp.swift
//  CrisisConnectWatch Watch App
//
//  Created by Emirhan Duman on 12.10.2026.
//

import SwiftUI

@main
struct CrisisConnectWatch_Watch_AppApp: App {
    var body: some Scene {
        WindowGroup {
            #if DEBUG && targetEnvironment(simulator) && CC_WATCH_INDEPENDENT
            if ProcessInfo.processInfo.environment["CC_INDEPENDENT_PHONE_TEST"] == "1" {
                IndependentWatchPhoneTestView()
            } else if ProcessInfo.processInfo.environment["CC_INDEPENDENT_WATCH_TEST"] == "1" {
                IndependentWatchSOSTestView()
            } else { ContentView() }
            #else
            ContentView()
            #endif
        }
    }
}
