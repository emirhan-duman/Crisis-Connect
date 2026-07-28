//
//  ContentView.swift
//  Crisis Connect
//
//  Created by Emirhan Duman on 25.10.2025.
//

import SwiftUI
import SwiftData
import StoreKit
import UIKit
import CoreImage.CIFilterBuiltins
import Combine
import FirebaseAuth

private enum PresentedChatDestination: Identifiable {
    case session(UUID)
    case generalChat
    case authorityChannels
    case authorityThread(channelId: String, peerUid: String)

    var id: String {
        switch self {
        case .session(let sessionId):
            return "session.\(sessionId.uuidString)"
        case .generalChat:
            return "general-chat"
        case .authorityChannels:
            return "authority-channels"
        case .authorityThread(let channelId, let peerUid):
            return "authority-thread.\(channelId).\(peerUid)"
        }
    }
}

struct ContentView: View {
    @EnvironmentObject private var settings: AppSettingsViewModel
    @StateObject private var viewModel = ContentViewModel()
    @StateObject private var voiceCallCoordinator = ChatPeerVoiceCallCoordinator.shared
    @State private var rescueRole: String = SecureLocalStore.shared.loadRole() ?? "user"
    @State private var presentedChatDestination: PresentedChatDestination?
    // Settings' aggregated Bluetooth/Location reminder reuses onboarding's status mapping so a
    // revoked permission (which silently kills the offline mesh) surfaces an in-app nudge.
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var permissionsMonitor = OnboardingPermissionsCoordinator()

