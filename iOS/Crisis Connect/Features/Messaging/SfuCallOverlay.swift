//
//  SfuCallOverlay.swift
//  Crisis Connect
//
//  Global overlay for SFU (Cloudflare Realtime) authority calls — Android's SfuCallOverlayHost.
//  Attached once at the app root next to InternetCallOverlayHost, styled as the same compact call
//  card so an SFU authority call looks exactly like a citizen call: ring with answer/decline, and
//  in-call mute / camera / speaker / end. Group-ready: every remote participant with a live video
//  track gets a tile.
//

import SwiftUI
import WebRTC

struct SfuCallOverlayHost: View {
    @ObservedObject private var manager = SfuAuthorityCallManager.shared

    var body: some View {
        if let call = manager.uiCall, call.state != .idle {
            SfuCallCard(call: call, media: manager.media)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .padding(.horizontal, AppTheme.screenPadding)
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
                .animation(.spring(duration: 0.3), value: call.state)
        }
    }
}

/// UIViewRepresentable around WebRTC's Metal renderer for a video track.
private struct SfuVideoTrackView: UIViewRepresentable {
    let track: RTCVideoTrack
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
}

private struct SfuCallCard: View {
    let call: SfuUiCall
    let media: SfuCallManager?

    private var manager: SfuAuthorityCallManager { .shared }

    var body: some View {
        VStack(spacing: 14) {
            HStack(spacing: 10) {
                Image(systemName: "phone.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color.appPrimary)
                VStack(alignment: .leading, spacing: 1) {
                    Text(call.peerName.isEmpty ? String(localized: "AUTHORITY_CHANNEL_ROW_LABEL") : call.peerName)
                        .font(.headline)
                        .lineLimit(1)
                    Text(stateLine)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
                Spacer()
                if call.state == .connected, let connectedAt = call.connectedAt {
                    Text(connectedAt, style: .timer)
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.appTextSecondary)
                }
            }

            if let media {
                SfuVideoCanvas(media: media, connected: call.state == .connected)
            }

            controls
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.appSurfaceElevated)
                .shadow(color: .black.opacity(0.18), radius: 14, y: 6)
        )
    }

    @ViewBuilder
    private var controls: some View {
        switch call.state {
        case .incoming:
            HStack(spacing: 14) {
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
            }
        case .outgoing, .connecting, .connected:
            VStack(spacing: 12) {
                if call.state == .connected, let media {
                    HStack(spacing: 14) {
                        BroadcastPickerButton(active: media.sharingScreen)
                            .accessibilityLabel(Text("SCREEN_SHARE"))
                        Menu {
                            ForEach(ScreenShareQualityPreset.allCases) { preset in
                                Button {
                                    media.setScreenShareQuality(preset)
                                } label: {
                                    if preset == media.screenShareQuality {
                                        Label(preset.displayName, systemImage: "checkmark")
                                    } else {
                                        Text(preset.displayName)
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: "gearshape.fill")
                                .font(.system(size: 17, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .background(Circle().fill(Color.gray))
                        }
                        .accessibilityLabel(Text("Screen share quality: \(media.screenShareQuality.displayName)"))
                        if media.cameraOn {
                            callButton(
                                systemName: "arrow.triangle.2.circlepath.camera",
                                tint: .gray,
                                labelKey: "VIDEO_CALL_SWITCH_CAMERA"
                            ) { manager.switchCamera() }
                        }
                    }
                }
                HStack(spacing: 14) {
                if let media {
                    callButton(
                        systemName: media.muted ? "mic.slash.fill" : "mic.fill",
                        tint: media.muted ? .orange : Color.appPrimary,
                        labelKey: media.muted ? "VOICE_CALL_UNMUTE" : "VOICE_CALL_MUTE"
                    ) { manager.toggleMute() }
                    callButton(
                        systemName: media.cameraOn ? "video.fill" : "video",
                        tint: media.cameraOn ? Color.appPrimary : .gray,
                        labelKey: media.cameraOn ? "VIDEO_CALL_DISABLE_CAMERA" : "VIDEO_CALL_ENABLE_CAMERA"
                    ) { manager.toggleCamera() }
                    callButton(
                        systemName: manager.speakerOn ? "speaker.wave.3.fill" : "speaker.fill",
                        tint: manager.speakerOn ? Color.appPrimary : .gray,
                        labelKey: manager.speakerOn ? "VOICE_CALL_EARPIECE" : "VOICE_CALL_SPEAKER"
                    ) { manager.setSpeaker(!manager.speakerOn) }
                }
                callButton(
                    systemName: "phone.down.fill",
                    tint: .red,
                    labelKey: "VOICE_CALL_END_ACTION"
                ) { manager.end() }
                }
            }
        case .ended:
            Text("VOICE_CALL_ENDED")
                .font(.subheadline.weight(.medium))
                .foregroundStyle(Color.appTextSecondary)
        case .idle:
            EmptyView()
        }
    }

    private var stateLine: LocalizedStringKey {
        switch call.state {
        case .outgoing: return "VOICE_CALL_STATE_DIALING"
        case .incoming: return "VOICE_CALL_STATE_INCOMING"
        case .connecting: return "VOICE_CALL_STATE_CONNECTING"
        case .connected: return "VOICE_CALL_STATE_ACTIVE"
        case .ended, .idle: return "VOICE_CALL_ENDED"
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

/// Remote participants' camera tiles (every uid with a live video track) + own camera PiP.
/// Observes the media engine directly so a mid-call track appearing re-renders the grid.
private struct SfuVideoCanvas: View {
    @ObservedObject var media: SfuCallManager
    let connected: Bool

    var body: some View {
        let remote = media.remoteVideoTracks.sorted { $0.key < $1.key }
        if connected && (!remote.isEmpty || media.cameraOn || media.sharingScreen) {
            ZStack(alignment: .bottomTrailing) {
                if remote.isEmpty {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(Color.black.opacity(0.85))
                        .frame(height: 260)
                        .overlay(
                            Image(systemName: "video.slash")
                                .font(.system(size: 28))
                                .foregroundStyle(.white.opacity(0.5))
                        )
                } else if remote.count == 1, let only = remote.first {
                    SfuVideoTrackView(
                        track: only.value,
                        contentMode: only.key.hasSuffix("|screen") ? .scaleAspectFit : .scaleAspectFill
                    )
                        .frame(height: 260)
                        .frame(maxWidth: .infinity)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                } else {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 6) {
                        ForEach(remote, id: \.key) { entry in
                            SfuVideoTrackView(
                                track: entry.value,
                                contentMode: entry.key.hasSuffix("|screen") ? .scaleAspectFit : .scaleAspectFill
                            )
                                .frame(height: 128)
                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        }
                    }
                }
                if media.cameraOn, let local = media.localVideoTrack {
                    SfuVideoTrackView(track: local)
                        .frame(width: 84, height: 116)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(.white.opacity(0.6), lineWidth: 1)
                        )
                        .padding(8)
                } else if media.sharingScreen, let local = media.localScreenTrack {
                    SfuVideoTrackView(track: local, contentMode: .scaleAspectFit)
                        .frame(width: 84, height: 116)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 10, style: .continuous)
                                .stroke(.white.opacity(0.6), lineWidth: 1)
                        )
                        .padding(8)
                }
            }
        }
    }
}
