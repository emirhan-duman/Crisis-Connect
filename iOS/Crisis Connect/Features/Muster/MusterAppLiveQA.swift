#if MUSTER_APP_LIVE_QA && !DEBUG
#error("MUSTER_APP_LIVE_QA requires a Debug build")
#endif

#if DEBUG && MUSTER_APP_LIVE_QA
import Combine
import SwiftUI
import UIKit

/// An App Live package opens the test scene directly, without constructing the normal app delegate.
struct MusterAppLiveQAApp: App {
    @UIApplicationDelegateAdaptor(MusterAppLiveQADelegate.self) private var delegate

    var body: some Scene {
        WindowGroup { MusterAppLiveQAView() }
    }
}

private final class MusterAppLiveQADelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MusterAppLiveBackground.register()
        return true
    }
}

private struct MusterAppLiveQAView: View {
    @StateObject private var setup = MusterAppLiveSetup()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        NavigationStack {
            MusterOfflineFixtureView(runID: setup.runID, useHTTPS: setup.transport != nil,
                                     configuredTransport: setup.transport)
                .id(setup.modeID)
                .navigationTitle("Muster QA")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("HTTPS setup") { setup.presented = true }
                            .accessibilityIdentifier("muster-app-live-https-setup")
                    }
                }
        }
        .sheet(isPresented: $setup.presented, onDismiss: setup.clearInputs) {
            NavigationStack {
                Form {
                    SecureField("Temporary iOS fixture JSON", text: $setup.manifest)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Public test CA PEM", text: $setup.caPEM, axis: .vertical)
                        .lineLimit(3...6)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Approved LAN IPv4 (optional)", text: $setup.lanIPv4)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                    if setup.invalid {
                        Text("The HTTPS configuration is invalid or expired.")
                            .foregroundStyle(.red)
                    }
                    Button("Use local HTTPS", action: setup.configure)
                        .disabled(setup.manifest.isEmpty)
                    if setup.transport != nil {
                        Button("Use synthetic connection", action: setup.useSynthetic)
                    }
                }
                .navigationTitle("HTTPS setup")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Close") { setup.presented = false }
                    }
                }
            }
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { setup.clearInputs() }
        }
    }
}

@MainActor
private final class MusterAppLiveSetup: ObservableObject {
    let runID: UUID
    @Published private(set) var modeID = UUID()
    @Published private(set) var transport: MusterOfflineHTTPS?
    @Published private(set) var invalid = false
    @Published var manifest = ""
    @Published var caPEM = ""
    @Published var lanIPv4 = ""
    @Published var presented = false

    init() {
        let key = "com.auralis.crisisconnect.muster-app-live-qa.run-id"
        if let value = UserDefaults.standard.string(forKey: key), let existing = UUID(uuidString: value) {
            runID = existing
        } else {
            runID = UUID()
            UserDefaults.standard.set(runID.uuidString, forKey: key)
        }
    }

    func configure() {
        let certificate = caPEM.trimmingCharacters(in: .whitespacesAndNewlines)
        let address = lanIPv4.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let configured = try MusterOfflineHTTPS(json: manifest, caPEM: certificate.isEmpty ? nil : certificate,
                                                    approvedLANIPv4: address.isEmpty ? nil : address)
            switchMode(to: configured)
        } catch {
            clearInputs()
            invalid = true
        }
    }

    func useSynthetic() {
        switchMode(to: nil)
    }

    func clearInputs() {
        manifest = ""
        caPEM = ""
        lanIPv4 = ""
        invalid = false
    }

    private func switchMode(to next: MusterOfflineHTTPS?) {
        if transport != nil { MusterAppLiveBackground.detach() }
        transport?.close()
        transport = next
        modeID = UUID()
        clearInputs()
        presented = false
    }
}
#endif
