//
//  SOSChatBubbleComponents.swift
//  Crisis Connect
//
//  Created by Assistant on 27.03.2026.
//

import Combine
import CoreLocation
import Foundation
import MapKit
import PhotosUI
import QuickLook
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct SOSChatBubble: View, Equatable {
    let message: SOSChatMessage
    let layout: ChatLayoutMetrics
    let onImageTap: ((SOSChatMessage) -> Void)?
    let onLocationTap: ((SOSChatMessage) -> Void)?
    let onFileTap: ((SOSChatMessage) -> Void)?
    // Tapping the quoted reply jumps to the original message (nil = not tappable). Closures are
    // excluded from Equatable — identity is (message, layout), which is what drives re-render.
    var onReplyTap: ((String) -> Void)? = nil

    static func == (lhs: SOSChatBubble, rhs: SOSChatBubble) -> Bool {
        lhs.message == rhs.message && lhs.layout == rhs.layout
    }

    var body: some View {
        if message.kind == .call {
            HStack {
                Spacer(minLength: layout.messageEdgeSpacer)
                bubbleContainer
                    .frame(maxWidth: min(maxBubbleWidth, 320), alignment: .center)
                Spacer(minLength: layout.messageEdgeSpacer)
            }
        } else {
            HStack {
                if message.isLocal { Spacer(minLength: layout.messageEdgeSpacer) }
                bubbleContainer
                    .frame(maxWidth: maxBubbleWidth, alignment: message.isLocal ? .trailing : .leading)
                if !message.isLocal { Spacer(minLength: layout.messageEdgeSpacer) }
            }
        }
    }

    private var bubbleContainer: some View {
        bubbleBody
            .padding(.horizontal, 13)
            .padding(.vertical, 10)
            .background(bubbleBackground)
            .overlay(bubbleStroke)
            .shadow(color: Color.black.opacity(0.05), radius: 2, x: 0, y: 1)
    }

    @ViewBuilder
    private var bubbleBackground: some View {
        if message.kind == .call {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(bubbleColor)
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: message.isLocal ? 20 : 8,
                bottomLeadingRadius: 20,
                bottomTrailingRadius: 20,
                topTrailingRadius: message.isLocal ? 8 : 20,
                style: .continuous
            )
            .fill(bubbleColor)
        }
    }

    @ViewBuilder
    private var bubbleStroke: some View {
        if message.kind == .call {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(Color.black.opacity(0.04), lineWidth: 0.5)
        } else {
            UnevenRoundedRectangle(
                topLeadingRadius: message.isLocal ? 20 : 8,
                bottomLeadingRadius: 20,
                bottomTrailingRadius: 20,
                topTrailingRadius: message.isLocal ? 8 : 20,
                style: .continuous
            )
            .stroke(Color.black.opacity(0.04), lineWidth: 0.5)
        }
    }

    @ViewBuilder
    private var bubbleBody: some View {
        switch message.kind {
        case .text:
            let visibleText = P2pSharedTransferSupport.stripReplyMetadata(message.text) ?? message.text
            if let payload = P2pSharedTransferSupport.parseFilePreviewMessage(visibleText) {
                VStack(alignment: .leading, spacing: 8) {
                    if let reply = message.inlineReplyMetadata {
                        ChatReplyQuotedPreview(
                            preview: reply.preview,
                            authorLabel: reply.authorLabel,
                            isLocal: message.isLocal,
                            onTap: reply.targetMessageId.map { targetId in { onReplyTap?(targetId) } }
                        )
                    }
                    ChatSharedFileBubbleContent(
                        payload: payload,
                        onTap: {
                            onFileTap?(message)
                        }
                    )
                    bubbleFooter
                }
            } else {
                VStack(alignment: .leading, spacing: message.inlineReplyMetadata == nil ? 4 : 8) {
                    if let reply = message.inlineReplyMetadata {
                        ChatReplyQuotedPreview(
                            preview: reply.preview,
                            authorLabel: reply.authorLabel,
                            isLocal: message.isLocal,
                            onTap: reply.targetMessageId.map { targetId in { onReplyTap?(targetId) } }
                        )
                    }

                    let displayText = message.visibleTextPreview
                    if !displayText.isEmpty {
                        Text(displayText)
                            .font(.body)
                            .foregroundStyle(.primary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    HStack(spacing: 0) {
                        Spacer(minLength: 0)
                        bubbleFooter
                    }
                }
            }
        case .audio:
            VStack(alignment: .leading, spacing: 6) {
                DeferredAudioMessageBubbleContent(
                    message: message,
                    tint: .whatsAppAccent,
                    layout: layout
                )
                bubbleFooter
            }
        case .image:
            VStack(alignment: .leading, spacing: 8) {
                ChatImageBubbleContent(
                    message: message,
                    maxWidth: maxBubbleWidth,
                    onTap: {
                        onImageTap?(message)
                    }
                )
                bubbleFooter
            }
        case .location:
            VStack(alignment: .leading, spacing: 8) {
                ChatLocationBubbleContent(
                    message: message,
                    maxWidth: maxBubbleWidth,
                    onTap: {
                        onLocationTap?(message)
                    }
                )
                bubbleFooter
            }
        case .call:
            VStack(alignment: .leading, spacing: 8) {
                ChatCallBubbleContent(message: message)
                bubbleFooter
            }
        case .sosAlert:
            // Emergency alert (wire template 202): unmistakable red treatment, mirroring
            // Android's SOS_ALERT message type.
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(Color.appDanger)
                    Text(verbatim: "SOS")
                        .font(.subheadline.weight(.heavy))
                        .foregroundStyle(Color.appDanger)
                }

                Text(message.text)
                    .font(.body.weight(.medium))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                if let mapsURL = Self.firstURL(in: message.text) {
                    Button {
                        UIApplication.shared.open(mapsURL)
                    } label: {
                        Label("SOS_ALERT_OPEN_MAP", systemImage: "map.fill")
                            .font(.subheadline.weight(.semibold))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.appDanger)
                }

                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    bubbleFooter
                }
            }
            .padding(10)
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.appDanger.opacity(0.12))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(Color.appDanger.opacity(0.5), lineWidth: 1)
            )
        }
    }

    private static func firstURL(in text: String) -> URL? {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(text.startIndex..., in: text)
        return detector?.firstMatch(in: text, options: [], range: range)?.url
    }

    @ViewBuilder
    private var bubbleFooter: some View {
        if message.kind == .call {
            Text(timeLabel)
                .font(.caption2)
                .foregroundStyle(.secondary)
        } else if message.isLocal {
            if message.status == .read {
                HStack(spacing: 4) {
                    Text(timeLabel)
                    Text("·")
                    Text("SOS_CHAT_STATUS_READ")
                        .fontWeight(.semibold)
                        .foregroundStyle(Color.whatsAppAccent)
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            } else {
                HStack(spacing: 4) {
                    Image(systemName: statusSymbol(for: message.status))
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(statusColor(for: message.status))
                    Text(timeLabel)
                }
                .font(.caption2)
                .foregroundStyle(.secondary)
            }
        } else {
            Text(timeLabel)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var bubbleColor: Color {
        message.isLocal ? .whatsAppOutgoing : .whatsAppIncoming
    }

    private var maxBubbleWidth: CGFloat {
        layout.bubbleMaxWidth
    }

    private var timeLabel: String {
        Self.timeFormatter.string(from: message.timestamp)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.timeStyle = .short
        formatter.dateStyle = .none
        return formatter
    }()

    private func statusSymbol(for status: SOSChatMessageStatus) -> String {
        switch status {
        case .pending:
            return "clock"
        case .sent:
            return "checkmark"
        case .delivered:
            return "checkmark.circle"
        case .read:
            return "checkmark.circle.fill"
        case .failed:
            return "exclamationmark.circle"
        }
    }

    private func statusColor(for status: SOSChatMessageStatus) -> Color {
        switch status {
        case .pending:
            return .secondary
        case .sent, .delivered:
            return .secondary
        case .read:
            return .whatsAppAccent
        case .failed:
            return .red
        }
    }
}

@MainActor
func copyChatTextToPasteboard(_ text: String?) {
    let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let trimmed, !trimmed.isEmpty else { return }
    UIPasteboard.general.string = trimmed
    UINotificationFeedbackGenerator().notificationOccurred(.success)
}

private struct ChatSwipeReplyModifier: ViewModifier {
    let isEnabled: Bool
    let onReply: () -> Void

    @State private var dragOffset: CGFloat = 0
    @State private var isArmed = false
    @State private var hasTriggeredHaptic = false

    private let maxOffset: CGFloat = 86
    private let triggerThreshold: CGFloat = 58

    func body(content: Content) -> some View {
        content
            .offset(x: dragOffset)
            .overlay(alignment: dragOffset >= 0 ? .leading : .trailing) {
                if isEnabled, abs(dragOffset) > 1 {
                    let progress = min(abs(dragOffset) / triggerThreshold, 1)
                    Image(systemName: "arrowshape.turn.up.left.fill")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(Color.white)
                        .frame(width: 30, height: 30)
                        .background(
                            Circle()
                                .fill(Color.whatsAppAccent.opacity(0.55 + 0.45 * progress))
                        )
                        .scaleEffect(0.82 + 0.18 * progress)
                        .opacity(0.35 + 0.65 * progress)
                        .padding(.horizontal, 8)
                        .allowsHitTesting(false)
                }
            }
            .contentShape(Rectangle())
            .simultaneousGesture(
                DragGesture(minimumDistance: 12)
                    .onChanged { value in
                        guard isEnabled else { return }
                        guard abs(value.translation.width) > abs(value.translation.height) * 1.1 else {
                            if dragOffset != 0 {
                                dragOffset = 0
                            }
                            if isArmed {
                                isArmed = false
                            }
                            if hasTriggeredHaptic {
                                hasTriggeredHaptic = false
                            }
                            return
                        }
                        let clamped = min(max(value.translation.width, -maxOffset), maxOffset)
                        if clamped != dragOffset {
                            dragOffset = clamped
                        }
                        let shouldArm = abs(clamped) >= triggerThreshold
                        if shouldArm != isArmed {
                            isArmed = shouldArm
                            if shouldArm && !hasTriggeredHaptic {
                                UIImpactFeedbackGenerator(style: .rigid).impactOccurred()
                                hasTriggeredHaptic = true
                            } else if !shouldArm {
                                hasTriggeredHaptic = false
                            }
                        }
                    }
                    .onEnded { value in
                        let shouldReply = isEnabled &&
                            abs(value.translation.width) > abs(value.translation.height) * 1.1 &&
                            abs(value.translation.width) >= triggerThreshold
                        isArmed = false
                        hasTriggeredHaptic = false
                        withAnimation(.spring(response: 0.28, dampingFraction: 0.82)) {
                            dragOffset = 0
                        }
                        if shouldReply {
                            onReply()
                            UINotificationFeedbackGenerator().notificationOccurred(.success)
                        }
                    }
            )
    }
}

extension View {
    func chatSwipeReply(
        enabled: Bool = true,
        onReply: @escaping () -> Void
    ) -> some View {
        modifier(ChatSwipeReplyModifier(isEnabled: enabled, onReply: onReply))
    }
}

struct ChatReplyQuotedPreview: View {
    let preview: String
    let authorLabel: String?
    let isLocal: Bool
    let onTap: (() -> Void)?

    init(
        preview: String,
        authorLabel: String?,
        isLocal: Bool,
        onTap: (() -> Void)? = nil
    ) {
        self.preview = preview
        self.authorLabel = authorLabel
        self.isLocal = isLocal
        self.onTap = onTap
    }

    private var titleText: String {
        let trimmed = authorLabel?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty
            ? NSLocalizedString("SOS_CHAT_REPLY_CONTEXT_LABEL", comment: "")
            : trimmed
    }

    private var accentColor: Color {
        .whatsAppAccent.opacity(isLocal ? 0.92 : 1)
    }

    private var backgroundColor: Color {
        isLocal ? Color.white.opacity(0.28) : Color.black.opacity(0.05)
    }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Capsule(style: .continuous)
                .fill(accentColor)
                .frame(width: 3)

            VStack(alignment: .leading, spacing: 3) {
                Text(titleText)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(accentColor)
                    .lineLimit(1)

                Text(preview)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(backgroundColor)
        )
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .onTapGesture {
            onTap?()
        }
    }
}

struct ChatComposerReplyBanner: View {
    let authorLabel: String?
    let preview: String
    let onDismiss: () -> Void

    private var titleText: String {
        let trimmed = authorLabel?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty
            ? NSLocalizedString("SOS_CHAT_REPLY_CONTEXT_LABEL", comment: "")
            : trimmed
    }

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(Color.whatsAppAccent)
                .frame(width: 3, height: 34)

            VStack(alignment: .leading, spacing: 2) {
                Text(titleText)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.whatsAppAccent)
                    .lineLimit(1)

                Text(preview)
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.appTextSecondary)
                    .frame(width: 24, height: 24)
                    .background(
                        Circle()
                            .fill(Color.black.opacity(0.04))
                    )
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("Close"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(Color.whatsAppInputBackground)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Color.whatsAppInputBorder, lineWidth: 1)
        )
    }
}

