//
//  P2pMessageInfoSheet.swift
//  Crisis Connect
//
//  Created by Assistant on 08.11.2026.
//

import SwiftUI

struct P2pMessageInfoSheet: View {
    let message: SOSChatMessage
    let sessionTitle: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    // Message preview bubble
                    messagePreviewCard

                    // Status Timeline Card
                    timelineSection

                    // P2P Route & Transport Details
                    transportSection
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 24)
            }
            .background(Color.appBackground.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("CHAT_MESSAGE_INFO_TITLE", comment: ""))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 20))
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var messagePreviewCard: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(sessionTitle)
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.appPrimary)

            switch message.kind {
            case .text:
                Text(ForwardMetadata.stripForward(message.text))
                    .font(.body)
                    .foregroundStyle(.primary)
            case .image:
                HStack(spacing: 8) {
                    Image(systemName: "photo.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(Color.appPrimary)
                    Text(NSLocalizedString("CONVERSATION_PREVIEW_PHOTO_MESSAGE", comment: ""))
                        .font(.body.weight(.medium))
                }
            case .audio:
                HStack(spacing: 8) {
                    Image(systemName: "waveform")
                        .font(.system(size: 18))
                        .foregroundStyle(Color.appPrimary)
                    Text(NSLocalizedString("CONVERSATION_PREVIEW_VOICE_MESSAGE", comment: ""))
                        .font(.body.weight(.medium))
                }
            default:
                Text(message.text.isEmpty ? "Message" : message.text)
                    .font(.body)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.whatsAppOutgoing)
                .shadow(color: Color.black.opacity(0.04), radius: 2, y: 1)
        )
    }

    private var timelineSection: some View {
        VStack(spacing: 12) {
            Text(NSLocalizedString("CHAT_MESSAGE_INFO_TIMELINE", comment: ""))
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 0) {
                // Read row
                TimelineRow(
                    icon: "checkmark.circle.fill",
                    iconColor: Color.whatsAppAccent,
                    title: NSLocalizedString("CHAT_MESSAGE_INFO_READ", comment: ""),
                    date: message.readAt ?? (message.status == .read ? message.timestamp : nil),
                    isDone: message.status == .read
                )

                Divider().padding(.leading, 44)

                // Delivered row
                TimelineRow(
                    icon: "checkmark.circle",
                    iconColor: .secondary,
                    title: NSLocalizedString("CHAT_MESSAGE_INFO_DELIVERED", comment: ""),
                    date: message.deliveredAt ?? (message.status == .delivered || message.status == .read ? message.timestamp : nil),
                    isDone: message.status == .delivered || message.status == .read
                )

                Divider().padding(.leading, 44)

                // Sent row
                TimelineRow(
                    icon: "checkmark",
                    iconColor: .secondary,
                    title: NSLocalizedString("CHAT_MESSAGE_INFO_SENT", comment: ""),
                    date: message.sentAt ?? message.timestamp,
                    isDone: message.status != .pending && message.status != .failed
                )
            }
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
        }
    }

    private var transportSection: some View {
        VStack(spacing: 12) {
            Text(NSLocalizedString("CHAT_MESSAGE_INFO_DELIVERY_DETAILS", comment: ""))
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)

            VStack(spacing: 14) {
                // Transport route
                HStack(spacing: 12) {
                    Image(systemName: transportIcon)
                        .font(.system(size: 20))
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 32)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(NSLocalizedString("CHAT_MESSAGE_INFO_TRANSPORT_CHANNEL", comment: ""))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(transportLabel)
                            .font(.subheadline.weight(.semibold))
                    }
                    Spacer()
                }

                Divider()

                // Store & Forward state
                HStack(spacing: 12) {
                    Image(systemName: "point.3.filled.connected.trianglepath.dotted")
                        .font(.system(size: 18))
                        .foregroundStyle(.orange)
                        .frame(width: 32)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(NSLocalizedString("CHAT_MESSAGE_INFO_ROUTING_MODE", comment: ""))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text(routingModeLabel)
                            .font(.subheadline.weight(.semibold))
                    }
                    Spacer()
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
        }
    }

    private var transportIcon: String {
        let t = message.transportUsed?.lowercased() ?? ""
        if t.contains("internet") { return "network" }
        if t.contains("satellite") { return "antenna.radiowaves.left.and.right" }
        return "wave.3.right.circle.fill" // Bluetooth
    }

    private var transportLabel: String {
        let t = message.transportUsed?.lowercased() ?? ""
        if t.contains("internet") {
            return NSLocalizedString("CHAT_INFO_TRANSPORT_INTERNET", comment: "")
        }
        if t.contains("satellite") {
            return NSLocalizedString("CHAT_INFO_TRANSPORT_SATELLITE", comment: "")
        }
        return NSLocalizedString("CHAT_INFO_TRANSPORT_BLUETOOTH", comment: "")
    }

    private var routingModeLabel: String {
        if message.status == .pending {
            return NSLocalizedString("CHAT_INFO_ROUTING_QUEUED_STORE_FORWARD", comment: "")
        }
        return NSLocalizedString("CHAT_INFO_ROUTING_P2P_DIRECT", comment: "")
    }
}

private struct TimelineRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let date: Date?
    let isDone: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(isDone ? iconColor : .secondary.opacity(0.4))
                .frame(width: 24)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(isDone ? .primary : .secondary)

                if let date {
                    Text(formatDate(date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Text(NSLocalizedString("CHAT_MESSAGE_INFO_WAITING", comment: ""))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
    }

    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
