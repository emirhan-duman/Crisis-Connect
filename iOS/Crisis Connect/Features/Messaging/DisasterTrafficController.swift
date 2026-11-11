import Combine
import Foundation
import Network
import FirebaseFunctions
import FirebaseFirestore

extension Notification.Name {
    static let disasterTrafficChanged = Notification.Name("DisasterTrafficChanged")
}

final class DisasterTrafficController: ObservableObject, @unchecked Sendable {
    static let shared = DisasterTrafficController()
    @Published private(set) var saving = false
    @Published private(set) var presence: TrafficPresence = .normal
    private let lock = NSLock()
    private var policy = DisasterTrafficPolicy()
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "messaging.traffic.policy")
    private var timer: DispatchSourceTimer?
    private var reportedSaving = false
    private var reportedPresence: TrafficPresence = .normal
    private var online = true
    private var serverRetry: TimeInterval = 30
    private var serverHoldUntil: TimeInterval = 0
    private var recoveryPacingUntil: TimeInterval = 0
    private var foregroundState = false
    private let gate = CompactSendGate()

    private init() {
        let remaining = UserDefaults.standard.double(forKey: "messaging.emergencyUntil") - Date().timeIntervalSince1970
        policy.emergencyUntil = ProcessInfo.processInfo.systemUptime + min(1_800, max(0, remaining))
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            self.lock.lock()
            self.online = path.status == .satisfied
            var restricted = path.isConstrained
            if #available(iOS 26.0, *) { restricted = restricted || path.isUltraConstrained }
            self.policy.updateNetwork(restricted: restricted, cellular: path.usesInterfaceType(.cellular), now: ProcessInfo.processInfo.systemUptime)
            self.lock.unlock()
            self.publish()
        }
        monitor.start(queue: queue)
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 15)
        timer.setEventHandler { [weak self] in self?.publish() }
        timer.resume()
        self.timer = timer
    }

    var isSaving: Bool {
        lock.lock(); defer { lock.unlock() }
        return policy.mode(now: ProcessInfo.processInfo.systemUptime) != .normal
    }
    var foreground: Bool { lock.lock(); defer { lock.unlock() }; return foregroundState }
    func setForeground(_ active: Bool) {
        lock.lock(); foregroundState = active; lock.unlock()
        if !active { TrafficMeasurements.shared.checkpoint() }
    }
    func permits(_ work: NetworkWork, userInitiated: Bool = false) -> Bool {
        TrafficBudget.permits(work, saving: isSaving, userInitiated: userInitiated)
    }
    var retryAfter: TimeInterval { lock.lock(); defer { lock.unlock() }; return serverRetry }
    func applyServerPolicy(_ data: [String: Any]?) {
        lock.lock(); defer { lock.unlock() }
        serverRetry = min(300,max(30,((data?["retryAfterMs"] as? NSNumber)?.doubleValue ?? 30_000) / 1000))
    }

    func setEmergency(_ active: Bool) {
        lock.lock()
        if !active && !policy.emergencyActive { lock.unlock(); return }
        if active { policy.emergencyActive = true }
        else { policy.stopEmergency(now: ProcessInfo.processInfo.systemUptime) }
        lock.unlock()
        UserDefaults.standard.set(Date().timeIntervalSince1970 + (active ? 1_800 : 300), forKey: "messaging.emergencyUntil")
        publish()
    }

    func record(succeeded: Bool, duration: TimeInterval, error: Error? = nil) {
        let ns = error as NSError?
        let transient = ns == nil || ns?.domain == NSURLErrorDomain ||
            (ns?.domain == FunctionsErrorDomain && [4, 8, 14].contains(ns?.code ?? -1)) ||
            (ns?.domain == FirestoreErrorDomain && [4, 8, 14].contains(ns?.code ?? -1))
        lock.lock()
        if let details = ns?.userInfo[FunctionsErrorDetailsKey] as? [String: Any],
           let delay = details["retryAfterMs"] as? NSNumber {
            serverHoldUntil = max(serverHoldUntil, ProcessInfo.processInfo.systemUptime + min(300,max(30,delay.doubleValue / 1000)))
        }
        policy.record(now: ProcessInfo.processInfo.systemUptime, succeeded: succeeded, duration: duration, transientFailure: transient)
        lock.unlock()
        publish()
    }

    private func publish() {
        lock.lock()
        let next = policy.mode(now: ProcessInfo.processInfo.systemUptime) != .normal
        let presence = TrafficBudget.presence(online: online, emergency: policy.emergencyActive, saving: next)
        if reportedSaving && !next { recoveryPacingUntil = ProcessInfo.processInfo.systemUptime + 60 }
        let changed = next != reportedSaving || presence != reportedPresence
        reportedSaving = next
        reportedPresence = presence
        lock.unlock()
        guard changed else { return }
        DispatchQueue.main.async { [weak self] in
            self?.saving = next
            self?.presence = presence
            TrafficMeasurements.shared.transition(presence.rawValue)
            NotificationCenter.default.post(name: .disasterTrafficChanged, object: nil)
        }
    }

    func allowsText(template: Int, text: String) -> Bool {
        !isSaving || DisasterTrafficPolicy.allowsCompact(template: template, text: text)
    }

    func awaitSendSlot(emergency: Bool) async throws {
        lock.lock(); let remaining = serverHoldUntil - ProcessInfo.processInfo.systemUptime; lock.unlock()
        if !emergency, remaining > 0 { try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000)) }
        lock.lock(); let recovering = ProcessInfo.processInfo.systemUptime < recoveryPacingUntil; lock.unlock()
        guard !emergency, isSaving || recovering else { return }
        try await gate.wait()
    }
}

private actor CompactSendGate {
    private var nextSendAt: TimeInterval = 0
    private var running = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async throws {
        while running {
            await withCheckedContinuation { waiters.append($0) }
            try Task.checkCancellation()
        }
        running = true
        defer {
            running = false
            if !waiters.isEmpty { waiters.removeFirst().resume() }
        }
        let now = ProcessInfo.processInfo.systemUptime
        if nextSendAt > now { try await Task.sleep(nanoseconds: UInt64((nextSendAt - now) * 1_000_000_000)) }
        try Task.checkCancellation()
        // Set the next slot AFTER waking, so app suspension cannot turn reserved slots into a burst.
        nextSendAt = ProcessInfo.processInfo.systemUptime + 5 + Double.random(in: 0...2)
    }
}
