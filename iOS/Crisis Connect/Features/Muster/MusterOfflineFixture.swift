#if DEBUG
import AVFoundation
import Combine
import Network
import Security
import SwiftUI

/// Separate entry point for a real-device XCUITest. No account or network services are started.
struct MusterOfflineFixtureApp: App {
    static var runID: UUID? {
        let arguments = ProcessInfo.processInfo.arguments
        guard let index = arguments.firstIndex(of: "UITEST_MUSTER_OFFLINE_FIXTURE"),
              arguments.indices.contains(index + 1) else { return nil }
        return UUID(uuidString: arguments[index + 1])
    }

    var body: some Scene {
        WindowGroup {
            if let runID = Self.runID {
                let useHTTPS = ProcessInfo.processInfo.arguments.contains("UITEST_MUSTER_OFFLINE_HTTPS")
                MusterOfflineFixtureView(runID: runID, useHTTPS: useHTTPS)
            }
        }
    }
}

struct MusterOfflineFixtureView: View {
    @StateObject private var fixture: MusterOfflineFixture
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var noteFocused: Bool

    init(runID: UUID, useHTTPS: Bool, configuredTransport: MusterOfflineHTTPS? = nil) {
        _fixture = StateObject(wrappedValue: MusterOfflineFixture(
            runID: runID, useHTTPS: useHTTPS, configuredTransport: configuredTransport
        ))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Personnel check-in test fixture").font(.headline)
                Text(fixture.status).accessibilityIdentifier("muster-fixture-status")
                Text("Pending: \(fixture.pending)").accessibilityIdentifier("muster-fixture-pending")
                Text("Sent: \(fixture.sent)").accessibilityIdentifier("muster-fixture-sent")
                Text("Stored audio: \(fixture.attachments)").accessibilityIdentifier("muster-fixture-audio")
                Text("Received: \(fixture.received)").accessibilityIdentifier("muster-fixture-received")
                Text("Saved text: \(fixture.savedText)").accessibilityIdentifier("muster-fixture-saved-text")
                if fixture.useHTTPS {
                    Text(fixture.pathDescription).accessibilityIdentifier("muster-fixture-network")
                    Text(fixture.deliveryDescription).accessibilityIdentifier("muster-fixture-delivery")
                    Text("Server voice: \(fixture.serverVoiceCount)").accessibilityIdentifier("muster-fixture-server-voice")
                }
                TextField("Fixture note", text: $fixture.note)
                    .textFieldStyle(.roundedBorder)
                    .focused($noteFocused)
                    .submitLabel(.done)
                    .accessibilityIdentifier("muster-fixture-note")
                Button("Queue text reply") {
                    noteFocused = false
                    Task { await fixture.enqueue(note: fixture.note, voice: false) }
                }
                .disabled(fixture.note.isEmpty || fixture.busy || !fixture.canQueue)
                .accessibilityIdentifier("muster-fixture-submit-text")
                Button("Queue synthetic voice reply") {
                    Task { await fixture.enqueue(note: "", voice: true) }
                }
                .disabled(fixture.busy || !fixture.canQueue)
                .accessibilityIdentifier("muster-fixture-submit-voice")
                if fixture.useHTTPS {
                    Button("Check QA server") { Task { await fixture.checkServer() } }
                        .disabled(fixture.busy || !fixture.canQueue)
                        .accessibilityIdentifier("muster-fixture-probe")
                } else {
                    Button("Restore fixture connection on next foreground") { fixture.armReconnect() }
                        .disabled(fixture.pending == 0 || fixture.busy)
                        .accessibilityIdentifier("muster-fixture-reconnect")
                }
                Button("Clear this test run") { Task { await fixture.clear() } }
                    .disabled(fixture.busy || fixture.isDelivering)
                    .accessibilityIdentifier("muster-fixture-clear")
            }.padding()
        }
        .task { await fixture.load() }
        .onChange(of: scenePhase, initial: true) { _, phase in
            fixture.sceneChanged(phase)
        }
    }
}

