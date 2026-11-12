//
//  ForwardMessageSheet.swift
//  Crisis Connect
//
//  Created by Assistant on 08.11.2026.
//

import SwiftUI

struct ForwardMessageSheet: View {
    let message: SOSChatMessage
    var onForwardComplete: ((Int) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    @ObservedObject private var contactStore = ContactStore.shared
    @State private var searchQuery: String = ""
    @State private var selectedContactIds: Set<UUID> = []
    @State private var isSending: Bool = false

    private var filteredContacts: [ContactRecord] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty {
            return contactStore.contacts
        }
        return contactStore.contacts.filter { contact in
            contact.name.lowercased().contains(q) ||
            contact.sessionCode.lowercased().contains(q)
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Message Preview Card
                messagePreviewHeader
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)

                // Search field
                searchField
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)

                Divider()

                // Contact list
                if filteredContacts.isEmpty {
                    VStack(spacing: 12) {
                        Spacer()
                        Image(systemName: "person.slash")
                            .font(.system(size: 36))
                            .foregroundStyle(.secondary)
                        Text(NSLocalizedString("FORWARD_SHEET_SELECT_CONTACTS", comment: ""))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(filteredContacts) { contact in
                            let isSelected = selectedContactIds.contains(contact.id)
                            ForwardContactRow(
                                contact: contact,
                                isSelected: isSelected,
                                onToggle: {
                                    if isSelected {
                                        selectedContactIds.remove(contact.id)
                                    } else {
                                        selectedContactIds.insert(contact.id)
                                    }
                                }
                            )
                            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
                            .listRowBackground(Color.clear)
                        }
                    }
                    .listStyle(.plain)
                }

