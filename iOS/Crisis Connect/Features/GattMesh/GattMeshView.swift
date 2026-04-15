//
//  GattMeshView.swift
//  Crisis Connect
//
//  Created by Codex on 29.03.2026.
//

import SwiftUI

struct GattMeshView: View {
    private static let replyPreviewLimit = 72

    private enum ActiveSheet: String, Identifiable {
        case connectedPeers
        case intro

        var id: String { rawValue }
    }

    @Environment(\.dismiss) private var dismiss
    @ObservedObject private var manager = GattMeshManager.shared
    @ObservedObject private var store = GattMeshChatStore.shared
    @ObservedObject private var settings = AdvancedSettingsStore.shared

    @AppStorage("advanced.generalChatIntroDismissed") private var didDismissIntroForever = false
    @State private var draft = ""
    @State private var activeSheet: ActiveSheet?
    @State private var dismissIntroForeverSelection = false
    @State private var didDismissIntroThisSession = false
    @State private var replyTargetMessageId: String?
    @State private var pendingScrollTargetMessageId: String?
    @FocusState private var composerFocused: Bool

    var body: some View {
        ZStack {
            chatBackground

            VStack(spacing: 14) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            if store.messages.isEmpty {
                                statusCard
                                emptyStateCard
                            } else {
                                messagesList
                            }
                        }
                        .padding(.horizontal, AppTheme.screenPadding)
                        .padding(.vertical, 16)
                    }
                    .scrollIndicators(.hidden)
                    .scrollDismissesKeyboard(.interactively)
                    .onChange(of: store.messages.count) { _, _ in
                        guard pendingScrollTargetMessageId == nil else { return }
                        guard let lastId = store.messages.last?.id else { return }
                        withAnimation(.easeOut(duration: 0.2)) {
                            proxy.scrollTo(lastId, anchor: .bottom)
                        }
                    }
                    .onChange(of: pendingScrollTargetMessageId) { _, targetId in
                        guard let targetId else { return }
                        withAnimation(.easeInOut(duration: 0.22)) {
                            proxy.scrollTo(targetId, anchor: .center)
                        }
                        pendingScrollTargetMessageId = nil
                    }
                }

                composerCard
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.bottom, 12)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button(action: { dismiss() }) {
                    Image(systemName: "chevron.left")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(.primary)
                }
                .accessibilityLabel(Text("GENERAL_CHAT_BACK_ACTION"))
            }
            ToolbarItem(placement: .principal) {
                GattMeshNavigationTitleView(
                    title: localized("GENERAL_CHAT_NAV_TITLE"),
                    subtitle: navigationSubtitle,
                    isEnabled: settings.publicMeshEnabled
                )
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    activeSheet = .connectedPeers
                } label: {
                    Image(systemName: "person.2.fill")
                        .font(.system(size: 16, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                }
                .disabled(connectedPeerItems.isEmpty)
                .opacity(connectedPeerItems.isEmpty ? 0.4 : 1)
                .accessibilityLabel(Text("GENERAL_CHAT_CONNECTED_USERS_TITLE"))
            }
        }
        .appNavigationBarStyle()
        .sheet(item: $activeSheet) { sheet in
            switch sheet {
            case .connectedPeers:
                GattMeshConnectedPeersSheet(peers: connectedPeerItems)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
            case .intro:
                GeneralChatIntroSheet(
                    isExperimentalFeaturesEnabled: settings.experimentalFeaturesEnabled,
                    dismissForever: $dismissIntroForeverSelection,
                    onContinue: {
                        if dismissIntroForeverSelection {
                            didDismissIntroForever = true
                        }
                        activeSheet = nil
                    },
                    onCancel: {
                        activeSheet = nil
                        dismiss()
                    }
                )
                .presentationDetents([.fraction(0.48), .large])
                .presentationDragIndicator(.visible)
                .presentationBackgroundInteraction(.disabled)
                .interactiveDismissDisabled()
                .presentationCornerRadius(28)
            }
        }
        .onChange(of: activeSheet) { oldValue, newValue in
            guard oldValue == .intro, newValue == nil else { return }
            didDismissIntroThisSession = true
            dismissIntroForeverSelection = false
        }
        .onAppear {
            manager.setChatOpen(true)
            presentIntroIfNeeded()
        }
        .onDisappear {
            manager.setChatOpen(false)
            replyTargetMessageId = nil
            pendingScrollTargetMessageId = nil
        }
    }

    private var messagesList: some View {
        LazyVStack(spacing: 10) {
            ForEach(store.messages) { message in
                messageBubble(message)
                    .id(message.id)
            }
        }
    }

    private var statusCard: some View {
        VStack(spacing: 8) {
            Text(statusNoticeText)
                .font(.footnote)
                .foregroundStyle(Color.whatsAppInfoText)
                .multilineTextAlignment(.center)

            Text(localizedFormat("GENERAL_CHAT_CONNECTED_USERS_COUNT", meshDeviceCount))
                .font(.caption.weight(.semibold))
                .foregroundStyle(.primary)
        }
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(Color.whatsAppInfoBackground)
        )
    }

    private var emptyStateCard: some View {
        VStack(spacing: 14) {
            if settings.publicMeshEnabled {
                GeneralChatGroupAvatarView(size: 56)
            } else {
                AppIconBadge(
                    systemName: "lock.slash",
                    tint: .appWarning,
                    size: 56
                )
            }

            Text("GENERAL_CHAT_NAV_TITLE")
                .font(.title3.weight(.bold))

            Text(emptyStateText)
                .font(.footnote)
                .foregroundStyle(Color.whatsAppInfoText)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 260)
        .padding(.horizontal, 22)
        .padding(.vertical, 24)
        .background(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color.whatsAppIncoming.opacity(0.9))
        )
    }

    private var composerCard: some View {
        VStack(spacing: 12) {
            if let replyTargetMessage {
                ChatComposerReplyBanner(
                    authorLabel: replyAuthorLabel(for: replyTargetMessage),
                    preview: String(replyTargetMessage.replyPreviewText.prefix(Self.replyPreviewLimit)),
                    onDismiss: {
                        replyTargetMessageId = nil
                    }
                )
            }

            if !settings.publicMeshEnabled {
                Button {
                    settings.publicMeshEnabled = true
                } label: {
                    Text("GENERAL_CHAT_JOIN_ACTION")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle(fill: .appPrimary))
            }

            HStack(spacing: 12) {
                TextField(LocalizedStringKey("GENERAL_CHAT_COMPOSER_PLACEHOLDER"), text: $draft, axis: .vertical)
                    .lineLimit(1...4)
                    .textFieldStyle(.plain)
                    .focused($composerFocused)
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
                    .disabled(!settings.publicMeshEnabled)

                Button(action: sendDraft) {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(sendButtonDisabled ? Color.appTextSecondary : Color.white)
                        .frame(width: 44, height: 44)
                        .background(
                            Circle()
                                .fill(sendButtonDisabled ? Color.whatsAppAccent.opacity(0.35) : Color.whatsAppAccent)
                        )
                }
                .buttonStyle(.plain)
                .disabled(sendButtonDisabled)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .background(Color.whatsAppBarBackground)
    }

    private func messageBubble(_ message: GattMeshChatMessage) -> some View {
        HStack(alignment: .bottom, spacing: 8) {
            if message.isLocal {
                Spacer(minLength: 42)
                interactiveBubbleContent(for: message)
            } else {
                GeneralChatParticipantAvatarView(
                    displayName: messageSenderDisplayName(message),
                    stableKey: messageAvatarKey(message),
                    size: 30
                )
                interactiveBubbleContent(for: message)
                Spacer(minLength: 24)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private func interactiveBubbleContent(for message: GattMeshChatMessage) -> some View {
        bubbleContent(for: message)
            .chatSwipeReply {
                activateReply(to: message)
            }
            .contextMenu {
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

    private func bubbleContent(for message: GattMeshChatMessage) -> some View {
        let directPeer = manager.snapshot.connectedPeers.first { peer in
            guard let sourcePeerId = message.sourcePeerId else { return false }
            return peer.routeKeys.contains(sourcePeerId)
        }
        let originVerifiedRoleLabel = resolveGattMeshVerifiedRoleLabel(message.originVerifiedRole)
        let directPeerVerifiedRoleLabel: String?
        if let directPeer, directPeer.verificationStatus == .verified {
            directPeerVerifiedRoleLabel = resolveGattMeshVerifiedRoleLabel(directPeer.verifiedRole)
        } else {
            directPeerVerifiedRoleLabel = nil
        }
        let effectiveVerifiedRoleLabel: String?
        if !message.isLocal && message.originVerifiedAtMillis != nil {
            effectiveVerifiedRoleLabel = originVerifiedRoleLabel
        } else {
            effectiveVerifiedRoleLabel = originVerifiedRoleLabel ?? directPeerVerifiedRoleLabel
        }

        return VStack(alignment: message.isLocal ? .trailing : .leading, spacing: 6) {
            if !message.isLocal {
                VStack(alignment: .leading, spacing: 4) {
                    Text(messageSenderDisplayName(message))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.appPrimary)
                    if let effectiveVerifiedRoleLabel {
                        GattMeshVerifiedPeerBadge(verifiedRoleLabel: effectiveVerifiedRoleLabel)
                    }
                }
            }

            if let reply = message.inlineReplyMetadata {
                ChatReplyQuotedPreview(
                    preview: P2pSharedTransferSupport.previewTextForReplyTarget(reply.preview) ?? reply.preview,
                    authorLabel: reply.authorLabel,
                    isLocal: message.isLocal,
                    onTap: reply.targetMessageId.flatMap { targetId in
                        store.messages.contains(where: { $0.id == targetId })
                            ? { pendingScrollTargetMessageId = targetId }
                            : nil
                    }
                )
            }

            if !message.visibleBodyText.isEmpty {
                Text(message.visibleBodyText)
                    .font(.body)
                    .foregroundStyle(message.isLocal ? Color.white : Color.primary)
                    .frame(maxWidth: .infinity, alignment: message.isLocal ? .trailing : .leading)
            }

            HStack(spacing: 8) {
                Text(message.isLocal ? message.status.label : localized("GENERAL_CHAT_REMOTE_STATUS"))
                    .font(.caption2.weight(.bold))
                Text(formatTimestamp(message.timestampMillis))
                    .font(.caption2)
            }
            .foregroundStyle(message.isLocal ? Color.white.opacity(0.82) : Color.appTextSecondary)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: 320, alignment: message.isLocal ? .trailing : .leading)
        .background(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .fill(message.isLocal ? Color.whatsAppOutgoing : Color.whatsAppIncoming)
        )
        .overlay(
            RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                .stroke(message.isLocal ? Color.clear : Color.whatsAppInputBorder, lineWidth: 1)
        )
    }

    private func sendDraft() {
        guard settings.publicMeshEnabled else { return }

        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        let outgoingText = P2pSharedTransferSupport.buildReplyFormattedMessage(
            body: trimmed,
            replyToMessageId: replyTargetMessage?.id,
            replyPreviewText: replyTargetMessage.map { String($0.replyPreviewText.prefix(Self.replyPreviewLimit)) },
            replyAuthorLabel: replyTargetMessage.map(replyAuthorLabel(for:))
        )
        guard let result = manager.sendChatMessage(outgoingText) else { return }

        GattMeshChatStore.shared.appendLocalMessage(
            text: outgoingText,
            messageId: result.messageId,
            status: result.disposition == .sent ? .sent : .queued
        )
        draft = ""
        replyTargetMessageId = nil
    }

    private func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1_000)
        return date.formatted(date: .omitted, time: .shortened)
    }

    private func messageSenderDisplayName(_ message: GattMeshChatMessage) -> String {
        nonEmpty(message.senderLabel) ?? localized("GENERAL_CHAT_PEER_NAME_FALLBACK")
    }

    private func replyAuthorLabel(for message: GattMeshChatMessage) -> String {
        if message.isLocal {
            return localized("SOS_CHAT_REPLY_SENDER_YOU")
        }
        return messageSenderDisplayName(message)
    }

    private func activateReply(to message: GattMeshChatMessage) {
        replyTargetMessageId = message.id
        composerFocused = true
    }

    private func messageAvatarKey(_ message: GattMeshChatMessage) -> String {
        nonEmpty(message.sourcePeerId) ?? messageSenderDisplayName(message)
    }

    private var navigationSubtitle: String {
        if !settings.publicMeshEnabled {
            return localized("GENERAL_CHAT_NAV_SUBTITLE_OFF")
        }
        return localizedFormat("GENERAL_CHAT_CONNECTED_USERS_COUNT", meshDeviceCount)
    }

    private var meshDeviceCount: Int {
        let connectedCount = max(manager.snapshot.connectedPeerCount, manager.snapshot.connectedPeers.count)
        if settings.publicMeshEnabled && (manager.snapshot.isEnabled || connectedCount > 0) {
            return connectedCount + 1
        }
        return 0
    }

    private var statusNoticeText: String {
        if !settings.publicMeshEnabled {
            return localized("GENERAL_CHAT_STATUS_OFF_NOTICE")
        }
        if let lastError = nonEmpty(manager.snapshot.lastError) {
            return lastError
        }
        if manager.snapshot.sendReadyPeerCount > 0 {
            return localized("GENERAL_CHAT_STATUS_ACTIVE_NOTICE")
        }
        return localized("GENERAL_CHAT_STATUS_SEARCHING_NOTICE")
    }

    private var emptyStateText: String {
        if settings.publicMeshEnabled {
            return localized("GENERAL_CHAT_EMPTY_ENABLED")
        }
        return localized("GENERAL_CHAT_EMPTY_DISABLED")
    }

    private var sendButtonDisabled: Bool {
        !settings.publicMeshEnabled || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private var replyTargetMessage: GattMeshChatMessage? {
        guard let replyTargetMessageId else { return nil }
        return store.messages.first(where: { $0.id == replyTargetMessageId })
    }

    private var connectedPeerItems: [GattMeshConnectedPeerSheetItem] {
        var items: [GattMeshConnectedPeerSheetItem] = []

        if settings.publicMeshEnabled && manager.snapshot.isEnabled {
            items.append(
                GattMeshConnectedPeerSheetItem(
                    id: "self",
                    displayName: GattMeshProtocol.localSenderLabel(),
                    subtitle: localized("GENERAL_CHAT_THIS_DEVICE"),
                    isSelf: true,
                    isReady: true,
                    verificationStatus: .unverified,
                    verifiedRole: nil
                )
            )
        }

        items.append(
            contentsOf: manager.snapshot.connectedPeers.map { peer in
                GattMeshConnectedPeerSheetItem(
                    id: peer.id,
                    displayName: peer.displayName,
                    subtitle: peer.transportLabel,
                    isSelf: false,
                    isReady: peer.isSendReady,
                    verificationStatus: peer.verificationStatus,
                    verifiedRole: peer.verifiedRole
                )
            }
        )

        return items
    }

    private func nonEmpty(_ value: String?) -> String? {
        let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
    }

    private func presentIntroIfNeeded() {
        guard !didDismissIntroForever, !didDismissIntroThisSession, activeSheet == nil else { return }
        dismissIntroForeverSelection = false
        Task { @MainActor in
            guard activeSheet == nil else { return }
            activeSheet = .intro
        }
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return String(format: format, locale: Locale.current, arguments: arguments)
    }

    private var chatBackground: some View {
        ZStack {
            Color.whatsAppBackground
            LinearGradient(
                colors: [
                    Color.whatsAppAccent.opacity(0.045),
                    Color.clear,
                    Color.whatsAppIncoming.opacity(0.22)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
        }
        .ignoresSafeArea()
    }
}

private struct GattMeshNavigationTitleView: View {
    let title: String
    let subtitle: String
    let isEnabled: Bool

    var body: some View {
        HStack(spacing: 10) {
            GeneralChatGroupAvatarView(size: 34)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(.primary)
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(isEnabled ? Color.appTextSecondary : Color.appWarning)
                    .lineLimit(1)
            }
        }
    }
}

private struct GattMeshConnectedPeerSheetItem: Identifiable {
    let id: String
    let displayName: String
    let subtitle: String
    let isSelf: Bool
    let isReady: Bool
    let verificationStatus: GattMeshPeerVerificationStatus
    let verifiedRole: String?
}

private struct GattMeshConnectedPeersSheet: View {
    let peers: [GattMeshConnectedPeerSheetItem]

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("GENERAL_CHAT_CONNECTED_USERS_TITLE")
                            .font(.title3.weight(.bold))
                        Text(
                            peers.isEmpty
                            ? localized("GENERAL_CHAT_CONNECTED_USERS_EMPTY")
                            : localizedFormat("GENERAL_CHAT_CONNECTED_USERS_COUNT", peers.count)
                        )
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                    }

                    if peers.isEmpty {
                        Text("GENERAL_CHAT_CONNECTED_USERS_WAITING")
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .appSurface(style: .regular, padding: 18)
                    } else {
                        VStack(spacing: 10) {
                            ForEach(peers) { peer in
                                HStack(spacing: 12) {
                                    ZStack(alignment: .bottomTrailing) {
                                        GeneralChatParticipantAvatarView(
                                            displayName: peer.displayName,
                                            stableKey: peer.id,
                                            size: 38
                                        )

                                        Circle()
                                            .fill(peer.isSelf ? Color.appPrimary : (peer.isReady ? Color.appSuccess : Color.appWarning))
                                            .frame(width: 11, height: 11)
                                            .overlay(
                                                Circle()
                                                    .stroke(Color.appSurfaceElevated, lineWidth: 2)
                                            )
                                    }

                                    VStack(alignment: .leading, spacing: 4) {
                                        HStack(spacing: 8) {
                                            Text(peer.displayName)
                                                .font(.subheadline.weight(.semibold))
                                            if peer.isSelf {
                                                Text("GENERAL_CHAT_YOU")
                                                    .font(.caption2.weight(.bold))
                                                    .foregroundStyle(Color.appPrimary)
                                                    .padding(.horizontal, 7)
                                                    .padding(.vertical, 4)
                                                    .background(
                                                        Capsule(style: .continuous)
                                                            .fill(Color.appPrimarySoft)
                                                    )
                                            }
                                        }
                                        if !peer.isSelf,
                                           peer.verificationStatus == .verified {
                                            GattMeshVerifiedPeerBadge(
                                                verifiedRoleLabel: resolveGattMeshVerifiedRoleLabel(peer.verifiedRole)
                                            )
                                        }
                                        Text(peer.subtitle)
                                            .font(.caption)
                                            .foregroundStyle(Color.appTextSecondary)
                                    }

                                    Spacer()

                                    Text(peer.isSelf || peer.isReady ? localized("GENERAL_CHAT_READY") : localized("GENERAL_CHAT_CONNECTING"))
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(peer.isSelf || peer.isReady ? Color.appSuccess : Color.appWarning)
                                }
                                .appSurface(style: .regular, padding: 16)
                            }
                        }
                    }
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.vertical, 20)
            }
            .scrollIndicators(.hidden)
        }
    }

    private func localized(_ key: String) -> String {
        NSLocalizedString(key, comment: "")
    }

    private func localizedFormat(_ key: String, _ arguments: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        return String(format: format, locale: Locale.current, arguments: arguments)
    }
}

