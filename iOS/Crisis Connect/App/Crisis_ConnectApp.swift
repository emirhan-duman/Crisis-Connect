//
//  Crisis_ConnectApp.swift
//  Crisis Connect
//
//  Created by Emirhan Duman on 25.10.2025.
//

import SwiftUI
import SwiftData
import Foundation
import Combine
import BackgroundTasks
import FirebaseAppCheck
import FirebaseCore
import FirebaseAuth
import GoogleSignIn
import UIKit
import UserNotifications

enum FirebaseRuntime {
    static func ensureConfigured() {
        guard !PlatformRuntime.isRunningTests else { return }
        guard FirebaseApp.app() == nil else { return }
        AppCheck.setAppCheckProviderFactory(CrisisConnectAppCheckProviderFactory())
        FirebaseApp.configure()
        CrashReporter.configure()
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    private var hasScheduledDeferredLaunchTasks = false

    private func shouldBootstrapBluetoothRuntimesEarly(
        launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        guard let launchOptions else { return false }
        if let centrals = launchOptions[.bluetoothCentrals] as? [String], !centrals.isEmpty {
            return true
        }
        if let peripherals = launchOptions[.bluetoothPeripherals] as? [String], !peripherals.isEmpty {
            return true
        }
        return false
    }

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        guard !PlatformRuntime.isRunningTests else { return true }
        FirebaseRuntime.ensureConfigured()
        OfflineMapBackgroundManager.shared.register()
        CrisisLinkBackgroundRefreshManager.shared.register()
        if PlatformRuntime.supportsRescueRuntime && shouldBootstrapBluetoothRuntimesEarly(launchOptions: launchOptions) {
            _ = RescueClientManager.shared
            _ = GattMeshManager.shared
            RescueClientManager.shared.bootstrapRuntime()
            if !AppStoreScreenshotSupport.isAnySceneEnabled {
                _ = RescueLiveLocationCoordinator.shared
                RescueLiveLocationCoordinator.shared.bootstrap()
            }
            GattMeshManager.shared.bootstrap()
        }
        OfflineMapBackgroundManager.shared.scheduleIfNeeded()
        CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
        return true
    }

    func application(
        _ application: UIApplication,
        open url: URL,
        options: [UIApplication.OpenURLOptionsKey: Any] = [:]
    ) -> Bool {
        handleIncomingURL(url)
    }

    @discardableResult
    func handleIncomingURL(_ url: URL) -> Bool {
        FirebaseRuntime.ensureConfigured()
        if GIDSignIn.sharedInstance.handle(url) {
            NSLog("Handled Google Sign-In callback URL.")
            return true
        }
        if Auth.auth().canHandle(url) {
            NSLog("Handled Firebase Auth callback URL.")
            return true
        }
        NSLog("Unhandled incoming URL with scheme: %@", url.scheme ?? "unknown")
        return false
    }

    func scheduleDeferredLaunchTasks() {
        guard !PlatformRuntime.isRunningTests else { return }
        FirebaseRuntime.ensureConfigured()
        guard !hasScheduledDeferredLaunchTasks else { return }
        hasScheduledDeferredLaunchTasks = true

        Task {
            do {
                _ = try await AppCheck.appCheck().token(forcingRefresh: false)
            } catch {
                NSLog("App Check token warm-up failed: %@", String(describing: error))
#if DEBUG
                let diagnostic = String(describing: error).lowercased()
                if diagnostic.contains("app attestation failed")
                    || diagnostic.contains("exchangedebugtoken") {
                    NSLog(
                        "Firebase App Check rejected this debug build. Register the printed debug token in Firebase Console > App Check, or verify App Attest/DeviceCheck configuration."
                    )
                }
#endif
            }
        }

        FirebaseBootstrapper.shared.start()
        SOSNotificationCenter.configure()

        Task { @MainActor in
            _ = ChatPeerVoiceCallCoordinator.shared
            ChatPeerVoiceCallCoordinator.shared.bootstrap()
            OfflineMapBackgroundManager.shared.scheduleIfNeeded()
            CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
            if PlatformRuntime.supportsBlePeripheralHosting {
                _ = ContactBroadcastManager.shared
                _ = GattMeshManager.shared
            }
            RescueClientManager.shared.bootstrapRuntime()
            if !AppStoreScreenshotSupport.isAnySceneEnabled {
                RescueLiveLocationCoordinator.shared.bootstrap()
            }
            GattMeshManager.shared.bootstrap()
        }
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        guard !PlatformRuntime.isRunningTests else { return }
        RescueClientManager.shared.bootstrapRuntime()
        if !AppStoreScreenshotSupport.isAnySceneEnabled {
            RescueLiveLocationCoordinator.shared.bootstrap()
        }
        GattMeshManager.shared.bootstrap()
        OfflineMapBackgroundManager.shared.scheduleIfNeeded()
        CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
    }