@MainActor
private final class MusterOfflineFixture: ObservableObject {
    let useHTTPS: Bool
    private let https: MusterOfflineHTTPS?
    private var uid: String { https?.uid ?? "muster-fixture-user" }
    private var panel: String { https?.panelId ?? "muster-fixture-panel" }
    private var session: String { https?.sessionId ?? "muster-fixture-session" }
    private let config = MusterResponseConfig(version: 1, question: "", options: [], allowText: true, allowVoice: true)
    private let journalService: String
    private let voiceKeyService: String
    private let directory: URL
    private let files: MusterVoiceFiles
    private let queue: MusterQueue
    private var reconnectArmed = false
    private var didBackground = false
    private var phase = ScenePhase.active
    private var pathSatisfied = false
    private var pathMonitor: NWPathMonitor?
    private var retry: Task<Void, Never>?
    private var cleared = false

    @Published var note = ""
    @Published private(set) var busy = false
    @Published private(set) var pending = 0
    @Published private(set) var sent = 0
    @Published private(set) var attachments = 0
    @Published private(set) var received = 0
    @Published private(set) var savedText = ""
    @Published private(set) var status = "Loading fixture"
    @Published private(set) var pathDescription = "QA path unknown"
    @Published private(set) var deliveryDescription = "HTTPS receipts: none"
    @Published private(set) var serverVoiceCount = 0
    @Published private(set) var isDelivering = false

    var canQueue: Bool { !useHTTPS || https?.isActive == true }

    init(runID: UUID, useHTTPS: Bool, configuredTransport: MusterOfflineHTTPS?) {
        self.useHTTPS = useHTTPS
        let transport = useHTTPS ? (configuredTransport ?? (try? MusterOfflineHTTPS.takeLaunchConfiguration())) : nil
        https = transport
        let namespace = "com.auralis.crisisconnect.muster-ui-test.\(transport?.sessionId ?? "local").\(runID.uuidString)"
        journalService = namespace + ".journal"
        voiceKeyService = namespace + ".voice"
        directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(namespace, isDirectory: true)
        files = MusterVoiceFiles(root: directory.appendingPathComponent("audio", isDirectory: true), keyService: voiceKeyService)
        queue = MusterQueue(storage: MusterKeychainStore(service: journalService), voiceFiles: files)
    }

    func load() async {
        await perform {
            self.status = self.useHTTPS ? (self.canQueue ? "HTTPS fixture ready" : "HTTPS fixture configuration unavailable") : "Fixture offline"
        }
        if useHTTPS, canQueue {
#if MUSTER_APP_LIVE_QA
            MusterAppLiveBackground.attach(self)
            await MusterAppLiveBackground.scheduleIfNeeded()
#endif
            startPathMonitor()
        }
    }

    func enqueue(note: String, voice: Bool) async {
        guard canQueue else { status = "HTTPS fixture configuration unavailable"; return }
        await perform {
            let recording = voice ? try self.syntheticRecording() : nil
            try await self.queue.enqueueConfigured(uid: self.uid, panel: self.panel, session: self.session,
                                                   config: self.config, optionId: nil, note: note, recording: recording)
            guard !self.useHTTPS else { return }
            // The synthetic test persists a connection failure without making a request.
            while let event = try await self.queue.claim(uid: self.uid) {
                try await self.queue.finish(event, state: "pending", error: "connection")
            }
        }
        if useHTTPS {
#if MUSTER_APP_LIVE_QA
            await MusterAppLiveBackground.scheduleIfNeeded()
#endif
            triggerHTTPSDrain(reason: "enqueue")
        }
    }

    func armReconnect() {
        reconnectArmed = true
        didBackground = false
        status = "Fixture reconnect armed"
    }

    func sceneChanged(_ phase: ScenePhase) {
        self.phase = phase
        if useHTTPS {
            logHTTPS("scene")
            if phase == .active { triggerHTTPSDrain(reason: "foreground") }
#if MUSTER_APP_LIVE_QA
            if phase != .active, MusterAppLiveBackground.registered {
                retry?.cancel()
                retry = nil
                if phase == .background { Task { await MusterAppLiveBackground.scheduleIfNeeded() } }
            }
#endif
            return
        }
        guard reconnectArmed else { return }
        if phase == .background { didBackground = true }
        if phase == .active, didBackground {
            reconnectArmed = false
            Task { await reconnect() }
        }
    }

