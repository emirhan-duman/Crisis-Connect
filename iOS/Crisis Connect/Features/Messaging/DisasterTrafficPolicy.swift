import Foundation

/// Evidence of a costly connection, never a claim to detect a disaster or cell occupancy.
struct DisasterTrafficPolicy {
    enum Mode { case normal, restrictedNetwork, sos, degradedCellular }
    var restricted = false
    var cellular = false
    var emergencyActive = false
    var emergencyUntil: TimeInterval = 0
    private var unhealthy = 0
    private var healthy = 0
    private var degraded = false
    private var recoveryAfter: TimeInterval = 0
    private var restrictedUntil: TimeInterval = 0

    mutating func updateNetwork(restricted: Bool, cellular: Bool, now: TimeInterval) {
        if self.restricted && !restricted { restrictedUntil = now + 60 }
        self.restricted = restricted
        self.cellular = cellular
    }

    func mode(now: TimeInterval) -> Mode {
        if restricted || now < restrictedUntil { return .restrictedNetwork }
        if emergencyActive || now < emergencyUntil { return .sos }
        return degraded ? .degradedCellular : .normal
    }

    mutating func record(now: TimeInterval, succeeded: Bool, duration: TimeInterval, transientFailure: Bool = true) {
        guard succeeded || transientFailure, cellular || degraded else { return }
        if !succeeded || duration >= 8 {
            healthy = 0
            unhealthy += succeeded ? 1 : 2
            if unhealthy >= 4 {
                degraded = true
                recoveryAfter = now + 300
            }
        } else {
            unhealthy = 0
            healthy += 1
            if healthy >= 2 && now >= recoveryAfter { degraded = false }
        }
    }

    mutating func stopEmergency(now: TimeInterval) {
        guard emergencyActive else { return } // Ordinary Bluetooth hosting must not trigger SOS mode.
        emergencyActive = false
        emergencyUntil = now + 300
    }

    static func allowsCompact(template: Int, text: String) -> Bool {
        [0, 202, 203, 204, 205, 208].contains(template) && text.utf8.count <= 1_024
    }

    static func isAttachmentChunk(_ id: String) -> Bool {
        id.range(of: #"\.\d{1,8}$"#, options: .regularExpression) != nil
    }
}
