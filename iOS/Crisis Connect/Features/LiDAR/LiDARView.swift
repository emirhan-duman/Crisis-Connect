//
//  LiDARView.swift
//  Crisis Connect
//
//  Created by Assistant on 23.12.2025
//

import SwiftUI
import ARKit
import MetalKit
import UIKit

private enum LiDARNightStyle {
    static let accent = Color(red: 0.32, green: 1.0, blue: 0.52)
    static let accentSoft = Color(red: 0.12, green: 0.55, blue: 0.3)

    static func tint(for alertState: LiDARAlertState) -> Color {
        switch alertState {
        case .clear: return accent
        case .caution: return .yellow
        case .danger: return .red
        case .signalLost: return .orange
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

                    LiDARViewportObserver(viewModel: viewModel)

                    LiDARTargetFrame(
                        detectionRegion: viewModel.detectionRegion,
                        tint: LiDARNightStyle.tint(for: viewModel.alertState)
                    )

                    if viewModel.alertState == .danger {
                        Rectangle()
                            .strokeBorder(Color.red.opacity(0.88), lineWidth: 4)
                            .ignoresSafeArea()
                            .allowsHitTesting(false)
                    }

                    VStack(spacing: 0) {
                        HStack(spacing: 12) {
                            if let status = topStatus {
                                LiDARStatusPill(
                                    statusKey: status.key,
                                    tint: status.tint,
                                    systemImage: status.systemImage
                                )
                            }

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

                        if viewModel.alertState == .caution || viewModel.alertState == .danger {
                            LiDARObstacleHUD(viewModel: viewModel)
                                .padding(.horizontal, 16)
                                .padding(.bottom, 18)
                                .transition(.move(edge: .bottom).combined(with: .opacity))
                        }
                    }
                }
                .animation(.easeOut(duration: 0.16), value: viewModel.alertState)
            } else {
                ZStack {
                    AppScreenBackground()

                    VStack(spacing: 12) {
                        ContentUnavailableView(
                            LocalizedStringKey("LIDAR_UNAVAILABLE_TITLE"),
                            systemImage: "xmark.octagon"
                        )

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

    private var topStatus: (key: String, tint: Color, systemImage: String)? {
        if viewModel.isFrozen {
            return ("LIDAR_FROZEN_WARNING", .yellow, "pause.fill")
        }
        if viewModel.alertState == .signalLost {
            return ("LIDAR_ALERT_SIGNAL", .orange, "exclamationmark.triangle.fill")
        }
        if viewModel.signalQuality == .weak {
            return ("LIDAR_SIGNAL_WEAK", .orange, "waveform.path.ecg")
        }
        return nil
    }
}

private struct LiDARCameraView: UIViewRepresentable {
    let viewModel: LiDARViewModel

    final class Coordinator {
        let session = ARSession()
        var renderer: LiDARMetalRenderer?
    }

    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    func makeUIView(context: Context) -> MTKView {
        let view = MTKView(frame: .zero)
        view.backgroundColor = .black
        if let renderer = LiDARMetalRenderer(view: view) {
            context.coordinator.renderer = renderer
            viewModel.attach(renderer: renderer)
        }
        viewModel.attach(session: context.coordinator.session)
        return view
    }

    func updateUIView(_ uiView: MTKView, context: Context) {}
}

private struct LiDARViewportObserver: View {
    let viewModel: LiDARViewModel
    @State private var interfaceOrientation: UIInterfaceOrientation = .portrait

    var body: some View {
        GeometryReader { proxy in
            Color.clear
                .onAppear { updateViewport(size: proxy.size) }
                .onChange(of: proxy.size) { _, newSize in
                    updateViewport(size: newSize)
                }
                .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
                    updateViewport(size: proxy.size)
                }
        }
        .allowsHitTesting(false)
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
                    .stroke(tint.opacity(0.16), lineWidth: 1)
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
            }
            .allowsHitTesting(false)
        }
    }
}

private struct LiDARStatusPill: View {
    let statusKey: String
    let tint: Color
    let systemImage: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: systemImage)
                .font(.caption.weight(.bold))
                .foregroundStyle(tint)

            Text(LocalizedStringKey(statusKey))
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .padding(.horizontal, 12)
        .frame(minHeight: 44)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().stroke(tint.opacity(0.55), lineWidth: 1))
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
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 48, height: 48)
                .background(.ultraThinMaterial, in: Circle())
                .overlay(Circle().stroke(Color.white.opacity(0.18), lineWidth: 1))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityTitle)
    }
}

private struct LiDARObstacleHUD: View {
    @ObservedObject var viewModel: LiDARViewModel

    var body: some View {
        let isDanger = viewModel.alertState == .danger
        let tint: Color = isDanger ? .red : .yellow

        HStack(spacing: 14) {
            Image(systemName: isDanger ? "exclamationmark.octagon.fill" : "exclamationmark.triangle.fill")
                .font(.system(size: 30, weight: .bold))
                .foregroundStyle(tint)
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(LocalizedStringKey(isDanger ? "LIDAR_OBSTACLE_IMMEDIATE" : "LIDAR_OBSTACLE_DETECTED"))
                    .font(.headline.weight(.heavy))
                    .foregroundStyle(.white)

                Text(viewModel.distanceText)
                    .font(.system(size: 27, weight: .bold, design: .monospaced))
                    .monospacedDigit()
                    .foregroundStyle(tint)
            }

            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .frame(maxWidth: .infinity, minHeight: 82, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(tint.opacity(0.85), lineWidth: isDanger ? 2 : 1)
        )
        .accessibilityElement(children: .combine)
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
                        Text(LocalizedStringKey(profile.titleKey)).tag(profile.rawValue)
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
                        Text(LocalizedStringKey(preset.titleKey)).tag(preset.rawValue)
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
                VStack(alignment: .leading, spacing: 10) {
                    Text(LocalizedStringKey("LIDAR_HINT"))
                    Text(LocalizedStringKey("LIDAR_SAFETY_NOTE"))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(LocalizedStringKey("LIDAR_CONTROLS_LABEL"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("COMMON_CLOSE") { dismiss() }
            }
        }
    }
}

#Preview {
    NavigationStack { LiDARView() }
}
