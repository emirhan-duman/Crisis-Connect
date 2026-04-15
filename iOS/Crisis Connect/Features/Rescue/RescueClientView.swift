//
//  RescueClientView.swift
//  Crisis Connect
//
//  Created by Assistant on 11.01.2026
//

import SwiftUI
#if canImport(DeviceDiscoveryUI)
import DeviceDiscoveryUI
#endif
#if canImport(WiFiAware)
import WiFiAware
#endif

struct RescueClientView: View {
    @ObservedObject private var manager = RescueClientManager.shared
    @StateObject private var settings = RescueSettingsStore.shared
    @ObservedObject private var chatStore = SOSChatStore.shared

    var body: some View {
        let activeSignals = activeBroadcasts
        let visibleBroadcasts = displayedBroadcasts
        let sessionsById = sessionLookup

        ScrollView {
            LazyVStack(spacing: 18) {
                RescueOverviewCard(
                    isScanning: manager.isScanning,
                    activeCount: activeSignals.count,
                    totalCount: manager.broadcasts.count,
                    lastUpdated: manager.lastUpdated,
                    primaryActionKey: manager.isScanning ? "RESCUE_PAUSE_SCAN" : "RESCUE_START_SCAN",
                    primaryAction: {
                        if manager.isScanning {
                            manager.stopScanning()
                        } else {
                            manager.startScanning()
                        }
                    },
                    secondaryAction: {
                        manager.refresh()
                    }
                )

                if let errorKey = manager.errorMessageKey {
                    RescueErrorBanner(messageKey: errorKey)
                }

                if manager.errorMessageKey == "RESCUE_ERROR_WIFI_AWARE_NO_PAIRED_DEVICES" {
                    RescueWiFiAwarePairingCardContainer {
                        manager.refresh()
                    }
                }

                if visibleBroadcasts.isEmpty {
                    RescueEmptyState(
                        filteringActiveSignals: settings.showOnlyActiveSignals,
                        hasAnySignals: !manager.broadcasts.isEmpty
                    )
                } else {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(LocalizedStringKey("RESCUE_SIGNAL_SECTION_TITLE"))
                            .font(.headline)

                        ForEach(visibleBroadcasts) { broadcast in
                            let session = sessionsById[broadcast.id]
                            NavigationLink {
                                SOSChatDetailScreen(
                                    sessionId: broadcast.id,
                                    sendMessageHandler: { sessionId, text, transportMessageId in
                                        manager.sendMessage(
                                            to: sessionId,
                                            text: text,
                                            transportMessageId: transportMessageId
                                        )
                                    },
                                    readReceiptHandler: { sessionId, transportMessageIds in
                                        manager.sendReadReceipt(
                                            to: sessionId,
                                            transportMessageIds: transportMessageIds
                                        )
                                    },
                                    sendVoiceHandler: { sessionId, audioFileName, mimeType, durationMillis, messageId in
                                        manager.sendVoiceMessage(
                                            to: sessionId,
                                            audioFileName: audioFileName,
                                            mimeType: mimeType,
                                            durationMillis: durationMillis,
                                            messageId: messageId
                                        )
                                    },
                                    sendImageHandler: { sessionId, imageFileName, mimeType, width, height, messageId in
                                        manager.sendImageMessage(
                                            to: sessionId,
                                            imageFileName: imageFileName,
                                            mimeType: mimeType,
                                            width: width,
                                            height: height,
                                            messageId: messageId
                                        )
                                    },
                                    sendFileHandler: { sessionId, data, displayName, mimeType, originalSizeBytes, messageId in
                                        manager.sendFileMessage(
                                            to: sessionId,
                                            data: data,
                                            displayName: displayName,
                                            mimeType: mimeType,
                                            originalSizeBytes: originalSizeBytes,
                                            messageId: messageId
                                        )
                                    }
                                )
                            } label: {
                                RescueSignalCard(
                                    broadcast: broadcast,
                                    session: session,
                                    statusKey: statusLabel(
                                        for: broadcast.status,
                                        autoConnectEnabled: settings.autoConnectToScannedBroadcasts
                                    ),
                                    statusColor: statusColor(for: broadcast.status),
                                    lastSeenText: lastSeenText(for: broadcast),
                                    unreadCount: session?.unreadCount ?? 0,
                                    showDeviceIdentifier: settings.showDeviceIdentifier
                                )
                            }
                            .buttonStyle(.plain)
                            .simultaneousGesture(TapGesture().onEnded {
                                manager.connect(to: broadcast.id)
                            })
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .background(Color.appBackground)
        .navigationTitle("RESCUE_SCREEN_TITLE")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    RescueSettingsView()
                } label: {
                    Image(systemName: "slider.horizontal.3")
                }
                .accessibilityLabel(LocalizedStringKey("RESCUE_SETTINGS_TITLE"))
            }
            ToolbarItem(placement: .topBarTrailing) {
                if manager.isScanning {
                    ProgressView()
                        .progressViewStyle(.circular)
                } else {
                    Button {
                        manager.refresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(LocalizedStringKey("RESCUE_REFRESH"))
                }
            }
        }
        .onAppear {
            manager.bootstrapRuntime()
        }
    }

    private var activeBroadcasts: [RescueBroadcast] {
        manager.broadcasts.filter { $0.status == .ready }
    }

    private var displayedBroadcasts: [RescueBroadcast] {
        if settings.showOnlyActiveSignals {
            return activeBroadcasts
        }
        return manager.broadcasts
    }

    private var sessionLookup: [UUID: SOSChatSession] {
        Dictionary(uniqueKeysWithValues: chatStore.sessions.map { ($0.id, $0) })
    }

    private func statusLabel(
        for status: RescueConnectionStatus,
        autoConnectEnabled: Bool
    ) -> LocalizedStringKey {
        if !autoConnectEnabled {
            switch status {
            case .ready:
                return "RESCUE_STATUS_ACTIVE"
            case .connecting:
                return "RESCUE_STATUS_CONNECTING"
            case .connected:
                return "RESCUE_STATUS_CONNECTED"
            case .discovering:
                return "RESCUE_STATUS_DISCOVERING"
            case .authenticating:
                return "RESCUE_STATUS_AUTHENTICATING"
            case .disconnected, .failed:
                return "RESCUE_STATUS_DETECTED"
            }
        }
        switch status {
        case .connecting:
            return "RESCUE_STATUS_CONNECTING"
        case .connected:
            return "RESCUE_STATUS_CONNECTED"
        case .discovering:
            return "RESCUE_STATUS_DISCOVERING"
        case .authenticating:
            return "RESCUE_STATUS_AUTHENTICATING"
        case .ready:
            return "RESCUE_STATUS_ACTIVE"
        case .disconnected:
            return "RESCUE_STATUS_DISCONNECTED"
        case .failed:
            return "RESCUE_STATUS_FAILED"
        }
    }

    private func statusColor(for status: RescueConnectionStatus) -> Color {
        switch status {
        case .ready:
            return .green
        case .authenticating, .discovering:
            return .orange
        case .connected, .connecting:
            return .blue
        case .disconnected:
            return .secondary
        case .failed:
            return .red
        }
    }

    private func lastSeenText(for broadcast: RescueBroadcast) -> String {
        rescueRelativeFormatter.localizedString(for: broadcast.lastSeen, relativeTo: Date())
    }
}

private struct RescueOverviewCard: View {
    let isScanning: Bool
    let activeCount: Int
    let totalCount: Int
    let lastUpdated: Date?
    let primaryActionKey: String
    let primaryAction: () -> Void
    let secondaryAction: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(LocalizedStringKey("RESCUE_OVERVIEW_HEADING"))
                        .font(.headline)
                    Text(LocalizedStringKey("RESCUE_OVERVIEW_DESCRIPTION"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                RescueScanStatusPill(isScanning: isScanning)
            }

            HStack(spacing: 12) {
                RescueStatPill(labelKey: "RESCUE_OVERVIEW_ACTIVE", value: "\(activeCount)")
                RescueStatPill(labelKey: "RESCUE_OVERVIEW_TOTAL", value: "\(totalCount)")
            }

            if let lastUpdated {
                Text(lastUpdatedLabel(for: lastUpdated))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 10) {
                Button(action: primaryAction) {
                    Text(LocalizedStringKey(primaryActionKey))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppSecondaryButtonStyle())

                Button(action: secondaryAction) {
                    Text(LocalizedStringKey("RESCUE_REFRESH"))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppSecondaryButtonStyle())
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.appRowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.06), lineWidth: 1)
        )
    }

