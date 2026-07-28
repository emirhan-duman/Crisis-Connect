//
//  SOSChatViews.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import AVFoundation
import Combine
import CoreLocation
import MapKit
import PhotosUI
import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct LazyNavigationDestination<Content: View>: View {
    private let build: () -> Content

    init(@ViewBuilder _ build: @escaping () -> Content) {
        self.build = build
    }

    var body: some View {
        build()
    }
}

typealias SOSChatImageSendHandler = (UUID, String, String, Int, Int, String) -> Bool
typealias SOSChatVoiceSendHandler = (UUID, String, String, Int, String) -> Bool
typealias SOSChatFileSendHandler = (UUID, Data, String, String?, Int, String) -> Bool

private struct ChatOperationAlert: Identifiable {
    let id = UUID()
    let title: String
    let message: String
}

struct SOSChatSessionsView: View {
    @ObservedObject private var store = SOSChatStore.shared
    @ObservedObject private var contactStore = ContactStore.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        GeometryReader { proxy in
            let layout = ChatLayoutMetrics(availableWidth: proxy.size.width)

            Group {
                if store.sessions.isEmpty {
                    VStack(spacing: 12) {
                        ContentUnavailableView(LocalizedStringKey("SOS_CHAT_EMPTY_TITLE"), systemImage: "bubble.left.and.bubble.right")
                        Text(LocalizedStringKey("SOS_CHAT_EMPTY_MESSAGE"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .frame(maxWidth: layout.listColumnWidth)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .padding(.horizontal, layout.outerHorizontalPadding)
                    .background(Color.appBackground)
                } else {
                    List {
                        ForEach(sortedSessions) { session in
                            NavigationLink(destination: LazyNavigationDestination {
                                SOSChatDetailScreen(sessionId: session.id)
                            }) {
                                SOSChatSessionRow(
                                    session: session,
                                    contactRecord: contactStore.contact(for: session.id),
                                    lastMessage: store.lastMessage(for: session.id)
                                )
                                    .frame(maxWidth: layout.listColumnWidth, alignment: .leading)
                                    .frame(maxWidth: .infinity, alignment: .center)
                            }
                            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
                            .listRowSeparator(.visible)
                            .listRowSeparatorTint(Color.primary.opacity(0.08))
                            .listRowBackground(Color.appBackground)
                        }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .background(Color.appBackground)
                }
            }
        }
        .navigationTitle(navigationTitle)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(action: { dismiss() }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                }
                .accessibilityLabel(Text("Back"))
            }
        }
    }

    private var sortedSessions: [SOSChatSession] {
        store.sessions.sorted { $0.lastUpdated > $1.lastUpdated }
    }

    private var navigationTitle: LocalizedStringKey {
        let hasDirectContacts = sortedSessions.contains {
            contactStore.contact(for: $0.id)?.preferredTransport == .bleGatt
        }
        return hasDirectContacts ? "Messages" : "SOS_CHAT_TITLE"
    }
}

struct SOSChatDetailScreen: View {
    let sessionId: UUID
    let sendMessageHandler: ((UUID, String, String) -> Bool)?
    let readReceiptHandler: ((UUID, [String]) -> Bool)?
    let sendVoiceHandler: SOSChatVoiceSendHandler?
    let sendImageHandler: SOSChatImageSendHandler?
    let sendFileHandler: SOSChatFileSendHandler?
    let onBack: (() -> Void)?

    init(
        sessionId: UUID,
        sendMessageHandler: ((UUID, String, String) -> Bool)? = nil,
        readReceiptHandler: ((UUID, [String]) -> Bool)? = nil,
        sendVoiceHandler: SOSChatVoiceSendHandler? = nil,
        sendImageHandler: SOSChatImageSendHandler? = nil,
        sendFileHandler: SOSChatFileSendHandler? = nil,
        onBack: (() -> Void)? = nil
    ) {
        self.sessionId = sessionId
        self.sendMessageHandler = sendMessageHandler
        self.readReceiptHandler = readReceiptHandler
        self.sendVoiceHandler = sendVoiceHandler
        self.sendImageHandler = sendImageHandler
        self.sendFileHandler = sendFileHandler
        self.onBack = onBack
    }

    var body: some View {
        SOSChatDetailView(
            sessionId: sessionId,
            sendMessageHandler: sendMessageHandler,
            readReceiptHandler: readReceiptHandler,
            sendVoiceHandler: sendVoiceHandler,
            sendImageHandler: sendImageHandler,
            sendFileHandler: sendFileHandler,
            onBack: onBack
        )
    }
}

struct ChatLayoutMetrics: Equatable {
    let viewportWidth: CGFloat
    let isWideLayout: Bool
    let outerHorizontalPadding: CGFloat
    let columnWidth: CGFloat
    let columnHorizontalPadding: CGFloat

    init(availableWidth: CGFloat) {
        let width = max(availableWidth, 320)
        viewportWidth = width
        isWideLayout = width >= 700
        outerHorizontalPadding = isWideLayout ? 24 : 0

        let usableWidth = max(width - (outerHorizontalPadding * 2), 320)
        columnWidth = isWideLayout ? min(usableWidth, 860) : usableWidth
        columnHorizontalPadding = isWideLayout ? 24 : 16
    }

    var listColumnWidth: CGFloat {
        isWideLayout ? min(columnWidth, 780) : columnWidth
    }

    var transcriptWidth: CGFloat {
        max(280, columnWidth - (columnHorizontalPadding * 2))
    }

    var bubbleMaxWidth: CGFloat {
        let candidate = transcriptWidth * (isWideLayout ? 0.76 : 0.78)
        let cap: CGFloat = isWideLayout ? 540 : 360
        return min(max(candidate, 236), cap)
    }

    var audioBubbleMinWidth: CGFloat {
        isWideLayout ? 240 : 188
    }

    var audioBubbleMaxWidth: CGFloat {
        min(max(bubbleMaxWidth - 18, audioBubbleMinWidth), isWideLayout ? 420 : 244)
    }

    var messageEdgeSpacer: CGFloat {
        isWideLayout ? 92 : 52
    }

    var loadMoreButtonWidth: CGFloat {
        min(max(transcriptWidth * 0.45, 180), 260)
    }
}

struct SOSChatSessionRow: View {
    let session: SOSChatSession
    let contactRecord: ContactRecord?
    let lastMessage: SOSChatMessage?
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var directStatusState: ChatNavigationDirectStatusState
    // Live call state so an in-progress call shows on the home row (Android's
    // buildActiveCallPreviewUi) instead of a stale last-message preview.
    @ObservedObject private var callCoordinator = ChatPeerVoiceCallCoordinator.shared

    init(session: SOSChatSession, contactRecord: ContactRecord?, lastMessage: SOSChatMessage?) {
        self.session = session
        self.contactRecord = contactRecord
        self.lastMessage = lastMessage
        _directStatusState = StateObject(
            wrappedValue: ChatNavigationDirectStatusState(sessionId: session.id)
        )
    }

    var body: some View {
        HStack(spacing: 14) {
            avatarView

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            HStack(spacing: 8) {
                                if showsVerifiedBadge {
                                    VerifiedSealIcon()
                                }
                                Text(displayNameText)
                                    .font(.headline.weight(hasUnread ? .semibold : .medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                    .truncationMode(.tail)
                            }
                            .layoutPriority(1)

                            if let directStatus = directStatusState.status {
                                InlineConnectionPill(status: directStatus)
                                    .layoutPriority(2)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        metadataChips
                    }

                    Spacer(minLength: 8)

                    VStack(alignment: .trailing, spacing: 8) {
                        Text(timestampText(session.lastUpdated))
                            .font(.caption.weight(hasUnread ? .semibold : .medium))
                            .foregroundStyle(hasUnread ? Color.appPrimary : Color.appTextSecondary)

                        if hasUnread {
                            unreadBadge
                        }
                    }
                }

                if let activeCall = activeCallSnapshot {
                    // Live call on THIS session → the row narrates it, like Android.
                    HStack(spacing: 8) {
                        Image(systemName: activeCall.callKind.isVideo ? "video.fill" : "phone.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.appPrimary)
                        Text(activeCallStatusText(for: activeCall))
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(Color.appPrimary)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                    }
                } else {
                    HStack(spacing: 8) {
                        if let previewSymbolName {
                            Image(systemName: previewSymbolName)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(previewSymbolTint)
                        }

                        Text(lastMessagePreviewText)
                            .font(.subheadline.weight(hasUnread ? .semibold : .regular))
                            .foregroundStyle(hasUnread ? Color.primary : Color.appTextSecondary)
                            .lineLimit(1)

                        Spacer(minLength: 8)

                        if let outgoingPreviewStatus {
                            Text(statusLabel(for: outgoingPreviewStatus))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(statusColor(for: outgoingPreviewStatus))
                                .lineLimit(1)
                                .fixedSize(horizontal: true, vertical: false)
                        }
                    }
                }
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
        .task(id: isDirectConversation) {
            directStatusState.setEnabled(isDirectConversation)
        }
    }

    /// The coordinator's call when it belongs to this row's session and is still in flight.
    private var activeCallSnapshot: ChatPeerVoiceCallSnapshot? {
        guard let call = callCoordinator.activeCall, call.sessionId == session.id else { return nil }
        switch call.phase {
        case .dialing, .ringing, .connecting, .active:
            return call
        case .idle, .ended, .failed:
            return nil
        }
    }

    private func activeCallStatusText(for call: ChatPeerVoiceCallSnapshot) -> String {
        let status = call.statusMessage.trimmingCharacters(in: .whitespacesAndNewlines)
        return status.isEmpty ? NSLocalizedString("CHAT_CALL_ACTIVE_ROW", comment: "") : status
    }

    private var avatarInitials: String {
        session.avatarInitials ?? AvatarGenerator.initials(from: displayNameText)
    }

    private var avatarColor: Color {
        let hue = session.avatarHue ?? AvatarGenerator.hue(for: session.id)
        let saturation = colorScheme == .dark ? 0.55 : 0.6
        let brightness = colorScheme == .dark ? 0.75 : 0.9
        return Color(hue: hue, saturation: saturation, brightness: brightness)
    }

    private var avatarView: some View {
        ChatAvatarCircleView(
            avatarImageRelativePath: session.avatarImageRelativePath,
            initials: avatarInitials,
            avatarHue: session.avatarHue ?? AvatarGenerator.hue(for: session.id),
            size: 56,
            borderColor: hasUnread ? avatarColor.opacity(colorScheme == .dark ? 0.55 : 0.34) : Color.clear,
            borderWidth: 2,
            peerPhotoUrl: contactRecord?.peerPhotoUrl
        )
        .overlay(alignment: .bottomTrailing) {
            if let avatarAccessorySymbolName {
                ZStack {
                    Circle()
                        .fill(Color.appSurfaceElevated)
                        .frame(width: 20, height: 20)

                    Image(systemName: avatarAccessorySymbolName)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(avatarAccessoryTint)
                }
                .overlay(
                    Circle()
                        .stroke(Color.appBorder, lineWidth: 1)
                )
            }
        }
    }

    private var lastMessageText: String {
        if let lastMessage, lastMessage.kind == .call {
            return SOSChatCallPresentation.summaryText(for: lastMessage)
        }
        if let lastMessage = session.lastMessage, !lastMessage.isEmpty {
            return P2pSharedTransferSupport.previewText(for: lastMessage)
        }
        return NSLocalizedString("No messages yet", comment: "")
    }

    private var lastMessagePreviewText: String {
        guard let lastMessage,
              !lastMessage.isLocal,
              lastMessage.kind != .call else {
            return lastMessageText
        }
        return "\(displayNameText): \(lastMessageText)"
    }

    private var outgoingPreviewStatus: SOSChatMessageStatus? {
        guard !hasUnread,
              let lastMessage,
              lastMessage.isLocal,
              lastMessage.kind != .call else {
            return nil
        }
        return lastMessage.status
    }

    private var hasUnread: Bool {
        session.unreadCount > 0
    }

    private var isDirectConversation: Bool {
        contactRecord?.preferredTransport == .bleGatt
    }

    private var displayNameText: String {
        if let contactRecord,
           contactRecord.preferredTransport == .bleGatt,
           !contactRecord.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return contactRecord.name
        }
        return session.displayName
    }

    @ViewBuilder
    private var metadataChips: some View {
        HStack(spacing: 6) {
            if let roleBadgeTitle {
                AppStatusPill(title: roleBadgeTitle, tint: roleBadgeTint)
            }

            if showsVerifiedBadge {
                AppStatusPill(title: "SOS_CHAT_VERIFIED", tint: .appPrimary)
            }
        }
    }

    private var roleBadgeTitle: LocalizedStringKey? {
        guard !isDirectConversation else { return nil }
        switch session.role {
        case .fieldTeam:
            return "SOS_CHAT_ROLE_FIELD_TEAM"
        case .victim:
            return "SOS_CHAT_ROLE_VICTIM"
        case .unknown:
            return nil
        }
    }

    private var roleBadgeTint: Color {
        switch session.role {
        case .fieldTeam:
            return .appWarning
        case .victim:
            return .appPrimary
        case .unknown:
            return .appTextSecondary
        }
    }

    private var previewSymbolName: String? {
        guard let lastMessage else { return nil }
        switch lastMessage.kind {
        case .audio:
            return "waveform"
        case .image:
            return "photo"
        case .location:
            return "location.fill"
        case .call:
            return SOSChatCallPresentation.iconName(for: lastMessage)
        case .text:
            if P2pSharedTransferSupport.parseFilePreviewMessage(lastMessage.text) != nil {
                return "doc.fill"
            }
            if P2pSharedTransferSupport.parseLocationMessage(lastMessage.text) != nil {
                return "location.fill"
            }
            return nil
        case .sosAlert:
            return "exclamationmark.triangle.fill"
        }
    }

    private var previewSymbolTint: Color {
        guard let lastMessage else { return .appTextSecondary }
        switch lastMessage.kind {
        case .audio:
            return .appPrimary
        case .image:
            return .appPrimary
        case .location:
            return lastMessage.isLocal ? .appPrimary : .appTextSecondary
        case .call:
            return SOSChatCallPresentation.iconTint(for: lastMessage)
        case .text:
            return lastMessage.isLocal ? .appPrimary : .appTextSecondary
        case .sosAlert:
            return .appDanger
        }
    }

    private var unreadBadge: some View {
        Text("\(session.unreadCount)")
            .font(.caption2.weight(.bold))
            .monospacedDigit()
            .padding(.horizontal, 8)
            .padding(.vertical, 5)
            .background(
                Capsule(style: .continuous)
                    .fill(Color.whatsAppAccent)
            )
            .foregroundStyle(Color.white)
    }

    private var avatarAccessorySymbolName: String? {
        if showsVerifiedBadge {
            return "checkmark.seal.fill"
        }
        if session.role == .fieldTeam {
            return "cross.case.fill"
        }
        return nil
    }

    private var avatarAccessoryTint: Color {
        if showsVerifiedBadge {
            return .appPrimary
        }
        if session.role == .fieldTeam {
            return .appWarning
        }
        return .appTextSecondary
    }

    private var showsVerifiedBadge: Bool {
        counterpartyIsFieldTeam && (session.showsVerificationBadge || contactRecord?.isVerified == true)
    }

    private var counterpartyIsFieldTeam: Bool {
        switch session.role {
        case .fieldTeam:
            return true
        case .victim:
            return false
        case .unknown:
            return false
        }
    }

    private func timestampText(_ date: Date) -> String {
        let calendar = Calendar.autoupdatingCurrent
        if calendar.isDateInToday(date) {
            return Self.timeFormatter.string(from: date)
        }
        if calendar.isDateInYesterday(date) {
            return Self.relativeDayFormatter.localizedString(for: date, relativeTo: Date())
        }
        if let weekAgo = calendar.date(byAdding: .day, value: -6, to: Date()),
           date >= weekAgo {
            return Self.weekdayFormatter.string(from: date)
        }
        return Self.shortDateFormatter.string(from: date)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter
    }()

    private static let weekdayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEE")
        return formatter
    }()

    private static let shortDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("d MMM")
        return formatter
    }()

