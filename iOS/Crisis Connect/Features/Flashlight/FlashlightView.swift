import SwiftUI
import UIKit

struct FlashlightView: View {
    @StateObject private var viewModel = FlashlightViewModel()
    @Environment(\.scenePhase) private var scenePhase
    @State private var showInfo = false
    @State private var previousIdleTimerState = false

    private let modeColumns = [
        GridItem(.flexible(), spacing: 10),
        GridItem(.flexible(), spacing: 10),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                statusCard

                if let errorKey = viewModel.errorKey {
                    errorCard(errorKey)
                }

                VStack(alignment: .leading, spacing: 12) {
                    sectionTitle("FLASHLIGHT_MODE_HEADING", systemImage: "flashlight.on.fill")

                    LazyVGrid(columns: modeColumns, spacing: 10) {
                        ForEach(FlashlightViewModel.Mode.allCases) { mode in
                            modeButton(mode)
                        }
                    }

                    FLText(viewModel.mode.descriptionKey)
                        .font(.footnote)
                        .foregroundStyle(Color.appTextSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .appSurface(style: .regular, padding: 16)

                modeSpecificControls
                autoOffCard
                safetyCard
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 16)
        }
        .scrollIndicators(.hidden)
        .background(AppScreenBackground())
        .navigationTitle(Text("FLASHLIGHT_TITLE", tableName: "Flashlight"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showInfo = true } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel(Text("FLASHLIGHT_INFO", tableName: "Flashlight"))
            }
        }
        .sheet(isPresented: $showInfo) {
            FlashlightInfoView()
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .alert(
            Text("FLASHLIGHT_STROBE_WARNING_TITLE", tableName: "Flashlight"),
            isPresented: $viewModel.showStrobeWarning
        ) {
            Button(role: .cancel) {
                viewModel.dismissStrobeWarning()
            } label: {
                Text("FLASHLIGHT_CANCEL", tableName: "Flashlight")
            }
            Button {
                viewModel.confirmStrobeWarning()
            } label: {
                Text("FLASHLIGHT_STROBE_WARNING_CONFIRM", tableName: "Flashlight")
            }
        } message: {
            Text("FLASHLIGHT_STROBE_WARNING_BODY", tableName: "Flashlight")
        }
        .fullScreenCover(isPresented: screenLightPresentation) {
            ScreenLightActiveView(
                screenColor: viewModel.screenColor,
                brightness: viewModel.screenBrightness,
                onStop: viewModel.stop
            )
            .interactiveDismissDisabled()
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase != .active { viewModel.stop() }
        }
        .onChange(of: viewModel.isActive) { _, isActive in
            updateIdleTimer(isActive: isActive)
        }
        .onDisappear {
            viewModel.stop()
            restoreIdleTimer()
        }
        .accessibilityIdentifier("flashlight-screen")
    }

    private var screenLightPresentation: Binding<Bool> {
        Binding(
            get: { viewModel.isScreenLightActive },
            set: { isPresented in
                if !isPresented { viewModel.stop() }
            }
        )
    }

    private var statusCard: some View {
        VStack(spacing: 12) {
            ZStack {
                Circle()
                    .fill(viewModel.isActive ? Color.appPrimary : Color.appSurfaceMuted)
                    .frame(width: 76, height: 76)

                Image(systemName: "flashlight.on.fill")
                    .font(.system(size: 31, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(viewModel.isActive ? Color.white : Color.appTextSecondary)
            }

            FLText(viewModel.isActive ? "FLASHLIGHT_STATUS_ACTIVE" : "FLASHLIGHT_STATUS_READY")
                .font(.title3.weight(.bold))

            FLText(viewModel.mode.titleKey)
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)

            Button(action: viewModel.requestToggle) {
                HStack(spacing: 8) {
                    Image(systemName: viewModel.isActive ? "stop.fill" : "power")
                    FLText(viewModel.isActive ? "FLASHLIGHT_STOP" : "FLASHLIGHT_START")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle(fill: viewModel.isActive ? .appDanger : .appPrimary))
        }
        .frame(maxWidth: .infinity)
        .appSurface(style: .elevated, padding: 20)
        .animation(.easeOut(duration: 0.2), value: viewModel.isActive)
    }

    private func errorCard(_ key: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.appDanger)
            FLText(key)
                .font(.footnote)
                .foregroundStyle(.primary)
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .muted, padding: 14)
    }

