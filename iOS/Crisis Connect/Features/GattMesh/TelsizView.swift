import AVFoundation
import SwiftUI
import UIKit

/// Telsiz (push-to-talk) screen for the authority mesh — iOS counterpart of the Android
/// `TelsizFullScreen`. A who's-speaking hero, concentric pulse rings around a big hold-to-talk
/// button, the live participant list (with verified-institution badges), and a leave action.
/// Observes `GattMeshManager.telsizState`.
///
/// Single-speaker floor: holding the button claims the floor (if free) and transmits; releasing frees
/// it. A press while a peer holds the floor is rejected and surfaces a transient "channel busy" hint.
struct TelsizView: View {
    @ObservedObject var manager: GattMeshManager
    @Environment(\.dismiss) private var dismiss

    @State private var isPressing = false
    @State private var transientMessage: String?
    @State private var transientDismiss: DispatchWorkItem?

    private var state: PttSessionState { manager.telsizState }
    private var floor: PttFloorState { state.floor }
    private var talking: Bool { floor == .localSpeaking }
    private var remoteSpeaking: Bool { floor == .remoteSpeaking }
    private var accent: Color { talking ? .appDanger : .appPrimary }

    var body: some View {
        ZStack {
            AppScreenBackground()

            VStack(spacing: 0) {
                header

                Spacer(minLength: 12)
                statusHero
                Spacer(minLength: 18)

                ZStack {
                    PttPulseRings(active: talking || remoteSpeaking, color: accent)
                        .frame(width: 232, height: 232)
                    talkButton
                }

                Text(hintKey)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
                    .padding(.top, 12)

                Spacer(minLength: 18)
                participantSection
                leaveButton
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.bottom, 14)

            if let transientMessage {
                VStack {
                    Spacer()
                    Text(transientMessage)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 10)
                        .background(Capsule(style: .continuous).fill(Color.black.opacity(0.82)))
                        .padding(.bottom, 96)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                .allowsHitTesting(false)
            }
        }
        .onChange(of: state.busyTick) { _, _ in showTransient(localized("TELSIZ_BUSY")) }
        .onChange(of: state.maxTalkTick) { _, _ in showTransient(localized("TELSIZ_MAX_TALK")) }
    }

    // MARK: - Header

    private var header: some View {
        HStack(spacing: 10) {
            Button {
                dismiss()
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(width: 36, height: 36)
                    .background(Circle().fill(Color.appSurfaceElevated))
            }
            .accessibilityLabel(Text("Close"))

            Text("TELSIZ_TITLE")
                .font(.title3.weight(.bold))

            Spacer()

            if state.joined {
                countChip
            }
        }
        .padding(.top, 6)
    }