    private func lastUpdatedLabel(for date: Date) -> String {
        let relative = rescueRelativeFormatter.localizedString(for: date, relativeTo: Date())
        let format = NSLocalizedString("RESCUE_LAST_REFRESH_FORMAT", comment: "")
        return String(format: format, relative)
    }
}

private struct RescueScanStatusPill: View {
    let isScanning: Bool

    var body: some View {
        let textKey = isScanning ? "RESCUE_SCANNING_ACTIVE" : "RESCUE_SCANNING_IDLE"
        let color = isScanning ? Color.green : Color.secondary
        Text(LocalizedStringKey(textKey))
            .font(.caption.weight(.semibold))
            .foregroundStyle(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                Capsule()
                    .fill(color.opacity(0.12))
            )
    }
}

private struct RescueStatPill: View {
    let labelKey: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(LocalizedStringKey(labelKey))
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
            Text(value)
                .font(.title3.weight(.semibold))
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.primary.opacity(0.04))
        )
    }
}

private struct RescueSignalCard: View {
    let broadcast: RescueBroadcast
    let session: SOSChatSession?
    let statusKey: LocalizedStringKey
    let statusColor: Color
    let lastSeenText: String
    let unreadCount: Int
    let showDeviceIdentifier: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        Text(displayName)
                            .font(.headline)
                            .lineLimit(1)

