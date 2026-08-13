//
//  ContentMessagesHome.swift
//  Crisis Connect
//
//  Created by Assistant on 28.03.2026.
//

import Combine
import Foundation
import SwiftUI

struct MessagesHomeView: View {
    @Binding var searchText: String
    let showRescueTools: Bool
    let onOpenNewChat: () -> Void
    let onOpenSOS: () -> Void

    @StateObject private var listState = MessagesSessionListState()
    @FocusState private var isMessagesSearchFocused: Bool
    @State private var selectedMessagesFilter: MessagesInboxFilter = .all
    @ObservedObject private var sosChatStore = SOSChatStore.shared
    @ObservedObject private var contactStore = ContactStore.shared
    @ObservedObject private var gattMeshStore = GattMeshChatStore.shared
    @ObservedObject private var authorityMeshStore = GattMeshChatStore.authority
    @ObservedObject private var advancedSettings = AdvancedSettingsStore.shared
    // Kurum channel conversations surfaced as normal-looking rows on the home list (Android
    // parity) — the same store also powers the full directory behind New Chat → Add from agency.
    @StateObject private var authorityChannelRows = AuthorityChannelsListStore()
    @State private var isCrisisSentinelReady = false
    // Full-history message search (Android's "Messages" results section): debounced sweep over
    // every session's transcript, newest first, capped.
    @State private var messageSearchRows: [MessageSearchRow] = []
    @State private var messageSearchTask: Task<Void, Never>?

    private let messagesHeaderAnchor = "messages-header-anchor"
    private let messagesHeaderAnimation = Animation.spring(response: 0.3, dampingFraction: 0.86)

