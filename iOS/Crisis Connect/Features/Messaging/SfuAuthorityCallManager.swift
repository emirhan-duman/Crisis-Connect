//
//  SfuAuthorityCallManager.swift
//  Crisis Connect
//
//  Process-wide orchestration for an SFU authority call — the SFU counterpart of
//  InternetCallManager and the iOS mirror of Android's SfuAuthorityCallManager. Ties the
//  ring/signaling (SfuRingManager over SfuAuthorityCallSignaling), the Cloudflare Realtime media
//  engine (SfuCallManager) and the room roster: when the ring reaches a room (offer accepted both
//  sides) it joins the SFU; the media engine publishes/pulls and runs the MLS handshake itself.
//
//  Receive is app-wide: SfuAuthorityCallReceiver routes every SFU signal it sees into
//  onSfuSignal (both the incoming offer AND the outgoing call's answer), so this manager never
//  needs its own per-call listener. Gated by SfuCallConfig; the citizen P2P path is untouched.
//
//  CALLKIT: uses the app's single shared CXProvider (SharedCallProvider), making itself the delegate
//  when it starts/receives a call. (An earlier version created a SECOND provider here; that broke
//  CallKit action delivery — with multiple providers the system routed answer/end actions to the
//  wrong one, so answering an incoming 1:1 call never connected. One provider per app is required.)
//  Only one call is active at a time, so a single delegate-swapping provider is safe.
//  This buys foreground/background native ringing, lock-screen answer, and the documented
//  manual-audio handoff (WebRTC's audio unit starts in the provider's didActivate). PushKit VoIP
//  ringing for a FORCE-QUIT app is still absent — that needs a backend raw-APNs VoIP sender and
//  is deferred with the 1:1 VoIP work; here CallKit only rings while the process is alive to
//  receive the Firestore call signal.
//

import AVFoundation
import CallKit
import Combine
import FirebaseAuth
import FirebaseFirestore
import Foundation
import UIKit
import WebRTC

/// Minimal UI snapshot of the current SFU authority call, for the overlay host.
struct SfuUiCall: Equatable {
    let peerName: String
    let peerUid: String
    let incoming: Bool
    let state: SfuCallState
    /// Moment the call connected — drives the overlay timer.
    let connectedAt: Date?
    /// Whether this was placed as a VIDEO call (drives the caller's initial camera).
    let video: Bool
}

@MainActor
final class SfuAuthorityCallManager: NSObject, ObservableObject {
    static let shared = SfuAuthorityCallManager()

    private static let heartbeatInterval: TimeInterval = 10
    private static let peerStale: TimeInterval = 30
    private static let peerLostEnd: TimeInterval = 30

    @Published private(set) var uiCall: SfuUiCall?
    /// The live media engine (nil until a room is joined). The overlay observes it directly for
    /// mute/camera state and the remote video tracks.
    @Published private(set) var media: SfuCallManager?
    @Published private(set) var speakerOn = false

    private var signaling: SfuAuthorityCallSignaling?
    private var ring: SfuRingManager?
    private var mediaStateSink: AnyCancellable?
    private var livenessListener: ListenerRegistration?
    private var livenessTask: Task<Void, Never>?

    // CallKit: the app's single shared provider (see SharedCallProvider). We make ourselves its
    // delegate when we start/receive an authority call.
    private let cxProvider = SharedCallProvider.shared.provider
    private let cxController = CXCallController()
    /// The CallKit UUID for the current call, so state changes report against the right call and
    /// a redundant end/report is a cheap no-op.
    private var cxCallUUID: UUID?
    private var reportedConnected = false

    private var boundChannelId = ""
    private var boundKind: SfuAuthorityCallSignaling.ChannelKind = .hierarchy
    private var myUid = ""
    private var incoming = false
    private var connectedAt: Date?
    private var usedVideo = false
    private var callLogged = false
    private var lastPeerAlive = Date.distantPast
    private var everSawPeer = false
    /// Self profile photo URL for the SFU roster (loaded once) — without it the web dashboard renders
    /// generic initials for the iOS participant instead of their photo. Same source as Android.
    private var selfPhotoUrl: String?

