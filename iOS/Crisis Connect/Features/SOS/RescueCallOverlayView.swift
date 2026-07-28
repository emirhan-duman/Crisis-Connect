//
//  RescueCallOverlayView.swift
//  Crisis Connect
//
//  In-app overlay for live rescue-link voice calls (RescueCallEngine). Mirrors the Android
//  BleChatScreen RescueCallOverlay: incoming ring = accept/decline; otherwise mute/speaker/end.
//

import Combine
import SwiftUI

struct RescueCallOverlayView: View {
    let call: RescueCallEngine.CallInfo
    let fallbackName: String
    let onAccept: () -> Void
    let onReject: () -> Void
    let onEnd: () -> Void
    let onToggleMute: () -> Void
    let onToggleSpeaker: () -> Void

    @State private var now = Date()
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var displayName: String {
        call.peerName.isEmpty ? fallbackName : call.peerName
    }

    private var stateLabel: String {
        switch call.state {
        case .inCall:
            if let connectedAt = call.connectedAt {
                let total = max(0, Int(now.timeIntervalSince(connectedAt)))
                return String(format: "%d:%02d", total / 60, total % 60)
            }
            return NSLocalizedString("RESCUE_CALL_CONNECTING", comment: "")
        case .ringingIncoming:
            return NSLocalizedString("RESCUE_CALL_INCOMING", comment: "")
        case .ringingOutgoing:
            return NSLocalizedString("RESCUE_CALL_RINGING", comment: "")
        case .connecting:
            return NSLocalizedString("RESCUE_CALL_CONNECTING", comment: "")
        }
    }

    var body: some View {
        VStack {
            Spacer(minLength: 72)
            VStack(spacing: 12) {
                Text(displayName)
                    .font(.title.weight(.semibold))
                    .multilineTextAlignment(.center)
                Text(stateLabel)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                Text(LocalizedStringKey("RESCUE_CALL_VIA_BLUETOOTH"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if call.state == .ringingIncoming {
                HStack {
                    Spacer()
                    callActionButton(
                        systemName: "phone.down.fill",
                        label: LocalizedStringKey("RESCUE_CALL_REJECT"),
                        background: .red,
                        action: onReject
                    )
                    Spacer()
                    callActionButton(
                        systemName: "phone.fill",
                        label: LocalizedStringKey("RESCUE_CALL_ACCEPT"),
                        background: .green,
                        action: onAccept
                    )
                    Spacer()
                }
                .padding(.bottom, 56)
            } else {
                HStack {
                    Spacer()
                    callActionButton(
                        systemName: call.muted ? "mic.slash.fill" : "mic.fill",
                        label: LocalizedStringKey(call.muted ? "RESCUE_CALL_UNMUTE" : "RESCUE_CALL_MUTE"),
                        background: call.muted ? Color.appPrimary : Color.gray.opacity(0.45),
                        action: onToggleMute
                    )
                    Spacer()
                    callActionButton(
                        systemName: "speaker.wave.2.fill",
                        label: LocalizedStringKey("RESCUE_CALL_SPEAKER"),
                        background: call.speakerOn ? Color.appPrimary : Color.gray.opacity(0.45),
                        action: onToggleSpeaker
                    )
                    Spacer()
                    callActionButton(
                        systemName: "phone.down.fill",
                        label: LocalizedStringKey("RESCUE_CALL_END"),
                        background: .red,
                        action: onEnd
                    )
                    Spacer()
                }
                .padding(.bottom, 56)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.regularMaterial)
        .onReceive(ticker) { value in
            now = value
        }
    }

    private func callActionButton(
        systemName: String,
        label: LocalizedStringKey,
        background: Color,
        action: @escaping () -> Void
    ) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: systemName)
                    .font(.system(size: 26, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 68, height: 68)
                    .background(Circle().fill(background))
            }
            .buttonStyle(.plain)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}
