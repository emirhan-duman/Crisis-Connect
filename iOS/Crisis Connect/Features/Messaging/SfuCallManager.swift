//
//  SfuCallManager.swift
//  Crisis Connect
//
//  iOS media core for SFU group calls — the port of Android's SfuCallManager and the web's
//  sfu-call.ts. ONE RTCPeerConnection to the nearest Cloudflare Realtime edge that PUSHES our mic
//  (+ optional camera) and PULLS every other room participant's tracks. Media never flows peer to
//  peer — each client talks only to the SFU. Room coordination (who is in the room + their SFU
//  session id + track names) arrives via `setRoster`, relayed over Firestore by SfuRoomClient, so
//  this stays transport-agnostic like the web.
//
//  E2EE: every media frame's bytes route through the shared MLS group exactly like the web
//  (RTCRtpScriptTransform → encrypt_msg) and Android (native C++ FrameEncryptor → mls_*_frame):
//  MlsFrameCrypto attaches native FrameEncryptor/FrameDecryptor implementations to the prebuilt
//  stasel/WebRTC binary through its compiled-in (headerless) `setFrameEncryptor:` /
//  `setFrameDecryptor:` native hooks — see MlsFrameCrypto.mm for the ABI story. Before the MLS
//  handshake completes the Rust core returns empty frames (silence), never plaintext. Video pins
//  VP9 like Android/web: the MLS whole-frame crypto leaves no plaintext header, and only VP9 is
//  wired for that in the shared Rust (split_vp9_header).
//

import Combine
import FirebaseAuth
import FirebaseFirestore
import Foundation
import WebRTC

@MainActor
final class SfuCallManager: NSObject, ObservableObject {

    enum MediaState: Equatable { case idle, joining, live, failed, left }

    struct RemoteFeed: Identifiable, Equatable {
        var id: String { uid }
        let uid: String
        var hasVideo: Bool
    }

    @Published private(set) var state: MediaState = .idle
    @Published private(set) var muted = false
    @Published private(set) var cameraOn = false
    /// Pre-warm privacy hold (Android's setMicHold): the caller joins + publishes during the
    /// outgoing ring, but the mic stays silent until the callee actually accepts.
    private var micHold = false
    /// Group verification code once the MLS handshake completes (nil while establishing).
    @Published private(set) var safetyNumber: String?
    /// Remote video tracks keyed by "uid|source" so a peer's camera AND screen share each get their
    /// own tile instead of colliding on one uid key, for the grid renderer.
    @Published private(set) var remoteVideoTracks: [String: RTCVideoTrack] = [:]
    @Published private(set) var localVideoTrack: RTCVideoTrack?

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    private let api = SfuApiClient()
    private let room: SfuRoomClient
    private let roomId: String
    private let myUid: String
    private let displayName: String
    private let photoUrl: String?
    // MLS-E2EE each frame when the Rust core and the WebRTC native hooks are both present
    // (Android: `e2ee = MlsWorker.available && MlsFrameCrypto.available`). Immutable so the
    // nonisolated delegate can read it.
    private nonisolated let e2ee = MlsWorker.available && MlsFrameCrypto.available

    private var pc: RTCPeerConnection?
    private var sessionId: String?
    private var localAudioTrack: RTCAudioTrack?
    private var cameraCapturer: RTCCameraVideoCapturer?
    private var cameraSender: RTCRtpSender?
    private var usingFrontCamera = true

    private var mlsSession: MlsSession?
    private var rosterRegistration: ListenerRegistration?
    private var heartbeatTask: Task<Void, Never>?

    // "sessionId/trackName" already pulled, so a roster refresh never double-subscribes.
    private var pulled = Set<String>()
    // "sessionId/trackName" → failed pull attempts, so a transient per-track "not found" (publisher
    // still publishing) retries a bounded number of times instead of stranding that participant's
    // media silent/black for the whole call (mirrors Android's pullRetries).
    private var pullRetries: [String: Int] = [:]
    private static let maxPullRetries = 5
    private static let pullRetryDelayNanos: UInt64 = 2_000_000_000
    // Inbound transceiver mid → (uid, source) so remote video tracks attribute to a participant.
    private var midToUid: [String: String] = [:]
    private var midSource: [String: String] = [:]

    init(roomId: String, displayName: String, photoUrl: String? = nil) {
        self.roomId = roomId
        self.myUid = Auth.auth().currentUser?.uid ?? ""
        self.displayName = displayName
        self.photoUrl = photoUrl
        self.room = SfuRoomClient(roomId: roomId, selfUid: Auth.auth().currentUser?.uid ?? "")
        super.init()
    }

