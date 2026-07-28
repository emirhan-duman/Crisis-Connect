//
//  InternetPresence.swift
//  Crisis Connect
//
//  Typing indicator + "last seen" presence for internet contacts — the iOS port of Android's
//  TypingIndicatorBus, PresenceReporter and ContactLastSeenStore.
//
//  Typing: the composer sends a throttled, E2E-sealed pulse (templateCode 201); the receiver
//  routes it here — never stored, never notified. Presence: while the app is foreground,
//  `presence/{uid}` gets a `lastActiveAt` heartbeat every minute plus one final stamp on
//  background — that last stamp IS the peer-visible "son görülme". Readers combine the peer's
//  presence doc with local offline observations (a live BT link, any incoming message), so a
//  peer met purely over Bluetooth still gets a truthful last-seen line.
//

import Combine
import Foundation
import FirebaseAuth
import FirebaseFirestore

// MARK: - Typing indicator bus

/// In-memory "peer is typing" pulses, keyed by the chat's session id. Entries self-expire — a
/// peer that stops typing simply stops sending pulses. Never persisted.
@MainActor
final class TypingIndicatorBus: ObservableObject {
    static let shared = TypingIndicatorBus()

    /// How long one received pulse keeps the indicator alive (the sender re-pulses every ~4s).
    static let typingTtl: TimeInterval = 6

    /// sessionId → moment the indicator expires. Stale entries may linger until touched.
    @Published private(set) var typing: [UUID: Date] = [:]

    private init() {}

    func signalTyping(sessionId: UUID) {
        let now = Date()
        typing = typing.filter { $0.value > now }
        typing[sessionId] = now.addingTimeInterval(Self.typingTtl)
    }

    /// A real message arrived — the indicator should drop instantly, not wait out the TTL.
    func clear(sessionId: UUID) {
        guard typing[sessionId] != nil else { return }
        let now = Date()
        typing = typing.filter { $0.value > now && $0.key != sessionId }
    }
}

// MARK: - Own presence heartbeat

/// Publishes this user's "last seen" presence to `presence/{uid}`. Best-effort everywhere:
/// offline just means peers see a stale stamp, never an error. Gated by the Advanced Settings
/// "share last seen" toggle (default ON); turning it off stops the heartbeat AND deletes the
/// doc so peers see nothing at all — not even a stale stamp.
///
/// The toggle is ACCOUNT-level (Android parity): it is mirrored to `presenceSettings/{uid}`,
/// which (a) the other platforms sync their local cache from and (b) the deployed Firestore
/// rules enforce on every presence write — so a stale client can't leak presence after the
/// user turned sharing off. The UserDefaults key is a per-device cache of that account doc;
/// on startup the cloud value wins ([syncAccountPreferenceOnce]).
final class PresenceReporter: @unchecked Sendable {
    static let shared = PresenceReporter()

    /// UserDefaults key shared with AdvancedSettingsStore.
    static let shareLastSeenKey = "advanced.shareLastSeenEnabled"

    private static let heartbeatSeconds: UInt64 = 60
    private static let settingsCollection = "presenceSettings"
    private static let settingsField = "shareLastSeen"

    private var heartbeatTask: Task<Void, Never>?

    /// One successful account-doc read per process; an explicit user toggle also settles it.
    private var accountPrefSynced = false

    private init() {}

    private var isSharingEnabled: Bool {
        UserDefaults.standard.object(forKey: Self.shareLastSeenKey) as? Bool ?? true
    }