                        if session?.showsVerificationBadge == true {
                            Image(systemName: "checkmark.seal.fill")
                                .font(.caption)
                                .foregroundStyle(.green)
                        }
                    }

                    if let broadcastId = broadcast.broadcastId {
                        Text(String(format: NSLocalizedString("RESCUE_BROADCAST_LABEL_FORMAT", comment: ""), broadcastId))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Text(String(format: NSLocalizedString("RESCUE_DEVICE_LABEL_FORMAT", comment: ""), broadcast.peripheralName))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                Spacer()

                VStack(alignment: .trailing, spacing: 6) {
                    Text(statusKey)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusColor)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(
                            Capsule().fill(statusColor.opacity(0.16))
                        )

                    if unreadCount > 0 {
                        Text("\(unreadCount)")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Capsule().fill(Color.red.opacity(0.9)))
                            .foregroundColor(.white)
                    }
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                infoRow(icon: "antenna.radiowaves.left.and.right", text: signalText)
                infoRow(icon: "clock", text: lastSeenLabel)
                if showDeviceIdentifier {
                    infoRow(icon: "number", text: deviceIdentifierText)
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(Color.appRowBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.primary.opacity(0.05), lineWidth: 1)
        )
    }

    private var displayName: String {
        let name = session?.displayName.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !name.isEmpty {
            return name
        }
        if let broadcastId = broadcast.broadcastId, !broadcastId.isEmpty {
            return broadcastId
        }
        return NSLocalizedString("RESCUE_UNKNOWN_DEVICE", comment: "")
    }

    private var signalText: String {
        if let rssi = broadcast.rssi {
            let format = NSLocalizedString("RESCUE_SIGNAL_STRENGTH_FORMAT", comment: "")
            return String(format: format, rssi)
        }
        return NSLocalizedString("RESCUE_SIGNAL_STRENGTH_UNKNOWN", comment: "")
    }

    private var lastSeenLabel: String {
        let format = NSLocalizedString("RESCUE_LAST_SEEN_FORMAT", comment: "")
        return String(format: format, lastSeenText)
    }

    private var deviceIdentifierText: String {
        let format = NSLocalizedString("RESCUE_DEVICE_IDENTIFIER_FORMAT", comment: "")
        return String(format: format, broadcast.peripheralId.uuidString.uppercased())
    }

    private func infoRow(icon: String, text: String) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(text)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

