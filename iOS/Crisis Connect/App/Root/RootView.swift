//
//  RootView.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import SwiftUI
import SwiftData

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
    private enum Step {
        case identity
        case backgroundGuidance
    }

    @Environment(\.modelContext) private var context
    @Query(sort: \Profile.createdAt, order: .reverse) private var profiles: [Profile]
    @Binding var hasCompletedOnboarding: Bool

    @State private var fullName: String = ""
    @State private var acceptedTerms: Bool = false
    @State private var acceptedPrivacy: Bool = false
    @State private var step: Step = .identity
    @FocusState private var isNameFieldFocused: Bool

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
        _step = State(initialValue: startAtBackgroundGuidance ? .backgroundGuidance : .identity)
    }

    private var trimmedName: String {
        fullName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var canContinue: Bool {
        acceptedTerms && acceptedPrivacy && !trimmedName.isEmpty
    }

    private var primaryButtonTitle: String {
        switch step {
        case .identity:
            return "Next"
        case .backgroundGuidance:
            return "Get started"
        }
    }

    private var isPrimaryButtonEnabled: Bool {
        switch step {
        case .identity:
            return canContinue
        case .backgroundGuidance:
            return true
        }
    }

    private var footerMessage: String {
        switch step {
        case .identity:
            if canContinue {
                return "You can edit these later in Profile."
            }
            return "To continue, please accept both documents and enter your name."
        case .backgroundGuidance:
            return "You can review these tips later from Messages and Rescue."
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

            guard fullName.isEmpty else { return }
            if let existingName = profiles.first?.fullName,
               existingName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                fullName = existingName
            }
        }
    }

    private func primaryAction() {
        switch step {
        case .identity:
            guard canContinue else { return }
            step = .backgroundGuidance
        case .backgroundGuidance:
            completeOnboarding()
        }
    }

    private func completeOnboarding() {
        guard canContinue else { return }
        let name = trimmedName

        let profile: Profile
        if let existing = profiles.first {
            profile = existing
        } else {
            profile = Profile()
            context.insert(profile)
        }
        profile.fullName = name

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
        switch step {
        case .identity:
            return "Step 1 of 2"
        case .backgroundGuidance:
            return "Step 2 of 2"
        }
    }

    private var heroTitle: String {
        switch step {
        case .identity:
            return "Complete the required setup."
        case .backgroundGuidance:
            return "Keep Crisis Connect reachable."
        }
    }

    private var heroDetail: String {
        switch step {
        case .identity:
            return "Add your name and accept the documents. You can edit details later."
        case .backgroundGuidance:
            return "Keep the app in the background so offline messages can still reach you."
        }
    }

    @ViewBuilder
    private var contentCard: some View {
        switch step {
        case .identity:
            identityCard
        case .backgroundGuidance:
            backgroundGuidanceCard
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

    private var backgroundGuidanceCard: some View {
        card {
            VStack(alignment: .leading, spacing: 14) {
                sectionHeader(
                    title: "How to leave the app",
                    detail: "Go Home or switch apps. Do not swipe Crisis Connect away."
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
                        title: "Home or switch apps is OK",
                        tint: .appSuccess,
                        systemImage: "checkmark.circle.fill"
                    )
                    guideCaptionTag(
                        title: "Do not swipe it away",
                        tint: .appWarning,
                        systemImage: "xmark.circle.fill"
                    )
                }

                VStack(alignment: .leading, spacing: 8) {
                    guideCaptionTag(
                        title: "Home or switch apps is OK",
                        tint: .appSuccess,
                        systemImage: "checkmark.circle.fill"
                    )
                    guideCaptionTag(
                        title: "Do not swipe it away",
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
            label: "Do This",
            title: "Leave it in the background",
            detail: "Go Home or switch apps.",
            imageName: "AppLeaveInBackground",
            tint: .appSuccess,
            systemImage: "checkmark.circle.fill"
        )
    }

    private var closeFromSwitcherCard: some View {
        guideComparisonCard(
            label: "Do Not Do This",
            title: "Do not close it",
            detail: "Do not swipe Crisis Connect away.",
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
            if step == .backgroundGuidance {
                HStack(spacing: 12) {
                    Button("ONBOARDING_BACK") {
                        step = .identity
                    }
                    .accessibilityIdentifier("onboarding-back-button")
                    .buttonStyle(AppSecondaryButtonStyle())

                    Button(primaryButtonTitle) {
                        primaryAction()
                    }
                    .accessibilityIdentifier("onboarding-finish-button")
                    .buttonStyle(AppPrimaryButtonStyle())
                }
            } else {
                Button(primaryButtonTitle) {
                    primaryAction()
                }
                .accessibilityIdentifier("onboarding-next-button")
                .buttonStyle(AppPrimaryButtonStyle(fill: isPrimaryButtonEnabled ? .appPrimary : .gray))
                .disabled(!isPrimaryButtonEnabled)
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

#Preview {
    RootView()
        .environmentObject(AppSettingsViewModel())
        .modelContainer(for: [Profile.self, Item.self], inMemory: true)
}
