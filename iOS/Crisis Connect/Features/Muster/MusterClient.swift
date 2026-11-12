import Foundation
import FirebaseAuth
import FirebaseFirestore

extension Notification.Name { static let musterStateChanged = Notification.Name("personnelMusterStateChanged") }

/// Automatic home discovery is event-driven, never a polling loop. Admission happens before any network read.
struct MusterHomeDiscoveryGate: Sendable {
    static let interval: TimeInterval = 60
    static let maximumAccounts = 32
    private struct Attempt: Sendable { let uptime: TimeInterval; var inFlight: UUID? }
    private var attempts: [String: Attempt] = [:]
    var retainedAccountCount: Int { attempts.count }
    mutating func begin(uid: String, uptime: TimeInterval) -> UUID? {
        guard !uid.isEmpty, uptime.isFinite, uptime >= 0 else { return nil }
        if let previous = attempts[uid] {
            guard previous.inFlight == nil, uptime - previous.uptime >= Self.interval else { return nil }
        } else if attempts.count >= Self.maximumAccounts {
            guard let oldest = attempts.filter({ $0.value.inFlight == nil }).min(by: { $0.value.uptime < $1.value.uptime })?.key else { return nil }
            attempts.removeValue(forKey: oldest)
        }
        let ticket = UUID(); attempts[uid] = Attempt(uptime: uptime, inFlight: ticket); return ticket
    }
    mutating func finish(uid: String, ticket: UUID) {
        guard attempts[uid]?.inFlight == ticket else { return }
        attempts[uid]?.inFlight = nil
    }
}

