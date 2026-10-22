//
//  SOSChatNavigationTitle.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import Combine
import FirebaseAuth
import SwiftUI
import UIKit

struct ChatNavigationTitleContainer: View {
    let sessionId: UUID
    let title: String
    let subtitle: String?
    let initials: String
    let avatarHue: Double
    let avatarImageRelativePath: String?
    let showsVerifiedTrust: Bool
    let allowVerifiedTitleIcon: Bool
    let showsDirectStatus: Bool
    let peerUid: String?
    let internetCapable: Bool
    @StateObject private var directStatusState: ChatNavigationDirectStatusState
    @StateObject private var presenceState: ChatPeerPresenceState
    @ObservedObject private var traffic = DisasterTrafficController.shared

    init(
        sessionId: UUID,
        title: String,
        subtitle: String?,
        initials: String,
        avatarHue: Double,
        avatarImageRelativePath: String?,
        showsVerifiedTrust: Bool,
        allowVerifiedTitleIcon: Bool,
        showsDirectStatus: Bool,
        peerUid: String? = nil,
        internetCapable: Bool = false
    ) {
        self.sessionId = sessionId
        self.title = title
        self.subtitle = subtitle
        self.initials = initials
        self.avatarHue = avatarHue
        self.avatarImageRelativePath = avatarImageRelativePath
        self.showsVerifiedTrust = showsVerifiedTrust
        self.allowVerifiedTitleIcon = allowVerifiedTitleIcon
        self.showsDirectStatus = showsDirectStatus
        self.peerUid = peerUid
        self.internetCapable = internetCapable
        _directStatusState = StateObject(
            wrappedValue: ChatNavigationDirectStatusState(sessionId: sessionId)
        )
        _presenceState = StateObject(
            wrappedValue: ChatPeerPresenceState(sessionId: sessionId)
        )
    }

    var body: some View {
        let snapshot = directStatusState.snapshot
        let directStatus = snapshot.legacyStatus
        ChatNavigationTitleView(
            title: title,
            subtitle: liveSubtitle,
            initials: initials,
            avatarHue: avatarHue,
            avatarImageRelativePath: avatarImageRelativePath,
            peerPhotoUrl: resolvedPeerPhotoUrl,
            // A live BLUETOOTH session proves the peer's identity (paired keys); an internet
            // "connected" badge must never light the verified seal on its own.
            showsVerifiedTitleIcon: allowVerifiedTitleIcon &&
                (showsVerifiedTrust ||
                    (snapshot.bluetoothReady)),
            directStatus: directStatus,
            transport: snapshot.transport
        )
        .task(id: showsDirectStatus) {
            directStatusState.setEnabled(showsDirectStatus)
        }
        .task(id: internetCapable) {
            directStatusState.setInternetCapable(internetCapable)
        }
        .task(id: peerUid) {
            presenceState.setPeerUid(peerUid)
        }
        .onDisappear {
            directStatusState.setEnabled(false)
            directStatusState.setInternetCapable(false)
        }
    }

    /// The peer's stored internet profile photo (directory/message-added citizen contacts) when this
    /// header has no locally captured BLE avatar. Looked up by the chat's contact id, falling back to
    /// the peer uid, so the header shows the same photo as the conversation list row.
    private var resolvedPeerPhotoUrl: String? {
        if traffic.saving { return nil }
        if avatarImageRelativePath != nil { return nil }
        if let byId = ContactStore.shared.contact(for: sessionId)?.peerPhotoUrl {
            return byId
        }
        return peerUid.flatMap { ContactStore.shared.contactForPeerUid($0)?.peerPhotoUrl }
    }

    /// Typing beats presence beats the static role subtitle — same precedence as Android.
    private var liveSubtitle: String? {
        switch traffic.presence {
        case .sos: return NSLocalizedString("TRAFFIC_LOCAL_SOS",comment:"")
        case .offline: return NSLocalizedString("TRAFFIC_LOCAL_OFFLINE",comment:"")
        case .limited: return NSLocalizedString("TRAFFIC_LOCAL_LIMITED",comment:"")
        case .normal: break
        }
        if presenceState.isTyping {
            return NSLocalizedString("CHAT_TYPING", comment: "")
        }
        switch presenceState.presence {
        case .online:
            return NSLocalizedString("CHAT_PRESENCE_ONLINE", comment: "")
        case .lastSeen(let date):
            return String(
                format: NSLocalizedString("CHAT_PRESENCE_LAST_SEEN", comment: ""),
                Self.lastSeenFormatter.localizedString(for: date, relativeTo: Date())
            )
        case .unknown:
            return subtitle
        }
    }