    func onAppForeground() {
        guard !PlatformRuntime.isRunningTests else { return }
        guard heartbeatTask == nil else { return }
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.syncAccountPreferenceOnce()
                await self?.publish()
                try? await Task.sleep(nanoseconds: Self.heartbeatSeconds * 1_000_000_000)
            }
        }
    }

    func onAppBackground() {
        guard !PlatformRuntime.isRunningTests else { return }
        heartbeatTask?.cancel()
        heartbeatTask = nil
        // One final stamp — this is the moment peers will see as "son görülme".
        Task { [weak self] in await self?.publish() }
    }

    /// Settings toggle changed: ON → stamp immediately; OFF → wipe the doc. Either way the
    /// choice is published account-wide so Android and web follow it too.
    func onSharingPreferenceChanged(enabled: Bool) {
        guard !PlatformRuntime.isRunningTests else { return }
        // The user's explicit choice beats any not-yet-run cloud sync this process.
        accountPrefSynced = true
        Task { [weak self] in
            guard let self else { return }
            if let uid = Auth.auth().currentUser?.uid {
                await self.publishAccountPreference(uid: uid, enabled: enabled)
            }
            if enabled {
                await self.publish()
            } else {
                await self.deletePresence()
            }
        }
    }

    /// Launch/sign-in hook (MessagingRegistrar): reconcile the local toggle with the account
    /// doc as soon as a uid exists, instead of waiting for the next heartbeat.
    func syncAccountPreferenceFromCloud() {
        guard !PlatformRuntime.isRunningTests else { return }
        Task { [weak self] in await self?.syncAccountPreferenceOnce() }
    }

    /// Cloud-wins startup sync of the account-level toggle (Android parity,
    /// PresenceReporter.syncAccountPreferenceOnce). Runs before the first heartbeat and retries
    /// on later beats until one read succeeds — an offline start must not lock in a stale local
    /// value. A device that was switched OFF before the account doc existed pushes its OFF up
    /// so the other platforms adopt it.
    private func syncAccountPreferenceOnce() async {
        guard !accountPrefSynced, let uid = Auth.auth().currentUser?.uid else { return }
        guard let snapshot = try? await Firestore.firestore()
            .collection(Self.settingsCollection)
            .document(uid)
            .getDocument()
        else { return } // offline: retry on the next heartbeat
        accountPrefSynced = true
        let cloud = snapshot.get(Self.settingsField) as? Bool
        let local = isSharingEnabled
        if let cloud, cloud != local {
            MessagingDiagLog.log("presence: shareLastSeen=\(cloud) adopted from account doc (local was \(local))")
            await MainActor.run {
                AdvancedSettingsStore.shared.adoptCloudShareLastSeen(cloud)
            }
            if !cloud {
                await deletePresence()
            }
        } else if cloud == nil, !local {
            await publishAccountPreference(uid: uid, enabled: false)
        }
    }

    /// Mirrors the toggle to `presenceSettings/{uid}` — the account-level doc the other
    /// platforms sync from and the Firestore rules enforce on every presence write.
    private func publishAccountPreference(uid: String, enabled: Bool) async {
        let payload: [String: Any] = [
            Self.settingsField: enabled,
            "updatedAt": Int(Date().timeIntervalSince1970 * 1000),
        ]
        do {
            try await Firestore.firestore()
                .collection(Self.settingsCollection)
                .document(uid)
                .setData(payload, merge: true)
            MessagingDiagLog.log("presence: account shareLastSeen=\(enabled) published")
        } catch {
            NSLog("PresenceReporter: failed to publish account preference: %@", String(describing: error))
        }
    }

    private func publish() async {
        guard isSharingEnabled, let uid = Auth.auth().currentUser?.uid else { return }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        try? await Firestore.firestore()
            .collection("presence")
            .document(uid)
            .setData(["lastActiveAt": stamp])
    }

    private func deletePresence() async {
        guard let uid = Auth.auth().currentUser?.uid else { return }
        try? await Firestore.firestore()
            .collection("presence")
            .document(uid)
            .delete()
    }
}

// MARK: - Local last-seen observations

/// Local, per-conversation "last saw the peer alive" record — the offline half of last seen.
/// Fed by observations that need no server at all: a live BT link to the peer or any incoming
/// message. The chat header shows max(this, the peer's internet presence doc).
enum ContactLastSeenStore {
    private static let defaultsKey = "contact.lastSeenMillis"

    static func record(sessionId: UUID, at date: Date = Date()) {
        let millis = Int64(date.timeIntervalSince1970 * 1000)
        guard millis > 0 else { return }
        let key = sessionId.uuidString
        var map = (UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: Double]) ?? [:]
        if millis > Int64(map[key] ?? 0) {
            map[key] = Double(millis)
            UserDefaults.standard.set(map, forKey: defaultsKey)
        }
    }

    static func lastSeen(sessionId: UUID) -> Date? {
        let map = UserDefaults.standard.dictionary(forKey: defaultsKey) as? [String: Double]
        guard let millis = map?[sessionId.uuidString], millis > 0 else { return nil }
        return Date(timeIntervalSince1970: millis / 1000)
    }
}

// MARK: - Peer presence for the chat header

/// Live "typing… / online / last seen" state for one chat, combining the typing bus, the peer's
/// internet presence doc and local offline observations — mirrors Android's ChatScreenViewModel
/// presence logic (online = stamp fresher than the 60s heartbeat + 30s slack).
@MainActor
final class ChatPeerPresenceState: ObservableObject {
    enum Presence: Equatable {
        case unknown
        case online
        case lastSeen(Date)
    }