    // MARK: - Join

    func join(video: Bool = false) async {
        guard state == .idle, !myUid.isEmpty else { return }
        state = .joining
        do {
            await TurnCredentialsProvider.shared.refresh()
            let connection = try makePeerConnection()
            pc = connection

            // Mic: sendonly — this client only PUSHES its audio and PULLS others'.
            let audioSource = Self.factory.audioSource(with: emptyConstraints)
            let audioTrack = Self.factory.audioTrack(with: audioSource, trackId: "sfu_mic")
            audioTrack.isEnabled = !muted && !micHold
            localAudioTrack = audioTrack
            let audioInit = RTCRtpTransceiverInit()
            audioInit.direction = .sendOnly
            let audioTransceiver = connection.addTransceiver(with: audioTrack, init: audioInit)
            attachFrameCrypto(sender: audioTransceiver?.sender, source: "mic")

            if video {
                enableCameraTrack(on: connection)
            }

            // 1) Create the SFU session with our first (fully gathered) offer.
            let offer1 = try await connection.offerAsync(constraints: mediaConstraints)
            try await connection.setLocalAsync(offer1)
            let gathered1 = try await connection.gatheredLocalSdp()
            let session = try await api.createSession(offerSdp: gathered1)
            guard let sid = (session["sessionId"] as? String), !sid.isEmpty else {
                throw SfuApiError.requestFailed("session not created")
            }
            sessionId = sid
            try await connection.setRemoteAsync(sdp: answerSdp(from: session))

            // 2) Publish: name each local track by its id, tied to its transceiver mid, renegotiate.
            let localTracks: [[String: Any]] = connection.transceivers.compactMap { transceiver in
                guard let track = transceiver.sender.track, let mid = transceiver.mid.nilIfEmpty else { return nil }
                return ["location": "local", "mid": mid, "trackName": track.trackId]
            }
            let offer2 = try await connection.offerAsync(constraints: mediaConstraints)
            try await connection.setLocalAsync(offer2)
            let gathered2 = try await connection.gatheredLocalSdp()
            let publish = try await api.publishTracks(sessionId: sid, offerSdp: gathered2, tracks: localTracks)
            if let description = publish["sessionDescription"] as? [String: Any],
               let sdp = description["sdp"] as? String {
                try await connection.setRemoteAsync(sdp: sdp, type: .answer)
            }

            let published: [SfuPublishedTrack] = connection.transceivers.compactMap { transceiver in
                guard let track = transceiver.sender.track else { return nil }
                let kind = track.kind
                return SfuPublishedTrack(
                    trackName: track.trackId,
                    kind: kind,
                    source: kind == "audio" ? "mic" : "camera"
                )
            }
            room.publishSelf(
                name: displayName, photoUrl: photoUrl, cameraOn: cameraOn, muted: muted,
                sessionId: sid, tracks: published
            )
            startRoomSync()
            startMlsHandshake()
            state = .live
        } catch {
            NSLog("SfuCallManager: join failed: %@", String(describing: error))
            state = .failed
            leave()
        }
    }

    // MARK: - Roster → pull remote tracks

