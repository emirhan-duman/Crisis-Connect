//
//  ProfileView.swift
//  Crisis Connect
//
//  Created by Assistant on 09.12.2025
//

import SwiftUI
import PhotosUI
import SwiftData
import AuthenticationServices
import UIKit
import Combine

struct ProfileView: View {
    @Environment(\.modelContext) private var context
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var viewModel = ProfileViewModel()
    @State private var isEmailSignInSheetPresented = false
    @State private var isNameEditorPresented = false
    @State private var isDeleteAccountConfirmPresented = false
    @State private var draftFullName = ""

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollView {
                VStack(spacing: 20) {
                    profileIdentitySection
                    accountDetailsSection
                    nameEditorSection
                    MedicalInfoSection()
                    if isSignedIn {
                        signedInStateBanner
                    }
                    if shouldShowManagedVerificationNotice {
                        managedVerificationNotice
                    }
                    if isSignedIn {
                        ProfileCertificateCard(viewModel: viewModel)
                    }
                    authActionsSection
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 18)
                .padding(.bottom, 40)
            }
            .scrollIndicators(.hidden)
        }
        .navigationTitle("Profile")
        .navigationBarTitleDisplayMode(.inline)
        .appNavigationBarStyle()
        .sheet(isPresented: $isEmailSignInSheetPresented, onDismiss: dismissEmailSignInSheet) {
            ProfileEmailSignInSheet(
                viewModel: viewModel,
                onDismiss: dismissEmailSignInSheet
            )
            .presentationDetents([.height(500), .large])
            .presentationDragIndicator(.visible)
            .presentationBackground(Color.appSurfaceElevated)
            .interactiveDismissDisabled(viewModel.isAuthLoading)
        }
        .alert("PROFILE_EDIT_NAME_TITLE", isPresented: $isNameEditorPresented) {
            TextField("Full Name", text: $draftFullName)
                .textInputAutocapitalization(.words)
            Button("COMMON_CANCEL", role: .cancel) {}
            Button("COMMON_SAVE") {
                saveEditedName()
            }
        } message: {
            Text("PROFILE_EDIT_NAME_MESSAGE")
        }
        .alert("PROFILE_DELETE_ACCOUNT_CONFIRM_TITLE", isPresented: $isDeleteAccountConfirmPresented) {
            Button("COMMON_CANCEL", role: .cancel) {}
            Button("PROFILE_DELETE_ACCOUNT_CONFIRM_ACTION", role: .destructive) {
                viewModel.deleteAccount(context: context)
            }
        } message: {
            Text("PROFILE_DELETE_ACCOUNT_CONFIRM_MESSAGE")
        }
        .overlay(alignment: .bottom) { transientMessageBanner }
        .analyticsScreen("profile")
        .onAppear { viewModel.loadIfNeeded(context: context) }
        .onDisappear {
            viewModel.handleDisappear(context: context)
        }
        .onChange(of: viewModel.pickerItem) { _, newValue in
            Task {
                await viewModel.loadImage(from: newValue)
                await MainActor.run {
                    viewModel.scheduleAutosave(context: context)
                    if newValue != nil {
                        viewModel.transientMessage = NSLocalizedString(
                            viewModel.avatarImage != nil ? "PROFILE_PHOTO_SAVED" : "PROFILE_PHOTO_ERROR",
                            comment: ""
                        )
                    }
                }
            }
        }
        .onChange(of: viewModel.fullName) { _, _ in viewModel.scheduleAutosave(context: context) }
        .onChange(of: viewModel.email) { _, _ in viewModel.scheduleAutosave(context: context) }
        .onChange(of: viewModel.country) { _, _ in viewModel.scheduleAutosave(context: context) }
        .onChange(of: viewModel.agency) { _, _ in viewModel.scheduleAutosave(context: context) }
        .onChange(of: viewModel.signInEmail) { _, _ in viewModel.clearAuthError() }
        .onChange(of: viewModel.signInPassword) { _, _ in viewModel.clearAuthError() }
        .onChange(of: viewModel.isAnonymous) { _, isAnonymous in
            if !isAnonymous && isEmailSignInSheetPresented {
                dismissEmailSignInSheet()
            }
        }
    }

    private var profileIdentitySection: some View {
        VStack(spacing: 16) {
            PhotosPicker(selection: $viewModel.pickerItem, matching: .images) {
                avatarView
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("PROFILE_PHOTO_CONTENT_DESCRIPTION"))

            VStack(spacing: 6) {
                Text(displayName)
                    .font(.title3.weight(.semibold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)

                Text(headerSecondaryText)
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }

            if isSignedIn && (!normalizedRole.isEmpty || viewModel.isVerified) {
                HStack(spacing: 10) {
                    if !normalizedRole.isEmpty {
                        profileStatusPill(
                            systemImage: "building.2.crop.circle",
                            label: normalizedRole.uppercased(),
                            tint: .appPrimary
                        )
                    }

                    if viewModel.isVerified {
                        profileStatusPill(
                            systemImage: "checkmark.seal.fill",
                            label: NSLocalizedString("PROFILE_VERIFIED_YES", comment: ""),
                            tint: .appPrimary
                        )
                    }
                }
                .frame(maxWidth: .infinity)
            }

            PhotosPicker(selection: $viewModel.pickerItem, matching: .images) {
                Label("Choose Photo", systemImage: "photo")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppSecondaryButtonStyle())

            if viewModel.avatarImage != nil {
                Button(role: .destructive) {
                    viewModel.removeProfilePhoto(context: context)
                } label: {
                    Label("PROFILE_REMOVE_PHOTO", systemImage: "trash")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppSecondaryButtonStyle())
                .tint(.red)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 24)
        .padding(.vertical, 28)
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color.appPrimary.opacity(0.16),
                            Color.appSurfaceElevated
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
    }

    private var accountDetailsSection: some View {
        sectionCard(
            title: NSLocalizedString("Account Details", comment: ""),
            subtitle: NSLocalizedString("Core profile fields synced across contact sharing and rescue coordination.", comment: ""),
            systemImage: "person.text.rectangle",
            tint: .appPrimary
        ) {
            VStack(spacing: 14) {
                profileInfoRow(
                    label: NSLocalizedString("Username", comment: ""),
                    value: currentNameDisplay
                )
                profileInfoRow(
                    label: NSLocalizedString("Email", comment: ""),
                    value: currentEmailDisplay
                )
                if !viewModel.displayPhoneNumber.isEmpty {
                    profileInfoRow(
                        label: NSLocalizedString("PROFILE_PHONE_LABEL", comment: ""),
                        value: viewModel.displayPhoneNumber
                    )
                }
                profileInfoRow(
                    label: NSLocalizedString("Country", comment: ""),
                    value: currentCountryDisplay
                )

                if showsManagedDetails {
                    profileInfoRow(
                        label: NSLocalizedString("Agency", comment: ""),
                        value: currentAgencyDisplay
                    )
                    profileInfoRow(
                        label: NSLocalizedString("Role", comment: ""),
                        value: normalizedRole.uppercased()
                    )
                    profileVerificationRow(
                        label: NSLocalizedString("PROFILE_VERIFIED_LABEL", comment: ""),
                        isVerified: viewModel.isVerified
                    )
                }
            }
        }
    }

    private var nameEditorSection: some View {
        sectionCard(
            title: NSLocalizedString("Display Name", comment: ""),
            subtitle: NSLocalizedString("Use a clear name that other people see in chats and QR adds.", comment: ""),
            systemImage: "square.and.pencil",
            tint: .appPrimary
        ) {
            HStack(alignment: .center, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Username")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)

                    Text(currentNameDisplay)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(2)
                }

                Spacer(minLength: 0)

                Button {
                    presentNameEditor()
                } label: {
                    Label("PROFILE_EDIT_LABEL", systemImage: "square.and.pencil")
                }
                .buttonStyle(AppSecondaryButtonStyle())
            }
        }
    }

    private var signedInStateBanner: some View {
        compactStatusBanner(
            systemImage: "checkmark.seal.fill",
            label: NSLocalizedString("PROFILE_AUTH_SIGNED_IN", comment: ""),
            tint: .appSuccess,
            background: Color.appSuccess.opacity(0.12)
        )
    }

    private var managedVerificationNotice: some View {
        compactStatusBanner(
            systemImage: "checkmark.shield",
            label: NSLocalizedString("Verification is managed by the server.", comment: ""),
            tint: .appPrimary,
            background: Color.appPrimary.opacity(0.12)
        )
    }

    private var authorizedOnlyNotice: some View {
        HStack(alignment: .center, spacing: 14) {
            ZStack {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(
                        LinearGradient(
                            colors: [Color.appPrimary, Color.appPrimary.opacity(0.62)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )

                Image(systemName: "building.columns.fill")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 48, height: 48)
            .shadow(color: Color.appPrimary.opacity(colorScheme == .dark ? 0 : 0.28), radius: 9, y: 5)

            VStack(alignment: .leading, spacing: 3) {
                Text("PROFILE_LOGIN_AUTHORIZED_ONLY_TITLE")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.primary)

                Text("PROFILE_LOGIN_AUTHORIZED_ONLY_MESSAGE")
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [Color.appPrimary.opacity(0.12), Color.appPrimary.opacity(0.04)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(Color.appPrimary.opacity(0.2), lineWidth: 1)
        )
    }

    private var authActionsSection: some View {
        sectionCard(
            title: NSLocalizedString("Access", comment: ""),
            subtitle: NSLocalizedString("Connect or disconnect the account used for verified profile sync.", comment: ""),
            systemImage: "person.crop.circle.badge.checkmark",
            tint: .appPrimary
        ) {
            if viewModel.isAnonymous || viewModel.authStatusText == "PROFILE_AUTH_SIGNED_OUT" {
                signedOutAuthContent
            } else {
                signedInAuthContent
            }

            if viewModel.isAuthLoading || viewModel.isProfileRefreshing {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
                .padding(.vertical, 6)
            }
        }
    }

    private var signedOutAuthContent: some View {
        VStack(spacing: 16) {
            authorizedOnlyNotice

            if let authErrorMessage = viewModel.authErrorMessage,
               authErrorMessage.isEmpty == false,
               !isEmailSignInSheetPresented {
                ProfileAuthErrorBanner(message: authErrorMessage) {
                    viewModel.clearAuthError()
                }
            }

            VStack(spacing: 10) {
                AuthProviderButton(
                    title: "PROFILE_LOGIN_EMAIL",
                    emphasis: .primary,
                    isDisabled: viewModel.isAuthLoading,
                    action: { presentEmailSignInSheet() }
                ) {
                    Image(systemName: "envelope.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                }

                AuthProviderButton(
                    title: "PROFILE_LOGIN_ENTERPRISE_SSO",
                    emphasis: .tinted,
                    isDisabled: viewModel.isAuthLoading,
                    action: { viewModel.signInWithEnterpriseSso() }
                ) {
                    Image(systemName: "building.2.fill")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                }
            }

            authDivider("PROFILE_LOGIN_DIVIDER")

            VStack(spacing: 10) {
                SignInWithAppleButton(.signIn) { request in
                    viewModel.prepareAppleSignInRequest(request)
                } onCompletion: { result in
                    viewModel.handleAppleSignInCompletion(result)
                }
                .signInWithAppleButtonStyle(colorScheme == .dark ? .white : .black)
                .frame(maxWidth: .infinity)
                .frame(height: 54)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(Color.appBorder.opacity(colorScheme == .dark ? 0 : 0.5), lineWidth: 1)
                )
                .disabled(viewModel.isAuthLoading)
                .opacity(viewModel.isAuthLoading ? 0.55 : 1)

                AuthProviderButton(
                    title: "PROFILE_LOGIN_GOOGLE",
                    emphasis: .neutral,
                    isDisabled: viewModel.isAuthLoading,
                    action: { viewModel.signInWithGoogle() }
                ) {
                    GoogleGMark()
                }

                AuthProviderButton(
                    title: "PROFILE_LOGIN_MICROSOFT",
                    emphasis: .neutral,
                    isDisabled: viewModel.isAuthLoading,
                    action: { viewModel.signInWithMicrosoft() }
                ) {
                    MicrosoftLogoMark()
                }
            }

            PhoneVerificationPanel(viewModel: viewModel)
        }
    }

    private var signedInAuthContent: some View {
        VStack(spacing: 10) {
            if let authErrorMessage = viewModel.authErrorMessage,
               authErrorMessage.isEmpty == false {
                ProfileAuthErrorBanner(message: authErrorMessage) {
                    viewModel.clearAuthError()
                }
            }

            Button {
                viewModel.signOut()
            } label: {
                Label("PROFILE_LOGOUT", systemImage: "rectangle.portrait.and.arrow.right")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppSecondaryButtonStyle())
            .disabled(viewModel.isAuthLoading)

            Button(role: .destructive) {
                isDeleteAccountConfirmPresented = true
            } label: {
                Label("PROFILE_DELETE_ACCOUNT", systemImage: "trash")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppSecondaryButtonStyle(tint: .appDanger))
            .disabled(viewModel.isAuthLoading)
        }
    }

    private func authDivider(_ key: LocalizedStringKey) -> some View {
        HStack(spacing: 12) {
            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [Color.appBorder.opacity(0), Color.appBorder],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .frame(height: 1)

            Text(key)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
                .textCase(.uppercase)
                .fixedSize()

            Rectangle()
                .fill(
                    LinearGradient(
                        colors: [Color.appBorder, Color.appBorder.opacity(0)],
                        startPoint: .leading,
                        endPoint: .trailing
                    )
                )
                .frame(height: 1)
        }
        .padding(.vertical, 2)
    }


    private func presentNameEditor() {
        draftFullName = viewModel.fullName
        isNameEditorPresented = true
    }

    /// Android's snackbar equivalent: a small bottom capsule that names the outcome and dismisses
    /// itself. Without any feedback channel, a failed rename looked identical to a successful one.
    @ViewBuilder private var transientMessageBanner: some View {
        if let message = viewModel.transientMessage {
            Text(message)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(.white)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(Capsule().fill(Color.black.opacity(0.8)))
                .padding(.bottom, 24)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .task {
                    try? await Task.sleep(nanoseconds: 2_500_000_000)
                    if viewModel.transientMessage == message {
                        viewModel.transientMessage = nil
                    }
                }
        }
    }

    private func saveEditedName() {
        viewModel.commitUsername(draftFullName)
    }

    private func presentEmailSignInSheet() {
        viewModel.clearAuthError()
        isEmailSignInSheetPresented = true
    }

    private func dismissEmailSignInSheet() {
        if isEmailSignInSheetPresented {
            isEmailSignInSheetPresented = false
        }
        viewModel.signInPassword = ""
        viewModel.clearAuthError()
    }

    private var authStatusDisplay: String {
        let status = viewModel.authStatusText
        if status.hasPrefix("PROFILE_") {
            return NSLocalizedString(status, comment: "")
        }
        return status
    }

    private var isSignedIn: Bool {
        !viewModel.isAnonymous && viewModel.authStatusText != "PROFILE_AUTH_SIGNED_OUT"
    }

    private var normalizedRole: String {
        viewModel.firebaseRole.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private var showsManagedDetails: Bool {
        normalizedRole == "admin" || normalizedRole == "fieldteam"
    }

    private var shouldShowManagedVerificationNotice: Bool {
        isSignedIn && showsManagedDetails && !viewModel.isVerified
    }

    private var currentNameDisplay: String {
        let trimmed = viewModel.fullName.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("Not set", comment: "") : trimmed
    }

    private var currentEmailDisplay: String {
        let trimmed = viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("Not set", comment: "") : trimmed
    }

    private var currentCountryDisplay: String {
        let trimmed = viewModel.country.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("Not set", comment: "") : trimmed
    }

    private var currentAgencyDisplay: String {
        let trimmed = viewModel.agency.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? NSLocalizedString("Not set", comment: "") : trimmed
    }

    private var headerSecondaryText: String {
        let trimmedEmail = viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedEmail.isEmpty { return trimmedEmail }
        // Android falls back email → phone → auth status; a phone-only account (the common
        // disaster-app case) showed a bare status line here instead of its own number.
        let phone = viewModel.displayPhoneNumber
        return phone.isEmpty ? authStatusDisplay : phone
    }

    private var displayName: String {
        let trimmed = viewModel.fullName.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty {
            return trimmed
        }
        let emailPrefix = viewModel.email
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: "@")
            .first
            .map(String.init)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if let emailPrefix, !emailPrefix.isEmpty {
            return emailPrefix
        }
        return NSLocalizedString("Profile", comment: "")
    }

    private func profileInfoRow(
        label: String,
        value: String
    ) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
            Spacer(minLength: 12)
            Text(value)
                .font(.body.weight(.medium))
                .foregroundStyle(.primary)
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
                .minimumScaleFactor(0.82)
        }
    }

    private func profileVerificationRow(
        label: String,
        isVerified: Bool
    ) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(label)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
            Spacer(minLength: 12)
            HStack(spacing: 6) {
                Image(systemName: isVerified ? "checkmark.seal.fill" : "xmark.seal")
                    .foregroundStyle(isVerified ? Color.appPrimary : Color.appDanger)
                Text(
                    isVerified
                        ? NSLocalizedString("PROFILE_VERIFIED_YES", comment: "")
                        : NSLocalizedString("PROFILE_VERIFIED_NO", comment: "")
                )
                .font(.body.weight(.medium))
                .foregroundStyle(isVerified ? Color.appPrimary : Color.appDanger)
            }
        }
    }

    private func profileStatusPill(
        systemImage: String,
        label: String,
        tint: Color
    ) -> some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
            Text(label)
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            Capsule(style: .continuous)
                .fill(tint.opacity(0.12))
        )
        .overlay(
            Capsule(style: .continuous)
                .stroke(tint.opacity(0.24), lineWidth: 1)
        )
        .foregroundStyle(tint)
    }

    private func compactStatusBanner(
        systemImage: String,
        label: String,
        tint: Color,
        background: Color
    ) -> some View {
        HStack(spacing: 10) {
            Image(systemName: systemImage)
                .font(.subheadline.weight(.semibold))
            Text(label)
                .font(.subheadline.weight(.semibold))
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(background)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(tint.opacity(0.18), lineWidth: 1)
        )
        .foregroundStyle(tint)
    }

    private func sectionCard<Content: View>(
        title: String,
        subtitle: String,
        systemImage: String,
        tint: Color,
        @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                AppIconBadge(systemName: systemImage, tint: tint)

                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.headline.weight(.semibold))
                    Text(subtitle)
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }

            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .regular, padding: 18)
    }

    private var avatarView: some View {
        ZStack {
            if let uiImage = viewModel.avatarImage {
                Image(uiImage: uiImage)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .resizable()
                    .scaledToFit()
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(.secondary)
                    .padding(18)
            }
        }
        .frame(width: 104, height: 104)
        .background(Circle().fill(Color.appSurfaceMuted))
        .clipShape(Circle())
        .overlay(Circle().stroke(Color.appBorder, lineWidth: 1))
        .shadow(color: .black.opacity(0.05), radius: 8, y: 3)
    }
}