actor MusterClient {
    static let shared = MusterClient()
    private let queue = MusterQueue.shared
    private var draining = false
    private var reconnectAccount: String?
    private var syncedLanguages: [String: String] = [:]
    private var homeDiscovery = MusterHomeDiscoveryGate()
    private let network: URLSession = {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20; config.timeoutIntervalForResource = 30
        return URLSession(configuration: config)
    }()
    // Slow audio must not restart a nearly complete upload because of the small status-request budget.
    // This is a bounded, on-demand session; it adds no polling and leaves normal reply latency unchanged.
    private let audioNetwork: URLSession = {
        let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 60; config.timeoutIntervalForResource = 180
        return URLSession(configuration: config)
    }()
    private func request(uid: String, panel: String, session: String? = nil, after: String? = nil, event: MusterEvent? = nil, language: String? = nil) async throws -> (Int, Data) {
        guard let user = Auth.auth().currentUser, user.uid == uid, !user.isAnonymous else { throw MusterError.accountChanged }
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard var url = URLComponents(string: raw.isEmpty ? "https://crisisconnect.network" : raw), url.scheme == "https", url.user == nil, url.password == nil else { throw MusterError.invalid }
        url.path = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/api/mobile/muster"
        if !url.path.hasPrefix("/") { url.path = "/" + url.path }
        url.query = nil; url.fragment = nil
        if event == nil && language == nil { url.queryItems = [URLQueryItem(name: "panelId", value: panel)] + (session.map { [URLQueryItem(name: "sessionId", value: $0)] } ?? []) + (after.map { [URLQueryItem(name: "after", value: $0)] } ?? []) }
        guard let endpoint = url.url else { throw MusterError.invalid }
        func send(force: Bool) async throws -> (Int, Data) {
            let token: String = try await withCheckedThrowingContinuation { continuation in
                user.getIDTokenForcingRefresh(force) { token, error in
                    if let token { continuation.resume(returning: token) }
                    else { continuation.resume(throwing: error ?? MusterError.accountChanged) }
                }
            }
            guard Auth.auth().currentUser?.uid == uid else { throw MusterError.accountChanged }
            var request = URLRequest(url: endpoint); request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            if let event { request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type"); request.httpBody = try await queue.body(event) }
            else if let language {
                request.httpMethod = "POST"; request.setValue("application/json", forHTTPHeaderField: "Content-Type")
                request.httpBody = try JSONSerialization.data(withJSONObject: ["action": "set_language", "panelId": panel, "language": language])
            }
            let client = event?.voice == nil ? network : audioNetwork
            let (data, response) = try await client.data(for: request)
            guard let http = response as? HTTPURLResponse, data.count <= 2_000_000 else { throw MusterError.invalid }
            return (http.statusCode, data)
        }
        let first = try await send(force: false)
        return first.0 == 401 ? try await send(force: true) : first
    }
    /// Quiet discovery lets a fresh installation find assignments even when their push channel is off.
    /// Manual refresh and push wakeups keep their existing immediate path, outside this automatic gate.
    func discoverForHome(expectedUid: String) async {
        guard !PlatformRuntime.isRunningTests, !Task.isCancelled,
              let user = Auth.auth().currentUser, !user.isAnonymous, user.uid == expectedUid,
              let ticket = homeDiscovery.begin(uid: expectedUid, uptime: ProcessInfo.processInfo.systemUptime) else { return }
        defer { homeDiscovery.finish(uid: expectedUid, ticket: ticket) }
        do { try await refresh(expectedUid: expectedUid) }
        catch { /* Nonmembers, offline devices and expired credentials do not add an error card to Home. */ }
    }
    func refresh(panel explicitPanel: String? = nil, session: String? = nil, expectedUid: String? = nil) async throws {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { throw MusterError.accountChanged }
        let uid = user.uid
        guard expectedUid == nil || expectedUid == uid else { throw MusterError.accountChanged }
        var panel = explicitPanel
        if panel == nil {
            do { let doc = try await Firestore.firestore().collection("users").document(uid).getDocument()
                panel = (doc.get("agencySlug") as? String) ?? (doc.get("panelId") as? String)
            } catch { panel = try await queue.assignments(uid: uid).last?.panelId }
        }
        guard let panel, !panel.isEmpty else { throw MusterError.rejected }
        struct Page: Decodable { let records: [MusterAssignment]; let nextCursor: String? }
        var after: String?; var seen = Set<String>()
        repeat {
            let (code, data) = try await request(uid: uid, panel: panel, session: session, after: after)
            guard (200..<300).contains(code), Auth.auth().currentUser?.uid == uid else { throw MusterError.rejected }
            let received: [MusterAssignment]
            if session != nil { received = [try JSONDecoder().decode(MusterAssignment.self, from: data)]; after = nil }
            else { let page = try JSONDecoder().decode(Page.self, from: data); received = page.records; after = page.nextCursor }
            try await queue.cache(uid: uid, panel: panel, received: received); changed()
            if let after { guard seen.insert(after).inserted, seen.count <= 20 else { throw MusterError.capacity } }
        } while after != nil
        Task { await self.synchronizeLanguagePreference(panel: panel) }
    }
    /// One account preference per language, independent of the safety-response outbox.
    /// Failure is retried on a later foreground refresh, never as a background polling job.
    func synchronizeLanguagePreference(panel explicitPanel: String? = nil) async {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return }
        let uid = user.uid
        let language = MusterLocalization.language(preference: UserDefaults.standard.string(forKey: "appLanguage") ?? "system")
        guard syncedLanguages[uid] != language else { return }
        do {
            var panel = explicitPanel
            if panel == nil { panel = try await queue.assignments(uid: uid).last?.panelId }
            if panel == nil {
                let document = try await Firestore.firestore().collection("users").document(uid).getDocument()
                panel = document.get("agencySlug") as? String ?? document.get("panelId") as? String
            }
            guard let panel, !panel.isEmpty, Auth.auth().currentUser?.uid == uid else { return }
            let (status, data) = try await request(uid: uid, panel: panel, language: language)
            let receipt = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            if (200..<300).contains(status), receipt?["ok"] as? Bool == true, receipt?["language"] as? String == language, Auth.auth().currentUser?.uid == uid {
                syncedLanguages[uid] = language
            }
        } catch { /* UI and encrypted safety responses work independently of this preference. */ }
    }
    func enqueue(uid: String, panel: String, session: String, answer: String, note: String = "") async throws {
        guard let user = Auth.auth().currentUser, user.uid == uid, !user.isAnonymous else { throw MusterError.accountChanged }
        if let config = try await queue.assignments(uid: uid).first(where: { $0.panelId == panel && $0.assignment.session.id == session })?.assignment.session.responseConfig {
            guard config.options.contains(where: { $0.id == answer && $0.meaning == answer && $0.label.isEmpty }) else { throw MusterError.invalid }
            try await queue.enqueueConfigured(uid: uid, panel: panel, session: session, config: config, optionId: answer, note: note)
        } else { try await queue.enqueue(uid: uid, panel: panel, session: session, answer: answer, note: note) }
        changed(); MusterBackgroundManager.shared.scheduleIfNeeded()
        Task { _ = await self.drain() }
    }
    func enqueueConfigured(uid: String, row: CachedMusterAssignment, optionId: String?, note: String, recording: MusterVoiceRecording?) async throws {
        guard let user = Auth.auth().currentUser, user.uid == uid, !user.isAnonymous else { throw MusterError.accountChanged }
        guard let config = row.assignment.session.responseConfig else { throw MusterError.invalid }
        try await queue.enqueueConfigured(uid: uid, panel: row.panelId, session: row.assignment.session.id, config: config, optionId: optionId, note: note, recording: recording)
        changed(); MusterBackgroundManager.shared.scheduleIfNeeded()
        Task { _ = await self.drain() }
    }
    /// Fetch only when the person asks to listen. Never expose a public attachment URL or prefetch audio.
    func voice(uid: String, panel: String, session: String, metadata: MusterVoiceMetadata) async throws -> Data {
        guard metadata.mimeType == "audio/mp4", (1...MusterVoiceFiles.maximumBytes).contains(metadata.bytes),
              (1...MusterVoiceFiles.maximumDuration).contains(metadata.durationMillis),
              metadata.eventId.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil else { throw MusterError.invalid }
        guard let user = Auth.auth().currentUser, user.uid == uid, !user.isAnonymous else { throw MusterError.accountChanged }
        let raw = (Bundle.main.object(forInfoDictionaryKey: "CRISIS_CONNECT_WEB_URL") as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard var url = URLComponents(string: raw.isEmpty ? "https://crisisconnect.network" : raw), url.scheme == "https", url.user == nil, url.password == nil else { throw MusterError.invalid }
        url.path = "/api/mobile/muster/voice"; url.fragment = nil
        url.queryItems = [URLQueryItem(name: "panelId", value: panel), URLQueryItem(name: "sessionId", value: session), URLQueryItem(name: "eventId", value: metadata.eventId)]
        guard let endpoint = url.url else { throw MusterError.invalid }
        for force in [false, true] {
            let token: String = try await withCheckedThrowingContinuation { continuation in
                user.getIDTokenForcingRefresh(force) { token, error in
                    if let token { continuation.resume(returning: token) }
                    else { continuation.resume(throwing: error ?? MusterError.accountChanged) }
                }
            }
            guard Auth.auth().currentUser?.uid == uid else { throw MusterError.accountChanged }
            var request = URLRequest(url: endpoint); request.cachePolicy = .reloadIgnoringLocalCacheData
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            let (stream, response) = try await audioNetwork.bytes(for: request)
            guard let http = response as? HTTPURLResponse else { throw MusterError.invalid }
            if http.statusCode == 401 && !force { continue }
            guard http.statusCode == 200, http.mimeType == "audio/mp4", response.expectedContentLength <= Int64(MusterVoiceFiles.maximumBytes) else { throw MusterError.rejected }
            var data = Data()
            for try await byte in stream {
                guard !Task.isCancelled, data.count < MusterVoiceFiles.maximumBytes else { throw MusterError.capacity }
                data.append(byte)
            }
            guard Auth.auth().currentUser?.uid == uid, data.count == metadata.bytes, MusterVoiceFiles.digest(data) == metadata.sha256 else { throw MusterError.rejected }
            return data
        }
        throw MusterError.rejected
    }
    func drain(reconnected: Bool = false) async -> Bool {
        guard let user = Auth.auth().currentUser, !user.isAnonymous else { return true }
        let uid = user.uid
        if reconnected { reconnectAccount = uid }
        guard !draining else { return false }
        draining = true
        defer {
            draining = false
            // A reconnect can arrive during the final queue read or the last allowed attempt.
            if let reconnectAccount, Auth.auth().currentUser?.uid == reconnectAccount {
                Task {
                    _ = await self.drain()
                    MusterBackgroundManager.shared.scheduleIfNeeded()
                }
            } else {
                reconnectAccount = nil
            }
        }
        do {
            for _ in 0..<16 {
                guard !Task.isCancelled, Auth.auth().currentUser?.uid == uid else { return false }
                if reconnectAccount == uid {
                    reconnectAccount = nil
                    try await queue.releaseConnectionBackoff(uid: uid)
                }
                guard let event = try await queue.claim(uid: uid) else {
                    if reconnectAccount == uid { continue }
                    break
                }
                do {
                    let (code, data) = try await request(uid: uid, panel: event.panelId, event: event)
                    let accepted = acceptsMusterReceipt(status: code, data: data, session: event.sessionId, event: event)
                    let permanent = (400..<500).contains(code) && ![401, 408, 425, 429].contains(code)
                    if accepted { try await queue.acknowledge(event, status: code, receipt: data) }
                    else { try await queue.finish(event, state: permanent ? "failed" : "pending", error: "http-\(code)") }
                } catch {
                    try await queue.finish(event, state: "pending", error: Self.isConnectionError(error) ? "connection" : "request")
                }
                changed()
            }
            return try await queue.events(uid: uid).allSatisfy { $0.state != "pending" }
        } catch { return false }
    }
    private static func isConnectionError(_ error: Error) -> Bool {
        let cause = error as NSError
        let transport = cause.domain == NSURLErrorDomain ? cause : cause.userInfo[NSUnderlyingErrorKey] as? NSError
        guard let transport, transport.domain == NSURLErrorDomain else { return false }
        let code = URLError.Code(rawValue: transport.code)
        return code == .notConnectedToInternet || code == .networkConnectionLost || code == .dataNotAllowed
    }
    func receivePush(_ info: [AnyHashable: Any]) async -> Bool {
        guard let payload = MusterNotificationCenter.payload(info) else { return false }
        do { try await queue.cacheWake(uid: payload.uid, panel: payload.panel, session: payload.session, kind: payload.kind); changed()
            try? await refresh(panel: payload.panel, session: payload.session); return true
        } catch { return false }
    }
    private func changed() { NotificationCenter.default.post(name: .musterStateChanged, object: nil) }
}