    private func startRoomSync() {
        rosterRegistration = room.listenRoster { [weak self] remotes in
            Task { @MainActor [weak self] in await self?.setRoster(remotes) }
        }
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                await MainActor.run { self?.room.heartbeat() }
            }
        }
    }

    private func setRoster(_ remotes: [SfuRemoteParticipant]) async {
        guard state == .live, let connection = pc, let sid = sessionId else { return }
        // Drop members who left: forget their pulled keys + mid attribution + video tiles.
        let liveUids = Set(remotes.map(\.uid))
        for (mid, uid) in midToUid where !liveUids.contains(uid) {
            let source = midSource[mid] ?? "camera"
            remoteVideoTracks.removeValue(forKey: Self.remoteTrackKey(uid: uid, source: source))
            midSource.removeValue(forKey: mid)
            midToUid.removeValue(forKey: mid)
        }
        pulled = pulled.filter { key in remotes.contains { key.hasPrefix("\($0.sessionId)/") } }

        for remote in remotes {
            let fresh = remote.tracks.filter { !pulled.contains("\(remote.sessionId)/\($0.trackName)") }
            guard !fresh.isEmpty else { continue }
            fresh.forEach { pulled.insert("\(remote.sessionId)/\($0.trackName)") }
            do {
                let trackObjs: [[String: Any]] = fresh.map {
                    ["location": "remote", "sessionId": remote.sessionId, "trackName": $0.trackName]
                }
                let result = try await api.pullTracks(sessionId: sid, tracks: trackObjs)
                // Which tracks subscribed cleanly: a per-track error inside a 200 response leaves the
                // track unsubscribed but is never thrown, so track it explicitly (Android's allOk).
                var okTrackNames = Set<String>()
                if let tracks = result["tracks"] as? [[String: Any]] {
                    for track in tracks {
                        guard let trackName = track["trackName"] as? String else { continue }
                        guard let mid = (track["mid"] as? String)?.nilIfEmpty,
                              (track["error"] as? String).nilIfEmpty == nil,
                              (track["errorDescription"] as? String).nilIfEmpty == nil else { continue }
                        midToUid[mid] = remote.uid
                        if let source = fresh.first(where: { $0.trackName == trackName })?.source {
                            midSource[mid] = source
                        }
                        okTrackNames.insert(trackName)
                    }
                }
                // The SFU offers the new recvonly m-lines; answer via renegotiate.
                if let description = result["sessionDescription"] as? [String: Any],
                   let sdp = description["sdp"] as? String {
                    try await connection.setRemoteAsync(sdp: sdp, type: .offer)
                    let answer = try await connection.answerAsync(constraints: mediaConstraints)
                    try await connection.setLocalAsync(answer)
                    let gathered = try await connection.gatheredLocalSdp()
                    _ = try await api.renegotiate(sessionId: sid, answerSdp: gathered)
                }
                // Any track the SFU didn't subscribe (publisher still publishing → transient "track
                // not found") is un-marked and re-pulled shortly, bounded, so it isn't stranded.
                let stranded = fresh.filter { !okTrackNames.contains($0.trackName) }
                if !stranded.isEmpty {
                    schedulePullRetry(stranded, remoteSessionId: remote.sessionId, remotes: remotes)
                }
            } catch {
                NSLog("SfuCallManager: pull for %@ failed: %@", remote.uid, String(describing: error))
                schedulePullRetry(fresh, remoteSessionId: remote.sessionId, remotes: remotes)
            }
        }
    }

    /// Un-mark stranded pulls and re-run `setRoster` after a short delay, bounded per track (cap
    /// `maxPullRetries`) — the publisher may not have finished publishing yet, so Cloudflare answers
    /// a per-track "not found" that never throws. Mirrors Android's bounded pull retry.
    private func schedulePullRetry(
        _ tracks: [SfuPublishedTrack],
        remoteSessionId: String,
        remotes: [SfuRemoteParticipant]
    ) {
        tracks.forEach { pulled.remove("\(remoteSessionId)/\($0.trackName)") }
        let retriable = tracks.filter { track in
            let key = "\(remoteSessionId)/\(track.trackName)"
            let attempts = (pullRetries[key] ?? 0) + 1
            pullRetries[key] = attempts
            return attempts < Self.maxPullRetries
        }
        guard !retriable.isEmpty else {
            NSLog("SfuCallManager: giving up pulling %d track(s) after %d attempts",
                  tracks.count, Self.maxPullRetries)
            return
        }
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: Self.pullRetryDelayNanos)
            await self?.setRoster(remotes)
        }
    }

    /// Grid key for a remote video track — a peer's camera and screen share are distinct tiles.
    private static func remoteTrackKey(uid: String, source: String) -> String { "\(uid)|\(source)" }

    // MARK: - MLS group handshake (establishes the E2EE key + safety number)

    private func startMlsHandshake() {
        guard MlsWorker.available else { return } // Rust core present but frame transform still blocked.
        Task { @MainActor [weak self] in
            guard let self else { return }
            let isCreator = (try? await self.room.claimMlsCreator()) ?? false
            let session = MlsSession(myUid: self.myUid, room: self.room) { [weak self] number in
                Task { @MainActor [weak self] in self?.safetyNumber = number }
            }
            self.mlsSession = session
            session.start(isCreator: isCreator)
        }
    }

    // MARK: - Controls

    func toggleMute() {
        muted.toggle()
        localAudioTrack?.isEnabled = !muted && !micHold
        republishSelf()
    }

    func setMicHold(_ hold: Bool) {
        micHold = hold
        localAudioTrack?.isEnabled = !muted && !micHold
    }

    func toggleCamera() {
        guard let connection = pc else { return }
        if cameraOn {
            cameraCapturer?.stopCapture()
            cameraCapturer = nil
            cameraSender?.track = nil
            localVideoTrack = nil
            cameraOn = false
        } else {
            enableCameraTrack(on: connection)
        }
        republishSelf()
    }

    func switchCamera() {
        guard cameraOn, cameraCapturer != nil else { return }
        usingFrontCamera.toggle()
        startCameraCapture()
    }

    func leave() {
        heartbeatTask?.cancel(); heartbeatTask = nil
        rosterRegistration?.remove(); rosterRegistration = nil
        mlsSession?.stop(); mlsSession = nil
        room.leave()
        cameraCapturer?.stopCapture(); cameraCapturer = nil
        pc?.close(); pc = nil
        localAudioTrack = nil
        localVideoTrack = nil
        cameraSender = nil
        remoteVideoTracks = [:]
        midToUid = [:]; midSource = [:]; pulled = []; pullRetries = [:]
        sessionId = nil
        state = .left
    }

    // MARK: - Camera

    private func enableCameraTrack(on connection: RTCPeerConnection) {
        let source = Self.factory.videoSource()
        let capturer = RTCCameraVideoCapturer(delegate: source)
        let track = Self.factory.videoTrack(with: source, trackId: "sfu_cam")
        track.isEnabled = true
        cameraCapturer = capturer
        localVideoTrack = track
        cameraOn = true
        if let existing = cameraSender {
            existing.track = track
        } else {
            let videoInit = RTCRtpTransceiverInit()
            videoInit.direction = .sendOnly
            let transceiver = connection.addTransceiver(with: track, init: videoInit)
            cameraSender = transceiver?.sender
            pinVp9(on: transceiver)
            attachFrameCrypto(sender: transceiver?.sender, source: "camera")
        }
        startCameraCapture()
    }

    private func startCameraCapture() {
        guard let capturer = cameraCapturer else { return }
        let position: AVCaptureDevice.Position = usingFrontCamera ? .front : .back
        guard let device = RTCCameraVideoCapturer.captureDevices().first(where: { $0.position == position })
            ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        let format = RTCCameraVideoCapturer.supportedFormats(for: device).min { lhs, rhs in
            func score(_ f: AVCaptureDevice.Format) -> Int {
                let d = CMVideoFormatDescriptionGetDimensions(f.formatDescription)
                return abs(Int(d.width) - 1280) + abs(Int(d.height) - 720)
            }
            return score(lhs) < score(rhs)
        }
        guard let format else { return }
        let fps = min(30, format.videoSupportedFrameRateRanges.map { Int($0.maxFrameRate) }.max() ?? 30)
        capturer.startCapture(with: device, format: format, fps: fps)
    }

    private func republishSelf() {
        guard let sid = sessionId, let connection = pc else { return }
        let tracks: [SfuPublishedTrack] = connection.transceivers.compactMap {
            guard let track = $0.sender.track else { return nil }
            return SfuPublishedTrack(
                trackName: track.trackId, kind: track.kind,
                source: track.kind == "audio" ? "mic" : "camera"
            )
        }
        room.publishSelf(
            name: displayName, photoUrl: photoUrl, cameraOn: cameraOn, muted: muted,
            sessionId: sid, tracks: tracks
        )
    }

    /// Attach the MLS FrameEncryptor to a local sender before publishing, so outgoing frames are
    /// E2E-encrypted (empty/silence until the group handshake lands — see MlsFrameCrypto).
    private func attachFrameCrypto(sender: RTCRtpSender?, source: String) {
        guard e2ee, let sender else { return }
        if MlsFrameCrypto.attachEncryptorToSender(sender) {
            NSLog("SfuCallManager: attached MLS FrameEncryptor to %@ sender", source)
        }
    }

    /// Pin VP9 (+rtx) on a video transceiver. The MLS per-frame crypto targets VP9 (whole-frame
    /// encryption, no plaintext header — see split_vp9_header in the shared Rust) and Android and
    /// the web pin the same, so all sides negotiate identical codecs.
    private func pinVp9(on transceiver: RTCRtpTransceiver?) {
        guard let transceiver else { return }
        let capabilities = Self.factory.rtpSenderCapabilities(forKind: kRTCMediaStreamTrackKindVideo)
        let vp9 = capabilities.codecs.filter {
            let name = $0.name.lowercased()
            return name == "vp9" || name == "rtx"
        }
        guard !vp9.isEmpty else { return }
        MlsFrameCrypto.applyCodecPreferences(vp9, toTransceiver: transceiver)
    }

    // MARK: - Peer connection plumbing

    private var emptyConstraints: RTCMediaConstraints {
        RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
    }

    private var mediaConstraints: RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: ["OfferToReceiveAudio": "true", "OfferToReceiveVideo": "true"],
            optionalConstraints: nil
        )
    }

    private func makePeerConnection() throws -> RTCPeerConnection {
        let config = RTCConfiguration()
        config.iceServers = TurnCredentialsProvider.shared.current().map {
            RTCIceServer(urlStrings: $0.urls, username: $0.username, credential: $0.credential)
        }
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherOnce
        guard let connection = Self.factory.peerConnection(with: config, constraints: emptyConstraints, delegate: self) else {
            throw SfuApiError.requestFailed("PeerConnection not created")
        }
        return connection
    }

    private func answerSdp(from response: [String: Any]) throws -> String {
        guard let description = response["sessionDescription"] as? [String: Any],
              let sdp = description["sdp"] as? String else {
            throw SfuApiError.requestFailed("no answer sdp")
        }
        return sdp
    }
}