private struct RescueErrorBanner: View {
    let messageKey: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)

            Text(LocalizedStringKey(messageKey))
                .font(.callout)
                .foregroundStyle(.primary)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.orange.opacity(0.12))
        )
    }
}

private struct RescueEmptyState: View {
    let filteringActiveSignals: Bool
    let hasAnySignals: Bool

    var body: some View {
        VStack(spacing: 12) {
            ContentUnavailableView(
                LocalizedStringKey(titleKey),
                systemImage: "antenna.radiowaves.left.and.right"
            )
            Text(LocalizedStringKey(bodyKey))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Text(LocalizedStringKey(supportKey))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
    }

    private var titleKey: String {
        filteringActiveSignals && hasAnySignals ? "RESCUE_EMPTY_FILTERED_TITLE" : "RESCUE_EMPTY_TITLE"
    }

    private var bodyKey: String {
        filteringActiveSignals && hasAnySignals ? "RESCUE_EMPTY_FILTERED_BODY" : "RESCUE_EMPTY_BODY"
    }

    private var supportKey: String {
        filteringActiveSignals && hasAnySignals ? "RESCUE_EMPTY_FILTERED_SUPPORT" : "RESCUE_EMPTY_SUPPORT"
    }
}

#if canImport(DeviceDiscoveryUI) && canImport(WiFiAware)
private struct RescueWiFiAwarePairingCardContainer: View {
    let onSelectionComplete: () -> Void

    var body: some View {
        Group {
            if #available(iOS 26.0, *) {
                RescueWiFiAwarePairingCard(onSelectionComplete: onSelectionComplete)
            } else {
                EmptyView()
            }
        }
    }
}

@available(iOS 26.0, *)
private struct RescueWiFiAwarePairingCard: View {
    let onSelectionComplete: () -> Void

    private let serviceName = "_ccrescue._tcp"

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(LocalizedStringKey("RESCUE_WIFI_AWARE_PAIR_TITLE"))
                .font(.headline)
            Text(LocalizedStringKey("RESCUE_WIFI_AWARE_PAIR_BODY"))
                .font(.footnote)
                .foregroundStyle(.secondary)

            if let provider = pickerProvider {
                DevicePicker(
                    provider,
                    access: .permanent,
                    onSelect: { _ in
                        onSelectionComplete()
                    },
                    label: {
                        Label {
                            Text(LocalizedStringKey("RESCUE_WIFI_AWARE_PAIR_ACTION"))
                        } icon: {
                            Image(systemName: "link.badge.plus")
                        }
                        .frame(maxWidth: .infinity)
                    },
                    fallback: {
                        Text(LocalizedStringKey("RESCUE_WIFI_AWARE_PAIR_FALLBACK"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                )
                .buttonStyle(.borderedProminent)
            } else {
                Text(LocalizedStringKey("RESCUE_WIFI_AWARE_PAIR_FALLBACK"))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.blue.opacity(0.10))
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.blue.opacity(0.25), lineWidth: 1)
        )
    }

    private var pickerProvider: WASubscriberBrowser? {
        guard let service = WASubscribableService.allServices[serviceName] else {
            return nil
        }
        return .wifiAware(.connecting(to: .userSpecifiedDevices, from: service))
    }
}
#else
private struct RescueWiFiAwarePairingCardContainer: View {
    let onSelectionComplete: () -> Void

    var body: some View {
        EmptyView()
    }
}
#endif

#Preview {
    NavigationStack { RescueClientView() }
}

private let rescueRelativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .short
    return formatter
}()
