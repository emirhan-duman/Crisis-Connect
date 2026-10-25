import SwiftUI
import UIKit

/// Contact-scanning presentation. Payload validation and pairing stay in New Contact.
struct ContactQRScannerScreen: View {
    let onScan: (String) -> Void
    let onError: (String) -> Void

    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @State private var phase: CameraPhase = .preparing
    @State private var cameraState = QRCodeCameraState()
    @State private var showHelp = false
    @State private var torchRequested = false
    @State private var showTorchError = false
    @State private var cameraRevision = 0

    private enum CameraPhase {
        case preparing, running, permissionDenied, unavailable, interrupted
    }

    private var compactLayout: Bool {
        dynamicTypeSize >= .xxxLarge || verticalSizeClass == .compact
    }

    private var blocked: Bool {
        phase == .permissionDenied || phase == .unavailable || phase == .interrupted
    }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    cameraViewport
                        .frame(height: compactLayout ? 320 : max(320, geometry.size.height - 126))

                    VStack(alignment: .leading, spacing: 8) {
                        Text("QR_SCANNER_HEADING")
                            .font(.headline)
                            .foregroundStyle(.primary)
                            .accessibilityAddTraits(.isHeader)
                        Text("QR_SCANNER_AUTO_HINT")
                            .font(.subheadline)
                            .foregroundStyle(Color.appTextSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        if compactLayout && !blocked {
                            Text(LocalizedStringKey(phase == .running ? "QR_SCANNER_READY" : "QR_SCANNER_STARTING"))
                                .font(.caption.weight(.medium))
                                .foregroundStyle(Color.appTextSecondary)
                        }
                    }
                    .padding(.horizontal, 4)
                }
                .padding(20)
                .frame(maxWidth: 600)
                .frame(maxWidth: .infinity)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(Color.appBackground.ignoresSafeArea())
        .tint(.primary)
        .toolbarBackground(Color.appSurfaceElevated, for: .navigationBar)
        .toolbarBackground(.visible, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showHelp = true
                } label: {
                    Image(systemName: "questionmark.circle")
                        .foregroundStyle(Color.primary)
                }
                .tint(Color.primary)
                .accessibilityLabel("QR_SCANNER_HELP")
                .accessibilityIdentifier("qr-scanner-help")
            }
        }
        .sheet(isPresented: $showHelp) {
            helpSheet
        }
        .alert("QR_SCANNER_LIGHT_ERROR", isPresented: $showTorchError) {
            Button("COMMON_OK", role: .cancel) {}
        }
        .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
            if phase == .permissionDenied {
                // Recreate the capture view after returning from permission settings.
                phase = .preparing
                cameraRevision += 1
            }
        }
    }

    private var cameraViewport: some View {
        ZStack {
            Color.black
            QRCodeScannerView(
                onScan: onScan,
                onError: handleCameraError,
                isScanningPaused: showHelp,
                torchEnabled: torchRequested,
                onStateChange: { state in
                    cameraState = state
                    if !state.hasTorch { torchRequested = false }
                    if state.isRunning {
                        phase = .running
                    }
                }
            )
            .id(cameraRevision)
            .accessibilityHidden(true)

            if !blocked {
                ContactQRViewfinder()
                    .allowsHitTesting(false)
                    .accessibilityHidden(true)
            }

            if blocked {
                cameraNotice
            } else if !compactLayout {
                VStack {
                    HStack {
                        Label(LocalizedStringKey(phase == .running ? "QR_SCANNER_READY" : "QR_SCANNER_STARTING"), systemImage: "camera.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 10)
                            .background(.black.opacity(0.72), in: Capsule())
                        Spacer(minLength: 0)
                    }
                    Spacer(minLength: 0)
                }
                .padding(16)
            }

            if phase == .running && cameraState.hasTorch {
                VStack {
                    Spacer(minLength: 0)
                    Button {
                        torchRequested = !cameraState.torchOn
                    } label: {
                        Label(LocalizedStringKey(cameraState.torchOn ? "QR_SCANNER_LIGHT_OFF" : "QR_SCANNER_LIGHT_ON"),
                              systemImage: cameraState.torchOn ? "flashlight.off.fill" : "flashlight.on.fill")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 18)
                            .frame(minHeight: 48)
                            .background(.black.opacity(0.72), in: Capsule())
                            .overlay(Capsule().stroke(.white.opacity(0.4), lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                }
                .padding(16)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .accessibilityIdentifier("qr-scanner-viewport")
    }

    private var cameraNotice: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 16) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 28, weight: .medium))
                        .foregroundStyle(.white)
                        .frame(width: 72, height: 72)
                        .background(.white.opacity(0.12), in: Circle())
                        .accessibilityHidden(true)
                    Text(LocalizedStringKey(phase == .permissionDenied ? "QR_SCANNER_PERMISSION_TITLE" : phase == .interrupted ? "QR_SCANNER_PAUSED_TITLE" : "QR_SCANNER_UNAVAILABLE_TITLE"))
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    Text(LocalizedStringKey(phase == .permissionDenied ? "QR_SCANNER_PERMISSION_DETAIL" : phase == .interrupted ? "QR_SCANNER_PAUSED_DETAIL" : "QR_SCANNER_UNAVAILABLE_DETAIL"))
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.75))
                        .fixedSize(horizontal: false, vertical: true)
                    if phase == .permissionDenied {
                        Button {
                            PlatformRuntime.openSystemSettings()
                        } label: {
                            Text("QR_SCANNER_OPEN_SETTINGS")
                                .font(.headline)
                                .foregroundStyle(.black)
                                .frame(maxWidth: .infinity, minHeight: 50)
                                .background(.white, in: RoundedRectangle(cornerRadius: 16))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
                .padding(28)
                .frame(maxWidth: 400)
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(.black)
        .accessibilityIdentifier("qr-scanner-camera-notice")
    }

    private var helpSheet: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    helpRow("qrcode.viewfinder", "QR_SCANNER_TIP_ALIGN")
                    helpRow("hand.raised", "QR_SCANNER_TIP_STILL")
                    helpRow("sun.max", "QR_SCANNER_TIP_LIGHT")
                }
                .padding(24)
            }
            .background(Color.appBackground)
            .navigationTitle("QR_SCANNER_HELP")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("COMMON_CLOSE") { showHelp = false }
                        .accessibilityIdentifier("qr-scanner-help-close")
                }
            }
        }
        .tint(.primary)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func helpRow(_ icon: String, _ key: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 16) {
            Image(systemName: icon)
                .font(.title3)
                .frame(width: 28)
                .accessibilityHidden(true)
            Text(key)
                .font(.body)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(.primary)
    }

    private func handleCameraError(_ errorKey: String) {
        switch errorKey {
        case "CAMERA_PERMISSION_DENIED": phase = .permissionDenied
        case "CAMERA_UNAVAILABLE": phase = .unavailable
        case "CAMERA_INTERRUPTED":
            torchRequested = false
            phase = .interrupted
        case "CAMERA_TORCH_UNAVAILABLE":
            torchRequested = cameraState.torchOn
            showTorchError = true
        default: onError(errorKey)
        }
    }
}

