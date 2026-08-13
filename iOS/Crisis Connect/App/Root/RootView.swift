//
//  RootView.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import SwiftUI
import SwiftData
import PhotosUI
import UIKit

struct RootView: View {
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding: Bool = false

    var body: some View {
        ZStack {
            if AppStoreScreenshotSupport.isChatSceneEnabled {
                AppStoreScreenshotChatRootView()
                    .transition(.opacity)
            } else if AppStoreScreenshotSupport.isMessagesHomeSceneEnabled || AppStoreScreenshotSupport.isMessagesSearchSceneEnabled {
                AppStoreScreenshotMessagesHomeRootView()
                    .transition(.opacity)
            } else if AppStoreScreenshotSupport.isOnboardingBackgroundSceneEnabled {
                AppStoreScreenshotOnboardingBackgroundRootView()
                    .transition(.opacity)
            } else if !hasCompletedOnboarding {
                OnboardingView(hasCompletedOnboarding: $hasCompletedOnboarding)
                    .transition(.opacity)
            } else {
                ContentView()
                    .transition(.opacity)
            }
        }
    }
}

private struct AppStoreScreenshotMessagesHomeRootView: View {
    init() {
        _ = AppStoreScreenshotSupport.prepareChatScenarioIfNeeded()
    }

    var body: some View {
        ContentView()
    }
}

private struct AppStoreScreenshotChatRootView: View {
    private let sessionId: UUID
    @State private var showsMessagesHome = false

    init() {
        sessionId = AppStoreScreenshotSupport.prepareChatScenarioIfNeeded()
    }

    var body: some View {
        Group {
            if showsMessagesHome {
                AppStoreScreenshotMessagesHomeRootView()
            } else {
                NavigationStack {
                    SOSChatDetailScreen(
                        sessionId: sessionId,
                        onBack: {
                            showsMessagesHome = true
                        }
                    )
                }
            }
        }
    }
}

private struct AppStoreScreenshotOnboardingBackgroundRootView: View {
    @State private var hasCompletedOnboarding: Bool = false

    var body: some View {
        OnboardingView(
            hasCompletedOnboarding: $hasCompletedOnboarding,
            startAtBackgroundGuidance: true,
            prefilledName: "Maya Chen",
            preacceptedDocuments: true
        )
    }
}

private struct OnboardingView: View {
    // Android welcome parity: intro → name → internet (skippable phone verification) →
    // permissions → background guidance → privacy (documents + finish).
    private enum Step: Int, CaseIterable {
        case intro
        case identity
        case internet
        case permissions
        case backgroundGuidance
        case privacy
    }

    @Environment(\.modelContext) private var context
    @Query(sort: \Profile.createdAt, order: .reverse) private var profiles: [Profile]
    @Binding var hasCompletedOnboarding: Bool

    @State private var fullName: String = ""
    @State private var acceptedTerms: Bool = false
    @State private var acceptedPrivacy: Bool = false
    // Android welcome parity (WelcomeAvatarPicker): an optional profile photo picked here carries
    // into Profile via the same persistence path (Profile.avatarImageData + metadata + upload).
    @State private var avatarItem: PhotosPickerItem?
    @State private var avatarImage: UIImage?
    @State private var step: Step = .intro
    @StateObject private var permissions = OnboardingPermissionsCoordinator()
    @StateObject private var phoneVerification = OnboardingPhoneVerification()
    @FocusState private var isNameFieldFocused: Bool
    // Re-shown from Messages/Rescue tips: only the guidance step, finishing right after it.
    private let isStandaloneGuidance: Bool

    private let termsURL = URL(string: "https://crisisconnect.network/terms")!
    private let privacyURL = URL(string: "https://crisisconnect.network/privacy")!

    init(
        hasCompletedOnboarding: Binding<Bool>,
        startAtBackgroundGuidance: Bool = false,
        prefilledName: String = "",
        preacceptedDocuments: Bool = false
    ) {
        _hasCompletedOnboarding = hasCompletedOnboarding
        _fullName = State(initialValue: prefilledName)
        _acceptedTerms = State(initialValue: preacceptedDocuments)
        _acceptedPrivacy = State(initialValue: preacceptedDocuments)
        _step = State(initialValue: startAtBackgroundGuidance ? .backgroundGuidance : .intro)
        isStandaloneGuidance = startAtBackgroundGuidance
    }

