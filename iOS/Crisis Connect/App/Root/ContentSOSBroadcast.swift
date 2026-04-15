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

    var body: some View {
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
                    SOSMetricCard(
                        title: "SOS_BROADCAST_STATUS_TITLE",
                        icon: "dot.radiowaves.left.and.right",
                        value: Text(LocalizedStringKey("SOS_BROADCAST_STATUS_ACTIVE"))
                            .font(.title3.weight(.semibold)),
                        valueColor: .appSOS
                    )
                }

                Text(LocalizedStringKey("SOS_BROADCAST_HELP"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)

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
            manager.start()
        }
        .onReceive(manager.$lastAuthenticatedFieldTeamSessionId.receive(on: RunLoop.main)) { sessionId in
            guard let sessionId else { return }
            activeChatSessionId = sessionId
        }
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