private struct ProfileEmailSignInSheet: View {
    @ObservedObject var viewModel: ProfileViewModel
    var onDismiss: () -> Void

    @FocusState private var focusedField: Field?
    @State private var didAttemptSubmit = false
    @State private var isPasswordVisible = false

    private enum Field {
        case email
        case password
    }

    private var normalizedEmail: String {
        viewModel.signInEmail.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isEmailValid: Bool {
        guard !normalizedEmail.isEmpty else { return false }
        return normalizedEmail.range(
            of: #"^[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}$"#,
            options: [.regularExpression, .caseInsensitive]
        ) != nil
    }

    private var isPasswordValid: Bool {
        !viewModel.signInPassword.isEmpty
    }

    private var shouldShowEmailError: Bool {
        didAttemptSubmit && !isEmailValid
    }

    private var shouldShowPasswordError: Bool {
        didAttemptSubmit && !isPasswordValid
    }

    private var emailErrorKey: String {
        normalizedEmail.isEmpty ? "PROFILE_EMAIL_REQUIRED" : "PROFILE_EMAIL_INVALID"
    }

    var body: some View {
        ZStack {
            Color.appSurfaceElevated
                .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header

                    if let authErrorMessage = viewModel.authErrorMessage,
                       authErrorMessage.isEmpty == false {
                        ProfileAuthErrorBanner(message: authErrorMessage) {
                            viewModel.clearAuthError()
                        }
                    }

                    inputField(
                        titleKey: "Email",
                        icon: "envelope.badge",
                        isError: shouldShowEmailError,
                        supportingTextKey: shouldShowEmailError ? emailErrorKey : nil
                    ) {
                        TextField("Email", text: $viewModel.signInEmail)
                            .focused($focusedField, equals: .email)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textContentType(.username)
                            .keyboardType(.emailAddress)
                            .submitLabel(.next)
                            .onSubmit {
                                focusedField = .password
                            }
                    }

                    inputField(
                        titleKey: "PROFILE_PASSWORD_LABEL",
                        icon: "key",
                        isError: shouldShowPasswordError,
                        supportingTextKey: shouldShowPasswordError ? "PROFILE_PASSWORD_REQUIRED" : nil
                    ) {
                        Group {
                            if isPasswordVisible {
                                TextField(
                                    NSLocalizedString("PROFILE_PASSWORD_LABEL", comment: ""),
                                    text: $viewModel.signInPassword
                                )
                            } else {
                                SecureField(
                                    NSLocalizedString("PROFILE_PASSWORD_LABEL", comment: ""),
                                    text: $viewModel.signInPassword
                                )
                            }
                        }
                        .focused($focusedField, equals: .password)
                        .textContentType(.password)
                        .submitLabel(.go)
                        .onSubmit {
                            submit()
                        }
                    } trailingAccessory: {
                        Button {
                            isPasswordVisible.toggle()
                        } label: {
                            Image(systemName: isPasswordVisible ? "eye.slash" : "eye")
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(Color.appTextSecondary)
                                .frame(width: 24, height: 24)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel(
                            LocalizedStringKey(
                                isPasswordVisible ? "PROFILE_PASSWORD_HIDE" : "PROFILE_PASSWORD_SHOW"
                            )
                        )
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 16)
                .padding(.bottom, 28)
                .background(ScrollViewTouchFixer())
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 10) {
                Button {
                    submit()
                } label: {
                    HStack(spacing: 10) {
                        if viewModel.isAuthLoading {
                            ProgressView()
                                .tint(.white)
                        }

                        Text("PROFILE_SIGN_IN_ACTION")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle())
                .disabled(viewModel.isAuthLoading)

                Button("COMMON_CANCEL") {
                    onDismiss()
                }
                .buttonStyle(AppSecondaryButtonStyle())
                .disabled(viewModel.isAuthLoading)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 12)
            .background(
                ZStack(alignment: .top) {
                    Color.appSurfaceElevated
                        .opacity(0.98)
                        .ignoresSafeArea(edges: .bottom)

                    Rectangle()
                        .fill(Color.appBorder)
                        .frame(height: 1)
                }
            )
        }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 14) {
            AppIconBadge(systemName: "lock.shield", tint: .appPrimary, size: 48)

            VStack(alignment: .leading, spacing: 4) {
                Text("PROFILE_EMAIL_LOGIN_TITLE")
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(.primary)

                Text("PROFILE_EMAIL_LOGIN_SUBTITLE")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 6)

            Spacer(minLength: 0)

            Button {
                onDismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(width: 30, height: 30)
                    .background(Circle().fill(Color.appSurfaceMuted))
            }
            .buttonStyle(.plain)
            .disabled(viewModel.isAuthLoading)
            .accessibilityLabel("Cancel")
        }
        .padding(.top, 4)
    }

    private func submit() {
        didAttemptSubmit = true
        guard isEmailValid, isPasswordValid, !viewModel.isAuthLoading else { return }
        focusedField = nil
        viewModel.signInWithEmailPassword()
    }

    private func inputField<FieldView: View, Accessory: View>(
        titleKey: String,
        icon: String,
        isError: Bool,
        supportingTextKey: String?,
        @ViewBuilder field: () -> FieldView,
        @ViewBuilder trailingAccessory: () -> Accessory = { EmptyView() }
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(LocalizedStringKey(titleKey))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)

            HStack(spacing: 12) {
                AppIconBadge(systemName: icon, tint: isError ? .appDanger : .appPrimary, size: 38)

                field()
                    .font(.body)

                trailingAccessory()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appSurfaceMuted)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(isError ? Color.appDanger.opacity(0.45) : Color.appBorder, lineWidth: 1)
            )