    private static let lastSeenFormatter: RelativeDateTimeFormatter = {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .short
        return formatter
    }()
}

@MainActor
final class ChatNavigationDirectStatusState: ObservableObject {
    @Published private(set) var snapshot = ChatConnectionBadgeSnapshot(phase: .idle)
    var status: RescueConnectionStatus? { snapshot.legacyStatus }
    var transport: ChatBadgeTransport { snapshot.transport }

    private let sessionId: UUID
    private var p2pStatus: RescueConnectionStatus = .disconnected
    private var isEnabled = false
    private var screenshotStatusOverride: RescueConnectionStatus?
    private var screenshotStatusTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()

    private var internetCapable = false
    private var internetAvailable = false
    private var internetObservation: AnyCancellable?
    private var authObservation: ChatBadgeAuthObservation?
    private var p2pManager: P2pGattChatManager?
    private var bindingGeneration: UInt64 = 0

    init(sessionId: UUID) {
        self.sessionId = sessionId
    }

    func setEnabled(_ enabled: Bool) {
        guard enabled != isEnabled else { return }
        isEnabled = enabled
        bindingGeneration &+= 1

        if enabled {
            bindDeferred()
        } else {
            screenshotStatusTask?.cancel()
            screenshotStatusTask = nil
            screenshotStatusOverride = nil
            cancellables.removeAll()
            p2pStatus = .disconnected
            p2pManager = nil
            recomputeStatus()
        }
    }

    /// Local path and authentication events only; no polling or additional network requests.
    func setInternetCapable(_ capable: Bool) {
        guard capable != internetCapable else { return }
        internetCapable = capable
        internetObservation = nil
        authObservation = nil
        if capable {
            internetObservation = InternetChatTransport.shared.availabilityChanges
                .receive(on: DispatchQueue.main)
                .sink { [weak self] in self?.refreshInternetAvailability() }
            authObservation = ChatBadgeAuthObservation { [weak self] in
                Task { @MainActor [weak self] in self?.refreshInternetAvailability() }
            }
        }
        refreshInternetAvailability()
    }

    private func refreshInternetAvailability() {
        // Constrained text paths remain usable; the separate traffic policy limits payloads.
        internetAvailable = internetCapable && InternetChatTransport.shared.isAvailable(allowUltraConstrained: true)
        recomputeStatus()
    }

    private func bindDeferred() {
        cancellables.removeAll()
        screenshotStatusTask?.cancel()
        screenshotStatusTask = nil
        screenshotStatusOverride = nil
        let hostManager = ContactBroadcastManager.shared

        hostManager.$connectedSessionIds
            .map { [sessionId] in $0.contains(sessionId) }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                self.recomputeStatus()
            }
            .store(in: &cancellables)

        let capturedSessionId = sessionId
        let generation = bindingGeneration
        Task { @MainActor [weak self] in
            let p2pManager = P2pGattChatManager.shared(sessionId: capturedSessionId)
            guard let self, self.isEnabled, self.bindingGeneration == generation else { return }
            self.p2pManager = p2pManager
            self.p2pStatus = p2pManager.status
            p2pManager.$status
                .receive(on: DispatchQueue.main)
                .sink { [weak self] status in
                    guard let self else { return }
                    self.p2pStatus = status
                    self.recomputeStatus()
                }
                .store(in: &self.cancellables)
            self.recomputeStatus()
        }

        if shouldSimulateScreenshotConnection {
            startScreenshotConnectionTransition()
        }