enum SOSChatCallPresentation {
    static func summaryText(for message: SOSChatMessage) -> String {
        message.callEventPreviewText ?? message.text
    }

    static func iconName(for message: SOSChatMessage) -> String {
        guard let result = message.callResult else {
            return "phone.fill"
        }
        switch result {
        case .answered:
            return message.callDirection == .outgoing
                ? "phone.arrow.up.right.fill"
                : "phone.arrow.down.left.fill"
        case .missed:
            return "phone.arrow.down.left.fill"
        case .rejected:
            return "phone.down.fill"
        case .canceled:
            return message.callDirection == .outgoing
                ? "phone.arrow.up.right.fill"
                : "phone.arrow.down.left.fill"
        }
    }

    static func iconTint(for message: SOSChatMessage) -> Color {
        guard let result = message.callResult else {
            return .appTextSecondary
        }
        switch result {
        case .answered:
            return .appPrimary
        case .missed, .rejected:
            return .appDanger
        case .canceled:
            return .appTextSecondary
        }
    }

    static func durationText(for message: SOSChatMessage) -> String? {
        guard message.callResult == .answered,
              let durationMillis = message.callDurationMillis,
              durationMillis > 0 else {
            return nil
        }
        let totalSeconds = max(0, durationMillis) / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%02d:%02d", minutes, seconds)
    }
}

