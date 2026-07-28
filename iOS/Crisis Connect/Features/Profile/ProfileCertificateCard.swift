//
//  ProfileCertificateCard.swift
//  Crisis Connect
//
//  SwiftUI port of Android's `ProfileCertificateCard` (screens/settings).
//  Renders the device-bound rescue certificate. State and actions live on
//  `ProfileViewModel` so the profile header (role chip, verified badge) and the
//  rescue gating stay in sync with revoke / provision actions taken here.
//

import SwiftUI

struct ProfileCertificateCard: View {
    @ObservedObject var viewModel: ProfileViewModel
    @State private var confirmRevoke = false

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            header

            content

            if let statusMessage = viewModel.certificateState.statusMessage {
                infoBanner(text: statusMessage, isError: false)
            }
            if let errorMessage = viewModel.certificateState.errorMessage {
                infoBanner(text: errorMessage, isError: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .regular, padding: 18)
        .task { viewModel.refreshCertificate() }
        .alert(
            Text("PROFILE_CERT_REVOKE_TITLE"),
            isPresented: $confirmRevoke
        ) {
            Button("PROFILE_CERT_REVOKE_CONFIRM", role: .destructive) {
                viewModel.revokeCertificate(reason: "user_request")
            }
            Button("PROFILE_CERT_CANCEL", role: .cancel) {}
        } message: {
            Text("PROFILE_CERT_REVOKE_BODY")
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack(alignment: .top, spacing: 12) {
            AppIconBadge(systemName: "checkmark.shield.fill", tint: .appPrimary)

            VStack(alignment: .leading, spacing: 4) {
                Text("PROFILE_CERT_CARD_TITLE")
                    .font(.headline.weight(.semibold))
                Text("PROFILE_CERT_CARD_SUBTITLE")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 8)

            if case let .loaded(_, isExpired, isRevoked) = viewModel.certificateState.status {
                statusChip(isExpired: isExpired, isRevoked: isRevoked)
            }
        }
    }

    private func statusChip(isExpired: Bool, isRevoked: Bool) -> some View {
        let label: LocalizedStringKey
        let tint: Color
        let symbol: String
        if isRevoked {
            label = "PROFILE_CERT_STATUS_REVOKED"; tint = .appDanger; symbol = "xmark.circle.fill"
        } else if isExpired {
            label = "PROFILE_CERT_STATUS_EXPIRED"; tint = .appWarning; symbol = "clock.fill"
        } else {
            label = "PROFILE_CERT_STATUS_ACTIVE"; tint = .appSuccess; symbol = "checkmark.circle.fill"
        }
        return HStack(spacing: 6) {
            Image(systemName: symbol)
                .font(.caption2.weight(.bold))
            Text(label)
                .font(.caption.weight(.semibold))
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Capsule(style: .continuous).fill(tint.opacity(0.14)))
        .overlay(Capsule(style: .continuous).stroke(tint.opacity(0.24), lineWidth: 1))
        .fixedSize()
    }

    // MARK: - State content

    @ViewBuilder
    private var content: some View {
        switch viewModel.certificateState.status {
        case .loading:
            loadingBlock
        case .missing:
            missingBlock
        case let .loaded(certificate, isExpired, isRevoked):
            loadedBlock(certificate: certificate, isExpired: isExpired, isRevoked: isRevoked)
        case let .failure(message):
            failureBlock(message: message)
        }
    }

    private var loadingBlock: some View {
        HStack(spacing: 10) {
            ProgressView()
            Text("PROFILE_CERT_LOADING")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
        }
    }

    private var missingBlock: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("PROFILE_CERT_MISSING_BODY")
                .font(.subheadline)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)

