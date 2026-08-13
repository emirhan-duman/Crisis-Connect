//
//  SfuRingManager.swift
//  Crisis Connect
//
//  iOS mirror of the web SfuRingManager (lib/messaging/sfu-ring.ts) and Android's SfuRingManager:
//  ring/signaling for SFU authority calls. It carries NO media — the `offer` signal carries a
//  shared ephemeral `roomId`; when the callee accepts, both sides get `onRoom` and join the
//  Cloudflare Realtime SFU room where the actual E2EE media flows.
//
//  Scoped to AUTHORITY (hierarchy + agency) calls only; citizen P2P calls keep InternetCallManager.
//

import Foundation

/// Call lifecycle state, mirroring the web CallState union (webrtc-call.ts).
enum SfuCallState: Equatable {
    case idle, outgoing, incoming, connecting, connected, ended
}

enum SfuProtocolVersion: Int {
    case legacy = 1
    case secure = 2
}

@MainActor
final class SfuRingManager {

    /// A ringing call auto-ends after this long if unanswered (same as the web + P2P paths).
    private static let ringTimeout: TimeInterval = 35
    private static let endedFlash: TimeInterval = 1.2

    private(set) var callId: String?
    private(set) var peerUid: String?
    var peerName: String?
    /// Whether this call was placed as a VIDEO call (camera on) — set from startCall (caller) or
    /// the offer signal (callee), so a voice call never lights up either side's camera.
    private(set) var video = false
    private(set) var roomId: String?
    /// Authority calls are secure-v2 only; missing/legacy capabilities are rejected.
    private(set) var protocolVersion: SfuProtocolVersion = .secure
    private(set) var state: SfuCallState = .idle

    private let sender: (_ toUid: String, _ signal: [String: Any]) -> Void
    private let onState: (SfuCallState) -> Void
    /// Fires once the call is accepted on both sides with the shared room to join (isCaller role).
    private let onRoom: (_ roomId: String, _ isCaller: Bool, _ version: SfuProtocolVersion) -> Void

    private var ringTask: Task<Void, Never>?
    private var cleanupTask: Task<Void, Never>?

    init(
        sender: @escaping (_ toUid: String, _ signal: [String: Any]) -> Void,
        onState: @escaping (SfuCallState) -> Void,
        onRoom: @escaping (_ roomId: String, _ isCaller: Bool, _ version: SfuProtocolVersion) -> Void
    ) {
        self.sender = sender
        self.onState = onState
        self.onRoom = onRoom
    }

    private func set(_ next: SfuCallState) {
        NSLog("SfuRing: state %@ → %@ (callId=%@ roomId=%@)",
              String(describing: state), String(describing: next), callId ?? "-", roomId ?? "-")
        state = next
        // Leaving a ringing state (answered, connecting, ended…) cancels the no-answer timeout.
        if next != .outgoing && next != .incoming { clearRing() }
        onState(next)
    }

