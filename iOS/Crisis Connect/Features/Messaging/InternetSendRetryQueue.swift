//
//  InternetSendRetryQueue.swift
//  Crisis Connect
//
//  Store-and-forward for outgoing internet messages — the iOS port of Android's queued-send
//  retry (ChatScreenViewModel's QUEUED + exponential backoff). The queue itself is the chat
//  store: every local text bubble still in `.pending` for an internet-capable contact IS a
//  queued send. Re-sends reuse the ORIGINAL transportMessageId, so the receiving side's dedup
//  makes double-delivery harmless.
//
//  Triggers: app returning to the foreground, connectivity coming back (NWPathMonitor), and a
//  kick after any failed live send. Retries back off exponentially per pass; a message that
//  burns MAX_ATTEMPTS flips to `.failed` (tap-to-retry in the bubble resets it to `.pending`
//  and kicks this queue again).
//

import Foundation
import Network
import UIKit

@MainActor
final class InternetSendRetryQueue {
    static let shared = InternetSendRetryQueue()

    private static let maxAttempts = 8
    private static let initialBackoff: TimeInterval = 3
    private static let maxBackoff: TimeInterval = 300

    private var attempts: [String: Int] = [:]
    private var passTask: Task<Void, Never>?
    private var scheduledTask: Task<Void, Never>?
    private var backoff: TimeInterval = InternetSendRetryQueue.initialBackoff
    private var monitor: NWPathMonitor?
    private var started = false

    private init() {}

    /// Arm the triggers. Idempotent; call from the messaging bootstrap.
    func start() {
        guard !started else { return }
        started = true

        NotificationCenter.default.addObserver(
            forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.kick(reason: "foreground") }
        }

        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.kick(reason: "connectivity") }
        }
        self.monitor = monitor
        monitor.start(queue: DispatchQueue(label: "internet.send.retry.path"))

        kick(reason: "startup")
    }

    /// Request a retry pass soon. Collapses concurrent kicks into one running pass.
    func kick(reason: String) {
        scheduledTask?.cancel()
        scheduledTask = nil
        guard passTask == nil else { return }
        passTask = Task { [weak self] in
            await self?.runPass(reason: reason)
            await MainActor.run { self?.passTask = nil }
        }
    }

    private static let btDrainQueue = DispatchQueue(label: "internet.send.retry.bt")

    /// Hands a queued text to a LIVE Bluetooth link when one exists. This queue only ever watched
    /// the internet, so in a blackout a pending message sat unsent right next to a working BT
    /// connection — the exact situation this app exists for. Reuses the original
    /// transportMessageId, so the peer's dedup makes a raced double-delivery harmless.
    private func drainOverBluetooth(
        sessionId: UUID, transportMessageId: String, text: String
    ) async -> Bool {
        let broadcast = ContactBroadcastManager.shared
        let gatt = P2pGattChatManager.sharedIfExists(sessionId: sessionId)
        let hostConnected = broadcast.isSessionConnected(sessionId)
        let centralReady = gatt?.isReady() ?? false
        guard hostConnected || centralReady else { return false }
        return await withCheckedContinuation { continuation in
            Self.btDrainQueue.async {
                if hostConnected,
                   broadcast.sendMessage(text, transportMessageId: transportMessageId, sessionId: sessionId) {
                    continuation.resume(returning: true)
                    return
                }
                if centralReady, gatt?.sendMessage(text, messageId: transportMessageId) == true {
                    continuation.resume(returning: true)
                    return
                }
                continuation.resume(returning: false)
            }
        }
    }

    private func runPass(reason: String) async {
        let pending = SOSChatStore.shared.pendingOutgoingTextMessages()
        guard !pending.isEmpty else {
            backoff = Self.initialBackoff
            return
        }
        // Bluetooth drain FIRST — it works precisely when the internet gate below would bail.
        var remaining: [(sessionId: UUID, transportMessageId: String, text: String)] = []
        for item in pending {
            if await drainOverBluetooth(
                sessionId: item.sessionId,
                transportMessageId: item.transportMessageId,
                text: item.text
            ) {
                attempts[item.transportMessageId] = nil
                SOSChatStore.shared.markSent(
                    sessionId: item.sessionId, transportMessageId: item.transportMessageId
                )
                MessagingDiagLog.log("send retry: drained \(item.transportMessageId) over BT")
            } else {
                remaining.append(item)
            }
        }
        guard !remaining.isEmpty else {
            backoff = Self.initialBackoff
            return
        }
        guard InternetChatTransport.shared.isAvailable() else {
            // Offline: connectivity trigger will re-kick; no busy backoff loop.
            return
        }
        MessagingDiagLog.log("send retry pass (\(reason)): \(remaining.count) queued")

        var anyFailure = false
        for item in remaining {
            guard let contact = ContactStore.shared.contacts.first(where: { $0.id == item.sessionId }),
                  contact.supportsInternet else { continue }
            let ok = await InternetChatTransport.shared.sendText(
                messageId: item.transportMessageId,
                text: item.text,
                contact: contact
            )
            if ok {
                attempts[item.transportMessageId] = nil
                SOSChatStore.shared.markSent(
                    sessionId: item.sessionId, transportMessageId: item.transportMessageId
                )
            } else {
                anyFailure = true
                let n = (attempts[item.transportMessageId] ?? 0) + 1
                attempts[item.transportMessageId] = n
                if n >= Self.maxAttempts {
                    attempts[item.transportMessageId] = nil
                    SOSChatStore.shared.markFailed(
                        sessionId: item.sessionId, transportMessageId: item.transportMessageId
                    )
                    MessagingDiagLog.log("send retry: giving up on \(item.transportMessageId) after \(n) attempts")
                }
            }
        }

        if anyFailure {
            let delay = backoff
            backoff = min(backoff * 2, Self.maxBackoff)
            scheduledTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard !Task.isCancelled else { return }
                await MainActor.run { self?.kick(reason: "backoff") }
            }
        } else {
            backoff = Self.initialBackoff
        }
    }
}
