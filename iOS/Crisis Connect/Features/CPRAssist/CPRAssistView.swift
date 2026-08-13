import SwiftUI
import UIKit

struct CPRAssistView: View {
    @StateObject private var manager = CPRAssistManager()
    @Environment(\.openURL) private var openURL
    @State private var showInfo = false
    @State private var showEndConfirmation = false
    @State private var previousIdleTimerState = false

    private let emergencyNumber = EmergencyNumberResolver.resolve()

    var body: some View {
        Group {
            switch manager.phase {
            case .ready:
                readyContent
            case .compressions, .breaths:
                activeContent
            case .ended:
                endedContent
            }
        }
        .background(AppScreenBackground())
        .navigationTitle(Text("CPR_TITLE", tableName: "CPR"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(manager.isSessionRunning)
        .toolbar(manager.isSessionRunning ? .hidden : .visible, for: .tabBar)
        .toolbar {
            if manager.isSessionRunning {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showEndConfirmation = true
                    } label: {
                        Label("Back", systemImage: "chevron.left")
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { showInfo = true } label: {
                    Image(systemName: "info.circle")
                }
                .accessibilityLabel(Text("CPR_INFO", tableName: "CPR"))
            }
        }
        .sheet(isPresented: $showInfo) {
            CPRInfoView()
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: aedPresentation) {
            CPRAEDGuideView(manager: manager)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
                .interactiveDismissDisabled(manager.aedStep.rawValue >= CPRAEDStep.analyze.rawValue)
        }
        .alert(
            Text("CPR_END_CONFIRM_TITLE", tableName: "CPR"),
            isPresented: $showEndConfirmation
        ) {
            Button(role: .cancel) {} label: {
                Text("CPR_CONTINUE_SESSION", tableName: "CPR")
            }
            Button(role: .destructive) {
                manager.endSession()
            } label: {
                Text("CPR_END_SESSION", tableName: "CPR")
            }
        } message: {
            Text("CPR_END_CONFIRM_BODY", tableName: "CPR")
        }
        .interactiveDismissDisabled(manager.isSessionRunning)
        .onChange(of: manager.isSessionRunning) { _, running in
            updateIdleTimer(running: running)
        }
        .onDisappear {
            restoreIdleTimer()
        }
        .accessibilityIdentifier("cpr-assist-screen")
    }

    private var aedPresentation: Binding<Bool> {
        Binding(
            get: { manager.isAEDGuidePresented },
            set: { presented in
                if !presented { manager.closeAEDGuideBeforeAnalysis() }
            }
        )
    }

    private var readyContent: some View {
        ScrollView {
            VStack(spacing: 14) {
                emergencyCard
                readinessCard

                VStack(alignment: .leading, spacing: 10) {
                    CPRLabel("CPR_CHOOSE_MODE")
                        .font(.headline)
                    modeCard(.handsOnly)
                    modeCard(.thirtyToTwo)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                qualityCard

                Button(action: manager.startSession) {
                    Label {
                        CPRLabel("CPR_START")
                            .fontWeight(.bold)
                    } icon: {
                        Image(systemName: "play.fill")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(AppPrimaryButtonStyle())
                .controlSize(.large)

                CPRLabel("CPR_DISCLAIMER_SHORT")
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 16)
        }
        .scrollIndicators(.hidden)
    }

    private var activeContent: some View {
        ScrollView {
            VStack(spacing: 14) {
                activeHeader
                CPRRhythmHero(manager: manager)

                ProgressView(
                    value: Double(manager.compressionInSet),
                    total: Double(CPRAssistTiming.compressionsPerSet)
                )
                .tint(.appDanger)
                .scaleEffect(x: 1, y: 1.6)

                Text(
                    String(
                        format: CPRText.value("CPR_SET_PROGRESS"),
                        manager.compressionInSet,
                        CPRAssistTiming.compressionsPerSet
                    )
                )
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(Color.appTextSecondary)

                HStack(spacing: 8) {
                    metricCard(
                        CPRAssistTiming.formatDuration(manager.elapsed),
                        key: "CPR_METRIC_DURATION"
                    )
                    metricCard(String(manager.totalCompressions), key: "CPR_METRIC_TOTAL")
                    metricCard(
                        CPRAssistTiming.formatDuration(manager.roundRemaining),
                        key: "CPR_METRIC_ROUND"
                    )
                }

                instructionCard
                activeButtons
                settingsCard

                Button(role: .destructive) {
                    showEndConfirmation = true
                } label: {
                    Label {
                        CPRLabel("CPR_END_SESSION")
                    } icon: {
                        Image(systemName: "stop.fill")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.vertical, 14)
        }
        .scrollIndicators(.hidden)
    }

    private var endedContent: some View {
        VStack(spacing: 16) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(Color.appPrimary)
            CPRLabel("CPR_SESSION_ENDED")
                .font(.title2.weight(.bold))
            Text(
                String(
                    format: CPRText.value("CPR_SESSION_SUMMARY"),
                    CPRAssistTiming.formatDuration(manager.elapsed),
                    manager.totalCompressions
                )
            )
            .foregroundStyle(Color.appTextSecondary)
            .multilineTextAlignment(.center)
            Button(action: manager.resetSession) {
                CPRLabel("CPR_NEW_SESSION")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(AppPrimaryButtonStyle())
        }
        .padding(AppTheme.screenPadding)
    }

    private var emergencyCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "phone.fill")
                .font(.title3)
                .foregroundStyle(Color.appDanger)
            VStack(alignment: .leading, spacing: 3) {
                CPRLabel("CPR_CALL_FIRST")
                    .font(.headline)
                CPRLabel("CPR_CALL_SPEAKER_HINT")
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
            }
            Spacer(minLength: 8)
            Button(emergencyNumber, action: callEmergency)
                .buttonStyle(.borderedProminent)
                .tint(.appDanger)
                .fontWeight(.bold)
        }
        .appSurface(style: .muted, padding: 16)
    }

    private var readinessCard: some View {
        VStack(alignment: .leading, spacing: 13) {
            HStack(spacing: 10) {
                Image(systemName: "heart.fill")
                    .foregroundStyle(Color.appDanger)
                CPRLabel("CPR_ADULT_TITLE")
                    .font(.title3.weight(.bold))
            }
            CPRLabel("CPR_ADULT_SCOPE")
                .font(.subheadline)
                .foregroundStyle(Color.appTextSecondary)
            checklist("CPR_CHECK_SCENE")
            checklist("CPR_CHECK_RESPONSE")
            checklist("CPR_CHECK_HELP")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .elevated, padding: 18)
    }

    private var qualityCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "waveform.path.ecg")
                .foregroundStyle(Color.appPrimary)
            VStack(alignment: .leading, spacing: 4) {
                CPRLabel("CPR_QUALITY_TARGET")
                    .font(.headline)
                CPRLabel("CPR_QUALITY_SUMMARY")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .regular, padding: 16)
    }

    private var activeHeader: some View {
        HStack {
            Text(CPRText.value(manager.isPaused ? "CPR_STATUS_PAUSED" : "CPR_STATUS_ACTIVE"))
                .font(.caption.weight(.bold))
                .foregroundStyle(manager.isPaused ? Color.appWarning : Color.appDanger)
                .padding(.horizontal, 11)
                .padding(.vertical, 6)
                .background(
                    Capsule().fill(
                        (manager.isPaused ? Color.appWarning : Color.appDanger).opacity(0.12)
                    )
                )
            Spacer()
            Button(action: callEmergency) {
                Label(
                    String(format: CPRText.value("CPR_CALL_NUMBER"), emergencyNumber),
                    systemImage: "phone.fill"
                )
            }
            .font(.subheadline.weight(.semibold))
        }
    }

    private var instructionCard: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: manager.phase == .breaths ? "wind" : "heart.fill")
                .foregroundStyle(manager.phase == .breaths ? Color.appPrimary : Color.appDanger)
            VStack(alignment: .leading, spacing: 5) {
                CPRLabel(
                    manager.phase == .breaths
                        ? "CPR_INSTRUCTION_BREATHS_TITLE"
                        : "CPR_INSTRUCTION_COMPRESSIONS_TITLE"
                )
                .font(.headline)
                CPRLabel(
                    manager.phase == .breaths
                        ? "CPR_INSTRUCTION_BREATHS_BODY"
                        : "CPR_INSTRUCTION_COMPRESSIONS_BODY"
                )
                .font(.footnote)
                .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .regular, padding: 16)
    }