    func applicationDidBecomeActive(_ application: UIApplication) {
        guard !PlatformRuntime.isRunningTests else { return }
        RescueClientManager.shared.bootstrapRuntime()
        if !AppStoreScreenshotSupport.isAnySceneEnabled {
            RescueLiveLocationCoordinator.shared.bootstrap()
        }
        GattMeshManager.shared.bootstrap()
        OfflineMapBackgroundManager.shared.scheduleIfNeeded()
        CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        guard !PlatformRuntime.isRunningTests else { return }
        OfflineMapBackgroundManager.shared.scheduleIfNeeded()
        CrisisLinkBackgroundRefreshManager.shared.scheduleIfNeeded()
    }
}

@main
struct Crisis_ConnectApp: App {
    @StateObject private var settings = AppSettingsViewModel()
    @StateObject private var startupCoordinator = AppStartupCoordinator()
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        if PlatformRuntime.isRunningTests {
            Self.applyUITestLaunchOverrides()
        }
        Self.configureSystemAppearance()
        if !PlatformRuntime.isRunningTests {
            FirebaseRuntime.ensureConfigured()
        }
#if DEBUG
        let localizations = Bundle.main.localizations
        let preferred = Bundle.main.preferredLocalizations
        let dev = Bundle.main.developmentLocalization ?? "nil"
        let enLocalizable = Bundle.main.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "en") != nil
        let trLocalizable = Bundle.main.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: "tr") != nil
        print("🌐 Localizations -> localizations: \(localizations), preferred: \(preferred), dev: \(dev), en.strings: \(enLocalizable), tr.strings: \(trLocalizable)")
#endif
    }

    private static func applyUITestLaunchOverrides() {
        let arguments = ProcessInfo.processInfo.arguments
        let defaults = UserDefaults.standard

        if arguments.contains("UITEST_SET_ONBOARDING_COMPLETE") {
            defaults.set(true, forKey: "hasCompletedOnboarding")
        }

        if arguments.contains("UITEST_SET_ONBOARDING_INCOMPLETE") {
            defaults.set(false, forKey: "hasCompletedOnboarding")
        }
    }

    private static func configureSystemAppearance() {
        let tabBarAppearance = UITabBarAppearance()
        tabBarAppearance.configureWithOpaqueBackground()
        tabBarAppearance.backgroundColor = UIColor(Color.appSurfaceElevated)
        tabBarAppearance.shadowColor = UIColor(Color.appBorder)
        applyTabBarItemColors(to: tabBarAppearance.stackedLayoutAppearance)
        applyTabBarItemColors(to: tabBarAppearance.inlineLayoutAppearance)
        applyTabBarItemColors(to: tabBarAppearance.compactInlineLayoutAppearance)

        let navigationAppearance = UINavigationBarAppearance()
        navigationAppearance.configureWithOpaqueBackground()
        navigationAppearance.backgroundColor = UIColor(Color.appSurfaceElevated)
        navigationAppearance.shadowColor = UIColor(Color.appBorder)
        navigationAppearance.titleTextAttributes = [
            .foregroundColor: UIColor.label
        ]
        navigationAppearance.largeTitleTextAttributes = [
            .foregroundColor: UIColor.label
        ]

        UITabBar.appearance().standardAppearance = tabBarAppearance
        UITabBar.appearance().scrollEdgeAppearance = tabBarAppearance
        UITabBar.appearance().tintColor = UIColor(Color.appPrimary)

        UINavigationBar.appearance().standardAppearance = navigationAppearance
        UINavigationBar.appearance().scrollEdgeAppearance = navigationAppearance
        UINavigationBar.appearance().compactAppearance = navigationAppearance
        UINavigationBar.appearance().compactScrollEdgeAppearance = navigationAppearance
        UINavigationBar.appearance().prefersLargeTitles = true
        UINavigationBar.appearance().tintColor = UIColor(Color.appPrimary)
    }

    private static func applyTabBarItemColors(to appearance: UITabBarItemAppearance) {
        let normalColor = UIColor.secondaryLabel
        let selectedColor = UIColor(Color.appPrimary)

        appearance.normal.iconColor = normalColor
        appearance.normal.titleTextAttributes = [
            .foregroundColor: normalColor
        ]
        appearance.selected.iconColor = selectedColor
        appearance.selected.titleTextAttributes = [
            .foregroundColor: selectedColor
        ]
    }

    nonisolated static func makeSharedModelContainer() -> ModelContainer? {
        let schema = Schema([
            Profile.self,
            Item.self,
        ])

        do {
            return try persistentModelContainer(for: schema)
        } catch {
            let storeURL = defaultStoreURL()
            NSLog("SwiftData persistent store failed at %@: %@", storeURL.path, String(describing: error))
            resetPersistentStore(at: storeURL)

            do {
                return try persistentModelContainer(for: schema)
            } catch {
                NSLog("SwiftData persistent store recovery failed, falling back to in-memory store: %@", String(describing: error))
                do {
                    return try inMemoryModelContainer(for: schema)
                } catch {
                    NSLog("SwiftData in-memory fallback failed: %@", String(describing: error))
                    return nil
                }
            }
        }
    }

    nonisolated private static func persistentModelContainer(for schema: Schema) throws -> ModelContainer {
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false)
        let container = try ModelContainer(for: schema, configurations: [configuration])
        hardenPersistentStoreArtifacts(at: defaultStoreURL())
        return container
    }

    nonisolated private static func inMemoryModelContainer(for schema: Schema) throws -> ModelContainer {
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    nonisolated private static func defaultStoreURL() -> URL {
        let applicationSupportURL = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        return applicationSupportURL.appendingPathComponent("default.store")
    }

    nonisolated private static func resetPersistentStore(at storeURL: URL) {
        let fileManager = FileManager.default
        let storeDirectory = storeURL.deletingLastPathComponent()

        if !fileManager.fileExists(atPath: storeDirectory.path) {
            try? fileManager.createDirectory(at: storeDirectory, withIntermediateDirectories: true)
        }

        let relatedURLs = [
            storeURL,
            URL(fileURLWithPath: storeURL.path + "-shm"),
            URL(fileURLWithPath: storeURL.path + "-wal"),
        ]

        for url in relatedURLs where fileManager.fileExists(atPath: url.path) {
            do {
                try fileManager.removeItem(at: url)
            } catch {
                NSLog("Failed to remove SwiftData store artifact at %@: %@", url.path, String(describing: error))
            }
        }
    }

    nonisolated private static func hardenPersistentStoreArtifacts(at storeURL: URL) {
        let fileManager = FileManager.default
        let attributes: [FileAttributeKey: Any] = [
            .protectionKey: FileProtectionType.completeUntilFirstUserAuthentication
        ]

        let directory = storeURL.deletingLastPathComponent()
        if !fileManager.fileExists(atPath: directory.path) {
            try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true, attributes: attributes)
        }
        try? fileManager.setAttributes(attributes, ofItemAtPath: directory.path)

        let urls = [
            storeURL,
            URL(fileURLWithPath: storeURL.path + "-shm"),
            URL(fileURLWithPath: storeURL.path + "-wal")
        ]
        for url in urls where fileManager.fileExists(atPath: url.path) {
            try? fileManager.setAttributes(attributes, ofItemAtPath: url.path)
        }
    }

    var body: some Scene {
        WindowGroup {
            AppStartupHost(coordinator: startupCoordinator)
            .environmentObject(settings)
            .environment(\.locale, settings.resolvedLocale)
            .preferredColorScheme(settings.resolvedColorScheme)
            .task {
                await startupCoordinator.start(appDelegate: appDelegate)
            }
            .onOpenURL { url in
                _ = appDelegate.handleIncomingURL(url)
            }
        }
    }
}

