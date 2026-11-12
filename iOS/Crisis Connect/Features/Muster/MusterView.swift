import SwiftUI
import Combine
import FirebaseAuth
import UserNotifications
import AVFoundation

func musterNeedsResponse(_ row: CachedMusterAssignment, latest: MusterEvent?, now: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
    let session = row.assignment.session
    return ["live", "drill"].contains(session.kind) && session.status != "closed" && latest == nil && row.assignment.response?.answer == nil &&
        (session.kind != "drill" || session.deadlineAtMillis == nil || session.deadlineAtMillis == 0 || session.deadlineAtMillis! > now)
}

/// A local event is a complete response, including deliberately absent choice/audio fields.
/// The caller supplies model.latest(row), which already yields to a newer server response.
struct MusterProjectedResponse: Equatable {
    let answer: String?
    let optionId: String?
    let note: String
    let voice: MusterVoiceMetadata?
    let helpVoice: MusterVoiceMetadata?
    init(latest: MusterEvent?, server: MusterOwnResponse?) {
        if let latest {
            answer = latest.answer; optionId = latest.optionId; note = latest.note
            voice = latest.voice.map { MusterVoiceMetadata(eventId: latest.serverEventId, mimeType: "audio/mp4", durationMillis: $0.durationMillis, bytes: $0.bytes, sha256: $0.sha256) }
        } else {
            answer = server?.answer; optionId = server?.optionId; note = server?.note ?? ""; voice = server?.voice
        }
        // An unresolved earlier help request is independent of the latest answer's attachment.
        helpVoice = server?.helpOpen == true ? server?.helpVoice : nil
    }
    func displayLabel(config: MusterResponseConfig?) -> String {
        config?.options.first(where: { $0.id == optionId })?.displayLabel ?? musterText(answer ?? "message")
    }
}

func musterResetsComposerAfterLatestEventChange(from previousId: String?, to latestId: String?, editing: Bool) -> Bool {
    guard previousId != latestId else { return false }
    // A newly enqueued local response is durable and closes its composer. A newer server
    // response may hide an older local receipt; that must not discard an unrelated unsent edit.
    return latestId != nil || !editing
}