struct ChatCallBubbleContent: View {
    let message: SOSChatMessage

    private var iconName: String {
        SOSChatCallPresentation.iconName(for: message)
    }

    private var iconTint: Color {
        SOSChatCallPresentation.iconTint(for: message)
    }

    private var summaryText: String {
        SOSChatCallPresentation.summaryText(for: message)
    }

    private var durationText: String? {
        SOSChatCallPresentation.durationText(for: message)
    }

    var body: some View {
        HStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(iconTint.opacity(0.14))
                    .frame(width: 34, height: 34)

                Image(systemName: iconName)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(iconTint)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(summaryText)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)

                if let durationText {
                    Text(durationText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .monospacedDigit()
                }
            }

            Spacer(minLength: 0)
        }
    }
}

/// Android's ScrollToBottomButton: a small circular FAB with a down chevron and an unread badge,
/// floated over the transcript while it is scrolled away from the newest message. Shared by the
/// citizen chat and the authority (kurum) thread.
struct ChatScrollToBottomButton: View {
    let count: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle()
                    .fill(Color.appSurfaceElevated)
                    .frame(width: 40, height: 40)
                    .overlay(Circle().stroke(Color.appBorder, lineWidth: 1))
                    .shadow(color: .black.opacity(0.18), radius: 5, y: 2)
                Image(systemName: "chevron.down")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color.appPrimary)
            }
            .overlay(alignment: .topTrailing) {
                if count > 0 {
                    Text("\(min(count, 99))")
                        .font(.caption2.weight(.bold))
                        .monospacedDigit()
                        .foregroundStyle(.white)
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(Capsule(style: .continuous).fill(Color.appPrimary))
                        .offset(x: 6, y: -6)
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text("CHAT_SCROLL_TO_BOTTOM"))
    }
}