    var body: some View {
        ZStack {
            AppScreenBackground()

            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 18) {
                        Color.clear
                            .frame(height: 1)
                            .id(messagesHeaderAnchor)

                        messagesHeroHeader
                        // Android parity: rescue-certified users always get the Sentinel entry —
                        // they can use the CLOUD engine without downloading the on-device model.
                        // Everyone else sees it only once the model is installed.
                        if isCrisisSentinelReady || showRescueTools {
                            crisisSentinelEntryCard
                        }
                        if advancedSettings.publicMeshEnabled {
                            publicMeshEntryCard
                        }
                        if showRescueTools && advancedSettings.authorityMeshEnabled {
                            authorityMeshEntryCard
                        }

                        if sosChatStore.sessions.isEmpty && messagedAuthorityRows.isEmpty {
                            emptyMessagesCard
                        } else if !listState.isPrepared {
                            messagesLoadingCard
                        } else if sessionBuckets.sections.isEmpty {
                            if !messageSearchRows.isEmpty {
                                messagesSearchSection
                            } else if sessionBuckets.searchResults.isEmpty && messagedAuthorityRows.isEmpty {
                                // Something is present but the current filter/search hides it all.
                                filteredEmptyCard
                            } else {
                                filterEmptyCard
                            }
                        } else {
                            messageSessionsSection
                            if !messageSearchRows.isEmpty {
                                messagesSearchSection
                            }
                        }
                    }
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.top, 12)
                    .padding(.bottom, 130)
                    .background(ScrollViewTouchFixer())
                }
                .scrollIndicators(.hidden)
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: isMessagesSearchFocused) { _, isFocused in
                    guard isFocused else { return }
                    withAnimation(messagesHeaderAnimation) {
                        proxy.scrollTo(messagesHeaderAnchor, anchor: .top)
                    }
                }
            }

            VStack {
                Spacer()
                HStack {
                    if showRescueTools {
                        NavigationLink(destination: LazyNavigationDestination {
                            RescueClientView()
                        }) {
                            RescueFloatingButton()
                        }
                        .buttonStyle(.plain)
                    }

                    Spacer()

                    SOSFloatingButton(action: onOpenSOS)
                }
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.bottom, 18)
            }
        }
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarRole(.navigationStack)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                profileToolbarLink
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    onOpenNewChat()
                } label: {
                    Image(systemName: "plus")
                        .imageScale(.large)
                }
                .accessibilityLabel("New Contact")
            }
        }
        .onAppear {
            isCrisisSentinelReady = checkCrisisSentinelReady()
            refreshFilteredSessions()
            if showRescueTools {
                Task {
                    await AuthorityMeshGroupKeyProvisioner.ensureGroupKey()
                }
                // Populate/refresh the kurum conversation rows on every appearance — returning
                // from a thread must clear its unread badge (the thread advanced the read cursor).
                Task { await authorityChannelRows.refresh() }
            }
            if AppStoreScreenshotSupport.isMessagesSearchSceneEnabled {
                searchText = AppStoreScreenshotSupport.screenshotSearchQuery
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    isMessagesSearchFocused = true
                }
            }
        }
        .onReceive(sosChatStore.$sessions.receive(on: RunLoop.main)) { sessions in
            refreshFilteredSessions(sessions: sessions)
        }
        .onReceive(contactStore.$contacts.receive(on: RunLoop.main)) { contacts in
            refreshFilteredSessions(contacts: contacts)
        }
        .onChange(of: searchText) { _, newValue in
            refreshFilteredSessions(query: newValue)
            scheduleMessageSearch(query: newValue)
        }
        .onReceive(NotificationCenter.default.publisher(for: .crisisSentinelModelDidInstall).receive(on: RunLoop.main)) { _ in
            isCrisisSentinelReady = checkCrisisSentinelReady()
        }
    }

    private func checkCrisisSentinelReady() -> Bool {
        if ProcessInfo.processInfo.arguments.contains("UITEST_MOCK_CRISIS_SENTINEL_READY") {
            return true
        }
        let release = CrisisSentinelModelManifestCache().load() ?? .defaultRelease
        return CrisisSentinelModelFileStore().status(for: release, validateChecksum: false).isReady
    }

    private var profileToolbarLink: some View {
        NavigationLink(destination: LazyNavigationDestination {
            ProfileView()
        }) {
            if ProcessInfo.processInfo.isiOSAppOnMac {
                ProfileToolbarCompactView()
            } else {
                ProfileToolbarLabelView()
            }
        }
        // No `.buttonStyle(.plain)`: that opts the item out of iOS 26's native
        // Liquid Glass platter. Keep the system glass and just pin the tint so
        // the avatar/name stay neutral instead of accent-blue.
        .tint(Color.primary)
    }

    private var sessionBuckets: MessagesSessionBuckets {
        MessagesSessionBuckets(
            searchResults: listState.filteredSessions,
            authorityRows: messagedAuthorityRows,
            selectedFilter: selectedMessagesFilter
        )
    }

    private var messagesHeroHeader: some View {
        VStack(alignment: .leading, spacing: isMessagesSearchFocused ? 12 : 16) {
            VStack(alignment: .leading, spacing: isMessagesSearchFocused ? 4 : 6) {
                Text("Messages")
                    .font(.system(size: isMessagesSearchFocused ? 30 : 34, weight: .bold, design: .rounded))
                    .tracking(isMessagesSearchFocused ? -0.7 : -0.9)
                    .foregroundStyle(.primary)

                Text("MESSAGES_HEADER_SUBTITLE")
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                    .opacity(isMessagesSearchFocused ? 0.72 : 1)
            }
            .offset(y: isMessagesSearchFocused ? -3 : 0)
            .scaleEffect(isMessagesSearchFocused ? 0.97 : 1, anchor: .topLeading)

            HStack(alignment: .center, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color.appTextSecondary)

                    TextField("Search chats", text: $searchText)
                        .focused($isMessagesSearchFocused)
                        .submitLabel(.search)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    if searchText.isEmpty == false {
                        Button {
                            searchText = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Color.appTextSecondary.opacity(0.75))
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Clear search")
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                        .fill(Color.appSurfaceElevated)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                        .stroke(isMessagesSearchFocused ? Color.appPrimary.opacity(0.55) : Color.appBorder, lineWidth: 1)
                )
                .shadow(color: Color.black.opacity(isMessagesSearchFocused ? 0.06 : 0), radius: 12, y: 4)

                if isMessagesSearchFocused {
                    Button("COMMON_CANCEL") {
                        isMessagesSearchFocused = false
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appPrimary)
                    .transition(.move(edge: .trailing).combined(with: .opacity))
                }
            }

            messagesFilterStrip
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
        .animation(messagesHeaderAnimation, value: isMessagesSearchFocused)
        .animation(messagesHeaderAnimation, value: searchText.isEmpty)
    }

    private var emptyMessagesCard: some View {
        VStack(spacing: 14) {
            AppIconBadge(systemName: "person.crop.circle.badge.plus", tint: .appPrimary, size: 56)

            Text("No messages yet")
                .font(.title3.weight(.bold))

            Text("NEW_CONTACT_EMPTY_DETAIL")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)

            Button {
                onOpenNewChat()
            } label: {
                Label("NEW_CONTACT_ADD_CONTACT", systemImage: "plus")
            }
            .buttonStyle(AppPrimaryButtonStyle())
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .elevated, padding: 22)
    }

    private var publicMeshEntryCard: some View {
        NavigationLink(destination: LazyNavigationDestination {
            GattMeshView()
        }) {
            HStack(spacing: 14) {
                GeneralChatGroupAvatarView(size: 54)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text("GENERAL_CHAT_ENTRY_TITLE")
                            .font(.headline)
                            .foregroundStyle(.primary)

                        if gattMeshStore.unreadCount > 0 {
                            Text("\(gattMeshStore.unreadCount)")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(Color.appPrimary)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule(style: .continuous)
                                        .fill(Color.appPrimarySoft)
                                )
                        }
                    }

                    Text(publicMeshSummary)
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
    }

    private var crisisSentinelEntryCard: some View {
        NavigationLink(destination: LazyNavigationDestination {
            CrisisSentinelHomeView()
        }) {
            HStack(spacing: 14) {
                AppCustomIconBadge(tint: .appPrimary, size: 54) {
                    CrisisSentinelSparklesIcon()
                }

                VStack(alignment: .leading, spacing: 5) {
                    Text("CRISIS_SENTINEL_TITLE")
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text("CRISIS_SENTINEL_MAIN_ENTRY_PREVIEW")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("messages-crisis-sentinel-entry")
    }

    private var authorityMeshEntryCard: some View {
        NavigationLink(destination: LazyNavigationDestination {
            GattMeshView(profile: .authority)
        }) {
            HStack(spacing: 14) {
                AppIconBadge(systemName: "checkmark.shield.fill", tint: .appPrimary, size: 54)

                VStack(alignment: .leading, spacing: 5) {
                    HStack(spacing: 8) {
                        Text("AUTHORITY_CHAT_ENTRY_TITLE")
                            .font(.headline)
                            .foregroundStyle(.primary)

                        if authorityMeshStore.unreadCount > 0 {
                            Text("\(authorityMeshStore.unreadCount)")
                                .font(.caption.weight(.bold))
                                .foregroundStyle(Color.appPrimary)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule(style: .continuous)
                                        .fill(Color.appPrimarySoft)
                                )
                        }
                    }

                    Text("AUTHORITY_CHAT_ENTRY_SUMMARY")
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.footnote.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .appSurface(style: .regular, padding: 16)
        }
        .buttonStyle(.plain)
    }

    /// Messaged kurum channel conversations as normal-looking rows (Android parity): peer name,
    /// panel, decrypted preview and timestamp. Never-messaged peers stay behind New Chat.
    private var messagedAuthorityRows: [AuthorityHomeRow] {
        guard showRescueTools else { return [] }
        let query = MessagesSessionListState.searchFold(
            searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        )
        return (authorityChannelRows.groups.flatMap(\.rows) + authorityChannelRows.agencyConversationRows)
            .filter { $0.previewAt != nil }
            .filter { row in
                query.isEmpty
                    || MessagesSessionListState.searchFold(row.peer.name).contains(query)
                    || MessagesSessionListState.searchFold(row.preview ?? "").contains(query)
            }
            .map { AuthorityHomeRow(row: $0) }
    }

    private var messagesLoadingCard: some View {
        VStack(spacing: 12) {
            ProgressView()
                .controlSize(.large)
            Text("NEW_CONTACT_LOADING_TITLE")
                .font(.headline)
            Text("NEW_CONTACT_LOADING_DETAIL")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .regular, padding: 20)
    }

    /// One matched transcript message in the home search results (Android's SearchMessageRowUi).
    private struct MessageSearchRow: Identifiable {
        let id: UUID
        let sessionId: UUID
        let displayName: String
        let body: String
        let at: Date
    }

    /// Debounced full-history sweep: every session's messages, folded matching, newest first.
    private func scheduleMessageSearch(query: String) {
        messageSearchTask?.cancel()
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            messageSearchRows = []
            return
        }
        messageSearchTask = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 250_000_000)
            guard !Task.isCancelled else { return }
            let folded = MessagesSessionListState.searchFold(trimmed)
            var rows: [MessageSearchRow] = []
            for session in SOSChatStore.shared.sessions {
                for message in SOSChatStore.shared.messages(for: session.id) {
                    guard message.kind == .text else { continue }
                    let raw = message.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !raw.isEmpty, !raw.hasPrefix("CC_") else { continue }
                    let display = P2pSharedTransferSupport.previewText(for: raw)
                    guard MessagesSessionListState.searchFold(display).contains(folded) else { continue }
                    rows.append(MessageSearchRow(
                        id: message.id,
                        sessionId: session.id,
                        displayName: session.displayName,
                        body: display,
                        at: message.timestamp
                    ))
                }
            }
            guard !Task.isCancelled else { return }
            messageSearchRows = Array(rows.sorted { $0.at > $1.at }.prefix(50))
        }
    }

    /// Android's "Messages" search section: matched transcript messages as tappable rows.
    private var messagesSearchSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("MAIN_SEARCH_SECTION_MESSAGES")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Color.appPrimary)
                .padding(.horizontal, 4)

            VStack(spacing: 0) {
                ForEach(Array(messageSearchRows.enumerated()), id: \.element.id) { index, row in
                    NavigationLink(destination: LazyNavigationDestination {
                        SOSChatDetailScreen(sessionId: row.sessionId)
                    }) {
                        HStack(spacing: 12) {
                            ChatAvatarCircleView(
                                avatarImageRelativePath: nil,
                                initials: AvatarGenerator.initials(from: row.displayName),
                                avatarHue: AvatarGenerator.hue(for: row.sessionId),
                                size: 42,
                                peerPhotoUrl: ContactStore.shared.contact(for: row.sessionId)?.peerPhotoUrl
                            )
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.displayName)
                                    .font(.body.weight(.medium))
                                    .foregroundStyle(.primary)
                                    .lineLimit(1)
                                Text(highlightedBody(row.body))
                                    .font(.footnote)
                                    .foregroundStyle(Color.appTextSecondary)
                                    .lineLimit(2)
                                    .multilineTextAlignment(.leading)
                            }
                            Spacer(minLength: 8)
                            Text(row.at, style: .time)
                                .font(.caption2)
                                .foregroundStyle(Color.appTextSecondary)
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)

                    if index < messageSearchRows.count - 1 {
                        Divider()
                            .padding(.leading, 70)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
        }
    }

    /// Bolds + tints the first query hit inside a result body (Android highlights matches too).
    private func highlightedBody(_ body: String) -> AttributedString {
        var attributed = AttributedString(body)
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        if !query.isEmpty,
           let range = attributed.range(of: query, options: [.caseInsensitive, .diacriticInsensitive]) {
            attributed[range].font = .footnote.weight(.bold)
            attributed[range].foregroundColor = .appPrimary
        }
        return attributed
    }

    private var filteredEmptyCard: some View {
        VStack(spacing: 12) {
            AppIconBadge(systemName: "magnifyingglass", tint: .appWarning, size: 52)
            Text(LocalizedStringKey("MESSAGES_SEARCH_EMPTY_TITLE"))
                .font(.headline)
            Text(LocalizedStringKey("MESSAGES_SEARCH_EMPTY_MESSAGE"))
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .regular, padding: 20)
    }

    private var filterEmptyCard: some View {
        VStack(spacing: 12) {
            AppIconBadge(systemName: selectedMessagesFilter.iconName, tint: selectedMessagesFilter.tint, size: 52)
            Text(LocalizedStringKey("MESSAGES_FILTER_EMPTY_TITLE"))
                .font(.headline)
            Text(filterEmptyMessageKey)
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .regular, padding: 20)
    }

    private var messageSessionsSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("MESSAGES_SECTION_TITLE")
                    .font(.headline)
                Spacer()
                if selectedMessagesFilter != .all {
                    Button("MESSAGES_CLEAR_FILTER") {
                        selectedMessagesFilter = .all
                    }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.appPrimary)
                    .buttonStyle(.plain)
                }
            }

            LazyVStack(spacing: 14) {
                ForEach(sessionBuckets.sections) { section in
                    MessagesSessionSectionCard(
                        section: section,
                        onMarkRead: { sessionId in
                            sosChatStore.markRemoteRead(sessionId: sessionId)
                        }
                    )
                }
            }
        }
    }

    private var messagesFilterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach(visibleFilters) { filter in
                    Button {
                        selectedMessagesFilter = filter
                    } label: {
                        HStack(spacing: 8) {
                            Image(systemName: filter.iconName)
                                .font(.system(size: 13, weight: .semibold))

                            Text(filter.titleKey)
                                .font(.subheadline.weight(.semibold))
                                .lineLimit(1)

                            Text("\(count(for: filter))")
                                .font(.caption.weight(.bold))
                                .monospacedDigit()
                                .padding(.horizontal, 7)
                                .padding(.vertical, 4)
                                .background(
                                    Capsule(style: .continuous)
                                        .fill(filterBadgeFill(for: filter))
                                )
                        }
                        .foregroundStyle(filterForeground(for: filter))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .background(
                            Capsule(style: .continuous)
                                .fill(filterBackground(for: filter))
                        )
                        .overlay(
                            Capsule(style: .continuous)
                                .stroke(filterStroke(for: filter), lineWidth: 1)
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.vertical, 2)
        }
        .scrollIndicators(.hidden)
    }

    private var visibleFilters: [MessagesInboxFilter] {
        MessagesInboxFilter.allCases.filter { filter in
            filter == .all || sessionBuckets.count(for: filter) > 0
        }
    }

    private var filterEmptyMessageKey: LocalizedStringKey {
        switch selectedMessagesFilter {
        case .all:
            return "MESSAGES_SEARCH_EMPTY_MESSAGE"
        case .unread:
            return "MESSAGES_FILTER_EMPTY_UNREAD"
        case .direct:
            return "MESSAGES_FILTER_EMPTY_DIRECT"
        case .rescue:
            return "MESSAGES_FILTER_EMPTY_RESCUE"
        }
    }

    private func count(for filter: MessagesInboxFilter) -> Int {
        sessionBuckets.count(for: filter)
    }

    private func filterBackground(for filter: MessagesInboxFilter) -> Color {
        selectedMessagesFilter == filter ? filter.tint.opacity(0.14) : Color.appSurfaceElevated
    }

    private func filterStroke(for filter: MessagesInboxFilter) -> Color {
        selectedMessagesFilter == filter ? filter.tint.opacity(0.32) : Color.appBorder
    }

    private func filterForeground(for filter: MessagesInboxFilter) -> Color {
        selectedMessagesFilter == filter ? filter.tint : .primary
    }

    private func filterBadgeFill(for filter: MessagesInboxFilter) -> Color {
        selectedMessagesFilter == filter ? filter.tint.opacity(0.16) : Color.appPrimarySoft
    }

    private func refreshFilteredSessions(
        sessions: [SOSChatSession]? = nil,
        query: String? = nil,
        contacts: [ContactRecord]? = nil
    ) {
        let sourceSessions = sessions ?? sosChatStore.sessions
        // Authority bridge contacts are transport-only; their conversation is the channel row.
        let sourceContacts = (contacts ?? contactStore.contacts).filter { $0.isAuthorityBridge != true }
        let lastMessages: [UUID: SOSChatMessage] = Dictionary(uniqueKeysWithValues: sourceSessions.compactMap { session in
            guard let message = sosChatStore.lastMessage(for: session.id) else { return nil }
            return (session.id, message)
        })

        listState.refresh(
            sessions: sourceSessions,
            contacts: sourceContacts,
            query: query ?? searchText,
            lastMessages: lastMessages,
            isCurrentUserRescue: showRescueTools
        )
    }

    private var publicMeshSummary: String {
        NSLocalizedString("GENERAL_CHAT_ENTRY_SUMMARY", comment: "")
    }
}