    private var activeButtons: some View {
        HStack(spacing: 10) {
            Button(action: manager.togglePause) {
                Label {
                    CPRLabel(manager.isPaused ? "CPR_RESUME" : "CPR_PAUSE")
                } icon: {
                    Image(systemName: manager.isPaused ? "play.fill" : "pause.fill")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .disabled(manager.pauseReason == .aedAnalysis)

            Button(action: manager.openAEDGuide) {
                Label {
                    CPRLabel("CPR_AED_ARRIVED")
                } icon: {
                    Image(systemName: "bolt.heart.fill")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.appDanger)
        }
        .controlSize(.large)
    }

    private var settingsCard: some View {
        VStack(spacing: 0) {
            Toggle(isOn: $manager.soundEnabled) {
                Label {
                    CPRLabel("CPR_SETTING_BEAT")
                } icon: {
                    Image(systemName: "speaker.wave.2.fill")
                }
            }
            .padding(.vertical, 8)
            Divider()
            Toggle(isOn: $manager.voiceEnabled) {
                Label {
                    CPRLabel("CPR_SETTING_VOICE")
                } icon: {
                    Image(systemName: "waveform")
                }
            }
            .padding(.vertical, 8)
            Divider()
            Toggle(isOn: $manager.hapticsEnabled) {
                Label {
                    CPRLabel("CPR_SETTING_HAPTICS")
                } icon: {
                    Image(systemName: "iphone.radiowaves.left.and.right")
                }
            }
            .padding(.vertical, 8)
        }
        .tint(.appPrimary)
        .appSurface(style: .regular, padding: 14)
    }

    private func modeCard(_ mode: CPRAssistMode) -> some View {
        let selected = manager.mode == mode
        let titleKey = mode == .handsOnly ? "CPR_MODE_HANDS_ONLY" : "CPR_MODE_30_2"
        let descriptionKey = mode == .handsOnly
            ? "CPR_MODE_HANDS_ONLY_DESCRIPTION"
            : "CPR_MODE_30_2_DESCRIPTION"
        return Button {
            manager.mode = mode
        } label: {
            HStack(spacing: 12) {
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(selected ? Color.appPrimary : Color.appTextSecondary)
                VStack(alignment: .leading, spacing: 4) {
                    CPRLabel(titleKey)
                        .font(.headline)
                        .foregroundStyle(.primary)
                    CPRLabel(descriptionKey)
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                        .multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .padding(15)
            .background(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .fill(selected ? Color.appPrimary.opacity(0.11) : Color.appSurface)
            )
            .overlay(
                RoundedRectangle(cornerRadius: AppTheme.cornerMedium, style: .continuous)
                    .stroke(selected ? Color.appPrimary : Color.appBorder, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private func checklist(_ key: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundStyle(Color.appPrimary)
            CPRLabel(key)
                .font(.subheadline)
        }
    }

    private func metricCard(_ value: String, key: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.headline.monospacedDigit())
            CPRLabel(key)
                .font(.caption2)
                .foregroundStyle(Color.appTextSecondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, minHeight: 58)
        .appSurface(style: .muted, padding: 8)
    }

    private func callEmergency() {
        guard let url = URL(string: "tel://\(emergencyNumber)") else { return }
        openURL(url)
    }

    private func updateIdleTimer(running: Bool) {
        if running {
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

private struct CPRRhythmHero: View {
    @ObservedObject var manager: CPRAssistManager
    @State private var pulseScale: CGFloat = 1

    var body: some View {
        let isBreathing = manager.phase == .breaths
        let color: Color = manager.isPaused ? .appWarning : (isBreathing ? .appPrimary : .appDanger)
        let progress = CGFloat(
            isBreathing
                ? max(0, manager.breathRemaining / CPRAssistTiming.breathPause)
                : Double(manager.compressionInSet) / Double(CPRAssistTiming.compressionsPerSet)
        )
        ZStack {
            Circle()
                .fill(color.opacity(0.1))
                .frame(width: 210, height: 210)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(color, style: StrokeStyle(lineWidth: 9, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .frame(width: 190, height: 190)
            VStack(spacing: 4) {
                Text(heroValue)
                    .font(.system(size: 58, weight: .black, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(color)
                CPRLabel(heroLabel)
                    .font(.caption.weight(.bold))
                if !isBreathing && !manager.isPaused {
                    Text(String(format: CPRText.value("CPR_BPM_VALUE"), CPRAssistTiming.targetBPM))
                        .font(.caption)
                        .foregroundStyle(Color.appTextSecondary)
                }
                if isBreathing {
                    Button {
                        manager.resumeCompressionsEarly()
                    } label: {
                        CPRLabel("CPR_RESUME_NOW")
                            .font(.caption.weight(.semibold))
                    }
                }
            }
        }
        .scaleEffect(pulseScale)
        .onChange(of: manager.beatSequence) { _, beat in
            guard beat > 0, manager.phase == .compressions, !manager.isPaused else { return }
            pulseScale = 1.075
            withAnimation(.easeOut(duration: 0.21)) { pulseScale = 1 }
        }
        .accessibilityElement(children: .combine)
    }

    private var heroValue: String {
        if manager.isPaused { return "Ⅱ" }
        if manager.phase == .breaths { return String(Int(ceil(manager.breathRemaining))) }
        return String(manager.compressionInSet)
    }

    private var heroLabel: String {
        if manager.isPaused { return "CPR_TAP_RESUME" }
        return manager.phase == .breaths ? "CPR_BREATHS_LABEL" : "CPR_COMPRESSIONS_LABEL"
    }
}

private struct CPRAEDGuideView: View {
    @ObservedObject var manager: CPRAssistManager

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                HStack(spacing: 12) {
                    Image(systemName: "bolt.heart.fill")
                        .font(.title2)
                        .foregroundStyle(Color.appDanger)
                        .frame(width: 48, height: 48)
                        .background(Circle().fill(Color.appDanger.opacity(0.12)))
                    VStack(alignment: .leading, spacing: 3) {
                        CPRLabel("CPR_AED_GUIDE_TITLE")
                            .font(.title2.weight(.bold))
                        Text(
                            String(
                                format: CPRText.value("CPR_AED_STEP_COUNT"),
                                manager.aedStep.rawValue + 1,
                                CPRAEDStep.allCases.count
                            )
                        )
                        .font(.subheadline)
                        .foregroundStyle(Color.appTextSecondary)
                    }
                    Spacer()
                    if manager.aedStep == .powerOn || manager.aedStep == .attachPads {
                        Button(action: manager.closeAEDGuideBeforeAnalysis) {
                            Image(systemName: "xmark.circle.fill")
                                .font(.title2)
                        }
                    }
                }

                ProgressView(
                    value: Double(manager.aedStep.rawValue + 1),
                    total: Double(CPRAEDStep.allCases.count)
                )
                .tint(.appDanger)

                VStack(alignment: .leading, spacing: 10) {
                    CPRLabel(titleKey)
                        .font(.title2.weight(.bold))
                    CPRLabel(bodyKey)
                        .font(.body)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .appSurface(style: .elevated, padding: 20)

                CPRLabel("CPR_AED_DEVICE_PRIORITY")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)

                actionButtons
            }
            .padding(.horizontal, AppTheme.screenPadding)
            .padding(.bottom, 32)
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        switch manager.aedStep {
        case .powerOn:
            primaryButton("CPR_AED_NEXT_PADS", action: manager.advanceAEDGuide)
        case .attachPads:
            primaryButton("CPR_AED_START_ANALYSIS", danger: true, action: manager.advanceAEDGuide)
        case .analyze:
            primaryButton("CPR_AED_ANALYSIS_COMPLETE", danger: true, action: manager.advanceAEDGuide)
        case .shockDecision:
            primaryButton("CPR_AED_SHOCK_DELIVERED", danger: true, action: manager.recordAEDDecision)
            Button(action: manager.recordAEDDecision) {
                CPRLabel("CPR_AED_NO_SHOCK")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        case .resumeCPR:
            primaryButton("CPR_AED_RESUME_NOW", action: manager.resumeAfterAED)
        }
    }

    private func primaryButton(_ key: String, danger: Bool = false, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            CPRLabel(key)
                .fontWeight(.bold)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .tint(danger ? .appDanger : .appPrimary)
        .controlSize(.large)
    }

    private var titleKey: String {
        switch manager.aedStep {
        case .powerOn: return "CPR_AED_POWER_TITLE"
        case .attachPads: return "CPR_AED_PADS_TITLE"
        case .analyze: return "CPR_AED_ANALYZE_TITLE"
        case .shockDecision: return "CPR_AED_DECISION_TITLE"
        case .resumeCPR: return "CPR_AED_RESUME_TITLE"
        }
    }

    private var bodyKey: String {
        switch manager.aedStep {
        case .powerOn: return "CPR_AED_POWER_BODY"
        case .attachPads: return "CPR_AED_PADS_BODY"
        case .analyze: return "CPR_AED_ANALYZE_BODY"
        case .shockDecision: return "CPR_AED_DECISION_BODY"
        case .resumeCPR: return "CPR_AED_RESUME_BODY"
        }
    }
}

private struct CPRInfoView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                CPRLabel("CPR_INFO_TITLE")
                    .font(.title2.weight(.bold))
                infoSection("CPR_INFO_SCOPE_TITLE", "CPR_INFO_SCOPE_BODY")
                infoSection("CPR_INFO_QUALITY_TITLE", "CPR_INFO_QUALITY_BODY")
                infoSection("CPR_INFO_AED_TITLE", "CPR_INFO_AED_BODY")
                infoSection("CPR_INFO_OFFLINE_TITLE", "CPR_INFO_OFFLINE_BODY")
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(Color.appWarning)
                    CPRLabel("CPR_DISCLAIMER_LONG")
                        .font(.footnote)
                }
                .appSurface(style: .muted, padding: 15)
            }
            .padding(AppTheme.screenPadding)
        }
    }

    private func infoSection(_ title: String, _ body: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            CPRLabel(title)
                .font(.headline)
            CPRLabel(body)
                .font(.body)
                .foregroundStyle(Color.appTextSecondary)
        }
    }
}

private struct CPRLabel: View {
    let key: String

    init(_ key: String) {
        self.key = key
    }

    var body: some View {
        Text(LocalizedStringKey(key), tableName: "CPR")
    }
}
