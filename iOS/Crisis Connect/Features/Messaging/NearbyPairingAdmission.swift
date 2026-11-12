import Foundation

/// Bound SPAKE password guesses across rotating BLE identifiers and within each connection.
struct NearbyPairingAdmission {
    private var attempts: [(UUID, TimeInterval)] = []
    mutating func admit(_ central: UUID, now: TimeInterval) -> Bool {
        attempts.removeAll { now < $0.1 || now - $0.1 >= 60 }
        guard attempts.count < 12, attempts.filter({ $0.0 == central }).count < 3 else { return false }
        attempts.append((central, now))
        return true
    }
}
