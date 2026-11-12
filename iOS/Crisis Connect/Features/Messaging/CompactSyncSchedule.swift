import Foundation

/// Monotonic request budget, retained across foreground resumes; no active speed tests.
struct CompactSyncSchedule {
    private var lastAttempt: TimeInterval = -30
    private var nextAttempt: TimeInterval = 0
    private var failures = 0
    mutating func reserve(now: TimeInterval) -> Bool {
        guard now >= nextAttempt, now - lastAttempt >= 30 else { return false }
        lastAttempt = now
        nextAttempt = now + 30
        return true
    }
    mutating func succeeded(now: TimeInterval, moreWork: Bool, jitter: TimeInterval) {
        failures = 0
        nextAttempt = max(lastAttempt + 30, now + (moreWork ? 30 : 120 + min(60,max(0,jitter))))
    }
    mutating func failed(now: TimeInterval, jitter: TimeInterval) {
        failures = min(5, failures + 1)
        let base = min(240,30 * pow(2,Double(failures - 1)))
        nextAttempt = now + min(300,base + min(base / 4,max(0,jitter)))
    }
    mutating func deferUntil(now: TimeInterval, delay: TimeInterval) {
        nextAttempt = max(nextAttempt,now + min(300,max(30,delay)))
    }
}
