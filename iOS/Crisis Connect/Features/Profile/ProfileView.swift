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

struct ProfileView: View {
    @Environment(\.modelContext) private var context
    @StateObject private var viewModel = ProfileViewModel()
    @State private var isEmailSignInSheetPresented = false
    @State private var isNameEditorPresented = false
    @State private var draftFullName = ""

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollView {
                VStack(spacing: 20) {
                    profileIdentitySection
                    accountDetailsSection
                    nameEditorSection
                    if isSignedIn {
                        signedInStateBanner
                    }
                    if shouldShowManagedVerificationNotice {
                        managedVerificationNotice
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
        .onAppear { viewModel.loadIfNeeded(context: context) }
        .onDisappear {
            viewModel.handleDisappear(context: context)
        }
        .onChange(of: viewModel.pickerItem) { _, newValue in
            Task {
                await viewModel.loadImage(from: newValue)
                await MainActor.run { viewModel.scheduleAutosave(context: context) }
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
            avatarView

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
            title: "Account Details",
            subtitle: "Core profile fields synced across contact sharing and rescue coordination.",
            systemImage: "person.text.rectangle",
            tint: .appPrimary
        ) {
            VStack(spacing: 14) {
                profileInfoRow(
                    label: "Username",
                    value: currentNameDisplay
                )
                profileInfoRow(
                    label: "Email",
                    value: currentEmailDisplay
                )
                profileInfoRow(
                    label: "Country",
                    value: currentCountryDisplay
                )

                if showsManagedDetails {
                    profileInfoRow(
                        label: "Agency",
                        value: currentAgencyDisplay
                    )
                    profileInfoRow(
                        label: "Role",
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
            title: "Display Name",
            subtitle: "Use a clear name that other people see in chats and QR adds.",
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
            label: "Verification is managed by the server.",
            tint: .appPrimary,
            background: Color.appPrimary.opacity(0.12)
        )
    }

    private var authActionsSection: some View {
        sectionCard(
            title: "Access",
            subtitle: "Connect or disconnect the account used for verified profile sync.",
            systemImage: "person.crop.circle.badge.checkmark",
            tint: .appPrimary
        ) {
            if viewModel.isAnonymous || viewModel.authStatusText == "PROFILE_AUTH_SIGNED_OUT" {
                if let authErrorMessage = viewModel.authErrorMessage,
                   authErrorMessage.isEmpty == false,
                   !isEmailSignInSheetPresented {
                    ProfileAuthErrorBanner(message: authErrorMessage) {
                        viewModel.clearAuthError()
                    }
                }

                Button {
                    presentEmailSignInSheet()
                } label: {
                    Label("PROFILE_LOGIN_EMAIL", systemImage: "envelope")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppSecondaryButtonStyle())
                .disabled(viewModel.isAuthLoading)

                Button {
                    viewModel.signInWithGoogle()
                } label: {
                    Label("PROFILE_LOGIN_GOOGLE", systemImage: "globe")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle())
                .disabled(viewModel.isAuthLoading)

                SignInWithAppleButton(.signIn) { request in
                    viewModel.prepareAppleSignInRequest(request)
                } onCompletion: { result in
                    viewModel.handleAppleSignInCompletion(result)
                }
                .signInWithAppleButtonStyle(.black)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .clipShape(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous))
                .disabled(viewModel.isAuthLoading)
            } else {
                Button {
                    viewModel.signOut()
                } label: {
                    Label("PROFILE_LOGOUT", systemImage: "rectangle.portrait.and.arrow.right")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppSecondaryButtonStyle())
                .disabled(viewModel.isAuthLoading)
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

    private func presentNameEditor() {
        draftFullName = viewModel.fullName
        isNameEditorPresented = true
    }

    private func saveEditedName() {
        viewModel.fullName = draftFullName.trimmingCharacters(in: .whitespacesAndNewlines)
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
        return trimmed.isEmpty ? "Not set" : trimmed
    }

    private var currentEmailDisplay: String {
        let trimmed = viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Not set" : trimmed
    }

    private var currentCountryDisplay: String {
        let trimmed = viewModel.country.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Not set" : trimmed
    }

    private var currentAgencyDisplay: String {
        let trimmed = viewModel.agency.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "Not set" : trimmed
    }

    private var headerSecondaryText: String {
        let trimmedEmail = viewModel.email.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedEmail.isEmpty ? authStatusDisplay : trimmedEmail
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

#Preview {
    NavigationStack { ProfileView() }
}