private func sessionRepresentsFieldTeam(
    session: SOSChatSession,
    contactRecord: ContactRecord?,
    isCurrentUserRescue: Bool
) -> Bool {
    switch session.role {
    case .fieldTeam:
        return true
    case .victim:
        return false
    case .unknown:
        return false
    }
}

private struct MessagesSessionListItem: Identifiable, Equatable {
    let session: SOSChatSession
    let contactRecord: ContactRecord?
    let lastMessage: SOSChatMessage?
    let isCurrentUserRescue: Bool

    var id: UUID { session.id }

    var isDirectConversation: Bool {
        contactRecord?.preferredTransport == .bleGatt
    }

    var isRescueConversation: Bool {
        sessionRepresentsFieldTeam(
            session: session,
            contactRecord: contactRecord,
            isCurrentUserRescue: isCurrentUserRescue
        )
    }
}

/// A messaged authority (kurum) channel conversation as a home-list row.
private struct AuthorityHomeRow: Identifiable {
    let row: AuthorityChannelsListStore.PeerRow
    var id: String { row.id }
}

/// A single home-list conversation — a citizen chat OR a kurum channel — so the two interleave in
/// one recency-sorted list (Android parity) instead of living in separate blocks.
private enum MessagesRowEntry: Identifiable {
    case citizen(MessagesSessionListItem)
    case authority(AuthorityHomeRow)