    private func reconnect() async {
        await perform {
            let pending = try await self.queue.events(uid: self.uid).filter { $0.state == "pending" }
            guard let retryAt = pending.map(\.nextAt).max() else { throw MusterError.invalid }
            // Advance only the fixture's claim clock past the actual persisted backoff.
            let eligible = Date(timeIntervalSince1970: retryAt)
            while let event = try await self.queue.claim(uid: self.uid, now: eligible) {
                let body = try await self.queue.body(event)
                let receipt = try self.receive(event, body: body)
                // Replay the same request as if the first receipt were lost.
                guard try self.receive(event, body: body) == receipt else { throw MusterError.invalid }
                try await self.queue.acknowledge(event, status: 200, receipt: receipt)
            }
            self.status = "Fixture reconnected"
        }
    }

    private func startPathMonitor() {
        guard pathMonitor == nil else { return }
        let monitor = NWPathMonitor()
        pathMonitor = monitor
        monitor.pathUpdateHandler = { [weak self] path in
            let satisfied = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self, self.canQueue else { return }
                self.pathSatisfied = satisfied
                self.pathDescription = satisfied ? "QA path satisfied" : "QA path unavailable"
                self.logHTTPS(satisfied ? "path-satisfied" : "path-unavailable")
                if satisfied { self.triggerHTTPSDrain(reason: "path") }
                else { self.retry?.cancel(); self.retry = nil }
            }
        }
        monitor.start(queue: DispatchQueue(label: "muster-offline-fixture-path"))
    }

    private func triggerHTTPSDrain(reason: String) {
        guard pathSatisfied, !isDelivering, let https, https.isActive else { return }
        retry?.cancel()
        retry = nil
        isDelivering = true
        logHTTPS("drain-\(reason)")
        Task { _ = await drainHTTPS(https) }
    }

    private func drainHTTPS(_ https: MusterOfflineHTTPS, backgroundTask: Bool = false) async -> Bool {
        var accepted = false
        var completed = false
        do {
            for _ in 0..<16 {
                guard !Task.isCancelled, https.isActive, backgroundTask || pathSatisfied,
                      let event = try await queue.claim(uid: uid) else { break }
                logHTTPS("request", event: event)
                do {
                    let body = try await queue.body(event)
                    let (code, receipt) = try await https.send(event, body: body)
                    if acceptsMusterReceipt(status: code, data: receipt, session: session, event: event) {
                        try await queue.acknowledge(event, status: code, receipt: receipt)
                        logHTTPS("receipt", event: event, status: code)
                        deliveryDescription = "Last HTTPS receipt: \(phaseLabel)"
                        accepted = true
                    } else {
                        logHTTPS("http-error", event: event, status: code)
                        let permanent = (300..<500).contains(code) && ![408, 425, 429].contains(code)
                        try await queue.finish(event, state: permanent ? "failed" : "pending", error: "http-\(code)")
                        if code == 401 { https.close() }
                    }
                } catch {
                    logHTTPS("connection-error", event: event)
                    try await queue.finish(event, state: "pending", error: "connection")
                }
            }
            try await refreshSnapshot()
            completed = pending == 0 && !Task.isCancelled
            if accepted, !backgroundTask { try? await loadServerStatus(https) }
        } catch {
            status = "HTTPS fixture error"
        }
        isDelivering = false
        if !backgroundTask {
            await scheduleHTTPSRetry()
#if MUSTER_APP_LIVE_QA
            await MusterAppLiveBackground.scheduleIfNeeded()
#endif
        }
        return completed
    }

    private func scheduleHTTPSRetry() async {
#if MUSTER_APP_LIVE_QA
        if phase != .active, MusterAppLiveBackground.registered { return }
#endif
        guard canQueue, pathSatisfied,
              let events = try? await queue.events(uid: uid), !isDelivering,
              let nextAt = events.filter({ $0.state == "pending" }).map(\.nextAt).min() else { return }
        retry?.cancel()
        let delay = max(0.25, nextAt - Date().timeIntervalSince1970)
        retry = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            if !Task.isCancelled { self?.triggerHTTPSDrain(reason: "retry") }
        }
    }