    private static let relativeDayFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter
    }()

    private func statusColor(for status: SOSChatMessageStatus) -> Color {
        switch status {
        case .pending:
            return .appTextSecondary
        case .sent, .delivered:
            return .appPrimary
        case .read:
            return .appSuccess
        case .failed:
            return .red
        }
    }

    private func statusLabel(for status: SOSChatMessageStatus) -> LocalizedStringKey {
        switch status {
        case .pending:
            return "SOS_CHAT_STATUS_PENDING"
        case .sent:
            return "SOS_CHAT_STATUS_SENT"
        case .delivered:
            return "SOS_CHAT_STATUS_DELIVERED"
        case .read:
            return "SOS_CHAT_STATUS_READ"
        case .failed:
            return "SOS_CHAT_STATUS_FAILED"
        }
    }
}

private struct SOSChatHeader: View {
    let session: SOSChatSession

    var body: some View {
        HStack {
            Spacer()
            HStack(spacing: 6) {
                if session.showsVerificationBadge {
                    VerifiedSealIcon()
                } else {
                    Image(systemName: "info.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                infoText
                    .font(.caption)
                    .foregroundStyle(Color.whatsAppInfoText)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.whatsAppInfoBackground)
            )
            Spacer()
        }
    }

    private var infoText: Text {
        let baseLabel: Text
        if session.role == .unknown {
            baseLabel = Text(LocalizedStringKey("SOS_CHAT_ROLE_CONTACT"))
        } else {
            baseLabel = Text(roleLabel(for: session.role))
        }
        if session.showsVerificationBadge {
            return Text("\(baseLabel) - \(Text(LocalizedStringKey("SOS_CHAT_VERIFIED")))")
        }
        return baseLabel
    }

    private func roleLabel(for role: SOSChatRole) -> LocalizedStringKey {
        switch role {
        case .fieldTeam:
            return "SOS_CHAT_ROLE_FIELD_TEAM"
        case .victim:
            return "SOS_CHAT_ROLE_VICTIM"
        case .unknown:
            return "SOS_CHAT_ROLE_UNKNOWN"
        }
    }

    private func relativeTime(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

private struct SOSChatEncryptionNoticeCard: View {
    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "lock")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Color.whatsAppInfoText)
                .frame(width: 18, height: 18)

            Text("SOS_CHAT_E2EE_NOTICE")
                .font(.footnote)
                .foregroundStyle(Color.whatsAppInfoText)
                .frame(maxWidth: .infinity, alignment: .leading)
                .multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.whatsAppInfoBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.black.opacity(0.05), lineWidth: 0.5)
        )
    }
}

struct SOSChatDetailView: View {
    private static let transportQueue = DispatchQueue(label: "sos.chat.transport", qos: .userInitiated)

    let sessionId: UUID
    let sendMessageHandler: ((UUID, String, String) -> Bool)?
    let readReceiptHandler: ((UUID, [String]) -> Bool)?
    let sendVoiceHandler: SOSChatVoiceSendHandler?
    let sendImageHandler: SOSChatImageSendHandler?
    let sendFileHandler: SOSChatFileSendHandler?
    let onBack: (() -> Void)?