            if let supportingTextKey {
                Text(LocalizedStringKey(supportingTextKey))
                    .font(.footnote)
                    .foregroundStyle(Color.appDanger)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct ProfileAuthErrorBanner: View {
    let message: String
    var onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconBadge(systemName: "exclamationmark.triangle.fill", tint: .appDanger, size: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text("PROFILE_AUTH_ERROR_TITLE")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)

                Text(message)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)

            Button {
                onDismiss()
            } label: {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(width: 28, height: 28)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Dismiss login error")
        }
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(Color.appDanger.opacity(0.1))
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .stroke(Color.appDanger.opacity(0.18), lineWidth: 1)
        )
    }
}

// MARK: - Sign-in provider components

/// A full-width, modern sign-in row that pairs a leading brand mark with a
/// centered title. Marks (Google, Microsoft, …) keep their own colors while
/// the chrome adapts to the requested emphasis.
private struct AuthProviderButton<Leading: View>: View {
    let title: LocalizedStringKey
    var emphasis: AuthProviderButtonStyle.Emphasis = .neutral
    var isDisabled: Bool = false
    let action: () -> Void
    private let leading: Leading

    init(
        title: LocalizedStringKey,
        emphasis: AuthProviderButtonStyle.Emphasis = .neutral,
        isDisabled: Bool = false,
        action: @escaping () -> Void,
        @ViewBuilder leading: () -> Leading
    ) {
        self.title = title
        self.emphasis = emphasis
        self.isDisabled = isDisabled
        self.action = action
        self.leading = leading()
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                leading
                    .frame(width: 22, height: 22)

                Spacer(minLength: 8)

                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(titleColor)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)

