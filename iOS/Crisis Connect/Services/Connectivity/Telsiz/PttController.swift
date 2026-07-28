import Foundation

/// Orchestrates the authority-mesh telsiz: session presence, single-speaker floor lock, and the
/// mic→Opus→broadcast / receive→decode→play loops (via `PttAudioEngine`). iOS port of the Android
/// `service.gattmesh.ptt.PttController`.
///
/// Floor model (user-approved): strict single speaker. Press-to-talk claims the floor only when it is
/// free; if a peer holds it, the local press is rejected (busy). Simultaneous claims are resolved
/// deterministically by a random `claimId` carried in the FLOOR_CLAIM (higher wins) so both sides
/// converge on the same speaker. Audio frames are fire-and-forget (no ACK); loss is concealed by the
/// jitter buffer.
///
/// **Multi-hop:** peers and the floor are keyed by a stable per-device **origin id** (carried in every
/// telsiz packet), NOT by the BLE hop address, so a participant reached via a relay is identified
/// correctly. Presence is liveness-based: each joined node re-announces (JOIN heartbeat) periodically
/// and entries not heard from within `presenceTimeoutMs` are swept. The transport relays packets
/// onward (see `GattMeshManager`).
///
/// Unlike Android, iOS audio routing (headset / Bluetooth / wired / speaker) is handled entirely by
/// `AVAudioSession(.playAndRecord, mode: .voiceChat)` inside `PttAudioEngine`, so there is no manual
/// `AudioManager`/SCO route management here.
final class PttController {

    // Transport + identity hooks supplied by GattMeshManager.
    private let profileId: String
    private let localDisplayName: () -> String
    private let localAgency: () -> String?
    private let onSendControl: (PttControlKind, Int64?) -> Void
    /// Sends one BLE packet carrying `frames` consecutive Opus frames starting at `baseSeq`, tagged
    /// with `talkTag` (a per-transmission id) so receivers can dedup relayed copies. Bundling 2 frames
    /// per packet halves the BLE packet rate, giving the link headroom and cutting dropouts.
    private let onSendAudio: (_ talkTag: String, _ baseSeq: Int, _ frames: [Data]) -> Void
    /// Fired when the floor crosses the idle boundary: true while someone is actively speaking (local
    /// or remote), false when idle. The manager uses this to free the BLE radio (pause scanning) only
    /// during actual audio, not for the whole joined session.
    private let onAudioActiveChanged: (Bool) -> Void
    /// Publishes the latest session state (always delivered on the main queue for SwiftUI).
    var onStateChanged: ((PttSessionState) -> Void)?

    private let engine = PttAudioEngine()
    private let lock = NSRecursiveLock()
    private let callbackQueue = DispatchQueue(label: "telsiz.ptt.callbacks")
    private let watchdogQueue = DispatchQueue(label: "telsiz.ptt.watchdog")
    private let audioSendQueue = DispatchQueue(label: "telsiz.ptt.sender")

    private var sessionState = PttSessionState()
    var currentState: PttSessionState {
        lock.lock(); defer { lock.unlock() }
        return sessionState
    }

    // Presence: origin id -> display name (with insertion order preserved), plus last-heard timestamps
    // for liveness sweeping, plus verified institution (kurum) shown beside the name.
    private var peers: [String: String] = [:]
    private var peerOrder: [String] = []
    private var peerLastSeen: [String: Int64] = [:]
    private var peerAgency: [String: String] = [:]

    private var floor: PttFloorState = .idle
    private var localClaimId: Int64?
    private var localTalkStartedAt: Int64 = 0
    private var localSeq = 0
    /// Per-transmission tag stamped into outgoing audio packet ids so relays can dedup. New on press.
    private var localTalkTag = ""
    private var remoteSpeaker: String?
    private var remoteClaimId: Int64 = .min
    private var remoteLastAudioAt: Int64 = 0
    private var audioActive = false

    private var watchdogTimer: DispatchSourceTimer?
    private var sinceHeartbeatMs: Int64 = 0

    // Audio sender bundling (mirrors the Android Channel + DROP_OLDEST coroutine): the capture callback
    // only enqueues frames; this queue bundles `framesPerPacket` of them (or flushes a partial bundle
    // after a short idle gap) and hands them to `onSendAudio`.
    private var senderActive = false
    private var pendingFrames: [Data] = []
    private var pendingBaseSeq = 0
    private var flushWorkItem: DispatchWorkItem?

    init(
        profileId: String,
        localDisplayName: @escaping () -> String,
        localAgency: @escaping () -> String? = { nil },
        onSendControl: @escaping (PttControlKind, Int64?) -> Void,
        onSendAudio: @escaping (_ talkTag: String, _ baseSeq: Int, _ frames: [Data]) -> Void,
        onAudioActiveChanged: @escaping (Bool) -> Void = { _ in }
    ) {
        self.profileId = profileId
        self.localDisplayName = localDisplayName
        self.localAgency = localAgency
        self.onSendControl = onSendControl
        self.onSendAudio = onSendAudio
        self.onAudioActiveChanged = onAudioActiveChanged
    }