    @Published private(set) var isTyping = false
    @Published private(set) var presence: Presence = .unknown

    private static let onlineWindow: TimeInterval = 90

    private let sessionId: UUID
    private var peerUid: String?
    private var presenceListener: ListenerRegistration?
    private var remotePresence: Date?
    private var typingRecheckTask: Task<Void, Never>?
    private var presenceRecheckTask: Task<Void, Never>?
    private var cancellables = Set<AnyCancellable>()

    init(sessionId: UUID) {
        self.sessionId = sessionId

        TypingIndicatorBus.shared.$typing
            .receive(on: DispatchQueue.main)
            .sink { [weak self] map in
                self?.refreshTyping(map)
            }
            .store(in: &cancellables)

        // A live BT link proves the peer is alive right now — no server needed.
        ContactBroadcastManager.shared.$connectedSessionIds
            .receive(on: DispatchQueue.main)
            .sink { [weak self] ids in
                guard let self, ids.contains(self.sessionId) else { return }
                ContactLastSeenStore.record(sessionId: self.sessionId)
                self.refreshPresence()
            }
            .store(in: &cancellables)

        // An incoming message from the peer is itself proof they're online right now — bump the
        // header to "online" immediately instead of waiting up to 60s for their next presence
        // heartbeat (Android refreshes presence on message arrival too).
        SOSChatStore.shared.$messagesBySession
            .receive(on: DispatchQueue.main)
            .sink { [weak self] map in
                guard let self,
                      let last = map[self.sessionId]?.last,
                      last.isLocal == false else { return }
                if self.lastSeenIncomingTimestamp != last.timestamp {
                    self.lastSeenIncomingTimestamp = last.timestamp
                    self.remotePresence = Date()
                    self.refreshPresence()
                }
            }
            .store(in: &cancellables)

        refreshPresence()
    }

    private var lastSeenIncomingTimestamp: Date?

    deinit {
        presenceListener?.remove()
    }

    /// Attaches (or re-targets) the live watch on the peer's presence doc. Safe to call whenever
    /// the contact loads or changes; a nil/empty uid leaves only the local observations active.
    func setPeerUid(_ uid: String?) {
        let trimmed = uid?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let next = trimmed.isEmpty ? nil : trimmed
        guard next != peerUid else {
            refreshPresence()
            return
        }
        peerUid = next
        presenceListener?.remove()
        presenceListener = nil
        remotePresence = nil
        if let next {
            presenceListener = Firestore.firestore()
                .collection("presence")
                .document(next)
                .addSnapshotListener { [weak self] snapshot, _ in
                    let millis = (snapshot?.get("lastActiveAt") as? NSNumber)?.doubleValue
                    Task { @MainActor [weak self] in
                        self?.remotePresence = millis.map { Date(timeIntervalSince1970: $0 / 1000) }
                        self?.refreshPresence()
                    }
                }
        }
        refreshPresence()
    }

    private func refreshTyping(_ map: [UUID: Date]) {
        let expiresAt = map[sessionId]
        let now = Date()
        let active = expiresAt.map { $0 > now } ?? false
        if isTyping != active {
            isTyping = active
        }
        typingRecheckTask?.cancel()
        if active, let expiresAt {
            // Re-evaluate the moment this pulse would expire (a fresh pulse reschedules us).
            typingRecheckTask = Task { @MainActor [weak self] in
                let delay = max(expiresAt.timeIntervalSince(now), 0.05)
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard let self, !Task.isCancelled else { return }
                self.refreshTyping(TypingIndicatorBus.shared.typing)
            }
        }
    }

    private func refreshPresence() {
        let local = ContactLastSeenStore.lastSeen(sessionId: sessionId)
        let last = [local, remotePresence].compactMap { $0 }.max()
        let now = Date()
        let next: Presence
        if let last {
            next = now.timeIntervalSince(last) < Self.onlineWindow ? .online : .lastSeen(last)
        } else {
            next = .unknown
        }
        if presence != next {
            presence = next
        }
        presenceRecheckTask?.cancel()
        if case .online = next, let last {
            // Re-evaluate the moment this stamp would age out of the online window.
            presenceRecheckTask = Task { @MainActor [weak self] in
                let delay = max(last.addingTimeInterval(Self.onlineWindow).timeIntervalSince(now), 0.25)
                try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
                guard let self, !Task.isCancelled else { return }
                self.refreshPresence()
            }
        }
    }
}