#if MUSTER_APP_LIVE_QA
    fileprivate func nextBackgroundAttempt() async -> TimeInterval? {
        guard canQueue, let entries = try? await queue.events(uid: uid) else { return nil }
        return entries.filter { $0.state == "pending" }.map(\.nextAt).min()
    }

    fileprivate func runBackgroundDelivery() async -> Bool {
        guard let https, https.isActive else { return true }
        guard !isDelivering else { return false }
        retry?.cancel()
        retry = nil
        isDelivering = true
        logHTTPS("bg-task-start")
        let completed = await drainHTTPS(https, backgroundTask: true)
        logHTTPS(completed ? "bg-task-complete" : "bg-task-incomplete")
        return completed
    }
#endif

    func checkServer() async {
        guard let https else { return }
        await perform {
            try await self.loadServerStatus(https)
            self.status = "HTTPS server checked"
        }
    }

    private func loadServerStatus(_ https: MusterOfflineHTTPS) async throws {
        let evidence = try await https.status()
        received = evidence.eventCount
        serverVoiceCount = evidence.voiceCount
        for event in evidence.events {
            logHTTPS("server-event", eventId: event.eventId, serverAtMillis: event.receivedAtMillis)
        }
    }

    private var phaseLabel: String {
        switch phase {
        case .active: return "foreground"
        case .background: return "background"
        case .inactive: return "inactive"
        @unknown default: return "unknown"
        }
    }

    private struct HTTPSObservation: Codable {
        let atMillis: Int64
        let phase: String
        let kind: String
        let eventId: String?
        let httpStatus: Int?
        let serverAtMillis: Int64?
    }

    fileprivate func logHTTPS(_ kind: String, event: MusterEvent? = nil, eventId: String? = nil, status: Int? = nil,
                          serverAtMillis: Int64? = nil) {
        guard useHTTPS, https != nil, !cleared else { return }
        let file = directory.appendingPathComponent("https-timeline.json")
        let entry = HTTPSObservation(atMillis: Int64(Date().timeIntervalSince1970 * 1000), phase: phaseLabel,
                                     kind: kind, eventId: event?.serverEventId ?? eventId, httpStatus: status,
                                     serverAtMillis: serverAtMillis)
        var entries = (try? JSONDecoder().decode([HTTPSObservation].self, from: Data(contentsOf: file))) ?? []
        entries.append(entry)
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(Array(entries.suffix(64))).write(to: file, options: .atomic)
        } catch {
            self.status = "HTTPS timeline could not be saved"
        }
    }

    private var ledgerFile: URL { directory.appendingPathComponent("receipts.json") }

    private func ledger() throws -> [String: String] {
        guard FileManager.default.fileExists(atPath: ledgerFile.path) else { return [:] }
        return try JSONDecoder().decode([String: String].self, from: Data(contentsOf: ledgerFile))
    }

    private func receive(_ event: MusterEvent, body: Data) throws -> Data {
        let id = event.serverEventId
        let digest = MusterVoiceFiles.digest(body)
        var entries = try ledger()
        if let original = entries[id] {
            guard original == digest else { throw MusterError.invalid }
        } else {
            entries[id] = digest
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try JSONEncoder().encode(entries).write(to: ledgerFile, options: .atomic)
        }
        return try JSONSerialization.data(withJSONObject: ["ok": true, "id": session, "eventId": id], options: .sortedKeys)
    }

    private func syntheticRecording() throws -> MusterVoiceRecording {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("fixture-tone.m4a")
        defer { try? FileManager.default.removeItem(at: url) }
        do {
            let file = try AVAudioFile(forWriting: url, settings: MusterAudio.recordingSettings)
            guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 16_000),
                  let samples = buffer.floatChannelData?[0] else { throw MusterError.invalid }
            buffer.frameLength = 16_000
            for index in 0..<16_000 { samples[index] = Float(sin(Double(index) * 2 * .pi * 440 / 16_000) * 0.05) }
            try file.write(from: buffer)
        }
        return try MusterVoiceRecording(data: Data(contentsOf: url), durationMillis: 1_000)
    }

    func clear() async {
        await perform {
#if MUSTER_APP_LIVE_QA
            MusterAppLiveBackground.detach(self)
#endif
            self.cleared = true
            self.https?.close()
            self.pathMonitor?.cancel()
            self.retry?.cancel()
            for (service, account) in [(self.journalService, "accounts"), (self.voiceKeyService, "encryption-key")] {
                let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: account]
                let result = SecItemDelete(query as CFDictionary)
                guard result == errSecSuccess || result == errSecItemNotFound else { throw MusterError.persistence }
            }
            if FileManager.default.fileExists(atPath: self.directory.path) { try FileManager.default.removeItem(at: self.directory) }
            self.status = "Fixture cleared"
        }
    }

    private func perform(_ work: () async throws -> Void) async {
        guard !busy else { return }
        busy = true
        defer { busy = false }
        do {
            try await work()
            try await refreshSnapshot()
        } catch {
            status = useHTTPS ? "HTTPS fixture error" : "Fixture error: \(error)"
        }
    }

    private func refreshSnapshot() async throws {
        let events = try await queue.events(uid: uid)
        pending = events.filter { $0.state == "pending" }.count
        sent = events.filter { $0.state == "sent" }.count
        savedText = events.last(where: { !$0.note.isEmpty })?.note ?? ""
        let audioRoot = directory.appendingPathComponent("audio", isDirectory: true)
        let sealedCount = FileManager.default.enumerator(at: audioRoot, includingPropertiesForKeys: nil)?
            .allObjects.compactMap { $0 as? URL }.filter { $0.pathExtension == "sealed" }.count ?? 0
        attachments = sealedCount
        if !useHTTPS {
            var retainedVoice = 0
            for event in events where event.state != "sent" && event.voice != nil {
                if try await queue.voiceData(event) != nil { retainedVoice += 1 }
            }
            guard retainedVoice == sealedCount else { throw MusterError.persistence }
            received = try ledger().count
        }
    }
}