    private override init() {
        super.init()
        // The 1:1 manager already puts the shared RTCAudioSession into manual-audio mode; assert
        // it here too in case an SFU call is the first to run.
        RTCAudioSession.sharedInstance().useManualAudio = true
        loadSelfPhotoUrl()
    }

    /// Load the self profile photo URL once for the SFU roster: `users/{uid}.photoURL`, falling back
    /// to the Firebase Auth photoURL — mirrors Android so the web call UI shows our photo, not
    /// generic initials. Async-safe: the Firestore read suspends and resumes back on the main actor.
    private func loadSelfPhotoUrl() {
        guard let user = Auth.auth().currentUser else { return }
        let uid = user.uid
        let authPhoto = user.photoURL?.absoluteString
        Task { [weak self] in
            let snapshot = try? await Firestore.firestore()
                .collection("users").document(uid).getDocument()
            let stored = (snapshot?.get("photoURL") as? String)?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            self?.selfPhotoUrl = (stored?.isEmpty == false ? stored : nil) ?? authPhoto
        }
    }

    /// True when this manager currently owns an active/ringing call.
    var hasActiveCall: Bool { uiCall != nil }

    /// Place an outgoing authority SFU call to `peerUid` over `channelId`.
    func startOutgoing(
        channelId: String,
        kind: SfuAuthorityCallSignaling.ChannelKind,
        peerUid: String,
        peerName: String,
        video: Bool = false
    ) {
        guard SfuCallConfig.enabled, uiCall == nil else { return }
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        NSLog("SfuAuthorityCall: startOutgoing channel=%@ peer=%@ video=%d", channelId, peerUid, video ? 1 : 0)
        requestCallPermissions(video: video) { [weak self] granted in
            guard let self, granted, self.uiCall == nil else { return }
            self.incoming = false
            self.bindSignaling(channelId: channelId, kind: kind, myUid: user.uid)
            self.usedVideo = video
            // Native outgoing-call reporting: audio starts when CallKit activates the session.
            let uuid = UUID()
            self.cxCallUUID = uuid
            self.reportedConnected = false
            SharedCallProvider.shared.makeActive(self)
            let handle = CXHandle(type: .generic, value: peerName)
            let startAction = CXStartCallAction(call: uuid, handle: handle)
            startAction.isVideo = video
            self.cxController.request(CXTransaction(action: startAction)) { error in
                if let error {
                    NSLog("SfuAuthorityCall: CXStartCallAction failed: %@", String(describing: error))
                }
            }
            self.ring?.startCall(peerUid: peerUid, peerName: peerName, video: video)
            // PRE-WARM: the caller knows the room id the moment it rings — join the SFU, publish,
            // and run the MLS claim NOW so the answer only has the pull left (~2s instead of ~7s
            // to two-way audio). The mic stays muted (hold) until the callee actually accepts
            // (privacy); an unanswered/rejected ring tears the media down via onRingState.
            if let roomId = self.ring?.roomId {
                self.joinMedia(roomId: roomId, micHold: true)
            }
        }
    }

    /// Feed an inbound SFU signal into the ring. Called app-wide by SfuAuthorityCallReceiver for
    /// the channel the signal arrived on. A fresh signal while idle (an incoming offer) binds the
    /// transport to that channel so our answer goes back over the right callSignals collection.
    func onSfuSignal(
        channelId: String,
        kind: SfuAuthorityCallSignaling.ChannelKind,
        myUid: String,
        fromUid: String,
        signal: [String: Any],
        peerName: String? = nil
    ) {
        NSLog("SfuAuthorityCall: onSfuSignal type=%@ from=%@ channel=%@",
              signal["type"] as? String ?? "?", fromUid, channelId)
        if ring == nil || uiCall == nil {
            incoming = true
            bindSignaling(channelId: channelId, kind: kind, myUid: myUid)
        } else if boundChannelId != channelId {
            // A signal for a different channel while we're busy — ignore (ring will busy it anyway).
            return
        }
        if let peerName, ring?.peerName == nil || ring?.peerName?.isEmpty == true {
            ring?.peerName = peerName
        }
        ring?.handleSignal(fromUid: fromUid, signal: signal)
    }

