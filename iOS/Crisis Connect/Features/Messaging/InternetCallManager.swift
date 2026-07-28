//
//  InternetCallManager.swift
//  Crisis Connect
//
//  1:1 internet voice/video calls over WebRTC — the iOS port of Android's InternetCallManager.
//  Signaling rides the E2E relay (templateCode 200) with the exact JSON the web and Android
//  speak: { type, kind, callId, sdp?, candidate?, sdpMid?, sdpMLineIndex? }, kinds offer/answer/
//  ice/restart-offer/restart-answer/restart-request/reject/busy/end. The offer IS the ring
//  (45s timeout); a second incoming offer while on a call gets a polite "busy".
//
//  Video: the caller pre-negotiates ONE sendrecv video m-line (camera); the callee adopts whatever
//  video transceivers the remote offer created. Turning the camera on/off is a track swap — never a
//  renegotiation. (We offer a single video m-line on purpose: a second, track-less video m-line
//  makes an Android callee bind its remote tile to the wrong receiver and show black — see
//  addVideoTransceivers.)
//
//  Screen share (in-app ReplayKit capture) is adaptive, exactly like Android's own logic: when the
//  remote offer negotiated a dedicated second video m-line (iOS is the CALLEE of an Android/web
//  call) the screen streams on its own slot alongside the camera; otherwise (iOS is the CALLER —
//  single m-line by design) the screen track swaps the camera out on the shared sender, Android's
//  documented legacy fallback. A received screen share gets its own tile, routed by transceiver mid.
//
//  Resilience: transport drops get a 12s grace, then a caller-driven ICE restart (max 3) — the
//  callee asks via "restart-request", the caller ships a new-ufrag "restart-offer".
//
//  CallKit: every call is reported to the system (native lock-screen answer UI, audio-session
//  priority, cellular-call coexistence) through the app's ONE shared CXProvider (SharedCallProvider —
//  several providers make CallKit misroute answer/end actions) using WebRTC's manual-audio mode.
//  Force-quit devices ring via a backend APNs VoIP push (PushKit) that wakes us to report the call.
//  On that cold-launch path CallKit may never send didActivate, so the audio session is primed when
//  the call is reported and self-activated shortly after answering if CallKit stays silent.
//

import AVFoundation
import CallKit
import Combine
import Foundation
import Network
import UIKit
import WebRTC

@MainActor
final class InternetCallManager: NSObject, ObservableObject {
    static let shared = InternetCallManager()

    enum CallState: Equatable {
        case dialing
        case incoming
        case connecting
        case active
        case ended
    }

    /// Coarse live network quality for the in-call indicator, derived from getStats RTT + inbound
    /// loss (mirrors Android's CallQuality).
    enum CallQuality {
        case good
        case fair
        case poor
    }

    struct CallInfo: Equatable {
        let callId: String
        let peerName: String
        let sessionId: UUID
        let incoming: Bool
        var state: CallState
        var muted: Bool = false
        var speakerOn: Bool = false
        var reconnecting: Bool = false
        var cameraOn: Bool = false
        var remoteVideoActive: Bool = false
        // Local screen share being transmitted; remote peer's screen frames arriving.
        var sharingScreen: Bool = false
        var remoteScreenActive: Bool = false
        var startedAt: Date? = nil
        /// Stamped the instant the LOCAL user picks up, independent of the transport.
        ///
        /// The chat verdict must NOT be derived from `state == .active`: that is only ever set from
        /// RTCPeerConnectionState.connected, so an incoming call the user answered but which was torn
        /// down before ICE completed got filed as `.missed` — the one combination that bumps
        /// unreadCount (SOSChatStore.appendCallEvent). That is where the phantom "unread" badge on an
        /// answered call came from.
        var answeredAt: Date? = nil
        var quality: CallQuality = .good
    }

    @Published private(set) var call: CallInfo?
    /// Remote camera track for the overlay renderer (published separately: RTCVideoTrack isn't Equatable).
    @Published private(set) var remoteVideoTrack: RTCVideoTrack?
    @Published private(set) var localVideoTrack: RTCVideoTrack?
    /// Remote SCREEN-share track for the overlay stage — only populated when the peer negotiated a
    /// dedicated second video m-line (an Android/web-INITIATED call). On a single-m-line call a remote
    /// screen share arrives as a track swap on the camera m-line and shows in the normal remote tile.
    @Published private(set) var remoteScreenTrack: RTCVideoTrack?

    private static let ringTimeout: TimeInterval = 45
    private static let endedLingerSeconds: TimeInterval = 2
    private static let disconnectGrace: TimeInterval = 12
    private static let maxIceRestarts = 3
    private static let statsIntervalSeconds: TimeInterval = 2
    // Remote frames stop when the peer turns their camera off; flip the far view back to the avatar
    // after this long without a decoded frame (debounced so a brief stall doesn't blink). Android
    // uses a 1s watchdog with a ~2s window; iOS uses the same 1s poll with a ~1.5s window.
    private static let remoteVideoStaleSeconds: TimeInterval = 1.5

    private static let factory: RTCPeerConnectionFactory = {
        RTCInitializeSSL()
        return RTCPeerConnectionFactory(
            encoderFactory: RTCDefaultVideoEncoderFactory(),
            decoderFactory: RTCDefaultVideoDecoderFactory()
        )
    }()

    private var peerConnection: RTCPeerConnection?
    private var localAudioTrack: RTCAudioTrack?
    private var videoSender: RTCRtpSender?
    private var screenSender: RTCRtpSender?
    private var cameraCapturer: RTCCameraVideoCapturer?
    private var cameraSource: RTCVideoSource?
    // Screen-share (in-app ReplayKit capture): frames are converted to RTCVideoFrames and pushed into
    // a dedicated RTCVideoSource through a shim capturer. On a single-m-line call the screen track
    // swaps the camera out on `videoSender` (Android's documented fallback); when the peer negotiated a
    // dedicated second video m-line the screen rides `screenSender` alongside the camera.
    private var screenSource: RTCVideoSource?
    private var screenTrack: RTCVideoTrack?
    private var screenCaptureShim: RTCVideoCapturer?
    private var cameraWasOnBeforeShare = false
    private var broadcastServer: BroadcastFrameServer?
    private var remoteScreenFrameWatcher: RemoteVideoFrameWatcher?
    private var usingFrontCamera = true
    private var contact: ContactRecord?
    private var callId: String?
    private var isCaller = false
    private var remoteDescriptionSet = false
    private var pendingRemoteCandidates: [RTCIceCandidate] = []
    private var restartAttempts = 0
    private var ringTimeoutTask: Task<Void, Never>?
    private var disconnectGraceTask: Task<Void, Never>?
    private var teardownTask: Task<Void, Never>?
    private var hasBootstrapped = false

    // Proactive ICE restart on default-path handover (Wi-Fi ↔ cellular) — mirrors Android's
    // registerNetworkWatcher. Started when a call begins, cancelled in cleanup.
    private let pathMonitorQueue = DispatchQueue(label: "cc.internetcall.path-monitor")
    private var pathMonitor: NWPathMonitor?
    private var currentNetworkSignature: String?
    // Remote-video frame watchdog — mirrors Android's startRemoteVideoWatchdog. The renderer stamps
    // each decoded frame; the 1s poll flips remoteVideoActive off once frames go stale, on when they
    // resume, so the overlay shows an avatar instead of a frozen last frame.
    private var remoteFrameWatcher: RemoteVideoFrameWatcher?
    private var remoteVideoWatchdogTask: Task<Void, Never>?
    // One-shot auto-speaker when video starts (Android's autoSpeakerForVideo), but never against a
    // deliberate user earpiece choice made during the call.
    private var autoSpeakerApplied = false
    private var userAdjustedSpeaker = false
    // Live call-quality poll — mirrors Android's startStatsMonitor. Counters carry the previous
    // inbound sample so the interval loss fraction can be computed.
    private var statsTask: Task<Void, Never>?
    private var prevPacketsLost: Int64 = 0
    private var prevPacketsReceived: Int64 = 0
    private var loggedAudioFlow = false
    /// Last time each outbound video stream was logged, keyed by ssrc. NOT a single Bool: with a
    /// dedicated screen m-line there are TWO outbound video streams, and one flag logged whichever the
    /// unordered stats dictionary yielded first — in practice the camera, never the screen we are
    /// trying to measure. And one sample at call start says nothing about a rate that collapses later.
    private var lastVideoStatLogAt: [String: Date] = [:]
    /// Guards the case where CallKit never sends didActivate (VoIP cold launch) — see
    /// scheduleAudioActivationFallback.
    private var audioFallbackTask: Task<Void, Never>?