#if MUSTER_APP_LIVE_QA
@MainActor
enum MusterAppLiveBackground {
    private(set) static var registered = false
    private static weak var fixture: MusterOfflineFixture?

    static func register() {
        let identifiers = Bundle.main.object(forInfoDictionaryKey: "BGTaskSchedulerPermittedIdentifiers") as? [String]
        let modes = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") as? [String]
        guard identifiers?.contains(MusterBackgroundDelivery.identifier) == true, modes?.contains("processing") == true else { return }
        registered = MusterBackgroundDelivery.register(drain: { await drain() }, reschedule: {
            Task { await scheduleIfNeeded() }
        })
    }

    fileprivate static func attach(_ next: MusterOfflineFixture) {
        fixture = next
        next.logHTTPS(registered ? "bg-task-registered" : "bg-task-unavailable")
    }

    fileprivate static func detach(_ current: MusterOfflineFixture) {
        if fixture === current { detach() }
    }

    static func detach() {
        fixture = nil
        if registered { MusterBackgroundDelivery.cancel() }
    }

    fileprivate static func scheduleIfNeeded() async {
        guard registered, let current = fixture, let nextAt = await current.nextBackgroundAttempt(),
              fixture === current, current.canQueue else { return }
        do {
            try MusterBackgroundDelivery.submit(nextAt: nextAt)
            current.logHTTPS("bg-task-scheduled")
        } catch {
            current.logHTTPS("bg-task-submit-error")
        }
    }

    private static func drain() async -> Bool {
        guard let fixture else { return true }
        return await fixture.runBackgroundDelivery()
    }
}
#endif
#endif