struct PendingChatImage {
    let sourceData: Data
    let previewImage: UIImage
}

struct ChatImageSelection {
    let data: Data
}

enum ChatLocationPresentation {
    static func coordinate(for message: SOSChatMessage) -> CLLocationCoordinate2D? {
        guard let latitude = message.locationLatitude,
              let longitude = message.locationLongitude else {
            return nil
        }
        return CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    static func coordinateText(for message: SOSChatMessage) -> String? {
        guard let latitude = message.locationLatitude,
              let longitude = message.locationLongitude else {
            return nil
        }
        return coordinateText(latitude: latitude, longitude: longitude)
    }

    static func coordinateText(latitude: Double, longitude: Double) -> String {
        String(
            format: NSLocalizedString("SOS_CHAT_LOCATION_COORDINATES_FORMAT", comment: ""),
            String(format: "%.5f", latitude),
            String(format: "%.5f", longitude)
        )
    }

    static func accuracyText(for accuracyMeters: Double?) -> String? {
        guard let accuracyMeters else { return nil }
        let formatter = MeasurementFormatter()
        formatter.unitOptions = .providedUnit
        formatter.unitStyle = .short
        let measurement = Measurement(value: accuracyMeters, unit: UnitLength.meters)
        return String(
            format: NSLocalizedString("SOS_CHAT_LOCATION_ACCURACY_FORMAT", comment: ""),
            formatter.string(from: measurement)
        )
    }

    static func spanMeters(for accuracyMeters: Double?, interactive: Bool) -> CLLocationDistance {
        let normalizedAccuracy = max(accuracyMeters ?? 0, 0)
        let base: CLLocationDistance = interactive ? 1_400 : 650
        let multiplier = interactive ? 6.0 : 4.0
        return max(base, normalizedAccuracy * multiplier)
    }
}

/// Streams the device's own location while the open chat has a live Bluetooth link, so the
/// user's OWN shared-location bubble tracks where they are NOW (Android's live-location bubble:
/// `shouldShowLiveLocation = isLocal && isBluetoothConnected && ownLocation != null`). Runs only
/// while a chat screen explicitly activates it; deactivating stops the GPS immediately.
final class ChatLiveOwnLocationTracker: NSObject, ObservableObject, CLLocationManagerDelegate {
    static let shared = ChatLiveOwnLocationTracker()

    @Published private(set) var activeSessionId: UUID?
    @Published private(set) var location: CLLocation?

    private let manager = CLLocationManager()
    private var pollTask: Task<Void, Never>?
    private var isUpdating = false

    private override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
    }

    /// Arms the tracker for the open chat: `linkProbe` is polled every few seconds and the GPS
    /// only runs while it reports a live Bluetooth link — exactly Android's
    /// `isLocal && isBluetoothConnected && ownLocation != null` condition, self-contained.
    @MainActor
    func activate(sessionId: UUID, linkProbe: @escaping () -> Bool) {
        deactivate()
        activeSessionId = sessionId
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                self?.setUpdating(linkProbe())
                try? await Task.sleep(nanoseconds: 5_000_000_000)
            }
        }
    }

    @MainActor
    func deactivate() {
        pollTask?.cancel()
        pollTask = nil
        activeSessionId = nil
        setUpdating(false)
    }

    @MainActor
    private func setUpdating(_ on: Bool) {
        if on {
            guard !isUpdating else { return }
            let status = manager.authorizationStatus
            guard status == .authorizedWhenInUse || status == .authorizedAlways else { return }
            isUpdating = true
            manager.startUpdatingLocation()
        } else {
            guard isUpdating else { return }
            isUpdating = false
            manager.stopUpdatingLocation()
            location = nil
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let latest = locations.last else { return }
        Task { @MainActor in
            guard self.isUpdating else { return }
            self.location = latest
        }
    }
}

struct ChatLocationBubbleContent: View {
    let message: SOSChatMessage
    let maxWidth: CGFloat
    let onTap: (() -> Void)?