    func accept() {
        // Route through CallKit so the system knows the call was answered from the app UI too
        // (keeps the native call state in sync when the incoming banner is showing).
        guard let uuid = cxCallUUID, ring?.state == .incoming else {
            performAccept()
            return
        }
        cxController.request(CXTransaction(action: CXAnswerCallAction(call: uuid))) { [weak self] error in
            guard error != nil else { return }
            // CallKit refused (rare) — answer directly so the user isn't stuck.
            Task { @MainActor [weak self] in self?.performAccept() }
        }
    }

    private func performAccept() {
        requestCallPermissions(video: false) { [weak self] granted in
            guard let self, granted else { return }
            self.ring?.accept()
            self.emitUi()
        }
    }

    func reject() { ring?.reject() }

    func end() {
        // Drive the end through CallKit so its UI tears down too; the action handler calls
        // ring.end(). Falls back to a direct end if there's no live CallKit call.
        guard let uuid = cxCallUUID else {
            ring?.end()
            return
        }
        cxController.request(CXTransaction(action: CXEndCallAction(call: uuid))) { [weak self] error in
            guard error != nil else { return }
            Task { @MainActor [weak self] in self?.ring?.end() }
        }
    }

    func toggleMute() {
        media?.toggleMute()
        emitUi()
    }

    func toggleCamera() {
        guard let media else { return }
        if media.cameraOn {
            media.toggleCamera()
            emitUi()
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { granted in
            Task { @MainActor [weak self] in
                guard let self, granted else { return }
                self.media?.toggleCamera()
                self.usedVideo = true
                self.emitUi()
            }
        }
    }

    func switchCamera() { media?.switchCamera() }

    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
        speakerOn = on
    }

    // MARK: - Wiring

    private func bindSignaling(
        channelId: String,
        kind: SfuAuthorityCallSignaling.ChannelKind,
        myUid: String
    ) {
        self.myUid = myUid
        boundChannelId = channelId
        boundKind = kind
        usedVideo = false
        callLogged = false
        let signaling = SfuAuthorityCallSignaling(channelId: channelId, myUid: myUid, kind: kind)
        self.signaling = signaling
        ring = SfuRingManager(
            sender: { toUid, signal in signaling.send(toUid: toUid, signal: signal) },
            onState: { [weak self] state in self?.onRingState(state) },
            onRoom: { [weak self] roomId, _ in self?.onRoom(roomId: roomId) }
        )
    }

    private func onRingState(_ state: SfuCallState) {
        switch state {
        case .incoming:
            reportIncomingToCallKit()
        case .connected:
            // Caller side: tell CallKit the outgoing call connected (drives its timer).
            if !incoming, !reportedConnected, let uuid = cxCallUUID {
                reportedConnected = true
                cxProvider.reportOutgoingCall(with: uuid, connectedAt: Date())
            }
        case .ended, .idle:
            break
        default:
            break
        }

        if state == .ended || state == .idle {
            maybeWriteCallLog()
            connectedAt = nil
            teardownMedia()
            // Tell CallKit the call is over (idempotent if it ended via a CXEndCallAction already).
            if let uuid = cxCallUUID {
                cxProvider.reportCall(with: uuid, endedAt: Date(), reason: .remoteEnded)
            }
            uiCall = (state == .idle) ? nil : uiCall.map {
                SfuUiCall(peerName: $0.peerName, peerUid: $0.peerUid, incoming: $0.incoming,
                          state: state, connectedAt: nil, video: $0.video)
            }
            if state == .idle {
                ring = nil
                signaling = nil
                cxCallUUID = nil
                reportedConnected = false
            }
        } else {
            emitUi(state)
        }
    }