    // MARK: - Public API (mirrors Android PttController)

    func join() {
        lock.lock()
        if sessionState.joined { lock.unlock(); return }
        if !engine.prepare() {
            lock.unlock()
            log("ptt join failed: audio engine unavailable")
            return
        }
        startAudioSender()
        sessionState.joined = true
        publishLocked()
        startWatchdogLocked()
        lock.unlock()
        onSendControl(.join, nil)
        log("ptt joined")
    }

    func leave() {
        lock.lock()
        if !sessionState.joined { lock.unlock(); return }
        let wasSpeaking = floor == .localSpeaking
        engine.stopCapture()
        engine.stopPlayback()
        engine.release()
        floor = .idle
        localClaimId = nil
        remoteSpeaker = nil
        remoteClaimId = .min
        peers.removeAll()
        peerOrder.removeAll()
        peerLastSeen.removeAll()
        peerAgency.removeAll()
        stopAudioSender()
        watchdogTimer?.cancel()
        watchdogTimer = nil
        sessionState = PttSessionState()
        let wasActive = audioActive
        audioActive = false
        lock.unlock()

        if wasActive { emitAudioActive(false) }
        emitState(PttSessionState())
        if wasSpeaking { onSendControl(.floorRelease, nil) }
        onSendControl(.leave, nil)
        log("ptt left")
    }

    /// Press-to-talk down. Claims the floor only when free.
    func pressTalk() {
        var claimId: Int64?
        lock.lock()
        if !sessionState.joined { lock.unlock(); return }
        switch floor {
        case .localSpeaking:
            lock.unlock()
            return
        case .remoteSpeaking:
            sessionState.busyTick += 1
            publishLocked()
            lock.unlock()
            log("ptt press rejected: floor busy")
            return
        case .idle:
            let id = Int64.random(in: Int64.min...Int64.max)
            localTalkTag = String(format: "%04x", Int.random(in: 0..<0x10000))
            let started = engine.startCapture { [weak self] opus in self?.broadcastAudioFrame(opus) }
            if !started {
                lock.unlock()
                log("ptt capture failed (mic permission/route)")
                return
            }
            localClaimId = id
            localSeq = 0
            localTalkStartedAt = nowMs()
            floor = .localSpeaking
            claimId = id
            publishLocked()
        }
        lock.unlock()
        if let claimId {
            onSendControl(.floorClaim, claimId)
            log("ptt floor claimed (local)")
            AppAnalytics.pttTransmit()
        }
    }

    /// Press-to-talk up. Releases the floor if we held it.
    func releaseTalk() {
        lock.lock()
        guard floor == .localSpeaking else { lock.unlock(); return }
        engine.stopCapture()
        floor = .idle
        localClaimId = nil
        publishLocked()
        lock.unlock()
        onSendControl(.floorRelease, nil)
        log("ptt floor released (local)")
    }

    /// Handle a telsiz control packet. `origin` is the sender's stable origin id (hop-independent),
    /// `senderLabel` their display name.
    func onPeerControl(origin: String, senderLabel: String, agency: String?, kind: PttControlKind, claimId: Int64?) {
        lock.lock()
        defer { lock.unlock() }
        guard sessionState.joined else { return }
        let now = nowMs()
        if let agency, !agency.isEmpty { peerAgency[origin] = agency }
        switch kind {
        case .join:
            // Only republish when a genuinely new participant appears; heartbeats from known peers just
            // refresh liveness (avoids needless UI churn every few seconds).
            let isNew = putPeer(origin: origin, label: senderLabel)
            peerLastSeen[origin] = now
            if isNew { publishLocked() }
        case .leave:
            removePeer(origin)
            if remoteSpeaker == origin { clearRemoteFloorLocked() }
            publishLocked()
        case .floorClaim:
            _ = putPeer(origin: origin, label: senderLabel)
            peerLastSeen[origin] = now
            let incoming = claimId ?? 0
            let grant = PttFloorRules.shouldGrantRemoteClaim(
                floor: floor,
                localClaimId: localClaimId,
                currentRemoteSpeaker: remoteSpeaker,
                currentRemoteClaimId: remoteClaimId,
                fromOrigin: origin,
                incomingClaimId: incoming
            )
            if grant {
                if floor == .localSpeaking {
                    // Lost the tie-break: yield the floor to the peer.
                    engine.stopCapture()
                    localClaimId = nil
                    log("ptt yielded floor to \(peers[origin] ?? origin)")
                }
                beginRemoteFloorLocked(origin, claimId: incoming)
            } else {
                publishLocked()
            }
        case .floorRelease:
            if remoteSpeaker == origin { clearRemoteFloorLocked() }
        }
    }