private struct GattMeshVerifiedPeerBadge: View {
    let verifiedRoleLabel: String?

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark.seal.fill")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(Color.appPrimary)
            Text("SOS_CHAT_VERIFIED")
                .font(.caption2.weight(.semibold))
            if let verifiedRoleLabel,
               !verifiedRoleLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(verifiedRoleLabel)
                    .font(.caption2)
            }
        }
        .foregroundStyle(Color.appPrimary)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule(style: .continuous)
                .fill(Color.appPrimarySoft)
        )
    }
}

private func resolveGattMeshVerifiedRoleLabel(_ verifiedRole: String?) -> String? {
    switch verifiedRole?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "admin", "fieldteam":
        return NSLocalizedString("SOS_CHAT_ROLE_FIELD_TEAM", comment: "")
    default:
        return nil
    }
}

private struct GeneralChatIntroSheet: View {
    let isExperimentalFeaturesEnabled: Bool
    @Binding var dismissForever: Bool
    let onContinue: () -> Void
    let onCancel: () -> Void

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(spacing: 12) {
                        GeneralChatGroupAvatarView(size: 48)

                        VStack(alignment: .leading, spacing: 4) {
                            Text("GENERAL_CHAT_INTRO_TITLE")
                                .font(.title3.weight(.bold))
                            Text("GENERAL_CHAT_INTRO_SUBTITLE")
                                .font(.footnote)
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                    .appSurface(style: .elevated, padding: 16)

                    VStack(alignment: .leading, spacing: 10) {
                        GeneralChatIntroBullet(textKey: "GENERAL_CHAT_INTRO_BULLET_SHARED")
                        GeneralChatIntroBullet(textKey: "GENERAL_CHAT_INTRO_BULLET_RELAY")
                        GeneralChatIntroBullet(textKey: "GENERAL_CHAT_INTRO_BULLET_PRIVACY")
                    }
                    .appSurface(style: .regular, padding: 16)

                    Text(isExperimentalFeaturesEnabled ? "GENERAL_CHAT_INTRO_FOOTER_EXPERIMENTAL" : "GENERAL_CHAT_INTRO_FOOTER_STANDARD")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(Color.appSurfaceMuted)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .stroke(Color.appBorder.opacity(0.35), lineWidth: 1)
                        )

                    Toggle(isOn: $dismissForever) {
                        Text("GENERAL_CHAT_INTRO_DONT_SHOW_AGAIN")
                            .font(.subheadline)
                    }
                    .toggleStyle(SwitchToggleStyle(tint: .appPrimary))

                    HStack(spacing: 10) {
                        Button(action: onCancel) {
                            Text("GENERAL_CHAT_INTRO_CANCEL")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(AppSecondaryButtonStyle())

                        Button(action: onContinue) {
                            Text("GENERAL_CHAT_INTRO_CONTINUE")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(AppPrimaryButtonStyle(fill: .appPrimary))
                    }
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 18)
                .padding(.bottom, 28)
            }
            .scrollIndicators(.hidden)
        }
    }
}