    @ObservedObject private var liveTracker = ChatLiveOwnLocationTracker.shared

    /// Android parity: while this chat holds a live Bluetooth link, my own location bubble tracks
    /// my CURRENT position (and says "Live location") instead of the point captured at send time.
    private var liveOwnLocation: CLLocation? {
        guard message.isLocal, liveTracker.activeSessionId != nil else { return nil }
        return liveTracker.location
    }

    private var coordinate: CLLocationCoordinate2D? {
        liveOwnLocation?.coordinate ?? ChatLocationPresentation.coordinate(for: message)
    }

    private var coordinateText: String? {
        if let live = liveOwnLocation {
            return ChatLocationPresentation.coordinateText(
                latitude: live.coordinate.latitude, longitude: live.coordinate.longitude
            )
        }
        return ChatLocationPresentation.coordinateText(for: message)
    }

    private var accuracyText: String? {
        ChatLocationPresentation.accuracyText(
            for: liveOwnLocation.map { $0.horizontalAccuracy } ?? message.locationHorizontalAccuracyMeters
        )
    }

    @ViewBuilder
    private var bubbleContents: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let coordinate {
                ChatLocationMapCard(
                    coordinate: coordinate,
                    accuracyMeters: liveOwnLocation.map { $0.horizontalAccuracy }
                        ?? message.locationHorizontalAccuracyMeters,
                    interactive: false,
                    height: 154,
                    cornerRadius: 16
                )
            }

            HStack(alignment: .top, spacing: 10) {
                ZStack {
                    Circle()
                        .fill(Color.whatsAppAccent.opacity(0.16))
                        .frame(width: 34, height: 34)
                    Image(systemName: "location.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.whatsAppAccent)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(liveOwnLocation != nil
                        ? NSLocalizedString("CHAT_LOCATION_PREVIEW_LABEL_LIVE", comment: "")
                        : SOSChatStore.locationPreviewText())
                        .font(.headline)
                        .foregroundStyle(.primary)
                        .fixedSize(horizontal: false, vertical: true)

                    if let coordinateText {
                        Text(coordinateText)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if let accuracyText {
                        Text(accuracyText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }

                if coordinate != nil {
                    Spacer(minLength: 8)
                    Image(systemName: "arrow.up.left.and.arrow.down.right.circle.fill")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(Color.whatsAppAccent.opacity(0.9))
                        .padding(.top, 2)
                }
            }
        }
        .frame(maxWidth: maxWidth, alignment: .leading)
    }

    var body: some View {
        if let onTap, coordinate != nil {
            Button(action: onTap) {
                bubbleContents
            }
            .buttonStyle(.plain)
        } else {
            bubbleContents
        }
    }
}

struct ChatLocationMapCard: View {
    let coordinate: CLLocationCoordinate2D
    let accuracyMeters: Double?
    let interactive: Bool
    let height: CGFloat
    let cornerRadius: CGFloat

    private var spanMeters: CLLocationDistance {
        ChatLocationPresentation.spanMeters(for: accuracyMeters, interactive: interactive)
    }

    var body: some View {
        ZStack(alignment: .topTrailing) {
            ChatLocationMapView(
                coordinate: coordinate,
                accuracyMeters: accuracyMeters,
                interactive: interactive,
                spanMeters: spanMeters
            )
            .frame(height: height)

            Image(systemName: interactive ? "location.viewfinder" : "map.fill")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white)
                .padding(8)
                .background(Color.black.opacity(0.35), in: Capsule(style: .continuous))
                .padding(10)
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(Color.black.opacity(0.08), lineWidth: 1)
        }
    }
}

struct ChatLocationDetailSheet: View {
    let message: SOSChatMessage

    @Environment(\.dismiss) private var dismiss

    private var coordinate: CLLocationCoordinate2D? {
        ChatLocationPresentation.coordinate(for: message)
    }

    private var coordinateText: String? {
        ChatLocationPresentation.coordinateText(for: message)
    }

    private var accuracyText: String? {
        ChatLocationPresentation.accuracyText(for: message.locationHorizontalAccuracyMeters)
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if let coordinate {
                    ChatLocationMapCard(
                        coordinate: coordinate,
                        accuracyMeters: message.locationHorizontalAccuracyMeters,
                        interactive: true,
                        height: 320,
                        cornerRadius: 0
                    )
                } else {
                    ContentUnavailableView(
                        SOSChatStore.locationPreviewText(),
                        systemImage: "location.slash"
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.appBackground)
                }

                VStack(alignment: .leading, spacing: 14) {
                    Text(SOSChatStore.locationPreviewText())
                        .font(.title3.weight(.semibold))

                    if let coordinateText {
                        Label(coordinateText, systemImage: "mappin.and.ellipse")
                            .font(.body)
                            .foregroundStyle(.primary)
                    }

                    if let accuracyText {
                        Label(accuracyText, systemImage: "scope")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }

                    Label(message.timestamp.formatted(date: .abbreviated, time: .shortened), systemImage: "clock")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)

                Spacer(minLength: 0)
            }
            .background(Color.appBackground)
            .navigationTitle(SOSChatStore.locationPreviewText())
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel(Text("Close"))
                }
            }
        }
    }
}