                Spacer(minLength: 8)

                // Balances the leading mark so the title stays optically centered.
                Color.clear.frame(width: 22, height: 22)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 16)
        }
        .buttonStyle(AuthProviderButtonStyle(emphasis: emphasis))
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.55 : 1)
    }

    private var titleColor: Color {
        switch emphasis {
        case .primary: return .white
        case .neutral: return .primary
        case .tinted: return .appPrimary
        }
    }
}

private struct AuthProviderButtonStyle: ButtonStyle {
    enum Emphasis {
        case primary
        case neutral
        case tinted
    }

    var emphasis: Emphasis

    func makeBody(configuration: Configuration) -> some View {
        // Wrap in a View so `@Environment(\.colorScheme)` resolves reliably
        // (reading it directly inside a ButtonStyle is not dependable).
        Chrome(emphasis: emphasis, isPressed: configuration.isPressed) {
            configuration.label
        }
    }

    private struct Chrome<Label: View>: View {
        let emphasis: Emphasis
        let isPressed: Bool
        @ViewBuilder var label: Label
        @Environment(\.colorScheme) private var colorScheme

        var body: some View {
            label
                .frame(maxWidth: .infinity, minHeight: 54)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .fill(fillStyle)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                        .stroke(strokeColor, lineWidth: 1)
                )
                .shadow(color: shadowColor, radius: 10, y: 5)
                .scaleEffect(isPressed ? 0.985 : 1)
                .opacity(isPressed ? 0.95 : 1)
                .animation(.easeOut(duration: 0.16), value: isPressed)
        }