@MainActor
private final class AppStartupCoordinator: ObservableObject {
    enum Phase {
        case launching
        case ready(ModelContainer)
        case failed
    }

    @Published private(set) var phase: Phase = .launching
    private var hasStarted = false
    private let minimumSplashDuration: TimeInterval = 0.25

    func start(appDelegate: AppDelegate) async {
        guard !hasStarted else { return }
        hasStarted = true

        await Task.yield()

        let startDate = Date()
        let modelContainer = await Task.detached(priority: .userInitiated) {
            Crisis_ConnectApp.makeSharedModelContainer()
        }.value
        let remaining = max(0, minimumSplashDuration - Date().timeIntervalSince(startDate))

        if remaining > 0 {
            try? await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
        }

        withAnimation(.easeOut(duration: 0.24)) {
            phase = modelContainer.map(Phase.ready) ?? .failed
        }

        guard modelContainer != nil else { return }

        try? await Task.sleep(nanoseconds: 280_000_000)
        appDelegate.scheduleDeferredLaunchTasks()
    }
}

private struct AppStartupHost: View {
    @ObservedObject var coordinator: AppStartupCoordinator

    var body: some View {
        ZStack {
            switch coordinator.phase {
            case .launching:
                SplashView()
                    .transition(.opacity)
            case .ready(let modelContainer):
                RootView()
                    .modelContainer(modelContainer)
                    .transition(.opacity)
            case .failed:
                StartupRecoveryView()
                    .transition(.opacity)
            }
        }
    }
}

private struct StartupRecoveryView: View {
    var body: some View {
        ZStack {
            Color.appBackground
                .ignoresSafeArea()

            VStack(spacing: 16) {
                Text("Crisis Connect")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(Color.appPrimary)

                Text("APP_ERROR_TITLE")
                    .font(.headline)
                    .foregroundStyle(.primary)

                Text("APP_ERROR_MESSAGE")
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(Color.appTextSecondary)
                    .padding(.horizontal, 24)
            }
            .padding(24)
        }
    }
}