    // CallKit plumbing. WebRTC runs in manual-audio mode: the audio unit starts only when CallKit
    // activates the AVAudioSession (didActivate), which is the documented reliable ordering.
    // The app's single shared CXProvider (see SharedCallProvider). We become its delegate when we
    // start/receive a call. Using one provider app-wide is REQUIRED for CallKit to deliver answer/end
    // actions to the right manager.
    private let cxProvider = SharedCallProvider.shared.provider
    private let cxController = CXCallController()

    private override init() {
        super.init()
        // Configure the WebRTC audio session for a two-way VoIP call BEFORE any call so CallKit's
        // activation starts a working play-and-record unit. Without this the session can come up in a
        // playback-only category → neither mic capture nor earpiece playout works (audio dead both
        // ways even though video, which doesn't use the audio session, is fine).
        let audioConfig = RTCAudioSessionConfiguration.webRTC()
        audioConfig.category = AVAudioSession.Category.playAndRecord.rawValue
        audioConfig.mode = AVAudioSession.Mode.voiceChat.rawValue
        audioConfig.categoryOptions = [.allowBluetooth, .allowBluetoothA2DP]
        RTCAudioSessionConfiguration.setWebRTC(audioConfig)
        RTCAudioSession.sharedInstance().useManualAudio = true
        RTCAudioSession.sharedInstance().isAudioEnabled = false
    }

    /// Hooks the incoming-signal bus. Called once from the app's deferred launch tasks.
    func bootstrap() {
        guard !hasBootstrapped else { return }
        hasBootstrapped = true
        MessagingDiagLog.log("=== app launch: InternetCallManager bootstrap (build with call-reroute) ===")
        InternetCallSignalBus.shared.onSignal = { [weak self] signal in
            self?.onSignal(signal)
        }
        Task { await TurnCredentialsProvider.shared.refresh() }
    }

    private func callKitUUID(for id: String) -> UUID {
        // A PushKit VoIP wake already reported the incoming call under its own UUID (derived from
        // the wake push, before we could decrypt the WebRTC id). Adopt it for this call's whole
        // lifetime so answer/end actions target the call the system is already showing.
        if let voipCallUUIDOverride { return voipCallUUIDOverride }
        return UUID(uuidString: id) ?? BroadcastSessionId.fromRawIdentifier(id)
    }

    // Set by [prepareForVoipWake] when a VoIP push has already reported an incoming call to CallKit;
    // consumed by the next incoming offer so it reuses that UUID and skips a duplicate report.
    private var voipCallUUIDOverride: UUID?
    private var voipWakeSenderUid: String?

    private var voipWakeTimeoutTask: Task<Void, Never>?

