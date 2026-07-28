//
//  SosContactNotifier.swift
//  Crisis Connect
//
//  Stage 3 of the SOS escalation — the iOS port of Android's SosContactNotifier: automatically
//  message the victim's emergency contacts over the internet relay.
//
//  Recipient selection (v1: automatic only): internet-capable contacts ranked by message volume
//  (more = closer) and by how long they've been a contact (older = closer); the combined rank
//  picks the top three. Alerts go out as template-202 messages (an unmistakable SOS text with a
//  maps link); while the SOS runs, movement streams as template-203 live updates that rewrite
//  the SAME bubble in place on both ends. Stopping the SOS sends a plain-text all-clear; one
//  that can't leave the phone (offline stop) is persisted and retried on the next foreground.
//

import Combine
import CoreLocation
import Foundation

final class SosContactNotifier: ObservableObject, @unchecked Sendable {
    static let shared = SosContactNotifier()

    /// Per-recipient delivery state surfaced on the active-SOS screen so the victim can see who is
    /// being alerted and whether each alert actually went out (Android's SosContactNotifier.Recipient).
    enum RecipientStatus: String, Sendable {
        case waiting
        case sending
        case sent
        case failed
    }

    /// Whether the alerted set came from the victim's hand-picked emergency contacts / confirmed
    /// parents (custom) or the automatic "closest contacts" blend.
    enum SelectionSource: Sendable {
        case custom
        case automatic
    }

    struct Recipient: Identifiable, Equatable, Sendable {
        let sessionId: UUID
        let name: String
        var status: RecipientStatus
        var id: UUID { sessionId }
    }

    /// The contacts this SOS is alerting, with live per-contact status. Observed by the active-SOS UI.
    @Published private(set) var recipients: [Recipient] = []
    /// True once recipient selection finished — distinguishes "still choosing" from "no one to notify".
    @Published private(set) var selectionReady = false
    @Published private(set) var selectionSource: SelectionSource = .automatic

    private static let maxRecipients = 3
    private static let initialLocationWait: TimeInterval = 12
    private static let loopTick: TimeInterval = 15
    private static let initialRetryInterval: TimeInterval = 30
    private static let locationRefreshInterval: TimeInterval = 60
    private static let liveUpdateMinInterval: TimeInterval = 60
    private static let liveUpdateMinMoveMeters: CLLocationDistance = 25
    private static let pendingAllClearKey = "sos.contactNotifier.pendingAllClearSessions"

    private struct AlertedRecipient {
        let contact: ContactRecord
        let alertMessageId: String
    }

    private var job: Task<Void, Never>?
    private var alerted: [UUID: AlertedRecipient] = [:]

    private init() {}

    // MARK: - Recipient-status publishing (all @Published writes hop to the main actor)

    @MainActor
    private func resetRecipientState() {
        recipients = []
        selectionReady = false
    }

    @MainActor
    private func publishSelection(_ targets: [ContactRecord], source: SelectionSource) {
        selectionSource = source
        recipients = targets.map { Recipient(sessionId: $0.id, name: $0.name, status: .waiting) }
        selectionReady = true
    }

    @MainActor
    private func publishStatus(_ sessionId: UUID, _ status: RecipientStatus) {
        guard let index = recipients.firstIndex(where: { $0.sessionId == sessionId }) else { return }
        recipients[index].status = status
    }