@MainActor final class MusterViewModel: ObservableObject {
    @Published var assignments: [CachedMusterAssignment] = []
    @Published var events: [MusterEvent] = []
    @Published var busy = false
    @Published var saving = false
    @Published var failed = false
    @Published var saveFailed = false
    @Published var uid: String?
    var outbox: [MusterEvent] { events.filter { ["pending", "failed"].contains($0.state) } }
    func latest(_ row: CachedMusterAssignment) -> MusterEvent? {
        guard let saved = events.last(where: { $0.panelId == row.panelId && $0.sessionId == row.assignment.session.id }) else { return nil }
        if saved.state == "sent", let response = row.assignment.response, let serverAnswer = response.answer, let serverTime = response.answeredAtMillis,
           serverTime > saved.answeredAtMillis || (serverTime == saved.answeredAtMillis && serverAnswer != saved.answer) { return nil }
        return saved
    }
    var priority: CachedMusterAssignment? {
        assignments.reversed().first { musterNeedsResponse($0, latest: latest($0)) } ??
        assignments.reversed().first { ["pending", "failed"].contains(latest($0)?.state ?? "") || $0.assignment.response?.helpOpen == true }
    }
    func load() async {
        let current = Auth.auth().currentUser.flatMap { $0.isAnonymous ? nil : $0.uid }
        if uid != current { assignments = []; events = []; failed = false; saveFailed = false; busy = false; saving = false; uid = current }
        guard let current else { return }
        do {
            let saved = try await MusterQueue.shared.assignments(uid: current)
            let pending = try await MusterQueue.shared.events(uid: current)
            guard Auth.auth().currentUser?.uid == current else { return }
            assignments = saved.filter { ["live", "drill"].contains($0.assignment.session.kind) }; events = pending
        } catch { if Auth.auth().currentUser?.uid == current { failed = true } }
    }
    func discoverFromHome() async {
        // Publish cached rows before waiting for any authenticated discovery request.
        await load(); guard let expected = uid else { return }
        await MusterClient.shared.discoverForHome(expectedUid: expected)
        await load()
    }
    func refresh(panel: String?, session: String?) async {
        await load(); guard let expected = uid, !busy else { return }
        busy = true; failed = false
        do { try await MusterClient.shared.refresh(panel: panel, session: session) }
        catch { if Auth.auth().currentUser?.uid == expected { failed = true } }
        await load()
        if uid == expected { busy = false }; MusterBackgroundManager.shared.scheduleIfNeeded()
    }
    func answer(_ row: CachedMusterAssignment, value: String, note: String) async {
        guard let expected = uid, !saving else { return }
        saving = true; saveFailed = false
        do { try await MusterClient.shared.enqueue(uid: expected, panel: row.panelId, session: row.assignment.session.id, answer: value, note: note) }
        catch { if Auth.auth().currentUser?.uid == expected { saveFailed = true } }
        await load(); if uid == expected { saving = false }
    }
    func answerConfigured(_ row: CachedMusterAssignment, optionId: String?, note: String, recording: MusterVoiceRecording?) async -> Bool {
        guard let expected = uid, !saving else { return false }
        saving = true; saveFailed = false
        do { try await MusterClient.shared.enqueueConfigured(uid: expected, row: row, optionId: optionId, note: note, recording: recording) }
        catch { if Auth.auth().currentUser?.uid == expected { saveFailed = true } }
        await load(); if uid == expected { saving = false }
        return uid == expected && !saveFailed
    }
    func audioData(_ row: CachedMusterAssignment, helpVoice: Bool = false) async throws -> Data {
        guard let expected = uid, Auth.auth().currentUser?.uid == expected else { throw MusterError.accountChanged }
        let event = latest(row), projected = MusterProjectedResponse(latest: event, server: row.assignment.response)
        guard let metadata = helpVoice ? projected.helpVoice : projected.voice else { throw MusterError.invalid }
        if !helpVoice, let event, event.voice != nil {
            if event.state != "sent", let data = try await MusterQueue.shared.voiceData(event) {
                guard Auth.auth().currentUser?.uid == expected else { throw MusterError.accountChanged }; return data
            }
        }
        return try await MusterClient.shared.voice(uid: expected, panel: row.panelId, session: row.assignment.session.id, metadata: metadata)
    }
}

private struct MusterPalette {
    var dark: Bool
    var accent: Color { dark ? Color(red: 0.74, green: 0.83, blue: 0.95) : Color(red: 0.19, green: 0.29, blue: 0.46) }
    var band: Color { dark ? Color(red: 0.09, green: 0.15, blue: 0.20) : Color(red: 0.93, green: 0.96, blue: 0.99) }
    var secondary: Color { dark ? Color(red: 0.73, green: 0.78, blue: 0.83) : Color(red: 0.32, green: 0.38, blue: 0.45) }
    var amber: Color { dark ? .orange : Color(red: 0.58, green: 0.38, blue: 0) }
    var green: Color { dark ? Color(red: 0.60, green: 0.87, blue: 0.73) : Color(red: 0.09, green: 0.42, blue: 0.27) }
    var red: Color { dark ? Color(red: 1, green: 0.71, blue: 0.67) : Color(red: 0.70, green: 0.15, blue: 0.12) }
}

struct MusterHomeBanner: View {
    @AppStorage("appLanguage") private var language = "system"
    @StateObject private var model = MusterViewModel()
    @State private var authListener: AuthStateDidChangeListenerHandle?
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    var body: some View {
        VStack(spacing: 0) {
            if let row = model.priority {
                NavigationLink { MusterView(panel: row.panelId, session: row.assignment.session.id) } label: {
                    MusterHomeCard(row: row, latest: model.latest(row), awaitingCount: model.assignments.filter { musterNeedsResponse($0, latest: model.latest($0)) }.count)
                }.buttonStyle(.plain).accessibilityIdentifier("muster-home-pending")
            } else if !model.assignments.isEmpty || !model.outbox.isEmpty || model.failed {
                NavigationLink { MusterView() } label: {
                    MusterHomeCard(row: nil, latest: nil)
                }.buttonStyle(.plain)
            }
        }
        .environment(\.locale, language == "system" ? .autoupdatingCurrent : Locale(identifier: language))
        .environment(\.layoutDirection, MusterLocalization.isRightToLeft ? .rightToLeft : .leftToRight)
        .task { await model.discoverFromHome() }
        .onAppear {
            guard !PlatformRuntime.isRunningTests, authListener == nil else { return }
            authListener = Auth.auth().addStateDidChangeListener { _, _ in
                Task { @MainActor in await model.discoverFromHome() }
            }
        }
        .onDisappear {
            if let authListener { Auth.auth().removeStateDidChangeListener(authListener) }
            authListener = nil
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await model.discoverFromHome() } }
        }
        .onReceive(NotificationCenter.default.publisher(for: .musterStateChanged).receive(on: RunLoop.main)) { _ in Task { await model.load() } }
    }
}