    /// PushKit entry point. iOS REQUIRES a CallKit incoming-call report synchronously inside the
    /// VoIP push handler (before its completion runs) or it kills the app and stops delivering VoIP
    /// pushes. So we report a placeholder call immediately; the real offer — carried by the same
    /// message doc — arrives over the Firestore listener moments later and [onOffer] adopts this
    /// UUID and fills in the real caller name. If no offer materializes within the ring window we
    /// end the placeholder so CallKit can't hang. `completion` MUST be the PushKit completion.
    @MainActor
    func reportVoipWake(senderUid: String, callerName: String, hasVideo: Bool, completion: @escaping () -> Void) {
        // An active call already owns CallKit — just satisfy the push contract.
        if peerConnection != nil || voipCallUUIDOverride != nil {
            completion()
            return
        }
        let uuid = UUID()
        voipCallUUIDOverride = uuid
        voipWakeSenderUid = senderUid
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: callerName.isEmpty ? senderUid : callerName)
        update.localizedCallerName = callerName.isEmpty
            ? NSLocalizedString("VOICE_CALL_NOTIFICATION_BODY", comment: "")
            : callerName
        update.hasVideo = hasVideo
        cxProvider.reportNewIncomingCall(with: uuid, update: update) { [weak self] error in
            if let error {
                NSLog("InternetCall: VoIP reportNewIncomingCall failed: %@", String(describing: error))
                self?.voipCallUUIDOverride = nil
                self?.voipWakeSenderUid = nil
            }
            completion()
        }
        // Safety net: no offer within the ring window → tear the placeholder down.
        voipWakeTimeoutTask?.cancel()
        voipWakeTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard let self, !Task.isCancelled else { return }
            if self.peerConnection == nil, let stale = self.voipCallUUIDOverride {
                self.cxProvider.reportCall(with: stale, endedAt: Date(), reason: .unanswered)
                self.voipCallUUIDOverride = nil
                self.voipWakeSenderUid = nil
            }
        }
    }

    // MARK: - Outgoing

    func startCall(contact target: ContactRecord) {
        MessagingDiagLog.log("InternetCall startCall: peer=\(target.peerUid?.prefix(8) ?? "nil") supportsInternet=\(target.supportsInternet) available=\(InternetChatTransport.shared.isAvailable())")
        guard peerConnection == nil else {
            NSLog("InternetCall: refusing outgoing call; another call is active")
            MessagingDiagLog.log("InternetCall startCall: refused — another call active")
            return
        }
        guard target.supportsInternet else {
            MessagingDiagLog.log("InternetCall startCall: aborted — contact has no internet identity")
            return
        }
        AppAnalytics.callStarted(transport: "internet")
        Task { await TurnCredentialsProvider.shared.refresh() }

        let id = UUID().uuidString
        contact = target
        callId = id
        isCaller = true
        remoteDescriptionSet = false
        restartAttempts = 0
        pendingRemoteCandidates.removeAll()
        call = CallInfo(
            callId: id,
            peerName: target.name,
            sessionId: target.id,
            incoming: false,
            state: .dialing
        )
        guard let pc = createPeerConnection() else {
            cleanup(finalState: .ended, result: .canceled)
            return
        }
        peerConnection = pc
        addLocalAudio(pc)
        addVideoTransceivers(pc)
        scheduleRingTimeout(id)
        startNetworkWatcher()

        // Native call reporting: audio starts when CallKit activates the session. Own the shared
        // provider first so CallKit routes this call's actions back to us.
        SharedCallProvider.shared.makeActive(self)
        primeAudioSessionForCall()
        let cxUUID = callKitUUID(for: id)
        // The handle is what iOS hands back on a call-back, so it must identify the contact, not
        // describe it: a display name is ambiguous and unresolvable. The name the user sees comes
        // from localizedCallerName, which is set separately, so this stays invisible in the UI.
        let handle = CXHandle(type: .generic, value: target.id.uuidString)
        let startAction = CXStartCallAction(call: cxUUID, handle: handle)
        cxController.request(CXTransaction(action: startAction)) { error in
            if let error {
                NSLog("InternetCall: CXStartCallAction failed: %@", String(describing: error))
            }
        }
        cxProvider.reportOutgoingCall(with: cxUUID, startedConnectingAt: Date())

        pc.offer(for: Self.mediaConstraints()) { [weak self] sdp, error in
            guard let sdp, error == nil else {
                Task { @MainActor [weak self] in self?.endCall() }
                return
            }
            pc.setLocalDescription(sdp) { _ in }
            Task { @MainActor [weak self] in
                guard let self else { return }
                var signal = self.jsonOf("offer", id)
                signal["sdp"] = sdp.sdp
                self.sendSignal(signal)
            }
        }
    }

    // MARK: - User controls

    func accept() {
        // Route through CallKit so the system knows the call was answered from the app UI too.
        guard let id = callId, call?.state == .incoming else { return }
        let action = CXAnswerCallAction(call: callKitUUID(for: id))
        cxController.request(CXTransaction(action: action)) { [weak self] error in
            guard error != nil else { return }
            // CallKit refused (rare) — answer directly so the user isn't stuck.
            Task { @MainActor [weak self] in self?.performAccept() }
        }
    }

    private func performAccept() {
        guard let pc = peerConnection, callId != nil, call?.state == .incoming else { return }
        call?.state = .connecting
        // One-way latch, set here only and never cleared, so every teardown path below can tell
        // "picked up" from "still ringing" without waiting on the ICE result. The guard above is what
        // keeps a call that was never actually answered from being latched.
        call?.answeredAt = Date()
        // On a VoIP cold launch CallKit may never activate the audio session; arm the fallback.
        scheduleAudioActivationFallback()
        pc.answer(for: Self.mediaConstraints()) { [weak self] sdp, error in
            guard let sdp, error == nil else {
                Task { @MainActor [weak self] in self?.endCall() }
                return
            }
            pc.setLocalDescription(sdp) { _ in }
            Task { @MainActor [weak self] in
                guard let self, let id = self.callId else { return }
                var signal = self.jsonOf("answer", id)
                signal["sdp"] = sdp.sdp
                self.sendSignal(signal)
            }
        }
    }

    func reject() {
        guard let id = callId else { return }
        sendSignal(jsonOf("reject", id))
        cleanup(finalState: .ended, result: .rejected)
    }

    func endCall() {
        guard let id = callId else { return }
        sendSignal(jsonOf("end", id))
        let wasAnswered = callWasAnswered
        cleanup(finalState: .ended, result: wasAnswered ? .answered : (call?.incoming == true ? .rejected : .canceled))
    }

    func toggleMute() {
        guard let track = localAudioTrack else { return }
        track.isEnabled.toggle()
        call?.muted = !track.isEnabled
    }

    func setSpeaker(_ on: Bool) {
        // A deliberate user choice — remember it so autoSpeakerForVideo can never override an
        // explicit earpiece selection later in the same call.
        userAdjustedSpeaker = true
        applySpeaker(on)
    }

    private func applySpeaker(_ on: Bool) {
        // Route via RTCAudioSession (under its lock) — touching the raw AVAudioSession while WebRTC
        // manages it in manual-audio mode can disrupt the running audio unit.
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.overrideOutputAudioPort(on ? .speaker : .none)
        session.unlockForConfiguration()
        call?.speakerOn = on
    }

    /// Put the audio session into the CALL category before CallKit activates it. On a VoIP cold launch
    /// (app was force-quit) the session is otherwise still the default SoloAmbient, CallKit then never
    /// fires didActivate, `isAudioEnabled` stays false and the call is silent BOTH ways — mic sends 0
    /// bytes and incoming audio is never rendered.
    private func primeAudioSessionForCall() {
        let session = RTCAudioSession.sharedInstance()
        session.lockForConfiguration()
        try? session.setConfiguration(RTCAudioSessionConfiguration.webRTC())
        session.unlockForConfiguration()
    }

    /// CallKit normally activates the audio session (didActivate) once the answer is fulfilled. On the
    /// force-quit/VoIP cold-launch path that callback can simply never arrive, so activate ourselves
    /// if it hasn't shown up shortly after answering. Cancelled as soon as didActivate does fire.
    private func scheduleAudioActivationFallback() {
        audioFallbackTask?.cancel()
        audioFallbackTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            guard let self, !Task.isCancelled, self.call != nil, self.peerConnection != nil else { return }
            let session = RTCAudioSession.sharedInstance()
            guard !session.isAudioEnabled else { return }
            session.lockForConfiguration()
            try? session.setConfiguration(RTCAudioSessionConfiguration.webRTC(), active: true)
            session.isAudioEnabled = true
            session.unlockForConfiguration()
            MessagingDiagLog.log("audio fallback: CallKit didActivate never arrived → activated audio manually")
        }
    }

    /// Video just started (local camera turned on, or the first remote frame arrived) → default to
    /// the loudspeaker like a video call. Fires at most ONCE per call and never against a user who
    /// explicitly picked the earpiece. Mirrors Android's autoSpeakerForVideo (which only guards
    /// `speakerOn`; iOS additionally respects a deliberate user toggle).
    private func autoSpeakerForVideo() {
        guard peerConnection != nil, !autoSpeakerApplied, !userAdjustedSpeaker else { return }
        autoSpeakerApplied = true
        guard call?.speakerOn != true else { return }
        applySpeaker(true)
    }

    /// Toggle the front camera on/off. No-op when the call has no negotiated video m-line, or when a
    /// single-m-line call is currently sharing the screen on that same sender (the shared sender is
    /// busy — the overlay disables the camera button in that state).
    func toggleCamera() {
        guard peerConnection != nil, videoSender != nil else { return }
        if call?.sharingScreen == true, screenSender == nil { return }
        if call?.cameraOn == true {
            disableCamera()
        } else {
            enableCamera()
        }
    }

    func switchCamera() {
        guard call?.cameraOn == true, cameraCapturer != nil else { return }
        usingFrontCamera.toggle()
        startCapture()
    }

    /// Toggle in-app screen sharing. No-op without a negotiated video m-line.
    /// Full-device screen sharing rides a ReplayKit Broadcast Upload Extension (a SEPARATE process).
    /// The app listens on loopback for the whole active call; when the user starts the system broadcast
    /// (via RPSystemBroadcastPickerView in the overlay) the extension connects and streams screen
    /// frames here, which we inject into the call's screen track. There is no in-app "start" — we react
    /// to frames arriving and to the client disconnecting.
    private func startScreenBroadcastServer() {
        guard peerConnection != nil, broadcastServer == nil else { return }
        // forScreenCast tells WebRTC this is a screen, so under pressure the encoder sacrifices frame
        // rate and KEEPS resolution (legible text). A plain videoSource() is treated as a camera and
        // drops resolution first, which turned the shared screen to mush.
        let source = Self.factory.videoSource(forScreenCast: true)
        // NOTE: deliberately no adaptOutputFormat. It used to be called with a SQUARE 1920x1920 and
        // libwebrtc center-CROPS to the requested aspect ratio — every screen frame lost its sides,
        // which is what "the resolution is wrong" was. Resolution is capped on the SENDER instead
        // (applyScreenSenderParams), where a single divisor hits both axes so cropping is impossible.
        // The extension caps the frame RATE only — it does NOT cap frame size, and believing it did
        // is why the screen share ran at 4 megapixels on a 3.5 Mbps budget for so long.
        let shim = RTCVideoCapturer(delegate: source)
        let track = Self.factory.videoTrack(with: source, trackId: "cc-screen0")
        track.isEnabled = true
        screenSource = source
        screenCaptureShim = shim
        screenTrack = track

        let server = BroadcastFrameServer()
        var attached = false // mutated only on the server's serial delivery queue
        server.onFrame = { [weak self] pixelBuffer, rotationDegrees, timestampNs in
            // Feed the source directly off-main (RTCVideoSource is thread-safe, like the camera path).
            // The screen's orientation rides along as frame metadata so the peer rotates on render
            // instead of us baking a rotate into the pixels.
            let rotation: RTCVideoRotation
            switch rotationDegrees {
            case 90: rotation = ._90
            case 180: rotation = ._180
            case 270: rotation = ._270
            default: rotation = ._0
            }
            let frame = RTCVideoFrame(
                buffer: RTCCVPixelBuffer(pixelBuffer: pixelBuffer),
                rotation: rotation,
                timeStampNs: timestampNs
            )
            source.capturer(shim, didCapture: frame)
            if !attached {
                attached = true
                Task { @MainActor [weak self] in self?.onBroadcastFrameArrived() }
            }
        }
        server.onClientDisconnected = { [weak self] in
            attached = false
            Task { @MainActor [weak self] in self?.onBroadcastEnded() }
        }
        server.start()
        broadcastServer = server
    }

    /// First frame of a broadcast → route the screen track onto a sender and flip the UI to "sharing".
    private func onBroadcastFrameArrived() {
        guard call?.sharingScreen != true, screenTrack != nil else { return }
        attachScreenTrackToSender()
    }

    /// The extension disconnected (user stopped the broadcast) → drop the screen track but keep
    /// listening so another broadcast can start within the same call.
    private func onBroadcastEnded() {
        detachScreenTrackFromSender()
    }

    /// Route the screen track onto whichever sender is appropriate: a dedicated screen slot when the
    /// peer negotiated one (camera keeps streaming too), otherwise swap it onto the camera sender.
    private func attachScreenTrackToSender() {
        guard let screenTrack else { return }
        if let screenSender {
            screenSender.track = screenTrack
            applyScreenSenderParams(screenSender)
            if call?.cameraOn == true, let videoSender { applyCameraSenderParams(videoSender, sharing: true) }
        } else if let videoSender {
            cameraWasOnBeforeShare = (call?.cameraOn == true)
            videoSender.track = screenTrack
            applyScreenSenderParams(videoSender)
        }
        call?.sharingScreen = true
        reportVideoUpdate()
        autoSpeakerForVideo()
    }

    /// Detach the screen track from its sender and restore the camera, WITHOUT tearing down the server.
    private func detachScreenTrackFromSender() {
        if let screenSender, screenSender.track === screenTrack {
            screenSender.track = nil
            if call?.cameraOn == true, let videoSender { applyCameraSenderParams(videoSender, sharing: false) }
        } else if let videoSender, videoSender.track === screenTrack {
            if cameraWasOnBeforeShare {
                videoSender.track = localVideoTrack
                applyCameraSenderParams(videoSender, sharing: false)
            } else {
                videoSender.track = nil
            }
        }
        cameraWasOnBeforeShare = false
        if call?.sharingScreen == true {
            call?.sharingScreen = false
            reportVideoUpdate()
        }
    }

    /// Stop listening and release the screen source/track (call teardown).
    private func stopScreenBroadcastServer() {
        detachScreenTrackFromSender()
        broadcastServer?.stop()
        broadcastServer = nil
        screenTrack = nil
        screenSource = nil
        screenCaptureShim = nil
    }

    /// Screen-share encoding parameters.
    ///
    /// scaleResolutionDownBy is what stops the shared screen being a slideshow. ReplayKit hands us the
    /// panel's NATIVE buffer — 2420x1668 = 4.04 Mpx on an 11" iPad Pro — and NOTHING downscales it
    /// anywhere in the pipeline, so at this 3.5 Mbps ceiling and the extension's 15 fps the encoder
    /// gets 3_500_000 / 15 / 4_036_560 = 0.058 bits per pixel. For comparison LiveKit ships its
    /// 1080p15 screenshare profile at 0.080 bpp and our own Android sender runs 0.090 bpp. And
    /// forScreenCast:true selects MAINTAIN_RESOLUTION — the encoder may NOT trade resolution away —
    /// so the only lever it has left to close that deficit is dropping frames. That is the stutter.
    /// Halving both axes quarters the pixels (1210x834 = 1.01 Mpx) and lands at 0.231 bpp, and the
    /// S21 can only display ~1080 px of width anyway, so nothing visible is lost: those pixels were
    /// being paid for in bitrate and then thrown away at render.
    ///
    /// 2.0 is ONE divisor libwebrtc applies to BOTH axes, so the aspect ratio survives by construction
    /// and this can never crop — unlike the 1920x1920 adaptOutputFormat this file shipped once, where
    /// VideoAdapter center-cropped every frame to the requested square. It is rotation-proof too: when
    /// ReplayKit swaps width and height mid-share, a divisor still just divides.
    ///
    /// maxFramerate mirrors ScreenBroadcast.targetFps. Left unset, libwebrtc budgets the bitrate
    /// against its kDefaultVideoMaxFramerate of 60 — headroom held back for 45 frames a second that
    /// can never arrive.
    private func applyScreenSenderParams(_ sender: RTCRtpSender) {
        let params = sender.parameters
        params.encodings.first?.maxBitrateBps = NSNumber(value: 3_500_000)
        params.encodings.first?.scaleResolutionDownBy = NSNumber(value: 2.0)
        params.encodings.first?.maxFramerate = NSNumber(value: 15)
        sender.parameters = params
    }

    private func applyCameraSenderParams(_ sender: RTCRtpSender, sharing: Bool) {
        let params = sender.parameters
        params.encodings.first?.maxBitrateBps = NSNumber(value: sharing ? 1_200_000 : 2_500_000)
        sender.parameters = params
    }

    // MARK: - Incoming signals

    private func onSignal(_ signal: InternetCallSignalBus.Signal) {
        let payload = signal.payload
        let kind = (payload["type"] as? String).flatMap { $0.isEmpty ? nil : $0 }
            ?? (payload["kind"] as? String) ?? ""
        guard !kind.isEmpty, let id = payload["callId"] as? String, !id.isEmpty else { return }

        switch kind {
        case "offer":
            onOffer(from: signal.contact, id: id, payload: payload)
        case "answer", "restart-answer":
            onAnswer(id: id, payload: payload)
        case "ice":
            onRemoteIce(id: id, payload: payload)
        case "ice-batch":
            // Android collects trickle candidates on a 300 ms window and ships anything above ONE as a
            // single "ice-batch" (its flushLocalIce). We had no case for it, so the whole batch — in
            // practice the entire host-candidate burst — fell into `default: break` and was dropped.
            // The call then had to fall back to peer-reflexive/relay discovery: slower to connect, and
            // that slow window is exactly when a hangup lands before .connected. Elements carry the
            // same keys as the legacy single "ice" signal.
            for candidate in payload["candidates"] as? [[String: Any]] ?? [] {
                onRemoteIce(id: id, payload: candidate)
            }
        case "restart-offer":
            onRestartOffer(id: id, payload: payload)
        case "restart-request":
            if id == callId, isCaller { attemptIceRestart() }
        case "reject", "busy", "end":
            if id == callId {
                let result: SOSChatCallResult = callWasAnswered
                    ? .answered
                    : (isCaller ? .rejected : .missed)
                cleanup(finalState: .ended, result: result)
            }
        default:
            break
        }
    }

    private func onOffer(from: ContactRecord, id: String, payload: [String: Any]) {
        if peerConnection != nil {
            // Already on a call → politely decline.
            sendSignal(to: from, jsonOf("busy", id))
            return
        }
        guard let sdp = payload["sdp"] as? String, !sdp.isEmpty else { return }
        MessagingDiagLog.log("onOffer: incoming call from \(from.name) id=\(id) appState=\(UIApplication.shared.applicationState.rawValue)")
        Task { await TurnCredentialsProvider.shared.refresh() }

        contact = from
        callId = id
        isCaller = false
        remoteDescriptionSet = false
        restartAttempts = 0
        pendingRemoteCandidates.removeAll()
        call = CallInfo(
            callId: id,
            peerName: from.name,
            sessionId: from.id,
            incoming: true,
            state: .incoming
        )
        guard let pc = createPeerConnection() else {
            cleanup(finalState: .ended, result: .missed)
            return
        }
        peerConnection = pc
        addLocalAudio(pc)
        scheduleRingTimeout(id)
        startNetworkWatcher()

        // Own the shared provider before reporting so CallKit routes the answer/end actions for this
        // incoming call back to THIS manager (previously they went to another provider and the call
        // never connected on answer).
        SharedCallProvider.shared.makeActive(self)
        primeAudioSessionForCall()
        // A VoIP wake for THIS sender already reported the call to CallKit — just refresh its
        // metadata (real caller name) instead of reporting a duplicate, which iOS would reject.
        let update = CXCallUpdate()
        update.remoteHandle = CXHandle(type: .generic, value: from.id.uuidString)
        update.localizedCallerName = from.name
        update.hasVideo = false
        if voipCallUUIDOverride != nil, voipWakeSenderUid == from.peerUid {
            voipWakeSenderUid = nil
            voipWakeTimeoutTask?.cancel()
            voipWakeTimeoutTask = nil
            cxProvider.reportCall(with: callKitUUID(for: id), updated: update)
            let remote = RTCSessionDescription(type: .offer, sdp: sdp)
            pc.setRemoteDescription(remote) { [weak self] error in
                Task { @MainActor [weak self] in
                    guard let self, error == nil else { return }
                    self.remoteDescriptionSet = true
                    self.grabVideoSendersFromRemote()
                    self.bindRemoteVideoTracks()
                    self.drainPendingCandidates()
                }
            }
            return
        }
        cxProvider.reportNewIncomingCall(with: callKitUUID(for: id), update: update) { [weak self] error in
            guard let error else { return }
            MessagingDiagLog.log("reportNewIncomingCall FAILED id=\(id): \(error.localizedDescription)")
            NSLog("InternetCall: reportNewIncomingCall failed: %@", String(describing: error))
            // CallKit refused the ring (Focus/policy/entitlement). Without a fallback the callee
            // sees NOTHING while the caller hears ringing until the timeout — surface at least a
            // time-sensitive local notification; its answer/reject actions route back to us via
            // ChatPeerVoiceCallCoordinator's notification handlers.
            Task { @MainActor [weak self] in
                guard let self, self.callId == id, self.call?.state == .incoming else { return }
                SOSNotificationCenter.notifyIncomingCall(
                    sessionId: from.id,
                    title: from.name,
                    body: NSLocalizedString("VOICE_CALL_NOTIFICATION_BODY", comment: "")
                )
            }
        }

        let remote = RTCSessionDescription(type: .offer, sdp: sdp)
        pc.setRemoteDescription(remote) { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self, error == nil else { return }
                self.remoteDescriptionSet = true
                // The remote offer's video m-lines auto-created transceivers; adopt their senders
                // so we can send camera too (empty when the peer is older / audio-only).
                self.grabVideoSendersFromRemote()
                self.bindRemoteVideoTracks()
                self.drainPendingCandidates()
            }
        }
    }

    private func onAnswer(id: String, payload: [String: Any]) {
        guard id == callId, let pc = peerConnection,
              let sdp = payload["sdp"] as? String, !sdp.isEmpty else { return }
        let remote = RTCSessionDescription(type: .answer, sdp: sdp)
        pc.setRemoteDescription(remote) { [weak self] error in
            Task { @MainActor [weak self] in
                guard let self, error == nil else { return }
                self.remoteDescriptionSet = true
                self.bindRemoteVideoTracks()
                self.drainPendingCandidates()
            }
        }
    }

    private func onRemoteIce(id: String, payload: [String: Any]) {
        guard id == callId else { return }
        let candidate = RTCIceCandidate(
            sdp: payload["candidate"] as? String ?? "",
            sdpMLineIndex: Int32(payload["sdpMLineIndex"] as? Int ?? 0),
            sdpMid: payload["sdpMid"] as? String
        )
        if remoteDescriptionSet {
            peerConnection?.add(candidate) { _ in }
        } else {
            pendingRemoteCandidates.append(candidate)
        }
    }

    private func drainPendingCandidates() {
        guard let pc = peerConnection else { return }
        for candidate in pendingRemoteCandidates {
            pc.add(candidate) { _ in }
        }
        pendingRemoteCandidates.removeAll()
    }

    // MARK: - ICE restart recovery (mirrors Android: caller-driven, 12s grace, max 3)

    private func onTransportDisconnected() {
        guard let id = callId else { return }
        call?.reconnecting = true
        disconnectGraceTask?.cancel()
        disconnectGraceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.disconnectGrace * 1_000_000_000))
            guard let self, !Task.isCancelled, self.callId == id else { return }
            if self.peerConnection?.connectionState != .connected {
                self.escalateRecovery()
            }
        }
    }

    /// Try to save the call with a caller-driven ICE restart; give up when out of tries.
    private func escalateRecovery() {
        guard let id = callId else { return }
        if restartAttempts >= Self.maxIceRestarts {
            cleanup(finalState: .ended, result: callWasAnswered ? .answered : .canceled)
            return
        }
        if isCaller {
            attemptIceRestart()
        } else {
            // Only the offerer can cleanly restart ICE — ask it to. Peers that predate the
            // restart protocol ignore this silently; the next verdict window tears down.
            restartAttempts += 1
            sendSignal(jsonOf("restart-request", id))
            scheduleRestartVerdict(id)
        }
    }

    /// Caller side: a new-ufrag offer over the same signaling. Media keeps flowing on the old
    /// path (if any) until the new one connects, then shifts over.
    private func attemptIceRestart() {
        guard let id = callId, let pc = peerConnection else { return }
        restartAttempts += 1
        call?.reconnecting = true
        pc.restartIce()
        pc.offer(for: Self.mediaConstraints()) { [weak self] sdp, error in
            guard let sdp, error == nil else { return }
            pc.setLocalDescription(sdp) { _ in }
            Task { @MainActor [weak self] in
                guard let self else { return }
                var signal = self.jsonOf("restart-offer", id)
                signal["sdp"] = sdp.sdp
                self.sendSignal(signal)
            }
        }
        scheduleRestartVerdict(id)
    }

    private func scheduleRestartVerdict(_ id: String) {
        disconnectGraceTask?.cancel()
        disconnectGraceTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.disconnectGrace * 1_000_000_000))
            guard let self, !Task.isCancelled, self.callId == id else { return }
            if self.peerConnection?.connectionState != .connected {
                self.escalateRecovery()
            }
        }
    }

    /// Callee side of an ICE restart: apply the new offer, answer over the same signaling.
    private func onRestartOffer(id: String, payload: [String: Any]) {
        guard id == callId, let pc = peerConnection,
              let sdp = payload["sdp"] as? String, !sdp.isEmpty else { return }
        call?.reconnecting = true
        let remote = RTCSessionDescription(type: .offer, sdp: sdp)
        pc.setRemoteDescription(remote) { [weak self] error in
            guard error == nil else { return }
            pc.answer(for: Self.mediaConstraints()) { [weak self] answer, answerError in
                guard let answer, answerError == nil else { return }
                pc.setLocalDescription(answer) { _ in }
                Task { @MainActor [weak self] in
                    guard let self else { return }
                    var signal = self.jsonOf("restart-answer", id)
                    signal["sdp"] = answer.sdp
                    self.sendSignal(signal)
                }
            }
        }
    }

    // MARK: - Peer connection + media

    /// Offer/answer constraints (matches Android's audioConstraints): accept remote audio AND
    /// video — video m-lines are pre-negotiated via transceivers.
    private static func mediaConstraints() -> RTCMediaConstraints {
        RTCMediaConstraints(
            mandatoryConstraints: [
                "OfferToReceiveAudio": "true",
                "OfferToReceiveVideo": "true"
            ],
            optionalConstraints: nil
        )
    }

    private func createPeerConnection() -> RTCPeerConnection? {
        let config = RTCConfiguration()
        config.iceServers = TurnCredentialsProvider.shared.current().map { spec in
            RTCIceServer(urlStrings: spec.urls, username: spec.username, credential: spec.credential)
        }
        config.sdpSemantics = .unifiedPlan
        config.continualGatheringPolicy = .gatherContinually
        let constraints = RTCMediaConstraints(mandatoryConstraints: nil, optionalConstraints: nil)
        return Self.factory.peerConnection(with: config, constraints: constraints, delegate: self)
    }

    private func addLocalAudio(_ pc: RTCPeerConnection) {
        // Ensure the microphone permission is settled up front: a play-and-record audio unit fails to
        // start at all when mic access is undetermined/denied, which kills BOTH send and receive.
        AVAudioApplication.requestRecordPermission { _ in }
        let source = Self.factory.audioSource(with: RTCMediaConstraints(
            mandatoryConstraints: nil, optionalConstraints: nil
        ))
        let track = Self.factory.audioTrack(with: source, trackId: "cc-audio0")
        localAudioTrack = track
        pc.add(track, streamIds: ["cc-stream"])
    }

    /// Caller side: pre-negotiate ONE sendrecv video m-line (camera). Turning the camera on/off is a
    /// track swap on this sender — never a renegotiation.
    ///
    /// We deliberately offer only ONE video m-line, not two. An Android callee decides camera-vs-
    /// screen for each incoming remote video track INSIDE its onAddTrack, which fires DURING
    /// setRemoteDescription — before it caches its screen-receiver id in onSetSuccess. With two
    /// track-less sendrecv video m-lines in our offer, BOTH of Android's video receivers are
    /// therefore classified as "camera", and the second (screen) one OVERWRITES Android's main
    /// remote-video tile binding; that tile then stays BLACK when our camera actually streams on the
    /// first m-line (frames arrive on the orphaned slot-0 track). iOS neither sends screen share nor
    /// renders a separate remote-screen tile, so a second m-line buys us nothing and only triggers
    /// that far-side bug. (A callee still adopts however many video m-lines a REMOTE offer creates,
    /// so an Android/web-initiated call — where the caller pre-sets its screen-receiver id — is
    /// unaffected.)
    private func addVideoTransceivers(_ pc: RTCPeerConnection) {
        let initValue = RTCRtpTransceiverInit()
        initValue.direction = .sendRecv
        let transceiver = pc.addTransceiver(of: .video, init: initValue)
        pinH264First(on: transceiver)
        videoSender = transceiver?.sender
    }

    /// Reorder this transceiver's codec list so H264 leads, BEFORE the offer/answer is built.
    ///
    /// Without this the codec is simply whatever the OFFERER happens to list first, and the two
    /// platforms disagree: Android's DefaultVideoEncoderFactory merges its SOFTWARE codecs ahead of
    /// its hardware ones, so an Android-initiated call offers VP8 first. Android still encodes that
    /// VP8 on MediaCodec (hardware is the primary encoder, software only a fallback) — but iOS has NO
    /// VideoToolbox VP8 encoder, so we drop to libvpx, which picks its fastest/lowest-quality speed
    /// preset at these resolutions. Their video looks fine to us while ours looks mushy to them, on
    /// the very same link. H264 is the one codec BOTH sides encode in hardware, so pinning it makes
    /// the call symmetric no matter who dials.
    ///
    /// REORDER, never filter: VP8/VP9/AV1 stay on the list as fallbacks, so a peer whose H264 encoder
    /// is missing or blacklisted still gets video instead of a black tile.
    private func pinH264First(on transceiver: RTCRtpTransceiver?) {
        guard let transceiver else { return }
        let all = Self.factory.rtpSenderCapabilities(forKind: kRTCMediaStreamTrackKindVideo).codecs
        let h264 = all.filter { $0.name.caseInsensitiveCompare("H264") == .orderedSame }
        guard !h264.isEmpty else { return }
        // No error handling on purpose: WebRTC's throwing setCodecPreferences(_:error:) and its
        // deprecated void twin import into Swift under the SAME name, and the overload that wins is
        // the void one — a do/catch here would be dead code that reads as if it were checked. What
        // actually took is verifiable at runtime instead: the OUT-VIDEO probe logs
        // encoderImplementation, which names VideoToolbox vs libvpx.
        transceiver.setCodecPreferences(
            h264 + all.filter { $0.name.caseInsensitiveCompare("H264") != .orderedSame }
        )
    }

    /// Callee side: adopt the senders of the transceivers the remote offer created (m-line order:
    /// camera first, screen second). Either may be missing for older peers.
    private func grabVideoSendersFromRemote() {
        guard let pc = peerConnection else { return }
        let videoTransceivers = pc.transceivers.filter { $0.mediaType == .video }
        if let first = videoTransceivers.first {
            first.setDirection(.sendRecv, error: nil)
            // The answerer's preference matters just as much as the offerer's: reordering here is what
            // rewrites the codec order in OUR answer, which is what both sides then send with.
            pinH264First(on: first)
            videoSender = first.sender
        }
        if videoTransceivers.count > 1 {
            videoTransceivers[1].setDirection(.sendRecv, error: nil)
            // The screen m-line needs the same H264 pin as the camera one: left to the peer's codec
            // order this negotiates VP8, and a software libvpx encode of a multi-megapixel screen is
            // the worst case there is.
            pinH264First(on: videoTransceivers[1])
            screenSender = videoTransceivers[1].sender
        }
    }

    private func enableCamera() {
        guard call?.cameraOn != true, let videoSender else { return }
        AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
            Task { @MainActor [weak self] in
                guard let self, granted else { return }
                let source = Self.factory.videoSource()
                let capturer = RTCCameraVideoCapturer(delegate: source)
                let track = Self.factory.videoTrack(with: source, trackId: "cc-camera0")
                track.isEnabled = true
                self.cameraSource = source
                self.cameraCapturer = capturer
                self.usingFrontCamera = true
                videoSender.track = track
                self.localVideoTrack = track
                self.call?.cameraOn = true
                self.applyCameraSenderParams(videoSender, sharing: self.call?.sharingScreen == true)
                self.startCapture()
                self.reportVideoUpdate()
                self.autoSpeakerForVideo()
            }
        }
    }

    private func disableCamera() {
        cameraCapturer?.stopCapture()
        cameraCapturer = nil
        cameraSource = nil
        videoSender?.track = nil
        localVideoTrack = nil
        call?.cameraOn = false
        reportVideoUpdate()
    }

    private func startCapture() {
        guard let capturer = cameraCapturer else { return }
        let position: AVCaptureDevice.Position = usingFrontCamera ? .front : .back
        guard let device = RTCCameraVideoCapturer.captureDevices()
            .first(where: { $0.position == position })
            ?? RTCCameraVideoCapturer.captureDevices().first else { return }
        // 1280x720@30 — the same conferencing profile Android captures.
        let formats = RTCCameraVideoCapturer.supportedFormats(for: device)
        let target = formats.min(by: { lhs, rhs in
            func score(_ format: AVCaptureDevice.Format) -> Int {
                let dims = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
                return abs(Int(dims.width) - 1280) + abs(Int(dims.height) - 720)
            }
            return score(lhs) < score(rhs)
        })
        guard let format = target else { return }
        let fps = format.videoSupportedFrameRateRanges
            .map { Int($0.maxFrameRate) }
            .max()
            .map { min($0, 30) } ?? 30
        let chosen = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
        MessagingDiagLog.log("call capture format=\(chosen.width)x\(chosen.height)@\(fps) front=\(usingFrontCamera)")
        capturer.startCapture(with: device, format: format, fps: fps)
    }

    /// True once the call counts as picked up: the local user accepted an incoming call, OR the media
    /// path connected (the only "answered" signal an OUTGOING call ever gets — the remote pickup is
    /// invisible to us until RTCPeerConnectionState.connected).
    private var callWasAnswered: Bool {
        call?.answeredAt != nil || call?.state == .active
    }

    private func reportVideoUpdate() {
        guard let id = callId else { return }
        let update = CXCallUpdate()
        update.hasVideo = (call?.cameraOn == true) || (call?.remoteVideoActive == true)
            || (call?.sharingScreen == true) || (call?.remoteScreenActive == true)
        cxProvider.reportCall(with: callKitUUID(for: id), updated: update)
    }

    // MARK: - Remote video frame watchdog (mirrors Android startRemoteVideoWatchdog)

    /// Bind a received remote CAMERA track: whether it's actually ON is decided by frames arriving, not
    /// by this transceiver event (WebRTC mobile has no remote track mute/unmute signal). Swap the
    /// frame stamper and let the watchdog drive `remoteVideoActive`.
    private func attachRemoteCamera(_ track: RTCVideoTrack) {
        if let old = remoteFrameWatcher {
            remoteVideoTrack?.remove(old)
            remoteFrameWatcher = nil
        }
        remoteVideoTrack = track
        let watcher = RemoteVideoFrameWatcher()
        track.add(watcher)
        remoteFrameWatcher = watcher
        startRemoteVideoWatchdog()
        reportVideoUpdate()
    }

    /// Bind a received remote SCREEN-share track (dedicated m-line): its own frame stamper drives
    /// `remoteScreenActive`, and the overlay promotes the screen to the stage while it's live.
    private func attachRemoteScreen(_ track: RTCVideoTrack) {
        if let old = remoteScreenFrameWatcher {
            remoteScreenTrack?.remove(old)
            remoteScreenFrameWatcher = nil
        }
        remoteScreenTrack = track
        let watcher = RemoteVideoFrameWatcher()
        track.add(watcher)
        remoteScreenFrameWatcher = watcher
        startRemoteVideoWatchdog()
        reportVideoUpdate()
    }

    /// Bind the remote video tiles straight from the negotiated m-lines: video m-line 0 is the camera,
    /// m-line 1 (when the peer offered one) is the screen share — the ordering both our own offer and
    /// Android/web's use.
    ///
    /// This used to happen ONLY from `didStartReceivingOn`, which fires DURING setRemoteDescription —
    /// i.e. before `grabVideoSendersFromRemote` has cached the screen m-line id. Against a two-m-line
    /// remote offer both received tracks therefore routed to the camera tile and the screen one
    /// OVERWROTE the camera binding, so the peer's camera frames landed on an orphaned track: the
    /// remote tile stayed empty (`remoteVideoActive` never went true) no matter what they streamed.
    /// Android hit the mirror image of this same race. Rebinding from the transceiver list is immune
    /// to that ordering, and re-running it is free — tracks compare by id, so an unchanged binding
    /// does nothing.
    private func bindRemoteVideoTracks() {
        guard let pc = peerConnection else { return }
        // The poll is what turns a bound track into a lit tile, and it also re-runs this binding, so it
        // has to be up even on a call where no video track has been adopted yet.
        startRemoteVideoWatchdog()
        let videoTransceivers = pc.transceivers.filter { $0.mediaType == .video }
        if let camera = videoTransceivers.first?.receiver.track as? RTCVideoTrack,
           camera.trackId != remoteVideoTrack?.trackId {
            MessagingDiagLog.log("remote camera tile bound to m-line 0 track \(camera.trackId)")
            attachRemoteCamera(camera)
        }
        if videoTransceivers.count > 1,
           let screen = videoTransceivers[1].receiver.track as? RTCVideoTrack,
           screen.trackId != remoteScreenTrack?.trackId {
            MessagingDiagLog.log("remote screen tile bound to m-line 1 track \(screen.trackId)")
            attachRemoteScreen(screen)
        }
    }

    /// Poll the last-frame stamp: a remote camera turning off shows up only as frames stopping, so a
    /// slot that has been "on" but frameless for `remoteVideoStaleSeconds` flips back off (avatar),
    /// and back on when frames resume.
    private func startRemoteVideoWatchdog() {
        // One poll serves both slots and re-binding is idempotent, so never churn the task — a restart
        // from inside its own loop (bindRemoteVideoTracks → attachRemote*) would cancel the live tick.
        guard remoteVideoWatchdogTask == nil else { return }
        remoteVideoWatchdogTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self, !Task.isCancelled else { return }
                guard self.peerConnection != nil else { return }
                // Self-heal: a track that arrived without (or before) a usable didStartReceivingOn
                // gets picked up here instead of leaving the tile dark for the whole call.
                self.bindRemoteVideoTracks()
                let now = ProcessInfo.processInfo.systemUptime
                if let watcher = self.remoteFrameWatcher {
                    let last = watcher.lastFrameAt
                    let active = last > 0 && (now - last) < Self.remoteVideoStaleSeconds
                    if self.call?.remoteVideoActive != active {
                        self.call?.remoteVideoActive = active
                        self.reportVideoUpdate()
                        // First real remote frames arriving is also a "video started" trigger for the
                        // one-shot auto-speaker.
                        if active { self.autoSpeakerForVideo() }
                    }
                }
                if let watcher = self.remoteScreenFrameWatcher {
                    let last = watcher.lastFrameAt
                    let active = last > 0 && (now - last) < Self.remoteVideoStaleSeconds
                    if self.call?.remoteScreenActive != active {
                        self.call?.remoteScreenActive = active
                        self.reportVideoUpdate()
                        if active { self.autoSpeakerForVideo() }
                    }
                }
            }
        }
    }

    // MARK: - Network handover watcher (mirrors Android registerNetworkWatcher)

    /// A default-path change (Wi-Fi ↔ cellular) is the classic mid-call killer: the old ICE pair is
    /// dead on the new interface. On a real handover — not the baseline path, not an unchanged one —
    /// the caller drives an ICE restart and the callee asks the caller to. Runs only while a call
    /// exists (started at call setup, cancelled in cleanup).
    private func startNetworkWatcher() {
        guard pathMonitor == nil else { return }
        currentNetworkSignature = nil
        let monitor = NWPathMonitor()
        monitor.pathUpdateHandler = { [weak self] path in
            // Only satisfied paths carry a usable interface; ignore "no network" transitions so a
            // brief gap between interfaces doesn't fire a restart into the void.
            guard path.status == .satisfied else { return }
            let signature = path.availableInterfaces.first?.name ?? "unknown"
            Task { @MainActor [weak self] in
                guard let self else { return }
                let previous = self.currentNetworkSignature
                self.currentNetworkSignature = signature
                // First update = baseline; an unchanged primary interface = no handover.
                guard previous != nil, previous != signature else { return }
                // No call active → nothing to recover (also guards a late callback after cleanup).
                guard let id = self.callId, self.peerConnection != nil else { return }
                if self.isCaller {
                    self.attemptIceRestart()
                } else {
                    self.sendSignal(self.jsonOf("restart-request", id))
                }
            }
        }
        monitor.start(queue: pathMonitorQueue)
        pathMonitor = monitor
    }

    private func stopNetworkWatcher() {
        pathMonitor?.cancel()
        pathMonitor = nil
        currentNetworkSignature = nil
    }

    // MARK: - Live call-quality (mirrors Android startStatsMonitor)

    /// Poll getStats during the active call and fold remote-inbound RTT + interval inbound loss into
    /// the coarse quality bucket, matching Android's thresholds exactly.
    private func startStatsMonitor() {
        statsTask?.cancel()
        prevPacketsLost = 0
        prevPacketsReceived = 0
        statsTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.statsIntervalSeconds * 1_000_000_000))
                guard let self, !Task.isCancelled, let pc = self.peerConnection else { return }
                // Fire-and-forget: the completion hops back to the MainActor. A closed pc simply
                // never calls back (no continuation to leak); the loop just polls again next tick.
                pc.statistics { [weak self] report in
                    Task { @MainActor [weak self] in self?.applyStats(report) }
                }
            }
        }
    }

    private func applyStats(_ report: RTCStatisticsReport) {
        guard call?.state == .active else { return }
        // One-time audio-flow diagnostic: confirm whether audio RTP actually leaves/arrives on iOS.
        if !loggedAudioFlow {
            var audioOut: Int64 = 0, audioIn: Int64 = 0
            for stats in report.statistics.values {
                guard (stats.values["kind"] as? String) == "audio" else { continue }
                if stats.type == "outbound-rtp" { audioOut = (stats.values["bytesSent"] as? NSNumber)?.int64Value ?? audioOut }
                if stats.type == "inbound-rtp" { audioIn = (stats.values["bytesReceived"] as? NSNumber)?.int64Value ?? audioIn }
            }
            if audioOut > 0 || audioIn > 0 {
                loggedAudioFlow = true
                MessagingDiagLog.log("call audio flow: bytesSent=\(audioOut) bytesReceived=\(audioIn) audioEnabled=\(RTCAudioSession.sharedInstance().isAudioEnabled)")
            }
        }
        // Outbound-VIDEO probe, per stream and every 5s. This is the only thing that separates an
        // encoder downscale (qualityLimitationReason=cpu/bandwidth) from a bad capture format
        // (reason=none but a small frame) from a starved bandwidth estimate. `encoderImplementation`
        // also names the codec path, so it proves whether the H264 pin actually took (VideoToolbox vs
        // libvpx) — on the SCREEN stream as well as the camera one.
        for stats in report.statistics.values
        where stats.type == "outbound-rtp" && (stats.values["kind"] as? String) == "video" {
            let w = (stats.values["frameWidth"] as? NSNumber)?.intValue ?? 0
            let h = (stats.values["frameHeight"] as? NSNumber)?.intValue ?? 0
            guard w > 0 else { continue }
            let ssrc = ((stats.values["ssrc"] as? NSNumber)?.stringValue) ?? stats.id
            let now = Date()
            if let last = lastVideoStatLogAt[ssrc], now.timeIntervalSince(last) < 5 { continue }
            lastVideoStatLogAt[ssrc] = now
            MessagingDiagLog.log(
                "call OUT-VIDEO ssrc=\(ssrc) \(w)x\(h)"
                + " fps=\((stats.values["framesPerSecond"] as? NSNumber)?.doubleValue ?? 0)"
                + " target=\((stats.values["targetBitrate"] as? NSNumber)?.intValue ?? 0)"
                + " limit=\((stats.values["qualityLimitationReason"] as? String) ?? "?")"
                + " enc=\((stats.values["encoderImplementation"] as? String) ?? "?")"
            )
        }
        var rttMs = 0.0
        var lost: Int64 = 0
        var received: Int64 = 0
        for stats in report.statistics.values {
            switch stats.type {
            case "remote-inbound-rtp":
                if let rtt = (stats.values["roundTripTime"] as? NSNumber)?.doubleValue {
                    rttMs = max(rttMs, rtt * 1000)
                }
            case "inbound-rtp":
                if let l = (stats.values["packetsLost"] as? NSNumber)?.int64Value { lost += l }
                if let r = (stats.values["packetsReceived"] as? NSNumber)?.int64Value { received += r }
            default:
                break
            }
        }
        let deltaLost = max(lost - prevPacketsLost, 0)
        let deltaReceived = max(received - prevPacketsReceived, 0)
        prevPacketsLost = lost
        prevPacketsReceived = received
        let total = deltaLost + deltaReceived
        let lossFraction = total > 0 ? Double(deltaLost) / Double(total) : 0
        let quality: CallQuality
        if lossFraction > 0.08 || rttMs > 500 {
            quality = .poor
        } else if lossFraction > 0.03 || rttMs > 250 {
            quality = .fair
        } else {
            quality = .good
        }
        if call?.quality != quality { call?.quality = quality }
    }

    // MARK: - Lifecycle

    private func scheduleRingTimeout(_ id: String) {
        ringTimeoutTask?.cancel()
        ringTimeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.ringTimeout * 1_000_000_000))
            guard let self, !Task.isCancelled, self.callId == id else { return }
            if self.call?.state == .dialing || self.call?.state == .incoming {
                let missedIncoming = self.call?.incoming == true
                self.sendSignal(self.jsonOf("end", id))
                self.cleanup(finalState: .ended, result: missedIncoming ? .missed : .canceled)
            }
        }
    }

    private func sendSignal(_ signal: [String: Any]) {
        guard let contact else { return }
        sendSignal(to: contact, signal)
    }

    private func sendSignal(to target: ContactRecord, _ signal: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: signal),
              let json = String(data: data, encoding: .utf8) else { return }
        Task {
            _ = await InternetChatTransport.shared.sendCallSignal(contact: target, signalJson: json)
        }
    }

    /// "type" is the web CallSignal field; "kind" is kept for backward-compat with older Android
    /// builds. Both carry the same value.
    private func jsonOf(_ kind: String, _ id: String) -> [String: Any] {
        ["type": kind, "kind": kind, "callId": id]
    }

    private func cleanup(finalState: CallState, result: SOSChatCallResult) {
        ringTimeoutTask?.cancel()
        ringTimeoutTask = nil
        disconnectGraceTask?.cancel()
        disconnectGraceTask = nil
        statsTask?.cancel()
        statsTask = nil
        remoteVideoWatchdogTask?.cancel()
        remoteVideoWatchdogTask = nil
        audioFallbackTask?.cancel()
        audioFallbackTask = nil
        stopNetworkWatcher()
        if let sessionId = call?.sessionId {
            SOSNotificationCenter.clearIncomingCallNotification(sessionId: sessionId)
            // File the call into the chat timeline, like BLE calls and Android internet calls do.
            let durationMillis = call?.startedAt.map { Int(Date().timeIntervalSince($0) * 1000) }
            _ = SOSChatStore.shared.appendCallEvent(
                sessionId: sessionId,
                direction: call?.incoming == true ? .incoming : .outgoing,
                result: result,
                durationMillis: durationMillis,
                analyticsTransport: "internet",
                // Keyed on the call id (read before cleanup nils it below) so a racing second
                // teardown files nothing instead of a duplicate row.
                callEventKey: callId.map { "internet:\($0)" }
            )
        }
        if let id = callId {
            cxProvider.reportCall(
                with: callKitUUID(for: id),
                endedAt: Date(),
                reason: result == .answered ? .remoteEnded : (result == .missed ? .unanswered : .remoteEnded)
            )
        }
        stopScreenBroadcastServer()
        disableCamera()
        if let watcher = remoteFrameWatcher {
            remoteVideoTrack?.remove(watcher)
            remoteFrameWatcher = nil
        }
        remoteVideoTrack = nil
        if let watcher = remoteScreenFrameWatcher {
            remoteScreenTrack?.remove(watcher)
            remoteScreenFrameWatcher = nil
        }
        remoteScreenTrack = nil
        peerConnection?.close()
        peerConnection = nil
        localAudioTrack = nil
        videoSender = nil
        screenSender = nil
        callId = nil
        remoteDescriptionSet = false
        restartAttempts = 0
        prevPacketsLost = 0
        prevPacketsReceived = 0
        loggedAudioFlow = false
        lastVideoStatLogAt.removeAll()
        autoSpeakerApplied = false
        userAdjustedSpeaker = false
        pendingRemoteCandidates.removeAll()
        voipCallUUIDOverride = nil
        voipWakeSenderUid = nil
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        call?.state = finalState
        contact = nil
        teardownTask?.cancel()
        teardownTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.endedLingerSeconds * 1_000_000_000))
            guard let self, !Task.isCancelled else { return }
            if self.call?.state == .ended { self.call = nil }
        }
    }
}

