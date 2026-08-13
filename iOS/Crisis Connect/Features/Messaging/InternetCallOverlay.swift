//
//  InternetCallOverlay.swift
//  Crisis Connect
//
//  Global overlay for 1:1 internet voice calls — attached once at the app root so an incoming
//  call surfaces no matter which screen is open (Android's InternetCallOverlayHost equivalent).
//  Audio-only for this increment: incoming banner with answer/decline, and a compact in-call
//  card with mute / speaker / end.
//

import ReplayKit
import SwiftUI
import WebRTC

struct InternetCallOverlayHost: View {
    @ObservedObject private var manager = InternetCallManager.shared

    var body: some View {
        if let call = manager.call {
            InternetCallCard(
                call: call,
                remoteVideoTrack: manager.remoteVideoTrack,
                remoteScreenTrack: manager.remoteScreenTrack,
                localVideoTrack: manager.localVideoTrack
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.top, 8)
            .transition(.move(edge: .top).combined(with: .opacity))
            .animation(.spring(duration: 0.3), value: call.state)
        }
    }
}

/// UIViewRepresentable around WebRTC's Metal renderer for a video track.
private struct RTCVideoTrackView: UIViewRepresentable {
    let track: RTCVideoTrack
    // Camera feeds fill; a shared screen fits so nothing is cropped.
    var contentMode: UIView.ContentMode = .scaleAspectFill

    func makeUIView(context: Context) -> RTCMTLVideoView {
        let view = RTCMTLVideoView()
        view.videoContentMode = contentMode
        view.clipsToBounds = true
        track.add(view)
        return view
    }

    func updateUIView(_ uiView: RTCMTLVideoView, context: Context) {
        uiView.videoContentMode = contentMode
    }

    static func dismantleUIView(_ uiView: RTCMTLVideoView, coordinator: ()) {
        // The track outlives the view; just stop rendering into it.
    }
}

/// Screen-broadcast control. RPSystemBroadcastPickerView renders its own button inconsistently
/// (notably invisible on iPad), so we draw our OWN styled circle+icon and lay a transparent, still-
/// tappable picker on top to catch the tap and present the OS broadcast sheet (targeting our
/// extension). The app is already listening once the call is active, so frames flow when it starts.
struct BroadcastPickerButton: View {
    let active: Bool

    var body: some View {
        ZStack {
            Image(systemName: active ? "rectangle.on.rectangle.fill" : "rectangle.on.rectangle")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Circle().fill(active ? Color.appPrimary : Color.gray))
            SystemBroadcastPicker()
                .frame(width: 44, height: 44)
        }
        .frame(width: 44, height: 44)
    }
}

/// Transparent RPSystemBroadcastPickerView overlay: keeps the tap behaviour, hides the default glyph
/// (so only our styled icon shows underneath).
private struct SystemBroadcastPicker: UIViewRepresentable {
    func makeUIView(context: Context) -> RPSystemBroadcastPickerView {
        let picker = RPSystemBroadcastPickerView(frame: CGRect(x: 0, y: 0, width: 44, height: 44))
        picker.preferredExtension = "com.auralis.crisisconnect.broadcast"
        picker.showsMicrophoneButton = false
        picker.backgroundColor = .clear
        neutralize(picker)
        return picker
    }

    func updateUIView(_ uiView: RPSystemBroadcastPickerView, context: Context) {
        uiView.frame = CGRect(x: 0, y: 0, width: 44, height: 44)
        neutralize(uiView)
    }

    /// Make the picker's own button fill the area but render nothing (our SwiftUI icon shows through),
    /// while staying tappable so it still presents the system sheet.
    private func neutralize(_ view: UIView) {
        view.backgroundColor = .clear
        for sub in view.subviews {
            if let button = sub as? UIButton {
                button.frame = view.bounds
                button.tintColor = .clear
                button.setImage(UIImage(), for: .normal)
                button.setBackgroundImage(UIImage(), for: .normal)
                button.imageView?.alpha = 0
            }
            neutralize(sub)
        }
    }
}