        private var fillStyle: AnyShapeStyle {
            switch emphasis {
            case .primary:
                return AnyShapeStyle(
                    LinearGradient(
                        colors: [Color.appPrimary, Color.appPrimary.opacity(0.86)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            case .neutral:
                return AnyShapeStyle(Color.appSurfaceElevated)
            case .tinted:
                return AnyShapeStyle(Color.appPrimary.opacity(colorScheme == .dark ? 0.16 : 0.10))
            }
        }

        private var strokeColor: Color {
            switch emphasis {
            case .primary: return .clear
            case .neutral: return Color.appBorder
            case .tinted: return Color.appPrimary.opacity(0.28)
            }
        }

        private var shadowColor: Color {
            guard emphasis == .primary, colorScheme == .light else { return .clear }
            return Color.appPrimary.opacity(0.28)
        }
    }
}

/// The official multi-color Google "G" logo, shipped as a vector asset
/// ("GoogleG" in Assets.xcassets). `.original` keeps the brand colors.
private struct GoogleGMark: View {
    var body: some View {
        Image("GoogleG")
            .renderingMode(.original)
            .resizable()
            .scaledToFit()
    }
}

/// The Microsoft four-square mark in its official tile colors.
private struct MicrosoftLogoMark: View {
    private let red = Color(red: 0.949, green: 0.314, blue: 0.133)
    private let green = Color(red: 0.498, green: 0.729, blue: 0.0)
    private let blue = Color(red: 0.0, green: 0.643, blue: 0.937)
    private let yellow = Color(red: 1.0, green: 0.725, blue: 0.0)

    var body: some View {
        VStack(spacing: 2) {
            HStack(spacing: 2) {
                Rectangle().fill(red)
                Rectangle().fill(green)
            }
            HStack(spacing: 2) {
                Rectangle().fill(blue)
                Rectangle().fill(yellow)
            }
        }
    }
}

// MARK: - Phone verification

/// Two-step phone sign-in: number entry → segmented one-time-code entry with a
/// live resend countdown. Owns its own timer/focus state.
private struct PhoneVerificationPanel: View {
    @ObservedObject var viewModel: ProfileViewModel
    @State private var resendCountdown = 0
    // Onboarding/Android parity: pick a country dial code and normalize to E.164 locally before
    // handing the number to ProfileViewModel, so "0555…" no longer becomes an invalid E.164.
    @State private var country: PhoneCountry = PhoneCountry.defaultCountry()
    @State private var nationalNumber = ""

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var trimmedNumber: String {
        viewModel.phoneSignInNumber.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// The entered national number in E.164. A leading "+" overrides the country selection (matches
    /// a pasted contact-card number); the national trunk '0' is dropped. Mirrors
    /// OnboardingPhoneVerification.e164Number.
    private var composedE164: String {
        let raw = nationalNumber.trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.hasPrefix("+") {
            return "+" + raw.dropFirst().filter(\.isNumber)
        }
        var digits = raw.filter(\.isNumber)
        if digits.hasPrefix("0") { digits.removeFirst() }
        return "+\(country.dialCode)\(digits)"
    }

    private var codeSentText: String {
        String(
            format: NSLocalizedString("PROFILE_PHONE_CODE_SENT_FORMAT", comment: ""),
            trimmedNumber
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 8) {
                Image(systemName: "phone.fill")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.appPrimary)
                Text("PROFILE_LOGIN_PHONE_TITLE")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
                Spacer(minLength: 0)
            }

            if viewModel.isAwaitingPhoneCode {
                awaitingState
            } else {
                entryState
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(Color.appSurfaceMuted)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .onChange(of: viewModel.isAwaitingPhoneCode) { _, awaiting in
            if awaiting { resendCountdown = 30 }
        }
        .onReceive(ticker) { _ in
            if resendCountdown > 0 { resendCountdown -= 1 }
        }
    }

    private var entryState: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Menu {
                    ForEach(PhoneCountry.all) { entry in
                        Button("\(entry.flag) \(entry.name) (+\(entry.dialCode))") {
                            country = entry
                        }
                    }
                } label: {
                    HStack(spacing: 4) {
                        Text(country.flag)
                        Text("+\(country.dialCode)")
                            .font(.body.monospacedDigit())
                            .foregroundStyle(.primary)
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption2)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 14)
                    .background(numberFieldBackground)
                }

                TextField("PROFILE_PHONE_PLACEHOLDER", text: $nationalNumber)
                    .keyboardType(.phonePad)
                    .textContentType(.telephoneNumber)
                    .autocorrectionDisabled()
                    .font(.body)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 14)
                    .background(numberFieldBackground)
            }

