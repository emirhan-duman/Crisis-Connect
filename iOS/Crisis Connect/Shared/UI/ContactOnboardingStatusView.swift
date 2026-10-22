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

    var stepIndex: Int? {
        switch self {
        case .searching: return 0
        case .pairing: return 1
        case .saving: return 2
        default: return nil
        }
    }
}

/// Presentation only: verification, persistence, and dismissal remain with the caller.
struct ContactOnboardingStatusView: View {
    let stage: ContactOnboardingStage
    let contactName: String?
    var onCancel: (() -> Void)? = nil
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var accent: Color { stage == .completed ? .appSuccess : .primary }

    var body: some View {
        if stage != .idle {
            VStack(alignment: .leading, spacing: 18) {
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: stage.systemImage)
                        .font(.system(size: 22, weight: .medium))
                        .foregroundStyle(accent)
                        .frame(width: 48, height: 48)
                        .background(stage == .completed ? Color.appSuccess.opacity(0.12) : Color.appSurfaceMuted,
                                    in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 7) {
                        Text(LocalizedStringKey(stage.titleKey))
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .accessibilityAddTraits(.isHeader)
                        Text(LocalizedStringKey(stage.messageKey))
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                if let contactName, !contactName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    Label {
                        Text(contactName)
                            .font(.subheadline.weight(.medium))
                            .fixedSize(horizontal: false, vertical: true)
                    } icon: {
                        Image(systemName: "person.crop.circle")
                            .foregroundStyle(Color.appTextSecondary)
                            .accessibilityHidden(true)
                    }
                    .padding(12)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.appSurfaceMuted, in: RoundedRectangle(cornerRadius: 12))
                }

                if let stepIndex = stage.stepIndex {
                    let layout = dynamicTypeSize.isAccessibilitySize
                        ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
                        : AnyLayout(HStackLayout(alignment: .top, spacing: 10))
                    layout {
                        step(0, current: stepIndex, label: "CONTACT_ONBOARDING_STEP_SEARCH")
                        step(1, current: stepIndex, label: "CONTACT_ONBOARDING_STEP_PAIR")
                        step(2, current: stepIndex, label: "CONTACT_ONBOARDING_STEP_SAVE")
                    }
                }

                if let onCancel, stage.isBlocking {
                    Divider().overlay(Color.appBorder)
                    Button(action: onCancel) {
                        Text("COMMON_CANCEL")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.primary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(20)
            .background(Color.appSurfaceElevated, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(Color.appBorder.opacity(0.8), lineWidth: 1)
            }
        }
    }

    private func step(_ index: Int, current: Int, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Capsule()
                .fill(index <= current ? Color.primary : Color.appBorder)
                .frame(height: 3)
                .accessibilityHidden(true)
            HStack(spacing: 6) {
                if index == current {
                    ProgressView().controlSize(.mini).tint(.primary)
                        .accessibilityHidden(true)
                } else if index < current {
                    Image(systemName: "checkmark").font(.caption2.weight(.bold))
                        .accessibilityHidden(true)
                }
                Text(label)
                    .font(.caption.weight(index == current ? .semibold : .regular))
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(index <= current ? Color.primary : Color.appTextSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(LocalizedStringKey(index < current
            ? "CONTACT_ONBOARDING_STEP_DONE"
            : index == current ? "CONTACT_ONBOARDING_STEP_ACTIVE" : "CONTACT_ONBOARDING_STEP_WAITING")))
    }
}

#if DEBUG
private struct ContactOnboardingCardGallery: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                ContactOnboardingStatusView(stage: .pairing, contactName: "Alex Morgan", onCancel: {})
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
