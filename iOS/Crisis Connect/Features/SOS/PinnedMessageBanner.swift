//
//  PinnedMessageBanner.swift
//  Crisis Connect
//
//  Created by Assistant on 08.11.2026.
//

import SwiftUI

struct PinnedMessageBanner: View {
    let message: SOSChatMessage
    let onBannerTap: () -> Void
    let onUnpinTap: () -> Void

    var body: some View {
        Button(action: onBannerTap) {
            HStack(spacing: 12) {
                // Pin icon with accent circle background
                ZStack {
                    Circle()
                        .fill(Color.appPrimary.opacity(0.15))
                        .frame(width: 32, height: 32)
                    Image(systemName: "pin.fill")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(NSLocalizedString("CHAT_PINNED_MESSAGE_TITLE", comment: ""))
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.appPrimary)

                    Text(previewText)
                        .font(.subheadline)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                }

                Spacer(minLength: 8)

                // Unpin / Close button
                Button(action: onUnpinTap) {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.secondary)
                        .padding(6)
                        .background(Color.secondary.opacity(0.12))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.appSurfaceElevated)
                    .shadow(color: Color.black.opacity(0.08), radius: 3, x: 0, y: 1)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Color.appPrimary.opacity(0.25), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    private var previewText: String {
        switch message.kind {
        case .text:
            let stripped = ForwardMetadata.stripForward(message.text)
            return stripped.isEmpty ? message.text : stripped
        case .image:
            return "📷 " + NSLocalizedString("CONVERSATION_PREVIEW_PHOTO_MESSAGE", comment: "")
        case .audio:
            return "🎤 " + NSLocalizedString("CONVERSATION_PREVIEW_VOICE_MESSAGE", comment: "")
        case .location:
            return "📍 " + NSLocalizedString("CONVERSATION_PREVIEW_LOCATION", comment: "")
        case .call:
            return "📞 " + (message.callEventPreviewText ?? "Call")
        case .sosAlert:
            return "🚨 " + message.text
        }
    }
}