    var id: String {
        switch self {
        case .citizen(let item): return "citizen:\(item.session.id.uuidString)"
        case .authority(let a): return "authority:\(a.id)"
        }
    }

    var updatedAt: Date {
        switch self {
        case .citizen(let item): return item.session.lastUpdated
        case .authority(let a): return a.row.previewAt ?? .distantPast
        }
    }

    var isUnread: Bool {
        switch self {
        case .citizen(let item): return item.session.unreadCount > 0
        case .authority(let a): return a.row.unread
        }
    }

    var isRescue: Bool {
        if case .citizen(let item) = self { return item.isRescueConversation }
        return false
    }

    var isDirect: Bool {
        if case .citizen(let item) = self { return item.isDirectConversation }
        return false
    }
}

private struct MessagesSessionBuckets {
    let searchResults: [MessagesSessionListItem]
    let visibleItems: [MessagesRowEntry]
    let sections: [MessagesSessionSection]
    private let counts: [MessagesInboxFilter: Int]

    init(
        searchResults: [MessagesSessionListItem],
        authorityRows: [AuthorityHomeRow],
        selectedFilter: MessagesInboxFilter
    ) {
        self.searchResults = searchResults

        // One merged pool: citizen chats + kurum channel rows.
        let allEntries: [MessagesRowEntry] =
            searchResults.map(MessagesRowEntry.citizen) + authorityRows.map(MessagesRowEntry.authority)

        var counts: [MessagesInboxFilter: Int] = [.all: allEntries.count]
        counts[.unread] = 0
        counts[.direct] = 0
        counts[.rescue] = 0
        for entry in allEntries {
            if entry.isUnread { counts[.unread, default: 0] += 1 }
            if entry.isDirect { counts[.direct, default: 0] += 1 }
            if entry.isRescue { counts[.rescue, default: 0] += 1 }
        }
        self.counts = counts

        let filtered = selectedFilter == .all ? allEntries : allEntries.filter { entry in
            switch selectedFilter {
            case .all: return true
            case .unread: return entry.isUnread
            case .direct: return entry.isDirect
            case .rescue: return entry.isRescue
            }
        }
        visibleItems = filtered

        var attention: [MessagesRowEntry] = []
        var rescue: [MessagesRowEntry] = []
        var recent: [MessagesRowEntry] = []
        for entry in filtered {
            if entry.isUnread { attention.append(entry) }
            else if entry.isRescue { rescue.append(entry) }
            else { recent.append(entry) }
        }
        // Within each section, newest first — authority rows interleave with citizen ones by recency.
        let byRecency: (MessagesRowEntry, MessagesRowEntry) -> Bool = { $0.updatedAt > $1.updatedAt }
        sections = [
            MessagesSessionSection(kind: .attention, items: attention.sorted(by: byRecency)),
            MessagesSessionSection(kind: .rescue, items: rescue.sorted(by: byRecency)),
            MessagesSessionSection(kind: .recent, items: recent.sorted(by: byRecency))
        ]
        .filter { !$0.items.isEmpty }
    }