    private var countChip: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(Color.appSuccess)
                .frame(width: 8, height: 8)
            Text("\(state.participantCount)")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.primary)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule(style: .continuous).fill(Color.appSurfaceElevated))
    }

    // MARK: - Status hero (borderless, like Android)

    private var statusHero: some View {
        VStack(spacing: 6) {
            switch floor {
            case .remoteSpeaking:
                Text(state.speakerName ?? "—")
                    .font(.title2.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                HStack(spacing: 8) {
                    TelsizSpeakingBars(color: accent)
                        .frame(width: 26, height: 18)
                    Text("TELSIZ_STATUS_SPEAKING")
                        .font(.body)
                        .foregroundStyle(accent)
                }
            case .localSpeaking:
                HStack(spacing: 6) {
                    Circle().fill(Color.appDanger).frame(width: 8, height: 8)
                    Text("TELSIZ_LIVE")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.appDanger)
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule(style: .continuous).fill(Color.appDanger.opacity(0.14)))
                Text("TELSIZ_STATUS_LOCAL")
                    .font(.title2.weight(.bold))
            case .idle:
                Text("TELSIZ_IDLE_HEADING")
                    .font(.title2.weight(.bold))
                Text(state.joined
                     ? localized("TELSIZ_HOLD_TO_TALK")
                     : localizedFormat("TELSIZ_MEMBERS_FORMAT", state.participantCount))
                    .font(.body)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity)
        .animation(.easeInOut(duration: 0.2), value: floor)
    }

    // MARK: - Hold-to-talk button

    private var talkButton: some View {
        let diameter: CGFloat = 168
        return ZStack {
            Circle()
                .fill(talkButtonContainer)
                .overlay(
                    // Subtle top highlight = Android's lerp(container, white, 0.16) vertical gradient.
                    Circle().fill(
                        LinearGradient(
                            colors: [Color.white.opacity(0.18), Color.clear],
                            startPoint: .top,
                            endPoint: .center
                        )
                    )
                )
                .frame(width: diameter, height: diameter)
                .shadow(color: talkButtonContainer.opacity(0.35), radius: 16, y: 8)

            talkButtonIcon
                .frame(width: 70, height: 70)
                .foregroundStyle(talkButtonContent)
        }
        .scaleEffect(isPressing ? 0.93 : 1)
        .animation(.spring(response: 0.25, dampingFraction: 0.7), value: isPressing)
        .contentShape(Circle())
        .gesture(
            DragGesture(minimumDistance: 0)
                .onChanged { _ in
                    guard !isPressing else { return }
                    isPressing = true
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    manager.pttPressTalk()
                }
                .onEnded { _ in
                    guard isPressing else { return }
                    isPressing = false
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    manager.pttReleaseTalk()
                }
        )
        .accessibilityLabel(Text("TELSIZ_HOLD_TO_TALK"))
    }

    @ViewBuilder
    private var talkButtonIcon: some View {
        if remoteSpeaking {
            Image("ic_telsiz")
                .renderingMode(.template)
                .resizable()
                .scaledToFit()
        } else {
            Image(systemName: "mic.fill")
                .resizable()
                .scaledToFit()
        }
    }

    private var talkButtonContainer: Color {
        switch floor {
        case .localSpeaking: return .appDanger
        case .remoteSpeaking: return .appSurfaceElevated
        case .idle: return .appPrimary
        }
    }

    private var talkButtonContent: Color {
        switch floor {
        case .localSpeaking: return .white
        case .remoteSpeaking: return .appTextSecondary
        case .idle: return .white
        }
    }

    private var hintKey: LocalizedStringKey {
        switch floor {
        case .localSpeaking: return "TELSIZ_RELEASE_HINT"
        case .remoteSpeaking: return "TELSIZ_BUSY"
        case .idle: return "TELSIZ_HOLD_TO_TALK"
        }
    }

    // MARK: - Participants

    @ViewBuilder
    private var participantSection: some View {
        if state.participants.isEmpty {
            Text("TELSIZ_EMPTY")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.top, 4)
        } else {
            VStack(alignment: .leading, spacing: 0) {
                Text("TELSIZ_MEMBERS")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
                    .textCase(.uppercase)
                    .padding(.leading, 6)
                    .padding(.bottom, 6)

                ScrollView {
                    VStack(spacing: 0) {
                        ForEach(state.participants) { participant in
                            participantRow(participant)
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
            .frame(maxHeight: .infinity)
        }
    }

    private func participantRow(_ participant: PttParticipant) -> some View {
        HStack(spacing: 12) {
            TelsizAvatar(displayName: participant.displayName, stableKey: participant.id, size: 34)

            Text(participant.isSelf
                 ? "\(participant.displayName) · \(localized("TELSIZ_SELF"))"
                 : participant.displayName)
                .font(.body)
                .lineLimit(1)

            Spacer(minLength: 8)

            if let agency = nonEmpty(participant.agency) {
                TelsizAgencyBadge(agency: agency)
            }

            if participant.isSpeaking {
                TelsizSpeakingBars(color: Color.appPrimary)
                    .frame(width: 22, height: 16)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - Leave

    private var leaveButton: some View {
        Button {
            manager.leaveTelsiz()
            dismiss()
        } label: {
            Text("TELSIZ_LEAVE")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appDanger)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
        }
    }

    // MARK: - Helpers

    private func showTransient(_ message: String) {
        transientDismiss?.cancel()
        withAnimation(.easeOut(duration: 0.2)) { transientMessage = message }
        let work = DispatchWorkItem {
            withAnimation(.easeIn(duration: 0.25)) { transientMessage = nil }
        }
        transientDismiss = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: work)
    }

    private func nonEmpty(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines), !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return String(format: format, locale: Locale.current, arguments: arguments)
    }
}

/// Pinned banner shown at the top of the authority chat while the telsiz session is active (joined or
/// participants present). Tapping it opens the full telsiz screen. iOS port of Android `TelsizActiveBanner`.
struct TelsizActiveBanner: View {
    let state: PttSessionState
    var onTap: () -> Void

    private var floor: PttFloorState { state.floor }

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                Image("ic_telsiz")
                    .renderingMode(.template)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 22, height: 22)
                    .foregroundStyle(Color.appPrimary)

                VStack(alignment: .leading, spacing: 1) {
                    Text("TELSIZ_TITLE")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                    Text(statusText)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                if floor == .idle {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.appTextSecondary)
                } else {
                    TelsizSpeakingBars(color: floor == .localSpeaking ? .appDanger : .appPrimary)
                        .frame(width: 20, height: 16)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(bannerColor)
        }
        .buttonStyle(.plain)
        .animation(.easeInOut(duration: 0.25), value: floor)
    }

    private var statusText: String {
        switch floor {
        case .localSpeaking:
            return NSLocalizedString("TELSIZ_STATUS_LOCAL", comment: "")
        case .remoteSpeaking:
            return String(format: NSLocalizedString("TELSIZ_STATUS_REMOTE_FORMAT", comment: ""), state.speakerName ?? "—")
        case .idle:
            return String(format: NSLocalizedString("TELSIZ_MEMBERS_FORMAT", comment: ""), state.participantCount)
        }
    }

    private var bannerColor: Color {
        switch floor {
        case .localSpeaking: return Color.appDanger.opacity(0.14)
        case .remoteSpeaking: return Color.appPrimarySoft
        case .idle: return Color.appSurfaceElevated
        }
    }
}

/// The "pull the chat down to open the telsiz" pill (iOS port of Android `TelsizPullIndicator`). Grows
/// and spins toward the walkie-talkie mark as the user over-pulls; flips to "release to open" past the
/// threshold. `progress` is pull / threshold (1 = ready).
struct TelsizPullIndicator: View {
    let progress: CGFloat

    var body: some View {
        let clamped = min(max(progress, 0), 1)
        let ready = progress >= 1
        let container: Color = ready ? .appPrimary : .appSurfaceElevated
        let content: Color = ready ? .white : .appTextSecondary

        return HStack(spacing: 8) {
            Group {
                if ready {
                    Image("ic_telsiz").renderingMode(.template).resizable().scaledToFit()
                } else {
                    Image(systemName: "chevron.up").resizable().scaledToFit()
                }
            }
            .frame(width: 18, height: 18)
            .rotationEffect(.degrees(Double(clamped) * 360))
            .foregroundStyle(content)

            Text(ready ? "TELSIZ_RELEASE_TO_OPEN" : "TELSIZ_PULL_TO_OPEN")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(content)
                .lineLimit(1)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .background(Capsule(style: .continuous).fill(container))
        .scaleEffect(0.85 + 0.15 * clamped)
        .animation(.easeOut(duration: 0.15), value: ready)
    }
}

/// Concentric pulse rings radiating from the PTT button while audio is live (iOS port of the Android
/// `PttPulseRings` Canvas). Three staggered rings expand outward and fade, driven by a `TimelineView`.
private struct PttPulseRings: View {
    let active: Bool
    let color: Color

    var body: some View {
        if active {
            TimelineView(.animation) { timeline in
                Canvas { context, size in
                    let now = timeline.date.timeIntervalSinceReferenceDate
                    let maxRadius = min(size.width, size.height) / 2
                    let period = 2.0
                    let strokeWidth: CGFloat = 3
                    for index in 0..<3 {
                        let delay = Double(index) * 0.65
                        let t = ((now - delay) / period).truncatingRemainder(dividingBy: 1.0)
                        let phase = t < 0 ? t + 1.0 : t
                        let radius = maxRadius * (0.46 + 0.54 * phase)
                        let alpha = (1.0 - phase) * 0.35
                        let rect = CGRect(
                            x: size.width / 2 - radius,
                            y: size.height / 2 - radius,
                            width: radius * 2,
                            height: radius * 2
                        )
                        context.stroke(
                            Circle().path(in: rect),
                            with: .color(color.opacity(alpha)),
                            lineWidth: strokeWidth
                        )
                    }
                }
            }
        }
    }
}

/// Neutral institution (kurum) capsule for a telsiz participant. The authority mesh already gates
/// membership to verified roles, so this is informational (not independently re-verified per packet).
private struct TelsizAgencyBadge: View {
    let agency: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 10, weight: .bold))
            Text(agency)
                .font(.caption2.weight(.semibold))
                .lineLimit(1)
        }
        .foregroundStyle(Color.appPrimary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule(style: .continuous).fill(Color.appPrimarySoft))
    }
}