                // Bottom Send Button
                bottomActionBar
            }
            .background(Color.appBackground.ignoresSafeArea())
            .navigationTitle(NSLocalizedString("FORWARD_SHEET_TITLE", comment: ""))
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

    private var messagePreviewHeader: some View {
        HStack(spacing: 10) {
            Image(systemName: "arrowshape.turn.up.right.fill")
                .font(.system(size: 14))
                .foregroundStyle(Color.appPrimary)

            VStack(alignment: .leading, spacing: 2) {
                Text(NSLocalizedString("CHAT_MESSAGE_FORWARDED", comment: ""))
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(Color.appPrimary)

                Text(previewText)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            Spacer()
        }
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
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
        default:
            return message.text
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)

            TextField(NSLocalizedString("FORWARD_SHEET_SEARCH_HINT", comment: ""), text: $searchQuery)
                .textFieldStyle(.plain)
                .font(.subheadline)

            if !searchQuery.isEmpty {
                Button(action: { searchQuery = "" }) {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
    }

    private var bottomActionBar: some View {
        VStack(spacing: 0) {
            Divider()
            HStack {
                let count = selectedContactIds.count
                Button(action: performForward) {
                    HStack(spacing: 8) {
                        if isSending {
                            ProgressView()
                                .tint(.white)
                        } else {
                            Image(systemName: "arrowshape.turn.up.right.fill")
                            Text(buttonTitle(count: count))
                                .font(.headline.weight(.semibold))
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 14)
                    .background(count > 0 && !isSending ? Color.appPrimary : Color.secondary.opacity(0.3))
                    .foregroundStyle(count > 0 && !isSending ? Color.white : Color.secondary)
                    .cornerRadius(14)
                }
                .disabled(count == 0 || isSending)
            }
            .padding(16)
        }
        .background(Color.appSurfaceElevated)
    }

    private func buttonTitle(count: Int) -> String {
        if count == 0 {
            return NSLocalizedString("FORWARD_SHEET_SELECT_CONTACTS", comment: "")
        }
        if count == 1, let singleId = selectedContactIds.first, let contact = contactStore.contacts.first(where: { $0.id == singleId }) {
            return String(format: NSLocalizedString("FORWARD_SHEET_SEND_SINGLE", comment: ""), contact.name)
        }
        return String(format: NSLocalizedString("FORWARD_SHEET_SEND_MULTIPLE", comment: ""), count)
    }

    private func performForward() {
        guard !selectedContactIds.isEmpty, !isSending else { return }
        isSending = true

        let selected = contactStore.contacts.filter { selectedContactIds.contains($0.id) }
        let rawText = ForwardMetadata.stripForward(message.text)
        let forwardedText = ForwardMetadata.formatForwardedText(rawText)

        Task {
            var sentCount = 0
            for contact in selected {
                let targetSessionId = contact.id
                let newTransportId = "FWD_\(UUID().uuidString)"

                switch message.kind {
                case .text:
                    let appended = SOSChatStore.shared.appendLocalMessage(
                        sessionId: targetSessionId,
                        text: forwardedText,
                        status: .pending,
                        transportMessageId: newTransportId,
                        kind: .text,
                        isForwarded: true
                    )
                    // If target has internet available, dispatch over internet
                    if let peerUid = contact.peerUid, !peerUid.isEmpty, InternetChatTransport.shared.isAvailable() {
                        let sent = await InternetChatTransport.shared.sendText(
                            messageId: newTransportId,
                            text: forwardedText,
                            contact: contact
                        )
                        if sent {
                            SOSChatStore.shared.markSent(sessionId: targetSessionId, transportMessageId: newTransportId)
                        }
                    }
                    sentCount += 1

                case .image:
                    if let rel = message.imageRelativePath {
                        let newFileName = "IMG_\(UUID().uuidString).jpg"
                        if let src = SOSChatStore.resolveSessionImageURL(fileName: rel),
                           let dst = SOSChatStore.resolveSessionImageURL(fileName: newFileName) {
                            try? FileManager.default.copyItem(at: src, to: dst)
                        }
                        SOSChatStore.shared.appendLocalImageMessage(
                            sessionId: targetSessionId,
                            imageRelativePath: newFileName,
                            thumbnailRelativePath: nil,
                            imageWidth: message.imageWidth,
                            imageHeight: message.imageHeight,
                            imageMimeType: message.imageMimeType,
                            status: .pending,
                            transportMessageId: newTransportId,
                            isForwarded: true
                        )
                        sentCount += 1
                    }

                case .audio:
                    if let rel = message.audioRelativePath {
                        let ext = (rel as NSString).pathExtension
                        let newFileName = "VOICE_\(UUID().uuidString).\(ext.isEmpty ? "m4a" : ext)"
                        if let src = SOSChatStore.resolveSessionAudioURL(fileName: rel),
                           let dst = SOSChatStore.resolveSessionAudioURL(fileName: newFileName) {
                            try? FileManager.default.copyItem(at: src, to: dst)
                        }
                        SOSChatStore.shared.appendLocalAudioMessage(
                            sessionId: targetSessionId,
                            audioRelativePath: newFileName,
                            durationMillis: message.audioDurationMillis,
                            status: .pending,
                            transportMessageId: newTransportId,
                            isForwarded: true
                        )
                        sentCount += 1
                    }

                default:
                    SOSChatStore.shared.appendLocalMessage(
                        sessionId: targetSessionId,
                        text: forwardedText,
                        status: .pending,
                        transportMessageId: newTransportId,
                        kind: message.kind,
                        isForwarded: true
                    )
                    sentCount += 1
                }
            }

            await MainActor.run {
                isSending = false
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                dismiss()
                onForwardComplete?(sentCount)
            }
        }
    }
}

private struct ForwardContactRow: View {
    let contact: ContactRecord
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 12) {
                // Avatar
                ChatAvatarCircleView(
                    avatarImageRelativePath: nil,
                    initials: String(contact.name.prefix(1)).uppercased(),
                    avatarHue: 0.6,
                    size: 44,
                    peerPhotoUrl: contact.peerPhotoUrl
                )

                VStack(alignment: .leading, spacing: 2) {
                    Text(contact.name)
                        .font(.body.weight(.semibold))
                        .foregroundStyle(.primary)

                    Text(contact.sessionCode.prefix(8).uppercased())
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                // Checkbox Circle
                ZStack {
                    Circle()
                        .stroke(isSelected ? Color.appPrimary : Color.secondary.opacity(0.3), lineWidth: 2)
                        .frame(width: 24, height: 24)

                    if isSelected {
                        Circle()
                            .fill(Color.appPrimary)
                            .frame(width: 24, height: 24)

                        Image(systemName: "checkmark")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.white)
                    }
                }
            }
            .padding(.vertical, 4)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
