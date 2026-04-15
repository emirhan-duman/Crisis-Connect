//
//  LiDARView.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI
import ARKit
import SceneKit
import UIKit

private enum LiDARNightStyle {
    static let accent = Color(red: 0.32, green: 1.0, blue: 0.52)
    static let accentSoft = Color(red: 0.12, green: 0.55, blue: 0.3)
    static let hudBorder = Color(red: 0.22, green: 0.75, blue: 0.45).opacity(0.55)

    static func tint(for alertState: LiDARAlertState) -> Color {
        switch alertState {
        case .clear:
            return accent
        case .caution:
            return .yellow
        case .danger:
            return .red
        case .signalLost:
            return .orange
        }
    }

    static func tint(for guidance: LiDARGuidanceState) -> Color {
        switch guidance {
        case .moveForward:
            return accent
        case .moveLeft, .moveRight:
            return .yellow
        case .stop:
            return .red
        case .scanSlowly:
            return .orange
        }
    }
}

struct LiDARView: View {
    @StateObject private var viewModel = LiDARViewModel()
    @State private var showingControls = false

    var body: some View {
        Group {
            if viewModel.isSupported {
                ZStack {
                    LiDARCameraView(viewModel: viewModel)
                        .ignoresSafeArea()

                    LiDARDepthLayer(
                        viewModel: viewModel,
                        depthImage: viewModel.depthImage,
                        normalizedDisplayTransform: viewModel.depthDisplayTransform
                    )

                    LiDARTargetFrame(
                        detectionRegion: viewModel.detectionRegion,
                        tint: frameTint
                    )

                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            LiDARStatusPill(statusKey: viewModel.statusKey, isRunning: viewModel.isRunning)

                            Spacer()

                            LiDARIconButton(
                                systemImage: viewModel.isFrozen ? "play.fill" : "pause.fill",
                                accessibilityTitle: NSLocalizedString(
                                    viewModel.isFrozen ? "LIDAR_RESUME_BUTTON" : "LIDAR_FREEZE_BUTTON",
                                    comment: ""
                                ),
                                tint: viewModel.isFrozen ? .yellow : LiDARNightStyle.accent
                            ) {
                                viewModel.toggleFreeze()
                            }

                            LiDARIconButton(
                                systemImage: "slider.horizontal.3",
                                accessibilityTitle: NSLocalizedString("LIDAR_CONTROLS_LABEL", comment: ""),
                                tint: .white
                            ) {
                                showingControls = true
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 10)

                        Spacer()

                        LiDARBottomHUD(viewModel: viewModel)
                            .padding(.horizontal, 16)
                            .padding(.bottom, 18)
                    }
                }
            } else {
                ZStack {
                    AppScreenBackground()

                    VStack(spacing: 12) {
                        ContentUnavailableView(LocalizedStringKey("LIDAR_UNAVAILABLE_TITLE"), systemImage: "xmark.octagon")

                        Text(LocalizedStringKey("LIDAR_UNAVAILABLE_MESSAGE"))
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                    .appSurface(style: .elevated, padding: 20)
                    .padding(.horizontal, AppTheme.screenPadding)
                }
            }
        }
        .navigationTitle(LocalizedStringKey("NIGHT_VISION_NAV_TITLE"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingControls) {
            NavigationStack {
                LiDARControlsSheet(viewModel: viewModel)
            }
            .presentationDetents([.medium])
        }
        .onAppear { viewModel.start() }
        .onDisappear { viewModel.stop() }
    }

    private var frameTint: Color {
        switch viewModel.alertState {
        case .clear:
            return LiDARNightStyle.tint(for: viewModel.guidanceState)
        default:
            return LiDARNightStyle.tint(for: viewModel.alertState)
        }
    }
}

private struct LiDARDepthLayer: View {
    let viewModel: LiDARViewModel
    let depthImage: CGImage?
    let normalizedDisplayTransform: CGAffineTransform
    @State private var interfaceOrientation: UIInterfaceOrientation = .portrait