// MARK: - RTCPeerConnectionDelegate

extension SfuCallManager: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        // Attach the MLS FrameDecryptor to every inbound receiver (audio AND video) so peers'
        // E2E-encrypted frames decode — Android does the same in onTrack.
        if e2ee, MlsFrameCrypto.attachDecryptor(toReceiver: transceiver.receiver) {
            NSLog("SfuCallManager: attached MLS FrameDecryptor to receiver mid=%@", transceiver.mid)
        }
        guard transceiver.mediaType == .video,
              let track = transceiver.receiver.track as? RTCVideoTrack else { return }
        let mid = transceiver.mid
        Task { @MainActor [weak self] in
            guard let self, let uid = self.midToUid[mid] else { return }
            // Route camera vs screen to separate tiles using the already-populated mid→source map.
            let source = self.midSource[mid] ?? "camera"
            self.remoteVideoTracks[Self.remoteTrackKey(uid: uid, source: source)] = track
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

// MARK: - Async WebRTC helpers (Cloudflare wants fully-gathered, non-trickle SDP)

private extension RTCPeerConnection {
    func offerAsync(constraints: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            offer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? SfuApiError.requestFailed("offer")) }
            }
        }
    }

    func answerAsync(constraints: RTCMediaConstraints) async throws -> RTCSessionDescription {
        try await withCheckedThrowingContinuation { continuation in
            answer(for: constraints) { sdp, error in
                if let sdp { continuation.resume(returning: sdp) }
                else { continuation.resume(throwing: error ?? SfuApiError.requestFailed("answer")) }
            }
        }
    }

    func setLocalAsync(_ sdp: RTCSessionDescription) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            setLocalDescription(sdp) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    func setRemoteAsync(sdp: String, type: RTCSdpType = .answer) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            setRemoteDescription(RTCSessionDescription(type: type, sdp: sdp)) { error in
                if let error { continuation.resume(throwing: error) } else { continuation.resume() }
            }
        }
    }

    /// Waits for ICE gathering to complete (gatherOnce policy), then returns the full local SDP —
    /// Cloudflare Realtime rejects a trickle offer with no candidates.
    func gatheredLocalSdp() async throws -> String {
        for _ in 0..<100 { // up to ~5s
            if iceGatheringState == .complete, let sdp = localDescription?.sdp {
                return sdp
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        guard let sdp = localDescription?.sdp else {
            throw SfuApiError.requestFailed("no gathered sdp")
        }
        return sdp
    }
}

/// Swift-visible face of the ObjC++ MlsFrameCrypto (bridging-header types don't cross into the
/// test target; this internal wrapper does via @testable import). SfuFrameCryptoTests drives the
/// attach + release lifecycle through the real prebuilt WebRTC binary with it.
enum MlsFrameCryptoBridge {
    static var available: Bool { MlsFrameCrypto.available }
    static var debugLiveInstances: Int { MlsFrameCrypto.debugLiveInstances }

    @discardableResult
    static func attachEncryptor(to sender: RTCRtpSender) -> Bool {
        MlsFrameCrypto.attachEncryptorToSender(sender)
    }

    @discardableResult
    static func attachDecryptor(to receiver: RTCRtpReceiver) -> Bool {
        MlsFrameCrypto.attachDecryptor(toReceiver: receiver)
    }
}

private extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

private extension Optional where Wrapped == String {
    var nilIfEmpty: String? {
        guard let self, !self.isEmpty else { return nil }
        return self
    }
}
