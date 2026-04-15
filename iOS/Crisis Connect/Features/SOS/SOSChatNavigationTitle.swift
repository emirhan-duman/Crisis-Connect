//
//  SOSChatNavigationTitle.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import Combine
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
    @StateObject private var directStatusState: ChatNavigationDirectStatusState

    init(
        sessionId: UUID,
        title: String,
        subtitle: String?,
        initials: String,
        avatarHue: Double,
        avatarImageRelativePath: String?,
        showsVerifiedTrust: Bool,
        allowVerifiedTitleIcon: Bool,
        showsDirectStatus: Bool
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
        _directStatusState = StateObject(
            wrappedValue: ChatNavigationDirectStatusState(sessionId: sessionId)
        )
    }

    var body: some View {
        let directStatus = directStatusState.status
        ChatNavigationTitleView(
            title: title,
            subtitle: subtitle,
            initials: initials,
            avatarHue: avatarHue,
            avatarImageRelativePath: avatarImageRelativePath,
            showsVerifiedTitleIcon: allowVerifiedTitleIcon &&
                (showsVerifiedTrust || directStatus?.showsVerifiedTitleIcon == true),
            directStatus: directStatus
        )
        .task(id: showsDirectStatus) {
            directStatusState.setEnabled(showsDirectStatus)
        }
    }
}

@MainActor
final class ChatNavigationDirectStatusState: ObservableObject {
    @Published private(set) var status: RescueConnectionStatus?

    private let sessionId: UUID
    private var isHostConnected = false
    private var p2pStatus: RescueConnectionStatus = .disconnected
    private var isEnabled = false
    private var screenshotStatusOverride: RescueConnectionStatus?
    private var screenshotStatusTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()

    init(sessionId: UUID) {
        self.sessionId = sessionId
    }

    func setEnabled(_ enabled: Bool) {
        guard enabled != isEnabled else { return }
        isEnabled = enabled

        if enabled {
            bindDeferred()
        } else {
            screenshotStatusTask?.cancel()
            screenshotStatusTask = nil
            screenshotStatusOverride = nil
            cancellables.removeAll()
            isHostConnected = false
            p2pStatus = .disconnected
            status = nil
        }
    }

    private func bindDeferred() {
        cancellables.removeAll()
        screenshotStatusTask?.cancel()
        screenshotStatusTask = nil
        screenshotStatusOverride = nil
        let hostManager = ContactBroadcastManager.shared

        isHostConnected = hostManager.connectedSessionIds.contains(sessionId)
        hostManager.$connectedSessionIds
            .map { [sessionId] in $0.contains(sessionId) }
            .removeDuplicates()
            .receive(on: DispatchQueue.main)
            .sink { [weak self] isConnected in
                guard let self else { return }
                self.isHostConnected = isConnected
                self.recomputeStatus()
            }
            .store(in: &cancellables)

        let capturedSessionId = sessionId
        Task { @MainActor [weak self] in
            let p2pManager = P2pGattChatManager.shared(sessionId: capturedSessionId)
            guard let self, self.isEnabled else { return }
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
            status = screenshotStatusOverride
            return
        }
        if isHostConnected {
            status = .connected
            return
        }
        switch p2pStatus {
        case .connecting, .discovering, .authenticating, .connected, .ready, .failed:
            status = p2pStatus
        case .disconnected:
            status = nil
        }
    }

    private var shouldSimulateScreenshotConnection: Bool {
        AppStoreScreenshotSupport.isAnySceneEnabled &&
        sessionId == AppStoreScreenshotSupport.chatSessionId
    }

    private func startScreenshotConnectionTransition() {
        screenshotStatusOverride = .connecting
        status = .connecting
        screenshotStatusTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled, self.isEnabled else { return }
            self.screenshotStatusOverride = .connected
            self.status = .connected
        }
    }
}

struct ChatNavigationTitleView: View {
    let title: String
    let subtitle: String?
    let initials: String
    let avatarHue: Double
    let avatarImageRelativePath: String?
    let showsVerifiedTitleIcon: Bool
    let directStatus: RescueConnectionStatus?

    var body: some View {
        HStack(spacing: 10) {
            ChatAvatarCircleView(
                avatarImageRelativePath: avatarImageRelativePath,
                initials: initials,
                avatarHue: avatarHue,
                size: 34
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

                    InlineConnectionPill(status: directStatus)
                }

                HStack(alignment: .center, spacing: 8) {
                    InlineConnectionPill(status: directStatus)

                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                InlineConnectionPill(status: directStatus)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if let directStatus {
            InlineConnectionPill(status: directStatus)
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
    let avatarImageRelativePath: String?
    let initials: String
    let avatarHue: Double
    let size: CGFloat
    var borderColor: Color = .clear
    var borderWidth: CGFloat = 0

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
            } else {
                Text(initials)
                    .font(size >= 48 ? .headline.weight(.semibold) : .subheadline.weight(.semibold))
                    .foregroundStyle(avatarColor)
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

    var body: some View {
        HStack(spacing: 5) {
            Circle()
                .fill(statusColor)
                .frame(width: 6, height: 6)
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

    private var statusTextKey: LocalizedStringKey {
        switch status {
        case .connecting, .discovering, .authenticating:
            return "DIRECT_CHAT_STATUS_CONNECTING"
        case .connected, .ready:
            return "DIRECT_CHAT_STATUS_CONNECTED"
        case .disconnected:
            return "DIRECT_CHAT_STATUS_DISCONNECTED"
        case .failed:
            return "DIRECT_CHAT_STATUS_FAILED"
        }
    }

    private var statusColor: Color {
        switch status {
        case .connected, .ready:
            return .green
        case .connecting, .discovering, .authenticating:
            return .orange
        case .failed:
            return .red
        case .disconnected:
            return .gray
        }
    }
}