    var body: some View {
        GeometryReader { proxy in
            Group {
                if let depthImage {
                    Canvas { context, size in
                        let viewportTransform = LiDARProcessing.viewportTransform(
                            from: normalizedDisplayTransform,
                            in: size
                        )
                        context.concatenate(viewportTransform)
                        context.draw(
                            Image(decorative: depthImage, scale: 1),
                            in: CGRect(origin: .zero, size: size)
                        )
                    }
                } else {
                    Color.black
                }
            }
            .ignoresSafeArea()
            .clipped()
            .onAppear { updateViewport(size: proxy.size) }
            .onChange(of: proxy.size) { _, newSize in
                updateViewport(size: newSize)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
                updateViewport(size: proxy.size)
            }
        }
    }

    private func updateViewport(size: CGSize) {
        if let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene {
            if #available(iOS 26.0, *) {
                interfaceOrientation = scene.effectiveGeometry.interfaceOrientation
            } else {
                interfaceOrientation = scene.interfaceOrientation
            }
        } else {
            interfaceOrientation = .portrait
        }

        viewModel.updateViewport(size: size, interfaceOrientation: interfaceOrientation)
    }
}

private struct LiDARCameraView: UIViewRepresentable {
    let viewModel: LiDARViewModel

    func makeUIView(context: Context) -> ARSCNView {
        let view = ARSCNView(frame: .zero)
        view.scene = SCNScene()
        view.automaticallyUpdatesLighting = true
        view.scene.background.contents = UIColor.black
        view.isOpaque = true
        view.session.delegate = viewModel
        viewModel.attach(session: view.session)
        return view
    }

    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}

private struct LiDARTargetFrame: View {
    let detectionRegion: CGRect
    let tint: Color

    var body: some View {
        GeometryReader { proxy in
            let rect = CGRect(
                x: detectionRegion.minX * proxy.size.width,
                y: detectionRegion.minY * proxy.size.height,
                width: detectionRegion.width * proxy.size.width,
                height: detectionRegion.height * proxy.size.height
            )

            ZStack {
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .stroke(tint.opacity(0.2), lineWidth: 1)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)

                Path { path in
                    let cornerLength = min(rect.width, rect.height) * 0.12

                    path.move(to: CGPoint(x: rect.minX, y: rect.minY + cornerLength))
                    path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
                    path.addLine(to: CGPoint(x: rect.minX + cornerLength, y: rect.minY))

                    path.move(to: CGPoint(x: rect.maxX - cornerLength, y: rect.minY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + cornerLength))

                    path.move(to: CGPoint(x: rect.minX, y: rect.maxY - cornerLength))
                    path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
                    path.addLine(to: CGPoint(x: rect.minX + cornerLength, y: rect.maxY))

                    path.move(to: CGPoint(x: rect.maxX - cornerLength, y: rect.maxY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
                    path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - cornerLength))
                }
                .stroke(
                    tint.opacity(0.95),
                    style: StrokeStyle(lineWidth: 2.4, lineCap: .round, lineJoin: .round)
                )

                Circle()
                    .fill(tint.opacity(0.75))
                    .frame(width: 6, height: 6)
                    .position(x: rect.midX, y: rect.midY)
                    .shadow(color: tint.opacity(0.45), radius: 8)
            }
            .allowsHitTesting(false)
        }
    }
}

private struct LiDARStatusPill: View {
    let statusKey: String
    let isRunning: Bool

    var body: some View {
        let tint = isRunning ? LiDARNightStyle.accent : LiDARNightStyle.accentSoft

        HStack(spacing: 8) {
            Circle()
                .fill(tint)
                .frame(width: 8, height: 8)

            Text(LocalizedStringKey(statusKey))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white.opacity(0.92))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(
            Capsule()
                .stroke(tint.opacity(0.35), lineWidth: 1)
        )
    }
}

private struct LiDARIconButton: View {
    let systemImage: String
    let accessibilityTitle: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 16, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 42, height: 42)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(
                    Circle()
                        .stroke(Color.white.opacity(0.15), lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityTitle)
    }
}

private struct LiDARBottomHUD: View {
    @ObservedObject var viewModel: LiDARViewModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(LocalizedStringKey("LIDAR_DISTANCE_LABEL"))
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(LiDARNightStyle.accentSoft)

