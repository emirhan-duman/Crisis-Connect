//
//  AuthorityChannelsListView.swift
//  Crisis Connect
//
//  The kurum directory — iOS counterpart of Android's AuthorityContactPickerScreen (reached from
//  New Chat → "Add from agency" and from notifications). One list: the agency's own encrypted
//  broadcast channel on top, cross-panel (hierarchy) peers in Android's direction sections
//  (HQ / peer / sub-units, names+photos enriched from the agency roster), then the own-agency
//  roster split management / field teams — tapping those adds a normal 1:1 internet contact and
//  opens the citizen chat, exactly like Android's picker.
//

import Combine
import CoreLocation
import CryptoKit
import FirebaseAuth
import FirebaseFirestore
import FirebaseFunctions
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Notification deep-link target: resolves a channelId + peerUid into the peer's thread and pushes
/// straight in (Android parity — a kurum push opens that exact conversation, not the directory).
/// Shows a spinner while resolving; if the peer is gone, falls back to the full directory.
struct AuthorityThreadDeepLinkView: View {
    let channelId: String
    let peerUid: String

    @State private var resolved: (channel: HierarchyChannel, peer: HierarchyPeer, key: HierarchyChannelKey?)?
    @State private var failed = false

    var body: some View {
        Group {
            if let resolved {
                HierarchyThreadView(
                    channel: resolved.channel,
                    peer: resolved.peer,
                    preloadedKey: resolved.key
                )
            } else if failed {
                AuthorityChannelsListView()
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.appBackground)
            }
        }
        .task {
            if let target = await AuthorityChannelsListStore.resolveThreadTarget(
                channelId: channelId, peerUid: peerUid
            ) {
                resolved = target
            } else {
                failed = true
            }
        }
    }
}

/// Kurum avatar: the roster/panel photo when one is published, initials circle otherwise —
/// Android's ContactAvatar for the authority screens (ChatAvatarCircleView only loads local files).
struct AuthorityAvatarView: View {
    let name: String
    let uid: String
    let photoUrl: String?
    let size: CGFloat
    var borderColor: Color = .clear
    var borderWidth: CGFloat = 0

    var body: some View {
        if let photoUrl, photoUrl.hasPrefix("http"), let url = URL(string: photoUrl) {
            AsyncImage(url: url) { phase in
                if let image = phase.image {
                    image.resizable().scaledToFill()
                } else {
                    initialsCircle
                }
            }
            .frame(width: size, height: size)
            .clipShape(Circle())
            .overlay(Circle().stroke(borderColor, lineWidth: borderWidth))
        } else {
            initialsCircle
        }
    }

    private var initialsCircle: some View {
        ChatAvatarCircleView(
            avatarImageRelativePath: nil,
            initials: AvatarGenerator.initials(from: name),
            avatarHue: AvatarGenerator.hue(for: BroadcastSessionId.fromRawIdentifier(uid)),
            size: size,
            borderColor: borderColor,
            borderWidth: borderWidth
        )
    }
}

@MainActor
final class AuthorityChannelsListStore: ObservableObject {
    struct PeerRow: Identifiable {
        var id: String { channel.channelId + ":" + peer.uid }
        let channel: HierarchyChannel
        let peer: HierarchyPeer
        let preview: String?
        let previewAt: Date?
        /// Last incoming message is newer than my read cursor → home-row badge (Android parity).
        var unread: Bool = false
        /// The peer's hidden Bluetooth-bridge contact has a live link right now.
        var bluetoothLinked: Bool = false
    }

    struct PanelGroup: Identifiable {
        var id: String { panelName }
        /// Localization KEY of the section title (Android picker sections: HQ/peer/sub units).
        let panelName: String
        let rows: [PeerRow]
    }

    struct RosterMember: Identifiable {
        var id: String { uid }
        let uid: String
        let name: String
        let role: String
        let phone: String
        let photoUrl: String?
        /// Android picker split: admin/authority → management, everyone else → field teams.
        var isManagement: Bool {
            let key = role.lowercased()
                .replacingOccurrences(of: "-", with: "")
                .replacingOccurrences(of: "_", with: "")
            return key == "admin" || key == "authority"
        }
    }

    @Published private(set) var groups: [PanelGroup] = []
    @Published private(set) var managementMembers: [RosterMember] = []
    @Published private(set) var fieldMembers: [RosterMember] = []
    @Published private(set) var isLoading = true
    /// The cross-panel (hierarchy) channels fetch failed while the own-agency roster loaded — drives
    /// the retry banner (Android's crossPanelFailed), so the roster still shows on its own.
    @Published private(set) var crossPanelFailed = false

