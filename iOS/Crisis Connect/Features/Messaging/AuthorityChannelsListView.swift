//
//  AuthorityChannelsListView.swift
//  Crisis Connect
//
//  The kurum directory — iOS counterpart of Android's AuthorityContactPickerScreen (reached from
//  New Chat → "Add from agency" and from notifications). One list: the agency's own encrypted
//  broadcast channel on top, cross-panel (hierarchy) peers in Android's direction sections
//  (HQ / peer / sub-units, names+photos enriched from the agency roster), then the own-agency
//  roster split management / field teams — tapping those opens the same agency-scoped MLS-v2
//  AuthorityChat used by Android and web (never the legacy citizen transport).
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

    @State private var resolved: (channel: HierarchyChannel, peer: HierarchyPeer)?
    @State private var failed = false

    var body: some View {
        Group {
            if let resolved {
                HierarchyThreadView(
                    channel: resolved.channel,
                    peer: resolved.peer
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
        var id: String { scopeType.rawValue + ":" + channel.channelId + ":" + peer.uid }
        let channel: HierarchyChannel
        let peer: HierarchyPeer
        let scopeType: AuthorityMlsScopeType
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
        /// Canonical panel id resolved by the membership-gated backend.
        let agencySlug: String
        let agencyName: String?
        /// Android picker split: admin/authority → management, everyone else → field teams.
        var isManagement: Bool {
            let key = role.lowercased()
                .replacingOccurrences(of: "-", with: "")
                .replacingOccurrences(of: "_", with: "")
            return key == "admin" || key == "authority"
        }
    }

    @Published private(set) var groups: [PanelGroup] = []
    /// Same-agency MLS conversations with local history, for the unified Messages home list.
    @Published private(set) var agencyConversationRows: [PeerRow] = []
    @Published private(set) var managementMembers: [RosterMember] = []
    @Published private(set) var fieldMembers: [RosterMember] = []
    @Published private(set) var isLoading = true
    /// The cross-panel (hierarchy) channels fetch failed while the own-agency roster loaded — drives
    /// the retry banner (Android's crossPanelFailed), so the roster still shows on its own.
    @Published private(set) var crossPanelFailed = false
    /// The own-agency callable failed while hierarchy rows may still have loaded. Keeping this
    /// separate prevents a partial directory from masquerading as "this agency has no members".
    @Published private(set) var rosterFailed = false

    private let client = HierarchyMessagingClient()
    private var hasStarted = false

    private struct MlsPrewarmTarget: Sendable {
        let peerUid: String
        let scopeType: AuthorityMlsScopeType
        let channelId: String
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await load() }
    }

    func refresh() async {
        await load()
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
        async let rosterFetch = Self.fetchRosterCatching()
        async let channelsFetch = fetchChannelsCatching()
        let rosterResult = await rosterFetch
        let roster = rosterResult.members
        rosterFailed = rosterResult.failed
        let channels = await channelsFetch

        // Register and converge this iPhone's MLS device for the full reachable roster in the
        // background. Neither person has to manually open the same thread before the first send.
        if !myUid.isEmpty {
            var targets = roster
                .filter { $0.uid != myUid }
                .map { MlsPrewarmTarget(peerUid: $0.uid, scopeType: .agency, channelId: $0.agencySlug) }
            targets += (channels ?? []).flatMap { channel in
                channel.peers
                    .filter { $0.uid != myUid }
                    .map { MlsPrewarmTarget(peerUid: $0.uid, scopeType: .hierarchy, channelId: channel.channelId) }
            }
            Task { await Self.prewarmMls(selfUid: myUid, targets: targets) }
        }

        // Own-agency people come straight from the roster — applied regardless of the channel fetch.
        managementMembers = roster
            .filter { $0.uid != myUid && $0.isManagement }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        fieldMembers = roster
            .filter { $0.uid != myUid && !$0.isManagement }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

        // Reconstruct same-agency conversation rows from the protected MLS cache without touching
        // Firestore or registering devices. Untouched roster entries stay only in New Chat.
        var ownRows: [PeerRow] = []
        if !myUid.isEmpty {
            for member in roster where member.uid != myUid {
                let cached = try? await AuthorityMlsChatChannel.loadCachedMessages(
                    selfUid: myUid,
                    peerUid: member.uid,
                    scopeType: .agency,
                    channelId: member.agencySlug
                )
                guard let latest = cached?.max(by: {
                    $0.payload.sentAtMillis < $1.payload.sentAtMillis
                }) else { continue }
                let peer = HierarchyPeer(
                    uid: member.uid,
                    name: member.name.isEmpty ? member.uid : member.name,
                    role: member.role.isEmpty ? nil : member.role,
                    photoUrl: member.photoUrl,
                    agency: member.agencyName,
                    phone: nil
                )
                let channel = HierarchyChannel(
                    channelId: member.agencySlug,
                    peerPanelId: member.agencySlug,
                    peerPanelName: member.agencyName ?? member.agencySlug,
                    group: "agency",
                    peers: [peer]
                )
                let at = Date(timeIntervalSince1970: Double(latest.payload.sentAtMillis) / 1000)
                let cursor = await client.myReadCursorAt(
                    channelId: member.agencySlug,
                    myUid: myUid,
                    peerUid: member.uid,
                    scopeType: .agency
                )
                let firstAttachment = latest.payload.attachments.first
                let attachmentKind = firstAttachment.map {
                    if $0.mime.hasPrefix("audio/") { return "audio" }
                    if $0.mime.hasPrefix("image/") { return "image" }
                    return "file"
                } ?? ""
                ownRows.append(PeerRow(
                    channel: channel,
                    peer: peer,
                    scopeType: .agency,
                    preview: Self.previewText(
                        text: latest.payload.text,
                        attachmentKind: attachmentKind
                    ),
                    previewAt: at,
                    unread: latest.senderUid == member.uid && (cursor.map { at > $0 } ?? true),
                    bluetoothLinked: false
                ))
            }
        }
        agencyConversationRows = ownRows.sorted {
            ($0.previewAt ?? .distantPast) > ($1.previewAt ?? .distantPast)
        }

        if let channels {
            crossPanelFailed = false
            // Hidden Bluetooth-bridge contacts for peers whose number the backend released
            // (fire-and-forget; failures only cost the offline capability).
            Task { await AuthorityBridgeContacts.sync(channels: channels) }
            let rosterByUid = Dictionary(roster.map { ($0.uid, $0) }, uniquingKeysWith: { first, _ in first })

            // Android picker sections: channels grouped by hierarchy DIRECTION, not by panel.
            var rowsByDirection: [String: [PeerRow]] = [:]
            for channel in channels {
                for peer in channel.peers {
                    // The hierarchy payload often carries the web account's name (an email);
                    // the agency roster knows the person's real name + photo — prefer those.
                    let enriched = Self.enrich(peer, from: rosterByUid[peer.uid])
                    // Never request or decrypt retired shared-key history. Selected conversations
                    // populate from their verified MLS-v2 cache/session instead.
                    let latest: (text: String, at: Date, senderUid: String, peerName: String?, attachmentKind: String)? = nil
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
                            scopeType: .hierarchy,
                            preview: latest.map { Self.previewText(text: $0.text, attachmentKind: $0.attachmentKind) },
                            previewAt: latest?.at,
                            unread: unread,
                            bluetoothLinked: false
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

    private static func prewarmMls(selfUid: String, targets: [MlsPrewarmTarget]) async {
        var seen = Set<String>()
        let canonical = targets.filter { target in
            guard !target.peerUid.isEmpty, !target.channelId.isEmpty else { return false }
            return seen.insert("\(target.scopeType.rawValue)\u{0}\(target.channelId)\u{0}\(target.peerUid)").inserted
        }
        // Four sessions at a time keeps startup responsive while still making registration quick.
        let deviceLabel = String("iPhone \(UIDevice.current.model)".prefix(64))
        for start in stride(from: 0, to: canonical.count, by: 4) {
            let batch = Array(canonical[start..<min(start + 4, canonical.count)])
            await withTaskGroup(of: Void.self) { group in
                for target in batch {
                    group.addTask {
                        do {
                            let channel = try await AuthorityMlsChatChannel.prepare(
                                selfUid: selfUid,
                                peerUid: target.peerUid,
                                scopeType: target.scopeType,
                                channelId: target.channelId,
                                deviceLabel: deviceLabel
                            )
                            do {
                                for attempt in 0..<20 {
                                    let preparation = try await channel.refreshPreparation()
                                    if preparation.ready {
                                        try await channel.activate(
                                            onMessage: { _ in throw AuthorityMlsDeferredApplicationError.openInChat },
                                            onSecurityError: { _ in }
                                        )
                                        if try await channel.isReadyToSend() { break }
                                    }
                                    let nanoseconds: UInt64 = attempt < 10 ? 400_000_000 : 1_500_000_000
                                    try await Task.sleep(nanoseconds: nanoseconds)
                                }
                            } catch {
                                await channel.close()
                                throw error
                            }
                            await channel.close()
                        } catch {
                            NSLog("AuthorityMlsPrewarm: automatic convergence will retry later: %@", String(describing: error))
                        }
                    }
                }
                await group.waitForAll()
            }
        }
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
        let scopeType: AuthorityMlsScopeType
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
                    scopeType: row.scopeType,
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
                    scopeType: row.scopeType,
                    preview: row.preview,
                    previewAt: row.previewAt,
                    unread: row.unread
                )
            })
        }
        guard let data = try? JSONEncoder().encode(sections) else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// Resolves a channel + peer for a notification deep-link without requesting a retired shared
    /// message key. nil if the peer is no longer reachable.
    static func resolveThreadTarget(
        channelId: String, peerUid: String
    ) async -> (channel: HierarchyChannel, peer: HierarchyPeer)? {
        let client = HierarchyMessagingClient()
        guard let channels = try? await client.fetchChannels(),
              let channel = channels.first(where: { $0.channelId == channelId }),
              let peer = channel.peers.first(where: { $0.uid == peerUid }) else { return nil }
        let roster = (try? await fetchRoster()) ?? []
        let enriched = enrich(peer, from: roster.first(where: { $0.uid == peerUid }))
        let named = withDisplayName(enriched, messageSenderName: nil)
        return (channel, named)
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

    /// Own-agency roster via the same `listAuthorityRoster` callable Android + web use. The callable
    /// derives the canonical panel from the verified account, so panelId/agencyName-linked users do
    /// not disappear merely because their local user document lacks an `agencySlug` field.
    private static func fetchRosterCatching() async -> (members: [RosterMember], failed: Bool) {
        do {
            return (try await fetchRoster(), false)
        } catch {
            NSLog("AuthorityChannelsList: roster fetch failed: %@", String(describing: error))
            return ([], true)
        }
    }

    private static func fetchRoster() async throws -> [RosterMember] {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else {
            throw URLError(.userAuthenticationRequired)
        }
        let result = try await Functions.functions(region: InternetMessagingClient.region)
            .httpsCallable("listAuthorityRoster")
            .callAsync(["agencySlug": ""])
        guard let data = result.data as? [String: Any],
              let members = data["members"] as? [[String: Any]] else {
            throw URLError(.cannotParseResponse)
        }
        return members.compactMap { m in
            guard let uid = (m["uid"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines),
                  !uid.isEmpty,
                  let agencySlug = (m["agencySlug"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                  !agencySlug.isEmpty else { return nil }
            let rawName = (m["name"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return RosterMember(
                uid: uid,
                // A login email would show as a raw address — prettify to a readable name.
                name: looksLikeEmail(rawName) ? prettifyEmail(rawName) : rawName,
                role: m["role"] as? String ?? "",
                phone: m["phone"] as? String ?? "",
                photoUrl: (m["photoUrl"] as? String).flatMap { $0.isEmpty ? nil : $0 },
                agencySlug: agencySlug,
                agencyName: (m["agencyName"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            )
        }
    }
}

struct AuthorityChannelsListView: View {
    @StateObject private var store = AuthorityChannelsListStore()
    @State private var searchText = ""
    @State private var openedAuthorityTarget: OpenedAuthorityTarget?

    private struct OpenedAuthorityTarget: Identifiable, Hashable {
        var id: String { agencySlug + ":" + uid }
        let agencySlug: String
        let agencyName: String?
        let uid: String
        let name: String
        let role: String
        let photoUrl: String?
    }

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
            if store.rosterFailed && !store.isLoading {
                Section {
                    directoryWarningBanner(messageKey: "AUTHORITY_CHANNEL_ERROR")
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            if store.crossPanelFailed && !store.isLoading {
                Section {
                    directoryWarningBanner(messageKey: "AUTHORITY_PICKER_CROSS_PANEL_WARNING")
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
                                    scopeType: row.scopeType
                                )
                            }) {
                                peerRow(row)
                            }
                        }
                    }
                }
                // Own-agency people (Android picker's management/field sections): tapping opens the
                // same MLS-v2 AuthorityChat surface with an agency-scoped conversation binding.
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
        .navigationDestination(item: $openedAuthorityTarget) { target in
            let peer = HierarchyPeer(
                uid: target.uid,
                name: target.name,
                role: target.role.isEmpty ? nil : target.role,
                photoUrl: target.photoUrl,
                agency: target.agencyName,
                phone: nil
            )
            let channel = HierarchyChannel(
                channelId: target.agencySlug,
                peerPanelId: target.agencySlug,
                peerPanelName: target.agencyName ?? target.agencySlug,
                group: "agency",
                peers: [peer]
            )
            HierarchyThreadView(channel: channel, peer: peer, scopeType: .agency)
        }
        .onAppear {
            store.start()
            SOSNotificationCenter.clearMessageNotification(route: .authorityChannels)
        }
        .refreshable { await store.refresh() }
    }

    /// Android's CrossPanelWarningBanner: a danger-tinted strip with a Retry that reloads the
    /// hierarchy channels, shown when only the cross-panel fetch failed.
    private func directoryWarningBanner(messageKey: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.caption)
                .foregroundStyle(Color.appDanger)
            Text(LocalizedStringKey(messageKey))
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
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.appTextSecondary)
            }
            .padding(.vertical, 2)
        }
    }

    /// Same-agency contacts use the exact AuthorityChat MLS-v2 thread as hierarchy contacts. They
    /// must never be downgraded into the legacy citizen/contact transport.
    private func openRosterChat(_ member: AuthorityChannelsListStore.RosterMember) {
        guard !member.agencySlug.isEmpty else { return }
        openedAuthorityTarget = OpenedAuthorityTarget(
            agencySlug: member.agencySlug,
            agencyName: member.agencyName,
            uid: member.uid,
            name: member.name,
            role: member.role,
            photoUrl: member.photoUrl
        )
    }
}

// MARK: - Hierarchy 1:1 thread

@MainActor
final class HierarchyThreadStore: ObservableObject {
    enum State: Equatable { case loading, ready, error }

    @Published private(set) var state: State = .loading
    @Published private(set) var messages: [HierarchyMessage] = []
    @Published private(set) var peerReadAt: Date?
    @Published private(set) var peerDeliveredAt: Date?
    @Published private(set) var peerTyping = false
    @Published private(set) var mlsPreparation: AuthorityMlsPreparation?
    @Published private(set) var mlsSendReady = false
    @Published private(set) var mlsSecurityError: String?
    @Published private(set) var mlsApprovalUid: String?
    @Published private(set) var mlsApprovalError = false
    /// A live Bluetooth link to this peer's hidden bridge contact (drives the badge + send routing).
    @Published private(set) var bluetoothLinked = false
    /// Bluetooth-carried voice notes/images by merged-row id ("bt:<uuid>") — rendered from local files.
    @Published private(set) var bridgeMedia: [String: SOSChatMessage] = [:]

    private var mlsCloudMessages: [HierarchyMessage] = []
    // Exposed so the thread's call button can route an offline audio call over the live
    // Bluetooth bridge (Android's placeCall does the same with its bridge contact).
    private(set) var bridgeContact: ContactRecord?
    private var bridgeMonitorTask: Task<Void, Never>?
    private var bridgeAutoLinkAttempted = false
    private var processedBridgeRows = Set<UUID>()
    private var pendingBluetoothEnvelopes: [String: String] = [:]
    private var pendingBluetoothAttachments: [String: [ChannelAttachment]] = [:]
    private var cloudRetryTask: Task<Void, Never>?

    private let client = HierarchyMessagingClient()
    private let channel: HierarchyChannel
    private let peer: HierarchyPeer
    private let scopeType: AuthorityMlsScopeType
    private var cursorRegistration: ListenerRegistration?
    private var deliveryCursorRegistration: ListenerRegistration?
    private var typingRegistration: ListenerRegistration?
    private var typingResetTask: Task<Void, Never>?
    private var mlsTask: Task<Void, Never>?
    private var mlsChannel: AuthorityMlsChatChannel?
    private var preparationWakeConversationId: String?
    private var lastTypingSentAt = Date.distantPast
    private var hasStarted = false

    var myUid: String? { Auth.auth().currentUser?.uid }

    /// MLS-v2 attachment descriptors carry an independent per-file key; legacy channel-key blobs
    /// intentionally fail closed.
    var mediaKey: SymmetricKey? { nil }
    var mediaAad: String? { nil }

    init(
        channel: HierarchyChannel,
        peer: HierarchyPeer,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) {
        self.channel = channel
        self.peer = peer
        self.scopeType = scopeType
    }

    func start() {
        guard !hasStarted else { return }
        hasStarted = true
        Task { await bootstrap() }
        mlsTask = Task { await bootstrapMls() }
        // Nearby is transport-only: it carries the exact MLS ciphertext, never AuthorityChat
        // plaintext or attachment keys outside the MLS application message.
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
        if let contact, contact.isAuthorityBridge == true {
            AuthorityNearbyCallRouteRegistry.register(
                sessionId: contact.id,
                channel: channel,
                peer: peer,
                scopeType: scopeType
            )
        }
        guard let contact, !contact.aesKeyBase64.isEmpty else {
            if bluetoothLinked { bluetoothLinked = false }
            if let contact, !bridgeAutoLinkAttempted, NearbyAutoLink.isEligible(contact) {
                bridgeAutoLinkAttempted = true
                NearbyAutoLink.shared.tryEstablish(contact: contact)
            }
            publishMerged()
            return
        }
        let hostConnected = ContactBroadcastManager.shared.isSessionConnected(contact.id)
        let central = P2pGattChatManager.shared(sessionId: contact.id)
        central.start()
        let centralReady = central.isReady()
        let linked = hostConnected || centralReady
        if linked != bluetoothLinked { bluetoothLinked = linked }
        consumeNearbyMlsRows(contact)
        if linked { drainBluetoothEnvelopes(contact) }
        publishMerged()
    }

    private func consumeNearbyMlsRows(_ contact: ContactRecord) {
        guard let mlsChannel else { return }
        var acceptedMachineRow = false
        for row in SOSChatStore.shared.messages(for: contact.id)
        where !row.isLocal && row.text.hasPrefix(AuthorityMlsOfflineEnvelopeCodec.prefix) &&
            !processedBridgeRows.contains(row.id) {
            guard let envelope = AuthorityMlsOfflineEnvelopeCodec.decode(row.text) else {
                processedBridgeRows.insert(row.id)
                continue
            }
            processedBridgeRows.insert(row.id)
            acceptedMachineRow = true
            Task { @MainActor [weak self] in
                do {
                    let expected = await mlsChannel.conversationId
                    guard envelope.conversationId == expected else { return }
                    try await mlsChannel.acceptOfflineEnvelope(row.text)
                    SOSChatStore.shared.removeInboundAuthorityMlsTransportMessage(
                        sessionId: contact.id,
                        messageId: row.id
                    )
                } catch {
                    self?.processedBridgeRows.remove(row.id)
                    NSLog("HierarchyThreadStore: nearby MLS delivery rejected: %@", String(describing: error))
                }
            }
        }
        if acceptedMachineRow {
            // The hidden transport row is not a citizen-chat message and must not leave an
            // unread badge behind. AuthorityChat emits its own decrypted-message notification.
            SOSChatStore.shared.markRemoteRead(sessionId: contact.id)
        }
    }

    private func drainBluetoothEnvelopes(_ contact: ContactRecord) {
        for (messageId, encoded) in pendingBluetoothEnvelopes {
            let attachments = pendingBluetoothAttachments[messageId] ?? []
            let cachedAttachments: [(ChannelAttachment, Data)] = attachments.compactMap { attachment in
                guard let cipher = ChannelAttachments.cachedAuthorityMlsCiphertext(path: attachment.path),
                      cipher.count <= ChannelAttachments.authorityMlsBluetoothMaxBytes else { return nil }
                return (attachment, cipher)
            }
            // Never advertise the MLS descriptor until every referenced ciphertext blob is
            // available to the peer. Oversized/missing files remain queued for the cloud retry.
            guard cachedAttachments.count == attachments.count else { continue }
            let transportId = "amls_\(messageId)"
            guard let envelopeData = encoded.data(using: .utf8),
                  envelopeData.count <= ChannelAttachments.authorityMlsBluetoothMaxBytes else { continue }
            let sent: Bool
            if ContactBroadcastManager.shared.isSessionConnected(contact.id) {
                let blobsSent = cachedAttachments.enumerated().allSatisfy { index, item in
                    ContactBroadcastManager.shared.sendFileMessage(
                        data: item.1,
                        displayName: item.0.path,
                        mimeType: ChannelAttachments.authorityMlsBlobMime,
                        originalSizeBytes: item.1.count,
                        messageId: "amlsa_\(messageId.suffix(80))_\(index)",
                        sessionId: contact.id
                    )
                }
                sent = blobsSent && ContactBroadcastManager.shared.sendFileMessage(
                    data: envelopeData,
                    displayName: "authority-mls-envelope",
                    mimeType: ChannelAttachments.authorityMlsEnvelopeMime,
                    originalSizeBytes: envelopeData.count,
                    messageId: transportId,
                    sessionId: contact.id
                )
            } else {
                let central = P2pGattChatManager.shared(sessionId: contact.id)
                central.start()
                // GATT owns an ordered in-memory queue while it scans/connects. Queue every opaque
                // blob before the MLS envelope so the receiver can resolve attachment descriptors.
                for (index, item) in cachedAttachments.enumerated() {
                    _ = central.sendFileMessage(
                        data: item.1,
                        displayName: item.0.path,
                        mimeType: ChannelAttachments.authorityMlsBlobMime,
                        originalSizeBytes: item.1.count,
                        messageId: "amlsa_\(messageId.suffix(80))_\(index)"
                    )
                }
                _ = central.sendFileMessage(
                    data: envelopeData,
                    displayName: "authority-mls-envelope",
                    mimeType: ChannelAttachments.authorityMlsEnvelopeMime,
                    originalSizeBytes: envelopeData.count,
                    messageId: transportId
                )
                sent = true
            }
            if sent {
                pendingBluetoothEnvelopes.removeValue(forKey: messageId)
                pendingBluetoothAttachments.removeValue(forKey: messageId)
            }
        }
    }

    /// One time-sorted timeline: cloud channel messages + Bluetooth-carried plain-text rows of
    /// the bridge contact. A backfilled doc carries the Bluetooth copy's uuid as clientUuid, so
    /// that bt: row is dropped and the message renders once (the copy the web also sees).
    @MainActor private func publishMerged() {
        let cloud = Dictionary(grouping: mlsCloudMessages, by: \.id)
            .compactMap { $0.value.last }
        if !bridgeMedia.isEmpty { bridgeMedia = [:] }
        let merged = cloud.sorted { $0.at < $1.at }
        if merged != messages { messages = merged }
    }

    func stop() {
        setTyping(false)
        cursorRegistration?.remove()
        cursorRegistration = nil
        deliveryCursorRegistration?.remove()
        deliveryCursorRegistration = nil
        typingRegistration?.remove()
        typingRegistration = nil
        bridgeMonitorTask?.cancel()
        bridgeMonitorTask = nil
        if let bridgeContact {
            P2pGattChatManager.sharedIfExists(sessionId: bridgeContact.id)?.stop()
        }
        cloudRetryTask?.cancel()
        cloudRetryTask = nil
        mlsTask?.cancel()
        mlsTask = nil
        mlsSendReady = false
        if let mlsChannel { Task { await mlsChannel.close() } }
        mlsChannel = nil
        hasStarted = false
    }

    /// The thread is on screen and messages are visible — advance my read cursor.
    func markRead() {
        guard let myUid = Auth.auth().currentUser?.uid,
              let newestInbound = messages
                .filter({ $0.senderUid == peer.uid })
                .map(\.at)
                .max() else { return }
        client.writeReadCursor(
            channelId: channel.channelId,
            myUid: myUid,
            peerUid: peer.uid,
            at: newestInbound,
            scopeType: scopeType
        )
    }

    /// Composer keystrokes → typing=true (throttled), auto-cleared after 4s of silence.
    func onComposerTyping(_ text: String) {
        guard let myUid = Auth.auth().currentUser?.uid,
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let now = Date()
        if now.timeIntervalSince(lastTypingSentAt) >= 3 {
            lastTypingSentAt = now
            client.setTyping(
                channelId: channel.channelId,
                myUid: myUid,
                peerUid: peer.uid,
                typing: true,
                scopeType: scopeType
            )
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
        client.setTyping(
            channelId: channel.channelId,
            myUid: myUid,
            peerUid: peer.uid,
            typing: typing,
            scopeType: scopeType
        )
    }

    private func bootstrap() async {
        guard let myUid = Auth.auth().currentUser?.uid else {
            state = .error
            return
        }
        cursorRegistration = client.listenReadCursor(
            channelId: channel.channelId,
            myUid: myUid,
            peerUid: peer.uid,
            scopeType: scopeType
        ) { [weak self] at in
            Task { @MainActor [weak self] in self?.peerReadAt = at }
        }
        deliveryCursorRegistration = client.listenDeliveredCursor(
            channelId: channel.channelId,
            myUid: myUid,
            peerUid: peer.uid,
            scopeType: scopeType
        ) { [weak self] at in
            Task { @MainActor [weak self] in self?.peerDeliveredAt = at }
        }
        typingRegistration = client.listenTyping(
            channelId: channel.channelId,
            myUid: myUid,
            peerUid: peer.uid,
            scopeType: scopeType
        ) { [weak self] typing in
            Task { @MainActor [weak self] in self?.peerTyping = typing }
        }
        state = .ready
    }

    private func bootstrapMls() async {
        guard let myUid else { return }
        var attempt = 0
        while !Task.isCancelled {
            do {
                let mls: AuthorityMlsChatChannel
                if let existing = mlsChannel {
                    mls = existing
                } else {
                    mls = try await AuthorityMlsChatChannel.prepare(
                        selfUid: myUid,
                        peerUid: peer.uid,
                        scopeType: scopeType,
                        channelId: channel.channelId,
                        deviceLabel: "iOS"
                    )
                    mlsChannel = mls
                    let cached = try await mls.loadCachedMessages()
                    mlsCloudMessages = cached.map(hierarchyMessage)
                    publishMerged()
                }
                let preparation = try await mls.refreshPreparation()
                mlsPreparation = preparation
                let conversationId = await mls.conversationId
                if !preparation.ready, preparationWakeConversationId != conversationId {
                    preparationWakeConversationId = conversationId
                    do {
                        try await InternetMessagingClient()
                            .requestAuthorityMlsPreparation(conversationId: conversationId)
                    } catch {
                        preparationWakeConversationId = nil
                        NSLog("Authority MLS preparation wake failed: %@", String(describing: error))
                    }
                }
                if preparation.ready {
                    try await activateMls(mls)
                    if try await mls.isReadyToSend() {
                        mlsSendReady = true
                        mlsSecurityError = nil
                        return
                    }
                }
                mlsSendReady = false
                mlsSecurityError = "automatic-retry"
            } catch {
                mlsSendReady = false
                mlsSecurityError = "automatic-retry"
                NSLog("Authority MLS setup will retry automatically: %@", String(describing: error))
            }
            // Firestore directory convergence is normally sub-second. Avoid the former exponential
            // backoff which could leave an otherwise healthy thread idle for up to 30 seconds.
            let retryDelay: UInt64 = attempt < 10 ? 400_000_000 : 1_500_000_000
            attempt += 1
            try? await Task.sleep(nanoseconds: retryDelay)
        }
    }

    func approveDeviceSet(uid: String, expectedFingerprint: String) {
        guard let mlsChannel,
              !uid.isEmpty,
              !expectedFingerprint.isEmpty,
              mlsApprovalUid == nil else { return }
        mlsApprovalUid = uid
        mlsApprovalError = false
        Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                mlsPreparation = try await mlsChannel.approveDeviceSet(
                    uid: uid,
                    expectedFingerprint: expectedFingerprint
                )
                mlsApprovalUid = nil
            } catch {
                NSLog("Authority MLS device-set approval failed closed: %@", String(describing: error))
                mlsApprovalUid = nil
                mlsApprovalError = true
            }
        }
    }

    private func activateMls(_ mls: AuthorityMlsChatChannel) async throws {
        try await mls.activate(onMessage: { [weak self] message in
            await MainActor.run {
                guard let self else { return }
                self.mlsCloudMessages.removeAll { $0.id == message.id }
                self.mlsCloudMessages.append(self.hierarchyMessage(message))
                self.publishMerged()
                if message.senderUid == self.peer.uid {
                    self.client.writeDeliveredCursor(
                        channelId: self.channel.channelId,
                        myUid: self.myUid ?? "",
                        peerUid: self.peer.uid,
                        at: Date(timeIntervalSince1970: TimeInterval(message.payload.sentAtMillis) / 1000),
                        scopeType: self.scopeType
                    )
                }
            }
        }, onSecurityError: { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self else { return }
                NSLog("Authority MLS transport will reconnect automatically: %@", String(describing: error))
                self.mlsSecurityError = "automatic-retry"
                self.mlsPreparation = nil
                self.mlsSendReady = false
                self.mlsChannel = nil
                self.mlsTask?.cancel()
                self.mlsTask = Task { @MainActor [weak self] in
                    await mls.close()
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    await self?.bootstrapMls()
                }
            }
        })
    }

    private func hierarchyMessage(_ message: AuthorityMlsChatMessage) -> HierarchyMessage {
        HierarchyMessage(
            id: message.id,
            senderUid: message.senderUid,
            senderName: message.payload.senderName,
            recipientUid: message.payload.recipientUid,
            recipientName: message.payload.recipientName,
            text: message.payload.text,
            at: Date(timeIntervalSince1970: TimeInterval(message.payload.sentAtMillis) / 1000),
            attachments: message.payload.attachments
        )
    }

    func send(_ text: String, attachments: [PendingChannelAttachment] = []) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty || !attachments.isEmpty else { return }
        guard let mlsChannel, mlsSendReady else {
            mlsSecurityError = "Secure device setup is still completing."
            return
        }
        // Real name (profile → Firebase Auth displayName), so the peer sees us by name, not "iOS".
        let senderName = ProfileMetadataStore.preferredDisplayName() ?? "iOS"
        let peer = peer
        Task {
            var preparedAttachments: [ChannelAttachment] = []
            do {
                let conversationId = await mlsChannel.conversationId
                preparedAttachments = try await ChannelAttachments.prepareAuthorityMlsAttachments(
                    conversationId: conversationId,
                    pendings: attachments
                )
                let sent = try await mlsChannel.stage(AuthorityMlsMessagePayload(
                    recipientUid: peer.uid,
                    recipientName: peer.name,
                    senderName: senderName,
                    text: trimmed,
                    sentAtMillis: Int64(Date().timeIntervalSince1970 * 1000),
                    attachments: preparedAttachments,
                    replyToId: nil
                ))
                mlsCloudMessages.removeAll { $0.id == sent.id }
                mlsCloudMessages.append(hierarchyMessage(sent))
                publishMerged()

                let kind = attachments.isEmpty ? "text"
                    : attachments.contains(where: { $0.mime.hasPrefix("audio/") }) ? "voice"
                    : attachments.contains(where: { $0.mime.hasPrefix("image/") }) ? "image" : "file"
                do {
                    guard InternetChatTransport.shared.isAvailable() else {
                        throw URLError(.notConnectedToInternet)
                    }
                    try await mlsChannel.publishPending()
                    pendingBluetoothEnvelopes.removeValue(forKey: sent.id)
                    pendingBluetoothAttachments.removeValue(forKey: sent.id)
                    AppAnalytics.messageSent(kind: kind, transport: "internet", chat: "authority_channel")
                } catch {
                    let encoded = try await mlsChannel.offlineEnvelope(messageId: sent.id)
                    pendingBluetoothEnvelopes[sent.id] = encoded
                    if !preparedAttachments.isEmpty {
                        pendingBluetoothAttachments[sent.id] = preparedAttachments
                    }
                    if let bridgeContact, bluetoothLinked {
                        drainBluetoothEnvelopes(bridgeContact)
                    }
                    scheduleCloudRetry()
                    AppAnalytics.messageSent(kind: kind, transport: "bluetooth_mls", chat: "authority_channel")
                }
            } catch {
                NSLog("HierarchyThreadStore: send failed: %@", String(describing: error))
                mlsSecurityError = "automatic-retry"
            }
        }
    }

    private func scheduleCloudRetry() {
        guard cloudRetryTask == nil else { return }
        cloudRetryTask = Task { @MainActor [weak self] in
            while let self, !Task.isCancelled {
                guard self.mlsSendReady, InternetChatTransport.shared.isAvailable(),
                      let mlsChannel = self.mlsChannel else {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    continue
                }
                do {
                    try await mlsChannel.publishPending()
                    self.pendingBluetoothEnvelopes.removeAll()
                    self.pendingBluetoothAttachments.removeAll()
                    self.cloudRetryTask = nil
                    return
                } catch {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                }
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

}

struct HierarchyThreadView: View {
    @Environment(\.scenePhase) private var scenePhase

    let channel: HierarchyChannel
    let peer: HierarchyPeer
    let scopeType: AuthorityMlsScopeType

    @StateObject private var store: HierarchyThreadStore
    @StateObject private var nearbyCallController: ChatPeerVoiceCallController
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
    @State private var transcriptInitialized = false
    @State private var screenVisible = false
    @State private var suppressNearbyCallScreen = false

    init(
        channel: HierarchyChannel,
        peer: HierarchyPeer,
        scopeType: AuthorityMlsScopeType = .hierarchy
    ) {
        self.channel = channel
        self.peer = peer
        self.scopeType = scopeType
        _store = StateObject(
            wrappedValue: HierarchyThreadStore(channel: channel, peer: peer, scopeType: scopeType)
        )
        let nearbySessionId = ContactStore.shared.contactForPeerUid(peer.uid)?.id
            ?? BroadcastSessionId.fromRawIdentifier(peer.uid)
        _nearbyCallController = StateObject(
            wrappedValue: ChatPeerVoiceCallController(sessionId: nearbySessionId)
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
            if mlsReady || store.bluetoothLinked {
                ToolbarItem(placement: .topBarTrailing) {
                    HStack(spacing: 2) {
                        // BLE/GATT carries audio only; video remains on the MLS-protected SFU.
                        if SfuCallConfig.enabled, mlsReady {
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
            screenVisible = true
            store.start()
            nearbyCallController.updateContact(store.bridgeContact)
            markReadIfVisible()
        }
        .onDisappear {
            screenVisible = false
            store.stop()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { markReadIfVisible() }
        }
        .onChange(of: store.bluetoothLinked) { _, _ in
            nearbyCallController.updateContact(store.bridgeContact)
        }
        .onChange(of: nearbyCallController.phase) { _, phase in
            switch phase {
            case .idle, .ended, .failed:
                suppressNearbyCallScreen = false
            case .dialing, .ringing, .connecting, .active:
                break
            }
        }
        .fullScreenCover(isPresented: nearbyCallScreenBinding) {
            ChatPeerVoiceOngoingCallScreen(
                controller: nearbyCallController,
                contactName: peer.name,
                avatarImageRelativePath: nil,
                avatarHue: AvatarGenerator.hue(for: nearbyCallController.sessionId),
                initials: AvatarGenerator.initials(from: peer.name),
                onReturnToChat: { suppressNearbyCallScreen = true }
            )
        }
    }

    private func markReadIfVisible() {
        guard screenVisible, scenePhase == .active, transcriptInitialized, isAtTranscriptBottom else { return }
        store.markRead()
        SOSNotificationCenter.clearMessageNotification(
            route: .authorityThread(channelId: channel.channelId, peerUid: peer.uid)
        )
    }

    private var typingSubtitle: String {
        NSLocalizedString("CHAT_TYPING", comment: "")
    }

    private var agencySubtitle: String {
        let name = peer.agency ?? channel.peerPanelName
        return name.isEmpty ? NSLocalizedString("AUTHORITY_CHANNEL_ROW_LABEL", comment: "") : name
    }

    private var mlsReady: Bool { store.mlsSendReady }

    private func placeAuthorityCall(video: Bool) {
        if !video,
           store.bluetoothLinked,
           let contact = store.bridgeContact,
           ChatPeerVoiceCallCoordinator.isCallEligibleContact(contact) {
            nearbyCallController.updateContact(contact)
            suppressNearbyCallScreen = false
            nearbyCallController.toolbarPrimaryAction()
            return
        }
        guard SfuCallConfig.enabled, mlsReady else { return }
        placeSfuCall(video: video)
    }

    private var nearbyCallScreenBinding: Binding<Bool> {
        Binding(
            get: {
                nearbyCallController.shouldPresentFullScreenExperience && !suppressNearbyCallScreen
            },
            set: { isPresented in
                if !isPresented, nearbyCallController.shouldPresentFullScreenExperience {
                    suppressNearbyCallScreen = true
                }
            }
        )
    }

    private func placeSfuCall(video: Bool) {
        SfuAuthorityCallManager.shared.startOutgoing(
            channelId: channel.channelId,
            kind: scopeType == .agency ? .agency : .hierarchy,
            peerUid: peer.uid,
            peerName: peer.name,
            video: video
        )
    }

    private var conversation: some View {
        VStack(spacing: 0) {
            if !pendingDeviceSets.isEmpty {
                VStack(alignment: .leading, spacing: 10) {
                    Label("AUTHORITY_DEVICE_VERIFICATION_TITLE", systemImage: "exclamationmark.shield.fill")
                        .font(.subheadline.weight(.semibold))
                    Text("AUTHORITY_DEVICE_VERIFICATION_BODY")
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                    ForEach(pendingDeviceSets, id: \.uid) { assessment in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(assessment.safetyNumber)
                                .font(.caption.monospaced())
                                .textSelection(.enabled)
                            Button {
                                store.approveDeviceSet(
                                    uid: assessment.uid,
                                    expectedFingerprint: assessment.fingerprint
                                )
                            } label: {
                                if store.mlsApprovalUid == assessment.uid {
                                    ProgressView()
                                        .controlSize(.small)
                                } else {
                                    Text("AUTHORITY_DEVICE_VERIFICATION_APPROVE")
                                }
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(store.mlsApprovalUid != nil)
                        }
                        .padding(10)
                        .background(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(Color.appSurfaceElevated)
                        )
                    }
                    if store.mlsApprovalError {
                        Text("AUTHORITY_DEVICE_VERIFICATION_CHANGED")
                            .font(.caption)
                            .foregroundStyle(Color.appDanger)
                    }
                }
                .padding(AppTheme.screenPadding)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.orange.opacity(0.1))
            }
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
                                transcriptInitialized = true
                                seenMessageCount = store.messages.count
                                markReadIfVisible()
                            }
                            .onDisappear { isAtTranscriptBottom = false }
                    }
                    .padding(.horizontal, AppTheme.screenPadding)
                    .padding(.vertical, 12)
                }
                .scrollDismissesKeyboard(.interactively)
                .onChange(of: store.messages.last?.id) { _, lastId in
                    guard lastId != nil else { return }
                    // Android parity: only follow new messages while the user IS at the bottom;
                    // scrolled up, the floating button badges the arrivals instead.
                    guard isAtTranscriptBottom else { return }
                    seenMessageCount = store.messages.count
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo("authority-thread-bottom", anchor: .bottom)
                    }
                    markReadIfVisible()
                }
                .onAppear {
                    if store.messages.last != nil {
                        proxy.scrollTo("authority-thread-bottom", anchor: .bottom)
                        seenMessageCount = store.messages.count
                        transcriptInitialized = true
                        markReadIfVisible()
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
                .disabled(voiceRecorder.isRecording || !mlsReady)

                Button {
                    showDocumentPicker = true
                } label: {
                    Image(systemName: "paperclip")
                        .font(.system(size: 19, weight: .semibold))
                        .foregroundStyle(Color.appPrimary)
                        .frame(width: 30, height: 38)
                }
                .disabled(voiceRecorder.isRecording || !mlsReady)

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
                .disabled(voiceRecorder.isRecording || isSharingLocation || !mlsReady)

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
                    .disabled(!mlsReady)
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
                    .disabled(!mlsReady)
                }
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 10)
        }
        .onChange(of: photoItem) { _, item in
            guard let item, mlsReady else { return }
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
            guard mlsReady, case .success(let url) = result else { return }
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

    private var pendingDeviceSets: [AuthorityMlsTrustAssessment] {
        store.mlsPreparation?.trust.filter { !$0.approved && !$0.fingerprint.isEmpty } ?? []
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
                        // ✓ sent, gray ✓✓ decrypted by peer, blue ✓✓ read.
                        let read = store.peerReadAt.map { message.at <= $0 } ?? false
                        let delivered = store.peerDeliveredAt.map { message.at <= $0 } ?? false
                        if read || delivered {
                            HStack(spacing: -3) {
                                Image(systemName: "checkmark")
                                Image(systemName: "checkmark")
                            }
                            .font(.system(size: 10, weight: .semibold))
                            .foregroundStyle(read ? Color.appPrimary : Color.appTextSecondary)
                        } else {
                            Image(systemName: "checkmark")
                                .font(.system(size: 10, weight: .semibold))
                                .foregroundStyle(Color.appTextSecondary)
                        }
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