    func count(for filter: MessagesInboxFilter) -> Int {
        counts[filter, default: 0]
    }
}

@MainActor
private final class MessagesSessionListState: ObservableObject {
    @Published private(set) var filteredSessions: [MessagesSessionListItem] = []
    @Published private(set) var isPrepared = false

    private static let filterQueue = DispatchQueue(label: "messages.session.filter", qos: .userInitiated)
    private var generation = 0

    func refresh(
        sessions: [SOSChatSession],
        contacts: [ContactRecord],
        query: String,
        lastMessages: [UUID: SOSChatMessage],
        isCurrentUserRescue: Bool
    ) {
        generation += 1
        let currentGeneration = generation
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines)

        Self.filterQueue.async { [weak self] in
            let sortedSessions = sessions.sorted { $0.lastUpdated > $1.lastUpdated }
            let contactMap = Dictionary(uniqueKeysWithValues: contacts.map { ($0.id, $0) })
            let allItems = sortedSessions.map { session in
                MessagesSessionListItem(
                    session: session,
                    contactRecord: contactMap[session.id],
                    lastMessage: lastMessages[session.id],
                    isCurrentUserRescue: isCurrentUserRescue
                )
            }

            let result: [MessagesSessionListItem]
            if trimmedQuery.isEmpty {
                result = allItems
            } else {
                // Locale-aware fold (Android's normalizeForSearch): plain lowercased() breaks on
                // Turkish dotted/dotless i — "İstanbul" would never match a query of "istanbul".
                let foldedQuery = Self.searchFold(trimmedQuery)
                result = allItems.filter { item in
                    let session = item.session
                    let contactDisplayName: String?
                    if let contact = item.contactRecord,
                       contact.preferredTransport == .bleGatt {
                        contactDisplayName = contact.name.trimmingCharacters(in: .whitespacesAndNewlines)
                    } else {
                        contactDisplayName = nil
                    }

                    let candidates = [
                        session.displayName,
                        contactDisplayName,
                        session.lastMessage
                    ]

                    return candidates
                        .compactMap { $0.map(Self.searchFold) }
                        .contains { $0.contains(foldedQuery) }
                }
            }

            DispatchQueue.main.async { [weak self] in
                guard let self, currentGeneration == self.generation else { return }
                self.filteredSessions = result
                self.isPrepared = true
            }
        }
    }

    /// Case-, diacritic- and locale-insensitive normalization for search matching.
    static func searchFold(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: .current)
    }
}

