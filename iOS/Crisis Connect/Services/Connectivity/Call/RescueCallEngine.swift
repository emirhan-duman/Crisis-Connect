//
//  RescueCallEngine.swift
//  Crisis Connect
//
//  Live voice calls over the rescue link (SOS service 0xCC00), both roles:
//  the victim's GATT server (peripheral) and the rescuer's central connection register a
//  transport here and feed it raw call-IO packets from the dedicated characteristic pair
//
//      0xCC40 CALL_IO_IN  (rescuer → victim, write)
//      0xCC41 CALL_IO_OUT (victim → rescuer, notify)
//
//  Wire format (bit-compatible with Android's RescueCallLinkCodec + P2pCallProtocol):
//   - Audio: standard P2pCallProtocol frames (magic 0xCA), directional keys HKDF-derived from
//     the rescue session key with fresh per-direction salts carried in offer/accept.
//   - Signaling: [0xCB][version=1][AES.GCM(sessionKey, call-signal JSON).combined]
//
//  v1 keeps the UI in-app (overlay in the SOS chat + a local notification on incoming ring);
//  CallKit integration is a deliberate later increment.
//

import Combine
import Foundation
import CryptoKit
import UserNotifications

final class RescueCallEngine: NSObject, ObservableObject {

    static let shared = RescueCallEngine()

    static let signalMagic: UInt8 = 0xCB
    static let signalVersion: UInt8 = 0x01

    // Behavior parity with Android's P2pCallController.
    private static let offerRetryInterval: TimeInterval = 2.0
    private static let offerTimeout: TimeInterval = 10.0
    private static let ringTimeout: TimeInterval = 30.0
    private static let offerFreshnessMillis: Int64 = 12_000
    private static let rxTimeout: TimeInterval = 5.0
    private static let watchdogTick: TimeInterval = 1.0
    private static let defaultBitrate = 12_000
    private static let framesPerPacket = 2

    struct CallInfo: Equatable {
        enum State: Equatable {
            case ringingIncoming
            case ringingOutgoing
            case connecting
            case inCall
        }

        let sessionId: UUID
        let callId: String
        let peerName: String
        var state: State
        var muted: Bool = false
        var speakerOn: Bool = true
        var connectedAt: Date?
    }

    /// One live rescue link able to carry call packets for a chat session.
    struct Transport {
        let sessionId: UUID
        /// Sends one single-ATT-write packet (audio frame or sealed signal). Best effort.
        let sendPacket: (Data) -> Bool
        let sessionKey: () -> SymmetricKey?
        let peerName: () -> String?
    }

    @Published private(set) var call: CallInfo?

    /// Sessions with a call-capable transport, published so SwiftUI toolbars re-evaluate the
    /// call button the moment the rescue link becomes ready (a plain canCall() read is not
    /// reactive — the button never appeared when the link came up after first render).
    @Published private(set) var readySessionIds: Set<UUID> = []

    private let lock = NSRecursiveLock()
    private var transports: [UUID: Transport] = [:]
    private var session: ActiveSession?

    private final class ActiveSession {
        let sessionId: UUID
        let callId: String
        let isOutgoing: Bool
        let callTag: Data
        let localSaltHex: String
        var remoteSaltHex: String?
        var peerName: String
        var muted = false
        var speakerOn = true
        var connectedAt: Date?
        var txKey: SymmetricKey?
        var rxKey: SymmetricKey?
        var txSeq: UInt32 = 0
        var rxExpectedSeq: UInt64 = 0
        var pendingFrames: [Data] = []
        var lastRxAt = Date()
        var offerTimer: DispatchSourceTimer?
        var watchdogTimer: DispatchSourceTimer?
        var startedAt = Date()
        var ringReceived = false
        let engine = GattCallAudioEngine()

        init(sessionId: UUID, callId: String, isOutgoing: Bool, peerName: String) {
            self.sessionId = sessionId
            self.callId = callId
            self.isOutgoing = isOutgoing
            self.callTag = P2pCallProtocol.deriveCallTag(callId: callId)
            self.localSaltHex = P2pCallProtocol.randomSaltHex()
            self.peerName = peerName
        }
    }