    private func modeButton(_ mode: FlashlightViewModel.Mode) -> some View {
        let selected = viewModel.mode == mode
        return Button {
            viewModel.mode = mode
        } label: {
            VStack(alignment: .leading, spacing: 9) {
                Image(systemName: mode.systemImage)
                    .font(.system(size: 20, weight: .semibold))
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(selected ? Color.white : Color.appPrimary)

                FLText(mode.titleKey)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(selected ? Color.white : Color.primary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, minHeight: 70, alignment: .leading)
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(selected ? Color.appPrimary : Color.appSurfaceMuted)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(selected ? Color.appPrimary : Color.appBorder, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var modeSpecificControls: some View {
        switch viewModel.mode {
        case .screenLight:
            screenLightControls
        case .strobe:
            strobeControls
            torchIntensityCard
        case .lowPower:
            lowPowerCard
        default:
            torchIntensityCard
        }
    }

    private var torchIntensityCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("FLASHLIGHT_BRIGHTNESS_HEADING", systemImage: "slider.horizontal.3")
            Slider(value: $viewModel.intensity, in: 0.1...1)
                .tint(.appPrimary)
            HStack {
                FLText("FLASHLIGHT_LOW")
                Spacer()
                Text("\(Int((viewModel.intensity * 100).rounded()))%")
                    .fontWeight(.semibold)
                Spacer()
                FLText("FLASHLIGHT_HIGH")
            }
            .font(.caption)
            .foregroundStyle(Color.appTextSecondary)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var lowPowerCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "battery.25percent")
                .foregroundStyle(Color.appSuccess)
            FLText("FLASHLIGHT_LOW_POWER_NOTE")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
            Spacer(minLength: 0)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var strobeControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("FLASHLIGHT_STROBE_RATE_HEADING", systemImage: "bolt.fill")
            Picker("", selection: $viewModel.strobeRate) {
                Text("1 Hz").tag(1)
                Text("2 Hz").tag(2)
                Text("3 Hz").tag(3)
            }
            .labelsHidden()
            .pickerStyle(.segmented)
            FLText("FLASHLIGHT_STROBE_RATE_NOTE")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var screenLightControls: some View {
        VStack(alignment: .leading, spacing: 14) {
            sectionTitle("FLASHLIGHT_SCREEN_SETTINGS_HEADING", systemImage: "sun.max.fill")

            HStack(spacing: 8) {
                ForEach(FlashlightViewModel.ScreenColor.allCases) { color in
                    compactChoice(
                        titleKey: color.titleKey,
                        selected: viewModel.screenColor == color
                    ) {
                        viewModel.screenColor = color
                    }
                }
            }

            Slider(value: $viewModel.screenBrightness, in: 0.2...1)
                .tint(.appPrimary)

            HStack {
                FLText("FLASHLIGHT_SCREEN_BRIGHTNESS")
                Spacer()
                Text("\(Int((viewModel.screenBrightness * 100).rounded()))%")
                    .fontWeight(.semibold)
            }
            .font(.caption)
            .foregroundStyle(Color.appTextSecondary)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var autoOffCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            sectionTitle("FLASHLIGHT_AUTO_OFF_HEADING", systemImage: "timer")
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(FlashlightViewModel.AutoOff.allCases) { option in
                        compactChoice(
                            titleKey: option.titleKey,
                            selected: viewModel.autoOff == option
                        ) {
                            viewModel.autoOff = option
                        }
                    }
                }
            }
            .scrollIndicators(.hidden)
        }
        .appSurface(style: .regular, padding: 16)
    }

    private var safetyCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(Color.appWarning)
            FLText("FLASHLIGHT_SAFETY_NOTE")
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 0)
        }
        .appSurface(style: .muted, padding: 14)
    }

    private func sectionTitle(_ key: String, systemImage: String) -> some View {
        HStack(spacing: 9) {
            Image(systemName: systemImage)
                .foregroundStyle(Color.appPrimary)
            FLText(key)
                .font(.headline)
        }
    }

    private func compactChoice(
        titleKey: String,
        selected: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            FLText(titleKey)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(selected ? Color.white : Color.appPrimary)
                .padding(.horizontal, 13)
                .padding(.vertical, 9)
                .background(
                    Capsule()
                        .fill(selected ? Color.appPrimary : Color.appSurfaceMuted)
                )
                .overlay(
                    Capsule()
                        .stroke(selected ? Color.appPrimary : Color.appBorder, lineWidth: 1)
                )
        }
        .buttonStyle(.plain)
    }

    private func updateIdleTimer(isActive: Bool) {
        if isActive {
            previousIdleTimerState = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
        } else {
            restoreIdleTimer()
        }
    }

    private func restoreIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = previousIdleTimerState
    }
}