struct MusterHomeCard: View {
    let row: CachedMusterAssignment?
    let latest: MusterEvent?
    var awaitingCount = 1
    @Environment(\.colorScheme) private var scheme
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    private var awaiting: Bool { row.map { musterNeedsResponse($0, latest: latest) } ?? false }
    private var subtitle: String {
        guard let row else { return "ui_history_settings" }
        if awaiting { return row.assignment.session.kind == "drill" ? "ui_drill_awaiting" : "ui_awaiting" }
        if latest?.state == "failed" { return "ui_not_delivered" }
        if latest?.state == "pending" { return "ui_home_pending" }
        return "ui_help_status"
    }
    var body: some View {
        HStack(spacing: 14) {
            AppIconBadge(systemName: "person.badge.shield.checkmark", tint: .appPrimary, size: 54)
            VStack(alignment: .leading, spacing: 5) {
                Text(musterText("title")).font(.headline).foregroundStyle(.primary).lineLimit(1)
                Text(musterText(subtitle)).font(.footnote).foregroundStyle(latest?.state == "failed" ? palette.red : Color.appTextSecondary)
                    .lineLimit(1).multilineTextAlignment(.leading)
            }.frame(maxWidth: .infinity, alignment: .leading)
            if awaiting {
                Text(awaitingCount > 99 ? "99+" : String(awaitingCount)).font(.caption.weight(.semibold))
                    .foregroundStyle(.white).padding(.horizontal, 7).padding(.vertical, 3).background(Color.appPrimary, in: Capsule())
            }
            Image(systemName: "chevron.forward").font(.footnote.weight(.bold)).foregroundStyle(Color.appTextSecondary)
        }.appSurface(style: .regular, padding: 16)
    }
}

