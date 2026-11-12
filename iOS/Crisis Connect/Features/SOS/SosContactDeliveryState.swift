import CoreLocation
import Foundation

/// The selected recipients and their stable alert IDs survive an offline start or a process death.
/// Successful location delivery is tracked separately for each recipient for this running session.
struct SosContactDeliveryState {
    let messageIDs: [String: String]
    private(set) var sentMessageIDs: [String: String]
    private var lastLocations: [String: CLLocationCoordinate2D] = [:]

    init(resuming: Bool, selectedIDs: [String], messageIDs: [String: String], sentMessageIDs: [String: String]) {
        let restored = resuming ? messageIDs.merging(sentMessageIDs) { _, sent in sent } : [:]
        self.messageIDs = restored.isEmpty
            ? Dictionary(selectedIDs.map { ($0, UUID().uuidString) }, uniquingKeysWith: { first, _ in first })
            : restored
        self.sentMessageIDs = resuming ? sentMessageIDs : [:]
    }

    func isPending(_ recipientID: String) -> Bool {
        messageIDs[recipientID] != nil && sentMessageIDs[recipientID] == nil
    }

    mutating func markAlertSent(_ recipientID: String, location: CLLocationCoordinate2D?) {
        guard let messageID = messageIDs[recipientID] else { return }
        sentMessageIDs[recipientID] = messageID
        lastLocations[recipientID] = location
    }

    func needsLocationUpdate(_ recipientID: String, location: CLLocationCoordinate2D) -> Bool {
        guard sentMessageIDs[recipientID] != nil else { return false }
        guard let previous = lastLocations[recipientID] else { return true }
        return CLLocation(latitude: previous.latitude, longitude: previous.longitude)
            .distance(from: CLLocation(latitude: location.latitude, longitude: location.longitude)) >= 25
    }

    mutating func markLocationSent(_ recipientID: String, location: CLLocationCoordinate2D) {
        lastLocations[recipientID] = location
    }
}