    var body: some View {
        ZStack(alignment: .top) {
            TabView(selection: $viewModel.selectedTab) {
                NavigationStack {
                    messagesScreen
                }
                .appNavigationBarStyle()
                .fullScreenCover(isPresented: $viewModel.showingNewChat) {
                    NavigationStack {
                        NewContactView(
                            onQrSharePaired: {
                                viewModel.closeNewChat()
                            },
                            showAddFromAgency: showRescueTools
                        )
                        .navigationTitle("New Contact")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .topBarTrailing) {
                                Button("COMMON_CLOSE") { viewModel.closeNewChat() }
                            }
                        }
                        .appNavigationBarStyle()
                    }
                }
                .fullScreenCover(isPresented: $viewModel.showingSOS) {
                    NavigationStack {
                        SOSBroadcastView {
                            viewModel.showingSOS = false
                        }
                        .navigationTitle("SOS")
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button {
                                    viewModel.showingSOS = false
                                } label: {
                                    Label("Back", systemImage: "chevron.left")
                                }
                            }
                        }
                        .appNavigationBarStyle()
                    }
                    .interactiveDismissDisabled(true)
                }
                .fullScreenCover(isPresented: $viewModel.showingRecentDisasters) {
                    NavigationStack {
                        RecentDisastersView()
                            .toolbar {
                                ToolbarItem(placement: .topBarLeading) {
                                    Button {
                                        viewModel.showingRecentDisasters = false
                                    } label: {
                                        Label("Back", systemImage: "chevron.left")
                                    }
                                }
                            }
                            .appNavigationBarStyle()
                    }
                }
                .tabItem {
                    Label("Messages", systemImage: "bubble.left.and.bubble.right")
                }
                .tag(ContentViewModel.Tab.messages)

                NavigationStack {
                    toolsScreen
                }
                .accessibilityIdentifier("tab-tools-root")
                .appNavigationBarStyle()
                .tabItem {
                    Label("Tools", systemImage: "wrench.and.screwdriver")
                }
                .tag(ContentViewModel.Tab.tools)

                NavigationStack {
                    ZStack {
                        AppScreenBackground()
                        SurvivalGuideView()
                    }
                    .navigationTitle("Survival Guide")
                    .navigationBarTitleDisplayMode(.inline)
                }
                .appNavigationBarStyle()
                .tabItem {
                    Label("Survival Guide", systemImage: "book")
                }
                .tag(ContentViewModel.Tab.guide)

                NavigationStack {
                    settingsScreen
                }
                .accessibilityIdentifier("tab-settings-root")
                .appNavigationBarStyle()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(ContentViewModel.Tab.settings)
            }

            if let snapshot = activeCallBannerSnapshot {
                ChatPeerVoiceActiveCallBanner(
                    snapshot: snapshot,
                    onOpen: { openCallSession(snapshot.sessionId) },
                    onEnd: { voiceCallCoordinator.endCall() }
                )
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .zIndex(1)
            }

            // Internet (WebRTC) voice call overlay — global, so an incoming call surfaces on
            // whatever screen is open.
            InternetCallOverlayHost()
                .zIndex(2)

            // SFU authority-call overlay (inert while SfuCallConfig.enabled is false).
            SfuCallOverlayHost()
                .zIndex(2)

            // Nearby SPAKE2 pairing consent. Referencing the host is ALSO what instantiates the
            // responder singleton, which registers the "open to add" beacon with the shared
            // peripheral coordinator — without this the pairing service is never hosted and a peer
            // can never establish the offline Bluetooth link (Android shows a notification here;
            // in-app consent is the iOS equivalent since pairing happens with the app open).
            NearbyPairingApprovalHost()
                .zIndex(2)

            // Rescue-certificate provisioning banner (Android's CertificateProvisioningBanner) —
            // global, so first-time issuance after sign-in is visible from any screen.
            CertificateProvisioningBannerHost()
                .zIndex(3)
        }
        .tint(.appPrimary)
        .id(settings.appLanguage)
        .accessibilityIdentifier("main-tab-view")
        .fullScreenCover(item: incomingCallPresentationBinding) { presentation in
            ChatPeerVoiceIncomingCallScreen(
                presentation: presentation,
                onAnswer: {
                    voiceCallCoordinator.answerCall(sessionId: presentation.sessionId)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        openCallSession(presentation.sessionId)
                    }
                },
                onReject: {
                    voiceCallCoordinator.rejectIncomingCall(sessionId: presentation.sessionId)
                },
                onOpenChat: {
                    voiceCallCoordinator.dismissIncomingPresentation()
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                        openCallSession(presentation.sessionId)
                    }
                }
            )
        }
        .fullScreenCover(item: $presentedChatDestination) { destination in
            NavigationStack {
                presentedChatDestinationView(for: destination)
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button("COMMON_CLOSE") {
                                presentedChatDestination = nil
                            }
                        }
                    }
                    .appNavigationBarStyle()
            }
        }
        .onAppear {
            rescueRole = SecureLocalStore.shared.loadRole() ?? "user"
            voiceCallCoordinator.bootstrap()
            consumePendingOpenSessionIfNeeded()
            consumePendingNotificationRouteIfNeeded()
            AppAnalytics.screenView(viewModel.selectedTab.screenRoute)
        }
        .onChange(of: viewModel.selectedTab) { _, newTab in
            // Central screen-view logging for the primary tab navigation (Android's nav observer).
            AppAnalytics.screenView(newTab.screenRoute)
        }
        .onReceive(NotificationCenter.default.publisher(for: .rescueRoleDidChange)) { _ in
            rescueRole = SecureLocalStore.shared.loadRole() ?? "user"
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .appNotificationRouteDidChange)
                .receive(on: RunLoop.main)
        ) { _ in
            consumePendingNotificationRouteIfNeeded()
        }
        .onChange(of: voiceCallCoordinator.requestedOpenSessionId) { _, _ in
            consumePendingOpenSessionIfNeeded()
        }
        .onChange(of: scenePhase) { _, phase in
            // Re-check after the user returns from the system Settings app (the exact moment they'd
            // flip Bluetooth/Location back on) so the reminder card clears itself.
            if phase == .active {
                permissionsMonitor.refreshStatuses()
            }
        }
    }

    private var incomingCallPresentationBinding: Binding<ChatPeerVoiceIncomingPresentation?> {
        Binding(
            get: { voiceCallCoordinator.incomingPresentation },
            set: { nextValue in
                if nextValue == nil {
                    voiceCallCoordinator.dismissIncomingPresentation()
                }
            }
        )
    }

    private var activeCallBannerSnapshot: ChatPeerVoiceCallSnapshot? {
        guard let snapshot = voiceCallCoordinator.activeCall else { return nil }
        switch snapshot.phase {
        case .dialing, .connecting, .active:
            return snapshot
        case .ringing:
            return snapshot.direction == .outgoing ? snapshot : nil
        case .idle, .ended, .failed:
            return nil
        }
    }

    @ViewBuilder
    private func presentedChatDestinationView(for destination: PresentedChatDestination) -> some View {
        switch destination {
        case .session(let sessionId):
            SOSChatDetailScreen(sessionId: sessionId)
        case .generalChat:
            GattMeshView()
        case .authorityChannels:
            AuthorityChannelsListView()
        case .authorityThread(let channelId, let peerUid):
            AuthorityThreadDeepLinkView(channelId: channelId, peerUid: peerUid)
        }
    }

    private func openCallSession(_ sessionId: UUID) {
        viewModel.selectedTab = .messages
        presentedChatDestination = .session(sessionId)
    }

    private func openGeneralChat() {
        viewModel.selectedTab = .messages
        presentedChatDestination = .generalChat
    }

    private func consumePendingOpenSessionIfNeeded() {
        guard let sessionId = voiceCallCoordinator.requestedOpenSessionId else { return }
        openCallSession(sessionId)
        voiceCallCoordinator.consumeRequestedOpenSession()
    }

    private func consumePendingNotificationRouteIfNeeded() {
        guard let route = SOSNotificationCenter.consumePendingRoute() else { return }
        switch route {
        case .session(let sessionId):
            openCallSession(sessionId)
        case .generalChat:
            openGeneralChat()
        case .authorityChannels:
            viewModel.selectedTab = .messages
            presentedChatDestination = .authorityChannels
        case .authorityThread(let channelId, let peerUid):
            viewModel.selectedTab = .messages
            presentedChatDestination = .authorityThread(channelId: channelId, peerUid: peerUid)
        case .sosCountdown:
            // Widget tap: open the SOS flow — its own arming countdown stays the
            // guard against accidental taps.
            viewModel.showingSOS = true
        case .recentDisasters:
            viewModel.showingRecentDisasters = true
        }
    }

    private var messagesScreen: some View {
        MessagesHomeView(
            searchText: $viewModel.searchText,
            showRescueTools: showRescueTools,
            onOpenNewChat: viewModel.openNewChat,
            onOpenSOS: viewModel.openSOS
        )
    }

    private var toolsScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 12) {
                    Text("TOOLS_SECTION_ASSISTANT")
                        .font(.headline)
                    crisisSentinelToolLink
                }

                if hasDetectTools {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("TOOLS_SECTION_DETECT")
                            .font(.headline)

                        if PlatformRuntime.supportsSensorsDashboard {
                            toolLink(title: NSLocalizedString("Sensors", comment: ""), subtitle: NSLocalizedString("TOOLS_SENSORS_SUBTITLE", comment: ""), systemImage: "sensor", tint: .appPrimary) {
                                SensorsDashboardView()
                            }
                        }

                        if PlatformRuntime.supportsCompass {
                            toolLink(title: NSLocalizedString("Compass", comment: ""), subtitle: NSLocalizedString("TOOLS_COMPASS_SUBTITLE", comment: ""), systemImage: "location.north", tint: .appPrimary) {
                                CompassView()
                            }
                        }

                        if PlatformRuntime.supportsNightVision {
                            toolLink(
                                title: NSLocalizedString("NIGHT_VISION_TOOL_TITLE", comment: ""),
                                subtitle: NSLocalizedString("NIGHT_VISION_TOOL_SUBTITLE", comment: ""),
                                systemImage: "eye.fill",
                                tint: .appSuccess
                            ) {
                                LiDARView()
                            }
                        }

                        if PlatformRuntime.supportsMetalDetector {
                            toolLink(title: NSLocalizedString("Metal Detector", comment: ""), subtitle: NSLocalizedString("TOOLS_METAL_DETECTOR_SUBTITLE", comment: ""), systemImage: "dot.radiowaves.left.and.right", tint: .appWarning) {
                                MetalDetectorView()
                            }
                        }
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("TOOLS_SECTION_SIGNAL")
                        .font(.headline)

                    if PlatformRuntime.supportsSignalScanner {
                        toolLink(title: NSLocalizedString("Signal Finder", comment: ""), subtitle: NSLocalizedString("TOOLS_SIGNAL_FINDER_SUBTITLE", comment: ""), systemImage: "antenna.radiowaves.left.and.right", tint: .appPrimary) {
                            SignalScannerView()
                        }
                    }

                    toolLink(title: NSLocalizedString("Whistle", comment: ""), subtitle: NSLocalizedString("TOOLS_WHISTLE_SUBTITLE", comment: ""), systemImage: "speaker.wave.3.fill", tint: .appWarning) {
                        WhistleView()
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("TOOLS_SECTION_NAVIGATE")
                        .font(.headline)
                    toolLink(
                        title: NSLocalizedString("Offline Map", comment: ""),
                        subtitle: NSLocalizedString("TOOLS_OFFLINE_MAP_SUBTITLE", comment: ""),
                        systemImage: "map",
                        tint: .appSuccess,
                        accessibilityIdentifier: "tool-link-offline-map"
                    ) {
                        OfflineMapView()
                    }
                }

                VStack(alignment: .leading, spacing: 12) {
                    Text("TOOLS_SECTION_AWARENESS")
                        .font(.headline)
                    toolLink(
                        title: NSLocalizedString("RECENT_DISASTERS_TITLE", comment: ""),
                        subtitle: NSLocalizedString("TOOLS_RECENT_DISASTERS_SUBTITLE", comment: ""),
                        systemImage: "globe.americas.fill",
                        tint: .appDanger,
                        accessibilityIdentifier: "tool-link-recent-disasters"
                    ) {
                        RecentDisastersView()
                    }
                }
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle("Tools")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("tools-screen")
    }

    private var settingsScreen: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                missingPermissionsCard

                settingsSectionLabel(NSLocalizedString("SETTINGS_SECTION_IDENTITY", comment: ""))
                settingsLink(title: NSLocalizedString("Profile", comment: ""), subtitle: NSLocalizedString("SETTINGS_PROFILE_SUBTITLE", comment: ""), systemImage: "person.crop.circle", tint: .appPrimary) {
                    ProfileView()
                }

                if showRescueTools {
                    settingsLink(
                        title: NSLocalizedString("RESCUE_SETTINGS_TITLE", comment: ""),
                        subtitle: NSLocalizedString("RESCUE_SETTINGS_SUBTITLE", comment: ""),
                        systemImage: "dot.radiowaves.left.and.right",
                        tint: .appDanger
                    ) {
                        RescueSettingsView()
                    }
                }

                settingsSectionLabel(NSLocalizedString("SETTINGS_SECTION_PREFERENCES", comment: ""))
                settingsLink(title: NSLocalizedString("Language", comment: ""), subtitle: NSLocalizedString(settings.languageNameKey, comment: ""), systemImage: "globe", tint: .appPrimary) {
                    LanguageSettingsView(settings: settings)
                }

                settingsLink(title: NSLocalizedString("Theme", comment: ""), subtitle: NSLocalizedString(settings.themeNameKey, comment: ""), systemImage: "circle.lefthalf.filled", tint: .appPrimary) {
                    ThemeSettingsView(settings: settings)
                }

                settingsLink(
                    title: NSLocalizedString("ADVANCED_SETTINGS_TITLE", comment: ""),
                    subtitle: NSLocalizedString("ADVANCED_SETTINGS_LINK_SUBTITLE", comment: ""),
                    systemImage: "slider.horizontal.3",
                    tint: .appWarning
                ) {
                    AdvancedSettingsView()
                }

                settingsSectionLabel(NSLocalizedString("SETTINGS_SECTION_SAFETY", comment: ""))
                settingsLink(
                    title: NSLocalizedString("SOS_EMERGENCY_CONTACTS_TITLE", comment: ""),
                    subtitle: NSLocalizedString("SOS_EMERGENCY_CONTACTS_SUBTITLE", comment: ""),
                    systemImage: "person.crop.circle.badge.exclamationmark",
                    tint: .appDanger
                ) {
                    SosEmergencyContactsView()
                }
                settingsLink(
                    title: NSLocalizedString("CHILD_PROFILE_TITLE", comment: ""),
                    // Live status: with the mode ON the static marketing line hid the one fact the
                    // parent checks this row for.
                    subtitle: ChildProfileManager.shared.isEnabled
                        ? NSLocalizedString("CHILD_PROFILE_STATUS_ENABLED", comment: "")
                        : NSLocalizedString("CHILD_PROFILE_LINK_SUBTITLE", comment: ""),
                    systemImage: "figure.and.child.holdinghands",
                    tint: .appPrimary
                ) {
                    ChildProfileView()
                }
                settingsLink(title: NSLocalizedString("Notifications", comment: ""), subtitle: NSLocalizedString("SETTINGS_NOTIFICATIONS_SUBTITLE", comment: ""), systemImage: "bell", tint: .appWarning) {
                    NotificationsView()
                }

                settingsLink(
                    title: NSLocalizedString("Privacy", comment: ""),
                    subtitle: NSLocalizedString("SETTINGS_PRIVACY_SUBTITLE", comment: ""),
                    systemImage: "lock",
                    tint: .appSuccess,
                    accessibilityIdentifier: "settings-link-privacy"
                ) {
                    PrivacyView()
                }

                settingsSectionLabel(NSLocalizedString("SETTINGS_SECTION_SUPPORT", comment: ""))
                settingsActionRow(
                    title: NSLocalizedString("SETTINGS_RATE_APP_TITLE", comment: ""),
                    subtitle: NSLocalizedString("SETTINGS_RATE_APP_SUBTITLE", comment: ""),
                    systemImage: "star",
                    tint: .appWarning
                ) {
                    requestAppRating()
                }
                ShareLink(item: String(
                    format: NSLocalizedString("SETTINGS_SHARE_APP_TEXT", comment: ""),
                    "https://crisisconnect.network"
                )) {
                    settingsRowLabel(
                        title: NSLocalizedString("SETTINGS_SHARE_APP_TITLE", comment: ""),
                        subtitle: NSLocalizedString("SETTINGS_SHARE_APP_SUBTITLE", comment: ""),
                        systemImage: "square.and.arrow.up",
                        tint: .appPrimary
                    )
                }
                .buttonStyle(.plain)
                settingsActionRow(
                    title: NSLocalizedString("SETTINGS_FEEDBACK_TITLE", comment: ""),
                    subtitle: NSLocalizedString("SETTINGS_FEEDBACK_SUBTITLE", comment: ""),
                    systemImage: "envelope",
                    tint: .appPrimary
                ) {
                    sendFeedbackEmail()
                }
                settingsActionRow(
                    title: NSLocalizedString("SETTINGS_HELP_TITLE", comment: ""),
                    subtitle: NSLocalizedString("SETTINGS_HELP_SUBTITLE", comment: ""),
                    systemImage: "questionmark.circle",
                    tint: .appSuccess
                ) {
                    if let url = URL(string: "https://crisisconnect.network/contact") {
                        UIApplication.shared.open(url)
                    }
                }

                Text(AppReleaseInfo.versionDetail)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.top, 8)
                    .accessibilityIdentifier("settings-version-footer")
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 18)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("settings-screen")
    }

    private func settingsSectionLabel(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.appTextSecondary)
            .padding(.horizontal, 4)
            .padding(.top, 4)
    }

    private var locationNeedsAttention: Bool {
        permissionsMonitor.locationStatus != .granted
    }

    private var bluetoothNeedsAttention: Bool {
        permissionsMonitor.bluetoothStatus != .granted || permissionsMonitor.bluetoothPoweredOff
    }

    /// Android's MissingPermissionsCard parity: an error-tinted nudge shown only when Bluetooth or
    /// Location isn't fully usable — the offline mesh silently dies otherwise. Deep-links to the
    /// system settings; the check refreshes on scenePhase .active when the user comes back.
    @ViewBuilder
    private var missingPermissionsCard: some View {
        if locationNeedsAttention || bluetoothNeedsAttention {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    AppIconBadge(systemName: "exclamationmark.triangle.fill", tint: .appDanger)

                    VStack(alignment: .leading, spacing: 4) {
                        Text("SETTINGS_PERMISSIONS_REMINDER_TITLE")
                            .font(.headline)
                        Text("SETTINGS_PERMISSIONS_REMINDER_MESSAGE")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 0)
                }

                VStack(alignment: .leading, spacing: 6) {
                    if bluetoothNeedsAttention {
                        permissionReminderRow(
                            icon: "dot.radiowaves.left.and.right",
                            title: NSLocalizedString("Bluetooth", comment: "")
                        )
                    }
                    if locationNeedsAttention {
                        permissionReminderRow(
                            icon: "location.fill",
                            title: NSLocalizedString("Location", comment: "")
                        )
                    }
                }

                Button {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                } label: {
                    Label(NSLocalizedString("Open Settings", comment: ""), systemImage: "gearshape.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle(fill: .appDanger))
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(Color.appDanger.opacity(0.10))
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appDanger.opacity(0.22), lineWidth: 1)
            )
            .accessibilityIdentifier("settings-missing-permissions-card")
        }
    }

    private func permissionReminderRow(icon: String, title: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appDanger)
                .frame(width: 20)
            Text(title)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.primary)
        }
    }

    /// A settings row that performs an action instead of navigating — same look as settingsLink,
    /// used by the Support & feedback section (rate/share/email/help).
    private func settingsActionRow(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            settingsRowLabel(title: title, subtitle: subtitle, systemImage: systemImage, tint: tint)
        }
        .buttonStyle(.plain)
    }

    private func settingsRowLabel(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color
    ) -> some View {
        HStack(spacing: 14) {
            AppIconBadge(systemName: systemImage, tint: tint)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .multilineTextAlignment(.leading)
            }

            Spacer()

            Image(systemName: "arrow.up.right")
                .font(.footnote.weight(.bold))
                .foregroundStyle(Color.appTextSecondary)
        }
        .appSurface(style: .regular, padding: 16)
    }

    /// The numeric App Store app ID (App Store Connect → App Information → "Apple ID"). Needed to
    /// deep-link the Rate row straight to the write-review page. UNSET placeholder for now — until a
    /// real ID is filled in, Rate falls back to StoreKit's requestReview at runtime.
    private static let appStoreAppID = "" // TODO(APP_STORE_APP_ID): set to the numeric App Store ID.

    /// Explicit Rate action: open the App Store's write-review page directly (Android's
    /// openPlayStoreListing parity) so the tap ALWAYS lands somewhere, instead of StoreKit's
    /// quota-throttled requestReview — that quota is usually already spent by the automatic
    /// onPositiveEvent prompt, so tapping Rate would silently do nothing. Falls back to the StoreKit
    /// card only while the numeric App Store ID hasn't been configured.
    private func requestAppRating() {
        let appID = Self.appStoreAppID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !appID.isEmpty else {
            requestStoreReviewPrompt()
            return
        }
        if let deepLink = URL(string: "itms-apps://apps.apple.com/app/id\(appID)?action=write-review") {
            UIApplication.shared.open(deepLink) { opened in
                guard !opened,
                      let webLink = URL(string: "https://apps.apple.com/app/id\(appID)?action=write-review")
                else { return }
                UIApplication.shared.open(webLink)
            }
        }
    }

    /// Best-effort StoreKit rating card — Apple's quota decides whether it actually appears. Kept for
    /// the automatic onPositiveEvent path and as the fallback until the App Store ID is set.
    private func requestStoreReviewPrompt() {
        guard let scene = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .first(where: { $0.activationState == .foregroundActive }) else { return }
        AppStore.requestReview(in: scene)
    }

    /// ACTION_SENDTO parity: a mailto: URL with the app/device context prefilled, so only mail
    /// apps handle it and the report lands with the version info attached.
    private func sendFeedbackEmail() {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        let subject = String(
            format: NSLocalizedString("SETTINGS_FEEDBACK_EMAIL_SUBJECT", comment: ""),
            "\(version) (\(build))"
        )
        let body = String(
            format: NSLocalizedString("SETTINGS_FEEDBACK_EMAIL_BODY", comment: ""),
            "\(version) (\(build))",
            UIDevice.current.model,
            UIDevice.current.systemVersion
        )
        var components = URLComponents()
        components.scheme = "mailto"
        components.path = "contact@crisisconnect.network"
        components.queryItems = [
            URLQueryItem(name: "subject", value: subject),
            URLQueryItem(name: "body", value: body),
        ]
        if let url = components.url {
            UIApplication.shared.open(url)
        }
    }

    private func settingsMetricCard(title: String, value: String, tint: Color) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)

            Text(value)
                .font(.headline.weight(.semibold))
                .foregroundStyle(tint)
                .lineLimit(1)
                .minimumScaleFactor(0.85)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
    }

    private var crisisSentinelToolLink: some View {
        NavigationLink(destination: LazyNavigationDestination {
            CrisisSentinelHomeView()
        }) {
            HStack(spacing: 14) {
                AppCustomIconBadge(tint: .appPrimary) {
                    CrisisSentinelSparklesIcon()
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text("CRISIS_SENTINEL_TITLE")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("CRISIS_SENTINEL_MAIN_ENTRY_PREVIEW")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("tool-link-crisis-sentinel")
    }

    private func toolLink<Destination: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        accessibilityIdentifier: String? = nil,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink(destination: LazyNavigationDestination {
            destination()
        }) {
            HStack(spacing: 14) {
                AppIconBadge(systemName: systemImage, tint: tint)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier ?? "")
    }

    private func settingsLink<Destination: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        accessibilityIdentifier: String? = nil,
        @ViewBuilder destination: @escaping () -> Destination
    ) -> some View {
        NavigationLink(destination: LazyNavigationDestination {
            destination()
        }) {
            HStack(spacing: 14) {
                AppIconBadge(systemName: systemImage, tint: tint)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(accessibilityIdentifier ?? "")
    }

    private var showRescueTools: Bool {
        RescueRoleAccess.isAuthorized(rescueRole)
    }

    private var hasDetectTools: Bool {
        PlatformRuntime.supportsSensorsDashboard
            || PlatformRuntime.supportsCompass
            || PlatformRuntime.supportsNightVision
            || PlatformRuntime.supportsMetalDetector
    }
}

private enum ContactAddInputSource {
    case qr
    case manual
}

private enum ContactOnboardingStage: Equatable {
    case idle
    case searching
    case pairing
    case saving
    case alreadyRegistered
    case completed

    var isBlocking: Bool {
        switch self {
        case .searching, .pairing, .saving:
            return true
        case .idle, .alreadyRegistered, .completed:
            return false
        }
    }

    var isVerificationProgress: Bool {
        switch self {
        case .searching, .pairing:
            return true
        case .idle, .saving, .alreadyRegistered, .completed:
            return false
        }
    }

    var titleKey: String {
        switch self {
        case .idle:
            return ""
        case .searching:
            return "CONTACT_ONBOARDING_SEARCHING_TITLE"
        case .pairing:
            return "CONTACT_ONBOARDING_PAIRING_TITLE"
        case .saving:
            return "CONTACT_ONBOARDING_SAVING_TITLE"
        case .alreadyRegistered:
            return "CONTACT_ONBOARDING_ALREADY_REGISTERED_TITLE"
        case .completed:
            return "CONTACT_ONBOARDING_COMPLETED_TITLE"
        }
    }

    var messageKey: String {
        switch self {
        case .idle:
            return ""
        case .searching:
            return "CONTACT_ONBOARDING_SEARCHING_MESSAGE"
        case .pairing:
            return "CONTACT_ONBOARDING_PAIRING_MESSAGE"
        case .saving:
            return "CONTACT_ONBOARDING_SAVING_MESSAGE"
        case .alreadyRegistered:
            return "CONTACT_ONBOARDING_ALREADY_REGISTERED_MESSAGE"
        case .completed:
            return "CONTACT_ONBOARDING_COMPLETED_MESSAGE"
        }
    }

    var systemImage: String {
        switch self {
        case .idle:
            return ""
        case .searching:
            return "dot.radiowaves.left.and.right"
        case .pairing:
            return "lock.shield"
        case .saving:
            return "square.and.arrow.down"
        case .alreadyRegistered:
            return "person.crop.circle.badge.checkmark"
        case .completed:
            return "checkmark.circle.fill"
        }
    }

    var tint: Color {
        switch self {
        case .idle:
            return .clear
        case .searching:
            return .appPrimary
        case .pairing:
            return .orange
        case .saving:
            return .blue
        case .alreadyRegistered:
            return .green
        case .completed:
            return .green
        }
    }
}

private struct ContactOnboardingStatusView: View {
    let stage: ContactOnboardingStage
    let contactName: String?
    var onCancel: (() -> Void)? = nil

    var body: some View {
        if stage != .idle {
            HStack(alignment: .top, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(stage.tint.opacity(0.14))
                        .frame(width: 38, height: 38)
                    if stage.isBlocking {
                        ProgressView()
                            .tint(stage.tint)
                    } else {
                        Image(systemName: stage.systemImage)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(stage.tint)
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(LocalizedStringKey(stage.titleKey))
                        .font(.headline)
                    Text(LocalizedStringKey(stage.messageKey))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    if let contactName, !contactName.isEmpty {
                        Text(contactName)
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(stage.tint)
                    }
                }

                Spacer(minLength: 8)

                if let onCancel, stage.isBlocking {
                    Button("Cancel", action: onCancel)
                        .font(.footnote.weight(.semibold))
                }
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(stage.tint.opacity(0.16), lineWidth: 1)
            )
        }
    }
}

private struct NewContactView: View {
    var onQrSharePaired: () -> Void = {}
    /// Android NewChat parity: agency members also get "Add from agency" here — the kurum
    /// directory moved off the messages home into this screen.
    var showAddFromAgency: Bool = false
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Query(sort: \Profile.createdAt, order: .reverse) private var profiles: [Profile]
    @ObservedObject private var  contactStore = ContactStore.shared
    @StateObject private var verifier = ContactBroadcastVerifier()
    @State private var showScanner = false
    @State private var alertTitleKey = ""
    @State private var alertMessageKey = ""
    @State private var showAlert = false
    @State private var activeChatId: UUID?
    @State private var isQrSharingActive = false
    @State private var activeQrCodeValue = ""
    @State private var activeQrImage: UIImage?
    @State private var activeQrSessionCode = ""
    @State private var activeQrAesKey = ""
    @State private var preparedQrCodeValue = ""
    @State private var preparedQrImage: UIImage?
    @State private var preparedQrSessionCode = ""
    @State private var preparedQrAesKey = ""
    @State private var preparedQrDisplayName = ""
    @State private var preparedQrShareId = ""
    @State private var qrPreparationToken = UUID()
    @State private var qrShareId = P2pBleProtocol.randomShareId()
    @State private var showManualEntry = false
    @State private var manualDisplayName = ""
    @State private var manualSessionCode = ""
    @State private var manualAesKey = ""
    @State private var manualShareId = ""
    @State private var onboardingSource: ContactAddInputSource?
    @State private var onboardingStage: ContactOnboardingStage = .idle
    @State private var onboardingResolvedName = ""

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Add an emergency contact")
                        .font(.headline)
                    Text("Share your QR code or connect manually.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section {
                if isQrSharingActive {
                    QRCodeCard(image: activeQrImage, size: qrSize)
                        .listRowBackground(Color.clear)
                        .listRowInsets(qrInsets)
                        .listRowSeparator(.hidden)

                    Button {
                        startQrSharing(refreshShareId: true)
                    } label: {
                        Label("NEW_CONTACT_REFRESH_QR", systemImage: "arrow.clockwise")
                    }

                    Button(role: .destructive) {
                        stopQrSharing()
                    } label: {
                        Label("NEW_CONTACT_STOP_SHARING", systemImage: "xmark.circle")
                    }
                } else {
                    Button {
                        startQrSharing(refreshShareId: false)
                    } label: {
                        Label("NEW_CONTACT_SHOW_QR", systemImage: "qrcode")
                    }
                }
            } header: {
                Text("Your QR Code")
            } footer: {
                Text("Your QR code is only visible when you choose to share it.")
            }

            Section("NEW_CONTACT_SECTION_CONNECT") {
                Button {
                    showScanner = true
                } label: {
                    Label("Scan QR", systemImage: "qrcode.viewfinder")
                }
                .disabled(onboardingStage.isBlocking)
                Button {
                    prepareManualEntry()
                } label: {
                    Label("Connect Manually", systemImage: "link")
                }
                .disabled(onboardingStage.isBlocking)
                // A pushed page, not a sheet: the flow continues into the newly added chat, and a
                // sheet-in-a-sheet navigation felt detached from the rest of the contacts area.
                NavigationLink {
                    LazyNavigationDestination {
                        AddFromContactsView()
                    }
                } label: {
                    Label("NEW_CONTACT_ADD_FROM_PHONE", systemImage: "person.crop.circle.badge.plus")
                }
                .disabled(onboardingStage.isBlocking)
                if showAddFromAgency {
                    NavigationLink {
                        LazyNavigationDestination {
                            AuthorityChannelsListView()
                        }
                    } label: {
                        Label("NEW_CONTACT_ADD_FROM_AGENCY", systemImage: "building.2")
                    }
                    .disabled(onboardingStage.isBlocking)
                }

                if onboardingStage != .idle {
                    ContactOnboardingStatusView(
                        stage: onboardingStage,
                        contactName: onboardingResolvedName.nilIfEmpty,
                        onCancel: {
                            cancelOnboarding(dismissManualEntry: false)
                        }
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)

        .onAppear {
            DispatchQueue.main.async {
                prepareQrShare(displayName: localDisplayName, shareId: qrShareId)
            }
        }
        .onDisappear {
            qrPreparationToken = UUID()
            stopQrSharing(prepareNext: false)
            if onboardingStage.isBlocking {
                verifier.cancel()
            }
            resetOnboardingState()
        }
        .onChange(of: localDisplayName) { _, newValue in
            if isQrSharingActive {
                refreshActiveQrShare(displayName: newValue)
            }
            prepareQrShare(displayName: newValue, shareId: qrShareId)
        }
        .onChange(of: showManualEntry) { _, isPresented in
            guard !isPresented, onboardingSource == .manual else { return }
            if onboardingStage.isBlocking {
                cancelOnboarding(dismissManualEntry: false)
            } else {
                resetOnboardingState()
            }
        }
        .onReceive(verifier.$progress.removeDuplicates()) { progress in
            handleVerificationProgress(progress)
        }
        .onReceive(
            NotificationCenter.default.publisher(for: .p2pShareDidAddContact)
                .receive(on: RunLoop.main)
        ) { _ in
            guard isQrSharingActive else { return }
            stopQrSharing(prepareNext: false)
            onQrSharePaired()
        }
        .navigationDestination(
            isPresented: Binding(
                get: { activeChatId != nil },
                set: { isPresented in
                    if !isPresented {
                        activeChatId = nil
                    }
                }
            )
        ) {
            if let sessionId = activeChatId {
                LazyNavigationDestination {
                    SOSChatDetailScreen(sessionId: sessionId)
                }
            }
        }
        .sheet(isPresented: $showScanner) {
            NavigationStack {
                QRCodeScannerView { value in
                    showScanner = false
                    handleScan(value)
                } onError: { errorKey in
                    showScanner = false
                    handleScannerError(errorKey)
                }
                .ignoresSafeArea()
                .navigationTitle("Scan QR")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button("COMMON_CLOSE") { showScanner = false }
                    }
                }
            }
        }
        .sheet(isPresented: $showManualEntry) {
            ManualContactEntrySheet(
                displayName: $manualDisplayName,
                sessionCode: $manualSessionCode,
                aesKey: $manualAesKey,
                shareId: $manualShareId,
                stage: onboardingSource == .manual ? onboardingStage : .idle,
                resolvedContactName: onboardingResolvedName.nilIfEmpty,
                onCancel: {
                    if onboardingStage.isBlocking {
                        cancelOnboarding(dismissManualEntry: true)
                    } else {
                        resetOnboardingState()
                        showManualEntry = false
                    }
                },
                onConnect: { displayName, sessionCode, aesKey, shareId in
                    handleManualConnect(
                        displayName: displayName,
                        sessionCode: sessionCode,
                        aesKey: aesKey,
                        shareId: shareId
                    )
                }
            )
        }
        .alert(LocalizedStringKey(alertTitleKey), isPresented: $showAlert) {
            Button("COMMON_OK") {}
        } message: {
            Text(LocalizedStringKey(alertMessageKey))
        }
    }

    private var qrSize: CGFloat {
        horizontalSizeClass == .regular ? 230 : 190
    }

    private var qrInsets: EdgeInsets {
        EdgeInsets(top: 8, leading: 16, bottom: 12, trailing: 16)
    }

    private var localSessionCode: String {
        SecureLocalStore.shared.getOrCreateP2pSessionCode()
    }

    private var localAesKey: String {
        SecureLocalStore.shared.getOrCreateAesKeyBase64()
    }

    private var localDisplayName: String {
        ProfileMetadataStore.preferredDisplayName() ?? "Crisis Connect"
    }

    private func startQrSharing(refreshShareId: Bool) {
        let displayName = localDisplayName
        let targetShareId: String
        if refreshShareId {
            targetShareId = P2pBleProtocol.randomShareId()
            qrShareId = targetShareId
        } else {
            targetShareId = qrShareId
        }
        if !activatePreparedQrIfAvailable(displayName: displayName, shareId: targetShareId) {
            activeQrSessionCode = localSessionCode
            activeQrAesKey = localAesKey
            let qrCodeValue = makeQrCodeValue(
                displayName: displayName,
                shareId: targetShareId,
                sessionCode: activeQrSessionCode,
                aesKey: activeQrAesKey
            )
            activeQrCodeValue = qrCodeValue
            activeQrImage = QRCodeRenderer.makeImage(value: qrCodeValue)
        }
        isQrSharingActive = true
        ContactBroadcastManager.shared.startSharing(
            shareId: targetShareId,
            sessionCode: activeQrSessionCode,
            displayName: displayName,
            aesKeyBase64: activeQrAesKey
        )
    }

    private func refreshActiveQrShare(displayName: String) {
        guard isQrSharingActive else { return }
        let qrCodeValue = makeQrCodeValue(
            displayName: displayName,
            shareId: qrShareId,
            sessionCode: activeQrSessionCode,
            aesKey: activeQrAesKey
        )
        activeQrCodeValue = qrCodeValue
        activeQrImage = QRCodeRenderer.makeImage(value: qrCodeValue)
        ContactBroadcastManager.shared.startSharing(
            shareId: qrShareId,
            sessionCode: activeQrSessionCode,
            displayName: displayName,
            aesKeyBase64: activeQrAesKey
        )
    }

    private func stopQrSharing(prepareNext: Bool = true) {
        isQrSharingActive = false
        activeQrCodeValue = ""
        activeQrImage = nil
        activeQrSessionCode = ""
        activeQrAesKey = ""
        ContactBroadcastManager.shared.stopSharing()
        guard prepareNext else { return }
        let nextShareId = P2pBleProtocol.randomShareId()
        qrShareId = nextShareId
        prepareQrShare(displayName: localDisplayName, shareId: nextShareId)
    }

    private func activatePreparedQrIfAvailable(displayName: String, shareId: String) -> Bool {
        guard
            preparedQrShareId == shareId,
            preparedQrDisplayName == displayName,
            !preparedQrSessionCode.isEmpty,
            !preparedQrAesKey.isEmpty,
            !preparedQrCodeValue.isEmpty
        else {
            return false
        }
        activeQrSessionCode = preparedQrSessionCode
        activeQrAesKey = preparedQrAesKey
        activeQrCodeValue = preparedQrCodeValue
        activeQrImage = preparedQrImage
        return true
    }

    private func prepareQrShare(displayName: String, shareId: String) {
        let preparationToken = UUID()
        qrPreparationToken = preparationToken
        let targetDisplayName = displayName
        DispatchQueue.global(qos: .userInitiated).async {
            let sessionCode = SecureLocalStore.shared.getOrCreateP2pSessionCode()
            let aesKey = SecureLocalStore.shared.getOrCreateAesKeyBase64()
            let qrCodeValue = makeQrCodeValue(
                displayName: targetDisplayName,
                shareId: shareId,
                sessionCode: sessionCode,
                aesKey: aesKey
            )
            let qrImage = QRCodeRenderer.makeImage(value: qrCodeValue)
            DispatchQueue.main.async {
                guard qrPreparationToken == preparationToken else { return }
                preparedQrSessionCode = sessionCode
                preparedQrAesKey = aesKey
                preparedQrCodeValue = qrCodeValue
                preparedQrImage = qrImage
                preparedQrDisplayName = targetDisplayName
                preparedQrShareId = shareId
            }
        }
    }

    private func makeQrCodeValue(
        displayName: String,
        shareId: String,
        sessionCode: String,
        aesKey: String
    ) -> String {
        // Carry our internet-messaging identity so the scanner can reach us online later. Only the
        // public key is shared; the private key stays in the Secure Enclave.
        let peerUid = Auth.auth().currentUser?.uid
        let peerPublicKey = try? MessagingIdentity.shared.publicKeyBase64()
        let payload = ContactQRPayload(
            code: sessionCode,
            key: aesKey,
            platform: "ios",
            bleFallbackCapable: true,
            name: displayName,
            shareId: shareId,
            peerUid: peerUid,
            peerPublicKey: peerPublicKey
        )
        return payload.encodedString() ?? ""
    }

    private func handleScan(_ value: String) {
        guard let payload = ContactQRPayload.decode(from: value) else {
            MessagingDiagLog.log("qr scan: DECODE FAILED (len=\(value.count))")
            showAlert(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
            return
        }
        guard payload.v == ContactQRPayload.currentVersion else {
            MessagingDiagLog.log("qr scan: version mismatch v=\(payload.v)")
            showAlert(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
            return
        }
        MessagingDiagLog.log(
            "qr scan: decoded platform=\(payload.platform) hasPeerUid=\(payload.peerUid != nil) hasPeerKey=\(payload.peerPublicKey != nil)"
        )
        processContactPayload(payload, fallbackDisplayName: "Contact", source: .qr)
    }

    private func handleManualConnect(
        displayName: String,
        sessionCode: String,
        aesKey: String,
        shareId: String
    ) {
        let trimmedSessionCode = sessionCode.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        let trimmedAesKey = aesKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let payload = ContactQRPayload(
            code: trimmedSessionCode,
            key: trimmedAesKey,
            platform: "ios",
            bleFallbackCapable: true,
            name: displayName,
            shareId: shareId
        )

        guard !trimmedSessionCode.isEmpty, isValidAESKey(trimmedAesKey) else {
            showAlert(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
            return
        }

        processContactPayload(
            payload,
            fallbackDisplayName: displayName.nilIfEmpty ?? "Contact",
            source: .manual
        )
    }

    private func processContactPayload(
        _ payload: ContactQRPayload,
        fallbackDisplayName: String,
        source: ContactAddInputSource
    ) {
        let remoteSessionCode = payload.code.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (payload.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let key = payload.key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !remoteSessionCode.isEmpty,
              isValidAESKey(key) else {
            showAlert(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
            return
        }

        let resolvedName = name.isEmpty ? fallbackDisplayName : name

        guard remoteSessionCode.caseInsensitiveCompare(localSessionCode) != .orderedSame else {
            showAlert(title: "CONTACT_SCAN_SELF_TITLE", message: "CONTACT_SCAN_SELF_MESSAGE")
            return
        }

        onboardingSource = source
        onboardingResolvedName = resolvedName
        onboardingStage = .searching

        MessagingDiagLog.log("qr add: starting BLE verify for \(resolvedName)")
        verifier.verify(payload: payload, localDisplayName: localDisplayName) { result in
            switch result {
            case .verified(let result):
                onboardingResolvedName = result.displayName ?? resolvedName
                onboardingStage = .saving
                let canonicalSessionCode = canonicalBleSessionCode(
                    remoteDeviceId: result.remoteDeviceId,
                    fallbackSessionCode: result.sessionCode,
                    fallbackAddress: result.lastKnownBleAddress
                )
                // Prefer a BLE-identity match; else fall back to a contact already stamped with this
                // peer's uid (added earlier via the directory or auto-created from an incoming
                // message) so re-scanning its QR heals that thread instead of spawning a duplicate.
                // Mirrors Android saveInternetContactFromQr's findStoredContactForQr ?? getContactByPeerUid.
                let existingRecord = contactStore.existingBleContact(
                    sessionCode: canonicalSessionCode,
                    remoteSessionCode: remoteSessionCode,
                    bleShareId: result.shareId,
                    lastKnownBleAddress: result.lastKnownBleAddress,
                    remoteDeviceId: result.remoteDeviceId,
                    verifiedIdentityKey: result.remoteDeviceId
                ) ?? (payload.peerUid?.nilIfEmpty).flatMap { contactStore.contactForPeerUid($0) }
                // Route the upsert into the existing thread's session code so upsertBleContact merges
                // in place (preserving that record's id/thread) instead of inserting a fresh row.
                let upsertSessionCode = existingRecord?.sessionCode ?? canonicalSessionCode
                let record = contactStore.upsertBleContact(
                    name: result.displayName ?? resolvedName,
                    sessionCode: upsertSessionCode,
                    aesKeyBase64: key,
                    isVerified: true,
                    verifiedIdentityKey: result.remoteDeviceId,
                    verifiedAt: Date(),
                    remoteSessionCode: remoteSessionCode,
                    remotePlatform: ContactRemotePlatform.normalize(
                        payload.platform.isEmpty ? result.platform : payload.platform
                    ),
                    bleShareId: result.shareId,
                    lastKnownBleAddress: result.lastKnownBleAddress,
                    remoteDeviceId: result.remoteDeviceId,
                    peerUid: payload.peerUid,
                    peerPublicKey: payload.peerPublicKey,
                    analyticsSource: "qr"
                )
                MessagingDiagLog.log(
                    "qr add (BLE verified): peerUid=\(payload.peerUid?.prefix(8) ?? "nil") " +
                        "peerKey=\(payload.peerPublicKey != nil) supportsInternet=\(record.supportsInternet)"
                )
                announceOurIdentity(to: record)
                SOSChatStore.shared.ensureSession(
                    id: record.id,
                    displayName: record.name,
                    role: .unknown,
                    isVerified: true
                )
                SOSChatStore.shared.updateIdentity(
                    id: record.id,
                    displayName: record.name,
                    role: .unknown,
                    isVerified: true,
                    avatarBase64: result.avatarBase64
                )
                finishOnboarding(with: record, alreadyRegistered: existingRecord != nil)
            case .failed(.notFound):
                if saveInternetContactFromPayload(payload, fallbackName: resolvedName) { return }
                resetOnboardingState()
                showAlert(title: "CONTACT_SCAN_NOT_FOUND_TITLE", message: "CONTACT_SCAN_NOT_FOUND_MESSAGE")
            case .failed(.bluetoothPoweredOff):
                if saveInternetContactFromPayload(payload, fallbackName: resolvedName) { return }
                resetOnboardingState()
                showAlert(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_DISABLED_MESSAGE")
            case .failed(.bluetoothUnauthorized):
                if saveInternetContactFromPayload(payload, fallbackName: resolvedName) { return }
                resetOnboardingState()
                showAlert(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_UNAUTHORIZED_MESSAGE")
            case .failed(.bluetoothUnsupported):
                if saveInternetContactFromPayload(payload, fallbackName: resolvedName) { return }
                resetOnboardingState()
                showAlert(title: "CONTACT_BLUETOOTH_TITLE", message: "CONTACT_BLUETOOTH_UNSUPPORTED_MESSAGE")
            }
        }
    }

    /// Internet-first fallback (Android parity, connectUsingQr.finishFailure): the Bluetooth pairing
    /// couldn't complete (peer out of range / BT off / unauthorized), but if the QR carries the
    /// peer's internet identity we STILL register an internet-capable contact so the chat and calls
    /// work online — no Bluetooth required. Returns true when it saved one (caller stops erroring).
    @discardableResult
    private func saveInternetContactFromPayload(_ payload: ContactQRPayload, fallbackName: String) -> Bool {
        let peerUid = (payload.peerUid ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let peerKey = (payload.peerPublicKey ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let key = payload.key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !peerUid.isEmpty, !peerKey.isEmpty, isValidAESKey(key) else { return false }
        let remoteSessionCode = payload.code.trimmingCharacters(in: .whitespacesAndNewlines)
        let name = (payload.name ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName = name.isEmpty ? fallbackName : name

        // Reuse an existing thread if we already have this peer (added via the directory or
        // auto-created from an incoming message) so re-scanning its QR heals that contact instead of
        // forking a second thread. Mirrors Android saveInternetContactFromQr's getContactByPeerUid reuse.
        let existingByPeerUid = contactStore.contactForPeerUid(peerUid)
        // No BLE handshake happened, so there is no verified device id/address — key the contact by
        // the existing thread's session code when we found one, else by its remote session code
        // (always present in a QR), else by the peer uid. peerUid + peerPublicKey make it
        // internet-capable (supportsInternet). Routing through the existing session code makes
        // upsertBleContact merge the QR fields into that record in place rather than insert a duplicate.
        let canonicalSessionCode = existingByPeerUid?.sessionCode
            ?? (remoteSessionCode.isEmpty ? peerUid : remoteSessionCode)
        let record = contactStore.upsertBleContact(
            name: resolvedName,
            sessionCode: canonicalSessionCode,
            aesKeyBase64: key,
            isVerified: false,
            remoteSessionCode: remoteSessionCode.nilIfEmpty,
            remotePlatform: ContactRemotePlatform.normalize(payload.platform),
            bleShareId: payload.shareId,
            lastKnownBleAddress: nil,
            remoteDeviceId: nil,
            peerUid: peerUid,
            peerPublicKey: peerKey,
            analyticsSource: "qr_internet"
        )
        SOSChatStore.shared.ensureSession(
            id: record.id,
            displayName: record.name,
            role: .unknown,
            isVerified: false
        )
        MessagingDiagLog.log("qr add: BLE verify failed but saved internet contact peer=\(peerUid.prefix(8))")
        announceOurIdentity(to: record)
        finishOnboarding(with: record, alreadyRegistered: existingByPeerUid != nil)
        return true
    }

    /// After a QR pairing saves an internet-capable contact, announce OUR internet identity to the
    /// peer over the relay so their side heals its BLE-only reciprocal contact (Android parity). The
    /// MTU-independent half of bidirectional QR pairing — the BLE client-hello can drop the identity
    /// on a small Android↔iOS MTU, so we never rely on it alone. Best-effort.
    private func announceOurIdentity(to record: ContactRecord) {
        guard record.supportsInternet else { return }
        let mySessionCode = localSessionCode
        let myName = ProfileMetadataStore.preferredDisplayName()
        Task {
            _ = await InternetChatTransport.shared.sendIdentityAnnounce(
                contact: record, mySessionCode: mySessionCode, myName: myName
            )
        }
    }

    private func prepareManualEntry() {
        resetOnboardingState()
        manualDisplayName = ""
        manualSessionCode = ""
        manualAesKey = ""
        manualShareId = ""
        showManualEntry = true
    }

    private func handleScannerError(_ errorKey: String) {
        switch errorKey {
        case "CAMERA_PERMISSION_DENIED", "CAMERA_UNAVAILABLE":
            showAlert(title: "CONTACT_CAMERA_TITLE", message: "CONTACT_CAMERA_MESSAGE")
        default:
            showAlert(title: "CONTACT_SCAN_INVALID_TITLE", message: "CONTACT_SCAN_INVALID_MESSAGE")
        }
    }

    private func showAlert(title: String, message: String) {
        alertTitleKey = title
        alertMessageKey = message
        showAlert = true
    }

    private func handleVerificationProgress(_ progress: ContactVerificationProgress) {
        switch progress {
        case .idle:
            if onboardingStage.isVerificationProgress {
                onboardingStage = .idle
            }
        case .searching:
            if onboardingSource != nil, onboardingStage != .saving {
                onboardingStage = .searching
            }
        case .pairing:
            if onboardingSource != nil, onboardingStage != .saving {
                onboardingStage = .pairing
            }
        }
    }

    private func finishOnboarding(with record: ContactRecord, alreadyRegistered: Bool) {
        onboardingResolvedName = record.name
        let terminalStage: ContactOnboardingStage = alreadyRegistered ? .alreadyRegistered : .completed
        onboardingStage = terminalStage
        let source = onboardingSource
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) {
            guard onboardingStage == terminalStage else { return }
            if source == .manual {
                showManualEntry = false
            }
            let targetChatId = record.id
            resetOnboardingState()
            activeChatId = targetChatId
        }
    }

    private func cancelOnboarding(dismissManualEntry: Bool) {
        verifier.cancel()
        resetOnboardingState()
        if dismissManualEntry {
            showManualEntry = false
        }
    }

    private func resetOnboardingState() {
        onboardingSource = nil
        onboardingStage = .idle
        onboardingResolvedName = ""
    }

    private func isValidAESKey(_ base64: String) -> Bool {
        guard let data = Data(base64Encoded: base64, options: [.ignoreUnknownCharacters]) else { return false }
        return data.count == 32
    }

    private func canonicalBleSessionCode(
        remoteDeviceId: String,
        fallbackSessionCode: String,
        fallbackAddress: String
    ) -> String {
        let raw = remoteDeviceId.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? fallbackSessionCode.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
            ?? fallbackAddress.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.lowercased().hasPrefix("ble:") {
            return raw
        }
        return "ble:\(raw)"
    }
}

private struct ManualContactEntrySheet: View {
    @Binding var displayName: String
    @Binding var sessionCode: String
    @Binding var aesKey: String
    @Binding var shareId: String
    let stage: ContactOnboardingStage
    let resolvedContactName: String?
    let onCancel: () -> Void
    let onConnect: (_ displayName: String, _ sessionCode: String, _ aesKey: String, _ shareId: String) -> Void

    var body: some View {
        NavigationStack {
            Form {
                if stage != .idle {
                    Section {
                        ContactOnboardingStatusView(
                            stage: stage,
                            contactName: resolvedContactName,
                            onCancel: onCancel
                        )
                    }
                    .listRowBackground(Color.clear)
                }

                Section {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("MANUAL_CONNECT_HEADER")
                            .font(.headline)
                        Text("MANUAL_CONNECT_HEADER_DETAIL")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 4)
                }

                Section("MANUAL_CONNECT_SECTION_IDENTITY") {
                    TextField("MANUAL_CONNECT_DISPLAY_NAME", text: $displayName)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                }
                .disabled(stage.isBlocking)

                Section("MANUAL_CONNECT_SECTION_CONNECTION") {
                    TextField("MANUAL_CONNECT_SESSION_CODE", text: $sessionCode)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()

                    TextField("MANUAL_CONNECT_AES_KEY", text: $aesKey)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    TextField("MANUAL_CONNECT_SHARE_ID", text: $shareId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                .disabled(stage.isBlocking)

                Section {
                    if stage == .idle {
                        Text("MANUAL_CONNECT_HINT")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.appBackground)
            .navigationTitle("MANUAL_CONNECT_TITLE")
            .navigationBarTitleDisplayMode(.inline)
            .interactiveDismissDisabled(stage.isBlocking)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("COMMON_CANCEL", action: onCancel)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("MANUAL_CONNECT_ACTION") {
                        onConnect(displayName, sessionCode, aesKey, shareId)
                    }
                    .disabled(stage != .idle)
                }
            }
        }
    }
}

private struct QRCodeCard: View {
    let image: UIImage?
    let size: CGFloat

    var body: some View {
        VStack(spacing: 12) {
            QRCodeImageView(image: image, logoSize: max(34, size * 0.18))
                .frame(width: size, height: size)
                .padding(.bottom, 6)
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
    }
}

private struct QRCodeImageView: View {
    let image: UIImage?
    let logoSize: CGFloat

    var body: some View {
        ZStack {
            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .interpolation(.none)
                        .scaledToFit()
                        .padding(12)
                } else {
                    Image(systemName: "qrcode")
                        .resizable()
                        .scaledToFit()
                        .foregroundStyle(.secondary)
                        .padding(12)
                }
            }

            if image != nil, let logoUIImage {
                Image(uiImage: logoUIImage)
                    .resizable()
                    .scaledToFit()
                    .frame(width: logoSize, height: logoSize)
                    .accessibilityHidden(true)
            }
        }
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Color.black.opacity(0.08), lineWidth: 1)
        )
    }

    private var logoUIImage: UIImage? {
        let candidates = ["AppLogo", "DCS Logo", "DCS_Logo", "DCSLogo", "dcslogo", "logo", "Logo"]
        for name in candidates {
            if let image = UIImage(named: name) {
                return image
            }
        }
        return nil
    }

    private func applyTint(to image: CIImage) -> CIImage {
        let colorFilter = CIFilter.falseColor()
        colorFilter.inputImage = image
        colorFilter.color0 = CIColor(red: 4.0 / 255.0, green: 44.0 / 255.0, blue: 67.0 / 255.0)
        colorFilter.color1 = CIColor(color: .white)
        return colorFilter.outputImage ?? image
    }
}

private enum QRCodeRenderer {
    private static let context = CIContext(options: [.useSoftwareRenderer: false])

    static func makeImage(value: String) -> UIImage? {
        guard !value.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.setValue(Data(value.utf8), forKey: "inputMessage")
        filter.setValue("H", forKey: "inputCorrectionLevel")
        guard let outputImage = filter.outputImage else { return nil }

        let colorFilter = CIFilter.falseColor()
        colorFilter.inputImage = outputImage
        colorFilter.color0 = CIColor(red: 4.0 / 255.0, green: 44.0 / 255.0, blue: 67.0 / 255.0)
        colorFilter.color1 = CIColor(color: .white)
        let tintedImage = colorFilter.outputImage ?? outputImage
        let scaledImage = tintedImage.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cgImage = context.createCGImage(scaledImage, from: scaledImage.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}

private extension String {
    var nilIfEmpty: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}

#Preview {
    ContentView()
        .environmentObject(AppSettingsViewModel())
}