private struct FlashlightInfoView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    infoSection("FLASHLIGHT_INFO_MODES_TITLE", body: "FLASHLIGHT_INFO_MODES_BODY")
                    infoSection("FLASHLIGHT_INFO_BEACON_TITLE", body: "FLASHLIGHT_INFO_BEACON_BODY")
                    infoSection("FLASHLIGHT_INFO_SAFETY_TITLE", body: "FLASHLIGHT_INFO_SAFETY_BODY")
                }
                .padding(20)
            }
            .background(Color.appBackground)
            .navigationTitle(Text("FLASHLIGHT_INFO", tableName: "Flashlight"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: {
                        Text("FLASHLIGHT_DONE", tableName: "Flashlight")
                    }
                }
            }
        }
    }

    private func infoSection(_ title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            FLText(title)
                .font(.headline)
            FLText(body)
                .font(.body)
                .foregroundStyle(Color.appTextSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct ScreenLightActiveView: View {
    let screenColor: FlashlightViewModel.ScreenColor
    let brightness: Double
    let onStop: () -> Void

    @State private var previousBrightness: CGFloat = UIScreen.main.brightness

    private var backgroundColor: Color {
        switch screenColor {
        case .white: return .white
        case .warm: return Color(red: 1, green: 0.89, blue: 0.72)
        case .red: return Color(red: 0.72, green: 0.11, blue: 0.11)
        }
    }

    private var foregroundColor: Color {
        screenColor == .red ? .white : Color(red: 0.07, green: 0.07, blue: 0.07)
    }

    var body: some View {
        ZStack {
            backgroundColor.ignoresSafeArea()
            VStack(spacing: 18) {
                Image(systemName: "sun.max.fill")
                    .font(.system(size: 48, weight: .semibold))
                FLText("FLASHLIGHT_SCREEN_ACTIVE")
                    .font(.title3.weight(.bold))
                Button(action: onStop) {
                    FLText("FLASHLIGHT_STOP")
                        .font(.headline)
                        .foregroundStyle(backgroundColor)
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Capsule().fill(foregroundColor))
                }
                .buttonStyle(.plain)
            }
            .foregroundStyle(foregroundColor)
        }
        .onAppear {
            previousBrightness = UIScreen.main.brightness
            UIScreen.main.brightness = CGFloat(brightness)
        }
        .onChange(of: brightness) { _, newValue in
            UIScreen.main.brightness = CGFloat(newValue)
        }
        .onDisappear {
            UIScreen.main.brightness = previousBrightness
        }
        .accessibilityIdentifier("flashlight-screen-light-active")
    }
}

private struct FLText: View {
    let key: String

    init(_ key: String) {
        self.key = key
    }

    var body: some View {
        Text(LocalizedStringKey(key), tableName: "Flashlight")
    }
}

#if DEBUG
    #Preview {
        NavigationStack {
            FlashlightView()
        }
    }
#endif
