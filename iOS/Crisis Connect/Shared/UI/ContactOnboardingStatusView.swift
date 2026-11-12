import SwiftUI

enum ContactOnboardingStage: Equatable {
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
}

/// Presentation only: verification, persistence, and dismissal remain with the caller.
struct ContactOnboardingStatusView: View {
    let stage: ContactOnboardingStage
    let contactName: String?
    var onCancel: (() -> Void)? = nil

    private var showsCancel: Bool { stage.isBlocking && onCancel != nil }

    var body: some View {
        if stage != .idle {
            VStack(alignment: .trailing, spacing: 4) {
                HStack(alignment: .top, spacing: 12) {
                    statusIndicator
                    VStack(alignment: .leading, spacing: 4) {
                        Text(LocalizedStringKey(stage.titleKey))
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .accessibilityAddTraits(.isHeader)
                        Text(LocalizedStringKey(stage.messageKey))
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                        if let name = contactName?.trimmingCharacters(in: .whitespacesAndNewlines),
                           !name.isEmpty {
                            Text(name)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.appTextSecondary)
                                .padding(.top, 2)
                        }
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                if showsCancel, let onCancel {
                    Button(action: onCancel) {
                        Text("COMMON_CANCEL")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .padding(.horizontal, 8)
                            .frame(minWidth: 44, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 16)
            .padding(.bottom, showsCancel ? 6 : 16)
            .background(Color.appSurfaceElevated, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 22, style: .continuous)
                    .strokeBorder(Color.appBorder, lineWidth: 1)
                    .allowsHitTesting(false)
            }
        }
    }

    private var statusIndicator: some View {
        ZStack {
            Circle()
                .fill(stage == .completed ? Color.appSuccess.opacity(0.12) : Color.appSurfaceMuted)
            if stage.isBlocking {
                ProgressView()
                    .controlSize(.small)
                    .tint(.primary)
            } else {
                Image(systemName: "checkmark")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(stage == .completed ? Color.appSuccess : Color.primary)
            }
        }
        .frame(width: 40, height: 40)
        .accessibilityHidden(true)
    }
}

#if DEBUG
private struct ContactOnboardingCardGallery: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                ContactOnboardingStatusView(stage: .searching, contactName: "Alex Morgan", onCancel: {})
                ContactOnboardingStatusView(stage: .saving, contactName: "Alex Morgan")
                ContactOnboardingStatusView(stage: .completed, contactName: "Alex Morgan")
                ContactOnboardingStatusView(stage: .alreadyRegistered, contactName: "Sam Taylor")
            }
            .padding(20)
        }
        .background(Color.appBackground)
    }
}

#Preview("QR cards · Light") {
    ContactOnboardingCardGallery().preferredColorScheme(.light)
}

#Preview("QR cards · Dark") {
    ContactOnboardingCardGallery().preferredColorScheme(.dark)
}

#Preview("QR cards · Large text") {
    ContactOnboardingStatusView(stage: .searching,
                                contactName: "Alexandra Morgan Taylor", onCancel: {})
        .padding(20)
        .background(Color.appBackground)
        .environment(\.dynamicTypeSize, .accessibility3)
        .preferredColorScheme(.dark)
}
#endif