    /// Handle a telsiz audio frame from `origin` (stable origin id), labelled `senderLabel`.
    func onPeerAudio(origin: String, senderLabel: String, seq: Int, opus: Data) {
        lock.lock()
        guard sessionState.joined else { lock.unlock(); return }
        peerLastSeen[origin] = nowMs()
        if peers[origin] == nil { _ = putPeer(origin: origin, label: senderLabel) }
        switch floor {
        case .localSpeaking:
            lock.unlock()
            return
        case .remoteSpeaking:
            if origin != remoteSpeaker { lock.unlock(); return }
            remoteLastAudioAt = nowMs()
        case .idle:
            // Recover from a brief transport stall that tripped the stale-floor watchdog, or adopt a
            // speaker whose FLOOR_CLAIM we never received (joined mid-talk / lossy multi-hop): resuming
            // audio (re-)adopts the speaker so it keeps playing.
            beginRemoteFloorLocked(origin, claimId: remoteClaimId != .min ? remoteClaimId : 0)
        }
        lock.unlock()
        engine.submitFrame(seq: seq, opus: opus)
    }

    func shutdown() {
        lock.lock()
        engine.release()
        stopAudioSender()
        watchdogTimer?.cancel()
        watchdogTimer = nil
        sessionState = PttSessionState()
        let wasActive = audioActive
        audioActive = false
        lock.unlock()
        if wasActive { emitAudioActive(false) }
        emitState(PttSessionState())
    }

    // MARK: - Internal helpers (lock held unless noted)

    /// Inserts/updates a peer, preserving insertion order. Returns true if it was a new participant.
    private func putPeer(origin: String, label: String) -> Bool {
        let name = label.isEmpty ? (peers[origin] ?? "—") : label
        let isNew = peers[origin] == nil
        peers[origin] = name
        if isNew { peerOrder.append(origin) }
        return isNew
    }

    private func removePeer(_ origin: String) {
        peers[origin] = nil
        peerOrder.removeAll { $0 == origin }
        peerLastSeen[origin] = nil
        peerAgency[origin] = nil
    }

    private func beginRemoteFloorLocked(_ origin: String, claimId: Int64) {
        let switching = remoteSpeaker != origin || floor != .remoteSpeaking
        remoteSpeaker = origin
        remoteClaimId = claimId
        remoteLastAudioAt = nowMs()
        floor = .remoteSpeaking
        if switching {
            engine.stopPlayback()
            _ = engine.startPlayback()
        }
        publishLocked()
    }

    private func clearRemoteFloorLocked() {
        engine.stopPlayback()
        remoteSpeaker = nil
        remoteClaimId = .min
        floor = .idle
        publishLocked()
    }

    /// Called from the capture callback (engine's processing queue) for each encoded frame.
    private func broadcastAudioFrame(_ opus: Data) {
        lock.lock()
        guard floor == .localSpeaking else { lock.unlock(); return }
        localSeq += 1
        let seq = localSeq
        lock.unlock()
        enqueueAudioFrame(seq: seq, opus: opus)
    }

    // MARK: - Audio sender (bundling)

    private func startAudioSender() {
        audioSendQueue.async {
            self.senderActive = true
            self.pendingFrames.removeAll()
            self.flushWorkItem?.cancel()
            self.flushWorkItem = nil
        }
    }

    private func stopAudioSender() {
        audioSendQueue.async {
            self.senderActive = false
            self.flushWorkItem?.cancel()
            self.flushWorkItem = nil
            self.pendingFrames.removeAll()
        }
    }

    private func enqueueAudioFrame(seq: Int, opus: Data) {
        audioSendQueue.async {
            guard self.senderActive else { return }
            if self.pendingFrames.isEmpty { self.pendingBaseSeq = seq }
            self.pendingFrames.append(opus)
            self.flushWorkItem?.cancel()
            self.flushWorkItem = nil
            if self.pendingFrames.count >= Self.framesPerPacket {
                self.flushPendingFrames()
            } else {
                // Flush a partial bundle if capture pauses/stops so the tail frame of a transmission is
                // never held back waiting for a partner.
                let work = DispatchWorkItem { [weak self] in self?.flushPendingFrames() }
                self.flushWorkItem = work
                self.audioSendQueue.asyncAfter(deadline: .now() + Self.bundleFlushTimeout, execute: work)
            }
        }
    }

    /// Runs on `audioSendQueue`.
    private func flushPendingFrames() {
        flushWorkItem?.cancel()
        flushWorkItem = nil
        guard !pendingFrames.isEmpty else { return }
        let frames = pendingFrames
        let base = pendingBaseSeq
        pendingFrames.removeAll()
        lock.lock()
        let tag = localTalkTag
        lock.unlock()
        onSendAudio(tag, base, frames)
    }