struct ChatLocationMapView: UIViewRepresentable {
    let coordinate: CLLocationCoordinate2D
    let accuracyMeters: Double?
    let interactive: Bool
    let spanMeters: CLLocationDistance

    func makeUIView(context: Context) -> MKMapView {
        let mapView = MKMapView(frame: .zero)
        mapView.delegate = context.coordinator
        mapView.showsCompass = interactive
        mapView.showsScale = interactive
        mapView.isRotateEnabled = false
        mapView.isPitchEnabled = false
        mapView.pointOfInterestFilter = .excludingAll
        mapView.showsUserLocation = false
        return mapView
    }

    func updateUIView(_ mapView: MKMapView, context: Context) {
        mapView.isScrollEnabled = interactive
        mapView.isZoomEnabled = interactive
        mapView.isUserInteractionEnabled = interactive

        let region = MKCoordinateRegion(
            center: coordinate,
            latitudinalMeters: spanMeters,
            longitudinalMeters: spanMeters
        )

        if context.coordinator.shouldUpdateRegion(region) {
            mapView.setRegion(region, animated: false)
            context.coordinator.lastRegion = region
        }

        context.coordinator.updateLocation(
            on: mapView,
            coordinate: coordinate,
            accuracyMeters: accuracyMeters
        )
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    final class Coordinator: NSObject, MKMapViewDelegate {
        var lastRegion: MKCoordinateRegion?
        private var annotation: MKPointAnnotation?
        private var accuracyOverlay: MKCircle?
        private let annotationReuseIdentifier = "ChatLocationPin"

        func shouldUpdateRegion(_ region: MKCoordinateRegion) -> Bool {
            guard let lastRegion else { return true }
            return abs(lastRegion.center.latitude - region.center.latitude) > 0.0001 ||
                abs(lastRegion.center.longitude - region.center.longitude) > 0.0001 ||
                abs(lastRegion.span.latitudeDelta - region.span.latitudeDelta) > 0.0001 ||
                abs(lastRegion.span.longitudeDelta - region.span.longitudeDelta) > 0.0001
        }

        func updateLocation(on mapView: MKMapView, coordinate: CLLocationCoordinate2D, accuracyMeters: Double?) {
            if let annotation {
                annotation.coordinate = coordinate
            } else {
                let annotation = MKPointAnnotation()
                annotation.coordinate = coordinate
                mapView.addAnnotation(annotation)
                self.annotation = annotation
            }

            if let accuracyOverlay {
                mapView.removeOverlay(accuracyOverlay)
                self.accuracyOverlay = nil
            }

            if let accuracyMeters, accuracyMeters > 0 {
                let overlay = MKCircle(center: coordinate, radius: accuracyMeters)
                mapView.addOverlay(overlay)
                accuracyOverlay = overlay
            }
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard !(annotation is MKUserLocation) else { return nil }
            let view = (mapView.dequeueReusableAnnotationView(withIdentifier: annotationReuseIdentifier) as? MKMarkerAnnotationView)
                ?? MKMarkerAnnotationView(annotation: annotation, reuseIdentifier: annotationReuseIdentifier)
            view.annotation = annotation
            view.canShowCallout = false
            view.markerTintColor = .systemBlue
            view.glyphImage = UIImage(systemName: "location.fill")
            return view
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let circle = overlay as? MKCircle else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let renderer = MKCircleRenderer(circle: circle)
            renderer.fillColor = UIColor.systemBlue.withAlphaComponent(0.14)
            renderer.strokeColor = UIColor.systemBlue.withAlphaComponent(0.4)
            renderer.lineWidth = 1
            return renderer
        }
    }
}

struct PreviewedSharedFile: Identifiable {
    let url: URL

    var id: String { url.path }
}

enum ChatAttachmentAction: String, Identifiable {
    case camera
    case photoLibrary
    case document
    case offlineMap
    case location

    var id: String { rawValue }
}

enum ChatMediaPickerSource: String, Identifiable {
    case camera
    case photoLibrary

    var id: String { rawValue }
}

struct ChatSharedFileBubbleContent: View {
    let payload: P2pSharedFilePayload
    let onTap: () -> Void

    private var sizeText: String {
        ByteCountFormatter.string(fromByteCount: Int64(payload.originalSizeBytes), countStyle: .file)
    }