// MARK: - RTCPeerConnectionDelegate

extension InternetCallManager: RTCPeerConnectionDelegate {
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didGenerate candidate: RTCIceCandidate) {
        Task { @MainActor [weak self] in
            guard let self, let id = self.callId else { return }
            var signal = self.jsonOf("ice", id)
            signal["candidate"] = candidate.sdp
            signal["sdpMid"] = candidate.sdpMid ?? ""
            signal["sdpMLineIndex"] = Int(candidate.sdpMLineIndex)
            self.sendSignal(signal)
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCPeerConnectionState) {
        Task { @MainActor [weak self] in
            guard let self else { return }
            switch newState {
            case .connected:
                self.disconnectGraceTask?.cancel()
                self.call?.reconnecting = false
                // Reset the ICE-restart budget on every successful (re)connect so cumulative
                // transient drops over a long call can't exhaust MAX_ICE_RESTARTS and force a
                // premature teardown (Android does this on CONNECTED).
                self.restartAttempts = 0
                if self.call != nil, self.call?.state != .active {
                    self.call?.state = .active
                    self.call?.startedAt = self.call?.startedAt ?? Date()
                    if let id = self.callId, self.isCaller {
                        self.cxProvider.reportOutgoingCall(with: self.callKitUUID(for: id), connectedAt: Date())
                    }
                }
                self.startStatsMonitor()
                // Listen for the screen-broadcast extension for the rest of the call (idle until the
                // user starts a system broadcast).
                self.startScreenBroadcastServer()
            case .disconnected:
                self.onTransportDisconnected()
            case .failed:
                self.call?.reconnecting = true
                self.escalateRecovery()
            case .closed:
                break
            default:
                break
            }
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didStartReceivingOn transceiver: RTCRtpTransceiver) {
        guard transceiver.mediaType == .video else { return }
        // Only a prompt (the tiles bind deterministically from the m-line order, and this event fires
        // mid-setRemoteDescription — too early to trust for routing). Re-binding is idempotent.
        Task { @MainActor [weak self] in
            guard let self, self.call != nil else { return }
            self.bindRemoteVideoTracks()
        }
    }

    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange stateChanged: RTCSignalingState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didAdd stream: RTCMediaStream) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove stream: RTCMediaStream) {}
    nonisolated func peerConnectionShouldNegotiate(_ peerConnection: RTCPeerConnection) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceConnectionState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didChange newState: RTCIceGatheringState) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didRemove candidates: [RTCIceCandidate]) {}
    nonisolated func peerConnection(_ peerConnection: RTCPeerConnection, didOpen dataChannel: RTCDataChannel) {}
}