    // MARK: - Watchdog (presence sweep + heartbeat + stale-floor recovery + max-talk)

    private func startWatchdogLocked() {
        watchdogTimer?.cancel()
        sinceHeartbeatMs = 0
        let timer = DispatchSource.makeTimerSource(queue: watchdogQueue)
        timer.schedule(deadline: .now() + Self.watchdogTick, repeating: Self.watchdogTick)
        timer.setEventHandler { [weak self] in self?.watchdogTick() }
        watchdogTimer = timer
        timer.resume()
    }

    private func watchdogTick() {
        sinceHeartbeatMs += Self.watchdogTickMs
        var autoReleased = false
        var sendHeartbeat = false
        lock.lock()
        if sessionState.joined {
            let now = nowMs()
            if floor == .remoteSpeaking && now - remoteLastAudioAt > Self.remoteStaleMs {
                clearRemoteFloorLocked()
                log("ptt remote floor timed out")
            }
            if floor == .localSpeaking && now - localTalkStartedAt > Self.maxTalkMs {
                engine.stopCapture()
                floor = .idle
                localClaimId = nil
                sessionState.maxTalkTick += 1
                publishLocked()
                autoReleased = true
                log("ptt local talk auto-released (max duration)")
            }
            // Liveness sweep: drop participants we haven't heard from within the timeout.
            let stale = peerLastSeen.filter { now - $0.value > Self.presenceTimeoutMs }.map { $0.key }
            if !stale.isEmpty {
                stale.forEach { removePeer($0) }
                if let speaker = remoteSpeaker, peers[speaker] == nil {
                    clearRemoteFloorLocked()
                } else {
                    publishLocked()
                }
            }
            if sinceHeartbeatMs >= Self.heartbeatMs {
                sinceHeartbeatMs = 0
                sendHeartbeat = true
            }
        }
        lock.unlock()
        if autoReleased { onSendControl(.floorRelease, nil) }
        // Re-announce presence so multi-hop peers keep us alive during silent stretches.
        if sendHeartbeat { onSendControl(.join, nil) }
    }

    // MARK: - State publishing (lock held)

    private func publishLocked() {
        let joined = sessionState.joined
        var list: [PttParticipant] = []
        if joined {
            let selfName = localDisplayName().isEmpty ? "—" : localDisplayName()
            let selfAgency = localAgency().flatMap { $0.isEmpty ? nil : $0 }
            list.append(
                PttParticipant(
                    address: PttParticipant.selfAddress,
                    displayName: selfName,
                    isSelf: true,
                    isSpeaking: floor == .localSpeaking,
                    agency: selfAgency
                )
            )
        }
        for origin in peerOrder {
            let name = peers[origin] ?? "—"
            list.append(
                PttParticipant(
                    address: origin,
                    displayName: name.isEmpty ? "—" : name,
                    isSpeaking: floor == .remoteSpeaking && origin == remoteSpeaker,
                    agency: peerAgency[origin]
                )
            )
        }
        let speakerName: String?
        switch floor {
        case .localSpeaking: speakerName = localDisplayName()
        case .remoteSpeaking: speakerName = remoteSpeaker.flatMap { peers[$0] } ?? "—"
        case .idle: speakerName = nil
        }
        sessionState.participants = list
        sessionState.floor = floor
        sessionState.speakerName = speakerName
        sessionState.joined = joined
        emitState(sessionState)

        // Signal active-audio transitions so the radio is freed only during actual transmissions.
        let nowActive = joined && floor != .idle
        if nowActive != audioActive {
            audioActive = nowActive
            emitAudioActive(nowActive)
        }
    }

    private func emitState(_ state: PttSessionState) {
        DispatchQueue.main.async { self.onStateChanged?(state) }
    }

    private func emitAudioActive(_ active: Bool) {
        callbackQueue.async { self.onAudioActiveChanged(active) }
    }

    private func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1_000) }

    private func log(_ message: String) {
        NSLog("Telsiz[%@] %@", profileId, message)
    }

    private static let remoteStaleMs: Int64 = 2_000
    private static let maxTalkMs: Int64 = 30_000
    private static let watchdogTick: TimeInterval = 0.5
    private static let watchdogTickMs: Int64 = 500
    // Re-announce presence every few seconds; drop peers not heard from within the timeout.
    private static let heartbeatMs: Int64 = 3_000
    private static let presenceTimeoutMs: Int64 = 10_000
    // Opus frames bundled into one BLE packet (halves the on-air packet rate).
    private static let framesPerPacket = 2
    // Flush a partial bundle if no new frame arrives within this window (end of a transmission).
    private static let bundleFlushTimeout: TimeInterval = 0.03
}
