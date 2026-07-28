//
//  ContentSOSBroadcast.swift
//  Crisis Connect
//
//  Created by Assistant on 28.03.2026.
//

import Combine
import Foundation
import SwiftUI
import UIKit

struct SOSBroadcastView: View {
    var onCancel: () -> Void
    @ObservedObject private var manager = SOSBroadcastManager.shared
    @State private var activeChatSessionId: UUID?
    // Apple-style arming phase (Android parity): a 5-second haptic countdown the user can abort
    // with a deliberate slide before the emergency broadcast actually starts. Skipped when SOS is
    // already live (relaunch / service restart).
    @State private var isArming = true

    var body: some View {
        if isArming && !manager.isBroadcasting {
            SOSCountdownView(
                onCancelled: onCancel,
                onFired: { isArming = false }
            )
        } else {
            broadcastScreen
                .onAppear { isArming = false }
        }
    }

    private var broadcastScreen: some View {
        ZStack {
            Color.appBackground
                .ignoresSafeArea()
            LinearGradient(
                colors: [Color.appSOS.opacity(0.1), Color.clear],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(spacing: 18) {
                Spacer(minLength: 8)

                SOSBroadcastHero()

                HStack(spacing: 12) {
                    SOSMetricCard(
                        title: "SOS_BROADCAST_DURATION",
                        icon: "timer",
                        value: Text(manager.elapsedText)
                            .font(.title3.weight(.semibold))
                            .monospacedDigit(),
                        valueColor: .primary
                    )
                    // A live count beats a static "ACTIVE": once a rescuer completes the
                    // authenticated handshake the victim SEES that someone found them.
                    SOSMetricCard(
                        title: "SOS_BROADCAST_STATUS_TITLE",
                        icon: "dot.radiowaves.left.and.right",
                        value: manager.connectedRescuerCount > 0
                            ? Text("\(manager.connectedRescuerCount) ").font(.title3.weight(.semibold))
                                + Text(LocalizedStringKey("SOS_BROADCAST_RESCUERS_NEARBY"))
                                    .font(.title3.weight(.semibold))
                            : Text(LocalizedStringKey("SOS_BROADCAST_STATUS_ACTIVE"))
                                .font(.title3.weight(.semibold)),
                        valueColor: manager.connectedRescuerCount > 0 ? .green : .appSOS
                    )
                }

                SOSCloudUplinkChip()

                Text(LocalizedStringKey("SOS_BROADCAST_HELP"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

                SOSEmergencyCallCard()

                SOSNotifiedContactsCard()

                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)
        }
        .navigationDestination(
            isPresented: Binding(
                get: { activeChatSessionId != nil },
                set: { isPresented in
                    if !isPresented {
                        activeChatSessionId = nil
                    }
                }
            )
        ) {
            if let sessionId = activeChatSessionId {
                LazyNavigationDestination {
                    SOSChatDetailScreen(sessionId: sessionId)
                }
            }
        }
        .safeAreaInset(edge: .bottom) {
            SlideToCancelView(title: LocalizedStringKey("SOS_BROADCAST_SLIDE_TO_CANCEL"), tint: .appSOS) {
                manager.stop()
                onCancel()
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .onAppear {
            // The one place a real emergency is declared: this screen is only reached by the user
            // triggering SOS. Everything else brings the session up with declaringEmergency: false.
            manager.start(declaringEmergency: true)
        }
        .onReceive(manager.$lastAuthenticatedFieldTeamSessionId.receive(on: RunLoop.main)) { sessionId in
            guard let sessionId else { return }
            activeChatSessionId = sessionId
        }
    }
}

/// The 5-second arming countdown — iOS port of Android's `SosCountdownScreen`: pulsing dial with a
/// large monosized digit, a heavy haptic tick each second, keep-screen-on, and a slide-to-cancel
/// rail. When it reaches zero the caller flips to the live broadcast screen (which starts the SOS).
private struct SOSCountdownView: View {
    let onCancelled: () -> Void
    let onFired: () -> Void

    private static let countdownSeconds = 5

    @Environment(\.colorScheme) private var colorScheme
    @State private var secondsLeft = SOSCountdownView.countdownSeconds
    @State private var fired = false
    @State private var ringScale: CGFloat = 1
    @State private var ringOpacity: Double = 0.45

    var body: some View {
        ZStack {
            LinearGradient(
                colors: colorScheme == .dark
                    ? [Color(red: 0.125, green: 0.039, blue: 0.055),
                       Color(red: 0.204, green: 0.063, blue: 0.102),
                       Color(red: 0.071, green: 0.024, blue: 0.039)]
                    : [Color(red: 1.0, green: 0.965, blue: 0.957),
                       Color(red: 0.992, green: 0.929, blue: 0.914),
                       Color(red: 0.973, green: 0.894, blue: 0.878)],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            VStack {
                VStack(spacing: 10) {
                    Text(LocalizedStringKey("SOS_COUNTDOWN_TITLE"))
                        .font(.title.weight(.bold))
                        .multilineTextAlignment(.center)
                    Text(LocalizedStringKey("SOS_COUNTDOWN_BODY"))
                        .font(.body)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.top, 40)

                Spacer()

                countdownDial

                Spacer()

                VStack(spacing: 12) {
                    SlideToCancelView(title: LocalizedStringKey("SOS_COUNTDOWN_SLIDE_LABEL"), tint: .appSOS) {
                        guard !fired else { return }
                        fired = true
                        onCancelled()
                    }
                    Text(LocalizedStringKey("SOS_COUNTDOWN_SLIDE_HINT"))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding(.bottom, 24)
            }
            .padding(.horizontal, 28)
            .frame(maxWidth: 560)
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            withAnimation(.easeOut(duration: 1).repeatForever(autoreverses: false)) {
                ringScale = 1.45
                ringOpacity = 0
            }
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
        }
        .task {
            let haptic = UIImpactFeedbackGenerator(style: .heavy)
            while secondsLeft > 0 {
                haptic.impactOccurred()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard !fired else { return }
                secondsLeft -= 1
            }
            guard !fired else { return }
            fired = true
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
            onFired()
        }
    }

    private var countdownDial: some View {
        let accent: Color = .appSOS
        return ZStack {
            Circle()
                .stroke(accent.opacity(0.6), lineWidth: 2)
                .frame(width: 230, height: 230)
                .scaleEffect(ringScale)
                .opacity(ringOpacity)
            Circle()
                .fill(
                    RadialGradient(
                        colors: [accent.opacity(0.18), accent.opacity(0.04), .clear],
                        center: .center,
                        startRadius: 10,
                        endRadius: 130
                    )
                )
                .overlay(Circle().stroke(accent.opacity(0.4), lineWidth: 1.5))
                .frame(width: 230, height: 230)
            Text("\(secondsLeft)")
                .font(.system(size: 128, weight: .bold, design: .monospaced))
                .foregroundStyle(colorScheme == .dark ? Color.white : accent)
                .contentTransition(.numericText(countsDown: true))
                .animation(.snappy, value: secondsLeft)
        }
        .accessibilityLabel(
            String(format: NSLocalizedString("SOS_COUNTDOWN_SECONDS_LEFT", comment: ""), secondsLeft)
        )
    }
}

private struct SOSBroadcastHero: View {
    var body: some View {
        VStack(spacing: 14) {
            HStack {
                Text(LocalizedStringKey("SOS_BROADCAST_LIVE_BADGE"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.appSOS))

                Spacer()
            }

            ZStack {
                SOSPulseRings()

                Circle()
                    .fill(Color.appSOS.opacity(0.2))
                    .frame(width: 140, height: 140)

                Circle()
                    .stroke(Color.appSOS.opacity(0.4), lineWidth: 1)
                    .frame(width: 140, height: 140)

                Text(LocalizedStringKey("SOS_BUTTON_LABEL"))
                    .font(.system(size: 40, weight: .bold, design: .rounded))
                    .foregroundStyle(Color.appSOS)
            }

            Text(LocalizedStringKey("SOS_BROADCAST_ACTIVE"))
                .font(.headline.weight(.semibold))
                .foregroundStyle(.primary)

            Text(LocalizedStringKey("SOS_BROADCAST_HINT"))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 12)
        }
        .padding(18)
        .frame(maxWidth: .infinity)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [Color.white.opacity(0.45), Color.appSOS.opacity(0.16)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: .black.opacity(0.08), radius: 12, y: 8)
    }
}

/// Cloud dashboard-uplink status for the active SOS — the iOS port of Android's SOS cloud status
/// chip (SOSScreen.cloudChipContent). Observes SosCloudReporter so the victim can see whether the
/// self-report actually reached the agency dashboard: no internet / sending / reported / will retry.
private struct SOSCloudUplinkChip: View {
    @ObservedObject private var reporter = SosCloudReporter.shared

    var body: some View {
        let display = Self.display(for: reporter.state)
        HStack(spacing: 8) {
            Image(systemName: display.icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(display.tint)
            Text(display.text)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }

    private static func display(
        for state: SosCloudReporter.State
    ) -> (icon: String, text: LocalizedStringKey, tint: Color) {
        switch state {
        case .idle, .waitingForNetwork:
            return ("icloud.slash", "SOS_CLOUD_STATUS_NO_INTERNET", .secondary)
        case .sending:
            return ("icloud.and.arrow.up", "SOS_CONTACT_STATUS_SENDING", .appSOS)
        case .reported:
            return ("checkmark.icloud", "SOS_CLOUD_STATUS_REPORTED", .appSuccess)
        case .failed:
            return ("icloud.slash", "SOS_CONTACT_STATUS_FAILED", .appWarning)
        }
    }
}

/// One-tap emergency call to the region's number (112/911/999…), the iOS port of Android's SOS
/// CallCard. Starts from the device-locale number, then upgrades to the physically-roamed country's
/// number once the SOS GPS fix reverse-geocodes to an ISO — a US-locale traveler in Turkey is
/// offered 112, not 911. The button opens the dialer via a `tel:` URL so the user still confirms
/// the call in the system UI.
private struct SOSEmergencyCallCard: View {
    @State private var emergency = EmergencyNumberResolver.resolveWithRegion()

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(LocalizedStringKey("SOS_STAGE_CALL_TITLE"))
                    .font(.subheadline.weight(.semibold))
                Spacer()
                AppStatusPill(title: "SOS_STAGE_TAP_BADGE", tint: .appSOS)
            }

            Button(action: call) {
                HStack(spacing: 8) {
                    Image(systemName: "phone.fill")
                        .font(.system(size: 16, weight: .semibold))
                    Text(String(format: NSLocalizedString("SOS_CALL_BUTTON_LABEL", comment: ""), emergency.number))
                        .font(.subheadline.weight(.bold))
                }
                .foregroundStyle(.white)
                .frame(maxWidth: .infinity)
                .frame(height: 50)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.appSOS)
                )
            }
            .accessibilityLabel(Text(String(format: NSLocalizedString("SOS_CALL_BUTTON_LABEL", comment: ""), emergency.number)))

            Text(LocalizedStringKey("SOS_CALL_HINT"))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .padding(.horizontal, 4)
        .task { await refreshRoamingNumber() }
    }

    private func call() {
        let digits = emergency.number.filter { $0.isNumber || $0 == "+" }
        guard !digits.isEmpty, let url = URL(string: "tel://\(digits)") else { return }
        UIApplication.shared.open(url)
    }

    /// A GPS fix beats the device locale for a roamed traveler. Reverse-geocode the SOS fix to an
    /// ISO (reusing SosCloudReporter's geocoder) and refresh the number. The fix can land a few
    /// seconds after the broadcast starts, so poll briefly; the locale default stands when there is
    /// no fix or geocoding can't resolve one. Defensive: never blocks or crashes the card.
    private func refreshRoamingNumber() async {
        for _ in 0..<8 {
            if Task.isCancelled { return }
            if let iso = await SosCloudReporter.shared.currentGeocodedCountryIso(),
               !iso.isEmpty {
                let number = EmergencyNumberResolver.number(forCountryIso: iso)
                await MainActor.run {
                    emergency = EmergencyNumberResolver.RegionalEmergencyNumber(
                        number: number,
                        countryIso: iso
                    )
                }
                return
            }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
    }
}

/// Who the SOS is alerting over the internet, with a live per-contact delivery badge — the iOS port
/// of Android's SOS ContactsCard. Without this the victim had no visibility into whether their
/// emergency contacts were actually reached.
private struct SOSNotifiedContactsCard: View {
    @ObservedObject private var notifier = SosContactNotifier.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Text(LocalizedStringKey("SOS_STAGE_CONTACTS_TITLE"))
                    .font(.subheadline.weight(.semibold))
                Spacer()
                AppStatusPill(
                    title: notifier.selectionSource == .custom
                        ? "SOS_CHIP_CUSTOM_SELECTION"
                        : "SOS_STAGE_AUTO_BADGE",
                    tint: .appSOS
                )
            }

            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .padding(.horizontal, 4)
    }

    @ViewBuilder
    private var content: some View {
        if !notifier.selectionReady {
            Text(LocalizedStringKey("SOS_CONTACTS_LOADING"))
                .font(.caption)
                .foregroundStyle(.secondary)
        } else if notifier.recipients.isEmpty {
            Text(LocalizedStringKey("SOS_CONTACTS_EMPTY"))
                .font(.caption)
                .foregroundStyle(.secondary)
        } else {
            HStack(alignment: .top, spacing: 14) {
                ForEach(notifier.recipients) { recipient in
                    SOSRecipientChip(recipient: recipient)
                }
                Spacer(minLength: 0)
            }
            Text(summaryText)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var summaryText: String {
        let sent = notifier.recipients.filter { $0.status == .sent }.count
        return String(
            format: NSLocalizedString("SOS_CONTACTS_SUMMARY", comment: ""),
            sent,
            notifier.recipients.count
        )
    }
}

/// A single alerted contact: an initial avatar with a corner badge reflecting the send state.
private struct SOSRecipientChip: View {
    let recipient: SosContactNotifier.Recipient

    var body: some View {
        VStack(spacing: 6) {
            ZStack(alignment: .bottomTrailing) {
                Circle()
                    .fill(Color.appSOS.opacity(0.14))
                    .frame(width: 44, height: 44)
                    .overlay(
                        Circle().stroke(Color.appBorder, lineWidth: 1)
                    )
                    .overlay(
                        Text(initial)
                            .font(.headline.weight(.bold))
                            .foregroundStyle(Color.appSOS)
                    )

                Image(systemName: badge.systemName)
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(badge.tint)
                    .background(Circle().fill(Color.appSurfaceElevated).padding(-1))
                    .offset(x: 3, y: 3)
            }

            Text(recipient.name)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: 60)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("\(recipient.name), \(badge.accessibilityKey)"))
    }

    private var initial: String {
        recipient.name
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .first
            .map { String($0).uppercased() } ?? "?"
    }

    private var badge: (systemName: String, tint: Color, accessibilityKey: String) {
        switch recipient.status {
        case .waiting:
            return ("clock.fill", .secondary, NSLocalizedString("SOS_CONTACT_STATUS_WAITING", comment: ""))
        case .sending:
            return ("arrow.up.circle.fill", .appSOS, NSLocalizedString("SOS_CONTACT_STATUS_SENDING", comment: ""))
        case .sent:
            return ("checkmark.circle.fill", .appSuccess, NSLocalizedString("SOS_CONTACT_STATUS_SENT", comment: ""))
        case .failed:
            return ("exclamationmark.circle.fill", .appWarning, NSLocalizedString("SOS_CONTACT_STATUS_FAILED", comment: ""))
        }
    }
}

private struct SOSMetricCard: View {
    let title: LocalizedStringKey
    let icon: String
    let value: Text
    let valueColor: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label(title, systemImage: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            value
                .foregroundStyle(valueColor)
        }
        .padding(14)
        .frame(maxWidth: .infinity, minHeight: 88, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(.ultraThinMaterial)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(
                    LinearGradient(
                        colors: [Color.white.opacity(0.35), Color.appSOS.opacity(0.12)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    ),
                    lineWidth: 1
                )
        )
        .shadow(color: .black.opacity(0.06), radius: 10, y: 6)
    }
}

private struct SOSPulseRings: View {
    @State private var pulse = false

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.appSOS.opacity(0.5), lineWidth: 2)
                .scaleEffect(pulse ? 1.65 : 0.95)
                .opacity(pulse ? 0 : 0.6)
                .animation(.easeOut(duration: 2.2).repeatForever(autoreverses: false), value: pulse)

            Circle()
                .stroke(Color.appSOS.opacity(0.34), lineWidth: 2)
                .scaleEffect(pulse ? 1.4 : 0.9)
                .opacity(pulse ? 0 : 0.5)
                .animation(.easeOut(duration: 2.2).repeatForever(autoreverses: false).delay(0.6), value: pulse)
        }
        .onAppear {
            pulse = true
        }
    }
}

private struct SlideToCancelView: View {
    var title: LocalizedStringKey
    var tint: Color
    var onComplete: () -> Void