    /// Report the ringing inbound call to CallKit (native banner / lock-screen ring). A fresh
    /// UUID is minted for the incoming call if the transport didn't set one.
    private func reportIncomingToCallKit() {
        let uuid = cxCallUUID ?? UUID()
        cxCallUUID = uuid
        reportedConnected = false
        let update = CXCallUpdate()
        let name = ring?.peerName ?? ""
        update.remoteHandle = CXHandle(type: .generic, value: name.isEmpty ? " " : name)
        update.localizedCallerName = name.isEmpty ? nil : name
        update.hasVideo = ring?.video ?? false
        SharedCallProvider.shared.makeActive(self)
        cxProvider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let error {
                NSLog("SfuAuthorityCall: reportNewIncomingCall failed: %@", String(describing: error))
                // If CallKit refuses (e.g. Do Not Disturb rejects), don't leave a dangling call.
                Task { @MainActor [weak self] in self?.ring?.reject() }
            }
        }
    }

    private func emitUi(_ state: SfuCallState? = nil) {
        let st = state ?? ring?.state ?? .idle
        guard let ring, st != .idle else {
            uiCall = nil
            connectedAt = nil
            return
        }
        if st == .connected && connectedAt == nil {
            connectedAt = Date()
        }
        uiCall = SfuUiCall(
            peerName: ring.peerName ?? "",
            peerUid: ring.peerUid ?? "",
            incoming: incoming,
            state: st,
            connectedAt: connectedAt,
            video: ring.video
        )
    }

    /// Ring accepted on both sides → join the SFU room and start audio.
    private func onRoom(roomId: String) {
        NSLog("SfuAuthorityCall: onRoom roomId=%@ (preWarmed=%d)", roomId, media != nil ? 1 : 0)
        if let media {
            // Pre-warmed during the outgoing ring — the callee accepted, open the mic and go.
            media.setMicHold(false)
            return
        }
        joinMedia(roomId: roomId, micHold: false)
    }

    private func joinMedia(roomId: String, micHold: Bool) {
        // Audio activation is CallKit's job now: WebRTC's audio unit starts in the provider's
        // didActivate handoff. Configure the category up front so the route is right when it fires.
        configureAudioCategory()
        let name = ProfileMetadataStore.preferredDisplayName() ?? "iOS"
        let engine = SfuCallManager(roomId: roomId, displayName: name, photoUrl: selfPhotoUrl)
        media = engine
        engine.setMicHold(micHold)
        // A failed media join/connection ends the whole call (Android's onState → FAILED → end).
        mediaStateSink = engine.$state.sink { [weak self, weak engine] state in
            guard let self, self.media === engine else { return }
            if state == .failed {
                Task { @MainActor in self.end() }
            }
        }
        Task { [weak self, weak engine] in
            await engine?.join(video: self?.ring?.video == true)
            guard let self, let engine, self.media === engine else { return }
            self.emitUi()
            self.startLivenessWatchdog(roomId: roomId)
        }
    }

    /// Liveness: END the call when the peer goes silent — an app kill / crash / dead network on
    /// the other side never sends an "end" signal and never deletes its roster doc, which would
    /// leave THIS side sitting in a ghost call forever. The media engine already heartbeats our
    /// own roster entry; this watchdog only OBSERVES the peers' freshness.
    private func startLivenessWatchdog(roomId: String) {
        lastPeerAlive = Date()
        everSawPeer = false
        livenessListener?.remove()
        livenessListener = SfuRoomClient(roomId: roomId, selfUid: myUid)
            .listenRoster { [weak self] remotes in
                let fresh = remotes.contains { remote in
                    remote.updatedAt.map { Date().timeIntervalSince($0) < Self.peerStale } ?? true
                }
                Task { @MainActor [weak self] in
                    guard let self, fresh else { return }
                    self.lastPeerAlive = Date()
                    self.everSawPeer = true
                }
            }
        livenessTask?.cancel()
        livenessTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.heartbeatInterval * 1_000_000_000))
                guard let self else { return }
                let connected = self.ring?.state == .connected
                if connected, self.everSawPeer,
                   Date().timeIntervalSince(self.lastPeerAlive) > Self.peerLostEnd {
                    NSLog("SfuAuthorityCall: peer liveness lost — ending the call")
                    self.end()
                    return
                }
            }
        }
    }

    /// WhatsApp-style call summary in the chat, matching web/Android exactly: only the CALLER
    /// writes it (the shared encrypted message shows in both threads), wire format
    /// `call{"kind","status","durationSec"}` — rendered by the authority thread's call card.
    private func maybeWriteCallLog() {
        guard !callLogged, !incoming, boundKind == .hierarchy else { return }
        guard !boundChannelId.isEmpty,
              let peerUid = ring?.peerUid, !peerUid.isEmpty else { return }
        let peerName = ring?.peerName ?? ""
        callLogged = true
        let connected = connectedAt
        let durationSec = connected.map { max(0, Int(Date().timeIntervalSince($0))) } ?? 0
        let payload: [String: Any] = [
            "kind": usedVideo ? "video" : "audio",
            "status": connected != nil ? "ended" : "missed",
            "durationSec": durationSec
        ]
        guard let json = try? JSONSerialization.data(withJSONObject: payload) else { return }
        let body = "call" + String(decoding: json, as: UTF8.self)
        let channelId = boundChannelId
        Task {
            do {
                let client = HierarchyMessagingClient()
                let key = try await client.fetchChannelKey(channelId: channelId)
                try await client.send(
                    channelKey: key,
                    senderName: ProfileMetadataStore.preferredDisplayName() ?? "iOS",
                    recipientUid: peerUid,
                    recipientName: peerName,
                    text: body
                )
            } catch {
                NSLog("SfuAuthorityCall: call log write failed: %@", String(describing: error))
            }
        }
    }

    private func teardownMedia() {
        livenessTask?.cancel()
        livenessTask = nil
        livenessListener?.remove()
        livenessListener = nil
        mediaStateSink = nil
        media?.leave()
        media = nil
        speakerOn = false
    }

    // MARK: - Audio session (manual-audio mode; CallKit owns activate/deactivate)

    /// Pre-set the category/mode so the audio route is correct the moment CallKit activates the
    /// session in didActivate. WebRTC's RTCAudioSession wraps the shared AVAudioSession.
    private func configureAudioCategory() {
        let rtcSession = RTCAudioSession.sharedInstance()
        rtcSession.lockForConfiguration()
        do {
            try rtcSession.setCategory(.playAndRecord, mode: .voiceChat,
                                       options: [.allowBluetooth, .allowBluetoothA2DP])
        } catch {
            NSLog("SfuAuthorityCall: audio category config failed: %@", String(describing: error))
        }
        rtcSession.unlockForConfiguration()
    }

    /// Mic (and camera for video calls) permission before ringing out / accepting.
    private func requestCallPermissions(video: Bool, completion: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { micGranted in
            Task { @MainActor in
                guard micGranted else {
                    completion(false)
                    return
                }
                guard video else {
                    completion(true)
                    return
                }
                AVCaptureDevice.requestAccess(for: .video) { _ in
                    // Camera denial downgrades to voice rather than blocking the call.
                    Task { @MainActor in completion(true) }
                }
            }
        }
    }
}

// MARK: - CXProviderDelegate

extension SfuAuthorityCallManager: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor [weak self] in
            guard let self, self.ring != nil else { return }
            self.ring?.end()
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        // The ring/offer is already in flight (kicked off in startOutgoing); just acknowledge and
        // report that the outgoing call has started connecting.
        provider.reportOutgoingCall(with: action.callUUID, startedConnectingAt: Date())
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor [weak self] in self?.performAccept() }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            // Reject if still ringing, else hang up — ring.end()/reject() send the right signal.
            if self.ring?.state == .incoming {
                self.ring?.reject()
            } else {
                self.ring?.end()
            }
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            if self.media?.muted != action.isMuted {
                self.media?.toggleMute()
                self.emitUi()
            }
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        // Manual-audio handoff: WebRTC's audio unit may only start now.
        RTCAudioSession.sharedInstance().audioSessionDidActivate(audioSession)
        RTCAudioSession.sharedInstance().isAudioEnabled = true
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        RTCAudioSession.sharedInstance().audioSessionDidDeactivate(audioSession)
    }
}