    private func startRing() {
        clearRing()
        ringTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.ringTimeout * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            switch self.state {
            case .outgoing: self.end()    // caller: no answer
            case .incoming: self.reject() // callee: didn't pick up in time
            default: break
            }
        }
    }

    private func clearRing() {
        ringTask?.cancel()
        ringTask = nil
    }

    /// Place an outgoing authority call: mint a shared room id and invite the peer.
    func startCall(peerUid: String, peerName: String, video: Bool = false) {
        guard state == .idle else { return }
        self.peerUid = peerUid
        self.peerName = peerName
        self.video = video
        callId = UUID().uuidString.lowercased()
        roomId = UUID().uuidString.lowercased()
        set(.outgoing)
        startRing()
        sender(peerUid, signal("offer").merging([
            "roomId": roomId ?? "", "video": video, "sfuVersion": SfuProtocolVersion.secure.rawValue
        ]) { _, new in new })
    }

    /// Accept the ringing call and join the invited room.
    func accept() {
        guard state == .incoming, let peer = peerUid, let room = roomId, callId != nil else { return }
        set(.connecting)
        var answer = signal("answer")
        answer["sfuVersion"] = SfuProtocolVersion.secure.rawValue
        sender(peer, answer)
        onRoom(room, false, protocolVersion)
        set(.connected)
    }

    func reject() {
        if let peer = peerUid, callId != nil { sender(peer, signal("reject")) }
        cleanup()
    }

    func end() {
        if let peer = peerUid, callId != nil { sender(peer, signal("end")) }
        cleanup()
    }

    /// Routes an inbound call signal (from the callSignals listener) through the ring state machine.
    func handleSignal(fromUid: String, signal: [String: Any]) {
        let type = signal["type"] as? String ?? ""
        let signalCallId = (signal["callId"] as? String ?? "")
        guard type == "offer" || (peerUid != nil && fromUid == peerUid) else { return }
        switch type {
        case "offer":
            guard !signalCallId.isEmpty else { return }
            // Busy if already on a different call.
            if state != .idle && callId != signalCallId {
                sender(fromUid, ["type": "busy", "callId": signalCallId])
                return
            }
            // Duplicate delivery of the SAME offer (a listener re-attach replays the collection):
            // ignore — re-entering INCOMING while ringing/connected re-rings and can double-join.
            if callId == signalCallId && state != .idle { return }
            guard (signal["sfuVersion"] as? Int) == SfuProtocolVersion.secure.rawValue else {
                sender(fromUid, ["type": "reject", "callId": signalCallId])
                return
            }
            guard let room = (signal["roomId"] as? String), !room.isEmpty else { return } // not an SFU invite
            peerUid = fromUid
            callId = signalCallId
            roomId = room
            video = signal["video"] as? Bool ?? false
            protocolVersion = .secure
            set(.incoming)
            startRing()
        case "answer":
            guard signalCallId == callId else { return }
            // Only the caller, and only ONCE: a replayed answer must not fire onRoom (join) again.
            guard state == .outgoing else { return }
            guard (signal["sfuVersion"] as? Int) == SfuProtocolVersion.secure.rawValue else {
                end()
                return
            }
            guard let room = roomId else { return }
            protocolVersion = .secure
            set(.connecting)
            onRoom(room, true, protocolVersion)
            set(.connected)
            // Answered elsewhere: the callee may have other ringing sessions (web tabs) — tell them
            // the call is taken so they stop ringing. The answering session ignores this ("cancel"
            // only acts while INCOMING).
            if let peer = peerUid {
                sender(peer, ["type": "cancel", "callId": callId ?? ""])
            }
        case "cancel":
            // Another session of MINE answered this call — stop ringing here, silently.
            if signalCallId == callId && state == .incoming { cleanup() }
        case "reject", "busy":
            // Only while ringing. The callee can have OTHER sessions (web multi-tab / multi-device);
            // a session that never answered ring-times-out ~35s in and sends reject — that must not
            // kill a call the answering session already connected.
            if signalCallId == callId && (state == .outgoing || state == .incoming) {
                cleanup()
            }
        case "end":
            if signalCallId == callId { cleanup() }
        default:
            break // "ice" — SFU calls carry no peer-to-peer ICE
        }
    }

    private func cleanup() {
        clearRing()
        set(.ended)
        // Brief "ended" flash, then back to idle so a fresh call can start.
        cleanupTask?.cancel()
        cleanupTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.endedFlash * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            self.callId = nil
            self.peerUid = nil
            self.peerName = nil
            self.roomId = nil
            self.video = false
            self.protocolVersion = .secure
            if self.state == .ended { self.set(.idle) }
        }
    }

    func dispose() {
        clearRing()
        cleanupTask?.cancel()
    }

    private func signal(_ type: String) -> [String: Any] {
        ["type": type, "callId": callId ?? ""]
    }
}