private struct ContactQRViewfinder: View {
    var body: some View {
        GeometryReader { geometry in
            let side = min(geometry.size.width, geometry.size.height) * 0.78
            let guide = CGRect(x: (geometry.size.width - side) / 2,
                               y: (geometry.size.height - side) / 2, width: side, height: side)
            ZStack {
                Path { path in
                    path.addRect(CGRect(origin: .zero, size: geometry.size))
                    path.addRoundedRect(in: guide, cornerSize: CGSize(width: 20, height: 20))
                }
                .fill(.black.opacity(0.42), style: FillStyle(eoFill: true))

                RoundedRectangle(cornerRadius: 20)
                    .stroke(.white.opacity(0.24), lineWidth: 1)
                    .frame(width: side, height: side)

                ContactQRGuideCorners()
                    .stroke(.white, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .shadow(color: .black.opacity(0.6), radius: 1)
                    .frame(width: side, height: side)
            }
        }
    }
}

private struct ContactQRGuideCorners: Shape {
    func path(in rect: CGRect) -> Path {
        let radius: CGFloat = min(20, rect.width / 4)
        let length: CGFloat = min(32, rect.width / 3)
        var path = Path()
        path.move(to: CGPoint(x: 0, y: length))
        path.addLine(to: CGPoint(x: 0, y: radius))
        path.addQuadCurve(to: CGPoint(x: radius, y: 0), control: .zero)
        path.addLine(to: CGPoint(x: length, y: 0))
        path.move(to: CGPoint(x: rect.maxX - length, y: 0))
        path.addLine(to: CGPoint(x: rect.maxX - radius, y: 0))
        path.addQuadCurve(to: CGPoint(x: rect.maxX, y: radius), control: CGPoint(x: rect.maxX, y: 0))
        path.addLine(to: CGPoint(x: rect.maxX, y: length))
        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - length))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - radius))
        path.addQuadCurve(to: CGPoint(x: rect.maxX - radius, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.maxX - length, y: rect.maxY))
        path.move(to: CGPoint(x: length, y: rect.maxY))
        path.addLine(to: CGPoint(x: radius, y: rect.maxY))
        path.addQuadCurve(to: CGPoint(x: 0, y: rect.maxY - radius), control: CGPoint(x: 0, y: rect.maxY))
        path.addLine(to: CGPoint(x: 0, y: rect.maxY - length))
        return path
    }
}