    // ---------------------------------------------------------------------------------------
    // Transport registry
    // ---------------------------------------------------------------------------------------

    func registerTransport(_ transport: Transport) {
        lock.lock()
        transports[transport.sessionId] = transport
        let ready = Set(transports.keys)
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            self?.readySessionIds = ready
        }
    }

    func unregisterTransport(sessionId: UUID) {
        lock.lock()
        transports.removeValue(forKey: sessionId)
        let ready = Set(transports.keys)
        let dying = session?.sessionId == sessionId ? session : nil
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            self?.readySessionIds = ready
        }
        if dying != nil {
            endCall(reason: "link_lost", sendEnd: false)
        }
    }

    func canCall(sessionId: UUID) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard session == nil, let transport = transports[sessionId] else { return false }
        return transport.sessionKey() != nil
    }

    // ---------------------------------------------------------------------------------------
    // Packet plumbing (both roles call this with raw 0xCC40/0xCC41 payloads)
    // ---------------------------------------------------------------------------------------

    static func isSignalPacket(_ packet: Data) -> Bool {
        packet.count > 2 && packet[packet.startIndex] == signalMagic &&
            packet[packet.index(after: packet.startIndex)] == signalVersion
    }

    func onInboundPacket(sessionId: UUID, packet: Data) {
        guard !packet.isEmpty else { return }
        if P2pCallProtocol.isCallAudioFrame(packet) {
            onInboundAudio(packet)
            return
        }
        guard Self.isSignalPacket(packet) else { return }
        lock.lock()
        let key = transports[sessionId]?.sessionKey()
        lock.unlock()
        guard let key,
              let sealed = try? AES.GCM.SealedBox(combined: packet.dropFirst(2)),
              let plain = try? AES.GCM.open(sealed, using: key),
              let object = try? JSONSerialization.jsonObject(with: plain),
              let json = object as? [String: Any],
              let signal = P2pCallProtocol.parseSignal(json) else {
            return
        }
        onInboundSignal(sessionId: sessionId, signal: signal)
    }

    private func sendSignal(sessionId: UUID, _ signal: P2pCallSignal) -> Bool {
        lock.lock()
        let transport = transports[sessionId]
        lock.unlock()
        guard let transport,
              let key = transport.sessionKey(),
              let json = P2pCallProtocol.encodeSignal(signal),
              let plain = try? JSONSerialization.data(withJSONObject: json),
              let sealed = try? AES.GCM.seal(plain, using: key),
              let combined = sealed.combined else {
            return false
        }
        return transport.sendPacket(Data([Self.signalMagic, Self.signalVersion]) + combined)
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    @discardableResult
    func startCall(sessionId: UUID) -> Bool {
        lock.lock()
        guard session == nil, let transport = transports[sessionId], transport.sessionKey() != nil else {
            lock.unlock()
            return false
        }
        let peerName = transport.peerName() ?? ""
        let active = ActiveSession(
            sessionId: sessionId,
            callId: UUID().uuidString.lowercased(),
            isOutgoing: true,
            peerName: peerName
        )
        session = active
        lock.unlock()
        publish(state: .ringingOutgoing)
        startOfferLoop(active)
        return true
    }

    func accept() {
        lock.lock()
        guard let active = session, !active.isOutgoing else {
            lock.unlock()
            return
        }
        lock.unlock()
        publish(state: .connecting)
        let accepted = sendSignal(
            sessionId: active.sessionId,
            P2pCallSignal(
                kind: P2pBleProtocol.chatKindCallAccept,
                callId: active.callId,
                sampleRateHz: 16_000,
                frameMs: 20,
                framesPerPacket: Self.framesPerPacket,
                bitrateBps: Self.defaultBitrate,
                saltHex: active.localSaltHex
            )
        )
        guard accepted else {
            endCall(reason: "error", sendEnd: false)
            return
        }
        beginInCall(active)
    }

    func reject() {
        lock.lock()
        let active = session
        lock.unlock()
        guard let active else { return }
        _ = sendSignal(
            sessionId: active.sessionId,
            P2pCallSignal(
                kind: P2pBleProtocol.chatKindCallReject,
                callId: active.callId,
                reason: "declined"
            )
        )
        endCall(reason: "declined", sendEnd: false)
    }

    func endCall(reason: String = "hangup", sendEnd: Bool = true) {
        lock.lock()
        guard let active = session else {
            lock.unlock()
            return
        }
        session = nil
        lock.unlock()
        active.offerTimer?.cancel()
        active.watchdogTimer?.cancel()
        if sendEnd {
            _ = sendSignal(
                sessionId: active.sessionId,
                P2pCallSignal(
                    kind: P2pBleProtocol.chatKindCallEnd,
                    callId: active.callId,
                    reason: reason
                )
            )
        }
        active.engine.stopCapture()
        active.engine.stopPlayback()
        active.engine.release()
        DispatchQueue.main.async { [weak self] in
            self?.call = nil
        }
    }

    func setMuted(_ muted: Bool) {
        lock.lock()
        session?.muted = muted
        session?.engine.muted = muted
        let active = session
        lock.unlock()
        guard let active else { return }
        publishCurrent(active)
    }

    func setSpeaker(_ enabled: Bool) {
        lock.lock()
        session?.speakerOn = enabled
        let active = session
        lock.unlock()
        guard let active else { return }
        active.engine.setSpeakerEnabled(enabled)
        publishCurrent(active)
    }

    // ---------------------------------------------------------------------------------------
    // Signaling handlers
    // ---------------------------------------------------------------------------------------

    private func onInboundSignal(sessionId: UUID, signal: P2pCallSignal) {
        switch signal.kind {
        case P2pBleProtocol.chatKindCallOffer:
            onOffer(sessionId: sessionId, signal: signal)
        case P2pBleProtocol.chatKindCallRing:
            lock.lock()
            let active = session
            if let active, active.callId == signal.callId, active.isOutgoing {
                active.ringReceived = true
            }
            lock.unlock()
        case P2pBleProtocol.chatKindCallAccept:
            onAccept(sessionId: sessionId, signal: signal)
        case P2pBleProtocol.chatKindCallReject,
             P2pBleProtocol.chatKindCallBusy,
             P2pBleProtocol.chatKindCallEnd:
            lock.lock()
            let matches = session?.callId == signal.callId
            lock.unlock()
            if matches {
                endCall(reason: signal.reason ?? "remote", sendEnd: false)
            }
        default:
            break
        }
    }

    private func onOffer(sessionId: UUID, signal: P2pCallSignal) {
        guard let saltHex = signal.saltHex, !saltHex.isEmpty else { return }
        if let ts = signal.timestampMillis {
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            if now - ts > Self.offerFreshnessMillis { return }
        }
        lock.lock()
        if let existing = session {
            let duplicate = existing.callId == signal.callId
            lock.unlock()
            if duplicate {
                _ = sendSignal(
                    sessionId: sessionId,
                    P2pCallSignal(kind: P2pBleProtocol.chatKindCallRing, callId: signal.callId)
                )
            } else {
                _ = sendSignal(
                    sessionId: sessionId,
                    P2pCallSignal(
                        kind: P2pBleProtocol.chatKindCallBusy,
                        callId: signal.callId,
                        reason: "busy"
                    )
                )
            }
            return
        }
        let peerName = signal.senderName
            ?? transports[sessionId]?.peerName()
            ?? ""
        let active = ActiveSession(
            sessionId: sessionId,
            callId: signal.callId,
            isOutgoing: false,
            peerName: peerName
        )
        active.remoteSaltHex = saltHex
        session = active
        lock.unlock()
        publish(state: .ringingIncoming)
        _ = sendSignal(
            sessionId: sessionId,
            P2pCallSignal(kind: P2pBleProtocol.chatKindCallRing, callId: signal.callId)
        )
        notifyIncomingRing(peerName: peerName)
        scheduleRingTimeout(active)
    }

    private func onAccept(sessionId: UUID, signal: P2pCallSignal) {
        lock.lock()
        guard let active = session,
              active.callId == signal.callId,
              active.isOutgoing,
              let saltHex = signal.saltHex, !saltHex.isEmpty else {
            lock.unlock()
            return
        }
        active.remoteSaltHex = saltHex
        active.offerTimer?.cancel()
        active.offerTimer = nil
        lock.unlock()
        beginInCall(active)
    }

    // ---------------------------------------------------------------------------------------
    // Audio
    // ---------------------------------------------------------------------------------------

    private func beginInCall(_ active: ActiveSession) {
        lock.lock()
        guard session === active, let transport = transports[active.sessionId],
              let sessionKey = transport.sessionKey(),
              let remoteSalt = active.remoteSaltHex else {
            lock.unlock()
            endCall(reason: "error", sendEnd: true)
            return
        }
        // Caller encrypts with a2b (its own salt), callee with b2a — mirrored on receive.
        active.txKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: sessionKey,
            callId: active.callId,
            callerToCallee: active.isOutgoing,
            directionSaltHex: active.localSaltHex
        )
        active.rxKey = P2pCallProtocol.deriveDirectionalKey(
            contactKey: sessionKey,
            callId: active.callId,
            callerToCallee: !active.isOutgoing,
            directionSaltHex: remoteSalt
        )
        lock.unlock()

        let engine = active.engine
        engine.bitrateBps = Self.defaultBitrate
        guard engine.prepare(), engine.startPlayback() else {
            endCall(reason: "error", sendEnd: true)
            return
        }
        engine.muted = active.muted
        engine.setSpeakerEnabled(active.speakerOn)
        let started = engine.startCapture { [weak self, weak active] frame in
            guard let self, let active else { return }
            self.lock.lock()
            guard self.session === active, let txKey = active.txKey else {
                self.lock.unlock()
                return
            }
            active.pendingFrames.append(frame)
            guard active.pendingFrames.count >= Self.framesPerPacket else {
                self.lock.unlock()
                return
            }
            let frames = active.pendingFrames
            active.pendingFrames = []
            let seq = active.txSeq
            active.txSeq = active.txSeq &+ UInt32(frames.count)
            let transport = self.transports[active.sessionId]
            self.lock.unlock()
            guard let bundle = P2pCallProtocol.packFrameBundle(frames),
                  let packet = P2pCallProtocol.encodeAudioFrame(
                    txKey: txKey,
                    callTag: active.callTag,
                    seq: seq,
                    bundle: bundle
                  ) else {
                return
            }
            _ = transport?.sendPacket(packet)
        }
        guard started else {
            endCall(reason: "error", sendEnd: true)
            return
        }
        lock.lock()
        active.connectedAt = Date()
        active.lastRxAt = Date()
        lock.unlock()
        publish(state: .inCall)
        startRxWatchdog(active)
    }

    private func onInboundAudio(_ packet: Data) {
        lock.lock()
        guard let active = session, let rxKey = active.rxKey else {
            lock.unlock()
            return
        }
        lock.unlock()
        guard let decoded = P2pCallProtocol.decodeAudioFrame(
            rxKey: rxKey,
            expectedCallTag: active.callTag,
            packet: packet
        ), let frames = P2pCallProtocol.unpackFrameBundle(decoded.bundle) else {
            return
        }
        let packetStart = UInt64(decoded.seq)
        let packetEnd = packetStart + UInt64(frames.count)
        lock.lock()
        // AES-GCM authenticates a packet but accepts the same valid ciphertext more than once.
        // Late voice is not useful, so a monotonic receive sequence safely rejects both replayed
        // and out-of-order packets without changing the legacy wire format.
        guard session === active, packetStart >= active.rxExpectedSeq else {
            lock.unlock()
            return
        }
        active.rxExpectedSeq = packetEnd
        active.lastRxAt = Date()
        lock.unlock()
        for (index, frame) in frames.enumerated() {
            active.engine.submitFrame(seq: Int(decoded.seq) + index, opus: frame)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Timers
    // ---------------------------------------------------------------------------------------

    private func startOfferLoop(_ active: ActiveSession) {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .userInitiated))
        timer.schedule(deadline: .now(), repeating: Self.offerRetryInterval)
        timer.setEventHandler { [weak self, weak active] in
            guard let self, let active else { return }
            self.lock.lock()
            let live = self.session === active && active.connectedAt == nil
            let ringReceived = active.ringReceived
            self.lock.unlock()
            guard live else {
                active.offerTimer?.cancel()
                return
            }
            let elapsed = Date().timeIntervalSince(active.startedAt)
            if !ringReceived && elapsed >= Self.offerTimeout {
                self.endCall(reason: "unreachable", sendEnd: false)
                return
            }
            if elapsed >= Self.ringTimeout {
                self.endCall(reason: "timeout", sendEnd: true)
                return
            }
            _ = self.sendSignal(
                sessionId: active.sessionId,
                P2pCallSignal(
                    kind: P2pBleProtocol.chatKindCallOffer,
                    callId: active.callId,
                    senderName: ProfileMetadataStore.loadFullName()
                        .trimmingCharacters(in: .whitespacesAndNewlines),
                    timestampMillis: Int64(Date().timeIntervalSince1970 * 1000),
                    sampleRateHz: 16_000,
                    frameMs: 20,
                    framesPerPacket: Self.framesPerPacket,
                    bitrateBps: Self.defaultBitrate,
                    saltHex: active.localSaltHex
                )
            )
        }
        active.offerTimer = timer
        timer.resume()
    }

    private func scheduleRingTimeout(_ active: ActiveSession) {
        DispatchQueue.global().asyncAfter(deadline: .now() + Self.ringTimeout) { [weak self, weak active] in
            guard let self, let active else { return }
            self.lock.lock()
            let stillRinging = self.session === active && active.connectedAt == nil && !active.isOutgoing
            self.lock.unlock()
            if stillRinging {
                _ = self.sendSignal(
                    sessionId: active.sessionId,
                    P2pCallSignal(
                        kind: P2pBleProtocol.chatKindCallReject,
                        callId: active.callId,
                        reason: "timeout"
                    )
                )
                self.endCall(reason: "timeout", sendEnd: false)
            }
        }
    }

    private func startRxWatchdog(_ active: ActiveSession) {
        let timer = DispatchSource.makeTimerSource(queue: DispatchQueue.global(qos: .utility))
        timer.schedule(deadline: .now() + Self.watchdogTick, repeating: Self.watchdogTick)
        timer.setEventHandler { [weak self, weak active] in
            guard let self, let active else { return }
            self.lock.lock()
            let live = self.session === active && active.connectedAt != nil
            self.lock.unlock()
            guard live else {
                active.watchdogTimer?.cancel()
                return
            }
            if Date().timeIntervalSince(active.lastRxAt) > Self.rxTimeout {
                self.endCall(reason: "rx_timeout", sendEnd: true)
            }
        }
        active.watchdogTimer = timer
        timer.resume()
    }

    // ---------------------------------------------------------------------------------------
    // Publishing + notification
    // ---------------------------------------------------------------------------------------

    private func publish(state: CallInfo.State) {
        lock.lock()
        guard let active = session else {
            lock.unlock()
            return
        }
        let info = CallInfo(
            sessionId: active.sessionId,
            callId: active.callId,
            peerName: active.peerName,
            state: state,
            muted: active.muted,
            speakerOn: active.speakerOn,
            connectedAt: active.connectedAt
        )
        lock.unlock()
        DispatchQueue.main.async { [weak self] in
            self?.call = info
        }
    }

    private func publishCurrent(_ active: ActiveSession) {
        let state: CallInfo.State
        if active.connectedAt != nil {
            state = .inCall
        } else if active.isOutgoing {
            state = .ringingOutgoing
        } else {
            state = .ringingIncoming
        }
        publish(state: state)
    }

    private func notifyIncomingRing(peerName: String) {
        let content = UNMutableNotificationContent()
        content.title = peerName.isEmpty
            ? NSLocalizedString("RESCUE_CALL_INCOMING", comment: "")
            : peerName
        content.body = NSLocalizedString("RESCUE_CALL_INCOMING_BODY", comment: "")
        content.sound = .defaultRingtone
        let request = UNNotificationRequest(
            identifier: "rescue-call-ring",
            content: content,
            trigger: nil
        )
        UNUserNotificationCenter.current().add(request)
    }
}