        recomputeStatus()
    }

    private func recomputeStatus() {
        if let screenshotStatusOverride {
            snapshot = ChatConnectionBadgeSnapshot(phase: screenshotStatusOverride.showsVerifiedTitleIcon
                ? .bluetoothConnected : .connecting)
            return
        }
        let connecting: Bool
        switch p2pStatus {
        case .connecting, .discovering, .authenticating, .connected: connecting = true
        default: connecting = false
        }
        // Use the same verified, writable Bluetooth route as message routing, without a UI delay.
        snapshot = .resolve(bluetoothReady: isEnabled && (ContactBroadcastManager.shared.isSessionConnected(sessionId) || p2pManager?.isReady() == true),
            internetAvailable: internetCapable && internetAvailable,
            bluetoothConnecting: isEnabled && connecting,
            bluetoothFailed: isEnabled && p2pStatus == .failed,
            enabled: isEnabled || internetCapable)
    }

    private var shouldSimulateScreenshotConnection: Bool {
        AppStoreScreenshotSupport.isAnySceneEnabled &&
        sessionId == AppStoreScreenshotSupport.chatSessionId
    }

    private func startScreenshotConnectionTransition() {
        screenshotStatusOverride = .connecting
        recomputeStatus()
        screenshotStatusTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled, self.isEnabled else { return }
            self.screenshotStatusOverride = .connected
            self.recomputeStatus()
        }
    }
}

struct ChatNavigationTitleView: View {
    let title: String
    let subtitle: String?
    let initials: String
    let avatarHue: Double
    let avatarImageRelativePath: String?
    var peerPhotoUrl: String? = nil
    let showsVerifiedTitleIcon: Bool
    let directStatus: RescueConnectionStatus?
    var transport: ChatBadgeTransport = .bluetooth