private enum MessagesInboxFilter: String, CaseIterable, Identifiable {
    case all
    case unread
    case direct
    case rescue

    var id: String { rawValue }

    var titleKey: LocalizedStringKey {
        switch self {
        case .all:
            return "MESSAGES_FILTER_ALL"
        case .unread:
            return "MESSAGES_FILTER_UNREAD"
        case .direct:
            return "MESSAGES_FILTER_DIRECT"
        case .rescue:
            return "MESSAGES_FILTER_RESCUE"
        }
    }

    var iconName: String {
        switch self {
        case .all:
            return "bubble.left.and.bubble.right.fill"
        case .unread:
            return "circle.badge.fill"
        case .direct:
            return "dot.radiowaves.left.and.right"
        case .rescue:
            return "cross.case.fill"
        }
    }

    var tint: Color {
        switch self {
        case .all:
            return .appPrimary
        case .unread:
            return .appPrimary
        case .direct:
            return .appSuccess
        case .rescue:
            return .appWarning
        }
    }

    func matches(_ item: MessagesSessionListItem) -> Bool {
        switch self {
        case .all:
            return true
        case .unread:
            return item.session.unreadCount > 0
        case .direct:
            return item.isDirectConversation
        case .rescue:
            return item.isRescueConversation
        }
    }
}