    private var trimmedName: String {
        fullName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canFinish: Bool {
        acceptedTerms && acceptedPrivacy && !trimmedName.isEmpty
    }

    private var primaryButtonTitle: String {
        switch step {
        case .intro:
            return NSLocalizedString("Next", comment: "")
        case .identity:
            return NSLocalizedString("Next", comment: "")
        case .internet:
            // Hoisted single-action flow (Android parity): Skip → Send code → Verify → Continue.
            if phoneVerification.verifiedPhone != nil {
                return NSLocalizedString("Continue", comment: "")
            }
            if phoneVerification.codeSent {
                return NSLocalizedString("ONBOARDING_INTERNET_VERIFY", comment: "")
            }
            if phoneVerification.hasNumberInput {
                return NSLocalizedString("ONBOARDING_INTERNET_SEND_CODE", comment: "")
            }
            return NSLocalizedString("ONBOARDING_INTERNET_SKIP", comment: "")
        case .permissions:
            return NSLocalizedString("Continue", comment: "")
        case .backgroundGuidance:
            return isStandaloneGuidance
                ? NSLocalizedString("Get started", comment: "")
                : NSLocalizedString("Next", comment: "")
        case .privacy:
            return NSLocalizedString("ONBOARDING_FINISH", comment: "")
        }
    }

    private var isPrimaryButtonEnabled: Bool {
        switch step {
        case .intro:
            return true
        case .identity:
            return !trimmedName.isEmpty
        case .internet:
            if phoneVerification.isBusy { return false }
            if phoneVerification.codeSent && phoneVerification.verifiedPhone == nil {
                return !phoneVerification.code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            }
            return true
        case .permissions:
            return requiredPermissionsSatisfied
        case .backgroundGuidance:
            return true
        case .privacy:
            return canFinish
        }
    }

    /// The permissions step can only be passed once Bluetooth — the app's core transport — is
    /// actually granted, and the location prompt has at least been answered (a conscious denial
    /// of location is allowed; skipping the prompt entirely is not).
    ///
    /// Notifications are deliberately NOT part of this gate: App Review guideline 4.5.4 requires
    /// push to stay optional, and gating Continue on the notification prompt read as "the app
    /// requires push notifications in order to function" (rejection of 1.1.8, July 30 2026).
    private var requiredPermissionsSatisfied: Bool {
        permissions.bluetoothStatus == .granted &&
            permissions.locationStatus.isResolved
    }

    private var footerMessage: String {
        switch step {
        case .intro:
            return NSLocalizedString("ONBOARDING_INTRO_FOOTER", comment: "")
        case .identity:
            if trimmedName.isEmpty {
                return NSLocalizedString("ONBOARDING_NAME_FOOTER_EMPTY", comment: "")
            }
            return NSLocalizedString("You can edit these later in Profile.", comment: "")
        case .internet:
            return NSLocalizedString("ONBOARDING_INTERNET_OPTIONAL_NOTE", comment: "")
        case .permissions:
            if !requiredPermissionsSatisfied {
                if permissions.bluetoothStatus == .denied || permissions.bluetoothStatus == .restricted {
                    return NSLocalizedString("ONBOARDING_PERMISSIONS_BLUETOOTH_REQUIRED", comment: "")
                }
                return NSLocalizedString("ONBOARDING_PERMISSIONS_FOOTER_REQUIRED", comment: "")
            }
            return NSLocalizedString("You can change these later in Settings.", comment: "")
        case .backgroundGuidance:
            return NSLocalizedString("You can review these tips later from Messages and Rescue.", comment: "")
        case .privacy:
            if canFinish {
                return NSLocalizedString("ONBOARDING_PRIVACY_FOOTER_READY", comment: "")
            }
            return NSLocalizedString("To continue, please accept both documents and enter your name.", comment: "")
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 16) {
                    heroCard
                    contentCard
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 20)
                .padding(.bottom, 132)
                .background(ScrollViewTouchFixer())
            }
            .scrollIndicators(.hidden)
            .background(AppScreenBackground())
            .safeAreaInset(edge: .bottom) {
                continueBar
            }
            .toolbar(.hidden, for: .navigationBar)
        }
        .onAppear {
            // Pre-warm the GIF asset so it's decoded and ready before the user reaches step 2.
            PreparedGIFAssetStore.shared.warmUp(named: "AppSwitcherGuide")

            if avatarImage == nil,
               let data = profiles.first?.avatarImageData,
               let existingAvatar = UIImage(data: data) {
                avatarImage = existingAvatar
            }

            guard fullName.isEmpty else { return }
            if let existingName = profiles.first?.fullName,
               existingName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                fullName = existingName
            }
        }
        .onChange(of: avatarItem) { _, newValue in
            guard let newValue else { return }
            Task {
                if let data = try? await newValue.loadTransferable(type: Data.self),
                   let uiImage = UIImage(data: data) {
                    await MainActor.run { avatarImage = uiImage }
                }
            }
        }
    }

    private func primaryAction() {
        switch step {
        case .intro:
            step = .identity
        case .identity:
            guard !trimmedName.isEmpty else { return }
            step = .internet
        case .internet:
            if phoneVerification.verifiedPhone != nil || !phoneVerification.hasNumberInput {
                // Continue (verified) or Skip (nothing entered) both advance.
                step = .permissions
                permissions.refreshStatuses()
            } else if phoneVerification.codeSent {
                phoneVerification.verify(displayName: trimmedName)
            } else {
                phoneVerification.sendCode()
            }
        case .permissions:
            step = .backgroundGuidance
        case .backgroundGuidance:
            if isStandaloneGuidance {
                completeOnboarding()
            } else {
                step = .privacy
            }
        case .privacy:
            completeOnboarding()
        }
    }

    private func goBack() {
        switch step {
        case .intro:
            break
        case .identity:
            step = .intro
        case .internet:
            step = .identity
        case .permissions:
            step = .internet
        case .backgroundGuidance:
            step = .permissions
        case .privacy:
            step = .backgroundGuidance
        }
    }

    private func completeOnboarding() {
        guard canFinish else { return }
        let name = trimmedName

        let profile: Profile
        if let existing = profiles.first {
            profile = existing
        } else {
            profile = Profile()
            context.insert(profile)
        }
        profile.fullName = name

        // Carry the onboarding-picked avatar into Profile using the SAME path ProfileViewModel.save
        // uses: SwiftData Profile.avatarImageData (read back by ProfileViewModel.loadIfNeeded) +
        // metadata thumbnail (contact avatars / QR) + best-effort cloud upload. Only written when a
        // photo was picked, so an existing avatar is never wiped.
        if let avatarImage, let data = avatarImage.jpegData(compressionQuality: 0.9) {
            profile.avatarImageData = data
            ProfileMetadataStore.saveAvatarThumbnailData(avatarImage.onboardingAvatarThumbnailData())
            ProfilePhotoUploader.schedule(jpegData: data)
        }

        do {
            try context.save()
            ProfileMetadataStore.saveFullName(name)
            hasCompletedOnboarding = true
        } catch {
            // Keep the user here if saving fails.
        }
    }

    private var heroCard: some View {
        HStack(alignment: .center, spacing: 14) {
            Image("dcslogo")
                .resizable()
                .scaledToFit()
                .frame(width: 42, height: 42)

            VStack(alignment: .leading, spacing: 6) {
                onboardingLabel(title: stepLabel)

                Text(heroTitle)
                    .font(.headline.weight(.semibold))

                Text(heroDetail)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .appSurface(style: .elevated, padding: 18)
    }

    private var stepLabel: String {
        if isStandaloneGuidance {
            return NSLocalizedString("ONBOARDING_GUIDANCE_EYEBROW", comment: "")
        }
        return String(
            format: NSLocalizedString("ONBOARDING_STEP_COUNTER", comment: ""),
            step.rawValue + 1,
            Step.allCases.count
        )
    }

    private var heroTitle: String {
        switch step {
        case .intro:
            return NSLocalizedString("ONBOARDING_INTRO_HEADLINE", comment: "")
        case .identity:
            return NSLocalizedString("ONBOARDING_NAME_HERO", comment: "")
        case .internet:
            return NSLocalizedString("ONBOARDING_INTERNET_TITLE", comment: "")
        case .permissions:
            return NSLocalizedString("Enable core features.", comment: "")
        case .backgroundGuidance:
            return NSLocalizedString("Keep Crisis Connect reachable.", comment: "")
        case .privacy:
            return NSLocalizedString("ONBOARDING_PRIVACY_TITLE", comment: "")
        }
    }

    private var heroDetail: String {
        switch step {
        case .intro:
            return NSLocalizedString("ONBOARDING_INTRO_BODY", comment: "")
        case .identity:
            return NSLocalizedString("ONBOARDING_NAME_HERO_DETAIL", comment: "")
        case .internet:
            return NSLocalizedString("ONBOARDING_INTERNET_HERO_DETAIL", comment: "")
        case .permissions:
            return NSLocalizedString("Grant the permissions Crisis Connect relies on during emergencies.", comment: "")
        case .backgroundGuidance:
            return NSLocalizedString("Keep the app in the background so offline messages can still reach you.", comment: "")
        case .privacy:
            return NSLocalizedString("ONBOARDING_PRIVACY_BODY", comment: "")
        }
    }

    @ViewBuilder
    private var contentCard: some View {
        switch step {
        case .intro:
            introCard
        case .identity:
            identityCard
        case .internet:
            card { OnboardingInternetStepCard(verification: phoneVerification) }
        case .permissions:
            permissionsCard
        case .backgroundGuidance:
            backgroundGuidanceCard
        case .privacy:
            privacyCard
        }
    }

    private var introCard: some View {
        card {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    AppIconBadge(systemName: "bolt.horizontal.circle", tint: .appPrimary, size: 42)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("ONBOARDING_INTRO_EYEBROW")
                            .font(.headline)
                        Text("ONBOARDING_INTRO_TAGLINE")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                VStack(alignment: .leading, spacing: 14) {
                    introCapabilityRow(
                        icon: "dot.radiowaves.left.and.right",
                        titleKey: "ONBOARDING_INTRO_NEARBY_TITLE",
                        detailKey: "ONBOARDING_INTRO_NEARBY_DETAIL"
                    )
                    introCapabilityRow(
                        icon: "wifi.slash",
                        titleKey: "ONBOARDING_INTRO_OFFLINE_TITLE",
                        detailKey: "ONBOARDING_INTRO_OFFLINE_DETAIL"
                    )
                    introCapabilityRow(
                        icon: "person.crop.circle.badge.checkmark",
                        titleKey: "ONBOARDING_INTRO_SAFETY_TITLE",
                        detailKey: "ONBOARDING_INTRO_SAFETY_DETAIL"
                    )
                }
            }
        }
    }

    private func introCapabilityRow(icon: String, titleKey: String, detailKey: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconBadge(systemName: icon, tint: .appPrimary, size: 36)
            VStack(alignment: .leading, spacing: 2) {
                Text(LocalizedStringKey(titleKey))
                    .font(.subheadline.weight(.semibold))
                Text(LocalizedStringKey(detailKey))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var privacyCard: some View {
        card {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    AppIconBadge(systemName: "hand.raised.fill", tint: .appSuccess, size: 42)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("ONBOARDING_TRUST_TITLE")
                            .font(.headline)
                        Text("ONBOARDING_TRUST_DIAGNOSTICS")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                VStack(alignment: .leading, spacing: 10) {
                    trustRow(icon: "chevron.left.forwardslash.chevron.right", textKey: "ONBOARDING_TRUST_OPEN_SOURCE")
                    trustRow(icon: "nosign", textKey: "ONBOARDING_TRUST_NO_ADS")
                    trustRow(icon: "cross.case", textKey: "ONBOARDING_TRUST_EMERGENCY")
                }
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(Color.appSuccess.opacity(0.08))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(Color.appSuccess.opacity(0.18), lineWidth: 1)
                )

                Divider()

                VStack(alignment: .leading, spacing: 12) {
                    sectionHeader(
                        title: NSLocalizedString("ONBOARDING_DOCUMENTS_SECTION", comment: ""),
                        detail: NSLocalizedString("ONBOARDING_DOCUMENTS_SECTION_DETAIL", comment: "")
                    )

                    agreementRow(
                        title: NSLocalizedString("ONBOARDING_TERMS", comment: ""),
                        destination: termsURL,
                        isOn: $acceptedTerms,
                        toggleAccessibilityIdentifier: "onboarding-terms-toggle"
                    )

                    agreementRow(
                        title: NSLocalizedString("ONBOARDING_PRIVACY", comment: ""),
                        destination: privacyURL,
                        isOn: $acceptedPrivacy,
                        toggleAccessibilityIdentifier: "onboarding-privacy-toggle"
                    )
                }
            }
        }
    }

    private func trustRow(icon: String, textKey: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appSuccess)
                .frame(width: 22)
            Text(LocalizedStringKey(textKey))
                .font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var identityCard: some View {
        card {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    AppIconBadge(systemName: "person.badge.shield.checkmark", tint: .appPrimary, size: 42)

                    VStack(alignment: .leading, spacing: 3) {
                        Text("ONBOARDING_COMPLETE_SETUP")
                            .font(.headline)
                        Text("ONBOARDING_COMPLETE_SETUP_DETAIL")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }

                avatarPicker
                    .frame(maxWidth: .infinity)

                VStack(alignment: .leading, spacing: 12) {
                    sectionHeader(
                        title: NSLocalizedString("ONBOARDING_NAME_SECTION", comment: ""),
                        detail: NSLocalizedString("ONBOARDING_NAME_SECTION_DETAIL", comment: "")
                    )

                    TextField("ONBOARDING_NAME_PLACEHOLDER", text: $fullName)
                        .focused($isNameFieldFocused)
                        .textContentType(.name)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                        .submitLabel(.done)
                        .accessibilityIdentifier("onboarding-name-field")
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                                .fill(Color.appSurfaceMuted)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                                .stroke(Color.appBorder, lineWidth: 1)
                        )

                    Text("ONBOARDING_NAME_HINT")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }

                VStack(alignment: .center, spacing: 8) {
                    Image(systemName: "lock.shield")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appSuccess)

                    Text("ONBOARDING_LOCAL_NOTICE")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(Color.appSuccess.opacity(0.08))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(Color.appSuccess.opacity(0.18), lineWidth: 1)
                )
            }
        }
    }

    /// Live circular avatar preview with a camera badge — initials fallback, then a person glyph.
    /// Tapping opens the system photo picker (Android's WelcomeAvatarPicker add/change-photo).
    private var avatarPicker: some View {
        PhotosPicker(selection: $avatarItem, matching: .images) {
            ZStack(alignment: .bottomTrailing) {
                ZStack {
                    if let avatarImage {
                        Image(uiImage: avatarImage)
                            .resizable()
                            .scaledToFill()
                    } else if !avatarInitials.isEmpty {
                        Text(avatarInitials)
                            .font(.title.weight(.semibold))
                            .foregroundStyle(Color.appPrimary)
                    } else {
                        Image(systemName: "person.crop.circle.fill")
                            .resizable()
                            .scaledToFit()
                            .symbolRenderingMode(.hierarchical)
                            .foregroundStyle(.secondary)
                            .padding(18)
                    }
                }
                .frame(width: 96, height: 96)
                .background(Circle().fill(Color.appPrimary.opacity(0.12)))
                .clipShape(Circle())
                .overlay(Circle().stroke(Color.appBorder, lineWidth: 1))

                Image(systemName: "camera.fill")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(Color.appPrimary))
                    .overlay(Circle().stroke(Color.appSurfaceElevated, lineWidth: 2))
            }
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("onboarding-avatar-picker")
        .accessibilityLabel(Text("Choose Photo"))
    }

    /// Up to two uppercased initials from the entered name — the avatar's text fallback.
    private var avatarInitials: String {
        let parts = trimmedName
            .split(separator: " ")
            .prefix(2)
            .compactMap { $0.first.map(String.init) }
        return parts.joined().uppercased()
    }

    private var permissionsCard: some View {
        card {
            VStack(alignment: .leading, spacing: 20) {
                HStack(spacing: 12) {
                    AppIconBadge(systemName: "checkmark.shield", tint: .appPrimary, size: 42)

                    VStack(alignment: .leading, spacing: 3) {
                        Text("Core permissions")
                            .font(.headline)
                        Text("Each permission helps Crisis Connect reach you, or help others, during emergencies.")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    Spacer(minLength: 0)
                }

                permissionRow(
                    icon: "location.fill",
                    tint: .appPrimary,
                    title: NSLocalizedString("Location", comment: ""),
                    detail: NSLocalizedString("Share SOS location, center offline maps, and help rescuers find you.", comment: ""),
                    status: permissions.locationStatus,
                    accessibilityId: "onboarding-permission-location",
                    onRequest: { permissions.requestLocation() }
                )

                Divider()

                permissionRow(
                    icon: "dot.radiowaves.left.and.right",
                    tint: .appWarning,
                    title: NSLocalizedString("Bluetooth", comment: ""),
                    detail: NSLocalizedString("Reach nearby phones over BLE mesh when the internet is down.", comment: ""),
                    status: permissions.bluetoothStatus,
                    accessibilityId: "onboarding-permission-bluetooth",
                    onRequest: { permissions.requestBluetooth() }
                )

                // Authorization is granted but the radio is OFF: a green check would lie —
                // every offline feature is dead until the user turns Bluetooth back on.
                if permissions.bluetoothStatus == .granted, permissions.bluetoothPoweredOff {
                    HStack(spacing: 8) {
                        Image(systemName: "antenna.radiowaves.left.and.right.slash")
                            .font(.footnote.weight(.semibold))
                        Text(NSLocalizedString("Bluetooth is off — turn it on from Control Center or Settings.", comment: ""))
                            .font(.footnote)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .foregroundStyle(Color.appWarning)
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(
                        RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                            .fill(Color.appWarning.opacity(0.10))
                    )
                    .accessibilityIdentifier("onboarding-bluetooth-powered-off")
                }

                Divider()

                permissionRow(
                    icon: "bell.fill",
                    tint: .appDanger,
                    title: NSLocalizedString("Notifications", comment: ""),
                    detail: NSLocalizedString("Receive SOS alerts from nearby users in distress.", comment: ""),
                    status: permissions.notificationsStatus,
                    accessibilityId: "onboarding-permission-notifications",
                    onRequest: {
                        Task { await permissions.requestNotifications() }
                    }
                )

                VStack(alignment: .center, spacing: 8) {
                    Image(systemName: "info.circle")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)

                    Text("You can skip any permission now and change it later from Settings. The app still works — but some rescue features will be limited.")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(Color.appPrimary.opacity(0.08))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(Color.appPrimary.opacity(0.18), lineWidth: 1)
                )
            }
        }
    }

    private func permissionRow(
        icon: String,
        tint: Color,
        title: String,
        detail: String,
        status: OnboardingPermissionsCoordinator.Status,
        accessibilityId: String,
        onRequest: @escaping () -> Void
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconBadge(systemName: icon, tint: tint, size: 36)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.semibold))
                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            permissionStatusControl(
                status: status,
                accessibilityId: accessibilityId,
                onRequest: onRequest
            )
        }
    }

    @ViewBuilder
    private func permissionStatusControl(
        status: OnboardingPermissionsCoordinator.Status,
        accessibilityId: String,
        onRequest: @escaping () -> Void
    ) -> some View {
        switch status {
        case .notDetermined:
            // "Continue", not "Allow": App Review guideline 5.1.1(iv) forbids a custom
            // pre-permission prompt whose button pre-empts the system dialog's own wording.
            Button("Continue", action: onRequest)
                .font(.footnote.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(
                    Capsule().fill(Color.appPrimary)
                )
                .foregroundStyle(Color.white)
                .accessibilityIdentifier(accessibilityId)
        case .granted:
            Label("Allowed", systemImage: "checkmark.circle.fill")
                .labelStyle(.titleAndIcon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appSuccess)
                .accessibilityIdentifier("\(accessibilityId)-allowed")
        case .denied:
            // iOS never re-shows the system prompt after a denial — without a Settings shortcut
            // the user is stuck permissionless. Mirrors Android's open-app-settings routing.
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label(NSLocalizedString("Open Settings", comment: ""), systemImage: "exclamationmark.triangle.fill")
                    .labelStyle(.titleAndIcon)
                    .font(.footnote.weight(.semibold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.appWarning.opacity(0.14)))
                    .foregroundStyle(Color.appWarning)
            }
            .accessibilityIdentifier("\(accessibilityId)-denied")
        case .restricted:
            Label("Restricted", systemImage: "lock.fill")
                .labelStyle(.titleAndIcon)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
                .accessibilityIdentifier("\(accessibilityId)-restricted")
        }
    }

    private var backgroundGuidanceCard: some View {
        card {
            VStack(alignment: .leading, spacing: 14) {
                sectionHeader(
                    title: NSLocalizedString("How to leave the app", comment: ""),
                    detail: NSLocalizedString("Go Home or switch apps. Do not swipe Crisis Connect away.", comment: "")
                )

                // The WHY, stated explicitly: offline (Bluetooth) messages can only arrive while
                // the app lives in the background — swiping it away cuts the no-internet lifeline.
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "wifi.slash")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 22)
                    Text("ONBOARDING_GUIDANCE_OFFLINE_REASON")
                        .font(.subheadline)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(12)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(Color.appPrimary.opacity(0.08))
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(Color.appPrimary.opacity(0.18), lineWidth: 1)
                )

                appSwitcherGuideCard
            }
        }
    }

    /// GIF display width scaled to ~45% of screen width, capped for large screens.
    private var gifDisplayWidth: CGFloat {
        min(UIScreen.main.bounds.width * 0.45, 220)
    }

    private var gifDisplayHeight: CGFloat {
        gifDisplayWidth * (284.0 / 131.0)
    }

    /// Corner radius proportional to GIF width to match the iPhone display shape.
    private var gifCornerRadius: CGFloat {
        gifDisplayWidth * 0.16
    }

    private var appSwitcherGuideCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [
                                Color.appPrimary.opacity(0.14),
                                Color.appPrimary.opacity(0.04),
                                Color.appSuccess.opacity(0.08)
                            ],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                AnimatedGIFView(assetName: "AppSwitcherGuide")
                    .frame(width: gifDisplayWidth, height: gifDisplayHeight)
                    .clipShape(RoundedRectangle(cornerRadius: gifCornerRadius, style: .continuous))
                    .shadow(color: Color.black.opacity(0.14), radius: 12, y: 8)
            }
            .frame(maxWidth: .infinity)
            .frame(height: gifDisplayHeight + 32)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )

            ViewThatFits {
                HStack(spacing: 8) {
                    guideCaptionTag(
                        title: NSLocalizedString("Home or switch apps is OK", comment: ""),
                        tint: .appSuccess,
                        systemImage: "checkmark.circle.fill"
                    )
                    guideCaptionTag(
                        title: NSLocalizedString("Do not swipe it away", comment: ""),
                        tint: .appWarning,
                        systemImage: "xmark.circle.fill"
                    )
                }

                VStack(alignment: .leading, spacing: 8) {
                    guideCaptionTag(
                        title: NSLocalizedString("Home or switch apps is OK", comment: ""),
                        tint: .appSuccess,
                        systemImage: "checkmark.circle.fill"
                    )
                    guideCaptionTag(
                        title: NSLocalizedString("Do not swipe it away", comment: ""),
                        tint: .appWarning,
                        systemImage: "xmark.circle.fill"
                    )
                }
            }
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
    }

    private func instructionStatusRow(title: String, detail: String, tint: Color, systemImage: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(tint)
                .padding(.top, 2)

            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(detail)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(tint.opacity(0.08))
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(tint.opacity(0.18), lineWidth: 1)
        )
    }

    private var leaveInBackgroundCard: some View {
        guideComparisonCard(
            label: NSLocalizedString("Do This", comment: ""),
            title: NSLocalizedString("Leave it in the background", comment: ""),
            detail: NSLocalizedString("Go Home or switch apps.", comment: ""),
            imageName: "AppLeaveInBackground",
            tint: .appSuccess,
            systemImage: "checkmark.circle.fill"
        )
    }

    private var closeFromSwitcherCard: some View {
        guideComparisonCard(
            label: NSLocalizedString("Do Not Do This", comment: ""),
            title: NSLocalizedString("Do not close it", comment: ""),
            detail: NSLocalizedString("Do not swipe Crisis Connect away.", comment: ""),
            imageName: "AppCloseFromSwitcher",
            tint: .appWarning,
            systemImage: "xmark.circle.fill"
        )
    }

    private func guideComparisonCard(
        label: String,
        title: String,
        detail: String,
        imageName: String,
        tint: Color,
        systemImage: String
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    Image(systemName: systemImage)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(tint)

                    Text(label)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(tint)
                }

                Text(title)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(detail)
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            ZStack {
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appBackground.opacity(0.8))

                Image(imageName)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 90, height: 112)
            }
            .frame(width: 100, height: 122)
            .clipShape(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(Color.appBorder.opacity(0.9), lineWidth: 1)
            )
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(tint.opacity(0.08))
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(tint.opacity(0.18), lineWidth: 1)
        )
    }

    private func guideCaptionTag(title: String, tint: Color, systemImage: String) -> some View {
        HStack(spacing: 6) {
            guideCaptionIcon(systemImage: systemImage, tint: tint)
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(minHeight: 18, alignment: .leading)
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(
            Capsule()
                .fill(tint.opacity(0.08))
        )
        .overlay(
            Capsule()
                .stroke(tint.opacity(0.18), lineWidth: 1)
        )
    }

    private func guideCaptionIcon(systemImage: String, tint: Color) -> some View {
        ZStack {
            Circle()
                .fill(tint)

            if systemImage.contains("xmark") {
                ZStack {
                    RoundedRectangle(cornerRadius: 1, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 10, height: 2.2)
                        .rotationEffect(.degrees(45))

                    RoundedRectangle(cornerRadius: 1, style: .continuous)
                        .fill(Color.white)
                        .frame(width: 10, height: 2.2)
                        .rotationEffect(.degrees(-45))
                }
            } else {
                Image(systemName: "checkmark")
                    .font(.system(size: 8, weight: .black))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: 18, height: 18)
        .accessibilityHidden(true)
    }

    private var continueBar: some View {
        VStack(spacing: 10) {
            if step != .intro && !isStandaloneGuidance {
                HStack(spacing: 12) {
                    Button("ONBOARDING_BACK") {
                        goBack()
                    }
                    .accessibilityIdentifier("onboarding-back-button")
                    .buttonStyle(AppSecondaryButtonStyle())

                    Button(primaryButtonTitle) {
                        primaryAction()
                    }
                    .accessibilityIdentifier(step == .privacy ? "onboarding-finish-button" : "onboarding-next-button")
                    .buttonStyle(AppPrimaryButtonStyle(fill: isPrimaryButtonEnabled ? .appPrimary : .gray))
                    .disabled(!isPrimaryButtonEnabled)
                }
            } else {
                Button(primaryButtonTitle) {
                    primaryAction()
                }
                .accessibilityIdentifier(isStandaloneGuidance ? "onboarding-finish-button" : "onboarding-next-button")
                .buttonStyle(AppPrimaryButtonStyle(fill: isPrimaryButtonEnabled ? .appPrimary : .gray))
                .disabled(!isPrimaryButtonEnabled)
            }

            // A typed-but-unverified number leaves the primary on "Send code"; this secondary lets
            // the user bail out of verification entirely — the step stays strictly optional.
            if step == .internet && phoneVerification.verifiedPhone == nil && phoneVerification.hasNumberInput {
                Button("ONBOARDING_INTERNET_SKIP") {
                    step = .permissions
                    permissions.refreshStatuses()
                }
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .accessibilityIdentifier("onboarding-internet-skip-button")
            }

            Text(footerMessage)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.top, 12)
        .padding(.bottom, 12)
        .background(
            ZStack(alignment: .top) {
                Color.appSurfaceElevated
                    .opacity(0.96)
                    .ignoresSafeArea(edges: .bottom)

                Rectangle()
                    .fill(Color.appBorder.opacity(0.9))
                    .frame(height: 1)
            }
        )
    }

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        content()
            .frame(maxWidth: .infinity, alignment: .leading)
            .appSurface(style: .regular, padding: 18)
    }

    private func onboardingLabel(title: String) -> some View {
        Text(title)
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.appPrimary)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(Color.appPrimary.opacity(0.1))
            )
            .overlay(
                Capsule()
                    .stroke(Color.appPrimary.opacity(0.16), lineWidth: 1)
            )
    }

    private func sectionHeader(title: String, detail: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.subheadline.weight(.semibold))

            Text(detail)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
        }
    }

    private func guidanceBullet(systemImage: String, tint: Color, text: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: systemImage)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(tint)
                .padding(.top, 2)

            Text(text)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func agreementRow(
        title: String,
        destination: URL,
        isOn: Binding<Bool>,
        toggleAccessibilityIdentifier: String
    ) -> some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.body.weight(.medium))
                Link(destination: destination) {
                    HStack(spacing: 4) {
                        Text("ONBOARDING_REVIEW_LINK")
                            .font(.footnote.weight(.semibold))
                        Image(systemName: "arrow.up.right")
                            .font(.footnote.weight(.semibold))
                    }
                }
                .foregroundStyle(Color.appPrimary)
            }

            Spacer(minLength: 8)

            Toggle("", isOn: isOn)
                .labelsHidden()
                .tint(.appPrimary)
                .accessibilityIdentifier(toggleAccessibilityIdentifier)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
            )
    }
}