/// Compact initials avatar for a telsiz participant (a stable colour seeded from the participant key).
private struct TelsizAvatar: View {
    let displayName: String
    let stableKey: String
    let size: CGFloat

    var body: some View {
        Circle()
            .fill(seedColor)
            .frame(width: size, height: size)
            .overlay(
                Text(initials)
                    .font(.system(size: size * 0.38, weight: .bold))
                    .foregroundStyle(.white)
            )
    }

    private var initials: String {
        let parts = displayName
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .split(separator: " ")
            .prefix(2)
        let letters = parts.compactMap { $0.first }.map(String.init).joined()
        return letters.isEmpty ? "?" : letters.uppercased()
    }

    private var seedColor: Color {
        let palette: [Color] = [.appPrimary, .appSuccess, .appWarning, .appDanger]
        let hash = stableKey.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fffffff }
        return palette[hash % palette.count]
    }
}

/// Small animated bar equalizer shown while someone is transmitting (Android `SpeakingEqualizer`).
private struct TelsizSpeakingBars: View {
    let color: Color
    @State private var animate = false

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(0..<4, id: \.self) { index in
                Capsule(style: .continuous)
                    .fill(color)
                    .frame(width: 4)
                    .scaleEffect(y: animate ? 1.0 : 0.3, anchor: .bottom)
                    .animation(
                        .easeInOut(duration: 0.38 + Double(index) * 0.11)
                            .repeatForever(autoreverses: true),
                        value: animate
                    )
            }
        }
        .frame(maxHeight: .infinity)
        .onAppear { animate = true }
    }
}