private struct InternetCallCard: View {
    let call: InternetCallManager.CallInfo
    let remoteVideoTrack: RTCVideoTrack?
    let remoteScreenTrack: RTCVideoTrack?
    let localVideoTrack: RTCVideoTrack?

    private var manager: InternetCallManager { .shared }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "phone.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color.appPrimary)
                VStack(alignment: .leading, spacing: 1) {
                    Text(call.peerName)
                        .font(.headline)
                        .lineLimit(1)
                    Text(stateLine)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
                Spacer()
                if call.state == .active {
                    Image(systemName: qualityGlyph)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(qualityColor)
                        .accessibilityLabel(Text(qualityLabelKey))
                }
                if call.state == .active, let startedAt = call.startedAt {
                    Text(startedAt, style: .timer)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.appTextSecondary)
                }
            }

            // Video canvas: a shared screen (or the remote camera) owns the stage; the remote camera
            // and own camera stack as PiPs. Mirrors Android's "remote screen owns the stage" layout.
            if call.state == .active,
               call.remoteVideoActive || call.cameraOn || call.remoteScreenActive || call.sharingScreen {
                stage
                    .frame(height: 260)
                    .frame(maxWidth: .infinity)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(alignment: .bottomTrailing) { pipStack }
                    .overlay(alignment: .topLeading) { sharingBadge }
            }

            if call.reconnecting {
                HStack(spacing: 8) {
                    ProgressView()
                        .controlSize(.small)
                    Text("VOICE_CALL_RECONNECTING")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(Color.appWarning)
                }
            }

            HStack(spacing: 10) {
                switch call.state {
                case .incoming:
                    callButton(
                        systemName: "phone.down.fill",
                        tint: .red,
                        labelKey: "VOICE_CALL_REJECT_ACTION"
                    ) { manager.reject() }
                    callButton(
                        systemName: "phone.fill",
                        tint: .green,
                        labelKey: "VOICE_CALL_ANSWER_ACTION"
                    ) { manager.accept() }
                case .dialing, .connecting, .active:
                    callButton(
                        systemName: call.muted ? "mic.slash.fill" : "mic.fill",
                        tint: call.muted ? .orange : Color.appPrimary,
                        labelKey: call.muted ? "VOICE_CALL_UNMUTE" : "VOICE_CALL_MUTE"
                    ) { manager.toggleMute() }
                    callButton(
                        systemName: call.cameraOn ? "video.fill" : "video",
                        tint: call.cameraOn ? Color.appPrimary : .gray,
                        labelKey: call.cameraOn ? "VIDEO_CALL_DISABLE_CAMERA" : "VIDEO_CALL_ENABLE_CAMERA"
                    ) { manager.toggleCamera() }
                    if call.cameraOn, call.sharingScreen != true {
                        callButton(
                            systemName: "arrow.triangle.2.circlepath.camera",
                            tint: .gray,
                            labelKey: "VIDEO_CALL_SWITCH_CAMERA"
                        ) { manager.switchCamera() }
                    }
                    if call.state == .active {
                        // Full-device screen share via the system broadcast picker (targets our
                        // ReplayKit broadcast extension). The user stops it here or from the status bar.
                        BroadcastPickerButton(active: call.sharingScreen)
                            .frame(width: 44, height: 44)
                            .accessibilityLabel(Text(LocalizedStringKey(
                                call.sharingScreen ? "VOICE_CALL_ACTION_STOP_SHARE" : "VOICE_CALL_ACTION_SHARE_SCREEN")))
                    }
                    callButton(
                        systemName: call.speakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                        tint: call.speakerOn ? Color.appPrimary : .gray,
                        labelKey: call.speakerOn ? "VOICE_CALL_EARPIECE" : "VOICE_CALL_SPEAKER"
                    ) { manager.setSpeaker(!call.speakerOn) }
                    callButton(
                        systemName: "phone.down.fill",
                        tint: .red,
                        labelKey: "VOICE_CALL_END_ACTION"
                    ) { manager.endCall() }
                case .ended:
                    Text("VOICE_CALL_ENDED")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color.appTextSecondary)
                }
            }
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.appSurfaceElevated)
                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
        )
    }

    private var stateLine: LocalizedStringKey {
        switch call.state {
        case .dialing: return "VOICE_CALL_STATE_DIALING"
        case .incoming: return "VOICE_CALL_STATE_INCOMING"
        case .connecting: return "VOICE_CALL_STATE_CONNECTING"
        case .active: return "VOICE_CALL_STATE_ACTIVE"
        case .ended: return "VOICE_CALL_ENDED"
        }
    }

    // Coarse live-quality indicator (mirrors Android's CallQuality bucketing, computed in the manager).
    private var qualityGlyph: String {
        switch call.quality {
        case .good: return "wifi"
        case .fair: return "wifi"
        case .poor: return "wifi.slash"
        }
    }

    private var qualityColor: Color {
        switch call.quality {
        case .good: return .green
        case .fair: return Color.appWarning
        case .poor: return .red
        }
    }

    private var qualityLabelKey: LocalizedStringKey {
        switch call.quality {
        case .good: return "VOICE_CALL_QUALITY_GOOD"
        case .fair: return "VOICE_CALL_QUALITY_FAIR"
        case .poor: return "VOICE_CALL_QUALITY_POOR"
        }
    }

    // The big video stage: a remote screen share (fit, so nothing is cropped) wins, then the remote
    // camera, then an avatar placeholder when neither has frames.
    // Fit, never fill: the far side may have a completely different aspect (a shared iPad screen on a
    // tall phone, or a screen arriving on the camera slot of a single-m-line call), and cropping its
    // edges off loses content. Letterboxing keeps the whole frame and it just grows when rotated.
    @ViewBuilder private var stage: some View {
        if call.remoteScreenActive, let remoteScreenTrack {
            RTCVideoTrackView(track: remoteScreenTrack, contentMode: .scaleAspectFit)
                .background(Color.black)
        } else if call.remoteVideoActive, let remoteVideoTrack {
            RTCVideoTrackView(track: remoteVideoTrack, contentMode: .scaleAspectFit)
                .background(Color.black)
        } else {
            Rectangle()
                .fill(Color.black.opacity(0.85))
                .overlay(
                    Image(systemName: "person.crop.circle.fill")
                        .font(.system(size: 52))
                        .foregroundStyle(.white.opacity(0.4))
                )
        }
    }

    // Picture-in-picture corner: the remote camera (while their screen owns the stage) over the own
    // self-view.
    @ViewBuilder private var pipStack: some View {
        VStack(spacing: 8) {
            if call.remoteScreenActive, call.remoteVideoActive, let remoteVideoTrack {
                pip(track: remoteVideoTrack)
            }
            if call.cameraOn, let localVideoTrack {
                pip(track: localVideoTrack)
            }
        }
        .padding(8)
    }

    private func pip(track: RTCVideoTrack) -> some View {
        RTCVideoTrackView(track: track)
            .frame(width: 84, height: 116)
            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .stroke(.white.opacity(0.6), lineWidth: 1)
            )
    }

    @ViewBuilder private var sharingBadge: some View {
        if call.sharingScreen {
            Label("VOICE_CALL_SHARING_SCREEN_LABEL", systemImage: "rectangle.on.rectangle.fill")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Capsule().fill(Color.appPrimary.opacity(0.92)))
                .padding(8)
        }
    }

    private func callButton(
        systemName: String,
        tint: Color,
        labelKey: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(Circle().fill(tint))
        }
        .accessibilityLabel(Text(LocalizedStringKey(labelKey)))
    }
}