struct MusterView: View {
    @AppStorage("appLanguage") private var language = "system"
    var panel: String? = nil
    var session: String? = nil
    @StateObject private var model = MusterViewModel()
    @State private var outbox = false
    @State private var showSettings = false
    @Environment(\.colorScheme) private var scheme
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    private var selected: CachedMusterAssignment? { model.assignments.first { $0.panelId == panel && $0.assignment.session.id == session } }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 20) {
                if model.uid == nil { empty("ui_sign_in_title", "sign_in", "lock") }
                else if let selected { MusterAssignmentRow(row: selected, model: model).id(model.uid) }
                else if session != nil { empty("title", "connect", "wifi.slash") }
                else {
                    Picker(musterText("title"), selection: $outbox) {
                        Text(musterText("ui_checkins")).tag(false)
                        Text(String(format: musterText("ui_outbox_count"), model.outbox.count)).tag(true)
                    }.pickerStyle(.segmented)
                    if model.failed { notice("ui_refresh_error", "wifi.slash") }
                    if outbox { outboxContent }
                    else {
                        VStack(alignment: .leading, spacing: 8) {
                            Text(musterText("ui_institutional")).font(.caption.bold()).tracking(1).foregroundStyle(palette.accent)
                            Text(musterText("ui_inbox_title")).font(.title2.bold())
                            Text(musterText("ui_inbox_detail")).font(.subheadline).foregroundStyle(palette.secondary)
                        }.padding(.vertical, 8)
                        if model.assignments.isEmpty { empty("empty", "ui_empty_detail", "checkmark.shield") }
                        ForEach(Array(model.assignments.reversed())) { row in
                            NavigationLink { MusterDetailView(row: row, model: model) } label: {
                                HStack(spacing: 14) {
                                    Image(systemName: "checklist").font(.title2).foregroundStyle(palette.accent)
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text(row.assignment.session.displayTitle).font(.headline).foregroundStyle(.primary).multilineTextAlignment(.leading)
                                        MusterStatus(state: row.assignment.session.status == "closed" ? "closed" : model.latest(row)?.state ?? (row.assignment.response?.answer == nil ? "awaiting" : "sent"))
                                        if row.assignment.session.kind == "drill" { Text(musterText("drill")).font(.caption2).foregroundStyle(palette.secondary) }
                                    }.frame(maxWidth: .infinity, alignment: .leading)
                                    Image(systemName: "chevron.forward").font(.caption).foregroundStyle(palette.secondary)
                                }.padding(18).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
                            }.buttonStyle(.plain)
                        }
                    }
                }
            }.padding(.horizontal, AppTheme.screenPadding).padding(.vertical, 20)
        }
        .background(Color.appBackground.ignoresSafeArea())
        .navigationTitle(musterText(session == nil ? "title" : "ui_your_status")).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if model.uid != nil {
                    Button { Task { await model.refresh(panel: panel, session: session) } } label: {
                        if model.busy { ProgressView() } else { Image(systemName: "arrow.clockwise") }
                    }.disabled(model.busy).accessibilityLabel(musterText("refresh"))
                    Button { showSettings = true } label: { Image(systemName: "slider.horizontal.3") }.accessibilityLabel(musterText("ui_settings"))
                }
            }
        }
        .environment(\.locale, language == "system" ? .autoupdatingCurrent : Locale(identifier: language))
        .environment(\.layoutDirection, MusterLocalization.isRightToLeft ? .rightToLeft : .leftToRight)
        .sheet(isPresented: $showSettings) { MusterSettingsView() }
        .task { await model.refresh(panel: panel, session: session) }
        .onReceive(NotificationCenter.default.publisher(for: .musterStateChanged).receive(on: RunLoop.main)) { _ in Task { await model.load() } }
    }
    private var outboxContent: some View {
        VStack(alignment: .leading, spacing: 16) {
            if model.outbox.contains(where: { $0.state == "pending" }) {
                Text(musterText("ui_delivery_explanation"))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }
            if model.outbox.isEmpty { empty("ui_outbox_empty", "ui_outbox_empty_detail", "checkmark.circle") }
            ForEach(model.outbox) { event in
                VStack(alignment: .leading, spacing: 10) {
                    Text(model.assignments.first { $0.panelId == event.panelId && $0.assignment.session.id == event.sessionId }?.assignment.session.displayTitle ?? musterText("title")).font(.headline)
                    MusterStatus(state: event.state)
                    Text(model.assignments.first { $0.panelId == event.panelId && $0.assignment.session.id == event.sessionId }?.assignment.session.responseConfig?.options.first { $0.id == event.optionId }?.displayLabel ?? musterText(event.answer))
                    if !event.note.isEmpty { Text(event.note).font(.subheadline).foregroundStyle(palette.secondary) }
                    if event.state == "failed" {
                        Text(musterText("failed"))
                            .font(.footnote)
                            .foregroundStyle(Color.appTextSecondary)
                    }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(18).background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 18))
            }
        }
    }
    private func empty(_ title: String, _ detail: String, _ symbol: String) -> some View {
        VStack(spacing: 16) {
            Image(systemName: symbol).font(.largeTitle).foregroundStyle(palette.accent).padding(20).background(palette.band, in: Circle())
            Text(musterText(title)).font(.title3.bold())
            Text(musterText(detail)).font(.subheadline).foregroundStyle(palette.secondary)
        }.multilineTextAlignment(.center).frame(maxWidth: .infinity).padding(.vertical, 32)
    }
}