    @State private var dragOffset: CGFloat = 0
    @State private var isCompleted: Bool = false
    @State private var isArmed: Bool = false
    @State private var hasTriggeredHaptic: Bool = false

    var body: some View {
        GeometryReader { proxy in
            let height = proxy.size.height
            let knobSize = max(0, height - 10)
            let maxOffset = max(0, proxy.size.width - knobSize - 10)
            let progress = maxOffset == 0 ? 0 : dragOffset / maxOffset
            let isNearEnd = progress > 0.78
            let fillWidth = max(0, knobSize + dragOffset + 10)

            ZStack(alignment: .leading) {
                Capsule()
                    .fill(.ultraThinMaterial)
                    .overlay(
                        Capsule()
                            .stroke(
                                LinearGradient(
                                    colors: [Color.white.opacity(0.5), tint.opacity(0.12)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 1
                            )
                    )
                    .shadow(color: .black.opacity(0.08), radius: 10, y: 6)

                Capsule()
                    .fill(
                        LinearGradient(
                            colors: [tint.opacity(0.45), tint.opacity(0.2)],
                            startPoint: .leading,
                            endPoint: .trailing
                        )
                    )
                    .frame(width: fillWidth)
                    .mask(Capsule())
                    .overlay(
                        Capsule()
                            .stroke(tint.opacity(0.25), lineWidth: 1)
                            .frame(width: fillWidth)
                    )

                ZStack {
                    Text(title)
                        .opacity(isNearEnd ? 0 : 1)
                    Text(LocalizedStringKey("SOS_BROADCAST_SLIDE_RELEASE"))
                        .opacity(isNearEnd ? 1 : 0)
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(isNearEnd ? tint : .secondary)
                .frame(maxWidth: .infinity)
                .opacity(isCompleted ? 0 : 1)
                .animation(.easeInOut(duration: 0.15), value: isNearEnd)

                Circle()
                    .fill(
                        LinearGradient(
                            colors: [tint.opacity(0.95), tint.opacity(0.7)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .frame(width: knobSize, height: knobSize)
                    .overlay(
                        Image(systemName: "chevron.right")
                            .font(.system(size: 14, weight: .bold))
                            .foregroundColor(.white)
                    )
                    .overlay(
                        Circle()
                            .stroke(Color.white.opacity(0.4), lineWidth: 1)
                    )
                    .shadow(color: tint.opacity(0.35), radius: 12, y: 6)
                    .offset(x: dragOffset)
            }
            .padding(5)
            .contentShape(Rectangle())
            .gesture(
                DragGesture()
                    .onChanged { value in
                        guard !isCompleted else { return }
                        let clamped = min(max(0, value.translation.width), maxOffset)
                        dragOffset = clamped
                        let progress = maxOffset == 0 ? 0 : clamped / maxOffset
                        let shouldArm = progress > 0.78
                        if shouldArm != isArmed {
                            isArmed = shouldArm
                            if shouldArm && !hasTriggeredHaptic {
                                UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                                hasTriggeredHaptic = true
                            }
                            if !shouldArm {
                                hasTriggeredHaptic = false
                            }
                        }
                    }
                    .onEnded { _ in
                        guard !isCompleted else { return }
                        if dragOffset > maxOffset * 0.78 {
                            isCompleted = true
                            isArmed = false
                            withAnimation(.easeOut(duration: 0.2)) {
                                dragOffset = maxOffset
                            }
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                            onComplete()
                        } else {
                            isArmed = false
                            hasTriggeredHaptic = false
                            withAnimation(.spring(response: 0.35, dampingFraction: 0.8)) {
                                dragOffset = 0
                            }
                        }
                    }
            )
        }
        .frame(height: 62)
        .accessibilityLabel(Text(title))
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { onComplete() }
    }
}
