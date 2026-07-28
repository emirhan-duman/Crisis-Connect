//
//  MessagingRegistrar.swift
//  Crisis Connect
//
//  One-shot startup registration that makes this device reachable over internet messaging,
//  mirroring Android's `MessagingBootstrap.ensureRegistered`:
//   - signs in anonymously first when there is no account, so a QR-added contact can reach this
//     device online WITHOUT the user ever signing in (no phone number required),
//   - publishes our long-term identity PUBLIC key to the directory so contacts can encrypt to us,
//   - registers our FCM push token so the delivery fan-out can reach this device when the app
//     is backgrounded or killed.
//
//  Privacy: only the PUBLIC identity key ever leaves the device. The private key and the QR
//  session secret never touch the server. Anonymous devices publish NON-discoverable (no phone /
//  username), so they are reachable only by someone who already scanned their QR — never by
//  directory enumeration.
//
//  Identity STABILITY (Android parity, MessagingBootstrap.resolveUid): the anonymous uid IS the
//  address a peer stored from our QR, so it must survive every launch. Firebase restores the
//  persisted session from the keychain asynchronously and can lag a few hundred ms after launch;
//  signing in anonymously before that restore completes would mint a SECOND anonymous account,
//  orphaning the identity key + push token published under the first — the peer's stored uid then
//  points at a dead address. So we wait briefly for a restored session before ever creating one,
//  and the sign-in itself is bounded + retried with backoff + a one-shot connectivity retry, so a
//  transient offline launch doesn't leave internet messaging dead for the whole session.
//

import Foundation
import FirebaseAuth
import Network

enum MessagingRegistrar {

    private static let signInTimeout: TimeInterval = 20
    private static let signInMaxAttempts = 4
    private static let signInInitialBackoff: TimeInterval = 1
    private static let signInMaxBackoff: TimeInterval = 8
    private static let restoreWaitDeadline: TimeInterval = 3

    // Serializes concurrent triggers (launch + connectivity retry + profile) so two anonymous
    // sign-ins never race — the exact failure that mints duplicate accounts.
    private static let gate = AsyncGate()
    private static let connectivityRetry = ConnectivityRetry()

    /// Idempotent; safe to call on every launch. Never throws — messaging registration is
    /// best-effort and must not block or crash app start.
    static func ensureRegistered() async {
        await gate.run {
            await ensureRegisteredLocked()
        }
    }

    private static func ensureRegisteredLocked() async {
        let client = InternetMessagingClient()

        // Resolve a STABLE uid: prefer a restored session (waiting briefly for the keychain restore),
        // otherwise a fresh anonymous sign-in — bounded and retried. nil only when we genuinely
        // couldn't reach Firebase (offline / wedged); arm a connectivity retry and bail for now.
        guard let uid = await resolveUid(client: client) else {
            NSLog("MessagingRegistrar: no uid yet — deferring internet bring-up until connectivity returns")
            MessagingDiagLog.log("registrar: NO uid — internet deferred until connectivity")
            connectivityRetry.arm { await ensureRegistered() }
            return
        }
        MessagingDiagLog.log("registrar: uid=\(uid.prefix(8)) resolved")
        connectivityRetry.disarm()

        // Account-level presence privacy: reconcile the local "share last seen" toggle with
        // presenceSettings/{uid} now that a uid exists, so a choice made on Android/web
        // applies on this device too (cloud wins over the local default).
        PresenceReporter.shared.syncAccountPreferenceFromCloud()

        do {
            let publicKey = try MessagingIdentity.shared.publicKeyBase64()
            // Discoverability by phone/username is reserved for full accounts (see the backend's
            // requireNonAnonymous guard); publishing non-discoverable keeps QR the only way in.
            let isChild = await MainActor.run { ChildProfileManager.shared.isEnabled }
            try await client.publishIdentityKey(
                publicKeyBase64: publicKey,
                discoverable: false,
                isChild: isChild
            )
        } catch {
            NSLog("MessagingRegistrar: failed to publish identity key: %@", String(describing: error))
        }

        // Register the push token now that a uid exists (also re-registered on FCM refresh).
        await InternetPushRegistrar.shared.registerTokenWithBackend()
        // And the PushKit VoIP token, so a force-quit device can be woken to ring an internet call.
        VoipPushRegistrar.shared.registerCurrentTokenWithBackend()

        // Signal-protocol (v3) prekeys: publish/rotate + top up one-time pools so peers can start
        // a forward-secret session with us. Best-effort — messaging falls back to the v2 envelope
        // for peers without a session if this fails.
        do {
            let store = try SignalProtocolFileStore()
            try await SignalPreKeyManager(store: store).ensurePublished()
        } catch {
            NSLog("MessagingRegistrar: failed to publish Signal prekeys: %@", String(describing: error))
        }

        // Start the realtime downlink so incoming internet messages land in the chat, and the
        // store-and-forward queue that redelivers any message stuck pending (offline compose,
        // transient relay failure) when the app foregrounds or connectivity returns.
        await MainActor.run {
            InternetMessageReceiver.shared.start()
            InternetSendRetryQueue.shared.start()
        }
        MessagingDiagLog.log("registrar: identity published + receiver started (internet ready)")
    }