private struct MusterDetailView: View {
    let row: CachedMusterAssignment
    @ObservedObject var model: MusterViewModel
    var body: some View {
        ScrollView {
            if model.uid != nil, let current = model.assignments.first(where: { $0.id == row.id }) {
                MusterAssignmentRow(row: current, model: model).id(model.uid)
            } else { Text(musterText("sign_in")) }
        }
        .contentMargins(.horizontal, AppTheme.screenPadding, for: .scrollContent)
        .contentMargins(.vertical, 20, for: .scrollContent)
        .background(Color.appBackground.ignoresSafeArea())
        .navigationTitle(musterText("ui_your_status"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
struct MusterAssignmentRow: View {
    let row: CachedMusterAssignment
    @ObservedObject var model: MusterViewModel
    @Environment(\.colorScheme) private var scheme
    @State private var note = ""
    @State private var showNote = false
    @State private var editing = false
    @State private var instructionsExpanded = false
    @State private var optionId: String?
    @State private var loadingVoice = false
    @State private var voiceTask: Task<Void, Never>?
    @State private var downloadedVoice: Data?
    @State private var downloadedHelpVoice: Data?
    @StateObject private var audio = MusterAudio()
    @Environment(\.scenePhase) private var scenePhase
    private var config: MusterResponseConfig? { row.assignment.session.responseConfig }
    private var latest: MusterEvent? { model.latest(row) }
    private var projected: MusterProjectedResponse { MusterProjectedResponse(latest: latest, server: row.assignment.response) }
    private var answer: String? { projected.answer }
    private var state: String { latest?.state ?? (answer == nil ? "awaiting" : "sent") }
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    private var answerLabel: String { projected.displayLabel(config: config) }
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            requestHeader
            if model.failed { notice("ui_refresh_error", "wifi.slash") }
            if model.saveFailed { notice("ui_save_error", "exclamationmark.circle", error: true) }
            if row.assignment.response?.helpOpen == true { notice("ui_help_open", "cross.case", error: true) }
            if answer != nil, !editing { responseSummary }
            if row.assignment.session.status == "closed" { notice("closed", "lock") }
            else if row.assignment.session.wakePlaceholder == true { notice("connect", "wifi.slash") }
            else if answer == nil || editing {
                if let config { configuredComposer(config) }
                else { legacyComposer }
            }
        }
        .frame(maxWidth: 640)
        .frame(maxWidth: .infinity)
        .onChange(of: latest?.id) { previousId, latestId in
            clearPlayback()
            if musterResetsComposerAfterLatestEventChange(from: previousId, to: latestId, editing: editing) {
                editing = false; note = ""; optionId = nil; audio.reset()
            }
        }
        .onChange(of: projected.voice) { _, _ in clearPlayback() }
        .onChange(of: projected.helpVoice) { _, _ in clearPlayback() }
        .onChange(of: model.uid) { _, _ in clearDraft() }
        .onChange(of: scenePhase, initial: true) { _, phase in
            audio.sceneActivityChanged(isActive: phase == .active)
            if phase == .background { voiceTask?.cancel(); audio.suspend() }
        }
        .onReceive(NotificationCenter.default.publisher(for: .musterStateChanged)) { _ in if Auth.auth().currentUser?.uid != model.uid { clearDraft() } }
        .onDisappear { clearDraft() }
        .onReceive(NotificationCenter.default.publisher(for: AVAudioSession.interruptionNotification)) { audio.handleInterruption($0) }
        if audio.error { notice("voice_error", "mic.slash", error: true) }
    }
    private var requestHeader: some View {
        let session = row.assignment.session
        let isDrill = session.kind == "drill"
        let instructions = session.displayInstructions
        let canExpand = instructions.count > 140

        return VStack(alignment: .leading, spacing: 14) {
            Label(musterText(isDrill ? "drill" : "ui_institutional"), systemImage: isDrill ? "flag" : "checkmark.shield")
                .font(.caption.weight(.semibold))
                .foregroundStyle(isDrill ? palette.amber : Color.appPrimary)
            Text(session.displayTitle)
                .font(.title2.weight(.bold))
                .accessibilityAddTraits(.isHeader)
            if session.wakePlaceholder != true, !instructions.isEmpty {
                Text(instructions)
                    .font(.subheadline)
                    .foregroundStyle(Color.appTextSecondary)
                    .lineLimit(canExpand && !instructionsExpanded ? 4 : nil)
                if canExpand {
                    Button(musterText(instructionsExpanded ? "ui_less" : "ui_instructions")) {
                        instructionsExpanded.toggle()
                    }
                    .font(.footnote.weight(.semibold))
                    .tint(.appPrimary)
                }
            }
            if let deadline = session.deadlineAtMillis, deadline > 0 {
                Divider()
                let date = Date(timeIntervalSince1970: Double(deadline) / 1000)
                let formatted = date.formatted(.dateTime.locale(MusterLocalization.locale).year().month(.abbreviated).day().hour().minute())
                Label(String(format: musterText("ui_deadline"), formatted), systemImage: "clock")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .elevated, padding: 20)
    }

    private var responseSummary: some View {
        VStack(alignment: .leading, spacing: 16) {
            HStack(alignment: .top, spacing: 12) {
                AppIconBadge(systemName: state == "sent" ? "checkmark.circle" : state == "failed" ? "exclamationmark.circle" : "icloud.and.arrow.up",
                             tint: state == "sent" ? palette.green : state == "failed" ? palette.red : palette.amber)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 6) {
                    Text(answerLabel)
                        .font(.headline)
                        .accessibilityAddTraits(.isHeader)
                    MusterStatus(state: state)
                }
            }
            if state == "pending" || state == "failed" {
                Text(musterText(state == "pending" ? "ui_delivery_explanation" : "failed"))
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }
            if !projected.note.isEmpty { Text(projected.note).font(.subheadline) }
            if projected.voice != nil { voiceButton() }
            if let helpVoice = projected.helpVoice, helpVoice.eventId != projected.voice?.eventId {
                voiceButton(helpVoice: true)
            }
            if row.assignment.session.status != "closed" {
                Button {
                    note = projected.note
                    optionId = projected.optionId
                    audio.reset()
                    editing = true
                } label: {
                    Label(musterText("ui_change_answer"), systemImage: "square.and.pencil")
                }
                .buttonStyle(AppSecondaryButtonStyle())
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .appSurface(style: .elevated, padding: 20)
    }
    private func clearPlayback() {
        voiceTask?.cancel(); voiceTask = nil; loadingVoice = false; downloadedVoice = nil; downloadedHelpVoice = nil
        if audio.isPlaying { audio.stopPlayback() }
    }
    private func clearDraft() { clearPlayback(); audio.reset(); note = ""; optionId = nil; editing = false }
    private func voiceButton(helpVoice: Bool = false) -> some View {
        Button {
            if audio.isPlaying { audio.stopPlayback(); return }
            if let cached = helpVoice ? downloadedHelpVoice : downloadedVoice { audio.play(cached, uid: model.uid); return }
            let expected = model.uid; loadingVoice = true
            voiceTask = Task {
                do {
                    let data = try await model.audioData(row, helpVoice: helpVoice)
                    guard !Task.isCancelled, model.uid == expected, Auth.auth().currentUser?.uid == expected else { return }
                    if helpVoice { downloadedHelpVoice = data } else { downloadedVoice = data }
                    audio.play(data, uid: expected)
                } catch { if !Task.isCancelled { audio.error = true } }
                if !Task.isCancelled { loadingVoice = false }
            }
        } label: {
            HStack {
                if loadingVoice { ProgressView() }
                Label((helpVoice ? musterText("ui_help_open") + " · " : "") + musterText(audio.isPlaying ? "voice_stop" : "voice_play"),
                      systemImage: audio.isPlaying ? "stop.fill" : "play.fill")
            }
        }
        .buttonStyle(AppSecondaryButtonStyle())
        .disabled(loadingVoice)
    }

    private var legacyComposer: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 12) {
                Text(musterText("ui_question")).font(.headline).accessibilityAddTraits(.isHeader)
                noteEditor(limit: 500)
                answerButton("safe", "ui_safe_detail", "checkmark.circle", palette.green)
                answerButton("help", "ui_help_detail", "cross.case", palette.red)
                answerButton("at_muster", "ui_point_detail", "mappin.and.ellipse", palette.accent)
            }
            .appSurface(style: .elevated)
            offlineDetail
        }
    }

    @ViewBuilder
    private func noteEditor(limit: Int) -> some View {
        if showNote || !note.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                TextField(musterText("note"), text: $note, axis: .vertical)
                    .font(.body)
                    .lineLimit(2...5)
                    .textFieldStyle(.plain)
                    .onChange(of: note) { _, value in
                        guard value.utf16.count > limit else { return }
                        var bounded = value
                        while bounded.utf16.count > limit { bounded.removeLast() }
                        note = bounded
                    }
                if !note.isEmpty {
                    Text("\(note.utf16.count)/\(limit)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Color.appTextSecondary)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }
            .padding(12)
            .background(Color.appSurfaceMuted, in: RoundedRectangle(cornerRadius: AppTheme.cornerSmall))
        } else {
            Button {
                showNote = true
            } label: {
                Label(musterText("ui_add_note"), systemImage: "square.and.pencil")
            }
            .buttonStyle(AppSecondaryButtonStyle())
        }
    }

    private func configuredComposer(_ config: MusterResponseConfig) -> some View {
        let hasMessage = !note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || audio.recording != nil
        let canSend = !model.saving && !audio.isRecording && !audio.isBusy && (optionId != nil || hasMessage)

        return VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 12) {
                Text(config.question.isEmpty ? musterText("ui_question") : config.question)
                    .font(.headline)
                    .accessibilityAddTraits(.isHeader)
                ForEach(config.options) { option in
                    choiceButton(option)
                }
                if !config.options.isEmpty && (config.allowText || config.allowVoice) { Divider() }
                if config.allowText { noteEditor(limit: 2000) }
                if config.allowVoice { voiceComposer }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .appSurface(style: .elevated)
            .disabled(model.saving)

            if optionId == nil && (config.allowText || config.allowVoice) {
                Label(musterText("message_hint"), systemImage: "info.circle")
                    .font(.footnote)
                    .foregroundStyle(Color.appTextSecondary)
            }
            Button {
                audio.stopPlayback()
                let selected = optionId
                let text = note
                let clip = audio.recording
                Task {
                    if await model.answerConfigured(row, optionId: selected, note: text, recording: clip) {
                        clearDraft()
                    }
                }
            } label: {
                HStack {
                    if model.saving { ProgressView().tint(.white) }
                    Label(musterText("send_response"), systemImage: "arrow.up.circle")
                }
            }
            .buttonStyle(AppPrimaryButtonStyle(fill: scheme == .dark ? Color.appPrimary.opacity(0.72) : .appPrimary))
            .disabled(!canSend)
            .opacity(canSend || model.saving ? 1 : 0.5)
            offlineDetail
        }
    }

    private func choiceButton(_ option: MusterResponseOption) -> some View {
        let isSelected = optionId == option.id
        let shape = RoundedRectangle(cornerRadius: AppTheme.cornerSmall)
        return Button {
            optionId = isSelected ? nil : option.id
        } label: {
            HStack(spacing: 12) {
                Text(option.displayLabel)
                    .font(.subheadline.weight(.semibold))
                    .multilineTextAlignment(.leading)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(isSelected ? Color.appPrimary : Color.appTextSecondary)
                    .accessibilityHidden(true)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 48)
            .background(isSelected ? Color.appPrimarySoft : Color.appSurfaceMuted, in: shape)
            .overlay { shape.strokeBorder(isSelected ? Color.appPrimary : Color.appBorder, lineWidth: isSelected ? 1.5 : 1) }
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var voiceComposer: some View {
        VStack(alignment: .leading, spacing: 8) {
            if audio.isRecording {
                Button {
                    audio.stop()
                } label: {
                    Label(musterText("voice_stop") + " · \(audio.elapsed)/30", systemImage: "stop.circle.fill")
                }
                .buttonStyle(AppSecondaryButtonStyle(tint: palette.red))
                ProgressView(value: Double(audio.elapsed), total: 30)
                    .tint(palette.red)
                    .accessibilityHidden(true)
            } else if let recording = audio.recording {
                HStack(spacing: 8) {
                    Button {
                        audio.preview()
                    } label: {
                        HStack(spacing: 10) {
                            Image(systemName: audio.isPlaying ? "stop.fill" : "play.fill")
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(musterText(audio.isPlaying ? "voice_stop" : "voice_play"))
                                    .multilineTextAlignment(.leading)
                                Text(Duration.milliseconds(recording.durationMillis).formatted(.time(pattern: .minuteSecond)))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(Color.appTextSecondary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .buttonStyle(AppSecondaryButtonStyle())

                    Button(role: .destructive) {
                        audio.reset()
                    } label: {
                        Image(systemName: "trash")
                            .frame(width: 44, height: 44)
                            .foregroundStyle(palette.red)
                            .background(Color.appSurfaceMuted, in: RoundedRectangle(cornerRadius: AppTheme.cornerSmall))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(musterText("voice_delete"))
                }
            } else {
                Button {
                    if let uid = model.uid { Task { await audio.start(uid: uid) } }
                } label: {
                    HStack {
                        if audio.isBusy { ProgressView() }
                        Label(musterText("voice_record"), systemImage: "mic")
                    }
                }
                .buttonStyle(AppSecondaryButtonStyle())
                .disabled(audio.isBusy)
                Text(musterText("voice_limit"))
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
            }
        }
    }

    private var offlineDetail: some View {
        Label(musterText("ui_offline_detail"), systemImage: "wifi.slash")
            .font(.footnote)
            .foregroundStyle(Color.appTextSecondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func answerButton(_ value: String, _ detail: String, _ symbol: String, _ tint: Color) -> some View {
        Button {
            let text = note
            Task { await model.answer(row, value: value, note: text) }
        } label: {
            HStack(spacing: 12) {
                AppIconBadge(systemName: symbol, tint: tint, size: 36)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text(musterText(value)).font(.subheadline.weight(.semibold))
                    Text(musterText(detail)).font(.caption).foregroundStyle(Color.appTextSecondary)
                }
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                Image(systemName: "chevron.forward")
                    .font(.caption)
                    .foregroundStyle(Color.appTextSecondary)
                    .accessibilityHidden(true)
            }
            .padding(12)
            .background(tint.opacity(0.10), in: RoundedRectangle(cornerRadius: AppTheme.cornerSmall))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(model.saving)
        .opacity(model.saving ? 0.55 : 1)
    }
}
private struct MusterStatus: View {
    let state: String
    @Environment(\.colorScheme) private var scheme
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    var body: some View {
        Label(musterText(state == "sent" ? "sent" : state == "pending" ? "queued" : state == "failed" ? "ui_not_delivered" : state == "closed" ? "closed" : "ui_awaiting"),
              systemImage: state == "sent" ? "checkmark.circle" : state == "failed" ? "exclamationmark.circle" : state == "closed" ? "lock" : "clock")
            .font(.caption.weight(.medium)).foregroundStyle(state == "failed" ? palette.red : state == "sent" ? palette.green : Color.secondary)
    }
}
private func notice(_ key: String, _ symbol: String, error: Bool = false) -> some View {
    Label(musterText(key), systemImage: symbol).font(.footnote).foregroundStyle(error ? Color.primary : Color.primary.opacity(0.75))
        .frame(maxWidth: .infinity, alignment: .leading).padding(14).background((error ? Color.red : .gray).opacity(0.06), in: RoundedRectangle(cornerRadius: 14))
}
private struct MusterSettingsView: View {
    @State private var notificationsReady = false
    @State private var soundReady = false
    @State private var permissionUndecided = false
    @Environment(\.scenePhase) private var scenePhase
    private func refreshPermissions() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        notificationsReady = settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional
        soundReady = settings.soundSetting == .enabled
        permissionUndecided = settings.authorizationStatus == .notDetermined
    }
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var scheme
    private var palette: MusterPalette { MusterPalette(dark: scheme == .dark) }
    var body: some View {
        NavigationStack {
            List {
                Section(musterText("ui_notification_settings")) {
                    Label(musterText(notificationsReady ? "alerts_enabled" : "alerts_disabled"), systemImage: notificationsReady ? "checkmark.circle" : "bell.slash")
                    Label(musterText(soundReady ? "sound_enabled" : "sound_disabled"), systemImage: soundReady ? "speaker.wave.2" : "speaker.slash")
                    if permissionUndecided {
                        Button(musterText("allow_notifications")) { Task {
                            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                            await refreshPermissions()
                        } }
                    }
                    Button(musterText("ui_open_system")) { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
                Section(musterText("urgent_alerts")) {
                    Label(musterText("critical_unavailable"), systemImage: "lock.shield")
                    Text(musterText("critical_help")).font(.footnote).foregroundStyle(palette.secondary)
                }
                Section(musterText("flash_alerts")) { Text(musterText("flash_help")).font(.footnote).foregroundStyle(palette.secondary) }
                Section { Text(musterText("ui_offline_detail")).foregroundStyle(palette.secondary) }
            }.navigationTitle(musterText("ui_settings")).navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("COMMON_CLOSE") { dismiss() } } }
        }.presentationDetents([.medium, .large])
            .task { await refreshPermissions() }
            .onChange(of: scenePhase) { _, phase in if phase == .active { Task { await refreshPermissions() } } }
    }
}
