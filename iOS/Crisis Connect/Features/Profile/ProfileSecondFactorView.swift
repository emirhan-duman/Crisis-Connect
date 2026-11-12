import SwiftUI

/// Keeps the Firebase resolver and all authentication mutations in the existing view model.
struct ProfileSecondFactorView: View {
    @ObservedObject var viewModel: ProfileViewModel
    let onCancel: () -> Void
    @State private var code = ""

    var body: some View {
        ProfileSecondFactorForm(
            factors: viewModel.secondFactors,
            selectedFactorID: viewModel.selectedSecondFactorID,
            selectedIsPhone: viewModel.selectedSecondFactorIsPhone,
            codeSent: viewModel.secondFactorCodeSent,
            isLoading: viewModel.isAuthLoading,
            errorMessage: viewModel.authErrorMessage,
            code: $code,
            onSelectFactor: viewModel.selectSecondFactor,
            onSendSms: viewModel.sendSecondFactorSms,
            onVerify: { viewModel.verifySecondFactor(code: $0) },
            onCancel: onCancel
        )
    }
}

/// Presentational only: callbacks never run during layout, previews, or state restoration.
struct ProfileSecondFactorForm: View {
    let factors: [ProfileSecondFactorOption]
    let selectedFactorID: String
    let selectedIsPhone: Bool
    let codeSent: Bool
    let isLoading: Bool
    let errorMessage: String?
    @Binding var code: String
    let onSelectFactor: (String) -> Void
    let onSendSms: () -> Void
    let onVerify: (String) -> Void
    let onCancel: () -> Void

    @Environment(\.locale) private var locale
    @Environment(\.colorScheme) private var colorScheme
    @FocusState private var isCodeFocused: Bool

    private var selectedFactor: ProfileSecondFactorOption? {
        factors.first { $0.id == selectedFactorID }
    }

    var canEnterCode: Bool {
        selectedFactor != nil && (!selectedIsPhone || codeSent)
    }

    var canVerify: Bool {
        canEnterCode && !isLoading && code.count == 6 && code.allSatisfy { $0 >= "0" && $0 <= "9" }
    }

    private var primaryEnabled: Bool {
        !isLoading && selectedFactor != nil && ((selectedIsPhone && !codeSent) || canVerify)
    }

    static func normalizedCode(_ value: String) -> String {
        String(value.filter { $0 >= "0" && $0 <= "9" }.prefix(6))
    }

    private var messageKey: String {
        guard selectedFactor != nil else { return "PROFILE_MFA_UNSUPPORTED" }
        if !selectedIsPhone { return "PROFILE_MFA_TOTP_MESSAGE" }
        return codeSent ? "PROFILE_MFA_SMS_SENT_MESSAGE" : "PROFILE_MFA_SMS_MESSAGE"
    }