            Button {
                viewModel.provisionCertificate()
            } label: {
                busyLabel(
                    isBusy: viewModel.certificateState.provisioning,
                    busyTitle: "PROFILE_CERT_REQUEST_IN_PROGRESS",
                    title: "PROFILE_CERT_REQUEST_BUTTON",
                    systemImage: "lock.fill",
                    onPrimary: true
                )
            }
            .buttonStyle(AppPrimaryButtonStyle())
            .disabled(viewModel.certificateState.provisioning)
        }
    }

    private func loadedBlock(certificate: RoleCertificate, isExpired: Bool, isRevoked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 14) {
            hero(certificate: certificate, isExpired: isExpired, isRevoked: isRevoked)

            VStack(spacing: 8) {
                detailRow(
                    label: "PROFILE_CERT_ROLE_LABEL",
                    value: certificate.role.prefix(1).uppercased() + certificate.role.dropFirst()
                )
                detailRow(label: "PROFILE_CERT_DEVICE_LABEL", value: certificate.deviceId, mono: true)
                detailRow(label: "PROFILE_CERT_ISSUED_LABEL", value: Self.formatDate(certificate.issuedAtMillis))
                detailRow(
                    label: "PROFILE_CERT_EXPIRES_LABEL",
                    value: Self.formatDate(certificate.expiresAtMillis),
                    highlight: expiryHighlight(certificate: certificate, isExpired: isExpired)
                )
            }

            Divider().overlay(Color.appBorder)

            Text("PROFILE_CERT_MANAGE_LABEL")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)
                .textCase(.uppercase)

            manageActions(isExpired: isExpired, isRevoked: isRevoked)
        }
    }

    @ViewBuilder
    private func manageActions(isExpired: Bool, isRevoked: Bool) -> some View {
        let canRenew = isExpired || isRevoked
        HStack(spacing: 10) {
            if canRenew {
                Button {
                    viewModel.provisionCertificate()
                } label: {
                    busyLabel(
                        isBusy: viewModel.certificateState.provisioning,
                        busyTitle: "PROFILE_CERT_REQUEST_IN_PROGRESS",
                        title: "PROFILE_CERT_RENEW_BUTTON",
                        systemImage: "arrow.clockwise",
                        onPrimary: true
                    )
                }
                .buttonStyle(AppPrimaryButtonStyle())
                .disabled(viewModel.certificateState.provisioning)
            } else {
                Button {
                    viewModel.refreshCertificate()
                } label: {
                    Label("PROFILE_CERT_REFRESH_BUTTON", systemImage: "arrow.clockwise")
                }
                .buttonStyle(AppSecondaryButtonStyle())
            }

            Button {
                confirmRevoke = true
            } label: {
                busyLabel(
                    isBusy: viewModel.certificateState.revoking,
                    busyTitle: "PROFILE_CERT_REVOKE_BUTTON",
                    title: "PROFILE_CERT_REVOKE_BUTTON",
                    systemImage: "xmark.circle",
                    onPrimary: false,
                    tint: .appDanger
                )
            }
            .buttonStyle(AppSecondaryButtonStyle(tint: .appDanger))
            .disabled(viewModel.certificateState.revoking || isRevoked)
        }
    }

    /// Prominent success/warning banner so a freshly issued certificate is
    /// impossible to miss. Green = active (with the live "expires in …" time),
    /// amber = expired, red = revoked.
    private func hero(certificate: RoleCertificate, isExpired: Bool, isRevoked: Bool) -> some View {
        let tint: Color
        let symbol: String
        let title: LocalizedStringKey
        let subtitle: String
        if isRevoked {
            tint = .appDanger
            symbol = "xmark.seal.fill"
            title = "PROFILE_CERT_REVOKED_HEADLINE"
            subtitle = NSLocalizedString("PROFILE_CERT_INACTIVE_SUBTITLE", comment: "")
        } else if isExpired {
            tint = .appWarning
            symbol = "clock.fill"
            title = "PROFILE_CERT_EXPIRED_HEADLINE"
            subtitle = NSLocalizedString("PROFILE_CERT_INACTIVE_SUBTITLE", comment: "")
        } else {
            tint = .appSuccess
            symbol = "checkmark.seal.fill"
            title = "PROFILE_CERT_ACTIVE_HEADLINE"
            subtitle = String(
                format: NSLocalizedString("PROFILE_CERT_EXPIRES_RELATIVE", comment: ""),
                Self.relativeExpiry(certificate.expiresAtMillis)
            )
        }

        return HStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 30, weight: .semibold))
                .foregroundStyle(tint)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline.weight(.bold))
                Text(subtitle)
                    .font(.subheadline)
            }
            Spacer(minLength: 0)
        }
        .foregroundStyle(tint)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(tint.opacity(0.14))
        )
    }

    private func failureBlock(message: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(Color.appDanger)
                Text("PROFILE_CERT_FAILURE_TITLE")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appDanger)
            }
            Text(message)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Button {
                viewModel.refreshCertificate()
            } label: {
                Label("PROFILE_CERT_RETRY_BUTTON", systemImage: "arrow.clockwise")
            }
            .buttonStyle(AppSecondaryButtonStyle())
        }
    }

    // MARK: - Rows & banners

    private func detailRow(
        label: LocalizedStringKey,
        value: String,
        mono: Bool = false,
        highlight: Color? = nil
    ) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.appTextSecondary)
                .frame(minWidth: 96, alignment: .leading)
            Text(value)
                .font(mono ? .system(.subheadline, design: .monospaced) : .subheadline.weight(highlight != nil ? .semibold : .regular))
                .foregroundStyle(highlight ?? .primary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
        }
    }

    private func infoBanner(text: String, isError: Bool) -> some View {
        let tint: Color = isError ? .appDanger : .appSuccess
        return HStack(spacing: 8) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .font(.subheadline)
            Text(text)
                .font(.footnote)
                .frame(maxWidth: .infinity, alignment: .leading)
            Button("PROFILE_CERT_DISMISS") {
                viewModel.consumeCertificateMessages()
            }
            .font(.footnote.weight(.semibold))
            .buttonStyle(.plain)
        }
        .foregroundStyle(tint)
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                .fill(tint.opacity(0.12))
        )
    }

    // MARK: - Helpers

    @ViewBuilder
    private func busyLabel(
        isBusy: Bool,
        busyTitle: LocalizedStringKey,
        title: LocalizedStringKey,
        systemImage: String,
        onPrimary: Bool,
        tint: Color = .appPrimary
    ) -> some View {
        if isBusy {
            HStack(spacing: 8) {
                ProgressView()
                    .tint(onPrimary ? Color.white : tint)
                Text(busyTitle)
            }
        } else {
            Label(title, systemImage: systemImage)
        }
    }

    private func expiryHighlight(certificate: RoleCertificate, isExpired: Bool) -> Color? {
        if isExpired {
            return .appDanger
        }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let dayMillis: Int64 = 24 * 60 * 60 * 1000
        if certificate.expiresAtMillis - now < dayMillis {
            return .appWarning
        }
        return nil
    }

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    private static let relativeFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter
    }()

    private static func formatDate(_ millis: Int64) -> String {
        dateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    private static func relativeExpiry(_ millis: Int64) -> String {
        relativeFormatter.localizedString(
            for: Date(timeIntervalSince1970: Double(millis) / 1000),
            relativeTo: Date()
        )
    }
}