private extension UIImage {
    /// Square-cropped, downscaled avatar thumbnail — mirrors ProfileViewModel's private helper so an
    /// onboarding-picked photo shows up identically in contact avatars and the metadata store.
    func onboardingAvatarThumbnailData(side: CGFloat = 72) -> Data? {
        let minSide = min(size.width, size.height)
        guard minSide > 0 else { return nil }
        let origin = CGPoint(x: (size.width - minSide) / 2, y: (size.height - minSide) / 2)
        let cropRect = CGRect(origin: origin, size: CGSize(width: minSide, height: minSide))
        let cropped: UIImage
        if let cgImage, let croppedImage = cgImage.cropping(to: cropRect) {
            cropped = UIImage(cgImage: croppedImage, scale: scale, orientation: imageOrientation)
        } else {
            cropped = self
        }
        let targetSize = CGSize(width: side, height: side)
        let renderer = UIGraphicsImageRenderer(size: targetSize)
        let thumbnail = renderer.image { _ in
            cropped.draw(in: CGRect(origin: .zero, size: targetSize))
        }
        return thumbnail.jpegData(compressionQuality: 0.78)
    }
}

#Preview {
    RootView()
        .environmentObject(AppSettingsViewModel())
        .modelContainer(for: [Profile.self, Item.self], inMemory: true)
}