    var body: some View {
        ZStack {
            Color.appSurfaceElevated.ignoresSafeArea()
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    header
                    if !factors.isEmpty { methods }
                    if let errorMessage, !errorMessage.isEmpty { errorBanner(errorMessage) }
                    if canEnterCode { codeEntry }
                }
                .frame(maxWidth: 480, alignment: .leading)
                .padding(.horizontal, 24)
                .padding(.top, 20)
                .padding(.bottom, 24)
                .frame(maxWidth: .infinity, alignment: .top)
            }
            .scrollIndicators(.hidden)
            .scrollDismissesKeyboard(.interactively)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) { actions }
        .environment(\.layoutDirection, locale.language.characterDirection == .rightToLeft ? .rightToLeft : .leftToRight)
        .onChange(of: selectedFactorID) { _, _ in
            code = ""
            isCodeFocused = false
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack {
                AppIconBadge(systemName: "lock.shield", tint: .appPrimary, size: 52)
                    .accessibilityHidden(true)
                Spacer(minLength: 16)
                Button(action: onCancel) {
                    Image(systemName: "xmark")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(Color.appSurfaceMuted))
                }
                .buttonStyle(.plain)
                .disabled(isLoading)
                .accessibilityLabel("COMMON_CANCEL")
                .accessibilityIdentifier("profile.mfa.close")
            }
            VStack(alignment: .leading, spacing: 8) {
                Text("PROFILE_MFA_TITLE")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(.primary)
                    .accessibilityAddTraits(.isHeader)
                Text(LocalizedStringKey(messageKey))
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var methods: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("PROFILE_MFA_METHOD_TITLE")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
            ForEach(Array(factors.enumerated()), id: \.element.id) { index, factor in
                if factors.count > 1 {
                    Button {
                        guard !isLoading, factor.id != selectedFactorID else { return }
                        code = ""
                        isCodeFocused = false
                        onSelectFactor(factor.id)
                    } label: {
                        methodRow(factor, index: index)
                    }
                    .buttonStyle(.plain)
                    .disabled(isLoading)
                    .accessibilityAddTraits(factor.id == selectedFactorID ? .isSelected : [])
                    .accessibilityIdentifier("profile.mfa.method.\(index)")
                } else {
                    methodRow(factor, index: index)
                }
            }
        }
    }

    private func methodRow(_ factor: ProfileSecondFactorOption, index: Int) -> some View {
        let isSelected = factor.id == selectedFactorID
        let methodKey = factor.isPhone ? "PROFILE_MFA_SMS" : "PROFILE_MFA_AUTHENTICATOR"
        let customName = factor.name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let hasSameType = factors.filter { $0.isPhone == factor.isPhone }.count > 1
        return HStack(spacing: 12) {
            Image(systemName: factor.isPhone ? "message" : "key.horizontal")
                .font(.body.weight(.medium))
                .foregroundStyle(isSelected ? Color.appPrimary : Color.appTextSecondary)
                .frame(width: 24)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Group {
                    if customName.isEmpty {
                        if hasSameType {
                            Text(LocalizedStringKey(methodKey)) + Text(" \(index + 1)")
                        } else {
                            Text(LocalizedStringKey(methodKey))
                        }
                    } else {
                        Text(verbatim: customName)
                    }
                }
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                if !customName.isEmpty {
                    Text(LocalizedStringKey(methodKey))
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            Spacer(minLength: 8)
            Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                .font(.title3)
                .foregroundStyle(isSelected ? Color.appPrimary : Color.appTextSecondary)
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity, minHeight: 28, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
            .fill(isSelected ? Color.appPrimarySoft : Color.appSurfaceMuted))
        .overlay(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
            .stroke(isSelected ? Color.appPrimary.opacity(0.45) : Color.appBorder, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous))
        .accessibilityElement(children: .combine)
    }

    private var codeEntry: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("PROFILE_MFA_CODE")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
            TextField("", text: Binding(get: { code }, set: { code = Self.normalizedCode($0) }),
                      prompt: Text("••••••").foregroundStyle(Color.appTextSecondary.opacity(0.5)))
                .font(.title2.monospaced().weight(.medium))
                .tracking(8)
                .keyboardType(.numberPad)
                .textContentType(.oneTimeCode)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($isCodeFocused)
                .submitLabel(.go)
                .onSubmit(submit)
                .disabled(isLoading)
                .padding(.horizontal, 16)
                .frame(minHeight: 60)
                .background(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(Color.appSurfaceMuted))
                .overlay(RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(isCodeFocused ? Color.appPrimary : Color.appBorder, lineWidth: isCodeFocused ? 2 : 1))
                .environment(\.layoutDirection, .leftToRight)
                .accessibilityLabel("PROFILE_MFA_CODE")
                .accessibilityIdentifier("profile.mfa.code")
            if selectedIsPhone && codeSent {
                Button {
                    code = ""
                    isCodeFocused = false
                    onSendSms()
                } label: {
                    Text("PROFILE_MFA_RESEND")
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.plain)
                .foregroundStyle(Color.appPrimary)
                .disabled(isLoading)
                .accessibilityIdentifier("profile.mfa.resend")
            }
        }
    }

    private func errorBanner(_ message: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.circle.fill")
                .accessibilityHidden(true)
            Text(message)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.subheadline)
        .foregroundStyle(Color.appDanger)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(RoundedRectangle(cornerRadius: AppTheme.cornerSmall, style: .continuous)
            .fill(Color.appDanger.opacity(0.08)))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("profile.mfa.error")
    }

    private var actions: some View {
        VStack(spacing: 8) {
            if selectedFactor != nil {
                Button {
                    if selectedIsPhone && !codeSent {
                        guard !isLoading else { return }
                        code = ""
                        onSendSms()
                    } else {
                        submit()
                    }
                } label: {
                    HStack(spacing: 10) {
                        if isLoading { ProgressView().tint(.white) }
                        Text(LocalizedStringKey(selectedIsPhone && !codeSent ? "PROFILE_MFA_SEND" : "PROFILE_MFA_VERIFY"))
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity, minHeight: 24)
                }
                .buttonStyle(AppPrimaryButtonStyle(fill: Color.appPrimary.opacity(colorScheme == .dark ? 0.78 : 1)))
                .disabled(!primaryEnabled)
                .opacity(primaryEnabled || isLoading ? 1 : 0.45)
                .accessibilityIdentifier("profile.mfa.primary")
            }
            Button(role: .cancel, action: onCancel) {
                Text("COMMON_CANCEL")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isLoading)
            .accessibilityIdentifier("profile.mfa.cancel")
        }
        .frame(maxWidth: 480)
        .padding(.horizontal, 24)
        .padding(.top, 16)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(alignment: .top) {
            Color.appSurfaceElevated.ignoresSafeArea(edges: .bottom)
                .overlay(alignment: .top) { Rectangle().fill(Color.appBorder).frame(height: 1) }
        }
    }

    private func submit() {
        guard canVerify else { return }
        isCodeFocused = false
        onVerify(code)
    }
}
