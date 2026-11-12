import Foundation

enum MessagingDatabaseTarget {
    // Enabled only after the live mixed-version and isolated-region acceptance gates pass.
    static let highAvailabilityEnabled = true
    static let databaseID = "messaging-live-eur3"
}