                    Text(viewModel.distanceText)
                        .font(.system(size: 30, weight: .bold, design: .monospaced))
                        .monospacedDigit()
                        .foregroundStyle(LiDARNightStyle.accent)
                }
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    LiDARMiniIconStat(
                        systemImage: guidanceSymbol,
                        tint: LiDARNightStyle.tint(for: viewModel.guidanceState)
                    )

                    LiDARMiniStat(
                        systemImage: "waveform.path.ecg",
                        value: NSLocalizedString(viewModel.signalQuality.titleKey, comment: ""),
                        tint: LiDARNightStyle.accent
                    )

                    LiDARMiniStat(
                        systemImage: "scope",
                        value: viewModel.alertDistanceText,
                        tint: .white.opacity(0.92)
                    )
                }

                VStack(alignment: .leading, spacing: 8) {
                    HStack(spacing: 8) {
                        LiDARMiniIconStat(
                            systemImage: guidanceSymbol,
                            tint: LiDARNightStyle.tint(for: viewModel.guidanceState)
                        )

                        LiDARMiniStat(
                            systemImage: "waveform.path.ecg",
                            value: NSLocalizedString(viewModel.signalQuality.titleKey, comment: ""),
                            tint: LiDARNightStyle.accent
                        )
                    }

                    LiDARMiniStat(
                        systemImage: "scope",
                        value: viewModel.alertDistanceText,
                        tint: .white.opacity(0.92)
                    )
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(LiDARNightStyle.hudBorder, lineWidth: 1)
        )
    }

    private var guidanceSymbol: String {
        switch viewModel.guidanceState {
        case .moveForward:
            return "arrow.up"
        case .moveLeft:
            return "arrow.turn.up.left"
        case .moveRight:
            return "arrow.turn.up.right"
        case .stop:
            return "hand.raised.fill"
        case .scanSlowly:
            return "viewfinder"
        }
    }
}

private struct LiDARMiniStat: View {
    let systemImage: String
    let value: String
    let tint: Color

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: systemImage)
                .font(.caption.weight(.semibold))
                .foregroundStyle(tint)

            Text(value)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.white.opacity(0.88))
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color.black.opacity(0.2), in: Capsule())
    }
}

private struct LiDARMiniIconStat: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.caption.weight(.semibold))
            .foregroundStyle(tint)
            .frame(width: 32, height: 32)
            .background(Color.black.opacity(0.2), in: Capsule())
    }
}

private struct LiDARControlsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var viewModel: LiDARViewModel

    var body: some View {
        Form {
            Section {
                Picker(
                    NSLocalizedString("LIDAR_SCAN_PROFILE_LABEL", comment: ""),
                    selection: Binding(
                        get: { viewModel.scanProfile.rawValue },
                        set: { selection in
                            if let profile = LiDARScanProfile(rawValue: selection) {
                                viewModel.setScanProfile(profile)
                            }
                        }
                    )
                ) {
                    ForEach(LiDARScanProfile.allCases) { profile in
                        Text(LocalizedStringKey(profile.titleKey))
                            .tag(profile.rawValue)
                    }
                }
                .pickerStyle(.segmented)

                Picker(
                    NSLocalizedString("LIDAR_ALERT_RADIUS_LABEL", comment: ""),
                    selection: Binding(
                        get: { viewModel.alertPreset.rawValue },
                        set: { selection in
                            if let preset = LiDARAlertPreset(rawValue: selection) {
                                viewModel.setAlertPreset(preset)
                            }
                        }
                    )
                ) {
                    ForEach(LiDARAlertPreset.allCases) { preset in
                        Text(LocalizedStringKey(preset.titleKey))
                            .tag(preset.rawValue)
                    }
                }
                .pickerStyle(.segmented)

                Toggle(
                    NSLocalizedString("LIDAR_HAPTICS_LABEL", comment: ""),
                    isOn: Binding(
                        get: { viewModel.hapticsEnabled },
                        set: { newValue in
                            if newValue != viewModel.hapticsEnabled {
                                viewModel.toggleHaptics()
                            }
                        }
                    )
                )
            } footer: {
                Text(LocalizedStringKey("LIDAR_HINT"))
            }
        }
        .navigationTitle(LocalizedStringKey("LIDAR_CONTROLS_LABEL"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("COMMON_CLOSE") {
                    dismiss()
                }
            }
        }
    }
}

#Preview {
    NavigationStack { LiDARView() }
}