    var body: some View {
        HStack(spacing: 10) {
            ChatAvatarCircleView(
                avatarImageRelativePath: avatarImageRelativePath,
                initials: initials,
                avatarHue: avatarHue,
                size: 34,
                peerPhotoUrl: peerPhotoUrl
            )

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if showsVerifiedTitleIcon {
                        VerifiedSealIcon()
                            .accessibilityLabel(Text(LocalizedStringKey("SOS_CHAT_VERIFIED")))
                    }
                    Text(title)
                        .font(.headline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(2)

                secondaryLine
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var secondaryLine: some View {
        if let directStatus, let subtitle, !subtitle.isEmpty {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 8) {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    InlineConnectionPill(status: directStatus, transport: transport)
                }

                HStack(alignment: .center, spacing: 8) {
                    InlineConnectionPill(status: directStatus, transport: transport)

                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                InlineConnectionPill(status: directStatus, transport: transport)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if let directStatus {
            InlineConnectionPill(status: directStatus, transport: transport)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if let subtitle, !subtitle.isEmpty {
            Text(subtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct VerifiedSealIcon: View {
    var body: some View {
        Image(systemName: "checkmark.seal.fill")
            .font(.caption.weight(.semibold))
            .symbolRenderingMode(.palette)
            .foregroundStyle(Color.appPrimary, Color.white)
    }
}

struct ChatAvatarCircleView: View {
    @ObservedObject private var traffic = DisasterTrafficController.shared
    let avatarImageRelativePath: String?
    let initials: String
    let avatarHue: Double
    let size: CGFloat
    var borderColor: Color = .clear
    var borderWidth: CGFloat = 0
    /// A remote internet profile photo (ContactRecord.peerPhotoUrl) for directory/message-added
    /// citizen contacts that have no locally captured BLE avatar. Rendered via AsyncImage, falling
    /// back to the colored initials while it loads and if it fails. Mirrors AuthorityAvatarView and
    /// Android's contact-photo rendering.
    var peerPhotoUrl: String? = nil

    @Environment(\.colorScheme) private var colorScheme
    @State private var avatarImage: UIImage?

    var body: some View {
        ZStack {
            Circle()
                .fill(avatarColor.opacity(colorScheme == .dark ? 0.3 : 0.2))
                .frame(width: size, height: size)

            if let avatarImage {
                Image(uiImage: avatarImage)
                    .resizable()
                    .scaledToFill()
                    .frame(width: size, height: size)
                    .clipShape(Circle())
            } else if let remotePhotoURL {
                AsyncImage(url: traffic.saving ? nil : remotePhotoURL) { phase in
                    if let image = phase.image {
                        image
                            .resizable()
                            .scaledToFill()
                    } else {
                        initialsText
                    }
                }
                .frame(width: size, height: size)
                .clipShape(Circle())
            } else {
                initialsText
            }
        }
        .overlay {
            Circle()
                .stroke(borderColor, lineWidth: borderWidth)
                .frame(width: size, height: size)
        }
        .frame(width: size, height: size)
        .task(id: avatarImageRelativePath) {
            await loadAvatarImage()
        }
    }

    private func loadAvatarImage() async {
        avatarImage = nil

        guard let avatarImageRelativePath else {
            return
        }

        let data = await Task.detached(priority: .utility) {
            SOSChatStore.loadSessionAvatarData(fileName: avatarImageRelativePath)
        }.value

        guard !Task.isCancelled,
              let data,
              let resolvedImage = UIImage(data: data)?.normalizedOrientationImage() else {
            return
        }
        if avatarImageRelativePath == self.avatarImageRelativePath {
            avatarImage = resolvedImage
        }
    }

    private var avatarColor: Color {
        Color(
            hue: avatarHue,
            saturation: colorScheme == .dark ? 0.55 : 0.6,
            brightness: colorScheme == .dark ? 0.78 : 0.88
        )
    }

    private var initialsText: some View {
        Text(initials)
            .font(size >= 48 ? .headline.weight(.semibold) : .subheadline.weight(.semibold))
            .foregroundStyle(avatarColor)
    }

    /// Only http(s) URLs are loaded — a blank/relative value falls back to initials, matching the
    /// AuthorityAvatarView guard.
    private var remotePhotoURL: URL? {
        guard let trimmed = peerPhotoUrl?.trimmingCharacters(in: .whitespacesAndNewlines),
              trimmed.hasPrefix("http"),
              let url = URL(string: trimmed) else {
            return nil
        }
        return url
    }
}

extension RescueConnectionStatus {
    var showsVerifiedTitleIcon: Bool {
        switch self {
        case .connected, .ready:
            return true
        case .connecting, .discovering, .authenticating, .disconnected, .failed:
            return false
        }
    }
}

struct InlineConnectionPill: View {
    let status: RescueConnectionStatus
    var transport: ChatBadgeTransport = .bluetooth

    var body: some View {
        HStack(spacing: 5) {
            if transport == .internet, isConnectedState {
                Image(systemName: "globe")
                    .font(.system(size: 9, weight: .semibold))
                    .foregroundStyle(statusColor)
            } else {
                Circle()
                    .fill(statusColor)
                    .frame(width: 6, height: 6)
            }
            Text(statusTextKey)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.primary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
        }
        .fixedSize(horizontal: true, vertical: false)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(
            Capsule(style: .continuous)
                .fill(Color.whatsAppInputBackground)
        )
        .overlay(
            Capsule(style: .continuous)
                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
        )
    }

    private var isConnectedState: Bool {
        status == .connected || status == .ready
    }

    private var statusTextKey: LocalizedStringKey {
        switch status {
        case .connecting, .discovering, .authenticating:
            return "DIRECT_CHAT_STATUS_CONNECTING"
        case .connected, .ready:
            // Same split as Android's ConnectionStatusBadge: the connected label names the
            // transport that is actually carrying the conversation.
            return transport == .internet
                ? "CHAT_STATUS_INTERNET_AVAILABLE"
                : "CHAT_STATUS_CONNECTED_BLUETOOTH"
        case .disconnected:
            return "DIRECT_CHAT_STATUS_DISCONNECTED"
        case .failed:
            return "DIRECT_CHAT_STATUS_FAILED"
        }
    }

    private var statusColor: Color {
        switch status {
        case .connected, .ready:
            return transport == .internet ? .blue : .green
        case .connecting, .discovering, .authenticating:
            return .orange
        case .failed:
            return .red
        case .disconnected:
            return .gray
        }
    }
}

// Keep the shared Bluetooth status enum out of the pure presentation policy.
private extension ChatConnectionBadgeSnapshot {
    var legacyStatus: RescueConnectionStatus? {
        switch phase {
        case .idle: return nil
        case .connecting: return .connecting
        case .bluetoothConnected: return .ready
        case .internetAvailable: return .connected // The pill explicitly labels this availability.
        case .disconnected: return .disconnected
        case .failed: return .failed
        }
    }
}

/// Owns Firebase's observer independently of the MainActor view model's teardown.
private final class ChatBadgeAuthObservation {
    private let handle: AuthStateDidChangeListenerHandle
    init(changed: @escaping () -> Void) {
        handle = Auth.auth().addStateDidChangeListener { _, _ in changed() }
    }
    deinit { Auth.auth().removeStateDidChangeListener(handle) }
}