    var body: some View {
        Button(action: onTap) {
            HStack(alignment: .center, spacing: 10) {
                ZStack {
                    Circle()
                        .fill(Color.whatsAppAccent.opacity(0.16))
                        .frame(width: 34, height: 34)
                    Image(systemName: "doc.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.whatsAppAccent)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(SOSChatStore.sharedFilePreviewText())
                        .font(.headline)
                        .foregroundStyle(.primary)
                    Text(payload.displayName)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    HStack(spacing: 6) {
                        Text(sizeText)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if payload.compression.caseInsensitiveCompare(P2pSharedTransferSupport.fileCompressionNone) != .orderedSame {
                            Text(payload.compression.uppercased())
                                .font(.caption2.weight(.semibold))
                                .foregroundStyle(Color.whatsAppAccent)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
    }
}

struct ChatImageBubbleContent: View {
    let message: SOSChatMessage
    let maxWidth: CGFloat
    let onTap: () -> Void

    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Button(action: onTap) {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFill()
                        .frame(width: displaySize.width, height: displaySize.height)
                        .clipped()
                        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                }
                .buttonStyle(.plain)
            } else {
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .fill(Color.primary.opacity(0.08))
                    .frame(width: displaySize.width, height: displaySize.height)
                    .overlay {
                        Image(systemName: "photo")
                            .font(.title3.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
            }
        }
        .task(id: taskKey) {
            await loadImage()
        }
    }

    private var displaySize: CGSize {
        let maxDisplayWidth = min(maxWidth, 240)
        guard let width = message.imageWidth,
              let height = message.imageHeight,
              width > 0,
              height > 0 else {
            return CGSize(width: maxDisplayWidth, height: maxDisplayWidth * 0.72)
        }
        let aspectRatio = CGFloat(height) / CGFloat(width)
        let resolvedHeight = min(280, max(120, maxDisplayWidth * aspectRatio))
        return CGSize(width: maxDisplayWidth, height: resolvedHeight)
    }

    private var taskKey: String {
        message.imageThumbnailRelativePath ?? message.imageRelativePath ?? message.id.uuidString
    }

    private func loadImage() async {
        image = nil

        let thumbnailPath = message.imageThumbnailRelativePath
        let imagePath = message.imageRelativePath
        let data = await Task.detached(priority: .userInitiated) { () -> Data? in
            if let thumbnailPath,
               let data = SOSChatStore.loadImageData(fileName: thumbnailPath, thumbnail: true) {
                return data
            }
            guard let imagePath else { return nil }
            return SOSChatStore.loadImageData(fileName: imagePath)
        }.value

        guard !Task.isCancelled,
              let data,
              let resolvedImage = UIImage(data: data)?.normalizedOrientationImage() else {
            return
        }
        if taskKey == (message.imageThumbnailRelativePath ?? message.imageRelativePath ?? message.id.uuidString) {
            self.image = resolvedImage
            return
        }
    }
}

struct ChatImageViewer: View {
    let message: SOSChatMessage
    let onDismiss: () -> Void

    @State private var image: UIImage?

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color.black.ignoresSafeArea()

            Group {
                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .scaledToFit()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 28)
                } else {
                    ProgressView()
                        .tint(.white)
                }
            }

            Button(action: onDismiss) {
                Image(systemName: "xmark")
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .frame(width: 40, height: 40)
                    .background(Circle().fill(Color.black.opacity(0.45)))
            }
            .buttonStyle(.plain)
            .padding(.top, 18)
            .padding(.trailing, 18)
        }
        .task(id: message.id) {
            await loadImage()
        }
    }

    private func loadImage() async {
        image = nil

        guard let imagePath = message.imageRelativePath else {
            return
        }

        let data = await Task.detached(priority: .userInitiated) {
            SOSChatStore.loadImageData(fileName: imagePath)
        }.value

        guard !Task.isCancelled,
              let data,
              let resolvedImage = UIImage(data: data)?.normalizedOrientationImage() else {
            return
        }
        if imagePath == message.imageRelativePath {
            self.image = resolvedImage
        }
    }
}

struct ImageAttachmentConfirmationView: View {
    let image: UIImage
    let isSending: Bool
    let onDiscard: () -> Void
    let onSend: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: 56, height: 56)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text("Photo")
                    .font(.subheadline.weight(.semibold))
                Text("Ready to send")
                    .font(.caption)
                    .foregroundStyle(.secondary)
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

struct ChatAttachmentOptionsSheet: View {
    let cameraAvailable: Bool
    let showsDocument: Bool
    let showsLocation: Bool
    let onSelect: (ChatAttachmentAction) -> Void

    @Environment(\.colorScheme) private var colorScheme

    private let columns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10),
    ]

    var body: some View {
        VStack(spacing: 14) {
            Capsule(style: .continuous)
                .fill(Color.appBorder.opacity(0.95))
                .frame(width: 36, height: 4)
                .padding(.top, 8)

            LazyVGrid(columns: columns, spacing: 10) {
                ChatAttachmentOptionCard(
                    title: "Camera",
                    systemName: "camera.fill",
                    tint: .orange,
                    isEnabled: cameraAvailable,
                    action: {
                        onSelect(.camera)
                    }
                )

                ChatAttachmentOptionCard(
                    title: "Gallery",
                    systemName: "photo.on.rectangle.angled",
                    tint: .appPrimary,
                    action: {
                        onSelect(.photoLibrary)
                    }
                )

                if showsDocument {
                    ChatAttachmentOptionCard(
                        title: "Document",
                        systemName: "doc.fill",
                        tint: .whatsAppAccent,
                        action: {
                            onSelect(.document)
                        }
                    )
                }

                if showsLocation {
                    ChatAttachmentOptionCard(
                        title: "Location",
                        systemName: "location.fill",
                        tint: .green,
                        action: {
                            onSelect(.location)
                        }
                    )
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.bottom, 14)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Color.appSurface)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Color.appBorder, lineWidth: 1)
        )
        .shadow(
            color: .black.opacity(colorScheme == .dark ? 0.2 : 0.08),
            radius: 14,
            y: 8
        )
    }
}