    func onSosStarted() {
        guard !PlatformRuntime.isRunningTests else { return }
        job?.cancel()
        alerted = [:]
        // A fresh SOS supersedes any all-clear still waiting from the previous session.
        UserDefaults.standard.removeObject(forKey: Self.pendingAllClearKey)

        job = Task { [weak self] in
            guard let self else { return }
            await self.resetRecipientState()
            let selection = await MainActor.run { Self.selectRecipients() }
            let targets = selection.contacts
            await self.publishSelection(targets, source: selection.source)
            guard !targets.isEmpty else {
                NSLog("SosContactNotifier: no internet-capable contacts to notify")
                return
            }
            var pending: [UUID: ContactRecord] = Dictionary(
                targets.map { ($0.id, $0) },
                uniquingKeysWith: { first, _ in first }
            )

            // A fix makes the first alert far more useful; wait briefly, then run with what we have.
            var lastFix = await Self.waitForFix(timeout: Self.initialLocationWait)
            var lastFixFetchedAt = Date()
            var lastSentFix: SOSSignalLocationPayload.GPSPayload?
            var lastInitialAttemptAt = Date.distantPast
            var lastLiveUpdateAt = Date.distantPast

            while !Task.isCancelled {
                let sessionActive = await MainActor.run { SOSBroadcastManager.shared.isSessionActive }
                guard sessionActive else { break }
                let now = Date()

                // Refresh the fix on its own cadence so the GPS isn't hammered every tick.
                if now.timeIntervalSince(lastFixFetchedAt) >= Self.locationRefreshInterval {
                    if let fix = await MainActor.run(body: {
                        SOSSignalLocationCoordinator.shared.currentVictimLocationPayload()
                    }) {
                        lastFix = fix
                    }
                    lastFixFetchedAt = now
                }

                guard InternetChatTransport.shared.isAvailable() else {
                    try? await Task.sleep(nanoseconds: UInt64(Self.loopTick * 1_000_000_000))
                    continue
                }

                // Initial alerts — rebuilt each attempt so a late send still ships a fresh fix.
                if !pending.isEmpty, now.timeIntervalSince(lastInitialAttemptAt) >= Self.initialRetryInterval {
                    lastInitialAttemptAt = now
                    let text = Self.buildSosMessage(location: lastFix)
                    for contact in Array(pending.values) {
                        await self.publishStatus(contact.id, .sending)
                        let messageId = UUID().uuidString
                        let sent = await InternetChatTransport.shared.sendText(
                            messageId: messageId,
                            text: text,
                            contact: contact,
                            priority: "high",
                            templateCode: InternetChatTransport.sosAlertTemplate
                        )
                        if sent {
                            pending.removeValue(forKey: contact.id)
                            self.alerted[contact.id] = AlertedRecipient(
                                contact: contact, alertMessageId: messageId
                            )
                            lastSentFix = lastFix
                            await MainActor.run {
                                SOSChatStore.shared.ensureSession(
                                    id: contact.id, displayName: contact.name, role: .unknown
                                )
                                _ = SOSChatStore.shared.appendLocalMessage(
                                    sessionId: contact.id,
                                    text: text,
                                    status: .sent,
                                    transportMessageId: messageId,
                                    kind: .sosAlert
                                )
                            }
                            await self.publishStatus(contact.id, .sent)
                        } else {
                            await self.publishStatus(contact.id, .failed)
                        }
                    }
                }

                // Live location updates — rewrite the delivered bubbles when the victim has moved.
                if let fix = lastFix,
                   !self.alerted.isEmpty,
                   now.timeIntervalSince(lastLiveUpdateAt) >= Self.liveUpdateMinInterval,
                   Self.hasMovedEnough(from: lastSentFix, to: fix) {
                    lastLiveUpdateAt = now
                    let newText = Self.buildSosMessage(location: fix)
                    var anySent = false
                    for recipient in self.alerted.values {
                        guard let payload = try? JSONSerialization.data(withJSONObject: [
                            "ref": recipient.alertMessageId,
                            "text": newText
                        ]) else { continue }
                        let sent = await InternetChatTransport.shared.sendText(
                            messageId: UUID().uuidString,
                            text: String(decoding: payload, as: UTF8.self),
                            contact: recipient.contact,
                            priority: "normal",
                            templateCode: InternetChatTransport.sosLocationUpdateTemplate
                        )
                        if sent {
                            anySent = true
                            await MainActor.run {
                                _ = SOSChatStore.shared.updateLocalMessageText(
                                    sessionId: recipient.contact.id,
                                    transportMessageId: recipient.alertMessageId,
                                    text: newText
                                )
                            }
                        }
                    }
                    if anySent { lastSentFix = fix }
                }

                try? await Task.sleep(nanoseconds: UInt64(Self.loopTick * 1_000_000_000))
            }
        }
    }

    /// Cancels alerting and sends the plain-text all-clear to everyone who actually received the
    /// alert. Undeliverable all-clears are persisted and retried on the next app foreground —
    /// nobody should keep worrying after a false alarm.
    func onSosStopped() {
        guard !PlatformRuntime.isRunningTests else { return }
        job?.cancel()
        job = nil
        Task { [weak self] in await self?.resetRecipientState() }
        let toNotify = alerted.values.map(\.contact)
        alerted = [:]
        guard !toNotify.isEmpty else { return }
        UserDefaults.standard.set(
            toNotify.map { $0.id.uuidString },
            forKey: Self.pendingAllClearKey
        )
        Task { [weak self] in
            _ = await self?.deliverPendingAllClear()
            // An offline stop leaves the all-clear persisted; hand it to the network-gated
            // background task so contacts get the all-clear even if the app is never reopened.
            SosFollowUpBackgroundManager.shared.scheduleIfNeeded()
        }
    }

    /// True when an offline stop left an all-clear still awaiting delivery — the trigger the
    /// background follow-up task checks before scheduling itself.
    static var hasPendingAllClear: Bool {
        !(UserDefaults.standard.stringArray(forKey: pendingAllClearKey) ?? []).isEmpty
    }