            Text("PROFILE_PHONE_HELP")
                .font(.caption2)
                .foregroundStyle(Color.appTextSecondary)

            Button {
                // Compose E.164 locally, then hand it to the existing sign-in path (which reads
                // viewModel.phoneSignInNumber verbatim). Resend/confirm reuse this stored value.
                viewModel.phoneSignInNumber = composedE164
                viewModel.startPhoneSignIn()
            } label: {
                Label("PROFILE_PHONE_SEND_CODE", systemImage: "paperplane.fill")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle())
            .disabled(viewModel.isAuthLoading || nationalNumber.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }

    private var numberFieldBackground: some View {
        RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
            .fill(Color.appSurfaceElevated)
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
    }

    private var awaitingState: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(codeSentText)
                .font(.caption)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)

            OTPCodeField(code: $viewModel.phoneVerificationCode, length: 6)

            Button {
                viewModel.confirmPhoneCode()
            } label: {
                HStack(spacing: 8) {
                    if viewModel.isAuthLoading {
                        ProgressView().tint(.white)
                    }
                    Label("PROFILE_PHONE_VERIFY", systemImage: "checkmark.shield.fill")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle())
            .disabled(viewModel.isAuthLoading || viewModel.phoneVerificationCode.count < 6)

            HStack(spacing: 0) {
                resendControl
                Spacer(minLength: 12)
                Button {
                    viewModel.cancelPhoneSignIn()
                } label: {
                    Text("PROFILE_PHONE_CHANGE_NUMBER")
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)
                }
                .buttonStyle(.plain)
                .disabled(viewModel.isAuthLoading)
            }
        }
    }

    @ViewBuilder
    private var resendControl: some View {
        if resendCountdown > 0 {
            Text(
                String(
                    format: NSLocalizedString("PROFILE_PHONE_RESEND_IN_FORMAT", comment: ""),
                    resendCountdown
                )
            )
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.appTextSecondary)
            .monospacedDigit()
        } else {
            Button {
                viewModel.startPhoneSignIn()
                resendCountdown = 30
            } label: {
                Text("PROFILE_PHONE_RESEND")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appPrimary)
            }
            .buttonStyle(.plain)
            .disabled(viewModel.isAuthLoading)
        }
    }
}