    private let store = SOSChatStore.shared
    private let cachedP2pGattChat: P2pGattChatManager
    @StateObject private var detailState: SOSChatDetailState
    @StateObject private var contactState: SOSChatContactState
    @StateObject private var contactLink = ContactLinkManager()
    @StateObject private var voiceCallController: ChatPeerVoiceCallController
    @StateObject private var voiceComposer = ChatVoiceComposer()
    @StateObject private var locationCoordinator = ChatAttachmentLocationCoordinator()
    @ObservedObject private var typingBus = TypingIndicatorBus.shared
    // Observed so the toolbar call button re-enables the moment a prior call clears (otherwise the
    // .disabled state was read once and left the button greyed out).
    @ObservedObject private var internetCall = InternetCallManager.shared
    @ObservedObject private var rescueCallEngine = RescueCallEngine.shared
    @State private var draft: String = ""
    /// Drives the one-time "save as emergency contact?" prompt (parity with Android's chat screen).
    @State private var showEmergencyContactPrompt = false
    @State private var lastTypingPulseAt: Date = .distantPast
    // A quoted-reply tap flashes the jumped-to bubble briefly.
    @State private var highlightedMessageId: UUID?
    @State private var highlightClearTask: DispatchWorkItem?
    @State private var pendingReadWorkItem: DispatchWorkItem?
    @State private var isSendingVoice = false
    @State private var isSendingImage = false
    @State private var isSendingDocument = false
    @State private var isSharingLocation = false
    @State private var pendingImage: PendingChatImage?
    @State private var mediaPickerSource: ChatMediaPickerSource?
    @State private var showsDocumentPicker = false
    @State private var showsAttachmentOptions = false
    @State private var fullScreenImageMessage: SOSChatMessage?
    @State private var previewedLocationMessage: SOSChatMessage?
    @State private var previewedSharedFile: PreviewedSharedFile?
    @State private var replyTargetMessageId: UUID?
    @State private var showsChatInfo = false
    /// In-chat message search (Android ChatScreenRoute parity): filter + jump-and-highlight.
    @State private var operationAlert: ChatOperationAlert?
    @State private var isTranscriptReady = false
    @State private var scrollTargetMessageId: UUID?
    // Newest message the user has actually had on screen — drives the scroll-to-bottom badge.
    // ID-based (not a count) so loading OLDER pages, which prepends, can't inflate it.
    @State private var lastSeenMessageId: UUID?
    @State private var transcriptStartupTask: Task<Void, Never>?
    @State private var transportStartupTask: Task<Void, Never>?
    @State private var statusTrackingTask: Task<Void, Never>?
    @State private var statusTrackingEnabled = false
    @State private var hasStartedRealtimeServices = false
    @State private var suppressFullScreenCall = false
    @FocusState private var isFocused: Bool
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dismiss) private var dismiss

    init(
        sessionId: UUID,
        sendMessageHandler: ((UUID, String, String) -> Bool)? = nil,
        readReceiptHandler: ((UUID, [String]) -> Bool)? = nil,
        sendVoiceHandler: SOSChatVoiceSendHandler? = nil,
        sendImageHandler: SOSChatImageSendHandler? = nil,
        sendFileHandler: SOSChatFileSendHandler? = nil,
        onBack: (() -> Void)? = nil
    ) {
        self.sessionId = sessionId
        self.sendMessageHandler = sendMessageHandler
        self.readReceiptHandler = readReceiptHandler
        self.sendVoiceHandler = sendVoiceHandler
        self.sendImageHandler = sendImageHandler
        self.sendFileHandler = sendFileHandler
        self.onBack = onBack
        self.cachedP2pGattChat = P2pGattChatManager.shared(sessionId: sessionId)
        _detailState = StateObject(wrappedValue: SOSChatDetailState(sessionId: sessionId))
        _contactState = StateObject(wrappedValue: SOSChatContactState(sessionId: sessionId))
        _voiceCallController = StateObject(wrappedValue: ChatPeerVoiceCallController(sessionId: sessionId))
    }

    var body: some View {
        GeometryReader { proxy in
            let layout = ChatLayoutMetrics(availableWidth: proxy.size.width)

            ZStack(alignment: .bottom) {
                VStack(spacing: 0) {
                    if shouldShowCompactCallPanel {
                        callPanelView(layout: layout)
                    }

                    if isTranscriptReady {
                        transcriptView(layout: layout)
                    } else {
                        transcriptPlaceholderView(layout: layout)
                    }

                    Divider()

                    composerView(layout: layout)
                }

                if showsAttachmentOptions {
                    attachmentOptionsOverlay(
                        layout: layout,
                        safeAreaBottomInset: proxy.safeAreaInsets.bottom
                    )
                }
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(action: handleBackAction) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                }
                .accessibilityLabel(Text("Back"))
            }
            ToolbarItem(placement: .principal) {
                // Tapping the header opens the contact-info screen (avatar, session code and the
                // safety-number card) — same navigation as Android's chat header.
                Button {
                    showsChatInfo = true
                } label: {
                    ChatNavigationTitleContainer(
                        sessionId: sessionId,
                        title: sessionTitle,
                        subtitle: navigationSubtitle,
                        initials: avatarInitials,
                        avatarHue: avatarHue,
                        avatarImageRelativePath: currentSession?.avatarImageRelativePath,
                        showsVerifiedTrust: (currentSession?.showsVerificationBadge ?? false) || (contactRecord?.isVerified == true),
                        allowVerifiedTitleIcon: counterpartyIsFieldTeam,
                        showsDirectStatus: statusTrackingEnabled,
                        peerUid: contactRecord?.peerUid,
                        internetCapable: contactRecord?.supportsInternet == true
                    )
                }
                .buttonStyle(.plain)
            }
            // Internet (WebRTC) voice call for internet-identified contacts when the BLE call
            // stack isn't offering its own button — mirrors Android preferring the live link.
            if !showsVoiceCallAction, contactRecord?.supportsInternet == true {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        if let contactRecord {
                            InternetCallManager.shared.startCall(contact: contactRecord)
                        }
                    } label: {
                        Image(systemName: "phone")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Color.appPrimary)
                    }
                    .disabled(internetCall.call != nil)
                    .accessibilityLabel(Text(LocalizedStringKey("VOICE_CALL_ACTION")))
                }
            }
            if showsRescueCallAction {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button {
                        AVAudioApplication.requestRecordPermission { granted in
                            guard granted else { return }
                            DispatchQueue.main.async {
                                _ = RescueCallEngine.shared.startCall(sessionId: sessionId)
                            }
                        }
                    } label: {
                        Image(systemName: "phone")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(Color.appPrimary)
                    }
                    .disabled(rescueCallEngine.call != nil)
                    .accessibilityLabel(Text(LocalizedStringKey("VOICE_CALL_ACTION")))
                }
            }
            if showsVoiceCallAction {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if voiceCallController.isCallForCurrentSession {
                        Button(action: voiceCallController.toolbarPrimaryAction) {
                            Image(systemName: voiceCallController.toolbarSystemImage)
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(voiceCallController.toolbarTintColor)
                        }
                        .accessibilityLabel(Text(voiceCallController.toolbarAccessibilityLabel))
                    } else {
                        if voiceCallController.shouldShowToolbarVideoAction {
                            Button(action: voiceCallController.toolbarVideoAction) {
                                Image(systemName: voiceCallController.videoToolbarSystemImage)
                                    .font(.system(size: 17, weight: .semibold))
                                    .foregroundStyle(Color.appPrimary)
                            }
                            .accessibilityLabel(Text(voiceCallController.videoToolbarAccessibilityLabel))
                        }

                        Button(action: voiceCallController.toolbarPrimaryAction) {
                            Image(systemName: voiceCallController.toolbarSystemImage)
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(voiceCallController.toolbarTintColor)
                        }
                        .accessibilityLabel(Text(voiceCallController.toolbarAccessibilityLabel))
                    }
                }
            }
        }
        .appNavigationBarStyle()
        .overlay {
            if let rescueCall = rescueCallEngine.call, rescueCall.sessionId == sessionId {
                RescueCallOverlayView(
                    call: rescueCall,
                    fallbackName: sessionTitle,
                    onAccept: { RescueCallEngine.shared.accept() },
                    onReject: { RescueCallEngine.shared.reject() },
                    onEnd: { RescueCallEngine.shared.endCall() },
                    onToggleMute: { RescueCallEngine.shared.setMuted(!rescueCall.muted) },
                    onToggleSpeaker: { RescueCallEngine.shared.setSpeaker(!rescueCall.speakerOn) }
                )
            }
        }
        .background(chatBackground)
        .sheet(item: $mediaPickerSource) { source in
            ChatImagePicker(source: source) { selection in
                handleImageSelection(selection)
            }
        }
        .sheet(isPresented: $showsDocumentPicker) {
            ChatDocumentPicker { url in
                handleDocumentSelection(url)
            }
        }
        .sheet(item: $previewedLocationMessage) { message in
            ChatLocationDetailSheet(message: message)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .analyticsScreen("chat_detail")
        .navigationDestination(isPresented: $showsChatInfo) {
            ChatInfoView(
                sessionId: sessionId,
                title: sessionTitle,
                subtitle: navigationSubtitle,
                initials: avatarInitials,
                avatarHue: avatarHue,
                avatarImageRelativePath: currentSession?.avatarImageRelativePath,
                contact: contactRecord
            )
        }
        .sheet(item: $previewedSharedFile) { preview in
            ChatSharedFilePreview(url: preview.url)
        }
        .alert(item: $operationAlert) { alert in
            Alert(
                title: Text(alert.title),
                message: Text(alert.message),
                dismissButton: .default(Text("OK"))
            )
        }
        .fullScreenCover(item: $fullScreenImageMessage) { message in
            ChatImageViewer(message: message) {
                fullScreenImageMessage = nil
            }
        }
        .fullScreenCover(isPresented: fullScreenCallBinding) {
            ChatPeerVoiceOngoingCallScreen(
                controller: voiceCallController,
                contactName: sessionTitle,
                avatarImageRelativePath: currentSession?.avatarImageRelativePath,
                avatarHue: avatarHue,
                initials: avatarInitials,
                onReturnToChat: {
                    suppressFullScreenCall = true
                }
            )
        }
        .onAppear {
            SOSNotificationCenter.registerVisibleSession(sessionId)
            isTranscriptReady = false
        }
        .task(id: sessionId) {
            detailState.prepare()
            contactState.prepare()
            voiceCallController.updateContact(contactRecord)
            scheduleTranscriptPresentation()
            scheduleLocalReadStateUpdate()
            ensureRealtimeServicesStarted()
            maybeAutoLinkBluetooth()
            // Android asks once, on a freshly established internet conversation, whether this person
            // should be alerted automatically when the user starts an SOS. iOS had the store and the
            // settings screen but never the question, so unless the user went hunting in Settings the
            // list stayed empty — and an empty list is what makes SosContactNotifier fall back to
            // auto-picking whoever it ranks highest.
            if let contactRecord, SosEmergencyContactsStore.shouldPrompt(for: contactRecord) {
                showEmergencyContactPrompt = true
            }
            // Own shared-location bubbles go live while this chat holds a Bluetooth link
            // (Android's live-location bubble); the tracker polls the link and gates the GPS.
            if usesBleGattDirectChat {
                let sessionId = sessionId
                ChatLiveOwnLocationTracker.shared.activate(sessionId: sessionId) {
                    contactBroadcastManager.isSessionConnected(sessionId)
                        || p2pGattChat.isReady()
                }
            }
        }
        .alert(
            "SOS_EC_PROMPT_TITLE",
            isPresented: $showEmergencyContactPrompt
        ) {
            Button("SOS_EC_PROMPT_CONFIRM") {
                if let contactRecord {
                    SosEmergencyContactsStore.addEmergencyContact(contactRecord.id)
                }
            }
            // Declining still records that we asked — the question is one-time either way.
            Button("SOS_EC_PROMPT_DECLINE", role: .cancel) {
                if let contactRecord {
                    SosEmergencyContactsStore.markPrompted(contactRecord.id)
                }
            }
        } message: {
            Text(String(
                format: NSLocalizedString("SOS_EC_PROMPT_MESSAGE", comment: ""),
                contactRecord?.name ?? ""
            ))
        }
        .onChange(of: usesBleGattDirectChat) { _, _ in
            voiceCallController.updateContact(contactRecord)
            ensureRealtimeServicesStarted()
        }
        .onChange(of: contactRecord) { _, nextContact in
            voiceCallController.updateContact(nextContact)
        }
        .onChange(of: draft) { _, text in
            sendTypingPulseIfNeeded(for: text)
        }
        .onChange(of: isFocused) { _, focused in
            guard focused else { return }
            ensureRealtimeServicesStarted()
        }
        .onChange(of: voiceCallController.phase) { oldPhase, newPhase in
            if newPhase.isLivePresentationPhase && !oldPhase.isLivePresentationPhase {
                suppressFullScreenCall = false
            } else if newPhase == .idle {
                suppressFullScreenCall = false
            }
        }
        .onDisappear {
            ChatLiveOwnLocationTracker.shared.deactivate()
            SOSNotificationCenter.unregisterVisibleSession(sessionId)
            transcriptStartupTask?.cancel()
            transcriptStartupTask = nil
            transportStartupTask?.cancel()
            transportStartupTask = nil
            statusTrackingTask?.cancel()
            statusTrackingTask = nil
            statusTrackingEnabled = false
            pendingReadWorkItem?.cancel()
            pendingReadWorkItem = nil
            contactLink.stop()
            p2pGattChat.stop()
            voiceComposer.cancelRecording()
            showsAttachmentOptions = false
            pendingImage = nil
            mediaPickerSource = nil
            showsDocumentPicker = false
            previewedLocationMessage = nil
            previewedSharedFile = nil
            fullScreenImageMessage = nil
            replyTargetMessageId = nil
            if usesBleGattDirectChat {
                contactBroadcastManager.setForegroundSession(nil)
            }
        }
    }

    private func handleBackAction() {
        if let onBack {
            onBack()
            return
        }
        dismiss()
    }

    @ViewBuilder
    private func transcriptPlaceholderView(layout: ChatLayoutMetrics) -> some View {
        ScrollView {
            VStack(spacing: 12) {
                ForEach(0..<3, id: \.self) { index in
                    HStack {
                        if index.isMultiple(of: 2) {
                            Spacer(minLength: layout.messageEdgeSpacer)
                        }
                        RoundedRectangle(cornerRadius: 20, style: .continuous)
                            .fill(Color.primary.opacity(0.08))
                            .frame(
                                width: min(layout.bubbleMaxWidth, index == 1 ? 220 : 174),
                                height: index == 2 ? 86 : 54
                            )
                        if !index.isMultiple(of: 2) {
                            Spacer(minLength: layout.messageEdgeSpacer)
                        }
                    }
                    .redacted(reason: .placeholder)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, layout.columnHorizontalPadding)
            .padding(.top, 12)
            .padding(.bottom, 16)
            .frame(maxWidth: layout.columnWidth)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, layout.outerHorizontalPadding)
        }
        .scrollDisabled(true)
        .background(chatBackground)
    }

    @ViewBuilder
    private func transcriptView(layout: ChatLayoutMetrics) -> some View {
        ScrollView {
            Group {
                if messages.isEmpty {
                    transcriptEmptyStateView(layout: layout)
                } else {
                    VStack(spacing: 0) {
                        LazyVStack(spacing: 12) {
                            if hasOlderMessages {
                                Button(action: { detailState.loadOlderMessages() }) {
                                    HStack(spacing: 8) {
                                        Image(systemName: "arrow.up.circle.fill")
                                            .font(.subheadline.weight(.semibold))
                                        Text(LocalizedStringKey("SOS_CHAT_LOAD_OLDER"))
                                            .font(.subheadline.weight(.semibold))
                                    }
                                    .foregroundStyle(Color.whatsAppAccent)
                                    .frame(maxWidth: layout.loadMoreButtonWidth)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 10)
                                    .background(
                                        Capsule(style: .continuous)
                                            .fill(Color.whatsAppInfoBackground)
                                    )
                                }
                                .buttonStyle(.plain)
                                .padding(.bottom, 4)
                            }

                            if isDirectContactConversation {
                                SOSChatEncryptionNoticeCard()
                            } else if let session = currentSession {
                                SOSChatHeader(session: session)
                            }

                            ForEach(messages) { message in
                                if let separatorDate = daySeparatorDates[message.id] {
                                    ChatDaySeparator(label: daySeparatorLabel(for: separatorDate))
                                }
                                SOSChatBubble(
                                    message: message,
                                    layout: layout,
                                    onImageTap: { tappedMessage in
                                        fullScreenImageMessage = tappedMessage
                                    },
                                    onLocationTap: { tappedMessage in
                                        presentLocationPreview(for: tappedMessage)
                                    },
                                    onFileTap: { tappedMessage in
                                        presentSharedFilePreview(for: tappedMessage)
                                    },
                                    onReplyTap: { transportId in
                                        jumpToRepliedMessage(transportMessageId: transportId)
                                    }
                                )
                                .equatable()
                                .background(
                                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                                        .fill(Color.whatsAppAccent.opacity(highlightedMessageId == message.id ? 0.18 : 0))
                                        .padding(.horizontal, -6)
                                        .padding(.vertical, -3)
                                )
                                .chatSwipeReply {
                                    activateReply(to: message)
                                }
                                .contextMenu {
                                    // Only TEXT has a store-and-forward retry path (the queue re-sends
                                    // text bubbles by their transportMessageId). An attachment's bytes
                                    // aren't re-queued, so we never offer retry for one — matching
                                    // Android, and avoiding a bubble stuck forever at .pending.
                                    if message.isLocal, message.status == .failed, message.kind == .text {
                                        Button(action: { retrySend(message) }) {
                                            Label(
                                                LocalizedStringKey("SOS_CHAT_ACTION_RETRY_SEND"),
                                                systemImage: "arrow.clockwise"
                                            )
                                        }
                                    }
                                    Button(action: {
                                        activateReply(to: message)
                                    }) {
                                        Label(
                                            LocalizedStringKey("SOS_CHAT_ACTION_REPLY"),
                                            systemImage: "arrowshape.turn.up.left.fill"
                                        )
                                    }
                                    Button(action: {
                                        copyChatTextToPasteboard(message.copyableText)
                                    }) {
                                        Label(
                                            LocalizedStringKey("CHAT_ACTION_COPY"),
                                            systemImage: "doc.on.doc"
                                        )
                                    }
                                    .disabled(message.copyableText == nil)
                                }
                            }
                            if isPeerTyping {
                                ChatTypingBubble()
                                    .transition(.opacity)
                            }
                        }
                        .animation(.easeInOut(duration: 0.2), value: isPeerTyping)
                        .scrollTargetLayout()
                        .padding(.horizontal, layout.columnHorizontalPadding)
                        .padding(.top, 12)
                        .padding(.bottom, 16)
                    }
                    .frame(maxWidth: layout.columnWidth)
                    .frame(maxWidth: .infinity)
                    .padding(.horizontal, layout.outerHorizontalPadding)
                }
            }
        }
        .defaultScrollAnchor(.top)
        .scrollPosition(id: $scrollTargetMessageId, anchor: .bottom)
        .scrollDismissesKeyboard(.interactively)
        .background(chatBackground)
        .overlay(alignment: .bottom) {
            if let lastId = messages.last?.id,
               let target = scrollTargetMessageId,
               target != lastId {
                ChatScrollToBottomButton(count: unseenTailCount) {
                    lastSeenMessageId = lastId
                    withAnimation(.easeOut(duration: 0.25)) {
                        scrollTargetMessageId = lastId
                    }
                }
                .padding(.bottom, 12)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
            }
        }
        .onChange(of: messages.last?.id) { previousLastId, lastId in
            guard let lastId else { return }
            // Follow the conversation only while the user is at the bottom — or when the new
            // message is their own. Scrolled up, the floating button badges arrivals instead
            // (Android parity).
            let wasAtBottom = scrollTargetMessageId == nil || scrollTargetMessageId == previousLastId
            if messages.last?.isLocal == true || wasAtBottom {
                lastSeenMessageId = lastId
                if scrollTargetMessageId != lastId {
                    scrollTargetMessageId = lastId
                }
            }
        }
        .onChange(of: scrollTargetMessageId) { _, target in
            // Scrolled back down by hand → everything is seen.
            if let target, target == messages.last?.id {
                lastSeenMessageId = target
            }
        }
        .onChange(of: totalMessageCount) { _, _ in
            if messages.last != nil {
                scheduleLocalReadStateUpdate()
                if hasStartedRealtimeServices {
                    scheduleReadReceiptIfNeeded()
                }
            }
        }
        .onAppear {
            scrollToLatestMessage()
        }
    }

    @ViewBuilder
    private func composerView(layout: ChatLayoutMetrics) -> some View {
        VStack(spacing: 10) {
            if let replyTargetMessage {
                ChatComposerReplyBanner(
                    authorLabel: replyAuthorLabel(for: replyTargetMessage),
                    preview: replyTargetMessage.replyPreviewText,
                    onDismiss: {
                        self.replyTargetMessageId = nil
                    }
                )
            }

            if let pendingImage {
                ImageAttachmentConfirmationView(
                    image: pendingImage.previewImage,
                    isSending: isSendingImage,
                    onDiscard: {
                        self.pendingImage = nil
                    },
                    onSend: { sendPendingImage() }
                )
            } else if voiceComposer.isRecording {
                VoiceRecordingIndicatorView(
                    durationLabel: voiceComposer.liveDurationLabel,
                    onStop: { voiceComposer.stopRecording() },
                    onCancel: { voiceComposer.cancelRecording() }
                )
            } else if voiceComposer.hasPendingRecording {
                VoiceRecordingConfirmationView(
                    durationLabel: voiceComposer.recordedDurationLabel,
                    isSending: isSendingVoice,
                    onDiscard: { voiceComposer.discardRecording() },
                    onSend: { sendVoiceMessage() }
                )
            } else {
                HStack(spacing: layout.isWideLayout ? 14 : 12) {
                    if canAttachImage {
                        Button(action: {
                            ensureRealtimeServicesStarted()
                            isFocused = false
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.86)) {
                                showsAttachmentOptions = true
                            }
                        }) {
                            Image(systemName: "paperclip")
                                .font(.headline)
                                .foregroundStyle(Color.whatsAppAccent)
                                .frame(width: 40, height: 40)
                                .background(
                                    Circle()
                                        .fill(Color.whatsAppInputBackground)
                                )
                                .overlay(
                                    Circle()
                                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
                                )
                        }
                        .buttonStyle(.plain)
                        .disabled(isSendingImage || isSendingDocument || isSharingLocation)
                    }

                    TextField(LocalizedStringKey("SOS_CHAT_SEND_PLACEHOLDER"), text: $draft)
                        .textFieldStyle(.plain)
                        .focused($isFocused)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(Color.whatsAppInputBackground)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
                        )
                        .onSubmit { composerPrimaryAction() }

                    Button(action: composerPrimaryAction) {
                        Image(systemName: sendEnabled ? "paperplane.fill" : "mic.fill")
                            .font(.headline)
                            .foregroundColor(.white)
                            .padding(12)
                            .background(
                                Circle()
                                    .fill(
                                        composerActionEnabled
                                            ? Color.whatsAppAccent
                                            : Color.whatsAppAccent.opacity(0.35)
                                    )
                            )
                    }
                    .buttonStyle(.plain)
                    .disabled(!composerActionEnabled)
                }
            }
        }
        .padding(.horizontal, layout.columnHorizontalPadding)
        .frame(maxWidth: layout.columnWidth)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, layout.outerHorizontalPadding)
        .padding(.vertical, 12)
        .background(Color.whatsAppBarBackground)
    }

    private var messages: [SOSChatMessage] {
        detailState.messages
    }

    /// True while the peer is actively typing (internet typing pulse, kept alive ~6s per pulse).
    private var isPeerTyping: Bool {
        (typingBus.typing[sessionId] ?? .distantPast) > Date()
    }

    /// message.id → the day-start Date to render as a separator ABOVE it: the first message of each
    /// calendar day. Keeps the transcript keyed by message UUIDs (scroll position stays intact)
    /// while giving WhatsApp-style "Today / Yesterday / date" dividers (Android parity).
    private var daySeparatorDates: [UUID: Date] {
        var result: [UUID: Date] = [:]
        let calendar = Calendar.current
        var previousDay: Date?
        for message in messages {
            let day = calendar.startOfDay(for: message.timestamp)
            if previousDay == nil || day != previousDay {
                result[message.id] = day
            }
            previousDay = day
        }
        return result
    }

    private static let daySeparatorFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter
    }()

    private func daySeparatorLabel(for date: Date) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            return NSLocalizedString("CHAT_DATE_TODAY", comment: "")
        }
        if calendar.isDateInYesterday(date) {
            return NSLocalizedString("CHAT_DATE_YESTERDAY", comment: "")
        }
        return Self.daySeparatorFormatter.string(from: date)
    }

    /// Quoted-reply tap: find the original bubble by its transport id, scroll to it, flash it.
    private func jumpToRepliedMessage(transportMessageId: String) {
        let normalized = transportMessageId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalized.isEmpty,
              let target = messages.last(where: {
                  ($0.transportMessageId ?? "").trimmingCharacters(in: .whitespacesAndNewlines) == normalized
              }) else { return }
        withAnimation(.easeInOut(duration: 0.25)) {
            scrollTargetMessageId = target.id
            highlightedMessageId = target.id
        }
        UISelectionFeedbackGenerator().selectionChanged()
        highlightClearTask?.cancel()
        let work = DispatchWorkItem {
            withAnimation(.easeOut(duration: 0.4)) { self.highlightedMessageId = nil }
        }
        highlightClearTask = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4, execute: work)
    }

    /// Scroll-and-flash for a message picked in the search sheet — the same jump the reply-quote
    /// tap uses, keyed by row id instead of transport id.
    private func jumpToMessage(_ message: SOSChatMessage) {
        withAnimation(.easeInOut(duration: 0.25)) {
            scrollTargetMessageId = message.id
            highlightedMessageId = message.id
        }
        UISelectionFeedbackGenerator().selectionChanged()
        highlightClearTask?.cancel()
        let work = DispatchWorkItem {
            withAnimation(.easeOut(duration: 0.4)) { self.highlightedMessageId = nil }
        }
        highlightClearTask = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.4, execute: work)
    }

    private var totalMessageCount: Int {
        detailState.totalMessageCount
    }

    private var hasOlderMessages: Bool {
        detailState.hasOlderMessages
    }

    private var currentSession: SOSChatSession? {
        detailState.session
    }

    private var replyTargetMessage: SOSChatMessage? {
        guard let replyTargetMessageId else { return nil }
        return messages.first { $0.id == replyTargetMessageId }
    }

    private var contactBroadcastManager: ContactBroadcastManager {
        .shared
    }

    private var broadcastManager: SOSBroadcastManager {
        .shared
    }

    private var contactRecord: ContactRecord? {
        contactState.contact
    }

    private var isDirectContactConversation: Bool {
        if let contactRecord {
            return contactRecord.preferredTransport == .bleGatt
        }
        return currentSession?.role == .unknown
    }

    private var usesBleGattDirectChat: Bool {
        contactRecord?.preferredTransport == .bleGatt
    }

    private var showsVoiceCallAction: Bool {
        AppStoreScreenshotSupport.isAnySceneEnabled || voiceCallController.shouldShowToolbarAction
    }

    /// Live rescue-link call (rescuer↔victim over 0xCC40/0xCC41): shown only for rescue
    /// sessions with a call-capable link, and never next to the contact-call button.
    /// Reads the PUBLISHED ready-set (not canCall()) so the button appears reactively the
    /// moment the rescue link finishes its handshake.
    private var showsRescueCallAction: Bool {
        guard !showsVoiceCallAction else { return false }
        guard currentSession?.role == .fieldTeam || currentSession?.role == .victim else {
            return false
        }
        return rescueCallEngine.call != nil
            || rescueCallEngine.readySessionIds.contains(sessionId)
    }

    private var emptyStateTitleKey: LocalizedStringKey {
        isDirectContactConversation ? "DIRECT_CHAT_EMPTY_TITLE" : "SOS_CHAT_EMPTY_TITLE"
    }

    private var emptyStateMessageKey: LocalizedStringKey {
        isDirectContactConversation ? "DIRECT_CHAT_EMPTY_MESSAGE" : "SOS_CHAT_EMPTY_MESSAGE"
    }

    private var cameraAvailable: Bool {
        UIImagePickerController.isSourceTypeAvailable(.camera)
    }

    private var counterpartyIsFieldTeam: Bool {
        switch currentSession?.role {
        case .fieldTeam:
            return true
        case .victim:
            return false
        case .unknown:
            return false
        case nil:
            return false
        }
    }

    private var p2pGattChat: P2pGattChatManager {
        cachedP2pGattChat
    }

    private var shouldShowCompactCallPanel: Bool {
        if voiceCallController.shouldPresentFullScreenExperience {
            return suppressFullScreenCall
        }
        return voiceCallController.shouldShowPanel
    }

    private var fullScreenCallBinding: Binding<Bool> {
        Binding(
            get: {
                voiceCallController.shouldPresentFullScreenExperience && !suppressFullScreenCall
            },
            set: { isPresented in
                if !isPresented, voiceCallController.shouldPresentFullScreenExperience {
                    suppressFullScreenCall = true
                }
            }
        )
    }

    @ViewBuilder
    private func transcriptEmptyStateView(layout: ChatLayoutMetrics) -> some View {
        VStack(spacing: 14) {
            if isDirectContactConversation {
                SOSChatEncryptionNoticeCard()
            } else if let session = currentSession {
                SOSChatHeader(session: session)
            }

            VStack(spacing: 12) {
                ContentUnavailableView(
                    emptyStateTitleKey,
                    systemImage: "bubble.left.and.bubble.right"
                )
                Text(emptyStateMessageKey)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .frame(maxWidth: .infinity)
        }
        .padding(.horizontal, layout.columnHorizontalPadding)
        .padding(.top, 12)
        .padding(.bottom, 16)
        .frame(maxWidth: layout.columnWidth, alignment: .top)
        .frame(maxWidth: .infinity, alignment: .top)
        .padding(.horizontal, layout.outerHorizontalPadding)
    }

    @ViewBuilder
    private func callPanelView(layout: ChatLayoutMetrics) -> some View {
        ChatPeerVoiceCallPanel(controller: voiceCallController, contactName: sessionTitle)
            .padding(.horizontal, layout.columnHorizontalPadding)
            .frame(maxWidth: layout.columnWidth)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, layout.outerHorizontalPadding)
            .padding(.top, 12)
            .padding(.bottom, 8)
            .background(Color.appBackground)
    }

    @ViewBuilder
    private func attachmentOptionsOverlay(
        layout: ChatLayoutMetrics,
        safeAreaBottomInset: CGFloat
    ) -> some View {
        ZStack(alignment: .bottom) {
            Color.black
                .opacity(colorScheme == .dark ? 0.38 : 0.16)
                .ignoresSafeArea()
                .contentShape(Rectangle())
                .onTapGesture {
                    dismissAttachmentOptions()
                }

            ChatAttachmentOptionsSheet(
                cameraAvailable: cameraAvailable,
                showsDocument: canSendDocumentAttachments,
                // Android allows location whenever BT OR the internet path exists; iOS gated it
                // on BLE alone, so the growth path — internet-added contacts — couldn't answer the
                // one question a disaster app exists for: "where are you?"
                showsLocation: usesBleGattDirectChat || contactRecord?.supportsInternet == true,
                onSelect: { action in
                    handleAttachmentSelection(action)
                }
            )
            .frame(maxWidth: layout.isWideLayout ? 520 : .infinity)
            .padding(.horizontal, layout.isWideLayout ? 24 : 14)
            .padding(.bottom, max(safeAreaBottomInset, 12) + 10)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
        .transition(.opacity)
        .zIndex(1)
    }

    private func dismissAttachmentOptions() {
        withAnimation(.spring(response: 0.28, dampingFraction: 0.88)) {
            showsAttachmentOptions = false
        }
    }

    private func scrollToLatestMessage() {
        guard let lastMessageId = messages.last?.id else { return }
        scrollTargetMessageId = lastMessageId
        lastSeenMessageId = lastMessageId
    }

    /// How many messages arrived after the newest one the user has seen (0 when the seen anchor
    /// is gone — only very old ids fall out of the window, and those imply nothing unseen).
    private var unseenTailCount: Int {
        guard let lastSeenMessageId,
              let index = messages.lastIndex(where: { $0.id == lastSeenMessageId }) else { return 0 }
        return messages.count - 1 - index
    }

    private func presentAttachmentPicker(_ source: ChatMediaPickerSource) {
        dismissAttachmentOptions()
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
            mediaPickerSource = source
        }
    }

    private func handleAttachmentSelection(_ action: ChatAttachmentAction) {
        switch action {
        case .camera:
            presentAttachmentPicker(.camera)
        case .photoLibrary:
            presentAttachmentPicker(.photoLibrary)
        case .document:
            dismissAttachmentOptions()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.18) {
                showsDocumentPicker = true
            }
        case .offlineMap:
            dismissAttachmentOptions()
            shareOfflineMapBundle()
        case .location:
            dismissAttachmentOptions()
            shareCurrentLocation()
        }
    }

    private var sessionTitle: String {
        if let contactRecord,
           contactRecord.preferredTransport == .bleGatt,
           !contactRecord.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return contactRecord.name
        }
        return currentSession?.displayName
            ?? NSLocalizedString(
                isDirectContactConversation ? "SOS_CHAT_ROLE_CONTACT" : "SOS_CHAT_TITLE",
                comment: ""
            )
    }

    private var navigationSubtitle: String? {
        if let contactRecord, contactRecord.preferredTransport == .bleGatt {
            return NSLocalizedString("SOS_CHAT_ROLE_CONTACT", comment: "")
        }
        guard let session = currentSession else { return nil }
        switch session.role {
        case .fieldTeam:
            return NSLocalizedString("SOS_CHAT_ROLE_FIELD_TEAM", comment: "")
        case .victim:
            return NSLocalizedString("SOS_CHAT_ROLE_VICTIM", comment: "")
        case .unknown:
            return isDirectContactConversation
                ? NSLocalizedString("SOS_CHAT_ROLE_CONTACT", comment: "")
                : nil
        }
    }

    private var avatarInitials: String {
        currentSession?.avatarInitials ?? AvatarGenerator.initials(from: sessionTitle)
    }

    private var avatarHue: Double {
        currentSession?.avatarHue ?? AvatarGenerator.hue(for: sessionId)
    }

    private func replyAuthorLabel(for message: SOSChatMessage) -> String {
        if message.isLocal {
            return NSLocalizedString("SOS_CHAT_REPLY_SENDER_YOU", comment: "")
        }
        return sessionTitle
    }

    private func activateReply(to message: SOSChatMessage) {
        replyTargetMessageId = message.id
        ensureRealtimeServicesStarted()
        Task { @MainActor in
            scrollToLatestMessage()
            await Task.yield()
            isFocused = true
        }
    }

    private var sendEnabled: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var composerActionEnabled: Bool {
        if pendingImage != nil {
            return !isSendingImage
        }
        if sendEnabled {
            return true
        }
        return voiceComposer.canStartRecording
    }

    /// True when this thread can reach the peer over the E2E internet transport — an internet
    /// contact can send/receive attachments even with no live Bluetooth link (Android parity).
    private var canSendViaInternet: Bool {
        sendMessageHandler == nil && (contactRecord?.supportsInternet ?? false)
    }

    private var canAttachImage: Bool {
        if sendImageHandler != nil {
            return true
        }
        if usesBleGattDirectChat && sendMessageHandler == nil {
            return true
        }
        if canSendViaInternet {
            return true
        }
        return sendMessageHandler == nil && currentSession?.role == .fieldTeam
    }

    private var canSendDocumentAttachments: Bool {
        if sendFileHandler != nil {
            return true
        }
        return usesBleGattDirectChat || canSendViaInternet
    }

    private var canShareOfflineMap: Bool {
        canSendDocumentAttachments
    }

    private var maxDocumentTransferBytes: Int {
        if sendFileHandler != nil {
            return BleFilePayload.maxOutgoingTotalBytes
        }
        // Internet transport (chunked relay) matches Android's 25 MB cap; BLE stays at its lower cap.
        if canSendViaInternet && !usesBleGattDirectChat {
            return 25 * 1_024 * 1_024
        }
        return P2pBleProtocol.fileMaxTotalBytes
    }

    private var shouldAutoStartBroadcast: Bool {
        guard sendMessageHandler == nil else { return false }
        if let contactRecord {
            return contactRecord.preferredTransport == .legacyBroadcast
        }
        guard let role = currentSession?.role else { return false }
        return role == .fieldTeam
    }

    private func startBroadcastIfNeeded() {
        guard shouldAutoStartBroadcast, !broadcastManager.isBroadcasting else { return }
        // Transport only. This runs merely because a chat was PRESENTED — it is not a statement that
        // anyone is in danger, and it used to declare a full emergency 720ms after the view appeared.
        SOSBroadcastManager.shared.start(declaringEmergency: false)
    }

    private func startContactLinkIfNeeded() {
        guard sendMessageHandler == nil else { return }
        guard let contact = contactRecord else { return }
        guard contact.preferredTransport == .legacyBroadcast else { return }
        guard !contact.broadcastId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        contactLink.start(broadcastId: contact.broadcastId, sessionId: sessionId)
    }

    private func startP2pGattIfNeeded() {
        guard sendMessageHandler == nil else { return }
        guard let contact = contactRecord, contact.preferredTransport == .bleGatt else { return }
        guard !contactBroadcastManager.isSessionConnected(sessionId) else { return }
        p2pGattChat.start()
    }

    private func composerPrimaryAction() {
        ensureRealtimeServicesStarted()
        if sendEnabled {
            sendMessage()
            return
        }
        voiceComposer.startRecording()
    }

    private func sendMessage() {
        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let outgoingText = P2pSharedTransferSupport.buildReplyFormattedMessage(
            body: trimmed,
            replyToMessageId: replyTargetMessage?.transportMessageId,
            replyPreviewText: replyTargetMessage?.replyPreviewText,
            replyAuthorLabel: replyTargetMessage.map(replyAuthorLabel(for:))
        )
        let transportMessageId = makeTransportMessageId()
        // Prefer the internet transport when the contact has an internet identity and we're online;
        // it continues seamlessly if Bluetooth drops. Falls through to BLE/broadcast when offline.
        // The internet path is ASYNC — it returns whether it took ownership (contact is internet
        // capable + online), NOT whether the relay accepted. When it owns the send, the bubble
        // starts .pending and its real result drives markSent/markFailed (with store-and-forward
        // retry). Only the synchronous BLE/broadcast paths report an immediate boolean.
        let internetOwned = sendViaInternet(outgoingText, transportMessageId: transportMessageId) ?? false
        let syncSuccess: Bool? = internetOwned
            ? nil
            : (sendMessageHandler?(sessionId, outgoingText, transportMessageId)
                ?? sendViaContactLink(outgoingText)
                ?? sendViaP2pGatt(outgoingText, transportMessageId: transportMessageId)
                ?? SOSBroadcastManager.shared.sendChatMessage(
                    to: sessionId,
                    text: outgoingText,
                    transportMessageId: transportMessageId
                ))
        AppAnalytics.messageSent(
            kind: "text",
            transport: internetOwned ? "internet" : "bluetooth"
        )
        // internet → .pending (async result upgrades it); sync transport → its own verdict.
        let status: SOSChatMessageStatus = internetOwned ? .pending : ((syncSuccess ?? false) ? .sent : .pending)
        store.appendLocalMessage(
            sessionId: sessionId,
            text: outgoingText,
            status: status,
            transportMessageId: transportMessageId
        )
        if internetOwned || (syncSuccess ?? false) {
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } else {
            UINotificationFeedbackGenerator().notificationOccurred(.warning)
        }
        draft = ""
        replyTargetMessageId = nil
    }

    /// Re-sends a bubble the user tapped after it failed (or is stuck pending). Resets it to
    /// pending and kicks the store-and-forward queue; the receiver dedups on transportMessageId.
    func retrySend(_ message: SOSChatMessage) {
        guard message.isLocal, let tid = message.transportMessageId,
              message.status == .failed || message.status == .pending else { return }
        store.requeueMessage(sessionId: sessionId, transportMessageId: tid)
        InternetSendRetryQueue.shared.kick(reason: "manual retry")
    }

    private func sendVoiceMessage() {
        guard !isSendingVoice,
              let sourceURL = voiceComposer.recordedFileURL,
              let durationMillis = voiceComposer.recordedDurationMillis else {
            return
        }

        let transportMessageId = makeTransportMessageId()
        let mimeType = voiceComposer.mimeType
        guard let audioRelativePath = SOSChatStore.persistVoiceFile(
            from: sourceURL,
            messageId: transportMessageId,
            mimeType: mimeType
        ) else {
            return
        }

        let hostConnected = contactBroadcastManager.isSessionConnected(sessionId)
        let centralReady = p2pGattChat.isReady()

        // Internet transport: relay the clip (chunked E2E) when Bluetooth is not linked but the
        // peer's identity is known and the internet is reachable — Android's exact priority.
        if sendVoiceHandler == nil, !hostConnected, !centralReady,
           let internetContact = contactRecord,
           internetContact.supportsInternet,
           InternetChatTransport.shared.isAvailable() {
            AppAnalytics.messageSent(kind: "voice", transport: "internet")
            _ = store.appendLocalAudioMessage(
                sessionId: sessionId,
                audioRelativePath: audioRelativePath,
                durationMillis: durationMillis,
                status: .pending,
                transportMessageId: transportMessageId
            )
            voiceComposer.discardRecording()
            let sessionId = self.sessionId
            Task { @MainActor in
                let data = await Task.detached(priority: .utility) {
                    SOSChatStore.loadVoiceData(fileName: audioRelativePath)
                }.value
                guard let data, !data.isEmpty else {
                    store.markFailed(sessionId: sessionId, transportMessageId: transportMessageId)
                    return
                }
                let ok = await InternetChatTransport.shared.sendAttachment(
                    transferId: transportMessageId,
                    kind: InternetE2eEnvelope.attachmentKindAudio,
                    mime: mimeType,
                    name: audioRelativePath,
                    durationMs: durationMillis,
                    data: data,
                    contact: internetContact
                )
                if ok {
                    store.markSent(sessionId: sessionId, transportMessageId: transportMessageId)
                } else {
                    store.markFailed(sessionId: sessionId, transportMessageId: transportMessageId)
                }
            }
            return
        }

        isSendingVoice = true
        AppAnalytics.messageSent(kind: "voice", transport: "bluetooth")

        Self.transportQueue.async {
            let success: Bool
            if let sendVoiceHandler = self.sendVoiceHandler {
                success = sendVoiceHandler(
                    self.sessionId,
                    audioRelativePath,
                    mimeType,
                    durationMillis,
                    transportMessageId
                )
            } else if hostConnected {
                success = self.contactBroadcastManager.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: transportMessageId,
                    sessionId: self.sessionId
                )
            } else if centralReady {
                success = self.p2pGattChat.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: transportMessageId
                )
            } else {
                let hostSuccess = self.contactBroadcastManager.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: transportMessageId,
                    sessionId: self.sessionId
                )
                // Final fallback mirrors the text chain: a victim chatting with a rescuer sends
                // over the SOS GATT server (this used to be missing, so victim voice died here).
                success = hostSuccess || self.p2pGattChat.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: transportMessageId
                ) || SOSBroadcastManager.shared.sendVoiceMessage(
                    to: self.sessionId,
                    audioFileName: audioRelativePath,
                    mimeType: mimeType,
                    durationMillis: durationMillis,
                    messageId: transportMessageId
                )
            }

            DispatchQueue.main.async {
                _ = self.store.appendLocalAudioMessage(
                    sessionId: self.sessionId,
                    audioRelativePath: audioRelativePath,
                    durationMillis: durationMillis,
                    status: success ? .sent : .pending,
                    transportMessageId: transportMessageId
                )
                self.voiceComposer.discardRecording()
                self.isSendingVoice = false
            }
        }
    }

    private func sendPendingImage() {
        guard canAttachImage,
              !isSendingImage,
              let pendingImage else {
            return
        }

        let transportMessageId = makeTransportMessageId()
        guard let prepared = ChatImageTransfer.prepareBleImageAttachment(
            sourceData: pendingImage.sourceData,
            messageId: transportMessageId
        ) else {
            return
        }

        ensureRealtimeServicesStarted()
        let hostConnected = contactBroadcastManager.isSessionConnected(sessionId)
        let centralReady = p2pGattChat.isReady()
        let canSendViaSosFieldTeam = currentSession?.role == .fieldTeam && sendMessageHandler == nil

        // Internet transport: relay the image (chunked E2E) when Bluetooth is not linked but the
        // peer's identity is known and the internet is reachable — Android's exact priority.
        if sendImageHandler == nil, !canSendViaSosFieldTeam, !hostConnected, !centralReady,
           let internetContact = contactRecord,
           internetContact.supportsInternet,
           InternetChatTransport.shared.isAvailable() {
            _ = store.appendLocalImageMessage(
                sessionId: sessionId,
                imageRelativePath: prepared.imageRelativePath,
                thumbnailRelativePath: prepared.thumbnailRelativePath,
                imageWidth: prepared.width,
                imageHeight: prepared.height,
                imageMimeType: prepared.mimeType,
                status: .pending,
                transportMessageId: transportMessageId
            )
            self.pendingImage = nil
            let sessionId = self.sessionId
            Task { @MainActor in
                let data = await Task.detached(priority: .utility) {
                    SOSChatStore.loadImageData(fileName: prepared.imageRelativePath)
                }.value
                guard let data, !data.isEmpty else {
                    store.markFailed(sessionId: sessionId, transportMessageId: transportMessageId)
                    return
                }
                let ok = await InternetChatTransport.shared.sendAttachment(
                    transferId: transportMessageId,
                    kind: InternetE2eEnvelope.attachmentKindImage,
                    mime: prepared.mimeType,
                    name: prepared.imageRelativePath,
                    durationMs: 0,
                    data: data,
                    contact: internetContact
                )
                if ok {
                    store.markSent(sessionId: sessionId, transportMessageId: transportMessageId)
                } else {
                    store.markFailed(sessionId: sessionId, transportMessageId: transportMessageId)
                }
            }
            return
        }

        isSendingImage = true

        Self.transportQueue.async {
            let success: Bool
            if let sendImageHandler = self.sendImageHandler {
                success = sendImageHandler(
                    self.sessionId,
                    prepared.imageRelativePath,
                    prepared.mimeType,
                    prepared.width,
                    prepared.height,
                    transportMessageId
                )
            } else if canSendViaSosFieldTeam {
                success = self.broadcastManager.sendImageMessage(
                    to: self.sessionId,
                    imageFileName: prepared.imageRelativePath,
                    mimeType: prepared.mimeType,
                    width: prepared.width,
                    height: prepared.height,
                    messageId: transportMessageId
                )
            } else if hostConnected {
                success = self.contactBroadcastManager.sendImageMessage(
                    imageFileName: prepared.imageRelativePath,
                    mimeType: prepared.mimeType,
                    width: prepared.width,
                    height: prepared.height,
                    messageId: transportMessageId,
                    sessionId: self.sessionId
                )
            } else if centralReady {
                success = self.p2pGattChat.sendImageMessage(
                    imageFileName: prepared.imageRelativePath,
                    mimeType: prepared.mimeType,
                    width: prepared.width,
                    height: prepared.height,
                    messageId: transportMessageId
                )
            } else {
                let hostSuccess = self.contactBroadcastManager.sendImageMessage(
                    imageFileName: prepared.imageRelativePath,
                    mimeType: prepared.mimeType,
                    width: prepared.width,
                    height: prepared.height,
                    messageId: transportMessageId,
                    sessionId: self.sessionId
                )
                success = hostSuccess || self.p2pGattChat.sendImageMessage(
                    imageFileName: prepared.imageRelativePath,
                    mimeType: prepared.mimeType,
                    width: prepared.width,
                    height: prepared.height,
                    messageId: transportMessageId
                )
            }

            DispatchQueue.main.async {
                _ = self.store.appendLocalImageMessage(
                    sessionId: self.sessionId,
                    imageRelativePath: prepared.imageRelativePath,
                    thumbnailRelativePath: prepared.thumbnailRelativePath,
                    imageWidth: prepared.width,
                    imageHeight: prepared.height,
                    imageMimeType: prepared.mimeType,
                    status: success ? .sent : .pending,
                    transportMessageId: transportMessageId
                )
                self.pendingImage = nil
                self.isSendingImage = false
            }
        }
    }

    private func handleImageSelection(_ selection: ChatImageSelection?) {
        mediaPickerSource = nil
        guard let selection,
              let image = UIImage(data: selection.data)?.normalizedOrientationImage() else {
            return
        }
        pendingImage = PendingChatImage(
            sourceData: selection.data,
            previewImage: image
        )
    }

    private func handleDocumentSelection(_ url: URL?) {
        showsDocumentPicker = false
        guard let url, canSendDocumentAttachments, !isSendingDocument else { return }
        let transportMessageId = makeTransportMessageId()
        isSendingDocument = true
        ensureRealtimeServicesStarted()
        // Snapshot on the main thread; sendPreparedDocument runs on the transport queue.
        let internetContact = contactRecord

        Self.transportQueue.async {
            guard let prepared = P2pSharedTransferSupport.prepareDocumentAttachment(
                from: url,
                messageId: transportMessageId,
                maxBytes: maxDocumentTransferBytes
            ) else {
                DispatchQueue.main.async {
                    self.isSendingDocument = false
                    self.operationAlert = ChatOperationAlert(
                        title: "File",
                        message: "The selected file could not be prepared for transfer."
                    )
                }
                return
            }
            self.sendPreparedDocument(
                prepared,
                transportMessageId: transportMessageId,
                internetContact: internetContact
            )
        }
    }

    private func sendPreparedDocument(
        _ prepared: PreparedP2pDocumentAttachment,
        transportMessageId: String,
        internetContact: ContactRecord?
    ) {
        let previewText = P2pSharedTransferSupport.buildFilePreviewMessage(prepared)
        let hostConnected = contactBroadcastManager.isSessionConnected(sessionId)
        let centralReady = p2pGattChat.isReady()

        // Internet transport when Bluetooth is not linked: the binary rides the relay as a kind-3
        // attachment and the CC_FILE preview goes as a normal text message — both under the same
        // uuid, exactly the pairing Android's receiver expects (and ours, symmetrically).
        if sendFileHandler == nil, !hostConnected, !centralReady,
           let internetContact,
           internetContact.supportsInternet,
           InternetChatTransport.shared.isAvailable() {
            let sessionId = self.sessionId
            DispatchQueue.main.async {
                _ = self.store.appendLocalMessage(
                    sessionId: sessionId,
                    text: previewText,
                    status: .pending,
                    transportMessageId: transportMessageId
                )
                self.isSendingDocument = false
            }
            Task { @MainActor in
                let fileSent = await InternetChatTransport.shared.sendAttachment(
                    transferId: transportMessageId,
                    kind: InternetE2eEnvelope.attachmentKindFile,
                    mime: prepared.mimeType ?? "application/octet-stream",
                    name: prepared.displayName,
                    durationMs: 0,
                    data: prepared.payloadData,
                    contact: internetContact
                )
                if fileSent {
                    _ = await InternetChatTransport.shared.sendText(
                        messageId: transportMessageId,
                        text: previewText,
                        contact: internetContact
                    )
                    self.store.markSent(sessionId: sessionId, transportMessageId: transportMessageId)
                } else {
                    self.store.markFailed(sessionId: sessionId, transportMessageId: transportMessageId)
                }
            }
            return
        }

        let success: Bool
        if let sendFileHandler = self.sendFileHandler {
            let fileSent = sendFileHandler(
                self.sessionId,
                prepared.payloadData,
                prepared.displayName,
                prepared.mimeType,
                prepared.originalSizeBytes,
                transportMessageId
            )
            let previewSent = self.sendMessageHandler?(
                self.sessionId,
                previewText,
                transportMessageId
            ) ?? fileSent
            success = fileSent && previewSent
        } else if hostConnected {
            let fileSent = self.contactBroadcastManager.sendFileMessage(
                data: prepared.payloadData,
                displayName: prepared.displayName,
                mimeType: prepared.mimeType,
                originalSizeBytes: prepared.originalSizeBytes,
                messageId: transportMessageId,
                sessionId: self.sessionId
            )
            let previewSent = fileSent && self.contactBroadcastManager.sendMessage(
                previewText,
                transportMessageId: transportMessageId,
                sessionId: self.sessionId
            )
            success = fileSent && previewSent
        } else if centralReady {
            let fileQueued = self.p2pGattChat.sendFileMessage(
                data: prepared.payloadData,
                displayName: prepared.displayName,
                mimeType: prepared.mimeType,
                originalSizeBytes: prepared.originalSizeBytes,
                messageId: transportMessageId
            )
            let previewQueued = self.p2pGattChat.sendMessage(
                previewText,
                messageId: transportMessageId
            )
            success = fileQueued && previewQueued
        } else {
            self.p2pGattChat.start()
            let hostFileSent = self.contactBroadcastManager.sendFileMessage(
                data: prepared.payloadData,
                displayName: prepared.displayName,
                mimeType: prepared.mimeType,
                originalSizeBytes: prepared.originalSizeBytes,
                messageId: transportMessageId,
                sessionId: self.sessionId
            )
            let hostPreviewSent = hostFileSent && self.contactBroadcastManager.sendMessage(
                previewText,
                transportMessageId: transportMessageId,
                sessionId: self.sessionId
            )
            if hostPreviewSent {
                success = true
            } else {
                let fileQueued = self.p2pGattChat.sendFileMessage(
                    data: prepared.payloadData,
                    displayName: prepared.displayName,
                    mimeType: prepared.mimeType,
                    originalSizeBytes: prepared.originalSizeBytes,
                    messageId: transportMessageId
                )
                let previewQueued = self.p2pGattChat.sendMessage(
                    previewText,
                    messageId: transportMessageId
                )
                success = fileQueued && previewQueued
            }
        }

        DispatchQueue.main.async {
            _ = self.store.appendLocalMessage(
                sessionId: self.sessionId,
                text: previewText,
                status: success ? .sent : .pending,
                transportMessageId: transportMessageId
            )
            self.isSendingDocument = false
        }
    }

    private func shareOfflineMapBundle() {
        guard canShareOfflineMap, !isSendingDocument else { return }
        ensureRealtimeServicesStarted()
        isSendingDocument = true
        // Snapshot on the main thread; sendPreparedDocument runs on the transport queue.
        let internetContact = contactRecord

        Task { @MainActor in
            let location = await locationCoordinator.requestLocation(timeout: 2.5)
            let transportMessageId = makeTransportMessageId()
            let coordinate = location?.coordinate

            do {
                let prepared = try await Task(priority: .userInitiated) {
                    try OfflineMapShareCoordinator.prepareShareAttachment(
                        for: coordinate,
                        messageId: transportMessageId,
                        maxBytes: maxDocumentTransferBytes
                    )
                }.value
                Self.transportQueue.async {
                    self.sendPreparedDocument(
                prepared,
                transportMessageId: transportMessageId,
                internetContact: internetContact
            )
                }
            } catch {
                isSendingDocument = false
                operationAlert = ChatOperationAlert(
                    title: "Offline Map",
                    message: error.localizedDescription
                )
            }
        }
    }

    private func shareCurrentLocation() {
        guard usesBleGattDirectChat || contactRecord?.supportsInternet == true,
              !isSharingLocation else { return }
        ensureRealtimeServicesStarted()
        isSharingLocation = true

        Task { @MainActor in
            defer { isSharingLocation = false }
            guard let location = await locationCoordinator.requestLocation() else {
                return
            }
            let transportMessageId = makeTransportMessageId()
            let source = abs(location.timestamp.timeIntervalSinceNow) <= 20
                ? P2pSharedTransferSupport.locationSourceGPS
                : P2pSharedTransferSupport.locationSourceLastKnown
            let payload = P2pSharedTransferSupport.buildLocationMessage(
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                accuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
                timestamp: location.timestamp,
                confidenceRadiusMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
                source: source
            )
            // CC_LOC is plain text on the wire, so it rides the exact transport chain a text
            // message uses: a live BT link wins, otherwise the internet path owns it (and its
            // store-and-forward queue upgrades the bubble to .sent asynchronously). This used to be
            // hardwired to the P2P GATT sender alone, so an internet-only contact could never be
            // sent a location at all.
            let internetOwned = sendViaInternet(payload, transportMessageId: transportMessageId) ?? false
            let syncSuccess: Bool? = internetOwned
                ? nil
                : (sendMessageHandler?(sessionId, payload, transportMessageId)
                    ?? sendViaContactLink(payload)
                    ?? sendViaP2pGatt(payload, transportMessageId: transportMessageId)
                    ?? SOSBroadcastManager.shared.sendChatMessage(
                        to: sessionId,
                        text: payload,
                        transportMessageId: transportMessageId
                    ))
            let status: SOSChatMessageStatus = internetOwned
                ? .pending
                : ((syncSuccess ?? false) ? .sent : .pending)
            _ = store.upsertLocalLocationMessage(
                sessionId: sessionId,
                latitude: location.coordinate.latitude,
                longitude: location.coordinate.longitude,
                horizontalAccuracyMeters: location.horizontalAccuracy >= 0 ? location.horizontalAccuracy : nil,
                capturedAt: location.timestamp,
                status: status,
                transportMessageId: transportMessageId
            )
        }
    }

    private func markReadLocallyIfNeeded() {
        guard let session = currentSession,
              session.unreadCount > 0 else { return }
        store.markRemoteRead(sessionId: sessionId)
    }

    private func sendReadReceiptIfNeeded() {
        let remoteTransportMessageIds = store.transportMessageIdsForRemoteMessages(sessionId: sessionId)
        guard !remoteTransportMessageIds.isEmpty else { return }
        // Internet contacts also get an E2E read receipt over the relay (idempotent on their
        // side); the BLE chain below still runs for a live local link.
        if let contactRecord, contactRecord.supportsInternet,
           InternetChatTransport.shared.isAvailable() {
            Task {
                _ = await InternetChatTransport.shared.sendReceipt(
                    contact: contactRecord,
                    templateCode: InternetChatTransport.readReceiptTemplate,
                    messageIds: remoteTransportMessageIds
                )
            }
        }
        let gattRead = sendReadViaP2pGatt()
        _ = readReceiptHandler?(sessionId, remoteTransportMessageIds)
            ?? gattRead
            ?? sendReadViaContactLink()
            ?? SOSBroadcastManager.shared.sendReadReceipt(
                to: sessionId,
                transportMessageIds: remoteTransportMessageIds
            )
    }

    private func scheduleLocalReadStateUpdate(delay: TimeInterval = 0.2) {
        pendingReadWorkItem?.cancel()
        let workItem = DispatchWorkItem {
            markReadLocallyIfNeeded()
        }
        pendingReadWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    private func scheduleReadReceiptIfNeeded(delay: TimeInterval = 0.35) {
        pendingReadWorkItem?.cancel()
        let workItem = DispatchWorkItem {
            sendReadReceiptIfNeeded()
        }
        pendingReadWorkItem = workItem
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: workItem)
    }

    /// Returns true when the internet transport OWNS this send (contact is internet-capable). The
    /// bubble is then .pending until the async relay result upgrades it to .sent (or the retry
    /// queue eventually flips it to .failed). Returns nil when internet can't be used, so the
    /// caller falls through to the BLE/broadcast transports. Note: contact must be internet-capable,
    /// but we take ownership even when momentarily OFFLINE — the message queues and store-and-forward
    /// delivers it when connectivity returns (a text composed offline must not silently vanish).
    private func sendViaInternet(_ text: String, transportMessageId: String) -> Bool? {
        guard let contactRecord else {
            MessagingDiagLog.log("send text: NOT internet — chat has no contactRecord")
            return nil
        }
        // A live Bluetooth link outranks the internet — Android's exact priority, and the one every
        // sibling media path on this screen (voice, image, document, receipts, typing) already
        // enforces. Text alone skipped these checks and returned true unconditionally, so any
        // contact that ever exchanged an internet identity permanently lost its Bluetooth fallback:
        // in a blackout, texts sat .pending in the retry queue right next to a working BT link —
        // the one situation this app exists for. No isAvailable() gate on the else-branch though:
        // with no BT link, internet ownership is still right even while unreachable, because the
        // store-and-forward queue beats failing on a dead BT chain.
        guard sendMessageHandler == nil,
              !contactBroadcastManager.isSessionConnected(sessionId),
              !p2pGattChat.isReady() else {
            MessagingDiagLog.log("send text: NOT internet — a Bluetooth link is live, it wins")
            return nil
        }
        guard contactRecord.supportsInternet else {
            MessagingDiagLog.log(
                "send text: NOT internet — contact '\(contactRecord.name)' supportsInternet=false " +
                    "(peerUid=\(contactRecord.peerUid != nil) peerKey=\(contactRecord.peerPublicKey != nil))"
            )
            return nil
        }
        MessagingDiagLog.log("send text: via internet to peer=\(contactRecord.peerUid?.prefix(8) ?? "?") id=\(transportMessageId)")
        let sessionId = self.sessionId
        Task { @MainActor in
            let ok = await InternetChatTransport.shared.sendText(
                messageId: transportMessageId,
                text: text,
                contact: contactRecord
            )
            if ok {
                store.markSent(sessionId: sessionId, transportMessageId: transportMessageId)
            } else {
                // Stays .pending; the store-and-forward queue retries and flips to .failed if it
                // burns out. Kick it so a transient failure retries promptly.
                InternetSendRetryQueue.shared.kick(reason: "live send failed")
            }
        }
        return true
    }

    /// Composer keystrokes → a throttled, sealed "typing" pulse to the peer (internet contacts
    /// only). One pulse per 4s while typing keeps writes cheap; the peer's indicator lives ~6s
    /// per pulse, so continuous typing renders as a continuous indicator.
    private func sendTypingPulseIfNeeded(for text: String) {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let contactRecord,
              contactRecord.supportsInternet,
              InternetChatTransport.shared.isAvailable() else { return }
        let now = Date()
        guard now.timeIntervalSince(lastTypingPulseAt) >= 4 else { return }
        lastTypingPulseAt = now
        Task {
            _ = await InternetChatTransport.shared.sendTypingSignal(contact: contactRecord)
        }
    }

    private func sendViaContactLink(_ text: String) -> Bool? {
        guard let contactRecord, contactRecord.preferredTransport == .legacyBroadcast else { return nil }
        let success = contactLink.sendMessage(text)
        return success ? true : nil
    }

    private func sendViaP2pGatt(_ text: String, transportMessageId: String) -> Bool? {
        guard let contactRecord, contactRecord.preferredTransport == .bleGatt else { return nil }
        let hostConnected = contactBroadcastManager.isSessionConnected(sessionId)
        let centralReady = p2pGattChat.isReady()

        if hostConnected {
            Self.transportQueue.async {
                _ = contactBroadcastManager.sendMessage(
                    text,
                    transportMessageId: transportMessageId,
                    sessionId: sessionId
                )
            }
            return true
        }
        if centralReady {
            Self.transportQueue.async {
                _ = p2pGattChat.sendMessage(text, messageId: transportMessageId)
            }
            return true
        }

        p2pGattChat.start()
        Self.transportQueue.async {
            if !contactBroadcastManager.sendMessage(
                text,
                transportMessageId: transportMessageId,
                sessionId: sessionId
            ) {
                _ = p2pGattChat.sendMessage(text, messageId: transportMessageId)
            }
        }
        return false
    }

    private func presentSharedFilePreview(for message: SOSChatMessage) {
        if let payload = P2pSharedTransferSupport.parseFilePreviewMessage(message.text),
           OfflineMapShareCoordinator.canImport(payload: payload) {
            importOfflineMapBundle(for: message)
            return
        }
        let trimmedTransportId = message.transportMessageId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = (trimmedTransportId?.isEmpty == false) ? trimmedTransportId! : message.id.uuidString
        guard let url = P2pSharedTransferSupport.resolveSharedDocumentURL(messageId: messageId) else {
            return
        }
        previewedSharedFile = PreviewedSharedFile(url: url)
    }

    private func importOfflineMapBundle(for message: SOSChatMessage) {
        let trimmedTransportId = message.transportMessageId?.trimmingCharacters(in: .whitespacesAndNewlines)
        let messageId = (trimmedTransportId?.isEmpty == false) ? trimmedTransportId! : message.id.uuidString
        guard let url = P2pSharedTransferSupport.resolveSharedDocumentURL(messageId: messageId) else {
            operationAlert = ChatOperationAlert(
                title: "Offline Map",
                message: "The shared offline map is still downloading."
            )
            return
        }

        Task {
            do {
                let region = try await Task(priority: .userInitiated) {
                    try OfflineMapShareCoordinator.importSharedBundle(from: url)
                }.value
                await MainActor.run {
                    operationAlert = ChatOperationAlert(
                        title: "Offline Map",
                        message: "Imported \(region.name)."
                    )
                }
            } catch {
                await MainActor.run {
                    operationAlert = ChatOperationAlert(
                        title: "Offline Map",
                        message: error.localizedDescription
                    )
                }
            }
        }
    }

    private func presentLocationPreview(for message: SOSChatMessage) {
        guard ChatLocationPresentation.coordinate(for: message) != nil else { return }
        previewedLocationMessage = message
    }

    private func sendReadViaContactLink() -> Bool? {
        guard let contactRecord, contactRecord.preferredTransport == .legacyBroadcast else { return nil }
        let success = contactLink.sendReadReceipt()
        return success ? true : nil
    }

    private func sendReadViaP2pGatt() -> Bool? {
        guard let contactRecord, contactRecord.preferredTransport == .bleGatt else { return nil }
        let hostConnected = contactBroadcastManager.isSessionConnected(sessionId)
        let centralReady = p2pGattChat.isReady()

        if hostConnected {
            Self.transportQueue.async {
                _ = contactBroadcastManager.sendReadReceipt(sessionId: sessionId)
            }
            return true
        }
        if centralReady {
            Self.transportQueue.async {
                _ = p2pGattChat.sendReadReceipt()
            }
            return true
        }

        p2pGattChat.start()
        Self.transportQueue.async {
            if !contactBroadcastManager.sendReadReceipt(sessionId: sessionId) {
                _ = p2pGattChat.sendReadReceipt()
            }
        }
        return false
    }

    private func makeTransportMessageId() -> String {
        "ios-\(UUID().uuidString.lowercased())"
    }

    private func scheduleTranscriptPresentation() {
        transcriptStartupTask?.cancel()
        transcriptStartupTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 150_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            isTranscriptReady = true
        }
    }

    private func scheduleDeferredTransportStartup() {
        transportStartupTask?.cancel()
        transportStartupTask = Task { @MainActor in
            do {
                try await Task.sleep(nanoseconds: 720_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            startContactLinkIfNeeded()
            startP2pGattIfNeeded()
            startBroadcastIfNeeded()
            if usesBleGattDirectChat {
                contactBroadcastManager.setForegroundSession(sessionId)
            }
            scheduleReadReceiptIfNeeded(delay: 0.55)
        }
    }

    private func ensureRealtimeServicesStarted() {
        guard !hasStartedRealtimeServices else { return }
        hasStartedRealtimeServices = true
        scheduleStatusTrackingIfNeeded()
        scheduleDeferredTransportStartup()
    }

    /// An internet contact added by number (from phone contacts) has no offline Bluetooth link yet.
    /// While its chat is open, try once to bootstrap one over SPAKE2 if the peer is nearby, so future
    /// messages can ride Bluetooth automatically (Android parity — ChatScreenViewModel's auto-link).
    /// Best-effort: the internet transport keeps carrying the chat, and the peer confirms once.
    private func maybeAutoLinkBluetooth() {
        guard let contactRecord, NearbyAutoLink.isEligible(contactRecord) else { return }
        NearbyAutoLink.shared.tryEstablish(contact: contactRecord)
    }

    private func scheduleStatusTrackingIfNeeded() {
        statusTrackingTask?.cancel()
        guard usesBleGattDirectChat else {
            statusTrackingEnabled = false
            return
        }
        statusTrackingEnabled = true
    }

    private var chatBackground: some View {
        ZStack {
            Color.whatsAppBackground
            LinearGradient(
                colors: [
                    Color.whatsAppAccent.opacity(colorScheme == .dark ? 0.08 : 0.045),
                    Color.clear,
                    Color.whatsAppIncoming.opacity(colorScheme == .dark ? 0.12 : 0.22)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .blendMode(.normal)
        }
        .ignoresSafeArea()
    }
}

struct DeferredAudioMessageBubbleContent: View {
    let message: SOSChatMessage
    let tint: Color
    let layout: ChatLayoutMetrics

    @State private var shouldRenderRichContent = false

    var body: some View {
        Group {
            if shouldRenderRichContent {
                AudioMessageBubbleContent(message: message, tint: tint, layout: layout)
            } else {
                AudioMessageBubblePlaceholder(
                    durationLabel: AudioMessageBubbleContent.durationLabelText(
                        durationMillis: message.audioDurationMillis
                    ),
                    tint: tint,
                    layout: layout
                )
            }
        }
        .task(id: message.id) {
            guard !shouldRenderRichContent else { return }
            do {
                try await Task.sleep(nanoseconds: 180_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled else { return }
            shouldRenderRichContent = true
        }
    }
}

private struct AudioMessageBubblePlaceholder: View {
    let durationLabel: String
    let tint: Color
    let layout: ChatLayoutMetrics

    var body: some View {
        HStack(spacing: 12) {
            Circle()
                .fill(tint.opacity(0.92))
                .frame(width: 40, height: 40)
                .overlay(
                    Image(systemName: "waveform")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(.white)
                )

            VStack(alignment: .leading, spacing: 8) {
                Capsule(style: .continuous)
                    .fill(Color.primary.opacity(0.08))
                    .frame(maxWidth: .infinity)
                    .frame(height: 6)
                    .overlay(alignment: .leading) {
                        Capsule(style: .continuous)
                            .fill(tint.opacity(0.36))
                            .frame(width: max(48, layout.audioBubbleMaxWidth * 0.34), height: 6)
                    }

                HStack {
                    Text("0:00")
                        .font(.caption.monospacedDigit().weight(.medium))
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(durationLabel)
                        .font(.caption.monospacedDigit().weight(.medium))
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .frame(minWidth: layout.audioBubbleMinWidth, maxWidth: layout.audioBubbleMaxWidth, alignment: .leading)
        .redacted(reason: .placeholder)
    }
}

private struct VoiceRecordingIndicatorView: View {
    let durationLabel: String
    let onStop: () -> Void
    let onCancel: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Color.red)
                    .frame(width: 10, height: 10)
                Text(durationLabel)
                    .font(.headline.monospacedDigit())
                    .foregroundStyle(.primary)
            }

            Spacer()

            Button(action: onCancel) {
                Image(systemName: "trash")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Color.whatsAppInputBackground))
            }
            .buttonStyle(.plain)

            Button(action: onStop) {
                Image(systemName: "stop.fill")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.white)
                    .frame(width: 42, height: 42)
                    .background(Circle().fill(Color.whatsAppAccent))
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.whatsAppInputBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
        )
    }
}

private struct VoiceRecordingConfirmationView: View {
    let durationLabel: String
    let isSending: Bool
    let onDiscard: () -> Void
    let onSend: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            HStack(spacing: 10) {
                Image(systemName: "waveform")
                    .font(.headline)
                    .foregroundStyle(Color.whatsAppAccent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Voice message")
                        .font(.subheadline.weight(.semibold))
                    Text(durationLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer()

            Button(action: onDiscard) {
                Image(systemName: "xmark")
                    .font(.subheadline.weight(.bold))
                    .foregroundStyle(.secondary)
                    .frame(width: 38, height: 38)
                    .background(Circle().fill(Color.whatsAppInputBackground))
            }
            .buttonStyle(.plain)
            .disabled(isSending)

            Button(action: onSend) {
                if isSending {
                    ProgressView()
                        .tint(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(Color.whatsAppAccent))
                } else {
                    Image(systemName: "paperplane.fill")
                        .font(.subheadline.weight(.bold))
                        .foregroundColor(.white)
                        .frame(width: 42, height: 42)
                        .background(Circle().fill(Color.whatsAppAccent))
                }
            }
            .buttonStyle(.plain)
            .disabled(isSending)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.whatsAppInputBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
        )
    }
}

private struct AudioMessageBubbleContent: View {
    private static let waveformBarCount = 40

    let message: SOSChatMessage
    let tint: Color
    let layout: ChatLayoutMetrics

    @StateObject private var player = ChatAudioPlayer()
    @State private var waveformSamples: [CGFloat] = AudioWaveformRepository.placeholder(barCount: waveformBarCount)

    var body: some View {
        HStack(spacing: 12) {
            Button(action: togglePlayback) {
                Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundColor(.white)
                    .frame(width: 40, height: 40)
                    .background(
                        Circle()
                            .fill(tint)
                            .overlay(
                                Circle()
                                    .stroke(Color.white.opacity(0.18), lineWidth: 1)
                            )
                    )
                    .shadow(color: .black.opacity(0.08), radius: 5, x: 0, y: 2)
            }
            .buttonStyle(.plain)
            .disabled(audioFileName == nil)

            VStack(alignment: .leading, spacing: 8) {
                AudioWaveformView(
                    samples: waveformSamples,
                    progress: player.progress,
                    tint: tint,
                    trackTint: Color.primary.opacity(0.14)
                )
                .frame(height: 38)

                HStack(spacing: 8) {
                    Text(player.currentTimeLabel(defaultDurationMillis: message.audioDurationMillis))
                        .font(.caption.monospacedDigit().weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Spacer(minLength: 8)

                    Text(durationLabel)
                        .font(.caption.monospacedDigit().weight(.medium))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 4)
        .frame(minWidth: layout.audioBubbleMinWidth, maxWidth: layout.audioBubbleMaxWidth, alignment: .leading)
        .task(id: waveformTaskKey, priority: .utility) {
            await loadWaveform()
        }
        .onDisappear {
            player.stop()
        }
    }

    private var audioFileName: String? {
        guard let relativePath = message.audioRelativePath?.trimmingCharacters(in: .whitespacesAndNewlines),
              !relativePath.isEmpty else {
            return nil
        }
        return relativePath
    }

    private var durationLabel: String {
        player.durationLabel(defaultDurationMillis: message.audioDurationMillis)
    }

    nonisolated static func durationLabelText(durationMillis: Int?) -> String {
        let millis = max(0, durationMillis ?? 0)
        let totalSeconds = millis / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }

    private var waveformTaskKey: String {
        audioFileName ?? message.id.uuidString
    }

    private func togglePlayback() {
        guard let audioFileName else { return }
        player.toggle(fileName: audioFileName, durationMillis: message.audioDurationMillis)
    }

    private func loadWaveform() async {
        let placeholder = AudioWaveformRepository.placeholder(barCount: Self.waveformBarCount)
        guard let audioFileName else {
            waveformSamples = placeholder
            return
        }

        if let cached = await AudioWaveformRepository.shared.cachedWaveform(
            for: audioFileName,
            barCount: Self.waveformBarCount
        ) {
            waveformSamples = cached
            return
        }

        waveformSamples = placeholder

        do {
            try await Task.sleep(nanoseconds: 220_000_000)
        } catch {
            return
        }

        guard !Task.isCancelled else { return }

        let extracted = await AudioWaveformRepository.shared.waveform(
            for: audioFileName,
            barCount: Self.waveformBarCount
        )
        guard !Task.isCancelled else { return }
        waveformSamples = extracted.isEmpty ? placeholder : extracted
    }
}

private struct AudioWaveformView: View {
    let samples: [CGFloat]
    let progress: CGFloat
    let tint: Color
    let trackTint: Color

    var body: some View {
        Canvas(opaque: false, colorMode: .linear, rendersAsynchronously: true) { context, size in
            let metrics = Metrics(width: size.width, height: size.height, count: samples.count)
            var x = metrics.horizontalInset
            for index in samples.indices {
                let barHeight = metrics.barHeight(for: samples[index])
                let rect = CGRect(
                    x: x,
                    y: ((size.height - barHeight) / 2).rounded(.down),
                    width: metrics.barWidth,
                    height: barHeight
                )
                let path = Path(
                    roundedRect: rect,
                    cornerRadius: metrics.barWidth / 2
                )
                context.fill(path, with: .color(color(for: index)))
                x += metrics.barWidth + metrics.spacing
            }
        }
        .padding(.vertical, 6)
        .animation(.easeOut(duration: 0.2), value: samples)
        .animation(.easeOut(duration: 0.16), value: progress)
    }

    private func color(for index: Int) -> Color {
        let normalizedProgress = min(max(progress, 0), 1)
        guard normalizedProgress > 0 else {
            return trackTint
        }

        let completedBars = normalizedProgress * CGFloat(samples.count)
        if CGFloat(index + 1) <= completedBars {
            return tint
        }
        return trackTint
    }

    private struct Metrics {
        let width: CGFloat
        let height: CGFloat
        let count: Int

        private let minBarWidth: CGFloat = 2.5
        private let maxBarWidth: CGFloat = 4
        private let minSpacing: CGFloat = 1.5
        private let maxSpacing: CGFloat = 3

        var spacing: CGFloat {
            guard count > 1 else { return minSpacing }
            let candidate = (width * 0.1) / CGFloat(count - 1)
            return min(max(candidate, minSpacing), maxSpacing)
        }

        var horizontalInset: CGFloat {
            min(8, width * 0.04)
        }

        var barWidth: CGFloat {
            guard count > 0 else { return minBarWidth }
            let availableWidth = max(width - (horizontalInset * 2) - (spacing * CGFloat(count - 1)), minBarWidth * CGFloat(count))
            let candidate = availableWidth / CGFloat(count)
            return min(max(candidate, minBarWidth), maxBarWidth)
        }

        func barHeight(for sample: CGFloat) -> CGFloat {
            let minimum = max(6, height * 0.16)
            let maximum = max(minimum, height - 10)
            return minimum + ((maximum - minimum) * sample)
        }
    }
}

@MainActor
private final class ChatVoiceComposer: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    @Published private(set) var hasPendingRecording = false
    @Published private(set) var canStartRecording = true
    @Published private(set) var liveDurationLabel = "0:00"
    @Published private(set) var recordedDurationLabel = "0:00"

    private var recorder: AVAudioRecorder?
    private var durationTimer: Timer?
    private(set) var recordedFileURL: URL?
    private(set) var recordedDurationMillis: Int?

    let mimeType = "audio/mp4"

    func startRecording() {
        guard !isRecording else { return }
        requestPermissionAndStart()
    }

    func cancelRecording() {
        guard let recorder else {
            discardRecording()
            return
        }
        recorder.stop()
        cleanupRecorder(deleteFile: true)
        discardRecording()
    }

    func discardRecording() {
        if let recordedFileURL {
            try? FileManager.default.removeItem(at: recordedFileURL)
        }
        recordedFileURL = nil
        recordedDurationMillis = nil
        hasPendingRecording = false
        liveDurationLabel = "0:00"
        recordedDurationLabel = "0:00"
    }

    private func requestPermissionAndStart() {
        Task { @MainActor [weak self] in
            guard let self else { return }
            let granted = await Self.requestRecordPermission()
            self.canStartRecording = granted
            guard granted else { return }
            self.beginRecording()
        }
    }

    private nonisolated static func requestRecordPermission() async -> Bool {
        await withCheckedContinuation { continuation in
            if #available(iOS 17.0, *) {
                AVAudioApplication.requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            } else {
                AVAudioSession.sharedInstance().requestRecordPermission { granted in
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    private func beginRecording() {
        let audioSession = AVAudioSession.sharedInstance()
        let fileURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("voice-\(UUID().uuidString.lowercased())")
            .appendingPathExtension("m4a")
        let settings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue
        ]

        do {
            try audioSession.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.defaultToSpeaker, .allowBluetoothHFP]
            )
            try audioSession.setActive(true, options: [])

            let recorder = try AVAudioRecorder(url: fileURL, settings: settings)
            recorder.isMeteringEnabled = false
            guard recorder.prepareToRecord(), recorder.record() else {
                return
            }

            discardRecording()
            self.recorder = recorder
            isRecording = true
            liveDurationLabel = "0:00"
            startDurationTimer()
        } catch {
            cleanupRecorder(deleteFile: true)
        }
    }

    private func startDurationTimer() {
        durationTimer?.invalidate()
        // Use the closure variant with [weak self] so the timer doesn't retain the
        // composer. With the target/selector form Timer holds self strongly, which
        // means cleanupRecorder() is the ONLY way to release the composer — if the
        // chat view is dismissed mid-recording the composer (and its AVAudioRecorder)
        // would leak indefinitely while the timer keeps firing every 0.25 s.
        // The timer is added to the main run loop (this method is @MainActor) so the
        // closure fires on the main thread, satisfying the actor isolation of the tick.
        durationTimer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.handleDurationTimerTick()
            }
        }
    }

    private func cleanupRecorder(deleteFile: Bool) {
        durationTimer?.invalidate()
        durationTimer = nil
        let existingURL = recorder?.url
        recorder = nil
        isRecording = false
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
        if deleteFile, let existingURL {
            try? FileManager.default.removeItem(at: existingURL)
        }
    }

    private func handleDurationTimerTick() {
        guard let recorder else { return }
        liveDurationLabel = Self.formatDuration(milliseconds: Int(recorder.currentTime * 1000))
    }

    deinit {
        durationTimer?.invalidate()
    }

    func stopRecording() {
        guard let recorder else { return }
        let durationMillis = max(0, Int(recorder.currentTime * 1000))
        let finishedURL = recorder.url
        let wasRecording = recorder.isRecording
        recorder.stop()
        cleanupRecorder(deleteFile: !wasRecording)

        guard wasRecording else { return }
        recordedFileURL = finishedURL
        recordedDurationMillis = durationMillis
        hasPendingRecording = true
        recordedDurationLabel = Self.formatDuration(milliseconds: durationMillis)
    }

    private static func formatDuration(milliseconds: Int) -> String {
        let totalSeconds = max(0, milliseconds / 1000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

@MainActor
private final class ChatAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published private(set) var isPlaying = false
    @Published private(set) var progress: CGFloat = 0
    @Published private(set) var currentMillis: Int = 0
    @Published private(set) var durationMillis: Int = 0

    private var player: AVAudioPlayer?
    private var progressTimer: Timer?
    private var currentFileName: String?

    func toggle(fileName: String, durationMillis: Int?) {
        if isPlaying, currentFileName == fileName {
            stop()
            return
        }
        start(fileName: fileName, durationMillis: durationMillis)
    }

    func stop() {
        progressTimer?.invalidate()
        progressTimer = nil
        player?.stop()
        player?.delegate = nil
        player = nil
        currentFileName = nil
        isPlaying = false
        progress = 0
        currentMillis = 0
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    func currentTimeLabel(defaultDurationMillis: Int?) -> String {
        let millis = isPlaying ? currentMillis : 0
        if millis > 0 {
            return Self.formatDuration(milliseconds: millis)
        }
        return Self.formatDuration(milliseconds: 0)
    }

    func durationLabel(defaultDurationMillis: Int?) -> String {
        let millis = durationMillis > 0 ? durationMillis : max(0, defaultDurationMillis ?? 0)
        return Self.formatDuration(milliseconds: millis)
    }

    private func start(fileName: String, durationMillis: Int?) {
        stop()
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [])
            try AVAudioSession.sharedInstance().setActive(true, options: [])

            guard let audioData = SOSChatStore.loadVoiceData(fileName: fileName) else {
                return
            }
            // Android sends internet voice clips as Ogg/Opus, which AVAudioPlayer cannot open.
            // Route through the transcoder: a no-op for non-Ogg clips, and after the first play
            // the m4a result is cached (encrypted) so this is a cheap lookup on replays.
            let playableData = OggOpusTranscoder.playableAudioData(for: audioData, cacheKey: fileName)
            let player = try AVAudioPlayer(data: playableData)
            player.delegate = self
            player.prepareToPlay()
            self.player = player
            self.currentFileName = fileName
            self.currentMillis = 0
            self.progress = 0
            self.durationMillis = max(durationMillis ?? 0, Int(player.duration * 1000))
            guard player.play() else {
                stop()
                return
            }
            self.isPlaying = true
            startProgressTimer()
        } catch {
            stop()
        }
    }

    private func startProgressTimer() {
        progressTimer?.invalidate()
        // Closure variant with [weak self] avoids the strong retain that
        // Timer.scheduledTimer(target:selector:) imposes — without this the audio
        // player object would survive past view dismissal and keep firing every 0.2 s.
        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated {
                self?.handleProgressTimerTick()
            }
        }
    }

    private func handleProgressTimerTick() {
        guard let player else { return }
        currentMillis = Int(player.currentTime * 1000)
        if player.duration > 0 {
            progress = CGFloat(player.currentTime / player.duration)
        } else {
            progress = 0
        }
    }

    deinit {
        progressTimer?.invalidate()
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        stop()
    }

    private static func formatDuration(milliseconds: Int) -> String {
        let totalSeconds = max(0, milliseconds / 1000)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

#Preview {
    NavigationStack { SOSChatSessionsView() }
}

extension Color {
    static let whatsAppBackground = Color.appBackground
    static let whatsAppIncoming = Color.appSurface
    static let whatsAppOutgoing = Color.appPrimarySoft
    static let whatsAppAccent = Color.appPrimary
    static let whatsAppBarBackground = Color.appSurface
    static let whatsAppInputBackground = Color.appSurfaceElevated
    static let whatsAppInputBorder = Color.appBorder
    static let whatsAppInfoBackground = Color.appSurfaceMuted
    static let whatsAppInfoText = Color.appTextSecondary
}


// MARK: - In-chat message search (Android ChatScreenRoute parity)

private struct ChatMessageSearchSheet: View {
    let messages: [SOSChatMessage]
    @Binding var query: String
    let onSelect: (SOSChatMessage) -> Void

    private var results: [SOSChatMessage] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        // Text bubbles only, newest first; control payloads (CC_*) never match user searches by
        // design — nobody is looking for "CC_LOC:41.0,29.0".
        return messages
            .filter { $0.kind == .text && !$0.text.hasPrefix("CC_") }
            .filter { $0.text.localizedCaseInsensitiveContains(trimmed) }
            .sorted { $0.timestamp > $1.timestamp }
    }

    var body: some View {
        NavigationStack {
            Group {
                if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    ContentUnavailableView(
                        LocalizedStringKey("CHAT_SEARCH_PLACEHOLDER"),
                        systemImage: "magnifyingglass"
                    )
                } else if results.isEmpty {
                    ContentUnavailableView(
                        LocalizedStringKey("CHAT_SEARCH_NO_RESULTS"),
                        systemImage: "text.magnifyingglass"
                    )
                } else {
                    List(results) { message in
                        Button {
                            onSelect(message)
                        } label: {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(message.text)
                                    .font(.subheadline)
                                    .foregroundStyle(Color.primary)
                                    .lineLimit(2)
                                Text(message.timestamp, format: .dateTime.day().month().hour().minute())
                                    .font(.caption2)
                                    .foregroundStyle(Color.appTextSecondary)
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .searchable(text: $query, prompt: Text(LocalizedStringKey("CHAT_SEARCH_PLACEHOLDER")))
            .navigationTitle(LocalizedStringKey("CHAT_SEARCH_TITLE"))
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}