private struct MessagesSessionSection: Identifiable {
    enum Kind: String {
        case attention
        case rescue
        case recent

        var titleKey: LocalizedStringKey {
            switch self {
            case .attention:
                return "MESSAGES_SECTION_ATTENTION"
            case .rescue:
                return "MESSAGES_SECTION_RESCUE"
            case .recent:
                return "MESSAGES_SECTION_RECENT"
            }
        }

        var subtitleKey: LocalizedStringKey {
            switch self {
            case .attention:
                return "MESSAGES_SECTION_ATTENTION_SUBTITLE"
            case .rescue:
                return "MESSAGES_SECTION_RESCUE_SUBTITLE"
            case .recent:
                return "MESSAGES_SECTION_RECENT_SUBTITLE"
            }
        }
    }

    let kind: Kind
    let items: [MessagesRowEntry]

    var id: Kind { kind }
}

private struct MessagesSessionSectionCard: View {
    let section: MessagesSessionSection
    let onMarkRead: (UUID) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(section.kind.titleKey)
                    .font(.headline)

                Text(section.kind.subtitleKey)
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
                    .lineLimit(1)

                Spacer()

                Text("\(section.items.count)")
                    .font(.caption.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(Color.appTextSecondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 5)
                    .background(
                        Capsule(style: .continuous)
                            .fill(Color.appPrimarySoft)
                    )
            }
            .padding(.horizontal, 4)

            VStack(spacing: 0) {
                ForEach(section.items.indices, id: \.self) { index in
                    switch section.items[index] {
                    case .citizen(let item):
                        NavigationLink(destination: LazyNavigationDestination {
                            SOSChatDetailScreen(sessionId: item.session.id)
                        }) {
                            SOSChatSessionRow(
                                session: item.session,
                                contactRecord: item.contactRecord,
                                lastMessage: item.lastMessage
                            )
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            if item.session.unreadCount > 0 {
                                Button {
                                    onMarkRead(item.session.id)
                                } label: {
                                    Label("MESSAGES_MARK_READ", systemImage: "checkmark.circle")
                                }
                            }
                        }
                    case .authority(let authority):
                        NavigationLink(destination: LazyNavigationDestination {
                            HierarchyThreadView(
                                channel: authority.row.channel,
                                peer: authority.row.peer,
                                scopeType: authority.row.scopeType
                            )
                        }) {
                            AuthorityHomeRowView(row: authority.row)
                                .padding(.horizontal, 16)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                    }

                    if index < section.items.count - 1 {
                        Divider()
                            .padding(.leading, 88)
                    }
                }
            }
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .fill(Color.appSurfaceElevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerLarge, style: .continuous)
                    .stroke(Color.appBorder, lineWidth: 1)
            )
            .shadow(
                color: .black.opacity(colorScheme == .light ? 0.04 : 0),
                radius: 10,
                y: 4
            )
        }
    }
}

/// A kurum channel conversation as a normal home-list row — the SAME visual language as a citizen
/// chat row (SOSChatSessionRow) and Android's ContactListItem: 56pt initials/photo avatar, name +
/// agency chip, decrypted preview, timestamp, unread badge, live-bridge radio.
private struct AuthorityHomeRowView: View {
    let row: AuthorityChannelsListStore.PeerRow
    @Environment(\.colorScheme) private var colorScheme

    private var hasUnread: Bool { row.unread }

    private var avatarColor: Color {
        let hue = AvatarGenerator.hue(for: BroadcastSessionId.fromRawIdentifier(row.peer.uid))
        let saturation = colorScheme == .dark ? 0.55 : 0.6
        let brightness = colorScheme == .dark ? 0.75 : 0.9
        return Color(hue: hue, saturation: saturation, brightness: brightness)
    }