// MARK: - CXProviderDelegate

extension InternetCallManager: CXProviderDelegate {
    nonisolated func providerDidReset(_ provider: CXProvider) {
        Task { @MainActor [weak self] in
            guard let self, self.callId != nil else { return }
            self.cleanup(finalState: .ended, result: .canceled)
        }
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {
        Task { @MainActor [weak self] in
            self?.performAccept()
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXEndCallAction) {
        Task { @MainActor [weak self] in
            guard let self, let id = self.callId else { return }
            self.sendSignal(self.jsonOf(self.call?.state == .incoming ? "reject" : "end", id))
            let result: SOSChatCallResult = self.callWasAnswered
                ? .answered
                : (self.call?.incoming == true ? .rejected : .canceled)
            self.cleanup(finalState: .ended, result: result)
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, perform action: CXSetMutedCallAction) {
        Task { @MainActor [weak self] in
            guard let self, let track = self.localAudioTrack else { return }
            track.isEnabled = !action.isMuted
            self.call?.muted = action.isMuted
        }
        action.fulfill()
    }

    nonisolated func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        // Manual-audio handoff: WebRTC's audio unit may only start now. Configure + enable under the
        // RTCAudioSession lock so the play-and-record unit comes up correctly.
        let rtcSession = RTCAudioSession.sharedInstance()
        rtcSession.lockForConfiguration()
        rtcSession.audioSessionDidActivate(audioSession)
        rtcSession.isAudioEnabled = true
        rtcSession.unlockForConfiguration()
        // CallKit did its job — the manual fallback is no longer needed.
        Task { @MainActor [weak self] in self?.audioFallbackTask?.cancel() }
    }

    nonisolated func provider(_ provider: CXProvider, didDeactivate audioSession: AVAudioSession) {
        RTCAudioSession.sharedInstance().isAudioEnabled = false
        RTCAudioSession.sharedInstance().audioSessionDidDeactivate(audioSession)
    }
}

// MARK: - Remote video frame stamper

/// A near-zero-cost renderer attached to the remote video track: it draws nothing, it just records
/// the time of the last decoded frame so the MainActor watchdog can tell whether the peer's camera
/// is still sending. WebRTC mobile has no remote track mute/unmute event, so this frame-timestamp
/// approach (matching Android's VideoSink) is how "remote camera off" is detected. `renderFrame`
/// runs on WebRTC's decoder thread, so the stamp is guarded by a lock.
private final class RemoteVideoFrameWatcher: NSObject, RTCVideoRenderer {
    private let lock = NSLock()
    private var stamp: TimeInterval = 0

    /// Monotonic `ProcessInfo.systemUptime` of the last frame, or 0 if none has arrived yet.
    var lastFrameAt: TimeInterval {
        lock.lock()
        defer { lock.unlock() }
        return stamp
    }

    func setSize(_ size: CGSize) {}

    func renderFrame(_ frame: RTCVideoFrame?) {
        guard frame != nil else { return }
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        stamp = now
        lock.unlock()
    }
}
