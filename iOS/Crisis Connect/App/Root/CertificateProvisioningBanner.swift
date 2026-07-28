//
//  CertificateProvisioningBanner.swift
//  Crisis Connect
//
//  Floating banner near the top of the screen that surfaces automatic
//  rescue-certificate provisioning outcomes (in progress, success, failure),
//  mirroring Android's CertificateProvisioningBanner. Mounted once at the
//  ContentView root so it floats above whatever screen is open.
//
//  Behaviour:
//   - Observes CertificateProvisioningNotifier.
//   - Auto-dismisses after a state-specific timeout.
//   - Replacing an in-progress event with a terminal one restarts the timer.
//   - User can dismiss manually with the close button.
//

import SwiftUI

struct CertificateProvisioningBannerHost: View {
    @ObservedObject private var notifier = CertificateProvisioningNotifier.shared

    var body: some View {
        VStack {
            if let event = notifier.event {
                CertificateProvisioningBannerCard(event: event) {
                    notifier.dismiss()
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .task(id: notifier.sequence) {
                    let delay = Self.autoDismissSeconds(for: event)
                    try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                    guard !Task.isCancelled else { return }
                    notifier.dismiss()
                }
            }
        }
        .animation(.spring(duration: 0.35), value: notifier.event)
        .allowsHitTesting(notifier.event != nil)
    }

    private static func autoDismissSeconds(for event: CertificateProvisioningNotifier.Event) -> Double {
        switch event {
        case .inProgress: return 12
        case .success: return 4.5
        case .failure: return 7
        }
    }
}

private struct CertificateProvisioningBannerCard: View {
    let event: CertificateProvisioningNotifier.Event
    let onDismiss: () -> Void

    private var accent: Color {
        switch event {
        case .inProgress: return .appPrimary
        case .success: return .appSuccess
        case .failure: return .appDanger
        }
    }

    private var iconName: String {
        switch event {
        case .inProgress: return "checkmark.shield"
        case .success: return "checkmark.circle"
        case .failure: return "exclamationmark.triangle"
        }
    }

    private var title: LocalizedStringKey {
        switch event {
        case .inProgress: return "CERT_BANNER_IN_PROGRESS_TITLE"
        case .success: return "CERT_BANNER_SUCCESS_TITLE"
        case .failure: return "CERT_BANNER_FAILURE_TITLE"
        }
    }

    private var bodyText: Text {
        switch event {
        case .inProgress:
            return Text("CERT_BANNER_IN_PROGRESS_BODY")
        case .success:
            return Text("CERT_BANNER_SUCCESS_BODY")
        case .failure(let detail):
            if let detail, !detail.isEmpty {
                return Text(
                    String(
                        format: NSLocalizedString("CERT_BANNER_FAILURE_BODY_WITH_DETAIL", comment: ""),
                        detail
                    )
                )
            }
            return Text("CERT_BANNER_FAILURE_BODY_GENERIC")
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .center, spacing: 12) {
                ZStack {
                    Circle()
                        .fill(accent.opacity(0.16))
                        .frame(width: 40, height: 40)
                    if case .inProgress = event {
                        ProgressView()
                            .controlSize(.small)
                            .tint(accent)
                    } else {
                        Image(systemName: iconName)
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(accent)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    bodyText
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                        .lineLimit(3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(Color.appTextSecondary)
                        .frame(width: 32, height: 32)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("CERT_BANNER_DISMISS"))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)

            Rectangle()
                .fill(accent.opacity(0.4))
                .frame(height: 3)
        }
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous))
        .shadow(color: accent.opacity(0.22), radius: 10, y: 4)
        .accessibilityElement(children: .combine)
    }
}