    private var agencyLabel: String {
        let name = row.peer.agency ?? row.channel.peerPanelName
        return name.isEmpty
            ? NSLocalizedString("AUTHORITY_CHANNEL_ROW_LABEL", comment: "")
            : name
    }

    var body: some View {
        HStack(spacing: 14) {
            AuthorityAvatarView(
                name: row.peer.name,
                uid: row.peer.uid,
                photoUrl: row.peer.photoUrl,
                size: 56,
                borderColor: hasUnread ? avatarColor.opacity(colorScheme == .dark ? 0.55 : 0.34) : .clear,
                borderWidth: 2
            )

            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top, spacing: 12) {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(alignment: .firstTextBaseline, spacing: 8) {
                            Text(row.peer.name.isEmpty ? row.peer.uid : row.peer.name)
                                .font(.headline.weight(hasUnread ? .semibold : .medium))
                                .foregroundStyle(.primary)
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .layoutPriority(1)
                            if row.bluetoothLinked {
                                Image(systemName: "dot.radiowaves.left.and.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)

                        AppStatusPill(title: LocalizedStringKey(agencyLabel), tint: .appPrimary)
                    }

                    Spacer(minLength: 8)

                    VStack(alignment: .trailing, spacing: 8) {
                        if let at = row.previewAt {
                            Text(at, style: .time)
                                .font(.caption.weight(hasUnread ? .semibold : .medium))
                                .foregroundStyle(hasUnread ? Color.appPrimary : Color.appTextSecondary)
                        }
                        if hasUnread {
                            Circle().fill(Color.appPrimary).frame(width: 10, height: 10)
                        }
                    }
                }

                Text(row.preview ?? "")
                    .font(.subheadline.weight(hasUnread ? .semibold : .regular))
                    .foregroundStyle(hasUnread ? Color.primary : Color.appTextSecondary)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }
}

private struct SOSFloatingButton: View {
    var action: () -> Void
    @ObservedObject private var manager = SOSBroadcastManager.shared
    @State private var pulse = false

    private var isActive: Bool {
        manager.isSessionActive
    }

    private var elapsedText: String? {
        isActive ? manager.elapsedText : nil
    }

    var body: some View {
        let glowOpacity = isActive ? (pulse ? 0.42 : 0.18) : 0.08
        let glowScale = isActive ? (pulse ? 1.12 : 1.02) : 1
        Button(action: action) {
            HStack(spacing: 12) {
                HStack(spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.system(size: 16, weight: .semibold))
                    Text(LocalizedStringKey("SOS_BUTTON_LABEL"))
                        .font(.system(.headline, design: .rounded).weight(.semibold))
                }
                if let elapsedText {
                    Text("- \(elapsedText)")
                        .font(.caption.weight(.semibold))
                        .monospacedDigit()
                }
            }
            .foregroundColor(.white)
            .padding(.horizontal, isActive ? 26 : 22)
            .padding(.vertical, 14)
            .background(
                Capsule().fill(Color.appSOS)
            )
            .overlay(
                Capsule().stroke(Color.white.opacity(0.18), lineWidth: 1)
            )
            .shadow(color: Color.appSOS.opacity(isActive ? (pulse ? 0.34 : 0.2) : 0.14), radius: 12, y: 5)
            .background(
                Capsule()
                    .fill(Color.appSOS.opacity(glowOpacity))
                    .blur(radius: 14)
                    .scaleEffect(glowScale)
            )
        }
        .opacity(isActive ? (pulse ? 0.6 : 1) : 1)
        .buttonStyle(.plain)
        .accessibilityLabel(LocalizedStringKey("SOS_BUTTON_LABEL"))
        .onAppear {
            updatePulse(isActive: isActive)
        }
        .onChange(of: isActive) { _, newValue in
            updatePulse(isActive: newValue)
        }
    }

    private func updatePulse(isActive: Bool) {
        if isActive {
            pulse = false
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                pulse = true
            }
        } else {
            withAnimation(.easeOut(duration: 0.2)) {
                pulse = false
            }
        }
    }
}

private struct RescueFloatingButton: View {
    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "cross.case.fill")
                .font(.system(size: 16, weight: .semibold))
            Text(LocalizedStringKey("RESCUE_BUTTON_LABEL"))
                .font(.system(.headline, design: .rounded).weight(.semibold))
        }
        .foregroundColor(Color.appPrimary)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .background(
            Capsule().fill(Color.appSurfaceElevated)
        )
        .overlay(
            Capsule().stroke(Color.appBorder, lineWidth: 1)
        )
        .accessibilityLabel(LocalizedStringKey("RESCUE_BUTTON_LABEL"))
    }
}
