import Foundation

enum TrafficPresence: String { case normal, limited, offline, sos }
enum NetworkWork { case message, receipt, sos, avatar, mapDownload, feedRefresh, telemetry }
enum TrafficBudget {
    static func permits(_ work: NetworkWork, saving: Bool, userInitiated: Bool = false) -> Bool {
        switch work {
        case .message, .receipt, .sos: return true
        case .feedRefresh: return !saving || userInitiated
        default: return !saving
        }
    }
    static func presence(online: Bool, emergency: Bool, saving: Bool) -> TrafficPresence {
        if emergency { return .sos }
        if !online { return .offline }
        return saving ? .limited : .normal
    }
}

/// Local-only diagnostics. URLSession counters cover instrumented sessions, NOT all app/FCM traffic.
final class TrafficMeasurements: @unchecked Sendable {
    static let shared = TrafficMeasurements()
    private let lock = NSLock()
    private var mode = "normal"
    private var started = ProcessInfo.processInfo.systemUptime
    private var requests: Int64 = 0, failures: Int64 = 0, jsonBytes: Int64 = 0
    private var urlSent: Int64 = 0, urlReceived: Int64 = 0
    func request(succeeded: Bool, responseJsonBytes: Int = 0) {
        lock.lock(); defer { lock.unlock() }
        requests += 1; if !succeeded { failures += 1 }; jsonBytes += Int64(max(0,responseJsonBytes))
    }
    func urlSession(sent: Int64, received: Int64) {
        lock.lock(); defer { lock.unlock() }
        urlSent += max(0,sent); urlReceived += max(0,received)
    }
    func transition(_ next: String) {
        lock.lock(); defer { lock.unlock() }
        guard mode != next else { return }
        checkpointLocked(); mode = next
    }
    func checkpoint() { lock.lock(); defer { lock.unlock() }; checkpointLocked() }
    private func checkpointLocked() {
        let defaults = UserDefaults.standard
        var history = defaults.array(forKey: "messaging.trafficMeasurements") as? [[String: Any]] ?? []
        history.append(["mode": mode, "recordedAtMs": Date().timeIntervalSince1970 * 1000,
            "durationMs": max(0,ProcessInfo.processInfo.systemUptime - started) * 1000,
            "requests": requests, "failures": failures, "responseJsonBytes": jsonBytes,
            "observedUrlSessionBytesSent": urlSent, "observedUrlSessionBytesReceived": urlReceived])
        defaults.set(Array(history.suffix(48)), forKey: "messaging.trafficMeasurements")
        started = ProcessInfo.processInfo.systemUptime
        requests = 0; failures = 0; jsonBytes = 0; urlSent = 0; urlReceived = 0
    }
}

final class TrafficSessionDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    static let shared = TrafficSessionDelegate()
    func urlSession(_ session: URLSession, task: URLSessionTask, didFinishCollecting metrics: URLSessionTaskMetrics) {
        TrafficMeasurements.shared.urlSession(
            sent: metrics.transactionMetrics.reduce(0) { $0 + $1.countOfRequestHeaderBytesSent + $1.countOfRequestBodyBytesSent },
            received: metrics.transactionMetrics.reduce(0) { $0 + $1.countOfResponseHeaderBytesReceived + $1.countOfResponseBodyBytesReceived })
    }
}