struct ChatDocumentPicker: UIViewControllerRepresentable {
    let onSelect: (URL?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onSelect: (URL?) -> Void

        init(onSelect: @escaping (URL?) -> Void) {
            self.onSelect = onSelect
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onSelect(nil)
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onSelect(urls.first)
        }
    }
}

struct ChatSharedFilePreview: UIViewControllerRepresentable {
    let url: URL

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url)
    }

    func makeUIViewController(context: Context) -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QLPreviewController, context: Context) {
        context.coordinator.url = url
        uiViewController.reloadData()
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        var url: URL

        init(url: URL) {
            self.url = url
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
            1
        }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }
    }
}

struct ChatAttachmentOptionCard: View {
    let title: String
    let systemName: String
    let tint: Color
    var isEnabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 10) {
                ZStack {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [tint.opacity(0.24), tint.opacity(0.12)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )

                    Image(systemName: systemName)
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(tint)
                }
                .frame(width: 44, height: 44)

                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, minHeight: 92)
            .padding(.horizontal, 10)
            .padding(.vertical, 12)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Color.whatsAppInputBackground)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(tint.opacity(0.16), lineWidth: 1)
            )
            .overlay(alignment: .topTrailing) {
                if !isEnabled {
                    Image(systemName: "slash.circle.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.appTextSecondary)
                        .padding(8)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled)
        .opacity(isEnabled ? 1 : 0.56)
    }
}

struct ChatImagePicker: UIViewControllerRepresentable {
    let source: ChatMediaPickerSource
    let onSelect: (ChatImageSelection?) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onSelect: onSelect)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        switch source {
        case .camera:
            if UIImagePickerController.isSourceTypeAvailable(.camera) {
                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.delegate = context.coordinator
                picker.mediaTypes = ["public.image"]
                picker.allowsEditing = false
                return picker
            }
            fallthrough
        case .photoLibrary:
            var configuration = PHPickerConfiguration(photoLibrary: .shared())
            configuration.filter = .images
            configuration.selectionLimit = 1
            configuration.preferredAssetRepresentationMode = .current
            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = context.coordinator
            return picker
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate, PHPickerViewControllerDelegate {
        private let onSelect: (ChatImageSelection?) -> Void

        init(onSelect: @escaping (ChatImageSelection?) -> Void) {
            self.onSelect = onSelect
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onSelect(nil)
        }

        func imagePickerController(
            _ picker: UIImagePickerController,
            didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey : Any]
        ) {
            if let image = info[.originalImage] as? UIImage,
               let data = image.jpegData(compressionQuality: 1) {
                onSelect(ChatImageSelection(data: data))
            } else {
                onSelect(nil)
            }
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider else {
                onSelect(nil)
                return
            }
            if provider.canLoadObject(ofClass: UIImage.self) {
                provider.loadObject(ofClass: UIImage.self) { object, _ in
                    let selection: ChatImageSelection?
                    if let image = object as? UIImage,
                       let data = image.jpegData(compressionQuality: 1) {
                        selection = ChatImageSelection(data: data)
                    } else {
                        selection = nil
                    }
                    DispatchQueue.main.async {
                        self.onSelect(selection)
                    }
                }
                return
            }
            onSelect(nil)
        }
    }
}

/// WhatsApp-style day divider between messages from different calendar days (Android parity).
struct ChatDaySeparator: View {
    let label: String

    var body: some View {
        HStack {
            Spacer()
            Text(label)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.vertical, 4)
                .background(
                    Capsule(style: .continuous).fill(Color.appSurface.opacity(0.9))
                )
            Spacer()
        }
        .padding(.vertical, 6)
    }
}

/// Incoming-styled "peer is typing" bubble with three pulsing dots — the timeline twin of
/// Android's TypingIndicatorBubble, shown as the last transcript row while a typing pulse is live.
/// TimelineView drives the animation (self-cleaning: no Timer to leak when the row disappears).
struct ChatTypingBubble: View {
    var body: some View {
        HStack {
            TimelineView(.periodic(from: .now, by: 0.35)) { context in
                let step = Int(context.date.timeIntervalSinceReferenceDate / 0.35) % 3
                HStack(spacing: 5) {
                    ForEach(0..<3, id: \.self) { index in
                        Circle()
                            .fill(Color.secondary)
                            .frame(width: 7, height: 7)
                            .opacity(step == index ? 1.0 : 0.35)
                            .scaleEffect(step == index ? 1.15 : 0.85)
                            .animation(.easeInOut(duration: 0.3), value: step)
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 12)
                .background(
                    UnevenRoundedRectangle(
                        topLeadingRadius: 8,
                        bottomLeadingRadius: 20,
                        bottomTrailingRadius: 20,
                        topTrailingRadius: 20,
                        style: .continuous
                    )
                    .fill(Color.appSurface)
                )
            }
            Spacer(minLength: 40)
        }
    }
}