/// Segmented one-time-code entry. An invisible text field captures keyboard,
/// paste, and SMS autofill while the boxes below render each digit.
private struct OTPCodeField: View {
    @Binding var code: String
    var length: Int = 6

    @FocusState private var isFocused: Bool

    var body: some View {
        ZStack {
            HStack(spacing: 8) {
                ForEach(0..<length, id: \.self) { index in
                    digitBox(at: index)
                }
            }
            .allowsHitTesting(false)

            // Real input lives here but is rendered (near-)invisible; the boxes
            // above are the visible representation of `code`.
            TextField("", text: $code)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .focused($isFocused)
                .tint(.clear)
                .opacity(0.02)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .onChange(of: code) { _, newValue in
                    let filtered = String(newValue.filter(\.isNumber).prefix(length))
                    if filtered != newValue { code = filtered }
                }
        }
        .frame(maxWidth: .infinity)
        .contentShape(Rectangle())
        .onTapGesture { isFocused = true }
        .onAppear {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { isFocused = true }
        }
    }

    private func digitBox(at index: Int) -> some View {
        let digits = Array(code)
        let isFilled = index < digits.count
        let isActive = isFocused
            && (index == digits.count || (index == length - 1 && digits.count == length))

        return Text(isFilled ? String(digits[index]) : "")
            .font(.title2.weight(.bold))
            .foregroundStyle(.primary)
            .frame(maxWidth: .infinity)
            .frame(height: 54)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(
                        isActive
                            ? Color.appPrimary
                            : (isFilled ? Color.appPrimary.opacity(0.4) : Color.appBorder),
                        lineWidth: isActive ? 2 : 1
                    )
            )
            .animation(.easeOut(duration: 0.15), value: isActive)
    }
}

#Preview {
    NavigationStack { ProfileView() }
}