private struct GeneralChatIntroBullet: View {
    let textKey: LocalizedStringKey

    init(textKey: String) {
        self.textKey = LocalizedStringKey(textKey)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Circle()
                .fill(Color.appPrimary.opacity(0.8))
                .frame(width: 7, height: 7)
                .padding(.top, 6)

            Text(textKey)
                .font(.body)
                .foregroundStyle(.primary)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct GeneralChatGroupAvatarView: View {
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(Color.appPrimarySoft)

            Image(systemName: "person.2.fill")
                .font(.system(size: size * 0.42, weight: .semibold))
                .symbolRenderingMode(.monochrome)
                .foregroundStyle(Color.appPrimary)
        }
        .overlay(
            Circle()
                .stroke(Color.appBorder.opacity(0.35), lineWidth: 1)
        )
        .frame(width: size, height: size)
    }
}

private struct GeneralChatParticipantAvatarView: View {
    let displayName: String
    let stableKey: String
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .fill(backgroundColor)

            Text(AvatarGenerator.initials(from: displayName))
                .font(size >= 36 ? .headline.weight(.bold) : .caption.weight(.bold))
                .foregroundStyle(Color.white)
        }
        .overlay(
            Circle()
                .stroke(Color.appBorder.opacity(0.28), lineWidth: 1)
        )
        .frame(width: size, height: size)
    }

    private var backgroundColor: Color {
        Self.palette[paletteIndex]
    }

    private var paletteIndex: Int {
        let normalizedKey = stableKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            ? displayName
            : stableKey

        var hash: UInt64 = 5381
        for scalar in normalizedKey.unicodeScalars {
            hash = ((hash << 5) &+ hash) &+ UInt64(scalar.value)
        }

        return Int(hash % UInt64(Self.palette.count))
    }

    private static let palette: [Color] = [
        Color(red: 46 / 255, green: 79 / 255, blue: 111 / 255),
        Color(red: 58 / 255, green: 97 / 255, blue: 115 / 255),
        Color(red: 74 / 255, green: 93 / 255, blue: 137 / 255),
        Color(red: 72 / 255, green: 96 / 255, blue: 67 / 255),
        Color(red: 108 / 255, green: 84 / 255, blue: 55 / 255),
        Color(red: 113 / 255, green: 71 / 255, blue: 71 / 255),
        Color(red: 90 / 255, green: 77 / 255, blue: 116 / 255),
        Color(red: 47 / 255, green: 106 / 255, blue: 98 / 255),
        Color(red: 78 / 255, green: 102 / 255, blue: 116 / 255),
        Color(red: 94 / 255, green: 107 / 255, blue: 68 / 255)
    ]
}