    /// Restored session first (waiting briefly for the async keychain restore), else a bounded,
    /// backed-off anonymous sign-in. Mirrors Android's MessagingBootstrap.resolveUid.
    private static func resolveUid(client: InternetMessagingClient) async -> String? {
        if let restored = await awaitRestoredUid(client: client) {
            return restored
        }
        var backoff = signInInitialBackoff
        for attempt in 0..<signInMaxAttempts {
            // A previously-timed-out sign-in may have completed in the background meanwhile.
            if let uid = client.currentUid { return uid }
            if let uid = await signInAnonymouslyWithTimeout() { return uid }
            if attempt < signInMaxAttempts - 1 {
                try? await Task.sleep(nanoseconds: UInt64(backoff * 1_000_000_000))
                backoff = min(backoff * 2, signInMaxBackoff)
            }
        }
        return nil
    }

    /// The uid of an already-signed-in user, waiting up to [restoreWaitDeadline] for Firebase to
    /// restore a persisted session (which lags after a cold launch). nil only when there is
    /// genuinely no saved session (first-ever launch) — the caller then signs in anonymously ONCE.
    private static func awaitRestoredUid(client: InternetMessagingClient) async -> String? {
        if let uid = client.currentUid { return uid }
        let deadline = Date().addingTimeInterval(restoreWaitDeadline)
        while Date() < deadline {
            try? await Task.sleep(nanoseconds: 100_000_000)
            if let uid = client.currentUid { return uid }
        }
        return nil
    }

    /// Anonymous sign-in bounded by [signInTimeout] so a wedged Task can't hang forever.
    private static func signInAnonymouslyWithTimeout() async -> String? {
        await withTaskGroup(of: String?.self) { group in
            group.addTask {
                do {
                    return try await Auth.auth().signInAnonymously().user.uid
                } catch {
                    NSLog("MessagingRegistrar: anonymous sign-in failed: %@", String(describing: error))
                    return nil
                }
            }
            group.addTask {
                try? await Task.sleep(nanoseconds: UInt64(signInTimeout * 1_000_000_000))
                return nil
            }
            let first = await group.next() ?? nil
            group.cancelAll()
            return first
        }
    }
}

/// Serializes async work — the Swift-concurrency analogue of Android's registerMutex.
private actor AsyncGate {
    private var running = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run(_ work: @Sendable () async -> Void) async {
        while running {
            await withCheckedContinuation { waiters.append($0) }
        }
        running = true
        await work()
        running = false
        if !waiters.isEmpty {
            waiters.removeFirst().resume()
        }
    }
}

/// One-shot "retry when the network returns" (Android parity: armConnectivityRetry). Fires the
/// stored action on the first satisfied path, then tears the monitor down.
private final class ConnectivityRetry: @unchecked Sendable {
    private let queue = DispatchQueue(label: "messaging.registrar.connectivity")
    private var monitor: NWPathMonitor?

    func arm(_ action: @escaping @Sendable () async -> Void) {
        queue.async {
            guard self.monitor == nil else { return }
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { [weak self] path in
                guard path.status == .satisfied else { return }
                self?.disarm()
                Task { await action() }
            }
            self.monitor = monitor
            monitor.start(queue: self.queue)
        }
    }

    func disarm() {
        queue.async {
            self.monitor?.cancel()
            self.monitor = nil
        }
    }
}