    private let client = HierarchyMessagingClient()
    private var cachedKeys: [String: HierarchyChannelKey] = [:]
    private var hasStarted = false

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await load() }
    }

    func refresh() async {
        await load()
    }

    func channelKey(for channelId: String) -> HierarchyChannelKey? {
        cachedKeys[channelId]
    }

    /// Same liveness check the thread's blue band uses (host or central side of the hidden
    /// bridge contact is connected), evaluated once per row at list load.
    @MainActor
    private static func isBridgeLinked(myUid: String, peerUid: String) -> Bool {
        guard !myUid.isEmpty else { return false }
        let session = InternetConversation.pairId(myUid, peerUid)
        guard let contact = ContactStore.shared.contacts.first(where: {
            $0.sessionCode.caseInsensitiveCompare(session) == .orderedSame
        }), !contact.aesKeyBase64.isEmpty else { return false }
        return ContactBroadcastManager.shared.isSessionConnected(contact.id)
            || P2pGattChatManager.shared(sessionId: contact.id).isReady()
    }

    private func load() async {
        // Offline-first (Android Room parity): surface the last known kurum conversations
        // immediately on a cold start; the network refresh below replaces them when it lands.
        if groups.isEmpty, let cached = Self.readCache() {
            groups = cached
            isLoading = false
        }
        let myUid = Auth.auth().currentUser?.uid ?? ""

        // Roster and channels load independently (Android parity): the own-agency roster must still
        // populate the management / field sections even when the cross-panel hierarchy fetch fails,
        // and a failed hierarchy fetch surfaces a retry banner instead of dropping the roster.
        async let rosterFetch = Self.fetchRoster()
        async let channelsFetch = fetchChannelsCatching()
        let roster = await rosterFetch
        let channels = await channelsFetch

        // Own-agency people come straight from the roster — applied regardless of the channel fetch.
        managementMembers = roster
            .filter { $0.uid != myUid && $0.isManagement }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        fieldMembers = roster
            .filter { $0.uid != myUid && !$0.isManagement }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        if let channels {
            crossPanelFailed = false
            // Hidden Bluetooth-bridge contacts for peers whose number the backend released
            // (fire-and-forget; failures only cost the offline capability).
            Task { await AuthorityBridgeContacts.sync(channels: channels) }
            let rosterByUid = Dictionary(roster.map { ($0.uid, $0) }, uniquingKeysWith: { first, _ in first })

            // Android picker sections: channels grouped by hierarchy DIRECTION, not by panel.
            var rowsByDirection: [String: [PeerRow]] = [:]
            for channel in channels {
                // One key per channel covers every preview decrypt; missing entitlement (or a
                // channel nobody has messaged in) simply renders rows without previews.
                var key = cachedKeys[channel.channelId]
                if key == nil {
                    key = try? await client.fetchChannelKey(channelId: channel.channelId)
                }
                if let key { cachedKeys[channel.channelId] = key }

                for peer in channel.peers {
                    // The hierarchy payload often carries the web account's name (an email);
                    // the agency roster knows the person's real name + photo — prefer those.
                    let enriched = Self.enrich(peer, from: rosterByUid[peer.uid])
                    let latest = await client.latestMessageWith(
                        channelId: channel.channelId,
                        peerUid: peer.uid,
                        channelKey: key
                    )
                    // Final display name: a real name from the roster/hierarchy wins; if that's only
                    // a login email, the name the peer's own app published on their messages wins;
                    // only then a prettified email (local-part) so a raw address never shows.
                    let named = Self.withDisplayName(
                        enriched, messageSenderName: latest?.peerName
                    )
                    // Unread: the last message is INCOMING and newer than my read cursor.
                    var unread = false
                    if let latest, latest.senderUid == peer.uid, !myUid.isEmpty {
                        let cursor = await client.myReadCursorAt(
                            channelId: channel.channelId, myUid: myUid, peerUid: peer.uid
                        )
                        unread = cursor.map { latest.at > $0 } ?? true
                    }
                    let direction: String
                    switch channel.group {
                    case "up": direction = "up"
                    case "sibling": direction = "sibling"
                    default: direction = "down"
                    }
                    rowsByDirection[direction, default: []].append(
                        PeerRow(
                            channel: channel,
                            peer: named,
                            preview: latest.map { Self.previewText(text: $0.text, attachmentKind: $0.attachmentKind) },
                            previewAt: latest?.at,
                            unread: unread,
                            bluetoothLinked: Self.isBridgeLinked(myUid: myUid, peerUid: peer.uid)
                        )
                    )
                }
            }
            var nextGroups: [PanelGroup] = []
            let sections = [
                ("up", "AUTHORITY_PICKER_HQ_UNITS"),
                ("sibling", "AUTHORITY_PICKER_SIBLING_UNITS"),
                ("down", "AUTHORITY_PICKER_SUB_UNITS"),
            ]
            for (direction, titleKey) in sections {
                guard var rows = rowsByDirection[direction], !rows.isEmpty else { continue }
                // Messaged conversations first (newest on top), then the untouched roster.
                rows.sort { lhs, rhs in
                    switch (lhs.previewAt, rhs.previewAt) {
                    case let (l?, r?): return l > r
                    case (_?, nil): return true
                    case (nil, _?): return false
                    case (nil, nil):
                        return lhs.peer.name.localizedCaseInsensitiveCompare(rhs.peer.name) == .orderedAscending
                    }
                }
                nextGroups.append(PanelGroup(panelName: titleKey, rows: rows))
            }
            groups = nextGroups
            Self.writeCache(nextGroups)
        } else {
            // Cross-panel hierarchy unavailable: keep whatever channel rows are already shown (cache
            // or a previous load) and surface a retry banner — the roster still loaded above.
            crossPanelFailed = true
        }
        isLoading = false
    }

    /// Cross-panel channels, or nil when the hierarchy fetch fails — so the roster can still load
    /// independently and only the cross-panel banner surfaces the failure (Android parity).
    private func fetchChannelsCatching() async -> [HierarchyChannel]? {
        do {
            return try await client.fetchChannels()
        } catch {
            NSLog("AuthorityChannelsList: channels load failed: %@", String(describing: error))
            return nil
        }
    }

    // MARK: - Offline row cache (Android Room parity)

    private struct CachedSection: Codable {
        let titleKey: String
        let rows: [CachedRow]
    }

    private struct CachedRow: Codable {
        let channel: HierarchyChannel
        let peer: HierarchyPeer
        let preview: String?
        let previewAt: Date?
        let unread: Bool
    }

    private static var cacheURL: URL? {
        guard let dir = try? FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true
        ) else { return nil }
        return dir.appendingPathComponent("authority_channel_rows.json")
    }

    private static func readCache() -> [PanelGroup]? {
        guard let url = cacheURL, let data = try? Data(contentsOf: url),
              let sections = try? JSONDecoder().decode([CachedSection].self, from: data),
              !sections.isEmpty else { return nil }
        return sections.map { section in
            PanelGroup(panelName: section.titleKey, rows: section.rows.map { row in
                PeerRow(
                    channel: row.channel,
                    peer: row.peer,
                    preview: row.preview,
                    previewAt: row.previewAt,
                    unread: row.unread,
                    bluetoothLinked: false
                )
            })
        }
    }

    private static func writeCache(_ groups: [PanelGroup]) {
        guard let url = cacheURL else { return }
        let sections = groups.map { group in
            CachedSection(titleKey: group.panelName, rows: group.rows.map { row in
                CachedRow(
                    channel: row.channel,
                    peer: row.peer,
                    preview: row.preview,
                    previewAt: row.previewAt,
                    unread: row.unread
                )
            })
        }
        guard let data = try? JSONEncoder().encode(sections) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// Resolves a single channel + peer for a notification deep-link: finds the channel/peer, fetches
    /// its key, and applies the same name resolution (roster → message senderName → email prettify)
    /// the list uses, so the thread opens with a real title. nil if the peer is no longer reachable.
    static func resolveThreadTarget(
        channelId: String, peerUid: String
    ) async -> (channel: HierarchyChannel, peer: HierarchyPeer, key: HierarchyChannelKey?)? {
        let client = HierarchyMessagingClient()
        guard let channels = try? await client.fetchChannels(),
              let channel = channels.first(where: { $0.channelId == channelId }),
              let peer = channel.peers.first(where: { $0.uid == peerUid }) else { return nil }
        let key = try? await client.fetchChannelKey(channelId: channelId)
        let roster = await fetchRoster()
        let enriched = enrich(peer, from: roster.first(where: { $0.uid == peerUid }))
        let latest = await client.latestMessageWith(channelId: channelId, peerUid: peerUid, channelKey: key)
        let named = withDisplayName(enriched, messageSenderName: latest?.peerName)
        return (channel, named, key)
    }

    /// Home/directory preview label (Android's buildChannelPreviewUi): a call log becomes
    /// "Missed/Video/Voice call", a shared location "Shared location", an attachment-only message
    /// its kind — never raw control payloads. Also used for the kurum push notification body.
    static func previewText(text: String, attachmentKind: String) -> String {
        if text.hasPrefix("\u{01}") {
            if let call = HierarchyThreadView.parseCallLog(text) {
                if !call.answered { return NSLocalizedString("AUTHORITY_CHANNEL_CALL_MISSED", comment: "") }
                if call.video { return NSLocalizedString("AUTHORITY_CHANNEL_CALL_VIDEO", comment: "") }
            }
            return NSLocalizedString("AUTHORITY_CHANNEL_CALL_AUDIO", comment: "")
        }
        if HierarchyThreadView.parseSharedLocation(text) != nil {
            return NSLocalizedString("AUTHORITY_SHARED_LOCATION", comment: "")
        }
        if text.isEmpty {
            switch attachmentKind {
            case "audio": return NSLocalizedString("CONVERSATION_PREVIEW_VOICE_MESSAGE", comment: "")
            case "image": return NSLocalizedString("CONVERSATION_PREVIEW_PHOTO_MESSAGE", comment: "")
            case "file": return NSLocalizedString("CHAT_FILE_PREVIEW_LABEL", comment: "")
            default: return "📎"
            }
        }
        return text
    }

    /// Prefers the roster's resolved name/photo/phone over the raw hierarchy peer fields. When both
    /// carry a name, a non-email one wins (a login email is the backend's last-resort fallback).
    private static func enrich(_ peer: HierarchyPeer, from member: RosterMember?) -> HierarchyPeer {
        guard let member else { return peer }
        return HierarchyPeer(
            uid: peer.uid,
            name: preferredName(peer.name, member.name),
            role: peer.role ?? (member.role.isEmpty ? nil : member.role),
            photoUrl: peer.photoUrl ?? member.photoUrl,
            agency: peer.agency,
            phone: peer.phone ?? (member.phone.isEmpty ? nil : member.phone)
        )
    }

    /// Picks the better of two candidate names: a real name beats an email, non-empty beats empty.
    private static func preferredName(_ a: String, _ b: String) -> String {
        let aOK = !a.isEmpty && !looksLikeEmail(a)
        let bOK = !b.isEmpty && !looksLikeEmail(b)
        if aOK { return a }
        if bOK { return b }
        return a.isEmpty ? b : a
    }

    /// Resolves the name actually shown: a real roster/hierarchy name wins; if that's only a login
    /// email, the name the peer's own app stamped on their messages wins; else the email's local part.
    private static func withDisplayName(_ peer: HierarchyPeer, messageSenderName: String?) -> HierarchyPeer {
        guard looksLikeEmail(peer.name) || peer.name.isEmpty else { return peer }
        let resolved: String
        if let sender = messageSenderName, !sender.isEmpty, !looksLikeEmail(sender) {
            resolved = sender
        } else if !peer.name.isEmpty {
            resolved = prettifyEmail(peer.name)
        } else {
            resolved = peer.name
        }
        return HierarchyPeer(
            uid: peer.uid, name: resolved, role: peer.role,
            photoUrl: peer.photoUrl, agency: peer.agency, phone: peer.phone
        )
    }

    private static func looksLikeEmail(_ value: String) -> Bool {
        let v = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let at = v.firstIndex(of: "@"), at != v.startIndex else { return false }
        return v[v.index(after: at)...].contains(".") && !v.contains(" ")
    }

    /// "demo@crisisconnect.network" → "Demo": local part, separators to spaces, capitalized.
    private static func prettifyEmail(_ email: String) -> String {
        let local = email.split(separator: "@").first.map(String.init) ?? email
        let words = local
            .replacingOccurrences(of: ".", with: " ")
            .replacingOccurrences(of: "_", with: " ")
            .replacingOccurrences(of: "-", with: " ")
            .split(separator: " ")
            .map { $0.prefix(1).uppercased() + $0.dropFirst() }
        return words.isEmpty ? local : words.joined(separator: " ")
    }

    /// Own-agency roster via the same `listAuthorityRoster` callable Android + web use.
    private static func fetchRoster() async -> [RosterMember] {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return [] }
        let doc = try? await Firestore.firestore().document("users/\(user.uid)").getDocument()
        let slug = ((doc?.get("agencySlug") as? String) ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !slug.isEmpty else { return [] }
        do {
            let result = try await Functions.functions(region: InternetMessagingClient.region)
                .httpsCallable("listAuthorityRoster")
                .callAsync(["agencySlug": slug])
            guard let data = result.data as? [String: Any],
                  let members = data["members"] as? [[String: Any]] else { return [] }
            return members.compactMap { m in
                guard let uid = (m["uid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                      !uid.isEmpty else { return nil }
                let rawName = (m["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return RosterMember(
                    uid: uid,
                    // A login email would show as a raw address — prettify to a readable name.
                    name: looksLikeEmail(rawName) ? prettifyEmail(rawName) : rawName,
                    role: m["role"] as? String ?? "",
                    phone: m["phone"] as? String ?? "",
                    photoUrl: (m["photoUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                )
            }
        } catch {
            NSLog("AuthorityChannelsList: roster fetch failed: %@", String(describing: error))
            return []
        }
    }
}

struct AuthorityChannelsListView: View {
    @StateObject private var store = AuthorityChannelsListStore()
    @State private var searchText = ""
    @State private var addingUid: String?
    @State private var openedSession: OpenedRosterSession?

    private struct OpenedRosterSession: Identifiable, Hashable { let id: UUID }

    private var trimmedQuery: String {
        searchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Android picker parity: the search box filters peers by name (and panel), empty groups drop out.
    private var filteredGroups: [AuthorityChannelsListStore.PanelGroup] {
        let query = trimmedQuery
        guard !query.isEmpty else { return store.groups }
        return store.groups.compactMap { group in
            let rows = group.rows.filter {
                $0.peer.name.localizedCaseInsensitiveContains(query)
                    || ($0.peer.agency ?? $0.channel.peerPanelName).localizedCaseInsensitiveContains(query)
            }
            return rows.isEmpty
                ? nil
                : AuthorityChannelsListStore.PanelGroup(panelName: group.panelName, rows: rows)
        }
    }

    private func filteredMembers(
        _ members: [AuthorityChannelsListStore.RosterMember]
    ) -> [AuthorityChannelsListStore.RosterMember] {
        let query = trimmedQuery
        guard !query.isEmpty else { return members }
        return members.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || $0.role.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        List {
            // Cross-panel channels failed to load but the own-agency roster did — warn + retry
            // (Android's CrossPanelWarningBanner), without hiding the roster that did load.
            if store.crossPanelFailed && !store.isLoading {
                Section {
                    crossPanelWarningBanner
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            if store.isLoading {
                Section {
                    HStack {
                        Spacer()
                        ProgressView()
                        Spacer()
                    }
                }
            } else if filteredGroups.isEmpty
                && filteredMembers(store.managementMembers).isEmpty
                && filteredMembers(store.fieldMembers).isEmpty {
                Section {
                    Text("AUTHORITY_HIERARCHY_EMPTY")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                }
            } else {
                ForEach(filteredGroups) { group in
                    Section(header: Text(LocalizedStringKey(group.panelName))) {
                        ForEach(group.rows) { row in
                            NavigationLink(destination: LazyNavigationDestination {
                                HierarchyThreadView(
                                    channel: row.channel,
                                    peer: row.peer,
                                    preloadedKey: store.channelKey(for: row.channel.channelId)
                                )
                            }) {
                                peerRow(row)
                            }
                        }
                    }
                }
                // Own-agency people (Android picker's management/field sections): tapping adds
                // them as a normal 1:1 internet contact and opens the citizen chat, like Android.
                let management = filteredMembers(store.managementMembers)
                if !management.isEmpty {
                    Section(header: Text("AUTHORITY_PICKER_MANAGEMENT")) {
                        ForEach(management) { member in rosterRow(member) }
                    }
                }
                let field = filteredMembers(store.fieldMembers)
                if !field.isEmpty {
                    Section(header: Text("AUTHORITY_PICKER_FIELD_TEAMS")) {
                        ForEach(field) { member in rosterRow(member) }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(Color.appBackground)
        .navigationTitle(LocalizedStringKey("AUTHORITY_CHANNELS_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .searchable(text: $searchText)
        .navigationDestination(item: $openedSession) { session in
            SOSChatDetailScreen(sessionId: session.id)
        }
        .onAppear {
            store.start()
            SOSNotificationCenter.clearMessageNotification(route: .authorityChannels)
        }
        .refreshable { await store.refresh() }
    }

    /// Android's CrossPanelWarningBanner: a danger-tinted strip with a Retry that reloads the
    /// hierarchy channels, shown when only the cross-panel fetch failed.
    private var crossPanelWarningBanner: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(Color.appDanger)
            Text("AUTHORITY_PICKER_CROSS_PANEL_WARNING")
                .font(.caption)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            Button {
                Task { await store.refresh() }
            } label: {
                Text("AUTHORITY_PICKER_RETRY")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.appPrimary)
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.appDanger.opacity(0.12))
        )
        .padding(.horizontal, AppTheme.screenPadding)
        .padding(.vertical, 4)
    }

    private func peerRow(_ row: AuthorityChannelsListStore.PeerRow) -> some View {
        HStack(spacing: 12) {
            AuthorityAvatarView(
                name: row.peer.name,
                uid: row.peer.uid,
                photoUrl: row.peer.photoUrl,
                size: 42
            )
            VStack(alignment: .leading, spacing: 2) {
                Text(row.peer.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                if let preview = row.preview {
                    Text(preview)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                        .lineLimit(1)
                } else {
                    // Android picker subtitle: the peer's agency, else their panel's name.
                    let subtitle = row.peer.agency ?? row.channel.peerPanelName
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                            .lineLimit(1)
                    }
                }
            }
            Spacer()
            if let previewAt = row.previewAt {
                Text(previewAt, style: .time)
                    .font(.caption2)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .padding(.vertical, 2)
    }

    private func rosterRow(_ member: AuthorityChannelsListStore.RosterMember) -> some View {
        Button {
            openRosterChat(member)
        } label: {
            HStack(spacing: 12) {
                AuthorityAvatarView(
                    name: member.name,
                    uid: member.uid,
                    photoUrl: member.photoUrl,
                    size: 42
                )
                VStack(alignment: .leading, spacing: 2) {
                    Text(member.name.isEmpty ? member.uid : member.name)
                        .font(.body.weight(.medium))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                    if let label = HierarchyThreadView.roleLabel(member.role) {
                        Text(label)
                            .font(.caption)
                            .foregroundStyle(Color.appTextSecondary)
                            .lineLimit(1)
                    }
                }
                Spacer()
                if addingUid == member.uid {
                    ProgressView()
                        .controlSize(.small)
                } else {
                    Image(systemName: "chevron.right")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
            .padding(.vertical, 2)
        }
        .disabled(addingUid != nil)
    }

    /// Android's rosterClient.addContact: resolve the member's identity key, create/reuse the 1:1
    /// internet contact (keeping the phone for the offline Bluetooth bootstrap), open the chat.
    private func openRosterChat(_ member: AuthorityChannelsListStore.RosterMember) {
        guard addingUid == nil else { return }
        addingUid = member.uid
        Task { @MainActor in
            defer { addingUid = nil }
            guard let myUid = Auth.auth().currentUser?.uid else { return }
            guard let identity = try? await InternetMessagingClient().lookupIdentityKey(uid: member.uid),
                  !identity.publicKeyBase64.isEmpty else { return }
            guard let contact = ContactStore.shared.applyInternetIdentity(
                conversationSessionCode: InternetConversation.pairId(myUid, member.uid),
                peerUid: member.uid,
                peerPublicKey: identity.publicKeyBase64,
                displayName: member.name.isEmpty ? nil : member.name,
                peerPhotoUrl: member.photoUrl ?? (identity.photoUrl.isEmpty ? nil : identity.photoUrl),
                peerPhone: member.phone.isEmpty ? nil : member.phone,
                analyticsSource: "authority_roster"
            ) else { return }
            SOSChatStore.shared.ensureSession(id: contact.id, displayName: contact.name)
            openedSession = OpenedRosterSession(id: contact.id)
        }
    }
}

// MARK: - Hierarchy 1:1 thread

@MainActor
final class HierarchyThreadStore: ObservableObject {
    enum State: Equatable { case loading, ready, error }

    @Published private(set) var state: State = .loading
    @Published private(set) var messages: [HierarchyMessage] = []
    @Published private(set) var peerReadAt: Date?
    @Published private(set) var peerTyping = false
    /// A live Bluetooth link to this peer's hidden bridge contact (drives the badge + send routing).
    @Published private(set) var bluetoothLinked = false
    /// Bluetooth-carried voice notes/images by merged-row id ("bt:<uuid>") — rendered from local files.
    @Published private(set) var bridgeMedia: [String: SOSChatMessage] = [:]

    private var cloudMessages: [HierarchyMessage] = []
    // Exposed so the thread's call button can route an offline audio call over the live
    // Bluetooth bridge (Android's placeCall does the same with its bridge contact).
    private(set) var bridgeContact: ContactRecord?
    private var bridgeMonitorTask: Task<Void, Never>?

    private let client = HierarchyMessagingClient()
    private let channel: HierarchyChannel
    private let peer: HierarchyPeer
    private var channelKey: HierarchyChannelKey?
    private var registration: ListenerRegistration?
    private var cursorRegistration: ListenerRegistration?
    private var typingRegistration: ListenerRegistration?
    private var typingResetTask: Task<Void, Never>?
    private var lastTypingSentAt = Date.distantPast
    private var hasStarted = false

    var myUid: String? { Auth.auth().currentUser?.uid }

    /// Key + AAD for attachment decrypt in bubbles (available once the channel key loaded).
    var mediaKey: SymmetricKey? { channelKey?.key }
    var mediaAad: String? { channelKey?.channelId }

    init(channel: HierarchyChannel, peer: HierarchyPeer, preloadedKey: HierarchyChannelKey?) {
        self.channel = channel
        self.peer = peer
        self.channelKey = preloadedKey
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await bootstrap() }
        // Offline Bluetooth bridge: while the thread is open, silently try to gain a BT link for
        // this peer's hidden bridge contact (number-keyed SPAKE2; the peer confirms once).
        if let myUid {
            let bridgeSession = InternetConversation.pairId(myUid, peer.uid)
            let bridge = ContactStore.shared.contacts.first {
                $0.sessionCode.caseInsensitiveCompare(bridgeSession) == .orderedSame
            }
            if let bridge, NearbyAutoLink.isEligible(bridge) {
                NearbyAutoLink.shared.tryEstablish(contact: bridge)
            }
        }
        startBridgeMonitoring()
    }

    /// Polls the bridge contact's link + Bluetooth-carried rows while the thread is open. A 3s
    /// cadence is plenty for a badge and keeps us off the transport internals.
    private func startBridgeMonitoring() {
        bridgeMonitorTask?.cancel()
        bridgeMonitorTask = Task { @MainActor [weak self] in
            while let self, !Task.isCancelled {
                self.refreshBridge()
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    @MainActor private func refreshBridge() {
        guard let myUid else { return }
        let session = InternetConversation.pairId(myUid, peer.uid)
        let contact = ContactStore.shared.contacts.first {
            $0.sessionCode.caseInsensitiveCompare(session) == .orderedSame
        }
        bridgeContact = contact
        guard let contact, !contact.aesKeyBase64.isEmpty else {
            if bluetoothLinked { bluetoothLinked = false }
            publishMerged()
            return
        }
        let hostConnected = ContactBroadcastManager.shared.isSessionConnected(contact.id)
        let centralReady = P2pGattChatManager.shared(sessionId: contact.id).isReady()
        let linked = hostConnected || centralReady
        if linked != bluetoothLinked { bluetoothLinked = linked }
        publishMerged()
    }

    /// One time-sorted timeline: cloud channel messages + Bluetooth-carried plain-text rows of
    /// the bridge contact. A backfilled doc carries the Bluetooth copy's uuid as clientUuid, so
    /// that bt: row is dropped and the message renders once (the copy the web also sees).
    @MainActor private func publishMerged() {
        guard let myUid else { return }
        let cloud = cloudMessages
        var backfilled = Set<String>()
        for message in cloud where !message.clientUuid.isEmpty {
            backfilled.insert(message.clientUuid)
        }
        var bridgeRows: [HierarchyMessage] = []
        var mediaById: [String: SOSChatMessage] = [:]
        if let contact = bridgeContact, !contact.aesKeyBase64.isEmpty {
            for row in SOSChatStore.shared.messages(for: contact.id) {
                let uuid = row.transportMessageId ?? row.id.uuidString
                guard !backfilled.contains(uuid) else { continue }
                let rowText: String
                switch row.kind {
                case .text:
                    let text = row.text.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { continue }
                    // Machine payloads stay out of the timeline — except shared locations (CC_LOC)
                    // and document previews (CC_FILE), which render as their own bubbles on both
                    // transports (Android parity). Dropping CC_FILE here was why a document sent
                    // over the bridge never appeared in the thread at all.
                    if text.hasPrefix("CC_"),
                       HierarchyThreadView.parseSharedLocation(text) == nil,
                       P2pSharedTransferSupport.parseFilePreviewMessage(text) == nil { continue }
                    rowText = text
                case .audio where row.audioRelativePath != nil,
                     .image where row.imageRelativePath != nil:
                    mediaById["bt:" + uuid] = row
                    rowText = ""
                default:
                    continue
                }
                bridgeRows.append(HierarchyMessage(
                    id: "bt:" + uuid,
                    senderUid: row.isLocal ? myUid : peer.uid,
                    senderName: "",
                    recipientUid: row.isLocal ? peer.uid : myUid,
                    recipientName: "",
                    text: rowText,
                    at: row.timestamp,
                    attachments: []
                ))
            }
        }
        if mediaById != bridgeMedia { bridgeMedia = mediaById }
        let merged = (cloud + bridgeRows).sorted { $0.at < $1.at }
        if merged != messages { messages = merged }
    }

    func stop() {
        setTyping(false)
        registration?.remove()
        registration = nil
        cursorRegistration?.remove()
        cursorRegistration = nil
        typingRegistration?.remove()
        typingRegistration = nil
        bridgeMonitorTask?.cancel()
        bridgeMonitorTask = nil
        hasStarted = false
    }

    /// The thread is on screen and messages are visible — advance my read cursor.
    func markRead() {
        guard let myUid = Auth.auth().currentUser?.uid else { return }
        client.writeReadCursor(
            channelId: channel.channelId, myUid: myUid, peerUid: peer.uid, at: Date()
        )
    }

    /// Composer keystrokes → typing=true (throttled), auto-cleared after 4s of silence.
    func onComposerTyping(_ text: String) {
        guard let myUid = Auth.auth().currentUser?.uid,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let now = Date()
        if now.timeIntervalSince(lastTypingSentAt) >= 3 {
            lastTypingSentAt = now
            client.setTyping(channelId: channel.channelId, myUid: myUid, peerUid: peer.uid, typing: true)
        }
        typingResetTask?.cancel()
        typingResetTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            self?.setTyping(false)
        }
    }

    func setTyping(_ typing: Bool) {
        guard let myUid = Auth.auth().currentUser?.uid else { return }
        if !typing { typingResetTask?.cancel() }
        client.setTyping(channelId: channel.channelId, myUid: myUid, peerUid: peer.uid, typing: typing)
    }

    private func bootstrap() async {
        guard let myUid = Auth.auth().currentUser?.uid else {
            state = .error
            return
        }
        do {
            let key: HierarchyChannelKey
            if let channelKey {
                key = channelKey
            } else {
                key = try await client.fetchChannelKey(channelId: channel.channelId)
                channelKey = key
            }
            cursorRegistration = client.listenReadCursor(
                channelId: channel.channelId, myUid: myUid, peerUid: peer.uid
            ) { [weak self] at in
                Task { @MainActor [weak self] in self?.peerReadAt = at }
            }
            typingRegistration = client.listenTyping(
                channelId: channel.channelId, myUid: myUid, peerUid: peer.uid
            ) { [weak self] typing in
                Task { @MainActor [weak self] in self?.peerTyping = typing }
            }
            registration = client.listenConversation(
                channelKey: key,
                myUid: myUid,
                peerUid: peer.uid
            ) { [weak self] messages in
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    self.cloudMessages = messages
                    self.publishMerged()
                    if self.state != .ready { self.state = .ready }
                }
            }
            state = .ready
            Task { [weak self] in await self?.backfillBluetoothMessages(key: key, myUid: myUid) }
        } catch {
            NSLog("HierarchyThreadStore: bootstrap failed: %@", String(describing: error))
            state = .error
        }
    }

    func send(_ text: String, attachments: [PendingChannelAttachment] = []) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || !attachments.isEmpty else { return }
        // Offline Bluetooth path (Android parity): plain text and shared-location payloads ride the
        // bridge as text, and a single voice/image/file attachment streams over the same citizen
        // BLE stack. Cloud stays the transport of record whenever the internet can carry the
        // message (web parity); Bluetooth takes over exactly when it can't and the bridge link is up.
        if bluetoothLinked, !InternetChatTransport.shared.isAvailable(), let bridge = bridgeContact {
            let isLocationPayload = HierarchyThreadView.parseSharedLocation(trimmed) != nil
            if attachments.isEmpty, !trimmed.isEmpty, (!trimmed.hasPrefix("CC_") || isLocationPayload) {
                AppAnalytics.messageSent(kind: "text", transport: "bluetooth", chat: "authority_channel")
                sendViaBluetoothBridge(trimmed, bridge: bridge)
                return
            }
            if trimmed.isEmpty, attachments.count == 1, let attachment = attachments.first {
                if attachment.mime.hasPrefix("audio/") {
                    AppAnalytics.messageSent(kind: "voice", transport: "bluetooth", chat: "authority_channel")
                    sendVoiceViaBluetoothBridge(attachment, bridge: bridge)
                } else if attachment.mime.hasPrefix("image/") {
                    AppAnalytics.messageSent(kind: "image", transport: "bluetooth", chat: "authority_channel")
                    sendImageViaBluetoothBridge(attachment, bridge: bridge)
                } else {
                    AppAnalytics.messageSent(kind: "file", transport: "bluetooth", chat: "authority_channel")
                    sendFileViaBluetoothBridge(attachment, bridge: bridge)
                }
                return
            }
        }
        guard let channelKey else { return }
        AppAnalytics.messageSent(
            kind: attachments.isEmpty ? "text"
                : attachments.contains(where: { $0.mime.hasPrefix("audio/") }) ? "voice"
                : attachments.contains(where: { $0.mime.hasPrefix("image/") }) ? "image" : "file",
            transport: "internet",
            chat: "authority_channel"
        )
        // Real name (profile → Firebase Auth displayName), so the peer sees us by name, not "iOS".
        let senderName = ProfileMetadataStore.preferredDisplayName() ?? "iOS"
        let peer = peer
        Task {
            do {
                try await client.send(
                    channelKey: channelKey,
                    senderName: senderName,
                    recipientUid: peer.uid,
                    recipientName: peer.name,
                    text: trimmed,
                    attachments: attachments
                )
            } catch {
                NSLog("HierarchyThreadStore: send failed: %@", String(describing: error))
            }
        }
    }

    /// Persists the row first (that's what the merged timeline renders) then pushes it over
    /// whichever side of the P2P BLE link is live — same transports citizen chat uses.
    private func sendViaBluetoothBridge(_ text: String, bridge: ContactRecord) {
        let transportMessageId = "ios-\(UUID().uuidString.lowercased())"
        _ = SOSChatStore.shared.appendLocalMessage(
            sessionId: bridge.id,
            text: text,
            status: .sent,
            transportMessageId: transportMessageId
        )
        let sessionId = bridge.id
        if ContactBroadcastManager.shared.isSessionConnected(sessionId) {
            DispatchQueue.global(qos: .userInitiated).async {
                _ = ContactBroadcastManager.shared.sendMessage(
                    text, transportMessageId: transportMessageId, sessionId: sessionId
                )
            }
        } else {
            let gatt = P2pGattChatManager.shared(sessionId: sessionId)
            DispatchQueue.global(qos: .userInitiated).async {
                _ = gatt.sendMessage(text, messageId: transportMessageId)
            }
        }
        publishMerged()
    }

    /// Persists the recording locally (that's what the merged timeline plays) then streams it over
    /// whichever side of the P2P BLE link is live — the same citizen transports the text path uses.
    private func sendVoiceViaBluetoothBridge(_ attachment: PendingChannelAttachment, bridge: ContactRecord) {
        let transportMessageId = "ios-\(UUID().uuidString.lowercased())"
        let durationMillis = attachment.durationSec.map { max(0, $0) * 1000 }
        guard let audioRelativePath = SOSChatStore.persistVoiceData(
            attachment.data, messageId: transportMessageId, mimeType: attachment.mime
        ) else { return }
        _ = SOSChatStore.shared.appendLocalAudioMessage(
            sessionId: bridge.id,
            audioRelativePath: audioRelativePath,
            durationMillis: durationMillis,
            status: .sent,
            transportMessageId: transportMessageId
        )
        let sessionId = bridge.id
        let mime = attachment.mime
        if ContactBroadcastManager.shared.isSessionConnected(sessionId) {
            DispatchQueue.global(qos: .userInitiated).async {
                _ = ContactBroadcastManager.shared.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mime,
                    durationMillis: durationMillis ?? 0,
                    messageId: transportMessageId,
                    sessionId: sessionId
                )
            }
        } else {
            let gatt = P2pGattChatManager.shared(sessionId: sessionId)
            DispatchQueue.global(qos: .userInitiated).async {
                _ = gatt.sendVoiceMessage(
                    audioFileName: audioRelativePath,
                    mimeType: mime,
                    durationMillis: durationMillis ?? 0,
                    messageId: transportMessageId
                )
            }
        }
        publishMerged()
    }

    /// Persists the image locally (rendered from the local file) then streams it over the live link.
    private func sendImageViaBluetoothBridge(_ attachment: PendingChannelAttachment, bridge: ContactRecord) {
        let transportMessageId = "ios-\(UUID().uuidString.lowercased())"
        guard let imageRelativePath = SOSChatStore.persistImageData(
            attachment.data, messageId: transportMessageId, mimeType: attachment.mime
        ) else { return }
        _ = SOSChatStore.shared.appendLocalImageMessage(
            sessionId: bridge.id,
            imageRelativePath: imageRelativePath,
            thumbnailRelativePath: nil,
            imageWidth: attachment.width,
            imageHeight: attachment.height,
            imageMimeType: attachment.mime,
            status: .sent,
            transportMessageId: transportMessageId
        )
        let sessionId = bridge.id
        let mime = attachment.mime
        let width = attachment.width ?? 0
        let height = attachment.height ?? 0
        if ContactBroadcastManager.shared.isSessionConnected(sessionId) {
            DispatchQueue.global(qos: .userInitiated).async {
                _ = ContactBroadcastManager.shared.sendImageMessage(
                    imageFileName: imageRelativePath,
                    mimeType: mime,
                    width: width,
                    height: height,
                    messageId: transportMessageId,
                    sessionId: sessionId
                )
            }
        } else {
            let gatt = P2pGattChatManager.shared(sessionId: sessionId)
            DispatchQueue.global(qos: .userInitiated).async {
                _ = gatt.sendImageMessage(
                    imageFileName: imageRelativePath,
                    mimeType: mime,
                    width: width,
                    height: height,
                    messageId: transportMessageId
                )
            }
        }
        publishMerged()
    }

    /// Shared files travel as the citizen CC_FILE preview text row + a chunked blob stream, under
    /// the same uuid (the exact pairing the peer's receiver expects). The preview row is persisted
    /// so the citizen chat can render the file even though the authority thread has no file card.
    private func sendFileViaBluetoothBridge(_ attachment: PendingChannelAttachment, bridge: ContactRecord) {
        let transportMessageId = "ios-\(UUID().uuidString.lowercased())"
        let prepared = PreparedP2pDocumentAttachment(
            displayName: attachment.name,
            mimeType: attachment.mime.isEmpty ? nil : attachment.mime,
            originalSizeBytes: attachment.data.count,
            transferSizeBytes: attachment.data.count,
            payloadData: attachment.data
        )
        let previewText = P2pSharedTransferSupport.buildFilePreviewMessage(prepared)
        _ = SOSChatStore.shared.appendLocalMessage(
            sessionId: bridge.id,
            text: previewText,
            status: .sent,
            transportMessageId: transportMessageId
        )
        let sessionId = bridge.id
        let data = attachment.data
        let displayName = attachment.name
        let mime = attachment.mime.isEmpty ? nil : attachment.mime
        let size = attachment.data.count
        if ContactBroadcastManager.shared.isSessionConnected(sessionId) {
            DispatchQueue.global(qos: .userInitiated).async {
                let fileSent = ContactBroadcastManager.shared.sendFileMessage(
                    data: data,
                    displayName: displayName,
                    mimeType: mime,
                    originalSizeBytes: size,
                    messageId: transportMessageId,
                    sessionId: sessionId
                )
                if fileSent {
                    _ = ContactBroadcastManager.shared.sendMessage(
                        previewText, transportMessageId: transportMessageId, sessionId: sessionId
                    )
                }
            }
        } else {
            let gatt = P2pGattChatManager.shared(sessionId: sessionId)
            DispatchQueue.global(qos: .userInitiated).async {
                let fileQueued = gatt.sendFileMessage(
                    data: data,
                    displayName: displayName,
                    mimeType: mime,
                    originalSizeBytes: size,
                    messageId: transportMessageId
                )
                if fileQueued {
                    _ = gatt.sendMessage(previewText, messageId: transportMessageId)
                }
            }
        }
        publishMerged()
    }

    /// Once online with the channel key, posts every Bluetooth-carried text of this thread that
    /// never reached the channel (keyed by clientUuid) so the web dashboard catches up too.
    private func backfillBluetoothMessages(key: HierarchyChannelKey, myUid: String) async {
        // Give the conversation listener a moment to hydrate cloud rows, else we could re-post.
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        guard InternetChatTransport.shared.isAvailable() else { return }
        let session = InternetConversation.pairId(myUid, peer.uid)
        guard let contact = ContactStore.shared.contacts.first(where: {
            $0.sessionCode.caseInsensitiveCompare(session) == .orderedSame
        }) else { return }
        let cloudUuids = Set(cloudMessages.compactMap { $0.clientUuid.isEmpty ? nil : $0.clientUuid })
        let senderName = ProfileMetadataStore.preferredDisplayName() ?? "iOS"
        for row in SOSChatStore.shared.messages(for: contact.id) {
            guard row.isLocal else { continue }
            let uuid = row.transportMessageId ?? row.id.uuidString
            guard !cloudUuids.contains(uuid) else { continue }
            var text = ""
            var attachments: [PendingChannelAttachment] = []
            switch row.kind {
            case .text:
                text = row.text.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty, !text.hasPrefix("CC_") else { continue }
            case .audio:
                guard let name = row.audioRelativePath,
                      let data = SOSChatStore.loadVoiceData(fileName: name) else { continue }
                attachments = [PendingChannelAttachment(
                    data: data,
                    name: name,
                    mime: name.lowercased().hasSuffix(".ogg") ? "audio/ogg" : "audio/mp4",
                    durationSec: (row.audioDurationMillis).flatMap { $0 > 0 ? $0 / 1000 : nil }
                )]
            case .image:
                guard let name = row.imageRelativePath,
                      let data = SOSChatStore.loadImageData(fileName: name) else { continue }
                attachments = [PendingChannelAttachment(
                    data: data,
                    name: name,
                    mime: row.imageMimeType ?? "image/jpeg",
                    width: row.imageWidth,
                    height: row.imageHeight
                )]
            default:
                continue
            }
            try? await client.send(
                channelKey: key,
                senderName: senderName,
                recipientUid: peer.uid,
                recipientName: peer.name,
                text: text,
                attachments: attachments,
                clientUuid: uuid
            )
        }
    }
}

struct HierarchyThreadView: View {
    let channel: HierarchyChannel
    let peer: HierarchyPeer

    @StateObject private var store: HierarchyThreadStore
    @StateObject private var voiceRecorder = AuthorityVoiceRecorderModel()
    @StateObject private var locationCoordinator = ChatAttachmentLocationCoordinator()
    @State private var draft = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showDocumentPicker = false
    @State private var isSharingLocation = false
    // Scroll-to-bottom affordance (Android parity): track whether the newest message is on
    // screen, and how many arrived while the user was scrolled up (badge count).
    @State private var isAtTranscriptBottom = true
    @State private var seenMessageCount = 0

    init(channel: HierarchyChannel, peer: HierarchyPeer, preloadedKey: HierarchyChannelKey?) {
        self.channel = channel
        self.peer = peer
        _store = StateObject(
            wrappedValue: HierarchyThreadStore(channel: channel, peer: peer, preloadedKey: preloadedKey)
        )
    }

    var body: some View {
        Group {
            switch store.state {
            case .loading:
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .error:
                VStack(spacing: 14) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.system(size: 40))
                        .foregroundStyle(Color.appTextSecondary)
                    Text("AUTHORITY_CHANNEL_ERROR")
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            case .ready:
                conversation
            }
        }
        .background(Color.appBackground)
        .navigationTitle(peer.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            // Android's two-line top bar: peer name over "typing…" / the agency name.
            ToolbarItem(placement: .principal) {
                VStack(spacing: 1) {
                    Text(peer.name)
                        .font(.headline)
                        .lineLimit(1)
                    Text(store.peerTyping ? typingSubtitle : agencySubtitle)
                        .font(.caption2)
                        .foregroundStyle(store.peerTyping ? Color.appPrimary : Color.appTextSecondary)
                        .lineLimit(1)
                }
            }
            // SFU (Cloudflare Realtime) authority call — interoperable with the web dashboard and
            // Android; hidden while its feature gate is off. The Bluetooth-bridge AUDIO call is
            // SFU-independent though: sharing the gate meant iOS kurum users could place ZERO
            // authority calls while the flag was off, even standing next to the peer with a live
            // bridge link. Audio shows whenever either path can work; video is SFU-only.
            if SfuCallConfig.enabled || bridgeAudioCallAvailable {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 2) {
                        if SfuCallConfig.enabled {
                            Button {
                                placeAuthorityCall(video: true)
                            } label: {
                                Image(systemName: "video")
                            }
                        }
                        Button {
                            placeAuthorityCall(video: false)
                        } label: {
                            Image(systemName: "phone")
                        }
                    }
                }
            }
        }
        .onAppear {
            store.start()
            store.markRead()
            // Consume this thread's notifications on open — the fixed pre-route ids could never be
            // swept, so banners for messages the user had already read stayed in the shade.
            SOSNotificationCenter.clearMessageNotification(
                route: .authorityThread(channelId: channel.channelId, peerUid: peer.uid)
            )
        }
        .onDisappear { store.stop() }
    }

    private var typingSubtitle: String {
        NSLocalizedString("CHAT_TYPING", comment: "")
    }

    private var agencySubtitle: String {
        let name = peer.agency ?? channel.peerPanelName
        return name.isEmpty ? NSLocalizedString("AUTHORITY_CHANNEL_ROW_LABEL", comment: "") : name
    }

    /// Android's placeCall order: offline + live Bluetooth bridge → classic BT voice call to the
    /// hidden bridge contact (the citizen GATT call stack, same call UI); anything else — video,
    /// or an internet connection — goes over the SFU. Video needs the internet stacks.
    private var bridgeAudioCallAvailable: Bool {
        store.bluetoothLinked && store.bridgeContact?.aesKeyBase64.isEmpty == false
    }

    private func placeAuthorityCall(video: Bool) {
        // Bridge audio wins when the internet is down — and it is the ONLY path while the SFU gate
        // is off, so in that state it must not require the internet to be unreachable first.
        if !video,
           bridgeAudioCallAvailable,
           !SfuCallConfig.enabled || !InternetChatTransport.shared.isAvailable(),
           let bridge = store.bridgeContact {
            ChatPeerVoiceCallCoordinator.shared.placeCall(sessionId: bridge.id, contact: bridge, kind: .audio)
            return
        }
        guard SfuCallConfig.enabled else { return }
        placeSfuCall(video: video)
    }

    private func placeSfuCall(video: Bool) {
        SfuAuthorityCallManager.shared.startOutgoing(
            channelId: channel.channelId,
            kind: .hierarchy,
            peerUid: peer.uid,
            peerName: peer.name,
            video: video
        )
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            // Offline transport badge: the peer's hidden bridge contact has a live Bluetooth link.
            if store.bluetoothLinked {
                HStack(spacing: 5) {
                    Image(systemName: "dot.radiowaves.left.and.right")
                        .font(.caption2)
                    Text("CHAT_STATUS_CONNECTED_BLUETOOTH")
                        .font(.caption2.weight(.semibold))
                }
                .foregroundStyle(Color.accentColor)
                .padding(.vertical, 4)
                .frame(maxWidth: .infinity)
                .background(Color.accentColor.opacity(0.08))
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 10) {
                        HStack(spacing: 5) {
                            Image(systemName: "lock.fill")
                                .font(.caption2)
                            Text("AUTHORITY_CHANNEL_E2EE_NOTICE")
                                .font(.caption2)
                        }
                        .foregroundStyle(Color.appTextSecondary)
                        .padding(.vertical, 4)
                        if store.messages.isEmpty {
                            Text("AUTHORITY_CHANNEL_EMPTY")
                                .font(.subheadline)
                                .foregroundStyle(Color.appTextSecondary)
                                .padding(.top, 24)
                        }
                        ForEach(Array(store.messages.enumerated()), id: \.element.id) { index, message in
                            // Day boundary → centered date chip, like Android's DateHeader.
                            if index == 0 || !Calendar.current.isDate(
                                message.at, inSameDayAs: store.messages[index - 1].at
                            ) {
                                dateHeader(message.at)
                            }
                            // A call summary is not a speech bubble: it renders as the same
                            // full-width call card the citizen chat uses (Android's CallEventRow).
                            if let call = Self.parseCallLog(message.text) {
                                callEventRow(call, message: message)
                                    .id(message.id)
                            } else {
                                bubble(message)
                                    .id(message.id)
                            }
                        }
                        // Bottom sentinel: on screen ⇔ the user is reading the newest messages.
                        Color.clear
                            .frame(height: 1)
                            .id("authority-thread-bottom")
                            .onAppear {
                                isAtTranscriptBottom = true
                                seenMessageCount = store.messages.count
                            }
                            .onDisappear { isAtTranscriptBottom = false }
                    }
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: store.messages.last?.id) { _, lastId in
                    guard lastId != nil else { return }
                    store.markRead()
                    // Android parity: only follow new messages while the user IS at the bottom;
                    // scrolled up, the floating button badges the arrivals instead.
                    guard isAtTranscriptBottom else { return }
                    seenMessageCount = store.messages.count
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("authority-thread-bottom", anchor: .bottom)
                    }
                }
                .onAppear {
                    if store.messages.last != nil {
                        proxy.scrollTo("authority-thread-bottom", anchor: .bottom)
                        seenMessageCount = store.messages.count
                    }
                }
                .overlay(alignment: .bottom) {
                    if !isAtTranscriptBottom, !store.messages.isEmpty {
                        ChatScrollToBottomButton(
                            count: max(0, store.messages.count - seenMessageCount)
                        ) {
                            seenMessageCount = store.messages.count
                            withAnimation(.easeOut(duration: 0.25)) {
                                proxy.scrollTo("authority-thread-bottom", anchor: .bottom)
                            }
                        }
                        .padding(.bottom, 12)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                }
            }

            Divider()

            HStack(spacing: 10) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Image(systemName: "photo")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 34, height: 38)
                }
                .disabled(voiceRecorder.isRecording)

                Button {
                    showDocumentPicker = true
                } label: {
                    Image(systemName: "paperclip")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 30, height: 38)
                }
                .disabled(voiceRecorder.isRecording)

                Button {
                    shareCurrentLocation()
                } label: {
                    if isSharingLocation {
                        ProgressView()
                            .controlSize(.small)
                            .frame(width: 30, height: 38)
                    } else {
                        Image(systemName: "location.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Color.appPrimary)
                            .frame(width: 30, height: 38)
                    }
                }
                .disabled(voiceRecorder.isRecording || isSharingLocation)

                if voiceRecorder.isRecording {
                    HStack(spacing: 8) {
                        Circle()
                            .fill(Color.appDanger)
                            .frame(width: 8, height: 8)
                        Text(recordingLabel)
                            .font(.subheadline.monospacedDigit())
                        Spacer()
                        Button {
                            voiceRecorder.cancel()
                        } label: {
                            Image(systemName: "trash")
                                .font(.system(size: 16, weight: .semibold))
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(
                        Capsule(style: .continuous)
                            .fill(Color.appSurfaceElevated)
                    )
                } else {
                    TextField(LocalizedStringKey("AUTHORITY_COMPOSER_PLACEHOLDER"), text: $draft, axis: .vertical)
                        .lineLimit(1...4)
                        .onChange(of: draft) { _, text in
                            store.onComposerTyping(text)
                        }
                        .textFieldStyle(.plain)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 9)
                        .background(
                            Capsule(style: .continuous)
                                .fill(Color.appSurfaceElevated)
                        )
                }

                if draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                    // Empty composer → the button records / sends a voice note (WhatsApp pattern).
                    Button {
                        if voiceRecorder.isRecording {
                            if let voice = voiceRecorder.stop() {
                                store.send("", attachments: [voice])
                            }
                        } else {
                            voiceRecorder.start()
                        }
                    } label: {
                        Image(systemName: voiceRecorder.isRecording ? "arrow.up.circle.fill" : "mic.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 38, height: 38)
                            .background(Circle().fill(voiceRecorder.isRecording ? Color.appDanger : Color.appPrimary))
                    }
                } else {
                    Button {
                        let text = draft
                        draft = ""
                        store.setTyping(false)
                        store.send(text)
                    } label: {
                        Image(systemName: "paperplane.fill")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(.white)
                            .frame(width: 38, height: 38)
                            .background(Circle().fill(Color.appPrimary))
                    }
                }
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 10)
        }
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            photoItem = nil
            Task {
                guard let data = try? await item.loadTransferable(type: Data.self),
                      let prepared = await Task.detached(priority: .userInitiated, operation: {
                          AuthorityMediaPrep.imageAttachment(from: data)
                      }).value else { return }
                store.send("", attachments: [prepared])
            }
        }
        .fileImporter(isPresented: $showDocumentPicker, allowedContentTypes: [.item]) { result in
            guard case .success(let url) = result else { return }
            // Security-scoped read off the main thread; same 25 MB cap as Android's channel wire.
            Task.detached(priority: .userInitiated) {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                guard let data = try? Data(contentsOf: url),
                      data.count <= ChannelAttachments.maxAttachmentBytes else { return }
                let mime = UTType(filenameExtension: url.pathExtension)?.preferredMIMEType
                    ?? "application/octet-stream"
                let attachment = PendingChannelAttachment(
                    data: data,
                    name: url.lastPathComponent,
                    mime: mime
                )
                await MainActor.run {
                    store.send("", attachments: [attachment])
                }
            }
        }
    }

    private func dateHeader(_ date: Date) -> some View {
        Text(date.formatted(date: .abbreviated, time: .omitted))
            .font(.caption.weight(.semibold))
            .foregroundStyle(Color.appTextSecondary)
            .padding(.horizontal, 12)
            .padding(.vertical, 4)
            .background(Capsule(style: .continuous).fill(Color.appSurfaceElevated))
            .frame(maxWidth: .infinity)
            .padding(.vertical, 2)
    }

    /// Android's CallEventRow / the citizen chat's call bubble: a full-width card with the tinted
    /// direction icon in a circle and "HH:mm Status · duration" — instead of a speech bubble.
    private func callEventRow(_ call: ChannelCallLog, message: HierarchyMessage) -> some View {
        let isMine = message.senderUid == store.myUid
        let statusText: String
        let iconName: String
        let tint: Color
        if call.answered {
            statusText = NSLocalizedString(
                isMine ? "SOS_CHAT_CALL_EVENT_OUTGOING_ANSWERED" : "SOS_CHAT_CALL_EVENT_INCOMING_ANSWERED",
                comment: ""
            )
            iconName = isMine ? "phone.arrow.up.right.fill" : "phone.arrow.down.left.fill"
            tint = .appPrimary
        } else if call.declined {
            statusText = NSLocalizedString("SOS_CHAT_CALL_EVENT_REJECTED", comment: "")
            iconName = "phone.down.fill"
            tint = .appDanger
        } else {
            statusText = NSLocalizedString("SOS_CHAT_CALL_EVENT_MISSED", comment: "")
            iconName = "phone.arrow.down.left.fill"
            tint = .appDanger
        }
        var fullText = message.at.formatted(date: .omitted, time: .shortened) + " " + statusText
        if call.answered, call.durationSec > 0 {
            fullText += " · " + String(format: "%d:%02d", call.durationSec / 60, call.durationSec % 60)
        }
        return HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(tint.opacity(0.14))
                    .frame(width: 34, height: 34)
                Image(systemName: call.video && call.answered ? "video.fill" : iconName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(tint)
            }
            Text(fullText)
                .font(.subheadline)
                .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.appSurfaceElevated)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
    }

    private var recordingLabel: String {
        String(format: "%d:%02d", voiceRecorder.elapsedSeconds / 60, voiceRecorder.elapsedSeconds % 60)
    }

    /// Android parity: composer location button sends the same CC_LOC wire format as the citizen
    /// chat, as a normal text message so it renders on Android and the web dashboard too.
    private func shareCurrentLocation() {
        guard !isSharingLocation else { return }
        isSharingLocation = true
        Task { @MainActor in
            defer { isSharingLocation = false }
            guard let location = await locationCoordinator.requestLocation() else { return }
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
            store.send(payload)
        }
    }

    private func bubble(_ message: HierarchyMessage) -> some View {
        let isMine = message.senderUid == store.myUid
        return HStack {
            if isMine { Spacer(minLength: 48) }
            VStack(alignment: .leading, spacing: 6) {
                // Sender header on RECEIVED messages so a multi-party channel is legible: name +
                // role badge (Android parity). Own messages need no header.
                if !isMine {
                    let name = message.senderName.isEmpty ? peer.name : message.senderName
                    HStack(spacing: 6) {
                        if !name.isEmpty {
                            Text(name)
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(Color.appPrimary)
                        }
                        if let label = Self.roleLabel(peer.role) {
                            Text(label)
                                .font(.caption2.weight(.semibold))
                                .padding(.horizontal, 6)
                                .padding(.vertical, 1)
                                .background(RoundedRectangle(cornerRadius: 6, style: .continuous)
                                    .fill(Color.appPrimary.opacity(0.14)))
                                .foregroundStyle(Color.appPrimary)
                        }
                    }
                }
                ForEach(message.attachments) { attachment in
                    ChannelAttachmentBubbleView(
                        attachment: attachment,
                        key: store.mediaKey,
                        aad: store.mediaAad
                    )
                }
                if let media = store.bridgeMedia[message.id] {
                    BridgeMediaBubbleView(row: media)
                }
                if let loc = Self.parseSharedLocation(message.text) {
                    // Shared location (CC_LOC:…) → the citizen chat's real map card (Android
                    // renders a map bubble too); tapping opens Apple Maps.
                    Button {
                        if let url = URL(string: "https://maps.apple.com/?ll=\(loc.lat),\(loc.lng)&q=\(loc.lat),\(loc.lng)") {
                            UIApplication.shared.open(url)
                        }
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            ChatLocationMapCard(
                                coordinate: CLLocationCoordinate2D(latitude: loc.lat, longitude: loc.lng),
                                accuracyMeters: loc.accuracy,
                                interactive: false,
                                height: 140,
                                cornerRadius: 12
                            )
                            .frame(width: 224)
                            Label("AUTHORITY_SHARED_LOCATION", systemImage: "mappin.and.ellipse")
                                .font(.callout.weight(.medium))
                                .foregroundStyle(Color.appPrimary)
                        }
                    }
                    .buttonStyle(.plain)
                } else if let filePayload = P2pSharedTransferSupport.parseFilePreviewMessage(message.text) {
                    // A document that crossed the Bluetooth bridge (CC_FILE:…). Rendering the same
                    // card the citizen chat uses at least tells both sides the document EXISTS —
                    // it used to be silently dropped from this timeline, so the sender got zero
                    // feedback that their file went anywhere.
                    ChatSharedFileBubbleContent(payload: filePayload, onTap: {})
                } else if !message.text.isEmpty {
                    Text(message.text)
                        .font(.body)
                }
                HStack(spacing: 4) {
                    Spacer(minLength: 0)
                    Text(message.at, style: .time)
                        .font(.caption2)
                        .foregroundStyle(Color.appTextSecondary)
                    if isMine {
                        // ✓ sent, ✓✓ read — driven by the peer's read cursor, like web/Android.
                        let read = store.peerReadAt.map { message.at <= $0 } ?? false
                        Image(systemName: read ? "checkmark.circle.fill" : "checkmark")
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(read ? Color.appPrimary : Color.appTextSecondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(isMine ? Color.appPrimarySoft : Color.appSurfaceElevated)
            )
            .contextMenu {
                // Copy only real text — not the call-log / location control payloads.
                if !message.text.isEmpty,
                   Self.parseCallLog(message.text) == nil,
                   Self.parseSharedLocation(message.text) == nil,
                   P2pSharedTransferSupport.parseFilePreviewMessage(message.text) == nil {
                    Button {
                        UIPasteboard.general.string = message.text
                    } label: {
                        Label("CHAT_ACTION_COPY", systemImage: "doc.on.doc")
                    }
                }
            }
            if !isMine { Spacer(minLength: 48) }
        }
        .frame(maxWidth: .infinity, alignment: isMine ? .trailing : .leading)
    }

    struct ChannelCallLog {
        let video: Bool
        let status: String
        let durationSec: Int
        var answered: Bool { status == "ended" }
        var declined: Bool { status == "declined" }
    }

    /// Wire sentinel the web's secure-channel.ts encodeCallLog uses (Android parity): the body is
    /// U+0001 "call" U+0001 + JSON {kind,status,durationSec}. A plain-"call" prefix would never
    /// match — the control characters are part of the payload.
    private static let callLogPrefix = "\u{01}call\u{01}"

    static func parseCallLog(_ text: String) -> ChannelCallLog? {
        guard text.hasPrefix(callLogPrefix) else { return nil }
        let jsonPart = String(text.dropFirst(callLogPrefix.count))
        guard let data = jsonPart.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let kind = obj["kind"] as? String, kind == "audio" || kind == "video" else {
            return nil
        }
        return ChannelCallLog(
            video: kind == "video",
            status: obj["status"] as? String ?? "",
            durationSec: (obj["durationSec"] as? NSNumber)?.intValue ?? 0
        )
    }

    /// Parses a shared-location message: "CC_LOC:" + lat,lng,accuracy,timestamp[,radius][,source].
    static func parseSharedLocation(_ text: String) -> (lat: Double, lng: Double, accuracy: Double?)? {
        guard text.hasPrefix("CC_LOC:") else { return nil }
        let parts = String(text.dropFirst("CC_LOC:".count)).split(separator: ",")
        guard parts.count >= 2, let lat = Double(parts[0]), let lng = Double(parts[1]) else { return nil }
        let accuracy = parts.count >= 3 ? Double(parts[2]).flatMap { $0 > 0 ? $0 : nil } : nil
        return (lat, lng, accuracy)
    }

    /// Maps a raw role to a short badge label; nil for blank/unknown so no badge shows.
    static func roleLabel(_ role: String?) -> String? {
        let key = (role ?? "").trimmingCharacters(in: .whitespaces)
            .lowercased()
            .replacingOccurrences(of: "[-_ ]", with: "", options: .regularExpression)
        switch key {
        case "": return nil
        case "admin", "superadmin": return NSLocalizedString("AUTHORITY_ROLE_ADMIN", comment: "")
        case "authority": return NSLocalizedString("AUTHORITY_ROLE_AUTHORITY", comment: "")
        case "fieldteam", "field", "rescue", "rescuer": return NSLocalizedString("AUTHORITY_ROLE_FIELDTEAM", comment: "")
        default: return (role ?? "").trimmingCharacters(in: .whitespaces).capitalized
        }
    }
}