    /// Attempts delivery of the persisted all-clear messages; called right after the stop and on
    /// app foreground. Never sends while a NEW SOS is running — that session owns the threads.
    @discardableResult
    func deliverPendingAllClear() async -> Bool {
        guard var pendingIds = UserDefaults.standard.stringArray(forKey: Self.pendingAllClearKey),
              !pendingIds.isEmpty else {
            return true
        }
        let sessionActive = await MainActor.run { SOSBroadcastManager.shared.isSessionActive }
        if sessionActive { return true }
        guard InternetChatTransport.shared.isAvailable() else { return false }

        let text = NSLocalizedString("SOS_CONTACT_MESSAGE_ENDED", comment: "")
        for idString in pendingIds {
            guard let sessionId = UUID(uuidString: idString),
                  let contact = await MainActor.run(body: {
                      ContactStore.shared.contacts.first { $0.id == sessionId }
                  }),
                  contact.supportsInternet else {
                pendingIds.removeAll { $0 == idString }
                continue
            }
            let messageId = UUID().uuidString
            let sent = await InternetChatTransport.shared.sendText(
                messageId: messageId,
                text: text,
                contact: contact,
                priority: "high"
            )
            if sent {
                await MainActor.run {
                    _ = SOSChatStore.shared.appendLocalMessage(
                        sessionId: contact.id,
                        text: text,
                        status: .sent,
                        transportMessageId: messageId
                    )
                }
                pendingIds.removeAll { $0 == idString }
            }
        }
        if pendingIds.isEmpty {
            UserDefaults.standard.removeObject(forKey: Self.pendingAllClearKey)
            return true
        }
        UserDefaults.standard.set(pendingIds, forKey: Self.pendingAllClearKey)
        return false
    }

    // MARK: - Selection + message building

    /// Hand-picked emergency contacts when configured; otherwise internet-capable contacts ranked
    /// by message volume and by contact age — a brand-new busy chat and a quiet long-standing one
    /// both surface.
    @MainActor
    private static func selectRecipients() -> (source: SelectionSource, contacts: [ContactRecord]) {
        // Child-profile contacts are never SOS recipients — an alert must reach an adult.
        let contacts = ContactStore.shared.contacts
            .filter { $0.supportsInternet && $0.peerIsChild != true && $0.isAuthorityBridge != true }
        guard !contacts.isEmpty else { return (.automatic, []) }

        // On a child device the confirmed parents ARE the emergency contacts. Only when none are
        // usable fall through to the normal selection — in an emergency reaching someone beats
        // reaching no one.
        if ChildProfileManager.shared.isEnabled {
            let parentIds = ChildProfileManager.shared.parents
            let parentRecipients = contacts.filter { parentIds.contains($0.id) }.prefix(maxRecipients)
            if !parentRecipients.isEmpty { return (.custom, Array(parentRecipients)) }
        }

        let chosen = SosEmergencyContactsStore.load()
        if !chosen.isEmpty {
            let custom = contacts.filter { chosen.contains($0.id) }.prefix(maxRecipients).map { $0 }
            if !custom.isEmpty { return (.custom, custom) }
        }
        let counts = Dictionary(
            contacts.map { ($0.id, SOSChatStore.shared.messageCount(for: $0.id)) },
            uniquingKeysWith: { first, _ in first }
        )
        let byVolume = contacts.sorted { (counts[$0.id] ?? 0) > (counts[$1.id] ?? 0) }
        let byAge = contacts.sorted { $0.createdAt < $1.createdAt }
        let volumeRank = Dictionary(
            byVolume.enumerated().map { ($0.element.id, $0.offset) },
            uniquingKeysWith: { first, _ in first }
        )
        let ageRank = Dictionary(
            byAge.enumerated().map { ($0.element.id, $0.offset) },
            uniquingKeysWith: { first, _ in first }
        )
        let ranked = contacts
            .sorted { (volumeRank[$0.id] ?? 0) + (ageRank[$0.id] ?? 0)
                < (volumeRank[$1.id] ?? 0) + (ageRank[$1.id] ?? 0) }
            .prefix(maxRecipients)
            .map { $0 }
        return (.automatic, ranked)
    }

    private static func buildSosMessage(location: SOSSignalLocationPayload.GPSPayload?) -> String {
        if let location {
            let mapsLink = String(
                format: "https://maps.google.com/?q=%.6f,%.6f",
                locale: Locale(identifier: "en_US_POSIX"),
                location.latitude,
                location.longitude
            )
            return String(
                format: NSLocalizedString("SOS_CONTACT_MESSAGE", comment: ""),
                mapsLink
            )
        }
        return NSLocalizedString("SOS_CONTACT_MESSAGE_NO_LOCATION", comment: "")
    }

    private static func hasMovedEnough(
        from: SOSSignalLocationPayload.GPSPayload?,
        to: SOSSignalLocationPayload.GPSPayload
    ) -> Bool {
        // The very first update after an alert that shipped WITHOUT a fix should always go out.
        guard let from else { return true }
        let distance = CLLocation(latitude: from.latitude, longitude: from.longitude)
            .distance(from: CLLocation(latitude: to.latitude, longitude: to.longitude))
        return distance >= liveUpdateMinMoveMeters
    }

    private static func waitForFix(timeout: TimeInterval) async -> SOSSignalLocationPayload.GPSPayload? {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if let fix = await MainActor.run(body: {
                SOSSignalLocationCoordinator.shared.currentVictimLocationPayload()
            }) {
                return fix
            }
            try? await Task.sleep(nanoseconds: 1_500_000_000)
        }
        return nil
    }
}
